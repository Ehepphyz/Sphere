
#include "shm_layout.h"
#include "common_config.h"
#include "platform.h"
#include "ringbuffer.h"

#include <atomic>
#include <bit>
#include <chrono>
#include <concepts>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <memory_resource>
#include <new>
#include <stdexcept>
#include <stop_token>
#include <thread>
#include <type_traits>
#include <vector>

#if defined(__x86_64__) || defined(_M_X64)
#include <cpuid.h>
#include <emmintrin.h>
#include <immintrin.h>
#include <nmmintrin.h>
#elif defined(__aarch64__)
#include <arm_acle.h>
#endif

namespace Sphere {

using namespace Sphere::Platform;
using Sphere::CACHE_LINE_SIZE;
using Sphere::MAX_RUNTIMES;

std::uint64_t shm_get_min_active_reader_epoch(const ShmLayout &layout) noexcept;

// Delegates RingHeader initialization to the official ring_init_in_shm API.
RingHeader *setup_ring_header(std::byte *address, std::size_t shm_bytes,
                              std::uint64_t capacity, std::uint32_t slot_size) {
  return ring_init_in_shm(static_cast<void *>(address), shm_bytes, capacity,
                          slot_size);
}

namespace detail {

std::atomic<std::uint64_t> g_crc_failures_total{0};
std::atomic<std::uint64_t> g_crc_failures_by_kind[KIND_BUCKETS]{};

[[nodiscard]] constexpr std::uint64_t
byteswap64_compat(std::uint64_t val) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  return __builtin_bswap64(val);
#elif defined(_MSC_VER)
  return _byteswap_uint64(val);
#else
  return ((val & 0x00000000000000FFULL) << 56) |
         ((val & 0x000000000000FF00ULL) << 40) |
         ((val & 0x0000000000FF0000ULL) << 24) |
         ((val & 0x00000000FF000000ULL) << 8) |
         ((val & 0x000000FF00000000ULL) >> 8) |
         ((val & 0x0000FF0000000000ULL) >> 24) |
         ((val & 0x00FF000000000000ULL) >> 40) |
         ((val & 0xFF00000000000000ULL) >> 56);
#endif
}

struct ProducerNumaTable {
  std::atomic<std::int32_t> nodes[PRODUCER_BUCKETS];

  ProducerNumaTable() noexcept {
    for (auto &node : nodes) {
      node.store(-1, std::memory_order_relaxed);
    }
  }
};

static ProducerNumaTable g_producer_numa_table;

} // namespace detail

// Lightweight 4B Clock-LFU frequency estimator for access hotness
struct ClockLFUFilter {
  static constexpr std::size_t COUNTER_COUNT = 16;
  std::atomic<std::uint8_t> counters[COUNTER_COUNT]{};

  // Increments frequency score with saturated 4B arithmetic (max 15)
  void record_access(std::size_t bucket_id) noexcept {
    const std::size_t idx = bucket_id % COUNTER_COUNT;
    std::uint8_t current = counters[idx].load(std::memory_order_relaxed);
    while (current < 15) {
      if (counters[idx].compare_exchange_weak(
              current, static_cast<std::uint8_t>(current + 1),
              std::memory_order_relaxed, std::memory_order_relaxed)) {
        break;
      }
    }
  }

  // Periodically decays frequency counters across all buckets (Clock sweep)
  void apply_clock_decay() noexcept {
    for (auto &counter : counters) {
      std::uint8_t val = counter.load(std::memory_order_relaxed);
      while (val > 0) {
        if (counter.compare_exchange_weak(
                val, static_cast<std::uint8_t>(val >> 1),
                std::memory_order_relaxed, std::memory_order_relaxed)) {
          break;
        }
      }
    }
  }

  [[nodiscard]] std::uint8_t
  estimate_frequency(std::size_t bucket_id) const noexcept {
    return counters[bucket_id % COUNTER_COUNT].load(std::memory_order_relaxed);
  }
};

static ClockLFUFilter g_kind_lfu[KIND_BUCKETS];

[[nodiscard]] std::uint8_t
shm_heap_kind_producer_frequency(std::uint16_t kind,
                                 std::uint16_t producer_id) noexcept {
  if (kind >= KIND_BUCKETS)
    return 0;
  return g_kind_lfu[kind].estimate_frequency(producer_id);
}

void shm_heap_decay_all_lfu() noexcept {
  for (auto &filter : g_kind_lfu) {
    filter.apply_clock_decay();
  }
}

// ============================================================================
// Fallback Lookup Table for CRC-32C (Castagnoli) Computation
// ============================================================================

static constexpr std::array<std::uint32_t, 256> crc32_fallback_table = []() {
  constexpr std::uint32_t kCrc32cPolyReflected = 0x82F63B78u;
  std::array<std::uint32_t, 256> table{};
  for (std::uint32_t i = 0; i < 256; ++i) {
    std::uint32_t ch = i;
    for (std::size_t j = 0; j < 8; ++j) {
      ch = (ch & 1) ? (kCrc32cPolyReflected ^ (ch >> 1)) : (ch >> 1);
    }
    table[i] = ch;
  }
  return table;
}();

// Hardware accelerated CRC32 (CRC-32C) implementation
[[nodiscard]] inline std::uint32_t
calculate_fast_crc32(const std::uint8_t *data, std::size_t len) noexcept {
  std::uint32_t crc = 0xFFFFFFFFu;
  std::size_t i = 0;

#if defined(__x86_64__) || defined(_M_X64)
  // Process 64B blocks using SSE4.2 instructions
  for (; i + sizeof(std::uint64_t) <= len; i += sizeof(std::uint64_t)) {
    std::uint64_t val;
    std::memcpy(&val, data + i, sizeof(val));

    if constexpr (std::endian::native == std::endian::big) {
      val = detail::byteswap64_compat(val);
    }

    crc = static_cast<std::uint32_t>(_mm_crc32_u64(crc, val));
  }

  // Process remaining 32B tail if present
  if (i + sizeof(std::uint32_t) <= len) {
    std::uint32_t val;
    std::memcpy(&val, data + i, sizeof(val));
    crc = _mm_crc32_u32(crc, val);
    i += sizeof(std::uint32_t);
  }
#elif defined(__aarch64__)
  // Process 64B blocks using ARMv8-A CRC32 extensions
  for (; i + sizeof(std::uint64_t) <= len; i += sizeof(std::uint64_t)) {
    std::uint64_t val;
    std::memcpy(&val, data + i, sizeof(val));

    if constexpr (std::endian::native == std::endian::big) {
      val = detail::byteswap64_compat(val);
    }

    crc = __crc32cd(crc, val);
  }

  // Process remaining 32B tail if present
  if (i + sizeof(std::uint32_t) <= len) {
    std::uint32_t val;
    std::memcpy(&val, data + i, sizeof(val));
    crc = __crc32cw(crc, val);
    i += sizeof(std::uint32_t);
  }
#endif

  // Process remaining scalar tail bytes using soft fallback table
  for (; i < len; ++i) {
    crc = (crc >> 8) ^ crc32_fallback_table[(crc ^ data[i]) & 0xFF];
  }

  return crc ^ 0xFFFFFFFFu;
}

// Retrieves active allocation count for a given kind bucket in O(1) time.
std::uint64_t count_by_kind(const ShmLayout &layout,
                            std::uint16_t kind) noexcept {
  if (!layout.data_heap || kind >= KIND_BUCKETS)
    return 0;
  auto *heap_hdr = get_heap_header(layout);
  return heap_hdr->allocations_by_kind[kind].load(std::memory_order_relaxed);
}

// Retrieves active allocation count for a given producer in O(1) time.
std::uint64_t count_by_producer(const ShmLayout &layout,
                                std::uint16_t producer_id) noexcept {
  if (!layout.data_heap || producer_id >= PRODUCER_BUCKETS)
    return 0;
  auto *heap_hdr = get_heap_header(layout);
  return heap_hdr->allocations_by_producer[producer_id].load(
      std::memory_order_relaxed);
}

void update_latency_stats(EngineStats *stats,
                          std::uint64_t latency_ns) noexcept {
  if (!stats) {
    return;
  }

  // Record the most recent job latency directly
  stats->last_job_latency_ns.store(latency_ns, std::memory_order_relaxed);

  // Max recorded latency using a CAS loop
  std::uint64_t current_max =
      stats->max_job_latency_ns.load(std::memory_order_relaxed);
  while (latency_ns > current_max &&
         !stats->max_job_latency_ns.compare_exchange_weak(
             current_max, latency_ns, std::memory_order_relaxed,
             std::memory_order_relaxed)) {
  }

  std::uint64_t prev_avg =
      stats->avg_job_latency_ns.load(std::memory_order_relaxed);
  std::uint64_t new_avg = 0;
  do {
    new_avg = (prev_avg == 0) ? latency_ns
                              : prev_avg + ((latency_ns - prev_avg) >> 3);
  } while (!stats->avg_job_latency_ns.compare_exchange_weak(
      prev_avg, new_avg, std::memory_order_relaxed, std::memory_order_relaxed));
}

[[nodiscard]] constexpr std::size_t heap_metadata_size() noexcept {
  return align_up(heap_root_offset() + sizeof(ShmHeapRoot), CACHE_LINE_SIZE);
}

// Low-latency CPU pause hint for spin wait loops
inline void cpu_pause() noexcept {
#if defined(__x86_64__) || defined(_M_X64)
  _mm_pause();
#elif defined(__aarch64__)
  asm volatile("yield" ::: "memory");
#else
  // Generic fallback for unsupported instruction set architectures
#endif
}

// Retrieves a mutable pointer to the chunk header given its payload offset
[[nodiscard]] inline ShmChunkHeader *
get_chunk_mut(ShmLayout &layout, std::uint64_t payload_offset) noexcept {
  if (!layout.base || payload_offset < sizeof(ShmChunkHeader)) {
    return nullptr;
  }
  const std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  return reinterpret_cast<ShmChunkHeader *>(layout.base + chunk_offset);
}

// ============================================================================
// Shared Memory Initialization and Layout Partitioning
// ============================================================================

namespace {
// Helper to safely transition state and clean up OS mapping resources on error
void shm_handle_init_failure(ShmHeader *header, EngineError error_code,
                             const char *msg) {
  if (header) {
    header->last_error_code.store(static_cast<std::uint32_t>(error_code),
                                  std::memory_order_release);
    header->state.store(static_cast<std::uint32_t>(EngineState::CORRUPTED),
                        std::memory_order_release);
  }
  throw std::runtime_error(msg);
}
} // namespace

