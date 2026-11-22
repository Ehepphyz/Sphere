package com.sphere.core.bridge;

import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Where the engines meet: the folder SPX files are exchanged in, the reader
 * libraries every language needs, and the environment that tells a process
 * where both are.
 *
 * Every engine Sphere starts (the Julia session, a Fortran or C++ build and
 * run, a Python script or kernel, the ROOT engine) is handed the same few
 * variables, so a program in any of them can open what another wrote by its
 * name alone:
 *
 * <pre>
 *   SPHERE_BRIDGE       the exchange folder; results dropped in its outbox reach the Plots tab
 *   SPHERE_BRIDGE_LIB   the readers: sphere_spx.hpp, SphereSPX.jl, sphere_spx.f90, sphere_spx.py
 *   CPLUS_INCLUDE_PATH  and ROOT_INCLUDE_PATH, so #include "sphere_spx.hpp" just works
 *   PYTHONPATH          so import sphere_spx just works
 *   SPHERE_MLINK        the gateway to TensorFlow, PyTorch and pyhf (com.sphere.core.ml.MlLink says what else)
 * </pre>
 */
public final class Bridge {

    /** The reader libraries, carried inside Sphere and written out on first use. */
    public static final List<String> LIBRARIES = List.of(
        "sphere_spx.hpp", "SphereSPX.jl", "sphere_spx.f90", "sphere_lhaglue.f90", "sphere_spx.py",
        "sphere_mpl.py", "sphere_view.hpp", "sphere_view3d.hpp", "sphere_minuit2.hpp",
        // Sphere's Minuit2 autopilot on ROOT's own Minuit2, for ROOT macros, C++ programs and PyROOT
        "sphere_autopilot.hpp",
        // the Sphere x87 ABI: long double on the x87 unit, one C interface for every language (x86-64)
        "sphere_x87.S", "sphere_x87.h",
        // machine learning: the engine and Python library (PyTorch, Keras, pyhf), and its networks run from C++
        "sphere_ml.py", "sphere_nn.hpp",
        // any PyTorch or Keras model as a portable graph: its export and numpy reference, its C++ runtime
        "sphere_graph.py", "sphere_graph.hpp", "sphere_graph_tool.cpp",
        // TensorFlow's models (frozen graphs, SavedModels) read without TensorFlow, into the same graphs
        "sphere_tf.py",
        // an analysis made differentiable (pyhf's model, its expected CLs), and the tensor arena shared with Java
        "sphere_dana.py", "sphere_mlshm.py",
        // simulation-based inference held to an exact likelihood, OmniFold against IBU, AutoML under constraints
        "sphere_sbi.py", "sphere_unfold.py", "sphere_automl.py",
        // one graph in PyTorch, TensorFlow and JAX (and their exports); normalizing flows written once for all
        "sphere_xf.py", "sphere_flows.py", "sphere_fastsim.py",
        // one differentiable computation across PyTorch, TensorFlow and JAX
        "sphere_xgrad.py",
        // TensorFlow, PyTorch and pyhf for every program: the gateway and a client in each language
        "sphere_mlink.py", "sphere_mlink.hpp", "sphere_mlink.f90", "SphereMLink.jl",
        // the Sphere Neural Network: the cortex (every module found, chosen together, checked, learned from), its synapses
        // in C++ and Julia, and the import finder Sphere's sitecustomize gives the Pythons Sphere starts (ROOT importable
        // where it is, served from WSL where it is not) -- with no file written in any Python of the machine
        "sphere_snn.py", "sphere_snn.hpp", "SphereSNN.jl", "sphere_snn_hook.py", "sitecustomize.py");

    private static volatile boolean materialized;

    private Bridge() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The exchange folder. */
    public static Path folder() {
        return Path.of("bridge").toAbsolutePath().normalize();
    }

    /** Where other engines drop results for Sphere to show. */
    public static Path outbox() {
        return folder().resolve("outbox");
    }

    /** Where compiled pieces of the bridge are kept between runs. */
    public static Path buildFolder() {
        return Path.of("config", "bridge", "build").toAbsolutePath().normalize();
    }

    /** The folder the reader libraries are written to. */
    public static Path libraryFolder() {
        return Path.of("config", "bridge", "lib").toAbsolutePath().normalize();
    }

