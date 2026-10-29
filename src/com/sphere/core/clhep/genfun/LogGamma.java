package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::LogGamma (LogGamma.hh, LogGamma.cc): ln Gamma(x), Lanczos's series (Numerical Recipes). */
public class LogGamma extends AbsFunction {

    private static final double[] _coefficient = {
        +76.18009172947146,
        -86.50532032941677,
        +24.01409824083091,
        -1.231739572450155,
        0.1208650973866179E-2,
        -0.5395239384953E-5};

    public LogGamma() {
    }

    public LogGamma(LogGamma right) {
    }

    @Override
    public LogGamma copy() {
        return new LogGamma(this);
    }

    @Override
    public double call(double x) {
        double y = x;
        double tmp = x + 5.5;
        double ser = 1.000000000190015;
        final double c = 2.5066282746310005;
        tmp -= (x + 0.5) * CMath.log(tmp);
        for (int j = 0; j < 6; j++) ser += _coefficient[j] / ++y;
        return -tmp + CMath.log(c * ser / x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
