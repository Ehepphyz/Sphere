package com.sphere.utils;

import java.io.*;
import java.util.Map;

/**
 * Manages the local workspace Python runtime configurations, caching environment variables,
 * detecting virtual environments across multiple OS architectures, and generating layouts via CLI.
 */
public class PythonEnvs {

    private static final String CONFIG_DIR_NAME = "config";
    private static final String CONFIG_FILE_NAME = "pythonpath.conf";
    private static String cachedPythonPath = "";

    /**
     * Safe baseline setup. Verifies if the 'config' layout directory exists,
     * creates it if missing, and loads or automatically generates the cache file on boot.
     *
     * @param sm Active system settings management instance layer.
     */
    public static void initialize(SettingsManager sm) {
        try {
            File configDir = new File(CONFIG_DIR_NAME);
            if (!configDir.exists()) {
                if (configDir.mkdirs()) {
                    /* AppLogger.debug("Created missing configurations workspace directory: " + CONFIG_DIR_NAME); */
                }
            }

            File configFile = new File(configDir, CONFIG_FILE_NAME);
            if (configFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line = reader.readLine();
                    cachedPythonPath = (line != null) ? line.trim() : "";
                }
                /* AppLogger.debug("Loaded cached PYTHONPATH seamlessly from storage: " + cachedPythonPath); */
            } else {
                /* AppLogger.debug("Missing 'pythonpath.conf' on boot. Initiating automatic environment scan..."); */
                updatePythonPathCacheFile(sm);
            }
        } catch (Exception e) {
            AppLogger.error("Failed to initialize Python filesystem layout layers: " + e.getMessage());
        }
    }

    /**
     * Command Interceptor / Router for your CLI parser engine.
     * Call this inside your main shell switch/case loop when a command starts with ':py'.
     *
     * @param fullCommand The raw text entered by the user (e.g., ":py update pythonpath")
     * @param sm Active system settings management instance layer.
     * @return true if the command was recognized and handled, false otherwise.
     */
    public static boolean handleCliCommand(String fullCommand, SettingsManager sm) {
        String cleanCmd = fullCommand.trim().toLowerCase();
        
        if (cleanCmd.equals(":py update pythonpath")) {
            /* AppLogger.debug("Initiating manual system environments scan mapping..."); */
            updatePythonPathCacheFile(sm);
            return true;
        }
        
        if (cleanCmd.startsWith(":py ")) {
            AppLogger.error("Unknown engine sub-command switch parameter context. Target usage: ':py update pythonpath'");
            return true;
        }
        
        return false;
    }

    /**
     * Probes the host system shell execution parameters securely to map active site-packages 
     * locations, flushing the compiled lines straight into local storage.
     *
     * @param sm Active system settings management instance layer.
     */
    public static void updatePythonPathCacheFile(SettingsManager sm) {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWin = os.contains("win");
        String pythonBinary = null;

        File configDir = new File(CONFIG_DIR_NAME);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        // Check file status before writing to separate "create" vs "update" log semantics
        File configFile = new File(configDir, CONFIG_FILE_NAME);
        boolean isCreatingNewFile = !configFile.exists();

        // --- Tier 1: Local In-Workspace Virtual Environment (venv) ---
        File venvPython = isWin ? new File("venv/Scripts/python.exe") : new File("venv/bin/python");
        if (venvPython.exists() && venvPython.canExecute()) {
            pythonBinary = venvPython.getAbsolutePath();
            /* AppLogger.debug("Detected workspace local venv: " + pythonBinary); */
        }

        // --- Tier 2: Local Pixi Environment (.pixi workspace layout) ---
        if (pythonBinary == null) {
            String pixiPath = isWin ? ".pixi/envs/default/python.exe" : ".pixi/envs/default/bin/python";
            File pixiPython = new File(pixiPath);
            if (pixiPython.exists() && pixiPython.canExecute()) {
                pythonBinary = pixiPython.getAbsolutePath();
                /* AppLogger.debug("Detected local Pixi context: " + pythonBinary); */
            }
        }

        // --- Tier 3: Active Shell Conda Context (Inherited environment variable) ---
        if (pythonBinary == null) {
            String condaPrefix = System.getenv("CONDA_PREFIX");
            if (condaPrefix != null && !condaPrefix.isBlank()) {
                File condaPython = isWin ? new File(condaPrefix, "python.exe") : new File(condaPrefix, "bin/python");
                if (condaPython.exists() && condaPython.canExecute()) {
                    pythonBinary = condaPython.getAbsolutePath();
                    /* AppLogger.debug("Detected active inherited Conda environment: " + pythonBinary); */
                }
            }
        }

        // --- Tier 4: Dynamic Global Fallbacks Scan via ProcessBuilder (Conda toolchain) ---
        if (pythonBinary == null) {
            /* AppLogger.debug("No active terminal environment found. Scanning global package managers..."); */
            pythonBinary = probeCondaEnvironments(isWin);
        }

        // --- Tier 5: Fallback to SettingsManager (settings.conf parser switch layer) ---
        if (pythonBinary == null) {
            String resolvedGlobal = sm.resolvePath("PYTHON_EXEC", isWin ? "python.exe" : "python3");
            if (resolvedGlobal != null && !resolvedGlobal.isBlank()) {
                // Force resolving to absolute file context to avoid unsafe/weird relative paths layout breaks
                File resolvedFile = new File(resolvedGlobal).getAbsoluteFile();
                if (resolvedFile.exists() && resolvedFile.canExecute() && isValidPython(resolvedFile.getAbsolutePath())) {
                    pythonBinary = resolvedFile.getAbsolutePath();
                    /* AppLogger.debug("Using explicit switcher runtime parameter from settings.conf: " + pythonBinary); */
                }
            }
        }

        // --- Tier 6: Smart System PATH & Launcher Fallback Array ---
        if (pythonBinary == null) {
            // Ordered candidates from highest priority/specificity to lowest generic fallbacks
            String[] globalCandidates;
            if (isWin) {
                globalCandidates = new String[]{
                    "py", // Windows Python Launcher (PEP 397) - Resolves active/latest version cleanly
                    "python", 
                    "python3", 
                    "python3.13", "python3.12", "python3.11", "python3.10"
                };
            } else {
                globalCandidates = new String[]{
                    "python3", 
                    "python", 
                    "python3.13", "python3.12", "python3.11", "python3.10"
                };
            }

            for (String candidate : globalCandidates) {
                if (isValidPython(candidate)) {
                    pythonBinary = candidate;
                    /* AppLogger.debug("Resolved functional interpreter via global fallback lookup candidate: " + candidate); */
                    break;
                }
            }
        }

        // Critical fallback guard validation check before execution probe routines
        if (pythonBinary == null) {
            AppLogger.error("Python environment validation critical failure: No functional interpreter found on host system.");
            return;
        }

        // --- Execute Reflection Probe ---
        StringBuilder customPythonPath = new StringBuilder();
        String existingPythonPath = System.getenv("PYTHONPATH");
        
        // Fallback for macOS/Linux: Query via non-interactive shell layout sourcing to prevent hangs or prompt noise
        if ((existingPythonPath == null || existingPythonPath.isBlank()) && !isWin) {
            try {
                String shell = os.contains("mac") ? "zsh" : "bash";
                String rcFile = os.contains("mac") ? "~/.zshrc" : "~/.bashrc";
                
                // Construct a safe command: source the config file if present, then wrap the output in clear tokens
                String shellCmd = String.format("[ -f %s ] && . %s; echo \"---START_PP---\"$PYTHONPATH\"---END_PP---\"", rcFile, rcFile);
                
                ProcessBuilder shellPb = new ProcessBuilder(shell, "-c", shellCmd);
                Process shellProc = shellPb.start();
                
                try (BufferedReader r = new BufferedReader(new InputStreamReader(shellProc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("---START_PP---") && line.contains("---END_PP---")) {
                            int startIdx = line.indexOf("---START_PP---") + "---START_PP---".length();
                            int endIdx = line.indexOf("---END_PP---");
                            String extractedPath = line.substring(startIdx, endIdx).trim();
                            if (!extractedPath.isEmpty()) {
                                existingPythonPath = extractedPath;
                            }
                            break;
                        }
                    }
                }

                boolean finished = shellProc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    shellProc.destroyForcibly();
                }

            } catch (Exception e) {
                /* Silent fallback if terminal profile sourcing isn't available */
            }
        }

        if (existingPythonPath != null && !existingPythonPath.isBlank()) {
            customPythonPath.append(existingPythonPath);
        }

        try {
            /* AppLogger.debug("Executing sys.path compilation probe on binary: " + pythonBinary); */
            ProcessBuilder pathPb = new ProcessBuilder(pythonBinary, "-c", "import sys; print(','.join(sys.path))");
            pathPb.environment().putAll(System.getenv());
            Process pathProc = pathPb.start();
            
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pathProc.getInputStream()))) {
                String pathsLine = r.readLine();
                if (pathsLine != null && !pathsLine.isBlank()) {
                    for (String p : pathsLine.split(",")) {
                        String cleanPath = p.trim();
                        
                        // Avoid empty elements and prevent double path separators (;; or ::)
                        if (cleanPath.isEmpty()) {
                            continue;
                        }
                        
                        String lowerPath = cleanPath.toLowerCase();
                        
                        // Strict verification to avoid false positives like C:\Windows\System32 or custom folders
                        boolean isSitePackages = lowerPath.contains("site-packages") || lowerPath.contains("dist-packages");
                        boolean isStrictPythonLib = cleanPath.endsWith(File.separator + "Lib") 
                                                || cleanPath.endsWith(File.separator + "Lib" + File.separator)
                                                || lowerPath.contains(File.separator + "lib" + File.separator + "python");

                        if (isSitePackages || isStrictPythonLib) {
                            if (customPythonPath.length() > 0) {
                                customPythonPath.append(File.pathSeparator);
                            }
                            customPythonPath.append(cleanPath);
                        }
                    }
                }
            }

            // --- Secure Timeout Enforcement ---
            boolean finished = pathProc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                pathProc.destroyForcibly();
                AppLogger.error("Interpreter reflection probe timed out and was forcibly terminated.");
            }

        } catch (Exception e) {
            AppLogger.error("Interpreter reflection probe failed to resolve layers layout: " + e.getMessage());
        }

        // --- Commit Compiled File ---
        try {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
                writer.write(customPythonPath.toString());
            }
            cachedPythonPath = customPythonPath.toString();
            
            // Adjusted output semantic messages contextually
            if (isCreatingNewFile) {
                AppLogger.success("PYTHONPATH configuration layout created successfully.");
            } else {
                AppLogger.success("PYTHONPATH configuration layout updated successfully.");
            }
        } catch (IOException ioEx) {
            AppLogger.error("Failed to commit resolved paths array to storage: " + ioEx.getMessage());
        }
    }

    /**
     * Verification guard: Executes the binary with '--version' to ensure it is a functional,
     * non-blocking executable (preventing Windows Store App Execution Alias traps).
     */
    private static boolean isValidPython(String binaryPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            // Safety timeout to catch hanging Windows Store execution aliases
            boolean finished = p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (finished && p.exitValue() == 0) {
                return true;
            }
            if (!finished) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            /* Intentionally silent: executable is unreachable or invalid */
        }
        return false;
    }

    /**
     * Safety Guard Probe: Queries the Conda binary directly via terminal context tracking to map paths.
     */
    private static String probeCondaEnvironments(boolean isWin) {
        try {
            String condaCmd = isWin ? "conda.exe" : "conda";
            ProcessBuilder pb = new ProcessBuilder(condaCmd, "info", "--envs");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#") || line.isBlank()) continue;
                    
                    // Remove the active environment indicator if present
                    String cleanLine = line.replace("*", "").trim();
                    String[] tokens = cleanLine.split("\\s+");
                    
                    if (tokens.length >= 2) {
                        // Reconstruct paths that might contain spaces by skipping the first token (the env name)
                        // Find where the path starts in the original cleanLine
                        String envName = tokens[0];
                        int pathStartIndex = cleanLine.indexOf(envName) + envName.length();
                        String envPathPath = cleanLine.substring(pathStartIndex).trim();
                        
                        File possiblePython = isWin ? new File(envPathPath, "python.exe") : new File(envPathPath, "bin/python");
                        if (possiblePython.exists() && possiblePython.canExecute()) {
                            p.destroy();
                            /* AppLogger.debug("Dynamic Conda scanning engine resolved valid environment target: " + possiblePython.getAbsolutePath()); */
                            return possiblePython.getAbsolutePath();
                        }
                    }
                }
            }

            // --- Secure Timeout Enforcement for Conda ---
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                /* AppLogger.debug("Conda environments probe timed out and was forcibly terminated."); */
            }

        } catch (Exception e) {
            /* AppLogger.debug("Conda package manager execution command was not reachable or uninitialized."); */
        }
        return null;
    }

    public static String getCachedPythonPath() {
        return cachedPythonPath;
    }
}
