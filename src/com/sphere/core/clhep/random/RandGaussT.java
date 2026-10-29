package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandGaussT (Random/RandGaussT.h, .icc, RandGaussT.cc): normal numbers, one per flat number,
 * by cubic interpolation of the inverse CDF ({@link HepStat#flatToGaussian}), good to about 1e-10.
 */
public class RandGaussT extends RandGauss {

    public RandGaussT(HepRandomEngine anEngine) {
        super(anEngine);
    }

    public RandGaussT(HepRandomEngine anEngine, double mean) {
        super(anEngine, mean);
    }

    public RandGaussT(HepRandomEngine anEngine, double mean, double stdDev) {
        super(anEngine, mean, stdDev);
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandGaussT(RandGaussT d) {
        super(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandGaussT assign(RandGaussT d) {
        super.assign(d);
        return this;
    }

    public static double shoot() {
        final HepRandomEngine anEngine = HepRandom.getTheEngine();
        return HepStat.flatToGaussian(anEngine.flat());
    }

    public static double shoot(HepRandomEngine anotherEngine) {
        return HepStat.flatToGaussian(anotherEngine.flat());
    }

    public static double shoot(double mean, double stdDev) {
        return shoot() * stdDev + mean;
    }

    public static double shoot(HepRandomEngine anotherEngine, double mean, double stdDev) {
        return shoot(anotherEngine) * stdDev + mean;
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 0.0, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean) {
        shootArray(size, vect, mean, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; i++) vect[i] = shoot(mean, stdDev);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 0.0, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double mean) {
        shootArray(anEngine, size, vect, mean, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, mean, stdDev);
    }

    @Override
    public double fire() {
        return HepStat.flatToGaussian(localEngine.flat()) * defaultStdDev + defaultMean;
    }

    @Override
    public double fire(double mean, double stdDev) {
        return HepStat.flatToGaussian(localEngine.flat()) * stdDev + mean;
    }

    @Override
    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultMean, defaultStdDev);
    }

    @Override
    public void fireArray(int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; i++) vect[i] = fire(mean, stdDev);
    }

    @Override
    public double call() {
        return HepStat.flatToGaussian(localEngine.flat()) * defaultStdDev + defaultMean;
    }

    @Override
    public double call(double mean, double stdDev) {
        return HepStat.flatToGaussian(localEngine.flat()) * stdDev + mean;
    }

    @Override
    public String name() {
        return "RandGaussT";
    }

    public static String distributionName() {
        return "RandGaussT";
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
