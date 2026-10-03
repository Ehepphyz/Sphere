package com.sphere.core.bridge;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.contrib.EventBatch;
import com.sphere.core.fastjet.io.EventIO;
import com.sphere.core.rootbackend.RootPdfAlphaS;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Sphere's own engines hand to the others: a PDF set as its grids, and an
 * event sample with the jets found in it.
 *
 * A PDF goes over as knots and values, not as numbers already interpolated,
 * because every reader carries the same log-bicubic interpolator LHAPDF uses.
 * The logarithms of the knots go too, computed here once, so that no reader's
 * own logarithm can move a knot by an ulp: what the readers compute is then the
 * same arithmetic on the same bits, and they agree with Java to the last digit
 * rather than merely closely. {@code :bridge crosscheck} is what shows it.
 */
public final class SpxExport {

    private SpxExport() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* PDF sets                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Writes every member the set holds open.
     *
     * The values are laid out member, then x, then Q, then flavour, the last
     * running fastest: the order the Java grid keeps them in, and the one a
     * column-major reader sees as an array (flavour, Q, x, member).
     */
    public static Path pdf(RootPdfSet set, Path file) throws IOException {
        final RootPdfGrid first = set.member(0);
        if (first.interpolation() != RootPdfGrid.Interpolation.LOG_BICUBIC) {
            throw new IOException(set.name() + " is interpolated " + first.interpolation()
                + "; the bridge carries LHAPDF's log-bicubic only.");
        }
        final double[] xs = first.xKnotValues();
        final double[] q2s = first.q2KnotValues();
        final int[] pids = first.flavors();
        final int members = set.memberCount();
        for (int m = 1; m < members; m++) {
            final RootPdfGrid g = set.member(m);
            if (!Arrays.equals(g.xKnotValues(), xs) || !Arrays.equals(g.q2KnotValues(), q2s)
                || !Arrays.equals(g.flavors(), pids)) {
                throw new IOException("Member " + m + " of " + set.name()
                    + " has knots or flavours of its own; LHAPDF sets share them, and so must the bridge.");
            }
        }
        final int nx = xs.length;
        final int nq = q2s.length;
        final int nf = pids.length;

        final double[] logx = new double[nx];
        for (int i = 0; i < nx; i++) logx[i] = Math.log(xs[i]);
        final double[] logq2 = new double[nq];
        for (int i = 0; i < nq; i++) logq2[i] = Math.log(q2s[i]);
        final long[] codes = new long[nf];
        for (int f = 0; f < nf; f++) codes[f] = pids[f];

        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("SetName", set.name());
        meta.put("SetDesc", set.meta("SetDesc", "").replace('\n', ' '));
        meta.put("Members", Integer.toString(members));
        meta.put("NumMembers", set.meta("NumMembers", Integer.toString(members)));
        meta.put("ErrorType", errorName(set.errorType()));
        meta.put("ErrorConfLevel", fmt(set.confidenceLevel()));
        meta.put("Accuracy", first.accuracy() == RootPdfGrid.Accuracy.WEIGHTED ? "weighted" : "lhapdf");
        meta.put("Interpolation", "logbicubic");
        meta.put("Extrapolation", first.extrapolation().name().toLowerCase(Locale.ROOT));
        meta.put("XMin", fmt(first.xMin()));
        meta.put("XMax", fmt(first.xMax()));
        meta.put("QMin", fmt(first.qMin()));
        meta.put("QMax", fmt(first.qMax()));
        meta.put("Source", String.valueOf(set.folder()));

        final Spx.Writer out = new Spx.Writer(Spx.Kind.PDF, set.name());
        final double[][] alphaS = alphaTable(set, first);
        if (alphaS != null) {
            meta.put("AlphaS", alphaS[2][0] == 1 ? "table of the set (ipol)" : "tabulated by Sphere from its "
                + set.alphaS().type().name().toLowerCase(Locale.ROOT) + " coupling");
            meta.put("AlphaS_MZ", fmt(set.alphaS().alphaSmZ()));
            // Above its table LHAPDF holds the last value; Sphere, unless told to
            // match LHAPDF, carries the slope on. The readers do what Java does.
            meta.put("AlphaS_Above", set.alphaS().matchesLhapdf() ? "hold" : "continue");
        } else {
            meta.put("AlphaS", "none");
        }
        out.meta(meta);
        out.i64("shape", new long[]{nf, nq, nx, members});
        out.i64("pids", codes);
        out.f64("xknots", xs);
        out.f64("q2knots", q2s);
        out.f64("logx", logx);
        out.f64("logq2", logq2);
        out.f64("xf", (long) members * nx * nq * nf, put -> {
            for (int m = 0; m < members; m++) {
                final RootPdfGrid g = set.member(m);
                for (int ix = 0; ix < nx; ix++) {
                    for (int iq = 0; iq < nq; iq++) {
                        for (int f = 0; f < nf; f++) put.accept(g.xf(ix, iq, f));
                    }
                }
            }
        });
        if (alphaS != null) {
            final double[] logs = new double[alphaS[0].length];
            for (int i = 0; i < logs.length; i++) logs[i] = Math.log(alphaS[0][i]);
            out.f64("as_q2", alphaS[0]);
            out.f64("as_logq2", logs);
            out.f64("as_val", alphaS[1]);
        }
        return out.write(file);
    }

