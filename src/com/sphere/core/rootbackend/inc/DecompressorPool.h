// DecompressorPool.h

// Parallel decompression of ROOT baskets and RNTuple pages into shared memory.


#pragma once

#include "root_format.h"

#include <libdeflate.h>
#include <lz4.h>
#include <zstd.h>

#if defined(SPHERE_HAVE_LZMA)
#include <lzma.h>
#endif

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <functional>
#include <mutex>
#include <queue>
#include <span>
#include <stdexcept>
#include <stop_token>
#include <string>
#include <thread>
#include <vector>

namespace Sphere::IO {

/**
 * Outcome of a decompression request
 */
struct DecompressResult {
  bool ok{false};
  std::uint64_t bytes_written{0};
  std::size_t blocks{0};
  std::string error;
};

/**
 * Decompresses one ROOT record 
 */
[[nodiscard]] inline DecompressResult
decompress_record(const std::uint8_t *src, std::size_t src_size,
                  std::uint8_t *dst, std::size_t dst_capacity,
                  bool has_key_header) {
  namespace RF = RootFormat;
  DecompressResult result{};

  if (src == nullptr || dst == nullptr || src_size == 0) {
    result.error = "null buffer or empty source";
    return result;
  }

  std::size_t offset = 0;
  std::size_t limit = src_size;

  if (has_key_header) {
    const RF::KeyHeader key = RF::parse_key_header(src, src_size);
    if (!key.valid) {
      result.error = "malformed TKey header";
      return result;
    }
    if (!RF::record_fits(key, src_size)) {
      result.error = "record is truncated: fNbytes=" +
                     std::to_string(key.nbytes) + " but only " +
                     std::to_string(src_size) + " bytes are available";
      return result;
    }

    if (key.is_uncompressed()) {
      if (dst_capacity < key.objlen) {
        result.error = "destination too small for the raw payload";
        return result;
      }
      std::memcpy(dst, src + key.payload_offset(), key.objlen);
      result.ok = true;
      result.bytes_written = key.objlen;
      result.blocks = 1;
      return result;
    }

    offset = key.payload_offset();
    limit = key.nbytes;
  }

  std::uint64_t written = 0;
  std::size_t blocks = 0;

  while (offset + RF::kCompressionHeaderSize <= limit) {
    const std::size_t remaining = limit - offset;
    const RF::BlockHeader block = RF::parse_block_header(src + offset, remaining);

    if (!block.valid) {
      if (blocks == 0) {
        result.error = std::string("unrecognised or truncated block (tag '") +
                       static_cast<char>(src[offset]) +
                       static_cast<char>(src[offset + 1]) + "')";
        return result;
      }
      break; // trailing padding after the last block
    }

    if (written + block.uncompressed_size > dst_capacity) {
      result.error = "destination too small: need at least " +
                     std::to_string(written + block.uncompressed_size) +
                     " bytes, have " + std::to_string(dst_capacity);
      return result;
    }

    const std::uint8_t *in = src + offset + block.payload_offset;
    const std::uint32_t in_size = RF::codec_input_size(block);
    std::uint8_t *out = dst + written;

    switch (block.algo) {
    case RF::Algo::LZ4: {
      const int produced = LZ4_decompress_safe(
          reinterpret_cast<const char *>(in), reinterpret_cast<char *>(out),
          static_cast<int>(in_size),
          static_cast<int>(block.uncompressed_size));
      if (produced < 0) {
        result.error = "LZ4_decompress_safe failed on block " +
                       std::to_string(blocks);
        return result;
      }
      written += static_cast<std::uint64_t>(produced);
      break;
    }

    case RF::Algo::ZSTD: {
      const std::size_t produced =
          ZSTD_decompress(out, block.uncompressed_size, in, in_size);
      if (ZSTD_isError(produced)) {
        result.error = std::string("ZSTD_decompress failed on block ") +
                       std::to_string(blocks) + ": " +
                       ZSTD_getErrorName(produced);
        return result;
      }
      written += produced;
      break;
    }

    case RF::Algo::ZLIB:
    case RF::Algo::OldZLIB: {
      thread_local struct DeflateHandle {
        libdeflate_decompressor *ptr{nullptr};
        DeflateHandle() : ptr(libdeflate_alloc_decompressor()) {}
        ~DeflateHandle() {
          if (ptr != nullptr) {
            libdeflate_free_decompressor(ptr);
          }
        }
        DeflateHandle(const DeflateHandle &) = delete;
        DeflateHandle &operator=(const DeflateHandle &) = delete;
      } tls_deflate;

      if (tls_deflate.ptr == nullptr) {
        result.error = "libdeflate_alloc_decompressor returned null";
        return result;
      }

      std::size_t produced = 0;
      const libdeflate_result res = libdeflate_zlib_decompress(
          tls_deflate.ptr, in, in_size, out, block.uncompressed_size, &produced);
      if (res != LIBDEFLATE_SUCCESS) {
        result.error = "libdeflate_zlib_decompress failed on block " +
                       std::to_string(blocks) + " (code " +
                       std::to_string(static_cast<int>(res)) + ")";
        return result;
      }
      written += produced;
      break;
    }

    case RF::Algo::LZMA: {
#if defined(SPHERE_HAVE_LZMA)
      std::size_t in_pos = 0;
      std::size_t out_pos = 0;
      const lzma_ret res = lzma_stream_buffer_decode(
          nullptr, 0, nullptr, in, &in_pos, in_size, out, &out_pos,
          block.uncompressed_size);
      if (res != LZMA_OK) {
        result.error = "lzma_stream_buffer_decode failed on block " +
                       std::to_string(blocks);
        return result;
      }
      written += out_pos;
      break;
#else
      result.error = "this build has no LZMA support; rebuild with "
                     "SPHERE_HAVE_LZMA and link liblzma";
      return result;
#endif
    }

    case RF::Algo::Uncompressed:
    case RF::Algo::Unknown:
    default:
      result.error = std::string("unsupported algorithm: ") +
                     RF::algo_name(block.algo);
      return result;
    }

    offset += block.total_size();
    ++blocks;
  }

  if (blocks == 0) {
    result.error = "no decodable block found";
    return result;
  }

  result.ok = true;
  result.bytes_written = written;
  result.blocks = blocks;
  return result;
}

/**
 * Reports how many bytes a record expands to
 */
[[nodiscard]] inline std::uint64_t
record_uncompressed_size(const std::uint8_t *src, std::size_t src_size,
                         bool has_key_header) noexcept {
  namespace RF = RootFormat;
  if (src == nullptr || src_size == 0) {
    return 0;
  }

  std::size_t offset = 0;
  if (has_key_header) {
    const RF::KeyHeader key = RF::parse_key_header(src, src_size);
    if (!key.valid) {
      return 0;
    }
    if (key.is_uncompressed()) {
      return key.objlen;
    }
    offset = key.payload_offset();
  }

  if (offset >= src_size) {
    return 0;
  }
  return RF::total_uncompressed_size(src + offset, src_size - offset);
}

/**
 * Fixed-size worker pool for decompression
 */
class DecompressorPool {
public:
  explicit DecompressorPool(
      std::size_t thread_count = std::thread::hardware_concurrency())
      : target_thread_count_(thread_count > 0 ? thread_count : 1) {}

