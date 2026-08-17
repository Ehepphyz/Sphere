// shm_layout.h

#pragma once

#include "common_config.h"
#include "lockfree_ring.h"

#include <atomic>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>
#include <type_traits>

namespace Sphere {

// Forward Declarations & Ring Aliases
struct BridgeMessage;

using CmdRing = ShmMpmcRing<BridgeMessage, CMD_RING_CAPACITY_POW2>;
using EvtRing = ShmMpmcRing<BridgeMessage, EVT_RING_CAPACITY_POW2>;

// Heap Flags, Chunk Flags & Engine Enumerations
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
  CORRUPTED_LAYOUT = 6
};

// Core Shared Memory Layout Structures
struct alignas(CACHE_LINE_SIZE) ShmHeader {
  std::atomic<std::uint32_t> magic{SHM_MAGIC};
  std::atomic<std::uint32_t> version{SHM_VERSION};
  std::atomic<std::uint32_t> state{
      static_cast<std::uint32_t>(EngineState::UNINITIALIZED)};

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> heartbeat_cpp{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> heartbeat_java{0};

  std::atomic<std::uint32_t> abi_version{1};
  std::atomic<std::uint32_t> proto_version{1};

  std::atomic<std::uint32_t> engine_flags{0};
  std::atomic<std::uint32_t> last_error_code{0};

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> engine_cycles{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> jobs_completed{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> jobs_failed{0};

  alignas(CACHE_LINE_SIZE) std::atomic<float> raw_occupancy{0.0f};
  std::atomic<float> predicted_pressure{0.0f};
  std::atomic<float> pressure_variance{0.0f};

  std::atomic<float> dynamic_high_threshold{0.80f};
  std::atomic<float> dynamic_low_threshold{0.10f};

  std::atomic<std::uint64_t> control_loop_jitter_ns{0};
  std::atomic<std::uint64_t> control_loop_period_ns{0};

  std::atomic<float> best_cluster_score{0.0f};
  std::atomic<std::uint32_t> offload_tokens_remaining{0};
  std::atomic<std::uint32_t> heap_fragmentation_score{0};

  alignas(CACHE_LINE_SIZE)
      std::atomic<std::uint32_t> cluster_runtime_ids[MAX_RUNTIMES]{};

  alignas(CACHE_LINE_SIZE) std::uint64_t off_cmd_ring{0};
  std::uint64_t cmd_ring_capacity{1ULL << CMD_RING_CAPACITY_POW2};
  std::uint32_t cmd_ring_slot_size{sizeof(BridgeMessage)};
  std::uint32_t evt_ring_slot_size{sizeof(BridgeMessage)};
  CmdRing cmd_ring;

  alignas(CACHE_LINE_SIZE) std::uint64_t off_evt_ring{0};
  std::uint64_t evt_ring_capacity{1ULL << EVT_RING_CAPACITY_POW2};
  EvtRing evt_ring;

  alignas(CACHE_LINE_SIZE) std::uint64_t off_data_heap{0};
  std::uint64_t size_data_heap{0};

  std::uint64_t off_schema_heap{0};
  std::uint64_t size_schema_heap{0};

  std::uint64_t off_tx_log{0};
  std::uint64_t size_tx_log{0};

  std::uint64_t total_size{SHM_SIZE};
};

struct alignas(CACHE_LINE_SIZE) EngineStats {
  std::atomic<std::uint64_t> jobs_inflight{0};
  std::atomic<std::uint64_t> last_job_latency_ns{0};
  std::atomic<std::uint64_t> last_update_epoch{0};
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
  std::atomic<std::uint64_t> allocations_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> allocations_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> quota_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> quota_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_min_epoch_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_max_epoch_by_kind[KIND_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_min_epoch_by_producer[PRODUCER_BUCKETS]{};
  std::atomic<std::uint64_t> snapshot_max_epoch_by_producer[PRODUCER_BUCKETS]{};
};

struct ShmLayout {
  ShmHeader *header{nullptr};
  CmdRing *cmd_ring{nullptr};
  EvtRing *evt_ring{nullptr};
  std::byte *base{nullptr};
  std::byte *data_heap{nullptr};
  std::byte *schema_heap{nullptr};
  std::byte *tx_log{nullptr};
};

struct ShmHeapDebugStats {
  std::uint64_t committed{0};
  std::uint64_t dirty{0};
  std::uint64_t tombstone{0};
  std::uint64_t incomplete{0};
  std::uint64_t by_kind[KIND_BUCKETS]{};
  std::uint64_t by_producer[PRODUCER_BUCKETS]{};
};

// ABI & Alignment Validation
static_assert(sizeof(ShmHeader) % CACHE_LINE_SIZE == 0,
              "ABI Error: ShmHeader size must be a multiple of CACHE_LINE_SIZE "
              "to avoid false sharing.");
static_assert(
    sizeof(EngineStats) % CACHE_LINE_SIZE == 0,
    "ABI Error: EngineStats size must be aligned to CACHE_LINE_SIZE.");
static_assert(
    sizeof(ShmHeapRoot) % CACHE_LINE_SIZE == 0,
    "ABI Error: ShmHeapRoot size must be aligned to CACHE_LINE_SIZE.");
static_assert(sizeof(Chunk) % CACHE_LINE_SIZE == 0,
              "ABI Error: Chunk size must be aligned to CACHE_LINE_SIZE.");
static_assert(
    sizeof(ShmHeapHeader) % CACHE_LINE_SIZE == 0,
    "ABI Error: ShmHeapHeader size must be aligned to CACHE_LINE_SIZE.");

static_assert(std::is_standard_layout_v<ShmHeader>,
              "ABI Error: ShmHeader must have a standard layout for "
              "cross-language/process compatibility.");
static_assert(std::is_standard_layout_v<Chunk>,
              "ABI Error: Chunk must have a standard layout.");

static_assert(offsetof(ShmHeader, heartbeat_cpp) == 64,
              "ABI Error: ShmHeader::heartbeat_cpp offset drift.");
static_assert(offsetof(ShmHeader, heartbeat_java) == 128,
              "ABI Error: ShmHeader::heartbeat_java offset drift.");
static_assert(
    offsetof(ShmHeader, off_cmd_ring) % CACHE_LINE_SIZE == 0,
    "ABI Error: ShmHeader::off_cmd_ring must start at a cache line boundary.");
static_assert(
    offsetof(ShmHeader, off_evt_ring) % CACHE_LINE_SIZE == 0,
    "ABI Error: ShmHeader::off_evt_ring must start at a cache line boundary.");

// Memory Alignment Utility Functions
[[nodiscard]] constexpr std::size_t align_up(std::size_t value,
                                             std::size_t alignment) noexcept {
  return (value + (alignment - 1)) & ~(alignment - 1);
}

// Memory Initialization & Allocation APIs
[[nodiscard]] ShmLayout init_shm(bool create);

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
void shm_heap_soft_barrier(ShmLayout &layout) noexcept;
[[nodiscard]] std::uint64_t
shm_heap_current_epoch(const ShmLayout &layout) noexcept;

// Chunk Traversal & Inspection APIs
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

// Engine Statistics & Navigation Helper Inline Functions
[[nodiscard]] inline EngineStats *shm_engine_stats(ShmLayout &layout) noexcept {
  return reinterpret_cast<EngineStats *>(
      reinterpret_cast<std::byte *>(layout.base) + sizeof(ShmHeader));
}

[[nodiscard]] inline const EngineStats *
shm_engine_stats(const ShmLayout &layout) noexcept {
  return reinterpret_cast<const EngineStats *>(
      reinterpret_cast<const std::byte *>(layout.base) + sizeof(ShmHeader));
}

[[nodiscard]] constexpr std::size_t heap_root_offset() noexcept {
  return sizeof(ShmHeapHeader);
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

// Prefetch & Chunk State Management
[[nodiscard]] std::uint64_t
shm_heap_prefetch_head(const ShmLayout &layout) noexcept;
[[nodiscard]] std::uint64_t
shm_heap_prefetch_head_by_kind(const ShmLayout &layout,
                               std::uint16_t kind) noexcept;
[[nodiscard]] std::uint64_t
shm_heap_prefetch_head_by_producer(const ShmLayout &layout,
                                   std::uint16_t producer_id) noexcept;

void shm_chunk_mark_dirty(ShmLayout &layout,
                          std::uint64_t payload_offset) noexcept;
void shm_chunk_mark_tombstone(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept;
void shm_chunk_mark_committed(ShmLayout &layout,
                              std::uint64_t payload_offset) noexcept;
[[nodiscard]] bool
shm_chunk_is_visible_for_reader(const ShmLayout &layout,
                                std::uint64_t payload_offset,
                                std::uint64_t reader_epoch) noexcept;
[[nodiscard]] bool
shm_chunk_is_consistent(const ShmLayout &layout,
                        std::uint64_t payload_offset) noexcept;

// Heap Iteration Utilities
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

// Synchronization Epochs & Reader Tracking APIs
[[nodiscard]] std::uint64_t
shm_get_min_active_reader_epoch(const ShmLayout &layout) noexcept;

[[nodiscard]] inline bool shm_has_active_readers(const ShmLayout &layout) noexcept {
  if (layout.header == nullptr) {
    return false;
  }
  
  // Checks active runtimes registered in the cluster runtime IDs array
  for (std::size_t i = 0; i < MAX_RUNTIMES; ++i) {
    if (layout.header->cluster_runtime_ids[i].load(std::memory_order_relaxed) != 0) {
      return true;
    }
  }
  return false;
}

// Prefetching, Quotas & Maintenance
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

void shm_heap_defragment(ShmLayout &layout) noexcept;

void shm_heap_update_prefetch(ShmLayout &layout, std::uint16_t kind) noexcept;

// Journaling, Backpressure & Runtime Tracking
void shm_journal_write(ShmLayout &layout, const BridgeMessage &msg) noexcept;

bool shm_journal_replay_next(ShmLayout &layout, std::uint64_t read_index,
                             BridgeMessage &out) noexcept;

[[nodiscard]] float shm_backpressure_level(const ShmLayout &layout) noexcept;

void shm_register_runtime(ShmLayout &layout, std::uint32_t id) noexcept;

void shm_unregister_runtime(ShmLayout &layout, std::uint32_t id) noexcept;

[[nodiscard]] bool shm_is_runtime_active(const ShmLayout &layout,
                                         std::uint32_t id) noexcept;

[[nodiscard]] inline std::uint64_t shm_numa_hotness(const ShmLayout &layout,
                                                    int node) noexcept {
  const auto *root = reinterpret_cast<const ShmHeapRoot *>(
      reinterpret_cast<const std::byte *>(layout.base) + sizeof(ShmHeader) +
      sizeof(EngineStats));
  if (node < 0 || static_cast<std::size_t>(node) >= KIND_BUCKETS) {
    return 0;
  }
  const std::uint64_t raw_hotness =
      root->hotness_by_kind[node].load(std::memory_order_relaxed);
  return (raw_hotness > 0)
             ? static_cast<std::uint64_t>(std::bit_width(raw_hotness))
             : 0;
}

void shm_extract_tensor_meta(const ShmLayout &layout, const BridgeMessage &msg,
                             void *&ptr, std::size_t &count) noexcept;

void shm_relocate_chunk(ShmLayout &layout, std::uint64_t old_off,
                        std::uint64_t new_off) noexcept;

// RAII Scoped Writer Utility Class
class ScopedChunkWriter {
public:
  ScopedChunkWriter(ShmLayout &layout, std::size_t size, std::uint16_t kind = 0,
                    std::uint16_t producer_id = 0,
                    std::uint16_t encoding = 0) noexcept
      : layout_(&layout), payload_offset_(shm_heap_alloc(
                              layout, size, kind, producer_id, encoding)) {
    if (payload_offset_ != 0) {
      header_ =
          const_cast<Chunk *>(shm_chunk_get_header(*layout_, payload_offset_));
      payload_ptr_ = layout_->base + payload_offset_;
    }
  }

  ~ScopedChunkWriter() noexcept {
    if (!committed_ && header_ != nullptr) {
      header_->flags.store(CHUNK_DIRTY, std::memory_order_release);
    }
  }

  ScopedChunkWriter(const ScopedChunkWriter &) = delete;
  ScopedChunkWriter &operator=(const ScopedChunkWriter &) = delete;

  ScopedChunkWriter(ScopedChunkWriter &&other) noexcept
      : layout_(other.layout_), payload_offset_(other.payload_offset_),
        header_(other.header_), payload_ptr_(other.payload_ptr_),
        committed_(other.committed_) {
    other.payload_offset_ = 0;
    other.header_ = nullptr;
    other.payload_ptr_ = nullptr;
    other.committed_ = true;
  }

  ScopedChunkWriter &operator=(ScopedChunkWriter &&other) noexcept {
    if (this != &other) {
      if (!committed_ && header_ != nullptr) {
        header_->flags.store(CHUNK_DIRTY, std::memory_order_release);
      }

      layout_ = other.layout_;
      payload_offset_ = other.payload_offset_;
      header_ = other.header_;
      payload_ptr_ = other.payload_ptr_;
      committed_ = other.committed_;

      other.payload_offset_ = 0;
      other.header_ = nullptr;
      other.payload_ptr_ = nullptr;
      other.committed_ = true;
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

  void abort() noexcept {
    if (!committed_ && header_ != nullptr) {
      header_->flags.store(CHUNK_DIRTY, std::memory_order_release);
      committed_ = true;
    }
  }

private:
  ShmLayout *layout_{nullptr};
  std::uint64_t payload_offset_{0};
  Chunk *header_{nullptr};
  std::byte *payload_ptr_{nullptr};
  bool committed_{false};
};

} // namespace Sphere
