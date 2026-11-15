package com.sphere.core.bridge;

import com.sphere.core.ml.MlLink;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * TensorFlow, PyTorch and pyhf asked the same questions from every language: does each get the numbers Java gets?
 *
 * The same four requests go through each client (sphere_mlink for C++, Fortran, Julia, Python, and in WSL), whose
 * answers are written back and compared with Java's bit by bit. A value that differs would mean a client that loses
 * digits, or reads a matrix in the other order; the time of a call is each client's own.
 */
public final class BridgeMlinkCheck {

    /** One client's verdict. */
    public record Verdict(String engine, boolean ran, String note, int values, int identical, double maxRelative, double ms) {
    }

    private BridgeMlinkCheck() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static final List<String> ENGINES = List.of("java", "python", "c++", "fortran", "julia", "wsl", "root");

    /** The numbers, in the order every client gives them: PyTorch, TensorFlow, pyhf's two, then a matrix of sines. */
    private static double[] reference() throws IOException {
        final Map<String, MlLink.Array> x = Map.of("x", new MlLink.Array(new double[] {1, 2, 3, 4, 5, 6.5}, new long[] {2, 3}));
        final double[] out = new double[10];
        out[0] = MlLink.eval("torch.linalg.norm(torch.as_tensor(x)).item()", x).number();
        out[1] = MlLink.eval("float(tf.reduce_sum(tf.constant(x) ** 2))", x).number();
        final MlLink.Reply p = MlLink.pyhf(new double[] {5, 10}, new double[] {50, 60}, new double[] {7, 8}, new double[] {53, 65}, 1.0, 0);
        out[2] = p.number("backends.numpy.cls_obs");
        out[3] = p.number("backends.numpy.mu_hat");
        final double[] sines = MlLink.eval("np.sin(x).ravel()", x).array().data();
        System.arraycopy(sines, 0, out, 4, 6);
        return out;
    }

