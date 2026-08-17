package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;
import com.sphere.core.Backend;
import com.sphere.core.rootbackend.RootObjects.RootFile;

import java.io.RandomAccessFile;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Primary orchestrator for the ROOT backend environment.
 * Serves as the high-level interface for zero-copy Shared Memory (SHM) ring buffers,
 * SPSC multi-runtime topologies, off-heap telemetry, and native error hooks.
 */
public final class RootBackend implements AutoCloseable, Backend {

    // Status flag to track if ROOT is properly configured on the system
    private boolean isAvailable = false;

    // Memory sub-region offsets (must match native C++ ShmRegion layout exactly)
    private static final long COMMAND_RING_OFFSET = 0L;
    private static final long EVENT_RING_OFFSET   = 8192L;
    private static final long DATA_HEAP_OFFSET    = 16384L;

    // Cluster and Ring Buffer Constants
    private static final long RING_CAPACITY = 64L;
    private static final long RING_MASK = RING_CAPACITY - 1;
    public static final int MAX_RUNTIMES = 32;

    // Log Levels matching Sphere::log::LogLevel in logger.h
    public static final short LOG_LEVEL_TRACE = 0;
    public static final short LOG_LEVEL_DEBUG = 1;
    public static final short LOG_LEVEL_INFO  = 2;
    public static final short LOG_LEVEL_WARN  = 3;
    public static final short LOG_LEVEL_ERROR = 4;
    public static final short LOG_LEVEL_FATAL = 5;

    // Subsystem Module IDs matching logger.h and span_record.h
    public static final short MODULE_GENERIC = 0;
    public static final short MODULE_FILE    = 1;
    public static final short MODULE_IOURING = 2;
    public static final short MODULE_ROOT    = 3;
    public static final short MODULE_WORKER  = 4;
    public static final short MODULE_SHM     = 5;
    public static final short MODULE_MAX     = 32;

    public static final int MAX_WORKERS = 64;
    public static final long CACHE_LINE_SIZE = 64L;

    // Ring Drop Policy Constants matching RingDropPolicy in ringbuffer.h
    public static final int DROP_POLICY_BLOCK        = 0;
    public static final int DROP_POLICY_DROP_OLDEST = 1;
    public static final int DROP_POLICY_DROP_NEWEST = 2;

    // Spin-wait backoff thresholds
    private static final int MAX_SPIN_RETRIES = 10;

    // Intelligence & Telemetry Constants
    private static final double EWMA_ALPHA = 0.05;
    private static final double LATENCY_ANOMALY_THRESHOLD_NANOS = 1_000_000.0; // 1 millisecond
    private static final int HISTOGRAM_BUCKETS = 1024;

    // PacketType Constants matching Platform::PacketType in packets.h
    public static final short CMD_PING            = 1;
    public static final short CMD_OPEN_FILE       = 2;
    public static final short CMD_CLOSE_FILE      = 3;
    public static final short CMD_CLOSE_ALL_FILES = 4;
    public static final short CMD_SAVE_FILE       = 5;
    public static final short CMD_SCHEMA_DISCOVER = 6;

    // System Management Commands
    public static final short CMD_SYS_NOOP        = 10;
    public static final short CMD_SYS_VERSION     = 11;
    public static final short CMD_SYS_UPTIME      = 12;

    // Event Types (Responses / Acknowledgments)
    public static final short EVT_OK              = 100;
    public static final short EVT_PONG            = 101;
    public static final short EVT_ERROR           = 102;
    public static final short EVT_FILE_OPENED     = 103;
    public static final short EVT_FILE_CLOSED     = 104;
    public static final short EVT_SCHEMA_READY    = 105;

    // System Management Events
    public static final short EVT_SYS_VERSION     = 110;
    public static final short EVT_SYS_UPTIME      = 111;

