/**
 * utils.cpp
 * High-performance multi-architecture SIMD utilities, hashing,
 * UTF-8 processing, string operations, and cycle timing.
 */

#include "utils.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <type_traits>
#include <vector>

// ============================================================================
// Platform & Architecture Detection
// ============================================================================
#if defined(__x86_64__) || defined(_M_X64)
#define ARCH_X86_64 1
#if defined(_MSC_VER)
#include <intrin.h>
#else
#include <cpuid.h>
#include <x86intrin.h>
#endif
#include <immintrin.h>

#elif defined(__aarch64__) || defined(_M_ARM64)
#define ARCH_ARM64 1
#include <arm_neon.h>

// Include ARM SVE headers only when targeting ARM64 with SVE support enabled
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
// Platform-Independent Inline & Optimization Macros
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

// ============================================================================
// Function Cloning & Multi-Version Dispatching (GNU IFUNC)
// ============================================================================
// GNU/Clang target_clones attribute allows automatic IFUNC resolver generation
// for x86_64 targets on ELF systems (Linux).
#if defined(__GNUC__) && !defined(__APPLE__) && defined(__x86_64__)
#define TARGET_CLONES_X86(...) __attribute__((target_clones(__VA_ARGS__)))
#else
#define TARGET_CLONES_X86(...)
#endif

// Macro shortcut for standard x86_64 multi-versioning paths
#define CLONE_X86_SIMD                                                         \
  TARGET_CLONES_X86("avx512f,avx512bw,avx512vl,avx2,bmi2,default")

// ============================================================================
// Legacy Specific Target Attributes (For Manual SIMD Intrinsic Blocks)
// ============================================================================
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

/**
 * L1/L2 cache prefetching
 */
ALWAYS_INLINE void prefetch_memory(const void *ptr) noexcept {
#if defined(__GNUC__) || defined(__clang__)

  __builtin_prefetch(ptr, 0, 3);
#elif defined(_M_ARM64)
  // MSVC ARM64 intrinsic
  __prefetch(ptr);
#elif defined(_M_X64) || defined(_M_IX86)
  // MSVC x86/x64 intrinsic
  _mm_prefetch(reinterpret_cast<const char *>(ptr), _MM_HINT_T0);
#else
  // Fallback for unsupported compilers or platforms
  (void)ptr;
#endif
}

// ============================================================================
// MEMORY ALIGNMENT HELPERS
// ============================================================================

constexpr std::size_t DEFAULT_SIMD_ALIGNMENT = 64;

template <typename T>
ALWAYS_INLINE bool
is_aligned(const T *ptr,
           std::size_t alignment = DEFAULT_SIMD_ALIGNMENT) noexcept {
  return (reinterpret_cast<std::uintptr_t>(ptr) % alignment) == 0;
}

void *aligned_alloc_simd(std::size_t alignment, std::size_t size) noexcept {
  // Return nullptr for zero-sized allocations or invalid zero alignments
  if (size == 0 || alignment == 0) {
    return nullptr;
  }

  // Ensure alignment meets the minimum requirement (sizeof(void*))
  constexpr std::size_t kMinAlignment = sizeof(void *);
  if (alignment < kMinAlignment) {
    alignment = kMinAlignment;
  }

  // Ensure alignment is a valid power of two
  if ((alignment & (alignment - 1)) != 0) {
    return nullptr;
  }

#if defined(_MSC_VER) || defined(__MINGW32__)
  // Windows / MSVC implementation
  return _aligned_malloc(size, alignment);

#elif (defined(_POSIX_C_SOURCE) && (_POSIX_C_SOURCE >= 200112L)) ||            \
    defined(__APPLE__) || defined(__FreeBSD__)
  // POSIX / macOS / FreeBSD implementation
  void *ptr = nullptr;
  if (posix_memalign(&ptr, alignment, size) != 0) {
    return nullptr;
  }
  return ptr;

#else
  const std::size_t remainder = size % alignment;
  if (remainder != 0) {
    const std::size_t padding = alignment - remainder;

    // Prevent potential integer overflow during size padding
    if (size > (std::numeric_limits<std::size_t>::max)() - padding) {
      return nullptr;
    }
    size += padding;
  }

  return std::aligned_alloc(alignment, size);
#endif
}

void aligned_free_simd(void *ptr) noexcept {
  if (!ptr) {
    return;
  }

#if defined(_MSC_VER) || defined(__MINGW32__)
  _aligned_free(ptr);
#else
  std::free(ptr);
#endif
}

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
    // Construct the object in the allocated aligned buffer
    T *const ptr = ::new (mem) T(std::forward<Args>(args)...);
    return std::unique_ptr<T, AlignedDeleter<T>>(ptr);
  } catch (...) {
    // Safely free the raw memory buffer if construction throws an exception
    aligned_free_simd(mem);
    throw;
  }
}

// ============================================================================
// 2. CYCLE TIMER IMPLEMENTATION & TIME UTILITIES
// ============================================================================

// CycleTimer is declared in utils.h; its out-of-line members are defined near
// the bottom of this file, after the CPU capability helpers they depend on.

// ============================================================================
// 3. HARDWARE & CPUID DISCOVERY UTILITIES
// ============================================================================

// CPUCapabilities is declared in utils.h.

CPUCapabilities detect_cpu_capabilities() noexcept {
  CPUCapabilities caps;

#if defined(ARCH_X86_64)
#if defined(_MSC_VER)
  int cpu_info[4] = {0};

  // Query leaf 1 to check OSXSAVE and AVX support
  __cpuid(cpu_info, 1);
  caps.has_sse42 = (cpu_info[2] & (1 << 20)) != 0;
  const bool osxsave = (cpu_info[2] & (1 << 27)) != 0;
  const bool avx = (cpu_info[2] & (1 << 28)) != 0;

  if (osxsave && avx) {
    // Read extended control register XCR0 to verify OS register context
    const unsigned long long xcr0 = _xgetbv(0);
    const bool ymm_supported =
        (xcr0 & 0x6) == 0x6; // XMM (bit 1) and YMM (bit 2)
    const bool zmm_supported =
        (xcr0 & 0xE0) ==
        0xE0; // Opmask (bit 5), ZMM_hi256 (bit 6), Hi16_ZMM (bit 7)

    if (ymm_supported) {
      // Query leaf 7, subleaf 0 for AVX2, BMI2, and AVX-512 features
      __cpuidex(cpu_info, 7, 0);
      caps.has_avx2 = (cpu_info[1] & (1 << 5)) != 0;
      caps.has_bmi2 = (cpu_info[1] & (1 << 8)) != 0;

      if (zmm_supported) {
        caps.has_avx512f = (cpu_info[1] & (1 << 16)) != 0;
        caps.has_avx512bw = (cpu_info[1] & (1 << 30)) != 0;
      }
    }
  }
#elif defined(__GNUC__) || defined(__clang__)
  std::uint32_t eax = 0, ebx = 0, ecx = 0, edx = 0;

  // Query leaf 1 to check OSXSAVE and AVX support
  if (__get_cpuid(1, &eax, &ebx, &ecx, &edx)) {
    caps.has_sse42 = (ecx & (1u << 20)) != 0;
    const bool osxsave = (ecx & (1 << 27)) != 0;
    const bool avx = (ecx & (1 << 28)) != 0;

    if (osxsave && avx) {
      // Read XCR0 register via inline assembly
      std::uint64_t xcr0 = 0;
      asm volatile("xgetbv" : "=A"(xcr0) : "c"(0));

      const bool ymm_supported = (xcr0 & 0x6) == 0x6;
      const bool zmm_supported = (xcr0 & 0xE0) == 0xE0;

      if (ymm_supported && __get_cpuid_count(7, 0, &eax, &ebx, &ecx, &edx)) {
        caps.has_avx2 = (ebx & (1 << 5)) != 0;
        caps.has_bmi2 = (ebx & (1 << 8)) != 0;

        if (zmm_supported) {
          caps.has_avx512f = (ebx & (1 << 16)) != 0;
          caps.has_avx512bw = (ebx & (1 << 30)) != 0;
        }
      }
    }
  }
#endif
#elif defined(ARCH_ARM64)
  caps.has_neon = true;
#if defined(__ARM_FEATURE_CRC32)
  caps.has_arm_crc = true;
#endif
#if defined(__ARM_FEATURE_SVE)
  caps.has_sve = true;
#endif
#endif

  return caps;
}

/**
 * Thread-safe, memoized accessor for the process-wide CPU capability set
 */
[[nodiscard]] const CPUCapabilities &get_cpu_capabilities() noexcept {
  static const CPUCapabilities caps = detect_cpu_capabilities();
  return caps;
}

// ============================================================================
// 4. SIMD MEMORY OPERATIONS (memcpy, memset, memcmp)
// ============================================================================

constexpr std::size_t STREAMING_THRESHOLD = 1024 * 1024;

#if defined(__x86_64__) || defined(_M_X64)
TARGET_AVX2
static inline void memcpy_stream_avx2(void *dest, const void *src,
                                      std::size_t n) noexcept {
  auto *d = static_cast<std::uint8_t *>(dest);
  const auto *s = static_cast<const std::uint8_t *>(src);

  std::size_t i = 0;
  for (; i + 32 <= n; i += 32) {
    __m256i v = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s + i));
    _mm256_stream_si256(reinterpret_cast<__m256i *>(d + i), v);
  }
  _mm_sfence();

  if (i < n) {
    std::memcpy(d + i, s + i, n - i);
  }
}
#endif

#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)

// Standard library offload
TARGET_AVX2
void memcpy_simd_avx2(void *dest, const void *src, std::size_t n) noexcept {
  std::memcpy(dest, src, n);
}

TARGET_AVX2
void memset_simd_avx2(void *dest, int val, std::size_t n) noexcept {
  std::memset(dest, val, n);
}

// -----------------------------------------------------------------------------
// Alternative Explicit AVX2 Implementations
// -----------------------------------------------------------------------------

TARGET_AVX2
void memcpy_avx2_explicit(void *dest, const void *src, std::size_t n) noexcept {
  auto *d = static_cast<std::uint8_t *>(dest);
  const auto *s = static_cast<const std::uint8_t *>(src);

  if (n == 0)
    return;
  if (n < 32) {
    if (n >= 16) {
      __m128i v0 = _mm_loadu_si128(reinterpret_cast<const __m128i *>(s));
      __m128i v1 =
          _mm_loadu_si128(reinterpret_cast<const __m128i *>(s + n - 16));
      _mm_storeu_si128(reinterpret_cast<__m128i *>(d), v0);
      _mm_storeu_si128(reinterpret_cast<__m128i *>(d + n - 16), v1);
      return;
    }
    if (n >= 8) {
      std::uint64_t v0, v1;
      std::memcpy(&v0, s, 8);
      std::memcpy(&v1, s + n - 8, 8);
      std::memcpy(d, &v0, 8);
      std::memcpy(d + n - 8, &v1, 8);
      return;
    }
    if (n >= 4) {
      std::uint32_t v0, v1;
      std::memcpy(&v0, s, 4);
      std::memcpy(&v1, s + n - 4, 4);
      std::memcpy(d, &v0, 4);
      std::memcpy(d + n - 4, &v1, 4);
      return;
    }
    if (n >= 2) {
      std::uint16_t v0, v1;
      std::memcpy(&v0, s, 2);
      std::memcpy(&v1, s + n - 2, 2);
      std::memcpy(d, &v0, 2);
      std::memcpy(d + n - 2, &v1, 2);
      return;
    }
    *d = *s;
    return;
  }

  // Unrolled 128B main AVX2 loop
  while (n >= 128) {
    __m256i v0 = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s + 0));
    __m256i v1 = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s + 32));
    __m256i v2 = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s + 64));
    __m256i v3 = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s + 96));

    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 0), v0);
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 32), v1);
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 64), v2);
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 96), v3);

    d += 128;
    s += 128;
    n -= 128;
  }

  // Remaining 32B blocks
  while (n >= 32) {
    __m256i v = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s));
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d), v);
    d += 32;
    s += 32;
    n -= 32;
  }

  // Overlapping trailing copy for remainder bytes (< 32)
  if (n > 0) {
    __m256i v_tail =
        _mm256_loadu_si256(reinterpret_cast<const __m256i *>(s + n - 32));
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + n - 32), v_tail);
  }
}

