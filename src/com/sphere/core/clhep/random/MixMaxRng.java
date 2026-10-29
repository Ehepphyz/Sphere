package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLHEP's MixMaxRng (Random/MixMaxRng.h, MixMaxRng.cc), CLHEP's and Geant4's default engine: the
 * MIXMAX matrix generator of K. Savvidy with N = 17, special entry 0 and multiplier 2^36 + 1, on the
 * integers modulo the Mersenne prime 2^61 - 1. Seeded with up to four 32-bit IDs, each bit of which
 * jumps the stream by a skip matrix, so that streams of different IDs never overlap.
 *
 * <p>The products modulo 2^61 - 1 are made on 128 bits, as the C++ does on x86-64.
 */
public final class MixMaxRng extends HepRandomEngine {

    private static final int N = 17;
    private static final int SPECIALMUL = 36;
    private static final int BITS = 61;
    private static final long M61 = 2305843009213693951L;
    private static final double INV_M61 = 0.43368086899420177360298E-18;
    private static final int VECTOR_STATE_SIZE = 2 * N + 4;
    private static final long MASK32 = 0xffffffffL;

    private static final AtomicInteger numberOfEngines = EngineCounters.counter();

    private final long[] V = new long[N];
    private long sumtot = 0;
    private int counter = N;

    /** Seeded with the number of MixMaxRng made so far, 1 for the first. */
    public MixMaxRng() {
        final int numEngines = numberOfEngines.incrementAndGet();
        setSeed(numEngines, 0);
    }

    public MixMaxRng(long seed) {
        theSeed = seed;
        setSeed(seed, 0);
    }

    /** The state read from a stream, as put() writes it. */
    public MixMaxRng(CIStream is) {
        get(is);
    }

    public MixMaxRng(MixMaxRng rng) {
        assign(rng);
    }

    public MixMaxRng assign(MixMaxRng rng) {
        if (this == rng) return this;
        theSeed = rng.theSeed;
        theSeeds = rng.theSeeds;
        System.arraycopy(rng.V, 0, V, 0, N);
        sumtot = rng.sumtot;
        counter = rng.counter;
        return this;
    }

