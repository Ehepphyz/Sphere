package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The statistics that decide whether a learned shower is Geant4's, built so that a perfect model is not failed by
 * chance and a poor one is not passed for lack of showers.
 *
 * <ul>
 * <li>Every comparison is an interval, a bootstrap over the showers of both samples, and a test has three outcomes:
 * shown equal within its margin, shown different, or not yet known. The third asks for more showers rather than a
 * guess.</li>
 * <li>The distance between the mean profiles is debiased: what each finite sample adds to a squared difference -- the
 * delta method's variance of a ratio of sums -- is taken off, so that it is zero on average for a perfect model,
 * where the raw distance between two samples of the same simulation is not (at 255 MeV, 500 showers of Geant4 against
 * 500 others differ by 2.9% of the shower).</li>
 * <li>The reference showers are spread over the energies so that each is measured as precisely as the others: a shower
 * costs in proportion to its energy and fluctuates as 1/E, so the cheap, noisy low energies get more of them.</li>
 * </ul>
 */
public final class FastSimStats {

    private FastSimStats() {
    }

    public enum Verdict { PASS, FAIL, UNDECIDED }

    /** An estimate and its 90% interval. */
    public record Interval(double value, double low, double high) {
        Verdict within(double min, double max) {
            if (low >= min && high <= max) {
                return Verdict.PASS;
            }
            return high < min || low > max ? Verdict.FAIL : Verdict.UNDECIDED;
        }
    }

    /**
     * Full simulation against fast at one energy. response: mean fast / mean full - 1; width: the ratio of the relative
     * widths; profile: the debiased squared distance between the mean shares of the cells.
     */
    public record Point(double energy, int nFull, int nFast, Interval response, double responseMargin, Interval width, Interval profile,
                        double profileRaw, double ksTotal, double ksDepth, double ksLimit, double resolutionFull, double resolutionFast,
                        double[] profileFull, double[] profileFast, Verdict verdict, List<String> failing, List<String> undecided) {

        public double responseDelta() {
            return response.value();
        }

        public boolean passed() {
            return verdict == Verdict.PASS;
        }

        /** The debiased distance between the mean profiles, as a share of the shower: 0 when it is within the noise. */
        public double profileDistance() {
            return Math.sqrt(Math.max(0, profile.value()));
        }

        /** Failed on its scale alone: a calibration, not a new training. */
        public boolean onlyResponse() {
            return !failing.isEmpty() && failing.stream().allMatch("response"::equals);
        }

        public String describe() {
            return String.format(Locale.ROOT, "%8.1f MeV  %-9s response %+.2f%% [%+.2f, %+.2f] (within %.2f%%)  width %.3f [%.3f, %.3f]  profile %.2f%% (raw %.2f%%)"
                    + "  KS %.3f/%.3f (%.3f)  n %d/%d%s", energy, verdict, 100 * response.value(), 100 * response.low(), 100 * response.high(),
                100 * responseMargin, width.value(), width.low(), width.high(), 100 * profileDistance(), 100 * profileRaw, ksTotal, ksDepth, ksLimit,
                nFull, nFast, failing.isEmpty() && undecided.isEmpty() ? "" : "  " + (failing.isEmpty() ? "" : "fails: " + String.join(", ", failing) + " ")
                    + (undecided.isEmpty() ? "" : "not yet known: " + String.join(", ", undecided)));
        }
    }

    /** Margins, stated before any result: the mean profile within 2% of the shower, the width within 10%, the response within 1% or 5% of the resolution. */
    static final double PROFILE_MARGIN = 0.02, WIDTH_MARGIN = 0.10, RESPONSE_FLOOR = 0.01, RESPONSE_OF_RESOLUTION = 0.05, KS_FLOOR = 0.02;
    static final int BOOTSTRAP = 200;

    private record Moments(double mean, double sd, double[] profile) {
    }

    /** Rows are showers, columns cells, in MeV; 90% intervals. */
    static Point compare(double energy, double[][] full, double[][] fast, long seed) {
        return compare(energy, full, fast, seed, 0.90);
    }

