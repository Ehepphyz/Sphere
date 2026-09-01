// cmd_system.cpp

#include "commands/cmd_system.h"
#include "command_registry.h"
#include "lockfree_ring.h"
#include "engine.h"
#include "packets.h"
#include "shm_layout.h"

#include <ROOT/RConfig.hxx>
#include <RVersion.h>
#include <TROOT.h>
#include <TInterpreter.h>
#include <TSystem.h>

#include <array>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <string>
#include <string_view>
#include <fstream>
#include <iterator>
#include <thread>

namespace Sphere {
namespace cmd {
namespace sys {

namespace {
// Engine launch timestamp for system uptime calculations
const auto g_engine_start_time = std::chrono::steady_clock::now();
} // namespace

// Opcode enumeration representing system commands (aligned with Java IPC layer protocol)
enum class SystemOpCode : std::uint16_t {
  Ping = 0,
  GetRootVersion = 1,
  GetIncDir = 2,
  GetLibDir = 3,
  GetFeatures = 4,
  GetCflags = 5,
  GetLibs = 6,
  GetMetrics = 7,

  // Extended root-config feature flags
  GetPrefix = 8,
  GetExecPrefix = 9,
  GetAuxCflags = 10,
  GetLdFlags = 11,
  GetGlibs = 12,
  GetEveLibs = 13,
  GetBinDir = 14,
  GetEtcDir = 15,
  GetTutDir = 16,
  GetSrcDir = 17,
  GetArch = 18,
  GetPlatform = 19,
  GetConfig = 20,
  GetNcpu = 21,
  GetGitRevision = 22,
  GetPythonVersion = 23,
  GetCxxStandard = 24,
  GetCc = 25,
  GetCxx = 26,
  GetLd = 27,

  Count // Total number of opcodes used for static table sizing
};

// Lightweight lock-free telemetry tracking IPC execution performance
struct SystemTelemetry {
  std::atomic<std::uint64_t> requests_processed{0};
  std::atomic<std::uint64_t> shm_allocation_failures{0};
  std::atomic<std::uint64_t> invalid_opcodes{0};
};

inline SystemTelemetry &get_telemetry() {
  static SystemTelemetry metrics;
  return metrics;
}

// Thread-safe runtime configuration cache populated ONCE at startup via CERN ROOT C++ API
class AdvancedRootConfigCache {
public:
  static AdvancedRootConfigCache &instance() {
    static AdvancedRootConfigCache cache;
    return cache;
  }

  // Zero-allocation lookup returning string_view for instant SIMD payload serialization
  std::string_view get(SystemOpCode code) const {
    const auto index = static_cast<std::size_t>(code);
    if (index < cache_.size() && !cache_[index].empty()) {
      return cache_[index];
    }
    return "ERROR: Configuration key invalid";
  }

private:
  static std::string get_env_or_default(const char *env_var,
                                        std::string_view fallback_suffix) {
    (void)env_var;

    const char *rootsys = gSystem ? gSystem->Getenv("ROOTSYS") : nullptr;
    if (rootsys != nullptr && *rootsys != '\0') {
      return std::string(rootsys) + std::string(fallback_suffix);
    }
    return "ERROR: ROOTSYS environment variable not defined";
  }

  static std::string resolve_incdir() {
    const char *inc = gSystem ? gSystem->GetIncludePath() : nullptr;
    if (inc == nullptr || *inc == '\0') {
      return get_env_or_default("ROOTSYS", "/include");
    }

    std::string_view s(inc);
    std::size_t pos = s.find("-I");
    if (pos == std::string_view::npos) {
      return std::string(s);
    }

    pos += 2;
    while (pos < s.size() && std::isspace(static_cast<unsigned char>(s[pos]))) {
      ++pos;
    }
    std::size_t end = pos;
    while (end < s.size() &&
           !std::isspace(static_cast<unsigned char>(s[end]))) {
      ++end;
    }
    return std::string(s.substr(pos, end - pos));
  }

