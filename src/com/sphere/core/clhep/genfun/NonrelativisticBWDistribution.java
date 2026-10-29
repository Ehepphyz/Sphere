package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::NonrelativisticBWDistribution (NonrelativisticBW.hh, .cc): the Breit-Wigner (Cauchy) density. */
public class NonrelativisticBWDistribution extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final Parameter _mass;
    private final Parameter _width;

    public NonrelativisticBWDistribution() {
        _mass = new Parameter("mass", 50, 10, 90);
        _width = new Parameter("width", 5, 0, 100);
    }

    public NonrelativisticBWDistribution(NonrelativisticBWDistribution right) {
        _mass = new Parameter(right._mass);
        _width = new Parameter(right._width);
    }

    @Override
    public NonrelativisticBWDistribution copy() {
        return new NonrelativisticBWDistribution(this);
    }

    @Override
    public double call(double x) {
        final double M = _mass.getValue();
        final double G = _width.getValue() / 2.0;
        final double f = (1.0 / M_PI) * G / ((x - M) * (x - M) + G * G);
        return f;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter mass() {
        return _mass;
    }

    public Parameter width() {
        return _width;
    }
}
