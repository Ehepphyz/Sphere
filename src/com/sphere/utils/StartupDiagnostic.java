package com.sphere.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.sphere.core.rootbackend.RootBridgeCompiler;

/**
 * Orchestrates multi-threaded environment validation and dependency mapping.
 * Avoids terminal clutter by aggregating successful checks and only surfacing active issues.
 */
public final class StartupDiagnostic {

    private static final Map<String, ValidationResult> results = new ConcurrentHashMap<>();
    private static boolean configUpdated = false;
    private static boolean autoDiscoveryEnabled = false;

    private record ValidationResult(
        String label, 
        String resolvedPath, 
        boolean isOptional, 
        boolean success, 
        String errorMessage
    ) {}

    private record DiagnosticTarget(
        String key, 
        String defaultExe, 
        String label, 
        boolean isOptional
    ) {}

    /**
     * Executes parallel system dependency discovery and verification.
     */
    public static void run(SettingsManager sm) {
        // Lightweight, modern header for optimal daily terminal usage
        AppLogger.raw("─ Sphere | Startup Diagnostics ─────────────────────────────────────");

        String os = System.getProperty("os.name").toLowerCase();
        boolean isWin = os.contains("win");

        results.clear();
        configUpdated = false;

        File confFile = new File(SettingsManager.CONFIG_FILENAME);
        if (!confFile.exists()) {
            autoDiscoveryEnabled = true;
            saveConfiguration(sm);
        } else {
            autoDiscoveryEnabled = false;
        }

        // 1. Gather all system diagnostic tasks
        List<DiagnosticTarget> targets = new ArrayList<>();
        populateDiagnosticTargets(targets, isWin);

        // 2. Process validations concurrently to prevent disk-I/O bottlenecking on startup
        targets.parallelStream().forEach(target -> validate(sm, target));

        // 3. Compile the C++ bridge silently in the background
        try {
            RootBridgeCompiler.getOrCompileBridge(sm);
        } catch (Exception ignored) {
            // Intentionally swallowed to ensure the main UI lifecycle remains non-blocking
        }

        if (configUpdated) {
            saveConfiguration(sm);
        }

        PythonEnvs.initialize(sm);

        // 4. Generate a clean, unified, and professional summary report
        generateSummaryReport();
    }

    private static void populateDiagnosticTargets(List<DiagnosticTarget> targets, boolean isWin) {
        // Core JVM & Runtimes
        targets.add(new DiagnosticTarget("JAVA_EXEC", "java", "Java (Default)", false));
        targets.add(new DiagnosticTarget("JAVA_ALTER", "java", "Java (Alternative)", true));
        targets.add(new DiagnosticTarget("PYTHON_EXEC", isWin ? "python" : "python3", "Python Interpreter", false));
        targets.add(new DiagnosticTarget("NODE_DIR", "node", "Node.js Runtime", false));

        // Compilers & Build Utilities
        targets.add(new DiagnosticTarget("GCC_DIR", "gcc", "GCC Compiler", false));
        targets.add(new DiagnosticTarget("GPP_DIR", "g++", "G++ Compiler", false));
        targets.add(new DiagnosticTarget("FORTRAN_DIR", "gfortran", "Fortran Compiler", true));
        targets.add(new DiagnosticTarget("CLANG_DIR", "clang", "LLVM/Clang (C Compiler)", true));
        targets.add(new DiagnosticTarget("CLANGPP_DIR", "clang++", "LLVM/Clang++ (C++ Compiler)", true));
        targets.add(new DiagnosticTarget("CMAKE_DIR", "cmake", "CMake Build Tool", false));
        targets.add(new DiagnosticTarget("JULIA_DIR", isWin ? "julia.exe" : "julia", "Julia Language", true));

        // Terminal Shell Profiles
        if (isWin) {
            targets.add(new DiagnosticTarget("WIN_CMD", "cmd.exe", "Windows CMD", true));
            targets.add(new DiagnosticTarget("WIN_POWERSHELL", "powershell.exe", "Windows PowerShell", true));
            targets.add(new DiagnosticTarget("WIN_PWSH", "pwsh.exe", "PowerShell Core", true));
            targets.add(new DiagnosticTarget("WIN_WSL", "wsl.exe", "Windows Subsystem for Linux", true));
        } else {
            targets.add(new DiagnosticTarget("UNIX_BASH", "bash", "Unix Bash Shell", true));
            targets.add(new DiagnosticTarget("UNIX_ZSH", "zsh", "Unix Zsh Shell", true));
            targets.add(new DiagnosticTarget("UNIX_SH", "sh", "Unix Standard Shell", true));
        }
    }

