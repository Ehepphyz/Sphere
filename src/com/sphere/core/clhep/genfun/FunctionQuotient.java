package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::FunctionQuotient (FunctionQuotient.hh, FunctionQuotient.cc): f / g. */
public class FunctionQuotient extends AbsFunction {

    private final AbsFunction _arg1;
    private final AbsFunction _arg2;

    public FunctionQuotient(AbsFunction arg1, AbsFunction arg2) {
        _arg1 = arg1.copy();
        _arg2 = arg2.copy();
        if (arg1.dimensionality() != arg2.dimensionality()) dimensionWarning("function quotient");
    }

    public FunctionQuotient(FunctionQuotient right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public FunctionQuotient copy() {
        return new FunctionQuotient(this);
    }

    @Override
    public int dimensionality() {
        return _arg1.dimensionality();
    }

    @Override
    public double call(double x) {
        return _arg1.call(x) / _arg2.call(x);
    }

    @Override
    public double call(Argument x) {
        return _arg1.call(x) / _arg2.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _arg1.partial(index).times(_arg2).minus(_arg1.times(_arg2.partial(index))).div(_arg2).div(_arg2);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
