package com.sphere.core.minuit2;

/** Migrad, with Simplex then Migrad again when Migrad alone fails. */
public final class MnMinimize extends MnApplication {

    private final CombinedMinimizer minimizer = new CombinedMinimizer();

    public MnMinimize(FCNBase fcn, MnUserParameterState par, MnStrategy str) {
        super(fcn, par, str, 0);
    }

    public MnMinimize(FCNBase fcn, MnUserParameterState par) {
        this(fcn, par, new MnStrategy(1));
    }

    public MnMinimize(FCNBase fcn, MnUserParameters par) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(1));
    }

    public MnMinimize(FCNBase fcn, MnUserParameters par, int stra) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(stra));
    }

    @Override
    public ModularFunctionMinimizer minimizer() {
        return minimizer;
    }
}
