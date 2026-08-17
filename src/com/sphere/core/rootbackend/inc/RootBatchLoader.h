// RootBatchLoader.h
#pragma once

#include "platform.h"

// ROOT TTree Headers
#include <TBasket.h>
#include <TBranch.h>
#include <TFile.h>
#include <TTree.h>

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

// ROOT v7 RNTuple Headers
// Note: RClusterDescriptor is natively declared inside RNTupleDescriptor.hxx
#include <ROOT/RNTupleDescriptor.hxx>
#include <ROOT/RPageStorage.hxx>

#include <algorithm>
#include <cstdint>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
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
 * Drives hardware-specific I/O coalescing thresholds.
 */
enum class StorageMediaType {
  NVMe,     // Low latency, high IOPS: smaller gap threshold (e.g., 64 KB)
  SATA_SSD, // Medium latency: moderate gap threshold (e.g., 256 KB)
  HDD       // High seek latency: aggressive gap merging (e.g., 2 MB)
};

/**
 * Hardware-specific parameters for I/O request coalescing.
 */
struct CoalesceTuningParams {
  std::uint64_t max_gap_bytes{
      64 * 1024}; // Maximum allowed gap between reads to merge
  std::uint32_t max_read_bytes{4 * 1024 *
                               1024}; // Maximum combined read buffer size

  static CoalesceTuningParams for_media(StorageMediaType media) {
    switch (media) {
    case StorageMediaType::NVMe:
      return {64 * 1024, 4 * 1024 * 1024};
    case StorageMediaType::SATA_SSD:
      return {256 * 1024, 8 * 1024 * 1024};
    case StorageMediaType::HDD:
      return {2 * 1024 * 1024, 16 * 1024 * 1024};
    }
    return {64 * 1024, 4 * 1024 * 1024};
  }
};

/**
 * Represents a physical byte range on disk for a TTree Basket or RNTuple
 * Page.
 */
struct RootChunkRequest {
  std::uint64_t
      file_offset; // Absolute byte position on disk (BasketSeek / PageOffset)
  std::uint32_t compressed_bytes; // Physical payload size on disk (BasketBytes
                                  // / PageSize)
  std::uint32_t
      uncompressed_bytes;      // Size after decompression (0 if uninspected)
  std::uint64_t entry_start;   // First event/entry index in this chunk
  std::uint64_t entry_count;   // Total number of events/entries in this chunk
  std::uint32_t cluster_id;    // RNTuple Cluster ID (0 for TTree)
  std::uint32_t column_id;     // RNTuple Column ID (0 for TTree)
  std::uint32_t logical_index; // Original sequence index before sorting
  void *target_shm_buffer; // Assigned target pointer inside Platform::ShmRegion
};

/**
 * Represents a merged contiguous I/O read operation containing multiple
 * sub-chunks.
 */
struct RootCoalescedRequest {
  std::uint64_t
      file_offset; // Starting disk byte offset for the combined operation
  std::uint32_t
      total_bytes; // Total byte range to read from disk (including gaps)
  void *
      target_shm_buffer; // Target memory destination inside Platform::ShmRegion
  std::vector<RootChunkRequest>
      sub_chunks; // Individual pages or baskets in this range
};

/**
 * High-performance asynchronous chunk and page loader for CERN ROOT
 * files. Features AVX-512 aligned allocations and dynamic I/O request
 * coalescing.
 */
class RootBatchLoader {
public:
  explicit RootBatchLoader(const char *filepath) {
#if defined(SPHERE_OS_WINDOWS)
    file_handle_ = ::_open(filepath, _O_RDONLY | _O_BINARY);
#else
    file_handle_ = ::open(filepath, O_RDONLY);
#endif
    if (file_handle_ < 0) {
      throw std::runtime_error("Failed to open ROOT file descriptor for "
                               "asynchronous batch reading.");
    }
  }

  ~RootBatchLoader() {
    if (file_handle_ >= 0) {
#if defined(SPHERE_OS_WINDOWS)
      ::_close(file_handle_);
#else
      ::close(file_handle_);
#endif
    }
  }

  RootBatchLoader(const RootBatchLoader &) = delete;
  RootBatchLoader &operator=(const RootBatchLoader &) = delete;

  RootBatchLoader(RootBatchLoader &&other) noexcept
      : file_handle_(other.file_handle_) {
    other.file_handle_ = -1;
  }

  RootBatchLoader &operator=(RootBatchLoader &&other) noexcept {
    if (this != &other) {
      if (file_handle_ >= 0) {
#if defined(SPHERE_OS_WINDOWS)
        ::_close(file_handle_);
#else
        ::close(file_handle_);
#endif
      }
      file_handle_ = other.file_handle_;
      other.file_handle_ = -1;
    }
    return *this;
  }

