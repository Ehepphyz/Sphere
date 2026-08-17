// main.cpp
// High-performance asynchronous ingestion engine for CERN ROOT dataset
// partitions.

#include "AsyncFileLoader_io_uring.h"
#include "DecompressorPool.h"
#include "RootBatchLoaderMultiFile.h"
#include "commands/cmd_file.h"
#include "commands/cmd_system.h"
#include "lockfree_ring.h"
#include "packets.h"
#include "platform.h"
#include "shm_layout.h"

// ROOT Version Macros for Multi-Version API Compatibility
#include <RVersion.h>

#if ROOT_VERSION_CODE < ROOT_VERSION(6, 34, 0)
#include <ROOT/RColumnModel.hxx>
#else
#include <ROOT/RField.hxx>
#include <ROOT/RNTupleModel.hxx>
#endif

#include <ROOT/RNTuple.hxx>
#include <ROOT/RNTupleReader.hxx>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

// Hardware CPU Relax Intrinsic Selection
#if defined(__x86_64__) || defined(_M_X64)
#include <emmintrin.h>
#endif

// Platform-Specific Thread Affinity & POSIX Headers
#if defined(SPHERE_OS_LINUX)
#include <pthread.h>
#include <sched.h>
#include <sys/wait.h>
#include <unistd.h>
#elif defined(SPHERE_OS_MACOS)
#include <mach/thread_act.h>
#include <mach/thread_policy.h>
#include <pthread.h>
#include <sys/wait.h>
#include <unistd.h>
#elif defined(SPHERE_OS_WINDOWS)
#include <windows.h>
#endif

namespace {

using SHMContext = Sphere::ShmLayout;

// Exact aliases aligned with global namespace Platform declared in packets.h
using ::Platform::PacketHeader;
using ::Platform::PacketType;

std::atomic<bool> g_shutdown{false};

struct alignas(64) HighPerfMetrics {
  std::atomic<std::size_t> total_batches{0};
  std::atomic<std::size_t> total_bytes_read{0};
  std::atomic<std::size_t> total_bytes_decompressed{0};
  std::atomic<std::size_t> commands_processed{0};
  std::atomic<std::size_t> high_ticks{0};
  std::atomic<std::size_t> low_ticks{0};
  std::atomic<std::size_t> normal_ticks{0};
  std::atomic<float> max_bp{0.0f};
  std::atomic<float> min_bp{1.0f};

  void update_max_bp(float val) noexcept {
    float current = max_bp.load(std::memory_order_relaxed);
    while (val > current && !max_bp.compare_exchange_weak(
                                current, val, std::memory_order_relaxed,
                                std::memory_order_relaxed)) {
    }
  }

  void update_min_bp(float val) noexcept {
    float current = min_bp.load(std::memory_order_relaxed);
    while (val < current && !min_bp.compare_exchange_weak(
                                current, val, std::memory_order_relaxed,
                                std::memory_order_relaxed)) {
    }
  }
};

constexpr std::size_t CLI_CMD_BUFFER_SIZE = 16;
enum class CliCommand { None, Status, StatusPlus, Ping, Restart, Quit };

// Lock-Free Single-Producer Single-Consumer (SPSC) Queue for CLI Commands
struct LockFreeCliQueue {
  std::array<CliCommand, CLI_CMD_BUFFER_SIZE> buffer{};
  alignas(64) std::atomic<std::size_t> head{0};
  alignas(64) std::atomic<std::size_t> tail{0};

  bool push(CliCommand cmd) noexcept {
    const auto current_tail = tail.load(std::memory_order_relaxed);
    const auto next_tail = (current_tail + 1) & (CLI_CMD_BUFFER_SIZE - 1);
    if (next_tail == head.load(std::memory_order_acquire)) {
      return false; // Queue buffer overflow
    }
    buffer[current_tail] = cmd;
    tail.store(next_tail, std::memory_order_release);
    return true;
  }

