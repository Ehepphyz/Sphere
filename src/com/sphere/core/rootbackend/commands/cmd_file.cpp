// commands/cmd_file.cpp

#include "cmd_file.h"
#include "lockfree_ring.h"
#include "packets.h"
#include "shm_layout.h"

#include <TFile.h>

#include <algorithm>
#include <cstdint>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>

namespace Sphere::cmd::file {

namespace {

/**
 * Thread-safe registry for managing active ROOT file handles.
 */
class FileRegistry {
public:
  static FileRegistry &instance() noexcept {
    static FileRegistry registry;
    return registry;
  }

  bool open_file(std::uint32_t file_id, const char *path, const char *option) {
    // Perform potentially slow file I/O outside the registry mutex lock
    TFile *f = TFile::Open(path, option);
    if (!f || f->IsZombie()) {
      delete f;
      return false;
    }

    std::unique_ptr<TFile> old_file;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto it = files_.find(file_id);
      if (it != files_.end()) {
        old_file = std::move(it->second);
      }
      files_[file_id] = std::unique_ptr<TFile>(f);
    }

    // Safely close and destroy previous instance outside the critical section
    if (old_file && !old_file->IsZombie()) {
      old_file->Close();
    }

    return true;
  }

  bool close_file(std::uint32_t file_id) {
    std::unique_ptr<TFile> file_to_close;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto it = files_.find(file_id);
      if (it == files_.end()) {
        return false;
      }
      file_to_close = std::move(it->second);
      files_.erase(it);
    }

    if (file_to_close && !file_to_close->IsZombie()) {
      file_to_close->Close();
    }
    return true;
  }

  void close_all() {
    std::unordered_map<std::uint32_t, std::unique_ptr<TFile>> files_to_close;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      files_to_close.swap(files_);
    }

    for (auto &[id, file_ptr] : files_to_close) {
      if (file_ptr && !file_ptr->IsZombie()) {
        file_ptr->Close();
      }
    }
  }

  bool save_file(std::uint32_t file_id) {
    TFile *file_ptr = nullptr;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto it = files_.find(file_id);
      if (it == files_.end() || !it->second || it->second->IsZombie()) {
        return false;
      }
      file_ptr = it->second.get();
    }

    // Execute flush/write command safely
    return (file_ptr->Write(nullptr, TObject::kOverwrite) >= 0);
  }

  TFile *get_file(std::uint32_t file_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = files_.find(file_id);
    if (it == files_.end() || !it->second || it->second->IsZombie()) {
      return nullptr;
    }
    return it->second.get();
  }

private:
  FileRegistry() = default;
  ~FileRegistry() = default;

  FileRegistry(const FileRegistry &) = delete;
  FileRegistry &operator=(const FileRegistry &) = delete;

  std::mutex mutex_;
  std::unordered_map<std::uint32_t, std::unique_ptr<TFile>> files_;
};

/**
 * Helper function to dispatch an event response back through the
 * shared memory ring buffer with spin-yield retry logic.
 */
void send_response(ShmLayout &shm, const Platform::PacketHeader &req,
                   Platform::PacketType type, std::uint16_t flags = 0,
                   std::uint32_t payload_size = 0) {
  if (!shm.evt_ring) {
    return;
  }

  BridgeMessage msg{};
  msg.type = static_cast<MsgType>(type);
  msg.flags = flags;
  msg.payload_size = static_cast<std::uint8_t>(
      std::min<std::uint32_t>(payload_size, sizeof(msg.inline_bytes)));
  msg.job_id = req.job_id;
  msg.req_id = req.req_id;

  constexpr int max_retries = 100;
  bool pushed = false;

  for (int retry = 0; retry < max_retries; ++retry) {
    if (shm.evt_ring->push(msg)) {
      pushed = true;
      break;
    }
    std::this_thread::yield();
  }

  if (!pushed) {
    std::cerr << "[CmdFile] Error: Ring buffer exhausted after retries. "
                 "Dropped event message for job_id: "
              << req.job_id << ", req_id: " << req.req_id << "\n";
  }
}

/**
 * Helper function to extract a string path from the shared payload.
 */
std::string_view extract_path(ShmLayout &shm,
                              const Platform::PacketHeader &pkt) {
  if (pkt.payload_size == 0) {
    return {};
  }

  // Resolve base pointer from shared memory layout and apply payload offset
  const auto *base_ptr = reinterpret_cast<const char *>(&shm);
  const char *raw_ptr = base_ptr + pkt.payload_offset;

  if (!raw_ptr) {
    return {};
  }

  return std::string_view(raw_ptr, pkt.payload_size);
}

} // anonymous namespace

void handle_open(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdFile] Executing file open command...\n";

  const std::string_view path_view = extract_path(shm, pkt);
  if (path_view.empty()) {
    std::cerr << "[CmdFile] Error: Invalid or empty file path provided.\n";
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR,
                  1 /* ERR_INVALID_ARG */);
    return;
  }

  // Ensure path is null-terminated before passing to ROOT C-style APIs
  const std::string path(path_view);
  const bool success =
      FileRegistry::instance().open_file(pkt.req_id, path.c_str(), "READ");

  if (!success) {
    std::cerr << "[CmdFile] Error: Failed to open ROOT file: " << path << "\n";
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR,
                  2 /* ERR_FILE_NOT_FOUND */);
    return;
  }

  std::cout << "[CmdFile] Successfully opened file: " << path
            << " [file_id: " << pkt.req_id << "]\n";
  send_response(shm, pkt, Platform::PacketType::EVT_FILE_OPENED);
}

void handle_close(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdFile] Executing file close command...\n";

  const bool success = FileRegistry::instance().close_file(pkt.req_id);
  if (!success) {
    std::cerr << "[CmdFile] Warning: File handle not found for file_id: "
              << pkt.req_id << "\n";
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR,
                  3 /* ERR_HANDLE_INVALID */);
    return;
  }

  std::cout << "[CmdFile] Successfully closed file [file_id: " << pkt.req_id
            << "]\n";
  send_response(shm, pkt, Platform::PacketType::EVT_FILE_CLOSED);
}

void handle_close_all(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdFile] Executing close all files command...\n";

  FileRegistry::instance().close_all();

  std::cout << "[CmdFile] All active file handles closed successfully.\n";
  send_response(shm, pkt, Platform::PacketType::EVT_OK);
}

void handle_save(ShmLayout &shm, const Platform::PacketHeader &pkt) {
  std::cout << "[CmdFile] Executing file save command...\n";

  const bool success = FileRegistry::instance().save_file(pkt.req_id);
  if (!success) {
    std::cerr
        << "[CmdFile] Error: Failed to save changes to ROOT file [file_id: "
        << pkt.req_id << "].\n";
    send_response(shm, pkt, Platform::PacketType::EVT_ERROR,
                  4 /* ERR_WRITE_FAILED */);
    return;
  }

  std::cout << "[CmdFile] Successfully saved changes to file [file_id: "
            << pkt.req_id << "]\n";
  send_response(shm, pkt, Platform::PacketType::EVT_OK);
}

} // namespace Sphere::cmd::file
