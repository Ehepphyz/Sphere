// cmd_system.cpp

#include "commands/cmd_system.h"
#include "engine.h"
#include "packets.h"
#include "shm_layout.h"

#include <ROOT/RConfig.hxx>
#include <RVersion.h>
#include <TROOT.h>
#include <TSystem.h>

#include <array>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <string_view>
#include <thread>

namespace Sphere {
namespace cmd {
namespace sys {

namespace {
// Engine launch timestamp for system uptime calculations
const auto g_engine_start_time = std::chrono::steady_clock::now();
} // namespace

// Opcode enumeration representing system commands (aligned with Java IPC layer
// protocol)
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

// Thread-safe runtime configuration cache populated ONCE at startup via CERN
// ROOT C++ API
class AdvancedRootConfigCache {
public:
  static AdvancedRootConfigCache &instance() {
    static AdvancedRootConfigCache cache;
    return cache;
  }

  // Zero-allocation lookup returning string_view for instant SIMD payload
  // serialization
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
        "imd",   "mathmore", "thread", "shared"};

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

    // Fallback default features if configuration string parsing yields no
    // matches
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

  BridgeMessage msg{};
  msg.type = static_cast<decltype(msg.type)>(Platform::PacketType::EVT_OK);

  msg.job_id = static_cast<std::uint32_t>(job_id);
  msg.req_id = static_cast<std::uint32_t>(req_id);
  msg.shm_ref.offset = writer.offset();
  msg.shm_ref.total_bytes = static_cast<std::uint32_t>(payload_len + 1);

  shm.evt_ring->push(msg);
  get_telemetry().requests_processed.fetch_add(1, std::memory_order_relaxed);
}

// Function pointer signature for system command dispatchers
using SystemCommandHandler = void (*)(ShmLayout &,
                                      const ::Platform::PacketHeader &);

template <SystemOpCode OpCode>
void handle_cached_config(ShmLayout &shm, const ::Platform::PacketHeader &pkt) {
  send_response(shm, pkt.job_id, pkt.req_id,
                AdvancedRootConfigCache::instance().get(OpCode));
}

// Runtime Telemetry Status Reporter
inline void handle_metrics(ShmLayout &shm,
                           const ::Platform::PacketHeader &pkt) {
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

  void dispatch(ShmLayout &shm, const ::Platform::PacketHeader &pkt) const {
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
                                 const ::Platform::PacketHeader &pkt) {
    get_telemetry().invalid_opcodes.fetch_add(1, std::memory_order_relaxed);
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: Unsupported system opcode");
  }

  std::array<SystemCommandHandler,
             static_cast<std::size_t>(SystemOpCode::Count)>
      table_;
};

// Standalone functions declared in cmd_system.h
void handle_noop(ShmLayout &shm, const ::Platform::PacketHeader &pkt) {
  send_response(shm, pkt.job_id, pkt.req_id, "PONG");
}

void handle_version(ShmLayout &shm, const ::Platform::PacketHeader &pkt) {
  send_response(
      shm, pkt.job_id, pkt.req_id,
      AdvancedRootConfigCache::instance().get(SystemOpCode::GetRootVersion));
}

void handle_uptime(ShmLayout &shm, const ::Platform::PacketHeader &pkt) {
  const auto now = std::chrono::steady_clock::now();
  const auto uptime_sec = std::chrono::duration_cast<std::chrono::seconds>(
                              now - g_engine_start_time)
                              .count();
  send_response(shm, pkt.job_id, pkt.req_id,
                "uptime_seconds=" + std::to_string(uptime_sec));
}

void handle_system(ShmLayout &shm, const ::Platform::PacketHeader &pkt) {
  SystemCommandDispatcher::instance().dispatch(shm, pkt);
}

} // namespace sys
} // namespace cmd
} // namespace Sphere
