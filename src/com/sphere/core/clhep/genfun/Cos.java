package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Cos (Cos.hh, Cos.cc): cos(x). */
public class Cos extends AbsFunction {

    public Cos() {
    }

    public Cos(Cos right) {
    }

    @Override
    public Cos copy() {
        return new Cos(this);
    }

    @Override
    public double call(double x) {
        return CMath.cos(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = new Sin().negate();
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
