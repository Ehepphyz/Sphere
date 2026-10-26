package com.sphere.core.geantbackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Geant4 installations of this machine, wherever they come from:
 * GEANT4_DIR in settings.conf, the environment (GEANT4_DIR, Geant4_DIR,
 * G4INSTALL), geant4-config on the PATH, the active conda or virtual
 * environment, every conda, mamba, miniforge and pixi environment, the
 * Python Sphere is set to use, and the usual system prefixes.
 *
 * An installation is a prefix holding Geant4Config.cmake (what CMake needs to
 * build geant4-bridge) and, when present, geant4-config (which also says
 * where the physics data sets are).
 *
 * Paths are read as settings.conf writes them, for any of the systems Sphere
 * runs on: C:\... and /mnt/c/... are the same folder seen from Windows or WSL.
 * On Windows, a Linux path (/home/me/geant4) or \\wsl.localhost\Debian\...
 * names a Geant4 installed inside WSL: it is looked at, built against and run
 * there, through wsl.exe, since a Linux Geant4 cannot be linked by a Windows
 * compiler.
 */
public final class Geant4Locator {

    private Geant4Locator() {
    }

    /**
     * One Geant4 found. Inside WSL (seen from Windows) its paths are Linux ones, in {@link Wsl}; prefix is
     * then the same folder as Windows reaches it (\\wsl.localhost\distro\...), for showing only.
     */
    public record Install(Path prefix, Path cmakeDir, Path geant4Config, String version, String origin,
                          Map<String, String> datasets, Wsl wsl) {
        public boolean buildable() {
            return wsl != null ? wsl.cmakeDir() != null || wsl.config() != null : cmakeDir != null || geant4Config != null;
        }

        @Override
        public String toString() {
            return "Geant4 " + (version == null ? "?" : version) + " at "
                + (wsl == null ? prefix.toString() : wsl.prefix() + " in WSL " + wsl.distro()) + " (" + origin + ")";
        }
    }

    /**
     * A Geant4 inside WSL: the distribution, the Linux paths of the prefix, of the CMake package and of
     * geant4-config, and of CMake and g++ in that distribution (null when absent).
     */
    public record Wsl(String distro, String prefix, String cmakeDir, String config, String cmake, String gpp) {
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Every installation found, the configured one first. */
    public static List<Install> find(Function<String, String> settings) {
        final Map<Path, String> candidates = new LinkedHashMap<>();
        final List<Install> inWsl = new ArrayList<>();
        final String configured = settings == null ? null : settings.apply("GEANT4_DIR");
        if (configured != null && !configured.isBlank()) {
            addConfigured(candidates, inWsl, configured.trim(), "settings.conf GEANT4_DIR", settings);
        }
        for (String env : List.of("GEANT4_DIR", "Geant4_DIR", "G4INSTALL", "GEANT4_INSTALL")) {
            final String v = System.getenv(env);
            if (v != null && !v.isBlank()) {
                addConfigured(candidates, inWsl, v.trim(), "environment " + env, settings);
            }
        }
        final Path onPath = which(WINDOWS ? "geant4-config.exe" : "geant4-config");
        if (onPath != null) {
            addCandidate(candidates, onPath, "geant4-config on PATH");
        }
        final String conda = System.getenv("CONDA_PREFIX");
        if (conda != null) {
            candidates.putIfAbsent(Path.of(conda), "active conda environment");
        }
        final String venv = System.getenv("VIRTUAL_ENV");
        if (venv != null) {
            candidates.putIfAbsent(Path.of(venv), "active virtual environment");
        }
        if (settings != null) {
            final String py = settings.apply("PYTHON_EXEC");
            if (py != null && !py.isBlank()) {
                // .../env/bin/python or ...\env\Scripts\python.exe: the environment is two levels up
                final Path p = Path.of(py.trim());
                if (p.getParent() != null && p.getParent().getParent() != null) {
                    candidates.putIfAbsent(p.getParent().getParent(), "Sphere's Python environment");
                }
            }
        }
        for (Path env : condaEnvironments()) {
            candidates.putIfAbsent(env, "conda environment " + env.getFileName());
        }
        for (String p : WINDOWS
            ? List.of("C:\\Program Files\\Geant4", "C:\\Geant4", "C:\\opt\\geant4", "D:\\Geant4")
            : List.of("/usr/local", "/usr", "/opt/geant4", "/opt/local", "/opt/homebrew", "/usr/local/geant4")) {
            candidates.putIfAbsent(Path.of(p), "system");
        }
        globInto(candidates, Path.of(WINDOWS ? "C:\\Program Files" : "/opt"), "geant4*", "system");
        globInto(candidates, Path.of(System.getProperty("user.home")), "geant4*", "home");

        final List<Install> out = new ArrayList<>(inWsl);
        final Set<Path> seen = new LinkedHashSet<>();
        for (Map.Entry<Path, String> e : candidates.entrySet()) {
            final Install i = inspect(e.getKey(), e.getValue());
            if (i != null && seen.add(i.prefix().toAbsolutePath().normalize())) {
                out.add(i);
            }
        }
        return out;
    }

