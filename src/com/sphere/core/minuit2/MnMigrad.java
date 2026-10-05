package com.sphere.core.minuit2;

/** Minimization by the variable metric method, MIGRAD: the method of choice. */
public final class MnMigrad extends MnApplication {

    private final VariableMetricMinimizer minimizer = new VariableMetricMinimizer();

    public MnMigrad(FCNBase fcn, MnUserParameterState par, MnStrategy str) {
        super(fcn, par, str, 0);
    }

    public MnMigrad(FCNBase fcn, MnUserParameterState par) {
        this(fcn, par, new MnStrategy(1));
    }

    public MnMigrad(FCNBase fcn, MnUserParameters par) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(1));
    }

    public MnMigrad(FCNBase fcn, MnUserParameters par, int stra) {
        this(fcn, new MnUserParameterState(par), new MnStrategy(stra));
    }

    public MnMigrad(FCNBase fcn, double[] par, double[] err) {
        this(fcn, new MnUserParameterState(par, err), new MnStrategy(1));
    }

    public MnMigrad(FCNBase fcn, double[] par, double[] err, int stra) {
        this(fcn, new MnUserParameterState(par, err), new MnStrategy(stra));
    }

    public MnMigrad(FCNBase fcn, MnUserParameters par, MnUserCovariance cov) {
        this(fcn, new MnUserParameterState(par, cov), new MnStrategy(1));
    }

    @Override
    public ModularFunctionMinimizer minimizer() {
        return minimizer;
    }
}
