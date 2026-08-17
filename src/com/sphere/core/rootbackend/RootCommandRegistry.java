package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Thread-safe Command Registry mapping 16-bit packet/message type IDs to Java execution handlers.
 * Mirrors the C++ Sphere::CommandRegistry O(1) array dispatch pattern with zero-allocation lookups.
 */
public final class RootCommandRegistry {

    /**
     * Priority levels matching C++ enum class TaskPriority : std::uint8_t.
     */
    public enum TaskPriority {
        LOW((byte) 0),
        NORMAL((byte) 1),
        HIGH((byte) 2);

        private final byte value;

        TaskPriority(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }

        public static TaskPriority fromValue(byte value) {
            return switch (value) {
                case 0 -> LOW;
                case 1 -> NORMAL;
                case 2 -> HIGH;
                default -> NORMAL;
            };
        }
    }

    /**
     * Functional interface for processing zero-copy SHM messages and inline payloads.
     */
    @FunctionalInterface
    public interface CommandHandler {
        void handle(short flags, int jobId, int reqId, String inlineMsg, MemorySegment payload, long tscTimestamp);
    }

    /**
     * Immutable entry holding the handler callback and its execution priority.
     */
    public record CommandEntry(CommandHandler handler, TaskPriority priority) {
        public CommandEntry {
            Objects.requireNonNull(priority, "Priority cannot be null");
        }
    }

    // Supports 16-bit unsigned message types (0x0000 to 0xFFFF)
    private static final int REGISTRY_CAPACITY = 65536;
    private static final RootCommandRegistry INSTANCE = new RootCommandRegistry();

    private final AtomicReferenceArray<CommandEntry> registry = new AtomicReferenceArray<>(REGISTRY_CAPACITY);

    private RootCommandRegistry() {
        // Private constructor for Singleton pattern
    }

    /**
     * Accesses the global static instance of RootCommandRegistry.
     */
    public static RootCommandRegistry instance() {
        return INSTANCE;
    }

    /**
     * Registers a command handler for a given 16-bit message type ID.
     * 
     * @param msgType  Message/Command type ID.
     * @param handler  Callback function executed upon message arrival.
     * @param priority Task priority level for scheduling.
     */
    public void registerCommand(short msgType, CommandHandler handler, TaskPriority priority) {
        int index = Short.toUnsignedInt(msgType);
        Objects.requireNonNull(handler, "CommandHandler cannot be null");
        registry.set(index, new CommandEntry(handler, priority));
        AppLogger.info("Registered command handler for type ID: 0x" + Integer.toHexString(index) + " (" + priority + ")");
    }

    /**
     * Registers a command handler with default TaskPriority.NORMAL.
     */
    public void registerCommand(short msgType, CommandHandler handler) {
        registerCommand(msgType, handler, TaskPriority.NORMAL);
    }

    /**
     * Retrieves the command entry registered for a specific message type ID.
     */
    public CommandEntry get(short msgType) {
        int index = Short.toUnsignedInt(msgType);
        return registry.get(index);
    }

    /**
     * Dispatches an incoming payload to the registered handler if present.
     * 
     * @return true if a handler was registered and executed; false otherwise.
     */
    public boolean dispatch(short type, short flags, int jobId, int reqId, String inlineMsg, MemorySegment payload, long tscTimestamp) {
        CommandEntry entry = get(type);
        if (entry != null && entry.handler() != null) {
            entry.handler().handle(flags, jobId, reqId, inlineMsg, payload, tscTimestamp);
            return true;
        }
        AppLogger.warn("Unhandled message type ID: 0x" + Integer.toHexString(Short.toUnsignedInt(type)) + " [JobId: " + jobId + "]");
        return false;
    }
}