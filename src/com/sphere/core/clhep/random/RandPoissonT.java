package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandPoissonT (Random/RandPoissonT.h, RandPoissonT.cc): RandPoissonQ's quick numbers below its
 * table's boundary (100), RandPoisson's exact ones above.
 */
public class RandPoissonT extends RandPoisson {

    public RandPoissonT(HepRandomEngine anEngine) {
        this(anEngine, 1.0);
    }

    public RandPoissonT(HepRandomEngine anEngine, double m) {
        super(anEngine, m);
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandPoissonT(RandPoissonT d) {
        super(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandPoissonT assign(RandPoissonT d) {
        super.assign(d);
        return this;
    }

    public static long shoot() {
        return shoot(1.0);
    }

    public static long shoot(double xm) {
        final HepRandomEngine e = HepRandom.getTheEngine();
        return shoot(e, xm);
    }

    public static void shootArray(int size, long[] vect) {
        shootArray(size, vect, 1.0);
    }

    public static void shootArray(int size, long[] vect, double m) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(m);
    }

    public static long shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1.0);
    }

    public static long shoot(HepRandomEngine anEngine, double mean) {
        if (mean < RandPoissonQ.tableBoundary()) {
            return RandPoissonQ.shoot(anEngine, mean);
        } else {
            return RandPoisson.shoot(anEngine, mean);
        }
    }

    public static void shootArray(HepRandomEngine anEngine, int size, long[] vect) {
        shootArray(anEngine, size, vect, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, long[] vect, double m1) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, m1);
    }

    @Override
    public long fire() {
        if (defaultMean < RandPoissonQ.tableBoundary()) {
            return RandPoissonQ.shoot(getLocalEngine(), defaultMean);
        } else {
            return RandPoisson.shoot(getLocalEngine(), defaultMean);
        }
    }

    @Override
    public long fire(double mean) {
        return shoot(getLocalEngine(), mean);
    }

    @Override
    public void fireArray(int size, long[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = fire(defaultMean);
    }

    @Override
    public void fireArray(int size, long[] vect, double m) {
        for (int i = 0; i < size; ++i) vect[i] = fire(m);
    }

    @Override
    public double call() {
        return (double) fire();
    }

    @Override
    public double call(double mean) {
        return (double) fire(mean);
    }

    @Override
    public String name() {
        return "RandPoissonT";
    }

    public static String distributionName() {
        return "RandPoissonT";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        super.put(os);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        super.get(is);
        return is;
    }
}
