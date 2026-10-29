package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::HermitePolynomial (HermitePolynomial.hh, .cc): the normalized Hermite polynomial of
 * order N (its recurrence from sqrt(pi/4)).
 */
public class HermitePolynomial extends AbsFunction {

    private static final double M_PI = 3.14159265358979323846;

    private final int _N;

    public HermitePolynomial(int N) {
        _N = N;
    }

    public HermitePolynomial(HermitePolynomial right) {
        _N = right._N;
    }

    @Override
    public HermitePolynomial copy() {
        return new HermitePolynomial(this);
    }

    @Override
    public double call(double x) {
        final double h00 = 0.0;
        final double h0 = Math.sqrt(M_PI / 4.0);
        double p0 = h00;
        double p1 = h00;
        double pn = h0;
        for (int i = 1; i <= _N; i++) {
            p0 = p1;
            p1 = pn;
            pn = x * Math.sqrt(2.0 / i) * p1 - Math.sqrt((i - 1.0) / i) * p0;
        }
        return pn;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public int N() {
        return _N;
    }

    @Override
    public FunctionNoop partial(int index) {
        if (_N > 0) {
            final AbsFunction fPrime = AbsFunction.times(Math.sqrt(2.0 * _N), new HermitePolynomial(_N - 1));
            return new FunctionNoop(fPrime);
        } else {
            final AbsFunction fPrime = new FixedConstant(0.0);
            return new FunctionNoop(fPrime);
        }
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
