package com.sphere.core.hepmc3.validation;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.CInput;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.CRand;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * What a test program sees of its process: its directory (file names are
 * relative to it), its arguments, std::cout and std::cerr (one stream each,
 * whose flags stay set as in C++), printf, rand(), std::cin, and the helpers
 * of HepMC3TestUtils.h that compare two files or two streams line by line.
 */
public final class TestContext {

    private final Path dir;
    private final String[] args;
    private final InputStream stdin;

    /** std::cout: its precision, width and flags stay as the program sets them. */
    public final COStream cout = stream(StdStreams::cout);

    /** std::cerr. */
    public final COStream cerr = stream(StdStreams::cerr);

    TestContext(Path dir, String[] args, InputStream stdin) {
        this.dir = dir;
        this.args = args.clone();
        this.stdin = stdin;
    }

    private static COStream stream(Supplier<StdStreams.Stream> target) {
        return new COStream() {
            @Override
            protected void write(CharSequence text) {
                target.get().print(text);
            }
        };
    }

    /** argv[1..]. */
    public String[] args() {
        return args.clone();
    }

    /** A file of the test directory. */
    public Path path(String name) {
        return CFiles.path(name);
    }

    public Path dir() {
        return dir;
    }

    /** std::cin (what the test's script pipes in). */
    public InputStream stdin() {
        return stdin;
    }

    public void printf(String fmt, Object... a) {
        StdStreams.cout().print(CFormat.sprintf(fmt, a));
    }

    /** std::rand() of the C library the references were made with. */
    public int rand() {
        return CRand.rand();
    }

    /** RAND_MAX of that library. */
    public int randMax() {
        return CRand.randMax();
    }

    /** unlink(name): 0 when removed. */
    public int unlink(String name) {
        try {
            return Files.deleteIfExists(path(name)) ? 0 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * A thread printing to this program's cout and cerr and opening files in
     * its directory, as a std::thread of the process does.
     */
    public Thread thread(Runnable body) {
        final StdStreams streams = StdStreams.current();
        return new Thread(() -> {
            try {
                StdStreams.with(streams, () -> {
                    body.run();
                    return null;
                });
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /* ---- HepMC3TestUtils.h ---------------------------------------------------------- */

    /** Lines left out of a comparison: empty ones, the version line, and "W Default" when asked. */
    private static boolean skip(String s, boolean ignoreDefaultWeight) {
        if (s.isEmpty()) return true;
        if (ignoreDefaultWeight && s.equals("W Default")) return true;
        return s.contains("HepMC::Version");
    }

    public int compareAsciiFiles(String f1, String f2) {
        return compareAsciiFiles(f1, f2, false);
    }

    /**
     * COMPARE_ASCII_FILES: the files line by line, as std::getline gives
     * them, empty and version lines skipped; 0 when they agree, 1 (and the
     * first difference on cout) when not. A line is compared as long as
     * neither file has hit its end, so a file that is a prefix of the other
     * agrees, as in the C++.
     */
    public int compareAsciiFiles(String f1, String f2, boolean ignoreDefaultWeight) {
        try (CInput file1 = CInput.open(path(f1)); CInput file2 = CInput.open(path(f2))) {
            final String[] s1 = {""};
            final String[] s2 = {""};
            int j1 = 0;
            int j2 = 0;
            cout.put("Run comparison").put("\n");
            while (!file1.eof() && !file2.eof()) {
                // the C++ loops for ever when neither file opens; the port stops
                if (file1.fail() && file2.fail()) break;
                for (;;) {
                    j1++;
                    if (!getline(file1, s1)) break;
                    if (!skip(s1[0], ignoreDefaultWeight)) break;
                }
                for (;;) {
                    j2++;
                    if (!getline(file2, s2)) break;
                    if (!skip(s2[0], ignoreDefaultWeight)) break;
                }
                if (!s1[0].equals(s2[0])) {
                    cout.put(j1).put("/").put(j2).put("-th strings are not equal ").put(f1).put(" ").put(f2).put("\n");
                    cout.put("   ->").put(s1[0]).put("<-\n");
                    cout.put("   ->").put(s2[0]).put("<-\n");
                    return 1;
                }
            }
            return 0;
        }
    }

    /** std::getline(in, s): s untouched when the stream was not good, emptied then filled otherwise. */
    private static boolean getline(CInput in, String[] s) {
        if (!in.good()) {
            in.setstate(CInput.FAIL);
            return false;
        }
        final String line = in.getline();
        s[0] = line == null ? "" : line;
        return line != null;
    }

    /** A std::stringstream being read: the text, where reading is, and its state. */
    private static final class StringIn {
        final String text;
        int pos;
        boolean eof;
        boolean fail;

        StringIn(String text) {
            this.text = text;
        }

        int inAvail() {
            return text.length() - pos;
        }

        boolean getline(String[] s) {
            if (eof || fail) {
                fail = true;
                return false;
            }
            final int nl = text.indexOf('\n', pos);
            if (nl < 0) {
                eof = true;
                if (pos == text.length()) {
                    fail = true;
                    s[0] = "";
                    return false;
                }
                s[0] = text.substring(pos);
                pos = text.length();
                return true;
            }
            s[0] = text.substring(pos, nl);
            pos = nl + 1;
            return true;
        }
    }

    /** COMPARE_ASCII_STREAMS: as COMPARE_ASCII_FILES, on two strings, while either has text left. */
    public int compareAsciiStreams(String a, String b) {
        final StringIn file1 = new StringIn(a);
        final StringIn file2 = new StringIn(b);
        final String[] s1 = {""};
        final String[] s2 = {""};
        int j1 = 0;
        int j2 = 0;
        cout.put("Run comparison").put("\n");
        while (file1.inAvail() != 0 || file2.inAvail() != 0) {
            for (;;) {
                j1++;
                if (!file1.getline(s1)) break;
                if (!skip(s1[0], false)) break;
            }
            for (;;) {
                j2++;
                if (!file2.getline(s2)) break;
                if (!skip(s2[0], false)) break;
            }
            if (!s1[0].equals(s2[0])) {
                cout.put(j1).put("/").put(j2).put("-th strings are not equal \n");
                cout.put("   ->").put(s1[0]).put("<-\n");
                cout.put("   ->").put(s2[0]).put("<-\n");
                return 1;
            }
        }
        return 0;
    }
}
