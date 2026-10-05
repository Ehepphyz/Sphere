package com.sphere.core.minuit2;

/**
 * The gradient the user's function gives (in external parameters), carried
 * to the internal ones by the derivatives of the transformation; likewise
 * its second derivatives or Hessian when it gives them.
 */
public class AnalyticalGradientCalculator extends GradientCalculator {

    protected final FCNBase gradFunc;
    protected final MnUserTransformation transformation;

    public AnalyticalGradientCalculator(FCNBase fcn, MnUserTransformation state) {
        this.gradFunc = fcn;
        this.transformation = state;
    }

    @Override
    public FunctionGradient compute(MinimumParameters par) {
        final double[] grad = gradFunc.gradient(transformation.transform(par.vec()));
        if (grad.length != transformation.parameters().size()) {
            throw new IllegalStateException("the gradient has " + grad.length + " values for "
                + transformation.parameters().size() + " parameters");
        }
        final LAVector v = new LAVector(par.vec().size());
        for (int i = 0; i < par.vec().size(); i++) {
            final int ext = transformation.extOfInt(i);
            final double dd = transformation.dInt2Ext(i, par.vec().get(i));
            v.set(i, dd * grad[ext]);
        }
        final MnPrint print = new MnPrint("AnalyticalGradientCalculator");
        print.debug("User given gradient in Minuit2", v);
        if (!canComputeG2() || canComputeHessian()) return new FunctionGradient(v);
        final LAVector g2 = new LAVector(par.vec().size());
        if (!this.g2(par, g2)) {
            print.error("Error computing G2");
            return new FunctionGradient(v);
        }
        return new FunctionGradient(v, g2);
    }

    @Override
    public FunctionGradient compute(MinimumParameters par, FunctionGradient previous) {
        return compute(par);
    }

    public boolean canComputeG2() {
        return gradFunc.hasG2() || gradFunc.hasHessian();
    }

    public boolean canComputeHessian() {
        return gradFunc.hasHessian();
    }

    @Override
    public boolean hessian(MinimumParameters par, LASymMatrix hmat) {
        final int n = par.vec().size();
        final double[] extParams = transformation.transform(par.vec());
        final double[] extHessian = gradFunc.hessian(extParams);
        if (extHessian.length == 0) {
            new MnPrint("AnalyticalGradientCalculator::Hessian").info("FCN cannot compute Hessian matrix");
            return false;
        }
        final int next = (int) Math.sqrt(extHessian.length);
        final double[] extGradient = gradFunc.gradient(extParams);
        for (int i = 0; i < n; i++) {
            final int iext = transformation.extOfInt(i);
            final double dxdi = transformation.dInt2Ext(i, par.vec().get(i));
            for (int j = i; j < n; j++) {
                final double dxdj = transformation.dInt2Ext(j, par.vec().get(j));
                final int jext = transformation.extOfInt(j);
                hmat.set(i, j, dxdi * extHessian[iext * next + jext] * dxdj);
                if (i == j) {
                    final double d2xdi2 = transformation.d2Int2Ext(i, par.vec().get(i));
                    if (d2xdi2 != 0.) hmat.set(i, j, hmat.get(i, j) + d2xdi2 * extGradient[iext]);
                }
            }
        }
        return true;
    }

    @Override
    public boolean g2(MinimumParameters par, LAVector g2) {
        final int n = par.vec().size();
        final MnPrint print = new MnPrint("AnalyticalGradientCalculator::G2");
        final double[] extParams = transformation.transform(par.vec());
        final double[] extGradient = gradFunc.gradient(extParams);
        if (gradFunc.hasG2()) {
            final double[] extG2 = gradFunc.g2(extParams);
            if (extG2.length == 0) {
                print.info("FCN cannot compute the 2nd derivatives vector (G2)");
                return false;
            }
            for (int i = 0; i < n; i++) {
                final int iext = transformation.extOfInt(i);
                final double dxdi = transformation.dInt2Ext(i, par.vec().get(i));
                final double d2xdi2 = transformation.d2Int2Ext(i, par.vec().get(i));
                g2.set(i, dxdi * dxdi * extG2[iext] + d2xdi2 * extGradient[iext]);
            }
            return true;
        }
        if (!gradFunc.hasHessian()) {
            print.info("FCN cannot compute the 2nd derivatives vector (G2) or the Hessian");
            return false;
        }
        final double[] extHessian = gradFunc.hessian(extParams);
        if (extHessian.length == 0) {
            print.info("FCN cannot compute Hessian matrix (needed to derive G2)");
            return false;
        }
        final int nExt = (int) Math.round(Math.sqrt((double) extHessian.length));
        if (nExt * nExt != extHessian.length) {
            print.error("Unexpected Hessian size; cannot derive G2 from Hessian diagonal");
            return false;
        }
        for (int i = 0; i < n; i++) {
            final int iext = transformation.extOfInt(i);
            final double diag = extHessian[iext * nExt + iext];
            final double dxdi = transformation.dInt2Ext(i, par.vec().get(i));
            final double d2xdi2 = transformation.d2Int2Ext(i, par.vec().get(i));
            g2.set(i, dxdi * dxdi * diag + d2xdi2 * extGradient[iext]);
        }
        return true;
    }
}
