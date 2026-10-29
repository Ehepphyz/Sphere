package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandPoissonQ (Random/RandPoissonQ.h, .icc, RandPoissonQ.cc): quick Poisson numbers — summing
 * terms below a mean of 10, tabulated CDFs up to 100, a quadratic transform of a RandGaussQ number above.
 */
public class RandPoissonQ extends RandPoisson {

    private static final double FIRST_MU = 10;
    private static final double LAST_MU = 95;
    private static final double S = 5;
    private static final int BELOW = 30;
    private static final int ENTRIES = 51;
    public static final double MAXIMUM_POISSON_DEVIATE = 2.0E9;

    private static final double[] oneOverN = {
        0, 1., 1 / 2., 1 / 3., 1 / 4., 1 / 5., 1 / 6., 1 / 7., 1 / 8., 1 / 9.,
        1 / 10., 1 / 11., 1 / 12., 1 / 13., 1 / 14., 1 / 15., 1 / 16., 1 / 17., 1 / 18., 1 / 19.,
        1 / 20., 1 / 21., 1 / 22., 1 / 23., 1 / 24., 1 / 25., 1 / 26., 1 / 27., 1 / 28., 1 / 29.,
        1 / 30., 1 / 31., 1 / 32., 1 / 33., 1 / 34., 1 / 35., 1 / 36., 1 / 37., 1 / 38., 1 / 39.,
        1 / 40., 1 / 41., 1 / 42., 1 / 43., 1 / 44., 1 / 45., 1 / 46., 1 / 47., 1 / 48., 1 / 49.};

    private static final double MINRANGE = .01;

    private double a0;
    private double a1;
    private double a2;
    private double sigma;

    public RandPoissonQ(HepRandomEngine anEngine) {
        this(anEngine, 1.0);
    }

