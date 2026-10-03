package com.sphere.core.fjcontrib.genericsubtractor;

/** What a generic subtraction found, GenericSubtractorInfo. */
public class GenericSubtractorInfo {

    double unsubtracted;
    double firstOrderSubtracted;
    double secondOrderSubtracted;
    double thirdOrderSubtracted;
    double firstDerivative;
    double secondDerivative;
    double thirdDerivative;
    double ghostScaleUsed;
    double rho;
    double rhom;

    public double unsubtracted() { return unsubtracted; }
    public double firstOrderSubtracted() { return firstOrderSubtracted; }
    public double secondOrderSubtracted() { return secondOrderSubtracted; }
    public double thirdOrderSubtracted() { return thirdOrderSubtracted; }
    public double firstDerivative() { return firstDerivative; }
    public double secondDerivative() { return secondDerivative; }
    public double thirdDerivative() { return thirdDerivative; }
    /** The step h the derivatives were taken with. */
    public double ghostScaleUsed() { return ghostScaleUsed; }
    public double rho() { return rho; }
    public double rhom() { return rhom; }

    /**
     * An estimate of the error of the subtraction from its own series,
     * |third order - second order|: Sphere's addition, to tell a shape the
     * expansion handles from one it does not.
     */
    public double truncationError() {
        return Math.abs(thirdOrderSubtracted - secondOrderSubtracted);
    }
}
