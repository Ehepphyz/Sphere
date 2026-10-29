package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLHEP's RanluxppEngine (Random/RanluxppEngine.h, RanluxppEngine.cc, src/ranluxpp): RANLUX++ of
 * A. Sibidanov and J. Hahnfeld, RANLUX as the equivalent linear congruential generator on 576 bits,
 * advanced by multiplication with a^2048 modulo m = 2^576 - 2^240 + 1; 48 bits per number.
 *
 * <p>to_ranlux keeps CLHEP's loop that adds the borrow to the words above the first without storing
 * them: the carry it leaves is all it changes, as in the C++.
 */
public final class RanluxppEngine extends HepRandomEngine {

    public static final int VECTOR_STATE_SIZE = 21;
    private static final AtomicInteger numberOfEngines = EngineCounters.counter();
    private static final long[] kA_2048 = {
        0xed7faa90747aaad9L, 0x4cec2c78af55c101L, 0xe64dcb31c48228ecL,
        0x6d8a15a13bee7cb0L, 0x20b2ca60cb78c509L, 0x256c3d3c662ea36cL,
        0xff74e54107684ed2L, 0x492edfcc0cc8e753L, 0xb48c187cf5b22097L,
    };
    private static final int kMaxPos = 9 * 64;
    private static final int kBits = 48;

    private final long[] fState = new long[9];
    /** unsigned in C++. */
    private int fCarry;
    private int fPosition = 0;

    public RanluxppEngine() {
        final int numEngines = numberOfEngines.incrementAndGet();
        setSeed(numEngines, 0);
    }

    public RanluxppEngine(long seed) {
        theSeed = seed;
        setSeed(seed, 0);
    }

    public RanluxppEngine(CIStream is) {
        get(is);
    }

    /** The copy constructor (C++'s implicit one): this engine's state, the seeds' array shared as C++ copies its pointer. */
    public RanluxppEngine(RanluxppEngine o) {
        super(o);
        System.arraycopy(o.fState, 0, fState, 0, fState.length);
        fCarry = o.fCarry;
        fPosition = o.fPosition;
    }

    public static String engineName() {
        return "RanluxppEngine";
    }

    @Override
    public String name() {
        return engineName();
    }

    public static String beginTag() {
        return "RanluxppEngine-begin";
    }

    // ------------------------------------------------------------------
    // Arithmetic on 576 bits (ranluxpp/helpers.h, mulmod.h, ranlux_lcg.h)
    // ------------------------------------------------------------------

    /** a + b, the overflow (0 or 1) into o[0]. */
    private static long addOverflow(long a, long b, int[] o) {
        final long add = a + b;
        o[0] = Long.compareUnsigned(add, a) < 0 ? 1 : 0;
        return add;
    }

    private static long addCarry(long a, long b, int[] carry) {
        final int[] o = new int[1];
        final long add = addOverflow(a, b, o);
        carry[0] += o[0];
        return add;
    }

    private static long subOverflow(long a, long b, int[] o) {
        final long sub = a - b;
        o[0] = Long.compareUnsigned(sub, a) > 0 ? 1 : 0;
        return sub;
    }

    private static long subCarry(long a, long b, int[] carry) {
        final int[] o = new int[1];
        final long sub = subOverflow(a, b, o);
        carry[0] += o[0];
        return sub;
    }

    /** An unsigned C carry as a 64-bit operand. */
    private static long u(int c) {
        return c & 0xffffffffL;
    }

