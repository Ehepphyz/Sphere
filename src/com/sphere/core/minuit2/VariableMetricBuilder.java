package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;

/**
 * Migrad: the variable metric method. From the seed, Newton steps -V g
 * along which a line search finds the best point, the inverse Hessian V
 * updated from each step (Davidon, or BFGS), until the expected distance to
 * the minimum is below the tolerance; then Hesse checks the matrix when it
 * changed too much, and the iterations resume if that EDM is still too large.
 */
public final class VariableMetricBuilder extends MinimumBuilder {

    public enum ErrorUpdatorType { kDavidon, kBFGS }

    private final VariableMetricEDMEstimator estimator = new VariableMetricEDMEstimator();
    private final MinimumErrorUpdator errorUpdator;

    public VariableMetricBuilder() {
        this(ErrorUpdatorType.kDavidon);
    }

    public VariableMetricBuilder(ErrorUpdatorType type) {
        errorUpdator = type == ErrorUpdatorType.kBFGS ? new BFGSErrorUpdator() : new DavidonErrorUpdator();
    }

    public VariableMetricEDMEstimator estimator() {
        return estimator;
    }

    public MinimumErrorUpdator errorUpdator() {
        return errorUpdator;
    }

    void addResult(List<MinimumState> result, MinimumState state) {
        result.add(state);
        if (traceIter()) {
            traceIteration(result.size() - 1, result.get(result.size() - 1));
        } else {
            new MnPrint("VariableMetricBuilder", printLevel()).info(new MnPrint.Oneline(result.get(result.size() - 1),
                result.size() - 1));
        }
    }

    @Override
    public FunctionMinimum minimum(MnFcn fcn, GradientCalculator gc, MinimumSeed seed, MnStrategy strategy, int maxfcn,
                                   double edmval) {
        final MnPrint print = new MnPrint("VariableMetricBuilder", printLevel());
        edmval *= 0.002;
        double edm = seed.state().edm();
        FunctionMinimum min = new FunctionMinimum(seed, fcn.up());
        if (seed.parameters().vec().size() == 0) {
            print.warn("No free parameters.");
            return min;
        }
        if (!seed.isValid()) {
            print.error("Minimum seed invalid.");
            return min;
        }
        if (edm < 0.) {
            print.error("Initial matrix not pos.def.");
            return min;
        }
        final List<MinimumState> result = new ArrayList<>(storageLevel() > 0 ? 10 : 2);
        print.info("Start iterating until Edm is <", edmval, "with call limit =", maxfcn);
        final long t0 = System.nanoTime();
        try {
            addResult(result, seed.state());
            int maxfcnEff = maxfcn;
            int ipass = 0;
            boolean iterate;
            do {
                iterate = false;
                print.debug(ipass > 0 ? "Continue" : "Start", "iterating...");
                min = minimum(fcn, gc, seed, result, maxfcnEff, edmval);
                if (min.hasReachedCallLimit()) {
                    print.warn("FunctionMinimum is invalid, reached function call limit");
                    return min;
                }
                if (ipass > 0) {
                    if (!min.isValid()) {
                        print.warn("FunctionMinimum is invalid after second try");
                        return min;
                    }
                }
                edm = result.get(result.size() - 1).edm();
                if (min.error().dcovar() > strategy.hessianRecomputeThreshold()) {
                    print.debug("MnMigrad will verify convergence and Error matrix; dcov =", min.error().dcovar());
                    final MnStrategy strat = new MnStrategy(strategy);
                    strat.setHessianForcePosDef(1);
                    final MinimumState st = new MnHesse(strat).compute(fcn, min.state(), min.seed().trafo(), maxfcn);
                    print.info("After Hessian");
                    addResult(result, st);
                    if (!st.isValid()) {
                        print.warn("Invalid Hessian - exit the minimization");
                        break;
                    }
                    edm = st.edm();
                    print.debug("New Edm", edm, "Requested", edmval);
                    if (edm > edmval) {
                        final double machineLimit = Math.abs(seed.precision().eps2() * result.get(result.size() - 1).fval());
                        if (edm >= machineLimit) {
                            iterate = true;
                            print.info("Tolerance not sufficient, continue minimization; Edm", edm, "Required", edmval);
                        } else {
                            print.warn("Reached machine accuracy limit; Edm", edm, "is smaller than machine limit",
                                machineLimit, "while", edmval, "was requested");
                        }
                    }
                }
                if (ipass == 0) maxfcnEff = (int) (maxfcn * 1.3);
                ipass++;
            } while (iterate);
            final MinimumState latest = result.get(result.size() - 1);
            if (edm > 10 * edmval) {
                min.add(latest, FunctionMinimum.Status.MnAboveMaxEdm);
                print.warn("No convergence; Edm", edm, "is above tolerance", 10 * edmval);
            } else if (latest.error().hasReachedCallLimit()) {
                min.add(latest, FunctionMinimum.Status.MnReachedCallLimit);
            } else if (latest.error().isAvailable()) {
                if (min.isAboveMaxEdm()) print.info("Edm has been re-computed after Hesse; Edm", edm, "is now within tolerance");
                min.add(latest);
            }
            print.debug("Minimum found", min);
            return min;
        } finally {
            print.info("Stop iterating after", MnSeedGenerator.elapsed(t0));
        }
    }

