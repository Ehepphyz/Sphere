package com.sphere.core.rootlab.suites;

import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootHistogram;
import com.sphere.core.rootio.HttpRangeChannel;
import com.sphere.core.rootio.RBuffer;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootio.RootLocation;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSkip;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * stressIOPlugins for the http protocol: the files ROOT's own test reads from
 * root.cern, opened by Sphere over HTTPS with range requests -- the bytes of
 * the keys and baskets asked for, never the whole file.
 *
 * <ol>
 * <li>stress_2.root: the end of the file and the compression factor, as ROOT checks them;</li>
 * <li>stress_5.root: the functions and histograms the graphics test draws, all decoded;</li>
 * <li>Event_8a.root (no compression) and Event_8b.root (zlib), written without
 *     splitting: every event of both read in full -- tracks, references, bits,
 *     the histogram inside -- and the bytes read must be the same for both;</li>
 * <li>the ways TChain accepts a file name (tree after a slash, a query, a fragment):
 *     each must find the 200 entries of the two files;</li>
 * <li>the first event of Event_8a: 603 tracks, as test 5 requires.</li>
 * </ol>
 */
public final class StressIOPluginsSuite implements LabSuite {

    public static final String BASE = "https://root.cern/files/StressIOPluginsTestFiles/";

    @Override
    public String id() {
        return "stressIOPlugins";
    }

    @Override
    public String title() {
        return "Remote CERN files over HTTP (stressIOPlugins, http)";
    }

    @Override
    public String origin() {
        return "root/test/stressIOPlugins.cxx, protocol http, files from " + BASE;
    }

    @Override
    public String description() {
        return "The test files ROOT reads from root.cern, read by Sphere with HTTP range requests: "
             + "sizes and compression, objects, full event trees in two compression modes, TChain file names.";
    }

    @Override
    public boolean network() {
        return true;
    }

    @Override
    public List<LabTest> tests() {
        return List.of(
            new LabTest("1", "Check size & compression factor of a Root file", StressIOPluginsSuite::test1),
            new LabTest("2", "Read the functions and histograms of the graphics test", StressIOPluginsSuite::test2),
            new LabTest("3", "Trees split and compression modes", StressIOPluginsSuite::test3),
            new LabTest("4", "Filename formats when adding files to TChain", StressIOPluginsSuite::test4),
            new LabTest("5", "Read event 0 (no cache): 603 tracks", StressIOPluginsSuite::test5));
    }

    private static RootIO open(LabContext ctx, String spec) throws Exception {
        try {
            return RootIO.open(spec);
        } catch (IOException unreachable) {
            if (unreachable.getMessage() != null && unreachable.getMessage().contains("HTTP 404")) {
                throw new LabFailure(spec + " is not on the server any more");
            }
            throw new LabSkip("root.cern cannot be reached: " + unreachable.getMessage());
        }
    }

    private static void traffic(LabContext ctx, RootIO io) throws IOException {
        ctx.detail(io.file().getLocation() + ": " + String.format(Locale.ROOT, "%,d bytes", io.file().getSize()));
    }

    /** stressIOPlugins1, after stress2: fEND within 9428 +- 200, compression factor between 2.0 and 2.4. */
    private static void test1(LabContext ctx) throws Exception {
        try (RootIO io = open(ctx, BASE + "stress_2.root")) {
            final RootFile f = io.file();
            final long last = f.getEnd();
            final double comp = f.compressionFactor();
            traffic(ctx, io);
            ctx.detail(String.format(Locale.ROOT, "last = %d (expected 9428 +- 200), comp = %.6f (expected 2.0 to 2.4)",
                last, comp));
            ctx.check(last >= 9428 - 200 && last <= 9428 + 200 && comp >= 2.0 && comp <= 2.4,
                String.format(Locale.ROOT, "last = %d, comp = %f", last, comp));
        }
    }

    /** stressIOPlugins2: the PostScript part has no place here; the objects it draws must read completely. */
    private static void test2(LabContext ctx) throws Exception {
        try (RootIO io = open(ctx, BASE + "stress_5.root")) {
            traffic(ctx, io);
            for (String name : new String[] {"f1form", "f2form", "h1form", "h2form"}) {
                final Object o = io.read(name);
                ctx.check(o instanceof RObject r && !r.has("@unread"), name + " could not be read: " + o);
                final RObject r = (RObject) o;
                ctx.detail(r.className + " " + name + ": " + r.members.size() + " members");
                if (r.className.startsWith("TH")) {
                    final RootHistogram h = RootHistogram.decode(io.file().payload(io.key(name)), r.className);
                    ctx.check(h.consistent, name + " does not decode to its exact length in the viewer");
                    ctx.detail(String.format(Locale.ROOT, "  %s: %d x %d bins, %.0f entries, integral %.6g",
                        name, h.xAxis.bins, Math.max(1, h.yAxis.bins), h.entries, h.integral()));
                    ctx.check(h.entries > 0, name + " is empty");
                }
            }
        }
    }

    /** What reading every entry of the event tree costs, and what it holds. */
    private record Scan(long entries, long bytes, long tracks, long first) {
    }

