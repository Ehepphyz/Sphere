// utils.h
#pragma once

#include <algorithm>
#include <array>
#include <bit>
#include <cctype>
#include <chrono>
#include <concepts>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <span>
#include <string>
#include <string_view>

// ============================================================================
// Architectural Intrinsics & Target Identification
// ============================================================================
#if defined(__x86_64__) || defined(_M_X64)
#ifndef ARCH_X86_64
#define ARCH_X86_64 1
#endif
#if defined(_MSC_VER)
#include <intrin.h>
#else
#include <cpuid.h>
#include <x86intrin.h>
#endif
#include <immintrin.h>

#elif defined(__aarch64__) || defined(_M_ARM64)
#ifndef ARCH_ARM64
#define ARCH_ARM64 1
#endif
#include <arm_neon.h>
#if defined(__ARM_FEATURE_SVE) || defined(__ARM_SVE) ||                        \
    defined(__ARM_FEATURE_SVE2) || defined(HAS_SVE_HEADERS)
#include <arm_sve.h>
#ifndef HAS_SVE_HEADERS
#define HAS_SVE_HEADERS 1
#endif
#endif
#endif

#if defined(_MSC_VER) || defined(__MINGW32__)
#include <malloc.h>
#endif

// ============================================================================
// Compiler-Specific Optimization & Inline Directives
// ============================================================================
#if defined(_MSC_VER)
#define ALWAYS_INLINE __forceinline
#define NEVER_INLINE __declspec(noinline)
#elif defined(__GNUC__) || defined(__clang__)
#define ALWAYS_INLINE inline __attribute__((always_inline))
#define NEVER_INLINE __attribute__((noinline))
#else
#define ALWAYS_INLINE inline
#define NEVER_INLINE
#endif

// Function Cloning Directives (GNU IFUNC / Automatic Multi-Version)
// Formatted into a single sequence string to maintain Clang compatibility
// (-Wtarget-clones-mixed-specifiers)
#if defined(__GNUC__) && !defined(__APPLE__) && defined(__x86_64__)
#define TARGET_CLONES_X86(...) __attribute__((target_clones(__VA_ARGS__)))
#else
#define TARGET_CLONES_X86(...)
#endif

#define CLONE_X86_SIMD                                                         \
  TARGET_CLONES_X86("avx512f,avx512bw,avx512vl,avx2,bmi2,default")

// Target Attributes for Manual SIMD Intrinsics
#if (defined(__GNUC__) || defined(__clang__)) && defined(ARCH_X86_64)
#define TARGET_AVX2 __attribute__((target("avx2,bmi,bmi2,lzcnt,fma")))
#define TARGET_AVX512                                                          \
  __attribute__((target("avx512f,avx512bw,avx512cd,avx512dq,avx512vl")))
#define TARGET_NEON
#define TARGET_SVE
#elif (defined(__GNUC__) || defined(__clang__)) && defined(ARCH_ARM64)
#define TARGET_AVX2
#define TARGET_AVX512
#define TARGET_NEON __attribute__((target("arch=armv8-a+simd")))
#if defined(HAS_SVE_HEADERS)
#define TARGET_SVE __attribute__((target("arch=armv8-a+sve")))
#else
#define TARGET_SVE
#endif
#else
#define TARGET_AVX2
#define TARGET_AVX512
#define TARGET_NEON
#define TARGET_SVE
#endif

