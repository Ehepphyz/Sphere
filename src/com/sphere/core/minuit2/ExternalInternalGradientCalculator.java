package com.sphere.core.minuit2;

/**
 * A gradient the user's function computes directly in Minuit's internal
 * parameters (GradientParameterSpace.Internal): taken as it is, with the
 * previous second derivatives and steps passed in for the function to use.
 */
public final class ExternalInternalGradientCalculator extends AnalyticalGradientCalculator {

    public ExternalInternalGradientCalculator(FCNBase fcn, MnUserTransformation trafo) {
        super(fcn, trafo);
    }

    @Override
    public FunctionGradient compute(MinimumParameters par) {
        final double[] parVec = par.vec().toArray();
        final double[] grad = gradFunc.gradient(parVec);
        final LAVector v = new LAVector(par.vec().size());
        for (int i = 0; i < par.vec().size(); i++) {
            v.set(i, grad[transformation.extOfInt(i)]);
        }
        new MnPrint("ExternalInternalGradientCalculator").debug("User given gradient in Minuit2", v);
        return new FunctionGradient(v);
    }

    @Override
    public FunctionGradient compute(MinimumParameters par, FunctionGradient functionGradient) {
        final double[] parVec = par.vec().toArray();
        final double[] previousGrad = functionGradient.grad().toArray();
        final double[] previousG2 = functionGradient.g2().toArray();
        final double[] previousGstep = functionGradient.gstep().toArray();
        final double[] grad = gradFunc.gradientWithPrevResult(parVec, previousGrad, previousG2, previousGstep, par.fval());
        final int n = par.vec().size();
        final LAVector v = new LAVector(n);
        final LAVector vG2 = new LAVector(n);
        final LAVector vGstep = new LAVector(n);
        for (int i = 0; i < n; i++) {
            final int ext = transformation.extOfInt(i);
            v.set(i, grad[ext]);
            vG2.set(i, previousG2[ext]);
            vGstep.set(i, previousGstep[ext]);
        }
        new MnPrint("ExternalInternalGradientCalculator").debug("User given gradient in Minuit2", v, "g2", vG2, "step size",
            vGstep);
        return new FunctionGradient(v, vG2, vGstep);
    }
}
