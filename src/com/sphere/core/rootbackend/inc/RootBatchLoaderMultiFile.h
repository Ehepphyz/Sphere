// RootBatchLoaderMultiFile.h
// Multi-file asynchronous batch loader for CERN ROOT dataset partitions.

#pragma once

#include "RootBatchLoader.h"
#include "platform.h"

// ROOT Version Macros for Multi-Version API Compatibility
#include <RVersion.h>

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
#include <ROOT/RColumnModel.hxx>
namespace RNTupleNS = ROOT::Experimental;
#else
#include <ROOT/RField.hxx>
#include <ROOT/RNTupleModel.hxx>
namespace RNTupleNS = ROOT;
#endif

#include <ROOT/RNTupleDescriptor.hxx>
#include <ROOT/RPageStorage.hxx>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#if defined(SPHERE_OS_WINDOWS)
#include <fcntl.h>
#include <io.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

namespace Sphere::IO {

/**
 * Multi-file request pointing to a specific file partition index.
 */
struct RootChunkRequestMulti {
  std::uint64_t file_offset{0};        // Absolute byte position in target file
  std::uint32_t compressed_bytes{0};   // Compressed payload size on disk
  std::uint32_t uncompressed_bytes{0}; // Uncompressed payload size
  std::uint64_t entry_start{0};        // Starting entry index
  std::uint64_t entry_count{0};        // Total entry count
  std::uint32_t cluster_id{0};         // RNTuple Cluster ID
  std::uint32_t column_id{0};          // RNTuple Column ID
  std::uint32_t logical_index{0};      // Global sequence index across batches
  std::uint32_t file_index{0};         // Target file index in files_ vector
  void *target_shm_buffer{nullptr}; // Target destination in Platform::ShmRegion
};

/**
 * Coalesced multi-file request grouping requests within the same file
 * partition.
 */
struct RootCoalescedRequestMulti {
  std::uint64_t file_offset{0};
  std::uint32_t total_bytes{0};
  std::uint32_t file_index{0}; // Target file index
  void *target_shm_buffer{nullptr};
  std::vector<RootChunkRequestMulti> sub_chunks;
};

/**
 * Batch loader for multi-partition ROOT datasets (TChain / RNTuple
 * partitions).
 */
class RootBatchLoaderMultiFile {
public:
  explicit RootBatchLoaderMultiFile(const std::vector<std::string> &filepaths) {
    files_.reserve(filepaths.size());
    for (const auto &path : filepaths) {
      int fd = -1;
#if defined(SPHERE_OS_WINDOWS)
      fd = ::_open(path.c_str(), _O_RDONLY | _O_BINARY);
#else
      fd = ::open(path.c_str(), O_RDONLY);
#endif
      if (fd < 0) {
        throw std::runtime_error("Failed to open ROOT partition file: " + path);
      }
      files_.push_back(fd);
    }
  }

  ~RootBatchLoaderMultiFile() { close_handles(); }

  RootBatchLoaderMultiFile(const RootBatchLoaderMultiFile &) = delete;
  RootBatchLoaderMultiFile &
  operator=(const RootBatchLoaderMultiFile &) = delete;

  RootBatchLoaderMultiFile(RootBatchLoaderMultiFile &&other) noexcept
      : files_(std::move(other.files_)) {
    other.files_.clear();
  }

  RootBatchLoaderMultiFile &
  operator=(RootBatchLoaderMultiFile &&other) noexcept {
    if (this != &other) {
      close_handles();
      files_ = std::move(other.files_);
      other.files_.clear();
    }
    return *this;
  }

