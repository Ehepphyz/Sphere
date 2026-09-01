// packets.h

// Wire format for the C++/Java IPC channel.


#pragma once

#include "common_config.h"

#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace Sphere::Proto {

/**
 * Identifiers for incoming command requests and outgoing event responses
 */
enum class PacketType : std::uint16_t {
  NONE = 0,

  // Command types (requests)
  CMD_PING = 1,
  CMD_OPEN_FILE = 2,
  CMD_CLOSE_FILE = 3,
  CMD_CLOSE_ALL_FILES = 4,
  CMD_SAVE_FILE = 5,
  CMD_SCHEMA_DISCOVER = 6,

  // System management commands
  CMD_SYS_NOOP = 10,
  CMD_SYS_VERSION = 11,
  CMD_SYS_UPTIME = 12,
  CMD_SYS_CONFIG = 13,
  CMD_CLING_EXEC = 14,

  // TTree commands
  CMD_TTREE_INSPECT = 20,
  CMD_TTREE_QUERY_ENTRIES = 21,
  CMD_TTREE_SCAN_BRANCHES = 22,
  CMD_TTREE_GET_ENTRY = 23,
  CMD_TTREE_READ_COLUMN = 24,
  CMD_TTREE_COMPUTE_STATS = 25,
  CMD_TTREE_APPLY_FILTER = 26,

  // File inspection
  CMD_FILE_SCAN = 27,
  CMD_FILE_LIST = 28,

  // Event types (responses / acknowledgments)
  EVT_OK = 100,
  EVT_PONG = 101,
  EVT_ERROR = 102,
  EVT_FILE_OPENED = 103,
  EVT_FILE_CLOSED = 104,
  EVT_SCHEMA_READY = 105,

  // System management events
  EVT_SYS_VERSION = 110,
  EVT_SYS_UPTIME = 111,
  EVT_SYS_CONFIG = 112,
  EVT_CLING_RESULT = 113, // answers CMD_CLING_EXEC
  EVT_TTREE_INFO = 130,   // answers CMD_TTREE_INSPECT / QUERY_ENTRIES
  EVT_TTREE_SCHEMA = 131, // answers CMD_TTREE_SCAN_BRANCHES
  EVT_TTREE_ENTRY = 132,  // answers CMD_TTREE_GET_ENTRY
  EVT_TTREE_COLUMN = 133, // answers CMD_TTREE_READ_COLUMN
  EVT_TTREE_STATS = 134,  // answers CMD_TTREE_COMPUTE_STATS
  EVT_TTREE_FILTER = 135, // answers CMD_TTREE_APPLY_FILTER
  EVT_BACKPRESSURE = 120,
  EVT_DEADLINE_EXCEEDED = 121
};

[[nodiscard]] constexpr bool is_command(PacketType type) noexcept {
  return static_cast<std::uint16_t>(type) > 0 &&
         static_cast<std::uint16_t>(type) < 100;
}

[[nodiscard]] constexpr bool is_event(PacketType type) noexcept {
  return static_cast<std::uint16_t>(type) >= 100;
}

/**
 * Processing control flags carried by PacketHeader::flags.
 */
enum PacketFlags : std::uint16_t {
  PKT_FLAG_NONE = 0x0000,
  PKT_FLAG_HAS_PAYLOAD = 0x0001, // payload_offset/payload_size are meaningful
  PKT_FLAG_URGENT = 0x0002,      // route to the high priority tier
  PKT_FLAG_NO_REPLY = 0x0004     // fire and forget, do not emit an event
};

/**
 * Fixed-size packet header preceding all dynamic IPC payloads
 */
struct PacketHeader {
  PacketType type{PacketType::NONE};       // offset  0, 2 bytes
  std::uint16_t flags{PKT_FLAG_NONE};      // offset  2, 2 bytes
  std::uint32_t payload_size{0};           // offset  4, 4 bytes
  std::uint64_t payload_offset{0};         // offset  8, 8 bytes, from SHM base
  std::uint64_t job_id{0};                 // offset 16, 8 bytes
  std::uint64_t req_id{0};                 // offset 24, 8 bytes

  [[nodiscard]] constexpr std::size_t total_size() const noexcept {
    return sizeof(PacketHeader) + payload_size;
  }
};

static_assert(sizeof(PacketHeader) == 32,
              "ABI: PacketHeader must be exactly 32 bytes.");
static_assert(alignof(PacketHeader) == 8,
              "ABI: PacketHeader must keep 8-byte alignment.");
static_assert(std::is_standard_layout_v<PacketHeader>,
              "ABI: PacketHeader must be standard layout.");
static_assert(std::is_trivially_copyable_v<PacketHeader>,
              "ABI: PacketHeader must be trivially copyable.");
static_assert(offsetof(PacketHeader, type) == 0, "ABI: type offset drift.");
static_assert(offsetof(PacketHeader, flags) == 2, "ABI: flags offset drift.");
static_assert(offsetof(PacketHeader, payload_size) == 4,
              "ABI: payload_size offset drift.");
static_assert(offsetof(PacketHeader, payload_offset) == 8,
              "ABI: payload_offset offset drift.");
static_assert(offsetof(PacketHeader, job_id) == 16,
              "ABI: job_id offset drift.");
static_assert(offsetof(PacketHeader, req_id) == 24,
              "ABI: req_id offset drift.");

} // namespace Sphere::Proto
