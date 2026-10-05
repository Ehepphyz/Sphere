package com.sphere.core.minuit2.validation;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.CRand;
import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.minuit2.Minuit2Minimizer;
import com.sphere.core.minuit2.MnPlot;
import com.sphere.core.minuit2.MnPrint;
import com.sphere.core.minuit2.MnParameterTransformation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the Java ports of Minuit2's test programs and compares what they
 * print with what the C++ programs printed: their standard output and error
 * text, line for line. The references are those of a C++ build of
 * root-master's math/minuit2 (g++, -O2, -ffp-contract=off) on the platform
 * named: the C library's rand(), exp, log... and long double differ between
 * platforms, and the port reproduces the platform it runs on.
 */
public final class Minuit2Tests {

    private Minuit2Tests() {
    }

    /** One comparison. */
    public record Outcome(String name, boolean passed, int exitJava, int exitCpp, String firstDifference, int lines,
                          double millis, String error) {
    }

    /** The reference outputs and input files of a platform: name -&gt; text. */
    public static final class References {
        final Map<String, String> files;
        final String platform;

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

        /** From a folder holding Name.out, Name.err, Name.exit and the input files. */
        public static References fromDirectory(Path dir, String platform) throws IOException {
            final Map<String, String> m = new HashMap<>();
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p)) m.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.ISO_8859_1));
                }
            }
            return new References(platform, m);
        }

        /** From the zip packed with the classes (refdata/minuit2-PLATFORM.zip), null when there is none. */
        public static References packaged(String platform) throws IOException {
            final InputStream in = Minuit2Tests.class.getResourceAsStream("refdata/minuit2-" + platform + ".zip");
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

    /** The platform of this machine, as the references name it. */
    public static String platform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows-mingw";
        if (os.contains("mac")) return "macos-clang";
        return "linux-gcc";
    }

    /** The text a program prints: its standard output, error, and exit code. */
    public record Run(String stdout, String stderr, int exit, double millis, String error) {
    }

    /** Runs a program as a process of its own would: rand() at seed 1, print level 0, outputs captured. */
    public static synchronized Run execute(Programs.Program p, References refs) {
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        final COStream cout = new COStream(out);
        final COStream cerr = new COStream(err);
        CRand.reset(platform().startsWith("windows") ? CRand.Library.MSVC : CRand.Library.GLIBC);
        final int prevLevel = MnPrint.setGlobalLevel(0);
        MnPrint.setSink(line -> err.append(line).append('\n'));
        MnPlot.setOutput(out::append);
        Minuit2Minimizer.setStdout(out::append);
        final long t0 = System.nanoTime();
        int exit;
        String error = null;
        try {
            exit = p.main().apply(new Programs.Io() {
                @Override
                public COStream cout() {
                    return cout;
                }

                @Override
                public COStream cerr() {
                    return cerr;
                }

                @Override
                public String input(String name) {
                    return refs == null ? null : refs.get(name);
                }
            });
        } catch (RuntimeException | StackOverflowError e) {
            exit = -999;
            error = e.toString();
        } finally {
            MnPrint.setSink(null);
            MnPlot.setOutput(s -> {
                System.out.print(s);
                System.out.flush();
            });
            Minuit2Minimizer.setStdout(s -> {
                System.out.print(s);
                System.out.flush();
            });
            MnPrint.setGlobalLevel(prevLevel);
        }
        return new Run(out.toString(), err.toString(), exit, (System.nanoTime() - t0) / 1e6, error);
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

    public static Outcome compare(Programs.Program p, References refs) {
        final Run r = execute(p, refs);
        final String cppOut = refs.get(p.name() + ".out");
        final String cppErr = refs.get(p.name() + ".err");
        final String cppExit = refs.get(p.name() + ".exit");
        final int exitCpp = cppExit == null ? -1 : Integer.parseInt(cppExit.trim());
        String diff = cppOut == null ? "no reference output" : firstDifference(r.stdout(), cppOut);
        if (diff == null && cppErr != null) {
            final String d = firstDifference(r.stderr(), cppErr);
            if (d != null) diff = "stderr " + d;
        }
        if (diff == null && r.exit() != exitCpp) diff = "exit " + r.exit() + " vs " + exitCpp;
        final int lines = normalize(r.stdout()).split("\n", -1).length;
        return new Outcome(p.name(), diff == null && r.error() == null, r.exit(), exitCpp, diff, lines, r.millis(), r.error());
    }

    public static List<Outcome> compareAll(String filter, References refs) {
        final List<Outcome> l = new ArrayList<>();
        for (Programs.Program p : Programs.all()) {
            if (filter == null || filter.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                l.add(compare(p, refs));
            }
        }
        return l;
    }

    public static String line(Outcome o) {
        final StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%s %-14s %4d lines  (%.0f ms)",
            o.passed() ? "IDENTICAL" : o.error() != null ? "ERROR    " : "DIFFERS  ", o.name(), o.lines(), o.millis()));
        if (o.error() != null) s.append("\n      ").append(o.error());
        if (!o.passed() && o.firstDifference() != null) s.append("\n      first: ").append(o.firstDifference());
        return s.toString();
    }

    /** java ... Minuit2Tests [filter] [reference folder]: runs and compares. */
    public static void main(String[] args) throws IOException {
        final String filter = args.length > 0 ? args[0] : "";
        final References refs = args.length > 1 ? References.fromDirectory(Path.of(args[1]), platform())
            : References.packaged(platform());
        if (refs == null) {
            System.out.println("no references for " + platform());
            return;
        }
        System.out.println("Minuit2 (Java port) against the C++ on " + refs.platform() + "; math: " + NativeMath.describe()
            + "; long double: " + MnParameterTransformation.longDouble());
        int ok = 0;
        int n = 0;
        for (Outcome o : compareAll(filter, refs)) {
            n++;
            if (o.passed()) ok++;
            System.out.println(line(o));
        }
        System.out.println(ok + " of " + n + " agree with the C++");
    }

    /** For the dump option: the program's own text. */
    static Function<Programs.Program, String> dump(References refs) {
        return p -> execute(p, refs).stdout();
    }
}
