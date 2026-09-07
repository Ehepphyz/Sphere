package com.sphere.core.rootbackend;

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
import java.util.concurrent.TimeUnit;

/**
 * Builds the code the user puts in the pipeline folders.
 *
 * Two kinds of source, two ways to build:
 *
 *   includes/       real C++ compiled to a shared library by the C++ compiler
 *                   settings.conf resolves, and loaded with gSystem->Load
 *   user_scripts/   ROOT macros compiled by ACLiC inside the interpreter,
 *                   which is the idiomatic way and needs a running engine
 *
 * This class owns the first. The second goes through the engine, so it lives
 * with the commands. Nothing here writes to the terminal Sphere was launched
 * from: the compiler's own words come back to the caller and reach the console.
 */
public final class RootUserCompiler {

    private static final int COMPILE_TIMEOUT_SECONDS = 180;
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    /** The result of one compilation, including what the compiler said. */
    public record Build(Path source, Path output, boolean succeeded,
                        int exitCode, String message, String commandLine) {

        /** The compiler's own words, trimmed, or a short reason when it never ran. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            if (message == null || message.isBlank()) return out;
            for (String line : message.strip().split("\\R")) {
                if (!line.isBlank()) out.add(line.strip());
            }
            return out;
        }
    }

    private static final long ASK_TIMEOUT_MS = 15_000L;

    private final SettingsManager settings;
    private final RootBackend engine;

    public RootUserCompiler() {
        this(new SettingsManager(), null);
    }

    public RootUserCompiler(SettingsManager settings) {
        this(settings, null);
    }

    /**
     * @param engine the running ROOT engine, which is asked for its own build
     *               settings. Null falls back to root-config, then to ROOT_DIR.
     */
    public RootUserCompiler(SettingsManager settings, RootBackend engine) {
        this.settings = settings != null ? settings : new SettingsManager();
        this.engine = engine;
    }

    /** Asks the engine, or null when there is none or it did not answer. */
    private String askEngine(String expression) {
        if (engine == null || !engine.isAvailable()) return null;
        try {
            String answer = engine.executeClingAwait(expression, ASK_TIMEOUT_MS);
            if (answer == null) return null;
            String text = answer.strip();
            if (text.isEmpty() || text.equals("OK") || text.startsWith("ERROR:")) return null;
            return text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* The toolchain                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * The compiler Sphere would really run, by the same three-state rule as the
     * rest of the platform: a declared path that works wins, a blank key
     * disables the tool, and only an absent key triggers a search.
     *
     * @return the compiler, or null when both keys are deliberately blank or
     *         nothing was found
     */
    public String compiler() {
        if (settings.isDeclaredEmpty("CPP_COMPILER_PATH")
                && settings.isDeclaredEmpty("GPP_DIR")) {
            return null;
        }
        String resolved = settings.resolveTool("CPP_COMPILER_PATH", null);
        if (resolved == null) {
            resolved = settings.resolveTool("GPP_DIR", "g++");
        }
        return resolved;
    }

    /**
     * The flags of the ROOT that is actually running.
     *
     * Asking the engine beats spawning root-config: it is the very installation
     * the bridge uses, so there is no risk of picking another one off the PATH,
     * and it answers on Windows, where root-config is a shell script that often
     * is not there at all. root-config and then ROOT_DIR remain as fallbacks for
     * a build started while the engine is down.
     */
    public List<String> rootFlags() {
        List<String> fromEngine = flagsFromEngine();
        if (!fromEngine.isEmpty()) return fromEngine;
        return flagsFromRootConfig();
    }

    /** Where the flags come from, for the console to say so. */
    public String flagSource() {
        if (!flagsFromEngine().isEmpty()) return "the running ROOT engine";
        String rootDir = settings.resolveTool("ROOT_DIR", null);
        return rootDir == null ? "nothing: no engine, no root-config, no ROOT_DIR"
                               : "root-config or ROOT_DIR";
    }

    private List<String> flagsFromEngine() {
        List<String> flags = new ArrayList<>();
        // TSystem hands back exactly what ACLiC would use for this installation
        String includes = askEngine("gSystem->GetIncludePath()");
        String libraries = askEngine("gSystem->GetLinkedLibs()");
        if (includes == null && libraries == null) return flags;

        for (String source : new String[]{includes, libraries}) {
            if (source == null) continue;
            for (String token : source.trim().split("\\s+")) {
                if (!token.isBlank()) flags.add(token);
            }
        }
        return flags;
    }

    /**
     * The extension ROOT itself uses for a shared library on this platform,
     * which is more reliable than reading os.name.
     */
    public String sharedLibraryExtension() {
        String fromEngine = askEngine("gSystem->GetSoExt()");
        if (fromEngine != null && !fromEngine.isBlank()) {
            String ext = fromEngine.strip();
            return ext.startsWith(".") ? ext : "." + ext;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
               ? ".dll" : ".so";
    }

    /**
     * The compiler ROOT was built with. A library built by a different one can
     * load and then fail on a symbol, so it is worth naming when they differ.
     */
    public String rootBuildCompiler() {
        return askEngine("gSystem->GetBuildCompiler()");
    }

    private List<String> flagsFromRootConfig() {
        List<String> flags = new ArrayList<>();
        String rootDir = settings.resolveTool("ROOT_DIR", null);

        List<String> candidates = new ArrayList<>();
        if (rootDir != null && !rootDir.isBlank()) {
            candidates.add(Path.of(rootDir, "bin", "root-config").toString());
        }
        candidates.add("root-config");

        for (String candidate : candidates) {
            flags.clear();
            boolean complete = true;
            for (String option : new String[]{"--cflags", "--libs"}) {
                String answer = probe(candidate, option);
                if (answer == null) { complete = false; break; }
                for (String token : answer.trim().split("\\s+")) {
                    if (!token.isBlank()) flags.add(token);
                }
            }
            if (complete && !flags.isEmpty()) return flags;
        }

        // No root-config: the headers and the core libraries, named by hand
        flags.clear();
        if (rootDir != null && !rootDir.isBlank()) {
            flags.add("-I" + rootDir + File.separator + "include");
            flags.add("-L" + rootDir + File.separator + "lib");
            for (String library : new String[]{"Core", "RIO", "Net", "Hist",
                                               "Graf", "Gpad", "Tree", "MathCore"}) {
                flags.add("-l" + library);
            }
        }
        return flags;
    }

    /* ------------------------------------------------------------------ */
    /* Building                                                            */
    /* ------------------------------------------------------------------ */

    /** Where the library of a source goes: beside it, with the usual prefix. */
    public static Path libraryFor(Path source) {
        return libraryFor(source,
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            ? ".dll" : ".so");
    }

    /** The same, with the extension the engine reported. */
    public static Path libraryFor(Path source, String extension) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        boolean windows = extension.equals(".dll");
        String prefix = windows || stem.startsWith("lib") ? "" : "lib";
        return source.getParent().resolve(prefix + stem + extension);
    }

    /** Where this compiler would put the library, asking the engine first. */
    public Path outputFor(Path source) {
        return libraryFor(source, sharedLibraryExtension());
    }

    /**
     * True when the library is missing or older than its source, which is what
     * lets a build of everything skip what has not changed.
     */
    public static boolean needsBuilding(Path source) {
        Path library = libraryFor(source);
        if (!Files.isRegularFile(library)) return true;
        try {
            return Files.getLastModifiedTime(source)
                        .compareTo(Files.getLastModifiedTime(library)) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    /** Compiles one source into a shared library beside it. */
    public Build build(Path source, List<String> extraFlags) {
        return build(source, outputFor(source), extraFlags);
    }

    /** Compiles one source into a named output. */
    public Build build(Path source, Path output, List<String> extraFlags) {
        if (source == null || !Files.isRegularFile(source)) {
            return new Build(source, output, false, -1, "No such source file.", "");
        }

        String compiler = compiler();
        if (compiler == null) {
            return new Build(source, output, false, -1,
                "No C++ compiler. Set CPP_COMPILER_PATH or GPP_DIR in settings.conf.", "");
        }

        boolean windows = System.getProperty("os.name", "")
                            .toLowerCase(Locale.ROOT).contains("win");

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add("-shared");
        if (!windows) command.add("-fPIC");
        command.add("-std=c++20");
        command.addAll(rootFlags());
        if (extraFlags != null) command.addAll(extraFlags);
        command.add("-o");
        command.add(output.toAbsolutePath().toString());
        command.add(source.toAbsolutePath().toString());

        String line = String.join(" ", command);
        StringBuilder said = new StringBuilder();

        try {
            if (output.getParent() != null) Files.createDirectories(output.getParent());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(source.getParent().toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String read;
                while ((read = reader.readLine()) != null) {
                    said.append(read).append('\n');
                }
            }

            // A compiler that never returns must not hold the caller for ever
            if (!process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Build(source, output, false, -1,
                    said + "The compiler did not finish within "
                    + COMPILE_TIMEOUT_SECONDS + " seconds and was stopped.", line);
            }

            int exit = process.exitValue();
            return new Build(source, output, exit == 0, exit, said.toString(), line);

        } catch (IOException e) {
            return new Build(source, output, false, -1,
                said + "Could not run " + compiler + ": " + e.getMessage(), line);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Build(source, output, false, -1,
                said + "The compilation was interrupted.", line);
        }
    }

    /**
     * Builds every source of a layer's includes/.
     *
     * @param onlyChanged skip a source whose library is already newer than it
     */
    public List<Build> buildAll(Path layerRoot, boolean onlyChanged) {
        List<Build> results = new ArrayList<>();
        for (Path source : RootUserPipeline.sources(layerRoot)) {
            if (onlyChanged && !needsBuilding(source)) continue;
            results.add(build(source, null));
        }
        return results;
    }

    /* ------------------------------------------------------------------ */

    private String probe(String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.start();
            StringBuilder answer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String read;
                while ((read = reader.readLine()) != null) {
                    answer.append(read).append(' ');
                }
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? answer.toString() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
