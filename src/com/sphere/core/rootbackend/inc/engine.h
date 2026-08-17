// engine.h
#pragma once

#include "common_config.h"
#include "lockfree_ring.h"
#include "shm_layout.h"
#include "span_ring.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <stop_token>
#include <thread>
#include <vector>

// Conditional inclusion for NUMA library support
#ifdef HAVE_NUMA
#include <numa.h>
#endif

// Conditional inclusion for Linux kernel-bypass I/O
#ifdef HAVE_IO_URING
#include <liburing.h>
#endif

namespace Sphere {

struct ShmMemoryPool;

/**
 * Priority tier configurations for task scheduling.
 */
enum class PriorityTier : std::uint8_t { HIGH = 0, NORMAL = 1, LOW = 2 };

/**
 * Resolution task item scheduled for execution across NUMA workers.
 */
struct ScheduledTask {
  BridgeMessage msg;                                // Hybrid message payload
  std::chrono::steady_clock::time_point deadline;   // Microsecond deadline boundary
  PriorityTier priority{PriorityTier::NORMAL};     // Task priority tier
  std::uint16_t source_node_id{0};                  // NUMA affinity node identifier
};

/**
 * Cache-line aligned atomic counter structure to prevent false sharing.
 */
struct alignas(CACHE_LINE_SIZE) AlignedAtomicCounter {
  std::atomic<std::uint64_t> value{0};
};

/**
 * Custom deleter for releasing physically allocated NUMA memory.
 */
struct NumaDeleter {
  std::size_t size{0};
  void operator()(void *ptr) const noexcept;
};

/**
 * Throughput Execution Engine managing lock-free queues, NUMA
 * thread pinning, optional io_uring kernel bypass, and zero-copy
 * inter-process communication for the ROOT Bridge architecture.
 */
class Engine {
  // Grant RootRuntime full access to internal state management and telemetry
  // functions
  friend class RootRuntime;

public:
  explicit Engine(bool create_shm);
  explicit Engine(ShmLayout &layout);
  ~Engine();

  // Prevent copy and move operations to enforce thread safety and static
  // lifecycle guarantees
  Engine(const Engine &) = delete;
  Engine &operator=(const Engine &) = delete;
  Engine(Engine &&) noexcept = delete;
  Engine &operator=(Engine &&) noexcept = delete;

  /**
   * Boots scheduler threads, registers worker pools, initializes I/O
   * engines, and configures SHM topology.
   */
  void run();

  /**
   * Terminates processing loops, releases io_uring rings, and
   * flushes pending tasks.
   */
  void stop();

  /**
   * Pins worker or scheduler thread to a specific CPU core.
   */
  void pin_thread_to_cpu(std::thread::native_handle_type handle, int cpu);

  /**
   * Pins the calling thread to a specific CPU core.
   */
  void pin_thread_to_cpu(int cpu);

  /**
   * Primary batch dequeue mechanism for worker threads.
   */
  bool try_dequeue_batch(int node,
                         std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
                         std::size_t &out_batch_size, std::size_t max_batch);

  /**
   * Primary batch dequeue mechanism overload for worker threads.
   */
  bool try_dequeue_batch(int node,
                         std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
                         std::size_t max_batch);

  /**
   * Retrieves the underlying Shared Memory layout descriptor.
   */
  [[nodiscard]] ShmLayout &shm() noexcept { return shm_; }
  [[nodiscard]] const ShmLayout &shm() const noexcept { return shm_; }

  /**
   * Accesses the telemetry Span Ring buffer associated with this Engine.
   */
  [[nodiscard]] log::SpanRing &span_ring() noexcept { return span_ring_; }

private:
  ShmLayout shm_{};
  log::SpanRing span_ring_{}; // Active SHM Span Tracing Ring view
  std::atomic<bool> running_{false};

  int numa_nodes_{1};

