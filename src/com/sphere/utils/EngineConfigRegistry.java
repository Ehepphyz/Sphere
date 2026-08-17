package com.sphere.utils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Global Configuration Hub for Sphere.
 * Centralizes value resolution, cross-platform normalization, and variable interpolation.
 * Routes parsing syntax warnings and missing key queries directly into AppLogger diagnostics.
 */
public final class EngineConfigRegistry {

    private static final Map<String, String> RESOLVED_CACHE = new HashMap<>();
    private static final Map<String, String> KEY_TO_CATEGORY_MAP = new HashMap<>();
    
    // Tracks keys that are marked for export into external runtime processes
    private static final Set<String> EXPORTED_KEYS = new HashSet<>();
    
    // Categories strictly restricted from being exported into child sandboxes
    private static final Set<String> EXPORT_BLACKLIST = Set.of("SYSTEM_PATH", "TERMINAL_CONFIG");

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$(?:\\{([A-Z0-9_]+)\\}|([A-Z0-9_]+))");

    private EngineConfigRegistry() {}

    /**
     * Reinitializes and resolves the entire configuration map from the raw settings data structure.
     */
    public static synchronized void synchronize(SettingsManager settingsManager) {
        if (settingsManager == null) return;
        
        RESOLVED_CACHE.clear();
        KEY_TO_CATEGORY_MAP.clear();
        EXPORTED_KEYS.clear();

        Map<String, String> rawMap = new HashMap<>();

        // 1. Pre-populate map with native host environment variables (baseline fallback strategy)
        System.getenv().forEach((envKey, envValue) -> {
            // Guardrail 2: Filter out internal hidden Windows directory keys starting with '='
            if (!envKey.startsWith("=")) {
                String normalizedEnvKey = envKey.toUpperCase().trim();
                rawMap.put(normalizedEnvKey, envValue);
                KEY_TO_CATEGORY_MAP.put(normalizedEnvKey, "HOST_SYSTEM_ENVIRONMENT");
                
                // Inherited host system variables are exported by default
                EXPORTED_KEYS.add(normalizedEnvKey);
            }
        });

        // 2. Map raw key layouts sequentially from configuration structure
        // UPDATED: Now properly iterates over List<Map.Entry> to support Bash-like sequential execution
        settingsManager.getSequentialStructure().forEach((category, entries) -> {
            String normalizedCategory = category.toUpperCase().trim();
            boolean isExportable = !EXPORT_BLACKLIST.contains(normalizedCategory);

            // Special Engine Behavior: Extract binary directories from SYSTEM_PATH and inject them 
            // into the runtime PATH loop before evaluating standard keys.
            if ("SYSTEM_PATH".equals(normalizedCategory)) {
                entries.forEach(entry -> {
                    String value = entry.getValue();
                    if (value != null && !value.isBlank()) {
                        try {
                            Path exePath = Path.of(value.trim());
                            Path parentDir = exePath.getParent();
                            
                            if (parentDir != null) {
                                String binDir = parentDir.toAbsolutePath().normalize().toString();
                                String pathKey = "PATH";
                                String currentPath = rawMap.getOrDefault(pathKey, "");
                                
                                if (!currentPath.contains(binDir)) {
                                    String targetDelimiter = System.getProperty("path.separator");
                                    String updatedPath = currentPath.isEmpty() ? binDir : binDir + targetDelimiter + currentPath;
                                    rawMap.put(pathKey, updatedPath);
                                }
                            }
                        } catch (Exception ignored) {
                            // Suppress exceptions for non-path parameter flags
                        }
                    }
                });
            }

            // Execute line-by-line evaluation
            entries.forEach(entry -> {
                String normalizedKey = entry.getKey().toUpperCase().trim();
                String value = entry.getValue();
                
                // Expand the current value (e.g., $FCAD_BASE:$PATH) against the active rawMap state
                String expandedValue = resolveKeyInternal(value, rawMap, new HashSet<>());

                if (normalizedKey.equals("PATH")) {
                    String existingPath = rawMap.getOrDefault(normalizedKey, "");
                    String separator = System.getProperty("path.separator");
                    
                    // Split, merge, and deduplicate
                    Set<String> pathSet = new LinkedHashSet<>(); // LinkedHashSet preserves order
                    
                    // Add new paths from the config line first (so they take priority)
                    for (String p : expandedValue.split(separator)) {
                        if (!p.isBlank()) pathSet.add(p);
                    }
                    // Add existing paths from earlier loop iterations or system defaults
                    for (String p : existingPath.split(separator)) {
                        if (!p.isBlank()) pathSet.add(p);
                    }
                    
                    // Rebuild and update the runtime dictionary string
                    rawMap.put(normalizedKey, String.join(separator, pathSet));
                } else {
                    // Overwrite the map entry so subsequent lines use the newly updated variable
                    rawMap.put(normalizedKey, expandedValue);
                }

                KEY_TO_CATEGORY_MAP.put(normalizedKey, normalizedCategory);
                if (isExportable) {
                    EXPORTED_KEYS.add(normalizedKey);
                }
            });
        });

        // 3. Final pipeline execution loop across remaining uncached properties
        Set<String> sharedActiveStack = new HashSet<>();
        for (String key : rawMap.keySet()) {
            resolveKey(key, rawMap, sharedActiveStack);
        }
    }

