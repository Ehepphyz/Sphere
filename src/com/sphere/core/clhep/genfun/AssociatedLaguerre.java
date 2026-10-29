package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::AssociatedLaguerre (AssociatedLaguerre.hh, .cc): the associated Laguerre polynomial
 * L(n, k), built as an expression of functions by the recurrence.
 */
public class AssociatedLaguerre extends AbsFunction {

    private final int _n;
    private final int _k;
    private AbsFunction _function;

    public AssociatedLaguerre(int xn, int xk) {
        _n = xn;
        _k = xk;
        create();
    }

    public AssociatedLaguerre(AssociatedLaguerre right) {
        _n = right._n;
        _k = right._k;
        create();
    }

    @Override
    public AssociatedLaguerre copy() {
        return new AssociatedLaguerre(this);
    }

    @Override
    public double call(double x) {
        return _function.call(x);
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public int n() {
        return _n;
    }

    public int k() {
        return _k;
    }

    private void create() {
        final Variable x = new Variable();
        if (_n == 0) {
            _function = new FixedConstant(1.0).copy();
        } else if (_n == 1) {
            _function = x.negate().plus((double) _k).plus(1).copy();
        } else {
            // the C++'s unsigned arithmetic: 2n-1+k and n+k-1, then the functions
            final double c1 = Integer.toUnsignedLong(2 * _n - 1 + _k);
            final double c2 = Integer.toUnsignedLong(_n + _k - 1);
            _function = AbsFunction.times(1.0 / _n,
                AbsFunction.minus(c1, x).times(new AssociatedLaguerre(_n - 1, _k))
                    .minus(AbsFunction.times(c2, new AssociatedLaguerre(_n - 2, _k)))).copy();
        }
    }
}
