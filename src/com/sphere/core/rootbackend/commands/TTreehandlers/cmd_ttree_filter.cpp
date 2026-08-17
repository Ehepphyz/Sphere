// commands/TTreehandlers/cmd_ttree_filter.cpp

#include "../cmd_ttree.h"
#include "ttree_common.h"

#include <TBranch.h>
#include <TLeaf.h>
#include <TTree.h>
#include <TTreeFormula.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <string>

namespace Sphere::cmd::ttree {

// ============================================================================
// Server-Side Summary Statistics Reduction (Welford's Algorithm)
// ============================================================================
void handle_compute_stats(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdTTree] Computing server-side column statistics...\n";

  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  // Extract target branch name safely from payload offset
  std::string target_branch_name;
  if (pkt.payload_size > 0) {
    const auto *base_ptr = reinterpret_cast<const char *>(&shm);
    const char *raw_ptr = base_ptr + pkt.payload_offset;
    if (raw_ptr) {
      target_branch_name.assign(raw_ptr, pkt.payload_size);
    }
  }

  TBranch *br = nullptr;
  if (!target_branch_name.empty()) {
    br = tree->GetBranch(target_branch_name.c_str());
  } else {
    auto *branches = tree->GetListOfBranches();
    if (branches && branches->GetSize() > 0) {
      br = static_cast<TBranch *>(branches->At(0));
    }
  }

  if (!br) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }

  const std::uint64_t total_entries = tree->GetEntries();
  if (total_entries == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_GENERIC);
    return;
  }

  auto *leaves = br->GetListOfLeaves();
  if (!leaves || leaves->GetSize() == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_BRANCH);
    return;
  }
  auto *leaf = static_cast<TLeaf *>(leaves->At(0));

  // Fast I/O Optimization: Deactivate unused branches
  tree->SetBranchStatus("*", 0);
  tree->SetBranchStatus(br->GetName(), 1);

  double min_val = std::numeric_limits<double>::infinity();
  double max_val = -std::numeric_limits<double>::infinity();

  // Welford's algorithm variables for numerically stable mean & variance
  std::uint64_t count = 0;
  double mean = 0.0;
  double M2 = 0.0;

  for (std::uint64_t i = 0; i < total_entries; ++i) {
    if (br->GetEntry(i) <= 0)
      continue;
    double val = leaf->GetValue(0);

    min_val = std::min(min_val, val);
    max_val = std::max(max_val, val);

    // Welford's online update step
    ++count;
    double delta = val - mean;
    mean += delta / static_cast<double>(count);
    double delta2 = val - mean;
    M2 += delta * delta2;
  }

  // Restore branch status for subsequent queries
  tree->SetBranchStatus("*", 1);

  double variance = (count > 0) ? (M2 / static_cast<double>(count)) : 0.0;
  double rms = std::sqrt(variance + (mean * mean));

  std::string json;
  json.reserve(256);
  json += "{\"branch\":\"" + escape_json(br->GetName()) + "\"";
  json += ",\"entries\":" + std::to_string(total_entries);
  json += ",\"min\":" + std::to_string(min_val);
  json += ",\"max\":" + std::to_string(max_val);
  json += ",\"mean\":" + std::to_string(mean);
  json += ",\"variance\":" + std::to_string(variance);
  json += ",\"rms\":" + std::to_string(rms);
  json += "}";

  std::size_t size = json.size();
  std::uint64_t payload_off = shm_heap_alloc_tx(shm, size);
  if (payload_off == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  const auto *shm_bytes = reinterpret_cast<const std::uint8_t *>(&shm);
  auto *dest_ptr = const_cast<std::uint8_t *>(shm_bytes + payload_off);
  std::memcpy(dest_ptr, json.data(), size);
  shm_chunk_commit(shm, payload_off);

  send_response(shm, pkt, Platform::PacketType::EVT_OK, 0,
                static_cast<std::uint32_t>(size), ResponseStatus::OK);
}

// ============================================================================
// High-Speed In-Engine Selection & SHM Bitmask Generation
// ============================================================================
void handle_apply_filter(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdTTree] Executing fast in-engine selection cut...\n";

  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  const std::uint64_t total_entries = tree->GetEntries();
  if (total_entries == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_GENERIC);
    return;
  }

  // Safe extraction of cut expression string without assuming null-termination
  if (pkt.payload_size == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_FORMULA);
    return;
  }

  const auto *base_ptr = reinterpret_cast<const char *>(&shm);
  const char *raw_ptr = base_ptr + pkt.payload_offset;
  if (!raw_ptr) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_FORMULA);
    return;
  }

  std::string cut_expression(raw_ptr, pkt.payload_size);

  // Compile expression using native ROOT TTreeFormula
  TTreeFormula formula("SelectionFormula", cut_expression.c_str(), tree);
  if (formula.GetNdim() == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_FORMULA);
    return;
  }

  // Fast I/O: Disable active reading for unused branches
  tree->SetBranchStatus("*", 0);
  formula.UpdateFormulaLeaves();

  // Allocate 1 bit per entry
  std::size_t bitmask_bytes = (total_entries + 7) / 8;
  std::uint64_t payload_off = shm_heap_alloc_data(shm, bitmask_bytes);
  if (payload_off == 0) {
    tree->SetBranchStatus("*", 1); // Reset status on failure
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  const auto *shm_bytes = reinterpret_cast<const std::uint8_t *>(&shm);
  auto *bitmask = const_cast<std::uint8_t *>(shm_bytes + payload_off);
  std::memset(bitmask, 0, bitmask_bytes);

  std::uint64_t passed_count = 0;

  // Event loop: Evaluate formula for each entry
  for (std::uint64_t i = 0; i < total_entries; ++i) {
    tree->GetEntry(i);

    if (formula.EvalInstance(0) != 0.0) {
      bitmask[i / 8] |= static_cast<std::uint8_t>(1u << (i % 8));
      ++passed_count;
    }
  }

  // Restore branch statuses
  tree->SetBranchStatus("*", 1);

  shm_chunk_commit(shm, payload_off);

  // Send zero-copy descriptor
  if (shm.evt_ring) {
    BridgeMessage msg{};
    msg.type = MsgType::SHM_REF;
    msg.job_id = pkt.job_id;
    msg.req_id = pkt.req_id;

    msg.shm_ref.offset = static_cast<std::uint32_t>(payload_off);
    msg.shm_ref.total_bytes = static_cast<std::uint32_t>(bitmask_bytes);
    msg.shm_ref.dtype = ShmDType::UInt8;
    msg.shm_ref.ndim = 1;
    msg.shm_ref.shape[0] = static_cast<std::uint32_t>(total_entries);

    shm.evt_ring->push(msg);
  }

  std::cout << "[CmdTTree] Selection completed: " << passed_count << " / "
            << total_entries << " events passed filter ("
            << (static_cast<double>(passed_count) /
                static_cast<double>(total_entries) * 100.0)
            << "%).\n";
}

} // namespace Sphere::cmd::ttree
