package com.sphere.core.bridge;

import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenRunInfo;
import com.sphere.core.hepmc3.WriterAscii;
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
import java.util.function.Function;

/**
 * HepMC3 events handed to every engine and read back by each: proof that what
 * Python, Julia, C++, Fortran and ROOT see of a sample written by
 * {@code :hepmc bridge} is the sample Java holds.
 *
 * <p>Each engine reads the SPX file with its own reader (Fortran through the
 * COMMON /HEPEVT/ block it fills, as a Fortran generator or analysis would)
 * and writes back, per event, the number of particles, of final-state
 * particles and of vertices, the sum of the first mothers, and the final-state
 * energy and pz and the weights summed in file order. Java computes the same
 * from the same file; the two must agree to the bit.
 *
 * <p>With a C++ installation of HepMC3 (HEPMC3_DIR, or HepMC3-config on the
 * PATH), one more engine runs: the C++ library rebuilds every event from the
 * file (spx::hepmc3::fill) and writes it with its own WriterAscii, and that
 * text must be the one Sphere's WriterAscii writes, character for character.
 */
public final class BridgeHepMCCheck {

    /** One engine's verdict. */
    public record Verdict(String engine, boolean ran, String note, int events, int identical, String firstDifference,
                          long millis) {
    }

    public static final List<String> ENGINES = List.of("java", "python", "julia", "c++", "fortran", "root", "hepmc3");

    private static final String[] COLUMNS = {"np", "nfinal", "nvtx", "mosum", "efinal", "pzfinal", "wsum"};

    private BridgeHepMCCheck() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Java's answer, from the file as the engines read it. */
    static double[][] reference(Path file) throws IOException {
        try (Spx.Reader r = new Spx.Reader(file)) {
            final long[] off = r.i64("evt_offset");
            final long[] status = r.i64("status");
            final long[] mothers = r.i64("mothers");
            final long[] vtx = r.i64("vtx_offset");
            final long[] wo = r.i64("wgt_offset");
            final double[] wa = r.f64("wgt_all");
            final double[] p4 = r.f64("p4");
            final int n = off.length - 1;
            final double[][] out = new double[COLUMNS.length][n];
            for (int e = 0; e < n; e++) {
                long nf = 0;
                long ms = 0;
                double ef = 0;
                double pzf = 0;
                double ws = 0;
                for (long i = off[e]; i < off[e + 1]; i++) {
                    final int k = (int) i;
                    if (status[k] == 1) {
                        nf++;
                        ef += p4[4 * k + 3];
                        pzf += p4[4 * k + 2];
                    }
                    ms += mothers[2 * k];
                }
                for (long k = wo[e]; k < wo[e + 1]; k++) ws += wa[(int) k];
                out[0][e] = off[e + 1] - off[e];
                out[1][e] = nf;
                out[2][e] = vtx[e + 1] - vtx[e];
                out[3][e] = ms;
                out[4][e] = ef;
                out[5][e] = pzf;
                out[6][e] = ws;
            }
            return out;
        }
    }

    /**
     * Runs the engines asked for on a file :hepmc bridge wrote.
     *
     * @param events the events of the file, for the C++ HepMC3 comparison (may be null to skip it)
     * @param hepmc3 the prefix of a C++ HepMC3 installation, or null
     * @param cling  a way to run a line in ROOT's interpreter, or null when ROOT is not up
     */
    public static List<Verdict> run(Path file, List<GenEvent> events, GenRunInfo run, Path hepmc3, List<String> engines,
                                    SettingsManager settings, Function<String, String> cling) throws IOException {
        final Path work = Bridge.folder().resolve("crosscheck");
        Files.createDirectories(work);
        final double[][] reference = reference(file);
        final int n = reference[0].length;
        final List<Verdict> out = new ArrayList<>();
        if (engines.contains("java")) out.add(new Verdict("java", true, "reference", n, n, null, 0));
        final Path lib = Bridge.libraries();
        for (String engine : engines) {
            if (engine.equals("java")) continue;
            final long t0 = System.nanoTime();
            final Path result = work.resolve("hepmc-" + engine.replace("+", "p") + ".spx");
            try {
                Files.deleteIfExists(result);
                if (engine.equals("hepmc3")) {
                    out.add(viaHepMC3(file, events, run, hepmc3, settings, work, t0));
                    continue;
                }
                final String why = evaluate(engine, file, result, lib, settings, cling);
                final long ms = (System.nanoTime() - t0) / 1_000_000;
                if (why != null) {
                    out.add(new Verdict(engine, false, why, 0, 0, null, ms));
                    continue;
                }
                out.add(compare(engine, result, reference, ms));
            } catch (IOException | RuntimeException failed) {
                out.add(new Verdict(engine, false, failed.getMessage(), 0, 0, null, (System.nanoTime() - t0) / 1_000_000));
            }
        }
        return out;
    }

