package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandPoisson (Random/RandPoisson.h, .icc, RandPoisson.cc): Poisson numbers of mean m by
 * Numerical Recipes' methods: products of flat numbers below 12, rejection on a Lorentzian above, a
 * normal approximation from meanMax (2e9). The constants of the last mean are kept (per thread for the
 * static shoots, per distribution for fire) and saved with the state.
 *
 * <p>C++'s long is 64 bits, as on Linux: means beyond 2^31 behave as there.
 */
public class RandPoisson extends HepRandom {

    private static final double meanMax_st = 2.0E9;

    protected double meanMax = 2.0E9;
    protected double defaultMean;
    private HepRandomEngine localEngine;
    /** status[0..2] (sq, alxm, g1), then oldm. */
    private final double[] state = {0., 0., 0., -1.0};

    public RandPoisson(HepRandomEngine anEngine) {
        this(anEngine, 1.0);
    }

    public RandPoisson(HepRandomEngine anEngine, double a1) {
        defaultMean = a1;
        localEngine = anEngine;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandPoisson(RandPoisson d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandPoisson assign(RandPoisson d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandPoisson d) {
        meanMax = d.meanMax;
        defaultMean = d.defaultMean;
        localEngine = d.localEngine;
        System.arraycopy(d.state, 0, state, 0, state.length);
    }

    /** The polar method's second number of a pair, the first dropped. */
    private static double normal(HepRandomEngine eptr) {
        double r;
        double v1;
        double v2;
        do {
            v1 = 2.0 * eptr.flat() - 1.0;
            v2 = 2.0 * eptr.flat() - 1.0;
            r = v1 * v1 + v2 * v2;
        } while (r > 1.0);
        final double fac = Math.sqrt(-2.0 * CMath.log(r) / r);
        return v2 * fac;
    }

    /** One number; st the kept constants (sq, alxm, g1, old mean), read and updated. */
    private static long draw(HepRandomEngine anEngine, double xm, double[] st, double meanMax) {
        double em;
        double t;
        double y;
        double sq = st[0];
        double alxm = st[1];
        double g1 = st[2];
        final double om = st[3];
        if (xm == -1) return 0;
        if (xm < 12.0) {
            if (xm != om) {
                st[3] = xm;
                g1 = CMath.exp(-xm);
            }
            em = -1;
            t = 1.0;
            do {
                em += 1.0;
                t *= anEngine.flat();
            } while (t > g1);
        } else if (xm < meanMax) {
            if (xm != om) {
                st[3] = xm;
                sq = Math.sqrt(2.0 * xm);
                alxm = CMath.log(xm);
                g1 = xm * alxm - HepStat.gammln(xm + 1.0);
            }
            do {
                do {
                    y = CMath.tan(SystemOfUnits.pi * anEngine.flat());
                    em = sq * y + xm;
                } while (em < 0.0);
                em = Math.floor(em);
                t = 0.9 * (1.0 + y * y) * CMath.exp(em * alxm - HepStat.gammln(em + 1.0) - g1);
            } while (anEngine.flat() > t);
        } else {
            em = xm + Math.sqrt(xm) * normal(anEngine);
            if ((long) em < 0) em = (long) xm >= 0 ? xm : meanMax_st;
        }
        st[0] = sq;
        st[1] = alxm;
        st[2] = g1;
        return (long) em;
    }

    /* ---- static ---- */

    public static long shoot() {
        return shoot(1.0);
    }

    public static long shoot(double xm) {
        final HepRandomEngine anEngine = HepRandom.getTheEngine();
        return draw(anEngine, xm, Statics.get().poisson, meanMax_st);
    }

    public static void shootArray(int size, long[] vect) {
        shootArray(size, vect, 1.0);
    }

    public static void shootArray(int size, long[] vect, double m1) {
        for (int i = 0; i < size; i++) vect[i] = shoot(m1);
    }

    public static long shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1.0);
    }

    public static long shoot(HepRandomEngine anEngine, double xm) {
        return draw(anEngine, xm, Statics.get().poisson, meanMax_st);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, long[] vect) {
        shootArray(anEngine, size, vect, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, long[] vect, double m1) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, m1);
    }

    /* ---- this distribution's engine ---- */

    public long fire() {
        return fire(defaultMean);
    }

    public long fire(double xm) {
        return draw(localEngine, xm, state, meanMax);
    }

    /**
     * RandPoisson::fire() called through a RandPoisson reference: it is not virtual in C++, so that a RandPoissonQ
     * or a RandPoissonT (whose fire() hides it) then draws with RandPoisson's own algorithm, from its mean and the
     * state RandPoisson keeps. Java's fire() is virtual; this is the C++ call.
     */
    public final long fireAsRandPoisson() {
        return draw(localEngine, defaultMean, state, meanMax);
    }

    public void fireArray(int size, long[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultMean);
    }

    public void fireArray(int size, long[] vect, double m1) {
        for (int i = 0; i < size; i++) vect[i] = fire(m1);
    }

    @Override
    public double call() {
        return (double) fire(defaultMean);
    }

    public double call(double mean) {
        return (double) fire(mean);
    }

    @Override
    public String name() {
        return "RandPoisson";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandPoisson";
    }

    protected HepRandomEngine getLocalEngine() {
        return localEngine;
    }

    protected static double getOldMean() {
        return Statics.get().poisson[3];
    }

    protected static double getMaxMean() {
        return meanMax_st;
    }

    protected static void setOldMean(double val) {
        Statics.get().poisson[3] = val;
    }

    /** This thread's kept constants: sq, alxm, g1 in [0..2] (the array itself, as the C++ pointer). */
    protected static double[] getPStatus() {
        return Statics.get().poisson;
    }

    protected static void setPStatus(double sq, double alxm, double g1) {
        final double[] st = Statics.get().poisson;
        st[0] = sq;
        st[1] = alxm;
        st[2] = g1;
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, meanMax);
        DistIO.put(os, defaultMean);
        DistIO.put(os, state[0]);
        DistIO.put(os, state[1]);
        DistIO.put(os, state[2]);
        DistIO.put(os, state[3]);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            meanMax = DistIO.get(is);
            defaultMean = DistIO.get(is);
            state[0] = DistIO.get(is);
            state[1] = DistIO.get(is);
            state[2] = DistIO.get(is);
            state[3] = DistIO.get(is);
            return is;
        }
        meanMax = DistIO.number(w);
        defaultMean = is.nextDouble();
        state[0] = is.nextDouble();
        state[1] = is.nextDouble();
        state[2] = is.nextDouble();
        return is;
    }
}
