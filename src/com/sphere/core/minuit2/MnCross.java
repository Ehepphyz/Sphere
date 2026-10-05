package com.sphere.core.minuit2;

/**
 * Where the function crosses the minimum + Up along a direction, as the
 * fraction of the direction (value), with the state there; or why it was
 * not found: a limit, the call limit, a new minimum.
 */
public final class MnCross {

    enum Kind { NONE, PAR_LIMIT, FCN_LIMIT, NEW_MIN }

    private final double value;
    private final MnUserParameterState state;
    private final int nfcn;
    private final boolean valid;
    private final boolean limset;
    private final boolean maxFcn;
    private final boolean newMin;

    /** Invalid, no calls. */
    public MnCross() {
        this(0);
    }

    public MnCross(int nfcn) {
        this(0., new MnUserParameterState(), nfcn, false, false, false, false);
    }

    /** Invalid, at a state. */
    public MnCross(MnUserParameterState state, int nfcn) {
        this(0., state, nfcn, false, false, false, false);
    }

    /** Found. */
    public MnCross(double value, MnUserParameterState state, int nfcn) {
        this(value, state, nfcn, true, false, false, false);
    }

    MnCross(MnUserParameterState state, int nfcn, Kind kind) {
        this(0., state, nfcn, kind == Kind.PAR_LIMIT, kind == Kind.PAR_LIMIT, kind == Kind.FCN_LIMIT, kind == Kind.NEW_MIN);
    }

    private MnCross(double value, MnUserParameterState state, int nfcn, boolean valid, boolean limset, boolean maxFcn,
                    boolean newMin) {
        this.value = value;
        this.state = new MnUserParameterState(state);
        this.nfcn = nfcn;
        this.valid = valid;
        this.limset = limset;
        this.maxFcn = maxFcn;
        this.newMin = newMin;
    }

    public double value() {
        return value;
    }

    public MnUserParameterState state() {
        return state;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean atLimit() {
        return limset;
    }

    public boolean atMaxFcn() {
        return maxFcn;
    }

    public boolean newMinimum() {
        return newMin;
    }

    public int nfcn() {
        return nfcn;
    }
}
