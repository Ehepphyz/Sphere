package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::DefiniteIntegral (DefiniteIntegral.hh, .cc): Romberg integration over [a, b], the
 * trapezoid rule extrapolated to zero step (CLOSED) or the extended midpoint rule (OPEN, for integrable
 * singularities at the ends), with Numerical Recipes' polint.
 */
public class DefiniteIntegral extends AbsFunctional {

    /** Whether the integrand is evaluated at the end points. */
    public enum Type {
        CLOSED, OPEN
    }

    /** A quadrature rule refined step after step, keeping its last estimate. */
    private abstract static class QuadratureRule {
        double retVal;
        int nFunctionCalls;

        abstract double integrate(AbsFunction function, double a, double b, int j);

        abstract int stepMultiplier();
    }

    private static final class TrapezoidQuadratureRule extends QuadratureRule {
        @Override
        double integrate(AbsFunction function, double aa, double bb, int n) {
            int it;
            int j;
            if (n == 1) {
                retVal = 0.5 * (bb - aa) * (function.call(aa) + function.call(bb));
                nFunctionCalls += 2;
            } else {
                for (it = 1, j = 1; j < n - 1; j++) it <<= 1;
                final double tnm = Integer.toUnsignedLong(it);
                final double del = (bb - aa) / tnm;
                double x = aa + 0.5 * del;
                double sum;
                for (sum = 0.0, j = 1; Integer.compareUnsigned(j, it) <= 0; j++, x += del) {
                    sum += function.call(x);
                    nFunctionCalls++;
                }
                retVal = 0.5 * (retVal + (bb - aa) * sum / tnm);
            }
            return retVal;
        }

        @Override
        int stepMultiplier() {
            return 2;
        }
    }

    private static final class XtMidpointQuadratureRule extends QuadratureRule {
        @Override
        double integrate(AbsFunction function, double aa, double bb, int n) {
            int it;
            int j;
            if (n == 1) {
                retVal = (bb - aa) * (function.call((aa + bb) / 2.0));
                nFunctionCalls += 1;
            } else {
                for (it = 1, j = 1; j < n - 1; j++) it *= 3;
                final double tnm = Integer.toUnsignedLong(it);
                final double del = (bb - aa) / (3.0 * tnm);
                final double ddel = del + del;
                double x = aa + 0.5 * del;
                double sum = 0;
                for (j = 1; Integer.compareUnsigned(j, it) <= 0; j++) {
                    sum += function.call(x);
                    x += ddel;
                    sum += function.call(x);
                    x += del;
                    nFunctionCalls += 2;
                }
                retVal = (retVal + (bb - aa) * sum / tnm) / 3.0;
            }
            return retVal;
        }

        @Override
        int stepMultiplier() {
            return 3;
        }
    }

    private final double a;
    private final double b;
    private final Type type;
    private int nFunctionCalls;
    private int MAXITER;
    private double EPS;
    private int K;

    public DefiniteIntegral(double a, double b) {
        this(a, b, Type.CLOSED);
    }

    public DefiniteIntegral(double a, double b, Type type) {
        this.a = a;
        this.b = b;
        this.type = type;
        nFunctionCalls = 0;
        MAXITER = type == Type.OPEN ? 20 : 14;
        EPS = 1.0E-6;
        K = 5;
    }

    public DefiniteIntegral(DefiniteIntegral right) {
        a = right.a;
        b = right.b;
        type = right.type;
        nFunctionCalls = right.nFunctionCalls;
        MAXITER = right.MAXITER;
        EPS = right.EPS;
        K = right.K;
    }

    public void setEpsilon(double eps) {
        EPS = eps;
    }

    public void setMaxIter(int maxIter) {
        MAXITER = maxIter;
    }

    public void setMinOrder(int minOrder) {
        K = (minOrder + 1) / 2;
    }

    /** The integrand evaluations of the last integration. */
    public int numFunctionCalls() {
        return nFunctionCalls;
    }

    @Override
    public double apply(AbsFunction function) {
        final QuadratureRule rule = type == Type.OPEN ? new XtMidpointQuadratureRule() : new TrapezoidQuadratureRule();
        final double xMult = rule.stepMultiplier();
        nFunctionCalls = 0;
        final double[] s = new double[MAXITER + 2];
        final double[] h = new double[MAXITER + 2];
        h[1] = 1.0;
        for (int j = 1; j <= MAXITER; j++) {
            s[j] = rule.integrate(function, a, b, j);
            nFunctionCalls = rule.nFunctionCalls;
            if (j >= K) {
                final double[] ssDss = polint(h, s, j - K, 0.0);
                final double ss = ssDss[0];
                final double dss = ssDss[1];
                if (CMath.fabs(dss) <= EPS * CMath.fabs(ss)) return ss;
            }
            s[j + 1] = s[j];
            h[j + 1] = h[j] / xMult / xMult;
        }
        throw new RuntimeException("DefiniteIntegral:  too many steps.  No convergence");
    }

    /** Neville's extrapolation through xArray[off+1..off+K], yArray likewise, to x: {y, deltay}. */
    private double[] polint(double[] xArray, double[] yArray, int off, double x) {
        double dif = CMath.fabs(x - xArray[off + 1]);
        double dift;
        final double[] cc = new double[K + 1];
        final double[] d = new double[K + 1];
        int ns = 1;
        for (int i = 1; i <= K; i++) {
            dift = CMath.fabs(x - xArray[off + i]);
            if (dift < dif) {
                ns = i;
                dif = dift;
            }
            cc[i] = d[i] = yArray[off + i];
        }
        double y = yArray[off + ns--];
        double deltay = 0;
        for (int m = 1; m < K; m++) {
            for (int i = 1; i <= K - m; i++) {
                final double ho = xArray[off + i] - x;
                final double hp = xArray[off + i + m] - x;
                final double w = cc[i + 1] - d[i];
                double den = ho - hp;
                if (den == 0) StdStreams.cerr().print("Error in polynomial extrapolation\n");
                den = w / den;
                d[i] = hp * den;
                cc[i] = ho * den;
            }
            deltay = 2 * ns < (K - m) ? cc[ns + 1] : d[ns--];
            y += deltay;
        }
        return new double[] {y, deltay};
    }
}