    private static String evaluate(String engine, Path file, Path result, Path lib, SettingsManager settings,
                                   Function<String, String> cling) throws IOException {
        switch (engine) {
            case "python" -> {
                final String python = Bridge.python(settings);
                if (python == null) return "no Python (PYTHON_EXEC)";
                check(Bridge.run(List.of(python, lib.resolve("sphere_spx.py").toString(), "hepmc_check", file.toString(),
                    result.toString()), null, 300, null), "Python");
            }
            case "julia" -> {
                final String julia = Bridge.julia(settings);
                if (julia == null) return "no Julia (JULIA_DIR)";
                final Path driver = Bridge.buildFolder().resolve("hepmc_check.jl");
                Files.createDirectories(driver.getParent());
                Files.writeString(driver, "include(joinpath(ARGS[1], \"SphereSPX.jl\"))\n"
                    + "SphereSPX.hepmccheck(ARGS[2], ARGS[3])\n");
                check(Bridge.run(List.of(julia, "--startup-file=no", driver.toString(), lib.toString(), file.toString(),
                    result.toString()), null, 300, null), "Julia");
            }
            case "c++" -> {
                final String cxx = Bridge.cppCompiler(settings);
                if (cxx == null) return "no C++ compiler (GPP_DIR)";
                final Path exe = cppChecker(cxx, lib);
                check(Bridge.run(List.of(exe.toString(), file.toString(), result.toString()), null, 120,
                    Bridge.parentOf(cxx)), "C++");
            }
            case "fortran" -> {
                final String fc = Bridge.fortranCompiler(settings);
                if (fc == null) return "no Fortran compiler (ENV_FC / FORTRAN_DIR)";
                final Path exe = fortranChecker(fc);
                check(Bridge.run(List.of(exe.toString(), file.toString(), result.toString()), null, 120,
                    Bridge.parentOf(fc)), "Fortran");
            }
            case "root" -> {
                if (cling == null) return "ROOT is not running (':root' starts it)";
                final String include = BridgeCrossCheck.slash(lib.resolve("sphere_spx.hpp"));
                cling.apply("gInterpreter->Declare(\"#include \\\"" + include + "\\\"\")");
                final String answer = cling.apply("spx::hepmcCheck(\"" + BridgeCrossCheck.slash(file) + "\", \""
                    + BridgeCrossCheck.slash(result) + "\", \"ROOT\")");
                if (!Files.isRegularFile(result)) return "ROOT answered: " + answer;
            }
            default -> {
                return "unknown engine " + engine;
            }
        }
        return null;
    }

    private static Verdict compare(String engine, Path result, double[][] reference, long millis) throws IOException {
        if (!Files.isRegularFile(result)) return new Verdict(engine, false, "wrote no answer", 0, 0, null, millis);
        try (Spx.Reader r = new Spx.Reader(result)) {
            final int n = reference[0].length;
            final boolean[] same = new boolean[n];
            java.util.Arrays.fill(same, true);
            String first = null;
            final String engineName = r.meta().getOrDefault("Engine", engine);
            for (int c = 0; c < COLUMNS.length; c++) {
                final double[] got = r.numbers(COLUMNS[c]);
                for (int e = 0; e < n; e++) {
                    final double g = e < got.length ? got[e] : Double.NaN;
                    if (Double.doubleToLongBits(g) != Double.doubleToLongBits(reference[c][e])) {
                        same[e] = false;
                        if (first == null) {
                            first = String.format(Locale.ROOT, "event %d, %s: %s, Java %s", e, COLUMNS[c],
                                Double.toString(g), Double.toString(reference[c][e]));
                        }
                    }
                }
            }
            int identical = 0;
            for (boolean s : same) if (s) identical++;
            return new Verdict(engine, true, engineName, n, identical, first, millis);
        }
    }