    /** The iterations proper, appending to result. */
    public FunctionMinimum minimum(MnFcn fcn, GradientCalculator gc, MinimumSeed seed, List<MinimumState> result,
                                   int maxfcn, double edmval) {
        final MnPrint print = new MnPrint("VariableMetricBuilder", printLevel());
        final MnMachinePrecision prec = seed.precision();
        final MinimumState initialState = result.get(result.size() - 1);
        double edm = initialState.edm();
        print.debug("Initial State:", "\n  Parameter:", initialState.vec(), "\n  Gradient:", initialState.gradient().vec(),
            "\n  InvHessian:", initialState.error().invHessian(), "\n  Edm:", initialState.edm());
        edm *= (1. + 3. * initialState.error().dcovar());
        final MnLineSearch lsearch = new MnLineSearch();
        LAVector step;
        MinimumState s0 = result.get(result.size() - 1);
        do {
            step = MnMatrix.times(-1. * 1. * 1., s0.error().invHessian(), s0.gradient().vec());
            print.debug("Iteration", result.size(), "Fval", s0.fval(), "numOfCall", fcn.numOfCalls(),
                "\n  Internal parameters", s0.vec(), "\n  Newton step", step);
            if (MnMatrix.innerProduct(s0.gradient().vec(), s0.gradient().vec()) <= 0) {
                print.debug("all derivatives are zero - return current status");
                break;
            }
            double gdel = MnMatrix.innerProduct(step, s0.gradient().grad());
            if (gdel > 0.) {
                print.warn("Matrix not pos.def, gdel =", gdel, "> 0");
                final MnPosDef psdf = new MnPosDef();
                s0 = psdf.apply(s0, prec);
                step = MnMatrix.times(-1. * 1. * 1., s0.error().invHessian(), s0.gradient().vec());
                gdel = MnMatrix.innerProduct(step, s0.gradient().grad());
                print.warn("gdel =", gdel);
                if (gdel > 0.) {
                    addResult(result, s0);
                    return new FunctionMinimum(seed, result, fcn.up());
                }
            }
            final MnParabola.Point pp = lsearch.search(fcn, s0.parameters(), step, gdel, prec);
            if (Math.abs(pp.y() - s0.fval()) <= Math.abs(s0.fval()) * prec.eps()) {
                print.warn("No improvement in line search");
                if (result.size() <= 1) {
                    addResult(result, new MinimumState(s0.parameters(), s0.error(), s0.gradient(), s0.edm(), fcn.numOfCalls()));
                } else {
                    addResult(result, new MinimumState(pp.y(), s0.edm(), fcn.numOfCalls()));
                }
                break;
            }
            print.debug("Result after line search :", "\n  x =", pp.x(), "\n  Old Fval =", s0.fval(), "\n  New Fval =", pp.y(),
                "\n  NFcalls =", fcn.numOfCalls());
            final MinimumParameters p = new MinimumParameters(MnMatrix.addScaled(s0.vec(), pp.x(), step), pp.y());
            final FunctionGradient g = gc.compute(p, s0.gradient());
            edm = estimator().estimate(g, s0.error());
            if (Double.isNaN(edm)) {
                print.warn("Edm is NaN; stop iterations");
                addResult(result, s0);
                return new FunctionMinimum(seed, result, fcn.up());
            }
            if (edm < 0.) {
                print.warn("Matrix not pos.def., try to make pos.def.");
                final MnPosDef psdf = new MnPosDef();
                s0 = psdf.apply(s0, prec);
                edm = estimator().estimate(g, s0.error());
                if (edm < 0.) {
                    print.warn("Matrix still not pos.def.; stop iterations");
                    addResult(result, s0);
                    return new FunctionMinimum(seed, result, fcn.up());
                }
            }
            final MinimumError e = errorUpdator().update(s0, p, g);
            print.debug("Updated new point:", "\n  Parameter:", p.vec(), "\n  Gradient:", g.vec(), "\n  InvHessian:",
                e.matrix(), "\n  Edm:", edm);
            s0 = new MinimumState(p, e, g, edm, fcn.numOfCalls());
            if (storageLevel() != 0 || result.size() <= 1) addResult(result, s0);
            else addResult(result, new MinimumState(p.fval(), edm, fcn.numOfCalls()));
            edm *= (1. + 3. * e.dcovar());
            print.debug("Dcovar =", e.dcovar(), "\tCorrected edm =", edm);
        } while (edm > edmval && fcn.numOfCalls() < maxfcn);
        if (!result.get(result.size() - 1).isValid()) result.set(result.size() - 1, s0);
        if (fcn.numOfCalls() >= maxfcn) {
            print.warn("Call limit exceeded");
            return new FunctionMinimum(seed, result, fcn.up(), FunctionMinimum.Status.MnReachedCallLimit);
        }
        if (edm > edmval) {
            if (edm < 10 * edmval) {
                print.info("Edm is close to limit - return current minimum");
                return new FunctionMinimum(seed, result, fcn.up());
            } else if (edm < Math.abs(prec.eps2() * result.get(result.size() - 1).fval())) {
                print.warn("Edm is limited by Machine accuracy - return current minimum");
                return new FunctionMinimum(seed, result, fcn.up());
            } else {
                print.warn("Iterations finish without convergence; Edm", edm, "Requested", edmval);
                return new FunctionMinimum(seed, result, fcn.up(), FunctionMinimum.Status.MnAboveMaxEdm);
            }
        }
        print.debug("Exiting successfully;", "Ncalls", fcn.numOfCalls(), "FCN", result.get(result.size() - 1).fval(), "Edm",
            edm, "Requested", edmval);
        return new FunctionMinimum(seed, result, fcn.up());
    }
}
