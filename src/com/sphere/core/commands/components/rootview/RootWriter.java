package com.sphere.components.rootview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a .root file made of records copied from another one.
 *
 * The bytes of an object are never touched: a record is copied as it sits on
 * disk, compressed or not, and only its key header is rewritten. That is what
 * makes renaming, dropping and extracting objects safe without a full encoder.
 */
public final class RootWriter {

    private static final int BEGIN = 100;
    private static final long LIMIT = 0x7FFFFFF0L;

    /** One object, with the bytes it occupies in the file it came from. */
    public static final class Entry {
        public String name;
        public String title;
        public String className;
        public byte[] stored;
        public int objlen;
        public int datime;
        public short cycle = 1;

        public Entry(String name, String title, String className,
                     byte[] stored, int objlen, int datime) {
            this.name = name;
            this.title = title;
            this.className = className;
            this.stored = stored;
            this.objlen = objlen;
            this.datime = datime;
        }
    }

    /** A directory holding objects and other directories. */
    public static final class Dir {
        public String name;
        public String title;
        public final List<Entry> objects = new ArrayList<>();
        public final List<Dir> directories = new ArrayList<>();

        public Dir(String name, String title) {
            this.name = name == null ? "" : name;
            this.title = title == null ? "" : title;
        }

        public int total() {
            int count = objects.size();
            for (Dir d : directories) {
                count += d.total();
            }
            return count;
        }
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int stamp = packNow();

    private RootWriter() {
    }

    /**
     * Builds the file at `target` from `top`.
     *
     * `streamerInfo` is the record ROOT stores to describe the classes it wrote;
     * carrying it over is what keeps the result readable by ROOT itself.
     */
    public static void write(Path target, Dir top, int formatVersion, int compress,
                             byte[] streamerInfoRecord, int streamerInfoObjlen)
            throws IOException {
        RootWriter writer = new RootWriter();
        writer.build(top, formatVersion, compress, streamerInfoRecord, streamerInfoObjlen);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temporary, writer.out.toByteArray());
        Files.move(temporary, target,
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void build(Dir top, int formatVersion, int compress,
                       byte[] streamerInfoRecord, int streamerInfoObjlen)
            throws IOException {
        pad(BEGIN);

        // The first key names the file and points at its key table.
        final String fileName = top.name;
        byte[] namePart = concat(string(fileName), string(top.title));
        final int topKeyLen = keyLength("TFile", fileName, top.title);
        final int topPayload = namePart.length + 30;
        writeKeyHeader(topKeyLen + topPayload, topPayload, topKeyLen, (short) 1,
                       BEGIN, 0, "TFile", fileName, top.title);
        final int topRecordAt = out.size() + namePart.length;
        write(namePart);
        pad(out.size() + 30);

        int streamerAt = 0;
        int streamerBytes = 0;
        if (streamerInfoRecord != null && streamerInfoRecord.length > 0) {
            streamerAt = out.size();
            final int keyLen = keyLength("TList", "StreamerInfo",
                                         "Doubly linked list");
            streamerBytes = keyLen + streamerInfoRecord.length;
            writeKeyHeader(streamerBytes, streamerInfoObjlen, keyLen, (short) 1,
                           streamerAt, BEGIN, "TList", "StreamerInfo",
                           "Doubly linked list");
            write(streamerInfoRecord);
        }

        Placed topTable = emit(top, BEGIN, 0);
        patchRecord(topRecordAt, topTable, topKeyLen, BEGIN, 0);

        final int end = out.size();
        if (end < 0 || end > LIMIT) {
            throw new IOException("the result would pass the two gigabyte limit "
                                  + "this writer supports");
        }
        writeHeader(formatVersion, end, topKeyLen, compress, streamerAt, streamerBytes);
    }

    /** Where a directory's key table landed, and how big it is. */
    private record Placed(int at, int nbytes) {
    }

    private Placed emit(Dir dir, int dirKeyAt, int parentAt) throws IOException {
        List<byte[]> headers = new ArrayList<>();

        for (Entry entry : dir.objects) {
            final int at = out.size();
            final int keyLen = keyLength(entry.className, entry.name, entry.title);
            final int nbytes = keyLen + entry.stored.length;
            byte[] header = keyHeader(nbytes, entry.objlen, keyLen, entry.cycle,
                                      at, dirKeyAt, entry.className, entry.name,
                                      entry.title, entry.datime);
            write(header);
            write(entry.stored);
            headers.add(header);
        }

        List<int[]> pending = new ArrayList<>();   // {directory key at, record at}
        for (Dir child : dir.directories) {
            final int at = out.size();
            final int keyLen = keyLength("TDirectoryFile", child.name, child.title);
            byte[] header = keyHeader(keyLen + 30, 30, keyLen, (short) 1, at,
                                      dirKeyAt, "TDirectoryFile", child.name,
                                      child.title, stamp);
            write(header);
            final int recordAt = out.size();
            pad(out.size() + 30);
            headers.add(header);
            pending.add(new int[] {at, recordAt});
        }

        for (int i = 0; i < dir.directories.size(); i++) {
            final int[] where = pending.get(i);
            Placed table = emit(dir.directories.get(i), where[0], dirKeyAt);
            final int keyLen = keyLength("TDirectoryFile",
                                         dir.directories.get(i).name,
                                         dir.directories.get(i).title);
            patchRecord(where[1], table, keyLen, where[0], dirKeyAt);
        }

        final int tableAt = out.size();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt(body, headers.size());
        for (byte[] header : headers) {
            body.writeBytes(header);
        }
        byte[] payload = body.toByteArray();
        final int keyLen = keyLength("TFile", dir.name, dir.title);
        writeKeyHeader(keyLen + payload.length, payload.length, keyLen, (short) 1,
                       tableAt, parentAt == 0 ? BEGIN : dirKeyAt,
                       "TFile", dir.name, dir.title);
        write(payload);
        return new Placed(tableAt, keyLen + payload.length);
    }

    /** The block a directory carries: when it was written and where its keys are. */
    private void patchRecord(int at, Placed table, int nameBytes,
                             int selfAt, int parentAt) {
        byte[] buffer = out.toByteArray();
        int p = at;
        p = put16(buffer, p, 5);
        p = put32(buffer, p, stamp);
        p = put32(buffer, p, stamp);
        p = put32(buffer, p, table.nbytes());
        p = put32(buffer, p, nameBytes);
        p = put32(buffer, p, selfAt);
        p = put32(buffer, p, parentAt);
        put32(buffer, p, table.at());
        out.reset();
        out.writeBytes(buffer);
    }

    private void writeHeader(int formatVersion, int end, int nameBytes,
                             int compress, int streamerAt, int streamerBytes) {
        byte[] buffer = out.toByteArray();
        int p = 0;
        buffer[p++] = 'r';
        buffer[p++] = 'o';
        buffer[p++] = 'o';
        buffer[p++] = 't';
        // A version at or above a million means 64 bit pointers, which this
        // writer does not produce.
        p = put32(buffer, p, formatVersion >= 1000000 || formatVersion <= 0
                             ? 62800 : formatVersion);
        p = put32(buffer, p, BEGIN);
        p = put32(buffer, p, end);
        p = put32(buffer, p, 0);              // fSeekFree
        p = put32(buffer, p, 0);              // fNbytesFree
        p = put32(buffer, p, 0);              // nfree
        p = put32(buffer, p, nameBytes);
        buffer[p++] = 4;                      // fUnits
        p = put32(buffer, p, compress);
        p = put32(buffer, p, streamerAt);
        put32(buffer, p, streamerBytes);
        out.reset();
        out.writeBytes(buffer);
    }

    // ---- key headers -------------------------------------------------------

    private static int keyLength(String className, String name, String title) {
        return 26 + string(className).length + string(name).length
             + string(title).length;
    }

    private byte[] keyHeader(int nbytes, int objlen, int keyLen, short cycle,
                             int seekKey, int seekPdir, String className,
                             String name, String title, int datime) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeInt(b, nbytes);
        writeShort(b, 4);
        writeInt(b, objlen);
        writeInt(b, datime);
        writeShort(b, keyLen);
        writeShort(b, cycle);
        writeInt(b, seekKey);
        writeInt(b, seekPdir);
        b.writeBytes(string(className));
        b.writeBytes(string(name));
        b.writeBytes(string(title));
        return b.toByteArray();
    }

