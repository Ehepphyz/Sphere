// isa_allocator.h

// Memory sized and aligned for the host CPU's vector unit, with huge pages
// under the large blocks.

#pragma once

#include "common_config.h"

#include <cstddef>
#include <limits>
#include <memory>
#include <memory_resource>
#include <new>
#include <string>
#include <type_traits>
#include <vector>

namespace Sphere::Memory {

// -----------------------------------------------------------------------------
// What the host CPU asks for
// -----------------------------------------------------------------------------

/// Widest vector register on this CPU, in bytes: 64 with AVX-512, 32 with AVX2,
/// 16 with SSE or NEON, and the measured vector length with SVE.
[[nodiscard]] std::size_t isa_register_bytes() noexcept;

/**
 * The boundary every block starts on, and the multiple its size is padded to.
 *
 * SIMD_ALIGNMENT is the floor, since that is what the shared-memory ABI is laid
 * out on. A CPU whose registers are wider than the floor raises it, so the same
 * binary stays correct on a machine the build host never saw.
 */
[[nodiscard]] std::size_t isa_alignment() noexcept;

// -----------------------------------------------------------------------------
// Raw blocks
// -----------------------------------------------------------------------------

/// A block this size or larger is offered to huge pages.
inline constexpr std::size_t HUGE_PAGE_THRESHOLD = HUGE_PAGE_FALLBACK;

/**
 * Aligned, padded block. Returns nullptr rather than throwing.
 *
 * `alignment` of zero means isa_alignment(). A block large enough to be worth
 * a huge page takes one when the kernel has a pool, and ordinary pages when it
 * has none: the caller sees no difference beyond the TLB pressure.
 */
[[nodiscard]] void *isa_allocate(std::size_t bytes,
                                 std::size_t alignment = 0) noexcept;

/// Releases a block returned by isa_allocate. A null pointer is accepted.
void isa_release(void *block) noexcept;

/// Bytes the block really holds, padding included. Zero if it is not ours.
[[nodiscard]] std::size_t isa_block_bytes(const void *block) noexcept;

/// Huge page size backing the block, in bytes. Zero means ordinary pages.
[[nodiscard]] std::size_t isa_block_page_size(const void *block) noexcept;

// -----------------------------------------------------------------------------
// STL allocator
// -----------------------------------------------------------------------------

/**
 * Allocator for the standard containers: std::vector, std::basic_string and
 * the rest take it as their last template argument.
 *
 * Stateless, so two instances always compare equal and a container can be moved
 * without reallocating.
 */
template <typename T> class ISAAllocator {
public:
  using value_type = T;
  using size_type = std::size_t;
  using difference_type = std::ptrdiff_t;
  using propagate_on_container_move_assignment = std::true_type;
  using propagate_on_container_copy_assignment = std::false_type;
  using propagate_on_container_swap = std::false_type;
  using is_always_equal = std::true_type;

  template <typename U> struct rebind {
    using other = ISAAllocator<U>;
  };

  constexpr ISAAllocator() noexcept = default;

  // Not explicit: a container rebinds the allocator for its own node type and
  // expects the conversion to happen on its own.
  template <typename U>
  constexpr ISAAllocator(const ISAAllocator<U> &) noexcept {}

  [[nodiscard]] T *allocate(std::size_t count) {
    if (count > max_size()) {
      throw std::bad_array_new_length();
    }
    void *const block = isa_allocate(count * sizeof(T), alignment_for());
    if (block == nullptr) {
      throw std::bad_alloc();
    }
    return static_cast<T *>(block);
  }

  void deallocate(T *block, std::size_t) noexcept { isa_release(block); }

  [[nodiscard]] std::size_t max_size() const noexcept {
    return (std::numeric_limits<std::size_t>::max)() / sizeof(T);
  }

  template <typename U>
  friend constexpr bool operator==(const ISAAllocator &,
                                   const ISAAllocator<U> &) noexcept {
    return true;
  }

  template <typename U>
  friend constexpr bool operator!=(const ISAAllocator &,
                                   const ISAAllocator<U> &) noexcept {
    return false;
  }

private:
  /// An over-aligned element type keeps its own requirement.
  [[nodiscard]] static std::size_t alignment_for() noexcept {
    const std::size_t wanted = isa_alignment();
    return (alignof(T) > wanted) ? alignof(T) : wanted;
  }
};

/// Containers already spelled out, for the two cases that come up most.
template <typename T> using isa_vector = std::vector<T, ISAAllocator<T>>;
using isa_string =
    std::basic_string<char, std::char_traits<char>, ISAAllocator<char>>;

// -----------------------------------------------------------------------------
// Polymorphic adapter (C++17 / C++20 std::pmr)
// -----------------------------------------------------------------------------

/**
 * The same memory behind std::pmr, for the containers that carry a resource
 * pointer instead of an allocator type.
 *
 * Two resources are equal only when they are the same object, which is what
 * lets a pmr container tell that another container's memory is not its to free.
 */
class ISAMemoryResource final : public std::pmr::memory_resource {
protected:
  void *do_allocate(std::size_t bytes, std::size_t alignment) override;
  void do_deallocate(void *block, std::size_t bytes,
                     std::size_t alignment) noexcept override;
  [[nodiscard]] bool
  do_is_equal(const std::pmr::memory_resource &other) const noexcept override;
};

/// Process-wide resource. Pass it to a pmr container, or to set_default_resource.
[[nodiscard]] ISAMemoryResource *isa_resource() noexcept;

} // namespace Sphere::Memory
