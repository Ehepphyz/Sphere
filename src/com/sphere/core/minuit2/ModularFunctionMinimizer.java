package com.sphere.core.minuit2;

/**
 * A minimizer made of a seed generator and a builder: it chooses the
 * gradient calculator (numerical, or the function's own, in external or
 * internal parameters), the call limit (200 + 100 n + 5 n^2 by default) and
 * scales the tolerance by Up.
 */
public abstract class ModularFunctionMinimizer {

    public abstract MinimumSeedGenerator seedGenerator();

    public abstract MinimumBuilder builder();

    public FunctionMinimum minimize(FCNBase fcn, MnUserParameterState st, MnStrategy strategy, int maxfcn, double toler) {
        final MnFcn mfcn = new MnFcn(fcn, st.trafo());
        final GradientCalculator gc;
        if (!fcn.hasGradient()) {
            gc = new Numerical2PGradientCalculator(mfcn, st.trafo(), strategy);
        } else if (fcn.gradParameterSpace() == FCNBase.GradientParameterSpace.Internal) {
            gc = new ExternalInternalGradientCalculator(fcn, st.trafo());
        } else {
            gc = new AnalyticalGradientCalculator(fcn, st.trafo());
        }
        final int npar = st.variableParameters();
        if (maxfcn == 0) maxfcn = 200 + 100 * npar + 5 * npar * npar;
        final MinimumSeed mnseeds = seedGenerator().generate(mfcn, gc, st, strategy);
        return minimize(mfcn, gc, mnseeds, strategy, maxfcn, toler);
    }

    public FunctionMinimum minimize(FCNBase fcn, MnUserParameterState st) {
        return minimize(fcn, st, new MnStrategy(1), 0, 0.1);
    }

    public FunctionMinimum minimize(MnFcn mfcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy, int maxfcn,
                                    double toler) {
        final MnPrint print = new MnPrint("ModularFunctionMinimizer");
        final MinimumBuilder mb = builder();
        double effectiveToler = toler * mfcn.up();
        final double eps = new MnMachinePrecision().eps2();
        if (effectiveToler < eps) effectiveToler = eps;
        if (mfcn.numOfCalls() >= maxfcn) {
            print.warn("Stop before iterating - call limit already exceeded");
            return new FunctionMinimum(seed, java.util.List.of(seed.state()), mfcn.up(), FunctionMinimum.Status.MnReachedCallLimit);
        }
        return mb.minimum(mfcn, gc, seed, strategy, maxfcn, effectiveToler);
    }
}
