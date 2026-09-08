package com.sphere.components.rootview;

import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Zstandard decompression, which ROOT has used by default since 6.20.
 *
 * Only the decoder is here, and only what a ROOT block uses: no dictionary, no
 * long-distance matcher window beyond the block itself. Frames written by
 * ZSTD_compress are read whole, checksum included but not verified.
 */
public final class ZstdBlock {

    private static final int MAGIC = 0xFD2FB528;
    private static final int SKIPPABLE_LOW = 0x184D2A50;
    private static final int SKIPPABLE_HIGH = 0x184D2A5F;

    private ZstdBlock() {
    }

    /** Decompresses one or more frames into a buffer of the expected size. */
    public static byte[] decode(byte[] src, int offset, int length, int expected)
            throws DataFormatException {
        byte[] out = new byte[expected];
        int written = 0;
        int at = offset;
        final int end = offset + length;

        while (at < end - 3) {
            final int magic = le32(src, at);
            if (magic >= SKIPPABLE_LOW && magic <= SKIPPABLE_HIGH) {
                final int size = le32(src, at + 4);
                at += 8 + size;
                continue;
            }
            if (magic != MAGIC) {
                throw new DataFormatException("not a zstd frame");
            }
            Frame frame = new Frame(src, at + 4, end);
            written = frame.decode(out, written);
            at = frame.at + (frame.checksum ? 4 : 0);
        }
        if (written != expected) {
            return Arrays.copyOf(out, written);
        }
        return out;
    }

    // ---- frame -------------------------------------------------------------

    private static final class Frame {

        private final byte[] src;
        private final int end;
        private int at;
        private boolean checksum;
        private int windowSize;

        /** The three most recent offsets a sequence may refer to instead of its own. */
        private int rep1 = 1;
        private int rep2 = 4;
        private int rep3 = 8;

        private FseTable literalTable;
        private FseTable matchTable;
        private FseTable offsetTable;
        private HuffmanTable huffman;

        Frame(byte[] src, int at, int end) throws DataFormatException {
            this.src = src;
            this.at = at;
            this.end = end;
            readHeader();
        }

        private void readHeader() throws DataFormatException {
            final int descriptor = u8(src, at++);
            final int contentSizeFlag = descriptor >>> 6;
            final boolean singleSegment = (descriptor & 0x20) != 0;
            this.checksum = (descriptor & 0x04) != 0;
            final int dictionaryFlag = descriptor & 0x03;

            if (!singleSegment) {
                final int window = u8(src, at++);
                final int exponent = 10 + (window >>> 3);
                final int mantissa = window & 0x07;
                final long base = 1L << exponent;
                windowSize = (int) Math.min(Integer.MAX_VALUE,
                                            base + (base / 8) * mantissa);
            }

            final int[] dictionaryBytes = {0, 1, 2, 4};
            at += dictionaryBytes[dictionaryFlag];

            int contentBytes = contentSizeFlag == 0 ? (singleSegment ? 1 : 0)
                             : 1 << contentSizeFlag;
            long contentSize = 0;
            for (int i = 0; i < contentBytes; i++) {
                contentSize |= ((long) u8(src, at + i)) << (8 * i);
            }
            if (contentBytes == 2) {
                contentSize += 256;
            }
            at += contentBytes;
            if (singleSegment) {
                windowSize = (int) Math.min(Integer.MAX_VALUE, contentSize);
            }
        }

        int decode(byte[] out, int written) throws DataFormatException {
            boolean last = false;
            while (!last && at < end) {
                final int header = u8(src, at) | (u8(src, at + 1) << 8)
                                 | (u8(src, at + 2) << 16);
                at += 3;
                last = (header & 1) != 0;
                final int type = (header >>> 1) & 0x03;
                final int size = header >>> 3;

                switch (type) {
                    case 0 -> {                       // raw
                        System.arraycopy(src, at, out, written, size);
                        written += size;
                        at += size;
                    }
                    case 1 -> {                       // one byte repeated
                        Arrays.fill(out, written, written + size, src[at]);
                        written += size;
                        at += 1;
                    }
                    case 2 -> written = compressedBlock(out, written, size);
                    default -> throw new DataFormatException("reserved block type");
                }
            }
            return written;
        }

