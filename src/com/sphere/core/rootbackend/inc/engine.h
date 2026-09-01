// engine.h

// Execution engine: NUMA-partitioned priority queues, pinned workers, and
// command dispatch.

#pragma once

#include "command_registry.h"
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

namespace Sphere {

/**
 * Scheduling tier
 */
using PriorityTier = TaskPriority;

/**
 * A message queued for execution
 */
struct ScheduledTask {
  BridgeMessage msg{};
  std::chrono::steady_clock::time_point deadline{};
  PriorityTier priority{PriorityTier::NORMAL};
  std::uint16_t source_node_id{0};
};

static_assert(std::is_trivially_copyable_v<ScheduledTask>,
              "ScheduledTask must be trivially copyable for the ring.");

/// Cache-line aligned counter, to keep unrelated hot counters apart.
struct alignas(CACHE_LINE_SIZE) AlignedAtomicCounter {
  std::atomic<std::uint64_t> value{0};
};

/**
 * Releases a NUMA-allocated priority queue
 */
struct NumaDeleter {
  std::size_t size{0};
  bool from_numa{false};

  void operator()(void *ptr) const noexcept;
};

/**
 * Throughput engine
 */
class Engine {
  friend class RootRuntime;

public:
  explicit Engine(ShmLayout &layout);
  ~Engine();

  Engine(const Engine &) = delete;
  Engine &operator=(const Engine &) = delete;
  Engine(Engine &&) noexcept = delete;
  Engine &operator=(Engine &&) noexcept = delete;

  /// Starts the scheduler and the worker pool. Idempotent.
  void run();

  /// Stops every loop and joins. Idempotent.
  void stop();

  [[nodiscard]] bool is_running() const noexcept {
    return running_.load(std::memory_order_acquire);
  }

  /// Pins a thread by native handle.
  void pin_thread_to_cpu(std::thread::native_handle_type handle, int cpu);

  /// Pins the calling thread.
  void pin_thread_to_cpu(int cpu);

  /// Drains up to `max_batch` tasks for `node`, stealing from peers if idle.
  bool try_dequeue_batch(int node,
                         std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
                         std::size_t &out_batch_size, std::size_t max_batch);

  [[nodiscard]] ShmLayout &shm() noexcept { return *shm_; }
  [[nodiscard]] const ShmLayout &shm() const noexcept { return *shm_; }

  [[nodiscard]] log::SpanRing &span_ring() noexcept { return span_ring_; }

  [[nodiscard]] int numa_nodes() const noexcept { return numa_nodes_; }

  /// Number of worker threads currently running.
  [[nodiscard]] std::size_t worker_count() const noexcept {
    return workers_.size();
  }

  /// Enqueues a message. Public so a peer runtime can inject work.
  void enqueue_message(const BridgeMessage &msg,
                       PriorityTier priority = PriorityTier::NORMAL);

  /// Executes one message inline, on the calling thread.
  void handle_message(const BridgeMessage &msg);

private:
  ShmLayout *shm_{nullptr};
  log::SpanRing span_ring_{};
  std::atomic<bool> running_{false};

  int numa_nodes_{1};

  using PriorityQueue = ShmMpmcRing<ScheduledTask, 10>;
  using NumaPriorityQueuePtr = std::unique_ptr<PriorityQueue, NumaDeleter>;

  std::vector<NumaPriorityQueuePtr> queues_per_node_high_;
  std::vector<NumaPriorityQueuePtr> queues_per_node_normal_;
  std::vector<NumaPriorityQueuePtr> queues_per_node_low_;

  std::jthread scheduler_thread_;
  std::vector<std::jthread> workers_;

  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> last_activity_ns_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> idle_loops_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> busy_loops_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> tasks_dispatched_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> tasks_expired_{0};
  alignas(CACHE_LINE_SIZE) std::atomic<std::uint64_t> tasks_rejected_{0};

  std::vector<AlignedAtomicCounter> node_hotness_;

  bool using_numa_{false};

  void scheduler_loop(std::stop_token stop_token);
  void worker_loop(std::stop_token stop_token, int id, int node);

  [[nodiscard]] int
  choose_node_for_message(const BridgeMessage &msg) const noexcept;

  void handle_cmd_ping(const BridgeMessage &msg);
  void handle_shm_tensor_ref(const BridgeMessage &msg);

  void emit_event(Proto::PacketType type, const BridgeMessage &source,
                  std::uint16_t flags = 0);

  void init_numa_topology();
  void init_priority_queues();

  void process_batch(std::array<ScheduledTask, ENGINE_WORKER_BATCH_MAX> &batch,
                     std::size_t batch_size);

  // Cluster attach/detach, driven by RootRuntime.
  void attach_runtime(ShmLayout &layout);
  void detach_runtime(ShmLayout &layout);

  /// Rebalances queue depth across NUMA nodes. Returns tasks moved.
  std::size_t rebalance_numa_queues();

  /// Runs one maintenance step on the heap. Returns chunks reclaimed.
  std::size_t heap_defragment_tick();

  /// Replays the next unprocessed journal entry. Returns false when caught up.
  bool replay_next_journal_entry(BridgeMessage &msg);

  std::uint64_t journal_replay_index_{0};
};

} // namespace Sphere
