package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an experienced Minuit user does when a fit goes wrong, done by the
 * program: Migrad first; when it fails, what the failure calls for (more
 * calls, a higher strategy, Simplex to get out of a bad region, Hesse with
 * central differences); starts spread over the parameter space when asked or
 * when nothing else worked, keeping the best minimum and noting the other
 * minima found; and a diagnosis in words of the result: parameters at their
 * limits, correlations that make parameters interchangeable, flat
 * directions, errors that are not parabolic, a covariance that is not to be
 * trusted. Minuit2 itself is untouched: every step is a plain Minuit2 call
 * whose numbers are the C++'s.
 */
public final class Autopilot {

    private Autopilot() {
    }

    /** What to try. */
    public record Options(int strategy, double tolerance, int maxfcn, int multistart, boolean minos, boolean verbose) {
        public static final Options DEFAULT = new Options(1, 0.1, 0, 0, false, false);

        public Options withMultistart(int k) {
            return new Options(strategy, tolerance, maxfcn, k, minos, verbose);
        }

        public Options withMinos(boolean on) {
            return new Options(strategy, tolerance, maxfcn, multistart, on, verbose);
        }
    }

    /** One attempt. */
    public record Step(String what, FunctionMinimum minimum, int calls, String outcome) {
    }

    /** The best minimum, the attempts, the other minima found, Minos errors when asked, and the diagnosis. */
    public record Report(FunctionMinimum best, List<Step> steps, List<FunctionMinimum> otherMinima, List<MinosError> minos,
                         List<String> diagnosis, int totalCalls) {

        public String text() {
            final StringBuilder s = new StringBuilder();
            s.append("Steps:\n");
            for (Step st : steps) {
                s.append(String.format(Locale.ROOT, "  %-44s %6d calls  %s%n", st.what(), st.calls(), st.outcome()));
            }
            if (!otherMinima.isEmpty()) {
                s.append("Other minima found:\n");
                for (FunctionMinimum m : otherMinima) {
                    s.append(String.format(Locale.ROOT, "  FCN = %.10g at %s%n", m.fval(), values(m.userState())));
                }
            }
            s.append("Diagnosis:\n");
            for (String d : diagnosis) s.append("  - ").append(d).append('\n');
            return s.toString();
        }
    }

