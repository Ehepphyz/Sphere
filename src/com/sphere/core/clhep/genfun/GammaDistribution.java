package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::GammaDistribution (GammaDistribution.hh, .cc): the gamma density of shape alpha, scale beta. */
public class GammaDistribution extends AbsFunction {

    private final Parameter _alpha;
    private final Parameter _beta;
    private final LogGamma _logGamma = new LogGamma();

    public GammaDistribution() {
        _alpha = new Parameter("a", 2.0, 1.0, 100);
        _beta = new Parameter("beta", 0.0, 0, 100);
    }

    public GammaDistribution(GammaDistribution right) {
        _alpha = new Parameter(right._alpha);
        _beta = new Parameter(right._beta);
    }

    @Override
    public GammaDistribution copy() {
        return new GammaDistribution(this);
    }

    @Override
    public double call(double x) {
        return CMath.pow(x, _alpha.getValue() - 1) * CMath.exp(-x / _beta.getValue())
            / CMath.pow(_beta.getValue(), (_alpha.getValue())) / CMath.exp(_logGamma.call(_alpha.getValue()));
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
