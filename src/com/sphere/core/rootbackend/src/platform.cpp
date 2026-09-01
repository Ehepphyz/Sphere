// platform.cpp
//
// Shared-memory mapping for POSIX and Windows.

#include "platform.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <string_view>
#include <stdexcept>
#include <string>

#if defined(SPHERE_OS_WINDOWS)
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#if defined(SPHERE_OS_LINUX)
#include <linux/mempolicy.h>
#include <sched.h>
#include <sys/syscall.h>
#endif
#endif

namespace Sphere::Platform {

namespace {

// Ensures POSIX shared-memory names begin with a single leading slash.

bool is_filesystem_path(const char *name) noexcept {
  if (name == nullptr || name[0] == '\0') {
    return false;
  }
  const std::string_view view(name);
  const std::size_t first = view.find('/');
  if (first == std::string_view::npos) {
    return view.find('.') != std::string_view::npos;
  }
  return view.find('/', first + 1) != std::string_view::npos || first > 0;
}

std::string sanitize_shm_name(const char *name) {
  if (name == nullptr || name[0] == '\0') {
    return "/default_shm";
  }
  if (is_filesystem_path(name)) {
    return std::string(name); // used verbatim by open()
  }
  if (name[0] == '/') {
    return std::string(name);
  }
  return "/" + std::string(name);
}

int open_backing_object(const std::string &path, bool as_file, int flags,
                        mode_t mode) noexcept {
  if (!as_file) {
    return ::shm_open(path.c_str(), flags, mode);
  }

  if ((flags & O_CREAT) != 0) {
    std::error_code ec;
    const std::filesystem::path parent =
        std::filesystem::path(path).parent_path();
    if (!parent.empty()) {
      std::filesystem::create_directories(parent, ec);
    }
  }
  return ::open(path.c_str(), flags, mode);
}

std::string os_error(const char *what) {
#if defined(SPHERE_OS_WINDOWS)
  return std::string(what) + " failed, GetLastError=" +
         std::to_string(static_cast<unsigned long>(::GetLastError()));
#else
  return std::string(what) + " failed: " + std::strerror(errno);
#endif
}

/// Touches every page so that the first real access does not take a fault.
void prefault_region(void *base, std::size_t size, bool read_only) noexcept {
  auto *ptr = static_cast<volatile std::uint8_t *>(base);
  const std::size_t page = get_page_size();
  for (std::size_t off = 0; off < size; off += page) {
    if (read_only) {
      const std::uint8_t dummy = ptr[off];
      (void)dummy;
    } else {
      ptr[off] = ptr[off];
    }
  }
}

#if defined(SPHERE_OS_LINUX)
/// Applies a NUMA placement policy. Best effort: failures are not fatal.
void apply_numa_policy(void *base, std::size_t size, ShmFlags flags) noexcept {
  const bool has_numa = has_flag(flags, ShmFlags::NUMA_AWARE) ||
                        has_flag(flags, ShmFlags::NUMA_INTERLEAVE) ||
                        has_flag(flags, ShmFlags::NUMA_LOCAL_ONLY);
  if (!has_numa) {
    return;
  }

  unsigned cpu = 0;
  unsigned node = 0;
  if (::syscall(SYS_getcpu, &cpu, &node, nullptr) != 0) {
    return;
  }

  const unsigned long nodemask = (1UL << node);
  const unsigned long maxnode = sizeof(nodemask) * 8;

  int mode = MPOL_PREFERRED;
  if (has_flag(flags, ShmFlags::NUMA_INTERLEAVE)) {
    mode = MPOL_INTERLEAVE;
  } else if (has_flag(flags, ShmFlags::NUMA_LOCAL_ONLY)) {
    mode = MPOL_BIND;
  }

  (void)::syscall(SYS_mbind, base, size, mode, &nodemask, maxnode, 0);
}
#else
void apply_numa_policy(void *, std::size_t, ShmFlags) noexcept {}
#endif

} // namespace

std::size_t get_page_size() noexcept {
#if defined(SPHERE_OS_WINDOWS)
  static const std::size_t cached = [] {
    SYSTEM_INFO si;
    ::GetSystemInfo(&si);
    return static_cast<std::size_t>(si.dwPageSize);
  }();
#else
  static const std::size_t cached = [] {
    const long p = ::sysconf(_SC_PAGESIZE);
    return (p > 0) ? static_cast<std::size_t>(p) : std::size_t{4096};
  }();
#endif
  return cached;
}

#if defined(SPHERE_OS_WINDOWS)

// =============================================================================
// Windows
// =============================================================================

ShmRegion shm_create(const char *name, std::size_t size, ShmFlags flags) {
  DWORD protection = PAGE_READWRITE;
  if (has_flag(flags, ShmFlags::HUGE_PAGES)) {
    const SIZE_T large = ::GetLargePageMinimum();
    if (large != 0) {
      size = (size + large - 1) & ~(large - 1);
      protection |= SEC_LARGE_PAGES;
    }
  }

  HANDLE mapping = ::CreateFileMappingA(
      INVALID_HANDLE_VALUE, nullptr, protection,
      static_cast<DWORD>(static_cast<std::uint64_t>(size) >> 32),
      static_cast<DWORD>(size & 0xFFFFFFFFu), name);
  if (mapping == nullptr) {
    throw std::runtime_error(os_error("CreateFileMapping"));
  }

  const DWORD access =
      has_flag(flags, ShmFlags::READ_ONLY) ? FILE_MAP_READ : FILE_MAP_ALL_ACCESS;
  void *base = ::MapViewOfFile(mapping, access, 0, 0, size);
  ::CloseHandle(mapping);

  if (base == nullptr) {
    throw std::runtime_error(os_error("MapViewOfFile"));
  }

  // Wrap immediately so that a throw from the steps below still unmaps.
  ShmRegion region(base, size, name, flags);

  apply_numa_policy(base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    ::VirtualLock(base, size);
  }
  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(base, size, has_flag(flags, ShmFlags::READ_ONLY));
  }
  return region;
}

