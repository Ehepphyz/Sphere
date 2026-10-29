package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::ACos (ACos.hh, ACos.cc): acos(x). */
public class ACos extends AbsFunction {

    public ACos() {
    }

    public ACos(ACos right) {
    }

    @Override
    public ACos copy() {
        return new ACos(this);
    }

    @Override
    public double call(double x) {
        return CMath.acos(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final Square square = new Square();
        final Sqrt root = new Sqrt();
        final AbsFunction fPrime = AbsFunction.div(1.0, root.of(AbsFunction.minus(1.0, square))).negate();
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
