package com.sphere.core.minuit2;

import java.util.stream.IntStream;

/**
 * The gradient by two-point finite differences: for each parameter, a few
 * cycles that choose the step from the second derivative (the optimal step
 * balancing truncation and rounding), until the step or the derivative stops
 * changing.
 */
public final class Numerical2PGradientCalculator extends GradientCalculator {

    private final MnFcn fcn;
    private final MnUserTransformation transformation;
    private final MnStrategy strategy;

    public Numerical2PGradientCalculator(MnFcn fcn, MnUserTransformation par, MnStrategy stra) {
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
        final MnUserTransformation trafo = transformation;
        final MnPrint print = new MnPrint("Numerical2PGradientCalculator");
        if (!par.isValid()) throw new IllegalArgumentException("invalid parameters");
        final double fcnmin = par.fval();
        final double eps2 = trafo.precision().eps2();
        final double eps = trafo.precision().eps();
        final double dfmin = 8. * eps2 * (Math.abs(fcnmin) + fcn.up());
        final double vrysml = 8. * eps * eps;
        final int n = par.vec().size();
        final int ncycle = strategy.gradientNCycles();
        final LAVector grd = gradient.grad().copy();
        final LAVector g2 = gradient.g2().copy();
        final LAVector gstep = gradient.gstep().copy();
        print.debug("Calculating gradient around function value", fcnmin, "\n\t at point", par.vec());
        if (isParallel() && n > 1) {
            final int[] calls = new int[n];
            IntStream.range(0, n).parallel().forEach(i -> {
                final MnFcn local = new MnFcn(fcn.fcn(), trafo);
                final LAVector x = par.vec().copy();
                derivative(i, x, new MnFcn.Caller(local), fcnmin, eps2, dfmin, vrysml, ncycle, grd, g2, gstep, print);
                calls[i] = local.numOfCalls();
            });
            int total = 0;
            for (int c : calls) total += c;
            fcn.addCalls(total);
        } else {
            final LAVector x = par.vec().copy();
            final MnFcn.Caller caller = new MnFcn.Caller(fcn);
            for (int i = 0; i < n; i++) {
                derivative(i, x, caller, fcnmin, eps2, dfmin, vrysml, ncycle, grd, g2, gstep, print);
            }
        }
        if (print.shows(MnPrint.Verbosity.DEBUG)) {
            print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                final int pr = os.precision();
                os.precision(13);
                os.endl();
                os.setw(14).put("Parameter").setw(14).put("Gradient").setw(14).put("g2 ").setw(14).put("step").endl();
                for (int i = 0; i < n; i++) {
                    os.setw(14).put(trafo.name(trafo.extOfInt(i))).put(" ").put(grd.get(i)).put(" ").put(g2.get(i))
                        .put(" ").put(gstep.get(i)).endl();
                }
                os.precision(pr);
            });
        }
        return new FunctionGradient(grd, g2, gstep);
    }

    private void derivative(int i, LAVector x, MnFcn.Caller caller, double fcnmin, double eps2, double dfmin,
                            double vrysml, int ncycle, LAVector grd, LAVector g2, LAVector gstep, MnPrint print) {
        final MnUserTransformation trafo = transformation;
        final double xtf = x.get(i);
        final double epspri = eps2 + Math.abs(grd.get(i) * eps2);
        double stepb4 = 0.;
        for (int j = 0; j < ncycle; j++) {
            final double optstp = Math.sqrt(dfmin / (Math.abs(g2.get(i)) + epspri));
            double step = Cxx.max(optstp, Math.abs(0.1 * gstep.get(i)));
            if (trafo.parameter(trafo.extOfInt(i)).hasLimits()) {
                if (step > 0.5) step = 0.5;
            }
            final double stpmax = 10. * Math.abs(gstep.get(i));
            if (step > stpmax) step = stpmax;
            final double stpmin = Cxx.max(vrysml, 8. * Math.abs(eps2 * x.get(i)));
            if (step < stpmin) step = stpmin;
            if (Math.abs((step - stepb4) / step) < strategy.gradientStepTolerance()) break;
            gstep.set(i, step);
            stepb4 = step;
            x.set(i, xtf + step);
            final double fs1 = caller.call(x);
            x.set(i, xtf - step);
            final double fs2 = caller.call(x);
            x.set(i, xtf);
            final double grdb4 = grd.get(i);
            grd.set(i, 0.5 * (fs1 - fs2) / step);
            g2.set(i, (fs1 + fs2 - 2. * fcnmin) / step / step);
            if (print.shows(MnPrint.Verbosity.TRACE)) {
                final int jj = j;
                final double gi = grd.get(i), g2i = g2.get(i), xi = x.get(i), st = step;
                print.trace((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                    final int pr = os.precision();
                    os.precision(13);
                    os.setw(10).put(trafo.name(trafo.extOfInt(i))).setw(5).put(jj).put("  ").put(xi).put(" ").put(st)
                        .put(" ").put(fs1).put(" ").put(fs2).put(" ").put(gi).put(" ").put(g2i).endl();
                    os.precision(pr);
                });
            }
            if (Math.abs(grdb4 - grd.get(i)) / (Math.abs(grd.get(i)) + dfmin / step) < strategy.gradientTolerance()) break;
        }
    }
}
