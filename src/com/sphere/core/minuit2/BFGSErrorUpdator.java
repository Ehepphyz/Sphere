package com.sphere.core.minuit2;

/** The Broyden-Fletcher-Goldfarb-Shanno update of the inverse Hessian (Migrad with the "BFGS" option). */
public final class BFGSErrorUpdator implements MinimumErrorUpdator {

    @Override
    public MinimumError update(MinimumState s0, MinimumParameters p1, FunctionGradient g1) {
        final LASymMatrix v0 = s0.error().invHessian();
        final LAVector dx = MnMatrix.subtract(p1.vec(), s0.vec());
        final LAVector dg = MnMatrix.subtract(g1.vec(), s0.gradient().vec());
        final double delgam = MnMatrix.innerProduct(dx, dg);
        final double gvg = MnMatrix.similarity(dg, v0);
        final MnPrint print = new MnPrint("BFGSErrorUpdator");
        print.debug("dx", dx, "dg", dg, "delgam", delgam, "gvg", gvg);
        if (delgam == 0) {
            print.warn("delgam = 0 : cannot update - return same matrix");
            return s0.error();
        }
        if (delgam < 0) print.warn("delgam < 0 : first derivatives increasing along search line");
        if (gvg <= 0) print.warn("gvg <= 0");
        final int n = v0.nrow();
        // a = dg dx^T (square), b = V0 a
        final double[] a = new double[n * n];
        for (int i = 0; i < n; ++i) {
            for (int j = 0; j < n; ++j) a[j + i * n] = dg.get(i) * dx.get(j);
        }
        final double[] b = new double[n * n];
        for (int i = 0; i < n; ++i) {
            for (int j = 0; j < n; ++j) {
                double s = 0;
                b[j + i * n] = 0;
                for (int k = 0; k < n; ++k) {
                    s = b[j + i * n] + v0.get(i, k) * a[j + k * n];
                    b[j + i * n] = s;
                }
            }
        }
        final LASymMatrix v2 = new LASymMatrix(n);
        for (int i = 0; i < n; ++i) {
            for (int j = i; j < n; ++j) v2.set(i, j, (b[j + i * n] + b[i + j * n]) / (delgam));
        }
        // (delgam + gvg) * Outer_product(dx) / (delgam * delgam)
        final LASymMatrix vUpd = MnMatrix.outer((1. * (delgam + gvg)) / (delgam * delgam) * 1. * 1., dx);
        vUpd.minusAssign(v2);
        final double sumUpd = MnMatrix.sumOfElements(vUpd);
        vUpd.plusAssign(v0);
        final double dcov = 0.5 * (s0.error().dcovar() + sumUpd / MnMatrix.sumOfElements(vUpd));
        print.debug("dcov", dcov);
        return new MinimumError(vUpd, dcov);
    }
}
