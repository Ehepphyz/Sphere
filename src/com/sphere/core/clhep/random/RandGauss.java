package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's RandGauss (Random/RandGauss.h, .icc, RandGauss.cc): normal numbers by the polar Box-Muller
 * method, two from each pair of flat numbers, the second kept for the next call (per thread for the static
 * shoot()s, per distribution for fire()).
 */
public class RandGauss extends HepRandom {

    protected double defaultMean;
    protected double defaultStdDev;
    protected HepRandomEngine localEngine;
    private boolean set;
    private double nextGauss;

    public RandGauss(HepRandomEngine anEngine) {
        this(anEngine, 0.0, 1.0);
    }

    public RandGauss(HepRandomEngine anEngine, double mean) {
        this(anEngine, mean, 1.0);
    }

    public RandGauss(HepRandomEngine anEngine, double mean, double stdDev) {
        defaultMean = mean;
        defaultStdDev = stdDev;
        localEngine = anEngine;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandGauss(RandGauss d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandGauss assign(RandGauss d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandGauss d) {
        defaultMean = d.defaultMean;
        defaultStdDev = d.defaultStdDev;
        localEngine = d.localEngine;
        set = d.set;
        nextGauss = d.nextGauss;
    }

    /* ---- static ---- */

    public static double shoot() {
        if (getFlag()) {
            setFlag(false);
            return getVal();
        }
        return shootPair(HepRandom.getTheEngine());
    }

    public static double shoot(double mean, double stdDev) {
        return shoot() * stdDev + mean;
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 0.0, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean) {
        shootArray(size, vect, mean, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; i++) vect[i] = shoot(mean, stdDev);
    }

    public static double shoot(HepRandomEngine anEngine) {
        if (getFlag()) {
            setFlag(false);
            return getVal();
        }
        return shootPair(anEngine);
    }

    public static double shoot(HepRandomEngine anEngine, double mean, double stdDev) {
        return shoot(anEngine) * stdDev + mean;
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 0.0, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double mean) {
        shootArray(anEngine, size, vect, mean, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, mean, stdDev);
    }

    /** A pair of the polar method: the first kept for this thread's next shoot, the second returned. */
    private static double shootPair(HepRandomEngine anEngine) {
        double r;
        double v1;
        double v2;
        do {
            v1 = 2.0 * anEngine.flat() - 1.0;
            v2 = 2.0 * anEngine.flat() - 1.0;
            r = v1 * v1 + v2 * v2;
        } while (r > 1.0);
        final double fac = Math.sqrt(-2.0 * CMath.log(r) / r);
        final double val = v1 * fac;
        setVal(val);
        setFlag(true);
        return v2 * fac;
    }

    /* ---- this distribution's engine ---- */

    public double fire() {
        return normal() * defaultStdDev + defaultMean;
    }

    public double fire(double mean, double stdDev) {
        return normal() * stdDev + mean;
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultMean, defaultStdDev);
    }

    public void fireArray(int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; i++) vect[i] = fire(mean, stdDev);
    }

    @Override
    public double call() {
        return fire(defaultMean, defaultStdDev);
    }

    public double call(double mean, double stdDev) {
        return fire(mean, stdDev);
    }

    protected double normal() {
        if (set) {
            set = false;
            return nextGauss;
        }
        double r;
        double v1;
        double v2;
        do {
            v1 = 2.0 * localEngine.flat() - 1.0;
            v2 = 2.0 * localEngine.flat() - 1.0;
            r = v1 * v1 + v2 * v2;
        } while (r > 1.0);
        final double fac = Math.sqrt(-2.0 * CMath.log(r) / r);
        nextGauss = v1 * fac;
        set = true;
        return v2 * fac;
    }

    @Override
    public String name() {
        return "RandGauss";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandGauss";
    }

    /** Whether this thread's static shoot has a number kept. */
    public static boolean getFlag() {
        return Statics.get().gaussSet;
    }

    public static void setFlag(boolean val) {
        Statics.get().gaussSet = val;
    }

    /** Whether this distribution has a number kept. */
    public boolean getF() {
        return set;
    }

    public void setF(boolean val) {
        set = val;
    }

    protected static double getVal() {
        return Statics.get().gaussNext;
    }

    protected static void setVal(double nextVal) {
        Statics.get().gaussNext = nextVal;
    }

    /* ---- states ---- */

    public static void saveEngineStatus() {
        saveEngineStatus("Config.conf");
    }

    public static void saveEngineStatus(String filename) {
        getTheEngine().saveStatus(filename);
        if (getFlag()) {
            final COStream os = new COStream();
            os.put("RANDGAUSS CACHED_GAUSSIAN: Uvec ").put(getVal()).put(" ");
            DistIO.words(os, getVal());
            os.put("\n");
            DistIO.append(filename, os.str());
        } else {
            DistIO.append(filename, "RANDGAUSS NO_CACHED_GAUSSIAN: 0 \n");
        }
    }