    public RandPoissonQ(HepRandomEngine anEngine, double b1) {
        super(anEngine, b1);
        setupForDefaultMu();
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandPoissonQ(RandPoissonQ d) {
        super(d);
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandPoissonQ assign(RandPoissonQ d) {
        super.assign(d);
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandPoissonQ d) {
        a0 = d.a0;
        a1 = d.a1;
        a2 = d.a2;
        sigma = d.sigma;
    }

    /** The transform's constants for the default mean (with divisions, where shoot multiplies by 1/6, 1/324). */
    private void setupForDefaultMu() {
        final double sig2 = defaultMean * (.9998654 - .08346 / defaultMean);
        sigma = Math.sqrt(sig2);
        final double t = 1. / (sig2);
        a2 = t / 6 + t * t / 324;
        a1 = Math.sqrt(1 - 2 * a2 * a2 * sig2);
        a0 = defaultMean + .5 - sig2 * a2;
    }

    public static int tableBoundary() {
        return (int) (LAST_MU + S);
    }

    /* ---- static ---- */

    public static long shoot() {
        return shoot(1.0);
    }

    public static long shoot(double xm) {
        return shoot(getTheEngine(), xm);
    }

    public static void shootArray(int size, long[] vect) {
        shootArray(size, vect, 1.0);
    }

    public static void shootArray(int size, long[] vect, double m) {
        for (int i = 0; i < size; i++) vect[i] = shoot(m);
    }

    public static long shoot(HepRandomEngine anEngine) {
        return shoot(anEngine, 1.0);
    }

    public static long shoot(HepRandomEngine anEngine, double mean) {
        if (mean < LAST_MU + S) {
            return poissonDeviateSmall(anEngine, mean);
        }
        final double sig2 = mean * (.9998654 - .08346 / mean);
        final double lastSigma = Math.sqrt(sig2);
        final double t = 1. / sig2;
        final double lastA2 = t * (1. / 6.) + t * t * (1. / 324.);
        final double lastA1 = Math.sqrt(1 - 2 * lastA2 * lastA2 * sig2);
        final double lastA0 = mean + .5 - sig2 * lastA2;
        return poissonDeviateQuick(anEngine, lastA0, lastA1, lastA2, lastSigma);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, long[] vect) {
        shootArray(anEngine, size, vect, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, long[] vect, double m1) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, m1);
    }

    /* ---- this distribution's engine ---- */

    /** The default mean, with the constants set up at construction. */
    @Override
    public long fire() {
        if (defaultMean < LAST_MU + S) {
            return poissonDeviateSmall(getLocalEngine(), defaultMean);
        } else {
            return poissonDeviateQuick(getLocalEngine(), a0, a1, a2, sigma);
        }
    }

    @Override
    public long fire(double mean) {
        return shoot(getLocalEngine(), mean);
    }

    /** fire(defaultMean) each time, as the C++ (not fire()). */
    @Override
    public void fireArray(int size, long[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(defaultMean);
    }

    @Override
    public void fireArray(int size, long[] vect, double m) {
        for (int i = 0; i < size; i++) vect[i] = fire(m);
    }

    @Override
    public double call() {
        return (double) fire();
    }

    @Override
    public double call(double mean) {
        return (double) fire(mean);
    }

    /* ---- the methods ---- */

    static long poissonDeviateQuick(HepRandomEngine e, double mu) {
        final double sig2 = mu * (.9998654 - .08346 / mu);
        final double sig = Math.sqrt(sig2);
        final double t = 1. / sig2;
        final double sa2 = t * (1. / 6.) + t * t * (1. / 324.);
        final double sa1 = Math.sqrt(1 - 2 * sa2 * sa2 * sig2);
        final double sa0 = mu + .5 - sig2 * sa2;
        return poissonDeviateQuick(e, sa0, sa1, sa2, sig);
    }

    static long poissonDeviateQuick(HepRandomEngine e, double A0, double A1, double A2, double sig) {
        double g = RandGaussQ.shoot(e);
        g *= sig;
        double p = A2 * g * g + A1 * g + A0;
        if (p < 0) return 0;
        if (p > MAXIMUM_POISSON_DEVIATE) p = MAXIMUM_POISSON_DEVIATE;
        return (long) p;
    }

    static long poissonDeviateSmall(HepRandomEngine e, double mean) {
        long N1;
        long N2;
        double rRange;
        double rRemainder = 0;

        if (mean > LAST_MU + S) {
            return RandPoisson.shoot(e, mean);
        }
        if (mean <= 0) {
            return 0;
        }
        final double r = e.flat();

        // below the tables: the terms summed
        if (mean < FIRST_MU) {
            long N = 0;
            double term = CMath.exp(-mean);
            double cdf = term;
            if (r < (1 - 1.0E-9)) {
                int oneOverNptr = 0;
                while (cdf <= r) {
                    N++;
                    oneOverNptr++;
                    term *= (mean * oneOverN[oneOverNptr]);
                    cdf += term;
                }
                return N;
            } else {
                double cdf0;
                while (cdf <= r) {
                    N++;
                    term *= (mean / N);
                    cdf0 = cdf;
                    cdf += term;
                    if (cdf == cdf0) break;
                }
                return N;
            }
        }

        // the row of the mean at or below
        final int rowNumber = (int) ((mean - FIRST_MU) / S);
        final int cdfs = rowNumber * ENTRIES;
        final double[] T = PoissonTables.T;
        final double mu = FIRST_MU + rowNumber * S;
        final double deltaMu = mean - mu;
        int Nmin = (int) (mu - BELOW);
        if (Nmin < 1) Nmin = 1;
        final int Nmax = Nmin + (ENTRIES - 1);

        if (r < T[cdfs]) {
            long N = 0;
            double term = CMath.exp(-mu);
            double cdf = term;
            double cdf0;
            while (cdf <= r) {
                N++;
                term *= (mu / N);
                cdf0 = cdf;
                cdf += term;
                if (cdf == cdf0) break;
            }
            N1 = N;
            rRange = 0;
        } else if (r < T[cdfs + ENTRIES - 1]) {
            int a = 0;
            int b = ENTRIES - 1;
            while (b != (a + 1)) {
                final int c = (a + b + 1) >> 1;
                if (r > T[cdfs + c]) {
                    a = c;
                } else {
                    b = c;
                }
            }
            N1 = Nmin + a;
            rRange = T[cdfs + a + 1] - T[cdfs + a];
            rRemainder = r - T[cdfs + a];
        } else {
            long N = Nmax - 1;
            double cdf = T[cdfs + ENTRIES - 1];
            double term = cdf - T[cdfs + ENTRIES - 2];
            double cdf0;
            while (cdf <= r) {
                N++;
                term *= (mu / N);
                cdf0 = cdf;
                cdf += term;
                if (cdf == cdf0) break;
            }
            N1 = N;
            rRange = 0;
        }

        // the Poisson of the rest of the mean, from the fraction left in the bin or a new number
        final double s;
        if (rRange > MINRANGE) {
            s = rRemainder / rRange;
        } else {
            s = e.flat();
        }
        N2 = 0;
        double term = CMath.exp(-deltaMu);
        double cdf = term;
        if (s < (1 - 1.0E-10)) {
            int oneOverNptr = 0;
            while (cdf <= s) {
                N2++;
                oneOverNptr++;
                term *= (deltaMu * oneOverN[oneOverNptr]);
                cdf += term;
            }
        } else {
            while (cdf <= s) {
                N2++;
                term *= (deltaMu / N2);
                cdf += term;
            }
        }
        return N1 + N2;
    }

    @Override
    public String name() {
        return "RandPoissonQ";
    }

    public static String distributionName() {
        return "RandPoissonQ";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, a0);
        DistIO.put(os, a1);
        DistIO.put(os, a2);
        DistIO.put(os, sigma);
        super.put(os);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            a0 = DistIO.get(is);
            a1 = DistIO.get(is);
            a2 = DistIO.get(is);
            sigma = DistIO.get(is);
            super.get(is);
            return is;
        }
        a0 = DistIO.number(w);
        a1 = is.nextDouble();
        a2 = is.nextDouble();
        sigma = is.nextDouble();
        super.get(is);
        return is;
    }
}
