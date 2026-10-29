package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;

/** CLHEP's Genfun::ArrayFunction (ArrayFunction.hh, .cc): values[i] at the nearest integer i, 0 outside. */
public class ArrayFunction extends AbsFunction {

    private final double[] _values;

    /** The values of [begin, end) of the array. */
    public ArrayFunction(double[] values, int begin, int end) {
        _values = java.util.Arrays.copyOfRange(values, begin, end);
    }

    public ArrayFunction(double... values) {
        this(values, 0, values.length);
    }

    public ArrayFunction(ArrayFunction right) {
        _values = right._values.clone();
    }

    @Override
    public ArrayFunction copy() {
        return new ArrayFunction(this);
    }

    @Override
    public double call(double argument) {
        final int i = CMath.toInt(argument + 0.5);
        if (i < 0 || i >= _values.length) return 0;
        else return _values[i];
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }
}
