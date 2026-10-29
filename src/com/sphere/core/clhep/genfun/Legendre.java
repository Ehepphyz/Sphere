package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Legendre (Legendre.hh, .icc): the Legendre polynomial P_l, GSL's. */
public class Legendre extends AbsFunction {

    private final int _order;

    public Legendre(int order) {
        _order = order;
    }

    public Legendre(Legendre right) {
        _order = right._order;
    }

    @Override
    public Legendre copy() {
        return new Legendre(this);
    }

    @Override
    public double call(double x) {
        return Gsl.checked(Gsl.legendrePl(_order, x), "gsl_sf_legendre_e");
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
