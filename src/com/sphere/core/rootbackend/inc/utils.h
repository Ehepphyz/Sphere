// utils.h

// Cycle timing, prefetch and SIMD helpers.


#pragma once

#include <cstddef>
#include <cstdint>

#if defined(__x86_64__) || defined(_M_X64)
#define SPHERE_ARCH_X86_64 1
#if defined(_MSC_VER)
#include <intrin.h>
#else
#include <x86intrin.h>
#endif
#elif defined(__aarch64__) || defined(_M_ARM64)
#define SPHERE_ARCH_ARM64 1
#endif

namespace utils {

// -----------------------------------------------------------------------------
// Cycle counter
// -----------------------------------------------------------------------------

[[nodiscard]] std::uint64_t rdtsc() noexcept;

/// Serializing cycle-counter read.
[[nodiscard]] std::uint64_t rdtsc_fenced() noexcept;

/**
 * Cycle-to-nanosecond conversion
 */
class CycleTimer {
public:
  /// Runs calibration if it has not run yet. Safe to call from any thread.
  static void ensure_calibrated() noexcept;

  /// Measured frequency in GHz. Zero before the first calibration.
  [[nodiscard]] static double frequency_ghz() noexcept;

  /// Converts elapsed cycles to nanoseconds.
  [[nodiscard]] static std::uint64_t tsc_to_ns(std::uint64_t cycles) noexcept;

  /// Converts a nanosecond duration to a cycle count.
  [[nodiscard]] static std::uint64_t ns_to_tsc(std::uint64_t nanoseconds) noexcept;

  /// Cycles in one second on this machine.
  [[nodiscard]] static std::uint64_t cycles_per_second() noexcept;

  CycleTimer() noexcept { ensure_calibrated(); }

  void start() noexcept { start_ = rdtsc_fenced(); }

  [[nodiscard]] std::uint64_t stop_cycles() const noexcept {
    const std::uint64_t end = rdtsc_fenced();
    return (end > start_) ? (end - start_) : 0;
  }

  [[nodiscard]] std::uint64_t stop_ns() const noexcept {
    return tsc_to_ns(stop_cycles());
  }

  [[nodiscard]] double stop_ms() const noexcept {
    return static_cast<double>(stop_ns()) / 1.0e6;
  }

private:
  std::uint64_t start_{0};
};

// -----------------------------------------------------------------------------
// Prefetch
// -----------------------------------------------------------------------------

/// Prefetches for reading, with high temporal locality (L1).
void prefetch_read(const void *ptr) noexcept;

/// Prefetches for writing.
void prefetch_write(const void *ptr) noexcept;

/// Prefetches for reading, streaming (do not pollute L1).
void prefetch_stream(const void *ptr) noexcept;

// -----------------------------------------------------------------------------
// CPU capabilities
// -----------------------------------------------------------------------------

struct CPUCapabilities {
  bool has_sse42{false};   // SSE4.2, which is what carries the CRC32C instruction
  bool has_arm_crc{false}; // ARMv8 CRC32 extension
  bool has_avx2{false};
  bool has_avx512f{false};
  bool has_avx512bw{false};
  bool has_neon{false};
  bool has_sve{false};
  bool has_bmi2{false};
};

/**
 * Runtime CPU feature set
 */
[[nodiscard]] const CPUCapabilities &cpu_capabilities() noexcept;

// -----------------------------------------------------------------------------
// SIMD memory and tensor helpers
// -----------------------------------------------------------------------------

void *memcpy_adaptive(void *dest, const void *src, std::size_t n) noexcept;
void *memset_adaptive(void *dest, int value, std::size_t n) noexcept;
[[nodiscard]] int memcmp_adaptive(const void *a, const void *b,
                                  std::size_t n) noexcept;

/**
 * Scales `bytes / sizeof(float)` floats in place, dispatching on the widest
 * vector unit the CPU actually supports.
 */
void tensor_scale(void *raw, std::size_t bytes, float factor) noexcept;

// -----------------------------------------------------------------------------
// Hashing and scheduling helpers
// -----------------------------------------------------------------------------

/// 64-bit finalizer (splitmix-style), for spreading sequential keys.
[[nodiscard]] std::uint64_t mix64(std::uint64_t x) noexcept;

/// Jump consistent hash of `key` over `nodes` buckets.
[[nodiscard]] std::size_t numa_hash(std::uint64_t key,
                                    std::size_t nodes) noexcept;

/// Exponential smoothing of a backpressure sample into `prev_state`.
[[nodiscard]] float backpressure_smooth(float level, float &prev_state,
                                        float alpha = 0.5f) noexcept;

/// Work-stealing heuristic: true when the remote queue is worth raiding.
[[nodiscard]] bool should_steal(std::uint64_t local, std::uint64_t remote,
                                std::uint64_t min_threshold = 4) noexcept;

// -----------------------------------------------------------------------------
// ASCII case conversion
// -----------------------------------------------------------------------------

void to_lower_ascii(char *str, std::size_t len) noexcept;
void to_upper_ascii(char *str, std::size_t len) noexcept;

} // namespace utils
