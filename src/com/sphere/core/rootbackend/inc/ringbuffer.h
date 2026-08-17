// ringbuffer.h

#pragma once

#include "common_config.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace Sphere {

using Sphere::CACHE_LINE_SIZE;
using Sphere::MAX_RUNTIMES;

// Forward declaration of BridgeMessage for journaling and tensor extraction
// APIs
struct BridgeMessage;

// Cross-platform forced inline directive for ultra-low latency hot paths
#if defined(_MSC_VER)
#define RING_ALWAYS_INLINE __forceinline
#elif defined(__GNUC__) || defined(__clang__)
#define RING_ALWAYS_INLINE inline __attribute__((always_inline))
#else
#define RING_ALWAYS_INLINE inline
#endif

/**
 * Admission and eviction policies applied when the ring buffer reaches
 * saturation
 */
enum class RingDropPolicy : std::uint8_t {
  BLOCK = 0, ///< Default mode: Returns nullptr when full (no dropped messages)
  DROP_OLDEST, ///< Evicts the oldest slot by advancing read_idx to free space
  DROP_NEWEST, ///< Rejects incoming data immediately when full (returns
               ///< nullptr)
  SAMPLE_ONLY  ///< Subsamples incoming reservations (e.g., 1 out of N slots)
};

/**
 * Cache-line aligned header tracking lock-free ring state, policy settings,
 * metrics
 */
struct alignas(64) RingHeader {
  // ---- Cache Line 0: Producer Hot Path ----
  std::atomic<std::uint64_t> write_idx{0}; // Offset 0 (8 bytes)
  std::uint8_t pad0[56]{};                 // Padding to 64 bytes (8 + 56 = 64)

  // ---- Cache Line 1: Consumer Hot Path ----
  std::atomic<std::uint64_t> read_idx{0}; // Offset 64 (8 bytes)
  std::uint8_t pad1[56]{}; // Padding to 128 bytes (64 + 8 + 56 = 128)

  // ---- Cache Line 2: Immutable / Policy & Watermark Metadata ----
  std::uint64_t capacity{0};  // Offset 128 (8 bytes, must be a power of two)
  std::uint32_t slot_size{0}; // Offset 136 (4 bytes)
  std::atomic<std::uint32_t> high_watermark{0}; // Offset 140 (4 bytes)
  std::atomic<std::uint32_t> low_watermark{0};  // Offset 144 (4 bytes)
  std::atomic<std::uint32_t> max_capacity{0};   // Offset 148 (4 bytes)
  std::atomic<std::uint32_t> sample_rate{1};    // Offset 152 (4 bytes)
  std::atomic<std::uint32_t> tsc_sample_mask{
      0}; // Offset 156 (4 bytes, 0 = sample every slot, 15 = sample 1/16)
  std::atomic<RingDropPolicy> drop_policy{
      RingDropPolicy::BLOCK}; // Offset 160 (1 byte)
  std::uint8_t pad2[31]{};    // Padding to 192 bytes (161 + 31 = 192)

  // ---- Cache Line 3: Telemetry, Drops & Journaling ----
  std::atomic<std::uint64_t> hotness_counter{0}; // Offset 192 (8 bytes)
  std::atomic<std::uint64_t> journal_seq{0};     // Offset 200 (8 bytes)
  std::atomic<std::uint64_t> tsc_last_sample{0}; // Offset 208 (8 bytes)
  std::atomic<std::uint64_t> dropped_count{0};   // Offset 216 (8 bytes)
  std::atomic<std::uint32_t> ebpf_slot_id{0};    // Offset 224 (4 bytes)
  std::atomic<int> io_fd{-1};                    // Offset 228 (4 bytes)
  std::uint8_t pad3[24]{}; // Padding to 256 bytes (232 + 24 = 256)

  // ---- Cache Line 4: Cluster Runtime Registration Table ----
  alignas(64) std::atomic<std::uint32_t> cluster_runtime_ids[MAX_RUNTIMES]{};
};

// Compile-time verification of strict memory layout alignment
static_assert(offsetof(RingHeader, write_idx) == 0, "write_idx offset drift!");
static_assert(offsetof(RingHeader, read_idx) == 64, "read_idx offset drift!");
static_assert(offsetof(RingHeader, capacity) == 128, "capacity offset drift!");
static_assert(offsetof(RingHeader, sample_rate) == 152,
              "sample_rate offset drift!");
static_assert(offsetof(RingHeader, tsc_sample_mask) == 156,
              "tsc_sample_mask offset drift!");
static_assert(offsetof(RingHeader, drop_policy) == 160,
              "drop_policy offset drift!");
static_assert(offsetof(RingHeader, hotness_counter) == 192,
              "hotness offset drift!");
static_assert(offsetof(RingHeader, dropped_count) == 216,
              "dropped_count offset drift!");

/**
 * Computes a direct memory pointer to a specific slot within the ring buffer
 */
