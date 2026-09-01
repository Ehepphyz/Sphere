package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootBackend;
import com.sphere.core.rootbackend.RootObjects.RootFile;
import com.sphere.core.rootbackend.RootObjects.RootHistogram;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intelligent high-level command facade for ROOT operations.
 * Manages open file lifecycles and resolves object handles dynamically to prevent
 * memory leaks and redundant file-system I/O.
 */
public final class RootCommands implements AutoCloseable {

    private final RootBackend backend;
    // Tracks active file references by both their file path and native handle ID
    private final Map<String, RootFile> activeFiles = new ConcurrentHashMap<>();

    public RootCommands(RootBackend backend) {
        this.backend = backend;
    }

    /**
     * Safely opens a ROOT file. If the file is already open, returns the existing 
     * cached instance to prevent redundant native file handles
     */
    public synchronized RootFile open(String path, String mode) throws IOException {
        if (activeFiles.containsKey(path)) {
            AppLogger.info("Retrieving cached ROOT file reference: " + path);
            return activeFiles.get(path);
        }

        String handleId = "handle_" + UUID.randomUUID().toString().substring(0, 8);
        RootFile file = new RootFile(backend.getProcessBridge(), handleId, path, mode);

        if (!file.isValid()) {
            throw new IOException("Failed to queue the open for " + path
                + ": " + file.getErrorMessage());
        }

        activeFiles.put(path, file);
        if (file.getHandleId() != null) {
            activeFiles.put(file.getHandleId(), file); // Map by handle ID as well for rapid lookups
        }
        return file;
    }

    /**
     * Safely retrieves a histogram from an open file.
     * Resolved by either the open file's absolute path or its native bridge handle ID.
     */
    public RootHistogram hist(String fileIdentifier, String histName) throws IOException {
        RootFile file = activeFiles.get(fileIdentifier);
        
        if (file == null) {
            throw new IOException("No active ROOT file found registered under identifier: " + fileIdentifier);
        }
        
        if (!file.isValid()) {
            throw new IOException("Target ROOT file is in an invalid state: " + fileIdentifier);
        }

        // Updated for Root-Bridge v2: RootFile#getHistogram requires (directoryPath, histName)
        return file.getHistogram("", histName);
    }

    /**
     * Alias method to retrieve a histogram from an open ROOT file.
     * Delegates directly to {@link #hist(String, String)} for API consistency across handlers.
     */
    public RootHistogram getHistogram(String fileIdentifier, String histName) throws IOException {
        return hist(fileIdentifier, histName);
    }

    /**
     * Dispatches an interactive C++ snippet through the non-blocking SHM pipeline.
     */
    public boolean exec(String clingCode) {
        return backend.executeCling(clingCode);
    }

    /**
     * Dispatches an interactive C++ snippet cleanly (returns queue status).
     */
    public boolean execSimple(String clingCode) {
        return backend.executeCling(clingCode);
    }

    /**
     * Alias method to dispatch a C++ snippet via {@link #exec(String)}.
     */
    public boolean executeCling(String clingCode) {
        return exec(clingCode);
    }

    /**
     * Alias method to dispatch a C++ snippet via {@link #execSimple(String)}.
     */
    public boolean executeClingSimple(String clingCode) {
        return execSimple(clingCode);
    }

    /**
     * Closes a specific open file and removes its references from the tracking registry.
     *
     * @param fileIdentifier File path or native handle ID to close.
     */
    public synchronized void closeFile(String fileIdentifier) {
        RootFile file = activeFiles.remove(fileIdentifier);
        if (file != null) {
            // Remove the secondary mapping (either the duplicate handle or path entry)
            activeFiles.values().removeIf(f -> f == file);
            file.close();
            AppLogger.info("Closed and unregistered ROOT file reference: " + fileIdentifier);
        }
    }

    /**
     * Closes all active files tracked by this command facade.
     */
    @Override
    public synchronized void close() {
        AppLogger.info("Shutting down ROOT command registry. Sweeping active file handles...");
        // Close unique file instances
        activeFiles.values().stream()
                .distinct()
                .forEach(file -> {
                    try {
                        file.close();
                    } catch (Exception e) {
                        AppLogger.error("Error closing file reference: " + file.getHandleId() + " - " + e.getMessage());
                    }
                });
        activeFiles.clear();
    }
}