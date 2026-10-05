package com.sphere.core.rootio;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The bytes of one ROOT record, read as TBufferFile reads them: big-endian
 * numbers, TStrings, class headers (a byte count and a version, or a version
 * and a checksum for classes without ClassDef), and the tags ReadObjectAny
 * uses to refer back to a class or an object already met in the record.
 *
 * <p>Positions in tags count from the start of the key, so the buffer knows
 * how long the key header in front of it was.
 */
public final class RBuffer {

    static final int BYTE_COUNT_MASK = 0x40000000;
    static final long NEW_CLASS_TAG = 0xFFFFFFFFL;
    static final long CLASS_MASK = 0x80000000L;
    static final int MAP_OFFSET = 2;
    /** Set on a version when an STL collection of objects was written member by member. */
    static final int STREAMED_MEMBER_WISE = 0x4000;

    private final byte[] data;
    private int pos;
    private final int limit;
    /** The key header's length: tags count from the start of the key, not of this buffer. */
    private final int keylen;
    /** What a tag designates: a class name (Class) or an object. */
    final Map<Long, Object> refs = new HashMap<>();

    /** When set, where the tags that count from the key's start were read (positions in this buffer). */
    java.util.List<Integer> tagLog;

    /** A class registered under a tag. */
    record ClassTag(String name) {
    }

    public RBuffer(byte[] data, int keylen) {
        this(data, 0, data.length, keylen);
    }

    public RBuffer(byte[] data, int from, int to, int keylen) {
        this.data = data;
        this.pos = from;
        this.limit = to;
        this.keylen = keylen;
    }

    public int position() {
        return pos;
    }

    public void position(int at) {
        pos = at;
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return limit - pos;
    }

    /** The position as tags count it. */
    long displacement() {
        return (long) pos + keylen;
    }

    void moveToDisplacement(long d) {
        pos = (int) (d - keylen);
    }

    private void need(int n) {
        if (pos + n > limit) {
            throw new IllegalStateException("ROOT record read past its end (" + (pos + n) + " > " + limit + ")");
        }
    }

    public byte i8() {
        need(1);
        return data[pos++];
    }

    public int u8() {
        return i8() & 0xFF;
    }

    public short i16() {
        need(2);
        final short v = (short) (((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF));
        pos += 2;
        return v;
    }

    public int u16() {
        return i16() & 0xFFFF;
    }

    public int i32() {
        need(4);
        final int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8)
            | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long u32() {
        return Integer.toUnsignedLong(i32());
    }

    public long i64() {
        final long hi = u32();
        return (hi << 32) | u32();
    }

    public float f32() {
        return Float.intBitsToFloat(i32());
    }

    public double f64() {
        return Double.longBitsToDouble(i64());
    }

    public boolean bool() {
        return i8() != 0;
    }

    public byte[] bytes(int n) {
        need(n);
        final byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    public void skip(int n) {
        pos = Math.min(limit, pos + n);
    }

    /** A TString: one length byte, or 255 and a four byte length, then the characters. */
    public String tstring() {
        int n = u8();
        if (n == 255) n = i32();
        if (n < 0 || n > remaining()) throw new IllegalStateException("TString of " + n + " bytes at " + pos);
        final String s = new String(data, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    /** A C string, ended by a zero byte (the class name after a new-class tag). */
    String cstring() {
        final int start = pos;
        while (pos < limit && data[pos] != 0) pos++;
        final String s = new String(data, start, pos - start, StandardCharsets.UTF_8);
        if (pos < limit) pos++;
        return s;
    }

    /** What a class header says: version, checksum when the version is 0, and where the object ends. */
    public record Header(int version, long checksum, int start, int end, boolean memberWise) {
        public boolean counted() {
            return end > start;
        }
    }

    /**
     * ReadVersion: a byte count (when its mask bit is set) and a version; a
     * version of 0 is followed by the checksum of the class as written.
     */
    public Header header() {
        final int start = pos;
        int end = start;
        final long first = u32();
        int version;
        if ((first & BYTE_COUNT_MASK) != 0) {
            end = start + 4 + (int) (first & ~BYTE_COUNT_MASK);
            version = u16();
        } else {
            pos = start;
            version = u16();
        }
        final boolean memberWise = (version & STREAMED_MEMBER_WISE) != 0;
        version &= ~STREAMED_MEMBER_WISE;
        long checksum = 0;
        if (version == 0 && remaining() >= 4) checksum = u32();
        return new Header(version, checksum, start, end, memberWise);
    }

    /** Jumps to where a header said its object ends. */
    public void end(Header h) {
        if (h.counted() && h.end() <= limit) pos = h.end();
    }
}
