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

  return {ShmDType::Float32, static_cast<std::size_t>(leaf->GetLenType())};
}

/**
 * Handles incoming IPC requests to read a contiguous column of TTree branch
 * data directly into shared memory.
 */
void handle_read_column(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  if (pkt.payload_size == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_ARG);
    return;
  }

  // Calculate raw payload pointer using payload_offset
  const auto *base_ptr = reinterpret_cast<const char *>(&shm);
  const char *raw_ptr = base_ptr + pkt.payload_offset;
  if (!raw_ptr) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_ARG);
    return;
  }

  std::string branch_name(raw_ptr, pkt.payload_size);

  TBranch *br = tree->GetBranch(branch_name.c_str());
  if (!br) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }

  auto *leaves = br->GetListOfLeaves();
  if (!leaves || leaves->GetSize() == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }

  auto *leaf = static_cast<TLeaf *>(leaves->At(0));
  LeafTypeInfo type_info = map_root_leaf_type(leaf);

  const std::int64_t total_tree_entries = tree->GetEntries();
  std::int64_t start_entry = 0;
  std::int64_t end_entry = total_tree_entries;

  if (pkt.flags > 0 &&
      static_cast<std::int64_t>(pkt.flags) < total_tree_entries) {
    start_entry = std::min<std::int64_t>(pkt.flags, total_tree_entries);
  }

  const std::int64_t count = end_entry - start_entry;
  if (count <= 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_GENERIC);
    return;
  }

  const std::size_t total_bytes =
      static_cast<std::size_t>(count) * type_info.elem_size;

  // Allocate contiguous data block directly within the shared memory heap
  std::uint64_t payload_off = shm_heap_alloc_data(shm, total_bytes);
  if (payload_off == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  const auto *shm_bytes = reinterpret_cast<const std::uint8_t *>(&shm);
  auto *dest_base = const_cast<std::uint8_t *>(shm_bytes + payload_off);

  {
    BranchStatusGuard guard(tree);
    tree->SetBranchStatus(branch_name.c_str(), 1);

    // Fast zero-copy memory transfer loop using GetValuePointer()
    for (std::int64_t i = 0; i < count; ++i) {
      std::int64_t entry_idx = start_entry + i;
      br->GetEntry(entry_idx);

      void *src_ptr = leaf->GetValuePointer();
      std::uint8_t *dest_ptr = dest_base + (i * type_info.elem_size);

      if (src_ptr) {
        std::memcpy(dest_ptr, src_ptr, type_info.elem_size);
      }
    }
  } // Guard automatically restores SetBranchStatus("*", 1) upon scope
    // destruction

  shm_chunk_commit(shm, payload_off);

  // Push zero-copy shared memory descriptor to the event ring buffer
  if (shm.evt_ring) {
    BridgeMessage msg{};
    msg.type = MsgType::SHM_REF;
    msg.job_id = pkt.job_id;
    msg.req_id = pkt.req_id;

    msg.shm_ref.offset = static_cast<std::uint32_t>(payload_off);
    msg.shm_ref.total_bytes = static_cast<std::uint32_t>(total_bytes);
    msg.shm_ref.dtype = type_info.dtype;
    msg.shm_ref.ndim = 1;
    msg.shm_ref.shape[0] = static_cast<std::uint32_t>(count);

    shm.evt_ring->push(msg);
  }

  std::cout << "[CmdTTree] Zero-copy column '" << branch_name
            << "' extracted: " << count << " elements (" << total_bytes
            << " bytes) at SHM offset " << payload_off << ".\n";
}

} // namespace Sphere::cmd::ttree