    /** The installation to build with: the configured one, else the first that can build. */
    public static Install best(Function<String, String> settings) {
        for (Install i : find(settings)) {
            if (i.buildable()) {
                return i;
            }
        }
        return null;
    }

    /**
     * A path the user gave: rewritten for this system (C:\... and /mnt/c/... alike), or, on Windows, a Linux
     * or \\wsl$ path, looked at inside WSL.
     */
    private static void addConfigured(Map<Path, String> c, List<Install> inWsl, String raw, String origin,
                                      Function<String, String> settings) {
        if (WINDOWS) {
            final String[] target = wslTarget(raw);
            if (target != null) {
                final Install i = inspectWsl(target[0], target[1], origin, settings);
                if (i != null) {
                    inWsl.add(i);
                }
                return;
            }
        }
        final String here = com.sphere.utils.SettingsManager.toNativePath(raw);
        if (here == null) {
            return; // a Windows drive on a Linux or macOS host: nothing there
        }
        try {
            addCandidate(c, Path.of(here), origin);
        } catch (RuntimeException ignored) {
            // not a path
        }
    }

    private static final Pattern WSL_UNC = Pattern.compile("^[\\\\/]{2}wsl(?:\\$|\\.localhost)[\\\\/]([^\\\\/]+)(.*)$",
        Pattern.CASE_INSENSITIVE);

    /**
     * On Windows, the (distribution, Linux path) a value names inside WSL, or null for a Windows path:
     * /home/me/geant4 (in the default distribution), \\wsl.localhost\Debian\home\me, \\wsl$\Debian\home\me.
     */
    static String[] wslTarget(String raw) {
        final String v = raw.trim();
        final Matcher unc = WSL_UNC.matcher(v);
        if (unc.matches()) {
            final String rest = unc.group(2).replace('\\', '/');
            return new String[] {unc.group(1), rest.isEmpty() ? "/" : rest};
        }
        if (v.startsWith("/") && com.sphere.utils.SettingsManager.toNativePath(v,
            com.sphere.utils.OSValidator.Platform.WINDOWS) == null) {
            return new String[] {null, v};
        }
        return null;
    }

    /** wsl.exe: settings.conf WIN_WSL when it names one, else the one on the PATH. */
    static String wslExe(Function<String, String> settings) {
        final String v = settings == null ? null : settings.apply("WIN_WSL");
        if (v != null && !v.isBlank() && Files.isRegularFile(Path.of(v.trim()))) {
            return v.trim();
        }
        return "wsl.exe";
    }

    /** The start of a command that runs a program inside the WSL distribution of an installation. */
    public static List<String> wslCommand(Install i, Function<String, String> settings) {
        final List<String> cmd = new ArrayList<>(List.of(wslExe(settings)));
        if (i.wsl() != null && i.wsl().distro() != null && !i.wsl().distro().isBlank()) {
            cmd.add("-d");
            cmd.add(i.wsl().distro());
        }
        return cmd;
    }

    /** A Windows path as the WSL distribution of an installation sees it (/mnt/d/...), asked of WSL itself. */
    public static String toWslPath(Path windowsPath, Install i, Function<String, String> settings) {
        final List<String> cmd = wslCommand(i, settings);
        cmd.addAll(List.of("-e", "wslpath", "-a", "-u", windowsPath.toAbsolutePath().toString()));
        final String out = runWithInput(cmd, "", 30);
        if (out != null && out.trim().startsWith("/")) {
            return out.trim();
        }
        // wslpath did not answer: the default automount root
        return com.sphere.utils.SettingsManager.toNativePath(windowsPath.toAbsolutePath().toString(),
            com.sphere.utils.OSValidator.Platform.WSL);
    }