  static std::string resolve_libdir() {
    const char *rootsys = gSystem ? gSystem->Getenv("ROOTSYS") : nullptr;
    if (rootsys != nullptr && *rootsys != '\0') {
      return std::string(rootsys) + "/lib";
    }

    const char *dyn = gSystem ? gSystem->GetDynamicPath() : nullptr;
    if (dyn == nullptr || *dyn == '\0') {
      return "ERROR: Unable to determine ROOT library directory";
    }

    std::string_view s(dyn);
#if defined(_WIN32)
    constexpr char sep = ';';
#else
    constexpr char sep = ':';
#endif
    std::size_t end = s.find(sep);
    return (end == std::string_view::npos) ? std::string(s)
                                           : std::string(s.substr(0, end));
  }

  static std::string resolve_cflags(std::string_view incdir) {
    const char *inc = gSystem ? gSystem->GetIncludePath() : nullptr;
    if (inc != nullptr && *inc != '\0') {
      return std::string(inc);
    }
    if (incdir.starts_with("ERROR:")) {
      return "ERROR: Unable to determine ROOT compile flags";
    }
    return "-I" + std::string(incdir);
  }

  static std::string resolve_libs(std::string_view libdir) {
    if (libdir.starts_with("ERROR:")) {
      return "ERROR: Unable to determine ROOT libraries";
    }
    const char *libs = gSystem ? gSystem->GetLibraries() : nullptr;
    if (libs != nullptr && *libs != '\0') {
      return "-L" + std::string(libdir) + " " + std::string(libs);
    }
    return "-L" + std::string(libdir) +
           " -lCore -lRIO -lNet -lHist -lGraf -lGraf3d -lGpad -lTree -lRint"
           " -lPostscript -lMatrix -lPhysics -lMathCore -lThread -lROOTVecOps "
           "-lNTuple";
  }

  static std::string resolve_glibs(std::string_view libdir) {
    if (libdir.starts_with("ERROR:")) {
      return "ERROR: Unable to determine ROOT glibs";
    }
    return "-L" + std::string(libdir) +
           " -lGui -lCore -lRIO -lNet -lHist -lGraf -lGraf3d -lGpad -lTree";
  }

  static std::string resolve_evelibs(std::string_view libdir) {
    if (libdir.starts_with("ERROR:")) {
      return "ERROR: Unable to determine ROOT evelibs";
    }
    return "-L" + std::string(libdir) +
           " -lEve -lGeom -lGed -lRGL -lGui -lCore";
  }

  static std::string resolve_features() {
    static constexpr std::array<const char *, 8> known_features = {
        "cxx17", "root7",    "webgui", "http",
        "imt",   "mathmore", "thread", "shared"};

    std::string result;

    // Retrieve CMake build options used during ROOT compilation
    if (gROOT != nullptr) {
      const std::string config_opts = gROOT->GetConfigOptions();
      for (const char *feat : known_features) {
        // Parse feature flags enabled during ROOT configuration
        if (config_opts.find(feat) != std::string::npos) {
          if (!result.empty()) {
            result += ' ';
          }
          result += feat;
        }
      }
    }

    return result.empty() ? "core io hist graf tree mathcore thread ntuple"
                          : result;
  }

  static std::string resolve_ncpu() {
    // Query hardware thread count using standard C++ threads first
    const unsigned int hardware_threads = std::thread::hardware_concurrency();
    if (hardware_threads > 0) {
      return std::to_string(hardware_threads);
    }

    // Fall back to CERN ROOT TSystem CpuInfo_t query
    if (gSystem != nullptr) {
      CpuInfo_t info{};
      gSystem->GetCpuInfo(&info, 50);
      const int cpus = info.fTotal > 0 ? info.fTotal : 1;
      return std::to_string(cpus);
    }

    return "1";
  }

