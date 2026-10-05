package com.sphere.core.minuit2;

/** Makes the first state of a minimization from the user's parameters. */
public interface MinimumSeedGenerator {

    MinimumSeed generate(MnFcn fcn, GradientCalculator gc, MnUserParameterState st, MnStrategy stra);
}
