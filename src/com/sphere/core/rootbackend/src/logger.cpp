// logger.cpp

#include "logger.h"
#include "lockfree_ring.h"
#include "shm_layout.h"
#include "utils.h"

#include <algorithm>
#include <charconv>
#include <cstring>

namespace Sphere::log {

namespace {

Metrics g_metrics{};
std::atomic<LogLevel> g_evt_level{LogLevel::WARN};

/// Arms every cmd_min_latency slot with the "unset" sentinel.
void arm_min_latency() noexcept {
  for (std::size_t i = 0; i < MODULE_MAX; ++i) {
    g_metrics.cmd_min_latency[i].store(LATENCY_UNSET,
                                       std::memory_order_relaxed);
  }
}

// Runs before main() so the sentinels are in place for the first sample.
const bool g_min_latency_armed = [] {
  arm_min_latency();
  return true;
}();

} // namespace

Metrics &metrics() noexcept {
  (void)g_min_latency_armed;
  return g_metrics;
}

void reset_metrics() noexcept {
  for (std::size_t i = 0; i < MODULE_MAX; ++i) {
    g_metrics.cmd_count[i].store(0, std::memory_order_relaxed);
    g_metrics.cmd_error_count[i].store(0, std::memory_order_relaxed);
    g_metrics.cmd_total_latency[i].store(0, std::memory_order_relaxed);
    g_metrics.cmd_max_latency[i].store(0, std::memory_order_relaxed);
  }
  arm_min_latency();

  g_metrics.io_submit_count.store(0, std::memory_order_relaxed);
  g_metrics.io_complete_count.store(0, std::memory_order_relaxed);
  g_metrics.io_saturation_events.store(0, std::memory_order_relaxed);
  g_metrics.root_open_errors.store(0, std::memory_order_relaxed);
  g_metrics.root_read_errors.store(0, std::memory_order_relaxed);
  g_metrics.root_decompress_errors.store(0, std::memory_order_relaxed);
  g_metrics.evt_ring_drops.store(0, std::memory_order_relaxed);
  g_metrics.cmd_ring_drops.store(0, std::memory_order_relaxed);

  for (std::size_t i = 0; i < Metrics::MAX_WORKERS; ++i) {
    g_metrics.workers[i].jobs.store(0, std::memory_order_relaxed);
    g_metrics.workers[i].busy_cycles.store(0, std::memory_order_relaxed);
  }
}

std::uint64_t min_latency_cycles(std::uint16_t module) noexcept {
  if (module >= MODULE_MAX) {
    return 0;
  }
  const std::uint64_t value =
      g_metrics.cmd_min_latency[module].load(std::memory_order_relaxed);
  return (value == LATENCY_UNSET) ? 0 : value;
}

std::uint64_t rdtsc_cycles() noexcept { return utils::rdtsc(); }

double cycles_to_ns(std::uint64_t cycles) noexcept {
  const double ghz = utils::CycleTimer::frequency_ghz();
  if (ghz <= 0.0) {
    return static_cast<double>(cycles);
  }
  return static_cast<double>(cycles) / ghz;
}

void set_evt_ring_level(LogLevel level) noexcept {
  g_evt_level.store(level, std::memory_order_relaxed);
}

LogLevel evt_ring_level() noexcept {
  return g_evt_level.load(std::memory_order_relaxed);
}

void log_evt(ShmLayout &shm, LogLevel level, std::string_view msg,
             std::uint16_t module, std::uint32_t job_id, std::uint32_t req_id) {
  if (module < MODULE_MAX) {
    g_metrics.cmd_count[module].fetch_add(1, std::memory_order_relaxed);
    if (level >= LogLevel::ERROR) {
      g_metrics.cmd_error_count[module].fetch_add(1, std::memory_order_relaxed);
    }
  }

  if (level < g_evt_level.load(std::memory_order_relaxed) ||
      shm.evt_ring == nullptr) {
    return;
  }

  BridgeMessage pkt{};
  pkt.type = MsgType::INLINE_DATA;
  pkt.cmd = static_cast<std::uint16_t>(Proto::PacketType::EVT_OK);
  pkt.flags = static_cast<std::uint16_t>(level);
  pkt.job_id = job_id;
  pkt.req_id = req_id;

  const std::size_t len =
      std::min<std::size_t>(msg.size(), BRIDGE_INLINE_CAPACITY);
  pkt.payload_size = static_cast<std::uint8_t>(len);
  if (len > 0) {
    std::memcpy(pkt.inline_bytes, msg.data(), len);
  }

  if (!shm.evt_ring->push(pkt)) {
    g_metrics.evt_ring_drops.fetch_add(1, std::memory_order_relaxed);
  }
}

CmdTraceScope::CmdTraceScope(ShmLayout &shm, std::uint16_t module,
                             std::uint32_t job_id, std::uint32_t req_id,
                             const char *name) noexcept
    : shm_(shm), module_(module), job_id_(job_id), req_id_(req_id), name_(name),
      start_cycles_(utils::rdtsc()) {
  if (module_ < MODULE_MAX) {
    g_metrics.cmd_count[module_].fetch_add(1, std::memory_order_relaxed);
  }
}

CmdTraceScope::~CmdTraceScope() {
  const std::uint64_t end = utils::rdtsc();
  const std::uint64_t duration = (end > start_cycles_) ? (end - start_cycles_) : 0;

  if (module_ < MODULE_MAX) {
    auto &m = g_metrics;
    m.cmd_total_latency[module_].fetch_add(duration, std::memory_order_relaxed);

    std::uint64_t old_min =
        m.cmd_min_latency[module_].load(std::memory_order_relaxed);
    while (duration < old_min &&
           !m.cmd_min_latency[module_].compare_exchange_weak(
               old_min, duration, std::memory_order_relaxed,
               std::memory_order_relaxed)) {
    }

    std::uint64_t old_max =
        m.cmd_max_latency[module_].load(std::memory_order_relaxed);
    while (duration > old_max &&
           !m.cmd_max_latency[module_].compare_exchange_weak(
               old_max, duration, std::memory_order_relaxed,
               std::memory_order_relaxed)) {
    }
  }

  if (LogLevel::TRACE < g_evt_level.load(std::memory_order_relaxed)) {
    return;
  }

  char buf[96];
  char *ptr = buf;
  char *const end_ptr = buf + sizeof(buf);

  auto append = [&](std::string_view sv) {
    const std::size_t n = std::min<std::size_t>(
        sv.size(), static_cast<std::size_t>(end_ptr - ptr));
    if (n > 0) {
      std::memcpy(ptr, sv.data(), n);
      ptr += n;
    }
  };

  append("CMD_END ");
  if (name_ != nullptr) {
    append(name_);
  }
  append(" cyc=");

  if (const auto res = std::to_chars(ptr, end_ptr, duration);
      res.ec == std::errc{}) {
    ptr = res.ptr;
  }

  log_evt(shm_, LogLevel::TRACE,
          std::string_view(buf, static_cast<std::size_t>(ptr - buf)), module_,
          job_id_, req_id_);
}

// --- io_uring hooks ---

void io_uring_on_submit(std::size_t batch_size) noexcept {
  g_metrics.io_submit_count.fetch_add(batch_size, std::memory_order_relaxed);
}

void io_uring_on_complete() noexcept {
  g_metrics.io_complete_count.fetch_add(1, std::memory_order_relaxed);
}

void io_uring_on_saturation() noexcept {
  g_metrics.io_saturation_events.fetch_add(1, std::memory_order_relaxed);
}

// --- ROOT hooks ---

void root_on_open_error() noexcept {
  g_metrics.root_open_errors.fetch_add(1, std::memory_order_relaxed);
}

void root_on_read_error() noexcept {
  g_metrics.root_read_errors.fetch_add(1, std::memory_order_relaxed);
}

void root_on_decompress_error() noexcept {
  g_metrics.root_decompress_errors.fetch_add(1, std::memory_order_relaxed);
}

// --- worker hooks ---

void worker_on_job(std::size_t worker_id, std::uint64_t busy_cycles) noexcept {
  if (worker_id >= Metrics::MAX_WORKERS) {
    return;
  }
  g_metrics.workers[worker_id].jobs.fetch_add(1, std::memory_order_relaxed);
  g_metrics.workers[worker_id].busy_cycles.fetch_add(busy_cycles,
                                                     std::memory_order_relaxed);
}

} // namespace Sphere::log
