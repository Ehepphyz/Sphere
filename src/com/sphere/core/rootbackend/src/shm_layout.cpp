// shm_layout.cpp

// Region construction, bump heap, chunk index and journal.

#include "shm_layout.h"
#include "common_config.h"
#include "platform.h"
#include "span_ring.h"
#include "utils.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>

#if defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
#include <nmmintrin.h>
#elif defined(__aarch64__)
#include <arm_acle.h>
#endif

namespace Sphere {

namespace detail {

std::atomic<std::uint64_t> g_crc_failures_total{0};
std::atomic<std::uint64_t> g_crc_failures_by_kind[KIND_BUCKETS]{};

[[nodiscard]] constexpr std::uint64_t byteswap64(std::uint64_t v) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  return __builtin_bswap64(v);
#elif defined(_MSC_VER)
  return _byteswap_uint64(v);
#else
  return ((v & 0x00000000000000FFULL) << 56) |
         ((v & 0x000000000000FF00ULL) << 40) |
         ((v & 0x0000000000FF0000ULL) << 24) |
         ((v & 0x00000000FF000000ULL) << 8) |
         ((v & 0x000000FF00000000ULL) >> 8) |
         ((v & 0x0000FF0000000000ULL) >> 24) |
         ((v & 0x00FF000000000000ULL) >> 40) |
         ((v & 0xFF00000000000000ULL) >> 56);
#endif
}

// Reflected CRC-32C table, used where the hardware instruction is absent.
constexpr std::array<std::uint32_t, 256> make_crc32c_table() {
  constexpr std::uint32_t kPoly = 0x82F63B78u;
  std::array<std::uint32_t, 256> table{};
  for (std::uint32_t i = 0; i < 256; ++i) {
    std::uint32_t ch = i;
    for (int j = 0; j < 8; ++j) {
      ch = (ch & 1u) ? (kPoly ^ (ch >> 1)) : (ch >> 1);
    }
    table[i] = ch;
  }
  return table;
}

constexpr std::array<std::uint32_t, 256> kCrc32cTable = make_crc32c_table();

inline void cpu_pause() noexcept {
#if defined(__x86_64__) || defined(_M_X64)
  _mm_pause();
#elif defined(__aarch64__)
  asm volatile("yield" ::: "memory");
#endif
}

} // namespace detail

namespace {

/**
 * Software CRC-32C, always available.
 */
[[nodiscard]] std::uint32_t crc32c_software(const std::uint8_t *data,
                                            std::size_t len,
                                            std::uint32_t crc) noexcept {
  for (std::size_t i = 0; i < len; ++i) {
    crc = (crc >> 8) ^ detail::kCrc32cTable[(crc ^ data[i]) & 0xFFu];
  }
  return crc;
}

#if defined(__x86_64__) || defined(_M_X64)
/**
 * Hardware CRC-32C.
 */
[[nodiscard]] __attribute__((target("sse4.2"))) std::uint32_t
crc32c_hardware(const std::uint8_t *data, std::size_t len,
                std::uint32_t crc) noexcept {
  std::size_t i = 0;
  for (; i + sizeof(std::uint64_t) <= len; i += sizeof(std::uint64_t)) {
    std::uint64_t value = 0;
    std::memcpy(&value, data + i, sizeof(value));
    if constexpr (std::endian::native == std::endian::big) {
      value = detail::byteswap64(value);
    }
    crc = static_cast<std::uint32_t>(_mm_crc32_u64(crc, value));
  }
  if (i + sizeof(std::uint32_t) <= len) {
    std::uint32_t value = 0;
    std::memcpy(&value, data + i, sizeof(value));
    crc = _mm_crc32_u32(crc, value);
    i += sizeof(std::uint32_t);
  }
  return crc32c_software(data + i, len - i, crc);
}
#elif defined(__aarch64__)
[[nodiscard]] __attribute__((target("+crc"))) std::uint32_t
crc32c_hardware(const std::uint8_t *data, std::size_t len,
                std::uint32_t crc) noexcept {
  std::size_t i = 0;
  for (; i + sizeof(std::uint64_t) <= len; i += sizeof(std::uint64_t)) {
    std::uint64_t value = 0;
    std::memcpy(&value, data + i, sizeof(value));
    if constexpr (std::endian::native == std::endian::big) {
      value = detail::byteswap64(value);
    }
    crc = __crc32cd(crc, value);
  }
  if (i + sizeof(std::uint32_t) <= len) {
    std::uint32_t value = 0;
    std::memcpy(&value, data + i, sizeof(value));
    crc = __crc32cw(crc, value);
    i += sizeof(std::uint32_t);
  }
  return crc32c_software(data + i, len - i, crc);
}
#endif

/**
 * CRC-32C, dispatched once on the CPU's actual feature set.
 */
[[nodiscard]] std::uint32_t calculate_crc32c(const std::uint8_t *data,
                                             std::size_t len) noexcept {
  constexpr std::uint32_t kInit = 0xFFFFFFFFu;

#if defined(__x86_64__) || defined(_M_X64)
  static const bool has_hw = utils::cpu_capabilities().has_sse42;
  if (has_hw) {
    return crc32c_hardware(data, len, kInit) ^ 0xFFFFFFFFu;
  }
#elif defined(__aarch64__)
  static const bool has_hw = utils::cpu_capabilities().has_arm_crc;
  if (has_hw) {
    return crc32c_hardware(data, len, kInit) ^ 0xFFFFFFFFu;
  }
#endif

  return crc32c_software(data, len, kInit) ^ 0xFFFFFFFFu;
}

/// Mutable chunk header from a payload offset, without validity checks.
[[nodiscard]] inline ShmChunkHeader *
chunk_from_payload(ShmLayout &layout, std::uint64_t payload_offset) noexcept {
  if (layout.base == nullptr || payload_offset < sizeof(ShmChunkHeader)) {
    return nullptr;
  }
  return reinterpret_cast<ShmChunkHeader *>(layout.base + payload_offset -
                                            sizeof(ShmChunkHeader));
}

[[nodiscard]] inline const ShmChunkHeader *
chunk_from_payload(const ShmLayout &layout,
                   std::uint64_t payload_offset) noexcept {
  if (layout.base == nullptr || payload_offset < sizeof(ShmChunkHeader)) {
    return nullptr;
  }
  return reinterpret_cast<const ShmChunkHeader *>(
      layout.base + payload_offset - sizeof(ShmChunkHeader));
}

/// First byte offset a chunk payload can occupy.
[[nodiscard]] inline std::uint64_t
first_payload_offset(const ShmLayout &layout) noexcept {
  return layout.header->off_data_heap + heap_metadata_size() +
         sizeof(ShmChunkHeader);
}

[[noreturn]] void fail_init(ShmHeader *header, EngineError code,
                            const std::string &msg) {
  if (header != nullptr) {
    header->last_error_code.store(static_cast<std::uint32_t>(code),
                                  std::memory_order_release);
    header->state.store(static_cast<std::uint32_t>(EngineState::CORRUPTED),
                        std::memory_order_release);
  }
  throw std::runtime_error(msg);
}

} // namespace

// ============================================================================
// Sizing
// ============================================================================

std::size_t shm_required_size() noexcept {
  std::size_t offset = align_up(sizeof(ShmHeader), CACHE_LINE_SIZE);
  offset = align_up(offset + sizeof(CmdRing), CACHE_LINE_SIZE);
  offset = align_up(offset + sizeof(EvtRing), CACHE_LINE_SIZE);
  offset = align_up(offset + sizeof(EngineStats), CACHE_LINE_SIZE);
  offset = align_up(offset + log::span_ring_bytes(SPAN_RING_CAPACITY),
                    CACHE_LINE_SIZE);
  offset = align_up(offset + SCHEMA_HEAP_SIZE, CACHE_LINE_SIZE);
  offset = align_up(offset + (JOURNAL_CAPACITY * sizeof(BridgeMessage)),
                    CACHE_LINE_SIZE);
  // Everything after this point is the data heap; require room for its
  // bookkeeping plus at least a few megabytes of payload.
  return offset + heap_metadata_size() + (4u * 1024u * 1024u);
}

// ============================================================================
// Region construction
// ============================================================================

