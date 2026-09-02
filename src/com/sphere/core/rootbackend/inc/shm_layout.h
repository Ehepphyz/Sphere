// shm_layout.h

// Shared-memory region layout

#pragma once

#include "common_config.h"
#include "lockfree_ring.h"
#include "platform.h"

#include <atomic>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>
#include <type_traits>

namespace Sphere {

using CmdRing = ShmMpmcRing<BridgeMessage, CMD_RING_CAPACITY_POW2>;
using EvtRing = ShmMpmcRing<BridgeMessage, EVT_RING_CAPACITY_POW2>;

// -----------------------------------------------------------------------------
// Flags and enumerations
// -----------------------------------------------------------------------------

enum ShmHeapFlags : std::uint32_t {
  SHM_HEAP_FLAG_NONE = 0x00,
  SHM_HEAP_FLAG_DEFRAG_IN_PROGRESS = 0x01
};

enum ChunkFlags : std::uint16_t {
  CHUNK_INCOMPLETE = 0x00,
  CHUNK_COMMITTED = 0x01,
  CHUNK_DIRTY = 0x02,
  CHUNK_TOMBSTONE = 0x04,
  CHUNK_FREE = 0x08
};

enum class EngineState : std::uint32_t {
  UNINITIALIZED = 0,
  INITIALIZING = 1,
  READY = 2,
  RUNNING = 3,
  DEGRADED = 4,
  STOPPING = 5,
  STOPPED = 6,
  RECOVERY = 7,
  CORRUPTED = 98,
  ERROR = 99
};

enum class EngineError : std::uint32_t {
  OK = 0,
  BUSY = 1,
  OOM_HEAP = 2,
  ABI_MISMATCH = 3,
  INIT_TIMEOUT = 4,
  INVALID_CONFIG = 5,
  CORRUPTED_LAYOUT = 6,
  REGION_TOO_SMALL = 7
};

// -----------------------------------------------------------------------------
// Region header
// -----------------------------------------------------------------------------

/**
 * Root descriptor of the shared-memory region
 */
struct alignas(CACHE_LINE_SIZE) ShmHeader {

  // ---- Cache line 0: identity, checked before anything else is trusted ----
  std::atomic<std::uint32_t> magic{SHM_MAGIC};
  std::atomic<std::uint32_t> version{SHM_VERSION};
  std::atomic<std::uint32_t> abi_version{SHM_VERSION};
  std::atomic<std::uint32_t> proto_version{PROTO_VERSION};
  std::atomic<std::uint32_t> state{
      static_cast<std::uint32_t>(EngineState::UNINITIALIZED)};
  std::atomic<std::uint32_t> engine_flags{0};
  std::atomic<std::uint32_t> last_error_code{0};
  std::uint32_t reserved0{0};
  std::uint64_t total_size{SHM_SIZE};
  std::uint64_t reserved1[3]{};

