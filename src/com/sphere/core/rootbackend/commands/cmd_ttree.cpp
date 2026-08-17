// commands/cmd_ttree.cpp

#include "cmd_ttree.h"
#include "TTreehandlers/ttree_common.h"
#include "lockfree_ring.h"

#include <ROOT/RVec.hxx>
#include <ROOT/TProcessExecutor.hxx>
#include <TBranch.h>
#include <TFile.h>
#include <TInterpreter.h>
#include <TROOT.h>
#include <TTree.h>
#include <TTreeCache.h>
#include <TTreeReader.h>
#include <TTreeReaderArray.h>
#include <TTreeReaderValue.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <queue>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace Sphere::cmd::ttree {

namespace {

// ============================================================================
// 1. ThreadPool (C++20 std::jthread Aligned)
// ============================================================================
class SimpleThreadPool {
public:
  explicit SimpleThreadPool(
      std::size_t threads = std::max(1u, std::thread::hardware_concurrency())) {
    workers_.reserve(threads);
    for (std::size_t i = 0; i < threads; ++i) {
      workers_.emplace_back([this](std::stop_token st) {
        while (!st.stop_requested()) {
          std::function<void()> task;
          {
            std::unique_lock<std::mutex> lock(queue_mutex_);
            cv_.wait(lock, [this, &st] {
              return stop_ || !tasks_.empty() || st.stop_requested();
            });

            if ((stop_ || st.stop_requested()) && tasks_.empty()) {
              return;
            }

            task = std::move(tasks_.front());
            tasks_.pop();
          }
          if (task) {
            task();
          }
        }
      });
    }
  }

  ~SimpleThreadPool() { shutdown(); }

  void shutdown() {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (stop_) {
        return;
      }
      stop_ = true;
    }
    cv_.notify_all();
    for (auto &worker : workers_) {
      if (worker.joinable()) {
        worker.request_stop();
      }
    }
  }

  void enqueue(std::function<void()> task) {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (stop_) {
        return;
      }
      tasks_.push(std::move(task));
    }
    cv_.notify_one();
  }

private:
  std::vector<std::jthread> workers_;
  std::queue<std::function<void()>> tasks_;
  std::mutex queue_mutex_;
  std::condition_variable cv_;
  bool stop_{false};
};

static SimpleThreadPool g_thread_pool;

// ============================================================================
// 2. Advanced Context & Native Cache Structures
// ============================================================================
struct AccessHistory {
  std::unordered_map<std::string, std::uint64_t> branch_access_counts;
  std::string last_accessed_branch;
};

struct TreeContext {
  TTree *tree{nullptr};
  AccessHistory history;
  mutable std::shared_mutex context_mutex;

  std::atomic<std::uint64_t> total_queries{0};
  std::atomic<std::uint64_t> prefetch_hits{0};
  std::atomic<std::uint64_t> total_latency_ns{0};

  // Native ROOT Cache handle
  TTreeCache *native_cache{nullptr};
  std::unordered_set<std::string> active_cached_branches;
  mutable std::shared_mutex cache_mutex;

  // Zero-Copy TTreeReader Engine
  std::unique_ptr<TTreeReader> reader{nullptr};
};

static std::unordered_map<std::uint32_t, std::shared_ptr<TreeContext>> g_trees;
static std::shared_mutex g_trees_mutex;
static std::once_flag g_imt_init_flag;

/**
 * Initializes global ROOT Implicit Multi-Threading once across the process
 * lifetime.
 */
void ensure_root_imt_enabled() {
  std::call_once(g_imt_init_flag, []() {
    ROOT::EnableImplicitMT();
    std::cout << "[CmdTTree] ROOT Implicit Multi-Threading (IMT) successfully "
                 "enabled.\n";
  });
}

} // anonymous namespace

// ============================================================================
// 3. Apache Arrow C Data Interface Exposer & Zero-Copy Helpers
// ============================================================================

struct ArrowSchemaBridge {
  const char *format;
  const char *name;
  const char *metadata;
  std::int64_t flags;
  std::int64_t n_children;
  ArrowSchemaBridge **children;
  ArrowSchemaBridge *dictionary;
  void (*release)(ArrowSchemaBridge *);
  void *private_data;
};

struct ArrowArrayBridge {
  std::int64_t length;
  std::int64_t null_count;
  std::int64_t offset;
  std::int64_t n_buffers;
  std::int64_t n_children;
  const void **buffers;
  ArrowArrayBridge **children;
  ArrowArrayBridge *dictionary;
  void (*release)(ArrowArrayBridge *);
  void *private_data;
};

