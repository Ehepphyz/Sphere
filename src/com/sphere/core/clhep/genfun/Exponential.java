package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Exponential (Exponential.hh, .cc): exp(-x/tau)/tau for x &gt;= 0, 0 below. */
public class Exponential extends AbsFunction {

    private final Parameter _decayConstant;

    public Exponential() {
        _decayConstant = new Parameter("Decay Constant", 1.0, 0, 10);
    }

    public Exponential(Exponential right) {
        _decayConstant = new Parameter(right._decayConstant);
    }

    @Override
    public Exponential copy() {
        return new Exponential(this);
    }

    @Override
    public double call(double x) {
        if (x < 0) return 0;
        return CMath.exp(-x / _decayConstant.getValue()) / _decayConstant.getValue();
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter decayConstant() {
        return _decayConstant;
    }

    /** -tau times this (as CLHEP writes it). */
    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _decayConstant.negate().times(this);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