  /**
   * Inspects a classic TTree branch and extracts disk byte offsets for all
   * baskets sorted by disk offset
   */
  std::vector<RootChunkRequest> inspect_branch_chunks(TTree *tree,
                                                      const char *branch_name) {
    std::vector<RootChunkRequest> requests;

    if (!tree) {
      std::cerr << "[RootBatchLoader] Error: Provided TTree pointer is null.\n";
      return requests;
    }

    TBranch *branch = tree->GetBranch(branch_name);
    if (!branch) {
      std::cerr << "[RootBatchLoader] Error: Branch not found: " << branch_name
                << "\n";
      return requests;
    }

    const Int_t last_write_basket = branch->GetWriteBasket();
    if (last_write_basket < 0) {
      return requests;
    }
    const Int_t num_baskets = last_write_basket + 1;

    // Use auto* to handle Int_t* vs Long64_t* variations across ROOT releases
    auto *basket_bytes = branch->GetBasketBytes();
    Long64_t *basket_entry = branch->GetBasketEntry();

    if (!basket_bytes || !basket_entry) {
      std::cerr
          << "[RootBatchLoader] Error: Basket metadata arrays are null.\n";
      return requests;
    }

    for (Int_t i = 0; i < num_baskets; ++i) {
      const auto size = static_cast<std::uint32_t>(basket_bytes[i]);

      if (i + 1 >= num_baskets) {
        break;
      }

      const Long64_t seek = branch->GetBasketSeek(i);
      const auto offset = static_cast<std::uint64_t>(seek);

      if (offset == 0 || size == 0) {
        continue;
      }

      RootChunkRequest req{};
      req.file_offset = offset;
      req.compressed_bytes = size;
      req.uncompressed_bytes = 0;
      req.entry_start = static_cast<std::uint64_t>(basket_entry[i]);
      req.entry_count =
          static_cast<std::uint64_t>(basket_entry[i + 1] - basket_entry[i]);
      req.cluster_id = 0;
      req.column_id = 0;
      req.logical_index = static_cast<std::uint32_t>(i);
      req.target_shm_buffer = nullptr;

      requests.push_back(req);
    }

    // Sort requests by disk offset to maximize physical read performance
    std::sort(requests.begin(), requests.end(),
              [](const RootChunkRequest &a, const RootChunkRequest &b) {
                return a.file_offset < b.file_offset;
              });

    return requests;
  }

  /**
   * Inspects an RNTuple descriptor and extracts page locators.
   * Compatible across ROOT < 6.34 and ROOT 6.34+ APIs
   */
  std::vector<RootChunkRequest>
  inspect_field_pages(const RNTupleNS::RNTupleDescriptor &descriptor,
                      std::string_view field_name) {

    std::vector<RootChunkRequest> requests;

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
    const auto field_id = descriptor.FindFieldId(std::string(field_name));
    if (field_id == RNTupleNS::kInvalidDescriptorId) {
#else
    const auto field_id = descriptor.FindFieldId(std::string(field_name));
    if (field_id == ROOT::kInvalidDescriptorId) {
#endif
      std::cerr << "[RootBatchLoader] Error: RNTuple field name not found: "
                << field_name << "\n";
      return requests;
    }

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
    std::vector<RNTupleNS::DescriptorId_t> column_ids;
#else
    std::vector<ROOT::DescriptorId_t> column_ids;
#endif

    for (const auto &col_desc : descriptor.GetColumnIterable(field_id)) {
      column_ids.push_back(col_desc.GetPhysicalId());
    }

    std::uint32_t logical_index = 0;

    for (const auto &cluster_desc : descriptor.GetClusterIterable()) {
      const auto cluster_id = cluster_desc.GetId();

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
      const std::uint64_t cluster_first_entry = cluster_desc.GetFirstEntryNum();
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

          RootChunkRequest req{};
          req.file_offset = offset;
          req.compressed_bytes = size;
          req.uncompressed_bytes = 0;
          req.entry_start = cluster_first_entry;
          req.entry_count = cluster_n_entries;
          req.cluster_id = static_cast<std::uint32_t>(cluster_id);
          req.column_id = static_cast<std::uint32_t>(col_id);
          req.logical_index = logical_index++;
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

          RootChunkRequest req{};
          req.file_offset = offset;
          req.compressed_bytes = size;
          req.uncompressed_bytes = 0;
          req.entry_start = cluster_first_entry;
          req.entry_count = cluster_n_entries;
          req.cluster_id = static_cast<std::uint32_t>(cluster_id);
          req.column_id = static_cast<std::uint32_t>(col_id);
          req.logical_index = logical_index++;
          req.target_shm_buffer = nullptr;

          requests.push_back(req);
        }
#endif
      }
    }

