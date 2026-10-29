package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's RandEngine (Random/RandEngine.h, RandEngine.cc): the C library's rand(), its 15 or 31 bits
 * made into 32 random bits as CLHEP's RandEngineBuilder does for the library's RAND_MAX. Its state is
 * the C library's, one for the program; the saved state is the seed and the count of rand() calls,
 * replayed on restoring.
 */
public final class RandEngine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 3;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    private long seq;

    public RandEngine(long seed) {
        setSeed(seed, 0);
        theSeeds = null;
        seq = 0;
    }

    public RandEngine(int rowIndex, int colIndex) {
        final long[] seeds = new long[2];
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final int col = Math.abs(colIndex % 2);
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        HepRandom.getTheTableSeeds(seeds, row);
        final long seed = seeds[col] ^ mask;
        setSeed(seed, 0);
        theSeeds = null;
        seq = 0;
    }

    public RandEngine() {
        final long[] seeds = new long[2];
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        HepRandom.getTheTableSeeds(seeds, curIndex);
        final long seed = seeds[0] ^ mask;
        setSeed(seed, 0);
        theSeeds = null;
        seq = 0;
    }

    public RandEngine(CIStream is) {
        get(is);
    }

    public static String engineName() {
        return "RandEngine";
    }

    @Override
    public String name() {
        return "RandEngine";
    }

    @Override
    public void setSeed(long seed, int extra) {
        theSeed = seed;
        CLib.srand((int) seed);
        seq = 0;
    }

    @Override
    public void setSeeds(long[] seeds, int extra) {
        setSeed(seeds != null ? seeds[0] : 19780503L, 0);
        theSeeds = seeds;
    }

    /** RandEngineBuilder&lt;RAND_MAX&gt;::thirtyTwoRandomBits for the two C libraries. */
    private int thirtyTwoRandomBits() {
        if (CLib.randMax() == 2147483647) {
            int x = CLib.rand() << 1;
            ++seq;
            x ^= ((x >>> 23) ^ (x >>> 7)) ^ 1;
            return x;
        }
        int x = CLib.rand() << 17;
        ++seq;
        x ^= CLib.rand() << 2;
        ++seq;
        x ^= ((x >>> 23) ^ (x >>> 7)) ^ 3;
        return x;
    }

    @Override
    public double flat() {
        double r;
        do {
            r = thirtyTwoRandomBits() & 0xffffffffL;
        } while (r == 0);
        return r / 4294967296.0;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = flat();
    }

    @Override
    public long toUnsignedInt() {
        return thirtyTwoRandomBits() & 0xffffffffL;
    }

    @Override
    public void saveStatus(String filename) {
        saveWords(filename);
    }

    @Override
    public void restoreStatus(String filename) {
        final CIStream inFile = openStatus(filename);
        if (!checkFile(inFile, filename, engineName(), "restoreStatus")) {
            StdStreams.cout().print("  -- Engine state remains unchanged\n");
            return;
        }
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nRandEngine state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail() && !inFile.eof()) {
            final long count = inFile.nextLong();
            setSeed(theSeed, 0);
            seq = 0;
            while (seq < count) flat();
        }
    }

    @Override
    public void showStatus() {
        StdStreams.cout().print("\n---------- Rand engine status ----------\n Initial seed  = " + theSeed
            + "\n Shooted sequences = " + seq + "\n----------------------------------------\n");
    }

    @Override
    public COStream put(COStream os) {
        return os.put(" ").put("RandEngine-begin").put("\n").put(theSeed).put(" ").put(seq).put(" ")
            .put("RandEngine-end").put("\n");
    }

    @Override
    public long[] put() {
        return new long[] {engineIDulong(engineName()), theSeed, seq};
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("RandEngine-begin")) {
            is.setFail();
            StdStreams.cout().print("\nInput stream mispositioned or\nRandEngine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "RandEngine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nRandEngine state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        final long count = is.nextLong();
        final String endMarker = marker(is);
        if (!endMarker.equals("RandEngine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nRandEngine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
            return is;
        }
        setSeed(theSeed, 0);
        while (seq < count) flat();
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nRandEngine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nRandEngine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        theSeed = v[1];
        final int count = (int) v[2];
        setSeed(theSeed, 0);
        while (seq < count) flat();
        return true;
    }
}