    private static String resolveKeyInternal(String input, Map<String, String> rawMap, Set<String> stack) {
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = (matcher.group(1) != null) ? matcher.group(1) : matcher.group(2);
            String replacement = rawMap.getOrDefault(token.toUpperCase().trim(), System.getenv(token.toUpperCase().trim()));
            if (replacement == null) replacement = "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String resolveKey(String key, Map<String, String> rawMap, Set<String> dependencyStack) {
        // Fast-path cache lookup check (ensuring we bypass if evaluating a nested active loop)
        if (RESOLVED_CACHE.containsKey(key) && !dependencyStack.contains(key)) {
            return RESOLVED_CACHE.get(key);
        }

        String rawValue = rawMap.get(key);
        String currentCategory = KEY_TO_CATEGORY_MAP.getOrDefault(key, "UNKNOWN_CATEGORY");

        if (rawValue == null || rawValue.isBlank()) {
            RESOLVED_CACHE.put(key, "");
            return "";
        }

        // Guardrail 1: If it is a native system environment variable, do not interpolate or modify it
        if ("HOST_SYSTEM_ENVIRONMENT".equals(currentCategory)) {
            RESOLVED_CACHE.put(key, rawValue);
            return rawValue;
        }

        // Track evaluation loop dependencies
        if (!dependencyStack.add(key)) {
            // Check if a point-in-time sequential snapshot is available before emitting an error
            if (RESOLVED_CACHE.containsKey(key)) {
                return RESOLVED_CACHE.get(key);
            }
            AppLogger.error("Circular configuration loop detected under category [" + currentCategory + "] for token: $" + key);
            RESOLVED_CACHE.put(key, rawValue);
            return rawValue; 
        }

        // Interpolate $VARIABLE or ${VARIABLE} tokens found within the raw string layout
        Matcher matcher = VARIABLE_PATTERN.matcher(rawValue);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String tokenName = (matcher.group(1) != null) ? matcher.group(1) : matcher.group(2);
            tokenName = tokenName.toUpperCase().trim();
            String replacement = "";

            if (rawMap.containsKey(tokenName)) {
                replacement = resolveKey(tokenName, rawMap, dependencyStack);
            } else {
                String osEnv = System.getenv(tokenName);
                if (osEnv == null && "HOME".equals(tokenName)) {
                    osEnv = System.getProperty("user.home");
                }
                
                if (osEnv != null) {
                    replacement = osEnv;
                } else {
                    AppLogger.warn("Unresolved reference detected in [" + currentCategory + "] -> " + key + "=\"" + rawValue + "\". Token $" + tokenName + " does not exist.");
                    replacement = matcher.group(0); // Fall back to outputting raw literal text
                }
            }
            
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        String resolvedValue = sb.toString();
        String trimmed = resolvedValue.trim();
        
        boolean isUri = trimmed.contains("://");
        boolean hasSpaces = trimmed.contains(" ");
        boolean hasSeparators = trimmed.contains("/") || trimmed.contains("\\");
        
        // Intelligent, behavior-based cross-platform path list normalization
        String targetDelimiter = System.getProperty("path.separator");

        // Scope normalization exclusively to keys structural to system lookups
        boolean isPathLikeKey =
                "PATH".equals(key)
                || key.endsWith("_PATH")
                || key.endsWith("PATH_LIST");

        if (isPathLikeKey && !isUri) {
            if (";".equals(targetDelimiter) && trimmed.contains(":")) {
                // Windows-specific path adaptation:
                // Splits on true Unix-style PATH separators while protecting standard drive letters.
                // Guarded by 'isPathLikeKey' to ensure zero impact on compiler arguments or inline flags.
                String[] pathSegments = trimmed.split(":(?![/\\\\])");
                trimmed = String.join(";", pathSegments);
                resolvedValue = trimmed;
            } else if (":".equals(targetDelimiter) && trimmed.contains(";")) {
                // Unix/macOS/WSL fallback: Ensures uniform colon-delimited path structures.
                trimmed = trimmed.replace(";", ":");
                resolvedValue = trimmed;
            }
        }


        // Evaluate localized path normalization profiles
        if (hasSeparators && !isUri && !hasSpaces && !trimmed.contains(";")) {
            try {
                resolvedValue = Path.of(trimmed).normalize().toString();
            } catch (Exception ignored) {
                AppLogger.debug("Skipped path normalization formatting rules on syntax exception for field: " + key + "=\"" + trimmed + "\"");
            }
        }

        RESOLVED_CACHE.put(key, resolvedValue);
        dependencyStack.remove(key); // Cleans up the single shared stack state during recursion unwind
        return resolvedValue;
    }

    /**
     * Universal access method to read properties safely anywhere inside Sphere.
     * Fires a descriptive warning if an operational module queries a nonexistent lookup identity.
     */
    public static synchronized String get(String key) {
        if (key == null) return "";
        String lookupKey = key.toUpperCase().trim();
        
        if (!RESOLVED_CACHE.containsKey(lookupKey)) {
            AppLogger.warn("Engine execution layer requested a missing configuration key identifier: \"" + key + "\"");
            return "";
        }
        
        return RESOLVED_CACHE.get(lookupKey);
    }

    /**
     * Helper accessor returning properties with a default fallback option.
     */
    public static synchronized String getOrDefault(String key, String defaultValue) {
        String val = get(key);
        return val.isEmpty() ? defaultValue : val;
    }

    /**
     * Compiles and outputs a filtered environment map containing exclusively authorized export variables.
     * Designed to safely initialize ProcessBuilder environments for sandboxed compilation scripts and runtimes.
     */
    public static synchronized Map<String, String> getExportEnvironment() {
        Map<String, String> exportMap = new HashMap<>();
        for (String key : EXPORTED_KEYS) {
            exportMap.put(key, RESOLVED_CACHE.getOrDefault(key, ""));
        }
        return exportMap;
    }
}
