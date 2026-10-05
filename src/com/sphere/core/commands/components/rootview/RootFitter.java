package com.sphere.components.rootview;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.Minuit2Minimizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The fit behind TH1::Fit, TGraph::Fit and TGraph2D::Fit, as ROOT does it:
 * the chi-square of a TFormula to the points (sum of ((y - f) / ey)^2, "W"
 * ignoring the errors) or the Poisson likelihood of the bins ("L"),
 * minimized by Minuit2's Migrad (Sphere's bit-exact port,
 * com.sphere.core.minuit2) with ROOT's defaults, strategy 1 and tolerance
 * 0.01; Hesse for the errors when Migrad has not checked them, Minos with
 * "E", the analytic gradient of the formula (automatic differentiation)
 * with "G"; and ROOT's first guesses for its predefined shapes (gaus from
 * the peak, expo from the logarithm, a polynomial by linear least squares).
 */
public final class RootFitter {

    private RootFitter() {
    }

    /**
     * How to fit: the algorithm of Minuit2 ("Migrad", "Simplex", "Minimize",
     * "Scan", "BFGS", "Fumili"), its strategy (0 to 3) and tolerance, and
     * ROOT's options: E (Minos errors), G (the formula's gradient by automatic
     * differentiation), M (once more at strategy 2 when Migrad fails).
     */
    public record Options(boolean minos, boolean gradient, int strategy, double tolerance, boolean improve, String algorithm) {
        public static final Options DEFAULT = new Options(false, false, 1, 0.01, false, "Migrad");

        private static volatile String defaultAlgorithm = "Migrad";
        private static volatile int defaultStrategy = 1;
        private static volatile double defaultTolerance = 0.01;

        /** ROOT::Math::MinimizerOptions::SetDefault...: what the fits use when they are not told. */
        public static void setDefaults(String algorithm, int strategy, double tolerance) {
            if (algorithm != null) defaultAlgorithm = algorithm;
            if (strategy >= 0) defaultStrategy = strategy;
            if (tolerance > 0) defaultTolerance = tolerance;
        }

        public static String defaultAlgorithm() {
            return defaultAlgorithm;
        }

        public static int defaultStrategy() {
            return defaultStrategy;
        }

        public static double defaultTolerance() {
            return defaultTolerance;
        }

        /** From a ROOT fit option string, with the default algorithm, strategy and tolerance. */
        public static Options of(String option) {
            final String o = (option == null ? "" : option.toUpperCase(Locale.ROOT)).replace("EX0", "");
            return new Options(o.contains("E"), o.contains("G"), defaultStrategy, defaultTolerance, o.contains("M"),
                defaultAlgorithm);
        }

        public Options with(String algo, int stra) {
            return new Options(minos, gradient, stra, tolerance, improve, algo);
        }
    }

    /**
     * What a fit gives: the parameters, their errors, chi2 (2 NLL for a
     * likelihood fit), ndf, the probability, how it went; the minimum of the
     * FCN, EDM, calls, Minuit2's status, the covariance (n x n) and the Minos
     * errors when asked.
     */
    public record Result(double[] p, double[] err, double chi2, int ndf, double prob, boolean converged, int iterations,
                         List<String> names, double nll, double fcn, double edm, int status, double[] cov,
                         double[] minosLow, double[] minosUp, boolean likelihood, String minimizer,
                         List<String> diagnosis) {

        /** As ROOT prints a fit (TFitResult::Print). */
        public String report(String what, String formula) {
            final COStream os = new COStream();
            os.put("****************************************\n");
            if (!converged) {
                os.put("         Invalid FitResult").put("  (status = ").put(status).put(" )");
                os.put("\n****************************************\n");
            }
            os.put("Minimizer is ").put(minimizer).endl();
            final int nw = 25;
            final int nn = 12;
            if (fcn != chi2 || chi2 < 0) os.left().setw(nw).put("MinFCN").put(" = ").right().setw(nn).put(fcn).endl();
            if (chi2 >= 0) os.left().setw(nw).put("Chi2").put(" = ").right().setw(nn).put(chi2).endl();
            os.left().setw(nw).put("NDf").put(" = ").right().setw(nn).put(ndf).endl();
            if (edm >= 0) os.left().setw(nw).put("Edm").put(" = ").right().setw(nn).put(edm).endl();
            os.left().setw(nw).put("NCalls").put(" = ").right().setw(nn).put(iterations).endl();
            for (int i = 0; i < p.length; i++) {
                os.left().setw(nw).put(names.get(i));
                os.put(" = ").right().setw(nn).put(p[i]);
                os.put("   +/-   ").left().setw(nn).put(err[i]).right();
                if (minosLow != null) {
                    os.put("  ").left().setw(nn).put(minosLow[i]).put(" +").setw(nn).put(minosUp[i]).put(" (Minos) ");
                    os.right();
                }
                os.endl();
            }
            os.right();
            final StringBuilder d = new StringBuilder();
            if (diagnosis != null && !diagnosis.isEmpty()) {
                d.append("\n Minuit2's diagnosis:\n");
                for (String line : diagnosis) d.append("  - ").append(line).append('\n');
            }
            return " " + what + "  " + formula + "\n" + os.str()
                + String.format(Locale.ROOT, "%n Chi2/ndf = %.5g / %d   Prob = %.5g%n", chi2, ndf, prob) + d;
        }
    }

