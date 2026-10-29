package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Sin (Sin.hh, Sin.cc): sin(x). */
public class Sin extends AbsFunction {

    public Sin() {
    }

    public Sin(Sin right) {
    }

    @Override
    public Sin copy() {
        return new Sin(this);
    }

    @Override
    public double call(double x) {
        return CMath.sin(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = new Cos();
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