static void release_arrow_schema(ArrowSchemaBridge *schema) {
  if (!schema || !schema->release) {
    return;
  }
  schema->release = nullptr;
}

static void release_arrow_array(ArrowArrayBridge *array) {
  if (!array || !array->release) {
    return;
  }
  array->release = nullptr;
}

/**
 * Generic scalar column exporter to Apache Arrow layout.
 */
template <typename T>
void export_numeric_column_to_arrow(void *shm_buffer, const T *data_ptr,
                                    std::size_t count, const char *column_name,
                                    const char *format) {
  if (!shm_buffer || !data_ptr) {
    return;
  }

  auto *schema = reinterpret_cast<ArrowSchemaBridge *>(shm_buffer);
  auto *array = reinterpret_cast<ArrowArrayBridge *>(
      reinterpret_cast<std::uint8_t *>(shm_buffer) + sizeof(ArrowSchemaBridge));

  schema->format = format;
  schema->name = column_name;
  schema->metadata = nullptr;
  schema->flags = 0;
  schema->n_children = 0;
  schema->children = nullptr;
  schema->dictionary = nullptr;
  schema->release = &release_arrow_schema;
  schema->private_data = nullptr;

  array->length = static_cast<std::int64_t>(count);
  array->null_count = 0;
  array->offset = 0;
  array->n_buffers = 2;
  array->n_children = 0;

  const void **buffers = reinterpret_cast<const void **>(
      reinterpret_cast<std::uint8_t *>(array) + sizeof(ArrowArrayBridge));
  buffers[0] = nullptr; // Null bitmap (all elements valid)
  buffers[1] = data_ptr;

  array->buffers = buffers;
  array->children = nullptr;
  array->dictionary = nullptr;
  array->release = &release_arrow_array;
  array->private_data = nullptr;
}

void export_column_to_arrow(void *shm_buffer, const float *data_ptr,
                            std::size_t count, const char *column_name) {
  export_numeric_column_to_arrow<float>(shm_buffer, data_ptr, count,
                                        column_name, "f");
}

void export_zero_copy_branch_to_arrow(void *shm_buffer, TBranch *branch,
                                      std::int64_t entry, std::size_t count,
                                      const char *column_name) {
  if (!branch) {
    return;
  }
  branch->GetEntry(entry);
  const float *raw_basket_ptr =
      reinterpret_cast<const float *>(branch->GetAddress());
  export_numeric_column_to_arrow<float>(shm_buffer, raw_basket_ptr, count,
                                        column_name, "f");
}

template <typename T>
void export_vector_branch_to_arrow(void *shm_buffer,
                                   const std::vector<T> &vec_data,
                                   const char *column_name) {
  if (!shm_buffer) {
    return;
  }

  auto *schema = reinterpret_cast<ArrowSchemaBridge *>(shm_buffer);
  auto *array = reinterpret_cast<ArrowArrayBridge *>(
      reinterpret_cast<std::uint8_t *>(shm_buffer) + sizeof(ArrowSchemaBridge));

  schema->format = "+l"; // Arrow List format code
  schema->name = column_name;
  schema->metadata = nullptr;
  schema->flags = 0;
  schema->n_children = 1;
  schema->release = &release_arrow_schema;

  array->length = 1; // 1 event entry containing 'N' elements
  array->null_count = 0;
  array->offset = 0;
  array->n_buffers = 2;
  array->n_children = 1;

  const void **buffers = reinterpret_cast<const void **>(
      reinterpret_cast<std::uint8_t *>(array) + sizeof(ArrowArrayBridge));

  // Contiguous allocation for array offsets
  auto *offsets = reinterpret_cast<std::int32_t *>(
      reinterpret_cast<std::uint8_t *>(buffers) + (2 * sizeof(void *)));
  offsets[0] = 0;
  offsets[1] = static_cast<std::int32_t>(vec_data.size());

  buffers[0] = nullptr;
  buffers[1] = offsets;

  array->buffers = buffers;
  array->release = &release_arrow_array;
}

// ============================================================================
// 4. Lifecycle Management & Registration
// ============================================================================

void register_tree(std::uint32_t job_id, TTree *tree) {
  register_tree_handle(job_id, tree);
}

