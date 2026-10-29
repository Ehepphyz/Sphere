package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.function.Function;

/**
 * CLHEP's Genfun::DoubleParamToArgAdaptor (DoubleParamToArgAdaptor.hh, .icc): as {@link ParamToArgAdaptor},
 * the last argument setting two parameters, each through its own scale factor.
 */
public class DoubleParamToArgAdaptor<F extends AbsFunction> extends AbsFunction {

    private final Parameter _scaleFactor0;
    private final Parameter _scaleFactor1;
    private final F _function;
    private final Function<F, Parameter> _parameterFetchMethod0;
    private final Function<F, Parameter> _parameterFetchMethod1;

    public DoubleParamToArgAdaptor(F function, Function<F, Parameter> parameterFetchMethod0,
                                   Function<F, Parameter> parameterFetchMethod1) {
        _scaleFactor0 = new Parameter("Scale 0", 1.0, 0, 10);
        _scaleFactor1 = new Parameter("Scale 1", 1.0, 0, 10);
        _function = ParamToArgAdaptor.copyOf(function);
        _parameterFetchMethod0 = parameterFetchMethod0;
        _parameterFetchMethod1 = parameterFetchMethod1;
        unbound();
    }

    public DoubleParamToArgAdaptor(DoubleParamToArgAdaptor<F> right) {
        _scaleFactor0 = new Parameter(right._scaleFactor0);
        _scaleFactor1 = new Parameter(right._scaleFactor1);
        _parameterFetchMethod0 = right._parameterFetchMethod0;
        _parameterFetchMethod1 = right._parameterFetchMethod1;
        _function = ParamToArgAdaptor.copyOf(right._function);
        unbound();
    }

    private void unbound() {
        _parameterFetchMethod0.apply(_function).setLowerLimit(-Double.MAX_VALUE);
        _parameterFetchMethod0.apply(_function).setUpperLimit(+Double.MAX_VALUE);
        _parameterFetchMethod1.apply(_function).setLowerLimit(-Double.MAX_VALUE);
        _parameterFetchMethod1.apply(_function).setUpperLimit(+Double.MAX_VALUE);
    }

    @Override
    public DoubleParamToArgAdaptor<F> copy() {
        return new DoubleParamToArgAdaptor<>(this);
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning.  DoubleParamToArgAdaptor called with scalar argument\n");
        return 0;
    }

    public Parameter scaleFactor0() {
        return _scaleFactor0;
    }

    public Parameter scaleFactor1() {
        return _scaleFactor1;
    }

    @Override
    public int dimensionality() {
        return _function.dimensionality() + 1;
    }

    @Override
    public double call(Argument a) {
        if (dimensionality() != a.dimension()) {
            StdStreams.cerr().print("Warning: DoubleParamToArgAdaptor function/argument dimension mismatch\n");
            return 0;
        }
        final int dMinus = a.dimension() - 1;
        _parameterFetchMethod0.apply(_function).setValue(_scaleFactor0.getValue() * a.get(dMinus));
        _parameterFetchMethod1.apply(_function).setValue(_scaleFactor1.getValue() * a.get(dMinus));
        return _function.call(a);
    }
}
