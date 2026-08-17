// root_runtime.cpp

#include "root_runtime.h"
#include "ringbuffer.h"
#include "utils.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <memory>
#include <thread>

#if defined(__linux__)
#include <pthread.h>
#include <sched.h>
#endif

#if defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
#endif

namespace Sphere {

// Align namespace aliases with class definitions
using Sphere::CACHE_LINE_SIZE;
using Sphere::MAX_RUNTIMES;

RootRuntime::RootRuntime(bool create_shm)
    : layout_(init_shm(create_shm)), engine_(layout_) {
  // Scope: Initialization phase log
  std::clog << "[INFO] [RootRuntime] Initialized runtime instance. Shared "
               "memory created: "
            << (create_shm ? "true" : "false") << std::endl;
}

RootRuntime::~RootRuntime() {
  stop();
  std::clog << "[INFO] [RootRuntime] Terminated runtime instance successfully."
            << std::endl;
}

void RootRuntime::start() {
  // Guard double initialization
  if (running_.exchange(true, std::memory_order_acq_rel)) {
    std::clog
        << "[WARN] [RootRuntime] Start requested, but loop is already running."
        << std::endl;
    return;
  }

  std::clog << "[INFO] [RootRuntime] Replaying journal logs..." << std::endl;
  replay_journal();

  std::clog << "[INFO] [RootRuntime] Starting engine background tasks..."
            << std::endl;
  engine_.run();

  // Scope: C++20 thread management using std::jthread and std::stop_token
  runtime_thread_ = std::jthread([this](std::stop_token stop_token) {
#if defined(__linux__)
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(0, &cpuset);
    if (pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset) !=
        0) {
      std::clog
          << "[WARN] [RootRuntime] Failed to bind thread affinity to CPU 0."
          << std::endl;
    }
#endif
    std::clog << "[INFO] [RootRuntime] Runtime worker loop started."
              << std::endl;
    this->runtime_loop(stop_token);
    std::clog << "[INFO] [RootRuntime] Runtime worker loop exited cleanly."
              << std::endl;
  });
}

void RootRuntime::stop() {
  if (!running_.exchange(false, std::memory_order_acq_rel)) {
    return;
  }

  std::clog
      << "[INFO] [RootRuntime] Stopping worker thread and execution engine..."
      << std::endl;

  if (runtime_thread_.joinable()) {
    runtime_thread_.request_stop();
    runtime_thread_.join();
  }

  engine_.stop();
  std::clog << "[INFO] [RootRuntime] Engine and worker thread stopped."
            << std::endl;
}

void RootRuntime::attach_cluster_runtime(ShmLayout &secondary_layout) {
  // Scope boundary: Mutex/Snapshot registration
  engine_.attach_runtime(secondary_layout);
  update_cluster_snapshot(
      [&](std::vector<ShmLayout *> &vec) { vec.push_back(&secondary_layout); });

  std::clog
      << "[INFO] [RootRuntime] Secondary cluster layout attached successfully."
      << std::endl;
}

void RootRuntime::detach_cluster_runtime(ShmLayout &secondary_layout) {
  engine_.detach_runtime(secondary_layout);
  update_cluster_snapshot([&](std::vector<ShmLayout *> &vec) {
    std::erase(vec, &secondary_layout);
  });

  std::clog
      << "[INFO] [RootRuntime] Secondary cluster layout detached successfully."
      << std::endl;
}

void RootRuntime::replay_journal() {
  std::size_t replayed_count = 0;
  BridgeMessage uncommitted_msg{};
  while (engine_.replay_next_journal_entry(uncommitted_msg)) {
    engine_.handle_message(uncommitted_msg);
    ++replayed_count;
  }
  std::clog << "[INFO] [RootRuntime] Replayed " << replayed_count
            << " uncommitted journal messages." << std::endl;
}

