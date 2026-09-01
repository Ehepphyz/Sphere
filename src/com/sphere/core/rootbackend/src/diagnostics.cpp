// See inc/diagnostics.h.

#include "diagnostics.h"

#include "common_config.h"
#include "shm_layout.h"

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

#if !defined(SPHERE_OS_WINDOWS)
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include <TROOT.h>
#include <TSystem.h>

namespace Sphere::diag {

namespace {

double lap(std::chrono::steady_clock::time_point &mark) {
  const auto now = std::chrono::steady_clock::now();
  const double elapsed =
      std::chrono::duration<double, std::milli>(now - mark).count();
  mark = now;
  return elapsed;
}

void unbuffer() { std::setvbuf(stdout, nullptr, _IONBF, 0); }

constexpr std::size_t RING_ENQUEUE_POS = 0;
constexpr std::size_t RING_DEQUEUE_POS = 64;

std::uint64_t read_u64(const unsigned char *base, std::size_t offset) {
  std::uint64_t value = 0;
  std::memcpy(&value, base + offset, sizeof(value));
  return value;
}

} // namespace

// -----------------------------------------------------------------------------
// RegionCreationTest
// -----------------------------------------------------------------------------

int RegionCreationTest::run(std::size_t size_mib, const std::string &path,
                            bool keep) {
#if defined(SPHERE_OS_WINDOWS)
  (void)size_mib;
  (void)path;
  (void)keep;
  std::printf("--test-shm is implemented for POSIX only.\n");
  return 1;
#else
  unbuffer();

  const std::size_t size = size_mib * 1024ull * 1024ull;
  std::printf("target : %s\nsize   : %zu MiB\n\n", path.c_str(), size_mib);

  auto mark = std::chrono::steady_clock::now();

  std::printf("[1/5] open(O_CREAT|O_RDWR)... ");
  const int fd = ::open(path.c_str(), O_CREAT | O_RDWR, 0666);
  if (fd < 0) {
    std::printf("FAILED: %s\n", std::strerror(errno));
    return 1;
  }
  std::printf("%.1f ms\n", lap(mark));

  std::printf("[2/5] ftruncate(%zu MiB)... ", size_mib);
  if (::ftruncate(fd, static_cast<off_t>(size)) < 0) {
    std::printf("FAILED: %s\n", std::strerror(errno));
    ::close(fd);
    return 1;
  }
  std::printf("%.1f ms\n", lap(mark));

  std::printf("[3/5] mmap(MAP_SHARED)... ");
  void *base = ::mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  ::close(fd);
  if (base == MAP_FAILED) {
    std::printf("FAILED: %s\n", std::strerror(errno));
    return 1;
  }
  std::printf("%.1f ms\n", lap(mark));

  std::printf("[4/5] zeroing the region, in 16 MiB slices:\n");
  const std::size_t slice = 16ull * 1024 * 1024;
  const auto started = std::chrono::steady_clock::now();
  for (std::size_t offset = 0; offset < size; offset += slice) {
    const std::size_t bytes = (offset + slice > size) ? (size - offset) : slice;
    auto slice_mark = std::chrono::steady_clock::now();
    std::memset(static_cast<char *>(base) + offset, 0, bytes);
    const double elapsed = lap(slice_mark);
    std::printf("      %5zu MiB ... %8.1f ms  (%7.1f MiB/s)\n",
                (offset + bytes) / (1024 * 1024), elapsed,
                elapsed > 0.0
                    ? static_cast<double>(bytes) / (1024 * 1024) / (elapsed / 1000.0)
                    : 0.0);
  }
  std::printf("      total: %.1f ms\n",
              std::chrono::duration<double, std::milli>(
                  std::chrono::steady_clock::now() - started)
                  .count());
  mark = std::chrono::steady_clock::now();

  std::printf("[5/5] msync(MS_SYNC)... ");
  const bool synced = (::msync(base, size, MS_SYNC) == 0);
  if (!synced) {
    std::printf("FAILED: %s\n", std::strerror(errno));
  } else {
    std::printf("%.1f ms\n", lap(mark));
  }

  ::munmap(base, size);

  if (!keep) {
    (void)::unlink(path.c_str());
    std::printf("\nTest file removed. Pass --keep to leave it in place.\n");
  }

  std::printf("\nCompleted without stalling.\n");
  return synced ? 0 : 1;
#endif
}

// -----------------------------------------------------------------------------
// RegionProbeTest
// -----------------------------------------------------------------------------

int RegionProbeTest::run(const std::string &path) {
#if defined(SPHERE_OS_WINDOWS)
  (void)path;
  std::printf("--test-shmprobe is implemented for POSIX only.\n");
  return 1;
#else
  unbuffer();

  std::printf("region : %s\n", path.c_str());

  const int fd = ::open(path.c_str(), O_RDONLY);
  if (fd < 0) {
    std::printf("Cannot open it: %s\n", std::strerror(errno));
    std::printf("Create one first with:  root-bridge --init-shm --shm %s\n",
                path.c_str());
    return 1;
  }

  struct stat info {};
  if (::fstat(fd, &info) != 0) {
    std::printf("fstat failed: %s\n", std::strerror(errno));
    ::close(fd);
    return 1;
  }
  const std::size_t mapped = static_cast<std::size_t>(info.st_size);
  std::printf("size   : %zu bytes (%zu MiB)\n", mapped, mapped / (1024 * 1024));

  if (mapped < sizeof(ShmHeader)) {
    std::printf("Too small to hold a header (%zu bytes).\n", sizeof(ShmHeader));
    ::close(fd);
    return 1;
  }

  void *base = ::mmap(nullptr, mapped, PROT_READ, MAP_SHARED, fd, 0);
  ::close(fd);
  if (base == MAP_FAILED) {
    std::printf("mmap failed: %s\n", std::strerror(errno));
    return 1;
  }

  const auto *bytes = static_cast<const unsigned char *>(base);
  const auto *header = reinterpret_cast<const ShmHeader *>(base);

  const std::uint32_t magic = header->magic.load(std::memory_order_acquire);
  std::printf("magic  : 0x%08X %s\n", magic,
              (magic == SHM_MAGIC) ? "OK" : "EXPECTED 0x53504852");
  if (magic != SHM_MAGIC) {
    std::printf("\nThe region is not formatted. Run root-bridge --init-shm.\n");
    ::munmap(base, mapped);
    return 1;
  }

  const std::uint32_t state = header->state.load(std::memory_order_relaxed);
  const char *state_name = "unknown";
  switch (static_cast<EngineState>(state)) {
  case EngineState::UNINITIALIZED: state_name = "UNINITIALIZED"; break;
  case EngineState::INITIALIZING:  state_name = "INITIALIZING";  break;
  case EngineState::READY:         state_name = "READY";         break;
  case EngineState::RUNNING:       state_name = "RUNNING";       break;
  case EngineState::DEGRADED:      state_name = "DEGRADED";      break;
  case EngineState::STOPPING:      state_name = "STOPPING";      break;
  case EngineState::STOPPED:       state_name = "STOPPED";       break;
  case EngineState::RECOVERY:      state_name = "RECOVERY";      break;
  case EngineState::CORRUPTED:     state_name = "CORRUPTED";     break;
  case EngineState::ERROR:         state_name = "ERROR";         break;
  }
  std::printf("version: %u   abi: %u   proto: %u   state: %u (%s)\n",
              header->version.load(std::memory_order_relaxed),
              header->abi_version.load(std::memory_order_relaxed),
              header->proto_version.load(std::memory_order_relaxed),
              state, state_name);
  std::printf("declared total_size: %llu\n",
              static_cast<unsigned long long>(header->total_size));

  // Two samples say whether an engine is alive, not merely whether a file is.
  const std::uint64_t beat_before =
      header->heartbeat_cpp.load(std::memory_order_acquire);
  std::printf("\nsampling the C++ heartbeat over 600 ms... ");
  std::this_thread::sleep_for(std::chrono::milliseconds(600));
  const std::uint64_t beat_after =
      header->heartbeat_cpp.load(std::memory_order_acquire);
  std::printf("%llu -> %llu   %s\n",
              static_cast<unsigned long long>(beat_before),
              static_cast<unsigned long long>(beat_after),
              (beat_after != beat_before) ? "AN ENGINE IS RUNNING"
                                          : "no beat (no engine attached)");

  std::printf("\npartition table\n");
  std::printf("  command ring : offset %llu  %llu bytes  %llu slots of %llu\n",
              static_cast<unsigned long long>(header->off_cmd_ring),
              static_cast<unsigned long long>(header->size_cmd_ring),
              static_cast<unsigned long long>(header->cmd_ring_capacity),
              static_cast<unsigned long long>(header->cmd_ring_slot_size));
  std::printf("  event ring   : offset %llu  %llu bytes  %llu slots of %llu\n",
              static_cast<unsigned long long>(header->off_evt_ring),
              static_cast<unsigned long long>(header->size_evt_ring),
              static_cast<unsigned long long>(header->evt_ring_capacity),
              static_cast<unsigned long long>(header->evt_ring_slot_size));
  std::printf("  data heap    : offset %llu  %llu bytes\n",
              static_cast<unsigned long long>(header->off_data_heap),
              static_cast<unsigned long long>(header->size_data_heap));

  if (header->off_cmd_ring != 0 && header->off_evt_ring != 0 &&
      header->off_evt_ring + 128 <= mapped) {
    const std::uint64_t cmd_enqueue =
        read_u64(bytes, header->off_cmd_ring + RING_ENQUEUE_POS);
    const std::uint64_t cmd_dequeue =
        read_u64(bytes, header->off_cmd_ring + RING_DEQUEUE_POS);
    const std::uint64_t evt_enqueue =
        read_u64(bytes, header->off_evt_ring + RING_ENQUEUE_POS);
    const std::uint64_t evt_dequeue =
        read_u64(bytes, header->off_evt_ring + RING_DEQUEUE_POS);

    std::printf("\nring cursors\n");
    std::printf("  commands : pushed %llu  consumed %llu  pending %lld\n",
                static_cast<unsigned long long>(cmd_enqueue),
                static_cast<unsigned long long>(cmd_dequeue),
                static_cast<long long>(cmd_enqueue) -
                    static_cast<long long>(cmd_dequeue));
    std::printf("  events   : pushed %llu  consumed %llu  pending %lld\n",
                static_cast<unsigned long long>(evt_enqueue),
                static_cast<unsigned long long>(evt_dequeue),
                static_cast<long long>(evt_enqueue) -
                    static_cast<long long>(evt_dequeue));
    const long long cmd_pending = static_cast<long long>(cmd_enqueue) -
                                  static_cast<long long>(cmd_dequeue);
    const long long evt_pending = static_cast<long long>(evt_enqueue) -
                                  static_cast<long long>(evt_dequeue);

    if (cmd_pending > 0) {
      std::printf("\n  Commands are queued and not being consumed: no engine is\n"
                  "  draining the command ring.\n");
    } else if (cmd_enqueue > 0) {
      std::printf("\n  Every command pushed has been consumed.\n");
    }
    if (evt_pending > 0) {
      std::printf("  %lld event(s) are waiting to be read by the Java side.\n",
                  evt_pending);
    }
    if (cmd_enqueue > 0 && evt_enqueue > cmd_enqueue) {
      std::printf("  %llu events for %llu commands: more replies than requests,\n"
                  "  which is what two engines on one region look like. Check\n"
                  "  with: pgrep -a root-bridge\n",
                  static_cast<unsigned long long>(evt_enqueue),
                  static_cast<unsigned long long>(cmd_enqueue));
    }
  }

  std::printf("\njobs completed: %llu   failed: %llu\n",
              static_cast<unsigned long long>(
                  header->jobs_completed.load(std::memory_order_relaxed)),
              static_cast<unsigned long long>(
                  header->jobs_failed.load(std::memory_order_relaxed)));

  ::munmap(base, mapped);
  return 0;
#endif
}

// -----------------------------------------------------------------------------
// RootInitTest
// -----------------------------------------------------------------------------

int RootInitTest::run() {
  unbuffer();

  auto mark = std::chrono::steady_clock::now();

  std::printf("[1/4] process start, shared libraries loaded... %.1f ms\n",
              lap(mark));

  std::printf("[2/4] first gROOT access (before thread safety, on purpose)... ");
  const char *version = gROOT->GetVersion();
  std::printf("%s in %.1f ms\n", (version != nullptr) ? version : "(null)",
              lap(mark));

  std::printf("[3/4] ROOT::EnableThreadSafety()... ");
  ROOT::EnableThreadSafety();
  std::printf("%.1f ms\n", lap(mark));

  std::printf("[4/4] gSystem->GetBuildArch()... ");
  const char *arch =
      (gSystem != nullptr) ? gSystem->GetBuildArch() : "(gSystem is null)";
  std::printf("%s in %.1f ms\n", arch, lap(mark));

  std::printf("\nCompleted without stalling.\n");
  return 0;
}

} // namespace Sphere::diag
