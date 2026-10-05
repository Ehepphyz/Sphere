package com.sphere.core.rootio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.zip.Deflater;

/**
 * A ROOT file written in Java, record by record, as TFile writes one (ROOT
 * sources, io/io: TFile, TDirectoryFile, TKey, TFree):
 *
 * <ul>
 *   <li>the header (TFile::WriteHeader), rewritten when the file is closed;</li>
 *   <li>the top directory: its key, its name and title, its record
 *       (TDirectoryFile::FillBuffer), rewritten when the file is closed;</li>
 *   <li>the objects' keys (TKey), listed by the directory, a cycle per name;</li>
 *   <li>records no directory lists (the baskets of a TTree);</li>
 *   <li>the class descriptions (WriteStreamerInfo), the list of keys
 *       (WriteKeys) and the free segments (WriteFree), in that order, when the
 *       file is closed.</li>
 * </ul>
 *
 * Space is taken as TKey::Create takes it, from the list of free segments
 * (TFree::GetBestFree): at the end of the file, or in the room a deleted
 * record left. Above 2 GB, keys, the directory record, the free segments and
 * the header use 64-bit positions, as ROOT's do.
 *
 * <p>Payloads are compressed in ROOT's blocks (core/zip, R__zipMultipleAlgorithm):
 * a nine byte header ("ZL", the method, the two sizes) before each zlib stream
 * of at most 16 MB, kept uncompressed when that is not smaller.
 */
public final class RFileWriter implements AutoCloseable {

    public static final int BEGIN = 100;
    public static final long START_BIG_FILE = 2000000000L;
    private static final int MAX_ZIP_BUFFER = 0xffffff;
    /** TDirectoryFile::Sizeof for a file of version 4 and later. */
    private static final int DIRECTORY_RECORD = 60;

    /** A key the directory lists: what WriteKeys copies, and where it is. */
    public record Key(String className, String name, int cycle, long seek, int nbytes, byte[] header) {
    }

    /** A free segment of the file (TFree). */
    private static final class Free {
        long first;
        long last;

        Free(long first, long last) {
            this.first = first;
            this.last = last;
        }
    }

    private final RandomAccessFile out;
    private final String name;
    private final String title;
    private final int fileVersion;
    private final int compress;
    private long end;
    private final int datimeC;
    private final byte[] uuid;
    private final List<Key> keys = new ArrayList<>();
    private final List<Free> free = new ArrayList<>();
    private final int nbytesName;
    private byte[] streamerList;
    private int streamerKeylen;
    private boolean rewriteInfo;
    private long seekInfo;
    private int nbytesInfo;
    private long seekKeys;
    private int nbytesKeys;
    private long seekFree;
    private int nbytesFree;
    private boolean closed;

    /**
     * Creates the file (TFile::Open with "RECREATE") and writes its header and its top directory.
     *
     * @param fileName       the name ROOT records for the file (as given to TFile::Open)
     * @param fileVersion    the ROOT version code written in the header (62004 for ROOT 6.20/04)
     * @param compress       ROOT's compression setting (101: zlib level 1)
     * @param streamerList   the TList of TStreamerInfo, uncompressed, as it was written behind a key
     * @param streamerKeylen the length of that key (64 in a file below 2 GB)
     */
    public RFileWriter(Path path, String fileName, String title, int fileVersion, int compress, byte[] streamerList,
                       int streamerKeylen) throws IOException {
        Files.deleteIfExists(path);
        this.out = new RandomAccessFile(path.toFile(), "rw");
        this.name = fileName;
        this.title = title == null ? "" : title;
        this.fileVersion = fileVersion;
        this.compress = compress;
        this.streamerList = streamerList;
        this.streamerKeylen = streamerKeylen;
        this.datimeC = datime();
        this.uuid = uuid();
        // TFile::Init: the first free segment, then the directory's key
        free.add(new Free(BEGIN, START_BIG_FILE));
        end = BEGIN;
        final int topKeylen = keylen("TFile", name, this.title, false);
        final int namelen = tstringLength(name) + tstringLength(this.title);
        this.nbytesName = topKeylen + namelen;
        final long at = allocate(topKeylen + namelen + DIRECTORY_RECORD);
        if (at != BEGIN) throw new IOException("the directory does not start at " + BEGIN);
        writeHeader();
        final WBuffer top = new WBuffer(0);
        top.bytes(keyHeader(topKeylen + namelen + DIRECTORY_RECORD, 4, namelen + DIRECTORY_RECORD, datimeC, topKeylen, 1,
            BEGIN, 0, "TFile", name, this.title));
        top.tstring(name);
        top.tstring(this.title);
        writeAt(BEGIN, top.toByteArray());
        writeDirectoryRecord(datimeC);
    }

