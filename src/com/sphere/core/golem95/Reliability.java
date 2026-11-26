package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

/**
 * How far an answer of Golem95 can be trusted, found out by asking Golem95 more questions whose answers
 * are known in advance, on every processor at once.
 *
 * <ul>
 *   <li><b>Noise.</b> The kinematics moved by 1e-15 (the rounding its numbers already carry): how much the
 *       answer moves is how many of its digits mean something.</li>
 *   <li><b>Conditioning.</b> Moved by 1e-11: the integral's own sensitivity kappa. A stable computation moves
 *       by about kappa times the rounding; one that moves far more loses digits the integral does not
 *       (Golem95 dividing by a B that is zero but for its rounding, for instance). This tells an
 *       ill-conditioned integral, which no double-precision program computes better, from an unstable
 *       evaluation, which another one can.</li>
 *   <li><b>Exact properties.</b> Every invariant and mu^2 times 256 (a power of 4 changes no bit of the
 *       inputs) multiplies the answer by 256^d; at 4 mu^2 it is the series at mu^2 re-expanded; with its
 *       propagators renamed it is the same integral, which Golem95 computes another way; above rank N, a
 *       form factor is symmetric in its labels, which Golem95 reduces in the order given (checked even in
 *       a quick assessment, since the orders can disagree by far more than the noise); a scalar
 *       integral is also OneLOop's; where Golem95 integrates numerically, a tighter tolerance measures
 *       its quadrature error.</li>
 *   <li><b>Rescue.</b> Where Golem95's evaluation is unstable but the integral is smooth (no threshold, no
 *       Landau singularity), the answer is interpolated from points around it where Golem95 is stable:
 *       Chebyshev nodes on two lines through the point, which must agree.</li>
 * </ul>
 */
public final class Reliability {

    private Reliability() {
    }

    /** The unit roundoff of double precision. */
    private static final double U = 0x1p-53;
    /** The move that measures noise: about the rounding of the inputs. */
    static final double NOISE_DELTA = 1e-15;
    /** The move that measures conditioning: far above rounding, far below any structure. */
    static final double COND_DELTA = 1e-11;
    /** The error under which an answer is reliable whatever else happens: about ten digits. */
    static final double GOOD = 1e-10;
    /** The move that measures conditioning when an unstable evaluation's noise drowns the first. */
    static final double WIDE_DELTA = 1e-7;
    private static final int WIDE_SAMPLES = 2;

    public enum Verdict {
        RELIABLE("reliable"),
        ILL_CONDITIONED("ill-conditioned"),
        UNSTABLE("unstable"),
        INCONSISTENT("inconsistent"),
        FAILED("failed");

        private final String text;

