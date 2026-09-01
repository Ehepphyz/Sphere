// common_config.h

// Compile-time configuration shared by the C++ engine and the Java client.


#pragma once

#include <cstddef>
#include <cstdint>

namespace Sphere {

// -----------------------------------------------------------------------------
// Hardware & Alignment Constants (Shared Memory ABI)
// -----------------------------------------------------------------------------

inline constexpr std::size_t CACHE_LINE_SIZE = 64;

#if defined(__APPLE__) && defined(__aarch64__)
inline constexpr std::size_t HARDWARE_CACHE_LINE_SIZE = 128;
#else
inline constexpr std::size_t HARDWARE_CACHE_LINE_SIZE = 64;
#endif

// AVX-512 friendly alignment used for decompressed payload placement.
inline constexpr std::size_t SIMD_ALIGNMENT = 128;

// -----------------------------------------------------------------------------
// Capacity, Ring Exponents, Quotas & Runtime Limits
// -----------------------------------------------------------------------------

// Maximum number of active runtime clients/workers that can register
// concurrently.
inline constexpr std::size_t MAX_RUNTIMES = 64;

// Command ring capacity
inline constexpr std::size_t CMD_RING_CAPACITY_POW2 = 10;
inline constexpr std::size_t CMD_RING_CAPACITY = 1ULL << CMD_RING_CAPACITY_POW2;

// Event ring capacity
inline constexpr std::size_t EVT_RING_CAPACITY_POW2 = 10;
inline constexpr std::size_t EVT_RING_CAPACITY = 1ULL << EVT_RING_CAPACITY_POW2;

// Default capacity in items for standard lock-free ring buffers.
inline constexpr std::size_t DEFAULT_RING_CAPACITY = 1024;

// Number of slots in the shared-memory transaction journal.
inline constexpr std::size_t JOURNAL_CAPACITY = 1024;

// Telemetry span ring capacity
inline constexpr std::uint64_t SPAN_RING_CAPACITY = 4096;

inline constexpr std::uint64_t DEFAULT_KIND_QUOTA = 65536;

// Default admission quota per producer runtime
inline constexpr std::uint64_t DEFAULT_PRODUCER_QUOTA = 65536;

// -----------------------------------------------------------------------------
// Protocol & Shared Memory Magic Numbers (Shared Memory ABI)
// -----------------------------------------------------------------------------

// Shared memory header magic signature ('SPHR').
inline constexpr std::uint32_t SHM_MAGIC = 0x53504852;

// Shared memory layout version.

inline constexpr std::uint32_t SHM_VERSION = 2;

// Wire protocol version for PacketHeader
inline constexpr std::uint32_t PROTO_VERSION = 2;

// Individual chunk header magic signature
inline constexpr std::uint32_t CHUNK_MAGIC = 0x43484E4B;

// -----------------------------------------------------------------------------
// Shared Memory Default Layout Constants
// -----------------------------------------------------------------------------

inline constexpr const char *SHM_NAME = "root_backend.shm";

// Total backing size of the shared memory mapping (256 MiB).
inline constexpr std::size_t SHM_SIZE = 256 * 1024 * 1024;

// Size of the schema partition, in bytes.
inline constexpr std::size_t SCHEMA_HEAP_SIZE = 1 * 1024 * 1024;

// Number of hash buckets for grouping memory chunks by message kind.
inline constexpr std::size_t KIND_BUCKETS = 16;

// Number of hash buckets for grouping memory chunks by producer runtime id.
inline constexpr std::size_t PRODUCER_BUCKETS = 16;

// -----------------------------------------------------------------------------
// Hot-Path Execution Settings
// -----------------------------------------------------------------------------

// Maximum batch size processed by an engine worker in a single iteration.
inline constexpr std::size_t ENGINE_WORKER_BATCH_MAX = 16;

// Idle backoff bounds for the scheduler and worker loops
inline constexpr std::uint32_t IDLE_BACKOFF_MIN_US = 2;
inline constexpr std::uint32_t IDLE_BACKOFF_MAX_US = 2000;

} // namespace Sphere
