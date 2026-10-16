package com.sphere.components.rootview;

/**
 * XXH64, the hash ROOT stores in front of every LZ4 block it writes.
 *
 * R__unzipLZ4 recomputes it before decompressing and refuses the block when it
 * differs, so a reader that skips it would hand back corrupted baskets that
 * ROOT itself would not. The value is stored big-endian (XXH64_canonical_t).
 */
public final class XxHash64 {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private XxHash64() {
    }

    public static long hash(byte[] data, int offset, int length, long seed) {
        final int end = offset + length;
        int p = offset;
        long h;
        if (length >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            final int limit = end - 32;
            do {
                v1 = round(v1, le64(data, p));
                v2 = round(v2, le64(data, p + 8));
                v3 = round(v3, le64(data, p + 16));
                v4 = round(v4, le64(data, p + 24));
                p += 32;
            } while (p <= limit);
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
              + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = merge(h, v1);
            h = merge(h, v2);
            h = merge(h, v3);
            h = merge(h, v4);
        } else {
            h = seed + P5;
        }
        h += length;
        while (p + 8 <= end) {
            h ^= round(0, le64(data, p));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h ^= (le32(data, p) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h ^= (data[p] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            p++;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    /** The checksum as ROOT writes it: eight bytes, most significant first. */
    public static long readCanonical(byte[] data, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[at + i] & 0xFFL);
        }
        return v;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        return acc * P1;
    }

    private static long merge(long acc, long value) {
        acc ^= round(0, value);
        return acc * P1 + P4;
    }

    private static long le64(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16)
             | ((b[p + 3] & 0xFFL) << 24) | ((b[p + 4] & 0xFFL) << 32) | ((b[p + 5] & 0xFFL) << 40)
             | ((b[p + 6] & 0xFFL) << 48) | ((b[p + 7] & 0xFFL) << 56);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
