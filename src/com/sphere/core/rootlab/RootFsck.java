package com.sphere.core.rootlab;

import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootKey;
import com.sphere.components.rootview.RootNode;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * A file system check for ROOT files: everything a ROOT file says about
 * itself, checked against everything else it says.
 *
 * <ol>
 * <li>The header: where the records start and end, against the file's size
 *     (a file shorter than its fEND was cut off while being copied or written).</li>
 * <li>Every record from fBEGIN to fEND, walked as TFile::Recover walks them:
 *     each key must say where it is, and together the records and the free
 *     gaps must cover the file exactly.</li>
 * <li>Every compressed block decoded, its checksum verified (Adler-32 for
 *     zlib, CRC32 for LZMA, XXH64 for LZ4 and Zstandard).</li>
 * <li>The directories: every key they list must be one of the records found,
 *     and the class descriptions must read.</li>
 * <li>Every object read through its class description, and every basket of
 *     every tree found where its branch says it is.</li>
 * </ol>
 * A file whose key list was never written (a job that crashed) is reported as
 * recoverable, with the objects the record walk still finds.
 */
public final class RootFsck {

    public enum Severity { INFO, WARNING, ERROR }

    /** One thing found: how serious, where in the file, and what. */
    public record Finding(Severity severity, String where, String what) {
        @Override
        public String toString() {
            return severity + "  " + where + ": " + what;
        }
    }

    /** What the check found, with the figures a report needs. */
    public static final class Report {
        public String location;
        public long size;
        public int formatVersion;
        public String compression = "";
        public int records;
        public int gaps;
        public long gapBytes;
        public long recordBytes;
        public int directoryKeys;
        public int objectsRead;
        public int objectsFailed;
        public int trees;
        public int basketsChecked;
        public int basketsFailed;
        public int blocksVerified;
        public double compressionFactor;
        public boolean keyListMissing;
        public final List<RootNode> recovered = new ArrayList<>();
        /** Per algorithm: {blocks, stored bytes, unpacked bytes}. */
        public final Map<String, long[]> algorithms = new TreeMap<>();
        /** Per class: {objects, stored bytes, unpacked bytes}. */
        public final Map<String, long[]> classes = new TreeMap<>();
        public final List<Finding> findings = new ArrayList<>();
        public long nanos;

        void add(Severity s, String where, String what) {
            findings.add(new Finding(s, where, what));
        }

        public long count(Severity s) {
            return findings.stream().filter(f -> f.severity() == s).count();
        }

        /** 100 for a file with nothing to say, less for every warning and much less for every error. */
        public int score() {
            final long e = count(Severity.ERROR);
            final long w = count(Severity.WARNING);
            return (int) Math.max(0, 100 - 25 * Math.min(4, e) - 3 * Math.min(10, w));
        }

        public String verdict() {
            if (count(Severity.ERROR) > 0) {
                return keyListMissing ? "damaged, but recoverable" : "damaged";
            }
            return count(Severity.WARNING) > 0 ? "readable, with warnings" : "healthy";
        }

