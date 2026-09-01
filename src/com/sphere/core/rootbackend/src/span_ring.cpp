// span_ring.cpp

#include "span_ring.h"
#include "logger.h"

#include <thread>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__linux__)
#include <sys/syscall.h>
#include <unistd.h>
#else
#include <pthread.h>
#endif

#if defined(_M_X64) || defined(_M_IX86) || defined(__x86_64__) ||              \
    defined(__i386__)
#include <immintrin.h>
#define SPAN_CPU_PAUSE() _mm_pause()
#elif defined(__aarch64__) || defined(__arm__)
#define SPAN_CPU_PAUSE() asm volatile("yield" ::: "memory")
#else
#define SPAN_CPU_PAUSE() ((void)0)
#endif

namespace Sphere::log {

namespace {

/**
 * Native thread id, cached per thread
 */
std::uint32_t cached_thread_id() noexcept {
  thread_local const std::uint32_t tid = []() noexcept -> std::uint32_t {
#if defined(_WIN32)
    return static_cast<std::uint32_t>(::GetCurrentThreadId());
#elif defined(__linux__)
    return static_cast<std::uint32_t>(::syscall(SYS_gettid));
#else
    std::uint64_t value = 0;
    const pthread_t self = ::pthread_self();
    std::memcpy(&value, &self,
                sizeof(value) < sizeof(self) ? sizeof(value) : sizeof(self));
    return static_cast<std::uint32_t>(value ^ (value >> 32));
#endif
  }();
  return tid;
}

} // namespace

SpanRing span_ring_view(void *base, std::size_t bytes,
                        std::uint64_t capacity) noexcept {
  SpanRing ring{};
  if (base == nullptr || capacity == 0 || (capacity & (capacity - 1)) != 0) {
    return ring;
  }
  if (bytes < span_ring_bytes(capacity)) {
    return ring;
  }

  ring.hdr = static_cast<SpanRingHeader *>(base);
  ring.slots = reinterpret_cast<SpanCell *>(static_cast<std::byte *>(base) +
                                            sizeof(SpanRingHeader));
  return ring;
}

void span_ring_init(SpanRing &ring, std::uint64_t capacity) noexcept {
  if (!ring.is_valid() || capacity == 0 || (capacity & (capacity - 1)) != 0) {
    return;
  }

  ring.hdr->capacity = capacity;
  ring.hdr->write_index.store(0, std::memory_order_relaxed);
  ring.hdr->read_index.store(0, std::memory_order_relaxed);
  ring.hdr->dropped_count.store(0, std::memory_order_relaxed);

  for (std::uint64_t i = 0; i < capacity; ++i) {
    ring.slots[i].seq.store(i, std::memory_order_relaxed);
  }
  ring.hdr->init_magic.store(SPAN_RING_MAGIC, std::memory_order_release);
}

bool span_ring_is_initialized(const SpanRing &ring) noexcept {
  return ring.is_valid() &&
         ring.hdr->init_magic.load(std::memory_order_acquire) ==
             SPAN_RING_MAGIC;
}

bool span_ring_push(SpanRing &ring, std::uint8_t level, std::uint16_t module,
                    std::uint32_t job_id, std::uint32_t req_id,
                    std::uint64_t tsc_start, std::uint64_t tsc_end) noexcept {
  SpanRecord record{};
  record.tsc_start = tsc_start;
  record.tsc_end = tsc_end;
  record.thread_id = cached_thread_id();
  record.job_id = job_id;
  record.req_id = req_id;
  record.module_id = module;
  record.level = level;
  record.reserved = 0;
  return span_ring_push_record(ring, record);
}

bool span_ring_push_record(SpanRing &ring, const SpanRecord &record) noexcept {
  if (!ring.is_valid()) {
    return false;
  }
  const std::uint64_t cap = ring.hdr->capacity;
  if (cap == 0) {
    return false;
  }
  const std::uint64_t mask = cap - 1;

  SpanCell *cell = nullptr;
  std::uint64_t pos = ring.hdr->write_index.load(std::memory_order_relaxed);

  for (;;) {
    cell = &ring.slots[pos & mask];
    const std::uint64_t seq = cell->seq.load(std::memory_order_acquire);
    const auto dif =
        static_cast<std::int64_t>(seq) - static_cast<std::int64_t>(pos);

    if (dif == 0) {
      if (ring.hdr->write_index.compare_exchange_weak(
              pos, pos + 1, std::memory_order_relaxed,
              std::memory_order_relaxed)) {
        break;
      }
    } else if (dif < 0) {
      ring.hdr->dropped_count.fetch_add(1, std::memory_order_relaxed);
      metrics().evt_ring_drops.fetch_add(1, std::memory_order_relaxed);
      return false;
    } else {
      pos = ring.hdr->write_index.load(std::memory_order_relaxed);
    }
    SPAN_CPU_PAUSE();
  }

  cell->record = record;
  cell->seq.store(pos + 1, std::memory_order_release);
  return true;
}

bool span_ring_pop(SpanRing &ring, SpanRecord &out_record) noexcept {
  if (!ring.is_valid()) {
    return false;
  }
  const std::uint64_t cap = ring.hdr->capacity;
  if (cap == 0) {
    return false;
  }
  const std::uint64_t mask = cap - 1;

  SpanCell *cell = nullptr;
  std::uint64_t pos = ring.hdr->read_index.load(std::memory_order_relaxed);

  for (;;) {
    cell = &ring.slots[pos & mask];
    const std::uint64_t seq = cell->seq.load(std::memory_order_acquire);
    const auto dif =
        static_cast<std::int64_t>(seq) - static_cast<std::int64_t>(pos + 1);

    if (dif == 0) {
      if (ring.hdr->read_index.compare_exchange_weak(
              pos, pos + 1, std::memory_order_relaxed,
              std::memory_order_relaxed)) {
        break;
      }
    } else if (dif < 0) {
      return false; // empty
    } else {
      pos = ring.hdr->read_index.load(std::memory_order_relaxed);
    }
    SPAN_CPU_PAUSE();
  }

  out_record = cell->record;
  cell->seq.store(pos + cap, std::memory_order_release);
  return true;
}

std::uint64_t span_ring_dropped(const SpanRing &ring) noexcept {
  return ring.is_valid()
             ? ring.hdr->dropped_count.load(std::memory_order_relaxed)
             : 0;
}

} // namespace Sphere::log
