package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller class managing low-latency shared memory polling and dispatching events 
 * from the C++ backend to the Java UI layer using zero-copy memory slices.
 */
public final class RootShmController implements AutoCloseable {

    private static final String DEFAULT_SHM_RELATIVE_PATH = "rootbackend" + File.separator + "root_backend.shm";
    private static final String EXECUTABLE_BINARY_NAME = "root-bridge";

    private RootShmSegment shmSegment;
    private RootShmRingBuffer eventRing;
    private final ExecutorService pollerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "root-shm-poller");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private RootShmCanvasRenderer canvasRenderer;
    private Path activeShmPath;

    /**
     * Resolves the SHM path based on user settings or auto-generates the default inside 'rootbackend/'.
     */
    public void initialize(SettingsManager settings, long memorySize) throws Exception {
        Path resolvedPath = resolveAndPrepareShmPath(settings);
        initialize(resolvedPath.toFile(), memorySize);
    }

    /**
     * Initializes the shared memory region using an explicit file reference.
     */
    public void initialize(File shmFile, long memorySize) throws Exception {
        this.activeShmPath = shmFile.toPath().toAbsolutePath();
        this.shmSegment = RootShmSegment.openSharedMemory(shmFile, memorySize);
        this.eventRing = new RootShmRingBuffer(shmSegment.segment());
        this.running.set(true);

        // Start dedicated background polling thread (Lock-free + CPU adaptive spin-wait)
        pollerExecutor.submit(this::pollLoop);
        //AppLogger.info("RootShmController connected to C++ shared memory buffer: " + activeShmPath);
    }

    /**
     * Resolves and validates the target SHM file location while enforcing user settings precedence.
     * Prevents mapping the executable binary itself to avoid Linux Errno 26 (Text file busy).
     */
    private static Path resolveAndPrepareShmPath(SettingsManager settings) {
        String configuredPath = settings != null ? settings.getProperty("SHM_PATH") : null;

        // Priority 1: User explicit setting in settings.conf
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path userPath = Path.of(configuredPath.trim()).toAbsolutePath().normalize();

            // Safety guard: Ensure configured path does not point to the binary file directly
            if (userPath.getFileName().toString().equals(EXECUTABLE_BINARY_NAME) || 
                userPath.getFileName().toString().equals(EXECUTABLE_BINARY_NAME + ".exe")) {
                AppLogger.warn("Configured SHM_PATH points to the binary executable. Falling back to default SHM segment.");
            } else {
                AppLogger.info("Using user-defined SHM path: " + userPath);
                ensureParentDirectoryExists(userPath);
                return userPath;
            }
        }

        // Priority 2: Automatic fallback to rootbackend/ directory
        Path defaultShmPath = Path.of(System.getProperty("user.dir"))
                .resolve(DEFAULT_SHM_RELATIVE_PATH)
                .toAbsolutePath()
                .normalize();

        AppLogger.info("SHM_PATH missing or invalid in settings.conf. Auto-creating default path: " + defaultShmPath);

        ensureParentDirectoryExists(defaultShmPath);

        // Write default configuration to settings.conf for persistence
        if (settings != null) {
            try {
                settings.setProperty("SHM_PATH", DEFAULT_SHM_RELATIVE_PATH);
                settings.save();
                AppLogger.success("Updated settings.conf with default SHM_PATH=" + DEFAULT_SHM_RELATIVE_PATH);
            } catch (Exception e) {
                AppLogger.warn("Could not persist default SHM_PATH into settings.conf: " + e.getMessage());
            }
        }

        return defaultShmPath;
    }

    private static void ensureParentDirectoryExists(Path filePath) {
        try {
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                AppLogger.debug("Created parent directory for SHM: " + parent);
            }
        } catch (IOException e) {
            AppLogger.error("Failed to create target directories for SHM path: " + e.getMessage());
        }
    }

    private static void cleanStaleShmFile(Path shmPath) {
        // Intentionally left empty to keep up the SHM file active across restarts
    }

    private void registerShutdownCleanup() {
        // Intentionally left empty to preserve the SHM region at shutdown
    }

    /**
     * Binds a canvas renderer instance for receiving direct off-heap pixel payload updates.
     */
    public void setCanvasRenderer(RootShmCanvasRenderer renderer) {
        this.canvasRenderer = renderer;
    }

    /**
     * Low-latency loop capturing native events from the shared memory ring buffer.
     */
    private void pollLoop() {
        int idleCount = 0;

        while (running.get()) {
            boolean processed = eventRing.tryPollResponse(payload -> {
                if (canvasRenderer != null) {
                    canvasRenderer.updatePixelsFromShm(payload);
                }
            });

            if (processed) {
                idleCount = 0;
            } else {
                idleCount++;
                if (idleCount < 1_000) {
                    Thread.onSpinWait();
                } else if (idleCount < 10_000) {
                    Thread.yield();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Stops the background poller thread and releases mapped off-heap memory resources.
     */
    @Override
    public void close() {
        running.set(false);
        pollerExecutor.shutdownNow();
        if (shmSegment != null) {
            shmSegment.close();
            shmSegment = null;
        }
    }
}