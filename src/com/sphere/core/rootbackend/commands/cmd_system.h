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

// What the engine knows about itself: counters, rings, heap, resident memory.
// The payload names the view -- text, json, engine, memory, heap, reset.
void handle_sys_metrics(ShmLayout &shm, const Proto::PacketHeader &pkt,
                        void *context);

// ROOT's implicit multithreading, which RDataFrame and the tree readers use.
// The payload reads "on", "on <n>", "off" or "status".
void handle_sys_threads(ShmLayout &shm, const Proto::PacketHeader &pkt,
                        void *context);

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
