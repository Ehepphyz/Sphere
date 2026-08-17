// ringbuffer.cpp
#include "ringbuffer.h"
#include "shm_layout.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>

#if defined(_MSC_VER)
#include <intrin.h>
#elif defined(__x86_64__) || defined(__i386__)
#include <x86intrin.h>
#endif

#if defined(_WIN32)
#include <windows.h>
#elif defined(__APPLE__)
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>
#elif defined(__linux__)
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#endif

// ============================================================================
// Internal Helpers for Off-Header Side-Buffers (SHM Contiguous Layout)
// ============================================================================

static inline std::uint64_t ring_rdtsc() noexcept {
#if defined(_MSC_VER) && (defined(_M_IX86) || defined(_M_X64))
  return __rdtsc();

#elif defined(__x86_64__) || defined(__i386__)
  // Read Time-Stamp Counter (x86 architecture)
  std::uint32_t low, high;
  asm volatile("rdtsc" : "=a"(low), "=d"(high));
  return (static_cast<std::uint64_t>(high) << 32) | low;

#elif defined(__aarch64__)
  // Read Virtual Count Register (ARM64 / AArch64)
  std::uint64_t val;
  asm volatile("mrs %0, cntvct_el0" : "=r"(val));
  return val;

#elif defined(__arm__)
  // Read 64-bit Virtual Count Register (ARMv7 32-bit with Virtualization
  // Extensions)
  std::uint64_t val;
  asm volatile("mrrc p15, 1, %Q0, %R0, c14" : "=r"(val));
  return val;

#else
  // Portable fallback for unsupported architectures (RISC-V, PowerPC, etc.)
  // Note: steady_clock queries the OS high-resolution timer via vDSO
  using namespace std::chrono;
  return static_cast<std::uint64_t>(
      duration_cast<nanoseconds>(steady_clock::now().time_since_epoch())
          .count());
#endif
}

