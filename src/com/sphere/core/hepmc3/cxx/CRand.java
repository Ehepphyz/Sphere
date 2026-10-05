package com.sphere.core.hepmc3.cxx;

/**
 * The C library's rand(), for the programs that draw with it. The C library
 * decides the numbers: the Microsoft one (MSVC, MinGW) is a congruential
 * generator, x = 214013 x + 2531011, giving bits 16 to 30, RAND_MAX 32767;
 * glibc's is an additive feedback generator, RAND_MAX 2147483647. Both start
 * from seed 1, so a program that never calls srand() draws the same numbers
 * every run, and the port draws the C++'s on the library chosen.
 *
 * <p>Each thread has its own generator, as each C++ test is a process of its
 * own; {@link #reset} starts it again as at the start of a process.
 */
public final class CRand {

    public enum Library {
        MSVC(32767), GLIBC(2147483647);

        public final int randMax;

        Library(int randMax) {
            this.randMax = randMax;
        }
    }

    private final Library library;
    private int msvc;
    private final int[] r = new int[31];
    private int front;
    private int rear;

    public CRand(Library library) {
        this.library = library;
        srand(1);
    }

    private static final ThreadLocal<CRand> CURRENT = ThreadLocal.withInitial(() -> new CRand(Library.MSVC));

    /** This thread's generator. */
    public static CRand current() {
        return CURRENT.get();
    }

    /** A generator of this library for this thread, at seed 1. */
    public static void reset(Library library) {
        CURRENT.set(new CRand(library));
    }

    public static int rand() {
        return current().next();
    }

    public static int randMax() {
        return current().library.randMax;
    }

    public Library library() {
        return library;
    }

    public void srand(int seed) {
        if (library == Library.MSVC) {
            msvc = seed;
            return;
        }
        if (seed == 0) seed = 1;
        r[0] = seed;
        for (int i = 1; i < 31; i++) {
            // 16807 r[i-1] % 2147483647 with Schrage's method, as srandom_r
            final long hi = r[i - 1] / 127773;
            final long lo = r[i - 1] % 127773;
            long word = 16807 * lo - 2836 * hi;
            if (word < 0) word += 2147483647;
            r[i] = (int) word;
        }
        front = 3;
        rear = 0;
        for (int i = 0; i < 310; i++) next();
    }

    public int next() {
        if (library == Library.MSVC) {
            msvc = msvc * 214013 + 2531011;
            return (msvc >>> 16) & 0x7FFF;
        }
        r[front] += r[rear];
        final int result = (r[front] >>> 1) & 0x7fffffff;
        if (++front >= 31) front = 0;
        if (++rear >= 31) rear = 0;
        return result;
    }
}
