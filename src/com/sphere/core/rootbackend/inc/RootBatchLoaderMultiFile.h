// RootBatchLoaderMultiFile.h

// Compatibility shim.


#pragma once

#include "RootBatchLoader.h"

#include <string_view>
#include <vector>

namespace Sphere::IO {

/// Retained spelling for the multi-file request type.
using RootChunkRequestMulti = RootChunkRequest;

/// Retained spelling for the merged multi-file request type.
using RootCoalescedRequestMulti = RootCoalescedRequest;

/// Retained spelling for the loader itself.
using RootBatchLoaderMultiFile = RootBatchLoader;

/**
 * Walks several RNTuple descriptors and returns one request list covering all of them
 */
[[nodiscard]] inline std::vector<RootChunkRequest> inspect_rntuple_partitions(
    const std::vector<const RNTupleNS::RNTupleDescriptor *> &descriptors,
    std::string_view field_name) {
  std::vector<RootChunkRequest> all;

  for (std::size_t file_index = 0; file_index < descriptors.size();
       ++file_index) {
    const auto *descriptor = descriptors[file_index];
    if (descriptor == nullptr) {
      continue;
    }

    auto partition = inspect_field_pages(*descriptor, field_name,
                                         static_cast<std::uint32_t>(file_index));
    // Renumber so logical_index stays unique across the whole batch.
    for (auto &req : partition) {
      req.logical_index = static_cast<std::uint32_t>(all.size());
      all.push_back(req);
    }
  }

  sort_requests(all);
  return all;
}

} // namespace Sphere::IO
