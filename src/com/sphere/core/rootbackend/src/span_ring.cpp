// span_ring.cpp
#include "span_ring.h"
#include "logger.h"

#include <thread>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__linux__) || defined(__APPLE__)
#include <pthread.h>
#include <unistd.h>
#endif

#if defined(_M_X64) || defined(_M_IX86) || defined(__x86_64__) ||               \
    defined(__i386__)
#include <immintrin.h>
#define CPU_PAUSE() _mm_pause()
#elif defined(__aarch64__) || defined(__arm__)
#define CPU_PAUSE() asm volatile("yield" ::: "memory")
#else
#define CPU_PAUSE() ((void)0)
#endif

namespace Sphere::log {

namespace {

/**
 * Fast platform-native thread identifier retrieval.
 * Eliminates std::hash overhead on high-frequency hot paths.
 */
inline std::uint32_t get_cached_thread_id() noexcept {
  thread_local const std::uint32_t tid = []() noexcept -> std::uint32_t {
#if defined(_WIN32)
    return static_cast<std::uint32_t>(GetCurrentThreadId());
#elif defined(__linux__)
    return static_cast<std::uint32_t>(
        std::hash<pthread_t>{}(pthread_self()));
#else
    return static_cast<std::uint32_t>(
        std::hash<std::thread::id>{}(std::this_thread::get_id()));
#endif
  }();
  return tid;
}

} // namespace

void span_ring_init(SpanRing &ring, std::uint64_t capacity) noexcept {
  if (!ring.hdr || !ring.slots || capacity == 0) {
    return;
  }

  ring.hdr->capacity = capacity;
  ring.hdr->write_index.store(0, std::memory_order_relaxed);
  ring.hdr->read_index.store(0, std::memory_order_relaxed);
  ring.hdr->dropped_count.store(0, std::memory_order_relaxed);

  for (std::uint64_t i = 0; i < capacity; ++i) {
    ring.slots[i].seq.store(i, std::memory_order_relaxed);
  }
}

bool span_ring_push(SpanRing &ring, std::uint8_t level, std::uint16_t module,
                    std::uint32_t job_id, std::uint32_t req_id,
                    std::uint64_t tsc_start, std::uint64_t tsc_end) noexcept {
  SpanRecord record{};
  record.tsc_start = tsc_start;
  record.tsc_end = tsc_end;
  record.thread_id = get_cached_thread_id();
  record.job_id = job_id;
  record.req_id = req_id;
  record.module_id = module;
  record.level = level;
  record.reserved = 0;

  return span_ring_push_record(ring, record);
}

bool span_ring_push_record(SpanRing &ring, const SpanRecord &record) noexcept {
  if (!ring.hdr || !ring.slots) {
    return false;
  }

  const std::uint64_t cap = ring.hdr->capacity;
  if (cap == 0) {
    return false;
  }

  const std::uint64_t mask = cap - 1;
  SpanCell *cell{nullptr};
  std::uint64_t pos = ring.hdr->write_index.load(std::memory_order_relaxed);

  for (;;) {
    cell = &ring.slots[pos & mask];
    std::uint64_t seq = cell->seq.load(std::memory_order_acquire);
    auto dif =
        static_cast<std::intptr_t>(seq) - static_cast<std::intptr_t>(pos);

    if (dif == 0) {
      if (ring.hdr->write_index.compare_exchange_weak(
              pos, pos + 1, std::memory_order_relaxed,
              std::memory_order_relaxed)) {
        break; // Successfully claimed slot
      }
    } else if (dif < 0) {
      // Ring buffer is full; record dropped metric
      ring.hdr->dropped_count.fetch_add(1, std::memory_order_relaxed);
      metrics().evt_ring_drops.fetch_add(1, std::memory_order_relaxed);
      return false;
    } else {
      pos = ring.hdr->write_index.load(std::memory_order_relaxed);
    }
    CPU_PAUSE();
  }

  // Emplace data and update sequence for consumer visibility
  cell->record = record;
  cell->seq.store(pos + 1, std::memory_order_release);
  return true;
}

bool span_ring_pop(SpanRing &ring, SpanRecord &out_record) noexcept {
  if (!ring.hdr || !ring.slots) {
    return false;
  }

  const std::uint64_t cap = ring.hdr->capacity;
  if (cap == 0) {
    return false;
  }

  const std::uint64_t mask = cap - 1;
  SpanCell *cell{nullptr};
  std::uint64_t pos = ring.hdr->read_index.load(std::memory_order_relaxed);

  for (;;) {
    cell = &ring.slots[pos & mask];
    std::uint64_t seq = cell->seq.load(std::memory_order_acquire);
    auto dif =
        static_cast<std::intptr_t>(seq) - static_cast<std::intptr_t>(pos + 1);

    if (dif == 0) {
      if (ring.hdr->read_index.compare_exchange_weak(
              pos, pos + 1, std::memory_order_relaxed,
              std::memory_order_relaxed)) {
        break; // Successfully claimed slot for reading
      }
    } else if (dif < 0) {
      return false; // Queue is empty
    } else {
      pos = ring.hdr->read_index.load(std::memory_order_relaxed);
    }
    CPU_PAUSE();
  }

  out_record = cell->record;
  cell->seq.store(pos + cap, std::memory_order_release);
  return true;
}

} // namespace Sphere::log