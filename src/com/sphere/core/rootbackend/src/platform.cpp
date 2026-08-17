// platform.cpp
#include "platform.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>

#if defined(_WIN32)
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#if defined(__linux__)
#include <linux/mempolicy.h>
#include <sched.h>
#include <sys/syscall.h>
#endif
#endif

namespace Sphere::Platform {

/**
 * Ensures POSIX shared memory names begin with a leading slash.
 */
std::string sanitize_shm_name(const char *name) {
  if (name == nullptr || name[0] == '\0') {
    return "/default_shm";
  }
  if (name[0] == '/') {
    return std::string(name);
  }
  return "/" + std::string(name);
}

#if !defined(_WIN32)
std::size_t get_page_size() {
  long p = ::sysconf(_SC_PAGESIZE);
  if (p <= 0) {
    return 4096;
  }
  return static_cast<std::size_t>(p);
}
#else
std::size_t get_page_size() {
  SYSTEM_INFO si;
  GetSystemInfo(&si);
  return static_cast<std::size_t>(si.dwPageSize);
}
#endif

/**
 * Touches every memory page to trigger page faulting up front.
 */
void prefault_region(void *base, std::size_t size, bool read_only) {
  auto *ptr = static_cast<volatile std::uint8_t *>(base);
  const std::size_t page = get_page_size();
  for (std::size_t off = 0; off < size; off += page) {
    if (read_only) {
      std::uint8_t dummy = ptr[off];
      (void)dummy;
    } else {
      ptr[off] = ptr[off];
    }
  }
}

#if defined(__linux__)
/**
 * Configures NUMA memory allocation policy on Linux systems.
 */
void apply_numa_policy(void *base, std::size_t size, ShmFlags flags) {
  const bool has_numa = has_flag(flags, ShmFlags::NUMA_AWARE) ||
                        has_flag(flags, ShmFlags::NUMA_INTERLEAVE) ||
                        has_flag(flags, ShmFlags::NUMA_LOCAL_ONLY);

  if (!has_numa) {
    return;
  }

  struct {
    unsigned cpu;
    unsigned node;
  } info{};

  if (::syscall(SYS_getcpu, &info.cpu, &info.node, nullptr) == 0) {
    const unsigned long nodemask = (1UL << info.node);
    const unsigned long maxnode = sizeof(nodemask) * 8; // Bitmask size in bits
    int mode = MPOL_PREFERRED;

    if (has_flag(flags, ShmFlags::NUMA_INTERLEAVE)) {
      mode = MPOL_INTERLEAVE;
    } else if (has_flag(flags, ShmFlags::NUMA_LOCAL_ONLY)) {
      mode = MPOL_BIND;
    }

    ::syscall(SYS_mbind, base, size, mode, &nodemask, maxnode, 0);
  }
}
#else
void apply_numa_policy(void *base, std::size_t size, ShmFlags flags) {
  (void)base;
  (void)size;
  (void)flags;
}
#endif

#if defined(_WIN32)

// =============================================================================
// Windows Implementation
// =============================================================================

ShmRegion shm_create(const char *name, std::size_t size, ShmFlags flags) {
  DWORD flProtect = PAGE_READWRITE;
  if (has_flag(flags, ShmFlags::HUGE_PAGES)) {
    SIZE_T lp = GetLargePageMinimum();
    if (lp != 0) {
      size = (size + lp - 1) & ~(lp - 1);
    }
    flProtect |= SEC_LARGE_PAGES;
  }

  HANDLE hMap = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, flProtect,
                                   static_cast<DWORD>(size >> 32),
                                   static_cast<DWORD>(size & 0xFFFFFFFF), name);
  if (hMap == nullptr) {
    throw std::runtime_error("CreateFileMapping failed");
  }

  const DWORD mapAccess = has_flag(flags, ShmFlags::READ_ONLY)
                              ? FILE_MAP_READ
                              : FILE_MAP_ALL_ACCESS;
  void *base = MapViewOfFile(hMap, mapAccess, 0, 0, size);
  CloseHandle(hMap);

  if (base == nullptr) {
    throw std::runtime_error("MapViewOfFile failed");
  }

  // Wrap inside RAII handle to ensure automatic cleanup if post-processing
  // fails
  ShmRegion region(base, size, name, flags);