  CliCommand pop() noexcept {
    const auto current_head = head.load(std::memory_order_relaxed);
    if (current_head == tail.load(std::memory_order_acquire)) {
      return CliCommand::None; // Queue is empty
    }
    const CliCommand cmd = buffer[current_head];
    head.store((current_head + 1) & (CLI_CMD_BUFFER_SIZE - 1),
               std::memory_order_release);
    return cmd;
  }
};

LockFreeCliQueue g_cli_queue;

// Hash functor for PacketType enum class keying in std::unordered_map
struct PacketTypeHash {
  std::size_t operator()(PacketType type) const noexcept {
    return static_cast<std::size_t>(type);
  }
};

// Command Handler Function Signature matching cmd_file.h / cmd_system.h
using CommandHandler = void (*)(SHMContext &, const PacketHeader &);

// Command Dispatch Registry
class CommandRegistry {
public:
  static CommandRegistry &instance() {
    static CommandRegistry reg;
    return reg;
  }

  void register_cmd(PacketType type, CommandHandler handler) {
    handlers_[type] = handler;
  }

  bool dispatch(const PacketHeader &pkt, SHMContext &shm) const {
    const auto it = handlers_.find(pkt.type);
    if (it != handlers_.cend() && it->second) {
      it->second(shm, pkt);
      return true;
    }
    return false;
  }

private:
  std::unordered_map<PacketType, CommandHandler, PacketTypeHash> handlers_;
};

// Default fallback ping handler for testing
void handle_default_ping(SHMContext &shm, const PacketHeader &pkt) {
  (void)shm;
  (void)pkt;
  std::cout << "[IPC] Ping handler executed (PONG)\n";
}

// Register command handlers defined in file and system modules
void register_all_commands() {
  auto &reg = CommandRegistry::instance();

  // 1. File management commands with standard [IPC] prefix
  reg.register_cmd(PacketType::CMD_OPEN_FILE, Sphere::cmd::file::handle_open);
  reg.register_cmd(PacketType::CMD_CLOSE_FILE, Sphere::cmd::file::handle_close);
  reg.register_cmd(PacketType::CMD_CLOSE_ALL_FILES,
                   Sphere::cmd::file::handle_close_all);
  reg.register_cmd(PacketType::CMD_SAVE_FILE, Sphere::cmd::file::handle_save);
  reg.register_cmd(PacketType::CMD_PING, handle_default_ping);

  // 2. Simple system commands (unlabeled output, no [IPC] prefix)
  namespace sys = Sphere::cmd::sys;
  reg.register_cmd(PacketType::CMD_SYS_NOOP, sys::handle_noop);
  reg.register_cmd(PacketType::CMD_SYS_VERSION, sys::handle_version);
  reg.register_cmd(PacketType::CMD_SYS_UPTIME, sys::handle_uptime);
}

// Helper method to safely pull packets from lock-free ring
inline bool pop_ring_command(Sphere::ShmLayout &shm,
                             ::Platform::PacketHeader &pkt) {
  if (!shm.cmd_ring) {
    return false;
  }

  Sphere::BridgeMessage msg;
  if (!shm.cmd_ring->pop(msg)) {
    return false;
  }
  static_assert(sizeof(msg) >= sizeof(::Platform::PacketHeader),
                "BridgeMessage buffer size smaller than PacketHeader size");
  std::memcpy(&pkt, &msg, sizeof(::Platform::PacketHeader));
  return true;
}

// Check and process incoming IPC commands from Shared Memory Ring
void process_shm_ring_commands(SHMContext &shm, HighPerfMetrics &metrics) {
  PacketHeader pkt{};

  // Process all available packets in the shared memory ring buffer
  while (pop_ring_command(shm, pkt)) {
    metrics.commands_processed.fetch_add(1, std::memory_order_relaxed);

    if (!CommandRegistry::instance().dispatch(pkt, shm)) {
      std::cerr << "[IPC] Warning: Unhandled packet type ("
                << static_cast<std::uint16_t>(pkt.type) << ")\n";
    }
  }
}

// Cross-Platform Thread Affinity Binding
void set_thread_affinity(int core_id) {
#if defined(SPHERE_OS_LINUX)
  cpu_set_t cpuset;
  CPU_ZERO(&cpuset);
  CPU_SET(core_id, &cpuset);
  pthread_t current_thread = pthread_self();
  if (pthread_setaffinity_np(current_thread, sizeof(cpu_set_t), &cpuset) != 0) {
    std::cerr << "[Main] Warning: Failed to set CPU affinity to core "
              << core_id << "\n";
  } else {
    std::cout << "[Main] Thread successfully pinned to Linux CPU core "
              << core_id << "\n";
  }

#elif defined(SPHERE_OS_WINDOWS)
  DWORD_PTR affinity_mask = static_cast<DWORD_PTR>(1) << core_id;
  HANDLE thread_handle = GetCurrentThread();
  if (SetThreadAffinityMask(thread_handle, affinity_mask) == 0) {
    std::cerr
        << "[Main] Warning: Failed to set Windows thread affinity to core "
        << core_id << "\n";
  } else {
    std::cout << "[Main] Thread successfully pinned to Windows logical core "
              << core_id << "\n";
  }

#elif defined(SPHERE_OS_MACOS)
  thread_affinity_policy_data_t policy_data = {core_id};
  kern_return_t ret = thread_policy_set(
      pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY,
      reinterpret_cast<thread_policy_t>(&policy_data),
      THREAD_AFFINITY_POLICY_COUNT);
  if (ret != KERN_SUCCESS) {
    std::cerr << "[Main] Warning: Failed to set macOS thread affinity tag "
              << core_id << "\n";
  } else {
    std::cout
        << "[Main] Thread affinity tag successfully assigned to macOS thread ("
        << core_id << ")\n";
  }

#else
  (void)core_id;
  std::cerr
      << "[Main] Warning: Thread affinity is not supported on this platform.\n";
#endif
}

// Cross-Platform Low-Latency Spin Abstraction
inline void cpu_relax() noexcept {
#if defined(__x86_64__) || defined(_M_X64)
  _mm_pause(); // Intel/AMD hardware pause instruction
#elif defined(__aarch64__) || defined(_M_ARM64)
#if defined(SPHERE_OS_WINDOWS)
  YieldProcessor();
#else
  asm volatile("yield" ::: "memory"); // ARM64 hardware yield instruction
#endif
#else
  std::this_thread::yield();
#endif
}

void handle_signal(int signal) {
  (void)signal;
  if (g_shutdown.exchange(true, std::memory_order_release)) {
    std::cerr << "\n[Main] Forceful exit requested. Terminating immediately.\n";
    std::quick_exit(EXIT_FAILURE);
  }
}

void print_usage(const char *program_name) {
  std::cout << "Usage: " << program_name
            << " [options] <root_file_1.root> [root_file_2.root ...]\n"
            << "       " << program_name << " config [root-config options]\n"
            << "  --init-shm        : Pre-allocates and initializes the shared memory segment and exits.\n"
            << "  --ping            : Tests command registry Ping execution "
               "and exits.\n"
            << "  --ntuple <name>   : RNTuple dataset container name (default: "
               "'Events').\n"
            << "  --field <name>    : Target RNTuple field name to extract "
               "(default: 'pt').\n"
            << "  --core <id>       : Pin main loop execution to a specific "
               "CPU core.\n"
            << "  --shm-mb <size>   : Shared memory region size in MB "
               "(default: 512 MB).\n"
            << "  --threads <count> : Parallel decompression worker count "
               "(default: hardware concurrency).\n"
            << "  --quiet           : Suppresses periodic telemetry logs.\n"
            << "  --stats           : Prints a detailed performance summary on "
               "shutdown.\n"
            << "  --interactive     : Enables live CLI prompt commands "
               "(status, status+, ping, restart, quit).\n"
            << "  --json-log        : Outputs periodic telemetry in JSON Lines "
               "format.\n"
            << "  --scenario <type> : Runs workload scenarios: 'burst', "
               "'ramp', 'stress'.\n"
            << "  --watchdog        : Enables automatic engine restarts on "
               "runtime stall detection.\n";
}

} // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
#if defined(SPHERE_OS_LINUX) || defined(SPHERE_OS_MACOS)
    if (isatty(STDERR_FILENO)) {
      print_usage(argv[0]);
    }