    /**
     * Runs the check.
     *
     * @param cling a way to run a line in ROOT's interpreter, or null when ROOT is not up
     */
    public static List<Verdict> run(List<String> engines, SettingsManager settings, Function<String, String> cling) throws IOException {
        final Path work = Bridge.folder().resolve("crosscheck").resolve("mlink");
        Files.createDirectories(work);
        MlLink.start();
        final double[] want = reference();
        final List<Verdict> out = new ArrayList<>();
        for (String engine : engines) {
            if (engine.equals("java")) {
                final long t0 = System.nanoTime();
                for (int k = 0; k < 20; k++) {
                    MlLink.eval("1+1", Map.of());
                }
                out.add(new Verdict("java", true, "the reference", want.length, want.length, 0, (System.nanoTime() - t0) / 20 / 1e6));
                continue;
            }
            final Path result = work.resolve("result-" + engine.replace("+", "p") + ".spx");
            Files.deleteIfExists(result);
            String note;
            try {
                note = ask(engine, result, settings, cling);
            } catch (IOException | RuntimeException failed) {
                out.add(new Verdict(engine, false, failed.getMessage(), 0, 0, 0, 0));
                continue;
            }
            if (!Files.isRegularFile(result)) {
                out.add(new Verdict(engine, false, note == null ? "no result written" : note, 0, 0, 0, 0));
                continue;
            }
            try (Spx.Reader r = new Spx.Reader(result)) {
                final double[] got = r.f64("values");
                int same = 0;
                double worst = 0;
                for (int k = 0; k < Math.min(got.length, want.length); k++) {
                    if (Double.doubleToLongBits(got[k]) == Double.doubleToLongBits(want[k])) {
                        same++;
                    } else {
                        worst = Math.max(worst, Math.abs(got[k] - want[k]) / Math.max(Math.abs(want[k]), 1e-300));
                    }
                }
                out.add(new Verdict(engine, true, r.meta().getOrDefault("Engine", engine), want.length,
                    got.length == want.length ? same : 0, worst, r.f64("ms")[0]));
            }
        }
        return out;
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void check(Bridge.Outcome outcome, String who) throws IOException {
        if (!outcome.ok()) {
            final String[] lines = outcome.output().split("\n");
            throw new IOException(who + " failed (" + outcome.status() + "): "
                + String.join(" | ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 4), lines.length)));
        }
    }

    /** Has one client make the requests. Answers null when it ran, or why it could not. */
    private static String ask(String engine, Path result, SettingsManager settings, Function<String, String> cling) throws IOException {
        final Path lib = Bridge.libraries();
        final Path build = Bridge.buildFolder().resolve("mlink");
        Files.createDirectories(build);
        switch (engine) {
            case "python" -> {
                final String python = Bridge.python(settings);
                if (python == null) {
                    return "no Python (PYTHON_EXEC)";
                }
                check(Bridge.run(List.of(python, "-B", lib.resolve("sphere_mlink.py").toString(), "--crosscheck", result.toString()), null, 300, null), "Python");
            }
            case "c++" -> {
                final String cxx = Bridge.cppCompiler(settings);
                if (cxx == null) {
                    return "no C++ compiler (GPP_DIR)";
                }
                final Path exe = build.resolve(windows() ? "check_cpp.exe" : "check_cpp");
                final Path main = build.resolve("check_cpp.cpp");
                Files.writeString(main, CPP);
                final List<String> command = new ArrayList<>(List.of(cxx, "-std=c++17", "-O1", "-I" + lib, main.toString(), "-o", exe.toString()));
                if (windows()) {
                    command.add("-static");
                }
                check(Bridge.run(command, build, 300, Bridge.parentOf(cxx)), "the C++ compiler");
                check(Bridge.run(List.of(exe.toString(), result.toString()), null, 300, Bridge.parentOf(cxx)), "C++");
            }
            case "fortran" -> {
                final String fc = Bridge.fortranCompiler(settings);
                if (fc == null) {
                    return "no Fortran compiler (ENV_FC / FORTRAN_DIR)";
                }
                final Path objects = Bridge.fortranObjects(fc);
                if (!Files.isRegularFile(objects.resolve("sphere_mlink.o"))) {
                    return "sphere_mlink.f90 did not compile with " + fc + " (written for gfortran)";
                }
                final Path exe = build.resolve(windows() ? "check_f.exe" : "check_f");
                final Path main = build.resolve("check_f.f90");
                Files.writeString(main, FORTRAN);
                final List<String> command = new ArrayList<>(List.of(fc, "-O1", "-I" + objects, "-J" + build, main.toString(),
                    objects.resolve("sphere_mlink.o").toString(), objects.resolve("sphere_spx.o").toString(), "-o", exe.toString()));
                if (windows()) {
                    command.add("-static");
                }
                check(Bridge.run(command, build, 300, Bridge.parentOf(fc)), "the Fortran compiler");
                check(Bridge.run(List.of(exe.toString(), result.toString()), null, 300, Bridge.parentOf(fc)), "Fortran");
            }
            case "julia" -> {
                final String julia = Bridge.julia(settings);
                if (julia == null) {
                    return "no Julia (JULIA_DIR)";
                }
                final Path driver = build.resolve("check.jl");
                Files.writeString(driver, "include(joinpath(ARGS[1], \"SphereMLink.jl\"))\nSphereMLink.crosscheck(ARGS[2])\n", StandardCharsets.UTF_8);
                check(Bridge.run(List.of(julia, "--startup-file=no", driver.toString(), lib.toString(), result.toString()), null, 300, null), "Julia");
            }
            case "wsl" -> {
                if (!windows()) {
                    return "not on Windows";
                }
                final List<String> distros = MlLink.wslDistros();
                if (distros.isEmpty()) {
                    return "no WSL distribution";
                }
                final Path main = build.resolve("check_cpp.cpp");
                Files.writeString(main, CPP);
                final String script = "command -v g++ >/dev/null || { echo no g++ in WSL; exit 3; }; dir=$(mktemp -d); "
                    + "g++ -std=c++17 -O1 -pthread -I'" + MlLink.wslPath(lib) + "' '" + MlLink.wslPath(main) + "' -o $dir/check && "
                    + "$dir/check '" + MlLink.wslPath(result) + "'; s=$?; rm -rf $dir; exit $s";
                final Bridge.Outcome o = Bridge.run(List.of(MlLink.wslExe(), "-d", distros.get(0), "--exec", "sh", "-c", script), null, 600, null);
                if (o.status() == 3) {
                    return "no g++ in WSL " + distros.get(0);
                }
                check(o, "C++ inside WSL " + distros.get(0));
            }
            case "root" -> {
                if (cling == null) {
                    return "ROOT is not running (':root' starts it; ROOT_DIR in settings.conf names it)";
                }
                cling.apply("gInterpreter->Declare(\"#include \\\"" + slash(lib.resolve("sphere_mlink.hpp")) + "\\\"\")");
                final String answer = cling.apply("sphere::mlink::crosscheck(\"" + slash(result) + "\", \"ROOT\")");
                if (!Files.isRegularFile(result)) {
                    return "ROOT answered: " + answer;
                }
            }
            default -> {
                return "unknown engine " + engine;
            }
        }
        return null;
    }

    private static final String CPP = """
        #include "sphere_mlink.hpp"
        #include <cstdio>
        int main(int argc, char** argv) {
            if (argc < 2) { std::puts("usage: check <out.spx>"); return 1; }
            try { sphere::mlink::crosscheck(argv[1], "C++"); }
            catch (const std::exception& e) { std::fprintf(stderr, "%s\\n", e.what()); return 2; }
            return 0;
        }
        """;

    private static final String FORTRAN = """
        program check
          use sphere_mlink
          implicit none
          character(len=4096) :: out
          call get_command_argument(1, out)
          call mlink_crosscheck(trim(out))
        end program check
        """;
}
