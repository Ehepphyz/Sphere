package com.sphere.core.rootlab.suites;

import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.XxHash64;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The five kinds of compressed block a ROOT file can hold (core/zip,
 * core/lzma, core/lz4, core/zstd and the old ROOT routine), decoded by
 * Sphere's own Java decoders from blocks made by the reference
 * implementations: zlib, liblzma (with R__zipLZMA's settings), lz4 1.10 and
 * zstd 1.5.7. The payload is 720 kB of text and big-endian numbers, so every
 * part of each format is used (matches, Huffman and FSE tables, repeat
 * offsets). Damaged blocks must be refused, as ROOT refuses them, not
 * returned as plausible numbers.
 */
public final class CompressionSuite implements LabSuite {

    /** SHA-256 of payload.bin, fixed when the fixtures were made. */
    private static final String PAYLOAD_SHA256 = "7f972ba0e66664aafe2d5b3d7d4301601fd84b9976957edb82d68d941a1b916c";

    @Override
    public String id() {
        return "compression";
    }

    @Override
    public String title() {
        return "Compression algorithms of ROOT files";
    }

    @Override
    public String origin() {
        return "root/core/zip (RZip.cxx), core/lzma, core/lz4, core/zstd; blocks made by zlib, liblzma, lz4 1.10, zstd 1.5.7";
    }

    @Override
    public String description() {
        return "zlib, the old ROOT deflate, LZMA (XZ), LZ4 with its XXH64 checksum and Zstandard, decoded in "
             + "pure Java and compared byte for byte; damaged blocks must be detected.";
    }

    @Override
    public List<LabTest> tests() {
        return List.of(
            new LabTest("xxh64", "XXH64 reference vectors (LZ4 and zstd checksums)", CompressionSuite::xxh64),
            new LabTest("zl", "ZL: zlib (algorithm 1, ROOT's default)", ctx -> block(ctx, "block-zlib.bin", "ZL")),
            new LabTest("cs", "CS: the old ROOT routine (raw deflate)", ctx -> block(ctx, "block-cs.bin", "CS")),
            new LabTest("xz", "XZ: LZMA2 in an xz stream, CRC32 verified", ctx -> block(ctx, "block-xz.bin", "XZ")),
            new LabTest("l4", "L4: LZ4 (HC level 9) behind its XXH64 checksum", CompressionSuite::lz4),
            new LabTest("zs", "ZS: Zstandard frame (level 5)", ctx -> block(ctx, "block-zstd.bin", "ZS")),
            new LabTest("zsc", "ZS: Zstandard frame with content checksum", ctx -> block(ctx, "block-zstd-check.bin", "ZS")),
            new LabTest("damage", "Damaged blocks are refused, not returned", CompressionSuite::damage));
    }

    private static byte[] payload() throws Exception {
        final byte[] p = Files.readAllBytes(LabContext.resource("payload.bin"));
        final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p));
        if (!sha.equals(PAYLOAD_SHA256)) {
            throw new LabFailure("payload.bin is not the file the fixtures were made from (" + sha + ")");
        }
        return p;
    }

    private static void xxh64(LabContext ctx) throws Exception {
        final String[][] vectors = {
            {"", "ef46db3751d8e999"},
            {"abc", "44bc2cf5ad770999"},
            {"Nobody inspects the spammish repetition", "fbcea83c8a378bf1"},
        };
        for (String[] v : vectors) {
            final byte[] in = v[0].getBytes(StandardCharsets.US_ASCII);
            final String got = String.format("%016x", XxHash64.hash(in, 0, in.length, 0));
            ctx.detail("XXH64(\"" + v[0] + "\") = " + got);
            ctx.check(got.equals(v[1]), "XXH64(\"" + v[0] + "\") = " + got + ", expected " + v[1]);
        }
    }

    private static int u24(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16);
    }

    /** Decodes one ROOT block (9-byte header, then the compressed bytes) the way RootFile does. */
    static byte[] unpack(byte[] block) throws IOException {
        final String tag = new String(block, 0, 2, StandardCharsets.US_ASCII);
        final int packed = u24(block, 3);
        final int unpacked = u24(block, 6);
        if (packed + 9 != block.length) {
            throw new IOException("block header says " + packed + " bytes, the block holds " + (block.length - 9));
        }
        final byte[] out = new byte[unpacked];
        final int n = RootFile.unpackBlock(tag, Arrays.copyOfRange(block, 9, block.length), unpacked, out, 0,
                                           unpacked, 0);
        if (n != unpacked) {
            throw new IOException("decoded " + n + " bytes, the header says " + unpacked);
        }
        return out;
    }

    private static void block(LabContext ctx, String fixture, String tag) throws Exception {
        final byte[] want = payload();
        final byte[] block = Files.readAllBytes(LabContext.resource(fixture));
        ctx.check(new String(block, 0, 2, StandardCharsets.US_ASCII).equals(tag), fixture + " is not a " + tag + " block");
        final long t0 = System.nanoTime();
        final byte[] got = unpack(block);
        final double ms = (System.nanoTime() - t0) / 1e6;
        ctx.detail(String.format(Locale.ROOT, "%s: %d bytes -> %d bytes (ratio %.2f) in %.1f ms", tag,
            block.length - 9, got.length, (double) got.length / (block.length - 9), ms));
        compare(want, got);
    }

    private static void compare(byte[] want, byte[] got) throws LabFailure {
        if (!Arrays.equals(want, got)) {
            int at = Arrays.mismatch(want, got);
            throw new LabFailure("decoded bytes differ from the payload, first at offset " + at
                                 + " (" + got.length + " bytes decoded, " + want.length + " expected)");
        }
    }

    /**
     * The LZ4 block comes out of a frame made by the lz4 tool; it is wrapped
     * the way R__zipLZ4 wraps its output: tag, version, sizes, then the XXH64
     * of the compressed bytes, big-endian, then the bytes.
     */
    static byte[] rootLz4Block(byte[] raw, int unpacked) {
        final byte[] block = new byte[9 + 8 + raw.length];
        block[0] = 'L';
        block[1] = '4';
        block[2] = 1;
        final int packed = 8 + raw.length;
        block[3] = (byte) packed;
        block[4] = (byte) (packed >> 8);
        block[5] = (byte) (packed >> 16);
        block[6] = (byte) unpacked;
        block[7] = (byte) (unpacked >> 8);
        block[8] = (byte) (unpacked >> 16);
        final long h = XxHash64.hash(raw, 0, raw.length, 0);
        for (int i = 0; i < 8; i++) {
            block[9 + i] = (byte) (h >>> (56 - 8 * i));
        }
        System.arraycopy(raw, 0, block, 17, raw.length);
        return block;
    }

    private static byte[] lz4Raw() throws Exception {
        final byte[] f = Files.readAllBytes(LabContext.resource("lz4-blocks.bin"));
        // the fixture holds the blocks of the frame: {uncompressed flag, length, bytes}
        final int flag = (f[0] & 0xFF) | ((f[1] & 0xFF) << 8);
        final int length = (f[4] & 0xFF) | ((f[5] & 0xFF) << 8) | ((f[6] & 0xFF) << 16) | ((f[7] & 0xFF) << 24);
        if (flag != 0 || 8 + length != f.length) {
            throw new LabFailure("lz4-blocks.bin should hold one compressed block");
        }
        return Arrays.copyOfRange(f, 8, f.length);
    }

    private static void lz4(LabContext ctx) throws Exception {
        final byte[] want = payload();
        final byte[] raw = lz4Raw();
        final byte[] block = rootLz4Block(raw, want.length);
        final long t0 = System.nanoTime();
        final byte[] got = unpack(block);
        ctx.detail(String.format(Locale.ROOT, "L4: %d bytes -> %d bytes in %.1f ms, checksum %016x",
            raw.length, got.length, (System.nanoTime() - t0) / 1e6, XxHash64.hash(raw, 0, raw.length, 0)));
        compare(want, got);
    }

    private static void damage(LabContext ctx) throws Exception {
        final String[][] cases = {
            {"block-zlib.bin", "ZL (Adler-32)"},
            {"block-xz.bin", "XZ (CRC32)"},
            {"block-zstd-check.bin", "ZS (XXH64 content checksum)"},
            {"lz4", "L4 (XXH64 of the block)"},
        };
        final byte[] want = payload();
        for (String[] c : cases) {
            byte[] block = c[0].equals("lz4") ? rootLz4Block(lz4Raw(), want.length)
                                              : Files.readAllBytes(LabContext.resource(c[0]));
            // one bit flipped in the middle of the compressed bytes
            final int at = 9 + (block.length - 9) / 2;
            block = block.clone();
            block[at] ^= 0x04;
            try {
                final byte[] got = unpack(block);
                if (!Arrays.equals(got, want)) {
                    throw new LabFailure(c[1] + ": a damaged block was decoded to wrong data without an error");
                }
                ctx.detail(c[1] + ": the flipped bit happened to be harmless");
            } catch (IOException refused) {
                ctx.detail(c[1] + ": refused -- " + refused.getMessage());
            }
        }
    }
}
