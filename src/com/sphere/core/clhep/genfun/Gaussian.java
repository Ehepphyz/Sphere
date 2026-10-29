package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Gaussian (Gaussian.hh, Gaussian.cc): the normal density of the mean and sigma. */
public class Gaussian extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final Parameter _mean;
    private final Parameter _sigma;

    public Gaussian() {
        _mean = new Parameter("Mean", 0.0, -10, 10);
        _sigma = new Parameter("Sigma", 1.0, 0, 10);
    }

    public Gaussian(Gaussian right) {
        _mean = new Parameter(right._mean);
        _sigma = new Parameter(right._sigma);
    }

    @Override
    public Gaussian copy() {
        return new Gaussian(this);
    }

    @Override
    public double call(double x) {
        final double s = _sigma.getValue();
        final double x0 = _mean.getValue();
        return (1.0 / (Math.sqrt(2 * M_PI) * s)) * CMath.exp(-(x - x0) * (x - x0) / (2.0 * s * s));
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter mean() {
        return _mean;
    }

    public Parameter sigma() {
        return _sigma;
    }

    /** This times (mean - x) / sigma / sigma. */
    @Override
    public FunctionNoop partial(int index) {
        final Variable x = new Variable();
        final AbsFunction fPrime = this.times(_mean.minus(x)).div(_sigma).div(_sigma);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