ShmRegion shm_open(const char *name, std::size_t size, ShmFlags flags) {
  const DWORD access =
      has_flag(flags, ShmFlags::READ_ONLY) ? FILE_MAP_READ : FILE_MAP_ALL_ACCESS;

  HANDLE mapping = ::OpenFileMappingA(access, FALSE, name);
  if (mapping == nullptr) {
    throw std::runtime_error(os_error("OpenFileMapping"));
  }

  void *base = ::MapViewOfFile(mapping, access, 0, 0, size);
  ::CloseHandle(mapping);
  if (base == nullptr) {
    throw std::runtime_error(os_error("MapViewOfFile"));
  }

  ShmRegion region(base, size, name, flags);
  apply_numa_policy(base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    ::VirtualLock(base, size);
  }
  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(base, size, has_flag(flags, ShmFlags::READ_ONLY));
  }
  return region;
}

void shm_remove(const char *) noexcept {
  // Windows section objects are reclaimed when the last handle closes.
}

void ShmRegion::close() noexcept {
  if (base_ == nullptr) {
    return;
  }
  if (has_flag(flags_, ShmFlags::LOCK_IN_MEMORY)) {
    ::VirtualUnlock(base_, size_);
  }
  ::UnmapViewOfFile(map_base_ != nullptr ? map_base_ : base_);

  base_ = nullptr;
  size_ = 0;
  map_base_ = nullptr;
  map_size_ = 0;
}

#else

// =============================================================================
// POSIX
// =============================================================================

ShmRegion shm_create(const char *name, std::size_t size, ShmFlags flags) {
  const std::string shm_path = sanitize_shm_name(name);
  const bool backing_is_file = is_filesystem_path(name);
  const bool use_anonymous = has_flag(flags, ShmFlags::ANONYMOUS);
  const bool is_read_only = has_flag(flags, ShmFlags::READ_ONLY);
  const bool use_huge = has_flag(flags, ShmFlags::HUGE_PAGES);
  const bool use_guard = has_flag(flags, ShmFlags::GUARD_PAGES) && !use_huge;

  const std::size_t page = get_page_size();
  const std::size_t aligned_user_size = (size + page - 1) & ~(page - 1);
  std::size_t map_size = aligned_user_size;

#if defined(MAP_HUGETLB)
  if (use_huge) {
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
    fd = open_backing_object(shm_path, backing_is_file, O_CREAT | O_RDWR, 0666);
    if (fd < 0) {
      throw std::runtime_error(os_error(
          ((backing_is_file ? "open(create) " : "shm_open(create) ") + shm_path)
              .c_str()));
    }
    if (::ftruncate(fd, static_cast<off_t>(map_size)) < 0) {
      const std::string msg = os_error("ftruncate");
      ::close(fd);
      if (backing_is_file) {
        (void)::unlink(shm_path.c_str());
      } else {
        (void)::shm_unlink(shm_path.c_str());
      }
      throw std::runtime_error(msg);
    }
  }

  int mmap_flags = MAP_SHARED;
  if (use_anonymous) {
    mmap_flags |= MAP_ANONYMOUS;
  }

#if defined(MAP_HUGETLB)
  if (use_huge) {
    mmap_flags |= MAP_HUGETLB;
#ifdef MAP_HUGE_1GB
    mmap_flags |= MAP_HUGE_1GB;
#endif
  }
#endif

  const int prot = is_read_only ? PROT_READ : (PROT_READ | PROT_WRITE);

  void *raw_base = ::mmap(nullptr, map_size, prot, mmap_flags, fd, 0);
  if (!use_anonymous) {
    ::close(fd); // the mapping keeps its own reference
  }

  if (raw_base == MAP_FAILED) {
    const std::string msg = os_error("mmap");
    if (!use_anonymous) {
      ::shm_unlink(shm_path.c_str());
    }
    throw std::runtime_error(msg);
  }

  void *user_base = raw_base;
  if (use_guard) {
    user_base = static_cast<void *>(static_cast<std::uint8_t *>(raw_base) + page);
    ::mprotect(raw_base, page, PROT_NONE); // lower guard
    ::mprotect(static_cast<std::uint8_t *>(raw_base) + page + aligned_user_size,
               page, PROT_NONE); // upper guard
  }

  ShmRegion region(user_base, size, raw_base, map_size,
                   use_anonymous ? "" : shm_path.c_str(), flags);

  apply_numa_policy(user_base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    if (::mlock(user_base, size) != 0) {
      throw std::runtime_error(
          os_error("mlock (check RLIMIT_MEMLOCK)"));
    }
  }
  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(user_base, size, is_read_only);
  }
  return region;
}