        public String text() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "%s%n  %,d bytes, ROOT format %d, %s%n", location, size,
                formatVersion, compression));
            b.append(String.format(Locale.ROOT, "  health %d/100 (%s), checked in %.2f s%n", score(), verdict(), nanos / 1e9));
            b.append(String.format(Locale.ROOT,
                "  %d records (%,d bytes) and %d free gaps (%,d bytes); %d keys in the directories%n",
                records, recordBytes, gaps, gapBytes, directoryKeys));
            b.append(String.format(Locale.ROOT, "  %d compressed blocks verified; compression factor %.3f%n",
                blocksVerified, compressionFactor));
            b.append(String.format(Locale.ROOT, "  %d objects read, %d not; %d trees, %d baskets checked, %d bad%n",
                objectsRead, objectsFailed, trees, basketsChecked, basketsFailed));
            if (!algorithms.isEmpty()) {
                b.append("  algorithms:");
                algorithms.forEach((k, v) -> b.append(String.format(Locale.ROOT, "  %s x%d (%.2f)", k, v[0],
                    v[1] == 0 ? 0.0 : (double) v[2] / v[1])));
                b.append('\n');
            }
            for (Finding f : findings) {
                b.append("  ").append(f).append('\n');
            }
            if (keyListMissing && !recovered.isEmpty()) {
                b.append("  recoverable objects:\n");
                for (RootNode n : recovered) {
                    b.append("    ").append(n.className).append(' ').append(n.name).append('\n');
                }
            }
            return b.toString();
        }
    }

    /** How far to go: the records and blocks always; objects and baskets when deep. */
    public record Options(boolean verifyBlocks, boolean decodeObjects, boolean checkBaskets, int maxBasketsPerBranch) {
        /** The header, the records and the directories: reads key headers only (fast, even over HTTP). */
        public static Options quick() {
            return new Options(false, false, false, 0);
        }

        /** Everything: every block decoded and its checksum verified, every object and basket read. */
        public static Options full() {
            return new Options(true, true, true, Integer.MAX_VALUE);
        }
    }

    private RootFsck() {
    }

    public static Report check(RootFile f, Options options, DoubleConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        final long t0 = System.nanoTime();
        final Report r = new Report();
        final DoubleConsumer p = progress == null ? x -> { } : progress;
        final BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        r.location = f.getLocation();
        r.size = f.getSize();
        r.formatVersion = f.getFormatVersion();
        r.compression = RootFile.compressionName(f.getCompressionAlgorithm()) + " level " + f.getCompressionLevel();

        // ---- 1. header
        if (f.getEnd() > f.getSize()) {
            r.add(Severity.ERROR, "header", String.format(Locale.ROOT,
                "the file ends at %,d bytes but says its records go to %,d: it was cut off", f.getSize(), f.getEnd()));
        } else if (f.getEnd() < f.getSize()) {
            r.add(Severity.WARNING, "header", String.format(Locale.ROOT,
                "%,d bytes after the end the header declares", f.getSize() - f.getEnd()));
        }
        if (f.getBegin() != 100) {
            r.add(Severity.INFO, "header", "the first record is at " + f.getBegin() + " rather than 100");
        }
        if (f.getStreamerInfoSeek() <= 0) {
            r.add(f.getFormatVersion() < 30000 ? Severity.INFO : Severity.WARNING, "header",
                f.getFormatVersion() < 30000 ? "no class descriptions (normal for a ROOT 2 file)"
                                             : "no class descriptions: the file was not closed, or written without them");
        } else if (f.getStreamerInfoSeek() >= f.getSize()) {
            r.add(Severity.ERROR, "header", "the class descriptions are said to be past the end of the file");
        }
        if (f.isBigFile() != (f.getEnd() > 2_000_000_000L) && f.getEnd() > 2_000_000_000L) {
            r.add(Severity.WARNING, "header", "a file past 2 GB written with 32-bit pointers");
        }

        // ---- 2. records
        final RootFile.RecordScan scan = f.scanRecords();
        final Map<Long, RootKey> byOffset = new HashMap<>();
        long covered = f.getBegin();
        final long totalSpan = Math.max(1, Math.min(f.getEnd(), f.getSize()) - f.getBegin());
        int done = 0;
        for (RootFile.Record rec : scan.records) {
            if (stop.getAsBoolean()) {
                break;
            }
            covered += rec.nbytes;
            if (rec.gap) {
                r.gaps++;
                r.gapBytes += rec.nbytes;
                continue;
            }
            r.records++;
            r.recordBytes += rec.nbytes;
            final RootKey k = rec.key;
            byOffset.put(rec.offset, k);
            final String where = String.format(Locale.ROOT, "%s %s;%d @%,d", k.className, k.name, k.cycle, rec.offset);
            if (k.seekKey != rec.offset && k.seekKey != 0) {
                r.add(Severity.ERROR, where, "the key says it is at " + k.seekKey);
            }
            if (k.objlen < k.storedBytes() && k.compressed()) {
                r.add(Severity.WARNING, where, "compressed, yet larger than its object");
            }
            // ---- 3. every compressed block
            if (k.compressed() && options.verifyBlocks()) {
                try {
                    final String algo = blockAlgorithm(f, k);
                    f.payload(k);
                    r.blocksVerified++;
                    final long[] a = r.algorithms.computeIfAbsent(algo, x -> new long[3]);
                    a[0]++;
                    a[1] += k.storedBytes();
                    a[2] += k.objlen;
                } catch (IOException bad) {
                    r.add(Severity.ERROR, where, bad.getMessage());
                }
            }
            final long[] c = r.classes.computeIfAbsent(k.className, x -> new long[3]);
            c[0]++;
            c[1] += k.storedBytes();
            c[2] += k.objlen;
            if ((++done & 255) == 0) {
                p.accept(0.6 * (rec.offset - f.getBegin()) / totalSpan);
            }
        }
        if (scan.stoppedBecause != null) {
            r.add(Severity.ERROR, String.format(Locale.ROOT, "@%,d", scan.stoppedAt), "record walk stopped: "
                  + scan.stoppedBecause);
        } else if (covered != Math.min(f.getEnd(), f.getSize()) && f.getEnd() <= f.getSize()) {
            r.add(Severity.ERROR, "records", String.format(Locale.ROOT,
                "the records cover up to %,d, the header says %,d", covered, f.getEnd()));
        }
        try {
            r.compressionFactor = f.compressionFactor();
        } catch (IOException unreadable) {
            r.compressionFactor = 0;
        }

        // ---- 4. directories and class descriptions
        long keyList = -1;
        try {
            keyList = f.topKeyListSeek();
        } catch (IOException unreadable) {
            r.add(Severity.ERROR, "top directory", unreadable.getMessage());
        }
        if (keyList <= 0) {
            r.keyListMissing = true;
            r.add(Severity.ERROR, "top directory", "the list of keys was never written (the file was not closed)");
            recover(r, scan);
        } else {
            final RootNode tree = f.tree();
            final Set<Long> listed = new HashSet<>();
            walk(tree, r, byOffset, listed, "");
            if (!byOffset.isEmpty() && r.directoryKeys == 0) {
                r.add(Severity.ERROR, "top directory", "the key list is empty although records exist");
                recover(r, scan);
            }
        }
        RootIO io = null;
        if (f.getStreamerInfoSeek() > 0) {
            try {
                io = RootIO.of(f);
                final int n = io.streamers().all().size();
                r.add(Severity.INFO, "class descriptions", n + " classes described");
            } catch (IOException | RuntimeException unreadable) {
                r.add(Severity.ERROR, "class descriptions", "unreadable: " + unreadable.getMessage());
                io = null;
            }
        }
        p.accept(0.7);

        // ---- 5. objects and trees
        if (options.decodeObjects() && io != null && !stop.getAsBoolean()) {
            final List<RootNode> objects = new ArrayList<>();
            collect(f.tree(), objects);
            int i = 0;
            for (RootNode n : objects) {
                if (stop.getAsBoolean()) {
                    break;
                }
                p.accept(0.7 + 0.3 * (i++) / Math.max(1, objects.size()));
                final String where = n.className + " " + n.name + ";" + n.key.cycle;
                try {
                    final Object o = io.read(n.key);
                    if (o instanceof RObject ro && ro.has("@unread")) {
                        r.objectsFailed++;
                        r.add(Severity.WARNING, where, String.valueOf(ro.get("@unread")));
                        continue;
                    }
                    r.objectsRead++;
                    if (options.checkBaskets() && n.isTree()) {
                        checkTree(r, f, io, n, options, stop);
                    }
                } catch (IOException | RuntimeException failed) {
                    r.objectsFailed++;
                    r.add(Severity.WARNING, where, "not readable by Sphere's reader: " + failed.getMessage());
                }
            }
        }
        p.accept(1.0);
        r.nanos = System.nanoTime() - t0;
        return r;
    }

    private static String blockAlgorithm(RootFile f, RootKey k) throws IOException {
        final byte[] head = f.rawAt(k.payloadAt(), Math.min(9, k.storedBytes()));
        return head.length >= 2 ? RootFile.algorithmName(new String(head, 0, 2, java.nio.charset.StandardCharsets.US_ASCII))
                                : "?";
    }

    private static void walk(RootNode dir, Report r, Map<Long, RootKey> byOffset, Set<Long> listed, String path) {
        final Map<String, Integer> names = new LinkedHashMap<>();
        for (RootNode n : dir.children) {
            if (n.key == null) {
                continue;
            }
            r.directoryKeys++;
            final String where = (path.isEmpty() ? "" : path + "/") + n.name + ";" + n.key.cycle;
            names.merge(n.name + ";" + n.key.cycle, 1, Integer::sum);
            if (!byOffset.isEmpty() && !byOffset.containsKey(n.key.seekKey)) {
                r.add(Severity.ERROR, where, "listed at " + n.key.seekKey + ", where no record starts");
            }
            listed.add(n.key.seekKey);
            if (n.note != null) {
                r.add(Severity.ERROR, where, n.note);
            }
            if (n.directory) {
                walk(n, r, byOffset, listed, where.substring(0, where.lastIndexOf(';')));
            }
        }
        names.forEach((name, count) -> {
            if (count > 1) {
                r.add(Severity.WARNING, (path.isEmpty() ? "/" : path), name + " is listed " + count + " times");
            }
        });
    }

    private static void collect(RootNode node, List<RootNode> out) {
        for (RootNode c : node.children) {
            if (c.directory) {
                collect(c, out);
            } else if (c.key != null) {
                out.add(c);
            }
        }
    }

    /** The objects a record walk finds when the directories cannot be used. */
    private static void recover(Report r, RootFile.RecordScan scan) {
        for (RootFile.Record rec : scan.records) {
            if (rec.gap || rec.key == null) {
                continue;
            }
            final String cls = rec.key.className;
            if (cls.equals("TFile") || cls.equals("TBasket") || cls.equals("KeysList") || cls.equals("FreeSegments")
                || cls.equals("StreamerInfo") || cls.equals("TList") && rec.key.name.equals("StreamerInfo")) {
                continue;
            }
            final RootNode n = new RootNode(rec.key.name, rec.key.title, cls, rec.key);
            n.directory = cls.startsWith("TDirectory");
            r.recovered.add(n);
        }
        if (!r.recovered.isEmpty()) {
            r.add(Severity.INFO, "recovery", r.recovered.size() + " objects can still be read from their records");
        }
    }

    private static void checkTree(Report r, RootFile f, RootIO io, RootNode n, Options options, BooleanSupplier stop) {
        r.trees++;
        final String where = "TTree " + n.name;
        try {
            final RTree t = io.tree(n.name + ";" + n.key.cycle);
            long entries = t.entries();
            for (RTree.RBranch b : t.allBranches()) {
                if (stop.getAsBoolean()) {
                    return;
                }
                if (!b.hasData()) {
                    continue;
                }
                final long[] seeks = b.object().longs("fBasketSeek");
                final long[] firsts = b.object().longs("fBasketEntry");
                int checked = 0;
                for (int i = 0; i < seeks.length && checked < options.maxBasketsPerBranch(); i++) {
                    if (seeks[i] == 0) {
                        continue;
                    }
                    checked++;
                    r.basketsChecked++;
                    try {
                        final RootKey k = f.keyAt(seeks[i]);
                        if (k == null || !k.className.equals("TBasket")) {
                            r.basketsFailed++;
                            r.add(Severity.ERROR, where + "." + b.name(), "no basket at " + seeks[i]);
                            continue;
                        }
                        f.payload(k);
                    } catch (IOException bad) {
                        r.basketsFailed++;
                        r.add(Severity.ERROR, where + "." + b.name(), "basket at " + seeks[i] + ": " + bad.getMessage());
                    }
                }
                // the entries a branch can reach must cover the tree's
                if (b.entries() != entries && b.entries() > 0 && entries > 0) {
                    r.add(Severity.WARNING, where + "." + b.name(), b.entries() + " entries, the tree has " + entries);
                }
                if (firsts.length > 0 && seeks.length > 0) {
                    for (int i = 1; i < Math.min(firsts.length, seeks.length); i++) {
                        if (seeks[i] != 0 && firsts[i] < firsts[i - 1]) {
                            r.add(Severity.ERROR, where + "." + b.name(), "basket entry numbers go backwards at basket " + i);
                            break;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            r.add(Severity.WARNING, where, "the tree could not be walked: " + unreadable.getMessage());
        }
    }
}
