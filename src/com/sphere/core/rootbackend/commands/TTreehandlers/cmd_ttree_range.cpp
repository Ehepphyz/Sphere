// commands/TTreehandlers/cmd_ttree_range.cpp

#include "../cmd_ttree.h"
#include "ttree_common.h"

#include <TBranch.h>
#include <TLeaf.h>
#include <TTree.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>

namespace Sphere::cmd::ttree {

namespace {

/**
* RAII Guard: Temporarily isolates branch activation for optimized column
* extraction and automatically restores full branch status upon scope exit.
*/
struct BranchStatusGuard {
  TTree *tree;
  explicit BranchStatusGuard(TTree *t) : tree(t) {
    if (tree) {
      tree->SetBranchStatus("*", 0);
    }
  }
  ~BranchStatusGuard() {
    if (tree) {
      tree->SetBranchStatus("*", 1);
    }
  }
};

/// How much room the read cache may take while one column is pulled.
inline constexpr Long64_t READ_CACHE_BYTES = 32L * 1024 * 1024;

/**
 * Holds a read cache over one branch for the length of a column read.
 *
 * Without it every basket costs its own read and nothing is fetched ahead, so a
 * long branch turns into thousands of small reads. The cache is handed back on
 * the way out: it belongs to this request, not to the tree.
 */
struct ReadCacheGuard {
  TTree *tree;
  ReadCacheGuard(TTree *t, TBranch *branch) : tree(t) {
    if (tree == nullptr) {
      return;
    }
    tree->SetCacheSize(READ_CACHE_BYTES);
    if (branch != nullptr) {
      tree->AddBranchToCache(branch, true);
    }
    tree->StopCacheLearningPhase();
  }
  ~ReadCacheGuard() {
    if (tree != nullptr) {
      tree->SetCacheSize(0);
    }
  }
};

} // anonymous namespace

/**
* Maps ROOT TLeaf type identifiers to shared memory data types (ShmDType)
* and determines their underlying element byte sizes.
*/
LeafTypeInfo map_root_leaf_type(TLeaf *leaf) {
  if (!leaf) {
    return {ShmDType::Float32, sizeof(float)};
  }

  const std::string type_name = leaf->GetTypeName();

  if (type_name == "Float_t" || type_name == "float") {
    return {ShmDType::Float32, sizeof(float)};
  }
  if (type_name == "Double_t" || type_name == "double") {
    return {ShmDType::Float64, sizeof(double)};
  }
  if (type_name == "Int_t" || type_name == "int") {
    return {ShmDType::Int32, sizeof(std::int32_t)};
  }
  if (type_name == "UInt_t" || type_name == "unsigned int") {
    return {ShmDType::UInt32, sizeof(std::uint32_t)};
  }
  if (type_name == "Long64_t" || type_name == "long long") {
    return {ShmDType::Int64, sizeof(std::int64_t)};
  }
  if (type_name == "ULong64_t") {
    return {ShmDType::UInt64, sizeof(std::uint64_t)};
  }
  if (type_name == "Short_t" || type_name == "short") {
    return {ShmDType::Int16, sizeof(std::int16_t)};
  }
  if (type_name == "UShort_t") {
    return {ShmDType::UInt16, sizeof(std::uint16_t)};
  }
  if (type_name == "Char_t" || type_name == "signed char") {
    return {ShmDType::Int8, sizeof(std::int8_t)};
  }
  if (type_name == "UChar_t" || type_name == "unsigned char" ||
      type_name == "Bool_t" || type_name == "bool") {
    return {ShmDType::UInt8, sizeof(std::uint8_t)};
  }

  // A branch of objects has no element size a column can be built from, and
  // guessing one publishes whatever the buffer happened to hold.
  return {ShmDType::Float32, static_cast<std::size_t>(leaf->GetLenType()), false};
}

/**
* Handles incoming IPC requests to read a contiguous column of TTree branch
* data directly into shared memory.
*/
void handle_read_column(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  if (pkt.payload_size == 0) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_ARG);
    return;
  }

  const std::string branch_name = read_payload_text(shm, pkt);
  if (branch_name.empty()) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_ARG);
    return;
  }

  TBranch *br = tree->GetBranch(branch_name.c_str());
  if (!br) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }

  auto *leaves = br->GetListOfLeaves();
  if (!leaves || leaves->GetEntries() == 0) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }

  auto *leaf = static_cast<TLeaf *>(leaves->At(0));
  LeafTypeInfo type_info = map_root_leaf_type(leaf);
  if (!type_info.known) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }

  const std::int64_t total_tree_entries = tree->GetEntries();
  std::int64_t start_entry = 0;
  std::int64_t end_entry = total_tree_entries;

  if (pkt.flags > 0 &&
      static_cast<std::int64_t>(pkt.flags) < total_tree_entries) {
    start_entry = std::min<std::int64_t>(pkt.flags, total_tree_entries);
  }

  const std::int64_t count = end_entry - start_entry;
  if (count <= 0) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_GENERIC);
    return;
  }

  // A leaf can hold more than one value per entry: a fixed array says so once,
  // a variable one through the leaf that counts it. Copying only the first of
  // them used to drop the rest without a word.
  TLeaf *counter = leaf->GetLeafCount();
  const std::int64_t per_entry =
      counter != nullptr
          ? std::max<std::int64_t>(1, counter->GetMaximum())
          : std::max<std::int64_t>(1, leaf->GetLen());

  // count comes from the tree, elem_size from the leaf, but the product is what
  // reaches the allocator: reject it here rather than let it wrap.
  if (type_info.elem_size == 0 || per_entry <= 0 ||
      static_cast<std::uint64_t>(count) >
          0xFFFFFFFFULL / (type_info.elem_size * per_entry)) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  const std::size_t total_bytes = static_cast<std::size_t>(count) *
                                  static_cast<std::size_t>(per_entry) *
                                  type_info.elem_size;

  // The bulk ring takes the block when it can, the chunk heap otherwise.
  const BulkBlock block = shm_bulk_acquire(shm, total_bytes);
  if (!block) {
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  auto *dest_base = reinterpret_cast<std::uint8_t *>(block.data);
  bool read_failed = false;
  std::int64_t written = 0;

  {
    BranchStatusGuard guard(tree);
    tree->SetBranchStatus(branch_name.c_str(), 1);
    ReadCacheGuard cache(tree, br);

    const std::size_t room = total_bytes;
    for (std::int64_t i = 0; i < count; ++i) {
      const std::int64_t entry_idx = start_entry + i;
      // LoadTree is what lets the cache see where the read is going and fetch
      // the baskets ahead of it.
      if (tree->LoadTree(entry_idx) < 0) {
        read_failed = true;
        break;
      }
      // A failed read would otherwise leave the previous entry in the leaf
      // buffer and publish it as if it were new data.
      if (br->GetEntry(entry_idx) < 0) {
        read_failed = true;
        break;
      }

      void *src_ptr = leaf->GetValuePointer();
      if (src_ptr == nullptr) {
        continue;
      }
      // A variable length leaf says how many values this entry carries only
      // once the entry has been read.
      const std::int64_t here =
          counter != nullptr ? std::max<std::int64_t>(0, leaf->GetLen())
                             : per_entry;
      const std::size_t bytes =
          static_cast<std::size_t>(here) * type_info.elem_size;
      const std::size_t at =
          static_cast<std::size_t>(written) * type_info.elem_size;
      if (bytes == 0 || at + bytes > room) {
        continue;
      }
      std::memcpy(dest_base + at, src_ptr, bytes);
      written += here;
    }
  } // Both guards restore what they changed on the way out.

  if (read_failed) {
    // Nothing was published, so the room goes straight back.
    shm_bulk_abort(shm, block);
    send_response(shm, pkt, Proto::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_GENERIC);
    return;
  }

  shm_bulk_commit(shm, block);

  // Push the zero-copy descriptor to the event ring
  if (shm.evt_ring) {
    BridgeMessage msg{};
    msg.job_id = pkt.job_id;
    msg.req_id = pkt.req_id;

    // The shape reports what was actually written, which is the entry count
    // for a scalar branch and more than that for an array one.
    shm_bulk_describe(msg, shm,
                      block,
                      static_cast<std::size_t>(written) * type_info.elem_size,
                      type_info.dtype, static_cast<std::uint32_t>(written));

    shm.evt_ring->push(msg);
  }

}

} // namespace Sphere::cmd::ttree
