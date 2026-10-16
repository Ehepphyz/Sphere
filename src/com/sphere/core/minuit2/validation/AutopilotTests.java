package com.sphere.core.minuit2.validation;

import com.sphere.core.minuit2.Autopilot;
import com.sphere.core.minuit2.ChiSquare;
import com.sphere.core.minuit2.DataFit;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.FunctionMinimum;
import com.sphere.core.minuit2.MinosError;
import com.sphere.core.minuit2.MnHesse;
import com.sphere.core.minuit2.MnMigrad;
import com.sphere.core.minuit2.MnStrategy;
import com.sphere.core.minuit2.MnUserParameterState;
import com.sphere.core.minuit2.MnUserParameters;
import com.sphere.core.minuit2.NoiseEstimator;
import com.sphere.core.minuit2.PdgFormat;
import com.sphere.core.minuit2.SmartFCN;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * The autopilot on functions that make Migrad fail, each for its own reason
 * (noise, values that are not finite, a saddle point, several minima, a
 * degeneracy), and its tools checked against known numbers: the chi-square
 * quantiles (as iminuit tabulates them), the PDG rounding (the examples of
 * the Review), the noise estimate (a noise of known size), Minos and the
 * profile at confidence levels (a parabola), and the cache (Minuit2's bits
 * unchanged through it). Minuit2 itself is checked by Minuit2Tests.
 */
public final class AutopilotTests {

    private AutopilotTests() {
    }

