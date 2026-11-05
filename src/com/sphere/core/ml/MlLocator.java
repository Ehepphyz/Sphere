package com.sphere.core.ml;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.python.env.PyInterpreters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The Pythons Sphere can learn with, and what each holds. Looked at in this order: the one settings.conf names
 * for learning (ML_PYTHON), Sphere's own (PYTHON_EXEC), the virtual environments beside it (a folder of them
 * holds the others: pyastro's neighbor pylhc has PyTorch), the interpreters of this machine, and, when asked,
 * the Pythons of WSL -- where TensorFlow, which has no wheel for every Windows Python, advises to run it.
 *
 * Each is asked by sphere_ml --probe, which reads the packages' metadata and imports nothing: a probe takes a
 * fraction of a second, and its answer is kept for the session, keyed by the interpreter and its date.
 */
public final class MlLocator {

    private MlLocator() {
    }

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** One Python and what it holds: torch, tensorflow, keras (and its backend), jax, pyhf... (null: absent). */
    public record Interpreter(String label, List<String> command, String distro, Map<String, Object> has) {

        public boolean wsl() {
            return distro != null;
        }

        public String version(String framework) {
            final Object v = has.get(framework);
            return v == null ? null : String.valueOf(v);
        }

        public boolean has(String framework) {
            return version(framework) != null;
        }

        /** "Python 3.14.3: torch 2.11.0, keras 3.13.2 on jax, jax 0.9.2, pyhf 0.7.6" */
        public String describe() {
            final List<String> parts = new ArrayList<>();
            for (String f : List.of("torch", "tensorflow", "keras", "jax", "pyhf", "sklearn")) {
                if (has(f)) {
                    parts.add(f + " " + version(f) + (f.equals("keras") && has("keras_backend") ? " on "
                        + version("keras_backend") + kerasFrom() : ""));
                }
            }
            return "Python " + version("python") + ": " + (parts.isEmpty() ? "numpy " + version("numpy")
                + " only" : String.join(", ", parts));
        }

        /** Where Keras's backend was chosen, when someone chose it: " (ML_KERAS_BACKEND)", " (KERAS_BACKEND)". */
        public String kerasFrom() {
            final String from = version("keras_backend_from");
            return from == null || from.equals("installed") ? "" : " (" + (from.equals("KERAS_BACKEND")
                && chosen.containsKey("KERAS_BACKEND") ? "ML_KERAS_BACKEND" : from) + ")";
        }

        /** How the engine names a file of this machine: the path itself, or its /mnt/... form inside WSL. */
        public String path(Path p) {
            return wsl() ? toWsl(p) : p.toAbsolutePath().toString();
        }
    }

    private static final Map<String, Interpreter> PROBED = new HashMap<>();
    private static List<Interpreter> found;
    private static volatile Map<String, String> chosen = Map.of();

    /**
     * What settings.conf chooses for the frameworks, as the probes and the engines get it in their environment:
     * ML_KERAS_BACKEND as KERAS_BACKEND (jax, torch or tensorflow); ML_MEMORY_MB as SPHERE_ML_MEMORY_MB, the
     * most a training may take (sphere_ml's MemoryGovernor otherwise takes what the machine can spare).
     */
    public static Map<String, String> chosen() {
        return chosen;
    }

    /** The interpreters, probed; WSL's too when asked (starting WSL takes a second or two). */
    public static synchronized List<Interpreter> find(Function<String, String> settings, boolean withWsl) {
        final Map<String, String> wanted = new LinkedHashMap<>();
        final String keras = settings == null ? null : settings.apply("ML_KERAS_BACKEND");
        if (keras != null && !keras.isBlank()) {
            wanted.put("KERAS_BACKEND", keras.strip().toLowerCase(Locale.ROOT));
        }
        final String memory = settings == null ? null : settings.apply("ML_MEMORY_MB");
        if (memory != null && memory.strip().matches("\\d+(\\.\\d+)?")) {
            wanted.put("SPHERE_ML_MEMORY_MB", memory.strip());
        }
        if (!wanted.equals(chosen)) {
            chosen = Map.copyOf(wanted);
            forget();
        }
        if (found != null && (!withWsl || found.stream().anyMatch(Interpreter::wsl) || wslDone)) {
            return found;
        }
        final Set<String> paths = new LinkedHashSet<>();
        for (String key : List.of("ML_PYTHON", "PYTHON_EXEC")) {
            final String v = settings == null ? null : settings.apply(key);
            if (v != null && !v.isBlank() && Files.isRegularFile(Path.of(v.strip()))) {
                paths.add(Path.of(v.strip()).toAbsolutePath().normalize().toString());
                siblings(Path.of(v.strip()), paths);
            }
        }
        try {
            for (PyInterpreters.Found f : PyInterpreters.discover(settings == null ? null : settings.apply("PYTHON_EXEC"))) {
                if (!f.path().toLowerCase(Locale.ROOT).contains("windowsapps")) {
                    paths.add(Path.of(f.path()).toAbsolutePath().normalize().toString());
                }
            }
        } catch (RuntimeException ignored) {
            // the configured ones and their neighbors are enough
        }
        final List<Interpreter> out = new ArrayList<>();
        for (String p : paths) {
            final Interpreter i = probe(p, List.of(p), null);
            if (i != null) {
                out.add(i);
            }
        }
        if (withWsl && WINDOWS) {
            for (String distro : distros(settings)) {
                final List<String> cmd = List.of(wslExe(settings), "-d", distro, "--", "python3");
                final Interpreter i = probe("wsl:" + distro, cmd, distro);
                if (i != null) {
                    out.add(i);
                }
            }
            wslDone = true;
        }
        found = out;
        return out;
    }

