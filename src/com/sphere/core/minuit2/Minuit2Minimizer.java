package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Minuit2 behind ROOT's generic minimizer interface (ROOT::Math::Minimizer),
 * as ROOT's fitting uses it: variables set by index and name, Minimize (with
 * Hesse afterwards when errors are to be validated), the status codes,
 * Minos, Scan, Contour, covariance and correlations.
 *
 * <p>Status after Minimize: 0 fine, 1 covariance made positive-definite, 2
 * Hesse not valid, 3 EDM above the maximum, 4 call limit, 5 covariance not
 * positive-definite, 6 unknown failure; Minos adds 10 times its status and
 * Hesse 100 times its.
 */
public final class Minuit2Minimizer {

    public enum EMinimizerType { kMigrad, kSimplex, kCombined, kScan, kFumili, kMigradBFGS }

    /* ---- options (ROOT::Math::MinimizerOptions) -------------------------------- */
    private int printLevel = 0;
    private int maxFunctionCalls = 0;
    private int maxIterations = 0;
    private double tolerance = 1.E-2;
    private double precision = -1;
    private int strategyLevel = 1;
    private double errorDef = 1.;
    private boolean validError = false;
    private final Map<String, Object> extraOptions = new HashMap<>();

    private int dim;
    private boolean useFumili;
    private ModularFunctionMinimizer minimizer;
    private FCNBase minuitFCN;
    private MnUserParameterState state = new MnUserParameterState();
    private FunctionMinimum minimum;
    private double[] values = new double[0];
    private double[] errors = new double[0];
    private int status = -1;
    private int minosStatus = -1;

    public Minuit2Minimizer() {
        this(EMinimizerType.kMigrad);
    }

    public Minuit2Minimizer(EMinimizerType type) {
        setMinimizerType(type);
    }

    /** By name: "migrad" (or ""), "simplex", "minimize", "scan", "fumili" (or "fumili2"), "bfgs". */
    public Minuit2Minimizer(String type) {
        final String algoname = type == null ? "" : type.toLowerCase(Locale.ROOT);
        EMinimizerType algoType = EMinimizerType.kMigrad;
        if (algoname.equals("simplex")) algoType = EMinimizerType.kSimplex;
        if (algoname.equals("minimize")) algoType = EMinimizerType.kCombined;
        if (algoname.equals("scan")) algoType = EMinimizerType.kScan;
        if (algoname.equals("fumili") || algoname.equals("fumili2")) algoType = EMinimizerType.kFumili;
        if (algoname.equals("bfgs")) algoType = EMinimizerType.kMigradBFGS;
        setMinimizerType(algoType);
    }

    public void setMinimizerType(EMinimizerType type) {
        useFumili = false;
        switch (type) {
            case kMigradBFGS -> minimizer = VariableMetricMinimizer.bfgs();
            case kSimplex -> minimizer = new SimplexMinimizer();
            case kCombined -> minimizer = new CombinedMinimizer();
            case kScan -> minimizer = new ScanMinimizer();
            case kFumili -> {
                minimizer = new FumiliMinimizer();
                useFumili = true;
            }
            default -> minimizer = new VariableMetricMinimizer();
        }
    }

    public void clear() {
        state = new MnUserParameterState();
        minimum = null;
    }

    /* ---- options ---------------------------------------------------------- */

    public int printLevel() {
        return printLevel;
    }

    public void setPrintLevel(int level) {
        printLevel = level;
    }

    public int maxFunctionCalls() {
        return maxFunctionCalls;
    }

    /** 0 keeps the default 200 + 100 n + 5 n^2 (as ROOT, a value of 0 is ignored). */
    public void setMaxFunctionCalls(int maxfcn) {
        if (maxfcn > 0) maxFunctionCalls = maxfcn;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxiter) {
        if (maxiter > 0) maxIterations = maxiter;
    }

    public double tolerance() {
        return tolerance;
    }

    /** Migrad stops when EDM &lt; 0.002 * tolerance * Up. */
    public void setTolerance(double tol) {
        tolerance = tol;
    }

    public double precision() {
        return precision;
    }

    public void setPrecision(double prec) {
        precision = prec;
    }

    public int strategy() {
        return strategyLevel;
    }

    public void setStrategy(int s) {
        strategyLevel = s;
    }

    public double errorDef() {
        return errorDef;
    }

    public void setErrorDef(double up) {
        errorDef = up;
    }

    public boolean isValidError() {
        return validError;
    }