    private static long computeR(long[] upper, int up, long[] r) {
        final int[] carry = {0};
        for (int i = 0; i < 9; i++) {
            long r_i = r[i];
            r_i = subOverflow(r_i, u(carry[0]), carry);
            final long t1_i = upper[up + i];
            r_i = subCarry(r_i, t1_i, carry);
            r[i] = r_i;
        }
        long c = -u(carry[0]);
        carry[0] = 0;
        for (int i = 0; i < 9; i++) {
            long r_i = r[i];
            r_i = subOverflow(r_i, u(carry[0]), carry);
            long t2_bits = 0;
            if (i < 4) {
                t2_bits += upper[up + i + 5] >>> 16;
                if (i < 3) t2_bits += upper[up + i + 6] << 48;
            }
            r_i = subCarry(r_i, t2_bits, carry);
            r[i] = r_i;
        }
        c -= u(carry[0]);
        carry[0] = 0;
        {
            long r_3 = r[3];
            final long t2_bits = (upper[up + 5] >>> 16) << 48;
            final long t3_bits = (upper[up] << 48);
            r_3 = addCarry(r_3, t2_bits, carry);
            r_3 = addCarry(r_3, t3_bits, carry);
            r[3] = r_3;
        }
        for (int i = 0; i < 3; i++) {
            long r_i = r[i + 4];
            r_i = addOverflow(r_i, u(carry[0]), carry);
            final long t2_bits = (upper[up + 5 + i] >>> 32) + (upper[up + 6 + i] << 32);
            final long t3_bits = (upper[up + i] >>> 16) + (upper[up + 1 + i] << 48);
            r_i = addCarry(r_i, t2_bits, carry);
            r_i = addCarry(r_i, t3_bits, carry);
            r[i + 4] = r_i;
        }
        {
            long r_7 = r[7];
            r_7 = addOverflow(r_7, u(carry[0]), carry);
            final long t2_bits = (upper[up + 8] >>> 32);
            final long t3_bits = (upper[up + 3] >>> 16) + (upper[up + 4] << 48);
            r_7 = addCarry(r_7, t2_bits, carry);
            r_7 = addCarry(r_7, t3_bits, carry);
            r[7] = r_7;
        }
        {
            long r_8 = r[8];
            r_8 = addOverflow(r_8, u(carry[0]), carry);
            final long t3_bits = (upper[up + 4] >>> 16) + (upper[up + 5] << 48);
            r_8 = addCarry(r_8, t3_bits, carry);
            r[8] = r_8;
        }
        c += u(carry[0]);
        boolean greater_m = (r[0] | r[1] | r[2] | (r[3] & 0x0000ffffffffffffL)) != 0;
        greater_m &= (r[3] >>> 48) == 0xffff;
        for (int i = 4; i < 9; i++) {
            greater_m &= (r[i] == -1L);
        }
        return c + (c == 0 && greater_m ? 1 : 0);
    }

    private static void multiply9x9(long[] in1, long[] in2, long[] out) {
        long next = 0;
        final int[] nextCarry = {0};
        for (int i = 0; i < 18; i++) {
            long current = next;
            final int[] carry = {nextCarry[0]};
            next = 0;
            nextCarry[0] = 0;
            for (int j = 0; j < 9; j++) {
                final int k = i - j;
                if (k < 0 || k >= 9) continue;
                final long fac1 = in1[j];
                final long fac2 = in2[k];
                final long upper = Math.unsignedMultiplyHigh(fac1, fac2);
                final long lower = fac1 * fac2;
                current = addCarry(current, lower, carry);
                next = addCarry(next, upper, nextCarry);
            }
            next = addCarry(next, u(carry[0]), nextCarry);
            out[i] = current;
        }
    }

    private static void modM(long[] mul, long[] out) {
        final long[] r = new long[9];
        System.arraycopy(mul, 0, r, 0, 9);
        final long c = computeR(mul, 9, r);
        final long t0 = c >> 1;
        final long t2 = t0 - (c << 48);
        final long t1 = t2 >> 48;
        final int[] carry = {0};
        out[0] = subCarry(r[0], c, carry);
        for (int i = 1; i < 3; i++) {
            long r_i = r[i];
            r_i = subOverflow(r_i, u(carry[0]), carry);
            out[i] = subCarry(r_i, t0, carry);
        }
        {
            long r_3 = r[3];
            r_3 = subOverflow(r_3, u(carry[0]), carry);
            out[3] = subCarry(r_3, t2, carry);
        }
        for (int i = 4; i < 9; i++) {
            long r_i = r[i];
            r_i = subOverflow(r_i, u(carry[0]), carry);
            out[i] = subCarry(r_i, t1, carry);
        }
    }

