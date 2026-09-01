// ringbuffer.cpp

// Implementation of the byte-slot shared-memory ring.

#include "ringbuffer.h"
#include "lockfree_ring.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

#if defined(_MSC_VER)
#include <intrin.h>
#elif defined(__x86_64__) || defined(__i386__)
#include <x86intrin.h>
#endif

#if defined(_WIN32)
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>
#endif

namespace {

/// Cycle counter, or a monotonic clock where none is available.
inline std::uint64_t ring_rdtsc() noexcept {
#if defined(_MSC_VER) && (defined(_M_IX86) || defined(_M_X64))
  return __rdtsc();
#elif defined(__x86_64__) || defined(__i386__)
  std::uint32_t low = 0;
  std::uint32_t high = 0;
  asm volatile("rdtsc" : "=a"(low), "=d"(high));
  return (static_cast<std::uint64_t>(high) << 32) | low;
#elif defined(__aarch64__)
  std::uint64_t val = 0;
  asm volatile("mrs %0, cntvct_el0" : "=r"(val));
  return val;
#else
  using namespace std::chrono;
  return static_cast<std::uint64_t>(
      duration_cast<nanoseconds>(steady_clock::now().time_since_epoch())
          .count());
#endif
}

inline void ring_pause() noexcept {
#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__)
  _mm_pause();
#elif defined(__aarch64__) || defined(__arm__)
  asm volatile("yield" ::: "memory");
#endif
}

} // namespace

namespace Sphere {

namespace {

std::uint64_t *tsc_produce_ptr(RingHeader *ring) noexcept {
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  const std::size_t raw_payload =
      static_cast<std::size_t>(cap) * ring->slot_size;
  const std::size_t aligned_payload = (raw_payload + 7) & ~std::size_t{7};
  return reinterpret_cast<std::uint64_t *>(reinterpret_cast<std::byte *>(ring) +
                                           sizeof(RingHeader) +
                                           aligned_payload);
}

std::uint64_t *tsc_consume_ptr(RingHeader *ring) noexcept {
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  const std::size_t raw_payload =
      static_cast<std::size_t>(cap) * ring->slot_size;
  const std::size_t aligned_payload = (raw_payload + 7) & ~std::size_t{7};
  const std::size_t produce_bytes =
      static_cast<std::size_t>(cap) * sizeof(std::uint64_t);
  return reinterpret_cast<std::uint64_t *>(reinterpret_cast<std::byte *>(ring) +
                                           sizeof(RingHeader) +
                                           aligned_payload + produce_bytes);
}

void mark_produced_tsc(RingHeader *ring, std::uint64_t w) noexcept {
  const std::uint32_t mask =
      ring->tsc_sample_mask.load(std::memory_order_relaxed);
  if ((w & mask) != 0) {
    return;
  }
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) {
    return;
  }
  tsc_produce_ptr(ring)[w & (cap - 1)] = ring_rdtsc();
}

void mark_consumed_tsc(RingHeader *ring, std::uint64_t r) noexcept {
  const std::uint32_t mask =
      ring->tsc_sample_mask.load(std::memory_order_relaxed);
  if ((r & mask) != 0) {
    return;
  }
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) {
    return;
  }
  tsc_consume_ptr(ring)[r & (cap - 1)] = ring_rdtsc();
}

void publish_in_order(std::atomic<std::uint64_t> &publish_index,
                      std::uint64_t target, std::uint64_t n) noexcept {
  constexpr unsigned kSpinBudget = 64;   // cheap: the predecessor is running
  constexpr unsigned kYieldBudget = 512; // it was descheduled; hand over the CPU

  unsigned attempts = 0;
  while (publish_index.load(std::memory_order_acquire) != target) {
    if (attempts < kSpinBudget) {
      ring_pause();
    } else if (attempts < kYieldBudget) {
      std::this_thread::yield();
    } else {
      std::this_thread::sleep_for(std::chrono::microseconds(50));
    }
    ++attempts;
  }
  publish_index.store(target + n, std::memory_order_release);
}

} // namespace

