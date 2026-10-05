package com.sphere.core.minuit2;

/**
 * The error matrix from the second derivatives computed by finite
 * differences (or given by the function): the diagonal in cycles that adapt
 * each step to the curvature, the off-diagonal terms from one more call each
 * (or four, central differences, at strategy 3), then made positive-definite
 * and inverted.
 */
public final class MnHesse {

    private final MnStrategy strategy;

    public MnHesse() {
        strategy = new MnStrategy(1);
    }

    public MnHesse(int stra) {
        strategy = new MnStrategy(stra);
    }

    public MnHesse(MnStrategy stra) {
        strategy = new MnStrategy(stra);
    }

    public int ncycles() {
        return strategy.hessianNCycles();
    }

    public double tolerstp() {
        return strategy.hessianStepTolerance();
    }

    public double tolerG2() {
        return strategy.hessianG2Tolerance();
    }

    /** Hesse at the user's parameters: a new state with the covariance. */
    public MnUserParameterState apply(FCNBase fcn, MnUserParameterState state, int maxcalls) {
        final int n = state.variableParameters();
        final MnFcn mfcn = new MnFcn(fcn, state.trafo(), state.nfcn());
        final LAVector x = new LAVector(state.intParameters());
        final double amin = new MnFcn.Caller(mfcn).call(x);
        final MinimumParameters par = new MinimumParameters(x, amin);
        if (fcn.hasGradient()) {
            final MinimumState tmp = computeAnalytical(fcn, new MinimumState(par, new MinimumError(new LASymMatrix(n), 1.),
                new FunctionGradient(n), state.edm(), state.nfcn()), state.trafo());
            return new MnUserParameterState(tmp, fcn.up(), state.trafo());
        }
        final FunctionGradient g = new Numerical2PGradientCalculator(mfcn, state.trafo(), strategy).compute(par);
        final MinimumState tmp = computeNumerical(mfcn, new MinimumState(par, new MinimumError(new LASymMatrix(n), 1.), g,
            state.edm(), state.nfcn()), state.trafo(), maxcalls, strategy);
        return new MnUserParameterState(tmp, fcn.up(), state.trafo());
    }

    public MnUserParameterState apply(FCNBase fcn, MnUserParameterState state) {
        return apply(fcn, state, 0);
    }

    /** Hesse at a minimum: its state is appended to it. */
    public void apply(FCNBase fcn, FunctionMinimum min, int maxcalls) {
        final MnUserTransformation trafo = min.userState().trafo();
        final MnFcn mfcn = new MnFcn(fcn, trafo, min.nfcn());
        final MinimumState st = compute(mfcn, min.state(), trafo, maxcalls);
        min.add(st);
    }

    public void apply(FCNBase fcn, FunctionMinimum min) {
        apply(fcn, min, 0);
    }

    /** Hesse at an internal state. */
    public MinimumState compute(MnFcn mfcn, MinimumState st, MnUserTransformation trafo, int maxcalls) {
        if (st.gradient().isAnalytical()) {
            if (mfcn.fcn().hasGradient() && mfcn.fcn().hasHessian()) {
                return computeAnalytical(mfcn.fcn(), st, trafo);
            }
        }
        return computeNumerical(mfcn, st, trafo, maxcalls, strategy);
    }

