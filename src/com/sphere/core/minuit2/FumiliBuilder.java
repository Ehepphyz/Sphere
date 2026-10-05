package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;

/**
 * Fumili: Newton steps with the approximate Hessian the fit function gives,
 * inside a trust region (default; scaled by the diagonal for "trs") or with
 * a line search and Levenberg-Marquardt damping ("ls").
 */
public final class FumiliBuilder extends MinimumBuilder {

    public enum FumiliMethodType { kLineSearch, kTrustRegion, kTrustRegionScaled }

    private final VariableMetricEDMEstimator estimator = new VariableMetricEDMEstimator();
    private final FumiliErrorUpdator errorUpdator = new FumiliErrorUpdator();
    private FumiliMethodType methodType = FumiliMethodType.kTrustRegion;

    public void setMethod(FumiliMethodType type) {
        methodType = type;
    }

    public VariableMetricEDMEstimator estimator() {
        return estimator;
    }

    public FumiliErrorUpdator errorUpdator() {
        return errorUpdator;
    }

    @Override
    public FunctionMinimum minimum(MnFcn fcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy, int maxfcn,
                                   double edmval) {
        final MnPrint print = new MnPrint("FumiliBuilder", printLevel());
        edmval *= 0.0001;
        print.debug("Convergence when edm <", edmval);
        if (seed.parameters().vec().size() == 0) {
            print.warn("No variable parameters are defined! - Return current function value ");
            return new FunctionMinimum(seed, fcn.up());
        }
        double edm = estimator().estimate(seed.gradient(), seed.error());
        print.debug("initial edm is ", edm);
        FunctionMinimum min = new FunctionMinimum(seed, fcn.up());
        if (edm < 0.) {
            print.error("Initial matrix not positive defined, edm = ", edm, "\nExit minimization ");
            return min;
        }
        final List<MinimumState> result = new ArrayList<>(8);
        result.add(seed.state());
        print.info("Start iterating until Edm is <", edmval, '\n', "Initial state", new MnPrint.Oneline(seed.state()));
        if (traceIter()) traceIteration(result.size() - 1, result.get(result.size() - 1));
        int maxfcnEff = (int) (0.5 * maxfcn);
        int ipass = 0;
        double edmprev = 1;
        do {
            min = minimum(fcn, gc, seed, result, maxfcnEff, edmval);
            if (ipass > 0) {
                if (!min.isValid()) {
                    print.warn("FunctionMinimum is invalid");
                    return min;
                }
            }
            edm = result.get(result.size() - 1).edm();
            print.debug("Approximate Edm", edm, "npass", ipass);
            if (min.error().dcovar() > strategy.hessianRecomputeThreshold()) {
                print.debug("FumiliBuilder will verify convergence and Error matrix; dcov", min.error().dcovar());
                final MinimumState st = new MnHesse(strategy).compute(fcn, min.state(), min.seed().trafo(), maxfcn);
                result.add(st);
                print.info("After Hessian");
                if (traceIter()) traceIteration(result.size() - 1, result.get(result.size() - 1));
                edm = st.edm();
                print.debug("Edm", edm, "State", st);
            }
            if (ipass > 0 && edm >= edmprev) {
                print.warn("Stop iterations, no improvements after Hesse; current Edm", edm, "previous value", edmprev);
                break;
            }
            if (edm > edmval) {
                print.debug("Tolerance not sufficient, continue minimization; Edm", edm, "Requested", edmval);
            } else {
                if (min.isAboveMaxEdm()) {
                    min = new FunctionMinimum(min.seed(), min.states(), min.up());
                    break;
                }
            }
            if (ipass == 0) maxfcnEff = maxfcn;
            ipass++;
            edmprev = edm;
        } while (edm > edmval);
        min.add(result.get(result.size() - 1));
        return min;
    }