  ~DecompressorPool() { stop(); }

  DecompressorPool(const DecompressorPool &) = delete;
  DecompressorPool &operator=(const DecompressorPool &) = delete;

  /// Starts the workers. Idempotent.
  void start() {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    if (!workers_.empty()) {
      return;
    }
    stop_requested_ = false;
    workers_.reserve(target_thread_count_);
    for (std::size_t i = 0; i < target_thread_count_; ++i) {
      workers_.emplace_back(
          [this](std::stop_token token) { worker_loop(std::move(token)); });
    }
  }

  /// Drains the queue and joins the workers. Idempotent.
  void stop() {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (stop_requested_ && workers_.empty()) {
        return;
      }
      stop_requested_ = true;
    }
    
    cv_.notify_all();
    workers_.clear();
  }

  /// True once start() has run and workers are available.
  [[nodiscard]] bool is_running() const noexcept {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    return !workers_.empty() && !stop_requested_;
  }

  /// Number of tasks queued or in flight.
  [[nodiscard]] std::size_t pending_jobs() const noexcept {
    return pending_tasks_count_.load(std::memory_order_relaxed);
  }

  /// Total number of chunks that failed to decompress since construction.
  [[nodiscard]] std::uint64_t error_count() const noexcept {
    return error_count_.load(std::memory_order_relaxed);
  }

