package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.StdStreams;

/** CLHEP's Genfun::BivariateGaussian (BivariateGaussian.hh, .cc): the correlated Gaussian of two variables. */
public class BivariateGaussian extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final Parameter _mean0;
    private final Parameter _mean1;
    private final Parameter _sigma0;
    private final Parameter _sigma1;
    private final Parameter _corr01;

    public BivariateGaussian() {
        _mean0 = new Parameter("Mean0", 0.0, -10, 10);
        _mean1 = new Parameter("Mean1", 0.0, -10, 10);
        _sigma0 = new Parameter("Sigma0", 1.0, 0, 10);
        _sigma1 = new Parameter("Sigma1", 1.0, 0, 10);
        _corr01 = new Parameter("Corr01", 0.0, -1.0, 1.0);
    }

    public BivariateGaussian(BivariateGaussian right) {
        _mean0 = new Parameter(right._mean0);
        _mean1 = new Parameter(right._mean1);
        _sigma0 = new Parameter(right._sigma0);
        _sigma1 = new Parameter(right._sigma1);
        _corr01 = new Parameter(right._corr01);
    }

    @Override
    public BivariateGaussian copy() {
        return new BivariateGaussian(this);
    }

    @Override
    public double call(Argument a) {
        final double x = a.get(0);
        final double y = a.get(1);
        final double x0 = _mean0.getValue();
        final double y0 = _mean1.getValue();
        final double dx = x - x0;
        final double dy = y - y0;
        final double sx = _sigma0.getValue();
        final double sy = _sigma1.getValue();
        final double sxs = sx * sx;
        final double sys = sy * sy;
        final double rho = _corr01.getValue();
        final double dt = (1.0 + rho) * (1.0 - rho);
        return (1.0 / (2 * M_PI * sx * sy * Math.sqrt(dt)))
            * CMath.exp(-1.0 / (2.0 * dt) * (dx * dx / sxs + dy * dy / sys - 2.0 * rho * dx * dy / sx / sy));
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning.  bivariate Gaussian called with scalar argument\n");
        return 0;
    }

    @Override
    public int dimensionality() {
        return 2;
    }

    public Parameter mean0() {
        return _mean0;
    }

    public Parameter mean1() {
        return _mean1;
    }

    public Parameter sigma0() {
        return _sigma0;
    }

    public Parameter sigma1() {
        return _sigma1;
    }

    public Parameter corr01() {
        return _corr01;
    }
}