    /** The same with intervals at this level: a second look at a stricter one, so that looking twice does not make a pass easier. */
    static Point compare(double energy, double[][] full, double[][] fast, long seed, double level) {
        if (full.length < 2 || fast.length < 2) {
            throw new IllegalStateException(String.format(Locale.ROOT, "too few showers at %.1f MeV to compare: %d and %d", energy, full.length, fast.length));
        }
        final int d = full[0].length;
        final double[] sa = totals(full), sb = totals(fast);
        final Moments ma = moments(full, sa, null), mb = moments(fast, sb, null);
        final double[] va = profileVariance(full, sa, ma.profile()), vb = profileVariance(fast, sb, mb.profile());
        double noise = 0, raw2 = 0, raw = 0;
        for (int i = 0; i < d; i++) {
            final double diff = ma.profile()[i] - mb.profile()[i];
            noise += va[i] + vb[i];
            raw2 += diff * diff;
            raw += Math.abs(diff);
        }
        final double theta = raw2 - noise, delta = mb.mean() / ma.mean() - 1, ratio = (mb.sd() / mb.mean()) / (ma.sd() / ma.mean());
        final SplittableRandom random = new SplittableRandom(seed);
        final double[] bd = new double[BOOTSTRAP], br = new double[BOOTSTRAP], bt = new double[BOOTSTRAP];
        final int[] ia = new int[full.length], ib = new int[fast.length];
        for (int k = 0; k < BOOTSTRAP; k++) {
            for (int i = 0; i < ia.length; i++) {
                ia[i] = random.nextInt(full.length);
            }
            for (int i = 0; i < ib.length; i++) {
                ib[i] = random.nextInt(fast.length);
            }
            final Moments xa = moments(full, sa, ia), xb = moments(fast, sb, ib);
            bd[k] = xb.mean() / xa.mean() - 1;
            br[k] = (xb.sd() / xb.mean()) / (xa.sd() / xa.mean());
            double s2 = 0;
            for (int i = 0; i < d; i++) {
                final double diff = xa.profile()[i] - xb.profile()[i];
                s2 += diff * diff;
            }
            bt[k] = s2 - noise - raw2;   // the bootstrap's estimate less its own truth, the plug-in distance
        }
        final double lo = (1 - level) / 2, hi = 1 - lo;
        final Interval response = percentile(delta, bd, lo, hi), width = percentile(ratio, br, lo, hi);
        Arrays.sort(bt);
        final Interval profile = new Interval(theta, theta - quantile(bt, hi), theta - quantile(bt, lo));
        final double resFull = ma.sd() / ma.mean(), resFast = mb.sd() / mb.mean();
        final double margin = Math.max(RESPONSE_FLOOR, RESPONSE_OF_RESOLUTION * resFull);
        final List<String> failing = new ArrayList<>(), undecided = new ArrayList<>();
        sort(response.within(-margin, margin), "response", failing, undecided);
        sort(width.within(1 - WIDTH_MARGIN, 1 + WIDTH_MARGIN), "width", failing, undecided);
        final double m2 = PROFILE_MARGIN * PROFILE_MARGIN;
        sort(profile.high() <= m2 ? Verdict.PASS : profile.low() > m2 ? Verdict.FAIL : Verdict.UNDECIDED, "profile", failing, undecided);
        final double ksTotal = ks(sa, sb), ksDepth = ks(depth(full, sa), depth(fast, sb));
        final double limit = 1.95 * Math.sqrt((double) (full.length + fast.length) / ((double) full.length * fast.length)) + KS_FLOOR;
        if (ksTotal > limit) {
            failing.add("energy distribution");
        }
        if (ksDepth > limit) {
            failing.add("depth");
        }
        final Verdict v = !failing.isEmpty() ? Verdict.FAIL : !undecided.isEmpty() ? Verdict.UNDECIDED : Verdict.PASS;
        return new Point(energy, full.length, fast.length, response, margin, width, profile, raw / 2, ksTotal, ksDepth, limit, resFull, resFast,
            ma.profile(), mb.profile(), v, List.copyOf(failing), List.copyOf(undecided));
    }

    private static void sort(Verdict v, String what, List<String> failing, List<String> undecided) {
        if (v == Verdict.FAIL) {
            failing.add(what);
        } else if (v == Verdict.UNDECIDED) {
            undecided.add(what);
        }
    }

    static double[] totals(double[][] rows) {
        final double[] out = new double[rows.length];
        for (int i = 0; i < rows.length; i++) {
            for (double v : rows[i]) {
                out[i] += v;
            }
        }
        return out;
    }