    static MinimumState computeAnalytical(FCNBase fcn, MinimumState st, MnUserTransformation trafo) {
        final int n = st.parameters().vec().size();
        LASymMatrix vhmat = new LASymMatrix(n);
        final MnPrint print = new MnPrint("MnHesse");
        final long t0 = System.nanoTime();
        final MnMachinePrecision prec = trafo.precision();
        final AnalyticalGradientCalculator hc = fcn.gradParameterSpace() == FCNBase.GradientParameterSpace.Internal
            ? new ExternalInternalGradientCalculator(fcn, trafo) : new AnalyticalGradientCalculator(fcn, trafo);
        final boolean ret = hc.hessian(st.parameters(), vhmat);
        if (!ret) {
            print.error("Error computing analytical Hessian. MnHesse fails and will return a null matrix");
            print.info("Done after", MnSeedGenerator.elapsed(t0));
            return new MinimumState(st.parameters(), new MinimumError(vhmat, MinimumError.Status.MnHesseFailed),
                st.gradient(), st.edm(), st.nfcn());
        }
        final LAVector g2 = new LAVector(n);
        for (int i = 0; i < n; i++) g2.set(i, vhmat.get(i, i));
        final FunctionGradient gr = new FunctionGradient(st.gradient().grad(), g2);
        print.debug("Original error matrix", vhmat);
        final MinimumError tmpErr = new MnPosDef().apply(new MinimumError(vhmat, 1.), prec);
        vhmat = tmpErr.invHessian().copy();
        print.debug("PosDef error matrix", vhmat);
        final int ifail = MnMatrix.invert(vhmat);
        if (ifail != 0) {
            print.warn("Matrix inversion fails; will return diagonal matrix");
            final LASymMatrix tmpsym = new LASymMatrix(vhmat.nrow());
            for (int j = 0; j < n; j++) tmpsym.set(j, j, 1. / g2.get(j));
            print.info("Done after", MnSeedGenerator.elapsed(t0));
            return new MinimumState(st.parameters(), new MinimumError(tmpsym, MinimumError.Status.MnInvertFailed), gr,
                st.edm(), st.nfcn());
        }
        final VariableMetricEDMEstimator estim = new VariableMetricEDMEstimator();
        if (tmpErr.isMadePosDef()) {
            final MinimumError err = new MinimumError(vhmat, MinimumError.Status.MnMadePosDef);
            final double edm = estim.estimate(gr, err);
            print.info("Done after", MnSeedGenerator.elapsed(t0));
            return new MinimumState(st.parameters(), err, gr, edm, st.nfcn());
        }
        final MinimumError err = new MinimumError(vhmat, 0.);
        final double edm = estim.estimate(gr, err);
        print.debug("Hessian is ACCURATE. New state:", "\n  First derivative:", st.gradient().grad(),
            "\n  Covariance matrix:", vhmat, "\n  Edm:", edm);
        print.info("Done after", MnSeedGenerator.elapsed(t0));
        return new MinimumState(st.parameters(), err, gr, edm, st.nfcn());
    }

    static MinimumState computeNumerical(MnFcn mfcn, MinimumState st, MnUserTransformation trafo, int maxcalls,
                                         MnStrategy strat) {
        final MnPrint print = new MnPrint("MnHesse");
        final long t0 = System.nanoTime();
        try {
            return numerical(mfcn, st, trafo, maxcalls, strat, print);
        } finally {
            print.info("Done after", MnSeedGenerator.elapsed(t0));
        }
    }

