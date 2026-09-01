package com.sphere.core.rootbackend;

import com.sphere.core.Backend;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
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
 * Primary orchestrator for the ROOT backend environment
 */
public final class RootBackend implements AutoCloseable, Backend {

    // VarHandle utility
    private static final VarHandle RAW_LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle RAW_INT_HANDLE = ValueLayout.JAVA_INT.varHandle();

    // Status flag tracking whether ROOT is properly configured on the host system
    private boolean isAvailable = false;

    // Topology and cluster constants (common_config.h)
    public static final int MAX_RUNTIMES = 64;   // was 32; the C++ side has 64
    public static final int MAX_WORKERS = 64;
    public static final long CACHE_LINE_SIZE = 64L;

    public static final int SHM_MAGIC = 0x53504852; // 'SPHR'
    public static final int SHM_VERSION = 2;
    public static final int PROTO_VERSION = 2;

    // ---- ShmHeader (inc/shm_layout.h), 1408 bytes at region offset 0 --------
    public static final long HDR_MAGIC            = 0L;
    public static final long HDR_VERSION          = 4L;
    public static final long HDR_ABI_VERSION      = 8L;
    public static final long HDR_PROTO_VERSION    = 12L;
    public static final long HDR_STATE            = 16L;
    public static final long HDR_ENGINE_FLAGS     = 20L;
    public static final long HDR_LAST_ERROR_CODE  = 24L;
    public static final long HDR_TOTAL_SIZE       = 32L;
    public static final long HDR_HEARTBEAT_CPP    = 64L;
    public static final long HDR_HEARTBEAT_JAVA   = 128L;
    public static final long HDR_ENGINE_CYCLES    = 192L;
    public static final long HDR_JOBS_COMPLETED   = 256L;
    public static final long HDR_JOBS_FAILED      = 320L;
    public static final long HDR_RAW_OCCUPANCY    = 384L;

    public static final long HDR_OFF_CMD_RING       = 448L;
    public static final long HDR_SIZE_CMD_RING      = 456L;
    public static final long HDR_CMD_RING_CAPACITY  = 464L;  // slot COUNT
    public static final long HDR_CMD_RING_SLOT_SIZE = 472L;
    public static final long HDR_OFF_EVT_RING       = 480L;
    public static final long HDR_SIZE_EVT_RING      = 488L;
    public static final long HDR_EVT_RING_CAPACITY  = 496L;  // slot COUNT
    public static final long HDR_EVT_RING_SLOT_SIZE = 504L;
    public static final long HDR_OFF_ENGINE_STATS   = 512L;
    public static final long HDR_SIZE_ENGINE_STATS  = 520L;
    public static final long HDR_OFF_SPAN_RING      = 528L;
    public static final long HDR_SIZE_SPAN_RING     = 536L;
    public static final long HDR_SPAN_RING_CAPACITY = 544L;
    public static final long HDR_OFF_SCHEMA_HEAP    = 552L;
    public static final long HDR_SIZE_SCHEMA_HEAP   = 560L;
    public static final long HDR_OFF_TX_LOG         = 568L;
    public static final long HDR_SIZE_TX_LOG        = 576L;
    public static final long HDR_OFF_DATA_HEAP      = 584L;
    public static final long HDR_SIZE_DATA_HEAP     = 592L;
    public static final long HDR_JOURNAL_CAPACITY   = 600L;

    public static final long HDR_CLUSTER_RUNTIME_IDS = 640L; // 64 x int32
    public static final long HDR_READER_EPOCHS       = 896L; // 64 x int64

    public static final long RING_ENQUEUE_POS_OFFSET = 0L;
    public static final long RING_DEQUEUE_POS_OFFSET = 64L;
    public static final long RING_INIT_MAGIC_OFFSET  = 128L;
    public static final long RING_BUFFER_BASE_OFFSET = 192L;
    public static final long RING_INIT_MAGIC = 0x52494E47494E4954L; // "RINGINIT"

    // Cell: an 8-byte sequence padded to a cache line, then the 64-byte message.
    public static final long CELL_SIZE = 128L;
    public static final long CELL_SEQ_OFFSET = 0L;
    public static final long CELL_DATA_OFFSET = 64L;

    // Ring Buffer Structural Offsets and Dimensions (aligned with C++ ShmRegion layout)
    public static final long BRIDGE_MESSAGE_SIZE = 64L;
    public static final long MSG_OFF_TYPE         = 0L;  // MsgType, 1 byte
    public static final long MSG_OFF_PAYLOAD_SIZE = 1L;  // 1 byte
    public static final long MSG_OFF_CMD          = 2L;  // PacketType, 2 bytes
    public static final long MSG_OFF_FLAGS        = 4L;  // 2 bytes
    public static final long MSG_OFF_RESERVED     = 6L;  // 2 bytes, must be 0
    public static final long MSG_OFF_JOB_ID       = 8L;  // 4 bytes
    public static final long MSG_OFF_REQ_ID       = 12L; // 4 bytes
    public static final long MSG_OFF_JOURNAL_SEQ  = 16L; // 4 bytes
    public static final long MSG_OFF_UNION        = 20L; // inline bytes or ShmRef
    public static final int  BRIDGE_INLINE_CAPACITY = 44;

    // MsgType (transport discriminator at MSG_OFF_TYPE)
    public static final byte MSG_TYPE_EMPTY       = 0;
    public static final byte MSG_TYPE_INLINE_DATA = 1;
    public static final byte MSG_TYPE_SHM_REF     = 2;

    // ---- ShmRef, 36 bytes, overlaid on the union at MSG_OFF_UNION ----------
    public static final long REF_OFF_OFFSET      = 0L;  // uint32 from region base
    public static final long REF_OFF_TOTAL_BYTES = 4L;  // uint32
    public static final long REF_OFF_DTYPE       = 8L;  // 1 byte
    public static final long REF_OFF_NDIM        = 9L;  // 1 byte
    public static final long REF_OFF_RESERVED    = 10L; // 2 bytes
    public static final long REF_OFF_SHAPE       = 12L; // 6 x uint32
    public static final int  SHM_REF_MAX_DIMS    = 6;

    // ShmDType (inc/lockfree_ring.h)
    public static final byte DTYPE_FLOAT32 = 0;
    public static final byte DTYPE_FLOAT16 = 1;
    public static final byte DTYPE_INT32   = 2;
    public static final byte DTYPE_INT64   = 3;
    public static final byte DTYPE_UINT8   = 4;
    public static final byte DTYPE_BFLOAT16 = 5;
    public static final byte DTYPE_FLOAT64 = 6;
    public static final byte DTYPE_UINT32  = 7;
    public static final byte DTYPE_UINT64  = 8;
    public static final byte DTYPE_INT16   = 9;
    public static final byte DTYPE_UINT16  = 10;
    public static final byte DTYPE_INT8    = 11;

    // Logger severity levels (logger.h)
    public static final short LOG_LEVEL_TRACE = 0;
    public static final short LOG_LEVEL_DEBUG = 1;
    public static final short LOG_LEVEL_INFO  = 2;
    public static final short LOG_LEVEL_WARN  = 3;
    public static final short LOG_LEVEL_ERROR = 4;
    public static final short LOG_LEVEL_FATAL = 5;

    // Subsystem module identifiers (logger.h, span_record.h)
    public static final short MODULE_GENERIC = 0;
    public static final short MODULE_FILE    = 1;
    public static final short MODULE_IOURING = 2;
    public static final short MODULE_ROOT    = 3;
    public static final short MODULE_WORKER  = 4;
    public static final short MODULE_SHM     = 5;
    public static final short MODULE_MAX     = 32;

    // Ring drop policy (ringbuffer.h)
    public static final int DROP_POLICY_BLOCK       = 0;
    public static final int DROP_POLICY_DROP_OLDEST = 1;
    public static final int DROP_POLICY_DROP_NEWEST = 2;

