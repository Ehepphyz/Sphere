package com.sphere.core.minuit2;

import java.util.List;

/** Minimization by scanning the parameters one at a time; and the scan of one parameter. */
public final class MnScan extends MnApplication {

    private final ScanMinimizer minimizer = new ScanMinimizer();

    public MnScan(FCNBase fcn, MnUserParameterState par, MnStrategy str) {
        super(fcn, par, str, 0);
    }

    public MnScan(FCNBase fcn, MnUserParameterState par) {
        this(fcn, par, new MnStrategy());
    }

    public MnScan(FCNBase fcn, MnUserParameters par) {
        this(fcn, new MnUserParameterState(par), new MnStrategy());
    }

    @Override
    public ModularFunctionMinimizer minimizer() {
        return minimizer;
    }

    /** The function along parameter par (maxsteps points between low and high; 0, 0: +- 2 errors). */
    public List<MnPrint.Point> scan(int par, int maxsteps, double low, double high) {
        final MnParameterScan scan = new MnParameterScan(fcn, state.parameters());
        double amin = scan.fval();
        final List<MnPrint.Point> result = scan.scan(par, maxsteps, low, high);
        if (scan.fval() < amin) {
            state.setValue(par, scan.parameters().value(par));
            amin = scan.fval();
        }
        return result;
    }

    public List<MnPrint.Point> scan(int par) {
        return scan(par, 41, 0., 0.);
    }
}
