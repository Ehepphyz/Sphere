package com.sphere.core.minuit2;

/**
 * A function for Fumili: a fit whose figure of merit (chi-square or
 * likelihood) also gives, linearizing the model, its gradient and an
 * approximate Hessian (the second derivatives of the model neglected),
 * computed together by {@link #evaluateAll}.
 */
public abstract class FumiliFCNBase implements FCNBase {

    private int numberOfParameters;
    private double value;
    private double[] gradient;
    private double[] hessian;

    protected FumiliFCNBase() {
        numberOfParameters = 0;
        value = 0;
        gradient = new double[0];
        hessian = new double[0];
    }

    protected FumiliFCNBase(int npar) {
        numberOfParameters = npar;
        value = 0;
        gradient = new double[npar];
        hessian = new double[(int) (0.5 * npar * (npar + 1))];
    }

    @Override
    public boolean hasGradient() {
        return true;
    }

    /** Computes the value, gradient and Hessian at par, kept for value(), gradient() and hessian(). */
    public abstract void evaluateAll(double[] par);

    public double value() {
        return value;
    }

    /** The gradient of the last evaluateAll. */
    public double[] gradient() {
        return gradient;
    }

    @Override
    public double[] gradient(double[] v) {
        return gradient.clone();
    }

    @Override
    public double[] hessian(double[] v) {
        return hessian.clone();
    }

    /** Element (row, col) of the Hessian of the last evaluateAll. */
    public double hessian(int row, int col) {
        if (row >= gradient.length || col >= gradient.length) throw new IndexOutOfBoundsException(row + "," + col);
        return hessian[LASymMatrix.index(row, col)];
    }

    public int dimension() {
        return numberOfParameters;
    }

    protected void initAndReset(int npar) {
        numberOfParameters = npar;
        gradient = new double[npar];
        hessian = new double[(int) (0.5 * npar * (npar + 1))];
    }

    protected void setFCNValue(double v) {
        value = v;
    }

    /** The stored gradient, to fill. */
    protected double[] gradientStore() {
        return gradient;
    }

    /** The stored packed Hessian, to fill. */
    protected double[] hessianStore() {
        return hessian;
    }

    /** grad.assign(n, 0.0): the stored gradient resized and zeroed. */
    protected double[] resetGradient(int n) {
        gradient = new double[n];
        return gradient;
    }

    /** h.assign(n, 0.0). */
    protected double[] resetHessian(int n) {
        hessian = new double[n];
        return hessian;
    }
}
