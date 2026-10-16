package com.sphere.core.cpp;

import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
            final Streams run = run(buildFormatCommand(file.toFile(), dryRun), null);
            final boolean success = run.exitCode == 0;

            if (!success && !run.stderr.isBlank()) {
                AppLogger.error("[Formatter] File format error: " + run.stderr.strip());
            }
            final String finalStdout = mergeStreams ? join(run.stdout, run.stderr) : run.stdout;
            return new FormatResult(success, finalStdout, run.stderr, run.exitCode);

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
            final Streams run = run(cmd, code);
            final boolean success = run.exitCode == 0;

            if (!success && !run.stderr.isBlank()) {
                AppLogger.error("[Formatter] Text tracking error: " + run.stderr.strip());
            } else if (!run.stderr.isBlank()) {
                // A warning (an unknown key in .clang-format, say) is reported,
                // never mixed into the code.
                AppLogger.info("[Formatter] " + run.stderr.strip());
            }
            if (!success) {
                return new FormatResult(false, code, run.stderr, run.exitCode);
            }
            final String finalStdout = mergeStreams ? join(run.stdout, run.stderr) : run.stdout;
            return new FormatResult(true, finalStdout, run.stderr, run.exitCode);

        } catch (Exception e) {
            return new FormatResult(false, code, e.getMessage(), -1);
        }
    }

    /** What a run of clang-format wrote on each stream, kept apart. */
    private record Streams(String stdout, String stderr, int exitCode) {
    }

    /**
     * Runs clang-format with its two output streams read separately.
     *
     * Both used to be merged into one (redirectErrorStream), and on success the
     * merged text was returned as the formatted code: any warning clang-format
     * printed -- an unknown key in .clang-format, a style it fell back from --
     * was written into the user's source. The result was also trimmed, which
     * took the final newline and the indentation of the first line with it.
     * stderr now goes to a file of its own, so neither stream can block the
     * other, and stdout comes back byte for byte.
     */
    private static Streams run(List<String> cmd, String input) throws IOException, InterruptedException {
        final File errors = File.createTempFile("sphere-clang-format-", ".err");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.to(errors));
            final Process process = pb.start();
            Thread feeder = null;
            if (input != null) {
                feeder = new Thread(() -> {
                    try (OutputStream in = process.getOutputStream()) {
                        in.write(input.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException closedEarly) {
                        // clang-format stopped reading; its exit code says why.
                    }
                }, "sphere-clang-format-stdin");
                feeder.setDaemon(true);
                feeder.start();
            } else {
                process.getOutputStream().close();
            }
            final byte[] out;
            try (InputStream stdout = process.getInputStream()) {
                out = stdout.readAllBytes();
            }
            final int exit = process.waitFor();
            if (feeder != null) {
                feeder.join(2000);
            }
            final String err = Files.readString(errors.toPath(), StandardCharsets.UTF_8);
            return new Streams(new String(out, StandardCharsets.UTF_8), err, exit);
        } finally {
            Files.deleteIfExists(errors.toPath());
        }
    }

    private static String join(String stdout, String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return stdout;
        }
        return stdout.endsWith("\n") || stdout.isEmpty() ? stdout + stderr : stdout + "\n" + stderr;
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