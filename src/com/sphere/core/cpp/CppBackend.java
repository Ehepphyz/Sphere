package com.sphere.core.cpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;
import com.sphere.utils.SecurityManager;
import com.sphere.core.Backend;

/**
 * Enhanced intelligent C++ execution engine.
 * Supports automated lifecycle orchestration, dynamic strategy command matching,
 * cross-platform abstraction (Windows, Linux, macOS, WSL), and volatile inline code evaluations.
 */
public class CppBackend implements Backend {
    private final Map<String, CppToolchain> toolchains = new HashMap<>();
    private final Map<String, CppCommand> systemCommands = new HashMap<>();
    private final CppCommandParser commandParser = new CppCommandParser();

    // Nothing used to feed the diagnostics engine: it was created, handed to the
    // console and read by two menu items that always found it empty.
    private CppDiagnosticsEngine diagnosticsEngine;
    private java.util.function.Consumer<java.io.File> diagnosticsListener;
    private final CppProcessRunner processRunner = new CppProcessRunner();
    
    // Step 1: Integrated Metrics Hub
    private final CppBackendMetrics metrics = new CppBackendMetrics();
    private final CppIntellisenseBackend intellisenseBackend;
    
    private CppToolchain activeToolchain;
    private Process currentProcess;
    private final SettingsManager settings;
    private CppFormatterEngine formatterEngine;

    /**
     * Registers one toolchain from the path settings.conf gives for it. Absent or
     * blank keys are skipped: a value left empty disables that backend, which is
     * what the file's own rules say.
     */
    private void registerToolchain(String name, String settingsKey,
                                   String executableName, boolean wsl) {
        String path = settings.resolveTool(settingsKey, executableName);
        if (path != null) {
            toolchains.put(name, new CppToolchain(name, path, executableName, wsl));
        }
    }

    public CppBackend() {
        this.settings = new SettingsManager();
        this.intellisenseBackend = new CppIntellisenseBackend();
        String os = System.getProperty("os.name").toLowerCase();

        // resolvePath dereferences the executable name, so passing null threw and
        // every lookup came back empty. Each key is asked for the binary it names.
        // settings.conf is the reference: it declares which toolchains exist
        // (CPP_TOOLCHAINS), which one is default (CPP_DEFAULT_TOOLCHAIN), and where
        // the binaries are, under [SYSTEM_PATH]. The six CPP_GCC style keys read
        // before are not in that file, so no toolchain was ever registered.
        registerToolchain("g++",     "GPP_DIR",     "g++",     false);
        registerToolchain("gcc",     "GCC_DIR",     "gcc",     false);
        registerToolchain("clang++", "CLANGPP_DIR", "clang++", false);
        registerToolchain("clang",   "CLANG_DIR",   "clang",   false);
        registerToolchain("msvc",    "CPP_MSVC_CL", "cl.exe",  false);
        registerToolchain("wsl-gcc", "CPP_WSL_GCC", "g++",     true);

        // A declared compiler path wins: it is the one the user pointed at.
        String declared = settings.resolveTool("CPP_COMPILER_PATH", null);
        String defaultToolchain = settings.getProperty("CPP_DEFAULT_TOOLCHAIN");
        if (defaultToolchain != null) {
            defaultToolchain = defaultToolchain.trim();
        }
        if (declared != null && defaultToolchain != null && !defaultToolchain.isEmpty()) {
            toolchains.put(defaultToolchain,
                new CppToolchain(defaultToolchain, declared, defaultToolchain, false));
        }

        if (defaultToolchain != null && toolchains.containsKey(defaultToolchain)) {
            activeToolchain = toolchains.get(defaultToolchain);
        } else if (!toolchains.isEmpty()) {
            activeToolchain = toolchains.values().iterator().next();
        }

        if (activeToolchain == null) {
            AppLogger.error("No C++ toolchain resolved. Set CPP_COMPILER_PATH or GPP_DIR "
                            + "in settings.conf.");
        }

        // Initialize Strategy Mappings
        systemCommands.put("version", new VersionCommand(processRunner));
        systemCommands.put("path", new PathCommand());
        systemCommands.put("build", new BuildCommand(processRunner));
        systemCommands.put("run", new RunCommand(processRunner));
        systemCommands.put("clean", new CleanCommand());
    }

