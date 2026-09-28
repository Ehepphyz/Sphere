package com.sphere.core.cpp;

import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Compiles only what changed.
 *
 * The three pieces this drives were written and never joined: CppBuildGraph
 * reads the #include tree, CppBuildCache remembers what was built, and nothing
 * asked either of them anything. A source is recompiled when its object is
 * missing, when the source itself moved, or when any header it reaches -- at
 * any depth -- is newer than that object. Everything else is reused, and the
 * link step runs only when an object actually changed.
 */
public final class CppIncrementalBuilder {

    /** Sources the builder picks up, the same set the rest of the backend knows. */
    private static final String[] SOURCE_EXTENSIONS =
        { ".cpp", ".cc", ".cxx", ".c++", ".C" };

    /** One source file and the object it produces. */
    public record Unit(Path source, Path object, boolean stale, String reason) { }

    /** What a build decided and did. */
    public record Outcome(int compiled, int reused, boolean linked, Path binary,
                          String failure, long millis, int cycles) {
        public boolean ok() {
            return failure == null;
        }
    }

    private final CppBackend backend;
    private final Path root;
    private final Path buildDirectory;
    private final Path objectDirectory;
    private final CppBuildCache cache = new CppBuildCache();
    private final List<Path> includeDirectories = new ArrayList<>();
    private final List<String> compileFlags = new ArrayList<>();
    private final List<String> linkFlags = new ArrayList<>();

    public CppIncrementalBuilder(CppBackend backend, Path root) {
        this.backend = backend;
        this.root = root.toAbsolutePath().normalize();
        // Under build/ and not build/ itself: build/ is where CMake puts its
        // own cache, and a project driven both ways would have one tool's
        // clean sweep away the other's work.
        this.buildDirectory = this.root.resolve("build").resolve("sphere");
        this.objectDirectory = this.buildDirectory.resolve("obj");
        cache.load(indexFile());
    }

    public Path getRoot() {
        return root;
    }

    public Path getBuildDirectory() {
        return buildDirectory;
    }

    public CppBuildCache getCache() {
        return cache;
    }

    public List<Path> getIncludeDirectories() {
        return includeDirectories;
    }

    public List<String> getCompileFlags() {
        return compileFlags;
    }

    public List<String> getLinkFlags() {
        return linkFlags;
    }

    private Path indexFile() {
        return buildDirectory.resolve("sphere-build.index");
    }

