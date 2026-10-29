package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Airy (Airy.hh, .icc): the Airy functions Ai and Bi, GSL's to double precision. */
public class Airy extends AbsFunction {

    public enum Type {
        Ai, Bi
    }

    private final Type _type;

    public Airy(Type type) {
        _type = type;
    }

    public Airy(Airy right) {
        _type = right._type;
    }

    @Override
    public Airy copy() {
        return new Airy(this);
    }

    @Override
    public double call(double x) {
        if (_type == Type.Ai) return Gsl.checked(Gsl.airyAi(x), "gsl_sf_airy_ai");
        return Gsl.checked(Gsl.airyBi(x), "gsl_sf_airy_bi");
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