// ============================================================================
// Initialization
// ============================================================================

RingHeader *ring_init_in_shm(void *shm_ptr, std::size_t shm_bytes,
                             std::uint64_t capacity,
                             std::uint32_t slot_size) noexcept {
  if (shm_ptr == nullptr || capacity == 0 ||
      (capacity & (capacity - 1)) != 0 || slot_size == 0) {
    return nullptr;
  }

  const std::size_t required = ring_total_shm_size(capacity, slot_size);
  if (required == 0 || shm_bytes < required) {
    return nullptr;
  }

  std::memset(shm_ptr, 0, required);

  auto *ring = static_cast<RingHeader *>(shm_ptr);
  ring->capacity.store(capacity, std::memory_order_relaxed);
  ring->slot_size = slot_size;
  ring->max_capacity.store(static_cast<std::uint32_t>(capacity),
                           std::memory_order_relaxed);
  ring->drop_policy.store(RingDropPolicy::BLOCK, std::memory_order_relaxed);
  ring->sample_rate.store(1, std::memory_order_relaxed);
  ring->tsc_sample_mask.store(0, std::memory_order_relaxed);
  ring->io_fd.store(-1, std::memory_order_relaxed);
  return ring;
}

// ============================================================================
// Policy
// ============================================================================

void ring_set_drop_policy(RingHeader *ring, RingDropPolicy policy,
                          std::uint32_t sample_rate) noexcept {
  if (ring == nullptr) {
    return;
  }
  ring->drop_policy.store(policy, std::memory_order_relaxed);
  ring->sample_rate.store(sample_rate > 0 ? sample_rate : 1,
                          std::memory_order_relaxed);
}

std::uint64_t ring_get_dropped_count(const RingHeader *ring) noexcept {
  return (ring == nullptr)
             ? 0
             : ring->dropped_count.load(std::memory_order_relaxed);
}

void ring_update_backpressure_policy(RingHeader *ring) noexcept {
  if (ring == nullptr) [[unlikely]] {
    return;
  }

  constexpr float HIGH_WATERMARK = 0.85f;
  constexpr float LOW_WATERMARK = 0.70f;

  const float level = ring_backpressure_level(ring);
  const RingDropPolicy current =
      ring->drop_policy.load(std::memory_order_relaxed);

  if (level > HIGH_WATERMARK && current == RingDropPolicy::BLOCK) {
    ring->drop_policy.store(RingDropPolicy::DROP_NEWEST,
                            std::memory_order_relaxed);
  } else if (level < LOW_WATERMARK && current == RingDropPolicy::DROP_NEWEST) {
    ring->drop_policy.store(RingDropPolicy::BLOCK, std::memory_order_relaxed);
  }
}

// ============================================================================
// Producer
// ============================================================================

RingReservation ring_reserve(RingHeader *ring) noexcept {
  RingReservation reservation{};
  if (ring == nullptr) {
    return reservation;
  }

  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) {
    return reservation;
  }

  const RingDropPolicy policy =
      ring->drop_policy.load(std::memory_order_relaxed);

  if (policy == RingDropPolicy::SAMPLE_ONLY) {
    const std::uint32_t rate = ring->sample_rate.load(std::memory_order_relaxed);
    if (rate > 1) {
      const std::uint64_t seq =
          ring->hotness_counter.fetch_add(1, std::memory_order_relaxed);
      if ((seq % rate) != 0) {
        ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
        return reservation;
      }
    }
  }

  std::uint64_t w = ring->write_claim.load(std::memory_order_relaxed);
  for (;;) {
    const std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);

    if ((w - r) >= cap) {
      if (policy == RingDropPolicy::DROP_OLDEST) {
        std::uint64_t old_r = r;
        if (ring->read_claim.compare_exchange_strong(
                old_r, r + 1, std::memory_order_acq_rel,
                std::memory_order_relaxed)) {
          publish_in_order(ring->read_idx, r, 1);
          ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
          continue;
        }
      }
      if (policy != RingDropPolicy::BLOCK) {
        ring->dropped_count.fetch_add(1, std::memory_order_relaxed);
      }
      return reservation;
    }

    if (ring->write_claim.compare_exchange_weak(w, w + 1,
                                                std::memory_order_acq_rel,
                                                std::memory_order_relaxed)) {
      break;
    }
    ring_pause();
  }

  reservation.index = w;
  reservation.slot = ring_slot_ptr(ring, w);
  return reservation;
}

