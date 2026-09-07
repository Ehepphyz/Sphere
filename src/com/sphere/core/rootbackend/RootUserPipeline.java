package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The user's own ROOT pipeline: shared libraries dropped in includes/ and
 * macros kept in user_scripts/.
 *
 * Both folders existed and were protected from cleanup, but nothing ever read
 * them and no library was ever loaded. This class is that missing step.
 *
 * Two layers are searched, in this order: the global pair beside Sphere, then
 * the active project's own pair. The project comes second so it can override
 * a library or a macro of the same name.
 */
public final class RootUserPipeline {

    public static final String INCLUDES_DIR = "includes";
    public static final String SCRIPTS_DIR = "user_scripts";

    private static final String WORKSPACE = "WorkSpace";
    private static final String BACKEND = "rootbackend";
    private static final long LOAD_TIMEOUT_MS = 20_000L;

    private RootUserPipeline() { }

    /** What one pass of loadInto did, so a caller can report it. */
    public record Outcome(int loaded, int alreadyLoaded, int failed, List<String> problems) {
        public int total() { return loaded + alreadyLoaded + failed; }
    }

    /* ------------------------------------------------------------------ */
    /* Where the folders are                                               */
    /* ------------------------------------------------------------------ */

    /** The pair beside Sphere, shared by every project. */
    public static Path globalRoot() {
        return Paths.get(System.getProperty("user.dir"), BACKEND).toAbsolutePath().normalize();
    }

    /** The pair inside a project, or null when no project is active. */
    public static Path projectRoot(String activeProject) {
        if (activeProject == null || activeProject.isBlank()) {
            return null;
        }
        Path project = Paths.get(WORKSPACE, activeProject).toAbsolutePath().normalize();
        return Files.isDirectory(project) ? project : null;
    }

    /** Global first, then the project, which is what lets the project win. */
    public static List<Path> layers(String activeProject) {
        List<Path> roots = new ArrayList<>(2);
        roots.add(globalRoot());
        Path project = projectRoot(activeProject);
        if (project != null && !project.equals(globalRoot())) {
            roots.add(project);
        }
        return roots;
    }

