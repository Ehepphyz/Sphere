package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstOverParameter (ConstOverParameter.hh, .cc): c / p. */
public class ConstOverParameter extends AbsParameter {

    private final double _constant;
    private final AbsParameter _parameter;

    public ConstOverParameter(double xconstant, AbsParameter aparm) {
        _constant = xconstant;
        _parameter = connectedCopy(aparm);
    }

    public ConstOverParameter(ConstOverParameter right) {
        _constant = right._constant;
        _parameter = right._parameter.copy();
    }

    @Override
    public ConstOverParameter copy() {
        return new ConstOverParameter(this);
    }

    @Override
    public double getValue() {
        return _constant / _parameter.getValue();
    }
}
