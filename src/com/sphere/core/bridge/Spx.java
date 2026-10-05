package com.sphere.core.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPX, the Sphere Physics eXchange: one file every engine reads natively.
 *
 * Java, Julia, Fortran, C++, ROOT and Python each hold their numbers in a heap
 * of their own, and until now the only way from one to another was text: a CSV
 * written by one, parsed by the next, with the digits lost in between and the
 * parsing costing more than the physics. SPX is binary and laid out so that it
 * need not be parsed at all. Julia wraps it with Mmap, C++ maps it, Python hands
 * it to numpy, Fortran reads it with stream access in one statement per array,
 * and all of them see the bits Java wrote, not a decimal rendering of them.
 *
 * The layout, little-endian throughout:
 *
 * <pre>
 *   header, 64 bytes
 *     0  char[8]   "SPHRSPX1"
 *     8  int32     version (1)
 *    12  int32     kind: 1 PDF set, 2 events, 3 table
 *    16  int32     number of sections
 *    20  int32     0
 *    24  int64     offset of the directory (64)
 *    32  int64     size of the file
 *    40  char[24]  title, space padded
 *   directory, 64 bytes per section
 *     0  char[24]  name, space padded
 *    24  int32     type: 1 float64, 2 int64, 3 text
 *    28  int32     0
 *    32  int64     offset of the data
 *    40  int64     number of elements (bytes for text)
 *    48  16 bytes  0
 *   data, each section starting on a 64-byte boundary
 * </pre>
 *
 * Three types only, on purpose: every reader is a few dozen lines, and the
 * Fortran one could not be shorter. Names are padded with spaces rather than
 * zeros because that is what a Fortran character variable holds.
 */
public final class Spx {

    public static final String MAGIC = "SPHRSPX1";
    public static final int VERSION = com.sphere.Sphere.SPX_VERSION;
    public static final int HEADER = 64;
    public static final int ENTRY = 64;
    public static final int ALIGN = 64;
    public static final int NAME = 24;

    public static final int F64 = 1;
    public static final int I64 = 2;
    public static final int TEXT = 3;

    /** What a file holds, which decides the sections a reader looks for. */
    public enum Kind {
        PDF(1), EVENTS(2), TABLE(3);

        public final int code;

        Kind(int code) {
            this.code = code;
        }

        static Kind of(int code) {
            for (Kind k : values()) {
                if (k.code == code) return k;
            }
            return null;
        }
    }

    /** One section as the directory describes it. */
    public record Section(String name, int type, long offset, long count) {
        public long bytes() {
            return type == TEXT ? count : count * 8L;
        }

        public String typeName() {
            return switch (type) {
                case F64 -> "float64";
                case I64 -> "int64";
                case TEXT -> "text";
                default -> "type " + type;
            };
        }
    }

    private Spx() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static long aligned(long at) {
        return (at + ALIGN - 1) / ALIGN * ALIGN;
    }

    /* ------------------------------------------------------------------ */
    /* Writing                                                             */
    /* ------------------------------------------------------------------ */

    /** Something that fills a float64 section without the whole array in memory. */
    @FunctionalInterface
    public interface DoubleSource {
        /** Writes the values in order, calling put once per value. */
        void emit(java.util.function.DoubleConsumer put);
    }

    /**
     * Collects sections and writes them in one go.
     *
     * A PDF set of a hundred members is tens of millions of numbers, which is
     * why a section may be given as a source rather than an array: the values
     * go from the grids to the file without a second copy of the set.
     */
    public static final class Writer {

        private record Pending(String name, int type, long count, Object data) { }

        private final Kind kind;
        private final String title;
        private final List<Pending> sections = new ArrayList<>();

        public Writer(Kind kind, String title) {
            this.kind = kind;
            this.title = title == null ? "" : title;
        }

        private void add(String name, int type, long count, Object data) {
            if (name.length() > NAME) {
                throw new IllegalArgumentException("Section name longer than " + NAME + ": " + name);
            }
            for (Pending p : sections) {
                if (p.name.equals(name)) throw new IllegalArgumentException("Section written twice: " + name);
            }
            sections.add(new Pending(name, type, count, data));
        }

        public Writer f64(String name, double[] values) {
            add(name, F64, values.length, values);
            return this;
        }

        public Writer f64(String name, long count, DoubleSource source) {
            add(name, F64, count, source);
            return this;
        }

        public Writer i64(String name, long[] values) {
            add(name, I64, values.length, values);
            return this;
        }

        public Writer text(String name, String text) {
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            add(name, TEXT, bytes.length, bytes);
            return this;
        }

