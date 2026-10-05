package com.sphere.core.minuit2;

/** Fumili's gradient and Hessian: the function's evaluateAll, carried to the internal parameters. */
public final class FumiliGradientCalculator extends AnalyticalGradientCalculator {

    private final FumiliFCNBase fumili;
    private LASymMatrix hessian;

    public FumiliGradientCalculator(FumiliFCNBase fcn, MnUserTransformation trafo, int n) {
        super(fcn, trafo);
        this.fumili = fcn;
        this.hessian = new LASymMatrix(n);
    }

    @Override
    public FunctionGradient compute(MinimumParameters par) {
        final MnPrint print = new MnPrint("FumiliGradientCalculator");
        final int nvar = par.vec().size();
        final double[] extParam = transformation.transform(par.vec());
        fumili.evaluateAll(extParam);
        final LAVector v = new LAVector(nvar);
        final LASymMatrix h = new LASymMatrix(nvar);
        final double[] fcnGradient = fumili.gradient();
        final double[] deriv = new double[nvar];
        final int[] extIndex = new int[nvar];
        for (int i = 0; i < nvar; ++i) {
            extIndex[i] = transformation.extOfInt(i);
            deriv[i] = 1;
            if (transformation.parameter(extIndex[i]).hasLimits()) deriv[i] = transformation.dInt2Ext(i, par.vec().get(i));
            v.set(i, fcnGradient[extIndex[i]] * deriv[i]);
            for (int j = 0; j <= i; ++j) {
                h.set(i, j, deriv[i] * deriv[j] * fumili.hessian(extIndex[i], extIndex[j]));
            }
        }
        if (print.shows(MnPrint.Verbosity.DEBUG)) {
            print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                os.put("Comparison of Fumili Gradient and standard (numerical) Minuit Gradient (done only when debugging enabled)")
                    .endl();
                final int plevel = MnPrint.setGlobalLevel(MnPrint.globalLevel() - 1);
                final Numerical2PGradientCalculator gc = new Numerical2PGradientCalculator(new MnFcn(fumili, transformation),
                    transformation, new MnStrategy(1));
                final FunctionGradient grd2 = gc.compute(par);
                os.put("Fumili Gradient:");
                v.print(os);
                os.endl();
                os.put("Minuit Gradient");
                grd2.vec().print(os);
                os.endl();
                os.put("Fumili Hessian:  ");
                h.print(os);
                os.endl();
                os.put("Numerical g2 ");
                grd2.g2().print(os);
                os.endl();
                MnPrint.setGlobalLevel(plevel);
            });
        }
        hessian = h;
        final LAVector g2 = new LAVector(nvar);
        g2(par, g2);
        return new FunctionGradient(v, g2);
    }

    @Override
    public FunctionGradient compute(MinimumParameters par, FunctionGradient previous) {
        return compute(par);
    }

    public MnUserTransformation trafo() {
        return transformation;
    }

    public LASymMatrix getHessian() {
        return hessian;
    }

    @Override
    public boolean g2(MinimumParameters par, LAVector g2) {
        final int n = par.vec().size();
        if (hessian.nrow() != n || g2.size() != n) return false;
        for (int i = 0; i < n; i++) g2.set(i, hessian.get(i, i));
        return true;
    }

    @Override
    public boolean hessian(MinimumParameters par, LASymMatrix h) {
        final int n = par.vec().size();
        if (hessian.nrow() != n) return false;
        h.assign(hessian);
        return true;
    }

    @Override
    public boolean canComputeG2() {
        return true;
    }

    @Override
    public boolean canComputeHessian() {
        return true;
    }
}
