// lockfree_ring.h

// Bounded lock-free MPMC queue (Vyukov) plus the hybrid message type carried
// across the C++/Java shared-memory bridge.

#pragma once

#include "common_config.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <new>
#include <type_traits>
#include <utility>

#if defined(_M_X64) || defined(_M_IX86) || defined(__x86_64__) ||              \
    defined(__i386__)
#include <immintrin.h>
#define SPHERE_CPU_PAUSE() _mm_pause()
#elif defined(__aarch64__) || defined(__arm__)
#define SPHERE_CPU_PAUSE() asm volatile("yield" ::: "memory")
#else
#define SPHERE_CPU_PAUSE() ((void)0)
#endif

namespace Sphere {

/**
 * Scalar types supported by the tensor interop path
 */
enum class ShmDType : std::uint8_t {
  Float32 = 0,  // float / Float_t
  Float16 = 1,  // half precision
  Int32 = 2,    // std::int32_t / Int_t
  Int64 = 3,    // std::int64_t / Long64_t
  UInt8 = 4,    // std::uint8_t / UChar_t
  BFloat16 = 5, // torch.bfloat16
  Float64 = 6,  // double / Double_t
  UInt32 = 7,   // std::uint32_t / UInt_t
  UInt64 = 8,   // std::uint64_t / ULong64_t
  Int16 = 9,    // std::int16_t / Short_t
  UInt16 = 10,  // std::uint16_t / UShort_t
  Int8 = 11     // std::int8_t / Char_t
};

inline constexpr ShmDType Uint8 = ShmDType::UInt8;

/**
 * Transport discriminator: how the payload of a BridgeMessage is carried
 */
enum class MsgType : std::uint8_t {
  EMPTY = 0,
  INLINE_DATA = 1, // small payload carried inside the message itself
  SHM_REF = 2      // offset reference into the shared-memory data heap
};

/// Maximum tensor rank describable by ShmRef.
inline constexpr std::size_t SHM_REF_MAX_DIMS = 6;

/// Zero-copy descriptor for an allocation in the shared-memory data heap.
struct ShmRef {
  std::uint32_t offset{0};      // byte offset from the SHM base
  std::uint32_t total_bytes{0}; // total allocation size
  ShmDType dtype{ShmDType::Float32};
  std::uint8_t ndim{0};
  std::uint16_t reserved{0};
  std::uint32_t shape[SHM_REF_MAX_DIMS]{};
};

/// Number of inline payload bytes available in a BridgeMessage.
inline constexpr std::size_t BRIDGE_INLINE_CAPACITY = 44;

/**
 * 64-byte hybrid message exchanged over the command and event rings
 */
struct alignas(CACHE_LINE_SIZE) BridgeMessage {
  MsgType type{MsgType::EMPTY};  // offset  0: transport discriminator
  std::uint8_t payload_size{0};  // offset  1: valid bytes in inline_bytes
  std::uint16_t cmd{0};          // offset  2: Proto::PacketType opcode
  std::uint16_t flags{0};        // offset  4: Proto::PacketFlags
  std::uint16_t reserved{0};     // offset  6: must be zero
  std::uint32_t job_id{0};       // offset  8
  std::uint32_t req_id{0};       // offset 12
  std::uint32_t journal_seq{0};  // offset 16

