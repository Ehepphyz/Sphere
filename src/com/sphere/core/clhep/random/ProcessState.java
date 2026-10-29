package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CRand;

/**
 * What a C++ program using CLHEP starts with, set again in this JVM: the engines' counters at 0 (the
 * seeds of default-made engines), the C library's drand48 and rand() unseeded, this thread's static
 * engine and distribution states not yet made. The validation starts every reference program here, as
 * the C++ one starts in a fresh process.
 */
public final class ProcessState {

    private ProcessState() {
    }

    /** A fresh program whose rand() is this machine's C library's. */
    public static void restart() {
        restart(CLib.defaultRandLibrary());
    }

    /** A fresh program whose rand() is the given C library's. */
    public static void restart(CRand.Library randLibrary) {
        EngineCounters.reset();
        CLib.restart(randLibrary);
        HepRandom.resetThread();
    }
}