    /** Creates includes/ and user_scripts/ under a root if they are missing. */
    public static void ensureLayout(Path root) {
        if (root == null) return;
        for (String name : new String[]{INCLUDES_DIR, SCRIPTS_DIR}) {
            Path dir = root.resolve(name);
            if (Files.isDirectory(dir)) continue;
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                AppLogger.warn("Could not create " + dir + ": " + e.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* What is in them                                                     */
    /* ------------------------------------------------------------------ */

    /** Shared libraries under a layer's includes/, whatever this platform calls them. */
    public static List<Path> libraries(Path root) {
        return filesIn(root, INCLUDES_DIR, name ->
            name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
            || name.contains(".so."));
    }

    /** C++ sources under a layer's includes/, the ones :root includes build compiles. */
    public static List<Path> sources(Path root) {
        return filesIn(root, INCLUDES_DIR, name ->
            name.endsWith(".cpp") || name.endsWith(".cxx") || name.endsWith(".cc")
            || name.endsWith(".c"));
    }

    /** Macros under a layer's user_scripts/. */
    public static List<Path> macros(Path root) {
        return filesIn(root, SCRIPTS_DIR, name ->
            name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cxx")
            || name.endsWith(".cc") || name.endsWith(".h"));
    }

    private static List<Path> filesIn(Path root, String folder,
                                      java.util.function.Predicate<String> accept) {
        List<Path> found = new ArrayList<>();
        if (root == null) return found;
        Path dir = root.resolve(folder);
        if (!Files.isDirectory(dir)) return found;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                   .filter(p -> accept.test(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                   .sorted()
                   .forEach(found::add);
        } catch (IOException e) {
            AppLogger.warn("Could not read " + dir + ": " + e.getMessage());
        }
        return found;
    }

    /**
     * Finds a macro by bare name, so ":root script run analyse.C" works without
     * a path. The project is searched first, then the global folder, then the
     * name is taken as a path relative to the working directory.
     */
    public static Path resolveMacro(String name, String activeProject) {
        if (name == null || name.isBlank()) return null;
        String wanted = name.trim().replace("\"", "");

        Path direct = Paths.get(wanted);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct.normalize();
        }

        List<Path> roots = layers(activeProject);
        // Reversed: the project layer wins over the global one
        for (int i = roots.size() - 1; i >= 0; i--) {
            Path candidate = roots.get(i).resolve(SCRIPTS_DIR).resolve(wanted);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        Path relative = Paths.get(System.getProperty("user.dir")).resolve(wanted);
        return Files.isRegularFile(relative) ? relative.normalize() : null;
    }

    /* ------------------------------------------------------------------ */
    /* Loading                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Adds every layer's includes/ to the interpreter's search path, then loads
     * each shared library it finds there.
     *
     * A library that refuses to load is named and the next one is tried, so one
     * stale build cannot keep the whole session from starting.
     */
    public static Outcome loadInto(RootBackend backend, String activeProject) {
        List<String> problems = new ArrayList<>();
        int loaded = 0, already = 0, failed = 0;

        if (backend == null || !backend.isAvailable()) {
            return new Outcome(0, 0, 0, problems);
        }

        for (Path root : layers(activeProject)) {
            ensureLayout(root);
            Path includes = root.resolve(INCLUDES_DIR);
            if (!Files.isDirectory(includes)) continue;

            // The header search path first, so a library's own headers resolve
            ask(backend, "gROOT->ProcessLine(\".I " + forCling(includes) + "\")");

            for (Path library : libraries(root)) {
                String answer = ask(backend,
                        "gSystem->Load(\"" + forCling(library) + "\")");
                int code = parseCode(answer);
                switch (code) {
                    case 0 -> loaded++;
                    case 1 -> already++;
                    default -> {
                        failed++;
                        problems.add(library.getFileName() + "  (" + describe(code, answer) + ")");
                        AppLogger.warn("Could not load " + library + ": " + describe(code, answer));
                    }
                }
            }
        }

        if (loaded + already > 0) {
            AppLogger.success("ROOT pipeline: " + loaded + " librar"
                + (loaded == 1 ? "y" : "ies") + " loaded"
                + (already > 0 ? ", " + already + " already there" : "")
                + (failed > 0 ? ", " + failed + " refused" : "") + ".");
        } else if (failed > 0) {
            AppLogger.warn("ROOT pipeline: none of the " + failed
                + " libraries in includes/ could be loaded.");
        }
        return new Outcome(loaded, already, failed, problems);
    }

    private static String ask(RootBackend backend, String expression) {
        try {
            return backend.executeClingAwait(expression, LOAD_TIMEOUT_MS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * gSystem->Load answers 0 loaded, 1 already there, negative on refusal.
     *
     * The engine sends what the expression printed and then its value, in that
     * order, so the code is on the LAST line. A failing Load prints
     * "Error in <TCling::Load>: ..." first, and reading from the left would pick
     * a digit out of that message instead of the return code.
     */
    private static int parseCode(String answer) {
        if (answer == null) return NO_ANSWER;
        String text = answer.strip();
        if (text.isEmpty()) return NO_ANSWER;
        if (text.startsWith("ERROR:")) return REFUSED;

        String[] lines = text.split("\\R");
        String last = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) { last = lines[i].strip(); break; }
        }
        if (last.equals("OK")) return NO_VALUE;

        try {
            return Integer.parseInt(last);
        } catch (NumberFormatException notBare) {
            // ROOT sometimes prefixes the type, as in "(int) -1"
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(-?\\d+)\\s*$").matcher(last);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (NumberFormatException bad) { return NO_ANSWER; }
            }
            return NO_ANSWER;
        }
    }

    private static final int NO_ANSWER = -101;
    private static final int NO_VALUE = -102;
    private static final int REFUSED = -103;

    /** The message the engine sent, when it refused outright. */
    private static String firstLine(String answer) {
        if (answer == null) return "";
        for (String line : answer.strip().split("\\R")) {
            if (!line.isBlank()) return line.strip();
        }
        return "";
    }

    private static String describe(int code, String answer) {
        return switch (code) {
            case -1 -> "the library or one of its dependencies is missing";
            case -2 -> "built against a different ROOT version";
            case -3 -> "already loaded from another path";
            case NO_ANSWER -> answer == null || answer.isBlank()
                    ? "the engine did not answer"
                    : "unexpected answer: " + firstLine(answer);
            case NO_VALUE -> "the engine answered without a return code";
            case REFUSED -> firstLine(answer).replaceFirst("^ERROR:\\s*", "");
            default -> "code " + code;
        };
    }

    /** Cling reads its own string literals, so separators must be forward slashes. */
    public static String forCling(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "/");
    }
}