namespace Sphere {

// Compute pointer to the produce TSC side-buffer located after payload slots
static inline std::uint64_t *ring_tsc_produce_ptr(RingHeader *ring) noexcept {
  const std::size_t raw_payload =
      static_cast<std::size_t>(ring->capacity) * ring->slot_size;
  const std::size_t aligned_payload = (raw_payload + 7) & ~std::size_t{7};

  auto *base = reinterpret_cast<std::byte *>(ring) + sizeof(RingHeader) +
               aligned_payload;
  return reinterpret_cast<std::uint64_t *>(base);
}

// Compute pointer to the consume TSC side-buffer located after the produce
// buffer
static inline std::uint64_t *ring_tsc_consume_ptr(RingHeader *ring) noexcept {
  const std::size_t raw_payload =
      static_cast<std::size_t>(ring->capacity) * ring->slot_size;
  const std::size_t aligned_payload = (raw_payload + 7) & ~std::size_t{7};
  const std::size_t produce_tsc_bytes =
      static_cast<std::size_t>(ring->capacity) * sizeof(std::uint64_t);

  auto *base = reinterpret_cast<std::byte *>(ring) + sizeof(RingHeader) +
               aligned_payload + produce_tsc_bytes;
  return reinterpret_cast<std::uint64_t *>(base);
}

// Record production timestamp according to the adaptive sub-sampling mask
static inline void ring_mark_produced_tsc(RingHeader *ring,
                                          std::uint64_t w) noexcept {
  const std::uint32_t mask =
      ring->tsc_sample_mask.load(std::memory_order_relaxed);
  if ((w & mask) == 0) {
    std::uint64_t *tsc_produce = ring_tsc_produce_ptr(ring);
    const std::uint64_t idx = w & (ring->capacity - 1);
    tsc_produce[idx] = ring_rdtsc();
  }
}

// Record consumption timestamp according to the adaptive sub-sampling mask
static inline void ring_mark_consumed_tsc(RingHeader *ring,
                                          std::uint64_t r) noexcept {
  const std::uint32_t mask =
      ring->tsc_sample_mask.load(std::memory_order_relaxed);
  if ((r & mask) == 0) {
    std::uint64_t *tsc_consume = ring_tsc_consume_ptr(ring);
    const std::uint64_t idx = r & (ring->capacity - 1);
    tsc_consume[idx] = ring_rdtsc();
  }
}

// ============================================================================
// Shared Memory Initialization
// ============================================================================

RingHeader *ring_init_in_shm(void *shm_ptr, std::size_t shm_bytes,
                             std::uint64_t capacity,
                             std::uint32_t slot_size) noexcept {
  if (!shm_ptr || capacity == 0 || (capacity & (capacity - 1)) != 0 ||
      slot_size == 0) {
    return nullptr;
  }

  // Verify mapped shared memory is large enough for Header + Payloads + 2x TSC
  // side-buffers
  const std::size_t required_bytes = ring_total_shm_size(capacity, slot_size);
  if (shm_bytes < required_bytes) {
    return nullptr; // Mapped region is too small to safely fit TSC side-buffers
  }

  // Zero out memory region and set header fields
  std::memset(shm_ptr, 0, required_bytes);

  auto *ring = static_cast<RingHeader *>(shm_ptr);
  ring->capacity = capacity;
  ring->slot_size = slot_size;
  ring->drop_policy.store(RingDropPolicy::BLOCK, std::memory_order_relaxed);
  ring->sample_rate.store(1, std::memory_order_relaxed);
  ring->tsc_sample_mask.store(
      0, std::memory_order_relaxed); // 0 = 100% sampling rate

  return ring;
}

// ============================================================================
// Public Telemetry & Latency API
// ============================================================================

std::uint64_t ring_slot_latency_cycles(const RingHeader *ring,
                                       std::uint64_t idx) noexcept {
  if (!ring || ring->capacity == 0)
    return 0;

  const std::uint64_t i = idx & (ring->capacity - 1);
  const std::uint64_t *tsc_produce =
      ring_tsc_produce_ptr(const_cast<RingHeader *>(ring));
  const std::uint64_t *tsc_consume =
      ring_tsc_consume_ptr(const_cast<RingHeader *>(ring));

  const std::uint64_t p = tsc_produce[i];
  const std::uint64_t c = tsc_consume[i];

  return (c >= p) ? (c - p) : 0;
}

// ============================================================================
// Core Ring Buffer Mechanics
// ============================================================================

void ring_set_drop_policy(RingHeader *ring, RingDropPolicy policy,
                          std::uint32_t sample_rate) noexcept {
  if (!ring)
    return;
  ring->drop_policy.store(policy, std::memory_order_relaxed);
  ring->sample_rate.store(sample_rate > 0 ? sample_rate : 1,
                          std::memory_order_relaxed);
}

void ring_update_backpressure_policy(RingHeader *ring) noexcept {
  if (!ring) [[unlikely]]
    return;

  const float level = ring_backpressure_level(ring);

  // Apply high/low watermarks (hysteresis) to prevent rapid policy thrashing
  // near 85% capacity
  constexpr float HIGH_WATERMARK = 0.85f;
  constexpr float LOW_WATERMARK = 0.70f;

  const RingDropPolicy current_policy =
      ring->drop_policy.load(std::memory_order_relaxed);

  if (level > HIGH_WATERMARK && current_policy != RingDropPolicy::DROP_NEWEST) {
    ring->drop_policy.store(RingDropPolicy::DROP_NEWEST,
                            std::memory_order_relaxed);
  } else if (level < LOW_WATERMARK && current_policy != RingDropPolicy::BLOCK) {
    // Only restore BLOCK policy if drop policy wasn't explicitly configured for
    // sampling
    if (current_policy == RingDropPolicy::DROP_NEWEST) {
      ring->drop_policy.store(RingDropPolicy::BLOCK, std::memory_order_relaxed);
    }
  }
}

void ring_compact(RingHeader *ring) noexcept {
  if (!ring) [[unlikely]]
    return;

  // Acquire read_idx first to establish a memory barrier with potential
  // concurrent producers
  std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);