    /** The mean and width of the totals, and the cells' shares of their sum, over the showers {@code index} names (all when null). */
    private static Moments moments(double[][] rows, double[] totals, int[] index) {
        final int n = index == null ? rows.length : index.length, d = rows[0].length;
        double s = 0, s2 = 0;
        final double[] cells = new double[d];
        for (int k = 0; k < n; k++) {
            final int e = index == null ? k : index[k];
            final double t = totals[e];
            s += t;
            s2 += t * t;
            final double[] row = rows[e];
            for (int i = 0; i < d; i++) {
                cells[i] += row[i];
            }
        }
        final double mean = s / n, var = Math.max(0, (s2 - n * mean * mean) / Math.max(1, n - 1));
        for (int i = 0; i < d; i++) {
            cells[i] = s > 0 ? cells[i] / s : 0;
        }
        return new Moments(mean, Math.sqrt(var), cells);
    }

    /** The variance of each share, a ratio of sums: the delta method's (sum of (c - p S)^2) / (n (n - 1) mean(S)^2). */
    private static double[] profileVariance(double[][] rows, double[] totals, double[] p) {
        final int n = rows.length, d = p.length;
        double mean = 0;
        for (double t : totals) {
            mean += t;
        }
        mean /= n;
        final double[] v = new double[d];
        if (!(mean > 0) || n < 2) {
            return v;
        }
        for (int e = 0; e < n; e++) {
            for (int i = 0; i < d; i++) {
                final double psi = (rows[e][i] - p[i] * totals[e]) / mean;
                v[i] += psi * psi;
            }
        }
        for (int i = 0; i < d; i++) {
            v[i] /= (double) n * (n - 1);
        }
        return v;
    }

    /** The shower's centre of gravity along the cells. */
    static double[] depth(double[][] rows, double[] totals) {
        final double[] out = new double[rows.length];
        for (int e = 0; e < rows.length; e++) {
            double w = 0;
            for (int i = 0; i < rows[e].length; i++) {
                w += i * rows[e][i];
            }
            out[e] = totals[e] > 0 ? w / totals[e] : 0;
        }
        return out;
    }

    /** The two-sample Kolmogorov-Smirnov statistic. */
    static double ks(double[] a0, double[] b0) {
        final double[] a = a0.clone(), b = b0.clone();
        Arrays.sort(a);
        Arrays.sort(b);
        int i = 0, j = 0;
        double d = 0;
        while (i < a.length && j < b.length) {
            final double x = Math.min(a[i], b[j]);
            while (i < a.length && a[i] <= x) {
                i++;
            }
            while (j < b.length && b[j] <= x) {
                j++;
            }
            d = Math.max(d, Math.abs((double) i / a.length - (double) j / b.length));
        }
        return d;
    }

    private static Interval percentile(double value, double[] replicas, double lo, double hi) {
        final double[] s = replicas.clone();
        Arrays.sort(s);
        return new Interval(value, quantile(s, lo), quantile(s, hi));
    }

    private static double quantile(double[] sorted, double q) {
        final double x = q * (sorted.length - 1);
        final int i = (int) Math.floor(x);
        final double f = x - i;
        return i + 1 < sorted.length ? sorted[i] * (1 - f) + sorted[i + 1] * f : sorted[sorted.length - 1];
    }

    // ------------------------------------------------------------------
    // Where the simulation time goes
    // ------------------------------------------------------------------

