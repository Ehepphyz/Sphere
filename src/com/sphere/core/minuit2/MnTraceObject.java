package com.sphere.core.minuit2;

/**
 * Called at each iteration of a minimization with its state: by default it
 * logs the iteration (at debug level) and the parameters; a subclass may
 * draw, record or stop on it.
 */
public class MnTraceObject {

    private MnUserParameterState userState;
    private int parNumber;

    public MnTraceObject() {
        this(-1);
    }

    public MnTraceObject(int parNumber) {
        this.parNumber = parNumber;
    }

    public void init(MnUserParameterState state) {
        userState = state;
    }

    public void trace(int iter, MinimumState state) {
        final MnPrint print = new MnPrint("MnTraceObject");
        print.debug(new MnPrint.Oneline(state, iter));
        if (userState == null) return;
        print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
            os.put("\n\t").setw(12).put("  ").put("  ").setw(12).put(" ext value ").put("  ").setw(12).put(" int value ")
                .put("  ").setw(12).put(" gradient  ");
            int firstPar = 0;
            int lastPar = state.vec().size();
            if (parNumber >= 0 && parNumber < lastPar) {
                firstPar = parNumber;
                lastPar = parNumber + 1;
            }
            for (int ipar = firstPar; ipar < lastPar; ++ipar) {
                final int epar = userState.trafo().extOfInt(ipar);
                final double eval = userState.trafo().int2ext(ipar, state.vec().get(ipar));
                os.put("\n\t").setw(12).put(userState.name(epar)).put("  ").setw(12).put(eval).put("  ").setw(12)
                    .put(state.vec().get(ipar)).put("  ").setw(12)
                    .put(ipar < state.gradient().vec().size() ? state.gradient().vec().get(ipar) : 0.);
            }
        });
    }

    public MnUserParameterState userState() {
        return userState;
    }

    public void setParNumber(int number) {
        parNumber = number;
    }

    public int parNumber() {
        return parNumber;
    }
}
