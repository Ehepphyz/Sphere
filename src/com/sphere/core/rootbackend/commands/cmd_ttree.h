// commands/cmd_ttree.h

#ifndef SPHERE_CMD_TTREE_H
#define SPHERE_CMD_TTREE_H

#include "lockfree_ring.h"
#include "packets.h"
#include "shm_layout.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

class TTree;
class TBranch;

namespace Sphere::cmd::ttree {

/**
* Status codes returned by TTree command handlers across IPC operations.
*/
enum class ResponseStatus : std::uint16_t {
  OK = 0,
  ERROR_GENERIC = 1,
  ERROR_NO_TREE = 2,
  ERROR_NO_BRANCH = 3,
  ERROR_INVALID_ARG = 4,
  ERROR_INVALID_FORMULA = 5,
  ERROR_SHM_OOM = 6,
  ERROR_ALREADY_REGISTERED = 7
};

/**
 * Binds a tree to a job id, so every tree command that follows finds it.
 *
 * `request` names it as "<file>\t<tree>", the file being an id or a name from
 * the open-file list and the tree a path inside it. Nothing used to create these
 * bindings, which is why the tree commands all answered that they had no tree.
 */
bool attach_tree(std::uint32_t job_id, std::string_view request);

/**
 * The trees a file holds, one path per line, empty when it holds none.
 *
 * A refusal that only carries a status code leaves nothing to act on; this is
 * what turns it into a sentence naming what the file actually contains.
 */
[[nodiscard]] std::string list_trees(std::uint32_t file_id);

void register_tree_handle(std::uint32_t job_id, TTree *tree);

void unregister_tree_handle(std::uint32_t job_id);

[[nodiscard]] bool has_tree(std::uint32_t job_id);

[[nodiscard]] TTree *get_tree(std::uint32_t job_id);

void shutdown_thread_pool();

void record_and_prefetch_access(std::uint32_t job_id,
                                std::string_view branch_name);

void export_branch_zero_copy(std::uint32_t job_id, std::string_view branch_name,
                             std::int64_t entry, void *shm_buffer);

/**
* Dynamically compiles C++ selection expressions for high-performance JIT
* execution
*/
bool register_and_compile_jit_filter(std::string_view function_name,
                                     std::string_view cpp_code);

/**
* Executes a precompiled JIT filter over a tree column
*/
void execute_jit_filter_on_tree(std::uint32_t job_id,
                                std::string_view function_name,
                                std::string_view branch_name,
                                std::vector<float> &out_filtered_results);

void process_tree_by_clusters(
    std::uint32_t job_id,
    const std::function<void(std::int64_t start_entry, std::int64_t end_entry)>
        &cluster_task);


void export_column_to_arrow(void *shm_buffer, const float *data_ptr,
                            std::size_t count, const char *column_name);

void export_zero_copy_branch_to_arrow(void *shm_buffer, TBranch *branch,
                                      std::int64_t entry, std::size_t count,
                                      const char *column_name);


[[nodiscard]] std::string escape_json(const char *s);

/**
 * Answers a request on the event ring.
 *
 * The defaults live here and nowhere else: the same function was declared twice,
 * once with them and once without, so whether a call with fewer arguments
 * compiled depended on the order a translation unit happened to include the two
 * headers in.
 */
void send_response(ShmLayout &shm, const Proto::PacketHeader &req,
                   Proto::PacketType type, std::uint16_t flags = 0,
                   std::uint32_t payload_size = 0,
                   ResponseStatus status = ResponseStatus::OK,
                   std::uint64_t payload_offset = 0,
                   ShmDType dtype = ShmDType::UInt8);

// ============================================================================
// Packet Handler Signatures
// ============================================================================
void handle_inspect(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);
void handle_query_entries(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);
void handle_scan_branches(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);
void handle_get_entry(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);
void handle_read_column(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);
void handle_compute_stats(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);
void handle_apply_filter(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Installs the handlers above into the process-wide CommandRegistry.
void register_all();

} // namespace Sphere::cmd::ttree

#endif // SPHERE_CMD_TTREE_H