ShmLayout init_shm(bool create) {
  // Use Platform::ShmRegion to manage OS shared memory mapping
  Platform::ShmRegion shm_region =
      create ? shm_create(SHM_NAME, SHM_SIZE) : shm_open(SHM_NAME, SHM_SIZE);

  void *base_ptr = shm_region.data();
  if (!base_ptr) {
    throw std::runtime_error("Failed to map shared memory base address.");
  }

  ShmLayout layout{};
  layout.base = static_cast<std::byte *>(base_ptr);
  auto *header = reinterpret_cast<ShmHeader *>(layout.base);

  if (create) {
    // Zero out the entire shared memory region to prevent garbage data
    std::memset(layout.base, 0, SHM_SIZE);

    // Construct the main ShmHeader in-place
    ::new (static_cast<void *>(header)) ShmHeader();
    header->total_size = SHM_SIZE;
    header->magic.store(SHM_MAGIC, std::memory_order_relaxed);
    header->version.store(SHM_VERSION, std::memory_order_relaxed);
    header->abi_version.store(1, std::memory_order_relaxed);
    header->proto_version.store(1, std::memory_order_relaxed);
    header->state.store(static_cast<std::uint32_t>(EngineState::UNINITIALIZED),
                        std::memory_order_relaxed);

    std::size_t current_offset = align_up(sizeof(ShmHeader), CACHE_LINE_SIZE);

    // ---------------------------------------------------------------------
    // 1. Command Ring Buffer Setup
    // ---------------------------------------------------------------------
    header->off_cmd_ring = current_offset;
    header->cmd_ring_capacity = CMD_RING_CAPACITY_POW2;
    header->cmd_ring_slot_size = sizeof(BridgeMessage);

    const std::size_t cmd_shm_bytes = ring_total_shm_size(
        header->cmd_ring_capacity, header->cmd_ring_slot_size);

    if (cmd_shm_bytes == 0) {
      shm_handle_init_failure(
          header, EngineError::INVALID_CONFIG,
          "Invalid command ring buffer dimensions or allocation overflow.");
    }

    // Initialize placement new directly into layout.cmd_ring typed memory
    layout.cmd_ring =
        ::new (static_cast<void *>(layout.base + current_offset)) CmdRing();
    current_offset = align_up(current_offset + cmd_shm_bytes, CACHE_LINE_SIZE);

    // ---------------------------------------------------------------------
    // 2. Event Ring Buffer Setup
    // ---------------------------------------------------------------------
    header->off_evt_ring = current_offset;
    header->evt_ring_capacity = EVT_RING_CAPACITY_POW2;
    header->evt_ring_slot_size = sizeof(BridgeMessage);

    const std::size_t evt_shm_bytes = ring_total_shm_size(
        header->evt_ring_capacity, header->evt_ring_slot_size);

    if (evt_shm_bytes == 0) {
      shm_handle_init_failure(
          header, EngineError::INVALID_CONFIG,
          "Invalid event ring buffer dimensions or allocation overflow.");
    }

    // Initialize placement new directly into layout.evt_ring typed memory
    layout.evt_ring =
        ::new (static_cast<void *>(layout.base + current_offset)) EvtRing();
    current_offset = align_up(current_offset + evt_shm_bytes, CACHE_LINE_SIZE);

    // ---------------------------------------------------------------------
    // 3. Schema Heap and Transaction Log Partitioning
    // ---------------------------------------------------------------------
    constexpr std::size_t schema_size = 1 * 1024 * 1024; // 1 MB
    constexpr std::size_t tx_log_size = 1 * 1024 * 1024; // 1 MB

    static_assert(sizeof(EngineStats) +
                          JOURNAL_CAPACITY * sizeof(BridgeMessage) <=
                      tx_log_size,
                  "JOURNAL_CAPACITY * sizeof(BridgeMessage) does not fit "
                  "inside the reserved tx_log partition");

    header->off_schema_heap = current_offset;
    header->size_schema_heap = schema_size;
    layout.schema_heap = layout.base + current_offset;
    current_offset = align_up(current_offset + schema_size, CACHE_LINE_SIZE);

    header->off_tx_log = current_offset;
    header->size_tx_log = tx_log_size;
    layout.tx_log = layout.base + current_offset;
    current_offset = align_up(current_offset + tx_log_size, CACHE_LINE_SIZE);

    // ---------------------------------------------------------------------
    // 4. Dedicated Lock-Free Data Heap Setup
    // ---------------------------------------------------------------------
    header->off_data_heap = current_offset;
    header->size_data_heap = SHM_SIZE - current_offset;

    layout.data_heap = layout.base + header->off_data_heap;

    // Construct heap metadata structures using placement new
    auto *heap_hdr =
        ::new (static_cast<void *>(layout.data_heap)) ShmHeapHeader();

    // Ensure ShmHeapRoot is aligned to its natural boundary or cache line
    auto *heap_root =
        ::new (static_cast<void *>(layout.data_heap + heap_root_offset()))
            ShmHeapRoot();

    const std::size_t heap_meta_size = heap_metadata_size();

    heap_hdr->total_capacity = header->size_data_heap - heap_meta_size;
    heap_hdr->allocated_bytes.store(0, std::memory_order_relaxed);
    heap_hdr->epoch.store(1, std::memory_order_relaxed);
    heap_hdr->active_allocations.store(0, std::memory_order_relaxed);
    heap_hdr->epoch_barrier.store(1, std::memory_order_relaxed);
    heap_hdr->snapshot_min_epoch.store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_max_epoch.store(0, std::memory_order_relaxed);

    for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
      heap_hdr->allocations_by_kind[i].store(0, std::memory_order_relaxed);
      heap_hdr->quota_by_kind[i].store(DEFAULT_KIND_QUOTA,
                                       std::memory_order_relaxed);
      heap_hdr->snapshot_min_epoch_by_kind[i].store(0,
                                                    std::memory_order_relaxed);
      heap_hdr->snapshot_max_epoch_by_kind[i].store(0,
                                                    std::memory_order_relaxed);
    }
    for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
      heap_hdr->allocations_by_producer[i].store(0, std::memory_order_relaxed);
      heap_hdr->quota_by_producer[i].store(DEFAULT_PRODUCER_QUOTA,
                                           std::memory_order_relaxed);
      heap_hdr->snapshot_min_epoch_by_producer[i].store(
          0, std::memory_order_relaxed);
      heap_hdr->snapshot_max_epoch_by_producer[i].store(
          0, std::memory_order_relaxed);
    }

    heap_root->n_chunks.store(0, std::memory_order_relaxed);
    heap_root->off_first_chunk.store(0, std::memory_order_relaxed);
    heap_root->off_last_chunk.store(0, std::memory_order_relaxed);
    heap_root->off_prefetch_head.store(0, std::memory_order_relaxed);
    heap_root->off_last_lvl2.store(0, std::memory_order_relaxed);
    heap_root->off_last_lvl4.store(0, std::memory_order_relaxed);
    heap_root->off_last_lvl8.store(0, std::memory_order_relaxed);

    for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
      heap_root->off_first_by_kind[i].store(0, std::memory_order_relaxed);
      heap_root->off_last_by_kind[i].store(0, std::memory_order_relaxed);
      heap_root->off_prefetch_head_by_kind[i].store(0,
                                                    std::memory_order_relaxed);
      heap_root->hotness_by_kind[i].store(0, std::memory_order_relaxed);
    }
    for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
      heap_root->off_first_by_producer[i].store(0, std::memory_order_relaxed);
      heap_root->off_last_by_producer[i].store(0, std::memory_order_relaxed);
      heap_root->off_prefetch_head_by_producer[i].store(
          0, std::memory_order_relaxed);
      heap_root->hotness_by_producer[i].store(0, std::memory_order_relaxed);
    }

    // Initialize Engine Statistics in transaction log region
    auto *stats = ::new (static_cast<void *>(layout.tx_log)) EngineStats();
    stats->jobs_inflight.store(0, std::memory_order_relaxed);
    stats->last_job_latency_ns.store(0, std::memory_order_relaxed);
    stats->last_update_epoch.store(0, std::memory_order_relaxed);
    stats->avg_job_latency_ns.store(0, std::memory_order_relaxed);
    stats->max_job_latency_ns.store(0, std::memory_order_relaxed);
    stats->heap_usage_bytes.store(0, std::memory_order_relaxed);
    stats->heap_fragmentation_score.store(0, std::memory_order_relaxed);

    // Mark shared memory region as READY using release semantics
    header->state.store(static_cast<std::uint32_t>(EngineState::READY),
                        std::memory_order_release);
  } else {
    // Spin-wait until creator finishes initialization, backed by wall-clock
    // timeout
    const auto start_time = std::chrono::steady_clock::now();
    constexpr auto max_init_timeout = std::chrono::seconds(5);

    std::uint32_t current_state = header->state.load(std::memory_order_acquire);

    while (current_state ==
           static_cast<std::uint32_t>(EngineState::UNINITIALIZED)) {
      if (std::chrono::steady_clock::now() - start_time > max_init_timeout) {
        shm_handle_init_failure(
            header, EngineError::INIT_TIMEOUT,
            "Shared memory initialization timed out waiting for creator.");
      }

      cpu_pause();
      std::this_thread::yield();
      current_state = header->state.load(std::memory_order_acquire);
    }

    // Check if the creator failed or encountered a corruption state during boot
    if (current_state == static_cast<std::uint32_t>(EngineState::CORRUPTED)) {
      throw std::runtime_error(
          "Cannot open shared memory: layout state marked as CORRUPTED.");
    }

    // Verify Magic and Version Compatibility after layout becomes ready
    if (header->magic.load(std::memory_order_acquire) != SHM_MAGIC ||
        header->version.load(std::memory_order_acquire) != SHM_VERSION) {
      shm_handle_init_failure(header, EngineError::ABI_MISMATCH,
                              "Shared memory version or magic mismatch.");
    }

    // Reconstruct non-owner layout pointers from header offsets
    layout.cmd_ring =
        reinterpret_cast<CmdRing *>(layout.base + header->off_cmd_ring);
    layout.evt_ring =
        reinterpret_cast<EvtRing *>(layout.base + header->off_evt_ring);
    layout.schema_heap = layout.base + header->off_schema_heap;
    layout.tx_log = layout.base + header->off_tx_log;
    layout.data_heap = layout.base + header->off_data_heap;
  }

  layout.header = header;
  return layout;
}

