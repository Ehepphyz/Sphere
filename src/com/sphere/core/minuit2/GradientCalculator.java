package com.sphere.core.minuit2;

/** Computes the gradient (and, some, the second derivatives) of the function at a point of the internal space. */
public abstract class GradientCalculator {

    private static volatile boolean parallel;

    /**
     * Lets the numerical gradient compute its parameters on several threads
     * (Minuit2's OpenMP option): the values are those of the serial
     * computation, each parameter's derivative being independent of the
     * others; the function must then be safe to call from several threads.
     */
    public static boolean setParallel(boolean on) {
        parallel = on;
        return true;
    }

    public static boolean isParallel() {
        return parallel;
    }

    public abstract FunctionGradient compute(MinimumParameters par);

    /** The gradient, starting from the previous one (its steps and second derivatives). */
    public abstract FunctionGradient compute(MinimumParameters par, FunctionGradient previous);

    /** Fills the Hessian; false when this calculator cannot. */
    public boolean hessian(MinimumParameters par, LASymMatrix h) {
        return false;
    }

    /** Fills the second derivatives; false when this calculator cannot. */
    public boolean g2(MinimumParameters par, LAVector g2) {
        return false;
    }

    /**
     * The first gradient, from the parameter errors: for each parameter a step
     * of its error mapped to the internal space, and the second derivative
     * that makes that step one error-definition unit (InitialGradientCalculator).
     */
    public static FunctionGradient calculateInitialGradient(MinimumParameters par, MnUserTransformation trafo, double errorDef) {
        if (!par.isValid()) throw new IllegalArgumentException("invalid parameters");
        final int n = trafo.variableParameters();
        final MnPrint print = new MnPrint("InitialGradientCalculator");
        print.debug("Calculating initial gradient at point", par.vec());
        final LAVector gr = new LAVector(n), gr2 = new LAVector(n), gst = new LAVector(n);
        for (int i = 0; i < n; i++) {
            final int exOfIn = trafo.extOfInt(i);
            final double var = par.vec().get(i);
            final double werr = trafo.parameter(exOfIn).error();
            final double save1 = trafo.int2ext(i, var);
            double save2 = save1 + werr;
            final MinuitParameter p = trafo.parameter(exOfIn);
            if (p.hasLimits()) {
                if (p.hasUpperLimit() && save2 > p.upperLimit()) save2 = p.upperLimit();
            }
            double var2 = trafo.ext2int(exOfIn, save2);
            final double vplu = var2 - var;
            save2 = save1 - werr;
            if (p.hasLimits()) {
                if (p.hasLowerLimit() && save2 < p.lowerLimit()) save2 = p.lowerLimit();
            }
            var2 = trafo.ext2int(exOfIn, save2);
            final double vmin = var2 - var;
            final double gsmin = 8. * trafo.precision().eps2() * (Math.abs(var) + trafo.precision().eps2());
            final double dirin = Cxx.max(0.5 * (Math.abs(vplu) + Math.abs(vmin)), gsmin);
            final double g2 = 2.0 * errorDef / (dirin * dirin);
            double gstep = Cxx.max(gsmin, 0.1 * dirin);
            final double grd = g2 * dirin;
            if (p.hasLimits()) {
                if (gstep > 0.5) gstep = 0.5;
            }
            gr.set(i, grd);
            gr2.set(i, g2);
            gst.set(i, gstep);
            print.trace("Computed initial gradient for parameter", trafo.name(exOfIn), "value", var, "[", vmin, ",", vplu,
                "]", "dirin", dirin, "grd", grd, "g2", g2);
        }
        return new FunctionGradient(gr, gr2, gst);
    }
}
