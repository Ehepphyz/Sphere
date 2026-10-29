package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::FunctionNoop (GenericFunctions/FunctionNoop.hh, FunctionNoop.cc), which CLHEP calls
 * Derivative: a copy of a function, the same values; what partial(), prime() and derivative() return.
 */
public class FunctionNoop extends AbsFunction {

    private final AbsFunction _arg1;

    public FunctionNoop(AbsFunction arg1) {
        _arg1 = arg1.copy();
    }

    public FunctionNoop(FunctionNoop right) {
        _arg1 = right._arg1.copy();
    }

    @Override
    public FunctionNoop copy() {
        return new FunctionNoop(this);
    }

    @Override
    public int dimensionality() {
        return _arg1.dimensionality();
    }

    @Override
    public double call(double x) {
        return +(_arg1.call(x));
    }

    @Override
    public double call(Argument x) {
        return +(_arg1.call(x));
    }

    @Override
    public FunctionNoop partial(int index) {
        return _arg1.partial(index);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
