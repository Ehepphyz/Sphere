package com.sphere.core.minuit2;

/** Where a minimization starts: the first state and the transformation it lives in. */
public final class MinimumSeed {

    private final MinimumState state;
    private final MnUserTransformation trafo;
    private final boolean valid;

    public MinimumSeed(MinimumState state, MnUserTransformation trafo) {
        this.state = state;
        this.trafo = new MnUserTransformation(trafo);
        this.valid = true;
    }

    public MinimumState state() {
        return state;
    }

    public MinimumParameters parameters() {
        return state.parameters();
    }

    public MinimumError error() {
        return state.error();
    }

    public FunctionGradient gradient() {
        return state.gradient();
    }

    public MnUserTransformation trafo() {
        return trafo;
    }

    public MnMachinePrecision precision() {
        return trafo.precision();
    }

    public double fval() {
        return state.fval();
    }

    public double edm() {
        return state.edm();
    }

    public int nfcn() {
        return state.nfcn();
    }

    public boolean isValid() {
        return valid;
    }
}
