// root_runtime.h

#pragma once

#include "common_config.h"
#include "engine.h"
#include "shm_layout.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <stop_token>
#include <thread>
#include <vector>

namespace Sphere {

using Sphere::CACHE_LINE_SIZE;
using Sphere::MAX_RUNTIMES;

class RootRuntime {
public:
  explicit RootRuntime(bool create_shm);
  ~RootRuntime();

  RootRuntime(const RootRuntime &) = delete;
  RootRuntime &operator=(const RootRuntime &) = delete;
  RootRuntime(RootRuntime &&) noexcept = delete;
  RootRuntime &operator=(RootRuntime &&) noexcept = delete;

  void start();
  void stop();

  void attach_cluster_runtime(ShmLayout &secondary_layout);
  void detach_cluster_runtime(ShmLayout &secondary_layout);

  [[nodiscard]] ShmLayout &shm_layout() noexcept { return layout_; }
  [[nodiscard]] const ShmLayout &shm_layout() const noexcept { return layout_; }

  [[nodiscard]] float backpressure_level() const noexcept {
    return backpressure_level_.load(std::memory_order_relaxed);
  }

  void set_manual_backpressure_override(float level) noexcept {
    manual_backpressure_override_.store(level, std::memory_order_relaxed);
  }

  void clear_manual_backpressure_override() noexcept {
    manual_backpressure_override_.store(-1.0f, std::memory_order_relaxed);
  }

private:
  /**
   * Immutable cluster view for RCU-style concurrent snapshot updates.
   */
  struct ClusterSnapshot {
    std::vector<ShmLayout *> layouts;
  };

  /**
   * Double Exponential Smoothing (Holt-Winters) predictor for dynamic memory
   * forecasting.
   */
  struct HoltPredictor {
    float level{0.0f};
    float trend{0.0f};
    float alpha{0.5f};
    float beta{0.3f};

    [[nodiscard]] float update_and_predict(float x, float horizon) noexcept {
      if (level == 0.0f && trend == 0.0f) {
        level = x;
        trend = 0.0f;
      }
      const float prev_level = level;
      level = alpha * x + (1.0f - alpha) * (level + trend);
      trend = beta * (level - prev_level) + (1.0f - beta) * trend;
      return level + horizon * trend;
    }
  };

  /**
   * Variance estimation
   */
  struct VarianceEstimator {
    float mean_{0.0f};
    float m2_{0.0f};
    std::uint64_t count_{0};

    void update(float x) noexcept {
      count_++;
      const float delta = x - mean_;
      mean_ += delta / static_cast<float>(count_);
      const float delta2 = x - mean_;
      m2_ += delta * delta2;
    }

    [[nodiscard]] float variance() const noexcept {
      if (count_ < 2) {
        return 0.0f;
      }
      return m2_ / static_cast<float>(count_);
    }

    [[nodiscard]] float mean() const noexcept { return mean_; }
  };

  /**
   * Token Bucket rate limiter for offloading tasks during pressure spikes.
   */
  struct TokenBucketLimiter {
    std::uint32_t tokens{10};
    std::uint32_t max_tokens{10};
    std::uint64_t last_refill_tsc{0};

    [[nodiscard]] bool try_consume(std::uint64_t now_tsc,
                                   std::uint64_t tsc_per_sec) noexcept {
      if (last_refill_tsc == 0) {
        last_refill_tsc = now_tsc;
      }
      const std::uint64_t delta = now_tsc - last_refill_tsc;
      if (delta > tsc_per_sec) {
        const std::uint32_t refill =
            static_cast<std::uint32_t>(delta / tsc_per_sec);
        tokens = std::min(max_tokens, tokens + refill);
        last_refill_tsc = now_tsc;
      }
      if (tokens == 0) {
        return false;
      }
      tokens--;
      return true;
    }
  };

  void replay_journal();
  void runtime_loop(std::stop_token stop_token);

  void adaptive_wait(std::uint64_t target_ns, float pressure,
                     const std::stop_token &stop_token) noexcept;
  void update_stratified_prefetch(std::size_t current_seq,
                                  std::size_t prev_seq) noexcept;

  [[nodiscard]] float variance_simd(float base_variance) noexcept;
  void auto_tune_holt(float predictive_pressure, float jitter_ns) noexcept;
  void predictive_numa_migration(float predictive_pressure,
                                 float burst_delta) noexcept;
  void update_psi_metrics(float predictive_pressure,
                          float raw_occupancy) noexcept;
  [[nodiscard]] float ml_predict_burst(float raw_occupancy, float variance,
                                       float frag_score) noexcept;

  void update_cluster_telemetry(const ClusterSnapshot &snapshot) noexcept;
  void scheduler_cross_node_tick(const ClusterSnapshot &snapshot,
                                 float predictive_pressure,
                                 float high_threshold) noexcept;

  /**
   * Lock-free Read-Copy-Update (RCU) cluster topology mutator
   */
  template <typename ModifierFn>
  void update_cluster_snapshot(ModifierFn &&modifier_fn) {
    auto old_snapshot = std::atomic_load_explicit(&cluster_snapshot_,
                                                  std::memory_order_relaxed);
    while (true) {
      auto new_snapshot = std::make_shared<ClusterSnapshot>(*old_snapshot);
      modifier_fn(new_snapshot->layouts);
      if (std::atomic_compare_exchange_weak_explicit(
              &cluster_snapshot_, &old_snapshot, new_snapshot,
              std::memory_order_release, std::memory_order_relaxed)) {
        break;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Core Subsystems & Layouts (Isolated per cache line)
  // ---------------------------------------------------------------------------
  alignas(CACHE_LINE_SIZE) ShmLayout layout_;
  alignas(CACHE_LINE_SIZE) Engine engine_;

  alignas(CACHE_LINE_SIZE) std::atomic<bool> running_{false};
  alignas(CACHE_LINE_SIZE) std::atomic<float> backpressure_level_{0.0f};

  alignas(CACHE_LINE_SIZE)
      std::atomic<std::shared_ptr<const ClusterSnapshot>> cluster_snapshot_{
          std::make_shared<ClusterSnapshot>()};
  std::jthread runtime_thread_;

  // ---------------------------------------------------------------------------
  // Internal Control & Forecasting State
  // ---------------------------------------------------------------------------
  alignas(CACHE_LINE_SIZE) HoltPredictor predictor_;
  VarianceEstimator variance_estimator_;
  TokenBucketLimiter offload_limiter_;

  static constexpr std::size_t history_size_ = 8;
  // Padded to 16 floats (64 bytes) to prevent AVX-512 register mask overreads
  alignas(64) std::array<float, 16> history_pressures_{};
  std::size_t history_index_{0};

  std::atomic<std::uint64_t> control_loop_period_ns_{0};
  std::atomic<std::uint64_t> control_loop_jitter_ns_{0};
  std::atomic<std::uint32_t> rebalance_hysteresis_counter_{0};
  std::atomic<std::uint32_t> defrag_hysteresis_counter_{0};

  std::atomic<std::uint64_t> psi_pressure_ns_{0};
  std::atomic<std::uint64_t> psi_stall_ns_{0};

  std::atomic<float> manual_backpressure_override_{-1.0f};

  // Machine learning burst heuristic coefficients
  float ml_w1_{0.7f};
  float ml_w2_{0.2f};
  float ml_w3_{0.1f};
  float ml_bias_{0.0f};

  // PID Backpressure controller variables
  [[maybe_unused]] float pid_integral_{0.0f};
  [[maybe_unused]] float prev_occupancy_{0.0f};
};

} // namespace Sphere