    public FunctionMinimum minimum(MnFcn fcn, GradientCalculator gc, MinimumSeed seed, List<MinimumState> result,
                                   int maxfcn, double edmval) {
        final MnMachinePrecision prec = seed.precision();
        final MinimumState initialState = result.get(result.size() - 1);
        double edm = initialState.edm();
        final MnPrint print = new MnPrint("FumiliBuilder");
        print.info("Iterating FumiliBuilder", maxfcn, edmval);
        print.debug("Initial State:", "\n  Parameter", initialState.vec(), "\n  Gradient", initialState.gradient().vec(),
            "\n  Inv Hessian", initialState.error().invHessian(), "\n  edm", initialState.edm(), "\n  maxfcn", maxfcn,
            "\n  tolerance", edmval);
        edm *= (1. + 3. * initialState.error().dcovar());
        LAVector step;
        final boolean doLineSearch = methodType == FumiliMethodType.kLineSearch;
        final boolean doTrustRegion = methodType == FumiliMethodType.kTrustRegion
            || methodType == FumiliMethodType.kTrustRegionScaled;
        final boolean scaleTR = methodType == FumiliMethodType.kTrustRegionScaled;
        final LAVector x0 = initialState.vec();
        final double normX0 = Math.sqrt(MnMatrix.innerProduct(x0, x0));
        double delta = 0.3 * Cxx.max(1.0, normX0);
        final double eta = 0.1;
        final double trFactorUp = 3;
        final double trFactorDown = 0.5;
        boolean acceptNewPoint = true;
        if (doLineSearch) print.info("Using Fumili with a line search algorithm");
        else if (doTrustRegion && scaleTR) {
            print.info("Using Fumili with a scaled trust region algorithm with factors up/down", trFactorUp, trFactorDown);
        } else {
            print.info("Using Fumili with a trust region algorithm with factors up/down", trFactorUp, trFactorDown);
        }
        double lambda = doLineSearch ? 0.001 : 0;
        final MnFcn.Caller fcnCaller = new MnFcn.Caller(fcn);
        do {
            MinimumState s0 = result.get(result.size() - 1);
            step = MnMatrix.times(-1. * 1. * 1., s0.error().invHessian(), s0.gradient().vec());
            print.debug("Iteration -", result.size(), "\n  Fval", s0.fval(), "numOfCall", fcn.numOfCalls(),
                "\n  Internal Parameter values", s0.vec(), "\n  Newton step", step);
            double gdel = MnMatrix.innerProduct(step, s0.gradient().grad());
            if (gdel > 0.) {
                print.warn("Matrix not pos.def, gdel =", gdel, " > 0");
                final MnPosDef psdf = new MnPosDef();
                s0 = psdf.apply(s0, prec);
                step = MnMatrix.times(-1. * 1. * 1., s0.error().invHessian(), s0.gradient().vec());
                gdel = MnMatrix.innerProduct(step, s0.gradient().grad());
                print.warn("After correction, gdel =", gdel);
                if (gdel > 0.) {
                    result.add(s0);
                    return new FunctionMinimum(seed, result, fcn.up());
                }
            }
            final double fval2 = doLineSearch ? fcnCaller.call(MnMatrix.add(s0.vec(), step)) : 0;
            MinimumParameters p = new MinimumParameters(MnMatrix.add(s0.vec(), step), fval2);
            if (doLineSearch && p.fval() >= s0.fval()) {
                print.debug("Do a line search", fcn.numOfCalls());
                final MnLineSearch lsearch = new MnLineSearch();
                final MnParabola.Point pp = lsearch.search(fcn, s0.parameters(), step, gdel, prec);
                if (Math.abs(pp.y() - s0.fval()) < prec.eps()) break;
                p = new MinimumParameters(MnMatrix.addScaled(s0.vec(), pp.x(), step), pp.y());
                print.debug("New point after Line Search :", "\n  FVAL     ", p.fval(), "\n  Parameter", p.vec());
            }
            final LASymMatrix hmat = s0.error().hessian();
            final int n = scaleTR ? hmat.nrow() : 0;
            final LASymMatrix d = new LASymMatrix(n);
            final LASymMatrix dinv = new LASymMatrix(n);
            final LASymMatrix dinv2 = new LASymMatrix(n);
            for (int i = 0; i < n; i++) {
                final double dd = Math.sqrt(hmat.get(i, i));
                d.set(i, i, dd);
                dinv.set(i, i, 1. / dd);
                dinv2.set(i, i, 1. / (dd * dd));
            }
            if (doTrustRegion) {
                double norm;
                if (scaleTR) {
                    print.debug("scaling Trust region with diagonal matrix D ", d);
                    final LAVector stepScaled = MnMatrix.times(d, step);
                    norm = Math.sqrt(MnMatrix.innerProduct(stepScaled, stepScaled));
                } else {
                    norm = Math.sqrt(MnMatrix.innerProduct(step, step));
                }
                if (norm <= delta) {
                    p = new MinimumParameters(MnMatrix.add(s0.vec(), step), fcnCaller.call(MnMatrix.add(s0.vec(), step)));
                    print.debug("Accept full Newton step - it is inside TR ", delta);
                } else {
                    double normGrad2;
                    double gHg;
                    if (scaleTR) {
                        final LAVector gScaled = MnMatrix.times(dinv, s0.gradient().grad());
                        normGrad2 = MnMatrix.innerProduct(gScaled, gScaled);
                        final LASymMatrix hscaled = new LASymMatrix(n);
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j <= i; j++) hscaled.set(i, j, hmat.get(i, j) * dinv.get(i, i) * dinv.get(j, j));
                        }
                        gHg = MnMatrix.similarity(gScaled, hscaled);
                    } else {
                        normGrad2 = MnMatrix.innerProduct(s0.gradient().grad(), s0.gradient().grad());
                        gHg = MnMatrix.similarity(s0.gradient().grad(), s0.error().hessian());
                    }
                    final double normGrad = Math.sqrt(normGrad2);
                    print.debug("computed gdel gHg and normGN", gdel, gHg, norm * norm, normGrad2);
                    if (gHg <= 0.) {
                        // step = -(delta/normGrad) * g
                        step = MnMatrix.scaled(-1. * (delta / normGrad), s0.gradient().grad());
                        if (scaleTR) step = MnMatrix.times(dinv2, step);
                        print.debug("Use as new point the Cauchy  point - along gradient with norm=delta ", delta);
                    } else {
                        final double tau = Cxx.min((normGrad2 * normGrad) / (gHg * delta), 1.0);
                        LAVector stepC = MnMatrix.scaled(-tau * (delta / normGrad), s0.gradient().grad());
                        if (scaleTR) stepC = MnMatrix.times(dinv2, stepC);
                        print.debug("Use as new point the Cauchy  point - along gradient with tau ", tau, "delta = ", delta);
                        final LAVector diffP = MnMatrix.subtract(step, stepC);
                        final double a = MnMatrix.innerProduct(diffP, diffP);
                        final double b = 2. * MnMatrix.innerProduct(stepC, diffP);
                        final double c = scaleTR ? MnMatrix.innerProduct(stepC, stepC) - delta * delta
                            : delta * delta * (tau * tau - 1.);
                        print.debug(" dogleg equation", a, b, c);
                        double t;
                        if (a <= 0) {
                            print.warn("a is equal to zero!  a = ", a);
                            print.info(" delta ", delta, " tau ", tau, " gHg ", gHg, " normgrad2 ", normGrad2);
                            t = -b / c;
                        } else {
                            final double t1 = (-b + Math.sqrt(b * b - 4. * a * c)) / (2.0 * a);
                            final double t2 = (-b - Math.sqrt(b * b - 4. * a * c)) / (2.0 * a);
                            print.debug(" solution dogleg equation", t1, t2);
                            t = (t1 >= 0 && t1 <= 1.) ? t1 : t2;
                        }
                        // step = stepC + t * diffP: stepC, then (diffP rebuilt, scaled by t) added
                        final LAVector tDiff = MnMatrix.subtract(step, stepC).scale(t);
                        step = stepC.copy().plusAssign(tDiff);
                        print.debug("New dogleg point is t = ", t);
                    }
                    print.debug("New accepted step is ", step);
                    p = new MinimumParameters(MnMatrix.add(s0.vec(), step), fcnCaller.call(MnMatrix.add(s0.vec(), step)));
                    norm = delta;
                    gdel = MnMatrix.innerProduct(step, s0.gradient().grad());
                }
                final double svs = 0.5 * MnMatrix.similarity(step, s0.error().hessian());
                final double rho = (p.fval() - s0.fval()) / (gdel + svs);
                if (rho < 0.25) {
                    delta = trFactorDown * delta;
                } else {
                    if (rho > 0.75 && norm == delta) delta = Cxx.min(trFactorUp * delta, 100. * norm);
                }
                print.debug("New point after Trust region :", "norm tr ", norm, " rho ", rho, " delta ", delta, "  FVAL    ",
                    p.fval(), "\n  Parameter", p.vec());
                acceptNewPoint = rho > eta;
                if (acceptNewPoint) {
                    print.debug("Trust region: accept new point p = x + step since rho is larger than eta");
                } else {
                    print.debug("Trust region reject new point and repeat since rho is smaller than eta");
                    p = new MinimumParameters(s0.vec(), s0.fval());
                }
            }
            FunctionGradient g = s0.gradient();
            if (acceptNewPoint || result.size() == 1) {
                print.debug("Before Gradient - NCalls = ", fcn.numOfCalls());
                g = gc.compute(p, s0.gradient());
                print.debug("After Gradient - NCalls = ", fcn.numOfCalls());
            }
            final MinimumError e = errorUpdator().update(s0, p, gc, lambda);
            edm = estimator().estimate(g, s0.error());
            print.debug("Updated new point:", "\n  FVAL     ", p.fval(), "\n  Parameter", p.vec(), "\n  Gradient", g.vec(),
                "\n  InvHessian", e.invHessian(), "\n  Hessian", e.hessian(), "\n  Edm", edm);
            if (edm < 0.) {
                print.warn("Matrix not pos.def., Edm < 0");
                final MnPosDef psdf = new MnPosDef();
                s0 = psdf.apply(s0, prec);
                edm = estimator().estimate(g, s0.error());
                if (edm < 0.) {
                    result.add(s0);
                    if (traceIter()) traceIteration(result.size() - 1, result.get(result.size() - 1));
                    return new FunctionMinimum(seed, result, fcn.up());
                }
            }
            if (doLineSearch) {
                if (p.fval() < s0.fval()) {
                    lambda *= 0.1;
                } else {
                    lambda *= 10;
                    if (edm < 0.1) break;
                }
            }
            print.debug("finish iteration -", result.size(), "lambda =", lambda, "f1 =", p.fval(), "f0 =", s0.fval(),
                "num of calls =", fcn.numOfCalls(), "edm =", edm);
            result.add(new MinimumState(p, e, g, edm, fcn.numOfCalls()));
            if (traceIter()) traceIteration(result.size() - 1, result.get(result.size() - 1));
            print.info(new MnPrint.Oneline(result.get(result.size() - 1), result.size()));
            edm *= (1. + 3. * e.dcovar());
        } while (edm > edmval && fcn.numOfCalls() < maxfcn);
        if (fcn.numOfCalls() >= maxfcn) {
            print.warn("Call limit exceeded", fcn.numOfCalls(), maxfcn);
            return new FunctionMinimum(seed, result, fcn.up(), FunctionMinimum.Status.MnReachedCallLimit);
        }
        if (edm > edmval) {
            if (edm < Math.abs(prec.eps2() * result.get(result.size() - 1).fval())) {
                print.warn("Machine accuracy limits further improvement");
                return new FunctionMinimum(seed, result, fcn.up());
            } else if (edm < 10 * edmval) {
                return new FunctionMinimum(seed, result, fcn.up());
            } else {
                print.warn("No convergence; Edm", edm, "is above tolerance", 10 * edmval);
                return new FunctionMinimum(seed, result, fcn.up(), FunctionMinimum.Status.MnAboveMaxEdm);
            }
        }
        print.debug("Exiting successfully", "Ncalls", fcn.numOfCalls(), "FCN", result.get(result.size() - 1).fval(), "Edm", edm,
            "Requested", edmval);
        return new FunctionMinimum(seed, result, fcn.up());
    }
}
