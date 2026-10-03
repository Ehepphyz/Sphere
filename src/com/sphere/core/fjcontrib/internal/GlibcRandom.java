package com.sphere.core.fjcontrib.internal;

/**
 * The C library's rand() as glibc implements it (the TYPE_3 additive
 * feedback generator, r[i] = r[i-3] + r[i-31], seeded through the
 * 16807 congruential sequence), with its global state: a contrib that calls
 * rand() without srand() draws, here as in C, the sequence of seed 1. The
 * randomised N-subjettiness minimisation (MultiPass axes) and the random
 * pieces of other contribs thus give the numbers the C++ gives on Linux.
 */
public final class GlibcRandom {

    public static final int RAND_MAX = 2147483647;

    private static final GlibcRandom SHARED = new GlibcRandom(1);

    private int[] state = new int[31];
    private int front;
    private int rear;

    public GlibcRandom(int seed) {
        seed(seed);
    }

    /** srand(seed) on this generator. */
    public synchronized void seed(int seed) {
        if (seed == 0) seed = 1;
        final int[] s = new int[31];
        s[0] = seed;
        for (int i = 1; i < 31; i++) {
            // 16807 * s[i-1] % 2147483647 with Schrage's method, as glibc's srandom_r
            final long hi = s[i - 1] / 127773;
            final long lo = s[i - 1] % 127773;
            long word = 16807 * lo - 2836 * hi;
            if (word < 0) word += 2147483647;
            s[i] = (int) word;
        }
        state = s;
        front = 3;
        rear = 0;
        for (int i = 0; i < 310; i++) next();
    }

    /** rand(): an integer in [0, RAND_MAX]. */
    public synchronized int next() {
        state[front] += state[rear];
        final int result = (state[front] >>> 1) & 0x7fffffff;
        if (++front >= 31) front = 0;
        if (++rear >= 31) rear = 0;
        return result;
    }

    /** The process-wide generator rand() and srand() act on. */
    public static int rand() {
        return SHARED.next();
    }

    public static void srand(int seed) {
        SHARED.seed(seed);
    }
}