  // Only compact indices if the queue is completely idle and empty
  if (r == w) {
    // Double-check with CAS to safely reset read_idx without race conditions
    if (ring->read_idx.compare_exchange_strong(r, 0,
                                               std::memory_order_acq_rel)) {
      ring->write_idx.store(0, std::memory_order_release);
    }
  }
}

std::uint64_t ring_get_dropped_count(const RingHeader *ring) noexcept {
  if (!ring)
    return 0;
  return ring->dropped_count.load(std::memory_order_relaxed);
}

std::byte *ring_reserve_slot(RingHeader *ring) noexcept {
  if (!ring)
    return nullptr;

  const RingDropPolicy policy =
      ring->drop_policy.load(std::memory_order_relaxed);

  if (policy == RingDropPolicy::SAMPLE_ONLY) {
    const std::uint32_t rate =
        ring->sample_rate.load(std::memory_order_relaxed);
    if (rate > 1) {
      // Apply sampling rate check against the global activity sequence
      const std::uint64_t seq =
          ring->hotness_counter.load(std::memory_order_relaxed);
      if ((seq % rate) != 0) {
        ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
        return nullptr; // Sample dropped
      }
    }
  }

  std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);

  if ((w - r) >= ring->capacity) {
    switch (policy) {
    case RingDropPolicy::BLOCK:
    case RingDropPolicy::SAMPLE_ONLY:
    case RingDropPolicy::DROP_NEWEST:
      ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
      return nullptr;

    case RingDropPolicy::DROP_OLDEST: {
      // Attempt CAS to safely advance read pointer in multi-threaded contexts.
      // NOTE: In SPSC mode without slot sequence validation, this presents a
      // data race if the consumer is concurrently reading the target slot.
      if (ring->read_idx.compare_exchange_strong(r, r + 1,
                                                 std::memory_order_acq_rel)) {
        ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
        break;
      }
      // Fallthrough if race occurred on read_idx
      ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
      return nullptr;
    }
    }
  }

  return ring_slot_ptr(ring, w);
}

void ring_commit_write(RingHeader *ring) noexcept {
  if (!ring)
    return;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);

  // Sample production timestamp into the SHM side-buffer
  ring_mark_produced_tsc(ring, w);

  ring->write_idx.store(w + 1, std::memory_order_release);
  ring_touch_hotness(ring);
}

bool ring_commit_multi_write(RingHeader *ring, std::uint64_t slots) noexcept {
  if (!ring || slots == 0)
    return false;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);

  // Mark TSC timestamps across the batch write boundary
  for (std::uint64_t i = 0; i < slots; ++i) {
    ring_mark_produced_tsc(ring, w + i);
  }

  ring->write_idx.store(w + slots, std::memory_order_release);
  ring_touch_hotness(ring);
  return true;
}

const std::byte *ring_peek_slot(RingHeader *ring) noexcept {
  if (!ring)
    return nullptr;

  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);

  if (r == w) {
    return nullptr;
  }

  return ring_slot_ptr(ring, r);
}

void ring_commit_read(RingHeader *ring) noexcept {
  if (!ring)
    return;

  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);

  // Sample consumption timestamp into the SHM side-buffer
  ring_mark_consumed_tsc(ring, r);

  ring->read_idx.store(r + 1, std::memory_order_release);
}

bool ring_commit_multi_read(RingHeader *ring, std::uint64_t slots) noexcept {
  if (!ring || slots == 0)
    return false;

  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);

  // Mark TSC timestamps across the batch read boundary
  for (std::uint64_t i = 0; i < slots; ++i) {
    ring_mark_consumed_tsc(ring, r + i);
  }

  ring->read_idx.store(r + slots, std::memory_order_release);
  return true;
}

bool ring_push(RingHeader *ring, const void *data, std::size_t size) noexcept {
  if (!ring || !data || size == 0 || size > ring->slot_size)
    return false;

  // Check if queue was empty to trigger selective consumer notification
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const bool was_empty = (w == r);

  std::byte *slot = ring_reserve_slot(ring);
  if (!slot)
    return false;

  std::memcpy(slot, data, size);
  ring_commit_write(ring);

  if (was_empty) {
    ring_notify_consumer(ring);
  }
  return true;
}

