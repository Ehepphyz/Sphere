package com.sphere.core.clhep.genfun;

import java.util.function.DoubleUnaryOperator;

/** CLHEP's Genfun::F1D (F1D.hh): a function of one variable wrapped as an AbsFunction. */
public class F1D extends AbsFunction {

    private final DoubleUnaryOperator p;

    public F1D(DoubleUnaryOperator fcn) {
        p = fcn;
    }

    public F1D(F1D right) {
        p = right.p;
    }

    @Override
    public F1D copy() {
        return new F1D(this);
    }

    @Override
    public double call(double x) {
        return p.applyAsDouble(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