void RootRuntime::adaptive_wait(std::uint64_t target_ns, float pressure,
                                const std::stop_token &stop_token) noexcept {
  if (pressure > 0.80f) {
    const std::uint64_t start = utils::rdtsc();
    const std::uint64_t target_cycles = utils::CycleTimer::ns_to_tsc(target_ns);

    // Responsive spin-wait: yield core while observing shutdown requests
    while (((utils::rdtsc() - start) < target_cycles) &&
           !stop_token.stop_requested()) {
#if defined(__x86_64__) || defined(_M_X64)
      _mm_pause();
#elif defined(__aarch64__)
      asm volatile("yield" ::: "memory");
#else
      std::this_thread::yield();
#endif
    }
  } else {
    // Coarse-grained sleep for lower backpressure conditions
    std::this_thread::sleep_for(std::chrono::nanoseconds(target_ns));
  }
}

void RootRuntime::update_stratified_prefetch(std::size_t current_seq,
                                             std::size_t prev_seq) noexcept {
  if (current_seq > prev_seq && layout_.cmd_ring != nullptr) {
    const std::size_t velocity = current_seq - prev_seq;
    const std::size_t cap = layout_.cmd_ring->capacity();
    if (cap == 0)
      return;

    const std::size_t stride_lookahead = std::min(velocity * 2, cap);

    for (std::size_t i = 0; i < 4; ++i) {
      const std::size_t distance = stride_lookahead + i;
      const std::size_t target_slot = (current_seq + distance) % cap;

      // Direct ring slot pointer prefetching
      const void *target_address = layout_.cmd_ring->element_at(target_slot);
      if (!target_address)
        continue;

#if defined(__x86_64__) || defined(_M_X64)
      if (distance < 4) {
        _mm_prefetch(reinterpret_cast<const char *>(target_address),
                     _MM_HINT_T0);
      } else {
        _mm_prefetch(reinterpret_cast<const char *>(target_address),
                     _MM_HINT_T2);
      }
#else
      utils::prefetch_read(target_address);
#endif
    }
  }
}

float RootRuntime::variance_simd(float base_variance) noexcept {
#if defined(__AVX512F__) && defined(__x86_64__)
  // Prevent out-of-bounds memory access if history_pressures_ is undersized
  const std::size_t available_capacity = history_pressures_.size();
  const std::size_t count = std::min(
      {history_size_, available_capacity, static_cast<std::size_t>(16)});

  if (count == 0)
    return base_variance;

  const __mmask16 mask =
      (count >= 16) ? 0xFFFF : static_cast<__mmask16>((1u << count) - 1);

  // Load masked pressure values zeroing inactive elements
  __m512 pressures = _mm512_maskz_loadu_ps(mask, history_pressures_.data());
  __m512 mean_vec = _mm512_set1_ps(variance_estimator_.mean());
  __m512 diff = _mm512_sub_ps(pressures, mean_vec);
  __m512 sq = _mm512_mul_ps(diff, diff);

  // Mask out unused items explicitly before sum reduction
  __m512 masked_sq = _mm512_maskz_mov_ps(mask, sq);
  return _mm512_reduce_add_ps(masked_sq) / static_cast<float>(count);
#else
  return base_variance;
#endif
}

void RootRuntime::auto_tune_holt(float predictive_pressure,
                                 float jitter_ns) noexcept {
  const float jitter_ms = static_cast<float>(jitter_ns) / 1'000'000.0f;
  if (jitter_ms > 5.0f) {
    predictor_.alpha = std::clamp(predictor_.alpha * 0.9f, 0.05f, 0.9f);
    predictor_.beta = std::clamp(predictor_.beta * 0.9f, 0.01f, 0.5f);
  } else if (predictive_pressure > 0.7f) {
    predictor_.alpha = std::clamp(predictor_.alpha * 1.05f, 0.05f, 0.95f);
    predictor_.beta = std::clamp(predictor_.beta * 1.05f, 0.01f, 0.6f);
  }
}

void RootRuntime::predictive_numa_migration(float predictive_pressure,
                                            float burst_delta) noexcept {
  if (predictive_pressure > 0.85f || burst_delta > 0.35f) {
    engine_.rebalance_numa_queues();
  }
}

void RootRuntime::update_psi_metrics(float predictive_pressure,
                                     float raw_occupancy) noexcept {
  const float stall = std::max(0.0f, predictive_pressure - raw_occupancy);
  psi_pressure_ns_.store(
      static_cast<std::uint64_t>(predictive_pressure * 1'000'000.0f),
      std::memory_order_relaxed);
  psi_stall_ns_.store(static_cast<std::uint64_t>(stall * 1'000'000.0f),
                      std::memory_order_relaxed);
}

