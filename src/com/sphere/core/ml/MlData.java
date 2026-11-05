package com.sphere.core.ml;

import com.sphere.core.bridge.Spx;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.contrib.EnergyCorrelator;
import com.sphere.core.fastjet.contrib.Nsubjettiness;
import com.sphere.core.fastjet.contrib.SoftDrop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jets made into a dataset sphere_ml trains on, from Sphere's own FastJet. Each jet gives its particles, the
 * leading ones up to a number and a mask for the rest, as the seven features ParticleNet introduced (log pt,
 * log E, log pt/ptJ, log E/EJ, delta eta, delta phi, delta R to the jet axis); its high-level observables, by
 * Sphere's fjcontrib (pt, eta, mass, constituents, tau21 and tau32 with WTA kT axes, C2 and D2 at beta = 1,
 * the soft-drop mass at zcut 0.1); its class; its event's weight. Written as an SPX table in the layout
 * sphere_ml.Dataset maps without a copy, and NeuralNet reads.
 */
public final class MlData {

    public static final List<String> FEATURES = List.of("log_pt", "log_e", "log_ptrel", "log_erel", "deta", "dphi",
        "dr");
    public static final List<String> HIGH_LEVEL = List.of("pt", "eta", "mass", "nconst", "tau21", "tau32", "c2", "d2",
        "sdmass");

    private MlData() {
    }

    /** One jet's particles as the datasets hold them: features [points][7], and the mask. */
    public static double[][] particles(PseudoJet jet, int points, double[] mask) {
        final double[][] x = new double[points][FEATURES.size()];
        final double ptJ = jet.pt(), eJ = jet.E(), etaJ = jet.eta(), phiJ = jet.phiStd();
        final List<PseudoJet> cs = PseudoJet.sortedByPt(jet.constituents());
        for (int i = 0; i < points && i < cs.size(); i++) {
            final PseudoJet p = cs.get(i);
            final double pt = Math.max(p.pt(), 1e-12), e = Math.max(p.E(), 1e-12);
            double dphi = p.phiStd() - phiJ;
            if (dphi > Math.PI) {
                dphi -= 2 * Math.PI;
            } else if (dphi < -Math.PI) {
                dphi += 2 * Math.PI;
            }
            final double deta = p.pt() > 0 ? p.eta() - etaJ : 0;
            x[i] = new double[] {Math.log(pt), Math.log(e), Math.log(pt / ptJ), Math.log(e / eJ), deta, dphi,
                Math.hypot(deta, dphi)};
            mask[i] = 1;
        }
        return x;
    }

    /** One jet's high-level observables, in the order of HIGH_LEVEL; one that cannot be computed is 0. */
    public static double[] highLevel(PseudoJet jet) {
        final double[] h = new double[HIGH_LEVEL.size()];
        h[0] = jet.pt();
        h[1] = jet.eta();
        h[2] = jet.m();
        h[3] = jet.constituents().size();
        h[4] = safe(() -> Nsubjettiness.ratio(2, 1.0, Nsubjettiness.Axes.WTA_KT).result(jet));
        h[5] = safe(() -> Nsubjettiness.ratio(3, 1.0, Nsubjettiness.Axes.WTA_KT).result(jet));
        final EnergyCorrelator.Values ec = h[3] > 2 ? new EnergyCorrelator(1.0).compute(jet) : null;
        h[6] = ec == null ? 0 : finite(ec.c2());
        h[7] = ec == null ? 0 : finite(ec.d2());
        h[8] = safe(() -> new SoftDrop(0.0, 0.1).result(jet).m());
        return h;
    }