    private static boolean wslDone;

    /** Forgets what was found: after an installation, or a change of settings. */
    public static synchronized void forget() {
        found = null;
        wslDone = false;
        PROBED.clear();
    }

    /**
     * The interpreter to learn with a framework: the first holding it, in the order of find(); for "keras" one
     * holding Keras, and for "tensorflow" one holding TensorFlow itself. WSL is looked at only when no Python of
     * this machine has it.
     */
    public static Interpreter best(Function<String, String> settings, String framework) {
        for (Interpreter i : find(settings, false)) {
            if (i.has(framework)) {
                return i;
            }
        }
        for (Interpreter i : find(settings, true)) {
            if (i.has(framework)) {
                return i;
            }
        }
        return null;
    }

    /** The environments beside a virtual environment's: its parent folder's other venvs. */
    private static void siblings(Path python, Set<String> out) {
        final Path venv = python.toAbsolutePath().getParent() == null ? null : python.toAbsolutePath().getParent().getParent();
        final Path folder = venv == null ? null : venv.getParent();
        if (folder == null || !Files.isRegularFile(venv.resolve("pyvenv.cfg"))) {
            return;
        }
        try (var s = Files.list(folder)) {
            for (Path d : (Iterable<Path>) s.sorted()::iterator) {
                final Path exe = d.resolve(WINDOWS ? "Scripts/python.exe" : "bin/python");
                if (Files.isRegularFile(d.resolve("pyvenv.cfg")) && Files.isRegularFile(exe)) {
                    out.add(exe.toAbsolutePath().normalize().toString());
                }
            }
        } catch (IOException ignored) {
            // the configured one stands alone
        }
    }

    /** sphere_ml --probe through an interpreter, cached by its path and date. */
    private static Interpreter probe(String label, List<String> command, String distro) {
        String key = label;
        try {
            if (distro == null) {
                key += "@" + Files.getLastModifiedTime(Path.of(command.get(0))).toMillis();
            }
        } catch (IOException e) {
            return null;
        }
        final Interpreter known = PROBED.get(key);
        if (known != null) {
            return known;
        }
        try {
            final Path lib = Bridge.libraries();
            final Map<String, String> vars = new LinkedHashMap<>(chosen);
            vars.put("PYTHONPATH", toWsl(lib));
            final List<String> cmd = withEnvironment(command, distro, vars);
            cmd.add(distro != null ? toWsl(lib.resolve("sphere_ml.py")) : lib.resolve("sphere_ml.py").toString());
            cmd.add("--probe");
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            final Map<String, String> env = new HashMap<>();
            Bridge.environment(env);
            env.putAll(chosen);
            pb.environment().putAll(env);
            // never read: a Python that writes much there would otherwise fill the pipe and stop
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            final Process p = pb.start();
            p.getOutputStream().close();
            final byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            final String text = new String(out, StandardCharsets.UTF_8).strip();
            final String last = text.substring(Math.max(0, text.lastIndexOf('\n') + 1));
            final Map<String, Object> has = new LinkedHashMap<>(G4Json.object(last));
            has.values().removeIf(v -> v == null);
            final Interpreter i = new Interpreter(distro == null ? command.get(0) : label, List.copyOf(command), distro,
                has);
            PROBED.put(key, i);
            return i;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * An interpreter's command, given variables: inside WSL the environment of Windows does not cross, so they
     * go on the command line, wsl -d distro -- env NAME=value ... python3; a Python of this machine takes them
     * from its process.
     */
    static List<String> withEnvironment(List<String> command, String distro, Map<String, String> vars) {
        final List<String> cmd = new ArrayList<>(command);
        if (distro != null) {
            final int at = cmd.indexOf("--") + 1;
            final List<String> env = new ArrayList<>();
            env.add("env");
            vars.forEach((k, v) -> env.add(k + "=" + v));
            cmd.addAll(at, env);
        }
        return cmd;
    }

    /** wsl.exe: settings.conf WIN_WSL when it names one, else the one on the PATH. */
    static String wslExe(Function<String, String> settings) {
        final String v = settings == null ? null : settings.apply("WIN_WSL");
        return v != null && !v.isBlank() && Files.isRegularFile(Path.of(v.strip())) ? v.strip() : "wsl.exe";
    }

    /** The WSL distributions (wsl -l -q, which speaks UTF-16). */
    static List<String> distros(Function<String, String> settings) {
        final List<String> out = new ArrayList<>();
        try {
            final Process p = new ProcessBuilder(wslExe(settings), "-l", "-q").redirectErrorStream(true).start();
            final byte[] raw = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            for (String line : new String(raw, StandardCharsets.UTF_16LE).split("\\R")) {
                final String d = line.replace("\u0000", "").strip();
                if (!d.isEmpty() && !d.toLowerCase(Locale.ROOT).startsWith("docker-desktop")) {
                    out.add(d);
                }
            }
        } catch (IOException e) {
            // no WSL
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** C:\a\b as WSL mounts it: /mnt/c/a/b. */
    static String toWsl(Path p) {
        final String s = p.toAbsolutePath().toString().replace('\\', '/');
        if (s.length() > 2 && s.charAt(1) == ':') {
            return "/mnt/" + Character.toLowerCase(s.charAt(0)) + s.substring(2);
        }
        return s;
    }
}