    // Spin-wait backoff thresholds
    private static final int MAX_SPIN_RETRIES = 10;

    // Intelligence & telemetry calibration
    private static final double EWMA_ALPHA = 0.05;
    private static final double LATENCY_ANOMALY_THRESHOLD_NANOS = 1_000_000.0;
    private static final int HISTOGRAM_BUCKETS = 64;

    // Command Identifiers (matching Platform::PacketType in packets.h)
    public static final short CMD_PING            = 1;
    public static final short CMD_OPEN_FILE       = 2;
    public static final short CMD_CLOSE_FILE      = 3;
    public static final short CMD_CLOSE_ALL_FILES = 4;
    public static final short CMD_SAVE_FILE       = 5;
    public static final short CMD_SCHEMA_DISCOVER = 6;

    // System Operations Commands
    public static final short CMD_SYS_NOOP        = 10;
    public static final short CMD_SYS_VERSION     = 11;
    public static final short CMD_SYS_UPTIME      = 12;
    public static final short CMD_SYS_CONFIG      = 13;
    public static final short CMD_CLING_EXEC      = 14;

    public static final short CMD_TTREE_INSPECT       = 20;
    public static final short CMD_TTREE_QUERY_ENTRIES = 21;
    public static final short CMD_TTREE_SCAN_BRANCHES = 22;
    public static final short CMD_TTREE_GET_ENTRY     = 23;
    public static final short CMD_TTREE_READ_COLUMN   = 24;
    public static final short CMD_TTREE_COMPUTE_STATS = 25;
    public static final short CMD_TTREE_APPLY_FILTER  = 26;

    // Telemetry Event Identifiers (Responses & Acknowledgments)
    public static final short EVT_OK              = 100;
    public static final short EVT_PONG            = 101;
    public static final short EVT_ERROR           = 102;
    public static final short EVT_FILE_OPENED     = 103;
    public static final short EVT_FILE_CLOSED     = 104;
    public static final short EVT_SCHEMA_READY    = 105;

    // System Telemetry Events
    public static final short EVT_SYS_VERSION     = 110;
    public static final short EVT_SYS_UPTIME      = 111;
    public static final short EVT_SYS_CONFIG      = 112;

    public static final short EVT_BACKPRESSURE      = 120;
    public static final short EVT_DEADLINE_EXCEEDED = 121;

    public static final short PKT_FLAG_NONE        = 0x0000;
    public static final short PKT_FLAG_HAS_PAYLOAD = 0x0001;
    public static final short PKT_FLAG_URGENT      = 0x0002;
    public static final short PKT_FLAG_NO_REPLY    = 0x0004;

    // Memory Segment References for Foreign Function & Memory API (FFM)
    private Arena sharedArena;
    private MemorySegment shmBaseSegment;
    private MemorySegment commandRingSegment;
    private MemorySegment eventRingSegment;
    private MemorySegment dataHeapSegment;
    private MemorySegment offHeapLatencyHistogram;

    // Ring geometry read from the partition table at attach time.
    private int cmdRingCapacityPow2 = 10;
    private int evtRingCapacityPow2 = 10;

    // One pump per JVM. Every instance maps the same region, so two pumps on one
    // event ring dequeue each other's replies and drop them as unmatched.
    private static final java.util.concurrent.ConcurrentHashMap<Integer,
            java.util.concurrent.ArrayBlockingQueue<BridgeEvent>> pendingReplies =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ArrayBlockingQueue<BridgeEvent> unmatchedEvents =
        new java.util.concurrent.ArrayBlockingQueue<>(1024);
    private static final Object PUMP_LOCK = new Object();
    private static Thread eventPump;
    private static volatile boolean pumping;
    private static volatile RootBackend pumpOwner;
    private static volatile RootProcessBridge sharedProcessBridge;
    private static volatile RootBackend processBridgeOwner;

    // Subsystem Component Drivers
    private RootShmCanvasRenderer canvasRenderer;
    private RootShmController shmController;
    private RootProcessBridge processBridge;

    // High-Performance Off-Heap Offsets & Statistics Counters
    private final AtomicLong heapAllocationOffset = new AtomicLong(0L);
    // Shared with the pump's reply table: two instances must never mint the same id.
    private static final java.util.concurrent.atomic.AtomicInteger nextJobId =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger nextReqId =
        new java.util.concurrent.atomic.AtomicInteger(
            new java.util.Random().nextInt(1 << 30));
    private final AtomicLong totalSpansProcessed = new AtomicLong(0L);
    private final DoubleAdder averageSpanLatencyNanos = new DoubleAdder();
    private final AtomicLong totalCasContentionRetries = new AtomicLong(0L);
    private double cyclesToNsRatio = 1.0;

