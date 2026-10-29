package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Ln (Ln.hh, Ln.cc): log(x), the natural logarithm. */
public class Ln extends AbsFunction {

    public Ln() {
    }

    public Ln(Ln right) {
    }

    @Override
    public Ln copy() {
        return new Ln(this);
    }

    @Override
    public double call(double x) {
        return CMath.log(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = AbsFunction.div(1.0, new Variable());
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
