// commands/cmd_file.h
#ifndef SPHERE_CMD_FILE_H
#define SPHERE_CMD_FILE_H

#include "packets.h"
#include "shm_layout.h"

#include <cstdint>
#include <string>

class TFile;

namespace Sphere::cmd::file {

// Shared memory IPC command handlers for ROOT file lifecycle management.

void handle_open(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

void handle_close(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

void handle_close_all(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Closes every ROOT file still open, outside of any request.
void close_all_files();

// The open file behind a handle, or nullptr. Lets the tree commands reach the
// objects a file holds without keeping a second registry of their own.
TFile *file_for(std::uint32_t id);

// The handle a token names, accepting an id or a name; 0 when neither matches.
std::uint32_t resolve_file(const std::string &token);

// Answers a request with text through the shared heap. Exported so the tree
// commands can say why they refused rather than send a bare status code.
void reply_text(ShmLayout &shm, const Proto::PacketHeader &req,
                Proto::PacketType type, const std::string &text);

void handle_save(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Lists what an open file holds, subdirectories included.
void handle_keys(ShmLayout &shm, const Proto::PacketHeader &pkt, void *context);

// Installs the handlers above into the process-wide CommandRegistry.
void register_all();

} // namespace Sphere::cmd::file

#endif // SPHERE_CMD_FILE_H
