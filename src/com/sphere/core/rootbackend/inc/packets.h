// packets.h

#pragma once

#include <cstddef>
#include <cstdint>

namespace Platform {

/**
 * Identifiers for incoming command requests and outgoing event responses.
 */
enum class PacketType : std::uint16_t {
  // Command Types (Requests)
  CMD_PING = 1,
  CMD_OPEN_FILE = 2,
  CMD_CLOSE_FILE = 3,
  CMD_CLOSE_ALL_FILES = 4,
  CMD_SAVE_FILE = 5,
  CMD_SCHEMA_DISCOVER = 6,

  // System Management Commands
  CMD_SYS_NOOP = 10,
  CMD_SYS_VERSION = 11,
  CMD_SYS_UPTIME = 12,

  // Event Types (Responses / Acknowledgments)
  EVT_OK = 100,
  EVT_PONG = 101,
  EVT_ERROR = 102,
  EVT_FILE_OPENED = 103,
  EVT_FILE_CLOSED = 104,
  EVT_SCHEMA_READY = 105,

  // System Management Events
  EVT_SYS_VERSION = 110,
  EVT_SYS_UPTIME = 111
};

// Enforce strict 1-byte alignment to guarantee identical memory layout across
// target platforms (32 bytes total)
#pragma pack(push, 1)

/**
 * Fixed-size packet header preceding all dynamic IPC payloads.
 */
struct PacketHeader {
  PacketType type;     // 2 bytes: Packet command or event identifier
  std::uint16_t flags; // 2 bytes: Processing control flags
  std::uint32_t
      payload_size; // 4 bytes: Size of trailing binary payload in bytes
  std::uint64_t payload_offset; // 8 bytes: Offset from shared memory base
                                // address to payload
  std::uint64_t job_id;         // 8 bytes: High-level job sequence tracker
  std::uint64_t req_id; // 8 bytes: Client request correlation identifier

  [[nodiscard]] constexpr std::size_t total_size() const noexcept {
    return sizeof(PacketHeader) + payload_size;
  }
};

#pragma pack(pop)

static_assert(
    sizeof(PacketHeader) == 32,
    "PacketHeader must be exactly 32 bytes without implicit struct padding");

} // namespace Platform
