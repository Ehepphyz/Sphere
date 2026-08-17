package com.sphere.utils.settingsmanager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;

/**
 * In-memory representation of an INI configuration structure with built-in
 * crash-safe atomic backup logic.
 */
public final class SettingsFile {

    private final Map<String, LinkedHashMap<String, String>> categories = new LinkedHashMap<>();

    public Map<String, LinkedHashMap<String, String>> getCategories() {
        return categories;
    }

    public SettingsFile cloneSnapshot() {
        SettingsFile copy = new SettingsFile();
        for (var entry : categories.entrySet()) {
            String cat = entry.getKey();
            LinkedHashMap<String, String> mapCopy = new LinkedHashMap<>(entry.getValue());
            copy.categories.put(cat, mapCopy);
        }
        return copy;
    }

    public void copyFrom(SettingsFile other) {
        categories.clear();
        for (var entry : other.categories.entrySet()) {
            String cat = entry.getKey();
            LinkedHashMap<String, String> mapCopy = new LinkedHashMap<>(entry.getValue());
            categories.put(cat, mapCopy);
        }
    }

    /**
     * Serializes configuration properties to disk via an atomic temporary swap transaction
     * to eliminate zero-byte truncations during thread or machine failures.
     */
    public void saveToFile(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        String ln = System.lineSeparator();

        for (var entry : categories.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]").append(ln);
            for (var kv : entry.getValue().entrySet()) {
                sb.append(kv.getKey()).append("=").append(kv.getValue()).append(ln);
            }
            sb.append(ln);
        }

        // Atomic Transaction: Write to .tmp file, back up old configuration, swap production live
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Path backupFile = path.resolveSibling(path.getFileName() + ".bak");

        Files.writeString(tempFile, sb.toString());

        if (Files.exists(path)) {
            Files.copy(path, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        
        // This is safe across Unix/Windows standard filesystems
        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
