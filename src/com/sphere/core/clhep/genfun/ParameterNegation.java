package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ParameterNegation (ParameterNegation.hh, .cc): -p. */
public class ParameterNegation extends AbsParameter {

    private final AbsParameter _arg1;

    public ParameterNegation(AbsParameter arg1) {
        _arg1 = connectedCopy(arg1);
    }

    public ParameterNegation(ParameterNegation right) {
        _arg1 = right._arg1.copy();
    }

    @Override
    public ParameterNegation copy() {
        return new ParameterNegation(this);
    }

    @Override
    public double getValue() {
        return -_arg1.getValue();
    }
}
