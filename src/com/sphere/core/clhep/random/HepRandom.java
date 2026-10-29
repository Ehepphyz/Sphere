package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's HepRandom (Random/Random.h, Random.cc): the static generator, a flat distribution on the
 * engine of this thread, which every static shoot() of the distributions draws from. Each thread has
 * its own, made at its first use: a MixMaxRng by default, as CLHEP built for threads has.
 */
public class HepRandom {

    /** The engine and generator of a thread (CLHEP's thread-local defaults). */
    private static final class Defaults {
        final HepRandom theGenerator = new HepRandom(true);
        HepRandomEngine theEngine = new MixMaxRng();
    }

    private static final ThreadLocal<Defaults> DEFAULTS = ThreadLocal.withInitial(Defaults::new);

    private static Defaults theDefaults() {
        return DEFAULTS.get();
    }

    private HepRandom(boolean quiet) {
    }

    public HepRandom() {
    }

    /** setTheSeed(seed) on this thread's engine. */
    public HepRandom(long seed) {
        setTheSeed(seed);
    }

    /** This thread's engine becomes the one given. */
    public HepRandom(HepRandomEngine algorithm) {
        theDefaults().theEngine = algorithm;
    }

    /** A flat number from this thread's engine. */
    public double flat() {
        return theDefaults().theEngine.flat();
    }

    public void flatArray(int size, double[] vect) {
        theDefaults().theEngine.flatArray(size, vect);
    }

    public double flat(HepRandomEngine theNewEngine) {
        return theNewEngine.flat();
    }

    public void flatArray(HepRandomEngine theNewEngine, int size, double[] vect) {
        theNewEngine.flatArray(size, vect);
    }

    /** operator(): fire. */
    public double call() {
        return flat();
    }

    public String name() {
        return "HepRandom";
    }

    public HepRandomEngine engine() {
        StdStreams.cerr().print("HepRandom::engine() called -- there is no assigned engine!\n");
        return theDefaults().theEngine;
    }

    public COStream put(COStream os) {
        return os;
    }

    public CIStream get(CIStream is) {
        return is;
    }

    public static void setTheSeed(long seed) {
        setTheSeed(seed, 3);
    }

    public static void setTheSeed(long seed, int lux) {
        theDefaults().theEngine.setSeed(seed, lux);
    }

    public static long getTheSeed() {
        return theDefaults().theEngine.getSeed();
    }

    public static void setTheSeeds(long[] seeds) {
        setTheSeeds(seeds, -1);
    }

    public static void setTheSeeds(long[] seeds, int aux) {
        theDefaults().theEngine.setSeeds(seeds, aux);
    }

    public static long[] getTheSeeds() {
        return theDefaults().theEngine.getSeeds();
    }

    /** The pair of seeds at a row of CLHEP's seed table (0 to 214) into seeds[0..1]; others leave them. */
    public static void getTheTableSeeds(long[] seeds, int index) {
        if ((index >= 0) && (index < 215)) {
            seeds[0] = SeedTable.SEEDS[index][0];
            seeds[1] = SeedTable.SEEDS[index][1];
        }
    }

    public static HepRandom getTheGenerator() {
        return theDefaults().theGenerator;
    }

    /** This thread's engine becomes the one given (it stays the caller's). */
    public static void setTheEngine(HepRandomEngine theNewEngine) {
        theDefaults().theEngine = theNewEngine;
    }

    public static HepRandomEngine getTheEngine() {
        return theDefaults().theEngine;
    }

    public static void saveEngineStatus() {
        saveEngineStatus("Config.conf");
    }

    public static void saveEngineStatus(String filename) {
        theDefaults().theEngine.saveStatus(filename);
    }

    public static void restoreEngineStatus() {
        restoreEngineStatus("Config.conf");
    }

    public static void restoreEngineStatus(String filename) {
        theDefaults().theEngine.restoreStatus(filename);
    }

    public static COStream saveFullState(COStream os) {
        return getTheEngine().put(os);
    }

    public static CIStream restoreFullState(CIStream is) {
        return getTheEngine().get(is);
    }

    public static COStream saveDistState(COStream os) {
        return os;
    }

    public static CIStream restoreDistState(CIStream is) {
        return is;
    }

    public static COStream saveStaticRandomStates(COStream os) {
        return StaticRandomStates.save(os);
    }

    public static CIStream restoreStaticRandomStates(CIStream is) {
        return StaticRandomStates.restore(is);
    }

    public static void showEngineStatus() {
        theDefaults().theEngine.showStatus();
    }

    public static int createInstance() {
        theDefaults();
        return 1;
    }

    public static String distributionName() {
        return "HepRandomEngine";
    }

    /** Forgets this thread's engine and distribution statics: the next use makes new default ones. */
    public static void resetThread() {
        DEFAULTS.remove();
        Statics.reset();
    }
}
