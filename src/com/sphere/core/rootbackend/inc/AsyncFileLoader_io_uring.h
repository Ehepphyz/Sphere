// AsyncFileLoader_io_uring.h

// The single asynchronous file loader for the project.


#pragma once

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

#if defined(HAVE_IO_URING) && defined(__linux__)
#include <liburing.h>
#include <sys/types.h>
#include <sys/uio.h>
#define SPHERE_ENABLE_IO_URING 1
#else
#define SPHERE_ENABLE_IO_URING 0
#endif

#if !defined(_WIN32)
#include <unistd.h>
#endif

namespace Sphere::Platform {


struct IoCompletion {
  std::uint64_t user_data{0};
  std::int32_t result_bytes{0};
};

/**
 * Asynchronous file loader
 */
class AsyncFileLoaderIoUring {
public:
  explicit AsyncFileLoaderIoUring(unsigned int queue_depth = 256)
      : queue_depth_(queue_depth) {
#if SPHERE_ENABLE_IO_URING
    std::memset(&params_, 0, sizeof(params_));
    params_.flags |= IORING_SETUP_SQPOLL;
    params_.sq_thread_idle = 200;

    int ret = ::io_uring_queue_init_params(queue_depth, &ring_, &params_);
    if (ret == 0) {
      using_sqpoll_ = true;
    } else {
      std::memset(&params_, 0, sizeof(params_));
      ret = ::io_uring_queue_init_params(queue_depth, &ring_, &params_);
      if (ret < 0) {
        ring_.ring_fd = -1;
        last_error_ = "io_uring_queue_init_params failed: " +
                      std::string(std::strerror(-ret));
        return;
      }
    }
    ring_valid_ = true;
#endif
  }

  ~AsyncFileLoaderIoUring() { destroy(); }

  AsyncFileLoaderIoUring(const AsyncFileLoaderIoUring &) = delete;
  AsyncFileLoaderIoUring &operator=(const AsyncFileLoaderIoUring &) = delete;

  AsyncFileLoaderIoUring(AsyncFileLoaderIoUring &&other) noexcept {
    steal_from(other);
  }

  AsyncFileLoaderIoUring &operator=(AsyncFileLoaderIoUring &&other) noexcept {
    if (this != &other) {
      destroy();
      steal_from(other);
    }
    return *this;
  }

  /// True when an io_uring ring is available; false means the pread() path.
  [[nodiscard]] bool using_io_uring() const noexcept {
#if SPHERE_ENABLE_IO_URING
    return ring_valid_;
#else
    return false;
#endif
  }

  /// True when the kernel submission-queue polling thread is active.
  [[nodiscard]] bool using_sqpoll() const noexcept { return using_sqpoll_; }

  /// Diagnostic message from the last failed operation; empty when none.
  [[nodiscard]] const std::string &last_error() const noexcept {
    return last_error_;
  }

  // ===========================================================================
  // Registration
  // ===========================================================================

  bool register_shm_buffer([[maybe_unused]] void *shm_base,
                           [[maybe_unused]] std::size_t shm_size) {
    if (buffers_registered_) {
      unregister_buffers();
    }
#if SPHERE_ENABLE_IO_URING
    if (!ring_valid_) {
      return false;
    }
    registered_buffers_.clear();
    iovec iov{};
    iov.iov_base = shm_base;
    iov.iov_len = shm_size;
    registered_buffers_.push_back(iov);

    const int ret = ::io_uring_register_buffers(
        &ring_, registered_buffers_.data(),
        static_cast<unsigned int>(registered_buffers_.size()));
    if (ret < 0) {
      last_error_ =
          "io_uring_register_buffers failed: " + std::string(std::strerror(-ret));
      registered_buffers_.clear();
      buffers_registered_ = false;
      return false;
    }
    registered_base_ = static_cast<std::uint8_t *>(shm_base);
    registered_size_ = shm_size;
#endif
    buffers_registered_ = true;
    return true;
  }

  void unregister_buffers() {
    if (!buffers_registered_) {
      return;
    }
#if SPHERE_ENABLE_IO_URING
    if (ring_valid_) {
      ::io_uring_unregister_buffers(&ring_);
    }
    registered_buffers_.clear();
    registered_base_ = nullptr;
    registered_size_ = 0;
#endif
    buffers_registered_ = false;
  }

