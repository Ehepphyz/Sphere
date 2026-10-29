package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ParameterSum (ParameterSum.hh, .cc): p1 + p2. */
public class ParameterSum extends AbsParameter {

    private final AbsParameter _arg1;
    private final AbsParameter _arg2;

    public ParameterSum(AbsParameter arg1, AbsParameter arg2) {
        _arg1 = connectedCopy(arg1);
        _arg2 = connectedCopy(arg2);
    }

    public ParameterSum(ParameterSum right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public ParameterSum copy() {
        return new ParameterSum(this);
    }

    @Override
    public double getValue() {
        return _arg1.getValue() + _arg2.getValue();
    }
}