    /**
     * Fits points: coordinates xs[point][dimension], values y, errors ey (null
     * for none). The parameters start from p0, or from guesses when p0 is null.
     */
    public static Result fit(RootFormula f, double[][] xs, double[] y, double[] ey, double[] p0, boolean likelihood,
                             boolean ignoreErrors) {
        return fit(f, xs, y, ey, p0, likelihood, ignoreErrors, Options.DEFAULT);
    }

    public static Result fit(RootFormula f, double[][] xs, double[] y, double[] ey, double[] p0, boolean likelihood,
                             boolean ignoreErrors, Options opt) {
        final int m = Math.max(1, f.parameters());
        final List<Integer> use = new ArrayList<>();
        for (int i = 0; i < y.length; i++) {
            if (!Double.isFinite(y[i])) continue;
            if (!likelihood && !ignoreErrors && ey != null && ey[i] <= 0 && y[i] == 0) continue;
            use.add(i);
        }
        final int n = use.size();
        final double[][] x = new double[n][];
        final double[] v = new double[n];
        final double[] invErr = new double[n];
        for (int k = 0; k < n; k++) {
            final int i = use.get(k);
            x[k] = pad4(xs[i]);
            v[k] = y[i];
            invErr[k] = ignoreErrors || ey == null || ey[i] <= 0 ? 1 : 1. / ey[i];
        }
        double[] p = p0 != null && p0.length >= m ? p0.clone() : guess(f, xs, y);
        if (p.length < m) p = Arrays.copyOf(p, m);
        final List<String> names = f.parameterNames().size() >= m ? f.parameterNames() : padNames(f, m);
        final String algo = opt.algorithm() == null ? "Migrad" : opt.algorithm();
        final boolean fumili = algo.equalsIgnoreCase("fumili");
        final String minimizerName = "Minuit2 / " + (fumili ? "Fumili" : algo);
        if (n <= m) {
            return new Result(p, new double[m], Double.NaN, n - m, Double.NaN, false, 0, names, Double.NaN, Double.NaN, -1,
                -1, null, null, null, likelihood, minimizerName, List.of(n + " points for " + m + " parameters: nothing to fit"));
        }
        final FCNBase fcn = fumili ? new FumiliFit(f, x, v, invErr, likelihood, m)
            : likelihood ? poisson(f, x, v, opt.gradient()) : chi2(f, x, v, invErr, opt.gradient());
        final Minuit2Minimizer min = new Minuit2Minimizer(fumili ? "fumili" : algo.equalsIgnoreCase("migrad") ? "migrad" : algo);
        min.setFCN(m, fcn);
        min.setErrorDef(likelihood ? 0.5 : 1.0);
        min.setStrategy(opt.strategy());
        min.setTolerance(opt.tolerance());
        for (int i = 0; i < m; i++) {
            final double step = p[i] != 0 ? 0.1 * Math.abs(p[i]) : 0.1;
            min.setVariable(i, names.get(i), p[i], step);
        }
        min.setPrintLevel(0);
        boolean ok = min.minimize();
        if (opt.improve() && !ok) {
            // "M": once more from where it stopped at strategy 2, then Migrad with Simplex to leave a bad region
            min.setStrategy(2);
            ok = min.minimize();
            if (!ok && !fumili) {
                min.setMinimizerType(com.sphere.core.minuit2.Minuit2Minimizer.EMinimizerType.kCombined);
                ok = min.minimize();
            }
        }
        final double[] best = min.x();
        final double[] errs = min.errors();
        double[] lo = null;
        double[] hi = null;
        if (opt.minos() && ok) {
            lo = new double[m];
            hi = new double[m];
            final double[] e = new double[2];
            for (int i = 0; i < m; i++) {
                if (min.getMinosError(i, e)) {
                    lo[i] = e[0];
                    hi[i] = e[1];
                }
            }
        }
        final double[] cov = new double[m * m];
        if (!min.getCovMatrix(cov)) Arrays.fill(cov, Double.NaN);
        double chi2 = 0;
        double nll = 0;
        for (int k = 0; k < n; k++) {
            final double mu = f.eval(x[k], best);
            final double r = (v[k] - mu) * invErr[k];
            chi2 += r * r;
            final double muc = Math.max(mu, 1e-300);
            nll += muc - v[k] + (v[k] > 0 ? v[k] * Math.log(v[k] / muc) : 0);
        }
        final double stat = likelihood ? 2 * nll : chi2;
        final int ndf = n - m;
        final List<String> diagnosis = new ArrayList<>();
        if (min.functionMinimum() != null) {
            diagnosis.addAll(com.sphere.core.minuit2.Autopilot.diagnose(min.functionMinimum(), List.of(), List.of()));
        }
        if (lo != null) {
            for (int i = 0; i < m; i++) {
                final double a = -lo[i];
                final double b = hi[i];
                if (a > 0 && b > 0 && Math.abs(b - a) / (a + b) > 0.1) {
                    diagnosis.add(String.format(Locale.ROOT, "%s: Minos errors -%.4g +%.4g are not symmetric: quote them, "
                        + "not the parabolic error.", names.get(i), a, b));
                }
            }
        }
        if (ndf > 0 && stat / ndf > 3) {
            diagnosis.add(String.format(Locale.ROOT, "chi2/ndf = %.3g: the model does not describe the data; the errors "
                + "would be about %.2g times larger if the misfit were statistical.", stat / ndf, Math.sqrt(stat / ndf)));
        }
        if (!ok && !opt.improve()) diagnosis.add("The fit did not converge: option M tries strategy 2, then Simplex.");
        return new Result(best, errs == null ? new double[m] : errs, stat, ndf, gammaQ(ndf / 2.0, stat / 2), ok, min.nCalls(),
            names, nll, min.minValue(), min.edm(), min.status(), cov, lo, hi, likelihood, minimizerName, diagnosis);
    }

