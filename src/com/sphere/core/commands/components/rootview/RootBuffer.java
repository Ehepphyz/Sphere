package com.sphere.components.rootview;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads the serialized form ROOT writes inside an object's payload.
 *
 * Every class writes a byte count and a version before its members, and a class
 * that inherits writes its base first. Following that structure is what lets a
 * reader skip a member it does not know instead of losing its place.
 */
public final class RootBuffer {

    /** Set on the byte count when the object is not a reference. */
    private static final int BYTE_COUNT_MASK = 0x40000000;

    private final ByteBuffer buffer;

    public RootBuffer(byte[] bytes) {
        this.buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    }

    public RootBuffer(ByteBuffer buffer) {
        this.buffer = buffer.order(ByteOrder.BIG_ENDIAN);
    }

    public int position() {
        return buffer.position();
    }

    public void position(int at) {
        buffer.position(at);
    }

    public int remaining() {
        return buffer.remaining();
    }

    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    public void skip(int bytes) {
        buffer.position(Math.min(buffer.limit(), buffer.position() + bytes));
    }

    public byte i8() {
        return buffer.get();
    }

    public int u8() {
        return buffer.get() & 0xFF;
    }

    public short i16() {
        return buffer.getShort();
    }

    public int u16() {
        return buffer.getShort() & 0xFFFF;
    }

    public int i32() {
        return buffer.getInt();
    }

    public long u32() {
        return Integer.toUnsignedLong(buffer.getInt());
    }

    public long i64() {
        return buffer.getLong();
    }

    public float f32() {
        return buffer.getFloat();
    }

    public double f64() {
        return buffer.getDouble();
    }

    public boolean bool() {
        return buffer.get() != 0;
    }

    /** A TString: one length byte, or 255 then a four byte length. */
    public String string() {
        if (!buffer.hasRemaining()) {
            return "";
        }
        int length = buffer.get() & 0xFF;
        if (length == 255) {
            if (buffer.remaining() < 4) {
                return "";
            }
            length = buffer.getInt();
        }
        if (length < 0 || length > buffer.remaining()) {
            return "";
        }
        byte[] text = new byte[length];
        buffer.get(text);
        return new String(text, StandardCharsets.UTF_8);
    }

    /** The header every class writes: its size in bytes and its version. */
    public static final class Header {
        public final int version;
        public final int start;
        public final int end;

        Header(int version, int start, int end) {
            this.version = version;
            this.start = start;
            this.end = end;
        }

        public boolean hasCount() {
            return end > start;
        }
    }

    /**
     * Reads a class header and returns where the class ends, so an unknown
     * member can be stepped over by jumping straight there.
     */
    public Header beginObject() {
        final int start = buffer.position();
        if (buffer.remaining() < 6) {
            return new Header(0, start, start);
        }
        final long count = u32();
        int end = start;
        int version;
        if ((count & BYTE_COUNT_MASK) != 0) {
            final int size = (int) (count & ~BYTE_COUNT_MASK);
            end = start + 4 + size;
            version = u16();
        } else {
            // No byte count: what was read is the version, on two bytes.
            buffer.position(start);
            version = u16();
        }
        return new Header(version, start, end);
    }

    /**
     * True when reading stopped within the object a header opened.
     *
     * Members that were read in the wrong shape carry the position past the end
     * the object declared, so this is what tells a decoder it lost its place.
     */
    public boolean landedInside(Header header) {
        if (!header.hasCount()) {
            return true;
        }
        return position() <= header.end;
    }

    /** Jumps to where a class header said its object ends. */
    public void endObject(Header header) {
        if (header.hasCount() && header.end <= buffer.limit() && header.end >= 0) {
            buffer.position(header.end);
        }
    }

    /** Set on fBits when the object is referenced; TObject::kIsReferenced. */
    private static final int IS_REFERENCED = 1 << 4;

    /** TObject: a version, a unique id and a bit field. */
    public void skipTObject() {
        if (buffer.remaining() < 10) {
            return;
        }
        u16();          // version
        i32();          // fUniqueID
        final int bits = i32();
        // A referenced object writes a process id after the bits. The bits that
        // are always set -- on the heap, not deleted -- are not this one.
        if ((bits & IS_REFERENCED) != 0 && buffer.remaining() >= 2) {
            u16();
        }
    }

    /** TNamed: a TObject, then the name and the title. */
    public String[] readTNamed() {
        Header header = beginObject();
        skipTObject();
        final String name = string();
        final String title = string();
        endObject(header);
        return new String[] {name, title};
    }

    /** The next four bytes as a signed integer, without consuming them. */
    public int peekI32() {
        if (buffer.remaining() < 4) {
            return -1;
        }
        return buffer.getInt(buffer.position());
    }

    /**
     * Reads a member declared as a pointer whose length is another member, the
     * way TGraph declares fX with [fNpoints].
     *
     * Such a member carries no length of its own: the streamer writes one flag
     * byte and then the values. A TArray, which is what fSumw2 and the bin edges
     * are, does carry its length -- reading one as the other is what leaves a
     * graph empty while the histograms come out right. Files that did write a
     * length are still accepted, since a length can only be there when it equals
     * the count already read.
     */
    public double[] readSizedArray(int count, boolean singlePrecision) {
        if (count <= 0) {
            if (hasRemaining()) {
                u8();
            }
            return new double[0];
        }
        if (peekI32() == count) {
            skip(4);
        } else if (hasRemaining()) {
            u8();
        }
        final int width = singlePrecision ? 4 : 8;
        final int n = Math.min(count, buffer.remaining() / width);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = singlePrecision ? buffer.getFloat() : buffer.getDouble();
        }
        return out;
    }

    public double[] readArrayD() {
        final int n = i32();
        if (n < 0 || n > buffer.remaining() / 8) {
            return new double[0];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = buffer.getDouble();
        }
        return out;
    }

    public double[] readArrayF() {
        final int n = i32();
        if (n < 0 || n > buffer.remaining() / 4) {
            return new double[0];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    public double[] readArrayI() {
        final int n = i32();
        if (n < 0 || n > buffer.remaining() / 4) {
            return new double[0];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = buffer.getInt();
        }
        return out;
    }

    public double[] readArrayS() {
        final int n = i32();
        if (n < 0 || n > buffer.remaining() / 2) {
            return new double[0];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = buffer.getShort();
        }
        return out;
    }

    public double[] readArrayC() {
        final int n = i32();
        if (n < 0 || n > buffer.remaining()) {
            return new double[0];
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = buffer.get();
        }
        return out;
    }

    /** Picks the array reader that matches a histogram's own class name. */
    public double[] readArrayFor(String className) {
        if (className.endsWith("D")) {
            return readArrayD();
        }
        if (className.endsWith("F")) {
            return readArrayF();
        }
        if (className.endsWith("I") || className.endsWith("L")) {
            return readArrayI();
        }
        if (className.endsWith("S")) {
            return readArrayS();
        }
        if (className.endsWith("C")) {
            return readArrayC();
        }
        return readArrayD();
    }
}