    public static final StructLayout WORKER_METRICS_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("jobs"),
        ValueLayout.JAVA_LONG.withName("busy_cycles"),
        MemoryLayout.paddingLayout(48)
    );

    public static final StructLayout SPAN_RECORD_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("tsc_start"),
        ValueLayout.JAVA_LONG.withName("tsc_end"),
        ValueLayout.JAVA_LONG.withName("trace_id"),
        ValueLayout.JAVA_INT.withName("thread_id"),
        ValueLayout.JAVA_INT.withName("job_id"),
        ValueLayout.JAVA_INT.withName("req_id"),
        ValueLayout.JAVA_SHORT.withName("module_id"),
        ValueLayout.JAVA_BYTE.withName("level"),
        ValueLayout.JAVA_BYTE.withName("reserved")
    );

    public static final StructLayout SPAN_CELL_LAYOUT = MemoryLayout.structLayout(
        SPAN_RECORD_LAYOUT.withName("record"),
        ValueLayout.JAVA_LONG.withName("seq"),
        MemoryLayout.paddingLayout(16)
    );

    public static final StructLayout SPAN_RING_HEADER_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("write_index"),
        MemoryLayout.paddingLayout(56),
        ValueLayout.JAVA_LONG.withName("read_index"),
        MemoryLayout.paddingLayout(56),
        ValueLayout.JAVA_LONG.withName("capacity"),
        ValueLayout.JAVA_LONG.withName("dropped_count"),
        ValueLayout.JAVA_LONG.withName("cas_contention_count"),
        MemoryLayout.paddingLayout(40)
    );

    public static final StructLayout SPSC_CHANNEL_LAYOUT = MemoryLayout.structLayout(
        SPAN_RING_HEADER_LAYOUT.withName("tx_header"),
        SPAN_RING_HEADER_LAYOUT.withName("rx_header")
    );

    private static final long SPAN_OFFSET_TSC_START = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("tsc_start"));
    private static final long SPAN_OFFSET_TSC_END   = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("tsc_end"));
    private static final long SPAN_OFFSET_TRACE_ID = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("trace_id"));
    private static final long SPAN_OFFSET_THREAD_ID = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("thread_id"));
    private static final long SPAN_OFFSET_JOB_ID    = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("job_id"));
    private static final long SPAN_OFFSET_REQ_ID    = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("req_id"));
    private static final long SPAN_OFFSET_MODULE_ID = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("module_id"));
    private static final long SPAN_OFFSET_LEVEL     = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("level"));

    private static final long SPAN_CELL_OFFSET_RECORD = SPAN_CELL_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("record"));
    private static final long SPAN_CELL_OFFSET_SEQ    = SPAN_CELL_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("seq"));

    public static final StructLayout RING_HEADER_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("write_idx"),
        MemoryLayout.paddingLayout(56),
        ValueLayout.JAVA_LONG.withName("read_idx"),
        MemoryLayout.paddingLayout(56),
        ValueLayout.JAVA_LONG.withName("capacity"),
        ValueLayout.JAVA_LONG.withName("slot_size"),
        ValueLayout.JAVA_INT.withName("high_watermark"),
        ValueLayout.JAVA_INT.withName("low_watermark"),
        ValueLayout.JAVA_INT.withName("drop_policy"),
        ValueLayout.JAVA_INT.withName("io_fd"),
        ValueLayout.JAVA_LONG.withName("hotness_counter"),
        ValueLayout.JAVA_LONG.withName("journal_seq"),
        ValueLayout.JAVA_LONG.withName("dropped_count"),
        ValueLayout.JAVA_LONG.withName("last_heartbeat_tsc"),
        MemoryLayout.paddingLayout(32),
        MemoryLayout.sequenceLayout(MAX_RUNTIMES, ValueLayout.JAVA_INT).withName("cluster_runtime_ids")
    );

    public static final StructLayout PACKET_HEADER_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withByteAlignment(1).withName("type"),
        ValueLayout.JAVA_SHORT.withByteAlignment(1).withName("flags"),
        ValueLayout.JAVA_INT.withByteAlignment(1).withName("payload_size"),
        ValueLayout.JAVA_LONG.withByteAlignment(1).withName("payload_offset"),
        ValueLayout.JAVA_LONG.withByteAlignment(1).withName("job_id"),
        ValueLayout.JAVA_LONG.withByteAlignment(1).withName("req_id")
    );

    private static final long OFFSET_TYPE           = PACKET_HEADER_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("type"));
    private static final long OFFSET_FLAGS          = PACKET_HEADER_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("flags"));
    private static final long OFFSET_PAYLOAD_SIZE   = PACKET_HEADER_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("payload_size"));
    private static final long OFFSET_PAYLOAD_OFFSET = PACKET_HEADER_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("payload_offset"));
    private static final long OFFSET_JOB_ID         = PACKET_HEADER_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("job_id"));
    private static final long OFFSET_REQ_ID         = PACKET_HEADER_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("req_id"));

    private static final VarHandle WRITE_IDX_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("write_idx"));
    private static final VarHandle READ_IDX_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("read_idx"));
    private static final VarHandle CAPACITY_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("capacity"));
    private static final VarHandle SLOT_SIZE_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("slot_size"));
    private static final VarHandle DROP_POLICY_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("drop_policy"));
    private static final VarHandle DROPPED_COUNT_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dropped_count"));
    private static final VarHandle LAST_HEARTBEAT_TSC_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("last_heartbeat_tsc"));

    private static final VarHandle SPAN_WRITE_IDX_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("write_index"));
    private static final VarHandle SPAN_READ_IDX_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("read_index"));
    private static final VarHandle SPAN_CAPACITY_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("capacity"));
    private static final VarHandle SPAN_DROPPED_COUNT_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dropped_count"));
    private static final VarHandle SPAN_CAS_CONTENTION_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("cas_contention_count"));

    private static final VarHandle SPAN_CELL_SEQ_HANDLE = SPAN_CELL_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("seq"));

    private static final VarHandle CLUSTER_RUNTIME_IDS_HANDLE = RING_HEADER_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("cluster_runtime_ids"),
        MemoryLayout.PathElement.sequenceElement()
    );

    private static final VarHandle METRICS_JOBS_HANDLE = WORKER_METRICS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("jobs"));
    private static final VarHandle METRICS_BUSY_CYCLES_HANDLE = WORKER_METRICS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("busy_cycles"));

    private MethodHandle nativeHandleErrorMH;
    private MethodHandle nativeClearErrorsMH;
    private MethodHandle nativeGetLastErrorMessageMH;

    private Arena sharedArena;
    private MemorySegment shmBaseSegment;
    private MemorySegment commandRingSegment;
    private MemorySegment eventRingSegment;
    private MemorySegment dataHeapSegment;

    private RootShmController shmController;
    private RootShmCanvasRenderer canvasRenderer;
    private RootProcessBridge processBridge;

    private MemorySegment offHeapLatencyHistogram;
    private final AtomicLong heapAllocationOffset = new AtomicLong(0L);

    private final AtomicLong totalSpansProcessed = new AtomicLong(0);
    private final DoubleAdder averageSpanLatencyNanos = new DoubleAdder();
    private final AtomicLong totalCasContentionRetries = new AtomicLong(0);

    private double cyclesToNsRatio = 1.0;

    // --- CONSTRUCTORS ---

    public RootBackend(String binaryPath) throws Exception {
        this(Paths.get(binaryPath), 33554432L, 1280, 720);
    }

    public RootBackend(String binaryPath, SettingsManager settingsManager) throws Exception {
        this(settingsManager, 33554432L, 1280, 720);
    }

    public RootBackend(SettingsManager settingsManager, long totalSizeBytes) throws Exception {
        this(settingsManager, totalSizeBytes, 1280, 720);
    }

    public RootBackend(SettingsManager settingsManager, long totalSizeBytes, int canvasWidth, int canvasHeight) throws Exception {
        // 1. Trigger or verify C++ bridge compilation
        String bridgeBinaryPath = RootBridgeCompiler.getOrCompileBridge(settingsManager);

        // 2. SAFETY GUARD: Abort SHM initialization if compilation failed or binary is missing
        if (bridgeBinaryPath == null || !java.nio.file.Files.exists(Paths.get(bridgeBinaryPath))) {
            this.isAvailable = false;
            return;
        }

        Path shmPath = resolveShmPath(settingsManager);
        initShmMemory(shmPath, totalSizeBytes, canvasWidth, canvasHeight);
    }

    public RootBackend(Path shmPath, long totalSizeBytes) throws Exception {
        this(shmPath, totalSizeBytes, 1280, 720);
    }

    public RootBackend(Path shmPath, long totalSizeBytes, int canvasWidth, int canvasHeight) throws Exception {
        Path binaryPath = Paths.get(System.getProperty("user.dir"), "rootbackend", 
            System.getProperty("os.name").toLowerCase().contains("win") ? "root-bridge.exe" : "root-bridge");

        if (!java.nio.file.Files.exists(binaryPath)) {
            this.isAvailable = false;
            return;
        }

        // Enforce mapping to a valid SHM file rather than the executable binary
        Path targetShmPath = (shmPath != null && shmPath.toString().endsWith(".shm")) 
            ? shmPath 
            : resolveShmPath(null);

        initShmMemory(targetShmPath, totalSizeBytes, canvasWidth, canvasHeight);
    }

    private void initShmMemory(Path shmPath, long totalSizeBytes, int canvasWidth, int canvasHeight) throws Exception {
        Objects.requireNonNull(shmPath, "SHM path cannot be null.");
        if (totalSizeBytes <= DATA_HEAP_OFFSET) {
            AppLogger.error("Insufficient SHM size for configured layout offsets.");
            throw new IllegalArgumentException("SHM size is too small for configured layout offsets.");
        }

        // Prevent memory-mapping the executable binary directly (avoids Linux "Text file busy" / error 26)
        String fileName = shmPath.getFileName().toString();
        if (fileName.equals("root-bridge") || fileName.equals("root-bridge.exe")) {
            shmPath = shmPath.getParent() != null 
                ? shmPath.getParent().resolve("root_backend.shm") 
                : Paths.get("rootbackend", "root_backend.shm");
        }

        //AppLogger.info("Initializing RootBackend shared memory region: " + shmPath);
        this.sharedArena = Arena.ofShared();

        try (RandomAccessFile file = new RandomAccessFile(shmPath.toFile(), "rw");
             FileChannel channel = file.getChannel()) {

            this.shmBaseSegment = channel.map(
                FileChannel.MapMode.READ_WRITE,
                0,
                totalSizeBytes,
                this.sharedArena
            );
        }

        long baseAddress = this.shmBaseSegment.address();
        /*if ((baseAddress % CACHE_LINE_SIZE) != 0) {
            //AppLogger.warn(String.format("SHM Base address 0x%X is NOT aligned to a 64-byte cache line boundary! Sub-optimal performance expected.", baseAddress));
        } else {
            //AppLogger.info(String.format("SHM Base address 0x%X verified: 64-byte cache-line aligned.", baseAddress));
        }*/

        this.commandRingSegment = this.shmBaseSegment.asSlice(COMMAND_RING_OFFSET, EVENT_RING_OFFSET - COMMAND_RING_OFFSET);
        this.eventRingSegment   = this.shmBaseSegment.asSlice(EVENT_RING_OFFSET, DATA_HEAP_OFFSET - EVENT_RING_OFFSET);
        this.dataHeapSegment    = this.shmBaseSegment.asSlice(DATA_HEAP_OFFSET, totalSizeBytes - DATA_HEAP_OFFSET);

        this.offHeapLatencyHistogram = this.sharedArena.allocate((long) HISTOGRAM_BUCKETS * Long.BYTES, Long.BYTES);

        this.canvasRenderer = new RootShmCanvasRenderer(canvasWidth, canvasHeight);
        this.shmController = new RootShmController();
        this.shmController.setCanvasRenderer(this.canvasRenderer);
        this.shmController.initialize(shmPath.toFile(), totalSizeBytes);

        // Initialize process bridge daemon for non-blocking SHM dispatch
        String executablePath = "root"; 
        java.util.Map<String, String> env = System.getenv();
        this.processBridge = new RootProcessBridge(executablePath, env);

        initNativeErrorHooks();
        this.isAvailable = true;

        //AppLogger.success("RootBackend memory segments, SPSC topology support, zero-copy renderer, and native hooks initialized successfully.");
    }

    public boolean isAvailable() {
        return this.isAvailable;
    }

    // --- METHODS REQUIRED BY SPHERE & CORE ---

    public void initialize() {
        if (!isAvailable) {
            AppLogger.warn("RootBackend skipped initialization (ROOT not installed or ROOT_DIR not set).");
            return;
        }
        //AppLogger.info("RootBackend initialized.");
    }

    public boolean executeCling(String command) {
        if (!isAvailable) {
            AppLogger.warn("Cannot execute Cling command: ROOT backend is disabled.");
            return false;
        }
        return processBridge.pushCommand("CLING_EXEC " + command);
    }

    public RootFile openFile(String path, String mode, String handleId) {
        if (!isAvailable) {
            AppLogger.warn("Cannot open ROOT file: ROOT backend is disabled.");
            return null;
        }
        AppLogger.info("Opening ROOT file asynchronously: " + path + " [handle: " + handleId + "]");
        return new RootFile(processBridge, handleId, path, mode);
    }

    public RootProcessBridge getProcessBridge() {
        return this.processBridge;
    }

    private void initNativeErrorHooks() {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.loaderLookup();

            lookup.find("root_backend_handle_error").ifPresent(symbol -> 
                this.nativeHandleErrorMH = linker.downcallHandle(
                    symbol, 
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
                )
            );

            lookup.find("root_backend_clear_errors").ifPresent(symbol -> 
                this.nativeClearErrorsMH = linker.downcallHandle(
                    symbol, 
                    FunctionDescriptor.ofVoid()
                )
            );

            lookup.find("root_backend_get_last_error").ifPresent(symbol -> 
                this.nativeGetLastErrorMessageMH = linker.downcallHandle(
                    symbol, 
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
                )
            );
        } catch (Exception e) {
            AppLogger.warn("Native ROOT error hooks could not be linked (running without direct C++ downcalls): " + e.getMessage());
        }
    }

    private static Path resolveShmPath(SettingsManager settings) {
        String defaultPath = "rootbackend/root_backend.shm";

        if (settings == null) {
            //AppLogger.warn("SettingsManager is null, defaulting to local workspace path: " + defaultPath);
            return Paths.get(System.getProperty("user.dir")).resolve(defaultPath).toAbsolutePath().normalize();
        }

        String pathStr = settings.getProperty("SHM_PATH");
        if (pathStr == null || pathStr.isBlank()) {
            pathStr = settings.resolvePath("SHM_EXEC", defaultPath);
        }

        // Priority 1: User setting. Priority 2: Auto-inject default setting if unconfigured
        if (pathStr == null || pathStr.isBlank()) {
            AppLogger.info("SHM_PATH missing in settings.conf. Automatically assigning default path: " + defaultPath);
            pathStr = defaultPath;

            try {
                settings.setProperty("SHM_PATH", defaultPath);
                settings.save();
                AppLogger.success("Updated settings.conf with default SHM_PATH=" + defaultPath);
            } catch (Exception e) {
                AppLogger.warn("Could not save default SHM_PATH to settings.conf: " + e.getMessage());
            }
        }

        Path resolvedPath = Paths.get(System.getProperty("user.dir")).resolve(pathStr).toAbsolutePath().normalize();

        // Ensure parent directory (rootbackend/) exists before returning
        try {
            Path parentDir = resolvedPath.getParent();
            if (parentDir != null && !java.nio.file.Files.exists(parentDir)) {
                java.nio.file.Files.createDirectories(parentDir);
            }
        } catch (Exception e) {
            AppLogger.warn("Could not create parent directories for SHM file: " + e.getMessage());
        }

        return resolvedPath;
    }

    public RootShmCanvasRenderer getCanvasRenderer() {
        return this.canvasRenderer;
    }

    public RootShmController getShmController() {
        return this.shmController;
    }

    public void setCyclesToNsRatio(double ratio) {
        this.cyclesToNsRatio = ratio;
    }

    public double cyclesToNanos(long cpuCycles) {
        return cpuCycles * this.cyclesToNsRatio;
    }

    public void logErrorToShm(int errorCode, String message) {
        if (!isAvailable) return;
        try {
            long writePos = (long) WRITE_IDX_HANDLE.getAcquire(eventRingSegment);
            long capacity = (long) CAPACITY_HANDLE.getAcquire(eventRingSegment);
            if (capacity <= 0) {
                AppLogger.warn("Invalid event ring capacity for SHM error logging: " + capacity);
                return;
            }

            byte[] bytes = message != null ? message.getBytes(StandardCharsets.UTF_8) : new byte[0];
            long payloadOffset = 0L;

            if (bytes.length > 0) {
                long heapSize = dataHeapSegment.byteSize();
                payloadOffset = heapAllocationOffset.getAndAdd(bytes.length) % Math.max(1L, heapSize - bytes.length);

                MemorySegment payloadSlice = dataHeapSegment.asSlice(payloadOffset, bytes.length);
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, payloadSlice, 0, bytes.length);
            }

            long mask = capacity - 1;
            long slotOffset = RING_HEADER_LAYOUT.byteSize() + ((writePos & mask) * PACKET_HEADER_LAYOUT.byteSize());
            MemorySegment packetSlice = eventRingSegment.asSlice(slotOffset, PACKET_HEADER_LAYOUT.byteSize());

            packetSlice.set(ValueLayout.JAVA_SHORT, OFFSET_TYPE, EVT_ERROR);
            packetSlice.set(ValueLayout.JAVA_SHORT, OFFSET_FLAGS, (short) errorCode);
            packetSlice.set(ValueLayout.JAVA_INT, OFFSET_PAYLOAD_SIZE, bytes.length);
            packetSlice.set(ValueLayout.JAVA_LONG, OFFSET_PAYLOAD_OFFSET, payloadOffset);
            packetSlice.set(ValueLayout.JAVA_LONG, OFFSET_JOB_ID, Thread.currentThread().threadId());
            packetSlice.set(ValueLayout.JAVA_LONG, OFFSET_REQ_ID, System.nanoTime());

            WRITE_IDX_HANDLE.setRelease(eventRingSegment, writePos + 1);
        } catch (Exception e) {
            AppLogger.error("Failed zero-call SHM error write, falling back to native error handler.", e);
            invokeNativeErrorHandler(errorCode, message);
        }
    }

    public void invokeNativeErrorHandler(int errorCode, String message) {
        if (nativeHandleErrorMH != null) {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment cStr = localArena.allocateFrom(message);
                nativeHandleErrorMH.invokeExact(errorCode, cStr);
            } catch (Throwable t) {
                AppLogger.error("Failed to invoke native C++ error handler", t);
            }
        } else {
            AppLogger.error("Native error handler not linked. Fallback Log [Code " + errorCode + "]: " + message);
        }
    }

    public void clearNativeErrors() {
        if (nativeClearErrorsMH != null) {
            try {
                nativeClearErrorsMH.invokeExact();
            } catch (Throwable t) {
                AppLogger.error("Failed to invoke native clear errors function", t);
            }
        }
    }

    public String getLastNativeError() {
        if (nativeGetLastErrorMessageMH != null) {
            try {
                MemorySegment strPtr = (MemorySegment) nativeGetLastErrorMessageMH.invokeExact();
                if (strPtr != null && !MemorySegment.NULL.equals(strPtr)) {
                    return strPtr.getString(0);
                }
            } catch (Throwable t) {
                AppLogger.error("Failed to retrieve last native error message", t);
            }
        }
        return null;
    }

    public SpanRecord parseSpanRecord(MemorySegment spanSlice) {
        if (spanSlice == null || spanSlice.byteSize() < SPAN_RECORD_LAYOUT.byteSize()) {
            throw new IllegalArgumentException("Invalid memory slice for parsing SpanRecord.");
        }

        long tscStart  = spanSlice.get(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_START);
        long tscEnd    = spanSlice.get(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_END);
        long traceId   = spanSlice.get(ValueLayout.JAVA_LONG, SPAN_OFFSET_TRACE_ID);
        int threadId   = spanSlice.get(ValueLayout.JAVA_INT, SPAN_OFFSET_THREAD_ID);
        int jobId      = spanSlice.get(ValueLayout.JAVA_INT, SPAN_OFFSET_JOB_ID);
        int reqId      = spanSlice.get(ValueLayout.JAVA_INT, SPAN_OFFSET_REQ_ID);
        short moduleId = spanSlice.get(ValueLayout.JAVA_SHORT, SPAN_OFFSET_MODULE_ID);
        byte level     = spanSlice.get(ValueLayout.JAVA_BYTE, SPAN_OFFSET_LEVEL);

        return new SpanRecord(tscStart, tscEnd, traceId, threadId, jobId, reqId, moduleId, level);
    }

    public boolean readSpanRecordDirect(MemorySegment cellSegment, long[] outTscTrace, int[] outIds, short[] outMeta) {
        if (cellSegment == null || cellSegment.byteSize() < SPAN_RECORD_LAYOUT.byteSize()) {
            return false;
        }
        outTscTrace[0] = cellSegment.get(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_START);
        outTscTrace[1] = cellSegment.get(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_END);
        outTscTrace[2] = cellSegment.get(ValueLayout.JAVA_LONG, SPAN_OFFSET_TRACE_ID);

        outIds[0] = cellSegment.get(ValueLayout.JAVA_INT, SPAN_OFFSET_THREAD_ID);
        outIds[1] = cellSegment.get(ValueLayout.JAVA_INT, SPAN_OFFSET_JOB_ID);
        outIds[2] = cellSegment.get(ValueLayout.JAVA_INT, SPAN_OFFSET_REQ_ID);

        outMeta[0] = cellSegment.get(ValueLayout.JAVA_SHORT, SPAN_OFFSET_MODULE_ID);
        outMeta[1] = (short) cellSegment.get(ValueLayout.JAVA_BYTE, SPAN_OFFSET_LEVEL);
        return true;
    }

    public SpanRecord popSpanRecord(MemorySegment spanRingSegment) {
        if (!isAvailable || spanRingSegment == null || MemorySegment.NULL.equals(spanRingSegment)) {
            return null;
        }

        MemorySegment headerSegment = spanRingSegment.asSlice(0, SPAN_RING_HEADER_LAYOUT.byteSize());
        long capacity = (long) SPAN_CAPACITY_HANDLE.getAcquire(headerSegment);

        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            AppLogger.warn("Invalid span ring capacity in popSpanRecord: " + capacity);
            return null;
        }

        long mask = capacity - 1;
        MemorySegment slotsSegment = spanRingSegment.asSlice(SPAN_RING_HEADER_LAYOUT.byteSize());

        long pos = (long) SPAN_READ_IDX_HANDLE.getAcquire(headerSegment);
        int spinCount = 0;

        while (true) {
            long index = pos & mask;
            long cellOffset = index * SPAN_CELL_LAYOUT.byteSize();
            MemorySegment cellSegment = slotsSegment.asSlice(cellOffset, SPAN_CELL_LAYOUT.byteSize());

            long seq = (long) SPAN_CELL_SEQ_HANDLE.getAcquire(cellSegment);
            long dif = seq - (pos + 1);

            if (dif == 0) {
                if (SPAN_READ_IDX_HANDLE.compareAndSet(headerSegment, pos, pos + 1)) {
                    MemorySegment recordSlice = cellSegment.asSlice(SPAN_CELL_OFFSET_RECORD, SPAN_RECORD_LAYOUT.byteSize());
                    SpanRecord record = parseSpanRecord(recordSlice);

                    SPAN_CELL_SEQ_HANDLE.setRelease(cellSegment, pos + mask + 1);
                    recordSpanTelemetry(record.tscStart(), record.tscEnd());
                    return record;
                } else {
                    totalCasContentionRetries.incrementAndGet();
                    SPAN_CAS_CONTENTION_HANDLE.getAndAdd(headerSegment, 1L);
                }
            } else if (dif < 0) {
                return null;
            } else {
                if (++spinCount < MAX_SPIN_RETRIES) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(1_000L);
                }
                pos = (long) SPAN_READ_IDX_HANDLE.getAcquire(headerSegment);
            }
        }
    }

    public boolean pushSpanRecord(MemorySegment spanRingSegment, SpanRecord record) {
        if (!isAvailable || spanRingSegment == null || MemorySegment.NULL.equals(spanRingSegment) || record == null) {
            return false;
        }

        MemorySegment headerSegment = spanRingSegment.asSlice(0, SPAN_RING_HEADER_LAYOUT.byteSize());
        long capacity = (long) SPAN_CAPACITY_HANDLE.getAcquire(headerSegment);

        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            AppLogger.warn("Invalid span ring capacity in pushSpanRecord: " + capacity);
            return false;
        }

        long writeIdx = (long) SPAN_WRITE_IDX_HANDLE.getAcquire(headerSegment);
        long mask = capacity - 1;
        MemorySegment slotsSegment = spanRingSegment.asSlice(SPAN_RING_HEADER_LAYOUT.byteSize());

        long pos = writeIdx;
        int spinCount = 0;

        while (true) {
            long index = pos & mask;
            long cellOffset = index * SPAN_CELL_LAYOUT.byteSize();
            MemorySegment cellSegment = slotsSegment.asSlice(cellOffset, SPAN_CELL_LAYOUT.byteSize());

            long seq = (long) SPAN_CELL_SEQ_HANDLE.getAcquire(cellSegment);
            long dif = seq - pos;

            if (dif == 0) {
                if (SPAN_WRITE_IDX_HANDLE.compareAndSet(headerSegment, pos, pos + 1)) {
                    MemorySegment recordSlice = cellSegment.asSlice(SPAN_CELL_OFFSET_RECORD, SPAN_RECORD_LAYOUT.byteSize());

                    recordSlice.set(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_START, record.tscStart());
                    recordSlice.set(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_END, record.tscEnd());
                    recordSlice.set(ValueLayout.JAVA_LONG, SPAN_OFFSET_TRACE_ID, record.traceId());
                    recordSlice.set(ValueLayout.JAVA_INT, SPAN_OFFSET_THREAD_ID, record.threadId());
                    recordSlice.set(ValueLayout.JAVA_INT, SPAN_OFFSET_JOB_ID, record.jobId());
                    recordSlice.set(ValueLayout.JAVA_INT, SPAN_OFFSET_REQ_ID, record.reqId());
                    recordSlice.set(ValueLayout.JAVA_SHORT, SPAN_OFFSET_MODULE_ID, record.moduleId());
                    recordSlice.set(ValueLayout.JAVA_BYTE, SPAN_OFFSET_LEVEL, record.level());
                    recordSlice.set(ValueLayout.JAVA_BYTE, 39L, (byte) 0);

                    SPAN_CELL_SEQ_HANDLE.setRelease(cellSegment, pos + 1);
                    return true;
                } else {
                    totalCasContentionRetries.incrementAndGet();
                    SPAN_CAS_CONTENTION_HANDLE.getAndAdd(headerSegment, 1L);
                }
            } else if (dif < 0) {
                SPAN_DROPPED_COUNT_HANDLE.getAndAdd(headerSegment, 1L);
                return false;
            } else {
                if (++spinCount < MAX_SPIN_RETRIES) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(1_000L);
                }
                pos = (long) SPAN_WRITE_IDX_HANDLE.getAcquire(headerSegment);
            }
        }
    }

    public boolean pushSPSCRecord(MemorySegment spscChannelSegment, SpanRecord record) {
        if (!isAvailable) return false;
        MemorySegment txHeader = spscChannelSegment.asSlice(0, SPAN_RING_HEADER_LAYOUT.byteSize());
        long writeIdx = (long) SPAN_WRITE_IDX_HANDLE.getAcquire(txHeader);
        long readIdx  = (long) SPAN_READ_IDX_HANDLE.getAcquire(txHeader);
        long capacity = (long) SPAN_CAPACITY_HANDLE.getAcquire(txHeader);

        if ((writeIdx - readIdx) >= capacity) {
            SPAN_DROPPED_COUNT_HANDLE.getAndAdd(txHeader, 1L);
            return false;
        }

        long mask = capacity - 1;
        long cellOffset = SPAN_RING_HEADER_LAYOUT.byteSize() + ((writeIdx & mask) * SPAN_CELL_LAYOUT.byteSize());
        MemorySegment cellSegment = spscChannelSegment.asSlice(cellOffset, SPAN_CELL_LAYOUT.byteSize());
        MemorySegment recordSlice = cellSegment.asSlice(SPAN_CELL_OFFSET_RECORD, SPAN_RECORD_LAYOUT.byteSize());

        recordSlice.set(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_START, record.tscStart());
        recordSlice.set(ValueLayout.JAVA_LONG, SPAN_OFFSET_TSC_END, record.tscEnd());
        recordSlice.set(ValueLayout.JAVA_LONG, SPAN_OFFSET_TRACE_ID, record.traceId());
        recordSlice.set(ValueLayout.JAVA_INT, SPAN_OFFSET_THREAD_ID, record.threadId());
        recordSlice.set(ValueLayout.JAVA_INT, SPAN_OFFSET_JOB_ID, record.jobId());
        recordSlice.set(ValueLayout.JAVA_INT, SPAN_OFFSET_REQ_ID, record.reqId());
        recordSlice.set(ValueLayout.JAVA_SHORT, SPAN_OFFSET_MODULE_ID, record.moduleId());
        recordSlice.set(ValueLayout.JAVA_BYTE, SPAN_OFFSET_LEVEL, record.level());

        SPAN_WRITE_IDX_HANDLE.setRelease(txHeader, writeIdx + 1);
        return true;
    }

    private void recordSpanTelemetry(long tscStart, long tscEnd) {
        long cycles = tscEnd - tscStart;
        double nanos = cyclesToNanos(cycles);
        long count = totalSpansProcessed.incrementAndGet();

        if (count == 1) {
            averageSpanLatencyNanos.add(nanos);
        } else {
            double currentAvg = averageSpanLatencyNanos.sum();
            double delta = (EWMA_ALPHA * nanos) - (EWMA_ALPHA * currentAvg);
            averageSpanLatencyNanos.add(delta);
        }

        int bucket = Math.min(HISTOGRAM_BUCKETS - 1, (int) (nanos / 100.0));
        long offset = (long) bucket * Long.BYTES;
        long currentCount = offHeapLatencyHistogram.get(ValueLayout.JAVA_LONG, offset);
        offHeapLatencyHistogram.set(ValueLayout.JAVA_LONG, offset, currentCount + 1);

        double avg = averageSpanLatencyNanos.sum();
        if (nanos > LATENCY_ANOMALY_THRESHOLD_NANOS && nanos > (avg * 2.0)) {
            AppLogger.warn("Latency anomaly detected in ROOT backend span: " + nanos + " ns (avg=" + avg + " ns)");
        }
    }

    public boolean registerRuntime(MemorySegment ringSegment, int id) {
        if (!isAvailable || ringSegment == null || MemorySegment.NULL.equals(ringSegment)) {
            return false;
        }

        if (id < 0 || id >= MAX_RUNTIMES) {
            AppLogger.warn("Attempted to register runtime ID out of bounds: " + id);
            return false;
        }

        CLUSTER_RUNTIME_IDS_HANDLE.setRelease(ringSegment, (long) id, 1);
        AppLogger.debug("Registered runtime node ID: " + id);
        return true;
    }

    public boolean registerCommandRuntime(int id) {
        return registerRuntime(commandRingSegment, id);
    }

    public boolean registerEventRuntime(int id) {
        return registerRuntime(eventRingSegment, id);
    }

    public void unregisterRuntime(MemorySegment ringSegment, int id) {
        if (!isAvailable || ringSegment == null || MemorySegment.NULL.equals(ringSegment)) {
            return;
        }

        if (id < 0 || id >= MAX_RUNTIMES) {
            AppLogger.warn("Attempted to unregister runtime ID out of bounds: " + id);
            return;
        }

        CLUSTER_RUNTIME_IDS_HANDLE.setRelease(ringSegment, (long) id, 0);
        AppLogger.debug("Unregistered runtime node ID: " + id);
    }

    public void unregisterCommandRuntime(int id) {
        unregisterRuntime(commandRingSegment, id);
    }

    public void unregisterEventRuntime(int id) {
        unregisterRuntime(eventRingSegment, id);
    }

    public boolean isRuntimeRegistered(MemorySegment ringSegment, int id) {
        if (!isAvailable || ringSegment == null || MemorySegment.NULL.equals(ringSegment) || id < 0 || id >= MAX_RUNTIMES) {
            return false;
        }
        int status = (int) CLUSTER_RUNTIME_IDS_HANDLE.getAcquire(ringSegment, (long) id);
        return status != 0;
    }

    public long getDroppedCount(MemorySegment ringSegment) {
        if (!isAvailable || ringSegment == null || MemorySegment.NULL.equals(ringSegment)) {
            return 0L;
        }
        return (long) DROPPED_COUNT_HANDLE.getAcquire(ringSegment);
    }

    public WorkerMetrics readWorkerMetrics(MemorySegment metricsBaseSegment, int workerIndex) {
        if (!isAvailable || metricsBaseSegment == null || MemorySegment.NULL.equals(metricsBaseSegment)) {
            return new WorkerMetrics(0L, 0L);
        }
        if (workerIndex < 0 || workerIndex >= MAX_WORKERS) {
            AppLogger.warn("Worker index out of bounds in readWorkerMetrics: " + workerIndex);
            return new WorkerMetrics(0L, 0L);
        }
        long offset = (long) workerIndex * WORKER_METRICS_LAYOUT.byteSize();
        MemorySegment workerSegment = metricsBaseSegment.asSlice(offset, WORKER_METRICS_LAYOUT.byteSize());
        long jobs = (long) METRICS_JOBS_HANDLE.getAcquire(workerSegment);
        long busyCycles = (long) METRICS_BUSY_CYCLES_HANDLE.getAcquire(workerSegment);
        return new WorkerMetrics(jobs, busyCycles);
    }

    public void updateHeartbeat(MemorySegment ringHeaderSegment, long tscTimestamp) {
        if (!isAvailable || ringHeaderSegment == null || MemorySegment.NULL.equals(ringHeaderSegment)) {
            return;
        }
        LAST_HEARTBEAT_TSC_HANDLE.setRelease(ringHeaderSegment, tscTimestamp);
    }

    public boolean isHeartbeatStale(MemorySegment ringHeaderSegment, long currentTsc, long thresholdCycles) {
        if (!isAvailable || ringHeaderSegment == null || MemorySegment.NULL.equals(ringHeaderSegment)) {
            return true;
        }
        long last = (long) LAST_HEARTBEAT_TSC_HANDLE.getAcquire(ringHeaderSegment);
        long delta = currentTsc - last;
        return last == 0L || delta > thresholdCycles || delta < 0L;
    }

    public void executeClusterWatchdog(MemorySegment ringHeaderSegment, long currentTsc, long staleThresholdCycles) {
        if (!isAvailable || ringHeaderSegment == null || MemorySegment.NULL.equals(ringHeaderSegment)) {
            return;
        }

        for (int id = 0; id < MAX_RUNTIMES; id++) {
            if (isRuntimeRegistered(ringHeaderSegment, id)) {
                if (isHeartbeatStale(ringHeaderSegment, currentTsc, staleThresholdCycles)) {
                    AppLogger.warn("Node crash detected on cluster runtime ID " + id + ". Executing automated failover recovery.");
                    unregisterRuntime(ringHeaderSegment, id);
                }
            }
        }
    }

    @Override
    public String getName() {
        return "ROOT Backend";
    }

    @Override
    public void execute(String command) {
        if (!isAvailable) {
            AppLogger.warn("ROOT Backend is disabled. Cannot execute command: " + command);
            return;
        }
        AppLogger.info("RootBackend dispatching non-blocking command over SHM: " + command);
        if (processBridge != null) {
            processBridge.pushCommand(command);
        }
    }

    @Override
    public void activate() {
        if (!isAvailable) {
            AppLogger.warn("ROOT Backend activation skipped (ROOT not available).");
            return;
        }
        AppLogger.info("RootBackend activated.");
    }

    @Override
    public void close() {
        if (processBridge != null) {
            processBridge.close();
            AppLogger.info("RootBackend process bridge daemon shut down.");
        }
        if (shmController != null) {
            shmController.close();
            AppLogger.info("RootBackend SHM controller shut down.");
        }
        if (sharedArena != null && sharedArena.scope().isAlive()) {
            sharedArena.close();
            AppLogger.info("RootBackend shared memory arena closed.");
        }
        this.isAvailable = false;
    }

    public record SpanRecord(long tscStart, long tscEnd, long traceId, int threadId, int jobId, int reqId, short moduleId, byte level) {}

    public record WorkerMetrics(long jobs, long busyCycles) {}
}