    /**
     * The relative variance of the response (the energy seen over the energy given) against the energy, as
     * exp(a + b ln E): a straight line in ln-ln through equal bins of the pilot's showers. {a, b}.
     */
    static double[] varianceModel(double[] energies, double[] response) {
        final int n = energies.length;
        final Integer[] order = new Integer[n];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (x, y) -> Double.compare(energies[x], energies[y]));
        final int bins = Math.max(1, Math.min(6, n / 50));
        final double[] lx = new double[bins], ly = new double[bins], w = new double[bins];
        int used = 0;
        for (int b = 0; b < bins; b++) {
            final int from = b * n / bins, to = (b + 1) * n / bins;
            double se = 0, s = 0, s2 = 0;
            for (int k = from; k < to; k++) {
                se += Math.log(energies[order[k]]);
                s += response[order[k]];
                s2 += response[order[k]] * response[order[k]];
            }
            final int m = to - from;
            final double mean = s / m, var = (s2 - m * mean * mean) / Math.max(1, m - 1);
            if (m >= 10 && mean > 0 && var > 0) {
                lx[used] = se / m;
                ly[used] = Math.log(var / (mean * mean));
                w[used] = m;
                used++;
            }
        }
        if (used >= 2) {
            final double[] c = polyfit(Arrays.copyOf(lx, used), Arrays.copyOf(ly, used), Arrays.copyOf(w, used), 1);
            if (c[1] < 0 && c[1] > -2) {
                return c;
            }
        }
        // a stochastic term alone, through the mean: relative variance as 1/E
        double se = 0, s = 0, s2 = 0;
        for (int k = 0; k < n; k++) {
            se += Math.log(energies[k]);
            s += response[k];
            s2 += response[k] * response[k];
        }
        final double mean = s / Math.max(1, n), var = Math.max(1e-12, (s2 - n * mean * mean) / Math.max(1, n - 1));
        return new double[] {Math.log(var / (mean * mean)) + se / Math.max(1, n), -1};
    }

    /**
     * Showers at each energy, so that each is measured as precisely as the others within {@code seconds}: n_k
     * proportional to the relative variance there, exp(a + b ln E_k), for a cost of {@code secondsPerMeV} E_k each.
     */
    static int[] allocate(double[] energies, double[] variance, double secondsPerMeV, double seconds, int floor) {
        double sum = 0;
        final double[] v = new double[energies.length];
        for (int k = 0; k < energies.length; k++) {
            v[k] = Math.exp(variance[0] + variance[1] * Math.log(energies[k]));
            sum += v[k] * energies[k];
        }
        final int[] n = new int[energies.length];
        for (int k = 0; k < energies.length; k++) {
            n[k] = (int) Math.max(floor, Math.round(seconds / secondsPerMeV * v[k] / sum));
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Least squares and the chi-square
    // ------------------------------------------------------------------

    /** Weighted least squares of a polynomial in t: c_0 + c_1 t + ... (fewer terms if the points cannot hold more). */
    static double[] polyfit(double[] t, double[] y, double[] w, int degree) {
        for (int deg = Math.min(degree, t.length - 1); deg >= 0; deg--) {
            final int k = deg + 1;
            final double[][] m = new double[k][k + 1];
            for (int i = 0; i < t.length; i++) {
                double pr = 1;
                final double[] p = new double[k];
                for (int r = 0; r < k; r++) {
                    p[r] = pr;
                    pr *= t[i];
                }
                for (int r = 0; r < k; r++) {
                    for (int c = 0; c < k; c++) {
                        m[r][c] += w[i] * p[r] * p[c];
                    }
                    m[r][k] += w[i] * p[r] * y[i];
                }
            }
            final double[] c = solve(m);
            if (c != null) {
                final double[] out = new double[degree + 1];
                System.arraycopy(c, 0, out, 0, c.length);
                return out;
            }
        }
        return new double[degree + 1];
    }

    static double poly(double[] c, double t) {
        double v = 0;
        for (int i = c.length - 1; i >= 0; i--) {
            v = v * t + c[i];
        }
        return v;
    }

    /** Gaussian elimination with partial pivoting on an augmented matrix; null when it is singular. */
    private static double[] solve(double[][] a) {
        final int k = a.length;
        double scale = 0;
        for (double[] row : a) {
            for (int c = 0; c < k; c++) {
                scale = Math.max(scale, Math.abs(row[c]));
            }
        }
        for (int i = 0; i < k; i++) {
            int pivot = i;
            for (int r = i + 1; r < k; r++) {
                if (Math.abs(a[r][i]) > Math.abs(a[pivot][i])) {
                    pivot = r;
                }
            }
            if (!(Math.abs(a[pivot][i]) > 1e-12 * scale)) {
                return null;
            }
            final double[] tmp = a[i];
            a[i] = a[pivot];
            a[pivot] = tmp;
            for (int r = 0; r < k; r++) {
                if (r != i) {
                    final double f = a[r][i] / a[i][i];
                    for (int c = i; c <= k; c++) {
                        a[r][c] -= f * a[i][c];
                    }
                }
            }
        }
        final double[] out = new double[k];
        for (int i = 0; i < k; i++) {
            out[i] = a[i][k] / a[i][i];
        }
        return out;
    }

    /** P(chi-square with k degrees of freedom > x). */
    static double chi2Above(double x, int k) {
        return x <= 0 ? 1 : 1 - FastSimPlanner.regularizedGamma(0.5 * k, 0.5 * x);
    }
}
