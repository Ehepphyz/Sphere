// platform.h

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <iostream>
#include <span>
#include <utility>

// -----------------------------------------------------------------------------
// Platform OS Identifier Macros & Headers
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

// Optional Linux Kernel-Bypass High-Performance I/O Header
#if defined(HAVE_IO_URING)
#include <liburing.h>
#endif

namespace Sphere::Platform {

// -----------------------------------------------------------------------------
// Shared Memory (SHM) Configuration Flags
// -----------------------------------------------------------------------------

/**
 * Bitmask flags configuring Shared Memory (SHM) allocation and NUMA mapping.
 */
enum class ShmFlags : std::uint32_t {
  NONE = 0,
  HUGE_PAGES = 1 << 0, // Allocate via OS huge pages (2MB / 1GB)
  LOCK_IN_MEMORY =
      1 << 1, // Lock pages in RAM to prevent swapping (mlock / VirtualLock)
  UNLINK_ON_CLOSE = 1 << 2, // Remove named SHM entry upon handle destruction
  GUARD_PAGES = 1 << 3,     // Place PROT_NONE pages around region bounds
  ANONYMOUS = 1 << 4,  // Back allocation with RAM instead of a file descriptor
  READ_ONLY = 1 << 5,  // Map region with read-only permissions
  NUMA_AWARE = 1 << 6, // Bind memory pages to explicit NUMA nodes
  PREFAULT_PAGES = 1 << 7,  // Force immediate page-table instantiation
  NUMA_INTERLEAVE = 1 << 8, // Interleave pages across available NUMA nodes
  NUMA_LOCAL_ONLY =
      1 << 9 // Restrict allocation strictly to the local NUMA node
};

// Bitwise Operators
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

/**
 * Checks if a specific flag is set within a ShmFlags bitmask
 */
[[nodiscard]] inline constexpr bool has_flag(ShmFlags flags,
                                             ShmFlags test) noexcept {
  return (static_cast<std::uint32_t>(flags) &
          static_cast<std::uint32_t>(test)) != 0;
}

// -----------------------------------------------------------------------------
// RAII Handle: ShmRegion
// -----------------------------------------------------------------------------

/**
 * RAII wrapper managing the lifecycle and mapping of Shared Memory regions.
 */
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
      if (len >= kMaxNameLen)
        len = kMaxNameLen - 1;
      std::memcpy(name_, name, len);
      name_[len] = '\0';
    }
  }

  ~ShmRegion() noexcept { close(); }

  // Enforce single ownership semantics
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

  /**
   * Gets a pointer to the mapped memory region payload.
   */
  [[nodiscard]] void *data() noexcept { return base_; }
  [[nodiscard]] const void *data() const noexcept { return base_; }

  /**
   * Casts the mapped base memory address to a typed pointer.
   */
  template <typename T> [[nodiscard]] T *as() noexcept {
    return static_cast<T *>(base_);
  }

  template <typename T> [[nodiscard]] const T *as() const noexcept {
    return static_cast<const T *>(base_);
  }

  /**
   * Provides a view over the raw byte range.
   */
  [[nodiscard]] std::span<std::byte> bytes() noexcept {
    return {static_cast<std::byte *>(base_), size_};
  }

  [[nodiscard]] std::span<const std::byte> bytes() const noexcept {
    return {static_cast<const std::byte *>(base_), size_};
  }

  /**
   * Returns the payload size of the memory region in bytes.
   */
  [[nodiscard]] std::size_t size() const noexcept { return size_; }

  /**
   * Retrieves the null-terminated shared memory region identifier name.
   */
  [[nodiscard]] const char *name() const noexcept { return name_; }

  /**
   * Retrieves the bitmask flags associated with this allocation.
   */
  [[nodiscard]] ShmFlags flags() const noexcept { return flags_; }

  /**
   * Validates if the memory mapping address is non-null.
   */
  [[nodiscard]] bool is_valid() const noexcept { return base_ != nullptr; }
  [[nodiscard]] explicit operator bool() const noexcept { return is_valid(); }

  /**
   * Unmaps the shared memory segment and releases associated OS resources.
   */
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
 * Allocates and maps a new named or anonymous shared memory segment
 */