  // ---- Liveness, one counter per cache line to avoid cross-process bouncing --
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> heartbeat_cpp{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> heartbeat_java{0};

  // ---- Job accounting ----
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> engine_cycles{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> jobs_completed{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> jobs_failed{0};

  // ---- Control-loop telemetry ----
  alignas(CACHE_LINE_SIZE) std::atomic<float> raw_occupancy{0.0f};
  std::atomic<float> predicted_pressure{0.0f};
  std::atomic<float> pressure_variance{0.0f};
  std::atomic<float> dynamic_high_threshold{0.80f};
  std::atomic<float> dynamic_low_threshold{0.10f};
  std::atomic<float> best_cluster_score{0.0f};
  std::atomic<std::uint32_t> offload_tokens_remaining{0};
  std::atomic<std::uint32_t> heap_fragmentation_score{0};
  std::atomic<std::uint64_t> control_loop_jitter_ns{0};
  std::atomic<std::uint64_t> control_loop_period_ns{0};
  std::uint64_t reserved2[2]{};

  // ---- Partition table: immutable after init_shm, and authoritative ----
  alignas(CACHE_LINE_SIZE) std::uint64_t off_cmd_ring{0};
  std::uint64_t size_cmd_ring{0};
  std::uint64_t cmd_ring_capacity{0};  // slot COUNT, never the exponent
  std::uint64_t cmd_ring_slot_size{0};
  std::uint64_t off_evt_ring{0};
  std::uint64_t size_evt_ring{0};
  std::uint64_t evt_ring_capacity{0};  // slot COUNT, never the exponent
  std::uint64_t evt_ring_slot_size{0};
  std::uint64_t off_engine_stats{0};
  std::uint64_t size_engine_stats{0};
  std::uint64_t off_span_ring{0};
  std::uint64_t size_span_ring{0};
  std::uint64_t span_ring_capacity{0};
  std::uint64_t off_schema_heap{0};
  std::uint64_t size_schema_heap{0};
  std::uint64_t off_tx_log{0};
  std::uint64_t size_tx_log{0};
  std::uint64_t off_data_heap{0};
  std::uint64_t size_data_heap{0};
  std::uint64_t journal_capacity{0};
  std::uint64_t reserved3[4]{};

  // ---- Cluster membership: 1 when runtime `i` is attached, 0 otherwise ----
  alignas(CACHE_LINE_SIZE)
      std::atomic<std::uint32_t> cluster_runtime_ids[MAX_RUNTIMES]{};

  // ---- Reader epochs, kept apart from membership ----

  alignas(CACHE_LINE_SIZE)
      std::atomic<std::uint64_t> reader_epochs[MAX_RUNTIMES]{};
};

struct alignas(CACHE_LINE_SIZE) EngineStats {
  std::atomic<std::uint64_t> jobs_inflight{0};
  std::atomic<std::uint64_t> last_job_latency_ns{0};
  std::atomic<std::uint64_t> journal_seq{0}; // next journal sequence number
  std::atomic<std::uint64_t> avg_job_latency_ns{0};
  std::atomic<std::uint64_t> max_job_latency_ns{0};
  std::atomic<std::uint64_t> heap_usage_bytes{0};
  std::atomic<std::uint64_t> heap_fragmentation_score{0};
  std::atomic<std::uint64_t> crc_failures_total{0};
};

struct alignas(CACHE_LINE_SIZE) ShmHeapRoot {
  std::atomic<std::uint32_t> flags{SHM_HEAP_FLAG_NONE};
  std::atomic<std::uint32_t> n_chunks{0};
  std::atomic<std::uint64_t> off_first_chunk{0};
  std::atomic<std::uint64_t> off_last_chunk{0};
  std::atomic<std::uint64_t> off_first_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> off_last_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> off_first_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> off_last_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> off_prefetch_head{0};
  std::atomic<std::uint64_t> off_last_lvl2{0};
  std::atomic<std::uint64_t> off_last_lvl4{0};
  std::atomic<std::uint64_t> off_last_lvl8{0};
  std::atomic<std::uint64_t> off_prefetch_head_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> off_prefetch_head_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> hotness_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> hotness_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> hotness_by_numa_node[MAX_RUNTIMES]{};
};

struct alignas(CACHE_LINE_SIZE) Chunk {
  std::uint32_t magic{CHUNK_MAGIC};
  std::uint32_t payload_size{0};
  std::uint64_t epoch{0};
  std::uint16_t kind{0};
  std::uint16_t producer_id{0};
  std::uint16_t encoding{0};
  std::atomic<std::uint16_t> flags{CHUNK_INCOMPLETE};
  std::uint32_t checksum{0};
  std::uint32_t reserved{0};

  std::atomic<std::uint64_t> next_offset{0};
  std::atomic<std::uint64_t> next_kind_offset{0};
  std::atomic<std::uint64_t> next_producer_offset{0};
  std::atomic<std::uint64_t> next_offset_lvl2{0};
  std::atomic<std::uint64_t> next_offset_lvl4{0};
  std::atomic<std::uint64_t> next_offset_lvl8{0};
};

using ShmChunkHeader = Chunk;

struct alignas(CACHE_LINE_SIZE) ShmHeapHeader {
  std::atomic<std::uint64_t> allocated_bytes{0};
  std::atomic<std::uint64_t> total_capacity{0};
  std::atomic<std::uint64_t> epoch{0};
  std::atomic<std::uint64_t> active_allocations{0};
  std::atomic<std::uint64_t> epoch_barrier{0};
  std::atomic<std::uint64_t> snapshot_min_epoch{0};
  std::atomic<std::uint64_t> snapshot_max_epoch{0};
  std::atomic<std::uint64_t> reclaimable_bytes{0};
  std::atomic<std::uint64_t> allocations_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> allocations_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> quota_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> quota_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_min_epoch_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_max_epoch_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_min_epoch_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_max_epoch_by_producer[PRODUCER_BUCKETS]{};
};

/**
 * Process-local view of the mapped region
 */
struct ShmLayout {
  ShmHeader *header{nullptr};
  CmdRing *cmd_ring{nullptr};
  EvtRing *evt_ring{nullptr};
  EngineStats *stats{nullptr};
  std::byte *base{nullptr};
  std::byte *span_ring{nullptr};
  std::byte *data_heap{nullptr};
  std::byte *schema_heap{nullptr};
  std::byte *tx_log{nullptr};

  [[nodiscard]] bool is_valid() const noexcept {
    return base != nullptr && header != nullptr && cmd_ring != nullptr &&
           evt_ring != nullptr && stats != nullptr && data_heap != nullptr;
  }
};

/**
 * Owning handle: keeps the OS mapping alive for as long as the view is used
 */
class ShmSession {
public:
  ShmSession() noexcept = default;

  ShmSession(Platform::ShmRegion &&region, const ShmLayout &layout) noexcept
      : region_(std::move(region)), layout_(layout) {}

  ShmSession(ShmSession &&) noexcept = default;
  ShmSession &operator=(ShmSession &&) noexcept = default;
  ShmSession(const ShmSession &) = delete;
  ShmSession &operator=(const ShmSession &) = delete;

  [[nodiscard]] ShmLayout &layout() noexcept { return layout_; }
  [[nodiscard]] const ShmLayout &layout() const noexcept { return layout_; }
  [[nodiscard]] Platform::ShmRegion &region() noexcept { return region_; }

  [[nodiscard]] bool is_valid() const noexcept {
    return region_.is_valid() && layout_.is_valid();
  }

private:
  Platform::ShmRegion region_{};
  ShmLayout layout_{};
};

struct ShmHeapDebugStats {
  std::uint64_t committed{0};
  std::uint64_t dirty{0};
  std::uint64_t tombstone{0};
  std::uint64_t incomplete{0};
  std::uint64_t by_kind[KIND_BUCKETS]{};
  std::uint64_t by_producer[PRODUCER_BUCKETS]{};
};

// -----------------------------------------------------------------------------
// ABI validation
// -----------------------------------------------------------------------------

static_assert(std::is_standard_layout_v<ShmHeader>,
              "ABI: ShmHeader must be standard layout.");
static_assert(std::is_standard_layout_v<Chunk>,
              "ABI: Chunk must be standard layout.");
static_assert(sizeof(ShmHeader) % CACHE_LINE_SIZE == 0,
              "ABI: ShmHeader must be a whole number of cache lines.");
static_assert(sizeof(EngineStats) % CACHE_LINE_SIZE == 0,
              "ABI: EngineStats must be cache-line sized.");
static_assert(sizeof(ShmHeapRoot) % CACHE_LINE_SIZE == 0,
              "ABI: ShmHeapRoot must be cache-line sized.");
static_assert(sizeof(Chunk) % CACHE_LINE_SIZE == 0,
              "ABI: Chunk must be cache-line sized.");
static_assert(sizeof(ShmHeapHeader) % CACHE_LINE_SIZE == 0,
              "ABI: ShmHeapHeader must be cache-line sized.");
static_assert(offsetof(ShmHeader, magic) == 0, "ABI: magic offset drift.");
static_assert(offsetof(ShmHeader, version) == 4, "ABI: version offset drift.");
static_assert(offsetof(ShmHeader, abi_version) == 8,
              "ABI: abi_version offset drift.");
static_assert(offsetof(ShmHeader, proto_version) == 12,
              "ABI: proto_version offset drift.");
static_assert(offsetof(ShmHeader, state) == 16, "ABI: state offset drift.");
static_assert(offsetof(ShmHeader, total_size) == 32,
              "ABI: total_size offset drift.");
static_assert(offsetof(ShmHeader, heartbeat_cpp) == 64,
              "ABI: heartbeat_cpp offset drift.");
static_assert(offsetof(ShmHeader, heartbeat_java) == 128,
              "ABI: heartbeat_java offset drift.");
static_assert(offsetof(ShmHeader, engine_cycles) == 192,
              "ABI: engine_cycles offset drift.");
static_assert(offsetof(ShmHeader, jobs_completed) == 256,
              "ABI: jobs_completed offset drift.");
static_assert(offsetof(ShmHeader, jobs_failed) == 320,
              "ABI: jobs_failed offset drift.");
static_assert(offsetof(ShmHeader, raw_occupancy) == 384,
              "ABI: raw_occupancy offset drift.");
static_assert(offsetof(ShmHeader, off_cmd_ring) == 448,
              "ABI: partition table offset drift.");
static_assert(offsetof(ShmHeader, off_evt_ring) == 480,
              "ABI: off_evt_ring offset drift.");
static_assert(offsetof(ShmHeader, off_engine_stats) == 512,
              "ABI: off_engine_stats offset drift.");
static_assert(offsetof(ShmHeader, off_data_heap) == 584,
              "ABI: off_data_heap offset drift.");
static_assert(offsetof(ShmHeader, cluster_runtime_ids) == 640,
              "ABI: cluster table offset drift.");
static_assert(offsetof(ShmHeader, reader_epochs) == 896,
              "ABI: reader epoch table offset drift.");
static_assert(sizeof(ShmHeader) == 1408, "ABI: ShmHeader size drift.");

// -----------------------------------------------------------------------------
// Alignment helpers
// -----------------------------------------------------------------------------

[[nodiscard]] constexpr std::size_t align_up(std::size_t value,
                                             std::size_t alignment) noexcept {

  return (alignment == 0 || (alignment & (alignment - 1)) != 0)
             ? value
             : ((value + (alignment - 1)) & ~(alignment - 1));
}

/// Byte offset of ShmHeapRoot inside the data heap partition.
[[nodiscard]] constexpr std::size_t heap_root_offset() noexcept {
  return sizeof(ShmHeapHeader);
}

/// Total size of the heap bookkeeping that precedes the first chunk.
[[nodiscard]] constexpr std::size_t heap_metadata_size() noexcept {
  return align_up(heap_root_offset() + sizeof(ShmHeapRoot), CACHE_LINE_SIZE);
}

// -----------------------------------------------------------------------------
// Initialization
// -----------------------------------------------------------------------------

[[nodiscard]] ShmSession init_shm(bool create, const char *region_name = SHM_NAME,
                                  std::size_t region_size = SHM_SIZE,
                                  bool force_format = false);

[[nodiscard]] std::size_t shm_required_size() noexcept;

// -----------------------------------------------------------------------------
// Sub-structure accessors
// -----------------------------------------------------------------------------

[[nodiscard]] inline EngineStats *shm_engine_stats(ShmLayout &layout) noexcept {
  return layout.stats;
}

[[nodiscard]] inline const EngineStats *
shm_engine_stats(const ShmLayout &layout) noexcept {
  return layout.stats;
}

[[nodiscard]] inline ShmHeapHeader *
get_heap_header(ShmLayout &layout) noexcept {
  return reinterpret_cast<ShmHeapHeader *>(layout.data_heap);
}

[[nodiscard]] inline const ShmHeapHeader *
get_heap_header(const ShmLayout &layout) noexcept {
  return reinterpret_cast<const ShmHeapHeader *>(layout.data_heap);
}

[[nodiscard]] inline ShmHeapRoot *get_heap_root(ShmLayout &layout) noexcept {
  return reinterpret_cast<ShmHeapRoot *>(layout.data_heap + heap_root_offset());
}

[[nodiscard]] inline const ShmHeapRoot *
get_heap_root(const ShmLayout &layout) noexcept {
  return reinterpret_cast<const ShmHeapRoot *>(layout.data_heap +
                                               heap_root_offset());
}

// -----------------------------------------------------------------------------
// Heap allocation
// -----------------------------------------------------------------------------

[[nodiscard]] std::uint64_t shm_heap_alloc(ShmLayout &layout, std::size_t size,
                                           std::uint16_t kind = 0,
                                           std::uint16_t producer_id = 0,
                                           std::uint16_t encoding = 0) noexcept;

void shm_chunk_commit(ShmLayout &layout, std::uint64_t payload_offset) noexcept;

[[nodiscard]] std::uint64_t shm_heap_alloc_data(ShmLayout &layout,
                                                std::size_t size) noexcept;
[[nodiscard]] std::uint64_t shm_heap_alloc_schema(ShmLayout &layout,
                                                  std::size_t size) noexcept;
[[nodiscard]] std::uint64_t shm_heap_alloc_tx(ShmLayout &layout,
                                              std::size_t size) noexcept;

void shm_heap_reset(ShmLayout &layout) noexcept;

/**
 * Publishes a reclamation barrier at the current epoch.
 */
void shm_heap_soft_barrier(ShmLayout &layout) noexcept;

[[nodiscard]] std::uint64_t
shm_heap_current_epoch(const ShmLayout &layout) noexcept;

// -----------------------------------------------------------------------------
// Chunk traversal and inspection
// -----------------------------------------------------------------------------

[[nodiscard]] bool shm_chunk_is_valid(const ShmLayout &layout,
                                      std::uint64_t payload_offset) noexcept;
[[nodiscard]] const Chunk *
shm_chunk_get_header(const ShmLayout &layout,
                     std::uint64_t payload_offset) noexcept;
[[nodiscard]] std::uint64_t
shm_chunk_next_payload(const ShmLayout &layout,
                       std::uint64_t current_payload_offset) noexcept;
[[nodiscard]] std::uint64_t
shm_chunk_next_payload_lvl2(const ShmLayout &layout,
                            std::uint64_t current_payload_offset) noexcept;
[[nodiscard]] std::uint64_t
shm_chunk_next_payload_lvl4(const ShmLayout &layout,
                            std::uint64_t current_payload_offset) noexcept;
[[nodiscard]] std::uint64_t
shm_chunk_next_payload_lvl8(const ShmLayout &layout,
                            std::uint64_t current_payload_offset) noexcept;

[[nodiscard]] bool shm_chunk_verify_crc(const ShmLayout &layout,
                                        std::uint64_t payload_offset) noexcept;
[[nodiscard]] std::uint64_t shm_chunk_scan(const ShmLayout &layout,
                                           std::uint64_t start_payload_offset,
                                           std::uint32_t step_log2) noexcept;
[[nodiscard]] std::uint64_t shm_heap_first_by_kind(const ShmLayout &layout,
                                                   std::uint16_t kind) noexcept;
[[nodiscard]] std::uint64_t
shm_heap_first_by_producer(const ShmLayout &layout,
                           std::uint16_t producer_id) noexcept;
[[nodiscard]] bool
shm_chunk_is_visible_for_analytics(const ShmLayout &layout,
                                   std::uint64_t payload_offset) noexcept;
[[nodiscard]] bool
shm_chunk_is_visible_for_debug(const ShmLayout &layout,
                               std::uint64_t payload_offset) noexcept;
[[nodiscard]] bool
shm_chunk_is_visible_for_reader(const ShmLayout &layout,
                                std::uint64_t payload_offset,
                                std::uint64_t reader_epoch) noexcept;
[[nodiscard]] bool
shm_chunk_is_consistent(const ShmLayout &layout,
                        std::uint64_t payload_offset) noexcept;

void shm_chunk_mark_dirty(ShmLayout &layout,
                          std::uint64_t payload_offset) noexcept;
void shm_chunk_mark_tombstone(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept;
void shm_chunk_mark_committed(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept;

template <typename Predicate>
[[nodiscard]] std::uint64_t
shm_chunk_scan_if(const ShmLayout &layout, std::uint64_t start_payload_offset,
                  std::uint32_t step_log2, Predicate &&pred) noexcept {
  std::uint64_t current = start_payload_offset;
  while (current != 0) {
    if (pred(layout, current)) {
      return current;
    }
    current = shm_chunk_scan(layout, current, step_log2);
  }
  return 0;
}

// -----------------------------------------------------------------------------
// Iteration
// -----------------------------------------------------------------------------

[[nodiscard]] std::uint64_t shm_iter_begin_by_kind(const ShmLayout &layout,
                                                   std::uint16_t kind) noexcept;
[[nodiscard]] std::uint64_t
shm_iter_next_by_kind(const ShmLayout &layout,
                      std::uint64_t current_payload_offset) noexcept;
[[nodiscard]] std::uint64_t
shm_iter_begin_by_producer(const ShmLayout &layout,
                           std::uint16_t producer_id) noexcept;
[[nodiscard]] std::uint64_t
shm_iter_next_by_producer(const ShmLayout &layout,
                          std::uint64_t current_payload_offset) noexcept;

// -----------------------------------------------------------------------------
// Reader epochs and cluster membership
// -----------------------------------------------------------------------------

void shm_register_runtime(ShmLayout &layout, std::uint32_t id) noexcept;
void shm_unregister_runtime(ShmLayout &layout, std::uint32_t id) noexcept;
[[nodiscard]] bool shm_is_runtime_active(const ShmLayout &layout,
                                         std::uint32_t id) noexcept;

/// Publishes the current epoch as runtime `id`'s read watermark.
void shm_reader_pin(ShmLayout &layout, std::uint32_t runtime_id) noexcept;

/// Clears runtime `id`'s read watermark.
void shm_reader_unpin(ShmLayout &layout, std::uint32_t runtime_id) noexcept;

[[nodiscard]] std::uint64_t
shm_get_min_active_reader_epoch(const ShmLayout &layout) noexcept;

/// True when at least one runtime holds a read pin.
[[nodiscard]] bool shm_has_active_readers(const ShmLayout &layout) noexcept;

// -----------------------------------------------------------------------------
// Prefetch, quotas and maintenance
// -----------------------------------------------------------------------------

[[nodiscard]] std::uint64_t
shm_heap_prefetch_head(const ShmLayout &layout) noexcept;
[[nodiscard]] std::uint64_t
shm_heap_prefetch_head_by_kind(const ShmLayout &layout,
                               std::uint16_t kind) noexcept;
[[nodiscard]] std::uint64_t
shm_heap_prefetch_head_by_producer(const ShmLayout &layout,
                                   std::uint16_t producer_id) noexcept;
[[nodiscard]] std::uint64_t
shm_prefetch_range(const ShmLayout &layout, std::uint64_t start_payload_offset,
                   std::uint32_t step_log2, std::uint32_t depth) noexcept;

void shm_heap_collect_debug_stats(const ShmLayout &layout,
                                  ShmHeapDebugStats &out) noexcept;
void shm_heap_set_quota_kind(ShmLayout &layout, std::uint16_t kind,
                             std::uint64_t quota) noexcept;
void shm_heap_set_quota_producer(ShmLayout &layout, std::uint16_t producer_id,
                                 std::uint64_t quota) noexcept;
void shm_heap_set_snapshot_epoch_window(ShmLayout &layout,
                                        std::uint64_t min_epoch,
                                        std::uint64_t max_epoch) noexcept;
void shm_heap_set_snapshot_kind(ShmLayout &layout, std::uint16_t kind,
                                std::uint64_t min_epoch,
                                std::uint64_t max_epoch) noexcept;
void shm_heap_set_snapshot_producer(ShmLayout &layout,
                                    std::uint16_t producer_id,
                                    std::uint64_t min_epoch,
                                    std::uint64_t max_epoch) noexcept;
void shm_heap_retire_chunk(ShmLayout &layout,
                           std::uint64_t payload_offset) noexcept;
std::size_t shm_heap_compact_logical(ShmLayout &layout) noexcept;

/**
 * Returns the bump pointer to the start of the heap, but only while the heap
 * holds no chunk at all and no reader is pinned. Never moves live data, so an
 * offset already handed to a client stays valid until that client releases it.
 * Returns the number of bytes handed back.
 */
std::uint64_t shm_heap_rewind_if_idle(ShmLayout &layout) noexcept;

void shm_heap_defragment(ShmLayout &layout) noexcept;
void shm_heap_update_prefetch(ShmLayout &layout, std::uint16_t kind) noexcept;
void shm_relocate_chunk(ShmLayout &layout, std::uint64_t old_off,
                        std::uint64_t new_off) noexcept;

// -----------------------------------------------------------------------------
// Journal and telemetry
// -----------------------------------------------------------------------------

void shm_journal_write(ShmLayout &layout, const BridgeMessage &msg) noexcept;

[[nodiscard]] bool shm_journal_replay_next(ShmLayout &layout,
                                           std::uint64_t read_index,
                                           BridgeMessage &out) noexcept;

/// Number of journal entries written so far.
[[nodiscard]] std::uint64_t shm_journal_count(const ShmLayout &layout) noexcept;

[[nodiscard]] float shm_backpressure_level(const ShmLayout &layout) noexcept;

/// Hotness score for a NUMA node, read from its own table rather than the
/// unrelated per-kind table.
[[nodiscard]] std::uint64_t shm_numa_hotness(const ShmLayout &layout,
                                             int node) noexcept;

void shm_extract_tensor_meta(const ShmLayout &layout, const BridgeMessage &msg,
                             void *&ptr, std::size_t &count) noexcept;

[[nodiscard]] std::uint64_t shm_crc_failures_total() noexcept;
[[nodiscard]] std::uint64_t
shm_crc_failures_by_kind(std::uint16_t kind) noexcept;

// -----------------------------------------------------------------------------
// Scoped writer
// -----------------------------------------------------------------------------

class ScopedChunkWriter {
public:
  ScopedChunkWriter(ShmLayout &layout, std::size_t size, std::uint16_t kind = 0,
                    std::uint16_t producer_id = 0,
                    std::uint16_t encoding = 0) noexcept
      : layout_(&layout), payload_offset_(shm_heap_alloc(
                              layout, size, kind, producer_id, encoding)) {
    if (payload_offset_ != 0) {
      header_ =
          const_cast<Chunk *>(shm_chunk_get_header_raw(*layout_, payload_offset_));
      payload_ptr_ = layout_->base + payload_offset_;
    }
  }

  ~ScopedChunkWriter() noexcept { rollback_if_open(); }

  ScopedChunkWriter(const ScopedChunkWriter &) = delete;
  ScopedChunkWriter &operator=(const ScopedChunkWriter &) = delete;

  ScopedChunkWriter(ScopedChunkWriter &&other) noexcept
      : layout_(other.layout_), payload_offset_(other.payload_offset_),
        header_(other.header_), payload_ptr_(other.payload_ptr_),
        committed_(other.committed_) {
    other.disarm();
  }

  ScopedChunkWriter &operator=(ScopedChunkWriter &&other) noexcept {
    if (this != &other) {
      rollback_if_open();
      layout_ = other.layout_;
      payload_offset_ = other.payload_offset_;
      header_ = other.header_;
      payload_ptr_ = other.payload_ptr_;
      committed_ = other.committed_;
      other.disarm();
    }
    return *this;
  }

  [[nodiscard]] explicit operator bool() const noexcept {
    return payload_ptr_ != nullptr;
  }

  [[nodiscard]] std::byte *data() noexcept { return payload_ptr_; }

  template <typename T> [[nodiscard]] T *as() noexcept {
    return std::launder(reinterpret_cast<T *>(payload_ptr_));
  }

  [[nodiscard]] std::uint64_t offset() const noexcept {
    return payload_offset_;
  }

  void commit() noexcept {
    if (!committed_ && payload_offset_ != 0 && layout_ != nullptr) {
      shm_chunk_commit(*layout_, payload_offset_);
      committed_ = true;
    }
  }

  void commit(std::uint32_t checksum) noexcept {
    if (!committed_ && payload_offset_ != 0 && layout_ != nullptr) {
      if (header_ != nullptr) {
        header_->checksum = checksum;
      }
      shm_chunk_commit(*layout_, payload_offset_);
      committed_ = true;
    }
  }

  void abort() noexcept { rollback_if_open(); }

private:

  static const Chunk *shm_chunk_get_header_raw(const ShmLayout &layout,
                                               std::uint64_t payload_offset) noexcept {
    if (layout.base == nullptr || payload_offset < sizeof(Chunk)) {
      return nullptr;
    }
    return reinterpret_cast<const Chunk *>(layout.base + payload_offset -
                                           sizeof(Chunk));
  }

  void rollback_if_open() noexcept {
    if (!committed_ && header_ != nullptr && layout_ != nullptr) {
      
      header_->flags.store(CHUNK_DIRTY, std::memory_order_release);
      shm_heap_retire_chunk(*layout_, payload_offset_);
      committed_ = true;
    }
  }

  void disarm() noexcept {
    payload_offset_ = 0;
    header_ = nullptr;
    payload_ptr_ = nullptr;
    committed_ = true;
  }

  ShmLayout *layout_{nullptr};
  std::uint64_t payload_offset_{0};
  Chunk *header_{nullptr};
  std::byte *payload_ptr_{nullptr};
  bool committed_{false};
};

} // namespace Sphere