bool ring_pop(RingHeader *ring, void *out, std::size_t max_size,
              std::size_t *bytes_read) noexcept {
  if (!ring || !out || max_size == 0)
    return false;

  const std::byte *slot = ring_peek_slot(ring);
  if (!slot)
    return false;

  const std::size_t copy_bytes =
      std::min(max_size, static_cast<std::size_t>(ring->slot_size));
  std::memcpy(out, slot, copy_bytes);

  if (bytes_read) {
    *bytes_read = copy_bytes;
  }

  ring_commit_read(ring);
  return true;
}

std::size_t ring_peek_n(RingHeader *ring, const std::byte **slots,
                        std::size_t max_slots) noexcept {
  if (!ring || !slots || max_slots == 0)
    return 0;

  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);
  const std::uint64_t available = w - r;
  const std::size_t count =
      std::min<std::size_t>(static_cast<std::size_t>(available), max_slots);

  for (std::size_t i = 0; i < count; ++i) {
    slots[i] = ring_slot_ptr(ring, r + static_cast<std::uint64_t>(i));
  }

  return count;
}

bool ring_push_batch(RingHeader *ring, const void *data, std::size_t slot_count,
                     std::size_t slot_size) noexcept {
  if (!ring || !data || slot_count == 0)
    return false;
  if (slot_size != ring->slot_size)
    return false;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);

  if ((w - r) + slot_count > ring->capacity)
    return false;

  const bool was_empty = (w == r);
  const auto *src = static_cast<const std::byte *>(data);

  for (std::size_t i = 0; i < slot_count; ++i) {
    std::byte *dst = ring_slot_ptr(ring, w + static_cast<std::uint64_t>(i));
    std::memcpy(dst, src + (i * slot_size), slot_size);
  }

  (void)ring_commit_multi_write(ring, static_cast<std::uint64_t>(slot_count));

  if (was_empty) {
    ring_notify_consumer(ring);
  }
  return true;
}

void ring_wait_for_data(const RingHeader *ring,
                        std::uint64_t last_read_idx) noexcept {
  if (!ring)
    return;

  while (ring->write_idx.load(std::memory_order_acquire) == last_read_idx) {
    ring->write_idx.wait(last_read_idx, std::memory_order_relaxed);
  }
}

void ring_notify_consumer(RingHeader *ring) noexcept {
  if (!ring)
    return;
  ring->write_idx.notify_one();
}

float ring_occupancy_ratio(const RingHeader *ring) noexcept {
  if (!ring || ring->capacity == 0)
    return 0.0f;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  return static_cast<float>(w - r) / static_cast<float>(ring->capacity);
}

[[nodiscard]] float ring_backpressure_level(const RingHeader *ring) noexcept {
  if (!ring || ring->capacity == 0) [[unlikely]]
    return 0.0f;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);

  // Unsigned arithmetic wrap-around handles counter rollover safely
  const std::uint64_t used = w - r;

  // Clamp ratio between 0.0f and 1.0f to guard against transient concurrency
  // spikes
  const float ratio =
      static_cast<float>(used) / static_cast<float>(ring->capacity);
  return std::clamp(ratio, 0.0f, 1.0f);
}

bool ring_is_congested(const RingHeader *ring) noexcept {
  return ring_occupancy_ratio(ring) > 0.85f;
}

bool ring_is_above_high_watermark(const RingHeader *ring) noexcept {
  if (!ring)
    return false;

  const std::uint32_t hw = ring->high_watermark.load(std::memory_order_relaxed);
  if (hw == 0)
    return false;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  return (w - r) >= static_cast<std::uint64_t>(hw);
}

bool ring_is_below_low_watermark(const RingHeader *ring) noexcept {
  if (!ring)
    return false;

  const std::uint32_t lw = ring->low_watermark.load(std::memory_order_relaxed);
  if (lw == 0)
    return true;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  return (w - r) <= static_cast<std::uint64_t>(lw);
}

