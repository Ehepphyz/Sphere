package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstOverFunction (ConstOverFunction.hh, .cc): c / f. */
public class ConstOverFunction extends AbsFunction {

    private final double _constant;
    private final AbsFunction _arg;

    public ConstOverFunction(double constant, AbsFunction arg) {
        _constant = constant;
        _arg = arg.copy();
    }

    public ConstOverFunction(ConstOverFunction right) {
        _constant = right._constant;
        _arg = right._arg.copy();
    }

    @Override
    public ConstOverFunction copy() {
        return new ConstOverFunction(this);
    }

    @Override
    public int dimensionality() {
        return _arg.dimensionality();
    }

    @Override
    public double call(double x) {
        return _constant / _arg.call(x);
    }

    @Override
    public double call(Argument x) {
        return _constant / _arg.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = AbsFunction.div(-_constant, _arg).div(_arg).times(_arg.partial(index));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