  /**
   * Pre-registers file descriptors
   */
  bool register_files(const std::vector<int> &fds) {
    if (files_registered_) {
      unregister_files();
    }
    registered_files_ = fds;
#if SPHERE_ENABLE_IO_URING
    if (!ring_valid_) {
      files_registered_ = true;
      return true;
    }
    const int ret = ::io_uring_register_files(
        &ring_, registered_files_.data(),
        static_cast<unsigned int>(registered_files_.size()));
    if (ret < 0) {
      last_error_ =
          "io_uring_register_files failed: " + std::string(std::strerror(-ret));
      files_registered_ = false;
      return false;
    }
#endif
    files_registered_ = true;
    return true;
  }

  void unregister_files() {
    if (!files_registered_) {
      return;
    }
#if SPHERE_ENABLE_IO_URING
    if (ring_valid_) {
      ::io_uring_unregister_files(&ring_);
    }
#endif
    registered_files_.clear();
    files_registered_ = false;
  }

  // ===========================================================================
  // Submission
  // ===========================================================================

  bool submit_read_fixed(int file_idx, void *buffer_ptr, std::uint32_t bytes,
                         std::uint64_t offset, std::uint64_t user_data,
                         [[maybe_unused]] std::uint16_t buf_index = 0) {
    if (file_idx < 0 ||
        static_cast<std::size_t>(file_idx) >= registered_files_.size()) {
      last_error_ = "submit_read_fixed: file index " + std::to_string(file_idx) +
                    " is outside the registered set";
      return false;
    }

#if SPHERE_ENABLE_IO_URING
    if (ring_valid_) {
      if (registered_base_ != nullptr) {
        auto *dst = static_cast<std::uint8_t *>(buffer_ptr);
        if (dst < registered_base_ ||
            dst + bytes > registered_base_ + registered_size_) {
          last_error_ = "submit_read_fixed: destination outside the registered "
                        "buffer";
          return false;
        }
      }

      io_uring_sqe *sqe = ::io_uring_get_sqe(&ring_);
      if (sqe == nullptr) {
        flush_sq(); // make room, then retry once
        sqe = ::io_uring_get_sqe(&ring_);
        if (sqe == nullptr) {
          last_error_ = "submit_read_fixed: submission queue saturated";
          return false;
        }
      }

      ::io_uring_prep_read_fixed(sqe, file_idx, buffer_ptr, bytes,
                                 static_cast<off_t>(offset), buf_index);
      sqe->flags |= IOSQE_FIXED_FILE;
      sqe->user_data = user_data;
      ++pending_sqes_;
      return true;
    }
#endif
    return submit_read_sync(registered_files_[file_idx], buffer_ptr, bytes,
                            offset, user_data);
  }

  /**
   * Queues a read using a raw descriptor rather than a registered index.
   */
  bool submit_read(int fd, void *buffer_ptr, std::uint32_t bytes,
                   std::uint64_t offset, std::uint64_t user_data) {
#if SPHERE_ENABLE_IO_URING
    if (ring_valid_) {
      io_uring_sqe *sqe = ::io_uring_get_sqe(&ring_);
      if (sqe == nullptr) {
        flush_sq();
        sqe = ::io_uring_get_sqe(&ring_);
        if (sqe == nullptr) {
          last_error_ = "submit_read: submission queue saturated";
          return false;
        }
      }
      ::io_uring_prep_read(sqe, fd, buffer_ptr, bytes, offset);
      ::io_uring_sqe_set_data64(sqe, user_data);
      ++pending_sqes_;
      return true;
    }
#endif
    return submit_read_sync(fd, buffer_ptr, bytes, offset, user_data);
  }

  int flush_sq() {
#if SPHERE_ENABLE_IO_URING
    if (ring_valid_ && pending_sqes_ > 0) {
      const int submitted = ::io_uring_submit(&ring_);
      if (submitted < 0) {
        last_error_ =
            "io_uring_submit failed: " + std::string(std::strerror(-submitted));
        return submitted;
      }
      pending_sqes_ = 0;
      return submitted;
    }
#endif
    return 0;
  }

  // ===========================================================================
  // Completion
  // ===========================================================================

