package com.sphere.core.rootlab.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a test has at hand while it runs: the reference files shipped with
 * Sphere (the .root files of ROOT's test/ directory and the compression
 * fixtures), a scratch folder of its own, a log, and whether the run was
 * cancelled.
 */
public final class LabContext {

    /** Where the reference data sits inside the jar. */
    public static final String REFDATA = "/com/sphere/core/rootlab/refdata/";

    private final Consumer<String> log;
    private final java.util.function.BooleanSupplier cancelled;
    private final boolean network;
    private final List<String> details = new ArrayList<>();
    private Path scratch;

    public LabContext(Consumer<String> log, java.util.function.BooleanSupplier cancelled, boolean network) {
        this.log = log == null ? s -> { } : log;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.network = network;
    }

    public void log(String line) {
        log.accept(line);
    }

    /** A line kept with the result, shown when the test is selected. */
    public void detail(String line) {
        details.add(line);
    }

    List<String> takeDetails() {
        List<String> out = new ArrayList<>(details);
        details.clear();
        return out;
    }

    public boolean networkAllowed() {
        return network;
    }

    public boolean cancelled() {
        return cancelled.getAsBoolean();
    }

    /** Fails the test with that message unless the condition holds. */
    public void check(boolean condition, String message) throws LabFailure {
        if (!condition) {
            throw new LabFailure(message, details);
        }
    }

    /**
     * A reference file, as a path on disk: copied once out of the jar into a
     * cache folder (a file inside a jar has no path to open it by).
     */
    public static Path resource(String name) throws IOException {
        final Path cache = Path.of(System.getProperty("java.io.tmpdir"), "sphere-rootlab", "refdata");
        Files.createDirectories(cache);
        final Path target = cache.resolve(name);
        try (InputStream in = LabContext.class.getResourceAsStream(REFDATA + name)) {
            if (in == null) {
                throw new IOException("reference file " + name + " is not in this build of Sphere"
                    + " (run.bat copies src/com/sphere/core/rootlab/refdata into the jar)");
            }
            final byte[] bytes = in.readAllBytes();
            if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                final Path tmp = Files.createTempFile(cache, name, ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    /** A folder of the test's own, emptied when the run ends. */
    public Path scratch() throws IOException {
        if (scratch == null) {
            scratch = Files.createTempDirectory("sphere-rootlab-");
        }
        return scratch;
    }

    void cleanup() {
        if (scratch != null) {
            try (var walk = Files.walk(scratch)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException leaveIt) {
                        // a file still open on Windows; the temp folder is cleaned eventually
                    }
                });
            } catch (IOException ignored) {
                // nothing to clean
            }
            scratch = null;
        }
    }
}
