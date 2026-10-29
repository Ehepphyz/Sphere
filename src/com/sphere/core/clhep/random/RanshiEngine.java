package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's RanshiEngine (Random/RanshiEngine.h, RanshiEngine.cc): F. Gutbrod's "random spin" generator,
 * a buffer of 512 words of 32 bits read alternately in its two halves, each word turned and mixed with
 * the "red spin" as it is read.
 */
public final class RanshiEngine extends HepRandomEngine {

    private static final int numBuff = 512;
    public static final int VECTOR_STATE_SIZE = numBuff + 4;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    /** Unsigned in C++. */
    private int halfBuff, numFlats;
    private final int[] buffer = new int[numBuff];
    private int redSpin;

    public RanshiEngine() {
        final int numEngines = numberOfEngines.getAndIncrement();
        int i = 0;
        while (i < numBuff) {
            buffer[i] = (int) ((numEngines + 19780503L * (i + 1)) & 0xffffffffL);
            ++i;
        }
        theSeed = numEngines + 19780503L * ++i;
        redSpin = (int) (theSeed & 0xffffffffL);
        for (i = 0; i < 10000; ++i) flat();
    }

    public RanshiEngine(long seed) {
        for (int i = 0; i < numBuff; ++i) {
            buffer[i] = (int) seed;
        }
        theSeed = seed;
        redSpin = (int) (theSeed & 0xffffffffL);
        for (int j = 0; j < numBuff * 20; ++j) {
            flat();
        }
    }

    public RanshiEngine(int rowIndex, int colIndex) {
        int i = 0;
        while (i < numBuff) {
            buffer[i] = rowIndex + (i + 1) * (colIndex + 8);
            ++i;
        }
        theSeed = rowIndex;
        redSpin = colIndex;
        for (i = 0; i < 100; ++i) flat();
    }

    public RanshiEngine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public RanshiEngine(RanshiEngine o) {
        super(o);
        halfBuff = o.halfBuff;
        numFlats = o.numFlats;
        System.arraycopy(o.buffer, 0, buffer, 0, numBuff);
        redSpin = o.redSpin;
    }

    private void assign(RanshiEngine o) {
        theSeed = o.theSeed;
        theSeeds = null;
        halfBuff = o.halfBuff;
        numFlats = o.numFlats;
        System.arraycopy(o.buffer, 0, buffer, 0, numBuff);
        redSpin = o.redSpin;
    }

    public static String engineName() {
        return "RanshiEngine";
    }

    @Override
    public String name() {
        return "RanshiEngine";
    }

    private static double u32(int x) {
        return (double) (x & 0xffffffffL);
    }

    @Override
    public double flat() {
        final int redAngle = (((numBuff / 2) - 1) & redSpin) + halfBuff;
        final int blkSpin = buffer[redAngle];
        final int boostResult = blkSpin ^ redSpin;
        buffer[redAngle] = ((blkSpin << 17) | (blkSpin >>> (32 - 17))) ^ redSpin;
        redSpin = blkSpin + numFlats++;
        halfBuff = numBuff / 2 - halfBuff;
        return u32(blkSpin) * twoToMinus_32() + u32(boostResult >>> 11) * twoToMinus_53() + nearlyTwoToMinus_54();
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) {
            vect[i] = flat();
        }
    }

    /** One word, as the next one is renewed. */
    private int nextWord() {
        final int redAngle = (((numBuff / 2) - 1) & redSpin) + halfBuff;
        final int blkSpin = buffer[redAngle];
        buffer[redAngle] = ((blkSpin << 17) | (blkSpin >>> (32 - 17))) ^ redSpin;
        redSpin = blkSpin + numFlats++;
        halfBuff = numBuff / 2 - halfBuff;
        return blkSpin;
    }

    @Override
    public float toFloat() {
        return (float) (u32(nextWord()) * twoToMinus_32());
    }

    @Override
    public long toUnsignedInt() {
        return nextWord() & 0xffffffffL;
    }

    /** As a RanshiEngine(seed) made anew. */
    @Override
    public void setSeed(long seed, int extra) {
        assign(new RanshiEngine(seed));
    }

    /** The buffer filled with the seeds up to the first zero, the last repeated. */
    @Override
    public void setSeeds(long[] seeds, int extra) {
        if (seeds[0] != 0) {
            int i = 0;
            while (i < seeds.length && seeds[i] != 0 && i < numBuff) {
                buffer[i] = (int) seeds[i];
                ++i;
            }
            while (i < numBuff) {
                buffer[i] = buffer[i - 1];
                ++i;
            }
            theSeed = seeds[0];
            redSpin = (int) theSeed;
        }
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nRanshiEngine state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail()) {
            for (int i = 0; i < numBuff; ++i) buffer[i] = (int) inFile.nextUnsigned(32);
            redSpin = (int) inFile.nextUnsigned(32);
            numFlats = (int) inFile.nextUnsigned(32);
            halfBuff = (int) inFile.nextUnsigned(32);
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n----------- Ranshi engine status ----------\n");
        b.append("Initial seed      = ").append(theSeed).append('\n');
        b.append("Current red spin  = ").append(redSpin & 0xffffffffL).append('\n');
        b.append("Values produced   = ").append(numFlats & 0xffffffffL).append('\n');
        b.append("Side of buffer    = ").append(halfBuff != 0 ? "upper" : "lower").append('\n');
        b.append("Current buffer    = \n");
        for (int i = 0; i < numBuff; i += 4) {
            b.append(String.format("%10d%11d%11d%11d%n", buffer[i] & 0xffffffffL, buffer[i + 1] & 0xffffffffL,
                buffer[i + 2] & 0xffffffffL, buffer[i + 3] & 0xffffffffL).replace("\r", ""));
        }
        b.append("-------------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "RanshiEngine-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        v[0] = engineIDulong(engineName());
        for (int i = 0; i < numBuff; ++i) v[i + 1] = buffer[i] & 0xffffffffL;
        v[numBuff + 1] = redSpin & 0xffffffffL;
        v[numBuff + 2] = numFlats & 0xffffffffL;
        v[numBuff + 3] = halfBuff & 0xffffffffL;
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("RanshiEngine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput mispositioned or\nRanshiEngine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "RanshiEngine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nRanshiEngine state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        for (int i = 0; i < numBuff; ++i) buffer[i] = (int) is.nextUnsigned(32);
        redSpin = (int) is.nextUnsigned(32);
        numFlats = (int) is.nextUnsigned(32);
        halfBuff = (int) is.nextUnsigned(32);
        final String endMarker = marker(is);
        if (!endMarker.equals("RanshiEngine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nRanshiEngine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nRanshiEngine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nRanshiEngine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 0; i < numBuff; ++i) buffer[i] = (int) v[i + 1];
        redSpin = (int) v[numBuff + 1];
        numFlats = (int) v[numBuff + 2];
        halfBuff = (int) v[numBuff + 3];
        return true;
    }
}