    private static Scan readAll(LabContext ctx, String file) throws Exception {
        try (RootIO io = open(ctx, BASE + file)) {
            traffic(ctx, io);
            final RTree t = io.tree("T");
            final RTree.RBranch event = t.branch("event");
            ctx.check(event != null, file + " has no branch event");
            long bytes = 0;
            long tracks = 0;
            long first = -1;
            for (long i = 0; i < t.entries(); i++) {
                if (ctx.cancelled()) {
                    throw new LabSkip("cancelled");
                }
                final RBuffer b = event.entry(i);
                bytes += b.remaining();
                final Object o = event.value(i);
                ctx.check(o instanceof RObject, file + ": entry " + i + " did not decode to an Event");
                final RObject e = (RObject) o;
                final int ntrack = e.integer("fNtrack");
                final List<Object> list = e.list("fTracks");
                ctx.check(list.size() == ntrack, file + ": entry " + i + " says " + ntrack + " tracks, holds " + list.size());
                tracks += ntrack;
                if (first < 0) {
                    first = ntrack;
                }
            }
            if (io.file().isRemote()) {
                ctx.detail(file + ": " + t.entries() + " events, " + tracks + " tracks, " + bytes + " bytes of events");
            }
            return new Scan(t.entries(), bytes, tracks, first);
        }
    }

    /** stressIOPlugins3, after stress8: both files read in full; GetEntry's byte counts must agree. */
    private static void test3(LabContext ctx) throws Exception {
        final long t0 = System.nanoTime();
        final Scan a = readAll(ctx, "Event_8a.root");
        final Scan b = readAll(ctx, "Event_8b.root");
        ctx.detail(String.format(Locale.ROOT, "nbr0 = %d, nbr1 = %d; %d and %d tracks; %.1f s", a.bytes(), b.bytes(),
            a.tracks(), b.tracks(), (System.nanoTime() - t0) / 1e9));
        ctx.check(a.bytes() != 0 && a.bytes() == b.bytes(), "nbr0 = " + a.bytes() + ", nbr1 = " + b.bytes());
        ctx.check(a.tracks() == b.tracks(), "the two files hold different events");
    }

    private static long entries(LabContext ctx, String treeName, String... files) throws Exception {
        long total = 0;
        for (String f : files) {
            final RootLocation loc = RootLocation.parse(f);
            final String tree = loc.tree != null ? loc.tree : treeName;
            try (RootIO io = open(ctx, f)) {
                try {
                    total += io.tree(tree).entries();
                } catch (IOException noSuchTree) {
                    // TChain falls back to the name given in the file name; with neither, no entries
                    if (loc.tree == null) {
                        throw new LabFailure(f + ": no tree " + tree);
                    }
                    throw noSuchTree;
                }
            }
        }
        return total;
    }

    /** stressIOPlugins4: the file name formats TChain accepts, each must give 200 entries. */
    private static void test4(LabContext ctx) throws Exception {
        final String a = BASE + "Event_8a.root";
        final String b = BASE + "Event_8b.root";
        final String[][] cases = {
            {"treename in chain", "T", a, b},
            {"treename in filenames, slash-suffix style", "nosuchtree", a + "/T", b + "/T"},
            {"treename and url query in filename", "nosuchtree", a + "/T?myq=xyz", b + "/T?myq=xyz"},
            {"treename given in url frag in filename", "nosuchtree", a + "?myq=xyz#T", b + "?myq=xyz#T"},
            {"filename with a url query in Add", "T", a + "?myq=xyz", b + "?myq=xyz"},
        };
        final StringBuilder failed = new StringBuilder();
        for (String[] c : cases) {
            final long n = entries(ctx, c[1], c[2], c[3]);
            ctx.detail(String.format(Locale.ROOT, "%-45s %d entries", c[0], n));
            if (n != 200) {
                failed.append(c[0]).append(": ").append(n).append(" entries; ");
            }
        }
        ctx.check(failed.length() == 0, failed.toString());
    }

    /** stressIOPlugins5's event check: the first event of Event_8a has 603 tracks. */
    private static void test5(LabContext ctx) throws Exception {
        try (RootIO io = open(ctx, BASE + "Event_8a.root")) {
            final RTree t = io.tree("T");
            ctx.check(t.entries() == 100, "T has " + t.entries() + " entries, expected 100");
            final RObject e = (RObject) t.branch("event").value(0);
            final int n = e.integer("fNtrack");
            final Object name = e.get("fEventName");
            ctx.detail("event 0: " + name + ", " + n + " tracks, temperature " + e.get("fTemperature"));
            if (io.file() != null && io.file().isRemote()) {
                ctx.detail("read through HTTP range requests, without downloading the file");
            }
            ctx.check(n == 603, "event 0 has " + n + " tracks, expected 603");
        }
    }

    /** Whether the server answers at all (for the panel, before a long run). */
    public static boolean reachable() {
        try (HttpRangeChannel c = HttpRangeChannel.open(java.net.URI.create(BASE + "stress_2.root"))) {
            return c.size() > 0;
        } catch (IOException | RuntimeException unreachable) {
            return false;
        }
    }
}
