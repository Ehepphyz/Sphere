// ringbuffer.h

// Byte-slot ring buffer placed directly in shared memory.


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

struct BridgeMessage;

#if defined(_MSC_VER)
#define RING_ALWAYS_INLINE __forceinline
#elif defined(__GNUC__) || defined(__clang__)
#define RING_ALWAYS_INLINE inline __attribute__((always_inline))
#else
#define RING_ALWAYS_INLINE inline
#endif

enum class RingDropPolicy : std::uint8_t {
  BLOCK = 0,   ///< reject the write and return nullptr; nothing is lost
  DROP_OLDEST, ///< advance the read index to free a slot
  DROP_NEWEST, ///< reject the incoming item
  SAMPLE_ONLY  ///< admit one reservation in N
};

/**
 * Ring header
  */
struct alignas(CACHE_LINE_SIZE) RingHeader {
  // ---- Cache line 0: producer publish index ----
  std::atomic<std::uint64_t> write_idx{0}; // offset 0
  std::uint8_t pad0[56]{};

  // ---- Cache line 1: consumer publish index ----
  std::atomic<std::uint64_t> read_idx{0}; // offset 64
  std::uint8_t pad1[56]{};

  // ---- Cache line 2: policy and geometry ----
  
  std::atomic<std::uint64_t> capacity{0};       // offset 128, power of two
  std::uint32_t slot_size{0};                   // offset 136, immutable
  std::atomic<std::uint32_t> high_watermark{0}; // offset 140
  std::atomic<std::uint32_t> low_watermark{0};  // offset 144
  std::atomic<std::uint32_t> max_capacity{0};   // offset 148
  std::atomic<std::uint32_t> sample_rate{1};    // offset 152
  std::atomic<std::uint32_t> tsc_sample_mask{0}; // offset 156, 0 = sample all
  std::atomic<RingDropPolicy> drop_policy{RingDropPolicy::BLOCK}; // offset 160
  std::uint8_t pad2[31]{};

  // ---- Cache line 3: telemetry and the two claim counters ----
  std::atomic<std::uint64_t> hotness_counter{0}; // offset 192
  std::atomic<std::uint64_t> journal_seq{0};     // offset 200
  std::atomic<std::uint64_t> tsc_last_sample{0}; // offset 208
  std::atomic<std::uint64_t> dropped_count{0};   // offset 216
  std::atomic<std::uint32_t> ebpf_slot_id{0};    // offset 224
  std::atomic<int> io_fd{-1};                    // offset 228
  // Claim counters run ahead of the publish indices; they were carved out of
  // the old padding so the header stays exactly 256 bytes.
  std::atomic<std::uint64_t> write_claim{0}; // offset 232
  std::atomic<std::uint64_t> read_claim{0};  // offset 240
  std::uint8_t pad3[8]{};

  // ---- Cache line 4: cluster registration table ----
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint32_t>
      cluster_runtime_ids[MAX_RUNTIMES]{};
};

static_assert(offsetof(RingHeader, write_idx) == 0, "ABI: write_idx drift.");
static_assert(offsetof(RingHeader, read_idx) == 64, "ABI: read_idx drift.");
static_assert(offsetof(RingHeader, capacity) == 128, "ABI: capacity drift.");
static_assert(offsetof(RingHeader, slot_size) == 136, "ABI: slot_size drift.");
static_assert(offsetof(RingHeader, sample_rate) == 152,
              "ABI: sample_rate drift.");
static_assert(offsetof(RingHeader, tsc_sample_mask) == 156,
              "ABI: tsc_sample_mask drift.");
static_assert(offsetof(RingHeader, drop_policy) == 160,
              "ABI: drop_policy drift.");
static_assert(offsetof(RingHeader, hotness_counter) == 192,
              "ABI: hotness drift.");
static_assert(offsetof(RingHeader, dropped_count) == 216,
              "ABI: dropped_count drift.");
static_assert(offsetof(RingHeader, write_claim) == 232,
              "ABI: write_claim drift.");
static_assert(offsetof(RingHeader, read_claim) == 240,
              "ABI: read_claim drift.");
static_assert(offsetof(RingHeader, cluster_runtime_ids) == 256,
              "ABI: cluster table drift.");

/**
 * Pointer to slot idx
 */
RING_ALWAYS_INLINE std::byte *ring_slot_ptr(RingHeader *ring,
                                            std::uint64_t idx) noexcept {
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) {
    return nullptr;
  }
  auto *slots_base = reinterpret_cast<std::byte *>(ring) + sizeof(RingHeader);
  return slots_base +
         (static_cast<std::size_t>(idx & (cap - 1)) * ring->slot_size);
}

