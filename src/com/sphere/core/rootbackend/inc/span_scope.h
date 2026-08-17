// span_scope.h
#pragma once

#include "span_ring.h"

#include <cstdint>

#if defined(_MSC_VER)
#include <intrin.h>
#elif defined(_M_X64) || defined(_M_IX86) || defined(__x86_64__) ||            \
    defined(__i386__)
#include <x86intrin.h>
#endif

namespace Sphere::log {

/**
 * Fast inline hardware Time Stamp Counter (TSC) reading.
 */
[[nodiscard]] inline std::uint64_t rdtsc_cycles() noexcept {
#if defined(_MSC_VER) || defined(_M_X64) || defined(_M_IX86) ||                \
    defined(__x86_64__) || defined(__i386__)
  return __rdtsc();
#elif defined(__aarch64__)
  std::uint64_t val;
  asm volatile("mrs %0, cntvct_el0" : "=r"(val));
  return val;
#else
  return 0;
#endif
}

/**
 * Zero-allocation, stack-allocated RAII scope guard for automated
 * latency span measurement via hardware TSC.
 */
struct SpanScope {
  SpanRing &ring;
  std::uint64_t start_cycles{0};
  std::uint32_t job_id{0};
  std::uint32_t req_id{0};
  std::uint16_t module{0};
  std::uint8_t level{0};

  /**
   * Constructs the scope guard and records the initial hardware TSC cycles.
   */
  SpanScope(SpanRing &r, std::uint16_t mod, std::uint32_t job, std::uint32_t req,
            std::uint8_t lvl = 0) noexcept
      : ring(r), start_cycles(rdtsc_cycles()), job_id(job), req_id(req),
        module(mod), level(lvl) {}

  /**
   * Automatically calculates elapsed cycles upon exiting scope and pushes
   * the trace span directly into the shared memory ring buffer.
   */
  ~SpanScope() noexcept {
    const std::uint64_t end_cycles = rdtsc_cycles();
    span_ring_push(ring, level, module, job_id, req_id, start_cycles,
                   end_cycles);
  }

  // Disable copying and moving to prevent duplicate scope lifecycle events
  SpanScope(const SpanScope &) = delete;
  SpanScope &operator=(const SpanScope &) = delete;
  SpanScope(SpanScope &&) = delete;
  SpanScope &operator=(SpanScope &&) = delete;
};

} // namespace Sphere::log