  // Constructor executes once during static thread-safe initialization
  AdvancedRootConfigCache() {
    cache_[static_cast<std::size_t>(SystemOpCode::Ping)] = "PONG";
    cache_[static_cast<std::size_t>(SystemOpCode::GetRootVersion)] =
        gROOT ? std::string(gROOT->GetVersion()) : std::string(ROOT_RELEASE);

    // Dynamic directory resolution
    const std::string prefix = get_env_or_default("ROOTSYS", "");
    const std::string incdir = resolve_incdir();
    const std::string libdir = resolve_libdir();

    cache_[static_cast<std::size_t>(SystemOpCode::GetPrefix)] = prefix;
    cache_[static_cast<std::size_t>(SystemOpCode::GetExecPrefix)] = prefix;
    cache_[static_cast<std::size_t>(SystemOpCode::GetIncDir)] = incdir;
    cache_[static_cast<std::size_t>(SystemOpCode::GetLibDir)] = libdir;
    cache_[static_cast<std::size_t>(SystemOpCode::GetBinDir)] =
        get_env_or_default("ROOTSYS", "/bin");
    cache_[static_cast<std::size_t>(SystemOpCode::GetEtcDir)] =
        get_env_or_default("ROOTSYS", "/etc");
    cache_[static_cast<std::size_t>(SystemOpCode::GetTutDir)] =
        get_env_or_default("ROOTSYS", "/tutorials");
    cache_[static_cast<std::size_t>(SystemOpCode::GetSrcDir)] =
        get_env_or_default("ROOTSYS", "/src");

    // Compiler and Linker Flags
    cache_[static_cast<std::size_t>(SystemOpCode::GetFeatures)] =
        resolve_features();
    cache_[static_cast<std::size_t>(SystemOpCode::GetCflags)] =
        resolve_cflags(incdir);
    cache_[static_cast<std::size_t>(SystemOpCode::GetAuxCflags)] = "";
    cache_[static_cast<std::size_t>(SystemOpCode::GetLdFlags)] = "";
    cache_[static_cast<std::size_t>(SystemOpCode::GetLibs)] =
        resolve_libs(libdir);
    cache_[static_cast<std::size_t>(SystemOpCode::GetGlibs)] =
        resolve_glibs(libdir);
    cache_[static_cast<std::size_t>(SystemOpCode::GetEveLibs)] =
        resolve_evelibs(libdir);

    // Host Platform Metadata & Toolchain Information
    cache_[static_cast<std::size_t>(SystemOpCode::GetArch)] =
        gSystem ? gSystem->GetBuildArch() : "unknown";
    cache_[static_cast<std::size_t>(SystemOpCode::GetPlatform)] =
        gSystem ? gSystem->GetName() : "unknown";
    cache_[static_cast<std::size_t>(SystemOpCode::GetConfig)] =
        gROOT ? gROOT->GetConfigOptions() : "";
    cache_[static_cast<std::size_t>(SystemOpCode::GetNcpu)] = resolve_ncpu();
    cache_[static_cast<std::size_t>(SystemOpCode::GetGitRevision)] =
        gROOT ? gROOT->GetGitCommit() : "unknown";
    cache_[static_cast<std::size_t>(SystemOpCode::GetPythonVersion)] = "3";
    cache_[static_cast<std::size_t>(SystemOpCode::GetCxxStandard)] = "20";
    cache_[static_cast<std::size_t>(SystemOpCode::GetCc)] = "cc";
    cache_[static_cast<std::size_t>(SystemOpCode::GetCxx)] = "c++";
    cache_[static_cast<std::size_t>(SystemOpCode::GetLd)] = "c++";
  }

