// main.cpp
//
// ROOT ingestion engine.

#include "AsyncFileLoader_io_uring.h"
#include "DecompressorPool.h"
#include "RootBatchLoaderMultiFile.h"
#include "commands/cmd_file.h"
#include "commands/cmd_system.h"
#include "commands/cmd_inspect.h"
#include "commands/cmd_ttree.h"
#include "logger.h"
#include "platform.h"
#include "root_runtime.h"
#include "utils.h"

#include <RVersion.h>
#include <TROOT.h>
#include "diagnostics.h"
#include <ROOT/RNTupleReader.hxx>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#if defined(SPHERE_OS_WINDOWS)
#include <windows.h>
#else
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#if defined(SPHERE_OS_LINUX)
#include <sys/prctl.h>
#endif
#if defined(SPHERE_OS_WINDOWS)
#include <windows.h>
#endif
#endif

namespace {

using Sphere::ShmLayout;

std::atomic<bool> g_shutdown{false};
static_assert(std::atomic<bool>::is_always_lock_free,
              "The shutdown flag must be lock-free to be set from a handler.");

extern "C" void handle_signal(int /*signal*/) {
  if (g_shutdown.exchange(true, std::memory_order_release)) {
    static constexpr char kMessage[] = "\n[Main] Forced exit.\n";
#if defined(SPHERE_OS_WINDOWS)
    ::_write(2, kMessage, sizeof(kMessage) - 1);
    ::_exit(EXIT_FAILURE);
#else
    const ssize_t ignored = ::write(STDERR_FILENO, kMessage, sizeof(kMessage) - 1);
    (void)ignored;
    ::_exit(EXIT_FAILURE);
#endif
  }
}

struct Options {
  bool quiet{false};
  bool stats{false};
  bool json_log{false};
  bool init_shm_only{false};
  bool ping_only{false};
  bool serve{false};
  std::size_t staging_mb{512};
  unsigned int worker_threads{0};
  int cpu_core{-1};
  std::string shm_path{Sphere::SHM_NAME};
  std::size_t shm_size{Sphere::SHM_SIZE};

