package com.sphere.core.python;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;
import com.sphere.utils.SecurityManager;
import com.sphere.core.python.PythonCommand;
import com.sphere.core.python.commands.*;
import com.sphere.core.Backend;

/**
 * Handles the execution of Python scripts and modules.
 * Integrates a command registry pattern to maintain scalability.
 */
public class PythonBackend implements Backend {
    private final String pythonExecutable;
    private final Map<String, PythonCommand> systemCommands = new HashMap<>();
    private final PythonCommandParser commandParser = new PythonCommandParser();
    private final PythonProcessRunner processRunner = new PythonProcessRunner();
    private final PythonVenvManager venvManager;
    
    // Track current running process for graceful/forced destruction from UI
    private Process currentProcess;

    public PythonBackend() {
        SettingsManager settings = new SettingsManager();
        String os = System.getProperty("os.name").toLowerCase();
        String executableName = os.contains("win") ? "python.exe" : "python";

        String resolvedPath = settings.resolvePath("PYTHON_EXEC", executableName);
        this.pythonExecutable = (resolvedPath != null) ? resolvedPath : executableName;

        this.venvManager = new PythonVenvManager(this.pythonExecutable, settings);
        this.venvManager.reload();
        
        // Register system commands
        systemCommands.put("freeze", new FreezeCommand(getEffectivePythonExecutable()));
        systemCommands.put("version", new VersionCommand(getEffectivePythonExecutable()));
        systemCommands.put("check", new CheckCommand(getEffectivePythonExecutable()));
        systemCommands.put("clean", new CleanCommand());
        systemCommands.put("reinstall", new ReinstallCommand(getEffectivePythonExecutable()));
        systemCommands.put("path", new PathComponentCommand(getEffectivePythonExecutable()));
    }

    private String getEffectivePythonExecutable() {
        return venvManager.getPythonExecutable();
    }

