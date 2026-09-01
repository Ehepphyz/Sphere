// commands/cmd_file.cpp

#include "cmd_file.h"
#include "command_registry.h"
#include "lockfree_ring.h"
#include "packets.h"
#include "shm_layout.h"

#include <TFile.h>
#include <TList.h>

#include <algorithm>
#include <cstdint>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <cstring>
#include <thread>
#include <unordered_map>
#include <vector>

namespace Sphere::cmd::file {

namespace {

/**
* Thread-safe registry for managing active ROOT file handles.
*/
/// Short name derived from the path, so a human can type it.
std::string short_name(const std::string &path) {
  std::size_t begin = path.find_last_of("/\\");
  begin = (begin == std::string::npos) ? 0 : begin + 1;
  std::size_t end = path.find_last_of('.');
  if (end == std::string::npos || end <= begin) {
    end = path.size();
  }
  std::string name = path.substr(begin, end - begin);
  return name.empty() ? std::string("file") : name;
}

struct FileEntry {
  std::unique_ptr<TFile> file;
  std::string path;
  std::string name;
};

/**
* Open ROOT files, addressable by a small number or by a short name. Both are
* handed back when the file opens, because a human types whichever is shorter.
*/
class FileRegistry {
public:
  struct Handle {
    std::uint32_t id{0};
    std::string name;
  };

  static FileRegistry &instance() noexcept {
    static FileRegistry registry;
    return registry;
  }

  /// Opens the file and returns its handle; id is 0 when the file is unusable.
  Handle open_file(const char *path, const char *option) {
    TFile *raw = TFile::Open(path, option);
    if (!raw || raw->IsZombie()) {
      delete raw;
      return {};
    }

    std::lock_guard<std::mutex> lock(mutex_);
    const std::string full(path);
    std::string name = short_name(full);
    if (by_name_.count(name) != 0) {
      // Same basename from another directory: keep both, tell them apart.
      for (int suffix = 2;; ++suffix) {
        std::string candidate = name + "~" + std::to_string(suffix);
        if (by_name_.count(candidate) == 0) {
          name = std::move(candidate);
          break;
        }
      }
    }

    const std::uint32_t id = next_id_++;
    FileEntry entry;
    entry.file.reset(raw);
    entry.path = full;
    entry.name = name;
    files_.emplace(id, std::move(entry));
    by_name_.emplace(name, id);
    return {id, name};
  }

  /// Accepts a number or a name; 0 when neither matches.
  std::uint32_t resolve(const std::string &token) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!token.empty() &&
        token.find_first_not_of("0123456789") == std::string::npos) {
      const std::uint32_t id =
          static_cast<std::uint32_t>(std::strtoul(token.c_str(), nullptr, 10));
      return (files_.count(id) != 0) ? id : 0;
    }
    auto it = by_name_.find(token);
    return (it != by_name_.end()) ? it->second : 0;
  }

  bool close_file(std::uint32_t id) {
    std::unique_ptr<TFile> closing;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto it = files_.find(id);
      if (it == files_.end()) {
        return false;
      }
      by_name_.erase(it->second.name);
      closing = std::move(it->second.file);
      files_.erase(it);
    }
    if (closing && !closing->IsZombie()) {
      closing->Close();
    }
    return true;
  }

  void close_all() {
    std::unordered_map<std::uint32_t, FileEntry> closing;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      closing.swap(files_);
      by_name_.clear();
    }
    for (auto &[id, entry] : closing) {
      if (entry.file && !entry.file->IsZombie()) {
        entry.file->Close();
      }
    }
  }

  bool save_file(std::uint32_t id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = files_.find(id);
    if (it == files_.end() || !it->second.file || it->second.file->IsZombie()) {
      return false;
    }
    if (!it->second.file->IsWritable()) {
      return false; // opened READ; reporting success would be a lie
    }
    return (it->second.file->Write(nullptr, TObject::kOverwrite) >= 0);
  }

  TFile *get_file(std::uint32_t id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = files_.find(id);
    if (it == files_.end() || !it->second.file || it->second.file->IsZombie()) {
      return nullptr;
    }
    return it->second.file.get();
  }

  std::string list() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (files_.empty()) {
      return "no file open";
    }
    std::vector<std::uint32_t> ids;
    ids.reserve(files_.size());
    for (const auto &[id, entry] : files_) {
      ids.push_back(id);
    }
    std::sort(ids.begin(), ids.end());

    std::string out;
    for (const std::uint32_t id : ids) {
      const FileEntry &entry = files_.at(id);
      out += std::to_string(id);
      out += "  ";
      out += entry.name;
      out += "  ";
      out += entry.path;
      const int keys = (entry.file && entry.file->GetListOfKeys() != nullptr)
                           ? entry.file->GetListOfKeys()->GetSize()
                           : 0;
      out += "  " + std::to_string(keys) + " key(s)";
      out += entry.file && entry.file->IsWritable() ? "  writable\n" : "\n";
    }
    if (!out.empty() && out.back() == '\n') {
      out.pop_back();
    }
    return out;
  }

private:
  FileRegistry() = default;
  ~FileRegistry() = default;

  FileRegistry(const FileRegistry &) = delete;
  FileRegistry &operator=(const FileRegistry &) = delete;

  mutable std::mutex mutex_;
  std::uint32_t next_id_{1};
  std::unordered_map<std::uint32_t, FileEntry> files_;
  std::unordered_map<std::string, std::uint32_t> by_name_;
};

