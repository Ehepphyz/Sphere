package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/**
 * CLHEP's Genfun::IncompleteGamma (IncompleteGamma.hh, .cc): the regularized lower incomplete gamma
 * function P(a, x), by its series below a+1 and its continued fraction above (Numerical Recipes); 0
 * when either does not converge in 100 terms.
 */
public class IncompleteGamma extends AbsFunction {

    private static final int ITMAX = 100;
    private static final double EPS = 3.0E-7;
    private static final double FPMIN = 1.0e-30;

    private final LogGamma _logGamma = new LogGamma();
    private final Parameter _a;

    public IncompleteGamma() {
        _a = new Parameter("a", 1.0, 0, 10);
    }

    public IncompleteGamma(IncompleteGamma right) {
        _a = new Parameter(right._a);
    }

    @Override
    public IncompleteGamma copy() {
        return new IncompleteGamma(this);
    }

    public Parameter a() {
        return _a;
    }

    @Override
    public double call(double x) {
        if (x < (_a.getValue() + 1.0)) return _gamser(_a.getValue(), x, _logGamma.call(_a.getValue()));
        else return 1.0 - _gammcf(_a.getValue(), x, _logGamma.call(_a.getValue()));
    }

    @Override
    public double call(Argument arg) {
        return call(arg.get(0));
    }

    private double _gamser(double xa, double x, double logGamma) {
        double n;
        double ap;
        double del;
        double sum;
        ap = xa;
        del = sum = 1.0 / xa;
        for (n = 1; n < ITMAX; n++) {
            ++ap;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * EPS) return sum * CMath.exp(-x + xa * CMath.log(x) - logGamma);
        }
        return 0;
    }

    private double _gammcf(double xa, double x, double logGamma) {
        double an;
        double b;
        double c;
        double d;
        double del;
        double h;
        int i;
        b = x + 1.0 - xa;
        c = 1.0 / FPMIN;
        d = 1.0 / b;
        h = d;
        for (i = 1; i < ITMAX; i++) {
            an = -i * (i - xa);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < FPMIN) d = FPMIN;
            c = b + an / c;
            if (Math.abs(c) < FPMIN) c = FPMIN;
            d = 1.0 / d;
            del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < EPS) return CMath.exp(-x + xa * CMath.log(x) - logGamma) * h;
        }
        return 0;
    }
}
