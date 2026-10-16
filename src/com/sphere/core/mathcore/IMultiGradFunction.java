package com.sphere.core.mathcore;

import java.util.function.ToDoubleFunction;

/**
 * ROOT::Math::IMultiGradFunction (IGradientFunctionMultiDim): a function of
 * several variables that computes its gradient too.
 */
public interface IMultiGradFunction extends IMultiGenFunction {

    /** The gradient at x into grad. */
    void Gradient(double[] x, double[] grad);

    @Override
    default boolean HasGradient() {
        return true;
    }

    /** The derivative along coordinate icoord. */
    default double Derivative(double[] x, int icoord) {
        final double[] g = new double[NDim()];
        Gradient(x, g);
        return g[icoord];
    }

    /** The value and the gradient. */
    default double FdF(double[] x, double[] grad) {
        Gradient(x, grad);
        return DoEval(x);
    }

    /** The gradient as the derivatives in x of a function. */
    @FunctionalInterface
    interface GradientFunction {
        void gradient(double[] x, double[] grad);
    }

    /** ROOT::Math::GradFunctor: a lambda and its gradient. */
    static IMultiGradFunction of(int ndim, ToDoubleFunction<double[]> f, GradientFunction g) {
        return new IMultiGradFunction() {
            @Override
            public int NDim() {
                return ndim;
            }

            @Override
            public double DoEval(double[] x) {
                return f.applyAsDouble(x);
            }

            @Override
            public void Gradient(double[] x, double[] grad) {
                g.gradient(x, grad);
            }
        };
    }
}
