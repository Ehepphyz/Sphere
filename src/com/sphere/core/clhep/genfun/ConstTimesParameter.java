package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstTimesParameter (ConstTimesParameter.hh, .cc): c * p. */
public class ConstTimesParameter extends AbsParameter {

    private final double _constant;
    private final AbsParameter _parameter;

    public ConstTimesParameter(double xconstant, AbsParameter aparm) {
        _constant = xconstant;
        _parameter = connectedCopy(aparm);
    }

    public ConstTimesParameter(ConstTimesParameter right) {
        _constant = right._constant;
        _parameter = right._parameter.copy();
    }

    @Override
    public ConstTimesParameter copy() {
        return new ConstTimesParameter(this);
    }

    @Override
    public double getValue() {
        return _constant * _parameter.getValue();
    }
}
