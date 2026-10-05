package com.sphere.core.minuit2;

/** A negative log-likelihood for Fumili: minus the sum of the logarithms of the model at each measurement. */
public abstract class FumiliMaximumLikelihoodFCN extends FumiliFCNBase {

    private ParametricFunction modelFunction;

    protected FumiliMaximumLikelihoodFCN() {
    }

    public void setModelFunction(ParametricFunction modelFCN) {
        modelFunction = modelFCN;
    }

    public ParametricFunction modelFunction() {
        return modelFunction;
    }

    public abstract double[] elements(double[] par);

    public abstract double[] getMeasurement(int index);

    public abstract int getNumberOfMeasurements();

    @Override
    public double value(double[] par) {
        double sumoflogs = 0.0;
        final double[] vecElements = elements(par);
        for (double tmp : vecElements) {
            if (!(tmp >= 0)) throw new IllegalStateException("negative likelihood element " + tmp);
            sumoflogs -= Cxx.evalLog(tmp);
        }
        return sumoflogs;
    }

    @Override
    public double up() {
        return 0.5;
    }
}