    /** inout = in1 * inout modulo m. */
    private static void mulmod(long[] in1, long[] inout) {
        final long[] mul = new long[18];
        multiply9x9(in1, inout, mul);
        modM(mul, inout);
    }

    /** res = base^n modulo m (res may be base). n is unsigned. */
    private static void powermod(long[] base, long[] res, long n) {
        final long[] fac = new long[9];
        fac[0] = base[0];
        res[0] = 1;
        for (int i = 1; i < 9; i++) {
            fac[i] = base[i];
            res[i] = 0;
        }
        final long[] mul = new long[18];
        while (n != 0) {
            if ((n & 1) != 0) {
                multiply9x9(res, fac, mul);
                modM(mul, res);
            }
            n >>>= 1;
            if (n == 0) break;
            multiply9x9(fac, fac, mul);
            modM(mul, fac);
        }
    }

    private static void toLcg(long[] ranlux, int c, long[] lcg) {
        final int[] carry = {0};
        for (int i = 0; i < 9; i++) {
            final long ranlux_i = ranlux[i];
            long lcg_i = subOverflow(ranlux_i, u(carry[0]), carry);
            long bits = 0;
            if (i < 4) {
                bits += ranlux[i + 5] >>> 16;
                if (i < 3) bits += ranlux[i + 6] << 48;
            }
            lcg_i = subCarry(lcg_i, bits, carry);
            lcg[i] = lcg_i;
        }
        final int[] cc = {c};
        for (int i = 0; i < 9; i++) {
            lcg[i] = addOverflow(lcg[i], u(cc[0]), cc);
        }
    }

    /** to_ranlux: the RANLUX state of an LCG state; its carry returned. */
    private static int toRanlux(long[] lcg, long[] ranlux) {
        final long[] r = new long[9];
        final long c = computeR(lcg, 0, r);
        final int[] carry = {0};
        for (int i = 0; i < 9; i++) {
            final long in_i = lcg[i];
            long tmp_i = addOverflow(in_i, u(carry[0]), carry);
            long bits = 0;
            if (i < 4) {
                bits += lcg[i + 5] >>> 16;
                if (i < 3) bits += lcg[i + 6] << 48;
            }
            tmp_i = addCarry(tmp_i, bits, carry);
            ranlux[i] = tmp_i;
        }
        final long c1 = c >> 1;
        ranlux[0] = addOverflow(ranlux[0], c, carry);
        for (int i = 1; i < 9; i++) {
            long ranlux_i = ranlux[i];
            ranlux_i = addOverflow(ranlux_i, u(carry[0]), carry);
            addCarry(ranlux_i, c1, carry);   // as CLHEP: the sum is not stored, only the carry counts
        }
        return carry[0];
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    private void advance() {
        final long[] lcg = new long[9];
        toLcg(fState, fCarry, lcg);
        mulmod(kA_2048, lcg);
        fCarry = toRanlux(lcg, fState);
        fPosition = 0;
    }

    private long nextRandomBits() {
        if (fPosition + kBits > kMaxPos) advance();
        final int idx = fPosition / 64;
        final int offset = fPosition % 64;
        final int numBits = 64 - offset;
        long bits = fState[idx] >>> offset;
        if (numBits < kBits) {
            bits |= fState[idx + 1] << numBits;
        }
        bits &= ((1L << kBits) - 1);
        fPosition += kBits;
        return bits;
    }

    @Override
    public double flat() {
        long random;
        do {
            random = nextRandomBits();
        } while (random == 0);
        final double div = 1.0 / (1L << kBits);
        return random * div;
    }

    @Override
    public void flatArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = flat();
    }

    /** The low 32 of the next 48 bits, as the C++ operator unsigned int takes them. */
    @Override
    public long toUnsignedInt() {
        return nextRandomBits() & 0xffffffffL;
    }

