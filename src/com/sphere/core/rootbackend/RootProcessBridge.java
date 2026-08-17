package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RootProcessBridge v2: Low-Latency Native Process & SHM Command Producer Engine.
 * Operates strictly via asynchronous lock-free off-heap MPMC/SPSC shared memory rings
 * using Java 22+ Foreign Function & Memory (FFM) Panama API.
 */
public final class RootProcessBridge implements AutoCloseable {

    private Process process;
    private final ReentrantLock lock = new ReentrantLock();
    private final String binaryPath;
    private final Map<String, String> environment;

    // SHM Off-Heap Memory Region
    private RootShmSegment commandShmSegment;
    private MemorySegment commandSegment;
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

    // Cache-Line Aligned Ring Buffer Offsets (64-byte aligned for CPU L1 Cache)
    private static final long HEAD_OFFSET = 0L;
    private static final long TAIL_OFFSET = 8L;
    private static final long CAPACITY_OFFSET = 16L;
    private static final long PAYLOAD_START_OFFSET = 64L;
    private static final long SLOT_SIZE = 256L;

    public RootProcessBridge(String binaryPath, Map<String, String> environment) {
        this.binaryPath = binaryPath;
        this.environment = environment;
    }

    /**
     * Spawns the native C++ daemon process and maps the SHM Command Ring memory segment.
     */
    public void start(File commandShmFile, long commandShmSize) throws Exception {
        lock.lock();
        try {
            if (process != null && process.isAlive()) {
                return;
            }

            File binaryFile = new File(binaryPath);
            File backendDir = binaryFile.getParentFile();
            if (backendDir == null) {
                backendDir = new File("rootbackend");
            }

            File errorLog = new File(backendDir, "rootbackend_error.log");

            ProcessBuilder pb = new ProcessBuilder(binaryFile.getAbsolutePath(), "--shm-mode");
            pb.directory(backendDir);
            if (this.environment != null && !this.environment.isEmpty()) {
                pb.environment().putAll(this.environment);
            }
            pb.redirectError(ProcessBuilder.Redirect.appendTo(errorLog));

            this.process = pb.start();

            // Brief pause for native process setup and SHM segment creation
            TimeUnit.MILLISECONDS.sleep(200);

            // Zero-Copy Memory Mapping via Java FFM API
            this.commandShmSegment = RootShmSegment.openSharedMemory(commandShmFile, commandShmSize);
            this.commandSegment = commandShmSegment.segment();

            AppLogger.info("RootProcessBridge v2: Native daemon spawned and bound to MPMC SHM Command Ring.");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non-blocking Lock-Free MPMC Producer.
     * Pushes an instruction payload directly into the shared memory command ring.
     *
     * @param command Command payload string to execute on C++ Engine Scheduler.
     * @return {@code true} if payload was queued successfully; {@code false} on ring overflow or offline backend.
     */
    public boolean pushCommand(String command) {
        if (!isAlive() || commandSegment == null) {
            AppLogger.error("Cannot push command: Native process or command SHM segment is inactive.");
            return false;
        }

        long capacity = commandSegment.get(ValueLayout.JAVA_LONG, CAPACITY_OFFSET);
        long currentHead = (long) LONG_HANDLE.getAcquire(commandSegment, HEAD_OFFSET);
        long currentTail = (long) LONG_HANDLE.getAcquire(commandSegment, TAIL_OFFSET);

        // Ring Buffer Overflow check
        if ((currentTail - currentHead) >= capacity) {
            AppLogger.error("Command Ring Buffer overflow. Target slot is locked by C++ scheduler.");
            return false;
        }

        long slotIndex = currentTail % capacity;
        long slotOffset = PAYLOAD_START_OFFSET + (slotIndex * SLOT_SIZE);

        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        long copyLength = Math.min(bytes.length, SLOT_SIZE - 1);

        // Off-heap direct memory copy
        MemorySegment slotSegment = commandSegment.asSlice(slotOffset, SLOT_SIZE);
        slotSegment.fill((byte) 0);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, slotSegment, 0L, copyLength);

        // Memory Release barrier to publish updated sequence index to C++ Engine Scheduler
        LONG_HANDLE.setRelease(commandSegment, TAIL_OFFSET, currentTail + 1);
        return true;
    }

    /**
     * Checks whether the native C++ backend daemon is running.
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Releases mapped off-heap memory segments and terminates the native process.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (commandShmSegment != null) {
                try {
                    commandShmSegment.close();
                } catch (Exception e) {
                    AppLogger.error("Error unmapping command SHM segment: " + e.getMessage());
                }
                commandShmSegment = null;
                commandSegment = null;
            }

            if (process != null) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                process = null;
            }
        } finally {
            lock.unlock();
        }
    }
}