    /**
     * Writes the reader libraries out, refreshing any that differ from the ones
     * this Sphere carries, and answers their folder. A copy the user edited is
     * replaced: the libraries are part of the format, and a reader that drifted
     * from the writer is the one thing the bridge must not have.
     */
    public static synchronized Path libraries() throws IOException {
        final Path dir = libraryFolder();
        if (materialized && Files.isDirectory(dir)) return dir;
        Files.createDirectories(dir);
        for (String name : LIBRARIES) {
            final byte[] carried = carried(name);
            final Path target = dir.resolve(name);
            if (!Files.isRegularFile(target) || !java.util.Arrays.equals(Files.readAllBytes(target), carried)) {
                Files.write(target, carried);
            }
        }
        Files.createDirectories(outbox());
        materialized = true;
        return dir;
    }

    /** A library as this Sphere carries it: from the jar, or from the sources when run from them. */
    static byte[] carried(String name) throws IOException {
        try (InputStream in = Bridge.class.getResourceAsStream("lib/" + name)) {
            if (in != null) return in.readAllBytes();
        }
        final Path source = Path.of("src", "com", "sphere", "core", "bridge", "lib", name);
        if (Files.isRegularFile(source)) return Files.readAllBytes(source);
        throw new IOException("The bridge library " + name + " is missing from this build of Sphere.");
    }

    /**
     * Gives a process the bridge. Called for every engine Sphere starts; it
     * also wakes the outbox, so what the process publishes is shown.
     */
    public static void environment(Map<String, String> env) {
        try {
            libraries(env);
            // Where a program's figures go to reach the Plots tab, and a
            // matplotlib whose plt.show() puts them there instead of opening
            // a window. A backend the user chose in the environment is kept.
            env.put("SPHERE_PLOTS", com.sphere.components.rootview.RootPlotsPanel.plotsFolder().toString());
            env.putIfAbsent("MPLBACKEND", "module://sphere_mpl");
            com.sphere.core.ml.MlLink.environment(env);
            com.sphere.core.snn.Snn.environment(env);
            BridgeOutbox.start();
        } catch (IOException | RuntimeException unavailable) {
            // An engine without the bridge still runs; it only cannot see the others.
            AppLogger.warn("Bridge not available to this process: " + unavailable.getMessage());
        }
    }

    /**
     * The bridge's libraries and the gateway to TensorFlow, PyTorch and pyhf, for a shell the user types in: what is
     * compiled or run there finds them, and its figures stay the user's (no MPLBACKEND).
     */
    public static void reach(Map<String, String> env) {
        try {
            libraries(env);
            com.sphere.core.ml.MlLink.environment(env);
            com.sphere.core.snn.Snn.environment(env);
        } catch (IOException | RuntimeException unavailable) {
            AppLogger.warn("Bridge not available to this shell: " + unavailable.getMessage());
        }
    }

    private static void libraries(Map<String, String> env) throws IOException {
        final String lib = libraries().toString();
        env.put("SPHERE_BRIDGE", folder().toString());
        env.put("SPHERE_BRIDGE_LIB", lib);
        prepend(env, "CPLUS_INCLUDE_PATH", lib);
        prepend(env, "ROOT_INCLUDE_PATH", lib);
        prepend(env, "PYTHONPATH", lib);
    }

