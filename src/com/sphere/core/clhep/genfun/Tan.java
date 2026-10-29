package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Tan (Tan.hh, Tan.cc): tan(x). */
public class Tan extends AbsFunction {

    public Tan() {
    }

    public Tan(Tan right) {
    }

    @Override
    public Tan copy() {
        return new Tan(this);
    }

    @Override
    public double call(double x) {
        return CMath.tan(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = AbsFunction.div(1.0, new Cos()).div(new Cos());
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
