package com.sphere.components.spherebrowser;

import com.sphere.components.rootview.RootScene;
import com.sphere.components.rootview.RootScene.Axis;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Hist;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The numbers behind a plot, worked as a physicist would: fits by
 * Levenberg-Marquardt to the shapes of the trade (a Gaussian on a polynomial
 * background, Breit-Wigner, Crystal Ball, Voigt, Landau, a Tsallis pT
 * spectrum, an erf trigger turn-on...) with least squares or Poisson
 * likelihood; the models ranked by AICc when one does not know which; peaks
 * found and fitted together; Bayesian Blocks for a binning the data choose;
 * two histograms compared (Kolmogorov-Smirnov, χ² homogeneity, ratio,
 * pulls); a TH2 projected and profiled. No library: the special functions are
 * here too.
 */
public final class HepAnalysis {

    private HepAnalysis() {
    }

    /* ================================================================== */
    /* Data                                                                */
    /* ================================================================== */

    /** The bins of a 1D histogram inside a range: centres, widths, contents, errors. */
    public static final class Data {
        public final double[] x;
        public final double[] w;
        public final double[] y;
        public final double[] e;
        public final double lo;
        public final double hi;

        Data(double[] x, double[] w, double[] y, double[] e, double lo, double hi) {
            this.x = x;
            this.w = w;
            this.y = y;
            this.e = e;
            this.lo = lo;
            this.hi = hi;
        }

        public int n() {
            return x.length;
        }

        public double sum() {
            double s = 0;
            for (double v : y) s += v;
            return s;
        }

        double binWidth() {
            double s = 0;
            for (double v : w) s += v;
            return x.length == 0 ? 1 : s / x.length;
        }
    }

