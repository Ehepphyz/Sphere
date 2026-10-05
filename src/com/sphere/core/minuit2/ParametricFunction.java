package com.sphere.core.minuit2;

/**
 * A model with parameters: a function of coordinates x whose shape the
 * parameters set (a Gaussian of mean and width, say). Its gradient with
 * respect to the parameters is, by default, numerical (Minuit2's two-point
 * calculator with errors 0.1).
 */
public abstract class ParametricFunction implements FCNBase {

    /** The parameters (mutable, as in the C++, where the hierarchy is const). */
    protected double[] par;

    protected ParametricFunction(double[] params) {
        par = params.clone();
    }

    protected ParametricFunction(int nparams) {
        par = new double[nparams];
    }

    public void setParameters(double[] params) {
        if (params.length != par.length) throw new IllegalArgumentException("parameter count");
        par = params.clone();
    }

    public double[] getParameters() {
        return par;
    }

    public int numberOfParameters() {
        return par.length;
    }

    /** The function at coordinates x, with the current parameters. */
    @Override
    public abstract double value(double[] x);

    /** The function at x with the parameters given (they become the current ones). */
    public double value(double[] x, double[] params) {
        setParameters(params);
        return value(x);
    }

    /** The gradient with respect to the coordinates handed in as x (the parameters, for a model). */
    public double[] getGradient(double[] x) {
        final MnFcn mfcn = new MnFcn(this);
        final MnStrategy strategy = new MnStrategy(1);
        final double[] err = new double[x.length];
        java.util.Arrays.fill(err, 0.1);
        final MnUserParameterState st = new MnUserParameterState(x, err);
        final Numerical2PGradientCalculator gc = new Numerical2PGradientCalculator(mfcn, st.trafo(), strategy);
        final LAVector xVec = new LAVector(x);
        final FunctionGradient g = gc.compute(new MinimumParameters(xVec, new MnFcn.Caller(mfcn).call(xVec)));
        return g.vec().toArray();
    }
}
