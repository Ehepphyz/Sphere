// commands/cmd_ntuple.h

#ifndef SPHERE_CMD_NTUPLE_H
#define SPHERE_CMD_NTUPLE_H

#include "packets.h"
#include "shm_layout.h"

#include <cstdint>
#include <string>
#include <string_view>

namespace Sphere::cmd::ntuple {

/**
 * Binds an RNTuple to a job id, so the commands that follow find it.
 *
 * `request` names it as "<file>\t<ntuple>", the same shape the tree commands
 * use. The reader stays open until the id is attached again or the engine stops.
 */
bool attach(std::uint32_t job_id, std::string_view request);

/// True when a reader is bound to that id.
[[nodiscard]] bool has_reader(std::uint32_t job_id);

/// Closes every reader. Called when the engine stops.
void shutdown();

// ============================================================================
// Packet handlers
// ============================================================================

// The RNTuples a file holds, one name per line.
void handle_list(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Opens one and binds it to pkt.job_id.
void handle_attach(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Entries, fields, clusters, and the on-disk format version.
void handle_info(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// The schema: one "name  type" per top level field.
void handle_fields(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// One field into shared memory, aligned on the host vector width.
void handle_column(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Turns a TTree into an RNTuple. The request reads
// "<file>\t<tree>\t<output>\t<ntuple>". Scalar branches only; the rest are
// named in the answer rather than silently dropped.
void handle_write(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Installs the handlers above into the process-wide CommandRegistry.
void register_all();

} // namespace Sphere::cmd::ntuple

#endif // SPHERE_CMD_NTUPLE_H
