package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A model fitted to binned data by the autopilot, and how well it fits.
 *
 * <p>The cost is the one the data call for: counts (non-negative whole numbers without errors
 * given) take the Poisson likelihood ratio of Baker and Cousins, 2 sum(f - n + n ln(n/f)), which
 * is a chi-square for the goodness of fit and stays right where bins are nearly empty; values
 * with errors take the chi-square. The verdict on the fit goes beyond its chi-square: the
 * p-value, the largest pull, and the runs test of Wald and Wolfowitz on the signs of the
 * residuals, which sees a model that misses a shape (long runs of one sign) even when the
 * chi-square is good.
 */
public final class DataFit {

    private DataFit() {
    }

    /** The model at x for the parameters p. */
    @FunctionalInterface
    public interface Model {
        double value(double x, double[] p);
    }

    public enum Cost { CHI2, POISSON }

    /** The fit: the autopilot's report, the cost, its value as a chi-square, ndf, p-value, the model in each bin, the pulls, the runs test. */
    public record Result(Autopilot.Report report, SmartFCN fcn, Cost cost, double chi2, int ndf, double pValue, double[] fitted, double[] pulls,
                         int runs, double runsZ, double runsP, List<String> quality) {

        public String text() {
            final StringBuilder s = new StringBuilder("Fit quality:\n");
            for (String q : quality) s.append("  - ").append(q).append('\n');
            return s.toString();
        }
    }

    /** Counts without errors: Poisson; anything else: chi-square. */
    public static Cost choose(double[] y, double[] err) {
        if (err != null) return Cost.CHI2;
        for (double v : y) if (!(v >= 0) || v != Math.rint(v)) return Cost.CHI2;
        return Cost.POISSON;
    }

    /** The cost function of the model on the points (x, y), err the errors (null: sqrt(y), at least 1, for a chi-square). */
    public static FCNBase cost(Model f, double[] x, double[] y, double[] err, Cost c) {
        final int n = x.length;
        return new FCNBase() {
            @Override
            public double value(double[] p) {
                double s = 0;
                for (int k = 0; k < n; k++) {
                    final double mu = f.value(x[k], p);
                    if (c == Cost.POISSON) {
                        if (y[k] > 0) {
                            if (!(mu > 0)) return Double.NaN;
                            s += mu - y[k] + y[k] * Math.log(y[k] / mu);
                        } else {
                            s += mu;
                        }
                    } else {
                        final double e = err != null ? err[k] : Math.sqrt(Math.max(Math.abs(y[k]), 1));
                        final double r = (y[k] - mu) / e;
                        s += r * r;
                    }
                }
                return c == Cost.POISSON ? 2 * s : s;
            }

            @Override
            public double up() {
                return 1;
            }
        };
    }

    /**
     * Fits the model to a histogram: edges (n + 1), values (n), the band (errPlus, errMinus, both
     * may be null; a band is symmetrized), from start, with the autopilot's options; c null
     * chooses the cost.
     */
    public static Result fit(Model f, double[] edges, double[] values, double[] errPlus, double[] errMinus,
                             MnUserParameterState start, Autopilot.Options opt, Cost c) {
        final int n = values.length;
        final double[] x = new double[n];
        for (int k = 0; k < n; k++) x[k] = 0.5 * (edges[k] + edges[k + 1]);
        double[] err = null;
        if (errPlus != null) {
            err = new double[n];
            for (int k = 0; k < n; k++) {
                final double lo = errMinus != null ? errMinus[k] : errPlus[k];
                err[k] = 0.5 * (Math.abs(errPlus[k]) + Math.abs(lo));
                if (!(err[k] > 0)) err[k] = 1;
            }
        }
        final Cost cost = c != null ? c : choose(values, err);
        final SmartFCN fcn = new SmartFCN(cost(f, x, values, err, cost));
        final Autopilot.Report r = Autopilot.minimize(fcn, start, opt);
        final double[] p = r.best().userState().params();
        final double[] fitted = new double[n];
        final double[] pulls = new double[n];
        for (int k = 0; k < n; k++) {
            fitted[k] = f.value(x[k], p);
            final double e = cost == Cost.POISSON ? Math.sqrt(Math.max(fitted[k], 1e-300))
                : err != null ? err[k] : Math.sqrt(Math.max(Math.abs(values[k]), 1));
            pulls[k] = (values[k] - fitted[k]) / e;
        }
        final int ndf = n - r.best().userState().variableParameters();
        final double chi2 = r.best().fval();
        final double pValue = ndf > 0 ? ChiSquare.gammaPQ(0.5 * ndf, 0.5 * chi2)[1] : Double.NaN;
        // runs of one sign among the residuals (bins where the model is not exactly the data)
        int plus = 0;
        int minus = 0;
        int runs = 0;
        int last = 0;
        for (double q : pulls) {
            final int s = q > 0 ? 1 : q < 0 ? -1 : 0;
            if (s == 0) continue;
            if (s > 0) plus++;
            else minus++;
            if (s != last) runs++;
            last = s;
        }
        final int total = plus + minus;
        double z = Double.NaN;
        double runsP = Double.NaN;
        if (plus > 0 && minus > 0 && total > 2) {
            final double mu = 2.0 * plus * minus / total + 1;
            final double var = (mu - 1) * (mu - 2) / (total - 1);
            if (var > 0) {
                z = (runs - mu) / Math.sqrt(var);
                // one-sided: too few runs is what a missed shape gives
                runsP = 0.5 * ChiSquare.gammaPQ(0.5, 0.5 * z * z)[1];
                if (z > 0) runsP = 1 - runsP;
            }
        }
        final List<String> quality = new ArrayList<>();
        quality.add(String.format(Locale.ROOT, "%s = %.4g for %d degrees of freedom (%s), p-value %.3g.",
            cost == Cost.POISSON ? "Likelihood-ratio chi2 (Baker-Cousins)" : "chi2", chi2, ndf,
            cost == Cost.POISSON ? "Poisson likelihood: the values are counts" : "chi-square", pValue));
        if (ndf > 0 && pValue < 1e-3) {
            quality.add(String.format(Locale.ROOT, "The model does not describe the data (chi2/ndf = %.3g): the errors of the "
                + "parameters are too small by about a factor %.2g if the misfit is to be taken as statistical.", chi2 / ndf,
                Math.sqrt(chi2 / ndf)));
        } else if (ndf > 5 && pValue > 0.999) {
            quality.add(String.format(Locale.ROOT, "chi2/ndf = %.3g is too good: the errors of the data are overestimated, or the "
                + "points are correlated.", chi2 / ndf));
        }
        int worst = 0;
        for (int k = 1; k < n; k++) if (Math.abs(pulls[k]) > Math.abs(pulls[worst])) worst = k;
        if (n > 0) {
            quality.add(String.format(Locale.ROOT, "Largest pull %.2f at x = %.5g (bin %d)%s.", pulls[worst], x[worst], worst + 1,
                Math.abs(pulls[worst]) > 4 ? ": an outlier, or a feature the model lacks" : ""));
        }
        if (!Double.isNaN(z)) {
            quality.add(String.format(Locale.ROOT, "Runs test: %d runs of one sign among %d residuals (%d above, %d below), "
                + "z = %.2f, p = %.3g%s.", runs, total, plus, minus, z, runsP, runsP < 1e-3
                ? ": the residuals come in long runs, the model misses a shape the data have (whatever chi2 says)"
                : runsP < 0.01 ? ": a hint of a shape the model misses; look at the pulls" : ""));
        }
        return new Result(r, fcn, cost, chi2, ndf, pValue, fitted, pulls, runs, z, runsP, quality);
    }
}