    // Expose a snapshot method for your UI or Diagnostics systems
    public CppBackendMetrics.MetricsSnapshot getMetricsSnapshot() {
        return metrics.getSnapshot();
    }

    @Override
    public String getName() {
        return "CppBackend";
    }

    @Override
    public void execute(String args) {
        execute(args, false);
    }

    public void execute(String args, boolean logCommand) {
        CppProcessRunner.CppResult result = executeAndReturn(args, logCommand);
        if (result.timedOut()) {
            AppLogger.error("C++ pipeline timed out.");
        } else if (result.exitCode() != 0 && !result.getStderr().isEmpty()) {
            AppLogger.error("C++ pipeline failed with exit code " + result.exitCode());
        }
    }

    @Override
    public void activate() {
        /* Silent initialization entrypoint */
    }

    /** Engine that receives compiler findings. Optional. */
    public void setDiagnosticsEngine(CppDiagnosticsEngine engine) {
        this.diagnosticsEngine = engine;
    }

    public CppDiagnosticsEngine getDiagnosticsEngine() {
        return diagnosticsEngine;
    }

    /** Notified with the compiled source once its findings have been ingested. */
    public void setDiagnosticsListener(java.util.function.Consumer<java.io.File> listener) {
        this.diagnosticsListener = listener;
    }

    /**
     * Parses one compilation's stderr into the engine and announces the file.
     * Clearing first keeps a fixed error from lingering on screen.
     */
    private void publishDiagnostics(File source, String stderr) {
        if (diagnosticsEngine == null || source == null) {
            return;
        }
        String path = source.getAbsolutePath();
        diagnosticsEngine.clearFile(path);
        if (stderr != null && !stderr.isBlank()) {
            diagnosticsEngine.ingestCompilerStderr(path,
                java.util.Arrays.asList(stderr.split("\\R")));
        }
        if (diagnosticsListener != null) {
            diagnosticsListener.accept(source);
        }
    }