        // ---- a compressed block: literals, then the sequences that place them --

        private byte[] literals = new byte[0];
        private int literalCount;

        private int compressedBlock(byte[] out, int written, int size)
                throws DataFormatException {
            final int blockEnd = at + size;
            readLiterals(blockEnd);
            return readSequences(out, written, blockEnd);
        }

        private void readLiterals(int blockEnd) throws DataFormatException {
            final int first = u8(src, at);
            final int type = first & 0x03;
            final int sizeFormat = (first >>> 2) & 0x03;

            int regenerated;
            int compressed = 0;
            int streams = 1;

            if (type <= 1) {                          // raw or one repeated byte
                switch (sizeFormat) {
                    case 0, 2 -> {
                        regenerated = first >>> 3;
                        at += 1;
                    }
                    case 1 -> {
                        regenerated = (first >>> 4) | (u8(src, at + 1) << 4);
                        at += 2;
                    }
                    default -> {
                        regenerated = (first >>> 4) | (u8(src, at + 1) << 4)
                                    | (u8(src, at + 2) << 12);
                        at += 3;
                    }
                }
                literals = grow(literals, regenerated);
                literalCount = regenerated;
                if (type == 0) {
                    System.arraycopy(src, at, literals, 0, regenerated);
                    at += regenerated;
                } else {
                    Arrays.fill(literals, 0, regenerated, src[at]);
                    at += 1;
                }
                return;
            }

            // Huffman coded, with the tree either included or carried over.
            switch (sizeFormat) {
                case 0, 1 -> {
                    final int value = first | (u8(src, at + 1) << 8)
                                    | (u8(src, at + 2) << 16);
                    regenerated = (value >>> 4) & 0x3FF;
                    compressed = (value >>> 14) & 0x3FF;
                    streams = sizeFormat == 0 ? 1 : 4;
                    at += 3;
                }
                case 2 -> {
                    final long value = (first & 0xFFL) | ((long) u8(src, at + 1) << 8)
                                     | ((long) u8(src, at + 2) << 16)
                                     | ((long) u8(src, at + 3) << 24);
                    regenerated = (int) ((value >>> 4) & 0x3FFF);
                    compressed = (int) ((value >>> 18) & 0x3FFF);
                    streams = 4;
                    at += 4;
                }
                default -> {
                    final long value = (first & 0xFFL) | ((long) u8(src, at + 1) << 8)
                                     | ((long) u8(src, at + 2) << 16)
                                     | ((long) u8(src, at + 3) << 24)
                                     | ((long) u8(src, at + 4) << 32);
                    regenerated = (int) ((value >>> 4) & 0x3FFFF);
                    compressed = (int) ((value >>> 22) & 0x3FFFF);
                    streams = 4;
                    at += 5;
                }
            }

            final int streamsAt = at;
            if (type == 2) {                          // the tree comes with the block
                huffman = HuffmanTable.read(src, at, blockEnd);
                at += huffman.headerBytes;
            } else if (huffman == null) {
                throw new DataFormatException("literals reuse a tree that was never sent");
            }

            literals = grow(literals, regenerated);
            literalCount = regenerated;
            final int payloadAt = at;
            final int payloadSize = compressed - (at - streamsAt);
            decodeHuffman(payloadAt, payloadSize, streams, regenerated);
            at = streamsAt + compressed;
        }

