package com.sphere.core.minuit2;

/**
 * Removes one parameter from a covariance matrix the right way: invert to
 * the Hessian, drop the row and column, invert back.
 */
public final class MnCovarianceSqueeze {

    private MnCovarianceSqueeze() {
    }

    public static MnUserCovariance squeeze(MnUserCovariance cov, int n) {
        if (cov.nrow() == 0 || n >= cov.nrow()) throw new IllegalArgumentException("cannot squeeze row " + n);
        final MnPrint print = new MnPrint("MnCovarianceSqueeze");
        final LASymMatrix hess = new LASymMatrix(cov.nrow());
        for (int i = 0; i < cov.nrow(); i++) {
            for (int j = i; j < cov.nrow(); j++) hess.set(i, j, cov.get(i, j));
        }
        int ifail = MnMatrix.invert(hess);
        if (ifail != 0) {
            print.warn("inversion failed; return diagonal matrix;");
            final MnUserCovariance result = new MnUserCovariance(cov.nrow() - 1);
            for (int i = 0, j = 0; i < cov.nrow(); i++) {
                if (i == n) continue;
                result.set(j, j, cov.get(i, i));
                j++;
            }
            return result;
        }
        final LASymMatrix squeezed = squeeze(hess, n);
        ifail = MnMatrix.invert(squeezed);
        if (ifail != 0) {
            print.warn("back-inversion failed; return diagonal matrix;");
            final MnUserCovariance result = new MnUserCovariance(squeezed.nrow());
            for (int i = 0; i < squeezed.nrow(); i++) result.set(i, i, 1. / squeezed.get(i, i));
            return result;
        }
        return new MnUserCovariance(squeezed.data, squeezed.nrow());
    }

    public static MinimumError squeeze(MinimumError err, int n) {
        final MnPrint print = new MnPrint("MnCovarianceSqueeze");
        final int[] ifail1 = {0};
        final LASymMatrix hess = MinimumError.invertMatrix(err.invHessian(), ifail1);
        final LASymMatrix squeezed = squeeze(hess, n);
        final int ifail2 = MnMatrix.invert(squeezed);
        if (ifail1[0] != 0 && ifail2 == 0) {
            print.warn("MinimumError inversion fails; return diagonal matrix.");
            return new MinimumError(squeezed, MinimumError.Status.MnInvertFailed);
        }
        if (ifail2 != 0) {
            print.warn("MinimumError back-inversion fails; return diagonal matrix.");
            final LASymMatrix tmp = new LASymMatrix(squeezed.nrow());
            for (int i = 0; i < squeezed.nrow(); i++) tmp.set(i, i, 1. / squeezed.get(i, i));
            return new MinimumError(tmp, MinimumError.Status.MnInvertFailed);
        }
        return new MinimumError(squeezed, err.dcovar());
    }

    /** The matrix without row and column n. */
    public static LASymMatrix squeeze(LASymMatrix hess, int n) {
        if (hess.nrow() == 0 || n >= hess.nrow()) throw new IllegalArgumentException("cannot squeeze row " + n);
        final LASymMatrix hs = new LASymMatrix(hess.nrow() - 1);
        for (int i = 0, j = 0; i < hess.nrow(); i++) {
            if (i == n) continue;
            for (int k = i, l = j; k < hess.nrow(); k++) {
                if (k == n) continue;
                hs.set(j, l, hess.get(i, k));
                l++;
            }
            j++;
        }
        return hs;
    }
}
