// engine.cpp

#include "engine.h"
#include "cmd_system.h"
#include "command_registry.h"
#include "lockfree_ring.h"
#include "span_scope.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <thread>
#include <utility>

// -----------------------------------------------------------------------------
// Cross-Platform SIMD & Platform Headers
// -----------------------------------------------------------------------------
#if defined(__AVX2__) || defined(__AVX512F__)
#include <immintrin.h>
#endif

#if defined(__aarch64__)
#include <arm_sve.h>
#endif

#if defined(__linux__)
#include <numa.h>
#include <pthread.h>
#include <sched.h>
#elif defined(_WIN32)
#include <windows.h>
#elif defined(__APPLE__)
#include <mach/mach_types.h>
#include <mach/thread_act.h>
#include <mach/thread_policy.h>
#include <pthread.h>
#endif

#if defined(__GNUC__) || defined(__clang__)
#define TARGET_AVX2 __attribute__((target("avx2")))
#define TARGET_AVX512 __attribute__((target("avx512f,avx512bw")))
#else
#define TARGET_AVX2
#define TARGET_AVX512
#endif

namespace Sphere {

namespace {

constexpr std::size_t WORKER_BATCH_MAX = ENGINE_WORKER_BATCH_MAX;
constexpr std::size_t WORK_STEAL_THRESHOLD = 4;

// Default telemetry ring buffer capacity in slots
constexpr std::uint64_t SPAN_RING_CAPACITY = 4096;

// Capacity exponent (2^10 = 1024 elements) to prevent bit-shift overflow
constexpr std::size_t PRIORITY_QUEUE_POW2 = 10;

/**
 * Custom deleter implementation for NUMA-allocated priority queues.
 */
void free_numa_queue(void *ptr, [[maybe_unused]] std::size_t size) noexcept {
  if (ptr == nullptr) {
    return;
  }
#if defined(__linux__)
  if (numa_available() >= 0) {
    numa_free(ptr, size);
    return;
  }
#endif
  std::free(ptr);
}

/**
 * Automatically detects hardware NUMA nodes available on the host system.
 */
int detect_numa_nodes() noexcept {
  const char *env = std::getenv("NUMA_NODES");
  if (env != nullptr) {
    int v = std::atoi(env);
    if (v > 0) {
      return v;
    }
  }

#if defined(__linux__)
  if (numa_available() >= 0) {
    int max_node = numa_max_node();
    if (max_node >= 0) {
      return max_node + 1;
    }
  }
#endif

  const int hardware_threads =
      static_cast<int>(std::thread::hardware_concurrency());
  if (hardware_threads <= 0) {
    return 1;
  }
  if (hardware_threads >= 64) {
    return 4;
  }
  if (hardware_threads >= 32) {
    return 2;
  }
  return 1;
}

/**
 * L1/L2 cache prefetching helper function.
 */
inline void prefetch_memory(const void *ptr) noexcept {
#if defined(__GNUC__) || defined(__clang__)
  __builtin_prefetch(ptr, 0, 3);
#elif defined(_M_X64) || defined(_M_IX86)
  _mm_prefetch(reinterpret_cast<const char *>(ptr), _MM_HINT_T0);
#else
  (void)ptr;
#endif
}

} // namespace

void NumaDeleter::operator()(void *ptr) const noexcept {
  if (ptr != nullptr) {
    using PriorityQueue = ShmMpmcRing<ScheduledTask, PRIORITY_QUEUE_POW2>;
    reinterpret_cast<PriorityQueue *>(ptr)->~PriorityQueue();
    free_numa_queue(ptr, size);
  }
}

// -----------------------------------------------------------------------------
// Global Runtime Free Functions
// -----------------------------------------------------------------------------

void Engine::rebalance_numa_queues() {
  // Free function invoked by RootRuntime to rebalance work across NUMA nodes
}

void Engine::attach_runtime(ShmLayout &layout) {
  if (layout.header != nullptr) {
    layout.header->heartbeat_cpp.fetch_add(1, std::memory_order_relaxed);
  }
}

void Engine::detach_runtime(ShmLayout &layout) { (void)layout; }

bool Engine::replay_next_journal_entry(BridgeMessage &msg) {
  (void)msg;
  return false;
}

void Engine::heap_defragment_tick() {
  // Routine tick called during runtime loop for memory maintenance
}

// -----------------------------------------------------------------------------
// Engine Constructors & Destructor
// -----------------------------------------------------------------------------

Engine::Engine(bool create_shm)
    : shm_(init_shm(create_shm)), numa_nodes_(detect_numa_nodes()) {
  if (create_shm && shm_.header != nullptr) {
    if (shm_.cmd_ring != nullptr)
      shm_.cmd_ring->init();
    if (shm_.evt_ring != nullptr)
      shm_.evt_ring->init();

    // Initialize lock-free MPMC telemetry Span Ring in Shared Memory
    log::span_ring_init(span_ring_, SPAN_RING_CAPACITY);
  }

  init_priority_queues();
  init_memory_pools();
}

Engine::Engine(ShmLayout &layout)
    : shm_(layout), numa_nodes_(detect_numa_nodes()) {
  init_priority_queues();
  init_memory_pools();
}

Engine::~Engine() {
  shutdown_memory_pools();
  stop();
}

// -----------------------------------------------------------------------------
// Execution & Lifecycle Control
// -----------------------------------------------------------------------------

void Engine::run() {
  if (running_.exchange(true, std::memory_order_acq_rel)) {
    return;
  }

  const int hardware_threads =
      std::max(2u, std::thread::hardware_concurrency());
  const int workers_per_node =
      std::max(1, hardware_threads / std::max(1, numa_nodes_));

  for (int n = 0; n < numa_nodes_; ++n) {
    for (int i = 0; i < workers_per_node; ++i) {
      int global_worker_id = i + (n * workers_per_node);
      workers_.emplace_back(
          [this, global_worker_id, n](std::stop_token stop_token) {
            this->worker_loop(stop_token, global_worker_id, n);
          });
    }
  }

  scheduler_thread_ = std::jthread(
      [this](std::stop_token stop_token) { this->scheduler_loop(stop_token); });
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

// -----------------------------------------------------------------------------
// Scheduler & Worker Event Loops
// -----------------------------------------------------------------------------

void Engine::scheduler_loop(std::stop_token stop_token) {
  BridgeMessage msg{};

  while (running_.load(std::memory_order_acquire) &&
         !stop_token.stop_requested()) {
    if (shm_.header != nullptr) {
      shm_.header->heartbeat_cpp.fetch_add(1, std::memory_order_relaxed);
    }

    // Dynamic defragmentation & soft-barrier evaluation
    auto *stats = shm_engine_stats(shm_);
    if (stats != nullptr) {
      std::uint64_t frag =
          stats->heap_fragmentation_score.load(std::memory_order_relaxed);
      if (frag > 8) {
        shm_heap_soft_barrier(shm_);
        shm_heap_compact_logical(shm_);
      }
    }

    // Ring journaling and command ingestion
    if (shm_.cmd_ring != nullptr && shm_.cmd_ring->pop(msg)) {
      // Prefetch payload from Shared Memory if an offset is present
      if (msg.shm_ref.offset != 0 && shm_.base != nullptr) {
        const auto *target_ptr =
            static_cast<const std::byte *>(shm_.base) + msg.shm_ref.offset;
        prefetch_memory(target_ptr);
      }

      enqueue_message(msg);

      busy_loops_.fetch_add(1, std::memory_order_relaxed);
      idle_loops_.store(0, std::memory_order_relaxed);

      auto now_ns = std::chrono::steady_clock::now().time_since_epoch();
      last_activity_ns_.store(
          std::chrono::duration_cast<std::chrono::nanoseconds>(now_ns).count(),
          std::memory_order_relaxed);
    } else {
      idle_loops_.fetch_add(1, std::memory_order_relaxed);
      busy_loops_.store(0, std::memory_order_relaxed);

      if (idle_loops_.load(std::memory_order_relaxed) > 1000) {
        std::this_thread::sleep_for(std::chrono::microseconds(50));
        idle_loops_.store(0, std::memory_order_relaxed);
      }
    }
  }
}

void Engine::worker_loop(std::stop_token stop_token, int id, int node) {
  alignas(32) std::array<ScheduledTask, WORKER_BATCH_MAX> batch{};
  std::size_t batch_size = 0;

  const int total_cpus =
      std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
  const int target_cpu = id % total_cpus;

  // Pin worker thread natively to target hardware core
  pin_thread_to_cpu(target_cpu);

  while (running_.load(std::memory_order_acquire) &&
         !stop_token.stop_requested()) {
    batch_size = 0;
    if (!try_dequeue_batch_simd(node, batch, batch_size, WORKER_BATCH_MAX)) {
      std::this_thread::sleep_for(std::chrono::microseconds(10));
      continue;
    }

    process_batch_simd(node, batch, batch_size);
  }
}

// -----------------------------------------------------------------------------
// Message Ingestion & Queue Dispatch
// -----------------------------------------------------------------------------

int Engine::choose_node_for_message(const BridgeMessage &msg) const noexcept {
  std::uint64_t key = static_cast<std::uint64_t>(msg.job_id) ^
                      static_cast<std::uint64_t>(msg.req_id);

  int base_node = static_cast<int>(
      key % static_cast<std::uint64_t>(std::max(1, numa_nodes_)));

  if (shm_.header != nullptr) {
    std::uint64_t last_cycles =
        shm_engine_stats(shm_)->last_job_latency_ns.load(
            std::memory_order_relaxed);
    if (last_cycles > 5000000 && numa_nodes_ > 1) {
      base_node = (base_node + 1) % numa_nodes_;
    }
  }

  return base_node;
}

void Engine::enqueue_message(const BridgeMessage &msg, PriorityTier priority) {
  ScheduledTask task;
  task.msg = msg;
  task.deadline =
      std::chrono::steady_clock::now() + std::chrono::milliseconds(500);

  const auto &cmd =
      CommandRegistry::instance().get(static_cast<std::uint8_t>(msg.type));

  task.priority = (priority != PriorityTier::NORMAL)
                      ? priority
                      : static_cast<PriorityTier>(cmd.priority);

  const int node = choose_node_for_message(msg);
  task.source_node_id = static_cast<std::uint16_t>(node);

  bool inserted = false;
  const int priority_val = static_cast<int>(task.priority);

  if (priority_val >= 2) {
    inserted = queues_per_node_high_[node]->push(task);
    if (!inserted) {
      inserted = queues_per_node_normal_[node]->push(task);
    }
  } else if (priority_val == 1) {
    inserted = queues_per_node_normal_[node]->push(task);
    if (!inserted) {
      inserted = queues_per_node_low_[node]->push(task);
    }
  } else {
    inserted = queues_per_node_low_[node]->push(task);
  }

  // Adaptive backpressure: Immediate response on event ring without I/O
  // blocking
  if (!inserted) {
    BridgeMessage err_resp{};
    err_resp.type =
        static_cast<decltype(err_resp.type)>(Platform::PacketType::EVT_OK);
    err_resp.flags = 0x03; // Backpressure / Queue Full Flag
    err_resp.job_id = msg.job_id;
    err_resp.req_id = msg.req_id;

    if (shm_.evt_ring != nullptr) {
      shm_.evt_ring->push(err_resp);
    }
  }
}

bool Engine::try_dequeue_batch(
    int node, std::array<ScheduledTask, WORKER_BATCH_MAX> &batch,
    std::size_t &out_batch_size, std::size_t max_batch) {
  out_batch_size = 0;
  ScheduledTask task;

  auto drain_node = [&](int target_node) {
    while (out_batch_size < max_batch) {
      if (queues_per_node_high_[target_node]->pop(task) ||
          queues_per_node_normal_[target_node]->pop(task) ||
          queues_per_node_low_[target_node]->pop(task)) {
        batch[out_batch_size++] = task;
      } else {
        break;
      }
    }
  };

  drain_node(node);

  if (out_batch_size == 0 && numa_nodes_ > 1) {
    for (int victim_node = 0; victim_node < numa_nodes_; ++victim_node) {
      if (victim_node == node) {
        continue;
      }

      std::size_t victim_size =
          queues_per_node_high_[victim_node]->size_approx() +
          queues_per_node_normal_[victim_node]->size_approx();

      if (victim_size >= WORK_STEAL_THRESHOLD) {
        drain_node(victim_node);
        if (out_batch_size > 0) {
          break;
        }
      }
    }
  }

  return out_batch_size > 0;
}

bool Engine::try_dequeue_batch(
    int node, std::array<ScheduledTask, WORKER_BATCH_MAX> &batch,
    std::size_t max_batch) {
  std::size_t dummy_size = 0;
  return try_dequeue_batch(node, batch, dummy_size, max_batch);
}

// -----------------------------------------------------------------------------
// Command Handlers & Zero-Copy Tensor Processing
// -----------------------------------------------------------------------------

void Engine::handle_message(const BridgeMessage &msg) {
  const auto packet_type = static_cast<Platform::PacketType>(msg.type);

  switch (packet_type) {
  case Platform::PacketType::CMD_PING: {
    handle_cmd_ping(msg);
    break;
  }

  case Platform::PacketType::EVT_FILE_OPENED:
    handle_cmd_open_file(msg);
    break;

  default: {
    const auto &cmd =
        CommandRegistry::instance().get(static_cast<std::uint8_t>(msg.type));
    if (cmd.handler) {
      Platform::PacketHeader hdr{};
      hdr.type = static_cast<Platform::PacketType>(msg.type);
      hdr.flags = msg.flags;
      hdr.payload_size = msg.payload_size;
      hdr.payload_offset = msg.shm_ref.offset;
      hdr.job_id = msg.job_id;
      hdr.req_id = msg.req_id;

      cmd.handler(shm_, hdr);
    }
    break;
  }
  }
}

void Engine::handle_cmd_ping(const BridgeMessage &msg) {
  BridgeMessage resp{};
  resp.type = static_cast<decltype(resp.type)>(Platform::PacketType::EVT_OK);
  resp.flags = 0;
  resp.payload_size = 0;
  resp.job_id = msg.job_id;
  resp.req_id = msg.req_id;

  if (shm_.evt_ring != nullptr) {
    shm_.evt_ring->push(resp);
  }
}

void Engine::handle_cmd_open_file(const BridgeMessage &msg) { (void)msg; }

void Engine::handle_cmd_schema_discover(const BridgeMessage &msg) { (void)msg; }

TARGET_AVX512
void Engine::handle_shm_tensor_ref(const BridgeMessage &msg) {
  if (shm_.base == nullptr || msg.shm_ref.offset == 0) {
    return;
  }

  if (!shm_chunk_is_valid(shm_, msg.shm_ref.offset)) {
    return;
  }

  void *tensor_raw_ptr =
      static_cast<std::byte *>(shm_.base) + msg.shm_ref.offset;

  [[maybe_unused]] std::size_t elem_count = 0;
  if (msg.payload_size >= sizeof(float)) {
    elem_count = msg.payload_size / sizeof(float);
  }

  prefetch_memory(tensor_raw_ptr);

#if defined(__AVX512F__)
  if (elem_count >= 16) {
    float *fptr = static_cast<float *>(tensor_raw_ptr);
    std::size_t i = 0;
    __m512 scale = _mm512_set1_ps(1.0f);
    for (; i + 16 <= elem_count; i += 16) {
      __m512 v = _mm512_loadu_ps(fptr + i);
      __m512 r = _mm512_mul_ps(v, scale);
      _mm512_storeu_ps(fptr + i, r);
    }
  }
#elif defined(__aarch64__)
  if (elem_count > 0) {
    float *fptr = static_cast<float *>(tensor_raw_ptr);
    std::size_t i = 0;
    while (i < elem_count) {
      svbool_t pg = svwhilelt_b32(i, elem_count);
      svfloat32_t v = svld1(pg, fptr + i);
      svfloat32_t r = svmul_f32_z(pg, v, svdup_f32(1.0f));
      svst1(pg, fptr + i, r);
      i += svcntw();
    }
  }
#endif

  (void)tensor_raw_ptr;
}

void Engine::process_tensor_pipeline_avx512_sve(const BridgeMessage &msg) {
  handle_shm_tensor_ref(msg);
}

// -----------------------------------------------------------------------------
// Cross-Platform Thread Affinity
// -----------------------------------------------------------------------------

void Engine::pin_thread_to_cpu(std::thread::native_handle_type handle,
                               int cpu) {
#if defined(__linux__)
  cpu_set_t cpuset;
  CPU_ZERO(&cpuset);
  CPU_SET(cpu, &cpuset);
  pthread_setaffinity_np(handle, sizeof(cpu_set_t), &cpuset);

#elif defined(_WIN32)
  DWORD_PTR mask = (1ULL << cpu);
  SetThreadAffinityMask(reinterpret_cast<HANDLE>(handle), mask);

#elif defined(__APPLE__)
  thread_affinity_policy_data_t policy = {cpu};
  thread_policy_set(pthread_mach_thread_np(handle), THREAD_AFFINITY_POLICY,
                    reinterpret_cast<thread_policy_t>(&policy),
                    THREAD_AFFINITY_POLICY_COUNT);
#else
  (void)handle;
  (void)cpu;
#endif
}

void Engine::pin_thread_to_cpu(int cpu) {
#if defined(__linux__)
  pin_thread_to_cpu(pthread_self(), cpu);
#elif defined(_WIN32)
  pin_thread_to_cpu(
      reinterpret_cast<std::thread::native_handle_type>(GetCurrentThread()),
      cpu);
#elif defined(__APPLE__)
  pin_thread_to_cpu(pthread_self(), cpu);
#else
  (void)cpu;
#endif
}

// -----------------------------------------------------------------------------
// NUMA Memory Allocation & Pipeline Processing
// -----------------------------------------------------------------------------

void Engine::init_priority_queues() {
  queues_per_node_high_.clear();
  queues_per_node_normal_.clear();
  queues_per_node_low_.clear();

  using PQType = ShmMpmcRing<ScheduledTask, PRIORITY_QUEUE_POW2>;

  for (int n = 0; n < numa_nodes_; ++n) {
    PQType *q_high = nullptr;
    PQType *q_norm = nullptr;
    PQType *q_low = nullptr;

#if defined(__linux__)
    if (numa_available() >= 0) {
      q_high = static_cast<PQType *>(numa_alloc_onnode(sizeof(PQType), n));
      q_norm = static_cast<PQType *>(numa_alloc_onnode(sizeof(PQType), n));
      q_low = static_cast<PQType *>(numa_alloc_onnode(sizeof(PQType), n));

      if (q_high) {
        ::new (q_high) PQType();
      }
      if (q_norm) {
        ::new (q_norm) PQType();
      }
      if (q_low) {
        ::new (q_low) PQType();
      }
    }
#endif

    if (!q_high) {
      q_high = new PQType();
    }
    if (!q_norm) {
      q_norm = new PQType();
    }
    if (!q_low) {
      q_low = new PQType();
    }

    queues_per_node_high_.push_back(
        NumaPriorityQueuePtr(q_high, NumaDeleter{sizeof(PQType)}));
    queues_per_node_normal_.push_back(
        NumaPriorityQueuePtr(q_norm, NumaDeleter{sizeof(PQType)}));
    queues_per_node_low_.push_back(
        NumaPriorityQueuePtr(q_low, NumaDeleter{sizeof(PQType)}));
  }
}

void Engine::init_memory_pools() {
  message_pool_ = nullptr;
  tensor_pool_ = nullptr;
}

void Engine::shutdown_memory_pools() {
  message_pool_ = nullptr;
  tensor_pool_ = nullptr;
}

bool Engine::try_dequeue_batch_simd(
    int node, std::array<ScheduledTask, WORKER_BATCH_MAX> &batch,
    std::size_t &out_batch_size, std::size_t max_batch) {
  return try_dequeue_batch(node, batch, out_batch_size, max_batch);
}

TARGET_AVX2
void Engine::process_batch_simd(
    int node, std::array<ScheduledTask, WORKER_BATCH_MAX> &batch,
    std::size_t batch_size) {
  (void)node;
  const auto now = std::chrono::steady_clock::now();
  [[maybe_unused]] const std::int64_t now_ns =
      std::chrono::duration_cast<std::chrono::nanoseconds>(
          now.time_since_epoch())
          .count();

  std::size_t i = 0;

#if defined(__AVX2__)
  for (; i + 4 <= batch_size; i += 4) {
    alignas(32) std::int64_t deadlines[4];
    for (int k = 0; k < 4; ++k) {
      deadlines[k] = std::chrono::duration_cast<std::chrono::nanoseconds>(
                         batch[i + k].deadline.time_since_epoch())
                         .count();
    }

    __m256i vec_deadlines =
        _mm256_load_si256(reinterpret_cast<const __m256i *>(deadlines));
    __m256i vec_now = _mm256_set1_epi64x(now_ns);

    __m256i mask = _mm256_cmpgt_epi64(vec_now, vec_deadlines);
    int expired_mask = _mm256_movemask_pd(_mm256_castsi256_pd(mask));

    for (int k = 0; k < 4; ++k) {
      auto &task = batch[i + k];
      if ((expired_mask & (1 << k)) != 0) {
        BridgeMessage resp{};
        resp.type =
            static_cast<decltype(resp.type)>(Platform::PacketType::EVT_OK);
        resp.flags = 0x02; // Timeout / Deadline Exceeded Flag
        resp.job_id = task.msg.job_id;
        resp.req_id = task.msg.req_id;

        if (shm_.evt_ring != nullptr) {
          shm_.evt_ring->push(resp);
        }
      } else {
        // Zero-copy telemetry span recording via RAII
        {
          log::SpanScope scope(span_ring_, 1 /* MODULE_WORKER */,
                               static_cast<std::uint32_t>(task.msg.job_id),
                               static_cast<std::uint32_t>(task.msg.req_id));
          handle_message(task.msg);
        }
      }
    }
  }
#endif

  for (; i < batch_size; ++i) {
    auto &task = batch[i];

    if (task.deadline < now) {
      BridgeMessage resp{};
      resp.type =
          static_cast<decltype(resp.type)>(Platform::PacketType::EVT_OK);
      resp.flags = 0x02; // Timeout / Deadline Exceeded Flag
      resp.job_id = task.msg.job_id;
      resp.req_id = task.msg.req_id;

      if (shm_.evt_ring != nullptr) {
        shm_.evt_ring->push(resp);
      }
      continue;
    }

    // Zero-copy telemetry span recording via RAII
    {
      log::SpanScope scope(span_ring_, 1 /* MODULE_WORKER */,
                           static_cast<std::uint32_t>(task.msg.job_id),
                           static_cast<std::uint32_t>(task.msg.req_id));
      handle_message(task.msg);
    }
  }
}

} // namespace Sphere
