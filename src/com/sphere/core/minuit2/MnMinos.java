package com.sphere.core.minuit2;

/**
 * Minos: the asymmetric errors of a parameter, where the function minimized
 * over the others rises by Up on each side. The minimum must be valid.
 */
public final class MnMinos {

    private final FCNBase fcn;
    private final FunctionMinimum minimum;
    private final MnStrategy strategy;

    public MnMinos(FCNBase fcn, FunctionMinimum min) {
        this(fcn, min, 1);
    }

    public MnMinos(FCNBase fcn, FunctionMinimum min, int stra) {
        this(fcn, min, new MnStrategy(stra), "MnMinos: ");
    }

    public MnMinos(FCNBase fcn, FunctionMinimum min, MnStrategy stra) {
        this(fcn, min, stra, "");
    }

    private MnMinos(FCNBase fcn, FunctionMinimum min, MnStrategy stra, String warnPrefix) {
        this.fcn = fcn;
        this.minimum = min;
        this.strategy = new MnStrategy(stra);
        if (fcn.up() != min.up()) {
            new MnPrint("MnMinos").warn(warnPrefix + "UP value has changed, need to update FunctionMinimum class");
        }
    }

    /** (lower, upper) errors of parameter par. */
    public double[] errors(int par, int maxcalls, double toler) {
        return minos(par, maxcalls, toler).pair();
    }

    public double[] errors(int par) {
        return errors(par, 0, 0.1);
    }

    public double lower(int par, int maxcalls, double toler) {
        final MnCross aopt = loval(par, maxcalls, toler);
        final MinosError mnerr = new MinosError(par, minimum.userState().value(par), aopt, new MnCross());
        return mnerr.lower();
    }

    public double upper(int par, int maxcalls, double toler) {
        final MnCross aopt = upval(par, maxcalls, toler);
        final MinosError mnerr = new MinosError(par, minimum.userState().value(par), new MnCross(), aopt);
        return mnerr.upper();
    }

    public MinosError minos(int par, int maxcalls, double toler) {
        final MnPrint print = new MnPrint("MnMinos");
        final MnCross up = upval(par, maxcalls, toler);
        print.debug("Function calls to find upper error", up.nfcn());
        final MnCross lo = loval(par, maxcalls, toler);
        print.debug("Function calls to find lower error", lo.nfcn());
        print.debug("return Minos error", lo.value(), ",", up.value());
        return new MinosError(par, minimum.userState().value(par), lo, up);
    }

    public MinosError minos(int par) {
        return minos(par, 0, 0.1);
    }

    public MnCross upval(int par, int maxcalls, double toler) {
        return findCrossValue(1, par, maxcalls, toler);
    }

    public MnCross loval(int par, int maxcalls, double toler) {
        return findCrossValue(-1, par, maxcalls, toler);
    }

    private MnCross findCrossValue(int direction, int par, int maxcalls, double toler) {
        final MnPrint print = new MnPrint("MnMinos");
        print.info("Determination of", direction == 1 ? "upper" : "lower", "Minos error for parameter", par);
        if (!minimum.isValid()) throw new IllegalStateException("Minos needs a valid minimum");
        if (minimum.userState().parameter(par).isFixed() || minimum.userState().parameter(par).isConst()) {
            throw new IllegalArgumentException("parameter " + par + " is not variable");
        }
        if (maxcalls == 0) {
            final int nvar = minimum.userState().variableParameters();
            maxcalls = 2 * (nvar + 1) * (200 + 100 * nvar + 5 * nvar * nvar);
        }
        final int[] para = {par};
        final MnUserParameterState upar = new MnUserParameterState(minimum.userState());
        double err = direction * upar.error(par);
        double val = upar.value(par) + err;
        if (direction == 1 && upar.parameter(par).hasUpperLimit()) val = Cxx.min(val, upar.parameter(par).upperLimit());
        if (direction == -1 && upar.parameter(par).hasLowerLimit()) val = Cxx.max(val, upar.parameter(par).lowerLimit());
        err = val - upar.value(par);
        final double[] xmid = {val};
        final double[] xdir = {err};
        final double up = fcn.up();
        final int ind = upar.intOfExt(par);
        final LASymMatrix m = minimum.error().matrix();
        final LAVector xt = minimum.parameters().vec();
        final double xunit = Math.sqrt(up / m.get(ind, ind));
        for (int i = 0; i < m.nrow(); i++) {
            if (i == ind) continue;
            final double xdev = xunit * m.get(ind, i);
            final double xnew = xt.get(i) + direction * xdev;
            final int ext = upar.extOfInt(i);
            double unew = upar.int2ext(i, xnew);
            if (upar.parameter(ext).hasUpperLimit()) unew = Cxx.min(unew, upar.parameter(ext).upperLimit());
            if (upar.parameter(ext).hasLowerLimit()) unew = Cxx.max(unew, upar.parameter(ext).lowerLimit());
            print.debug("Parameter", ext, "is set from", upar.value(ext), "to", unew);
            upar.setValue(ext, unew);
        }
        upar.fix(par);
        upar.setValue(par, val);
        print.debug("Parameter", par, "is fixed and set from", minimum.userState().value(par), "to", val, "delta =", err);
        final MnFunctionCross cross = new MnFunctionCross(fcn, upar, minimum.fval(), strategy);
        final MnCross aopt = cross.cross(para, xmid, xdir, toler, maxcalls);
        print.debug("aopt value found from MnFunctionCross =", aopt.value());
        final String parName = upar.name(par);
        if (aopt.atMaxFcn()) print.warn("maximum number of function calls exceeded for Parameter", parName);
        if (aopt.newMinimum()) print.warn("new Minimum found while looking for Parameter", parName);
        if (direction == 1) {
            if (aopt.atLimit()) print.warn("parameter", parName, "is at Upper limit");
            if (!aopt.isValid()) print.warn("could not find Upper Value for Parameter", parName);
        } else {
            if (aopt.atLimit()) print.warn("parameter", parName, "is at Lower limit");
            if (!aopt.isValid()) print.warn("could not find Lower Value for Parameter", parName);
        }
        print.info("end of Minos scan for", direction == 1 ? "up" : "low", "interval for parameter", upar.name(par));
        return aopt;
    }
}