std::uint64_t shm_heap_alloc(ShmLayout &layout, std::size_t size,
                             std::uint16_t kind, std::uint16_t producer_id,
                             std::uint16_t encoding) noexcept {
  if (!layout.data_heap)
    return 0;

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);

  std::size_t total_alloc_size =
      align_up(sizeof(ShmChunkHeader) + size, CACHE_LINE_SIZE);

  if (kind < KIND_BUCKETS) {
    std::uint64_t quota =
        heap_hdr->quota_by_kind[kind].load(std::memory_order_relaxed);
    if (quota > 0) {
      std::uint64_t current =
          heap_hdr->allocations_by_kind[kind].load(std::memory_order_relaxed);
      while (true) {
        if (current >= quota) {
          layout.header->last_error_code.store(
              static_cast<std::uint32_t>(EngineError::OOM_HEAP),
              std::memory_order_relaxed);
          return 0;
        }
        if (heap_hdr->allocations_by_kind[kind].compare_exchange_weak(
                current, current + 1, std::memory_order_relaxed,
                std::memory_order_relaxed)) {
          break;
        }
      }
    } else {
      heap_hdr->allocations_by_kind[kind].fetch_add(1,
                                                    std::memory_order_relaxed);
    }
  }

  if (producer_id < PRODUCER_BUCKETS) {
    std::uint64_t quota = heap_hdr->quota_by_producer[producer_id].load(
        std::memory_order_relaxed);
    if (quota > 0) {
      std::uint64_t current =
          heap_hdr->allocations_by_producer[producer_id].load(
              std::memory_order_relaxed);
      while (true) {
        if (current >= quota) {
          if (kind < KIND_BUCKETS) {
            heap_hdr->allocations_by_kind[kind].fetch_sub(
                1, std::memory_order_relaxed);
          }
          layout.header->last_error_code.store(
              static_cast<std::uint32_t>(EngineError::OOM_HEAP),
              std::memory_order_relaxed);
          return 0;
        }
        if (heap_hdr->allocations_by_producer[producer_id]
                .compare_exchange_weak(current, current + 1,
                                       std::memory_order_relaxed,
                                       std::memory_order_relaxed)) {
          break;
        }
      }
    } else {
      heap_hdr->allocations_by_producer[producer_id].fetch_add(
          1, std::memory_order_relaxed);
    }
  }

  // Atomic check for heap capacity
  std::uint64_t offset =
      heap_hdr->allocated_bytes.load(std::memory_order_relaxed);
  while (true) {
    if (offset + total_alloc_size > heap_hdr->total_capacity) {
      if (kind < KIND_BUCKETS) {
        heap_hdr->allocations_by_kind[kind].fetch_sub(
            1, std::memory_order_relaxed);
      }
      if (producer_id < PRODUCER_BUCKETS) {
        heap_hdr->allocations_by_producer[producer_id].fetch_sub(
            1, std::memory_order_relaxed);
      }

      layout.header->last_error_code.store(
          static_cast<std::uint32_t>(EngineError::OOM_HEAP),
          std::memory_order_relaxed);
      return 0;
    }
    if (heap_hdr->allocated_bytes.compare_exchange_weak(
            offset, offset + total_alloc_size, std::memory_order_relaxed)) {
      break;
    }
  }

  std::uint64_t chunk_offset =
      layout.header->off_data_heap + heap_metadata_size() + offset;
  auto *chunk =
      ::new (static_cast<void *>(layout.base + chunk_offset)) ShmChunkHeader();
  chunk->magic = CHUNK_MAGIC;
  chunk->payload_size = static_cast<std::uint32_t>(size);
  chunk->epoch = heap_hdr->epoch.load(std::memory_order_relaxed);
  chunk->kind = kind;
  chunk->producer_id = producer_id;
  chunk->encoding = encoding;
  chunk->flags.store(CHUNK_INCOMPLETE, std::memory_order_relaxed);
  chunk->checksum = 0;
  chunk->next_offset.store(0, std::memory_order_relaxed);
  chunk->next_kind_offset.store(0, std::memory_order_relaxed);
  chunk->next_producer_offset.store(0, std::memory_order_relaxed);
  chunk->next_offset_lvl2.store(0, std::memory_order_relaxed);
  chunk->next_offset_lvl4.store(0, std::memory_order_relaxed);
  chunk->next_offset_lvl8.store(0, std::memory_order_relaxed);

  std::atomic_thread_fence(std::memory_order_release);

  std::uint64_t prev_last = heap_root->off_last_chunk.exchange(
      chunk_offset, std::memory_order_acq_rel);
  if (prev_last == 0) {
    heap_root->off_first_chunk.store(chunk_offset, std::memory_order_release);
  } else {
    auto *last_chunk =
        reinterpret_cast<ShmChunkHeader *>(layout.base + prev_last);
    last_chunk->next_offset.store(chunk_offset, std::memory_order_release);
  }

  if (kind < KIND_BUCKETS) {
    std::uint64_t prev_kind_last = heap_root->off_last_by_kind[kind].exchange(
        chunk_offset, std::memory_order_acq_rel);
    if (prev_kind_last == 0) {
      heap_root->off_first_by_kind[kind].store(chunk_offset,
                                               std::memory_order_release);
    } else {
      auto *last_kind_chunk =
          reinterpret_cast<ShmChunkHeader *>(layout.base + prev_kind_last);
      last_kind_chunk->next_kind_offset.store(chunk_offset,
                                              std::memory_order_release);
    }
    heap_root->off_prefetch_head_by_kind[kind].store(chunk_offset,
                                                     std::memory_order_release);
  }

  if (producer_id < PRODUCER_BUCKETS) {
    std::uint64_t prev_prod_last =
        heap_root->off_last_by_producer[producer_id].exchange(
            chunk_offset, std::memory_order_acq_rel);
    if (prev_prod_last == 0) {
      heap_root->off_first_by_producer[producer_id].store(
          chunk_offset, std::memory_order_release);
    } else {
      auto *last_prod_chunk =
          reinterpret_cast<ShmChunkHeader *>(layout.base + prev_prod_last);
      last_prod_chunk->next_producer_offset.store(chunk_offset,
                                                  std::memory_order_release);
    }
    heap_root->off_prefetch_head_by_producer[producer_id].store(
        chunk_offset, std::memory_order_release);
  }

  std::uint32_t seq =
      heap_root->n_chunks.fetch_add(1, std::memory_order_relaxed) + 1;
  if ((seq & 1u) == 0) {
    std::uint64_t prev_lvl2 = heap_root->off_last_lvl2.exchange(
        chunk_offset, std::memory_order_acq_rel);
    if (prev_lvl2 != 0) {
      auto *last2 = reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl2);
      last2->next_offset_lvl2.store(chunk_offset, std::memory_order_release);
    }
  }
  if ((seq & 3u) == 0) {
    std::uint64_t prev_lvl4 = heap_root->off_last_lvl4.exchange(
        chunk_offset, std::memory_order_acq_rel);
    if (prev_lvl4 != 0) {
      auto *last4 = reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl4);
      last4->next_offset_lvl4.store(chunk_offset, std::memory_order_release);
    }
  }
  if ((seq & 7u) == 0) {
    std::uint64_t prev_lvl8 = heap_root->off_last_lvl8.exchange(
        chunk_offset, std::memory_order_acq_rel);
    if (prev_lvl8 != 0) {
      auto *last8 = reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl8);
      last8->next_offset_lvl8.store(chunk_offset, std::memory_order_release);
    }
  }

  if (kind == 1) {
    heap_root->off_prefetch_head.store(chunk_offset, std::memory_order_release);
  }

  heap_hdr->active_allocations.fetch_add(1, std::memory_order_relaxed);

  auto *stats = shm_engine_stats(layout);
  if (stats) {
    std::uint64_t used =
        heap_hdr->allocated_bytes.load(std::memory_order_relaxed);
    stats->heap_usage_bytes.store(used, std::memory_order_relaxed);

    std::uint64_t n = heap_root->n_chunks.load(std::memory_order_relaxed);
    std::uint64_t frag = 0;

    if (heap_hdr->total_capacity > 0 && n > 0) {
      std::uint64_t avg_chunk_capacity = heap_hdr->total_capacity / n;
      if (avg_chunk_capacity > 0) {
        frag = used / avg_chunk_capacity;
      }
    }
    stats->heap_fragmentation_score.store(frag, std::memory_order_relaxed);
  }

  return chunk_offset + sizeof(ShmChunkHeader);
}

void shm_chunk_commit(ShmLayout &layout,
                      std::uint64_t payload_offset) noexcept {
  if (payload_offset < sizeof(ShmChunkHeader))
    return;
  std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  auto *chunk = reinterpret_cast<ShmChunkHeader *>(layout.base + chunk_offset);

  chunk->checksum = calculate_fast_crc32(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);

  chunk->flags.store(CHUNK_COMMITTED, std::memory_order_release);
}

void shm_chunk_mark_dirty(ShmLayout &layout,
                          std::uint64_t payload_offset) noexcept {
  auto *chunk = get_chunk_mut(layout, payload_offset);
  if (!chunk)
    return;
  chunk->flags.fetch_or(CHUNK_DIRTY, std::memory_order_release);
}

