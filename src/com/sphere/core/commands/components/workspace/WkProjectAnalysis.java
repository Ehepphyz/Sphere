package com.sphere.components.workspace;

import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automates system scanning procedures across target workspace structures, 
 * capturing relative project footprints and synchronizing asset profiles cleanly.
 */
public class WkProjectAnalysis {

    private static final String SETTINGS_FILE_NAME = ".projectsettings";
    private static final String KEY_PROJECT_NAME = "projectName";
    private static final String KEY_PROJECT_ROOT = "projectRoot";
    private static final String KEY_DIRECTORIES = "directories";
    private static final String KEY_FILES = "files";

    /**
     * Scans the specified project directory and writes or updates a custom .projectsettings JSON file.
     * Preserves existing custom JSON fields and updates only the structural arrays.
     * * @param projectDirectory The root folder of the project to analyze.
     */
    @SuppressWarnings("unchecked")
    public void analyze(File projectDirectory) {
        if (projectDirectory == null || !projectDirectory.exists() || !projectDirectory.isDirectory()) {
            AppLogger.error("Workspace Analysis failed: Invalid project directory targeting.");
            return;
        }

        File settingsFile = new File(projectDirectory, SETTINGS_FILE_NAME);
        List<String> discoveredFiles = new ArrayList<>();
        List<String> discoveredDirectories = new ArrayList<>();

        // Safe, modern file tree analysis pass
        try {
            scanDirectory(projectDirectory.toPath(), discoveredFiles, discoveredDirectories);
        } catch (IOException e) {
            AppLogger.error("Failed to complete filesystem analysis walk: " + e.getMessage());
            return;
        }

        Map<String, Object> rootJsonObject = new LinkedHashMap<>();

        // Hydrate baseline properties if the file already exists
        if (settingsFile.exists()) {
            try {
                String jsonContent = Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8);
                Object parsedStructure = MinimalJson.parse(jsonContent);
                
                if (parsedStructure instanceof Map) {
                    rootJsonObject.putAll((Map<String, Object>) parsedStructure);
                }
            } catch (Exception e) {
                AppLogger.error("Settings parsing encountered corrupted fields. Regenerating baseline: " + e.getMessage());
            }
        }

        // Keep existing custom attributes intact while refreshing structural records
        rootJsonObject.put(KEY_PROJECT_NAME, projectDirectory.getName());
        rootJsonObject.put(KEY_PROJECT_ROOT, projectDirectory.getAbsolutePath());

        // Extract old entries for strict structural change validation tests
        Object existingDirs = rootJsonObject.get(KEY_DIRECTORIES);
        Object existingFiles = rootJsonObject.get(KEY_FILES);

        // Intercept updates if data remains structurally unchanged
        if (existingDirs instanceof List && existingFiles instanceof List) {
            if (existingDirs.equals(discoveredDirectories) && existingFiles.equals(discoveredFiles)) {
                return; // Structure matches; abort rewrite cycle to avoid file lock thrashing
            }
        }

        // Apply updated collections
        rootJsonObject.put(KEY_DIRECTORIES, discoveredDirectories);
        rootJsonObject.put(KEY_FILES, discoveredFiles);

        // Commit configuration changes to the storage layout safely
        try (FileWriter writer = new FileWriter(settingsFile, StandardCharsets.UTF_8)) {
            writer.write(MinimalJson.toJson(rootJsonObject));
        } catch (IOException e) {
            AppLogger.error("Failed to write project settings output stream: " + e.getMessage());
        }
    }

    /**
     * Modern, non-recursive directory analysis routine using hardware-accelerated NIO file tree walks.
     */
    private void scanDirectory(Path rootPath, List<String> filesList, List<String> dirsList) throws IOException {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip the root path reference itself
                if (dir.equals(rootPath)) {
                    return FileVisitResult.CONTINUE;
                }

                String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");
                dirsList.add(relativePath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                
                // Completely ignore tracking config metadata file signatures
                if (!fileName.equals(SETTINGS_FILE_NAME)) {
                    String relativePath = rootPath.relativize(file).toString().replace("\\", "/");
                    filesList.add(relativePath);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Gracefully skip restricted system resources or unreadable paths
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