    /**
     * The coupling as a table the readers interpolate the LHAPDF way.
     *
     * A set whose coupling is itself a table ships that table, and then every
     * reader answers what LHAPDF answers. An analytic or solved coupling is
     * sampled here, forty points to the unit of log Q squared and a seam at
     * each quark threshold so that no interval straddles a kink; the readers
     * then agree with Java to about one part in 10^9, which the cross-check
     * reports rather than hides.
     */
    private static double[][] alphaTable(RootPdfSet set, RootPdfGrid grid) {
        final RootPdfAlphaS as = set.alphaS();
        if (as == null) return null;
        if (as.type() == RootPdfAlphaS.Type.IPOL) {
            final double[] qs = list(set.meta("AlphaS_Qs", ""));
            final double[] vals = list(set.meta("AlphaS_Vals", ""));
            if (qs.length > 1 && qs.length == vals.length) {
                final double[] q2 = new double[qs.length];
                for (int i = 0; i < qs.length; i++) q2[i] = qs[i] * qs[i];
                return new double[][]{q2, vals, {1}};
            }
        }
        final double low = Math.log(grid.q2Min());
        final double high = Math.log(grid.q2Max());
        final List<Double> seams = new ArrayList<>();
        for (double q : as.thresholdScales()) {
            final double l = Math.log(q * q);
            if (l > low && l < high) seams.add(l);
        }
        final List<Double> q2 = new ArrayList<>();
        final List<Double> value = new ArrayList<>();
        double start = low;
        for (int piece = 0; piece <= seams.size(); piece++) {
            final double end = piece < seams.size() ? seams.get(piece) : high;
            final int n = Math.max(4, (int) Math.ceil((end - start) * 40));
            for (int k = 0; k <= n; k++) {
                final double at = Math.exp(start + (end - start) * k / n);
                q2.add(at);
                value.add(as.alphasQ2(at));
            }
            start = end;
        }
        final double[] a = new double[q2.size()];
        final double[] v = new double[q2.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = q2.get(i);
            v[i] = value.get(i);
            if (!Double.isFinite(v[i])) return null;
        }
        return new double[][]{a, v, {0}};
    }

    private static double[] list(String text) {
        final String bare = text.replace("[", "").replace("]", "").trim();
        if (bare.isEmpty()) return new double[0];
        final String[] words = bare.split("[,\\s]+");
        final double[] out = new double[words.length];
        try {
            for (int i = 0; i < words.length; i++) out[i] = Double.parseDouble(words[i]);
        } catch (NumberFormatException e) {
            return new double[0];
        }
        return out;
    }

    public static String errorName(RootPdfSet.Errors errors) {
        return switch (errors) {
            case HESSIAN -> "hessian";
            case SYMMETRIC_HESSIAN -> "symmhessian";
            case REPLICAS -> "replicas";
            case NONE -> "none";
        };
    }

