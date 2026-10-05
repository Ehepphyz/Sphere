package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;

/**
 * The function along one parameter, the others held: maxsteps points between
 * low and high (by default the value +- 2 errors, kept inside the limits),
 * remembering the best point.
 */
public final class MnParameterScan {

    private final FCNBase fcn;
    private final MnUserParameters parameters;
    private double amin;

    public MnParameterScan(FCNBase fcn, MnUserParameters par) {
        this.fcn = fcn;
        this.parameters = new MnUserParameters(par);
        this.amin = fcn.value(par.params());
    }

    public MnParameterScan(FCNBase fcn, MnUserParameters par, double fval) {
        this.fcn = fcn;
        this.parameters = new MnUserParameters(par);
        this.amin = fval;
    }

    /** (x, f) pairs: the start first, then the scan. */
    public List<MnPrint.Point> scan(int par, int maxsteps, double low, double high) {
        final List<MnPrint.Point> result = new ArrayList<>(maxsteps + 1);
        final double[] params = parameters.params();
        result.add(new MnPrint.Point(params[par], amin));
        if (low > high) return result;
        if (maxsteps < 2) return result;
        if (low == 0. && high == 0.) {
            low = params[par] - 2. * parameters.error(par);
            high = params[par] + 2. * parameters.error(par);
        }
        final MinuitParameter p = parameters.parameter(par);
        if (low == 0. && high == 0. && p.hasLimits()) {
            if (p.hasLowerLimit()) low = p.lowerLimit();
            if (p.hasUpperLimit()) high = p.upperLimit();
        }
        if (p.hasLimits()) {
            if (p.hasLowerLimit()) low = Cxx.max(low, p.lowerLimit());
            if (p.hasUpperLimit()) high = Cxx.min(high, p.upperLimit());
        }
        final double x0 = low;
        final double stp = (high - low) / (double) (maxsteps - 1);
        for (int i = 0; i < maxsteps; i++) {
            params[par] = x0 + (double) i * stp;
            final double fval = fcn.value(params);
            if (fval < amin) {
                parameters.setValue(par, params[par]);
                amin = fval;
            }
            result.add(new MnPrint.Point(params[par], fval));
        }
        return result;
    }

    public List<MnPrint.Point> scan(int par) {
        return scan(par, 41, 0., 0.);
    }

    public MnUserParameters parameters() {
        return parameters;
    }

    public double fval() {
        return amin;
    }
}