    public static void restoreEngineStatus() {
        restoreEngineStatus("Config.conf");
    }

    public static void restoreEngineStatus(String filename) {
        getTheEngine().restoreStatus(filename);
        final CIStream infile = DistIO.read(filename);
        if (infile == null) return;
        if (DistIO.seek(infile, "RANDGAUSS")) {
            final String setword = DistIO.word(infile, 39);
            if (setword.equals("CACHED_GAUSSIAN:")) {
                final String w = infile.nextWord();
                if (w.equals("Uvec")) {
                    infile.nextDouble();
                    setVal(DistIO.getWords(infile));
                } else {
                    setVal(DistIO.number(w));
                }
                setFlag(true);
            } else {
                setFlag(false);
                setVal(infile.nextDouble());
            }
        } else {
            setFlag(false);
        }
    }

    @Override
    public COStream put(COStream os) {
        os.put(name()).put("\n");
        final int prec = os.precision();
        os.precision(20);
        os.put("Uvec\n");
        DistIO.put(os, defaultMean);
        DistIO.put(os, defaultStdDev);
        if (set) {
            os.put("nextGauss ");
            DistIO.put(os, nextGauss);
        } else {
            os.put("no_cached_nextGauss \n");
        }
        os.precision(prec);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String c1 = is.nextWord();
        if (c1.equals("Uvec")) {
            defaultMean = DistIO.get(is);
            defaultStdDev = DistIO.get(is);
            final String ng = is.nextWord();
            set = false;
            if (ng.equals("nextGauss")) {
                nextGauss = DistIO.get(is);
                set = true;
            }
            return is;
        }
        // the older form: Mean: m Sigma: s RANDGAUSS (NO_)CACHED_GAUSSIAN: n
        defaultMean = is.nextDouble();
        final String c2 = is.nextWord();
        defaultStdDev = is.nextDouble();
        if (is.fail() || !c1.equals("Mean:") || !c2.equals("Sigma:")) {
            StdStreams.cerr().print("i/o problem while expecting to read state of a " + name() + " distribution\n"
                + "default mean and/or sigma could not be read\n");
            return is;
        }
        final String k1 = is.nextWord();
        final String k2 = is.nextWord();
        nextGauss = is.nextDouble();
        if (is.fail() || !k1.equals("RANDGAUSS")) {
            is.setFail();
            StdStreams.cerr().print("Failure when reading caching state of RandGauss\n");
            return is;
        }
        if (k2.equals("CACHED_GAUSSIAN:")) {
            set = true;
        } else if (k2.equals("NO_CACHED_GAUSSIAN:")) {
            set = false;
        } else {
            is.setFail();
            StdStreams.cerr().print("Unexpected caching state keyword of RandGauss:" + k2
                + "\nistream is left in the badbit state\n");
        }
        return is;
    }

    public static COStream saveDistState(COStream os) {
        final int prec = os.precision();
        os.precision(20);
        os.put(distributionName()).put("\n");
        os.put("Uvec\n");
        if (getFlag()) {
            os.put("nextGauss_st ");
            DistIO.put(os, getVal());
        } else {
            os.put("no_cached_nextGauss_st \n");
        }
        os.precision(prec);
        return os;
    }

    public static CIStream restoreDistState(CIStream is) {
        if (!DistIO.expectStatic(is, distributionName())) return is;
        final String c1 = is.nextWord();
        if (c1.equals("Uvec")) {
            final String ng = is.nextWord();
            setFlag(false);
            if (ng.equals("nextGauss_st")) {
                setVal(DistIO.get(is));
                setFlag(true);
            }
            return is;
        }
        final String c2 = is.nextWord();
        setVal(is.nextDouble());
        if (is.fail() || !c1.equals("RANDGAUSS")) {
            is.setFail();
            StdStreams.cerr().print("Failure when reading caching state of static RandGauss\n");
            return is;
        }
        if (c2.equals("CACHED_GAUSSIAN:")) {
            setFlag(true);
        } else if (c2.equals("NO_CACHED_GAUSSIAN:")) {
            setFlag(false);
        } else {
            is.setFail();
            StdStreams.cerr().print("Unexpected caching state keyword of static RandGauss:" + c2
                + "\nistream is left in the badbit state\n");
        }
        return is;
    }

    public static COStream saveFullState(COStream os) {
        HepRandom.saveFullState(os);
        saveDistState(os);
        return os;
    }

    public static CIStream restoreFullState(CIStream is) {
        HepRandom.restoreFullState(is);
        restoreDistState(is);
        return is;
    }
}
