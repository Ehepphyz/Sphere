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
#include <cstdio>
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

/**
 * The name each root-config key answers to, and the opcode it selects.
 *
 * The sub-opcode used to be read from the packet's flags word alone. Nothing on
 * the Java side ever wrote that word, so every request landed on opcode 0 and
 * the twenty-seven other entries were unreachable. Naming them in the payload
 * costs nothing on the wire and needs no mirrored enum.
 */
struct SystemOpCodeName {
  std::string_view name;
  SystemOpCode code;
};

constexpr std::array<SystemOpCodeName, 27> kSystemOpCodeNames{{
    {"version", SystemOpCode::GetRootVersion},
    {"incdir", SystemOpCode::GetIncDir},
    {"libdir", SystemOpCode::GetLibDir},
    {"features", SystemOpCode::GetFeatures},
    {"cflags", SystemOpCode::GetCflags},
    {"libs", SystemOpCode::GetLibs},
    {"metrics", SystemOpCode::GetMetrics},
    {"prefix", SystemOpCode::GetPrefix},
    {"exec-prefix", SystemOpCode::GetExecPrefix},
    {"auxcflags", SystemOpCode::GetAuxCflags},
    {"ldflags", SystemOpCode::GetLdFlags},
    {"glibs", SystemOpCode::GetGlibs},
    {"evelibs", SystemOpCode::GetEveLibs},
    {"bindir", SystemOpCode::GetBinDir},
    {"etcdir", SystemOpCode::GetEtcDir},
    {"tutdir", SystemOpCode::GetTutDir},
    {"srcdir", SystemOpCode::GetSrcDir},
    {"arch", SystemOpCode::GetArch},
    {"platform", SystemOpCode::GetPlatform},
    {"config", SystemOpCode::GetConfig},
    {"ncpu", SystemOpCode::GetNcpu},
    {"git-revision", SystemOpCode::GetGitRevision},
    {"python-version", SystemOpCode::GetPythonVersion},
    {"cxx-standard", SystemOpCode::GetCxxStandard},
    {"cc", SystemOpCode::GetCc},
    {"cxx", SystemOpCode::GetCxx},
    {"ld", SystemOpCode::GetLd},
}};

