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

    private static boolean autoDiscoveryEnabled = false;
    private static final Map<String, ValidationResult> results = new ConcurrentHashMap<>();
    private static boolean configUpdated = false;

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

        // Detection fills the file only when there is none. An existing settings.conf
        // is the user's, and is never completed behind their back.
        File confFile = new File(SettingsManager.CONFIG_FILENAME);
        autoDiscoveryEnabled = !confFile.exists();
        if (autoDiscoveryEnabled) {
            saveConfiguration(sm);
        }

        // 1. Gather all system diagnostic tasks
        List<DiagnosticTarget> targets = new ArrayList<>();
        populateDiagnosticTargets(targets, isWin);

        // 2. Probe concurrently, then write in the declared order. Writing from the
        // parallel stream put the keys in the file in whatever order the threads
        // finished, so a generated settings.conf came out differently every time.
        Map<String, String> discovered = new ConcurrentHashMap<>();
        targets.parallelStream().forEach(target -> validate(sm, target, discovered));

        if (autoDiscoveryEnabled) {
            // Folders a tool was already found in. A toolchain keeps its pieces
            // together, so gdb missing from the PATH is usually next to the g++
            // that was just located.
            List<File> knownFolders = new ArrayList<>();
            String previousSystemKey = null;
            for (DiagnosticTarget target : targets) {
                String value = discovered.get(target.key());
                if (value == null) {
                    value = besideKnown(knownFolders, target.defaultExe(), isWin);
                }
                if (value != null) {
                    File folder = new File(value).getParentFile();
                    if (folder != null && knownFolders.stream()
                            .noneMatch(f -> f.getAbsolutePath().equals(folder.getAbsolutePath()))) {
                        knownFolders.add(folder);
                    }
                }
                if (value != null) {
                    sm.setProperty(target.key(), value);
                    configUpdated = true;
                    if (isSystemPathKey(target.key())) {
                        previousSystemKey = target.key();
                    }
                } else if (isSystemPathKey(target.key())) {
                    // Written as a comment, not as an empty declaration: an empty
                    // value means the user disabled that backend, and discovery has
                    // no business deciding that on their behalf.
                    sm.declarePlaceholder("SYSTEM_PATH", previousSystemKey, target.key());
                    configUpdated = true;
                }
            }
        }

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
        targets.add(new DiagnosticTarget("GDB_DIR", isWin ? "gdb.exe" : "gdb",
                                         "GDB Debugger", true));
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

    /** Mirrors the section SettingsManager files a key under. */
    private static boolean isSystemPathKey(String key) {
        String k = key.toUpperCase().trim();
        if (k.startsWith("WIN_") || k.startsWith("UNIX_")) {
            return false;
        }
        return k.endsWith("_EXEC") || k.endsWith("_DIR") || k.endsWith("_FWORK_DIR");
    }

    private static void validate(SettingsManager sm, DiagnosticTarget target,
                                 Map<String, String> discoveries) {
        String raw = sm.getProperty(target.key);

        // Handle missing key mapping
        if (raw == null || raw.trim().isEmpty()) {
            if (autoDiscoveryEnabled) {
                String discovered = autoDiscoverExecutable(target.defaultExe);
                if (discovered != null) {
                    discoveries.put(target.key, discovered);
                    results.put(target.key, new ValidationResult(target.label, discovered, target.isOptional, true, null));
                    return;
                }
            }
            // A key present but empty is settings.conf's way of disabling a
            // backend, and SettingsManager owns that rule: it holds only in
            // [SYSTEM_PATH] and [GENERAL]. Reporting it as a missing critical
            // component turned a deliberate choice into an alarm at every startup.
            if (sm.isDeclaredEmpty(target.key)) {
                results.put(target.key, new ValidationResult(target.label, null, true, false,
                    "Disabled: the key is empty in settings.conf."));
            } else if (!target.isOptional) {
                results.put(target.key, new ValidationResult(target.label, null, false, false,
                    raw == null
                        ? "Not configured: the key is absent from settings.conf."
                        : "Not configured: the key is empty outside [SYSTEM_PATH] and "
                          + "[GENERAL], where alone a blank line disables a backend."));
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
            return;
        }

        // The declared path cannot work on this machine. The rest of Sphere answers
        // that with the same rule that lets one settings.conf serve Windows, WSL,
        // Linux and macOS: look for the tool where this system keeps it. Without
        // this the diagnostic said CRITICAL for a g++ that resolveTool was about to
        // find, and Sphere refused to start over a path meant for another OS.
        String elsewhere = sm.resolveTool(target.key, target.defaultExe);
        if (elsewhere != null) {
            results.put(target.key, new ValidationResult(target.label, elsewhere,
                target.isOptional, true,
                "declared path '" + raw + "' does not work on this system; using "
                + elsewhere + "."));
            return;
        }

        String errorMsg = "Path '" + raw + "' is invalid or does not target '" + attemptedTarget + "'";
        results.put(target.key, new ValidationResult(target.label, null, target.isOptional, false, errorMsg));
    }

    /**
     * Analyzes compiled verification mappings to print a high-grade professional summary.
     */
    private static void generateSummaryReport() {
        long totalCore = results.values().stream().filter(r -> !r.isOptional).count();
        long successfulCore = results.values().stream().filter(r -> !r.isOptional && r.success).count();
        long criticalErrors = results.values().stream().filter(r -> !r.isOptional && !r.success).count();

        // A declared path that cannot work here, answered by the same tool found on
        // this system: said once so it can be corrected, not treated as a failure.
        results.values().stream()
                .filter(r -> r.success && r.errorMessage != null)
                .forEach(r -> AppLogger.warn(r.label + ": " + r.errorMessage));

        // An optional toolchain that is not installed says nothing: not having
        // Julia or Fortran is a choice, not a fault to report at every startup.

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

    /**
     * Finds a tool on the PATH, accepting the versioned names distributions ship.
     * Looking for the exact name only found nothing on a machine carrying g++-12
     * and no plain g++, which is the normal Debian and Ubuntu layout.
     */
    private static String autoDiscoverExecutable(String exe) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || exe == null) return null;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String[] dirs = pathEnv.split(File.pathSeparator);
        String[] winExts = {".exe", ".cmd", ".bat"};

        // name, then name-14, name-13 ... the highest version found.
        java.util.regex.Pattern versioned = java.util.regex.Pattern.compile(
            "^" + java.util.regex.Pattern.quote(exe) + "-([0-9]+(?:\\.[0-9]+)*)"
            + (isWin ? "(\\.exe|\\.cmd|\\.bat)?" : "") + "$",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        String bestVersioned = null;
        java.util.List<Integer> bestVersion = null;

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

                try (java.util.stream.Stream<Path> listing = Files.list(dir)) {
                    for (Path entry : listing.toList()) {
                        java.util.regex.Matcher m =
                            versioned.matcher(entry.getFileName().toString());
                        if (!m.matches() || !Files.isRegularFile(entry)
                                || !Files.isExecutable(entry)) {
                            continue;
                        }
                        java.util.List<Integer> version = new ArrayList<>();
                        for (String part : m.group(1).split("\\.")) {
                            version.add(Integer.parseInt(part));
                        }
                        if (bestVersion == null || compare(version, bestVersion) > 0) {
                            bestVersion = version;
                            bestVersioned = entry.toAbsolutePath().toString();
                        }
                    }
                } catch (Exception ignored) {
                    // unreadable directory on the PATH
                }
            } catch (Exception ignored) {}
        }
        return bestVersioned;
    }

    /** Looks for a tool in the folders where the others were already found. */
    private static String besideKnown(List<File> folders, String exe, boolean isWin) {
        if (exe == null) {
            return null;
        }
        String[] names = isWin ? new String[] {exe + ".exe", exe + ".cmd", exe + ".bat", exe}
                               : new String[] {exe};
        for (File folder : folders) {
            for (String name : names) {
                File candidate = new File(folder, name);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static int compare(java.util.List<Integer> a, java.util.List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static void saveConfiguration(SettingsManager sm) {
        try {
            sm.save();
        } catch (Exception e) {
            AppLogger.error("Failed to persist environment changes: " + e.getMessage());
        }
    }
}