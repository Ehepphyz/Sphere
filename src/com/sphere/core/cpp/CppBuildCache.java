package com.sphere.core.cpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CppBuildCache {
    
    public static final class CacheEntry {
        private final Path sourceFile;
        private final Path objectFile;
        private final long sourceTimestamp;
        private final long objectTimestamp;

        public CacheEntry(Path sourceFile, Path objectFile, long sourceTimestamp, long objectTimestamp) {
            this.sourceFile = sourceFile;
            this.objectFile = objectFile;
            this.sourceTimestamp = sourceTimestamp;
            this.objectTimestamp = objectTimestamp;
        }

        public Path getSourceFile() { return sourceFile; }
        public Path getObjectFile() { return objectFile; }
        public long getSourceTimestamp() { return sourceTimestamp; }
        public long getObjectTimestamp() { return objectTimestamp; }
    }

    // Thread-safe map backing concurrent background builds seamlessly
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    /**
     * Normalizes an incoming file path into an absolute platform-specific look-up signature.
     */
    private String normalizePath(Path path) {
        if (path == null) return "";
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * Stores a source file compilation footprint inside the caching index.
     */
    public void put(Path source, Path object) {
        if (source == null || object == null) return;

        try {
            // An object that is not on disk yet has no timestamp to record, and
            // inventing one made isValid compare the object against a clock
            // reading, so an entry written that way was stale on the next build
            // and its object never reused. Nothing to record, no entry.
            if (!Files.exists(object)) {
                entries.remove(normalizePath(source));
                return;
            }
            long sourceTs = Files.getLastModifiedTime(source).toMillis();
            long objectTs = Files.getLastModifiedTime(object).toMillis();
            entries.put(normalizePath(source), new CacheEntry(source, object, sourceTs, objectTs));
        } catch (Exception unreadable) {
            entries.remove(normalizePath(source));
        }
    }

    /**
     * Retrieves the tracking entry assigned to a specific source file.
     */
    public CacheEntry get(Path source) {
        if (source == null) return null;
        return entries.get(normalizePath(source));
    }

    /**
     * Verifies if the compiled object cache artifact is entirely valid.
     * Ensures absolute build consistency by verifying that:
     * 1. The tracking data exists.
     * 2. The source file has not been modified since tracking.
     * 3. The generated physical binary object (.o/.obj) still exists on disk.
     * 4. The generated binary object has not been deleted or overwritten externally.
     */
    public boolean isValid(Path source) {
        if (source == null) return false;

        CacheEntry entry = entries.get(normalizePath(source));
        if (entry == null) return false;

        try {
            // Verification Boundary 1: Has the source file changed since compilation?
            if (!Files.exists(source) || Files.getLastModifiedTime(source).toMillis() != entry.getSourceTimestamp()) {
                return false;
            }

            // Verification Boundary 2: Does the output object file still exist where we put it?
            Path objPath = entry.getObjectFile();
            if (!Files.exists(objPath)) {
                return false;
            }

            // Verification Boundary 3: Verify the object file's filesystem stamp matches what we recorded
            return Files.getLastModifiedTime(objPath).toMillis() == entry.getObjectTimestamp();

        } catch (Exception e) {
            return false; // Invalidate cache safely on access errors
        }
    }

    /**
     * Invalidates a single entry from the compiler cache layer.
     */
    public void invalidate(Path source) {
        if (source == null) return;
        entries.remove(normalizePath(source));
    }

    /**
     * Flushes the entire cache configuration index.
     */
    public void clear() {
        entries.clear();
    }

    /** How many source files the cache is tracking. */
    public int size() {
        return entries.size();
    }

    /**
     * Writes the index beside the objects it describes.
     *
     * The map alone lives as long as the process, so every restart of Sphere
     * began by rebuilding a tree whose objects were all still on disk and all
     * still valid. One line per entry: the two stamps, then the two paths.
     */
    public void save(Path index) {
        if (index == null) return;
        List<String> lines = new ArrayList<>(entries.size());
        for (CacheEntry entry : entries.values()) {
            lines.add(entry.getSourceTimestamp() + "\t" + entry.getObjectTimestamp()
                      + "\t" + entry.getSourceFile() + "\t" + entry.getObjectFile());
        }
        try {
            if (index.getParent() != null) {
                Files.createDirectories(index.getParent());
            }
            Files.write(index, lines, StandardCharsets.UTF_8);
        } catch (IOException notWritten) {
            // The index is an optimization: losing it costs a full build, not
            // correctness, so a read-only build directory is not an error.
        }
    }

    /**
     * Reads an index back. Entries whose files no longer match are dropped by
     * isValid the moment they are asked about, so nothing is checked here.
     */
    public void load(Path index) {
        if (index == null || !Files.isReadable(index)) return;
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 4);
                if (parts.length != 4) continue;
                try {
                    Path source = Paths.get(parts[2]);
                    entries.put(normalizePath(source), new CacheEntry(
                        source, Paths.get(parts[3]),
                        Long.parseLong(parts[0]), Long.parseLong(parts[1])));
                } catch (NumberFormatException | java.nio.file.InvalidPathException skip) {
                    // A line written by another version, or a path this system
                    // cannot name. The file it describes is simply rebuilt.
                }
            }
        } catch (IOException notReadable) {
            entries.clear();
        }
    }
}