package com.sphere.core.minuit2;

/** The eigenvalues of a covariance matrix, increasing. */
public final class MnEigen {

    public double[] eigenvalues(MnUserCovariance covar) {
        final LASymMatrix cov = new LASymMatrix(covar.nrow());
        for (int i = 0; i < covar.nrow(); i++) {
            for (int j = i; j < covar.nrow(); j++) cov.set(i, j, covar.get(i, j));
        }
        final LAVector eigen = MnMatrix.eigenvalues(cov);
        final double[] result = new double[covar.nrow()];
        System.arraycopy(eigen.data, 0, result, 0, covar.nrow());
        return result;
    }
}