/**
* Helper function to dispatch an event response back through the
* shared memory ring buffer with spin-yield retry logic.
*/
void send_response(ShmLayout &shm, const Proto::PacketHeader &req,
                   Proto::PacketType type, std::uint16_t flags = 0,
                   std::uint32_t payload_size = 0) {
  if (!shm.evt_ring) {
    return;
  }

  BridgeMessage msg{};
  msg.type = MsgType::INLINE_DATA;
  msg.cmd = static_cast<std::uint16_t>(type);
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
* Text reply through the shared heap: a handle, a list, or a reason.
*/
void send_text(ShmLayout &shm, const Proto::PacketHeader &req,
               Proto::PacketType type, const std::string &text) {
  if (shm.evt_ring == nullptr) {
    return;
  }

  ScopedChunkWriter writer(shm, text.size() + 1);
  if (!writer) {
    std::cerr << "[CmdFile] Error: no room in the shared heap for the reply.\n";
    return;
  }
  std::memcpy(writer.data(), text.data(), text.size() + 1);
  writer.commit();

  BridgeMessage msg{};
  msg.type = MsgType::SHM_REF;
  msg.cmd = static_cast<std::uint16_t>(type);
  msg.job_id = req.job_id;
  msg.req_id = req.req_id;
  msg.shm_ref.offset = static_cast<std::uint32_t>(writer.offset());
  msg.shm_ref.total_bytes = static_cast<std::uint32_t>(text.size() + 1);
  msg.shm_ref.dtype = ShmDType::UInt8;
  msg.shm_ref.ndim = 1;
  msg.shm_ref.shape[0] = static_cast<std::uint32_t>(text.size() + 1);

  for (int retry = 0; retry < 100; ++retry) {
    if (shm.evt_ring->push(msg)) {
      return;
    }
    std::this_thread::yield();
  }
  std::cerr << "[CmdFile] Error: event ring full, reply dropped for req_id "
            << req.req_id << ".\n";
}

/**
* Helper function to extract a string path from the shared payload.
*/
std::string_view extract_path(ShmLayout &shm,
                              const Proto::PacketHeader &pkt) {
  if (pkt.payload_size == 0) {
    return {};
  }

  if (shm.base == nullptr || shm.header == nullptr) {
    return {};
  }

  const std::uint64_t total = shm.header->total_size;
  if (pkt.payload_offset >= total ||
      pkt.payload_size > total - pkt.payload_offset) {
    return {};
  }

  const auto *raw_ptr =
      reinterpret_cast<const char *>(shm.base + pkt.payload_offset);
  return std::string_view(raw_ptr, pkt.payload_size);
}

} // anonymous namespace

void handle_open(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;

  const std::string_view path_view = extract_path(shm, pkt);
  if (path_view.empty()) {
    send_text(shm, pkt, Proto::PacketType::EVT_ERROR,
              "ERROR: no path given. Usage: :root file open <path>");
    return;
  }

  const std::string path(path_view);
  const FileRegistry::Handle handle =
      FileRegistry::instance().open_file(path.c_str(), "READ");

  if (handle.id == 0) {
    send_text(shm, pkt, Proto::PacketType::EVT_ERROR,
              "ERROR: cannot open as a ROOT file: " + path);
    return;
  }

  send_text(shm, pkt, Proto::PacketType::EVT_FILE_OPENED,
            "FILE_OPENED  FILE_ID: " + std::to_string(handle.id) + "  " +
                handle.name);
}

void handle_close(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;

  const std::string token(extract_path(shm, pkt));
  const std::uint32_t id = FileRegistry::instance().resolve(token);
  if (id == 0) {
    send_text(shm, pkt, Proto::PacketType::EVT_ERROR,
              "ERROR: no open file called '" + token +
                  "'. Try :root file list");
    return;
  }
  if (!FileRegistry::instance().close_file(id)) {
    send_text(shm, pkt, Proto::PacketType::EVT_ERROR,
              "ERROR: could not close '" + token + "'");
    return;
  }
  send_text(shm, pkt, Proto::PacketType::EVT_FILE_CLOSED,
            "FILE_CLOSED  " + token);
}

void close_all_files() { FileRegistry::instance().close_all(); }

void handle_close_all(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  FileRegistry::instance().close_all();
  send_text(shm, pkt, Proto::PacketType::EVT_OK, "all files closed");
}

void handle_save(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;

  const std::string token(extract_path(shm, pkt));
  const std::uint32_t id = FileRegistry::instance().resolve(token);
  if (id == 0) {
    send_text(shm, pkt, Proto::PacketType::EVT_ERROR,
              "ERROR: no open file called '" + token +
                  "'. Try :root file list");
    return;
  }
  if (!FileRegistry::instance().save_file(id)) {
    send_text(shm, pkt, Proto::PacketType::EVT_ERROR,
              "ERROR: '" + token +
                  "' was opened read-only, or the write failed");
    return;
  }
  send_text(shm, pkt, Proto::PacketType::EVT_OK, "SAVED  " + token);
}

void handle_list(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  send_text(shm, pkt, Proto::PacketType::EVT_OK,
            FileRegistry::instance().list());
}

void register_all() {
  auto &registry = CommandRegistry::instance();
  registry.register_command(Proto::PacketType::CMD_OPEN_FILE, &handle_open);
  registry.register_command(Proto::PacketType::CMD_CLOSE_FILE, &handle_close);
  registry.register_command(Proto::PacketType::CMD_CLOSE_ALL_FILES,
                            &handle_close_all);
  registry.register_command(Proto::PacketType::CMD_SAVE_FILE, &handle_save);
  registry.register_command(Proto::PacketType::CMD_FILE_LIST, &handle_list);
}

} // namespace Sphere::cmd::file