  apply_numa_policy(base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    VirtualLock(base, size);
  }

  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(base, size, has_flag(flags, ShmFlags::READ_ONLY));
  }

  return region;
}

ShmRegion shm_open(const char *name, std::size_t size, ShmFlags flags) {
  const DWORD mapAccess = has_flag(flags, ShmFlags::READ_ONLY)
                              ? FILE_MAP_READ
                              : FILE_MAP_ALL_ACCESS;

  HANDLE hMap = OpenFileMappingA(mapAccess, FALSE, name);
  if (hMap == nullptr) {
    throw std::runtime_error("OpenFileMapping failed");
  }

  void *base = MapViewOfFile(hMap, mapAccess, 0, 0, size);
  CloseHandle(hMap);

  if (base == nullptr) {
    throw std::runtime_error("MapViewOfFile failed");
  }

  ShmRegion region(base, size, name, flags);

  apply_numa_policy(base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    VirtualLock(base, size);
  }

  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(base, size, has_flag(flags, ShmFlags::READ_ONLY));
  }

  return region;
}

void ShmRegion::close() noexcept {
  if (base_ != nullptr) {
    if (has_flag(flags_, ShmFlags::LOCK_IN_MEMORY)) {
      VirtualUnlock(base_, size_);
    }

    void *raw = map_base_ ? map_base_ : base_;
    UnmapViewOfFile(raw);

    base_ = nullptr;
    size_ = 0;
    map_base_ = nullptr;
    map_size_ = 0;
  }
}

#else

// =============================================================================
// POSIX Linux Implementation
// =============================================================================

ShmRegion shm_create(const char *name, std::size_t size, ShmFlags flags) {
  const std::string shm_path = sanitize_shm_name(name);
  const bool use_anonymous = has_flag(flags, ShmFlags::ANONYMOUS);
  const bool is_read_only = has_flag(flags, ShmFlags::READ_ONLY);

  const std::size_t page = get_page_size();
  const bool use_guard = has_flag(flags, ShmFlags::GUARD_PAGES) &&
                         !has_flag(flags, ShmFlags::HUGE_PAGES);
  const std::size_t aligned_user_size = (size + page - 1) & ~(page - 1);
  std::size_t map_size = size;

#if defined(MAP_HUGETLB)
  if (has_flag(flags, ShmFlags::HUGE_PAGES)) {
#ifdef MAP_HUGE_1GB
    constexpr std::size_t huge_page_size = 1ULL << 30;
#else
    constexpr std::size_t huge_page_size = 2 * 1024 * 1024;
#endif
    map_size = (map_size + huge_page_size - 1) & ~(huge_page_size - 1);
  }
#endif

  if (use_guard) {
    map_size = aligned_user_size + 2 * page;
  }

  int fd = -1;
  if (!use_anonymous) {
    const int open_flags = O_CREAT | O_RDWR;
    fd = ::shm_open(shm_path.c_str(), open_flags, 0666);
    if (fd < 0) {
      throw std::runtime_error("shm_open create failed for: " + shm_path + " - Error: " + std::strerror(errno));
    }

    // Truncate file to match mapped size (including guard pages if applicable)
    if (::ftruncate(fd, static_cast<off_t>(map_size)) < 0) {
      ::close(fd);
      ::shm_unlink(shm_path.c_str());
      throw std::runtime_error("ftruncate failed for SHM size - Error: " + std::string(std::strerror(errno)));
    }
  }

  int mmap_flags = MAP_SHARED;
  if (use_anonymous) {
    mmap_flags |= MAP_ANONYMOUS | MAP_PRIVATE;
  }

#if defined(MAP_HUGETLB)
  if (has_flag(flags, ShmFlags::HUGE_PAGES)) {
    mmap_flags |= MAP_HUGETLB;
#ifdef MAP_HUGE_1GB
    mmap_flags |= MAP_HUGE_1GB;
#endif
  }
#endif

  const int prot = is_read_only ? PROT_READ : (PROT_READ | PROT_WRITE);

  void *raw_base = ::mmap(nullptr, map_size, prot, mmap_flags, fd, 0);
  if (!use_anonymous) {
    ::close(fd);
  }

  if (raw_base == MAP_FAILED) {
    if (!use_anonymous) {
      ::shm_unlink(shm_path.c_str());
    }
    throw std::runtime_error("mmap failed - Error: " + std::string(std::strerror(errno)));
  }

  void *user_base = raw_base;
  if (use_guard) {
    user_base =
        static_cast<void *>(static_cast<std::uint8_t *>(raw_base) + page);
    // Upper guard page protection
    ::mprotect(raw_base, page, PROT_NONE);
    // Lower guard page protection
    ::mprotect(static_cast<std::uint8_t *>(raw_base) + page + aligned_user_size,
               page, PROT_NONE);
  }

  // Wrap inside RAII handle to ensure automatic cleanup if post-processing
  // fails
  ShmRegion region(user_base, size, raw_base, map_size,
                   use_anonymous ? "" : shm_path.c_str(), flags);

  apply_numa_policy(user_base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    if (::mlock(user_base, size) != 0) {
      throw std::runtime_error(
          "mlock failed (verify RLIMIT_MEMLOCK permissions) - Error: " + std::string(std::strerror(errno)));
    }
  }

  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(user_base, size, is_read_only);
  }

  return region;
}

