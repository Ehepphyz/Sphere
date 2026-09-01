// root_runtime.h

// Owns the shared-memory session and runs the adaptive control loop that sits
// above the Engine.

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

/**
 * Process-level runtime
 */
class RootRuntime {
public:
  explicit RootRuntime(bool create_shm, const char *region_name = SHM_NAME,
                       std::size_t region_size = SHM_SIZE,
                       bool force_format = false);
  ~RootRuntime();

  RootRuntime(const RootRuntime &) = delete;
  RootRuntime &operator=(const RootRuntime &) = delete;
  RootRuntime(RootRuntime &&) noexcept = delete;
  RootRuntime &operator=(RootRuntime &&) noexcept = delete;

  void start();
  void stop();

  [[nodiscard]] bool is_running() const noexcept {
    return running_.load(std::memory_order_acquire);
  }

  void attach_cluster_runtime(ShmLayout &secondary_layout);
  void detach_cluster_runtime(ShmLayout &secondary_layout);

  [[nodiscard]] ShmLayout &shm_layout() noexcept { return session_.layout(); }
  [[nodiscard]] const ShmLayout &shm_layout() const noexcept {
    return session_.layout();
  }

  [[nodiscard]] Engine &engine() noexcept { return engine_; }

  [[nodiscard]] float backpressure_level() const noexcept {
    return backpressure_level_.load(std::memory_order_relaxed);
  }

  /// Forces a backpressure value, for testing and manual throttling.
  void set_manual_backpressure_override(float level) noexcept {
    manual_backpressure_override_.store(level, std::memory_order_relaxed);
  }

  void clear_manual_backpressure_override() noexcept {
    manual_backpressure_override_.store(-1.0f, std::memory_order_relaxed);
  }

  /// Counters exposed for diagnostics.
  struct ControlStats {
    std::uint64_t loop_iterations{0};
    std::uint64_t rebalances{0};
    std::uint64_t tasks_migrated{0};
    std::uint64_t defrag_passes{0};
    std::uint64_t chunks_reclaimed{0};
    std::uint64_t journal_replayed{0};
  };

  [[nodiscard]] ControlStats control_stats() const noexcept;

private:
  /**
   * Immutable cluster view, swapped atomically.
   */
  struct ClusterSnapshot {
    std::vector<ShmLayout *> layouts;
  };

  /**
   * Holt double exponential smoothing.
   */
  struct HoltPredictor {
    float level{0.0f};
    float trend{0.0f};
    float alpha{0.5f};
    float beta{0.3f};
    bool initialized{false};

    [[nodiscard]] float update_and_predict(float x, float horizon) noexcept {
      if (!initialized) {
        level = x;
        trend = 0.0f;
        initialized = true;
        return x;
      }
      const float previous = level;
      level = alpha * x + (1.0f - alpha) * (level + trend);
      trend = beta * (level - previous) + (1.0f - beta) * trend;
      return level + horizon * trend;
    }

    void reset() noexcept {
      level = 0.0f;
      trend = 0.0f;
      initialized = false;
    }
  };

  /**
   * Welford variance over a bounded window.
   */
  struct VarianceEstimator {
    float mean_{0.0f};
    float m2_{0.0f};
    std::uint64_t count_{0};

    void update(float x) noexcept {
      constexpr std::uint64_t kMaxWeight = 4096;
      if (count_ < kMaxWeight) {
        ++count_;
      }
      const float delta = x - mean_;
      mean_ += delta / static_cast<float>(count_);
      m2_ += delta * (x - mean_);
    }

    /// Sample variance. The divisor is count - 1, matching the count < 2 guard
    [[nodiscard]] float variance() const noexcept {
      if (count_ < 2) {
        return 0.0f;
      }
      return m2_ / static_cast<float>(count_ - 1);
    }

    [[nodiscard]] float mean() const noexcept { return mean_; }
  };

  struct TokenBucketLimiter {
    std::uint32_t tokens{10};
    std::uint32_t max_tokens{10};
    std::uint64_t last_refill_tsc{0};