    private void writeKeyHeader(int nbytes, int objlen, int keyLen, short cycle,
                                int seekKey, int seekPdir, String className,
                                String name, String title) {
        write(keyHeader(nbytes, objlen, keyLen, cycle, seekKey, seekPdir,
                        className, name, title, stamp));
    }

    // ---- small helpers -----------------------------------------------------

    private static byte[] string(String text) {
        byte[] raw = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (raw.length < 255) {
            byte[] out = new byte[raw.length + 1];
            out[0] = (byte) raw.length;
            System.arraycopy(raw, 0, out, 1, raw.length);
            return out;
        }
        byte[] out = new byte[raw.length + 5];
        out[0] = (byte) 255;
        put32(out, 1, raw.length);
        System.arraycopy(raw, 0, out, 5, raw.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void write(byte[] bytes) {
        out.writeBytes(bytes);
    }

    private void pad(int until) {
        while (out.size() < until) {
            out.write(0);
        }
    }

    private static void writeInt(ByteArrayOutputStream b, int value) {
        b.write((value >>> 24) & 0xFF);
        b.write((value >>> 16) & 0xFF);
        b.write((value >>> 8) & 0xFF);
        b.write(value & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream b, int value) {
        b.write((value >>> 8) & 0xFF);
        b.write(value & 0xFF);
    }

    private static int put32(byte[] buffer, int at, int value) {
        buffer[at] = (byte) (value >>> 24);
        buffer[at + 1] = (byte) (value >>> 16);
        buffer[at + 2] = (byte) (value >>> 8);
        buffer[at + 3] = (byte) value;
        return at + 4;
    }

    private static int put16(byte[] buffer, int at, int value) {
        buffer[at] = (byte) (value >>> 8);
        buffer[at + 1] = (byte) value;
        return at + 2;
    }

    /** The moment packed the way a key header stores it. */
    private static int packNow() {
        LocalDateTime now = LocalDateTime.now();
        return ((now.getYear() - 1995) << 26) | (now.getMonthValue() << 22)
             | (now.getDayOfMonth() << 17) | (now.getHour() << 12)
             | (now.getMinute() << 6) | now.getSecond();
    }
}
