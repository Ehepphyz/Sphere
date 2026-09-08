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
    private final SeekableByteChannel channel;
    private final long size;

    private int fileVersion;
    private long begin;
    private long end;
    private long seekFree;
    private long seekInfo;
    private int nbytesInfo;
    private int compress;
    private int units;
    private boolean bigFile;

    private RootNode root;

    public RootFile(Path path) throws IOException {
        this.path = path;
        this.channel = Files.newByteChannel(path);
        this.size = Files.size(path);
        readHeader();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // ---- header ------------------------------------------------------------

    public Path getPath() {
        return path;
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
        h.getInt();                 // fNbytesFree
        h.getInt();                 // nfree
        h.getInt();                 // fNbytesName
        units = h.get() & 0xff;
        compress = h.getInt();
        seekInfo = bigFile ? h.getLong() : Integer.toUnsignedLong(h.getInt());
        nbytesInfo = h.getInt();
    }

    // ---- raw access --------------------------------------------------------

    private ByteBuffer read(long at, int length) throws IOException {
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
            int written;
            switch (algorithm) {
                case "ZL":
                    written = inflateZlib(packedBytes, result, filled, room);
                    break;
                case "L4":
                    // LZ4 blocks carry an eight byte checksum before the data.
                    written = Lz4Block.decode(packedBytes, 8, packedBytes.length - 8,
                                              result, filled, room);
                    break;
                case "ZS":
                    written = inflateZstd(packedBytes, unpacked, result, filled, room);
                    break;
                default:
                    // XZ is LZMA and CS the old ROOT routine; neither ships with
                    // the JDK, so say so rather than return noise.
                    throw new UnsupportedPayload(algorithmName(algorithm));
            }
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

    private static int inflateZlib(byte[] input, byte[] out, int at, int room)
            throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            return inflater.inflate(out, at, room);
        } catch (DataFormatException bad) {
            throw new IOException("a compressed block is damaged");
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
                                   int at, int room) throws IOException {
        try {
            byte[] block = ZstdBlock.decode(input, 0, input.length, unpacked);
            final int written = Math.min(block.length, room);
            System.arraycopy(block, 0, out, at, written);
            return written;
        } catch (DataFormatException bad) {
            throw new IOException("a compressed block is damaged");
        }
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
}