    @Override
    public double flat() {
        if (counter >= N) iterate();
        return INV_M61 * (double) V[counter++];
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) {
            vect[i] = flat();
        }
    }

    @Override
    public void setSeed(long longSeed, int extra) {
        seed_spbox(theSeed = longSeed);
    }

    /** Up to four seeds, taken as 32-bit IDs (two when seedNum is 0). */
    @Override
    public void setSeeds(long[] seeds, int seedNum) {
        int seed0, seed1 = 0, seed2 = 0, seed3 = 0;
        if (seedNum < 1) {
            seed0 = (int) at(seeds, 0);
            seed1 = (int) at(seeds, 1);
        } else {
            seed0 = (int) at(seeds, 0);
            if (seedNum < 4) {
                if (seedNum > 1) seed1 = (int) at(seeds, 1);
                if (seedNum > 2) seed2 = (int) at(seeds, 2);
            } else {
                seed1 = (int) at(seeds, 1);
                seed2 = (int) at(seeds, 2);
                seed3 = (int) at(seeds, 3);
            }
        }
        theSeed = at(seeds, 0);
        theSeeds = seeds;
        seed_uniquestream(seed3, seed2, seed1, seed0);
    }

    private static long at(long[] s, int i) {
        return s != null && i < s.length ? s[i] : 0;
    }

    public static String engineName() {
        return "MixMaxRng";
    }

    @Override
    public String name() {
        return "MixMaxRng";
    }

    @Override
    public float toFloat() {
        return (float) flat();
    }

    /** operator unsigned int: the low 32 bits of the next raw number. */
    @Override
    public long toUnsignedInt() {
        return get_next() & MASK32;
    }

    // ------------------------------------------------------------------
    // The generator
    // ------------------------------------------------------------------

    private static long modMersenne(long k) {
        return (k & M61) + (k >>> BITS);
    }

    private static long mulwu(long k) {
        return ((k << SPECIALMUL) & M61) ^ (k >>> (BITS - SPECIALMUL));
    }

    private static long modadd(long a, long b) {
        return modMersenne(a + b);
    }

    private void iterate() {
        sumtot = iterate_raw_vec(V, sumtot);
        counter = 1;
    }

    private static long iterate_raw_vec(long[] y, long sumtotOld) {
        long tempP, tempV;
        y[0] = (tempV = sumtotOld);
        long insumtot = y[0], ovflow = 0;
        tempP = 0;
        for (int i = 1; i < N; ++i) {
            final long tempPO = mulwu(tempP);
            tempP = modadd(tempP, y[i]);
            tempV = modMersenne(tempV + tempP + tempPO);
            y[i] = tempV;
            insumtot += tempV;
            if (Long.compareUnsigned(insumtot, tempV) < 0) ++ovflow;
        }
        return modMersenne(modMersenne(insumtot) + (ovflow << 3));
    }

    private long get_next() {
        final int i = counter;
        if (i <= N - 1) {
            ++counter;
            return V[i];
        } else {
            sumtot = iterate_raw_vec(V, sumtot);
            counter = 2;
            return V[1];
        }
    }

    private long precalc() {
        long temp = 0;
        for (int i = 0; i < N; ++i) {
            temp = modMersenne(temp + V[i]);
        }
        sumtot = temp;
        return temp;
    }

    private void state_init() {
        for (int i = 1; i < N; ++i) {
            V[i] = 0;
        }
        V[0] = 1;
        counter = N;
        sumtot = 1;
    }

    private void seed_spbox(long seed) {
        if (seed == 0) throw new IllegalArgumentException("try seeding with nonzero seed next time");
        final long MULT64 = 6364136223846793005L;
        sumtot = 0;
        long l = seed;
        for (int i = 0; i < N; ++i) {
            l *= MULT64;
            l = (l << 32) ^ (l >>> 32);
            V[i] = l & M61;
            sumtot = modMersenne(sumtot + V[i]);
        }
        counter = N;
    }

    private void seed_uniquestream(int clusterID, int machineID, int runID, int streamID) {
        state_init();
        sumtot = apply_bigskip(V, V, clusterID, machineID, runID, streamID);
        counter = 1;
    }

    /** The state jumped ahead by the skips each bit of the four IDs selects. */
    private static long apply_bigskip(long[] vout, long[] vin, int clusterID, int machineID, int runID, int streamID) {
        final long[][] skipMat = MixMaxSkip.SKIP;
        final int[] idvec = {streamID, runID, machineID, clusterID};
        final long[] y = new long[N];
        final long[] cum = new long[N];
        long insumtot = 0;
        for (int i = 0; i < N; ++i) {
            y[i] = vin[i];
            insumtot = modadd(insumtot, vin[i]);
        }
        for (int idIndex = 0; idIndex < 4; ++idIndex) {
            int id = idvec[idIndex];
            int r = 0;
            while (id != 0) {
                if ((id & 1) != 0) {
                    final long[] rowPtr = skipMat[r + idIndex * 32];
                    for (int i = 0; i < N; ++i) {
                        cum[i] = 0;
                    }
                    for (int j = 0; j < N; ++j) {
                        final long coeff = rowPtr[j];
                        for (int i = 0; i < N; ++i) {
                            cum[i] = fmodmulM61(cum[i], coeff, y[i]);
                        }
                        insumtot = iterate_raw_vec(y, insumtot);
                    }
                    insumtot = 0;
                    for (int i = 0; i < N; ++i) {
                        y[i] = cum[i];
                        insumtot = modadd(insumtot, cum[i]);
                    }
                }
                id = id >>> 1;
                ++r;
            }
        }
        insumtot = 0;
        for (int i = 0; i < N; ++i) {
            vout[i] = y[i];
            insumtot = modadd(insumtot, y[i]);
        }
        return insumtot;
    }

    /** (a * b + cum) mod 2^61 - 1, partly reduced, from the 128-bit product as the x86-64 C++ makes it. */
    private static long fmodmulM61(long cum, long a, long b) {
        final long plo = a * b;
        final long phi = Math.unsignedMultiplyHigh(a, b);
        final long lo = plo + cum;
        final long hi = phi + (Long.compareUnsigned(lo, plo) < 0 ? 1 : 0);
        final long s1 = (lo & M61) + (hi * 8) + (lo >>> BITS);
        return modMersenne(s1);
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    @Override
    public void saveStatus(String filename) {
        final StringBuilder b = new StringBuilder("mixmax state, file version 1.0\n");
        b.append("N=").append(N).append("; V[N]={");
        for (int j = 0; j < N - 1; ++j) {
            b.append(Long.toUnsignedString(V[j])).append(", ");
        }
        b.append(Long.toUnsignedString(V[N - 1]));
        b.append("}; ");
        b.append("counter=").append(counter).append("; ");
        b.append("sumtot=").append(Long.toUnsignedString(sumtot)).append(";\n");
        writeStatus(filename, b.toString());
    }

    private static final Pattern STATE = Pattern.compile(
        "\\{\\s*(\\d+)((?:\\s*,\\s*\\d+)*)\\s*\\}\\s*;\\s*counter=\\s*([+-]?\\d+)\\s*;\\s*sumtot=\\s*(\\d+)");

    @Override
    public void restoreStatus(String filename) {
        final CIStream in = openStatus(filename);
        if (in == null) {
            StdStreams.cerr().print("mixmax -> read_state: error reading file " + filename + "\n");
            throw new IllegalStateException("Error in reading state file");
        }
        final Matcher m = STATE.matcher(in.rest());
        if (!m.find()) {
            StdStreams.cerr().print("mixmax -> read_state: error reading file " + filename + "\n");
            throw new IllegalStateException("Error in reading state file");
        }
        V[0] = Long.parseUnsignedLong(m.group(1));
        final String[] rest = m.group(2).split(",");
        int i = 1;
        for (String t : rest) {
            if (t.isBlank()) continue;
            if (i >= N) break;
            final long vecVal = Long.parseUnsignedLong(t.trim());
            if (Long.compareUnsigned(vecVal, M61) <= 0) {
                V[i] = vecVal;
            } else {
                StdStreams.cerr().print("mixmax -> read_state: Invalid state vector value= " + Long.toUnsignedString(vecVal)
                    + " ( must be less than " + M61 + " )  obtained from reading file " + filename + "\n");
            }
            i++;
        }
        if (i < N) {
            StdStreams.cerr().print("mixmax -> read_state: error reading vector component i=" + i + " from file "
                + filename + "\n");
            throw new IllegalStateException("Error in reading state file");
        }
        final int incounter = Integer.parseInt(m.group(3));
        if (incounter <= N) {
            counter = incounter;
        } else {
            StdStreams.cerr().print("mixmax -> read_state: Invalid counter = " + counter + "  Must be 0 <= counter < "
                + N + "\n");
            print_state();
            throw new IllegalStateException("Error in reading state counter");
        }
        precalc();
        final long insumtot = Long.parseUnsignedLong(m.group(4));
        if (sumtot != insumtot) {
            StdStreams.cerr().print("mixmax -> checksum error while reading state from file " + filename
                + " - corrupted?\n");
            throw new IllegalStateException("Error in reading state checksum");
        }
    }

    @Override
    public void showStatus() {
        StdStreams.cout().print("\n------- MixMaxRng engine status -------\n Current state vector is:\n");
        print_state();
        StdStreams.cout().print("---------------------------------------\n");
    }

    private void print_state() {
        final StringBuilder b = new StringBuilder("mixmax state, file version 1.0\n");
        b.append("N=").append(N).append("; V[N]={");
        for (int j = 0; j < N - 1; ++j) {
            b.append(Long.toUnsignedString(V[j])).append(", ");
        }
        b.append(Long.toUnsignedString(V[N - 1]));
        b.append("}; counter= ").append(counter).append("sumtot= ").append(Long.toUnsignedString(sumtot)).append('\n');
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        os.put("MixMaxRng-begin").put(" ").put(theSeed).put("\n");
        for (int i = 0; i < N; ++i) {
            os.put(Long.toUnsignedString(V[i])).put("\n");
        }
        os.put(counter).put("\n");
        os.put(Long.toUnsignedString(sumtot)).put("\n");
        os.put("MixMaxRng-end").put("\n");
        return os;
    }

    @Override
    public long[] put() {
        final long[] vec = new long[VECTOR_STATE_SIZE];
        int k = 0;
        vec[k++] = engineIDulong(engineName());
        for (int i = 0; i < N; ++i) {
            vec[k++] = V[i] & MASK32;
            vec[k++] = V[i] >>> 32;
        }
        vec[k++] = counter & MASK32;
        vec[k++] = sumtot & MASK32;
        vec[k] = sumtot >>> 32;
        return vec;
    }

    @Override
    public CIStream get(CIStream is) {
        final String beginMarker = marker(is);
        if (!beginMarker.equals("MixMaxRng-begin")) {
            is.setFail();
            StdStreams.cerr().print("\nInput stream mispositioned or\nMixMaxRng state description missing or"
                + "\nwrong engine type found.\n");
            return is;
        }
        return getState(is);
    }

    public static String beginTag() {
        return "MixMaxRng-begin";
    }

    @Override
    public CIStream getState(CIStream is) {
        theSeed = is.nextLong();
        for (int i = 0; i < N; ++i) {
            V[i] = is.nextUnsigned(64);
        }
        counter = is.nextInt();
        final long checksum = is.nextUnsigned(64);
        final String endMarker = marker(is);
        if (!endMarker.equals("MixMaxRng-end")) {
            is.setFail();
            StdStreams.cerr().print("\nMixMaxRng state description incomplete.\nInput stream is probably mispositioned now.\n");
            return is;
        }
        if (counter < 0 || counter > N) {
            StdStreams.cerr().print("\nMixMaxRng::getState(): vector read wrong value of counter from file!"
                + "\nInput stream is probably mispositioned now.\n");
            return is;
        }
        precalc();
        if (checksum != sumtot) {
            StdStreams.cerr().print("\nMixMaxRng::getState(): checksum disagrees with value stored in file!"
                + "\nInput stream is probably mispositioned now.\n");
        }
        return is;
    }

    @Override
    public boolean get(long[] vec) {
        if ((vec[0] & 0xffffffffL) != engineIDulong(engineName())) {
            StdStreams.cerr().print("\nMixMaxRng::get(): vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(vec);
    }

    @Override
    public boolean getState(long[] vec) {
        if (vec.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("\nMixMaxRng::getState(): vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 1; i < 2 * N; i = i + 2) {
            V[i / 2] = (vec[i] & MASK32) | (vec[i + 1] << 32);
        }
        counter = (int) vec[2 * N + 1];
        precalc();
        if (((vec[2 * N + 2] & MASK32) | (vec[2 * N + 3] << 32)) != sumtot) {
            StdStreams.cerr().print("\nMixMaxRng::getState(): vector has wrong checksum!"
                + "\nInput vector is probably mispositioned now.\n");
            return false;
        }
        return true;
    }
}
