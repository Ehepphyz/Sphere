package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::VoigtProfile (VoigtProfile.hh, .cc): the convolution of a Breit-Wigner and a Gaussian,
 * the real part of the Faddeeva function by CERNLIB's WWERF (nwwerf).
 */
public class VoigtProfile extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final Parameter _mass;
    private final Parameter _width;
    private final Parameter _sigma;

    public VoigtProfile() {
        _mass = new Parameter("mass", 50, 10, 90);
        _width = new Parameter("width", 5, 0, 100);
        _sigma = new Parameter("sigma", 5, 0, 100);
    }

    public VoigtProfile(VoigtProfile right) {
        _mass = new Parameter(right._mass);
        _width = new Parameter(right._width);
        _sigma = new Parameter(right._sigma);
    }

    @Override
    public VoigtProfile copy() {
        return new VoigtProfile(this);
    }

    public Parameter mass() {
        return _mass;
    }

    public Parameter width() {
        return _width;
    }

    public Parameter sigma() {
        return _sigma;
    }

    @Override
    public double call(double x) {
        final double M = _mass.getValue();
        final double G = _width.getValue() / 2.0;
        final double s = _sigma.getValue();
        final double sqrt2 = Math.sqrt(2.0);
        final double sqrt2PI = Math.sqrt(2.0 * M_PI);
        // ((x-M) + I*G)/sqrt2/s: I*G is (0*G, G); a double plus a complex adds to the real part
        final Complex iG = new Complex(0 * G, 1 * G);
        final Complex z = iG.plus(x - M).div(sqrt2).div(s);
        return ErrorFunctions.nwwerf(z).re() / s / sqrt2PI;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