    /**
     * Looks at a prefix inside WSL in one call. From whatever was given (the prefix, bin/, geant4-config,
     * lib/cmake/Geant4) the script finds the prefix, the CMake package, geant4-config, the version, the
     * data sets that are there, and the CMake and g++ a build needs in that distribution.
     */
    private static final String WSL_PROBE = """
        p="$1"
        echo "DISTRO $WSL_DISTRO_NAME"
        case "$p" in
          */geant4-config|*/geant4.sh|*/geant4.csh) p=$(dirname "$(dirname "$p")") ;;
          */bin) p=$(dirname "$p") ;;
        esac
        case "$p" in
          */lib/cmake/Geant4|*/lib64/cmake/Geant4) p=$(dirname "$(dirname "$(dirname "$p")")") ;;
        esac
        if [ ! -d "$p" ]; then echo "NODIR $p"; exit 0; fi
        p=$(cd "$p" && pwd -P)
        echo "PREFIX $p"
        for c in lib/cmake/Geant4 lib64/cmake/Geant4 lib/Geant4 cmake $(cd "$p" && ls -d lib/cmake/Geant4-* lib64/cmake/Geant4-* 2>/dev/null); do
          if [ -f "$p/$c/Geant4Config.cmake" ]; then
            echo "CMAKE $p/$c"
            v=$(grep -o 'PACKAGE_VERSION "[0-9.]*"' "$p/$c/Geant4ConfigVersion.cmake" 2>/dev/null | head -n 1 | tr -dc '0-9.')
            [ -n "$v" ] && echo "VERSION $v"
            break
          fi
        done
        if [ -f "$p/bin/geant4-config" ]; then
          echo "CONFIG $p/bin/geant4-config"
          echo "VERSION $(sh "$p/bin/geant4-config" --version 2>/dev/null)"
          sh "$p/bin/geant4-config" --datasets 2>/dev/null | while read -r n v d; do [ -d "$d" ] && echo "DATA $v $d"; done
        fi
        t=$(command -v cmake) && echo "TOOL cmake $t"
        t=$(command -v g++) && echo "TOOL g++ $t"
        exit 0
        """;

    static Install inspectWsl(String distro, String linuxPath, String origin, Function<String, String> settings) {
        final List<String> cmd = new ArrayList<>(List.of(wslExe(settings)));
        if (distro != null) {
            cmd.add("-d");
            cmd.add(distro);
        }
        cmd.addAll(List.of("-e", "sh", "-s", "--", linuxPath));
        final String out = runWithInput(cmd, WSL_PROBE, 90);
        if (out == null) {
            return null; // no WSL, or it did not answer
        }
        String dist = distro, prefix = null, cmake = null, config = null, version = null, cmakeTool = null, gpp = null;
        final Map<String, String> data = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            final int sp = line.indexOf(' ');
            if (sp < 0) {
                continue;
            }
            final String k = line.substring(0, sp), v = line.substring(sp + 1).trim();
            switch (k) {
                case "DISTRO" -> dist = dist == null && !v.isEmpty() ? v : dist;
                case "PREFIX" -> prefix = v;
                case "CMAKE" -> cmake = v;
                case "CONFIG" -> config = v;
                case "VERSION" -> version = v.isEmpty() ? version : v;
                case "DATA" -> {
                    final int s2 = v.indexOf(' ');
                    if (s2 > 0) {
                        data.put(v.substring(0, s2), v.substring(s2 + 1).trim());
                    }
                }
                case "TOOL" -> {
                    if (v.startsWith("cmake ")) {
                        cmakeTool = v.substring(6).trim();
                    } else if (v.startsWith("g++ ")) {
                        gpp = v.substring(4).trim();
                    }
                }
                default -> {
                    // NODIR and the like: nothing found there
                }
            }
        }
        if (prefix == null || (cmake == null && config == null)) {
            return null;
        }
        Path shown;
        try {
            shown = Path.of("\\\\wsl.localhost\\" + (dist == null ? "" : dist) + prefix.replace('/', '\\'));
        } catch (RuntimeException e) {
            shown = Path.of(System.getProperty("user.home"));
        }
        return new Install(shown, null, null, version, origin, data, new Wsl(dist, prefix, cmake, config, cmakeTool, gpp));
    }

