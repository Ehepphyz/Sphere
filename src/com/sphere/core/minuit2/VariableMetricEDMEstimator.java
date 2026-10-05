package com.sphere.core.minuit2;

/** The expected distance to the minimum, EDM = g^T V g / 2, V the inverse Hessian. */
public final class VariableMetricEDMEstimator {

    public double estimate(FunctionGradient g, MinimumError e) {
        if (e.invHessian().size() == 1) {
            return 0.5 * g.grad().get(0) * g.grad().get(0) * e.invHessian().get(0, 0);
        }
        final double rho = MnMatrix.similarity(g.grad(), e.invHessian());
        return 0.5 * rho;
    }
}
