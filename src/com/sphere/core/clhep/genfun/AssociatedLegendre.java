package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::AssociatedLegendre (AssociatedLegendre.hh, .icc): the associated Legendre function
 * P_l^m, GSL's. (CLHEP's warning names gsl_sf_bessel_Yn_impl, kept.)
 */
public class AssociatedLegendre extends AbsFunction {

    private final int _l;
    private final int _m;

    public AssociatedLegendre(int l, int m) {
        _l = l;
        _m = m;
    }

    public AssociatedLegendre(AssociatedLegendre right) {
        _l = right._l;
        _m = right._m;
    }

    @Override
    public AssociatedLegendre copy() {
        return new AssociatedLegendre(this);
    }

    public int l() {
        return _l;
    }

    public int m() {
        return _m;
    }

    @Override
    public double call(double x) {
        return Gsl.checked(Gsl.legendrePlm(_l, _m, x), "gsl_sf_bessel_Yn_impl");
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