        Verdict(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * @param samples how many moved kinematics measure the noise (half as many measure the conditioning)
     * @param checks  whether the exact properties are checked
     * @param rescue  whether an unstable answer is rescued by interpolation
     * @param seed    the seed of the moves: the same question gets the same report
     */
    public record Options(int samples, boolean checks, boolean rescue, long seed) {

        public static final Options DEFAULT = new Options(6, true, true, 0x601E95L);
        /** Noise and conditioning only, two samples each: what a scan asks at every point. */
        public static final Options QUICK = new Options(2, false, false, 0x601E95L);

        public Options {
            if (samples < 1 || samples > 64) {
                throw new IllegalArgumentException("between 1 and 64 samples, not " + samples);
            }
        }
    }

    /** An exact property, how far the answer is from it, and how far it may be. */
    public record Check(String name, double deviation, double allowed, String detail, boolean exact) {

        public boolean passed() {
            return deviation <= allowed;
        }
    }

    /**
     * The answer interpolated from stable points around an unstable one.
     *
     * @param conditioning the integral's conditioning as those points show it
     */
    public record Rescue(Laurent value, double error, int points, double radius, double conditioning, String detail) {
    }

    /**
     * @param noise        how much the answer moves (relative) when the kinematics moves by 1e-15
     * @param conditioning kappa: a relative change delta of the invariants changes the integral by kappa delta
     * @param error        the relative error Sphere estimates for the answer
     * @param digits       the correct digits that error leaves
     */
    public record Report(Quantity quantity, Kinematics kinematics, GolemEngine.Settings settings, GolemEngine.Value value,
                         double noise, double conditioning, double error, int digits, Verdict verdict,
                         List<Check> checks, Rescue rescue, List<String> findings, int evaluations, long nanos) {

        /** The answer to use: the rescued one when it is clearly better than Golem95's own. */
        public Laurent best() {
            if (rescued(rescue, error)) {
                return rescue.value();
            }
            return value == null ? null : value.laurent();
        }

        /** The error of {@link #best()}. */
        public double bestError() {
            return rescued(rescue, error) ? rescue.error() : error;
        }

        public List<String> lines() {
            final List<String> out = new ArrayList<>();
            final String what = value != null ? value.what() : quantity.label();
            out.add(String.format(Locale.ROOT, "%s, %s: %s%s", what, kinematics.origin(), verdict.text(),
                verdict == Verdict.FAILED ? "" : ", about " + digits + " correct digit" + (digits == 1 ? "" : "s")));
            if (value != null) {
                for (String l : value.laurent().lines().split("\n")) {
                    out.add("   " + l);
                }
                out.add(String.format(Locale.ROOT, "   noise         %.1e  (relative move of the answer when the kinematics moves by 1e-15)",
                    noise));
                out.add(Double.isNaN(conditioning)
                    ? "   conditioning  unmeasurable here: Golem95's noise drowns the moves of the integral"
                    + (rescue == null || Double.isNaN(rescue.conditioning()) ? ""
                    : String.format(Locale.ROOT, " (about %.2g from the stable points around)", rescue.conditioning()))
                    : String.format(Locale.ROOT, "   conditioning  %.2g  (the integral moves by this times a relative move of its invariants)",
                    conditioning));
            }
            for (Check c : checks) {
                out.add(String.format(Locale.ROOT, "   %-6s %-22s %s  (allowed %.0e)  %s", c.passed() ? "ok" : "FAILED", c.name(),
                    Double.isNaN(c.deviation()) ? "   none" : String.format(Locale.ROOT, "%.1e", c.deviation()), c.allowed(),
                    c.detail()));
            }
            if (rescue != null) {
                out.add("   rescued, " + rescue.detail());
                for (String l : rescue.value().lines().split("\n")) {
                    out.add("   " + l);
                }
            }
            for (String f : findings) {
                out.add("   " + f);
            }
            out.add(String.format(Locale.ROOT, "   %d evaluations on %d workers, %.2f s", evaluations, Golem95.workers(),
                nanos / 1e9));
            return out;
        }
    }

    /** A check to run: what it computes and what it must give, from the answer. */
    private record Spec(String name, String detail, Callable<GolemEngine.Value> task, UnaryOperator<Laurent> expect,
                        boolean exact) {
    }

    public static Report assess(Quantity q, Kinematics k, GolemEngine.Settings s, Options o) {
        final long t0 = System.nanoTime();
        final int n = k.n();
        final Random rng = new Random(o.seed());
        final List<Callable<GolemEngine.Value>> tasks = new ArrayList<>();
        tasks.add(Golem95.task(q, k, s));
        for (int i = 0; i < o.samples(); i++) {
            tasks.add(Golem95.task(q, k.perturbed(new Random(rng.nextLong()), NOISE_DELTA), s));
        }
        final int conds = Math.max(2, (o.samples() + 1) / 2);
        for (int i = 0; i < conds; i++) {
            tasks.add(Golem95.task(q, k.perturbed(new Random(rng.nextLong()), COND_DELTA), s));
        }
        for (int i = 0; i < WIDE_SAMPLES; i++) {
            tasks.add(Golem95.task(q, k.perturbed(new Random(rng.nextLong()), WIDE_DELTA), s));
        }
        final List<Spec> specs = o.checks() ? specs(q, k, s, rng) : orderSpecs(q, k, s, 1);
        for (Spec c : specs) {
            tasks.add(c.task());
        }
        final List<Golem95.Outcome<GolemEngine.Value>> out = Golem95.all(tasks);
        int evaluations = tasks.size();
        final Golem95.Outcome<GolemEngine.Value> first = out.get(0);
        if (!first.ok()) {
            final List<String> f = new ArrayList<>();
            f.add(message(first.error()));
            if (first.error() instanceof GolemEngine.GolemException g) {
                f.addAll(g.said());
            }
            return new Report(q, k, s, null, Double.NaN, Double.NaN, Double.NaN, 0, Verdict.FAILED, List.of(), null,
                List.copyOf(f), evaluations, System.nanoTime() - t0);
        }
        final GolemEngine.Value v0 = first.value();
        final Laurent l0 = v0.laurent();
        if (!l0.isFinite()) {
            final List<String> f = new ArrayList<>();
            f.add("Golem95 gives no finite number here (NaN or infinity).");
            if (!k.roundingZeros(Kinematics.SNAP).isEmpty()) {
                f.add("The likely cause: entries of S that are zero but for rounding, which Golem95 takes for legs off shell "
                    + "by that much; ':golem snap' makes them exactly 0.");
            } else if (k.analyse().landau()) {
                f.add("det S vanishes: the integral sits on its leading Landau singularity.");
            }
            f.addAll(v0.said());
            return new Report(q, k, s, v0, Double.NaN, Double.NaN, Double.NaN, 0, Verdict.FAILED, List.of(), null,
                List.copyOf(f), evaluations, System.nanoTime() - t0);
        }
        final double[] scale = scale(l0);
        double noise = 0;
        int lost = 0;
        for (int i = 1; i <= o.samples(); i++) {
            final double d = out.get(i).ok() ? dev(out.get(i).value().laurent(), l0, scale) : Double.NaN;
            if (Double.isNaN(d)) {
                lost++;
            } else {
                noise = Math.max(noise, d);
            }
        }
        double moved = 0;
        for (int i = 1 + o.samples(); i < 1 + o.samples() + conds; i++) {
            final double d = out.get(i).ok() ? dev(out.get(i).value().laurent(), l0, scale) : Double.NaN;
            if (Double.isNaN(d)) {
                lost++;
            } else {
                moved = Math.max(moved, d);
            }
        }
        double movedWide = 0;
        for (int i = 1 + o.samples() + conds; i < 1 + o.samples() + conds + WIDE_SAMPLES; i++) {
            final double d = out.get(i).ok() ? dev(out.get(i).value().laurent(), l0, scale) : Double.NaN;
            if (Double.isNaN(d)) {
                lost++;
            } else {
                movedWide = Math.max(movedWide, d);
            }
        }
        // the move by 1e-11 measures kappa unless the noise drowns it; then the move by 1e-7 does, unless
        // the noise drowns that too (kappa is then unknown: NaN)
        final double kappa = moved > 30 * noise ? moved / COND_DELTA
            : movedWide > 30 * noise ? movedWide / WIDE_DELTA : Double.NaN;
        final double expected = Double.isNaN(kappa) ? 2 * U : Math.max(kappa * U, 2 * U);
        final double floor = Math.max(noise, expected);
        final List<Check> checks = new ArrayList<>();
        int at = 1 + o.samples() + conds + WIDE_SAMPLES;
        double quadrature = 0;
        for (Spec c : specs) {
            final Golem95.Outcome<GolemEngine.Value> r = out.get(at++);
            final double allowed = c.exact() ? Math.max(1e-13, 30 * floor) : Math.max(10 * s.tolerance(), 30 * floor);
            if (!r.ok()) {
                checks.add(new Check(c.name(), Double.NaN, allowed, "could not be computed: " + message(r.error()), c.exact()));
                continue;
            }
            final Laurent want = c.expect().apply(l0);
            final double d = dev(r.value().laurent(), want, scale(want));
            if (!c.exact()) {
                quadrature = Math.max(quadrature, d);
            }
            checks.add(new Check(c.name(), d, allowed, c.detail(), c.exact()));
        }
        // Golem95 loses digits the integral does not when its answer moves far more than kappa allows
        final boolean losing = noise > 1e3 * expected && noise > 1e-13;
        double error = Math.max(floor, quadrature);
        boolean broken = false;
        for (Check c : checks) {
            if (!c.passed()) {
                broken = true;
                if (Double.isFinite(c.deviation())) {
                    error = Math.max(error, c.deviation());
                }
            }
        }
        Verdict verdict;
        if (error <= GOOD) {
            verdict = Verdict.RELIABLE;
        } else if (losing) {
            verdict = Verdict.UNSTABLE;
        } else {
            verdict = Verdict.ILL_CONDITIONED;
        }
        if (broken && verdict != Verdict.UNSTABLE) {
            verdict = Verdict.INCONSISTENT;
        }
        final Kinematics.Analysis an = k.pinched(q.pinched()).analyse();
        Rescue rescue = null;
        String noRescue = null;
        if (o.rescue() && verdict == Verdict.UNSTABLE) {
            // the lines through the point must stay clear of the integral's own singularities
            final double reach = Math.min(1e-1, an.thresholdDistance() / 3);
            if (an.landau() || reach < 1e-3) {
                noRescue = "Not rescued: the integral is not smooth here (" + (an.landau() ? "det S vanishes"
                    : "a threshold is near") + "), so points around it say nothing reliable about it.";
            } else {
                rescue = rescue(q, k, s, l0, scale, reach, new Random(rng.nextLong()));
                evaluations += rescue == null ? 0 : rescue.points();
            }
        }
        final List<String> findings = findings(q, n, v0, an, verdict, noise, kappa, error, checks, lost, rescue, l0, scale);
        if (noRescue != null) {
            findings.add(noRescue);
        }
        return new Report(q, k, s, v0, noise, kappa, error, digits(error), verdict, List.copyOf(checks), rescue,
            List.copyOf(findings), evaluations, System.nanoTime() - t0);
    }

    private static List<Spec> specs(Quantity q, Kinematics k, GolemEngine.Settings s, Random rng) {
        final List<Spec> out = new ArrayList<>();
        final int n = k.n();
        final int d = q.scaling(n);
        for (double lambda : new double[]{256, 1.0 / 256}) {
            final double f = Math.pow(lambda, d);
            out.add(new Spec(lambda > 1 ? "units x 256" : "units / 256",
                String.format(Locale.ROOT, "every S_ij and mu^2 %s 256 multiply it by 256^%d exactly", lambda > 1 ? "times" : "over",
                    lambda > 1 ? d : -d),
                Golem95.task(q, k.scaled(lambda), s.withMu2(s.mu2() * lambda)), l -> l.times(f), true));
        }
        final double mu2 = 4 * s.mu2();
        out.add(new Spec("mu^2 x 4", "at 4 mu^2 it is the series at mu^2, re-expanded",
            Golem95.task(q, k, s.withMu2(mu2)), l -> l.atScale(mu2), true));
        if (n >= 2) {
            // rotations and reflections only: they keep each leg between the same two propagators, so the
            // integral stays one Golem95 recognises (another permutation may put the invariants s and t
            // where Golem95 expects leg masses, and it gives up on a kinematics it calls unphysical)
            final List<int[]> perms = new ArrayList<>();
            final int[] rev = new int[n];
            for (int i = 0; i < n; i++) {
                rev[i] = n - i;
            }
            perms.add(rev);
            for (int tries = 0; n >= 3 && perms.size() < 3 && tries < 50; tries++) {
                final int[] p = dihedral(n, rng);
                if (!seen(perms, p) && !identity(p)) {
                    perms.add(p);
                }
            }
            for (int[] p : perms) {
                out.add(new Spec("renamed " + Arrays.toString(p).replaceAll("[\\[\\],]", ""),
                    "the same integral with its propagators renamed, which Golem95 computes another way",
                    Golem95.task(q.relabelled(p), k.permuted(p), s), l -> l, true));
            }
        }
        out.addAll(orderSpecs(q, k, s, 2));
        final int points = n - q.pinched().length;
        if (q.isScalar() && points <= 4) {
            if (q instanceof Quantity.Scalar) {
                out.add(new Spec("Golem95 A^{N,0}", "Golem95's own scalar integral, against OneLOop's",
                    Golem95.task(new Quantity.FormFactor('a', new int[0], q.pinched()), k, s), l -> l, true));
            } else {
                out.add(new Spec("OneLOop", "the scalar integral by OneLOop, independent of Golem95",
                    Golem95.task(new Quantity.Scalar(q.pinched()), k, s), l -> l, true));
            }
        }
        final Kinematics.Analysis an = k.pinched(q.pinched()).analyse();
        if (!(q instanceof Quantity.Scalar) && (points == 3 || points == 4) && an.normalizedB() <= s.cut()) {
            out.add(new Spec("tolerance / 100", "Golem95 integrates numerically here: the same with a 100 times tighter tolerance",
                Golem95.task(q, k, s.withTolerance(s.tolerance() / 100)), l -> l, false));
        }
        return out;
    }

    /**
     * Up to {@code count} checks of a form factor (or basis integral) above rank N with its labels in other
     * orders, the reverse and a rotation: the integral is symmetric in them, and Golem95 reduces them in the
     * order given, which above rank N changes its answer by 1e-9 (a triangle at rank 4) to 3e-3 (a box at rank
     * 7). At rank N and below the orders agree to the rounding, and nothing is checked.
     */
    private static List<Spec> orderSpecs(Quantity q, Kinematics k, GolemEngine.Settings s, int count) {
        final List<Spec> out = new ArrayList<>();
        final int[] l = q instanceof Quantity.FormFactor ff ? ff.labels()
            : q instanceof Quantity.Integral in ? in.labels() : new int[0];
        if (!rankAbove(q, k.n() - q.pinched().length) || Arrays.stream(l).distinct().count() < 2) {
            return out;
        }
        final int[] reverse = new int[l.length];
        final int[] rotation = new int[l.length];
        for (int i = 0; i < l.length; i++) {
            reverse[i] = l[l.length - 1 - i];
            rotation[i] = l[(i + 1) % l.length];
        }
        final List<int[]> orders = new ArrayList<>();
        for (int[] o : List.of(reverse, rotation)) {
            if (orders.size() < count && !Arrays.equals(o, l) && !seen(orders, o)) {
                orders.add(o);
            }
        }
        for (int[] o : orders) {
            final Quantity other = q instanceof Quantity.FormFactor ff ? new Quantity.FormFactor(ff.type(), o, ff.pinched())
                : new Quantity.Integral(((Quantity.Integral) q).dimPlus(), o, q.pinched());
            out.add(new Spec("labels " + Arrays.toString(o).replaceAll("[\\[\\],]", ""),
                "the same with its labels in another order, in which the integral is symmetric",
                Golem95.task(other, k, s), x -> x, true));
        }
        return out;
    }

    /* ---------------------------------------------------------------- rescue */

    /** Chebyshev nodes on each line: enough for analytic functions, few enough to stay cheap. */
    private static final int NODES = 12;

    /** Lines through the point: two must agree, the third confirms. */
    private static final int LINES = 3;

    /**
     * Interpolates along three random lines through the point, at radii up to {@code reach}: the nodes
     * nearest the point are a radius times sin(pi/24) away from it, where the evaluation is stable again.
     * Every parameter moves at a rate between 0.5 and 1.5 along each line (a parameter that hardly moved
     * would keep the line near the instability). At each radius, the two lines that agree best give the
     * value and their agreement the error; the radius with the best pair wins, and the third line says
     * whether it confirms.
     */
    private static Rescue rescue(Quantity q, Kinematics k, GolemEngine.Settings s, Laurent l0, double[] scale, double reach,
                                 Random rng) {
        final int n = k.n();
        final double[] radii = {reach / 30, reach / 10, reach / 3, reach};
        final double[][][] dirs = new double[LINES][n][n];
        for (double[][] d : dirs) {
            for (int i = 0; i < n; i++) {
                for (int j = i; j < n; j++) {
                    d[i][j] = d[j][i] = (rng.nextBoolean() ? 1 : -1) * (0.5 + rng.nextDouble());
                }
            }
        }
        final List<Callable<GolemEngine.Value>> tasks = new ArrayList<>();
        for (double r : radii) {
            for (double[][] d : dirs) {
                for (int j = 0; j < NODES; j++) {
                    tasks.add(Golem95.task(q, k.deformed(d, r * node(j)), s));
                }
            }
        }
        final List<Golem95.Outcome<GolemEngine.Value>> out = Golem95.all(tasks);
        final Laurent[][] est = new Laurent[radii.length][dirs.length];
        final Laurent[][][] values = new Laurent[radii.length][dirs.length][];
        int at = 0;
        for (int ri = 0; ri < radii.length; ri++) {
            for (int di = 0; di < dirs.length; di++) {
                final Laurent[] f = new Laurent[NODES];
                for (int j = 0; j < NODES; j++) {
                    final Golem95.Outcome<GolemEngine.Value> o = out.get(at++);
                    f[j] = o.ok() ? o.value().laurent() : null;
                }
                values[ri][di] = f;
                est[ri][di] = interpolate(f, l0.mu2());
            }
        }
        int best = -1;
        int bi = -1;
        int bj = -1;
        double agreement = Double.POSITIVE_INFINITY;
        for (int ri = 0; ri < radii.length; ri++) {
            for (int i = 0; i < LINES; i++) {
                for (int j = i + 1; j < LINES; j++) {
                    if (est[ri][i] == null || est[ri][j] == null) {
                        continue;
                    }
                    final double a = dev(est[ri][i], est[ri][j], scale);
                    if (a < agreement) {
                        agreement = a;
                        best = ri;
                        bi = i;
                        bj = j;
                    }
                }
            }
        }
        if (best < 0) {
            return null;
        }
        final Laurent value = est[best][bi].plus(est[best][bj]).times(0.5);
        final double error = Math.max(agreement, 4 * U);
        final int third = LINES - bi - bj;
        final double confirm = est[best][third] == null ? Double.NaN : dev(est[best][third], value, scale);
        // the integral's own conditioning, from how the stable points of the two lines move away from the value
        final double[] around = scale(value);
        double kappa = 0;
        for (int di : new int[]{bi, bj}) {
            double size = 0;
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    size = Math.max(size, Math.abs(dirs[di][a][b]));
                }
            }
            for (int j = 0; j < NODES; j++) {
                final double h = Math.abs(radii[best] * node(j));
                final double d = dev(values[best][di][j], value, around);
                if (Double.isFinite(d)) {
                    kappa = Math.max(kappa, d / (h * size));
                }
            }
        }
        return new Rescue(value, error, tasks.size(), radii[best], kappa, String.format(Locale.ROOT,
            "about %d digits: interpolated from %d stable points at relative distances up to %.0e along two lines, "
                + "which agree to %.1e; a third line %s", digits(error), NODES * 2, radii[best], agreement,
            Double.isNaN(confirm) ? "could not be computed" : confirm <= 10 * error
                ? String.format(Locale.ROOT, "confirms it (%.1e)", confirm)
                : String.format(Locale.ROOT, "is %.1e from it, where Golem95 is still unstable along it", confirm)));
    }