ShmSession init_shm(bool create, const char *region_name,
                    std::size_t region_size, bool force_format) {
  Platform::ShmRegion region =
      create ? Platform::shm_create(region_name, region_size)
             : Platform::shm_open(region_name, region_size);

  void *base_ptr = region.data();
  if (base_ptr == nullptr) {
    throw std::runtime_error("Failed to map the shared memory base address.");
  }

  ShmLayout layout{};
  layout.base = static_cast<std::byte *>(base_ptr);
  auto *header = reinterpret_cast<ShmHeader *>(layout.base);

  // Formatting is idempotent.
  bool already_formatted = false;
  if (create && !force_format) {
    already_formatted =
        header->magic.load(std::memory_order_acquire) == SHM_MAGIC &&
        header->version.load(std::memory_order_acquire) == SHM_VERSION &&
        header->total_size == region_size;
  }

  if (create && !already_formatted) {
    const std::size_t needed = shm_required_size();
    if (needed > region_size) {
      throw std::runtime_error(
          "region_size is " + std::to_string(region_size) + " but the layout needs " +
          std::to_string(needed) +
          " bytes. Raise region_size in common_config.h.");
    }

    // Only the metadata is cleared, not the whole region.
    const std::size_t metadata_bytes =
        std::min<std::size_t>(shm_required_size(), region_size);
    std::memset(layout.base, 0, metadata_bytes);
    ::new (static_cast<void *>(header)) ShmHeader();

    header->total_size = region_size;
    header->magic.store(SHM_MAGIC, std::memory_order_relaxed);
    header->version.store(SHM_VERSION, std::memory_order_relaxed);
    header->abi_version.store(SHM_VERSION, std::memory_order_relaxed);
    header->proto_version.store(PROTO_VERSION, std::memory_order_relaxed);
    header->state.store(static_cast<std::uint32_t>(EngineState::INITIALIZING),
                        std::memory_order_relaxed);

    std::size_t offset = align_up(sizeof(ShmHeader), CACHE_LINE_SIZE);
    auto reserve = [&](std::size_t bytes, const char *what) -> std::size_t {
      if (offset + bytes > region_size) {
        fail_init(header, EngineError::REGION_TOO_SMALL,
                  std::string("Shared memory exhausted while reserving ") +
                      what);
      }
      const std::size_t start = offset;
      offset = align_up(offset + bytes, CACHE_LINE_SIZE);
      return start;
    };

    // --- command ring ---
    const std::size_t cmd_off = reserve(sizeof(CmdRing), "the command ring");
    header->off_cmd_ring = cmd_off;
    header->size_cmd_ring = sizeof(CmdRing);
    header->cmd_ring_capacity = CMD_RING_CAPACITY; // slot count, not 2^n
    header->cmd_ring_slot_size = sizeof(BridgeMessage);
    layout.cmd_ring =
        ::new (static_cast<void *>(layout.base + cmd_off)) CmdRing();
    layout.cmd_ring->init();

    // --- event ring ---
    const std::size_t evt_off = reserve(sizeof(EvtRing), "the event ring");
    header->off_evt_ring = evt_off;
    header->size_evt_ring = sizeof(EvtRing);
    header->evt_ring_capacity = EVT_RING_CAPACITY;
    header->evt_ring_slot_size = sizeof(BridgeMessage);
    layout.evt_ring =
        ::new (static_cast<void *>(layout.base + evt_off)) EvtRing();
    layout.evt_ring->init();

    // --- engine statistics ---
    
    const std::size_t stats_off = reserve(sizeof(EngineStats), "engine stats");
    header->off_engine_stats = stats_off;
    header->size_engine_stats = sizeof(EngineStats);
    layout.stats =
        ::new (static_cast<void *>(layout.base + stats_off)) EngineStats();

    // --- telemetry span ring ---
    const std::size_t span_bytes = log::span_ring_bytes(SPAN_RING_CAPACITY);
    const std::size_t span_off = reserve(span_bytes, "the span ring");
    header->off_span_ring = span_off;
    header->size_span_ring = span_bytes;
    header->span_ring_capacity = SPAN_RING_CAPACITY;
    layout.span_ring = layout.base + span_off;
    {
      log::SpanRing view = log::span_ring_view(layout.span_ring, span_bytes,
                                               SPAN_RING_CAPACITY);
      log::span_ring_init(view, SPAN_RING_CAPACITY);
    }

    // --- schema heap ---
    const std::size_t schema_off = reserve(SCHEMA_HEAP_SIZE, "the schema heap");
    header->off_schema_heap = schema_off;
    header->size_schema_heap = SCHEMA_HEAP_SIZE;
    layout.schema_heap = layout.base + schema_off;

    // --- transaction journal ---
    const std::size_t tx_bytes = JOURNAL_CAPACITY * sizeof(BridgeMessage);
    const std::size_t tx_off = reserve(tx_bytes, "the transaction journal");
    header->off_tx_log = tx_off;
    header->size_tx_log = tx_bytes;
    header->journal_capacity = JOURNAL_CAPACITY;
    layout.tx_log = layout.base + tx_off;

    // --- data heap: everything that is left ---
    header->off_data_heap = offset;
    header->size_data_heap = region_size - offset;
    layout.data_heap = layout.base + offset;

    auto *heap_hdr =
        ::new (static_cast<void *>(layout.data_heap)) ShmHeapHeader();
    auto *heap_root =
        ::new (static_cast<void *>(layout.data_heap + heap_root_offset()))
            ShmHeapRoot();

    const std::size_t heap_meta = heap_metadata_size();
    if (header->size_data_heap <= heap_meta) {
      fail_init(header, EngineError::REGION_TOO_SMALL,
                "The data heap partition is smaller than its own metadata.");
    }

    heap_hdr->total_capacity.store(header->size_data_heap - heap_meta,
                                   std::memory_order_relaxed);
    heap_hdr->allocated_bytes.store(0, std::memory_order_relaxed);
    heap_hdr->epoch.store(1, std::memory_order_relaxed);
    heap_hdr->epoch_barrier.store(1, std::memory_order_relaxed);
    heap_hdr->active_allocations.store(0, std::memory_order_relaxed);

    for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
      heap_hdr->quota_by_kind[i].store(DEFAULT_KIND_QUOTA,
                                       std::memory_order_relaxed);
    }
    for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
      heap_hdr->quota_by_producer[i].store(DEFAULT_PRODUCER_QUOTA,
                                           std::memory_order_relaxed);
    }
    (void)heap_root; // value-initialized above

    header->state.store(static_cast<std::uint32_t>(EngineState::READY),
                        std::memory_order_release);
  } else {
    const auto start_time = std::chrono::steady_clock::now();
    constexpr auto kInitTimeout = std::chrono::seconds(5);

    std::uint32_t state = header->state.load(std::memory_order_acquire);
    while (state == static_cast<std::uint32_t>(EngineState::UNINITIALIZED) ||
           state == static_cast<std::uint32_t>(EngineState::INITIALIZING)) {
      if (std::chrono::steady_clock::now() - start_time > kInitTimeout) {
        fail_init(header, EngineError::INIT_TIMEOUT,
                  "Timed out waiting for the shared memory creator. If the "
                  "segment is stale, remove " + std::string(region_name) + ".");
      }
      detail::cpu_pause();
      std::this_thread::yield();
      state = header->state.load(std::memory_order_acquire);
    }

    if (state == static_cast<std::uint32_t>(EngineState::CORRUPTED)) {
      throw std::runtime_error(
          "Shared memory layout is marked CORRUPTED; the creator failed.");
    }

    const std::uint32_t magic = header->magic.load(std::memory_order_acquire);
    const std::uint32_t version = header->version.load(std::memory_order_acquire);
    if (magic != SHM_MAGIC || version != SHM_VERSION) {
      fail_init(header, EngineError::ABI_MISMATCH,
                "Shared memory ABI mismatch: magic=0x" +
                    std::to_string(magic) + " version=" +
                    std::to_string(version) + ", expected version " +
                    std::to_string(SHM_VERSION) + ".");
    }

    // Rebuild the view strictly from the recorded offsets.
    layout.cmd_ring =
        reinterpret_cast<CmdRing *>(layout.base + header->off_cmd_ring);
    layout.evt_ring =
        reinterpret_cast<EvtRing *>(layout.base + header->off_evt_ring);
    layout.stats =
        reinterpret_cast<EngineStats *>(layout.base + header->off_engine_stats);
    layout.span_ring = layout.base + header->off_span_ring;
    layout.schema_heap = layout.base + header->off_schema_heap;
    layout.tx_log = layout.base + header->off_tx_log;
    layout.data_heap = layout.base + header->off_data_heap;

    if (!layout.cmd_ring->is_initialized() ||
        !layout.evt_ring->is_initialized()) {
      fail_init(header, EngineError::CORRUPTED_LAYOUT,
                "Shared memory rings are present but were never initialized.");
    }
  }

  layout.header = header;
  return ShmSession(std::move(region), layout);
}

