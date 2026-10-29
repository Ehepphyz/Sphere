package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstMinusFunction (ConstMinusFunction.hh, .cc): c - f. */
public class ConstMinusFunction extends AbsFunction {

    private final double _constant;
    private final AbsFunction _arg;

    public ConstMinusFunction(double constant, AbsFunction arg) {
        _constant = constant;
        _arg = arg.copy();
    }

    public ConstMinusFunction(ConstMinusFunction right) {
        _constant = right._constant;
        _arg = right._arg.copy();
    }

    @Override
    public ConstMinusFunction copy() {
        return new ConstMinusFunction(this);
    }

    @Override
    public int dimensionality() {
        return _arg.dimensionality();
    }

    @Override
    public double call(double x) {
        return _constant - _arg.call(x);
    }

    @Override
    public double call(Argument x) {
        return _constant - _arg.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _arg.partial(index).negate();
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