float RootRuntime::ml_predict_burst(float raw_occupancy, float variance,
                                    float frag_score) noexcept {
  const float x1 = raw_occupancy;
  const float x2 = variance;
  const float x3 = frag_score;
  const float y = ml_bias_ + ml_w1_ * x1 + ml_w2_ * x2 + ml_w3_ * x3;
  return std::clamp(y, 0.0f, 1.0f);
}

void RootRuntime::runtime_loop(std::stop_token stop_token) {
  std::size_t prev_sequence = 0;
  std::uint64_t sleep_target_ns = 10'000'000ULL;

  std::uint32_t high_pressure_ticks = 0;
  std::uint32_t low_pressure_ticks = 0;

  float burst_prev = 0.0f;

  while (running_.load(std::memory_order_acquire) &&
         !stop_token.stop_requested()) {
    const std::uint64_t start_cycles = utils::rdtsc();

    const float current_pressure_hint =
        backpressure_level_.load(std::memory_order_relaxed);

    // Pass stop_token for responsive interrupt handling during spin-wait
    adaptive_wait(sleep_target_ns, current_pressure_hint, stop_token);

    if (stop_token.stop_requested())
      break;

    const std::uint64_t end_cycles = utils::rdtsc();

    const std::uint64_t actual_period_ns =
        utils::CycleTimer::tsc_to_ns(end_cycles - start_cycles);
    const std::uint64_t jitter_ns = (actual_period_ns > sleep_target_ns)
                                        ? (actual_period_ns - sleep_target_ns)
                                        : (sleep_target_ns - actual_period_ns);

    control_loop_period_ns_.store(actual_period_ns, std::memory_order_relaxed);
    control_loop_jitter_ns_.store(jitter_ns, std::memory_order_relaxed);

    auto *header = layout_.header;

    if (header != nullptr) {
      float raw_occupancy = 0.0f;
      std::size_t current_sequence = 0;

      if (layout_.cmd_ring != nullptr) {
        const std::size_t cap = layout_.cmd_ring->capacity();
        const std::size_t approx = layout_.cmd_ring->size_approx();
        current_sequence = layout_.cmd_ring->head_sequence();
        if (cap > 0) {
          raw_occupancy = static_cast<float>(approx) / static_cast<float>(cap);
        }
      }

      const float override_val =
          manual_backpressure_override_.load(std::memory_order_relaxed);
      const float predictive_pressure =
          (override_val >= 0.0f)
              ? override_val
              : predictor_.update_and_predict(raw_occupancy, 3.0f);

      backpressure_level_.store(predictive_pressure, std::memory_order_relaxed);

      if (!history_pressures_.empty()) {
        history_pressures_[history_index_ % history_size_] =
            predictive_pressure;
        history_index_++;
      }

      variance_estimator_.update(predictive_pressure);
      float p_var = variance_estimator_.variance();
      p_var = variance_simd(p_var);
      const float p_stddev = std::sqrt(p_var);

      const float dynamic_high_thresh =
          std::clamp(0.85f - (p_stddev * 0.5f), 0.60f, 0.90f);
      const float dynamic_low_thresh =
          std::clamp(0.10f + (p_stddev * 0.2f), 0.05f, 0.25f);

      // Store metrics in shared memory header
      header->raw_occupancy.store(raw_occupancy, std::memory_order_relaxed);
      header->predicted_pressure.store(predictive_pressure,
                                       std::memory_order_relaxed);
      header->pressure_variance.store(p_var, std::memory_order_relaxed);
      header->dynamic_high_threshold.store(dynamic_high_thresh,
                                           std::memory_order_relaxed);
      header->dynamic_low_threshold.store(dynamic_low_thresh,
                                          std::memory_order_relaxed);
      header->control_loop_jitter_ns.store(jitter_ns,
                                           std::memory_order_relaxed);
      header->control_loop_period_ns.store(actual_period_ns,
                                           std::memory_order_relaxed);
      header->offload_tokens_remaining.store(offload_limiter_.tokens,
                                             std::memory_order_relaxed);

      update_psi_metrics(predictive_pressure, raw_occupancy);

      const float frag_score = static_cast<float>(
          header->heap_fragmentation_score.load(std::memory_order_relaxed));
      const float ml_burst_prob =
          ml_predict_burst(raw_occupancy, p_var, frag_score);

      const float burst_delta = raw_occupancy - burst_prev;
      burst_prev = raw_occupancy;

      if (ml_burst_prob > 0.7f || burst_delta > 0.30f) {
        sleep_target_ns = 500'000ULL;
        predictive_numa_migration(predictive_pressure, burst_delta);
      }

      auto_tune_holt(predictive_pressure, jitter_ns);

      if (predictive_pressure > dynamic_high_thresh) {
        high_pressure_ticks++;
        low_pressure_ticks = 0;
        rebalance_hysteresis_counter_.store(high_pressure_ticks,
                                            std::memory_order_relaxed);

        if (high_pressure_ticks >= 3) {
          sleep_target_ns = 500'000ULL;
          engine_.rebalance_numa_queues();
        }
      } else if (predictive_pressure < dynamic_low_thresh) {
        low_pressure_ticks++;
        high_pressure_ticks = 0;
        rebalance_hysteresis_counter_.store(0, std::memory_order_relaxed);

        if (low_pressure_ticks >= 5) {
          sleep_target_ns = 20'000'000ULL;
        }
      } else {
        high_pressure_ticks = 0;
        low_pressure_ticks = 0;
        rebalance_hysteresis_counter_.store(0, std::memory_order_relaxed);
        sleep_target_ns = 10'000'000ULL;
      }

      if (header->heap_fragmentation_score.load(std::memory_order_relaxed) >
          8) {
        if (predictive_pressure < 0.30f) {
          defrag_hysteresis_counter_.fetch_add(1, std::memory_order_relaxed);
          engine_.heap_defragment_tick();
        }
      } else {
        defrag_hysteresis_counter_.store(0, std::memory_order_relaxed);
      }

      update_stratified_prefetch(current_sequence, prev_sequence);
      prev_sequence = current_sequence;

      // Scope: Acquire shared snapshot reference safely
      auto snapshot_ptr = std::atomic_load_explicit(&cluster_snapshot_,
                                                    std::memory_order_acquire);
      if (snapshot_ptr) {
        update_cluster_telemetry(*snapshot_ptr);
        scheduler_cross_node_tick(*snapshot_ptr, predictive_pressure,
                                  dynamic_high_thresh);
      }
    }
  }
}

