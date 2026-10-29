package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's TripleRand (Random/TripleRand.h, TripleRand.cc): DualRand's Tausworthe and congruential
 * generators xor'ed with a Hurd288Engine, whose next word also fills the low bits of each double.
 */
public final class TripleRand extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 20;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    private final int numEngines;
    private final DualRand.Tausworthe tausworthe;
    private final DualRand.IntegerCong integerCong;
    private Hurd288Engine hurd;

    public TripleRand() {
        numEngines = numberOfEngines.getAndIncrement();
        tausworthe = new DualRand.Tausworthe(1234567 + numEngines + 175321);
        integerCong = new DualRand.IntegerCong(69607 * tausworthe.next() + 54329, numEngines);
        hurd = new Hurd288Engine((19781127 + integerCong.next()) & 0xffffffffL);
        theSeed = 1234567;
    }

    public TripleRand(long seed) {
        numEngines = 0;
        tausworthe = new DualRand.Tausworthe((int) seed + 175321);
        integerCong = new DualRand.IntegerCong(69607 * tausworthe.next() + 54329, 1313);
        hurd = new Hurd288Engine((19781127 + integerCong.next()) & 0xffffffffL);
        theSeed = seed;
    }

    public TripleRand(int rowIndex, int colIndex) {
        numEngines = numberOfEngines.get();
        tausworthe = new DualRand.Tausworthe(rowIndex + numEngines * colIndex + 175321);
        integerCong = new DualRand.IntegerCong(69607 * tausworthe.next() + 54329, 19);
        hurd = new Hurd288Engine((19781127 + integerCong.next()) & 0xffffffffL);
        theSeed = rowIndex;
    }

    public TripleRand(CIStream is) {
        numEngines = 0;
        tausworthe = new DualRand.Tausworthe();
        integerCong = new DualRand.IntegerCong();
        hurd = new Hurd288Engine();
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public TripleRand(TripleRand o) {
        super(o);
        numEngines = o.numEngines;
        tausworthe = new DualRand.Tausworthe();
        tausworthe.assign(o.tausworthe);
        integerCong = new DualRand.IntegerCong();
        integerCong.assign(o.integerCong);
        hurd = new Hurd288Engine(o.hurd);
    }

    public static String engineName() {
        return "TripleRand";
    }

    @Override
    public String name() {
        return "TripleRand";
    }

    /** The Hurd288Engine inside. */
    public Hurd288Engine hurd() {
        return hurd;
    }

    @Override
    public double flat() {
        final int ic = integerCong.next();
        final int t = tausworthe.next();
        final int h = (int) hurd.toUnsignedInt();
        return ((t ^ ic ^ h) & 0xffffffffL) * twoToMinus_32() + ((h >>> 11) & 0xffffffffL) * twoToMinus_53()
            + nearlyTwoToMinus_54();
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) {
            vect[i] = flat();
        }
    }

    @Override
    public float toFloat() {
        final int ic = integerCong.next();
        final int t = tausworthe.next();
        final int h = (int) hurd.toUnsignedInt();
        return (float) (((ic ^ t ^ h) & 0xffffffffL) * twoToMinus_32() + nearlyTwoToMinus_54());
    }

    @Override
    public long toUnsignedInt() {
        final int ic = integerCong.next();
        final int t = tausworthe.next();
        final int h = (int) hurd.toUnsignedInt();
        return (ic ^ t ^ h) & 0xffffffffL;
    }

    @Override
    public void setSeed(long seed, int extra) {
        theSeed = seed;
        tausworthe.assign(new DualRand.Tausworthe((int) seed + 175321));
        integerCong.assign(new DualRand.IntegerCong(69607 * tausworthe.next() + 54329, 1313));
        hurd = new Hurd288Engine((19781127 + integerCong.next()) & 0xffffffffL);
    }

    @Override
    public void setSeeds(long[] seeds, int extra) {
        setSeed(seeds != null ? seeds[0] : 1234567, 0);
        theSeeds = seeds;
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nTripleRand state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail()) {
            tausworthe.get(inFile);
            integerCong.get(inFile);
            hurd.get(inFile);
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n-------- TripleRand engine status ---------\n");
        b.append("Initial seed           = ").append(theSeed).append('\n');
        b.append("Tausworthe generator   = \n");
        tausworthe.put(b);
        b.append("IntegerCong generator  = \n");
        integerCong.put(b);
        b.append("Hurd288Engine generator= \n");
        StdStreams.cout().print(b);
        hurd.showStatus();
        StdStreams.cout().print("\n-----------------------------------------\n");
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "TripleRand-begin");
    }

    @Override
    public long[] put() {
        final long[] vh = hurd.put();
        final long[] v = new long[9 + vh.length];
        v[0] = engineIDulong(engineName());
        tausworthe.put(v, 1);
        integerCong.put(v, 6);
        System.arraycopy(vh, 0, v, 9, vh.length);
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("TripleRand-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput mispositioned or\nTripleRand state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "TripleRand-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nTripleRand state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        tausworthe.get(is);
        integerCong.get(is);
        hurd.get(is);
        final String endMarker = marker(is);
        if (!endMarker.equals("TripleRand-end")) {
            is.setFail();
            StdStreams.cerr().print("\nTripleRand state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nTripleRand get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nTripleRand get:state vector has wrong size: " + v.length
                + " - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length < 9) return false;
        int at = tausworthe.get(v, 1);
        at = integerCong.get(v, at);
        final long[] vHurd = java.util.Arrays.copyOfRange(v, at, v.length);
        if (!hurd.get(vHurd)) {
            StdStreams.cerr().print("\nTripleRand get from vector: problem getting the hurd sub-engine state\n");
            return false;
        }
        return true;
    }
}