void ring_set_watermarks(RingHeader *ring, std::uint32_t low,
                         std::uint32_t high) noexcept {
  if (!ring)
    return;

  ring->low_watermark.store(low, std::memory_order_relaxed);
  ring->high_watermark.store(high, std::memory_order_relaxed);
}

bool ring_try_grow_capacity(RingHeader *ring,
                            std::uint64_t new_capacity) noexcept {
  if (!ring)
    return false;

  // 1. Verify capacity does not exceed pre-allocated SHM bounds
  const std::uint32_t max_cap =
      ring->max_capacity.load(std::memory_order_relaxed);
  if (max_cap == 0 || new_capacity > static_cast<std::uint64_t>(max_cap)) {
    return false;
  }

  const std::uint64_t current = ring->capacity;
  if (new_capacity <= current)
    return false;

  // 2. Validate power-of-two alignment
  if ((new_capacity & (new_capacity - 1)) != 0)
    return false;

  // 3. Prevent resizing when unread payloads exist to avoid offset mismatch
  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  if (w != r) {
    return false; // Ring buffer must be completely empty to grow safely
  }

  // 4. Update active capacity safely
  ring->capacity = new_capacity;
  return true;
}

std::uint64_t ring_next_journal_seq(RingHeader *ring) noexcept {
  if (!ring)
    return 0;
  return ring->journal_seq.fetch_add(1, std::memory_order_relaxed) + 1;
}

bool ring_journal_write(RingHeader *ring, const BridgeMessage &msg) noexcept {
  if (!ring) [[unlikely]]
    return false;

  // Fast path validation: ensure slot size is large enough to fit the payload
  if (ring->slot_size < sizeof(BridgeMessage)) [[unlikely]]
    return false;

  BridgeMessage copy = msg;
  copy.journal_seq = static_cast<std::uint32_t>(ring_next_journal_seq(ring));

  return ring_push(ring, &copy, sizeof(copy));
}

bool ring_journal_replay_next(RingHeader *ring, BridgeMessage &out) noexcept {
  if (!ring) [[unlikely]]
    return false;

  // Fast path validation: ensure slot size matches expected payload
  if (ring->slot_size < sizeof(BridgeMessage)) [[unlikely]]
    return false;

  std::size_t bytes_read = 0;
  const bool popped = ring_pop(ring, &out, sizeof(out), &bytes_read);

  // Guarantee that a complete message frame was popped without truncation
  return popped && (bytes_read == sizeof(BridgeMessage));
}

std::uint64_t ring_touch_hotness(RingHeader *ring) noexcept {
  if (!ring)
    return 0;
  return ring->hotness_counter.fetch_add(1, std::memory_order_relaxed) + 1;
}

[[nodiscard]] std::uint64_t
ring_prefetch_hot_slot(const RingHeader *ring) noexcept {
  if (!ring || ring->capacity == 0) [[unlikely]]
    return 0;

  const std::uint64_t idx =
      ring->hotness_counter.load(std::memory_order_relaxed);

  // Fast bitwise modulo optimization (requires capacity to be a power of two)
  return idx & (ring->capacity - 1);
}

[[nodiscard]] std::size_t
ring_choose_numa_node(const RingHeader *ring, std::uint64_t key,
                      std::size_t numa_nodes) noexcept {
  if (!ring || numa_nodes == 0) [[unlikely]]
    return 0;

  // Incorporate the ring's global hotness counter for dynamic thread-safe
  // spreading
  const std::uint64_t hotness =
      ring->hotness_counter.load(std::memory_order_relaxed);

  // Mix key with hotness to prevent routing hot spots on fixed key sets
  const std::uint64_t mixed_key = key ^ (hotness * 0x9e3779b97f4a7c15ULL);

  return static_cast<std::size_t>(mixed_key % numa_nodes);
}

