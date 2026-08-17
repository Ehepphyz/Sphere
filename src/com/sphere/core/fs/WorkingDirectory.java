package com.sphere.core.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks and controls the active operational working directory context safely across threads.
 * Enhanced with cross-platform user home path resolution for multi-OS stability (Win, Linux, macOS, WSL).
 */
public class WorkingDirectory {

    private static final AtomicReference<Path> currentDir = 
            new AtomicReference<>(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());

    public static Path get() {
        return currentDir.get();
    }

    public static String getPath() {
        return currentDir.get().toString();
    }

    public static boolean changeTo(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return false;
        }

        String cleanedPath = pathStr.trim();
        Path targetPath;

        // --- Cross-Platform Home Directory Handling (~ Support) ---
        if (cleanedPath.equals("~")) {
            targetPath = Paths.get(System.getProperty("user.home"));
        } else if (cleanedPath.startsWith("~" + java.io.File.separator)) {
            targetPath = Paths.get(System.getProperty("user.home")).resolve(cleanedPath.substring(2));
        } else if (cleanedPath.startsWith("~/")) { // Explicit Unix/WSL/Forward-slash normalization fallback
            targetPath = Paths.get(System.getProperty("user.home")).resolve(cleanedPath.substring(2));
        } else {
            targetPath = Paths.get(cleanedPath);
        }

        Path current = currentDir.get();

        if (!targetPath.isAbsolute()) {
            targetPath = current.resolve(targetPath);
        }

        targetPath = targetPath.toAbsolutePath().normalize();

        if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
            currentDir.set(targetPath);
            return true;
        }
        return false;
    }
}