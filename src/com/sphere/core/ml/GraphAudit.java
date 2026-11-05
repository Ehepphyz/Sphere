package com.sphere.core.ml;

import com.sphere.core.bridge.Spx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A jet tagger refereed as physics would, whatever framework made it -- PyTorch, Keras, TensorFlow -- in Sphere's
 * Java alone, on the graph it was made into (NeuralGraph) and the jets of a dataset: whether the order of its
 * particles matters, whether its padded slots leak into the output, whether it is collinear safe (the hardest
 * particle split in two, exactly) and infrared safe (a particle of eps of the jet's pt added, every feature and
 * the jet's axis recomputed from the four-vectors, eps scanned to 0), how much float32 inputs move it, and which
 * of Sphere's particle features it reacts to most.
 *
 * <p>The features are Sphere's (MlData.FEATURES): log pt, log E, log pt/ptJ, log E/EJ, delta eta, delta phi,
 * delta R, relative to the jet's axis -- from which each particle's four-vector, and the jet's, are recovered.
 */
public final class GraphAudit {

    /** One check: its name, its grade (pass, warn, fail, info) and what was found. */
    public record Check(String name, String grade, String finding) {
    }

    /** The checks, the features by how much the tagger reacts to each, and a verdict. */
    public record Report(List<Check> checks, List<String> features, String verdict, int jets) {
    }

    private static final int F = 7, PT = 0, E = 1, PTREL = 2, EREL = 3, DETA = 4, DPHI = 5, DR = 6;
    private static final double TIGHT = 1e-9;

    private final NeuralGraph g;
    private final int n, points;
    private final double[] x, mask;   // [n, points, F], [n, points]
    private final Random rng = new Random(20261102L);

    private GraphAudit(NeuralGraph g, int n, int points, double[] x, double[] mask) {
        this.g = g;
        this.n = n;
        this.points = points;
        this.x = x;
        this.mask = mask;
    }

    /** The audit of graph g on the first jets of a dataset (':ml jets', ':ml toy'), at most jets of them. */
    public static Report run(NeuralGraph g, Path dataset, int jets, Consumer<String> say) throws IOException {
        if (!g.feeds.equals("particles")) {
            throw new IllegalArgumentException(g.name + " does not feed on particles (feeds '" + g.feeds + "'): export it with feeds=\"particles\"");
        }
        final int[][] ex = g.exampleShapes();
        final int points = ex[0][1];
        if (ex[0][2] != F || g.inputNames.size() < 2) {
            throw new IllegalArgumentException(g.name + " takes rows of " + ex[0][2] + " features and " + g.inputNames.size()
                + " inputs; the audit knows Sphere's " + F + " particle features and their mask");
        }
        final double[] x, mask;
        final int n;
        try (Spx.Reader r = new Spx.Reader(dataset)) {
            final Map<String, String> meta = r.meta();
            final int all = Integer.parseInt(meta.getOrDefault("Jets", meta.getOrDefault("Rows", "0")));
            final int p0 = Integer.parseInt(meta.getOrDefault("Points", "0"));
            final double[] rx = r.f64("x"), rm = r.f64("mask");
            n = Math.min(all, jets);
            x = new double[n * points * F];
            mask = new double[n * points];
            for (int j = 0; j < n; j++) {   // the dataset's particles in the graph's slots: the hardest first, truncated or padded
                for (int q = 0; q < Math.min(points, p0); q++) {
                    System.arraycopy(rx, (j * p0 + q) * F, x, (j * points + q) * F, F);
                    mask[j * points + q] = rm[j * p0 + q];
                }
            }
        }
        say.accept(String.format(Locale.ROOT, "  auditing %s on %d jets of %s, %d particle slots", g.name, n, dataset.getFileName(), points));
        return new GraphAudit(g, n, points, x, mask).audit();
    }

    private double[] score(double[] xs, double[] ms) {
        final List<NeuralGraph.Tensor> in = new ArrayList<>(List.of(new NeuralGraph.Tensor(xs, n, points, F),
            new NeuralGraph.Tensor(ms, n, points)));
        final NeuralGraph.Score s = NeuralGraph.signal(g.run(in).get(0));
        if (s == null) {
            throw new IllegalStateException(g.name + "'s output is not a classifier's");
        }
        return s.p();
    }

    private static double maxGap(double[] a, double[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) {
            d = Math.max(d, Math.abs(a[i] - b[i]));
        }
        return d;
    }

    private static double meanGap(double[] a, double[] b, boolean[] used) {
        double s = 0;
        int k = 0;
        for (int i = 0; i < a.length; i++) {
            if (used == null || used[i]) {
                s += Math.abs(a[i] - b[i]);
                k++;
            }
        }
        return k == 0 ? Double.NaN : s / k;
    }

    private int real(int j) {
        int c = 0;
        for (int q = 0; q < points; q++) {
            c += mask[j * points + q] > 0.5 ? 1 : 0;
        }
        return c;
    }

    private Report audit() {
        final List<Check> checks = new ArrayList<>();
        final double[] base = score(x, mask);

        // 1. the particles' order
        final double[] xs = x.clone();
        for (int j = 0; j < n; j++) {
            final int m = real(j);
            for (int q = m - 1; q > 0; q--) {   // Fisher-Yates over the jet's real particles
                final int s = rng.nextInt(q + 1);
                swap(xs, (j * points + q) * F, (j * points + s) * F);
            }
        }
        final double perm = maxGap(score(xs, mask), base);
        checks.add(perm <= TIGHT ? new Check("permutation", "pass", String.format(Locale.ROOT,
            "the particles' order changes nothing (%.1e): a function of the set", perm))
            : new Check("permutation", "info", String.format(Locale.ROOT, "the particles' order matters (up to %.3f in P(signal)):"
                + " the tagger reads them as a sequence, sorted by pt", perm)));

        // 2. the padded slots
        final double[] xp = x.clone();
        boolean padded = false;
        for (int j = 0; j < n; j++) {
            for (int q = 0; q < points; q++) {
                if (mask[j * points + q] < 0.5) {
                    padded = true;
                    for (int f = 0; f < F; f++) {
                        xp[(j * points + q) * F + f] = 10 * rng.nextGaussian();
                    }
                }
            }
        }
        if (padded) {
            final double leak = maxGap(score(xp, mask), base);
            checks.add(leak <= TIGHT ? new Check("padding", "pass", String.format(Locale.ROOT,
                "what the padded slots hold changes nothing (%.1e): the mask seals them", leak))
                : new Check("padding", "fail", String.format(Locale.ROOT, "LEAK: values in padded slots move P(signal) by up to %.3f --"
                    + " the mask does not keep them out (a model reading padding learns the jets' multiplicity from it)", leak)));
        }

        // 3. collinear safety: the hardest particle split in two halves, exactly
        final double[] xc = x.clone(), mc = mask.clone();
        final boolean[] split = new boolean[n];
        for (int j = 0; j < n; j++) {
            final int m = real(j);
            if (m == 0 || m >= points) {
                continue;
            }
            split[j] = true;
            final int h = hardest(xc, j, m);
            final double lz = Math.log(0.5);
            final int at = (j * points + m) * F;
            System.arraycopy(xc, (j * points + h) * F, xc, at, F);
            for (int f : new int[] {PT, E, PTREL, EREL}) {
                xc[(j * points + h) * F + f] += lz;
                xc[at + f] += lz;
            }
            mc[j * points + m] = 1;
            sortByPt(xc, j, m + 1);
        }
        final double coll = meanGap(score(xc, mc), base, split);
        checks.add(Double.isNaN(coll) ? new Check("collinear", "info", "no jet with a free slot to split a particle into")
            : coll <= 1e-6 ? new Check("collinear", "pass", String.format(Locale.ROOT,
                "splitting the hardest particle in two collinear halves changes P(signal) by %.1e: collinear safe", coll))
            : new Check("collinear", "warn", String.format(Locale.ROOT, "collinear UNSAFE: splitting the hardest particle in two"
                + " collinear halves moves P(signal) by %.2e on average", coll)));

        // 4. infrared safety: a soft particle of eps x ptJ, the jet and every feature recomputed; eps scanned
        final double[] eps = {1e-1, 1e-2, 1e-3, 1e-4, 1e-5};
        final double[] gap = new double[eps.length];
        boolean[] room = null;
        for (int k = 0; k < eps.length; k++) {
            final double[] xi = x.clone(), mi = mask.clone();
            final boolean[] used = new boolean[n];
            final Random soft = new Random(77);   // the same directions at every eps
            for (int j = 0; j < n; j++) {
                final int m = real(j);
                if (m == 0 || m >= points) {
                    soft.nextDouble();
                    soft.nextDouble();
                    continue;
                }
                used[j] = true;
                addSoft(xi, mi, j, m, eps[k], soft.nextDouble(), soft.nextDouble());
            }
            room = used;
            gap[k] = meanGap(score(xi, mi), base, used);
        }
        if (room != null && Double.isFinite(gap[eps.length - 1])) {
            final double slope = (Math.log(gap[eps.length - 1] + 1e-300) - Math.log(gap[eps.length - 3] + 1e-300))
                / (Math.log(eps[eps.length - 1]) - Math.log(eps[eps.length - 3]));
            final String scan = String.format(Locale.ROOT, "|change| %.1e, %.1e, %.1e, %.1e, %.1e at eps 1e-1 .. 1e-5", gap[0], gap[1],
                gap[2], gap[3], gap[4]);
            checks.add(gap[eps.length - 1] <= 1e-6 || slope >= 0.5 ? new Check("infrared", "pass", String.format(Locale.ROOT,
                "a soft particle changes P(signal) as eps^%.2f -> 0: infrared safe (%s)", slope, scan))
                : new Check("infrared", "warn", String.format(Locale.ROOT, "infrared UNSAFE: a particle of vanishing pt still moves"
                    + " P(signal) by %.1e (slope %.2f; %s)", gap[eps.length - 1], slope, scan)));
        }

        // 5. float32 inputs
        final double[] x32 = x.clone();
        for (int i = 0; i < x32.length; i++) {
            x32[i] = (float) x32[i];
        }
        final double f32 = maxGap(score(x32, mask), base);
        checks.add(new Check("float32 inputs", f32 <= 1e-5 ? "pass" : "warn", String.format(Locale.ROOT,
            "its inputs rounded to float32 move P(signal) by up to %.1e%s", f32, f32 <= 1e-5 ? ": float32 inference is safe"
                : ": a float32 deployment would show it")));

        // 6. what it reacts to: each feature moved by 5 % of its spread over the real particles
        final String[] names = MlData.FEATURES.toArray(String[]::new);
        final double[] react = new double[F];
        for (int f = 0; f < F; f++) {
            double s = 0, s2 = 0;
            int c = 0;
            for (int i = 0; i < n * points; i++) {
                if (mask[i] > 0.5) {
                    final double v = x[i * F + f];
                    s += v;
                    s2 += v * v;
                    c++;
                }
            }
            final double sd = c > 1 ? Math.sqrt(Math.max(0, s2 / c - (s / c) * (s / c))) : 1;
            final double[] xf = x.clone();
            for (int i = 0; i < n * points; i++) {
                if (mask[i] > 0.5) {
                    xf[i * F + f] += 0.05 * sd;
                }
            }
            react[f] = meanGap(score(xf, mask), base, null);
        }
        final double total = Arrays.stream(react).sum();
        final Integer[] order = {0, 1, 2, 3, 4, 5, 6};
        Arrays.sort(order, (a, b) -> Double.compare(react[b], react[a]));
        final List<String> ranking = new ArrayList<>();
        for (int f : order) {
            ranking.add(String.format(Locale.ROOT, "%s %.0f %%", names[f], total > 0 ? 100 * react[f] / total : 0));
        }

        final long fails = checks.stream().filter(c -> c.grade.equals("fail")).count();
        final long warns = checks.stream().filter(c -> c.grade.equals("warn")).count();
        final boolean irc = checks.stream().filter(c -> c.name.equals("collinear") || c.name.equals("infrared"))
            .allMatch(c -> c.grade.equals("pass"));
        final String verdict = fails > 0 ? "a defect to fix first: " + checks.stream().filter(c -> c.grade.equals("fail"))
            .map(Check::name).toList()
            : irc && perm <= TIGHT ? "a permutation-invariant, infrared and collinear safe tagger: its output is calculable in"
                + " perturbation theory, robust to soft and collinear radiation"
            : warns > 0 ? "usable, with what the warnings say: " + checks.stream().filter(c -> c.grade.equals("warn")).map(Check::name).toList()
            : "nothing wrong found";
        return new Report(checks, ranking, verdict, n);
    }

    private static void swap(double[] a, int i, int j) {
        for (int f = 0; f < F; f++) {
            final double t = a[i + f];
            a[i + f] = a[j + f];
            a[j + f] = t;
        }
    }

    private int hardest(double[] a, int j, int m) {
        int h = 0;
        for (int q = 1; q < m; q++) {
            if (a[(j * points + q) * F + PT] > a[(j * points + h) * F + PT]) {
                h = q;
            }
        }
        return h;
    }

    /** The jet's first m particles sorted by pt, hardest first (insertion: they are nearly sorted). */
    private void sortByPt(double[] a, int j, int m) {
        for (int q = 1; q < m; q++) {
            for (int s = q; s > 0 && a[(j * points + s) * F + PT] > a[(j * points + s - 1) * F + PT]; s--) {
                swap(a, (j * points + s) * F, (j * points + s - 1) * F);
            }
        }
    }

    /**
     * A particle of pt eps x ptJ added to jet j at (u, v) in a disk of radius 0.4 around its axis, the jet's
     * four-vector and every feature recomputed: the frame has the jet's axis at eta 0, phi 0, its pt and energy
     * recovered from any particle's pt / (pt/ptJ) and E / (E/EJ).
     */
    private void addSoft(double[] a, double[] m, int j, int real, double eps, double u, double v) {
        final int b0 = j * points * F;
        final double ptJ = Math.exp(a[b0 + PT] - a[b0 + PTREL]), eJ = Math.exp(a[b0 + E] - a[b0 + EREL]);
        final double r = 0.4 * Math.sqrt(u), t = 2 * Math.PI * v;
        final double etaS = r * Math.cos(t), phiS = r * Math.sin(t), ptS = eps * ptJ, eS = ptS * Math.cosh(etaS);
        final double px = ptJ + ptS * Math.cos(phiS), py = ptS * Math.sin(phiS), pz = ptS * Math.sinh(etaS), en = eJ + eS;
        final double pt2 = Math.hypot(px, py), eta2 = asinh(pz / pt2), phi2 = Math.atan2(py, px);
        final int at = b0 + real * F;
        a[at + PT] = Math.log(ptS);
        a[at + E] = Math.log(eS);
        a[at + DETA] = etaS;
        a[at + DPHI] = phiS;
        m[j * points + real] = 1;
        for (int q = 0; q <= real; q++) {   // every particle against the new jet
            final int p = b0 + q * F;
            final double pt = Math.exp(a[p + PT]), e = Math.exp(a[p + E]);
            final double eta = a[p + DETA] - eta2;
            double phi = a[p + DPHI] - phi2;
            phi = phi > Math.PI ? phi - 2 * Math.PI : phi < -Math.PI ? phi + 2 * Math.PI : phi;
            a[p + PTREL] = Math.log(pt / pt2);
            a[p + EREL] = Math.log(e / en);
            a[p + DETA] = eta;
            a[p + DPHI] = phi;
            a[p + DR] = Math.hypot(eta, phi);
        }
        sortByPt(a, j, real + 1);
    }

    private static double asinh(double z) {
        return Math.log(z + Math.sqrt(z * z + 1));
    }
}
