package com.sphere.components.workspace;

import com.sphere.utils.AppLogger;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * High-performance serialization utility coordinator managing JSON persistence routines, 
 * data rehydration cycles, and stream mutation actions for local workspace manifests.
 */
public final class ProjectManifestIO {

    private static final String DEFAULT_FALLBACK_EXPERIMENT = "Generic";

    private ProjectManifestIO() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static ProjectManifest load(Path targetFilePath) {
        Objects.requireNonNull(targetFilePath, "Target file path cannot be null.");
        ProjectManifest manifest = new ProjectManifest();

        if (!Files.exists(targetFilePath)) {
            return manifest;
        }

        try {
            String inputJson = Files.readString(targetFilePath);
            Map<String, Object> dataMap = MinimalJson.parse(inputJson);

            if (dataMap == null) {
                // FIX: Fallback to AppLogger.info
                AppLogger.info("Encountered empty or unparseable JSON document block at: " + targetFilePath);
                return manifest;
            }

            manifest.projectName = extractString(dataMap, "projectName");
            manifest.projectRoot = extractString(dataMap, "projectRoot");
            manifest.experiment = extractStringOrDefault(dataMap, "experiment", DEFAULT_FALLBACK_EXPERIMENT);
            manifest.presetVersion = extractString(dataMap, "presetVersion");
            manifest.description = extractString(dataMap, "description");
            manifest.projectType = extractString(dataMap, "projectType");
            manifest.notes = extractString(dataMap, "notes");
            manifest.createdAt = extractString(dataMap, "createdAt");
            manifest.modifiedAt = extractString(dataMap, "modifiedAt");

            manifest.customTypeName = extractString(dataMap, "customTypeName");
            manifest.customTypeDescription = extractString(dataMap, "customTypeDescription");
            manifest.customIcon = extractString(dataMap, "customIcon");
            manifest.customWorkflowTemplate = extractString(dataMap, "customWorkflowTemplate");
            manifest.customDefaultStructure = extractString(dataMap, "customDefaultStructure");

            manifest.tags = extractStringList(dataMap, "tags");
            manifest.customTags = extractStringList(dataMap, "customTags");

        } catch (Exception ex) {
            AppLogger.error("Failed to rehydrate project settings manifest file from: " + targetFilePath + " - Exception: " + ex.getMessage());
        }

        return manifest;
    }

    public static void save(ProjectManifest manifest, Path targetFilePath) {
        Objects.requireNonNull(manifest, "Source manifest parameters block cannot be null.");
        Objects.requireNonNull(targetFilePath, "Destination file path configuration cannot be null.");

        try {
            Map<String, Object> persistencePayload = new LinkedHashMap<>();

            if (Files.exists(targetFilePath)) {
                try {
                    Map<String, Object> existingData = MinimalJson.parse(Files.readString(targetFilePath));
                    if (existingData != null) {
                        persistencePayload.putAll(existingData);
                    }
                } catch (Exception ex) {
                    // FIX: Fallback to AppLogger.info
                    AppLogger.info("Overwriting malformed or corrupted manifest file: " + targetFilePath);
                }
            }

            manifest.modifiedAt = Instant.now().toString();
            if (manifest.createdAt == null || manifest.createdAt.isBlank()) {
                manifest.createdAt = manifest.modifiedAt;
            }

            persistencePayload.put("projectName", manifest.projectName);
            persistencePayload.put("projectRoot", manifest.projectRoot);
            persistencePayload.put("experiment", manifest.experiment);
            persistencePayload.put("presetVersion", manifest.presetVersion);
            persistencePayload.put("description", manifest.description);
            persistencePayload.put("projectType", manifest.projectType);
            persistencePayload.put("notes", manifest.notes);
            persistencePayload.put("createdAt", manifest.createdAt);
            persistencePayload.put("modifiedAt", manifest.modifiedAt);

            persistencePayload.put("customTypeName", manifest.customTypeName);
            persistencePayload.put("customTypeDescription", manifest.customTypeDescription);
            persistencePayload.put("customIcon", manifest.customIcon);
            persistencePayload.put("customWorkflowTemplate", manifest.customWorkflowTemplate);
            persistencePayload.put("customDefaultStructure", manifest.customDefaultStructure);

            persistencePayload.put("tags", manifest.tags != null ? manifest.tags : Collections.emptyList());
            persistencePayload.put("customTags", manifest.customTags != null ? manifest.customTags : Collections.emptyList());

            Path parentDirectory = targetFilePath.getParent();
            if (parentDirectory != null && !Files.exists(parentDirectory)) {
                Files.createDirectories(parentDirectory);
            }

            Files.writeString(targetFilePath, MinimalJson.toJson(persistencePayload), 
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        } catch (IOException ioException) {
            AppLogger.error("Critical I/O error writing workspace updates to file system target: " + targetFilePath);
            throw new RuntimeException("Could not persist manifest profile changes.", ioException);
        }
    }

    private static String extractString(Map<String, Object> dataMap, String dataKey) {
        return extractStringOrDefault(dataMap, dataKey, "");
    }

    private static String extractStringOrDefault(Map<String, Object> dataMap, String dataKey, String fallbackString) {
        Object extractedValue = dataMap.get(dataKey);
        return extractedValue == null ? fallbackString : String.valueOf(extractedValue).trim();
    }

    private static List<String> extractStringList(Map<String, Object> dataMap, String dataKey) {
        Object extractedValue = dataMap.get(dataKey);
        if (extractedValue instanceof List<?> collectionList) {
            return collectionList.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(contentString -> !contentString.isEmpty())
                    .toList();
        }
        return new ArrayList<>();
    }
}
