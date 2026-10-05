package com.sphere.core.hepmc3.validation;

import com.sphere.core.hepmc3.Setup;
import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CRand;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs the ported test programs as ctest runs the C++ ones, each in a
 * directory of its own holding its inputs, and compares everything the run
 * gives with the C++ run: the value main returns, the text on cout and on
 * cerr, and every file written, line for line (line ends aside).
 */
public final class Validator {

    private static final Pattern POINTER = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern DURATION = Pattern.compile("[0-9]+\\.[0-9]+ms");
    /** The number of a HEPEVT row and its parents' numbers (the type kept). */
    private static final Pattern HEPEVT_ROW = Pattern.compile("^\\s*\\d+(\\s+-?\\d+)\\s+\\d+\\s+-\\s+\\d+");

    private Validator() {
    }

    /** What the port printed, returned and wrote. */
    public record Run(int exit, List<String> stdout, List<String> stderr, Path dir, String error, double millis) {
    }

    /** One comparison. */
    public record Outcome(TestProgram test, boolean passed, int exitJava, Integer exitCpp, String stdout, String stderr,
                          int filesCompared, List<String> differingFiles, String firstDifference, String error,
                          double millis) {
    }

    /**
     * Runs the port in a fresh directory holding its inputs; the directory is
     * left for the caller (deleted by {@link #run(TestProgram)}).
     */
    public static Run execute(TestProgram t, References refs) throws IOException {
        final Path dir = Files.createTempDirectory("hepmc3-" + t.id() + "-");
        for (String in : t.inputs()) {
            final byte[] b = refs.input(in);
            if (b == null) throw new IOException("input " + in + " is not among the references");
            Files.write(dir.resolve(in), b);
        }
        final List<String> out = Collections.synchronizedList(new ArrayList<>());
        final List<String> err = Collections.synchronizedList(new ArrayList<>());
        final StdStreams streams = new StdStreams(out::add, err::add);
        final byte[] cin = t.name().equals("testReaderFactory5") ? refs.input("inputReaderFactory5.hepmc") : new byte[0];
        final TestContext ctx = new TestContext(dir, t.args(), new ByteArrayInputStream(cin == null ? new byte[0] : cin));
        final long t0 = System.nanoTime();
        int exit;
        String error = null;
        synchronized (Validator.class) {
            final boolean pe = Setup.printErrors();
            final boolean pw = Setup.printWarnings();
            final int el = Setup.errorsLevel();
            final int wl = Setup.warningsLevel();
            final int dl = Setup.debugLevel();
            final CRand.Library lib = CRand.current().library();
            try {
                // a fresh process: default Setup, rand() from seed 1
                Setup.setPrintErrors(true);
                Setup.setPrintWarnings(true);
                Setup.setErrorsLevel(1000);
                Setup.setWarningsLevel(750);
                Setup.setDebugLevel(5);
                CRand.reset(CRand.Library.MSVC);
                exit = StdStreams.with(streams, () -> CFiles.in(dir, () -> t.body().run(ctx)));
            } catch (Throwable e) {
                exit = 3;
                error = "terminate called after " + e.getClass().getSimpleName() + ": " + e.getMessage();
                streams.err().flush();
            } finally {
                Setup.setPrintErrors(pe);
                Setup.setPrintWarnings(pw);
                Setup.setErrorsLevel(el);
                Setup.setWarningsLevel(wl);
                Setup.setDebugLevel(dl);
                CRand.reset(lib);
            }
        }
        return new Run(exit, new ArrayList<>(out), new ArrayList<>(err), dir, error, (System.nanoTime() - t0) / 1e6);
    }

    public static Outcome run(TestProgram t) {
        final References refs;
        try {
            refs = References.get();
        } catch (IOException e) {
            return new Outcome(t, false, -1, null, "", "", 0, List.of(), null, e.getMessage(), 0);
        }
        Run r = null;
        try {
            r = execute(t, refs);
            return compare(t, r, refs);
        } catch (IOException e) {
            return new Outcome(t, false, -1, null, "", "", 0, List.of(), null, e.getMessage(), 0);
        } finally {
            if (r != null) delete(r.dir());
        }
    }