  // Capacity exponent 10 = 1024 slots per queue tier
  using PriorityQueue = ShmMpmcRing<ScheduledTask, 10>;

  // Smart pointer supporting custom NUMA memory deallocation
  using NumaPriorityQueuePtr = std::unique_ptr<PriorityQueue, NumaDeleter>;

  std::vector<NumaPriorityQueuePtr> queues_per_node_high_;
  std::vector<NumaPriorityQueuePtr> queues_per_node_normal_;
  std::vector<NumaPriorityQueuePtr> queues_per_node_low_;

  std::jthread scheduler_thread_;
  std::vector<std::jthread> workers_;

  // Performance telemetry counters aligned on cache lines to avoid false
  // sharing
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> last_activity_ns_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> idle_loops_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> busy_loops_{0};

  // Counters isolated per cache line (False sharing protection)
  std::vector<AlignedAtomicCounter> node_hotness_;
  std::vector<AlignedAtomicCounter> queue_hotness_high_;
  std::vector<AlignedAtomicCounter> queue_hotness_normal_;
  std::vector<AlignedAtomicCounter> queue_hotness_low_;

  using JournalRing = ShmMpmcRing<BridgeMessage, 12>;
  std::unique_ptr<JournalRing> journal_ring_;

  std::atomic<float> backpressure_level_{0.0f};

  std::vector<ShmLayout *> cluster_runtimes_;

// Conditional NUMA topology state
#ifdef HAVE_NUMA
  bool using_numa_{true};
#else
  bool using_numa_{false};
#endif

// Conditional I/O Backend (Kernel Bypass vs POSIX / Worker Pool Fallback)
#ifdef HAVE_IO_URING
  struct io_uring ring_{};
  bool using_io_uring_{true};
#else
  bool using_io_uring_{false};
#endif

  // Main event loops accepting std::stop_token for C++20 jthread management
  void scheduler_loop(std::stop_token stop_token);
  void worker_loop(std::stop_token stop_token, int id, int node);

  // Queue ingestion operations
  void enqueue_message(const BridgeMessage &msg,
                       PriorityTier priority = PriorityTier::NORMAL);

  bool try_dequeue_cross_node_lockfree(
      int node, std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
      std::size_t &out_batch_size, std::size_t max_batch);

  // Message and payload internal handlers (via RootRuntime)
  void handle_message(const BridgeMessage &msg);
  void handle_cmd_ping(const BridgeMessage &msg);
  void handle_cmd_open_file(const BridgeMessage &msg);
  void handle_cmd_schema_discover(const BridgeMessage &msg);
  void handle_shm_tensor_ref(
      const BridgeMessage &msg); // Zero-Copy PyTorch Tensor handler

  void process_tensor_pipeline_avx512_sve(const BridgeMessage &msg);

  // Async I/O Subsystem management
  void init_io_backend();
  void shutdown_io_backend();

  [[nodiscard]] int
  choose_node_for_message(const BridgeMessage &msg) const noexcept;

  void init_numa_topology();
  void init_priority_queues();
  void init_memory_pools();
  void shutdown_memory_pools();

  void update_backpressure();
  void prefetch_hot_tasks(int node);
  void journal_message(const BridgeMessage &msg);

  bool try_dequeue_batch_simd(
      int node, std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
      std::size_t &out_batch_size, std::size_t max_batch);

  void
  process_batch_simd(int node,
                     std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
                     std::size_t batch_size);

  // Cluster runtime attach & detach operations (Accessed by RootRuntime)
  void attach_runtime(ShmLayout &layout);
  void detach_runtime(ShmLayout &layout);

  // Memory defragmentation and queue rebalancing (Accessed by RootRuntime)
  void rebalance_numa_queues();
  void heap_defragment_tick();
  bool replay_next_journal_entry(BridgeMessage &msg);

  ShmMemoryPool *message_pool_{nullptr};
  ShmMemoryPool *tensor_pool_{nullptr};
};

} // namespace Sphere