void shm_chunk_mark_tombstone(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept {
  auto *chunk = get_chunk_mut(layout, payload_offset);
  if (!chunk)
    return;
  chunk->flags.fetch_or(CHUNK_TOMBSTONE, std::memory_order_release);
}

void shm_chunk_mark_committed(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept {
  shm_chunk_commit(layout, payload_offset);
}

std::uint64_t shm_heap_alloc_data(ShmLayout &layout,
                                  std::size_t size) noexcept {
  return shm_heap_alloc(layout, size, 1, 0, 0);
}

std::uint64_t shm_heap_alloc_schema(ShmLayout &layout,
                                    std::size_t size) noexcept {
  return shm_heap_alloc(layout, size, 2, 0, 0);
}

std::uint64_t shm_heap_alloc_tx(ShmLayout &layout, std::size_t size) noexcept {
  return shm_heap_alloc(layout, size, 3, 0, 0);
}

void shm_heap_reset(ShmLayout &layout) noexcept {
  if (!layout.data_heap)
    return;

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);

  std::uint64_t new_epoch =
      heap_hdr->epoch.fetch_add(1, std::memory_order_acq_rel) + 1;
  heap_hdr->epoch_barrier.store(new_epoch, std::memory_order_release);
  heap_hdr->allocated_bytes.store(0, std::memory_order_release);
  heap_hdr->active_allocations.store(0, std::memory_order_release);
  heap_hdr->snapshot_min_epoch.store(0, std::memory_order_relaxed);
  heap_hdr->snapshot_max_epoch.store(0, std::memory_order_relaxed);

  for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
    heap_hdr->allocations_by_kind[i].store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_min_epoch_by_kind[i].store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_max_epoch_by_kind[i].store(0, std::memory_order_relaxed);
  }
  for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
    heap_hdr->allocations_by_producer[i].store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_min_epoch_by_producer[i].store(
        0, std::memory_order_relaxed);
    heap_hdr->snapshot_max_epoch_by_producer[i].store(
        0, std::memory_order_relaxed);
  }

  heap_root->n_chunks.store(0, std::memory_order_release);
  heap_root->off_first_chunk.store(0, std::memory_order_release);
  heap_root->off_last_chunk.store(0, std::memory_order_release);
  heap_root->off_prefetch_head.store(0, std::memory_order_release);
  heap_root->off_last_lvl2.store(0, std::memory_order_release);
  heap_root->off_last_lvl4.store(0, std::memory_order_release);
  heap_root->off_last_lvl8.store(0, std::memory_order_release);

  for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
    heap_root->off_first_by_kind[i].store(0, std::memory_order_release);
    heap_root->off_last_by_kind[i].store(0, std::memory_order_release);
    heap_root->off_prefetch_head_by_kind[i].store(0, std::memory_order_release);
    heap_root->hotness_by_kind[i].store(0, std::memory_order_relaxed);
  }
  for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
    heap_root->off_first_by_producer[i].store(0, std::memory_order_release);
    heap_root->off_last_by_producer[i].store(0, std::memory_order_release);
    heap_root->off_prefetch_head_by_producer[i].store(
        0, std::memory_order_release);
    heap_root->hotness_by_producer[i].store(0, std::memory_order_relaxed);
  }

  auto *stats = shm_engine_stats(layout);
  if (stats) {
    stats->heap_usage_bytes.store(0, std::memory_order_relaxed);
    stats->heap_fragmentation_score.store(0, std::memory_order_relaxed);
  }
}

void shm_heap_soft_barrier(ShmLayout &layout) noexcept {
  if (!layout.data_heap)
    return;
  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->epoch_barrier.fetch_add(1, std::memory_order_relaxed);
}

std::uint64_t shm_heap_current_epoch(const ShmLayout &layout) noexcept {
  if (!layout.data_heap)
    return 0;
  auto *heap_hdr = reinterpret_cast<const ShmHeapHeader *>(layout.data_heap);
  return heap_hdr->epoch.load(std::memory_order_acquire);
}

bool shm_chunk_is_valid(const ShmLayout &layout,
                        std::uint64_t payload_offset) noexcept {
  if (!layout.base || !layout.data_heap)
    return false;

  std::size_t min_offset = layout.header->off_data_heap + heap_metadata_size() +
                           sizeof(ShmChunkHeader);
  if (payload_offset < min_offset || payload_offset >= SHM_SIZE) {
    return false;
  }

  std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  auto *chunk =
      reinterpret_cast<const ShmChunkHeader *>(layout.base + chunk_offset);

  if (chunk->magic != CHUNK_MAGIC)
    return false;

  std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);
  if (!(flags & CHUNK_COMMITTED) || (flags & CHUNK_TOMBSTONE))
    return false;

  auto *heap_hdr = reinterpret_cast<const ShmHeapHeader *>(layout.data_heap);
  std::uint64_t current_epoch = heap_hdr->epoch.load(std::memory_order_acquire);
  std::uint64_t barrier =
      heap_hdr->epoch_barrier.load(std::memory_order_acquire);

  if (chunk->epoch != current_epoch)
    return false;
  if (chunk->epoch < barrier)
    return false;

  std::uint64_t global_min =
      heap_hdr->snapshot_min_epoch.load(std::memory_order_relaxed);
  std::uint64_t global_max =
      heap_hdr->snapshot_max_epoch.load(std::memory_order_relaxed);
  if (global_max > 0) {
    if (chunk->epoch < global_min || chunk->epoch > global_max)
      return false;
  }

  if (chunk->kind < KIND_BUCKETS) {
    std::uint64_t kmin = heap_hdr->snapshot_min_epoch_by_kind[chunk->kind].load(
        std::memory_order_relaxed);
    std::uint64_t kmax = heap_hdr->snapshot_max_epoch_by_kind[chunk->kind].load(
        std::memory_order_relaxed);
    if (kmax > 0) {
      if (chunk->epoch < kmin || chunk->epoch > kmax)
        return false;
    }
  }

  if (chunk->producer_id < PRODUCER_BUCKETS) {
    std::uint64_t pmin =
        heap_hdr->snapshot_min_epoch_by_producer[chunk->producer_id].load(
            std::memory_order_relaxed);
    std::uint64_t pmax =
        heap_hdr->snapshot_max_epoch_by_producer[chunk->producer_id].load(
            std::memory_order_relaxed);
    if (pmax > 0) {
      if (chunk->epoch < pmin || chunk->epoch > pmax)
        return false;
    }
  }

  auto *heap_root_for_hotness =
      const_cast<ShmHeapRoot *>(get_heap_root(layout));
  if (chunk->kind < KIND_BUCKETS) {
    heap_root_for_hotness->hotness_by_kind[chunk->kind].fetch_add(
        1, std::memory_order_relaxed);
    g_kind_lfu[chunk->kind].record_access(chunk->producer_id);
  }
  if (chunk->producer_id < PRODUCER_BUCKETS) {
    heap_root_for_hotness->hotness_by_producer[chunk->producer_id].fetch_add(
        1, std::memory_order_relaxed);
  }

  return true;
}

bool shm_chunk_is_visible_for_reader(const ShmLayout &layout,
                                     std::uint64_t payload_offset,
                                     std::uint64_t reader_epoch) noexcept {
  if (!layout.base || !layout.data_heap)
    return false;
  std::size_t min_offset = layout.header->off_data_heap + heap_metadata_size() +
                           sizeof(ShmChunkHeader);
  if (payload_offset < min_offset || payload_offset >= SHM_SIZE) {
    return false;
  }
  std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  auto *chunk =
      reinterpret_cast<const ShmChunkHeader *>(layout.base + chunk_offset);
  if (chunk->magic != CHUNK_MAGIC)
    return false;
  std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);
  if (!(flags & CHUNK_COMMITTED) || (flags & CHUNK_TOMBSTONE))
    return false;
  auto *heap_hdr = reinterpret_cast<const ShmHeapHeader *>(layout.data_heap);
  std::uint64_t barrier =
      heap_hdr->epoch_barrier.load(std::memory_order_acquire);
  if (chunk->epoch < barrier)
    return false;
  if (chunk->epoch > reader_epoch)
    return false;

  std::uint64_t global_min =
      heap_hdr->snapshot_min_epoch.load(std::memory_order_relaxed);
  std::uint64_t global_max =
      heap_hdr->snapshot_max_epoch.load(std::memory_order_relaxed);
  if (global_max > 0) {
    if (chunk->epoch < global_min || chunk->epoch > global_max)
      return false;
  }

  if (chunk->kind < KIND_BUCKETS) {
    std::uint64_t kmin = heap_hdr->snapshot_min_epoch_by_kind[chunk->kind].load(
        std::memory_order_relaxed);
    std::uint64_t kmax = heap_hdr->snapshot_max_epoch_by_kind[chunk->kind].load(
        std::memory_order_relaxed);
    if (kmax > 0) {
      if (chunk->epoch < kmin || chunk->epoch > kmax)
        return false;
    }
  }

  if (chunk->producer_id < PRODUCER_BUCKETS) {
    std::uint64_t pmin =
        heap_hdr->snapshot_min_epoch_by_producer[chunk->producer_id].load(
            std::memory_order_relaxed);
    std::uint64_t pmax =
        heap_hdr->snapshot_max_epoch_by_producer[chunk->producer_id].load(
            std::memory_order_relaxed);
    if (pmax > 0) {
      if (chunk->epoch < pmin || chunk->epoch > pmax)
        return false;
    }
  }

  return true;
}

const ShmChunkHeader *
shm_chunk_get_header(const ShmLayout &layout,
                     std::uint64_t payload_offset) noexcept {
  if (!shm_chunk_is_valid(layout, payload_offset))
    return nullptr;
  return reinterpret_cast<const ShmChunkHeader *>(layout.base + payload_offset -
                                                  sizeof(ShmChunkHeader));
}

std::uint64_t
shm_chunk_next_payload(const ShmLayout &layout,
                       std::uint64_t current_payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, current_payload_offset);
  if (!chunk)
    return 0;

  std::uint64_t next_off = chunk->next_offset.load(std::memory_order_acquire);
  if (next_off == 0)
    return 0;

  std::uint64_t next_payload = next_off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, next_payload) ? next_payload : 0;
}

std::uint64_t
shm_chunk_next_payload_lvl2(const ShmLayout &layout,
                            std::uint64_t current_payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, current_payload_offset);
  if (!chunk)
    return 0;
  std::uint64_t next_off =
      chunk->next_offset_lvl2.load(std::memory_order_acquire);
  if (next_off == 0)
    return 0;
  std::uint64_t next_payload = next_off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, next_payload) ? next_payload : 0;
}

std::uint64_t
shm_chunk_next_payload_lvl4(const ShmLayout &layout,
                            std::uint64_t current_payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, current_payload_offset);
  if (!chunk)
    return 0;
  std::uint64_t next_off =
      chunk->next_offset_lvl4.load(std::memory_order_acquire);
  if (next_off == 0)
    return 0;
  std::uint64_t next_payload = next_off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, next_payload) ? next_payload : 0;
}

std::uint64_t
shm_chunk_next_payload_lvl8(const ShmLayout &layout,
                            std::uint64_t current_payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, current_payload_offset);
  if (!chunk)
    return 0;
  std::uint64_t next_off =
      chunk->next_offset_lvl8.load(std::memory_order_acquire);
  if (next_off == 0)
    return 0;
  std::uint64_t next_payload = next_off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, next_payload) ? next_payload : 0;
}

