package com.sphere.core.minuit2;

/** The standard negative log-likelihood: elements model(x_i), the density at each measurement. */
public final class FumiliStandardMaximumLikelihoodFCN extends FumiliMaximumLikelihoodFCN {

    private final double[][] positions;

    public FumiliStandardMaximumLikelihoodFCN(ParametricFunction modelFCN, double[] pos) {
        setModelFunction(modelFCN);
        positions = new double[pos.length][];
        for (int i = 0; i < pos.length; ++i) positions[i] = new double[] {pos[i]};
    }

    public FumiliStandardMaximumLikelihoodFCN(ParametricFunction modelFCN, double[][] pos) {
        setModelFunction(modelFCN);
        positions = new double[pos.length][];
        for (int i = 0; i < pos.length; i++) positions[i] = pos[i].clone();
    }

    @Override
    public double[] elements(double[] par) {
        final double[] result = new double[positions.length];
        for (int i = 0; i < positions.length; i++) result[i] = modelFunction().value(par, positions[i]);
        return result;
    }

    @Override
    public double[] getMeasurement(int index) {
        return positions[index];
    }

    @Override
    public int getNumberOfMeasurements() {
        return positions.length;
    }

    @Override
    public void evaluateAll(double[] par) {
        final double minDouble = 8.0 * Double.MIN_NORMAL;
        final double minDouble2 = Math.sqrt(minDouble);
        final double maxDouble2 = 1.0 / minDouble2;
        final int nmeas = getNumberOfMeasurements();
        final int npar = par.length;
        double logLikelihood = 0;
        final double[] grad = resetGradient(npar);
        final double[] h = resetHessian((int) (0.5 * npar * (npar + 1)));
        final ParametricFunction modelFunc = modelFunction();
        for (int i = 0; i < nmeas; ++i) {
            modelFunc.setParameters(positions[i]);
            double fval = modelFunc.value(par);
            if (fval < minDouble) fval = minDouble;
            logLikelihood -= Cxx.log(fval);
            final double invFval = 1.0 / fval;
            final double[] mfg = modelFunc.getGradient(par);
            for (int j = 0; j < npar; ++j) {
                if (Math.abs(mfg[j]) < minDouble) mfg[j] = mfg[j] < 0 ? -minDouble : minDouble;
                double dfj = invFval * mfg[j];
                if (Math.abs(dfj) > maxDouble2) dfj = dfj > 0 ? maxDouble2 : -maxDouble2;
                grad[j] -= dfj;
                for (int k = j; k < npar; ++k) {
                    final int idx = j + k * (k + 1) / 2;
                    if (Math.abs(mfg[k]) < minDouble) mfg[k] = mfg[k] < 0 ? -minDouble : minDouble;
                    double dfk = invFval * mfg[k];
                    if (Math.abs(dfk) > maxDouble2) dfk = dfk > 0 ? maxDouble2 : -maxDouble2;
                    h[idx] += dfj * dfk;
                }
            }
        }
        setFCNValue(logLikelihood);
    }
}
