package com.sphere.core.minuit2;

/** Migrad, and when it fails Simplex then Migrad again from where Simplex got (MnMinimize). */
public final class CombinedMinimumBuilder extends MinimumBuilder {

    private final VariableMetricMinimizer vmMinimizer = new VariableMetricMinimizer();
    private final SimplexMinimizer simplexMinimizer = new SimplexMinimizer();

    @Override
    public FunctionMinimum minimum(MnFcn fcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy, int maxfcn,
                                   double edmval) {
        final MnPrint print = new MnPrint("CombinedMinimumBuilder");
        final FunctionMinimum min = vmMinimizer.builder().minimum(fcn, gc, seed, strategy, maxfcn, edmval);
        if (!min.isValid()) {
            print.warn("Migrad method fails, will try with simplex method first");
            final MnStrategy str = new MnStrategy(2);
            final FunctionMinimum min1 = simplexMinimizer.builder().minimum(fcn, gc, seed, str, maxfcn, edmval);
            if (!min1.isValid()) {
                print.warn("Both Migrad and Simplex methods failed");
                return min1;
            }
            final MinimumSeed seed1 = vmMinimizer.seedGenerator().generate(fcn, gc, min1.userState(), str);
            final FunctionMinimum min2 = vmMinimizer.builder().minimum(fcn, gc, seed1, str, maxfcn, edmval);
            if (!min2.isValid()) {
                print.warn("Both migrad and method failed also at 2nd attempt; return simplex Minimum");
                return min1;
            }
            return min2;
        }
        return min;
    }

    @Override
    public void setPrintLevel(int level) {
        super.setPrintLevel(level);
        vmMinimizer.builder().setPrintLevel(level);
        simplexMinimizer.builder().setPrintLevel(level);
    }

    @Override
    public void setStorageLevel(int level) {
        super.setStorageLevel(level);
        vmMinimizer.builder().setStorageLevel(level);
        simplexMinimizer.builder().setStorageLevel(level);
    }

    @Override
    public void setTraceObject(MnTraceObject obj) {
        super.setTraceObject(obj);
        vmMinimizer.builder().setTraceObject(obj);
        simplexMinimizer.builder().setTraceObject(obj);
    }
}
