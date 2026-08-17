package com.sphere.utils.settingsmanager;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Multi-platform validation engine (Windows, Linux, macOS).
 * Restricts absolute path checking strictly to the [SYSTEM] category context
 * to prevent false positives on CLI argument flags or environment parameters.
 */
public final class SettingsValidator {

    private static final Pattern PORT_PATTERN = Pattern.compile("^[0-9]+$");
    private static final Pattern BOOL_PATTERN = Pattern.compile("^(true|false)$", Pattern.CASE_INSENSITIVE);

    private SettingsValidator() {}

    /**
     * Checks if a string conforms to a valid absolute path format for the host operating system.
     */
    public static boolean isValidAbsoluteSystemPath(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();

        // Handle quoted paths containing spaces (e.g., "C:\Program Files\...")
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        } else if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        try {
            // Java NIO automatically adapts to Windows (C:\) or Unix/macOS (/) layout constraints
            Path parsed = Paths.get(trimmed);
            return parsed.isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Core validation entry-point routing properties based on clear category environments.
     */
    public static boolean validate(String category, String key, String value) {
        if (value == null) return false;
        
        String cleanValue = value.trim();
        String upperKey = key.toUpperCase().trim();
        String upperCategory = category != null ? category.toUpperCase().trim() : "";

        // 1. Structural Port Validations (Global rule based on naming convention)
        if (upperKey.contains("PORT")) {
            if (!PORT_PATTERN.matcher(cleanValue).matches()) return false;
            try {
                int port = Integer.parseInt(cleanValue);
                return port >= 0 && port <= 65535;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // 2. Structural Boolean Validations (Global rule based on naming convention)
        if (upperKey.contains("ENABLED") || upperKey.contains("USE_") || upperKey.contains("DEBUG")) {
            return BOOL_PATTERN.matcher(cleanValue).matches();
        }

        // 3. Category Isolation: Path validation is STRICTLY applied only if inside [SYSTEM] or [SYSTEM_PATH]
        if ("SYSTEM".equals(upperCategory) || "SYSTEM_PATH".equals(upperCategory)) {
            return isValidAbsoluteSystemPath(cleanValue);
        }

        // 4. Fallback: Everything else (including argument blocks like --jobs=4 or JVM flags) 
        // is safely treated as a standard configuration string, bypassing path validation entirely.
        return true;
    }
}
