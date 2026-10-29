package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandExponential (Random/RandExponential.h, .icc, RandExponential.cc): -mean log(u) of a flat
 * number u.
 */
public class RandExponential extends HepRandom {

    private HepRandomEngine localEngine;
    private double defaultMean;

    public RandExponential(HepRandomEngine anEngine) {
        this(anEngine, 1.0);
    }

    public RandExponential(HepRandomEngine anEngine, double mean) {
        localEngine = anEngine;
        defaultMean = mean;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandExponential(RandExponential d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandExponential assign(RandExponential d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandExponential d) {
        localEngine = d.localEngine;
        defaultMean = d.defaultMean;
    }

    public static double shoot() {
        return -CMath.log(HepRandom.getTheEngine().flat());
    }

    public static double shoot(double mean) {
        return -CMath.log(HepRandom.getTheEngine().flat()) * mean;
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean) {
        for (int i = 0; i < size; i++) vect[i] = shoot(mean);
    }

    public static double shoot(HepRandomEngine anEngine) {
        return -CMath.log(anEngine.flat());
    }

    public static double shoot(HepRandomEngine anEngine, double mean) {
        return -CMath.log(anEngine.flat()) * mean;
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double mean) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, mean);
    }

    public double fire() {
        return -CMath.log(localEngine.flat()) * defaultMean;
    }

    public double fire(double mean) {
        return -CMath.log(localEngine.flat()) * mean;
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultMean);
    }

    public void fireArray(int size, double[] vect, double mean) {
        for (int i = 0; i < size; i++) vect[i] = fire(mean);
    }

    @Override
    public double call() {
        return fire(defaultMean);
    }

    public double call(double mean) {
        return fire(mean);
    }

    @Override
    public String name() {
        return "RandExponential";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandExponential";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, defaultMean);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            defaultMean = DistIO.get(is);
            return is;
        }
        defaultMean = DistIO.number(w);
        return is;
    }
}
