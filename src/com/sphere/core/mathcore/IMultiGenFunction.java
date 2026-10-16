package com.sphere.core.mathcore;

import java.util.function.ToDoubleFunction;

/**
 * ROOT::Math::IMultiGenFunction (IBaseFunctionMultiDim): a function of NDim()
 * variables, evaluated by DoEval; it says whether it has a gradient
 * ({@link IMultiGradFunction}).
 */
public interface IMultiGenFunction {

    int NDim();

    double DoEval(double[] x);

    /** operator(): the value at x. */
    default double eval(double[] x) {
        return DoEval(x);
    }

    default boolean HasGradient() {
        return false;
    }

    /** ROOT::Math::Functor: a lambda of ndim variables as a function. */
    static IMultiGenFunction of(int ndim, ToDoubleFunction<double[]> f) {
        return new IMultiGenFunction() {
            @Override
            public int NDim() {
                return ndim;
            }

            @Override
            public double DoEval(double[] x) {
                return f.applyAsDouble(x);
            }
        };
    }
}
