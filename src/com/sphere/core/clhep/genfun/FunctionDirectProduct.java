package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::FunctionDirectProduct (FunctionDirectProduct.hh, .cc): f % g, the function of f's m
 * variables then g's n, their product.
 */
public class FunctionDirectProduct extends AbsFunction {

    private final AbsFunction _arg1;
    private final AbsFunction _arg2;
    private final int _m;
    private final int _n;

    public FunctionDirectProduct(AbsFunction arg1, AbsFunction arg2) {
        _arg1 = arg1.copy();
        _arg2 = arg2.copy();
        _m = arg1.dimensionality();
        _n = arg2.dimensionality();
    }

    public FunctionDirectProduct(FunctionDirectProduct right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
        _m = right._m;
        _n = right._n;
    }

    @Override
    public FunctionDirectProduct copy() {
        return new FunctionDirectProduct(this);
    }

    @Override
    public double call(Argument a) {
        final int P = a.dimension();
        final Argument x1 = new Argument(_m);
        final Argument x2 = new Argument(_n);
        if (_m + _n != P) {
            StdStreams.cerr().print("Warning: Direct product function/argument dimension mismatch\n");
            return 0;
        }
        for (int i = 0; i < _m; i++) x1.set(i, a.get(i));
        for (int j = 0; j < _n; j++) x2.set(j, a.get(j + _m));
        return _arg1.call(x1) * _arg2.call(x2);
    }

    @Override
    public int dimensionality() {
        return _m + _n;
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning.  direct product called with scalar argument\n");
        return 0;
    }

    @Override
    public FunctionNoop partial(int index) {
        if (index < _m) {
            final AbsFunction fPrime = _arg1.partial(index).directProduct(_arg2);
            return new FunctionNoop(fPrime);
        } else {
            final AbsFunction fPrime = _arg1.directProduct(_arg2.partial(index - _m));
            return new FunctionNoop(fPrime);
        }
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