void register_tree_handle(std::uint32_t job_id, TTree *tree) {
  std::unique_lock<std::shared_mutex> lock(g_trees_mutex);

  if (g_trees.find(job_id) != g_trees.end()) {
    std::cout << "[CmdTTree] Warning: job_id already registered: " << job_id
              << "\n";
  }

  if (!tree) {
    std::cout << "[CmdTTree] Error: null TTree pointer for job_id: " << job_id
              << "\n";
    return;
  }

  // Enable Implicit Multi-Threading for background basket decompression
  ensure_root_imt_enabled();

  auto ctx = std::make_shared<TreeContext>();
  ctx->tree = tree;

  // Level 3 Optimization: Basket Layout & CPU Cache Line Alignment
  constexpr std::int64_t kMaxMemoryBuffer =
      100 * 1024 * 1024; // 100 MB max buffer
  tree->OptimizeBaskets(kMaxMemoryBuffer, 1.1, "d");
  std::cout << "[CmdTTree] Optimized tree basket layout for L3/CPU cache "
               "alignment.\n";

  // Level 2 Optimization: Asynchronous Double-Buffered Prefetching
  constexpr std::int64_t kCacheSizeBytes =
      64 * 1024 * 1024; // 64 MB Async Window
  tree->SetCacheSize(kCacheSizeBytes);
  tree->SetCacheLearnEntries(10); // Auto-learn access pattern after 10 reads

  TFile *parent_file = tree->GetCurrentFile();
  if (parent_file) {
    ctx->native_cache =
        dynamic_cast<TTreeCache *>(tree->GetReadCache(parent_file, true));
    if (ctx->native_cache) {
      ctx->native_cache->SetLearnEntries(10);
      std::cout << "[CmdTTree] Async TTreeCache prefetching activated on "
                   "parent file.\n";
    }
  }

  // Initialize TTreeReader for zero-copy operations
  ctx->reader = std::make_unique<TTreeReader>(tree);

  g_trees[job_id] = ctx;
  std::cout << "[CmdTTree] Registered TTree context with advanced features for "
               "job_id: "
            << job_id << "\n";
}

void unregister_tree(std::uint32_t job_id) { unregister_tree_handle(job_id); }

void unregister_tree_handle(std::uint32_t job_id) {
  std::unique_lock<std::shared_mutex> lock(g_trees_mutex);
  auto it = g_trees.find(job_id);
  if (it != g_trees.end()) {
    if (it->second && it->second->tree) {
      it->second->tree->SetCacheSize(0); // Safely detach read cache
    }
    g_trees.erase(it);
  }
  std::cout << "[CmdTTree] Unregistered TTree context for job_id: " << job_id
            << "\n";
}

bool has_tree(std::uint32_t job_id) {
  std::shared_lock<std::shared_mutex> lock(g_trees_mutex);
  return g_trees.find(job_id) != g_trees.end();
}

TTree *get_tree(std::uint32_t job_id) {
  std::shared_lock<std::shared_mutex> lock(g_trees_mutex);
  auto it = g_trees.find(job_id);
  if (it == g_trees.end() || !it->second || !it->second->tree) {
    return nullptr;
  }
  return it->second->tree;
}

void shutdown_thread_pool() { g_thread_pool.shutdown(); }

// ============================================================================
// 5. Speculative Branch Prefetching & Hit Tracking
// ============================================================================

