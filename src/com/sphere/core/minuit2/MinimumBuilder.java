package com.sphere.core.minuit2;

/** Finds the minimum from a seed: the algorithm proper (Migrad, Simplex, Scan, Fumili...). */
public abstract class MinimumBuilder {

    private int printLevel = MnPrint.globalLevel();
    private int storageLevel = 1;
    private MnTraceObject tracer;

    public abstract FunctionMinimum minimum(MnFcn fcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy,
                                            int maxfcn, double edmval);

    public int storageLevel() {
        return storageLevel;
    }

    public int printLevel() {
        return printLevel;
    }

    public boolean traceIter() {
        return tracer != null;
    }

    public MnTraceObject traceObject() {
        return tracer;
    }

    public void setPrintLevel(int level) {
        printLevel = level;
    }

    public void setStorageLevel(int level) {
        storageLevel = level;
    }

    public void setTraceObject(MnTraceObject obj) {
        tracer = obj;
    }

    public void traceIteration(int iter, MinimumState state) {
        if (tracer != null) tracer.trace(iter, state);
    }
}
