package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Exp (Exp.hh, Exp.cc): exp(x). */
public class Exp extends AbsFunction {

    public Exp() {
    }

    public Exp(Exp right) {
    }

    @Override
    public Exp copy() {
        return new Exp(this);
    }

    @Override
    public double call(double x) {
        return CMath.exp(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    @Override
    public FunctionNoop partial(int index) {
        return new FunctionNoop(this);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
