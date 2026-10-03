package com.sphere.core.bridge;

import com.sphere.core.rootbackend.RootPdfAlphaS;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * One PDF, every engine, the same points: does each language give the numbers
 * Java gives?
 *
 * A result that depends on which program interpolated the grid is not a
 * result. The bridge's claim is that it does not: every reader runs the same
 * arithmetic on the same bits. This checks the claim instead of asserting it.
 * Points are drawn inside the grid, on its knots, across its seams and outside
 * it where LHAPDF's continuation takes over; each engine evaluates them and
 * writes its answers back through SPX; they are compared with Java's, bit by
 * bit, and each engine's time per evaluation is measured on the same points.
 */
public final class BridgeCrossCheck {

    /** One engine's verdict. */
    public record Verdict(String engine, boolean ran, String note, int points, int identical, double maxRelative,
                          int asPoints, int asIdentical, double asMaxRelative, double nsPerEval, long millis) {
    }

    private BridgeCrossCheck() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static final List<String> ENGINES = List.of("java", "c++", "fortran", "julia", "python", "root");

    /**
     * Runs the check.
     *
     * @param cling a way to run a line in ROOT's interpreter, or null when ROOT is not up
     */
    public static List<Verdict> run(RootPdfSet set, int points, long seed, List<String> engines,
                                    SettingsManager settings, Function<String, String> cling) throws IOException {
        final Path work = Bridge.folder().resolve("crosscheck");
        Files.createDirectories(work);
        final Path pdf = SpxExport.pdf(set, Bridge.folder().resolve(SpxExport.safeName(set.name()) + ".spx"));

        // --- the points -----------------------------------------------------
        final RootPdfGrid g = set.member(0);
        final int[] flavors = g.flavors();
        final double[] xs = g.xKnotValues();
        final double[] q2s = g.q2KnotValues();
        final Random random = new Random(seed);
        final int n = Math.max(10, points);
        final double[] pid = new double[n];
        final double[] x = new double[n];
        final double[] q2 = new double[n];
        final double[] member = new double[n];
        final double lxMin = Math.log(g.xMin());
        final double lxMax = Math.log(g.xMax());
        final double lqMin = Math.log(g.q2Min());
        final double lqMax = Math.log(g.q2Max());
        for (int k = 0; k < n; k++) {
            pid[k] = flavors[random.nextInt(flavors.length)];
            member[k] = random.nextInt(set.memberCount());
            final int region = k % 20;
            if (region == 0) {                      // exactly on a knot
                x[k] = xs[random.nextInt(xs.length)];
                q2[k] = q2s[random.nextInt(q2s.length)];
            } else if (region == 1) {               // below the smallest x
                x[k] = g.xMin() * Math.exp(-random.nextDouble() * Math.log(100));
                q2[k] = Math.exp(lqMin + random.nextDouble() * (lqMax - lqMin));
            } else if (region == 2) {               // below the smallest scale
                x[k] = Math.exp(lxMin + random.nextDouble() * (lxMax - lxMin) * 0.999);
                q2[k] = g.q2Min() * Math.exp(-random.nextDouble() * Math.log(10));
            } else if (region == 3) {               // above the largest scale
                x[k] = Math.exp(lxMin + random.nextDouble() * (lxMax - lxMin) * 0.999);
                q2[k] = g.q2Max() * Math.exp(random.nextDouble() * Math.log(10));
            } else {
                x[k] = Math.exp(lxMin + random.nextDouble() * (lxMax - lxMin));
                q2[k] = Math.exp(lqMin + random.nextDouble() * (lqMax - lqMin));
            }
        }
        final RootPdfAlphaS alpha = set.alphaS();
        final int na = alpha == null ? 0 : Math.max(10, n / 10);
        final double[] asQ2 = new double[na];
        for (int k = 0; k < na; k++) asQ2[k] = Math.exp(lqMin + random.nextDouble() * (lqMax - lqMin));

        final Path pointsFile = new Spx.Writer(Spx.Kind.TABLE, "points")
            .text("meta", "Title: points\nSet: " + set.name() + "\n")
            .f64("pid", pid).f64("x", x).f64("q2", q2).f64("member", member).f64("as_q2", asQ2)
            .write(work.resolve("points.spx"));

        // --- Java, the reference --------------------------------------------
        final double[] reference = new double[n];
        for (int k = 0; k < n; k++) reference[k] = set.member((int) member[k]).xfxQ2((int) pid[k], x[k], q2[k]);
        final double[] asReference = new double[na];
        for (int k = 0; k < na; k++) asReference[k] = alpha.alphasQ2(asQ2[k]);
        final List<Verdict> out = new ArrayList<>();
        if (engines.contains("java")) {
            int rounds = 0;
            double sink = 0;
            // Warmed first, as Julia is by its own first pass: the JIT has to
            // have compiled the interpolator for the time to mean anything.
            final long warm = System.nanoTime();
            while (System.nanoTime() - warm < 200_000_000L) {
                for (int k = 0; k < n; k++) sink += set.member((int) member[k]).xfxQ2((int) pid[k], x[k], q2[k]);
            }
            final long start = System.nanoTime();
            do {
                for (int k = 0; k < n; k++) sink += set.member((int) member[k]).xfxQ2((int) pid[k], x[k], q2[k]);
                rounds++;
            } while (System.nanoTime() - start < 200_000_000L && rounds < 1000);
            final double ns = (System.nanoTime() - start) / ((double) n * rounds) + (sink == 42 ? 1e-300 : 0);
            out.add(new Verdict("java", true, "reference (" + Runtime.version().feature() + ")", n, n, 0, na, na, 0,
                ns, 0));
        }

        // --- the others, side by side ----------------------------------------
        final Map<String, java.util.concurrent.Callable<Verdict>> jobs = new LinkedHashMap<>();
        for (String engine : engines) {
            if (engine.equals("java")) continue;
            final Path result = work.resolve("result-" + engine.replace("+", "p") + ".spx");
            jobs.put(engine, () -> {
                Files.deleteIfExists(result);
                final long started = System.nanoTime();
                final String note;
                try {
                    note = evaluate(engine, pdf, pointsFile, result, settings, cling);
                } catch (IOException | RuntimeException failed) {
                    return new Verdict(engine, false, failed.getMessage(), 0, 0, 0, 0, 0, 0, 0, 0);
                }
                final long millis = (System.nanoTime() - started) / 1_000_000;
                if (note != null && !Files.isRegularFile(result)) {
                    return new Verdict(engine, false, note, 0, 0, 0, 0, 0, 0, 0, millis);
                }
                return compare(engine, result, reference, asReference, millis);
            });
        }
        // One engine at a time: they would otherwise share the cores, and the
        // time each reports would be the scheduler's rather than its own.
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Map<String, Future<Verdict>> running = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.concurrent.Callable<Verdict>> e : jobs.entrySet()) {
                running.put(e.getKey(), pool.submit(e.getValue()));
            }
            for (Map.Entry<String, Future<Verdict>> e : running.entrySet()) {
                try {
                    out.add(e.getValue().get());
                } catch (java.util.concurrent.ExecutionException failed) {
                    out.add(new Verdict(e.getKey(), false, String.valueOf(failed.getCause()), 0, 0, 0, 0, 0, 0, 0, 0));
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /**
     * Has one engine evaluate the points. Answers null when it ran, or why it
     * could not be run at all (no compiler, no interpreter).
     */
    private static String evaluate(String engine, Path pdf, Path points, Path result, SettingsManager settings,
                                   Function<String, String> cling) throws IOException {
        final Path lib = Bridge.libraries();
        switch (engine) {
            case "c++" -> {
                final String cxx = Bridge.cppCompiler(settings);
                if (cxx == null) return "no C++ compiler (GPP_DIR)";
                final Path exe = cppChecker(cxx, lib);
                check(Bridge.run(List.of(exe.toString(), pdf.toString(), points.toString(), result.toString()),
                    null, 120, Bridge.parentOf(cxx)), "C++");
            }
            case "fortran" -> {
                final String fc = Bridge.fortranCompiler(settings);
                if (fc == null) return "no Fortran compiler (ENV_FC / FORTRAN_DIR)";
                final Path exe = fortranChecker(fc);
                check(Bridge.run(List.of(exe.toString(), pdf.toString(), points.toString(), result.toString()),
                    null, 120, Bridge.parentOf(fc)), "Fortran");
            }
            case "julia" -> {
                final String julia = Bridge.julia(settings);
                if (julia == null) return "no Julia (JULIA_DIR)";
                // A driver file rather than -e: Windows passes a quote inside
                // an argument through mangled, and the include needs quotes.
                final Path driver = Bridge.buildFolder().resolve("crosscheck.jl");
                Files.createDirectories(driver.getParent());
                Files.writeString(driver, "include(joinpath(ARGS[1], \"SphereSPX.jl\"))\n"
                    + "SphereSPX.crosscheck(ARGS[2], ARGS[3], ARGS[4])\n");
                check(Bridge.run(List.of(julia, "--startup-file=no", driver.toString(), lib.toString(), pdf.toString(),
                    points.toString(), result.toString()), null, 300, null), "Julia");
            }
            case "python" -> {
                final String python = Bridge.python(settings);
                if (python == null) return "no Python (PYTHON_EXEC)";
                check(Bridge.run(List.of(python, lib.resolve("sphere_spx.py").toString(), "crosscheck", pdf.toString(),
                    points.toString(), result.toString()), null, 300, null), "Python");
            }
            case "root" -> {
                if (cling == null) return "ROOT is not running (':root' starts it)";
                final String include = slash(lib.resolve("sphere_spx.hpp"));
                cling.apply("gInterpreter->Declare(\"#include \\\"" + include + "\\\"\")");
                final String answer = cling.apply("spx::crosscheck(\"" + slash(pdf) + "\", \"" + slash(points)
                    + "\", \"" + slash(result) + "\", \"ROOT\")");
                if (!Files.isRegularFile(result)) return "ROOT answered: " + answer;
            }
            default -> {
                return "unknown engine " + engine;
            }
        }
        return null;
    }

    private static void check(Bridge.Outcome outcome, String who) throws IOException {
        if (!outcome.ok()) {
            throw new IOException(who + " failed (" + outcome.status() + "): " + tail(outcome.output()));
        }
    }

    private static String tail(String text) {
        final String[] lines = text.split("\n");
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 4), lines.length));
    }

    static String slash(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    /** The C++ checker, compiled once per version of the header. */
    private static Path cppChecker(String cxx, Path lib) throws IOException {
        final String key = Bridge.digest(cxx, new String(Bridge.carried("sphere_spx.hpp"), StandardCharsets.UTF_8));
        final Path dir = Bridge.buildFolder().resolve("cpp-" + key.substring(0, 12));
        final Path exe = dir.resolve(windows() ? "spx_check.exe" : "spx_check");
        if (Files.isRegularFile(exe)) return exe;
        Files.createDirectories(dir);
        final Path main = dir.resolve("spx_check.cpp");
        Files.writeString(main, """
            #include "sphere_spx.hpp"
            #include <cstdio>
            int main(int argc, char** argv) {
                if (argc < 4) { std::puts("usage: spx_check <pdf.spx> <points.spx> <out.spx>"); return 1; }
                try { spx::crosscheck(argv[1], argv[2], argv[3]); }
                catch (const std::exception& e) { std::fprintf(stderr, "%s\\n", e.what()); return 2; }
                return 0;
            }
            """);
        final List<String> command = new ArrayList<>(List.of(cxx, "-std=c++17", "-O2", "-I" + lib, main.toString(),
            "-o", exe.toString()));
        if (windows()) command.add("-static");
        final Bridge.Outcome built = Bridge.run(command, dir, 300, Bridge.parentOf(cxx));
        if (!built.ok()) throw new IOException("the C++ checker did not compile: " + tail(built.output()));
        return exe;
    }

    /** The Fortran checker, compiled once per version of the module. */
    private static Path fortranChecker(String fc) throws IOException {
        final Path objects = Bridge.fortranObjects(fc);
        final Path exe = objects.resolve(windows() ? "spx_check.exe" : "spx_check");
        if (Files.isRegularFile(exe)) return exe;
        final Path main = objects.resolve("spx_check.f90");
        Files.writeString(main, """
            program spx_check
              use sphere_spx
              implicit none
              character(len=4096) :: pdf, points, out
              call get_command_argument(1, pdf)
              call get_command_argument(2, points)
              call get_command_argument(3, out)
              call spx_crosscheck(trim(pdf), trim(points), trim(out))
            end program spx_check
            """);
        final List<String> command = new ArrayList<>(List.of(fc, "-O2", "-I" + objects, main.toString(),
            objects.resolve("sphere_spx.o").toString(), "-o", exe.toString()));
        if (windows()) command.add("-static");
        final Bridge.Outcome built = Bridge.run(command, objects, 300, Bridge.parentOf(fc));
        if (!built.ok()) throw new IOException("the Fortran checker did not compile: " + tail(built.output()));
        return exe;
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Verdict compare(String engine, Path result, double[] reference, double[] asReference, long millis)
            throws IOException {
        try (Spx.Reader r = new Spx.Reader(result)) {
            final double[] xf = r.f64("xf");
            final double[] as = r.f64("as");
            final double ns = r.f64("ns_per_eval")[0];
            final String name = r.meta().getOrDefault("Engine", engine);
            if (xf.length != reference.length || as.length != asReference.length) {
                return new Verdict(engine, false, "answered " + xf.length + " values for " + reference.length,
                    0, 0, 0, 0, 0, 0, 0, millis);
            }
            final double[] xfStats = stats(xf, reference);
            final double[] asStats = stats(as, asReference);
            return new Verdict(engine, true, name, xf.length, (int) xfStats[0], xfStats[1], as.length,
                (int) asStats[0], asStats[1], ns, millis);
        }
    }

    /** How many values are the same double, and the largest relative difference among the others. */
    private static double[] stats(double[] got, double[] want) {
        int same = 0;
        double worst = 0;
        for (int k = 0; k < got.length; k++) {
            if (Double.doubleToLongBits(got[k]) == Double.doubleToLongBits(want[k])) {
                same++;
                continue;
            }
            final double scale = Math.max(Math.abs(want[k]), 1e-300);
            worst = Math.max(worst, Double.isNaN(got[k]) ? Double.POSITIVE_INFINITY : Math.abs(got[k] - want[k]) / scale);
        }
        return new double[]{same, worst};
    }
}