#else
    print_usage(argv[0]);
#endif
    return EXIT_FAILURE;
  }

  // =========================================================================
  // 1. BYPASS IPC / IO_URING FOR CONFIGURATION COMMANDS
  // =========================================================================
  if (std::string_view(argv[1]) == "config") {
    std::string cmd = "root-config";
    for (int i = 2; i < argc; ++i) {
      cmd += " ";
      cmd += argv[i];
    }
    int ret = std::system(cmd.c_str());
#if defined(WEXITSTATUS)
    return WEXITSTATUS(ret);
#else
    return ret;
#endif
  }

  // =========================================================================
  // 2. CLI ARGUMENT PARSING & COMMAND HANDLING (IPC & io_uring)
  // =========================================================================
  bool quiet_mode = false;
  bool stats_mode = false;
  bool interactive_mode = false;
  bool json_log_mode = false;
  bool watchdog_mode = false;
  bool ping_mode = false;
  bool init_shm_mode = false;
  std::string scenario = "none";
  int cpu_core = -1;
  std::size_t shm_size_mb = 512;
  unsigned int worker_threads = std::thread::hardware_concurrency();
  std::string ntuple_name = "Events";
  std::string target_field = "pt";
  std::vector<std::string> filepaths;

  for (int i = 1; i < argc; ++i) {
    const std::string_view arg = argv[i];
    if (arg == "--init-shm")
      init_shm_mode = true;
    else if (arg == "--quiet")
      quiet_mode = true;
    else if (arg == "--stats")
      stats_mode = true;
    else if (arg == "--ping")
      ping_mode = true;
    else if (arg == "--interactive")
      interactive_mode = true;
    else if (arg == "--json-log")
      json_log_mode = true;
    else if (arg == "--watchdog")
      watchdog_mode = true;
    else if (arg == "--scenario" && i + 1 < argc) {
      scenario = argv[++i];
    } else if (arg == "--ntuple" && i + 1 < argc) {
      ntuple_name = argv[++i];
    } else if (arg == "--field" && i + 1 < argc) {
      target_field = argv[++i];
    } else if (arg == "--shm-mb" && i + 1 < argc) {
      shm_size_mb = static_cast<std::size_t>(std::atoi(argv[++i]));
    } else if (arg == "--threads" && i + 1 < argc) {
      worker_threads = static_cast<unsigned int>(std::atoi(argv[++i]));
    } else if (arg == "--core" && i + 1 < argc) {
      cpu_core = std::atoi(argv[++i]);
    } else if (arg == "help" || arg == "-h" || arg == "--help") {
      print_usage(argv[0]);
      return EXIT_SUCCESS;
    } else if (!arg.empty() && arg.front() != '-') {
      filepaths.emplace_back(arg);
    } else {
      std::cerr << "[Main] Unknown command-line parameter: " << arg << "\n";
      print_usage(argv[0]);
      return EXIT_FAILURE;
    }
  }

  if (cpu_core >= 0) {
    set_thread_affinity(cpu_core);
  }

  std::signal(SIGINT, handle_signal);
