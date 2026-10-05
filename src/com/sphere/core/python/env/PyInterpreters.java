package com.sphere.core.python.env;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Every Python on the machine, found where they are installed rather than
 * typed by hand: the Windows launcher's list (py -0p), the PATH, the usual
 * installation folders, conda's environment list, pyenv, uv, Poetry and
 * pipenv, the venvs of the workspace and those beside the configured one.
 * A venv's version is read from its pyvenv.cfg and a conda environment's
 * from its conda-meta, so that only the others are started, in parallel.
 */
public final class PyInterpreters {

    private PyInterpreters() {
    }

    /** One interpreter: where, which version, what kind, how it was found. */
    public record Found(String path, String version, String kind, String origin) {
        public String label() {
            return "Python " + (version.isEmpty() ? "?" : version) + "  ·  " + kind + "  ·  " + path;
        }
    }

    private static final boolean WIN = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** The interpreters, the configured one first. */
    public static List<Found> discover(String configured) {
        final Map<String, String[]> seen = new LinkedHashMap<>();
        if (configured != null && !configured.isBlank()) add(seen, Path.of(configured), "settings.conf");
        launcher(seen);
        final String home = System.getProperty("user.home");
        final String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String n : WIN ? new String[]{"python.exe", "python3.exe"} : new String[]{"python3", "python"}) {
                    add(seen, Path.of(dir.strip(), n), "PATH");
                }
            }
        }
        if (WIN) {
            final String local = System.getenv("LOCALAPPDATA");
            final String appdata = System.getenv("APPDATA");
            final String programs = System.getenv("ProgramFiles");
            children(seen, local == null ? null : Path.of(local, "Programs", "Python"), "python.exe", "python.org");
            children(seen, Path.of("C:\\"), "python.exe", "python.org", "Python");
            children(seen, programs == null ? null : Path.of(programs), "python.exe", "python.org", "Python");
            children(seen, appdata == null ? null : Path.of(appdata, "uv", "python"), "python.exe", "uv");
            children(seen, Path.of(home, ".pyenv", "pyenv-win", "versions"), "python.exe", "pyenv");
            for (String env : new String[]{"ucrt64", "mingw64", "clang64"}) add(seen, Path.of("C:\\msys64", env, "bin", "python.exe"), "MSYS2");
            children(seen, local == null ? null : Path.of(local, "pypoetry", "Cache", "virtualenvs"), "Scripts\\python.exe", "Poetry");
        } else {
            for (String dir : new String[]{"/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"}) {
                final File[] list = new File(dir).listFiles((d, n) -> n.matches("python3(\\.[0-9]+)?"));
                if (list != null) for (File f : list) add(seen, f.toPath(), "system");
            }
            children(seen, Path.of(home, ".pyenv", "versions"), "bin/python", "pyenv");
            children(seen, Path.of(home, ".local", "share", "uv", "python"), "bin/python3", "uv");
            children(seen, Path.of(home, ".cache", "pypoetry", "virtualenvs"), "bin/python", "Poetry");
        }
        conda(seen, home);
        // Virtual environments: the workspace's, the usual folders, and the neighbours of the configured one.
        final Path cwd = Path.of("").toAbsolutePath();
        for (String n : new String[]{"venv", ".venv", "env"}) add(seen, PipRunner.venvPython(cwd.resolve(n)), "workspace");
        venvsIn(seen, cwd.resolve("WorkSpace"), "workspace");
        venvsIn(seen, Path.of(home, ".virtualenvs"), "virtualenvs");
        venvsIn(seen, Path.of(home, "Envs"), "virtualenvs");
        venvsIn(seen, Path.of(home, ".local", "share", "virtualenvs"), "pipenv");
        if (configured != null && !configured.isBlank()) {
            final Path prefix = prefixOf(Path.of(configured));
            if (prefix != null && prefix.getParent() != null && Files.isRegularFile(prefix.resolve("pyvenv.cfg"))) {
                venvsIn(seen, prefix.getParent(), "beside the configured venv");
            }
        }
        // Versions: from the files when they say it, else by asking, in parallel.
        final List<Found> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final Map<String, Future<String>> asked = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> e : seen.entrySet()) {
                final Path exe = Path.of(e.getValue()[0]);
                final String known = versionFromFiles(exe);
                asked.put(e.getKey(), known != null ? java.util.concurrent.CompletableFuture.completedFuture(known)
                    : pool.submit(() -> versionByRunning(exe)));
            }
            for (Map.Entry<String, String[]> e : seen.entrySet()) {
                String v;
                try {
                    v = asked.get(e.getKey()).get(4, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    v = null;
                }
                if (v == null) continue;
                final Path exe = Path.of(e.getValue()[0]);
                // python.exe and python3.exe of one folder are one interpreter.
                final String version = v;
                if (out.stream().anyMatch(f -> f.version().equals(version)
                    && String.valueOf(Path.of(f.path()).getParent()).equalsIgnoreCase(String.valueOf(exe.getParent())))) {
                    continue;
                }
                out.add(new Found(exe.toString(), v, kind(exe), e.getValue()[1]));
            }
        }
        return out;
    }

    private static void add(Map<String, String[]> seen, Path exe, String origin) {
        try {
            if (exe == null || !Files.isRegularFile(exe)) return;
            // The Store's aliases in WindowsApps are empty files that open the Store.
            if (WIN && exe.toString().toLowerCase(Locale.ROOT).contains("windowsapps") && Files.size(exe) == 0) return;
            String key;
            try {
                key = exe.toRealPath().toString();
            } catch (IOException e) {
                key = exe.toAbsolutePath().toString();
            }
            if (WIN) key = key.toLowerCase(Locale.ROOT);
            seen.putIfAbsent(key, new String[]{exe.toAbsolutePath().normalize().toString(), origin});
        } catch (IOException | RuntimeException ignored) {
            // unreadable
        }
    }

    /** Each folder of base (whose name starts with prefix when given) holding the relative interpreter. */
    private static void children(Map<String, String[]> seen, Path base, String relative, String origin, String... prefix) {
        if (base == null || !Files.isDirectory(base)) return;
        try (Stream<Path> s = Files.list(base)) {
            s.filter(Files::isDirectory).filter(d -> prefix.length == 0
                    || d.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(prefix[0].toLowerCase(Locale.ROOT)))
                .sorted().forEach(d -> add(seen, d.resolve(relative), origin));
        } catch (IOException ignored) {
            // unreadable
        }
    }

    private static void venvsIn(Map<String, String[]> seen, Path base, String origin) {
        if (base == null || !Files.isDirectory(base)) return;
        try (Stream<Path> s = Files.list(base)) {
            s.filter(d -> Files.isRegularFile(d.resolve("pyvenv.cfg"))).sorted()
                .forEach(d -> add(seen, PipRunner.venvPython(d), origin));
        } catch (IOException ignored) {
            // unreadable
        }
    }

    /** py -0p: the interpreters the Windows launcher knows (python.org, the Store, registered ones). */
    private static void launcher(Map<String, String[]> seen) {
        if (!WIN) return;
        try {
            final Process p = new ProcessBuilder("py", "-0p").redirectErrorStream(true).start();
            final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                final java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z]:\\\\.*\\.exe)\\s*$").matcher(line);
                if (m.find()) add(seen, Path.of(m.group(1).strip()), "py launcher");
            }
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            // no launcher
        }
    }

    /** conda's environments.txt and the usual base folders, with their envs. */
    private static void conda(Map<String, String[]> seen, String home) {
        final List<Path> prefixes = new ArrayList<>();
        final Path list = Path.of(home, ".conda", "environments.txt");
        try {
            if (Files.isRegularFile(list)) for (String l : Files.readAllLines(list)) if (!l.isBlank()) prefixes.add(Path.of(l.strip()));
        } catch (IOException ignored) {
            // no list
        }
        for (String base : new String[]{"anaconda3", "miniconda3", "miniforge3", "mambaforge", "micromamba"}) {
            prefixes.add(Path.of(home, base));
            if (WIN) prefixes.add(Path.of("C:\\ProgramData", base));
        }
        final String condaPrefix = System.getenv("CONDA_PREFIX");
        if (condaPrefix != null) prefixes.add(Path.of(condaPrefix));
        for (Path prefix : new ArrayList<>(prefixes)) {
            final Path envs = prefix.resolve("envs");
            if (Files.isDirectory(envs)) {
                try (Stream<Path> s = Files.list(envs)) {
                    s.filter(Files::isDirectory).forEach(prefixes::add);
                } catch (IOException ignored) {
                    // unreadable
                }
            }
        }
        for (Path prefix : prefixes) add(seen, WIN ? prefix.resolve("python.exe") : prefix.resolve("bin").resolve("python"), "conda");
    }

    /** The environment's prefix: the folder above Scripts or bin, else the interpreter's folder. */
    static Path prefixOf(Path exe) {
        final Path dir = exe.toAbsolutePath().getParent();
        if (dir == null) return null;
        final String n = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
        return (n.equals("scripts") || n.equals("bin")) && dir.getParent() != null ? dir.getParent() : dir;
    }

    /** The version a venv's pyvenv.cfg or a conda environment's conda-meta records, without starting it. */
    static String versionFromFiles(Path exe) {
        final Path prefix = prefixOf(exe);
        if (prefix == null) return null;
        final Path cfg = prefix.resolve("pyvenv.cfg");
        try {
            if (Files.isRegularFile(cfg)) {
                for (String l : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                    final String[] kv = l.split("=", 2);
                    if (kv.length == 2 && (kv[0].strip().equals("version") || kv[0].strip().equals("version_info"))) {
                        return kv[1].strip().replaceAll("\\.final\\.0$", "");
                    }
                }
            }
            final Path meta = prefix.resolve("conda-meta");
            if (Files.isDirectory(meta)) {
                try (Stream<Path> s = Files.list(meta)) {
                    for (Path f : (Iterable<Path>) s::iterator) {
                        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^python-([0-9]+\\.[0-9]+\\.[0-9]+)-")
                            .matcher(f.getFileName().toString());
                        if (m.find()) return m.group(1);
                    }
                }
            }
        } catch (IOException ignored) {
            // ask it
        }
        return null;
    }

    /** python --version, under a second, or null when it is no working Python. */
    static String versionByRunning(Path exe) {
        try {
            final Process p = new ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(PyProbe.nullFile())).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.exitValue() == 0 && out.startsWith("Python ") ? out.substring(7).strip() : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** What kind of environment, from its files. */
    public static String kind(Path exe) {
        final String s = exe.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        final Path prefix = prefixOf(exe);
        if (s.contains("/.pixi/")) return "pixi";
        if (prefix != null && Files.isDirectory(prefix.resolve("conda-meta"))) {
            return s.contains("/envs/") ? "conda env" : s.contains("micromamba") ? "micromamba" : "conda (base)";
        }
        if (prefix != null && Files.isRegularFile(prefix.resolve("pyvenv.cfg"))) {
            try {
                final String cfg = Files.readString(prefix.resolve("pyvenv.cfg"), StandardCharsets.UTF_8);
                if (cfg.contains("uv =")) return "uv venv";
                if (s.contains("pypoetry")) return "poetry venv";
                if (cfg.contains("virtualenv =")) return "virtualenv";
            } catch (IOException ignored) {
                // a venv anyway
            }
            return "venv";
        }
        if (s.contains("/.pyenv/") || s.contains("/pyenv-win/")) return "pyenv";
        if (s.contains("/windowsapps/")) return "Microsoft Store";
        if (s.contains("/msys64/")) return "MSYS2";
        if (s.contains("/uv/python/")) return "uv-managed";
        return "system";
    }
}