void ring_commit(RingHeader *ring, const RingReservation &reservation) noexcept {
  if (ring == nullptr || reservation.slot == nullptr) {
    return;
  }
  mark_produced_tsc(ring, reservation.index);
  publish_in_order(ring->write_idx, reservation.index, 1);
  ring_touch_hotness(ring);
}

RingReservation ring_reserve_multi(RingHeader *ring,
                                   std::uint64_t slots) noexcept {
  RingReservation reservation{};
  if (ring == nullptr || slots == 0) {
    return reservation;
  }

  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0 || slots > cap) {
    return reservation;
  }

  std::uint64_t w = ring->write_claim.load(std::memory_order_relaxed);
  for (;;) {
    const std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);
    if ((w - r) + slots > cap) {
      return reservation; // caller retries; see the note in ring_reserve
    }
    if (ring->write_claim.compare_exchange_weak(w, w + slots,
                                                std::memory_order_acq_rel,
                                                std::memory_order_relaxed)) {
      break;
    }
    ring_pause();
  }

  reservation.index = w;
  reservation.slot = ring_slot_ptr(ring, w);
  return reservation;
}

void ring_commit_multi(RingHeader *ring, const RingReservation &reservation,
                       std::uint64_t slots) noexcept {
  if (ring == nullptr || reservation.slot == nullptr || slots == 0) {
    return;
  }
  for (std::uint64_t i = 0; i < slots; ++i) {
    mark_produced_tsc(ring, reservation.index + i);
  }
  publish_in_order(ring->write_idx, reservation.index, slots);
  ring_touch_hotness(ring);
}

// ============================================================================
// Consumer
// ============================================================================

RingReservation ring_claim_read(RingHeader *ring) noexcept {
  RingReservation reservation{};
  if (ring == nullptr) {
    return reservation;
  }
  if (ring->capacity.load(std::memory_order_relaxed) == 0) {
    return reservation;
  }

  std::uint64_t r = ring->read_claim.load(std::memory_order_relaxed);
  for (;;) {
    const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);
    if (r >= w) {
      return reservation; // empty
    }
    if (ring->read_claim.compare_exchange_weak(r, r + 1,
                                               std::memory_order_acq_rel,
                                               std::memory_order_relaxed)) {
      break;
    }
    ring_pause();
  }

  reservation.index = r;
  reservation.slot = ring_slot_ptr(ring, r);
  return reservation;
}

void ring_release_read(RingHeader *ring,
                       const RingReservation &reservation) noexcept {
  if (ring == nullptr || reservation.slot == nullptr) {
    return;
  }
  mark_consumed_tsc(ring, reservation.index);
  publish_in_order(ring->read_idx, reservation.index, 1);
}

// ============================================================================
// High-level operations
// ============================================================================

bool ring_push(RingHeader *ring, const void *data, std::size_t size) noexcept {
  if (ring == nullptr || data == nullptr || size == 0 ||
      size > ring->slot_size) {
    return false;
  }

  const std::uint64_t before_w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t before_r = ring->read_idx.load(std::memory_order_relaxed);
  const bool was_empty = (before_w == before_r);

  const RingReservation reservation = ring_reserve(ring);
  if (!reservation) {
    return false;
  }

  std::memcpy(reservation.slot, data, size);
  if (size < ring->slot_size) {
    // Clear the tail
    std::memset(reservation.slot + size, 0, ring->slot_size - size);
  }
  ring_commit(ring, reservation);

  if (was_empty) {
    ring_notify_consumer(ring);
  }
  return true;
}