// ============================================================================
// Heap allocation
// ============================================================================

std::uint64_t shm_heap_alloc(ShmLayout &layout, std::size_t size,
                             std::uint16_t kind, std::uint16_t producer_id,
                             std::uint16_t encoding) noexcept {
  if (layout.data_heap == nullptr || layout.header == nullptr || size == 0) {
    return 0;
  }

  // A size close to SIZE_MAX would wrap in the align_up below and slip past the
  // capacity check as a small number. payload_size is a uint32 besides.
  if (size > 0xFFFFFFFFULL) {
    layout.header->last_error_code.store(
        static_cast<std::uint32_t>(EngineError::OOM_HEAP),
        std::memory_order_relaxed);
    return 0;
  }

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);

  const std::size_t total_alloc_size =
      align_up(sizeof(ShmChunkHeader) + size, CACHE_LINE_SIZE);

  bool kind_charged = false;
  if (kind < KIND_BUCKETS) {
    const std::uint64_t quota =
        heap_hdr->quota_by_kind[kind].load(std::memory_order_relaxed);
    std::uint64_t current =
        heap_hdr->allocations_by_kind[kind].load(std::memory_order_relaxed);
    for (;;) {
      if (quota > 0 && current >= quota) {
        layout.header->last_error_code.store(
            static_cast<std::uint32_t>(EngineError::OOM_HEAP),
            std::memory_order_relaxed);
        return 0;
      }
      if (heap_hdr->allocations_by_kind[kind].compare_exchange_weak(
              current, current + 1, std::memory_order_relaxed,
              std::memory_order_relaxed)) {
        kind_charged = true;
        break;
      }
    }
  }

  bool producer_charged = false;
  if (producer_id < PRODUCER_BUCKETS) {
    const std::uint64_t quota =
        heap_hdr->quota_by_producer[producer_id].load(std::memory_order_relaxed);
    std::uint64_t current = heap_hdr->allocations_by_producer[producer_id].load(
        std::memory_order_relaxed);
    for (;;) {
      if (quota > 0 && current >= quota) {
        if (kind_charged) {
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
        producer_charged = true;
        break;
      }
    }
  }

  // Bump allocation.
  const std::uint64_t capacity =
      heap_hdr->total_capacity.load(std::memory_order_relaxed);
  std::uint64_t offset =
      heap_hdr->allocated_bytes.load(std::memory_order_relaxed);
  for (;;) {
    if (offset + total_alloc_size > capacity) {
      if (kind_charged) {
        heap_hdr->allocations_by_kind[kind].fetch_sub(
            1, std::memory_order_relaxed);
      }
      if (producer_charged) {
        heap_hdr->allocations_by_producer[producer_id].fetch_sub(
            1, std::memory_order_relaxed);
      }
      layout.header->last_error_code.store(
          static_cast<std::uint32_t>(EngineError::OOM_HEAP),
          std::memory_order_relaxed);
      return 0;
    }
    if (heap_hdr->allocated_bytes.compare_exchange_weak(
            offset, offset + total_alloc_size, std::memory_order_relaxed,
            std::memory_order_relaxed)) {
      break;
    }
  }

  const std::uint64_t chunk_offset =
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

  std::atomic_thread_fence(std::memory_order_release);

  // Splice into the global list.
  const std::uint64_t prev_last =
      heap_root->off_last_chunk.exchange(chunk_offset, std::memory_order_acq_rel);
  if (prev_last == 0) {
    heap_root->off_first_chunk.store(chunk_offset, std::memory_order_release);
  } else {
    auto *last = reinterpret_cast<ShmChunkHeader *>(layout.base + prev_last);
    last->next_offset.store(chunk_offset, std::memory_order_release);
  }

  if (kind < KIND_BUCKETS) {
    const std::uint64_t prev = heap_root->off_last_by_kind[kind].exchange(
        chunk_offset, std::memory_order_acq_rel);
    if (prev == 0) {
      heap_root->off_first_by_kind[kind].store(chunk_offset,
                                               std::memory_order_release);
    } else {
      auto *last = reinterpret_cast<ShmChunkHeader *>(layout.base + prev);
      last->next_kind_offset.store(chunk_offset, std::memory_order_release);
    }
    heap_root->off_prefetch_head_by_kind[kind].store(chunk_offset,
                                                     std::memory_order_release);
  }

  if (producer_id < PRODUCER_BUCKETS) {
    const std::uint64_t prev = heap_root->off_last_by_producer[producer_id]
                                   .exchange(chunk_offset,
                                             std::memory_order_acq_rel);
    if (prev == 0) {
      heap_root->off_first_by_producer[producer_id].store(
          chunk_offset, std::memory_order_release);
    } else {
      auto *last = reinterpret_cast<ShmChunkHeader *>(layout.base + prev);
      last->next_producer_offset.store(chunk_offset, std::memory_order_release);
    }
    heap_root->off_prefetch_head_by_producer[producer_id].store(
        chunk_offset, std::memory_order_release);
  }

  // Skip-list levels for coarse scanning.
  const std::uint32_t seq =
      heap_root->n_chunks.fetch_add(1, std::memory_order_relaxed) + 1;
  if ((seq & 1u) == 0) {
    const std::uint64_t prev =
        heap_root->off_last_lvl2.exchange(chunk_offset, std::memory_order_acq_rel);
    if (prev != 0) {
      reinterpret_cast<ShmChunkHeader *>(layout.base + prev)
          ->next_offset_lvl2.store(chunk_offset, std::memory_order_release);
    }
  }
  if ((seq & 3u) == 0) {
    const std::uint64_t prev =
        heap_root->off_last_lvl4.exchange(chunk_offset, std::memory_order_acq_rel);
    if (prev != 0) {
      reinterpret_cast<ShmChunkHeader *>(layout.base + prev)
          ->next_offset_lvl4.store(chunk_offset, std::memory_order_release);
    }
  }
  if ((seq & 7u) == 0) {
    const std::uint64_t prev =
        heap_root->off_last_lvl8.exchange(chunk_offset, std::memory_order_acq_rel);
    if (prev != 0) {
      reinterpret_cast<ShmChunkHeader *>(layout.base + prev)
          ->next_offset_lvl8.store(chunk_offset, std::memory_order_release);
    }
  }

  if (kind == 1) {
    heap_root->off_prefetch_head.store(chunk_offset, std::memory_order_release);
  }

  heap_hdr->active_allocations.fetch_add(1, std::memory_order_relaxed);

  if (auto *stats = shm_engine_stats(layout); stats != nullptr) {
    const std::uint64_t used =
        heap_hdr->allocated_bytes.load(std::memory_order_relaxed);
    stats->heap_usage_bytes.store(used, std::memory_order_relaxed);
    const std::uint64_t reclaimable =
        heap_hdr->reclaimable_bytes.load(std::memory_order_relaxed);
    const std::uint64_t score = (used > 0) ? ((reclaimable * 100) / used) : 0;
    stats->heap_fragmentation_score.store(score, std::memory_order_relaxed);
    layout.header->heap_fragmentation_score.store(
        static_cast<std::uint32_t>(score), std::memory_order_relaxed);
  }

  return chunk_offset + sizeof(ShmChunkHeader);
}

void shm_chunk_commit(ShmLayout &layout,
                      std::uint64_t payload_offset) noexcept {
  auto *chunk = chunk_from_payload(layout, payload_offset);
  if (chunk == nullptr) {
    return;
  }
  chunk->checksum = calculate_crc32c(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);
  chunk->flags.store(CHUNK_COMMITTED, std::memory_order_release);
}

void shm_chunk_mark_dirty(ShmLayout &layout,
                          std::uint64_t payload_offset) noexcept {
  if (auto *chunk = chunk_from_payload(layout, payload_offset)) {
    chunk->flags.fetch_or(CHUNK_DIRTY, std::memory_order_release);
  }
}

