package com.sphere.core.clhep.genfun;

import java.util.ArrayList;
import java.util.List;

/** CLHEP's Genfun::Sigma (Sigma.hh, Sigma.cc): the sum of the functions accumulated. */
public class Sigma extends AbsFunction {

    private final List<AbsFunction> _fcn = new ArrayList<>();

    public Sigma() {
    }

    public Sigma(Sigma right) {
        for (AbsFunction f : right._fcn) _fcn.add(f.copy());
    }

    @Override
    public Sigma copy() {
        return new Sigma(this);
    }

    /** A copy of fcn added to the sum. */
    public void accumulate(AbsFunction fcn) {
        _fcn.add(fcn.copy());
    }

    @Override
    public int dimensionality() {
        return _fcn.get(0).dimensionality();
    }

    @Override
    public double call(double x) {
        double retVal = 0.0;
        for (AbsFunction f : _fcn) retVal += f.call(x);
        return retVal;
    }

    @Override
    public double call(Argument x) {
        double retVal = 0.0;
        for (AbsFunction f : _fcn) retVal += f.call(x);
        return retVal;
    }

    @Override
    public FunctionNoop partial(int index) {
        final Sigma fPrime = new Sigma();
        for (AbsFunction f : _fcn) fPrime.accumulate(f.partial(index));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