namespace utils {

// ============================================================================
// Hardware & CPUID Capability Discovery
// ============================================================================

struct CPUCapabilities {
  bool has_avx2 = false;
  bool has_avx512f = false;
  bool has_avx512bw = false;
  bool has_neon = false;
  bool has_sve = false;
  bool has_bmi2 = false;
};

// Functions defined in utils.cpp (must NOT be marked inline here)
CPUCapabilities detect_cpu_capabilities() noexcept;
[[nodiscard]] const CPUCapabilities &get_cpu_capabilities() noexcept;

// ============================================================================
// Memory Alignment Helpers & Custom Deleters
// ============================================================================

constexpr std::size_t DEFAULT_SIMD_ALIGNMENT = 64;

template <typename T>
ALWAYS_INLINE bool
is_aligned(const T *ptr,
           std::size_t alignment = DEFAULT_SIMD_ALIGNMENT) noexcept {
  return (reinterpret_cast<std::uintptr_t>(ptr) % alignment) == 0;
}

// Memory allocation prototypes implemented in utils.cpp
void *aligned_alloc_simd(std::size_t alignment, std::size_t size) noexcept;
void aligned_free_simd(void *ptr) noexcept;

template <typename T> struct AlignedDeleter {
  void operator()(T *ptr) const noexcept {
    if (ptr) {
      ptr->~T();
      aligned_free_simd(ptr);
    }
  }
};

template <typename T, typename... Args>
[[nodiscard]] std::unique_ptr<T, AlignedDeleter<T>>
make_aligned_unique(std::size_t alignment, Args &&...args) {
  void *const mem = aligned_alloc_simd(alignment, sizeof(T));
  if (!mem) {
    throw std::bad_alloc();
  }

  try {
    T *const ptr = ::new (mem) T(std::forward<Args>(args)...);
    return std::unique_ptr<T, AlignedDeleter<T>>(ptr);
  } catch (...) {
    aligned_free_simd(mem);
    throw;
  }
}

// ============================================================================
// Low-Latency Utilities & Cache Prefetching
// ============================================================================

ALWAYS_INLINE void prefetch_read(const void *addr) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  __builtin_prefetch(addr, 0, 3);
#elif defined(ARCH_X86_64) && defined(_MSC_VER)
  _mm_prefetch(static_cast<const char *>(addr), _MM_HINT_T0);
#else
  (void)addr;
#endif
}

ALWAYS_INLINE void prefetch_write(const void *addr) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  __builtin_prefetch(addr, 1, 3);
#elif defined(ARCH_X86_64) && defined(_MSC_VER)
  _mm_prefetch(static_cast<const char *>(addr), _MM_HINT_T0);
#else
  (void)addr;
#endif
}

ALWAYS_INLINE void cpu_relax() noexcept {
#if defined(ARCH_X86_64)
#if defined(_MSC_VER)
  _mm_pause();
#else
  asm volatile("pause" ::: "memory");
#endif
#elif defined(ARCH_ARM64)
#if defined(_MSC_VER)
  __yield();
#else
  asm volatile("yield" ::: "memory");
#endif
#endif
}

template <std::integral T>
constexpr T align_up(T value, std::size_t alignment) noexcept {
  return static_cast<T>((static_cast<std::size_t>(value) + alignment - 1) &
                        ~(alignment - 1));
}

// ============================================================================
// String & UTF-8 Utilities (Zero-Copy & SIMD-Accelerated)
// ============================================================================

void to_lower_simd(char *str, std::size_t len) noexcept;
void to_upper_simd(char *str, std::size_t len) noexcept;

ALWAYS_INLINE void to_lower_inplace(std::span<char> buffer) noexcept {
  to_lower_simd(buffer.data(), buffer.size());
}

ALWAYS_INLINE void to_upper_inplace(std::span<char> buffer) noexcept {
  to_upper_simd(buffer.data(), buffer.size());
}

ALWAYS_INLINE std::string to_lower(std::string_view s) {
  std::string result(s);
  to_lower_simd(result.data(), result.size());
  return result;
}

ALWAYS_INLINE std::string to_upper(std::string_view s) {
  std::string result(s);
  to_upper_simd(result.data(), result.size());
  return result;
}

ALWAYS_INLINE std::string_view trim_view(std::string_view s) noexcept {
  while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) {
    s.remove_prefix(1);
  }
  while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) {
    s.remove_suffix(1);
  }
  return s;
}

