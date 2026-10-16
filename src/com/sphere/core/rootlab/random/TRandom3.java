package com.sphere.core.rootlab.random;

/**
 * ROOT's TRandom3: the Mersenne Twister MT19937 of Matsumoto and Nishimura,
 * as TRandom3.cxx writes it. gRandom is a TRandom3 seeded with 4357, so a
 * program that draws from gRandom (hsimple, stress) draws these numbers.
 */
public final class TRandom3 extends TRandom {

    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;
    private static final int TEMPERING_B = 0x9d2c5680;
    private static final int TEMPERING_C = 0xefc60000;

    private final int[] mt = new int[N];
    private int count624;

    public TRandom3() {
        this(4357);
    }

    public TRandom3(long seed) {
        super(seed == 0 ? 4357 : seed);
        setSeed(seed);
    }

    @Override
    public void setSeed(long newSeed) {
        super.setSeed(newSeed);
        count624 = N;
        if (newSeed > 0) {
            mt[0] = (int) seed;
            for (int i = 1; i < N; i++) {
                mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            }
        } else {
            TRandom2 r = new TRandom2(0);
            for (int i = 0; i < N; i++) {
                mt[i] = (int) (long) (4294967296.0 * r.rndm());
            }
            for (int i = 0; i < 10; ++i) {
                rndm();
            }
        }
    }

    @Override
    public long next32() {
        int y;
        if (count624 >= N) {
            int i;
            for (i = 0; i < N - M; i++) {
                y = (mt[i] & UPPER_MASK) | (mt[i + 1] & LOWER_MASK);
                mt[i] = mt[i + M] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? MATRIX_A : 0);
            }
            for (; i < N - 1; i++) {
                y = (mt[i] & UPPER_MASK) | (mt[i + 1] & LOWER_MASK);
                mt[i] = mt[i + M - N] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? MATRIX_A : 0);
            }
            y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? MATRIX_A : 0);
            count624 = 0;
        }
        y = mt[count624++];
        y ^= (y >>> 11);
        y ^= ((y << 7) & TEMPERING_B);
        y ^= ((y << 15) & TEMPERING_C);
        y ^= (y >>> 18);
        return y & 0xFFFFFFFFL;
    }

    @Override
    public double rndm() {
        while (true) {
            final long y = next32();
            if (y != 0) {
                return (double) y * 2.3283064365386963e-10;
            }
        }
    }
}
