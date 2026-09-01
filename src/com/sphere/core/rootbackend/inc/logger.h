// logger.h

// Counters and event logging for the engine.

#ifndef SPHERE_LOGGER_H
#define SPHERE_LOGGER_H

#include "common_config.h"
#include "packets.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace Sphere {
struct ShmLayout;
} // namespace Sphere

namespace Sphere::log {

enum class LogLevel : std::uint8_t {
  TRACE = 0,
  DEBUG = 1,
  INFO = 2,
  WARN = 3,
  ERROR = 4,
  FATAL = 5
};

enum : std::uint16_t {
  MODULE_GENERIC = 0,
  MODULE_FILE = 1,
  MODULE_IOURING = 2,
  MODULE_ROOT = 3,
  MODULE_WORKER = 4,
  MODULE_SHM = 5,
  MODULE_MAX = 32
};

/// Per-worker counters, one cache line each to avoid write sharing.
struct alignas(CACHE_LINE_SIZE) WorkerMetrics {
  std::atomic<std::uint64_t> jobs{0};
  std::atomic<std::uint64_t> busy_cycles{0};
};

struct Metrics {
  // Command counters, grouped per cache line so unrelated subsystems do not
  // bounce each other's lines.
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> cmd_count[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_error_count[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_total_latency[MODULE_MAX]{};

  // Minimum latency starts at "unset" rather than zero. A minimum initialized
  // to 0 can never be lowered, so it would read 0 forever.
  std::atomic<std::uint64_t> cmd_min_latency[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_max_latency[MODULE_MAX]{};

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> io_submit_count{0};
  std::atomic<std::uint64_t> io_complete_count{0};
  std::atomic<std::uint64_t> io_saturation_events{0};

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> root_open_errors{0};
  std::atomic<std::uint64_t> root_read_errors{0};
  std::atomic<std::uint64_t> root_decompress_errors{0};

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> evt_ring_drops{0};
  std::atomic<std::uint64_t> cmd_ring_drops{0};

  static constexpr std::size_t MAX_WORKERS = 64;
  WorkerMetrics workers[MAX_WORKERS];
};

/// Sentinel stored in cmd_min_latency until the first sample arrives.
inline constexpr std::uint64_t LATENCY_UNSET = ~std::uint64_t{0};

/// Process-wide metrics.
Metrics &metrics() noexcept;

/// Resets every counter, and re-arms the minimum-latency sentinels.
void reset_metrics() noexcept;

/// Minimum recorded latency in cycles, or 0 when nothing has been sampled.
[[nodiscard]] std::uint64_t min_latency_cycles(std::uint16_t module) noexcept;

/// Cycle counter (delegates to utils::rdtsc).
[[nodiscard]] std::uint64_t rdtsc_cycles() noexcept;

/**
 * Converts cycles to nanoseconds using the calibrated frequency
 */
[[nodiscard]] double cycles_to_ns(std::uint64_t cycles) noexcept;

/**
 * Sets the minimum level that reaches the shared-memory event ring.
 * Defaults to WARN.
 */
void set_evt_ring_level(LogLevel level) noexcept;

/// Current event-ring threshold.
[[nodiscard]] LogLevel evt_ring_level() noexcept;

/**
 * Event Recordings
 */
void log_evt(ShmLayout &shm, LogLevel level, std::string_view msg,
             std::uint16_t module = MODULE_GENERIC, std::uint32_t job_id = 0,
             std::uint32_t req_id = 0);

/**
 * RAII command tracer
 */
class CmdTraceScope {
public:
  CmdTraceScope(ShmLayout &shm, std::uint16_t module, std::uint32_t job_id,
                std::uint32_t req_id, const char *name) noexcept;
  ~CmdTraceScope();

  CmdTraceScope(const CmdTraceScope &) = delete;
  CmdTraceScope &operator=(const CmdTraceScope &) = delete;
  CmdTraceScope(CmdTraceScope &&) = delete;
  CmdTraceScope &operator=(CmdTraceScope &&) = delete;

private:
  ShmLayout &shm_;
  std::uint16_t module_;
  std::uint32_t job_id_;
  std::uint32_t req_id_;
  const char *name_;
  std::uint64_t start_cycles_;
};

// Subsystem hooks.
void io_uring_on_submit(std::size_t batch_size) noexcept;
void io_uring_on_complete() noexcept;
void io_uring_on_saturation() noexcept;

void root_on_open_error() noexcept;
void root_on_read_error() noexcept;
void root_on_decompress_error() noexcept;

void worker_on_job(std::size_t worker_id, std::uint64_t busy_cycles) noexcept;

} // namespace Sphere::log

#endif // SPHERE_LOGGER_H