    /** The j-th Chebyshev node of the first kind on [-1, 1]: never 0 for an even count. */
    private static double node(int j) {
        return Math.cos((2 * j + 1) * Math.PI / (2 * NODES));
    }

    /** The interpolating polynomial through the nodes, at 0 (barycentric formula, Chebyshev weights). */
    private static Laurent interpolate(Laurent[] f, double mu2) {
        Cx a = Cx.ZERO;
        Cx b = Cx.ZERO;
        Cx c = Cx.ZERO;
        double den = 0;
        for (int j = 0; j < f.length; j++) {
            if (f[j] == null || !f[j].isFinite()) {
                return null;
            }
            final double w = ((j & 1) == 0 ? 1 : -1) * Math.sin((2 * j + 1) * Math.PI / (2 * NODES)) / (0 - node(j));
            a = Cx.add(a, Cx.mul(w, f[j].a()));
            b = Cx.add(b, Cx.mul(w, f[j].b()));
            c = Cx.add(c, Cx.mul(w, f[j].c()));
            den += w;
        }
        return new Laurent(Cx.div(a, den), Cx.div(b, den), Cx.div(c, den), mu2);
    }

    /* ---------------------------------------------------------------- what it means */

    private static List<String> findings(Quantity q, int n, GolemEngine.Value v0, Kinematics.Analysis an, Verdict verdict,
                                         double noise, double kappa, double error, List<Check> checks, int lost,
                                         Rescue rescue, Laurent l0, double[] scale) {
        final List<String> f = new ArrayList<>();
        final int points = n - q.pinched().length;
        final double allows = Math.max(kappa * U, 2 * U);
        switch (verdict) {
            case RELIABLE -> {
                if (noise > 1e3 * allows && noise > 1e-13) {
                    f.add(String.format(Locale.ROOT, "Reliable to about %d digits, though Golem95 loses about %d more than the "
                            + "integral requires: its answer moves by %.0e where the conditioning (%.2g) accounts for %.0e.",
                        digits(error), Math.round(Math.log10(noise / allows)), noise, kappa, allows));
                } else {
                    f.add(String.format(Locale.ROOT,
                        "Reliable: the answer moves as little as the integral's conditioning (%.2g) allows%s.", kappa,
                        checks.isEmpty() ? "" : ", and every check holds"));
                }
            }
            case ILL_CONDITIONED -> {
                f.add(String.format(Locale.ROOT, "Ill-conditioned: the integral itself is this sensitive here (a change in the "
                    + "last digits of the invariants moves it by %.0e, as its conditioning %.2g says), so no double-precision "
                    + "program does much better than about %d digits.", Math.max(noise, kappa * U), kappa, digits(error)));
                for (String t : an.thresholds()) {
                    f.add("   " + t);
                }
                if (an.landau()) {
                    f.add("   det S vanishes: the leading Landau singularity of the " + points + "-point integral is here.");
                }
            }
            case UNSTABLE -> {
                f.add(Double.isNaN(kappa)
                    ? String.format(Locale.ROOT, "Unstable: Golem95's answer moves by %.0e when the kinematics moves by 1e-15, "
                    + "a noise that drowns even a move by 1e-7: the evaluation loses digits the integral does not%s.", noise,
                    rescue == null ? "" : String.format(Locale.ROOT, " (whose conditioning, from the stable points around, "
                        + "is about %.2g)", rescue.conditioning()))
                    : String.format(Locale.ROOT, "Unstable: Golem95's answer moves by %.0e when the kinematics moves by 1e-15, "
                    + "while the integral's own conditioning (%.2g) accounts for %.0e: the evaluation loses digits the "
                    + "integral does not.", noise, kappa, allows));
                if (an.bIsNoise()) {
                    f.add(String.format(Locale.ROOT, "   The cause: B = sum (S^-1)_ij is zero but for rounding here (%.0e of its "
                        + "terms). Golem95 reduces %s through 1/B whenever B is not exactly 0; its fallback, a Gram-matrix "
                        + "(Passarino-Veltman) reduction, is no better there, since B vanishes with the Gram determinant.",
                        an.relativeB(), rankAbove(q, points) ? "higher-rank integrals" : "this integral"));
                } else if (an.normalizedB() < 1e-6) {
                    f.add(String.format(Locale.ROOT, "   The likely cause: the Gram determinant is small (|B| max|S| = %.1e).",
                        an.normalizedB()));
                } else if (an.condition() > 1e8) {
                    f.add(String.format(Locale.ROOT, "   The likely cause: S is ill-conditioned (%.1e).", an.condition()));
                }
            }
            case INCONSISTENT -> {
                f.add("Inconsistent: an exact property of the integral does not hold for Golem95's answers.");
                for (Check c : checks) {
                    if (!c.passed()) {
                        f.add("   " + c.name() + ": " + (Double.isNaN(c.deviation()) ? c.detail()
                            : String.format(Locale.ROOT, "off by %.1e (%s)", c.deviation(), c.detail())));
                        if (c.name().startsWith("units")) {
                            f.add("   An exact rescaling changing the answer means a decision inside Golem95 depends on the "
                                + "units: it compares det S with 1e-15 and invariants with 10 eps, and OneLOop sets masses "
                                + "and invariants under 1e-10 to zero, all in absolute terms. Invariants of order 1 avoid it.");
                        }
                        if (c.name().startsWith("labels")) {
                            f.add("   Above rank N, Golem95 reduces a form factor in the order its labels are given, and the "
                                + "orders disagree beyond the rounding: the integral is symmetric in them, so their spread "
                                + "is part of the error. Sphere's tensors take the mean over the orders.");
                        }
                    }
                }
            }
            default -> {
            }
        }
        if (v0.accuracyWarning()) {
            f.add("Golem95 itself warned that it could not reach the accuracy it was asked for.");
        }
        for (String s : v0.said()) {
            f.add("Golem95: " + s);
        }
        if (lost > 0) {
            f.add(lost + " of the moved kinematics could not be computed: Golem95 is at an edge of what it handles.");
        }
        if (rescue != null) {
            final double gap = dev(rescue.value(), l0, scale);
            if (rescued(rescue, error)) {
                f.add(String.format(Locale.ROOT, "Use the rescued value: about %d digits instead of %d; Golem95's own is %.1e from it.",
                    digits(rescue.error()), digits(error), gap));
            } else {
                f.add("The rescue does no better than Golem95's own answer: the evaluation is as unstable all around this point.");
            }
        }
        return f;
    }

