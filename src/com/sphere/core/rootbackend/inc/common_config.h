// common_config.h
#pragma once

#include <cstddef>
#include <cstdint>

namespace Sphere {

// -----------------------------------------------------------------------------
// Hardware & Alignment Constants
// -----------------------------------------------------------------------------

// CPU cache line size (64 bytes on x86_64 and ARM64); used for memory alignment
// to prevent false sharing.
inline constexpr std::size_t CACHE_LINE_SIZE = 64;

// -----------------------------------------------------------------------------
// Capacity, Ring Exponents, Quotas & Runtime Limits
// -----------------------------------------------------------------------------

// Maximum number of active runtime clients/workers that can register
// concurrently.
inline constexpr std::size_t MAX_RUNTIMES = 64;

// Command ring capacity expressed as a power of 2 (2^10 = 1024 message slots).
inline constexpr std::size_t CMD_RING_CAPACITY_POW2 = 10;

// Event ring capacity expressed as a power of 2 (2^10 = 1024 message slots).
inline constexpr std::size_t EVT_RING_CAPACITY_POW2 = 10;

// Default capacity in items for standard lock-free ring buffers.
inline constexpr std::size_t DEFAULT_RING_CAPACITY = 1024;

// Maximum number of slots in the lock-free shared memory event/audit journal.
inline constexpr std::size_t JOURNAL_CAPACITY = 1024;

// Default byte quota allocated per message category/kind for rate-limiting.
inline constexpr std::uint64_t DEFAULT_KIND_QUOTA = 1024;

// Default byte quota allocated per producer runtime for memory usage tracking.
inline constexpr std::uint64_t DEFAULT_PRODUCER_QUOTA = 1024;

// -----------------------------------------------------------------------------
// Protocol & Shared Memory Magic Numbers
// -----------------------------------------------------------------------------

// Shared memory header magic signature ('SPHR') used to validate region
// initialization.
inline constexpr std::uint32_t SHM_MAGIC = 0x53504852;

// Shared memory layout version number for ABI compatibility verification.
inline constexpr std::uint32_t SHM_VERSION = 1;

// Individual chunk header magic signature ('CHNK') used to detect memory
// corruption.
inline constexpr std::uint32_t CHUNK_MAGIC = 0x43484E4B;

// -----------------------------------------------------------------------------
// Shared Memory Default Layout Constants
// -----------------------------------------------------------------------------

// Default System V / POSIX shared memory object name path.
inline constexpr const char *SHM_NAME = "/sphere_shm_region";

// Total default backing size of the shared memory mapping (64 Megabytes).
inline constexpr std::size_t SHM_SIZE = 64 * 1024 * 1024;

// Number of hash/lookup buckets for grouping memory chunks by message kind.
inline constexpr std::size_t KIND_BUCKETS = 16;

// Number of hash/lookup buckets for grouping memory chunks by producer runtime
// ID.
inline constexpr std::size_t PRODUCER_BUCKETS = 16;

// -----------------------------------------------------------------------------
// Hot-Path Execution Settings
// -----------------------------------------------------------------------------

// Maximum batch size processed by an engine worker in a single iteration (zero
// allocations).
inline constexpr std::size_t ENGINE_WORKER_BATCH_MAX = 16;

} // namespace Sphere