    private static void prepend(Map<String, String> env, String key, String dir) {
        final String was = env.get(key);
        if (was == null || was.isBlank()) {
            env.put(key, dir);
        } else if (!List.of(was.split(File.pathSeparator)).contains(dir)) {
            env.put(key, dir + File.pathSeparator + was);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Tools                                                               */
    /* ------------------------------------------------------------------ */

    /** A tool from settings.conf, trying each key in turn, or null. */
    public static String tool(SettingsManager settings, String fallbackName, String... keys) {
        if (settings == null) settings = new SettingsManager();
        for (String key : keys) {
            if (settings.isDeclaredEmpty(key)) continue;
            final String found = settings.resolveTool(key, fallbackName);
            if (found != null) return found;
        }
        return null;
    }

    public static String fortranCompiler(SettingsManager s) {
        return tool(s, "gfortran", "ENV_FC", "FORTRAN_DIR");
    }

    public static String cppCompiler(SettingsManager s) {
        return tool(s, "g++", "GPP_DIR", "CLANGPP_DIR");
    }

    public static String julia(SettingsManager s) {
        return tool(s, "julia", "JULIA_DIR");
    }

    public static String python(SettingsManager s) {
        return tool(s, "python", "PYTHON_EXEC");
    }

    /** What a finished process said, and how it ended. */
    public record Outcome(int status, String output, long millis) {
        public boolean ok() {
            return status == 0;
        }
    }

    /**
     * Runs a program to the end with the bridge in its environment. A compiler
     * from MSYS2 needs its own folder on the PATH to find its DLLs, so the
     * folder of the program's tool is put there first.
     */
    public static Outcome run(List<String> command, Path directory, long timeoutSeconds, String toolDir)
            throws IOException {
        final long started = System.nanoTime();
        final ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        environment(builder.environment());
        if (toolDir != null) {
            final String key = builder.environment().containsKey("Path") ? "Path" : "PATH";
            final String was = builder.environment().getOrDefault(key, "");
            builder.environment().put(key, toolDir + File.pathSeparator + was);
        }
        final Process process = builder.start();
        final StringBuilder said = new StringBuilder();
        final Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (said) {
                        said.append(line).append('\n');
                    }
                }
            } catch (IOException closed) {
                // The process ended.
            }
        }, "sphere-bridge-run");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Outcome(-1, said + "(stopped after " + timeoutSeconds + " s)", elapsed(started));
            }
            reader.join(2000);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Outcome(-1, "interrupted", elapsed(started));
        }
        synchronized (said) {
            return new Outcome(process.exitValue(), said.toString().stripTrailing(), elapsed(started));
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    static String parentOf(String tool) {
        if (tool == null) return null;
        final File f = new File(tool);
        return f.getParentFile() == null ? null : f.getParentFile().getAbsolutePath();
    }

    /* ------------------------------------------------------------------ */
    /* The Fortran side, compiled once                                     */
    /* ------------------------------------------------------------------ */

    /**
     * The compiled reader and LHAPDF interface, rebuilt when the sources or the
     * compiler change. A .mod file belongs to the compiler that wrote it, so
     * each compiler gets a folder of its own.
     *
     * @return the folder holding sphere_spx.o, sphere_lhaglue.o and the .mod files
     */
    public static synchronized Path fortranObjects(String compiler) throws IOException {
        final Path lib = libraries();
        final String key = digest(compiler, new String(carried("sphere_spx.f90"), StandardCharsets.UTF_8),
            new String(carried("sphere_lhaglue.f90"), StandardCharsets.UTF_8),
            new String(carried("sphere_mlink.f90"), StandardCharsets.UTF_8));
        final Path dir = buildFolder().resolve("fortran-" + key.substring(0, 12));
        if (Files.isRegularFile(dir.resolve("sphere_spx.o")) && Files.isRegularFile(dir.resolve("sphere_lhaglue.o"))) {
            return dir;
        }
        Files.createDirectories(dir);
        final Outcome built = run(List.of(compiler, "-O2", "-c", lib.resolve("sphere_spx.f90").toString(),
            lib.resolve("sphere_lhaglue.f90").toString(), "-J", dir.toString()), dir, 300, parentOf(compiler));
        if (!built.ok()) {
            throw new IOException("The Fortran bridge did not compile:\n" + built.output());
        }
        // On its own, and allowed to fail: it uses gfortran's GETPID, and another compiler still has the rest of the bridge.
        final Outcome link = run(List.of(compiler, "-O2", "-c", lib.resolve("sphere_mlink.f90").toString(), "-J", dir.toString()),
            dir, 300, parentOf(compiler));
        if (!link.ok()) {
            AppLogger.warn("sphere_mlink.f90 did not compile with " + compiler + " (it is written for gfortran):\n" + link.output());
        }
        return dir;
    }

    /** The flags that give a Fortran build the bridge: the module folder and the two objects. */
    public static List<String> fortranFlags(String compiler) throws IOException {
        final Path dir = fortranObjects(compiler);
        final List<String> flags = new ArrayList<>();
        flags.add("-I" + dir);
        flags.add(dir.resolve("sphere_spx.o").toString());
        flags.add(dir.resolve("sphere_lhaglue.o").toString());
        if (Files.isRegularFile(dir.resolve("sphere_mlink.o"))) {
            flags.add(dir.resolve("sphere_mlink.o").toString());
        }
        return flags;
    }

    static String digest(String... parts) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                sha.update(String.valueOf(p).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
