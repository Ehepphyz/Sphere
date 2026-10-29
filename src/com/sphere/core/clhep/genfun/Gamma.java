package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::Gamma (Gamma.hh, Gamma.cc): Gamma(x), the exponential of LogGamma. */
public class Gamma extends AbsFunction {

    private final LogGamma _logGamma = new LogGamma();

    public Gamma() {
    }

    public Gamma(Gamma right) {
    }

    @Override
    public Gamma copy() {
        return new Gamma(this);
    }

    @Override
    public double call(double x) {
        return CMath.exp(_logGamma.call(x));
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
