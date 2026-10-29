package com.sphere.core.clhep.genfun;

/** CLHEP's Genfun::FunctionPlusParameter (FunctionPlusParameter.hh, .cc): p + f. */
public class FunctionPlusParameter extends AbsFunction {

    private final AbsFunction _function;
    private final AbsParameter _parameter;

    public FunctionPlusParameter(AbsParameter parameter, AbsFunction function) {
        _function = function.copy();
        _parameter = parameter.copy();
        if (parameter.parameter() != null && _parameter.parameter() != null) {
            _parameter.parameter().connectFrom(parameter.parameter());
        }
    }

    public FunctionPlusParameter(FunctionPlusParameter right) {
        _function = right._function.copy();
        _parameter = right._parameter.copy();
    }

    @Override
    public FunctionPlusParameter copy() {
        return new FunctionPlusParameter(this);
    }

    @Override
    public int dimensionality() {
        return _function.dimensionality();
    }

    @Override
    public double call(double x) {
        return _parameter.getValue() + _function.call(x);
    }

    @Override
    public double call(Argument x) {
        return _parameter.getValue() + _function.call(x);
    }

    @Override
    public FunctionNoop partial(int index) {
        return _function.partial(index);
    }

    @Override
    public boolean hasAnalyticDerivative() {
        return true;
    }
}
