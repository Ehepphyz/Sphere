// g4b_shm.cpp

#include "g4b_shm.h"

#include <chrono>
#include <cstring>
#include <thread>

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace g4b {

static_assert(std::atomic<std::uint64_t>::is_always_lock_free, "the rings need lock-free 64-bit atomics");
static_assert(sizeof(std::atomic<std::uint64_t>) == 8, "an atomic counter must be a plain 64-bit word");

Region::~Region() { close(); }

#if defined(_WIN32)

bool Region::open(const std::string &path, std::size_t size, std::string &error) {
  close();
  HANDLE f = ::CreateFileA(path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                           OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (f == INVALID_HANDLE_VALUE) {
    error = "cannot open " + path + " (error " + std::to_string(::GetLastError()) + ")";
    return false;
  }
  LARGE_INTEGER current{};
  ::GetFileSizeEx(f, &current);
  if (static_cast<std::size_t>(current.QuadPart) < size) {
    LARGE_INTEGER want{};
    want.QuadPart = static_cast<LONGLONG>(size);
    if (!::SetFilePointerEx(f, want, nullptr, FILE_BEGIN) || !::SetEndOfFile(f)) {
      error = "cannot size " + path;
      ::CloseHandle(f);
      return false;
    }
  } else {
    size = static_cast<std::size_t>(current.QuadPart);
  }
  HANDLE m = ::CreateFileMappingA(f, nullptr, PAGE_READWRITE, static_cast<DWORD>(static_cast<std::uint64_t>(size) >> 32),
                                  static_cast<DWORD>(size & 0xFFFFFFFFu), nullptr);
  if (m == nullptr) {
    error = "cannot map " + path + " (error " + std::to_string(::GetLastError()) + ")";
    ::CloseHandle(f);
    return false;
  }
  void *view = ::MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, size);
  if (view == nullptr) {
    error = "cannot view " + path;
    ::CloseHandle(m);
    ::CloseHandle(f);
    return false;
  }
  file_ = f;
  mapping_ = m;
  base_ = static_cast<std::uint8_t *>(view);
  size_ = size;
  return true;
}

void Region::close() {
  if (base_ != nullptr) {
    ::FlushViewOfFile(base_, 0);
    ::UnmapViewOfFile(base_);
  }
  if (mapping_ != nullptr) {
    ::CloseHandle(static_cast<HANDLE>(mapping_));
  }
  if (file_ != nullptr) {
    ::CloseHandle(static_cast<HANDLE>(file_));
  }
  base_ = nullptr;
  mapping_ = nullptr;
  file_ = nullptr;
  size_ = 0;
}

#else

bool Region::open(const std::string &path, std::size_t size, std::string &error) {
  close();
  const int fd = ::open(path.c_str(), O_RDWR | O_CREAT, 0600);
  if (fd < 0) {
    error = "cannot open " + path + ": " + std::strerror(errno);
    return false;
  }
  struct stat st {};
  ::fstat(fd, &st);
  if (static_cast<std::size_t>(st.st_size) < size) {
    if (::ftruncate(fd, static_cast<off_t>(size)) != 0) {
      error = "cannot size " + path + ": " + std::strerror(errno);
      ::close(fd);
      return false;
    }
  } else {
    size = static_cast<std::size_t>(st.st_size);
  }
  void *p = ::mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (p == MAP_FAILED) {
    error = "cannot map " + path + ": " + std::strerror(errno);
    ::close(fd);
    return false;
  }
  fd_ = fd;
  base_ = static_cast<std::uint8_t *>(p);
  size_ = size;
  return true;
}

void Region::close() {
  if (base_ != nullptr) {
    ::msync(base_, size_, MS_ASYNC);
    ::munmap(base_, size_);
  }
  if (fd_ >= 0) {
    ::close(fd_);
  }
  base_ = nullptr;
  fd_ = -1;
  size_ = 0;
}

#endif

// ---------------------------------------------------------------------------
// Ring

void Ring::format() {
  new (at_) std::atomic<std::uint64_t>(0);
  new (at_ + 64) std::atomic<std::uint64_t>(0);
}

std::uint64_t Ring::used() const {
  return tail().load(std::memory_order_acquire) - head().load(std::memory_order_acquire);
}

void Ring::copyIn(std::uint64_t pos, const void *src, std::size_t n) {
  const std::uint64_t at = pos % capacity_;
  const std::size_t first = static_cast<std::size_t>(std::min<std::uint64_t>(n, capacity_ - at));
  std::memcpy(data() + at, src, first);
  if (first < n) {
    std::memcpy(data(), static_cast<const std::uint8_t *>(src) + first, n - first);
  }
}

void Ring::copyOut(std::uint64_t pos, void *dst, std::size_t n) const {
  const std::uint64_t at = pos % capacity_;
  const std::size_t first = static_cast<std::size_t>(std::min<std::uint64_t>(n, capacity_ - at));
  std::memcpy(dst, data() + at, first);
  if (first < n) {
    std::memcpy(static_cast<std::uint8_t *>(dst) + first, data(), n - first);
  }
}

bool Ring::push(std::uint32_t id, const void *payload, std::uint32_t length) {
  const std::uint64_t need = (8ull + length + 7ull) & ~7ull;
  if (need > capacity_) {
    return false;
  }
  const std::uint64_t t = tail().load(std::memory_order_relaxed);
  const std::uint64_t h = head().load(std::memory_order_acquire);
  if (capacity_ - (t - h) < need) {
    return false;
  }
  const std::uint32_t frame[2] = {length, id};
  copyIn(t, frame, 8);
  copyIn(t + 8, payload, length);
  tail().store(t + need, std::memory_order_release);
  return true;
}

bool Ring::pop(std::uint32_t &id, std::string &out) {
  const std::uint64_t h = head().load(std::memory_order_relaxed);
  const std::uint64_t t = tail().load(std::memory_order_acquire);
  if (t == h) {
    return false;
  }
  std::uint32_t frame[2];
  copyOut(h, frame, 8);
  const std::uint32_t length = frame[0];
  id = frame[1];
  out.resize(length);
  copyOut(h + 8, out.data(), length);
  head().store(h + ((8ull + length + 7ull) & ~7ull), std::memory_order_release);
  return true;
}

// ---------------------------------------------------------------------------
// Channel

bool Channel::open(const std::string &path, std::size_t size, std::string &error) {
  if (size < (8u << 20)) {
    size = 8u << 20;
  }
  if (!region_.open(path, size, error)) {
    return false;
  }
  std::uint8_t *b = region_.base();
  const std::size_t total = region_.size();
  // A quarter of the room for requests, the rest for replies (tracks are big)
  const std::uint64_t reqCap = ((total - kHeaderBytes) / 4 - kRingHeader) & ~63ull;
  const std::uint64_t repOff = kHeaderBytes + kRingHeader + reqCap;
  const std::uint64_t repCap = ((total - repOff) - kRingHeader) & ~63ull;

  // Every start lays the region out afresh: one engine per region, and a
  // client only attaches once the engine says it is ready.
  std::memset(b, 0, kHeaderBytes);
  std::memcpy(b + off::version, &kLayoutVersion, 4);
  const std::uint64_t sz = total, ro = kHeaderBytes;
  std::memcpy(b + off::size, &sz, 8);
  std::memcpy(b + off::reqOffset, &ro, 8);
  std::memcpy(b + off::reqCapacity, &reqCap, 8);
  std::memcpy(b + off::repOffset, &repOff, 8);
  std::memcpy(b + off::repCapacity, &repCap, 8);
  req_ = Ring(b + kHeaderBytes, reqCap);
  rep_ = Ring(b + repOff, repCap);
  req_.format();
  rep_.format();
  field<std::uint64_t>(off::engineBeat).store(0, std::memory_order_relaxed);
  field<std::uint32_t>(off::cancel).store(0, std::memory_order_relaxed);
  field<std::uint32_t>(off::state).store(static_cast<std::uint32_t>(EngineState::Starting), std::memory_order_relaxed);
#if defined(_WIN32)
  const std::uint32_t pid = static_cast<std::uint32_t>(::GetCurrentProcessId());
#else
  const std::uint32_t pid = static_cast<std::uint32_t>(::getpid());
#endif
  std::memcpy(b + off::pid, &pid, 4);
  // The magic last, released: a client that sees it sees a laid-out region
  std::atomic_thread_fence(std::memory_order_release);
  field<std::uint32_t>(off::magic).store(kMagic, std::memory_order_release);
  return true;
}

void Channel::beat() { field<std::uint64_t>(off::engineBeat).fetch_add(1, std::memory_order_release); }

void Channel::setState(EngineState s) {
  field<std::uint32_t>(off::state).store(static_cast<std::uint32_t>(s), std::memory_order_release);
}

bool Channel::cancelRequested() const { return field<std::uint32_t>(off::cancel).load(std::memory_order_acquire) != 0; }

void Channel::clearCancel() { field<std::uint32_t>(off::cancel).store(0, std::memory_order_release); }

void Channel::describe(const std::string &text) {
  const std::size_t n = std::min<std::size_t>(text.size(), 255);
  std::memcpy(region_.base() + off::description, text.data(), n);
  region_.base()[off::description + n] = 0;
}

bool Channel::send(std::uint32_t id, const std::string &text, int timeoutMs) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
  int spins = 0;
  while (!rep_.push(id, text.data(), static_cast<std::uint32_t>(text.size()))) {
    if ((8ull + text.size()) > rep_.capacity()) {
      return false; // can never fit
    }
    if (std::chrono::steady_clock::now() > deadline) {
      return false;
    }
    if (++spins < 200) {
      std::this_thread::yield();
    } else {
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    beat();
  }
  return true;
}

} // namespace g4b