        private void decodeHuffman(int from, int size, int streams, int regenerated)
                throws DataFormatException {
            if (streams == 1) {
                huffman.decodeStream(src, from, from + size, literals, 0, regenerated);
                return;
            }
            // Four streams, each covering a quarter of the output; the first three
            // sizes are in a jump table, the fourth is what is left.
            final int s1 = u8(src, from) | (u8(src, from + 1) << 8);
            final int s2 = u8(src, from + 2) | (u8(src, from + 3) << 8);
            final int s3 = u8(src, from + 4) | (u8(src, from + 5) << 8);
            final int body = from + 6;
            final int s4 = size - 6 - s1 - s2 - s3;
            if (s4 < 0) {
                throw new DataFormatException("literal jump table is inconsistent");
            }
            final int quarter = (regenerated + 3) / 4;
            final int lastPart = regenerated - 3 * quarter;

            huffman.decodeStream(src, body, body + s1, literals, 0, quarter);
            huffman.decodeStream(src, body + s1, body + s1 + s2,
                                 literals, quarter, quarter);
            huffman.decodeStream(src, body + s1 + s2, body + s1 + s2 + s3,
                                 literals, 2 * quarter, quarter);
            huffman.decodeStream(src, body + s1 + s2 + s3, body + s1 + s2 + s3 + s4,
                                 literals, 3 * quarter, lastPart);
        }

        // ---- sequences ---------------------------------------------------------