    private static MinimumState numerical(MnFcn mfcn, MinimumState st, MnUserTransformation trafo, int maxcalls,
                                          MnStrategy strat, MnPrint print) {
        final MnFcn.Caller mfcnCaller = new MnFcn.Caller(mfcn);
        final MnMachinePrecision prec = trafo.precision();
        final double amin = mfcnCaller.call(st.vec());
        final double aimsag = Math.sqrt(prec.eps2()) * (Math.abs(amin) + mfcn.up());
        final int n = st.parameters().vec().size();
        if (maxcalls == 0) maxcalls = 200 + 100 * n + 5 * n * n;
        LASymMatrix vhmat = new LASymMatrix(n);
        LAVector g2 = st.gradient().g2().copy();
        LAVector gst = st.gradient().gstep().copy();
        LAVector grd = st.gradient().grad().copy();
        LAVector dirin = st.gradient().gstep().copy();
        final LAVector yy = new LAVector(n);
        if (st.gradient().isAnalytical()) {
            print.info("Using analytical gradient but a numerical Hessian calculator - it could be not optimal");
            final Numerical2PGradientCalculator igc = new Numerical2PGradientCalculator(mfcn, trafo, strat);
            final FunctionGradient tmp = igc.compute(st.parameters());
            gst = tmp.gstep().copy();
            dirin = tmp.gstep().copy();
            g2 = tmp.g2().copy();
            print.warn("Analytical calculator ", grd, " numerical ", tmp.grad(), " g2 ", g2);
        }
        final LAVector x = st.parameters().vec().copy();
        print.debug("Gradient is", st.gradient().isAnalytical() ? "analytical" : "numerical", "\n  point:", x,
            "\n  fcn  :", amin, "\n  grad :", grd, "\n  step :", gst, "\n  g2   :", g2);
        for (int i = 0; i < n; i++) {
            final double xtf = x.get(i);
            final double dmin = 8. * prec.eps2() * (Math.abs(xtf) + prec.eps2());
            double d = Math.abs(gst.get(i));
            if (d < dmin) d = dmin;
            print.debug("Derivative parameter", i, "d =", d, "dmin =", dmin);
            for (int icyc = 0; icyc < strat.hessianNCycles(); icyc++) {
                double sag = 0.;
                double fs1 = 0.;
                double fs2 = 0.;
                boolean found = false;
                for (int multpy = 0; multpy < 5; multpy++) {
                    x.set(i, xtf + d);
                    fs1 = mfcnCaller.call(x);
                    x.set(i, xtf - d);
                    fs2 = mfcnCaller.call(x);
                    x.set(i, xtf);
                    sag = 0.5 * (fs1 + fs2 - 2. * amin);
                    print.debug("cycle", icyc, "mul", multpy, "\tsag =", sag, "d =", d);
                    if (sag != 0) {
                        found = true;
                        break;
                    }
                    // the C++ asks the parameter of external number i here
                    if (trafo.parameter(i).hasLimits()) {
                        if (d > 0.5) break;
                        d *= 10.;
                        if (d > 0.5) d = 0.51;
                        continue;
                    }
                    d *= 10.;
                }
                if (!found) {
                    print.warn("2nd derivative zero for parameter", trafo.name(trafo.extOfInt(i)),
                        "; MnHesse fails and will return diagonal matrix");
                    for (int j = 0; j < n; j++) {
                        final double tmp = g2.get(j) < prec.eps2() ? 1. : 1. / g2.get(j);
                        vhmat.set(j, j, tmp < prec.eps2() ? 1. : tmp);
                    }
                    return new MinimumState(st.parameters(), new MinimumError(vhmat, MinimumError.Status.MnHesseFailed),
                        st.gradient(), st.edm(), mfcn.numOfCalls());
                }
                final double g2bfor = g2.get(i);
                g2.set(i, 2. * sag / (d * d));
                grd.set(i, (fs1 - fs2) / (2. * d));
                gst.set(i, d);
                dirin.set(i, d);
                yy.set(i, fs1);
                final double dlast = d;
                d = Math.sqrt(2. * aimsag / Math.abs(g2.get(i)));
                if (trafo.parameter(i).hasLimits()) d = Cxx.min(0.5, d);
                if (d < dmin) d = dmin;
                print.debug("g1 =", grd.get(i), "g2 =", g2.get(i), "step =", gst.get(i), "d =", d, "diffd =",
                    Math.abs(d - dlast) / d, "diffg2 =", Math.abs(g2.get(i) - g2bfor) / g2.get(i));
                if (Math.abs((d - dlast) / d) < strat.hessianStepTolerance()) break;
                if (Math.abs((g2.get(i) - g2bfor) / g2.get(i)) < strat.hessianG2Tolerance()) break;
                d = Cxx.min(d, 10. * dlast);
                d = Cxx.max(d, 0.1 * dlast);
            }
            vhmat.set(i, i, g2.get(i));
            if (mfcn.numOfCalls() > maxcalls) {
                print.warn("Maximum number of allowed function calls exhausted; will return diagonal matrix");
                for (int j = 0; j < n; j++) {
                    final double tmp = g2.get(j) < prec.eps2() ? 1. : 1. / g2.get(j);
                    vhmat.set(j, j, tmp < prec.eps2() ? 1. : tmp);
                }
                return new MinimumState(st.parameters(), new MinimumError(vhmat, MinimumError.Status.MnReachedCallLimit),
                    st.gradient(), st.edm(), mfcn.numOfCalls());
            }
        }
        print.debug("Second derivatives", g2);
        if (strat.refineGradientInHessian()) {
            final HessianGradientCalculator hgc = new HessianGradientCalculator(mfcn, trafo, strat);
            final FunctionGradient gr = hgc.compute(st.parameters(), new FunctionGradient(grd, g2, gst));
            grd = gr.grad().copy();
            gst = gr.gstep().copy();
        }
        final boolean doCentralFD = strat.hessianCentralFDMixedDerivatives() != 0;
        if (n > 0) {
            final int end = n * (n - 1) / 2;
            int offsetVect = 0;
            for (int in = 0; in < end; in++) {
                final int i = (in + offsetVect) / (n - 1);
                if ((in + offsetVect) % (n - 1) == 0) offsetVect += i;
                final int j = (in + offsetVect) % (n - 1) + 1;
                if ((i + 1) == j || in == 0) x.set(i, x.get(i) + dirin.get(i));
                if (mfcn.fcn().secondDerivativeAlwaysVanishes(trafo.extOfInt(i), trafo.extOfInt(j))) {
                    vhmat.set(i, j, 0.);
                } else if (!doCentralFD) {
                    x.set(j, x.get(j) + dirin.get(j));
                    final double fs1 = mfcnCaller.call(x);
                    final double elem = (fs1 + amin - yy.get(i) - yy.get(j)) / (dirin.get(i) * dirin.get(j));
                    vhmat.set(i, j, elem);
                    x.set(j, x.get(j) - dirin.get(j));
                } else {
                    x.set(j, x.get(j) + dirin.get(j));
                    final double fs1 = mfcnCaller.call(x);
                    x.set(i, x.get(i) - dirin.get(i));
                    x.set(i, x.get(i) - dirin.get(i));
                    final double fs3 = mfcnCaller.call(x);
                    x.set(j, x.get(j) - dirin.get(j));
                    x.set(j, x.get(j) - dirin.get(j));
                    final double fs4 = mfcnCaller.call(x);
                    x.set(i, x.get(i) + dirin.get(i));
                    x.set(i, x.get(i) + dirin.get(i));
                    final double fs2 = mfcnCaller.call(x);
                    x.set(j, x.get(j) + dirin.get(j));
                    final double elem = (fs1 - fs2 - fs3 + fs4) / (4. * dirin.get(i) * dirin.get(j));
                    vhmat.set(i, j, elem);
                }
                if (j % (n - 1) == 0 || in == end - 1) x.set(i, x.get(i) - dirin.get(i));
            }
        }
        print.debug("Original error matrix", vhmat);
        final MinimumError tmpErr = new MnPosDef().apply(new MinimumError(vhmat, 1.), prec);
        if (strat.hessianForcePosDef() != 0) vhmat = tmpErr.invHessian().copy();
        print.debug("PosDef error matrix", vhmat);
        final int ifail = MnMatrix.invert(vhmat);
        if (ifail != 0) {
            print.warn("Matrix inversion fails; will return diagonal matrix");
            final LASymMatrix tmpsym = new LASymMatrix(vhmat.nrow());
            for (int j = 0; j < n; j++) {
                final double tmp = g2.get(j) < prec.eps2() ? 1. : 1. / g2.get(j);
                tmpsym.set(j, j, tmp < prec.eps2() ? 1. : tmp);
            }
            return new MinimumState(st.parameters(), new MinimumError(tmpsym, MinimumError.Status.MnInvertFailed),
                st.gradient(), st.edm(), mfcn.numOfCalls());
        }
        final FunctionGradient gr = new FunctionGradient(grd, g2, gst);
        final VariableMetricEDMEstimator estim = new VariableMetricEDMEstimator();
        if (tmpErr.isMadePosDef()) {
            final MinimumError err = new MinimumError(vhmat, strat.hessianForcePosDef() != 0
                ? MinimumError.Status.MnMadePosDef : MinimumError.Status.MnNotPosDef);
            final double edm = estim.estimate(gr, err);
            return new MinimumState(st.parameters(), err, gr, edm, mfcn.numOfCalls());
        }
        final MinimumError err = new MinimumError(vhmat, 0.);
        final double edm = estim.estimate(gr, err);
        print.debug("Hessian is ACCURATE. New state:", "\n  First derivative:", grd, "\n  Second derivative:", g2,
            "\n  Gradient step:", gst, "\n  Covariance matrix:", vhmat, "\n  Edm:", edm);
        return new MinimumState(st.parameters(), err, gr, edm, mfcn.numOfCalls());
    }
}
