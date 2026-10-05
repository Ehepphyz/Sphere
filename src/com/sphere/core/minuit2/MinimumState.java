package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/** One step of a minimization: the point, the error matrix, the gradient, the EDM and the calls so far. */
public final class MinimumState implements MnPrint.Printable {

    private final MinimumParameters parameters;
    private final MinimumError error;
    private final FunctionGradient gradient;
    private final double edm;
    private final int nfcn;

    /** n zeros. */
    public MinimumState(int n) {
        this(new MinimumParameters(n, 0.0), new MinimumError(n), new FunctionGradient(n), 0.0, 0);
    }

    /** A function value only (a step whose point is not kept). */
    public MinimumState(double fval, double edm, int nfcn) {
        this(new MinimumParameters(0, fval), new MinimumError(0), new FunctionGradient(0), edm, nfcn);
    }

    public MinimumState(MinimumParameters states, double edm, int nfcn) {
        this(states, new MinimumError(states.vec().size()), new FunctionGradient(states.vec().size()), edm, nfcn);
    }

    public MinimumState(MinimumParameters states, MinimumError err, FunctionGradient grad, double edm, int nfcn) {
        this.parameters = states;
        this.error = err;
        this.gradient = grad;
        this.edm = edm;
        this.nfcn = nfcn;
    }

    public MinimumParameters parameters() {
        return parameters;
    }

    public LAVector vec() {
        return parameters.vec();
    }

    public int size() {
        return vec().size();
    }

    public MinimumError error() {
        return error;
    }

    public FunctionGradient gradient() {
        return gradient;
    }

    public double fval() {
        return parameters.fval();
    }

    public double edm() {
        return edm;
    }

    public int nfcn() {
        return nfcn;
    }

    public boolean isValid() {
        if (hasParameters() && hasCovariance()) return parameters().isValid() && error().isValid();
        else if (hasParameters()) return parameters().isValid();
        else return false;
    }

    public boolean hasParameters() {
        return parameters.isValid();
    }

    public boolean hasCovariance() {
        return error.isAvailable();
    }

    /** operator&lt;&lt;. */
    @Override
    public void print(COStream os) {
        final int pr = os.precision();
        os.precision(MnMatrix.PRECISION);
        os.put("\n  Minimum value : ").put(fval()).put("\n  Edm           : ").put(edm()).put("\n  Internal parameters:");
        vec().print(os);
        os.put("\n  Internal gradient  :");
        gradient().vec().print(os);
        if (hasCovariance()) {
            os.put("\n  Internal covariance matrix:");
            error().matrix().print(os);
        }
        os.precision(pr);
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}