    /**
     * The C++ HepMC3 library rebuilds the events from the file and writes them
     * in Asciiv3; Sphere writes the same events; the texts are compared.
     */
    private static Verdict viaHepMC3(Path file, List<GenEvent> events, GenRunInfo run, Path prefix,
                                     SettingsManager settings, Path work, long t0) throws IOException {
        if (prefix == null) return new Verdict("hepmc3", false, "no C++ HepMC3 found (HEPMC3_DIR, HepMC3-config)", 0, 0, null, 0);
        if (events == null) return new Verdict("hepmc3", false, "no events to compare with", 0, 0, null, 0);
        final String cxx = Bridge.cppCompiler(settings);
        if (cxx == null) return new Verdict("hepmc3", false, "no C++ compiler (GPP_DIR)", 0, 0, null, 0);
        final Path exe = hepmc3Program(cxx, Bridge.libraries(), prefix);
        final Path theirs = work.resolve("hepmc-cpp-hepmc3.hepmc");
        final Path ours = work.resolve("hepmc-java.hepmc");
        Files.deleteIfExists(theirs);
        check(Bridge.run(List.of(exe.toString(), file.toString(), theirs.toString()), null, 300, Bridge.parentOf(cxx)),
            "the C++ HepMC3 program");
        final WriterAscii w = new WriterAscii(ours, run);
        for (GenEvent e : events) w.writeEvent(e);
        w.close();
        final List<String> a = keep(Files.readString(theirs, StandardCharsets.ISO_8859_1));
        final List<String> b = keep(Files.readString(ours, StandardCharsets.ISO_8859_1));
        String first = null;
        for (int i = 0; i < Math.max(a.size(), b.size()) && first == null; i++) {
            final String x = i < a.size() ? a.get(i) : "(end)";
            final String y = i < b.size() ? b.get(i) : "(end)";
            if (!x.equals(y)) first = "line " + (i + 1) + ": C++ " + x + " | Java " + y;
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        final int n = events.size();
        return new Verdict("hepmc3", true, "C++ HepMC3 at " + prefix + ", WriterAscii text", n, first == null ? n : 0, first, ms);
    }

    /** The lines that matter: the version line and blank lines aside, line ends either way. */
    private static List<String> keep(String text) {
        final List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            final String s = l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
            if (s.isEmpty() || s.contains("HepMC::Version")) continue;
            out.add(s);
        }
        return out;
    }

