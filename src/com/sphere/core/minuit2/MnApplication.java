package com.sphere.core.minuit2;

import java.util.List;

/**
 * What a user minimizes with: a function, a parameter state that each run
 * starts from and is replaced by the result, a strategy; the parameters may
 * be changed between runs.
 */
public abstract class MnApplication {

    protected final FCNBase fcn;
    protected MnUserParameterState state;
    protected final MnStrategy strategy;
    protected int numCall;

    protected MnApplication(FCNBase fcn, MnUserParameterState state, MnStrategy stra, int nfcn) {
        this.fcn = fcn;
        this.state = new MnUserParameterState(state);
        this.strategy = new MnStrategy(stra);
        this.numCall = nfcn;
    }

    /** Minimizes with the default call limit (200 + 100 n + 5 n^2) and tolerance 0.1 (EDM &lt; 0.002 Up tolerance). */
    public FunctionMinimum minimize() {
        return minimize(0, 0.1);
    }

    public FunctionMinimum minimize(int maxfcn) {
        return minimize(maxfcn, 0.1);
    }

    public FunctionMinimum minimize(int maxfcn, double toler) {
        final MnPrint print = new MnPrint("MnApplication");
        if (!state.isValid()) throw new IllegalStateException("the parameter state is not valid");
        final int npar = state().variableParameters();
        if (maxfcn == 0) maxfcn = 200 + 100 * npar + 5 * npar * npar;
        final FCNBase f = fcnbase();
        if (npar == 0) {
            final double fval = f.value(state.params());
            print.info("Function has zero parameters - returning current function value - ", fval);
            final MinimumParameters mparams = new MinimumParameters(fval, MinimumParameters.Status.MnValid);
            final MinimumState mstate = new MinimumState(mparams, 0., 1);
            return new FunctionMinimum(new MinimumSeed(mstate, state.trafo()), f.up());
        }
        final FunctionMinimum min = minimizer().minimize(f, state, strategy, maxfcn, toler);
        numCall += min.nfcn();
        state = new MnUserParameterState(min.userState());
        if (print.shows(MnPrint.Verbosity.DEBUG)) {
            final List<MinimumState> iterationStates = min.states();
            print.debug("State resulting from Migrad after", iterationStates.size(), "iterations:", state);
            print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                for (int i = 0; i < iterationStates.size(); ++i) {
                    final MinimumState st = iterationStates.get(i);
                    os.put("\n----------> Iteration ").put(i).put('\n');
                    final int pr = os.precision();
                    os.precision(18);
                    os.put("            FVAL = ").put(st.fval()).put(" Edm = ").put(st.edm()).put(" Nfcn = ").put(st.nfcn())
                        .put('\n');
                    os.precision(pr);
                    os.put("            Error matrix change = ").put(st.error().dcovar()).put('\n');
                    os.put("            Internal parameters : ");
                    for (int j = 0; j < st.size(); ++j) os.put(" p").put(j).put(" = ").put(st.vec().get(j));
                }
            });
        }
        return min;
    }

    public abstract ModularFunctionMinimizer minimizer();

    public MnMachinePrecision precision() {
        return state.precision();
    }

    /** The state the next run starts from (to be changed: fix, release, set values...). */
    public MnUserParameterState state() {
        return state;
    }

    public MnUserParameters parameters() {
        return state.parameters();
    }

    public MnUserCovariance covariance() {
        return state.covariance();
    }

    public FCNBase fcnbase() {
        return fcn;
    }

    public MnStrategy strategy() {
        return strategy;
    }

    public int numOfCalls() {
        return numCall;
    }

    /* ---- facade of the state ------------------------------------------------- */

    public void add(String name, double val, double err) {
        state.add(name, val, err);
    }

    public void add(String name, double val, double err, double low, double up) {
        state.add(name, val, err, low, up);
    }

    public void add(String name, double val) {
        state.add(name, val);
    }

    public void fix(int i) {
        state.fix(i);
    }

    public void release(int i) {
        state.release(i);
    }

    public void setValue(int i, double v) {
        state.setValue(i, v);
    }

    public void setError(int i, double e) {
        state.setError(i, e);
    }

    public void setLimits(int i, double low, double up) {
        state.setLimits(i, low, up);
    }

    public void removeLimits(int i) {
        state.removeLimits(i);
    }

    public double value(int i) {
        return state.value(i);
    }

    public double error(int i) {
        return state.error(i);
    }

    public void fix(String name) {
        state.fix(name);
    }

    public void release(String name) {
        state.release(name);
    }

    public void setValue(String name, double v) {
        state.setValue(name, v);
    }

    public void setError(String name, double e) {
        state.setError(name, e);
    }

    public void setLimits(String name, double low, double up) {
        state.setLimits(name, low, up);
    }

    public void removeLimits(String name) {
        state.removeLimits(name);
    }

    public void setPrecision(double prec) {
        state.setPrecision(prec);
    }

    public double value(String name) {
        return state.value(name);
    }

    public double error(String name) {
        return state.error(name);
    }

    public int index(String name) {
        return state.index(name);
    }

    public String name(int i) {
        return state.name(i);
    }

    public double int2ext(int i, double v) {
        return state.int2ext(i, v);
    }

    public double ext2int(int i, double v) {
        return state.ext2int(i, v);
    }

    public int intOfExt(int i) {
        return state.intOfExt(i);
    }

    public int extOfInt(int i) {
        return state.extOfInt(i);
    }

    public int variableParameters() {
        return state.variableParameters();
    }
}