    public synchronized void cancelCurrentExecution() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
            AppLogger.info("C++ process pipeline forcibly cancelled by user.");
        }
    }

    public CppProcessRunner.CppResult executeAndReturn(String args, boolean logCommand) {
        return executeAndReturn(args, logCommand, null);
    }

    //System-wide context wiring hook
    public void initializeFormatter(com.sphere.utils.SettingsManager settings) {
        this.formatterEngine = new CppFormatterEngine(settings);
    }

    public String formatCode(String sourceCode) {
        if (this.formatterEngine == null) {
            return sourceCode;
        }
        // Unpack the formatted stdout string if successful; fallback to original code on failure
        com.sphere.core.cpp.CppFormatterEngine.FormatResult result = this.formatterEngine.formatCode(sourceCode);
        return result.isSuccess() ? result.getStdout() : sourceCode;
    }

    public boolean formatFile(Path file) {
        if (this.formatterEngine == null) {
            return false;
        }
        // Extract the plain boolean success flag from the rich result container
        com.sphere.core.cpp.CppFormatterEngine.FormatResult result = this.formatterEngine.formatFile(file);
        return result.isSuccess();
    }


    public CppProcessRunner.CppResult executeAndReturn(String args, boolean logCommand, CppOutputListener listener) {
        if (args == null) {
            return new CppProcessRunner.CppResult("", "Received null arguments.", -1, false);
        }

        String cleanArgs = args.replaceAll("^::?cpp\\s+", "").trim();

        if (!SecurityManager.isCommandSafe(cleanArgs)) {
            String errorMsg = "Security Violation: Host command contains forbidden instructions or unsafe system routines.";
            AppLogger.error(errorMsg);
            if (listener != null) {
                listener.onStderrLine(errorMsg);
                listener.onProcessComplete(-1, false);
            }
            return new CppProcessRunner.CppResult("", errorMsg, -1, false);
        }

        if (!cleanArgs.isEmpty()) {
            List<String> tokens = CppCommandParser.tokenize(cleanArgs);
            if (!tokens.isEmpty()) {
                String first = tokens.get(0);
                if (systemCommands.containsKey(first)) {
                    CppCommand cmd = systemCommands.get(first);
                    String[] cmdArgs = tokens.size() > 1 ? tokens.subList(1, tokens.size()).toArray(new String[0]) : new String[0];
                    
                    CommandExecutionResult result = cmd.execute(cmdArgs, activeToolchain, logCommand, listener);
                    if (listener != null) {
                        if (!result.getStdout().isEmpty()) listener.onStdoutLine(result.getStdout());
                        if (!result.getStderr().isEmpty()) listener.onStderrLine(result.getStderr());
                        listener.onProcessComplete(result.getExitCode(), false);
                    }
                    return new CppProcessRunner.CppResult(result.getStdout(), result.getStderr(), result.getExitCode(), false);
                }
            }
        }

        if (cleanArgs.startsWith("use ")) {
            String targetToolchain = cleanArgs.substring(4).trim();
            if (toolchains.containsKey(targetToolchain)) {
                activeToolchain = toolchains.get(targetToolchain);
                String msg = "Switched active C++ toolchain to: " + targetToolchain;
                AppLogger.info(msg);
                if (listener != null) {
                    listener.onStdoutLine(msg);
                    listener.onProcessComplete(0, false);
                }
                return new CppProcessRunner.CppResult(msg, "", 0, false);
            } else {
                String errorMsg = "Toolchain '" + targetToolchain + "' is not registered.";
                AppLogger.error(errorMsg);
                if (listener != null) {
                    listener.onStderrLine(errorMsg);
                    listener.onProcessComplete(-1, false);
                }
                return new CppProcessRunner.CppResult("", errorMsg, -1, false);
            }
        }

        if (cleanArgs.equals("status")) {
            String statusMsg = "Active Toolchain: " + (activeToolchain != null ? activeToolchain.getName() : "None available");
            if (listener != null) {
                listener.onStdoutLine(statusMsg);
                listener.onProcessComplete(0, false);
            }
            return new CppProcessRunner.CppResult(statusMsg, "", 0, false);
        }

        if (cleanArgs.startsWith("-c ") || cleanArgs.contains("#include") || cleanArgs.contains("main()")) {
            return handleInlineCodeEvaluation(cleanArgs, logCommand, listener);
        }

        return handleStandardSourcePipeline(cleanArgs, logCommand, listener);
    }

    private CppProcessRunner.CppResult handleInlineCodeEvaluation(String inputCode, boolean logCommand, CppOutputListener listener) {
        String codeBody = inputCode.startsWith("-c ") ? inputCode.substring(3).trim() : inputCode;
        
        if (!codeBody.contains("main(")) {
            codeBody = "#include <iostream>\n#include <vector>\n#include <string>\nint main() {\n" + codeBody + "\nreturn 0;\n}";
        }

        Path tempSourceFile = null;
        Path tempBinaryFile = null;
        try {
            tempSourceFile = Files.createTempFile("sphere_inline_", ".cpp");
            Files.writeString(tempSourceFile, codeBody, StandardCharsets.UTF_8);

            String os = System.getProperty("os.name").toLowerCase();
            boolean useWsl = activeToolchain.isWsl();
            boolean targetIsWindows = os.contains("win") && !useWsl;
            
            tempBinaryFile = Files.createTempFile("sphere_exec_", targetIsWindows ? ".exe" : ".out");
            Files.deleteIfExists(tempBinaryFile);

            List<String> compileCmd = new ArrayList<>();
            compileCmd.add(activeToolchain.getExecutable());
            
            String sourcePath = normalizePathForToolchain(tempSourceFile.toAbsolutePath().toString(), useWsl);
            String binaryPath = normalizePathForToolchain(tempBinaryFile.toAbsolutePath().toString(), useWsl);

            if ("msvc".equalsIgnoreCase(activeToolchain.getName())) {
                compileCmd.add(sourcePath);
                compileCmd.add("/Fe:" + binaryPath);
            } else {
                compileCmd.add(sourcePath);
                compileCmd.add("-o");
                compileCmd.add(binaryPath);
            }

            if (listener != null) listener.onStdoutLine("[Sphere Core] Compiling raw evaluation payload...");
            
            // Tagged as compilation step (true)
            CppProcessRunner.CppResult compResult = processRunner.run(compileCmd, logCommand, listener, activeToolchain, true);
            if (compResult.getExitCode() != 0) {
                return compResult;
            }

            if (listener != null) listener.onStdoutLine("[Sphere Core] Executing volatile binary target...");
            List<String> runCmd = List.of(binaryPath);
            
            // Tagged as runtime step (false)
            return processRunner.run(runCmd, logCommand, listener, activeToolchain, false);

        } catch (IOException e) {
            AppLogger.error("Failed to process inline C++ dynamic evaluation: " + e.getMessage());
            return new CppProcessRunner.CppResult("", e.getMessage(), -1, false);
        } finally {
            try {
                if (tempSourceFile != null) Files.deleteIfExists(tempSourceFile);
                if (tempBinaryFile != null) Files.deleteIfExists(tempBinaryFile);
            } catch (IOException ignored) {}
        }
    }

    /// Source extensions the auto-compile pipeline recognises. ".cpp" alone left
    /// ".cc", ".cxx" and the ".C" of ROOT macros falling through to the raw
    /// compiler invocation, which builds a.out and never runs it.
    private static final String[] CPP_SOURCE_EXTENSIONS =
        { ".cpp", ".cc", ".cxx", ".c++", ".C" };

    private static String sourceExtensionOf(String path) {
        if (path == null) return null;
        for (String ext : CPP_SOURCE_EXTENSIONS) {
            if (path.endsWith(ext)) return ext;
        }
        return null;
    }

    /**
     * Compiles one source file and runs the result. Flags reach the compiler,
     * arguments reach the produced binary.
     */
    public CppProcessRunner.CppResult executeSource(String source,
                                                    List<String> compileFlags,
                                                    List<String> runtimeArgs,
                                                    boolean logCommand,
                                                    CppOutputListener listener) {
        if (activeToolchain == null) {
            AppLogger.error("No valid active C++ toolchain configured.");
            return new CppProcessRunner.CppResult("", "No toolchain.", -1, false);
        }

        File sourceFile = new File(source);
        if (!sourceFile.isFile()) {
            AppLogger.error("C++ source not found: " + source);
            return new CppProcessRunner.CppResult("", "Source not found.", -1, false);
        }

        String extension = sourceExtensionOf(sourceFile.getName());
        if (extension == null) {
            AppLogger.error("Unrecognised C++ source extension: " + sourceFile.getName());
            return new CppProcessRunner.CppResult("", "Unrecognised extension.", -1, false);
        }

        final String os = System.getProperty("os.name").toLowerCase();
        final boolean useWsl = activeToolchain.isWsl();
        final boolean targetIsWindows = os.contains("win") && !useWsl;

        String baseName = sourceFile.getName();
        baseName = baseName.substring(0, baseName.length() - extension.length());
        File outputBinary = new File(sourceFile.getAbsoluteFile().getParentFile(),
                                     baseName + (targetIsWindows ? ".exe" : ""));

        String sourcePath = normalizePathForToolchain(sourceFile.getAbsolutePath(), useWsl);
        String binaryPath = normalizePathForToolchain(outputBinary.getAbsolutePath(), useWsl);

        List<String> compileCmd = new ArrayList<>();
        compileCmd.add(activeToolchain.getExecutable());
        if (compileFlags != null) {
            compileCmd.addAll(compileFlags);
        }
        compileCmd.add(sourcePath);
        if ("msvc".equalsIgnoreCase(activeToolchain.getName())) {
            compileCmd.add("/Fe:" + binaryPath);
        } else {
            compileCmd.add("-o");
            compileCmd.add(binaryPath);
        }

        if (listener != null) {
            listener.onStdoutLine("[Sphere Core] Auto-compiling targeting " + outputBinary.getName());
        }
        CppProcessRunner.CppResult compResult =
            processRunner.run(compileCmd, logCommand, listener, activeToolchain, true);
        publishDiagnostics(sourceFile, compResult.getStderr());
        if (compResult.getExitCode() != 0) {
            return compResult;
        }

        List<String> runCmd = new ArrayList<>();
        runCmd.add(binaryPath);
        if (runtimeArgs != null) {
            runCmd.addAll(runtimeArgs);
        }

        if (listener != null) {
            listener.onStdoutLine("[Sphere Core] Launching executable output...");
        }
        return processRunner.run(runCmd, logCommand, listener, activeToolchain, false);
    }

    private CppProcessRunner.CppResult handleStandardSourcePipeline(String cleanArgs, boolean logCommand, CppOutputListener listener) {
        ParsedCppCommand parsed = commandParser.parse(cleanArgs, activeToolchain);
        if (parsed == null) {
            return new CppProcessRunner.CppResult("", "Command parsing failed.", -1, false);
        }

        // Element 0 is the compiler the parser prepended; the rest is what was typed.
        List<String> rawTokens = parsed.getCommand();
        List<String> typed = rawTokens.subList(Math.min(1, rawTokens.size()), rawTokens.size());

        int sourceIndex = -1;
        for (int i = 0; i < typed.size(); i++) {
            if (sourceExtensionOf(typed.get(i)) != null && new File(typed.get(i)).isFile()) {
                sourceIndex = i;
                break;
            }
        }

        if (sourceIndex >= 0) {
            List<String> compileFlags = new ArrayList<>(typed.subList(0, sourceIndex));
            List<String> trailing = new ArrayList<>(typed.subList(sourceIndex + 1, typed.size()));
            compileFlags.addAll(trailing);
            return executeSource(typed.get(sourceIndex), compileFlags,
                                 java.util.Collections.emptyList(), logCommand, listener);
        }

        // Catch-all fallback guess: If the first command array parameter matches the compiler binary path, treat it as a build step
        boolean guessCompile = !parsed.getCommand().isEmpty() && parsed.getCommand().get(0).equals(activeToolchain.getExecutable());
        return processRunner.run(parsed.getCommand(), logCommand, listener, activeToolchain, guessCompile);
    }

    private String normalizePathForToolchain(String path, boolean isWsl) {
        if (path == null) return "";
        String forwardSlashes = path.replace("\\", "/");
        if (isWsl && forwardSlashes.matches("^[a-zA-Z]:.*")) {
            String drive = forwardSlashes.substring(0, 1).toLowerCase();
            return "/mnt/" + drive + forwardSlashes.substring(2);
        }
        return forwardSlashes;
    }

    public static final class CppToolchain {
        private final String name;
        private final String basePath;
        private final String executableName;
        private final boolean isWsl;

        CppToolchain(String name, String basePath, String executableName, boolean isWsl) {
            this.name = name;
            this.basePath = basePath;
            this.executableName = executableName;
            this.isWsl = isWsl;
        }

        String getExecutable() {
            if (basePath == null || basePath.isEmpty()) {
                return executableName;
            }
            // basePath may already be the binary itself: settings.resolvePath hands
            // back a full path, and appending the name again produced
            // "/usr/bin/g++/g++", which the launcher reported as "Not a directory".
            File base = new File(basePath);
            if (base.isFile()) {
                return base.getPath().replace("\\", "/");
            }
            return new File(basePath, executableName).getPath().replace("\\", "/");
        }

        String getName() { return name; }
        boolean isWsl() { return isWsl; }
    }

    private static final class CommandExecutionResult {
        private final String stdout;
        private final String stderr;
        private final int exitCode;

        CommandExecutionResult(String stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        String getStdout() { return stdout; }
        String getStderr() { return stderr; }
        int getExitCode() { return exitCode; }
    }

    private interface CppCommand {
        CommandExecutionResult execute(String[] args, CppToolchain toolchain, boolean logCommand, CppOutputListener listener);
    }

    private static final class VersionCommand implements CppCommand {
        private final CppProcessRunner runner;
        VersionCommand(CppProcessRunner runner) { this.runner = runner; }

        public CommandExecutionResult execute(String[] args, CppToolchain toolchain, boolean logCommand, CppOutputListener listener) {
            if (toolchain == null) return new CommandExecutionResult("", "No active toolchain.", -1);
            List<String> cmd = List.of(toolchain.getExecutable(), "--version");
            // Strategic version checks are technically compilation binary checks (true)
            CppProcessRunner.CppResult res = runner.run(cmd, logCommand, listener, toolchain, true);
            return new CommandExecutionResult(res.getStdout(), res.getStderr(), res.getExitCode());
        }
    }

    private static final class PathCommand implements CppCommand {
        public CommandExecutionResult execute(String[] args, CppToolchain toolchain, boolean logCommand, CppOutputListener listener) {
            if (toolchain == null) return new CommandExecutionResult("", "No active toolchain.", -1);
            return new CommandExecutionResult(toolchain.getExecutable(), "", 0);
        }
    }

    private static final class BuildCommand implements CppCommand {
        private final CppProcessRunner runner;
        BuildCommand(CppProcessRunner runner) { this.runner = runner; }

        public CommandExecutionResult execute(String[] args, CppToolchain toolchain, boolean logCommand, CppOutputListener listener) {
            if (toolchain == null) return new CommandExecutionResult("", "No active toolchain.", -1);
            if (args.length == 0) return new CommandExecutionResult("", "Build error: No target files specified.", -1);

            List<String> cmd = new ArrayList<>();
            cmd.add(toolchain.getExecutable());
            cmd.addAll(Arrays.asList(args));

            // Explicit build step (true)
            CppProcessRunner.CppResult res = runner.run(cmd, logCommand, listener, toolchain, true);
            return new CommandExecutionResult(res.getStdout(), res.getStderr(), res.getExitCode());
        }
    }

    private static final class RunCommand implements CppCommand {
        private final CppProcessRunner runner;
        RunCommand(CppProcessRunner runner) { this.runner = runner; }

        public CommandExecutionResult execute(String[] args, CppToolchain toolchain, boolean logCommand, CppOutputListener listener) {
            if (args.length == 0) return new CommandExecutionResult("", "Run error: No target executable provided.", -1);
            
            List<String> cmd = new ArrayList<>(Arrays.asList(args));
            // Explicit executable execution step (false)
            return new CommandExecutionResult("", "", runner.run(cmd, logCommand, listener, toolchain, false).getExitCode());
        }
    }

    private static final class CleanCommand implements CppCommand {
        public CommandExecutionResult execute(String[] args, CppToolchain toolchain, boolean logCommand, CppOutputListener listener) {
            File currentDir = new File(".");
            File[] targets = currentDir.listFiles((dir, name) -> name.endsWith(".exe") || name.endsWith(".out") || name.endsWith(".obj") || name.endsWith(".o"));
            
            if (targets == null || targets.length == 0) {
                return new CommandExecutionResult("Clean operational build success: Workspace directory clear.", "", 0);
            }

            int deletedCount = 0;
            for (File file : targets) {
                if (file.delete()) deletedCount++;
            }
            return new CommandExecutionResult("Clean pipeline removed " + deletedCount + " artifacts successfully.", "", 0);
        }
    }

    private static final class ParsedCppCommand {
        private final List<String> command;
        ParsedCppCommand(List<String> command) { this.command = command; }
        List<String> getCommand() { return command; }
    }

    private static final class CppCommandParser {
        ParsedCppCommand parse(String cleanArgs, CppToolchain toolchain) {
            if (toolchain == null) {
                AppLogger.error("No valid active C++ toolchain configured.");
                return null;
            }
            if (cleanArgs.isEmpty()) return null;

            List<String> tokens = tokenize(cleanArgs);
            if (tokens.isEmpty()) return null;

            List<String> command = new ArrayList<>();
            command.add(toolchain.getExecutable());
            command.addAll(tokens);

            return new ParsedCppCommand(command);
        }

        static List<String> tokenize(String input) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inDoubleQuotes = false;
            boolean inSingleQuotes = false;
            boolean escaping = false;

            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (escaping) {
                    current.append(c);
                    escaping = false;
                    continue;
                }
                if (c == '\\') {
                    escaping = true;
                    current.append(c);
                    continue;
                }
                if (c == '"' && !inSingleQuotes) {
                    inDoubleQuotes = !inDoubleQuotes;
                    current.append(c);
                    continue;
                }
                if (c == '\'' && !inDoubleQuotes) {
                    inSingleQuotes = !inSingleQuotes;
                    current.append(c);
                    continue;
                }
                if (Character.isWhitespace(c) && !inDoubleQuotes && !inSingleQuotes) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                tokens.add(current.toString());
            }
            return tokens;
        }
    }

    // ==========================================
    // UPDATED INTERNAL PROCESS RUNNER ENGINE
    // ==========================================
    private final class CppProcessRunner {
        private static final ExecutorService EXECUTOR = com.sphere.utils.ThreadUtils.createAdaptiveExecutor();
        private static final long TIMEOUT_SECONDS = 120L;

        public static final class CppResult {
            private final String stdout;
            private final String stderr;
            private final int exitCode;
            private final boolean timedOut;

            CppResult(String stdout, String stderr, int exitCode, boolean timedOut) {
                this.stdout = stdout;
                this.stderr = stderr;
                this.exitCode = exitCode;
                this.timedOut = timedOut;
            }

            public String getStdout() { return stdout; }
            public String getStderr() { return stderr; }
            public int exitCode() { return exitCode; }
            public int getExitCode() { return exitCode; }
            public boolean timedOut() { return timedOut; }
        }

        // Added 'boolean isCompileStep' flag to routing signature
        public CppResult run(List<String> command, boolean logCommand, CppOutputListener listener, CppToolchain toolchain, boolean isCompileStep) {
            if (command == null || command.isEmpty()) {
                return new CppResult("", "Empty payload command context.", -1, false);
            }

            List<String> executionCommand = command;
            if (toolchain != null && toolchain.isWsl()) {
                List<String> wrapped = new ArrayList<>();
                wrapped.add("wsl");
                for (String arg : command) {
                    if (arg.contains(":\\") || arg.contains(":/")) {
                        wrapped.add(normalizePathForToolchain(arg, true));
                    } else {
                        wrapped.add(arg);
                    }
                }
                executionCommand = wrapped;
            }

            if (logCommand) {
                AppLogger.info("Executing Pipeline Step: " + String.join(" ", executionCommand));
            }

            // High Precision Metric Tracking Setup
            long startTime = System.nanoTime();
            boolean errorOccurred = false;
            boolean timedOut = false;
            int exitCode = -1;

            Process process;
            try {
                ProcessBuilder pb = new ProcessBuilder(executionCommand);
                pb.redirectErrorStream(false);

                if (toolchain != null && "msvc".equalsIgnoreCase(toolchain.getName())) {
                    Map<String, String> env = pb.environment();
                    String msvcInclude = settings.resolvePath("CPP_MSVC_INCLUDE", null);
                    String msvcLib = settings.resolvePath("CPP_MSVC_LIB", null);
                    if (msvcInclude != null) env.put("INCLUDE", msvcInclude);
                    if (msvcLib != null) env.put("LIB", msvcLib);
                }

                synchronized (CppBackend.this) {
                    process = pb.start();
                    currentProcess = process;
                }
            } catch (IOException e) {
                synchronized (CppBackend.this) {
                    currentProcess = null;
                }
                if (listener != null) listener.onStderrLine(e.getMessage());
                
                // Telemetry Capture: Initial IO Process launching failures
                long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                if (isCompileStep) {
                    metrics.recordCompile(durationMillis, true);
                } else {
                    metrics.recordRun(durationMillis, true, false);
                }
                
                return new CppResult("", e.getMessage(), -1, false);
            }

            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            CompletableFuture<Void> stdoutTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String cleanLine = line.replace("\r", "");
                        stdoutBuilder.append(cleanLine).append(System.lineSeparator());
                        if (listener != null) {
                            listener.onStdoutLine(cleanLine);
                        } else {
                            AppLogger.raw(cleanLine);
                        }
                    }
                } catch (IOException e) {
                    AppLogger.error("Process stdout mapping leak: " + e.getMessage());
                }
            }, EXECUTOR);

            CompletableFuture<Void> stderrTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String cleanLine = line.replace("\r", "");
                        stderrBuilder.append(cleanLine).append(System.lineSeparator());
                        if (listener != null) {
                            listener.onStderrLine(cleanLine);
                        } else {
                            AppLogger.error(cleanLine);
                        }
                    }
                } catch (IOException e) {
                    AppLogger.error("Process stderr mapping leak: " + e.getMessage());
                }
            }, EXECUTOR);

            try {
                process.onExit().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
                exitCode = process.exitValue();
                errorOccurred = (exitCode != 0);
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof TimeoutException) {
                    timedOut = true;
                    errorOccurred = true;
                    process.destroyForcibly();
                }
            } finally {
                // Safeguard boundary: Clean up volatile state references smoothly
                synchronized (CppBackend.this) {
                    if (currentProcess == process) {
                        currentProcess = null;
                    }
                }

                // Complete background pipe drains
                CompletableFuture.allOf(stdoutTask, stderrTask).join();

                // Telemetry Injection: Update the metrics engine safely inside the execution cleanup step
                long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                if (isCompileStep) {
                    metrics.recordCompile(durationMillis, errorOccurred);
                } else {
                    metrics.recordRun(durationMillis, errorOccurred, timedOut);
                }
            }

            return new CppResult(stdoutBuilder.toString(), stderrBuilder.toString(), exitCode, timedOut);
        }
    }

    /**
     * Retrieves the persistent Intellisense LSP backend instance managed by this core service layer.
     * Prevents duplicate process allocations across disparate UI views
     */
    public CppIntellisenseBackend getIntellisenseBackend() {
        return this.intellisenseBackend;
    }

    /**
     * Exposes the underlying C++ code formatting engine instance
     */
    public CppFormatterEngine getFormatterEngine() {
        return this.formatterEngine;
    }

    /**
     * Executes file formatting with full telemetry result outputs
     */
    public com.sphere.core.cpp.CppFormatterEngine.FormatResult formatFileVerbose(java.nio.file.Path file, boolean dryRun) {
        if (this.formatterEngine == null) {
            return new com.sphere.core.cpp.CppFormatterEngine.FormatResult(
                false, "", "[Backend] Formatter engine is uninitialized.", -1
            );
        }
        return this.formatterEngine.formatFile(file, dryRun, false);
    }

    public interface CppOutputListener {
        void onStdoutLine(String line);
        void onStderrLine(String line);
        void onProcessComplete(int exitCode, boolean timedOut);
    }
}