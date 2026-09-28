package com.sphere.core.fastjet.plugins.siscone;

/**
 * siscone::ranlux, the RANLUX generator (luxury level 1, 24-bit outputs)
 * SISCone draws the random 96-bit references of its particles from.
 *
 * As in the C++ it is one generator for the whole program, shared by the
 * planar and spherical SISCone and seeded when either is first used, so that
 * the references, hence the order in which stable cones come out of the
 * hash, are those of FastJet event after event.
 */
public final class Ranlux {

    private static final long MASK_LO = 0x00ffffffL;
    private static final long TWO24 = 16777216L;

    private static int i;
    private static int j;
    private static int n;
    private static int skip;
    private static int carry;
    private static final long[] U = new long[24];

    private Ranlux() {
    }

    private static long incrementState() {
        long delta = U[j] - U[i] - carry;
        if (delta < 0) {
            carry = 1;
            delta &= MASK_LO;
        } else {
            carry = 0;
        }
        U[i] = delta;
        i = (i == 0) ? 23 : i - 1;
        j = (j == 0) ? 23 : j - 1;
        return delta;
    }

    private static void set(long s) {
        if (s == 0) s = 314159265;
        long seed = s;
        for (int k = 0; k < 24; k++) {
            final long q = seed / 53668;
            seed = 40014 * (seed - q * 53668) - q * 12211;
            if (seed < 0) seed += 2147483563L;
            U[k] = seed % TWO24;
        }
        i = 23;
        j = 9;
        n = 0;
        skip = 389 - 24;
        carry = (U[23] & ~MASK_LO) != 0 ? 1 : 0;
    }

    /** ranlux_init: back to the start of the sequence. */
    public static synchronized void init() {
        set(0);
    }

    /** ranlux_get: the next 24-bit number. */
    public static synchronized long get() {
        final int s = skip;
        final long r = incrementState();
        n++;
        if (n == 24) {
            n = 0;
            for (int k = 0; k < s; k++) incrementState();
        }
        return r;
    }
}