    static String values(MnUserParameterState st) {
        final StringBuilder s = new StringBuilder("(");
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            if (i > 0) s.append(", ");
            s.append(st.name(i)).append('=').append(String.format(Locale.ROOT, "%.6g", st.value(i)));
        }
        return s.append(')').toString();
    }

    static String outcome(FunctionMinimum m) {
        if (m.isValid()) {
            return String.format(Locale.ROOT, "valid, FCN = %.10g, EDM = %.3g%s", m.fval(), m.edm(),
                m.hasAccurateCovar() ? "" : m.hasMadePosDefCovar() ? ", covariance made pos-def" : ", covariance approximate");
        }
        final List<String> why = new ArrayList<>();
        if (m.hasReachedCallLimit()) why.add("call limit reached");
        if (m.isAboveMaxEdm()) why.add("EDM above maximum");
        if (!m.state().isValid()) why.add("state invalid");
        if (m.hesseFailed()) why.add("Hesse failed");
        return String.format(Locale.ROOT, "NOT valid (%s), FCN = %.10g", String.join(", ", why), m.fval());
    }

    /** Minimizes with escalation, then (as asked) multi-start, Minos and a diagnosis. */
    public static Report minimize(FCNBase fcn, MnUserParameterState start, Options opt) {
        final List<Step> steps = new ArrayList<>();
        int calls = 0;
        final int npar = start.variableParameters();
        final int maxfcn = opt.maxfcn() > 0 ? opt.maxfcn() : 200 + 100 * npar + 5 * npar * npar;

        FunctionMinimum best = new MnMigrad(fcn, start, new MnStrategy(opt.strategy())).minimize(maxfcn, opt.tolerance());
        calls += best.nfcn();
        steps.add(new Step("Migrad, strategy " + opt.strategy(), best, best.nfcn(), outcome(best)));

        if (!best.isValid() && best.hasReachedCallLimit()) {
            final FunctionMinimum m = new MnMigrad(fcn, best.userState(), new MnStrategy(opt.strategy()))
                .minimize(4 * maxfcn, opt.tolerance());
            calls += m.nfcn();
            steps.add(new Step("Migrad again from there, 4x the calls", m, m.nfcn(), outcome(m)));
            best = better(best, m);
        }
        if (!best.isValid()) {
            final FunctionMinimum m = new MnMinimize(fcn, start, new MnStrategy(2)).minimize(4 * maxfcn, opt.tolerance());
            calls += m.nfcn();
            steps.add(new Step("Migrad then Simplex then Migrad, strategy 2", m, m.nfcn(), outcome(m)));
            best = better(best, m);
        }
        if (!best.isValid()) {
            final FunctionMinimum sx = new MnSimplex(fcn, start, new MnStrategy(2)).minimize(10 * maxfcn, opt.tolerance());
            calls += sx.nfcn();
            steps.add(new Step("Simplex alone, to leave a bad region", sx, sx.nfcn(), outcome(sx)));
            final FunctionMinimum m = new MnMigrad(fcn, sx.userState(), new MnStrategy(2)).minimize(4 * maxfcn, opt.tolerance());
            calls += m.nfcn();
            steps.add(new Step("Migrad, strategy 2, from Simplex's point", m, m.nfcn(), outcome(m)));
            best = better(best, m);
        }

        // spread starts: when asked, or when no single start worked
        final List<FunctionMinimum> others = new ArrayList<>();
        final int k = opt.multistart() > 0 ? opt.multistart() : best.isValid() ? 0 : 8;
        if (k > 0) {
            final List<FunctionMinimum> found = new ArrayList<>();
            found.add(best);
            for (int s = 1; s <= k; s++) {
                final MnUserParameterState st = spread(start, s);
                final FunctionMinimum m = new MnMigrad(fcn, st, new MnStrategy(opt.strategy())).minimize(maxfcn, opt.tolerance());
                calls += m.nfcn();
                steps.add(new Step("Migrad from spread start " + s + "/" + k, m, m.nfcn(), outcome(m)));
                if (m.isValid() || !best.isValid()) found.add(m);
                best = better(best, m);
            }
            for (FunctionMinimum m : found) {
                if (m == best || !m.isValid()) continue;
                if (distinct(m, best) && others.stream().noneMatch(o -> !distinct(o, m))) others.add(m);
            }
        }

        // the covariance: when Migrad left it approximate or forced, Hesse at strategy 2, then 3
        if (best.hasValidParameters() && !best.hasAccurateCovar()) {
            for (int s = 2; s <= 3 && !best.hasAccurateCovar(); s++) {
                final int before = best.nfcn();
                new MnHesse(s).apply(fcn, best, maxfcn);
                calls += best.nfcn() - before;
                steps.add(new Step("Hesse, strategy " + s + (s == 3 ? " (central differences)" : ""), best,
                    best.nfcn() - before, outcome(best)));
            }
        }

        final List<MinosError> minos = new ArrayList<>();
        if (opt.minos() && best.isValid()) {
            final MnMinos mn = new MnMinos(fcn, best, new MnStrategy(opt.strategy()));
            for (int i = 0; i < best.userState().minuitParameters().size(); i++) {
                final MinuitParameter p = best.userState().parameter(i);
                if (p.isFixed() || p.isConst()) continue;
                final MinosError me = mn.minos(i);
                calls += me.nfcn();
                minos.add(me);
            }
            steps.add(new Step("Minos for the " + minos.size() + " variable parameters", best,
                minos.stream().mapToInt(MinosError::nfcn).sum(), "done"));
        }
        return new Report(best, steps, others, minos, diagnose(best, minos, others), calls);
    }

    /** The better of two minima: a valid one, then the lower value. */
    static FunctionMinimum better(FunctionMinimum a, FunctionMinimum b) {
        if (a.isValid() != b.isValid()) return a.isValid() ? a : b;
        return b.fval() < a.fval() ? b : a;
    }

    /** Whether two minima are apart by more than 3 errors in some parameter and in function value. */
    static boolean distinct(FunctionMinimum a, FunctionMinimum b) {
        if (Math.abs(a.fval() - b.fval()) < 1e-3 * a.up()) return false;
        final MnUserParameterState sa = a.userState();
        final MnUserParameterState sb = b.userState();
        for (int i = 0; i < sa.minuitParameters().size(); i++) {
            final double e = Math.max(Math.max(sa.error(i), sb.error(i)), 1e-12);
            if (Math.abs(sa.value(i) - sb.value(i)) > 3 * e) return true;
        }
        return false;
    }

    /** The radical inverse in a prime base: Halton's points, spread evenly whatever their number. */
    static double halton(int index, int base) {
        double f = 1;
        double r = 0;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    private static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79,
        83, 89, 97, 101, 103, 107, 109, 113};

    /**
     * Start number s of a spread: each variable parameter inside its limits
     * (both), or within its value +- 5 errors (one or no limit), at the
     * Halton point of index s.
     */
    static MnUserParameterState spread(MnUserParameterState start, int s) {
        final MnUserParameterState st = new MnUserParameterState(start);
        int d = 0;
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            final MinuitParameter p = st.parameter(i);
            if (p.isFixed() || p.isConst()) continue;
            final double u = halton(s, PRIMES[d++ % PRIMES.length]);
            double lo = p.value() - 5 * Math.max(p.error(), 1e-8);
            double hi = p.value() + 5 * Math.max(p.error(), 1e-8);
            if (p.hasLowerLimit()) lo = Math.max(lo, p.lowerLimit());
            if (p.hasUpperLimit()) hi = Math.min(hi, p.upperLimit());
            if (p.hasLowerLimit() && p.hasUpperLimit()) {
                lo = p.lowerLimit();
                hi = p.upperLimit();
            }
            final double margin = 1e-3 * (hi - lo);
            st.setValue(i, lo + margin + u * (hi - lo - 2 * margin));
        }
        return st;
    }

    /* ---- diagnosis ---------------------------------------------------------------- */

    /** The diagnosis of a minimum, in sentences. */
    public static List<String> diagnose(FunctionMinimum min, List<MinosError> minos, List<FunctionMinimum> others) {
        final List<String> out = new ArrayList<>();
        final MnUserParameterState st = min.userState();
        if (min.isValid()) {
            out.add(String.format(Locale.ROOT, "The minimum is valid: FCN = %.10g, EDM = %.3g (below the tolerance), %d calls.",
                min.fval(), min.edm(), min.nfcn()));
        } else {
            out.add("The minimum is NOT valid: " + outcome(min) + ". The values are the best point reached; their errors "
                + "are not to be trusted.");
        }
        out.add(switch (st.covarianceStatus()) {
            case 3 -> "The covariance is accurate (full Hesse, positive-definite).";
            case 2 -> "The covariance was forced positive-definite: the function is not a parabola near the minimum, "
                + "or two parameters are degenerate; the errors are approximate.";
            case 1 -> "The covariance is approximate (from Migrad's updates, not from Hesse).";
            case 0 -> "The covariance is not positive-definite: the point may be a saddle, not a minimum.";
            default -> "There is no covariance.";
        });
        // limits
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            final MinuitParameter p = st.parameter(i);
            if (p.isFixed() || p.isConst() || !p.hasLimits()) continue;
            final double e = Math.max(p.error(), 1e-300);
            if (p.hasLowerLimit() && Math.abs(p.value() - p.lowerLimit()) < e) {
                out.add(String.format(Locale.ROOT, "%s = %.6g is within one error of its lower limit %.6g: its error is not "
                    + "Gaussian; prefer Minos, or move the limit if it is not physical.", p.name(), p.value(), p.lowerLimit()));
            }
            if (p.hasUpperLimit() && Math.abs(p.upperLimit() - p.value()) < e) {
                out.add(String.format(Locale.ROOT, "%s = %.6g is within one error of its upper limit %.6g: its error is not "
                    + "Gaussian; prefer Minos, or move the limit if it is not physical.", p.name(), p.value(), p.upperLimit()));
            }
        }
        if (st.hasCovariance()) {
            final int n = st.variableParameters();
            final MnUserCovariance c = st.intCovariance();
            final double[][] corr = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    final double d = Math.sqrt(Math.abs(c.get(i, i) * c.get(j, j)));
                    corr[i][j] = d > 0 ? c.get(i, j) / d : 0;
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (Math.abs(corr[i][j]) > 0.95) {
                        out.add(String.format(Locale.ROOT, "%s and %s are %.1f%% correlated: the data fix mostly a combination "
                            + "of them; a reparametrization (or fixing one) makes the fit more robust.",
                            st.name(st.extOfInt(i)), st.name(st.extOfInt(j)), 100 * corr[i][j]));
                    }
                }
            }
            final MnGlobalCorrelationCoeff g = st.globalCC();
            if (g.isValid()) {
                for (int i = 0; i < n; i++) {
                    if (g.globalCC().get(i) > 0.99) {
                        out.add(String.format(Locale.ROOT, "%s has a global correlation of %.4f: it is almost determined by the "
                            + "others.", st.name(st.extOfInt(i)), g.globalCC().get(i)));
                    }
                }
            }
            // flat directions: the small eigenvalues of the correlation matrix, and the parameters they mix
            final Jacobi eig = Jacobi.of(corr);
            for (int k = 0; k < n; k++) {
                if (eig.values[k] < 1e-4 * n) {
                    final StringBuilder combo = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        final double w = eig.vectors[i][k];
                        if (Math.abs(w) < 0.2) continue;
                        if (combo.length() > 0) combo.append(w > 0 ? " + " : " - ");
                        else if (w < 0) combo.append("-");
                        combo.append(String.format(Locale.ROOT, "%.2f %s", Math.abs(w), st.name(st.extOfInt(i))));
                    }
                    out.add(String.format(Locale.ROOT, "A flat direction (eigenvalue %.2g of the correlation matrix): %s is "
                        + "not constrained by the data.", eig.values[k], combo));
                }
            }
        }
        for (MinosError me : minos) {
            if (!me.isValid()) {
                out.add("Minos could not find both errors of " + st.name(me.parameter())
                    + (me.atLowerLimit() || me.atUpperLimit() ? " (a limit is reached)" : "")
                    + (me.lowerNewMin() || me.upperNewMin() ? " (a lower minimum was found on the way: the fit is not at the "
                        + "global minimum)" : ""));
                continue;
            }
            final double lo = -me.lower();
            final double up = me.upper();
            if (lo > 0 && up > 0 && Math.abs(up - lo) / (up + lo) > 0.1) {
                out.add(String.format(Locale.ROOT, "%s: Minos errors -%.4g +%.4g differ by %.0f%%: the function is not "
                    + "parabolic there; quote the Minos errors.", st.name(me.parameter()), lo, up,
                    100 * Math.abs(up - lo) / (0.5 * (up + lo))));
            }
        }
        if (!others.isEmpty()) {
            out.add(others.size() + " other local minim" + (others.size() > 1 ? "a were" : "um was") + " found from other "
                + "starts: the function has several minima; the lowest is kept.");
        }
        return out;
    }

    /** Eigenvalues and eigenvectors of a small symmetric matrix (cyclic Jacobi), the values increasing. */
    static final class Jacobi {
        final double[] values;
        final double[][] vectors;

        private Jacobi(double[] v, double[][] w) {
            values = v;
            vectors = w;
        }

        static Jacobi of(double[][] a0) {
            final int n = a0.length;
            final double[][] a = new double[n][];
            for (int i = 0; i < n; i++) a[i] = a0[i].clone();
            final double[][] v = new double[n][n];
            for (int i = 0; i < n; i++) v[i][i] = 1;
            for (int sweep = 0; sweep < 100; sweep++) {
                double off = 0;
                for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) off += a[i][j] * a[i][j];
                if (off < 1e-30) break;
                for (int p = 0; p < n; p++) {
                    for (int q = p + 1; q < n; q++) {
                        if (Math.abs(a[p][q]) < 1e-300) continue;
                        final double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                        final double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                        final double c = 1 / Math.sqrt(t * t + 1);
                        final double s = t * c;
                        for (int k = 0; k < n; k++) {
                            final double akp = a[k][p];
                            final double akq = a[k][q];
                            a[k][p] = c * akp - s * akq;
                            a[k][q] = s * akp + c * akq;
                        }
                        for (int k = 0; k < n; k++) {
                            final double apk = a[p][k];
                            final double aqk = a[q][k];
                            a[p][k] = c * apk - s * aqk;
                            a[q][k] = s * apk + c * aqk;
                        }
                        for (int k = 0; k < n; k++) {
                            final double vkp = v[k][p];
                            final double vkq = v[k][q];
                            v[k][p] = c * vkp - s * vkq;
                            v[k][q] = s * vkp + c * vkq;
                        }
                    }
                }
            }
            final Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (x, y) -> Double.compare(a[x][x], a[y][y]));
            final double[] values = new double[n];
            final double[][] vectors = new double[n][n];
            for (int k = 0; k < n; k++) {
                values[k] = a[order[k]][order[k]];
                for (int i = 0; i < n; i++) vectors[i][k] = v[i][order[k]];
            }
            return new Jacobi(values, vectors);
        }
    }

    /* ---- profiles ------------------------------------------------------------------- */

    /** The profile of one parameter: at each value, the function minimized over the others, less the minimum. */
    public record Profile(String name, double[] x, double[] delta, double lowCross, double highCross) {
        /** The text: one line per point. */
        public String text(double up) {
            final StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "Profile of %s (FCN - FCNmin; the error "
                + "interval is where it is below %.3g)%n", name, up));
            for (int i = 0; i < x.length; i++) s.append(String.format(Locale.ROOT, "  %14.7g  %12.6g%n", x[i], delta[i]));
            s.append(String.format(Locale.ROOT, "  crossings: %.7g and %.7g%n", lowCross, highCross));
            return s.toString();
        }
    }

    /** Profiles parameter par over +- nsigma errors in npoints points (Migrad at each, the others free). */
    public static Profile profile(FCNBase fcn, FunctionMinimum min, int par, int npoints, double nsigma) {
        final MnUserParameterState best = min.userState();
        final MinuitParameter p = best.parameter(par);
        double lo = p.value() - nsigma * p.error();
        double hi = p.value() + nsigma * p.error();
        if (p.hasLowerLimit()) lo = Math.max(lo, p.lowerLimit());
        if (p.hasUpperLimit()) hi = Math.min(hi, p.upperLimit());
        final double[] xs = new double[npoints];
        final double[] ds = new double[npoints];
        for (int k = 0; k < npoints; k++) {
            final double x = npoints == 1 ? p.value() : lo + (hi - lo) * k / (npoints - 1);
            final MnUserParameterState st = new MnUserParameterState(best);
            st.setValue(par, x);
            st.fix(par);
            final double f;
            if (st.variableParameters() == 0) {
                f = fcn.value(st.params());
            } else {
                f = new MnMigrad(fcn, st, new MnStrategy(1)).minimize().fval();
            }
            xs[k] = x;
            ds[k] = f - min.fval();
        }
        final double up = fcn.up();
        double lowCross = Double.NaN;
        double highCross = Double.NaN;
        for (int k = 0; k + 1 < npoints; k++) {
            if (xs[k + 1] <= p.value() && ds[k] >= up && ds[k + 1] < up) {
                lowCross = xs[k] + (up - ds[k]) * (xs[k + 1] - xs[k]) / (ds[k + 1] - ds[k]);
            }
            if (xs[k] >= p.value() && ds[k] < up && ds[k + 1] >= up) {
                highCross = xs[k] + (up - ds[k]) * (xs[k + 1] - xs[k]) / (ds[k + 1] - ds[k]);
            }
        }
        return new Profile(p.name(), xs, ds, lowCross, highCross);
    }
}
