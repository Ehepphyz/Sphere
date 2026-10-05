package com.sphere.core.minuit2;

/** Fumili: for fits (chi-square or likelihood) through a {@link FumiliFCNBase}. */
public final class FumiliMinimizer extends ModularFunctionMinimizer {

    private final MnSeedGenerator seedGen = new MnSeedGenerator();
    private final FumiliBuilder builder = new FumiliBuilder();

    @Override
    public MinimumSeedGenerator seedGenerator() {
        return seedGen;
    }

    @Override
    public FumiliBuilder builder() {
        return builder;
    }

    /** "tr" (trust region, the default), "trs" (scaled trust region) or "ls" (line search). */
    public void setMethod(String method) {
        switch (method) {
            case "tr" -> builder.setMethod(FumiliBuilder.FumiliMethodType.kTrustRegion);
            case "ls" -> builder.setMethod(FumiliBuilder.FumiliMethodType.kLineSearch);
            case "trs" -> builder.setMethod(FumiliBuilder.FumiliMethodType.kTrustRegionScaled);
            default -> {
            }
        }
    }

    @Override
    public FunctionMinimum minimize(FCNBase fcn, MnUserParameterState st, MnStrategy strategy, int maxfcn, double toler) {
        final MnPrint print = new MnPrint("FumiliMinimizer::Minimize");
        final MnFcn mfcn = new MnFcn(fcn, st.trafo());
        final int npar = st.variableParameters();
        if (maxfcn == 0) maxfcn = 200 + 100 * npar + 5 * npar * npar;
        if (!(fcn instanceof FumiliFCNBase fumiliFcn)) {
            print.error("Wrong FCN type; try to use default minimizer");
            return new FunctionMinimum(new MinimumSeed(new MinimumState(0), st.trafo()), fcn.up());
        }
        final FumiliGradientCalculator fgc = new FumiliGradientCalculator(fumiliFcn, st.trafo(), npar);
        if (fcn.hasGradient()) print.debug("Using FumiliMinimizer with analytical gradients");
        else print.debug("Using FumiliMinimizer with numerical gradients");
        final LAVector x = new LAVector(st.intParameters());
        final double fcnmin = new MnFcn.Caller(mfcn).call(x);
        final MinimumParameters pa = new MinimumParameters(x, fcnmin);
        final FunctionGradient grad = fgc.compute(pa);
        final FumiliErrorUpdator errUpdator = new FumiliErrorUpdator();
        final MinimumError err = errUpdator.update(new MinimumState(0), pa, fgc, 0.);
        final MinimumSeed mnseeds = new MinimumSeed(new MinimumState(pa, err, grad, 1.E10, 1), st.trafo());
        return minimize(mfcn, fgc, mnseeds, strategy, maxfcn, toler);
    }
}
