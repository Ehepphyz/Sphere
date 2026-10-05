package com.sphere.core.minuit2;

/** A point of the internal parameter space with its function value, and the step that led there when known. */
public final class MinimumParameters {

    public enum Status { MnValid, MnInvalid }

    private final LAVector parameters;
    private final LAVector stepSize;
    private final double fval;
    private final boolean valid;
    private final boolean hasStep;

    /** n zeros, not valid. */
    public MinimumParameters(int n, double fval) {
        this.parameters = new LAVector(n);
        this.stepSize = new LAVector(n);
        this.fval = fval;
        this.valid = false;
        this.hasStep = false;
    }

    /** No parameters, only a function value (a function without parameters). */
    public MinimumParameters(double fval, Status status) {
        this.parameters = new LAVector(0);
        this.stepSize = new LAVector(0);
        this.fval = fval;
        this.valid = status == Status.MnValid;
        this.hasStep = false;
    }

    public MinimumParameters(LAVector avec, double fval) {
        this.parameters = avec.copy();
        this.stepSize = new LAVector(avec.size());
        this.fval = fval;
        this.valid = true;
        this.hasStep = false;
    }

    public MinimumParameters(LAVector avec, LAVector dirin, double fval) {
        this.parameters = avec.copy();
        this.stepSize = dirin.copy();
        this.fval = fval;
        this.valid = true;
        this.hasStep = true;
    }

    /** The parameters (not to be changed: copy first). */
    public LAVector vec() {
        return parameters;
    }

    public LAVector dirin() {
        return stepSize;
    }

    public double fval() {
        return fval;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean hasStepSize() {
        return hasStep;
    }
}