        /** Text as LHAPDF writes its info files: one "Key: value" per line. */
        public Writer meta(Map<String, String> entries) {
            final StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            return text("meta", out.toString());
        }

        /**
         * Writes the file, and answers where it went.
         *
         * The file is written beside its destination and moved over it, so a
         * reader never maps half a file. On Windows a file that some engine
         * still has mapped cannot be replaced; the new one then goes next to it
         * under a numbered name, and that name is what is answered.
         */
        public Path write(Path file) throws IOException {
            final Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            final Path temporary = file.resolveSibling(file.getFileName() + ".part");

            long at = aligned(HEADER + (long) ENTRY * sections.size());
            final long[] offsets = new long[sections.size()];
            for (int k = 0; k < sections.size(); k++) {
                final Pending p = sections.get(k);
                offsets[k] = at;
                at = aligned(at + (p.type == TEXT ? p.count : p.count * 8L));
            }
            final long size = at;

            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                final ByteBuffer head = ByteBuffer.allocate((int) aligned(HEADER + (long) ENTRY * sections.size()))
                    .order(ByteOrder.LITTLE_ENDIAN);
                head.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
                head.putInt(VERSION);
                head.putInt(kind.code);
                head.putInt(sections.size());
                head.putInt(0);
                head.putLong(HEADER);
                head.putLong(size);
                head.put(padded(title));
                for (int k = 0; k < sections.size(); k++) {
                    final Pending p = sections.get(k);
                    head.position(HEADER + k * ENTRY);
                    head.put(padded(p.name));
                    head.putInt(p.type);
                    head.putInt(0);
                    head.putLong(offsets[k]);
                    head.putLong(p.count);
                }
                head.clear();
                writeFully(out, head, 0);

                final ByteBuffer chunk = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
                for (int k = 0; k < sections.size(); k++) {
                    final Pending p = sections.get(k);
                    final long[] position = {offsets[k]};
                    chunk.clear();
                    final java.util.function.DoubleConsumer putDouble = v -> {
                        if (!chunk.hasRemaining()) flush(out, chunk, position);
                        chunk.putDouble(v);
                    };
                    long written;
                    switch (p.data) {
                        case double[] values -> {
                            for (double v : values) putDouble.accept(v);
                            written = values.length;
                        }
                        case long[] values -> {
                            for (long v : values) {
                                if (!chunk.hasRemaining()) flush(out, chunk, position);
                                chunk.putLong(v);
                            }
                            written = values.length;
                        }
                        case byte[] bytes -> {
                            for (byte b : bytes) {
                                if (!chunk.hasRemaining()) flush(out, chunk, position);
                                chunk.put(b);
                            }
                            written = bytes.length;
                        }
                        case DoubleSource source -> {
                            final long[] counted = {0};
                            source.emit(v -> {
                                putDouble.accept(v);
                                counted[0]++;
                            });
                            written = counted[0];
                        }
                        default -> throw new IllegalStateException("unknown section data");
                    }
                    if (written != p.count) {
                        throw new IOException("Section " + p.name + " declared " + p.count
                            + " values and produced " + written + ".");
                    }
                    flush(out, chunk, position);
                }
                // The last section is padded too, so the size in the header is
                // the size on disk.
                if (out.size() < size) writeFully(out, ByteBuffer.allocate((int) (size - out.size())), out.size());
            } catch (java.io.UncheckedIOException failed) {
                Files.deleteIfExists(temporary);
                throw failed.getCause();
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(temporary);
                throw failed;
            }
            return moveIntoPlace(temporary, file);
        }

        private static void flush(FileChannel out, ByteBuffer chunk, long[] position) {
            chunk.flip();
            try {
                position[0] += writeFully(out, chunk, position[0]);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            chunk.clear();
        }
    }

    private static long writeFully(FileChannel out, ByteBuffer buffer, long position) throws IOException {
        long written = 0;
        while (buffer.hasRemaining()) {
            written += out.write(buffer, position + written);
        }
        return written;
    }