    private static Outcome compare(TestProgram t, Run r, References refs) throws IOException {
        final String id = t.id();
        final TestProgram.Rules rules = t.rules();
        if (!rules.reference() || !refs.hasRun(id)) {
            final boolean ok = r.error() == null && r.exit() == 0;
            return new Outcome(t, ok, r.exit(), null, "self-check", "self-check", 0, List.of(),
                ok ? null : "main returned " + r.exit(), r.error(), r.millis());
        }
        final int cppExit = Integer.parseInt(refs.text("refs/" + id + "/.exit").strip());
        String first = null;
        boolean passed = r.error() == null && r.exit() == cppExit;
        if (r.exit() != cppExit) first = "main returned " + r.exit() + ", the C++ " + cppExit;

        final String so = compareText(rules.stdout(), r.stdout(), lines(refs.text("refs/" + id + "/.stdout")));
        final String se = compareText(rules.stderr(), r.stderr(), lines(refs.text("refs/" + id + "/.stderr")));
        if (!agrees(so)) {
            passed = false;
            if (first == null) first = "stdout: " + so;
        }
        if (!agrees(se)) {
            passed = false;
            if (first == null) first = "stderr: " + se;
        }
        final List<String> differing = new ArrayList<>();
        int compared = 0;
        for (String name : refs.outputs(id)) {
            compared++;
            final Path p = r.dir().resolve(name);
            if (!Files.isRegularFile(p)) {
                differing.add(name + " (not written)");
                passed = false;
                if (first == null) first = name + " was not written";
                continue;
            }
            final List<String> got = lines(Files.readString(p, StandardCharsets.ISO_8859_1));
            final List<String> want = lines(refs.text("refs/" + id + "/" + name));
            final String d = firstDifference(got, want);
            if (d != null) {
                differing.add(name);
                passed = false;
                if (first == null) first = name + ": " + d;
            }
        }
        return new Outcome(t, passed, r.exit(), cppExit, so, se, compared, differing, first, r.error(), r.millis());
    }

    private static boolean agrees(String verdict) {
        return verdict.startsWith("identical") || verdict.equals("not compared");
    }

    private static String compareText(TestProgram.Output rule, List<String> got, List<String> want) {
        switch (rule) {
            case IGNORE:
                return "not compared";
            case SORTED: {
                final List<String> a = new ArrayList<>(got);
                final List<String> b = new ArrayList<>(want);
                Collections.sort(a);
                Collections.sort(b);
                final String d = firstDifference(a, b);
                return d == null ? "identical once sorted" : d;
            }
            case ROWS_ANY_ORDER: {
                final List<String> a = mask(got, HEPEVT_ROW, "#$1 # - #");
                final List<String> b = mask(want, HEPEVT_ROW, "#$1 # - #");
                Collections.sort(a);
                Collections.sort(b);
                final String d = firstDifference(a, b);
                return d == null ? "identical rows in another order" : d;
            }
            case POINTERS: {
                final String d = firstDifference(mask(got, POINTER, "0x#"), mask(want, POINTER, "0x#"));
                return d == null ? "identical but for addresses" : d;
            }
            case TIMES: {
                final String d = firstDifference(mask(got, DURATION, "#ms"), mask(want, DURATION, "#ms"));
                return d == null ? "identical but for durations" : d;
            }
            default: {
                final String d = firstDifference(got, want);
                return d == null ? "identical" : d;
            }
        }
    }

    private static List<String> mask(List<String> l, Pattern p, String by) {
        final List<String> out = new ArrayList<>(l.size());
        for (String s : l) out.add(p.matcher(s).replaceAll(by));
        return out;
    }

    /** Null when the lists agree, else where and how they first differ. */
    static String firstDifference(List<String> got, List<String> want) {
        final int n = Math.min(got.size(), want.size());
        for (int i = 0; i < n; i++) {
            if (!got.get(i).equals(want.get(i))) {
                return "line " + (i + 1) + "\n      Java: " + got.get(i) + "\n      C++ : " + want.get(i);
            }
        }
        if (got.size() != want.size()) {
            return got.size() + " lines, the C++ " + want.size()
                + (got.size() > n ? "; first extra: " + got.get(n) : "; first missing: " + want.get(n));
        }
        return null;
    }

    /** The lines of a text, line ends of either kind, without the empty one after the final newline. */
    static List<String> lines(String text) {
        final List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int start = 0;
        final int len = text.length();
        while (start < len) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) nl = len;
            int end = nl;
            if (end > start && text.charAt(end - 1) == '\r') end--;
            out.add(text.substring(start, end));
            start = nl + 1;
        }
        return out;
    }

    static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a file still open on Windows: left in the temporary directory
                }
            });
        } catch (IOException ignored) {
            // nothing to clean
        }
    }
}
