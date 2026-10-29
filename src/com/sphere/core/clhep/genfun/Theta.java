package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Theta (Theta.hh, Theta.cc): the step function: 1 for x &gt;= 0, 0 below. */
public class Theta extends AbsFunction {

    public Theta() {
    }

    public Theta(Theta right) {
    }

    @Override
    public Theta copy() {
        return new Theta(this);
    }

    @Override
    public double call(double x) {
        return (x >= 0) ? 1.0 : 0.0;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        if (index != 0) throw new RuntimeException("Theta::Partial: index out of range");
        final AbsFunction fPrime = new FixedConstant(0.0);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