  // Diagnostics. Each runs on its own and returns before the engine starts.
  bool test_shm{false};
  bool test_shmprobe{false};
  bool test_root{false};
  bool keep_test_file{false};
  std::size_t test_size_mib{Sphere::SHM_SIZE / (1024 * 1024)};
  std::string test_path{"root_backend_test.shm"};
  long long parent_pid{0};
  std::string ntuple_name{"Events"};
  std::string field_name{"pt"};
  std::vector<std::string> filepaths;
};

/**
 * Resolves the default region location against the binary's own directory
 */
std::string resolve_default_region(const std::string &name) {
  if (!name.empty() && (name[0] == '/' || name.find('/') != std::string::npos)) {
    return name; // absolute, POSIX object, or already qualified
  }

#if defined(SPHERE_OS_LINUX)
  char buffer[4096];
  const ssize_t written = ::readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
  if (written > 0) {
    buffer[written] = '\0';
    const std::string exe(buffer);
    const std::size_t slash = exe.find_last_of('/');
    if (slash != std::string::npos) {
      return exe.substr(0, slash + 1) + name;
    }
  }
#endif
  return name;
}

void print_usage(const char *program) {
  std::cout
      << "Usage: " << program << " [options] [file1.root file2.root ...]\n\n"
      << "  --serve             Run the IPC engine and wait for commands.\n"
      << "  --init-shm          Create and initialize the shared region, then "
         "exit.\n"
      << "  --ping              Dispatch one CMD_PING through the registry and "
         "exit.\n"
      << "  --shm <path>        Shared region location (default: "
      << Sphere::SHM_NAME << ").\n"
         "                      A path is a file and survives a reboot; a name\n"
         "                      like /foo is a POSIX object under /dev/shm.\n"
      << "  --size <bytes>      Region size (default: " << Sphere::SHM_SIZE
      << ").\n"
      << "  --parent-pid <pid>  Exit when that process exits.\n"
      << "\n"
         "Diagnostics, each isolating one layer and returning:\n"
         "  --test-shm          Create a region from nothing, timing every\n"
         "                      syscall. Uses root_backend_test.shm, never the\n"
         "                      live region. --test-size <MiB>, --test-path\n"
         "                      <file> and --keep adjust it.\n"
         "  --test-shmprobe     Attach to the region named by --shm and report\n"
         "                      its header, rings and heartbeat. Read-only,\n"
         "                      safe against a running engine.\n"
         "  --test-root         Time ROOT coming up: gROOT, thread safety,\n"
         "                      gSystem.\n"
      << "  --ntuple <name>     RNTuple container name (default: Events).\n"
      << "  --field <name>      Field to extract (default: pt).\n"
      << "  --staging-mb <n>    Staging region size in MiB (default: 512).\n"
      << "  --threads <n>       Decompression workers (default: hardware "
         "concurrency).\n"
      << "  --core <id>         Pin the main loop to a CPU core.\n"
      << "  --quiet             Suppress periodic telemetry.\n"
      << "  --stats             Print a summary on shutdown.\n"
      << "  --json-log          Emit telemetry as JSON Lines.\n"
      << "  -h, --help          Show this message.\n";
}

bool parse_options(int argc, char **argv, Options &options) {
  for (int i = 1; i < argc; ++i) {
    const std::string_view arg = argv[i];
    auto next = [&](std::string &out) {
      if (i + 1 < argc) {
        out = argv[++i];
        return true;
      }
      std::cerr << "[Main] Missing value for " << arg << "\n";
      return false;
    };

    if (arg == "--serve") {
      options.serve = true;
    } else if (arg == "--init-shm") {
      options.init_shm_only = true;
    } else if (arg == "--ping") {
      options.ping_only = true;
    } else if (arg == "--quiet") {
      options.quiet = true;
    } else if (arg == "--stats") {
      options.stats = true;
    } else if (arg == "--json-log") {
      options.json_log = true;
    } else if (arg == "--test-shm") {
      options.test_shm = true;
    } else if (arg == "--test-shmprobe") {
      options.test_shmprobe = true;
    } else if (arg == "--test-root") {
      options.test_root = true;
    } else if (arg == "--keep") {
      options.keep_test_file = true;
    } else if (arg == "--test-size") {
      std::string value;
      if (!next(value)) {
        return false;
      }
      options.test_size_mib =
          static_cast<std::size_t>(std::strtoull(value.c_str(), nullptr, 10));
      if (options.test_size_mib == 0) {
        std::cerr << "[Main] --test-size must be a positive MiB count.\n";
        return false;
      }
    } else if (arg == "--test-path") {
      if (!next(options.test_path)) {
        return false;
      }
    } else if (arg == "--parent-pid") {
      std::string value;
      if (!next(value)) {
        return false;
      }
      options.parent_pid = std::strtoll(value.c_str(), nullptr, 10);
    } else if (arg == "--shm") {
      if (!next(options.shm_path)) {
        return false;
      }
    } else if (arg == "--size") {
      std::string value;
      if (!next(value)) {
        return false;
      }
      options.shm_size = static_cast<std::size_t>(
          std::strtoull(value.c_str(), nullptr, 10));
      if (options.shm_size == 0) {
        std::cerr << "[Main] --size must be a positive byte count.\n";
        return false;
      }
      // ShmRef::offset is a uint32, so nothing past 4 GiB can be addressed in
      // an event. Refusing here beats handing out truncated offsets later.
      if (options.shm_size > 0xFFFFFFFFULL) {
        std::cerr << "[Main] --size cannot exceed 4 GiB: a heap offset travels "
                     "as a 32-bit field.\n";
        return false;
      }
    } else if (arg == "--ntuple") {
      if (!next(options.ntuple_name)) {
        return false;
      }
    } else if (arg == "--field") {
      if (!next(options.field_name)) {
        return false;
      }
    } else if (arg == "--staging-mb") {
      std::string value;
      if (!next(value)) {
        return false;
      }
      options.staging_mb = static_cast<std::size_t>(std::strtoul(
          value.c_str(), nullptr, 10));
    } else if (arg == "--threads") {
      std::string value;
      if (!next(value)) {
        return false;
      }
      options.worker_threads = static_cast<unsigned int>(
          std::strtoul(value.c_str(), nullptr, 10));
    } else if (arg == "--core") {
      std::string value;
      if (!next(value)) {
        return false;
      }
      options.cpu_core = static_cast<int>(std::strtol(value.c_str(), nullptr, 10));
    } else if (arg == "-h" || arg == "--help" || arg == "help") {
      print_usage(argv[0]);
      return false;
    } else if (!arg.empty() && arg.front() != '-') {
      options.filepaths.emplace_back(arg);
    } else {
      // Ignored, not fatal: a launcher newer than this binary must not kill it.
      std::cerr << "[Main] Ignoring unknown option: " << arg << "\n";
    }
  }

  // Anchor the default to the binary's directory once every option is parsed.
  options.shm_path = resolve_default_region(options.shm_path);

  if (options.staging_mb == 0) {
    options.staging_mb = 512;
  }
  if (options.worker_threads == 0) {
    options.worker_threads = std::max(1u, std::thread::hardware_concurrency());
  }
  return true;
}

/**
 * Takes the region's single-engine lock, or reports who already holds it.
 */
// Exits when the parent process goes away, so no engine outlives its launcher.
void exit_with_parent(long long parent_pid) {
#if defined(SPHERE_OS_WINDOWS)
  if (parent_pid <= 0) {
    return;
  }
  HANDLE parent = ::OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(parent_pid));
  if (parent == nullptr) {
    return;
  }
  std::thread([parent]() {
    ::WaitForSingleObject(parent, INFINITE);
    ::CloseHandle(parent);
    std::_Exit(EXIT_SUCCESS);
  }).detach();
#else
#if defined(SPHERE_OS_LINUX)
  ::prctl(PR_SET_PDEATHSIG, SIGTERM);
#endif
  const pid_t watched =
      (parent_pid > 0) ? static_cast<pid_t>(parent_pid) : ::getppid();
  if (watched <= 1) {
    return;
  }
  if (::kill(watched, 0) != 0) {
    std::_Exit(EXIT_SUCCESS);
  }
  std::thread([watched]() {
    while (::kill(watched, 0) == 0) {
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    std::_Exit(EXIT_SUCCESS);
  }).detach();
#endif
}

bool claim_region(const std::string &path, const char *what) {
#if defined(SPHERE_OS_WINDOWS)
  (void)path;
  (void)what;
  return true;
#else
  const int fd = ::open(path.c_str(), O_RDWR);
  if (fd < 0) {
    // Nothing to lock yet; the region is about to be created.
    return true;
  }
  if (::flock(fd, LOCK_EX | LOCK_NB) == 0) {
    return true; // held for the lifetime of this process
  }
  if (errno == EWOULDBLOCK) {
    std::cerr << "[Main] Another root-bridge already serves " << path
              << "; refusing to " << what << ".\n";
  } else {
    std::cerr << "[Main] Could not lock " << path << ": " << std::strerror(errno)
              << ". Continuing without the single-engine guard.\n";
    ::close(fd);
    return true;
  }
  ::close(fd);
  return false;
#endif
}

/**
 * Winds down the command modules' own resources
 */
void shutdown_command_modules() noexcept {
  try {
    Sphere::cmd::ttree::shutdown_thread_pool();
    Sphere::cmd::file::close_all_files();
  } catch (const std::exception &ex) {
    std::cerr << "[Main] Error during command shutdown: " << ex.what() << "\n";
  } catch (...) {
    std::cerr << "[Main] Unknown error during command shutdown.\n";
  }
}

void pin_main_thread(int core_id) {
  if (core_id < 0) {
    return;
  }
#if defined(SPHERE_OS_LINUX)
  cpu_set_t cpuset;
  CPU_ZERO(&cpuset);
  CPU_SET(static_cast<std::size_t>(core_id), &cpuset);
  if (::pthread_setaffinity_np(::pthread_self(), sizeof(cpu_set_t), &cpuset) !=
      0) {
    std::cerr << "[Main] Could not pin to core " << core_id << "\n";
  }
#else
  std::cerr << "[Main] Thread pinning is not supported on this platform.\n";
#endif
}

/**
 * Runs the ingestion pipeline over a set of RNTuple files
 */
int run_ingestion(const Options &options, Sphere::RootRuntime &runtime) {
  namespace IO = Sphere::IO;
  namespace Platform = Sphere::Platform;

  const std::size_t staging_bytes = options.staging_mb * 1024 * 1024;
  Platform::ShmRegion staging = Platform::shm_create(
      "sphere_staging", staging_bytes,
      Platform::ShmFlags::ANONYMOUS | Platform::ShmFlags::PREFAULT_PAGES);
  if (!staging.is_valid()) {
    std::cerr << "[Main] Could not allocate the staging region.\n";
    return EXIT_FAILURE;
  }

  // Split the staging region
  const std::size_t half = staging.size() / 2;
  auto *compressed_base = staging.as<std::uint8_t>();
  auto *decompressed_base = compressed_base + half;

  // Plain pointers
  struct BorrowedRegion {
    std::uint8_t *base;
    std::size_t size;
  };
  const BorrowedRegion compressed{compressed_base, half};
  const BorrowedRegion decompressed{decompressed_base, staging.size() - half};

  IO::RootBatchLoader batch_loader(options.filepaths);

  Platform::AsyncFileLoaderIoUring io_loader(256);
  if (!io_loader.using_io_uring()) {
    std::cout << "[Main] io_uring unavailable (" << io_loader.last_error()
              << "); using synchronous reads.\n";
  } else if (!io_loader.using_sqpoll()) {
    std::cout << "[Main] io_uring active without SQPOLL (needs privileges).\n";
  }

  if (!io_loader.register_files(batch_loader.native_handles())) {
    std::cerr << "[Main] Could not register file descriptors: "
              << io_loader.last_error() << "\n";
  }
  if (io_loader.using_io_uring() &&
      !io_loader.register_shm_buffer(compressed.base, compressed.size)) {
    std::cerr << "[Main] Could not register the staging buffer: "
              << io_loader.last_error() << "\n";
  }

  IO::DecompressorPool pool(options.worker_threads);
  pool.start();

  std::vector<std::unique_ptr<RNTupleNS::RNTupleReader>> readers;
  std::vector<const RNTupleNS::RNTupleDescriptor *> descriptors;
  readers.reserve(options.filepaths.size());
  descriptors.reserve(options.filepaths.size());

  for (const auto &path : options.filepaths) {
    auto reader = RNTupleNS::RNTupleReader::Open(options.ntuple_name, path);
    if (!reader) {
      std::cerr << "[Main] Could not open RNTuple '" << options.ntuple_name
                << "' in " << path << "\n";
      return EXIT_FAILURE;
    }
    descriptors.push_back(&reader->GetDescriptor());
    readers.push_back(std::move(reader));
  }

  auto requests =
      IO::inspect_rntuple_partitions(descriptors, options.field_name);
  if (requests.empty()) {
    std::cerr << "[Main] Field '" << options.field_name
              << "' produced no pages.\n";
    return EXIT_FAILURE;
  }

  const IO::CoalesceResult coalesced =
      IO::coalesce_requests(requests, compressed.base, compressed.size);

  std::cout << "[Main] " << requests.size() << " page(s) -> "
            << coalesced.batches.size() << " read(s)";
  if (coalesced.chunks_dropped > 0) {
    std::cout << ", " << coalesced.chunks_dropped
              << " dropped (staging region too small; raise --staging-mb)";
  }
  std::cout << "\n";

  const std::size_t submitted =
      batch_loader.submit_batches(io_loader, coalesced.batches);
  Sphere::log::io_uring_on_submit(submitted);

  std::uint64_t bytes_read = 0;
  std::uint64_t bytes_decompressed = 0;
  std::uint64_t decode_errors = 0;
  std::size_t completed_batches = 0;
  std::size_t inflight = submitted;

  std::vector<Platform::IoCompletion> completions;
  completions.reserve(256);

  // Bump allocator over the decompressed half.
  std::size_t decompressed_used = 0;

  const auto started = std::chrono::steady_clock::now();
  auto last_log = started;

  while (inflight > 0 && !g_shutdown.load(std::memory_order_acquire)) {
    const int count = io_loader.poll_completions(1, completions);
    if (count <= 0) {
      continue;
    }

    for (int i = 0; i < count; ++i) {
      const Platform::IoCompletion &completion = completions[i];
      --inflight;
      ++completed_batches;
      Sphere::log::io_uring_on_complete();

      if (completion.result_bytes < 0) {
        std::cerr << "[Main] Read failed on batch " << completion.user_data
                  << ": " << std::strerror(-completion.result_bytes) << "\n";
        Sphere::log::root_on_read_error();
        continue;
      }
      bytes_read += static_cast<std::uint64_t>(completion.result_bytes);

      const auto batch_index = static_cast<std::size_t>(completion.user_data);
      if (batch_index >= coalesced.batches.size()) {
        continue;
      }
      const IO::RootCoalescedRequest &batch = coalesced.batches[batch_index];

      // Build one decompression job per chunk in this batch.
      std::vector<IO::DecompressorPool::Job> jobs;
      jobs.reserve(batch.sub_indices.size());

      for (const std::size_t index : batch.sub_indices) {
        const IO::RootChunkRequest &chunk = requests[index];
        if (chunk.target_shm_buffer == nullptr) {
          continue;
        }

        const auto *src =
            static_cast<const std::uint8_t *>(chunk.target_shm_buffer);
        const std::uint64_t expanded = IO::record_uncompressed_size(
            src, chunk.compressed_bytes, chunk.has_key_header);
        if (expanded == 0) {
          ++decode_errors;
          Sphere::log::root_on_decompress_error();
          continue;
        }

        const std::size_t aligned =
            (static_cast<std::size_t>(expanded) + Sphere::SIMD_ALIGNMENT - 1) &
            ~(Sphere::SIMD_ALIGNMENT - 1);
        if (decompressed_used + aligned > decompressed.size) {
          decompressed_used = 0;
        }

        IO::DecompressorPool::Job job{};
        job.src = src;
        job.src_size = chunk.compressed_bytes;
        job.dst = decompressed.base + decompressed_used;
        job.dst_capacity = decompressed.size - decompressed_used;
        job.has_key_header = chunk.has_key_header;
        job.index = chunk.logical_index;
        jobs.push_back(job);

        decompressed_used += aligned;
      }

      if (!jobs.empty()) {
        const auto results = pool.submit_and_wait(jobs);
        for (const auto &result : results) {
          if (result.ok) {
            bytes_decompressed += result.bytes_written;
          } else {
            ++decode_errors;
            Sphere::log::root_on_decompress_error();
            if (decode_errors <= 5) {
              std::cerr << "[Main] Decode error: " << result.error << "\n";
            }
          }
        }
      }
    }

    const auto now = std::chrono::steady_clock::now();
    if (!options.quiet && now - last_log >= std::chrono::seconds(1)) {
      last_log = now;
      const float pressure = runtime.backpressure_level();
      if (options.json_log) {
        std::cout << "{\"batches\":" << completed_batches
                  << ",\"bytes_read\":" << bytes_read
                  << ",\"bytes_decompressed\":" << bytes_decompressed
                  << ",\"errors\":" << decode_errors
                  << ",\"backpressure\":" << pressure << "}\n"
                  << std::flush;
      } else {
        std::cout << "[Main] " << (bytes_read / (1024 * 1024)) << " MiB read, "
                  << (bytes_decompressed / (1024 * 1024))
                  << " MiB decompressed, " << completed_batches << " batch(es), "
                  << decode_errors << " error(s)\n";
      }
    }
  }

  pool.stop();

  const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - started)
                           .count();

