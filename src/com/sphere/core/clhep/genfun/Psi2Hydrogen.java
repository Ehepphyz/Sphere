package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::Psi2Hydrogen (Psi2Hydrogen.hh, .icc): |psi_nlm|² of the hydrogen atom as a function of
 * (r, cos theta, phi), built from Laguerre and associated Legendre functions (the latter GSL's).
 */
public class Psi2Hydrogen extends AbsFunction {

    private final int _n;
    private final int _l;
    private final int _m;
    private AbsFunction _function;

    public Psi2Hydrogen(int n, int l, int m) {
        _n = n;
        _l = l;
        _m = m;
        create();
    }

    public Psi2Hydrogen(Psi2Hydrogen right) {
        _n = right._n;
        _l = right._l;
        _m = right._m;
        create();
    }

    @Override
    public Psi2Hydrogen copy() {
        return new Psi2Hydrogen(this);
    }

    @Override
    public double call(Argument a) {
        return _function.call(a);
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning. Psi2Hydrogen called with scalar argument\n");
        return 0;
    }

    public int n() {
        return _n;
    }

    public int l() {
        return _l;
    }

    public int m() {
        return _m;
    }

    /** n!, as the recursion n * factorial(n - 1) multiplies. */
    private static double factorial(int n) {
        double f = 1.0;
        for (int i = 2; i <= n; i++) f = i * f;
        return f;
    }

    private void create() {
        final FixedConstant I = new FixedConstant(1.0);
        final Variable r = new Variable();
        final double asq = CMath.pow(2.0 / _n, 3.0) * factorial(_n - _l - 1) / (2.0 * _n * factorial(_n + _l));
        final AbsFunction ar = AbsFunction.times(2.0 / _n, r);
        final AssociatedLegendre P = new AssociatedLegendre(_l, _m);
        final AssociatedLaguerre L = new AssociatedLaguerre(_n - _l - 1, 2 * _l + 1);
        final Exponential exp = new Exponential();
        final Power pow2L = new Power(2 * _l);
        _function = AbsFunction.times(asq, exp.of(ar)).times(pow2L.of(ar)).times(L.of(ar)).times(L.of(ar))
            .directProduct(P.times(P)).directProduct(I.times(I)).copy();
    }
}
