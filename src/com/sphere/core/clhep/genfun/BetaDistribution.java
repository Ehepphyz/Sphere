package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::BetaDistribution (BetaDistribution.hh, .cc): the beta density of alpha and beta. */
public class BetaDistribution extends AbsFunction {

    private final Parameter _alpha;
    private final Parameter _beta;
    private final LogGamma _logGamma = new LogGamma();

    public BetaDistribution() {
        _alpha = new Parameter("a", 1.0, 0.0, 100);
        _beta = new Parameter("beta", 1.0, 0.0, 100);
    }

    public BetaDistribution(BetaDistribution right) {
        _alpha = new Parameter(right._alpha);
        _beta = new Parameter(right._beta);
    }

    @Override
    public BetaDistribution copy() {
        return new BetaDistribution(this);
    }

    @Override
    public double call(double x) {
        final double a = _alpha.getValue();
        final double b = _beta.getValue();
        return CMath.pow(x, a - 1) * CMath.pow((1 - x), b - 1)
            * CMath.exp(_logGamma.call(a + b) - _logGamma.call(a) - _logGamma.call(b));
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter alpha() {
        return _alpha;
    }

    public Parameter beta() {
        return _beta;
    }
}