    private static List<String> padNames(RootFormula f, int m) {
        final List<String> l = new ArrayList<>();
        for (int i = 0; i < m; i++) l.add(f.parameterName(i));
        return l;
    }

    /** sum ((y - f) / ey)^2, written as ROOT's FitUtil (the residual times the inverse error), with its gradient. */
    static FCNBase chi2(RootFormula f, double[][] x, double[] v, double[] invErr, boolean gradient) {
        final int np = Math.max(1, f.parameters());
        if (!gradient) {
            return new FCNBase() {
                @Override
                public double value(double[] p) {
                    double c = 0;
                    for (int k = 0; k < x.length; k++) {
                        final double t = (v[k] - f.eval(x[k], p)) * invErr[k];
                        c += t * t;
                    }
                    return c;
                }

                @Override
                public double up() {
                    return 1;
                }
            };
        }
        return new com.sphere.core.minuit2.FCNGradientBase() {
            @Override
            public double value(double[] p) {
                double c = 0;
                for (int k = 0; k < x.length; k++) {
                    final double t = (v[k] - f.eval(x[k], p)) * invErr[k];
                    c += t * t;
                }
                return c;
            }

            @Override
            public double[] gradient(double[] p) {
                final double[] g = new double[p.length];
                final double[] d = new double[np];
                for (int k = 0; k < x.length; k++) {
                    final double fv = f.evalGradient(x[k], p, d);
                    final double t = (v[k] - fv) * invErr[k];
                    for (int i = 0; i < Math.min(np, g.length); i++) g[i] += -2 * t * invErr[k] * d[i];
                }
                return g;
            }

            @Override
            public double up() {
                return 1;
            }
        };
    }

    /** The Poisson negative log-likelihood of the bins, less that of the saturated model (Baker and Cousins). */
    static FCNBase poisson(RootFormula f, double[][] x, double[] v, boolean gradient) {
        final int np = Math.max(1, f.parameters());
        final FCNBase plain = new FCNBase() {
            @Override
            public double value(double[] p) {
                double s = 0;
                for (int k = 0; k < x.length; k++) {
                    final double mu = Math.max(f.eval(x[k], p), 1e-300);
                    s += mu - v[k] + (v[k] > 0 ? v[k] * Math.log(v[k] / mu) : 0);
                }
                return s;
            }

            @Override
            public double up() {
                return 0.5;
            }
        };
        if (!gradient) return plain;
        return new com.sphere.core.minuit2.FCNGradientBase() {
            @Override
            public double value(double[] p) {
                return plain.value(p);
            }

            @Override
            public double[] gradient(double[] p) {
                final double[] g = new double[p.length];
                final double[] d = new double[np];
                for (int k = 0; k < x.length; k++) {
                    final double fv = f.evalGradient(x[k], p, d);
                    if (fv <= 1e-300) continue;
                    final double w = 1 - v[k] / fv;
                    for (int i = 0; i < Math.min(np, g.length); i++) g[i] += w * d[i];
                }
                return g;
            }

            @Override
            public double up() {
                return 0.5;
            }
        };
    }

