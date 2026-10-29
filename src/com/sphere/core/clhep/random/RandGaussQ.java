package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandGaussQ (Random/RandGaussQ.h, .icc, RandGaussQ.cc): quick normal numbers, one per flat
 * number, by linear interpolation in a single-precision table of the inverse CDF (good to about 1e-6 in
 * the deviate, the result rounded to float).
 */
public class RandGaussQ extends RandGauss {

    private static final int Table0size = 250;
    private static final int Table1size = 1000;
    private static final double Table0step = 2.0E-6;
    private static final double Table1step = 5.0E-4;
    private static final double Table0scale = 1.0 / Table1step;
    private static final int Table0offset = 0;
    private static final int Table1offset = Table0size;

    public RandGaussQ(HepRandomEngine anEngine) {
        super(anEngine);
    }

    public RandGaussQ(HepRandomEngine anEngine, double mean) {
        super(anEngine, mean);
    }

    public RandGaussQ(HepRandomEngine anEngine, double mean, double stdDev) {
        super(anEngine, mean, stdDev);
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandGaussQ(RandGaussQ d) {
        super(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandGaussQ assign(RandGaussQ d) {
        super.assign(d);
        return this;
    }

    public static double shoot() {
        final HepRandomEngine anEngine = HepRandom.getTheEngine();
        return transformQuick(anEngine.flat());
    }

    public static double shoot(HepRandomEngine anotherEngine) {
        return transformQuick(anotherEngine.flat());
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
        return transformQuick(localEngine.flat()) * defaultStdDev + defaultMean;
    }

    @Override
    public double fire(double mean, double stdDev) {
        return transformQuick(localEngine.flat()) * stdDev + mean;
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
        return transformQuick(localEngine.flat()) * defaultStdDev + defaultMean;
    }

    @Override
    public double call(double mean, double stdDev) {
        return transformQuick(localEngine.flat()) * stdDev + mean;
    }

    /** The deviate of a flat number: linear in the table, rounded to float; the tail below 2e-6 computed. */
    protected static double transformQuick(double r) {
        double sign = +1.0;
        if (r > .5) {
            r = 1 - r;
            sign = -1.0;
        }
        int index;
        final double dx;
        if (r >= Table1step) {
            index = (int) ((Table1size << 1) * r);
            if (index == Table1size) return 0.0;
            dx = (Table1size << 1) * r - index;
            index += Table1offset - 1;
        } else if (r > Table0step) {
            final double rr = r * Table0scale;
            index = (int) (Table0size * rr);
            dx = Table0size * rr - index;
            index += Table0offset - 1;
        } else {
            return sign * transformSmall(r);
        }
        final double y0 = GaussQTables.T[index++];
        final double y1 = GaussQTables.T[index];
        return (float) (sign * (y1 * dx + y0 * (1.0 - dx)));
    }

    protected static double transformSmall(double r) {
        final double eps = 1.0e-7;
        double guess = 7.5;
        double v = 0;
        for (int i = 1; i < 50; i++) {
            final double vn2 = 1.0 / (guess * guess);
            double s1 = -13 * 11 * 9 * 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2 * vn2 * vn2 * vn2;
            s1 += 11 * 9 * 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2 * vn2 * vn2;
            s1 += -9 * 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2 * vn2;
            s1 += 7 * 5 * 3 * vn2 * vn2 * vn2 * vn2;
            s1 += -5 * 3 * vn2 * vn2 * vn2;
            s1 += 3 * vn2 * vn2 - vn2 + 1.0;
            v = Math.sqrt(2.0 * CMath.log(s1 / (r * guess * Math.sqrt(SystemOfUnits.twopi))));
            if (Math.abs(v - guess) < eps) break;
            guess = v;
        }
        return -v;
    }

    @Override
    public String name() {
        return "RandGaussQ";
    }

    public static String distributionName() {
        return "RandGaussQ";
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
