// span_record.h
#pragma once

#include <cstdint>
#include <type_traits>

namespace Sphere::log {

/**
 * Strictly padded 32-byte binary payload for high-performance,
 * zero-copy shared memory (SHM) latency tracing.
 */
struct alignas(32) SpanRecord {
  std::uint64_t tsc_start{0}; // Start Time Stamp Counter
  std::uint64_t tsc_end{0};   // End Time Stamp Counter
  std::uint32_t thread_id{0}; // Fast thread identifier
  std::uint32_t job_id{0};    // SHM job sequence identifier
  std::uint32_t req_id{0};    // SHM request sequence identifier
  std::uint16_t module_id{0}; // Subsystem module (e.g., IOURING, ROOT)
  std::uint8_t level{0};      // LogLevel priority
  std::uint8_t reserved{0};   // Explicit structure padding
};

// Compile-time verification for shared memory compatibility and cache line
// alignment
static_assert(sizeof(SpanRecord) == 32,
              "SpanRecord layout must be exactly 32 bytes for SHM alignment!");

static_assert(std::is_trivially_copyable_v<SpanRecord>,
              "SpanRecord must be trivially copyable for SHM IPC safety!");

} // namespace Sphere::log