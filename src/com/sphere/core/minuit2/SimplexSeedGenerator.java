package com.sphere.core.minuit2;

/** The seed of Simplex and Scan: the function at the start and the initial gradient from the errors, no derivative computed. */
public final class SimplexSeedGenerator implements MinimumSeedGenerator {

    @Override
    public MinimumSeed generate(MnFcn fcn, GradientCalculator gc, MnUserParameterState st, MnStrategy stra) {
        final int n = st.variableParameters();
        final MnMachinePrecision prec = st.precision();
        final LAVector x = new LAVector(st.intParameters());
        final double fcnmin = new MnFcn.Caller(fcn).call(x);
        final MinimumParameters pa = new MinimumParameters(x, fcnmin);
        final FunctionGradient dgrad = GradientCalculator.calculateInitialGradient(pa, st.trafo(), fcn.errorDef());
        final LASymMatrix mat = new LASymMatrix(n);
        final double dcovar = 1.;
        for (int i = 0; i < n; i++) {
            mat.set(i, i, Math.abs(dgrad.g2().get(i)) > prec.eps2() ? 1. / dgrad.g2().get(i) : 1.);
        }
        final MinimumError err = new MinimumError(mat, dcovar);
        final double edm = new VariableMetricEDMEstimator().estimate(dgrad, err);
        final MinimumState state = new MinimumState(pa, err, dgrad, edm, fcn.numOfCalls());
        return new MinimumSeed(state, st.trafo());
    }
}
