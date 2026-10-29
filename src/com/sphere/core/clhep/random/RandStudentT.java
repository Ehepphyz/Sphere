package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandStudentT (Random/RandStudentT.h, .icc, RandStudentT.cc): Student's t of a degrees of
 * freedom by Bailey's polar method (DBL_MAX for a static shoot of negative a).
 */
public class RandStudentT extends HepRandom {

    private HepRandomEngine localEngine;
    private double defaultA;

    public RandStudentT(HepRandomEngine anEngine) {
        this(anEngine, 1.0);
    }

    public RandStudentT(HepRandomEngine anEngine, double a) {
        localEngine = anEngine;
        defaultA = a;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandStudentT(RandStudentT d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandStudentT assign(RandStudentT d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandStudentT d) {
        localEngine = d.localEngine;
        defaultA = d.defaultA;
    }

    private static double polar(HepRandomEngine e, double a) {
        double u;
        double v;
        double w;
        do {
            u = 2.0 * e.flat() - 1.0;
            v = 2.0 * e.flat() - 1.0;
        } while ((w = u * u + v * v) > 1.0);
        return (u * Math.sqrt(a * (CMath.exp(-2.0 / a * CMath.log(w)) - 1.0) / w));
    }

    public static double shoot() {
        return shoot(1.0);
    }

    public static double shoot(double a) {
        if (a < 0.0) return Double.MAX_VALUE;
        return polar(HepRandom.getTheEngine(), a);
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
        return polar(anEngine, a);
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
        return polar(localEngine, a);
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

    @Override
    public String name() {
        return "RandStudentT";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandStudentT";
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
