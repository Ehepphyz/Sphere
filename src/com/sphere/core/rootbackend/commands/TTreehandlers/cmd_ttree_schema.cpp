// commands/TTreeHandlers/cmd_ttree_schema.cpp
#include "ttree_common.h"
#include "cmd_ttree.h"

#include <TBranch.h>
#include <TLeaf.h>
#include <TObjArray.h>
#include <TTree.h>

#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>

namespace Sphere::cmd::ttree {

// ============================================================================
// Inspection: TTree Metadata Query
// ============================================================================
void handle_inspect(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdTTree] Inspecting target TTree metadata structure...\n";

  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  auto *branches = tree->GetListOfBranches();
  std::size_t branch_count = branches ? branches->GetSize() : 0;

  std::string json;
  json.reserve(256);
  json += "{";
  json += "\"name\":\"" + escape_json(tree->GetName()) + "\",";
  json += "\"title\":\"" + escape_json(tree->GetTitle()) + "\",";
  json += "\"entries\":" + std::to_string(tree->GetEntries()) + ",";
  json += "\"branches\":" + std::to_string(branch_count);
  json += "}";

  std::size_t size = json.size();
  std::uint64_t payload_off = shm_heap_alloc_schema(shm, size);
  if (payload_off == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  std::memcpy(shm.base + payload_off, json.data(), size);
  shm_chunk_commit(shm, payload_off);

  send_response(shm, pkt, Platform::PacketType::EVT_OK, 0,
                static_cast<std::uint32_t>(size), ResponseStatus::OK);
}

// ============================================================================
// Schema Scan: Branch & Leaf Structural Hierarchy
// ============================================================================
void handle_scan_branches(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdTTree] Scanning TTree branch and leaf hierarchy...\n";

  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  auto *branches = tree->GetListOfBranches();
  int branch_count = branches ? branches->GetSize() : 0;

  std::string json;
  json.reserve(2048);
  json += "{\"branches\":[";

  for (int i = 0; i < branch_count; ++i) {
    auto *br = static_cast<TBranch *>(branches->At(i));
    if (!br) continue;

    if (i > 0) json += ",";

    json += "{";
    json += "\"name\":\"" + escape_json(br->GetName()) + "\",";
    json += "\"title\":\"" + escape_json(br->GetTitle()) + "\",";
    
    const char *cls = br->GetClassName();
    json += "\"class\":\"" + std::string(cls ? escape_json(cls) : "") + "\",";

    auto *leaves = br->GetListOfLeaves();
    int leaf_count = leaves ? leaves->GetSize() : 0;

    json += "\"leaves\":[";
    for (int j = 0; j < leaf_count; ++j) {
      auto *leaf = static_cast<TLeaf *>(leaves->At(j));
      if (!leaf) continue;

      if (j > 0) json += ",";

      json += "{";
      json += "\"name\":\"" + escape_json(leaf->GetName()) + "\",";
      json += "\"type\":\"" + escape_json(leaf->GetTypeName()) + "\",";
      json += "\"length\":" + std::to_string(leaf->GetLen());
      json += "}";
    }
    json += "]}";
  }
  json += "]}";

  std::size_t size = json.size();
  std::uint64_t payload_off = shm_heap_alloc_schema(shm, size);
  if (payload_off == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  std::memcpy(shm.base + payload_off, json.data(), size);
  shm_chunk_commit(shm, payload_off);

  send_response(shm, pkt, Platform::PacketType::EVT_OK, 0,
                static_cast<std::uint32_t>(size), ResponseStatus::OK);
}

// ============================================================================
// Query: Fast Total Entry Count Retrieval
// ============================================================================
void handle_query_entries(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdTTree] Querying total TTree entry count...\n";

  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  std::string json;
  json.reserve(64);
  json += "{\"entries\":" + std::to_string(tree->GetEntries()) + "}";

  std::size_t size = json.size();
  std::uint64_t payload_off = shm_heap_alloc_tx(shm, size);
  if (payload_off == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  std::memcpy(shm.base + payload_off, json.data(), size);
  shm_chunk_commit(shm, payload_off);

  send_response(shm, pkt, Platform::PacketType::EVT_OK, 0,
                static_cast<std::uint32_t>(size), ResponseStatus::OK);
}

// ============================================================================
// Data Extraction: Single Event Entry Inspection
// ============================================================================
void handle_get_entry(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdTTree] Reading single TTree event entry...\n";

  TTree *tree = get_tree(pkt.job_id);
  if (!tree) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_NO_TREE);
    return;
  }

  std::uint64_t index = pkt.flags;
  if (index >= static_cast<std::uint64_t>(tree->GetEntries())) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_INVALID_ARG);
    return;
  }

  // Load entry from ROOT baskets
  tree->GetEntry(index);

  auto *branches = tree->GetListOfBranches();
  int branch_count = branches ? branches->GetSize() : 0;

  std::string json;
  json.reserve(2048);
  json += "{\"index\":" + std::to_string(index) + ",\"values\":{";

  bool first_branch = true;
  for (int i = 0; i < branch_count; ++i) {
    auto *br = static_cast<TBranch *>(branches->At(i));
    if (!br) continue;

    if (!first_branch) json += ",";
    first_branch = false;

    json += "\"" + escape_json(br->GetName()) + "\":";

    auto *leaves = br->GetListOfLeaves();
    if (leaves && leaves->GetSize() > 0) {
      auto *leaf = static_cast<TLeaf *>(leaves->At(0));
      int len = leaf->GetLen();

      // Handle scalar vs array leaf elements
      if (len > 1) {
        json += "[";
        for (int k = 0; k < len; ++k) {
          if (k > 0) json += ",";
          json += std::to_string(leaf->GetValue(k));
        }
        json += "]";
      } else {
        json += std::to_string(leaf->GetValue());
      }
    } else {
      json += "null";
    }
  }

  json += "}}";

  std::size_t size = json.size();
  std::uint64_t payload_off = shm_heap_alloc_data(shm, size);
  if (payload_off == 0) {
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR, 0, 0,
                  ResponseStatus::ERROR_SHM_OOM);
    return;
  }

  std::memcpy(shm.base + payload_off, json.data(), size);
  shm_chunk_commit(shm, payload_off);

  send_response(shm, pkt, Platform::PacketType::EVT_OK, 0,
                static_cast<std::uint32_t>(size), ResponseStatus::OK);
}

} // namespace Sphere::cmd::ttree