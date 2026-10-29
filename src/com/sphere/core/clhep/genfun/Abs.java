package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Abs (Abs.hh, Abs.cc): |x|. */
public class Abs extends AbsFunction {

    public Abs() {
    }

    public Abs(Abs right) {
    }

    @Override
    public Abs copy() {
        return new Abs(this);
    }

    @Override
    public double call(double x) {
        return Math.abs(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
