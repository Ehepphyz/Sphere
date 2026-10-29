package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::FunctionTimesParameter (FunctionTimesParameter.hh, .cc): p * f. */
public class FunctionTimesParameter extends AbsFunction {

    private final AbsFunction _function;
    private final AbsParameter _parameter;

    public FunctionTimesParameter(AbsParameter parameter, AbsFunction function) {
        _function = function.copy();
        _parameter = parameter.copy();
        if (parameter.parameter() != null && _parameter.parameter() != null) {
            _parameter.parameter().connectFrom(parameter.parameter());
        }
    }

    public FunctionTimesParameter(FunctionTimesParameter right) {
        _function = right._function.copy();
        _parameter = right._parameter.copy();
    }

    @Override
    public FunctionTimesParameter copy() {
        return new FunctionTimesParameter(this);
    }

    @Override
    public int dimensionality() {
        return _function.dimensionality();
    }

    @Override
    public double call(double x) {
        return _parameter.getValue() * _function.call(x);
    }

    @Override
    public double call(Argument x) {
        return _parameter.getValue() * _function.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        final AbsFunction fPrime = _parameter.times(_function.partial(index));
        return new FunctionNoop(fPrime);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
