package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's RanluxEngine (Random/RanluxEngine.h, RanluxEngine.cc): Lüscher's RANLUX, F. James's version,
 * a subtract-with-borrow generator on 24-bit floats, of which numbers are thrown away after each 24
 * according to the luxury level (0 to 4; 3 by default).
 */
public final class RanluxEngine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 31;
    private static final int int_modulus = 0x1000000;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();
    private static final int ecuyer_a = 53668;
    private static final int ecuyer_b = 40014;
    private static final int ecuyer_c = 12211;
    private static final int ecuyer_d = 2147483563;
    private static final int[] lux_levels = {0, 24, 73, 199, 365};

    private int nskip, luxury;
    private final float[] float_seed_table = new float[24];
    private int i_lag, j_lag;
    private float carry;
    private int count24;

    public RanluxEngine(long seed) {
        this(seed, 3);
    }

    public RanluxEngine(long seed, int lux) {
        final long[] seedlist = {0, 0};
        luxury = lux;
        setSeed(seed, luxury);
        seedlist[0] = theSeed;
        seedlist[1] = 0;
        setSeeds(seedlist, luxury);
    }

    public RanluxEngine() {
        final long[] seedlist = {0, 0};
        luxury = 3;
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        HepRandom.getTheTableSeeds(seedlist, curIndex);
        final long seed = seedlist[0] ^ mask;
        setSeed(seed, luxury);
        seedlist[0] = theSeed;
        seedlist[1] = 0;
        setSeeds(seedlist, luxury);
    }

    public RanluxEngine(int rowIndex, int colIndex, int lux) {
        final long[] seedlist = {0, 0};
        luxury = lux;
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final int col = Math.abs(colIndex % 2);
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        HepRandom.getTheTableSeeds(seedlist, row);
        final long seed = seedlist[col] ^ mask;
        setSeed(seed, luxury);
        seedlist[0] = theSeed;
        seedlist[1] = 0;
        setSeeds(seedlist, luxury);
    }

    public RanluxEngine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public RanluxEngine(RanluxEngine o) {
        super(o);
        nskip = o.nskip;
        luxury = o.luxury;
        System.arraycopy(o.float_seed_table, 0, float_seed_table, 0, float_seed_table.length);
        i_lag = o.i_lag;
        j_lag = o.j_lag;
        carry = o.carry;
        count24 = o.count24;
    }

    public static String engineName() {
        return "RanluxEngine";
    }

    @Override
    public String name() {
        return "RanluxEngine";
    }

    public int getLuxury() {
        return luxury;
    }

    private void luxuryLevel(int lux) {
        if ((lux > 4) || (lux < 0)) {
            nskip = lux >= 24 ? lux - 24 : lux_levels[3];
        } else {
            luxury = lux;
            nskip = lux_levels[luxury];
        }
    }

    private static long nextSeed(long nextSeed) {
        final long k_multiple = nextSeed / ecuyer_a;
        nextSeed = ecuyer_b * (nextSeed - k_multiple * ecuyer_a) - k_multiple * ecuyer_c;
        if (nextSeed < 0) nextSeed += ecuyer_d;
        return nextSeed;
    }

    private void fill(long[] int_seed_table) {
        for (int i = 0; i != 24; i++) {
            float_seed_table[i] = (float) (int_seed_table[i] * mantissa_bit_24());
        }
        i_lag = 23;
        j_lag = 9;
        carry = 0.0f;
        if (float_seed_table[23] == 0.) carry = (float) mantissa_bit_24();
        count24 = 0;
    }

    @Override
    public void setSeed(long seed, int lux) {
        final long[] int_seed_table = new long[24];
        long next_seed = seed;
        theSeed = seed;
        luxuryLevel(lux);
        for (int i = 0; i != 24; i++) {
            next_seed = nextSeed(next_seed);
            int_seed_table[i] = next_seed % int_modulus;
        }
        fill(int_seed_table);
    }

    /** The seeds up to the first zero (24 at most), the table completed by the congruential sequence. */
    @Override
    public void setSeeds(long[] seeds, int lux) {
        theSeeds = seeds;
        if (seeds == null) {
            setSeed(theSeed, lux);
            theSeeds = null;
            return;
        }
        theSeed = seeds[0];
        luxuryLevel(lux);
        final long[] int_seed_table = new long[24];
        int i;
        for (i = 0; (i != 24) && i < seeds.length && (seeds[i] != 0); i++) {
            int_seed_table[i] = seeds[i] % int_modulus;
        }
        if (i != 24) {
            long next_seed = int_seed_table[i - 1];
            for (; i != 24; i++) {
                next_seed = nextSeed(next_seed);
                int_seed_table[i] = next_seed % int_modulus;
            }
        }
        fill(int_seed_table);
    }

    /** One step of the subtract-with-borrow recursion. */
    private float step() {
        float uni = float_seed_table[j_lag] - float_seed_table[i_lag] - carry;
        if (uni < 0.) {
            uni += 1.0;
            carry = (float) mantissa_bit_24();
        } else {
            carry = 0.0f;
        }
        float_seed_table[i_lag] = uni;
        i_lag--;
        j_lag--;
        if (i_lag < 0) i_lag = 23;
        if (j_lag < 0) j_lag = 23;
        return uni;
    }

    @Override
    public double flat() {
        float uni = step();
        if (uni < mantissa_bit_12()) {
            uni += mantissa_bit_24() * float_seed_table[j_lag];
            if (uni == 0) uni = (float) (mantissa_bit_24() * mantissa_bit_24());
        }
        final float next_random = uni;
        count24++;
        if (count24 == 24) {
            count24 = 0;
            for (int i = 0; i != nskip; i++) step();
        }
        return next_random;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int index = 0; index < size; ++index) {
            vect[index] = flat();
        }
    }

    @Override
    public long toUnsignedInt() {
        return (d2ui(flat() * exponent_bit_32()) & 0xffffffffL)
            | ((d2ui(float_seed_table[i_lag] * exponent_bit_32()) >>> 16) & 0xff);
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nRanluxEngine state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail() && !inFile.eof()) {
            for (int i = 0; i < 24; ++i) float_seed_table[i] = inFile.nextFloat();
            i_lag = inFile.nextInt();
            j_lag = inFile.nextInt();
            carry = inFile.nextFloat();
            count24 = inFile.nextInt();
            luxury = inFile.nextInt();
            nskip = inFile.nextInt();
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n--------- Ranlux engine status ---------\n");
        b.append(" Initial seed = ").append(theSeed).append('\n');
        b.append(" float_seed_table[] = ");
        for (int i = 0; i < 24; ++i) b.append(g(float_seed_table[i])).append(' ');
        b.append('\n');
        b.append(" i_lag = ").append(i_lag).append(", j_lag = ").append(j_lag).append('\n');
        b.append(" carry = ").append(g(carry)).append(", count24 = ").append(count24).append('\n');
        b.append(" luxury = ").append(luxury).append(" nskip = ").append(nskip).append('\n');
        b.append("----------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "RanluxEngine-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        v[0] = engineIDulong(engineName());
        for (int i = 0; i < 24; ++i) {
            v[i + 1] = (long) (float_seed_table[i] / mantissa_bit_24());
        }
        v[25] = i_lag & 0xffffffffL;
        v[26] = j_lag & 0xffffffffL;
        v[27] = (long) (carry / mantissa_bit_24());
        v[28] = count24 & 0xffffffffL;
        v[29] = luxury & 0xffffffffL;
        v[30] = nskip & 0xffffffffL;
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("RanluxEngine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nRanluxEngine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "RanluxEngine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nRanluxEngine state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        for (int i = 0; i < 24; ++i) float_seed_table[i] = is.nextFloat();
        i_lag = is.nextInt();
        j_lag = is.nextInt();
        carry = is.nextFloat();
        count24 = is.nextInt();
        luxury = is.nextInt();
        nskip = is.nextInt();
        final String endMarker = marker(is);
        if (!endMarker.equals("RanluxEngine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nRanluxEngine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nRanluxEngine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nRanluxEngine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 0; i < 24; ++i) {
            float_seed_table[i] = (float) (u64ToDouble(v[i + 1]) * mantissa_bit_24());
        }
        i_lag = (int) v[25];
        j_lag = (int) v[26];
        carry = (float) (u64ToDouble(v[27]) * mantissa_bit_24());
        count24 = (int) v[28];
        luxury = (int) v[29];
        nskip = (int) v[30];
        return true;
    }

    /** An unsigned long as a double, as C++ converts it. */
    static double u64ToDouble(long x) {
        if (x >= 0) return (double) x;
        final double d = (double) ((x >>> 1) | (x & 1));
        return d + d;
    }
}
