// AsyncFileLoader_io_uring.h

#pragma once

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

// Conditionally include liburing if detected by CMake (HAVE_IO_URING)
#if defined(HAVE_IO_URING) && defined(__linux__)
#include <liburing.h>
#include <sys/types.h> // Required for off_t
#include <sys/uio.h>   // Required for struct iovec
#define SPHERE_ENABLE_IO_URING 1
#else
#define SPHERE_ENABLE_IO_URING 0
#include <unistd.h> // Fallback pread() support on POSIX
#endif

namespace Sphere::Platform {

struct IoCompletion {
  std::uint64_t user_data{0};
  std::int32_t result_bytes{0}; // Bytes read if >= 0, or -errno if < 0
};

/**
 * High-performance asynchronous file loader using Linux io_uring (Zero-Copy /
 * Fixed Buffers) with automatic fallback to synchronous pread() when
 * HAVE_IO_URING is not defined.
 */
class AsyncFileLoaderIoUring {
public:
  explicit AsyncFileLoaderIoUring(
      [[maybe_unused]] unsigned int queue_depth = 256) {
#if SPHERE_ENABLE_IO_URING
    std::memset(&params_, 0, sizeof(params_));
    params_.flags |= IORING_SETUP_SQPOLL;
    params_.sq_thread_idle = 2000; // ms

    int ret = ::io_uring_queue_init_params(queue_depth, &ring_, &params_);
    if (ret < 0) {
      throw std::runtime_error("io_uring_queue_init_params failed: " +
                               std::string(std::strerror(-ret)));
    }
#else
    std::cerr << "[io_uring] Warning: io_uring is disabled or unsupported on "
                 "this build. "
              << "Falling back to POSIX pread().\n";
#endif
  }

  ~AsyncFileLoaderIoUring() {
#if SPHERE_ENABLE_IO_URING
    unregister_buffers();
    unregister_files();
    ::io_uring_queue_exit(&ring_);
#endif
  }

  AsyncFileLoaderIoUring(const AsyncFileLoaderIoUring &) = delete;
  AsyncFileLoaderIoUring &operator=(const AsyncFileLoaderIoUring &) = delete;

  AsyncFileLoaderIoUring(AsyncFileLoaderIoUring &&other) noexcept
      : registered_buffers_(std::move(other.registered_buffers_)),
        registered_files_(std::move(other.registered_files_)),
        buffers_registered_(other.buffers_registered_),
        files_registered_(other.files_registered_) {
#if SPHERE_ENABLE_IO_URING
    ring_ = other.ring_;
    params_ = other.params_;
    std::memset(&other.ring_, 0, sizeof(other.ring_));
#endif
    other.buffers_registered_ = false;
    other.files_registered_ = false;
  }

  AsyncFileLoaderIoUring &operator=(AsyncFileLoaderIoUring &&other) noexcept {
    if (this != &other) {
#if SPHERE_ENABLE_IO_URING
      unregister_buffers();
      unregister_files();
      ::io_uring_queue_exit(&ring_);

      ring_ = other.ring_;
      params_ = other.params_;
      std::memset(&other.ring_, 0, sizeof(other.ring_));
#endif
      registered_buffers_ = std::move(other.registered_buffers_);
      registered_files_ = std::move(other.registered_files_);
      buffers_registered_ = other.buffers_registered_;
      files_registered_ = other.files_registered_;

      other.buffers_registered_ = false;
      other.files_registered_ = false;
    }
    return *this;
  }

  // =========================================================================
  // BUFFER REGISTRATION (SHM PINNING) & FILE DESCRIPTORS
  // =========================================================================

  /**
   * Pins the Shared Memory region in the kernel
   * shm_base Base address of the memory region (aligned to page/128
   * bytes)
   * shm_size: Total size of the shared memory region.
   */
  bool register_shm_buffer([[maybe_unused]] void *shm_base,
                           [[maybe_unused]] std::size_t shm_size) {
    if (buffers_registered_) {
      unregister_buffers();
    }

#if SPHERE_ENABLE_IO_URING
    registered_buffers_.clear();
    iovec iov{};
    iov.iov_base = shm_base;
    iov.iov_len = shm_size;
    registered_buffers_.push_back(iov);

    int ret = ::io_uring_register_buffers(
        &ring_, registered_buffers_.data(),
        static_cast<unsigned int>(registered_buffers_.size()));
    if (ret < 0) {
      std::cerr << "[io_uring] Failed to register SHM buffer: "
                << std::strerror(-ret) << "\n";
      buffers_registered_ = false;
      return false;
    }
#endif

    buffers_registered_ = true;
    return true;
  }

  void unregister_buffers() {
    if (buffers_registered_) {
#if SPHERE_ENABLE_IO_URING
      ::io_uring_unregister_buffers(&ring_);
#endif
      buffers_registered_ = false;
      registered_buffers_.clear();
    }
  }