    // Sort requests by disk offset to maximize physical read performance
    std::sort(requests.begin(), requests.end(),
              [](const RootChunkRequest &a, const RootChunkRequest &b) {
                return a.file_offset < b.file_offset;
              });

    return requests;
  }

  /**
   * Merges adjacent requests with small disk gaps into contiguous I/O
   * blocks. Assigns 128-byte aligned target pointers inside the provided shared
   * memory region std::vector<RootCoalescedRequest> Coalesced list of batched
   * requests
   */
  std::vector<RootCoalescedRequest> coalesce_requests(
      std::vector<RootChunkRequest> &requests, Platform::ShmRegion &shm,
      CoalesceTuningParams params =
          CoalesceTuningParams::for_media(StorageMediaType::NVMe)) {

    std::vector<RootCoalescedRequest> coalesced_list;
    if (requests.empty()) {
      return coalesced_list;
    }

    auto *current_shm_ptr = shm.as<std::uint8_t>();
    std::size_t remaining_shm_size = shm.size();

    RootCoalescedRequest current_batch{};
    current_batch.file_offset = requests[0].file_offset;
    current_batch.total_bytes = 0;

    // Helper lambda to finalize and commit the active batch safely
    auto finalize_batch = [&](RootCoalescedRequest &batch) -> bool {
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
        std::cerr << "[RootBatchLoader] Warning: Shared memory region "
                     "exhausted during I/O coalescing.\n";
        return false;
      }

      batch.target_shm_buffer = current_shm_ptr;

      for (auto &sub : batch.sub_chunks) {
        const std::uint64_t relative_offset =
            sub.file_offset - batch.file_offset;
        sub.target_shm_buffer = current_shm_ptr + relative_offset;

        // Synchronize target buffer address back to caller's request vector
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
      const std::uint64_t req_end = req.file_offset + req.compressed_bytes;
      const std::uint64_t batch_start = current_batch.file_offset;
      const std::uint64_t batch_end = batch_start + current_batch.total_bytes;

      const bool is_first = current_batch.sub_chunks.empty();
      const std::uint64_t gap =
          is_first
              ? 0
              : (req.file_offset > batch_end ? req.file_offset - batch_end : 0);
      const std::uint64_t potential_new_size = (req_end > batch_start)
                                                   ? (req_end - batch_start)
                                                   : current_batch.total_bytes;

      // Finalize and commit the current batch if gaps or batch size exceed
      // thresholds
      if (!is_first && (gap > params.max_gap_bytes ||
                        potential_new_size > params.max_read_bytes)) {

        if (!finalize_batch(current_batch)) {
          break;
        }

        current_batch = RootCoalescedRequest{};
        current_batch.file_offset = req.file_offset;
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
   * Submits unmerged requests directly to AsyncFileLoader
   */
  void submit_batch(Platform::AsyncFileLoader &loader,
                    const std::vector<RootChunkRequest> &requests) {

    for (std::size_t i = 0; i < requests.size(); ++i) {
      const auto &req = requests[i];
      const std::uint64_t user_data = static_cast<std::uint64_t>(i);

      const bool submitted =
          loader.submit_read(file_handle_, req.target_shm_buffer,
                             req.compressed_bytes, req.file_offset, user_data);

      if (!submitted) {
        std::cerr << "[RootBatchLoader] Failed to submit asynchronous I/O "
                     "request for index "
                  << i << "\n";
      }
    }
  }

  /**
   * Submits coalesced merged requests to AsyncFileLoader
   */
  void submit_coalesced_batch(
      Platform::AsyncFileLoader &loader,
      const std::vector<RootCoalescedRequest> &coalesced_requests) {

    for (std::size_t i = 0; i < coalesced_requests.size(); ++i) {
      const auto &batch = coalesced_requests[i];
      const std::uint64_t user_data = static_cast<std::uint64_t>(i);

      const bool submitted =
          loader.submit_read(file_handle_, batch.target_shm_buffer,
                             batch.total_bytes, batch.file_offset, user_data);

      if (!submitted) {
        std::cerr << "[RootBatchLoader] Failed to submit coalesced I/O request "
                     "for index "
                  << i << "\n";
      }
    }
  }

  [[nodiscard]] int native_handle() const noexcept { return file_handle_; }

private:
  int file_handle_{-1};
};

} // namespace Sphere::IO
