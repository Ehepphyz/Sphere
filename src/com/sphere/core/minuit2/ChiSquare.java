package com.sphere.core.minuit2;

/**
 * The chi-square distribution, for the error definitions of confidence
 * levels: Minos at 95% is Up = chi2_1 quantile(0.95) = 3.84 times the
 * function's Up, a contour of two parameters at 68% is chi2_2
 * quantile(0.68) = 2.28 times. A level given as a number of standard
 * deviations n >= 1 means the probability inside +-n sigma of a Gaussian
 * (iminuit's convention): 1 is 68.27%, 2 is 95.45%.
 */
public final class ChiSquare {

    private ChiSquare() {
    }

    private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    /** ln Gamma(x), x > 0 (Lanczos, g = 7). */
    public static double lnGamma(double x) {
        if (x < 0.5) return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        final double z = x - 1;
        double a = LANCZOS[0];
        final double t = z + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) a += LANCZOS[i] / (z + i);
        return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** {P(a, x), Q(a, x)}: the regularized incomplete gamma functions, each computed where it is accurate. */
    static double[] gammaPQ(double a, double x) {
        if (x <= 0) return new double[] {0, 1};
        if (Double.isInfinite(x)) return new double[] {1, 0};
        final double front = Math.exp(-x + a * Math.log(x) - lnGamma(a));
        if (x < a + 1) {
            double ap = a;
            double del = 1 / a;
            double sum = del;
            for (int n = 0; n < 1000; n++) {
                ap += 1;
                del *= x / ap;
                sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-17) break;
            }
            final double p = sum * front;
            return new double[] {p, 1 - p};
        }
        // continued fraction for Q, modified Lentz
        final double tiny = 1e-300;
        double b = x + 1 - a;
        double c = 1 / tiny;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < 1000; i++) {
            final double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < tiny) d = tiny;
            c = b + an / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            final double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-17) break;
        }
        final double q = front * h;
        return new double[] {1 - q, q};
    }

    /** P(chi2_k <= x). */
    public static double cdf(double x, int k) {
        return gammaPQ(0.5 * k, 0.5 * x)[0];
    }

    /** The density of chi2_k at x. */
    public static double pdf(double x, int k) {
        if (x <= 0) return k == 2 ? 0.5 : k < 2 ? Double.POSITIVE_INFINITY : 0;
        return Math.exp((0.5 * k - 1) * Math.log(x) - 0.5 * x - 0.5 * k * Math.log(2) - lnGamma(0.5 * k));
    }

    /** The x with P(chi2_k <= x) = p: exact for k = 2, Newton kept inside a bracket otherwise. */
    public static double quantile(double p, int k) {
        if (!(p > 0)) return 0;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        if (k == 2) return -2 * Math.log1p(-p);
        double lo = 0;
        double hi = Math.max(1, k);
        while (cdf(hi, k) < p) {
            lo = hi;
            hi *= 2;
        }
        double x = 0.5 * (lo + hi);
        for (int it = 0; it < 200; it++) {
            final double[] pq = gammaPQ(0.5 * k, 0.5 * x);
            // the difference from the side where it is accurate: P near 0, Q near 1
            final double diff = p < 0.5 ? pq[0] - p : (1 - p) - pq[1];
            if (diff == 0) return x;
            if (diff < 0) lo = x;
            else hi = x;
            double next = x - diff / pdf(x, k);
            if (!(next > lo && next < hi)) next = 0.5 * (lo + hi);
            if (Math.abs(next - x) <= 1e-15 * x || hi - lo <= 1e-15 * hi) return next;
            x = next;
        }
        return x;
    }

    /** The probability inside +-n standard deviations of a Gaussian. */
    public static double sigmaToProbability(double n) {
        return cdf(n * n, 1);
    }

    /**
     * The factor of Up for a confidence level cl in ndim parameters: cl in
     * (0, 1) a probability, cl >= 1 a number of standard deviations; 0 (or
     * less) the standard error, factor 1 in one dimension.
     */
    public static double upFactor(double cl, int ndim) {
        if (!(cl > 0)) cl = 1;
        if (cl >= 1) {
            if (ndim == 1) return cl * cl;
            return quantile(sigmaToProbability(cl), ndim);
        }
        return quantile(cl, ndim);
    }

    /** The level in words: "68.27% (1 sigma)", "95%". */
    public static String describe(double cl) {
        if (!(cl > 0)) cl = 1;
        if (cl >= 1) {
            return String.format(java.util.Locale.ROOT, "%.4g%% (%s sigma)", 100 * sigmaToProbability(cl),
                cl == Math.rint(cl) ? String.valueOf((int) cl) : String.valueOf(cl));
        }
        return String.format(java.util.Locale.ROOT, "%.4g%%", 100 * cl);
    }
}
