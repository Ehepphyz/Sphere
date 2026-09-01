// RootBatchLoader.h

// Extracts on-disk byte ranges for TTree baskets and RNTuple pages

#pragma once

#include "AsyncFileLoader_io_uring.h"
#include "common_config.h"
#include "platform.h"

// ROOT TTree headers
#include <TBasket.h>
#include <TBranch.h>
#include <TFile.h>
#include <TTree.h>

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
#include <cstdint>
#include <numeric>
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
 * Storage class
 */
enum class StorageMediaType {
  NVMe,     // low latency, high IOPS: merge only small gaps
  SATA_SSD, // moderate latency
  HDD       // high seek cost: merge aggressively
};

struct CoalesceTuningParams {
  std::uint64_t max_gap_bytes{64 * 1024};
  std::uint32_t max_read_bytes{4 * 1024 * 1024};

  static CoalesceTuningParams for_media(StorageMediaType media) noexcept {
    switch (media) {
    case StorageMediaType::SATA_SSD:
      return {256 * 1024, 8 * 1024 * 1024};
    case StorageMediaType::HDD:
      return {2 * 1024 * 1024, 16 * 1024 * 1024};
    case StorageMediaType::NVMe:
    default:
      return {64 * 1024, 4 * 1024 * 1024};
    }
  }
};

/**
 * One physical byte range on disk.
 */
struct RootChunkRequest {
  std::uint64_t file_offset{0};         // absolute position in the file
  std::uint32_t compressed_bytes{0};    // on-disk size of this range
  std::uint32_t uncompressed_bytes{0};  // expanded size, 0 when not yet known
  std::uint64_t entry_start{0};
  std::uint64_t entry_count{0};
  std::uint32_t cluster_id{0};          // RNTuple cluster, 0 for TTree
  std::uint32_t column_id{0};           // RNTuple column, 0 for TTree
  std::uint32_t logical_index{0};       // stable identity for the caller
  std::uint32_t file_index{0};          // index into the loader's file list

  /**
   * True when file_offset addresses a TKey rather than the payload
   */
  bool has_key_header{false};

  void *target_shm_buffer{nullptr}; // assigned by coalesce_requests
};

/**
 * A merged read covering several chunks
 */
struct RootCoalescedRequest {
  std::uint64_t file_offset{0};
  std::uint32_t total_bytes{0};
  std::uint32_t file_index{0};
  void *target_shm_buffer{nullptr};
  std::vector<std::size_t> sub_indices;
};

/**
 * Result of a coalescing pass.
 */
struct CoalesceResult {
  std::vector<RootCoalescedRequest> batches;
  std::size_t chunks_placed{0};
  std::size_t chunks_dropped{0}; // did not fit in the shared-memory region
  bool shm_exhausted{false};
};

/**
 * Merges requests into contiguous reads and assigns destination pointers
 */
[[nodiscard]] inline CoalesceResult
coalesce_requests(std::vector<RootChunkRequest> &requests,
                  std::uint8_t *destination, std::size_t destination_bytes,
                  CoalesceTuningParams params =
                      CoalesceTuningParams::for_media(StorageMediaType::NVMe)) {
  CoalesceResult result{};
  if (requests.empty() || destination == nullptr || destination_bytes == 0) {
    return result;
  }

  auto *cursor = destination;
  std::size_t remaining = destination_bytes;

  RootCoalescedRequest batch{};
  batch.file_offset = requests[0].file_offset;
  batch.file_index = requests[0].file_index;

  auto finalize = [&]() -> bool {
    if (batch.sub_indices.empty()) {
      return true;
    }

    const std::size_t aligned =
        (static_cast<std::size_t>(batch.total_bytes) + (SIMD_ALIGNMENT - 1)) &
        ~(SIMD_ALIGNMENT - 1);

    if (remaining < aligned) {
      result.shm_exhausted = true;
      result.chunks_dropped += batch.sub_indices.size();
      batch.sub_indices.clear();
      return false;
    }

    batch.target_shm_buffer = cursor;
    for (const std::size_t index : batch.sub_indices) {
      const std::uint64_t relative =
          requests[index].file_offset - batch.file_offset;
      requests[index].target_shm_buffer = cursor + relative;
    }
    result.chunks_placed += batch.sub_indices.size();
    result.batches.push_back(std::move(batch));

    cursor += aligned;
    remaining -= aligned;
    return true;
  };

  for (std::size_t i = 0; i < requests.size(); ++i) {
    const RootChunkRequest &req = requests[i];
    const bool first = batch.sub_indices.empty();
    const bool file_changed = (req.file_index != batch.file_index);

    const std::uint64_t req_end = req.file_offset + req.compressed_bytes;
    const std::uint64_t batch_end = batch.file_offset + batch.total_bytes;
    const std::uint64_t gap = (first || file_changed || req.file_offset <= batch_end)
                                  ? 0
                                  : (req.file_offset - batch_end);
    const std::uint64_t candidate_size =
        (req_end > batch.file_offset) ? (req_end - batch.file_offset)
                                      : batch.total_bytes;

    if (!first && (file_changed || gap > params.max_gap_bytes ||
                   candidate_size > params.max_read_bytes)) {
      if (!finalize()) {
        result.chunks_dropped += requests.size() - i;
        return result;
      }
      batch = RootCoalescedRequest{};
      batch.file_offset = req.file_offset;
      batch.file_index = req.file_index;
    }

    batch.sub_indices.push_back(i);
    batch.total_bytes = static_cast<std::uint32_t>(
        std::max<std::uint64_t>(batch.total_bytes,
                                req_end - batch.file_offset));
  }

  (void)finalize();
  return result;
}

