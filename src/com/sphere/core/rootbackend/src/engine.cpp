// engine.cpp

#include "engine.h"
#include "command_registry.h"
#include "logger.h"
#include "span_scope.h"
#include "utils.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>

#if defined(HAVE_NUMA) && defined(__linux__)
#include <numa.h>
#define SPHERE_HAVE_NUMA 1
#else
#define SPHERE_HAVE_NUMA 0
#endif

#if defined(__linux__)
#include <pthread.h>
#include <sched.h>
#elif defined(_WIN32)
#include <windows.h>
#elif defined(__APPLE__)
#include <mach/thread_act.h>
#include <mach/thread_policy.h>
#include <pthread.h>
#endif

namespace Sphere {

namespace {

constexpr std::size_t WORKER_BATCH_MAX = ENGINE_WORKER_BATCH_MAX;
constexpr std::size_t WORK_STEAL_THRESHOLD = 4;
constexpr std::size_t PRIORITY_QUEUE_POW2 = 10;
constexpr auto TASK_DEADLINE = std::chrono::milliseconds(500);

/// Detects the NUMA node count, honouring a NUMA_NODES override.
int detect_numa_nodes() noexcept {
  if (const char *env = std::getenv("NUMA_NODES"); env != nullptr) {
    const int value = std::atoi(env);
    if (value > 0) {
      return value;
    }
  }

#if SPHERE_HAVE_NUMA
  if (::numa_available() >= 0) {
    const int max_node = ::numa_max_node();
    if (max_node >= 0) {
      return max_node + 1;
    }
  }
#endif

  return 1;
}

/// Exponential idle backoff, bounded by IDLE_BACKOFF_MAX_US.
std::uint32_t next_backoff(std::uint32_t current) noexcept {
  const std::uint32_t doubled = current * 2;
  return (doubled > IDLE_BACKOFF_MAX_US) ? IDLE_BACKOFF_MAX_US : doubled;
}

} // namespace

// ============================================================================
// NumaDeleter
// ============================================================================

void NumaDeleter::operator()(void *ptr) const noexcept {
  if (ptr == nullptr) {
    return;
  }

  using PriorityQueue = ShmMpmcRing<ScheduledTask, PRIORITY_QUEUE_POW2>;
  reinterpret_cast<PriorityQueue *>(ptr)->~PriorityQueue();

#if SPHERE_HAVE_NUMA
  if (from_numa) {
    ::numa_free(ptr, size);
    return;
  }
#endif
  ::operator delete(ptr, std::align_val_t{alignof(PriorityQueue)});
}

// ============================================================================
// Construction
// ============================================================================

Engine::Engine(ShmLayout &layout)
    : shm_(&layout), numa_nodes_(detect_numa_nodes()) {
  init_numa_topology();
  init_priority_queues();

  if (shm_->span_ring != nullptr && shm_->header != nullptr) {
    span_ring_ = log::span_ring_view(shm_->span_ring,
                                     shm_->header->size_span_ring,
                                     shm_->header->span_ring_capacity);
  }
}

Engine::~Engine() { stop(); }

void Engine::init_numa_topology() {
#if SPHERE_HAVE_NUMA
  using_numa_ = (::numa_available() >= 0);
#else
  using_numa_ = false;
#endif
  node_hotness_ = std::vector<AlignedAtomicCounter>(
      static_cast<std::size_t>(std::max(1, numa_nodes_)));
}

void Engine::init_priority_queues() {
  queues_per_node_high_.clear();
  queues_per_node_normal_.clear();
  queues_per_node_low_.clear();

  using PQ = ShmMpmcRing<ScheduledTask, PRIORITY_QUEUE_POW2>;

  auto make_queue = [this](int node) -> NumaPriorityQueuePtr {

    (void)this;

    PQ *queue = nullptr;
    bool from_numa = false;

#if SPHERE_HAVE_NUMA
    if (using_numa_) {
      if (void *raw = ::numa_alloc_onnode(sizeof(PQ), node); raw != nullptr) {
        queue = ::new (raw) PQ();
        from_numa = true;
      }
    }
#else
    (void)node;
#endif

    if (queue == nullptr) {
      void *raw = ::operator new(sizeof(PQ), std::align_val_t{alignof(PQ)});
      queue = ::new (raw) PQ();
    }

    queue->init();
    return NumaPriorityQueuePtr(queue, NumaDeleter{sizeof(PQ), from_numa});
  };

  for (int node = 0; node < numa_nodes_; ++node) {
    queues_per_node_high_.push_back(make_queue(node));
    queues_per_node_normal_.push_back(make_queue(node));
    queues_per_node_low_.push_back(make_queue(node));
  }
}

// ============================================================================
// Lifecycle
// ============================================================================

void Engine::run() {
  if (running_.exchange(true, std::memory_order_acq_rel)) {
    return;
  }

  const int hardware_threads =
      std::max(2, static_cast<int>(std::thread::hardware_concurrency()));
  const int workers_per_node =
      std::max(1, hardware_threads / std::max(1, numa_nodes_));

  for (int node = 0; node < numa_nodes_; ++node) {
    for (int i = 0; i < workers_per_node; ++i) {
      const int worker_id = i + (node * workers_per_node);
      workers_.emplace_back([this, worker_id, node](std::stop_token token) {
        worker_loop(std::move(token), worker_id, node);
      });
    }
  }

  scheduler_thread_ = std::jthread(
      [this](std::stop_token token) { scheduler_loop(std::move(token)); });
}

void Engine::stop() {
  if (!running_.exchange(false, std::memory_order_acq_rel)) {
    return;
  }

  if (scheduler_thread_.joinable()) {
    scheduler_thread_.request_stop();
    scheduler_thread_.join();
  }
  for (auto &worker : workers_) {
    if (worker.joinable()) {
      worker.request_stop();
      worker.join();
    }
  }
  workers_.clear();
}

// ============================================================================
// Scheduler
// ============================================================================

void Engine::scheduler_loop(std::stop_token stop_token) {
  BridgeMessage msg{};
  std::uint32_t backoff_us = IDLE_BACKOFF_MIN_US;

  while (running_.load(std::memory_order_acquire) &&
         !stop_token.stop_requested()) {
    if (shm_->header != nullptr) {
      shm_->header->heartbeat_cpp.fetch_add(1, std::memory_order_relaxed);
    }

    if (shm_->cmd_ring != nullptr && shm_->cmd_ring->pop(msg)) {
      if (msg.type == MsgType::SHM_REF && msg.shm_ref.offset != 0 &&
          shm_->base != nullptr) {
        utils::prefetch_read(shm_->base + msg.shm_ref.offset);
      }

      enqueue_message(msg);
      busy_loops_.fetch_add(1, std::memory_order_relaxed);
      backoff_us = IDLE_BACKOFF_MIN_US;

      const auto now = std::chrono::steady_clock::now().time_since_epoch();
      last_activity_ns_.store(
          static_cast<std::uint64_t>(
              std::chrono::duration_cast<std::chrono::nanoseconds>(now).count()),
          std::memory_order_relaxed);
    } else {
      idle_loops_.fetch_add(1, std::memory_order_relaxed);
      std::this_thread::sleep_for(std::chrono::microseconds(backoff_us));
      backoff_us = next_backoff(backoff_us);
    }
  }
}

// ============================================================================
// Workers
// ============================================================================

void Engine::worker_loop(std::stop_token stop_token, int id, int node) {
  alignas(64) std::array<ScheduledTask, WORKER_BATCH_MAX> batch{};
  std::size_t batch_size = 0;
  std::uint32_t backoff_us = IDLE_BACKOFF_MIN_US;

  const int total_cpus =
      std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
  pin_thread_to_cpu(id % total_cpus);

  while (running_.load(std::memory_order_acquire) &&
         !stop_token.stop_requested()) {
    batch_size = 0;
    if (!try_dequeue_batch(node, batch, batch_size, WORKER_BATCH_MAX)) {
      std::this_thread::sleep_for(std::chrono::microseconds(backoff_us));
      backoff_us = next_backoff(backoff_us);
      continue;
    }

    backoff_us = IDLE_BACKOFF_MIN_US;

    const std::uint64_t start = utils::rdtsc();
    process_batch(batch, batch_size);
    log::worker_on_job(static_cast<std::size_t>(id), utils::rdtsc() - start);
  }
}

bool Engine::try_dequeue_batch(
    int node, std::array<ScheduledTask, WORKER_BATCH_MAX> &batch,
    std::size_t &out_batch_size, std::size_t max_batch) {
  out_batch_size = 0;
  if (node < 0 || static_cast<std::size_t>(node) >= queues_per_node_high_.size()) {
    return false;
  }

  ScheduledTask task{};

  auto drain = [&](int target) {
    while (out_batch_size < max_batch) {
      if (queues_per_node_high_[target]->pop(task) ||
          queues_per_node_normal_[target]->pop(task) ||
          queues_per_node_low_[target]->pop(task)) {
        batch[out_batch_size++] = task;
      } else {
        break;
      }
    }
  };

  drain(node);

  if (out_batch_size == 0 && numa_nodes_ > 1) {
    for (int victim = 0; victim < numa_nodes_; ++victim) {
      if (victim == node) {
        continue;
      }
      const std::uint64_t depth =
          queues_per_node_high_[victim]->size_approx() +
          queues_per_node_normal_[victim]->size_approx();
      if (depth >= WORK_STEAL_THRESHOLD) {
        drain(victim);
        if (out_batch_size > 0) {
          break;
        }
      }
    }
  }

  return out_batch_size > 0;
}

// ============================================================================
// Dispatch
// ============================================================================

int Engine::choose_node_for_message(const BridgeMessage &msg) const noexcept {
  const std::uint64_t key = (static_cast<std::uint64_t>(msg.job_id) << 32) ^
                            static_cast<std::uint64_t>(msg.req_id);
  return static_cast<int>(
      utils::numa_hash(key, static_cast<std::size_t>(std::max(1, numa_nodes_))));
}

void Engine::enqueue_message(const BridgeMessage &msg, PriorityTier priority) {
  ScheduledTask task{};
  task.msg = msg;
  task.deadline = std::chrono::steady_clock::now() + TASK_DEADLINE;

  const auto opcode = static_cast<Proto::PacketType>(msg.cmd);

  task.priority = (priority != PriorityTier::NORMAL)
                      ? priority
                      : CommandRegistry::instance().priority_of(opcode);

  // An explicitly urgent packet outranks whatever the table says.
  if ((msg.flags & Proto::PKT_FLAG_URGENT) != 0) {
    task.priority = PriorityTier::HIGH;
  }

  const int node = choose_node_for_message(msg);
  task.source_node_id = static_cast<std::uint16_t>(node);
  node_hotness_[static_cast<std::size_t>(node)].value.fetch_add(
      1, std::memory_order_relaxed);

  bool inserted = false;
  switch (task.priority) {
  case PriorityTier::HIGH:
    inserted = queues_per_node_high_[node]->push(task) ||
               queues_per_node_normal_[node]->push(task);
    break;
  case PriorityTier::NORMAL:
    inserted = queues_per_node_normal_[node]->push(task) ||
               queues_per_node_low_[node]->push(task);
    break;
  case PriorityTier::LOW:
  default:
    inserted = queues_per_node_low_[node]->push(task);
    break;
  }

  if (!inserted) {
    tasks_rejected_.fetch_add(1, std::memory_order_relaxed);
    log::metrics().cmd_ring_drops.fetch_add(1, std::memory_order_relaxed);
    emit_event(Proto::PacketType::EVT_BACKPRESSURE, msg);
  }
}

void Engine::emit_event(Proto::PacketType type, const BridgeMessage &source,
                        std::uint16_t flags) {
  if (shm_->evt_ring == nullptr) {
    return;
  }
  BridgeMessage response{};
  response.type = MsgType::INLINE_DATA;
  response.cmd = static_cast<std::uint16_t>(type);
  response.flags = flags;
  response.job_id = source.job_id;
  response.req_id = source.req_id;

  if (!shm_->evt_ring->push(response)) {
    log::metrics().evt_ring_drops.fetch_add(1, std::memory_order_relaxed);
  }
}

void Engine::handle_message(const BridgeMessage &msg) {
  const auto opcode = static_cast<Proto::PacketType>(msg.cmd);

  // Fast paths the engine owns itself.
  if (opcode == Proto::PacketType::CMD_PING) {
    handle_cmd_ping(msg);
    return;
  }
  if (msg.type == MsgType::SHM_REF) {
    handle_shm_tensor_ref(msg);
  }

  Proto::PacketHeader header{};
  header.type = opcode;
  header.flags = msg.flags;
  // msg.payload_size is a uint8_t: a heap payload carries its real length
  // in shm_ref.total_bytes.
  header.payload_size = (msg.type == MsgType::SHM_REF)
                            ? msg.shm_ref.total_bytes
                            : msg.payload_size;
  header.payload_offset =
      (msg.type == MsgType::SHM_REF) ? msg.shm_ref.offset : 0;
  header.job_id = msg.job_id;
  header.req_id = msg.req_id;

  // An INLINE_DATA message carries its payload inside the 64-byte message,
  std::optional<ScopedChunkWriter> inline_staging;
  if (msg.type == MsgType::INLINE_DATA && msg.payload_size > 0) {
    inline_staging.emplace(*shm_, msg.payload_size);
    if (*inline_staging) {
      std::memcpy(inline_staging->data(), msg.inline_bytes, msg.payload_size);
      inline_staging->commit();
      header.payload_offset = inline_staging->offset();
    } else {
      header.payload_size = 0;
      log::metrics().evt_ring_drops.fetch_add(1, std::memory_order_relaxed);
    }
  }

  if (!CommandRegistry::instance().dispatch(*shm_, header)) {
    if (Proto::is_command(opcode)) {
      emit_event(Proto::PacketType::EVT_ERROR, msg);
    }
    return;
  }

  if (shm_->header != nullptr) {
    shm_->header->jobs_completed.fetch_add(1, std::memory_order_relaxed);
  }
}

void Engine::handle_cmd_ping(const BridgeMessage &msg) {
  emit_event(Proto::PacketType::EVT_PONG, msg);
}

void Engine::handle_shm_tensor_ref(const BridgeMessage &msg) {
  if (shm_->base == nullptr || msg.shm_ref.offset == 0) {
    return;
  }
  if (!shm_chunk_is_valid(*shm_, msg.shm_ref.offset)) {
    return;
  }

  void *tensor = nullptr;
  std::size_t bytes = 0;
  shm_extract_tensor_meta(*shm_, msg, tensor, bytes);
  if (tensor == nullptr || bytes < sizeof(float)) {
    return;
  }

  utils::prefetch_read(tensor);

  // Runtime dispatch on the widest vector unit the CPU actually has.

  utils::tensor_scale(tensor, bytes, 1.0f);
}

void Engine::process_batch(
    std::array<ScheduledTask, WORKER_BATCH_MAX> &batch,
    std::size_t batch_size) {
  const auto now = std::chrono::steady_clock::now();

  for (std::size_t i = 0; i < batch_size; ++i) {
    ScheduledTask &task = batch[i];

    if (task.deadline < now) {
      tasks_expired_.fetch_add(1, std::memory_order_relaxed);
      if (shm_->header != nullptr) {
        shm_->header->jobs_failed.fetch_add(1, std::memory_order_relaxed);
      }
      emit_event(Proto::PacketType::EVT_DEADLINE_EXCEEDED, task.msg);
      continue;
    }

    {
      log::SpanScope scope(span_ring_, log::MODULE_WORKER, task.msg.job_id,
                           task.msg.req_id);
      handle_message(task.msg);
    }
    tasks_dispatched_.fetch_add(1, std::memory_order_relaxed);
  }
}

// ============================================================================
// Thread affinity
// ============================================================================

void Engine::pin_thread_to_cpu(std::thread::native_handle_type handle,
                               int cpu) {
#if defined(__linux__)
  cpu_set_t cpuset;
  CPU_ZERO(&cpuset);
  CPU_SET(static_cast<std::size_t>(cpu), &cpuset);
  (void)::pthread_setaffinity_np(handle, sizeof(cpu_set_t), &cpuset);
#elif defined(_WIN32)
  const DWORD_PTR mask = (static_cast<DWORD_PTR>(1) << cpu);
  ::SetThreadAffinityMask(reinterpret_cast<HANDLE>(handle), mask);
#elif defined(__APPLE__)
  // macOS has no hard affinity; this is only an advisory grouping tag.
  thread_affinity_policy_data_t policy = {cpu};
  ::thread_policy_set(::pthread_mach_thread_np(handle), THREAD_AFFINITY_POLICY,
                      reinterpret_cast<thread_policy_t>(&policy),
                      THREAD_AFFINITY_POLICY_COUNT);
#else
  (void)handle;
  (void)cpu;
#endif
}

void Engine::pin_thread_to_cpu(int cpu) {
#if defined(__linux__) || defined(__APPLE__)
  pin_thread_to_cpu(::pthread_self(), cpu);
#elif defined(_WIN32)
  pin_thread_to_cpu(
      reinterpret_cast<std::thread::native_handle_type>(::GetCurrentThread()),
      cpu);
#else
  (void)cpu;
#endif
}

// ============================================================================
// Cluster and maintenance
// ============================================================================

void Engine::attach_runtime(ShmLayout &layout) {
  if (layout.header != nullptr) {
    layout.header->heartbeat_cpp.fetch_add(1, std::memory_order_relaxed);
  }
}

void Engine::detach_runtime(ShmLayout &layout) {
  if (layout.header != nullptr) {
    layout.header->engine_flags.fetch_and(~0x1u, std::memory_order_relaxed);
  }
}

std::size_t Engine::rebalance_numa_queues() {
  // Move work from the deepest node's normal queue to the shallowest one
  if (numa_nodes_ < 2) {
    return 0;
  }

  int deepest = 0;
  int shallowest = 0;
  std::uint64_t max_depth = 0;
  std::uint64_t min_depth = ~std::uint64_t{0};

  for (int node = 0; node < numa_nodes_; ++node) {
    const std::uint64_t depth =
        queues_per_node_high_[node]->size_approx() +
        queues_per_node_normal_[node]->size_approx() +
        queues_per_node_low_[node]->size_approx();
    if (depth > max_depth) {
      max_depth = depth;
      deepest = node;
    }
    if (depth < min_depth) {
      min_depth = depth;
      shallowest = node;
    }
  }

  if (deepest == shallowest || max_depth < min_depth + WORK_STEAL_THRESHOLD) {
    return 0;
  }

  // Move at most half the difference, so the two nodes cannot oscillate.
  const std::uint64_t to_move = (max_depth - min_depth) / 2;
  std::size_t moved = 0;
  ScheduledTask task{};

  while (moved < to_move && queues_per_node_normal_[deepest]->pop(task)) {
    task.source_node_id = static_cast<std::uint16_t>(shallowest);
    if (!queues_per_node_normal_[shallowest]->push(task)) {
      // The destination filled up; put it back rather than dropping the task.
      (void)queues_per_node_normal_[deepest]->push(task);
      break;
    }
    ++moved;
  }
  return moved;
}

std::size_t Engine::heap_defragment_tick() {
  if (shm_ == nullptr || shm_->data_heap == nullptr) {
    return 0;
  }
  return shm_heap_compact_logical(*shm_);
}

bool Engine::replay_next_journal_entry(BridgeMessage &msg) {
  if (shm_ == nullptr || shm_->tx_log == nullptr) {
    return false;
  }
  if (!shm_journal_replay_next(*shm_, journal_replay_index_, msg)) {
    return false;
  }
  ++journal_replay_index_;
  return true;
}

} // namespace Sphere
