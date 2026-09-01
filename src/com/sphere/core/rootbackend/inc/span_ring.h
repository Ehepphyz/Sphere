// span_ring.h

// Lock-free MPMC ring of latency spans
#pragma once

#include "common_config.h"
#include "span_record.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace Sphere::log {

using Sphere::CACHE_LINE_SIZE;

/**
 * One ring slot
*/
struct alignas(CACHE_LINE_SIZE) SpanCell {
  SpanRecord record{};               // offsets 0..31
  std::atomic<std::uint64_t> seq{0}; // offsets 32..39
  std::uint8_t pad[24]{};            // offsets 40..63
};

static_assert(sizeof(SpanCell) == CACHE_LINE_SIZE,
              "ABI: SpanCell must be exactly one 64-byte cache line.");

/**
 * Header preceding the slot array.
 */
struct alignas(CACHE_LINE_SIZE) SpanRingHeader {
  // ---- Cache line 0: producer ----
  std::atomic<std::uint64_t> write_index{0};
  std::uint8_t pad0[56]{};

  // ---- Cache line 1: consumer ----
  std::atomic<std::uint64_t> read_index{0};
  std::uint8_t pad1[56]{};

  // ---- Cache line 2: geometry and telemetry ----
  std::uint64_t capacity{0}; // power of two
  std::atomic<std::uint64_t> dropped_count{0};
  std::atomic<std::uint64_t> init_magic{0};
  std::uint8_t pad2[40]{};
};

static_assert(offsetof(SpanRingHeader, write_index) == 0,
              "ABI: write_index offset drift.");
static_assert(offsetof(SpanRingHeader, read_index) == 64,
              "ABI: read_index offset drift.");
static_assert(offsetof(SpanRingHeader, capacity) == 128,
              "ABI: capacity offset drift.");
static_assert(sizeof(SpanRingHeader) == 192,
              "ABI: SpanRingHeader must be exactly 192 bytes.");

/// Magic stamped by span_ring_init().
inline constexpr std::uint64_t SPAN_RING_MAGIC = 0x5350414E52494E47ULL; // SPANRING

/**
 * Bytes required for a span ring of `capacity` slots
 */
[[nodiscard]] constexpr std::size_t
span_ring_bytes(std::uint64_t capacity) noexcept {
  return sizeof(SpanRingHeader) +
         static_cast<std::size_t>(capacity) * sizeof(SpanCell);
}

/**
 * Process-local view of a span ring living in shared memory
 */
struct SpanRing {
  SpanRingHeader *hdr{nullptr};
  SpanCell *slots{nullptr};

  [[nodiscard]] bool is_valid() const noexcept {
    return hdr != nullptr && slots != nullptr;
  }
};

[[nodiscard]] SpanRing span_ring_view(void *base, std::size_t bytes,
                                      std::uint64_t capacity) noexcept;

/**
 * One-time initialization of the sequence numbers
 */
void span_ring_init(SpanRing &ring, std::uint64_t capacity) noexcept;

/// True once span_ring_init() has run on this region.
[[nodiscard]] bool span_ring_is_initialized(const SpanRing &ring) noexcept;

/// Non-blocking MPMC push. Returns false when the ring is full.
bool span_ring_push(SpanRing &ring, std::uint8_t level, std::uint16_t module,
                    std::uint32_t job_id, std::uint32_t req_id,
                    std::uint64_t tsc_start, std::uint64_t tsc_end) noexcept;

/// Non-blocking MPMC push of a prebuilt record.
bool span_ring_push_record(SpanRing &ring, const SpanRecord &record) noexcept;

/// Non-blocking MPMC pop. Returns false when the ring is empty.
bool span_ring_pop(SpanRing &ring, SpanRecord &out_record) noexcept;

/// Number of spans dropped because the ring was full.
[[nodiscard]] std::uint64_t span_ring_dropped(const SpanRing &ring) noexcept;

} // namespace Sphere::log
