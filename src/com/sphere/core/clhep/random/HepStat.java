package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.units.SystemOfUnits;

/**
 * CLHEP's HepStat (Random/Stat.h; flatToGaussian.cc, erfQ.cc, gammln.cc): the statistical functions the
 * distributions use — the inverse of the normal CDF by cubic interpolation in tables, the error function
 * and its quick approximation, the logarithm of the gamma function.
 */
public final class HepStat {

    private HepStat() {
    }

    // flatToGaussian's tables: five ranges of the flat number, each a size, a step and an offset
    private static final int Table0size = 200;
    private static final int Table1size = 250;
    private static final int Table2size = 200;
    private static final int Table3size = 250;
    private static final int Table4size = 1000;
    private static final double Table0step = 2.0E-13;
    private static final double Table1step = 4.0E-11;
    private static final double Table2step = 1.0E-8;
    private static final double Table3step = 2.0E-6;
    private static final double Table4step = 5.0E-4;
    private static final int Table0offset = 0;
    private static final int Table1offset = 2 * Table0size;
    private static final int Table2offset = 2 * (Table0size + Table1size);
    private static final int Table3offset = 2 * (Table0size + Table1size + Table2size);
    private static final int Table4offset = 2 * (Table0size + Table1size + Table2size + Table3size);
    private static final int[] Tsizes = {Table0size, Table1size, Table2size, Table3size, Table4size};
    private static final double[] Tsteps = {Table0step, Table1step, Table2step, Table3step, Table4step};
    private static final int[] Toffsets = {Table0offset, Table1offset, Table2offset, Table3offset, Table4offset};

    /** The normal deviate whose CDF is r: cubic (Hermite) interpolation of the tabulated values and derivatives. */
    public static double flatToGaussian(double r) {
        double sign = +1.0;
        if (r > .5) {
            r = 1 - r;
            sign = -1.0;
        } else if (r == .5) {
            return 0.0;
        }
        int tptr = 0;
        double dx = 0;
        double h = 0;
        int index;
        if (r >= Table4step) {
            index = (int) ((Table4size << 1) * r);
            if (index <= 0) index = 1;
            if (index >= Table4size) index = Table4size - 1;
            dx = (Table4size << 1) * r - index;
            h = Table4step;
            index = (index << 1) + (Table4offset - 2);
            tptr = index;
        } else if (r < Tsteps[0]) {
            return sign * transformSmall(r);
        } else {
            for (int tableN = 3; tableN >= 0; tableN--) {
                if (r < Tsteps[tableN]) continue;
                final double step = Tsteps[tableN];
                index = (int) (r / step);
                if (index == 0) index = 1;
                if (index >= Tsizes[tableN]) index = Tsizes[tableN] - 1;
                dx = r / step - index;
                h = step;
                index = (index << 1) + Toffsets[tableN] - 2;
                tptr = index;
                break;
            }
        }
        final double[] t = GaussTables.T;
        final double y0 = t[tptr];
        final double d0 = t[tptr + 1];
        final double y1 = t[tptr + 2];
        final double d1 = t[tptr + 3];
        final double x2 = dx * dx;
        final double oneMinusX = 1 - dx;
        final double oneMinusX2 = oneMinusX * oneMinusX;
        final double f0 = (2. * dx + 1.) * oneMinusX2;
        final double f1 = (3. - 2. * dx) * x2;
        final double g0 = h * dx * oneMinusX2;
        final double g1 = -h * oneMinusX * x2;
        final double answer = f0 * y0 + f1 * y1 + g0 * d0 + g1 * d1;
        return sign * answer;
    }

    /** Below the tables: the asymptotic series of the tail, solved by iteration. */
    static double transformSmall(double r) {
        final double eps = 1.0e-7;
        double guess = 7.5;
        double v = 0;
        for (int i = 1; i < 50; i++) {
            final double vn2 = 1.0 / (guess * guess);
            double s1 = -13 * 11 * 9 * 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2 * vn2 * vn2 * vn2;
            s1 += 11 * 9 * 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2 * vn2 * vn2;
            s1 += -9 * 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2 * vn2;
            s1 += 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2;
            s1 += -5 * 3 * vn2 * vn2 * vn2;
            s1 += 3 * vn2 * vn2 - vn2 + 1.0;
            v = Math.sqrt(2.0 * CMath.log(s1 / (r * guess * Math.sqrt(SystemOfUnits.twopi))));
            if (Math.abs(v - guess) < eps) break;
            guess = v;
        }
        return -v;
    }

    public static double inverseErf(double t) {
        return Math.sqrt(0.5) * flatToGaussian(.5 * (t + 1));
    }

    /** The error function: erfQ refined by one Newton step on inverseErf. */
    public static double erf(double x) {
        final double t0 = erfQ(x);
        final double deriv = CMath.exp(-x * x) * (2.0 / Math.sqrt(SystemOfUnits.pi));
        return t0 - (inverseErf(t0) - x) * deriv;
    }

    /** A quick error function (Numerical Recipes' erfc to about 1.2e-7). */
    public static double erfQ(double x) {
        final double z = Math.abs(x);
        final double t = 1.0 / (1.0 + .5 * z);
        double erfc = t * CMath.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
            + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
            + t * (-0.82215223 + t * 0.17087277)))))))));
        if (x < 0) erfc = 2.0 - erfc;
        return 1 - erfc;
    }

    private static final double[] cof = {76.18009172947146, -86.50532032941677, 24.01409824083091,
        -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};

    /** ln Gamma(xx) (Lanczos, as Numerical Recipes). */
    public static double gammln(double xx) {
        double x = xx - 1.0;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * CMath.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j <= 5; j++) {
            x += 1.0;
            ser += cof[j] / x;
        }
        return -tmp + CMath.log(2.5066282746310005 * ser);
    }
}
