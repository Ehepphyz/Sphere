package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/**
 * The error functions GenericFunctions carries privately in several classes: CERNLIB's WWERF (nwwerf, the
 * Faddeeva function of AnalyticConvolution and VoigtProfile) and the Numerical Recipes erfc (of
 * AnalyticConvolution and PuncturedSmearedExp), the complex arithmetic that of {@link Complex}.
 */
final class ErrorFunctions {

    private ErrorFunctions() {
    }

    /** x to the n by repeated multiplication (VoigtProfile's Pow, AnalyticConvolution's pow). */
    private static double pow(double x, int n) {
        double val = 1;
        for (int i = 0; i < n; i++) val = val * x;
        return val;
    }

    /** CERNLIB's WWERF: w(z) = exp(-z²) erfc(-iz). */
    static Complex nwwerf(Complex z) {
        Complex zh;
        final Complex[] r = new Complex[38];
        Complex s;
        Complex t;
        Complex v;

        final double z1 = 1;
        final double hf = z1 / 2;
        final double z10 = 10;
        final double c1 = 74 / z10;
        final double c2 = 83 / z10;
        final double c3 = z10 / 32;
        final double c4 = 16 / z10;
        final double c = 1.12837916709551257;
        final double p = pow(2.0 * c4, 33);

        final double x = z.re();
        final double y = z.im();
        final double xa = (x >= 0) ? x : -x;
        final double ya = (y >= 0) ? y : -y;
        if (ya < c1 && xa < c2) {
            zh = new Complex(ya + c4, xa);
            r[37] = new Complex(0, 0);
            for (int n = 36; n > 0; n--) {
                t = zh.plus(r[n + 1].conj().times((double) n));
                r[n] = t.times(hf).div(t.norm());
            }
            double xl = p;
            s = new Complex(0, 0);
            for (int k = 33; k > 0; k--) {
                xl = c3 * xl;
                s = r[k].times(s.plus(xl));
            }
            v = s.times(c);
        } else {
            zh = new Complex(ya, xa);
            r[1] = new Complex(0, 0);
            for (int n = 9; n > 0; n--) {
                t = zh.plus(r[1].conj().times((double) n));
                r[1] = t.times(hf).div(t.norm());
            }
            v = r[1].times(c);
        }
        if (ya == 0) v = new Complex(CMath.exp(-xa * xa), v.im());
        if (y < 0) {
            final Complex e = new Complex(-xa, -ya).times(new Complex(xa, ya));
            v = e.exp().times(2.0).minus(v);
            if (x > 0) v = v.conj();
        } else {
            if (x < 0) v = v.conj();
        }
        return v;
    }

    /** The Numerical Recipes erfc (Chebyshev fit, fractional error below 1.2e-7). */
    static double erfc(double x) {
        final double t;
        final double z;
        double ans;
        z = (x < 0) ? -x : x;
        t = 1.0 / (1.0 + .5 * z);
        ans = t * CMath.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
            + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
            + t * (-0.82215223 + t * 0.17087277)))))))));
        if (x < 0) ans = 2.0 - ans;
        return ans;
    }
}
