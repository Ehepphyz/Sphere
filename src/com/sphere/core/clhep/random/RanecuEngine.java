package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's RanecuEngine (Random/RanecuEngine.h, RanecuEngine.cc): L'Ecuyer's combination of two
 * multiplicative congruential generators, RANECU of CERNLIB, with a table of 215 seed couples of which
 * one is in use (the index).
 */
public final class RanecuEngine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 4;
    private static final int ecuyer_a = 40014;
    private static final int ecuyer_b = 53668;
    private static final int ecuyer_c = 12211;
    private static final int ecuyer_d = 40692;
    private static final int ecuyer_e = 52774;
    private static final int ecuyer_f = 3791;
    private static final int shift1 = 2147483563;
    private static final int shift2 = 2147483399;
    private static final int maxSeq = 215;
    private static final double prec = 4.6566128E-10;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    private final long[][] table = new long[215][2];
    private int seq;

    /** On the couple of the number of engines of this kind made so far. */
    public RanecuEngine() {
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxSeq);
        seq = Math.abs(numEngines % maxSeq);
        theSeed = seq;
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        for (int i = 0; i < 2; ++i) {
            for (int j = 0; j < maxSeq; ++j) {
                HepRandom.getTheTableSeeds(table[j], j);
                table[j][i] ^= mask;
            }
        }
        theSeeds = table[seq];
    }

    /** On the couple of an index, randomized further by it. */
    public RanecuEngine(int index) {
        final int cycle = Math.abs(index / maxSeq);
        seq = Math.abs(index % maxSeq);
        theSeed = seq;
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        for (int j = 0; j < maxSeq; ++j) {
            HepRandom.getTheTableSeeds(table[j], j);
            table[j][0] ^= mask;
            table[j][1] ^= mask;
        }
        theSeeds = table[seq];
        further_randomize(seq, 0, index, shift1);
    }

    public RanecuEngine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public RanecuEngine(RanecuEngine o) {
        super(o);
        for (int i = 0; i < table.length; i++) System.arraycopy(o.table[i], 0, table[i], 0, 2);
        seq = o.seq;
    }

    public static String engineName() {
        return "RanecuEngine";
    }

    @Override
    public String name() {
        return "RanecuEngine";
    }

    private void further_randomize(int seq1, int col, int index, int modulus) {
        table[seq1][col] -= (index & 0x3FFFFFFF);
        while (table[seq1][col] <= 0) table[seq1][col] += (modulus - 1);
    }

    /** The couple of an index (modulo 215), randomized further by it and by dum. */
    @Override
    public void setSeed(long index, int dum) {
        seq = Math.abs((int) (index % maxSeq));
        theSeed = seq;
        HepRandom.getTheTableSeeds(table[seq], seq);
        theSeeds = table[seq];
        further_randomize(seq, 0, (int) index, shift1);
        further_randomize(seq, 1, dum, shift2);
    }

    /** The couple in use replaced by the seeds given, at the index pos when it is not -1. */
    @Override
    public void setSeeds(long[] seeds, int pos) {
        if (pos != -1) {
            seq = Math.abs(pos % maxSeq);
            theSeed = seq;
        }
        table[seq][0] = Math.abs(seeds[0]) % shift1;
        table[seq][1] = Math.abs(seeds[1]) % shift2;
        theSeeds = table[seq];
    }

    public void setIndex(long index) {
        seq = Math.abs((int) (index % maxSeq));
        theSeed = seq;
        theSeeds = table[seq];
    }

    @Override
    public double flat() {
        final int index = seq;
        long seed1 = table[index][0];
        long seed2 = table[index][1];
        final int k1 = (int) (seed1 / ecuyer_b);
        final int k2 = (int) (seed2 / ecuyer_e);
        seed1 = ecuyer_a * (seed1 - (long) k1 * ecuyer_b) - (long) k1 * ecuyer_c;
        if (seed1 < 0) seed1 += shift1;
        seed2 = ecuyer_d * (seed2 - (long) k2 * ecuyer_e) - (long) k2 * ecuyer_f;
        if (seed2 < 0) seed2 += shift2;
        table[index][0] = seed1;
        table[index][1] = seed2;
        long diff = seed1 - seed2;
        if (diff <= 0) diff += (shift1 - 1);
        return diff * prec;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        final int index = seq;
        long seed1 = table[index][0];
        long seed2 = table[index][1];
        for (int i = 0; i < size; ++i) {
            final int k1 = (int) (seed1 / ecuyer_b);
            final int k2 = (int) (seed2 / ecuyer_e);
            seed1 = ecuyer_a * (seed1 - (long) k1 * ecuyer_b) - (long) k1 * ecuyer_c;
            if (seed1 < 0) seed1 += shift1;
            seed2 = ecuyer_d * (seed2 - (long) k2 * ecuyer_e) - (long) k2 * ecuyer_f;
            if (seed2 < 0) seed2 += shift2;
            long diff = seed1 - seed2;
            if (diff <= 0) diff += (shift1 - 1);
            vect[i] = diff * prec;
        }
        table[index][0] = seed1;
        table[index][1] = seed2;
    }

    @Override
    public long toUnsignedInt() {
        final int index = seq;
        long seed1 = table[index][0];
        long seed2 = table[index][1];
        final int k1 = (int) (seed1 / ecuyer_b);
        final int k2 = (int) (seed2 / ecuyer_e);
        seed1 = ecuyer_a * (seed1 - (long) k1 * ecuyer_b) - (long) k1 * ecuyer_c;
        if (seed1 < 0) seed1 += shift1;
        seed2 = ecuyer_d * (seed2 - (long) k2 * ecuyer_e) - (long) k2 * ecuyer_f;
        if (seed2 < 0) seed2 += shift2;
        table[index][0] = seed1;
        table[index][1] = seed2;
        long diff = seed1 - seed2;
        if (diff <= 0) diff += (shift1 - 1);
        return ((diff << 1) | (seed1 & 1)) & 0xffffffffL;
    }

    @Override
    public void saveStatus(String filename) {
        saveWords(filename);
    }

    @Override
    public void restoreStatus(String filename) {
        final CIStream inFile = openStatus(filename);
        if (!checkFile(inFile, filename, engineName(), "restoreStatus")) {
            StdStreams.cerr().print("  -- Engine state remains unchanged\n");
            return;
        }
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nJamesRandom state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail() && !inFile.eof()) {
            for (int i = 0; i < 2; ++i) table[(int) theSeed][i] = inFile.nextLong();
            seq = (int) theSeed;
        }
    }

    @Override
    public void showStatus() {
        StdStreams.cout().print("\n--------- Ranecu engine status ---------\n Initial seed (index) = " + theSeed
            + "\n Current couple of seeds = " + table[(int) theSeed][0] + ", " + table[(int) theSeed][1]
            + "\n----------------------------------------\n");
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "RanecuEngine-begin");
    }

    @Override
    public long[] put() {
        return new long[] {engineIDulong(engineName()), theSeed, table[(int) theSeed][0], table[(int) theSeed][1]};
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("RanecuEngine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nRanecuEngine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "RanecuEngine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nRanecuEngine state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        for (int i = 0; i < 2; ++i) table[(int) theSeed][i] = is.nextLong();
        final String endMarker = marker(is);
        if (!endMarker.equals("RanecuEngine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nRanecuEngine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
            return is;
        }
        seq = (int) theSeed;
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nRanecuEngine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nRanecuEngine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        theSeed = v[1];
        table[(int) theSeed][0] = v[2];
        table[(int) theSeed][1] = v[3];
        seq = (int) theSeed;
        return true;
    }
}
