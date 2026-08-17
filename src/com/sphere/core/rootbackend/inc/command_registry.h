// command_registry.h

#ifndef COMMAND_REGISTRY_H
#define COMMAND_REGISTRY_H

#include "packets.h"

#include <array>
#include <cstdint>
#include <functional>
#include <utility>

namespace Sphere {

// Forward declare ShmLayout to avoid circular dependencies with shm_layout.h
struct ShmLayout;

/**
 * Handler function signature for incoming packet commands.
 * Accepts a reference to the shared memory layout and the packet header.
 */
using CommandHandler =
    std::function<void(ShmLayout &shm, const Platform::PacketHeader &pkt)>;

/**
 * Task priority levels for scheduler queues.
 */
enum class TaskPriority : std::uint8_t { Low = 0, Normal = 1, High = 2 };

/**
 * Structure holding command metadata and its associated execution
 * callback
 */
struct CommandEntry {
  CommandHandler handler{nullptr};
  TaskPriority priority{TaskPriority::Normal};
};

/**
 * Thread-safe Singleton Registry mapping packet command types to
 * handlers
 */
class CommandRegistry {
public:
  /**
   * Access the global static instance of the CommandRegistry
   */
  static CommandRegistry &instance() noexcept {
    static CommandRegistry reg;
    return reg;
  }

  /**
   * Registers a command handler for a given packet type ID
   */
  void register_command(std::uint8_t type, CommandHandler handler,
                        TaskPriority priority = TaskPriority::Normal) {
    registry_[type] = CommandEntry{std::move(handler), priority};
  }

  /**
   * Retrieves the command entry registered for a specific type ID
   */
  [[nodiscard]] const CommandEntry &get(std::uint8_t type) const noexcept {
    return registry_[type];
  }

private:
  CommandRegistry() = default;
  ~CommandRegistry() = default;

  // Non-copyable and non-movable singleton interface
  CommandRegistry(const CommandRegistry &) = delete;
  CommandRegistry &operator=(const CommandRegistry &) = delete;
  CommandRegistry(CommandRegistry &&) = delete;
  CommandRegistry &operator=(CommandRegistry &&) = delete;

  std::array<CommandEntry, 256> registry_{};
};

} // namespace Sphere

#endif // COMMAND_REGISTRY_H
