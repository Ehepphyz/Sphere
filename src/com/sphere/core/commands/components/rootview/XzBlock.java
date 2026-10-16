package com.sphere.components.rootview;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;

/**
 * XZ decompression: the container liblzma writes, the LZMA2 chunks inside it
 * and the LZMA range decoder under those, written for Sphere so that a file
 * ROOT compressed with algorithm 2 opens without ROOT and without a library.
 *
 * ROOT's R__zipLZMA hands each block to lzma_stream_encoder with one LZMA2
 * filter and a CRC32 check, so behind the "XZ" tag of a ROOT block there is a
 * complete .xz stream. Everything that stream carries is verified here, the
 * way xz itself does: the CRC32 of the stream flags, of every block header and
 * of the index, the backward size of the footer, the sizes the index records
 * against the sizes decoded, and the check of every block (CRC32, CRC64 or
 * SHA-256). A stream that decodes to the right length but whose check does not
 * match is refused rather than returned: a flipped bit inside a basket would
 * otherwise come back as a plausible number.
 *
 * Whole .xz files (several streams, stream padding, the Delta filter) go
 * through the same code, which is what the format sniffer of the ROOT Lab uses
 * to look inside an event file compressed with xz.
 */
public final class XzBlock {

    private static final byte[] HEADER_MAGIC = {(byte) 0xFD, '7', 'z', 'X', 'Z', 0};
    private static final int CHECK_NONE = 0x00;
    private static final int CHECK_CRC32 = 0x01;
    private static final int CHECK_CRC64 = 0x04;
    private static final int CHECK_SHA256 = 0x0A;

    private static final long FILTER_DELTA = 0x03;
    private static final long FILTER_LZMA2 = 0x21;

    private XzBlock() {
    }

    /** What a decoded stream said about itself, for the integrity report. */
    public static final class Report {
        public int streams;
        public int blocks;
        public String check = "none";
        public boolean checksVerified;
        public long uncompressed;
    }

