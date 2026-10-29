package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.function.Function;

/**
 * CLHEP's Genfun::ParamToArgAdaptor (ParamToArgAdaptor.hh, .icc): a function of one more variable, the last
 * argument times the scale factor setting one parameter of a copy of the function (unbounded). As in CLHEP,
 * the function is then called with the whole argument.
 */
public class ParamToArgAdaptor<F extends AbsFunction> extends AbsFunction {

    private final Parameter _scaleFactor;
    private final F _function;
    private final Function<F, Parameter> _parameterFetchMethod;

    /** parameterFetchMethod is the accessor of the parameter, such as {@code Gaussian::sigma}. */
    public ParamToArgAdaptor(F function, Function<F, Parameter> parameterFetchMethod) {
        _scaleFactor = new Parameter("Sigma", 1.0, 0, 10);
        _function = copyOf(function);
        _parameterFetchMethod = parameterFetchMethod;
        _parameterFetchMethod.apply(_function).setLowerLimit(-Double.MAX_VALUE);
        _parameterFetchMethod.apply(_function).setUpperLimit(+Double.MAX_VALUE);
    }

    public ParamToArgAdaptor(ParamToArgAdaptor<F> right) {
        _scaleFactor = new Parameter(right._scaleFactor);
        _parameterFetchMethod = right._parameterFetchMethod;
        _function = copyOf(right._function);
        _parameterFetchMethod.apply(_function).setLowerLimit(-Double.MAX_VALUE);
        _parameterFetchMethod.apply(_function).setUpperLimit(+Double.MAX_VALUE);
    }

    /** F's clone(): every function's copy() returns its own class. */
    @SuppressWarnings("unchecked")
    static <F extends AbsFunction> F copyOf(F function) {
        return (F) function.copy();
    }

    @Override
    public ParamToArgAdaptor<F> copy() {
        return new ParamToArgAdaptor<>(this);
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning.  ParamToArgAdaptor called with scalar argument\n");
        return 0;
    }

    public Parameter scaleFactor() {
        return _scaleFactor;
    }

    @Override
    public int dimensionality() {
        return _function.dimensionality() + 1;
    }

    @Override
    public double call(Argument a) {
        if (dimensionality() != a.dimension()) {
            StdStreams.cerr().print("Warning: ParamToArgAdaptor function/argument dimension mismatch\n");
            return 0;
        }
        final int dMinus = a.dimension() - 1;
        _parameterFetchMethod.apply(_function).setValue(_scaleFactor.getValue() * a.get(dMinus));
        return _function.call(a);
    }
}