  std::array<std::string, static_cast<std::size_t>(SystemOpCode::Count)> cache_;
};

// Response helper serializing output payload into Shared Memory ring buffer
static void send_response(ShmLayout &shm, std::uint64_t job_id,
                          std::uint64_t req_id, std::string_view payload) {
  if (shm.evt_ring == nullptr) {
    return;
  }

  const std::size_t payload_len = payload.size();
  ScopedChunkWriter writer(shm, payload_len + 1);

  if (!writer) {
    get_telemetry().shm_allocation_failures.fetch_add(
        1, std::memory_order_relaxed);
    std::cerr
        << "[cmd_system] Critical: Shared Memory chunk allocation failed.\n";
    return;
  }

  std::memcpy(writer.data(), payload.data(), payload_len);
  reinterpret_cast<char *>(writer.data())[payload_len] = '\0';

  writer.commit();

  BridgeMessage msg{};
  msg.type = MsgType::SHM_REF;
  msg.cmd = static_cast<std::uint16_t>(Proto::PacketType::EVT_OK);

  msg.job_id = static_cast<std::uint32_t>(job_id);
  msg.req_id = static_cast<std::uint32_t>(req_id);
  if (writer.offset() > 0xFFFFFFFFULL || payload_len + 1 > 0xFFFFFFFFULL) {
    get_telemetry().shm_allocation_failures.fetch_add(1,
                                                      std::memory_order_relaxed);
    return;
  }
  msg.shm_ref.offset = static_cast<std::uint32_t>(writer.offset());
  msg.shm_ref.total_bytes = static_cast<std::uint32_t>(payload_len + 1);
  msg.shm_ref.dtype = ShmDType::UInt8;
  msg.shm_ref.ndim = 1;
  msg.shm_ref.shape[0] = static_cast<std::uint32_t>(payload_len + 1);

  if (!shm.evt_ring->push(msg)) {
    std::cerr << "[cmd_system] Warning: event ring full, response dropped for "
                 "job_id "
              << job_id << ".\n";
    return;
  }
  get_telemetry().requests_processed.fetch_add(1, std::memory_order_relaxed);
}

// Function pointer signature for system command dispatchers
using SystemCommandHandler = void (*)(ShmLayout &,
                                      const Proto::PacketHeader &);

template <SystemOpCode OpCode>
void handle_cached_config(ShmLayout &shm, const Proto::PacketHeader &pkt) {
  send_response(shm, pkt.job_id, pkt.req_id,
                AdvancedRootConfigCache::instance().get(OpCode));
}

// Runtime Telemetry Status Reporter
inline void handle_metrics(ShmLayout &shm,
                           const Proto::PacketHeader &pkt) {
  auto &tel = get_telemetry();
  std::string report =
      "processed=" + std::to_string(tel.requests_processed.load()) +
      " alloc_failures=" + std::to_string(tel.shm_allocation_failures.load()) +
      " invalid_opcodes=" + std::to_string(tel.invalid_opcodes.load());
  send_response(shm, pkt.job_id, pkt.req_id, report);
}

// Static O(1) Dispatch Table Registry
class SystemCommandDispatcher {
public:
  static SystemCommandDispatcher &instance() {
    static SystemCommandDispatcher dispatcher;
    return dispatcher;
  }