        private static final int[] LITERAL_BASE = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
            8192, 16384, 32768, 65536};
        private static final int[] LITERAL_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16};
        private static final short[] LITERAL_DEFAULT = {
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1};

        private static final int[] MATCH_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
            4099, 8195, 16387, 32771, 65539};
        private static final int[] MATCH_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16};
        private static final short[] MATCH_DEFAULT = {
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            -1, -1, -1, -1, -1, -1, -1};

        private static final short[] OFFSET_DEFAULT = {
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};

        private int readSequences(byte[] out, int written, int blockEnd)
                throws DataFormatException {
            int count = u8(src, at++);
            if (count == 0) {
                System.arraycopy(literals, 0, out, written, literalCount);
                return written + literalCount;
            }
            if (count < 128) {
                // count is already right
            } else if (count < 255) {
                count = ((count - 128) << 8) + u8(src, at++);
            } else {
                count = u8(src, at) + (u8(src, at + 1) << 8) + 0x7F00;
                at += 2;
            }

            final int modes = u8(src, at++);
            literalTable = table((modes >>> 6) & 0x03, literalTable,
                                 LITERAL_DEFAULT, 6, 35);
            offsetTable = table((modes >>> 4) & 0x03, offsetTable,
                                OFFSET_DEFAULT, 5, 31);
            matchTable = table((modes >>> 2) & 0x03, matchTable,
                               MATCH_DEFAULT, 6, 52);

            BackwardBits bits = new BackwardBits(src, at, blockEnd);
            at = blockEnd;

            int literalState = bits.read(literalTable.accuracy);
            int offsetState = bits.read(offsetTable.accuracy);
            int matchState = bits.read(matchTable.accuracy);

            int literalAt = 0;
            for (int i = 0; i < count; i++) {
                final int offsetCode = offsetTable.symbol[offsetState];
                final int matchCode = matchTable.symbol[matchState];
                final int literalCode = literalTable.symbol[literalState];

                final int offsetValue = (1 << offsetCode) + bits.read(offsetCode);
                final int matchLength = MATCH_BASE[matchCode]
                                      + bits.read(MATCH_BITS[matchCode]);
                final int literalLength = LITERAL_BASE[literalCode]
                                        + bits.read(LITERAL_BITS[literalCode]);

                final int offset = resolveOffset(offsetValue, literalLength);

                System.arraycopy(literals, literalAt, out, written, literalLength);
                written += literalLength;
                literalAt += literalLength;

                // The match may overlap what it copies, so it goes byte by byte.
                int from = written - offset;
                if (from < 0) {
                    throw new DataFormatException("match reaches before the output");
                }
                for (int k = 0; k < matchLength; k++) {
                    out[written++] = out[from++];
                }

                if (i + 1 < count) {
                    literalState = literalTable.next(literalState, bits);
                    matchState = matchTable.next(matchState, bits);
                    offsetState = offsetTable.next(offsetState, bits);
                }
            }

            final int rest = literalCount - literalAt;
            if (rest > 0) {
                System.arraycopy(literals, literalAt, out, written, rest);
                written += rest;
            }
            return written;
        }

        /** Codes 1 to 3 name an earlier offset rather than carrying one. */
        private int resolveOffset(int value, int literalLength) {
            if (value > 3) {
                final int offset = value - 3;
                rep3 = rep2;
                rep2 = rep1;
                rep1 = offset;
                return offset;
            }
            int index = value;
            if (literalLength == 0) {
                index++;
            }
            int offset;
            if (index == 1) {
                offset = rep1;
            } else if (index == 2) {
                offset = rep2;
                rep2 = rep1;
                rep1 = offset;
            } else if (index == 3) {
                offset = rep3;
                rep3 = rep2;
                rep2 = rep1;
                rep1 = offset;
            } else {
                offset = rep1 - 1;
                rep3 = rep2;
                rep2 = rep1;
                rep1 = offset;
            }
            return offset;
        }

        private FseTable table(int mode, FseTable previous, short[] defaults,
                               int defaultAccuracy, int maxSymbol)
                throws DataFormatException {
            switch (mode) {
                case 0:
                    return FseTable.fromCounts(defaults, defaultAccuracy);
                case 1: {
                    final int symbol = u8(src, at++);
                    return FseTable.single(symbol);
                }
                case 2: {
                    FseTable built = FseTable.read(src, at, end, maxSymbol);
                    at += built.headerBytes;
                    return built;
                }
                default:
                    if (previous == null) {
                        throw new DataFormatException("sequence table repeats one never sent");
                    }
                    return previous;
            }
        }
    }

    // ---- FSE ---------------------------------------------------------------

    /**
     * A finite state entropy table: each state gives a symbol and how to reach
     * the next state, so the decoder walks states instead of reading codes.
     */
    private static final class FseTable {

        int accuracy;
        int[] symbol;
        int[] newState;
        int[] bitCount;
        int headerBytes;

        static FseTable single(int value) {
            FseTable t = new FseTable();
            t.accuracy = 0;
            t.symbol = new int[] {value};
            t.newState = new int[] {0};
            t.bitCount = new int[] {0};
            return t;
        }

        int next(int state, BackwardBits bits) {
            return newState[state] + bits.read(bitCount[state]);
        }

        static FseTable read(byte[] src, int from, int end, int maxSymbol)
                throws DataFormatException {
            ForwardBits bits = new ForwardBits(src, from, end);
            final int accuracy = 5 + bits.read(4);
            final int size = 1 << accuracy;

            short[] counts = new short[maxSymbol + 2];
            int remaining = size + 1;
            int threshold = size;
            int width = accuracy + 1;
            int symbol = 0;
            boolean previousWasZero = false;

            while (remaining > 1 && symbol <= maxSymbol) {
                if (previousWasZero) {
                    // A run of absent symbols, three at a time while the pair is 11.
                    while (bits.peek(2) == 3) {
                        bits.skip(2);
                        symbol += 3;
                    }
                    symbol += bits.read(2);
                    previousWasZero = false;
                    continue;
                }
                // The field narrows as the budget shrinks, so small counts near
                // the end of the table cost fewer bits.
                final int low = (2 * threshold - 1) - remaining;
                final int value = bits.peek(width);
                int count;
                if ((value & (threshold - 1)) < low) {
                    count = value & (threshold - 1);
                    bits.skip(width - 1);
                } else {
                    count = value & (2 * threshold - 1);
                    if (count >= threshold) {
                        count -= low;
                    }
                    bits.skip(width);
                }
                count--;
                remaining -= count < 0 ? -count : count;
                counts[symbol] = (short) count;
                previousWasZero = count == 0;
                symbol++;
                while (remaining < threshold) {
                    width--;
                    threshold >>= 1;
                }
            }

            FseTable table = build(counts, symbol, accuracy);
            table.headerBytes = bits.bytesUsed();
            return table;
        }

        static FseTable fromCounts(short[] counts, int accuracy)
                throws DataFormatException {
            return build(counts, counts.length, accuracy);
        }

        private static FseTable build(short[] counts, int symbols, int accuracy)
                throws DataFormatException {
            final int size = 1 << accuracy;
            FseTable t = new FseTable();
            t.accuracy = accuracy;
            t.symbol = new int[size];
            t.newState = new int[size];
            t.bitCount = new int[size];

            int[] next = new int[symbols];
            int highest = size - 1;

            // The -1 counts sit at the top of the table, in symbol order.
            for (int s = 0; s < symbols; s++) {
                if (counts[s] == -1) {
                    t.symbol[highest--] = s;
                    next[s] = 1;
                }
            }

            // Everything else is spread with the step the format prescribes.
            final int step = (size >>> 1) + (size >>> 3) + 3;
            final int mask = size - 1;
            int position = 0;
            for (int s = 0; s < symbols; s++) {
                for (int i = 0; i < counts[s]; i++) {
                    t.symbol[position] = s;
                    position = (position + step) & mask;
                    while (position > highest) {
                        position = (position + step) & mask;
                    }
                }
            }
            if (position != 0) {
                throw new DataFormatException("fse table did not fill exactly");
            }

            for (int s = 0; s < symbols; s++) {
                if (counts[s] > 0) {
                    next[s] = counts[s];
                }
            }
            for (int state = 0; state < size; state++) {
                final int s = t.symbol[state];
                final int slot = next[s]++;
                final int bits = accuracy - (31 - Integer.numberOfLeadingZeros(slot));
                t.bitCount[state] = bits;
                t.newState[state] = (slot << bits) - size;
            }
            return t;
        }
    }

    // ---- Huffman -----------------------------------------------------------

    private static final class HuffmanTable {

        int maxBits;
        byte[] symbol;
        byte[] bits;
        int headerBytes;

        static HuffmanTable read(byte[] src, int from, int end)
                throws DataFormatException {
            final int header = u8(src, from);
            int[] weights;
            int used;

            if (header >= 128) {
                final int count = header - 127;
                weights = new int[count];
                for (int i = 0; i < count; i++) {
                    final int b = u8(src, from + 1 + i / 2);
                    weights[i] = (i % 2 == 0) ? (b >>> 4) : (b & 0x0F);
                }
                used = 1 + (count + 1) / 2;
            } else {
                // The weights are themselves FSE coded, over two interleaved states.
                FseTable table = FseTable.read(src, from + 1, from + 1 + header, 255);
                weights = decodeWeights(src, from + 1, from + 1 + header,
                                        table.headerBytes, table);
                used = 1 + header;
            }

            HuffmanTable t = new HuffmanTable();
            t.headerBytes = used;
            t.finish(weights);
            return t;
        }

        private static int[] decodeWeights(byte[] src, int from, int end,
                                           int tableBytes, FseTable table)
                throws DataFormatException {
            BackwardBits bits = new BackwardBits(src, from + tableBytes, end);
            int a = bits.read(table.accuracy);
            int b = bits.read(table.accuracy);

            int[] out = new int[256];
            int count = 0;
            // Two states take turns; the stream is over once a read runs past
            // its start, and the state that had not spoken yet gets the last say.
            while (true) {
                if (count >= 254) {
                    throw new DataFormatException("too many huffman weights");
                }
                out[count++] = table.symbol[a];
                a = table.next(a, bits);
                if (bits.overflowed()) {
                    out[count++] = table.symbol[b];
                    break;
                }
                out[count++] = table.symbol[b];
                b = table.next(b, bits);
                if (bits.overflowed()) {
                    out[count++] = table.symbol[a];
                    break;
                }
            }
            return Arrays.copyOf(out, count);
        }

        /** Turns weights into code lengths and lays out the lookup table. */
        private void finish(int[] weights) throws DataFormatException {
            int total = 0;
            for (int w : weights) {
                if (w > 0) {
                    total += 1 << (w - 1);
                }
            }
            if (total == 0) {
                throw new DataFormatException("empty huffman tree");
            }
            maxBits = 32 - Integer.numberOfLeadingZeros(total);
            final int size = 1 << maxBits;
            final int leftover = size - total;
            if (leftover == 0 || (leftover & (leftover - 1)) != 0) {
                throw new DataFormatException("huffman weights do not close the tree");
            }
            final int lastWeight = Integer.numberOfTrailingZeros(leftover) + 1;

            int[] all = Arrays.copyOf(weights, weights.length + 1);
            all[weights.length] = lastWeight;

            symbol = new byte[size];
            bits = new byte[size];

            // Symbols go in by increasing weight, so the longest codes take the
            // low end of the table and the shortest the high end.
            int position = 0;
            for (int weight = 1; weight <= maxBits; weight++) {
                final int length = maxBits + 1 - weight;
                final int span = 1 << (weight - 1);
                for (int s = 0; s < all.length; s++) {
                    if (all[s] != weight) {
                        continue;
                    }
                    Arrays.fill(symbol, position, position + span, (byte) s);
                    Arrays.fill(bits, position, position + span, (byte) length);
                    position += span;
                }
            }
            if (position != size) {
                throw new DataFormatException("huffman table did not fill exactly");
            }
        }

        void decodeStream(byte[] src, int from, int to, byte[] out, int at, int count)
                throws DataFormatException {
            BackwardBits bits = new BackwardBits(src, from, to);
            for (int i = 0; i < count; i++) {
                final int index = bits.peek(maxBits);
                out[at + i] = symbol[index];
                bits.skip(this.bits[index]);
            }
        }
    }

    // ---- bit readers -------------------------------------------------------

    /**
     * Reads bits from the end of the buffer towards its start, most significant
     * first, which is how the entropy streams are written.
     */
    private static final class BackwardBits {

        private final byte[] src;
        private final int start;
        private long window;
        private int available;
        private int at;

        BackwardBits(byte[] src, int start, int end) throws DataFormatException {
            this.src = src;
            this.start = start;
            this.at = end - 1;
            while (at >= start && src[at] == 0) {
                at--;
            }
            if (at < start) {
                throw new DataFormatException("empty entropy stream");
            }
            final int last = src[at] & 0xFF;
            // The highest set byte marks where the padding ends.
            final int padding = Integer.numberOfLeadingZeros(last) - 24 + 1;
            window = last;
            available = 8 - padding;
            at--;
            fill();
        }

        private void fill() {
            while (available <= 56 && at >= start) {
                window = (window << 8) | (src[at] & 0xFF);
                available += 8;
                at--;
            }
        }

        int peek(int n) {
            if (n == 0) {
                return 0;
            }
            if (available < n) {
                fill();
            }
            final long mask = (1L << n) - 1;
            final int shift = available - n;
            // Reading past the end yields zeros, which is what the padding stands for.
            return (int) (shift >= 0 ? (window >>> shift) & mask
                                     : (window << -shift) & mask);
        }

        void skip(int n) {
            available -= n;
            fill();
        }

        int read(int n) {
            final int value = peek(n);
            skip(n);
            return value;
        }

        /** True once more bits have been taken than the stream held. */
        boolean overflowed() {
            return available < 0;
        }
    }

    /** Reads bits from the start of the buffer, least significant first. */
    private static final class ForwardBits {

        private final byte[] src;
        private final int start;
        private final int end;
        private int at;
        private int consumed;

        ForwardBits(byte[] src, int start, int end) {
            this.src = src;
            this.start = start;
            this.end = end;
            this.at = start;
        }

        int peek(int n) {
            long window = 0;
            for (int i = 0; i < 8; i++) {
                final int index = at + i;
                final long b = index < end ? (src[index] & 0xFFL) : 0L;
                window |= b << (8 * i);
            }
            return (int) ((window >>> consumed) & ((1L << n) - 1));
        }

        void skip(int n) {
            consumed += n;
            at += consumed >>> 3;
            consumed &= 7;
        }

        int read(int n) {
            final int value = peek(n);
            skip(n);
            return value;
        }

        int bytesUsed() {
            return at - start + (consumed > 0 ? 1 : 0);
        }
    }

    // ---- small helpers -----------------------------------------------------

    private static byte[] grow(byte[] buffer, int needed) {
        return buffer.length >= needed ? buffer : new byte[needed];
    }

    private static int u8(byte[] src, int at) {
        return src[at] & 0xFF;
    }

    private static int le32(byte[] src, int at) {
        return (src[at] & 0xFF) | ((src[at + 1] & 0xFF) << 8)
             | ((src[at + 2] & 0xFF) << 16) | ((src[at + 3] & 0xFF) << 24);
    }
}
