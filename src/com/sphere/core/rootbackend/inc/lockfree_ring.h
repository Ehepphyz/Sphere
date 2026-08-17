// lockfree_ring.h

#pragma once

#include "common_config.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <new>
#include <type_traits>
#include <utility>

#if defined(_M_X64) || defined(_M_IX86) || defined(__x86_64__) ||              \
    defined(__i386__)
#include <immintrin.h>
#define CPU_PAUSE() _mm_pause()
#elif defined(__aarch64__) || defined(__arm__)
#define CPU_PAUSE() asm volatile("yield" ::: "memory")
#else
#define CPU_PAUSE() ((void)0)
#endif

namespace Sphere {

// Supported data types for PyTorch / Deep Learning Tensor interop
enum class ShmDType : std::uint8_t {
  Float32 = 0, // 32-bit Single-precision Floating Point (float / Float_t)
  Float16 = 1, // 16-bit Half-precision Floating Point (std::float16_t / __fp16)
  Int32 = 2,   // 32-bit Signed Integer (std::int32_t / Int_t)
  Int64 = 3,   // 64-bit Signed Integer (std::int64_t / Long64_t)
  Uint8 = 4,   // 8-bit Unsigned Integer (std::uint8_t / UChar_t) - Backward
               // compatibility
  UInt8 = 4, // 8-bit Unsigned Integer (std::uint8_t / UChar_t) - Standard alias
  BFloat16 = 5, // 16-bit Brain Floating Point (PyTorch torch.bfloat16)

  // Extended Data Types for Full ROOT & Native C++ Interoperability
  Float64 = 6, // 64-bit Double-precision Floating Point (double / Double_t)
  UInt32 = 7,  // 32-bit Unsigned Integer (std::uint32_t / UInt_t)
  UInt64 = 8,  // 64-bit Unsigned Integer (std::uint64_t / ULong64_t)
  Int16 = 9,   // 16-bit Signed Integer (std::int16_t / Short_t)
  UInt16 = 10, // 16-bit Unsigned Integer (std::uint16_t / UShort_t)
  Int8 = 11    // 8-bit Signed Integer (std::int8_t / Char_t)
};

// Message type indicator discriminating fast-path inline data from large SHM
// references
enum class MsgType : std::uint8_t {
  EMPTY = 0,
  INLINE_DATA = 1, // Fast-Path: Small payloads, control signals, pings (<= 48B)
  SHM_REF = 2 // Large-Path: Offset reference to ML/CNN Tensors in the SHM heap
};

/// High-performance, zero-copy descriptor for external SHM heap allocations
struct ShmRef {
  std::uint32_t offset;      // Relative byte offset in the SHM data heap
  std::uint32_t total_bytes; // Total byte allocation size
  ShmDType dtype;            // Tensor scalar type
  std::uint8_t ndim;      // Tensor rank/dimensions (e.g., 4 for [N, C, H, W])
  std::uint32_t shape[6]; // Tensor shape array
};

// 64-byte Cache-line aligned hybrid payload (POD / Trivially Copyable for SHM
// IPC)
struct alignas(CACHE_LINE_SIZE) BridgeMessage {
  MsgType type{MsgType::EMPTY};
  std::uint8_t payload_size{0}; // Active byte size when using INLINE_DATA
  std::uint16_t flags{0};       // System flags / routing priorities
  std::uint32_t job_id{0};
  std::uint32_t req_id{0};
  std::uint32_t journal_seq{0};

  union {
    // FAST-PATH: Micro-commands, pings, low-latency control messages
    std::uint8_t inline_bytes[48];