  void dispatch(ShmLayout &shm, const Proto::PacketHeader &pkt) const {
    const auto opcode = static_cast<std::uint16_t>(pkt.flags & 0xFFFF);

    if (opcode >= static_cast<std::uint16_t>(SystemOpCode::Count)) {
      get_telemetry().invalid_opcodes.fetch_add(1, std::memory_order_relaxed);
      send_response(shm, pkt.job_id, pkt.req_id,
                    "ERROR: Invalid system opcode");
      return;
    }

    const auto opcode_val = static_cast<std::size_t>(opcode);
    table_[opcode_val](shm, pkt);
  }

private:
  SystemCommandDispatcher() {
    table_.fill(&handle_unsupported);

    // Register handlers via compile-time template instantiations
    table_[static_cast<std::size_t>(SystemOpCode::Ping)] =
        &handle_cached_config<SystemOpCode::Ping>;
    table_[static_cast<std::size_t>(SystemOpCode::GetRootVersion)] =
        &handle_cached_config<SystemOpCode::GetRootVersion>;
    table_[static_cast<std::size_t>(SystemOpCode::GetIncDir)] =
        &handle_cached_config<SystemOpCode::GetIncDir>;
    table_[static_cast<std::size_t>(SystemOpCode::GetLibDir)] =
        &handle_cached_config<SystemOpCode::GetLibDir>;
    table_[static_cast<std::size_t>(SystemOpCode::GetFeatures)] =
        &handle_cached_config<SystemOpCode::GetFeatures>;
    table_[static_cast<std::size_t>(SystemOpCode::GetCflags)] =
        &handle_cached_config<SystemOpCode::GetCflags>;
    table_[static_cast<std::size_t>(SystemOpCode::GetLibs)] =
        &handle_cached_config<SystemOpCode::GetLibs>;
    table_[static_cast<std::size_t>(SystemOpCode::GetMetrics)] =
        &handle_metrics;

    // Extended root-config flags
    table_[static_cast<std::size_t>(SystemOpCode::GetPrefix)] =
        &handle_cached_config<SystemOpCode::GetPrefix>;
    table_[static_cast<std::size_t>(SystemOpCode::GetExecPrefix)] =
        &handle_cached_config<SystemOpCode::GetExecPrefix>;
    table_[static_cast<std::size_t>(SystemOpCode::GetAuxCflags)] =
        &handle_cached_config<SystemOpCode::GetAuxCflags>;
    table_[static_cast<std::size_t>(SystemOpCode::GetLdFlags)] =
        &handle_cached_config<SystemOpCode::GetLdFlags>;
    table_[static_cast<std::size_t>(SystemOpCode::GetGlibs)] =
        &handle_cached_config<SystemOpCode::GetGlibs>;
    table_[static_cast<std::size_t>(SystemOpCode::GetEveLibs)] =
        &handle_cached_config<SystemOpCode::GetEveLibs>;
    table_[static_cast<std::size_t>(SystemOpCode::GetBinDir)] =
        &handle_cached_config<SystemOpCode::GetBinDir>;
    table_[static_cast<std::size_t>(SystemOpCode::GetEtcDir)] =
        &handle_cached_config<SystemOpCode::GetEtcDir>;
    table_[static_cast<std::size_t>(SystemOpCode::GetTutDir)] =
        &handle_cached_config<SystemOpCode::GetTutDir>;
    table_[static_cast<std::size_t>(SystemOpCode::GetSrcDir)] =
        &handle_cached_config<SystemOpCode::GetSrcDir>;
    table_[static_cast<std::size_t>(SystemOpCode::GetArch)] =
        &handle_cached_config<SystemOpCode::GetArch>;
    table_[static_cast<std::size_t>(SystemOpCode::GetPlatform)] =
        &handle_cached_config<SystemOpCode::GetPlatform>;
    table_[static_cast<std::size_t>(SystemOpCode::GetConfig)] =
        &handle_cached_config<SystemOpCode::GetConfig>;
    table_[static_cast<std::size_t>(SystemOpCode::GetNcpu)] =
        &handle_cached_config<SystemOpCode::GetNcpu>;
    table_[static_cast<std::size_t>(SystemOpCode::GetGitRevision)] =
        &handle_cached_config<SystemOpCode::GetGitRevision>;
    table_[static_cast<std::size_t>(SystemOpCode::GetPythonVersion)] =
        &handle_cached_config<SystemOpCode::GetPythonVersion>;
    table_[static_cast<std::size_t>(SystemOpCode::GetCxxStandard)] =
        &handle_cached_config<SystemOpCode::GetCxxStandard>;
    table_[static_cast<std::size_t>(SystemOpCode::GetCc)] =
        &handle_cached_config<SystemOpCode::GetCc>;
    table_[static_cast<std::size_t>(SystemOpCode::GetCxx)] =
        &handle_cached_config<SystemOpCode::GetCxx>;
    table_[static_cast<std::size_t>(SystemOpCode::GetLd)] =
        &handle_cached_config<SystemOpCode::GetLd>;
  }