TARGET_AVX2
void memset_avx2_explicit(void *dest, int val, std::size_t n) noexcept {
  auto *d = static_cast<std::uint8_t *>(dest);
  const __m256i v = _mm256_set1_epi8(static_cast<char>(val));

  if (n == 0)
    return;

  if (n < 32) {
    const __m128i v128 = _mm256_castsi256_si128(v);
    if (n >= 16) {
      _mm_storeu_si128(reinterpret_cast<__m128i *>(d), v128);
      _mm_storeu_si128(reinterpret_cast<__m128i *>(d + n - 16), v128);
      return;
    }
    if (n >= 8) {
      const std::uint64_t v64 = _mm_cvtsi128_si64(v128);
      std::memcpy(d, &v64, 8);
      std::memcpy(d + n - 8, &v64, 8);
      return;
    }
    if (n >= 4) {
      const std::uint32_t v32 =
          static_cast<std::uint32_t>(_mm_cvtsi128_si32(v128));
      std::memcpy(d, &v32, 4);
      std::memcpy(d + n - 4, &v32, 4);
      return;
    }
    std::memset(d, val, n);
    return;
  }

  // Unrolled 128B loop
  while (n >= 128) {
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 0), v);
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 32), v);
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 64), v);
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + 96), v);
    d += 128;
    n -= 128;
  }

  while (n >= 32) {
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d), v);
    d += 32;
    n -= 32;
  }

  // Overlapping trailing store
  if (n > 0) {
    _mm256_storeu_si256(reinterpret_cast<__m256i *>(d + n - 32), v);
  }
}

TARGET_AVX2
int memcmp_simd_avx2(const void *s1, const void *s2, std::size_t n) noexcept {
  auto *p1 = static_cast<const std::uint8_t *>(s1);
  auto *p2 = static_cast<const std::uint8_t *>(s2);

  while (n >= 32) {
    __m256i v1 = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(p1));
    __m256i v2 = _mm256_loadu_si256(reinterpret_cast<const __m256i *>(p2));
    __m256i cmp = _mm256_cmpeq_epi8(v1, v2);
    std::uint32_t mask = static_cast<std::uint32_t>(_mm256_movemask_epi8(cmp));

    if (mask != 0xFFFFFFFFU) {
#if defined(_MSC_VER)
      unsigned long diff_pos;
      _BitScanForward(&diff_pos, ~mask);
#else
      std::uint32_t diff_pos = static_cast<std::uint32_t>(__builtin_ctz(~mask));
#endif
      return static_cast<int>(p1[diff_pos]) - static_cast<int>(p2[diff_pos]);
    }
    p1 += 32;
    p2 += 32;
    n -= 32;
  }

  while (n > 0) {
    if (*p1 != *p2)
      return static_cast<int>(*p1) - static_cast<int>(*p2);
    ++p1;
    ++p2;
    --n;
  }
  return 0;
}

#elif defined(ARCH_ARM64) || defined(__aarch64__)

TARGET_NEON
void memcpy_simd_neon(void *dest, const void *src, std::size_t n) noexcept {
  // Rely on highly optimized libc memcpy implementation for ARM AArch64
  std::memcpy(dest, src, n);
}

TARGET_NEON
void memset_simd_neon(void *dest, int val, std::size_t n) noexcept {
  auto *d = static_cast<std::uint8_t *>(dest);
  const uint8x16_t v = vdupq_n_u8(static_cast<std::uint8_t>(val));

  // Process 64B blocks with 4-way loop unrolling
  while (n >= 64) {
    vst1q_u8(d + 0, v);
    vst1q_u8(d + 16, v);
    vst1q_u8(d + 32, v);
    vst1q_u8(d + 48, v);
    d += 64;
    n -= 64;
  }

  // Process remaining 16B blocks
  while (n >= 16) {
    vst1q_u8(d, v);
    d += 16;
    n -= 16;
  }

  // Scalar fallback for remaining bytes
  while (n > 0) {
    *d++ = static_cast<std::uint8_t>(val);
    --n;
  }
}

TARGET_NEON
int memcmp_simd_neon(const void *s1, const void *s2, std::size_t n) noexcept {
  auto *p1 = static_cast<const std::uint8_t *>(s1);
  auto *p2 = static_cast<const std::uint8_t *>(s2);

  // Vectorized comparison in 16B chunks
  while (n >= 16) {
    const uint8x16_t v1 = vld1q_u8(p1);
    const uint8x16_t v2 = vld1q_u8(p2);
    const uint8x16_t cmp = vceqq_u8(v1, v2);

    // If any byte comparison fails (not all 0xFF bytes)
    if (vminvq_u8(cmp) != 0xFF) {
      for (std::size_t i = 0; i < 16; ++i) {
        if (p1[i] != p2[i]) {
          return static_cast<int>(p1[i]) - static_cast<int>(p2[i]);
        }
      }
    }
    p1 += 16;
    p2 += 16;
    n -= 16;
  }

  // Scalar fallback for remaining bytes
  while (n > 0) {
    if (*p1 != *p2) {
      return static_cast<int>(*p1) - static_cast<int>(*p2);
    }
    ++p1;
    ++p2;
    --n;
  }

  return 0;
}

#endif

// Generic SIMD Dispatchers

void memcpy_simd(void *dest, const void *src, std::size_t n) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  if (get_cpu_capabilities().has_avx2) {
    memcpy_simd_avx2(dest, src, n);
    return;
  }
  std::memcpy(dest, src, n);
#elif defined(ARCH_ARM64) || defined(__aarch64__)
  memcpy_simd_neon(dest, src, n);
#else
  std::memcpy(dest, src, n);
#endif
}

void memset_simd(void *dest, int val, std::size_t n) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  if (get_cpu_capabilities().has_avx2) {
    memset_simd_avx2(dest, val, n);
    return;
  }
  std::memset(dest, val, n);
#elif defined(ARCH_ARM64) || defined(__aarch64__)
  memset_simd_neon(dest, val, n);
#else
  std::memset(dest, val, n);
#endif
}

int memcmp_simd(const void *s1, const void *s2, std::size_t n) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  if (get_cpu_capabilities().has_avx2) {
    return memcmp_simd_avx2(s1, s2, n);
  }
  return std::memcmp(s1, s2, n);
#elif defined(ARCH_ARM64) || defined(__aarch64__)
  return memcmp_simd_neon(s1, s2, n);
#else
  return std::memcmp(s1, s2, n);
#endif
}

/**
 * Adaptively selects between SIMD and standard std::memcpy based on runtime CPU
 * features and buffer size.
 */
void *memcpy_adaptive(void *dest, const void *src, std::size_t n) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  const CPUCapabilities &caps = get_cpu_capabilities();
  if (caps.has_avx2 && n >= STREAMING_THRESHOLD) [[unlikely]] {
    memcpy_stream_avx2(dest, src, n);
    return dest;
  }
#endif
  return std::memcpy(dest, src, n);
}

/**
 * Adaptively selects between SIMD and standard std::memset based on runtime CPU
 * features and buffer size.
 */
void *memset_adaptive(void *dest, int v, std::size_t n) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  const CPUCapabilities &caps = get_cpu_capabilities();
  if (caps.has_avx2 && n >= 64) {
    memset_simd(dest, v, n);
    return dest;
  }
#elif defined(ARCH_ARM64) || defined(__aarch64__)
  if (n >= 64) {
    memset_simd(dest, v, n);
    return dest;
  }
#endif
  std::memset(dest, v, n);
  return dest;
}

/**
 * Adaptively selects between SIMD and standard std::memcmp based on runtime CPU
 * features and buffer size.
 */
int memcmp_adaptive(const void *a, const void *b, std::size_t n) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  const CPUCapabilities &caps = get_cpu_capabilities();
  if (caps.has_avx2 && n >= 64) {
    return memcmp_simd(a, b, n);
  }
#elif defined(ARCH_ARM64) || defined(__aarch64__)
  if (n >= 64) {
    return memcmp_simd(a, b, n);
  }
#endif
  return std::memcmp(a, b, n);
}

/**
 * Scales an array of floats in-place using AVX-512 intrinsics.
 */
TARGET_AVX512
void tensor_process_avx512(float *__restrict__ ptr, std::size_t count,
                           float factor) noexcept {
  std::size_t i = 0;
  const __m512 v_scale = _mm512_set1_ps(factor);

  // Main unrolled loop: process 32 floats (2 x 512B vectors) per iteration
  for (; i + 32 <= count; i += 32) {
    __m512 v0 = _mm512_loadu_ps(ptr + i);
    __m512 v1 = _mm512_loadu_ps(ptr + i + 16);

    v0 = _mm512_mul_ps(v0, v_scale);
    v1 = _mm512_mul_ps(v1, v_scale);

    _mm512_storeu_ps(ptr + i, v0);
    _mm512_storeu_ps(ptr + i + 16, v1);
  }

  // Secondary loop: process remaining 16-float block
  if (i + 16 <= count) {
    __m512 v = _mm512_loadu_ps(ptr + i);
    v = _mm512_mul_ps(v, v_scale);
    _mm512_storeu_ps(ptr + i, v);
    i += 16;
  }

  // Masked tail processing: handles remaining 1 to 15 elements using AVX-512
  // masks
  if (i < count) {
    const std::size_t remainder = count - i;
    const __mmask16 mask = static_cast<__mmask16>((1U << remainder) - 1U);

    __m512 v = _mm512_mask_loadu_ps(_mm512_setzero_ps(), mask, ptr + i);
    v = _mm512_mul_ps(v, v_scale);
    _mm512_mask_storeu_ps(ptr + i, mask, v);
  }
}

#if defined(ARCH_ARM64) && defined(HAS_SVE_HEADERS)
/**
 * Scales an array of floats in-place using ARM SVE intrinsics.
 */
