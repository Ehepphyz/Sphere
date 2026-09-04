package com.sphere.core.cpp;

import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class CppFormatterEngine {

    /**
     * Immutable snapshot capturing the complete execution outputs from the formatting pipeline.
     */
    public static final class FormatResult {
        private final boolean success;
        private final String stdout;
        private final String stderr;
        private final int exitCode;

        public FormatResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        public boolean isSuccess() { return success; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public int getExitCode() { return exitCode; }
    }

    private final SettingsManager settings;
    private final String fallbackStyle;
    private final String explicitExecutablePath;

    public CppFormatterEngine(SettingsManager settings) {
        this.settings = settings;
        this.fallbackStyle = "LLVM";
        this.explicitExecutablePath = null;
    }

    public CppFormatterEngine(String clangFormatExecutable, String defaultStyle) {
        this.settings = null;
        this.explicitExecutablePath = (clangFormatExecutable == null || clangFormatExecutable.isBlank())
                ? null
                : clangFormatExecutable.trim();
        this.fallbackStyle = (defaultStyle == null || defaultStyle.isBlank()) ? "LLVM" : defaultStyle.trim();
    }

    private boolean isExecutableValid(String path) {
        if (path == null || path.isBlank()) return false;
        if (path.startsWith("/")) return true; // Pass through absolute WSL virtual environment targets safely
        File f = new File(path);
        return f.exists() && f.isFile() && f.canExecute();
    }

    private boolean isWslActive() {
        return CppToolchainDetector.detectOsType() == CppToolchainDetector.OsType.WSL;
    }

    /**
     * True when CLANG_FORMAT_EXEC is declared empty in [SYSTEM_PATH]: the user
     * switched formatting off. Without this the last resort below ran whatever
     * clang-format the PATH happened to hold, which is the opposite of the choice.
     */
    private boolean isDisabled() {
        return explicitExecutablePath == null && settings != null
               && settings.isDeclaredEmpty("CLANG_FORMAT_EXEC");
    }

    private static FormatResult disabledResult() {
        return new FormatResult(false, "",
            "[Formatter] CLANG_FORMAT_EXEC is empty in settings.conf, which turns "
            + "formatting off.", -1);
    }

    private String resolveExecutable() {
        if (isExecutableValid(explicitExecutablePath)) return explicitExecutablePath;
        if (settings != null) {
            String resolved = settings.resolveTool("CLANG_FORMAT_EXEC", "clang-format",
                                                   "CLANGPP_DIR", "CLANG_DIR");
            if (isExecutableValid(resolved)) return resolved;
        }
        return "clang-format";
    }

    private String resolveStyle() {
        if (settings == null) return fallbackStyle;
        String configured = settings.getProperty("cpp.format.style");
        return (configured != null && !configured.isBlank()) ? configured.trim() : fallbackStyle;
    }

    private String convertToWslPath(String windowsPath) {
        if (windowsPath == null || windowsPath.length() < 2 || windowsPath.charAt(1) != ':') {
            return windowsPath;
        }
        String drive = windowsPath.substring(0, 1).toLowerCase();
        String rest = windowsPath.substring(2).replace("\\", "/");
        return "/mnt/" + drive + rest;
    }

    private List<String> buildBaseCommand(String exe) {
        List<String> cmd = new ArrayList<>();
        if (isWslActive()) {
            cmd.add("wsl.exe");
            cmd.add("-e");
            cmd.add(exe.replace("\\", "/"));
        } else {
            cmd.add(exe);
        }
        // Two arguments, not one. "-style=file:X" tells clang-format to read the
        // style file named X, so the fallback was handed over as a file name and
        // every run died on "Error reading -fallback-style=LLVM".
        cmd.add("-style=file");
        cmd.add("-fallback-style=" + resolveStyle());
        return cmd;
    }

    public List<String> buildFormatCommand(File file, boolean dryRun) {
        String exe = resolveExecutable();
        List<String> cmd = buildBaseCommand(exe);

        if (dryRun) cmd.add("--dry-run");
        else cmd.add("-i");

        String targetPath = file.getAbsolutePath();
        cmd.add(isWslActive() ? convertToWslPath(targetPath) : targetPath);

        return cmd;
    }

    public FormatResult formatFile(Path file, boolean dryRun, boolean mergeStreams) {
        if (file == null || !Files.isRegularFile(file)) {
            return new FormatResult(false, "", "[Formatter] Invalid file reference.", -1);
        }
        if (isDisabled()) {
            return disabledResult();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(buildFormatCommand(file.toFile(), dryRun));
            // CRITICAL FIX: Always redirect error streams to avoid OS pipe deadlock traps.
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String outputLog;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                outputLog = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }

            int exitCode = process.waitFor();
            boolean success = exitCode == 0;

            if (!success && !outputLog.isBlank()) {
                AppLogger.error("[Formatter] File format error: " + outputLog);
            }

            // Honor the user's architectural selection flag safely on the return mapping object
            String finalStdout = success ? outputLog.trim() : (mergeStreams ? outputLog.trim() : "");
            String finalStderr = !success ? outputLog.trim() : "";

            return new FormatResult(success, finalStdout, finalStderr, exitCode);

        } catch (Exception e) {
            return new FormatResult(false, "", e.getMessage(), -1);
        }
    }

    public FormatResult formatCode(String code, boolean mergeStreams) {
        if (code == null || code.isEmpty()) {
            return new FormatResult(true, "", "", 0);
        }
        if (isDisabled()) {
            return disabledResult();
        }

        String exe = resolveExecutable();
        List<String> cmd = buildBaseCommand(exe);
        cmd.add("-"); // Bind to standard input buffer lines

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // CRITICAL FIX: Keeping streams combined to bypass OS block drops completely
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Push string source blocks upstream to stdin
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(code);
                writer.flush();
            }

            String outputLog;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                outputLog = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }

            int exitCode = process.waitFor();
            boolean success = exitCode == 0;

            if (!success && !outputLog.isBlank()) {
                AppLogger.error("[Formatter] Text tracking error: " + outputLog);
            }

            String finalStdout = success ? outputLog.trim() : (mergeStreams ? outputLog.trim() : "");
            String finalStderr = !success ? outputLog.trim() : "";

            return new FormatResult(success, finalStdout, finalStderr, exitCode);

        } catch (Exception e) {
            return new FormatResult(false, code, e.getMessage(), -1);
        }
    }

    /**
     * Backwards-compatible overload for code formatting.
     * Defaults to separating output and error streams.
     */
    public FormatResult formatCode(String code) {
        return formatCode(code, false);
    }

    /**
     * Backwards-compatible overload for file formatting.
     * Defaults to performing an active in-place rewrite (dryRun = false) 
     * and separating output streams (mergeStreams = false).
     */
    public FormatResult formatFile(Path file) {
        return formatFile(file, false, false);
    }
}