  static void handle_unsupported(ShmLayout &shm,
                                 const Proto::PacketHeader &pkt) {
    get_telemetry().invalid_opcodes.fetch_add(1, std::memory_order_relaxed);
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: Unsupported system opcode");
  }

  std::array<SystemCommandHandler,
             static_cast<std::size_t>(SystemOpCode::Count)>
      table_;
};

// Reads a request payload out of the region, bounds-checked.
static std::string read_payload(const ShmLayout &shm,
                                const Proto::PacketHeader &pkt) {
  constexpr std::size_t kMaxCommand = 64 * 1024;
  if (shm.base == nullptr || shm.header == nullptr || pkt.payload_size == 0 ||
      pkt.payload_size > kMaxCommand) {
    return {};
  }
  const std::uint64_t total = shm.header->total_size;
  if (pkt.payload_offset == 0 || pkt.payload_offset >= total ||
      pkt.payload_size > total - pkt.payload_offset) {
    return {};
  }
  const auto *bytes =
      reinterpret_cast<const char *>(shm.base + pkt.payload_offset);
  std::size_t length = 0;
  while (length < pkt.payload_size && bytes[length] != '\0') {
    ++length;
  }
  return std::string(bytes, length);
}

/**
* Captures what ROOT prints during one interpreter call. Held under
* interpreter_mutex(), so the two cling calls never overlap.
*/
class OutputCapture {
public:
  OutputCapture() {
    if (gSystem == nullptr) {
      return;
    }
    path_ = std::string(gSystem->TempDirectory()) + "/sphere_cling_" +
            std::to_string(gSystem->GetPid()) + ".txt";
    active_ = (gSystem->RedirectOutput(path_.c_str(), "w", &handle_) == 0);
  }

  ~OutputCapture() { (void)stop(); }

  OutputCapture(const OutputCapture &) = delete;
  OutputCapture &operator=(const OutputCapture &) = delete;

