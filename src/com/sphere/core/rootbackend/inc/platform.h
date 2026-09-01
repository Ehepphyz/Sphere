// platform.h

// OS abstraction: shared-memory mapping with RAII ownership.


#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <utility>

// -----------------------------------------------------------------------------
// Platform identification
// -----------------------------------------------------------------------------

#if defined(_WIN32) || defined(_WIN64)
#define SPHERE_OS_WINDOWS
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <io.h>
#include <windows.h>
#elif defined(__APPLE__) && defined(__MACH__)
#define SPHERE_OS_MACOS
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#elif defined(__linux__)
#define SPHERE_OS_LINUX
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#else
#error "Unsupported platform target."
#endif

namespace Sphere::Platform {

// -----------------------------------------------------------------------------
// Shared memory configuration flags
// -----------------------------------------------------------------------------

enum class ShmFlags : std::uint32_t {
  NONE = 0,
  HUGE_PAGES = 1 << 0,      // back the mapping with huge pages
  LOCK_IN_MEMORY = 1 << 1,  // mlock / VirtualLock the payload
  UNLINK_ON_CLOSE = 1 << 2, // remove the named object when the handle dies
  GUARD_PAGES = 1 << 3,     // PROT_NONE pages on both sides of the payload
  ANONYMOUS = 1 << 4,       // RAM-backed, no filesystem name
  READ_ONLY = 1 << 5,       // map read-only
  NUMA_AWARE = 1 << 6,      // apply a NUMA placement policy
  PREFAULT_PAGES = 1 << 7,  // touch every page up front
  NUMA_INTERLEAVE = 1 << 8, // interleave across nodes
  NUMA_LOCAL_ONLY = 1 << 9  // bind strictly to the local node
};

inline constexpr ShmFlags operator|(ShmFlags a, ShmFlags b) noexcept {
  return static_cast<ShmFlags>(static_cast<std::uint32_t>(a) |
                               static_cast<std::uint32_t>(b));
}

inline constexpr ShmFlags operator&(ShmFlags a, ShmFlags b) noexcept {
  return static_cast<ShmFlags>(static_cast<std::uint32_t>(a) &
                               static_cast<std::uint32_t>(b));
}

inline constexpr ShmFlags operator^(ShmFlags a, ShmFlags b) noexcept {
  return static_cast<ShmFlags>(static_cast<std::uint32_t>(a) ^
                               static_cast<std::uint32_t>(b));
}

inline constexpr ShmFlags operator~(ShmFlags a) noexcept {
  return static_cast<ShmFlags>(~static_cast<std::uint32_t>(a));
}

inline ShmFlags &operator|=(ShmFlags &a, ShmFlags b) noexcept {
  return a = a | b;
}

inline ShmFlags &operator&=(ShmFlags &a, ShmFlags b) noexcept {
  return a = a & b;
}

inline ShmFlags &operator^=(ShmFlags &a, ShmFlags b) noexcept {
  return a = a ^ b;
}

[[nodiscard]] inline constexpr bool has_flag(ShmFlags flags,
                                             ShmFlags test) noexcept {
  return (static_cast<std::uint32_t>(flags) &
          static_cast<std::uint32_t>(test)) != 0;
}

// -----------------------------------------------------------------------------
// ShmRegion
// -----------------------------------------------------------------------------

class ShmRegion {
public:
  static constexpr std::size_t kMaxNameLen = 64;

  ShmRegion() noexcept = default;

  ShmRegion(void *user_base, std::size_t size, const char *name = nullptr,
            ShmFlags flags = ShmFlags::NONE) noexcept
      : ShmRegion(user_base, size, user_base, size, name, flags) {}

  ShmRegion(void *user_base, std::size_t size, void *map_base,
            std::size_t map_size, const char *name, ShmFlags flags) noexcept
      : base_(user_base), size_(size), flags_(flags), map_base_(map_base),
        map_size_(map_size) {
    if (name != nullptr && name[0] != '\0') {
      std::size_t len = std::strlen(name);
      if (len >= kMaxNameLen) {
        len = kMaxNameLen - 1;
      }
      std::memcpy(name_, name, len);
      name_[len] = '\0';
    }
  }

  ~ShmRegion() noexcept { close(); }

  ShmRegion(const ShmRegion &) = delete;
  ShmRegion &operator=(const ShmRegion &) = delete;

  ShmRegion(ShmRegion &&other) noexcept
      : base_(std::exchange(other.base_, nullptr)),
        size_(std::exchange(other.size_, 0)),
        flags_(std::exchange(other.flags_, ShmFlags::NONE)),
        map_base_(std::exchange(other.map_base_, nullptr)),
        map_size_(std::exchange(other.map_size_, 0)) {
    std::memcpy(name_, other.name_, kMaxNameLen);
    other.name_[0] = '\0';
  }

  ShmRegion &operator=(ShmRegion &&other) noexcept {
    if (this != &other) {
      close();
      base_ = std::exchange(other.base_, nullptr);
      size_ = std::exchange(other.size_, 0);
      flags_ = std::exchange(other.flags_, ShmFlags::NONE);
      map_base_ = std::exchange(other.map_base_, nullptr);
      map_size_ = std::exchange(other.map_size_, 0);
      std::memcpy(name_, other.name_, kMaxNameLen);
      other.name_[0] = '\0';
    }
    return *this;
  }

  [[nodiscard]] void *data() noexcept { return base_; }
  [[nodiscard]] const void *data() const noexcept { return base_; }

  template <typename T> [[nodiscard]] T *as() noexcept {
    return static_cast<T *>(base_);
  }

  template <typename T> [[nodiscard]] const T *as() const noexcept {
    return static_cast<const T *>(base_);
  }

  [[nodiscard]] std::span<std::byte> bytes() noexcept {
    return {static_cast<std::byte *>(base_), size_};
  }

  [[nodiscard]] std::span<const std::byte> bytes() const noexcept {
    return {static_cast<const std::byte *>(base_), size_};
  }

  [[nodiscard]] std::size_t size() const noexcept { return size_; }
  [[nodiscard]] const char *name() const noexcept { return name_; }
  [[nodiscard]] ShmFlags flags() const noexcept { return flags_; }

  [[nodiscard]] bool is_valid() const noexcept { return base_ != nullptr; }
  [[nodiscard]] explicit operator bool() const noexcept { return is_valid(); }

  /// Unmaps the region and releases the OS resources. Idempotent.
  void close() noexcept;

private:
  void *base_{nullptr};
  std::size_t size_{0};
  ShmFlags flags_{ShmFlags::NONE};
  char name_[kMaxNameLen]{0};
  void *map_base_{nullptr};
  std::size_t map_size_{0};
};

/**
 * Creates (or truncates to size) a named or anonymous shared-memory region
 */
ShmRegion shm_create(const char *name, std::size_t size,
                     ShmFlags flags = ShmFlags::NONE);

/**
 * Opens an existing named region
 */
ShmRegion shm_open(const char *name, std::size_t size,
                   ShmFlags flags = ShmFlags::NONE);

/// Removes a named region from the filesystem namespace. Safe if absent.
void shm_remove(const char *name) noexcept;

/// System page size, cached after the first call.
[[nodiscard]] std::size_t get_page_size() noexcept;

} // namespace Sphere::Platform
