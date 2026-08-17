// logger.h
#ifndef SPHERE_LOGGER_H
#define SPHERE_LOGGER_H

#include "common_config.h"
#include "packets.h"
#include "shm_layout.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string_view>

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

// Aligned per-worker metric struct to prevent False Sharing / Cache Bouncing
struct alignas(CACHE_LINE_SIZE) WorkerMetrics {
  std::atomic<std::uint64_t> jobs{0};
  std::atomic<std::uint64_t> busy_cycles{0};
};

struct Metrics {
  // Command Metrics (Cache-line aligned to isolate engine scheduling hot-paths)
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> cmd_count[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_error_count[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_total_latency[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_min_latency[MODULE_MAX]{};
  std::atomic<std::uint64_t> cmd_max_latency[MODULE_MAX]{};

  // io_uring Subsystem
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> io_submit_count{0};
  std::atomic<std::uint64_t> io_complete_count{0};
  std::atomic<std::uint64_t> io_saturation_events{0};

  // ROOT Subsystem
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> root_open_errors{0};
  std::atomic<std::uint64_t> root_read_errors{0};
  std::atomic<std::uint64_t> root_decompress_errors{0};

  // SHM Ring Buffers
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> evt_ring_drops{0};
  std::atomic<std::uint64_t> cmd_ring_drops{0};

  // Workers Array - Each worker owns its dedicated cache line
  static constexpr std::size_t MAX_WORKERS = 64;
  WorkerMetrics workers[MAX_WORKERS];
};

/// Global metrics singleton/accessor
Metrics &metrics() noexcept;

/// High-precision Time Stamp Counter (TSC) reading (x86 / ARM64)
std::uint64_t rdtsc_cycles() noexcept;

/// Converts CPU cycles to nanoseconds based on calibrated frequency
double cycles_to_ns(std::uint64_t cycles) noexcept;

/// Asynchronous event logging (pushes packet + text payload to SHM evt_ring)
void log_evt(ShmLayout &shm, LogLevel level, std::string_view msg,
             std::uint16_t module = MODULE_GENERIC, std::uint32_t job_id = 0,
             std::uint32_t req_id = 0);

/// RAII scope for tracing command execution time and collecting latency metrics
struct CmdTraceScope {
  ShmLayout &shm;
  std::uint16_t module;
  std::uint32_t job_id;
  std::uint32_t req_id;
  const char *name;
  std::uint64_t start_cycles;

  CmdTraceScope(ShmLayout &shm_, std::uint16_t mod, std::uint32_t job,
                std::uint32_t req, const char *n) noexcept;

  ~CmdTraceScope();
};

/// io_uring subsystem monitoring hooks
void io_uring_on_submit(std::size_t batch_size) noexcept;
void io_uring_on_complete() noexcept;
void io_uring_on_saturation() noexcept;

/// ROOT I/O subsystem monitoring hooks
void root_on_open_error() noexcept;
void root_on_read_error() noexcept;
void root_on_decompress_error() noexcept;

/// Worker execution thread monitoring hooks
void worker_on_job(std::size_t worker_id, std::uint64_t busy_cycles) noexcept;

} // namespace Sphere::log

#endif // SPHERE_LOGGER_H
