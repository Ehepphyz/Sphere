package com.sphere.core.minuit2;

/** The standard chi-square: elements (model(x_i) - y_i)/sigma_i, a zero variance counting as 1 (as ROOT and PAW do). */
public final class FumiliStandardChi2FCN extends FumiliChi2FCN {

    private final double[] measurements;
    private final double[][] positions;
    private final double[] invErrors;

    /** One-dimensional positions. */
    public FumiliStandardChi2FCN(ParametricFunction modelFCN, double[] meas, double[] pos, double[] mvar) {
        setModelFunction(modelFCN);
        if (meas.length != pos.length || meas.length != mvar.length) throw new IllegalArgumentException("sizes differ");
        measurements = meas.clone();
        final int n = mvar.length;
        positions = new double[n][];
        invErrors = new double[n];
        for (int i = 0; i < n; ++i) {
            positions[i] = new double[] {pos[i]};
            invErrors[i] = mvar[i] == 0 ? 1 : 1.0 / Math.sqrt(mvar[i]);
        }
    }

    /** Positions of any dimension. */
    public FumiliStandardChi2FCN(ParametricFunction modelFCN, double[] meas, double[][] pos, double[] mvar) {
        setModelFunction(modelFCN);
        if (meas.length != pos.length || meas.length != mvar.length) throw new IllegalArgumentException("sizes differ");
        measurements = meas.clone();
        positions = new double[pos.length][];
        for (int i = 0; i < pos.length; i++) positions[i] = pos[i].clone();
        final int n = mvar.length;
        invErrors = new double[n];
        for (int i = 0; i < n; ++i) invErrors[i] = mvar[i] == 0 ? 1 : 1.0 / Math.sqrt(mvar[i]);
    }

    @Override
    public double[] elements(double[] par) {
        final double[] result = new double[positions.length];
        for (int i = 0; i < positions.length; i++) {
            final double tmp1 = modelFunction().value(par, positions[i]) - measurements[i];
            result[i] = tmp1 * invErrors[i];
        }
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
        final int nmeas = getNumberOfMeasurements();
        final int npar = par.length;
        double chi2 = 0;
        final double[] grad = resetGradient(npar);
        final double[] h = resetHessian((int) (0.5 * npar * (npar + 1)));
        final ParametricFunction modelFunc = modelFunction();
        for (int i = 0; i < nmeas; ++i) {
            modelFunc.setParameters(positions[i]);
            final double invError = invErrors[i];
            final double fval = modelFunc.value(par);
            final double element = (fval - measurements[i]) * invError;
            chi2 += element * element;
            final double[] mfg = modelFunc.getGradient(par);
            for (int j = 0; j < npar; ++j) {
                final double dfj = invError * mfg[j];
                grad[j] += 2.0 * element * dfj;
                for (int k = j; k < npar; ++k) {
                    final int idx = j + k * (k + 1) / 2;
                    h[idx] += 2.0 * dfj * invError * mfg[k];
                }
            }
        }
        setFCNValue(chi2);
    }
}
