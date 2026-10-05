package com.sphere.core.minuit2;

/**
 * The function to minimize, as Minuit2 calls it: the value at the external
 * parameters, and Up, the change of the value that makes one standard
 * deviation (1 for a chi-square, 0.5 for a negative log-likelihood). A
 * function may also give its gradient, its second derivatives or its
 * Hessian; Minuit2 then uses them instead of finite differences.
 */
public interface FCNBase {

    /** Where a given gradient lives: the user's parameters or Minuit's internal ones. */
    enum GradientParameterSpace { External, Internal }

    /** The function at the parameters (all of them, fixed and constant ones included). */
    double value(double[] v);

    double up();

    default double errorDef() {
        return up();
    }

    default void setErrorDef(double up) {
    }

    default boolean hasGradient() {
        return false;
    }

    default double[] gradient(double[] v) {
        return new double[0];
    }

    /** The gradient knowing the previous one, its second derivatives and steps (which it may update in place). */
    default double[] gradientWithPrevResult(double[] parameters, double[] previousGrad, double[] previousG2,
                                            double[] previousGstep, double fValAtParameters) {
        return gradient(parameters);
    }

    default GradientParameterSpace gradParameterSpace() {
        return GradientParameterSpace.External;
    }

    default double[] g2(double[] v) {
        return new double[0];
    }

    /** The Hessian, row by row (n x n), or nothing. */
    default double[] hessian(double[] v) {
        return new double[0];
    }

    default boolean hasHessian() {
        return false;
    }

    default boolean hasG2() {
        return false;
    }

    /** True when d2f/dxi dxj is known to vanish: Hesse then skips it. */
    default boolean secondDerivativeAlwaysVanishes(int i, int j) {
        return false;
    }
}
