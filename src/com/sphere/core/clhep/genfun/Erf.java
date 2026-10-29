package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::Erf (Erf.hh, Erf.cc): the error function, as the incomplete gamma P(1/2, x²) signed. */
public class Erf extends AbsFunction {

    private final IncompleteGamma _incompleteGamma;

    public Erf() {
        _incompleteGamma = new IncompleteGamma();
        _incompleteGamma.a().setValue(0.5);
    }

    public Erf(Erf right) {
        _incompleteGamma = new IncompleteGamma(right._incompleteGamma);
    }

    @Override
    public Erf copy() {
        return new Erf(this);
    }

    @Override
    public double call(double x) {
        return x < 0 ? -_incompleteGamma.call(x * x) : _incompleteGamma.call(x * x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    /** 2 x a Gaussian of sigma sqrt(2). */
    @Override
    public FunctionNoop partial(int index) {
        final Gaussian gauss = new Gaussian();
        gauss.sigma().setValue(Math.sqrt(2.0));
        final AbsFunction fPrime = AbsFunction.times(2.0, gauss);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