    public static Data data(Hist h, double lo, double hi) {
        final List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < h.nx(); i++) {
            final double c = h.x.center(i);
            if (c < lo || c > hi) continue;
            rows.add(new double[]{c, h.x.edge(i + 1) - h.x.edge(i), h.at(i, 0), h.error(i)});
        }
        final int n = rows.size();
        final double[] x = new double[n];
        final double[] w = new double[n];
        final double[] y = new double[n];
        final double[] e = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = rows.get(i)[0];
            w[i] = rows.get(i)[1];
            y[i] = rows.get(i)[2];
            e[i] = rows.get(i)[3];
        }
        return new Data(x, w, y, e, lo, hi);
    }

    /* ================================================================== */
    /* Models                                                              */
    /* ================================================================== */

    @FunctionalInterface
    interface Shape {
        double at(double x, double[] p);
    }

    @FunctionalInterface
    interface Guess {
        double[] from(Data d);
    }

    /** A function to fit: its parameters, its formula and a first guess from the data. */
    public static final class Model {
        public final String name;
        public final String formula;
        public final String[] params;
        final Shape f;
        final Guess guess;
        /** The parameters that make the signal, for yields and significance; null when none. */
        final int[] signal;
        final Shape signalPart;
        final Shape backgroundPart;

        Model(String name, String formula, String[] params, Shape f, Guess guess, int[] signal, Shape signalPart,
              Shape backgroundPart) {
            this.name = name;
            this.formula = formula;
            this.params = params;
            this.f = f;
            this.guess = guess;
            this.signal = signal;
            this.signalPart = signalPart;
            this.backgroundPart = backgroundPart;
        }

        public double at(double x, double[] p) {
            return f.at(x, p);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static double gauss(double x, double a, double m, double s) {
        final double t = (x - m) / s;
        return a * Math.exp(-0.5 * t * t);
    }

    static double crystalBall(double x, double a, double m, double s, double alpha, double n) {
        final double t = (x - m) / Math.abs(s);
        final double al = Math.abs(alpha);
        if (t > -al) return a * Math.exp(-0.5 * t * t);
        final double nn = Math.max(1.0001, n);
        final double A = Math.pow(nn / al, nn) * Math.exp(-0.5 * al * al);
        final double B = nn / al - al;
        return a * A * Math.pow(B - t, -nn);
    }

    static double breitWigner(double x, double a, double m, double g) {
        final double h = g / 2;
        return a * h * h / ((x - m) * (x - m) + h * h);
    }

    /** Pseudo-Voigt (Thompson-Cox-Hastings): a Lorentzian and a Gaussian of one width, mixed by eta. */
    static double voigt(double x, double a, double m, double fwhm, double eta) {
        final double e = Math.max(0, Math.min(1, eta));
        final double s = Math.abs(fwhm) / 2.35482;
        final double t = (x - m) / Math.max(1e-300, s);
        final double g = Math.exp(-0.5 * t * t);
        final double h = fwhm / 2;
        final double l = h * h / ((x - m) * (x - m) + h * h);
        return a * (e * l + (1 - e) * g);
    }

    /** Moyal's approximation of the Landau: the energy loss in a thin layer. */
    static double landau(double x, double a, double mpv, double w) {
        final double l = (x - mpv) / Math.max(1e-300, Math.abs(w));
        return a * Math.exp(-0.5 * (l + Math.exp(-l))) / Math.exp(-0.5);
    }

    private static double max(double[] v) {
        double m = -Double.MAX_VALUE;
        for (double x : v) m = Math.max(m, x);
        return m == -Double.MAX_VALUE ? 1 : m;
    }

    private static int argmax(double[] v) {
        int k = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[k]) k = i;
        return k;
    }

    /** Mean and RMS of the contents over x. */
    private static double[] moments(Data d) {
        double s = 0;
        double sx = 0;
        double sxx = 0;
        for (int i = 0; i < d.n(); i++) {
            final double y = Math.max(0, d.y[i]);
            s += y;
            sx += y * d.x[i];
            sxx += y * d.x[i] * d.x[i];
        }
        if (s <= 0) return new double[]{(d.lo + d.hi) / 2, (d.hi - d.lo) / 6};
        final double m = sx / s;
        return new double[]{m, Math.sqrt(Math.max(1e-30, sxx / s - m * m))};
    }

    /**
     * The peak: height above its background, position, a width from the half
     * maximum, and the background under it. The most prominent peak, not the
     * highest bin: on a falling spectrum the highest bin is the edge of the
     * range, and a fit started there never finds the resonance.
     */
    private static double[] peak(Data d) {
        if (d.n() == 0) return new double[]{1, 0, 1, 0};
        final List<Peak> found = d.n() >= 5 ? findPeaks(d, 1.5, 0.02) : List.of();
        if (!found.isEmpty()) {
            final Peak p = found.get(0);
            final double[] line = edgeLine(d);
            final double base = Math.max(0, Math.min(p.height() - p.prominence() * 0.5, line[0] + line[1] * p.x()));
            return new double[]{Math.max(1e-12, p.height() - base), p.x(),
                Math.max(d.binWidth() * 0.5, p.fwhm() / 2.35482), base};
        }
        final int k = argmax(d.y);
        final int edge = Math.max(1, d.n() / 10);
        double base = 0;
        for (int i = 0; i < edge; i++) base += d.y[i] + d.y[d.n() - 1 - i];
        base /= 2 * edge;
        final double height = Math.max(1e-12, d.y[k] - base);
        int l = k;
        int r = k;
        while (l > 0 && d.y[l] - base > height / 2) l--;
        while (r < d.n() - 1 && d.y[r] - base > height / 2) r++;
        final double fwhm = Math.max(d.binWidth(), d.x[r] - d.x[l]);
        return new double[]{height, d.x[k], fwhm / 2.35482, base};
    }

    /** A straight line through the edges of the range, for a background's start. */
    private static double[] edgeLine(Data d) {
        final int n = d.n();
        if (n < 2) return new double[]{0, 0};
        final int k = Math.max(1, n / 8);
        double xa = 0;
        double ya = 0;
        double xb = 0;
        double yb = 0;
        for (int i = 0; i < k; i++) {
            xa += d.x[i];
            ya += d.y[i];
            xb += d.x[n - 1 - i];
            yb += d.y[n - 1 - i];
        }
        xa /= k;
        ya /= k;
        xb /= k;
        yb /= k;
        final double slope = xb == xa ? 0 : (yb - ya) / (xb - xa);
        return new double[]{ya - slope * xa, slope};
    }

    private static double[] logLine(Data d) {
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        int n = 0;
        for (int i = 0; i < d.n(); i++) {
            if (d.y[i] <= 0) continue;
            final double ly = Math.log(d.y[i]);
            sx += d.x[i];
            sy += ly;
            sxx += d.x[i] * d.x[i];
            sxy += d.x[i] * ly;
            n++;
        }
        if (n < 2) return new double[]{0, 0};
        final double b = (n * sxy - sx * sy) / Math.max(1e-300, n * sxx - sx * sx);
        return new double[]{(sy - b * sx) / n, b};
    }

    private static double[] concat(double[] a, double... b) {
        final double[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static final List<Model> MODELS = List.of(
        new Model("gaus", "A·exp(-½((x-μ)/σ)²)", new String[]{"A", "μ", "σ"},
            (x, p) -> gauss(x, p[0], p[1], p[2]),
            d -> {
                final double[] k = peak(d);
                return new double[]{k[0] + k[3], k[1], k[2]};
            }, new int[]{0, 1, 2}, (x, p) -> gauss(x, p[0], p[1], p[2]), (x, p) -> 0),
        new Model("gaus+pol1", "gaus + c₀ + c₁x", new String[]{"A", "μ", "σ", "c₀", "c₁"},
            (x, p) -> gauss(x, p[0], p[1], p[2]) + p[3] + p[4] * x,
            d -> {
                final double[] k = peak(d);
                return concat(new double[]{k[0], k[1], k[2]}, edgeLine(d));
            }, new int[]{0, 1, 2}, (x, p) -> gauss(x, p[0], p[1], p[2]), (x, p) -> p[3] + p[4] * x),
        new Model("gaus+pol2", "gaus + c₀ + c₁x + c₂x²", new String[]{"A", "μ", "σ", "c₀", "c₁", "c₂"},
            (x, p) -> gauss(x, p[0], p[1], p[2]) + p[3] + p[4] * x + p[5] * x * x,
            d -> {
                final double[] k = peak(d);
                return concat(new double[]{k[0], k[1], k[2]}, concat(edgeLine(d), 0));
            }, new int[]{0, 1, 2}, (x, p) -> gauss(x, p[0], p[1], p[2]), (x, p) -> p[3] + p[4] * x + p[5] * x * x),
        new Model("gaus+expo", "gaus + exp(c₀ + c₁x)", new String[]{"A", "μ", "σ", "c₀", "c₁"},
            (x, p) -> gauss(x, p[0], p[1], p[2]) + Math.exp(Math.min(700, p[3] + p[4] * x)),
            d -> {
                final double[] k = peak(d);
                return concat(new double[]{k[0], k[1], k[2]}, logLine(d));
            }, new int[]{0, 1, 2}, (x, p) -> gauss(x, p[0], p[1], p[2]), (x, p) -> Math.exp(Math.min(700, p[3] + p[4] * x))),
        new Model("2gaus", "core + tail, one mean", new String[]{"A₁", "μ", "σ₁", "A₂", "σ₂"},
            (x, p) -> gauss(x, p[0], p[1], p[2]) + gauss(x, p[3], p[1], p[4]),
            d -> {
                final double[] k = peak(d);
                return new double[]{0.8 * k[0], k[1], 0.8 * k[2], 0.2 * k[0], 2.5 * k[2]};
            }, new int[]{0, 1, 2}, (x, p) -> gauss(x, p[0], p[1], p[2]) + gauss(x, p[3], p[1], p[4]), (x, p) -> 0),
        new Model("breitwigner", "A·(Γ/2)²/((x-M)²+(Γ/2)²)", new String[]{"A", "M", "Γ"},
            (x, p) -> breitWigner(x, p[0], p[1], p[2]),
            d -> {
                final double[] k = peak(d);
                return new double[]{k[0] + k[3], k[1], 2.35 * k[2]};
            }, new int[]{0, 1, 2}, (x, p) -> breitWigner(x, p[0], p[1], p[2]), (x, p) -> 0),
        new Model("bw+pol1", "Breit-Wigner + c₀ + c₁x", new String[]{"A", "M", "Γ", "c₀", "c₁"},
            (x, p) -> breitWigner(x, p[0], p[1], p[2]) + p[3] + p[4] * x,
            d -> {
                final double[] k = peak(d);
                return concat(new double[]{k[0], k[1], 2.35 * k[2]}, edgeLine(d));
            }, new int[]{0, 1, 2}, (x, p) -> breitWigner(x, p[0], p[1], p[2]), (x, p) -> p[3] + p[4] * x),
        new Model("crystalball", "Gaussian core, power-law tail (α, n)", new String[]{"A", "μ", "σ", "α", "n"},
            (x, p) -> crystalBall(x, p[0], p[1], p[2], p[3], p[4]),
            d -> {
                final double[] k = peak(d);
                return new double[]{k[0] + k[3], k[1], k[2], 1.5, 3};
            }, new int[]{0, 1, 2}, (x, p) -> crystalBall(x, p[0], p[1], p[2], p[3], p[4]), (x, p) -> 0),
        new Model("voigt", "pseudo-Voigt (Lorentz ⊗ Gauss)", new String[]{"A", "μ", "FWHM", "η"},
            (x, p) -> voigt(x, p[0], p[1], p[2], p[3]),
            d -> {
                final double[] k = peak(d);
                return new double[]{k[0] + k[3], k[1], 2.35 * k[2], 0.4};
            }, new int[]{0, 1, 2}, (x, p) -> voigt(x, p[0], p[1], p[2], p[3]), (x, p) -> 0),
        new Model("cb+expo", "Crystal Ball + exp(c₀ + c₁x): a mass peak with its radiative tail",
            new String[]{"A", "μ", "σ", "α", "n", "c₀", "c₁"},
            (x, p) -> crystalBall(x, p[0], p[1], p[2], p[3], p[4]) + Math.exp(Math.min(700, p[5] + p[6] * x)),
            d -> {
                final double[] k = peak(d);
                return concat(new double[]{k[0], k[1], k[2], 1.5, 3}, logLine(d));
            }, new int[]{0, 1, 2}, (x, p) -> crystalBall(x, p[0], p[1], p[2], p[3], p[4]),
            (x, p) -> Math.exp(Math.min(700, p[5] + p[6] * x))),
        new Model("voigt+pol1", "pseudo-Voigt + c₀ + c₁x", new String[]{"A", "μ", "FWHM", "η", "c₀", "c₁"},
            (x, p) -> voigt(x, p[0], p[1], p[2], p[3]) + p[4] + p[5] * x,
            d -> {
                final double[] k = peak(d);
                return concat(new double[]{k[0], k[1], 2.35 * k[2], 0.4}, edgeLine(d));
            }, new int[]{0, 1, 2}, (x, p) -> voigt(x, p[0], p[1], p[2], p[3]), (x, p) -> p[4] + p[5] * x),
        new Model("landau", "Moyal: A·exp(-½(λ+e^-λ)), λ=(x-MPV)/w", new String[]{"A", "MPV", "w"},
            (x, p) -> landau(x, p[0], p[1], p[2]),
            d -> {
                final double[] k = peak(d);
                return new double[]{k[0] + k[3], k[1], k[2] * 0.6};
            }, null, null, null),
        new Model("expo", "exp(c₀ + c₁x)", new String[]{"c₀", "c₁"},
            (x, p) -> Math.exp(Math.min(700, p[0] + p[1] * x)), HepAnalysis::logLine, null, null, null),
        new Model("pol1", "c₀ + c₁x", new String[]{"c₀", "c₁"}, (x, p) -> p[0] + p[1] * x, HepAnalysis::edgeLine,
            null, null, null),
        new Model("pol2", "c₀ + c₁x + c₂x²", new String[]{"c₀", "c₁", "c₂"}, (x, p) -> p[0] + p[1] * x + p[2] * x * x,
            d -> concat(edgeLine(d), 0), null, null, null),
        new Model("pol3", "c₀ + c₁x + c₂x² + c₃x³", new String[]{"c₀", "c₁", "c₂", "c₃"},
            (x, p) -> p[0] + x * (p[1] + x * (p[2] + x * p[3])), d -> concat(edgeLine(d), 0, 0), null, null, null),
        new Model("powerlaw", "A·x^(-k)", new String[]{"A", "k"},
            (x, p) -> p[0] * Math.pow(Math.max(1e-300, x), -p[1]),
            d -> {
                double sx = 0;
                double sy = 0;
                double sxx = 0;
                double sxy = 0;
                int n = 0;
                for (int i = 0; i < d.n(); i++) {
                    if (d.y[i] <= 0 || d.x[i] <= 0) continue;
                    final double lx = Math.log(d.x[i]);
                    final double ly = Math.log(d.y[i]);
                    sx += lx;
                    sy += ly;
                    sxx += lx * lx;
                    sxy += lx * ly;
                    n++;
                }
                if (n < 2) return new double[]{1, 1};
                final double b = (n * sxy - sx * sy) / Math.max(1e-300, n * sxx - sx * sx);
                return new double[]{Math.exp((sy - b * sx) / n), -b};
            }, null, null, null),
        new Model("tsallis", "A·pT·(1 + pT/(nT))^(-n): a pT spectrum", new String[]{"A", "T", "n"},
            (x, p) -> p[0] * x * Math.pow(1 + x / (Math.max(1e-6, p[2]) * p[1]), -p[2]),
            d -> {
                final double[] mo = moments(d);
                final double t = Math.max(1e-3, mo[0] / 3);
                double norm = 0;
                for (int i = 0; i < d.n(); i++) norm = Math.max(norm, d.y[i] / Math.max(1e-12, d.x[i] * Math.pow(1 + d.x[i] / (8 * t), -8)));
                return new double[]{norm, t, 8};
            }, null, null, null),
        new Model("erf turn-on", "ε·½(1+erf((x-x₅₀)/(√2σ))): a trigger efficiency", new String[]{"ε", "x₅₀", "σ"},
            (x, p) -> p[0] * 0.5 * (1 + erf((x - p[1]) / (Math.sqrt(2) * Math.abs(p[2]) + 1e-300))),
            d -> {
                final double top = max(d.y);
                int k = 0;
                while (k < d.n() - 1 && d.y[k] < top / 2) k++;
                return new double[]{top, d.x[k], (d.hi - d.lo) / 12};
            }, null, null, null));

    /** A p-value and its significance, written as a physicist reads them. */
    static String pz(double p) {
        if (!(p > 0)) return "< 1e-300  (> 37σ)";
        final double z = zOfP(p);
        return String.format(Locale.ROOT, "%.4g  (%.2fσ)", p, z);
    }

    public static Model model(String name) {
        for (Model m : MODELS) if (m.name.equals(name)) return m;
        return MODELS.get(0);
    }

    /** N Gaussian peaks on a second-order polynomial: what "fit the peaks found" fits. */
    static Model peaks(List<Peak> found) {
        final int n = Math.min(8, found.size());
        final String[] names = new String[3 * n + 3];
        for (int k = 0; k < n; k++) {
            names[3 * k] = "A" + (k + 1);
            names[3 * k + 1] = "μ" + (k + 1);
            names[3 * k + 2] = "σ" + (k + 1);
        }
        names[3 * n] = "c₀";
        names[3 * n + 1] = "c₁";
        names[3 * n + 2] = "c₂";
        final Shape sig = (x, p) -> {
            double s = 0;
            for (int k = 0; k < n; k++) s += gauss(x, p[3 * k], p[3 * k + 1], p[3 * k + 2]);
            return s;
        };
        final Shape bkg = (x, p) -> p[3 * n] + p[3 * n + 1] * x + p[3 * n + 2] * x * x;
        return new Model(n + " peaks + pol2", "Σ gaus + pol2", names, (x, p) -> sig.at(x, p) + bkg.at(x, p),
            d -> {
                final double[] p = new double[3 * n + 3];
                final double[] line = edgeLine(d);
                for (int k = 0; k < n; k++) {
                    final Peak pk = found.get(k);
                    p[3 * k] = Math.max(1e-9, pk.height - (line[0] + line[1] * pk.x));
                    p[3 * k + 1] = pk.x;
                    p[3 * k + 2] = Math.max(d.binWidth() * 0.5, pk.fwhm / 2.35482);
                }
                p[3 * n] = line[0];
                p[3 * n + 1] = line[1];
                return p;
            }, null, sig, bkg);
    }

    /* ================================================================== */
    /* Fitting                                                             */
    /* ================================================================== */

    public static final class Fit {
        public Model model;
        public double[] p;
        public double[] err;
        public double[][] cov;
        public double chi2;
        public double deviance;
        public int ndf;
        public int points;
        public double pValue;
        public boolean converged;
        public int iterations;
        public boolean poisson;
        public double aicc;
        public double bic;
        public double binWidth;
        public double lo;
        public double hi;
        public String message = "";

        /** The statistic the fit minimised: χ² or the Poisson deviance (Baker-Cousins). */
        public double statistic() {
            return poisson ? deviance : chi2;
        }

        public double at(double x) {
            return model.at(x, p);
        }
    }

    /**
     * Levenberg-Marquardt. In least squares the errors are the bins' own,
     * empty bins left out as ROOT does; in Poisson likelihood each bin weighs
     * by the model's expectation, which is iteratively reweighted least
     * squares on the Poisson deviance, and empty bins count.
     */
    public static Fit fit(Model model, Data d, boolean poisson) {
        final Fit r = new Fit();
        r.model = model;
        r.poisson = poisson;
        r.binWidth = d.binWidth();
        r.lo = d.lo;
        r.hi = d.hi;
        final int m = model.params.length;
        final List<Integer> use = new ArrayList<>();
        for (int i = 0; i < d.n(); i++) {
            if (!Double.isFinite(d.y[i])) continue;
            if (!poisson && (d.y[i] == 0 && d.e[i] == 0)) continue;
            use.add(i);
        }
        r.points = use.size();
        double[] p = model.guess.from(d);
        for (int k = 0; k < p.length; k++) if (!Double.isFinite(p[k])) p[k] = 0;
        if (r.points <= m) {
            r.p = p;
            r.err = new double[m];
            r.message = "too few bins (" + r.points + ") for " + m + " parameters";
            return r;
        }
        final int n = use.size();
        final double[] xs = new double[n];
        final double[] ys = new double[n];
        final double[] es = new double[n];
        for (int k = 0; k < n; k++) {
            final int i = use.get(k);
            xs[k] = d.x[i];
            ys[k] = d.y[i];
            es[k] = d.e[i] > 0 ? d.e[i] : 1;
        }
        double lambda = 1e-3;
        double[] sigma = weights(model, p, xs, ys, es, poisson);
        double cost = cost(model, p, xs, ys, sigma);
        int it = 0;
        boolean done = false;
        for (; it < 300 && !done; it++) {
            final double[][] jac = jacobian(model, p, xs, sigma);
            final double[][] a = new double[m][m];
            final double[] g = new double[m];
            for (int k = 0; k < n; k++) {
                final double res = (ys[k] - model.at(xs[k], p)) / sigma[k];
                for (int i = 0; i < m; i++) {
                    g[i] += jac[k][i] * res;
                    for (int j = 0; j <= i; j++) a[i][j] += jac[k][i] * jac[k][j];
                }
            }
            for (int i = 0; i < m; i++) for (int j = i + 1; j < m; j++) a[i][j] = a[j][i];
            boolean improved = false;
            while (lambda < 1e12) {
                final double[][] damped = new double[m][m];
                for (int i = 0; i < m; i++) {
                    damped[i] = a[i].clone();
                    damped[i][i] += lambda * Math.max(a[i][i], 1e-12);
                }
                final double[] step = solve(damped, g);
                if (step == null) {
                    lambda *= 10;
                    continue;
                }
                final double[] trial = p.clone();
                for (int i = 0; i < m; i++) trial[i] += step[i];
                final double[] ts = weights(model, trial, xs, ys, es, poisson);
                final double c = cost(model, trial, xs, ys, ts);
                if (Double.isFinite(c) && c < cost) {
                    final double gain = cost - c;
                    p = trial;
                    sigma = ts;
                    lambda = Math.max(1e-12, lambda / 10);
                    improved = true;
                    if (gain < 1e-9 * (1 + c)) done = true;
                    cost = c;
                    break;
                }
                lambda *= 10;
            }
            if (!improved) done = true;
        }
        r.iterations = it;
        r.p = p;
        // The covariance: the inverse of the curvature at the minimum.
        final double[][] jac = jacobian(model, p, xs, sigma);
        final double[][] a = new double[m][m];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) a[i][j] += jac[k][i] * jac[k][j];
        }
        r.cov = invert(a);
        r.err = new double[m];
        r.converged = r.cov != null && it < 300;
        if (r.cov != null) for (int i = 0; i < m; i++) r.err[i] = Math.sqrt(Math.max(0, r.cov[i][i]));
        r.chi2 = 0;
        r.deviance = 0;
        for (int k = 0; k < n; k++) {
            final double mu = model.at(xs[k], p);
            r.chi2 += sq((ys[k] - mu) / es[k]);
            final double muc = Math.max(mu, 1e-300);
            r.deviance += 2 * (muc - ys[k] + (ys[k] > 0 ? ys[k] * Math.log(ys[k] / muc) : 0));
        }
        r.ndf = n - m;
        r.pValue = gammaQ(r.ndf / 2.0, r.statistic() / 2);
        final double stat = r.statistic();
        r.aicc = stat + 2 * m + (n - m - 1 > 0 ? 2.0 * m * (m + 1) / (n - m - 1) : 1e9);
        r.bic = stat + m * Math.log(n);
        if (!r.converged) r.message = r.cov == null ? "the curvature matrix is singular" : "did not converge";
        return r;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double[] weights(Model model, double[] p, double[] xs, double[] ys, double[] es, boolean poisson) {
        final double[] s = new double[xs.length];
        for (int k = 0; k < xs.length; k++) {
            s[k] = poisson ? Math.sqrt(Math.max(model.at(xs[k], p), 0.25)) : es[k];
        }
        return s;
    }

    private static double cost(Model model, double[] p, double[] xs, double[] ys, double[] s) {
        double c = 0;
        for (int k = 0; k < xs.length; k++) {
            final double v = model.at(xs[k], p);
            if (!Double.isFinite(v)) return Double.POSITIVE_INFINITY;
            c += sq((ys[k] - v) / s[k]);
        }
        return c;
    }

    private static double[][] jacobian(Model model, double[] p, double[] xs, double[] s) {
        final int m = p.length;
        final double[][] j = new double[xs.length][m];
        for (int i = 0; i < m; i++) {
            final double h = 1e-6 * Math.max(Math.abs(p[i]), 1e-4);
            final double[] up = p.clone();
            final double[] dn = p.clone();
            up[i] += h;
            dn[i] -= h;
            for (int k = 0; k < xs.length; k++) {
                j[k][i] = (model.at(xs[k], up) - model.at(xs[k], dn)) / (2 * h) / s[k];
                if (!Double.isFinite(j[k][i])) j[k][i] = 0;
            }
        }
        return j;
    }

    /** Gauss-Jordan with partial pivoting; null when singular. */
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
                final double f = m[r][c] / m[c][c];
                if (f == 0) continue;
                for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
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

    /* ================================================================== */
    /* What a fit says                                                     */
    /* ================================================================== */

    public static String report(Fit f) {
        final StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "%s   %s%n", f.model.name, f.model.formula));
        s.append(String.format(Locale.ROOT, "range [%.5g, %.5g]   %d bins   %s   %d iterations%s%n", f.lo, f.hi,
            f.points, f.poisson ? "Poisson likelihood" : "least squares (χ²)", f.iterations,
            f.message.isEmpty() ? "" : "   ⚠ " + f.message));
        for (int i = 0; i < f.p.length; i++) {
            s.append(String.format(Locale.ROOT, "  %-6s = %14.6g  ± %-12.4g%n", f.model.params[i], f.p[i],
                f.err == null ? Double.NaN : f.err[i]));
        }
        s.append(String.format(Locale.ROOT, "χ²/ndf = %.4g / %d = %.4g", f.chi2, f.ndf, f.ndf > 0 ? f.chi2 / f.ndf : Double.NaN));
        if (f.poisson) s.append(String.format(Locale.ROOT, "   deviance = %.4g", f.deviance));
        s.append("   p-value = ").append(pz(f.pValue)).append('\n');
        s.append(String.format(Locale.ROOT, "AICc = %.4g   BIC = %.4g%n", f.aicc, f.bic));
        derived(f, s);
        if (f.cov != null && f.p.length > 1) {
            s.append("correlations:\n");
            for (int i = 0; i < f.p.length; i++) {
                s.append("  ");
                for (int j = 0; j < f.p.length; j++) {
                    final double c = f.cov[i][j] / Math.sqrt(Math.max(1e-300, f.cov[i][i] * f.cov[j][j]));
                    s.append(String.format(Locale.ROOT, "%6.2f", c));
                }
                s.append('\n');
            }
        }
        return s.toString();
    }

    /** Yields, widths and significance, for the models that have a signal. */
    private static void derived(Fit f, StringBuilder s) {
        if (f.model.signal == null || f.p.length < 3) return;
        final double a = f.p[0];
        final double mean = f.p[1];
        final double width = Math.abs(f.p[2]);
        final String n = f.model.name;
        final double sigma;
        if (n.startsWith("breit") || n.startsWith("bw")) {
            s.append(String.format(Locale.ROOT, "mass M = %.6g ± %.3g   width Γ = %.5g ± %.3g%n", mean, f.err[1], width,
                f.err[2]));
            sigma = width / 2.35482;
        } else if (n.startsWith("voigt")) {
            s.append(String.format(Locale.ROOT, "position = %.6g ± %.3g   FWHM = %.5g ± %.3g   Lorentz share η = %.3f%n",
                mean, f.err[1], width, f.err[2], f.p[3]));
            sigma = width / 2.35482;
        } else {
            sigma = width;
            s.append(String.format(Locale.ROOT, "mean = %.6g ± %.3g   σ = %.5g ± %.3g   FWHM = %.5g   resolution σ/μ = %.4g%%%n",
                mean, f.err[1], sigma, f.err[2], 2.35482 * sigma, 100 * sigma / Math.max(1e-300, Math.abs(mean))));
        }
        // The signal counted by integrating its part; its error by the parameters' covariance.
        final double lo = mean - 2 * sigma;
        final double hi = mean + 2 * sigma;
        final double sAll = integral(f.model.signalPart, f.p, mean - 12 * sigma - 10 * width, mean + 12 * sigma + 10 * width)
            / f.binWidth;
        final double sWin = integral(f.model.signalPart, f.p, lo, hi) / f.binWidth;
        final double bWin = f.model.backgroundPart == null ? 0 : integral(f.model.backgroundPart, f.p, lo, hi) / f.binWidth;
        final double sErr = yieldError(f);
        s.append(String.format(Locale.ROOT, "signal yield = %.5g ± %.3g events%n", sAll, sErr));
        if (bWin > 0) {
            final double zA = Math.sqrt(Math.max(0, 2 * ((sWin + bWin) * Math.log(1 + sWin / bWin) - sWin)));
            s.append(String.format(Locale.ROOT, "in μ ± 2σ: S = %.5g  B = %.5g  S/B = %.3g  S/√B = %.3g  Z(Asimov) = %.3gσ%n",
                sWin, bWin, sWin / bWin, sWin / Math.sqrt(bWin), zA));
        } else if (a > 0) {
            s.append(String.format(Locale.ROOT, "in μ ± 2σ: S = %.5g%n", sWin));
        }
    }

    private static double yieldError(Fit f) {
        if (f.cov == null) return Double.NaN;
        final int m = f.p.length;
        final double[] grad = new double[m];
        final double sigma = Math.abs(f.p[2]);
        final double lo = f.p[1] - 12 * sigma - 10 * sigma;
        final double hi = f.p[1] + 12 * sigma + 10 * sigma;
        final double y0 = integral(f.model.signalPart, f.p, lo, hi);
        for (int i = 0; i < m; i++) {
            final double h = 1e-5 * Math.max(Math.abs(f.p[i]), 1e-6);
            final double[] up = f.p.clone();
            up[i] += h;
            grad[i] = (integral(f.model.signalPart, up, lo, hi) - y0) / h;
        }
        double v = 0;
        for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) v += grad[i] * f.cov[i][j] * grad[j];
        return Math.sqrt(Math.max(0, v)) / f.binWidth;
    }

    /** Simpson's rule on 2000 intervals. */
    static double integral(Shape f, double[] p, double lo, double hi) {
        if (f == null || !(hi > lo)) return 0;
        final int n = 2000;
        final double h = (hi - lo) / n;
        double s = f.at(lo, p) + f.at(hi, p);
        for (int i = 1; i < n; i++) s += f.at(lo + i * h, p) * (i % 2 == 1 ? 4 : 2);
        return s * h / 3;
    }

    /** Every model on the same data, ranked by AICc, with the Akaike weights. */
    public static List<Fit> rank(Data d, boolean poisson) {
        final List<Fit> all = new ArrayList<>();
        for (Model m : MODELS) {
            try {
                final Fit f = fit(m, d, poisson);
                if (f.converged && Double.isFinite(f.aicc)) all.add(f);
            } catch (RuntimeException failed) {
                // a model that cannot even start is not ranked
            }
        }
        all.sort(Comparator.comparingDouble(f -> f.aicc));
        return all;
    }

    public static String rankReport(List<Fit> fits) {
        if (fits.isEmpty()) return "No model converged on this range.";
        final double best = fits.get(0).aicc;
        double norm = 0;
        for (Fit f : fits) norm += Math.exp(-0.5 * (f.aicc - best));
        final StringBuilder s = new StringBuilder("Models ranked by AICc (Akaike weight = the probability each is the best of these):\n");
        s.append(String.format(Locale.ROOT, "  %-13s %10s %8s %10s %9s %8s%n", "model", "ΔAICc", "weight", "χ²/ndf",
            "p-value", "k"));
        for (Fit f : fits) {
            s.append(String.format(Locale.ROOT, "  %-13s %10.3f %8.3f %10.4g %9.3g %8d%n", f.model.name, f.aicc - best,
                Math.exp(-0.5 * (f.aicc - best)) / norm, f.ndf > 0 ? f.chi2 / f.ndf : Double.NaN, f.pValue,
                f.model.params.length));
        }
        s.append("\nBest:\n").append(report(fits.get(0)));
        return s.toString();
    }

    /** The fitted curve as a function histogram a pad draws over the data. */
    public static Hist curve(Fit f, String name, Color color) {
        final Hist h = new Hist();
        h.dim = 1;
        h.kind = "h1";
        h.className = "TF1";
        h.name = name;
        h.title = f.model.name;
        h.formula = f.model.formula;
        h.option = "C";
        h.line = color;
        h.lineWidth = 2;
        final int n = 600;
        h.x = new Axis();
        h.x.n = n;
        h.x.lo = f.lo;
        h.x.hi = f.hi;
        h.v = new double[n];
        for (int i = 0; i < n; i++) h.v[i] = f.at(h.x.center(i));
        return h;
    }

    /** The pulls of the data against a fit, (data - fit)/error, as a histogram on the same bins. */
    public static Hist pulls(Hist data, Fit f) {
        final Hist h = new Hist();
        h.dim = 1;
        h.kind = "h1";
        h.className = "TH1D";
        h.name = data.name + "_pulls";
        h.title = "pulls";
        h.x = data.x;
        h.yTitle = "(data - fit)/σ";
        h.v = new double[data.nx()];
        h.err = new double[data.nx()];
        h.fill = new Color(0x4C8CE0);
        h.fillStyle = 1001;
        h.line = new Color(0x2050A0);
        h.option = "HIST";
        for (int i = 0; i < data.nx(); i++) {
            final double x = data.x.center(i);
            if (x < f.lo || x > f.hi) continue;
            final double e = data.error(i);
            h.v[i] = e > 0 ? (data.at(i, 0) - f.at(x)) / e : 0;
        }
        return h;
    }

    /* ================================================================== */
    /* Peaks                                                               */
    /* ================================================================== */

    public record Peak(double x, double height, double fwhm, double prominence, double localSignificance) {
    }

    /**
     * Local maxima of the contents smoothed by a Gaussian of `smooth` bins,
     * kept when they stand out of their surroundings by more than `threshold`
     * of the highest: TSpectrum's job, done plainly.
     */
    public static List<Peak> findPeaks(Data d, double smooth, double threshold) {
        final int n = d.n();
        final List<Peak> out = new ArrayList<>();
        if (n < 5) return out;
        final double[] s = smooth(d.y, smooth);
        final double top = max(s);
        for (int i = 1; i < n - 1; i++) {
            if (!(s[i] > s[i - 1] && s[i] >= s[i + 1])) continue;
            // Prominence: above the higher of the two lowest points before a higher peak.
            double leftMin = s[i];
            for (int k = i - 1; k >= 0 && s[k] <= s[i]; k--) leftMin = Math.min(leftMin, s[k]);
            double rightMin = s[i];
            for (int k = i + 1; k < n && s[k] <= s[i]; k++) rightMin = Math.min(rightMin, s[k]);
            final double base = Math.max(leftMin, rightMin);
            final double prominence = s[i] - base;
            if (prominence < threshold * top) continue;
            int l = i;
            int r = i;
            while (l > 0 && s[l] - base > prominence / 2) l--;
            while (r < n - 1 && s[r] - base > prominence / 2) r++;
            final double fwhm = Math.max(d.w[i], d.x[r] - d.x[l]);
            final double z = prominence / Math.sqrt(Math.max(1, base));
            // A parabola through the three bins puts the top between them.
            final double den = s[i - 1] - 2 * s[i] + s[i + 1];
            final double shift = den == 0 ? 0 : 0.5 * (s[i - 1] - s[i + 1]) / den;
            final double x = d.x[i] + shift * (d.x[i + 1] - d.x[i - 1]) / 2;
            out.add(new Peak(x, d.y[i], fwhm, prominence, z));
        }
        out.sort((a, b) -> Double.compare(b.prominence(), a.prominence()));
        return out;
    }

    static double[] smooth(double[] y, double sigmaBins) {
        if (sigmaBins <= 0.01) return y.clone();
        final int r = (int) Math.ceil(3 * sigmaBins);
        final double[] k = new double[2 * r + 1];
        for (int i = -r; i <= r; i++) k[i + r] = Math.exp(-0.5 * i * i / (sigmaBins * sigmaBins));
        final double[] out = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            double s = 0;
            double w = 0;
            for (int j = -r; j <= r; j++) {
                final int q = i + j;
                if (q < 0 || q >= y.length) continue;
                s += y[q] * k[j + r];
                w += k[j + r];
            }
            out[i] = s / w;
        }
        return out;
    }

    public static String peaksReport(List<Peak> peaks) {
        if (peaks.isEmpty()) return "No peak stands out at this threshold.";
        final StringBuilder s = new StringBuilder(peaks.size() + " peaks, by prominence:\n");
        s.append(String.format(Locale.ROOT, "  %3s %14s %12s %12s %12s %10s%n", "#", "position", "height", "FWHM",
            "prominence", "local Z"));
        int k = 1;
        for (Peak p : peaks) {
            s.append(String.format(Locale.ROOT, "  %3d %14.6g %12.5g %12.5g %12.5g %9.2fσ%n", k++, p.x(), p.height(),
                p.fwhm(), p.prominence(), p.localSignificance()));
        }
        return s.toString();
    }

    /* ================================================================== */
    /* Bayesian Blocks                                                     */
    /* ================================================================== */

    /**
     * Scargle's Bayesian Blocks (2013) on binned counts: the binning in which
     * every block is as constant as the data allow, its edges where the rate
     * truly changes, with a false-alarm probability p0 per edge. Answers a
     * histogram of densities on those edges, comparable in height to the bins
     * it came from.
     */
    public static Hist bayesianBlocks(Hist h, double p0) {
        final int n = h.nx();
        final double[] counts = new double[n];
        final double[] edges = new double[n + 1];
        for (int i = 0; i < n; i++) counts[i] = Math.max(0, h.at(i, 0));
        for (int i = 0; i <= n; i++) edges[i] = h.x.edge(i);
        final double prior = 4 - Math.log(73.53 * p0 * Math.pow(Math.max(1, n), -0.478));
        final double[] best = new double[n];
        final int[] last = new int[n];
        for (int r = 0; r < n; r++) {
            double bestValue = -Double.MAX_VALUE;
            int bestStart = 0;
            double cum = 0;
            for (int start = r; start >= 0; start--) {
                cum += counts[start];
                final double width = edges[r + 1] - edges[start];
                final double fit = cum > 0 ? cum * (Math.log(cum) - Math.log(width)) : 0;
                final double v = fit - prior + (start > 0 ? best[start - 1] : 0);
                if (v > bestValue) {
                    bestValue = v;
                    bestStart = start;
                }
            }
            best[r] = bestValue;
            last[r] = bestStart;
        }
        final List<Integer> starts = new ArrayList<>();
        for (int r = n - 1; r >= 0; r = last[r] - 1) starts.add(0, last[r]);
        final int blocks = starts.size();
        final Hist b = new Hist();
        b.dim = 1;
        b.kind = "h1";
        b.className = "TH1D";
        b.name = h.name + "_blocks";
        b.title = h.title + " — Bayesian Blocks (" + blocks + " blocks, p₀ = " + p0 + ")";
        b.x = new Axis();
        b.x.n = blocks;
        b.x.lo = edges[0];
        b.x.hi = edges[n];
        b.x.title = h.x.title;
        b.x.edges = new double[blocks + 1];
        b.v = new double[blocks];
        b.err = new double[blocks];
        double meanWidth = (edges[n] - edges[0]) / Math.max(1, n);
        for (int k = 0; k < blocks; k++) {
            final int s = starts.get(k);
            final int e = k + 1 < blocks ? starts.get(k + 1) : n;
            b.x.edges[k] = edges[s];
            double c = 0;
            for (int i = s; i < e; i++) c += counts[i];
            final double width = edges[e] - edges[s];
            b.v[k] = c / width * meanWidth;
            b.err[k] = Math.sqrt(c) / width * meanWidth;
        }
        b.x.edges[blocks] = edges[n];
        b.yTitle = "entries per " + String.format(Locale.ROOT, "%.3g", meanWidth);
        b.line = new Color(0xD03030);
        b.lineWidth = 2;
        b.option = "HIST";
        b.stats = false;
        return b;
    }

    /* ================================================================== */
    /* Two histograms                                                      */
    /* ================================================================== */

    public static String compare(Hist a, Hist b) {
        final int n = Math.min(a.nx(), b.nx());
        final StringBuilder s = new StringBuilder();
        s.append("Comparing ").append(a.name).append(" with ").append(b.name).append('\n');
        if (a.nx() != b.nx() || Math.abs(a.x.lo - b.x.lo) > 1e-9 * (1 + Math.abs(a.x.lo))
            || Math.abs(a.x.hi - b.x.hi) > 1e-9 * (1 + Math.abs(a.x.hi))) {
            s.append("⚠ the binnings differ; bin by bin over the first ").append(n).append('\n');
        }
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            na += a.at(i, 0);
            nb += b.at(i, 0);
        }
        // Kolmogorov-Smirnov on the cumulative shapes.
        double ca = 0;
        double cb = 0;
        double dmax = 0;
        for (int i = 0; i < n; i++) {
            ca += a.at(i, 0) / Math.max(1e-300, na);
            cb += b.at(i, 0) / Math.max(1e-300, nb);
            dmax = Math.max(dmax, Math.abs(ca - cb));
        }
        final double ne = na * nb / Math.max(1e-300, na + nb);
        final double ks = kolmogorov(Math.sqrt(ne) * dmax);
        s.append(String.format(Locale.ROOT, "Kolmogorov-Smirnov: D = %.5g   p = %.4g  (binned: conservative)%n", dmax, ks));
        // χ² of homogeneity for two unweighted histograms (ROOT's Chi2Test "UU").
        double chi2 = 0;
        int used = 0;
        for (int i = 0; i < n; i++) {
            final double x = a.at(i, 0);
            final double y = b.at(i, 0);
            if (x + y <= 0) continue;
            chi2 += sq(nb * x - na * y) / (x + y);
            used++;
        }
        chi2 /= Math.max(1e-300, na * nb);
        final int ndf = Math.max(1, used - 1);
        final double p = gammaQ(ndf / 2.0, chi2 / 2);
        s.append(String.format(Locale.ROOT, "χ² homogeneity: χ²/ndf = %.4g / %d   p = %.4g  (%.2fσ)%n", chi2, ndf, p,
            zOfP(p)));
        s.append(String.format(Locale.ROOT, "integrals: %.6g and %.6g   ratio %.5g%n", na, nb, na / Math.max(1e-300, nb)));
        return s.toString();
    }

    /** The ratio a/b, b scaled to a's integral, with the errors of both. */
    public static Hist ratio(Hist a, Hist b) {
        final int n = Math.min(a.nx(), b.nx());
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            na += a.at(i, 0);
            nb += b.at(i, 0);
        }
        final double k = nb > 0 ? na / nb : 1;
        final Hist r = new Hist();
        r.dim = 1;
        r.kind = "h1";
        r.className = "TH1D";
        r.name = a.name + "_over_" + b.name;
        r.title = a.name + " / " + b.name + " (normalised)";
        r.x = a.x;
        r.yTitle = "ratio";
        r.v = new double[a.nx()];
        r.err = new double[a.nx()];
        r.option = "E1";
        r.marker = Color.BLACK;
        r.markerStyle = 20;
        r.line = Color.BLACK;
        for (int i = 0; i < n; i++) {
            final double x = a.at(i, 0);
            final double y = b.at(i, 0) * k;
            if (y <= 0) continue;
            r.v[i] = x / y;
            final double ex = a.error(i);
            final double ey = b.error(i) * k;
            r.err[i] = r.v[i] * Math.sqrt(sq(x > 0 ? ex / x : 0) + sq(ey / y));
        }
        return r;
    }

    /* ================================================================== */
    /* 1D statistics                                                       */
    /* ================================================================== */

    public static String statistics(Hist h, double lo, double hi) {
        double s = 0;
        double s1 = 0;
        double s2 = 0;
        double s3 = 0;
        double s4 = 0;
        double sw2 = 0;
        for (int i = 0; i < h.nx(); i++) {
            final double x = h.x.center(i);
            if (x < lo || x > hi) continue;
            final double w = h.at(i, 0);
            final double e = h.error(i);
            s += w;
            s1 += w * x;
            sw2 += e * e;
        }
        final double mean = s1 / Math.max(1e-300, s);
        for (int i = 0; i < h.nx(); i++) {
            final double x = h.x.center(i);
            if (x < lo || x > hi) continue;
            final double w = h.at(i, 0);
            final double d = x - mean;
            s2 += w * d * d;
            s3 += w * d * d * d;
            s4 += w * d * d * d * d;
        }
        final double var = s2 / Math.max(1e-300, s);
        final double rms = Math.sqrt(Math.max(0, var));
        final double neff = sw2 > 0 ? s * s / sw2 : s;
        final StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%s in [%.5g, %.5g]%n", h.name, lo, hi));
        out.append(String.format(Locale.ROOT, "integral = %.6g   effective entries = %.6g%n", s, neff));
        out.append(String.format(Locale.ROOT, "mean = %.6g ± %.3g%n", mean, rms / Math.sqrt(Math.max(1, neff))));
        out.append(String.format(Locale.ROOT, "RMS  = %.6g ± %.3g%n", rms, rms / Math.sqrt(Math.max(1, 2 * neff))));
        out.append(String.format(Locale.ROOT, "skewness = %.4g   excess kurtosis = %.4g%n",
            s3 / Math.max(1e-300, s) / Math.pow(Math.max(1e-300, var), 1.5), s4 / Math.max(1e-300, s) / Math.max(1e-300, var * var) - 3));
        final double[] q = quantiles(h, lo, hi, new double[]{0.0228, 0.1587, 0.5, 0.8413, 0.9772});
        out.append(String.format(Locale.ROOT, "median = %.6g   68%% central interval [%.5g, %.5g]   95%% [%.5g, %.5g]%n",
            q[2], q[1], q[3], q[0], q[4]));
        int mode = 0;
        for (int i = 1; i < h.nx(); i++) if (h.at(i, 0) > h.at(mode, 0)) mode = i;
        out.append(String.format(Locale.ROOT, "mode = %.6g (bin %d)%n", h.x.center(mode), mode + 1));
        return out.toString();
    }

    static double[] quantiles(Hist h, double lo, double hi, double[] probs) {
        double total = 0;
        for (int i = 0; i < h.nx(); i++) {
            final double x = h.x.center(i);
            if (x >= lo && x <= hi) total += Math.max(0, h.at(i, 0));
        }
        final double[] out = new double[probs.length];
        for (int k = 0; k < probs.length; k++) {
            final double target = probs[k] * total;
            double cum = 0;
            out[k] = hi;
            for (int i = 0; i < h.nx(); i++) {
                final double x = h.x.center(i);
                if (x < lo || x > hi) continue;
                final double c = Math.max(0, h.at(i, 0));
                if (cum + c >= target && c > 0) {
                    final double f = (target - cum) / c;
                    out[k] = h.x.edge(i) + f * (h.x.edge(i + 1) - h.x.edge(i));
                    break;
                }
                cum += c;
            }
        }
        return out;
    }

    /* ================================================================== */
    /* TH2                                                                 */
    /* ================================================================== */

    public static Hist projection(Hist h, boolean onX) {
        final Hist p = new Hist();
        p.dim = 1;
        p.kind = "h1";
        p.className = "TH1D";
        final Axis axis = onX ? h.x : h.y;
        p.name = h.name + (onX ? "_px" : "_py");
        p.title = h.title + (onX ? " — projection on x" : " — projection on y");
        p.x = axis;
        p.v = new double[axis.n];
        p.err = new double[axis.n];
        for (int j = 0; j < h.ny(); j++) {
            for (int i = 0; i < h.nx(); i++) {
                final double v = h.at(i, j);
                p.v[onX ? i : j] += v;
                p.err[onX ? i : j] += Math.abs(v);
            }
        }
        for (int i = 0; i < p.err.length; i++) p.err[i] = Math.sqrt(p.err[i]);
        p.stats = true;
        p.option = "HIST";
        p.fill = new Color(0x7FA8E0);
        p.fillStyle = 1001;
        p.line = new Color(0x2050A0);
        double s = 0;
        double sx = 0;
        double sxx = 0;
        for (int i = 0; i < p.v.length; i++) {
            final double c = axis.center(i);
            s += p.v[i];
            sx += p.v[i] * c;
            sxx += p.v[i] * c * c;
        }
        p.entries = s;
        p.mean = s > 0 ? sx / s : 0;
        p.std = s > 0 ? Math.sqrt(Math.max(0, sxx / s - p.mean * p.mean)) : 0;
        return p;
    }

    /** ProfileX: the mean of y in each x bin, with the error of that mean. */
    public static Graph profile(Hist h) {
        final List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < h.nx(); i++) {
            double s = 0;
            double sy = 0;
            double syy = 0;
            for (int j = 0; j < h.ny(); j++) {
                final double w = h.at(i, j);
                if (w <= 0) continue;
                final double y = h.y.center(j);
                s += w;
                sy += w * y;
                syy += w * y * y;
            }
            if (s <= 0) continue;
            final double m = sy / s;
            final double rms = Math.sqrt(Math.max(0, syy / s - m * m));
            pts.add(new double[]{h.x.center(i), m, (h.x.edge(i + 1) - h.x.edge(i)) / 2, rms / Math.sqrt(s)});
        }
        final Graph g = new Graph();
        g.kind = "g";
        g.className = "TGraphErrors";
        g.name = h.name + "_pfx";
        g.title = h.title + " — profile in x (mean y)";
        g.x = new double[pts.size()];
        g.y = new double[pts.size()];
        g.exl = new double[pts.size()];
        g.exh = new double[pts.size()];
        g.eyl = new double[pts.size()];
        g.eyh = new double[pts.size()];
        for (int k = 0; k < pts.size(); k++) {
            g.x[k] = pts.get(k)[0];
            g.y[k] = pts.get(k)[1];
            g.exl[k] = pts.get(k)[2];
            g.exh[k] = pts.get(k)[2];
            g.eyl[k] = pts.get(k)[3];
            g.eyh[k] = pts.get(k)[3];
        }
        g.xTitle = h.x.title;
        g.yTitle = "⟨" + (h.y.title == null || h.y.title.isBlank() ? "y" : h.y.title) + "⟩";
        g.option = "AP";
        g.marker = new Color(0xC03030);
        g.markerStyle = 20;
        g.line = new Color(0xC03030);
        return g;
    }

    public static String statistics2(Hist h) {
        double s = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int j = 0; j < h.ny(); j++) {
            for (int i = 0; i < h.nx(); i++) {
                final double w = h.at(i, j);
                final double x = h.x.center(i);
                final double y = h.y.center(j);
                s += w;
                sx += w * x;
                sy += w * y;
                sxx += w * x * x;
                syy += w * y * y;
                sxy += w * x * y;
            }
        }
        final double mx = sx / Math.max(1e-300, s);
        final double my = sy / Math.max(1e-300, s);
        final double vx = sxx / Math.max(1e-300, s) - mx * mx;
        final double vy = syy / Math.max(1e-300, s) - my * my;
        final double cxy = sxy / Math.max(1e-300, s) - mx * my;
        final double rho = cxy / Math.sqrt(Math.max(1e-300, vx * vy));
        return String.format(Locale.ROOT,
            "%s: %d x %d bins%nintegral = %.6g%nmean x = %.6g   RMS x = %.5g%nmean y = %.6g   RMS y = %.5g%n"
                + "covariance = %.5g   correlation ρ = %.4f%n",
            h.name, h.nx(), h.ny(), s, mx, Math.sqrt(Math.max(0, vx)), my, Math.sqrt(Math.max(0, vy)), cxy, rho);
    }

    /* ================================================================== */
    /* The search: a bump hunt with its look-elsewhere effect and limits   */
    /* ================================================================== */

    /** One mass hypothesis of the scan. */
    public record ScanPoint(double mass, double q0, double zLocal, double pLocal, double yield, double yieldError,
                            double limitObserved, double[] limitExpected) {
    }

    /** What the scan found: its points, the most significant excess, and what it means globally. */
    public static final class Scan {
        public final List<ScanPoint> points = new ArrayList<>();
        public String background;
        public double sigma;
        public double backgroundDeviance;
        public ScanPoint best;
        public int upcrossings;
        public double referenceQ0 = 0.5;
        public double pGlobal;
        public double zGlobal;
    }

    /**
     * The search the experiments run: a Gaussian signal of the detector's
     * resolution slid along the spectrum over a background shape, fitted by
     * Poisson likelihood at each mass. At each mass, the test statistic of the
     * background-only hypothesis q₀ = D(B) − D(S+B) for an excess (zero for a
     * deficit) gives the local significance √q₀. The look-elsewhere effect is
     * estimated as Gross and Vitells do (2010): the up-crossings of q₀ above a
     * low reference level, counted on this very scan, extrapolated to the
     * level reached. At each mass, the 95% CLs upper limit on the signal yield
     * in the asymptotic approximation, observed and expected with its ±1σ and
     * ±2σ bands: the "Brazil plot".
     */
    public static Scan bumpHunt(Data d, String background, double sigma, int steps) {
        final Scan scan = new Scan();
        scan.background = background;
        scan.sigma = sigma;
        final Model bkg = model(background);
        final Fit b = fit(bkg, d, true);
        scan.backgroundDeviance = b.deviance;
        final int nb = bkg.params.length;
        final double bw = d.binWidth();
        final double lo = d.lo + 2 * sigma;
        final double hi = d.hi - 2 * sigma;
        if (!(hi > lo)) return scan;
        double[] start = b.p.clone();
        for (int k = 0; k < steps; k++) {
            final double m = lo + (hi - lo) * k / Math.max(1, steps - 1);
            final double[] bstart = start;
            final Model sb = new Model("gaus(" + fmt(m) + ")+" + background, "signal + " + bkg.formula,
                concatNames("A", bkg.params),
                (x, p) -> gauss(x, p[0], m, sigma) + bkg.f.at(x, Arrays.copyOfRange(p, 1, p.length)),
                dd -> {
                    final double[] p = new double[nb + 1];
                    // The excess over the background at this mass, as the start of the amplitude.
                    double excess = 0;
                    for (int i = 0; i < dd.n(); i++) {
                        if (Math.abs(dd.x[i] - m) < sigma) {
                            excess = Math.max(excess, dd.y[i] - bkg.f.at(dd.x[i], bstart));
                        }
                    }
                    p[0] = Math.max(1e-3, excess);
                    System.arraycopy(bstart, 0, p, 1, nb);
                    return p;
                }, null, null, null);
            final Fit f = fit(sb, d, true);
            final double a = f.p[0];
            final double ea = f.err == null || f.err.length == 0 ? Double.NaN : f.err[0];
            final double q0 = a > 0 ? Math.max(0, b.deviance - f.deviance) : 0;
            final double z = Math.sqrt(q0);
            final double p0 = 0.5 * gammaQ(0.5, q0 / 2);
            final double toYield = sigma * Math.sqrt(2 * Math.PI) / bw;
            final double yield = a * toYield;
            final double yErr = ea * toYield;
            // Asymptotic CLs: μ_up = μ̂ + σ Φ⁻¹(1 − α Φ(μ̂/σ)); expected: σ (Φ⁻¹(1 − α Φ(N)) + N).
            final double s = Double.isFinite(yErr) && yErr > 0 ? yErr : Double.NaN;
            final double hat = Math.max(0, yield);
            final double observed = Double.isNaN(s) ? Double.NaN : hat + s * zOfP(0.05 * phi(hat / s));
            final double[] expected = new double[5];
            for (int n = -2; n <= 2; n++) {
                expected[n + 2] = Double.isNaN(s) ? Double.NaN : s * (zOfP(0.05 * phi(n)) + n);
            }
            scan.points.add(new ScanPoint(m, q0, z, p0, yield, yErr, observed, expected));
            if (f.converged) start = Arrays.copyOfRange(f.p, 1, f.p.length);
        }
        // Up-crossings of the reference level and the most significant point.
        ScanPoint best = scan.points.get(0);
        boolean above = scan.points.get(0).q0() > scan.referenceQ0;
        for (ScanPoint p : scan.points) {
            if (p.q0() > best.q0()) best = p;
            final boolean now = p.q0() > scan.referenceQ0;
            if (now && !above) scan.upcrossings++;
            above = now;
        }
        scan.best = best;
        final double c = best.q0();
        final double pLocal = best.pLocal();
        final double nUp = Math.max(1, scan.upcrossings);
        scan.pGlobal = Math.min(1, pLocal + nUp * Math.exp(-(c - scan.referenceQ0) / 2));
        scan.zGlobal = zOfP(scan.pGlobal);
        return scan;
    }

    private static String[] concatNames(String first, String[] rest) {
        final String[] r = new String[rest.length + 1];
        r[0] = first;
        System.arraycopy(rest, 0, r, 1, rest.length);
        return r;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4g", v);
    }

    /** The standard normal cumulative distribution. */
    static double phi(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    public static String scanReport(Scan s) {
        if (s.best == null) return "The range is too narrow for a scan at this resolution.";
        final StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Bump hunt: Gaussian signal of σ = %.4g over %s, %d mass points, "
            + "Poisson likelihood%n", s.sigma, s.background, s.points.size()));
        out.append(String.format(Locale.ROOT, "background-only deviance = %.4g%n%n", s.backgroundDeviance));
        final ScanPoint b = s.best;
        out.append(String.format(Locale.ROOT, "most significant excess at m = %.5g%n", b.mass()));
        out.append(String.format(Locale.ROOT, "  signal yield = %.4g ± %.3g events%n", b.yield(), b.yieldError()));
        out.append(String.format(Locale.ROOT, "  q₀ = %.4g   local p₀ = %.3g   local Z = %.2fσ%n", b.q0(), b.pLocal(),
            b.zLocal()));
        out.append(String.format(Locale.ROOT, "look-elsewhere (Gross-Vitells): %d up-crossings of q₀ = %.1f%n",
            s.upcrossings, s.referenceQ0));
        out.append(String.format(Locale.ROOT, "  global p = %.3g   global Z = %.2fσ%n", s.pGlobal, Math.max(0, s.zGlobal)));
        final double verdict = b.zLocal();
        out.append(verdict >= 5 ? "  → a local 5σ: an observation, if the global significance holds\n"
            : verdict >= 3 ? "  → a local 3σ: evidence, to be weighed against the look-elsewhere effect\n"
            : "  → nothing beyond the fluctuations expected\n");
        out.append(String.format(Locale.ROOT, "%n95%% CLs upper limit on the yield at that mass: %.4g (expected %.4g, "
            + "±1σ [%.4g, %.4g])%n", b.limitObserved(), b.limitExpected()[2], b.limitExpected()[1], b.limitExpected()[3]));
        out.append("\n   mass        q₀     local Z    yield ± error          CLs 95% obs   exp\n");
        final int every = Math.max(1, s.points.size() / 25);
        for (int i = 0; i < s.points.size(); i += every) {
            final ScanPoint p = s.points.get(i);
            out.append(String.format(Locale.ROOT, "  %9.5g %8.3f %8.2fσ  %10.4g ± %-10.3g %12.4g %8.4g%n", p.mass(), p.q0(),
                p.zLocal(), p.yield(), p.yieldError(), p.limitObserved(), p.limitExpected()[2]));
        }
        return out.toString();
    }

    /** The local p₀ against the mass, with the 1σ to 5σ lines: the plot of a discovery. */
    public static RootScene.Group p0Plot(Scan s) {
        final RootScene.Group g = new RootScene.Group();
        g.kind = "mgraph";
        g.className = "TMultiGraph";
        g.name = "p0_scan";
        g.title = "local p₀";
        final Graph p = new Graph();
        p.kind = "g";
        p.name = "local p0";
        p.x = s.points.stream().mapToDouble(ScanPoint::mass).toArray();
        p.y = s.points.stream().mapToDouble(q -> Math.max(1e-300, q.pLocal())).toArray();
        p.line = new Color(0x1050C0);
        p.lineWidth = 2.5;
        p.option = "L";
        p.xTitle = "mass hypothesis";
        p.yTitle = "local p₀";
        double lowest = 1;
        for (double v : p.y) lowest = Math.min(lowest, v);
        for (int z = 1; z <= 7; z++) {
            final double pz = 0.5 * gammaQ(0.5, z * z / 2.0);
            if (pz < lowest * 0.05 && z > 2) break;
            final Graph line = new Graph();
            line.kind = "g";
            line.name = z + "σ";
            line.x = new double[]{p.x[0], p.x[p.x.length - 1]};
            line.y = new double[]{pz, pz};
            line.line = z >= 5 ? new Color(0xD02020) : z >= 3 ? new Color(0xE08020) : new Color(0x909090);
            line.lineStyle = 2;
            line.option = "L";
            g.items.add(line);
        }
        g.items.add(0, p);
        return g;
    }

    /** "1σ" ... "5σ" at the right end of the lines of the p₀ plot. */
    public static List<RootScene.Item> sigmaLabels(RootScene.Group p0) {
        final List<RootScene.Item> out = new ArrayList<>();
        for (RootScene.Item i : p0.items) {
            if (!(i instanceof Graph g) || !g.name.endsWith("σ") || g.x.length != 2) continue;
            final RootScene.Text t = new RootScene.Text();
            t.kind = "text";
            t.name = g.name;
            t.text = g.name;
            t.x = g.x[1];
            t.y = g.y[1] * 1.25;
            t.size = 0.045;
            t.color = g.line;
            t.align = 31;
            out.add(t);
        }
        return out;
    }

    /** The 95% CLs limits along the mass: observed, expected, and the green and yellow bands. */
    public static RootScene.Group brazil(Scan s) {
        final RootScene.Group g = new RootScene.Group();
        g.kind = "mgraph";
        g.className = "TMultiGraph";
        g.name = "cls_limits";
        g.title = "95% CLs upper limit on the signal yield";
        final double[] m = s.points.stream().mapToDouble(ScanPoint::mass).toArray();
        g.items.add(band(m, s, 0, 4, new Color(0xFFE000), "±2σ expected"));
        g.items.add(band(m, s, 1, 3, new Color(0x00C000), "±1σ expected"));
        final Graph exp = new Graph();
        exp.kind = "g";
        exp.name = "expected";
        exp.x = m;
        exp.y = s.points.stream().mapToDouble(p -> p.limitExpected()[2]).toArray();
        exp.line = Color.BLACK;
        exp.lineStyle = 2;
        exp.lineWidth = 2;
        exp.option = "L";
        exp.xTitle = "mass hypothesis";
        exp.yTitle = "N_{signal} (95% CLs)";
        final Graph obs = new Graph();
        obs.kind = "g";
        obs.name = "observed";
        obs.x = m;
        obs.y = s.points.stream().mapToDouble(ScanPoint::limitObserved).toArray();
        obs.line = Color.BLACK;
        obs.lineWidth = 2.5;
        obs.option = "L";
        g.items.add(exp);
        g.items.add(obs);
        return g;
    }

    private static Graph band(double[] m, Scan s, int low, int high, Color color, String name) {
        final int n = m.length;
        final Graph b = new Graph();
        b.kind = "g";
        b.name = name;
        b.x = new double[2 * n];
        b.y = new double[2 * n];
        for (int i = 0; i < n; i++) {
            b.x[i] = m[i];
            b.y[i] = s.points.get(i).limitExpected()[high];
            b.x[2 * n - 1 - i] = m[i];
            b.y[2 * n - 1 - i] = s.points.get(i).limitExpected()[low];
        }
        b.fill = color;
        b.fillStyle = 1001;
        b.line = color;
        b.option = "F";
        return b;
    }

    /* ================================================================== */
    /* Efficiencies                                                        */
    /* ================================================================== */

    /**
     * The efficiency passed/total in each bin with Clopper-Pearson's exact 68.3%
     * interval, as TEfficiency gives it by default: a graph with asymmetric
     * errors, ready for an erf turn-on fit.
     */
    public static Graph efficiency(Hist passed, Hist total) {
        final int n = Math.min(passed.nx(), total.nx());
        final List<double[]> pts = new ArrayList<>();
        final double alpha = 1 - 0.682689;
        for (int i = 0; i < n; i++) {
            final double t = Math.rint(total.at(i, 0));
            final double k = Math.min(t, Math.rint(passed.at(i, 0)));
            if (t <= 0) continue;
            final double e = k / t;
            final double lower = k <= 0 ? 0 : betaInverse(alpha / 2, k, t - k + 1);
            final double upper = k >= t ? 1 : betaInverse(1 - alpha / 2, k + 1, t - k);
            final double hw = (total.x.edge(i + 1) - total.x.edge(i)) / 2;
            pts.add(new double[]{total.x.center(i), e, hw, e - lower, upper - e});
        }
        final Graph g = new Graph();
        g.kind = "g";
        g.className = "TGraphAsymmErrors";
        g.name = passed.name + "_eff";
        g.title = "efficiency " + passed.name + " / " + total.name + " (Clopper-Pearson 68%)";
        final int m = pts.size();
        g.x = new double[m];
        g.y = new double[m];
        g.exl = new double[m];
        g.exh = new double[m];
        g.eyl = new double[m];
        g.eyh = new double[m];
        for (int i = 0; i < m; i++) {
            final double[] p = pts.get(i);
            g.x[i] = p[0];
            g.y[i] = p[1];
            g.exl[i] = p[2];
            g.exh[i] = p[2];
            g.eyl[i] = p[3];
            g.eyh[i] = p[4];
        }
        g.xTitle = total.x.title;
        g.yTitle = "efficiency";
        g.option = "AP";
        g.marker = Color.BLACK;
        g.markerStyle = 20;
        g.line = Color.BLACK;
        g.min = 0;
        g.max = 1.1;
        return g;
    }

    /** A graph's points as data to fit, each with the mean of its two errors. */
    public static Data data(Graph g) {
        final int n = g.x.length;
        final double[] w = new double[n];
        final double[] e = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = g.exl != null ? g.exl[i] + g.exh[i] : 1;
            e[i] = g.eyl != null ? 0.5 * (g.eyl[i] + g.eyh[i]) : 1;
            if (!(e[i] > 0)) e[i] = 1e-3;
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double x : g.x) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return new Data(g.x.clone(), w, g.y.clone(), e, lo, hi);
    }

    /** The regularised incomplete beta function I_x(a, b), by its continued fraction. */
    static double betaI(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        final double front = Math.exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) return front * betaFraction(x, a, b) / a;
        return 1 - front * betaFraction(1 - x, b, a) / b;
    }

    private static double betaFraction(double x, double a, double b) {
        final double tiny = 1e-300;
        double c = 1;
        double d = 1 - (a + b) * x / (a + 1);
        if (Math.abs(d) < tiny) d = tiny;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            final int m2 = 2 * m;
            double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            final double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-14) break;
        }
        return h;
    }

    /** The x where I_x(a, b) = p, by bisection. */
    static double betaInverse(double p, double a, double b) {
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 100; i++) {
            final double mid = 0.5 * (lo + hi);
            if (betaI(mid, a, b) < p) lo = mid;
            else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    /* ================================================================== */
    /* Special functions                                                   */
    /* ================================================================== */

    static double lnGamma(double x) {
        final double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155,
            0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        final double tmp = x + 5.5 - (x + 0.5) * Math.log(x + 5.5);
        double ser = 1.000000000190015;
        for (double v : c) ser += v / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /** The regularised upper incomplete gamma Q(a, x): the p-value of a χ² of 2x with 2a degrees. */
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

    static double erf(double x) {
        // Numerical Recipes' Chebyshev fit of erfc: a relative error below 1.2e-7.
        final double t = 1 / (1 + 0.5 * Math.abs(x));
        final double y = 1 - t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
            + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223
            + t * 0.17087277)))))))));
        return x >= 0 ? y : -y;
    }

    /** The Kolmogorov distribution's tail. */
    static double kolmogorov(double lambda) {
        if (lambda < 0.2) return 1;
        double s = 0;
        for (int k = 1; k <= 100; k++) {
            final double term = 2 * (k % 2 == 1 ? 1 : -1) * Math.exp(-2 * k * k * lambda * lambda);
            s += term;
            if (Math.abs(term) < 1e-12) break;
        }
        return Math.max(0, Math.min(1, s));
    }

    /** The one-sided significance of a p-value: Φ⁻¹(1 - p), by Acklam's rational approximation. */
    public static double zOfP(double p) {
        if (!(p > 0)) return Double.POSITIVE_INFINITY;
        if (p >= 1) return Double.NEGATIVE_INFINITY;
        final double q = 1 - p;
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
            -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
            4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        final double low = 0.02425;
        if (p > 1 - low) {
            // The lower tail, from q itself.
            final double u = Math.sqrt(-2 * Math.log(q));
            return (((((c[0] * u + c[1]) * u + c[2]) * u + c[3]) * u + c[4]) * u + c[5])
                / ((((d[0] * u + d[1]) * u + d[2]) * u + d[3]) * u + 1);
        }
        if (p < low) {
            // The upper tail from p, never from 1 - q: a p of 1e-30 must not round to zero.
            final double u = Math.sqrt(-2 * Math.log(p));
            return -(((((c[0] * u + c[1]) * u + c[2]) * u + c[3]) * u + c[4]) * u + c[5])
                / ((((d[0] * u + d[1]) * u + d[2]) * u + d[3]) * u + 1);
        }
        final double u = q - 0.5;
        final double r = u * u;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * u
            / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