ShmRegion shm_open(const char *name, std::size_t size, ShmFlags flags) {
  const std::string shm_path = sanitize_shm_name(name);
  const bool backing_is_file = is_filesystem_path(name);
  const bool is_read_only = has_flag(flags, ShmFlags::READ_ONLY);
  const bool use_huge = has_flag(flags, ShmFlags::HUGE_PAGES);
  const bool use_guard = has_flag(flags, ShmFlags::GUARD_PAGES) && !use_huge;

  const int fd = open_backing_object(shm_path, backing_is_file,
                                     is_read_only ? O_RDONLY : O_RDWR, 0666);
  if (fd < 0) {
    throw std::runtime_error(os_error(
        ((backing_is_file ? "open " : "shm_open ") + shm_path).c_str()));
  }

  const std::size_t page = get_page_size();
  const std::size_t aligned_user_size = (size + page - 1) & ~(page - 1);
  std::size_t map_size = aligned_user_size;

#if defined(MAP_HUGETLB)
  if (use_huge) {
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

  struct stat st {};
  if (::fstat(fd, &st) != 0) {
    const std::string msg = os_error("fstat");
    ::close(fd);
    throw std::runtime_error(msg);
  }
  if (static_cast<std::size_t>(st.st_size) < map_size) {
    ::close(fd);
    throw std::runtime_error(
        "Shared memory object " + shm_path + " is " +
        std::to_string(static_cast<std::uint64_t>(st.st_size)) +
        " bytes but " + std::to_string(map_size) +
        " were requested. The creator and this process disagree on SHM_SIZE.");
  }

  const int prot = is_read_only ? PROT_READ : (PROT_READ | PROT_WRITE);
  void *raw_base = ::mmap(nullptr, map_size, prot, MAP_SHARED, fd, 0);
  ::close(fd);

  if (raw_base == MAP_FAILED) {
    throw std::runtime_error(os_error("mmap"));
  }

  void *user_base = raw_base;
  if (use_guard) {
    user_base = static_cast<void *>(static_cast<std::uint8_t *>(raw_base) + page);
    ::mprotect(raw_base, page, PROT_NONE);
    ::mprotect(static_cast<std::uint8_t *>(raw_base) + page + aligned_user_size,
               page, PROT_NONE);
  }

  ShmRegion region(user_base, size, raw_base, map_size, shm_path.c_str(), flags);

  apply_numa_policy(user_base, size, flags);

  if (has_flag(flags, ShmFlags::LOCK_IN_MEMORY)) {
    if (::mlock(user_base, size) != 0) {
      throw std::runtime_error(os_error("mlock (check RLIMIT_MEMLOCK)"));
    }
  }
  if (has_flag(flags, ShmFlags::PREFAULT_PAGES)) {
    prefault_region(user_base, size, is_read_only);
  }
  return region;
}

void shm_remove(const char *name) noexcept {
  if (name == nullptr || name[0] == '\0') {
    return;
  }
  const std::string shm_path = sanitize_shm_name(name);
  if (is_filesystem_path(name)) {
    (void)::unlink(shm_path.c_str());
  } else {
    (void)::shm_unlink(shm_path.c_str());
  }
}

void ShmRegion::close() noexcept {
  if (base_ == nullptr) {
    return;
  }
  if (has_flag(flags_, ShmFlags::LOCK_IN_MEMORY)) {
    ::munlock(base_, size_);
  }

  void *raw = (map_base_ != nullptr) ? map_base_ : base_;
  const std::size_t msize = (map_size_ != 0) ? map_size_ : size_;
  ::munmap(raw, msize);

  if (has_flag(flags_, ShmFlags::UNLINK_ON_CLOSE) && name_[0] != '\0') {
    ::shm_unlink(name_);
  }

  base_ = nullptr;
  size_ = 0;
  map_base_ = nullptr;
  map_size_ = 0;
}

#endif

} // namespace Sphere::Platform
