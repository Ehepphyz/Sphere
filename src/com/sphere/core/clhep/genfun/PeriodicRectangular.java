package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::PeriodicRectangular (PeriodicRectangular.hh, .cc): 0 for a valley of length a, height
 * for a plateau of length b, again and again.
 */
public class PeriodicRectangular extends AbsFunction {

    private final Parameter _a;
    private final Parameter _b;
    private final Parameter _height;

    public PeriodicRectangular() {
        _a = new Parameter("Size-of-valley (a)", 1.0, 1.0, 10);
        _b = new Parameter("Size-of-plateau (b)", 1.0, 1.0, 10);
        _height = new Parameter("height", 1, 0, 10);
    }

    public PeriodicRectangular(PeriodicRectangular right) {
        _a = new Parameter(right._a);
        _b = new Parameter(right._b);
        _height = new Parameter(right._height);
    }

    @Override
    public PeriodicRectangular copy() {
        return new PeriodicRectangular(this);
    }

    @Override
    public double call(double x) {
        double xx = x / (_a.getValue() + _b.getValue());
        xx = xx - Math.floor(xx);
        if (xx < _a.getValue() / (_a.getValue() + _b.getValue())) {
            return 0;
        } else {
            return _height.getValue();
        }
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter a() {
        return _a;
    }

    public Parameter b() {
        return _b;
    }

    public Parameter height() {
        return _height;
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = new FixedConstant(0);
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
