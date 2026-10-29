package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::SphericalNeumann (SphericalNeumann.hh, .icc): the spherical Bessel function y_l, GSL's. */
public class SphericalNeumann extends AbsFunction {

    private final int _l;

    public SphericalNeumann(int l) {
        _l = l;
    }

    public SphericalNeumann(SphericalNeumann right) {
        _l = right._l;
    }

    @Override
    public SphericalNeumann copy() {
        return new SphericalNeumann(this);
    }

    public int l() {
        return _l;
    }

    @Override
    public double call(double x) {
        return Gsl.checked(Gsl.besselyl(_l, x), "gsl_sf_bessel_yl_impl");
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