ALWAYS_INLINE std::string trim(std::string_view s) {
  return std::string(trim_view(s));
}

ALWAYS_INLINE bool starts_with(std::string_view s,
                               std::string_view prefix) noexcept {
  return s.starts_with(prefix);
}

ALWAYS_INLINE bool ends_with(std::string_view s,
                             std::string_view suffix) noexcept {
  return s.ends_with(suffix);
}

bool utf8_validate_simd(const char *str, std::size_t len) noexcept;

ALWAYS_INLINE bool utf8_validate(std::string_view s) noexcept {
  return utf8_validate_simd(s.data(), s.size());
}

#if defined(ARCH_X86_64)
TARGET_AVX512 void to_lower_avx512_inplace(char *str, std::size_t len) noexcept;
TARGET_AVX512 void to_upper_avx512_inplace(char *str, std::size_t len) noexcept;
TARGET_AVX2 void to_lower_avx2_inplace(char *str, std::size_t len) noexcept;
TARGET_AVX2 void to_upper_avx2_inplace(char *str, std::size_t len) noexcept;
#elif defined(ARCH_ARM64)
TARGET_NEON void to_lower_neon_inplace(char *str, std::size_t len) noexcept;
TARGET_NEON void to_upper_neon_inplace(char *str, std::size_t len) noexcept;
#endif

// ============================================================================
// Hashing Algorithms (FNV-1a, xxHash64, HighwayHash)
// ============================================================================

constexpr std::uint64_t hash_string_constexpr(std::string_view str) noexcept {
  std::uint64_t hash = 14695981039346656037ULL;
  for (char c : str) {
    hash ^= static_cast<std::uint8_t>(c);
    hash *= 1099511628211ULL;
  }
  return hash;
}

constexpr std::uint64_t operator""_hash(const char *str,
                                        std::size_t len) noexcept {
  return hash_string_constexpr(std::string_view{str, len});
}

std::uint64_t hash_bytes(const void *input, std::size_t len,
                         std::uint64_t seed = 0) noexcept;

ALWAYS_INLINE std::uint64_t hash_string(const char *str,
                                        std::size_t len) noexcept {
  return hash_bytes(str, len, 0);
}

ALWAYS_INLINE std::uint64_t hash_string(const std::string &str) noexcept {
  return hash_bytes(str.data(), str.size(), 0);
}

ALWAYS_INLINE std::uint64_t hash_string_runtime(std::string_view str) noexcept {
  return hash_bytes(str.data(), str.size(), 0);
}

ALWAYS_INLINE std::uint64_t hash_fnv1a_runtime(std::string_view str) noexcept {
  return hash_string_constexpr(str);
}

ALWAYS_INLINE std::uint64_t hash_xxhash64(const void *data, std::size_t size,
                                          std::uint64_t seed = 0) noexcept {
  return hash_bytes(data, size, seed);
}

#if defined(ARCH_X86_64)
[[nodiscard]] TARGET_AVX2 std::uint64_t
hash_xxhash64_avx2(const void *input, std::size_t len,
                   std::uint64_t seed) noexcept;
[[nodiscard]] TARGET_AVX2 std::uint64_t
hash_highway64_avx2(const void *input, std::size_t len,
                    const std::uint64_t key[4]) noexcept;
#elif defined(ARCH_ARM64)
TARGET_NEON
std::uint64_t hash_xxhash64_neon(const void *input, std::size_t len,
                                 std::uint64_t seed) noexcept;
#if defined(HAS_SVE_HEADERS)
TARGET_SVE
std::uint64_t hash_xxhash64_sve(const void *input, std::size_t len,
                                std::uint64_t seed) noexcept;
#endif
#endif

ALWAYS_INLINE std::uint64_t
hash_highway64(const void *data, std::size_t size,
               const std::array<std::uint64_t, 4> &key) noexcept {
#if defined(ARCH_X86_64)
  if (get_cpu_capabilities().has_avx2) {
    return hash_highway64_avx2(data, size, key.data());
  }
#endif
  return hash_bytes(data, size, key[0]);
}