bool ring_try_cross_node_steal(RingHeader *victim,
                               BridgeMessage &out) noexcept {
  if (!victim) [[unlikely]]
    return false;

  // Fast path validation: ensure the victim ring slot size matches the expected
  // payload frame
  if (victim->slot_size < sizeof(BridgeMessage)) [[unlikely]]
    return false;

  // Quick non-blocking check to avoid unnecessary pop contention on empty
  // victim rings
  const std::uint64_t r = victim->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = victim->write_idx.load(std::memory_order_acquire);
  if (r == w) {
    return false; // Victim queue is empty
  }

  std::size_t bytes_read = 0;
  const bool popped = ring_pop(victim, &out, sizeof(out), &bytes_read);

  // Ensure a complete message frame was stolen without payload truncation
  return popped && (bytes_read == sizeof(BridgeMessage));
}

bool ring_extract_tensor_meta(const RingHeader *ring, const BridgeMessage &msg,
                              void *&ptr, std::size_t &bytes) noexcept {
  ptr = nullptr;
  bytes = 0;

  if (!ring) [[unlikely]]
    return false;

  // Validate that the incoming message type represents a valid Shared Memory
  // Reference
  if (msg.type != MsgType::SHM_REF) [[unlikely]]
    return false;

  const std::uint64_t offset = msg.shm_ref.offset;
  const std::uint64_t total_bytes = msg.shm_ref.total_bytes;

  // Basic sanity check on non-zero size payload
  if (total_bytes == 0) [[unlikely]]
    return false;

  // Resolve base address safely using std::byte arithmetic to prevent undefined
  // behaviors
  const auto *base_addr = reinterpret_cast<const std::byte *>(ring);
  ptr = const_cast<std::byte *>(base_addr + offset);
  bytes = static_cast<std::size_t>(total_bytes);

  return true;
}

void ring_sample_tsc(RingHeader *ring, std::uint64_t tsc) noexcept {
  if (!ring)
    return;
  ring->tsc_last_sample.store(tsc, std::memory_order_relaxed);
}

void ring_record_ebpf_slot(RingHeader *ring, std::uint32_t slot) noexcept {
  if (!ring)
    return;
  ring->ebpf_slot_id.store(slot, std::memory_order_relaxed);
}

// ============================================================================
// Virtual Memory & Platform Mapping Operations
// ============================================================================

