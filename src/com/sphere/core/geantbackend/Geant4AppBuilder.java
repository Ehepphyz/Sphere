package com.sphere.core.geantbackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Builds and runs a Geant4 application from its C++ sources, the way the
 * examples of the Geant4 distribution are built (examples/README.HowToRun.md):
 * a folder holding a CMakeLists.txt that calls find_package(Geant4), a main
 * exampleXYZ.cc, include/ and src/; configured with
 * -DGeant4_DIR=<install>/lib/cmake/Geant4, built with CMake, and run in batch
 * mode on a macro from the build directory, where CMake copied the macros.
 * :g4 export writes such a folder; B1..B5 and TestEm* are such folders.
 *
 * It uses the Geant4 the locator finds, as geant4-bridge does: here, with
 * CMake and the C++ compiler of settings.conf (or geant4-config's flags when
 * there is no CMake package), or inside WSL when that Geant4 lives there. A
 * build inside WSL stays in WSL's own file system, which builds several
 * times faster than /mnt/d, and is kept so the next build is incremental.
 */
public final class Geant4AppBuilder {

    private Geant4AppBuilder() {
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * What a build gave: the build directory (a Windows path, or a Linux one inside WSL) and the
     * programs in it.
     */
    public record Build(boolean ok, String message, Path folder, String buildDir, List<String> programs,
                        Geant4Locator.Install g4) {
        public boolean inWsl() {
            return g4 != null && g4.wsl() != null;
        }

        /** The program to run: the one named after a top-level .cc (the file with main), else the only one. */
        public String program() {
            for (String p : programs) {
                final String base = p.toLowerCase(Locale.ROOT).endsWith(".exe") ? p.substring(0, p.length() - 4) : p;
                if (Files.isRegularFile(folder.resolve(base + ".cc"))) {
                    return p;
                }
            }
            return programs.isEmpty() ? null : programs.get(0);
        }
    }

    private static volatile Process current;

    /** Stops the build or the run going on, if any. */
    public static boolean stop() {
        final Process p = current;
        if (p == null || !p.isAlive()) {
            return false;
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        return true;
    }

    public static boolean running() {
        final Process p = current;
        return p != null && p.isAlive();
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /**
     * Configures (once) and builds the application in a folder.
     *
     * @param clean    start again from an empty build directory
     * @param settings key lookup into settings.conf (GEANT4_DIR, CMAKE_DIR, GPP_DIR)
     * @param log      the build output, line by line
     */
    public static Build build(Path folder, boolean clean, Function<String, String> settings, Consumer<String> log) {
        final Path src = folder.toAbsolutePath().normalize();
        if (!Files.isRegularFile(src.resolve("CMakeLists.txt")) && mainSource(src) == null) {
            return new Build(false, src + " holds no CMakeLists.txt and no .cc with main(): not a Geant4 application"
                + " (:g4 export writes one, the Geant4 examples are some)", src, null, List.of(), null);
        }
        final Geant4Locator.Install g4 = Geant4Locator.best(settings);
        if (g4 == null) {
            return new Build(false, "no Geant4 found to build against: set GEANT4_DIR in settings.conf (a Linux path"
                + " such as /home/me/geant4 names a Geant4 inside WSL), put geant4-config on the PATH, or activate a"
                + " conda environment with Geant4 (:g4 locate lists what is found)", src, null, List.of(), null);
        }
        if (!g4.buildable()) {
            return new Build(false, g4 + " has neither a CMake package nor geant4-config to build with", src, null,
                List.of(), g4);
        }
        log.accept("Building " + src.getFileName() + " against " + g4);
        try {
            return g4.wsl() != null ? buildInWsl(src, clean, g4, settings, log) : buildHere(src, clean, g4, settings, log);
        } catch (IOException e) {
            return new Build(false, e.getMessage(), src, null, List.of(), g4);
        }
    }

    private static Build buildHere(Path src, boolean clean, Geant4Locator.Install g4, Function<String, String> settings,
                                   Consumer<String> log) throws IOException {
        final Path build = src.resolve("build");
        if (clean) {
            delete(build);
        }
        Files.createDirectories(build);
        final String cmake = Geant4BridgeCompiler.tool(settings, "CMAKE_DIR", WINDOWS ? "cmake.exe" : "cmake");
        final String gpp = Geant4BridgeCompiler.tool(settings, "GPP_DIR", WINDOWS ? "g++.exe" : "g++");
        final int jobs = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (cmake != null && g4.cmakeDir() != null && Files.isRegularFile(src.resolve("CMakeLists.txt"))) {
            if (!Files.isRegularFile(build.resolve("CMakeCache.txt"))) {
                final List<String> configure = new ArrayList<>(List.of(cmake, "-S", src.toString(), "-B", build.toString(),
                    "-DCMAKE_BUILD_TYPE=Release", "-DGeant4_DIR=" + g4.cmakeDir()));
                if (WINDOWS && gpp != null && Geant4Locator.which("cl.exe") == null) {
                    configure.addAll(List.of("-G", "MinGW Makefiles", "-DCMAKE_CXX_COMPILER=" + gpp));
                }
                if (exec(configure, build, Map.of(), log) != 0) {
                    return new Build(false, "CMake could not configure " + src.getFileName() + "; see the output above",
                        src, build.toString(), List.of(), g4);
                }
            }
            if (exec(List.of(cmake, "--build", build.toString(), "--config", "Release", "--parallel", String.valueOf(jobs)),
                build, Map.of(), log) != 0) {
                return new Build(false, "the build failed; see the output above", src, build.toString(), List.of(), g4);
            }
        } else {
            // No CMake: the compiler with geant4-config's flags, over <main>.cc and src/*.cc
            if (g4.geant4Config() == null || gpp == null) {
                return new Build(false, "no CMake (CMAKE_DIR) to build with, nor geant4-config and a compiler (GPP_DIR)",
                    src, build.toString(), List.of(), g4);
            }
            final Path main = mainSource(src);
            final String cflags = Geant4Locator.run(g4.geant4Config(), "--cflags");
            final String libs = Geant4Locator.run(g4.geant4Config(), "--libs");
            if (main == null || cflags == null || libs == null) {
                return new Build(false, main == null ? "no .cc with main() in " + src : "geant4-config did not answer",
                    src, build.toString(), List.of(), g4);
            }
            final String name = main.getFileName().toString().replaceFirst("\\.cc$", "") + (WINDOWS ? ".exe" : "");
            final List<String> cmd = new ArrayList<>(List.of(gpp, "-O2", "-I" + src.resolve("include")));
            cmd.addAll(List.of(cflags.trim().split("\\s+")));
            cmd.add(main.toString());
            cmd.addAll(sources(src.resolve("src")));
            cmd.addAll(List.of("-o", build.resolve(name).toString()));
            cmd.addAll(List.of(libs.trim().split("\\s+")));
            if (exec(cmd, build, Map.of(), log) != 0) {
                return new Build(false, "the build failed; see the output above", src, build.toString(), List.of(), g4);
            }
            copyMacros(src, build);
        }
        final List<String> programs = programs(build);
        return programs.isEmpty()
            ? new Build(false, "the build gave no program in " + build, src, build.toString(), programs, g4)
            : new Build(true, "built " + String.join(", ", programs) + " in " + build, src, build.toString(), programs, g4);
    }

    /**
     * Arguments: the sources, the CMake package, geant4-config, the build directory's tag, the jobs and
     * whether to start from scratch. Prints BUILD <dir> first and PROGRAM <name> for each program built.
     */
    private static final String WSL_BUILD = """
        set -e
        exec 2>&1
        src="$1"; cmakedir="$2"; config="$3"; tag="$4"; jobs="$5"; clean="$6"
        [ "$cmakedir" = "-" ] && cmakedir=""
        [ "$config" = "-" ] && config=""
        b="${XDG_CACHE_HOME:-$HOME/.cache}/sphere/g4apps/$tag"
        echo "BUILD $b"
        if [ "$clean" = "1" ]; then rm -rf "$b"; fi
        mkdir -p "$b"
        if [ -n "$cmakedir" ] && [ -f "$src/CMakeLists.txt" ] && command -v cmake >/dev/null 2>&1; then
          if [ ! -f "$b/CMakeCache.txt" ]; then
            echo "$ cmake -S $src -B $b -DCMAKE_BUILD_TYPE=Release -DGeant4_DIR=$cmakedir"
            cmake -S "$src" -B "$b" -DCMAKE_BUILD_TYPE=Release -DGeant4_DIR="$cmakedir"
          fi
          echo "$ cmake --build $b --parallel $jobs"
          cmake --build "$b" --parallel "$jobs"
        else
          main=$(grep -l "main *(" "$src"/*.cc 2>/dev/null | head -n 1)
          if [ -z "$main" ] || [ -z "$config" ]; then echo "no CMake, or no geant4-config and main() to build with"; exit 1; fi
          name=$(basename "$main" .cc)
          echo "$ g++ with the flags of $config"
          g++ -O2 -I"$src/include" $(sh "$config" --cflags) "$main" $(ls "$src"/src/*.cc 2>/dev/null) -o "$b/$name" $(sh "$config" --libs)
          for m in "$src"/*.mac "$src"/*.in "$src"/*.gdml; do if [ -f "$m" ]; then cp "$m" "$b/"; fi; done
        fi
        for f in "$b"/*; do
          # ELF programs only: files copied from /mnt/c (macros) look executable too
          if [ -f "$f" ] && [ -x "$f" ] && [ "$(head -c 4 "$f" | tr -d '\\177')" = "ELF" ]; then
            case "$f" in *.so|*.so.*) ;; *) echo "PROGRAM $(basename "$f")" ;; esac
          fi
        done
        exit 0
        """;

    private static Build buildInWsl(Path src, boolean clean, Geant4Locator.Install g4, Function<String, String> settings,
                                    Consumer<String> log) throws IOException {
        final Geant4Locator.Wsl w = g4.wsl();
        final String lsrc = Geant4Locator.toWslPath(src, g4, settings);
        if (lsrc == null) {
            return new Build(false, "WSL " + w.distro() + " cannot reach " + src + " (is the drive mounted there?)", src,
                null, List.of(), g4);
        }
        final String tag = src.getFileName() + "-" + Integer.toHexString((lsrc + "|" + w.prefix()).hashCode());
        final List<String> cmd = Geant4Locator.wslCommand(g4, settings);
        cmd.addAll(List.of("-e", "sh", "-s", "--", lsrc, w.cmakeDir() == null ? "-" : w.cmakeDir(),
            w.config() == null ? "-" : w.config(), tag, String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors())),
            clean ? "1" : "0"));
        final String[] build = {null};
        final List<String> programs = new ArrayList<>();
        final int code = exec(cmd, WSL_BUILD, src, Map.of(), line -> {
            if (line.startsWith("BUILD ")) {
                build[0] = line.substring(6).trim();
            } else if (line.startsWith("PROGRAM ")) {
                programs.add(line.substring(8).trim());
            } else {
                log.accept(line);
            }
        });
        if (code != 0) {
            return new Build(false, "the build inside WSL " + w.distro() + " failed; see the output above", src, build[0],
                List.of(), g4);
        }
        return programs.isEmpty()
            ? new Build(false, "the build gave no program in " + build[0], src, build[0], programs, g4)
            : new Build(true, "built " + String.join(", ", programs) + " in " + build[0] + " (WSL " + w.distro() + ")",
                src, build[0], programs, g4);
    }

    // ------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------

    /** The macro a run takes when none is given: run1.mac, run.mac, then the program's .in, as the examples have them. */
    public static String defaultMacro(Build b) {
        final String program = b.program();
        final String base = program == null ? "" : program.replaceFirst("(?i)\\.exe$", "");
        for (String m : List.of("run1.mac", "run.mac", base + ".in")) {
            if (Files.isRegularFile(b.folder().resolve(m))) {
                return m;
            }
        }
        return null;
    }

    /**
     * Runs a program of a build in batch mode, from its build directory, with the installation's data sets
     * and libraries. An argument naming a file of the application's folder that CMake did not copy is passed
     * by its full path. Returns the exit code; the output goes to the log line by line.
     */
    public static int run(Build b, String program, List<String> args, Function<String, String> settings,
                          Consumer<String> log) throws IOException {
        final Map<String, String> env = Geant4Locator.runtimeEnvironment(b.g4());
        final List<String> cmd;
        final Path dir;
        if (b.inWsl()) {
            final List<String> largs = new ArrayList<>();
            for (String a : args) {
                final Path f = b.folder().resolve(a);
                final String l = Files.isRegularFile(f) && !isCopiedMacro(b, a) ? Geant4Locator.toWslPath(f, b.g4(), settings) : null;
                largs.add(l != null ? l : a);
            }
            cmd = Geant4Locator.wslCommand(b.g4(), settings);
            cmd.addAll(List.of("--cd", b.buildDir(), "-e", "env"));
            env.forEach((k, v) -> cmd.add(k + "=" + v));
            cmd.add("./" + program);
            cmd.addAll(largs);
            dir = b.folder();
            return exec(cmd, null, dir, Map.of(), log);
        }
        dir = Path.of(b.buildDir());
        cmd = new ArrayList<>(List.of(dir.resolve(program).toString()));
        for (String a : args) {
            final Path f = b.folder().resolve(a);
            cmd.add(Files.isRegularFile(dir.resolve(a)) || !Files.isRegularFile(f) ? a : f.toString());
        }
        final Map<String, String> runEnv = new LinkedHashMap<>(env);
        // A program built with MinGW needs its compiler's runtime DLLs (libstdc++, libgcc) on the PATH
        final String gpp = Geant4BridgeCompiler.tool(settings, "GPP_DIR", WINDOWS ? "g++.exe" : "g++");
        if (WINDOWS && gpp != null && Path.of(gpp).getParent() != null) {
            final String path = runEnv.getOrDefault("PATH", System.getenv("PATH"));
            runEnv.put("PATH", path + ";" + Path.of(gpp).getParent());
        }
        return exec(cmd, null, dir, runEnv, log);
    }

    /** The files a run wrote in the build directory since a moment (here only: inside WSL they stay there). */
    public static List<Path> outputs(Build b, long sinceMillis) {
        final List<Path> out = new ArrayList<>();
        if (b.inWsl() || b.buildDir() == null) {
            return out;
        }
        try (Stream<Path> s = Files.list(Path.of(b.buildDir()))) {
            s.filter(Files::isRegularFile).filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() >= sinceMillis;
                } catch (IOException e) {
                    return false;
                }
            }).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // nothing to list
        }
        return out;
    }

    private static boolean isCopiedMacro(Build b, String arg) {
        // configure_file(... COPYONLY) in the examples' CMakeLists.txt puts the macros next to the program
        try {
            final String cm = Files.readString(b.folder().resolve("CMakeLists.txt"));
            return cm.contains(arg) && cm.contains("configure_file");
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------

    /** The top-level .cc holding main(), as exampleB1.cc in B1. */
    static Path mainSource(Path folder) {
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".cc")).sorted()::iterator) {
                if (Files.readString(p).matches("(?s).*\\bint\\s+main\\s*\\(.*")) {
                    return p;
                }
            }
        } catch (IOException ignored) {
            // no folder, or unreadable
        }
        return null;
    }

    private static List<String> sources(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".cc")).sorted().map(Path::toString).toList();
        }
    }

    private static void copyMacros(Path src, Path build) throws IOException {
        try (Stream<Path> s = Files.list(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                final String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(".mac") || n.endsWith(".in") || n.endsWith(".gdml"))) {
                    Files.copy(p, build.resolve(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The programs CMake put in a build directory (or its Release/ for a multi-configuration generator). */
    private static List<String> programs(Path build) {
        final List<String> out = new ArrayList<>();
        for (Path dir : List.of(build, build.resolve("Release"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile).filter(p -> {
                    final String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return WINDOWS ? n.endsWith(".exe") && !n.startsWith("cmake")
                        : Files.isExecutable(p) && !n.endsWith(".so") && !n.contains(".so.") && isBinary(p);
                }).sorted().forEach(p -> out.add(build.relativize(p).toString().replace('\\', '/')));
            } catch (IOException ignored) {
                // skip
            }
        }
        return out;
    }

    /** An ELF or Mach-O program, not a script nor a macro that happens to be executable. */
    private static boolean isBinary(Path p) {
        try (var in = Files.newInputStream(p)) {
            final byte[] m = in.readNBytes(4);
            if (m.length < 4) {
                return false;
            }
            final int magic = ((m[0] & 0xff) << 24) | ((m[1] & 0xff) << 16) | ((m[2] & 0xff) << 8) | (m[3] & 0xff);
            return magic == 0x7f454c46 || magic == 0xcffaedfe || magic == 0xfeedfacf || magic == 0xcafebabe;
        } catch (IOException e) {
            return false;
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static int exec(List<String> cmd, Path dir, Map<String, String> env, Consumer<String> log) throws IOException {
        return exec(cmd, null, dir, env, log);
    }

    /** Runs a command (fed a script on its input when one is given), its output to the log; stop() ends it. */
    private static int exec(List<String> cmd, String script, Path dir, Map<String, String> env, Consumer<String> log)
        throws IOException {
        log.accept("$ " + String.join(" ", cmd));
        final ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        // A tool from MSYS2 (cmake, g++, mingw32-make) needs its own bin directory on the PATH
        final Path exe = Path.of(cmd.get(0));
        if (WINDOWS && exe.getParent() != null && !env.containsKey("PATH")) {
            pb.environment().merge("PATH", exe.getParent().toString(), (a, b) -> b + ";" + a);
        }
        final Process p = pb.start();
        current = p;
        try {
            if (script != null) {
                try (var w = new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                    w.write(script);
                }
            } else {
                p.getOutputStream().close(); // batch mode: nothing to read on the standard input
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.accept(line);
                }
            }
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -1;
        } finally {
            if (current == p) {
                current = null;
            }
        }
    }
}