  if (options.stats) {
    const double seconds = std::max<double>(1.0, static_cast<double>(elapsed)) / 1000.0;
    const auto control = runtime.control_stats();

    std::cout << std::fixed << std::setprecision(2);
    std::cout << "\n===================== Summary =====================\n"
              << "  Batches completed     : " << completed_batches << "\n"
              << "  Compressed read       : " << (bytes_read / (1024 * 1024))
              << " MiB\n"
              << "  Decompressed          : "
              << (bytes_decompressed / (1024 * 1024)) << " MiB\n"
              << "  Decode errors         : " << decode_errors << "\n"
              << "  Elapsed               : " << elapsed << " ms\n"
              << "  Read throughput       : "
              << (static_cast<double>(bytes_read) / (1024.0 * 1024.0) / seconds)
              << " MiB/s\n"
              << "  Control iterations    : " << control.loop_iterations << "\n"
              << "  Queue rebalances      : " << control.rebalances << " ("
              << control.tasks_migrated << " task(s) moved)\n"
              << "  Compaction passes     : " << control.defrag_passes << " ("
              << control.chunks_reclaimed << " chunk(s) reclaimed)\n"
              << "===================================================\n";
  }

  return (decode_errors == 0) ? EXIT_SUCCESS : EXIT_FAILURE;
}

} // namespace