    /** Runs a program with a text on its standard input and returns its output, or null. */
    static String runWithInput(List<String> cmd, String input, int seconds) {
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var w = new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(input);
            }
            final StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    b.append(l).append('\n');
                }
            }
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? b.toString() : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static void addCandidate(Map<Path, String> c, Path p, String origin) {
        // A path to geant4-config, to its bin directory, to lib/cmake/Geant4, or a prefix
        Path prefix = p;
        final String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("geant4-config") || name.equals("geant4.sh") || name.equals("geant4.csh")) {
            prefix = p.getParent() != null ? p.getParent().getParent() : p;
        } else if (name.equals("bin")) {
            prefix = p.getParent();
        } else if (name.equalsIgnoreCase("geant4") && p.getParent() != null
            && p.getParent().getFileName() != null && p.getParent().getFileName().toString().equals("cmake")) {
            prefix = p.getParent().getParent().getParent();
        }
        if (prefix != null) {
            c.putIfAbsent(prefix, origin);
        }
    }

    private static void globInto(Map<Path, String> c, Path dir, String glob, String origin) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                c.putIfAbsent(p, origin);
                // geant4-v11.2.1-install style: the prefix may be one level down
                globInto(c, p, "*install*", origin);
            }
        } catch (IOException ignored) {
            // unreadable directory: nothing from there
        }
    }

    /** Conda, mamba, miniforge and pixi environments of this user. */
    static List<Path> condaEnvironments() {
        final Set<Path> out = new LinkedHashSet<>();
        final Path home = Path.of(System.getProperty("user.home"));
        final Path listed = home.resolve(".conda").resolve("environments.txt");
        if (Files.isRegularFile(listed)) {
            try {
                for (String l : Files.readAllLines(listed)) {
                    if (!l.isBlank()) {
                        out.add(Path.of(l.trim()));
                    }
                }
            } catch (IOException ignored) {
                // no list then
            }
        }
        final List<Path> roots = new ArrayList<>();
        for (String r : List.of("miniconda3", "anaconda3", "miniforge3", "mambaforge", "micromamba", ".micromamba",
            "conda", ".pixi")) {
            roots.add(home.resolve(r));
        }
        if (WINDOWS) {
            roots.add(Path.of("C:\\ProgramData\\miniconda3"));
            roots.add(Path.of("C:\\ProgramData\\anaconda3"));
            roots.add(Path.of("C:\\ProgramData\\miniforge3"));
        } else {
            roots.add(Path.of("/opt/conda"));
            roots.add(Path.of("/opt/miniconda3"));
            roots.add(Path.of("/opt/miniforge3"));
        }
        final String condaExe = System.getenv("CONDA_EXE");
        if (condaExe != null) {
            final Path p = Path.of(condaExe);
            if (p.getParent() != null && p.getParent().getParent() != null) {
                roots.add(p.getParent().getParent());
            }
        }
        for (Path r : roots) {
            if (!Files.isDirectory(r)) {
                continue;
            }
            out.add(r);
            final Path envs = r.resolve("envs");
            if (Files.isDirectory(envs)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(envs)) {
                    for (Path e : ds) {
                        out.add(e);
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** What a prefix holds: the CMake package, geant4-config, the version and the data sets. */
    static Install inspect(Path prefix, String origin) {
        if (prefix == null || !Files.isDirectory(prefix)) {
            return null;
        }
        Path cmake = null;
        for (String rel : List.of("lib/cmake/Geant4", "lib64/cmake/Geant4", "Library/lib/cmake/Geant4",
            "lib/Geant4", "cmake")) {
            final Path c = prefix.resolve(rel);
            if (Files.isRegularFile(c.resolve("Geant4Config.cmake"))) {
                cmake = c;
                break;
            }
        }
        if (cmake == null) {
            // lib/cmake/Geant4-11.2.1 in some installs
            for (String lib : List.of("lib/cmake", "lib64/cmake")) {
                final Path d = prefix.resolve(lib);
                if (Files.isDirectory(d)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "Geant4*")) {
                        for (Path p : ds) {
                            if (Files.isRegularFile(p.resolve("Geant4Config.cmake"))) {
                                cmake = p;
                            }
                        }
                    } catch (IOException ignored) {
                        // skip
                    }
                }
            }
        }
        Path config = null;
        for (String rel : WINDOWS ? List.of("bin/geant4-config.exe", "bin/geant4-config", "Library/bin/geant4-config")
            : List.of("bin/geant4-config")) {
            if (Files.isRegularFile(prefix.resolve(rel))) {
                config = prefix.resolve(rel);
                break;
            }
        }
        if (cmake == null && config == null) {
            return null;
        }
        String version = null;
        if (cmake != null) {
            final Path v = cmake.resolve("Geant4ConfigVersion.cmake");
            try {
                if (Files.isRegularFile(v)) {
                    final Matcher m = Pattern.compile("PACKAGE_VERSION \"([0-9.]+)\"").matcher(Files.readString(v));
                    if (m.find()) {
                        version = m.group(1);
                    }
                }
            } catch (IOException ignored) {
                // unknown version
            }
        }
        if (version == null && config != null) {
            version = run(config, "--version");
        }
        return new Install(prefix.toAbsolutePath().normalize(), cmake, config, version == null ? null : version.trim(),
            origin, datasets(prefix, config), null);
    }

    /**
     * The physics data sets, as environment variables Geant4 reads: from
     * geant4-config --datasets, else from share/Geant4/data by directory name.
     */
    static Map<String, String> datasets(Path prefix, Path config) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (config != null) {
            final String text = run(config, "--datasets");
            if (text != null) {
                for (String line : text.split("\\R")) {
                    final String[] f = line.trim().split("\\s+");
                    if (f.length >= 3 && Files.isDirectory(Path.of(f[2]))) {
                        out.put(f[1], f[2]);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            final Map<String, String> byPrefix = Map.ofEntries(
                Map.entry("G4NDL", "G4NEUTRONHPDATA"), Map.entry("G4EMLOW", "G4LEDATA"),
                Map.entry("PhotonEvaporation", "G4LEVELGAMMADATA"), Map.entry("RadioactiveDecay", "G4RADIOACTIVEDATA"),
                Map.entry("G4PARTICLEXS", "G4PARTICLEXSDATA"), Map.entry("G4PII", "G4PIIDATA"),
                Map.entry("RealSurface", "G4REALSURFACEDATA"), Map.entry("G4SAIDDATA", "G4SAIDXSDATA"),
                Map.entry("G4ABLA", "G4ABLADATA"), Map.entry("G4INCL", "G4INCLDATA"),
                Map.entry("G4ENSDFSTATE", "G4ENSDFSTATEDATA"), Map.entry("G4CHANNELING", "G4CHANNELINGDATA"));
            final List<Path> dirs = new ArrayList<>();
            final String env = System.getenv("GEANT4_DATA_DIR");
            if (env != null) {
                dirs.add(Path.of(env));
            }
            dirs.add(prefix.resolve("share").resolve("Geant4").resolve("data"));
            dirs.add(prefix.resolve("Library").resolve("share").resolve("Geant4").resolve("data"));
            try (var share = Files.isDirectory(prefix.resolve("share"))
                ? Files.newDirectoryStream(prefix.resolve("share"), "Geant4*") : null) {
                if (share != null) {
                    for (Path p : share) {
                        dirs.add(p.resolve("data"));
                    }
                }
            } catch (IOException ignored) {
                // skip
            }
            for (Path d : dirs) {
                if (!Files.isDirectory(d)) {
                    continue;
                }
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                    for (Path p : ds) {
                        final String n = p.getFileName().toString();
                        for (Map.Entry<String, String> e : byPrefix.entrySet()) {
                            if (n.startsWith(e.getKey()) && Files.isDirectory(p)) {
                                out.putIfAbsent(e.getValue(), p.toString());
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    /** The environment geant4-bridge runs with: libraries on the search path, data sets set. */
    public static Map<String, String> runtimeEnvironment(Install i) {
        final Map<String, String> env = new LinkedHashMap<>();
        if (i == null) {
            return env;
        }
        env.putAll(i.datasets());
        if (i.wsl() != null) {
            // Linux paths, set inside WSL by the command that starts the engine there
            env.put("LD_LIBRARY_PATH", i.wsl().prefix() + "/lib:" + i.wsl().prefix() + "/lib64");
            return env;
        }
        final String sep = WINDOWS ? ";" : ":";
        final List<String> libs = new ArrayList<>();
        for (String rel : WINDOWS ? List.of("bin", "lib", "Library/bin") : List.of("lib", "lib64")) {
            final Path p = i.prefix().resolve(rel);
            if (Files.isDirectory(p)) {
                libs.add(p.toString());
            }
        }
        final String key = WINDOWS ? "PATH" : System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
            ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
        final String old = System.getenv(key);
        env.put(key, String.join(sep, libs) + (old == null || old.isBlank() ? "" : sep + old));
        return env;
    }

    static Path which(String exe) {
        final String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (d.isBlank()) {
                continue;
            }
            try {
                final Path p = Path.of(d.trim()).resolve(exe);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (RuntimeException ignored) {
                // a malformed PATH entry
            }
        }
        return null;
    }

    /** Runs a program with arguments and returns its output, or null. Scripts run through sh. */
    static String run(Path program, String... args) {
        try {
            final List<String> cmd = new ArrayList<>();
            if (!WINDOWS || !program.toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                if (WINDOWS) {
                    return null; // a shell script on Windows: not runnable here
                }
                cmd.add("sh");
            }
            cmd.add(program.toString());
            cmd.addAll(List.of(args));
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    b.append(l).append('\n');
                }
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? b.toString() : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}
