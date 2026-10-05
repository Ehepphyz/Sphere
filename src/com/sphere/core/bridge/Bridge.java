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
 * </pre>
 */
public final class Bridge {

    /** The reader libraries, carried inside Sphere and written out on first use. */
    public static final List<String> LIBRARIES = List.of(
        "sphere_spx.hpp", "SphereSPX.jl", "sphere_spx.f90", "sphere_lhaglue.f90", "sphere_spx.py",
        "sphere_mpl.py", "sphere_view.hpp", "sphere_view3d.hpp", "sphere_minuit2.hpp");

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
    static Path buildFolder() {
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
            final String lib = libraries().toString();
            env.put("SPHERE_BRIDGE", folder().toString());
            env.put("SPHERE_BRIDGE_LIB", lib);
            prepend(env, "CPLUS_INCLUDE_PATH", lib);
            prepend(env, "ROOT_INCLUDE_PATH", lib);
            prepend(env, "PYTHONPATH", lib);
            // Where a program's figures go to reach the Plots tab, and a
            // matplotlib whose plt.show() puts them there instead of opening
            // a window. A backend the user chose in the environment is kept.
            env.put("SPHERE_PLOTS", com.sphere.components.rootview.RootPlotsPanel.plotsFolder().toString());
            env.putIfAbsent("MPLBACKEND", "module://sphere_mpl");
            BridgeOutbox.start();
        } catch (IOException | RuntimeException unavailable) {
            // An engine without the bridge still runs; it only cannot see the others.
            AppLogger.warn("Bridge not available to this process: " + unavailable.getMessage());
        }
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
            new String(carried("sphere_lhaglue.f90"), StandardCharsets.UTF_8));
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
        return dir;
    }

    /** The flags that give a Fortran build the bridge: the module folder and the two objects. */
    public static List<String> fortranFlags(String compiler) throws IOException {
        final Path dir = fortranObjects(compiler);
        final List<String> flags = new ArrayList<>();
        flags.add("-I" + dir);
        flags.add(dir.resolve("sphere_spx.o").toString());
        flags.add(dir.resolve("sphere_lhaglue.o").toString());
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