  /**
   * One unit of work: a compressed source region and where it should land.
   */
  struct Job {
    const std::uint8_t *src{nullptr};
    std::size_t src_size{0};
    std::uint8_t *dst{nullptr};
    std::size_t dst_capacity{0};
    bool has_key_header{false};
    std::uint32_t index{0}; // caller-supplied identity, echoed back on error
  };

  /**
   * Runs a batch of jobs across the pool and blocks until all are done
   */
  std::vector<DecompressResult> submit_and_wait(std::span<const Job> jobs) {
    if (jobs.empty()) {
      return {};
    }
    if (!is_running()) {
      throw std::logic_error(
          "DecompressorPool::submit_and_wait called before start()");
    }

    std::vector<DecompressResult> results(jobs.size());
    std::atomic<std::size_t> remaining{jobs.size()};
    std::mutex done_mutex;
    std::condition_variable done_cv;

    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      for (std::size_t i = 0; i < jobs.size(); ++i) {
        const Job job = jobs[i];
        DecompressResult *slot = &results[i];
        tasks_.emplace([this, job, slot, &remaining, &done_mutex, &done_cv]() {
          *slot = decompress_record(job.src, job.src_size, job.dst,
                                    job.dst_capacity, job.has_key_header);
          if (!slot->ok) {
            error_count_.fetch_add(1, std::memory_order_relaxed);
          }
          if (remaining.fetch_sub(1, std::memory_order_acq_rel) == 1) {
            std::lock_guard<std::mutex> done_lock(done_mutex);
            done_cv.notify_one();
          }
        });
        pending_tasks_count_.fetch_add(1, std::memory_order_relaxed);
      }
    }
    cv_.notify_all();

    std::unique_lock<std::mutex> done_lock(done_mutex);
    done_cv.wait(done_lock, [&remaining] {
      return remaining.load(std::memory_order_acquire) == 0;
    });

    return results;
  }

  /**
   * Queues a single job without waiting. `on_done` runs on a worker thread.
   */
  template <typename Callback>
  bool enqueue_job(const Job &job, Callback &&on_done) {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (stop_requested_ || workers_.empty()) {
        return false;
      }
      tasks_.emplace([this, job, cb = std::forward<Callback>(on_done)]() {
        const DecompressResult res = decompress_record(
            job.src, job.src_size, job.dst, job.dst_capacity,
            job.has_key_header);
        if (!res.ok) {
          error_count_.fetch_add(1, std::memory_order_relaxed);
        }
        cb(job, res);
      });
      pending_tasks_count_.fetch_add(1, std::memory_order_relaxed);
    }
    cv_.notify_one();
    return true;
  }

private:
  void worker_loop(std::stop_token token) {
    for (;;) {
      std::function<void()> task;
      {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        cv_.wait(lock, token,
                 [this] { return !tasks_.empty() || stop_requested_; });

        if (tasks_.empty()) {
          return; // stop requested and nothing left to run
        }
        task = std::move(tasks_.front());
        tasks_.pop();
      }
      task();
      pending_tasks_count_.fetch_sub(1, std::memory_order_relaxed);
    }
  }

  std::size_t target_thread_count_;
  std::vector<std::jthread> workers_;
  std::queue<std::function<void()>> tasks_;
  std::atomic<std::size_t> pending_tasks_count_{0};
  std::atomic<std::uint64_t> error_count_{0};
  mutable std::mutex queue_mutex_;
  std::condition_variable_any cv_;
  bool stop_requested_{false};
};

} // namespace Sphere::IO
