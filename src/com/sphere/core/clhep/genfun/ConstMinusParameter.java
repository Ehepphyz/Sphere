package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::ConstMinusParameter (ConstMinusParameter.hh, .cc): c - p. */
public class ConstMinusParameter extends AbsParameter {

    private final double _constant;
    private final AbsParameter _parameter;

    public ConstMinusParameter(double xconstant, AbsParameter aparm) {
        _constant = xconstant;
        _parameter = connectedCopy(aparm);
    }

    public ConstMinusParameter(ConstMinusParameter right) {
        _constant = right._constant;
        _parameter = right._parameter.copy();
    }

    @Override
    public ConstMinusParameter copy() {
        return new ConstMinusParameter(this);
    }

    @Override
    public double getValue() {
        return _constant - _parameter.getValue();
    }
}
