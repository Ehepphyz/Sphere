package com.sphere.core.minuit2;

/** Updates the inverse Hessian from one step: the change of the point and of the gradient. */
public interface MinimumErrorUpdator {

    MinimumError update(MinimumState s0, MinimumParameters p1, FunctionGradient g1);
}
