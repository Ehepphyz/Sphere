package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ParameterQuotient (ParameterQuotient.hh, .cc): p1 / p2. */
public class ParameterQuotient extends AbsParameter {

    private final AbsParameter _arg1;
    private final AbsParameter _arg2;

    public ParameterQuotient(AbsParameter arg1, AbsParameter arg2) {
        _arg1 = connectedCopy(arg1);
        _arg2 = connectedCopy(arg2);
    }

    public ParameterQuotient(ParameterQuotient right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public ParameterQuotient copy() {
        return new ParameterQuotient(this);
    }

    @Override
    public double getValue() {
        return _arg1.getValue() / _arg2.getValue();
    }
}