TARGET_SVE
void tensor_process_sve(float *__restrict__ ptr, std::size_t count,
                        float factor) noexcept {
  std::size_t i = 0;
  const svfloat32_t v_scale = svdup_n_f32(factor);
  const std::size_t vl = svcntw(); // Get current vector length (32B elements)

  // Main unrolled loop: processes 2 vector lengths per iteration
  svbool_t pg_all = svptrue_b32();
  for (; i + 2 * vl <= count; i += 2 * vl) {
    svfloat32_t v0 = svld1_f32(pg_all, ptr + i);
    svfloat32_t v1 = svld1_f32(pg_all, ptr + i + vl);

    v0 = svmul_f32_x(pg_all, v0, v_scale);
    v1 = svmul_f32_x(pg_all, v1, v_scale);

    svst1_f32(pg_all, ptr + i, v0);
    svst1_f32(pg_all, ptr + i + vl, v1);
  }

  // Predicate-driven loop handles remaining tail elements
  svbool_t pg = svwhilelt_b32(i, count);
  while (svptest_any(svptrue_b32(), pg)) {
    svfloat32_t v = svld1_f32(pg, ptr + i);
    svfloat32_t r = svmul_f32_x(pg, v, v_scale);
    svst1_f32(pg, ptr + i, r);

    i += svcntw();
    pg = svwhilelt_b32(i, count);
  }
}
#else
/**
 * Fallback implementation for non-ARM SVE architectures (x86_64, NEON, etc.).
 */
void tensor_process_sve(float *__restrict__ ptr, std::size_t count,
                        float factor) noexcept {
  for (std::size_t i = 0; i < count; ++i) {
    ptr[i] *= factor;
  }
}
#endif

#if defined(ARCH_X86_64)
TARGET_AVX2
static void tensor_process_avx2_fallback(float *__restrict__ ptr,
                                         std::size_t count,
                                         float factor) noexcept {
  std::size_t i = 0;
  const __m256 v_scale = _mm256_set1_ps(factor);
  for (; i + 8 <= count; i += 8) {
    __m256 v = _mm256_loadu_ps(ptr + i);
    v = _mm256_mul_ps(v, v_scale);
    _mm256_storeu_ps(ptr + i, v);
  }
  for (; i < count; ++i) {
    ptr[i] *= factor;
  }
}
#endif // ARCH_X86_64

/**
 * High-level cross-platform dispatcher for SIMD tensor processing.
 *
 * Dynamically selects the optimal vector architecture (AVX-512, AVX2, SVE,
 * NEON) at runtime based on detected hardware support.
 */
void tensor_process_simd(void *raw, std::size_t bytes,
                         float factor) noexcept {
  if (!raw || bytes < sizeof(float))
    return;

  auto *ptr = static_cast<float *>(raw);
  const std::size_t count = bytes / sizeof(float);

#if defined(ARCH_X86_64)
  const CPUCapabilities &caps = get_cpu_capabilities();

  if (caps.has_avx512f && caps.has_avx512bw) {
    tensor_process_avx512(ptr, count, factor);
  } else if (caps.has_avx2) {
    // AVX2 path -- only reached once CPUID has actually confirmed support.
    tensor_process_avx2_fallback(ptr, count, factor);
  } else {
    std::size_t i = 0;
    const __m128 v_scale = _mm_set1_ps(factor);
    for (; i + 4 <= count; i += 4) {
      __m128 v = _mm_loadu_ps(ptr + i);
      v = _mm_mul_ps(v, v_scale);
      _mm_storeu_ps(ptr + i, v);
    }
    for (; i < count; ++i) {
      ptr[i] *= factor;
    }
  }

#elif defined(ARCH_ARM64)
#if defined(__ARM_FEATURE_SVE)
  const CPUCapabilities &caps = get_cpu_capabilities();
  if (caps.has_sve) {
    tensor_process_sve(ptr, count, factor);
    return;
  }
#endif
  // NEON fallback for ARM64
  std::size_t i = 0;
  const float32x4_t v_scale = vdupq_n_f32(factor);
  for (; i + 4 <= count; i += 4) {
    float32x4_t v = vld1q_f32(ptr + i);
    v = vmulq_f32(v, v_scale);
    vst1q_f32(ptr + i, v);
  }
  for (; i < count; ++i) {
    ptr[i] *= factor;
  }

#else
  // Generic Scalar Fallback
  for (std::size_t i = 0; i < count; ++i) {
    ptr[i] *= factor;
  }
#endif
}

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
                                          std::size_t nodes) noexcept {
  if (nodes <= 1)
    return 0;

  // Bit-mix to ensure high entropy even for sequential keys
  key ^= key >> 33;
  key *= 0xff51afd7ed558ccdULL;
  key ^= key >> 33;

  // Jump Consistent Hash Algorithm (Lamping & Veach, 2014).
  std::int64_t b = -1;
  std::int64_t j = 0;

  while (j < static_cast<std::int64_t>(nodes)) {
    b = j;
    key = key * 2862933555777941757ULL + 1ULL;
    j = static_cast<std::int64_t>(
        (static_cast<double>(b + 1) * static_cast<double>(1ULL << 31)) /
        static_cast<double>((key >> 33) + 1));
  }

  return static_cast<std::size_t>(b);
}