    private static void validate(SettingsManager sm, DiagnosticTarget target) {
        String raw = sm.getProperty(target.key);

        // Handle missing key mapping
        if (raw == null || raw.trim().isEmpty()) {
            if (autoDiscoveryEnabled) {
                String discovered = autoDiscoverExecutable(target.defaultExe);
                if (discovered != null) {
                    sm.setProperty(target.key, discovered);
                    configUpdated = true;
                    results.put(target.key, new ValidationResult(target.label, discovered, target.isOptional, true, null));
                    return;
                }
            }
            if (!target.isOptional) {
                results.put(target.key, new ValidationResult(target.label, null, false, false, "Configuration mapping is missing."));
            }
            return;
        }

        // Validate explicit paths robustly (resolves symlinks, system environments, virtual sandboxes)
        String path = null;
        String attemptedTarget = target.defaultExe;
        Path fileCheck = Paths.get(raw.trim());

        if (Files.exists(fileCheck) && !Files.isDirectory(fileCheck)) {
            if (fileCheck.getFileName() != null) {
                String fileName = fileCheck.getFileName().toString();
                attemptedTarget = fileName.toLowerCase().endsWith(".exe") ? 
                        fileName.substring(0, fileName.length() - 4) : fileName;
            }
            path = sm.resolvePath(target.key, attemptedTarget);
        }

        if (path == null) {
            attemptedTarget = target.defaultExe;
            path = sm.resolvePath(target.key, target.defaultExe);
        }

        if (path != null) {
            results.put(target.key, new ValidationResult(target.label, path, target.isOptional, true, null));
        } else {
            String errorMsg = "Path '" + raw + "' is invalid or does not target '" + attemptedTarget + "'";
            results.put(target.key, new ValidationResult(target.label, null, target.isOptional, false, errorMsg));
        }
    }

    /**
     * Analyzes compiled verification mappings to print a high-grade professional summary.
     */
    private static void generateSummaryReport() {
        long totalCore = results.values().stream().filter(r -> !r.isOptional).count();
        long successfulCore = results.values().stream().filter(r -> !r.isOptional && r.success).count();
        long criticalErrors = results.values().stream().filter(r -> !r.isOptional && !r.success).count();

        // Print warning details ONLY
        results.values().stream()
                .filter(r -> r.isOptional && !r.success && r.errorMessage != null)
                .forEach(r -> AppLogger.warn("Optional toolchain offline: " + r.label));

        // Print critical errors ONLY
        results.values().stream()
                .filter(r -> !r.isOptional && !r.success)
                .forEach(r -> AppLogger.error("CRITICAL: " + r.label + " FAILED -> " + r.errorMessage));

        if (criticalErrors == 0) {
            // Pristine operational summary
            AppLogger.info(String.format("Environment verified. %d/%d core runtimes fully functional. Ready.", 
                    successfulCore, totalCore));
        } else {
            AppLogger.error(String.format("System initialization failed. %d critical components missing.", 
                    criticalErrors));
        }
    }

    private static String autoDiscoverExecutable(String exe) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String[] dirs = pathEnv.split(File.pathSeparator);
        String[] winExts = {".exe", ".cmd", ".bat"};

        for (String d : dirs) {
            try {
                Path dir = Paths.get(d);
                if (!Files.isDirectory(dir)) continue;

                if (isWin) {
                    for (String ext : winExts) {
                        Path candidate = dir.resolve(exe + ext);
                        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                            return candidate.toAbsolutePath().toString();
                        }
                    }
                }

                Path candidate = dir.resolve(exe);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void saveConfiguration(SettingsManager sm) {
        try {
            sm.save();
        } catch (Exception e) {
            AppLogger.error("Failed to persist environment changes: " + e.getMessage());
        }
    }
}