  int poll_completions([[maybe_unused]] unsigned int min_complete,
                       std::vector<IoCompletion> &out_completions) {
    out_completions.clear();
    flush_sq();

#if SPHERE_ENABLE_IO_URING
    if (ring_valid_) {
      io_uring_cqe *cqe = nullptr;

      if (min_complete > 0) {
        const int ret = ::io_uring_wait_cqe_nr(&ring_, &cqe, min_complete);
        if (ret < 0) {
          if (ret != -EAGAIN && ret != -EINTR) {
            last_error_ = "io_uring_wait_cqe_nr failed: " +
                          std::string(std::strerror(-ret));
          }
          return 0;
        }
      }

      unsigned int count = 0;
      unsigned head = 0;
      io_uring_for_each_cqe(&ring_, head, cqe) {
        out_completions.push_back(
            IoCompletion{cqe->user_data, cqe->res});
        ++count;
      }

      if (count > 0) {
        ::io_uring_cq_advance(&ring_, count);
      }
      return static_cast<int>(count);
    }
#endif

    // Fallback: hand back the synchronous results collected at submit time.
    const std::size_t take = fallback_completions_.size();
    out_completions.reserve(take);
    while (!fallback_completions_.empty()) {
      out_completions.push_back(fallback_completions_.front());
      fallback_completions_.pop_front();
    }
    return static_cast<int>(take);
  }

  /// Number of entries queued but not yet published to the kernel.
  [[nodiscard]] unsigned int pending_submissions() const noexcept {
    return pending_sqes_;
  }

  /// Configured submission-queue depth.
  [[nodiscard]] unsigned int queue_depth() const noexcept {
    return queue_depth_;
  }

private:
  /**
   * Synchronous read used whenever no io_uring ring is available
   */
  bool submit_read_sync(int fd, void *buffer_ptr, std::uint32_t bytes,
                        std::uint64_t offset, std::uint64_t user_data) {
#if !defined(_WIN32)
    auto *dst = static_cast<std::uint8_t *>(buffer_ptr);
    std::size_t done = 0;
    std::int32_t result = 0;

    while (done < bytes) {
      const ssize_t res = ::pread(fd, dst + done, bytes - done,
                                  static_cast<off_t>(offset + done));
      if (res < 0) {
        if (errno == EINTR) {
          continue;
        }
        result = -errno;
        break;
      }
      if (res == 0) {
        break; // end of file
      }
      done += static_cast<std::size_t>(res);
    }

    if (result == 0) {
      result = static_cast<std::int32_t>(done);
    }
    fallback_completions_.push_back(IoCompletion{user_data, result});
    return true;
#else

    (void)fd;
    (void)buffer_ptr;
    (void)bytes;
    (void)offset;
    (void)user_data;
    last_error_ = "no synchronous read path on this platform";
    return false;
#endif
  }

  void destroy() noexcept {
#if SPHERE_ENABLE_IO_URING
    if (ring_valid_) {
      if (buffers_registered_) {
        ::io_uring_unregister_buffers(&ring_);
        buffers_registered_ = false;
      }
      if (files_registered_) {
        ::io_uring_unregister_files(&ring_);
        files_registered_ = false;
      }
      ::io_uring_queue_exit(&ring_);
      ring_valid_ = false;
    }
#endif
  }

  void steal_from(AsyncFileLoaderIoUring &other) noexcept {
#if SPHERE_ENABLE_IO_URING
    ring_ = other.ring_;
    params_ = other.params_;
    registered_buffers_ = std::move(other.registered_buffers_);
    registered_base_ = other.registered_base_;
    registered_size_ = other.registered_size_;
    ring_valid_ = other.ring_valid_;
    other.ring_valid_ = false;
    other.ring_.ring_fd = -1;
    other.registered_base_ = nullptr;
    other.registered_size_ = 0;
#endif
    registered_files_ = std::move(other.registered_files_);
    fallback_completions_ = std::move(other.fallback_completions_);
    buffers_registered_ = other.buffers_registered_;
    files_registered_ = other.files_registered_;
    using_sqpoll_ = other.using_sqpoll_;
    pending_sqes_ = other.pending_sqes_;
    queue_depth_ = other.queue_depth_;
    last_error_ = std::move(other.last_error_);

    other.buffers_registered_ = false;
    other.files_registered_ = false;
    other.using_sqpoll_ = false;
    other.pending_sqes_ = 0;
  }

#if SPHERE_ENABLE_IO_URING
  io_uring ring_{};
  io_uring_params params_{};
  std::vector<iovec> registered_buffers_;
  std::uint8_t *registered_base_{nullptr};
  std::size_t registered_size_{0};
  bool ring_valid_{false};
#endif

  std::vector<int> registered_files_;
  std::deque<IoCompletion> fallback_completions_;
  std::string last_error_;
  unsigned int queue_depth_{256};
  unsigned int pending_sqes_{0};
  bool buffers_registered_{false};
  bool files_registered_{false};
  bool using_sqpoll_{false};
};

} // namespace Sphere::Platform
