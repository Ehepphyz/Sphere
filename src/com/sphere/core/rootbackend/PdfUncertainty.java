package com.sphere.core.rootbackend;

import java.util.Arrays;

/**
 * PDF uncertainties and correlations as LHAPDF 6 computes them
 * (PDFSet::uncertainty and PDFSet::correlation), from the values of every
 * member of a set at one point:
 * <ul>
 * <li>Hessian: asymmetric errors from the eigenvector pairs, errsymm half
 *     the quadrature sum of the pair differences;</li>
 * <li>symmetric Hessian: the quadrature sum of the deviations;</li>
 * <li>replicas: the mean and the standard deviation (N-1), or, as LHAPDF's
 *     "alternative", the median and the central interval of the requested
 *     confidence level.</li>
 * </ul>
 * Errors are rescaled from the set's confidence level to the requested one
 * with the quantiles of the normal distribution (except the replica
 * percentiles, which are taken at the requested level directly).
 */
public final class PdfUncertainty {

    private PdfUncertainty() {
    }

    /** LHAPDF's PDFUncertainty. */
    public record Result(double central, double errplus, double errminus, double errsymm, double scale) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%.8g  +%.6g  -%.6g  (symm %.6g)", central, errplus, errminus, errsymm);
        }
    }

    public static Result uncertainty(double[] values, RootPdfSet.Errors type, double setCL, double reqCL,
                                     boolean alternative) {
        final int n = values.length;
        double central = values[0];
        double errplus = 0;
        double errminus = 0;
        double errsymm = 0;
        if (n < 2 || type == RootPdfSet.Errors.NONE) {
            return new Result(central, 0, 0, 0, 1);
        }
        switch (type) {
            case REPLICAS -> {
                final int nrep = n - 1;
                if (alternative) {
                    final double[] r = Arrays.copyOfRange(values, 1, n);
                    Arrays.sort(r);
                    central = quantile(r, 0.5);
                    final double tail = (1 - reqCL / 100.0) / 2;
                    final double low = quantile(r, tail);
                    final double high = quantile(r, 1 - tail);
                    errplus = high - central;
                    errminus = central - low;
                    errsymm = 0.5 * (errplus + errminus);
                    return new Result(central, errplus, errminus, errsymm, 1);
                }
                double mean = 0;
                double mean2 = 0;
                for (int m = 1; m < n; m++) {
                    mean += values[m];
                    mean2 += values[m] * values[m];
                }
                mean /= nrep;
                mean2 /= nrep;
                central = mean;
                errsymm = Math.sqrt(Math.max(0, nrep / (nrep - 1.0) * (mean2 - mean * mean)));
                errplus = errminus = errsymm;
            }
            case SYMMETRIC_HESSIAN -> {
                double s = 0;
                for (int m = 1; m < n; m++) s += (values[m] - central) * (values[m] - central);
                errsymm = Math.sqrt(s);
                errplus = errminus = errsymm;
            }
            case HESSIAN -> {
                double up = 0;
                double down = 0;
                double symm = 0;
                for (int m = 1; m + 1 < n; m += 2) {
                    final double a = values[m] - central;
                    final double b = values[m + 1] - central;
                    final double rise = Math.max(Math.max(a, b), 0.0);
                    final double fall = Math.max(Math.max(-a, -b), 0.0);
                    up += rise * rise;
                    down += fall * fall;
                    symm += (values[m] - values[m + 1]) * (values[m] - values[m + 1]);
                }
                errplus = Math.sqrt(up);
                errminus = Math.sqrt(down);
                errsymm = 0.5 * Math.sqrt(symm);
            }
            default -> {
            }
        }
        final double scale = normalQuantile(0.5 + reqCL / 200.0) / normalQuantile(0.5 + setCL / 200.0);
        return new Result(central, errplus * scale, errminus * scale, errsymm * scale, scale);
    }

    /** The correlation of two quantities over the members, PDFSet::correlation. */
    public static double correlation(double[] a, double[] b, RootPdfSet.Errors type) {
        final int n = a.length;
        if (n < 2 || type == RootPdfSet.Errors.NONE) return Double.NaN;
        final Result ua = uncertainty(a, type, 68.268949, 68.268949, false);
        final Result ub = uncertainty(b, type, 68.268949, 68.268949, false);
        double cor = 0;
        switch (type) {
            case REPLICAS -> {
                final int nrep = n - 1;
                double sab = 0;
                for (int m = 1; m < n; m++) sab += a[m] * b[m];
                cor = (sab / nrep - ua.central() * ub.central()) * nrep / (nrep - 1.0);
            }
            case SYMMETRIC_HESSIAN -> {
                for (int m = 1; m < n; m++) cor += (a[m] - a[0]) * (b[m] - b[0]);
            }
            case HESSIAN -> {
                for (int m = 1; m + 1 < n; m += 2) cor += (a[m] - a[m + 1]) * (b[m] - b[m + 1]);
                cor /= 4.0;
            }
            default -> {
            }
        }
        return cor / (ua.errsymm() * ub.errsymm());
    }

    private static double quantile(double[] sorted, double p) {
        final double pos = p * (sorted.length - 1);
        final int lo = (int) Math.floor(pos);
        final int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /**
     * The quantile of the standard normal distribution: Acklam's rational
     * approximation refined by one Halley step on an erfc accurate to double
     * precision, so about 1e-15 relative.
     */
    public static double normalQuantile(double p) {
        if (!(p > 0 && p < 1)) return p == 0 ? Double.NEGATIVE_INFINITY : p == 1 ? Double.POSITIVE_INFINITY : Double.NaN;
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00};
        final double plow = 0.02425;
        double x;
        if (p < plow) {
            final double q = Math.sqrt(-2 * Math.log(p));
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= 1 - plow) {
            final double q = p - 0.5;
            final double r = q * q;
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            final double q = Math.sqrt(-2 * Math.log(1 - p));
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        final double e = 0.5 * erfc(-x / Math.sqrt(2)) - p;
        final double u = e * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
        return x - u / (1 + x * u / 2);
    }

    /** The complementary error function (W. J. Cody's rational approximations). */
    public static double erfc(double x) {
        final double ax = Math.abs(x);
        double r;
        if (ax < 0.5) {
            final double t = x * x;
            final double top = (((0.185777706184603153 * t + 3.16112374387056560) * t + 113.864154151050156) * t
                + 377.485237685302021) * t + 3209.37758913846947;
            final double bot = (((t + 23.6012909523441209) * t + 244.024637934444173) * t + 1282.61652607737228) * t
                + 2844.23683343917062;
            return 1 - x * top / bot;
        } else if (ax < 4) {
            final double[] c = {5.64188496988670089e-1, 8.88314979438837594, 66.1191906371416295,
                298.635138197400131, 881.952221241769090, 1712.04761263407058, 2051.07837782607147,
                1230.33935479799725, 2.15311535474403846e-8};
            final double[] d = {15.7449261107098347, 117.693950891312499, 537.181101862009858,
                1621.38957456669019, 3290.79923573345963, 4362.61909014324716, 3439.36767414372164,
                1230.33935480374942};
            double num = c[8] * ax;
            double den = ax;
            for (int i = 0; i < 7; i++) {
                num = (num + c[i]) * ax;
                den = (den + d[i]) * ax;
            }
            final double ysq = Math.floor(ax * 16) / 16;
            final double del = (ax - ysq) * (ax + ysq);
            r = Math.exp(-ysq * ysq) * Math.exp(-del) * (num + c[7]) / (den + d[7]);
        } else {
            final double z = 1 / (ax * ax);
            final double top = ((((1.63153871373020978e-2 * z + 3.05326634961232344e-1) * z
                + 3.60344899949804439e-1) * z + 1.25781726111229246e-1) * z + 1.60837851487422766e-2) * z
                + 6.58749161529837803e-4;
            final double bot = ((((z + 2.56852019228982242) * z + 1.87295284992346725) * z
                + 5.27905102951428412e-1) * z + 6.05183413124413191e-2) * z + 2.33520497626869185e-3;
            r = Math.exp(-ax * ax) / ax * (0.564189583547756287 - z * top / bot);
        }
        return x < 0 ? 2 - r : r;
    }
}
