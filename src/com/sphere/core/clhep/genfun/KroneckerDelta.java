package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::KroneckerDelta (KroneckerDelta.hh, .cc): 1 within half a unit of 0, 0 elsewhere. */
public class KroneckerDelta extends AbsFunction {

    public KroneckerDelta() {
    }

    public KroneckerDelta(KroneckerDelta right) {
    }

    @Override
    public KroneckerDelta copy() {
        return new KroneckerDelta(this);
    }

    @Override
    public double call(double x) {
        if (x < 0.5 && x > -0.5) return 1;
        return 0;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = new FixedConstant(0);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