  /**
   * Pre-registers a list of file descriptors in the io_uring internal kernel
   * table
   * fds: Vector of file descriptors to register.
   */
  bool register_files(const std::vector<int> &fds) {
    if (files_registered_) {
      unregister_files();
    }

    registered_files_ = fds;
#if SPHERE_ENABLE_IO_URING
    int ret = ::io_uring_register_files(
        &ring_, registered_files_.data(),
        static_cast<unsigned int>(registered_files_.size()));
    if (ret < 0) {
      std::cerr << "[io_uring] Failed to register files: "
                << std::strerror(-ret) << "\n";
      files_registered_ = false;
      return false;
    }
#endif

    files_registered_ = true;
    return true;
  }

  void unregister_files() {
    if (files_registered_) {
#if SPHERE_ENABLE_IO_URING
      ::io_uring_unregister_files(&ring_);
#endif
      files_registered_ = false;
      registered_files_.clear();
    }
  }

  /**
   * Accelerated submission using registered buffers and registered files
   */
  bool submit_read_fixed(int file_idx, void *buffer_ptr, std::uint32_t bytes,
                         std::uint64_t offset, std::uint64_t user_data,
                         [[maybe_unused]] std::uint16_t buf_group_idx = 0) {

#if SPHERE_ENABLE_IO_URING
    io_uring_sqe *sqe = ::io_uring_get_sqe(&ring_);
    if (!sqe) {
      flush_sq();
      sqe = ::io_uring_get_sqe(&ring_);
      if (!sqe) {
        std::cerr << "[io_uring] Error: SQ Ring saturated.\n";
        return false;
      }
    }

    ::io_uring_prep_read_fixed(sqe, file_idx, buffer_ptr, bytes,
                               static_cast<off_t>(offset), buf_group_idx);

    sqe->flags |= IOSQE_FIXED_FILE;
    sqe->user_data = user_data;

    if (::io_uring_sq_ring_needs_wakeup(&ring_)) {
      ::io_uring_submit(&ring_);
    }
    return true;
#else
    // Fallback: Immediate synchronous read using POSIX pread()
    if (file_idx < 0 ||
        static_cast<std::size_t>(file_idx) >= registered_files_.size()) {
      std::cerr << "[io_uring Fallback] Invalid file index: " << file_idx
                << "\n";
      return false;
    }

    int fd = registered_files_[file_idx];
    ssize_t res = ::pread(fd, buffer_ptr, bytes, static_cast<off_t>(offset));

    IoCompletion comp{};
    comp.user_data = user_data;
    comp.result_bytes = (res >= 0) ? static_cast<std::int32_t>(res) : -errno;

    fallback_completions_.push_back(comp);
    return true;
#endif
  }

  void flush_sq() {
#if SPHERE_ENABLE_IO_URING
    ::io_uring_submit(&ring_);
#endif
  }

  /**
   * Retrieves I/O completions.
   */
  int poll_completions([[maybe_unused]] unsigned int min_complete,
                       std::vector<IoCompletion> &out_completions) {

    out_completions.clear();

#if SPHERE_ENABLE_IO_URING
    io_uring_cqe *cqe = nullptr;

    if (min_complete > 0) {
      int ret = ::io_uring_wait_cqe_nr(&ring_, &cqe, min_complete);
      if (ret < 0) {
        if (ret != -EAGAIN && ret != -EINTR) {
          std::cerr << "[io_uring] io_uring_wait_cqe failed: "
                    << std::strerror(-ret) << "\n";
        }
        return 0;
      }
    }

    unsigned int count = 0;
    unsigned head = 0;
    io_uring_for_each_cqe(&ring_, head, cqe) {
      IoCompletion comp{};
      comp.user_data = cqe->user_data;
      comp.result_bytes = cqe->res;

      out_completions.push_back(comp);
      ++count;
    }

    if (count > 0) {
      ::io_uring_cq_advance(&ring_, count);
    }

    return static_cast<int>(count);
#else
    // Fallback: Return pre-computed completions from sync reads
    out_completions = std::move(fallback_completions_);
    fallback_completions_.clear();
    return static_cast<int>(out_completions.size());
#endif
  }

private:
#if SPHERE_ENABLE_IO_URING
  io_uring ring_{};
  io_uring_params params_{};
  std::vector<iovec> registered_buffers_;
#else
  struct DummyIovec {
    void *iov_base;
    std::size_t iov_len;
  };
  std::vector<DummyIovec> registered_buffers_;
  std::vector<IoCompletion> fallback_completions_;
#endif

  std::vector<int> registered_files_;
  bool buffers_registered_{false};
  bool files_registered_{false};
};

} // namespace Sphere::Platform