    [[nodiscard]] bool try_consume(std::uint64_t now_tsc,
                                   std::uint64_t tsc_per_sec) noexcept {
      if (tsc_per_sec == 0) {
        return false;
      }
      if (last_refill_tsc == 0 || now_tsc < last_refill_tsc) {
        last_refill_tsc = now_tsc;
      }

      const std::uint64_t delta = now_tsc - last_refill_tsc;
      if (delta >= tsc_per_sec) {
        const auto refill = static_cast<std::uint32_t>(delta / tsc_per_sec);
        tokens = std::min(max_tokens, tokens + refill);
        last_refill_tsc += static_cast<std::uint64_t>(refill) * tsc_per_sec;
      }

      if (tokens == 0) {
        return false;
      }
      --tokens;
      return true;
    }
  };

  void replay_journal();
  void runtime_loop(std::stop_token stop_token);

  void adaptive_wait(std::uint64_t target_ns, float pressure,
                     const std::stop_token &stop_token) noexcept;
  void update_stratified_prefetch(std::uint64_t current_seq,
                                  std::uint64_t prev_seq) noexcept;

  void auto_tune_holt(float predictive_pressure, float jitter_ns) noexcept;
  void update_psi_metrics(float predictive_pressure,
                          float raw_occupancy) noexcept;

  /**
   * Linear burst
   */
  [[nodiscard]] float burst_score(float raw_occupancy, float variance,
                                  float frag_score) const noexcept;

  void update_cluster_telemetry(const ClusterSnapshot &snapshot) noexcept;
  void scheduler_cross_node_tick(const ClusterSnapshot &snapshot,
                                 float predictive_pressure,
                                 float high_threshold) noexcept;

  /// Read-copy-update of the cluster topology.
  template <typename ModifierFn>
  void update_cluster_snapshot(ModifierFn &&modifier_fn) {
    auto old_snapshot = cluster_snapshot_.load(std::memory_order_acquire);
    for (;;) {
      auto new_snapshot = std::make_shared<ClusterSnapshot>(*old_snapshot);
      modifier_fn(new_snapshot->layouts);
      if (cluster_snapshot_.compare_exchange_weak(old_snapshot, new_snapshot,
                                                  std::memory_order_release,
                                                  std::memory_order_acquire)) {
        return;
      }
    }
  }
  ShmSession session_;
  alignas(CACHE_LINE_SIZE) Engine engine_;

  alignas(CACHE_LINE_SIZE) std::atomic<bool> running_{false};
  alignas(CACHE_LINE_SIZE) std::atomic<float> backpressure_level_{0.0f};

  std::atomic<std::shared_ptr<const ClusterSnapshot>> cluster_snapshot_{
      std::make_shared<ClusterSnapshot>()};

  std::jthread runtime_thread_;

  alignas(CACHE_LINE_SIZE) HoltPredictor predictor_;
  VarianceEstimator variance_estimator_;
  TokenBucketLimiter offload_limiter_;

  static constexpr std::size_t history_size_ = 8;
  // Padded to 16 floats so a masked 512-bit load never reads past the array.
  alignas(64) std::array<float, 16> history_pressures_{};
  std::size_t history_index_{0};

  std::atomic<std::uint64_t> control_loop_period_ns_{0};
  std::atomic<std::uint64_t> control_loop_jitter_ns_{0};
  std::atomic<std::uint32_t> rebalance_hysteresis_counter_{0};
  std::atomic<std::uint32_t> defrag_hysteresis_counter_{0};

  std::atomic<std::uint64_t> psi_pressure_ns_{0};
  std::atomic<std::uint64_t> psi_stall_ns_{0};

  std::atomic<float> manual_backpressure_override_{-1.0f};

  // Burst heuristic weights: occupancy, variance, fragmentation.
  float weight_occupancy_{0.7f};
  float weight_variance_{0.2f};
  float weight_fragmentation_{0.1f};
  float weight_bias_{0.0f};

  // Control-loop accounting, so a no-op maintenance pass is visible.
  std::atomic<std::uint64_t> loop_iterations_{0};
  std::atomic<std::uint64_t> rebalance_count_{0};
  std::atomic<std::uint64_t> migrated_tasks_{0};
  std::atomic<std::uint64_t> defrag_passes_{0};
  std::atomic<std::uint64_t> chunks_reclaimed_{0};
  std::atomic<std::uint64_t> journal_replayed_{0};
};

} // namespace Sphere
