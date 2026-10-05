package com.sphere.core.minuit2;

/**
 * The first derivatives at a point, with (numerically) the second
 * derivatives and the steps they were taken with, or (analytically) as the
 * user's function gives them.
 */
public final class FunctionGradient {

    private final LAVector gradient;
    private final LAVector g2ndDerivative;
    private final LAVector gStepSize;
    private final boolean valid;
    private final boolean analytical;
    private final boolean hasG2;

    /** n zeros, not valid. */
    public FunctionGradient(int n) {
        gradient = new LAVector(n);
        g2ndDerivative = new LAVector(n);
        gStepSize = new LAVector(n);
        valid = false;
        analytical = false;
        hasG2 = false;
    }

    /** An analytical gradient. */
    public FunctionGradient(LAVector grd) {
        gradient = grd.copy();
        g2ndDerivative = new LAVector(0);
        gStepSize = new LAVector(0);
        valid = true;
        analytical = true;
        hasG2 = false;
    }

    /** An analytical gradient with its second derivatives. */
    public FunctionGradient(LAVector grd, LAVector g2) {
        gradient = grd.copy();
        g2ndDerivative = g2.copy();
        gStepSize = new LAVector(0);
        valid = true;
        analytical = true;
        hasG2 = true;
    }

    /** A numerical gradient. */
    public FunctionGradient(LAVector grd, LAVector g2, LAVector gstep) {
        gradient = grd.copy();
        g2ndDerivative = g2.copy();
        gStepSize = gstep.copy();
        valid = true;
        analytical = false;
        hasG2 = true;
    }

    public LAVector grad() {
        return gradient;
    }

    public LAVector vec() {
        return gradient;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isAnalytical() {
        return analytical;
    }

    public boolean hasG2() {
        return hasG2;
    }

    public LAVector g2() {
        return g2ndDerivative;
    }

    public LAVector gstep() {
        return gStepSize;
    }
}
