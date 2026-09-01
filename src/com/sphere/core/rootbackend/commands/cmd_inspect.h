// commands/cmd_inspect.h

#pragma once

#include "packets.h"
#include "shm_layout.h"

namespace Sphere::cmd::inspect {

void handle_file_scan(ShmLayout &shm, const Proto::PacketHeader &pkt,
                      void *context);

void register_all();

} // namespace Sphere::cmd::inspect
