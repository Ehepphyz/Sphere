package com.sphere.core.fastjet;

/**
 * FastJet's random generator, BasicRandom<double>: L'Ecuyer's combined
 * multiplicative generator with the same two seeds (12345, 67890). Ported bit
 * for bit, so that ghosts land where the C++ puts them and areas can be
 * compared number for number.
 */
public final class BasicRandom {

    private final int[] seed = new int[2];

    public BasicRandom() {
        this(12345, 67890);
    }

    public BasicRandom(int s1, int s2) {
        seed[0] = s1;
        seed[1] = s2;
    }

    /** One integer in [1, 2147483562], the C++ __default_random_generator. */
    public synchronized int nextInt() {
        int k = seed[0] / 53668;
        seed[0] = (seed[0] - k * 53668) * 40014 - k * 12211;
        if (seed[0] < 0) seed[0] += 2147483563;
        k = seed[1] / 52774;
        seed[1] = (seed[1] - k * 52774) * 40692 - k * 3791;
        if (seed[1] < 0) seed[1] += 2147483399;
        int iz = seed[0] - seed[1];
        if (iz < 1) iz += 2147483562;
        return iz;
    }

    /** One double in (0, 1). */
    public synchronized double next() {
        return 4.6566128752457969241e-10 * nextInt();
    }

    /** n doubles, and the state they were drawn from. */
    public synchronized double[] next(int n, int[] usedInitSeed) {
        if (usedInitSeed != null) {
            usedInitSeed[0] = seed[0];
            usedInitSeed[1] = seed[1];
        }
        final double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = next();
        }
        return out;
    }

    public synchronized int[] status() {
        return new int[]{seed[0], seed[1]};
    }

    public synchronized void setStatus(int[] s) {
        seed[0] = s[0];
        seed[1] = s[1];
    }
}
