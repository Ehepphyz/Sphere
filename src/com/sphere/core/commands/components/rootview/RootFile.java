package com.sphere.components.rootview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A .root file read without ROOT.
 *
 * Same approach as the :root command's reader, kept as an object so the viewer
 * can walk a file and pull one object at a time. The container -- the TFile
 * header, the TKey records and the directory tables -- is stored uncompressed so
 * that ROOT can seek without inflating, and it does not depend on which program
 * wrote the file.
 */
public final class RootFile implements AutoCloseable {

    /** A payload compressed with something the JDK cannot inflate. */
    public static final class UnsupportedPayload extends IOException {
        public final String algorithm;

        UnsupportedPayload(String algorithm) {
            super("compressed with " + algorithm);
            this.algorithm = algorithm;
        }
    }

    public static final class NotRootFile extends IOException {
        NotRootFile(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 32;
    private static final int MAX_KEYS = 200_000;
    private static final long MAX_PAYLOAD = 512L * 1024 * 1024;

    private final Path path;
    /** Where the bytes come from: an absolute path, or the URL of a remote file. */
    private final String location;
    private final boolean remote;
    private final SeekableByteChannel channel;
    private final long size;

    private int fileVersion;
    private long begin;
    private long end;
    private long seekFree;
    private int nbytesFree;
    private int nbytesName;
    private long seekInfo;
    private int nbytesInfo;
    private int compress;
    private int units;
    private boolean bigFile;

    private RootNode root;

    public RootFile(Path path) throws IOException {
        this.path = path;
        this.location = path.toAbsolutePath().toString();
        this.remote = false;
        this.channel = Files.newByteChannel(path);
        this.size = Files.size(path);
        try {
            readHeader();
        } catch (IOException | RuntimeException notRoot) {
            // The channel was opened above; a file that is not ROOT must not keep it.
            channel.close();
            throw notRoot;
        }
    }

    /**
     * A file read through any channel: a remote file over HTTP, an entry stored
     * inside a zip archive. The nominal path is only a name for display (its
     * file name is what titles show); location says where the bytes really are.
     */
    public RootFile(SeekableByteChannel channel, long size, Path nominal, String location)
            throws IOException {
        this.path = nominal;
        this.location = location;
        this.remote = true;
        this.channel = channel;
        this.size = size;
        try {
            readHeader();
        } catch (IOException | RuntimeException notRoot) {
            channel.close();
            throw notRoot;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // ---- header ------------------------------------------------------------

    public Path getPath() {
        return path;
    }

    /** The absolute path, or the URL, the file was opened from. */
    public String getLocation() {
        return location;
    }

    /** True when the bytes are not read from a local file (HTTP, an archive). */
    public boolean isRemote() {
        return remote;
    }

    /** fBEGIN: where the first record, the TFile's own key, starts. */
    public long getBegin() {
        return begin;
    }

    public int getFileVersionRaw() {
        return fileVersion;
    }

    public int getNbytesFree() {
        return nbytesFree;
    }

    public int getNbytesName() {
        return nbytesName;
    }

    public int getUnits() {
        return units;
    }

    /** fCompress as written: 100 * algorithm + level. */
    public int getCompressionSetting() {
        return compress;
    }

    public long getSize() {
        return size;
    }

    public int getFormatVersion() {
        return bigFile ? fileVersion - 1_000_000 : fileVersion;
    }

    public boolean isBigFile() {
        return bigFile;
    }

    public int getCompressionAlgorithm() {
        return compress / 100;
    }

    public int getCompressionLevel() {
        return compress % 100;
    }

    public long getStreamerInfoSeek() {
        return seekInfo;
    }

    public int getStreamerInfoBytes() {
        return nbytesInfo;
    }

    public long getEnd() {
        return end;
    }

    public long getFreeSeek() {
        return seekFree;
    }

    public int getPointerBits() {
        return bigFile ? 64 : units * 8;
    }

    private void readHeader() throws IOException {
        if (size < 100) {
            throw new NotRootFile("Too short to be a ROOT file: " + size + " bytes");
        }
        ByteBuffer h = read(0, 100);
        byte[] magic = new byte[4];
        h.get(magic);
        if (!"root".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new NotRootFile("Not a ROOT file: it does not start with \"root\"");
        }

        fileVersion = h.getInt();
        // Past 2 GB, ROOT adds a million to the version and widens the pointers
        bigFile = fileVersion > 1_000_000;

        begin = Integer.toUnsignedLong(h.getInt());
        if (bigFile) {
            end = h.getLong();
            seekFree = h.getLong();
        } else {
            end = Integer.toUnsignedLong(h.getInt());
            seekFree = Integer.toUnsignedLong(h.getInt());
        }
        nbytesFree = h.getInt();
        h.getInt();                 // nfree
        nbytesName = h.getInt();
        units = h.get() & 0xff;
        compress = h.getInt();
        seekInfo = bigFile ? h.getLong() : Integer.toUnsignedLong(h.getInt());
        nbytesInfo = h.getInt();
    }

    // ---- raw access --------------------------------------------------------

    /**
     * Synchronized because the position and the read are two calls on one
     * channel: the viewer, the tree reader and the ROOT Lab may read the same
     * file from different threads, and an interleaving there handed one of them
     * the bytes of another record.
     */
    private synchronized ByteBuffer read(long at, int length) throws IOException {
        if (at < 0 || length < 0 || at + length > size) {
            throw new IOException("read past the end of the file at " + at);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        channel.position(at);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("file ended early at " + at);
            }
        }
        return buffer.flip();
    }

    /** A ROOT TString: one length byte, or 255 then a four byte length. */
    static String readString(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return "";
        }
        int length = buffer.get() & 0xff;
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

    /** Bytes of the file as they sit on disk: a basket's key header, say. */
    public byte[] rawAt(long at, int length) throws IOException {
        ByteBuffer buffer = read(at, length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    /** The key at an offset (a TTree basket, which no directory lists), or null. */
    public RootKey keyAt(long at) throws IOException {
        return readKey(at);
    }

    /** The list of class descriptions, inflated: the TList of TStreamerInfo. */
    public RootKey streamerInfoKey() throws IOException {
        return seekInfo > 0 ? readKey(seekInfo) : null;
    }

    // ---- keys --------------------------------------------------------------

    /** The key header at an offset. Returns null when it cannot be one. */
    private RootKey readKey(long at) throws IOException {
        if (at < 0 || at + 18 > size) {
            return null;
        }
        RootKey k = new RootKey();
        k.offset = at;
        ByteBuffer fixed = read(at, 18);
        k.nbytes = fixed.getInt();
        k.version = fixed.getShort();
        k.objlen = fixed.getInt();
        k.datime = fixed.getInt();
        k.keylen = fixed.getShort();
        k.cycle = fixed.getShort();

        // A key written past 2 GB carries 8 byte pointers, flagged on the key
        boolean wide = k.version > 1000;
        int pointerBytes = wide ? 16 : 8;
        if (k.keylen < 18 + pointerBytes || at + k.keylen > size) {
            return null;
        }
        ByteBuffer rest = read(at + 18, k.keylen - 18);
        k.seekKey = wide ? rest.getLong() : Integer.toUnsignedLong(rest.getInt());
        k.seekPdir = wide ? rest.getLong() : Integer.toUnsignedLong(rest.getInt());
        k.className = readString(rest);
        k.name = readString(rest);
        k.title = readString(rest);
        return k;
    }

    /** A key header read out of an in-memory key table rather than the file. */
    private static RootKey readKeyFrom(ByteBuffer buffer) {
        if (buffer.remaining() < 18) {
            return null;
        }
        RootKey k = new RootKey();
        k.nbytes = buffer.getInt();
        k.version = buffer.getShort();
        k.objlen = buffer.getInt();
        k.datime = buffer.getInt();
        k.keylen = buffer.getShort();
        k.cycle = buffer.getShort();
        boolean wide = k.version > 1000;
        if (buffer.remaining() < (wide ? 16 : 8)) {
            return null;
        }
        k.seekKey = wide ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
        k.seekPdir = wide ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
        k.className = readString(buffer);
        k.name = readString(buffer);
        k.title = readString(buffer);
        k.offset = k.seekKey;
        return k;
    }

    // ---- payload -----------------------------------------------------------

    /**
     * The object behind a key. Uncompressed when nbytes - keylen equals objlen,
     * otherwise a chain of blocks, each behind a nine byte header naming its
     * algorithm, because ROOT splits past 16 MB.
     */
    public byte[] payload(RootKey k) throws IOException {
        if (k.objlen < 0 || k.objlen > MAX_PAYLOAD) {
            throw new IOException("object of " + k.objlen + " bytes refused");
        }
        int stored = k.nbytes - k.keylen;
        if (stored < 0) {
            throw new IOException("key at " + k.offset + " is malformed");
        }

        if (!k.compressed()) {
            ByteBuffer raw = read(k.payloadAt(), k.objlen);
            byte[] bytes = new byte[k.objlen];
            raw.get(bytes);
            return bytes;
        }

        byte[] result = new byte[k.objlen];
        int filled = 0;
        long at = k.payloadAt();
        long limit = k.payloadAt() + stored;

        while (filled < k.objlen && at + 9 <= limit) {
            ByteBuffer head = read(at, 9);
            byte[] tag = new byte[2];
            head.get(tag);
            String algorithm = new String(tag, StandardCharsets.US_ASCII);
            head.get();                                  // method
            int packed = u24(head);
            int unpacked = u24(head);
            at += 9;

            if (packed <= 0 || at + packed > limit) {
                break;
            }
            ByteBuffer block = read(at, packed);
            byte[] packedBytes = new byte[packed];
            block.get(packedBytes);

            final int room = Math.min(unpacked, result.length - filled);
            final int written = unpackBlock(algorithm, packedBytes, unpacked, result, filled, room, at - 9);
            if (written <= 0) {
                break;
            }
            filled += written;
            at += packed;
        }

        if (filled < k.objlen) {
            throw new IOException("object was only partly recovered: "
                                  + filled + " of " + k.objlen + " bytes");
        }
        return result;
    }

    /**
     * One compressed block, by the tag in front of it, as R__unzip dispatches:
     * ZL zlib, CS the old ROOT deflate (raw, no zlib wrapper), XZ an .xz
     * stream, L4 LZ4 behind its XXH64 checksum, ZS a Zstandard frame. Every
     * check the format carries is verified, as ROOT does; a block that fails
     * one is refused rather than returned damaged.
     */
    public static int unpackBlock(String algorithm, byte[] packedBytes, int unpacked,
                           byte[] result, int filled, int room, long blockAt) throws IOException {
        try {
            switch (algorithm) {
                case "ZL":
                    return inflate(packedBytes, result, filled, room, false, blockAt);
                case "CS":
                    // R__Inflate, the Info-ZIP inflate ROOT kept for its first
                    // files: deflate without the zlib header and trailer.
                    return inflate(packedBytes, result, filled, room, true, blockAt);
                case "XZ": {
                    byte[] block = XzBlock.decode(packedBytes, 0, packedBytes.length, unpacked);
                    final int written = Math.min(block.length, room);
                    System.arraycopy(block, 0, result, filled, written);
                    return written;
                }
                case "L4": {
                    // LZ4 blocks carry an eight byte checksum before the data.
                    if (packedBytes.length < 8) {
                        throw new IOException("LZ4 block at " + blockAt + " is too short for its checksum");
                    }
                    final long stored = XxHash64.readCanonical(packedBytes, 0);
                    final long actual = XxHash64.hash(packedBytes, 8, packedBytes.length - 8, 0);
                    if (stored != actual) {
                        throw new IOException("LZ4 block at " + blockAt
                            + " fails its XXH64 checksum: the data is damaged");
                    }
                    return Lz4Block.decode(packedBytes, 8, packedBytes.length - 8, result, filled, room);
                }
                case "ZS":
                    return inflateZstd(packedBytes, unpacked, result, filled, room);
                default:
                    throw new UnsupportedPayload(algorithmName(algorithm));
            }
        } catch (DataFormatException bad) {
            throw new IOException(algorithmName(algorithm) + " block at " + blockAt + " is damaged: "
                                  + bad.getMessage());
        } catch (RuntimeException corrupt) {
            // A damaged block can send an index out of its array; that is a bad
            // block, not a reason to take the reading thread down.
            throw new IOException(algorithmName(algorithm) + " block at " + blockAt + " is damaged ("
                                  + corrupt.getClass().getSimpleName() + ")");
        }
    }

    private static int inflate(byte[] input, byte[] out, int at, int room, boolean raw, long blockAt)
            throws IOException, DataFormatException {
        Inflater inflater = new Inflater(raw);
        try {
            if (raw) {
                // zlib wants one byte past a raw stream to be sure it has ended.
                byte[] padded = java.util.Arrays.copyOf(input, input.length + 1);
                inflater.setInput(padded);
            } else {
                inflater.setInput(input);
            }
            int written = 0;
            while (written < room && !inflater.finished()) {
                final int n = inflater.inflate(out, at + written, room - written);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                written += n;
            }
            if (!raw && !inflater.finished() && written == room) {
                // The block filled its room; zlib only checks the Adler-32 once it
                // reads the end of the stream, so make it do so.
                byte[] probe = new byte[1];
                if (inflater.inflate(probe) != 0) {
                    throw new IOException("zlib block at " + blockAt + " holds more data than its header says");
                }
            }
            return written;
        } finally {
            inflater.end();
        }
    }

    /**
     * True when this file belongs to the ROOT viewer.
     *
     * The extension settles it for the usual case; anything else is decided by
     * the four bytes ROOT puts at the front, so a file named otherwise still
     * opens in the right window.
     */
    public static boolean isRootFile(java.io.File candidate) {
        if (candidate == null || !candidate.isFile()) {
            return false;
        }
        if (candidate.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".root")) {
            return true;
        }
        if (candidate.length() < 100) {
            return false;
        }
        try (java.io.InputStream in = Files.newInputStream(candidate.toPath())) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'r' && magic[1] == 'o'
                && magic[2] == 'o' && magic[3] == 't';
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** The bytes of a record exactly as they sit on disk, compression included. */
    public byte[] storedPayload(RootKey k) throws IOException {
        final int stored = k.nbytes - k.keylen;
        if (stored < 0 || stored > MAX_PAYLOAD) {
            throw new IOException("key at " + k.offset + " is malformed");
        }
        ByteBuffer buffer = read(k.payloadAt(), stored);
        byte[] bytes = new byte[stored];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * The list of class descriptions ROOT keeps at the end of a file, copied
     * whole. A file written without it opens here but not in ROOT.
     */
    public byte[] streamerInfoRecord() throws IOException {
        if (seekInfo <= 0 || nbytesInfo <= 0) {
            return null;
        }
        RootKey k = readKey(seekInfo);
        if (k == null) {
            return null;
        }
        return storedPayload(k);
    }

    public int streamerInfoObjectLength() throws IOException {
        if (seekInfo <= 0) {
            return 0;
        }
        RootKey k = readKey(seekInfo);
        return k == null ? 0 : k.objlen;
    }

    private static int inflateZstd(byte[] input, int unpacked, byte[] out,
                                   int at, int room) throws DataFormatException {
        byte[] block = ZstdBlock.decode(input, 0, input.length, unpacked);
        final int written = Math.min(block.length, room);
        System.arraycopy(block, 0, out, at, written);
        return written;
    }

    private static int u24(ByteBuffer b) {
        // The block header stores its two sizes little endian, unlike the rest
        int a = b.get() & 0xff, c = b.get() & 0xff, d = b.get() & 0xff;
        return a | (c << 8) | (d << 16);
    }

    public static String algorithmName(String tag) {
        return switch (tag) {
            case "ZL" -> "zlib";
            case "XZ" -> "LZMA";
            case "L4" -> "LZ4";
            case "ZS" -> "Zstandard";
            case "CS" -> "the old ROOT routine";
            default -> "an unknown algorithm (" + tag + ")";
        };
    }

    public static String compressionName(int algorithm) {
        return switch (algorithm) {
            case 0 -> "as written by the default";
            case 1 -> "zlib";
            case 2 -> "LZMA";
            case 3 -> "the old ROOT routine";
            case 4 -> "LZ4";
            case 5 -> "Zstandard";
            default -> "algorithm " + algorithm;
        };
    }

    // ---- directory tree ----------------------------------------------------

    /** The whole file as a tree of directories and objects, read once. */
    public RootNode tree() throws IOException {
        if (root != null) {
            return root;
        }
        root = new RootNode(path.getFileName().toString(), "", "TFile", null);
        root.directory = true;

        RootKey top = readKey(begin);
        if (top == null) {
            return root;
        }
        long table = topDirectoryKeyTable(top);
        if (table <= 0 || table >= size) {
            return root;
        }
        fill(root, table, 0, new HashSet<>());
        return root;
    }

    private void fill(RootNode parent, long tableAt, int depth, Set<Long> seen)
            throws IOException {
        if (depth > MAX_DEPTH || !seen.add(tableAt)) {
            return;
        }
        RootKey table = readKey(tableAt);
        if (table == null) {
            return;
        }
        byte[] body;
        try {
            body = payload(table);
        } catch (IOException unreadable) {
            parent.note = unreadable.getMessage();
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < 4) {
            return;
        }
        int count = buffer.getInt();
        if (count < 0 || count > MAX_KEYS) {
            return;
        }

        for (int i = 0; i < count && buffer.remaining() >= 18; i++) {
            RootKey k = readKeyFrom(buffer);
            if (k == null) {
                break;
            }
            final boolean directory = k.className.startsWith("TDirectory");
            RootNode node = new RootNode(k.name, k.title, k.className, k);
            node.directory = directory;
            parent.children.add(node);

            if (directory) {
                long child = childKeyTable(k);
                if (child > 0 && child < size) {
                    fill(node, child, depth + 1, seen);
                }
            }
        }
    }

    /** The top level directory record sits in the payload of the first key. */
    private long topDirectoryKeyTable(RootKey top) throws IOException {
        long at = top.payloadAt();
        ByteBuffer buffer = read(at, (int) Math.min(top.objlen, 256));
        // fName and fTitle again, then the directory block
        readString(buffer);
        readString(buffer);
        return directoryKeyTable(buffer);
    }

    /** fSeekKeys out of a TDirectory record, in either pointer width. */
    private static long directoryKeyTable(ByteBuffer buffer) {
        if (buffer.remaining() < 22) {
            return -1;
        }
        short version = buffer.getShort();
        boolean wide = version > 1000;
        buffer.getInt();                        // fDatimeC
        buffer.getInt();                        // fDatimeM
        buffer.getInt();                        // fNbytesKeys
        buffer.getInt();                        // fNbytesName
        int need = wide ? 24 : 12;
        if (buffer.remaining() < need) {
            return -1;
        }
        if (wide) {
            buffer.getLong();                   // fSeekDir
            buffer.getLong();                   // fSeekParent
            return buffer.getLong();
        }
        buffer.getInt();
        buffer.getInt();
        return Integer.toUnsignedLong(buffer.getInt());
    }

    private long childKeyTable(RootKey directoryKey) {
        try {
            RootKey onDisk = readKey(directoryKey.seekKey);
            if (onDisk == null) {
                return -1;
            }
            byte[] body = payload(onDisk);
            ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            return directoryKeyTable(buffer);
        } catch (IOException unreadable) {
            return -1;
        }
    }

    /** Every class the file holds, with how many of each. */
    public List<String> classSummary() throws IOException {
        java.util.Map<String, Integer> tally = new java.util.TreeMap<>();
        countClasses(tree(), tally);
        List<String> lines = new ArrayList<>();
        tally.forEach((name, count) -> lines.add(count + "  " + name));
        return lines;
    }

    private static void countClasses(RootNode node, java.util.Map<String, Integer> tally) {
        for (RootNode child : node.children) {
            tally.merge(child.className, 1, Integer::sum);
            countClasses(child, tally);
        }
    }

    // ---- the records as they sit on disk -------------------------------------

    /**
     * One record met walking the file from fBEGIN: a key and its object, or a
     * gap (a negative byte count, which ROOT writes over freed space).
     */
    public static final class Record {
        public final long offset;
        public final int nbytes;
        public final boolean gap;
        public final RootKey key;

        Record(long offset, int nbytes, boolean gap, RootKey key) {
            this.offset = offset;
            this.nbytes = nbytes;
            this.gap = gap;
            this.key = key;
        }
    }

    /** What a walk over the records found, and where it had to stop. */
    public static final class RecordScan {
        public final List<Record> records = new ArrayList<>();
        /** Null when the walk reached fEND; otherwise why it stopped. */
        public String stoppedBecause;
        public long stoppedAt = -1;
    }

    /**
     * Every record from fBEGIN to fEND, in file order, the way TFile::Recover
     * and TFile::GetCompressionFactor walk them. It does not need the
     * directories: this is how a file whose key list was never written (a job
     * that crashed) still shows what it holds, and how holes and overlaps in a
     * damaged one are found.
     */
    public RecordScan scanRecords() throws IOException {
        RecordScan scan = new RecordScan();
        long at = begin;
        final long stop = Math.min(end, size);
        int guard = 0;
        while (at < stop) {
            if (++guard > 50_000_000) {
                scan.stoppedBecause = "too many records";
                scan.stoppedAt = at;
                break;
            }
            if (at + 4 > size) {
                scan.stoppedBecause = "a record header runs past the end of the file";
                scan.stoppedAt = at;
                break;
            }
            final int nbytes = read(at, 4).getInt();
            if (nbytes < 0) {
                // a free gap, as ROOT marks freed space
                scan.records.add(new Record(at, -nbytes, true, null));
                at += -(long) nbytes;
                continue;
            }
            if (nbytes == 0) {
                scan.stoppedBecause = "a record of zero bytes (the file is damaged here)";
                scan.stoppedAt = at;
                break;
            }
            RootKey k = readKey(at);
            if (k == null || k.nbytes != nbytes || k.keylen <= 0 || k.keylen > nbytes) {
                scan.stoppedBecause = "no readable key header";
                scan.stoppedAt = at;
                break;
            }
            scan.records.add(new Record(at, nbytes, false, k));
            at += nbytes;
        }
        return scan;
    }

    /**
     * TFile::GetCompressionFactor: the bytes the objects would take
     * uncompressed over the bytes they take, keys included, starting from
     * fBEGIN on both sides.
     */
    public double compressionFactor() throws IOException {
        double comp = begin;
        double uncomp = begin;
        long at = begin;
        while (at < end - 100) {
            if (at + 18 > size) {
                break;
            }
            ByteBuffer h = read(at, 18);
            final int nbytes = h.getInt();
            if (nbytes < 0) {
                at -= nbytes;
                continue;
            }
            if (nbytes == 0) {
                break;
            }
            h.getShort();                                   // key version
            int objlen = h.getInt();
            h.getInt();                                     // datime
            final short keylen = h.getShort();
            if (objlen == 0) {
                objlen = nbytes - keylen;
            }
            comp += nbytes;
            uncomp += keylen + objlen;
            at += nbytes;
        }
        return uncomp / comp;
    }

    /** One free segment, [first, last], as TFree writes it. */
    public record FreeSegment(long first, long last) {
    }

    /** The free segments list ROOT keeps at fSeekFree (always uncompressed). */
    public List<FreeSegment> freeSegments() throws IOException {
        List<FreeSegment> out = new ArrayList<>();
        if (seekFree <= 0 || seekFree >= size) {
            return out;
        }
        RootKey k = readKey(seekFree);
        if (k == null) {
            return out;
        }
        ByteBuffer b = ByteBuffer.wrap(payload(k)).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 10) {
            final short version = b.getShort();
            if (version == 0) {
                break;          // TFile::WriteFree pads the record with zeros when it reserved too much
            }
            if (version > 1000) {
                if (b.remaining() < 16) {
                    break;
                }
                out.add(new FreeSegment(b.getLong(), b.getLong()));
            } else {
                out.add(new FreeSegment(Integer.toUnsignedLong(b.getInt()), Integer.toUnsignedLong(b.getInt())));
            }
        }
        return out;
    }

    /**
     * The algorithm the compressed records really use, read off the first
     * one's block header: a file whose fCompress says only "the default"
     * (algorithm 0) was compressed with what that ROOT's default was -- the
     * old ROOT routine before 5.30, zlib after. Null when nothing is compressed.
     */
    public String firstBlockAlgorithm() throws IOException {
        final java.util.Deque<RootNode> todo = new java.util.ArrayDeque<>(tree().children);
        while (!todo.isEmpty()) {
            final RootNode n = todo.poll();
            if (n.directory) {
                todo.addAll(n.children);
            }
            if (n.key != null && n.key.compressed() && n.key.storedBytes() >= 2) {
                final byte[] tag = rawAt(n.key.payloadAt(), 2);
                return algorithmName(new String(tag, StandardCharsets.US_ASCII));
            }
        }
        final RootKey info = streamerInfoKey();
        if (info != null && info.compressed() && info.storedBytes() >= 2) {
            return algorithmName(new String(rawAt(info.payloadAt(), 2), StandardCharsets.US_ASCII));
        }
        return null;
    }

    /** The key list record of the top directory (fSeekKeys), or -1 when it was never written. */
    public long topKeyListSeek() throws IOException {
        RootKey top = readKey(begin);
        return top == null ? -1 : topDirectoryKeyTable(top);
    }
}
