package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandBreitWigner (Random/RandBreitWigner.h, .icc, RandBreitWigner.cc): the Breit-Wigner (Cauchy)
 * distribution of a mass of the given mean and width, cut or not at mean ± cut; the M2 forms draw the
 * relativistic one in m², returning m.
 */
public class RandBreitWigner extends HepRandom {

    private HepRandomEngine localEngine;
    private double defaultA;
    private double defaultB;

    public RandBreitWigner(HepRandomEngine anEngine) {
        this(anEngine, 1.0, 0.2);
    }

    public RandBreitWigner(HepRandomEngine anEngine, double a) {
        this(anEngine, a, 0.2);
    }

    public RandBreitWigner(HepRandomEngine anEngine, double a, double b) {
        localEngine = anEngine;
        defaultA = a;
        defaultB = b;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandBreitWigner(RandBreitWigner d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandBreitWigner assign(RandBreitWigner d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandBreitWigner d) {
        localEngine = d.localEngine;
        defaultA = d.defaultA;
        defaultB = d.defaultB;
    }

    /** std::max(a, b): (a &lt; b) ? b : a, which keeps a when b is NaN. */
    private static double stdMax(double a, double b) {
        return (a < b) ? b : a;
    }

    /* ---- the draws, from an engine ---- */

    private static double bw(HepRandomEngine e, double mean, double gamma) {
        final double rval = 2.0 * e.flat() - 1.0;
        final double displ = 0.5 * gamma * CMath.tan(rval * SystemOfUnits.halfpi);
        return mean + displ;
    }

    private static double bw(HepRandomEngine e, double mean, double gamma, double cut) {
        if (gamma == 0.0) return mean;
        final double val = CMath.atan(2.0 * cut / gamma);
        final double rval = 2.0 * e.flat() - 1.0;
        final double displ = 0.5 * gamma * CMath.tan(rval * val);
        return mean + displ;
    }

    private static double m2(HepRandomEngine e, double mean, double gamma) {
        if (gamma == 0.0) return mean;
        final double val = CMath.atan(-mean / gamma);
        final double rval = RandFlat.shoot(e, val, SystemOfUnits.halfpi);
        final double displ = gamma * CMath.tan(rval);
        return Math.sqrt(mean * mean + mean * displ);
    }

    private static double m2(HepRandomEngine e, double mean, double gamma, double cut) {
        if (gamma == 0.0) return mean;
        final double tmp = stdMax(0.0, mean - cut);
        final double lower = CMath.atan((tmp * tmp - mean * mean) / (mean * gamma));
        final double upper = CMath.atan(((mean + cut) * (mean + cut) - mean * mean) / (mean * gamma));
        final double rval = RandFlat.shoot(e, lower, upper);
        final double displ = gamma * CMath.tan(rval);
        return Math.sqrt(stdMax(0.0, mean * mean + mean * displ));
    }

    /* ---- static ---- */

    public static double shoot() {
        return shoot(1.0, 0.2);
    }

    public static double shoot(double a) {
        return shoot(a, 0.2);
    }

    public static double shoot(double mean, double gamma) {
        return bw(HepRandom.getTheEngine(), mean, gamma);
    }

    public static double shoot(double mean, double gamma, double cut) {
        if (gamma == 0.0) return mean;
        return bw(HepRandom.getTheEngine(), mean, gamma, cut);
    }

    public static double shootM2() {
        return shootM2(1.0, 0.2);
    }

    public static double shootM2(double a) {
        return shootM2(a, 0.2);
    }

    /** RandFlat::shoot(val, halfpi) draws from this thread's engine. */
    public static double shootM2(double mean, double gamma) {
        if (gamma == 0.0) return mean;
        final double val = CMath.atan(-mean / gamma);
        final double rval = RandFlat.shoot(val, SystemOfUnits.halfpi);
        final double displ = gamma * CMath.tan(rval);
        return Math.sqrt(mean * mean + mean * displ);
    }

    public static double shootM2(double mean, double gamma, double cut) {
        if (gamma == 0.0) return mean;
        final double tmp = stdMax(0.0, mean - cut);
        final double lower = CMath.atan((tmp * tmp - mean * mean) / (mean * gamma));
        final double upper = CMath.atan(((mean + cut) * (mean + cut) - mean * mean) / (mean * gamma));
        final double rval = RandFlat.shoot(lower, upper);
        final double displ = gamma * CMath.tan(rval);
        return Math.sqrt(stdMax(0.0, mean * mean + mean * displ));
    }

    public static void shootArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = shoot(1.0, 0.2);
    }

    public static void shootArray(int size, double[] vect, double a, double b) {
        for (int i = 0; i < size; i++) vect[i] = shoot(a, b);
    }

    public static void shootArray(int size, double[] vect, double a, double b, double c) {
        for (int i = 0; i < size; i++) vect[i] = shoot(a, b, c);
    }

    public static double shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1.0, 0.2);
    }

    public static double shoot(HepRandomEngine anEngine, double a) {
        return shoot(anEngine, a, 0.2);
    }

    public static double shoot(HepRandomEngine anEngine, double mean, double gamma) {
        return bw(anEngine, mean, gamma);
    }

    public static double shoot(HepRandomEngine anEngine, double mean, double gamma, double cut) {
        return bw(anEngine, mean, gamma, cut);
    }

    public static double shootM2(HepRandomEngine anEngine) {
        return shootM2(anEngine, 1.0, 0.2);
    }

    public static double shootM2(HepRandomEngine anEngine, double a) {
        return shootM2(anEngine, a, 0.2);
    }

    public static double shootM2(HepRandomEngine anEngine, double mean, double gamma) {
        return m2(anEngine, mean, gamma);
    }

    public static double shootM2(HepRandomEngine anEngine, double mean, double gamma, double cut) {
        return m2(anEngine, mean, gamma, cut);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, 1.0, 0.2);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double a, double b) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, a, b);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double a, double b, double c) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, a, b, c);
    }

    /* ---- this distribution's engine ---- */

    public double fire() {
        return fire(defaultA, defaultB);
    }

    public double fire(double mean, double gamma) {
        return bw(localEngine, mean, gamma);
    }

    public double fire(double mean, double gamma, double cut) {
        return bw(localEngine, mean, gamma, cut);
    }

    public double fireM2() {
        return fireM2(defaultA, defaultB);
    }

    public double fireM2(double mean, double gamma) {
        return m2(localEngine, mean, gamma);
    }

    public double fireM2(double mean, double gamma, double cut) {
        return m2(localEngine, mean, gamma, cut);
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultA, defaultB);
    }

    public void fireArray(int size, double[] vect, double a, double b) {
        for (int i = 0; i < size; i++) vect[i] = fire(a, b);
    }

    public void fireArray(int size, double[] vect, double a, double b, double c) {
        for (int i = 0; i < size; i++) vect[i] = fire(a, b, c);
    }

    @Override
    public double call() {
        return fire(defaultA, defaultB);
    }

    public double call(double a, double b) {
        return fire(a, b);
    }

    public double call(double a, double b, double c) {
        return fire(a, b, c);
    }

    @Override
    public String name() {
        return "RandBreitWigner";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandBreitWigner";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, defaultA);
        DistIO.put(os, defaultB);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            defaultA = DistIO.get(is);
            defaultB = DistIO.get(is);
            return is;
        }
        defaultA = DistIO.number(w);
        defaultB = is.nextDouble();
        return is;
    }
}
