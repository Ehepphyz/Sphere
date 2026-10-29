package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ParameterDifference (ParameterDifference.hh, .cc): p1 - p2. */
public class ParameterDifference extends AbsParameter {

    private final AbsParameter _arg1;
    private final AbsParameter _arg2;

    public ParameterDifference(AbsParameter arg1, AbsParameter arg2) {
        _arg1 = connectedCopy(arg1);
        _arg2 = connectedCopy(arg2);
    }

    public ParameterDifference(ParameterDifference right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public ParameterDifference copy() {
        return new ParameterDifference(this);
    }

    @Override
    public double getValue() {
        return _arg1.getValue() - _arg2.getValue();
    }
}
