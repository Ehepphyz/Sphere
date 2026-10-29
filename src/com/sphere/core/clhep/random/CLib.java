package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CRand;

import java.util.Locale;

/**
 * The C library state two of CLHEP's engines draw from: drand48 (DRand48Engine) and rand() (RandEngine).
 * As in a C++ process, it is one for the whole program, shared by every engine of those kinds.
 *
 * <p>drand48 is the same everywhere (POSIX's 48-bit generator, CLHEP's drand48.src on Windows); rand()
 * is the C library's own: Microsoft's on Windows (RAND_MAX 32767), glibc's elsewhere, as a CLHEP built
 * on the machine would draw.
 */
public final class CLib {

    private CLib() {
    }

    // drand48: X = a X + c modulo 2^48
    private static final long A = 0x5DEECE66DL;
    private static final long C = 0xB;
    private static long x = 0;   // the C library's state before any seeding
    private static long old;

    public static synchronized void srand48(long seedval) {
        seedval &= 0xffffffffL;
        x = (seedval << 16) | 0x330E;
    }

    /** seed48: the state set from three 16-bit words (least significant first); the old ones returned. */
    public static synchronized int[] seed48(int[] seed16v) {
        old = x;
        x = ((long) (seed16v[2] & 0xffff) << 32) | ((long) (seed16v[1] & 0xffff) << 16) | (seed16v[0] & 0xffff);
        return words(old);
    }

    /** The three 16-bit words of the current state, as seed48 returns them, the state unchanged. */
    public static synchronized int[] state48() {
        return words(x);
    }

    private static int[] words(long s) {
        return new int[] {(int) (s & 0xffff), (int) ((s >>> 16) & 0xffff), (int) ((s >>> 32) & 0xffff)};
    }

    /** drand48: the 48 bits of the next state over 2^48. */
    public static synchronized double drand48() {
        x = (x * A + C) & 0xFFFFFFFFFFFFL;
        return Double.longBitsToDouble(0x3FF0000000000000L | (x << 4)) - 1.0;
    }

    private static CRand rand = new CRand(defaultRandLibrary());

    /**
     * Whose rand() a CLHEP built here draws: Microsoft's on Windows, glibc's elsewhere; the property
     * sphere.clhep.rand (MSVC or GLIBC) chooses another.
     */
    public static CRand.Library defaultRandLibrary() {
        final String chosen = System.getProperty("sphere.clhep.rand", "");
        if (!chosen.isEmpty()) return CRand.Library.valueOf(chosen.toUpperCase(Locale.ROOT));
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            ? CRand.Library.MSVC : CRand.Library.GLIBC;
    }

    /** The C library as a program starts: drand48 unseeded, rand() from seed 1 (the library given). */
    public static synchronized void restart(CRand.Library library) {
        x = 0;
        old = 0;
        rand = new CRand(library);
    }

    /** Chooses whose rand() RandEngine reproduces (it starts again from seed 1). */
    public static synchronized void setRandLibrary(CRand.Library library) {
        rand = new CRand(library);
    }

    public static synchronized CRand.Library randLibrary() {
        return rand.library();
    }

    public static synchronized void srand(int seed) {
        rand.srand(seed);
    }

    public static synchronized int rand() {
        return rand.next();
    }

    public static synchronized int randMax() {
        return rand.library().randMax;
    }
}
