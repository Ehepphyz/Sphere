// commands/cmd_system.h
#pragma once

#include "packets.h"
#include "shm_layout.h"

namespace Sphere {
namespace cmd {
namespace sys {

void handle_noop(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

void handle_version(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

void handle_uptime(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

/**
* Central processing entry point for system-level IPC commands
*/
void handle_system(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Runs the request payload through the ROOT interpreter and answers with the
void handle_cling_exec(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Hands one heap chunk back to the allocator, addressed by its payload offset
// carried in pkt.job_id. Answers nothing.
void handle_release_chunk(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Builds every ROOT-backed resource these handlers use, on the calling thread.
void warm_up();

/**
 * Installs the handlers above into the process-wide CommandRegistry
 */
void register_all();

} // namespace sys
} // namespace cmd
} // namespace Sphere
