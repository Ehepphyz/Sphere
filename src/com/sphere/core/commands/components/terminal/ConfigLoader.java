package com.sphere.components.terminal;

import java.io.*;
import java.net.URI;
import java.util.*;

/**
 * Note on Configuration Nuance:
 * 1. OS-Specific Prefixes: All keys in the [Terminal_Config] section must be 
 * prefixed with 'WIN_' (for Windows) or 'UNIX_' (for Linux/macOS). The 
 * loader automatically filters keys based on the current operating system 
 * to prevent cross-platform configuration errors.
 * 2. Mandatory Configuration: Keys that are commented out (#) or have empty 
 * values (e.g., WIN_EXAMPLE=) are ignored by the loader. This ensures 
 * that only explicitly defined and valid paths are added to the system.
 * 3. File Paths: In the settings.conf file, use single backslashes for 
 * Windows paths (e.g., C:\Path\To\Exe). The ConfigLoader will interpret 
 * these correctly without the need for escaped double-backslashes.
 */
public class ConfigLoader {
    private static final String CONFIG_FILENAME = "settings.conf";
    private static final Map<String, String> terminalPaths = new HashMap<>();

    public static void load() {
        // Clear previous paths before reloading to maintain an accurate state
        terminalPaths.clear();

        String os = System.getProperty("os.name").toLowerCase();
        String prefix = os.contains("win") ? "WIN_" : "UNIX_";

        // Resolve absolute path to avoid "Working Directory" changes during runtime
        File configFile = getAbsoluteConfigFile();

        try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
            String line;
            boolean inTerminalSection = false;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                
                // Ignore comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                // Identify section (case-insensitive to be safe)
                if (line.equalsIgnoreCase("[TERMINAL_CONFIG]")) { 
                    inTerminalSection = true; 
                    continue; 
                }
                if (line.startsWith("[")) { 
                    inTerminalSection = false; 
                    continue; 
                }
                
                // Process key=value pairs
                if (inTerminalSection && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String value = (parts.length > 1) ? parts[1].trim() : "";
                    
                    // Only store valid paths that start with the correct OS prefix
                    if (key.startsWith(prefix) && !value.isEmpty()) {
                        terminalPaths.put(key, value);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Config file not found or unreadable at " + configFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public static String getPath(String key) {
        return terminalPaths.get(key);
    }

    /**
     * Dynamically locates the settings.conf file relative to the application workspace 
     * or JAR file location, preventing absolute path failures on Unix systems.
     */
    private static File getAbsoluteConfigFile() {
        try {
            URI jarUri = ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File jarFile = new File(jarUri);
            // If running inside a directory (like an IDE workspace), look in the parent or current directory
            File parentDir = jarFile.isDirectory() ? jarFile : jarFile.getParentFile();
            return new File(parentDir, CONFIG_FILENAME);
        } catch (Exception e) {
            // Fallback to relative path if dynamic resolution fails
            return new File(CONFIG_FILENAME);
        }
    }
}
