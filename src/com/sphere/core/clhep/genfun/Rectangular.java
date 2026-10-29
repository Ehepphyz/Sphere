package com.sphere.core.clhep.genfun;

/**
 * CLHEP's Genfun::Rectangular (Rectangular.hh, Rectangular.cc): height between x0 and x1, baseline
 * elsewhere.
 */
public class Rectangular extends AbsFunction {

    private final Parameter _x0;
    private final Parameter _x1;
    private final Parameter _baseline;
    private final Parameter _height;

    public Rectangular() {
        _x0 = new Parameter("x0", -1.0, -10, 0);
        _x1 = new Parameter("x1", 1.0, 0, 10);
        _baseline = new Parameter("baseline", 0, -10, 10);
        _height = new Parameter("height", 1, -10, 10);
    }

    public Rectangular(Rectangular right) {
        _x0 = new Parameter(right._x0);
        _x1 = new Parameter(right._x1);
        _baseline = new Parameter(right._baseline);
        _height = new Parameter(right._height);
    }

    @Override
    public Rectangular copy() {
        return new Rectangular(this);
    }

    @Override
    public double call(double x) {
        if (x < _x0.getValue()) {
            return _baseline.getValue();
        } else if (x < _x1.getValue()) {
            return _height.getValue();
        } else {
            return _baseline.getValue();
        }
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    public Parameter x0() {
        return _x0;
    }

    public Parameter x1() {
        return _x1;
    }

    public Parameter baseline() {
        return _baseline;
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
