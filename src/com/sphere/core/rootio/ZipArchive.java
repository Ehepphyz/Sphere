package com.sphere.core.rootio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The directory of a zip archive, read through a channel (local or HTTP) so
 * that a ROOT file stored in it can be read in place, as TZIPFile does. Only
 * stored members can be: a compressed one has no byte at a fixed offset.
 * ZIP64 archives are understood.
 */
public final class ZipArchive {

    /** One member: its name, how it is stored, and where its bytes start. */
    public record Member(String name, int method, long size, long dataStart) {
    }

    private ZipArchive() {
    }

    public static List<Member> members(SeekableByteChannel ch, long fileSize) throws IOException {
        final int tail = (int) Math.min(fileSize, 65_557);
        final ByteBuffer end = read(ch, fileSize - tail, tail);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (end.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("not a zip archive (no end of central directory)");
        }
        long entries = end.getShort(eocd + 10) & 0xFFFF;
        long cdSize = end.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = end.getInt(eocd + 16) & 0xFFFFFFFFL;
        if ((entries == 0xFFFF || cdOffset == 0xFFFFFFFFL) && eocd >= 20 && end.getInt(eocd - 20) == 0x07064b50) {
            final long zip64At = end.getLong(eocd - 20 + 8);
            final ByteBuffer z = read(ch, zip64At, 56);
            if (z.getInt(0) != 0x06064b50) {
                throw new IOException("zip64 end of central directory is missing");
            }
            entries = z.getLong(32);
            cdSize = z.getLong(40);
            cdOffset = z.getLong(48);
        }
        if (cdSize > 64L * 1024 * 1024) {
            throw new IOException("zip central directory is implausibly large");
        }
        final ByteBuffer cd = read(ch, cdOffset, (int) cdSize);
        final List<Member> out = new ArrayList<>();
        int p = 0;
        for (long e = 0; e < entries; e++) {
            if (cd.getInt(p) != 0x02014b50) {
                throw new IOException("zip central directory entry " + e + " is damaged");
            }
            final int method = cd.getShort(p + 10) & 0xFFFF;
            long csize = cd.getInt(p + 20) & 0xFFFFFFFFL;
            long usize = cd.getInt(p + 24) & 0xFFFFFFFFL;
            final int nameLen = cd.getShort(p + 28) & 0xFFFF;
            final int extraLen = cd.getShort(p + 30) & 0xFFFF;
            final int commentLen = cd.getShort(p + 32) & 0xFFFF;
            long localOffset = cd.getInt(p + 42) & 0xFFFFFFFFL;
            final byte[] nameBytes = new byte[nameLen];
            cd.get(p + 46, nameBytes);
            // ZIP64 extra field: the sizes and offset that did not fit
            int x = p + 46 + nameLen;
            final int xEnd = x + extraLen;
            while (x + 4 <= xEnd) {
                final int id = cd.getShort(x) & 0xFFFF;
                final int len = cd.getShort(x + 2) & 0xFFFF;
                if (id == 0x0001) {
                    int q = x + 4;
                    if (usize == 0xFFFFFFFFL) {
                        usize = cd.getLong(q);
                        q += 8;
                    }
                    if (csize == 0xFFFFFFFFL) {
                        csize = cd.getLong(q);
                        q += 8;
                    }
                    if (localOffset == 0xFFFFFFFFL) {
                        localOffset = cd.getLong(q);
                    }
                }
                x += 4 + len;
            }
            final ByteBuffer local = read(ch, localOffset, 30);
            if (local.getInt(0) != 0x04034b50) {
                throw new IOException("zip local header of entry " + e + " is damaged");
            }
            final long dataStart = localOffset + 30 + (local.getShort(26) & 0xFFFF) + (local.getShort(28) & 0xFFFF);
            out.add(new Member(new String(nameBytes, StandardCharsets.UTF_8), method,
                               method == 0 ? usize : csize, dataStart));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    /** The member to open: by name, else by index, else the first ROOT file. */
    public static Member find(SeekableByteChannel ch, long fileSize, String name, int index) throws IOException {
        final List<Member> all = members(ch, fileSize);
        Member chosen = null;
        if (name != null) {
            for (Member m : all) {
                if (m.name().equals(name) || m.name().endsWith("/" + name)) {
                    chosen = m;
                    break;
                }
            }
            if (chosen == null) {
                throw new IOException("the archive has no member " + name);
            }
        } else if (index >= 0) {
            if (index >= all.size()) {
                throw new IOException("the archive has " + all.size() + " members, not " + (index + 1));
            }
            chosen = all.get(index);
        } else {
            for (Member m : all) {
                if (m.name().toLowerCase(java.util.Locale.ROOT).endsWith(".root")) {
                    chosen = m;
                    break;
                }
            }
            if (chosen == null) {
                throw new IOException("the archive holds no .root file");
            }
        }
        if (chosen.method() != 0) {
            throw new IOException(chosen.name() + " is compressed inside the archive (method "
                                  + chosen.method() + "); ROOT, like Sphere, reads only stored members in place");
        }
        return chosen;
    }

    private static ByteBuffer read(SeekableByteChannel ch, long at, int length) throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        synchronized (ch) {
            ch.position(at);
            while (b.hasRemaining()) {
                if (ch.read(b) < 0) {
                    throw new IOException("zip archive ends early");
                }
            }
        }
        return b.flip();
    }
}
