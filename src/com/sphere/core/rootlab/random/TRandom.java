package com.sphere.core.rootlab.random;

/**
 * ROOT's TRandom, translated from math/mathcore/src/TRandom.cxx of the ROOT
 * sources Sphere follows: the generator itself (a linear congruential one,
 * kept for completeness) and the distributions every ROOT generator shares.
 *
 * The distributions are written operation for operation as ROOT writes them,
 * so a subclass that reproduces ROOT's uniform numbers bit for bit (TRandom2,
 * TRandom3) also reproduces Uniform, Integer and Binomial bit for bit; the
 * ones that go through a transcendental function (Gaus, Rannor, Exp) agree
 * with ROOT to the last bit or two, the difference between Java's and the C
 * library's log and sin.
 */
public class TRandom {

    /** fSeed: a 32-bit unsigned value, kept in the low half of a long. */
    protected long seed;

    public TRandom() {
        this(65539);
    }

    public TRandom(long seed) {
        // Not through setSeed: a subclass overrides it, and its fields do not
        // exist yet while this constructor runs. Subclasses seed themselves.
        this.seed = baseSeed(seed);
    }

    /** TRandom::SetSeed: 0 picks a seed from the clock, as ROOT does from a TUUID. */
    public void setSeed(long seed) {
        this.seed = baseSeed(seed);
    }

    private static long baseSeed(long seed) {
        if (seed == 0) {
            final long s = (System.nanoTime() ^ 0x5DEECE66DL) & 0xFFFFFFFFL;
            return s == 0 ? 65539 : s;
        }
        return seed & 0xFFFFFFFFL;
    }

    public long getSeed() {
        return seed & 0xFFFFFFFFL;
    }

    /** A uniform number in ]0, 1[: 0 and 1 are never returned. */
    public double rndm() {
        final double kCons = 4.6566128730774E-10;           // 1 / 2^31
        do {
            seed = (1103515245L * seed + 12345L) & 0x7FFFFFFFL;
        } while (seed == 0);
        return kCons * seed;
    }

    public double uniform(double x1) {
        final double ans = rndm();
        return x1 * ans;
    }

    public double uniform(double x1, double x2) {
        final double ans = rndm();
        return x1 + (x2 - x1) * ans;
    }

    /** TRandom::Integer: a number in [0, imax-1]. */
    public long integer(long imax) {
        return (long) ((imax & 0xFFFFFFFFL) * rndm()) & 0xFFFFFFFFL;
    }

    public double exp(double tau) {
        final double x = rndm();
        return -tau * Math.log(x);
    }