    // Foreign Memory Layout Declarations
    public static final StructLayout WORKER_METRICS_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("jobs"),
        ValueLayout.JAVA_LONG.withName("busy_cycles"),
        MemoryLayout.paddingLayout(48)
    );

    public static final StructLayout SPAN_RECORD_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("tsc_start"),
        ValueLayout.JAVA_LONG.withName("tsc_end"),
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

    // Span Record Structural Offsets
    private static final long SPAN_OFFSET_TSC_START = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("tsc_start"));
    private static final long SPAN_OFFSET_TSC_END   = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("tsc_end"));
    private static final long SPAN_OFFSET_THREAD_ID = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("thread_id"));
    private static final long SPAN_OFFSET_JOB_ID    = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("job_id"));
    private static final long SPAN_OFFSET_REQ_ID    = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("req_id"));
    private static final long SPAN_OFFSET_MODULE_ID = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("module_id"));
    private static final long SPAN_OFFSET_LEVEL     = SPAN_RECORD_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("level"));

    private static final long SPAN_CELL_OFFSET_RECORD = SPAN_CELL_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("record"));
    private static final long SPAN_CELL_OFFSET_SEQ    = SPAN_CELL_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("seq"));

    public static final StructLayout RING_HEADER_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("write_idx"),          // 0
        MemoryLayout.paddingLayout(56),
        ValueLayout.JAVA_LONG.withName("read_idx"),            // 64
        MemoryLayout.paddingLayout(56),
        ValueLayout.JAVA_LONG.withName("capacity"),            // 128
        ValueLayout.JAVA_INT.withName("slot_size"),            // 136
        ValueLayout.JAVA_INT.withName("high_watermark"),       // 140
        ValueLayout.JAVA_INT.withName("low_watermark"),        // 144
        ValueLayout.JAVA_INT.withName("max_capacity"),         // 148
        ValueLayout.JAVA_INT.withName("sample_rate"),          // 152
        ValueLayout.JAVA_INT.withName("tsc_sample_mask"),      // 156
        ValueLayout.JAVA_INT.withName("drop_policy"),          // 160
        MemoryLayout.paddingLayout(28),
        ValueLayout.JAVA_LONG.withName("hotness_counter"),     // 192
        ValueLayout.JAVA_LONG.withName("journal_seq"),         // 200
        ValueLayout.JAVA_LONG.withName("tsc_last_sample"),     // 208
        ValueLayout.JAVA_LONG.withName("dropped_count"),       // 216
        ValueLayout.JAVA_INT.withName("ebpf_slot_id"),         // 224
        ValueLayout.JAVA_INT.withName("io_fd"),                // 228
        ValueLayout.JAVA_LONG.withName("write_claim"),         // 232
        ValueLayout.JAVA_LONG.withName("read_claim"),          // 240
        MemoryLayout.paddingLayout(8),
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

    // Variable Handles for Atomic Access via FFM Memory Layouts
    private static final VarHandle WRITE_IDX_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("write_idx"));
    private static final VarHandle READ_IDX_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("read_idx"));
    private static final VarHandle CAPACITY_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("capacity"));
    private static final VarHandle SLOT_SIZE_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("slot_size"));
    private static final VarHandle DROP_POLICY_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("drop_policy"));
    private static final VarHandle DROPPED_COUNT_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dropped_count"));
    private static final VarHandle TSC_LAST_SAMPLE_HANDLE = RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("tsc_last_sample"));

    private static final VarHandle SPAN_WRITE_IDX_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("write_index"));
    private static final VarHandle SPAN_READ_IDX_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("read_index"));
    private static final VarHandle SPAN_CAPACITY_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("capacity"));
    private static final VarHandle SPAN_DROPPED_COUNT_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dropped_count"));
    private static final VarHandle SPAN_CAS_CONTENTION_HANDLE = SPAN_RING_HEADER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("cas_contention_count"));

    private static final VarHandle SPAN_CELL_SEQ_HANDLE = SPAN_CELL_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("seq"));

    private static final VarHandle METRICS_JOBS_HANDLE = WORKER_METRICS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("jobs"));
    private static final VarHandle METRICS_BUSY_CYCLES_HANDLE = WORKER_METRICS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("busy_cycles"));

    // Native Method Handles for Direct Native C++ Error Management
    private MethodHandle nativeHandleErrorMH;
    private MethodHandle nativeClearErrorsMH;
    private MethodHandle nativeGetLastErrorMessageMH;

    public boolean isAvailable() {
        return this.isAvailable;
    }

    public void setAvailable(boolean available) {
        this.isAvailable = available;
    }

    // The application's live backend, or null when none has come up.
    private static volatile RootBackend INSTANCE;

    public static RootBackend getInstance() {
        return INSTANCE;
    }

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

    /**
     * Initializes the Shared Memory (SHM) regions.
     */
    private void initShmMemory(Path shmPath, long totalSizeBytes, int canvasWidth, int canvasHeight) throws Exception {
        Objects.requireNonNull(shmPath, "SHM path cannot be null.");
        if (totalSizeBytes > 0L && totalSizeBytes <= MIN_REGION_BYTES) {
            AppLogger.warn("Requested SHM size " + totalSizeBytes
                + " is below the minimum region size; the mapped region's own "
                + "size will be used instead.");
        }

        // Prevent memory-mapping the executable binary directly (avoids Linux "Text file busy" / error 26)
        String fileName = shmPath.getFileName().toString();
        if (fileName.equals("root-bridge") || fileName.equals("root-bridge.exe")) {
            shmPath = shmPath.getParent() != null
                ? shmPath.getParent().resolve("root_backend.shm")
                : Paths.get("rootbackend", "root_backend.shm");
        }

        this.sharedArena = Arena.ofShared();

        Path binaryForInit = Paths.get(System.getProperty("user.dir"), "rootbackend",
            System.getProperty("os.name").toLowerCase().contains("win")
                ? "root-bridge.exe" : "root-bridge");
        ensureSharedRegionExists(binaryForInit, resolveSharedRegionPath(shmPath));

        Path resolved = resolveSharedRegionPath(shmPath);
        long mappedSize;

        // Memory-map the SHM file directly into an off-heap MemorySegment
        try (RandomAccessFile file = new RandomAccessFile(resolved.toFile(), "rw");
            FileChannel channel = file.getChannel()) {

            mappedSize = channel.size();
            if (mappedSize < MIN_REGION_BYTES) {
                throw new IllegalStateException(
                    "Shared region " + resolved + " is only " + mappedSize
                    + " bytes. Start the engine with --init-shm first.");
            }

            this.shmBaseSegment = channel.map(
                FileChannel.MapMode.READ_WRITE,
                0,
                mappedSize,
                this.sharedArena
            );
        }

        long baseAddress = this.shmBaseSegment.address();
        if ((baseAddress % CACHE_LINE_SIZE) != 0) {
            AppLogger.warn(String.format("SHM Base address 0x%X is NOT aligned to a 64-byte cache line boundary!", baseAddress));
        }

        verifyRegionHeader();

        this.cmdRingCapacityPow2 = capacityToPow2(
            this.shmBaseSegment.get(ValueLayout.JAVA_LONG, HDR_CMD_RING_CAPACITY));
        this.evtRingCapacityPow2 = capacityToPow2(
            this.shmBaseSegment.get(ValueLayout.JAVA_LONG, HDR_EVT_RING_CAPACITY));

        this.commandRingSegment = slicePartition(HDR_OFF_CMD_RING, HDR_SIZE_CMD_RING, "command ring");
        this.eventRingSegment   = slicePartition(HDR_OFF_EVT_RING, HDR_SIZE_EVT_RING, "event ring");
        this.dataHeapSegment    = slicePartition(HDR_OFF_DATA_HEAP, HDR_SIZE_DATA_HEAP, "data heap");

        // Allocate off-heap buffer for latency histogram tracking
        this.offHeapLatencyHistogram = this.sharedArena.allocate((long) HISTOGRAM_BUCKETS * Long.BYTES, Long.BYTES);

        // Initialize Java canvas renderer and controller
        this.canvasRenderer = new RootShmCanvasRenderer(canvasWidth, canvasHeight);
        this.shmController = new RootShmController();
        this.shmController.setCanvasRenderer(this.canvasRenderer);
        this.shmController.initialize(shmPath.toFile(), totalSizeBytes);

        // Resolve native root-bridge binary path
        Path rootBridgePath = Paths.get(System.getProperty("user.dir"), "rootbackend",
            System.getProperty("os.name").toLowerCase().contains("win") ? "root-bridge.exe" : "root-bridge");

        String executablePath = java.nio.file.Files.exists(rootBridgePath)
            ? rootBridgePath.toAbsolutePath().toString()
            : "root";

        // Perform diagnostic ping-pong execution with native binary
        // It pops the event ring itself, so never while a pump already owns it.
        if (!pumping && java.nio.file.Files.exists(rootBridgePath)) {
            try {
                ProcessBuilder pingBuilder = new ProcessBuilder(
                        executablePath, "--ping", "--shm", resolved.toString());
                pingBuilder.directory(rootBridgePath.getParent().toFile());
                Process pingProcess = pingBuilder.start();
                boolean pingSuccess = false;

                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(pingProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.toUpperCase().contains("PONG")) {
                            pingSuccess = true;
                            break;
                        }
                    }
                }

                pingProcess.waitFor();

                if (pingSuccess) {
                    //AppLogger.info("RootBridge binary ping successful: Received 'PONG' signal.");
                } else {
                    AppLogger.warn("RootBridge ping failed. 'PONG' token not detected in execution output.");
                }
            } catch (Exception e) {
                AppLogger.error("Failed to execute root-bridge ping test: " + e.getMessage());
            }
        }


        drainStaleEvents();

        startEventPump();

        // Write initial Java heartbeat timestamp into SHM header
        sendJavaHeartbeat();

        java.util.Map<String, String> env = System.getenv();

        // One engine per region: a second --serve is refused by the file lock.
        synchronized (PUMP_LOCK) {
            if (sharedProcessBridge != null) {
                this.processBridge = sharedProcessBridge;
            } else {
                this.processBridge = new RootProcessBridge(executablePath, env);
                try {
                    processBridge.start(resolved.toFile(), mappedSize);
                    sharedProcessBridge = this.processBridge;
                    processBridgeOwner = this;
                } catch (Exception e) {
                    AppLogger.error("Could not start the root-bridge engine: " + e.getMessage()
                        + ". Commands will queue in the ring with nothing to consume them.");
                }
            }
        }

        // The engine bumps heartbeat_cpp on every scheduler iteration, and its
        // scheduler starts only after ROOT and the interpreter are up. Waiting for
        // that counter to move is proof it drains the ring; a fixed sleep was a
        // guess that a cold ROOT start could not meet.
        waitForEngineHeartbeat(ENGINE_READY_TIMEOUT_MS);

        initNativeErrorHooks();
        this.isAvailable = true;

        synchronized (RootBackend.class) {
            if (INSTANCE == null) {
                INSTANCE = this;
            }
        }

        //AppLogger.success("RootBackend memory segments and C++ MPMC Ring Buffer interop initialized successfully.");
    }

    /**
    * Pushes a BridgeMessage into the C++ MPMC Ring Buffer using lock-free atomic operations.
    */
    /** Same as pushCommandMessage, but the payload already sits in the heap. */
    public static boolean pushCommandRef(MemorySegment ringSegment, int capacityPow2,
                                         short opcode, int jobId, int reqId,
                                         long heapOffset, int byteCount) {
        final long capacity = 1L << capacityPow2;
        final long mask = capacity - 1L;
        long pos = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, RING_ENQUEUE_POS_OFFSET);

        for (;;) {
            final long cellOffset = RING_BUFFER_BASE_OFFSET + ((pos & mask) * CELL_SIZE);
            final long seqOffset = cellOffset + CELL_SEQ_OFFSET;
            final long dataOffset = cellOffset + CELL_DATA_OFFSET;
            final long seq = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, seqOffset);
            final long dif = seq - pos;

            if (dif == 0) {
                final long witness = (long) RAW_LONG_HANDLE.compareAndExchange(
                    ringSegment, RING_ENQUEUE_POS_OFFSET, pos, pos + 1L);
                if (witness == pos) {
                    ringSegment.asSlice(dataOffset, BRIDGE_MESSAGE_SIZE).fill((byte) 0);
                    ringSegment.set(ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_TYPE,
                                    MSG_TYPE_SHM_REF);
                    ringSegment.set(ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_PAYLOAD_SIZE,
                                    (byte) 0);
                    ringSegment.set(ValueLayout.JAVA_SHORT, dataOffset + MSG_OFF_CMD, opcode);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_JOB_ID, jobId);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_REQ_ID, reqId);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_UNION,
                                    (int) heapOffset);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_UNION + 4,
                                    byteCount);
                    RAW_LONG_HANDLE.setRelease(ringSegment, seqOffset, pos + 1L);
                    return true;
                }
                pos = witness;
            } else if (dif < 0) {
                return false;
            } else {
                pos = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, RING_ENQUEUE_POS_OFFSET);
            }
        }
    }

    public static boolean pushCommandMessage(MemorySegment ringSegment, int capacityPow2,
                                      short opcode, int jobId, int reqId, byte[] payload) {
        final long capacity = 1L << capacityPow2;
        final long mask = capacity - 1L;

        // Slot stolen by another thread, reload position and retry
        long pos = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, RING_ENQUEUE_POS_OFFSET);

        for (int spins = 0; ; ) {
            final long cellOffset = RING_BUFFER_BASE_OFFSET + ((pos & mask) * CELL_SIZE);
            final long seqOffset = cellOffset + CELL_SEQ_OFFSET;
            final long dataOffset = cellOffset + CELL_DATA_OFFSET;

            final long seq = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, seqOffset);
            final long dif = seq - pos;

            if (dif == 0) {
                // Perform atomic CAS (Compare-And-Swap) on enqueue_pos_ to claim the slot
                final long witness = (long) RAW_LONG_HANDLE.compareAndExchange(
                    ringSegment, RING_ENQUEUE_POS_OFFSET, pos, pos + 1L);

                if (witness == pos) {
                    // Slice the base SHM segment into functional memory regions
                    ringSegment.asSlice(dataOffset, BRIDGE_MESSAGE_SIZE).fill((byte) 0);

                    final int inlineLength = (payload != null)
                        ? Math.min(payload.length, BRIDGE_INLINE_CAPACITY) : 0;

                    // Slot reserved successfully. Populate the BridgeMessage structure (64 Bytes)
                    ringSegment.set(ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_TYPE,
                                    MSG_TYPE_INLINE_DATA);
                    ringSegment.set(ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_PAYLOAD_SIZE,
                                    (byte) inlineLength);
                    ringSegment.set(ValueLayout.JAVA_SHORT, dataOffset + MSG_OFF_CMD, opcode);
                    ringSegment.set(ValueLayout.JAVA_SHORT, dataOffset + MSG_OFF_FLAGS, (short) 0);
                    ringSegment.set(ValueLayout.JAVA_SHORT, dataOffset + MSG_OFF_RESERVED, (short) 0);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_JOB_ID, jobId);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_REQ_ID, reqId);
                    ringSegment.set(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_JOURNAL_SEQ, 0);

                    if (inlineLength > 0) {
                        MemorySegment.copy(MemorySegment.ofArray(payload), 0L,
                                           ringSegment, dataOffset + MSG_OFF_UNION, inlineLength);
                    }

                    RAW_LONG_HANDLE.setRelease(ringSegment, seqOffset, pos + 1L);
                    return true;
                }
                // Lost the race; witness already holds the current position.
                pos = witness;
            } else if (dif < 0) {
                // Ring buffer is full
                return false; // ring full
            } else {
                pos = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, RING_ENQUEUE_POS_OFFSET);
            }

            if (++spins >= MAX_SPIN_RETRIES) {
                // Execute CPU pause hint for spin-wait efficiency
                Thread.onSpinWait();
                spins = 0;
            }
        }
    }

    // One decoded event from the C++ engine.
    public record BridgeEvent(byte transport, short cmd, short flags, int jobId, int reqId,
                              byte[] inlineBytes, long shmOffset, long shmBytes,
                              byte dtype, int[] shape) {
        public boolean isShmRef() { return transport == MSG_TYPE_SHM_REF; }
        public boolean isError()  { return cmd == EVT_ERROR; }

        public int status() {
            if (!isError() || inlineBytes == null || inlineBytes.length < 2) {
                return 0;
            }
            return (inlineBytes[0] & 0xFF) | ((inlineBytes[1] & 0xFF) << 8);
        }

        public String message() {
            if (inlineBytes == null) {
                return "";
            }
            final int from = isError() ? 2 : 0;
            if (inlineBytes.length <= from) {
                return "";
            }
            return new String(inlineBytes, from, inlineBytes.length - from,
                              StandardCharsets.UTF_8);
        }
    }

    public static BridgeEvent pollEventMessage(MemorySegment ringSegment, int capacityPow2) {
        final long capacity = 1L << capacityPow2;
        final long mask = capacity - 1L;

        long pos = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, RING_DEQUEUE_POS_OFFSET);

        for (int spins = 0; ; ) {
            final long cellOffset = RING_BUFFER_BASE_OFFSET + ((pos & mask) * CELL_SIZE);
            final long seqOffset = cellOffset + CELL_SEQ_OFFSET;
            final long dataOffset = cellOffset + CELL_DATA_OFFSET;

            final long seq = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, seqOffset);
            final long dif = seq - (pos + 1L);

            if (dif == 0) {
                final long witness = (long) RAW_LONG_HANDLE.compareAndExchange(
                    ringSegment, RING_DEQUEUE_POS_OFFSET, pos, pos + 1L);

                if (witness == pos) {
                    final BridgeEvent event = decodeBridgeMessage(ringSegment, dataOffset);
                    // Release store memory barrier: update sequence to signal C++ consumer thread (seq = pos + 1)
                    RAW_LONG_HANDLE.setRelease(ringSegment, seqOffset, pos + mask + 1L);
                    return event;
                }
                pos = witness;
            } else if (dif < 0) {
                return null; // ring empty
            } else {
                pos = (long) RAW_LONG_HANDLE.getVolatile(ringSegment, RING_DEQUEUE_POS_OFFSET);
            }

            if (++spins >= MAX_SPIN_RETRIES) {
                Thread.onSpinWait();
                spins = 0;
            }
        }
    }

    private static BridgeEvent decodeBridgeMessage(MemorySegment ringSegment, long dataOffset) {
        final byte transport = ringSegment.get(ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_TYPE);
        final int payloadSize = Byte.toUnsignedInt(
            ringSegment.get(ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_PAYLOAD_SIZE));
        final short cmd = ringSegment.get(ValueLayout.JAVA_SHORT, dataOffset + MSG_OFF_CMD);
        final short flags = ringSegment.get(ValueLayout.JAVA_SHORT, dataOffset + MSG_OFF_FLAGS);
        final int jobId = ringSegment.get(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_JOB_ID);
        final int reqId = ringSegment.get(ValueLayout.JAVA_INT, dataOffset + MSG_OFF_REQ_ID);

        if (transport == MSG_TYPE_SHM_REF) {
            final long refBase = dataOffset + MSG_OFF_UNION;
            final long offset = Integer.toUnsignedLong(
                ringSegment.get(ValueLayout.JAVA_INT, refBase + REF_OFF_OFFSET));
            final long bytes = Integer.toUnsignedLong(
                ringSegment.get(ValueLayout.JAVA_INT, refBase + REF_OFF_TOTAL_BYTES));
            final byte dtype = ringSegment.get(ValueLayout.JAVA_BYTE, refBase + REF_OFF_DTYPE);
            final int ndim = Math.min(SHM_REF_MAX_DIMS, Byte.toUnsignedInt(
                ringSegment.get(ValueLayout.JAVA_BYTE, refBase + REF_OFF_NDIM)));

            final int[] shape = new int[ndim];
            for (int d = 0; d < ndim; d++) {
                shape[d] = ringSegment.get(ValueLayout.JAVA_INT,
                                           refBase + REF_OFF_SHAPE + (4L * d));
            }
            return new BridgeEvent(transport, cmd, flags, jobId, reqId,
                                   new byte[0], offset, bytes, dtype, shape);
        }

        final int length = Math.min(payloadSize, BRIDGE_INLINE_CAPACITY);
        final byte[] inline = new byte[length];
        if (length > 0) {
            MemorySegment.copy(ringSegment, ValueLayout.JAVA_BYTE, dataOffset + MSG_OFF_UNION,
                               inline, 0, length);
        }
        return new BridgeEvent(transport, cmd, flags, jobId, reqId,
                               inline, 0L, 0L, (byte) 0, new int[0]);
    }

    /**
     * Updates the Java process heartbeat timestamp in the shared memory header.
     */
    public void sendJavaHeartbeat() {
        if (this.shmBaseSegment != null && this.shmBaseSegment.scope().isAlive()) {
            RAW_LONG_HANDLE.setRelease(this.shmBaseSegment, HDR_HEARTBEAT_JAVA, System.currentTimeMillis());
        }
    }

    private static void ensureSharedRegionExists(Path binaryPath, Path region) {
        if (java.nio.file.Files.exists(region)) {
            try (RandomAccessFile probe = new RandomAccessFile(region.toFile(), "r")) {
                if (probe.length() >= MIN_REGION_BYTES) {
                    probe.seek(HDR_MAGIC);
                    // ShmHeader::magic is a little-endian uint32.
                    byte[] four = new byte[4];
                    probe.readFully(four);
                    int magic = (four[0] & 0xFF) | ((four[1] & 0xFF) << 8)
                              | ((four[2] & 0xFF) << 16) | ((four[3] & 0xFF) << 24);
                    if (magic == SHM_MAGIC) {
                        return; 
                    }
                }
            } catch (Exception ignored) {
                // Fall through and let the engine reformat it.
            }
        }

        if (!java.nio.file.Files.exists(binaryPath)) {
            AppLogger.warn("root-bridge binary not found at " + binaryPath
                + "; the shared region " + region + " cannot be created.");
            return;
        }

        try {
            Process init = new ProcessBuilder(
                    binaryPath.toAbsolutePath().toString(),
                    "--init-shm",
                    "--shm", region.toString())
                .redirectErrorStream(true)
                .start();
            if (!init.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                init.destroyForcibly();
                AppLogger.warn("root-bridge --init-shm did not finish within 10 s.");
                return;
            }
            if (init.exitValue() != 0) {
                AppLogger.warn("root-bridge --init-shm exited with " + init.exitValue() + ".");
            }
        } catch (Exception e) {
            AppLogger.error("Could not create the shared region: " + e.getMessage());
        }
    }

    public static final String SHM_FILE_NAME = "root_backend.shm";

    private static final long MIN_REGION_BYTES = 1408L + (2L * 131264L);

    // Resolves the file that backs the engine's shared region.
    private static Path resolveSharedRegionPath(Path requested) {
        if (requested != null && requested.toString().endsWith(".shm")) {
            return requested.toAbsolutePath().normalize();
        }
        return defaultRegionPath();
    }

    private static Path defaultRegionPath() {
        return Paths.get(System.getProperty("user.dir"), "rootbackend", SHM_FILE_NAME)
                    .toAbsolutePath().normalize();
    }

    private void verifyRegionHeader() {
        int magic = this.shmBaseSegment.get(ValueLayout.JAVA_INT, HDR_MAGIC);
        int version = this.shmBaseSegment.get(ValueLayout.JAVA_INT, HDR_VERSION);
        int proto = this.shmBaseSegment.get(ValueLayout.JAVA_INT, HDR_PROTO_VERSION);

        if (magic != SHM_MAGIC) {
            throw new IllegalStateException(String.format(
                "Shared region magic is 0x%08X, expected 0x%08X. "
                + "Either the engine has not initialized it yet, or this is not "
                + "a root-bridge region.", magic, SHM_MAGIC));
        }
        if (version != SHM_VERSION) {
            throw new IllegalStateException(
                "Shared region layout version " + version + ", this client speaks "
                + SHM_VERSION + ". Rebuild whichever side is behind.");
        }
        if (proto != PROTO_VERSION) {
            AppLogger.warn("Region wire version " + proto + " differs from this client's "
                + PROTO_VERSION + "; opcode meanings may have moved.");
        }
    }

    private MemorySegment slicePartition(long offsetField, long sizeField, String what) {
        long offset = this.shmBaseSegment.get(ValueLayout.JAVA_LONG, offsetField);
        long size = this.shmBaseSegment.get(ValueLayout.JAVA_LONG, sizeField);
        long total = this.shmBaseSegment.byteSize();

        if (offset <= 0 || size <= 0 || offset > total || size > total - offset) {
            throw new IllegalStateException(String.format(
                "Partition table entry for the %s is out of range: offset=%d size=%d "
                + "in a %d byte region.", what, offset, size, total));
        }
        return this.shmBaseSegment.asSlice(offset, size);
    }

    private static int capacityToPow2(long capacity) {
        if (capacity <= 0 || Long.bitCount(capacity) != 1) {
            throw new IllegalStateException(
                "Ring capacity " + capacity + " is not a power of two. The partition "
                + "table stores a slot COUNT; a value like 10 means the exponent "
                + "was written there by mistake.");
        }
        return Long.numberOfTrailingZeros(capacity);
    }

    public boolean sendCommand(short opcode, int jobId, int reqId, byte[] payload) {
        if (!isAvailable || commandRingSegment == null) {
            return false;
        }
        // Beyond the inline slot the payload travels through the shared heap.
        if (payload != null && payload.length > BRIDGE_INLINE_CAPACITY) {
            long offset = stageOnHeap(payload);
            if (offset == 0L) {
                return false;
            }
            return pushCommandRef(commandRingSegment, cmdRingCapacityPow2,
                                  opcode, jobId, reqId, offset, payload.length);
        }
        return pushCommandMessage(commandRingSegment, cmdRingCapacityPow2,
                                  opcode, jobId, reqId, payload);
    }

    public boolean sendCommand(short opcode, int jobId, int reqId, String payload) {
        return sendCommand(opcode, jobId, reqId,
                           payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    private static final long ENGINE_READY_TIMEOUT_MS = 20_000L;

    /** Blocks until the engine's scheduler loop is running, or the timeout expires. */
    private boolean waitForEngineHeartbeat(long timeoutMillis) {
        if (shmBaseSegment == null) {
            return false;
        }
        final long first = (long) RAW_LONG_HANDLE.getVolatile(shmBaseSegment, HDR_HEARTBEAT_CPP);
        final long deadline = System.nanoTime() + (timeoutMillis * 1_000_000L);
        while (System.nanoTime() < deadline) {
            if ((long) RAW_LONG_HANDLE.getVolatile(shmBaseSegment, HDR_HEARTBEAT_CPP) != first) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        AppLogger.error("The engine did not start draining the ring within "
            + (timeoutMillis / 1000L) + " s. See rootbackend/rootbackend_error.log.");
        return false;
    }

    private int drainStaleEvents() {
        // A running pump owns the ring; draining here would steal live replies.
        if (eventRingSegment == null || pumping) {
            return 0;
        }
        int discarded = 0;
        final int limit = 1 << evtRingCapacityPow2;
        while (discarded < limit
               && pollEventMessage(eventRingSegment, evtRingCapacityPow2) != null) {
            discarded++;
        }
        if (discarded > 0) {
        }
        return discarded;
    }

    private void startEventPump() {
        if (eventRingSegment == null) {
            return;
        }
        synchronized (PUMP_LOCK) {
            if (pumping) {
                return; // another instance already drains this ring
            }
            pumpOwner = this;
            pumping = true;
            eventPump = new Thread(RootBackend::pumpLoop, "root-shm-event-pump");
            eventPump.setDaemon(true);
            eventPump.start();
        }
    }

    private void stopEventPump() {
        Thread pump;
        synchronized (PUMP_LOCK) {
            if (pumpOwner != this) {
                return; // not this instance's pump to stop
            }
            pumping = false;
            pump = eventPump;
            eventPump = null;
            pumpOwner = null;
        }
        if (pump != null) {
            pump.interrupt();
            try {
                pump.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void pumpLoop() {
        int idle = 0;
        while (pumping) {
            final RootBackend owner = pumpOwner;
            if (owner == null || owner.eventRingSegment == null) {
                return;
            }
            BridgeEvent event = pollEventMessage(owner.eventRingSegment,
                                                 owner.evtRingCapacityPow2);
            if (event == null) {
                idle++;
                if (idle < 1_000) {
                    Thread.onSpinWait();
                } else if (idle < 10_000) {
                    Thread.yield();
                } else {
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            idle = 0;
            route(owner, event);
        }
    }

    private static void route(RootBackend owner, BridgeEvent event) {
        java.util.concurrent.ArrayBlockingQueue<BridgeEvent> waiter =
            pendingReplies.remove(event.reqId());
        if (waiter != null) {
            waiter.offer(event); // capacity 1, and the waiter is the only reader
            return;
        }

        final RootShmCanvasRenderer canvasRenderer = owner.canvasRenderer;
        final MemorySegment shmBaseSegment = owner.shmBaseSegment;
        if (canvasRenderer != null && event.isShmRef() && shmBaseSegment != null
            && event.shmOffset() > 0
            && event.shmOffset() + event.shmBytes() <= shmBaseSegment.byteSize()
            && event.shmBytes() == canvasRenderer.expectedByteCount()) {
            try {
                canvasRenderer.updatePixelsFromShm(
                    shmBaseSegment.asSlice(event.shmOffset(), event.shmBytes()));
            } catch (Exception ex) {
                AppLogger.warn("Canvas renderer refused a payload: " + ex.getMessage());
            }
            return;
        }

        if (!unmatchedEvents.offer(event)) {
            unmatchedEvents.poll();
            unmatchedEvents.offer(event);
        }
    }

    private BridgeEvent sendAndAwait(short opcode, int jobId, int reqId,
                                     byte[] payload, long timeoutMillis) {
        java.util.concurrent.ArrayBlockingQueue<BridgeEvent> slot =
            new java.util.concurrent.ArrayBlockingQueue<>(1);
        pendingReplies.put(reqId, slot);
        try {
            if (!sendCommand(opcode, jobId, reqId, payload)) {
                return null;
            }
            return slot.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pendingReplies.remove(reqId);
        }
    }

    public BridgeEvent pollEvent() {
        if (!isAvailable) {
            return null;
        }
        return unmatchedEvents.poll();
    }

    public byte[] readReferencedBytes(long regionOffset, long length) {
        if (shmBaseSegment == null || regionOffset <= 0 || length <= 0
            || regionOffset > shmBaseSegment.byteSize()
            || length > shmBaseSegment.byteSize() - regionOffset) {
            return new byte[0];
        }
        byte[] out = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
        MemorySegment.copy(shmBaseSegment, ValueLayout.JAVA_BYTE, regionOffset, out, 0, out.length);
        return out;
    }

    public MemorySegment commandRingSegment() { return commandRingSegment; }
    public MemorySegment eventRingSegment()   { return eventRingSegment; }
    public MemorySegment shmBaseSegment()     { return shmBaseSegment; }
    public int cmdRingCapacityPow2()          { return cmdRingCapacityPow2; }
    public int evtRingCapacityPow2()          { return evtRingCapacityPow2; }

    // --- METHODS REQUIRED BY SPHERE & CORE ---

    public void initialize() {
        if (!isAvailable) {
            AppLogger.warn("RootBackend skipped initialization (ROOT not installed or ROOT_DIR not set).");
            return;
        }
        //AppLogger.info("RootBackend initialized.");
    }

    // Runs one line through the engine's ROOT interpreter.
    public boolean executeCling(String command) {
        if (!isAvailable) {
            AppLogger.warn("Cannot execute an interpreter command: the ROOT backend is disabled.");
            return false;
        }
        if (command == null || command.isBlank()) {
            return false;
        }

        byte[] payload = command.getBytes(StandardCharsets.UTF_8);        return sendCommand(CMD_CLING_EXEC, 0, nextReqId.incrementAndGet(), payload);
    }

    // Runs an interpreter command and waits for its result.
    // --- Shared-heap staging: payloads larger than the 44-byte inline slot ---
    // Mirrors shm_heap_alloc()/shm_chunk_commit() in src/shm_layout.cpp. The
    // offsets come from a probe compiled against inc/shm_layout.h.

    private static final int  CHUNK_MAGIC        = 0x43484E4B;
    private static final short CHUNK_INCOMPLETE  = 0;
    private static final short CHUNK_COMMITTED   = 1;
    private static final short CHUNK_KIND_DATA   = 1;

    private static final long HEAP_ALLOCATED_OFF       = 0L;
    private static final long HEAP_CAPACITY_OFF        = 8L;
    private static final long HEAP_EPOCH_OFF           = 16L;
    private static final long HEAP_ACTIVE_OFF          = 24L;
    private static final long HEAP_ALLOC_BY_KIND_OFF   = 64L;
    private static final long HEAP_ALLOC_BY_PROD_OFF   = 192L;
    private static final long HEAP_QUOTA_BY_KIND_OFF   = 320L;
    private static final long HEAP_QUOTA_BY_PROD_OFF   = 448L;

    private static final long ROOT_OFF                 = 1088L;
    private static final long ROOT_N_CHUNKS_OFF        = 4L;
    private static final long ROOT_FIRST_CHUNK_OFF     = 8L;
    private static final long ROOT_LAST_CHUNK_OFF      = 16L;
    private static final long ROOT_FIRST_BY_KIND_OFF   = 24L;
    private static final long ROOT_LAST_BY_KIND_OFF    = 152L;
    private static final long ROOT_FIRST_BY_PROD_OFF   = 280L;
    private static final long ROOT_LAST_BY_PROD_OFF    = 408L;
    private static final long ROOT_PREFETCH_HEAD_OFF   = 536L;
    private static final long ROOT_LAST_LVL2_OFF       = 544L;
    private static final long ROOT_LAST_LVL4_OFF       = 552L;
    private static final long ROOT_LAST_LVL8_OFF       = 560L;
    private static final long ROOT_PF_HEAD_BY_KIND_OFF = 568L;
    private static final long ROOT_PF_HEAD_BY_PROD_OFF = 696L;

    private static final long CHUNK_HEADER_SIZE  = 128L;
    private static final long CHUNK_MAGIC_OFF    = 0L;
    private static final long CHUNK_SIZE_OFF     = 4L;
    private static final long CHUNK_EPOCH_OFF    = 8L;
    private static final long CHUNK_KIND_OFF     = 16L;
    private static final long CHUNK_PRODUCER_OFF = 18L;
    private static final long CHUNK_ENCODING_OFF = 20L;
    private static final long CHUNK_FLAGS_OFF    = 22L;
    private static final long CHUNK_CHECKSUM_OFF = 24L;
    private static final long CHUNK_NEXT_OFF      = 32L;
    private static final long CHUNK_NEXT_KIND_OFF = 40L;
    private static final long CHUNK_NEXT_PROD_OFF = 48L;
    private static final long CHUNK_NEXT_LVL2_OFF = 56L;
    private static final long CHUNK_NEXT_LVL4_OFF = 64L;
    private static final long CHUNK_NEXT_LVL8_OFF = 72L;

    private static final long HEAP_METADATA_SIZE = 2688L;
    private static final long CACHE_LINE         = 64L;
    private static final int  KIND_BUCKETS       = 16;
    private static final int  PRODUCER_BUCKETS   = 16;

    private static long alignUp(long v, long a) {
        return (v + a - 1L) & ~(a - 1L);
    }

    /** One bucket of the quota table. False when the quota is already reached. */
    private boolean chargeBucket(long quotaSlot, long countSlot) {
        final long quota = (long) RAW_LONG_HANDLE.getVolatile(shmBaseSegment, quotaSlot);
        long current = (long) RAW_LONG_HANDLE.getVolatile(shmBaseSegment, countSlot);
        for (;;) {
            if (quota > 0L && current >= quota) {
                return false;
            }
            long seen = (long) RAW_LONG_HANDLE.compareAndExchange(
                shmBaseSegment, countSlot, current, current + 1L);
            if (seen == current) {
                return true;
            }
            current = seen;
        }
    }

    private void unchargeBucket(long countSlot) {
        RAW_LONG_HANDLE.getAndAdd(shmBaseSegment, countSlot, -1L);
    }

    /** Appends the chunk to one of the heap's intrusive lists. */
    private void spliceList(long lastSlot, long firstSlot, long nextFieldOffset, long chunk) {
        final long previous = (long) RAW_LONG_HANDLE.getAndSet(shmBaseSegment, lastSlot, chunk);
        if (previous == 0L) {
            RAW_LONG_HANDLE.setRelease(shmBaseSegment, firstSlot, chunk);
        } else {
            RAW_LONG_HANDLE.setRelease(shmBaseSegment, previous + nextFieldOffset, chunk);
        }
    }

    private void spliceLevel(long lastSlot, long nextFieldOffset, long chunk) {
        final long previous = (long) RAW_LONG_HANDLE.getAndSet(shmBaseSegment, lastSlot, chunk);
        if (previous != 0L) {
            RAW_LONG_HANDLE.setRelease(shmBaseSegment, previous + nextFieldOffset, chunk);
        }
    }

    /**
     * Mirror of shm_heap_alloc(): quotas, bump allocation, chunk header, and the
     * traversal lists the engine's compaction pass walks. Returns the payload
     * offset, or 0. The chunk is left INCOMPLETE for the caller to commit.
     */
    private long heapAlloc(int size, short kind, short producerId, short encoding) {
        final long heap = shmBaseSegment.get(ValueLayout.JAVA_LONG, HDR_OFF_DATA_HEAP);
        if (heap <= 0L || size <= 0) {
            return 0L;
        }
        final long root = heap + ROOT_OFF;
        final long need = alignUp(CHUNK_HEADER_SIZE + size, CACHE_LINE);

        final boolean hasKind = kind >= 0 && kind < KIND_BUCKETS;
        final boolean hasProducer = producerId >= 0 && producerId < PRODUCER_BUCKETS;
        final long kindCount = heap + HEAP_ALLOC_BY_KIND_OFF + kind * 8L;
        final long prodCount = heap + HEAP_ALLOC_BY_PROD_OFF + producerId * 8L;

        if (hasKind && !chargeBucket(heap + HEAP_QUOTA_BY_KIND_OFF + kind * 8L, kindCount)) {
            AppLogger.error("Shared heap: kind " + kind + " has reached its allocation quota.");
            return 0L;
        }
        if (hasProducer
            && !chargeBucket(heap + HEAP_QUOTA_BY_PROD_OFF + producerId * 8L, prodCount)) {
            if (hasKind) {
                unchargeBucket(kindCount);
            }
            AppLogger.error("Shared heap: producer " + producerId
                + " has reached its allocation quota.");
            return 0L;
        }

        final long capacity = shmBaseSegment.get(ValueLayout.JAVA_LONG, heap + HEAP_CAPACITY_OFF);
        long allocated = (long) RAW_LONG_HANDLE.getVolatile(
            shmBaseSegment, heap + HEAP_ALLOCATED_OFF);
        for (;;) {
            if (allocated + need > capacity) {
                if (hasKind) {
                    unchargeBucket(kindCount);
                }
                if (hasProducer) {
                    unchargeBucket(prodCount);
                }
                AppLogger.error("Shared heap is full: " + size
                    + " bytes requested, " + (capacity - allocated) + " left.");
                return 0L;
            }
            long seen = (long) RAW_LONG_HANDLE.compareAndExchange(
                shmBaseSegment, heap + HEAP_ALLOCATED_OFF, allocated, allocated + need);
            if (seen == allocated) {
                break;
            }
            allocated = seen;
        }

        final long chunk = heap + HEAP_METADATA_SIZE + allocated;
        if (chunk + CHUNK_HEADER_SIZE + size > shmBaseSegment.byteSize()) {
            return 0L;
        }

        shmBaseSegment.asSlice(chunk, CHUNK_HEADER_SIZE).fill((byte) 0);
        shmBaseSegment.set(ValueLayout.JAVA_INT, chunk + CHUNK_MAGIC_OFF, CHUNK_MAGIC);
        shmBaseSegment.set(ValueLayout.JAVA_INT, chunk + CHUNK_SIZE_OFF, size);
        shmBaseSegment.set(ValueLayout.JAVA_LONG, chunk + CHUNK_EPOCH_OFF,
            shmBaseSegment.get(ValueLayout.JAVA_LONG, heap + HEAP_EPOCH_OFF));
        shmBaseSegment.set(ValueLayout.JAVA_SHORT, chunk + CHUNK_KIND_OFF, kind);
        shmBaseSegment.set(ValueLayout.JAVA_SHORT, chunk + CHUNK_PRODUCER_OFF, producerId);
        shmBaseSegment.set(ValueLayout.JAVA_SHORT, chunk + CHUNK_ENCODING_OFF, encoding);
        shmBaseSegment.set(ValueLayout.JAVA_SHORT, chunk + CHUNK_FLAGS_OFF, CHUNK_INCOMPLETE);
        VarHandle.releaseFence();

        spliceList(root + ROOT_LAST_CHUNK_OFF, root + ROOT_FIRST_CHUNK_OFF,
                   CHUNK_NEXT_OFF, chunk);
        if (hasKind) {
            spliceList(root + ROOT_LAST_BY_KIND_OFF + kind * 8L,
                       root + ROOT_FIRST_BY_KIND_OFF + kind * 8L,
                       CHUNK_NEXT_KIND_OFF, chunk);
            RAW_LONG_HANDLE.setRelease(
                shmBaseSegment, root + ROOT_PF_HEAD_BY_KIND_OFF + kind * 8L, chunk);
        }
        if (hasProducer) {
            spliceList(root + ROOT_LAST_BY_PROD_OFF + producerId * 8L,
                       root + ROOT_FIRST_BY_PROD_OFF + producerId * 8L,
                       CHUNK_NEXT_PROD_OFF, chunk);
            RAW_LONG_HANDLE.setRelease(
                shmBaseSegment, root + ROOT_PF_HEAD_BY_PROD_OFF + producerId * 8L, chunk);
        }

        final int seq = (int) RAW_INT_HANDLE.getAndAdd(
            shmBaseSegment, root + ROOT_N_CHUNKS_OFF, 1) + 1;
        if ((seq & 1) == 0) {
            spliceLevel(root + ROOT_LAST_LVL2_OFF, CHUNK_NEXT_LVL2_OFF, chunk);
        }
        if ((seq & 3) == 0) {
            spliceLevel(root + ROOT_LAST_LVL4_OFF, CHUNK_NEXT_LVL4_OFF, chunk);
        }
        if ((seq & 7) == 0) {
            spliceLevel(root + ROOT_LAST_LVL8_OFF, CHUNK_NEXT_LVL8_OFF, chunk);
        }

        if (kind == CHUNK_KIND_DATA) {
            RAW_LONG_HANDLE.setRelease(shmBaseSegment, root + ROOT_PREFETCH_HEAD_OFF, chunk);
        }
        RAW_LONG_HANDLE.getAndAdd(shmBaseSegment, heap + HEAP_ACTIVE_OFF, 1L);

        return chunk + CHUNK_HEADER_SIZE;
    }

    /**
     * Copies `payload` into the shared heap and returns its offset, or 0.
     * The chunk is committed, so the engine sees it as readable.
     */
    private long stageOnHeap(byte[] payload) {
        if (shmBaseSegment == null || payload == null || payload.length == 0) {
            return 0L;
        }
        final long data = heapAlloc(payload.length, CHUNK_KIND_DATA, (short) 0, (short) 0);
        if (data == 0L) {
            return 0L;
        }

        MemorySegment.copy(MemorySegment.ofArray(payload), 0L, shmBaseSegment, data, payload.length);

        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(payload, 0, payload.length);
        final long chunk = data - CHUNK_HEADER_SIZE;
        shmBaseSegment.set(ValueLayout.JAVA_INT, chunk + CHUNK_CHECKSUM_OFF, (int) crc.getValue());

        VarHandle.releaseFence();
        shmBaseSegment.set(ValueLayout.JAVA_SHORT, chunk + CHUNK_FLAGS_OFF, CHUNK_COMMITTED);
        return data;
    }

    // Sends any opcode and returns the reply text, or null on timeout.
    public String sendAwait(short opcode, int jobId, byte[] payload, long timeoutMillis) {
        if (!isAvailable) {
            return null;
        }
        BridgeEvent reply = sendAndAwait(opcode, jobId, nextReqId.incrementAndGet(),
                                         payload, timeoutMillis);
        if (reply == null) {
            return null;
        }
        if (reply.isShmRef()) {
            byte[] bytes = readReferencedBytes(reply.shmOffset(), reply.shmBytes());
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        }
        return reply.message();
    }

    public String executeClingAwait(String command, long timeoutMillis) {
        if (!isAvailable || command == null || command.isBlank()) {
            return null;
        }
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);

        final int reqId = nextReqId.incrementAndGet();
        BridgeEvent reply =
            sendAndAwait(CMD_CLING_EXEC, 0, reqId, payload, timeoutMillis);
        if (reply == null) {
            return null;
        }

        if (reply.isShmRef()) {
            byte[] bytes = readReferencedBytes(reply.shmOffset(), reply.shmBytes());
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        }
        return reply.message();
    }

    public int openFile(String path, String mode, String handleId) {
        if (!isAvailable) {
            AppLogger.warn("Cannot open ROOT file: ROOT backend is disabled.");
            return -1;
        }
        if (path == null || path.isBlank()) {
            return -1;
        }
        byte[] payload = path.getBytes(StandardCharsets.UTF_8);
        int jobId = nextJobId.incrementAndGet();
        int reqId = nextReqId.incrementAndGet();
        if (!sendCommand(CMD_OPEN_FILE, jobId, reqId, payload)) {
            AppLogger.error("Command ring is full; open refused for " + path);
            return -1;
        }
        return jobId;
    }

    public int closeFile(int jobId) {
        if (!isAvailable) {
            return -1;
        }
        int reqId = nextReqId.incrementAndGet();
        return sendCommand(CMD_CLOSE_FILE, jobId, reqId, (byte[]) null) ? reqId : -1;
    }

    // Liveness probe that waits for the reply.
    public boolean pingAwait(long timeoutMillis) {
        if (!isAvailable || commandRingSegment == null) {
            return false;
        }
        final int reqId = nextReqId.incrementAndGet();
        BridgeEvent reply = sendAndAwait(CMD_PING, 0, reqId, null, timeoutMillis);
        return reply != null && reply.cmd() == EVT_PONG;
    }

    public int ping() {
        if (!isAvailable) {
            return -1;
        }
        int reqId = nextReqId.incrementAndGet();
        return sendCommand(CMD_PING, 0, reqId, (byte[]) null) ? reqId : -1;
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
        if (!isAvailable || eventRingSegment == null) {
            invokeNativeErrorHandler(errorCode, message);
            return;
        }
        try {
            byte[] text = (message != null)
                ? message.getBytes(StandardCharsets.UTF_8) : new byte[0];

            int textLength = Math.min(text.length, BRIDGE_INLINE_CAPACITY - 2);
            byte[] payload = new byte[2 + textLength];
            payload[0] = (byte) (errorCode & 0xFF);
            payload[1] = (byte) ((errorCode >> 8) & 0xFF);
            System.arraycopy(text, 0, payload, 2, textLength);

            boolean pushed = pushCommandMessage(eventRingSegment, evtRingCapacityPow2,
                EVT_ERROR, (int) Thread.currentThread().threadId(),
                (int) System.nanoTime(), payload);

            if (!pushed) {
                invokeNativeErrorHandler(errorCode, message);
            }
        } catch (Exception e) {
            AppLogger.error("Failed SHM error write, falling back to the native handler.", e);
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
        long traceId   = 0L; // not carried in shared memory; see above
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
        outTscTrace[2] = 0L; // trace id is not carried in shared memory

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
        // Slot reserved successfully. Populate the BridgeMessage structure (64 Bytes)
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

        shmBaseSegment.set(ValueLayout.JAVA_INT, HDR_CLUSTER_RUNTIME_IDS + (4L * id), 1);
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

        shmBaseSegment.set(ValueLayout.JAVA_INT, HDR_CLUSTER_RUNTIME_IDS + (4L * id), 0);
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
        int status = shmBaseSegment.get(ValueLayout.JAVA_INT, HDR_CLUSTER_RUNTIME_IDS + (4L * id));
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
        TSC_LAST_SAMPLE_HANDLE.setRelease(ringHeaderSegment, tscTimestamp);
    }

    public boolean isHeartbeatStale(MemorySegment ringHeaderSegment, long currentTsc, long staleThresholdCycles) {
        if (!isAvailable || ringHeaderSegment == null || MemorySegment.NULL.equals(ringHeaderSegment)) {
            return true;
        }
        long last = (long) TSC_LAST_SAMPLE_HANDLE.getAcquire(ringHeaderSegment);
        long delta = currentTsc - last;
        return last == 0L || delta > staleThresholdCycles || delta < 0L;
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
        //AppLogger.info("RootBackend activated.");
    }

    @Override
    public void close() {
        synchronized (RootBackend.class) {
            if (INSTANCE == this) {
                INSTANCE = null;
            }
        }
        stopEventPump();
        synchronized (PUMP_LOCK) {
            if (processBridgeOwner == this && processBridge != null) {
                processBridge.close();
                sharedProcessBridge = null;
                processBridgeOwner = null;
                AppLogger.info("RootBackend process bridge daemon shut down.");
            }
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

    // One latency span.
    public record SpanRecord(long tscStart, long tscEnd, long traceId, int threadId, int jobId, int reqId, short moduleId, byte level) {}

    public record WorkerMetrics(long jobs, long busyCycles) {}
}