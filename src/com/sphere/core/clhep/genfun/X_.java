package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::X_ (X.hh, X.cc): the identity, x. */
public class X_ extends AbsFunction {

    public X_() {
    }

    public X_(X_ right) {
    }

    @Override
    public X_ copy() {
        return new X_(this);
    }

    @Override
    public double call(double x) {
        return x;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
