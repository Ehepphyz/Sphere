package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::RelativisticBWDistribution (RelativisticBW.hh, .cc): the relativistic Breit-Wigner density. */
public class RelativisticBWDistribution extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final Parameter _mass;
    private final Parameter _width;

    public RelativisticBWDistribution() {
        _mass = new Parameter("mass", 50, 10, 90);
        _width = new Parameter("width", 5, 0, 100);
    }

    public RelativisticBWDistribution(RelativisticBWDistribution right) {
        _mass = new Parameter(right._mass);
        _width = new Parameter(right._width);
    }

    @Override
    public RelativisticBWDistribution copy() {
        return new RelativisticBWDistribution(this);
    }

    @Override
    public double call(double x) {
        final double M = _mass.getValue();
        final double G = _width.getValue();
        final double g = Math.sqrt(M * M * (M * M + G * G));
        final double k = 2.0 * Math.sqrt(2.0) * M * G * g / M_PI / Math.sqrt(M * M + g);
        final double f = k / ((x - M) * (x - M) * (x + M) * (x + M) + M * M * G * G);
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
