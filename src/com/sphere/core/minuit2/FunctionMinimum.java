package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of a minimization: the seed, every state on the way (or the
 * last ones, at storage level 0), the error definition, why it may not be
 * valid; the user's view of the last state is made when first asked.
 */
public final class FunctionMinimum implements MnPrint.Printable {

    public enum Status { MnValid, MnReachedCallLimit, MnAboveMaxEdm }

    private final MinimumSeed seed;
    private final List<MinimumState> states;
    private double errorDef;
    private boolean aboveMaxEdm;
    private boolean reachedCallLimit;
    private MnUserParameterState userState = new MnUserParameterState();

    /** The seed alone, as the one state (its EDM being, as in the C++, the seed's function value). */
    public FunctionMinimum(MinimumSeed seed, double up) {
        this(seed, List.of(new MinimumState(seed.parameters(), seed.error(), seed.gradient(), seed.parameters().fval(),
            seed.nfcn())), up, Status.MnValid);
    }

    public FunctionMinimum(MinimumSeed seed, List<MinimumState> states, double up) {
        this(seed, states, up, Status.MnValid);
    }

    public FunctionMinimum(MinimumSeed seed, List<MinimumState> states, double up, Status status) {
        this.seed = seed;
        this.states = new ArrayList<>(states);
        this.errorDef = up;
        this.aboveMaxEdm = status == Status.MnAboveMaxEdm;
        this.reachedCallLimit = status == Status.MnReachedCallLimit;
    }

    /** Appends a state (Hesse's, typically) and remakes the user's view. */
    public void add(MinimumState state, Status status) {
        states.add(state);
        userState = new MnUserParameterState(state(), up(), seed().trafo());
        aboveMaxEdm = status == Status.MnAboveMaxEdm;
        reachedCallLimit = status == Status.MnReachedCallLimit;
    }

    public void add(MinimumState state) {
        add(state, Status.MnValid);
    }

    public MinimumSeed seed() {
        return seed;
    }

    public List<MinimumState> states() {
        return Collections.unmodifiableList(states);
    }

    /** The user's view of the last state (shared: copy it before changing it). */
    public MnUserParameterState userState() {
        if (!userState.isValid()) userState = new MnUserParameterState(state(), up(), seed().trafo());
        return userState;
    }

    public MnUserParameters userParameters() {
        return userState().parameters();
    }

    public MnUserCovariance userCovariance() {
        return userState().covariance();
    }

    public MinimumState state() {
        return states.get(states.size() - 1);
    }

    public MinimumParameters parameters() {
        return state().parameters();
    }

    public MinimumError error() {
        return state().error();
    }

    public FunctionGradient grad() {
        return state().gradient();
    }

    public double fval() {
        return state().fval();
    }

    public double edm() {
        return state().edm();
    }

    public int nfcn() {
        return state().nfcn();
    }

    public double up() {
        return errorDef;
    }

    public boolean isValid() {
        return state().isValid() && !isAboveMaxEdm() && !hasReachedCallLimit();
    }

    public boolean hasValidParameters() {
        return state().parameters().isValid();
    }

    public boolean hasValidCovariance() {
        return state().error().isValid();
    }

    public boolean hasAccurateCovar() {
        return state().error().isAccurate();
    }

    public boolean hasPosDefCovar() {
        return state().error().isPosDef();
    }

    public boolean hasMadePosDefCovar() {
        return state().error().isMadePosDef();
    }

    public boolean hesseFailed() {
        return state().error().hesseFailed();
    }

    public boolean hasCovariance() {
        return state().error().isAvailable();
    }

    public boolean isAboveMaxEdm() {
        return aboveMaxEdm || Double.isNaN(edm());
    }

    public boolean hasReachedCallLimit() {
        return reachedCallLimit;
    }

    public void setErrorDef(double up) {
        errorDef = up;
        userState = new MnUserParameterState(state(), up, seed().trafo());
    }

    /** operator&lt;&lt;. */
    @Override
    public void print(COStream os) {
        final int pr = os.precision();
        os.precision(MnMatrix.PRECISION);
        os.put("\n  Valid         : ").put(isValid() ? "yes" : "NO").put("\n  Function calls: ").put(nfcn())
            .put("\n  Minimum value : ").put(fval()).put("\n  Edm           : ").put(edm())
            .put("\n  Internal parameters: ");
        parameters().vec().print(os);
        if (hasValidCovariance()) {
            os.put("\n  Internal covariance matrix: ");
            error().matrix().print(os);
        }
        os.put("\n  External parameters: ");
        userParameters().print(os);
        if (!isValid()) {
            os.put("\n  FunctionMinimum is invalid:");
            if (!state().isValid()) os.put("\n    State is invalid");
            if (isAboveMaxEdm()) os.put("\n    Edm is above max");
            if (hasReachedCallLimit()) os.put("\n    Reached call limit");
        }
        os.precision(pr);
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}