  /**
   * Inspects RNTuple descriptors across multiple dataset partitions.
   * Accepts descriptor pointers to bypass non-copyable type restrictions.
   * Extracts page locations without binding memory pointers prematurely.
   */
  std::vector<RootChunkRequestMulti> inspect_rntuple_partitions(
      const std::vector<const RNTupleNS::RNTupleDescriptor *> &descriptors,
      std::string_view field_name) const {

    std::vector<RootChunkRequestMulti> requests;
    std::uint32_t global_logical_index = 0;

    for (std::size_t file_idx = 0; file_idx < descriptors.size(); ++file_idx) {
      const auto *descriptor_ptr = descriptors[file_idx];
      if (!descriptor_ptr) {
        continue;
      }
      const auto &descriptor = *descriptor_ptr;

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
      const auto field_id = descriptor.FindFieldId(std::string(field_name));
      if (field_id == RNTupleNS::kInvalidDescriptorId) {
#else
      const auto field_id = descriptor.FindFieldId(std::string(field_name));
      if (field_id == ROOT::kInvalidDescriptorId) {
#endif
        std::cerr << "[RootBatchLoaderMultiFile] Warning: Field not found in "
                     "partition "
                  << file_idx << ": " << field_name << "\n";
        continue;
      }

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
      std::vector<RNTupleNS::DescriptorId_t> column_ids;
#else
      std::vector<ROOT::DescriptorId_t> column_ids;
#endif

      for (const auto &col_desc : descriptor.GetColumnIterable(field_id)) {
        column_ids.push_back(col_desc.GetPhysicalId());
      }

      for (const auto &cluster_desc : descriptor.GetClusterIterable()) {
        const auto cluster_id = cluster_desc.GetId();

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
        const std::uint64_t cluster_first_entry =
            cluster_desc.GetFirstEntryNum();
#else
        const std::uint64_t cluster_first_entry =
            cluster_desc.GetFirstEntryIndex();
#endif
        const std::uint64_t cluster_n_entries = cluster_desc.GetNEntries();

        for (const auto col_id : column_ids) {
#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
          const auto &page_locations =
              descriptor.GetPageLocations(cluster_id, col_id);

          for (const auto &page_loc : page_locations) {
            const std::uint64_t offset = page_loc.fLocator.fOffset;
            const std::uint32_t size = page_loc.fLocator.fSize;

            if (offset == 0 || size == 0) {
              continue;
            }

            RootChunkRequestMulti req{};
            req.file_offset = offset;
            req.compressed_bytes = size;
            req.uncompressed_bytes = 0;
            req.entry_start = cluster_first_entry;
            req.entry_count = cluster_n_entries;
            req.cluster_id = static_cast<std::uint32_t>(cluster_id);
            req.column_id = static_cast<std::uint32_t>(col_id);
            req.logical_index = global_logical_index++;
            req.file_index = static_cast<std::uint32_t>(file_idx);
            req.target_shm_buffer = nullptr;

            requests.push_back(req);
          }
#else
          // ROOT 6.34+: Inspect RPageRange via GetPageInfos() and
          // RNTupleLocator accessors
          const auto &page_range = cluster_desc.GetPageRange(col_id);

          for (const auto &page_info : page_range.GetPageInfos()) {
            const auto &locator = page_info.GetLocator();
            const std::uint64_t offset = locator.GetPosition<std::uint64_t>();
            const std::uint32_t size =
                static_cast<std::uint32_t>(locator.GetNBytesOnStorage());

            if (offset == 0 || size == 0) {
              continue;
            }

            RootChunkRequestMulti req{};
            req.file_offset = offset;
            req.compressed_bytes = size;
            req.uncompressed_bytes = 0;
            req.entry_start = cluster_first_entry;
            req.entry_count = cluster_n_entries;
            req.cluster_id = static_cast<std::uint32_t>(cluster_id);
            req.column_id = static_cast<std::uint32_t>(col_id);
            req.logical_index = global_logical_index++;
            req.file_index = static_cast<std::uint32_t>(file_idx);
            req.target_shm_buffer = nullptr;

            requests.push_back(req);
          }
#endif
        }
      }
    }

    // Sort requests primarily by file index, secondarily by byte offset
    // to enforce strictly sequential disk access per partition
    std::sort(requests.begin(), requests.end(),
              [](const RootChunkRequestMulti &a,
                 const RootChunkRequestMulti &b) noexcept {
                if (a.file_index == b.file_index) {
                  return a.file_offset < b.file_offset;
                }
                return a.file_index < b.file_index;
              });

    return requests;
  }

  /**
   * Merges requests across partitions into contiguous I/O operations.
   * Enforces 128-byte AVX-512 vector alignment and guarantees NO cross-file
   * boundary coalescing
   */
  std::vector<RootCoalescedRequestMulti> coalesce_requests(
      std::vector<RootChunkRequestMulti> &requests, Platform::ShmRegion &shm,
      CoalesceTuningParams params =
          CoalesceTuningParams::for_media(StorageMediaType::NVMe)) const {

    std::vector<RootCoalescedRequestMulti> coalesced_list;
    if (requests.empty()) {
      return coalesced_list;
    }

    auto *current_shm_ptr = shm.as<std::uint8_t>();
    std::size_t remaining_shm_size = shm.size();

    RootCoalescedRequestMulti current_batch{};
    current_batch.file_offset = requests[0].file_offset;
    current_batch.file_index = requests[0].file_index;
    current_batch.total_bytes = 0;

    // Helper lambda to finalize and commit the active batch safely
    auto finalize_batch = [&](RootCoalescedRequestMulti &batch) -> bool {
      if (batch.sub_chunks.empty()) {
        return true;
      }

      // Align memory offset to 128-byte boundaries for AVX-512 optimizations
      constexpr std::size_t kAvx512Alignment = 128;
      const std::size_t aligned_size =
          (static_cast<std::size_t>(batch.total_bytes) +
           (kAvx512Alignment - 1)) &
          ~(kAvx512Alignment - 1);

      if (remaining_shm_size < aligned_size) {
        std::cerr << "[RootBatchLoaderMultiFile] Error: Shared memory region "
                     "exhausted during request coalescing.\n";
        return false;
      }

      batch.target_shm_buffer = current_shm_ptr;

      for (auto &sub : batch.sub_chunks) {
        const std::uint64_t relative_offset =
            sub.file_offset - batch.file_offset;
        sub.target_shm_buffer = current_shm_ptr + relative_offset;

        // Synchronize target buffer address back to the caller's request vector
        if (sub.logical_index < requests.size()) {
          requests[sub.logical_index].target_shm_buffer = sub.target_shm_buffer;
        }
      }

      coalesced_list.push_back(std::move(batch));

      current_shm_ptr += aligned_size;
      remaining_shm_size = (remaining_shm_size > aligned_size)
                               ? (remaining_shm_size - aligned_size)
                               : 0;
      return true;
    };

    for (auto &req : requests) {
      const bool is_first = current_batch.sub_chunks.empty();
      const bool file_changed = (req.file_index != current_batch.file_index);

      const std::uint64_t req_end = req.file_offset + req.compressed_bytes;
      const std::uint64_t batch_start = current_batch.file_offset;
      const std::uint64_t batch_end = batch_start + current_batch.total_bytes;

      const std::uint64_t gap =
          (is_first || file_changed)
              ? 0
              : (req.file_offset > batch_end ? req.file_offset - batch_end : 0);

      const std::uint64_t potential_new_size = (req_end > batch_start)
                                                   ? (req_end - batch_start)
                                                   : current_batch.total_bytes;

      // Finalize current batch if target file changes or gap/size thresholds
      // are exceeded
      if (!is_first && (file_changed || gap > params.max_gap_bytes ||
                        potential_new_size > params.max_read_bytes)) {

        if (!finalize_batch(current_batch)) {
          break;
        }

        current_batch = RootCoalescedRequestMulti{};
        current_batch.file_offset = req.file_offset;
        current_batch.file_index = req.file_index;
        current_batch.total_bytes = 0;
      }

      current_batch.sub_chunks.push_back(req);
      current_batch.total_bytes = static_cast<std::uint32_t>(
          (req.file_offset + req.compressed_bytes) - current_batch.file_offset);
    }

    // Commit final pending batch
    if (!current_batch.sub_chunks.empty()) {
      finalize_batch(current_batch);
    }

    return coalesced_list;
  }

  /**
   * Submits I/O requests using standard file descriptors.
   */
  template <typename AsyncLoader>
  void submit_batch(
      AsyncLoader &loader,
      const std::vector<RootCoalescedRequestMulti> &coalesced_requests) const {

    for (std::size_t i = 0; i < coalesced_requests.size(); ++i) {
      const auto &batch = coalesced_requests[i];

      if (batch.file_index >= files_.size()) {
        std::cerr << "[RootBatchLoaderMultiFile] Error: Invalid file index ("
                  << batch.file_index << ") in coalesced request.\n";
        continue;
      }

      int fd = files_[batch.file_index];
      const std::uint64_t user_data = static_cast<std::uint64_t>(i);

      const bool submitted =
          loader.submit_read(fd, batch.target_shm_buffer, batch.total_bytes,
                             batch.file_offset, user_data);

      if (!submitted) {
        std::cerr << "[RootBatchLoaderMultiFile] Failed to submit I/O request "
                     "for batch index "
                  << i << "\n";
      }
    }
  }

  /**
   * Submits I/O requests using registered fixed file descriptors (io_uring)
   */
  template <typename AsyncLoader>
  void submit_batch_fixed(
      AsyncLoader &loader,
      const std::vector<RootCoalescedRequestMulti> &coalesced_requests) const {

    for (std::size_t i = 0; i < coalesced_requests.size(); ++i) {
      const auto &batch = coalesced_requests[i];
      const std::uint64_t user_data = static_cast<std::uint64_t>(i);

      const bool submitted = loader.submit_read_fixed(
          static_cast<int>(batch.file_index), batch.target_shm_buffer,
          batch.total_bytes, batch.file_offset, user_data);

      if (!submitted) {
        std::cerr << "[RootBatchLoaderMultiFile] Failed to submit fixed I/O "
                     "request for batch index "
                  << i << "\n";
      }
    }
  }

  [[nodiscard]] const std::vector<int> &native_handles() const noexcept {
    return files_;
  }

private:
  void close_handles() noexcept {
    for (int fd : files_) {
      if (fd >= 0) {
#if defined(SPHERE_OS_WINDOWS)
        ::_close(fd);
#else
        ::close(fd);
#endif
      }
    }
    files_.clear();
  }

  std::vector<int> files_;
};

} // namespace Sphere::IO