    /** Every source under the root, what a build tool generates left out. */
    public List<Path> sources() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !generated(p))
                .filter(CppIncrementalBuilder::isSource)
                .sorted()
                .forEach(found::add);
        } catch (IOException unreadable) {
            AppLogger.error("Cannot read " + root + ": " + unreadable.getMessage());
        }
        return found;
    }

    /**
     * Whether a path belongs to a build tool rather than to the project.
     *
     * CMake writes a small C++ file of its own under build/ to identify the
     * compiler, and a walk that did not know that offered to compile it.
     */
    private boolean generated(Path path) {
        Path relative;
        try {
            relative = root.relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException elsewhere) {
            return true;
        }
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".")
                    || name.equals("build")
                    || name.equals("CMakeFiles")
                    || name.equals("node_modules")
                    || name.startsWith("cmake-build-")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSource(Path path) {
        String name = path.getFileName().toString();
        for (String extension : SOURCE_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Where one source's object goes, named so two trees cannot collide. */
    public Path objectFor(Path source) {
        Path relative = root.relativize(source.toAbsolutePath().normalize());
        String flat = relative.toString().replace(File.separatorChar, '_').replace('/', '_');
        return objectDirectory.resolve(flat + objectExtension());
    }

    private boolean isMsvc() {
        CppBackend.CppToolchain toolchain = backend.getActiveToolchain();
        return toolchain != null && "msvc".equalsIgnoreCase(toolchain.getName());
    }

    private String objectExtension() {
        return isMsvc() ? ".obj" : ".o";
    }

    private String binaryExtension() {
        CppBackend.CppToolchain toolchain = backend.getActiveToolchain();
        boolean wsl = toolchain != null && toolchain.isWsl();
        return (!wsl && System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
             ? ".exe" : "";
    }

    /**
     * What the next build would do, without doing any of it.
     *
     * The graph is rebuilt from scratch each time rather than kept: reading the
     * include lines of a tree costs milliseconds, and a graph kept across edits
     * is a graph that can disagree with the disk.
     */
    public List<Unit> plan() {
        List<Path> sources = sources();
        List<Unit> units = new ArrayList<>(sources.size());
        if (sources.isEmpty()) {
            return units;
        }

        CppBuildGraph graph = new CppBuildGraph();
        List<File> searchPath = new ArrayList<>();
        searchPath.add(root.toFile());
        for (Path directory : includeDirectories) {
            searchPath.add(directory.toFile());
        }

        for (Path source : sources) {
            Path object = objectFor(source);
            String reason = stalenessOf(source, object, graph, searchPath);
            units.add(new Unit(source, object, reason != null, reason));
        }
        return units;
    }

    /** Why this source must be compiled again, or null when it must not. */
    private String stalenessOf(Path source, Path object, CppBuildGraph graph, List<File> searchPath) {
        if (!Files.exists(object)) {
            return "no object yet";
        }
        if (!cache.isValid(source)) {
            return "source changed";
        }
        final long objectStamp;
        try {
            objectStamp = Files.getLastModifiedTime(object).toMillis();
        } catch (IOException unreadable) {
            return "object unreadable";
        }

        CppBuildGraph.Node node;
        try {
            node = graph.getOrCreateNode(source.toFile().getCanonicalPath(), source.toFile());
        } catch (IOException unreadable) {
            return "source unreadable";
        }
        graph.discoverDependencies(node, searchPath);

        for (CppBuildGraph.Node header : node.getDependencies()) {
            CppBuildGraph.Node changed = header.firstChanged(objectStamp, new HashSet<>());
            if (changed != null) {
                return "header " + shorten(changed.getFile().toPath());
            }
        }
        return null;
    }

    private String shorten(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
    }

    /**
     * Builds, compiling only the stale units, then links when anything changed.
     *
     * @param binaryName what the executable is called, without an extension
     * @param rebuildAll ignores the cache and compiles everything
     */
    public Outcome build(String binaryName, boolean rebuildAll, CppBackend.CppOutputListener listener) {
        final long started = System.currentTimeMillis();
        CppBackend.CppToolchain toolchain = backend.getActiveToolchain();
        if (toolchain == null) {
            return new Outcome(0, 0, false, null, "No C++ toolchain is available.", 0, 0);
        }

        List<Unit> units = plan();
        if (units.isEmpty()) {
            return new Outcome(0, 0, false, null, "No source file under " + root, 0, 0);
        }

        try {
            Files.createDirectories(objectDirectory);
        } catch (IOException cannotWrite) {
            return new Outcome(0, 0, false, null,
                "Cannot create " + objectDirectory + ": " + cannotWrite.getMessage(), 0, 0);
        }

        int compiled = 0;
        int reused = 0;
        for (Unit unit : units) {
            if (!rebuildAll && !unit.stale()) {
                reused++;
                continue;
            }
            if (listener != null) {
                listener.onStdoutLine("compiling " + shorten(unit.source())
                                      + (unit.reason() == null ? "" : "   (" + unit.reason() + ")"));
            }
            if (backend.runToolCommand(compileCommand(unit), false, listener, true) != 0) {
                cache.invalidate(unit.source());
                cache.save(indexFile());
                return new Outcome(compiled, reused, false, null,
                    shorten(unit.source()) + " did not compile", elapsed(started), 0);
            }
            cache.put(unit.source(), unit.object());
            compiled++;
        }

        Path binary = buildDirectory.resolve(binaryName + binaryExtension());
        boolean mustLink = compiled > 0 || !Files.exists(binary);
        if (mustLink) {
            if (listener != null) {
                listener.onStdoutLine("linking " + binary.getFileName());
            }
            List<Path> objects = new ArrayList<>();
            for (Unit unit : units) {
                objects.add(unit.object());
            }
            objects.sort(Comparator.comparing(Path::toString));
            if (backend.runToolCommand(linkCommand(objects, binary), false, listener, true) != 0) {
                cache.save(indexFile());
                return new Outcome(compiled, reused, false, null,
                    "link failed", elapsed(started), 0);
            }
        }
        cache.save(indexFile());
        return new Outcome(compiled, reused, mustLink, binary, null, elapsed(started), 0);
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private List<String> compileCommand(Unit unit) {
        CppBackend.CppToolchain toolchain = backend.getActiveToolchain();
        List<String> command = new ArrayList<>();
        command.add(toolchain.getExecutable());
        if (isMsvc()) {
            command.add("/c");
            command.add(backend.toolchainPath(unit.source().toString()));
            command.add("/Fo" + backend.toolchainPath(unit.object().toString()));
            for (Path directory : includeDirectories) {
                command.add("/I" + backend.toolchainPath(directory.toString()));
            }
        } else {
            command.add("-c");
            command.add(backend.toolchainPath(unit.source().toString()));
            command.add("-o");
            command.add(backend.toolchainPath(unit.object().toString()));
            command.add("-I" + backend.toolchainPath(root.toString()));
            for (Path directory : includeDirectories) {
                command.add("-I" + backend.toolchainPath(directory.toString()));
            }
        }
        command.addAll(compileFlags);
        return command;
    }

    private List<String> linkCommand(List<Path> objects, Path binary) {
        CppBackend.CppToolchain toolchain = backend.getActiveToolchain();
        List<String> command = new ArrayList<>();
        command.add(toolchain.getExecutable());
        for (Path object : objects) {
            command.add(backend.toolchainPath(object.toString()));
        }
        if (isMsvc()) {
            command.add("/Fe:" + backend.toolchainPath(binary.toString()));
        } else {
            command.add("-o");
            command.add(backend.toolchainPath(binary.toString()));
        }
        command.addAll(linkFlags);
        return command;
    }

    /** Removes the objects, the index and the binaries. Returns what it removed. */
    public int clean() {
        int removed = 0;
        cache.clear();
        if (!Files.isDirectory(buildDirectory)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(buildDirectory)) {
            List<Path> targets = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : targets) {
                try {
                    if (Files.deleteIfExists(path)) {
                        removed++;
                    }
                } catch (IOException held) {
                    // A file another program holds open stops that one file, not
                    // the sweep: the rest of the tree still goes.
                }
            }
        } catch (IOException unreadable) {
            AppLogger.error("Cannot read " + buildDirectory + ": " + unreadable.getMessage());
        }
        return removed;
    }

    /** Reads the include directories a project declares, one per line. */
    public void readIncludeFile(Path file) {
        if (file == null || !Files.isReadable(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                Path directory = Paths.get(trimmed);
                includeDirectories.add(
                    directory.isAbsolute() ? directory : root.resolve(directory));
            }
        } catch (IOException | java.nio.file.InvalidPathException unreadable) {
            AppLogger.error("Cannot read " + file + ": " + unreadable.getMessage());
        }
    }
}