    /** One check: whether it passed and what it saw. */
    public record Outcome(String name, boolean passed, String detail) {
    }

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }

    /** Fails a check with what was seen. */
    private static final class Failed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failed(String s) {
            super(s);
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new Failed(what);
    }

    private static Outcome check(String name, Check c) {
        try {
            return new Outcome(name, true, c.run());
        } catch (Failed f) {
            return new Outcome(name, false, f.getMessage());
        } catch (Exception | AssertionError e) {
            return new Outcome(name, false, e.toString());
        }
    }

    /** A function of the parameters, with Up. */
    private static FCNBase fcn(ToDoubleFunction<double[]> f, double up) {
        return new FCNBase() {
            @Override
            public double value(double[] v) {
                return f.applyAsDouble(v);
            }

            @Override
            public double up() {
                return up;
            }
        };
    }

    private static MnUserParameterState state(String[] names, double[] values, double[] errors) {
        final MnUserParameters p = new MnUserParameters();
        for (int i = 0; i < names.length; i++) p.add(names[i], values[i], errors[i]);
        return new MnUserParameterState(p);
    }

    /** A noise in [-a, a] fixed by the bits of the point (a function, so that it is the same at the same point). */
    static double noise(double[] v, double a) {
        long h = 0x9E3779B97F4A7C15L;
        for (double x : v) {
            h ^= Double.doubleToRawLongBits(x);
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 31;
            h *= 0x94D049BB133111EBL;
            h ^= h >>> 29;
        }
        return a * (2 * ((h >>> 11) * 0x1.0p-53) - 1);
    }

    /** A Poisson count of mean mu (Knuth below 30, a rounded Gaussian above). */
    static int poisson(java.util.SplittableRandom r, double mu) {
        if (mu < 30) {
            final double limit = Math.exp(-mu);
            double p = 1;
            int k = 0;
            do {
                k++;
                p *= r.nextDouble();
            } while (p > limit);
            return k - 1;
        }
        final double g = Math.sqrt(-2 * Math.log(1 - r.nextDouble())) * Math.cos(2 * Math.PI * r.nextDouble());
        return (int) Math.max(0, Math.round(mu + Math.sqrt(mu) * g));
    }

    private static boolean close(double a, double b, double rel) {
        return Math.abs(a - b) <= rel * Math.max(Math.abs(a), Math.abs(b));
    }

    public static List<Outcome> runAll() {
        final List<Outcome> out = new ArrayList<>();

        out.add(check("chi-square quantiles", () -> {
            final double[][] table = { // cl, ndim, iminuit's value (scipy's chi2.ppf)
                {0.68, 1, 0.988946481478023}, {0.90, 1, 2.705543454095404}, {0.95, 1, 3.841458820694124},
                {0.99, 1, 6.634896601021215}, {0.68, 2, 2.27886856637673}, {0.90, 2, 4.605170185988092},
                {0.95, 2, 5.991464547107979}, {0.99, 2, 9.21034037197618}, {1, 2, 2.295748928898636},
                {2, 2, 6.180074306244168}, {3, 2, 11.829158081900795}, {5, 2, 28.743702426935496}, {2, 1, 4},
                {0.95, 3, 7.814727903251178}};
            double worst = 0;
            for (double[] t : table) {
                final double v = ChiSquare.upFactor(t[0], (int) t[1]);
                final double rel = Math.abs(v - t[2]) / t[2];
                worst = Math.max(worst, rel);
                require(rel < 1e-10, String.format(Locale.ROOT, "cl %s in %d dimensions: %.15g instead of %.15g", t[0], (int) t[1],
                    v, t[2]));
            }
            return String.format(Locale.ROOT, "%d levels, largest relative difference %.1e", table.length, worst);
        }));

        out.add(check("PDG rounding", () -> {
            final String[][] cases = {
                {PdgFormat.format(0.827, 0.119), "0.83 +- 0.12"},
                {PdgFormat.format(0.827, 0.367), "0.8 +- 0.4"},
                {PdgFormat.format(2.3456, 0.123, 0.0123), "2.346 +- 0.123 +- 0.012"},
                {PdgFormat.format(2.3e-9, 0.354e-9), "(2.30 +- 0.35)e-9"},
                {PdgFormat.format(1.0, 0.0996), "1.00 +- 0.10"},
                {PdgFormat.asymmetric(1.234, -0.11, 0.22), "1.23 -0.11 +0.22"},
                {PdgFormat.format(12345.6, 7), "12346 +- 7"},
                {PdgFormat.format(-0.0004, 0.12), "0.00 +- 0.12"}};
            for (String[] c : cases) require(c[0].equals(c[1]), "'" + c[0] + "' instead of '" + c[1] + "'");
            return cases.length + " cases, as the Review rounds them";
        }));

        out.add(check("noise estimate", () -> {
            final double a = 1e-6;
            final FCNBase f = fcn(v -> 1 + (v[0] - 1) * (v[0] - 1) + (v[1] + 2) * (v[1] + 2) + noise(v, a), 1);
            final NoiseEstimator.Estimate e = NoiseEstimator.estimate(f, state(new String[] {"x", "y"}, new double[] {1, -2},
                new double[] {0.1, 0.1}));
            final double sigma = a / Math.sqrt(3);
            require(e.found() && e.sigma() > 0.5 * sigma && e.sigma() < 2 * sigma, "sigma " + e.sigma() + " for " + sigma);
            final FCNBase smooth = fcn(v -> 1000 + (v[0] - 1) * (v[0] - 1) + (v[1] + 2) * (v[1] + 2), 1);
            final NoiseEstimator.Estimate s = NoiseEstimator.estimate(smooth, state(new String[] {"x", "y"},
                new double[] {1, -2}, new double[] {0.1, 0.1}));
            require(s.precisionForMinuit(1) < 1e-15, "a smooth function given the precision " + s.precisionForMinuit(1));
            return String.format(Locale.ROOT, "sigma %.3g for %.3g (order %d, %d calls); a smooth function: %s", e.sigma(), sigma,
                e.order(), e.calls(), s.found() ? String.format(Locale.ROOT, "relative %.1g, precision unchanged", s.relative())
                    : "below detection");
        }));

        out.add(check("cache keeps Minuit2's bits", () -> {
            final FCNBase f = fcn(v -> 100 * (v[1] - v[0] * v[0]) * (v[1] - v[0] * v[0]) + (1 - v[0]) * (1 - v[0]), 1);
            final MnUserParameterState st = state(new String[] {"x", "y"}, new double[] {-1.2, 1}, new double[] {0.1, 0.1});
            final FunctionMinimum plain = new MnMigrad(f, st, new MnStrategy(1)).minimize();
            new MnHesse(2).apply(f, plain);
            final SmartFCN s = new SmartFCN(f);
            final FunctionMinimum cached = new MnMigrad(s, st, new MnStrategy(1)).minimize();
            new MnHesse(2).apply(s, cached);
            require(plain.fval() == cached.fval() && plain.nfcn() == cached.nfcn()
                && Arrays.equals(plain.userState().params(), cached.userState().params())
                && Arrays.equals(plain.userState().errors(), cached.userState().errors()), "the results differ");
            require(s.stats().cached() > 0, "nothing came from the cache");
            return "same FCN, values, errors and calls (" + plain.nfcn() + "); " + s.stats().text();
        }));

        out.add(check("Rosenbrock", () -> {
            final FCNBase f = fcn(v -> 100 * (v[1] - v[0] * v[0]) * (v[1] - v[0] * v[0]) + (1 - v[0]) * (1 - v[0]), 1);
            final Autopilot.Report r = Autopilot.minimize(f, state(new String[] {"x", "y"}, new double[] {-1.2, 1},
                new double[] {0.1, 0.1}), Autopilot.Options.DEFAULT);
            require(r.best().isValid() && Math.abs(r.best().userState().value(0) - 1) < 1e-3
                && Math.abs(r.best().userState().value(1) - 1) < 2e-3, "not at (1, 1): " + r.best().userState().value(0));
            return String.format(Locale.ROOT, "FCN %.3g after %d calls; %s", r.best().fval(), r.totalCalls(),
                r.verdict().grade().words);
        }));

        out.add(check("noisy function", () -> {
            // a chi-square with a noise of 1e-3: above the EDM Migrad asks (2e-4), as a numerically integrated likelihood
            final FCNBase f = fcn(v -> (v[0] - 1) * (v[0] - 1) / 0.01 + (v[1] + 2) * (v[1] + 2) / 0.04
                + (v[2] - 0.5) * (v[2] - 0.5) + 0.5 * (v[0] - 1) * (v[2] - 0.5) / 0.1 + noise(v, 2e-3), 1);
            final MnUserParameterState st = state(new String[] {"a", "b", "c"}, new double[] {0, 0, 0},
                new double[] {0.1, 0.1, 0.1});
            final FunctionMinimum plain = new MnMigrad(f, st, new MnStrategy(1)).minimize();
            final Autopilot.Report r = Autopilot.minimize(f, st, Autopilot.Options.DEFAULT);
            final MnUserParameterState b = r.best().userState();
            require(!plain.isValid(), "plain Migrad succeeded: the case tests nothing");
            require(r.best().isValid(), "not valid: " + r.steps().get(r.steps().size() - 1).outcome());
            require(Math.abs(b.value(0) - 1) < 0.05 && Math.abs(b.value(1) + 2) < 0.1, "wrong place " + b.value(0) + ", "
                + b.value(1));
            return String.format(Locale.ROOT, "plain Migrad %s; autopilot valid in %d steps%s", plain.isValid() ? "valid"
                : "NOT valid", r.steps().size(), r.noise() != null ? ", " + r.noise().text() : "");
        }));

        out.add(check("function not defined everywhere", () -> {
            // log(a) is NaN for a < 0, and the start step of 1 from a = 0.05 reaches it: Migrad ends with an EDM NaN
            final FCNBase f = fcn(v -> (Math.log(v[0]) - Math.log(2)) * (Math.log(v[0]) - Math.log(2)) / 1e-4
                + (v[1] - 1) * (v[1] - 1), 1);
            final MnUserParameterState st = state(new String[] {"a", "b"}, new double[] {0.05, 0}, new double[] {1, 0.1});
            final FunctionMinimum plain = new MnMigrad(f, st, new MnStrategy(1)).minimize();
            final Autopilot.Report r = Autopilot.minimize(f, st, Autopilot.Options.DEFAULT);
            require(!plain.isValid(), "plain Migrad succeeded: the case tests nothing");
            require(r.best().isValid() && Math.abs(r.best().userState().value(0) - 2) < 1e-2,
                "not at a = 2: " + r.best().userState().value(0) + " " + Autopilot.summary(r.best(), List.of(), 0));
            require(r.best().userState().parameter(0).hasLowerLimit(), "no limit inferred");
            return String.format(Locale.ROOT, "plain Migrad NOT valid (EDM %s); autopilot inferred a >= %.3g and found a = %.6g "
                + "(%d values not finite on the way)", plain.edm(), r.best().userState().parameter(0).lowerLimit(),
                r.best().userState().value(0), r.stats().nonFinite());
        }));

        out.add(check("saddle point", () -> {
            // the curvature goes down along x - y only (each d2f/dx2, d2f/dy2 is positive, so Minuit's seed does not see it);
            // started on the ridge x = y, Migrad goes to the saddle (0, 0); the minima are at x - y = +-1/sqrt(2), FCN = -1/16
            final FCNBase f = fcn(v -> v[0] * v[0] + v[1] * v[1] + 3 * v[0] * v[1] + Math.pow(v[0] - v[1], 4) / 4, 1);
            final MnUserParameterState st = state(new String[] {"x", "y"}, new double[] {0.3, 0.3}, new double[] {0.1, 0.1});
            final FunctionMinimum plain = new MnMigrad(f, st, new MnStrategy(1)).minimize();
            final Autopilot.Report r = Autopilot.minimize(f, st, Autopilot.Options.DEFAULT);
            require(Math.abs(plain.fval()) < 1e-10, "plain Migrad did not stop on the saddle: the case tests nothing");
            require(Math.abs(r.best().fval() + 0.0625) < 1e-6 && r.best().isValid(), "still at FCN " + r.best().fval());
            return String.format(Locale.ROOT, "plain Migrad FCN %.2g at (%.2g, %.2g), %s; autopilot FCN %.6g at (%.4f, %.4f), %s",
                plain.fval(), plain.userState().value(0), plain.userState().value(1), plain.isValid() ? "said valid" : "not valid",
                r.best().fval(), r.best().userState().value(0), r.best().userState().value(1), r.verdict().grade().words);
        }));

        out.add(check("two minima (multistart)", () -> {
            final FCNBase f = fcn(v -> (v[0] * v[0] - 1) * (v[0] * v[0] - 1) + 0.3 * v[0] + v[1] * v[1], 1);
            final MnUserParameterState st = state(new String[] {"x", "y"}, new double[] {1.5, 0.3}, new double[] {0.5, 0.5});
            final FunctionMinimum plain = new MnMigrad(f, st, new MnStrategy(1)).minimize();
            final Autopilot.Report r = Autopilot.minimize(f, st, Autopilot.Options.DEFAULT.withMultistart(6));
            require(r.best().userState().value(0) < 0, "kept the local minimum at x = " + r.best().userState().value(0));
            require(r.basins().size() >= 2, "only " + r.basins().size() + " minimum found");
            return String.format(Locale.ROOT, "plain Migrad x = %.4g (FCN %.4g); autopilot x = %.4g (FCN %.4g), %d minima, the "
                + "lowest from %d starts", plain.userState().value(0), plain.fval(), r.best().userState().value(0), r.best().fval(),
                r.basins().size(), r.basins().get(0).hits());
        }));

        out.add(check("degenerate parameters", () -> {
            final FCNBase f = fcn(v -> (v[0] + v[1] - 3) * (v[0] + v[1] - 3) / 0.01 + 1e-9 * (v[0] - v[1]) * (v[0] - v[1]), 1);
            final Autopilot.Report r = Autopilot.minimize(f, state(new String[] {"a", "b"}, new double[] {1, 1},
                new double[] {0.1, 0.1}), Autopilot.Options.DEFAULT);
            final String all = String.join(" ", r.diagnosis());
            require(r.verdict().grade() != Autopilot.Grade.RELIABLE, "judged reliable");
            require(all.contains("flat direction") || all.contains("correlated"), "the degeneracy is not said");
            return r.verdict().text();
        }));

        out.add(check("Minos at confidence levels", () -> {
            final FCNBase f = fcn(v -> (v[0] - 1) * (v[0] - 1) / 0.25 + (v[1] - v[0]) * (v[1] - v[0]) / 0.01, 1);
            final MnUserParameterState st = state(new String[] {"x", "y"}, new double[] {0, 0}, new double[] {0.1, 0.1});
            final double[][] want = {{0, 0.5}, {2, 1.0}, {0.95, 0.5 * Math.sqrt(ChiSquare.quantile(0.95, 1))}};
            final StringBuilder s = new StringBuilder();
            for (double[] w : want) {
                final Autopilot.Report r = Autopilot.minimize(f, st, Autopilot.Options.DEFAULT.withMinos(true).withCl(w[0]));
                final MinosError me = r.minos().get(0);
                require(me.isValid() && close(me.upper(), w[1], 1e-3) && close(-me.lower(), w[1], 1e-3),
                    "cl " + w[0] + ": -" + -me.lower() + " +" + me.upper() + " instead of " + w[1]);
                s.append(String.format(Locale.ROOT, "%s: +-%.5g  ", ChiSquare.describe(w[0]), me.upper()));
            }
            return s.toString().trim();
        }));

        out.add(check("Minos on demand", () -> {
            // -2 ln L of a Poisson count n = 3 in mu (very asymmetric), and a Gaussian b (a parabola)
            final FCNBase f = fcn(v -> 2 * (v[0] - 3 * Math.log(v[0])) + (v[1] - 1) * (v[1] - 1) / 0.04, 1);
            final MnUserParameterState st = state(new String[] {"mu", "b"}, new double[] {2, 0}, new double[] {0.5, 0.1});
            final Autopilot.Report r = Autopilot.minimize(f, st, Autopilot.Options.DEFAULT);
            require(r.minos().size() == 1 && r.minos().get(0).parameter() == 0, "Minos ran for " + r.minos().size()
                + " parameters, not for mu alone");
            final MinosError me = r.minos().get(0);
            require(me.isValid() && me.upper() > -me.lower() * 1.2, "the interval of mu is not asymmetric: -" + -me.lower()
                + " +" + me.upper());
            final FCNBase g = fcn(v -> (v[0] - 1) * (v[0] - 1) / 0.25 + (v[1] - v[0]) * (v[1] - v[0]) / 0.01, 1);
            final Autopilot.Report q = Autopilot.minimize(g, state(new String[] {"x", "y"}, new double[] {0, 0},
                new double[] {0.1, 0.1}), Autopilot.Options.DEFAULT);
            require(q.minos().isEmpty(), "Minos ran on a parabola");
            return String.format(Locale.ROOT, "Poisson mu = 3 -%.4f +%.4f by Minos (only mu tested non-parabolic); "
                + "a parabola: no Minos", -me.lower(), me.upper());
        }));

        out.add(check("binned fit and its quality", () -> {
            // 60 bins of counts: a Gaussian peak on an exponential background, drawn once with a fixed seed
            final java.util.SplittableRandom rnd = new java.util.SplittableRandom(42);
            final int nb = 60;
            final double[] edges = new double[nb + 1];
            for (int k = 0; k <= nb; k++) edges[k] = k * 0.5;
            final double[] truth = {200, 0.15, 400, 15, 1.2};
            final DataFit.Model model = (x, p) -> p[0] * Math.exp(-p[1] * x) + p[2] * Math.exp(-0.5 * Math.pow((x - p[3]) / p[4], 2));
            final double[] counts = new double[nb];
            for (int k = 0; k < nb; k++) counts[k] = poisson(rnd, model.value(0.5 * (edges[k] + edges[k + 1]), truth));
            final MnUserParameterState st = state(new String[] {"b", "slope", "a", "mean", "sigma"},
                new double[] {150, 0.1, 300, 14, 1}, new double[] {15, 0.01, 30, 1, 0.1});
            final DataFit.Result good = DataFit.fit(model, edges, counts, null, null, st, Autopilot.Options.DEFAULT, null);
            require(good.cost() == DataFit.Cost.POISSON, "counts not fitted by a Poisson likelihood");
            require(good.report().best().isValid(), "the fit is not valid");
            final MnUserParameterState b = good.report().best().userState();
            for (int i = 0; i < truth.length; i++) {
                require(Math.abs(b.value(i) - truth[i]) < 4 * b.error(i), b.name(i) + " = " + b.value(i) + " +- " + b.error(i)
                    + " for " + truth[i]);
            }
            require(good.pValue() > 1e-3 && !(good.runsP() < 1e-3), "a good model judged bad: p " + good.pValue() + ", runs "
                + good.runsP());
            // the background alone: the fit must be judged bad
            final DataFit.Model bkg = (x, p) -> p[0] * Math.exp(-p[1] * x);
            final DataFit.Result bad = DataFit.fit(bkg, edges, counts, null, null, state(new String[] {"b", "slope"},
                new double[] {150, 0.1}, new double[] {15, 0.01}), Autopilot.Options.DEFAULT, null);
            require(bad.pValue() < 1e-3 || bad.runsP() < 1e-3, "a model without the peak judged good");
            return String.format(Locale.ROOT, "peak fit: chi2 %.1f / %d, p %.2f, runs p %.2f; without the peak: p %.1g, runs z %.1f",
                good.chi2(), good.ndf(), good.pValue(), good.runsP(), bad.pValue(), bad.runsZ());
        }));

        out.add(check("profile crossings", () -> {
            final FCNBase f = fcn(v -> (v[0] - 1) * (v[0] - 1) / 0.25 + (v[1] - v[0]) * (v[1] - v[0]) / 0.01, 1);
            final FunctionMinimum m = new MnMigrad(f, state(new String[] {"x", "y"}, new double[] {0, 0},
                new double[] {0.1, 0.1}), new MnStrategy(1)).minimize();
            final Autopilot.Profile p = Autopilot.profile(f, m, 0, 11, 3, 0);
            require(Math.abs(p.lowCross() - 0.5) < 1e-3 && Math.abs(p.highCross() - 1.5) < 1e-3,
                "crossings " + p.lowCross() + " and " + p.highCross());
            final Autopilot.Profile p2 = Autopilot.profile(f, m, 0, 11, 3, 2);
            require(Math.abs(p2.lowCross() - 0.0) < 1e-3 && Math.abs(p2.highCross() - 2.0) < 1e-3,
                "2 sigma crossings " + p2.lowCross() + " and " + p2.highCross());
            return String.format(Locale.ROOT, "1 sigma [%.6g, %.6g], 2 sigma [%.6g, %.6g], %d + %d calls", p.lowCross(),
                p.highCross(), p2.lowCross(), p2.highCross(), p.calls(), p2.calls());
        }));
        return out;
    }

    /** java ... AutopilotTests: runs the checks and prints them. */
    public static void main(String[] args) {
        int ok = 0;
        final List<Outcome> all = runAll();
        for (Outcome o : all) {
            if (o.passed()) ok++;
            System.out.printf(Locale.ROOT, "%s %-34s %s%n", o.passed() ? "PASS" : "FAIL", o.name(), o.detail());
        }
        System.out.println(ok + " of " + all.size() + " pass");
        if (ok != all.size()) System.exit(1);
    }
}