/**
 * Convenience overload placing chunks in a whole ShmRegion
 */
[[nodiscard]] inline CoalesceResult
coalesce_requests(std::vector<RootChunkRequest> &requests,
                  Platform::ShmRegion &shm,
                  CoalesceTuningParams params =
                      CoalesceTuningParams::for_media(StorageMediaType::NVMe)) {
  if (!shm.is_valid()) {
    return CoalesceResult{};
  }
  return coalesce_requests(requests, shm.as<std::uint8_t>(), shm.size(), params);
}

/**
 * Sorts requests for sequential disk access
 */
inline void sort_requests(std::vector<RootChunkRequest> &requests) noexcept {
  std::sort(requests.begin(), requests.end(),
            [](const RootChunkRequest &a, const RootChunkRequest &b) noexcept {
              if (a.file_index != b.file_index) {
                return a.file_index < b.file_index;
              }
              return a.file_offset < b.file_offset;
            });
}

/**
 * Extracts basket byte ranges for one TTree branch
 */
[[nodiscard]] inline std::vector<RootChunkRequest>
inspect_branch_chunks(TTree *tree, const char *branch_name,
                      std::uint32_t file_index = 0) {
  std::vector<RootChunkRequest> requests;

  if (tree == nullptr || branch_name == nullptr) {
    return requests;
  }
  TBranch *branch = tree->GetBranch(branch_name);
  if (branch == nullptr) {
    return requests;
  }

  const Int_t basket_count = branch->GetWriteBasket();
  if (basket_count <= 0) {
    return requests;
  }

  auto *basket_bytes = branch->GetBasketBytes();
  Long64_t *basket_entry = branch->GetBasketEntry();
  if (basket_bytes == nullptr || basket_entry == nullptr) {
    return requests;
  }

  requests.reserve(static_cast<std::size_t>(basket_count));

  for (Int_t i = 0; i < basket_count; ++i) {
    const auto size = static_cast<std::uint32_t>(basket_bytes[i]);
    const auto offset = static_cast<std::uint64_t>(branch->GetBasketSeek(i));
    if (offset == 0 || size == 0) {
      continue;
    }

    RootChunkRequest req{};
    req.file_offset = offset;
    req.compressed_bytes = size;
    req.entry_start = static_cast<std::uint64_t>(basket_entry[i]);
    req.entry_count =
        static_cast<std::uint64_t>(basket_entry[i + 1] - basket_entry[i]);
    req.logical_index = static_cast<std::uint32_t>(requests.size());
    req.file_index = file_index;
    // GetBasketSeek() addresses the TKey, so the decoder must skip it.
    req.has_key_header = true;

    requests.push_back(req);
  }

  sort_requests(requests);
  return requests;
}

/**
 * Extracts page locators for one RNTuple field
 */