int main(int argc, char **argv) {
  Options options;
  if (!parse_options(argc, argv, options)) {
    return EXIT_FAILURE;
  }
  if (argc < 2) {
    print_usage(argv[0]);
    return EXIT_FAILURE;
  }

  // One engine per region, and one reformat at a time
  if (options.serve) {
    exit_with_parent(options.parent_pid);
  }
  if (options.serve && !claim_region(options.shm_path, "start a second engine")) {
    return EXIT_FAILURE;
  }
  if (options.init_shm_only &&
      !claim_region(options.shm_path, "reformat a region in use")) {
    return EXIT_FAILURE;
  }

  // Diagnostics first: each isolates one layer and returns
  if (options.test_shm) {
    return Sphere::diag::RegionCreationTest::run(
        options.test_size_mib, options.test_path, options.keep_test_file);
  }
  if (options.test_shmprobe) {
    return Sphere::diag::RegionProbeTest::run(options.shm_path);
  }
  if (options.test_root) {
    return Sphere::diag::RootInitTest::run();
  }

  pin_main_thread(options.cpu_core);

  std::signal(SIGINT, handle_signal);
#if defined(SIGTERM)
  std::signal(SIGTERM, handle_signal);
#endif
#if defined(SIGPIPE)
  std::signal(SIGPIPE, SIG_IGN);
#endif

  // ROOT's internal locks
  // Only a serving engine needs ROOT and the interpreter. --init-shm formats a
  // region and exits; paying for cling there kept the lock held for seconds.
  if (options.serve) {
    (void)gROOT->GetVersion();
    ROOT::EnableThreadSafety();
    Sphere::cmd::sys::warm_up();
  }
  Sphere::cmd::sys::register_all();
  Sphere::cmd::file::register_all();
  Sphere::cmd::ttree::register_all();
  Sphere::cmd::inspect::register_all();

  try {
    Sphere::RootRuntime runtime(true,
                                options.shm_path.c_str(),
                                options.shm_size,
                                options.init_shm_only);
    ShmLayout &layout = runtime.shm_layout();

    if (options.init_shm_only) {
      return EXIT_SUCCESS;
    }

    if (options.ping_only) {
      Sphere::Proto::PacketHeader ping{};
      ping.type = Sphere::Proto::PacketType::CMD_PING;
      ping.req_id = 1;

      if (!Sphere::CommandRegistry::instance().dispatch(layout, ping)) {
        std::cerr << "[Main] No handler registered for CMD_PING.\n";
        return EXIT_FAILURE;
      }

      Sphere::BridgeMessage reply{};
      bool answered = false;
      for (int attempt = 0; attempt < 200 && !answered; ++attempt) {
        if (layout.evt_ring != nullptr && layout.evt_ring->pop(reply)) {
          answered = true;
          break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
      }

      if (!answered) {
        std::cerr << "[Main] CMD_PING dispatched but no event came back.\n";
        return EXIT_FAILURE;
      }

      std::string payload;
      if (reply.type == Sphere::MsgType::SHM_REF && reply.shm_ref.offset != 0) {
        // The handler answered through the shared heap; read the chunk.
        const std::uint64_t offset = reply.shm_ref.offset;
        const std::uint64_t size = reply.shm_ref.total_bytes;
        if (layout.base != nullptr && layout.header != nullptr &&
            offset < layout.header->total_size &&
            size <= layout.header->total_size - offset) {
          const auto *bytes =
              reinterpret_cast<const char *>(layout.base + offset);
          payload.assign(bytes, ::strnlen(bytes, static_cast<std::size_t>(size)));
        }
      } else if (reply.payload_size > 0) {
        payload.assign(reinterpret_cast<const char *>(reply.inline_bytes),
                       std::min<std::size_t>(reply.payload_size,
                                             Sphere::BRIDGE_INLINE_CAPACITY));
      }
      if (payload.empty()) {
        payload = "PONG";
      }
      std::cout << "[Main] " << payload << " (cmd=" << reply.cmd
                << ", req_id=" << reply.req_id << ")\n";
      return EXIT_SUCCESS;
    }

    runtime.start();

    int status = EXIT_SUCCESS;
    if (!options.filepaths.empty()) {
      status = run_ingestion(options, runtime);
    } else if (!options.serve) {
      std::cerr << "[Main] No input files. Pass --serve to run as an IPC "
                   "engine, or give at least one .root file.\n";
      runtime.stop();
      shutdown_command_modules();
      return EXIT_FAILURE;
    }

    if (options.serve) {
      while (!g_shutdown.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
      }
    }

    runtime.stop();
    shutdown_command_modules();
    return status;

  } catch (const std::exception &ex) {
    std::cerr << "[Main] Fatal: " << ex.what() << "\n";
    return EXIT_FAILURE;
  } catch (...) {
    std::cerr << "[Main] Fatal: unknown exception.\n";
    return EXIT_FAILURE;
  }
}
