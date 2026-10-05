package com.sphere.core.minuit2;

/**
 * A more careful numerical gradient, used by Hesse once the second
 * derivatives are known: steps shrinking by 5 until the derivative settles,
 * with an estimate of its uncertainty.
 */
public final class HessianGradientCalculator extends GradientCalculator {

    private final MnFcn fcn;
    private final MnUserTransformation transformation;
    private final MnStrategy strategy;

    public HessianGradientCalculator(MnFcn fcn, MnUserTransformation par, MnStrategy stra) {
        this.fcn = fcn;
        this.transformation = par;
        this.strategy = stra;
    }

    @Override
    public FunctionGradient compute(MinimumParameters par) {
        final FunctionGradient gra = calculateInitialGradient(par, transformation, fcn.errorDef());
        return compute(par, gra);
    }

    @Override
    public FunctionGradient compute(MinimumParameters par, FunctionGradient gradient) {
        return deltaGradient(par, gradient).gradient();
    }

    /** The gradient and the uncertainty of each component. */
    public record Delta(FunctionGradient gradient, LAVector dgrd) {
    }

    public Delta deltaGradient(MinimumParameters par, FunctionGradient gradient) {
        if (!par.isValid()) throw new IllegalArgumentException("invalid parameters");
        final MnPrint print = new MnPrint("HessianGradientCalculator");
        final MnMachinePrecision prec = transformation.precision();
        final LAVector x = par.vec().copy();
        final LAVector grd = gradient.grad().copy();
        final LAVector g2 = gradient.g2();
        final LAVector gstep = gradient.gstep().copy();
        final double fcnmin = par.fval();
        final double dfmin = 4. * prec.eps2() * (Math.abs(fcnmin) + fcn.up());
        final int n = x.size();
        final LAVector dgrd = new LAVector(n);
        final MnFcn.Caller fcnCaller = new MnFcn.Caller(fcn);
        final int ncycle = strategy.hessianGradientNCycles();
        for (int i = 0; i < n; i++) {
            final double xtf = x.get(i);
            final double dmin = 4. * prec.eps2() * (xtf + prec.eps2());
            final double epspri = prec.eps2() + Math.abs(grd.get(i) * prec.eps2());
            final double optstp = Math.sqrt(dfmin / (Math.abs(g2.get(i)) + epspri));
            double d = 0.2 * Math.abs(gstep.get(i));
            if (d > optstp) d = optstp;
            if (d < dmin) d = dmin;
            double chgold = 10000.;
            double dgmin = 0.;
            double grdold = 0.;
            double grdnew = 0.;
            for (int j = 0; j < ncycle; j++) {
                x.set(i, xtf + d);
                final double fs1 = fcnCaller.call(x);
                x.set(i, xtf - d);
                final double fs2 = fcnCaller.call(x);
                x.set(i, xtf);
                grdold = grd.get(i);
                grdnew = (fs1 - fs2) / (2. * d);
                dgmin = prec.eps() * (Math.abs(fs1) + Math.abs(fs2)) / d;
                if (grdnew == 0) break;
                final double change = Math.abs((grdold - grdnew) / grdnew);
                if (change > chgold && j > 1) break;
                chgold = change;
                grd.set(i, grdnew);
                gstep.set(i, d);
                if (change < 0.05) break;
                if (Math.abs(grdold - grdnew) < dgmin) break;
                if (d < dmin) break;
                d *= 0.2;
            }
            dgrd.set(i, Cxx.max(dgmin, Math.abs(grdold - grdnew)));
            print.debug("HGC Param :", i, "\t new g1 =", grd.get(i), "gstep =", d, "dgrd =", dgrd.get(i));
        }
        return new Delta(new FunctionGradient(grd, g2, gstep), dgrd);
    }
}