    // LARGE-PATH: Zero-copy descriptors for PyTorch tensors and CNN feature
    // maps
    ShmRef shm_ref;
  };
};

// Compile-time sanity checks (evaluated once BridgeMessage is fully defined)
static_assert(std::is_trivially_copyable_v<BridgeMessage>,
              "BridgeMessage must be Trivially Copyable for IPC!");
static_assert(sizeof(BridgeMessage) == 64,
              "BridgeMessage must be exactly 64 bytes (1 cache line)!");

/**
 * Lock-Free Bounded MPMC Queue engineered for Shared Memory (SHM) placement
 */
template <typename T, std::size_t CapacityPow2> class ShmMpmcRing {
public:
  static constexpr std::size_t CAPACITY = 1ull << CapacityPow2;
  static constexpr std::size_t MASK = CAPACITY - 1;

  static_assert(std::is_trivially_copyable_v<T>,
                "T must be Trivially Copyable for Shared Memory safety!");

  ShmMpmcRing() noexcept = default;
  ~ShmMpmcRing() noexcept = default;

  // Prevent copying and moving across process virtual address boundaries
  ShmMpmcRing(const ShmMpmcRing &) = delete;
  ShmMpmcRing &operator=(const ShmMpmcRing &) = delete;

  /**
   * Initialization of sequence indices, must be invoked by the primary SHM
   * creator process before runtime queue activity
   */
  void init() noexcept {
    for (std::size_t i = 0; i < CAPACITY; ++i) {
      buffer_[i].seq.store(i, std::memory_order_relaxed);
    }
    enqueue_pos_.store(0, std::memory_order_relaxed);
    dequeue_pos_.store(0, std::memory_order_relaxed);
  }

  // Push value by const reference
  bool push(const T &data) noexcept { return emplace(data); }

  // Push value by rvalue reference
  bool push(T &&data) noexcept { return emplace(std::move(data)); }

  /**
   * Emplaces an item into the ring buffer slot without blocking.
   */
  template <typename... Args> bool emplace(Args &&...args) noexcept {
    Cell *cell;
    std::size_t pos = enqueue_pos_.load(std::memory_order_relaxed);

    for (;;) {
      cell = &buffer_[pos & MASK];
      std::size_t seq = cell->seq.load(std::memory_order_acquire);
      auto dif =
          static_cast<std::intptr_t>(seq) - static_cast<std::intptr_t>(pos);

      if (dif == 0) {
        if (enqueue_pos_.compare_exchange_weak(pos, pos + 1,
                                               std::memory_order_relaxed,
                                               std::memory_order_relaxed)) {
          break;
        }
      } else if (dif < 0) {
        return false; // Queue is full
      } else {
        pos = enqueue_pos_.load(std::memory_order_relaxed);
      }
      CPU_PAUSE();
    }

    cell->data = T(std::forward<Args>(args)...);
    cell->seq.store(pos + 1, std::memory_order_release);
    return true;
  }

  /**
   * Pops an item out of the ring buffer without blocking.
   */
  bool pop(T &data) noexcept {
    Cell *cell;
    std::size_t pos = dequeue_pos_.load(std::memory_order_relaxed);

    for (;;) {
      cell = &buffer_[pos & MASK];
      std::size_t seq = cell->seq.load(std::memory_order_acquire);
      auto dif =
          static_cast<std::intptr_t>(seq) - static_cast<std::intptr_t>(pos + 1);

      if (dif == 0) {
        if (dequeue_pos_.compare_exchange_weak(pos, pos + 1,
                                               std::memory_order_relaxed,
                                               std::memory_order_relaxed)) {
          break;
        }
      } else if (dif < 0) {
        return false; // Queue is empty
      } else {
        pos = dequeue_pos_.load(std::memory_order_relaxed);
      }
      CPU_PAUSE();
    }

    data = cell->data;
    cell->seq.store(pos + CAPACITY, std::memory_order_release);
    return true;
  }

  std::size_t pop_batch(T *dst, std::size_t max_items) noexcept {
    std::size_t count = 0;
    while (count < max_items && pop(dst[count])) {
      ++count;
    }
    return count;
  }

  /**
   * Retrieves the current dequeue/head sequence index.
   */
  [[nodiscard]] std::size_t head_sequence() const noexcept {
    return dequeue_pos_.load(std::memory_order_relaxed);
  }

  /**
   * Retrieves the current enqueue/tail sequence index.
   */
  [[nodiscard]] std::size_t tail_sequence() const noexcept {
    return enqueue_pos_.load(std::memory_order_relaxed);
  }

  /**
   * Retrieves the direct read-only pointer to the element slot at index.
   */
  [[nodiscard]] const void *element_at(std::size_t index) const noexcept {
    const std::size_t slot = index & MASK;
    return static_cast<const void *>(&buffer_[slot].data);
  }

  [[nodiscard]] constexpr std::size_t capacity() const noexcept {
    return CAPACITY;
  }

  std::size_t size_approx() const noexcept {
    std::size_t enq = enqueue_pos_.load(std::memory_order_relaxed);
    std::size_t deq = dequeue_pos_.load(std::memory_order_relaxed);
    return (enq >= deq) ? (enq - deq) : 0;
  }

  bool is_above_watermark(std::size_t watermark) const noexcept {
    return size_approx() >= watermark;
  }

private:
  struct alignas(CACHE_LINE_SIZE) Cell {
    std::atomic<std::size_t> seq;
    T data;
  };

  // Cache-line alignment to isolate producer and consumer contention
  alignas(CACHE_LINE_SIZE) std::atomic<std::size_t> enqueue_pos_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::size_t> dequeue_pos_{0};

  // Flat inline array layout for direct SHM mapping
  alignas(CACHE_LINE_SIZE) Cell buffer_[CAPACITY];
};

} // namespace Sphere