[[nodiscard]] inline std::vector<RootChunkRequest>
inspect_field_pages(const RNTupleNS::RNTupleDescriptor &descriptor,
                    std::string_view field_name, std::uint32_t file_index = 0) {
  std::vector<RootChunkRequest> requests;

  const auto field_id = descriptor.FindFieldId(std::string(field_name));
#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
  if (field_id == RNTupleNS::kInvalidDescriptorId) {
    return requests;
  }
  std::vector<RNTupleNS::DescriptorId_t> column_ids;
#else
  if (field_id == ROOT::kInvalidDescriptorId) {
    return requests;
  }
  std::vector<ROOT::DescriptorId_t> column_ids;
#endif

  for (const auto &column : descriptor.GetColumnIterable(field_id)) {
    column_ids.push_back(column.GetPhysicalId());
  }

  for (const auto &cluster : descriptor.GetClusterIterable()) {
    const auto cluster_id = cluster.GetId();

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
    const std::uint64_t first_entry = cluster.GetFirstEntryNum();
#else
    const std::uint64_t first_entry = cluster.GetFirstEntryIndex();
#endif
    const std::uint64_t entry_count = cluster.GetNEntries();

    for (const auto column_id : column_ids) {
      auto emit = [&](std::uint64_t offset, std::uint32_t size) {
        if (offset == 0 || size == 0) {
          return;
        }
        RootChunkRequest req{};
        req.file_offset = offset;
        req.compressed_bytes = size;
        req.entry_start = first_entry;
        req.entry_count = entry_count;
        req.cluster_id = static_cast<std::uint32_t>(cluster_id);
        req.column_id = static_cast<std::uint32_t>(column_id);
        req.logical_index = static_cast<std::uint32_t>(requests.size());
        req.file_index = file_index;
        // Page locators point at the payload; there is no key to skip.
        req.has_key_header = false;
        requests.push_back(req);
      };

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
      for (const auto &page : descriptor.GetPageLocations(cluster_id, column_id)) {
        emit(page.fLocator.fOffset, page.fLocator.fSize);
      }
#else
      const auto &page_range = cluster.GetPageRange(column_id);
      for (const auto &page : page_range.GetPageInfos()) {
        const auto &locator = page.GetLocator();
        emit(locator.template GetPosition<std::uint64_t>(),
             static_cast<std::uint32_t>(locator.GetNBytesOnStorage()));
      }
#endif
    }
  }

  sort_requests(requests);
  return requests;
}

/**
 * Opens one or more ROOT files and submits merged reads for them
 */
class RootBatchLoader {
public:
  explicit RootBatchLoader(const std::vector<std::string> &filepaths) {
    files_.reserve(filepaths.size());
    for (const auto &path : filepaths) {
#if defined(SPHERE_OS_WINDOWS)
      const int fd = ::_open(path.c_str(), _O_RDONLY | _O_BINARY);
#else
      const int fd = ::open(path.c_str(), O_RDONLY);
#endif
      if (fd < 0) {
        close_handles(); // do not leak the files already opened
        throw std::runtime_error("Failed to open ROOT file: " + path);
      }
      files_.push_back(fd);
      paths_.push_back(path);
    }
  }

  explicit RootBatchLoader(const std::string &filepath)
      : RootBatchLoader(std::vector<std::string>{filepath}) {}

  ~RootBatchLoader() { close_handles(); }

  RootBatchLoader(const RootBatchLoader &) = delete;
  RootBatchLoader &operator=(const RootBatchLoader &) = delete;

  RootBatchLoader(RootBatchLoader &&other) noexcept
      : files_(std::move(other.files_)), paths_(std::move(other.paths_)) {
    other.files_.clear();
    other.paths_.clear();
  }

  RootBatchLoader &operator=(RootBatchLoader &&other) noexcept {
    if (this != &other) {
      close_handles();
      files_ = std::move(other.files_);
      paths_ = std::move(other.paths_);
      other.files_.clear();
      other.paths_.clear();
    }
    return *this;
  }

  /**
   * Submits merged reads using registered file indices
   */
  [[nodiscard]] std::size_t
  submit_batches(Platform::AsyncFileLoaderIoUring &loader,
                 const std::vector<RootCoalescedRequest> &batches) const {
    std::size_t submitted = 0;
    for (std::size_t i = 0; i < batches.size(); ++i) {
      const RootCoalescedRequest &batch = batches[i];
      if (batch.file_index >= files_.size() ||
          batch.target_shm_buffer == nullptr) {
        continue;
      }
      if (loader.submit_read_fixed(static_cast<int>(batch.file_index),
                                   batch.target_shm_buffer, batch.total_bytes,
                                   batch.file_offset,
                                   static_cast<std::uint64_t>(i))) {
        ++submitted;
      }
    }
    loader.flush_sq();
    return submitted;
  }

  [[nodiscard]] const std::vector<int> &native_handles() const noexcept {
    return files_;
  }

  [[nodiscard]] const std::vector<std::string> &paths() const noexcept {
    return paths_;
  }

  [[nodiscard]] std::size_t file_count() const noexcept { return files_.size(); }

private:
  void close_handles() noexcept {
    for (const int fd : files_) {
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
  std::vector<std::string> paths_;
};

} // namespace Sphere::IO
