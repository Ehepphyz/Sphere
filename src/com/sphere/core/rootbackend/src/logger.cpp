// logger.cpp

#include "logger.h"
#include "shm_layout.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cstring>

#if defined(__x86_64__) || defined(_M_X64)
#include <x86intrin.h>
#endif

namespace Sphere::log {

namespace {
Metrics g_metrics{};
double g_cycles_to_ns = 0.5; // Baseline: 2.0 GHz CPU -> 0.5 ns per cycle
} // namespace

Metrics &metrics() noexcept { return g_metrics; }

std::uint64_t rdtsc_cycles() noexcept {
#if defined(__x86_64__) || defined(_M_X64)
  unsigned int aux;
  return __rdtscp(&aux);
#elif defined(__aarch64__)
  std::uint64_t vct;
  asm volatile("mrs %0, cntvct_el0" : "=r"(vct));
  return vct;
#else
  return static_cast<std::uint64_t>(
      std::chrono::steady_clock::now().time_since_epoch().count());
#endif
}

double cycles_to_ns(std::uint64_t cycles) noexcept {
  return static_cast<double>(cycles) * g_cycles_to_ns;
}

void log_evt(ShmLayout &shm, LogLevel level, std::string_view msg,
             std::uint16_t module, std::uint32_t job_id, std::uint32_t req_id) {
  // Update lightweight atomic telemetry counters
  if (module < MODULE_MAX) {
    metrics().cmd_count[module].fetch_add(1, std::memory_order_relaxed);
    if (level >= LogLevel::ERROR) {
      metrics().cmd_error_count[module].fetch_add(1, std::memory_order_relaxed);
    }
  }

  // Push event packet to SHM Ring Buffer for downstream consumption
  if (level >= LogLevel::WARN && shm.evt_ring) {
    BridgeMessage pkt{};

    // 1. Message type is INLINE_DATA for short strings/logs
    pkt.type = MsgType::INLINE_DATA;
    pkt.flags = static_cast<std::uint16_t>(level);
    pkt.job_id = job_id;
    pkt.req_id = req_id;

    // 2. Payload is stored in inline_bytes (max 48 bytes)
    const std::size_t len =
        std::min<std::size_t>(msg.size(), sizeof(pkt.inline_bytes));
    pkt.payload_size = static_cast<std::uint8_t>(len);

    if (len > 0) {
      std::memcpy(pkt.inline_bytes, msg.data(), len);
    }

    shm.evt_ring->push(pkt);
  }
}

CmdTraceScope::CmdTraceScope(ShmLayout &shm_, std::uint16_t mod,
                             std::uint32_t job, std::uint32_t req,
                             const char *n) noexcept
    : shm(shm_), module(mod), job_id(job), req_id(req), name(n) {
  start_cycles = rdtsc_cycles();
  if (module < MODULE_MAX) {
    metrics().cmd_count[module].fetch_add(1, std::memory_order_relaxed);
  }
}

CmdTraceScope::~CmdTraceScope() {
  const auto end_cycles = rdtsc_cycles();
  const auto dur = end_cycles - start_cycles;

  if (module < MODULE_MAX) {
    auto &m = metrics();
    m.cmd_total_latency[module].fetch_add(dur, std::memory_order_relaxed);

    // Thread-safe atomic minimum latency update
    std::uint64_t old_min =
        m.cmd_min_latency[module].load(std::memory_order_relaxed);
    while ((old_min == 0 || dur < old_min) &&
           !m.cmd_min_latency[module].compare_exchange_weak(
               old_min, dur, std::memory_order_relaxed)) {
    }

    // Thread-safe atomic maximum latency update
    std::uint64_t old_max =
        m.cmd_max_latency[module].load(std::memory_order_relaxed);
    while (dur > old_max && !m.cmd_max_latency[module].compare_exchange_weak(
                                old_max, dur, std::memory_order_relaxed)) {
    }
  }

  // Zero-allocation stack buffer formatting using C++17 std::to_chars
  char buf[128];
  char *ptr = buf;
  char *end = buf + sizeof(buf);

  auto append = [&](std::string_view sv) {
    std::size_t n =
        std::min<std::size_t>(sv.size(), static_cast<std::size_t>(end - ptr));
    if (n > 0) {
      std::memcpy(ptr, sv.data(), n);
      ptr += n;
    }
  };

  append("CMD_END: ");
  if (name) {
    append(name);
  }
  append(" cycles=");

  auto res = std::to_chars(ptr, end, dur);
  if (res.ec == std::errc{}) {
    ptr = res.ptr;
  }

  log_evt(shm, LogLevel::TRACE,
          std::string_view(buf, static_cast<std::size_t>(ptr - buf)), module,
          job_id, req_id);
}

// --- io_uring Monitoring Hooks ---

void io_uring_on_submit(std::size_t batch_size) noexcept {
  metrics().io_submit_count.fetch_add(batch_size, std::memory_order_relaxed);
}

void io_uring_on_complete() noexcept {
  metrics().io_complete_count.fetch_add(1, std::memory_order_relaxed);
}

void io_uring_on_saturation() noexcept {
  metrics().io_saturation_events.fetch_add(1, std::memory_order_relaxed);
}

// --- ROOT Error Monitoring Hooks ---

void root_on_open_error() noexcept {
  metrics().root_open_errors.fetch_add(1, std::memory_order_relaxed);
}

void root_on_read_error() noexcept {
  metrics().root_read_errors.fetch_add(1, std::memory_order_relaxed);
}

void root_on_decompress_error() noexcept {
  metrics().root_decompress_errors.fetch_add(1, std::memory_order_relaxed);
}

// --- Worker Thread Monitoring Hooks ---

void worker_on_job(std::size_t worker_id, std::uint64_t busy_cycles) noexcept {
  if (worker_id >= Metrics::MAX_WORKERS) {
    return;
  }
  auto &m = metrics();
  m.workers[worker_id].jobs.fetch_add(1, std::memory_order_relaxed);
  m.workers[worker_id].busy_cycles.fetch_add(busy_cycles,
                                             std::memory_order_relaxed);
}

} // namespace Sphere::log