RING_ALWAYS_INLINE std::byte *ring_slot_ptr(RingHeader *ring,
                                            std::uint64_t idx) noexcept {
  auto *slots_base = reinterpret_cast<std::byte *>(ring) + sizeof(RingHeader);
  return slots_base + (static_cast<std::size_t>(idx & (ring->capacity - 1)) *
                       ring->slot_size);
}

/**
 * Computes a const memory pointer to a specific slot within the ring buffer
 */
RING_ALWAYS_INLINE const std::byte *ring_slot_ptr(const RingHeader *ring,
                                                  std::uint64_t idx) noexcept {
  const auto *slots_base =
      reinterpret_cast<const std::byte *>(ring) + sizeof(RingHeader);
  return slots_base + (static_cast<std::size_t>(idx & (ring->capacity - 1)) *
                       ring->slot_size);
}

// ============================================================================
// Shared Memory Layout & Initialization APIs
// ============================================================================

/**
 * Calculates total shared memory size required for the header, slots, and
 * metadata
 */
[[nodiscard]] constexpr std::size_t
ring_total_shm_size(std::uint64_t capacity, std::uint32_t slot_size) noexcept {
  if (capacity == 0 || slot_size == 0) {
    return 0;
  }

  // 1. Check for multiplication overflow: capacity * slot_size
  const std::size_t max_size = static_cast<std::size_t>(-1);
  if (static_cast<std::size_t>(capacity) > max_size / slot_size) {
    return 0; // Overflow
  }
  const std::size_t raw_payload =
      static_cast<std::size_t>(capacity) * slot_size;

  // 2. Check for alignment padding addition overflow
  if (raw_payload > max_size - 7) {
    return 0; // Overflow
  }
  const std::size_t aligned_payload = (raw_payload + 7) & ~std::size_t{7};

  // 3. Check for TSC side-buffers multiplication overflow: 2 * capacity *
  // sizeof(uint64_t)
  if (static_cast<std::size_t>(capacity) >
      max_size / (2 * sizeof(std::uint64_t))) {
    return 0; // Overflow
  }
  const std::size_t tsc_buffers =
      2 * (static_cast<std::size_t>(capacity) * sizeof(std::uint64_t));

  // 4. Check for final summation overflow
  const std::size_t header_size = sizeof(RingHeader);
  if (aligned_payload > max_size - header_size ||
      tsc_buffers > max_size - (header_size + aligned_payload)) {
    return 0; // Overflow
  }

  return header_size + aligned_payload + tsc_buffers;
}

/**
 * Initializes a ring buffer inside a provided shared memory pointer
 */
RingHeader *ring_init_in_shm(void *shm_ptr, std::size_t shm_bytes,
                             std::uint64_t capacity,
                             std::uint32_t slot_size) noexcept;

// ============================================================================
// Core Operational APIs
// ============================================================================

// Adaptive Drop Policy API
void ring_set_drop_policy(RingHeader *ring, RingDropPolicy policy,
                          std::uint32_t sample_rate = 1) noexcept;

RING_ALWAYS_INLINE RingDropPolicy
ring_get_drop_policy(const RingHeader *ring) noexcept {
  if (!ring)
    return RingDropPolicy::BLOCK;
  return ring->drop_policy.load(std::memory_order_relaxed);
}

[[nodiscard]] std::uint64_t
ring_get_dropped_count(const RingHeader *ring) noexcept;

// Producer / Write Interface
[[nodiscard]] std::byte *ring_reserve_slot(RingHeader *ring) noexcept;
void ring_commit_write(RingHeader *ring) noexcept;
[[nodiscard]] bool ring_commit_multi_write(RingHeader *ring,
                                           std::uint64_t slots) noexcept;

// Consumer / Read Interface
[[nodiscard]] const std::byte *ring_peek_slot(RingHeader *ring) noexcept;
void ring_commit_read(RingHeader *ring) noexcept;
[[nodiscard]] bool ring_commit_multi_read(RingHeader *ring,
                                          std::uint64_t slots) noexcept;

// High-Level Push / Pop Operations
[[nodiscard]] bool ring_push(RingHeader *ring, const void *data,
                             std::size_t size) noexcept;
[[nodiscard]] bool ring_pop(RingHeader *ring, void *out, std::size_t max_size,
                            std::size_t *bytes_read = nullptr) noexcept;

// Batch Operations
[[nodiscard]] std::size_t ring_peek_n(RingHeader *ring, const std::byte **slots,
                                      std::size_t max_slots) noexcept;
[[nodiscard]] bool ring_push_batch(RingHeader *ring, const void *data,
                                   std::size_t slot_count,
                                   std::size_t slot_size) noexcept;

// Synchronization & Futex Support
void ring_wait_for_data(const RingHeader *ring,
                        std::uint64_t last_read_idx) noexcept;
void ring_notify_consumer(RingHeader *ring) noexcept;

