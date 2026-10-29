package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::FunctionNegation (FunctionNegation.hh, .cc): -f. */
public class FunctionNegation extends AbsFunction {

    private final AbsFunction _arg1;

    public FunctionNegation(AbsFunction arg1) {
        _arg1 = arg1.copy();
    }

    public FunctionNegation(FunctionNegation right) {
        _arg1 = right._arg1.copy();
    }

    @Override
    public FunctionNegation copy() {
        return new FunctionNegation(this);
    }

    @Override
    public int dimensionality() {
        return _arg1.dimensionality();
    }

    @Override
    public double call(double x) {
        return -(_arg1.call(x));
    }

    @Override
    public double call(Argument x) {
        return -(_arg1.call(x));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _arg1.partial(index).negate();
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