    /** True: Minimize runs Hesse after the minimization, for accurate errors. */
    public void setValidError(boolean on) {
        validError = on;
    }

    /**
     * Minuit2's extra options, by name: GradientNCycles, HessianNCycles,
     * HessianGradientNCycles (int), GradientTolerance, GradientStepTolerance,
     * HessianStepTolerance, HessianG2Tolerance (double),
     * HessianCentralFDMixedDerivatives, HessianForcePosDef, StorageLevel
     * (int), FumiliMethod ("tr", "trs", "ls").
     */
    public void setExtraOption(String name, Object value) {
        extraOptions.put(name, value);
    }

    public Map<String, Object> extraOptions() {
        return extraOptions;
    }

    /* ---- variables -------------------------------------------------------- */

    public boolean setVariable(int ivar, String name, double val, double step) {
        final MnPrint print = new MnPrint("Minuit2Minimizer::SetVariable", printLevel());
        if (step <= 0) {
            print.info("Parameter", name, "has zero or invalid step size - consider it as constant");
            state.add(name, val);
        } else {
            state.add(name, val, step);
        }
        final int minuit2Index = state.index(name);
        if (minuit2Index != ivar) {
            print.warn("Wrong index", minuit2Index, "used for the variable", name);
            return false;
        }
        state.removeLimits(ivar);
        return true;
    }