ShmRegion shm_open(const char *name, std::size_t size, ShmFlags flags) {
  const std::string shm_path = sanitize_shm_name(name);
  const bool is_read_only = has_flag(flags, ShmFlags::READ_ONLY);

  const int fd =
      ::shm_open(shm_path.c_str(), is_read_only ? O_RDONLY : O_RDWR, 0666);
  if (fd < 0) {
    throw std::runtime_error("shm_open open failed for: " + shm_path + " - Error: " + std::strerror(errno));
  }

  const int prot = is_read_only ? PROT_READ : (PROT_READ | PROT_WRITE);

  const std::size_t page = get_page_size();
  const std::size_t aligned_user_size = (size + page - 1) & ~(page - 1);
  std::size_t map_size = size;

  const bool use_guard = has_flag(flags, ShmFlags::GUARD_PAGES) &&
                         !has_flag(flags, ShmFlags::HUGE_PAGES);
  if (use_guard) {
    map_size = aligned_user_size + 2 * page;
  }

  int mmap_flags = MAP_SHARED;

#if defined(MAP_HUGETLB)
  if (has_flag(flags, ShmFlags::HUGE_PAGES)) {
    mmap_flags |= MAP_HUGETLB;
#ifdef MAP_HUGE_1GB
    mmap_flags |= MAP_HUGE_1GB;
    constexpr std::size_t huge_page_size = 1ULL << 30;
#else
    constexpr std::size_t huge_page_size = 2 * 1024 * 1024;
#endif
    map_size = (map_size + huge_page_size - 1) & ~(huge_page_size - 1);
  }
#endif

  void *raw_base = ::mmap(nullptr, map_size, prot, mmap_flags, fd, 0);
  ::close(fd);

  if (raw_base == MAP_FAILED) {
    throw std::runtime_error("mmap failed - Error: " + std::string(std::strerror(errno)));
  }

  void *user_base = raw_base;
  if (use_guard) {
    user_base =
        static_cast<void *>(static_cast<std::uint8_t *>(raw_base) + page);
    ::mprotect(raw_base, page, PROT_NONE);
    ::mprotect(static_cast<std::uint8_t *>(raw_base) + page + aligned_user_size,
               page, PROT_NONE);
  }

  ShmRegion region(user_base, size, raw_base, map_size, shm_path.c_str(),
                   flags);

  apply_numa_policy(user_base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    if (::mlock(user_base, size) != 0) {
      throw std::runtime_error(
          "mlock failed (verify RLIMIT_MEMLOCK permissions) - Error: " + std::string(std::strerror(errno)));
    }
  }

  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(user_base, size, is_read_only);
  }

  return region;
}

void ShmRegion::close() noexcept {
  if (base_ != nullptr) {
    if (has_flag(flags_, ShmFlags::LOCK_IN_MEMORY)) {
      ::munlock(base_, size_);
    }

    void *raw = map_base_ ? map_base_ : base_;
    const std::size_t msize = map_size_ ? map_size_ : size_;
    ::munmap(raw, msize);

    if (has_flag(flags_, ShmFlags::UNLINK_ON_CLOSE) && name_[0] != '\0') {
      ::shm_unlink(name_);
    }

    base_ = nullptr;
    size_ = 0;
    map_base_ = nullptr;
    map_size_ = 0;
  }
}

#endif

} // namespace Sphere::Platform