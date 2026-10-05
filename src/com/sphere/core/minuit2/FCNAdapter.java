package com.sphere.core.minuit2;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * A function to minimize made of Java functions: the value, and optionally
 * the gradient, the second derivatives, the Hessian (n x n row by row).
 */
public final class FCNAdapter implements FCNBase {

    /** Fills the gradient at x. */
    public interface Gradient {
        void eval(double[] x, double[] grad);
    }

    private double up;
    private final ToDoubleFunction<double[]> func;
    private Gradient gradFunc;
    private Function<double[], double[]> g2Func;
    private BiFunction<double[], double[], Boolean> hessianFunc;
    private BiPredicate<Integer, Integer> secondDerivAlwaysVanishes;
    private double[] hessianStore = new double[0];

    public FCNAdapter(ToDoubleFunction<double[]> f, double up) {
        this.func = f;
        this.up = up;
    }

    public FCNAdapter(ToDoubleFunction<double[]> f) {
        this(f, 1.);
    }

    @Override
    public boolean hasGradient() {
        return gradFunc != null;
    }

    @Override
    public boolean hasG2() {
        return g2Func != null;
    }

    @Override
    public boolean hasHessian() {
        return hessianFunc != null;
    }

    @Override
    public double value(double[] v) {
        return func.applyAsDouble(v);
    }

    @Override
    public double up() {
        return up;
    }

    @Override
    public double[] gradient(double[] v) {
        final double[] output = new double[v.length];
        gradFunc.eval(v, output);
        return output;
    }

    @Override
    public double[] g2(double[] x) {
        if (g2Func != null) return g2Func.apply(x);
        if (hessianFunc != null) {
            final int n = x.length;
            final double[] output = new double[n];
            if (hessianStore.length == 0) hessianStore = new double[n * n];
            hessianFunc.apply(x, hessianStore);
            for (int i = 0; i < n; i++) output[i] = hessianStore[i * n + i];
            return output;
        }
        return new double[0];
    }

    @Override
    public double[] hessian(double[] x) {
        if (hessianFunc != null) {
            final int n = x.length;
            final double[] output = new double[n * n];
            final boolean ret = hessianFunc.apply(x, output);
            if (!ret) {
                hessianFunc = null;
                return new double[0];
            }
            return output;
        }
        return new double[0];
    }

    public void setGradientFunction(Gradient f) {
        gradFunc = f;
    }

    public void setG2Function(Function<double[], double[]> f) {
        g2Func = f;
    }

    /** The Hessian into an n x n array; false when it cannot be had (it is then not asked again). */
    public void setHessianFunction(BiFunction<double[], double[], Boolean> f) {
        hessianFunc = f;
    }

    @Override
    public void setErrorDef(double up) {
        this.up = up;
    }

    @Override
    public boolean secondDerivativeAlwaysVanishes(int i, int j) {
        return secondDerivAlwaysVanishes != null && secondDerivAlwaysVanishes.test(i, j);
    }

    public void setSecondDerivativeAlwaysVanishesFunc(BiPredicate<Integer, Integer> f) {
        secondDerivAlwaysVanishes = f;
    }
}
