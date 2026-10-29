package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::FixedConstant (FixedConstant.hh, .cc): a constant function. */
public class FixedConstant extends AbsFunction {

    private final double _value;

    public FixedConstant(double value) {
        _value = value;
    }

    public FixedConstant(FixedConstant right) {
        _value = right._value;
    }

    @Override
    public FixedConstant copy() {
        return new FixedConstant(this);
    }

    @Override
    public double call(double x) {
        return _value;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final FixedConstant fPrime = new FixedConstant(0.0);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