RING_ALWAYS_INLINE const std::byte *ring_slot_ptr(const RingHeader *ring,
                                                  std::uint64_t idx) noexcept {
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) {
    return nullptr;
  }
  const auto *slots_base =
      reinterpret_cast<const std::byte *>(ring) + sizeof(RingHeader);
  return slots_base +
         (static_cast<std::size_t>(idx & (cap - 1)) * ring->slot_size);
}

// ============================================================================
// Sizing and initialization
// ============================================================================

[[nodiscard]] constexpr std::size_t
ring_total_shm_size(std::uint64_t capacity, std::uint32_t slot_size) noexcept {
  if (capacity == 0 || slot_size == 0) {
    return 0;
  }
  constexpr std::size_t max_size = static_cast<std::size_t>(-1);

  if (static_cast<std::size_t>(capacity) > max_size / slot_size) {
    return 0;
  }
  const std::size_t raw_payload =
      static_cast<std::size_t>(capacity) * slot_size;

  if (raw_payload > max_size - 7) {
    return 0;
  }
  const std::size_t aligned_payload = (raw_payload + 7) & ~std::size_t{7};

  if (static_cast<std::size_t>(capacity) >
      max_size / (2 * sizeof(std::uint64_t))) {
    return 0;
  }
  const std::size_t tsc_buffers =
      2 * (static_cast<std::size_t>(capacity) * sizeof(std::uint64_t));

  const std::size_t header_size = sizeof(RingHeader);
  if (aligned_payload > max_size - header_size ||
      tsc_buffers > max_size - (header_size + aligned_payload)) {
    return 0;
  }
  return header_size + aligned_payload + tsc_buffers;
}

/**
 * Formats a ring in place
 */
RingHeader *ring_init_in_shm(void *shm_ptr, std::size_t shm_bytes,
                             std::uint64_t capacity,
                             std::uint32_t slot_size) noexcept;

// ============================================================================
// Policy
// ============================================================================

void ring_set_drop_policy(RingHeader *ring, RingDropPolicy policy,
                          std::uint32_t sample_rate = 1) noexcept;

RING_ALWAYS_INLINE RingDropPolicy
ring_get_drop_policy(const RingHeader *ring) noexcept {
  if (ring == nullptr) {
    return RingDropPolicy::BLOCK;
  }
  return ring->drop_policy.load(std::memory_order_relaxed);
}

[[nodiscard]] std::uint64_t
ring_get_dropped_count(const RingHeader *ring) noexcept;

// ============================================================================
// Producer interface
// ============================================================================

struct RingReservation {
  std::byte *slot{nullptr};
  std::uint64_t index{0};

  [[nodiscard]] explicit operator bool() const noexcept {
    return slot != nullptr;
  }
};

/// Claims one slot for writing. Returns an empty reservation when full.
[[nodiscard]] RingReservation ring_reserve(RingHeader *ring) noexcept;

/// Publishes a reservation, in claim order.
void ring_commit(RingHeader *ring, const RingReservation &reservation) noexcept;

/// Claims `slots` contiguous entries. Returns an empty reservation on failure.
[[nodiscard]] RingReservation ring_reserve_multi(RingHeader *ring,
                                                 std::uint64_t slots) noexcept;

/// Publishes a multi-slot reservation.
void ring_commit_multi(RingHeader *ring, const RingReservation &reservation,
                       std::uint64_t slots) noexcept;

// ============================================================================
// Consumer interface
// ============================================================================

/// Claims one slot for reading. Returns an empty reservation when empty.
[[nodiscard]] RingReservation ring_claim_read(RingHeader *ring) noexcept;

/// Releases a read reservation, in claim order.
void ring_release_read(RingHeader *ring,
                       const RingReservation &reservation) noexcept;

// ============================================================================
// High-level operations
// ============================================================================

[[nodiscard]] bool ring_push(RingHeader *ring, const void *data,
                             std::size_t size) noexcept;
[[nodiscard]] bool ring_pop(RingHeader *ring, void *out, std::size_t max_size,
                            std::size_t *bytes_read = nullptr) noexcept;
[[nodiscard]] bool ring_push_batch(RingHeader *ring, const void *data,
                                   std::size_t slot_count,
                                   std::size_t slot_size) noexcept;

/**
 * Snapshots up to 'max_slots' readable slot pointers without consuming them
 */
