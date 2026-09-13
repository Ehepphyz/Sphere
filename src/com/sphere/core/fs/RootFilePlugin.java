package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * :root -- what a .root file contains, without ROOT.
 *
 * Only the container is read: the TFile header and the TKey records, both of
 * which ROOT stores uncompressed so that it can seek without inflating. That
 * part of the format does not depend on which program wrote the file. What does
 * depend on it -- the branches inside a TTree, custom classes, RNTuple pages --
 * lives in the compressed payload behind the streamer info, and is left to the
 * ROOT backend rather than guessed at here.
 */
public class RootFilePlugin implements CommandRouter.CommandPlugin {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_KEYS = 200_000;
    private static final long MAX_PAYLOAD = 256L * 1024 * 1024;

    private final CommandRouter router;

    public RootFilePlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "rootfile";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        // :root alone belongs to the ROOT backend; only a file argument is ours.
        if (!t.startsWith(":root ")) return false;
        String rest = t.substring(6).trim();
        if (rest.isEmpty()) return false;
        // The grammar here is ":root <file.root> [options]", so the file has to
        // be the first operand. Accepting it anywhere in the line swallowed the
        // backend's own commands: ":root file open x.root" ended up asking for a
        // file named "file open x.root".
        for (String token : FsSupport.tokenize(rest)) {
            if (token.startsWith("--")) continue;
            return token.toLowerCase(Locale.ROOT).endsWith(".root");
        }
        return false;
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());

        boolean wholeTree = false;
        boolean classesOnly = false;
        boolean rawWalk = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            switch (t) {
                case "--help": usage(); return;
                case "--tree": wholeTree = true; break;
                case "--classes": classesOnly = true; break;
                case "--raw": rawWalk = true; break;
                default:
                    if (t.startsWith("--")) { AppLogger.error("Unknown option: " + t); return; }
                    operands.add(t);
            }
        }

        if (operands.isEmpty()) { usage(); return; }

        Path file = FsSupport.resolve(router, String.join(" ", operands));
        if (!Files.isRegularFile(file)) {
            AppLogger.error("Not a file: " + file);
            return;
        }

        final Path finalFile = file;
        final boolean finalTree = wholeTree;
        final boolean finalClasses = classesOnly;
        final boolean finalRaw = rawWalk;

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try (Reader reader = new Reader(finalFile)) {
                    reader.readHeader();
                    reader.describe(this::publish);
                    if (finalClasses) reader.tallyClasses(this::publish);
                    else if (finalRaw) reader.walkLinear(this::publish);
                    else reader.listDirectories(finalTree, this::publish);
                } catch (NotRootFile e) {
                    publish("[!] " + e.getMessage());
                } catch (IOException e) {
                    publish("[!] Could not read " + finalFile.getFileName() + ": " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else if (line.startsWith("[W] ")) AppLogger.warn(line.substring(4));
                    else AppLogger.raw(line);
                }
            }
        }.execute();
    }

    /* ------------------------------------------------------------------ */

    private static final class NotRootFile extends IOException {
        NotRootFile(String message) { super(message); }
    }

    /** One TKey header, as stored on disk in front of every object. */
    private static final class Key {
        long offset;
        int nbytes;
        short version;
        int objlen;
        int datime;
        short keylen;
        short cycle;
        long seekKey;
        long seekPdir;
        String className = "";
        String name = "";
        String title = "";

        boolean compressed() { return nbytes - keylen != objlen; }
        long payloadAt() { return offset + keylen; }
    }

    private final class Reader implements AutoCloseable {

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

        Reader(Path path) throws IOException {
            this.path = path;
            this.channel = Files.newByteChannel(path);
            this.size = Files.size(path);
        }

        @Override public void close() throws IOException { channel.close(); }

        private ByteBuffer read(long at, int length) throws IOException {
            if (at < 0 || length < 0 || at + length > size) {
                throw new IOException("read past the end of the file at " + at);
            }
            ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
            channel.position(at);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new IOException("file ended early at " + at);
            }
            return buffer.flip();
        }

        void readHeader() throws IOException {
            if (size < 100) throw new NotRootFile("Too short to be a ROOT file: " + size + " bytes");

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

        void describe(java.util.function.Consumer<String> out) {
            int visible = bigFile ? fileVersion - 1_000_000 : fileVersion;
            out.accept("  file          " + path.getFileName());
            out.accept("  size          " + FsSupport.humanBytes(size)
                       + " (" + size + " bytes)");
            out.accept("  written by    ROOT " + rootVersion(visible)
                       + "   (format " + visible + ")");
            out.accept("  pointers      " + (bigFile ? "64 bit, past the 2 GB mark" : units * 8 + " bit"));
            out.accept("  compression   " + compressionName(compress / 100)
                       + ", level " + compress % 100 + "   (fCompress " + compress + ")");
            out.accept("  layout        first record at " + begin + ", end at " + end
                       + (end == size ? "" : "   [W] the file is " + size + " bytes"));
            if (seekInfo > 0 && seekInfo < size) {
                out.accept("  streamer info " + FsSupport.humanBytes(nbytesInfo) + " at " + seekInfo);
            } else {
                out.accept("  streamer info absent");
            }
            if (seekFree > 0 && seekFree < size) {
                out.accept("  free record   at " + seekFree);
            }
            out.accept("");
        }

        /** The key header at an offset. Returns null when it cannot be one. */
        private Key readKey(long at) throws IOException {
            if (at < 0 || at + 18 > size) return null;

            Key k = new Key();
            k.offset = at;
            ByteBuffer fixed = read(at, 18);
            k.nbytes = fixed.getInt();
            k.version = fixed.getShort();
            k.objlen = fixed.getInt();
            k.datime = fixed.getInt();
            k.keylen = fixed.getShort();
            k.cycle = fixed.getShort();

            // A key written past 2 GB carries 8 byte pointers, flagged on the key itself
            boolean wide = k.version > 1000;
            int pointerBytes = wide ? 16 : 8;
            if (k.keylen < 18 + pointerBytes || at + k.keylen > size) return null;

            ByteBuffer rest = read(at + 18, k.keylen - 18);
            k.seekKey = wide ? rest.getLong() : Integer.toUnsignedLong(rest.getInt());
            k.seekPdir = wide ? rest.getLong() : Integer.toUnsignedLong(rest.getInt());
            k.className = readString(rest);
            k.name = readString(rest);
            k.title = readString(rest);
            return k;
        }

        /** A ROOT TString: one length byte, or 255 then a four byte length. */
        private String readString(ByteBuffer buffer) {
            if (!buffer.hasRemaining()) return "";
            int length = buffer.get() & 0xff;
            if (length == 255) {
                if (buffer.remaining() < 4) return "";
                length = buffer.getInt();
            }
            if (length < 0 || length > buffer.remaining()) return "";
            byte[] text = new byte[length];
            buffer.get(text);
            return new String(text, StandardCharsets.UTF_8);
        }

        /**
         * The object behind a key. Uncompressed when nbytes - keylen equals
         * objlen, otherwise a chain of blocks, each behind a nine byte header
         * naming its algorithm, because ROOT splits past 16 MB.
         */
        private byte[] payload(Key k) throws IOException {
            if (k.objlen < 0 || k.objlen > MAX_PAYLOAD) {
                throw new IOException("object of " + k.objlen + " bytes refused");
            }
            int stored = k.nbytes - k.keylen;
            if (stored < 0) throw new IOException("key at " + k.offset + " is malformed");

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

                if (packed <= 0 || at + packed > limit) break;
                if (!"ZL".equals(algorithm)) {
                    // XZ is LZMA, L4 is LZ4, ZS is Zstandard, CS the old routine.
                    // None ships with the JDK, so say so rather than return noise.
                    throw new UnsupportedPayload(algorithmName(algorithm));
                }

                ByteBuffer block = read(at, packed);
                byte[] packedBytes = new byte[packed];
                block.get(packedBytes);
                Inflater inflater = new Inflater();
                try {
                    inflater.setInput(packedBytes);
                    int written = inflater.inflate(result, filled,
                            Math.min(unpacked, result.length - filled));
                    if (written <= 0) break;
                    filled += written;
                } catch (DataFormatException bad) {
                    throw new IOException("compressed block at " + at + " is damaged");
                } finally {
                    inflater.end();
                }
                at += packed;
            }

            if (filled < k.objlen) {
                throw new IOException("object was only partly recovered: "
                                      + filled + " of " + k.objlen + " bytes");
            }
            return result;
        }

        private int u24(ByteBuffer b) {
            // The block header stores its two sizes little endian, unlike the rest
            int a = b.get() & 0xff, c = b.get() & 0xff, d = b.get() & 0xff;
            return a | (c << 8) | (d << 16);
        }

        /**
         * Walks the directory records, which is how ROOT itself navigates: a
         * directory names the record holding its key table, and that table is a
         * count followed by that many key headers.
         */
        void listDirectories(boolean recurse, java.util.function.Consumer<String> out)
                throws IOException {
            Key top = readKey(begin);
            if (top == null) {
                out.accept("[W] The first record is unreadable; showing a linear walk instead.");
                walkLinear(out);
                return;
            }

            long keyTable = topDirectoryKeyTable(top);
            if (keyTable <= 0 || keyTable >= size) {
                out.accept("[W] No key table found; showing a linear walk instead.");
                walkLinear(out);
                return;
            }

            int[] counted = new int[2];
            Set<Long> seen = new HashSet<>();
            listTable(keyTable, "  ", recurse, 0, seen, counted, out);
            out.accept("");
            out.accept("  " + counted[0] + " objects, " + counted[1] + " directories"
                       + (recurse ? "" : "   (--tree for everything below)"));
        }

        /** The top level directory record sits in the payload of the first key. */
        private long topDirectoryKeyTable(Key top) throws IOException {
            // The TFile record repeats the directory fields after the name and title
            long at = top.payloadAt();
            ByteBuffer buffer = read(at, (int) Math.min(top.objlen, 256));
            // fName and fTitle again, then the directory block
            readString(buffer);
            readString(buffer);
            return directoryKeyTable(buffer);
        }

        /** fSeekKeys out of a TDirectory record, in either pointer width. */
        private long directoryKeyTable(ByteBuffer buffer) {
            if (buffer.remaining() < 22) return -1;
            short version = buffer.getShort();
            boolean wide = version > 1000;
            buffer.getInt();                        // fDatimeC
            buffer.getInt();                        // fDatimeM
            buffer.getInt();                        // fNbytesKeys
            buffer.getInt();                        // fNbytesName
            int need = wide ? 24 : 12;
            if (buffer.remaining() < need) return -1;
            if (wide) {
                buffer.getLong();                   // fSeekDir
                buffer.getLong();                   // fSeekParent
                return buffer.getLong();
            }
            buffer.getInt();
            buffer.getInt();
            return Integer.toUnsignedLong(buffer.getInt());
        }

        private void listTable(long tableAt, String indent, boolean recurse, int depth,
                               Set<Long> seen, int[] counted,
                               java.util.function.Consumer<String> out) throws IOException {
            if (depth > MAX_DEPTH || !seen.add(tableAt)) return;

            Key table = readKey(tableAt);
            if (table == null) return;

            byte[] body;
            try {
                body = payload(table);
            } catch (UnsupportedPayload unsupported) {
                out.accept(indent + "[W] key table compressed with " + unsupported.algorithm
                           + ", which this reader cannot inflate");
                return;
            } catch (IOException e) {
                out.accept(indent + "[W] key table unreadable: " + e.getMessage());
                return;
            }

            ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            if (buffer.remaining() < 4) return;
            int count = buffer.getInt();
            if (count < 0 || count > MAX_KEYS) return;

            for (int i = 0; i < count && buffer.remaining() >= 18; i++) {
                Key k = readKeyFrom(buffer);
                if (k == null) break;

                boolean directory = k.className.startsWith("TDirectory");
                if (directory) counted[1]++; else counted[0]++;

                out.accept(String.format("%s%-22s %-30s %10s%s",
                        indent, k.className, trim(k.name, 30),
                        FsSupport.humanBytes(k.objlen),
                        describeExtra(k)));

                if (directory && (recurse || depth == 0)) {
                    long child = childKeyTable(k);
                    if (child > 0 && child < size) {
                        listTable(child, indent + "    ", recurse, depth + 1, seen, counted, out);
                    }
                }
            }
        }

        /** A key header read out of an in memory key table rather than the file. */
        private Key readKeyFrom(ByteBuffer buffer) {
            if (buffer.remaining() < 18) return null;
            Key k = new Key();
            k.nbytes = buffer.getInt();
            k.version = buffer.getShort();
            k.objlen = buffer.getInt();
            k.datime = buffer.getInt();
            k.keylen = buffer.getShort();
            k.cycle = buffer.getShort();
            boolean wide = k.version > 1000;
            if (buffer.remaining() < (wide ? 16 : 8)) return null;
            k.seekKey = wide ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
            k.seekPdir = wide ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
            k.className = readString(buffer);
            k.name = readString(buffer);
            k.title = readString(buffer);
            k.offset = k.seekKey;
            return k;
        }

        private long childKeyTable(Key directoryKey) {
            try {
                Key onDisk = readKey(directoryKey.seekKey);
                if (onDisk == null) return -1;
                byte[] body = payload(onDisk);
                ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
                return directoryKeyTable(buffer);
            } catch (IOException unreadable) {
                return -1;
            }
        }

        /** Every class name the file stores, counted from the key headers alone. */
        void tallyClasses(java.util.function.Consumer<String> out) throws IOException {
            Map<String, int[]> tally = new LinkedHashMap<>();
            long at = begin;
            int keys = 0;

            while (at < end && at < size && keys < MAX_KEYS) {
                Key k = readKey(at);
                if (k == null) break;
                if (k.nbytes < 0) { at += -k.nbytes; continue; }
                if (k.nbytes == 0) break;
                keys++;
                int[] cell = tally.computeIfAbsent(k.className, c -> new int[2]);
                cell[0]++;
                cell[1] += k.objlen;
                at += k.nbytes;
            }

            out.accept(String.format("  %-30s %7s %12s", "class", "count", "uncompressed"));
            tally.entrySet().stream()
                 .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                 .forEach(e -> out.accept(String.format("  %-30s %7d %12s",
                         e.getKey(), e.getValue()[0], FsSupport.humanBytes(e.getValue()[1]))));
            out.accept("");
            out.accept("  " + keys + " records in all");
        }

        /**
         * Every record from the first to the last, ignoring the directory tree.
         * A damaged or half written file still gives up its contents this way,
         * and a negative length marks a gap left by a deleted object.
         */
        void walkLinear(java.util.function.Consumer<String> out) throws IOException {
            long at = begin;
            int keys = 0, gaps = 0;

            while (at < end && at < size && keys < MAX_KEYS) {
                Key k = readKey(at);
                if (k == null) {
                    out.accept("[W] Stopped at " + at + ": the record header is unreadable.");
                    break;
                }
                if (k.nbytes < 0) {
                    gaps++;
                    at += -k.nbytes;
                    continue;
                }
                if (k.nbytes == 0) {
                    out.accept("[W] Stopped at " + at + ": a record of length zero.");
                    break;
                }
                keys++;
                out.accept(String.format("  %10d  %-22s %-30s %10s%s",
                        at, k.className, trim(k.name, 30),
                        FsSupport.humanBytes(k.objlen), describeExtra(k)));
                at += k.nbytes;
            }

            out.accept("");
            out.accept("  " + keys + " records" + (gaps > 0 ? ", " + gaps + " gaps from deletions" : "")
                       + ", walk ended at " + at
                       + (at == end ? " which is exactly the declared end"
                                    : "   [W] the declared end is " + end));
        }

        private String describeExtra(Key k) {
            StringBuilder extra = new StringBuilder();
            if (k.cycle > 1) extra.append("  cycle ").append(k.cycle);
            if (k.className.contains("RNTuple")) {
                extra.append("   RNTuple, not a TTree");
            } else if (k.className.equals("TTree") || k.className.endsWith("::TTree")) {
                extra.append("   branches need the ROOT backend");
            }
            return extra.toString();
        }
    }

    private static final class UnsupportedPayload extends IOException {
        final String algorithm;
        UnsupportedPayload(String algorithm) {
            super("compressed with " + algorithm);
            this.algorithm = algorithm;
        }
    }

    private static String trim(String text, int width) {
        if (text == null) return "";
        return text.length() <= width ? text : text.substring(0, width - 3) + "...";
    }

    /** ROOT stores its version as major*10000 + minor*100 + patch. */
    private static String rootVersion(int packed) {
        if (packed <= 0) return "unknown";
        return packed / 10000 + "." + (packed / 100) % 100 + "." + packed % 100;
    }

    private static String compressionName(int algorithm) {
        switch (algorithm) {
            case 0: return "none or inherited";
            case 1: return "ZLIB";
            case 2: return "LZMA";
            case 3: return "the old ROOT routine";
            case 4: return "LZ4";
            case 5: return "Zstandard";
            default: return "algorithm " + algorithm;
        }
    }

    private static String algorithmName(String tag) {
        switch (tag) {
            case "ZL": return "ZLIB";
            case "XZ": return "LZMA";
            case "L4": return "LZ4";
            case "ZS": return "Zstandard";
            case "CS": return "the old ROOT routine";
            default: return "an unknown algorithm tagged " + tag;
        }
    }

    private void usage() {
        AppLogger.raw("Usage: :root <file.root> [--tree | --classes | --raw]");
        AppLogger.raw("Options:");
        AppLogger.raw("  --tree        descend into every directory");
        AppLogger.raw("  --classes     count the classes the file stores");
        AppLogger.raw("  --raw         every record in file order, for a damaged file");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("Only the container is read, so no ROOT installation is needed.");
        AppLogger.raw("Branches inside a TTree need the ROOT backend.");
    }
}
