package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's DRand48Engine (Random/DRand48Engine.h, DRand48Engine.cc): the C library's drand48, a 48-bit
 * linear congruential generator. Its state is the C library's, one for the program: every
 * DRand48Engine shares it, as in C++ (not thread safe there either).
 */
public final class DRand48Engine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 4;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    public DRand48Engine(long seed) {
        setSeed(seed, 0);
        theSeeds = null;
    }

    public DRand48Engine() {
        final long[] seeds = new long[2];
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        HepRandom.getTheTableSeeds(seeds, curIndex);
        final long seed = seeds[0] ^ mask;
        setSeed(seed, 0);
        theSeeds = null;
    }

    public DRand48Engine(int rowIndex, int colIndex) {
        final long[] seeds = new long[2];
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final int col = Math.abs(colIndex % 2);
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        HepRandom.getTheTableSeeds(seeds, row);
        final long seed = seeds[col] ^ mask;
        setSeed(seed, 0);
        theSeeds = null;
    }

    public DRand48Engine(CIStream is) {
        get(is);
    }

    public static String engineName() {
        return "DRand48Engine";
    }

    @Override
    public String name() {
        return "DRand48Engine";
    }

    @Override
    public void setSeed(long seed, int extra) {
        CLib.srand48(seed);
        theSeed = seed;
    }

    @Override
    public void setSeeds(long[] seeds, int extra) {
        setSeed(seeds != null ? seeds[0] : 19780503L, 0);
        theSeeds = seeds;
    }

    @Override
    public double flat() {
        double num = 0.;
        while (num == 0.) num = CLib.drand48();
        return num;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = flat();
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nDRand48Engine state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail() && !inFile.eof()) {
            theSeed = inFile.nextLong();
            final int[] cseed = new int[3];
            for (int i = 0; i < 3; ++i) cseed[i] = (int) inFile.nextUnsigned(16);
            CLib.seed48(cseed);
        }
    }

    @Override
    public void showStatus() {
        final int[] cseed = CLib.state48();
        StdStreams.cout().print("\n-------- DRand48 engine status ---------\n Initial seed  = " + theSeed
            + "\n Current seeds = " + cseed[0] + ", " + cseed[1] + ", " + cseed[2]
            + "\n----------------------------------------\n");
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "DRand48Engine-begin");
    }

    @Override
    public long[] put() {
        final int[] cseed = CLib.state48();
        return new long[] {engineIDulong(engineName()), cseed[0], cseed[1], cseed[2]};
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("DRand48Engine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nDRand48Engine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "DRand48Engine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        final int[] cseed = new int[3];
        final String first = is.nextWord();
        if (first.equals("Uvec")) {
            final long[] v = new long[VECTOR_STATE_SIZE];
            for (int i = 0; i < VECTOR_STATE_SIZE; ++i) {
                v[i] = is.nextUnsigned(64);
                if (is.fail()) {
                    is.setFail();
                    StdStreams.cerr().print("\nDRand48Engine state (vector) description improper."
                        + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n");
                    return is;
                }
            }
            getState(v);
            return is;
        }
        final CIStream reread = new CIStream(first);
        cseed[0] = (int) reread.nextUnsigned(16);
        theSeed = is.nextLong();
        for (int i = 1; i < 3; ++i) cseed[i] = (int) is.nextUnsigned(16);
        final String endMarker = marker(is);
        if (!endMarker.equals("DRand48Engine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nDRand48Engine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
            return is;
        }
        CLib.seed48(cseed);
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nDRand48Engine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nDRand48Engine getState:state vector has wrong length - state unchanged\n");
            return false;
        }
        CLib.seed48(new int[] {(int) v[1], (int) v[2], (int) v[3]});
        return true;
    }
}
