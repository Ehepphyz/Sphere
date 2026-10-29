package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLHEP's HepRandomEngine (Random/RandomEngine.h, .icc, RandomEngine.cc): what every engine gives —
 * flat numbers in (0,1), seeding, and its state saved and restored as text (put/get on streams, the
 * status files) or as a vector of 32-bit words ({@link #put()}, {@link #get(long[])}).
 *
 * <p>C++'s {@code long} is 64 bits here, as on Linux x86-64 where Geant4 runs; CLHEP keeps every word
 * of an engine's state within 32 bits, so the sequences are those of any platform for seeds that fit
 * in 32 bits. {@code std::vector<unsigned long>} is a {@code long[]}.
 */
public abstract class HepRandomEngine {

    /** Room for a begin or end marker in a stream. */
    protected static final int MarkerLen = 64;

    protected long theSeed = 19780503L;
    /** setSeeds' array; null while the seeds are the single theSeed (C++'s theSeeds = &amp;theSeed). */
    protected long[] theSeeds;

    protected HepRandomEngine() {
    }

    /** What the engines' copy constructors copy of the base: the seed, and the seeds' array (C++ copies its pointer). */
    protected HepRandomEngine(HepRandomEngine o) {
        theSeed = o.theSeed;
        theSeeds = o.theSeeds;
    }

    /** A number in (0,1). */
    public abstract double flat();

    public abstract void flatArray(int size, double[] vect);

    public abstract void setSeed(long seed, int extra);

    public abstract void setSeeds(long[] seeds, int extra);

    public abstract void saveStatus(String filename);

    public abstract void restoreStatus(String filename);

    public abstract void showStatus();

    public abstract String name();

    /** operator&lt;&lt;: the state as text. */
    public COStream put(COStream os) {
        StdStreams.cerr().print("HepRandomEngine::put called -- no effect!\n");
        return os;
    }

    /** operator&gt;&gt;: the state read back, from its begin tag. */
    public CIStream get(CIStream is) {
        StdStreams.cerr().print("HepRandomEngine::get called -- no effect!\n");
        return is;
    }

    public static String beginTag() {
        return "HepRandomEngine-begin";
    }

    /** The state read back, its begin tag already taken. */
    public CIStream getState(CIStream is) {
        StdStreams.cerr().print("HepRandomEngine::getState called -- no effect!\n");
        return is;
    }

    /** The state as 32-bit words, the first the engine's ID. */
    public long[] put() {
        StdStreams.cerr().print("v=HepRandomEngine::put() called -- no data!\n");
        return new long[0];
    }

    public boolean get(long[] v) {
        StdStreams.cerr().print("HepRandomEngine::get(v) called -- no effect!\n");
        return false;
    }

    public boolean getState(long[] v) {
        StdStreams.cerr().print("HepRandomEngine::getState(v) called -- no effect!\n");
        return false;
    }

    /** An engine of the kind its saved state names. */
    public static HepRandomEngine newEngine(CIStream is) {
        return EngineFactory.newEngine(is);
    }

    public static HepRandomEngine newEngine(long[] v) {
        return EngineFactory.newEngine(v);
    }

    public long getSeed() {
        return theSeed;
    }

    public long[] getSeeds() {
        return theSeeds != null ? theSeeds : new long[] {theSeed};
    }

    /** operator double: flat(). */
    public double toDouble() {
        return flat();
    }

    /** operator float. */
    public float toFloat() {
        return (float) flat();
    }

    /** operator unsigned int: 32 random bits, as the low bits of a long. */
    public long toUnsignedInt() {
        return d2ui(flat() * exponent_bit_32());
    }

    /** (unsigned int) d of a double in [0, 2^32): its integer part (beyond, the 64-bit conversion's low bits, as GCC). */
    protected static long d2ui(double d) {
        return ((long) d) & 0xffffffffL;
    }

    // the constants CLHEP computes once
    private static final double EXPONENT_BIT_32 = Math.pow(2.0, 32.0);
    private static final double MANTISSA_BIT_12 = Math.pow(0.5, 12.0);
    private static final double MANTISSA_BIT_24 = Math.pow(0.5, 24.0);
    private static final double TWO_TO_MINUS_32 = Math.scalb(1.0, -32);
    private static final double TWO_TO_MINUS_48 = Math.scalb(1.0, -48);
    private static final double TWO_TO_MINUS_49 = Math.scalb(1.0, -49);
    private static final double TWO_TO_MINUS_53 = Math.scalb(1.0, -53);
    private static final double NEARLY_TWO_TO_MINUS_54 = Math.scalb(1.0, -54) - Math.scalb(1.0, -100);

    protected static double exponent_bit_32() {
        return EXPONENT_BIT_32;
    }

    protected static double mantissa_bit_12() {
        return MANTISSA_BIT_12;
    }

    protected static double mantissa_bit_24() {
        return MANTISSA_BIT_24;
    }

    protected static double twoToMinus_32() {
        return TWO_TO_MINUS_32;
    }

    protected static double twoToMinus_48() {
        return TWO_TO_MINUS_48;
    }

    protected static double twoToMinus_49() {
        return TWO_TO_MINUS_49;
    }

    protected static double twoToMinus_53() {
        return TWO_TO_MINUS_53;
    }

    protected static double nearlyTwoToMinus_54() {
        return NEARLY_TWO_TO_MINUS_54;
    }

    /** False, and said on cerr, when a status file cannot be read. */
    protected static boolean checkFile(CIStream file, String filename, String classname, String methodname) {
        if (file == null || file.fail()) {
            StdStreams.cerr().print("Failure to find or open file " + filename + " in " + classname + "::" + methodname
                + "()\n");
            return false;
        }
        return true;
    }

    /** A status file's text, or null when it cannot be read. */
    protected static CIStream openStatus(String filename) {
        final String text = StatusFiles.read(filename);
        return text == null ? null : new CIStream(text);
    }

    /** A status file written; false when it cannot be. */
    protected static boolean writeStatus(String filename, String text) {
        return StatusFiles.write(filename, text);
    }

    /**
     * possibleKeywordInput: true when the next word is the keyword; otherwise the word is read as a
     * number into seed[0] (CLHEP's theSeed), and false.
     */
    protected static boolean possibleKeywordInput(CIStream is, String key, long[] seed) {
        final String firstWord = is.nextWord();
        if (firstWord.equals(key)) return true;
        final CIStream reread = new CIStream(firstWord);
        final long v = reread.nextLong();
        if (!reread.fail()) seed[0] = v;
        else seed[0] = 0;
        return false;
    }

    /** "begin" read with the width of MarkerLen: the next word, at most 63 characters. */
    protected static String marker(CIStream is) {
        is.ws();
        final String w = is.nextWord();
        return w.length() > MarkerLen - 1 ? w.substring(0, MarkerLen - 1) : w;
    }

    /** A state vector of 32-bit words read from a stream: VECTOR_STATE_SIZE unsigned longs. */
    protected static long[] readWords(CIStream is, int n) {
        final long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = is.nextUnsigned(64);
            if (is.fail()) return null;
        }
        return v;
    }

    /** saveStatus of the engines that save their words: "Uvec", then the words of put(), one per line. */
    protected final void saveWords(String filename) {
        writeStatus(filename, "Uvec\n" + words(put()));
    }

    /** put(os) of the engines that save their words: the begin marker, "Uvec", the words. */
    protected final COStream putWords(COStream os, String beginMarker) {
        os.put(beginMarker).put("\nUvec\n");
        for (long w : put()) {
            os.put(Long.toUnsignedString(w)).put("\n");
        }
        return os;
    }

    /**
     * The "Uvec" form of a saved state, as getState and restoreStatus read it: true when the stream holds it
     * (its words then read and set, or the stream failed with the message given); false when it holds the
     * older form, whose first word was read into theSeed.
     */
    protected final boolean readUvec(CIStream is, int size, String failure) {
        final long[] seed = {theSeed};
        if (!possibleKeywordInput(is, "Uvec", seed)) {
            theSeed = seed[0];
            return false;
        }
        final long[] v = new long[size];
        for (int i = 0; i < size; ++i) {
            v[i] = is.nextUnsigned(64);
            if (is.fail()) {
                is.setFail();
                StdStreams.cerr().print(failure);
                return true;
            }
        }
        getState(v);
        return true;
    }

    /** The words as saveStatus and put write them, one per line. */
    protected static String words(long[] v) {
        final StringBuilder b = new StringBuilder();
        for (long x : v) {
            b.append(Long.toUnsignedString(x)).append('\n');
        }
        return b.toString();
    }

    private static final Map<String, Long> IDS = new ConcurrentHashMap<>();

    /** engineIDulong&lt;E&gt;(): the CRC-32 of the engine's name, its state vectors' first word. */
    protected static long engineIDulong(String engineName) {
        return IDS.computeIfAbsent(engineName, HepRandomEngine::crc32ul);
    }

    private static final long[] CRC_TABLE = crcTable();

    private static long[] crcTable() {
        final long poly = 0x04c11db7L;
        final long[] t = new long[256];
        for (long i = 0; i < 256; ++i) {
            long crc = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80000000L) != 0) {
                    crc = ((crc << 1) ^ poly) & 0xffffffffL;
                } else {
                    crc = (crc << 1) & 0xffffffffL;
                }
            }
            t[(int) i] = crc;
        }
        return t;
    }

    /** crc32ul: CLHEP's CRC-32 (not reflected) of a string. */
    public static long crc32ul(String s) {
        long crc = 0;
        for (int j = 0; j < s.length(); ++j) {
            final int i = ((int) (crc >>> 24) ^ (byte) s.charAt(j)) & 0xff;
            crc = ((crc << 8) ^ CRC_TABLE[i]) & 0xffffffffL;
        }
        return crc;
    }

    /** The text of a double as cout prints it (six significant digits). */
    protected static String g(double x) {
        return new COStream().put(x).str();
    }

    @Override
    public String toString() {
        return put(new COStream()).str();
    }
}