// Multi-String Batch Hashing Overloads
ALWAYS_INLINE void hash_string_multi(std::span<const std::string_view> inputs,
                                     std::span<std::uint64_t> hashes) noexcept {
  const std::size_t count = std::min(inputs.size(), hashes.size());
  for (std::size_t i = 0; i < count; ++i) {
    hashes[i] = hash_string_runtime(inputs[i]);
  }
}

ALWAYS_INLINE void hash_fnv1a_multi(std::span<const std::string_view> inputs,
                                    std::span<std::uint64_t> hashes) noexcept {
  const std::size_t count = std::min(inputs.size(), hashes.size());
  for (std::size_t i = 0; i < count; ++i) {
    hashes[i] = hash_fnv1a_runtime(inputs[i]);
  }
}

ALWAYS_INLINE void hash_xxhash64_multi(std::span<const std::string_view> inputs,
                                       std::span<std::uint64_t> hashes,
                                       std::uint64_t seed = 0) noexcept {
  const std::size_t count = std::min(inputs.size(), hashes.size());
  for (std::size_t i = 0; i < count; ++i) {
    hashes[i] = hash_xxhash64(inputs[i].data(), inputs[i].size(), seed);
  }
}

ALWAYS_INLINE void
hash_highway64_multi(std::span<const std::string_view> inputs,
                     std::span<std::uint64_t> hashes,
                     const std::array<std::uint64_t, 4> &key) noexcept {
  const std::size_t count = std::min(inputs.size(), hashes.size());
  for (std::size_t i = 0; i < count; ++i) {
    hashes[i] = hash_highway64(inputs[i].data(), inputs[i].size(), key);
  }
}

// ============================================================================
// Multi-Buffer Processing Infrastructure (N-Way Engine)
// ============================================================================

template <std::size_t NWay> class MultiBufferEngine {
public:
  static_assert(NWay == 2 || NWay == 4 || NWay == 8 || NWay == 16,
                "NWay must be a power of 2 suited for SIMD registers");

  template <typename Op, typename TailOp>
  static void process(std::array<std::span<const std::uint8_t>, NWay> inputs,
                      Op &&simd_op, TailOp &&tail_op) noexcept {
    std::array<const std::uint8_t *, NWay> ptrs;
    std::array<std::size_t, NWay> remaining;

    for (std::size_t i = 0; i < NWay; ++i) {
      ptrs[i] = inputs[i].data();
      remaining[i] = inputs[i].size();
    }

    std::size_t min_len = *std::min_element(remaining.begin(), remaining.end());
    std::size_t offset = 0;

    constexpr std::size_t CHUNK_SIZE = 4;

    while (offset + CHUNK_SIZE <= min_len) {
      alignas(32) std::uint32_t chunk_data[NWay];

      for (std::size_t i = 0; i < NWay; ++i) {
        std::memcpy(&chunk_data[i], ptrs[i] + offset, sizeof(std::uint32_t));
      }

      simd_op(chunk_data, offset);
      offset += CHUNK_SIZE;
    }

    for (std::size_t i = 0; i < NWay; ++i) {
      std::size_t processed = offset;
      std::size_t tail_len = remaining[i] - processed;
      if (tail_len > 0) {
        tail_op(i, ptrs[i] + processed, tail_len);
      }
    }
  }
};

// ============================================================================
// SIMD Memory Operations & Vector Processing
// ============================================================================

#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
TARGET_AVX2 void memcpy_simd_avx2(void *dest, const void *src,
                                  std::size_t n) noexcept;
TARGET_AVX2 void memset_simd_avx2(void *dest, int val, std::size_t n) noexcept;
TARGET_AVX2 int memcmp_simd_avx2(const void *s1, const void *s2,
                                 std::size_t n) noexcept;
