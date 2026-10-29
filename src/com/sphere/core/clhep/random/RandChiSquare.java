package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandChiSquare (Random/RandChiSquare.h, .icc, RandChiSquare.cc): the chi-square distribution of
 * a degrees of freedom (a ≥ 1; -1 below), by the ratio of uniforms (Kinderman and Monahan). The C++'s
 * thread-local cache of the constants of a is only a cache: they are computed each time here.
 */
public class RandChiSquare extends HepRandom {

    private HepRandomEngine localEngine;
    private double defaultA;

    public RandChiSquare(HepRandomEngine anEngine) {
        this(anEngine, 1);
    }

    public RandChiSquare(HepRandomEngine anEngine, double a) {
        localEngine = anEngine;
        defaultA = a;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandChiSquare(RandChiSquare d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandChiSquare assign(RandChiSquare d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandChiSquare d) {
        localEngine = d.localEngine;
        defaultA = d.defaultA;
    }

    public static double shoot() {
        return shoot(1.0);
    }

    public static double shoot(double a) {
        return genChiSquare(HepRandom.getTheEngine(), a);
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 1.0);
    }

    public static void shootArray(int size, double[] vect, double a) {
        for (int i = 0; i < size; i++) vect[i] = shoot(a);
    }

    public static double shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1.0);
    }

    public static double shoot(HepRandomEngine anEngine, double a) {
        return genChiSquare(anEngine, a);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double a) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, a);
    }

    public double fire() {
        return fire(defaultA);
    }

    public double fire(double a) {
        return genChiSquare(localEngine, a);
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultA);
    }

    public void fireArray(int size, double[] vect, double a) {
        for (int i = 0; i < size; i++) vect[i] = fire(a);
    }

    @Override
    public double call() {
        return fire(defaultA);
    }

    public double call(double a) {
        return fire(a);
    }

    private static double genChiSquare(HepRandomEngine anEngine, double a) {
        double u;
        double v;
        double z;
        double zz;
        double r;
        if (a < 1) return (-1.0);
        if (a == 1) {
            for (;;) {
                u = anEngine.flat();
                v = anEngine.flat() * 0.857763884960707;
                z = v / u;
                if (z < 0) continue;
                zz = z * z;
                r = 2.5 - zz;
                if (z < 0.0) r = r + zz * z / (3.0 * z);
                if (u < r * 0.3894003915) return (z * z);
                if (zz > (1.036961043 / u + 1.4)) continue;
                if (2 * CMath.log(u) < (-zz * 0.5)) return (z * z);
            }
        } else {
            final double b = Math.sqrt(a - 1.0);
            double vm = -0.6065306597 * (1.0 - 0.25 / (b * b + 1.0));
            vm = (-b > vm) ? -b : vm;
            final double vp = 0.6065306597 * (0.7071067812 + b) / (0.5 + b);
            final double vd = vp - vm;
            for (;;) {
                u = anEngine.flat();
                v = anEngine.flat() * vd + vm;
                z = v / u;
                if (z < -b) continue;
                zz = z * z;
                r = 2.5 - zz;
                if (z < 0.0) r = r + zz * z / (3.0 * (z + b));
                if (u < r * 0.3894003915) return ((z + b) * (z + b));
                if (zz > (1.036961043 / u + 1.4)) continue;
                if (2 * CMath.log(u) < (CMath.log(1.0 + z / b) * b * b - zz * 0.5 - z * b)) return ((z + b) * (z + b));
            }
        }
    }

    @Override
    public String name() {
        return "RandChiSquare";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandChiSquare";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, defaultA);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            defaultA = DistIO.get(is);
            return is;
        }
        defaultA = DistIO.number(w);
        return is;
    }
}