bool ring_setup_virtual_loop(void *base, std::size_t length) noexcept {
  if (length == 0)
    return false;

#if defined(__linux__)

  int fd = memfd_create("ring_buffer_shm", 0);
  if (fd < 0)
    return false;

  if (ftruncate(fd, static_cast<off_t>(length)) < 0) {
    close(fd);
    return false;
  }

  // Reserve 2x length virtual address space without committing physical pages
  void *addr1 =
      mmap(base, length * 2, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (addr1 == MAP_FAILED) {
    close(fd);
    return false;
  }

  // Safely map both consecutive halves into contiguous memory address ranges
#if defined(MAP_FIXED_NOREPLACE)
  constexpr int map_flags = MAP_SHARED | MAP_FIXED_NOREPLACE;
#else
  constexpr int map_flags = MAP_SHARED | MAP_FIXED;
#endif

  void *first_half =
      mmap(addr1, length, PROT_READ | PROT_WRITE, map_flags, fd, 0);
  void *second_half = mmap(static_cast<std::byte *>(addr1) + length, length,
                           PROT_READ | PROT_WRITE, map_flags, fd, 0);

  close(
      fd); // File descriptor can safely be closed once mappings are established

  if (first_half == MAP_FAILED || second_half == MAP_FAILED) {
    munmap(addr1, length * 2);
    return false;
  }

  return true;

#elif defined(__APPLE__)

  char shm_name[64];
  std::snprintf(shm_name, sizeof(shm_name), "/ring_buf_%d_%p", getpid(), base);

  int fd = shm_open(shm_name, O_RDWR | O_CREAT | O_EXCL, 0600);
  if (fd < 0)
    return false;

  // Immediately unlink so backing storage is reclaimed when file descriptors
  // close
  shm_unlink(shm_name);

  if (ftruncate(fd, static_cast<off_t>(length)) < 0) {
    close(fd);
    return false;
  }

  void *addr1 =
      mmap(base, length * 2, PROT_NONE, MAP_PRIVATE | MAP_ANON, -1, 0);
  if (addr1 == MAP_FAILED) {
    close(fd);
    return false;
  }

  void *first_half = mmap(addr1, length, PROT_READ | PROT_WRITE,
                          MAP_SHARED | MAP_FIXED, fd, 0);
  void *second_half =
      mmap(static_cast<std::byte *>(addr1) + length, length,
           PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);

  close(fd);

  if (first_half == MAP_FAILED || second_half == MAP_FAILED) {
    munmap(addr1, length * 2);
    return false;
  }

  return true;

#elif defined(_WIN32)

  const DWORD length_low = static_cast<DWORD>(length & 0xFFFFFFFF);
  const DWORD length_high = static_cast<DWORD>(
      (static_cast<std::uint64_t>(length) >> 32) & 0xFFFFFFFF);

  HANDLE hMap = CreateFileMappingA(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE,
                                   length_high, length_low, NULL);

  if (!hMap)
    return false;

  void *first_half = nullptr;
  void *second_half = nullptr;

  // Retry loop to attempt contiguous address space allocation.
  // WARNING: VirtualFree creates a brief TOCTOU window before MapViewOfFileEx.
  for (int attempts = 0; attempts < 10; ++attempts) {
    void *placeholder =
        VirtualAlloc(base, length * 2, MEM_RESERVE, PAGE_NOACCESS);
    if (!placeholder)
      break;

    // Release placeholder directly prior to mapping
    VirtualFree(placeholder, 0, MEM_RELEASE);

    first_half =
        MapViewOfFileEx(hMap, FILE_MAP_ALL_ACCESS, 0, 0, length, placeholder);

    if (first_half) {
      second_half =
          MapViewOfFileEx(hMap, FILE_MAP_ALL_ACCESS, 0, 0, length,
                          static_cast<std::byte *>(first_half) + length);

      if (second_half) {
        CloseHandle(hMap);
        return true;
      }

      UnmapViewOfFile(first_half);
      first_half = nullptr;
    }
  }

  CloseHandle(hMap);
  return false;

#else
  (void)base;
  (void)length;
  return false;
#endif
}

bool ring_bind_io_fd(RingHeader *ring, int fd) noexcept {
  if (!ring || fd < 0)
    return false;
  ring->io_fd.store(fd, std::memory_order_relaxed);
  return true;
}

std::size_t ring_zero_copy_send(RingHeader *ring, int fd,
                                std::size_t max_slots) noexcept {
#if !defined(_WIN32)
  if (!ring || max_slots == 0)
    return 0;

  int use_fd = (fd >= 0) ? fd : ring->io_fd.load(std::memory_order_relaxed);
  if (use_fd < 0)
    return 0;

  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);
  const std::uint64_t available = w - r;
  const std::size_t count =
      std::min<std::size_t>(static_cast<std::size_t>(available),
                            std::min<std::size_t>(max_slots, 64));

  if (count == 0)
    return 0;

  struct iovec iov[64];
  for (std::size_t i = 0; i < count; ++i) {
    const std::byte *ptr =
        ring_slot_ptr(ring, r + static_cast<std::uint64_t>(i));
    iov[i].iov_base = const_cast<std::byte *>(ptr);
    iov[i].iov_len = ring->slot_size;
  }

  ssize_t written = writev(use_fd, iov, static_cast<int>(count));
  if (written <= 0)
    return 0;

  // Only commit whole, fully-transmitted slots to preserve frame boundaries
  const std::uint64_t slots_consumed =
      static_cast<std::uint64_t>(written / ring->slot_size);
  if (slots_consumed > 0) {
    (void)ring_commit_multi_read(ring, slots_consumed);
  }

  return static_cast<std::size_t>(written);
#else
  (void)ring;
  (void)fd;
  (void)max_slots;
  return 0;
#endif
}

