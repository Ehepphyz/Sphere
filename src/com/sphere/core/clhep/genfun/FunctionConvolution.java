package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::FunctionConvolution (FunctionConvolution.hh, .cc): (f*g)(x), the integral of f(x-t)
 * g(t) from x0 to x1 as the mean of 200 points.
 */
public class FunctionConvolution extends AbsFunction {

    private final AbsFunction _arg1;
    private final AbsFunction _arg2;
    private final double _x0;
    private final double _x1;

    public FunctionConvolution(AbsFunction arg1, AbsFunction arg2, double x0, double x1) {
        _arg1 = arg1.copy();
        _arg2 = arg2.copy();
        _x0 = x0;
        _x1 = x1;
        if ((arg1.dimensionality() != 1) || arg2.dimensionality() != 1) {
            StdStreams.cout().print("Warning:  dimension mismatch in function convolution\n");
        }
    }

    public FunctionConvolution(FunctionConvolution right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
        _x0 = right._x0;
        _x1 = right._x1;
    }

    @Override
    public FunctionConvolution copy() {
        return new FunctionConvolution(this);
    }

    @Override
    public double call(double argument) {
        final double NDIVISIONS = 200.0;
        final double dx = (_x1 - _x0) / NDIVISIONS;
        double result = 0.0;
        for (double x = _x0; x < _x1; x += dx) result += _arg1.call(argument - x) * _arg2.call(x);
        result /= NDIVISIONS;
        return result;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