[[nodiscard]] std::size_t ring_peek_n(RingHeader *ring, const std::byte **slots,
                                      std::size_t max_slots) noexcept;

// ============================================================================
// Synchronization
// ============================================================================

void ring_wait_for_data(const RingHeader *ring,
                        std::uint64_t last_read_idx) noexcept;
void ring_notify_consumer(RingHeader *ring) noexcept;

// ============================================================================
// Telemetry
// ============================================================================

[[nodiscard]] float ring_occupancy_ratio(const RingHeader *ring) noexcept;
[[nodiscard]] bool ring_is_congested(const RingHeader *ring) noexcept;
[[nodiscard]] bool ring_is_above_high_watermark(const RingHeader *ring) noexcept;
[[nodiscard]] bool ring_is_below_low_watermark(const RingHeader *ring) noexcept;
void ring_set_watermarks(RingHeader *ring, std::uint32_t low,
                         std::uint32_t high) noexcept;
[[nodiscard]] float ring_backpressure_level(const RingHeader *ring) noexcept;
void ring_update_backpressure_policy(RingHeader *ring) noexcept;

std::uint64_t ring_next_journal_seq(RingHeader *ring) noexcept;
std::uint64_t ring_touch_hotness(RingHeader *ring) noexcept;
void ring_sample_tsc(RingHeader *ring, std::uint64_t tsc) noexcept;
void ring_record_ebpf_slot(RingHeader *ring, std::uint32_t slot) noexcept;
[[nodiscard]] std::uint64_t
ring_slot_latency_cycles(const RingHeader *ring, std::uint64_t idx) noexcept;

/**
 * Resets both indices to zero when the ring is quiescent
 */
[[nodiscard]] bool ring_compact(RingHeader *ring) noexcept;

/**
 * Grows the ring
 */
[[nodiscard]] bool ring_try_grow_capacity(RingHeader *ring,
                                          std::uint64_t new_capacity) noexcept;

// ============================================================================
// Zero-copy transport
// ============================================================================

[[nodiscard]] bool ring_setup_virtual_loop(std::size_t length,
                                           void **out_base) noexcept;

[[nodiscard]] bool ring_bind_io_fd(RingHeader *ring, int fd) noexcept;
[[nodiscard]] std::size_t ring_zero_copy_send(RingHeader *ring, int fd = -1,
                                              std::size_t max_slots = 64) noexcept;
[[nodiscard]] std::size_t ring_zero_copy_recv(RingHeader *ring, int fd = -1,
                                              std::size_t max_slots = 64) noexcept;

// ============================================================================
// Journal, NUMA and cluster helpers
// ============================================================================

[[nodiscard]] bool ring_journal_write(RingHeader *ring,
                                      const BridgeMessage &msg) noexcept;
[[nodiscard]] bool ring_journal_replay_next(RingHeader *ring,
                                            BridgeMessage &out) noexcept;
[[nodiscard]] std::uint64_t
ring_prefetch_hot_slot(const RingHeader *ring) noexcept;
[[nodiscard]] std::size_t ring_choose_numa_node(const RingHeader *ring,
                                                std::uint64_t key,
                                                std::size_t numa_nodes) noexcept;
[[nodiscard]] bool ring_try_cross_node_steal(RingHeader *victim,
                                             BridgeMessage &out) noexcept;

/**
 * Resolves a tensor reference to a pointer and a length
 */
[[nodiscard]] bool ring_extract_tensor_meta(const void *shm_base,
                                            std::size_t shm_size,
                                            const BridgeMessage &msg, void *&ptr,
                                            std::size_t &bytes) noexcept;

void ring_register_runtime(RingHeader *ring, std::uint32_t id) noexcept;
void ring_unregister_runtime(RingHeader *ring, std::uint32_t id) noexcept;

// ============================================================================
// Multi-producer composition
// ============================================================================

class MultiProducerRingSet {
public:
  MultiProducerRingSet(void *shm_base_ptr, std::size_t num_producers,
                       std::uint64_t capacity_per_ring,
                       std::uint32_t slot_size);

  [[nodiscard]] bool push(std::size_t producer_id, const void *data,
                          std::size_t size) noexcept;

  std::size_t poll_all(void *out_buffer, std::size_t max_size,
                       std::size_t &out_producer_id) noexcept;

  [[nodiscard]] std::size_t ring_count() const noexcept { return rings_.size(); }

private:
  std::vector<RingHeader *> rings_{};
  std::size_t last_polled_idx_{0};
};

} // namespace Sphere
