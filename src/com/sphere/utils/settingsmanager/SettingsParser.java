package com.sphere.utils.settingsmanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Handles cross-platform, line-by-line parsing of configuration files
 * into structured category maps.
 */
public final class SettingsParser {

    private SettingsParser() {}

    /**
     * Parses an absolute file path descriptor into a structured SettingsFile instance.
     */
    public static SettingsFile parse(Path file) throws IOException {
        SettingsFile settings = new SettingsFile();
        List<String> lines = Files.readAllLines(file);

        String currentCategory = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Category block initialization
            if (line.startsWith("[") && line.endsWith("]")) {
                currentCategory = line.substring(1, line.length() - 1).trim();
                settings.getCategories().putIfAbsent(currentCategory, new LinkedHashMap<>());
                continue;
            }

            // Key-value pair extraction
            if (currentCategory != null && line.contains("=")) {
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                
                settings.getCategories()
                        .get(currentCategory)
                        .put(key, value);
            }
        }

        return settings;
    }
}
