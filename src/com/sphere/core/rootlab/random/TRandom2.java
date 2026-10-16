package com.sphere.core.rootlab.random;

/**
 * ROOT's TRandom2: L'Ecuyer's maximally equidistributed combined Tausworthe
 * generator (taus88), period about 10^26, translated from TRandom2.cxx. It is
 * the generator stressHistogram draws every number from, which is why its
 * reference file can be compared with histograms filled here: the same seed
 * gives the same numbers, bit for bit.
 */
public final class TRandom2 extends TRandom {

    private long seed1;
    private long seed2;

    public TRandom2() {
        this(1);
    }

    public TRandom2(long seed) {
        super(seed == 0 ? 1 : seed);
        setSeed(seed);
    }

    private static long lcg(long n) {
        return (69069L * n) & 0xFFFFFFFFL;
    }

    private static long tausworthe(long s, int a, int b, long c, int d) {
        return (((s & c) << d) & 0xFFFFFFFFL) ^ ((((s << a) & 0xFFFFFFFFL) ^ s) >>> b);
    }

    @Override
    public void setSeed(long newSeed) {
        if (newSeed != 0) {
            seed = lcg(newSeed & 0xFFFFFFFFL);
            if (seed < 2) {
                seed += 2;
            }
            seed1 = lcg(seed);
            if (seed1 < 8) {
                seed1 += 8;
            }
            seed2 = lcg(seed1);
            if (seed2 < 16) {
                seed2 += 16;
            }
        } else {
            final java.util.UUID u = java.util.UUID.randomUUID();
            seed = u.getMostSignificantBits() & 0xFFFFFFFFL;
            seed1 = (u.getMostSignificantBits() >>> 32) & 0xFFFFFFFFL;
            seed2 = (u.getLeastSignificantBits() & 0xFFFFFFFFL)
                  + ((u.getLeastSignificantBits() >>> 32) & 0xFFFFFFFFL);
            seed2 &= 0xFFFFFFFFL;
            if (seed < 2) {
                seed += 2;
            }
            if (seed1 < 8) {
                seed1 += 8;
            }
            if (seed2 < 16) {
                seed2 += 16;
            }
        }
        // "warm it up" by calling it 6 times
        for (int i = 0; i < 6; ++i) {
            rndm();
        }
    }

    /** operator(): the next 32 bits. */
    @Override
    public long next32() {
        seed = tausworthe(seed, 13, 19, 4294967294L, 12);
        seed1 = tausworthe(seed1, 2, 25, 4294967288L, 4);
        seed2 = tausworthe(seed2, 3, 11, 4294967280L, 17);
        return seed ^ seed1 ^ seed2;
    }

    @Override
    public double rndm() {
        final double kScale = 2.3283064365386963e-10;          // 1 / 2^32
        while (true) {
            final long iy = next32();
            if (iy != 0) {
                return kScale * (double) iy;
            }
        }
    }

    /** fSeed, as GetSeed returns it (the first of the three). */
    @Override
    public long getSeed() {
        return seed;
    }
}
