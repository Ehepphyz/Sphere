package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's HepJamesRandom (Random/JamesRandom.h, JamesRandom.cc): the RANMAR generator of Marsaglia,
 * Zaman and Tsang as F. James wrote it, a lagged Fibonacci generator of lags 97 and 33 combined with
 * an arithmetic sequence. Seeds from 0 to 900 000 000.
 */
public final class HepJamesRandom extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 202;
    private static final int maxIndex = 215;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    private final double[] u = new double[97];
    private double c, cd, cm;
    private int i97, j97;

    /** Seeded from CLHEP's seed table, by the number of engines of this kind made so far. */
    public HepJamesRandom() {
        final long[] seeds = new long[2];
        final int numEngines = numberOfEngines.getAndIncrement();
        final int cycle = Math.abs(numEngines / maxIndex);
        final int curIndex = Math.abs(numEngines % maxIndex);
        final long mask = ((long) (cycle & 0x007fffff) << 8);
        HepRandom.getTheTableSeeds(seeds, curIndex);
        final long seed = seeds[0] ^ mask;
        setSeed(seed, 0);
        setSeeds(null, 0, true);
    }

    public HepJamesRandom(long seed) {
        setSeed(seed, 0);
        setSeeds(null, 0, true);
    }

    /** Seeded from a row and column of the seed table. */
    public HepJamesRandom(int rowIndex, int colIndex) {
        final long[] seeds = new long[2];
        final int cycle = Math.abs(rowIndex / maxIndex);
        final int row = Math.abs(rowIndex % maxIndex);
        final int col = Math.abs(colIndex % 2);
        final long mask = ((long) (cycle & 0x000007ff) << 20);
        HepRandom.getTheTableSeeds(seeds, row);
        final long seed = seeds[col] ^ mask;
        setSeed(seed, 0);
        setSeeds(null, 0, true);
    }

    public HepJamesRandom(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public HepJamesRandom(HepJamesRandom o) {
        super(o);
        System.arraycopy(o.u, 0, u, 0, u.length);
        c = o.c;
        cd = o.cd;
        cm = o.cm;
        i97 = o.i97;
        j97 = o.j97;
    }

    public static String engineName() {
        return "HepJamesRandom";
    }

    @Override
    public String name() {
        return "HepJamesRandom";
    }

    @Override
    public void setSeed(long seed, int dum) {
        int m, n;
        float s, t;
        long mm;
        if (seed < 0) {
            StdStreams.cout().print("Seed for HepJamesRandom must be non-negative\nSeed value supplied was " + seed
                + "\nUsing its absolute value instead\n");
            seed = -seed;
        }
        final long ij = seed / 30082;
        final long kl = seed - 30082 * ij;
        long i = (ij / 177) % 177 + 2;
        long j = ij % 177 + 2;
        long k = (kl / 169) % 178 + 1;
        long l = kl % 169;
        theSeed = seed;
        for (n = 1; n < 98; n++) {
            s = 0.0f;
            t = 0.5f;
            for (m = 1; m < 25; m++) {
                mm = (((i * j) % 179) * k) % 179;
                i = j;
                j = k;
                k = mm;
                l = (53 * l + 1) % 169;
                if ((l * mm % 64) >= 32) s += t;
                t *= 0.5;
            }
            u[n - 1] = s;
        }
        c = 362436.0 / 16777216.0;
        cd = 7654321.0 / 16777216.0;
        cm = 16777213.0 / 16777216.0;
        i97 = 96;
        j97 = 32;
    }

    /** setSeed of the first seed (19780503 when there is none). */
    @Override
    public void setSeeds(long[] seeds, int dum) {
        setSeeds(seeds, dum, false);
    }

    /** setSeeds(&amp;theSeed): the seed already set, theSeeds left on it. */
    private void setSeeds(long[] seeds, int dum, boolean own) {
        if (own) {
            setSeed(theSeed, 0);
            theSeeds = null;
            return;
        }
        setSeed(seeds != null && seeds.length > 0 ? seeds[0] : 19780503L, 0);
        theSeeds = seeds;
    }

    @Override
    public double flat() {
        double uni;
        do {
            uni = u[i97] - u[j97];
            if (uni < 0.0) uni++;
            u[i97] = uni;
            if (i97 == 0) i97 = 96;
            else i97--;
            if (j97 == 0) j97 = 96;
            else j97--;
            c -= cd;
            if (c < 0.0) c += cm;
            uni -= c;
            if (uni < 0.0) uni += 1.0;
        } while (uni <= 0.0 || uni >= 1.0);
        return uni;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) {
            vect[i] = flat();
        }
    }

    @Override
    public long toUnsignedInt() {
        return (d2ui(flat() * exponent_bit_32()) & 0xffffffffL) | ((d2ui(u[i97] * exponent_bit_32()) >>> 16) & 0xff);
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
            for (int i = 0; i < 97; ++i) u[i] = inFile.nextDouble();
            c = inFile.nextDouble();
            cd = inFile.nextDouble();
            cm = inFile.nextDouble();
            final int jpos = inFile.nextInt();
            i97 = (64 + jpos) % 97;
            j97 = jpos;
        }
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n----- HepJamesRandom engine status -----\n");
        b.append(" Initial seed = ").append(theSeed).append('\n');
        b.append(" u[] = ");
        for (int i = 0; i < 97; ++i) b.append(g(u[i])).append(' ');
        b.append('\n');
        b.append(" c = ").append(g(c)).append(", cd = ").append(g(cd)).append(", cm = ").append(g(cm)).append('\n');
        b.append(" i97 = ").append(i97).append(", u[i97] = ").append(g(u[i97])).append('\n');
        b.append(" j97 = ").append(j97).append(", u[j97] = ").append(g(u[j97])).append('\n');
        b.append("----------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "JamesRandom-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        int k = 0;
        v[k++] = engineIDulong(engineName());
        for (int i = 0; i < 97; ++i) {
            final long[] t = DoubConv.dto2longs(u[i]);
            v[k++] = t[0];
            v[k++] = t[1];
        }
        for (double x : new double[] {c, cd, cm}) {
            final long[] t = DoubConv.dto2longs(x);
            v[k++] = t[0];
            v[k++] = t[1];
        }
        v[k] = j97 & 0xffffffffL;
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("JamesRandom-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nJamesRandom state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "JamesRandom-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        if (readUvec(is, VECTOR_STATE_SIZE, "\nJamesRandom state (vector) description improper.\ngetState() has failed."
            + "\nInput stream is probably mispositioned now.\n")) {
            return is;
        }
        for (int i = 0; i < 97; ++i) u[i] = is.nextDouble();
        c = is.nextDouble();
        cd = is.nextDouble();
        cm = is.nextDouble();
        final int jpos = is.nextInt();
        final String endMarker = marker(is);
        if (!endMarker.equals("JamesRandom-end")) {
            is.setFail();
            StdStreams.cerr().print("\nJamesRandom state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
            return is;
        }
        i97 = (64 + jpos) % 97;
        j97 = jpos;
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nHepJamesRandom get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nHepJamesRandom get:state vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 0; i < 97; ++i) {
            u[i] = DoubConv.longs2double(v[2 * i + 1], v[2 * i + 2]);
        }
        c = DoubConv.longs2double(v[195], v[196]);
        cd = DoubConv.longs2double(v[197], v[198]);
        cm = DoubConv.longs2double(v[199], v[200]);
        j97 = (int) v[201];
        i97 = (64 + j97) % 97;
        return true;
    }
}