  std::string stop() {
    if (!active_) {
      return {};
    }
    active_ = false;
    gSystem->RedirectOutput(nullptr, "", &handle_);

    std::string text;
    if (std::ifstream in(path_); in) {
      text.assign(std::istreambuf_iterator<char>(in),
                  std::istreambuf_iterator<char>());
    }
    gSystem->Unlink(path_.c_str());

    constexpr std::size_t kMaxCapture = 60 * 1024;
    if (text.size() > kMaxCapture) {
      text.resize(kMaxCapture);
      text += "\n[truncated]";
    }
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
      text.pop_back();
    }
    return text;
  }

private:
  std::string path_;
  RedirectHandle_t handle_;
  bool active_{false};
};

// A std::string inside the interpreter that handlers can read directly.
std::mutex &interpreter_mutex() {
  static std::mutex mutex;
  return mutex;
}

/// Declares one block and, when it fails, says why instead of staying silent.
bool declare_block(const char *what, const char *source) {
  OutputCapture capture;
  const bool ok = gInterpreter->Declare(source);
  const std::string diagnostic = capture.stop();
  if (!ok) {
    std::cerr << "[cmd_system] The interpreter refused the " << what
              << " declarations:\n"
              << diagnostic << "\n";
  }
  return ok;
}

std::string *interpreter_result_slot() {
  static std::string *const slot = []() -> std::string * {
    if (gInterpreter == nullptr) {
      return nullptr;
    }

    // The core block must succeed: everything else in this file depends on it.
    if (!declare_block("core",
        "#include <string>\n"
        "#include <sstream>\n"
        "#include <type_traits>\n"
        "namespace SphereBridge {\n"
        "  inline std::string last_result;\n"
        "  template <typename T> std::string ToText(const T &value) {\n"
        "    std::ostringstream out; out << value; return out.str();\n"
        "  }\n"
        "  inline std::string ToText(const char *value) {\n"
        "    return (value != nullptr) ? std::string(value) : std::string();\n"
        "  }\n"
        "  template <typename F> std::string Run(F &&f) {\n"
        "    try {\n"
        "      if constexpr (std::is_void_v<decltype(f())>) { f(); return \"OK\"; }\n"
        "      else { return ToText(f()); }\n"
        "    } catch (const std::exception &e) {\n"
        "      return std::string(\"ERROR: \") + e.what();\n"
        "    } catch (...) {\n"
        "      return \"ERROR: the call raised an unknown exception\";\n"
        "    }\n"
        "  }\n"
        "}\n")) {
      return nullptr;
    }

    TInterpreter::EErrorCode error = TInterpreter::kNoError;
    Long_t address = 0;
    {
      OutputCapture capture;
      address = gInterpreter->ProcessLine("(void*)&SphereBridge::last_result",
                                          &error);
      const std::string diagnostic = capture.stop();
      if (error != TInterpreter::kNoError || address == 0) {
        std::cerr << "[cmd_system] The interpreter could not hand back the "
                     "result slot:\n"
                  << diagnostic << "\n";
        return nullptr;
      }
    }
    return reinterpret_cast<std::string *>(address);
  }();
  return slot;
}

/**
* Checked object lookup. Declared on its own: if a ROOT build refuses it, the
* commands that name an object degrade, the interpreter itself keeps working.
*/
bool object_lookup_ready() {
  static const bool ready = (gInterpreter != nullptr) &&
      declare_block("object lookup",
        "#include <stdexcept>\n"
        "#include <string>\n"
        "#include \"TROOT.h\"\n"
        "#include \"TFile.h\"\n"
        "#include \"TDirectory.h\"\n"
        "namespace SphereBridge {\n"
        "  template <typename T> T *Need(const char *name, const char *type) {\n"
        "    TObject *found = gROOT->FindObject(name);\n"
        "    if (found == nullptr && gDirectory != nullptr) {\n"
        "      found = gDirectory->Get(name);\n"
        "    }\n"
        "    if (found == nullptr && gFile != nullptr) {\n"
        "      found = gFile->Get(name);\n"
        "    }\n"
        "    if (found == nullptr) {\n"
        "      throw std::runtime_error(std::string(\"no object named '\") + name + \"'\");\n"
        "    }\n"
        "    T *typed = dynamic_cast<T *>(found);\n"
        "    if (typed == nullptr) {\n"
        "      throw std::runtime_error(std::string(\"'\") + name + \"' is a \" +\n"
        "                               found->ClassName() + \", not a \" + type);\n"
        "    }\n"
        "    return typed;\n"
        "  }\n"
        "}\n");
  return ready;
}

// Runs one line through the ROOT interpreter and answers with its result.
void handle_cling_exec(ShmLayout &shm, const Proto::PacketHeader &pkt,
                       void *context) {
  (void)context;

  const std::string command = read_payload(shm, pkt);
  if (command.empty()) {
    get_telemetry().invalid_opcodes.fetch_add(1, std::memory_order_relaxed);
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: empty or unreadable interpreter command");
    return;
  }

  if (gInterpreter == nullptr) {
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: the ROOT interpreter is not available");
    return;
  }

  const std::lock_guard<std::mutex> interpreter_lock(interpreter_mutex());

  std::string *slot = interpreter_result_slot();
  if (slot == nullptr) {
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: could not prepare the interpreter result slot");
    return;
  }

  if (command.find("SphereBridge::Need<") != std::string::npos &&
      !object_lookup_ready()) {
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: this ROOT build refused the object-lookup helper; see "
                  "rootbackend_error.log. Name the object through gDirectory or "
                  "gFile directly in the meantime.");
    return;
  }