    @Override
    public void setSeed(long seed, int extra) {
        theSeed = seed;
        final long[] lcg = new long[9];
        lcg[0] = 1;
        final long[] a_seed = new long[9];
        powermod(kA_2048, a_seed, 1L << 48);
        powermod(a_seed, a_seed, 1L << 48);
        powermod(a_seed, a_seed, seed);
        mulmod(a_seed, lcg);
        fCarry = toRanlux(lcg, fState);
        fPosition = 0;
    }

    @Override
    public void setSeeds(long[] seeds, int extra) {
        theSeeds = seeds;
        setSeed(seeds[0], 0);
    }

    /** skip(n): n numbers skipped, in a few multiplications. n is unsigned. */
    public void skip(long n) {
        final int left = (kMaxPos - fPosition) / kBits;
        if (Long.compareUnsigned(n, left) < 0) {
            fPosition += (int) (n * kBits);
            return;
        }
        n -= left;
        final int nPerState = kMaxPos / kBits;
        final int skip = (int) Long.divideUnsigned(n, nPerState);
        final long[] a_skip = new long[9];
        powermod(kA_2048, a_skip, skip + 1);
        final long[] lcg = new long[9];
        toLcg(fState, fCarry, lcg);
        mulmod(a_skip, lcg);
        fCarry = toRanlux(lcg, fState);
        final int remaining = (int) (n - (long) skip * nPerState);
        fPosition = remaining * kBits;
    }

    @Override
    public void saveStatus(String filename) {
        writeStatus(filename, put(new COStream()).str());
    }

    @Override
    public void restoreStatus(String filename) {
        final CIStream is = openStatus(filename);
        if (is == null) return;
        get(is);
    }

    @Override
    public void showStatus() {
        final StringBuilder b = new StringBuilder("\n--------------------- RanluxppEngine status --------------------\n");
        b.append(" fState[] = {");
        for (int i = 0; i < 9; i++) {
            b.append(i % 3 == 0 ? "\n     " : " ");
            b.append("0x").append(String.format("%016x", fState[i])).append(',');
        }
        b.append("\n }\n");
        b.append(" fCarry = ").append(fCarry & 0xffffffffL).append(", fPosition = ").append(fPosition).append('\n');
        b.append("----------------------------------------------------------------\n");
        StdStreams.cout().print(b);
    }

    @Override
    public COStream put(COStream os) {
        os.put(beginTag()).put("\n");
        for (long v : put()) os.put(Long.toUnsignedString(v)).put("\n");
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        final String tag = is.nextWord();
        if (!tag.equals(beginTag())) {
            is.setFail();
            StdStreams.cerr().print("No RanluxppEngine found at current position\n");
            return is;
        }
        return getState(is);
    }

    @Override
    public CIStream getState(CIStream is) {
        final long[] state = new long[VECTOR_STATE_SIZE];
        for (int i = 0; i < VECTOR_STATE_SIZE; i++) state[i] = is.nextUnsigned(64);
        getState(state);
        return is;
    }

    @Override
    public long[] put() {
        final long[] v = new long[VECTOR_STATE_SIZE];
        v[0] = engineIDulong(engineName());
        for (int i = 0; i < 9; i++) {
            v[2 * i + 1] = fState[i] & 0xffffffffL;
            v[2 * i + 2] = fState[i] >>> 32;
        }
        v[19] = fCarry & 0xffffffffL;
        v[20] = fPosition & 0xffffffffL;
        return v;
    }

    @Override
    public boolean get(long[] v) {
        if (v[0] != engineIDulong(engineName())) {
            StdStreams.cerr().print("RanluxppEngine::get(): vector has wrong ID word - state unchanged\n");
            return false;
        }
        return getState(v);
    }

    @Override
    public boolean getState(long[] v) {
        if (v.length != VECTOR_STATE_SIZE) {
            StdStreams.cerr().print("RanluxppEngine::getState(): vector has wrong length - state unchanged\n");
            return false;
        }
        for (int i = 0; i < 9; i++) {
            final long lower = v[2 * i + 1];
            final long upper = v[2 * i + 2];
            fState[i] = (upper << 32) + lower;
        }
        fCarry = (int) v[19];
        fPosition = (int) v[20];
        return true;
    }
}
