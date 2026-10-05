package com.sphere.core.minuit2;

/** Minimization of a fit function by Fumili. */
public final class MnFumiliMinimize extends MnApplication {

    private final FumiliMinimizer minimizer = new FumiliMinimizer();
    private final FumiliFCNBase fumili;

    public MnFumiliMinimize(FumiliFCNBase fcn, MnUserParameterState par, MnStrategy str) {
        super(fcn, par, str, 0);
        this.fumili = fcn;
    }

    public MnFumiliMinimize(FumiliFCNBase fcn, MnUserParameterState par) {
        this(fcn, par, new MnStrategy(1));
    }

    public MnFumiliMinimize(FumiliFCNBase fcn, MnUserParameters par) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(1));
    }

    @Override
    public FumiliMinimizer minimizer() {
        return minimizer;
    }

    @Override
    public FumiliFCNBase fcnbase() {
        return fumili;
    }

    @Override
    public FunctionMinimum minimize(int maxfcn, double toler) {
        if (!state.isValid()) throw new IllegalStateException("the parameter state is not valid");
        final int npar = state().variableParameters();
        if (maxfcn == 0) maxfcn = 200 + 100 * npar + 5 * npar * npar;
        final FunctionMinimum min = minimizer().minimize(fcnbase(), state, strategy, maxfcn, toler);
        numCall += min.nfcn();
        state = new MnUserParameterState(min.userState());
        return min;
    }
}
