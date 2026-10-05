package com.sphere.core.rootio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The bytes of one ROOT record being written, as TBufferFile writes them:
 * big-endian numbers, TStrings, byte counts patched in once an object is
 * complete, and the tags WriteObjectAny and WriteClass leave so that an
 * object or a class met a second time is written as a reference.
 *
 * <p>Tags count from the start of the key, so the buffer knows the length of
 * the key header that will precede it.
 */
public final class WBuffer {

    private byte[] a = new byte[4096];
    private int n;
    private final int keylen;
    final Map<Object, Long> objects = new IdentityHashMap<>();
    final Map<String, Long> classes = new HashMap<>();

    public WBuffer(int keylen) {
        this.keylen = keylen;
    }

    public int length() {
        return n;
    }

    /** TBufferIO::MapObject: the object a key holds is known under that tag (1) before it is written. */
    public void mapObject(Object o, long tag) {
        objects.put(o, tag);
    }

    /** TBuffer::ResetMap: objects and classes met before are forgotten (a basket does it at each entry). */
    public void resetMap() {
        objects.clear();
        classes.clear();
    }

    /** Empties the buffer for reuse. */
    public void reset() {
        n = 0;
        resetMap();
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(a, n);
    }

    long displacement() {
        return (long) n + keylen;
    }

    private void room(int k) {
        if (n + k > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + k));
    }

    public WBuffer i8(int v) {
        room(1);
        a[n++] = (byte) v;
        return this;
    }

    public WBuffer i16(int v) {
        room(2);
        a[n++] = (byte) (v >>> 8);
        a[n++] = (byte) v;
        return this;
    }

    public WBuffer i32(int v) {
        room(4);
        a[n++] = (byte) (v >>> 24);
        a[n++] = (byte) (v >>> 16);
        a[n++] = (byte) (v >>> 8);
        a[n++] = (byte) v;
        return this;
    }

    public WBuffer u32(long v) {
        return i32((int) v);
    }

    public WBuffer i64(long v) {
        i32((int) (v >>> 32));
        return i32((int) v);
    }

    public WBuffer f32(float v) {
        return i32(Float.floatToRawIntBits(v));
    }

    public WBuffer f64(double v) {
        return i64(Double.doubleToRawLongBits(v));
    }

    public WBuffer bool(boolean v) {
        return i8(v ? 1 : 0);
    }

    public WBuffer bytes(byte[] b) {
        return bytes(b, 0, b.length);
    }

    public WBuffer bytes(byte[] b, int from, int len) {
        room(len);
        System.arraycopy(b, from, a, n, len);
        n += len;
        return this;
    }

    /** A TString: one length byte, or 255 and a four byte length. */
    public WBuffer tstring(String s) {
        final byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (raw.length < 255) {
            i8(raw.length);
        } else {
            i8(255);
            i32(raw.length);
        }
        return bytes(raw);
    }

    /** A class name after a new-class tag: the characters and a zero byte. */
    WBuffer cstring(String s) {
        bytes(s.getBytes(StandardCharsets.UTF_8));
        return i8(0);
    }

    /** Leaves room for a byte count; answers where it goes. */
    public int reserveCount() {
        final int at = n;
        i32(0);
        return at;
    }

    /** SetByteCount: the bytes written since the count, with its mask bit. */
    public void setCount(int at) {
        final int cnt = n - at - 4;
        a[at] = (byte) ((cnt >>> 24) | 0x40);
        a[at + 1] = (byte) (cnt >>> 16);
        a[at + 2] = (byte) (cnt >>> 8);
        a[at + 3] = (byte) cnt;
    }

    /** Overwrites four bytes already written. */
    public void patch32(int at, int v) {
        a[at] = (byte) (v >>> 24);
        a[at + 1] = (byte) (v >>> 16);
        a[at + 2] = (byte) (v >>> 8);
        a[at + 3] = (byte) v;
    }
}
