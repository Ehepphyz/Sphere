package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * What the distributions' put and get share: a double written with the two words of its bits
 * ({@code x << " " << t[0] << " " << t[1]}), read back from the words; the name a state begins with.
 */
final class DistIO {

    private DistIO() {
    }

    /** The name that begins a distribution's state: false, the stream failed and cerr told, when it is another. */
    static boolean expect(CIStream is, String name) {
        final String inName = is.nextWord();
        if (!inName.equals(name)) {
            is.setFail();
            StdStreams.cerr().print("Mismatch when expecting to read state of a " + name + " distribution\n"
                + "Name found was " + inName + "\nistream is left in the badbit state\n");
            return false;
        }
        return true;
    }

    /** The name that begins a static state (restoreDistState). */
    static boolean expectStatic(CIStream is, String name) {
        final String inName = is.nextWord();
        if (!inName.equals(name)) {
            is.setFail();
            StdStreams.cerr().print("Mismatch when expecting to read static state of a " + name + " distribution\n"
                + "Name found was " + inName + "\nistream is left in the badbit state\n");
            return false;
        }
        return true;
    }

    /** x and the two words of its bits, then a new line. */
    static void put(COStream os, double x) {
        final long[] t = DoubConv.dto2longs(x);
        os.put(x).put(" ").put(t[0]).put(" ").put(t[1]).put("\n");
    }

    /** The two words of x's bits. */
    static void words(COStream os, double x) {
        final long[] t = DoubConv.dto2longs(x);
        os.put(t[0]).put(" ").put(t[1]);
    }

    /** What put wrote: the decimal read and set aside, the words make the value. */
    static double get(CIStream is) {
        is.nextDouble();
        return getWords(is);
    }

    /** The two words of a double's bits. */
    static double getWords(CIStream is) {
        final long hi = is.nextUnsigned(64);
        final long lo = is.nextUnsigned(64);
        return DoubConv.longs2double(hi, lo);
    }

    /** A word read as a double, as possibleKeywordInput rereads it: 0 when it is no number. */
    static double number(String word) {
        final CIStream reread = new CIStream(word);
        final double v = reread.nextDouble();
        return reread.fail() ? 0 : v;
    }

    /** A word read as an integer, as possibleKeywordInput rereads it: 0 when it is no number. */
    static long integer(String word) {
        final CIStream reread = new CIStream(word);
        final long v = reread.nextLong();
        return reread.fail() ? 0 : v;
    }

    /** Text added at the end of a status file (std::ofstream with std::ios::app). */
    static void append(String filename, String text) {
        // as an ofstream that cannot open, nothing written when it fails
        StatusFiles.append(filename, text);
    }

    /** A status file's words, for the restoreEngineStatus that look for their keyword; null when unreadable. */
    static CIStream read(String filename) {
        final String text = StatusFiles.read(filename);
        return text == null ? null : new CIStream(text);
    }

    /**
     * Reads words of at most 12 characters (infile.width(13) into a char array) until the keyword or the
     * end: true when found.
     */
    static boolean seek(CIStream in, String keyword) {
        while (true) {
            in.ws();
            if (in.eof()) return false;
            final String w = word(in, 12);
            if (w.equals(keyword)) return true;
            if (in.eof()) return false;
        }
    }

    /** One word of at most max characters, as operator>> into a char array with width(max+1) reads it. */
    static String word(CIStream in, int max) {
        in.ws();
        final StringBuilder b = new StringBuilder();
        while (b.length() < max) {
            final int c = in.peek();
            if (c < 0 || Character.isWhitespace(c)) break;
            b.append((char) in.get());
        }
        if (in.peek() < 0) in.ws();   // the end met: eof, as the C++ stream sets it
        return b.toString();
    }
}
