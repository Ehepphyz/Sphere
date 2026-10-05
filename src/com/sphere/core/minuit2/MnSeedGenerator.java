package com.sphere.core.minuit2;

/**
 * Migrad's seed: the function and its gradient at the start, an error
 * matrix from the user's covariance or the inverse second derivatives, a
 * line search away from any negative curvature, and with strategy 2 a full
 * Hesse.
 */
public final class MnSeedGenerator implements MinimumSeedGenerator {

    @Override
    public MinimumSeed generate(MnFcn fcn, GradientCalculator gc, MnUserParameterState st, MnStrategy stra) {
        if (gc instanceof AnalyticalGradientCalculator agc) {
            return callWithAnalyticalGradientCalculator(fcn, agc, st, stra);
        }
        final MnPrint print = new MnPrint("MnSeedGenerator");
        final int n = st.variableParameters();
        final MnMachinePrecision prec = st.precision();
        print.info("Computing seed using NumericalGradient calculator");
        print.debug(n, "free parameters, FCN pointer", fcn);
        final LAVector x = new LAVector(n);
        final double[] ip = st.intParameters();
        for (int i = 0; i < n; i++) x.set(i, ip[i]);
        final long t0 = System.nanoTime();
        final MinimumParameters pa = new MinimumParameters(x, new MnFcn.Caller(fcn).call(x));
        final FunctionGradient dgrad = gc.compute(pa);
        print.info("Evaluated function and gradient in", elapsed(t0));
        final LASymMatrix mat = new LASymMatrix(n);
        double dcovar = 1.;
        if (st.hasCovariance()) {
            for (int i = 0; i < n; i++) {
                mat.set(i, i, st.intCovariance().get(i, i) > prec.eps() ? st.intCovariance().get(i, i)
                    : dgrad.g2().get(i) > prec.eps() ? 1. / dgrad.g2().get(i) : 1.0);
                for (int j = i + 1; j < n; j++) mat.set(i, j, st.intCovariance().get(i, j));
            }
            dcovar = 0.;
        } else {
            for (int i = 0; i < n; i++) mat.set(i, i, dgrad.g2().get(i) > prec.eps() ? 1. / dgrad.g2().get(i) : 1.0);
        }
        final MinimumError err = new MinimumError(mat, dcovar);
        final double edm = new VariableMetricEDMEstimator().estimate(dgrad, err);
        MinimumState state = new MinimumState(pa, err, dgrad, edm, fcn.numOfCalls());
        print.info("Initial state:", new MnPrint.Oneline(state));
        if (!st.hasCovariance()) {
            final NegativeG2LineSearch ng2ls = new NegativeG2LineSearch();
            if (ng2ls.hasNegativeG2(dgrad, prec)) {
                print.debug("Negative G2 Found", "\n  point:", x, "\n  grad :", dgrad.grad(), "\n  g2   :", dgrad.g2());
                state = ng2ls.apply(fcn, state, gc, prec);
                print.info("Negative G2 found - new state:", state);
            }
        }
        if (stra.computeInitialHessian() && !st.hasCovariance()) {
            print.debug("calling MnHesse");
            final MinimumState tmp = new MnHesse(stra).compute(fcn, state, st.trafo(), 0);
            print.info("run Hesse - Initial seeding state:", tmp);
            return new MinimumSeed(tmp, st.trafo());
        }
        print.info("Initial state ", state);
        return new MinimumSeed(state, st.trafo());
    }

    static String elapsed(long t0) {
        return String.format(java.util.Locale.ROOT, "%.3f ms", (System.nanoTime() - t0) / 1e6);
    }

    private MinimumSeed callWithAnalyticalGradientCalculator(MnFcn fcn, AnalyticalGradientCalculator gc,
                                                             MnUserParameterState st, MnStrategy stra) {
        final MnPrint print = new MnPrint("MnSeedGenerator");
        if (!gc.canComputeG2()) {
            print.info("Using analytical (external) gradient calculator but cannot compute G2 - use then numerical gradient for G2");
            final Numerical2PGradientCalculator ngc = new Numerical2PGradientCalculator(fcn, st.trafo(), stra);
            return generate(fcn, ngc, st, stra);
        }
        if (gc.canComputeHessian()) print.info("Computing seed using analytical (external) gradients and Hessian calculator");
        else print.info("Computing seed using analytical (external) gradients and G2 calculator");
        final int n = st.variableParameters();
        final MnMachinePrecision prec = st.precision();
        final LAVector x = new LAVector(st.intParameters());
        final double fcnmin = new MnFcn.Caller(fcn).call(x);
        final MinimumParameters pa = new MinimumParameters(x, fcnmin);
        FunctionGradient grad = gc.compute(pa);
        double dcovar = 0;
        final LASymMatrix mat = new LASymMatrix(n);
        final boolean computedHessian = false;
        if (!grad.hasG2()) {
            final LASymMatrix hmat = new LASymMatrix(n);
            final boolean ret = gc.hessian(pa, hmat);
            if (!ret) print.error("Cannot compute G2 and Hessian");
            final LAVector g2 = new LAVector(n);
            for (int i = 0; i < n; i++) g2.set(i, hmat.get(i, i));
            grad = new FunctionGradient(grad.grad(), g2);
            print.debug("Computed analytical G2", g2);
        }
        if (st.hasCovariance()) {
            print.info("Using existing covariance matrix");
            for (int i = 0; i < n; i++) {
                mat.set(i, i, st.intCovariance().get(i, i) > prec.eps() ? st.intCovariance().get(i, i)
                    : grad.g2().get(i) > prec.eps() ? 1. / grad.g2().get(i) : 1.0);
                for (int j = i + 1; j < n; j++) mat.set(i, j, st.intCovariance().get(i, j));
            }
            dcovar = 0.;
        } else {
            for (int i = 0; i < n; i++) mat.set(i, i, grad.g2().get(i) > prec.eps() ? 1. / grad.g2().get(i) : 1.0);
            dcovar = 1.;
        }
        final MinimumError err = new MinimumError(mat, dcovar);
        final double edm = new VariableMetricEDMEstimator().estimate(grad, err);
        if (!grad.hasG2()) print.error("Cannot compute seed because G2 is not computed");
        MinimumState state = new MinimumState(pa, err, grad, edm, fcn.numOfCalls());
        if (!st.hasCovariance()) {
            final NegativeG2LineSearch ng2ls = new NegativeG2LineSearch();
            if (ng2ls.hasNegativeG2(grad, prec)) state = ng2ls.apply(fcn, state, gc, prec);
        }
        if (stra.computeInitialHessian() && !st.hasCovariance() && !computedHessian) {
            final MinimumState tmpState = new MnHesse(stra).compute(fcn, state, st.trafo(), 0);
            print.info("Compute full Hessian: Initial seeding state is ", tmpState);
            return new MinimumSeed(tmpState, st.trafo());
        }
        print.info("Initial seeding state ", state);
        return new MinimumSeed(state, st.trafo());
    }
}
