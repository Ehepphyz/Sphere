package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::CumulativeChiSquare (CumulativeChiSquare.hh, .cc): the cumulative chi-square
 * distribution of ndf degrees of freedom, P(ndf/2, x/2) as a composition of functions.
 */
public class CumulativeChiSquare extends AbsFunction {

    private final int _nDof;
    private AbsFunction _function;

    public CumulativeChiSquare(int ndf) {
        _nDof = ndf;
        create();
    }

    public CumulativeChiSquare(CumulativeChiSquare right) {
        _nDof = right._nDof;
        create();
    }

    @Override
    public CumulativeChiSquare copy() {
        return new CumulativeChiSquare(this);
    }

    @Override
    public double call(double x) {
        return _function.call(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public int nDof() {
        return _nDof;
    }

    private void create() {
        final Variable x = new Variable();
        final IncompleteGamma incompleteGamma = new IncompleteGamma();
        incompleteGamma.a().setValue(_nDof / 2.0);
        _function = incompleteGamma.of(x.div(2.0)).copy();
    }
}
