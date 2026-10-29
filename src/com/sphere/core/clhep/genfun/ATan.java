package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::ATan (ATan.hh, ATan.cc): atan(x). */
public class ATan extends AbsFunction {

    public ATan() {
    }

    public ATan(ATan right) {
    }

    @Override
    public ATan copy() {
        return new ATan(this);
    }

    @Override
    public double call(double x) {
        return CMath.atan(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final Square square = new Square();
        final AbsFunction fPrime = AbsFunction.div(1.0, AbsFunction.plus(1.0, square));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
