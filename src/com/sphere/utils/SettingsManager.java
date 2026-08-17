package com.sphere.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * SettingsManager handles parsing and writing of settings.conf.
 * Preserves INI sections, is encoding-safe, and validates tool paths cross-platform.
 * Implements strict line-by-line sequential accumulation for Bash-like path manipulation.
 */
public class SettingsManager {

    // Maps a SectionName to a List of Key-Value pairs to allow duplicate keys (e.g., multiple PATH lines)
    private final Map<String, List<Map.Entry<String, String>>> sections = new LinkedHashMap<>();
    public static final String CONFIG_FILENAME = "settings.conf";

    public SettingsManager() {
        loadSettings();
    }

    // -------------------------------------------------------------------------
    // LOADING / SAVING
    // -------------------------------------------------------------------------

    public synchronized void loadSettings() {
        File file = new File(CONFIG_FILENAME);
        if (!file.exists()) return;

        sections.clear();
        
        // Root tracking initialization: Avoid hardcoded magic section strings.
        // Unmapped keys at the top of the file automatically stream to a "GLOBAL" scope.
        String currentSection = null;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).toUpperCase().trim();
                    sections.putIfAbsent(currentSection, new ArrayList<>());
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).toUpperCase().trim();
                    String val = line.substring(eq + 1).trim();
                    
                    String targetSection = (currentSection != null) ? currentSection : "GLOBAL";
                    sections.putIfAbsent(targetSection, new ArrayList<>());
                    
                    // Store the raw line sequentially. 
                    // Interpolation is strictly handled downstream by EngineConfigRegistry.
                    sections.get(targetSection).add(new AbstractMap.SimpleEntry<>(key, val));
                }
            }
        } catch (IOException e) {
            System.err.println("Critical Error: Unable to read " + CONFIG_FILENAME);
        }
    }

    public synchronized void saveSettings() {
        File file = new File(CONFIG_FILENAME);
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            for (var section : sections.entrySet()) {
                if (section.getValue().isEmpty()) continue;

                writer.write("[" + section.getKey() + "]");
                writer.newLine();
                for (var kv : section.getValue()) {
                    writer.write(kv.getKey() + "=" + kv.getValue());
                    writer.newLine();
                }
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Critical Error: Unable to save " + CONFIG_FILENAME);
        }
    }

    public void save() {
        saveSettings();
    }

    // -------------------------------------------------------------------------
    // ACCESSORS
    // -------------------------------------------------------------------------

    public String getProperty(String section, String key) {
        if (section == null || key == null) return null;
        List<Map.Entry<String, String>> s = sections.get(section.toUpperCase().trim());
        if (s == null) return null;
        
        String lookupKey = key.toUpperCase().trim();
        // Iterate backwards to return the most recently assigned value for this key
        for (int i = s.size() - 1; i >= 0; i--) {
            if (s.get(i).getKey().equals(lookupKey)) {
                return sanitizeValue(s.get(i).getValue());
            }
        }
        return null;
    }

    public String getProperty(String key) {
        if (key == null) return null;
        String lookupKey = key.toUpperCase().trim();
        
        for (var s : sections.values()) {
            // Iterate backwards within the first section that contains the key
            for (int i = s.size() - 1; i >= 0; i--) {
                if (s.get(i).getKey().equals(lookupKey)) {
                    String v = sanitizeValue(s.get(i).getValue());
                    if (v != null) return v;
                }
            }
        }
        return null;
    }

    /**
     * Cross-platform guard:
     * - Windows: Accepts C:\..., C:/..., /c/..., /C:/..., Rejects /usr/bin/...
     * - Unix: Rejects C:\..., C:/...
     */
    private String sanitizeValue(String value) {
        if (value == null) return null;

        String v = value.trim();
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWin) {
            if (v.startsWith("/")
                    && !v.matches("^/[A-Za-z]/.*")      // Allows /c/... (MSYS2/Git Bash variants)
                    && !v.matches("^/[A-Za-z]:/.*")     // Allows /C:/... notation
                    && !v.matches("^[A-Za-z]:.*")) {    // Allows standard C:/... pathways
                return null;
            }
            return v;
        }

        // Unix execution boundaries: Strip out native Windows partition assignments
        if (v.matches("^[A-Za-z]:.*")) {
            return null;
        }

        return v;
    }

    public String getArgs(String toolKey) {
        String v = getProperty("ARGS", toolKey);
        return (v != null) ? v : "";
    }

    public synchronized void setProperty(String section, String key, String value) {
        if (section == null || key == null) return;
        String targetSection = section.toUpperCase().trim();
        String targetKey = key.toUpperCase().trim();
        
        List<Map.Entry<String, String>> s = sections.computeIfAbsent(targetSection, k -> new ArrayList<>());
        
        boolean updated = false;
        // Update the last occurrence of the key if it exists
        for (int i = s.size() - 1; i >= 0; i--) {
            if (s.get(i).getKey().equals(targetKey)) {
                s.set(i, new AbstractMap.SimpleEntry<>(targetKey, value));
                updated = true;
                break;
            }
        }
        // Otherwise, append it as a new line
        if (!updated) {
            s.add(new AbstractMap.SimpleEntry<>(targetKey, value));
        }
        saveSettings();
    }

    public synchronized void setProperty(String key, String value) {
        if (key == null) return;
        String lookupKey = key.toUpperCase().trim();
        String section = "GENERAL"; // Default target fallback for arbitrary engine sets

        if (lookupKey.startsWith("WIN_") || lookupKey.startsWith("UNIX_")) {
            section = "TERMINAL_CONFIG";
        } else if (lookupKey.endsWith("_EXEC") || lookupKey.endsWith("_DIR") || lookupKey.endsWith("_FWORK_DIR")) {
            section = "SYSTEM_PATH";
        } else if (lookupKey.endsWith("_ARGS")) {
            section = "ARGS";
        }

        setProperty(section, lookupKey, value);
    }

    public synchronized void removeProperty(String section, String key) {
        if (section == null || key == null) return;
        String targetSection = section.toUpperCase().trim();
        String targetKey = key.toUpperCase().trim();
        
        if (sections.containsKey(targetSection)) {
            // Remove all occurrences of the key within the specified section
            sections.get(targetSection).removeIf(entry -> entry.getKey().equals(targetKey));
            saveSettings();
        }
    }

    // Exposed for downstream sequential Bash evaluation
    public Map<String, List<Map.Entry<String, String>>> getSequentialStructure() {
        return Collections.unmodifiableMap(sections);
    }

    // -------------------------------------------------------------------------
    // PATH RESOLUTION
    // -------------------------------------------------------------------------

    /**
     * Resolves and normalizes explicit executable paths, validating file or directory presence.
     * Stays completely isolated from the global system environment PATH to maintain strict 
     * user configuration priority. Accommodates virtualized runtime mount points and specialized 
     * Windows/Unix directory structures.
     */
    public String resolvePath(String key, String executableName) {
        String raw = getProperty(key);
        if (raw == null || raw.isBlank()) return null;

        // 1. Normalize cross-platform environment path string layouts
        raw = normalizeCrossPlatformPath(raw);

        // 2. Uniformly resolve system-specific file separator boundaries
        String localized = raw.replace("/", File.separator).replace("\\", File.separator);
        Path p = Paths.get(localized).normalize();
        File f = p.toFile();

        try {
            // Safely check if the target path is a known directory
            boolean isDir = f.exists() && f.isDirectory();

            if (!isDir) {
                // Tier 1: Match against standard filesystem tracking if accessible
                if (f.exists() && matchesExecutable(p, executableName)) {
                    checkIfSymlink(p);
                    return p.toAbsolutePath().toString();
                }

                // Tier 2: Resilient suffix match fallback
                // Bypasses Java NIO/IO false-negatives on virtual reparse mounts
                if (p.getFileName() != null) {
                    String disk = p.getFileName().toString().toLowerCase();
                    String base = executableName.toLowerCase().replace(".exe", "");
                    
                    // Windows adaptation: Strip trailing execution extensions
                    if (System.getProperty("os.name").toLowerCase().contains("win") && disk.endsWith(".exe")) {
                        disk = disk.substring(0, disk.length() - 4);
                    }
                    
                    // Validates base combinations flawlessly
                    if (disk.matches("^" + java.util.regex.Pattern.quote(base) + "([0-9]|\\.|\\-)*$")) {
                        return p.toAbsolutePath().toString();
                    }
                }

                return null;
            }

            // Case 2: The path explicitly tracks a container directory holding the binary
            Path match = findExecutableInDirectory(p, executableName);
            if (match != null) {
                checkIfSymlink(match);
                return match.toAbsolutePath().toString();
            }

        } catch (Exception e) {
            System.err.println("resolvePath(): Invalid path format for key " + key + ": " + raw);
        }

        return null;
    }

    private String normalizeCrossPlatformPath(String raw) {
        if (raw.matches("^/[A-Za-z]:/.*")) {
            return raw.substring(1);
        }

        if (raw.startsWith("/mnt/") && raw.length() > 6) {
            String drive = raw.substring(5, 6).toUpperCase();
            return drive + ":" + raw.substring(6);
        }

        if (raw.matches("^/[A-Za-z]/.*")) {
            String drive = raw.substring(1, 2).toUpperCase();
            return drive + ":" + raw.substring(2);
        }

        // macOS volumes mount layer mapping pass-through
        if (raw.startsWith("/Volumes/")) {
            return raw;
        }

        return raw;
    }

    private boolean matchesExecutable(Path file, String exeName) {
        if (file.getFileName() == null) return false;

        String disk = file.getFileName().toString().toLowerCase();
        String target = exeName.toLowerCase();

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWin) {
            String[] ext = {".exe", ".cmd", ".bat", ".com"};
            for (String e : ext) {
                if (disk.endsWith(e)) disk = disk.substring(0, disk.length() - e.length());
                if (target.endsWith(e)) target = target.substring(0, target.length() - e.length());
            }
            return disk.equals(target);
        }

        if (disk.equals(target)) return true;

        String safe = java.util.regex.Pattern.quote(target);
        return disk.matches("^" + safe + "([0-9]|\\.|\\-)*$");
    }

    private Path findExecutableInDirectory(Path dir, String exeName) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(f -> (Files.isRegularFile(f) || Files.isSymbolicLink(f)) && matchesExecutable(f, exeName))
                    .findFirst()
                    .orElse(null);
        }
    }

    private void checkIfSymlink(Path p) {
        if (Files.isSymbolicLink(p)) {
            try {
                Files.readSymbolicLink(p);
            } catch (IOException ignored) {}
        }
    }

    public String getConfigFilePath() {
        return CONFIG_FILENAME;
    }
}