#elif defined(ARCH_ARM64) || defined(__aarch64__)
TARGET_NEON void memcpy_simd_neon(void *dest, const void *src,
                                  std::size_t n) noexcept;
TARGET_NEON void memset_simd_neon(void *dest, int val, std::size_t n) noexcept;
TARGET_NEON int memcmp_simd_neon(const void *s1, const void *s2,
                                 std::size_t n) noexcept;
#endif

int memcmp_simd(const void *a, const void *b, std::size_t size) noexcept;
void memcpy_simd(void *dst, const void *src, std::size_t size) noexcept;
void memset_simd(void *dst, int value, std::size_t size) noexcept;

void *memcpy_adaptive(void *dest, const void *src, std::size_t n) noexcept;
void *memset_adaptive(void *dest, int v, std::size_t n) noexcept;
int memcmp_adaptive(const void *a, const void *b, std::size_t n) noexcept;

TARGET_AVX512
void tensor_process_avx512(float *__restrict__ ptr, std::size_t count,
                           float factor = 1.0f) noexcept;

TARGET_SVE
void tensor_process_sve(float *__restrict__ ptr, std::size_t count,
                        float factor = 1.0f) noexcept;

void tensor_process_simd(void *raw, std::size_t bytes,
                         float factor = 1.0f) noexcept;

// ============================================================================
// Distribution, Hashing & Routing Operations
// ============================================================================

[[nodiscard]] inline std::uint64_t
utils_prefetch_hotness(std::uint64_t hot) noexcept {
  hot ^= hot >> 33;
  hot *= 0xff51afd7ed558ccdULL;
  hot ^= hot >> 33;
  hot *= 0xc4ceb9fe1a85ec53ULL;
  hot ^= hot >> 33;
  return hot;
}

[[nodiscard]] std::size_t utils_numa_hash(std::uint64_t key,
                                          std::size_t nodes) noexcept;

[[nodiscard]] inline float
utils_backpressure_smooth(float level, float &prev_state,
                          float alpha = 0.5f) noexcept {
  level = std::clamp(level, 0.0f, 1.0f);
  alpha = std::clamp(alpha, 0.001f, 1.0f);

  const float out = prev_state + alpha * (level - prev_state);
  prev_state = out;
  return out;
}

[[nodiscard]] inline std::uint64_t
utils_mix_journal_seq(std::uint64_t seq) noexcept {
  seq ^= seq >> 30;
  seq *= 0xBF58476D1CE4E5B9ULL;
  seq ^= seq >> 27;
  seq *= 0x94D049BB133111EBULL;
  seq ^= seq >> 31;
  return seq;
}

[[nodiscard]] inline bool
utils_should_steal(std::uint64_t local, std::uint64_t remote,
                   std::uint64_t min_threshold = 4) noexcept {
  if (remote <= local || (remote - local) < min_threshold) {
    return false;
  }
  return remote > (local * 2);
}

[[nodiscard]] inline std::uint32_t
utils_cluster_hash(std::uint32_t id) noexcept {
  id ^= id >> 16;
  id *= 0x85ebca6bU;
  id ^= id >> 13;
  id *= 0xc2b2ae35U;
  id ^= id >> 16;
  return id;
}

// ============================================================================
// High-Resolution Timers & Hardware Counters
// ============================================================================

class CycleTimer {
public:
  CycleTimer() noexcept;

  // Time conversion static helpers required by external execution contexts
  static ALWAYS_INLINE std::uint64_t
  ns_to_tsc(std::uint64_t ns) noexcept {
#if defined(__SIZEOF_INT128__)
    // Use the calibration constant if accessible via a global instance
    // Otherwise, use fallback conversion via steady_clock or estimated
    // frequency:
    return static_cast<std::uint64_t>(
        ns * 2.5); // Adjust according to the target frequency factor if fixed
#else
    return static_cast<std::uint64_t>(ns * 2.5);
#endif
  }

