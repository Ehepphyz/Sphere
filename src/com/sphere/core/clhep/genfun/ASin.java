package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::ASin (ASin.hh, ASin.cc): asin(x). */
public class ASin extends AbsFunction {

    public ASin() {
    }

    public ASin(ASin right) {
    }

    @Override
    public ASin copy() {
        return new ASin(this);
    }

    @Override
    public double call(double x) {
        return CMath.asin(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        final Square square = new Square();
        final Sqrt root = new Sqrt();
        final AbsFunction fPrime = AbsFunction.div(1.0, root.of(AbsFunction.minus(1.0, square)));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
