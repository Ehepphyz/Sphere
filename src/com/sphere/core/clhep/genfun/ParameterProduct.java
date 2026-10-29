package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ParameterProduct (ParameterProduct.hh, .cc): p1 * p2. */
public class ParameterProduct extends AbsParameter {

    private final AbsParameter _arg1;
    private final AbsParameter _arg2;

    public ParameterProduct(AbsParameter arg1, AbsParameter arg2) {
        _arg1 = connectedCopy(arg1);
        _arg2 = connectedCopy(arg2);
    }

    public ParameterProduct(ParameterProduct right) {
        _arg1 = right._arg1.copy();
        _arg2 = right._arg2.copy();
    }

    @Override
    public ParameterProduct copy() {
        return new ParameterProduct(this);
    }

    @Override
    public double getValue() {
        return _arg1.getValue() * _arg2.getValue();
    }
}
