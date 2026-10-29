package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Sqrt (Sqrt.hh, Sqrt.cc): sqrt(x). */
public class Sqrt extends AbsFunction {

    public Sqrt() {
    }

    public Sqrt(Sqrt right) {
    }

    @Override
    public Sqrt copy() {
        return new Sqrt(this);
    }

    @Override
    public double call(double x) {
        return Math.sqrt(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = AbsFunction.div(0.5, new Sqrt());
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