/// The key a request names, or Count when the name matches none.
[[nodiscard]] SystemOpCode
system_opcode_from_name(std::string_view request) noexcept {
  std::string key;
  key.reserve(request.size());
  for (const char c : request) {
    if (c == '_') {
      key.push_back('-');
    } else if (!std::isspace(static_cast<unsigned char>(c))) {
      key.push_back(
          static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
    }
  }
  // "--cflags" is what one types at a root-config prompt.
  while (!key.empty() && key.front() == '-') {
    key.erase(key.begin());
  }
  for (const SystemOpCodeName &entry : kSystemOpCodeNames) {
    if (entry.name == key) {
      return entry.code;
    }
  }
  return SystemOpCode::Count;
}

[[nodiscard]] std::string system_opcode_name_list() {
  std::string out;
  for (const SystemOpCodeName &entry : kSystemOpCodeNames) {
    if (!out.empty()) {
      out += " ";
    }
    out.append(entry.name);
  }
  return out;
}

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
    if (index >= cache_.size()) {
      return "ERROR: Configuration key invalid";
    }
    // Empty is the right answer for ldflags and auxcflags on most builds, so
    // only a key outside the table is an error.
    return cache_[index].empty() ? std::string_view("(empty)")
                                 : std::string_view(cache_[index]);
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
  if (writer.offset() >= SHM_MAX_ADDRESSABLE || payload_len + 1 > 0xFFFFFFFFULL) {
    get_telemetry().shm_allocation_failures.fetch_add(1,
                                                      std::memory_order_relaxed);
    return;
  }
  shm_ref_set_byte_offset(msg.shm_ref, writer.offset());
  msg.shm_ref.generation = writer.handle().generation;
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

// -----------------------------------------------------------------------------
// What the engine knows about itself
// -----------------------------------------------------------------------------

[[nodiscard]] const char *engine_state_name(std::uint32_t state) noexcept {
  switch (static_cast<EngineState>(state)) {
  case EngineState::UNINITIALIZED: return "uninitialized";
  case EngineState::INITIALIZING:  return "initializing";
  case EngineState::READY:         return "ready";
  case EngineState::RUNNING:       return "running";
  case EngineState::DEGRADED:      return "degraded";
  case EngineState::STOPPING:      return "stopping";
  case EngineState::STOPPED:       return "stopped";
  case EngineState::RECOVERY:      return "recovery";
  case EngineState::CORRUPTED:     return "corrupted";
  case EngineState::ERROR:         return "error";
  }
  return "unknown";
}

/// Views a metrics request can ask for.
enum class MetricsView { Text, Json, Engine, Memory, Heap, Reset };

[[nodiscard]] MetricsView metrics_view(std::string_view request) noexcept {
  if (request == "json")   { return MetricsView::Json; }
  if (request == "engine") { return MetricsView::Engine; }
  if (request == "memory") { return MetricsView::Memory; }
  if (request == "heap")   { return MetricsView::Heap; }
  if (request == "reset")  { return MetricsView::Reset; }
  return MetricsView::Text;
}

/// Resident and virtual size of this process, in KB, as ROOT reports them.
struct ProcessFootprint {
  long resident_kb{0};
  long virtual_kb{0};
  double cpu_user_s{0.0};
  double cpu_sys_s{0.0};
  bool known{false};
};

[[nodiscard]] ProcessFootprint process_footprint() {
  ProcessFootprint out;
  if (gSystem == nullptr) {
    return out;
  }
  ProcInfo_t info;
  if (gSystem->GetProcInfo(&info) != 0) {
    return out;
  }
  out.resident_kb = static_cast<long>(info.fMemResident);
  out.virtual_kb = static_cast<long>(info.fMemVirtual);
  out.cpu_user_s = static_cast<double>(info.fCpuUser);
  out.cpu_sys_s = static_cast<double>(info.fCpuSys);
  out.known = true;
  return out;
}

/// One "name=value" pair per line, or one JSON object, depending on the view.
class Report {
public:
  explicit Report(bool as_json) : json_(as_json) {}

  void section(const char *name) {
    if (!json_) {
      if (!text_.empty()) {
        text_ += "\n";
      }
      text_ += "[";
      text_ += name;
      text_ += "]\n";
    }
  }

  void add(const char *key, std::uint64_t value) {
    write(key, std::to_string(value), false);
  }

  void add(const char *key, const std::string &value) {
    write(key, value, true);
  }

  void add(const char *key, double value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%.3f", value);
    write(key, std::string(buffer), false);
  }

  [[nodiscard]] std::string take() {
    return json_ ? "{" + body_ + "}" : text_;
  }

private:
  void write(const char *key, const std::string &value, bool quote) {
    if (json_) {
      if (!body_.empty()) {
        body_ += ",";
      }
      body_ += "\"";
      body_ += key;
      body_ += "\":";
      body_ += quote ? "\"" + value + "\"" : value;
      return;
    }
    text_ += "  ";
    text_ += key;
    text_ += " = ";
    text_ += value;
    text_ += "\n";
  }

  bool json_;
  std::string text_;
  std::string body_;
};

/**
 * One word on whether the engine is well, and why when it is not.
 *
 * Reading a column of counters to find out takes a habit the user should not
 * need; the conditions that matter are known here, so they are named here.
 */
[[nodiscard]] std::string engine_health(const ShmLayout &shm,
                                        const SystemTelemetry &tel) {
  if (shm.header != nullptr) {
    const auto state = static_cast<EngineState>(shm.header->state.load());
    if (state == EngineState::CORRUPTED || state == EngineState::ERROR) {
      return std::string("failed, state ") +
             engine_state_name(shm.header->state.load());
    }
    if (state == EngineState::DEGRADED) {
      return "degraded";
    }
  }
  const std::uint64_t failures = tel.shm_allocation_failures.load();
  if (failures != 0) {
    return "short of heap, " + std::to_string(failures) + " refused";
  }
  if (shm.stats != nullptr && shm.stats->crc_failures_total.load() != 0) {
    return "checksum failures, " +
           std::to_string(shm.stats->crc_failures_total.load());
  }
  if (shm.cmd_ring != nullptr && shm.cmd_ring->occupancy() > 0.80f) {
    return "commands backing up";
  }
  if (shm.evt_ring != nullptr && shm.evt_ring->occupancy() > 0.80f) {
    return "answers backing up, nothing is draining them";
  }
  if (tel.invalid_opcodes.load() != 0) {
    return "answering, " + std::to_string(tel.invalid_opcodes.load()) +
           " requests refused";
  }
  return "answering";
}

/**
 * The engine's own figures, in the requested view.
 *
 * Every number here is read from this process or from the region it owns. The
 * commands that report on the engine used to ask ROOT instead, which knows
 * nothing about any of it.
 */
[[nodiscard]] std::string build_metrics_report(ShmLayout &shm,
                                               MetricsView view) {
  auto &tel = get_telemetry();

  if (view == MetricsView::Reset) {
    tel.requests_processed.store(0, std::memory_order_relaxed);
    tel.shm_allocation_failures.store(0, std::memory_order_relaxed);
    tel.invalid_opcodes.store(0, std::memory_order_relaxed);
    if (shm.stats != nullptr) {
      shm.stats->max_job_latency_ns.store(0, std::memory_order_relaxed);
      shm.stats->crc_failures_total.store(0, std::memory_order_relaxed);
    }
    if (shm.header != nullptr) {
      shm.header->last_error_code.store(0, std::memory_order_relaxed);
    }
    return "counters cleared";
  }

  const bool json = (view == MetricsView::Json);
  const bool all = (view == MetricsView::Text) || json;
  Report report(json);

  if (all || view == MetricsView::Engine) {
    const auto now = std::chrono::steady_clock::now();
    const std::uint64_t uptime =
        static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::seconds>(
                now - g_engine_start_time)
                .count());
    report.section("engine");
    // The verdict first: a page of counters is not an answer on its own.
    report.add("health", engine_health(shm, tel));
    report.add("uptime_seconds", uptime);
    report.add("requests_per_second",
               (uptime == 0)
                   ? 0.0
                   : static_cast<double>(tel.requests_processed.load()) /
                         static_cast<double>(uptime));
    if (shm.header != nullptr) {
      report.add("state",
                 std::string(engine_state_name(shm.header->state.load())));
      report.add("last_error_code",
                 static_cast<std::uint64_t>(shm.header->last_error_code.load()));
      report.add("heartbeat_cpp", shm.header->heartbeat_cpp.load());
      report.add("heartbeat_java", shm.header->heartbeat_java.load());
      report.add("cycles", shm.header->engine_cycles.load());
      report.add("jobs_completed", shm.header->jobs_completed.load());
      report.add("jobs_failed", shm.header->jobs_failed.load());
    }
    if (shm.stats != nullptr) {
      report.add("jobs_inflight", shm.stats->jobs_inflight.load());
      report.add("latency_last_ns", shm.stats->last_job_latency_ns.load());
      report.add("latency_avg_ns", shm.stats->avg_job_latency_ns.load());
      report.add("latency_max_ns", shm.stats->max_job_latency_ns.load());
    }
    report.add("requests_processed", tel.requests_processed.load());
    report.add("alloc_failures", tel.shm_allocation_failures.load());
    report.add("invalid_opcodes", tel.invalid_opcodes.load());
    if (shm.stats != nullptr) {
      report.add("crc_failures", shm.stats->crc_failures_total.load());
    }
  }

  if (all) {
    report.section("rings");
    if (shm.cmd_ring != nullptr) {
      report.add("cmd_queued", shm.cmd_ring->size_approx());
      report.add("cmd_capacity", shm.cmd_ring->capacity());
      report.add("cmd_percent_full",
                 static_cast<double>(shm.cmd_ring->occupancy()) * 100.0);
    }
    if (shm.evt_ring != nullptr) {
      report.add("evt_queued", shm.evt_ring->size_approx());
      report.add("evt_capacity", shm.evt_ring->capacity());
      report.add("evt_percent_full",
                 static_cast<double>(shm.evt_ring->occupancy()) * 100.0);
    }
    if (shm.header != nullptr) {
      report.add("raw_occupancy",
                 static_cast<double>(shm.header->raw_occupancy.load()));
      report.add("predicted_pressure",
                 static_cast<double>(shm.header->predicted_pressure.load()));
      report.add("control_loop_jitter_ns",
                 shm.header->control_loop_jitter_ns.load());
    }
  }

  if (all || view == MetricsView::Heap) {
    report.section("heap");
    const ShmHeapHeader *heap =
        (shm.data_heap != nullptr) ? get_heap_header(shm) : nullptr;
    if (heap != nullptr) {
      report.add("allocated_bytes", heap->allocated_bytes.load());
      report.add("total_capacity", heap->total_capacity.load());
      report.add("active_allocations", heap->active_allocations.load());
      report.add("reclaimable_bytes", heap->reclaimable_bytes.load());
    }
    const ShmHeapRoot *root =
        (shm.data_heap != nullptr) ? get_heap_root(shm) : nullptr;
    if (root != nullptr) {
      report.add("chunks", static_cast<std::uint64_t>(root->n_chunks.load()));
      report.add("recycled_total", root->recycled_total.load());
      report.add("carved_total", root->carved_total.load());
      report.add("stale_rejected", root->stale_rejected.load());
    }
    if (shm.header != nullptr) {
      report.add("fragmentation_score",
                 static_cast<std::uint64_t>(
                     shm.header->heap_fragmentation_score.load()));
    }
  }

  if (all || view == MetricsView::Memory) {
    report.section("process");
    const ProcessFootprint footprint = process_footprint();
    if (footprint.known) {
      report.add("resident_kb",
                 static_cast<std::uint64_t>(footprint.resident_kb));
      report.add("virtual_kb",
                 static_cast<std::uint64_t>(footprint.virtual_kb));
      report.add("cpu_user_seconds", footprint.cpu_user_s);
      report.add("cpu_system_seconds", footprint.cpu_sys_s);
    }
    if (shm.header != nullptr) {
      report.add("region_bytes", shm.header->total_size);
      report.add("region_version",
                 static_cast<std::uint64_t>(shm.header->version.load()));
    }
    report.add("simd_alignment", static_cast<std::uint64_t>(SIMD_ALIGNMENT));
  }

  return report.take();
}

