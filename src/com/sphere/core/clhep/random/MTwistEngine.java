package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's MTwistEngine (Random/MTwistEngine.h, MTwistEngine.cc): the Mersenne Twister MT19937 of
 * Matsumoto and Nishimura, 624 words of 32 bits, each double made of two of them. Seeding warms the
 * engine up by 2000 numbers, as CLHEP does.
 */
public final class MTwistEngine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 626;
    private static final int NminusM = 227, M = 397, N = 624;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    /** The words, unsigned in C++. */
    private final int[] mt = new int[624];
    private int count624;

    public MTwistEngine() {
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        final long[] seedlist = new long[2];
        HepRandom.getTheTableSeeds(seedlist, curIndex);
        seedlist[0] = seedlist[0] ^ mask;
        seedlist[1] = 0;
        setSeeds(seedlist, numEngines);
        warmUp();
    }

    public MTwistEngine(long seed) {
        setSeeds(new long[] {seed, 17587}, 0);
        warmUp();
    }

    public MTwistEngine(int rowIndex, int colIndex) {
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final int col = Math.abs(colIndex % 2);
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        final long[] seedlist = new long[2];
        HepRandom.getTheTableSeeds(seedlist, row);
        seedlist[0] = seedlist[col] ^ mask;
        seedlist[1] = 690691;
        setSeeds(seedlist, 4444772);
        warmUp();
    }

    public MTwistEngine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public MTwistEngine(MTwistEngine o) {
        super(o);
        System.arraycopy(o.mt, 0, mt, 0, mt.length);
        count624 = o.count624;
    }

    private void warmUp() {
        count624 = 0;
        for (int i = 0; i < 2000; ++i) flat();
    }

    public static String engineName() {
        return "MTwistEngine";
    }

    @Override
    public String name() {
        return "MTwistEngine";
    }

    /** The 624 words renewed. */
    private void twist() {
        int y;
        int i;
        for (i = 0; i < NminusM; ++i) {
            y = (mt[i] & 0x80000000) | (mt[i + 1] & 0x7fffffff);
            mt[i] = mt[i + M] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? 0x9908b0df : 0x0);
        }
        for (; i < N - 1; ++i) {
            y = (mt[i] & 0x80000000) | (mt[i + 1] & 0x7fffffff);
            mt[i] = mt[i - NminusM] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? 0x9908b0df : 0x0);
        }
        y = (mt[i] & 0x80000000) | (mt[0] & 0x7fffffff);
        mt[i] = mt[M - 1] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? 0x9908b0df : 0x0);
        count624 = 0;
    }

    private static int temper(int y) {
        y ^= (y >>> 11);
        y ^= ((y << 7) & 0x9d2c5680);
        y ^= ((y << 15) & 0xefc60000);
        y ^= (y >>> 18);
        return y;
    }

    private static double u32(int x) {
        return (double) (x & 0xffffffffL);
    }

    @Override
    public double flat() {
        if (count624 >= N) twist();
        final int y = temper(mt[count624]);
        return u32(y) * twoToMinus_32() + u32(mt[count624++] >>> 11) * twoToMinus_53() + nearlyTwoToMinus_54();
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = flat();
    }

    /** operator float: one word only. */
    @Override
    public float toFloat() {
        if (count624 >= N) twist();
        final int y = temper(mt[count624++]);
        return (float) (u32(y) * twoToMinus_32());
    }

    /** operator unsigned int: one tempered word. */
    @Override
    public long toUnsignedInt() {
        if (count624 >= N) twist();
        return temper(mt[count624++]) & 0xffffffffL;
    }

    @Override
    public void setSeed(long seed, int k) {
        theSeed = seed != 0 ? seed : 4357;
        final int N1 = 624;
        mt[0] = (int) (theSeed & 0xffffffffL);
        for (int mti = 1; mti < N1; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
        for (int i = 1; i < 624; ++i) {
            mt[i] ^= k;
        }
    }

    /** setSeed of the first seed (43571346 when it is 0), the second added to every word but the first. */
    @Override
    public void setSeeds(long[] seeds, int k) {
        setSeed(seeds[0] != 0 ? seeds[0] : 43571346, k);
        for (int i = 1; i < 624; ++i) {
            mt[i] = (int) ((seeds[1] + (mt[i] & 0xffffffffL)) & 0xffffffffL);
        }
        theSeeds = seeds;
    }

    @Override
    public void saveStatus(String filename) {
        final StringBuilder b = new StringBuilder();
        b.append(theSeed).append('\n');
        for (int i = 0; i < 624; ++i) b.append(mt[i] & 0xffffffffL).append(' ');
        b.append('\n').append(count624).append('\n');
        writeStatus(filename, b.toString());
    }

    @Override
    public void restoreStatus(String filename) {
        final CIStream inFile = openStatus(filename);
        if (!checkFile(inFile, filename, engineName(), "restoreStatus")) {
            StdStreams.cerr().print("  -- Engine state remains unchanged\n");
            return;
        }
        if (!inFile.fail() && !inFile.eof()) {
            theSeed = inFile.nextLong();
            for (int i = 0; i < 624; ++i) mt[i] = (int) inFile.nextUnsigned(32);
            count624 = inFile.nextInt();
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n--------- MTwist engine status ---------\n");
        b.append(" Initial seed      = ").append(theSeed).append('\n');
        b.append(" Current index     = ").append(count624).append('\n');
        b.append(" Array status mt[] = \n");
        for (int i = 0; i < 620; i += 5) {
            b.append(mt[i] & 0xffffffffL).append(' ').append(mt[i + 1] & 0xffffffffL).append(' ')
                .append(mt[i + 2] & 0xffffffffL).append(' ').append(mt[i + 3] & 0xffffffffL).append(' ')
                .append(mt[i + 4] & 0xffffffffL).append('\n');
        }
        b.append(mt[620] & 0xffffffffL).append(' ').append(mt[621] & 0xffffffffL).append(' ')
            .append(mt[622] & 0xffffffffL).append(' ').append(mt[623] & 0xffffffffL).append('\n');
        b.append("----------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        os.put(" ").put("MTwistEngine-begin").put(" ").put(theSeed).put(" ");
        for (int i = 0; i < 624; ++i) {
            os.put(mt[i] & 0xffffffffL).put("\n");
        }
        os.put(count624).put(" ").put("MTwistEngine-end").put("\n");
        return os;
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        v[0] = engineIDulong(engineName());
        for (int i = 0; i < 624; ++i) v[i + 1] = mt[i] & 0xffffffffL;
        v[625] = count624 & 0xffffffffL;
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("MTwistEngine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nMTwistEngine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "MTwistEngine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        theSeed = is.nextLong();
        for (int i = 0; i < 624; ++i) mt[i] = (int) is.nextUnsigned(32);
        count624 = is.nextInt();
        final String endMarker = marker(is);
        if (!endMarker.equals("MTwistEngine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nMTwistEngine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nMTwistEngine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nMTwistEngine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 0; i < 624; ++i) mt[i] = (int) v[i + 1];
        count624 = (int) v[625];
        return true;
    }
}