    private static void check(Bridge.Outcome outcome, String who) throws IOException {
        if (!outcome.ok()) {
            final String[] lines = outcome.output().split("\n");
            throw new IOException(who + " failed (" + outcome.status() + "): "
                + String.join(" | ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 4), lines.length)));
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path cppChecker(String cxx, Path lib) throws IOException {
        final String key = Bridge.digest(cxx, new String(Bridge.carried("sphere_spx.hpp"), StandardCharsets.UTF_8), "hepmc");
        final Path dir = Bridge.buildFolder().resolve("cpp-hepmc-" + key.substring(0, 12));
        final Path exe = dir.resolve(windows() ? "spx_hepmc_check.exe" : "spx_hepmc_check");
        if (Files.isRegularFile(exe)) return exe;
        Files.createDirectories(dir);
        final Path main = dir.resolve("spx_hepmc_check.cpp");
        Files.writeString(main, """
            #include "sphere_spx.hpp"
            #include <cstdio>
            int main(int argc, char** argv) {
                if (argc < 3) { std::puts("usage: spx_hepmc_check <events.spx> <out.spx>"); return 1; }
                try { spx::hepmcCheck(argv[1], argv[2]); }
                catch (const std::exception& e) { std::fprintf(stderr, "%s\\n", e.what()); return 2; }
                return 0;
            }
            """);
        final List<String> command = new ArrayList<>(List.of(cxx, "-std=c++17", "-O2", "-I" + lib, main.toString(),
            "-o", exe.toString()));
        if (windows()) command.add("-static");
        final Bridge.Outcome built = Bridge.run(command, dir, 300, Bridge.parentOf(cxx));
        if (!built.ok()) throw new IOException("the C++ checker did not compile: " + built.output());
        return exe;
    }

    private static Path fortranChecker(String fc) throws IOException {
        final Path objects = Bridge.fortranObjects(fc);
        final Path exe = objects.resolve(windows() ? "spx_hepmc_check.exe" : "spx_hepmc_check");
        if (Files.isRegularFile(exe)) return exe;
        final Path main = objects.resolve("spx_hepmc_check.f90");
        Files.writeString(main, """
            program spx_hepmc_check_main
              use sphere_spx
              implicit none
              character(len=4096) :: events, out
              call get_command_argument(1, events)
              call get_command_argument(2, out)
              call spx_hepmc_check(trim(events), trim(out))
            end program spx_hepmc_check_main
            """);
        final List<String> command = new ArrayList<>(List.of(fc, "-O2", "-I" + objects, main.toString(),
            objects.resolve("sphere_spx.o").toString(), "-o", exe.toString()));
        if (windows()) command.add("-static");
        final Bridge.Outcome built = Bridge.run(command, objects, 300, Bridge.parentOf(fc));
        if (!built.ok()) throw new IOException("the Fortran checker did not compile: " + built.output());
        return exe;
    }

    /** The program that has the C++ HepMC3 library rebuild and write the events, compiled once per installation. */
    private static Path hepmc3Program(String cxx, Path lib, Path prefix) throws IOException {
        final String key = Bridge.digest(cxx, prefix.toString(),
            new String(Bridge.carried("sphere_spx.hpp"), StandardCharsets.UTF_8), "hepmc3");
        final Path dir = Bridge.buildFolder().resolve("hepmc3-" + key.substring(0, 12));
        final Path exe = dir.resolve(windows() ? "spx_to_hepmc3.exe" : "spx_to_hepmc3");
        if (Files.isRegularFile(exe)) return exe;
        Files.createDirectories(dir);
        final Path main = dir.resolve("spx_to_hepmc3.cpp");
        Files.writeString(main, """
            #include "HepMC3/GenEvent.h"
            #include "HepMC3/ReaderAscii.h"
            #include "HepMC3/WriterAscii.h"
            #include "sphere_spx.hpp"
            #include <cstdio>
            int main(int argc, char** argv) {
                if (argc < 3) { std::puts("usage: spx_to_hepmc3 <events.spx> <out.hepmc>"); return 1; }
                try {
                    const spx::Events ev{spx::File(argv[1])};
                    auto run = spx::hepmc3::runInfo(ev);
                    HepMC3::WriterAscii out(argv[2], run);
                    for (std::int64_t e = 0; e < ev.size(); ++e) {
                        HepMC3::GenEvent g;
                        spx::hepmc3::fill(ev, e, g);
                        if (run) g.set_run_info(run);
                        out.write_event(g);
                    }
                    out.close();
                } catch (const std::exception& e) { std::fprintf(stderr, "%s\\n", e.what()); return 2; }
                return 0;
            }
            """);
        final Path libdir = Files.isDirectory(prefix.resolve("lib64")) && !Files.isDirectory(prefix.resolve("lib"))
            ? prefix.resolve("lib64") : prefix.resolve("lib");
        final List<String> command = new ArrayList<>(List.of(cxx, "-std=c++17", "-O2", "-I" + prefix.resolve("include"),
            "-I" + lib, main.toString(), "-o", exe.toString(), "-L" + libdir, "-L" + prefix.resolve("bin"), "-lHepMC3"));
        if (windows()) command.add("-static");
        else command.add("-Wl,-rpath," + libdir);
        final Bridge.Outcome built = Bridge.run(command, dir, 600, Bridge.parentOf(cxx));
        if (!built.ok()) throw new IOException("the C++ HepMC3 program did not compile or link: " + built.output());
        return exe;
    }

    /** One line per verdict, for the console. */
    public static Map<String, String> describe(List<Verdict> verdicts) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (Verdict v : verdicts) {
            if (!v.ran()) {
                out.put(v.engine(), "not run: " + v.note());
                continue;
            }
            out.put(v.engine(), String.format(Locale.ROOT, "%d/%d events identical%s  (%s, %d ms)", v.identical(), v.events(),
                v.firstDifference() == null ? "" : "; first difference: " + v.firstDifference(), v.note(), v.millis()));
        }
        return out;
    }
}
