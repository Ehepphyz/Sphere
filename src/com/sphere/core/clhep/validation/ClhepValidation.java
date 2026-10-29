package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.genfun.Gsl;
import com.sphere.core.hepmc3.cxx.CRand;
import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ':clhep validate': the port against the C++, line for line and bit for bit. Each reference program
 * (validation.*Reference) is the Java twin of a C++ program built against CLHEP that prints every double as
 * its 64 bits; CLHEP's own test programs, ported ({@link ClhepTests}), print on cout and cerr what the C++
 * tests print, write the files they write and end with their exit codes. The C++ outputs are in the jar,
 * refdata/clhep-refs.zip: those of CLHEP built with MinGW-w64 (g++ 16, UCRT) under mingw/, and those of CLHEP
 * built on Linux (g++ 12, glibc) under linux/ (for the reference programs, as the lines that differ): the C
 * library's mathematical functions differ in their last bits, and the port calls this machine's
 * ({@link NativeMath}), so the outputs to compare with are those of its system. The random engines, and the
 * tests that run them all (testRandom, testBug73093, testInstanceRestore, testVectorSave, testEngineCopy), are
 * compared with Linux's outputs, rand() being glibc's for them: MinGW's 32-bit long makes one of its engines
 * (RanshiEngine) undefined, and testInstanceRestore corrupts its heap there. The long test (testRotationAxis)
 * runs when named or with "all". The property clhep.refdir names a folder of the same layout instead.
 */
public final class ClhepValidation {

    private static final String RESOURCE = "/com/sphere/core/clhep/validation/refdata/clhep-refs.zip";

    /** What a check compares, and against which system's outputs. */
    private record Check(String id, String what, String file, Supplier<String> java, boolean linuxOnly, boolean gsl) {
    }

    private static final Map<String, Check> CHECKS = new LinkedHashMap<>();

    static {
        add(new Check("vector", "Vector: three- and four-vectors, rotations, boosts, Euler and axis angles", "vector_ref",
            () -> new VectorReference().output(), false, false));
        add(new Check("engines", "Random: the 14 engines, their seeds, saved and restored states", "engines_ref",
            () -> new EnginesReference(CRand.Library.GLIBC).output(), true, false));
        add(new Check("dists", "Random: every distribution, static and instance, saved and restored", "dists_ref",
            () -> new DistsReference().output(), false, false));
        add(new Check("evaluator", "Evaluator: expressions, functions, units, errors and errno", "evaluator_ref",
            () -> new EvaluatorReference().output(), false, false));
        add(new Check("geometry", "Geometry: points, vectors, normals, planes, every transformation", "geometry_ref",
            () -> new GeometryReference().output(), false, false));
        add(new Check("matrix", "Matrix: the four matrix classes, inversions, QR, Householder, diagonalization", "matrix_ref",
            () -> new MatrixReference().output(), false, false));
        add(new Check("genfun", "GenericFunctions: every function and derivative, integrals, Runge-Kutta, GSL's", "genfun_ref",
            () -> new GenfunReference().output(), false, true));
        add(new Check("randobj", "RandomObjects and Fields: RandMultiGauss, HepRandomVector, XF", "randobj_ref",
            () -> new RandomObjectsReference().output(), false, false));
    }

    private static void add(Check c) {
        CHECKS.put(c.id, c);
    }