void shm_chunk_mark_tombstone(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept {
  if (auto *chunk = chunk_from_payload(layout, payload_offset)) {
    chunk->flags.fetch_or(CHUNK_TOMBSTONE, std::memory_order_release);
  }
}

void shm_chunk_mark_committed(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept {
  shm_chunk_commit(layout, payload_offset);
}

std::uint64_t shm_heap_alloc_data(ShmLayout &layout, std::size_t size) noexcept {
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
  if (layout.data_heap == nullptr) {
    return;
  }

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);

  // Bumping the epoch invalidates every existing chunk in one step
  const std::uint64_t new_epoch =
      heap_hdr->epoch.fetch_add(1, std::memory_order_acq_rel) + 1;
  heap_hdr->epoch_barrier.store(new_epoch, std::memory_order_release);
  heap_hdr->allocated_bytes.store(0, std::memory_order_release);
  heap_hdr->reclaimable_bytes.store(0, std::memory_order_release);
  heap_hdr->active_allocations.store(0, std::memory_order_release);
  heap_hdr->snapshot_min_epoch.store(0, std::memory_order_relaxed);
  heap_hdr->snapshot_max_epoch.store(0, std::memory_order_relaxed);

  for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
    heap_hdr->allocations_by_kind[i].store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_min_epoch_by_kind[i].store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_max_epoch_by_kind[i].store(0, std::memory_order_relaxed);
    heap_root->off_first_by_kind[i].store(0, std::memory_order_release);
    heap_root->off_last_by_kind[i].store(0, std::memory_order_release);
    heap_root->off_prefetch_head_by_kind[i].store(0, std::memory_order_release);
    heap_root->hotness_by_kind[i].store(0, std::memory_order_relaxed);
  }
  for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
    heap_hdr->allocations_by_producer[i].store(0, std::memory_order_relaxed);
    heap_hdr->snapshot_min_epoch_by_producer[i].store(0,
                                                      std::memory_order_relaxed);
    heap_hdr->snapshot_max_epoch_by_producer[i].store(0,
                                                      std::memory_order_relaxed);
    heap_root->off_first_by_producer[i].store(0, std::memory_order_release);
    heap_root->off_last_by_producer[i].store(0, std::memory_order_release);
    heap_root->off_prefetch_head_by_producer[i].store(0,
                                                      std::memory_order_release);
    heap_root->hotness_by_producer[i].store(0, std::memory_order_relaxed);
  }

  heap_root->n_chunks.store(0, std::memory_order_release);
  heap_root->off_first_chunk.store(0, std::memory_order_release);
  heap_root->off_last_chunk.store(0, std::memory_order_release);
  heap_root->off_prefetch_head.store(0, std::memory_order_release);
  heap_root->off_last_lvl2.store(0, std::memory_order_release);
  heap_root->off_last_lvl4.store(0, std::memory_order_release);
  heap_root->off_last_lvl8.store(0, std::memory_order_release);

  if (auto *stats = shm_engine_stats(layout); stats != nullptr) {
    stats->heap_usage_bytes.store(0, std::memory_order_relaxed);
    stats->heap_fragmentation_score.store(0, std::memory_order_relaxed);
  }
}

void shm_heap_soft_barrier(ShmLayout &layout) noexcept {
  if (layout.data_heap == nullptr) {
    return;
  }
  auto *heap_hdr = get_heap_header(layout);
  const std::uint64_t current = heap_hdr->epoch.load(std::memory_order_acquire);
  std::uint64_t barrier = heap_hdr->epoch_barrier.load(std::memory_order_relaxed);
  while (barrier < current) {
    if (heap_hdr->epoch_barrier.compare_exchange_weak(
            barrier, current, std::memory_order_release,
            std::memory_order_relaxed)) {
      break;
    }
  }
}

std::uint64_t shm_heap_current_epoch(const ShmLayout &layout) noexcept {
  if (layout.data_heap == nullptr) {
    return 0;
  }
  return get_heap_header(layout)->epoch.load(std::memory_order_acquire);
}

// ============================================================================
// Chunk validity
// ============================================================================

namespace {

/// Shared validity test, without the hotness side effects.
[[nodiscard]] bool chunk_is_readable(const ShmLayout &layout,
                                     std::uint64_t payload_offset,
                                     const ShmChunkHeader *&out) noexcept {
  out = nullptr;
  if (layout.base == nullptr || layout.data_heap == nullptr ||
      layout.header == nullptr) {
    return false;
  }
  if (payload_offset < first_payload_offset(layout) ||
      payload_offset >= layout.header->total_size) {
    return false;
  }

  const auto *chunk = chunk_from_payload(layout, payload_offset);
  if (chunk == nullptr || chunk->magic != CHUNK_MAGIC) {
    return false;
  }

  const std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);
  if ((flags & CHUNK_COMMITTED) == 0 || (flags & CHUNK_TOMBSTONE) != 0) {
    return false;
  }

  const auto *heap_hdr = get_heap_header(layout);
  const std::uint64_t current = heap_hdr->epoch.load(std::memory_order_acquire);
  const std::uint64_t barrier =
      heap_hdr->epoch_barrier.load(std::memory_order_acquire);

  if (chunk->epoch > current || chunk->epoch < barrier) {
    return false;
  }

  const std::uint64_t gmin =
      heap_hdr->snapshot_min_epoch.load(std::memory_order_relaxed);
  const std::uint64_t gmax =
      heap_hdr->snapshot_max_epoch.load(std::memory_order_relaxed);
  if (gmax > 0 && (chunk->epoch < gmin || chunk->epoch > gmax)) {
    return false;
  }

  if (chunk->kind < KIND_BUCKETS) {
    const std::uint64_t kmin = heap_hdr->snapshot_min_epoch_by_kind[chunk->kind]
                                   .load(std::memory_order_relaxed);
    const std::uint64_t kmax = heap_hdr->snapshot_max_epoch_by_kind[chunk->kind]
                                   .load(std::memory_order_relaxed);
    if (kmax > 0 && (chunk->epoch < kmin || chunk->epoch > kmax)) {
      return false;
    }
  }

  if (chunk->producer_id < PRODUCER_BUCKETS) {
    const std::uint64_t pmin =
        heap_hdr->snapshot_min_epoch_by_producer[chunk->producer_id].load(
            std::memory_order_relaxed);
    const std::uint64_t pmax =
        heap_hdr->snapshot_max_epoch_by_producer[chunk->producer_id].load(
            std::memory_order_relaxed);
    if (pmax > 0 && (chunk->epoch < pmin || chunk->epoch > pmax)) {
      return false;
    }
  }

  out = chunk;
  return true;
}

} // namespace

bool shm_chunk_is_valid(const ShmLayout &layout,
                        std::uint64_t payload_offset) noexcept {
  const ShmChunkHeader *chunk = nullptr;
  if (!chunk_is_readable(layout, payload_offset, chunk)) {
    return false;
  }

  auto *root = const_cast<ShmHeapRoot *>(get_heap_root(layout));
  if (chunk->kind < KIND_BUCKETS) {
    root->hotness_by_kind[chunk->kind].fetch_add(1, std::memory_order_relaxed);
  }
  if (chunk->producer_id < PRODUCER_BUCKETS) {
    root->hotness_by_producer[chunk->producer_id].fetch_add(
        1, std::memory_order_relaxed);
  }
  return true;
}

bool shm_chunk_is_visible_for_reader(const ShmLayout &layout,
                                     std::uint64_t payload_offset,
                                     std::uint64_t reader_epoch) noexcept {
  const ShmChunkHeader *chunk = nullptr;
  if (!chunk_is_readable(layout, payload_offset, chunk)) {
    return false;
  }
  return chunk->epoch <= reader_epoch;
}

bool shm_chunk_is_visible_for_analytics(const ShmLayout &layout,
                                        std::uint64_t payload_offset) noexcept {
  const ShmChunkHeader *chunk = nullptr;
  return chunk_is_readable(layout, payload_offset, chunk);
}

bool shm_chunk_is_visible_for_debug(const ShmLayout &layout,
                                    std::uint64_t payload_offset) noexcept {
  if (layout.base == nullptr || layout.header == nullptr) {
    return false;
  }
  if (payload_offset < first_payload_offset(layout) ||
      payload_offset >= layout.header->total_size) {
    return false;
  }
  const auto *chunk = chunk_from_payload(layout, payload_offset);
  return chunk != nullptr && chunk->magic == CHUNK_MAGIC;
}