void record_and_prefetch_access(std::uint32_t job_id,
                                std::string_view branch_name) {
  const std::string b_name(branch_name);
  std::shared_ptr<TreeContext> ctx;
  {
    std::shared_lock<std::shared_mutex> lock(g_trees_mutex);
    auto it = g_trees.find(job_id);
    if (it == g_trees.end()) {
      return;
    }
    ctx = it->second;
  }

  auto start = std::chrono::steady_clock::now();

  // 1. Check if requested branch is already cached in native TTreeCache
  {
    std::shared_lock<std::shared_mutex> cache_lock(ctx->cache_mutex);
    if (ctx->active_cached_branches.contains(b_name)) {
      ctx->prefetch_hits.fetch_add(1, std::memory_order_relaxed);
    }
  }

  // 2. Fast statistics update under minimal locking
  std::unordered_map<std::string, std::uint64_t> counts_snapshot;
  {
    std::unique_lock<std::shared_mutex> ctx_lock(ctx->context_mutex);
    ctx->total_queries.fetch_add(1, std::memory_order_relaxed);
    ctx->history.branch_access_counts[b_name]++;
    ctx->history.last_accessed_branch = b_name;
    counts_snapshot = ctx->history.branch_access_counts; // Copy snapshot
  }

  // 3. Off-lock access pattern sorting
  std::vector<std::pair<std::string, std::uint64_t>> sorted_branches(
      counts_snapshot.begin(), counts_snapshot.end());
  std::sort(sorted_branches.begin(), sorted_branches.end(),
            [](const auto &a, const auto &b) { return a.second > b.second; });

  const std::uint64_t total_q =
      ctx->total_queries.load(std::memory_order_relaxed);
  const std::uint64_t hits = ctx->prefetch_hits.load(std::memory_order_relaxed);
  const double hit_ratio =
      (total_q > 0) ? static_cast<double>(hits) / static_cast<double>(total_q)
                    : 0.0;
  const std::uint64_t threshold =
      (hit_ratio < 0.2) ? 1 : (hit_ratio < 0.5 ? 2 : 3);

  std::vector<std::string> candidates;
  for (const auto &entry : sorted_branches) {
    if (entry.first != b_name && entry.second >= threshold) {
      candidates.push_back(entry.first);
      if (candidates.size() >= 3) {
        break;
      }
    }
  }

  auto end = std::chrono::steady_clock::now();
  auto dur =
      std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count();
  ctx->total_latency_ns.fetch_add(static_cast<std::uint64_t>(dur),
                                  std::memory_order_relaxed);

  // 4. Asynchronous branch registration into TTreeCache
  if (!candidates.empty()) {
    g_thread_pool.enqueue([candidates, ctx]() {
      std::unique_lock<std::shared_mutex> io_lock(ctx->context_mutex);
      if (!ctx->tree) {
        return;
      }

      for (const auto &prefetch_candidate : candidates) {
        {
          std::shared_lock<std::shared_mutex> cache_lock(ctx->cache_mutex);
          if (ctx->active_cached_branches.contains(prefetch_candidate)) {
            continue; // Already registered in TTreeCache
          }
        }

        TBranch *branch = ctx->tree->GetBranch(prefetch_candidate.c_str());
        if (!branch) {
          continue;
        }

        if (ctx->native_cache) {
          ctx->native_cache->AddBranch(branch, kTRUE);
        }

        // Touch entry to populate memory basket
        branch->GetEntry(0);

        {
          std::unique_lock<std::shared_mutex> cache_lock(ctx->cache_mutex);
          ctx->active_cached_branches.insert(prefetch_candidate);
        }
      }
    });
  }
}

// ============================================================================
// 6. Zero-Copy Memory Access via TTreeReaderArray
// ============================================================================

void export_branch_zero_copy(std::uint32_t job_id, std::string_view branch_name,
                             std::int64_t entry, void *shm_buffer) {
  const std::string b_name(branch_name);
  std::shared_ptr<TreeContext> ctx;
  {
    std::shared_lock<std::shared_mutex> lock(g_trees_mutex);
    auto it = g_trees.find(job_id);
    if (it == g_trees.end()) {
      return;
    }
    ctx = it->second;
  }

  std::unique_lock<std::shared_mutex> lock(ctx->context_mutex);
  if (!ctx->reader) {
    return;
  }

  // Read vector data without redundant intermediate copies
  TTreeReaderArray<float> array_reader(*ctx->reader, b_name.c_str());
  ctx->reader->SetEntry(entry);

  const std::size_t count = array_reader.GetSize();
  if (count == 0) {
    return;
  }

  std::vector<float> data_buffer;
  data_buffer.reserve(count);
  for (std::size_t i = 0; i < count; ++i) {
    data_buffer.push_back(array_reader[i]);
  }

  // Expose directly via Apache Arrow schema
  export_numeric_column_to_arrow<float>(shm_buffer, data_buffer.data(), count,
                                        b_name.c_str(), "f");
}

// ============================================================================
// 7. Dynamic JIT Compilation & Execution (Cling / gInterpreter)
// ============================================================================

bool register_and_compile_jit_filter(std::string_view function_name,
                                     std::string_view cpp_code) {
  const std::string fn_name(function_name);
  const std::string code(cpp_code);

  bool success = gInterpreter->Declare(code.c_str());
  if (!success) {
    std::cout << "[CmdTTree JIT] Error: Failed to compile JIT function: "
              << fn_name << "\n";
    return false;
  }

  std::cout << "[CmdTTree JIT] Successfully JIT-compiled C++ function: "
            << fn_name << "\n";
  return true;
}

