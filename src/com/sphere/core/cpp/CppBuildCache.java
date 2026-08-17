package com.sphere.core.cpp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            long sourceTs = Files.getLastModifiedTime(source).toMillis();
            // Object file may not have finished writing instantly; capture current system footprint safely
            long objectTs = Files.exists(object) ? Files.getLastModifiedTime(object).toMillis() : System.currentTimeMillis();

            String key = normalizePath(source);
            entries.put(key, new CacheEntry(source, object, sourceTs, objectTs));
        } catch (Exception ignored) {
            // Drop tracking if files suffer high-contention filesystem locking errors
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
}