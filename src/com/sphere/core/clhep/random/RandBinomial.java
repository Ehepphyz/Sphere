package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandBinomial (Random/RandBinomial.h, .icc, RandBinomial.cc): binomial numbers of n trials of
 * probability p (-1 when np ≤ 0), by inversion below np = 10 and Kachitvichyanukul and Schmeiser's BTPE
 * above. The C++'s thread-local cache of the constants of (n, p) is only a cache: computed each time here.
 *
 * <p>C++'s long is 64 bits, as on Linux.
 */
public class RandBinomial extends HepRandom {

    private HepRandomEngine localEngine;
    private long defaultN;
    private double defaultP;

    public RandBinomial(HepRandomEngine anEngine) {
        this(anEngine, 1, 0.5);
    }

    public RandBinomial(HepRandomEngine anEngine, long n) {
        this(anEngine, n, 0.5);
    }

    public RandBinomial(HepRandomEngine anEngine, long n, double p) {
        localEngine = anEngine;
        defaultN = n;
        defaultP = p;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandBinomial(RandBinomial d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandBinomial assign(RandBinomial d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandBinomial d) {
        localEngine = d.localEngine;
        defaultN = d.defaultN;
        defaultP = d.defaultP;
    }

    public static double shoot() {
        return shoot(1, 0.5);
    }

    public static double shoot(long n, double p) {
        return genBinomial(HepRandom.getTheEngine(), n, p);
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 1, 0.5);
    }

    public static void shootArray(int size, double[] vect, long n) {
        shootArray(size, vect, n, 0.5);
    }

    public static void shootArray(int size, double[] vect, long n, double p) {
        for (int i = 0; i < size; i++) vect[i] = shoot(n, p);
    }

    public static double shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1, 0.5);
    }

    public static double shoot(HepRandomEngine anEngine, long n, double p) {
        return genBinomial(anEngine, n, p);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 1, 0.5);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, long n) {
        shootArray(anEngine, size, vect, n, 0.5);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, long n, double p) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, n, p);
    }

    public double fire() {
        return fire(defaultN, defaultP);
    }

    public double fire(long n, double p) {
        return genBinomial(localEngine, n, p);
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultN, defaultP);
    }

    public void fireArray(int size, double[] vect, long n, double p) {
        for (int i = 0; i < size; i++) vect[i] = fire(n, p);
    }

    @Override
    public double call() {
        return fire(defaultN, defaultP);
    }

    public double call(long n, double p) {
        return fire(n, p);
    }

    private static final double C1 = 8.33333333333333333e-02;
    private static final double C3 = -2.77777777777777778e-03;
    private static final double C5 = 7.93650793650793651e-04;
    private static final double C7 = -5.95238095238095238e-04;

    private static final double[] c = {0.0,
        8.106146679532726e-02, 4.134069595540929e-02,
        2.767792568499834e-02, 2.079067210376509e-02,
        1.664469118982119e-02, 1.387612882307075e-02,
        1.189670994589177e-02, 1.041126526197209e-02,
        9.255462182712733e-03, 8.330563433362871e-03,
        7.573675487951841e-03, 6.942840107209530e-03,
        6.408994188004207e-03, 5.951370112758848e-03,
        5.554733551962801e-03, 5.207655919609640e-03,
        4.901395948434738e-03, 4.629153749334029e-03,
        4.385560249232324e-03, 4.166319691996922e-03,
        3.967954218640860e-03, 3.787618068444430e-03,
        3.622960224683090e-03, 3.472021382978770e-03,
        3.333155636728090e-03, 3.204970228055040e-03,
        3.086278682608780e-03, 2.976063983550410e-03,
        2.873449362352470e-03, 2.777674929752690e-03};

    /** The correction of Stirling's approximation of ln k!: tabulated to 30, its series above. */
    private static double StirlingCorrection(long k) {
        if (k > 30L) {
            final double r = 1.0 / (double) k;
            final double rr = r * r;
            return (r * (C1 + rr * (C3 + rr * (C5 + rr * C7))));
        } else {
            return (c[(int) k]);
        }
    }

    private static final double C1_3 = 0.33333333333333333;
    private static final double C5_8 = 0.62500000000000000;
    private static final double C1_6 = 0.16666666666666667;
    private static final long DMAX_KM = 20L;

    private static double genBinomial(HepRandomEngine anEngine, long n, double p) {
        long b = 0;
        long m;
        long nm;
        double par;
        double np;
        double p0 = 0;
        double q;
        double pq = 0;
        double rc = 0;
        double ss = 0;
        double xm = 0;
        double xl = 0;
        double xr = 0;
        double ll = 0;
        double lr = 0;
        double cc = 0;
        double p1 = 0;
        double p2 = 0;
        double p3 = 0;
        double p4 = 0;
        double ch;
        long bh;
        long i;
        long K;
        long Km;
        long nK;
        double f;
        double rm;
        double U;
        double V;
        double X;
        double T;
        double E;

        // the constants of (n, p)
        par = (1.0 - p < p) ? 1.0 - p : p;
        q = 1.0 - par;
        np = n * par;
        if (np <= 0.0) return (-1.0);
        rm = np + par;
        m = (long) rm;
        if (np < 10) {
            p0 = CMath.exp(n * CMath.log(q));
            bh = (long) (np + 10.0 * Math.sqrt(np * q));
            b = (bh < n) ? bh : n;
        } else {
            rc = (n + 1.0) * (pq = par / q);
            ss = np * q;
            i = (long) (2.195 * Math.sqrt(ss) - 4.6 * q);
            xm = m + 0.5;
            xl = (double) (m - i);
            xr = (double) (m + i + 1L);
            f = (rm - xl) / (rm - xl * par);
            ll = f * (1.0 + 0.5 * f);
            f = (xr - rm) / (xr * q);
            lr = f * (1.0 + 0.5 * f);
            cc = 0.134 + 20.5 / (15.3 + (double) m);
            p1 = i + 0.5;
            p2 = p1 * (1.0 + cc + cc);
            p3 = p2 + cc / ll;
            p4 = p3 + cc / lr;
        }

        if (np < 10) {
            // inversion
            double pk;
            K = 0;
            pk = p0;
            U = anEngine.flat();
            while (U > pk) {
                ++K;
                if (K > b) {
                    U = anEngine.flat();
                    K = 0;
                    pk = p0;
                } else {
                    U -= pk;
                    pk = ((n - K + 1) * par * pk) / (K * q);
                }
            }
            return ((p > 0.5) ? (double) (n - K) : (double) K);
        }

        for (;;) {
            V = anEngine.flat();
            if ((U = anEngine.flat() * p4) <= p1) {
                K = (long) (xm - U + p1 * V);
                return ((p > 0.5) ? (double) (n - K) : (double) K);
            }
            if (U <= p2) {
                X = xl + (U - p1) / cc;
                if ((V = V * cc + 1.0 - Math.abs(xm - X) / p1) >= 1.0) continue;
                K = (long) X;
            } else if (U <= p3) {
                if ((X = xl + CMath.log(V) / ll) < 0.0) continue;
                K = (long) X;
                V *= (U - p2) * ll;
            } else {
                if ((K = (long) (xr - CMath.log(V) / lr)) > n) continue;
                V *= (U - p3) * lr;
            }

            if ((Km = Math.abs(K - m)) <= DMAX_KM || Km + Km + 2L >= ss) {
                // recursive evaluation of f(K)
                f = 1.0;
                if (m < K) {
                    for (i = m; i < K; ) {
                        if ((f *= (rc / ++i - pq)) < V) break;
                    }
                } else {
                    for (i = K; i < m; ) {
                        if ((V *= (rc / ++i - pq)) > f) break;
                    }
                }
                if (V <= f) break;
            } else {
                // squeeze and final acceptance
                V = CMath.log(V);
                T = -Km * Km / (ss + ss);
                E = (Km / ss) * ((Km * (Km * C1_3 + C5_8) + C1_6) / ss + 0.5);
                if (V <= T - E) break;
                if (V <= T + E) {
                    nm = n - m + 1L;
                    ch = xm * CMath.log((m + 1.0) / (pq * nm))
                        + StirlingCorrection(m + 1L) + StirlingCorrection(nm);
                    nK = n - K + 1L;
                    if (V <= ch + (n + 1.0) * CMath.log((double) nm / (double) nK)
                        + (K + 0.5) * CMath.log(nK * pq / (K + 1.0))
                        - StirlingCorrection(K + 1L) - StirlingCorrection(nK)) break;
                }
            }
        }
        return ((p > 0.5) ? (double) (n - K) : (double) K);
    }

    @Override
    public String name() {
        return "RandBinomial";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandBinomial";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        os.put(defaultN).put(" ").put(defaultP).put(" ");
        DistIO.words(os, defaultP);
        os.put("\n");
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            defaultN = is.nextLong();
            is.nextDouble();
            defaultP = DistIO.getWords(is);
            return is;
        }
        defaultN = DistIO.integer(w);
        defaultP = is.nextDouble();
        return is;
    }
}
