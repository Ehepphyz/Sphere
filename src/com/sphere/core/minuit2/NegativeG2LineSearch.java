package com.sphere.core.minuit2;

/**
 * When a second derivative is negative (the start is near a maximum along
 * that parameter), line searches along those parameters, downhill, until
 * every second derivative is positive; the error matrix is then the
 * diagonal of their inverses.
 */
public final class NegativeG2LineSearch {

    public MinimumState apply(MnFcn fcn, MinimumState st, GradientCalculator gc, MnMachinePrecision prec) {
        final MnPrint print = new MnPrint("NegativeG2LineSearch");
        final boolean negG2 = hasNegativeG2(st.gradient(), prec);
        if (!negG2) return st;
        print.info("Doing a NegativeG2LineSearch since one of the G2 component is negative");
        final int n = st.parameters().vec().size();
        FunctionGradient dgrad = st.gradient();
        MinimumParameters pa = st.parameters();
        boolean iterate;
        int iter = 0;
        final boolean hasGStep = !dgrad.isAnalytical();
        if (!dgrad.hasG2()) {
            print.error("Input gradient to NG2LS must have G2 already computed");
            return st;
        }
        do {
            iterate = false;
            for (int i = 0; i < n; i++) {
                if (dgrad.g2().get(i) <= 0) {
                    if (Math.abs(dgrad.vec().get(i)) < prec.eps() && Math.abs(dgrad.g2().get(i)) < prec.eps()) continue;
                    final LAVector step = new LAVector(n);
                    final MnLineSearch lsearch = new MnLineSearch();
                    if (dgrad.vec().get(i) < 0) step.set(i, hasGStep ? dgrad.gstep().get(i) : 1);
                    else step.set(i, hasGStep ? -dgrad.gstep().get(i) : -1);
                    final double gdel = step.get(i) * dgrad.vec().get(i);
                    print.debug("Iter", iter, "param", i, pa.vec().get(i), "grad2", dgrad.g2().get(i), "grad",
                        dgrad.vec().get(i), "grad step", step.get(i), " gdel ", gdel);
                    final MnParabola.Point pp = lsearch.search(fcn, pa, step, gdel, prec);
                    print.debug("Line search result", pp.x(), "f(0)", pa.fval(), "f(1)", pp.y());
                    step.scale(pp.x());
                    pa = new MinimumParameters(MnMatrix.add(pa.vec(), step), pp.y());
                    dgrad = gc.compute(pa, dgrad);
                    if (!dgrad.hasG2()) {
                        print.debug("Compute  G2 at the new point", pa.vec());
                        final LAVector g2 = new LAVector(n);
                        final boolean ret = gc.g2(pa, g2);
                        if (!ret) {
                            print.error("Cannot compute G2");
                            return st;
                        }
                        dgrad = new FunctionGradient(dgrad.grad(), g2);
                    }
                    print.debug("New result after Line search - iter", iter, "param", i, pa.vec().get(i), "step", step.get(i),
                        "new grad2", dgrad.g2().get(i), "new grad", dgrad.vec().get(i));
                    iterate = true;
                    break;
                }
            }
        } while (iter++ < 2 * n && iterate);
        print.debug("Approximate new covariance after NegativeG2LS using only G2");
        final LASymMatrix mat = new LASymMatrix(n);
        for (int i = 0; i < n; i++) {
            mat.set(i, i, Math.abs(dgrad.g2().get(i)) > prec.eps() ? 1. / dgrad.g2().get(i) : 1);
        }
        MinimumError err = new MinimumError(mat, 1.);
        final double edm = new VariableMetricEDMEstimator().estimate(dgrad, err);
        if (edm < 0) err = new MinimumError(mat, MinimumError.Status.MnNotPosDef);
        return new MinimumState(pa, err, dgrad, edm, fcn.numOfCalls());
    }

    public boolean hasNegativeG2(FunctionGradient grad, MnMachinePrecision prec) {
        for (int i = 0; i < grad.vec().size(); i++) {
            if (grad.g2().get(i) <= 0) return true;
        }
        return false;
    }
}
