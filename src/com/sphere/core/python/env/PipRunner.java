package com.sphere.core.python.env;

import com.sphere.components.rootview.Json;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What changes an environment: pip, or uv when it is installed (the same
 * commands, ten to a hundred times faster), run with their output streamed
 * line by line and stoppable; and pip's dry run, which says what an install
 * would change before it changes anything.
 */
public final class PipRunner {

    private volatile Process current;
    private volatile boolean cancelled;

    /** One change an install would make. */
    public record Change(String name, String from, String to) {
        public String kind() {
            if (from == null) return "install";
            final Pep440.Jump j = Pep440.jump(Pep440.parse(from), Pep440.parse(to));
            return switch (j) {
                case DOWNGRADE -> "downgrade";
                case NONE -> "reinstall";
                default -> "upgrade (" + j.name().toLowerCase(Locale.ROOT) + ")";
            };
        }
    }

    private static volatile String uv;
    private static volatile boolean uvLooked;

    /** uv on the PATH, or null. */
    public static String uv() {
        if (uvLooked) return uv;
        uvLooked = true;
        final boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        final List<String> places = new ArrayList<>();
        final String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(File.pathSeparator)) places.add(dir);
        final String home = System.getProperty("user.home");
        places.add(home + "/.local/bin");
        places.add(home + "/.cargo/bin");
        if (win) places.add(System.getenv("LOCALAPPDATA") + "/Programs/uv");
        for (String dir : places) {
            if (dir == null || dir.isBlank()) continue;
            final File f = new File(dir, win ? "uv.exe" : "uv");
            if (f.isFile() && f.canExecute()) {
                uv = f.getAbsolutePath();
                return uv;
            }
        }
        return null;
    }

    /** The command of a pip operation: uv pip ... --python exe when asked and possible, else exe -m pip ... */
    public static List<String> command(PyEnv env, boolean useUv, String verb, List<String> args) {
        final List<String> cmd = new ArrayList<>();
        final String u = useUv ? uv() : null;
        if (u != null && !verb.equals("check")) {
            cmd.add(u);
            cmd.add("pip");
            cmd.add(verb);
            cmd.add("--python");
            cmd.add(env.executable);
            for (String a : args) {
                // uv does not ask before uninstalling, and has no -y.
                if (verb.equals("uninstall") && a.equals("-y")) continue;
                cmd.add(a);
            }
            return cmd;
        }
        cmd.add(env.executable);
        cmd.add("-m");
        cmd.add("pip");
        cmd.add(verb);
        cmd.addAll(args);
        cmd.add("--disable-pip-version-check");
        if (!verb.equals("check")) cmd.add("--no-input");
        return cmd;
    }

    /** Runs a command, every line of its output to out as it comes; answers its exit code. */
    public int run(List<String> command, Consumer<String> out) throws IOException, InterruptedException {
        cancelled = false;
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PIP_DISABLE_PIP_VERSION_CHECK", "1");
        pb.environment().put("PIP_NO_INPUT", "1");
        pb.environment().put("PIP_PROGRESS_BAR", "off");
        pb.environment().put("NO_COLOR", "1");
        pb.redirectInput(ProcessBuilder.Redirect.from(PyProbe.nullFile()));
        out.accept("$ " + String.join(" ", command));
        final Process p = pb.start();
        current = p;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.accept(line);
        } finally {
            current = null;
        }
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("stopped after ten minutes");
        }
        if (cancelled) out.accept("(stopped)");
        return p.exitValue();
    }

    /** Stops what runs, its children too (pip starts builds). */
    public void cancel() {
        cancelled = true;
        final Process p = current;
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    public boolean running() {
        return current != null;
    }

    /**
     * What installing specs would change, by pip's own resolver: pip install
     * --dry-run --report (pip 22.2 and later). Null when this pip cannot tell.
     */
    public List<Change> preview(PyEnv env, List<String> specs, List<String> options, Consumer<String> out)
        throws IOException, InterruptedException {
        if (env.pip == null || !Pep440.specifier(">=22.2").contains(env.pip)) return null;
        final Path report = Files.createTempFile("sphere-pip-report", ".json");
        try {
            final List<String> args = new ArrayList<>(List.of("--dry-run", "--quiet", "--report", report.toString()));
            args.addAll(options);
            args.addAll(specs);
            final int code = run(command(env, false, "install", args), out);
            if (code != 0) return null;
            final Object root = Json.parse(Files.readString(report, StandardCharsets.UTF_8));
            final List<Change> changes = new ArrayList<>();
            for (Object item : Json.list(root, "install")) {
                final Object meta = Json.get(item, "metadata");
                final String name = Json.text(meta, "name", "?");
                final String version = Json.text(meta, "version", "?");
                final PyEnv.Pkg now = env.packages.get(Pep440.normalize(name));
                changes.add(new Change(name, now == null ? null : now.version, version));
            }
            return changes;
        } finally {
            Files.deleteIfExists(report);
        }
    }

    /** Creates a virtual environment with a base interpreter (uv venv when available). */
    public int createVenv(String baseExecutable, Path where, boolean systemSitePackages, boolean useUv, Consumer<String> out)
        throws IOException, InterruptedException {
        final List<String> cmd = new ArrayList<>();
        final String u = useUv ? uv() : null;
        if (u != null) {
            cmd.addAll(List.of(u, "venv", "--python", baseExecutable, "--seed"));
            if (systemSitePackages) cmd.add("--system-site-packages");
            cmd.add(where.toString());
        } else {
            cmd.addAll(List.of(baseExecutable, "-m", "venv"));
            if (systemSitePackages) cmd.add("--system-site-packages");
            cmd.add(where.toString());
        }
        return run(cmd, out);
    }

    /** The interpreter of a virtual environment folder. */
    public static Path venvPython(Path venv) {
        final boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return win ? venv.resolve("Scripts").resolve("python.exe") : venv.resolve("bin").resolve("python");
    }
}
