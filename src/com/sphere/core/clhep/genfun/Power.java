package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/**
 * CLHEP's Genfun::Power (Power.hh, Power.cc): x to an integer power (repeated multiplication or
 * division) or to a real one (pow).
 */
public class Power extends AbsFunction {

    private final double _doublePower;
    private final int _intPower;
    private final boolean _asInteger;

    public Power(int n) {
        _intPower = n;
        _doublePower = 0;
        _asInteger = true;
    }

    public Power(double n) {
        _doublePower = n;
        _intPower = 0;
        _asInteger = false;
    }

    public Power(Power right) {
        _doublePower = right._doublePower;
        _intPower = right._intPower;
        _asInteger = right._asInteger;
    }

    @Override
    public Power copy() {
        return new Power(this);
    }

    @Override
    public double call(double x) {
        if (_asInteger) {
            if (_intPower == 0) {
                return 1;
            } else if (_intPower > 0) {
                double f = 1;
                for (int i = 0; i < _intPower; i++) f *= x;
                return f;
            } else {
                double f = 1;
                for (int i = 0; i < -_intPower; i++) f /= x;
                return f;
            }
        } else {
            return CMath.pow(x, _doublePower);
        }
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    /** n x^(n-1). */
    @Override
    public FunctionNoop partial(int index) {
        if (_asInteger) {
            final AbsFunction fPrime = AbsFunction.times(_intPower, new Power(_intPower - 1));
            return new FunctionNoop(fPrime);
        } else {
            final AbsFunction fPrime = AbsFunction.times(_doublePower, new Power(_doublePower - 1));
            return new FunctionNoop(fPrime);
        }
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
