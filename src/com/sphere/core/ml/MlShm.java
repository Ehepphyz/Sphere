package com.sphere.core.ml;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tensor arena shared by Sphere and its learning engine: a file both map -- Java with the Foreign Function and
 * Memory API, Python with mmap -- in which Java writes jets and reads back what PyTorch, TensorFlow or JAX made of
 * them, no byte copied through a pipe, a file or JSON. The pipe carries only what to do with which slots
 * (sphere_ml's shm_* commands): the data plane is the mapping, the control plane the engine's JSON lines, and the
 * pipe's system calls order the two, so the region needs no lock.
 *
 * <p>The region describes itself, as sphere_mlshm.py reads it: a header (magic "SPMLSHM1", version, size, slots),
 * a table of 64 slots (name, dtype, shape, offset, bytes), then the tensors, each 64-byte aligned -- what DLPack
 * asks of a CPU tensor for PyTorch, JAX and TensorFlow to take it without a copy. Slots are bump-allocated and
 * freed all at once ({@link #reset}): a request's inputs and outputs, then the next request's.
 */
public final class MlShm implements AutoCloseable {

    static final long MAGIC = 0x314D48534C4D5053L;   // "SPMLSHM1", little-endian
    static final int VERSION = 1, SLOTS = 64, SLOT_BYTES = 128, NAME_BYTES = 32, MAX_RANK = 8, ALIGN = 64;
    static final long OFF_MAGIC = 0, OFF_VERSION = 8, OFF_SLOTS = 12, OFF_SIZE = 16, OFF_USED = 24, OFF_GEN = 32,
        OFF_TABLE = 256, DATA = 16384;
    // a slot: name[32], dtype int, rank int, shape long[8], offset long, bytes long, live int
    static final long S_NAME = 0, S_DTYPE = 32, S_RANK = 36, S_SHAPE = 40, S_OFFSET = 104, S_BYTES = 112, S_LIVE = 120;

    /** The element types a slot holds, by the codes sphere_mlshm.py shares. */
    public enum DType {
        FLOAT64(0, 8), FLOAT32(1, 4), INT64(2, 8), INT32(3, 4), UINT8(4, 1);

        public final int code, size;

        DType(int code, int size) {
            this.code = code;
            this.size = size;
        }

        static DType of(String name) {
            return switch (name) {
                case "float64", "f8" -> FLOAT64;
                case "float32", "f4" -> FLOAT32;
                case "int64", "i8" -> INT64;
                case "int32", "i4" -> INT32;
                case "uint8", "u1" -> UINT8;
                default -> throw new IllegalArgumentException("no dtype " + name);
            };
        }

        public String numpy() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One tensor in the arena. */
    public record Slot(int index, String name, DType dtype, long[] shape, long offset, long bytes) {
        public long count() {
            long n = 1;
            for (long s : shape) {
                n *= s;
            }
            return n;
        }

        /** What a shm_* command names the slot by. */
        public Map<String, Object> describe() {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", index);
            m.put("name", name);
            m.put("dtype", dtype.numpy());
            final List<Object> s = new ArrayList<>();
            for (long v : shape) {
                s.add(v);
            }
            m.put("shape", s);
            m.put("offset", offset);
            return m;
        }
    }

    private static final AtomicInteger COUNT = new AtomicInteger();

    private final Path file;
    private final Arena arena;
    private final MemorySegment region;
    private final long size;
    private long used = DATA;
    private int slots;

    private MlShm(Path file, Arena arena, MemorySegment region, long size) {
        this.file = file;
        this.arena = arena;
        this.region = region;
        this.size = size;
    }

    /** A new arena of size bytes (rounded up to 64 KiB) in folder, its header written. */
    public static MlShm create(Path folder, long size) throws IOException {
        Files.createDirectories(folder);
        final long bytes = Math.max(DATA + 65536, (size + 65535) / 65536 * 65536);
        final Path f = folder.resolve("arena-" + ProcessHandle.current().pid() + "-" + COUNT.incrementAndGet() + ".shm");
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(bytes);
        }
        final Arena arena = Arena.ofShared();
        try (FileChannel fc = FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final MemorySegment seg = fc.map(FileChannel.MapMode.READ_WRITE, 0, bytes, arena);
            final MlShm shm = new MlShm(f, arena, seg, bytes);
            seg.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_VERSION, VERSION);
            seg.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_SLOTS, SLOTS);
            seg.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_SIZE, bytes);
            shm.reset();
            seg.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_MAGIC, MAGIC);   // last: a reader seeing the magic sees the rest
            return shm;
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    public Path file() {
        return file;
    }

    public long size() {
        return size;
    }

    /** The bytes the slots hold now. */
    public long used() {
        return used - DATA;
    }

    /** Every slot freed; the generation counted, for a reader to know its views are stale. */
    public synchronized void reset() {
        for (int i = 0; i < SLOTS; i++) {
            region.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_TABLE + (long) i * SLOT_BYTES + S_LIVE, 0);
        }
        slots = 0;
        used = DATA;
        region.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_USED, used);
        region.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_GEN, region.get(ValueLayout.JAVA_LONG_UNALIGNED, OFF_GEN) + 1);
    }

    /** A slot of this dtype and shape, its bytes zero, 64-byte aligned. */
    public synchronized Slot allocate(String name, DType dtype, long... shape) {
        if (shape.length > MAX_RANK) {
            throw new IllegalArgumentException("a tensor of rank " + shape.length + " (8 at most)");
        }
        if (slots >= SLOTS) {
            throw new IllegalStateException("the arena's " + SLOTS + " slots are taken: reset() it");
        }
        final byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length >= NAME_BYTES) {
            throw new IllegalArgumentException("a slot name of " + nb.length + " bytes (31 at most)");
        }
        long count = 1;
        for (long s : shape) {
            count *= s;
        }
        final long bytes = count * dtype.size, at = (used + ALIGN - 1) / ALIGN * ALIGN;
        if (at + bytes > size) {
            throw new IllegalStateException(String.format("%s needs %.1f MB; the arena has %.1f MB left of %.1f", name, bytes / 1048576.0,
                (size - at) / 1048576.0, size / 1048576.0));
        }
        final int index = slots++;
        final long e = OFF_TABLE + (long) index * SLOT_BYTES;
        region.asSlice(e, SLOT_BYTES).fill((byte) 0);
        MemorySegment.copy(nb, 0, region, ValueLayout.JAVA_BYTE, e + S_NAME, nb.length);
        region.set(ValueLayout.JAVA_INT_UNALIGNED, e + S_DTYPE, dtype.code);
        region.set(ValueLayout.JAVA_INT_UNALIGNED, e + S_RANK, shape.length);
        for (int d = 0; d < shape.length; d++) {
            region.set(ValueLayout.JAVA_LONG_UNALIGNED, e + S_SHAPE + 8L * d, shape[d]);
        }
        region.set(ValueLayout.JAVA_LONG_UNALIGNED, e + S_OFFSET, at);
        region.set(ValueLayout.JAVA_LONG_UNALIGNED, e + S_BYTES, bytes);
        region.asSlice(at, bytes).fill((byte) 0);
        region.set(ValueLayout.JAVA_INT_UNALIGNED, e + S_LIVE, 1);
        used = at + bytes;
        region.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_USED, used);
        return new Slot(index, name, dtype, shape.clone(), at, bytes);
    }

    /** A slot holding these doubles, written as dtype (float32 rounds each as Java's (float) cast does). */
    public Slot put(String name, double[] values, DType dtype, long... shape) {
        final Slot s = allocate(name, dtype, shape);
        if (s.count() != values.length) {
            throw new IllegalArgumentException(name + ": " + values.length + " values for a shape of " + s.count());
        }
        write(s, values, 0);
        return s;
    }

    /** values written into the slot from element at on (doubles converted to the slot's dtype as they go). */
    public void write(Slot s, double[] values, long at) {
        final MemorySegment seg = segment(s);
        switch (s.dtype()) {
            case FLOAT64 -> MemorySegment.copy(values, 0, seg, ValueLayout.JAVA_DOUBLE_UNALIGNED, at * 8, values.length);
            case FLOAT32 -> {
                for (int i = 0; i < values.length; i++) {
                    seg.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, at + i, (float) values[i]);
                }
            }
            case INT64 -> {
                for (int i = 0; i < values.length; i++) {
                    seg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, at + i, (long) values[i]);
                }
            }
            case INT32 -> {
                for (int i = 0; i < values.length; i++) {
                    seg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, at + i, (int) values[i]);
                }
            }
            case UINT8 -> {
                for (int i = 0; i < values.length; i++) {
                    seg.setAtIndex(ValueLayout.JAVA_BYTE, at + i, (byte) values[i]);
                }
            }
        }
    }

    /** The slot's values as doubles (a copy: Java computes on its heap). */
    public double[] read(Slot s) {
        final MemorySegment seg = segment(s);
        final int n = Math.toIntExact(s.count());
        final double[] out = new double[n];
        switch (s.dtype()) {
            case FLOAT64 -> MemorySegment.copy(seg, ValueLayout.JAVA_DOUBLE_UNALIGNED, 0, out, 0, n);
            case FLOAT32 -> {
                for (int i = 0; i < n; i++) {
                    out[i] = seg.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i);
                }
            }
            case INT64 -> {
                for (int i = 0; i < n; i++) {
                    out[i] = seg.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i);
                }
            }
            case INT32 -> {
                for (int i = 0; i < n; i++) {
                    out[i] = seg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                }
            }
            case UINT8 -> {
                for (int i = 0; i < n; i++) {
                    out[i] = Byte.toUnsignedInt(seg.getAtIndex(ValueLayout.JAVA_BYTE, i));
                }
            }
        }
        return out;
    }

    /** The slot's bytes themselves, for code that writes in place (a FastJet loop filling a batch of jets). */
    public MemorySegment segment(Slot s) {
        return region.asSlice(s.offset(), s.bytes());
    }

    /** What the engine is told to attach: the file, its size, its generation. */
    public Map<String, Object> describe() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", file.toAbsolutePath().toString());
        m.put("size", size);
        m.put("generation", region.get(ValueLayout.JAVA_LONG_UNALIGNED, OFF_GEN));
        return m;
    }

    @Override
    public void close() {
        try {
            arena.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the engine still maps it (Windows refuses then): the next session's arena is another file
        }
    }

    @Override
    public String toString() {
        return String.format("arena %s: %.1f MB, %d slot%s, %.2f MB used", file.getFileName(), size / 1048576.0, slots,
            slots == 1 ? "" : "s", used() / 1048576.0);
    }
}
