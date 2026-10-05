package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;

/**
 * The simplex method of Nelder and Mead (Comp. J. 7, 308 (1965)) as in
 * Minuit: reflection, expansion with a parabolic choice of the factor,
 * contraction, until the spread of the function values over the simplex is
 * below the tolerance.
 */
public final class SimplexBuilder extends MinimumBuilder {

    /** The n+1 points of the simplex with their values, and which are highest and lowest (SimplexParameters). */
    public static final class SimplexParameters {
        private final List<Double> values;
        private final List<LAVector> points;
        private int jHigh;
        private int jLow;

        SimplexParameters(List<Double> values, List<LAVector> points, int jh, int jl) {
            this.values = new ArrayList<>(values);
            this.points = new ArrayList<>(points);
            this.jHigh = jh;
            this.jLow = jl;
        }

        /** Replaces the highest point and finds the new highest. */
        void update(double y, LAVector p) {
            values.set(jh(), y);
            points.set(jh(), p.copy());
            if (y < values.get(jl())) jLow = jh();
            int jh = 0;
            for (int i = 1; i < values.size(); i++) {
                if (values.get(i) > values.get(jh)) jh = i;
            }
            jHigh = jh;
        }

        public double value(int i) {
            return values.get(i);
        }

        public LAVector point(int i) {
            return points.get(i);
        }

        public int size() {
            return values.size();
        }

        public int jh() {
            return jHigh;
        }

        public int jl() {
            return jLow;
        }

        public double edm() {
            return values.get(jh()) - values.get(jl());
        }

        /** The extent of the simplex along each parameter. */
        public LAVector dirin() {
            final LAVector dirin = new LAVector(values.size() - 1);
            for (int i = 0; i < values.size() - 1; i++) {
                double pbig = points.get(0).get(i), plit = pbig;
                for (int j = 0; j < values.size(); j++) {
                    if (points.get(j).get(i) < plit) plit = points.get(j).get(i);
                    if (points.get(j).get(i) > pbig) pbig = points.get(j).get(i);
                }
                dirin.set(i, pbig - plit);
            }
            return dirin;
        }
    }