#if defined(SIGTERM)
  std::signal(SIGTERM, handle_signal);
#endif
#if defined(SIGPIPE)
  std::signal(SIGPIPE, SIG_IGN);
#endif

  // Register command handlers
  register_all_commands();

  // 1. Initialize Zero-Copy Shared Memory Region
  const std::size_t shm_bytes = shm_size_mb * 1024 * 1024;
  Sphere::Platform::ShmRegion shm =
      Sphere::Platform::shm_create("sphere_root_shm_buffer", shm_bytes);

  if (!shm.data()) {
    std::cerr << "[Main] Error: Failed to allocate shared memory region.\n";
    return EXIT_FAILURE;
  }

  // Initialize Shared Memory Layout instance
  Sphere::ShmLayout layout = Sphere::init_shm(true);

  // Handle explicit SHM initialization request
  if (init_shm_mode) {
    std::cout << "[Main] Shared memory segment successfully pre-allocated and initialized ("
              << shm_size_mb << " MB).\n";
    return EXIT_SUCCESS;
  }

  SHMContext *shm_ctx = reinterpret_cast<SHMContext *>(shm.data());
  if (!shm_ctx) {
    shm_ctx = &layout;
  }

  // Direct standalone CLI Ping execution
  if (ping_mode) {
    std::cout << "[Main] Executing command-line --ping test...\n";

    PacketHeader ping_pkt{};
    ping_pkt.type = PacketType::CMD_PING;

    if (shm_ctx && CommandRegistry::instance().dispatch(ping_pkt, *shm_ctx)) {
      std::cout << "[Main] Ping command handled successfully! (PONG)\n";
      return EXIT_SUCCESS;
    } else {
      std::cerr << "[Main] Ping dispatched, but no handler registered for "
                   "CMD_PING or invalid SHM context.\n";
      return EXIT_FAILURE;
    }
  }

  if (filepaths.empty()) {
    std::cerr << "[Main] Error: No ROOT input files specified.\n";
    return EXIT_FAILURE;
  }

  std::cout << "[Main] Starting ROOT Ingestion Engine (" << filepaths.size()
            << " input partition(s))...\n";

  try {
    std::cout << "[Main] Shared memory initialized: " << shm_size_mb
              << " MB allocated.\n";

    // 2. Instantiate Multi-File Ingestion Engine
    Sphere::IO::RootBatchLoaderMultiFile batch_loader(filepaths);

    // 3. Initialize io_uring Async Kernel Loader & Register Fixed Resources
    Sphere::Platform::AsyncFileLoaderIoUring io_loader(256);
    if (!io_loader.register_shm_buffer(shm.data(), shm.size())) {
      std::cerr << "[Main] Warning: Failed to register shared memory buffer "
                   "for Fixed I/O.\n";
    }
    if (!io_loader.register_files(batch_loader.native_handles())) {
      std::cerr << "[Main] Warning: Failed to register file descriptors for "
                   "Fixed I/O.\n";
    }

    // 4. Initialize Parallel Decompression Worker Pool
    Sphere::IO::DecompressorPool decompressor_pool(worker_threads);
    decompressor_pool.start();

    // 5. Safely manage RNTupleReader instances and descriptors
    std::vector<std::unique_ptr<RNTupleNS::RNTupleReader>> readers;
    std::vector<const RNTupleNS::RNTupleDescriptor *> descriptors;
    readers.reserve(filepaths.size());
    descriptors.reserve(filepaths.size());

    for (const auto &path : filepaths) {
      auto reader = RNTupleNS::RNTupleReader::Open(ntuple_name, path);
      if (!reader) {
        std::cerr << "[Main] Error: Failed to open RNTuple file at " << path
                  << "\n";
        return EXIT_FAILURE;
      }
      descriptors.push_back(&reader->GetDescriptor());
      readers.push_back(std::move(reader));
    }

    // Inspect partitions using pointers to descriptors
    auto raw_requests =
        batch_loader.inspect_rntuple_partitions(descriptors, target_field);
    auto coalesced_requests = batch_loader.coalesce_requests(raw_requests, shm);

    std::cout << "[Main] Inspection complete: " << raw_requests.size()
              << " chunk(s) merged into " << coalesced_requests.size()
              << " I/O batch(es).\n";

    // 6. Submit Initial Batch to io_uring ring using Fixed Files
    std::size_t current_batch_index = 0;
    std::size_t active_in_flight = 0;

    // Prefill submission queue up to capacity or available batches
    while (current_batch_index < coalesced_requests.size() &&
           active_in_flight < 256) {
      const auto &batch = coalesced_requests[current_batch_index];
      io_loader.submit_read_fixed(static_cast<int>(batch.file_index),
                                  batch.target_shm_buffer, batch.total_bytes,
                                  batch.file_offset, current_batch_index);
      ++current_batch_index;
      ++active_in_flight;
    }

    HighPerfMetrics metrics;
    float last_bp = -1.0f;
    auto last_log = std::chrono::steady_clock::now();
    auto last_watchdog = std::chrono::steady_clock::now();
    const auto scenario_start = std::chrono::steady_clock::now();

    // Background Interactive CLI Listener Thread
    std::thread cli_thread;
    if (interactive_mode) {
      cli_thread = std::thread([]() {
        std::string line;
        while (!g_shutdown.load(std::memory_order_relaxed)) {
          if (std::getline(std::cin, line)) {
            if (line == "status")
              g_cli_queue.push(CliCommand::Status);
            else if (line == "status+" || line == "diag")
              g_cli_queue.push(CliCommand::StatusPlus);
            else if (line == "ping")
              g_cli_queue.push(CliCommand::Ping);
            else if (line == "restart")
              g_cli_queue.push(CliCommand::Restart);
            else if (line == "quit")
              g_cli_queue.push(CliCommand::Quit);
          } else {
            break;
          }
        }
      });
    }

    std::cout << "[Main] Main processing loop running. Press Ctrl+C to stop.\n";

    std::vector<Sphere::Platform::IoCompletion> completions;
    completions.reserve(256);

    // ========================================================================
    // MAIN PROCESSING & HEALTH CONTROL LOOP
    // ========================================================================
    while (!g_shutdown.load(std::memory_order_acquire)) {

      // A. Process incoming IPC commands from shared memory ring
      if (shm_ctx) {
        process_shm_ring_commands(*shm_ctx, metrics);
      }

      // B. Poll io_uring CQ (Completion Queue)
      const int completed_count = io_loader.poll_completions(0, completions);

      for (int i = 0; i < completed_count; ++i) {
        const auto &comp = completions[i];
        --active_in_flight;

        if (comp.result_bytes > 0) {
          const std::size_t batch_idx =
              static_cast<std::size_t>(comp.user_data);
          const auto &batch = coalesced_requests[batch_idx];

          metrics.total_batches.fetch_add(1, std::memory_order_relaxed);
          metrics.total_bytes_read.fetch_add(
              static_cast<std::size_t>(comp.result_bytes),
              std::memory_order_relaxed);

          // Dispatch sub-chunks to parallel decompression workers
          for (const auto &sub : batch.sub_chunks) {
            decompressor_pool.enqueue_job(
                sub.target_shm_buffer, sub.compressed_bytes,
                [&metrics](void *uncompressed_ptr, std::size_t bytes) {
                  (void)uncompressed_ptr;
                  metrics.total_bytes_decompressed.fetch_add(
                      bytes, std::memory_order_relaxed);
                });
          }
        } else if (comp.result_bytes < 0) {
          std::cerr << "[Main] I/O error on completion index " << comp.user_data
                    << ": " << std::strerror(-comp.result_bytes) << "\n";
        }

        // Continuously feed submission queue as completions arrive
        if (current_batch_index < coalesced_requests.size()) {
          const auto &next_batch = coalesced_requests[current_batch_index];
          io_loader.submit_read_fixed(
              static_cast<int>(next_batch.file_index),
              next_batch.target_shm_buffer, next_batch.total_bytes,
              next_batch.file_offset, current_batch_index);
          ++current_batch_index;
          ++active_in_flight;
        }
      }

      // Exit main processing loop cleanly when all batches finish ingestion
      if (current_batch_index >= coalesced_requests.size() &&
          active_in_flight == 0) {
        std::cout << "[Main] All I/O batches completed successfully.\n";
        break;
      }

      // C. Compute pipeline pressure metric
      const std::size_t pending_decomp = decompressor_pool.pending_jobs();
      const float bp =
          std::min(1.0f, static_cast<float>(pending_decomp) / 1000.0f);

      metrics.update_max_bp(bp);
      metrics.update_min_bp(bp);

      if (bp > 0.80f)
        metrics.high_ticks.fetch_add(1, std::memory_order_relaxed);
      else if (bp < 0.10f)
        metrics.low_ticks.fetch_add(1, std::memory_order_relaxed);
      else
        metrics.normal_ticks.fetch_add(1, std::memory_order_relaxed);

      // D. Process Queued Interactive CLI Commands
      if (interactive_mode) {
        CliCommand cmd = g_cli_queue.pop();
        while (cmd != CliCommand::None) {
          if (cmd == CliCommand::Status) {
            std::cout << "[CLI] Batches=" << metrics.total_batches.load()
                      << ", IPC Cmds=" << metrics.commands_processed.load()
                      << ", Backpressure=" << bp << "\n";
          } else if (cmd == CliCommand::StatusPlus) {
            std::cout << "\n=== [CLI Extended Telemetry & Diagnostics] ===\n"
                      << "  Total Batches         : "
                      << metrics.total_batches.load() << "\n"
                      << "  Total IPC Commands    : "
                      << metrics.commands_processed.load() << "\n"
                      << "  Total Compressed Read : "
                      << metrics.total_bytes_read.load() << " bytes\n"
                      << "  Pending Decompress    : " << pending_decomp
                      << " tasks\n"
                      << "  Current Backpressure  : " << bp << "\n"
                      << "  SHM Base Address      : "
                      << static_cast<void *>(shm.data()) << "\n"
                      << "===============================================\n\n";
          } else if (cmd == CliCommand::Ping) {
            PacketHeader ping_pkt{};
            ping_pkt.type = PacketType::CMD_PING;

            std::cout << "[CLI Test] Sending CMD_PING directly to "
                         "CommandRegistry...\n";

            if (shm_ctx &&
                CommandRegistry::instance().dispatch(ping_pkt, *shm_ctx)) {
              std::cout
                  << "[CLI Test] Ping command handled successfully! (PONG)\n";
            } else {
              std::cout << "[CLI Test] Ping dispatched, but no handler "
                           "registered for CMD_PING.\n";
            }

            metrics.commands_processed.fetch_add(1, std::memory_order_relaxed);
          } else if (cmd == CliCommand::Quit) {
            g_shutdown.store(true, std::memory_order_release);
          }
          cmd = g_cli_queue.pop();
        }
      }

      // E. Scenario Workload Injection
      const auto now = std::chrono::steady_clock::now();
      if (scenario == "burst") {
        const auto elapsed_sec =
            std::chrono::duration_cast<std::chrono::seconds>(now -
                                                              scenario_start)
                .count();
        if (elapsed_sec > 0 && elapsed_sec % 5 == 0) {
          volatile std::uint64_t dummy = 0;
          for (int k = 0; k < 500000; ++k)
            dummy += static_cast<std::uint64_t>(k);
        }
      }

      // F. JSON Lines or Standard Console Logging
      if (now - last_log >= std::chrono::seconds(1)) {
        last_log = now;
        const float delta = (last_bp < 0.0f) ? 0.0f : (bp - last_bp);
        last_bp = bp;

        if (json_log_mode) {
          const auto timestamp =
              std::chrono::duration_cast<std::chrono::milliseconds>(
                  now.time_since_epoch())
                  .count();
          std::cout << "{\"timestamp\":" << timestamp
                    << ",\"batches\":" << metrics.total_batches.load()
                    << ",\"ipc_cmds\":" << metrics.commands_processed.load()
                    << ",\"bytes_read\":" << metrics.total_bytes_read.load()
                    << ",\"backpressure\":" << bp << ",\"delta\":" << delta
                    << "}\n"
                    << std::flush;
        } else if (!quiet_mode) {
          std::cout << std::fixed << std::setprecision(3);
          std::cout << "[Main] Read: "
                    << (metrics.total_bytes_read.load() / (1024 * 1024))
                    << " MB | Batches: " << metrics.total_batches.load()
                    << " | IPC Cmds: " << metrics.commands_processed.load()
                    << " | BP: " << bp << " (delta: " << delta << ")\n";
        }
      }

      // G. Engine Stall Watchdog Monitoring
      if (watchdog_mode && now - last_watchdog >= std::chrono::seconds(3)) {
        last_watchdog = now;
        if (bp > 0.95f) {
          std::cout << "[Main] Watchdog: Engine stall detected (>95% BP). "
                       "Flushing pipeline...\n";
          decompressor_pool.flush();
        }
      }

      // H. Adaptive Low-Latency Spin-Wait Execution
      if (completed_count == 0) {
        if (bp > 0.80f) {
          for (int k = 0; k < 100; ++k)
            cpu_relax();
        } else if (bp > 0.10f) {
          std::this_thread::yield();
        } else {
          std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
      }
    }

    // Safely stop background workers and clean up
    g_shutdown.store(true, std::memory_order_release);

    if (cli_thread.joinable()) {
      cli_thread.detach(); // Prevent blocking on stdin read during shutdown
    }

    std::cout << "[Main] Shutting down decompression workers...\n";
    decompressor_pool.stop();

    if (stats_mode) {
      const std::size_t total_ticks =
          metrics.high_ticks + metrics.low_ticks + metrics.normal_ticks;
      std::cout << std::fixed << std::setprecision(3);
      std::cout << "\n================ High-Perf Runtime Metrics Summary "
                   "================\n";
      std::cout << "  Total Batches Processed   : "
                << metrics.total_batches.load() << "\n";
      std::cout << "  Total IPC Cmds Processed  : "
                << metrics.commands_processed.load() << "\n";
      std::cout << "  Total Compressed Read     : "
                << metrics.total_bytes_read.load() << " bytes\n";
      std::cout << "  Total Decompressed Bytes  : "
                << metrics.total_bytes_decompressed.load() << "\n";
      std::cout << "  Total Telemetry Samples   : " << total_ticks << "\n";
      std::cout << "  Maximum Backpressure      : " << metrics.max_bp.load()
                << "\n";
      std::cout << "  Minimum Backpressure      : " << metrics.min_bp.load()
                << "\n";
      std::cout << "  High Pressure Ticks       : " << metrics.high_ticks.load()
                << "\n";
      std::cout << "  Low Pressure Ticks        : " << metrics.low_ticks.load()
                << "\n";
      std::cout << "  Normal Pressure Ticks     : "
                << metrics.normal_ticks.load() << "\n";
      std::cout << "==========================================================="
                   "========\n";
    }

  } catch (const std::exception &ex) {
    std::cerr << "[Main] Fatal runtime exception: " << ex.what() << "\n";
    return EXIT_FAILURE;
  } catch (...) {
    std::cerr << "[Main] Fatal unknown exception occurred.\n";
    return EXIT_FAILURE;
  }

  std::cout << "[Main] Shutdown complete.\n";
  return EXIT_SUCCESS;
}