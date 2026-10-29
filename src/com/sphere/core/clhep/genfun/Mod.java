package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Mod (Mod.hh, Mod.cc): x modulo y, in [0, y): x - y floor(x/y). */
public class Mod extends AbsFunction {

    private final double _y;

    public Mod(double y) {
        _y = y;
    }

    public Mod(Mod right) {
        _y = right._y;
    }

    @Override
    public Mod copy() {
        return new Mod(this);
    }

    @Override
    public double call(double x) {
        return (x - _y * Math.floor(x / _y));
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