  static ALWAYS_INLINE std::uint64_t
  tsc_to_ns(std::uint64_t cycles) noexcept {
    return static_cast<std::uint64_t>(cycles / 2.5);
  }

  static ALWAYS_INLINE std::uint64_t rdtsc() noexcept {
#if defined(ARCH_X86_64)
#if defined(_MSC_VER)
    return __rdtsc();
#else
    std::uint32_t low, high;
    asm volatile("rdtsc" : "=a"(low), "=d"(high)::"memory");
    return (static_cast<std::uint64_t>(high) << 32) | low;
#endif
#elif defined(ARCH_ARM64)
#if defined(_MSC_VER)
    return static_cast<std::uint64_t>(__readcntvct());
#else
    std::uint64_t val;
    asm volatile("isb; mrs %0, cntvct_el0" : "=r"(val)::"memory");
    return val;
#endif
#else
    return static_cast<std::uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
#endif
  }

  static ALWAYS_INLINE std::uint64_t rdtsc_fenced() noexcept {
#if defined(ARCH_X86_64)
#if defined(_MSC_VER)
    _mm_lfence();
    return __rdtsc();
#else
    std::uint32_t low, high;
    asm volatile("lfence\n\trdtsc" : "=a"(low), "=d"(high)::"memory");
    return (static_cast<std::uint64_t>(high) << 32) | low;
#endif
#else
    return rdtsc();
#endif
  }

  ALWAYS_INLINE void start() noexcept { start_cycles_ = rdtsc_fenced(); }

  ALWAYS_INLINE std::uint64_t stop_cycles() const noexcept {
    const std::uint64_t end = rdtsc_fenced();
    return (end > start_cycles_) ? (end - start_cycles_) : 0;
  }

  ALWAYS_INLINE std::uint64_t stop_ns() const noexcept {
    const std::uint64_t elapsed = stop_cycles();
#if defined(__SIZEOF_INT128__)
    return static_cast<std::uint64_t>(
        (static_cast<unsigned __int128>(elapsed) * ns_per_cycle_fp_) >> 32);
#else
    const std::uint64_t elapsed_hi = elapsed >> 32;
    const std::uint64_t elapsed_lo = elapsed & 0xFFFFFFFFULL;
    const std::uint64_t fp_hi = ns_per_cycle_fp_ >> 32;
    const std::uint64_t fp_lo = ns_per_cycle_fp_ & 0xFFFFFFFFULL;

    const std::uint64_t res_mid =
        (elapsed_lo * fp_hi) + ((elapsed_lo * fp_lo) >> 32);
    return (elapsed_hi * ns_per_cycle_fp_) + (elapsed_lo * fp_hi) +
           (res_mid >> 32);
#endif
  }

  ALWAYS_INLINE double stop_ms() const noexcept {
    return static_cast<double>(stop_ns()) / 1e6;
  }

  ALWAYS_INLINE std::uint64_t elapsed_ns() const noexcept { return stop_ns(); }

  ALWAYS_INLINE double get_frequency_ghz() const noexcept {
    if (ns_per_cycle_fp_ == 0)
      return 0.0;
    const double ns_per_cycle =
        static_cast<double>(ns_per_cycle_fp_) / static_cast<double>(1ULL << 32);
    return 1.0 / ns_per_cycle;
  }

private:
  void calibrate() noexcept;

  std::uint64_t start_cycles_{0};
  std::uint64_t ns_per_cycle_fp_{0};
};

ALWAYS_INLINE std::uint64_t rdtsc() noexcept { return CycleTimer::rdtsc(); }

ALWAYS_INLINE std::uint64_t rdtsc_fenced() noexcept {
  return CycleTimer::rdtsc_fenced();
}

ALWAYS_INLINE std::uint64_t now_ns() noexcept {
  return static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
}

ALWAYS_INLINE std::uint64_t now_ms() noexcept {
  return static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
}

} // namespace utils
