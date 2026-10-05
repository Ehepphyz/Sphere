package com.sphere.core.minuit2;

/** Fumili's error matrix: the inverse of its approximate Hessian, the diagonal raised by 1 + lambda. */
public final class FumiliErrorUpdator implements MinimumErrorUpdator {

    @Override
    public MinimumError update(MinimumState s0, MinimumParameters p1, FunctionGradient g1) {
        return new MinimumError(2);
    }

    public MinimumError update(MinimumState s0, MinimumParameters p1, GradientCalculator gc, double lambda) {
        final MnPrint print = new MnPrint("FumiliErrorUpdator");
        print.debug("Compute covariance matrix using Fumili method");
        if (!(gc instanceof FumiliGradientCalculator fgc)) throw new IllegalArgumentException("Fumili needs its gradient calculator");
        final LASymMatrix h = fgc.getHessian().copy();
        final int nvar = p1.vec().size();
        final double eps = 8 * Double.MIN_NORMAL;
        for (int j = 0; j < nvar; j++) {
            h.set(j, j, h.get(j, j) * (1. + lambda));
            if (Math.abs(h.get(j, j)) < eps) {
                if (lambda > 1) h.set(j, j, lambda * eps);
                else h.set(j, j, eps);
            }
        }
        final LASymMatrix cov = h.copy();
        final int ifail = MnMatrix.invert(cov);
        if (ifail != 0) {
            print.warn("inversion fails; return diagonal matrix");
            for (int i = 0; i < cov.nrow(); i++) cov.set(i, i, 1. / cov.get(i, i));
            return new MinimumError(cov, MinimumError.Status.MnInvertFailed);
        }
        double dcov = -1;
        if (s0.isValid()) {
            final LASymMatrix v0 = s0.error().invHessian();
            dcov = 0.5 * (s0.error().dcovar() + MnMatrix.sumOfElements(MnMatrix.subtract(cov, v0)) / MnMatrix.sumOfElements(cov));
        }
        return new MinimumError(cov, h, dcov);
    }
}