    public boolean setCovarianceDiag(double[] d2, int n) {
        final double[] cov = new double[n * (n + 1) / 2];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) cov[i + j * (j + 1) / 2] = (i == j) ? d2[i] : 0.;
        }
        return setCovariance(cov, n);
    }

    public boolean setCovariance(double[] cov, int nrow) {
        state.addCovariance(new MnUserCovariance(cov, nrow));
        return true;
    }

    public boolean setLowerLimitedVariable(int ivar, String name, double val, double step, double lower) {
        if (!setVariable(ivar, name, val, step)) return false;
        state.setLowerLimit(ivar, lower);
        return true;
    }

    public boolean setUpperLimitedVariable(int ivar, String name, double val, double step, double upper) {
        if (!setVariable(ivar, name, val, step)) return false;
        state.setUpperLimit(ivar, upper);
        return true;
    }

    public boolean setLimitedVariable(int ivar, String name, double val, double step, double lower, double upper) {
        if (!setVariable(ivar, name, val, step)) return false;
        state.setLimits(ivar, lower, upper);
        return true;
    }

    public boolean setFixedVariable(int ivar, String name, double val) {
        final double step = (val != 0) ? 0.1 * Math.abs(val) : 0.1;
        if (!setVariable(ivar, name, val, step)) ivar = state.index(name);
        state.fix(ivar);
        return true;
    }

    public String variableName(int ivar) {
        if (ivar >= state.minuitParameters().size()) return "";
        return state.name(ivar);
    }

    public int variableIndex(String name) {
        return state.trafo().findIndex(name);
    }

    public boolean setVariableValue(int ivar, double val) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.setValue(ivar, val);
        return true;
    }

    public boolean setVariableValues(double[] x) {
        final int n = state.minuitParameters().size();
        if (n == 0) return false;
        for (int ivar = 0; ivar < n; ++ivar) state.setValue(ivar, x[ivar]);
        return true;
    }

    public boolean setVariableStepSize(int ivar, double step) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.setError(ivar, step);
        return true;
    }

    public boolean setVariableLowerLimit(int ivar, double lower) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.setLowerLimit(ivar, lower);
        return true;
    }

    public boolean setVariableUpperLimit(int ivar, double upper) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.setUpperLimit(ivar, upper);
        return true;
    }

    public boolean setVariableLimits(int ivar, double lower, double upper) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.setLimits(ivar, lower, upper);
        return true;
    }

    public boolean fixVariable(int ivar) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.fix(ivar);
        return true;
    }

    public boolean releaseVariable(int ivar) {
        if (ivar >= state.minuitParameters().size()) return false;
        state.release(ivar);
        return true;
    }

    public boolean isFixedVariable(int ivar) {
        if (ivar >= state.minuitParameters().size()) {
            new MnPrint("Minuit2Minimizer", printLevel()).error("Wrong variable index");
            return false;
        }
        return state.parameter(ivar).isFixed() || state.parameter(ivar).isConst();
    }

    /* ---- the function ----------------------------------------------------- */

    /** The function of ndim variables (the gradient too when given). */
    public void setFunction(int ndim, ToDoubleFunction<double[]> f, FCNAdapter.Gradient gradient) {
        dim = ndim;
        if (useFumili) {
            new MnPrint("Minuit2Minimizer", printLevel()).error("Wrong Fit method function for Fumili");
            minuitFCN = null;
            return;
        }
        final FCNAdapter adapter = new FCNAdapter(f, errorDef());
        if (gradient != null) adapter.setGradientFunction(gradient);
        minuitFCN = adapter;
    }

    public void setFunction(int ndim, ToDoubleFunction<double[]> f) {
        setFunction(ndim, f, null);
    }

    /** A Minuit2 function directly (a FumiliFCNBase for Fumili). */
    public void setFCN(int ndim, FCNBase fcn) {
        dim = ndim;
        minuitFCN = fcn;
    }

    public void setHessianFunction(java.util.function.BiFunction<double[], double[], Boolean> hfunc) {
        if (useFumili) return;
        if (minuitFCN instanceof FCNAdapter a) a.setHessianFunction(hfunc);
    }

    public FCNBase getFCN() {
        return minuitFCN;
    }

    public ModularFunctionMinimizer getMinimizer() {
        return minimizer;
    }

    public MnUserParameterState state() {
        return state;
    }

    public FunctionMinimum functionMinimum() {
        return minimum;
    }

    private int intOption(String name, int def) {
        final Object v = extraOptions.get(name);
        return v instanceof Number n ? n.intValue() : def;
    }

    private double doubleOption(String name, double def) {
        final Object v = extraOptions.get(name);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    /** The strategy of the level, with the extra options that override its tunables. */
    MnStrategy customizedStrategy(int level) {
        final MnStrategy st = new MnStrategy(level);
        if (extraOptions.isEmpty()) return st;
        st.setGradientNCycles(intOption("GradientNCycles", st.gradientNCycles()));
        st.setHessianNCycles(intOption("HessianNCycles", st.hessianNCycles()));
        st.setHessianGradientNCycles(intOption("HessianGradientNCycles", st.hessianGradientNCycles()));
        st.setGradientTolerance(doubleOption("GradientTolerance", st.gradientTolerance()));
        st.setGradientStepTolerance(doubleOption("GradientStepTolerance", st.gradientStepTolerance()));
        st.setHessianStepTolerance(doubleOption("HessianStepTolerance", st.hessianStepTolerance()));
        st.setHessianG2Tolerance(doubleOption("HessianG2Tolerance", st.hessianG2Tolerance()));
        st.setHessianCentralFDMixedDerivatives(intOption("HessianCentralFDMixedDerivatives",
            st.hessianCentralFDMixedDerivatives()));
        st.setHessianForcePosDef(intOption("HessianForcePosDef", st.hessianForcePosDef()));
        return st;
    }

    /* ---- minimization ----------------------------------------------------- */

    /** Minimizes; true when the minimum is valid. */
    public boolean minimize() {
        final MnPrint print = new MnPrint("Minuit2Minimizer::Minimize", printLevel());
        if (minuitFCN == null) {
            print.error("FCN function has not been set");
            return false;
        }
        minimum = null;
        final int maxfcn = maxFunctionCalls();
        final double tol = tolerance();
        final int level = strategy();
        minuitFCN.setErrorDef(errorDef());
        final int pl = printLevel();
        print.debug("Minuit print level is", pl);
        if (printLevel() >= 1) {
            int maxfcnUsed = maxfcn;
            if (maxfcnUsed == 0) {
                final int nvar = state.variableParameters();
                maxfcnUsed = 200 + 100 * nvar + 5 * nvar * nvar;
            }
            cout().put("Minuit2Minimizer: Minimize with max-calls ").put(maxfcnUsed).put(" convergence for edm < ").put(tol)
                .put(" strategy ").put(level).endl();
            flush();
        }
        minimizer.builder().setPrintLevel(pl);
        final int prevGlobalLevel = MnPrint.setGlobalLevel(pl);
        try {
            if (precision() > 0) state.setPrecision(precision());
            if (!extraOptions.isEmpty()) {
                if (extraOptions.get("StorageLevel") instanceof Number n) setStorageLevel(n.intValue());
                if (useFumili && extraOptions.get("FumiliMethod") instanceof String m && minimizer instanceof FumiliMinimizer fm) {
                    fm.setMethod(m);
                }
                if (pl > 0) {
                    cout().put("Minuit2Minimizer::Minuit  - Changing default options").endl();
                    for (Map.Entry<String, Object> e : extraOptions.entrySet()) {
                        cout().put(e.getKey()).put(" : ").put(String.valueOf(e.getValue())).endl();
                    }
                    flush();
                }
            }
            MnTraceObject traceObj = null;
            if (pl == 100 || (pl >= 10000 && pl < 20000)) {
                traceObj = new MnTraceObject(pl - 10000);
                traceObj.init(state);
                setTraceObject(traceObj);
            }
            final MnStrategy strat = customizedStrategy(level);
            final FunctionMinimum min = minimizer.minimize(minuitFCN, state, strat, maxfcn, tol);
            minimum = min;
            if (minimum.isValid() && isValidError() && minimum.state().error().dcovar() != 0) {
                new MnHesse(strat).apply(minuitFCN, minimum, maxfcn);
            }
        } finally {
            MnPrint.setGlobalLevel(prevGlobalLevel);
        }
        state = new MnUserParameterState(minimum.userState());
        return examineMinimum(minimum);
    }

    private boolean examineMinimum(FunctionMinimum min) {
        final int debugLevel = printLevel();
        if (debugLevel >= 3) {
            final List<MinimumState> iterationStates = min.states();
            final COStream os = cout();
            os.put("Number of iterations ").put(iterationStates.size()).endl();
            for (int i = 0; i < iterationStates.size(); ++i) {
                final MinimumState st = iterationStates.get(i);
                os.put("----------> Iteration ").put(i).endl();
                final int pr = os.precision();
                os.precision(12);
                os.put("            FVAL = ").put(st.fval()).put(" Edm = ").put(st.edm()).put(" Nfcn = ").put(st.nfcn()).endl();
                os.precision(pr);
                if (st.hasCovariance()) os.put("            Error matrix change = ").put(st.error().dcovar()).endl();
                if (st.hasParameters()) {
                    os.put("            Parameters : ");
                    for (int j = 0; j < st.size(); ++j) os.put(" p").put(j).put(" = ").put(state.int2ext(j, st.vec().get(j)));
                    os.endl();
                }
            }
            flush();
        }
        status = 0;
        String txt = "";
        if (!min.hasPosDefCovar()) {
            txt = "Covar is not pos def";
            status = 5;
        }
        if (min.hasMadePosDefCovar()) {
            txt = "Covar was made pos def";
            status = 1;
        }
        if (min.hesseFailed()) {
            txt = "Hesse is not valid";
            status = 2;
        }
        if (min.isAboveMaxEdm()) {
            txt = "Edm is above max";
            status = 3;
        }
        if (min.hasReachedCallLimit()) {
            txt = "Reached call limit";
            status = 4;
        }
        final MnPrint print = new MnPrint("Minuit2Minimizer::Minimize", debugLevel);
        final boolean validMinimum = min.isValid();
        if (validMinimum) {
            if (status != 0 && debugLevel > 0) print.warn(txt);
        } else {
            if (status == 0) {
                txt = "unknown failure";
                status = 6;
            }
            print.warn("Minimization did NOT converge,", txt);
        }
        if (debugLevel >= 1) printResults();
        final List<MinuitParameter> paramsObj = state.minuitParameters();
        if (paramsObj.isEmpty()) return false;
        if (values.length != dim) values = new double[dim];
        for (int i = 0; i < dim; ++i) values[i] = paramsObj.get(i).value();
        return validMinimum;
    }

    public void printResults() {
        if (minimum == null) return;
        final COStream os = cout();
        if (minimum.isValid()) {
            os.put("Minuit2Minimizer : Valid minimum - status = ").put(status).endl();
            final int pr = os.precision();
            os.precision(18);
            os.put("FVAL  = ").put(state.fval()).endl();
            os.put("Edm   = ").put(state.edm()).endl();
            os.precision(pr);
            os.put("Nfcn  = ").put(state.nfcn()).endl();
            for (int i = 0; i < state.minuitParameters().size(); ++i) {
                final MinuitParameter par = state.parameter(i);
                os.put(par.name()).put("\t  = ").put(par.value()).put("\t ");
                if (par.isFixed()) os.put("(fixed)").endl();
                else if (par.isConst()) os.put("(const)").endl();
                else if (par.hasLimits()) os.put("+/-  ").put(par.error()).put("\t(limited)").endl();
                else os.put("+/-  ").put(par.error()).endl();
            }
        } else {
            os.put("Minuit2Minimizer : Invalid minimum - status = ").put(status).endl();
            os.put("FVAL  = ").put(state.fval()).endl();
            os.put("Edm   = ").put(state.edm()).endl();
            os.put("Nfcn  = ").put(state.nfcn()).endl();
        }
        flush();
    }

    /* ---- results ---------------------------------------------------------- */

    public double minValue() {
        return state.fval();
    }

    public double edm() {
        return state.edm();
    }

    public double[] x() {
        return values.clone();
    }

    public int nCalls() {
        return state.nfcn();
    }

    public int nIterations() {
        return nCalls();
    }

    public int nDim() {
        return dim;
    }

    public int nFree() {
        return state.variableParameters();
    }

    public int status() {
        return status;
    }

    public int minosStatus() {
        return minosStatus;
    }

    /** The errors, 0 for fixed and constant parameters. */
    public double[] errors() {
        final List<MinuitParameter> paramsObj = state.minuitParameters();
        if (paramsObj.isEmpty()) return null;
        if (errors.length != dim) errors = new double[dim];
        for (int i = 0; i < dim; ++i) {
            final MinuitParameter par = paramsObj.get(i);
            errors[i] = par.isFixed() || par.isConst() ? 0 : par.error();
        }
        return errors.clone();
    }

    public double covMatrix(int i, int j) {
        if (i >= dim || j >= dim) return 0;
        if (!state.hasCovariance()) return 0;
        if (state.parameter(i).isFixed() || state.parameter(i).isConst()) return 0;
        if (state.parameter(j).isFixed() || state.parameter(j).isConst()) return 0;
        return state.covariance().get(state.intOfExt(i), state.intOfExt(j));
    }

    /** The covariance, dim x dim row by row, zeros for fixed parameters; false when there is none. */
    public boolean getCovMatrix(double[] cov) {
        if (!state.hasCovariance()) return false;
        for (int i = 0; i < dim; ++i) {
            if (state.parameter(i).isFixed() || state.parameter(i).isConst()) {
                for (int j = 0; j < dim; ++j) cov[i * dim + j] = 0;
            } else {
                final int l = state.intOfExt(i);
                for (int j = 0; j < dim; ++j) {
                    final int k = i * dim + j;
                    if (state.parameter(j).isFixed() || state.parameter(j).isConst()) cov[k] = 0;
                    else cov[k] = state.covariance().get(l, state.intOfExt(j));
                }
            }
        }
        return true;
    }

    public boolean getHessianMatrix(double[] hess) {
        if (!state.hasCovariance()) return false;
        final MnUserCovariance h = state.hessian();
        for (int i = 0; i < dim; ++i) {
            if (state.parameter(i).isFixed() || state.parameter(i).isConst()) {
                for (int j = 0; j < dim; ++j) hess[i * dim + j] = 0;
            } else {
                final int l = state.intOfExt(i);
                for (int j = 0; j < dim; ++j) {
                    final int k = i * dim + j;
                    if (state.parameter(j).isFixed() || state.parameter(j).isConst()) hess[k] = 0;
                    else hess[k] = h.get(l, state.intOfExt(j));
                }
            }
        }
        return true;
    }

    public double correlation(int i, int j) {
        if (i >= dim || j >= dim) return 0;
        if (!state.hasCovariance()) return 0;
        if (state.parameter(i).isFixed() || state.parameter(i).isConst()) return 0;
        if (state.parameter(j).isFixed() || state.parameter(j).isConst()) return 0;
        final int k = state.intOfExt(i);
        final int l = state.intOfExt(j);
        final double cij = state.intCovariance().get(k, l);
        final double tmp = Math.sqrt(Math.abs(state.intCovariance().get(k, k) * state.intCovariance().get(l, l)));
        if (tmp > 0) return cij / tmp;
        return 0;
    }

    public double[] globalCC() {
        final MnGlobalCorrelationCoeff globalCC = state.globalCC();
        if (!globalCC.isValid()) return new double[0];
        final double[] out = new double[dim];
        for (int i = 0; i < dim; ++i) {
            if (state.parameter(i).isFixed() || state.parameter(i).isConst()) out[i] = 0;
            else out[i] = globalCC.globalCC().get(state.intOfExt(i));
        }
        return out;
    }

    /** 3 accurate, 2 made positive-definite, 1 approximate, 0 not positive-definite, -1 none. */
    public int covMatrixStatus() {
        if (minimum != null) {
            if (minimum.hasAccurateCovar()) return 3;
            else if (minimum.hasMadePosDefCovar()) return 2;
            else if (minimum.hasValidCovariance()) return 1;
            else if (minimum.hasCovariance()) return 0;
            return -1;
        }
        return state.covarianceStatus();
    }

    /* ---- Minos, Hesse, Scan, Contour --------------------------------------- */

    /** The Minos errors of variable i into err[0] (lower) and err[1] (upper); runopt 1: lower only, 2: upper only. */
    public boolean getMinosError(int i, double[] err, int runopt) {
        err[0] = 0;
        err[1] = 0;
        if (state.parameter(i).isConst() || state.parameter(i).isFixed()) return false;
        final MnPrint print = new MnPrint("Minuit2Minimizer::GetMinosError", printLevel());
        if (minimum == null) {
            print.error("Failed - no function minimum existing");
            return false;
        }
        if (!minimum.isValid()) {
            print.error("Failed - invalid function minimum");
            return false;
        }
        minuitFCN.setErrorDef(errorDef());
        if (errorDef() != minimum.up()) minimum.setErrorDef(errorDef());
        int mstatus = runMinosError(i, err, runopt);
        if ((mstatus & 8) != 0) {
            print.info((java.util.function.Consumer<COStream>) os -> {
                os.put("Found a new minimum: run again the Minimization starting from the new point");
                os.put("\nFVAL  = ").put(state.fval());
                for (MinuitParameter par : state.minuitParameters()) os.put('\n').put(par.name()).put("\t  = ").put(par.value());
            });
            releaseVariable(i);
            final boolean ok = minimize();
            if (!ok) return false;
            print.info("Run now again Minos from the new found Minimum");
            mstatus = runMinosError(i, err, runopt);
            mstatus |= 8;
        }
        status += 10 * mstatus;
        minosStatus = mstatus;
        return ((mstatus & 1) == 0) && ((mstatus & 2) == 0);
    }

    public boolean getMinosError(int i, double[] err) {
        return getMinosError(i, err, 0);
    }

    private int runMinosError(int i, double[] err, int runopt) {
        final boolean runLower = runopt != 2;
        final boolean runUpper = runopt != 1;
        final int debugLevel = printLevel();
        final int prevGlobalLevel = MnPrint.setGlobalLevel(debugLevel);
        MinosError me;
        MnCross low = new MnCross();
        MnCross up = new MnCross();
        final String parName = state.name(i);
        try {
            if (precision() > 0) state.setPrecision(precision());
            final MnMinos minos = new MnMinos(minuitFCN, minimum);
            final int maxfcn = maxFunctionCalls();
            final double tol = Cxx.max(tolerance(), 0.01);
            int maxfcnUsed = maxfcn;
            if (maxfcnUsed == 0) {
                final int nvar = state.variableParameters();
                maxfcnUsed = 2 * (nvar + 1) * (200 + 100 * nvar + 5 * nvar * nvar);
            }
            if (runLower) {
                if (debugLevel >= 1) {
                    cout().put("******************************************************************************************************\n")
                        .put("Minuit2Minimizer::GetMinosError - Run MINOS LOWER error for parameter #").put(i).put(" : ")
                        .put(parName).put(" using max-calls ").put(maxfcnUsed).put(", tolerance ").put(tol).endl();
                    flush();
                }
                low = minos.loval(i, maxfcn, tol);
            }
            if (runUpper) {
                if (debugLevel >= 1) {
                    cout().put("******************************************************************************************************\n")
                        .put("Minuit2Minimizer::GetMinosError - Run MINOS UPPER error for parameter #").put(i).put(" : ")
                        .put(parName).put(" using max-calls ").put(maxfcnUsed).put(", tolerance ").put(tol).endl();
                    flush();
                }
                up = minos.upval(i, maxfcn, tol);
            }
            me = new MinosError(i, minimum.userState().value(i), low, up);
        } finally {
            MnPrint.setGlobalLevel(prevGlobalLevel);
        }
        if (debugLevel > 0) {
            final COStream os = cout();
            if (runLower) {
                if (!me.lowerValid()) os.put("Minos:  Invalid lower error for parameter ").put(parName).endl();
                if (me.atLowerLimit()) os.put("Minos:  Parameter : ").put(parName).put("  is at Lower limit; error is ").put(me.lower()).endl();
                if (me.atLowerMaxFcn()) {
                    os.put("Minos:  Maximum number of function calls exceeded when running for lower error for parameter ")
                        .put(parName).endl();
                }
                if (me.lowerNewMin()) os.put("Minos:  New Minimum found while running Minos for lower error for parameter ").put(parName).endl();
                if (debugLevel >= 1 && me.lowerValid()) os.put("Minos: Lower error for parameter ").put(parName).put("  :  ").put(me.lower()).endl();
            }
            if (runUpper) {
                if (!me.upperValid()) os.put("Minos:  Invalid upper error for parameter ").put(parName).endl();
                if (me.atUpperLimit()) os.put("Minos:  Parameter ").put(parName).put(" is at Upper limit; error is ").put(me.upper()).endl();
                if (me.atUpperMaxFcn()) {
                    os.put("Minos:  Maximum number of function calls exceeded when running for upper error for parameter ")
                        .put(parName).endl();
                }
                if (me.upperNewMin()) os.put("Minos:  New Minimum found while running Minos for upper error for parameter ").put(parName).endl();
                if (debugLevel >= 1 && me.upperValid()) os.put("Minos: Upper error for parameter ").put(parName).put("  :  ").put(me.upper()).endl();
            }
            flush();
        }
        final MnPrint print = new MnPrint("RunMinosError", printLevel());
        final boolean lowerInvalid = runLower && !me.lowerValid();
        final boolean upperInvalid = runUpper && !me.upperValid();
        if (lowerInvalid) print.warn("Invalid lower error for parameter", minimum.userState().name(i));
        if (upperInvalid) print.warn("Invalid upper error for parameter", minimum.userState().name(i));
        if (me.atLowerLimit()) print.warn("Lower error for parameter", minimum.userState().name(i), "is at the Lower limit!");
        if (me.atUpperLimit()) print.warn("Upper error for parameter", minimum.userState().name(i), "is at the Upper limit!");
        int mstatus = 0;
        if (lowerInvalid || upperInvalid) {
            if (lowerInvalid) {
                mstatus |= 1;
                if (me.atLowerMaxFcn()) mstatus |= 4;
                if (me.lowerNewMin()) mstatus |= 8;
            }
            if (upperInvalid) {
                mstatus |= 2;
                if (me.atUpperMaxFcn()) mstatus |= 4;
                if (me.upperNewMin()) mstatus |= 8;
            }
        }
        if (me.atUpperLimit() || me.atLowerLimit()) mstatus |= 16;
        if (runLower) err[0] = me.lower();
        if (runUpper) err[1] = me.upper();
        if ((runLower && me.lowerNewMin()) && (runUpper && me.upperNewMin())) {
            state = new MnUserParameterState(low.state().fval() < up.state().fval() ? low.state() : up.state());
        } else if (runLower && me.lowerNewMin()) {
            state = new MnUserParameterState(low.state());
        } else if (runUpper && me.upperNewMin()) {
            state = new MnUserParameterState(up.state());
        }
        return mstatus;
    }

    /** The function along variable ipar: nstep points (x, y), xmin = xmax = 0 meaning +- 2 errors. */
    public boolean scan(int ipar, int nstep, double[] x, double[] y, double xmin, double xmax) {
        final MnPrint print = new MnPrint("Minuit2Minimizer::Scan", printLevel());
        if (minuitFCN == null) {
            print.error("Function must be set before using Scan");
            return false;
        }
        if (ipar > state.minuitParameters().size()) {
            print.error("Invalid number; minimizer variables must be set before using Scan");
            return false;
        }
        final int prevGlobalLevel = MnPrint.setGlobalLevel(printLevel());
        final MnParameterScan scan;
        final double amin;
        final List<MnPrint.Point> result;
        try {
            if (precision() > 0) state.setPrecision(precision());
            scan = new MnParameterScan(minuitFCN, state.parameters());
            amin = scan.fval();
            result = new ArrayList<>(scan.scan(ipar, nstep - 1, xmin, xmax));
        } finally {
            MnPrint.setGlobalLevel(prevGlobalLevel);
        }
        if (result.size() != nstep) {
            print.error("Invalid result from MnParameterScan");
            return false;
        }
        result.sort((a, b) -> a.x() != b.x() ? Double.compare(a.x(), b.x()) : Double.compare(a.y(), b.y()));
        for (int i = 0; i < nstep; ++i) {
            x[i] = result.get(i).x();
            y[i] = result.get(i).y();
        }
        if (scan.fval() < amin) {
            print.info("A new minimum has been found");
            state.setValue(ipar, scan.parameters().value(ipar));
        }
        return true;
    }

    /** The contour of ipar and jpar at errorDef: npoints points into x and y. */
    public boolean contour(int ipar, int jpar, int npoints, double[] x, double[] y) {
        final MnPrint print = new MnPrint("Minuit2Minimizer::Contour", printLevel());
        if (minimum == null) {
            print.error("No function minimum existing; must minimize function before");
            return false;
        }
        if (!minimum.isValid()) {
            print.error("Invalid function minimum");
            return false;
        }
        minuitFCN.setErrorDef(errorDef());
        if (errorDef() != minimum.up()) minimum.setErrorDef(errorDef());
        print.info("Computing contours at level -", errorDef());
        final int prevGlobalLevel = MnPrint.setGlobalLevel(printLevel() - 1);
        if (precision() > 0) state.setPrecision(precision());
        final MnContours contour = new MnContours(minuitFCN, minimum, strategy());
        MnPrint.setGlobalLevel(prevGlobalLevel);
        final List<MnPrint.Point> result = contour.points(ipar, jpar, npoints);
        if (result.size() != npoints) {
            print.error("Invalid result from MnContours");
            return false;
        }
        for (int i = 0; i < npoints; ++i) {
            x[i] = result.get(i).x();
            y[i] = result.get(i).y();
        }
        print.info((java.util.function.Consumer<COStream>) os -> {
            os.put(" Computed ").put(npoints).put(" points at level ").put(errorDef());
            for (int i = 0; i < npoints; i++) {
                if (i % 5 == 0) os.endl();
                os.put("( ").put(x[i]).put(", ").put(y[i]).put(") ");
            }
            os.endl().endl();
        });
        return true;
    }

    /** Hesse at the minimum (or at the current variables); true when the covariance is valid. */
    public boolean hesse() {
        final MnPrint print = new MnPrint("Minuit2Minimizer::Hesse", printLevel());
        if (minuitFCN == null) {
            print.error("FCN function has not been set");
            return false;
        }
        final int maxfcn = maxFunctionCalls();
        print.info("Using max-calls", maxfcn);
        final int prevGlobalLevel = MnPrint.setGlobalLevel(printLevel());
        try {
            if (precision() > 0) state.setPrecision(precision());
            final MnHesse hesse = new MnHesse(customizedStrategy(strategy()));
            if (minimum != null) {
                hesse.apply(minuitFCN, minimum, maxfcn);
                state = new MnUserParameterState(minimum.userState());
            } else {
                state = hesse.apply(minuitFCN, state, maxfcn);
            }
        } finally {
            MnPrint.setGlobalLevel(prevGlobalLevel);
        }
        if (printLevel() >= 3) {
            cout().put("Minuit2Minimizer::Hesse  - State returned from Hesse ").endl();
            state.print(cout());
            cout().endl();
            flush();
        }
        final int covStatus = state.covarianceStatus();
        String covStatusType = "not valid";
        if (covStatus == 1) covStatusType = "approximate";
        if (covStatus == 2) covStatusType = "full but made positive defined";
        if (covStatus == 3) covStatusType = "accurate";
        if (covStatus == 0) covStatusType = "full but not positive defined";
        if (!state.hasCovariance()) {
            int hstatus = 4;
            if (minimum != null) {
                if (minimum.error().hesseFailed()) hstatus = 1;
                if (minimum.error().invertFailed()) hstatus = 2;
                else if (!minimum.error().isPosDef()) hstatus = 3;
            }
            print.warn("Hesse failed - matrix is", covStatusType);
            print.warn(hstatus);
            status += 100 * hstatus;
            return false;
        }
        print.info("Hesse is valid - matrix is", covStatusType);
        return true;
    }

    public void setTraceObject(MnTraceObject obj) {
        if (minimizer != null) minimizer.builder().setTraceObject(obj);
    }

    public void setStorageLevel(int level) {
        if (minimizer != null) minimizer.builder().setStorageLevel(level);
    }

    /* ---- standard output -------------------------------------------------- */

    private static volatile java.util.function.Consumer<String> stdout = s -> {
        System.out.print(s);
        System.out.flush();
    };

    private final COStream out = new COStream();

    /** Where the minimizer's printouts go (std::cout in the C++). */
    public static void setStdout(java.util.function.Consumer<String> sink) {
        stdout = sink;
    }

    private COStream cout() {
        return out;
    }

    private void flush() {
        final String s = out.str();
        out.buffer().setLength(0);
        if (!s.isEmpty()) stdout.accept(s);
    }
}
