package com.sphere.core.minuit2;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * How noisy the function is: the standard deviation of the noise on its
 * values near a point, by the method of J. J. More and S. M. Wild
 * ("Estimating computational noise", SIAM J. Sci. Comput. 33 (2011)
 * 1292-1314): nine values along a line, their difference table, and the
 * order where the differences have stopped measuring the smooth part of the
 * function and measure only its noise (the estimates of three successive
 * orders agree, the differences change sign). A function computed by a
 * numerical integration, a Monte Carlo, single precision or a long sum is
 * noisy; Migrad's numerical derivatives and its convergence test then have
 * to be told (Minuit's SET EPS, MnUserParameterState.setPrecision), or it
 * ends with an EDM above the maximum it cannot get below.
 */
public final class NoiseEstimator {

    private NoiseEstimator() {
    }

    /** Points along the line. */
    private static final int NF = 9;

    /**
     * The noise sigma (absolute) at a point where the function is f, from the
     * differences of order 'order' with a step h (in errors of the
     * parameters); found false when no step gave a consistent estimate (a
     * function smooth to the last bit, or one not smooth at all).
     */
    public record Estimate(boolean found, double sigma, double f, double h, int order, int calls) {

        /** The noise relative to the function value. */
        public double relative() {
            return sigma / Math.max(Math.abs(f), Double.MIN_NORMAL);
        }

        /**
         * The relative precision to give Minuit for a minimum near the value
         * fmin: the one for which its finite-difference steps (made to change
         * the function by about 16 sqrt(eps) (|F| + Up), F the value where it
         * stands) rise a hundred times above the noise, so that the second
         * derivatives are not drowned in it; never below the double precision
         * Minuit assumes by default.
         */
        public double precisionForMinuit(double up, double fmin) {
            final double base = new MnMachinePrecision().eps();
            if (!found || !(sigma > 0)) return base;
            final double r = 6.25 * sigma / ((Double.isFinite(fmin) ? Math.abs(fmin) : Math.abs(f)) + up);
            return Math.min(1e-2, Math.max(base, r * r));
        }

        /** The precision for a minimum near the value where the noise was measured. */
        public double precisionForMinuit(double up) {
            return precisionForMinuit(up, f);
        }

        public String text() {
            if (!found) return "no noise found above the last bits (the function is smooth to double precision)";
            return String.format(Locale.ROOT, "noise sigma = %.3g on F = %.10g (relative %.2g), from the differences of order %d "
                + "with steps of %.2g errors", sigma, f, relative(), order, h);
        }
    }

    /** The noise at the values of st, along a fixed pseudo-random direction scaled by the errors. */
    public static Estimate estimate(FCNBase fcn, MnUserParameterState st) {
        final int n = st.minuitParameters().size();
        final double[] x0 = st.params();
        final double[] dir = new double[n];
        final SplittableRandom rnd = new SplittableRandom(0x5EEDL);
        double norm = 0;
        for (int i = 0; i < n; i++) {
            final MinuitParameter p = st.parameter(i);
            if (p.isFixed() || p.isConst()) continue;
            if (p.hasLowerLimit() && x0[i] <= p.lowerLimit() || p.hasUpperLimit() && x0[i] >= p.upperLimit()) continue;
            final double u = (rnd.nextBoolean() ? 1 : -1) * (0.5 + 0.5 * rnd.nextDouble());
            final double scale = p.error() > 0 && Double.isFinite(p.error()) ? p.error() : 1e-3 * Math.max(Math.abs(x0[i]), 1);
            dir[i] = u * scale;
            norm += u * u;
        }
        if (norm == 0) return new Estimate(false, Double.NaN, Double.NaN, 0, 0, 0);
        norm = Math.sqrt(norm);
        double hmax = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            dir[i] /= norm;
            if (dir[i] == 0) continue;
            final MinuitParameter p = st.parameter(i);
            // the points go to +-4h along dir: both ends inside the limits
            if (p.hasUpperLimit()) hmax = Math.min(hmax, (p.upperLimit() - x0[i]) / Math.abs(dir[i]) / (NF / 2));
            if (p.hasLowerLimit()) hmax = Math.min(hmax, (x0[i] - p.lowerLimit()) / Math.abs(dir[i]) / (NF / 2));
        }
        final double f0 = fcn.value(x0.clone());
        int calls = 1;
        double h = 1e-4;
        for (int attempt = 0; attempt < 8; attempt++) {
            final double hh = Math.min(h, 0.999 * hmax);
            final double[] fv = new double[NF];
            for (int k = 0; k < NF; k++) {
                if (k == NF / 2) {
                    fv[k] = f0;
                    continue;
                }
                final double[] x = x0.clone();
                for (int i = 0; i < n; i++) x[i] += (k - NF / 2) * hh * dir[i];
                fv[k] = fcn.value(x);
                calls++;
            }
            final double[] r = ecnoise(fv);
            if (r[0] == 1) return new Estimate(true, r[1], f0, hh, (int) r[2], calls);
            if (r[0] == 2) {
                if (hh < h) break; // the limits allow no larger step
                h = hh * 100;
            } else {
                h = hh / 100;
            }
        }
        return new Estimate(false, 0, f0, h, 0, calls);
    }

    /**
     * ECnoise on nf values at equally spaced points: {1, sigma, order} when an
     * estimate is found, {2} when the step is too small (half the first
     * differences are zero), {3} when it is too large.
     */
    static double[] ecnoise(double[] fval0) {
        final int nf = fval0.length;
        final double[] fval = fval0.clone();
        double fmin = Double.POSITIVE_INFINITY;
        double fmax = Double.NEGATIVE_INFINITY;
        for (double v : fval) {
            if (!Double.isFinite(v)) return new double[] {3};
            fmin = Math.min(fmin, v);
            fmax = Math.max(fmax, v);
        }
        if ((fmax - fmin) / Math.max(Math.max(Math.abs(fmax), Math.abs(fmin)), Double.MIN_NORMAL) > 0.1) return new double[] {3};
        final double[] level = new double[nf - 1];
        final boolean[] signChange = new boolean[nf - 1];
        double gamma = 1.0;
        for (int j = 1; j < nf; j++) {
            for (int i = 0; i < nf - j; i++) fval[i] = fval[i + 1] - fval[i];
            if (j == 1) {
                int zeros = 0;
                for (int i = 0; i < nf - 1; i++) if (fval[i] == 0) zeros++;
                if (zeros >= nf / 2) return new double[] {2};
            }
            // gamma_j = (j!)^2 / (2j)!, the variance factor of the j-th difference of white noise
            gamma *= 0.5 * j / (2. * j - 1.);
            double s = 0;
            double emin = Double.POSITIVE_INFINITY;
            double emax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < nf - j; i++) {
                s += fval[i] * fval[i];
                emin = Math.min(emin, fval[i]);
                emax = Math.max(emax, fval[i]);
            }
            level[j - 1] = Math.sqrt(gamma * s / (nf - j));
            signChange[j - 1] = emin * emax < 0;
        }
        for (int k = 0; k < nf - 3; k++) {
            final double emin = Math.min(level[k], Math.min(level[k + 1], level[k + 2]));
            final double emax = Math.max(level[k], Math.max(level[k + 1], level[k + 2]));
            if (emax <= 4 * emin && signChange[k]) return new double[] {1, level[k], k + 1};
        }
        return new double[] {3};
    }
}
