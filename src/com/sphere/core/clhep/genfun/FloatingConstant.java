package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::FloatingConstant (FloatingConstant.hh, .cc): a function whose value is a parameter's. */
public class FloatingConstant extends AbsFunction {

    private final AbsParameter _value;

    public FloatingConstant(AbsParameter p) {
        _value = p.copy();
        if (_value.parameter() != null && p.parameter() != null) _value.parameter().connectFrom(p);
    }

    public FloatingConstant(FloatingConstant right) {
        _value = right._value.copy();
    }

    @Override
    public FloatingConstant copy() {
        return new FloatingConstant(this);
    }

    public AbsParameter value() {
        return _value;
    }

    @Override
    public double call(double x) {
        return _value.getValue();
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
