package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandLandau (Random/RandLandau.h, .icc, RandLandau.cc): the Landau distribution by its inverse
 * CDF, interpolated in a table (linear in the middle, cubic towards the ends) and by Padé approximants in
 * the tails.
 */
public class RandLandau extends HepRandom {

    /** The table's step; it ends at TABLE_END = 982 (LandauTable holds 983 points). */
    private static final float TABLE_INTERVAL = .001f;
    private static final float TABLE_MULTIPLIER = 1.0f / TABLE_INTERVAL;

    private HepRandomEngine localEngine;

    public RandLandau(HepRandomEngine anEngine) {
        localEngine = anEngine;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandLandau(RandLandau d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandLandau assign(RandLandau d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandLandau d) {
        localEngine = d.localEngine;
    }

    public static double shoot() {
        final HepRandomEngine anEngine = HepRandom.getTheEngine();
        return transform(anEngine.flat());
    }

    public static void shootArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = shoot();
    }

    public static double shoot(HepRandomEngine anotherEngine) {
        return transform(anotherEngine.flat());
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anotherEngine);
    }

    public double fire() {
        return transform(localEngine.flat());
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire();
    }

    @Override
    public double call() {
        return transform(localEngine.flat());
    }

    /** The Landau deviate whose CDF is r. */
    protected static double transform(double r) {
        final float[] inverseLandau = LandauTable.T;
        final double u = r * TABLE_MULTIPLIER;
        final int index = (int) u;
        final double du = u - index;

        // the middle: linear
        if (index >= 70 && index <= 800) {
            final double f0 = inverseLandau[index];
            final double f1 = inverseLandau[index + 1];
            return f0 + du * (f1 - f0);
        // towards the ends: cubic
        } else if (index >= 7 && index <= 980) {
            final double f_1 = inverseLandau[index - 1];
            final double f0 = inverseLandau[index];
            final double f1 = inverseLandau[index + 1];
            final double f2 = inverseLandau[index + 2];
            return f0 + du * (f1 - f0 - .25 * (1 - du) * (f2 - f1 - f0 + f_1));
        // the low tail
        } else if (index < 7) {
            final double n0 = 0.99858950;
            final double n1 = 34.5213058;
            final double d1 = 34.1760202;
            final double n2 = 17.0854528;
            final double d2 = 4.01244582;
            final double logr = CMath.log(r);
            final double x = 1 / logr;
            final double x2 = x * x;
            final double pade = (n0 + n1 * x + n2 * x2) / (1.0 + d1 * x + d2 * x2);
            return (-CMath.log(-.91893853 - logr) - 1) * pade;
        // the high tail
        } else if (index <= 999) {
            final double n0 = 1.00060006;
            final double n1 = 263.991156;
            final double d1 = 257.368075;
            final double n2 = 4373.20068;
            final double d2 = 3414.48018;
            final double x = 1 - r;
            final double x2 = x * x;
            return (n0 + n1 * x + n2 * x2) / (x * (1.0 + d1 * x + d2 * x2));
        } else {
            final double n0 = 1.00001538;
            final double n1 = 6075.14119;
            final double d1 = 6065.11919;
            final double n2 = 734266.409;
            final double d2 = 694021.044;
            final double x = 1 - r;
            final double x2 = x * x;
            return (n0 + n1 * x + n2 * x2) / (x * (1.0 + d1 * x + d2 * x2));
        }
    }

    @Override
    public String name() {
        return "RandLandau";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandLandau";
    }

    /** The state is the name: the distribution has no parameter. */
    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        DistIO.expect(is, name());
        return is;
    }
}