void RootRuntime::update_cluster_telemetry(
    const ClusterSnapshot &snapshot) noexcept {
  for (auto *cluster_layout : snapshot.layouts) {
    if (cluster_layout && cluster_layout->header != nullptr) {
      cluster_layout->header->heartbeat_cpp.fetch_add(
          1, std::memory_order_relaxed);
    }
  }
}

void RootRuntime::scheduler_cross_node_tick(const ClusterSnapshot &snapshot,
                                            float predictive_pressure,
                                            float high_threshold) noexcept {
  if (predictive_pressure > high_threshold) {
    float best_score = 0.0f;
    ShmLayout *best_layout = nullptr;

    for (auto *cluster_layout : snapshot.layouts) {
      if (!cluster_layout || !cluster_layout->cmd_ring)
        continue;

      const std::size_t cap = cluster_layout->cmd_ring->capacity();
      if (cap == 0)
        continue;

      const float occ =
          static_cast<float>(cluster_layout->cmd_ring->size_approx()) /
          static_cast<float>(cap);
      const float score = (1.0f - occ);

      if (score > best_score) {
        best_score = score;
        best_layout = cluster_layout;
      }
    }

    if (layout_.header != nullptr) {
      layout_.header->best_cluster_score.store(best_score,
                                               std::memory_order_relaxed);
    }

    const std::uint64_t current_tsc = utils::rdtsc();
    const std::uint64_t tsc_per_sec =
        utils::CycleTimer::ns_to_tsc(1'000'000'000ULL);

    if (best_layout && best_score > 0.40f) {
      if (offload_limiter_.try_consume(current_tsc, tsc_per_sec)) {
        engine_.rebalance_numa_queues();
      }
    }
  }
}

} // namespace Sphere