    @Override
    public FunctionMinimum minimum(MnFcn mfcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy, int maxfcn,
                                   double minedm) {
        final MnFcn.Caller mfcnCaller = new MnFcn.Caller(mfcn);
        final MnPrint print = new MnPrint("SimplexBuilder", printLevel());
        print.debug("Running with maxfcn", maxfcn, "minedm", minedm);
        final MnMachinePrecision prec = seed.precision();
        final LAVector x = seed.parameters().vec().copy();
        final LAVector step = MnMatrix.scaled(10., seed.gradient().gstep());
        final int n = x.size();
        if (n == 0) {
            final MinimumState st = new MinimumState(new MinimumParameters(new LAVector(0), new LAVector(0), mfcnCaller.call(x)),
                0.0, mfcn.numOfCalls());
            return new FunctionMinimum(seed, List.of(st), mfcn.up());
        }
        final double wg = 1. / (double) n;
        final double alpha = 1.;
        final double beta = 0.5;
        final double gamma = 2.;
        final double rhomin = 4.;
        final double rhomax = 8.;
        final double rho1 = 1. + alpha;
        final double rho2 = 1. + alpha * gamma;
        final List<Double> vals = new ArrayList<>(n + 1);
        final List<LAVector> pts = new ArrayList<>(n + 1);
        vals.add(seed.fval());
        pts.add(x.copy());
        int jl = 0;
        int jh = 0;
        double amin = seed.fval();
        double aming = seed.fval();
        for (int i = 0; i < n; i++) {
            final double dmin = 8. * prec.eps2() * (Math.abs(x.get(i)) + prec.eps2());
            if (step.get(i) < dmin) step.set(i, dmin);
            x.set(i, x.get(i) + step.get(i));
            final double tmp = mfcnCaller.call(x);
            if (tmp < amin) {
                amin = tmp;
                jl = i + 1;
            }
            if (tmp > aming) {
                aming = tmp;
                jh = i + 1;
            }
            vals.add(tmp);
            pts.add(x.copy());
            x.set(i, x.get(i) - step.get(i));
        }
        final SimplexParameters simplex = new SimplexParameters(vals, pts, jh, jl);
        if (print.shows(MnPrint.Verbosity.DEBUG)) {
            final int jl0 = jl, jh0 = jh;
            final double amin0 = amin, aming0 = aming;
            print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                os.put("Initial parameters - min  ").put(jl0).put("  ").put(amin0).put(" max ").put(jh0).put("  ").put(aming0)
                    .put('\n');
                for (int i = 0; i < simplex.size(); ++i) {
                    os.put(" i = ").put(i).put(" x = ");
                    simplex.point(i).print(os);
                    os.put(" fval(x) = ").put(simplex.value(i)).put('\n');
                }
            });
        }
        double edmPrev = simplex.edm();
        int niterations = 0;
        do {
            jl = simplex.jl();
            jh = simplex.jh();
            amin = simplex.value(jl);
            edmPrev = simplex.edm();
            print.debug("iteration: edm =", simplex.edm(), '\n', "--> Min Param is", jl, "pmin", simplex.point(jl), "f(pmin)",
                amin, '\n', "--> Max param is", jh, simplex.value(jh));
            if (traceIter()) {
                traceIteration(niterations, new MinimumState(new MinimumParameters(simplex.point(jl), simplex.value(jl)),
                    simplex.edm(), mfcn.numOfCalls()));
            }
            print.info(new MnPrint.Oneline(simplex.value(jl), simplex.edm(), mfcn.numOfCalls(), niterations));
            niterations++;
            final LAVector pbar = new LAVector(n);
            for (int i = 0; i < n + 1; i++) {
                if (i == jh) continue;
                pbar.plusAssign(wg, simplex.point(i));
            }
            final LAVector pstar = MnMatrix.sum(1. + alpha, pbar, -1. * alpha, simplex.point(jh));
            final double ystar = mfcnCaller.call(pstar);
            print.debug("pbar", pbar, "pstar", pstar, "f(pstar)", ystar);
            if (ystar > amin) {
                if (ystar < simplex.value(jh)) {
                    simplex.update(ystar, pstar);
                    if (jh != simplex.jh()) continue;
                }
                final LAVector pstst = MnMatrix.sum(beta, simplex.point(jh), 1. - beta, pbar);
                final double ystst = mfcnCaller.call(pstst);
                print.debug("Reduced simplex pstst", pstst, "f(pstst)", ystst);
                if (ystst > simplex.value(jh)) break;
                simplex.update(ystst, pstst);
                continue;
            }
            LAVector pstst = MnMatrix.sum(gamma, pstar, 1. - gamma, pbar);
            double ystst = mfcnCaller.call(pstst);
            print.debug("pstst", pstst, "f(pstst)", ystst);
            final double y1 = (ystar - simplex.value(jh)) * rho2;
            final double y2 = (ystst - simplex.value(jh)) * rho1;
            double rho = 0.5 * (rho2 * y1 - rho1 * y2) / (y1 - y2);
            if (rho < rhomin) {
                if (ystst < simplex.value(jl)) simplex.update(ystst, pstst);
                else simplex.update(ystar, pstar);
                continue;
            }
            if (rho > rhomax) rho = rhomax;
            final LAVector prho = MnMatrix.sum(rho, pbar, 1. - rho, simplex.point(jh));
            final double yrho = mfcnCaller.call(prho);
            print.debug("prho", prho, "f(prho)", yrho);
            if (yrho < simplex.value(jl) && yrho < ystst) {
                simplex.update(yrho, prho);
                continue;
            }
            if (ystst < simplex.value(jl)) {
                simplex.update(ystst, pstst);
                continue;
            }
            if (yrho > simplex.value(jl)) {
                if (ystst < simplex.value(jl)) simplex.update(ystst, pstst);
                else simplex.update(ystar, pstar);
                continue;
            }
            if (ystar > simplex.value(jh)) {
                pstst = MnMatrix.sum(beta, simplex.point(jh), 1. - beta, pbar);
                ystst = mfcnCaller.call(pstst);
                if (ystst > simplex.value(jh)) break;
                simplex.update(ystst, pstst);
            }
            print.debug("End loop : Edm", simplex.edm(), "pstst", pstst, "f(pstst)", ystst);
        } while ((simplex.edm() > minedm || edmPrev > minedm) && mfcn.numOfCalls() < maxfcn);
        jl = simplex.jl();
        jh = simplex.jh();
        amin = simplex.value(jl);
        LAVector pbar = new LAVector(n);
        for (int i = 0; i < n + 1; i++) {
            if (i == jh) continue;
            pbar.plusAssign(wg, simplex.point(i));
        }
        double ybar = mfcnCaller.call(pbar);
        if (ybar < amin) {
            simplex.update(ybar, pbar);
        } else {
            pbar = simplex.point(jl).copy();
            ybar = simplex.value(jl);
        }
        final LAVector dirin = simplex.dirin();
        dirin.scale(Math.sqrt(mfcn.up() / simplex.edm()));
        print.debug("End simplex edm =", simplex.edm(), "pbar =", pbar, "f(p) =", ybar);
        final MinimumState st = new MinimumState(new MinimumParameters(pbar, dirin, ybar), simplex.edm(), mfcn.numOfCalls());
        print.info("Final iteration", new MnPrint.Oneline(st));
        if (traceIter()) traceIteration(niterations, st);
        if (mfcn.numOfCalls() > maxfcn) {
            print.warn("Simplex did not converge, #fcn calls exhausted");
            return new FunctionMinimum(seed, List.of(st), mfcn.up(), FunctionMinimum.Status.MnReachedCallLimit);
        }
        if (simplex.edm() > minedm) {
            print.warn("Simplex did not converge, edm > minedm");
            return new FunctionMinimum(seed, List.of(st), mfcn.up(), FunctionMinimum.Status.MnAboveMaxEdm);
        }
        return new FunctionMinimum(seed, List.of(st), mfcn.up());
    }
}