    /**
     * Forcibly terminates the running python sub-process (e.g. from a UI Stop button).
     */
    public synchronized void cancelCurrentExecution() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
            AppLogger.info("Python process execution forcibly cancelled by user.");
        }
    }

    public void execute(String args, boolean logCommand) {
        PythonProcessRunner.PythonResult result = executeAndReturn(args, logCommand);
        // Fallback logging behavior for the fire-and-forget implementation
        if (result.timedOut()) {
            AppLogger.error("Python command timed out.");
        } else if (result.exitCode() != 0 && !result.stderr().isEmpty()) {
            AppLogger.error("Python command failed with exit code " + result.exitCode());
        }
    }

    @Override
    public String getName() {
        return "PythonBackend";
    }

    @Override
    public void execute(String args) {
        execute(args, false);
    }

    @Override
    public void activate() {
        /* Mode activation remains silent */
    }

    private static final class ParsedCommand {
        private final List<String> command;
        private final PythonCommand systemCommand;
        private final String[] systemArgs;

        ParsedCommand(List<String> command) {
            this.command = command;
            this.systemCommand = null;
            this.systemArgs = null;
        }

        ParsedCommand(PythonCommand systemCommand, String[] systemArgs) {
            this.command = null;
            this.systemCommand = systemCommand;
            this.systemArgs = systemArgs;
        }

        boolean isSystemCommand() { return systemCommand != null; }
        List<String> getCommand() { return command; }
        PythonCommand getSystemCommand() { return systemCommand; }
        String[] getSystemArgs() { return systemArgs; }
    }

    private static final class PythonCommandParser {

        ParsedCommand parse(String args, String pythonExecutable, Map<String, PythonCommand> systemCommands) {
            if (args == null) {
                AppLogger.error("Received null arguments for execution.");
                return null;
            }

            String cleanArgs = args.replaceAll("^::?py\\s+", "").trim();
            if (cleanArgs.isEmpty()) {
                AppLogger.error("Command arguments are empty after sanitization.");
                return null;
            }

            List<String> tokens = tokenize(cleanArgs);
            if (tokens.isEmpty()) {
                AppLogger.error("No tokens found in command arguments.");
                return null;
            }

            String firstToken = tokens.get(0);
            if (systemCommands.containsKey(firstToken)) {
                PythonCommand cmd = systemCommands.get(firstToken);
                String[] cmdArgs = tokens.subList(1, tokens.size()).toArray(new String[0]);
                return new ParsedCommand(cmd, cmdArgs);
            }

            List<String> command = new ArrayList<>();
            command.add(pythonExecutable);
            
            // Scan tokens to support flags/options mixed before or after keywords
            int cIndex = tokens.indexOf("-c");
            int mIndex = tokens.indexOf("-m");

            if (mIndex != -1 && tokens.size() > mIndex + 1) {
                String module = tokens.get(mIndex + 1);
                if (!SecurityManager.isModuleAllowed(module)) {
                    AppLogger.error("Security: Module '" + module + "' is not authorized.");
                    return null;
                }
                command.addAll(tokens);
            } else if (cIndex != -1 && tokens.size() > cIndex + 1) {
                InlineCode inline = InlineCodeParser.parse(cleanArgs);
                String code = inline.code;
                if (!SecurityManager.isCommandSafe(code)) {
                    AppLogger.error("Security: Direct code execution blocked or failed safety validation.");
                    return null;
                }
                
                // Reconstruct the system arguments layout explicitly using parsed parts
                command.add("-c");
                command.add(inline.code);
                if (!inline.args.isEmpty()) {
                    command.addAll(tokenize(inline.args));
                }
            } else if (firstToken.endsWith(".py") || (firstToken.startsWith("\"") && firstToken.endsWith(".py\"")) || (firstToken.startsWith("'") && firstToken.endsWith(".py'"))) {
                String cleanPath = firstToken.replaceAll("^[\"']|[\"']$", "");
                File scriptFile = new File(cleanPath);
                if (!scriptFile.exists() || !scriptFile.isFile()) {
                    AppLogger.error("Error: Python script not found -> " + scriptFile.getAbsolutePath());
                    return null;
                }
                if (!SecurityManager.isModuleAllowed(scriptFile.getAbsolutePath())) {
                    AppLogger.error("Security: Script '" + scriptFile.getAbsolutePath() + "' is not authorized.");
                    return null;
                }
                command.addAll(tokens);
            } else {
                if (!firstToken.startsWith("-") && SecurityManager.isModuleAllowed(firstToken)) {
                    command.add("-m");
                }
                command.addAll(tokens);
            }

            return new ParsedCommand(command);
        }

        private static List<String> tokenize(String input) {
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

        private static final class InlineCode {
            final String code;
            final String args;
            InlineCode(String code, String args) {
                this.code = code;
                this.args = args;
            }
        }

        private static final class InlineCodeParser {
            static InlineCode parse(String cleanArgs) {
                int idx = cleanArgs.indexOf("-c");
                if (idx == -1) {
                    return new InlineCode("", "");
                }
                int pos = idx + 2;
                while (pos < cleanArgs.length() && Character.isWhitespace(cleanArgs.charAt(pos))) {
                    pos++;
                }
                StringBuilder code = new StringBuilder();
                StringBuilder args = new StringBuilder();
                boolean inDoubleQuotes = false;
                boolean inSingleQuotes = false;
                boolean escaping = false;
                boolean inCode = true;

                for (int i = pos; i < cleanArgs.length(); i++) {
                    char c = cleanArgs.charAt(i);
                    if (escaping) {
                        if (inCode) {
                            code.append(c);
                        } else {
                            args.append(c);
                        }
                        escaping = false;
                        continue;
                    }
                    if (c == '\\') {
                        escaping = true;
                        if (inCode) {
                            code.append(c);
                        } else {
                            args.append(c);
                        }
                        continue;
                    }
                    if (c == '"' && !inSingleQuotes) {
                        if (inCode) {
                            inDoubleQuotes = !inDoubleQuotes;
                            code.append(c);
                        } else {
                            args.append(c);
                        }
                        continue;
                    }
                    if (c == '\'' && !inDoubleQuotes) {
                        if (inCode) {
                            inSingleQuotes = !inSingleQuotes;
                            code.append(c);
                        } else {
                            args.append(c);
                        }
                        continue;
                    }
                    if (Character.isWhitespace(c) && !inDoubleQuotes && !inSingleQuotes) {
                        if (inCode && code.length() > 0) {
                            inCode = false;
                            continue;
                        }
                        if (!inCode) {
                            args.append(c);
                        }
                    } else {
                        if (inCode) {
                            code.append(c);
                        } else {
                            args.append(c);
                        }
                    }
                }
                return new InlineCode(code.toString().trim(), args.toString().trim());
            }
        }
    }

    private final class PythonProcessRunner {
        // Replace the old rigid line with this adaptive initialization:
        private static final ExecutorService EXECUTOR = com.sphere.utils.ThreadUtils.createAdaptiveExecutor();
        private static final long TIMEOUT_SECONDS = 60L;

        public static final class PythonResult {
            private final String stdout;
            private final String stderr;
            private final int exitCode;
            private final boolean timedOut;

            PythonResult(String stdout, String stderr, int exitCode, boolean timedOut) {
                this.stdout = stdout;
                this.stderr = stderr;
                this.exitCode = exitCode;
                this.timedOut = timedOut;
            }

            public String stdout() { return stdout; }
            public String stderr() { return stderr; }
            public int exitCode() { return exitCode; }
            public boolean timedOut() { return timedOut; }

            public String toSwingHtmlText() {
                StringBuilder html = new StringBuilder("<html><body>");
                if (!stdout.isEmpty()) {
                    html.append("<h3>Output:</h3><pre style='color:green;'>")
                        .append(escapeHtml(stdout).replace("\n", "<br>")).append("</pre>");
                }
                if (!stderr.isEmpty()) {
                    html.append("<h3>Errors:</h3><pre style='color:red;'>")
                        .append(escapeHtml(stderr).replace("\n", "<br>")).append("</pre>");
                }
                if (timedOut) {
                    html.append("<p style='color:orange;'><b>Execution status: Process Timed Out</b></p>");
                } else {
                    html.append("<p><b>Exit Code:</b> ").append(exitCode).append("</p>");
                }
                html.append("</body></html>");
                return html.toString();
            }

            private static String escapeHtml(String s) {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '<': out.append("&lt;"); break;
                        case '>': out.append("&gt;"); break;
                        case '&': out.append("&amp;"); break;
                        case '"': out.append("&quot;"); break;
                        case '\'': out.append("&#39;"); break;
                        default: out.append(c);
                    }
                }
                return out.toString();
            }
        }

        public PythonResult run(List<String> command, boolean logCommand, PythonOutputListener listener) {
            if (command == null || command.isEmpty()) {
                AppLogger.error("No command to execute.");
                return new PythonResult("", "", -1, false);
            }

            if (logCommand) {
                AppLogger.info("Executing: " + String.join(" ", command));
            }

            Process process;
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);
                synchronized (PythonBackend.this) {
                    process = pb.start();
                    currentProcess = process; // Captures the active reference for UI cancellation
                }
            } catch (IOException e) {
                synchronized (PythonBackend.this) {
                    currentProcess = null;
                }
                AppLogger.error("Failed to execute Python command: " + e.getMessage());
                if (listener != null) listener.onStderrLine(e.getMessage());
                return new PythonResult("", e.getMessage(), -1, false);
            }

            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            // Read the process standard output stream asynchronously using virtual threads
            CompletableFuture<Void> stdoutTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String cleanLine = line.replace("\r", "");
                        stdoutBuilder.append(cleanLine).append(System.lineSeparator());
                        
                        // Harmonization: Route stream exclusively to UI listener if available to avoid duplication
                        if (listener != null) {
                            listener.onStdoutLine(cleanLine);
                        } else {
                            String upper = cleanLine.toUpperCase();
                            if (upper.contains("ERROR") || upper.contains("TRACEBACK")) {
                                AppLogger.error(cleanLine);
                            } else {
                                AppLogger.raw(cleanLine);
                            }
                        }
                    }
                } catch (IOException e) {
                    AppLogger.error("Error reading process stdout: " + e.getMessage());
                }
            }, EXECUTOR);

            // Read the process error stream asynchronously using virtual threads
            CompletableFuture<Void> stderrTask = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String cleanLine = line.replace("\r", "");
                        stderrBuilder.append(cleanLine).append(System.lineSeparator());
                        
                        // Harmonization: Route stream exclusively to UI listener if available to avoid duplication
                        if (listener != null) {
                            listener.onStderrLine(cleanLine);
                        } else {
                            AppLogger.error(cleanLine);
                        }
                    }
                } catch (IOException e) {
                    AppLogger.error("Error reading process stderr: " + e.getMessage());
                }
            }, EXECUTOR);

            boolean timedOut = false;
            int exitCode = -1;

            try {
                process.onExit().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
                exitCode = process.exitValue();
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof TimeoutException) {
                    timedOut = true;
                    process.destroyForcibly();
                    AppLogger.error("Python process execution timed out.");
                }
            }

            // Ensure background tasks finish processing remaining text lines
            CompletableFuture.allOf(stdoutTask, stderrTask).join();
            
            if (listener != null) {
                listener.onProcessComplete(exitCode, timedOut);
            }

            synchronized (PythonBackend.this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }

            return new PythonResult(stdoutBuilder.toString(), stderrBuilder.toString(), exitCode, timedOut);
        }
    }

    public PythonProcessRunner.PythonResult executeAndReturn(String args, boolean logCommand) {
        return executeAndReturn(args, logCommand, null);
    }

    public PythonProcessRunner.PythonResult executeAndReturn(String args, boolean logCommand, PythonOutputListener listener) {
        ParsedCommand parsed = commandParser.parse(args, getEffectivePythonExecutable(), systemCommands);
        if (parsed == null) {
            return new PythonProcessRunner.PythonResult("", "Command parsing failed.", -1, false);
        }

        if (parsed.isSystemCommand()) {
            PythonCommand cmd = parsed.getSystemCommand();
            String[] cmdArgs = parsed.getSystemArgs();
            
            // Harmonization: Notify the listener about internal system command cycles
            if (listener != null) {
                listener.onStdoutLine("Executing system command: " + args.trim());
            }

            if (cmdArgs != null && cmdArgs.length > 0 && cmd instanceof PythonCommandWithArgs) {
                ((PythonCommandWithArgs) cmd).execute(cmdArgs);
            } else {
                cmd.execute();
            }
            
            if (listener != null) {
                listener.onProcessComplete(0, false);
            }
            
            return new PythonProcessRunner.PythonResult("System command executed.", "", 0, false);
        } else {
            return processRunner.run(parsed.getCommand(), logCommand, listener);
        }
    }

    public interface PythonOutputListener {
        void onStdoutLine(String line);
        void onStderrLine(String line);
        void onProcessComplete(int exitCode, boolean timedOut);
    }

    public interface PythonCommandWithArgs extends PythonCommand {
        void execute(String[] args);
    }

    private static final class PythonVenvManager {
        private final String systemPythonExecutable;
        private final SettingsManager settingsManager;
        private String activeVenvPath;
        private String activeVenvPythonExecutable;

        PythonVenvManager(String systemPythonExecutable, SettingsManager settingsManager) {
            this.systemPythonExecutable = systemPythonExecutable;
            this.settingsManager = settingsManager;
        }

        void reload() {
            String venvPath = settingsManager.resolvePath("PYTHON_VENV", null);
            activeVenvPath = venvPath;
            if (venvPath != null) {
                String os = System.getProperty("os.name").toLowerCase();
                File venvPython = new File(venvPath, os.contains("win") ? "Scripts/python.exe" : "bin/python");
                activeVenvPythonExecutable = venvPython.exists() ? venvPython.getAbsolutePath() : null;
            } else {
                activeVenvPythonExecutable = null;
            }
        }

        String getPythonExecutable() {
            if (activeVenvPythonExecutable != null) {
                return activeVenvPythonExecutable;
            }
            return systemPythonExecutable;
        }

        String getActiveVenvPath() { return activeVenvPath; }
        boolean isVenvActive() { return activeVenvPythonExecutable != null; }
    }
}