void execute_jit_filter_on_tree(std::uint32_t job_id,
                                std::string_view function_name,
                                std::string_view branch_name,
                                std::vector<float> &out_filtered_results) {
  const std::string fn_name(function_name);
  const std::string b_name(branch_name);

  std::shared_ptr<TreeContext> ctx;
  {
    std::shared_lock<std::shared_mutex> lock(g_trees_mutex);
    auto it = g_trees.find(job_id);
    if (it == g_trees.end()) {
      return;
    }
    ctx = it->second;
  }

  std::unique_lock<std::shared_mutex> lock(ctx->context_mutex);
  if (!ctx->tree) {
    return;
  }

  // Resolve JIT-compiled function pointer via gInterpreter
  auto filter_func = reinterpret_cast<bool (*)(float)>(
      gInterpreter->ProcessLine((fn_name + ";").c_str()));
  if (!filter_func) {
    return;
  }

  TTreeReader reader(ctx->tree);
  TTreeReaderValue<float> val(reader, b_name.c_str());

  out_filtered_results.clear();
  out_filtered_results.reserve(ctx->tree->GetEntries());

  // High-performance filter loop using JIT-compiled function pointer
  while (reader.Next()) {
    float v = *val;
    if (filter_func(v)) {
      out_filtered_results.push_back(v);
    }
  }
}

// ============================================================================
// 8. Cluster-Aware Parallel Tree Processing
// ============================================================================

void process_tree_by_clusters(
    std::uint32_t job_id,
    const std::function<void(std::int64_t start_entry, std::int64_t end_entry)>
        &cluster_task) {
  std::shared_ptr<TreeContext> ctx;
  {
    std::shared_lock<std::shared_mutex> lock(g_trees_mutex);
    auto it = g_trees.find(job_id);
    if (it == g_trees.end()) {
      return;
    }
    ctx = it->second;
  }

  std::unique_lock<std::shared_mutex> lock(ctx->context_mutex);
  if (!ctx->tree) {
    return;
  }

  const std::int64_t total_entries = ctx->tree->GetEntries();
  if (total_entries <= 0) {
    return;
  }

  // Obtain the cluster iterator from TTree starting at entry 0
  auto cluster_iter = ctx->tree->GetClusterIterator(0);
  std::int64_t start_entry = cluster_iter();

  while (start_entry < total_entries) {
    // Advancing the iterator returns the start entry of the NEXT cluster
    const std::int64_t next_start = cluster_iter();
    std::int64_t end_entry =
        (next_start > start_entry) ? next_start : total_entries;

    // Fallback: Ensure forward progress if cluster boundaries cannot be
    // resolved
    if (end_entry <= start_entry) {
      end_entry = std::min<std::int64_t>(start_entry + 10000, total_entries);
    }

    // Dispatch batch work to worker pool
    g_thread_pool.enqueue([cluster_task, start_entry, end_entry]() {
      cluster_task(start_entry, end_entry);
    });

    start_entry = end_entry;
  }
}

// ============================================================================
// 9. JSON Utilities
// ============================================================================

std::string escape_json(const char *s) {
  std::string out;
  if (!s) {
    return out;
  }
  out.reserve(std::strlen(s) + 16);
  while (*s) {
    const unsigned char c = static_cast<unsigned char>(*s++);
    switch (c) {
    case '\"':
      out += "\\\"";
      break;
    case '\\':
      out += "\\\\";
      break;
    case '\n':
      out += "\\n";
      break;
    case '\r':
      out += "\\r";
      break;
    case '\t':
      out += "\\t";
      break;
    default:
      if (c < 0x20) {
        char buf[7];
        std::snprintf(buf, sizeof(buf), "\\u%04x", c);
        out += buf;
      } else {
        out += static_cast<char>(c);
      }
      break;
    }
  }
  return out;
}

// ============================================================================
// 10. Resilient Response Sender
// ============================================================================

void send_response(ShmLayout &shm, const Platform::PacketHeader &req,
                   Platform::PacketType type, std::uint16_t flags,
                   std::uint32_t payload_size, ResponseStatus status) {
  if (!shm.evt_ring) {
    return;
  }

  BridgeMessage msg{};
  msg.type = MsgType::INLINE_DATA;
  msg.flags = flags;
  msg.payload_size = static_cast<std::uint8_t>(
      std::min<std::uint32_t>(payload_size, sizeof(msg.inline_bytes)));
  msg.job_id = req.job_id;
  msg.req_id = req.req_id;

  msg.inline_bytes[0] = static_cast<std::uint8_t>(type);
  msg.inline_bytes[1] = static_cast<std::uint8_t>(status);

  constexpr int max_retries = 100;
  bool pushed = false;

  for (int retry = 0; retry < max_retries; ++retry) {
    if (shm.evt_ring->push(msg)) {
      pushed = true;
      break;
    }
    std::this_thread::yield();
  }

  if (!pushed) {
    std::cout
        << "[CmdTTree] Error: Event ring buffer full after maximum retries. "
           "Dropped response packet for job_id: "
        << req.job_id << "\n";
  }
}

} // namespace Sphere::cmd::ttree
