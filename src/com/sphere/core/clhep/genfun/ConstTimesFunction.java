package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstTimesFunction (ConstTimesFunction.hh, .cc): c * f. */
public class ConstTimesFunction extends AbsFunction {

    private final double _constant;
    private final AbsFunction _arg;

    public ConstTimesFunction(double constant, AbsFunction arg) {
        _constant = constant;
        _arg = arg.copy();
    }

    public ConstTimesFunction(ConstTimesFunction right) {
        _constant = right._constant;
        _arg = right._arg.copy();
    }

    @Override
    public ConstTimesFunction copy() {
        return new ConstTimesFunction(this);
    }

    @Override
    public int dimensionality() {
        return _arg.dimensionality();
    }

    @Override
    public double call(double x) {
        return _constant * _arg.call(x);
    }

    @Override
    public double call(Argument x) {
        return _constant * _arg.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = AbsFunction.times(_constant, _arg.partial(index));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