[[nodiscard]] inline float
utils_backpressure_smooth(float level, float &prev_state,
                          float alpha) noexcept {
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
                   std::uint64_t min_threshold) noexcept {
  // Steal only if remote has strictly more tasks than local plus the minimum
  // threshold
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

ALWAYS_INLINE void utils_prefetch(const void *ptr) noexcept {
  if (ptr) {
    prefetch_memory(ptr);
  }
}

// ============================================================================
// 5. SIMD STRING PROCESSING (to_lower, to_upper, trim)
// ============================================================================

CLONE_X86_SIMD
inline void transform_buffer(char *data, std::size_t size) noexcept {
  for (std::size_t i = 0; i < size; ++i) {
    if (data[i] >= 'a' && data[i] <= 'z') {
      data[i] -= 32; // Convert lowercase ASCII to uppercase
    }
  }
}

#if defined(ARCH_X86_64)

/**
 * Converts an ASCII string to lowercase
 */
TARGET_AVX512
void to_lower_avx512_inplace(char *str, std::size_t len) noexcept {
  std::size_t i = 0;

  // Set constant vectors for ASCII bounds and the case-flipping bit (0x20)
  const __m512i vec_A = _mm512_set1_epi8('A');
  const __m512i vec_Z = _mm512_set1_epi8('Z');
  const __m512i flip = _mm512_set1_epi8(0x20);

  // Main SIMD loop processing 64B per iteration
  for (; i + 64 <= len; i += 64) {
    __m512i vec = _mm512_loadu_si512(reinterpret_cast<const void *>(str + i));

    // Direct unsigned byte range comparison ['A', 'Z'] using AVX-512BW
    __mmask64 mask_ge_a = _mm512_cmpge_epu8_mask(vec, vec_A);
    __mmask64 mask_le_z = _mm512_cmple_epu8_mask(vec, vec_Z);
    __mmask64 is_upper = mask_ge_a & mask_le_z;

    // XOR 0x20 only where is_upper is true using bitwise XOR over 32B dwords
    __m512i toggled = _mm512_xor_si512(vec, flip);
    __m512i result = _mm512_mask_blend_epi8(is_upper, vec, toggled);

    _mm512_storeu_si512(reinterpret_cast<void *>(str + i), result);
  }

  // Masked SIMD tail loop for remaining bytes (< 64)
  if (i < len) {
    const std::size_t rem = len - i;
    const __mmask64 tail_mask = (1ULL << rem) - 1ULL;

    __m512i vec = _mm512_maskz_loadu_epi8(tail_mask, str + i);

    __mmask64 mask_ge_a = _mm512_cmpge_epu8_mask(vec, vec_A);
    __mmask64 mask_le_z = _mm512_cmple_epu8_mask(vec, vec_Z);
    __mmask64 is_upper = mask_ge_a & mask_le_z;

    __m512i toggled = _mm512_xor_si512(vec, flip);
    __m512i result = _mm512_mask_blend_epi8(is_upper, vec, toggled);

    _mm512_mask_storeu_epi8(str + i, tail_mask, result);
  }
}

/**
 * Converts an ASCII string to uppercase
 */
TARGET_AVX512
void to_upper_avx512_inplace(char *str, std::size_t len) noexcept {
  std::size_t i = 0;

  // Set constant vectors for ASCII bounds and the case-flipping bit (0x20)
  const __m512i vec_a = _mm512_set1_epi8('a');
  const __m512i vec_z = _mm512_set1_epi8('z');
  const __m512i flip = _mm512_set1_epi8(0x20);

  // Main SIMD loop processing 64B per iteration
  for (; i + 64 <= len; i += 64) {
    __m512i vec = _mm512_loadu_si512(reinterpret_cast<const void *>(str + i));

    // Direct unsigned byte range comparison ['a', 'z'] using AVX-512BW
    __mmask64 mask_ge_a = _mm512_cmpge_epu8_mask(vec, vec_a);
    __mmask64 mask_le_z = _mm512_cmple_epu8_mask(vec, vec_z);
    __mmask64 is_lower = mask_ge_a & mask_le_z;

    // XOR 0x20 only where is_lower is true using byte mask blending
    __m512i toggled = _mm512_xor_si512(vec, flip);
    __m512i result = _mm512_mask_blend_epi8(is_lower, vec, toggled);

    _mm512_storeu_si512(reinterpret_cast<void *>(str + i), result);
  }

  // Masked SIMD tail loop for remaining bytes (< 64)
  if (i < len) {
    const std::size_t rem = len - i;
    const __mmask64 tail_mask = (1ULL << rem) - 1ULL;

    __m512i vec = _mm512_maskz_loadu_epi8(tail_mask, str + i);

    __mmask64 mask_ge_a = _mm512_cmpge_epu8_mask(vec, vec_a);
    __mmask64 mask_le_z = _mm512_cmple_epu8_mask(vec, vec_z);
    __mmask64 is_lower = mask_ge_a & mask_le_z;

    __m512i toggled = _mm512_xor_si512(vec, flip);
    __m512i result = _mm512_mask_blend_epi8(is_lower, vec, toggled);

    _mm512_mask_storeu_epi8(str + i, tail_mask, result);
  }
}

/**
 * Converts an ASCII string to lowercase
 */
TARGET_AVX2
void to_lower_avx2_inplace(char *str, std::size_t len) noexcept {
  std::size_t i = 0;

  // Shift values by 128 to perform signed comparison on unsigned range [65, 90]
  const __m256i offset = _mm256_set1_epi8(-128);
  const __m256i a = _mm256_set1_epi8(static_cast<char>('A' - 1 - 128));
  const __m256i z = _mm256_set1_epi8(static_cast<char>('Z' + 1 - 128));
  const __m256i flip = _mm256_set1_epi8(0x20);

  for (; i + 32 <= len; i += 32) {
    __m256i vec =
        _mm256_loadu_si256(reinterpret_cast<const __m256i *>(str + i));

    // Normalize to signed comparison domain
    __m256i vec_shifted = _mm256_add_epi8(vec, offset);

    __m256i is_gt_a = _mm256_cmpgt_epi8(vec_shifted, a);
    __m256i is_lt_z = _mm256_cmpgt_epi8(z, vec_shifted);
    __m256i is_upper = _mm256_and_si256(is_gt_a, is_lt_z);

    __m256i to_flip = _mm256_and_si256(is_upper, flip);
    vec = _mm256_xor_si256(vec, to_flip);

    _mm256_storeu_si256(reinterpret_cast<__m256i *>(str + i), vec);
  }

  // Scalar fallback for tail bytes (< 32)
  for (; i < len; ++i) {
    const auto c = static_cast<unsigned char>(str[i]);
    if (c >= 'A' && c <= 'Z') {
      str[i] = static_cast<char>(c | 0x20);
    }
  }
}

/**
 * Converts an ASCII string to uppercase
 */
TARGET_AVX2
void to_upper_avx2_inplace(char *str, std::size_t len) noexcept {
  std::size_t i = 0;

  // Shift values by 128 to perform signed comparison on unsigned range
  const __m256i offset = _mm256_set1_epi8(-128);
  const __m256i a = _mm256_set1_epi8(static_cast<char>('a' - 1 - 128));
  const __m256i z = _mm256_set1_epi8(static_cast<char>('z' + 1 - 128));
  const __m256i flip = _mm256_set1_epi8(0x20);

  for (; i + 32 <= len; i += 32) {
    __m256i vec =
        _mm256_loadu_si256(reinterpret_cast<const __m256i *>(str + i));

    // Normalize to signed comparison domain
    __m256i vec_shifted = _mm256_add_epi8(vec, offset);

    __m256i is_gt_a = _mm256_cmpgt_epi8(vec_shifted, a);
    __m256i is_lt_z = _mm256_cmpgt_epi8(z, vec_shifted);
    __m256i is_lower = _mm256_and_si256(is_gt_a, is_lt_z);

    __m256i to_flip = _mm256_and_si256(is_lower, flip);
    vec = _mm256_xor_si256(vec, to_flip);

    _mm256_storeu_si256(reinterpret_cast<__m256i *>(str + i), vec);
  }

  // Scalar fallback for tail bytes (< 32)
  for (; i < len; ++i) {
    const auto c = static_cast<unsigned char>(str[i]);
    if (c >= 'a' && c <= 'z') {
      str[i] = static_cast<char>(c & ~0x20);
    }
  }
}

#elif defined(ARCH_ARM64)

TARGET_NEON
void to_lower_neon_inplace(char *str, std::size_t len) noexcept {
  std::size_t i = 0;
  uint8x16_t a = vdupq_n_u8('A');
  uint8x16_t z = vdupq_n_u8('Z');
  uint8x16_t flip = vdupq_n_u8(0x20);

  for (; i + 16 <= len; i += 16) {
    uint8x16_t vec = vld1q_u8(reinterpret_cast<const uint8_t *>(str + i));
    uint8x16_t ge_a = vcgeq_u8(vec, a);
    uint8x16_t le_z = vcleq_u8(vec, z);
    uint8x16_t is_upper = vandq_u8(ge_a, le_z);

    uint8x16_t mask = vandq_u8(is_upper, flip);
    vec = veorq_u8(vec, mask);
    vst1q_u8(reinterpret_cast<uint8_t *>(str + i), vec);
  }

  for (; i < len; ++i) {
    if (str[i] >= 'A' && str[i] <= 'Z')
      str[i] |= 0x20;
  }
}

TARGET_NEON
void to_upper_neon_inplace(char *str, std::size_t len) noexcept {
  std::size_t i = 0;
  uint8x16_t a = vdupq_n_u8('a');
  uint8x16_t z = vdupq_n_u8('z');
  uint8x16_t flip = vdupq_n_u8(0x20);

  for (; i + 16 <= len; i += 16) {
    uint8x16_t vec = vld1q_u8(reinterpret_cast<const uint8_t *>(str + i));
    uint8x16_t ge_a = vcgeq_u8(vec, a);
    uint8x16_t le_z = vcleq_u8(vec, z);
    uint8x16_t is_lower = vandq_u8(ge_a, le_z);

    uint8x16_t mask = vandq_u8(is_lower, flip);
    vec = veorq_u8(vec, mask);
    vst1q_u8(reinterpret_cast<uint8_t *>(str + i), vec);
  }

  for (; i < len; ++i) {
    if (str[i] >= 'a' && str[i] <= 'z')
      str[i] &= ~0x20;
  }
}

#endif

void to_lower_simd(char *str, std::size_t len) noexcept {
#if defined(ARCH_X86_64)

  const CPUCapabilities &caps = get_cpu_capabilities();

  if (caps.has_avx512bw) {
    to_lower_avx512_inplace(str, len);
  } else if (caps.has_avx2) {
    to_lower_avx2_inplace(str, len);
  } else {
    for (std::size_t i = 0; i < len; ++i) {
      const auto c = static_cast<unsigned char>(str[i]);
      if (c >= 'A' && c <= 'Z') {
        str[i] = static_cast<char>(c | 0x20);
      }
    }
  }
#elif defined(ARCH_ARM64)
  to_lower_neon_inplace(str, len);
#else
  // Scalar fallback for unsupported architectures
  for (std::size_t i = 0; i < len; ++i) {
    const auto c = static_cast<unsigned char>(str[i]);
    if (c >= 'A' && c <= 'Z') {
      str[i] |= 0x20;
    }
  }
#endif
}

void to_upper_simd(char *str, std::size_t len) noexcept {
#if defined(ARCH_X86_64)
  const CPUCapabilities &caps = get_cpu_capabilities();
  if (caps.has_avx512bw) {
    to_upper_avx512_inplace(str, len);
  } else if (caps.has_avx2) {
    to_upper_avx2_inplace(str, len);
  } else {
    for (std::size_t i = 0; i < len; ++i) {
      const auto c = static_cast<unsigned char>(str[i]);
      if (c >= 'a' && c <= 'z') {
        str[i] = static_cast<char>(c & ~0x20);
      }
    }
  }
#elif defined(ARCH_ARM64)
  to_upper_neon_inplace(str, len);
#else
  for (std::size_t i = 0; i < len; ++i) {
    if (static_cast<unsigned char>(str[i]) >= 'a' &&
        static_cast<unsigned char>(str[i]) <= 'z')
      str[i] &= ~0x20;
  }
#endif
}

// ============================================================================
// 6. SIMD HASHING ALGORITHMS (xxHash64 & HighwayHash)
// ============================================================================

static constexpr std::uint64_t PRIME64_1 = 11400714785074694791ULL;
static constexpr std::uint64_t PRIME64_2 = 14029467366897019727ULL;
static constexpr std::uint64_t PRIME64_3 = 1609587929390651085ULL;
static constexpr std::uint64_t PRIME64_4 = 9650029242287828579ULL;
static constexpr std::uint64_t PRIME64_5 = 2870177450012600056ULL;

static ALWAYS_INLINE std::uint64_t rotl64(std::uint64_t x, int r) noexcept {
  return (x << r) | (x >> (64 - r));
}

#if defined(ARCH_X86_64)

// Cste vectors and inline utilities for xxHash64

TARGET_AVX2
static inline __m256i rotate_left_64_avx2(__m256i v, int r) noexcept {
  return _mm256_or_si256(_mm256_slli_epi64(v, r), _mm256_srli_epi64(v, 64 - r));
}

// Helper: Emulated 64B integer vector multiplication in AVX2
TARGET_AVX2
static inline __m256i mul_epi64_avx2(__m256i a, __m256i b) noexcept {
  __m256i low_high0 = _mm256_mul_epu32(a, b);
  __m256i a_hi = _mm256_srli_epi64(a, 32);
  __m256i b_hi = _mm256_srli_epi64(b, 32);
  __m256i high_low0 = _mm256_mul_epu32(a_hi, b);
  __m256i low_high1 = _mm256_mul_epu32(a, b_hi);

  __m256i cross = _mm256_add_epi64(high_low0, low_high1);
  __m256i cross_shifted = _mm256_slli_epi64(cross, 32);

  return _mm256_add_epi64(low_high0, cross_shifted);
}

TARGET_AVX2
std::uint64_t hash_xxhash64_avx2(const void *input, std::size_t len,
                                 std::uint64_t seed) noexcept {
  const auto *p = static_cast<const std::uint8_t *>(input);
  std::uint64_t h64;

  if (len >= 32) {
    const auto *limit = p + len - 32;

    // Broadcast prime constants across 256B SIMD registers
    const __m256i v_prime1 =
        _mm256_set1_epi64x(static_cast<long long>(PRIME64_1));
    const __m256i v_prime2 =
        _mm256_set1_epi64x(static_cast<long long>(PRIME64_2));

    // Initialize state lanes (Note: _mm256_set_epi64x loads in reverse order
    // [e3, e2, e1, e0])
    __m256i v_state = _mm256_set_epi64x(
        static_cast<long long>(seed - PRIME64_1),            // Lane 3 (v4)
        static_cast<long long>(seed),                        // Lane 2 (v3)
        static_cast<long long>(seed + PRIME64_2),            // Lane 1 (v2)
        static_cast<long long>(seed + PRIME64_1 + PRIME64_2) // Lane 0 (v1)
    );

    do {
      __m256i v_block =
          _mm256_loadu_si256(reinterpret_cast<const __m256i *>(p));

      // v_state += v_block * PRIME64_2
      __m256i v_term = mul_epi64_avx2(v_block, v_prime2);
      v_state = _mm256_add_epi64(v_state, v_term);

      // v_state = rotl64(v_state, 31)
      v_state = rotate_left_64_avx2(v_state, 31);

      // v_state *= PRIME64_1
      v_state = mul_epi64_avx2(v_state, v_prime1);

      p += 32;
    } while (p <= limit);

    // Extract internal states into a local aligned array to finalize state
    // reduction
    alignas(32) std::uint64_t v_arr[4];
    _mm256_store_si256(reinterpret_cast<__m256i *>(v_arr), v_state);

    // Merge state accumulators according to xxHash64 specification
    h64 = rotl64(v_arr[0], 1) + rotl64(v_arr[1], 7) + rotl64(v_arr[2], 12) +
          rotl64(v_arr[3], 18);

    // Helper lambda to process round accumulation for a lane
    auto process_lane = [](std::uint64_t acc,
                           std::uint64_t lane) -> std::uint64_t {
      lane *= PRIME64_2;
      lane = rotl64(lane, 31);
      lane *= PRIME64_1;
      acc ^= lane;
      return acc * PRIME64_1 + PRIME64_4;
    };

    h64 = process_lane(h64, v_arr[0]);
    h64 = process_lane(h64, v_arr[1]);
    h64 = process_lane(h64, v_arr[2]);
    h64 = process_lane(h64, v_arr[3]);
  } else {
    h64 = seed + PRIME64_5;
  }

  h64 += static_cast<std::uint64_t>(len);

  const auto *end = static_cast<const std::uint8_t *>(input) + len;

  // Process 8B chunks
  while (p + 8 <= end) {
    std::uint64_t k1;
    std::memcpy(&k1, p, 8);
    k1 *= PRIME64_2;
    k1 = rotl64(k1, 31);
    k1 *= PRIME64_1;
    h64 ^= k1;
    h64 = rotl64(h64, 27) * PRIME64_1 + PRIME64_4;
    p += 8;
  }

  // Process 4B chunk
  if (p + 4 <= end) {
    std::uint32_t k1;
    std::memcpy(&k1, p, 4);
    h64 ^= static_cast<std::uint64_t>(k1) * PRIME64_1;
    h64 = rotl64(h64, 23) * PRIME64_2 + PRIME64_3;
    p += 4;
  }

  // Process trailing bytes (< 4)
  while (p < end) {
    h64 ^= static_cast<std::uint64_t>(*p) * PRIME64_5;
    h64 = rotl64(h64, 11) * PRIME64_1;
    p++;
  }

  // Final avalanche mix
  h64 ^= h64 >> 33;
  h64 *= PRIME64_2;
  h64 ^= h64 >> 29;
  h64 *= PRIME64_3;
  h64 ^= h64 >> 32;

  return h64;
}

// Helper: 256B AVX2 64B lane left bit rotation
TARGET_AVX2
static ALWAYS_INLINE __m256i avx2_rotl_epi64(__m256i v, int r) noexcept {
  return _mm256_or_si256(_mm256_slli_epi64(v, r), _mm256_srli_epi64(v, 64 - r));
}

// Helper: AVX2 64B vector left rotation replacement (AVX2 lacks native
// _mm256_rotl_epi64)
TARGET_AVX2
ALWAYS_INLINE __m256i avx2_rotl_epi64_32(__m256i v) noexcept {
  return _mm256_or_si256(_mm256_slli_epi64(v, 32), _mm256_srli_epi64(v, 32));
}

TARGET_AVX2
ALWAYS_INLINE __m256i highway_update(__m256i state, __m256i block,
                                     __m256i key) noexcept {
  // 1. Add input block to current state
  state = _mm256_add_epi64(state, block);

  // 2. Multiply 32B halves into 64B values (HighwayHash core mixing
  // operator)
  __m256i state_swapped = _mm256_shuffle_epi32(state, _MM_SHUFFLE(2, 3, 0, 1));
  __m256i mul = _mm256_mul_epu32(state, state_swapped);

  // 3. XOR with key, accumulate products, and rotate 64B lanes by 32 bits
  state = _mm256_xor_si256(state, key);
  state = _mm256_add_epi64(state, mul);

  return avx2_rotl_epi64(state, 32);
}

TARGET_AVX2
std::uint64_t hash_highway64_avx2(const void *input, std::size_t len,
                                  const std::uint64_t key[4]) noexcept {
  const auto *p = static_cast<const std::uint8_t *>(input);

  // Initial state constants XORed with the provided 256B key
  alignas(32) const std::uint64_t init_state[4] = {
      key[0] ^ 0x243f6a8885a308d3ULL, key[1] ^ 0x13198a2e03707344ULL,
      key[2] ^ 0xa4093822299f31d0ULL, key[3] ^ 0x082efa98ec4e6c89ULL};

  // Load initial state and key using SIMD
  __m256i v_state =
      _mm256_load_si256(reinterpret_cast<const __m256i *>(init_state));
  const __m256i v_key =
      _mm256_loadu_si256(reinterpret_cast<const __m256i *>(key));

  const std::size_t blocks = len / 32;
  const std::size_t remainder = len % 32;

  // Main loop: Process complete 32B blocks
  for (std::size_t i = 0; i < blocks; ++i) {
    __m256i v_block =
        _mm256_loadu_si256(reinterpret_cast<const __m256i *>(p + i * 32));
    v_state = highway_update(v_state, v_block, v_key);
  }

  // Process remaining bytes (< 32 bytes) safely before finalizing state
  if (remainder > 0) {
    alignas(32) std::uint8_t tail_buffer[32] = {0};
    std::memcpy(tail_buffer, p + (blocks * 32), remainder);

    // Append the length byte to guarantee unique hashes for trailing zeros
    tail_buffer[remainder] = static_cast<std::uint8_t>(remainder);

    __m256i v_tail =
        _mm256_load_si256(reinterpret_cast<const __m256i *>(tail_buffer));
    v_state = highway_update(v_state, v_tail, v_key);
  }

  // Zipper fold / permute reduction stage across all 4 lanes
  __m256i v_permuted =
      _mm256_permute4x64_epi64(v_state, _MM_SHUFFLE(1, 0, 3, 2));
  v_state = _mm256_add_epi64(v_state, v_permuted);

  // Store updated state vector to local stack
  alignas(32) std::uint64_t state[4];
  _mm256_store_si256(reinterpret_cast<__m256i *>(state), v_state);

  // Final 64B avalanche mix
  std::uint64_t hash = state[0] ^ state[2];
  std::uint64_t hash2 = state[1] ^ state[3];

  hash ^= hash2;
  hash ^= (hash >> 33);
  hash *= 0xff51afd7ed558ccdULL;
  hash ^= (hash >> 33);
  hash *= 0xc4ceb9fe1a85ec53ULL;
  hash ^= (hash >> 33);

  return hash;
}

#endif // ARCH_X86_64

#if defined(ARCH_ARM64)

#if defined(__ARM_FEATURE_SVE)
TARGET_SVE
std::uint64_t hash_xxhash64_sve(const void *input, std::size_t len,
                                std::uint64_t seed) noexcept {
  const auto *p = static_cast<const std::uint8_t *>(input);
  std::uint64_t h64 = seed + PRIME64_5 + len;

  alignas(64) std::uint64_t tmp[16];
  svbool_t pg = svptrue_b64();

  while (p + 32 <= static_cast<const std::uint8_t *>(input) + len) {
    svuint64_t v = svld1_u64(pg, reinterpret_cast<const std::uint64_t *>(p));
    svst1_u64(pg, tmp, v);
    for (int i = 0; i < 4; ++i) {
      h64 ^= tmp[i] * PRIME64_2;
      h64 = rotl64(h64, 31) * PRIME64_1;
    }
    p += 32;
  }

  const auto *end = static_cast<const std::uint8_t *>(input) + len;
  while (p < end) {
    h64 ^= static_cast<std::uint64_t>(*p) * PRIME64_5;
    h64 = rotl64(h64, 11) * PRIME64_1;
    p++;
  }
  return h64;
}
#endif

TARGET_NEON
std::uint64_t hash_xxhash64_neon(const void *input, std::size_t len,
                                 std::uint64_t seed) noexcept {
  const auto *p = static_cast<const std::uint8_t *>(input);
  std::uint64_t h64 = seed + PRIME64_5 + len;

  while (p + 16 <= static_cast<const std::uint8_t *>(input) + len) {
    uint64x2_t v = vld1q_u64(reinterpret_cast<const std::uint64_t *>(p));
    std::uint64_t v0 = vgetq_lane_u64(v, 0);
    std::uint64_t v1 = vgetq_lane_u64(v, 1);

    h64 ^= v0 * PRIME64_2;
    h64 = rotl64(h64, 31) * PRIME64_1;
    h64 ^= v1 * PRIME64_2;
    h64 = rotl64(h64, 31) * PRIME64_1;

    p += 16;
  }

  const auto *end = static_cast<const std::uint8_t *>(input) + len;
  while (p < end) {
    h64 ^= static_cast<std::uint64_t>(*p) * PRIME64_5;
    h64 = rotl64(h64, 11) * PRIME64_1;
    p++;
  }

  return h64;
}

#endif

#if defined(ARCH_X86_64)

static std::uint64_t hash_xxhash64_scalar(const void *input, std::size_t len,
                                          std::uint64_t seed) noexcept {
  const auto *p = static_cast<const std::uint8_t *>(input);
  const auto *end = p + len;
  std::uint64_t h64;

  if (len >= 32) {
    const auto *limit = end - 32;
    std::uint64_t v1 = seed + PRIME64_1 + PRIME64_2;
    std::uint64_t v2 = seed + PRIME64_2;
    std::uint64_t v3 = seed;
    std::uint64_t v4 = seed - PRIME64_1;

    do {
      std::uint64_t k1, k2, k3, k4;
      std::memcpy(&k1, p, 8);
      std::memcpy(&k2, p + 8, 8);
      std::memcpy(&k3, p + 16, 8);
      std::memcpy(&k4, p + 24, 8);

      v1 = rotl64(v1 + k1 * PRIME64_2, 31) * PRIME64_1;
      v2 = rotl64(v2 + k2 * PRIME64_2, 31) * PRIME64_1;
      v3 = rotl64(v3 + k3 * PRIME64_2, 31) * PRIME64_1;
      v4 = rotl64(v4 + k4 * PRIME64_2, 31) * PRIME64_1;
      p += 32;
    } while (p <= limit);

    h64 = rotl64(v1, 1) + rotl64(v2, 7) + rotl64(v3, 12) + rotl64(v4, 18);

    v1 = rotl64(v1 * PRIME64_2, 31) * PRIME64_1;
    h64 = (h64 ^ v1) * PRIME64_1 + PRIME64_4;
    v2 = rotl64(v2 * PRIME64_2, 31) * PRIME64_1;
    h64 = (h64 ^ v2) * PRIME64_1 + PRIME64_4;
    v3 = rotl64(v3 * PRIME64_2, 31) * PRIME64_1;
    h64 = (h64 ^ v3) * PRIME64_1 + PRIME64_4;
    v4 = rotl64(v4 * PRIME64_2, 31) * PRIME64_1;
    h64 = (h64 ^ v4) * PRIME64_1 + PRIME64_4;
  } else {
    h64 = seed + PRIME64_5;
  }

  h64 += static_cast<std::uint64_t>(len);

  while (p + 8 <= end) {
    std::uint64_t k1;
    std::memcpy(&k1, p, 8);
    k1 *= PRIME64_2;
    k1 = rotl64(k1, 31);
    k1 *= PRIME64_1;
    h64 ^= k1;
    h64 = rotl64(h64, 27) * PRIME64_1 + PRIME64_4;
    p += 8;
  }

  if (p + 4 <= end) {
    std::uint32_t k1;
    std::memcpy(&k1, p, 4);
    h64 ^= static_cast<std::uint64_t>(k1) * PRIME64_1;
    h64 = rotl64(h64, 23) * PRIME64_2 + PRIME64_3;
    p += 4;
  }

  while (p < end) {
    h64 ^= static_cast<std::uint64_t>(*p) * PRIME64_5;
    h64 = rotl64(h64, 11) * PRIME64_1;
    p++;
  }

  h64 ^= h64 >> 33;
  h64 *= PRIME64_2;
  h64 ^= h64 >> 29;
  h64 *= PRIME64_3;
  h64 ^= h64 >> 32;

  return h64;
}
#endif // ARCH_X86_64

// Generic Hash Dispatcher
std::uint64_t hash_bytes(const void *input, std::size_t len,
                         std::uint64_t seed = 0) noexcept {
#if defined(ARCH_X86_64)
  if (get_cpu_capabilities().has_avx2) {
    return hash_xxhash64_avx2(input, len, seed);
  }
  return hash_xxhash64_scalar(input, len, seed);
#elif defined(ARCH_ARM64)
#if defined(__ARM_FEATURE_SVE)
  return hash_xxhash64_sve(input, len, seed);
#else
  return hash_xxhash64_neon(input, len, seed);
#endif
#else
  std::uint64_t h = seed ^ 14695981039346656037ULL;
  const auto *p = static_cast<const std::uint8_t *>(input);
  for (std::size_t i = 0; i < len; ++i) {
    h ^= p[i];
    h *= 1099511628211ULL;
  }
  return h;
#endif
}

std::uint64_t hash_string(const char *str, std::size_t len) noexcept {
  return hash_bytes(str, len, 0);
}

std::uint64_t hash_string(const std::string &str) noexcept {
  return hash_bytes(str.data(), str.size(), 0);
}

// ============================================================================
// 7. UTF-8 SIMD VALIDATION
// ============================================================================

#if defined(ARCH_ARM64)

TARGET_NEON
bool utf8_validate_neon(const char *str, std::size_t len) noexcept {
  const auto *data = reinterpret_cast<const std::uint8_t *>(str);
  std::size_t i = 0;
  const uint8x16_t msb_mask = vdupq_n_u8(0x80);

  // Fast path: Process 16B chunks using ARM NEON when all bytes are ASCII (< 0x80)
  for (; i + 16 <= len; i += 16) {
    uint8x16_t chunk = vld1q_u8(data + i);
    uint8x16_t has_msb = vandq_u8(chunk, msb_mask);

    if (vmaxvq_u8(has_msb) != 0) {
      std::size_t j = i;
      std::size_t block_end = i + 16;

      while (j < block_end && j < len) {
        std::uint8_t c = data[j];

        if (c < 0x80) {
          j++;
        } else if (c >= 0xC2 && c <= 0xDF) {
          // 2B sequence
          if (j + 1 >= len || (data[j + 1] & 0xC0) != 0x80)
            return false;
          j += 2;
        } else if (c >= 0xE0 && c <= 0xEF) {
          // 3B sequence
          if (j + 2 >= len)
            return false;
          std::uint8_t c2 = data[j + 1];
          std::uint8_t c3 = data[j + 2];

          // Reject overlongs (0xE0) and UTF-16 surrogates (0xED)
          if ((c == 0xE0 && c2 < 0xA0) || (c == 0xED && c2 > 0x9F) ||
              (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
            return false;
          }
          j += 3;
        } else if (c >= 0xF0 && c <= 0xF4) {
          // 4B sequence
          if (j + 3 >= len)
            return false;
          std::uint8_t c2 = data[j + 1];
          std::uint8_t c3 = data[j + 2];
          std::uint8_t c4 = data[j + 3];

          // Reject overlongs (0xF0) and values exceeding U+10FFFF (0xF4)
          if ((c == 0xF0 && c2 < 0x90) || (c == 0xF4 && c2 > 0x8F) ||
              (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80 ||
              (c4 & 0xC0) != 0x80) {
            return false;
          }
          j += 4;
        } else {
          return false; // Invalid leading byte
        }
      }
      i = j; // Re-synchronize loop index
    }
  }

  // Scalar fallback loop for remaining tail bytes
  while (i < len) {
    std::uint8_t c = data[i];

    if (c < 0x80) {
      i++;
    } else if (c >= 0xC2 && c <= 0xDF) {
      if (i + 1 >= len || (data[i + 1] & 0xC0) != 0x80)
        return false;
      i += 2;
    } else if (c >= 0xE0 && c <= 0xEF) {
      if (i + 2 >= len)
        return false;
      std::uint8_t c2 = data[i + 1];
      std::uint8_t c3 = data[i + 2];

      if ((c == 0xE0 && c2 < 0xA0) || (c == 0xED && c2 > 0x9F) ||
          (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
        return false;
      }
      i += 3;
    } else if (c >= 0xF0 && c <= 0xF4) {
      if (i + 3 >= len)
        return false;
      std::uint8_t c2 = data[i + 1];
      std::uint8_t c3 = data[i + 2];
      std::uint8_t c4 = data[i + 3];

      if ((c == 0xF0 && c2 < 0x90) || (c == 0xF4 && c2 > 0x8F) ||
          (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80 || (c4 & 0xC0) != 0x80) {
        return false;
      }
      i += 4;
    } else {
      return false;
    }
  }

  return true;
}

#endif

// Forward declaration for strict scalar validation
static bool utf8_scalar_validate_strict(const std::uint8_t *p,
                                        std::size_t n) noexcept;

#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)

[[nodiscard]] TARGET_AVX2 static bool
utf8_validate_avx2(const char *str, std::size_t len) noexcept {
  const auto *data = reinterpret_cast<const std::uint8_t *>(str);
  std::size_t i = 0;

  // Mask used to check if the Most Significant Bit (MSB) is set in any byte
  const __m256i msb_mask = _mm256_set1_epi8(static_cast<char>(0x80));

  // Process 32B chunks using AVX2 vectorization
  while (i + 32 <= len) {
    const __m256i input =
        _mm256_loadu_si256(reinterpret_cast<const __m256i *>(data + i));

    if (_mm256_testz_si256(input, msb_mask)) {
      i += 32;
      continue;
    }
    const std::size_t chunk_len = std::min<std::size_t>(32, len - i);
    if (!utf8_scalar_validate_strict(data + i, chunk_len)) {
      return false;
    }

    i += 32;
  }

  if (i < len) {
    return utf8_scalar_validate_strict(data + i, len - i);
  }

  return true;
}

#endif // ARCH_X86_64

// RFC 3629 compliant scalar fallback algorithm
static bool utf8_scalar_validate_strict(const std::uint8_t *p,
                                        std::size_t n) noexcept {
  std::size_t i = 0;
  while (i < n) {
    std::uint32_t c = p[i];

    // ASCII byte (0x00 - 0x7F)
    if (c < 0x80) {
      ++i;
      continue;
    }

    // Invalid lead byte (below 0xC2 or above 0xF4)
    if (c < 0xC2 || c > 0xF4) {
      return false;
    }

    // 2B sequence: 0xC2..0xDF 0x80..0xBF
    if (c <= 0xDF) {
      if (i + 1 >= n)
        return false;
      if ((p[i + 1] & 0xC0) != 0x80)
        return false;
      i += 2;
    }
    // 3B sequence: 0xE0..0xEF
    else if (c <= 0xEF) {
      if (i + 2 >= n)
        return false;
      const std::uint8_t c1 = p[i + 1];
      const std::uint8_t c2 = p[i + 2];

      if (c == 0xE0 && (c1 < 0xA0 || c1 > 0xBF))
        return false; // Overlong sequence
      else if (c == 0xED && (c1 < 0x80 || c1 > 0x9F))
        return false; // UTF-16 surrogate
      else if ((c1 & 0xC0) != 0x80)
        return false;

      if ((c2 & 0xC0) != 0x80)
        return false;
      i += 3;
    }
    // 4B sequence: 0xF0..0xF4
    else {
      if (i + 3 >= n)
        return false;
      const std::uint8_t c1 = p[i + 1];
      const std::uint8_t c2 = p[i + 2];
      const std::uint8_t c3 = p[i + 3];

      if (c == 0xF0 && (c1 < 0x90 || c1 > 0xBF))
        return false; // Overlong sequence
      else if (c == 0xF4 && (c1 < 0x80 || c1 > 0x8F))
        return false; // Out of U+10FFFF range
      else if ((c1 & 0xC0) != 0x80)
        return false;

      if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80)
        return false;
      i += 4;
    }
  }
  return true;
}

bool utf8_validate_simd(const char *str, std::size_t len) noexcept {
#if defined(ARCH_X86_64) || defined(__x86_64__) || defined(_M_X64)
  if (get_cpu_capabilities().has_avx2) {
    return utf8_validate_avx2(str, len);
  }
  return utf8_scalar_validate_strict(
      reinterpret_cast<const std::uint8_t *>(str), len);
#elif defined(ARCH_ARM64) || defined(__aarch64__)
  return utf8_validate_neon(str, len);
#else
  return utf8_scalar_validate_strict(
      reinterpret_cast<const std::uint8_t *>(str), len);
#endif
}

// ============================================================================
// 8. BASE64 ENCODING & DECODING
// ============================================================================

static constexpr char BASE64_CHARS[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                                       "abcdefghijklmnopqrstuvwxyz"
                                       "0123456789+/";

std::string base64_encode(const std::uint8_t *data, std::size_t len) {
  std::string ret;
  ret.reserve(((len + 2) / 3) * 4);

  std::uint32_t val = 0;
  int valb = -6;

  for (std::size_t i = 0; i < len; ++i) {
    val = (val << 8) + data[i];
    valb += 8;
    while (valb >= 0) {
      ret.push_back(BASE64_CHARS[(val >> valb) & 0x3F]);
      valb -= 6;
    }
  }

  if (valb > -6) {
    ret.push_back(BASE64_CHARS[((val << 8) >> (valb + 8)) & 0x3F]);
  }

  while (ret.size() % 4 != 0) {
    ret.push_back('=');
  }

  return ret;
}

std::vector<std::uint8_t> base64_decode(const std::string &input) {
  std::vector<int> T(256, -1);
  for (int i = 0; i < 64; i++) {
    T[static_cast<unsigned char>(BASE64_CHARS[i])] = i;
  }

  std::vector<std::uint8_t> out;
  out.reserve((input.size() / 4) * 3);

  std::uint32_t val = 0;
  int valb = -8;

  for (unsigned char c : input) {
    if (T[c] == -1) {
      if (c == '=')
        break;
      continue;
    }
    val = (val << 6) + T[c];
    valb += 6;
    if (valb >= 0) {
      out.push_back(static_cast<std::uint8_t>((val >> valb) & 0xFF));
      valb -= 8;
    }
  }

  return out;
}
// ============================================================================
// 9. FAST NUMERICAL FORMATTING & PARSING (itoa, dtoa, atoi)
// ============================================================================

// Pre-computed 2-digit lookup table ("00", "01", ..., "99")
alignas(64) static constexpr char DIGIT_PAIRS[200] = {
    '0', '0', '0', '1', '0', '2', '0', '3', '0', '4', '0', '5', '0', '6', '0',
    '7', '0', '8', '0', '9', '1', '0', '1', '1', '1', '2', '1', '3', '1', '4',
    '1', '5', '1', '6', '1', '7', '1', '8', '1', '9', '2', '0', '2', '1', '2',
    '2', '2', '3', '2', '4', '2', '5', '2', '6', '2', '7', '2', '8', '2', '9',
    '3', '0', '3', '1', '3', '2', '3', '3', '3', '4', '3', '5', '3', '6', '3',
    '7', '3', '8', '3', '9', '4', '0', '4', '1', '4', '2', '4', '3', '4', '4',
    '4', '5', '4', '6', '4', '7', '4', '8', '4', '9', '5', '0', '5', '1', '5',
    '2', '5', '3', '5', '4', '5', '5', '5', '6', '5', '7', '5', '8', '5', '9',
    '6', '0', '6', '1', '6', '2', '6', '3', '6', '4', '6', '5', '6', '6', '6',
    '7', '6', '8', '6', '9', '7', '0', '7', '1', '7', '2', '7', '3', '7', '4',
    '7', '5', '7', '6', '7', '7', '7', '8', '7', '9', '8', '0', '8', '1', '8',
    '2', '8', '3', '8', '4', '8', '5', '8', '6', '8', '7', '8', '8', '8', '9',
    '9', '0', '9', '1', '9', '2', '9', '3', '9', '4', '9', '5', '9', '6', '9',
    '7', '9', '8', '9', '9'};

ALWAYS_INLINE std::size_t u64toa_fast(std::uint64_t value,
                                      char *buffer) noexcept {
  constexpr std::size_t TEMP_BUF_SIZE = 32;
  char temp[TEMP_BUF_SIZE];
  char *p = temp + TEMP_BUF_SIZE;

  while (value >= 100) {
    const std::size_t idx = static_cast<std::size_t>((value % 100) * 2);
    value /= 100;
    p -= 2;

    std::uint16_t pair;
    std::memcpy(&pair, &DIGIT_PAIRS[idx], sizeof(pair));
    std::memcpy(p, &pair, sizeof(pair));
  }

  if (value < 10) {
    *--p = static_cast<char>('0' + value);
  } else {
    const std::size_t idx = static_cast<std::size_t>(value * 2);
    p -= 2;

    std::uint16_t pair;
    std::memcpy(&pair, &DIGIT_PAIRS[idx], sizeof(pair));
    std::memcpy(p, &pair, sizeof(pair));
  }

  const std::size_t len = static_cast<std::size_t>(temp + TEMP_BUF_SIZE - p);
  std::memcpy(buffer, p, len);
  buffer[len] = '\0';

  return len;
}

ALWAYS_INLINE std::size_t i64toa_fast(std::int64_t value,
                                      char *buffer) noexcept {
  if (value < 0) {
    *buffer = '-';
    std::uint64_t uval = 0ULL - static_cast<std::uint64_t>(value);
    return u64toa_fast(uval, buffer + 1) + 1;
  }
  return u64toa_fast(static_cast<std::uint64_t>(value), buffer);
}

ALWAYS_INLINE bool fast_atoi64(const char *str, std::size_t len,
                               std::int64_t &out) noexcept {
  if (len == 0)
    return false;

  std::size_t i = 0;
  bool negative = false;

  if (str[0] == '-') {
    negative = true;
    i++;
  } else if (str[0] == '+') {
    i++;
  }

  if (i == len)
    return false;

  // Thresholds for signed 64B bounds
  constexpr std::uint64_t kMaxAbsPositive = 9223372036854775807ULL; // INT64_MAX
  constexpr std::uint64_t kMaxAbsNegative =
      9223372036854775808ULL; // |INT64_MIN|

  std::uint64_t limit = negative ? kMaxAbsNegative : kMaxAbsPositive;
  std::uint64_t result = 0;

  for (; i < len; ++i) {
    char c = str[i];
    if (c < '0' || c > '9')
      return false;

    std::uint64_t digit = static_cast<std::uint64_t>(c - '0');

    // Check for unsigned overflow before accumulating
    if (result > (limit - digit) / 10) {
      return false;
    }

    result = result * 10 + digit;
  }

  // Safely assign without UB on INT64_MIN
  if (negative) {
    out = (result == kMaxAbsNegative) ? std::numeric_limits<std::int64_t>::min()
                                      : -static_cast<std::int64_t>(result);
  } else {
    out = static_cast<std::int64_t>(result);
  }

  return true;
}

ALWAYS_INLINE bool fast_atod(const char *str, std::size_t len,
                             double &out) noexcept {
  if (len == 0)
    return false;

  std::size_t i = 0;
  bool negative = false;

  if (str[0] == '-') {
    negative = true;
    i++;
  } else if (str[0] == '+') {
    i++;
  }

  if (i >= len)
    return false;

  static constexpr double POW10[] = {
      1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,  1e8,  1e9,  1e10, 1e11,
      1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
  static constexpr std::size_t MAX_POW10 = sizeof(POW10) / sizeof(POW10[0]) - 1;

  std::uint64_t mantissa = 0;
  std::size_t digits_parsed = 0;
  int exponent = 0;

  // Parse integer part
  for (; i < len && str[i] >= '0' && str[i] <= '9'; ++i) {
    if (digits_parsed < 19) { // 19 digits max to fit safely in uint64_t
      mantissa = mantissa * 10 + static_cast<std::uint64_t>(str[i] - '0');
      digits_parsed++;
    } else {
      exponent++;
    }
  }

  // Parse fractional part
  if (i < len && str[i] == '.') {
    i++;
    for (; i < len && str[i] >= '0' && str[i] <= '9'; ++i) {
      if (digits_parsed < 19) {
        mantissa = mantissa * 10 + static_cast<std::uint64_t>(str[i] - '0');
        digits_parsed++;
        exponent--; // Shift decimal point for each parsed fractional digit
      }
    }
  }

  if (digits_parsed == 0)
    return false;

  if (i < len && (str[i] == 'e' || str[i] == 'E')) {
    i++;
    if (i >= len)
      return false;

    bool exp_negative = false;
    if (str[i] == '-') {
      exp_negative = true;
      i++;
    } else if (str[i] == '+') {
      i++;
    }

    if (i >= len || str[i] < '0' || str[i] > '9')
      return false;

    int parsed_exp = 0;
    for (; i < len && str[i] >= '0' && str[i] <= '9'; ++i) {
      parsed_exp = parsed_exp * 10 + (str[i] - '0');
      if (parsed_exp > 308)
        break;
    }

    exponent += exp_negative ? -parsed_exp : parsed_exp;
  }

  if (i != len)
    return false;

  // Calculate final floating point value
  double val = static_cast<double>(mantissa);

  if (exponent < 0) {
    const std::size_t abs_exp = static_cast<std::size_t>(-exponent);
    const double scale = (abs_exp <= MAX_POW10)
                             ? POW10[abs_exp]
                             : std::pow(10.0, static_cast<double>(abs_exp));
    val /= scale;
  } else if (exponent > 0) {
    const std::size_t abs_exp = static_cast<std::size_t>(exponent);
    const double scale = (abs_exp <= MAX_POW10)
                             ? POW10[abs_exp]
                             : std::pow(10.0, static_cast<double>(abs_exp));
    val *= scale;
  }

  out = negative ? -val : val;
  return true;
}

// ============================================================================
// 10. FILE I/O UTILITIES & BENCHMARKING HELPERS
// ============================================================================

/**
 * Reads an entire file into a std::string buffer using SIMD-aligned allocation.
 */
inline std::string read_file_fast(const std::string &filepath) {
  std::ifstream file(filepath, std::ios::binary | std::ios::ate);
  if (!file.is_open()) {
    return "";
  }

  auto size = file.tellg();
  if (size <= 0) {
    return "";
  }

  std::string buffer;
  buffer.resize(static_cast<std::size_t>(size));

  file.seekg(0, std::ios::beg);
  file.read(&buffer[0], static_cast<std::streamsize>(size));

  return buffer;
}

/**
 * Writes raw memory bytes to a target file.
 */
inline bool write_file_fast(const std::string &filepath, const void *data,
                            std::size_t len) {
  std::ofstream file(filepath, std::ios::binary | std::ios::trunc);
  if (!file.is_open()) {
    return false;
  }

  file.write(static_cast<const char *>(data),
             static_cast<std::streamsize>(len));
  return file.good();
}

/**
 * Internal benchmarking utility to measure function execution speed
 */

template <typename T>
ALWAYS_INLINE void do_not_optimize(T const &value) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  asm volatile("" : : "r,m"(value) : "memory");
#else

  volatile T sink = value;
  (void)sink;
#endif
}

template <typename Func>
void benchmark_execution(const std::string &label, Func &&func,
                         std::size_t iterations = 1000) {
  CycleTimer timer;

  constexpr bool kReturnsVoid = std::is_void_v<std::invoke_result_t<Func &>>;

  // Warmup
  for (std::size_t i = 0; i < iterations / 10 + 1; ++i) {
    if constexpr (kReturnsVoid) {
      func();
    } else {
      do_not_optimize(func());
    }
  }

  timer.start();
  for (std::size_t i = 0; i < iterations; ++i) {
    if constexpr (kReturnsVoid) {
      func();
    } else {
      do_not_optimize(func());
    }
  }
  std::uint64_t total_cycles = timer.stop_cycles();
  std::uint64_t total_ns = timer.stop_ns();

  double avg_cycles =
      static_cast<double>(total_cycles) / static_cast<double>(iterations);
  double avg_ns =
      static_cast<double>(total_ns) / static_cast<double>(iterations);

  std::cout << "[BENCHMARK] " << std::left << std::setw(30) << label
            << " | Avg Cycles: " << std::right << std::setw(10) << std::fixed
            << std::setprecision(2) << avg_cycles
            << " | Avg Time: " << std::right << std::setw(10) << avg_ns << " ns"
            << std::endl;
}

/**
 * Validates hardware capabilities and prints debug status to stdout.
 */
inline void print_hardware_support() {
  CPUCapabilities caps = detect_cpu_capabilities();
  std::cout << "========================================================\n";
  std::cout << "          HARDWARE SIMD CAPABILITIES DETECTED           \n";
  std::cout << "========================================================\n";
#if defined(ARCH_X86_64)
  std::cout << " Architecture   : x86_64\n";
  std::cout << " AVX2 Support   : " << (caps.has_avx2 ? "YES" : "NO") << "\n";
  std::cout << " BMI2 Support   : " << (caps.has_bmi2 ? "YES" : "NO") << "\n";
  std::cout << " AVX512F Support: " << (caps.has_avx512f ? "YES" : "NO")
            << "\n";
  std::cout << " AVX512BW Sup.  : " << (caps.has_avx512bw ? "YES" : "NO")
            << "\n";
#elif defined(ARCH_ARM64)
  std::cout << " Architecture   : ARM64 / AArch64\n";
  std::cout << " NEON Support   : " << (caps.has_neon ? "YES" : "NO") << "\n";
  std::cout << " SVE Support    : " << (caps.has_sve ? "YES" : "NO") << "\n";
#else
  std::cout << " Architecture   : Generic (Fallback Scalar)\n";
#endif
  std::cout << "========================================================\n";
}

ALWAYS_INLINE std::string_view trim_left(std::string_view sv) noexcept {
  auto pos = sv.find_first_not_of(" \t\n\r\f\v");
  return (pos == std::string_view::npos) ? "" : sv.substr(pos);
}

ALWAYS_INLINE std::string_view trim_right(std::string_view sv) noexcept {
  auto pos = sv.find_last_not_of(" \t\n\r\f\v");
  return (pos == std::string_view::npos) ? "" : sv.substr(0, pos + 1);
}

std::string_view trim_view(std::string_view s) noexcept {
  return trim_right(trim_left(s));
}

std::string trim(std::string_view s) { return std::string(trim_view(s)); }

/// ============================================================================
// SIMD Function Implementations (Guarded SVE & AVX-512 Sections)
// ============================================================================

#if defined(ARCH_ARM64) && defined(HAS_SVE_HEADERS)
TARGET_SVE
void scale_array_sve(float *ptr, float factor, std::size_t count) noexcept {
  const svfloat32_t v_scale = svdup_n_f32(factor);
  std::size_t i = 0;
  const std::size_t vl =
      svcntw(); // Query hardware vector length (number of 32B elements)

  const svbool_t pg_all = svptrue_b32();

  // Unrolled main loop (process 2 full vector lengths per iteration)
  for (; i + 2 * vl <= count; i += 2 * vl) {
    svfloat32_t v0 = svld1_f32(pg_all, ptr + i);
    svfloat32_t v1 = svld1_f32(pg_all, ptr + i + vl);

    v0 = svmul_f32_x(pg_all, v0, v_scale);
    v1 = svmul_f32_x(pg_all, v1, v_scale);

    svst1_f32(pg_all, ptr + i, v0);
    svst1_f32(pg_all, ptr + i + vl, v1);
  }

  // Tail loop to handle remaining elements using predicate-driven operations
  svbool_t pg = svwhilelt_b32(i, count);
  while (svptest_any(svptrue_b32(), pg)) {
    svfloat32_t v = svld1_f32(pg, ptr + i);
    svfloat32_t r = svmul_f32_x(pg, v, v_scale);
    svst1_f32(pg, ptr + i, r);

    i += vl;
    pg = svwhilelt_b32(i, count);
  }
}
#endif // ARCH_ARM64 && HAS_SVE_HEADERS

#if defined(ARCH_X86_64)

/**
 * Inverts the case of all ASCII alphabetic characters
 */
TARGET_AVX512
void transform_string_avx512(char *data, std::size_t size) noexcept {
  std::size_t i = 0;

  // Set constant vectors for ASCII bounds and the case-flipping bit (0x20)
  const __m512i vec_a = _mm512_set1_epi8('a');
  const __m512i vec_z = _mm512_set1_epi8('z');
  const __m512i vec_A = _mm512_set1_epi8('A');
  const __m512i vec_Z = _mm512_set1_epi8('Z');
  const __m512i flip = _mm512_set1_epi8(0x20);

  // Main SIMD loop processing full 64B chunks
  for (; i + 64 <= size; i += 64) {
    __m512i vec = _mm512_loadu_si512(reinterpret_cast<const void *>(data + i));

    // Identify lowercase and uppercase ASCII character ranges
    __mmask64 is_lower =
        _mm512_cmpge_epu8_mask(vec, vec_a) & _mm512_cmple_epu8_mask(vec, vec_z);
    __mmask64 is_upper =
        _mm512_cmpge_epu8_mask(vec, vec_A) & _mm512_cmple_epu8_mask(vec, vec_Z);
    __mmask64 is_alpha = is_lower | is_upper;

    // Apply bitwise XOR (0x20) only to valid ASCII alphabetic characters
    __m512i toggled = _mm512_xor_si512(vec, flip);
    __m512i result = _mm512_mask_blend_epi8(is_alpha, vec, toggled);

    _mm512_storeu_si512(reinterpret_cast<void *>(data + i), result);
  }

  // Handle remaining tail elements (< 64B) using AVX-512 masks safely
  if (i < size) {
    const std::size_t remainder = size - i;
    const __mmask64 mask = (1ULL << remainder) - 1ULL;

    __m512i vec = _mm512_maskz_loadu_epi8(mask, data + i);

    __mmask64 is_lower =
        _mm512_cmpge_epu8_mask(vec, vec_a) & _mm512_cmple_epu8_mask(vec, vec_z);
    __mmask64 is_upper =
        _mm512_cmpge_epu8_mask(vec, vec_A) & _mm512_cmple_epu8_mask(vec, vec_Z);
    __mmask64 is_alpha = (is_lower | is_upper) & mask;

    __m512i toggled = _mm512_xor_si512(vec, flip);
    __m512i result = _mm512_mask_blend_epi8(is_alpha, vec, toggled);

    _mm512_mask_storeu_epi8(data + i, mask, result);
  }
}

#endif // ARCH_X86_64

// ============================================================================
// 11. COMPILER-GENERATED MULTIVERSIONING (target_clones / IFUNC)
// ============================================================================

#if defined(ARCH_X86_64) && ((defined(__GNUC__) && !defined(__clang__)) ||     \
                             (defined(__clang__) && __clang_major__ >= 12))
#define HAS_TARGET_CLONES_X86 1
#endif

#if defined(ARCH_ARM64) && defined(__GNUC__) && !defined(__clang__)
#define HAS_TARGET_CLONES_ARM64 1
#endif

#if defined(HAS_TARGET_CLONES_X86)

__attribute__((target_clones("avx512f,avx2,bmi,bmi2,sse4.2,default"))) void
to_lower_multiversioned(char *str, std::size_t len) noexcept {
  for (std::size_t i = 0; i < len; ++i) {
    const auto c = static_cast<unsigned char>(str[i]);
    if (c >= 'A' && c <= 'Z') {
      str[i] = static_cast<char>(c | 0x20);
    }
  }
}

/**
 * ASCII uppercase, auto-multiversioned. See to_lower_multiversioned.
 */
__attribute__((target_clones("avx512f,avx2,fma,sse2,default"))) void
to_upper_multiversioned(char *str, std::size_t len) noexcept {
  for (std::size_t i = 0; i < len; ++i) {
    const auto c = static_cast<unsigned char>(str[i]);
    if (c >= 'a' && c <= 'z') {
      str[i] = static_cast<char>(c & ~0x20);
    }
  }
}

__attribute__((target_clones("avx512f,avx2,fma,sse2,default"))) void
tensor_scale_multiversioned(float *__restrict__ ptr, std::size_t count,
                            float factor) noexcept {
  for (std::size_t i = 0; i < count; ++i) {
    ptr[i] *= factor;
  }
}

__attribute__((target_clones("avx512f", "avx2", "sse2", "default"))) bool
bytes_equal_multiversioned(const void *a, const void *b,
                           std::size_t n) noexcept {
  const auto *pa = static_cast<const std::uint8_t *>(a);
  const auto *pb = static_cast<const std::uint8_t *>(b);
  std::uint8_t diff = 0;
  for (std::size_t i = 0; i < n; ++i) {
    diff |= static_cast<std::uint8_t>(pa[i] ^ pb[i]);
  }
  return diff == 0;
}

#else // !HAS_TARGET_CLONES_X86

inline void to_lower_multiversioned(char *str, std::size_t len) noexcept {
  to_lower_simd(str, len);
}

inline void to_upper_multiversioned(char *str, std::size_t len) noexcept {
  to_upper_simd(str, len);
}

inline void tensor_scale_multiversioned(float *ptr, std::size_t count,
                                        float factor) noexcept {
  tensor_process_simd(ptr, count * sizeof(float), factor);
}

inline bool bytes_equal_multiversioned(const void *a, const void *b,
                                       std::size_t n) noexcept {
  return memcmp_adaptive(a, b, n) == 0;
}

#endif // HAS_TARGET_CLONES_X86

// ============================================================================
// Cycle counter and calibration (declared in utils.h)
// ============================================================================

std::uint64_t rdtsc() noexcept {
#if defined(SPHERE_ARCH_X86_64)
#if defined(_MSC_VER)
  return __rdtsc();
#else
  std::uint32_t low = 0;
  std::uint32_t high = 0;
  asm volatile("rdtsc" : "=a"(low), "=d"(high));
  return (static_cast<std::uint64_t>(high) << 32) | low;
#endif
#elif defined(SPHERE_ARCH_ARM64)
  std::uint64_t val = 0;
  asm volatile("mrs %0, cntvct_el0" : "=r"(val));
  return val;
#else
  return static_cast<std::uint64_t>(
      std::chrono::steady_clock::now().time_since_epoch().count());
#endif
}

std::uint64_t rdtsc_fenced() noexcept {
#if defined(SPHERE_ARCH_X86_64)
#if defined(_MSC_VER)
  _mm_lfence();
  return __rdtsc();
#else
  std::uint32_t low = 0;
  std::uint32_t high = 0;
  asm volatile("lfence\n\trdtsc" : "=a"(low), "=d"(high)::"memory");
  return (static_cast<std::uint64_t>(high) << 32) | low;
#endif
#elif defined(SPHERE_ARCH_ARM64)
  std::uint64_t val = 0;
  asm volatile("isb; mrs %0, cntvct_el0" : "=r"(val)::"memory");
  return val;
#else
  return rdtsc();
#endif
}

namespace {

/// Cycles per second, measured once against steady_clock.
struct Calibration {
  std::uint64_t cycles_per_sec{0};
  double ghz{0.0};
};

Calibration measure_calibration() noexcept {
  using clock = std::chrono::steady_clock;

  Calibration best{};
  double best_ghz = 0.0;

  for (int i = 0; i < 3; ++i) {
    const auto t1 = clock::now();
    const std::uint64_t c1 = rdtsc_fenced();
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    const std::uint64_t c2 = rdtsc_fenced();
    const auto t2 = clock::now();

    const auto ns =
        std::chrono::duration_cast<std::chrono::nanoseconds>(t2 - t1).count();
    if (ns <= 0 || c2 <= c1) {
      continue;
    }
    const double cycles = static_cast<double>(c2 - c1);
    const double ghz = cycles / static_cast<double>(ns);
    if (ghz > best_ghz) {
      best_ghz = ghz;
      best.ghz = ghz;
      best.cycles_per_sec = static_cast<std::uint64_t>(ghz * 1.0e9);
    }
  }

  if (best.cycles_per_sec == 0) {
    
    best.ghz = 1.0;
    best.cycles_per_sec = 1000000000ULL;
  }
  return best;
}

const Calibration &calibration() noexcept {
  // Function-local static: initialized exactly once, thread-safe since C++11.
  static const Calibration cal = measure_calibration();
  return cal;
}

} // namespace

void CycleTimer::ensure_calibrated() noexcept { (void)calibration(); }

double CycleTimer::frequency_ghz() noexcept { return calibration().ghz; }

std::uint64_t CycleTimer::cycles_per_second() noexcept {
  return calibration().cycles_per_sec;
}

std::uint64_t CycleTimer::tsc_to_ns(std::uint64_t cycles) noexcept {
  const double ghz = calibration().ghz;
  if (ghz <= 0.0) {
    return cycles;
  }
  return static_cast<std::uint64_t>(static_cast<double>(cycles) / ghz);
}

std::uint64_t CycleTimer::ns_to_tsc(std::uint64_t nanoseconds) noexcept {
  return static_cast<std::uint64_t>(static_cast<double>(nanoseconds) *
                                    calibration().ghz);
}

// ============================================================================
// Prefetch (declared in utils.h)
// ============================================================================

void prefetch_read(const void *ptr) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  __builtin_prefetch(ptr, 0, 3);
#elif defined(_M_X64) || defined(_M_IX86)
  _mm_prefetch(reinterpret_cast<const char *>(ptr), _MM_HINT_T0);
#elif defined(_M_ARM64)
  __prefetch(ptr);
#else
  (void)ptr;
#endif
}

void prefetch_write(const void *ptr) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  __builtin_prefetch(ptr, 1, 3);
#else
  prefetch_read(ptr);
#endif
}

void prefetch_stream(const void *ptr) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  __builtin_prefetch(ptr, 0, 0);
#elif defined(_M_X64) || defined(_M_IX86)
  _mm_prefetch(reinterpret_cast<const char *>(ptr), _MM_HINT_NTA);
#else
  (void)ptr;
#endif
}

// ============================================================================
// Public wrappers over the internal SIMD implementations
// ============================================================================

const CPUCapabilities &cpu_capabilities() noexcept {
  return get_cpu_capabilities();
}

void tensor_scale(void *raw, std::size_t bytes, float factor) noexcept {
  tensor_process_simd(raw, bytes, factor);
}

void to_lower_ascii(char *str, std::size_t len) noexcept {
  if (str != nullptr && len > 0) {
    to_lower_simd(str, len);
  }
}

void to_upper_ascii(char *str, std::size_t len) noexcept {
  if (str != nullptr && len > 0) {
    to_upper_simd(str, len);
  }
}

std::uint64_t mix64(std::uint64_t x) noexcept {
  return utils_mix_journal_seq(x);
}

std::size_t numa_hash(std::uint64_t key, std::size_t nodes) noexcept {
  return utils_numa_hash(key, nodes);
}

float backpressure_smooth(float level, float &prev_state,
                          float alpha) noexcept {
  return utils_backpressure_smooth(level, prev_state, alpha);
}

bool should_steal(std::uint64_t local, std::uint64_t remote,
                  std::uint64_t min_threshold) noexcept {
  return utils_should_steal(local, remote, min_threshold);
}

} // namespace utils
