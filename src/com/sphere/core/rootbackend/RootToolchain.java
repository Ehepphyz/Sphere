package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Making what Sphere uses visible to the rest of the toolchain.
 *
 * Sphere knows exactly which ROOT its engine is bound to and exactly where its
 * parton distributions are, because it asks the engine and it keeps the folder
 * list itself. Nothing else on the machine knows either. MadGraph, Herwig,
 * Rivet and a Geant4 build all look for the same two things in the same two
 * ways -- a small program called root-config or lhapdf-config somewhere on the
 * path, and a handful of environment variables -- and if neither is there they
 * conclude the software is not installed, whatever Sphere is running.
 *
 * So this reports what those tools would find, and writes what they need: the
 * variables a shell can source, and, where the program they look for is
 * missing, a stand-in that answers from what Sphere knows. The engine is the
 * authority for ROOT, because it is the installation actually loaded rather
 * than whichever one happens to be first on the path.
 */
public final class RootToolchain {

    /** How long a probe is given before it is treated as absent. */
    private static final int PROBE_SECONDS = 10;

    /** What a piece of the toolchain turned out to be. */
    public record Found(String what, String where, String version,
                        boolean present, String how) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-8s %s", what,
                present ? where + (version.isEmpty() ? "" : "  (" + version + ")")
                        + "   found through " + how
                        : "not found");
        }
    }

    /** Something a program needs before it will use what Sphere has. */
    public record Need(String who, String what, boolean satisfied, String remedy) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "  %-12s %-34s %s", who, what,
                satisfied ? "ok" : "MISSING -- " + remedy);
        }
    }

    private RootToolchain() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* What is there                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * The ROOT the engine is actually using.
     *
     * Asked of the engine first. A machine can carry several ROOT builds and
     * have the wrong one first on the path, so the one that answers is the one
     * worth publishing; root-config and then ROOTSYS are the fallbacks for a
     * session where the engine is not up.
     */
    public static Found root(Function<String, String> askEngine) {
        if (askEngine != null) {
            final String prefix = trimmed(askEngine.apply("prefix"));
            if (!prefix.isEmpty() && !prefix.startsWith("ERROR")) {
                return new Found("ROOT", prefix, trimmed(askEngine.apply("version")),
                                 true, "the running engine");
            }
        }
        final String fromConfig = run("root-config", "--prefix");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return new Found("ROOT", fromConfig.trim(),
                             orEmpty(run("root-config", "--version")),
                             true, "root-config on the path");
        }
        final String sys = System.getenv("ROOTSYS");
        if (sys != null && !sys.isBlank() && Files.isDirectory(Path.of(sys))) {
            return new Found("ROOT", sys.trim(), "", true, "ROOTSYS");
        }
        return new Found("ROOT", "", "", false, "");
    }

    /** The LHAPDF installation, if the machine has one beside Sphere's own sets. */
    public static Found lhapdf() {
        final String prefix = run("lhapdf-config", "--prefix");
        if (prefix != null && !prefix.isBlank()) {
            return new Found("LHAPDF", prefix.trim(),
                             orEmpty(run("lhapdf-config", "--version")),
                             true, "lhapdf-config on the path");
        }
        final String said = System.getenv("LHAPDF_DATA_PATH");
        if (said != null && !said.isBlank()) {
            return new Found("LHAPDF", said.trim(), "", true, "LHAPDF_DATA_PATH");
        }
        final List<Path> own = RootPdfCatalog.ownPaths();
        if (!own.isEmpty()) {
            return new Found("LHAPDF", own.get(0).toString(), "",
                             true, "the folders Sphere was told about");
        }
        return new Found("LHAPDF", "", "", false, "");
    }

    /* ------------------------------------------------------------------ */
    /* Who would find what                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * What each program looks for, and whether it is there.
     *
     * The list is what these programs actually probe, not what their manuals
     * say. Geant4 is here for ROOT only: it transports particles through
     * matter and has no parton distributions to read, so the one that needs
     * LHAPDF in a Geant4 workflow is the generator upstream of it.
     */
    public static List<Need> check(Function<String, String> askEngine) {
        final Found root = root(askEngine);
        final Found pdf = lhapdf();
        final boolean rootConfig = onPath("root-config");
        final boolean pdfConfig = onPath("lhapdf-config");
        final boolean rootsys = notBlank(System.getenv("ROOTSYS"));
        final boolean dataPath = notBlank(System.getenv("LHAPDF_DATA_PATH"))
                              || notBlank(System.getenv("LHAPATH"));

        List<Need> out = new ArrayList<>();
        final String writeThem = "':root env write <folder>' writes it, then add that "
            + "folder to PATH and source the profile beside it.";

        out.add(new Need("MadGraph5", "lhapdf-config on PATH", pdfConfig, writeThem));
        out.add(new Need("MadGraph5", "LHAPDF_DATA_PATH set", dataPath,
            "':root env write <folder>' writes a profile that sets it."));
        out.add(new Need("Herwig", "lhapdf-config on PATH", pdfConfig, writeThem));
        out.add(new Need("Herwig", "LHAPDF_DATA_PATH set", dataPath,
            "':root env write <folder>' writes a profile that sets it."));
        out.add(new Need("Pythia8", "LHAPDF_DATA_PATH set", dataPath,
            "':root env write <folder>' writes a profile that sets it."));
        out.add(new Need("Rivet", "lhapdf-config on PATH", pdfConfig, writeThem));
        out.add(new Need("Geant4", "root-config on PATH", rootConfig, writeThem));
        out.add(new Need("Geant4", "ROOTSYS set", rootsys,
            "':root env write <folder>' writes a profile that sets it."));
        out.add(new Need("CMake", "root-config or ROOTSYS", rootConfig || rootsys,
            "CMake's FindROOT reads one or the other; " + writeThem));
        out.add(new Need("any", "a ROOT to publish", root.present(),
            "Start the engine, or set ROOT_DIR in settings.conf."));
        out.add(new Need("any", "a PDF folder to publish", pdf.present()
            || !RootPdfCatalog.installed().isEmpty(),
            "':root pdf where add <folder>' names one."));
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* What to write                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * The variables another program needs in its environment.
     *
     * These are the ones thisroot.sh sets, plus the one LHAPDF reads. The
     * existing value of each path variable is kept and the new entry put in
     * front, because a machine that already has a working toolchain should
     * keep it: the point is to be found first, not to be the only one.
     */
    public static Map<String, String> environment(Function<String, String> askEngine) {
        Map<String, String> out = new LinkedHashMap<>();
        final Found root = root(askEngine);
        if (root.present()) {
            final Path prefix = Path.of(root.where());
            out.put("ROOTSYS", prefix.toString());
            prepend(out, "PATH", prefix.resolve("bin").toString());
            final String lib = askedOr(askEngine, "libdir", prefix.resolve("lib").toString());
            prepend(out, "LD_LIBRARY_PATH", lib);
            prepend(out, "DYLD_LIBRARY_PATH", lib);
            prepend(out, "PYTHONPATH", lib);
            prepend(out, "CMAKE_PREFIX_PATH", prefix.toString());
        }
        final List<Path> folders = new ArrayList<>(RootPdfCatalog.ownPaths());
        for (Path one : RootPdfCatalog.paths()) {
            if (!folders.contains(one)) {
                folders.add(one);
            }
        }
        if (!folders.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (Path one : folders) {
                joined.append(joined.length() == 0 ? "" : java.io.File.pathSeparator)
                      .append(one);
            }
            out.put("LHAPDF_DATA_PATH", joined.toString());
            out.put("LHAPATH", joined.toString());
        }
        return out;
    }

    /**
     * Writes a folder another program can be pointed at.
     *
     * Two things go in it: a profile to source, which sets the variables, and a
     * stand-in for whichever of the two small programs the machine is missing.
     * A stand-in is only written when the real one is not on the path, so a
     * proper installation is never shadowed by this one.
     */
    public static List<Path> write(Path folder, Function<String, String> askEngine)
            throws IOException {
        Files.createDirectories(folder);
        List<Path> written = new ArrayList<>();

        final Map<String, String> env = environment(askEngine);
        StringBuilder profile = new StringBuilder();
        profile.append("# Written by Sphere. Source this to let the rest of the\n");
        profile.append("# toolchain find the ROOT and the parton distributions\n");
        profile.append("# Sphere is using:  . ").append(folder.resolve("sphere-env.sh")).append('\n');
        profile.append("#\n# Delete it and nothing else changes: it sets variables, it installs nothing.\n\n");
        for (Map.Entry<String, String> one : env.entrySet()) {
            profile.append("export ").append(one.getKey()).append("=\"")
                   .append(one.getValue()).append("\"\n");
        }
        profile.append("export PATH=\"").append(folder).append(":$PATH\"\n");
        final Path shell = folder.resolve("sphere-env.sh");
        Files.writeString(shell, profile.toString(), StandardCharsets.UTF_8);
        written.add(shell);

        if (!onPath("root-config")) {
            final Path shim = folder.resolve("root-config");
            Files.writeString(shim, rootConfigShim(askEngine), StandardCharsets.UTF_8);
            makeRunnable(shim);
            written.add(shim);
        }
        if (!onPath("lhapdf-config")) {
            final Path shim = folder.resolve("lhapdf-config");
            Files.writeString(shim, lhapdfConfigShim(env), StandardCharsets.UTF_8);
            makeRunnable(shim);
            written.add(shim);
        }
        return written;
    }

    /**
     * A stand-in for root-config, answering what the engine answers.
     *
     * Every value is baked in at the moment it is written rather than asked for
     * when it runs, because the thing that knows them is a running Sphere and
     * the thing asking is a build system that will run long after this session
     * has ended. It is regenerated by running the command again.
     */
    private static String rootConfigShim(Function<String, String> askEngine) {
        final Found root = root(askEngine);
        final String prefix = root.where();
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("--prefix", prefix);
        answers.put("--exec-prefix", askedOr(askEngine, "exec-prefix", prefix));
        answers.put("--version", root.version());
        answers.put("--incdir", askedOr(askEngine, "incdir", prefix + "/include"));
        answers.put("--libdir", askedOr(askEngine, "libdir", prefix + "/lib"));
        answers.put("--bindir", askedOr(askEngine, "bindir", prefix + "/bin"));
        answers.put("--etcdir", askedOr(askEngine, "etcdir", prefix + "/etc"));
        answers.put("--cflags", askedOr(askEngine, "cflags", "-I" + prefix + "/include"));
        answers.put("--auxcflags", askedOr(askEngine, "auxcflags", ""));
        answers.put("--ldflags", askedOr(askEngine, "ldflags", ""));
        answers.put("--libs", askedOr(askEngine, "libs", "-L" + prefix + "/lib -lCore"));
        answers.put("--glibs", askedOr(askEngine, "glibs", ""));
        answers.put("--evelibs", askedOr(askEngine, "evelibs", ""));
        answers.put("--features", askedOr(askEngine, "features", ""));
        answers.put("--arch", askedOr(askEngine, "arch", ""));
        answers.put("--platform", askedOr(askEngine, "platform", ""));
        answers.put("--cc", askedOr(askEngine, "cc", "cc"));
        answers.put("--cxx", askedOr(askEngine, "cxx", "c++"));
        answers.put("--ld", askedOr(askEngine, "ld", "c++"));
        answers.put("--ncpu", askedOr(askEngine, "ncpu", "1"));
        answers.put("--git-revision", askedOr(askEngine, "git-revision", ""));
        answers.put("--python-version", askedOr(askEngine, "python-version", ""));
        answers.put("--cxxstandard", askedOr(askEngine, "cxx-standard", ""));
        answers.put("--config", askedOr(askEngine, "config", ""));

        StringBuilder out = new StringBuilder("#!/bin/sh\n");
        out.append("# Written by Sphere: what its engine answers, in the shape\n");
        out.append("# root-config answers it, for the build systems that ask.\n");
        out.append("# Regenerated by ':root env write'.\n\n");
        out.append("case \"$1\" in\n");
        for (Map.Entry<String, String> one : answers.entrySet()) {
            out.append("  ").append(one.getKey()).append(") echo '")
               .append(shellSafe(one.getValue())).append("' ;;\n");
        }
        out.append("  --has-*) case \"$1\" in\n");
        out.append("      *) for f in ").append(shellSafe(askedOr(askEngine, "features", "")))
           .append("; do\n");
        out.append("           [ \"--has-$f\" = \"$1\" ] && echo yes && exit 0\n");
        out.append("         done\n");
        out.append("         echo no ;;\n");
        out.append("    esac ;;\n");
        out.append("  --help|\"\") echo 'root-config, written by Sphere' ; exit 0 ;;\n");
        out.append("  *) echo \"root-config: unknown option $1\" >&2 ; exit 1 ;;\n");
        out.append("esac\n");
        return out.toString();
    }

    /** The same for lhapdf-config, whose keys are fewer. */
    private static String lhapdfConfigShim(Map<String, String> env) {
        final String data = env.getOrDefault("LHAPDF_DATA_PATH", "");
        final String first = data.isEmpty() ? ""
            : data.split(java.io.File.pathSeparator, 2)[0];
        StringBuilder out = new StringBuilder("#!/bin/sh\n");
        out.append("# Written by Sphere. It answers where the sets are, which is what\n");
        out.append("# MadGraph, Herwig and Rivet ask for. It reports no library and no\n");
        out.append("# headers, because Sphere reads the grids itself and links nothing:\n");
        out.append("# a build that wants to compile against LHAPDF needs the real one.\n");
        out.append("# Regenerated by ':root env write'.\n\n");
        out.append("case \"$1\" in\n");
        out.append("  --datadir|--datarootdir) echo '").append(shellSafe(first)).append("' ;;\n");
        out.append("  --prefix|--exec-prefix) echo '").append(shellSafe(first)).append("' ;;\n");
        out.append("  --version) echo 'sphere' ;;\n");
        out.append("  --libdir|--incdir|--cflags|--ldflags|--libs) echo '' ;;\n");
        out.append("  --help|\"\") echo 'lhapdf-config, written by Sphere' ; exit 0 ;;\n");
        out.append("  *) echo \"lhapdf-config: unknown option $1\" >&2 ; exit 1 ;;\n");
        out.append("esac\n");
        return out.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    /** What the whole thing looks like, written out. */
    public static String report(Function<String, String> askEngine) {
        StringBuilder text = new StringBuilder("What Sphere is using:");
        text.append(System.lineSeparator()).append("  ").append(root(askEngine));
        text.append(System.lineSeparator()).append("  ").append(lhapdf());
        final List<String> sets = RootPdfCatalog.installed();
        text.append(String.format(Locale.ROOT, "%n  %-8s %d set(s) on disk in %d folder(s)",
            "sets", sets.size(), RootPdfCatalog.paths().size()));

        text.append(System.lineSeparator()).append(System.lineSeparator());
        text.append("What the rest of the toolchain would find:");
        int missing = 0;
        for (Need one : check(askEngine)) {
            text.append(System.lineSeparator()).append(one);
            if (!one.satisfied()) {
                missing++;
            }
        }
        text.append(System.lineSeparator()).append(System.lineSeparator());
        text.append(missing == 0
            ? "Nothing missing: another program started from this environment finds both."
            : missing + " thing(s) missing. ':root env write <folder>' writes what they need.");
        return text.toString();
    }

    /** True when a program of that name is on the path and answers. */
    public static boolean onPath(String program) {
        final String answer = run(program, "--help");
        if (answer != null) {
            return true;
        }
        // A program that refuses --help is still a program, so look for the file.
        final String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String folder : path.split(java.io.File.pathSeparator)) {
            if (!folder.isBlank() && Files.isExecutable(Path.of(folder, program))) {
                return true;
            }
        }
        return false;
    }

    private static String askedOr(Function<String, String> askEngine,
                                  String key, String fallback) {
        if (askEngine == null) {
            return fallback;
        }
        final String said = trimmed(askEngine.apply(key));
        return said.isEmpty() || said.startsWith("ERROR") ? fallback : said;
    }

    private static void prepend(Map<String, String> into, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        final String already = System.getenv(name);
        into.put(name, already == null || already.isBlank()
            ? value : value + java.io.File.pathSeparator + already);
    }

    /**
     * One probe, with a timeout and both streams drained.
     *
     * A probe that is left to fill its error pipe and never read stops the
     * caller rather than the probe, which is why the two are joined and the
     * wait is bounded.
     */
    private static String run(String program, String argument) {
        try {
            ProcessBuilder builder = new ProcessBuilder(program, argument);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder said = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    said.append(said.length() == 0 ? "" : "\n").append(line);
                }
            }
            if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? said.toString() : null;
        } catch (IOException notThere) {
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void makeRunnable(Path file) {
        try {
            Set<PosixFilePermission> rights = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, rights);
        } catch (IOException | UnsupportedOperationException notPosix) {
            // Windows has no such bit, and nothing there runs a shell script anyway.
        }
    }

    /** A value going inside single quotes in a shell script. */
    private static String shellSafe(String value) {
        return value == null ? "" : value.replace("'", "'\\''").replace("\n", " ");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
