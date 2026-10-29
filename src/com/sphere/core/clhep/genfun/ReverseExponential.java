package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::ReverseExponential (ReverseExponential.hh, .cc): exp(x/tau)/tau for x &lt;= 0, 0 above. */
public class ReverseExponential extends AbsFunction {

    private final Parameter _decayConstant;

    public ReverseExponential() {
        _decayConstant = new Parameter("Decay Constant", 1.0, 0, 10);
    }

    public ReverseExponential(ReverseExponential right) {
        _decayConstant = new Parameter(right._decayConstant);
    }

    @Override
    public ReverseExponential copy() {
        return new ReverseExponential(this);
    }

    @Override
    public double call(double x) {
        if (x > 0) return 0;
        return CMath.exp(x / _decayConstant.getValue()) / _decayConstant.getValue();
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter decayConstant() {
        return _decayConstant;
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _decayConstant.times(this);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
