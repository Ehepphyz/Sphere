// command_registry.h

// Dispatch table mapping packet opcodes to handlers.


#pragma once

#include "packets.h"

#include <array>
#include <atomic>
#include <cstdint>

namespace Sphere {

struct ShmLayout;

/**
 * Handler signature
 */
using CommandHandler = void (*)(ShmLayout &shm, const Proto::PacketHeader &pkt,
                                void *context);

/**
 * Scheduling tier for a command
 */
enum class TaskPriority : std::uint8_t { LOW = 0, NORMAL = 1, HIGH = 2 };

/**
 * Number of opcodes the table can hold
 */
inline constexpr std::size_t COMMAND_TABLE_SIZE = 512;

/**
 * One table entry.
 */
struct CommandEntry {
  CommandHandler handler{nullptr};
  void *context{nullptr};
  TaskPriority priority{TaskPriority::NORMAL};
};

/**
 * Process-wide command table
 */
class CommandRegistry {
public:
  static CommandRegistry &instance() noexcept {
    static CommandRegistry registry;
    return registry;
  }

  /**
   * Registers a handler
   */
  bool register_command(Proto::PacketType type, CommandHandler handler,
                        void *context = nullptr,
                        TaskPriority priority = TaskPriority::NORMAL) noexcept {
    const std::size_t index = static_cast<std::size_t>(type);
    if (index >= COMMAND_TABLE_SIZE) {
      return false;
    }
    contexts_[index].store(context, std::memory_order_relaxed);
    priorities_[index].store(priority, std::memory_order_relaxed);
    handlers_[index].store(handler, std::memory_order_release);
    return true;
  }

  /// Removes any handler registered for `type`.
  void unregister_command(Proto::PacketType type) noexcept {
    const std::size_t index = static_cast<std::size_t>(type);
    if (index < COMMAND_TABLE_SIZE) {
      handlers_[index].store(nullptr, std::memory_order_release);
    }
  }

  /// Snapshot of the entry for `type`; handler is null when none is registered.
  [[nodiscard]] CommandEntry get(Proto::PacketType type) const noexcept {
    const std::size_t index = static_cast<std::size_t>(type);
    if (index >= COMMAND_TABLE_SIZE) {
      return CommandEntry{};
    }
    CommandEntry entry{};
    entry.handler = handlers_[index].load(std::memory_order_acquire);
    entry.context = contexts_[index].load(std::memory_order_relaxed);
    entry.priority = priorities_[index].load(std::memory_order_relaxed);
    return entry;
  }

  /// Priority for `type`, or NORMAL when nothing is registered.
  [[nodiscard]] TaskPriority priority_of(Proto::PacketType type) const noexcept {
    const std::size_t index = static_cast<std::size_t>(type);
    if (index >= COMMAND_TABLE_SIZE) {
      return TaskPriority::NORMAL;
    }
    return priorities_[index].load(std::memory_order_relaxed);
  }

  /**
   * Invokes the handler for pkt
   */
  bool dispatch(ShmLayout &shm, const Proto::PacketHeader &pkt) const noexcept {
    const std::size_t index = static_cast<std::size_t>(pkt.type);
    if (index >= COMMAND_TABLE_SIZE) {
      return false;
    }
    const CommandHandler handler =
        handlers_[index].load(std::memory_order_acquire);
    if (handler == nullptr) {
      return false;
    }
    handler(shm, pkt, contexts_[index].load(std::memory_order_relaxed));
    return true;
  }

  /// Number of registered handlers, for startup diagnostics.
  [[nodiscard]] std::size_t registered_count() const noexcept {
    std::size_t count = 0;
    for (const auto &slot : handlers_) {
      if (slot.load(std::memory_order_relaxed) != nullptr) {
        ++count;
      }
    }
    return count;
  }

private:
  CommandRegistry() = default;
  ~CommandRegistry() = default;

  CommandRegistry(const CommandRegistry &) = delete;
  CommandRegistry &operator=(const CommandRegistry &) = delete;
  CommandRegistry(CommandRegistry &&) = delete;
  CommandRegistry &operator=(CommandRegistry &&) = delete;

  std::array<std::atomic<CommandHandler>, COMMAND_TABLE_SIZE> handlers_{};
  std::array<std::atomic<void *>, COMMAND_TABLE_SIZE> contexts_{};
  std::array<std::atomic<TaskPriority>, COMMAND_TABLE_SIZE> priorities_{};
};

} // namespace Sphere
