package com.sphere.core.rootlab.suites;

import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootHistogram;
import com.sphere.components.rootview.RootNode;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The data files of ROOT's test/histviewer: time-of-flight spectra from the
 * December 1998 beam test of HADES at GSI, written by ROOT 2.00/12. Those
 * files predate class descriptions and byte counts -- each class wrote itself
 * by hand -- so they are the oldest ROOT data that can be met, and reading
 * them takes the decoder Sphere keeps for that format.
 *
 * Every histogram is checked against itself: an unweighted histogram's cells,
 * under- and overflow included, add up to its entries, and its in-range cells
 * to the sum of weights it stored; the mean recomputed from the cells agrees
 * with the stored one to within the bin width.
 */
public final class HistViewerSuite implements LabSuite {

    @Override
    public String id() {
        return "histviewer";
    }

    @Override
    public String title() {
        return "GSI beam test data (ROOT 2.00/12, December 1998)";
    }

    @Override
    public String origin() {
        return "root/test/histviewer: hist4_5.root and hist6_8.root";
    }

    @Override
    public String description() {
        return "The oldest ROOT data format: no class descriptions, no byte counts. Every TH1F is decoded "
             + "and checked for internal consistency.";
    }

    @Override
    public List<LabTest> tests() {
        return List.of(
            new LabTest("open45", "Open hist4_5.root (format 2.00/12)", ctx -> header(ctx, "hist4_5.root", 16)),
            new LabTest("decode45", "Decode the TOF spectra of hist4_5.root", ctx -> decode(ctx, "hist4_5.root")),
            new LabTest("open68", "Open hist6_8.root (format 2.00/12)", ctx -> header(ctx, "hist6_8.root", 22)),
            new LabTest("decode68", "Decode the TOF spectra of hist6_8.root", ctx -> decode(ctx, "hist6_8.root")));
    }

    private static void header(LabContext ctx, String name, int expectedKeys) throws Exception {
        try (RootFile f = new RootFile(LabContext.resource(name))) {
            ctx.detail(String.format(Locale.ROOT, "format %d, %d bytes, compression %s level %d",
                f.getFormatVersion(), f.getSize(), RootFile.compressionName(f.getCompressionAlgorithm()),
                f.getCompressionLevel()));
            ctx.check(f.getFormatVersion() == 20012, "format version " + f.getFormatVersion() + ", expected 20012");
            final int keys = f.tree().children.size();
            ctx.detail(keys + " keys");
            ctx.check(keys == expectedKeys, keys + " keys, expected " + expectedKeys);
            ctx.check(f.streamerInfoKey() == null || f.getStreamerInfoBytes() == 0,
                      "a ROOT 2 file should carry no class descriptions");
        }
    }

    private static void decode(LabContext ctx, String name) throws Exception {
        final List<String> problems = new ArrayList<>();
        int checked = 0;
        double totalEntries = 0;
        try (RootFile f = new RootFile(LabContext.resource(name))) {
            for (RootNode n : f.tree().children) {
                if (!n.className.equals("TH1F")) {
                    problems.add(n.name + " is a " + n.className + ", not a TH1F");
                    continue;
                }
                final RootHistogram h = RootHistogram.decode(f.payload(n.key), n.className);
                checked++;
                if (!h.consistent) {
                    problems.add(n.name + ": the record did not decode to its exact length");
                    continue;
                }
                double all = 0;
                for (double c : h.contents) {
                    all += c;
                }
                final double inRange = h.integral();
                totalEntries += h.entries;
                if (Math.abs(all - h.entries) > 1e-6) {
                    problems.add(String.format(Locale.ROOT, "%s: cells add up to %.0f, entries %.0f", n.name, all, h.entries));
                }
                if (Math.abs(inRange - h.tsumw) > 1e-6) {
                    problems.add(String.format(Locale.ROOT, "%s: in-range cells %.0f, stored sum of weights %.0f",
                        n.name, inRange, h.tsumw));
                }
                if (h.tsumw > 0) {
                    double sum = 0;
                    double sumx = 0;
                    for (int i = 1; i <= h.xAxis.bins; i++) {
                        sum += h.content(i);
                        sumx += h.content(i) * h.xAxis.center(i - 1);
                    }
                    final double fromBins = sumx / sum;
                    final double stored = h.tsumwx / h.tsumw;
                    final double width = (h.xAxis.max - h.xAxis.min) / h.xAxis.bins;
                    if (Math.abs(fromBins - stored) > width) {
                        problems.add(String.format(Locale.ROOT, "%s: mean from the cells %.4g, stored %.4g",
                            n.name, fromBins, stored));
                    }
                }
                if (checked <= 3) {
                    ctx.detail(String.format(Locale.ROOT, "%s \"%s\": %d bins [%g, %g], %.0f entries, mean %.4g, rms %.4g",
                        n.name, h.title, h.xAxis.bins, h.xAxis.min, h.xAxis.max, h.entries, h.mean(), h.stdDev()));
                }
            }
        }
        ctx.detail(checked + " histograms, " + String.format(Locale.ROOT, "%.0f", totalEntries) + " entries in all");
        if (!problems.isEmpty()) {
            throw new LabFailure(problems.size() + " inconsistencies", problems);
        }
        ctx.check(checked > 0, "no histogram was found");
    }
}
