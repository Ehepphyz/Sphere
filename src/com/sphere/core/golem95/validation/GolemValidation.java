package com.sphere.core.golem95.validation;

import com.sphere.core.golem95.Golem95;
import com.sphere.core.golem95.GolemEngine;
import com.sphere.core.golem95.Kinematics;
import com.sphere.core.golem95.Laurent;
import com.sphere.core.golem95.Quantity;
import com.sphere.core.golem95.Tensors;
import com.sphere.core.golem95.port.Cx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Sphere's Java Golem95 against the Fortran: 300 kinematics drawn at random (3 to 6 points; massless,
 * real and complex masses, on-shell and off-shell legs), and for each the form factors of every kind and
 * rank Golem95 has, higher ranks included, as gfortran 16 computed them (refdata/fortran.txt).
 *
 * <p>A Java answer is right when it is the Fortran's to the bit, or within ten times the Fortran's own
 * spread: how far the Fortran's answer moves when its S matrix moves by a relative 1e-12
 * (refdata/spread.txt, from three such runs). Where Golem95 itself is that sensitive, two compilations of
 * the same Fortran differ as much; asking the Java for more than the Fortran can give would be asking it to
 * reproduce rounding noise.
 */
public final class GolemValidation {

    /** The form factors computed for each kinematics, in the order of the references (golem_ref.f90). */
    static final String[][] LIST = {
        {},
        {},
        {},
        {"a30", "a31 1", "a31 2", "a32 1 2", "a32 2 2", "b32", "a33 1 2 3", "a33 1 1 1", "b33 1", "a34 1 2 3 3", "b34 1 2", "c34"},
        {"a40", "a41 1", "a41 3", "a42 1 4", "a42 2 2", "b42", "a43 1 1 3", "a43 2 3 4", "b43 1", "a44 1 2 3 4",
            "a44 4 4 4 4", "b44 1 2", "c44", "a45 1 1 2 3 4", "b45 1 2 3", "c45 1"},
        {"a50", "a51 1", "a52 1 2", "b52", "a53 1 2 4", "b53 1", "a54 1 2 3 4", "b54 1 2", "c54", "a55 1 2 3 4 5",
            "b55 1 2 3", "c55 1"},
        {"a60", "a61 1", "a62 1 2", "a63 1 2 3", "a64 1 2 3 4", "a65 1 2 3 4 5", "a66 1 2 3 4 5 6"},
    };

    private GolemValidation() {
    }

    /** One form factor of one kinematics that the Java does not give as the Fortran does. */
    public record Miss(int caseNumber, String formFactor, double deviation, double spread) {
    }

    /**
     * @param values       form factors compared
     * @param identical    the Fortran's to the bit (six doubles each)
     * @param withinSpread not identical, within ten times the Fortran's own spread
     * @param sensitive    of the misses, those where the Fortran itself moves by more than 1e-9 under a 1e-12 change
     * @param noNumber     of those within, the ones where neither gives a finite number (both NaN or infinite)
     */
    public record Outcome(int cases, int values, int identical, int withinSpread, int sensitive, int noNumber,
                          List<Miss> misses, long millis) {

        public boolean passed() {
            return misses.isEmpty() && values > 0;
        }

        public String line() {
            return String.format(Locale.ROOT, "%d form factors of %d kinematics: %d identical to the bit (%.1f%%), %d within the "
                    + "Fortran's own spread%s, %d outside it%s in %.1f s", values, cases, identical,
                100.0 * identical / Math.max(1, values), withinSpread,
                noNumber == 0 ? "" : " (" + noNumber + " where neither gives a finite number)", misses.size(),
                sensitive == 0 ? "" : " (" + sensitive + " where Golem95 itself is ill-conditioned)", millis / 1e3);
        }
    }