    /** True when the bytes start like an .xz stream. */
    public static boolean isXz(byte[] src, int offset, int length) {
        if (length < HEADER_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < HEADER_MAGIC.length; i++) {
            if (src[offset + i] != HEADER_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes the .xz stream(s) in src[offset, offset+length) into a buffer of
     * the size the caller expects (ROOT says it in the block header). Fewer
     * bytes than expected come back as a shorter array; more is an error.
     */
    public static byte[] decode(byte[] src, int offset, int length, int expected)
            throws DataFormatException {
        Output out = new Output(Math.max(expected, 0), true);
        decodeInto(src, offset, length, out, null);
        return out.toArray();
    }

    /** Decodes a whole .xz file whose size is not known in advance. */
    public static byte[] decodeAll(byte[] src, int offset, int length, long limit, Report report)
            throws DataFormatException {
        Output out = new Output((int) Math.min(Math.max(length * 4L, 1024), Math.max(1024, limit)), false);
        out.limit = limit;
        decodeInto(src, offset, length, out, report);
        return out.toArray();
    }

    /**
     * As much of the start of an .xz stream as the bytes given decode to, at
     * most limit bytes: what recognising the format of a compressed file needs,
     * from its first kilobytes, without its end.
     */
    public static byte[] decodePrefix(byte[] src, int offset, int length, int limit) {
        Output out = new Output(Math.min(limit, Math.max(length * 4, 1024)), false);
        out.limit = limit;
        try {
            decodeInto(src, offset, length, out, null);
        } catch (DataFormatException | RuntimeException cut) {
            // the stream goes on past what was read, or past the limit: keep what decoded
        }
        return out.toArray();
    }

    private static void decodeInto(byte[] src, int offset, int length, Output out, Report report)
            throws DataFormatException {
        final In in = new In(src, offset, offset + length);
        boolean first = true;
        while (in.remaining() > 0) {
            if (!first) {
                // Stream padding: null bytes in groups of four between streams.
                int zeros = 0;
                while (in.remaining() > 0 && in.peek() == 0) {
                    in.u8();
                    zeros++;
                }
                if (in.remaining() == 0) {
                    if ((zeros & 3) != 0) {
                        throw new DataFormatException("xz stream padding is not a multiple of four bytes");
                    }
                    break;
                }
                if ((zeros & 3) != 0) {
                    throw new DataFormatException("xz stream padding is not a multiple of four bytes");
                }
            }
            decodeStream(in, out, report);
            first = false;
        }
        if (first) {
            throw new DataFormatException("empty xz input");
        }
    }

    // ---- stream ---------------------------------------------------------------

    private static void decodeStream(In in, Output out, Report report) throws DataFormatException {
        final int streamStart = in.pos;
        for (byte magic : HEADER_MAGIC) {
            if (in.remaining() == 0 || (byte) in.u8() != magic) {
                throw new DataFormatException("not an xz stream (bad magic bytes)");
            }
        }
        final int flags0 = in.u8();
        final int flags1 = in.u8();
        final long flagsCrc = in.le32();
        if (crc32(in.src, in.pos - 6, 2) != flagsCrc) {
            throw new DataFormatException("xz stream header CRC32 does not match");
        }
        if (flags0 != 0 || (flags1 & 0xF0) != 0) {
            throw new DataFormatException("xz stream flags use reserved bits");
        }
        final int checkType = flags1 & 0x0F;
        final int checkSize = checkSize(checkType);

        // Block sizes as decoded, to be compared with what the index records.
        long[] unpadded = new long[8];
        long[] sizes = new long[8];
        int count = 0;

        while (true) {
            if (in.remaining() == 0) {
                throw new DataFormatException("xz stream ends before its index");
            }
            if (in.peek() == 0) {
                break;                                   // the index indicator
            }
            final long before = out.size;
            final long[] got = decodeBlock(in, out, checkType, checkSize);
            if (count == unpadded.length) {
                unpadded = Arrays.copyOf(unpadded, count * 2);
                sizes = Arrays.copyOf(sizes, count * 2);
            }
            unpadded[count] = got[0];
            sizes[count] = out.size - before;
            count++;
        }

        // ---- index
        final int indexStart = in.pos;
        in.u8();                                         // indicator
        final long records = in.vli();
        if (records != count) {
            throw new DataFormatException("xz index lists " + records + " blocks, the stream holds " + count);
        }
        for (int i = 0; i < count; i++) {
            final long recordedUnpadded = in.vli();
            final long recordedSize = in.vli();
            if (recordedUnpadded != unpadded[i] || recordedSize != sizes[i]) {
                throw new DataFormatException("xz index disagrees with block " + i);
            }
        }
        while (((in.pos - indexStart) & 3) != 0) {
            if (in.u8() != 0) {
                throw new DataFormatException("xz index padding is not zero");
            }
        }
        final int indexSize = in.pos - indexStart;
        final long indexCrc = in.le32();
        if (crc32(in.src, indexStart, indexSize) != indexCrc) {
            throw new DataFormatException("xz index CRC32 does not match");
        }

        // ---- footer
        final long footerCrc = in.le32();
        final int footerFields = in.pos;
        final long backward = in.le32();
        final int fflags0 = in.u8();
        final int fflags1 = in.u8();
        if (in.u8() != 'Y' || in.u8() != 'Z') {
            throw new DataFormatException("xz stream footer magic is wrong");
        }
        if (crc32(in.src, footerFields, 6) != footerCrc) {
            throw new DataFormatException("xz stream footer CRC32 does not match");
        }
        if ((backward + 1) * 4 != indexSize + 4) {
            throw new DataFormatException("xz backward size does not match the index");
        }
        if (fflags0 != flags0 || fflags1 != flags1) {
            throw new DataFormatException("xz footer flags differ from the header flags");
        }
        if (report != null) {
            report.streams++;
            report.blocks += count;
            report.check = checkName(checkType);
            report.checksVerified = checkType != CHECK_NONE;
            report.uncompressed = out.size;
        }
        if (in.pos - streamStart < 32) {
            throw new DataFormatException("xz stream is too short");
        }
    }

    /** One block; returns {unpadded size}. */
    private static long[] decodeBlock(In in, Output out, int checkType, int checkSize)
            throws DataFormatException {
        final int headerStart = in.pos;
        final int headerSize = (in.u8() + 1) * 4;
        if (in.remaining() < headerSize - 1) {
            throw new DataFormatException("xz block header runs past the input");
        }
        final int headerEnd = headerStart + headerSize;
        final long headerCrc = Integer.toUnsignedLong(
            (in.src[headerEnd - 4] & 0xFF) | ((in.src[headerEnd - 3] & 0xFF) << 8)
            | ((in.src[headerEnd - 2] & 0xFF) << 16) | ((in.src[headerEnd - 1] & 0xFF) << 24));
        if (crc32(in.src, headerStart, headerSize - 4) != headerCrc) {
            throw new DataFormatException("xz block header CRC32 does not match");
        }
        final int blockFlags = in.u8();
        if ((blockFlags & 0x3C) != 0) {
            throw new DataFormatException("xz block flags use reserved bits");
        }
        final int filterCount = (blockFlags & 0x03) + 1;
        final long compressedSize = (blockFlags & 0x40) != 0 ? in.vli() : -1;
        final long uncompressedSize = (blockFlags & 0x80) != 0 ? in.vli() : -1;

        int deltaDistance = 0;
        boolean lzma2 = false;
        int dictionaryBits = -1;
        for (int f = 0; f < filterCount; f++) {
            final long id = in.vli();
            final long propsSize = in.vli();
            if (in.pos + propsSize > headerEnd - 4) {
                throw new DataFormatException("xz filter properties run past the block header");
            }
            if (id == FILTER_LZMA2) {
                if (propsSize != 1 || f != filterCount - 1) {
                    throw new DataFormatException("xz LZMA2 filter is malformed");
                }
                dictionaryBits = in.u8();
                if (dictionaryBits > 40) {
                    throw new DataFormatException("xz LZMA2 dictionary size is invalid");
                }
                lzma2 = true;
            } else if (id == FILTER_DELTA) {
                if (propsSize != 1 || f == filterCount - 1) {
                    throw new DataFormatException("xz Delta filter is malformed");
                }
                deltaDistance = in.u8() + 1;
            } else {
                throw new DataFormatException("xz filter 0x" + Long.toHexString(id)
                    + " is not supported (ROOT writes LZMA2 only)");
            }
        }
        if (!lzma2) {
            throw new DataFormatException("xz block has no LZMA2 filter");
        }
        while (in.pos < headerEnd - 4) {
            if (in.u8() != 0) {
                throw new DataFormatException("xz block header padding is not zero");
            }
        }
        in.pos = headerEnd;

        final int dataStart = in.pos;
        final int outStart = out.size;
        Lzma2.decode(in, out);
        final long compressed = in.pos - dataStart;
        final long produced = out.size - outStart;
        if (compressedSize >= 0 && compressedSize != compressed) {
            throw new DataFormatException("xz block compressed size disagrees with its header");
        }
        if (uncompressedSize >= 0 && uncompressedSize != produced) {
            throw new DataFormatException("xz block uncompressed size disagrees with its header");
        }
        if (deltaDistance > 0) {
            undelta(out.buf, outStart, out.size, deltaDistance);
        }
        // Block padding: the block is made a multiple of four bytes.
        while (((in.pos - headerStart) & 3) != 0) {
            if (in.u8() != 0) {
                throw new DataFormatException("xz block padding is not zero");
            }
        }
        if (in.remaining() < checkSize) {
            throw new DataFormatException("xz block check runs past the input");
        }
        verifyCheck(checkType, out.buf, outStart, out.size - outStart, in.src, in.pos);
        in.pos += checkSize;
        return new long[] {headerSize + compressed + checkSize};
    }

    private static void undelta(byte[] buf, int from, int to, int distance) {
        for (int i = from + distance; i < to; i++) {
            buf[i] = (byte) (buf[i] + buf[i - distance]);
        }
    }

    // ---- checks ----------------------------------------------------------------

    private static int checkSize(int type) throws DataFormatException {
        if (type == CHECK_NONE) {
            return 0;
        }
        // The size of an unknown check is fixed by its id, in groups of three.
        final int[] sizes = {0, 4, 4, 4, 8, 8, 8, 16, 16, 16, 32, 32, 32, 64, 64, 64};
        if (type != CHECK_CRC32 && type != CHECK_CRC64 && type != CHECK_SHA256) {
            throw new DataFormatException("xz check type " + type + " is not supported");
        }
        return sizes[type];
    }

    private static String checkName(int type) {
        return switch (type) {
            case CHECK_CRC32 -> "CRC32";
            case CHECK_CRC64 -> "CRC64";
            case CHECK_SHA256 -> "SHA-256";
            default -> "none";
        };
    }

    private static void verifyCheck(int type, byte[] data, int from, int length, byte[] src, int at)
            throws DataFormatException {
        switch (type) {
            case CHECK_NONE -> {
            }
            case CHECK_CRC32 -> {
                final long stored = Integer.toUnsignedLong((src[at] & 0xFF) | ((src[at + 1] & 0xFF) << 8)
                    | ((src[at + 2] & 0xFF) << 16) | ((src[at + 3] & 0xFF) << 24));
                if (crc32(data, from, length) != stored) {
                    throw new DataFormatException("xz block CRC32 does not match: the data is damaged");
                }
            }
            case CHECK_CRC64 -> {
                long stored = 0;
                for (int i = 7; i >= 0; i--) {
                    stored = (stored << 8) | (src[at + i] & 0xFF);
                }
                if (crc64(data, from, length) != stored) {
                    throw new DataFormatException("xz block CRC64 does not match: the data is damaged");
                }
            }
            case CHECK_SHA256 -> {
                try {
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    sha.update(data, from, length);
                    final byte[] digest = sha.digest();
                    for (int i = 0; i < 32; i++) {
                        if (digest[i] != src[at + i]) {
                            throw new DataFormatException("xz block SHA-256 does not match: the data is damaged");
                        }
                    }
                } catch (NoSuchAlgorithmException absent) {
                    // Every JDK has SHA-256; without it the data simply goes unchecked.
                }
            }
            default -> throw new DataFormatException("xz check type " + type + " is not supported");
        }
    }

    private static long crc32(byte[] data, int from, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, from, length);
        return crc.getValue();
    }

    private static final long[] CRC64_TABLE = new long[256];

    static {
        // ECMA-182, reflected, as xz uses it.
        final long poly = 0xC96C5795D7870F42L;
        for (int i = 0; i < 256; i++) {
            long r = i;
            for (int k = 0; k < 8; k++) {
                r = (r & 1) != 0 ? (r >>> 1) ^ poly : r >>> 1;
            }
            CRC64_TABLE[i] = r;
        }
    }

    static long crc64(byte[] data, int from, int length) {
        long crc = -1L;
        for (int i = from; i < from + length; i++) {
            crc = CRC64_TABLE[(int) ((crc ^ data[i]) & 0xFF)] ^ (crc >>> 8);
        }
        return ~crc;
    }

    // ---- input and output -----------------------------------------------------

    /** A cursor over the compressed bytes, refusing to read past its end. */
    static final class In {
        final byte[] src;
        final int end;
        int pos;

        In(byte[] src, int pos, int end) {
            this.src = src;
            this.pos = pos;
            this.end = end;
        }

        int remaining() {
            return end - pos;
        }

        int peek() {
            return src[pos] & 0xFF;
        }

        int u8() throws DataFormatException {
            if (pos >= end) {
                throw new DataFormatException("xz data ends unexpectedly");
            }
            return src[pos++] & 0xFF;
        }

        long le32() throws DataFormatException {
            long v = 0;
            for (int i = 0; i < 4; i++) {
                v |= ((long) u8()) << (8 * i);
            }
            return v;
        }

        /** A variable-length integer: seven bits a byte, least significant first, at most nine bytes. */
        long vli() throws DataFormatException {
            long value = 0;
            for (int i = 0; i < 9; i++) {
                final int b = u8();
                value |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    if (b == 0 && i > 0) {
                        throw new DataFormatException("xz integer is not minimally encoded");
                    }
                    return value;
                }
            }
            throw new DataFormatException("xz integer is too long");
        }
    }

    /** The decoded bytes, which are also the dictionary matches copy from. */
    static final class Output {
        byte[] buf;
        int size;
        final boolean exact;
        long limit = Integer.MAX_VALUE - 16;

        Output(int capacity, boolean exact) {
            this.buf = new byte[Math.max(capacity, 0)];
            this.exact = exact;
        }

        void ensure(int more) throws DataFormatException {
            final long need = (long) size + more;
            if (need <= buf.length) {
                return;
            }
            if (exact || need > limit) {
                throw new DataFormatException("xz data decodes to more bytes than expected");
            }
            buf = Arrays.copyOf(buf, (int) Math.min(limit, Math.max(need, buf.length * 2L)));
        }

        byte[] toArray() {
            return size == buf.length ? buf : Arrays.copyOf(buf, size);
        }
    }

    // ---- LZMA2 ------------------------------------------------------------------

    /** The chunked layer: uncompressed chunks and LZMA chunks, each with its own range coder. */
    static final class Lzma2 {

        private Lzma2() {
        }

        static void decode(In in, Output out) throws DataFormatException {
            Lzma lzma = null;
            boolean needDictReset = true;
            boolean needProps = true;
            int dictStart = out.size;

            while (true) {
                final int control = in.u8();
                if (control == 0x00) {
                    return;
                }
                if (control >= 0xE0 || control == 0x01) {
                    needProps = true;
                    needDictReset = false;
                    dictStart = out.size;
                } else if (needDictReset) {
                    throw new DataFormatException("LZMA2 stream does not start with a dictionary reset");
                }

                if (control >= 0x80) {
                    final int unpacked = ((control & 0x1F) << 16) + (in.u8() << 8) + in.u8() + 1;
                    final int packed = (in.u8() << 8) + in.u8() + 1;
                    if (control >= 0xC0) {
                        final int props = in.u8();
                        lzma = Lzma.fromProps(props);
                        needProps = false;
                    } else if (needProps) {
                        throw new DataFormatException("LZMA2 chunk without the properties it needs");
                    } else if (control >= 0xA0) {
                        lzma.reset();
                    }
                    if (packed < 5 || in.remaining() < packed) {
                        throw new DataFormatException("LZMA2 chunk runs past the input");
                    }
                    out.ensure(unpacked);
                    final int chunkEnd = in.pos + packed;
                    lzma.decodeChunk(in.src, in.pos, chunkEnd, out, dictStart, unpacked);
                    in.pos = chunkEnd;
                } else {
                    if (control > 0x02) {
                        throw new DataFormatException("LZMA2 control byte 0x" + Integer.toHexString(control)
                            + " is invalid");
                    }
                    final int size = (in.u8() << 8) + in.u8() + 1;
                    if (in.remaining() < size) {
                        throw new DataFormatException("LZMA2 uncompressed chunk runs past the input");
                    }
                    out.ensure(size);
                    System.arraycopy(in.src, in.pos, out.buf, out.size, size);
                    out.size += size;
                    in.pos += size;
                }
            }
        }
    }

    // ---- LZMA ---------------------------------------------------------------------

    /**
     * The LZMA decoder proper: a range decoder driving adaptive binary models,
     * literals coded against the previous byte, matches against the four most
     * recent distances. Positions count from the last dictionary reset.
     */
    static final class Lzma {

        private static final int STATES = 12;
        private static final int POS_STATES_MAX = 16;
        private static final int LITERAL_STATES = 7;
        private static final int DIST_STATES = 4;
        private static final int DIST_SLOTS = 64;
        private static final int DIST_MODEL_START = 4;
        private static final int DIST_MODEL_END = 14;
        private static final int FULL_DISTANCES = 128;
        private static final int ALIGN_BITS = 4;
        private static final int MATCH_LEN_MIN = 2;
        private static final int PROB_INIT = 1024;

        private final int lc;
        private final int lp;
        private final int pb;

        private final short[] isMatch = new short[STATES * POS_STATES_MAX];
        private final short[] isRep = new short[STATES];
        private final short[] isRep0 = new short[STATES];
        private final short[] isRep1 = new short[STATES];
        private final short[] isRep2 = new short[STATES];
        private final short[] isRep0Long = new short[STATES * POS_STATES_MAX];
        private final short[] distSlot = new short[DIST_STATES * DIST_SLOTS];
        private final short[] distSpecial = new short[FULL_DISTANCES - DIST_MODEL_END];
        private final short[] distAlign = new short[1 << ALIGN_BITS];
        private final LengthDecoder matchLen = new LengthDecoder();
        private final LengthDecoder repLen = new LengthDecoder();
        private final short[] literal;

        private int state;
        private int rep0;
        private int rep1;
        private int rep2;
        private int rep3;

        // the range decoder of the current chunk
        private byte[] src;
        private int at;
        private int end;
        private int range;
        private int code;

        private Lzma(int lc, int lp, int pb) {
            this.lc = lc;
            this.lp = lp;
            this.pb = pb;
            this.literal = new short[0x300 << (lc + lp)];
            reset();
        }

        static Lzma fromProps(int props) throws DataFormatException {
            if (props > (4 * 5 + 4) * 9 + 8) {
                throw new DataFormatException("LZMA properties byte is invalid");
            }
            final int pb = props / (9 * 5);
            props -= pb * 9 * 5;
            final int lp = props / 9;
            final int lc = props - lp * 9;
            if (lc + lp > 4) {
                throw new DataFormatException("LZMA2 requires lc + lp <= 4");
            }
            return new Lzma(lc, lp, pb);
        }

        void reset() {
            state = 0;
            rep0 = rep1 = rep2 = rep3 = 0;
            Arrays.fill(isMatch, (short) PROB_INIT);
            Arrays.fill(isRep, (short) PROB_INIT);
            Arrays.fill(isRep0, (short) PROB_INIT);
            Arrays.fill(isRep1, (short) PROB_INIT);
            Arrays.fill(isRep2, (short) PROB_INIT);
            Arrays.fill(isRep0Long, (short) PROB_INIT);
            Arrays.fill(distSlot, (short) PROB_INIT);
            Arrays.fill(distSpecial, (short) PROB_INIT);
            Arrays.fill(distAlign, (short) PROB_INIT);
            Arrays.fill(literal, (short) PROB_INIT);
            matchLen.reset();
            repLen.reset();
        }

        // ---- range decoder

        private void rcInit(byte[] src, int from, int to) throws DataFormatException {
            this.src = src;
            this.at = from;
            this.end = to;
            if ((src[at++] & 0xFF) != 0) {
                throw new DataFormatException("LZMA range coder does not start with zero");
            }
            code = 0;
            for (int i = 0; i < 4; i++) {
                code = (code << 8) | (src[at++] & 0xFF);
            }
            range = -1;
            if (code == -1) {
                throw new DataFormatException("LZMA range coder starts out of range");
            }
        }

        private void normalize() throws DataFormatException {
            if (Integer.compareUnsigned(range, 1 << 24) < 0) {
                if (at >= end) {
                    throw new DataFormatException("LZMA chunk ends inside its data");
                }
                range <<= 8;
                code = (code << 8) | (src[at++] & 0xFF);
            }
        }

        private int bit(short[] probs, int index) throws DataFormatException {
            normalize();
            final int prob = probs[index];
            final int bound = (range >>> 11) * prob;
            if (Integer.compareUnsigned(code, bound) < 0) {
                range = bound;
                probs[index] = (short) (prob + ((2048 - prob) >>> 5));
                return 0;
            }
            range -= bound;
            code -= bound;
            probs[index] = (short) (prob - (prob >>> 5));
            return 1;
        }

        private int bitTree(short[] probs, int base, int bits) throws DataFormatException {
            int symbol = 1;
            for (int i = 0; i < bits; i++) {
                symbol = (symbol << 1) | bit(probs, base + symbol);
            }
            return symbol - (1 << bits);
        }

        private int bitTreeReverse(short[] probs, int base, int bits) throws DataFormatException {
            int symbol = 1;
            int result = 0;
            for (int i = 0; i < bits; i++) {
                final int b = bit(probs, base + symbol);
                symbol = (symbol << 1) | b;
                result |= b << i;
            }
            return result;
        }

        private int direct(int bits) throws DataFormatException {
            int result = 0;
            for (int i = 0; i < bits; i++) {
                normalize();
                range >>>= 1;
                code -= range;
                final int t = code >> 31;          // -1 when code went negative, i.e. the bit is 0
                code += range & t;
                result = (result << 1) + (t + 1);
            }
            return result;
        }

        // ---- one chunk

        void decodeChunk(byte[] src, int from, int to, Output out, int dictStart, int unpacked)
                throws DataFormatException {
            rcInit(src, from, to);
            final byte[] buf = out.buf;
            int pos = out.size;
            final int limit = pos + unpacked;
            final int pbMask = (1 << pb) - 1;
            final int lpMask = (1 << lp) - 1;

            while (pos < limit) {
                final int rel = pos - dictStart;
                final int posState = rel & pbMask;
                if (bit(isMatch, state * POS_STATES_MAX + posState) == 0) {
                    final int prev = rel > 0 ? buf[pos - 1] & 0xFF : 0;
                    final int base = 0x300 * (((rel & lpMask) << lc) + (prev >>> (8 - lc)));
                    int symbol = 1;
                    if (state < LITERAL_STATES) {
                        while (symbol < 0x100) {
                            symbol = (symbol << 1) | bit(literal, base + symbol);
                        }
                    } else {
                        if (Integer.compareUnsigned(rep0, rel) >= 0) {
                            throw new DataFormatException("LZMA matched literal refers before the dictionary");
                        }
                        int matchByte = (buf[pos - rep0 - 1] & 0xFF) << 1;
                        int offset = 0x100;
                        while (symbol < 0x100) {
                            final int matchBit = matchByte & offset;
                            matchByte <<= 1;
                            if (bit(literal, base + offset + matchBit + symbol) != 0) {
                                symbol = (symbol << 1) | 1;
                                offset &= matchBit;
                            } else {
                                symbol <<= 1;
                                offset &= ~matchBit;
                            }
                        }
                    }
                    buf[pos++] = (byte) symbol;
                    state = state < 4 ? 0 : state < 10 ? state - 3 : state - 6;
                    continue;
                }

                int length;
                if (bit(isRep, state) == 0) {
                    length = matchLen.decode(this, posState);
                    state = state < LITERAL_STATES ? 7 : 10;
                    rep3 = rep2;
                    rep2 = rep1;
                    rep1 = rep0;
                    rep0 = distance(length);
                    if (rep0 == -1) {
                        throw new DataFormatException("LZMA2 data carries an end marker");
                    }
                } else {
                    if (bit(isRep0, state) == 0) {
                        if (bit(isRep0Long, state * POS_STATES_MAX + posState) == 0) {
                            // a single byte from the most recent distance
                            if (Integer.compareUnsigned(rep0, rel) >= 0) {
                                throw new DataFormatException("LZMA short repeat refers before the dictionary");
                            }
                            state = state < LITERAL_STATES ? 9 : 11;
                            buf[pos] = buf[pos - rep0 - 1];
                            pos++;
                            continue;
                        }
                    } else {
                        final int dist;
                        if (bit(isRep1, state) == 0) {
                            dist = rep1;
                        } else {
                            if (bit(isRep2, state) == 0) {
                                dist = rep2;
                            } else {
                                dist = rep3;
                                rep3 = rep2;
                            }
                            rep2 = rep1;
                        }
                        rep1 = rep0;
                        rep0 = dist;
                    }
                    length = repLen.decode(this, posState);
                    state = state < LITERAL_STATES ? 8 : 11;
                }

                if (Integer.compareUnsigned(rep0, rel) >= 0) {
                    throw new DataFormatException("LZMA match refers before the dictionary");
                }
                if (pos + length > limit) {
                    throw new DataFormatException("LZMA match runs past the end of its chunk");
                }
                int from0 = pos - rep0 - 1;
                if (rep0 + 1 >= length) {
                    System.arraycopy(buf, from0, buf, pos, length);
                    pos += length;
                } else {
                    // overlapping on purpose: a short distance repeats a pattern
                    for (int i = 0; i < length; i++) {
                        buf[pos++] = buf[from0++];
                    }
                }
            }
            normalize();
            if (code != 0) {
                throw new DataFormatException("LZMA chunk did not end cleanly (range coder not finished)");
            }
            if (at != end) {
                throw new DataFormatException("LZMA chunk size disagrees with its data");
            }
            out.size = pos;
        }

        private int distance(int length) throws DataFormatException {
            final int lenState = Math.min(length - MATCH_LEN_MIN, DIST_STATES - 1);
            final int slot = bitTree(distSlot, lenState * DIST_SLOTS, 6);
            if (slot < DIST_MODEL_START) {
                return slot;
            }
            final int limit = (slot >>> 1) - 1;
            int dist = (2 | (slot & 1)) << limit;
            if (slot < DIST_MODEL_END) {
                dist += bitTreeReverse(distSpecial, dist - slot - 1, limit);
            } else {
                dist += direct(limit - ALIGN_BITS) << ALIGN_BITS;
                dist += bitTreeReverse(distAlign, 0, ALIGN_BITS);
            }
            return dist;
        }

        /** Match lengths: 2 to 9 cheaply, 10 to 17 a little less so, then up to 273. */
        private static final class LengthDecoder {
            private final short[] choice = new short[2];
            private final short[] low = new short[POS_STATES_MAX << 3];
            private final short[] mid = new short[POS_STATES_MAX << 3];
            private final short[] high = new short[256];

            void reset() {
                Arrays.fill(choice, (short) PROB_INIT);
                Arrays.fill(low, (short) PROB_INIT);
                Arrays.fill(mid, (short) PROB_INIT);
                Arrays.fill(high, (short) PROB_INIT);
            }

            int decode(Lzma rc, int posState) throws DataFormatException {
                if (rc.bit(choice, 0) == 0) {
                    return MATCH_LEN_MIN + rc.bitTree(low, posState << 3, 3);
                }
                if (rc.bit(choice, 1) == 0) {
                    return MATCH_LEN_MIN + 8 + rc.bitTree(mid, posState << 3, 3);
                }
                return MATCH_LEN_MIN + 16 + rc.bitTree(high, 0, 8);
            }
        }
    }
}