    private static double safe(java.util.function.Supplier<Double> f) {
        try {
            return finite(f.get());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double finite(Double v) {
        return v == null || !Double.isFinite(v) ? 0 : v;
    }

    /**
     * A table made into a dataset: rows of features (from the branches of ROOT trees, say), one class per
     * block of rows. The MLP of the zoo trains on it (its 'high-level' features); there are no particles.
     */
    public static Path table(Path file, String title, List<String> features, List<String> classes,
                             List<double[][]> columnsPerClass, String source) throws IOException {
        int n = 0;
        for (double[][] cols : columnsPerClass) {
            n += cols[0].length;
        }
        final int H = features.size();
        final double[] hl = new double[n * H];
        final long[] y = new long[n];
        int row = 0;
        for (int c = 0; c < columnsPerClass.size(); c++) {
            final double[][] cols = columnsPerClass.get(c);
            for (int r = 0; r < cols[0].length; r++, row++) {
                for (int f = 0; f < H; f++) {
                    hl[row * H + f] = cols[f][r];
                }
                y[row] = c;
            }
        }
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Kind", "ml-dataset");
        meta.put("Classes", String.join(",", classes));
        meta.put("Jets", Integer.toString(n));
        meta.put("Points", "0");
        meta.put("Features", "");
        meta.put("HighLevel", String.join(",", features));
        meta.put("Source", source);
        final double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0);
        final Spx.Writer out = new Spx.Writer(Spx.Kind.TABLE, title);
        out.meta(meta);
        out.f64("hl", hl);
        out.i64("y", y);
        out.f64("w", w);
        final int mass = features.indexOf("mass");
        if (mass >= 0) {
            final double[] m = new double[n];
            for (int r = 0; r < n; r++) {
                m[r] = hl[r * H + mass];
            }
            out.f64("mass", m);
        }
        return out.write(file);
    }

    /** Jets gathered class by class (signal from one sample, background from another), until written. */
    public static final class Builder {
        private final int points;
        private final List<String> classes = new ArrayList<>();
        private final List<double[][]> x = new ArrayList<>();
        private final List<double[]> mask = new ArrayList<>(), hl = new ArrayList<>();
        private final List<Integer> label = new ArrayList<>();
        private final List<Double> weight = new ArrayList<>();
        private final List<String> sources = new ArrayList<>();

        public Builder(int points) {
            this.points = points;
        }

        public int points() {
            return points;
        }

        /** The class's index: classes keep the order they were first named in (background first, by habit). */
        public int classOf(String name) {
            int c = classes.indexOf(name);
            if (c < 0) {
                classes.add(name);
                c = classes.size() - 1;
            }
            return c;
        }

        public void add(PseudoJet jet, String cls, double w) {
            final double[] m = new double[points];
            x.add(particles(jet, points, m));
            mask.add(m);
            hl.add(highLevel(jet));
            label.add(classOf(cls));
            weight.add(w);
        }

        public void source(String s) {
            if (s != null && !sources.contains(s)) {
                sources.add(s);
            }
        }

        public int size() {
            return label.size();
        }

        public List<String> classes() {
            return classes;
        }

        /** Jets per class, in the classes' order. */
        public Map<String, Integer> counts() {
            final Map<String, Integer> out = new LinkedHashMap<>();
            for (String c : classes) {
                out.put(c, 0);
            }
            for (int l : label) {
                out.merge(classes.get(l), 1, Integer::sum);
            }
            return out;
        }

        /** The dataset as SPX, its particles streamed rather than gathered into one more array. */
        public Path write(Path file, String title) throws IOException {
            final int n = size(), F = FEATURES.size(), H = HIGH_LEVEL.size();
            final Map<String, String> meta = new LinkedHashMap<>();
            meta.put("Kind", "ml-dataset");
            meta.put("Classes", String.join(",", classes));
            meta.put("Jets", Integer.toString(n));
            meta.put("Points", Integer.toString(points));
            meta.put("Features", String.join(",", FEATURES));
            meta.put("HighLevel", String.join(",", HIGH_LEVEL));
            meta.put("Coords", "4,5");
            meta.put("PtIndex", "0");
            meta.put("Source", "Sphere FastJet: " + String.join("; ", sources));
            final long[] y = new long[n];
            final double[] w = new double[n], m = new double[n], pt = new double[n];
            for (int i = 0; i < n; i++) {
                y[i] = label.get(i);
                w[i] = weight.get(i);
                m[i] = hl.get(i)[2];
                pt[i] = hl.get(i)[0];
            }
            final Spx.Writer out = new Spx.Writer(Spx.Kind.TABLE, title);
            out.meta(meta);
            out.f64("x", (long) n * points * F, put -> {
                for (double[][] jet : x) {
                    for (double[] p : jet) {
                        for (double v : p) {
                            put.accept(v);
                        }
                    }
                }
            });
            out.f64("mask", (long) n * points, put -> {
                for (double[] mm : mask) {
                    for (double v : mm) {
                        put.accept(v);
                    }
                }
            });
            out.f64("hl", (long) n * H, put -> {
                for (double[] h : hl) {
                    for (double v : h) {
                        put.accept(v);
                    }
                }
            });
            out.i64("y", y);
            out.f64("w", w);
            out.f64("mass", m);
            out.f64("pt", pt);
            return out.write(file);
        }
    }
}
