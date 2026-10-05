package com.sphere.core.hepmc3;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Compressed input and output, as HepMC3's CompressedIO (bxzstr) gives them:
 * the type found from the first bytes (gzip or zlib, bzip2, xz, zstd), a
 * stream that decompresses on reading, and gzip on writing.
 *
 * <p>zlib/gzip are the JDK's, zstd Sphere's own decoder; bzip2 and xz have
 * no decoder here and are refused with a message saying so.
 */
public enum Compression {
    PLAINTEXT, Z, LZMA, BZ2, ZSTD;

    /** The compressions this port can read. */
    public static final Compression[] SUPPORTED = {Z, ZSTD};

    /** The four HepMC3 knows of. */
    public static final Compression[] KNOWN = {Z, LZMA, BZ2, ZSTD};

    /** HepMC3's name of it: "z", "lzma", "bz2", "zstd", "plaintext". */
    public String label() {
        return switch (this) {
            case Z -> "z";
            case LZMA -> "lzma";
            case BZ2 -> "bz2";
            case ZSTD -> "zstd";
            default -> "plaintext";
        };
    }

    public boolean readable() {
        return this == PLAINTEXT || this == Z || this == ZSTD;
    }

    /** detect_type: the compression the first bytes show (at least two needed, six looked at). */
    public static Compression detect(byte[] b, int n) {
        final int b0 = n > 0 ? b[0] & 0xFF : 0;
        final int b1 = n > 1 ? b[1] & 0xFF : 0;
        final boolean gzip = b0 == 0x1F && b1 == 0x8B;
        final boolean zlib = b0 == 0x78 && (b1 == 0x01 || b1 == 0x9C || b1 == 0xDA);
        if (n >= 2 && (gzip || zlib)) return Z;
        final int b2 = n > 2 ? b[2] & 0xFF : 0;
        if (n >= 3 && b0 == 0x42 && b1 == 0x5A && b2 == 0x68) return BZ2;
        final int b3 = n > 3 ? b[3] & 0xFF : 0;
        final int b4 = n > 4 ? b[4] & 0xFF : 0;
        final int b5 = n > 5 ? b[5] & 0xFF : 0;
        if (n >= 5 && b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58 && b4 == 0x5A && b5 == 0x00) return LZMA;
        if (n >= 4 && b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD) return ZSTD;
        return PLAINTEXT;
    }

    /** The compression of a file, from its first six bytes. */
    public static Compression detect(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] head = in.readNBytes(6);
            // C++ copies the first line through snprintf(buf, 6, "%s"): the sixth byte is always '\0'
            final byte[] six = java.util.Arrays.copyOf(head, 6);
            six[5] = 0;
            return detect(six, Math.max(head.length, 6));
        } catch (IOException e) {
            return PLAINTEXT;
        }
    }

    /** An input stream that reads the file decompressed, whatever its compression; plain text as it is. */
    public static InputStream open(Path file) throws IOException {
        final InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        return decompressing(raw);
    }

    /** Wraps a stream so that it reads decompressed (the type looked at from its first bytes). */
    public static InputStream decompressing(InputStream raw) throws IOException {
        final InputStream in = raw.markSupported() ? raw : new BufferedInputStream(raw, 1 << 16);
        in.mark(8);
        final byte[] head = in.readNBytes(6);
        in.reset();
        final Compression c = detect(head, head.length);
        return switch (c) {
            case Z -> (head.length > 1 && (head[0] & 0xFF) == 0x1F) ? new GZIPInputStream(in, 1 << 16) : new InflaterInputStream(in);
            case ZSTD -> new ByteArrayInputStream(zstd(in.readAllBytes()));
            case BZ2, LZMA -> throw new IOException("the input is compressed with " + c.label()
                + ", which this Java port of HepMC3 cannot decompress; decompress it first (bunzip2, unxz)");
            default -> in;
        };
    }

    /** An output stream compressing with this type (gzip for Z; plain text for PLAINTEXT). */
    public OutputStream compressing(OutputStream out) throws IOException {
        return switch (this) {
            case Z -> new GZIPOutputStream(out, 1 << 16);
            case PLAINTEXT -> out;
            default -> throw new IOException("writing " + label() + " is not supported by this Java port of HepMC3 (z is)");
        };
    }

    /** Decodes zstd frames whole, sizing the output from the frame headers when they say it. */
    static byte[] zstd(byte[] src) throws IOException {
        long declared = 0;
        boolean known = true;
        int at = 0;
        // read the content size of the first frame; later frames are rare in event files
        if (src.length >= 6) {
            final int descriptor = src[4] & 0xFF;
            final int flag = descriptor >>> 6;
            final boolean single = (descriptor & 0x20) != 0;
            int pos = 5 + (single ? 0 : 1) + new int[]{0, 1, 2, 4}[descriptor & 3];
            final int bytes = flag == 0 ? (single ? 1 : 0) : 1 << flag;
            if (bytes == 0) known = false;
            for (int i = 0; i < bytes && pos + i < src.length; i++) declared |= ((long) (src[pos + i] & 0xFF)) << (8 * i);
            if (bytes == 2) declared += 256;
        } else {
            known = false;
        }
        long guess = known ? declared : Math.max(1L << 20, src.length * 8L);
        while (true) {
            if (guess > Integer.MAX_VALUE - 16) throw new IOException("zstd input too large to decode in memory");
            try {
                return com.sphere.components.rootview.ZstdBlock.decode(src, at, src.length, (int) guess);
            } catch (IndexOutOfBoundsException tooSmall) {
                if (known && declared > 0 && guess == declared) {
                    // several frames: the first one's size was not the whole
                    guess = Math.max(guess * 2, src.length * 8L);
                } else {
                    guess *= 2;
                }
            } catch (java.util.zip.DataFormatException e) {
                throw new IOException("corrupt zstd input: " + e.getMessage(), e);
            }
        }
    }
}