    /** One check's result: how many lines agreed, where the first difference is. */
    public record Outcome(String id, boolean passed, boolean skipped, int lines, int differ, String firstDifference,
                          String note, double millis) {

        /** One line of the report. */
        public String line() {
            if (skipped) return String.format(Locale.ROOT, "%-21s skipped: %s", id, note);
            if (passed) return String.format(Locale.ROOT, "%-21s identical: %d line%s, every bit (%.0f ms)%s", id, lines,
                lines == 1 ? "" : "s", millis, note.isEmpty() ? "" : "; " + note);
            return String.format(Locale.ROOT, "%-21s DIFFERS in %d of %d lines; first: %s%s", id, differ, lines, firstDifference,
                note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    private ClhepValidation() {
    }

    /** The reference programs, then CLHEP's own tests. */
    public static List<String> names() {
        final List<String> out = new ArrayList<>(CHECKS.keySet());
        out.addAll(ClhepTests.PROGRAMS.keySet());
        return out;
    }

    /**
     * The checks a word names: "" all but the long tests, "all" every one, "tests" CLHEP's tests (but the long
     * ones), "references" the reference programs, any other word those whose name contains it.
     */
    public static List<String> matching(String filter) {
        final String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (String id : names()) {
            final boolean test = ClhepTests.PROGRAMS.containsKey(id);
            final boolean isLong = ClhepTests.LONG.containsKey(id);
            final boolean wanted = switch (f) {
                case "" -> !isLong;
                case "all" -> true;
                case "tests" -> test && !isLong;
                case "references" -> !test;
                default -> id.toLowerCase(Locale.ROOT).contains(f);
            };
            if (wanted) out.add(id);
        }
        return out;
    }

    /** The long tests a filter leaves out, each with why it is long. */
    public static Map<String, String> leftOut(String filter) {
        final String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        final Map<String, String> out = new LinkedHashMap<>();
        if (!f.isEmpty() && !f.equals("tests")) return out;
        for (String id : ClhepTests.PROGRAMS.keySet()) {
            if (ClhepTests.LONG.containsKey(id)) out.put(id, ClhepTests.LONG.get(id));
        }
        return out;
    }

    public static String describe(String id) {
        final Check c = CHECKS.get(id);
        if (c != null) return c.what;
        final ClhepTests.Program p = ClhepTests.PROGRAMS.get(id);
        return p == null ? "" : "CLHEP's own " + p.pkg() + " test, ported";
    }

    /* ---- the C++ outputs ---- */

    private static volatile Map<String, byte[]> refs;

    private static Map<String, byte[]> refs() throws IOException {
        Map<String, byte[]> r = refs;
        if (r != null) return r;
        synchronized (ClhepValidation.class) {
            if (refs != null) return refs;
            final String dir = System.getProperty("clhep.refdir");
            refs = dir != null ? fromDirectory(Path.of(dir)) : fromJar();
            return refs;
        }
    }

    public static boolean referencesAvailable() {
        try {
            refs();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<String, byte[]> fromDirectory(Path root) throws IOException {
        final Map<String, byte[]> m = new HashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) m.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return m;
    }

    private static Map<String, byte[]> fromJar() throws IOException {
        try (InputStream s = ClhepValidation.class.getResourceAsStream(RESOURCE)) {
            if (s == null) throw new IOException("the CLHEP references are not in the jar (and clhep.refdir is not set)");
            final Map<String, byte[]> m = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(s)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    z.transferTo(out);
                    m.put(e.getName(), out.toByteArray());
                }
            }
            return m;
        }
    }

    /** Which system's outputs this machine's C library gives: "mingw" (Windows), "linux", or null (none of them). */
    public static String system() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "mingw";
        if (os.contains("linux")) return "linux";
        return null;
    }

    /** The C++ output of a program for a system: MinGW's in full, Linux's from its patch over MinGW's. */
    static List<String> expected(String file, String system) throws IOException {
        final Map<String, byte[]> r = refs();
        final byte[] base = r.get("mingw/" + file + ".out");
        if (base == null) throw new IOException("no C++ output for " + file);
        final List<String> lines = new ArrayList<>(List.of(new String(base, StandardCharsets.ISO_8859_1).replace("\r", "").split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        if (system.equals("linux")) {
            final byte[] patch = r.get("linux/" + file + ".patch");
            if (patch == null) throw new IOException("no Linux output for " + file);
            for (String p : new String(patch, StandardCharsets.ISO_8859_1).split("\n")) {
                if (p.isEmpty()) continue;
                final int tab = p.indexOf('\t');
                final int n = Integer.parseInt(p.substring(0, tab));
                final String text = p.substring(tab + 1);
                if (n < lines.size()) lines.set(n, text);
                else lines.add(text);
            }
        }
        return lines;
    }

    /** Runs a check. Never throws: a failure is its outcome. */
    public static Outcome run(String id) {
        if (ClhepTests.PROGRAMS.containsKey(id)) return runTest(id);
        final Check c = CHECKS.get(id);
        if (c == null) return new Outcome(id, false, true, 0, 0, "", "no such check", 0);
        final String host = system();
        final String system = c.linuxOnly ? "linux" : host;
        if (system == null) {
            return new Outcome(id, false, true, 0, 0, "", "the C++ outputs are MinGW's and glibc's; this machine's C library is "
                + System.getProperty("os.name"), 0);
        }
        if (c.gsl && !Gsl.available()) return new Outcome(id, false, true, 0, 0, "", Gsl.describe(), 0);
        final long t0 = System.nanoTime();
        try {
            final List<String> want = expected(c.file, system);
            // what the programs write on cerr (CLHEP's warnings) is not part of the outputs compared
            final String out = StdStreams.with(StdStreams.NONE, c.java::get);
            final List<String> got = new ArrayList<>(List.of(out.split("\n", -1)));
            if (!got.isEmpty() && got.get(got.size() - 1).isEmpty()) got.remove(got.size() - 1);
            int differ = 0;
            String first = "";
            final int n = Math.max(want.size(), got.size());
            for (int i = 0; i < n; i++) {
                final String w = i < want.size() ? want.get(i) : "<none>";
                final String g = i < got.size() ? got.get(i) : "<none>";
                if (!w.equals(g)) {
                    if (differ == 0) first = "line " + (i + 1) + ": C++ '" + w + "', Java '" + g + "'";
                    differ++;
                }
            }
            String note = c.linuxOnly && !"linux".equals(host) ? "against Linux's outputs, with glibc's rand()" : "";
            if (NativeMath.target() != NativeMath.Target.HOST) {
                note = (note.isEmpty() ? "" : note + "; ") + "portable mathematics: " + NativeMath.describe();
            }
            if (c.gsl) note = (note.isEmpty() ? "" : note + "; ") + Gsl.describe() + " (the references used MSYS2's GSL 2.8, Debian's 2.7.1)";
            return new Outcome(id, differ == 0, false, n, differ, first, note, (System.nanoTime() - t0) / 1e6);
        } catch (Throwable t) {
            return new Outcome(id, false, false, 0, 0, t.getClass().getSimpleName() + ": " + t.getMessage(), "",
                (System.nanoTime() - t0) / 1e6);
        }
    }

    private static List<String> lines(String text) {
        final List<String> l = new ArrayList<>(List.of(text.replace("\r", "").split("\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        return l;
    }

    /**
     * A test of CLHEP's, run as the C++ program ran, with the same standard input: its cout, its cerr, its exit
     * code and every file it wrote compared with the C++'s, line for line.
     */
    private static Outcome runTest(String id) {
        final ClhepTests.Program p = ClhepTests.PROGRAMS.get(id);
        final String host = system();
        final String system = p.linux() ? "linux" : host;
        if (system == null) {
            return new Outcome(id, false, true, 0, 0, "", "CLHEP's tests were recorded with MinGW and glibc; this machine's C library is "
                + System.getProperty("os.name"), 0);
        }
        final long t0 = System.nanoTime();
        try {
            final Map<String, byte[]> r = refs();
            final String base = "tests/" + system + "/" + id;
            final byte[] input = r.get("tests/inputs/" + id + ".input");
            final ClhepTests.Result got = ClhepTests.run(id, input == null ? "" : new String(input, StandardCharsets.ISO_8859_1));
            final Map<String, String> want = new LinkedHashMap<>();
            final Map<String, String> have = new LinkedHashMap<>();
            want.put("cout", new String(r.getOrDefault(base + ".out", new byte[0]), StandardCharsets.ISO_8859_1));
            have.put("cout", got.out());
            want.put("cerr", new String(r.getOrDefault(base + ".err", new byte[0]), StandardCharsets.ISO_8859_1));
            have.put("cerr", got.err());
            for (Map.Entry<String, byte[]> e : r.entrySet()) {
                if (e.getKey().startsWith(base + "/")) {
                    final String name = e.getKey().substring(base.length() + 1);
                    want.put(name, new String(e.getValue(), StandardCharsets.ISO_8859_1));
                    have.put(name, got.files().getOrDefault(name, "<not written>"));
                }
            }
            for (String name : got.files().keySet()) {
                if (!want.containsKey(name)) {
                    want.put(name, "<not written by the C++>");
                    have.put(name, got.files().get(name));
                }
            }
            int lines = 1;
            int differ = 0;
            String first = "";
            final int exit = Integer.parseInt(new String(r.getOrDefault(base + ".exit", "0".getBytes()), StandardCharsets.ISO_8859_1).trim());
            if (exit != got.exit()) {
                differ++;
                first = "exit code: C++ " + exit + ", Java " + got.exit();
            }
            for (Map.Entry<String, String> e : want.entrySet()) {
                final List<String> w = lines(e.getValue());
                final List<String> g = lines(have.get(e.getKey()));
                final int n = Math.max(w.size(), g.size());
                lines += n;
                for (int i = 0; i < n; i++) {
                    final String a = i < w.size() ? w.get(i) : "<none>";
                    final String b = i < g.size() ? g.get(i) : "<none>";
                    if (!a.equals(b)) {
                        if (differ == 0) first = e.getKey() + " line " + (i + 1) + ": C++ '" + a + "', Java '" + b + "'";
                        differ++;
                    }
                }
            }
            String note = p.linux() && !"linux".equals(host) ? "against Linux's outputs (64-bit long, glibc's rand())" : "";
            if (NativeMath.target() != NativeMath.Target.HOST) {
                note = (note.isEmpty() ? "" : note + "; ") + "portable mathematics: " + NativeMath.describe();
            }
            return new Outcome(id, differ == 0, false, lines, differ, first, note, (System.nanoTime() - t0) / 1e6);
        } catch (Throwable t) {
            return new Outcome(id, false, false, 0, 0, t.getClass().getSimpleName() + ": " + t.getMessage(), "",
                (System.nanoTime() - t0) / 1e6);
        }
    }
}