bool ring_pop(RingHeader *ring, void *out, std::size_t max_size,
              std::size_t *bytes_read) noexcept {
  if (ring == nullptr || out == nullptr || max_size == 0) {
    return false;
  }

  const RingReservation reservation = ring_claim_read(ring);
  if (!reservation) {
    return false;
  }

  const std::size_t copy_bytes =
      std::min(max_size, static_cast<std::size_t>(ring->slot_size));
  std::memcpy(out, reservation.slot, copy_bytes);
  if (bytes_read != nullptr) {
    *bytes_read = copy_bytes;
  }

  ring_release_read(ring, reservation);
  return true;
}

std::size_t ring_peek_n(RingHeader *ring, const std::byte **slots,
                        std::size_t max_slots) noexcept {
  if (ring == nullptr || slots == nullptr || max_slots == 0) {
    return 0;
  }
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);
  const std::uint64_t available = (w > r) ? (w - r) : 0;
  const std::size_t count =
      std::min<std::size_t>(static_cast<std::size_t>(available), max_slots);

  for (std::size_t i = 0; i < count; ++i) {
    slots[i] = ring_slot_ptr(ring, r + static_cast<std::uint64_t>(i));
  }
  return count;
}

bool ring_push_batch(RingHeader *ring, const void *data, std::size_t slot_count,
                     std::size_t slot_size) noexcept {
  if (ring == nullptr || data == nullptr || slot_count == 0) {
    return false;
  }
  if (slot_size != ring->slot_size) {
    return false;
  }

  const std::uint64_t before_w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t before_r = ring->read_idx.load(std::memory_order_relaxed);
  const bool was_empty = (before_w == before_r);

  const RingReservation reservation =
      ring_reserve_multi(ring, static_cast<std::uint64_t>(slot_count));
  if (!reservation) {
    return false;
  }

  const auto *src = static_cast<const std::byte *>(data);
  for (std::size_t i = 0; i < slot_count; ++i) {
    std::byte *dst =
        ring_slot_ptr(ring, reservation.index + static_cast<std::uint64_t>(i));
    std::memcpy(dst, src + (i * slot_size), slot_size);
  }

  ring_commit_multi(ring, reservation, static_cast<std::uint64_t>(slot_count));

  if (was_empty) {
    ring_notify_consumer(ring);
  }
  return true;
}

// ============================================================================
// Synchronization
// ============================================================================

void ring_wait_for_data(const RingHeader *ring,
                        std::uint64_t last_read_idx) noexcept {
  if (ring == nullptr) {
    return;
  }
  while (ring->write_idx.load(std::memory_order_acquire) == last_read_idx) {
    ring->write_idx.wait(last_read_idx, std::memory_order_relaxed);
  }
}

void ring_notify_consumer(RingHeader *ring) noexcept {
  if (ring != nullptr) {
    ring->write_idx.notify_all();
  }
}

// ============================================================================
// Telemetry
// ============================================================================

float ring_occupancy_ratio(const RingHeader *ring) noexcept {
  return ring_backpressure_level(ring);
}

float ring_backpressure_level(const RingHeader *ring) noexcept {
  if (ring == nullptr) [[unlikely]] {
    return 0.0f;
  }
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) [[unlikely]] {
    return 0.0f;
  }
  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t used = (w > r) ? (w - r) : 0;
  const float ratio = static_cast<float>(used) / static_cast<float>(cap);
  return std::clamp(ratio, 0.0f, 1.0f);
}

bool ring_is_congested(const RingHeader *ring) noexcept {
  return ring_backpressure_level(ring) > 0.85f;
}

bool ring_is_above_high_watermark(const RingHeader *ring) noexcept {
  if (ring == nullptr) {
    return false;
  }
  const std::uint32_t hw = ring->high_watermark.load(std::memory_order_relaxed);
  if (hw == 0) {
    return false;
  }
  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  return (w > r) && ((w - r) >= static_cast<std::uint64_t>(hw));
}

