package com.sphere.core.minuit2;

/** A function that gives its gradient (in external parameters, the same number as the parameters). */
public interface FCNGradientBase extends FCNBase {

    @Override
    default boolean hasGradient() {
        return true;
    }

    @Override
    double[] gradient(double[] v);
}
