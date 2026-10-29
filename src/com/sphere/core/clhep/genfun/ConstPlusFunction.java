package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstPlusFunction (ConstPlusFunction.hh, .cc): c + f. */
public class ConstPlusFunction extends AbsFunction {

    private final double _constant;
    private final AbsFunction _arg;

    public ConstPlusFunction(double constant, AbsFunction arg) {
        _constant = constant;
        _arg = arg.copy();
    }

    public ConstPlusFunction(ConstPlusFunction right) {
        _constant = right._constant;
        _arg = right._arg.copy();
    }

    @Override
    public ConstPlusFunction copy() {
        return new ConstPlusFunction(this);
    }

    @Override
    public int dimensionality() {
        return _arg.dimensionality();
    }

    @Override
    public double call(double x) {
        return _constant + _arg.call(x);
    }

    @Override
    public double call(Argument x) {
        return _constant + _arg.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        return _arg.partial(index);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