    private static Path moveIntoPlace(Path temporary, Path file) throws IOException {
        try {
            try {
                return Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                return Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException busy) {
            final String name = file.getFileName().toString();
            final int dot = name.lastIndexOf('.');
            final String stem = dot > 0 ? name.substring(0, dot) : name;
            final String ext = dot > 0 ? name.substring(dot) : "";
            for (int n = 2; n < 100; n++) {
                final Path other = file.resolveSibling(stem + "-" + n + ext);
                try {
                    return Files.move(temporary, other, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException alsoBusy) {
                    // Mapped as well; try the next name.
                }
            }
            Files.deleteIfExists(temporary);
            throw busy;
        }
    }

    private static byte[] padded(String text) {
        final byte[] out = new byte[NAME];
        java.util.Arrays.fill(out, (byte) ' ');
        final byte[] given = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(given, 0, out, 0, Math.min(NAME, given.length));
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * An SPX file read for Java.
     *
     * Read, not mapped, unlike in the other engines: on Windows a mapping
     * holds the file until the collector gets round to it, and an engine that
     * publishes the same histogram again, as a loop updating a plot does, would
     * find it cannot replace the file. What Java reads back is results, which
     * are small; the large files go the other way.
     */
    public static final class Reader implements AutoCloseable {

        private final Path file;
        private final Kind kind;
        private final String title;
        private final Map<String, Section> sections = new LinkedHashMap<>();
        private final ByteBuffer map;

        public Reader(Path file) throws IOException {
            this.file = file;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                final long size = channel.size();
                if (size < HEADER) throw new IOException(file.getFileName() + " is too short to be an SPX file.");
                if (size > Integer.MAX_VALUE) {
                    throw new IOException(file.getFileName() + " is larger than 2 GB; read it from Julia, C++ or Python.");
                }
                this.map = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
                while (map.hasRemaining() && channel.read(map) >= 0) {
                    // Until the buffer is full or the file ends.
                }
                map.flip();
            }
            final long size = map.limit();
            final byte[] magic = new byte[8];
            map.get(0, magic);
            if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException(file.getFileName() + " is not an SPX file.");
            }
            final int version = map.getInt(8);
            if (version != VERSION) {
                throw new IOException(file.getFileName() + " is SPX version " + version + "; this Sphere reads " + VERSION + ".");
            }
            this.kind = Kind.of(map.getInt(12));
            final int count = map.getInt(16);
            final long directory = map.getLong(24);
            this.title = string(40, NAME).strip();
            for (int k = 0; k < count; k++) {
                final int at = (int) (directory + (long) k * ENTRY);
                final String name = string(at, NAME).strip();
                final Section s = new Section(name, map.getInt(at + 24), map.getLong(at + 32), map.getLong(at + 40));
                if (s.offset() + s.bytes() > size) {
                    throw new IOException("Section " + name + " runs past the end of " + file.getFileName() + ".");
                }
                sections.put(name, s);
            }
        }

        private String string(int at, int length) {
            final byte[] bytes = new byte[length];
            map.get(at, bytes);
            return new String(bytes, StandardCharsets.US_ASCII);
        }

        public Path file() { return file; }
        public Kind kind() { return kind; }
        public String title() { return title; }
        public boolean has(String name) { return sections.containsKey(name); }

        public List<Section> sections() {
            return Collections.unmodifiableList(new ArrayList<>(sections.values()));
        }

        private Section section(String name, int type) throws IOException {
            final Section s = sections.get(name);
            if (s == null) throw new IOException(file.getFileName() + " has no section " + name + ".");
            if (s.type() != type) throw new IOException("Section " + name + " is " + s.typeName() + ".");
            return s;
        }

        public double[] f64(String name) throws IOException {
            final Section s = section(name, F64);
            final double[] out = new double[(int) s.count()];
            map.slice((int) s.offset(), (int) s.bytes()).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(out);
            return out;
        }

        public long[] i64(String name) throws IOException {
            final Section s = section(name, I64);
            final long[] out = new long[(int) s.count()];
            map.slice((int) s.offset(), (int) s.bytes()).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(out);
            return out;
        }

        /** A numeric section as doubles, whichever of the two numeric types it was written as. */
        public double[] numbers(String name) throws IOException {
            final Section s = sections.get(name);
            if (s != null && s.type() == I64) {
                final long[] whole = i64(name);
                final double[] out = new double[whole.length];
                for (int k = 0; k < whole.length; k++) out[k] = whole[k];
                return out;
            }
            return f64(name);
        }

        public String text(String name) throws IOException {
            final Section s = section(name, TEXT);
            final byte[] bytes = new byte[(int) s.count()];
            map.get((int) s.offset(), bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** The "Key: value" lines of the meta section, or nothing. */
        public Map<String, String> meta() throws IOException {
            final Map<String, String> out = new LinkedHashMap<>();
            if (!has("meta")) return out;
            for (String line : text("meta").split("\n")) {
                final int colon = line.indexOf(':');
                if (colon > 0) out.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            return out;
        }

        /** Nothing is held open; kept so a reader reads the same in a try block as a file does. */
        @Override
        public void close() {
        }
    }
}