    /**
     * Opens a file ROOT wrote, to add to it (TFile::Open with "UPDATE"): its
     * header, its directory, its keys and its free segments are read; nothing
     * is written until objects are and the file is closed. Its class
     * descriptions stay, unless {@link #replaceStreamerInfo} is called.
     *
     * @param fileName the name given to open it, which the keys written now record
     */
    public static RFileWriter update(Path path, String fileName) throws IOException {
        return new RFileWriter(path, fileName);
    }

    private RFileWriter(Path path, String fileName) throws IOException {
        this.out = new RandomAccessFile(path.toFile(), "rw");
        try {
            final byte[] h = new byte[BEGIN];
            out.seek(0);
            out.readFully(h);
            if (h[0] != 'r' || h[1] != 'o' || h[2] != 'o' || h[3] != 't') throw new IOException(path + " is not a ROOT file");
            final ByteBuffer hb = ByteBuffer.wrap(h);
            int version = hb.getInt(4);
            final boolean big = version > 1000000;
            if (big) version -= 1000000;
            if (hb.getInt(8) != BEGIN) throw new IOException(path + " does not start its records at " + BEGIN);
            int p = 12;
            if (big) {
                end = hb.getLong(p);
                seekFree = hb.getLong(p + 8);
                p += 16;
            } else {
                end = Integer.toUnsignedLong(hb.getInt(p));
                seekFree = Integer.toUnsignedLong(hb.getInt(p + 4));
                p += 8;
            }
            nbytesFree = hb.getInt(p);
            p += 8; // and the number of free segments
            this.nbytesName = hb.getInt(p);
            p += 5; // and the units
            this.compress = hb.getInt(p);
            p += 4;
            if (big) {
                seekInfo = hb.getLong(p);
                p += 8;
            } else {
                seekInfo = Integer.toUnsignedLong(hb.getInt(p));
                p += 4;
            }
            nbytesInfo = hb.getInt(p);
            p += 4;
            this.uuid = Arrays.copyOfRange(h, p, p + 18);
            this.fileVersion = version;
            this.name = fileName;
            // the title of the top directory, from its key
            final byte[] top = read(BEGIN, nbytesName);
            final int topKeylen = ByteBuffer.wrap(top).getShort(14);
            final int titleAt = topKeylen + 1 + (top[topKeylen] & 0xff);
            this.title = new String(top, titleAt + 1, top[titleAt] & 0xff, StandardCharsets.UTF_8);
            final ByteBuffer rec = ByteBuffer.wrap(read(BEGIN + nbytesName, DIRECTORY_RECORD));
            final int dirVersion = rec.getShort(0);
            this.datimeC = rec.getInt(2);
            nbytesKeys = rec.getInt(10);
            seekKeys = dirVersion > 1000 ? rec.getLong(34) : Integer.toUnsignedLong(rec.getInt(26));
            readKeys();
            readFree();
        } catch (IOException | RuntimeException e) {
            out.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** The list of keys (TDirectoryFile::ReadKeys): their headers as they are. */
    private void readKeys() throws IOException {
        if (seekKeys == 0) return;
        final byte[] record = read(seekKeys, nbytesKeys);
        final ByteBuffer b = ByteBuffer.wrap(record);
        int p = b.getShort(14);
        final int n = b.getInt(p);
        p += 4;
        for (int i = 0; i < n; i++) {
            final int nbytes = b.getInt(p);
            final int version = b.getShort(p + 4);
            final int klen = b.getShort(p + 14);
            final int cycle = b.getShort(p + 16);
            final long seek = version > 1000 ? b.getLong(p + 18) : Integer.toUnsignedLong(b.getInt(p + 18));
            int q = p + 18 + (version > 1000 ? 16 : 8);
            final String[] names = new String[3];
            for (int k = 0; k < 3; k++) {
                int len = record[q] & 0xff;
                q++;
                if (len == 255) {
                    len = b.getInt(q);
                    q += 4;
                }
                names[k] = new String(record, q, len, StandardCharsets.UTF_8);
                q += len;
            }
            keys.add(new Key(names[0], names[1], cycle, seek, nbytes, Arrays.copyOfRange(record, p, p + klen)));
            p += klen;
        }
    }

    /** The free segments (TFile::ReadFree). */
    private void readFree() throws IOException {
        if (seekFree <= BEGIN) throw new IOException("the file was not closed: its free segments are unknown");
        final byte[] record = read(seekFree, nbytesFree);
        final ByteBuffer b = ByteBuffer.wrap(record);
        final int keylen = b.getShort(14);
        final int objlen = b.getInt(6);
        int p = keylen;
        while (p + 10 <= keylen + objlen && p + 10 <= record.length) {
            final int version = b.getShort(p);
            if (version > 1000) {
                free.add(new Free(b.getLong(p + 2), b.getLong(p + 10)));
                p += 18;
            } else {
                final long first = Integer.toUnsignedLong(b.getInt(p + 2));
                if (first == 0) break;
                free.add(new Free(first, Integer.toUnsignedLong(b.getInt(p + 6))));
                p += 10;
            }
        }
        if (free.isEmpty()) free.add(new Free(end, Math.max(START_BIG_FILE, end + 1000000000L)));
    }

    private byte[] read(long at, int n) throws IOException {
        final byte[] b = new byte[n];
        out.seek(at);
        out.readFully(b);
        return b;
    }

    /** The key of that name with the highest cycle, or null. */
    public Key key(String objName) {
        Key best = null;
        for (Key k : keys) if (k.name().equals(objName) && (best == null || k.cycle() > best.cycle())) best = k;
        return best;
    }

    /**
     * The class descriptions to write when the file is closed, in place of
     * those it has (TFile::WriteStreamerInfo when classes were added); null
     * when no class was used, and none are written.
     */
    public void replaceStreamerInfo(byte[] list, int keylen) {
        this.streamerList = list;
        this.streamerKeylen = keylen;
        this.rewriteInfo = true;
    }

    public long end() {
        return end;
    }

    public int compression() {
        return compress;
    }

    /* ---- keys ----------------------------------------------------------------------------- */

    /** TKey::Sizeof: 26 bytes, 8 more with 64-bit positions, and the three strings. */
    public static int keylen(String className, String name, String title, boolean wide) {
        return 26 + (wide ? 8 : 0) + tstringLength(className) + tstringLength(name) + tstringLength(title);
    }

    static int tstringLength(String s) {
        final int n = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8).length;
        return n < 255 ? n + 1 : n + 5;
    }

    /** TKey::FillBuffer. */
    public static byte[] keyHeader(int nbytes, int version, int objlen, int datime, int keylen, int cycle, long seekKey,
                                   long seekPdir, String className, String name, String title) {
        final WBuffer b = new WBuffer(0);
        b.i32(nbytes);
        b.i16(version);
        b.i32(objlen);
        b.u32(Integer.toUnsignedLong(datime));
        b.i16(keylen);
        b.i16(cycle);
        if (version > 1000) {
            b.i64(seekKey);
            b.i64(seekPdir);
        } else {
            b.i32((int) seekKey);
            b.i32((int) seekPdir);
        }
        b.tstring(className);
        b.tstring(name);
        b.tstring(title);
        return b.toByteArray();
    }

    /**
     * Writes an object under a key of the top directory (TDirectoryFile::WriteTObject):
     * the next cycle of its name, the payload serialised for the key's length
     * (the tags inside count from the key's start), compressed when it is worth it.
     */
    public Key writeObject(String className, String objName, String objTitle, IntFunction<byte[]> payloadForKeylen)
            throws IOException {
        final boolean wide = end > START_BIG_FILE;
        final int keylen = keylen(className, objName, objTitle, wide);
        final byte[] object = payloadForKeylen.apply(keylen);
        final byte[] stored = compressed(object, true);
        int cycle = 0;
        for (Key k : keys) if (k.name().equals(objName) && k.cycle() > cycle) cycle = k.cycle();
        cycle++;
        final int nbytes = keylen + stored.length;
        final long at = allocate(nbytes);
        final byte[] header = keyHeader(nbytes, wide ? 1004 : 4, object.length, datime(), keylen, cycle, at, BEGIN,
            className, objName, objTitle);
        writeAt(at, header);
        writeAt(at + header.length, stored);
        final Key key = new Key(className, objName, cycle, at, nbytes, header);
        // TDirectoryFile::AppendKey: a new cycle goes before the older ones of its name
        int where = keys.size();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).name().equals(objName)) {
                where = i;
                break;
            }
        }
        keys.add(where, key);
        return key;
    }

    /** TKey::Delete: the key leaves the directory and its room becomes free. */
    public void deleteKey(Key key) throws IOException {
        if (keys.remove(key)) makeFree(key.seek(), key.seek() + key.nbytes() - 1);
    }

    /**
     * Writes a TTree basket (TBasket::WriteBuffer), a record no directory
     * lists: its key (version 1004, 64-bit positions), its own fields, then
     * the payload, compressed unless that does not make it smaller.
     *
     * @param fields  what TBasket::Streamer writes after the key: version, buffer size,
     *                entry offset size, entries, last byte, flag
     * @return {position, bytes on disk, key length}
     */
    public long[] writeBasket(String branchName, String treeName, int cycle, byte[] fields, byte[] payload)
            throws IOException {
        final int keylen = keylen("TBasket", branchName, treeName, true) + fields.length;
        final byte[] stored = compressed(payload, false);
        final int nbytes = keylen + stored.length;
        final long at = allocate(nbytes);
        final byte[] header = keyHeader(nbytes, 1004, payload.length, datime(), keylen, cycle, at, BEGIN,
            "TBasket", branchName, treeName);
        writeAt(at, header);
        writeAt(at + header.length, fields);
        writeAt(at + header.length + fields.length, stored);
        return new long[]{at, nbytes, keylen};
    }

    /**
     * TFile::WriteStreamerInfo: the list of class descriptions behind its own
     * key, which no directory lists; the earlier one, if any, is freed.
     */
    public void writeStreamerInfo() throws IOException {
        final boolean wide = end > START_BIG_FILE;
        final int keylen = keylen("TList", "StreamerInfo", "Doubly linked list", wide);
        final byte[] list = Streamers.rebase(streamerList, streamerKeylen, keylen);
        if (seekInfo != 0) makeFree(seekInfo, seekInfo + nbytesInfo - 1);
        final byte[] stored = compressed(list, true);
        final int nbytes = keylen + stored.length;
        final long at = allocate(nbytes);
        final byte[] header = keyHeader(nbytes, wide ? 1004 : 4, list.length, datime(), keylen, 1, at, BEGIN,
            "TList", "StreamerInfo", "Doubly linked list");
        writeAt(at, header);
        writeAt(at + header.length, stored);
        seekInfo = at;
        nbytesInfo = nbytes;
    }

    public boolean streamerInfoWritten() {
        return seekInfo != 0;
    }

    /* ---- space ---------------------------------------------------------------------------- */

    /**
     * TFree::GetBestFree then TKey::Create: a segment of exactly that size,
     * else the first one with room to spare, else the last one stretched by
     * 1 GB; at the end of the file the end moves, in a gap the rest of the gap
     * is marked by its negated size.
     */
    private long allocate(int nsize) throws IOException {
        Free best = null;
        Free roomy = null;
        for (Free f : free) {
            final long nleft = f.last - f.first + 1;
            if (nleft == nsize) {
                best = f;
                break;
            }
            if (nleft > nsize + 3L && roomy == null) roomy = f;
        }
        if (best == null) best = roomy;
        if (best == null) {
            best = free.get(free.size() - 1);
            best.last += 1000000000L;
        }
        final long at = best.first;
        if (at >= end) {
            end = at + nsize;
            best.first = at + nsize;
            if (end > best.last) best.last += 1000000000L;
            return at;
        }
        final long left = best.last - at - nsize + 1;
        if (left == 0) {
            free.remove(best);
        } else if (left > 0) {
            final WBuffer marker = new WBuffer(0);
            marker.i32((int) -left);
            writeAt(at + nsize, marker.toByteArray());
            best.first = at + nsize;
        }
        return at;
    }

    /** TFile::MakeFree with TFree::AddFree: the segment joins its neighbours, and its first bytes say its size. */
    private void makeFree(long first, long last) throws IOException {
        Free added = null;
        for (int i = 0; i < free.size(); i++) {
            final Free cur = free.get(i);
            if (cur.last == first - 1) {
                cur.last = last;
                if (i + 1 < free.size() && free.get(i + 1).first <= last + 1) {
                    cur.last = free.get(i + 1).last;
                    free.remove(i + 1);
                }
                added = cur;
                break;
            }
            if (cur.first == last + 1) {
                cur.first = first;
                added = cur;
                break;
            }
            if (first < cur.first) {
                added = new Free(first, last);
                free.add(i, added);
                break;
            }
        }
        if (added == null) return;
        final long size = Math.min(added.last - added.first + 1, 2000000000L);
        if (last == end - 1) end = added.first;
        final WBuffer marker = new WBuffer(0);
        marker.i32((int) -size);
        writeAt(added.first, marker.toByteArray());
    }

    /* ---- compression ------------------------------------------------------------------- */

    /**
     * The payload as ROOT stores it: blocks of at most 16 MB, each behind its
     * nine byte header; the payload itself when compression does not make it
     * smaller, or when it is 256 bytes or less for a key (TKey's rule), or
     * less than 11 bytes for a basket (R__zip's).
     */
    public byte[] compressed(byte[] payload, boolean keyRule) {
        final int level = compress % 100;
        final int algorithm = compress / 100;
        if (level <= 0 || (algorithm != 1 && algorithm != 0)) return payload;
        if (keyRule ? payload.length <= 256 : payload.length < 11) return payload;
        final ByteArrayOutputStream zipped = new ByteArrayOutputStream(payload.length / 2 + 64);
        final byte[] buf = new byte[65536];
        for (int from = 0; from < payload.length; from += MAX_ZIP_BUFFER) {
            final int len = Math.min(MAX_ZIP_BUFFER, payload.length - from);
            final Deflater d = new Deflater(Math.min(level, 9));
            d.setInput(payload, from, len);
            d.finish();
            final ByteArrayOutputStream z = new ByteArrayOutputStream(len / 2 + 64);
            while (!d.finished()) z.write(buf, 0, d.deflate(buf));
            d.end();
            final int packed = z.size();
            if (packed >= payload.length) return payload;
            zipped.write('Z');
            zipped.write('L');
            zipped.write(8);
            zipped.write(packed & 0xff);
            zipped.write((packed >>> 8) & 0xff);
            zipped.write((packed >>> 16) & 0xff);
            zipped.write(len & 0xff);
            zipped.write((len >>> 8) & 0xff);
            zipped.write((len >>> 16) & 0xff);
            zipped.writeBytes(z.toByteArray());
        }
        return zipped.size() >= payload.length ? payload : zipped.toByteArray();
    }

    /* ---- closing ------------------------------------------------------------------------- */

    /** TFile::Close: the class descriptions if not yet written, the keys, the directory, the free segments, the header. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if ((seekInfo == 0 || rewriteInfo) && streamerList != null) writeStreamerInfo();
            writeKeys();
            writeDirectoryRecord(datime());
            writeFree();
            writeHeader();
        } finally {
            out.close();
        }
    }

    /** Lets the file go without closing it as ROOT does: what is written stays, nothing more is. */
    public void abandon() {
        if (closed) return;
        closed = true;
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing more can be done with it
        }
    }

    /** TDirectoryFile::WriteKeys: the number of keys and every key's header, behind a key of the directory's class. */
    private void writeKeys() throws IOException {
        if (seekKeys != 0) makeFree(seekKeys, seekKeys + nbytesKeys - 1);
        final WBuffer list = new WBuffer(0);
        list.i32(keys.size());
        for (Key k : keys) list.bytes(k.header());
        if (end > START_BIG_FILE) list.i64(0);
        final byte[] payload = list.toByteArray();
        final boolean wide = end > START_BIG_FILE;
        final int keylen = keylen("TFile", name, title, wide);
        final int nbytes = keylen + payload.length;
        final long at = allocate(nbytes);
        final byte[] header = keyHeader(nbytes, wide ? 1004 : 4, payload.length, datime(), keylen, 1, at, BEGIN,
            "TFile", name, title);
        writeAt(at, header);
        writeAt(at + header.length, payload);
        seekKeys = at;
        nbytesKeys = nbytes;
    }

    /** TFile::WriteFree: every free segment (TFree::FillBuffer), behind a key that itself takes room. */
    private void writeFree() throws IOException {
        if (seekFree != 0) makeFree(seekFree, seekFree + nbytesFree - 1);
        final boolean wide = end > START_BIG_FILE;
        int size = 0;
        for (Free f : free) size += f.last > START_BIG_FILE ? 18 : 10;
        final int keylen = keylen("TFile", name, title, wide);
        final int nbytes = keylen + size;
        final long at = allocate(nbytes);
        final WBuffer list = new WBuffer(0);
        for (Free f : free) {
            if (f.last > START_BIG_FILE) {
                list.i16(1001);
                list.i64(f.first);
                list.i64(f.last);
            } else {
                list.i16(1);
                list.i32((int) f.first);
                list.i32((int) f.last);
            }
        }
        final byte[] segments = Arrays.copyOf(list.toByteArray(), size);
        final byte[] header = keyHeader(nbytes, wide ? 1004 : 4, size, datime(), keylen, 1, at, BEGIN,
            "TFile", name, title);
        writeAt(at, header);
        writeAt(at + header.length, segments);
        seekFree = at;
        nbytesFree = nbytes;
    }

    /** TFile::WriteHeader: 32-bit positions, or 64-bit ones (version + 1000000) above 2 GB. */
    private void writeHeader() throws IOException {
        final WBuffer h = new WBuffer(0);
        h.bytes("root".getBytes(StandardCharsets.US_ASCII));
        final boolean big = end > START_BIG_FILE;
        h.i32(big ? fileVersion + 1000000 : fileVersion);
        h.i32(BEGIN);
        if (big) {
            h.i64(end);
            h.i64(seekFree);
            h.i32(nbytesFree);
            h.i32(free.size());
            h.i32(nbytesName);
            h.i8(8);
            h.i32(compress);
            h.i64(seekInfo);
            h.i32(nbytesInfo);
        } else {
            h.i32((int) end);
            h.i32((int) seekFree);
            h.i32(nbytesFree);
            h.i32(free.size());
            h.i32(nbytesName);
            h.i8(4);
            h.i32(compress);
            h.i32((int) seekInfo);
            h.i32(nbytesInfo);
        }
        h.bytes(uuid);
        final byte[] bytes = h.toByteArray();
        final byte[] padded = new byte[BEGIN];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        writeAt(0, padded);
    }

    /** TDirectoryFile::WriteDirHeader: the directory's record, after its key, name and title. */
    private void writeDirectoryRecord(int datimeM) throws IOException {
        final WBuffer b = new WBuffer(0);
        final boolean wide = seekKeys > START_BIG_FILE;
        b.i16(wide ? 1005 : 5);
        b.u32(Integer.toUnsignedLong(datimeC));
        b.u32(Integer.toUnsignedLong(datimeM));
        b.i32(nbytesKeys);
        b.i32(nbytesName);
        if (wide) {
            b.i64(BEGIN);
            b.i64(0);
            b.i64(seekKeys);
        } else {
            b.i32(BEGIN);
            b.i32(0);
            b.i32((int) seekKeys);
        }
        b.bytes(uuid);
        if (!wide) {
            b.i32(0);
            b.i32(0);
            b.i32(0);
        }
        writeAt(BEGIN + nbytesName, b.toByteArray());
    }

    private void writeAt(long at, byte[] bytes) throws IOException {
        out.seek(at);
        out.write(bytes);
    }

    /** TDatime::Set: the year since 1995, month, day, hour, minute and second in 32 bits, local time. */
    public static int datime() {
        return datime(LocalDateTime.now());
    }

    /** TDatime((UInt_t) 0), what TKey::Reset leaves in a basket kept in memory: the epoch, local time. */
    public static int datimeEpoch() {
        return datime(LocalDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault()));
    }

    private static int datime(LocalDateTime t) {
        return ((t.getYear() - 1995) << 26) | (t.getMonthValue() << 22) | (t.getDayOfMonth() << 17)
            | (t.getHour() << 12) | (t.getMinute() << 6) | t.getSecond();
    }

    /** TUUID::FillBuffer: version 1, then the sixteen bytes. */
    private static byte[] uuid() {
        final UUID u = UUID.randomUUID();
        final WBuffer b = new WBuffer(0);
        b.i16(1);
        b.i64(u.getMostSignificantBits());
        b.i64(u.getLeastSignificantBits());
        return b.toByteArray();
    }
}