    /** A rescue worth using: ten times more accurate than the answer it replaces. */
    static boolean rescued(Rescue r, double error) {
        return r != null && r.error() * 10 < error;
    }

    private static boolean rankAbove(Quantity q, int points) {
        if (q instanceof Quantity.FormFactor ff) {
            return ff.rank() > points;
        }
        if (q instanceof Quantity.Integral in) {
            return in.labels().length + in.dimPlus() / 2 > points;
        }
        return false;
    }

    /* ---------------------------------------------------------------- measures */

    /**
     * Per coefficient, what a deviation is relative to: the coefficient itself, unless it is under a
     * millionth of the series (a pole that is absent, computed as rounding), which is measured against
     * the whole series instead.
     */
    static double[] scale(Laurent v) {
        final double size = Math.max(v.size(), Double.MIN_NORMAL);
        final double[] out = {Cx.abs(v.a()), Cx.abs(v.b()), Cx.abs(v.c())};
        for (int i = 0; i < 3; i++) {
            if (!(out[i] >= 1e-6 * size)) {
                out[i] = size;
            }
        }
        return out;
    }

    /** The largest relative deviation of a coefficient; NaN if either is not finite. */
    static double dev(Laurent x, Laurent y, double[] scale) {
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
            return Double.NaN;
        }
        return Math.max(Cx.abs(Cx.sub(x.a(), y.a())) / scale[0],
            Math.max(Cx.abs(Cx.sub(x.b(), y.b())) / scale[1], Cx.abs(Cx.sub(x.c(), y.c())) / scale[2]));
    }

    /** The correct digits a relative error leaves (0 to 16), for callers outside the package. */
    public static int digitsOf(double error) {
        return digits(error);
    }

    /** The correct digits a relative error leaves (0 to 16). */
    static int digits(double error) {
        if (!(error >= 0)) {
            return 0;
        }
        return (int) Math.max(0, Math.min(16, Math.floor(-Math.log10(Math.max(3 * error, 1e-16)))));
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** A random rotation, possibly reflected, of the propagators 1..n: i -> +-(i + shift) mod n. */
    private static int[] dihedral(int n, Random rng) {
        final int shift = rng.nextInt(n);
        final boolean reflect = rng.nextBoolean();
        final int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            final int j = reflect ? n - 1 - i : i;
            p[i] = Math.floorMod(j + shift, n) + 1;
        }
        return p;
    }

    private static boolean identity(int[] p) {
        for (int i = 0; i < p.length; i++) {
            if (p[i] != i + 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean seen(List<int[]> l, int[] p) {
        for (int[] x : l) {
            if (Arrays.equals(x, p)) {
                return true;
            }
        }
        return false;
    }
}
