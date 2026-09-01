// commands/cmd_file.h
#ifndef SPHERE_CMD_FILE_H
#define SPHERE_CMD_FILE_H

#include "packets.h"
#include "shm_layout.h"

namespace Sphere::cmd::file {

// Shared memory IPC command handlers for ROOT file lifecycle management.

void handle_open(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

void handle_close(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

void handle_close_all(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Closes every ROOT file still open, outside of any request.
void close_all_files();

void handle_save(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Installs the handlers above into the process-wide CommandRegistry.
void register_all();

} // namespace Sphere::cmd::file

#endif // SPHERE_CMD_FILE_H
