// commands/cmd_system.h
#pragma once

#include "packets.h"
#include "shm_layout.h"

namespace Sphere {
namespace cmd {
namespace sys {

/**
 * Executes a no-operation (NOOP) system check.
 */
void handle_noop(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

/**
 * Queries and outputs the current version of the system engine.
 */
void handle_version(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

/**
 * Calculates and reports engine uptime metrics.
 */
void handle_uptime(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

/**
 * Central processing entry point for system-level IPC commands.
 *
 * Dispatches incoming packet headers to dedicated internal handlers based on
 * PacketType (CMD_SYS_NOOP, CMD_SYS_VERSION, CMD_SYS_UPTIME), writing
 * responses back into the Shared Memory layout.
 */
void handle_system(ShmLayout &shm, const ::Platform::PacketHeader &pkt);

} // namespace sys
} // namespace cmd
} // namespace Sphere
