package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's DualRand (Random/DualRand.h, DualRand.cc): a Tausworthe shift register generator xor'ed with
 * an integer congruential one, both of 32 bits. As in C++, converting the Tausworthe generator to an
 * integer draws from it, which the constructors do once when they seed the congruential one.
 */
public final class DualRand extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 9;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    /** The shift register: four words, used from the last. */
    static final class Tausworthe {
        int wordIndex;
        final int[] words = new int[4];

        Tausworthe() {
            this(1234567);
        }

        Tausworthe(int seed) {
            words[0] = seed;
            for (wordIndex = 1; wordIndex < 4; ++wordIndex) {
                words[wordIndex] = 69607 * words[wordIndex - 1] + 54329;
            }
        }

        void assign(Tausworthe o) {
            wordIndex = o.wordIndex;
            System.arraycopy(o.words, 0, words, 0, 4);
        }

        /** operator unsigned int: the next word, the four renewed when all are used. */
        int next() {
            if (wordIndex <= 0) {
                for (wordIndex = 0; wordIndex < 4; ++wordIndex) {
                    words[wordIndex] = ((words[(wordIndex + 1) & 3] << 1) | (words[wordIndex] >>> 31))
                        ^ ((words[(wordIndex + 1) & 3] << 31) | (words[wordIndex] >>> 1));
                }
            }
            return words[--wordIndex];
        }

        void put(StringBuilder os) {
            os.append(" Tausworthe-begin ");
            for (int i = 0; i < 4; ++i) {
                os.append(words[i] & 0xffffffffL).append(' ');
            }
            os.append(wordIndex).append(" Tausworthe-end \n");
        }

        void put(long[] v, int at) {
            for (int i = 0; i < 4; ++i) v[at + i] = words[i] & 0xffffffffL;
            v[at + 4] = wordIndex & 0xffffffffL;
        }

        void get(CIStream is) {
            if (!marker(is).equals("Tausworthe-begin")) {
                is.setFail();
                StdStreams.cerr().print("\nInput mispositioned or\nTausworthe state description missing or"
                    + "\nwrong engine type found.\n");
            }
            for (int i = 0; i < 4; ++i) words[i] = (int) is.nextUnsigned(32);
            wordIndex = is.nextInt();
            if (!marker(is).equals("Tausworthe-end")) {
                is.setFail();
                StdStreams.cerr().print("\nTausworthe state description incomplete."
                    + "\nInput stream is probably mispositioned now.\n");
            }
        }

        int get(long[] v, int at) {
            for (int i = 0; i < 4; ++i) words[i] = (int) v[at++];
            wordIndex = (int) v[at++];
            return at;
        }
    }

    /** The congruential generator: state = state * multiplier + addend, modulo 2^32. */
    static final class IntegerCong {
        int state, multiplier, addend;

        IntegerCong() {
            state = (int) 3758656018L;
            multiplier = 66565;
            addend = 12341;
        }

        IntegerCong(int seed, int streamNumber) {
            state = seed;
            multiplier = 65536 + 1024 + 5 + (8 * 1017 * streamNumber);
            addend = 12341;
        }

        void assign(IntegerCong o) {
            state = o.state;
            multiplier = o.multiplier;
            addend = o.addend;
        }

        int next() {
            return state = state * multiplier + addend;
        }

        void put(StringBuilder os) {
            os.append(" IntegerCong-begin ").append(state & 0xffffffffL).append(' ').append(multiplier & 0xffffffffL)
                .append(' ').append(addend & 0xffffffffL).append(" IntegerCong-end \n");
        }

        void put(long[] v, int at) {
            v[at] = state & 0xffffffffL;
            v[at + 1] = multiplier & 0xffffffffL;
            v[at + 2] = addend & 0xffffffffL;
        }

        void get(CIStream is) {
            if (!marker(is).equals("IntegerCong-begin")) {
                is.setFail();
                StdStreams.cerr().print("\nInput mispositioned or\nIntegerCong state description missing or"
                    + "\nwrong engine type found.\n");
            }
            state = (int) is.nextUnsigned(32);
            multiplier = (int) is.nextUnsigned(32);
            addend = (int) is.nextUnsigned(32);
            if (!marker(is).equals("IntegerCong-end")) {
                is.setFail();
                StdStreams.cerr().print("\nIntegerCong state description incomplete."
                    + "\nInput stream is probably mispositioned now.\n");
            }
        }

        int get(long[] v, int at) {
            state = (int) v[at++];
            multiplier = (int) v[at++];
            addend = (int) v[at++];
            return at;
        }
    }

    private final int numEngines;
    private final Tausworthe tausworthe;
    private final IntegerCong integerCong;

    public DualRand() {
        numEngines = numberOfEngines.getAndIncrement();
        tausworthe = new Tausworthe(1234567 + numEngines + 175321);
        integerCong = new IntegerCong(69607 * tausworthe.next() + 54329, numEngines);
        theSeed = 1234567;
    }

    public DualRand(long seed) {
        numEngines = 0;
        tausworthe = new Tausworthe((int) seed + 175321);
        integerCong = new IntegerCong(69607 * tausworthe.next() + 54329, 8043);
        theSeed = seed;
    }

    public DualRand(int rowIndex, int colIndex) {
        numEngines = 0;
        tausworthe = new Tausworthe(rowIndex + 1000 * colIndex + 85329);
        integerCong = new IntegerCong(69607 * tausworthe.next() + 54329, 1123);
        theSeed = rowIndex;
    }

    public DualRand(CIStream is) {
        numEngines = 0;
        tausworthe = new Tausworthe();
        integerCong = new IntegerCong();
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public DualRand(DualRand o) {
        super(o);
        numEngines = o.numEngines;
        tausworthe = new Tausworthe();
        tausworthe.assign(o.tausworthe);
        integerCong = new IntegerCong();
        integerCong.assign(o.integerCong);
    }

    public static String engineName() {
        return "DualRand";
    }

    @Override
    public String name() {
        return "DualRand";
    }

    @Override
    public double flat() {
        final int ic = integerCong.next();
        final int t = tausworthe.next();
        return ((t ^ ic) & 0xffffffffL) * twoToMinus_32() + ((t >>> 11) & 0xffffffffL) * twoToMinus_53()
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
        return (float) (((ic ^ t) & 0xffffffffL) * twoToMinus_32() + nearlyTwoToMinus_54());
    }

    @Override
    public long toUnsignedInt() {
        final int ic = integerCong.next();
        final int t = tausworthe.next();
        return (ic ^ t) & 0xffffffffL;
    }

    @Override
    public void setSeed(long seed, int extra) {
        theSeed = seed;
        tausworthe.assign(new Tausworthe((int) seed + 175321));
        integerCong.assign(new IntegerCong(69607 * tausworthe.next() + 54329, 8043));
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nDualRand state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail()) {
            tausworthe.get(inFile);
            integerCong.get(inFile);
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n-------- DualRand engine status ---------\n");
        b.append("Initial seed          = ").append(theSeed).append('\n');
        b.append("Tausworthe generator  = \n");
        tausworthe.put(b);
        b.append("\nIntegerCong generator = \n");
        integerCong.put(b);
        b.append("\n-----------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "DualRand-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        v[0] = engineIDulong(engineName());
        tausworthe.put(v, 1);
        integerCong.put(v, 6);
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("DualRand-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput mispositioned or\nDualRand state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "DualRand-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nDualRand state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        tausworthe.get(is);
        integerCong.get(is);
        final String endMarker = marker(is);
        if (!endMarker.equals("DualRand-end")) {
            is.setFail();
            StdStreams.cerr().print("DualRand state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nDualRand get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nDualRand get:state vector has wrong size: " + v.length + " - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length < 9) {
            StdStreams.cerr().print("\nDualRand get:state vector has wrong size: " + v.length + "\n");
            return false;
        }
        int at = tausworthe.get(v, 1);
        at = integerCong.get(v, at);
        if (at != v.length) {
            StdStreams.cerr().print("\nDualRand get:state vector has wrong size: " + v.length
                + "\n         Apparently " + at + " words were consumed\n");
            return false;
        }
        return true;
    }
}