bool shm_chunk_verify_crc(const ShmLayout &layout,
                          std::uint64_t payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, payload_offset);
  if (!chunk)
    return false;
  std::uint32_t crc = calculate_fast_crc32(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);
  return crc == chunk->checksum;
}

// Process local total count of CRC verification failures
[[nodiscard]] std::uint64_t shm_crc_failures_total() noexcept {
  return detail::g_crc_failures_total.load(std::memory_order_relaxed);
}

// Process-local count of CRC verification failures for a specific kind.
[[nodiscard]] std::uint64_t
shm_crc_failures_by_kind(std::uint16_t kind) noexcept {
  if (kind >= KIND_BUCKETS)
    return 0;
  return detail::g_crc_failures_by_kind[kind].load(std::memory_order_relaxed);
}

using ChunkRecoveryFn =
    std::function<bool(std::uint16_t kind, std::uint64_t chunk_offset,
                       void *dest, std::size_t size)>;

bool shm_chunk_verify_crc_tracked(ShmLayout &layout,
                                  std::uint64_t payload_offset,
                                  const ChunkRecoveryFn &recover) noexcept {
  auto *chunk = get_chunk_mut(layout, payload_offset);
  if (!chunk)
    return false;

  std::uint32_t crc = calculate_fast_crc32(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);

  if (crc == chunk->checksum) {
    return true;
  }

  detail::g_crc_failures_total.fetch_add(1, std::memory_order_relaxed);
  if (chunk->kind < KIND_BUCKETS) {
    detail::g_crc_failures_by_kind[chunk->kind].fetch_add(
        1, std::memory_order_relaxed);
  }

  if (!recover) {
    return false;
  }

  std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  if (!recover(chunk->kind, chunk_offset, layout.base + payload_offset,
               chunk->payload_size)) {
    return false;
  }

  std::uint32_t recovered_crc = calculate_fast_crc32(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);

  return recovered_crc == chunk->checksum;
}

bool shm_chunk_is_consistent(const ShmLayout &layout,
                             std::uint64_t payload_offset) noexcept {
  if (!layout.base || !layout.data_heap)
    return false;
  std::size_t min_offset = layout.header->off_data_heap + heap_metadata_size() +
                           sizeof(ShmChunkHeader);
  if (payload_offset < min_offset || payload_offset >= SHM_SIZE) {
    return false;
  }
  std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  auto *chunk =
      reinterpret_cast<const ShmChunkHeader *>(layout.base + chunk_offset);
  if (chunk->magic != CHUNK_MAGIC)
    return false;
  std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);
  if (!(flags & CHUNK_COMMITTED))
    return false;
  auto *heap_hdr = reinterpret_cast<const ShmHeapHeader *>(layout.data_heap);
  std::uint64_t current_epoch = heap_hdr->epoch.load(std::memory_order_acquire);
  std::uint64_t barrier =
      heap_hdr->epoch_barrier.load(std::memory_order_acquire);
  if (chunk->epoch != current_epoch)
    return false;
  if (chunk->epoch < barrier)
    return false;
  std::uint32_t crc = calculate_fast_crc32(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);
  if (crc != chunk->checksum)
    return false;
  return true;
}

std::uint64_t shm_chunk_scan(const ShmLayout &layout,
                             std::uint64_t start_payload_offset,
                             std::uint32_t step_log2) noexcept {
  switch (step_log2) {
  case 0:
    return shm_chunk_next_payload(layout, start_payload_offset);
  case 1:
    return shm_chunk_next_payload_lvl2(layout, start_payload_offset);
  case 2:
    return shm_chunk_next_payload_lvl4(layout, start_payload_offset);
  case 3:
    return shm_chunk_next_payload_lvl8(layout, start_payload_offset);
  default:
    return 0;
  }
}

