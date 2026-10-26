package com.sphere.core.geantbackend;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Sphere's end of geant4-bridge's shared-memory channel (g4b_shm.h): the
 * region file mapped with the Foreign Function and Memory API, a request ring
 * this side produces into and a reply ring it consumes, both lock-free
 * single-producer single-consumer rings advanced with acquire/release
 * accesses. No copy goes through a pipe or a socket.
 */
public final class G4ShmChannel implements AutoCloseable {

    static final int MAGIC = 0x52423447;              // "G4BR"
    static final int LAYOUT_VERSION = 1;
    static final long OFF_MAGIC = 0, OFF_VERSION = 4, OFF_SIZE = 8, OFF_REQ = 16, OFF_REQ_CAP = 24, OFF_REP = 32,
        OFF_REP_CAP = 40, OFF_ENGINE_BEAT = 64, OFF_CLIENT_BEAT = 128, OFF_CANCEL = 192, OFF_STATE = 196, OFF_PID = 200,
        OFF_DESCRIPTION = 256;
    static final long RING_HEAD = 0, RING_TAIL = 64, RING_DATA = 128;

    public static final int STATE_STARTING = 0, STATE_READY = 1, STATE_BUSY = 2, STATE_STOPPED = 3;

    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();

    private final Arena arena;
    private final MemorySegment region;
    private final long reqAt, reqCap, repAt, repCap;

    private G4ShmChannel(Arena arena, MemorySegment region) {
        this.arena = arena;
        this.region = region;
        reqAt = region.get(ValueLayout.JAVA_LONG, OFF_REQ);
        reqCap = region.get(ValueLayout.JAVA_LONG, OFF_REQ_CAP);
        repAt = region.get(ValueLayout.JAVA_LONG, OFF_REP);
        repCap = region.get(ValueLayout.JAVA_LONG, OFF_REP_CAP);
    }

    /** Maps a region the engine has laid out; null when it is not ready (no magic yet). */
    public static G4ShmChannel attach(Path file) throws IOException {
        final Arena arena = Arena.ofShared();
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final long size = fc.size();
            if (size < 8192) {
                arena.close();
                return null;
            }
            final MemorySegment seg = fc.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
            final int magic = (int) INT.getAcquire(seg, OFF_MAGIC);
            if (magic != MAGIC || seg.get(ValueLayout.JAVA_INT, OFF_VERSION) != LAYOUT_VERSION) {
                arena.close();
                return null;
            }
            final G4ShmChannel ch = new G4ShmChannel(arena, seg);
            if (ch.reqAt <= 0 || ch.repAt <= 0 || ch.repAt + RING_DATA + ch.repCap > size || ch.reqCap <= 0) {
                arena.close();
                return null;
            }
            return ch;
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    public int state() {
        return (int) INT.getAcquire(region, OFF_STATE);
    }

    public long engineBeat() {
        return (long) LONG.getAcquire(region, OFF_ENGINE_BEAT);
    }

    public void clientBeat() {
        LONG.setRelease(region, OFF_CLIENT_BEAT, System.currentTimeMillis());
    }

    public void requestCancel() {
        INT.setRelease(region, OFF_CANCEL, 1);
    }

    public int enginePid() {
        return region.get(ValueLayout.JAVA_INT, OFF_PID);
    }

    public String description() {
        final byte[] b = new byte[256];
        MemorySegment.copy(region, ValueLayout.JAVA_BYTE, OFF_DESCRIPTION, b, 0, 256);
        int n = 0;
        while (n < b.length && b[n] != 0) {
            n++;
        }
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    public long requestCapacity() {
        return reqCap;
    }

    public long replyCapacity() {
        return repCap;
    }

    // ------------------------------------------------------------------
    // Rings

    /** Appends a request; false when the ring has no room now. */
    public synchronized boolean push(int id, String text) {
        final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        final long need = (8L + payload.length + 7L) & ~7L;
        if (need > reqCap) {
            throw new IllegalArgumentException("request of " + payload.length + " bytes exceeds the ring");
        }
        final long tail = region.get(ValueLayout.JAVA_LONG, reqAt + RING_TAIL);
        final long head = (long) LONG.getAcquire(region, reqAt + RING_HEAD);
        if (reqCap - (tail - head) < need) {
            return false;
        }
        final byte[] frame = new byte[8];
        putInt(frame, 0, payload.length);
        putInt(frame, 4, id);
        copyIn(reqAt, reqCap, tail, frame);
        copyIn(reqAt, reqCap, tail + 8, payload);
        LONG.setRelease(region, reqAt + RING_TAIL, tail + need);
        return true;
    }

    /** One reply message: the request id it belongs to and its text. */
    public record Message(int id, String text) {
    }

    /** Takes the next reply if there is one. */
    public synchronized Message poll() {
        final long head = region.get(ValueLayout.JAVA_LONG, repAt + RING_HEAD);
        final long tail = (long) LONG.getAcquire(region, repAt + RING_TAIL);
        if (tail == head) {
            return null;
        }
        final byte[] frame = new byte[8];
        copyOut(repAt, repCap, head, frame);
        final int length = getInt(frame, 0), id = getInt(frame, 4);
        if (length < 0 || length > repCap) {
            throw new IllegalStateException("corrupt reply frame of length " + length);
        }
        final byte[] payload = new byte[length];
        copyOut(repAt, repCap, head + 8, payload);
        LONG.setRelease(region, repAt + RING_HEAD, head + ((8L + length + 7L) & ~7L));
        return new Message(id, new String(payload, StandardCharsets.UTF_8));
    }

    private void copyIn(long ring, long cap, long pos, byte[] src) {
        final long at = Math.floorMod(pos, cap);
        final int first = (int) Math.min(src.length, cap - at);
        MemorySegment.copy(src, 0, region, ValueLayout.JAVA_BYTE, ring + RING_DATA + at, first);
        if (first < src.length) {
            MemorySegment.copy(src, first, region, ValueLayout.JAVA_BYTE, ring + RING_DATA, src.length - first);
        }
    }

    private void copyOut(long ring, long cap, long pos, byte[] dst) {
        final long at = Math.floorMod(pos, cap);
        final int first = (int) Math.min(dst.length, cap - at);
        MemorySegment.copy(region, ValueLayout.JAVA_BYTE, ring + RING_DATA + at, dst, 0, first);
        if (first < dst.length) {
            MemorySegment.copy(region, ValueLayout.JAVA_BYTE, ring + RING_DATA, dst, first, dst.length - first);
        }
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }

    private static int getInt(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    @Override
    public void close() {
        try {
            arena.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }
}
