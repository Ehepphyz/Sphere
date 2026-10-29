package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::SphericalBessel (SphericalBessel.hh, .icc): the spherical Bessel function j_l, GSL's. */
public class SphericalBessel extends AbsFunction {

    private final int _l;

    public SphericalBessel(int l) {
        _l = l;
    }

    public SphericalBessel(SphericalBessel right) {
        _l = right._l;
    }

    @Override
    public SphericalBessel copy() {
        return new SphericalBessel(this);
    }

    public int l() {
        return _l;
    }

    @Override
    public double call(double x) {
        return Gsl.checked(Gsl.besseljl(_l, x), "gsl_sf_bessel_jl_impl");
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