std::uint64_t shm_heap_first_by_kind(const ShmLayout &layout,
                                     std::uint16_t kind) noexcept {
  if (!layout.data_heap || kind >= KIND_BUCKETS)
    return 0;
  auto *heap_root = get_heap_root(layout);
  std::uint64_t off =
      heap_root->off_first_by_kind[kind].load(std::memory_order_acquire);
  if (off == 0)
    return 0;
  std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_heap_first_by_producer(const ShmLayout &layout,
                                         std::uint16_t producer_id) noexcept {
  if (!layout.data_heap || producer_id >= PRODUCER_BUCKETS)
    return 0;
  auto *heap_root = get_heap_root(layout);
  std::uint64_t off = heap_root->off_first_by_producer[producer_id].load(
      std::memory_order_acquire);
  if (off == 0)
    return 0;
  std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_iter_begin_by_kind(const ShmLayout &layout,
                                     std::uint16_t kind) noexcept {
  return shm_heap_first_by_kind(layout, kind);
}

std::uint64_t
shm_iter_next_by_kind(const ShmLayout &layout,
                      std::uint64_t current_payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, current_payload_offset);
  if (!chunk)
    return 0;
  std::uint64_t next_off =
      chunk->next_kind_offset.load(std::memory_order_acquire);
  if (next_off == 0)
    return 0;
  std::uint64_t payload = next_off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_iter_begin_by_producer(const ShmLayout &layout,
                                         std::uint16_t producer_id) noexcept {
  return shm_heap_first_by_producer(layout, producer_id);
}

std::uint64_t
shm_iter_next_by_producer(const ShmLayout &layout,
                          std::uint64_t current_payload_offset) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, current_payload_offset);
  if (!chunk)
    return 0;
  std::uint64_t next_off =
      chunk->next_producer_offset.load(std::memory_order_acquire);
  if (next_off == 0)
    return 0;
  std::uint64_t payload = next_off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

bool shm_chunk_is_visible_for_analytics(const ShmLayout &layout,
                                        std::uint64_t payload_offset) noexcept {
  return shm_chunk_is_valid(layout, payload_offset);
}

bool shm_chunk_is_visible_for_debug(const ShmLayout &layout,
                                    std::uint64_t payload_offset) noexcept {
  if (!layout.base || !layout.data_heap)
    return false;
  std::size_t min_offset = layout.header->off_data_heap + heap_metadata_size() +
                           sizeof(ShmChunkHeader);
  if (payload_offset < min_offset || payload_offset >= SHM_SIZE) {
    return false;
  }
  std::uint64_t chunk_offset = payload_offset - sizeof(ShmChunkHeader);
  auto *chunk =
      reinterpret_cast<const ShmChunkHeader *>(layout.base + chunk_offset);
  return chunk->magic == CHUNK_MAGIC;
}

void shm_journal_write(ShmLayout &layout, const BridgeMessage &msg) noexcept {
  if (!layout.tx_log) {
    return;
  }

  // Obtain transaction log header located at the start of the tx_log partition
  auto *stats = shm_engine_stats(layout);
  if (!stats) {
    return;
  }

  // Atomically increment last update epoch / sequence counter
  std::uint64_t seq =
      stats->last_update_epoch.fetch_add(1, std::memory_order_relaxed) + 1;

  BridgeMessage copy = msg;
  copy.journal_seq = static_cast<std::uint32_t>(seq);
  auto *journal_entry = reinterpret_cast<BridgeMessage *>(
      layout.tx_log + sizeof(EngineStats) +
      ((seq - 1) % JOURNAL_CAPACITY) * sizeof(BridgeMessage));

  std::memcpy(journal_entry, &copy, sizeof(BridgeMessage));
  std::atomic_thread_fence(std::memory_order_release);
}

bool shm_journal_replay_next(ShmLayout &layout, std::uint64_t read_index,
                             BridgeMessage &out) noexcept {
  if (!layout.tx_log) {
    return false;
  }

  auto *stats = shm_engine_stats(layout);
  if (!stats) {
    return false;
  }

  // Obtain the current total count of written journal messages
  const std::uint64_t current_seq =
      stats->last_update_epoch.load(std::memory_order_acquire);

  // Check if there are unread messages available
  if (read_index >= current_seq) {
    return false;
  }

  // Calculate position within the ring buffer area of tx_log
  const auto *journal_entry = reinterpret_cast<const BridgeMessage *>(
      layout.tx_log + sizeof(EngineStats) +
      (read_index % JOURNAL_CAPACITY) * sizeof(BridgeMessage));

  std::atomic_thread_fence(std::memory_order_acquire);
  std::memcpy(&out, journal_entry, sizeof(BridgeMessage));

  return true;
}

void shm_extract_tensor_meta(const ShmLayout &layout, const BridgeMessage &msg,
                             void *&ptr, std::size_t &count) noexcept {
  if (!layout.base || !layout.header) {
    ptr = nullptr;
    count = 0;
    return;
  }

  const std::uint64_t offset = msg.shm_ref.offset;
  const std::size_t bytes = msg.shm_ref.total_bytes;

  // Validate offset and buffer capacity to prevent out-of-bounds access
  if (offset >= layout.header->total_size ||
      (offset + bytes) > layout.header->total_size) {
    ptr = nullptr;
    count = 0;
    return;
  }

  ptr = layout.base + offset;
  count = bytes;
}

// ============================================================================
// Ring Buffer Diagnostics: Shared Memory Backpressure Calculation
// ============================================================================
float shm_backpressure_level(const ShmLayout &layout) noexcept {
  if (!layout.cmd_ring) {
    return 0.0f;
  }

  // layout.cmd_ring is typed as CmdRing* (ShmMpmcRing<BridgeMessage, N>*)
  const auto *ring = layout.cmd_ring;

  // Retrieve capacity via member function API
  const std::size_t capacity = ring->capacity();
  if (capacity == 0) {
    return 0.0f;
  }

  const std::uint64_t head = ring->head_sequence();
  const std::uint64_t tail = ring->tail_sequence();

  const std::size_t occupied_slots =
      (head >= tail) ? static_cast<std::size_t>(head - tail) : 0;

  const float ratio =
      static_cast<float>(occupied_slots) / static_cast<float>(capacity);

  // Clamp the ratio strictly between 0.0f and 1.0f
  return (ratio > 1.0f) ? 1.0f : ((ratio < 0.0f) ? 0.0f : ratio);
}

struct IoCoalescingBudget {
  std::uint32_t max_gap_bytes;
  std::uint32_t max_read_bytes;
};

[[nodiscard]] inline IoCoalescingBudget
shm_adaptive_io_budget(const ShmLayout &layout,
                       const IoCoalescingBudget &base) noexcept {
  const float pressure = shm_backpressure_level(layout);
  // Linear interpolation from 1.0x (no pressure) down to 0.25x (saturated).
  const float scale = 1.0f - (0.75f * pressure);

  IoCoalescingBudget out;
  out.max_gap_bytes = static_cast<std::uint32_t>(
      static_cast<float>(base.max_gap_bytes) * scale);
  out.max_read_bytes = static_cast<std::uint32_t>(
      static_cast<float>(base.max_read_bytes) * scale);
  return out;
}

[[nodiscard]] inline bool
shm_admission_should_throttle(const ShmLayout &layout,
                              float high_watermark = 0.85f) noexcept {
  return shm_backpressure_level(layout) >= high_watermark;
}

void shm_register_runtime(ShmLayout &layout, std::uint32_t id) noexcept {
  if (!layout.header) {
    return;
  }

  // Ensure runtime ID stays within allocated cluster array boundaries
  if (id >= MAX_RUNTIMES) {
    return;
  }
  layout.header->cluster_runtime_ids[id].store(1, std::memory_order_release);
}

void shm_unregister_runtime(ShmLayout &layout, std::uint32_t id) noexcept {
  if (!layout.header || id >= MAX_RUNTIMES) {
    return;
  }
  layout.header->cluster_runtime_ids[id].store(0, std::memory_order_release);
}

bool shm_is_runtime_active(const ShmLayout &layout, std::uint32_t id) noexcept {
  if (!layout.header || id >= MAX_RUNTIMES) {
    return false;
  }

  return layout.header->cluster_runtime_ids[id].load(
             std::memory_order_acquire) != 0;
}

// Portable prefetch macro supporting GCC, Clang, and MSVC across x86_64 and
// ARM64
#if defined(__GNUC__) || defined(__clang__)
#define SPHERE_PREFETCH(addr, rw, locality)                                    \
  __builtin_prefetch((addr), (rw), (locality))
#elif defined(_MSC_VER)
#if defined(_M_X64) || defined(_M_IX86)
// MSVC x86/x64: Locality mapping (_MM_HINT_T0 = 3, _MM_HINT_T1 = 2, _MM_HINT_T2
// = 1, _MM_HINT_NTA = 0)
#define SPHERE_PREFETCH(addr, rw, locality)                                    \
  _mm_prefetch(reinterpret_cast<const char *>(addr),                           \
               ((locality) == 3)   ? _MM_HINT_T0                               \
               : ((locality) == 2) ? _MM_HINT_T1                               \
               : ((locality) == 1) ? _MM_HINT_T2                               \
                                   : _MM_HINT_NTA)
#elif defined(_M_ARM64) || defined(_M_ARM)
// MSVC ARM/ARM64: Uses native intrinsic
#define SPHERE_PREFETCH(addr, rw, locality)                                    \
  __prefetch(reinterpret_cast<const void *>(addr))
#else
#define SPHERE_PREFETCH(addr, rw, locality) (void)(addr)
#endif
#else
#define SPHERE_PREFETCH(addr, rw, locality) (void)(addr)
#endif

std::uint64_t shm_heap_prefetch_head(const ShmLayout &layout) noexcept {
  const auto *heap_root = get_heap_root(layout);
  const std::uint64_t off =
      heap_root->off_prefetch_head.load(std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }

  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  SPHERE_PREFETCH(layout.base + off, 0, 3);

  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_heap_prefetch_head_by_kind(const ShmLayout &layout,
                                             std::uint16_t kind) noexcept {
  if (!layout.data_heap || kind >= KIND_BUCKETS) {
    return 0;
  }

  const auto *heap_root = get_heap_root(layout);
  const std::uint64_t off = heap_root->off_prefetch_head_by_kind[kind].load(
      std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }

  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  SPHERE_PREFETCH(layout.base + off, 0, 3);

  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t
shm_heap_prefetch_head_by_producer(const ShmLayout &layout,
                                   std::uint16_t producer_id) noexcept {
  if (!layout.data_heap || producer_id >= PRODUCER_BUCKETS) {
    return 0;
  }

  const auto *heap_root = get_heap_root(layout);
  const std::uint64_t off =
      heap_root->off_prefetch_head_by_producer[producer_id].load(
          std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }

  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  SPHERE_PREFETCH(layout.base + off, 0, 3);

  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_prefetch_range(const ShmLayout &layout,
                                 std::uint64_t start_payload_offset,
                                 std::uint32_t step_log2,
                                 std::uint32_t depth) noexcept {
  std::uint64_t current = start_payload_offset;

  for (std::uint32_t i = 0; i < depth && current != 0; ++i) {
    if (current < sizeof(ShmChunkHeader)) {
      break;
    }

    const std::uint64_t chunk_offset = current - sizeof(ShmChunkHeader);

    // Prefetch chunk header into L2 cache and payload into L1 cache
    SPHERE_PREFETCH(layout.base + chunk_offset, 0, 2);
    SPHERE_PREFETCH(layout.base + current, 0, 3);

    current = shm_chunk_scan(layout, current, step_log2);
  }

  return current;
}

void shm_heap_update_prefetch(ShmLayout &layout, std::uint16_t kind) noexcept {
  if (!layout.base || !layout.data_heap || kind >= KIND_BUCKETS) {
    return;
  }

  auto *heap_root = get_heap_root(layout);
  if (!heap_root) {
    return;
  }

  std::uint64_t curr_off =
      heap_root->off_first_by_kind[kind].load(std::memory_order_acquire);

  if (curr_off == 0) {
    return;
  }

  std::uint64_t best_off = curr_off;

  while (curr_off != 0) {
    const auto *chunk =
        reinterpret_cast<const ShmChunkHeader *>(layout.base + curr_off);
    const std::uint16_t flags = chunk->flags.load(std::memory_order_relaxed);

    if ((flags & CHUNK_COMMITTED) && !(flags & CHUNK_TOMBSTONE)) {
      best_off = curr_off;
      SPHERE_PREFETCH(layout.base + curr_off + sizeof(ShmChunkHeader), 0, 1);
    }

    curr_off = chunk->next_kind_offset.load(std::memory_order_acquire);
  }

  heap_root->off_prefetch_head_by_kind[kind].store(best_off,
                                                   std::memory_order_release);
}

void shm_heap_set_prefetch_hint(
    ShmLayout &layout, std::uint16_t kind,
    std::uint64_t planned_next_payload_offset) noexcept {
  if (!layout.data_heap || kind >= KIND_BUCKETS) {
    return;
  }
  if (planned_next_payload_offset < sizeof(ShmChunkHeader)) {
    return;
  }

  auto *heap_root = get_heap_root(layout);
  const std::uint64_t chunk_offset =
      planned_next_payload_offset - sizeof(ShmChunkHeader);

  heap_root->off_prefetch_head_by_kind[kind].store(chunk_offset,
                                                   std::memory_order_release);
  SPHERE_PREFETCH(layout.base + chunk_offset, 0, 3);
}

void shm_heap_set_producer_numa_node(std::uint16_t producer_id,
                                     std::int32_t numa_node) noexcept {
  if (producer_id >= PRODUCER_BUCKETS)
    return;
  detail::g_producer_numa_table.nodes[producer_id].store(
      numa_node, std::memory_order_relaxed);
}

[[nodiscard]] std::int32_t
shm_heap_preferred_numa_node_for_producer(std::uint16_t producer_id) noexcept {
  if (producer_id >= PRODUCER_BUCKETS)
    return -1;
  return detail::g_producer_numa_table.nodes[producer_id].load(
      std::memory_order_relaxed);
}

void shm_heap_collect_debug_stats(const ShmLayout &layout,
                                  ShmHeapDebugStats &out) noexcept {
  if (!layout.data_heap)
    return;
  auto *heap_root = get_heap_root(layout);
  std::uint64_t off =
      heap_root->off_first_chunk.load(std::memory_order_acquire);
  while (off != 0) {
    auto *chunk = reinterpret_cast<const ShmChunkHeader *>(layout.base + off);
    std::uint16_t flags = chunk->flags.load(std::memory_order_relaxed);
    if (flags & CHUNK_COMMITTED) {
      out.committed++;
    }
    if (flags & CHUNK_DIRTY) {
      out.dirty++;
    }
    if (flags & CHUNK_TOMBSTONE) {
      out.tombstone++;
    }
    if (!(flags & CHUNK_COMMITTED) && !(flags & CHUNK_DIRTY) &&
        !(flags & CHUNK_TOMBSTONE)) {
      out.incomplete++;
    }
    if (chunk->kind < KIND_BUCKETS) {
      out.by_kind[chunk->kind]++;
    }
    if (chunk->producer_id < PRODUCER_BUCKETS) {
      out.by_producer[chunk->producer_id]++;
    }
    off = chunk->next_offset.load(std::memory_order_acquire);
  }
}

void shm_heap_set_quota_kind(ShmLayout &layout, std::uint16_t kind,
                             std::uint64_t quota) noexcept {
  if (!layout.data_heap || kind >= KIND_BUCKETS)
    return;
  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->quota_by_kind[kind].store(quota, std::memory_order_relaxed);
}

void shm_heap_set_quota_producer(ShmLayout &layout, std::uint16_t producer_id,
                                 std::uint64_t quota) noexcept {
  if (!layout.data_heap || producer_id >= PRODUCER_BUCKETS)
    return;
  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->quota_by_producer[producer_id].store(quota,
                                                 std::memory_order_relaxed);
}

void shm_heap_set_snapshot_epoch_window(ShmLayout &layout,
                                        std::uint64_t min_epoch,
                                        std::uint64_t max_epoch) noexcept {
  if (!layout.data_heap)
    return;
  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->snapshot_min_epoch.store(min_epoch, std::memory_order_relaxed);
  heap_hdr->snapshot_max_epoch.store(max_epoch, std::memory_order_relaxed);
}

void shm_heap_set_snapshot_kind(ShmLayout &layout, std::uint16_t kind,
                                std::uint64_t min_epoch,
                                std::uint64_t max_epoch) noexcept {
  if (!layout.data_heap || kind >= KIND_BUCKETS)
    return;
  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->snapshot_min_epoch_by_kind[kind].store(min_epoch,
                                                   std::memory_order_relaxed);
  heap_hdr->snapshot_max_epoch_by_kind[kind].store(max_epoch,
                                                   std::memory_order_relaxed);
}

void shm_heap_set_snapshot_producer(ShmLayout &layout,
                                    std::uint16_t producer_id,
                                    std::uint64_t min_epoch,
                                    std::uint64_t max_epoch) noexcept {
  if (!layout.data_heap || producer_id >= PRODUCER_BUCKETS)
    return;
  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->snapshot_min_epoch_by_producer[producer_id].store(
      min_epoch, std::memory_order_relaxed);
  heap_hdr->snapshot_max_epoch_by_producer[producer_id].store(
      max_epoch, std::memory_order_relaxed);
}

void shm_heap_retire_chunk(ShmLayout &layout,
                           std::uint64_t payload_offset) noexcept {
  auto *chunk = get_chunk_mut(layout, payload_offset);
  if (!chunk)
    return;
  std::uint16_t flags = chunk->flags.load(std::memory_order_relaxed);
  if (flags & CHUNK_TOMBSTONE)
    return;
  chunk->flags.fetch_or(CHUNK_TOMBSTONE, std::memory_order_release);

  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->active_allocations.fetch_sub(1, std::memory_order_relaxed);
  if (chunk->kind < KIND_BUCKETS) {
    heap_hdr->allocations_by_kind[chunk->kind].fetch_sub(
        1, std::memory_order_relaxed);
  }
  if (chunk->producer_id < PRODUCER_BUCKETS) {
    heap_hdr->allocations_by_producer[chunk->producer_id].fetch_sub(
        1, std::memory_order_relaxed);
  }
}

std::size_t shm_heap_compact_logical(ShmLayout &layout) noexcept {
  if (!layout.base || !layout.data_heap)
    return 0;

  auto *heap_root = get_heap_root(layout);
  std::uint64_t curr_off =
      heap_root->off_first_chunk.load(std::memory_order_acquire);

  std::size_t reclaimed_chunks = 0;
  std::uint64_t prev_off = 0;

  // CALL the function to compute the minimum active reader epoch
  const std::uint64_t safe_reclaim_epoch =
      shm_get_min_active_reader_epoch(layout);

  std::uint64_t prev_kind_off[KIND_BUCKETS] = {0};
  std::uint64_t prev_prod_off[PRODUCER_BUCKETS] = {0};

  while (curr_off != 0) {
    auto *chunk = reinterpret_cast<ShmChunkHeader *>(layout.base + curr_off);

    std::uint64_t next_off = chunk->next_offset.load(std::memory_order_acquire);
    std::uint64_t next_kind =
        chunk->next_kind_offset.load(std::memory_order_acquire);
    std::uint64_t next_prod =
        chunk->next_producer_offset.load(std::memory_order_acquire);
    std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);
    const bool is_tombstone = (flags & CHUNK_TOMBSTONE) != 0;
    const bool is_safe_to_reclaim =
        is_tombstone && (chunk->epoch < safe_reclaim_epoch);

    if (is_safe_to_reclaim) {

      if (prev_off != 0) {
        auto *prev_chunk =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev_off);
        prev_chunk->next_offset.store(next_off, std::memory_order_release);

        if (next_off == 0) {
          heap_root->off_last_chunk.store(prev_off, std::memory_order_release);
        }

        auto repair_skip = [](std::atomic<std::uint64_t> &prev_lvl,
                              std::atomic<std::uint64_t> &curr_lvl,
                              std::uint64_t deleted_off,
                              std::uint64_t fallback_next) {
          if (prev_lvl.load(std::memory_order_relaxed) == deleted_off) {
            std::uint64_t target = curr_lvl.load(std::memory_order_relaxed);
            prev_lvl.store(target != 0 ? target : fallback_next,
                           std::memory_order_release);
          }
        };

        repair_skip(prev_chunk->next_offset_lvl2, chunk->next_offset_lvl2,
                    curr_off, next_off);
        repair_skip(prev_chunk->next_offset_lvl4, chunk->next_offset_lvl4,
                    curr_off, next_off);
        repair_skip(prev_chunk->next_offset_lvl8, chunk->next_offset_lvl8,
                    curr_off, next_off);
      } else {
        heap_root->off_first_chunk.store(next_off, std::memory_order_release);

        if (next_off == 0) {
          heap_root->off_last_chunk.store(0, std::memory_order_release);
          heap_root->off_last_lvl2.store(0, std::memory_order_release);
          heap_root->off_last_lvl4.store(0, std::memory_order_release);
          heap_root->off_last_lvl8.store(0, std::memory_order_release);
        }
      }

      if (chunk->kind < KIND_BUCKETS) {
        std::uint16_t k = chunk->kind;
        if (prev_kind_off[k] != 0) {
          auto *pk = reinterpret_cast<ShmChunkHeader *>(layout.base +
                                                        prev_kind_off[k]);
          pk->next_kind_offset.store(next_kind, std::memory_order_release);
          if (next_kind == 0) {
            heap_root->off_last_by_kind[k].store(prev_kind_off[k],
                                                 std::memory_order_release);
          }
        } else {
          heap_root->off_first_by_kind[k].store(next_kind,
                                                std::memory_order_release);
          if (next_kind == 0) {
            heap_root->off_last_by_kind[k].store(0, std::memory_order_release);
          }
        }
      }

      if (chunk->producer_id < PRODUCER_BUCKETS) {
        std::uint16_t p = chunk->producer_id;
        if (prev_prod_off[p] != 0) {
          auto *pp = reinterpret_cast<ShmChunkHeader *>(layout.base +
                                                        prev_prod_off[p]);
          pp->next_producer_offset.store(next_prod, std::memory_order_release);
          if (next_prod == 0) {
            heap_root->off_last_by_producer[p].store(prev_prod_off[p],
                                                     std::memory_order_release);
          }
        } else {
          heap_root->off_first_by_producer[p].store(next_prod,
                                                    std::memory_order_release);
          if (next_prod == 0) {
            heap_root->off_last_by_producer[p].store(0,
                                                     std::memory_order_release);
          }
        }
      }

      chunk->flags.store(CHUNK_FREE, std::memory_order_release);
      reclaimed_chunks++;
    } else {
      prev_off = curr_off;
      if (chunk->kind < KIND_BUCKETS) {
        prev_kind_off[chunk->kind] = curr_off;
      }
      if (chunk->producer_id < PRODUCER_BUCKETS) {
        prev_prod_off[chunk->producer_id] = curr_off;
      }
    }

    curr_off = next_off;
  }

  return reclaimed_chunks;
}

void shm_heap_defragment(ShmLayout &layout) noexcept {
  if (!layout.base || !layout.data_heap) {
    return;
  }

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);
  if (!heap_hdr || !heap_root) {
    return;
  }

  // Retrieve the oldest active reader epoch across all runtimes
  const std::uint64_t safe_reclaim_epoch =
      shm_get_min_active_reader_epoch(layout);

  std::uint64_t curr_offset =
      heap_root->off_first_chunk.load(std::memory_order_acquire);
  if (curr_offset == 0) {
    return;
  }

  // Signal defragmentation start to stall new reader attachments
  heap_root->flags.fetch_or(SHM_HEAP_FLAG_DEFRAG_IN_PROGRESS,
                            std::memory_order_acq_rel);

  // Check whether active concurrent readers exist in the system
  const bool has_active_readers = shm_has_active_readers(layout);

  const std::uint64_t heap_data_start =
      layout.header->off_data_heap + heap_metadata_size();

  std::uint64_t write_offset = heap_data_start;
  std::uint64_t prev_written_chunk_off = 0;
  std::size_t total_active_bytes = 0;

  std::uint64_t prev_kind_off[KIND_BUCKETS] = {0};
  std::uint64_t prev_prod_off[PRODUCER_BUCKETS] = {0};

  std::uint64_t prev_lvl2_off = 0;
  std::uint64_t prev_lvl4_off = 0;
  std::uint64_t prev_lvl8_off = 0;
  std::uint32_t seq = 0;

  // Clear root linkage indices prior to reconstruction
  for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
    heap_root->off_first_by_kind[i].store(0, std::memory_order_relaxed);
    heap_root->off_last_by_kind[i].store(0, std::memory_order_relaxed);
  }
  for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
    heap_root->off_first_by_producer[i].store(0, std::memory_order_relaxed);
    heap_root->off_last_by_producer[i].store(0, std::memory_order_relaxed);
  }

  heap_root->off_last_lvl2.store(0, std::memory_order_relaxed);
  heap_root->off_last_lvl4.store(0, std::memory_order_relaxed);
  heap_root->off_last_lvl8.store(0, std::memory_order_relaxed);

  while (curr_offset != 0) {
    auto *chunk = reinterpret_cast<ShmChunkHeader *>(layout.base + curr_offset);
    const std::uint64_t next_offset =
        chunk->next_offset.load(std::memory_order_acquire);
    const std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);

    const bool is_tombstone = (flags & CHUNK_TOMBSTONE) != 0;
    const bool drop_permanently =
        (flags & CHUNK_FREE) ||
        (is_tombstone && (chunk->epoch < safe_reclaim_epoch));

    if (drop_permanently) {
      curr_offset = next_offset;
      continue;
    }

    const std::uint64_t total_chunk_size =
        align_up(sizeof(ShmChunkHeader) + chunk->payload_size, CACHE_LINE_SIZE);

    const std::uint64_t target_offset =
        has_active_readers ? curr_offset : write_offset;

    // Perform physical relocation ONLY when no active readers are present
    if (!has_active_readers && curr_offset != write_offset) {
      std::memmove(layout.base + write_offset, layout.base + curr_offset,
                   total_chunk_size);
    }

    auto *relocated =
        reinterpret_cast<ShmChunkHeader *>(layout.base + target_offset);

    // Reset linked list pointers
    relocated->next_offset.store(0, std::memory_order_relaxed);
    relocated->next_kind_offset.store(0, std::memory_order_relaxed);
    relocated->next_producer_offset.store(0, std::memory_order_relaxed);
    relocated->next_offset_lvl2.store(0, std::memory_order_relaxed);
    relocated->next_offset_lvl4.store(0, std::memory_order_relaxed);
    relocated->next_offset_lvl8.store(0, std::memory_order_relaxed);

    // 1. Primary Global List Linkage
    if (prev_written_chunk_off != 0) {
      auto *prev_chunk = reinterpret_cast<ShmChunkHeader *>(
          layout.base + prev_written_chunk_off);
      prev_chunk->next_offset.store(target_offset, std::memory_order_release);
    } else {
      heap_root->off_first_chunk.store(target_offset,
                                       std::memory_order_release);
    }

    // 2. Kind Index Linkage
    if (relocated->kind < KIND_BUCKETS) {
      const std::uint16_t k = relocated->kind;
      if (prev_kind_off[k] != 0) {
        auto *pk =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev_kind_off[k]);
        pk->next_kind_offset.store(target_offset, std::memory_order_release);
      } else {
        heap_root->off_first_by_kind[k].store(target_offset,
                                              std::memory_order_release);
      }
      prev_kind_off[k] = target_offset;
      heap_root->off_last_by_kind[k].store(target_offset,
                                           std::memory_order_release);
    }

    // 3. Producer Index Linkage
    if (relocated->producer_id < PRODUCER_BUCKETS) {
      const std::uint16_t p = relocated->producer_id;
      if (prev_prod_off[p] != 0) {
        auto *pp =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev_prod_off[p]);
        pp->next_producer_offset.store(target_offset,
                                       std::memory_order_release);
      } else {
        heap_root->off_first_by_producer[p].store(target_offset,
                                                  std::memory_order_release);
      }
      prev_prod_off[p] = target_offset;
      heap_root->off_last_by_producer[p].store(target_offset,
                                               std::memory_order_release);
    }

    // 4. Skip-List Linkage (Levels 2, 4, 8)
    ++seq;
    if ((seq & 1u) == 0) {
      if (prev_lvl2_off != 0) {
        auto *p2 =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl2_off);
        p2->next_offset_lvl2.store(target_offset, std::memory_order_release);
      }
      prev_lvl2_off = target_offset;
      heap_root->off_last_lvl2.store(target_offset, std::memory_order_release);
    }
    if ((seq & 3u) == 0) {
      if (prev_lvl4_off != 0) {
        auto *p4 =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl4_off);
        p4->next_offset_lvl4.store(target_offset, std::memory_order_release);
      }
      prev_lvl4_off = target_offset;
      heap_root->off_last_lvl4.store(target_offset, std::memory_order_release);
    }
    if ((seq & 7u) == 0) {
      if (prev_lvl8_off != 0) {
        auto *p8 =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl8_off);
        p8->next_offset_lvl8.store(target_offset, std::memory_order_release);
      }
      prev_lvl8_off = target_offset;
      heap_root->off_last_lvl8.store(target_offset, std::memory_order_release);
    }

    prev_written_chunk_off = target_offset;
    write_offset += total_chunk_size;
    total_active_bytes += total_chunk_size;

    curr_offset = next_offset;
  }

  heap_root->n_chunks.store(seq, std::memory_order_release);

  if (prev_written_chunk_off != 0) {
    heap_root->off_last_chunk.store(prev_written_chunk_off,
                                    std::memory_order_release);
  } else {
    heap_root->off_first_chunk.store(0, std::memory_order_release);
    heap_root->off_last_chunk.store(0, std::memory_order_release);
  }

  if (!has_active_readers) {
    heap_hdr->allocated_bytes.store(total_active_bytes,
                                    std::memory_order_release);
  }

  if (auto *stats = shm_engine_stats(layout); stats != nullptr) {
    stats->heap_usage_bytes.store(total_active_bytes,
                                  std::memory_order_relaxed);
    if (!has_active_readers) {
      stats->heap_fragmentation_score.store(0, std::memory_order_relaxed);
    }
  }

  // Clear defragmentation flag
  heap_root->flags.fetch_and(~SHM_HEAP_FLAG_DEFRAG_IN_PROGRESS,
                             std::memory_order_acq_rel);
}

