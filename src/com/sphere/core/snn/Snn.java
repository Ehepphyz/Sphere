package com.sphere.core.snn;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.ml.MlLink;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Sphere Neural Network seen from Java: every module of the machine a neuron (ROOT and its parts, Geant4, CLHEP,
 * FastJet and fjcontrib, HepMC3, LHAPDF, Pythia8, YODA, Rivet, Golem95, PyTorch, TensorFlow, JAX, pyhf, Julia's packages,
 * Sphere's own translations...), the ways data goes from one to another its synapses. The cortex, sphere_snn.py, finds
 * the neurons on this side of WSL and the other, chooses installations that can share one program, writes how every
 * language reaches them, learns from what it runs and runs each neuron and synapse for real.
 *
 * <p>Sphere is a sandbox: nothing of the machine is written. The network's configuration is settings.conf's [SNN]
 * section and nothing else -- one key a neuron, the installation it is taken from; Sphere writes it from what it finds
 * and keeps it true, a line the user changed staying theirs. What every process Sphere starts is given comes from it:
 *
 * <pre>
 *   SPHERE_SNN          the network's folder, one subfolder a side (windows, wsl-Debian, linux)
 *   SPHERE_SNN_NEURONS  "root=6.41.01;fastjet=3.5.2;geant4=11.4.0;...": what a shell or a CMakeLists can test
 *   PATH, CMAKE_PREFIX_PATH, PKG_CONFIG_PATH   the side's snn and snn-config, find_package(SphereNN), pkg-config snn-x
 *   PYTHONPATH          Sphere's bridge library, whose sitecustomize puts the network's import finder in every Python
 *                       Sphere starts (none of the machine's Pythons is changed)
 *   JULIA_LOAD_PATH     a folder of Sphere holding SphereSNN, never precompiled into the user's depot
 *   PYTHONPYCACHEPREFIX bytecode kept inside Sphere, not beside the machine's packages
 * </pre>
 *
 * Switching an installation is editing settings.conf: the next process takes it, the network's files are written again
 * in the background. Nothing here blocks an engine's start.
 */
public final class Snn {

    private Snn() {
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final AtomicBoolean PREPARED = new AtomicBoolean();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static volatile long settingsSeen = -1;

    /** A neuron: a module of the machine, its version, the side it is on and the languages that reach it. */
    public record Neuron(String id, String title, String version, String side, String languages, String where, String family) {
        public boolean reaches(String language) {
            return List.of(languages.split(",")).contains(language);
        }
    }

    /** The network's folder: one subfolder a side. */
    public static Path folder() {
        return Bridge.folder().resolve("snn");
    }

    public static Path sideFolder(String side) {
        return folder().resolve(side);
    }

    /** windows, wsl-&lt;distribution&gt; or linux: the side this Sphere runs on. */
    public static String side() {
        if (WINDOWS) {
            return "windows";
        }
        try {
            if (Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT).contains("microsoft")) {
                final String d = System.getenv("WSL_DISTRO_NAME");
                return d == null || d.isBlank() ? "wsl" : "wsl-" + d;
            }
        } catch (IOException | RuntimeException e) {
            // not Linux
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "macos" : "linux";
    }

    /** The sides the network has been seen from: the subfolders holding a neurons.tsv. */
    public static List<String> sides() {
        final List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder())) {
            s.filter(p -> Files.isRegularFile(p.resolve("neurons.tsv"))).map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        } catch (IOException e) {
            // not written yet
        }
        return out;
    }

    /** The neurons as one side sees them (Windows sees those of WSL too), from neurons.tsv. */
    public static List<Neuron> neurons(String side) {
        final List<Neuron> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(sideFolder(side).resolve("neurons.tsv"), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] f = java.util.Arrays.copyOf(line.split("\t", -1), 7);
                for (int k = 0; k < f.length; k++) {
                    f[k] = f[k] == null ? "" : f[k];
                }
                out.add(new Neuron(f[0], f[1], f[2], f[3], f[4], f[5], f[6]));
            }
        } catch (IOException e) {
            // not written yet: the cortex runs in the background
        }
        return out;
    }

    public static List<Neuron> neurons() {
        return neurons(side());
    }

    /** "root=6.41.01;fastjet=3.5.2;...": the neurons of this side. */
    static String summary(List<Neuron> neurons) {
        final StringBuilder s = new StringBuilder();
        for (Neuron n : neurons) {
            if (n.side().equals(side())) {
                s.append(s.length() == 0 ? "" : ";").append(n.id()).append('=').append(n.version().replace(';', ','));
            }
        }
        return s.toString();
    }

    // ------------------------------------------------------------------
    // What every process Sphere starts is given
    // ------------------------------------------------------------------

    /**
     * Called for every process Sphere starts, by Bridge.environment and Bridge.reach: the network as settings.conf [SNN]
     * declares it, from the side's env.tsv the cortex wrote. A settings.conf changed since is written again in the
     * background: the switch reaches the next process.
     */
    public static void environment(Map<String, String> env) {
        env.put("SPHERE_SNN", folder().toString());
        final String summary = summary(neurons());
        if (!summary.isEmpty()) {
            env.put("SPHERE_SNN_NEURONS", summary);
        }
        final Path mine = sideFolder(side());
        final String pathKey = env.containsKey("Path") ? "Path" : "PATH";
        boolean applied = false;
        try {
            for (String line : Files.readAllLines(mine.resolve("env.tsv"), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] f = line.split("\t", 3);
                if (f.length < 3 || f[1].equals("SPHERE_SNN")) {
                    continue;
                }
                apply(env, f[0], f[1].equals("PATH") ? pathKey : f[1], f[2]);
                applied = true;
            }
        } catch (IOException e) {
            // not written yet
        }
        if (!applied && Files.isDirectory(mine.resolve("bin"))) {
            prepend(env, pathKey, mine.resolve("bin").toString());
        }
        if (WINDOWS) {
            crossIntoWsl(env, List.of("SPHERE_SNN/p", "SPHERE_SNN_NEURONS", "PYTHONPYCACHEPREFIX/p"));
        }
        prepare();
        followSettings();
    }

    private static void apply(Map<String, String> env, String how, String key, String value) {
        switch (how) {
            case "prepend" -> {
                final String[] parts = value.split(java.util.regex.Pattern.quote(File.pathSeparator));
                for (int k = parts.length - 1; k >= 0; k--) {
                    if (!parts[k].isBlank()) {
                        prepend(env, key, parts[k]);
                    }
                }
            }
            case "default" -> env.putIfAbsent(key, value);
            case "append" -> {
                // JULIA_LOAD_PATH: an empty entry first stands for Julia's own load path
                final String was = env.get(key);
                if (was == null || was.isBlank()) {
                    env.put(key, File.pathSeparator + value);
                } else if (!List.of(was.split(java.util.regex.Pattern.quote(File.pathSeparator))).contains(value)) {
                    env.put(key, was + File.pathSeparator + value);
                }
            }
            default -> env.put(key, value);
        }
    }

    private static void prepend(Map<String, String> env, String key, String dir) {
        final String was = env.get(key);
        if (was == null || was.isBlank()) {
            env.put(key, dir);
        } else if (!List.of(was.split(java.util.regex.Pattern.quote(File.pathSeparator))).contains(dir)) {
            env.put(key, dir + File.pathSeparator + was);
        }
    }

    /** WSLENV names what crosses into a Linux process wsl.exe starts; /p translates a path. */
    private static void crossIntoWsl(Map<String, String> env, List<String> names) {
        final String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("WSLENV")).findFirst().orElse("WSLENV");
        final List<String> all = new ArrayList<>();
        final String was = env.get(key);
        if (was != null && !was.isBlank()) {
            all.addAll(List.of(was.split(":")));
        }
        for (String n : names) {
            final String bare = n.contains("/") ? n.substring(0, n.indexOf('/')) : n;
            if (env.containsKey(bare) && all.stream().noneMatch(a -> a.equals(bare) || a.startsWith(bare + "/"))) {
                all.add(n);
            }
        }
        env.put(key, String.join(":", all));
    }

    /** settings.conf changed since the network's files were written: they are written again, in the background. */
    private static void followSettings() {
        final Path conf = Path.of(SettingsManager.CONFIG_FILENAME).toAbsolutePath();
        long now;
        try {
            now = Files.getLastModifiedTime(conf).toMillis();
        } catch (IOException e) {
            return;
        }
        if (settingsSeen < 0) {
            settingsSeen = now;
            return;
        }
        if (now != settingsSeen && !REFRESHING.get()) {
            settingsSeen = now;
            background("sphere-snn-follow", () -> refresh(false, line -> { }));
        }
    }

    /**
     * Once a session, in the background: the neurons found on both sides of WSL, every way in written inside Sphere,
     * settings.conf [SNN] made true, and every API indexed (what heals programs and what the neural model learns from).
     */
    public static void prepare() {
        if (!PREPARED.compareAndSet(false, true)) {
            return;
        }
        background("sphere-snn-prepare", () -> {
            refresh(false, line -> { });
            for (String d : distros()) {
                cortex(List.of("index"), d, 900, line -> { });
            }
            cortex(List.of("index"), null, 900, line -> { });
        });
    }

    private interface Job {
        void run() throws IOException;
    }

    private static void background(String name, Job job) {
        final Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (IOException | RuntimeException e) {
                AppLogger.warn("snn: " + e.getMessage());
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /** The WSL distributions the network reaches into (none away from Windows): [SNN] SNN_WSL, else every one. */
    public static List<String> distros() {
        if (!WINDOWS) {
            return List.of();
        }
        String chosen = null;
        try {
            chosen = new SettingsManager().getProperty("SNN", "SNN_WSL");
            if (chosen == null) {
                chosen = new SettingsManager().getProperty("SNN_WSL");
            }
        } catch (RuntimeException e) {
            // no such key: every distribution
        }
        if (chosen != null && !chosen.isBlank()) {
            return List.of(chosen.split("\\s*,\\s*"));
        }
        return MlLink.wslDistros().stream().filter(d -> !d.toLowerCase(Locale.ROOT).contains("docker")).toList();
    }

    /**
     * Finds the neurons and writes every way in: here (from Windows the cortex looks into WSL too), then in each WSL
     * distribution, where the C++ neurons are; then settings.conf [SNN] is made true from both.
     */
    public static void refresh(boolean rescan, Consumer<String> out) throws IOException {
        if (!REFRESHING.compareAndSet(false, true)) {
            return;
        }
        try {
            final List<String> args = new ArrayList<>(List.of("write"));
            if (rescan) {
                args.add("--rescan");
            }
            final int here = cortex(args, null, 900, out);
            if (here != 0) {
                throw new IOException("the cortex stopped (" + here + ") on " + side());
            }
            for (String d : distros()) {
                final int there = cortex(args, d, 900, out);
                if (there != 0) {
                    AppLogger.warn("snn: the cortex stopped (" + there + ") in WSL " + d);
                }
            }
            declare(out);
        } finally {
            try {
                settingsSeen = Files.getLastModifiedTime(Path.of(SettingsManager.CONFIG_FILENAME).toAbsolutePath()).toMillis();
            } catch (IOException e) {
                // no settings.conf
            }
            REFRESHING.set(false);
        }
    }

    /** settings.conf [SNN] made true: every module found, the user's own lines kept as they are. */
    public static int declare(Consumer<String> out) throws IOException {
        return cortex(List.of("declare"), null, 300, out);
    }

    /**
     * Runs the cortex, here or in a WSL distribution, each line it prints handed to out as it comes; its exit status.
     */
    public static int cortex(List<String> args, String distro, long seconds, Consumer<String> out) throws IOException {
        return cortex(args, distro, seconds, out, null);
    }

    /** The same, the process handed to started as soon as it runs (for :kill and a cancel to reach it). */
    public static int cortex(List<String> args, String distro, long seconds, Consumer<String> out,
                             Consumer<Process> started) throws IOException {
        final Path lib = Bridge.libraries();
        final List<String> cmd = new ArrayList<>();
        if (distro == null) {
            final String python = Bridge.python(new SettingsManager());
            if (python == null) {
                throw new IOException("no Python to run the network's cortex: name one in settings.conf (PYTHON_EXEC)");
            }
            cmd.add(python);
            cmd.add(lib.resolve("sphere_snn.py").toString());
        } else {
            cmd.addAll(List.of(MlLink.wslExe(), "-d", distro, "--", "env", "SPHERE_SNN=" + MlLink.wslPath(folder()),
                "SPHERE_BRIDGE=" + MlLink.wslPath(Bridge.folder()), "PYTHONDONTWRITEBYTECODE=1",
                "python3", MlLink.wslPath(lib.resolve("sphere_snn.py"))));
        }
        cmd.addAll(args);
        final ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("SPHERE_SNN", folder().toString());
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        final Process p = pb.start();
        p.getOutputStream().close();
        if (started != null) {
            started.accept(p);
        }
        final Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.accept(line.replace("\0", ""));
                }
            } catch (IOException ended) {
                // the process is gone
            }
        }, "sphere-snn-cortex");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                out.accept("(stopped after " + seconds + " s)");
                return -1;
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }
}