// Runtime Telemetry Status Reporter
inline void handle_metrics(ShmLayout &shm,
                           const Proto::PacketHeader &pkt) {
  send_response(shm, pkt.job_id, pkt.req_id,
                build_metrics_report(shm, MetricsView::Text));
}

// Static O(1) Dispatch Table Registry
class SystemCommandDispatcher {
public:
  static SystemCommandDispatcher &instance() {
    static SystemCommandDispatcher dispatcher;
    return dispatcher;
  }

  /// Runs one key, already resolved by name.
  void dispatch(ShmLayout &shm, const Proto::PacketHeader &pkt,
                SystemOpCode code) const {
    table_[static_cast<std::size_t>(code)](shm, pkt);
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
        // gDirectory is an adapter since 6.36, and comparing it to nullptr is
        // ambiguous. Binding it to a plain pointer first works on every release.
        "    TDirectory *here = gDirectory;\n"
        "    if (found == nullptr && here != nullptr) {\n"
        "      found = here->Get(name);\n"
        "    }\n"
        "    TFile *current = gFile;\n"
        "    if (found == nullptr && current != nullptr) {\n"
        "      found = current->Get(name);\n"
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

/**
 * Objects the interpreter keeps between two commands, addressed by name.
 *
 * A data frame, a workspace, a factory, a connection or a socket is not a named
 * TObject, so Need<T> cannot find it, and a command that built one left nothing
 * behind for the next command to use. This table is what a name now resolves
 * to. It holds only <map> and <string>, so no ROOT class is named here: the
 * type appears at the call site, and a class a build does not carry fails that
 * one command rather than this block.
 */
bool handles_ready() {
  static const bool ready = (gInterpreter != nullptr) &&
      declare_block("named handles",
        "#include <map>\n"
        "#include <stdexcept>\n"
        "#include <string>\n"
        "#include <typeinfo>\n"
        "namespace SphereBridge {\n"
        "  struct Handle {\n"
        "    void *ptr;\n"
        "    std::string type;   // typeid, which is what a lookup is checked on\n"
        "    std::string shown;  // the same type as it is written in a command\n"
        "    void (*drop)(void *);\n"
        "  };\n"
        "  inline std::map<std::string, Handle> &Handles() {\n"
        "    static std::map<std::string, Handle> table;\n"
        "    return table;\n"
        "  }\n"
        "  inline std::string Bound() {\n"
        "    if (Handles().empty()) { return \"nothing\"; }\n"
        "    std::string out;\n"
        "    for (std::map<std::string, Handle>::const_iterator it =\n"
        "             Handles().begin(); it != Handles().end(); ++it) {\n"
        "      if (!out.empty()) { out += \", \"; }\n"
        "      out += it->first;\n"
        "    }\n"
        "    return out;\n"
        "  }\n"
        "  template <typename T> void Release(void *p) { delete (T *)p; }\n"
        // Binding a name a second time frees what it held, so a chain of
        // commands on one name does not pile up objects nobody can reach.
        "  template <typename T>\n"
        "  std::string Keep(const char *name, T *ptr, const char *shown) {\n"
        "    if (ptr == nullptr) {\n"
        "      return std::string(\"ERROR: nothing to bind to '\") + name + \"'\";\n"
        "    }\n"
        "    std::map<std::string, Handle>::iterator it = Handles().find(name);\n"
        "    if (it != Handles().end() && it->second.drop != 0 &&\n"
        "        it->second.ptr != (void *)ptr) {\n"
        "      it->second.drop(it->second.ptr);\n"
        "    }\n"
        "    Handle bound;\n"
        "    bound.ptr = (void *)ptr;\n"
        "    bound.type = typeid(T).name();\n"
        "    bound.shown = shown;\n"
        "    bound.drop = &Release<T>;\n"
        "    Handles()[name] = bound;\n"
        "    return std::string(name) + \" -> \" + shown;\n"
        "  }\n"
        // A name bound to the wrong kind of object is the mistake worth naming,
        // so the refusal says what it does hold rather than only what it is not.
        "  template <typename T> T *Held(const char *name) {\n"
        "    std::map<std::string, Handle>::iterator it = Handles().find(name);\n"
        "    if (it == Handles().end()) {\n"
        "      throw std::runtime_error(std::string(\"no handle called '\") +\n"
        "          name + \"'. Bound: \" + Bound() + \".\");\n"
        "    }\n"
        "    if (it->second.type != typeid(T).name()) {\n"
        "      throw std::runtime_error(std::string(\"handle '\") + name +\n"
        "          \"' holds a \" + it->second.shown + \".\");\n"
        "    }\n"
        "    return (T *)it->second.ptr;\n"
        "  }\n"
        "  inline std::string HandleList() {\n"
        "    if (Handles().empty()) { return \"no handle bound\"; }\n"
        "    std::string out;\n"
        "    for (std::map<std::string, Handle>::const_iterator it =\n"
        "             Handles().begin(); it != Handles().end(); ++it) {\n"
        "      if (!out.empty()) { out += \"\\n\"; }\n"
        "      out += it->first;\n"
        "      out += std::string(12 > it->first.size()\n"
        "                             ? 12 - it->first.size() : 1, ' ');\n"
        "      out += it->second.shown;\n"
        "    }\n"
        "    return out;\n"
        "  }\n"
        "  inline std::string HandleDrop(const char *name) {\n"
        "    std::map<std::string, Handle>::iterator it = Handles().find(name);\n"
        "    if (it == Handles().end()) {\n"
        "      return std::string(\"no handle called '\") + name + \"'\";\n"
        "    }\n"
        "    if (it->second.drop != 0) { it->second.drop(it->second.ptr); }\n"
        "    Handles().erase(it);\n"
        "    return std::string(name) + \" dropped\";\n"
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

  if (command.find("SphereBridge::Keep<") != std::string::npos ||
      command.find("SphereBridge::Held<") != std::string::npos ||
      command.find("SphereBridge::Handle") != std::string::npos) {
    if (!handles_ready()) {
      send_response(shm, pkt.job_id, pkt.req_id,
                    "ERROR: this ROOT build refused the named-handle table; see "
                    "rootbackend_error.log.");
      return;
    }
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

  // The key travels in the payload. A request that carries none is answered
  // with the list, which is more use than the flags word it used to fall on.
  const std::string request = read_payload(shm, pkt);
  if (request.empty()) {
    if ((pkt.flags & 0xFFFF) != 0) {
      SystemCommandDispatcher::instance().dispatch(shm, pkt);
      return;
    }
    send_response(shm, pkt.job_id, pkt.req_id,
                  "keys: " + system_opcode_name_list());
    return;
  }

  const SystemOpCode code = system_opcode_from_name(request);
  if (code == SystemOpCode::Count) {
    get_telemetry().invalid_opcodes.fetch_add(1, std::memory_order_relaxed);
    send_response(shm, pkt.job_id, pkt.req_id,
                  "ERROR: no root-config key called '" + request +
                      "'. keys: " + system_opcode_name_list());
    return;
  }
  SystemCommandDispatcher::instance().dispatch(shm, pkt, code);
}

void handle_sys_metrics(ShmLayout &shm, const Proto::PacketHeader &pkt,
                        void *context) {
  (void)context;
  send_response(shm, pkt.job_id, pkt.req_id,
                build_metrics_report(shm, metrics_view(read_payload(shm, pkt))));
}

/**
 * ROOT's implicit multithreading, on or off for the whole engine.
 *
 * RDataFrame, the tree readers and the RNTuple readers all take their parallelism
 * from this one switch, and nothing in Sphere could reach it.
 */
void handle_sys_threads(ShmLayout &shm, const Proto::PacketHeader &pkt,
                        void *context) {
  (void)context;

  const std::string request = read_payload(shm, pkt);
  const std::string verb = request.substr(0, request.find(' '));

  auto report = [&]() {
    const bool on = ROOT::IsImplicitMTEnabled();
    return std::string("implicit multithreading ") + (on ? "on" : "off") +
           ", pool " + std::to_string(ROOT::GetThreadPoolSize()) + " threads";
  };

  if (verb == "off") {
    ROOT::DisableImplicitMT();
    send_response(shm, pkt.job_id, pkt.req_id, report());
    return;
  }

  if (verb == "on") {
    // Zero means "as many threads as the machine has", which is ROOT's own
    // default and the right answer when the user names no number.
    unsigned int threads = 0;
    const std::size_t at = request.find(' ');
    if (at != std::string::npos) {
      const std::string rest = request.substr(at + 1);
      char *end = nullptr;
      const long asked = std::strtol(rest.c_str(), &end, 10);
      if (end != rest.c_str() && asked > 0 && asked < 4096) {
        threads = static_cast<unsigned int>(asked);
      }
    }
    // Turning it on twice is ignored by ROOT, so the count would not change.
    if (ROOT::IsImplicitMTEnabled()) {
      ROOT::DisableImplicitMT();
    }
    ROOT::EnableImplicitMT(threads);
    send_response(shm, pkt.job_id, pkt.req_id, report());
    return;
  }

  if (verb.empty() || verb == "status") {
    send_response(shm, pkt.job_id, pkt.req_id, report());
    return;
  }

  get_telemetry().invalid_opcodes.fetch_add(1, std::memory_order_relaxed);
  send_response(shm, pkt.job_id, pkt.req_id,
                "ERROR: say on, on <n>, off or status");
}

// Installs the handlers above into the process-wide CommandRegistry.
void warm_up() {
  (void)AdvancedRootConfigCache::instance();
  // Instantiating Run()/ToText() here costs the autoparse once, on the main
  // thread, instead of on the first client command. Captured so the interpreter's
  // own chatter stays out of the engine log.
  if (interpreter_result_slot() != nullptr && gInterpreter != nullptr) {
    (void)object_lookup_ready();
    (void)handles_ready();
    OutputCapture capture;
    TInterpreter::EErrorCode error = TInterpreter::kNoError;
    (void)gInterpreter->ProcessLine(
        "SphereBridge::last_result = "
        "SphereBridge::Run([&]{ return (gROOT->GetVersion()); });",
        &error);
    (void)capture.stop();
  }
}

void handle_release_chunk(ShmLayout &shm, const Proto::PacketHeader &pkt,
                          void *context) {
  (void)context;
  // The engine answers this opcode itself, where the generation is still in
  // reach. This entry stays for the older form that puts the byte offset in
  // job_id and carries no generation to check.
  if (pkt.job_id != 0) {
    shm_heap_retire_chunk(shm, pkt.job_id);
  }
}

void register_all() {
  auto &registry = CommandRegistry::instance();
  registry.register_command(Proto::PacketType::CMD_RELEASE_CHUNK,
                            &handle_release_chunk);
  registry.register_command(Proto::PacketType::CMD_PING, &handle_noop);
  registry.register_command(Proto::PacketType::CMD_SYS_NOOP, &handle_noop);
  registry.register_command(Proto::PacketType::CMD_SYS_VERSION, &handle_version);
  registry.register_command(Proto::PacketType::CMD_SYS_UPTIME, &handle_uptime);
  registry.register_command(Proto::PacketType::CMD_SYS_CONFIG, &handle_system);
  registry.register_command(Proto::PacketType::CMD_SYS_METRICS,
                            &handle_sys_metrics);
  registry.register_command(Proto::PacketType::CMD_SYS_THREADS,
                            &handle_sys_threads);
  registry.register_command(Proto::PacketType::CMD_CLING_EXEC, &handle_cling_exec);
}

} // namespace sys
} // namespace cmd
} // namespace Sphere