bool ring_is_below_low_watermark(const RingHeader *ring) noexcept {
  if (ring == nullptr) {
    return false;
  }
  const std::uint32_t lw = ring->low_watermark.load(std::memory_order_relaxed);
  if (lw == 0) {
    return true;
  }
  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t used = (w > r) ? (w - r) : 0;
  return used <= static_cast<std::uint64_t>(lw);
}

void ring_set_watermarks(RingHeader *ring, std::uint32_t low,
                         std::uint32_t high) noexcept {
  if (ring == nullptr) {
    return;
  }
  ring->low_watermark.store(low, std::memory_order_relaxed);
  ring->high_watermark.store(high, std::memory_order_relaxed);
}

std::uint64_t ring_next_journal_seq(RingHeader *ring) noexcept {
  return (ring == nullptr)
             ? 0
             : ring->journal_seq.fetch_add(1, std::memory_order_relaxed) + 1;
}

std::uint64_t ring_touch_hotness(RingHeader *ring) noexcept {
  return (ring == nullptr)
             ? 0
             : ring->hotness_counter.fetch_add(1, std::memory_order_relaxed) + 1;
}

void ring_sample_tsc(RingHeader *ring, std::uint64_t tsc) noexcept {
  if (ring != nullptr) {
    ring->tsc_last_sample.store(tsc, std::memory_order_relaxed);
  }
}

void ring_record_ebpf_slot(RingHeader *ring, std::uint32_t slot) noexcept {
  if (ring != nullptr) {
    ring->ebpf_slot_id.store(slot, std::memory_order_relaxed);
  }
}

std::uint64_t ring_slot_latency_cycles(const RingHeader *ring,
                                       std::uint64_t idx) noexcept {
  if (ring == nullptr) {
    return 0;
  }
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) {
    return 0;
  }
  auto *mutable_ring = const_cast<RingHeader *>(ring);
  const std::uint64_t i = idx & (cap - 1);
  const std::uint64_t p = tsc_produce_ptr(mutable_ring)[i];
  const std::uint64_t c = tsc_consume_ptr(mutable_ring)[i];
  return (c >= p) ? (c - p) : 0;
}

bool ring_compact(RingHeader *ring) noexcept {
  if (ring == nullptr) [[unlikely]] {
    return false;
  }

  const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);
  const std::uint64_t wc = ring->write_claim.load(std::memory_order_acquire);
  const std::uint64_t rc = ring->read_claim.load(std::memory_order_acquire);

  if (w != r || wc != w || rc != r) {
    return false;
  }

  ring->write_claim.store(0, std::memory_order_relaxed);
  ring->read_claim.store(0, std::memory_order_relaxed);
  ring->write_idx.store(0, std::memory_order_release);
  ring->read_idx.store(0, std::memory_order_release);
  return true;
}

bool ring_try_grow_capacity(RingHeader *ring,
                            std::uint64_t new_capacity) noexcept {
  if (ring == nullptr) {
    return false;
  }

  const std::uint32_t max_cap = ring->max_capacity.load(std::memory_order_relaxed);
  if (max_cap == 0 || new_capacity > static_cast<std::uint64_t>(max_cap)) {
    return false;
  }
  if ((new_capacity & (new_capacity - 1)) != 0) {
    return false;
  }
  const std::uint64_t current = ring->capacity.load(std::memory_order_relaxed);
  if (new_capacity <= current) {
    return false;
  }

  if (!ring_compact(ring)) {
    return false;
  }

  ring->capacity.store(new_capacity, std::memory_order_release);
  return true;
}

// ============================================================================
// Zero-copy transport
// ============================================================================

