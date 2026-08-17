// DecompressorPool.h
#pragma once

#include "RootBatchLoader.h"

#include <libdeflate.h>
#include <lz4.h>
#include <zstd.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <functional>
#include <future>
#include <iostream>
#include <mutex>
#include <queue>
#include <span>
#include <stdexcept>
#include <thread>
#include <vector>

namespace Sphere::IO {

/**
 * Parsed 9-byte ROOT compression header metadata.
 */
struct RootHeaderInfo {
  enum class Algo { Unknown, Uncompressed, LZ4, ZSTD, ZLIB };

  Algo algo{Algo::Unknown};
  std::uint32_t compressed_size{0};
  std::uint32_t uncompressed_size{0};
  std::size_t header_offset{9}; // Standard ROOT basket/page header size
};

/**
 * Parses the 9-byte ROOT header prefix inside a raw fetched buffer.
 */
inline RootHeaderInfo parse_root_header(const std::uint8_t *src) {
  RootHeaderInfo info{};

  // Check magic bytes
  if (src[0] == 'L' && src[1] == '4') {
    info.algo = RootHeaderInfo::Algo::LZ4;
  } else if (src[0] == 'Z' && src[1] == 'S') {
    info.algo = RootHeaderInfo::Algo::ZSTD;
  } else if (src[0] == 'Z' && src[1] == 'L') {
    info.algo = RootHeaderInfo::Algo::ZLIB;
  } else {
    // If no header magic matches, assume uncompressed raw payload
    info.algo = RootHeaderInfo::Algo::Uncompressed;
    info.header_offset = 0;
    return info;
  }

  // ROOT stores 24-bit integers for sizes in bytes 3..5 and 6..8
  info.uncompressed_size = static_cast<std::uint32_t>(src[3]) |
                           (static_cast<std::uint32_t>(src[4]) << 8) |
                           (static_cast<std::uint32_t>(src[5]) << 16);

  info.compressed_size = static_cast<std::uint32_t>(src[6]) |
                         (static_cast<std::uint32_t>(src[7]) << 8) |
                         (static_cast<std::uint32_t>(src[8]) << 16);

  return info;
}

/**
 * Thread pool specialized for zero-allocation SHM decompression.
 * Supports LZ4, ZSTD, and ZLIB/DEFLATE
 */
class DecompressorPool {
public:
  explicit DecompressorPool(
      std::size_t thread_count = std::thread::hardware_concurrency())
      : target_thread_count_(thread_count) {}

  ~DecompressorPool() { stop(); }

  /**
   * Initializes and starts worker threads.
   */
  void start() {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    if (!workers_.empty())
      return;

    stop_requested_ = false;
    workers_.reserve(target_thread_count_);
    for (std::size_t i = 0; i < target_thread_count_; ++i) {
      workers_.emplace_back(
          [this](std::stop_token stop_token) { worker_loop(stop_token); });
    }
  }

  /**
   * Safely stops worker threads and flushes remaining tasks.
   */
  void stop() {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (stop_requested_)
        return;
      stop_requested_ = true;
    }
    cv_.notify_all();
    workers_.clear(); // Triggers jthread join and stop token request
  }

  /**
   * Flushes all currently pending decompression tasks in the queue.
   */
  void flush() {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    std::queue<std::function<void()>> empty;
    std::swap(tasks_, empty);
    pending_tasks_count_.store(0, std::memory_order_relaxed);
  }

  /**
   * Returns the total count of pending decompression tasks.
   */
  std::size_t pending_jobs() const noexcept {
    return pending_tasks_count_.load(std::memory_order_relaxed);
  }

