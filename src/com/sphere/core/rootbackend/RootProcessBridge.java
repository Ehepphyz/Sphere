package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RootProcessBridge: Low-Latency Native Process & SHM Command Producer Engine.
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

    private int cmdRingCapacityPow2 = 10;

    public RootProcessBridge(String binaryPath, Map<String, String> environment) {
        this.binaryPath = binaryPath;
        this.environment = environment;
    }

    private static final long STARTUP_TIMEOUT_MS = 15_000L;

    // Blocks until `file` carries the region magic, or the timeout expires.
    private static void waitForFormattedRegion(File file, long timeoutMillis)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        long pollMillis = 5L;

        while (System.currentTimeMillis() < deadline) {
            if (file.length() >= 4L) {
                try (RandomAccessFile probe = new RandomAccessFile(file, "r")) {
                    byte[] four = new byte[4];
                    probe.seek(RootBackend.HDR_MAGIC);
                    probe.readFully(four);
                    int magic = (four[0] & 0xFF) | ((four[1] & 0xFF) << 8)
                              | ((four[2] & 0xFF) << 16) | ((four[3] & 0xFF) << 24);
                    if (magic == RootBackend.SHM_MAGIC) {
                        return;
                    }
                } catch (Exception ignored) {
                    // Not readable yet; keep waiting.
                }
            }
            Thread.sleep(pollMillis);
            pollMillis = Math.min(pollMillis * 2L, 250L);
        }

        AppLogger.warn("The shared region did not become readable within "
            + timeoutMillis + " ms; the mapping below will report what it finds.");
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

            ProcessBuilder pb = new ProcessBuilder(
                    binaryFile.getAbsolutePath(),
                    "--serve",
                    "--shm", commandShmFile.getAbsolutePath(),
                    "--parent-pid", Long.toString(ProcessHandle.current().pid()));
            pb.directory(backendDir);
            if (this.environment != null && !this.environment.isEmpty()) {
                pb.environment().putAll(this.environment);
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(errorLog));

            this.process = pb.start();

            waitForFormattedRegion(commandShmFile, STARTUP_TIMEOUT_MS);

            // A formatted region does not mean OUR engine is alive: it may have
            // been formatted by an earlier run while this process died at once.
            if (!this.process.isAlive()) {
                throw new IllegalStateException(
                    "root-bridge --serve exited immediately with code "
                    + this.process.exitValue()
                    + ". See rootbackend/rootbackend_error.log.");
            }

            File target = commandShmFile;
            long actualSize = target.length() > 0 ? target.length() : commandShmSize;

            // Zero-Copy Memory Mapping via Java FFM API
            this.commandShmSegment = RootShmSegment.openSharedMemory(target, actualSize);
            MemorySegment base = commandShmSegment.segment();

            int magic = base.get(ValueLayout.JAVA_INT, RootBackend.HDR_MAGIC);
            if (magic != RootBackend.SHM_MAGIC) {
                throw new IllegalStateException(String.format(
                    "Shared region %s has magic 0x%08X, expected 0x%08X.",
                    target, magic, RootBackend.SHM_MAGIC));
            }

            long cmdOffset = base.get(ValueLayout.JAVA_LONG, RootBackend.HDR_OFF_CMD_RING);
            long cmdSize = base.get(ValueLayout.JAVA_LONG, RootBackend.HDR_SIZE_CMD_RING);
            long cmdCapacity = base.get(ValueLayout.JAVA_LONG, RootBackend.HDR_CMD_RING_CAPACITY);

            if (cmdOffset <= 0 || cmdSize <= 0 || cmdOffset > actualSize
                || cmdSize > actualSize - cmdOffset) {
                throw new IllegalStateException(
                    "Command ring partition is out of range: offset=" + cmdOffset
                    + " size=" + cmdSize + " in a " + actualSize + " byte region.");
            }
            if (cmdCapacity <= 0 || Long.bitCount(cmdCapacity) != 1) {
                throw new IllegalStateException(
                    "Command ring capacity " + cmdCapacity + " is not a power of two.");
            }

            // Off-heap direct memory copy
            this.commandSegment = base.asSlice(cmdOffset, cmdSize);
            this.cmdRingCapacityPow2 = Long.numberOfTrailingZeros(cmdCapacity);

        } finally {
            lock.unlock();
        }
    }

    public boolean pushCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);
        // Same counter as RootBackend: both write into this region's command
        // ring and the pump routes every reply through one table keyed by
        // request id, so a second counter would misdeliver answers.
        return pushCommand(RootBackend.CMD_CLING_EXEC, 0,
                           RootBackend.nextRequestId(), payload);
    }

    public boolean pushCommand(short opcode, int jobId, int reqId, byte[] payload) {
        if (!isAlive() || commandSegment == null) {
            AppLogger.error("Cannot push command: the engine or the command ring is inactive.");
            return false;
        }
        return RootBackend.pushCommandMessage(commandSegment, cmdRingCapacityPow2,
                                             opcode, jobId, reqId, payload);
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