bool ring_setup_virtual_loop(std::size_t length, void **out_base) noexcept {
  if (length == 0 || out_base == nullptr) {
    return false;
  }
  *out_base = nullptr;

#if defined(__linux__) || defined(__APPLE__)
#if defined(__linux__)
  int fd = ::memfd_create("sphere_ring_loop", 0);
#else
  char shm_name[64];
  std::snprintf(shm_name, sizeof(shm_name), "/sphere_ring_%d_%p", ::getpid(),
                static_cast<void *>(out_base));
  int fd = ::shm_open(shm_name, O_RDWR | O_CREAT | O_EXCL, 0600);
  if (fd >= 0) {
    ::shm_unlink(shm_name);
  }
#endif
  if (fd < 0) {
    return false;
  }
  if (::ftruncate(fd, static_cast<off_t>(length)) < 0) {
    ::close(fd);
    return false;
  }

  // Reserve a 2 * length window, then map the same object over each half.
  void *window =
      ::mmap(nullptr, length * 2, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (window == MAP_FAILED) {
    ::close(fd);
    return false;
  }

  void *first = ::mmap(window, length, PROT_READ | PROT_WRITE,
                       MAP_SHARED | MAP_FIXED, fd, 0);
  void *second = ::mmap(static_cast<std::byte *>(window) + length, length,
                        PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);
  ::close(fd);

  if (first == MAP_FAILED || second == MAP_FAILED) {
    ::munmap(window, length * 2);
    return false;
  }

  *out_base = window;
  return true;

#elif defined(_WIN32)
  const DWORD low = static_cast<DWORD>(length & 0xFFFFFFFFu);
  const DWORD high =
      static_cast<DWORD>((static_cast<std::uint64_t>(length) >> 32) & 0xFFFFFFFFu);

  HANDLE mapping = ::CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr,
                                        PAGE_READWRITE, high, low, nullptr);
  if (mapping == nullptr) {
    return false;
  }

  for (int attempt = 0; attempt < 10; ++attempt) {
    void *placeholder =
        ::VirtualAlloc(nullptr, length * 2, MEM_RESERVE, PAGE_NOACCESS);
    if (placeholder == nullptr) {
      break;
    }
    // Releasing before mapping opens a window in which another thread can take
    // the address; the retry loop is the mitigation.
    ::VirtualFree(placeholder, 0, MEM_RELEASE);

    void *first =
        ::MapViewOfFileEx(mapping, FILE_MAP_ALL_ACCESS, 0, 0, length, placeholder);
    if (first == nullptr) {
      continue;
    }
    void *second =
        ::MapViewOfFileEx(mapping, FILE_MAP_ALL_ACCESS, 0, 0, length,
                          static_cast<std::byte *>(first) + length);
    if (second != nullptr) {
      ::CloseHandle(mapping);
      *out_base = first;
      return true;
    }
    ::UnmapViewOfFile(first);
  }

  ::CloseHandle(mapping);
  return false;
#else
  return false;
#endif
}

bool ring_bind_io_fd(RingHeader *ring, int fd) noexcept {
  if (ring == nullptr || fd < 0) {
    return false;
  }
  ring->io_fd.store(fd, std::memory_order_relaxed);
  return true;
}

