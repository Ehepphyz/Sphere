// span_ring.h
#pragma once

#include "span_record.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace Sphere::log {

// Standard CPU Cache-Line Size
constexpr std::size_t CACHE_LINE_SIZE = 64;

/**
 * Cache-line aligned Ring Buffer slot wrapping a SpanRecord with sequence
 * tracking. Required for multi-producer multi-consumer (MPMC) lock-free
 * synchronization.
 */
struct alignas(CACHE_LINE_SIZE) SpanCell {
  SpanRecord record{};               // Offsets 0..31  (32 bytes, alignas 32)
  std::atomic<std::uint64_t> seq{0}; // Offsets 32..39 (8 bytes)
  std::uint8_t pad[24]{};            // Offsets 40..63 (24 bytes padding)
};

static_assert(
    sizeof(SpanCell) == CACHE_LINE_SIZE,
    "SpanCell must be exactly 64 bytes to align with CPU cache lines!");

/**
 * Shared memory layout header for low-latency tracing ring buffers.
 * Explicitly padded to isolate producer/consumer atomics onto separate cache
 * lines.
 */
struct alignas(CACHE_LINE_SIZE) SpanRingHeader {
  // ---- Cache Line 0: Producer Hot Path ----
  std::atomic<std::uint64_t> write_index{0};
  std::uint8_t pad0[56]{}; // Padding to 64 bytes

  // ---- Cache Line 1: Consumer Hot Path ----
  std::atomic<std::uint64_t> read_index{0};
  std::uint8_t pad1[56]{}; // Padding to 128 bytes

  // ---- Cache Line 2: Read-Only Metadata & Telemetry ----
  std::uint64_t capacity{0}; // Must be a power of two
  std::atomic<std::uint64_t> dropped_count{0};
  std::uint8_t pad2[48]{}; // Padding to 192 bytes
};

// Compile-time layout verification
static_assert(offsetof(SpanRingHeader, write_index) == 0,
              "write_index offset drift!");
static_assert(offsetof(SpanRingHeader, read_index) == 64,
              "read_index offset drift!");
static_assert(offsetof(SpanRingHeader, capacity) == 128,
              "capacity offset drift!");
static_assert(sizeof(SpanRingHeader) == 192,
              "SpanRingHeader size must be exactly 192 bytes!");

/**
 * Shared memory view descriptor referencing mapped headers and buffer slots
 */
struct SpanRing {
  SpanRingHeader *hdr{nullptr};
  SpanCell *slots{nullptr};
};

/**
 * One-time initialization of the shared memory sequence slots.
 * Must be invoked by the primary SHM owner process prior to queue activity.
 */
void span_ring_init(SpanRing &ring, std::uint64_t capacity) noexcept;

/**
 * Lock-free, non-blocking MPMC push into the shared memory Span Ring Buffer
 */
bool span_ring_push(SpanRing &ring, std::uint8_t level, std::uint16_t module,
                    std::uint32_t job_id, std::uint32_t req_id,
                    std::uint64_t tsc_start, std::uint64_t tsc_end) noexcept;

/**
 * Overload for pushing an already constructed SpanRecord directly into the
 * ring.
 */
bool span_ring_push_record(SpanRing &ring, const SpanRecord &record) noexcept;

/**
 * Non-blocking MPMC pop from the shared memory Span Ring Buffer
 */
bool span_ring_pop(SpanRing &ring, SpanRecord &out_record) noexcept;

} // namespace Sphere::log