const Chunk *shm_chunk_get_header(const ShmLayout &layout,
                                  std::uint64_t payload_offset) noexcept {
  const ShmChunkHeader *chunk = nullptr;
  return chunk_is_readable(layout, payload_offset, chunk) ? chunk : nullptr;
}

bool shm_chunk_is_consistent(const ShmLayout &layout,
                             std::uint64_t payload_offset) noexcept {
  const ShmChunkHeader *chunk = nullptr;
  if (!chunk_is_readable(layout, payload_offset, chunk)) {
    return false;
  }
  const std::uint32_t crc = calculate_crc32c(
      reinterpret_cast<const std::uint8_t *>(layout.base + payload_offset),
      chunk->payload_size);
  return crc == chunk->checksum;
}

bool shm_chunk_verify_crc(const ShmLayout &layout,
                          std::uint64_t payload_offset) noexcept {
  const ShmChunkHeader *chunk = nullptr;
  if (!chunk_is_readable(layout, payload_offset, chunk)) {
    return false;
  }
  const std::uint32_t crc = calculate_crc32c(
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
  return false;
}

std::uint64_t shm_crc_failures_total() noexcept {
  return detail::g_crc_failures_total.load(std::memory_order_relaxed);
}

std::uint64_t shm_crc_failures_by_kind(std::uint16_t kind) noexcept {
  if (kind >= KIND_BUCKETS) {
    return 0;
  }
  return detail::g_crc_failures_by_kind[kind].load(std::memory_order_relaxed);
}

// ============================================================================
// Traversal
// ============================================================================

namespace {

std::uint64_t follow(const ShmLayout &layout, std::uint64_t payload_offset,
                     std::atomic<std::uint64_t> Chunk::*link) noexcept {
  const auto *chunk = shm_chunk_get_header(layout, payload_offset);
  if (chunk == nullptr) {
    return 0;
  }
  const std::uint64_t next = (chunk->*link).load(std::memory_order_acquire);
  if (next == 0) {
    return 0;
  }
  const std::uint64_t payload = next + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

} // namespace

std::uint64_t shm_chunk_next_payload(const ShmLayout &layout,
                                     std::uint64_t offset) noexcept {
  return follow(layout, offset, &Chunk::next_offset);
}

std::uint64_t shm_chunk_next_payload_lvl2(const ShmLayout &layout,
                                          std::uint64_t offset) noexcept {
  return follow(layout, offset, &Chunk::next_offset_lvl2);
}

std::uint64_t shm_chunk_next_payload_lvl4(const ShmLayout &layout,
                                          std::uint64_t offset) noexcept {
  return follow(layout, offset, &Chunk::next_offset_lvl4);
}

std::uint64_t shm_chunk_next_payload_lvl8(const ShmLayout &layout,
                                          std::uint64_t offset) noexcept {
  return follow(layout, offset, &Chunk::next_offset_lvl8);
}

std::uint64_t shm_chunk_scan(const ShmLayout &layout, std::uint64_t start,
                             std::uint32_t step_log2) noexcept {
  switch (step_log2) {
  case 0:
    return shm_chunk_next_payload(layout, start);
  case 1:
    return shm_chunk_next_payload_lvl2(layout, start);
  case 2:
    return shm_chunk_next_payload_lvl4(layout, start);
  case 3:
    return shm_chunk_next_payload_lvl8(layout, start);
  default:
    return 0;
  }
}

std::uint64_t shm_heap_first_by_kind(const ShmLayout &layout,
                                     std::uint16_t kind) noexcept {
  if (layout.data_heap == nullptr || kind >= KIND_BUCKETS) {
    return 0;
  }
  const std::uint64_t off = get_heap_root(layout)->off_first_by_kind[kind].load(
      std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }
  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_heap_first_by_producer(const ShmLayout &layout,
                                         std::uint16_t producer_id) noexcept {
  if (layout.data_heap == nullptr || producer_id >= PRODUCER_BUCKETS) {
    return 0;
  }
  const std::uint64_t off =
      get_heap_root(layout)->off_first_by_producer[producer_id].load(
          std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }
  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_iter_begin_by_kind(const ShmLayout &layout,
                                     std::uint16_t kind) noexcept {
  return shm_heap_first_by_kind(layout, kind);
}

std::uint64_t shm_iter_next_by_kind(const ShmLayout &layout,
                                    std::uint64_t offset) noexcept {
  return follow(layout, offset, &Chunk::next_kind_offset);
}

std::uint64_t shm_iter_begin_by_producer(const ShmLayout &layout,
                                         std::uint16_t producer_id) noexcept {
  return shm_heap_first_by_producer(layout, producer_id);
}

std::uint64_t shm_iter_next_by_producer(const ShmLayout &layout,
                                        std::uint64_t offset) noexcept {
  return follow(layout, offset, &Chunk::next_producer_offset);
}

// ============================================================================
// Reader epochs and cluster membership
// ============================================================================

void shm_register_runtime(ShmLayout &layout, std::uint32_t id) noexcept {
  if (layout.header == nullptr || id >= MAX_RUNTIMES) {
    return;
  }
  layout.header->cluster_runtime_ids[id].store(1, std::memory_order_release);
}

void shm_unregister_runtime(ShmLayout &layout, std::uint32_t id) noexcept {
  if (layout.header == nullptr || id >= MAX_RUNTIMES) {
    return;
  }
  layout.header->cluster_runtime_ids[id].store(0, std::memory_order_release);
  layout.header->reader_epochs[id].store(0, std::memory_order_release);
}

bool shm_is_runtime_active(const ShmLayout &layout, std::uint32_t id) noexcept {
  if (layout.header == nullptr || id >= MAX_RUNTIMES) {
    return false;
  }
  return layout.header->cluster_runtime_ids[id].load(
             std::memory_order_acquire) != 0;
}

void shm_reader_pin(ShmLayout &layout, std::uint32_t runtime_id) noexcept {
  if (layout.header == nullptr || layout.data_heap == nullptr ||
      runtime_id >= MAX_RUNTIMES) {
    return;
  }
  const std::uint64_t epoch =
      get_heap_header(layout)->epoch.load(std::memory_order_acquire);
  layout.header->reader_epochs[runtime_id].store(epoch,
                                                 std::memory_order_release);
}

void shm_reader_unpin(ShmLayout &layout, std::uint32_t runtime_id) noexcept {
  if (layout.header == nullptr || runtime_id >= MAX_RUNTIMES) {
    return;
  }
  layout.header->reader_epochs[runtime_id].store(0, std::memory_order_release);
}

bool shm_has_active_readers(const ShmLayout &layout) noexcept {
  if (layout.header == nullptr) {
    return false;
  }
  
  for (std::size_t i = 0; i < MAX_RUNTIMES; ++i) {
    if (layout.header->reader_epochs[i].load(std::memory_order_acquire) != 0) {
      return true;
    }
  }
  return false;
}

std::uint64_t
shm_get_min_active_reader_epoch(const ShmLayout &layout) noexcept {
  if (layout.header == nullptr || layout.data_heap == nullptr) {
    return 0;
  }

  const std::uint64_t current =
      get_heap_header(layout)->epoch.load(std::memory_order_acquire);

  // Callers reclaim a chunk when its epoch is strictly older than this
  // watermark. With no reader pinned nothing can still be looking at the
  // current epoch, so the watermark sits one past it; returning `current`
  // instead made the test `chunk->epoch < safe_epoch` unsatisfiable and no
  // chunk was ever reclaimed.
  std::uint64_t min_epoch = current + 1;

  for (std::size_t i = 0; i < MAX_RUNTIMES; ++i) {
    const std::uint64_t pinned =
        layout.header->reader_epochs[i].load(std::memory_order_acquire);
    if (pinned > 0 && pinned < min_epoch) {
      min_epoch = pinned;
    }
  }
  return min_epoch;
}

// ============================================================================
// Prefetch
// ============================================================================

#if defined(__GNUC__) || defined(__clang__)
#define SPHERE_PREFETCH(addr, rw, locality) __builtin_prefetch((addr), (rw), (locality))
#elif defined(_MSC_VER) && (defined(_M_X64) || defined(_M_IX86))
#define SPHERE_PREFETCH(addr, rw, locality)                                    \
  _mm_prefetch(reinterpret_cast<const char *>(addr), _MM_HINT_T0)
#else
#define SPHERE_PREFETCH(addr, rw, locality) ((void)(addr))
#endif

std::uint64_t shm_heap_prefetch_head(const ShmLayout &layout) noexcept {
  if (layout.data_heap == nullptr) {
    return 0;
  }
  const std::uint64_t off =
      get_heap_root(layout)->off_prefetch_head.load(std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }
  SPHERE_PREFETCH(layout.base + off, 0, 3);
  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_heap_prefetch_head_by_kind(const ShmLayout &layout,
                                             std::uint16_t kind) noexcept {
  if (layout.data_heap == nullptr || kind >= KIND_BUCKETS) {
    return 0;
  }
  const std::uint64_t off =
      get_heap_root(layout)->off_prefetch_head_by_kind[kind].load(
          std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }
  SPHERE_PREFETCH(layout.base + off, 0, 3);
  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t
shm_heap_prefetch_head_by_producer(const ShmLayout &layout,
                                   std::uint16_t producer_id) noexcept {
  if (layout.data_heap == nullptr || producer_id >= PRODUCER_BUCKETS) {
    return 0;
  }
  const std::uint64_t off =
      get_heap_root(layout)->off_prefetch_head_by_producer[producer_id].load(
          std::memory_order_acquire);
  if (off == 0) {
    return 0;
  }
  SPHERE_PREFETCH(layout.base + off, 0, 3);
  const std::uint64_t payload = off + sizeof(ShmChunkHeader);
  return shm_chunk_is_valid(layout, payload) ? payload : 0;
}

std::uint64_t shm_prefetch_range(const ShmLayout &layout, std::uint64_t start,
                                 std::uint32_t step_log2,
                                 std::uint32_t depth) noexcept {
  std::uint64_t current = start;
  for (std::uint32_t i = 0; i < depth && current != 0; ++i) {
    if (current < sizeof(ShmChunkHeader)) {
      break;
    }
    SPHERE_PREFETCH(layout.base + current - sizeof(ShmChunkHeader), 0, 2);
    SPHERE_PREFETCH(layout.base + current, 0, 3);
    current = shm_chunk_scan(layout, current, step_log2);
  }
  return current;
}

void shm_heap_update_prefetch(ShmLayout &layout, std::uint16_t kind) noexcept {
  if (layout.base == nullptr || layout.data_heap == nullptr ||
      kind >= KIND_BUCKETS) {
    return;
  }
  auto *root = get_heap_root(layout);
  std::uint64_t off =
      root->off_first_by_kind[kind].load(std::memory_order_acquire);
  if (off == 0) {
    return;
  }

  std::uint64_t best = off;
  while (off != 0) {
    const auto *chunk =
        reinterpret_cast<const ShmChunkHeader *>(layout.base + off);
    const std::uint16_t flags = chunk->flags.load(std::memory_order_relaxed);
    if ((flags & CHUNK_COMMITTED) != 0 && (flags & CHUNK_TOMBSTONE) == 0) {
      best = off;
      SPHERE_PREFETCH(layout.base + off + sizeof(ShmChunkHeader), 0, 1);
    }
    off = chunk->next_kind_offset.load(std::memory_order_acquire);
  }
  root->off_prefetch_head_by_kind[kind].store(best, std::memory_order_release);
}

// ============================================================================
// Quotas, snapshots, debug
// ============================================================================

void shm_heap_collect_debug_stats(const ShmLayout &layout,
                                  ShmHeapDebugStats &out) noexcept {
  if (layout.data_heap == nullptr) {
    return;
  }
  std::uint64_t off =
      get_heap_root(layout)->off_first_chunk.load(std::memory_order_acquire);
  while (off != 0) {
    const auto *chunk =
        reinterpret_cast<const ShmChunkHeader *>(layout.base + off);
    const std::uint16_t flags = chunk->flags.load(std::memory_order_relaxed);

    if ((flags & CHUNK_COMMITTED) != 0) {
      ++out.committed;
    }
    if ((flags & CHUNK_DIRTY) != 0) {
      ++out.dirty;
    }
    if ((flags & CHUNK_TOMBSTONE) != 0) {
      ++out.tombstone;
    }
    if (flags == CHUNK_INCOMPLETE) {
      ++out.incomplete;
    }
    if (chunk->kind < KIND_BUCKETS) {
      ++out.by_kind[chunk->kind];
    }
    if (chunk->producer_id < PRODUCER_BUCKETS) {
      ++out.by_producer[chunk->producer_id];
    }
    off = chunk->next_offset.load(std::memory_order_acquire);
  }
}

void shm_heap_set_quota_kind(ShmLayout &layout, std::uint16_t kind,
                             std::uint64_t quota) noexcept {
  if (layout.data_heap == nullptr || kind >= KIND_BUCKETS) {
    return;
  }
  get_heap_header(layout)->quota_by_kind[kind].store(quota,
                                                     std::memory_order_relaxed);
}

void shm_heap_set_quota_producer(ShmLayout &layout, std::uint16_t producer_id,
                                 std::uint64_t quota) noexcept {
  if (layout.data_heap == nullptr || producer_id >= PRODUCER_BUCKETS) {
    return;
  }
  get_heap_header(layout)->quota_by_producer[producer_id].store(
      quota, std::memory_order_relaxed);
}

void shm_heap_set_snapshot_epoch_window(ShmLayout &layout,
                                        std::uint64_t min_epoch,
                                        std::uint64_t max_epoch) noexcept {
  if (layout.data_heap == nullptr) {
    return;
  }
  auto *h = get_heap_header(layout);
  h->snapshot_min_epoch.store(min_epoch, std::memory_order_relaxed);
  h->snapshot_max_epoch.store(max_epoch, std::memory_order_relaxed);
}

void shm_heap_set_snapshot_kind(ShmLayout &layout, std::uint16_t kind,
                                std::uint64_t min_epoch,
                                std::uint64_t max_epoch) noexcept {
  if (layout.data_heap == nullptr || kind >= KIND_BUCKETS) {
    return;
  }
  auto *h = get_heap_header(layout);
  h->snapshot_min_epoch_by_kind[kind].store(min_epoch, std::memory_order_relaxed);
  h->snapshot_max_epoch_by_kind[kind].store(max_epoch, std::memory_order_relaxed);
}

void shm_heap_set_snapshot_producer(ShmLayout &layout,
                                    std::uint16_t producer_id,
                                    std::uint64_t min_epoch,
                                    std::uint64_t max_epoch) noexcept {
  if (layout.data_heap == nullptr || producer_id >= PRODUCER_BUCKETS) {
    return;
  }
  auto *h = get_heap_header(layout);
  h->snapshot_min_epoch_by_producer[producer_id].store(min_epoch,
                                                       std::memory_order_relaxed);
  h->snapshot_max_epoch_by_producer[producer_id].store(max_epoch,
                                                       std::memory_order_relaxed);
}

void shm_heap_retire_chunk(ShmLayout &layout,
                           std::uint64_t payload_offset) noexcept {
  auto *chunk = chunk_from_payload(layout, payload_offset);
  if (chunk == nullptr || chunk->magic != CHUNK_MAGIC) {
    return;
  }

  const std::uint16_t previous =
      chunk->flags.fetch_or(CHUNK_TOMBSTONE, std::memory_order_acq_rel);
  if ((previous & CHUNK_TOMBSTONE) != 0) {
    return; // already retired; do not double-decrement
  }

  auto *heap_hdr = get_heap_header(layout);
  heap_hdr->active_allocations.fetch_sub(1, std::memory_order_relaxed);

  const std::uint64_t chunk_bytes =
      align_up(sizeof(ShmChunkHeader) + chunk->payload_size, CACHE_LINE_SIZE);
  heap_hdr->reclaimable_bytes.fetch_add(chunk_bytes, std::memory_order_relaxed);

  if (chunk->kind < KIND_BUCKETS) {
    heap_hdr->allocations_by_kind[chunk->kind].fetch_sub(
        1, std::memory_order_relaxed);
  }
  if (chunk->producer_id < PRODUCER_BUCKETS) {
    heap_hdr->allocations_by_producer[chunk->producer_id].fetch_sub(
        1, std::memory_order_relaxed);
  }
}

// ============================================================================
// Compaction and defragmentation
// ============================================================================

std::size_t shm_heap_compact_logical(ShmLayout &layout) noexcept {
  if (layout.base == nullptr || layout.data_heap == nullptr) {
    return 0;
  }

  auto *heap_root = get_heap_root(layout);
  const std::uint64_t safe_epoch = shm_get_min_active_reader_epoch(layout);

  std::uint64_t curr = heap_root->off_first_chunk.load(std::memory_order_acquire);
  std::uint64_t prev = 0;
  std::size_t reclaimed = 0;

  std::uint64_t prev_kind[KIND_BUCKETS] = {0};
  std::uint64_t prev_prod[PRODUCER_BUCKETS] = {0};

  while (curr != 0) {
    auto *chunk = reinterpret_cast<ShmChunkHeader *>(layout.base + curr);

    const std::uint64_t next = chunk->next_offset.load(std::memory_order_acquire);
    const std::uint64_t next_kind =
        chunk->next_kind_offset.load(std::memory_order_acquire);
    const std::uint64_t next_prod =
        chunk->next_producer_offset.load(std::memory_order_acquire);
    const std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);

    const bool tombstoned = (flags & CHUNK_TOMBSTONE) != 0;
    const bool safe = tombstoned && chunk->epoch < safe_epoch;

    if (safe) {
      if (prev != 0) {
        auto *prev_chunk =
            reinterpret_cast<ShmChunkHeader *>(layout.base + prev);
        prev_chunk->next_offset.store(next, std::memory_order_release);
        if (next == 0) {
          heap_root->off_last_chunk.store(prev, std::memory_order_release);
        }

        auto repair = [](std::atomic<std::uint64_t> &prev_link,
                         std::atomic<std::uint64_t> &curr_link,
                         std::uint64_t removed, std::uint64_t fallback) {
          if (prev_link.load(std::memory_order_relaxed) == removed) {
            const std::uint64_t target =
                curr_link.load(std::memory_order_relaxed);
            prev_link.store(target != 0 ? target : fallback,
                            std::memory_order_release);
          }
        };
        repair(prev_chunk->next_offset_lvl2, chunk->next_offset_lvl2, curr, next);
        repair(prev_chunk->next_offset_lvl4, chunk->next_offset_lvl4, curr, next);
        repair(prev_chunk->next_offset_lvl8, chunk->next_offset_lvl8, curr, next);
      } else {
        heap_root->off_first_chunk.store(next, std::memory_order_release);
        if (next == 0) {
          heap_root->off_last_chunk.store(0, std::memory_order_release);
          heap_root->off_last_lvl2.store(0, std::memory_order_release);
          heap_root->off_last_lvl4.store(0, std::memory_order_release);
          heap_root->off_last_lvl8.store(0, std::memory_order_release);
        }
      }

      if (chunk->kind < KIND_BUCKETS) {
        const std::uint16_t k = chunk->kind;
        if (prev_kind[k] != 0) {
          reinterpret_cast<ShmChunkHeader *>(layout.base + prev_kind[k])
              ->next_kind_offset.store(next_kind, std::memory_order_release);
          if (next_kind == 0) {
            heap_root->off_last_by_kind[k].store(prev_kind[k],
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
        const std::uint16_t p = chunk->producer_id;
        if (prev_prod[p] != 0) {
          reinterpret_cast<ShmChunkHeader *>(layout.base + prev_prod[p])
              ->next_producer_offset.store(next_prod, std::memory_order_release);
          if (next_prod == 0) {
            heap_root->off_last_by_producer[p].store(prev_prod[p],
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
      ++reclaimed;
    } else {
      prev = curr;
      if (chunk->kind < KIND_BUCKETS) {
        prev_kind[chunk->kind] = curr;
      }
      if (chunk->producer_id < PRODUCER_BUCKETS) {
        prev_prod[chunk->producer_id] = curr;
      }
    }

    curr = next;
  }

  return reclaimed;
}

std::uint64_t shm_heap_rewind_if_idle(ShmLayout &layout) noexcept {
  if (layout.base == nullptr || layout.data_heap == nullptr) {
    return 0;
  }

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);

  // Only when the heap holds nothing at all. Every live chunk may be the target
  // of an offset a client is still holding, and unlike shm_heap_defragment this
  // never moves one, so no client can be left pointing at relocated bytes.
  if (heap_hdr->active_allocations.load(std::memory_order_acquire) != 0 ||
      heap_root->off_first_chunk.load(std::memory_order_acquire) != 0 ||
      shm_has_active_readers(layout)) {
    return 0;
  }

  const std::uint64_t released =
      heap_hdr->allocated_bytes.exchange(0, std::memory_order_acq_rel);
  if (released == 0) {
    return 0;
  }

  heap_hdr->reclaimable_bytes.store(0, std::memory_order_release);
  heap_root->n_chunks.store(0, std::memory_order_release);
  heap_root->off_last_chunk.store(0, std::memory_order_release);
  heap_root->off_prefetch_head.store(0, std::memory_order_release);
  heap_root->off_last_lvl2.store(0, std::memory_order_release);
  heap_root->off_last_lvl4.store(0, std::memory_order_release);
  heap_root->off_last_lvl8.store(0, std::memory_order_release);

  for (std::size_t i = 0; i < KIND_BUCKETS; ++i) {
    heap_root->off_first_by_kind[i].store(0, std::memory_order_release);
    heap_root->off_last_by_kind[i].store(0, std::memory_order_release);
    heap_root->off_prefetch_head_by_kind[i].store(0, std::memory_order_release);
  }
  for (std::size_t i = 0; i < PRODUCER_BUCKETS; ++i) {
    heap_root->off_first_by_producer[i].store(0, std::memory_order_release);
    heap_root->off_last_by_producer[i].store(0, std::memory_order_release);
    heap_root->off_prefetch_head_by_producer[i].store(0,
                                                      std::memory_order_release);
  }

  if (auto *stats = shm_engine_stats(layout); stats != nullptr) {
    stats->heap_usage_bytes.store(0, std::memory_order_relaxed);
    stats->heap_fragmentation_score.store(0, std::memory_order_relaxed);
  }
  layout.header->heap_fragmentation_score.store(0, std::memory_order_relaxed);

  return released;
}

void shm_heap_defragment(ShmLayout &layout) noexcept {
  if (layout.base == nullptr || layout.data_heap == nullptr) {
    return;
  }

  auto *heap_hdr = get_heap_header(layout);
  auto *heap_root = get_heap_root(layout);

  if (shm_has_active_readers(layout)) {
    return;
  }

  heap_root->flags.fetch_or(SHM_HEAP_FLAG_DEFRAG_IN_PROGRESS,
                            std::memory_order_acq_rel);

  const std::uint64_t safe_epoch = shm_get_min_active_reader_epoch(layout);
  const std::uint64_t heap_data_start =
      layout.header->off_data_heap + heap_metadata_size();

  std::uint64_t curr = heap_root->off_first_chunk.load(std::memory_order_acquire);
  std::uint64_t write_offset = heap_data_start;
  std::uint64_t prev_written = 0;
  std::uint64_t total_bytes = 0;
  std::uint32_t seq = 0;

  std::uint64_t prev_kind[KIND_BUCKETS] = {0};
  std::uint64_t prev_prod[PRODUCER_BUCKETS] = {0};
  std::uint64_t prev_lvl2 = 0;
  std::uint64_t prev_lvl4 = 0;
  std::uint64_t prev_lvl8 = 0;

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

  while (curr != 0) {
    auto *chunk = reinterpret_cast<ShmChunkHeader *>(layout.base + curr);
    const std::uint64_t next = chunk->next_offset.load(std::memory_order_acquire);
    const std::uint16_t flags = chunk->flags.load(std::memory_order_acquire);

    const bool tombstoned = (flags & CHUNK_TOMBSTONE) != 0;
    const bool drop = (flags & CHUNK_FREE) != 0 ||
                      (tombstoned && chunk->epoch < safe_epoch);
    if (drop) {
      curr = next;
      continue;
    }

    const std::uint64_t chunk_bytes =
        align_up(sizeof(ShmChunkHeader) + chunk->payload_size, CACHE_LINE_SIZE);

    if (curr != write_offset) {
      std::memmove(layout.base + write_offset, layout.base + curr, chunk_bytes);
    }
    const std::uint64_t target = write_offset;
    auto *moved = reinterpret_cast<ShmChunkHeader *>(layout.base + target);

    moved->next_offset.store(0, std::memory_order_relaxed);
    moved->next_kind_offset.store(0, std::memory_order_relaxed);
    moved->next_producer_offset.store(0, std::memory_order_relaxed);
    moved->next_offset_lvl2.store(0, std::memory_order_relaxed);
    moved->next_offset_lvl4.store(0, std::memory_order_relaxed);
    moved->next_offset_lvl8.store(0, std::memory_order_relaxed);

    if (prev_written != 0) {
      reinterpret_cast<ShmChunkHeader *>(layout.base + prev_written)
          ->next_offset.store(target, std::memory_order_release);
    } else {
      heap_root->off_first_chunk.store(target, std::memory_order_release);
    }

    if (moved->kind < KIND_BUCKETS) {
      const std::uint16_t k = moved->kind;
      if (prev_kind[k] != 0) {
        reinterpret_cast<ShmChunkHeader *>(layout.base + prev_kind[k])
            ->next_kind_offset.store(target, std::memory_order_release);
      } else {
        heap_root->off_first_by_kind[k].store(target, std::memory_order_release);
      }
      prev_kind[k] = target;
      heap_root->off_last_by_kind[k].store(target, std::memory_order_release);
    }

    if (moved->producer_id < PRODUCER_BUCKETS) {
      const std::uint16_t p = moved->producer_id;
      if (prev_prod[p] != 0) {
        reinterpret_cast<ShmChunkHeader *>(layout.base + prev_prod[p])
            ->next_producer_offset.store(target, std::memory_order_release);
      } else {
        heap_root->off_first_by_producer[p].store(target,
                                                  std::memory_order_release);
      }
      prev_prod[p] = target;
      heap_root->off_last_by_producer[p].store(target, std::memory_order_release);
    }

    ++seq;
    if ((seq & 1u) == 0) {
      if (prev_lvl2 != 0) {
        reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl2)
            ->next_offset_lvl2.store(target, std::memory_order_release);
      }
      prev_lvl2 = target;
      heap_root->off_last_lvl2.store(target, std::memory_order_release);
    }
    if ((seq & 3u) == 0) {
      if (prev_lvl4 != 0) {
        reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl4)
            ->next_offset_lvl4.store(target, std::memory_order_release);
      }
      prev_lvl4 = target;
      heap_root->off_last_lvl4.store(target, std::memory_order_release);
    }
    if ((seq & 7u) == 0) {
      if (prev_lvl8 != 0) {
        reinterpret_cast<ShmChunkHeader *>(layout.base + prev_lvl8)
            ->next_offset_lvl8.store(target, std::memory_order_release);
      }
      prev_lvl8 = target;
      heap_root->off_last_lvl8.store(target, std::memory_order_release);
    }

    prev_written = target;
    write_offset += chunk_bytes;
    total_bytes += chunk_bytes;
    curr = next;
  }

  heap_root->n_chunks.store(seq, std::memory_order_release);
  if (prev_written != 0) {
    heap_root->off_last_chunk.store(prev_written, std::memory_order_release);
  } else {
    heap_root->off_first_chunk.store(0, std::memory_order_release);
    heap_root->off_last_chunk.store(0, std::memory_order_release);
  }

  heap_hdr->allocated_bytes.store(total_bytes, std::memory_order_release);
  heap_hdr->reclaimable_bytes.store(0, std::memory_order_release);

  if (auto *stats = shm_engine_stats(layout); stats != nullptr) {
    stats->heap_usage_bytes.store(total_bytes, std::memory_order_relaxed);
    stats->heap_fragmentation_score.store(0, std::memory_order_relaxed);
  }
  layout.header->heap_fragmentation_score.store(0, std::memory_order_relaxed);

  heap_root->flags.fetch_and(~SHM_HEAP_FLAG_DEFRAG_IN_PROGRESS,
                             std::memory_order_acq_rel);
}

void shm_relocate_chunk(ShmLayout &layout, std::uint64_t old_off,
                        std::uint64_t new_off) noexcept {
  if (layout.base == nullptr || layout.header == nullptr || old_off == 0 ||
      new_off == 0 || old_off == new_off) {
    return;
  }
  if (old_off >= layout.header->total_size ||
      new_off >= layout.header->total_size) {
    return;
  }

  const auto *src =
      reinterpret_cast<const ShmChunkHeader *>(layout.base + old_off);
  const std::size_t bytes =
      align_up(sizeof(ShmChunkHeader) + src->payload_size, CACHE_LINE_SIZE);
  if (new_off + bytes > layout.header->total_size) {
    return;
  }
  std::memmove(layout.base + new_off, layout.base + old_off, bytes);
}

// ============================================================================
// Journal
// ============================================================================

void shm_journal_write(ShmLayout &layout, const BridgeMessage &msg) noexcept {
  if (layout.tx_log == nullptr || layout.header == nullptr) {
    return;
  }
  auto *stats = shm_engine_stats(layout);
  if (stats == nullptr) {
    return;
  }

  const std::uint64_t capacity = layout.header->journal_capacity;
  if (capacity == 0) {
    return;
  }

  // The sequence counter
  const std::uint64_t seq =
      stats->journal_seq.fetch_add(1, std::memory_order_relaxed);

  BridgeMessage copy = msg;
  copy.journal_seq = static_cast<std::uint32_t>(seq + 1);

  auto *entry = reinterpret_cast<BridgeMessage *>(
      layout.tx_log + (seq % capacity) * sizeof(BridgeMessage));
  std::memcpy(entry, &copy, sizeof(BridgeMessage));
  std::atomic_thread_fence(std::memory_order_release);
}

bool shm_journal_replay_next(ShmLayout &layout, std::uint64_t read_index,
                             BridgeMessage &out) noexcept {
  if (layout.tx_log == nullptr || layout.header == nullptr) {
    return false;
  }
  auto *stats = shm_engine_stats(layout);
  if (stats == nullptr) {
    return false;
  }

  const std::uint64_t capacity = layout.header->journal_capacity;
  if (capacity == 0) {
    return false;
  }

  const std::uint64_t written =
      stats->journal_seq.load(std::memory_order_acquire);
  if (read_index >= written) {
    return false;
  }
  // Entries older than one full lap have been overwritten.
  if (written - read_index > capacity) {
    return false;
  }

  std::atomic_thread_fence(std::memory_order_acquire);
  const auto *entry = reinterpret_cast<const BridgeMessage *>(
      layout.tx_log + (read_index % capacity) * sizeof(BridgeMessage));
  std::memcpy(&out, entry, sizeof(BridgeMessage));
  return true;
}

std::uint64_t shm_journal_count(const ShmLayout &layout) noexcept {
  const auto *stats = shm_engine_stats(layout);
  return (stats == nullptr)
             ? 0
             : stats->journal_seq.load(std::memory_order_acquire);
}

// ============================================================================
// Telemetry
// ============================================================================

float shm_backpressure_level(const ShmLayout &layout) noexcept {
  if (layout.cmd_ring == nullptr) {
    return 0.0f;
  }
  return layout.cmd_ring->occupancy();
}

std::uint64_t shm_numa_hotness(const ShmLayout &layout, int node) noexcept {
  if (layout.data_heap == nullptr || node < 0 ||
      static_cast<std::size_t>(node) >= MAX_RUNTIMES) {
    return 0;
  }
  const std::uint64_t raw = get_heap_root(layout)->hotness_by_numa_node[node].load(
      std::memory_order_relaxed);
  return (raw > 0) ? static_cast<std::uint64_t>(std::bit_width(raw)) : 0;
}

void shm_extract_tensor_meta(const ShmLayout &layout, const BridgeMessage &msg,
                             void *&ptr, std::size_t &count) noexcept {
  ptr = nullptr;
  count = 0;

  if (layout.base == nullptr || layout.header == nullptr) {
    return;
  }
  if (msg.type != MsgType::SHM_REF) {
    return;
  }

  const std::uint64_t offset = msg.shm_ref.offset;
  const std::uint64_t bytes = msg.shm_ref.total_bytes;
  const std::uint64_t total = layout.header->total_size;

  if (bytes == 0 || offset >= total || bytes > total - offset) {
    return;
  }

  ptr = layout.base + offset;
  count = static_cast<std::size_t>(bytes);
}

} // namespace Sphere
