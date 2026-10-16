package com.sphere.core.minuit.validation;

import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.minuit2.validation.Minuit2Tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the TMinuit probe and the scenarios through the Java port and compares
 * what they print with what ROOT's TMinuit printed running the same: standard
 * output, error and exit code, line for line. The references are those of
 * root-master's math/minuit/src/TMinuit.cxx compiled with g++ (-O2,
 * -ffp-contract=off) and run by tmprobe.cxx and tmscript.cxx, the twins of
 * {@link TMinuitProbe} and {@link TMinuitScript}; on Linux the same programs
 * linked to ROOT 6.36's own libMinuit printed the same text. The C library's
 * exp and sin differ between platforms and the port reproduces the platform
 * it runs on, so each platform has its references.
 */
public final class TMinuitTests {

    private TMinuitTests() {
    }

    /** One comparison. */
    public record Outcome(String name, boolean passed, String firstDifference, int lines, int doubles, double millis,
                          String error) {
    }

    /** What a run printed. */
    public record Run(String stdout, String stderr, int exit, double millis, String error) {
    }

    /** The reference outputs and the scenarios of a platform: file name -&gt; text. */
    public static final class References {
        private final Map<String, String> files;
        private final String platform;

        References(String platform, Map<String, String> files) {
            this.platform = platform;
            this.files = files;
        }

        public String platform() {
            return platform;
        }

        String get(String name) {
            return files.get(name);
        }

        /** The runs: the probe, then the scenarios (Name.txt) in alphabetical order. */
        public List<String> runs() {
            final List<String> l = new ArrayList<>();
            l.add("Probe");
            final TreeSet<String> s = new TreeSet<>();
            for (String f : files.keySet()) if (f.endsWith(".txt")) s.add(f.substring(0, f.length() - 4));
            l.addAll(s);
            return l;
        }

        /** From a folder holding Name.out, Name.err, Name.exit and the scenarios Name.txt. */
        public static References fromDirectory(Path dir, String platform) throws IOException {
            final Map<String, String> m = new HashMap<>();
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p)) m.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.ISO_8859_1));
                }
            }
            return new References(platform, m);
        }

        /** From the zip packed with the classes (refdata/tminuit-PLATFORM.zip), null when there is none. */
        public static References packaged(String platform) throws IOException {
            final InputStream in = TMinuitTests.class.getResourceAsStream("refdata/tminuit-" + platform + ".zip");
            if (in == null) return null;
            final Map<String, String> m = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    final ByteArrayOutputStream b = new ByteArrayOutputStream();
                    z.transferTo(b);
                    m.put(e.getName().substring(e.getName().lastIndexOf('/') + 1), b.toString(StandardCharsets.ISO_8859_1));
                }
            }
            return new References(platform, m);
        }
    }

    /** The platform of this machine, as the references name it (those of Minuit2's). */
    public static String platform() {
        return Minuit2Tests.platform();
    }

    /** Runs the probe or a scenario as its own process would: the random seed at its start, outputs captured. */
    public static synchronized Run execute(String name, References refs) {
        final ByteArrayOutputStream ob = new ByteArrayOutputStream();
        final ByteArrayOutputStream eb = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(ob, false, StandardCharsets.ISO_8859_1);
        final PrintStream err = new PrintStream(eb, false, StandardCharsets.ISO_8859_1);
        final long t0 = System.nanoTime();
        int exit = 0;
        String error = null;
        try {
            if (name.equals("Probe")) {
                TMinuitProbe.run(out, err);
            } else {
                final String script = refs == null ? null : refs.get(name + ".txt");
                if (script == null) throw new IllegalArgumentException("no scenario " + name);
                TMinuitScript.run(new StringReader(script), out, err);
            }
        } catch (RuntimeException | IOException | StackOverflowError e) {
            exit = -999;
            error = e.toString();
        }
        out.flush();
        err.flush();
        return new Run(ob.toString(StandardCharsets.ISO_8859_1), eb.toString(StandardCharsets.ISO_8859_1), exit,
            (System.nanoTime() - t0) / 1e6, error);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n");
    }

    /** The first line where two texts differ, or null. */
    static String firstDifference(String java, String cpp) {
        final String[] a = normalize(java).split("\n", -1);
        final String[] b = normalize(cpp).split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final String x = i < a.length ? a[i] : "<end>";
            final String y = i < b.length ? b[i] : "<end>";
            if (!x.equals(y)) return "line " + (i + 1) + ": java [" + x + "] c++ [" + y + "]";
        }
        return null;
    }

    /** How many results the text gives as the 64 bits of a double ("name=0123456789abcdef"). */
    private static int doubles(String text) {
        int n = 0;
        for (String l : normalize(text).split("\n")) {
            final int eq = l.lastIndexOf('=');
            if (eq >= 0 && l.length() - eq - 1 == 16 && l.substring(eq + 1).chars().allMatch(c -> Character.digit(c, 16) >= 0)) n++;
        }
        return n;
    }

    public static Outcome compare(String name, References refs) {
        final Run r = execute(name, refs);
        final String cppOut = refs.get(name + ".out");
        final String cppErr = refs.get(name + ".err");
        final String cppExit = refs.get(name + ".exit");
        final int exitCpp = cppExit == null ? -1 : Integer.parseInt(cppExit.trim());
        String diff = cppOut == null ? "no reference output" : firstDifference(r.stdout(), cppOut);
        if (diff == null && cppErr != null) {
            final String d = firstDifference(r.stderr(), cppErr);
            if (d != null) diff = "stderr " + d;
        }
        if (diff == null && r.exit() != exitCpp) diff = "exit " + r.exit() + " vs " + exitCpp;
        final int lines = normalize(r.stdout()).split("\n", -1).length - 1;
        return new Outcome(name, diff == null && r.error() == null, diff, lines, doubles(r.stdout()), r.millis(), r.error());
    }

    public static List<Outcome> compareAll(String filter, References refs) {
        final List<Outcome> l = new ArrayList<>();
        for (String n : refs.runs()) {
            if (filter == null || filter.isEmpty() || n.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                l.add(compare(n, refs));
            }
        }
        return l;
    }

    public static String line(Outcome o) {
        final StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%s %-10s %5d lines %4d doubles  (%.0f ms)",
            o.passed() ? "IDENTICAL" : o.error() != null ? "ERROR    " : "DIFFERS  ", o.name(), o.lines(), o.doubles(), o.millis()));
        if (o.error() != null) s.append("\n      ").append(o.error());
        if (!o.passed() && o.firstDifference() != null) s.append("\n      first: ").append(o.firstDifference());
        return s.toString();
    }

    /** java ... TMinuitTests [filter] [reference folder]: runs and compares. */
    public static void main(String[] args) throws IOException {
        final String filter = args.length > 0 ? args[0] : "";
        final References refs = args.length > 1 ? References.fromDirectory(Path.of(args[1]), platform())
            : References.packaged(platform());
        if (refs == null) {
            System.out.println("no references for " + platform());
            return;
        }
        System.out.println("TMinuit (Java port) against ROOT's TMinuit on " + refs.platform() + "; math: " + NativeMath.describe());
        int ok = 0;
        int n = 0;
        for (Outcome o : compareAll(filter, refs)) {
            n++;
            if (o.passed()) ok++;
            System.out.println(line(o));
        }
        System.out.println(ok + " of " + n + " agree with the C++");
    }
}
