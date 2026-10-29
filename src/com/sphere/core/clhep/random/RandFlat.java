package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's RandFlat (Random/RandFlat.h, .icc, RandFlat.cc): flat numbers in [a, b), integers in [a1, n),
 * and single bits drawn 15 at a time from one flat number. The static shoot()s draw from this thread's
 * engine (the bits they keep are this thread's), fire() from the engine given.
 */
public class RandFlat extends HepRandom {

    private static final int MSBBits = 15;
    private static final long MSB = 1L << MSBBits;

    private long randomInt;
    private long firstUnusedBit;
    private HepRandomEngine localEngine;
    private double defaultWidth;
    private double defaultA;
    private double defaultB;

    public RandFlat(HepRandomEngine anEngine) {
        this(anEngine, 0.0, 1.0);
    }

    public RandFlat(HepRandomEngine anEngine, double width) {
        this(anEngine, 0.0, width);
    }

    public RandFlat(HepRandomEngine anEngine, double a, double b) {
        localEngine = anEngine;
        defaultWidth = b - a;
        defaultA = a;
        defaultB = b;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandFlat(RandFlat d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandFlat assign(RandFlat d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandFlat d) {
        randomInt = d.randomInt;
        firstUnusedBit = d.firstUnusedBit;
        localEngine = d.localEngine;
        defaultWidth = d.defaultWidth;
        defaultA = d.defaultA;
        defaultB = d.defaultB;
    }

    /* ---- static: this thread's engine, or the one given ---- */

    public static double shoot() {
        return HepRandom.getTheEngine().flat();
    }

    public static double shoot(double width) {
        return width * shoot();
    }

    public static double shoot(double a, double b) {
        return (b - a) * shoot() + a;
    }

    public static long shootInt(long n) {
        return (long) (shoot() * (double) n);
    }

    public static long shootInt(long a1, long n) {
        return (long) (shoot() * (double) (n - a1)) + a1;
    }

    public static int shootBit() {
        final Statics s = Statics.get();
        if (s.flatFirstUnusedBit == 0) shootBits(s, shoot());
        final long temp = s.flatFirstUnusedBit & s.flatRandomInt;
        s.flatFirstUnusedBit >>>= 1;
        return temp != 0 ? 1 : 0;
    }

    public static void shootArray(int size, double[] vect) {
        HepRandom.getTheEngine().flatArray(size, vect);
    }

    public static void shootArray(int size, double[] vect, double lx, double dx) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(lx, dx);
    }

    public static double shoot(HepRandomEngine anEngine) {
        return anEngine.flat();
    }

    public static double shoot(HepRandomEngine anEngine, double width) {
        return width * anEngine.flat();
    }

    public static double shoot(HepRandomEngine anEngine, double a, double b) {
        return (b - a) * anEngine.flat() + a;
    }

    public static long shootInt(HepRandomEngine anEngine, long n) {
        return (long) (anEngine.flat() * (double) n);
    }

    public static long shootInt(HepRandomEngine anEngine, long a1, long n) {
        return (long) ((double) (n - a1) * anEngine.flat()) + a1;
    }

    public static int shootBit(HepRandomEngine engine) {
        final Statics s = Statics.get();
        if (s.flatFirstUnusedBit == 0) shootBits(s, shoot(engine));
        final long temp = s.flatFirstUnusedBit & s.flatRandomInt;
        s.flatFirstUnusedBit >>>= 1;
        return temp != 0 ? 1 : 0;
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        anEngine.flatArray(size, vect);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double lx, double dx) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(anEngine, lx, dx);
    }

    /** 16 bits from a flat number, the first of them MSB. */
    private static void shootBits(Statics s, double flat) {
        final double factor = 2.0 * MSB;
        s.flatFirstUnusedBit = MSB;
        s.flatRandomInt = (long) (factor * flat);
    }

    /* ---- this distribution's engine ---- */

    public double fire() {
        return (defaultB - defaultA) * localEngine.flat() + defaultA;
    }

    public double fire(double width) {
        return width * localEngine.flat();
    }

    public double fire(double a, double b) {
        return (b - a) * localEngine.flat() + a;
    }

    public long fireInt(long n) {
        return (long) (localEngine.flat() * (double) n);
    }

    public long fireInt(long a1, long n) {
        return (long) (localEngine.flat() * (double) (n - a1)) + a1;
    }

    public int fireBit() {
        if (firstUnusedBit == 0) {
            final double factor = 2.0 * MSB;
            firstUnusedBit = MSB;
            randomInt = (long) (factor * localEngine.flat());
        }
        final long temp = firstUnusedBit & randomInt;
        firstUnusedBit >>>= 1;
        return temp != 0 ? 1 : 0;
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = fire(defaultA, defaultB);
    }

    public void fireArray(int size, double[] vect, double lx, double dx) {
        for (int i = 0; i < size; ++i) vect[i] = fire(lx, dx);
    }

    @Override
    public double call() {
        return fire(defaultA, defaultB);
    }

    public double call(double w) {
        return fire(w);
    }

    public double call(double a, double b) {
        return fire(a, b);
    }

    @Override
    public String name() {
        return "RandFlat";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandFlat";
    }

    /* ---- states ---- */

    public static void saveEngineStatus() {
        saveEngineStatus("Config.conf");
    }

    public static void saveEngineStatus(String filename) {
        getTheEngine().saveStatus(filename);
        final Statics s = Statics.get();
        DistIO.append(filename, "RANDFLAT staticRandomInt: " + s.flatRandomInt + "    staticFirstUnusedBit: "
            + s.flatFirstUnusedBit + "\n");
    }

    public static void restoreEngineStatus() {
        restoreEngineStatus("Config.conf");
    }

    public static void restoreEngineStatus(String filename) {
        getTheEngine().restoreStatus(filename);
        final CIStream infile = DistIO.read(filename);
        if (infile == null) return;
        if (DistIO.seek(infile, "RANDFLAT")) {
            final Statics s = Statics.get();
            DistIO.word(infile, 39);
            s.flatRandomInt = infile.nextUnsigned(64);
            DistIO.word(infile, 39);
            s.flatFirstUnusedBit = infile.nextUnsigned(64);
        }
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        os.put(randomInt).put(" ").put(firstUnusedBit).put("\n");
        DistIO.put(os, defaultWidth);
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
            randomInt = is.nextUnsigned(64);
            firstUnusedBit = is.nextUnsigned(64);
            defaultWidth = DistIO.get(is);
            defaultA = DistIO.get(is);
            defaultB = DistIO.get(is);
            if (is.fail()) {
                is.setFail();
                StdStreams.cerr().print("\nRandFlat input failed\nInput stream is probably mispositioned now.\n");
                return is;
            }
            return is;
        }
        randomInt = DistIO.integer(w);
        firstUnusedBit = is.nextUnsigned(64);
        defaultWidth = is.nextDouble();
        defaultA = is.nextDouble();
        defaultB = is.nextDouble();
        return is;
    }

    public static COStream saveDistState(COStream os) {
        os.put(distributionName()).put("\n");
        final int prec = os.precision();
        os.precision(20);
        final Statics s = Statics.get();
        os.put("RANDFLAT staticRandomInt: ").put(s.flatRandomInt).put("    staticFirstUnusedBit: ")
            .put(s.flatFirstUnusedBit).put("\n");
        os.precision(prec);
        return os;
    }

    public static CIStream restoreDistState(CIStream is) {
        if (!DistIO.expectStatic(is, distributionName())) return is;
        final String keyword = is.nextWord();
        if (!keyword.equals("RANDFLAT")) {
            is.setFail();
            StdStreams.cerr().print("Mismatch when expecting to read RANDFLAT bit cache info: " + keyword + "\n");
            return is;
        }
        final Statics s = Statics.get();
        is.nextWord();
        s.flatRandomInt = is.nextUnsigned(64);
        is.nextWord();
        s.flatFirstUnusedBit = is.nextUnsigned(64);
        return is;
    }

    public static COStream saveFullState(COStream os) {
        HepRandom.saveFullState(os);
        saveDistState(os);
        return os;
    }

    public static CIStream restoreFullState(CIStream is) {
        HepRandom.restoreFullState(is);
        restoreDistState(is);
        return is;
    }
}
