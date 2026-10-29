package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

/** CLHEP's Genfun::FunctionComposition (FunctionComposition.hh, .cc): f(g), f of one variable. */
public class FunctionComposition extends AbsFunction {

    private final AbsFunction _arg1;
    private final AbsFunction _arg2;

    public FunctionComposition(AbsFunction arg1, AbsFunction arg2) {
        _arg1 = arg1.copy();
        _arg2 = arg2.copy();
        if (arg1.dimensionality() != 1) {
            StdStreams.cout().print("Warning: dimension mismatch in function convolution\n");
        }
    }

    public FunctionComposition(FunctionComposition right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public FunctionComposition copy() {
        return new FunctionComposition(this);
    }

    @Override
    public int dimensionality() {
        return _arg2.dimensionality();
    }

    @Override
    public double call(double argument) {
        if (dimensionality() != 1) {
            StdStreams.cerr().print("Warning: LifetimeResolutionConvolution function/argument dimension mismatch\n");
            return 0;
        }
        return _arg1.call(_arg2.call(argument));
    }

    @Override
    public double call(Argument v) {
        if (v.dimension() != _arg2.dimensionality()) {
            StdStreams.cerr().print("Warning: FunctionComposition function/argument dimension mismatch\n");
            return 0;
        }
        return _arg1.call(_arg2.call(v));
    }

    /** f'(g) times g'. */
    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _arg1.partial(0).of(_arg2).times(_arg2.partial(index));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
