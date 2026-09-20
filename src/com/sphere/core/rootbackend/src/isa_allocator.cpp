// isa_allocator.cpp

// Blocks aligned on the host CPU's vector width, padded so a vector read of the
// tail stays inside the block, and backed by huge pages once they are worth it.

#include "isa_allocator.h"

#include "platform.h"
#include "utils.h"

#include <cstdint>
#include <cstring>

#if defined(SPHERE_OS_LINUX) || defined(SPHERE_OS_MACOS)
#include <sys/mman.h>
#endif

#if defined(__aarch64__) && defined(HAS_SVE_HEADERS)
#include <arm_sve.h>
#endif

namespace Sphere::Memory {

namespace {

/// Written just below every payload, so one release call serves both paths.
struct BlockTag {
  std::uint32_t magic;
  std::uint32_t huge_page_size_kb; // 0 when the block sits on ordinary pages
  std::uint64_t mapped_bytes;      // what goes back to the OS
  std::uint64_t user_bytes;        // payload size, padding included
  std::uint64_t prefix_bytes;      // payload address minus allocation address
};

constexpr std::uint32_t kTagMagic = 0x49534142; // 'ISAB'

static_assert(sizeof(BlockTag) <= SIMD_ALIGNMENT,
              "The tag must fit in the prefix that precedes a payload.");

[[nodiscard]] std::size_t round_up(std::size_t value,
                                   std::size_t multiple) noexcept {
  return (multiple == 0) ? value
                         : ((value + multiple - 1) & ~(multiple - 1));
}

[[nodiscard]] BlockTag *tag_of(void *payload) noexcept {
  return reinterpret_cast<BlockTag *>(static_cast<std::uint8_t *>(payload) -
                                      sizeof(BlockTag));
}

[[nodiscard]] const BlockTag *tag_of(const void *payload) noexcept {
  return reinterpret_cast<const BlockTag *>(
      static_cast<const std::uint8_t *>(payload) - sizeof(BlockTag));
}

/// Huge-page block, or nullptr when the kernel has none to give.
[[nodiscard]] void *map_huge(std::size_t total, std::size_t huge,
                             std::size_t &mapped) noexcept {
#if defined(SPHERE_OS_LINUX) && defined(MAP_HUGETLB)
  int shift = 0;
  while ((std::size_t{1} << shift) < huge && shift < 63) {
    ++shift;
  }
  const std::size_t attempt = round_up(total, huge);
  void *base = ::mmap(nullptr, attempt, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS | MAP_HUGETLB |
                          (shift << MAP_HUGE_SHIFT),
                      -1, 0);
  if (base == MAP_FAILED) {
    return nullptr;
  }
  mapped = attempt;
  return base;
#elif defined(SPHERE_OS_WINDOWS)
  const std::size_t attempt = round_up(total, huge);
  void *base = ::VirtualAlloc(nullptr, attempt,
                              MEM_RESERVE | MEM_COMMIT | MEM_LARGE_PAGES,
                              PAGE_READWRITE);
  if (base == nullptr) {
    return nullptr;
  }
  mapped = attempt;
  return base;
#else
  (void)total;
  (void)huge;
  (void)mapped;
  return nullptr;
#endif
}

void unmap_huge(void *base, std::size_t mapped) noexcept {
#if defined(SPHERE_OS_LINUX) && defined(MAP_HUGETLB)
  (void)::munmap(base, mapped);
#elif defined(SPHERE_OS_WINDOWS)
  (void)mapped;
  (void)::VirtualFree(base, 0, MEM_RELEASE);
#else
  (void)base;
  (void)mapped;
#endif
}

} // namespace

std::size_t isa_register_bytes() noexcept {
  const utils::CPUCapabilities &cpu = utils::cpu_capabilities();

#if defined(__aarch64__) && defined(HAS_SVE_HEADERS)
  if (cpu.has_sve) {
    // SVE registers are as wide as the silicon chose, from 16 to 256 bytes.
    return static_cast<std::size_t>(svcntb());
  }
#endif
  if (cpu.has_avx512f) {
    return 64;
  }
  if (cpu.has_avx2) {
    return 32;
  }
  return 16; // SSE, NEON, and anything older
}

std::size_t isa_alignment() noexcept {
  static const std::size_t chosen = [] {
    const std::size_t registers = isa_register_bytes();
    return (registers > SIMD_ALIGNMENT) ? round_up(registers, SIMD_ALIGNMENT)
                                        : SIMD_ALIGNMENT;
  }();
  return chosen;
}

void *isa_allocate(std::size_t bytes, std::size_t alignment) noexcept {
  if (bytes == 0) {
    return nullptr;
  }

  std::size_t align = (alignment == 0) ? isa_alignment() : alignment;
  if (align < SIMD_ALIGNMENT) {
    align = SIMD_ALIGNMENT;
  }
  if ((align & (align - 1)) != 0) {
    return nullptr; // not a power of two
  }

  const std::size_t padded = round_up(bytes, align);
  if (padded < bytes) {
    return nullptr; // rounding overflowed
  }

  // The prefix holds the tag and keeps the payload on its boundary.
  const std::size_t prefix = align;
  const std::size_t total = prefix + padded;
  if (total < padded) {
    return nullptr;
  }

  std::uint8_t *base = nullptr;
  std::size_t mapped = 0;
  std::size_t huge = 0;

  if (padded >= HUGE_PAGE_THRESHOLD) {
    huge = Platform::usable_huge_page_size();
    // A page larger than the block itself wastes more than it saves.
    if (huge > total) {
      huge = HUGE_PAGE_FALLBACK;
    }
    if (huge != 0) {
      base = static_cast<std::uint8_t *>(map_huge(total, huge, mapped));
      if (base == nullptr && huge != HUGE_PAGE_FALLBACK) {
        huge = HUGE_PAGE_FALLBACK;
        base = static_cast<std::uint8_t *>(map_huge(total, huge, mapped));
      }
      if (base == nullptr) {
        huge = 0;
      }
    }
  }

  if (base == nullptr) {
    base = static_cast<std::uint8_t *>(utils::aligned_alloc_simd(align, total));
    if (base == nullptr) {
      return nullptr;
    }
    mapped = 0;
  }

  std::uint8_t *const payload = base + prefix;
  BlockTag *const tag = tag_of(payload);
  tag->magic = kTagMagic;
  tag->huge_page_size_kb = static_cast<std::uint32_t>(huge / 1024);
  tag->mapped_bytes = mapped;
  tag->user_bytes = padded;
  tag->prefix_bytes = prefix;
  return payload;
}

void isa_release(void *block) noexcept {
  if (block == nullptr) {
    return;
  }
  BlockTag *const tag = tag_of(block);
  if (tag->magic != kTagMagic) {
    return; // not ours: releasing it would corrupt someone else's heap
  }

  std::uint8_t *const base =
      static_cast<std::uint8_t *>(block) - tag->prefix_bytes;
  const std::size_t mapped = static_cast<std::size_t>(tag->mapped_bytes);
  tag->magic = 0;

  if (mapped != 0) {
    unmap_huge(base, mapped);
  } else {
    utils::aligned_free_simd(base);
  }
}

std::size_t isa_block_bytes(const void *block) noexcept {
  if (block == nullptr) {
    return 0;
  }
  const BlockTag *const tag = tag_of(block);
  return (tag->magic == kTagMagic) ? static_cast<std::size_t>(tag->user_bytes)
                                   : 0;
}

std::size_t isa_block_page_size(const void *block) noexcept {
  if (block == nullptr) {
    return 0;
  }
  const BlockTag *const tag = tag_of(block);
  return (tag->magic == kTagMagic)
             ? static_cast<std::size_t>(tag->huge_page_size_kb) * 1024
             : 0;
}

// -----------------------------------------------------------------------------
// std::pmr adapter
// -----------------------------------------------------------------------------

void *ISAMemoryResource::do_allocate(std::size_t bytes,
                                     std::size_t alignment) {
  void *const block = isa_allocate(bytes, alignment);
  if (block == nullptr) {
    throw std::bad_alloc();
  }
  return block;
}

void ISAMemoryResource::do_deallocate(void *block, std::size_t,
                                      std::size_t) noexcept {
  isa_release(block);
}

bool ISAMemoryResource::do_is_equal(
    const std::pmr::memory_resource &other) const noexcept {
  return this == &other;
}

ISAMemoryResource *isa_resource() noexcept {
  static ISAMemoryResource resource;
  return &resource;
}

} // namespace Sphere::Memory
