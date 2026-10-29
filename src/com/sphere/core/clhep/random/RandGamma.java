package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandGamma (Random/RandGamma.h, .icc, RandGamma.cc): the gamma distribution of shape k and rate
 * lambda (-1 when either is not positive): Ahrens and Dieter's GD algorithm for k ≥ 1, GS below.
 */
public class RandGamma extends HepRandom {

    private HepRandomEngine localEngine;
    private double defaultK;
    private double defaultLambda;

    public RandGamma(HepRandomEngine anEngine) {
        this(anEngine, 1.0, 1.0);
    }

    public RandGamma(HepRandomEngine anEngine, double k) {
        this(anEngine, k, 1.0);
    }

    public RandGamma(HepRandomEngine anEngine, double k, double lambda) {
        localEngine = anEngine;
        defaultK = k;
        defaultLambda = lambda;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandGamma(RandGamma d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandGamma assign(RandGamma d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandGamma d) {
        localEngine = d.localEngine;
        defaultK = d.defaultK;
        defaultLambda = d.defaultLambda;
    }

    public static double shoot() {
        return shoot(1.0, 1.0);
    }

    public static double shoot(double k, double lambda) {
        return genGamma(HepRandom.getTheEngine(), k, lambda);
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 1.0, 1.0);
    }

    public static void shootArray(int size, double[] vect, double k) {
        shootArray(size, vect, k, 1.0);
    }

    public static void shootArray(int size, double[] vect, double k, double lambda) {
        for (int i = 0; i < size; i++) vect[i] = shoot(k, lambda);
    }

    public static double shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1.0, 1.0);
    }

    public static double shoot(HepRandomEngine anEngine, double k, double lambda) {
        return genGamma(anEngine, k, lambda);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 1.0, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double k) {
        shootArray(anEngine, size, vect, k, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double k, double lambda) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, k, lambda);
    }

    public double fire() {
        return fire(defaultK, defaultLambda);
    }

    public double fire(double k, double lambda) {
        return genGamma(localEngine, k, lambda);
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultK, defaultLambda);
    }

    public void fireArray(int size, double[] vect, double k, double lambda) {
        for (int i = 0; i < size; i++) vect[i] = fire(k, lambda);
    }

    @Override
    public double call() {
        return fire(defaultK, defaultLambda);
    }

    public double call(double k, double lambda) {
        return fire(k, lambda);
    }

    private static final double q1 = 0.0416666664, q2 = 0.0208333723, q3 = 0.0079849875,
        q4 = 0.0015746717, q5 = -0.0003349403, q6 = 0.0003340332,
        q7 = 0.0006053049, q8 = -0.0004701849, q9 = 0.0001710320,
        a1 = 0.333333333, a2 = -0.249999949, a3 = 0.199999867,
        a4 = -0.166677482, a5 = 0.142873973, a6 = -0.124385581,
        a7 = 0.110368310, a8 = -0.112750886, a9 = 0.104089866,
        e1 = 1.000000000, e2 = 0.499999994, e3 = 0.166666848,
        e4 = 0.041664508, e5 = 0.008345522, e6 = 0.001353826,
        e7 = 0.000247453;

    private static double genGamma(HepRandomEngine anEngine, double a, double lambda) {
        double aa = -1.0;
        double aaa = -1.0;
        double b = 0.;
        double c = 0.;
        double d = 0.;
        double e;
        double r;
        double s = 0.;
        double si = 0.;
        double ss = 0.;
        double q0 = 0.;
        double gds;
        double p;
        double q;
        double t;
        double signU;
        double u;
        double v;
        double w;
        double x;
        double v1;
        double v2;
        double v12;

        if (a <= 0.0) return (-1.0);
        if (lambda <= 0.0) return (-1.0);

        if (a < 1.0) {
            // CASE A: Acceptance rejection algorithm gs
            b = 1.0 + 0.36788794412 * a;
            for (;;) {
                p = b * anEngine.flat();
                if (p <= 1.0) {
                    gds = CMath.exp(CMath.log(p) / a);
                    if (CMath.log(anEngine.flat()) <= -gds) return (gds / lambda);
                } else {
                    gds = -CMath.log((b - p) / a);
                    if (CMath.log(anEngine.flat()) <= ((a - 1.0) * CMath.log(gds))) return (gds / lambda);
                }
            }
        } else {
            // CASE B: Acceptance complement algorithm gd
            if (a != aa) {
                aa = a;
                ss = a - 0.5;
                s = Math.sqrt(ss);
                d = 5.656854249 - 12.0 * s;
            }
            do {
                v1 = 2.0 * anEngine.flat() - 1.0;
                v2 = 2.0 * anEngine.flat() - 1.0;
                v12 = v1 * v1 + v2 * v2;
            } while (v12 > 1.0);
            t = v1 * Math.sqrt(-2.0 * CMath.log(v12) / v12);
            x = s + 0.5 * t;
            gds = x * x;
            if (t >= 0.0) return (gds / lambda);

            u = anEngine.flat();
            if (d * u <= t * t * t) return (gds / lambda);

            if (a != aaa) {
                aaa = a;
                r = 1.0 / a;
                q0 = ((((((((q9 * r + q8) * r + q7) * r + q6) * r + q5) * r + q4)
                    * r + q3) * r + q2) * r + q1) * r;
                if (a > 3.686) {
                    if (a > 13.022) {
                        b = 1.77;
                        si = 0.75;
                        c = 0.1515 / s;
                    } else {
                        b = 1.654 + 0.0076 * ss;
                        si = 1.68 / s + 0.275;
                        c = 0.062 / s + 0.024;
                    }
                } else {
                    b = 0.463 + s - 0.178 * ss;
                    si = 1.235;
                    c = 0.195 / s - 0.079 + 0.016 * s;
                }
            }
            if (x > 0.0) {
                v = t / (s + s);
                if (Math.abs(v) > 0.25) {
                    q = q0 - s * t + 0.25 * t * t + (ss + ss) * CMath.log(1.0 + v);
                } else {
                    q = q0 + 0.5 * t * t * ((((((((a9 * v + a8) * v + a7) * v + a6)
                        * v + a5) * v + a4) * v + a3) * v + a2) * v + a1) * v;
                }
                if (CMath.log(1.0 - u) <= q) return (gds / lambda);
            }

            for (;;) {
                do {
                    e = -CMath.log(anEngine.flat());
                    u = anEngine.flat();
                    u = u + u - 1.0;
                    signU = (u > 0) ? 1.0 : -1.0;
                    t = b + (e * si) * signU;
                } while (t <= -0.71874483771719);
                v = t / (s + s);
                if (Math.abs(v) > 0.25) {
                    q = q0 - s * t + 0.25 * t * t + (ss + ss) * CMath.log(1.0 + v);
                } else {
                    q = q0 + 0.5 * t * t * ((((((((a9 * v + a8) * v + a7) * v + a6)
                        * v + a5) * v + a4) * v + a3) * v + a2) * v + a1) * v;
                }
                if (q <= 0.0) continue;
                if (q > 0.5) {
                    w = CMath.exp(q) - 1.0;
                } else {
                    w = ((((((e7 * q + e6) * q + e5) * q + e4) * q + e3) * q + e2)
                        * q + e1) * q;
                }
                if (c * u * signU <= w * CMath.exp(e - 0.5 * t * t)) {
                    x = s + 0.5 * t;
                    return (x * x / lambda);
                }
            }
        }
    }

    @Override
    public String name() {
        return "RandGamma";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandGamma";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, defaultK);
        DistIO.put(os, defaultLambda);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            defaultK = DistIO.get(is);
            defaultLambda = DistIO.get(is);
            return is;
        }
        defaultK = DistIO.number(w);
        defaultLambda = is.nextDouble();
        return is;
    }
}