bool shm_run_auto_compaction_once(ShmLayout &layout,
                                  std::uint64_t watermark) noexcept {
  if (!layout.header || !layout.data_heap)
    return false;

  auto *stats = shm_engine_stats(layout);
  if (!stats)
    return false;

  const std::uint64_t score =
      stats->heap_fragmentation_score.load(std::memory_order_relaxed);

  if (score < watermark)
    return false;

  shm_heap_compact_logical(layout);
  shm_heap_defragment(layout);
  shm_heap_decay_all_lfu();

  return true;
}
[[nodiscard]] std::jthread shm_start_auto_compaction(
    ShmLayout &layout, std::uint64_t watermark,
    std::chrono::milliseconds poll_interval = std::chrono::milliseconds(500)) {
  return std::jthread(
      [&layout, watermark, poll_interval](std::stop_token stop_tok) {
        while (!stop_tok.stop_requested()) {
          shm_run_auto_compaction_once(layout, watermark);
          std::this_thread::sleep_for(poll_interval);
        }
      });
}

void shm_relocate_chunk(ShmLayout &layout, std::uint64_t old_off,
                        std::uint64_t new_off) noexcept {
  if (!layout.base || old_off == 0 || new_off == 0 || old_off == new_off) {
    return;
  }

  // Validate bounds against shared memory total capacity
  if (!layout.header || old_off >= layout.header->total_size ||
      new_off >= layout.header->total_size) {
    return;
  }

  const auto *src_chunk =
      reinterpret_cast<const ShmChunkHeader *>(layout.base + old_off);
  const std::size_t total_chunk_size = align_up(
      sizeof(ShmChunkHeader) + src_chunk->payload_size, CACHE_LINE_SIZE);
  if ((new_off + total_chunk_size) > layout.header->total_size) {
    return;
  }
  std::memmove(layout.base + new_off, layout.base + old_off, total_chunk_size);
}

