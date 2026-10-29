package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

import java.util.function.DoubleUnaryOperator;

/**
 * CLHEP's Genfun::FunctionNumDeriv (GenericFunctions/FunctionNumDeriv.hh, FunctionNumDeriv.cc): the
 * numerical derivative of a function, by Richardson extrapolation of central differences, the step
 * adapted until three estimates agree (CLHEP's ROBUST_DERIVATIVES, about 8 digits).
 */
public class FunctionNumDeriv extends AbsFunction {

    private final AbsFunction _arg1;
    private final int _wrtIndex;
    private Argument _xArg = new Argument();

    public FunctionNumDeriv(AbsFunction arg1) {
        this(arg1, 0);
    }

    public FunctionNumDeriv(AbsFunction arg1, int index) {
        _arg1 = arg1.copy();
        _wrtIndex = index;
    }

    public FunctionNumDeriv(FunctionNumDeriv right) {
        _arg1 = right._arg1.copy();
        _wrtIndex = right._wrtIndex;
    }

    @Override
    public FunctionNumDeriv copy() {
        return new FunctionNumDeriv(this);
    }

    @Override
    public int dimensionality() {
        return _arg1.dimensionality();
    }

    private double f_x(double x) {
        return _arg1.call(x);
    }

    private double f_Arg(double x) {
        _xArg.set(_wrtIndex, x);
        return _arg1.call(_xArg);
    }

    @Override
    public double call(double x) {
        return numericalDerivative(this::f_x, x);
    }

    @Override
    public double call(Argument x) {
        _xArg = new Argument(x);
        final double xx = x.get(_wrtIndex);
        return numericalDerivative(this::f_Arg, xx);
    }

    private double numericalDerivative(DoubleUnaryOperator f, double x) {
        final double h0 = 5 * CMath.pow(2.0, -17);

        final double maxErrorA = .0012;
        final double maxErrorB = .0000026;

        final double maxErrorC = .0003;

        final int nItersMax = 6;
        int nIters;
        double bestError = 1.0E30;
        double bestAns = 0;

        final double valFactor = CMath.pow(2.0, -16);

        final double w = 5.0 / 8;
        final double wi2 = 64.0 / 25.0;
        final double wi4 = wi2 * wi2;

        double size = Math.abs(f.applyAsDouble(x));
        if (size == 0) size = CMath.pow(2.0, -53);

        final double[] adjustmentFactor = {
            1.0,
            CMath.pow(2.0, -17),
            CMath.pow(2.0, +17),
            CMath.pow(2.0, -34),
            CMath.pow(2.0, +34),
            CMath.pow(2.0, -51)};

        for (nIters = 0; nIters < nItersMax; ++nIters) {

            final double h = h0 * adjustmentFactor[nIters];

            // three estimates from h and two smaller steps
            final double A1 = (f.applyAsDouble(x + h) - f.applyAsDouble(x - h)) / (2.0 * h);
            if (Math.abs(A1) > size) size = Math.abs(A1);

            double hh = w * h;
            final double A2 = (f.applyAsDouble(x + hh) - f.applyAsDouble(x - hh)) / (2.0 * hh);
            if (Math.abs(A2) > size) size = Math.abs(A2);

            hh *= w;
            final double A3 = (f.applyAsDouble(x + hh) - f.applyAsDouble(x - hh)) / (2.0 * hh);
            if (Math.abs(A3) > size) size = Math.abs(A3);

            if ((Math.abs(A1 - A2) / size > maxErrorA) || (Math.abs(A1 - A3) / size > maxErrorA)) {
                continue;
            }

            // two Richardson extrapolations, then a third
            final double B1 = (A2 * wi2 - A1) / (wi2 - 1);
            final double B2 = (A3 * wi2 - A2) / (wi2 - 1);
            if (Math.abs(B1 - B2) / size > maxErrorB) {
                continue;
            }

            final double ans = (B2 * wi4 - B1) / (wi4 - 1);
            final double err = Math.abs(ans - B1);
            if (err < bestError) {
                bestError = err;
                bestAns = ans;
            }

            // validated by a smaller step
            hh = h * valFactor;
            final double val = (f.applyAsDouble(x + hh) - f.applyAsDouble(x - hh)) / (2.0 * hh);
            if (Math.abs(val - ans) / size > maxErrorC) {
                continue;
            }

            break;
        }
        return bestAns;
    }
}
