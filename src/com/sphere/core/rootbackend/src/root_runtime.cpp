// root_runtime.cpp

#include "root_runtime.h"
#include "logger.h"
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

namespace Sphere {

namespace {

constexpr std::uint64_t SLEEP_TARGET_IDLE_NS = 20'000'000ULL; // 20 ms
constexpr std::uint64_t SLEEP_TARGET_NOMINAL_NS = 10'000'000ULL;
constexpr std::uint64_t SLEEP_TARGET_BUSY_NS = 500'000ULL; // 500 us

constexpr std::uint32_t REBALANCE_HYSTERESIS_TICKS = 3;
constexpr std::uint32_t IDLE_HYSTERESIS_TICKS = 5;

} // namespace

RootRuntime::RootRuntime(bool create_shm, const char *region_name,
                         std::size_t region_size, bool force_format)
    : session_(init_shm(create_shm, region_name, region_size, force_format)),
      engine_(session_.layout()) {
  utils::CycleTimer::ensure_calibrated();

}

RootRuntime::~RootRuntime() {
  stop();
}

void RootRuntime::start() {
  if (running_.exchange(true, std::memory_order_acq_rel)) {
    std::clog << "[WARN] [RootRuntime] Already running." << std::endl;
    return;
  }

  replay_journal();
  engine_.run();

  runtime_thread_ = std::jthread([this](std::stop_token token) {
#if defined(__linux__)
    // Keep the control loop off core 0, which typically fields interrupts.
    const int cpus =
        std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(static_cast<std::size_t>(cpus - 1), &cpuset);
    if (::pthread_setaffinity_np(::pthread_self(), sizeof(cpu_set_t),
                                 &cpuset) != 0) {
      std::clog << "[WARN] [RootRuntime] Could not set control-loop affinity."
                << std::endl;
    }
#endif
    runtime_loop(std::move(token));
  });

}

void RootRuntime::stop() {
  if (!running_.exchange(false, std::memory_order_acq_rel)) {
    return;
  }
  if (runtime_thread_.joinable()) {
    runtime_thread_.request_stop();
    runtime_thread_.join();
  }
  engine_.stop();
}

RootRuntime::ControlStats RootRuntime::control_stats() const noexcept {
  ControlStats stats{};
  stats.loop_iterations = loop_iterations_.load(std::memory_order_relaxed);
  stats.rebalances = rebalance_count_.load(std::memory_order_relaxed);
  stats.tasks_migrated = migrated_tasks_.load(std::memory_order_relaxed);
  stats.defrag_passes = defrag_passes_.load(std::memory_order_relaxed);
  stats.chunks_reclaimed = chunks_reclaimed_.load(std::memory_order_relaxed);
  stats.journal_replayed = journal_replayed_.load(std::memory_order_relaxed);
  return stats;
}

void RootRuntime::attach_cluster_runtime(ShmLayout &secondary_layout) {
  engine_.attach_runtime(secondary_layout);
  update_cluster_snapshot([&](std::vector<ShmLayout *> &layouts) {
    if (std::find(layouts.begin(), layouts.end(), &secondary_layout) ==
        layouts.end()) {
      layouts.push_back(&secondary_layout);
    }
  });
}

void RootRuntime::detach_cluster_runtime(ShmLayout &secondary_layout) {
  engine_.detach_runtime(secondary_layout);
  update_cluster_snapshot([&](std::vector<ShmLayout *> &layouts) {
    std::erase(layouts, &secondary_layout);
  });
}

void RootRuntime::replay_journal() {
  std::uint64_t replayed = 0;
  BridgeMessage msg{};
  while (engine_.replay_next_journal_entry(msg)) {
    engine_.handle_message(msg);
    ++replayed;
  }
  journal_replayed_.store(replayed, std::memory_order_relaxed);
}

void RootRuntime::adaptive_wait(std::uint64_t target_ns, float pressure,
                                const std::stop_token &stop_token) noexcept {
  if (pressure > 0.80f) {
    const std::uint64_t start = utils::rdtsc();
    const std::uint64_t target = utils::CycleTimer::ns_to_tsc(target_ns);
    while ((utils::rdtsc() - start) < target && !stop_token.stop_requested()) {
#if defined(__x86_64__) || defined(_M_X64)
      __builtin_ia32_pause();
#elif defined(__aarch64__)
      asm volatile("yield" ::: "memory");
#else
      std::this_thread::yield();
#endif
    }
    return;
  }
  std::this_thread::sleep_for(std::chrono::nanoseconds(target_ns));
}

void RootRuntime::update_stratified_prefetch(std::uint64_t current_seq,
                                             std::uint64_t prev_seq) noexcept {
  auto &layout = session_.layout();
  if (current_seq <= prev_seq || layout.cmd_ring == nullptr) {
    return;
  }

  const std::uint64_t capacity = layout.cmd_ring->capacity();
  if (capacity == 0) {
    return;
  }

  const std::uint64_t velocity = current_seq - prev_seq;
  const std::uint64_t lookahead = std::min(velocity * 2, capacity);

  for (std::uint64_t i = 0; i < 4; ++i) {
    const std::uint64_t slot = current_seq + lookahead + i;
    if (const void *address = layout.cmd_ring->element_at(slot);
        address != nullptr) {
      if (i < 2) {
        utils::prefetch_read(address);
      } else {
        utils::prefetch_stream(address);
      }
    }
  }
}

void RootRuntime::auto_tune_holt(float predictive_pressure,
                                 float jitter_ns) noexcept {
  const float jitter_ms = jitter_ns / 1'000'000.0f;
  if (jitter_ms > 5.0f) {
    // The loop is not keeping its own schedule; smooth harder.
    predictor_.alpha = std::clamp(predictor_.alpha * 0.9f, 0.05f, 0.9f);
    predictor_.beta = std::clamp(predictor_.beta * 0.9f, 0.01f, 0.5f);
  } else if (predictive_pressure > 0.7f) {
    // Under load, react faster.
    predictor_.alpha = std::clamp(predictor_.alpha * 1.05f, 0.05f, 0.95f);
    predictor_.beta = std::clamp(predictor_.beta * 1.05f, 0.01f, 0.6f);
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

float RootRuntime::burst_score(float raw_occupancy, float variance,
                               float frag_score) const noexcept {
  const float y = weight_bias_ + weight_occupancy_ * raw_occupancy +
                  weight_variance_ * variance +
                  weight_fragmentation_ * frag_score;
  return std::clamp(y, 0.0f, 1.0f);
}

void RootRuntime::runtime_loop(std::stop_token stop_token) {
  auto &layout = session_.layout();

  std::uint64_t prev_sequence = 0;
  std::uint64_t sleep_target_ns = SLEEP_TARGET_NOMINAL_NS;
  std::uint32_t high_pressure_ticks = 0;
  std::uint32_t low_pressure_ticks = 0;
  float previous_occupancy = 0.0f;

  while (running_.load(std::memory_order_acquire) &&
         !stop_token.stop_requested()) {
    // Measure the whole iteration
    const std::uint64_t iteration_start = utils::rdtsc();

    adaptive_wait(sleep_target_ns,
                  backpressure_level_.load(std::memory_order_relaxed),
                  stop_token);
    if (stop_token.stop_requested()) {
      break;
    }

    auto *header = layout.header;
    if (header == nullptr) {
      break;
    }

    float raw_occupancy = 0.0f;
    std::uint64_t current_sequence = 0;
    if (layout.cmd_ring != nullptr) {
      raw_occupancy = layout.cmd_ring->occupancy();
      current_sequence = layout.cmd_ring->head_sequence();
    }

    const float override_value =
        manual_backpressure_override_.load(std::memory_order_relaxed);
    const float predictive_pressure =
        (override_value >= 0.0f)
            ? override_value
            : predictor_.update_and_predict(raw_occupancy, 3.0f);

    backpressure_level_.store(predictive_pressure, std::memory_order_relaxed);

    history_pressures_[history_index_ % history_size_] = predictive_pressure;
    ++history_index_;

    variance_estimator_.update(predictive_pressure);
    const float variance = variance_estimator_.variance();
    const float stddev = std::sqrt(variance);

    const float high_threshold =
        std::clamp(0.85f - (stddev * 0.5f), 0.60f, 0.90f);
    const float low_threshold =
        std::clamp(0.10f + (stddev * 0.2f), 0.05f, 0.25f);

    header->raw_occupancy.store(raw_occupancy, std::memory_order_relaxed);
    header->predicted_pressure.store(predictive_pressure,
                                     std::memory_order_relaxed);
    header->pressure_variance.store(variance, std::memory_order_relaxed);
    header->dynamic_high_threshold.store(high_threshold,
                                         std::memory_order_relaxed);
    header->dynamic_low_threshold.store(low_threshold,
                                        std::memory_order_relaxed);
    header->offload_tokens_remaining.store(offload_limiter_.tokens,
                                           std::memory_order_relaxed);

    update_psi_metrics(predictive_pressure, raw_occupancy);

    const float frag_score =
        static_cast<float>(
            header->heap_fragmentation_score.load(std::memory_order_relaxed)) /
        100.0f;
    const float burst = burst_score(raw_occupancy, variance, frag_score);
    const float burst_delta = raw_occupancy - previous_occupancy;
    previous_occupancy = raw_occupancy;

    if (burst > 0.7f || burst_delta > 0.30f) {
      sleep_target_ns = SLEEP_TARGET_BUSY_NS;
      if (const std::size_t moved = engine_.rebalance_numa_queues(); moved > 0) {
        rebalance_count_.fetch_add(1, std::memory_order_relaxed);
        migrated_tasks_.fetch_add(moved, std::memory_order_relaxed);
      }
    }

    if (predictive_pressure > high_threshold) {
      ++high_pressure_ticks;
      low_pressure_ticks = 0;
      rebalance_hysteresis_counter_.store(high_pressure_ticks,
                                          std::memory_order_relaxed);
      if (high_pressure_ticks >= REBALANCE_HYSTERESIS_TICKS) {
        sleep_target_ns = SLEEP_TARGET_BUSY_NS;
        if (const std::size_t moved = engine_.rebalance_numa_queues();
            moved > 0) {
          rebalance_count_.fetch_add(1, std::memory_order_relaxed);
          migrated_tasks_.fetch_add(moved, std::memory_order_relaxed);
        }
      }
    } else if (predictive_pressure < low_threshold) {
      ++low_pressure_ticks;
      high_pressure_ticks = 0;
      rebalance_hysteresis_counter_.store(0, std::memory_order_relaxed);
      if (low_pressure_ticks >= IDLE_HYSTERESIS_TICKS) {
        sleep_target_ns = SLEEP_TARGET_IDLE_NS;
      }
    } else {
      high_pressure_ticks = 0;
      low_pressure_ticks = 0;
      rebalance_hysteresis_counter_.store(0, std::memory_order_relaxed);
      sleep_target_ns = SLEEP_TARGET_NOMINAL_NS;
    }

    if (header->heap_fragmentation_score.load(std::memory_order_relaxed) > 25 &&
        predictive_pressure < 0.30f) {
      defrag_hysteresis_counter_.fetch_add(1, std::memory_order_relaxed);
      const std::size_t reclaimed = engine_.heap_defragment_tick();
      defrag_passes_.fetch_add(1, std::memory_order_relaxed);
      chunks_reclaimed_.fetch_add(reclaimed, std::memory_order_relaxed);
    } else {
      defrag_hysteresis_counter_.store(0, std::memory_order_relaxed);
    }

    update_stratified_prefetch(current_sequence, prev_sequence);
    prev_sequence = current_sequence;

    if (auto snapshot = cluster_snapshot_.load(std::memory_order_acquire);
        snapshot && !snapshot->layouts.empty()) {
      update_cluster_telemetry(*snapshot);
      scheduler_cross_node_tick(*snapshot, predictive_pressure, high_threshold);
    }

    const std::uint64_t elapsed_ns =
        utils::CycleTimer::tsc_to_ns(utils::rdtsc() - iteration_start);
    const std::uint64_t jitter_ns = (elapsed_ns > sleep_target_ns)
                                        ? (elapsed_ns - sleep_target_ns)
                                        : (sleep_target_ns - elapsed_ns);

    control_loop_period_ns_.store(elapsed_ns, std::memory_order_relaxed);
    control_loop_jitter_ns_.store(jitter_ns, std::memory_order_relaxed);
    header->control_loop_period_ns.store(elapsed_ns, std::memory_order_relaxed);
    header->control_loop_jitter_ns.store(jitter_ns, std::memory_order_relaxed);

    auto_tune_holt(predictive_pressure, static_cast<float>(jitter_ns));
    loop_iterations_.fetch_add(1, std::memory_order_relaxed);
  }
}

void RootRuntime::update_cluster_telemetry(
    const ClusterSnapshot &snapshot) noexcept {
  for (auto *layout : snapshot.layouts) {
    if (layout != nullptr && layout->header != nullptr) {
      layout->header->heartbeat_cpp.fetch_add(1, std::memory_order_relaxed);
    }
  }
}

void RootRuntime::scheduler_cross_node_tick(const ClusterSnapshot &snapshot,
                                            float predictive_pressure,
                                            float high_threshold) noexcept {
  if (predictive_pressure <= high_threshold) {
    return;
  }

  float best_score = 0.0f;
  ShmLayout *best_layout = nullptr;

  for (auto *layout : snapshot.layouts) {
    if (layout == nullptr || layout->cmd_ring == nullptr) {
      continue;
    }
    const float score = 1.0f - layout->cmd_ring->occupancy();
    if (score > best_score) {
      best_score = score;
      best_layout = layout;
    }
  }

  if (auto *header = session_.layout().header; header != nullptr) {
    header->best_cluster_score.store(best_score, std::memory_order_relaxed);
  }

  if (best_layout != nullptr && best_score > 0.40f) {
    const std::uint64_t tsc_per_sec = utils::CycleTimer::cycles_per_second();
    if (offload_limiter_.try_consume(utils::rdtsc(), tsc_per_sec)) {
      if (const std::size_t moved = engine_.rebalance_numa_queues(); moved > 0) {
        rebalance_count_.fetch_add(1, std::memory_order_relaxed);
        migrated_tasks_.fetch_add(moved, std::memory_order_relaxed);
      }
    }
  }
}

} // namespace Sphere
