package com.sphere.core.clhep.random;

/**
 * The statics CLHEP keeps per thread for its distributions (CLHEP_THREAD_LOCAL): the bits RandFlat's
 * static shootBit has left, RandGauss's static spare number, RandPoisson's static cache, the Ziggurat
 * tables. Made at the first use on a thread, as the C++ thread-locals are; forgotten by
 * {@link HepRandom#resetThread()}.
 */
final class Statics {

    private static final ThreadLocal<Statics> CURRENT = ThreadLocal.withInitial(Statics::new);

    static Statics get() {
        return CURRENT.get();
    }

    static void reset() {
        CURRENT.remove();
    }

    // RandFlat::staticRandomInt, staticFirstUnusedBit
    long flatRandomInt;
    long flatFirstUnusedBit;

    // RandGauss::set_st, nextGauss_st
    boolean gaussSet;
    double gaussNext;

    // RandPoisson::status_st[3] (sq, alxm, g1), then oldm_st
    final double[] poisson = {0., 0., 0., -1.0};

    // RandGaussZiggurat's and RandExpZiggurat's tables, made at their first use: their ziggurat_init
    // compute the same numbers, kept once here
    ZigguratTables ziggurat;
}
