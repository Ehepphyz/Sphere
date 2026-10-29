package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's Hurd160Engine (Random/Hurd160Engine.h, Hurd160Engine.cc): J. R. Hurd's 160-bit shift register generator: five words of 32 bits, each double made of two
 * of them.
 */
public final class Hurd160Engine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 7;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    private final long[] seeds_ = new long[2];
    private int wordIndex;
    /** Unsigned in C++. */
    private final int[] words = new int[5];

    public Hurd160Engine() {
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        HepRandom.getTheTableSeeds(seeds_, curIndex);
        seeds_[0] ^= mask;
        setSeeds(seeds_, 0);
        words[0] ^= 0x1324abcd;
        if (words[0] == 0) words[0] = 1;
        for (int i = 0; i < 100; ++i) flat();
    }

    public Hurd160Engine(long seed) {
        seeds_[0] = seed;
        setSeeds(seeds_, 0);
        words[0] ^= 0xa5482134;
        if (words[0] == 0) words[0] = 1;
        for (int i = 0; i < 100; ++i) flat();
    }

    public Hurd160Engine(int rowIndex, int colIndex) {
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final int col = colIndex & 0x1;
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        HepRandom.getTheTableSeeds(seeds_, row);
        seeds_[0] = seeds_[col] ^ mask;
        setSeeds(seeds_, 0);
        for (int i = 0; i < 100; ++i) flat();
    }

    public Hurd160Engine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public Hurd160Engine(Hurd160Engine o) {
        super(o);
        System.arraycopy(o.seeds_, 0, seeds_, 0, seeds_.length);
        wordIndex = o.wordIndex;
        System.arraycopy(o.words, 0, words, 0, words.length);
    }

    public static String engineName() {
        return "Hurd160Engine";
    }

    @Override
    public String name() {
        return "Hurd160Engine";
    }

    private static int f(int a, int b, int c) {
        return (((b << 2) & 0x7c) | ((a << 2) & ~0x7c) | (a >>> 30)) ^ ((c << 1) | (c >>> 31));
    }

    private void advance() {
        int W0 = words[0];
        int W1 = words[1];
        int W2 = words[2];
        int W3 = words[3];
        int W4 = words[4];
        W1 ^= W0;
        W0 = f(W4, W3, W0);
        W2 ^= W1;
        W1 = f(W0, W4, W1);
        W3 ^= W2;
        W2 = f(W1, W0, W2);
        W4 ^= W3;
        W3 = f(W2, W1, W3);
        W0 ^= W4;
        W4 = f(W3, W2, W4);
        words[0] = W0;
        words[1] = W1;
        words[2] = W2;
        words[3] = W3;
        words[4] = W4;
        wordIndex = 5;
    }

    private static double u32(int x) {
        return (double) (x & 0xffffffffL);
    }

    @Override
    public double flat() {
        if (wordIndex <= 2) advance();
        final double x = u32(words[--wordIndex]) * twoToMinus_32();
        final double y = u32(words[--wordIndex] >>> 11) * twoToMinus_53() + nearlyTwoToMinus_54();
        return x + y;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) {
            vect[i] = flat();
        }
    }

    @Override
    public float toFloat() {
        if (wordIndex <= 1) advance();
        return (float) (u32(words[--wordIndex]) * twoToMinus_32());
    }

    @Override
    public long toUnsignedInt() {
        if (wordIndex <= 1) advance();
        return words[--wordIndex] & 0xffffffffL;
    }

    @Override
    public void setSeed(long seed, int extra) {
        seeds_[0] = seed;
        seeds_[1] = 0;
        theSeed = seeds_[0];
        words[0] = (int) seed;
        for (wordIndex = 1; wordIndex < 5; ++wordIndex) {
            words[wordIndex] = 69607 * words[wordIndex - 1] + 54329;
        }
    }

    /** setSeed of the first seed (32767 when it is 0). */
    @Override
    public void setSeeds(long[] seeds, int extra) {
        theSeeds = seeds;
        setSeed(seeds[0] != 0 ? seeds[0] : 32767, 0);
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nHurd160Engine state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail()) {
            wordIndex = inFile.nextInt();
            for (int i = 0; i < 5; ++i) words[i] = (int) inFile.nextUnsigned(32);
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n----------- Hurd engine status ----------\n");
        b.append("Initial seed  = ").append(theSeed).append('\n');
        b.append("Current index = ").append(wordIndex).append('\n');
        b.append("Current words = \n");
        for (int i = 0; i < 5; ++i) b.append("    ").append(words[i] & 0xffffffffL).append('\n');
        b.append("------------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "Hurd160Engine-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        v[0] = engineIDulong(engineName());
        v[1] = wordIndex & 0xffffffffL;
        for (int i = 0; i < 5; ++i) v[i + 2] = words[i] & 0xffffffffL;
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("Hurd160Engine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput mispositioned or\nHurd160Engine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "Hurd160Engine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nHurd160Engine state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        wordIndex = is.nextInt();
        for (int i = 0; i < 5; ++i) words[i] = (int) is.nextUnsigned(32);
        final String endMarker = marker(is);
        if (!endMarker.equals("Hurd160Engine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nHurd160Engine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nHurd160Engine get:state vector has wrong ID word - state unchanged\n"
                + "The correct ID would be " + engineIDulong(engineName()) + "; the actual ID is "
                + Long.toUnsignedString(v[0]) + "\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nHurd160Engine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        wordIndex = (int) v[1];
        for (int i = 0; i < 5; ++i) words[i] = (int) v[i + 2];
        return true;
    }
}