// ============================================================================
// CRC32 Calculation
// ============================================================================
std::uint32_t compute_crc32(const std::uint8_t *data,
                            std::size_t length) noexcept {
  static constexpr std::array<std::uint32_t, 256> ieee_table = []() {
    std::array<std::uint32_t, 256> table{};
    for (std::uint32_t i = 0; i < 256; ++i) {
      std::uint32_t ch = i;
      for (std::size_t j = 0; j < 8; ++j) {
        ch = (ch & 1) ? (0xEDB88320u ^ (ch >> 1)) : (ch >> 1);
      }
      table[i] = ch;
    }
    return table;
  }();

  std::uint32_t crc = 0xFFFFFFFFu;
  for (std::size_t i = 0; i < length; ++i) {
    crc = (crc >> 8) ^ ieee_table[(crc ^ data[i]) & 0xFF];
  }
  return crc ^ 0xFFFFFFFFu;
}

// ============================================================================
// Shared Memory Allocation / Access
// ============================================================================
void *init_shared_memory(bool create) {
  Platform::ShmRegion region =
      create ? shm_create(SHM_NAME, SHM_SIZE) : shm_open(SHM_NAME, SHM_SIZE);
  return region.data();
}

// ============================================================================
// Engine State Management
// ============================================================================
void set_engine_ready(ShmHeader *header) noexcept {
  header->state.store(static_cast<std::uint32_t>(EngineState::READY),
                      std::memory_order_release);
}

void wait_until_initialized(const ShmHeader *header) noexcept {
  while (header->state.load(std::memory_order_acquire) ==
         static_cast<std::uint32_t>(EngineState::UNINITIALIZED)) {
    std::this_thread::yield();
  }
}

// ============================================================================
// Chunk Initialization & Linked List Appends
// ============================================================================
void init_chunk(Chunk *chunk) noexcept {
  chunk->flags.store(CHUNK_INCOMPLETE, std::memory_order_relaxed);
  chunk->next_offset.store(0, std::memory_order_relaxed);
  chunk->next_kind_offset.store(0, std::memory_order_relaxed);
  chunk->next_producer_offset.store(0, std::memory_order_relaxed);
  chunk->next_offset_lvl2.store(0, std::memory_order_relaxed);
  chunk->next_offset_lvl4.store(0, std::memory_order_relaxed);
  chunk->next_offset_lvl8.store(0, std::memory_order_relaxed);
}

void append_chunk(Chunk *last_chunk, Chunk *last_kind_chunk,
                  Chunk *last_prod_chunk, Chunk *last2, Chunk *last4,
                  Chunk *last8, std::uint64_t chunk_offset) noexcept {
  if (last_chunk) {
    last_chunk->next_offset.store(chunk_offset, std::memory_order_release);
  }

  if (last_kind_chunk) {
    last_kind_chunk->next_kind_offset.store(chunk_offset,
                                            std::memory_order_release);
  }

  if (last_prod_chunk) {
    last_prod_chunk->next_producer_offset.store(chunk_offset,
                                                std::memory_order_release);
  }

  if (last2) {
    last2->next_offset_lvl2.store(chunk_offset, std::memory_order_release);
  }

  if (last4) {
    last4->next_offset_lvl4.store(chunk_offset, std::memory_order_release);
  }

  if (last8) {
    last8->next_offset_lvl8.store(chunk_offset, std::memory_order_release);
  }
}

void commit_chunk(Chunk *chunk) noexcept {
  chunk->flags.store(CHUNK_COMMITTED, std::memory_order_release);
}

void shm_reader_pin(ShmLayout &layout, std::uint32_t runtime_id) noexcept {
  if (!layout.header || runtime_id >= MAX_RUNTIMES) {
    return;
  }

  auto *heap_hdr = get_heap_header(layout);
  if (!heap_hdr)
    return;

  // Retrieve the global current epoch
  std::uint64_t current_epoch = heap_hdr->epoch.load(std::memory_order_acquire);

  layout.header->cluster_runtime_ids[runtime_id].store(
      current_epoch, std::memory_order_release);
}

void shm_reader_unpin(ShmLayout &layout, std::uint32_t runtime_id) noexcept {
  if (!layout.header || runtime_id >= MAX_RUNTIMES) {
    return;
  }

  layout.header->cluster_runtime_ids[runtime_id].store(
      0, std::memory_order_release);
}

[[nodiscard]] std::uint64_t
shm_get_min_active_reader_epoch(const ShmLayout &layout) noexcept {
  if (!layout.header || !layout.data_heap) {
    return 0;
  }

  auto *heap_hdr = reinterpret_cast<const ShmHeapHeader *>(layout.data_heap);
  std::uint64_t min_epoch = heap_hdr->epoch.load(std::memory_order_acquire);

  for (std::size_t i = 0; i < MAX_RUNTIMES; ++i) {
    std::uint64_t r_epoch =
        layout.header->cluster_runtime_ids[i].load(std::memory_order_acquire);

    if (r_epoch > 0 && r_epoch < min_epoch) {
      min_epoch = r_epoch;
    }
  }

  return min_epoch;
}

} // namespace Sphere
