package com.sphere.core.rootlab;

import com.sphere.components.rootview.RootNode;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootlab.hist.HistCompare;
import com.sphere.core.rootlab.hist.LabHist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Two ROOT files compared histogram by histogram, the way a validation of a
 * new release or of new conditions is done at the LHC: every histogram of one
 * file paired with the one of the same path in the other, and for each pair
 * the probability that both come from the same distribution (Chi2Test, which
 * picks unweighted or weighted as ROOT does, and the Kolmogorov test for one
 * dimension). A pair below the threshold is flagged; histograms present on
 * one side only are listed.
 */
public final class FileCompare {

    /** One histogram compared: its path, the class, and the two p-values (NaN when not computed). */
    public record Row(String path, String className, double chi2Prob, double chi2, int ndf, double ksProb,
                      double entriesA, double entriesB, String note) {
        public boolean flagged(double threshold) {
            return (!Double.isNaN(chi2Prob) && chi2Prob < threshold) || (!Double.isNaN(ksProb) && ksProb < threshold);
        }
    }

    public static final class Result {
        public final List<Row> rows = new ArrayList<>();
        public final List<String> onlyInA = new ArrayList<>();
        public final List<String> onlyInB = new ArrayList<>();
        public double threshold = 0.01;

        public long flagged() {
            return rows.stream().filter(r -> r.flagged(threshold)).count();
        }

        public String text(String nameA, String nameB) {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "%s  vs  %s%n%d histograms compared, %d below p = %.3g%n",
                nameA, nameB, rows.size(), flagged(), threshold));
            b.append(String.format(Locale.ROOT, "%-40s %-10s %12s %12s %10s %10s%n", "path", "class", "chi2 prob",
                "KS prob", "entries A", "entries B"));
            for (Row r : rows) {
                b.append(String.format(Locale.ROOT, "%-40s %-10s %12.4g %12.4g %10.0f %10.0f %s%s%n",
                    shorten(r.path(), 40), r.className(), r.chi2Prob(), r.ksProb(), r.entriesA(), r.entriesB(),
                    r.flagged(threshold) ? "  <== differs" : "", r.note().isEmpty() ? "" : "  (" + r.note() + ")"));
            }
            for (String s : onlyInA) {
                b.append("only in A: ").append(s).append('\n');
            }
            for (String s : onlyInB) {
                b.append("only in B: ").append(s).append('\n');
            }
            return b.toString();
        }

        private static String shorten(String s, int n) {
            return s.length() <= n ? s : "..." + s.substring(s.length() - n + 3);
        }
    }

    private FileCompare() {
    }

    public static Result compare(RootIO a, RootIO b, BooleanSupplier cancelled) throws java.io.IOException {
        final Map<String, RootNode> histA = histograms(a);
        final Map<String, RootNode> histB = histograms(b);
        final Result result = new Result();
        for (Map.Entry<String, RootNode> e : histA.entrySet()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                break;
            }
            final RootNode nb = histB.get(e.getKey());
            if (nb == null) {
                result.onlyInA.add(e.getKey());
                continue;
            }
            result.rows.add(row(e.getKey(), a, e.getValue(), b, nb));
        }
        for (String path : histB.keySet()) {
            if (!histA.containsKey(path)) {
                result.onlyInB.add(path);
            }
        }
        return result;
    }

    /**
     * A histogram of a file, through its class description, or for a file
     * written before class descriptions existed (ROOT 2), through the viewer's
     * decoder of that format.
     */
    public static LabHist readHistogram(RootIO io, RootNode node) throws java.io.IOException {
        final Object o = io.read(node.key);
        if (o instanceof RObject r && !r.has("@unread")) {
            return LabHist.fromObject(r);
        }
        final com.sphere.components.rootview.RootHistogram legacy =
            com.sphere.components.rootview.RootHistogram.decode(io.file().payload(node.key), node.className);
        if (!legacy.consistent || legacy.dimensions != 1) {
            throw new java.io.IOException(node.className + " " + node.name + " could not be decoded");
        }
        final com.sphere.core.rootlab.hist.LabAxis axis = legacy.xAxis.isVariable()
            ? new com.sphere.core.rootlab.hist.LabAxis(legacy.xAxis.bins, legacy.xAxis.min, legacy.xAxis.max, legacy.xAxis.edges)
            : new com.sphere.core.rootlab.hist.LabAxis(legacy.xAxis.bins, legacy.xAxis.min, legacy.xAxis.max);
        final LabHist h = new LabHist(node.className, node.name, legacy.title, axis, null, null);
        System.arraycopy(legacy.contents, 0, h.array, 0, Math.min(legacy.contents.length, h.array.length));
        if (legacy.sumw2.length == h.array.length) {
            h.sumw2 = legacy.sumw2.clone();
        }
        h.entries = legacy.entries;
        h.tsumw = legacy.tsumw;
        h.tsumw2 = legacy.tsumw2;
        h.tsumwx = legacy.tsumwx;
        h.tsumwx2 = legacy.tsumwx2;
        return h;
    }

    private static Row row(String path, RootIO a, RootNode na, RootIO b, RootNode nb) {
        try {
            final LabHist ha = readHistogram(a, na);
            final LabHist hb = readHistogram(b, nb);
            if (ha.dimension != hb.dimension || ha.xaxis.nbins != hb.xaxis.nbins
                || ha.yaxis.nbins != hb.yaxis.nbins || ha.zaxis.nbins != hb.zaxis.nbins) {
                return new Row(path, na.className, Double.NaN, Double.NaN, 0, Double.NaN, ha.entries, hb.entries,
                    "different binning");
            }
            final HistCompare.Chi2 c = HistCompare.chi2TestX(ha, hb, "");
            final double ks = ha.dimension == 1 ? HistCompare.kolmogorov(ha, hb) : Double.NaN;
            return new Row(path, na.className, c.prob(), c.chi2(), c.ndf(), ks, ha.entries, hb.entries, "");
        } catch (Exception failed) {
            return new Row(path, na.className, Double.NaN, Double.NaN, 0, Double.NaN, 0, 0,
                failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    /** Every histogram of a file by path, the highest cycle only. */
    static Map<String, RootNode> histograms(RootIO io) throws java.io.IOException {
        final Map<String, RootNode> out = new LinkedHashMap<>();
        collect(io.file().tree(), "", out);
        return out;
    }

    private static void collect(RootNode dir, String prefix, Map<String, RootNode> out) {
        for (RootNode n : dir.children) {
            final String path = prefix.isEmpty() ? n.name : prefix + "/" + n.name;
            if (n.directory) {
                collect(n, path, out);
            } else if (n.isHistogram() && n.key != null) {
                final RootNode prev = out.get(path);
                if (prev == null || prev.key.cycle < n.key.cycle) {
                    out.put(path, n);
                }
            }
        }
    }
}