std::size_t ring_zero_copy_send(RingHeader *ring, int fd,
                                std::size_t max_slots) noexcept {
#if !defined(_WIN32)
  if (ring == nullptr || max_slots == 0) {
    return 0;
  }
  const int use_fd =
      (fd >= 0) ? fd : ring->io_fd.load(std::memory_order_relaxed);
  if (use_fd < 0) {
    return 0;
  }

  const std::uint64_t r = ring->read_idx.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_acquire);
  const std::uint64_t available = (w > r) ? (w - r) : 0;
  const std::size_t count = std::min<std::size_t>(
      static_cast<std::size_t>(available), std::min<std::size_t>(max_slots, 64));
  if (count == 0) {
    return 0;
  }

  struct iovec iov[64];
  for (std::size_t i = 0; i < count; ++i) {
    const std::byte *ptr = ring_slot_ptr(ring, r + static_cast<std::uint64_t>(i));
    iov[i].iov_base = const_cast<std::byte *>(ptr);
    iov[i].iov_len = ring->slot_size;
  }

  const ssize_t written = ::writev(use_fd, iov, static_cast<int>(count));
  if (written <= 0) {
    return 0;
  }

  // Only retire whole slots so a partial write never splits a record.
  const std::uint64_t consumed =
      static_cast<std::uint64_t>(written) / ring->slot_size;
  for (std::uint64_t i = 0; i < consumed; ++i) {
    const RingReservation res = ring_claim_read(ring);
    if (!res) {
      break;
    }
    ring_release_read(ring, res);
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
  if (ring == nullptr || max_slots == 0) {
    return 0;
  }
  const int use_fd =
      (fd >= 0) ? fd : ring->io_fd.load(std::memory_order_relaxed);
  if (use_fd < 0) {
    return 0;
  }

  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  const std::uint64_t w = ring->write_idx.load(std::memory_order_relaxed);
  const std::uint64_t r = ring->read_idx.load(std::memory_order_acquire);
  const std::uint64_t free_slots = cap - ((w > r) ? (w - r) : 0);
  const std::size_t count = std::min<std::size_t>(
      static_cast<std::size_t>(free_slots), std::min<std::size_t>(max_slots, 64));
  if (count == 0) {
    return 0;
  }

  const RingReservation reservation =
      ring_reserve_multi(ring, static_cast<std::uint64_t>(count));
  if (!reservation) {
    return 0;
  }

  struct iovec iov[64];
  for (std::size_t i = 0; i < count; ++i) {
    iov[i].iov_base =
        ring_slot_ptr(ring, reservation.index + static_cast<std::uint64_t>(i));
    iov[i].iov_len = ring->slot_size;
  }

  const ssize_t received = ::readv(use_fd, iov, static_cast<int>(count));
  const bool was_empty = (w == r);

  ring_commit_multi(ring, reservation, static_cast<std::uint64_t>(count));

  if (received <= 0) {
    return 0;
  }
  if (was_empty) {
    ring_notify_consumer(ring);
  }
  return static_cast<std::size_t>(received);
#else
  (void)ring;
  (void)fd;
  (void)max_slots;
  return 0;
#endif
}

// ============================================================================
// Journal, NUMA and cluster helpers
// ============================================================================

bool ring_journal_write(RingHeader *ring, const BridgeMessage &msg) noexcept {
  if (ring == nullptr) [[unlikely]] {
    return false;
  }
  if (ring->slot_size < sizeof(BridgeMessage)) [[unlikely]] {
    return false;
  }
  BridgeMessage copy = msg;
  copy.journal_seq = static_cast<std::uint32_t>(ring_next_journal_seq(ring));
  return ring_push(ring, &copy, sizeof(copy));
}

bool ring_journal_replay_next(RingHeader *ring, BridgeMessage &out) noexcept {
  if (ring == nullptr) [[unlikely]] {
    return false;
  }
  if (ring->slot_size < sizeof(BridgeMessage)) [[unlikely]] {
    return false;
  }
  std::size_t bytes_read = 0;
  const bool popped = ring_pop(ring, &out, sizeof(out), &bytes_read);
  return popped && (bytes_read >= sizeof(BridgeMessage));
}

std::uint64_t ring_prefetch_hot_slot(const RingHeader *ring) noexcept {
  if (ring == nullptr) [[unlikely]] {
    return 0;
  }
  const std::uint64_t cap = ring->capacity.load(std::memory_order_relaxed);
  if (cap == 0) [[unlikely]] {
    return 0;
  }
  return ring->hotness_counter.load(std::memory_order_relaxed) & (cap - 1);
}

std::size_t ring_choose_numa_node(const RingHeader *ring, std::uint64_t key,
                                  std::size_t numa_nodes) noexcept {
  if (ring == nullptr || numa_nodes == 0) [[unlikely]] {
    return 0;
  }
  const std::uint64_t hotness =
      ring->hotness_counter.load(std::memory_order_relaxed);
  const std::uint64_t mixed = key ^ (hotness * 0x9e3779b97f4a7c15ULL);
  return static_cast<std::size_t>(mixed % numa_nodes);
}