    public static boolean referencesAvailable() {
        try {
            read("cases.txt");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static Outcome run() throws IOException {
        return run(Integer.MAX_VALUE);
    }

    /** The first {@code limit} kinematics only (a quick look, for ':golem diag'). */
    public static Outcome run(int limit) throws IOException {
        final long t0 = System.nanoTime();
        final List<String> cases = read("cases.txt");
        final List<List<double[]>> fortran = blocks(read("fortran.txt"));
        final List<double[]> spread = spreads(read("spread.txt"));
        final int count = Math.min(limit, Integer.parseInt(cases.get(0).trim()));
        final List<Callable<List<double[]>>> tasks = new ArrayList<>();
        for (int k = 1; k <= count; k++) {
            final String line = cases.get(k);
            tasks.add(() -> java(line));
        }
        final List<Golem95.Outcome<List<double[]>>> java = Golem95.all(tasks);
        int values = 0;
        int identical = 0;
        int within = 0;
        int sensitive = 0;
        int noNumber = 0;
        final List<Miss> misses = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            final int n = Integer.parseInt(cases.get(k + 1).trim().split("\\s+")[0]);
            final List<double[]> f = k < fortran.size() ? fortran.get(k) : List.of();
            final Golem95.Outcome<List<double[]>> o = java.get(k);
            for (int i = 0; i < LIST[n].length; i++) {
                final double[] a = i < f.size() ? f.get(i) : null;
                if (a == null) {
                    continue;
                }
                values++;
                final double[] b = o.ok() && i < o.value().size() ? o.value().get(i) : null;
                final double sp = k < spread.size() && i < spread.get(k).length ? spread.get(k)[i] : 0;
                if (sp > 1e-9) {
                    sensitive++;
                }
                if (b == null) {
                    misses.add(new Miss(k + 1, LIST[n][i], Double.POSITIVE_INFINITY, sp));
                    continue;
                }
                boolean same = true;
                boolean finite = true;
                double scale = 0;
                double d = 0;
                for (int c = 0; c < 6; c++) {
                    same &= Double.doubleToLongBits(a[c]) == Double.doubleToLongBits(b[c]) || a[c] == b[c];
                    // where one gives no number, the other must give none either (a NaN for an infinity is no difference)
                    if (!Double.isFinite(a[c]) || !Double.isFinite(b[c])) {
                        finite = false;
                        if (Double.isFinite(a[c]) || Double.isFinite(b[c])) {
                            d = Double.POSITIVE_INFINITY;
                        }
                        continue;
                    }
                    scale = Math.max(scale, Math.abs(a[c]));
                }
                for (int c = 0; c < 6; c++) {
                    if (Double.isFinite(a[c]) && Double.isFinite(b[c])) {
                        d = Math.max(d, Math.abs(a[c] - b[c]) / (scale == 0 ? 1 : scale));
                    }
                }
                if (same) {
                    identical++;
                } else if (d <= Math.max(10 * sp, 1e-12)) {
                    within++;
                    if (!finite) {
                        noNumber++;
                    }
                } else {
                    misses.add(new Miss(k + 1, LIST[n][i], d, sp));
                }
            }
        }
        misses.sort(Comparator.comparingDouble(Miss::deviation).reversed());
        int sensitiveMisses = 0;
        for (Miss m : misses) {
            if (m.spread() > 1e-9) {
                sensitiveMisses++;
            }
        }
        return new Outcome(count, values, identical, within, sensitiveMisses, noNumber, List.copyOf(misses),
            (System.nanoTime() - t0) / 1_000_000);
    }

    /** The Java's form factors for one kinematics, as the Fortran printed them: re a, im a, re b, im b, re c, im c. */
    private static List<double[]> java(String line) {
        final String[] f = line.trim().split("\\s+");
        final int n = Integer.parseInt(f[0]);
        final Cx[][] s = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                s[i][j] = new Cx(Double.parseDouble(f[1 + i * n + j]), Double.parseDouble(f[1 + n * n + i * n + j]));
            }
        }
        final GolemEngine e = Golem95.workerEngine(GolemEngine.Settings.DEFAULT).set(Kinematics.ofS(s, "validation"));
        final List<double[]> out = new ArrayList<>();
        for (String q : LIST[n]) {
            try {
                final Laurent v = Quantity.parse(q, n).on(e).laurent();
                out.add(new double[]{v.a().re, v.a().im, v.b().re, v.b().im, v.c().re, v.c().im});
            } catch (RuntimeException failed) {
                out.add(null);
            }
        }
        return out;
    }

    private static List<List<double[]>> blocks(List<String> lines) {
        final List<List<double[]>> out = new ArrayList<>();
        List<double[]> cur = null;
        for (String l : lines) {
            if (l.startsWith("case")) {
                cur = new ArrayList<>();
                out.add(cur);
            } else if (cur != null && (l.startsWith("ERROR") || l.trim().equals("SKIP"))) {
                cur.add(null);
            } else if (cur != null && !l.isBlank()) {
                final double[] v = new double[6];
                for (int c = 0; c < 6 && 26 * c < l.length(); c++) {
                    v[c] = Double.parseDouble(l.substring(26 * c, Math.min(l.length(), 26 * c + 26)).trim());
                }
                cur.add(v);
            }
        }
        return out;
    }

    /* ---------------------------------------------------------------- the interfaces */

    /**
     * One part of the interfaces' validation: how many values were compared, how many are the reference's
     * to the bit, how many within the tolerance ({@code tolerance} says which), and the others.
     */
    public record Part(String name, int values, int identical, int within, String tolerance, int sensitive,
                       List<String> misses) {

        public boolean passed() {
            return misses.isEmpty() && values > 0;
        }

        public String line() {
            return String.format(Locale.ROOT, "%s: %d values, %d identical to the bit, %d within %s%s, %d outside", name, values,
                identical, within, tolerance,
                sensitive == 0 ? "" : " (" + sensitive + " where the Fortran itself moves by more than 1e-9)", misses.size());
        }
    }

    private static final String SPREAD = "the Fortran's own spread";

    /** The contraction vectors of the tensors, as iface_ref.f90 makes them. */
    private static final double[][] VV = new double[7][];

    static {
        for (int i = 1; i <= 7; i++) {
            VV[i - 1] = new double[]{1.1 + 0.1 * i, 0.3 - 0.05 * i, -0.7 + 0.02 * i, 0.4 + 0.03 * i};
        }
    }

    private static final int[] MAXRANK = {2, 3, 4, 5, 6, 7};

    /**
     * The interfaces Sphere offers beside the form factors, against the Fortran on 36 kinematics (N = 1..6,
     * massless and massive propagators, random momenta): Golem95's LoopTools-style functions as translated
     * (g*0i with their ids, g*0, those with complex masses), computed in order on one core as the Fortran
     * program computed them; and Sphere's own tensors, every rank, against Golem95's ti* where they work and,
     * for the rank N+1 of N = 2..5, which Golem95 1.3.4 gets wrong or stops on, against the Fortran with
     * those four routines written as Golem95 meant them.
     */
    public static List<Part> interfaces() throws IOException {
        final List<String> cases = read("iface_cases.txt");
        final List<List<double[]>> fortran = blocks(read("iface_fortran.txt"));
        final List<List<double[]>> fixed = blocks(read("iface_fixed.txt"));
        final List<double[]> spread = spreads(read("iface_spread.txt"));
        final List<double[]> fixedSpread = spreads(read("iface_fixed_spread.txt"));
        final int count = Integer.parseInt(cases.get(0).trim());
        final com.sphere.core.golem95.port.GolemCore g = new com.sphere.core.golem95.port.GolemCore();
        int ltValues = 0;
        int ltSame = 0;
        int ltWithin = 0;
        int ltSensitive = 0;
        final List<String> ltMiss = new ArrayList<>();
        final List<Callable<List<TensorValue>>> tasks = new ArrayList<>();
        final List<double[][]> refs = new ArrayList<>();
        final List<double[]> refSpreads = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        int fixedAt = 0;
        for (int k = 1; k <= count; k++) {
            final Case c = Case.parse(cases.get(k));
            final List<double[]> ref = fortran.get(k - 1);
            final List<double[]> lt = loopTools(g, c);
            final double[] sp = spread.get(k - 1);
            for (int i = 0; i < lt.size(); i++) {
                ltValues++;
                if (sp[i] > 1e-9) {
                    ltSensitive++;
                }
                final int verdict = compare(ref.get(i), lt.get(i), Math.max(10 * sp[i], 1e-12));
                if (verdict == 0) {
                    ltSame++;
                } else if (verdict == 1) {
                    ltWithin++;
                } else {
                    ltMiss.add(String.format(Locale.ROOT, "kinematics %d (N=%d), value %d: %.1e from the Fortran (its own spread "
                        + "%.1e)", k, c.n, i + 1, deviation(ref.get(i), lt.get(i)), sp[i]));
                }
            }
            // the tensors' references: the last ranks of the block; rank N+1 of N = 2..5 from the corrected run
            final int nt = MAXRANK[c.n - 1] + 1;
            final List<double[]> fx = c.n >= 2 && c.n <= 5 ? fixed.get(fixedAt) : null;
            final double[] fxs = c.n >= 2 && c.n <= 5 ? fixedSpread.get(fixedAt++) : null;
            final double[][] tr = new double[nt][];
            final double[] ts = new double[nt];
            for (int r = 0; r < nt; r++) {
                final boolean corrected = c.n >= 2 && c.n <= 5 && r == c.n + 1;
                tr[r] = corrected ? fx.get(fx.size() - nt + r) : ref.get(ref.size() - nt + r);
                ts[r] = corrected ? fxs[fxs.length - nt + r] : sp[sp.length - nt + r];
            }
            refs.add(tr);
            refSpreads.add(ts);
            labels.add("kinematics " + k + " (N=" + c.n + ")");
            tasks.add(() -> sphereTensors(c));
        }
        int tValues = 0;
        int tSame = 0;
        int tWithin = 0;
        int tSensitive = 0;
        final List<String> tMiss = new ArrayList<>();
        int sValues = 0;
        int sSame = 0;
        int sWithin = 0;
        int sSensitive = 0;
        final List<String> sMiss = new ArrayList<>();
        // the largest move of the tensors by the orders of the labels, and their largest distance to the ti*
        double sOrders = 0;
        double sFarthest = 0;
        final List<Golem95.Outcome<List<TensorValue>>> done = Golem95.all(tasks);
        for (int k = 0; k < count; k++) {
            final Golem95.Outcome<List<TensorValue>> o = done.get(k);
            for (int r = 0; r < refs.get(k).length; r++) {
                final double[] ref = refs.get(k)[r];
                if (ref == null) {
                    continue;
                }
                tValues++;
                final double s = refSpreads.get(k)[r];
                if (s > 1e-9) {
                    tSensitive++;
                }
                final TensorValue mine = o.ok() ? o.value().get(r) : null;
                final int verdict = mine == null ? 2 : compare(ref, mine.asGolem95(), Math.max(10 * s, 1e-12));
                if (verdict == 0) {
                    tSame++;
                } else if (verdict == 1) {
                    tWithin++;
                } else {
                    tMiss.add(labels.get(k) + ", rank " + r + (o.ok() ? String.format(Locale.ROOT, ": %.1e from the reference "
                        + "(its own spread %.1e)", deviation(ref, mine.asGolem95()), s) : ": " + o.error().getMessage()));
                }
                // Sphere's own answer where the orders of the labels matter: within the error it states for it
                if (mine == null || mine.orderSpread() == 0) {
                    continue;
                }
                sValues++;
                if (s > 1e-9) {
                    sSensitive++;
                }
                double scale = 0;
                for (double x : ref) {
                    scale = Math.max(scale, Math.abs(x));
                }
                final double stated = mine.orderError() / (scale == 0 ? 1 : scale);
                final int own = compare(ref, mine.symmetric(), Math.max(Math.max(10 * s, 1e-12), stated));
                final double d = deviation(ref, mine.symmetric());
                sFarthest = Double.isFinite(d) ? Math.max(sFarthest, d) : sFarthest;
                sOrders = Math.max(sOrders, mine.orderSpread());
                if (own == 0) {
                    sSame++;
                } else if (own == 1) {
                    sWithin++;
                } else {
                    sMiss.add(String.format(Locale.ROOT, "%s, rank %d: %.1e from the reference, beyond the %.1e Sphere states "
                        + "(the Fortran's own spread %.1e)", labels.get(k), r, d, stated, s));
                }
            }
        }
        final Part identities = identities(cases);
        return List.of(
            new Part("LoopTools-style functions (g*0i, g*0, complex masses), translated, against the Fortran", ltValues,
                ltSame, ltWithin, SPREAD, ltSensitive, List.copyOf(ltMiss)),
            new Part("Sphere's tensors, every rank, against Golem95's ti* (above rank N each order of the labels with its own "
                + "form factor, as they sum them; the rank N+1 of N = 2..5 against a corrected Fortran)",
                tValues, tSame, tWithin, SPREAD, tSensitive, List.copyOf(tMiss)),
            new Part(String.format(Locale.ROOT, "Sphere's own tensors above rank N, the mean over the orders of the labels "
                + "(one order or another moves them by up to %.0e), against Golem95's ti*: up to %.0e from them", sOrders,
                sFarthest),
                sValues, sSame, sWithin, "the error Sphere states for them", sSensitive, List.copyOf(sMiss)),
            identities);
    }

    /**
     * The tensor of the highest rank of the first kinematics of each N = 2..6, held to the identities it
     * satisfies exactly ({@link Tensors#identities}: its contractions with r_j - r_a and its trace are tensors of
     * lower rank, propagators pinched), within the error Sphere states for it: a check that does not rest on
     * Golem95's ti*, nor on the Fortran.
     */
    private static Part identities(List<String> cases) {
        final List<Callable<String>> tasks = new ArrayList<>();
        for (int n = 2; n <= 6; n++) {
            for (int k = 1; k < cases.size(); k++) {
                if (cases.get(k).trim().startsWith(n + " ")) {
                    final Case c = Case.parse(cases.get(k));
                    final int kk = k;
                    tasks.add(() -> {
                        final Cx[] m2 = new Cx[c.n];
                        for (int i = 0; i < c.n; i++) {
                            m2[i] = Cx.of(c.mu2[i]);
                        }
                        final Kinematics kin = Kinematics.fromPropagatorMomenta(c.r, m2, "interfaces");
                        final GolemEngine e = Golem95.workerEngine(GolemEngine.Settings.DEFAULT).set(kin);
                        final int rank = MAXRANK[c.n - 1];
                        final Tensors.Tensor t = e.tensor(rank, null);
                        final double stated = Math.max(Tensors.noise(kin, GolemEngine.Settings.DEFAULT, rank, null, t,
                            0x601E95L), t.orderSpread());
                        final Tensors.Identities id = Tensors.identities(e, t, null);
                        return String.format(Locale.ROOT, "%d %d %d %s %s %s", kk, c.n, rank, id.contraction(), id.trace(), stated);
                    });
                    break;
                }
            }
        }
        int values = 0;
        int within = 0;
        final List<String> misses = new ArrayList<>();
        // one kinematics after the other, from this thread: each tensor's form factors then take every worker
        for (Callable<String> task : tasks) {
            final String[] w;
            try {
                w = task.call().split(" ");
            } catch (Exception e) {
                values++;
                misses.add("an identity could not be computed: " + e.getMessage());
                continue;
            }
            final double stated = Double.parseDouble(w[5]);
            for (int i = 3; i <= 4; i++) {
                final double d = Double.parseDouble(w[i]);
                if (Double.isNaN(d)) {
                    continue;
                }
                values++;
                if (d <= Math.max(10 * stated, 1e-12)) {
                    within++;
                } else {
                    misses.add(String.format(Locale.ROOT, "kinematics %s (N=%s), rank %s: the %s is %.1e from its identity, "
                        + "beyond the %.1e Sphere states", w[0], w[1], w[2], i == 3 ? "contraction with r_j - r_a" : "trace", d,
                        stated));
                }
            }
        }
        return new Part("Sphere's tensors of the highest rank (one kinematics per N = 2..6) against exact identities: "
            + "their contractions with r_j - r_a and their traces are tensors of lower rank with propagators pinched",
            values, 0, within, "the error Sphere states for them", 0, List.copyOf(misses));
    }

    /** One kinematics of the interfaces: masses squared, propagator momenta (r_N = 0), the g*0 invariants. */
    private record Case(int n, double[] mu2, double[][] r, double[] lt) {

        static Case parse(String line) {
            final String[] f = line.trim().split("\\s+");
            final int n = Integer.parseInt(f[0]);
            int at = 1;
            final double[] mu2 = new double[n];
            for (int i = 0; i < n; i++) {
                mu2[i] = Double.parseDouble(f[at++]);
            }
            final double[][] r = new double[n - 1][4];
            for (int i = 0; i < n - 1; i++) {
                for (int c = 0; c < 4; c++) {
                    r[i][c] = Double.parseDouble(f[at++]);
                }
            }
            final double[] lt = new double[15];
            for (int i = 0; at < f.length; i++) {
                lt[i] = Double.parseDouble(f[at++]);
            }
            return new Case(n, mu2, r, lt);
        }
    }

    private static String[] ids(int n) {
        return switch (n) {
            case 1 -> new String[]{"aa0", "aa00"};
            case 2 -> new String[]{"bb0", "bb1", "bb2", "bb00", "bb11", "bb12", "bb001", "bb002", "bb111", "bb112"};
            case 3 -> new String[]{"cc0", "cc1", "cc3", "cc00", "cc12", "cc33", "cc001", "cc003", "cc123", "cc111", "cc002",
                "cc0012", "cc0033", "cc1123", "cc1111"};
            case 4 -> new String[]{"dd0", "dd1", "dd4", "dd00", "dd12", "dd44", "dd001", "dd004", "dd123", "dd0000", "dd0012",
                "dd1234", "dd4444", "dd00001", "dd00123", "dd11234"};
            case 5 -> new String[]{"ee0", "ee1", "ee00", "ee12", "ee001", "ee124", "ee0000", "ee0012", "ee1234", "ee00003",
                "ee00123", "ee12345"};
            default -> new String[]{"ff0", "ff1", "ff12", "ff123", "ff1234", "ff12345", "ff123456"};
        };
    }

    /** The g*0i of every id, g*0, and with complex masses (m^2 - 0.1 i m^2) for N <= 4: what iface_ref.f90 writes. */
    private static List<double[]> loopTools(com.sphere.core.golem95.port.GolemCore g, Case c) {
        final int n = c.n;
        final double[] mu2 = c.mu2;
        final double[] lt = c.lt;
        final double mu = 1.3;
        final List<double[]> out = new ArrayList<>();
        for (String id : ids(n)) {
            final Cx[] v = new Cx[3];
            for (int e = -2; e <= 0; e++) {
                v[e + 2] = switch (n) {
                    case 1 -> g.m_ext_ga0.ga0i(id, mu2[0], mu, e);
                    case 2 -> g.m_ext_gb0.gb0i(id, lt[0], mu2[1], mu2[0], mu, e);
                    case 3 -> g.m_ext_gc0.gc0i(id, lt[0], lt[1], lt[2], mu2[2], mu2[0], mu2[1], mu, e);
                    case 4 -> g.m_ext_gd0.gd0i(id, lt[0], lt[1], lt[2], lt[3], lt[4], lt[5], mu2[3], mu2[0], mu2[1], mu2[2], mu, e);
                    case 5 -> g.m_ext_ge0.ge0i(id, lt[0], lt[1], lt[2], lt[3], lt[4], lt[5], lt[6], lt[7], lt[8], lt[9],
                        mu2[4], mu2[0], mu2[1], mu2[2], mu2[3], mu, e);
                    default -> g.m_ext_gf0.gf0i(id, lt[0], lt[1], lt[2], lt[3], lt[4], lt[5], lt[6], lt[7], lt[8], lt[9],
                        lt[10], lt[11], lt[12], lt[13], lt[14], mu2[5], mu2[0], mu2[1], mu2[2], mu2[3], mu2[4], mu, e);
                };
            }
            out.add(six(v));
        }
        final Cx[] v = new Cx[3];
        for (int e = -2; e <= 0; e++) {
            v[e + 2] = switch (n) {
                case 1 -> g.m_ext_ga0.ga0(mu2[0], mu, e);
                case 2 -> g.m_ext_gb0.gb0(lt[0], mu2[1], mu2[0], mu, e);
                case 3 -> g.m_ext_gc0.gc0(lt[0], lt[1], lt[2], mu2[2], mu2[0], mu2[1], mu, e);
                case 4 -> g.m_ext_gd0.gd0(lt[0], lt[1], lt[2], lt[3], lt[4], lt[5], mu2[3], mu2[0], mu2[1], mu2[2], mu, e);
                case 5 -> g.m_ext_ge0.ge0(lt[0], lt[1], lt[2], lt[3], lt[4], lt[5], lt[6], lt[7], lt[8], lt[9],
                    mu2[4], mu2[0], mu2[1], mu2[2], mu2[3], mu, e);
                default -> g.m_ext_gf0.gf0(lt[0], lt[1], lt[2], lt[3], lt[4], lt[5], lt[6], lt[7], lt[8], lt[9],
                    lt[10], lt[11], lt[12], lt[13], lt[14], mu2[5], mu2[0], mu2[1], mu2[2], mu2[3], mu2[4], mu, e);
            };
        }
        out.add(six(v));
        if (n <= 4) {
            final Cx[] cm = new Cx[n];
            for (int i = 0; i < n; i++) {
                cm[i] = new Cx(mu2[i], -0.1 * mu2[i]);
            }
            if (n < 4) {
                for (String id : ids(n)) {
                    final Cx[] w = new Cx[3];
                    for (int e = -2; e <= 0; e++) {
                        w[e + 2] = switch (n) {
                            case 1 -> g.m_ext_ga0.ga0ic(id, cm[0], mu, e);
                            case 2 -> g.m_ext_gb0.gb0ic(id, Cx.of(lt[0]), cm[1], cm[0], mu, e);
                            default -> g.m_ext_gc0.gc0ic(id, Cx.of(lt[0]), Cx.of(lt[1]), Cx.of(lt[2]), cm[2], cm[0], cm[1], mu, e);
                        };
                    }
                    out.add(six(w));
                }
            }
            final Cx[] w = new Cx[3];
            for (int e = -2; e <= 0; e++) {
                w[e + 2] = switch (n) {
                    case 1 -> g.m_ext_ga0.ga0c(cm[0], mu, e);
                    case 2 -> g.m_ext_gb0.gb0c(Cx.of(lt[0]), cm[1], cm[0], mu, e);
                    case 3 -> g.m_ext_gc0.gc0c(Cx.of(lt[0]), Cx.of(lt[1]), Cx.of(lt[2]), cm[2], cm[0], cm[1], mu, e);
                    default -> g.m_ext_gd0.gd0c(Cx.of(lt[0]), Cx.of(lt[1]), Cx.of(lt[2]), Cx.of(lt[3]), Cx.of(lt[4]),
                        Cx.of(lt[5]), cm[3], cm[0], cm[1], cm[2], mu, e);
                };
            }
            out.add(six(w));
        }
        return out;
    }

    private static double[] six(Cx[] v) {
        return new double[]{v[0].re, v[0].im, v[1].re, v[1].im, v[2].re, v[2].im};
    }

    /**
     * One tensor of the interfaces, contracted as iface_ref.f90 contracts it: as Golem95's ti* sum it (above
     * rank N each order of the labels with its own form factor), Sphere's own (the mean over the orders), and
     * the error Sphere states for its own from the orders of the labels, as an absolute bound on the contraction.
     */
    private record TensorValue(double[] asGolem95, double[] symmetric, double orderError, double orderSpread) {
    }

    /** Sphere's tensors of every rank for one kinematics. */
    private static List<TensorValue> sphereTensors(Case c) {
        final Cx[] m2 = new Cx[c.n];
        for (int i = 0; i < c.n; i++) {
            m2[i] = Cx.of(c.mu2[i]);
        }
        final GolemEngine e = Golem95.workerEngine(GolemEngine.Settings.DEFAULT)
            .set(Kinematics.fromPropagatorMomenta(c.r, m2, "interfaces"));
        final List<TensorValue> out = new ArrayList<>();
        for (int rank = 0; rank <= MAXRANK[c.n - 1]; rank++) {
            final double[][] v = new double[rank][];
            System.arraycopy(VV, 0, v, 0, rank);
            final Tensors.Tensor t = e.tensor(rank, null);
            final double[] mine = contracted(t, v);
            // every order of the labels as Golem95 takes them, where they are few (not the hexagon's 5^7 at rank 7)
            final double[] golem = rank > c.n && c.n <= 5 ? contracted(e.tensor(rank, null, Tensors.Labels.AS_GOLEM95), v) : mine;
            // a component-wise error E moves the contraction by at most E prod_s sum_mu |v_s^mu|
            double norms = 1;
            for (double[] x : v) {
                norms *= Math.abs(x[0]) + Math.abs(x[1]) + Math.abs(x[2]) + Math.abs(x[3]);
            }
            double largest = 0;
            for (double d : t.data()) {
                largest = Math.max(largest, Math.abs(d));
            }
            out.add(new TensorValue(golem, mine, t.orderSpread() * largest * norms, t.orderSpread()));
        }
        return out;
    }

    private static double[] contracted(Tensors.Tensor t, double[][] v) {
        final Laurent l = t.rank() == 0 ? t.get() : t.contractComponents(v);
        return new double[]{l.a().re, l.a().im, l.b().re, l.b().im, l.c().re, l.c().im};
    }

    /** The largest difference, relative to the reference's largest number. */
    private static double deviation(double[] ref, double[] mine) {
        double scale = 0;
        double d = 0;
        for (int i = 0; i < 6; i++) {
            scale = Math.max(scale, Math.abs(ref[i]));
            d = Math.max(d, Math.abs(ref[i] - mine[i]));
        }
        return d / (scale == 0 ? 1 : scale);
    }

    /** 0 the same to the bit, 1 within the tolerance (relative to the reference's largest number), 2 outside. */
    private static int compare(double[] ref, double[] mine, double tolerance) {
        if (ref == null || mine == null) {
            return 2;
        }
        boolean same = true;
        double scale = 0;
        for (int i = 0; i < 6; i++) {
            same &= Double.doubleToLongBits(ref[i]) == Double.doubleToLongBits(mine[i]) || ref[i] == mine[i];
            scale = Math.max(scale, Math.abs(ref[i]));
        }
        if (same) {
            return 0;
        }
        double d = 0;
        for (int i = 0; i < 6; i++) {
            if (!Double.isFinite(ref[i]) || !Double.isFinite(mine[i])) {
                return Double.isFinite(ref[i]) == Double.isFinite(mine[i]) ? 1 : 2;
            }
            d = Math.max(d, Math.abs(ref[i] - mine[i]));
        }
        return d <= tolerance * (scale == 0 ? 1 : scale) ? 1 : 2;
    }

    private static List<double[]> spreads(List<String> lines) {
        final List<double[]> out = new ArrayList<>();
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("#")) {
                continue;
            }
            final String[] w = l.trim().split("\\s+");
            final double[] v = new double[w.length - 1];
            for (int i = 1; i < w.length; i++) {
                v[i - 1] = w[i].equals("nan") ? Double.NaN : Double.parseDouble(w[i]);
            }
            out.add(v);
        }
        return out;
    }

    /** A reference file: from the jar, or from the sources when Sphere runs from them. */
    private static List<String> read(String name) throws IOException {
        try (InputStream in = GolemValidation.class.getResourceAsStream("refdata/" + name)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        }
        final Path p = Path.of("src", "com", "sphere", "core", "golem95", "validation", "refdata", name);
        if (Files.isRegularFile(p)) {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        }
        throw new IOException("the Golem95 reference " + name + " is missing from this build of Sphere");
    }
}