  union {                        // offset 20
    std::uint8_t inline_bytes[BRIDGE_INLINE_CAPACITY];
    ShmRef shm_ref;
  };
};

static_assert(std::is_trivially_copyable_v<BridgeMessage>,
              "ABI: BridgeMessage must be trivially copyable for IPC.");
static_assert(std::is_standard_layout_v<BridgeMessage>,
              "ABI: BridgeMessage must be standard layout.");
static_assert(sizeof(BridgeMessage) == 64,
              "ABI: BridgeMessage must be exactly 64 bytes.");
static_assert(alignof(BridgeMessage) == CACHE_LINE_SIZE,
              "ABI: BridgeMessage must be cache-line aligned.");
static_assert(offsetof(BridgeMessage, type) == 0, "ABI: type offset drift.");
static_assert(offsetof(BridgeMessage, payload_size) == 1,
              "ABI: payload_size offset drift.");
static_assert(offsetof(BridgeMessage, cmd) == 2, "ABI: cmd offset drift.");
static_assert(offsetof(BridgeMessage, flags) == 4, "ABI: flags offset drift.");
static_assert(offsetof(BridgeMessage, job_id) == 8, "ABI: job_id offset drift.");
static_assert(offsetof(BridgeMessage, req_id) == 12,
              "ABI: req_id offset drift.");
static_assert(offsetof(BridgeMessage, journal_seq) == 16,
              "ABI: journal_seq offset drift.");
static_assert(offsetof(BridgeMessage, inline_bytes) == 20,
              "ABI: inline payload offset drift.");
static_assert(offsetof(BridgeMessage, shm_ref) == 20,
              "ABI: shm_ref offset drift.");
static_assert(sizeof(ShmRef) <= BRIDGE_INLINE_CAPACITY,
              "ABI: ShmRef no longer fits in the message union.");

static_assert(std::atomic<std::uint64_t>::is_always_lock_free,
              "ABI: 64-bit atomics must be lock-free for cross-process use.");
static_assert(sizeof(std::atomic<std::uint64_t>) == sizeof(std::uint64_t),
              "ABI: atomic<uint64_t> must not add storage.");
static_assert(alignof(std::atomic<std::uint64_t>) == alignof(std::uint64_t),
              "ABI: atomic<uint64_t> must not change alignment.");
static_assert(std::atomic<std::uint32_t>::is_always_lock_free,
              "ABI: 32-bit atomics must be lock-free for cross-process use.");
static_assert(sizeof(std::atomic<std::uint32_t>) == sizeof(std::uint32_t),
              "ABI: atomic<uint32_t> must not add storage.");

inline constexpr std::uint64_t RING_INIT_MAGIC = 0x52494E47494E4954ULL; // RINGINIT

/**
 * Bounded lock-free MPMC queue laid out for direct placement in shared memory
 */
template <typename T, std::size_t CapacityPow2> class ShmMpmcRing {
public:
  static constexpr std::uint64_t CAPACITY = 1ULL << CapacityPow2;
  static constexpr std::uint64_t MASK = CAPACITY - 1;

  static_assert(std::is_trivially_copyable_v<T>,
                "T must be trivially copyable for shared-memory safety.");
  static_assert(CapacityPow2 > 0 && CapacityPow2 < 32,
                "Capacity exponent out of range.");

  ShmMpmcRing() noexcept = default;
  ~ShmMpmcRing() noexcept = default;

  // Copying or moving would carry indices across address spaces.
  ShmMpmcRing(const ShmMpmcRing &) = delete;
  ShmMpmcRing &operator=(const ShmMpmcRing &) = delete;

  /**
   * Initializes the per-cell sequence numbers
   */
  void init() noexcept {
    for (std::uint64_t i = 0; i < CAPACITY; ++i) {
      buffer_[i].seq.store(i, std::memory_order_relaxed);
    }
    enqueue_pos_.store(0, std::memory_order_relaxed);
    dequeue_pos_.store(0, std::memory_order_relaxed);
    init_magic_.store(RING_INIT_MAGIC, std::memory_order_release);
  }

  /**
   * Returns true once init() has run on this region
   */
  [[nodiscard]] bool is_initialized() const noexcept {
    return init_magic_.load(std::memory_order_acquire) == RING_INIT_MAGIC;
  }

  bool push(const T &data) noexcept { return emplace(data); }
  bool push(T &&data) noexcept { return emplace(std::move(data)); }

  /**
   * Emplaces an item without blocking. Returns false when the ring is full.
   */
  template <typename... Args> bool emplace(Args &&...args) noexcept {
    Cell *cell = nullptr;
    std::uint64_t pos = enqueue_pos_.load(std::memory_order_relaxed);

    for (;;) {
      cell = &buffer_[pos & MASK];
      const std::uint64_t seq = cell->seq.load(std::memory_order_acquire);
      const auto dif =
          static_cast<std::int64_t>(seq) - static_cast<std::int64_t>(pos);

      if (dif == 0) {
        if (enqueue_pos_.compare_exchange_weak(pos, pos + 1,
                                               std::memory_order_relaxed,
                                               std::memory_order_relaxed)) {
          break;
        }
      } else if (dif < 0) {
        return false; // full
      } else {
        pos = enqueue_pos_.load(std::memory_order_relaxed);
      }
      SPHERE_CPU_PAUSE();
    }

    cell->data = T(std::forward<Args>(args)...);
    cell->seq.store(pos + 1, std::memory_order_release);
    return true;
  }

  /**
   * Pops an item without blocking. Returns false when the ring is empty.
   */
  bool pop(T &data) noexcept {
    Cell *cell = nullptr;
    std::uint64_t pos = dequeue_pos_.load(std::memory_order_relaxed);

    for (;;) {
      cell = &buffer_[pos & MASK];
      const std::uint64_t seq = cell->seq.load(std::memory_order_acquire);
      const auto dif =
          static_cast<std::int64_t>(seq) - static_cast<std::int64_t>(pos + 1);

      if (dif == 0) {
        if (dequeue_pos_.compare_exchange_weak(pos, pos + 1,
                                               std::memory_order_relaxed,
                                               std::memory_order_relaxed)) {
          break;
        }
      } else if (dif < 0) {
        return false; // empty
      } else {
        pos = dequeue_pos_.load(std::memory_order_relaxed);
      }
      SPHERE_CPU_PAUSE();
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

  /// Consumer-side sequence index (number of items dequeued so far).
  [[nodiscard]] std::uint64_t head_sequence() const noexcept {
    return dequeue_pos_.load(std::memory_order_relaxed);
  }

  /// Producer-side sequence index (number of items enqueued so far).
  [[nodiscard]] std::uint64_t tail_sequence() const noexcept {
    return enqueue_pos_.load(std::memory_order_relaxed);
  }

  /// Read-only pointer to a slot's payload, for prefetching.
  [[nodiscard]] const void *element_at(std::uint64_t index) const noexcept {
    return static_cast<const void *>(&buffer_[index & MASK].data);
  }

  [[nodiscard]] constexpr std::uint64_t capacity() const noexcept {
    return CAPACITY;
  }

  /**
   * Approximate number of queued items
   */
  [[nodiscard]] std::uint64_t size_approx() const noexcept {
    const std::uint64_t enq = enqueue_pos_.load(std::memory_order_relaxed);
    const std::uint64_t deq = dequeue_pos_.load(std::memory_order_relaxed);
    return (enq >= deq) ? (enq - deq) : 0;
  }

  /// Occupancy as a fraction of capacity, clamped to [0, 1].
  [[nodiscard]] float occupancy() const noexcept {
    const float ratio =
        static_cast<float>(size_approx()) / static_cast<float>(CAPACITY);
    return (ratio > 1.0f) ? 1.0f : ratio;
  }

  [[nodiscard]] bool is_above_watermark(std::uint64_t watermark) const noexcept {
    return size_approx() >= watermark;
  }

private:

  struct alignas(CACHE_LINE_SIZE) Cell {
    std::atomic<std::uint64_t> seq{0};
    T data{};
  };

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> enqueue_pos_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> dequeue_pos_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> init_magic_{0};
  alignas(CACHE_LINE_SIZE) Cell buffer_[CAPACITY];
};

// Convenience alias.
template <typename T, std::size_t CapacityPow2 = 10>
using LockFreeRing = ShmMpmcRing<T, CapacityPow2>;

} // namespace Sphere