std::size_t ring_zero_copy_recv(RingHeader *ring, int fd,
                                std::size_t max_slots) noexcept {
#if !defined(_WIN32)
  if (!ring || max_slots == 0)
    return 0;

  int use_fd = (fd >= 0) ? fd : ring->io_fd.load(std::memory_order_relaxed);
  if (use_fd < 0)
    return 0;

  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);
  const std::uint64_t free_slots = ring->capacity - (w - r);
  const std::size_t count =
      std::min<std::size_t>(static_cast<std::size_t>(free_slots),
                            std::min<std::size_t>(max_slots, 64));

  if (count == 0)
    return 0;

  struct iovec iov[64];
  for (std::size_t i = 0; i < count; ++i) {
    std::byte *ptr = ring_slot_ptr(ring, w + static_cast<std::uint64_t>(i));
    iov[i].iov_base = ptr;
    iov[i].iov_len = ring->slot_size;
  }

  ssize_t read_bytes = readv(use_fd, iov, static_cast<int>(count));
  if (read_bytes <= 0)
    return 0;

  // Only commit fully-received slots to prevent reading incomplete frames
  const std::uint64_t slots_filled =
      static_cast<std::uint64_t>(read_bytes / ring->slot_size);
  if (slots_filled > 0) {
    const bool was_empty = (w == r);
    (void)ring_commit_multi_write(ring, slots_filled);

    if (was_empty) {
      ring_notify_consumer(ring);
    }
  }

  return static_cast<std::size_t>(read_bytes);
#else
  (void)ring;
  (void)fd;
  (void)max_slots;
  return 0;
#endif
}

// ============================================================================
// Multi-Producer Composition Implementation (Partitioned RingSet)
// ============================================================================

MultiProducerRingSet::MultiProducerRingSet(void *shm_base_ptr,
                                           std::size_t num_producers,
                                           std::uint64_t capacity_per_ring,
                                           std::uint32_t slot_size)
    : num_rings_(num_producers) {
  if (!shm_base_ptr || num_producers == 0)
    return;

  std::size_t single_ring_bytes =
      ring_total_shm_size(capacity_per_ring, slot_size);
  auto *byte_ptr = static_cast<std::byte *>(shm_base_ptr);

  rings_.reserve(num_producers);
  for (std::size_t i = 0; i < num_producers; ++i) {
    void *current_shm = byte_ptr + (i * single_ring_bytes);
    RingHeader *ring = ring_init_in_shm(current_shm, single_ring_bytes,
                                        capacity_per_ring, slot_size);
    rings_.push_back(ring);
  }
}

bool MultiProducerRingSet::push(std::size_t producer_id, const void *data,
                                std::size_t size) noexcept {
  if (producer_id >= rings_.size() || !rings_[producer_id])
    return false;
  return ring_push(rings_[producer_id], data, size);
}

std::size_t
MultiProducerRingSet::poll_all(void *out_buffer, std::size_t max_size,
                               std::size_t &out_producer_id) noexcept {
  if (rings_.empty() || !out_buffer)
    return 0;

  const std::size_t count = rings_.size();
  for (std::size_t i = 0; i < count; ++i) {
    std::size_t ring_to_check = (last_polled_idx_ + i) % count;
    std::size_t bytes_read = 0;

    if (ring_pop(rings_[ring_to_check], out_buffer, max_size, &bytes_read)) {
      last_polled_idx_ =
          (ring_to_check + 1) % count; // Fair Round-Robin scheduling
      out_producer_id = ring_to_check;
      return bytes_read;
    }
  }
  return 0; // No messages available across all rings
}

void ring_register_runtime(RingHeader *ring, std::uint32_t id) noexcept {
  if (!ring) [[unlikely]]
    return;

  // Boundary protection against cluster array overflow
  if (id >= MAX_RUNTIMES) [[unlikely]]
    return;

  // Use release ordering so initialization writes are visible before
  // registration completes
  ring->cluster_runtime_ids[id].store(1, std::memory_order_release);
}

/// Method 10B: Unregisters an active runtime cluster node ID from the ring
/// header
void ring_unregister_runtime(RingHeader *ring, std::uint32_t id) noexcept {
  if (!ring) [[unlikely]]
    return;

  // Boundary protection against cluster array overflow
  if (id >= MAX_RUNTIMES) [[unlikely]]
    return;

  ring->cluster_runtime_ids[id].store(0, std::memory_order_release);
}

} // namespace Sphere
