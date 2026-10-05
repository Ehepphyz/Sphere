package com.sphere.core.minuit2;

/**
 * The user's function as Minuit2 calls it internally: it counts the calls,
 * and through {@link Caller} turns internal parameters into external ones.
 */
public final class MnFcn {

    private final FCNBase fcn;
    private int numCall;
    private final MnUserTransformation transform;

    public MnFcn(FCNBase fcn) {
        this(fcn, 0);
    }

    public MnFcn(FCNBase fcn, int ncall) {
        this.fcn = fcn;
        this.numCall = ncall;
        this.transform = null;
    }

    public MnFcn(FCNBase fcn, MnUserTransformation trafo) {
        this(fcn, trafo, 0);
    }

    public MnFcn(FCNBase fcn, MnUserTransformation trafo, int ncall) {
        this.fcn = fcn;
        this.numCall = ncall;
        this.transform = trafo;
    }

    public int numOfCalls() {
        return numCall;
    }

    public double errorDef() {
        return fcn.up();
    }

    public double up() {
        return fcn.up();
    }

    public FCNBase fcn() {
        return fcn;
    }

    public MnUserTransformation trafo() {
        return transform;
    }

    /** Counts calls made through another MnFcn (the threads of a parallel gradient). */
    synchronized void addCalls(int n) {
        numCall += n;
    }

    double callWithTransformedParams(double[] vpar) {
        numCall++;
        return fcn.value(vpar);
    }

    double callWithoutDoingTrafo(LAVector v) {
        numCall++;
        return fcn.value(v.toArray());
    }

    /**
     * Calls the function at internal parameters (MnFcnCaller): the external
     * values start as the initial ones and only those of the parameters that
     * changed since the last call are transformed again.
     */
    public static final class Caller {
        private final MnFcn mfcn;
        private final boolean doInt2ext;
        private double[] lastInput = new double[0];
        private double[] vpar;

        public Caller(MnFcn mfcn) {
            this.mfcn = mfcn;
            this.doInt2ext = mfcn.trafo() != null;
            if (doInt2ext) vpar = mfcn.trafo().initialParValues();
        }

        public double call(LAVector v) {
            if (!doInt2ext) return mfcn.callWithoutDoingTrafo(v);
            final MnUserTransformation transform = mfcn.trafo();
            final boolean firstCall = lastInput.length != v.size();
            if (firstCall) lastInput = new double[v.size()];
            for (int i = 0; i < v.size(); i++) {
                if (firstCall || lastInput[i] != v.get(i)) {
                    vpar[transform.extOfInt(i)] = transform.int2ext(i, v.get(i));
                    lastInput[i] = v.get(i);
                }
            }
            return mfcn.callWithTransformedParams(vpar);
        }
    }
}