  slot->clear();
  const std::string statement =
      "SphereBridge::last_result = SphereBridge::Run([&]{ return (" + command +
      "); });";

  // Each attempt is captured on its own: a failed first attempt must not put its
  // diagnostic in front of a successful second one.
  TInterpreter::EErrorCode error = TInterpreter::kNoError;
  std::string printed;
  {
    OutputCapture capture;
    try {
      (void)gInterpreter->ProcessLine(statement.c_str(), &error);
    } catch (...) {
      error = TInterpreter::kFatal;
    }
    printed = capture.stop();
  }

  if (error != TInterpreter::kNoError) {
    // Not an expression: run it as a statement, as a ROOT prompt would.
    TInterpreter::EErrorCode bare_error = TInterpreter::kNoError;
    std::string bare_printed;
    {
      OutputCapture capture;
      try {
        (void)gInterpreter->ProcessLine((command + ";").c_str(), &bare_error);
      } catch (...) {
        bare_error = TInterpreter::kFatal;
      }
      bare_printed = capture.stop();
    }

    if (bare_error != TInterpreter::kNoError) {
      const std::string &diagnostic = bare_printed.empty() ? printed : bare_printed;
      send_response(shm, pkt.job_id, pkt.req_id,
                    diagnostic.empty()
                        ? "ERROR: interpreter refused: " + command
                        : "ERROR: " + diagnostic);
      return;
    }
    send_response(shm, pkt.job_id, pkt.req_id,
                  bare_printed.empty() ? "OK" : bare_printed);
    return;
  }

  // What the expression printed, then what it evaluated to.
  const std::string value = slot->empty() ? std::string("OK") : *slot;
  if (printed.empty()) {
    send_response(shm, pkt.job_id, pkt.req_id, value);
    return;
  }
  send_response(shm, pkt.job_id, pkt.req_id,
                (value == "OK") ? printed : (printed + "\n" + value));
}

void handle_noop(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  send_response(shm, pkt.job_id, pkt.req_id, "PONG");
}

void handle_version(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  send_response(
      shm, pkt.job_id, pkt.req_id,
      AdvancedRootConfigCache::instance().get(SystemOpCode::GetRootVersion));
}

void handle_uptime(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  const auto now = std::chrono::steady_clock::now();
  const auto uptime_sec = std::chrono::duration_cast<std::chrono::seconds>(
                              now - g_engine_start_time)
                              .count();
  send_response(shm, pkt.job_id, pkt.req_id,
                "uptime_seconds=" + std::to_string(uptime_sec));
}

void handle_system(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context) {
  (void)context;
  SystemCommandDispatcher::instance().dispatch(shm, pkt);
}

// Installs the handlers above into the process-wide CommandRegistry.
void warm_up() {
  (void)AdvancedRootConfigCache::instance();
  
  if (interpreter_result_slot() != nullptr && gInterpreter != nullptr) {
    (void)object_lookup_ready();
    OutputCapture capture;
    TInterpreter::EErrorCode error = TInterpreter::kNoError;
    (void)gInterpreter->ProcessLine(
        "SphereBridge::last_result = "
        "SphereBridge::Run([&]{ return (gROOT->GetVersion()); });",
        &error);
    (void)capture.stop();
  }
}

void register_all() {
  auto &registry = CommandRegistry::instance();
  registry.register_command(Proto::PacketType::CMD_PING, &handle_noop);
  registry.register_command(Proto::PacketType::CMD_SYS_NOOP, &handle_noop);
  registry.register_command(Proto::PacketType::CMD_SYS_VERSION, &handle_version);
  registry.register_command(Proto::PacketType::CMD_SYS_UPTIME, &handle_uptime);
  registry.register_command(Proto::PacketType::CMD_SYS_CONFIG, &handle_system);
  registry.register_command(Proto::PacketType::CMD_CLING_EXEC, &handle_cling_exec);
}

} // namespace sys
} // namespace cmd
} // namespace Sphere