// Capacity & Watermark Telemetry
[[nodiscard]] float ring_occupancy_ratio(const RingHeader *ring) noexcept;
[[nodiscard]] bool ring_is_congested(const RingHeader *ring) noexcept;
[[nodiscard]] bool
ring_is_above_high_watermark(const RingHeader *ring) noexcept;
[[nodiscard]] bool ring_is_below_low_watermark(const RingHeader *ring) noexcept;
void ring_set_watermarks(RingHeader *ring, std::uint32_t low,
                         std::uint32_t high) noexcept;

// Dynamic Scaling & Metrics
[[nodiscard]] bool ring_try_grow_capacity(RingHeader *ring,
                                          std::uint64_t new_capacity) noexcept;
std::uint64_t ring_next_journal_seq(RingHeader *ring) noexcept;
std::uint64_t ring_touch_hotness(RingHeader *ring) noexcept;
void ring_sample_tsc(RingHeader *ring, std::uint64_t tsc) noexcept;
void ring_record_ebpf_slot(RingHeader *ring, std::uint32_t slot) noexcept;

// Shared Memory Latency API
[[nodiscard]] std::uint64_t
ring_slot_latency_cycles(const RingHeader *ring, std::uint64_t idx) noexcept;

// Virtual Memory Mapping & Zero-Copy I/O
[[nodiscard]] bool ring_setup_virtual_loop(void *base,
                                           std::size_t length) noexcept;
[[nodiscard]] bool ring_bind_io_fd(RingHeader *ring, int fd) noexcept;
[[nodiscard]] std::size_t
ring_zero_copy_send(RingHeader *ring, int fd = -1,
                    std::size_t max_slots = 64) noexcept;
[[nodiscard]] std::size_t
ring_zero_copy_recv(RingHeader *ring, int fd = -1,
                    std::size_t max_slots = 64) noexcept;

// ============================================================================
// Advanced System Extension APIs (Journaling, NUMA, Backpressure & Cluster)
// ============================================================================

/**
 * Writes a message sequentially into the journal log buffer.
 */
[[nodiscard]] bool ring_journal_write(RingHeader *ring,
                                      const BridgeMessage &msg) noexcept;

/**
 * Replays and pops the next sequential message from the journal log buffer.
 */
[[nodiscard]] bool ring_journal_replay_next(RingHeader *ring,
                                            BridgeMessage &out) noexcept;

/**
 * Returns current queue backpressure level normalized between [0.0, 1.0].
 */
[[nodiscard]] float ring_backpressure_level(const RingHeader *ring) noexcept;

/**
 * Resolves the slot offset of the most recently updated payload slot.
 */
[[nodiscard]] std::uint64_t
ring_prefetch_hot_slot(const RingHeader *ring) noexcept;

/**
 * Selects a target NUMA node index based on key hashing and ring hotness.
 */
[[nodiscard]] std::size_t
ring_choose_numa_node(const RingHeader *ring, std::uint64_t key,
                      std::size_t numa_nodes) noexcept;

/**
 * Attempts lock-free work-stealing from a remote victim ring buffer.
 */
[[nodiscard]] bool ring_try_cross_node_steal(RingHeader *victim,
                                             BridgeMessage &out) noexcept;

/**
 * Resolves tensor memory pointers and byte lengths from a SHM reference
 * message.
 */
[[nodiscard]] bool ring_extract_tensor_meta(const RingHeader *ring,
                                            const BridgeMessage &msg,
                                            void *&ptr,
                                            std::size_t &bytes) noexcept;

/**
 * Dynamically evaluates queue saturation and updates the drop policy using
 * hysteresis.
 */
void ring_update_backpressure_policy(RingHeader *ring) noexcept;

/**
 * Resets ring sequence indices to zero when the buffer is idle and empty.
 */
void ring_compact(RingHeader *ring) noexcept;

/**
 * Registers an active runtime node ID in the ring header's cluster table.
 */
void ring_register_runtime(RingHeader *ring, std::uint32_t id) noexcept;

/**
 * Unregisters an active runtime node ID from the ring header's cluster table.
 */
void ring_unregister_runtime(RingHeader *ring, std::uint32_t id) noexcept;

// ============================================================================
// Multi-Producer Composition Layer
// ============================================================================

/**
 * Multiplexes multiple single-producer ring buffers into a unified interface.
 */
class MultiProducerRingSet {
public:
  MultiProducerRingSet(void *shm_base_ptr, std::size_t num_producers,
                       std::uint64_t capacity_per_ring,
                       std::uint32_t slot_size);

  [[nodiscard]] bool push(std::size_t producer_id, const void *data,
                          std::size_t size) noexcept;

  std::size_t poll_all(void *out_buffer, std::size_t max_size,
                       std::size_t &out_producer_id) noexcept;

private:
  std::vector<RingHeader *> rings_{};
  [[maybe_unused]] std::size_t num_rings_{0};
  std::size_t last_polled_idx_{0};
};

} // namespace Sphere