  /**
   * Enqueues a single generic callback decompression task.
   */
  template <typename Callback>
  void enqueue_job(void *shm_buffer, std::size_t compressed_bytes,
                   Callback &&cb) {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (stop_requested_)
        return;

      tasks_.emplace(
          [shm_buffer, compressed_bytes, cb = std::forward<Callback>(cb)]() {
            const auto *src = static_cast<const std::uint8_t *>(shm_buffer);
            const auto header = parse_root_header(src);

            std::size_t uncompressed_size =
                (header.algo == RootHeaderInfo::Algo::Uncompressed)
                    ? compressed_bytes
                    : header.uncompressed_size;

            cb(shm_buffer, uncompressed_size);
          });
      pending_tasks_count_.fetch_add(1, std::memory_order_relaxed);
    }
    cv_.notify_one();
  }

  /**
   * Decompresses a single chunk synchronously using the appropriate codec.
   * thread_local decompressor instances ensure lock-free thread safety.
   */
  static bool decompress_chunk(const RootChunkRequest &req,
                               std::uint8_t *dst_buffer,
                               std::size_t dst_capacity) {
    const auto *src = static_cast<const std::uint8_t *>(req.target_shm_buffer);
    const auto header = parse_root_header(src);

    if (header.algo == RootHeaderInfo::Algo::Uncompressed) {
      std::memcpy(dst_buffer, src, req.compressed_bytes);
      return true;
    }

    if (dst_capacity < header.uncompressed_size) {
      std::cerr << "[DecompressorPool] Error: Target SHM buffer too small ("
                << dst_capacity << " < " << header.uncompressed_size << ")\n";
      return false;
    }

    const std::uint8_t *payload = src + header.header_offset;
    const auto payload_size = static_cast<std::size_t>(header.compressed_size);

    switch (header.algo) {
    case RootHeaderInfo::Algo::LZ4: {
      int decompressed_bytes = LZ4_decompress_safe(
          reinterpret_cast<const char *>(payload),
          reinterpret_cast<char *>(dst_buffer), static_cast<int>(payload_size),
          static_cast<int>(header.uncompressed_size));

      if (decompressed_bytes < 0) {
        std::cerr
            << "[DecompressorPool] LZ4 decompression failed for chunk offset "
            << req.file_offset << "\n";
        return false;
      }
      return true;
    }

    case RootHeaderInfo::Algo::ZSTD: {
      std::size_t const ret = ZSTD_decompress(
          dst_buffer, header.uncompressed_size, payload, payload_size);

      if (ZSTD_isError(ret)) {
        std::cerr << "[DecompressorPool] ZSTD decompression failed: "
                  << ZSTD_getErrorName(ret) << "\n";
        return false;
      }
      return true;
    }

    case RootHeaderInfo::Algo::ZLIB: {
      // Thread-local libdeflate decompressor instance avoids allocation
      // overhead per task
      thread_local struct DeflateDecompressor {
        libdeflate_decompressor *decompressor{nullptr};
        DeflateDecompressor() {
          decompressor = libdeflate_alloc_decompressor();
        }
        ~DeflateDecompressor() {
          if (decompressor)
            libdeflate_free_decompressor(decompressor);
        }
      } tls_deflate;

      if (!tls_deflate.decompressor) {
        std::cerr << "[DecompressorPool] Failed to allocate libdeflate "
                     "decompressor.\n";
        return false;
      }

      std::size_t actual_out_bytes = 0;

      // ROOT ZLIB buffers contain a zlib header (RFC 1950 format)
      libdeflate_result res = libdeflate_zlib_decompress(
          tls_deflate.decompressor, payload, payload_size, dst_buffer,
          header.uncompressed_size, &actual_out_bytes);

      if (res != LIBDEFLATE_SUCCESS) {
        std::cerr
            << "[DecompressorPool] libdeflate decompression failed (code: "
            << static_cast<int>(res) << ") for chunk offset " << req.file_offset
            << "\n";
        return false;
      }

      return true;
    }

    default:
      std::cerr << "[DecompressorPool] Unsupported compression format.\n";
      return false;
    }
  }

  /**
   * Enqueues a batch of fetched requests for parallel decompression.
   */
  void dispatch_batch(std::span<const RootChunkRequest> requests,
                      Platform::ShmRegion &decompressed_shm,
                      std::vector<std::uint8_t *> &out_decompressed_ptrs) {

    out_decompressed_ptrs.resize(requests.size());

    auto *current_shm_dst = decompressed_shm.as<std::uint8_t>();
    std::size_t remaining_dst_size = decompressed_shm.size();

    std::vector<std::future<bool>> futures;
    futures.reserve(requests.size());

    for (std::size_t i = 0; i < requests.size(); ++i) {
      const auto &req = requests[i];
      const auto *src =
          static_cast<const std::uint8_t *>(req.target_shm_buffer);
      const auto header = parse_root_header(src);

      std::size_t uncompressed_size =
          (header.algo == RootHeaderInfo::Algo::Uncompressed)
              ? req.compressed_bytes
              : header.uncompressed_size;

      // 128-byte AVX-512 alignment in destination SHM
      std::size_t aligned_size =
          (uncompressed_size + 127) & ~static_cast<std::size_t>(127);

      if (remaining_dst_size < aligned_size) {
        throw std::runtime_error(
            "Decompressed SHM region exhausted during batch dispatch.");
      }

      std::uint8_t *dst_ptr = current_shm_dst;
      out_decompressed_ptrs[req.logical_index] = dst_ptr;

      // Advance output SHM allocation pointer
      current_shm_dst += aligned_size;
      remaining_dst_size -= aligned_size;

      // Package decompression task
      auto task = std::make_shared<std::packaged_task<bool()>>(
          [&req, dst_ptr, uncompressed_size]() {
            return decompress_chunk(req, dst_ptr, uncompressed_size);
          });

      futures.push_back(task->get_future());

      {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        tasks_.emplace([task]() { (*task)(); });
        pending_tasks_count_.fetch_add(1, std::memory_order_relaxed);
      }
      cv_.notify_one();
    }

    // Wait for all workers to finish decompressing the batch
    for (auto &fut : futures) {
      if (!fut.get()) {
        throw std::runtime_error("One or more chunks failed to decompress.");
      }
    }
  }

private:
  void worker_loop(std::stop_token stop_token) {
    while (!stop_token.stop_requested()) {
      std::function<void()> task;
      {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        cv_.wait(lock, [this, &stop_token]() {
          return !tasks_.empty() || stop_requested_ ||
                 stop_token.stop_requested();
        });

        if ((stop_requested_ || stop_token.stop_requested()) &&
            tasks_.empty()) {
          return;
        }

        task = std::move(tasks_.front());
        tasks_.pop();
        pending_tasks_count_.fetch_sub(1, std::memory_order_relaxed);
      }
      task();
    }
  }

  std::size_t target_thread_count_;
  std::vector<std::jthread> workers_;
  std::queue<std::function<void()>> tasks_;
  std::atomic<std::size_t> pending_tasks_count_{0};
  std::mutex queue_mutex_;
  std::condition_variable cv_;
  bool stop_requested_{false};
};

} // namespace Sphere::IO