ShmRegion shm_create(const char *name, std::size_t size,
                     ShmFlags flags = ShmFlags::NONE);

/**
 * Maps an existing named shared memory segment into the process address space
 */
ShmRegion shm_open(const char *name, std::size_t size,
                   ShmFlags flags = ShmFlags::NONE);

// -----------------------------------------------------------------------------
// Asynchronous File I/O Engine with io_uring / POSIX / Windows Fallback
// -----------------------------------------------------------------------------

/**
 * Asynchronous file loader featuring automatic runtime
 * backend (io_uring on Linux, Windows Overlapped I/O, or POSIX pread)
 */
class AsyncFileLoader {
public:
  AsyncFileLoader() {
#if defined(HAVE_IO_URING)
    if (io_uring_queue_init(64, &ring_, 0) == 0) {
      using_uring_ = true;
      std::cout
          << "[Platform::AsyncFileLoader] Initialized with io_uring backend.\n";
    } else {
      std::cout << "[Platform::AsyncFileLoader] io_uring initialization "
                   "failed. Falling back to POSIX.\n";
    }
#else
    std::cout << "[Platform::AsyncFileLoader] Compiled without io_uring "
                 "support. Using portable fallback.\n";
#endif
  }

  ~AsyncFileLoader() {
#if defined(HAVE_IO_URING)
    if (using_uring_) {
      io_uring_queue_exit(&ring_);
    }
#endif
  }

  /**
   * Submits a non-blocking read operation on the given file descriptor
   */
  bool submit_read(int fd, void *buffer, std::size_t size, std::uint64_t offset,
                   std::uint64_t user_data) {
#if defined(HAVE_IO_URING)
    if (using_uring_) {
      struct io_uring_sqe *sqe = io_uring_get_sqe(&ring_);
      if (!sqe)
        return false;

      io_uring_prep_read(sqe, fd, buffer, static_cast<unsigned int>(size),
                         offset);
      io_uring_sqe_set_data64(sqe, user_data);
      io_uring_submit(&ring_);
      return true;
    }
#endif

#if defined(SPHERE_OS_WINDOWS)
    DWORD bytes_read = 0;
    HANDLE hFile = reinterpret_cast<HANDLE>(_get_osfhandle(fd));
    OVERLAPPED overlapped = {};
    overlapped.Offset = static_cast<DWORD>(offset);
    overlapped.OffsetHigh = static_cast<DWORD>(offset >> 32);

    if (ReadFile(hFile, buffer, static_cast<DWORD>(size), &bytes_read,
                 &overlapped)) {
      pending_fallback_data_ = user_data;
      pending_fallback_bytes_ = static_cast<int>(bytes_read);
      return true;
    }
    return false;
#else
    ssize_t res = ::pread(fd, buffer, size, static_cast<off_t>(offset));
    pending_fallback_data_ = user_data;
    pending_fallback_bytes_ = static_cast<int>(res);
    return true;
#endif
  }

  /**
   * Polls pending completion events and executes the provided callback
   */
  template <typename Callback> void poll_completions(Callback &&on_complete) {
#if defined(HAVE_IO_URING)
    if (using_uring_) {
      struct io_uring_cqe *cqe = nullptr;
      unsigned head = 0;

      io_uring_for_each_cqe(&ring_, head, cqe) {
        std::uint64_t user_data = io_uring_cqe_get_data64(cqe);
        int res = cqe->res;
        on_complete(user_data, res);
      }

      io_uring_cq_advance(&ring_, head);
      return;
    }
#endif

    if (pending_fallback_bytes_ != -1) {
      on_complete(pending_fallback_data_, pending_fallback_bytes_);
      pending_fallback_bytes_ = -1;
    }
  }

private:
  [[maybe_unused]] bool using_uring_{false};
  std::uint64_t pending_fallback_data_{0};
  int pending_fallback_bytes_{-1};

#if defined(HAVE_IO_URING)
  struct io_uring ring_{};
#endif
};

} // namespace Sphere::Platform
