package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::LogisticFunction (LogisticFunction.hh, .cc): the logistic map x(n+1) = a x(n) (1 -
 * x(n)) from x0, at the integer nearest x (0 to 1000; 0 outside); the sequence kept between calls.
 */
public class LogisticFunction extends AbsFunction {

    private static final int MAXRANGE = 1000;

    private final Parameter _x0;
    private final Parameter _a;
    private final List<Double> fx = new ArrayList<>();
    private double __a = Double.NaN;
    private double __x0 = Double.NaN;

    public LogisticFunction() {
        _x0 = new Parameter("X0", 0.0, 0.0, 1.0);
        _a = new Parameter("A", 2.0, 1.0, 4.0);
    }

    public LogisticFunction(LogisticFunction right) {
        _x0 = new Parameter(right._x0);
        _a = new Parameter(right._a);
    }

    @Override
    public LogisticFunction copy() {
        return new LogisticFunction(this);
    }

    @Override
    public double call(double x) {
        final int i = CMath.toInt(x + 0.5);
        final int end = i + 1;
        if (i < 0 || i > MAXRANGE) {
            return 0;
        } else {
            if (__a != _a.getValue() || __x0 != _x0.getValue()) {
                fx.clear();
                __a = _a.getValue();
                __x0 = _x0.getValue();
            }
            if (fx.isEmpty()) fx.add(__x0);
            while (fx.size() < end) {
                final double v = fx.get(fx.size() - 1);
                fx.add(__a * v * (1.0 - v));
            }
            return fx.get(i);
        }
    }

    @Override
    public double call(Argument arg) {
        return call(arg.get(0));
    }

    public Parameter x0() {
        return _x0;
    }

    public Parameter a() {
        return _a;
    }
}
