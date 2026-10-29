package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::TrivariateGaussian (TrivariateGaussian.hh, .cc): the correlated Gaussian of three
 * variables, with CLHEP's determinant (its rho1*rho2*rho3 term counted once).
 */
public class TrivariateGaussian extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final Parameter _mean0;
    private final Parameter _mean1;
    private final Parameter _mean2;
    private final Parameter _sigma0;
    private final Parameter _sigma1;
    private final Parameter _sigma2;
    private final Parameter _corr01;
    private final Parameter _corr02;
    private final Parameter _corr12;

    public TrivariateGaussian() {
        _mean0 = new Parameter("Mean0", 0.0, -10, 10);
        _mean1 = new Parameter("Mean1", 0.0, -10, 10);
        _mean2 = new Parameter("Mean2", 0.0, -10, 10);
        _sigma0 = new Parameter("Sigma0", 1.0, 0, 10);
        _sigma1 = new Parameter("Sigma1", 1.0, 0, 10);
        _sigma2 = new Parameter("Sigma2", 1.0, 0, 10);
        _corr01 = new Parameter("Corr01", 0.0, -1.0, 1.0);
        _corr02 = new Parameter("Corr02", 0.0, -1.0, 1.0);
        _corr12 = new Parameter("Corr12", 0.0, -1.0, 1.0);
    }

    public TrivariateGaussian(TrivariateGaussian right) {
        _mean0 = new Parameter(right._mean0);
        _mean1 = new Parameter(right._mean1);
        _mean2 = new Parameter(right._mean2);
        _sigma0 = new Parameter(right._sigma0);
        _sigma1 = new Parameter(right._sigma1);
        _sigma2 = new Parameter(right._sigma2);
        _corr01 = new Parameter(right._corr01);
        _corr02 = new Parameter(right._corr02);
        _corr12 = new Parameter(right._corr12);
    }

    @Override
    public TrivariateGaussian copy() {
        return new TrivariateGaussian(this);
    }

    @Override
    public double call(Argument a) {
        final double x = a.get(0);
        final double y = a.get(1);
        final double z = a.get(2);
        final double x0 = _mean0.getValue();
        final double y0 = _mean1.getValue();
        final double z0 = _mean2.getValue();
        final double dx = x - x0;
        final double dy = y - y0;
        final double dz = z - z0;
        final double sx = _sigma0.getValue();
        final double sy = _sigma1.getValue();
        final double sz = _sigma2.getValue();
        final double sxs = sx * sx;
        final double sys = sy * sy;
        final double szs = sz * sz;
        final double rho1 = _corr01.getValue();
        final double rho2 = _corr12.getValue();
        final double rho3 = _corr02.getValue();
        final double dt = (1.0 + rho1 * rho2 * rho3 - rho1 * rho1 - rho2 * rho2 - rho3 * rho3);
        final double tmp1 = 1.0 / ((2 * M_PI) * Math.sqrt(2 * M_PI) * sx * sy * sz * Math.sqrt(dt));
        final double tmp2 = CMath.exp(-0.5 / dt * (dx * dx * (1.0 - rho2 * rho2) / sxs + dy * dy * (1.0 - rho3 * rho3) / sys
            + dz * dz * (1.0 - rho1 * rho1) / szs + 2.0 * dx * dy * (rho2 * rho3 - rho1) / sx / sy
            + 2.0 * dy * dz * (rho1 * rho3 - rho2) / sy / sz + 2.0 * dx * dz * (rho1 * rho2 - rho3) / sx / sz));
        return tmp1 * tmp2;
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning.  trivariate Gaussian called with scalar argument\n");
        return 0;
    }

    @Override
    public int dimensionality() {
        return 3;
    }

    public Parameter mean0() {
        return _mean0;
    }

    public Parameter mean1() {
        return _mean1;
    }

    public Parameter mean2() {
        return _mean2;
    }

    public Parameter sigma0() {
        return _sigma0;
    }

    public Parameter sigma1() {
        return _sigma1;
    }

    public Parameter sigma2() {
        return _sigma2;
    }

    public Parameter corr01() {
        return _corr01;
    }

    public Parameter corr02() {
        return _corr02;
    }

    public Parameter corr12() {
        return _corr12;
    }
}
