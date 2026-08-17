// commands/cmd_file.h
#ifndef SPHERE_CMD_FILE_H
#define SPHERE_CMD_FILE_H

#include "packets.h"
#include "shm_layout.h"

namespace Sphere::cmd::file {

/**
 * Shared memory IPC command handlers for ROOT file lifecycle management.
 *
 * These handlers process file-level operations triggered over the shared memory
 * communication ring buffer, ensuring thread-safe state synchronization across
 * client applications and backend processing engines.
 */

/**
 * Opens a ROOT file specified by the payload string path in the incoming
 * packet. Registers the resulting handle with the global file registry using
 * the packet request ID.
 */
void handle_open(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

/**
 * Closes an active ROOT file identified by the request ID in the incoming
 * packet header. Frees associated system resources and releases context memory.
 */
void handle_close(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

/**
 * Closes all currently opened ROOT file handles managed by the process context.
 */
void handle_close_all(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

/**
 * Flushes dirty memory buffers and saves modifications to the target ROOT file.
 */
void handle_save(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

} // namespace Sphere::cmd::file

#endif // SPHERE_CMD_FILE_H
