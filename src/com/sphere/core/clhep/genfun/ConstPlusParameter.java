package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstPlusParameter (ConstPlusParameter.hh, .cc): c + p. */
public class ConstPlusParameter extends AbsParameter {

    private final double _constant;
    private final AbsParameter _parameter;

    public ConstPlusParameter(double xconstant, AbsParameter aparm) {
        _constant = xconstant;
        _parameter = connectedCopy(aparm);
    }

    public ConstPlusParameter(ConstPlusParameter right) {
        _constant = right._constant;
        _parameter = right._parameter.copy();
    }

    @Override
    public ConstPlusParameter copy() {
        return new ConstPlusParameter(this);
    }

    @Override
    public double getValue() {
        return _constant + _parameter.getValue();
    }
}