    /* ------------------------------------------------------------------ */
    /* Events and jets                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Writes an event sample, and the jets of every event when a definition is
     * given.
     *
     * Particles are flattened into one array with an offset per event, which
     * is what a columnar reader wants and what a ROOT tree of vectors is made
     * from. Each particle carries the index of the jet it ended up in (-1 when
     * it was left out by the pt cut), so a reader can rebuild any jet's
     * constituents without a second clustering.
     */
    public static Path events(List<List<PseudoJet>> events, List<Double> weights,
                              List<EventIO.Incoming> incoming, JetDefinition def, double ptmin,
                              String source, int threads, Path file) throws IOException {
        final int nev = events.size();
        final long[] evtOffset = new long[nev + 1];
        for (int e = 0; e < nev; e++) evtOffset[e + 1] = evtOffset[e] + events.get(e).size();
        final int np = (int) evtOffset[nev];

        final double[] p4 = new double[np * 4];
        final long[] pdg = new long[np];
        for (int e = 0; e < nev; e++) {
            int at = (int) evtOffset[e];
            for (PseudoJet p : events.get(e)) {
                p4[at * 4] = p.px();
                p4[at * 4 + 1] = p.py();
                p4[at * 4 + 2] = p.pz();
                p4[at * 4 + 3] = p.E();
                pdg[at] = p.userIndex();
                at++;
            }
        }
        final double[] w = new double[nev];
        for (int e = 0; e < nev; e++) w[e] = e < weights.size() ? weights.get(e) : 1.0;

        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Events", Integer.toString(nev));
        meta.put("Particles", Integer.toString(np));
        meta.put("Source", source == null ? "" : source);

        final Spx.Writer out = new Spx.Writer(Spx.Kind.EVENTS, source == null ? "events"
            : Path.of(source).getFileName().toString());
        out.i64("evt_offset", evtOffset);
        out.f64("p4", p4);
        out.i64("pdg", pdg);
        out.f64("weight", w);

        boolean haveIncoming = incoming != null && incoming.size() == nev;
        if (haveIncoming) {
            for (EventIO.Incoming in : incoming) {
                if (in == null) {
                    haveIncoming = false;
                    break;
                }
            }
        }
        if (haveIncoming) {
            final double[] inc = new double[nev * 5];
            for (int e = 0; e < nev; e++) {
                final EventIO.Incoming in = incoming.get(e);
                inc[e * 5] = in.id1();
                inc[e * 5 + 1] = in.id2();
                inc[e * 5 + 2] = in.x1();
                inc[e * 5 + 3] = in.x2();
                inc[e * 5 + 4] = in.scale();
            }
            out.f64("incoming", inc);
            meta.put("Incoming", "id1 id2 x1 x2 scale, per event");
        }

        if (def != null) {
            final List<ClusterSequence> clustered = EventBatch.cluster(events, def, threads);
            final long[] jetOffset = new long[nev + 1];
            final List<double[]> jets = new ArrayList<>();
            final long[] jetOf = new long[np];
            Arrays.fill(jetOf, -1);
            for (int e = 0; e < nev; e++) {
                final List<PseudoJet> found = PseudoJet.sortedByPt(clustered.get(e).inclusiveJets(ptmin));
                final int inEvent = events.get(e).size();
                for (int k = 0; k < found.size(); k++) {
                    final PseudoJet j = found.get(k);
                    jets.add(new double[]{j.px(), j.py(), j.pz(), j.E()});
                    // The inputs are the first entries of the history, in the
                    // order they were given, so a constituent's history index
                    // is its place in the event.
                    for (PseudoJet c : j.constituents()) {
                        final int h = c.clusterHistIndex();
                        if (h >= 0 && h < inEvent) jetOf[(int) evtOffset[e] + h] = k;
                    }
                }
                jetOffset[e + 1] = jetOffset[e] + found.size();
            }
            final double[] jp4 = new double[jets.size() * 4];
            for (int k = 0; k < jets.size(); k++) System.arraycopy(jets.get(k), 0, jp4, k * 4, 4);
            out.i64("jet_offset", jetOffset);
            out.f64("jet_p4", jp4);
            out.i64("jet_of", jetOf);
            meta.put("Jets", Integer.toString(jets.size()));
            meta.put("JetDefinition", def.description().replace('\n', ' '));
            meta.put("JetPtMin", fmt(ptmin));
        }
        out.meta(meta);
        return out.write(file);
    }

    /* ------------------------------------------------------------------ */
    /* Tables                                                              */
    /* ------------------------------------------------------------------ */

    /** A histogram: edges, values and, when given, the band around them. */
    public static Path histogram(String title, String xLabel, double[] edges, double[] values,
                                 double[] errPlus, double[] errMinus, Map<String, double[]> extra,
                                 Path file) throws IOException {
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Title", title);
        meta.put("XLabel", xLabel);
        meta.put("Kind", "histogram");
        meta.put("Engine", "Sphere");
        final Spx.Writer out = new Spx.Writer(Spx.Kind.TABLE, title);
        out.meta(meta);
        out.f64("edges", edges);
        out.f64("values", values);
        if (errPlus != null) out.f64("err_plus", errPlus);
        if (errMinus != null) out.f64("err_minus", errMinus);
        if (extra != null) {
            for (Map.Entry<String, double[]> e : extra.entrySet()) out.f64(e.getKey(), e.getValue());
        }
        return out.write(file);
    }

    /** The shortest decimal that reads back to the same double, in every reader. */
    static String fmt(double v) {
        return Double.toString(v);
    }

    /** A file name from a set or sample name, safe on every system. */
    public static String safeName(String name) {
        final String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "unnamed" : s;
    }

    /** True when the file exists and is newer than every file in the folder. */
    static boolean fresh(Path spx, Path folder) {
        try {
            if (!Files.isRegularFile(spx) || folder == null || !Files.isDirectory(folder)) return false;
            final long written = Files.getLastModifiedTime(spx).toMillis();
            try (var files = Files.list(folder)) {
                return files.allMatch(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis() <= written;
                    } catch (IOException e) {
                        return false;
                    }
                });
            }
        } catch (IOException e) {
            return false;
        }
    }
}
