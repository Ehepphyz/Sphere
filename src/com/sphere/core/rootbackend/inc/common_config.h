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
// Heap Size Classes
// -----------------------------------------------------------------------------

// Freed blocks return to the list for their size class instead of being
// abandoned. Each class carries a power-of-two payload plus room for the chunk
// header, so a payload that is itself a round power of two does not spill into
// the class above and waste half the block.
inline constexpr std::size_t HEAP_SIZE_CLASSES = 14;

inline constexpr std::size_t HEAP_CLASS_HEADROOM = 128;

inline constexpr std::size_t HEAP_CLASS_BYTES[HEAP_SIZE_CLASSES] = {
    (1ULL << 12) + HEAP_CLASS_HEADROOM,  // 4 KiB
    (1ULL << 14) + HEAP_CLASS_HEADROOM,  // 16 KiB
    (1ULL << 15) + HEAP_CLASS_HEADROOM,  // 32 KiB, default TTree basket
    (1ULL << 16) + HEAP_CLASS_HEADROOM,  // 64 KiB, common RNTuple page
    (1ULL << 17) + HEAP_CLASS_HEADROOM,  // 128 KiB
    (1ULL << 18) + HEAP_CLASS_HEADROOM,  // 256 KiB
    (1ULL << 19) + HEAP_CLASS_HEADROOM,  // 512 KiB
    (1ULL << 20) + HEAP_CLASS_HEADROOM,  // 1 MiB
    (1ULL << 22) + HEAP_CLASS_HEADROOM,  // 4 MiB
    (1ULL << 24) + HEAP_CLASS_HEADROOM,  // 16 MiB
    (1ULL << 26) + HEAP_CLASS_HEADROOM,  // 64 MiB
    (1ULL << 27) + HEAP_CLASS_HEADROOM,  // 128 MiB
    (1ULL << 28) + HEAP_CLASS_HEADROOM,  // 256 MiB
    (1ULL << 29) + HEAP_CLASS_HEADROOM   // 512 MiB, whole-column reads
};

// A block larger than the last class cannot be recycled and is refused.
inline constexpr std::size_t HEAP_MAX_PAYLOAD =
    HEAP_CLASS_BYTES[HEAP_SIZE_CLASSES - 1] - HEAP_CLASS_HEADROOM;

// -----------------------------------------------------------------------------
// Protocol & Shared Memory Magic Numbers (Shared Memory ABI)
// -----------------------------------------------------------------------------

// Shared memory header magic signature ('SPHR').
inline constexpr std::uint32_t SHM_MAGIC = 0x53504852;

// Shared memory layout version.

inline constexpr std::uint32_t SHM_VERSION = 3;

// Wire protocol version for PacketHeader
inline constexpr std::uint32_t PROTO_VERSION = 3;

// Individual chunk header magic signature
inline constexpr std::uint32_t CHUNK_MAGIC = 0x43484E4B;

// -----------------------------------------------------------------------------
// Shared Memory Default Layout Constants
// -----------------------------------------------------------------------------

inline constexpr const char *SHM_NAME = "root_backend.shm";

// Total backing size of the shared memory mapping (1 GiB).
inline constexpr std::size_t SHM_SIZE = 1024ULL * 1024ULL * 1024ULL;

// Chunk offsets travel in a uint32 counted in units of this many bytes, which
// lifts the addressable region from 4 GiB to 256 GiB. Chunks are cache-line
// aligned anyway, so nothing is lost to the shift.
inline constexpr std::uint32_t SHM_OFFSET_SHIFT = 6;
inline constexpr std::uint64_t SHM_MAX_ADDRESSABLE =
    (1ULL << 32) << SHM_OFFSET_SHIFT;

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
