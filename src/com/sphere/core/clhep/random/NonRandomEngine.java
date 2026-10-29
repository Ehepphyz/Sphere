package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's NonRandomEngine (Random/NonRandomEngine.h, NonRandomEngine.cc): an engine for tests, giving the
 * number set next, a sequence set beforehand, or numbers stepping by a fixed interval.
 */
public final class NonRandomEngine extends HepRandomEngine {

    private boolean nextHasBeenSet = false;
    private boolean sequenceHasBeenSet = false;
    private boolean intervalHasBeenSet = false;
    private double nextRandom = 0.05;
    private final List<Double> sequence = new ArrayList<>();
    private int nInSeq = 0;
    private double randomInterval = 0.1;

    public NonRandomEngine() {
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public NonRandomEngine(NonRandomEngine o) {
        super(o);
        nextHasBeenSet = o.nextHasBeenSet;
        sequenceHasBeenSet = o.sequenceHasBeenSet;
        intervalHasBeenSet = o.intervalHasBeenSet;
        nextRandom = o.nextRandom;
        sequence.addAll(o.sequence);
        nInSeq = o.nInSeq;
        randomInterval = o.randomInterval;
    }

    public static String engineName() {
        return "NonRandomEngine";
    }

    @Override
    public String name() {
        return "NonRandomEngine";
    }

    public void setNextRandom(double r) {
        nextRandom = r;
        nextHasBeenSet = true;
    }

    public void setRandomSequence(double[] s, int n) {
        sequence.clear();
        for (int i = 0; i < n; i++) sequence.add(s[i]);
        nInSeq = 0;
        sequenceHasBeenSet = true;
        nextHasBeenSet = false;
    }

    public void setRandomInterval(double x) {
        randomInterval = x;
        intervalHasBeenSet = true;
    }

    /** The next of the sequence, else the number set; with neither, an error (CLHEP exits). */
    @Override
    public double flat() {
        if (sequenceHasBeenSet) {
            final double v = sequence.get(nInSeq++);
            if (nInSeq >= sequence.size()) sequenceHasBeenSet = false;
            return v;
        }
        if (!nextHasBeenSet) {
            StdStreams.cout().print("Attempt to use NonRandomEngine without setting next random!\n");
            throw new IllegalStateException("Attempt to use NonRandomEngine without setting next random!");
        }
        final double a = nextRandom;
        nextHasBeenSet = false;
        if (intervalHasBeenSet) {
            nextRandom += randomInterval;
            if (nextRandom >= 1) nextRandom -= 1.0;
            nextHasBeenSet = true;
        }
        return a;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = flat();
    }

    @Override
    public void setSeed(long seed, int extra) {
    }

    @Override
    public void setSeeds(long[] seeds, int extra) {
    }

    @Override
    public void saveStatus(String filename) {
    }

    @Override
    public void restoreStatus(String filename) {
    }

    @Override
    public void showStatus() {
    }

    @Override
    public COStream put(COStream os) {
        return putWords(os, "NonRandomEngine-begin");
    }

    @Override
    public long[] put() {
        final long[] v = new long[10 + 2 * sequence.size()];
        int k = 0;
        v[k++] = engineIDulong(engineName());
        v[k++] = nextHasBeenSet ? 1 : 0;
        v[k++] = sequenceHasBeenSet ? 1 : 0;
        v[k++] = intervalHasBeenSet ? 1 : 0;
        long[] t = DoubConv.dto2longs(nextRandom);
        v[k++] = t[0];
        v[k++] = t[1];
        v[k++] = nInSeq & 0xffffffffL;
        t = DoubConv.dto2longs(randomInterval);
        v[k++] = t[0];
        v[k++] = t[1];
        v[k++] = sequence.size();
        for (double x : sequence) {
            t = DoubConv.dto2longs(x);
            v[k++] = t[0];
            v[k++] = t[1];
        }
        return v;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = is.nextWord();
        if (!beginMarker.equals("NonRandomEngine-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput mispositioned or\nNonRandomEngine state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "NonRandomEngine-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        final String first = is.nextWord();
        if (first.equals("Uvec")) {
            final List<Long> v = new ArrayList<>();
            long ssiz = 0;
            for (int istart = 0; istart < 10; ++istart) {
                final long uu = is.nextUnsigned(64);
                if (is.fail()) {
                    is.setFail();
                    StdStreams.cout().print("istart = " + istart + "\n");
                    StdStreams.cerr().print("\nNonRandomEngine state (vector) description has no sequence size."
                        + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n");
                    return is;
                }
                v.add(uu);
                if (istart == 9) ssiz = uu;
            }
            for (long ivec = 0; ivec < 2 * ssiz; ++ivec) {
                final long uu = is.nextUnsigned(64);
                if (is.fail()) {
                    is.setFail();
                    StdStreams.cerr().print("\nNonRandomEngine state (vector) description improper."
                        + "\ngetState() has failed.\nInput stream is probably mispositioned now.\n");
                    return is;
                }
                v.add(uu);
            }
            getState(v.stream().mapToLong(Long::longValue).toArray());
            return is;
        }
        nextHasBeenSet = !first.equals("0") && !first.isEmpty();
        sequenceHasBeenSet = is.nextInt() != 0;
        intervalHasBeenSet = is.nextInt() != 0;
        nextRandom = is.nextDouble();
        nInSeq = is.nextInt();
        randomInterval = is.nextDouble();
        final long seqSize = is.nextUnsigned(32);
        sequence.clear();
        for (long i = 0; i < seqSize; ++i) {
            sequence.add(is.nextDouble());
        }
        final String endMarker = is.nextWord();
        if (!endMarker.equals("NonRandomEngine-end")) {
            is.setFail();
            StdStreams.cerr().print("\nNonRandomEngine state description incomplete."
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] v) {
        if ((v[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nNonRandomEngine get:state vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        final long seqSize = v[9];
        if (v.length != 2 * seqSize + 10) {
            StdStreams.cerr().print("\nNonRandomEngine get:state vector has wrong length - state unchanged\n");
            StdStreams.cerr().print("  (length = " + v.length + "; expected " + (2 * seqSize + 10) + ")\n");
            return false;
        }
        nextHasBeenSet = v[1] != 0;
        sequenceHasBeenSet = v[2] != 0;
        intervalHasBeenSet = v[3] != 0;
        nextRandom = DoubConv.longs2double(v[4], v[5]);
        nInSeq = (int) v[6];
        randomInterval = DoubConv.longs2double(v[7], v[8]);
        sequence.clear();
        for (int i = 0; i < seqSize; ++i) {
            sequence.add(DoubConv.longs2double(v[2 * i + 10], v[2 * i + 11]));
        }
        return true;
    }
}
