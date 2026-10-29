package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.function.Function;

/**
 * CLHEP's Genfun::SymToArgAdaptor (SymToArgAdaptor.hh, .icc): F evaluated at f_expression(a), with one of
 * its parameters set to p_expression(a) (unbounded).
 */
public class SymToArgAdaptor<F extends AbsFunction> extends AbsFunction {

    private final F _function;
    private final AbsFunction _f_expression;
    private final Function<F, Parameter> _parameterFetchMethod;
    private final AbsFunction _p_expression;

    public SymToArgAdaptor(F function, AbsFunction f_expression, Function<F, Parameter> parameterFetchMethod,
                           AbsFunction p_expression) {
        _function = ParamToArgAdaptor.copyOf(function);
        _f_expression = f_expression.copy();
        _parameterFetchMethod = parameterFetchMethod;
        _p_expression = p_expression.copy();
        _parameterFetchMethod.apply(_function).setLowerLimit(-Double.MAX_VALUE);
        _parameterFetchMethod.apply(_function).setUpperLimit(+Double.MAX_VALUE);
    }

    public SymToArgAdaptor(SymToArgAdaptor<F> right) {
        _function = ParamToArgAdaptor.copyOf(right._function);
        _f_expression = right._f_expression.copy();
        _parameterFetchMethod = right._parameterFetchMethod;
        _p_expression = right._p_expression.copy();
        _parameterFetchMethod.apply(_function).setLowerLimit(-Double.MAX_VALUE);
        _parameterFetchMethod.apply(_function).setUpperLimit(+Double.MAX_VALUE);
    }

    @Override
    public SymToArgAdaptor<F> copy() {
        return new SymToArgAdaptor<>(this);
    }

    @Override
    public double call(double x) {
        StdStreams.cerr().print("Warning.  SymToArgAdaptor called with scalar argument\n");
        return 0;
    }

    @Override
    public int dimensionality() {
        return _f_expression.dimensionality();
    }

    @Override
    public double call(Argument a) {
        if (dimensionality() != a.dimension()) {
            StdStreams.cerr().print("Warning: SymToArgAdaptor function/argument dimension mismatch\n");
            return 0;
        }
        final double pVal = _p_expression.call(a);
        _parameterFetchMethod.apply(_function).setValue(pVal);
        return _function.call(_f_expression.call(a));
    }
}
