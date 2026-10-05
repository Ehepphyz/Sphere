package com.sphere.core.minuit2;

/** A chi-square for Fumili: the sum of the squares of elements, one per measurement. */
public abstract class FumiliChi2FCN extends FumiliFCNBase {

    private ParametricFunction modelFunction;

    protected FumiliChi2FCN() {
    }

    public void setModelFunction(ParametricFunction modelFCN) {
        modelFunction = modelFCN;
    }

    public ParametricFunction modelFunction() {
        return modelFunction;
    }

    /** The figure of merit of each measurement, (model - measured)/sigma for the standard chi-square. */
    public abstract double[] elements(double[] par);

    public abstract double[] getMeasurement(int index);

    public abstract int getNumberOfMeasurements();

    @Override
    public double value(double[] par) {
        double chiSquare = 0.0;
        final double[] vecElements = elements(par);
        for (double e : vecElements) chiSquare += e * e;
        return chiSquare;
    }

    @Override
    public double up() {
        return 1.0;
    }
}
