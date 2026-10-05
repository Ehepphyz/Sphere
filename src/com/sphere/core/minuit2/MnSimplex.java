package com.sphere.core.minuit2;

/** Minimization by the simplex method: function values only, no derivatives. */
public final class MnSimplex extends MnApplication {

    private final SimplexMinimizer minimizer = new SimplexMinimizer();

    public MnSimplex(FCNBase fcn, MnUserParameterState par, MnStrategy str) {
        super(fcn, par, str, 0);
    }

    public MnSimplex(FCNBase fcn, MnUserParameterState par) {
        this(fcn, par, new MnStrategy(1));
    }

    public MnSimplex(FCNBase fcn, MnUserParameters par) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(1));
    }

    public MnSimplex(FCNBase fcn, MnUserParameters par, int stra) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(stra));
    }

    public MnSimplex(FCNBase fcn, double[] par, double[] err) {
        this(fcn, new MnUserParameterState(par, err), new MnStrategy(1));
    }

    @Override
    public ModularFunctionMinimizer minimizer() {
        return minimizer;
    }
}
