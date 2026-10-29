package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's Ranlux64Engine (Random/Ranlux64Engine.h, Ranlux64Engine.cc): Lüscher's RANLUX on 48-bit
 * doubles (Hamilton and James's RANLXD), twelve numbers at a time, discarding 109, 202 or 397 of
 * every chunk by luxury level 0, 1 (default) or 2.
 *
 * <p>Seeds wider than 32 bits fold their top bits into the table as on 64-bit Linux.
 */
public final class Ranlux64Engine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 30;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();
    private static final int ecuyer_a = 53668;
    private static final int ecuyer_b = 40014;
    private static final int ecuyer_c = 12211;
    private static final int ecuyer_d = 2147483563;
    private static final int[] lux_levels = {109, 202, 397};

    private int pDiscard;
    private int pDozens;
    private int endIters;
    private int luxury;
    private int index;
    private double carry;
    private final double[] randoms = new double[12];

    public Ranlux64Engine() {
        luxury = 1;
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        final long[] seedlist = new long[2];
        HepRandom.getTheTableSeeds(seedlist, curIndex);
        seedlist[0] ^= mask;
        seedlist[1] = 0;
        setSeeds(seedlist, luxury);
        advance(8);
    }

    public Ranlux64Engine(long seed) {
        this(seed, 1);
    }

    public Ranlux64Engine(long seed, int lux) {
        luxury = lux;
        final long[] seedlist = {seed, 0};
        setSeeds(seedlist, lux);
        advance(2 * lux + 1);
    }

    public Ranlux64Engine(int rowIndex, int colIndex, int lux) {
        luxury = lux;
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        final long[] seedlist = new long[2];
        HepRandom.getTheTableSeeds(seedlist, row);
        seedlist[0] ^= mask;
        seedlist[1] = 0;
        setSeeds(seedlist, lux);
    }

    public Ranlux64Engine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public Ranlux64Engine(Ranlux64Engine o) {
        super(o);
        pDiscard = o.pDiscard;
        pDozens = o.pDozens;
        endIters = o.endIters;
        luxury = o.luxury;
        index = o.index;
        carry = o.carry;
        System.arraycopy(o.randoms, 0, randoms, 0, randoms.length);
    }

    public static String engineName() {
        return "Ranlux64Engine";
    }

    @Override
    public String name() {
        return "Ranlux64Engine";
    }

    public int getLuxury() {
        return luxury;
    }

    @Override
    public double flat() {
        if (index <= 0) update();
        return randoms[--index] + twoToMinus_49();
    }

    private void update() {
        advance(pDozens);
        double y1;
        if (endIters == 1) {
            y1 = randoms[4] - randoms[11] - carry;
            if (y1 < 0.0) {
                y1 += 1.0;
                carry = twoToMinus_48();
            } else {
                carry = 0.0;
            }
            System.arraycopy(randoms, 0, randoms, 1, 11);
            randoms[0] = y1;
        } else {
            int m, nr, ns;
            for (m = 0, nr = 11, ns = 4; m < endIters; ++m, --nr) {
                y1 = randoms[ns] - randoms[nr] - carry;
                if (y1 < 0.0) {
                    y1 += 1.0;
                    carry = twoToMinus_48();
                } else {
                    carry = 0.0;
                }
                randoms[nr] = y1;
                --ns;
                if (ns < 0) ns = 11;
            }
            final double[] temp = randoms.clone();
            ns = 11 - endIters;
            for (m = 11; m >= 0; --m) {
                randoms[m] = temp[ns];
                --ns;
                if (ns < 0) ns = 11;
            }
        }
        index = 12;
    }

    /** Twelve steps at a time of the subtract-with-borrow recursion, as F. James unrolled them. */
    private void advance(int dozens) {
        double y1, y2, y3;
        final double cValue = twoToMinus_48();
        final double zero = 0.0;
        final double one = 1.0;
        final double[] r = randoms;
        for (int k = dozens; k > 0; --k) {
            y1 = r[4] - r[11] - carry;
            y2 = r[3] - r[10];
            if (y1 < zero) {
                y1 += one;
                y2 -= cValue;
            }
            r[11] = y1;
            y3 = r[2] - r[9];
            if (y2 < zero) {
                y2 += one;
                y3 -= cValue;
            }
            r[10] = y2;
            y1 = r[1] - r[8];
            if (y3 < zero) {
                y3 += one;
                y1 -= cValue;
            }
            r[9] = y3;
            y2 = r[0] - r[7];
            if (y1 < zero) {
                y1 += one;
                y2 -= cValue;
            }
            r[8] = y1;
            y3 = r[11] - r[6];
            if (y2 < zero) {
                y2 += one;
                y3 -= cValue;
            }
            r[7] = y2;
            y1 = r[10] - r[5];
            if (y3 < zero) {
                y3 += one;
                y1 -= cValue;
            }
            r[6] = y3;
            y2 = r[9] - r[4];
            if (y1 < zero) {
                y1 += one;
                y2 -= cValue;
            }
            r[5] = y1;
            y3 = r[8] - r[3];
            if (y2 < zero) {
                y2 += one;
                y3 -= cValue;
            }
            r[4] = y2;
            y1 = r[7] - r[2];
            if (y3 < zero) {
                y3 += one;
                y1 -= cValue;
            }
            r[3] = y3;
            y2 = r[6] - r[1];
            if (y1 < zero) {
                y1 += one;
                y2 -= cValue;
            }
            r[2] = y1;
            y3 = r[5] - r[0];
            if (y2 < zero) {
                y2 += one;
                y3 -= cValue;
            }
            r[1] = y2;
            if (y3 < zero) {
                y3 += one;
                carry = cValue;
            }
            r[0] = y3;
        }
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = flat();
    }

    private void discardLevel(int lux) {
        if ((lux > 2) || (lux < 0)) {
            pDiscard = (lux >= 12) ? (lux - 12) : lux_levels[1];
        } else {
            pDiscard = lux_levels[luxury];   // the luxury held, as CLHEP reads it
        }
        pDozens = pDiscard / 12;
        endIters = pDiscard % 12;
    }

    private static long nextSeed(long next_seed) {
        final long k_multiple = next_seed / ecuyer_a;
        next_seed = ecuyer_b * (next_seed - k_multiple * ecuyer_a) - k_multiple * ecuyer_c;
        if (next_seed < 0) next_seed += ecuyer_d;
        return next_seed & 0xffffffffL;
    }

    private void fill(long[] init_table) {
        for (int i = 0; i < 12; i++) {
            randoms[i] = (init_table[2 * i]) * 2.0 * twoToMinus_32() + (init_table[2 * i + 1] >> 15) * twoToMinus_48();
        }
        carry = 0.0;
        if (randoms[11] == 0.) carry = twoToMinus_48();
        index = -1;
    }

    @Override
    public void setSeed(long seed, int lux) {
        theSeed = seed;
        discardLevel(lux);
        final long[] init_table = new long[24];
        long next_seed = seed;
        next_seed &= 0xffffffffL;
        while (next_seed >= ecuyer_d) next_seed -= ecuyer_d;
        for (int i = 0; i != 24; i++) {
            next_seed = nextSeed(next_seed);
            init_table[i] = next_seed;
        }
        // long has 64 bits: the seed's top bits are folded in
        final long topbits1 = (seed >>> 32) & 0xffff;
        final long topbits2 = (seed >>> 48) & 0xffff;
        init_table[0] ^= topbits1;
        init_table[2] ^= topbits2;
        fill(init_table);
    }

    @Override
    public void setSeeds(long[] seeds, int lux) {
        theSeeds = seeds;
        if (seeds == null) {
            setSeed(theSeed, lux);
            theSeeds = null;
            return;
        }
        theSeed = seeds[0];
        discardLevel(lux);
        final long[] init_table = new long[24];
        int i;
        for (i = 0; (i != 24) && i < seeds.length && (seeds[i] != 0); i++) {
            init_table[i] = seeds[i] & 0xffffffffL;
        }
        if (i != 24) {
            long next_seed = init_table[i - 1];
            for (; i != 24; i++) {
                next_seed = nextSeed(next_seed);
                init_table[i] = next_seed;
            }
        }
        fill(init_table);
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
        if (readUvec(inFile, VECTOR_STATE_SIZE, "\nRanlux64Engine state (vector) description improper."
            + "\nrestoreStatus has failed.\nInput stream is probably mispositioned now.\n")) {
            return;
        }
        if (!inFile.fail() && !inFile.eof()) {
            for (int i = 0; i < 12; ++i) randoms[i] = inFile.nextDouble();
            carry = inFile.nextDouble();
            index = inFile.nextInt();
            luxury = inFile.nextInt();
            pDiscard = inFile.nextInt();
            pDozens = pDiscard / 12;
            endIters = pDiscard % 12;
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n--------- Ranlux engine status ---------\n");
        b.append(" Initial seed = ").append(theSeed).append('\n');
        b.append(" randoms[] = ");
        for (int i = 0; i < 12; ++i) b.append(g(randoms[i])).append('\n');
        b.append(" carry = ").append(g(carry)).append(", index = ").append(index).append('\n');
        b.append(" luxury = ").append(luxury).append(" pDiscard = ").append(pDiscard).append('\n');
        b.append("----------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "Ranlux64Engine-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        int k = 0;
        v[k++] = engineIDulong(engineName());
        for (int i = 0; i < 12; ++i) {
            final long[] t = DoubConv.dto2longs(randoms[i]);
            v[k++] = t[0];
            v[k++] = t[1];
        }
        final long[] t = DoubConv.dto2longs(carry);
        v[k++] = t[0];
        v[k++] = t[1];
        v[k++] = index & 0xffffffffL;
        v[k++] = luxury & 0xffffffffL;
        v[k] = pDiscard & 0xffffffffL;
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("Ranlux64Engine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nRanlux64Engine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "Ranlux64Engine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nRanlux64Engine state (vector) description improper."
            + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        for (int i = 0; i < 12; ++i) randoms[i] = is.nextDouble();
        carry = is.nextDouble();
        index = is.nextInt();
        luxury = is.nextInt();
        pDiscard = is.nextInt();
        pDozens = pDiscard / 12;
        endIters = pDiscard % 12;
        final String endMarker = marker(is);
        if (!endMarker.equals("Ranlux64Engine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nRanlux64Engine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nRanlux64Engine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nRanlux64Engine get:state vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 0; i < 12; ++i) {
            randoms[i] = DoubConv.longs2double(v[2 * i + 1], v[2 * i + 2]);
        }
        carry = DoubConv.longs2double(v[25], v[26]);
        index = (int) v[27];
        luxury = (int) v[28];
        pDiscard = (int) v[29];
        return true;
    }
}