    public int binomial(int ntot, double prob) {
        if (prob < 0 || prob > 1) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < ntot; i++) {
            if (rndm() > prob) {
                continue;
            }
            n++;
        }
        return n;
    }

    public double breitWigner(double mean, double gamma) {
        final double rval = 2 * rndm() - 1;
        final double displ = 0.5 * gamma * Math.tan(rval * (Math.PI / 2));
        return mean + displ;
    }

    /** TRandom::Rannor: two independent standard normal numbers (Box-Muller). */
    public double[] rannor() {
        final double y = rndm();
        final double z = rndm();
        final double x = z * 6.28318530717958623;
        final double r = Math.sqrt(-2 * Math.log(y));
        return new double[] {r * Math.sin(x), r * Math.cos(x)};
    }

    /** The float version, as hsimple calls it: Rannor(Float_t&, Float_t&). */
    public float[] rannorFloat() {
        final double[] d = rannor();
        return new float[] {(float) d[0], (float) d[1]};
    }

    /**
     * TRandom::Gaus: the acceptance-complement ratio method of W. Hörmann and
     * G. Derflinger that ROOT uses since 5.14, constant for constant.
     */
    public double gaus(double mean, double sigma) {
        final double kC1 = 1.448242853;
        final double kC2 = 3.307147487;
        final double kC3 = 1.46754004;
        final double kD1 = 1.036467755;
        final double kD2 = 5.295844968;
        final double kD3 = 3.631288474;
        final double kHm = 0.483941449;
        final double kZm = 0.107981933;
        final double kHp = 4.132731354;
        final double kZp = 18.52161694;
        final double kPhln = 0.4515827053;
        final double kHm1 = 0.516058551;
        final double kHp1 = 3.132731354;
        final double kHzm = 0.375959516;
        final double kHzmp = 0.591923442;
        final double kAs = 0.8853395638;
        final double kBs = 0.2452635696;
        final double kCs = 0.2770276848;
        final double kB = 0.5029324303;
        final double kX0 = 0.4571828819;
        final double kYm = 0.187308492;
        final double kS = 0.7270572718;
        final double kT = 0.03895759111;

        double result;
        double rn;
        double x;
        double y;
        double z;
        outer:
        {
            y = rndm();
            if (y > kHm1) {
                result = kHp * y - kHp1;
                break outer;
            } else if (y < kZm) {
                rn = kZp * y - 1;
                result = rn > 0 ? 1 + rn : -1 + rn;
                break outer;
            } else if (y < kHm) {
                rn = rndm();
                rn = rn - 1 + rn;
                z = rn > 0 ? 2 - rn : -2 - rn;
                if ((kC1 - y) * (kC3 + Math.abs(z)) < kC2) {
                    result = z;
                    break outer;
                } else {
                    x = rn * rn;
                    if ((y + kD1) * (kD3 + x) < kD2) {
                        result = rn;
                        break outer;
                    } else if (kHzmp - y < Math.exp(-(z * z + kPhln) / 2)) {
                        result = z;
                        break outer;
                    } else if (y + kHzm < Math.exp(-(x + kPhln) / 2)) {
                        result = rn;
                        break outer;
                    }
                }
            }
            while (true) {
                x = rndm();
                y = kYm * rndm();
                z = kX0 - kS * x - y;
                if (z > 0) {
                    rn = 2 + y / x;
                } else {
                    x = 1 - x;
                    y = kYm - y;
                    rn = -(2 + y / x);
                }
                if ((y - kAs + x) * (kCs + x) + kBs < 0) {
                    result = rn;
                    break;
                } else if (y < x + kT) {
                    if (rn * rn < 4 * (kB - Math.log(x))) {
                        result = rn;
                        break;
                    }
                }
            }
        }
        return mean + sigma * result;
    }

    /** TRandom::PoissonD for a mean below 25 (the product of uniforms), else the rejection method. */
    public double poissonD(double mean) {
        if (mean <= 0) {
            return 0;
        }
        if (mean < 25) {
            final double expmean = Math.exp(-mean);
            double pir = 1;
            int n = -1;
            while (true) {
                n++;
                pir *= rndm();
                if (pir <= expmean) {
                    break;
                }
            }
            return n;
        } else if (mean < 1E9) {
            final double sq = Math.sqrt(2.0 * mean);
            final double alxm = Math.log(mean);
            final double g = mean * alxm - lnGamma(mean + 1.0);
            double em;
            double t;
            double y;
            do {
                do {
                    y = Math.tan(Math.PI * rndm());
                    em = sq * y + mean;
                } while (em < 0.0);
                em = Math.floor(em);
                t = 0.9 * (1.0 + y * y) * Math.exp(em * alxm - lnGamma(em + 1.0) - g);
            } while (rndm() > t);
            return em;
        }
        return gaus(0, 1) * Math.sqrt(mean) + mean + 0.5;
    }

    /** ln Γ(x) by Lanczos (g = 7, n = 9), accurate to about 1e-15 for x > 0. */
    static double lnGamma(double x) {
        final double[] c = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        }
        final double xx = x - 1;
        double a = c[0];
        final double t = xx + 7.5;
        for (int i = 1; i < 9; i++) {
            a += c[i] / (xx + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (xx + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** The 32 bits the generator produces (operator() in ROOT); TRandom's own are 31. */
    public long next32() {
        rndm();
        return seed;
    }
}