bool ring_try_cross_node_steal(RingHeader *victim,
                               BridgeMessage &out) noexcept {
  if (victim == nullptr) [[unlikely]] {
    return false;
  }
  if (victim->slot_size < sizeof(BridgeMessage)) [[unlikely]] {
    return false;
  }
  std::size_t bytes_read = 0;
  const bool popped = ring_pop(victim, &out, sizeof(out), &bytes_read);
  return popped && (bytes_read >= sizeof(BridgeMessage));
}

bool ring_extract_tensor_meta(const void *shm_base, std::size_t shm_size,
                              const BridgeMessage &msg, void *&ptr,
                              std::size_t &bytes) noexcept {
  ptr = nullptr;
  bytes = 0;

  if (shm_base == nullptr) [[unlikely]] {
    return false;
  }
  if (msg.type != MsgType::SHM_REF) [[unlikely]] {
    return false;
  }

  const std::uint64_t offset = msg.shm_ref.offset;
  const std::uint64_t total_bytes = msg.shm_ref.total_bytes;
  if (total_bytes == 0) [[unlikely]] {
    return false;
  }

  // Bounds check against the region, which the previous version skipped while
  // also using the wrong base pointer.
  if (offset >= shm_size || total_bytes > shm_size - offset) [[unlikely]] {
    return false;
  }

  ptr = const_cast<std::byte *>(static_cast<const std::byte *>(shm_base) +
                                offset);
  bytes = static_cast<std::size_t>(total_bytes);
  return true;
}

void ring_register_runtime(RingHeader *ring, std::uint32_t id) noexcept {
  if (ring == nullptr || id >= MAX_RUNTIMES) [[unlikely]] {
    return;
  }
  ring->cluster_runtime_ids[id].store(1, std::memory_order_release);
}

void ring_unregister_runtime(RingHeader *ring, std::uint32_t id) noexcept {
  if (ring == nullptr || id >= MAX_RUNTIMES) [[unlikely]] {
    return;
  }
  ring->cluster_runtime_ids[id].store(0, std::memory_order_release);
}

// ============================================================================
// MultiProducerRingSet
// ============================================================================

MultiProducerRingSet::MultiProducerRingSet(void *shm_base_ptr,
                                           std::size_t num_producers,
                                           std::uint64_t capacity_per_ring,
                                           std::uint32_t slot_size) {
  if (shm_base_ptr == nullptr || num_producers == 0) {
    return;
  }

  const std::size_t single_ring_bytes =
      ring_total_shm_size(capacity_per_ring, slot_size);
  if (single_ring_bytes == 0) {
    return;
  }

  auto *byte_ptr = static_cast<std::byte *>(shm_base_ptr);
  rings_.reserve(num_producers);
  for (std::size_t i = 0; i < num_producers; ++i) {
    void *current = byte_ptr + (i * single_ring_bytes);
    RingHeader *ring = ring_init_in_shm(current, single_ring_bytes,
                                        capacity_per_ring, slot_size);
    rings_.push_back(ring);
  }
}

bool MultiProducerRingSet::push(std::size_t producer_id, const void *data,
                                std::size_t size) noexcept {
  if (producer_id >= rings_.size() || rings_[producer_id] == nullptr) {
    return false;
  }
  return ring_push(rings_[producer_id], data, size);
}

std::size_t MultiProducerRingSet::poll_all(void *out_buffer,
                                           std::size_t max_size,
                                           std::size_t &out_producer_id) noexcept {
  if (rings_.empty() || out_buffer == nullptr) {
    return 0;
  }

  const std::size_t count = rings_.size();
  for (std::size_t i = 0; i < count; ++i) {
    const std::size_t idx = (last_polled_idx_ + i) % count;
    if (rings_[idx] == nullptr) {
      continue;
    }
    std::size_t bytes_read = 0;
    if (ring_pop(rings_[idx], out_buffer, max_size, &bytes_read)) {
      last_polled_idx_ = (idx + 1) % count; // round-robin fairness
      out_producer_id = idx;
      return bytes_read;
    }
  }
  return 0;
}

} // namespace Sphere
