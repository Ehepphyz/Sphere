package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ParameterComposition (ParameterComposition.hh, .cc): f(p), a parameter. */
public class ParameterComposition extends AbsParameter {

    private final AbsFunction _arg1;
    private final AbsParameter _arg2;

    public ParameterComposition(AbsFunction arg1, AbsParameter arg2) {
        _arg1 = arg1.copy();
        _arg2 = connectedCopy(arg2);
    }

    public ParameterComposition(ParameterComposition right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public ParameterComposition copy() {
        return new ParameterComposition(this);
    }

    @Override
    public double getValue() {
        return _arg1.call(_arg2.getValue());
    }
}