    private static double[] pad4(double[] x) {
        return x.length >= 4 ? x : Arrays.copyOf(x, 4);
    }

    static double[] solve(double[][] a, double[] b) {
        final int n = b.length;
        final double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
            if (Math.abs(m[piv][c]) < 1e-300) return null;
            final double[] t = m[c];
            m[c] = m[piv];
            m[piv] = t;
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                final double q = m[r][c] / m[c][c];
                if (q == 0) continue;
                for (int k = c; k <= n; k++) m[r][k] -= q * m[c][k];
            }
        }
        final double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    static double[][] invert(double[][] a) {
        final int n = a.length;
        final double[][] inv = new double[n][n];
        for (int c = 0; c < n; c++) {
            final double[] e = new double[n];
            e[c] = 1;
            final double[] col = solve(a, e);
            if (col == null) return null;
            for (int r = 0; r < n; r++) inv[r][c] = col[r];
        }
        return inv;
    }

    /** The upper regularised incomplete gamma Q(a, x): TMath::Prob(2x, 2a). */
    public static double gammaQ(double a, double x) {
        if (!(a > 0) || !(x >= 0)) return Double.NaN;
        if (x == 0) return 1;
        if (x < a + 1) {
            double sum = 1 / a;
            double del = sum;
            double ap = a;
            for (int n = 0; n < 1000; n++) {
                ap++;
                del *= x / ap;
                sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-15) break;
            }
            return Math.max(0, 1 - sum * Math.exp(-x + a * Math.log(x) - lnGamma(a)));
        }
        double b = x + 1 - a;
        double c = 1 / 1e-300;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < 1000; i++) {
            final double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < 1e-300) d = 1e-300;
            c = b + an / c;
            if (Math.abs(c) < 1e-300) c = 1e-300;
            d = 1 / d;
            final double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-15) break;
        }
        return Math.exp(-x + a * Math.log(x) - lnGamma(a)) * h;
    }

    static double lnGamma(double x) {
        final double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155,
            0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        final double tmp = x + 5.5 - (x + 0.5) * Math.log(x + 5.5);
        double ser = 1.000000000190015;
        for (double v : c) ser += v / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /* ------------------------------------------------------------------ */
    /* First guesses                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * ROOT's first guesses for each predefined piece of the formula: a
     * Gaussian from the highest point, its position and the spread, an
     * exponential by a straight line through the logarithms, a polynomial
     * by linear least squares; any other parameter starts at 1.
     */
    static double[] guess(RootFormula f, double[][] xs, double[] y) {
        final double[] p = new double[Math.max(1, f.parameters())];
        Arrays.fill(p, 1);
        final int n = y.length;
        if (n == 0) return p;
        int top = 0;
        double sw = 0;
        double sx = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            if (y[i] > y[top]) top = i;
            final double w = Math.max(0, y[i]);
            sw += w;
            sx += w * xs[i][0];
            sxx += w * xs[i][0] * xs[i][0];
        }
        final double mean = sw > 0 ? sx / sw : xs[top][0];
        final double rms = sw > 0 ? Math.sqrt(Math.max(1e-30, sxx / sw - mean * mean)) : 1;
        for (RootFormula.Piece piece : f.pieces) {
            final int o = piece.first();
            switch (piece.name()) {
                case "gaus", "xgaus", "landau", "breitwigner", "crystalball" -> {
                    set(p, o, y[top]);
                    set(p, o + 1, xs[top][0]);
                    set(p, o + 2, rms * (piece.name().equals("breitwigner") ? 2.35 : 1));
                    if (piece.name().equals("crystalball")) {
                        set(p, o + 3, 1.5);
                        set(p, o + 4, 3);
                    }
                }
                case "gausn", "landaun" -> {
                    double area = 0;
                    for (int i = 0; i + 1 < n; i++) area += 0.5 * (y[i] + y[i + 1]) * Math.abs(xs[i + 1][0] - xs[i][0]);
                    set(p, o, area);
                    set(p, o + 1, xs[top][0]);
                    set(p, o + 2, rms);
                }
                case "expo" -> {
                    double a = 0;
                    double b = 0;
                    double c = 0;
                    double d = 0;
                    int k = 0;
                    for (int i = 0; i < n; i++) {
                        if (y[i] <= 0) continue;
                        final double lx = xs[i][0];
                        final double ly = Math.log(y[i]);
                        a += lx;
                        b += ly;
                        c += lx * lx;
                        d += lx * ly;
                        k++;
                    }
                    if (k >= 2) {
                        final double slope = (k * d - a * b) / Math.max(1e-300, k * c - a * a);
                        set(p, o, (b - slope * a) / k);
                        set(p, o + 1, slope);
                    }
                }
                case "xygaus", "bigaus" -> {
                    set(p, o, y[top]);
                    set(p, o + 1, xs[top][0]);
                    set(p, o + 2, rms);
                    set(p, o + 3, xs[top].length > 1 ? xs[top][1] : 0);
                    set(p, o + 4, rms);
                }
                default -> {
                    if (piece.name().startsWith("pol")) {
                        final int deg = piece.count() - 1;
                        final double[] c = polynomial(xs, y, deg);
                        for (int k = 0; k <= deg; k++) set(p, o + k, c[k]);
                    }
                }
            }
        }
        return p;
    }

    private static void set(double[] p, int i, double v) {
        if (i < p.length && Double.isFinite(v)) p[i] = v;
    }

    /** Least-squares polynomial coefficients. */
    static double[] polynomial(double[][] xs, double[] y, int deg) {
        final int m = deg + 1;
        final double[][] a = new double[m][m];
        final double[] b = new double[m];
        for (int i = 0; i < y.length; i++) {
            final double x = xs[i][0];
            for (int r = 0; r < m; r++) {
                b[r] += y[i] * Math.pow(x, r);
                for (int c = 0; c < m; c++) a[r][c] += Math.pow(x, r + c);
            }
        }
        final double[] s = solve(a, b);
        return s == null ? new double[m] : s;
    }

    /**
     * The fit for Fumili: chi-square or Poisson likelihood with, at each
     * point, the formula's gradient by automatic differentiation; the Hessian
     * is the sum of the outer products of those gradients (the model's
     * second derivatives neglected, which is Fumili's approximation).
     */
    static final class FumiliFit extends com.sphere.core.minuit2.FumiliFCNBase {
        private final RootFormula f;
        private final double[][] x;
        private final double[] v;
        private final double[] invErr;
        private final boolean likelihood;

        FumiliFit(RootFormula f, double[][] x, double[] v, double[] invErr, boolean likelihood, int npar) {
            super(npar);
            this.f = f;
            this.x = x;
            this.v = v;
            this.invErr = invErr;
            this.likelihood = likelihood;
        }

        @Override
        public double value(double[] p) {
            double s = 0;
            for (int k = 0; k < x.length; k++) {
                if (likelihood) {
                    final double mu = Math.max(f.eval(x[k], p), 1e-300);
                    s += mu - v[k] + (v[k] > 0 ? v[k] * Math.log(v[k] / mu) : 0);
                } else {
                    final double t = (f.eval(x[k], p) - v[k]) * invErr[k];
                    s += t * t;
                }
            }
            return s;
        }

        @Override
        public double up() {
            return likelihood ? 0.5 : 1.0;
        }

        @Override
        public void evaluateAll(double[] p) {
            final int npar = p.length;
            final double[] grad = resetGradient(npar);
            final double[] h = resetHessian(npar * (npar + 1) / 2);
            final double[] d = new double[npar];
            double s = 0;
            for (int k = 0; k < x.length; k++) {
                final double fv = f.evalGradient(x[k], p, d);
                if (likelihood) {
                    final double mu = Math.max(fv, 1e-300);
                    s += mu - v[k] + (v[k] > 0 ? v[k] * Math.log(v[k] / mu) : 0);
                    final double w = 1 - v[k] / mu;
                    final double c = v[k] > 0 ? v[k] / (mu * mu) : 1 / mu;
                    for (int j = 0; j < npar; j++) {
                        grad[j] += w * d[j];
                        for (int i = j; i < npar; i++) h[j + i * (i + 1) / 2] += c * d[j] * d[i];
                    }
                } else {
                    final double e = (fv - v[k]) * invErr[k];
                    s += e * e;
                    for (int j = 0; j < npar; j++) {
                        final double dfj = invErr[k] * d[j];
                        grad[j] += 2.0 * e * dfj;
                        for (int i = j; i < npar; i++) h[j + i * (i + 1) / 2] += 2.0 * dfj * invErr[k] * d[i];
                    }
                }
            }
            setFCNValue(s);
        }
    }
}
