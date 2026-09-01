package com.sphere.core.commands;

import com.sphere.utils.AppLogger;
import javax.swing.SwingUtilities;
import java.util.Optional;

/**
 * Handles core command logic for the Sphere platform.
 * Logic is delegated to domain-specific services or bridge handlers.
 */
public class Handlers {

    // --- Core Commands ---
    public static void help(String input, CommandExecutionContext c) {
        AppLogger.info("Available commands:");
        CommandDefinitions.all().values().forEach(cmd ->
            AppLogger.raw("  " + cmd.name + " — " + cmd.description)
        );
    }

    public static void version(String input, CommandExecutionContext c) {
        AppLogger.info("Sphere version 2026.1.0.0");
    }

    public static void quit(String input, CommandExecutionContext c) {
        AppLogger.info("Shutting down Sphere...");
        System.exit(0);
    }

    public static void editNfile(String input, CommandExecutionContext c) {
        AppLogger.info("[edit] Edit file execution (placeholder)");
    }

    public static void createNew(String input, CommandExecutionContext c) {
        AppLogger.info("[create] Create new item execution (placeholder)");
    }

    // --- Project Commands ---
    public static void projectNew(String input, CommandExecutionContext c) {
        AppLogger.info("[project] Create new project (placeholder)");
    }

    public static void projectOpen(String input, CommandExecutionContext c) {
        AppLogger.info("[project] Open existing project (placeholder)");
    }

    public static void projectClose(String input, CommandExecutionContext c) {
        AppLogger.info("[project] Close current project (placeholder)");
    }

    public static void projectSet(String input, CommandExecutionContext c) {
        AppLogger.info("[project] Set active project (placeholder)");
    }

    public static void projectInfo(String input, CommandExecutionContext c) {
        AppLogger.info("[project] Display current project info (placeholder)");
    }

    public static void projectList(String input, CommandExecutionContext c) {
        AppLogger.info("[project] List available projects (placeholder)");
    }

    public static void projectDelete(String input, CommandExecutionContext c) {
        AppLogger.info("[project] Delete project (placeholder)");
    }

    // --- Workspace Commands ---
    public static void workspaceScan(String input, CommandExecutionContext c) {
        AppLogger.info("[workspace] Scan workspace for projects (placeholder)");
    }

    public static void workspaceClean(String input, CommandExecutionContext c) {
        AppLogger.info("[workspace] Clean temporary files (placeholder)");
    }

    public static void workspaceDiag(String input, CommandExecutionContext c) {
        AppLogger.info("[workspace] Run workspace diagnostics (placeholder)");
    }

    // --- Environment Commands ---
    public static void envList(String input, CommandExecutionContext c) {
        AppLogger.info("[env] List available environments (placeholder)");
    }

    public static void envActivate(String input, CommandExecutionContext c) {
        AppLogger.info("[env] Activate environment (placeholder)");
    }

    public static void envDeactivate(String input, CommandExecutionContext c) {
        AppLogger.info("[env] Deactivate current environment (placeholder)");
    }

    public static void envInfo(String input, CommandExecutionContext c) {
        AppLogger.info("[env] Display current environment info (placeholder)");
    }

    // --- Backend Commands ---
    public static void backendList(String input, CommandExecutionContext c) {
        AppLogger.info("[backend] List available backends (placeholder)");
    }

    public static void backendDiag(String input, CommandExecutionContext c) {
        AppLogger.info("[backend] Run backend diagnostics (placeholder)");
    }

    public static void backendReload(String input, CommandExecutionContext c) {
        AppLogger.info("[backend] Reload backend configurations (placeholder)");
    }

    // --- Configuration Commands ---
    public static void configShow(String input, CommandExecutionContext c) {
        AppLogger.info("[config] Display configurations (placeholder)");
    }

    public static void configEdit(String input, CommandExecutionContext c) {
        AppLogger.info("[config] Edit configurations (placeholder)");
    }

    public static void configReset(String input, CommandExecutionContext c) {
        AppLogger.info("[config] Reset configurations to defaults (placeholder)");
    }

    // --- Logging Commands ---
    public static void logLevel(String input, CommandExecutionContext c) {
        AppLogger.info("[log] Set logging severity level (placeholder)");
    }

    public static void logTail(String input, CommandExecutionContext c) {
        AppLogger.info("[log] Tail live log output (placeholder)");
    }

    public static void logClear(String input, CommandExecutionContext c) {
        AppLogger.info("[log] Clear target log files (placeholder)");
    }

    public static void clearConsole(String input, CommandExecutionContext ctx) {
        AppLogger.clear();
    }

    /**
     * Universal supervisor task termination routine.
     * Intercepts and terminates any OS-level process cleanly by PID across Linux, macOS, and Windows.
     */
    public static void terminateProcess(String input, CommandExecutionContext ctx) {
        String target = input.replaceFirst("^:kill", "").trim();

        if (target.isEmpty()) {
            AppLogger.warn("Task termination aborted: Missing target PID. Usage: :kill <PID>");
            return;
        }

        try {
            long pid = Long.parseLong(target);
            Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);

            if (processHandle.isPresent()) {
                ProcessHandle ph = processHandle.get();
                String processName = ph.info().command().orElse("Unknown Process");
                
                AppLogger.info("Sending termination signal to PID " + pid + " (" + processName + ")...");
                
                boolean success = ph.destroyForcibly(); 
                
                if (success) {
                    AppLogger.success("Process [PID: " + pid + "] was successfully terminated.");
                } else {
                    AppLogger.error("OS Level Denial: Failed to terminate process [PID: " + pid + "]. Check execution permissions.");
                }
            } else {
                AppLogger.info("System supervisor scan completed: No active process found with PID '" + pid + "'.");
            }
        } catch (NumberFormatException e) {
            AppLogger.warn("Invalid argument: ':kill' expects a numeric Process ID (PID). Example: :kill 1234");
        }
    }

    /**
     * Diagnostic tracking routine. Queries the universal JVM process handle factory 
     * to list active running tasks across Linux, macOS, and Windows seamlessly.
     */
    public static void listActiveTasks(String input, CommandExecutionContext ctx) {
        AppLogger.info("Querying host supervisor for active process contexts...");
        
        AppLogger.separator();
        AppLogger.raw(String.format("%-10s %-45s %-15s", "PID", "COMMAND / IMAGENAME", "USER"));
        AppLogger.separator();

        ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .limit(30)
            .forEach(ph -> {
                long pid = ph.pid();
                ProcessHandle.Info info = ph.info();
                
                String cmdPath = info.command().orElse("[System Task / Shell Window]");
                String user = info.user().orElse("unknown");
                
                String cleanCmd = cmdPath.substring(cmdPath.lastIndexOf(java.io.File.separator) + 1);

                AppLogger.raw(String.format("%-10d %-45s %-15s", pid, cleanCmd, user));
            });

        AppLogger.separator();
    }

    // --- Python Engine ---
    public static void pySettings(String input, CommandExecutionContext c) {
        SwingUtilities.invokeLater(() -> {
            com.sphere.ui.PyEnvManagerDialog dlg = new com.sphere.ui.PyEnvManagerDialog();
            dlg.setDefaultCloseOperation(javax.swing.JDialog.DISPOSE_ON_CLOSE);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
            dlg.setVisible(true);
        });
    }

    public static void pyMode(String input, CommandExecutionContext c) { 
        String clean = (input != null) ? input.trim() : "";
        if (clean.equals(":py mode")) {
            switchMode(c, "py", "[py]"); 
        } else {
            AppLogger.info("[py] Executing explicit standalone script or statement...");
        }
    }

    public static void pyExit(String input, CommandExecutionContext c) { 
        switchMode(c, null, ""); 
    }

    public static void pyDiag(String input, CommandExecutionContext c) { 
        AppLogger.info("[py] Running diagnostics (placeholder)"); 
    }

    public static void pyVars(String input, CommandExecutionContext c) { 
        AppLogger.info("[py] Discovered environment state variables (placeholder)"); 
    }

    // --- C++ Engine ---
    public static void cppMode(String input, CommandExecutionContext c) { 
        String clean = (input != null) ? input.trim() : "";
        if (clean.equals(":cpp mode")) {
            switchMode(c, "cpp", "[cpp]"); 
        } else {
            AppLogger.info("[cpp] Evaluating contextual macro or raw direct implementation code...");
        }
    }

    public static void cppExit(String input, CommandExecutionContext c) { 
        switchMode(c, null, ""); 
    }

    public static void cppVars(String input, CommandExecutionContext c) { 
        AppLogger.info("[cpp] Inspecting memory structure definitions (placeholder)"); 
    }

    public static void cppDiag(String input, CommandExecutionContext c) { 
        AppLogger.info("[cpp] Running environment toolchain diagnostic check (placeholder)"); 
    }

    // --- JS Engine ---
    public static void jsMode(String input, CommandExecutionContext c) { 
        String clean = (input != null) ? input.trim() : "";
        if (clean.equals(":js mode")) {
            switchMode(c, "js", "[js]"); 
        } else {
            AppLogger.info("[js] Evaluating targeted runtime source code line...");
        }
    }

    public static void jsExit(String input, CommandExecutionContext c) { 
        switchMode(c, null, ""); 
    }

    public static void jsEnv(String input, CommandExecutionContext c) { 
        AppLogger.info("[js] Dumping engine configuration parameters (placeholder)"); 
    }

    public static void jsDiag(String input, CommandExecutionContext c) { 
        AppLogger.info("[js] Checking active ECMAScript interpreter states (placeholder)"); 
    }

    // --- Snippet & Tool Commands ---
    public static void snippetList(String input, CommandExecutionContext c) { 
        AppLogger.info("[snippet] Listing indexed workspace code snippets (placeholder)"); 
    }

    public static void snippetInfo(String input, CommandExecutionContext c) { 
        AppLogger.info("[snippet] Displaying target metadata definitions (placeholder)"); 
    }

    public static void snippetReload(String input, CommandExecutionContext c) { 
        AppLogger.info("[snippet] Performing hot reload on dynamic registers (placeholder)"); 
    }

    public static void toolsDiag(String input, CommandExecutionContext c) { 
        AppLogger.info("[tools] Auditing local platform dependencies (placeholder)"); 
    }

    public static void toolsList(String input, CommandExecutionContext c) { 
        AppLogger.info("[tools] Listing valid compiled binary endpoints (placeholder)"); 
    }

    public static void toolsUpdate(String input, CommandExecutionContext c) { 
        AppLogger.info("[tools] Refreshing dependency metadata versions (placeholder)"); 
    }

    private static void switchMode(CommandExecutionContext c, String mode, String indicator) {
        if (c != null && c.ctx != null) {
            c.ctx.currentMode = mode;
            if (c.ctx.modeUpdater != null) {
                c.ctx.modeUpdater.accept(mode == null ? "" : indicator);
            }
        }
        
        String targetIndicator = (mode == null) ? "" : indicator;
        try {
            com.sphere.Sphere.assignGlobalIndicator(targetIndicator);
        } catch (Throwable t) {
            // Graceful fallback safeguard
        }

        AppLogger.info(mode == null ? "Exited persistent language shell state." : "Entered " + mode + " persistent execution mode.");
    }

    public static void echoCommand(String input, CommandExecutionContext ctx) {
        String args = input.replaceFirst("^:echo", "").trim();

        if (args.isEmpty()) {
            AppLogger.raw(""); 
            return;
        }

        if ("all".equalsIgnoreCase(args)) {
            AppLogger.info("--- Sphere Config Registry Cache ---");
            System.getenv().keySet().stream().sorted().forEach(key -> 
                AppLogger.raw("  Env  -> " + key)
            );
            return;
        }

        if (args.startsWith("$")) {
            String varName = args.substring(1).trim().toUpperCase();
            String varValue = com.sphere.utils.EngineConfigRegistry.get(varName);
            
            if (varValue.isEmpty()) {
                String osFallback = System.getenv(varName);
                if (osFallback != null) {
                    AppLogger.raw(osFallback);
                    return;
                }
                AppLogger.info("$" + varName + " exists, but it is empty (null).");
            } else {
                AppLogger.raw(varValue);
            }
        } else {
            AppLogger.raw(args);
        }
    }

    public static void setCommand(String input, CommandExecutionContext ctx) {
        String args = input.replaceFirst("^:set", "").trim();

        if (args.isEmpty()) {
            AppLogger.warn("Variable assignment aborted: Missing parameters.");
            return;
        }

        String varName;
        String varValue;

        if (args.contains("=")) {
            int splitIdx = args.indexOf('=');
            varName = args.substring(0, splitIdx).trim().toUpperCase();
            varValue = args.substring(splitIdx + 1).trim();
        } else {
            String[] tokens = args.split("\\s+", 2);
            varName = tokens[0].trim().toUpperCase();
            varValue = (tokens.length > 1) ? tokens[1].trim() : "";
        }

        if (varName.startsWith("$")) {
            varName = varName.substring(1);
        }

        System.setProperty(varName, varValue); 
        AppLogger.success("Session environment updated: $" + varName + " -> " + varValue);
    }

    // =========================================================================
    // --- ROOT BRIDGE EXECUTION ROUTINES ---
    // =========================================================================


    // --- ROOT Framework Engine ---
    // --- ROOT bridge plumbing ---

    private static final long TIMEOUT_MS = 5000L;

    private static com.sphere.core.rootbackend.RootBackend backend(CommandExecutionContext c) {
        if (c == null || c.ctx == null || c.ctx.router == null) {
            AppLogger.error("Command execution context is lost or missing router driver configuration.");
            return null;
        }
        Object o = c.ctx.router.getRootBackend();
        if (o instanceof com.sphere.core.rootbackend.RootBackend b) {
            return b;
        }
        AppLogger.error("ROOT backend core component is uninitialized or type-mismatched.");
        return null;
    }

    /**
     * Entry point used by InternalDispatcher. A "CLING_EXEC " prefix marks C++ code
     * the caller already resolved; anything else is first looked up among the
     * registered :root commands, and only then handed to the interpreter.
     */
    public static void sendToRootBridge(String command, CommandExecutionContext context) {
        if (command == null || command.isBlank()) {
            return;
        }
        String text = command.trim();
        if (text.regionMatches(true, 0, "CLING_EXEC ", 0, 11)) {
            cling(context, text.substring(11).trim());
            return;
        }
        String full = text.startsWith(":root") ? text : ":root " + text;
        CommandDefinitions.CommandInfo info = CommandDefinitions.find(full);
        if (info != null) {
            info.handler.accept(full, context);
            return;
        }
        // The interpreter decides whether this is C++. A character scan here would
        // reject `:root h1`, which is how one inspects an object in ROOT.
        final String answer = clingAnswer(context, text);
        if (answer == null) {
            return;
        }
        // Any refusal is a candidate: a mistyped command word can collide with a
        // real C symbol, as `open` does, and then the diagnostic is not about an
        // undeclared identifier at all.
        if (answer.startsWith("ERROR")) {
            final java.util.List<String> near = nearestCommands(text);
            if (!near.isEmpty()) {
                reportUnknown(text, near, answer);
                return;
            }
        }
        AppLogger.info(answer);
    }

    // --- Unknown command rather than a puzzling interpreter error ---

    private static final java.util.regex.Pattern UNDECLARED =
        java.util.regex.Pattern.compile("use of undeclared identifier '([^']+)'");

    /** The identifier cling did not know, or null when it refused for another reason. */
    private static String undeclaredIdentifier(String answer) {
        if (answer == null || !answer.startsWith("ERROR")) {
            return null;
        }
        final java.util.regex.Matcher m = UNDECLARED.matcher(answer);
        return m.find() ? m.group(1) : null;
    }

    private static void reportUnknown(String text, java.util.List<String> near,
                                      String diagnostic) {
        AppLogger.error("Unknown command: :root " + text.trim());
        for (int i = 0; i < near.size(); i++) {
            final String name = near.get(i);
            // Only the best match is worth rewriting as a line to run; the others
            // would carry over words that belong to a different command.
            final String line = (i == 0) ? runnable(name, text) : name;
            final CommandDefinitions.CommandInfo info = CommandDefinitions.find(name);
            AppLogger.raw((info != null && info.description != null && !info.description.isBlank())
                ? "  " + line + "  -  " + info.description
                : "  " + line);
        }
        // The interpreter's own reason, subordinate: the suggestion is the answer,
        // but a line meant as C++ still deserves to know why it was refused.
        final String reason = firstLine(diagnostic);
        if (!reason.isEmpty() && undeclaredIdentifier(diagnostic) == null) {
            AppLogger.raw("  (as C++ it was refused: " + reason + ")");
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String body = text.startsWith("ERROR:") ? text.substring(6).trim() : text.trim();
        final int end = body.indexOf('\n');
        if (end >= 0) {
            body = body.substring(0, end).trim();
        }
        return body.length() > 160 ? body.substring(0, 160) + "..." : body;
    }

    /** The suggested command, carrying over the words that were not part of its name. */
    private static String runnable(String name, String text) {
        final String[] words = name.substring(6).toLowerCase(java.util.Locale.ROOT).split("\\s+");
        final StringBuilder out = new StringBuilder(name);
        for (String typed : text.trim().split("\\s+")) {
            final String candidate = typed.toLowerCase(java.util.Locale.ROOT);
            boolean partOfName = false;
            for (String word : words) {
                if (editDistance(candidate, word) <= 2) {
                    partOfName = true;
                    break;
                }
            }
            if (!partOfName) {
                out.append(' ').append(typed);
            }
        }
        return out.toString();
    }

    /**
     * Registered :root commands closest to what was typed. A command word counts as
     * matched when some typed word is within two edits of it; the command matching
     * the most words wins, ties broken by how close those matches are.
     */
    private static java.util.List<String> nearestCommands(String text) {
        final String[] typed = text.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        final int lookAt = Math.min(typed.length, 4);
        final java.util.List<String[]> scored = new java.util.ArrayList<>();

        for (String name : CommandDefinitions.all().keySet()) {
            if (!name.startsWith(":root ")) {
                continue;
            }
            int matched = 0;
            int total = 0;
            for (String word : name.substring(6).toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < lookAt; i++) {
                    best = Math.min(best, editDistance(typed[i], word));
                }
                if (best <= 2) {
                    matched++;
                    total += best;
                }
            }
            if (matched > 0) {
                final int average = (total * 100) / matched;
                scored.add(new String[] {
                    String.format("%02d%04d", 99 - matched, average), name });
            }
        }

        scored.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] entry : scored) {
            if (out.size() == 3) {
                break;
            }
            out.add(entry[1]);
        }
        return out;
    }

    private static int editDistance(String a, String b) {
        final int n = b.length();
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= n; j++) {
                final int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                                      previous[j - 1] + cost);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[n];
    }

    /** Everything after the registered command name. */
    private static String args(String input, String command) {
        if (input == null) {
            return "";
        }
        String s = input.trim();
        return s.regionMatches(true, 0, command, 0, command.length())
            ? s.substring(command.length()).trim()
            : s.replaceFirst("^:root\\s+", "").trim();
    }

    private static void usage(String text) {
        AppLogger.warn("Usage: " + text);
    }

    /** Sends a native opcode and prints the engine's answer. */
    private static void send(CommandExecutionContext c, short opcode, int jobId, String payload) {
        com.sphere.core.rootbackend.RootBackend b = backend(c);
        if (b == null) {
            return;
        }
        byte[] bytes = (payload == null || payload.isEmpty())
            ? null : payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String answer = b.sendAwait(opcode, jobId, bytes, TIMEOUT_MS);
        if (answer == null) {
            AppLogger.error("No answer from the engine (opcode " + opcode + ").");
            return;
        }
        AppLogger.info(answer);
    }

    /** Runs one C++ expression in the engine's interpreter and prints the result. */
    private static void cling(CommandExecutionContext c, String expression) {
        String answer = clingAnswer(c, expression);
        if (answer != null) {
            AppLogger.info(answer);
        }
    }

    /** Same, but hands the answer back instead of printing it. Null when none came. */
    private static String clingAnswer(CommandExecutionContext c, String expression) {
        com.sphere.core.rootbackend.RootBackend b = backend(c);
        if (b == null) {
            return null;
        }
        String answer = b.executeClingAwait(expression, TIMEOUT_MS);
        if (answer == null) {
            AppLogger.error("No answer for: " + expression);
            return null;
        }
        return answer;
    }

    /** First token, the rest, or "" when absent. */
    private static String head(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String tail(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? "" : s.substring(i + 1).trim();
    }

    private static int asInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A named ROOT object, cast to `type`. Handles are names, not numbers:
     *  the engine keeps no registry for histograms, objects, graphs or canvases. */
    /** A checked lookup: a missing or mistyped object raises instead of yielding null. */
    private static String obj(String type, String name) {
        return "SphereBridge::Need<" + type + ">(\"" + name + "\", \"" + type + "\")";
    }

    // --- Level 1: native opcodes ---

    public static void rootPing(String i, CommandExecutionContext c) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_PING, 0, null);
    }

    public static void rootVersion(String i, CommandExecutionContext c) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_VERSION, 0, null);
    }

    public static void rootSysUptime(String i, CommandExecutionContext c) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_UPTIME, 0, null);
    }

    public static void rootSysConfig(String i, CommandExecutionContext c) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_CONFIG, 0, args(i, ":root sys config"));
    }

    public static void rootOpenFile(String i, CommandExecutionContext c) {
        String a = args(i, ":root file open");
        if (a.isEmpty()) {
            usage(":root file open <path>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_OPEN_FILE, 0, head(a));
    }

    public static void rootClose(String i, CommandExecutionContext c) {
        String a = args(i, ":root file close");
        if (a.isEmpty()) {
            usage(":root file close <id|name>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_CLOSE_FILE, 0, head(a));
    }

    public static void rootFileList(String i, CommandExecutionContext c) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_LIST, 0, null);
    }

    public static void rootFileScan(String i, CommandExecutionContext c) {
        String a = args(i, ":root file scan");
        if (a.isEmpty()) {
            usage(":root file scan <path> [--json]");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_FILE_SCAN, 0, a);
    }

    public static void rootCloseAll(String i, CommandExecutionContext c) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_CLOSE_ALL_FILES, 0, null);
    }

    public static void rootFileWrite(String i, CommandExecutionContext c) {
        String a = args(i, ":root file write");
        if (a.isEmpty()) {
            usage(":root file write <id|name>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_SAVE_FILE, 0, head(a));
    }

    public static void rootSchemaDiscover(String i, CommandExecutionContext c) {
        String a = args(i, ":root schema discover");
        if (a.isEmpty()) {
            usage(":root schema discover <tree_id>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_SCHEMA_DISCOVER, asInt(a, 0), null);
    }

    public static void rootTreePrint(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree print");
        if (a.isEmpty()) {
            usage(":root tree print <tree_id>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_INSPECT, asInt(a, 0), null);
    }

    public static void rootTreeEntries(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree entries");
        if (a.isEmpty()) {
            usage(":root tree entries <tree_id>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_QUERY_ENTRIES, asInt(a, 0), null);
    }

    public static void rootTreeBranches(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree branches");
        if (a.isEmpty()) {
            usage(":root tree branches <tree_id>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_SCAN_BRANCHES, asInt(a, 0), null);
    }

    public static void rootTreeLeaves(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree leaves");
        if (a.isEmpty()) {
            usage(":root tree leaves <tree_id>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_SCAN_BRANCHES, asInt(a, 0), null);
    }

    public static void rootTreeGetentry(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree getentry");
        if (a.isEmpty()) {
            usage(":root tree getentry <tree_id> <entry>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_GET_ENTRY,
             asInt(head(a), 0), tail(a));
    }

    public static void rootTreeColumn(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree column");
        if (a.isEmpty() || tail(a).isEmpty()) {
            usage(":root tree column <tree_id> <branch>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_READ_COLUMN,
             asInt(head(a), 0), tail(a));
    }

    public static void rootTreeStats(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree stats");
        if (a.isEmpty() || tail(a).isEmpty()) {
            usage(":root tree stats <tree_id> <branch>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_COMPUTE_STATS,
             asInt(head(a), 0), tail(a));
    }

    public static void rootTreeFilter(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree filter");
        if (a.isEmpty() || tail(a).isEmpty()) {
            usage(":root tree filter <tree_id> <expression>");
            return;
        }
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_TTREE_APPLY_FILTER,
             asInt(head(a), 0), tail(a));
    }

    public static void rootOpenRemoteFile(String i, CommandExecutionContext c) {
        String a = args(i, ":root file open-remote");
        if (a.isEmpty()) {
            usage(":root file open-remote <url>");
            return;
        }
        String a0 = a;
        cling(c, "TFile::Open(\"" + a0 + "\")");
    }

    public static void rootLs(String i, CommandExecutionContext c) {
        cling(c, "gDirectory->ls()");
    }

    public static void rootFileKeys(String i, CommandExecutionContext c) {
        cling(c, "gDirectory->GetListOfKeys()->Print()");
    }

    public static void rootFileCd(String i, CommandExecutionContext c) {
        String a = args(i, ":root file cd");
        if (a.isEmpty()) {
            usage(":root file cd <path>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->cd(\"" + a0 + "\")");
    }

    public static void rootFilePwd(String i, CommandExecutionContext c) {
        cling(c, "gDirectory->pwd()");
    }

    public static void rootFileDir(String i, CommandExecutionContext c) {
        cling(c, "gDirectory->ls()");
    }

    public static void rootFileGet(String i, CommandExecutionContext c) {
        String a = args(i, ":root file get");
        if (a.isEmpty()) {
            usage(":root file get <name>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->Get(\"" + a0 + "\")->ClassName()");
    }

    public static void rootFileRecreate(String i, CommandExecutionContext c) {
        String a = args(i, ":root file recreate");
        if (a.isEmpty()) {
            usage(":root file recreate <path>");
            return;
        }
        String a0 = a;
        cling(c, "TFile::Open(\"" + a0 + "\",\"RECREATE\")");
    }

    public static void rootFileOpenUpdate(String i, CommandExecutionContext c) {
        String a = args(i, ":root file open-update");
        if (a.isEmpty()) {
            usage(":root file open-update <path>");
            return;
        }
        String a0 = a;
        cling(c, "TFile::Open(\"" + a0 + "\",\"UPDATE\")");
    }

    public static void rootFileMkdir(String i, CommandExecutionContext c) {
        String a = args(i, ":root file mkdir");
        if (a.isEmpty()) {
            usage(":root file mkdir <name>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->mkdir(\"" + a0 + "\")");
    }

    public static void rootFileRmdir(String i, CommandExecutionContext c) {
        String a = args(i, ":root file rmdir");
        if (a.isEmpty()) {
            usage(":root file rmdir <name>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->rmdir(\"" + a0 + "\")");
    }

    public static void rootFileDelete(String i, CommandExecutionContext c) {
        String a = args(i, ":root file delete");
        if (a.isEmpty()) {
            usage(":root file delete <name>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->Delete(\"" + a0 + "\")");
    }

    public static void rootFileCopy(String i, CommandExecutionContext c) {
        String a = args(i, ":root file copy");
        if (a.isEmpty()) {
            usage(":root file copy <src> <dst>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "gDirectory->Get(\"" + a0 + "\")->Clone(\"" + a1 + "\")");
    }

    public static void rootFileMove(String i, CommandExecutionContext c) {
        String a = args(i, ":root file move");
        if (a.isEmpty()) {
            usage(":root file move <src> <dst>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "gDirectory->Get(\"" + a0 + "\")->Clone(\"" + a1 + "\");gDirectory->Delete(\"" + a0 + "\")");
    }

    public static void rootFileInfo(String i, CommandExecutionContext c) {
        cling(c, "gFile->Print()");
    }

    public static void rootListHandles(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootCd(String i, CommandExecutionContext c) {
        String a = args(i, ":root cd");
        if (a.isEmpty()) {
            usage(":root cd <path>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->cd(\"" + a0 + "\")");
    }

    public static void rootPwd(String i, CommandExecutionContext c) {
        cling(c, "gDirectory->pwd()");
    }

    public static void rootMkdir(String i, CommandExecutionContext c) {
        String a = args(i, ":root mkdir");
        if (a.isEmpty()) {
            usage(":root mkdir <name>");
            return;
        }
        String a0 = a;
        cling(c, "gDirectory->mkdir(\"" + a0 + "\")");
    }

    public static void rootGetHist(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist get");
        if (a.isEmpty()) {
            usage(":root hist get <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->ClassName()");
    }

    public static void rootDumpHistBins(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist bins");
        if (a.isEmpty()) {
            usage(":root hist bins <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->Print(\"all\")");
    }

    public static void rootHistReset(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist reset");
        if (a.isEmpty()) {
            usage(":root hist reset <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->Reset()");
    }

    public static void rootHistRebin(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist rebin");
        if (a.isEmpty()) {
            usage(":root hist rebin <name> <n>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Rebin(" + a1 + ")");
    }

    public static void rootHistScale(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist scale");
        if (a.isEmpty()) {
            usage(":root hist scale <name> <f>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Scale(" + a1 + ")");
    }

    public static void rootHistDraw(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist draw");
        if (a.isEmpty()) {
            usage(":root hist draw <name> [opt]");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Draw(\"" + a1 + "\")");
    }

    public static void rootHistFit(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist fit");
        if (a.isEmpty()) {
            usage(":root hist fit <name> <f>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootHistIntegral(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist integral");
        if (a.isEmpty()) {
            usage(":root hist integral <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->Integral()");
    }

    public static void rootHistMax(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist max");
        if (a.isEmpty()) {
            usage(":root hist max <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->GetMaximum()");
    }

    public static void rootHistMin(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist min");
        if (a.isEmpty()) {
            usage(":root hist min <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->GetMinimum()");
    }

    public static void rootHistList(String i, CommandExecutionContext c) {
        cling(c, "gDirectory->GetList()->Print()");
    }

    public static void rootHistSmooth(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist smooth");
        if (a.isEmpty()) {
            usage(":root hist smooth <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TH1", a0) + "->Smooth()");
    }

    public static void rootHistProject(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist project");
        if (a.isEmpty()) {
            usage(":root hist project <name> <axis>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH2", a0) + "->ProjectionX(\"" + a1 + "\")");
    }

    public static void rootHistStatbox(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist statbox");
        if (a.isEmpty()) {
            usage(":root hist statbox <0|1>");
            return;
        }
        String a0 = a;
        cling(c, "gStyle->SetOptStat(" + a0 + ")");
    }

    public static void rootHistSetbin(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist setbin");
        if (a.isEmpty()) {
            usage(":root hist setbin <name> <bin> <v>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->SetBinContent(" + a1 + ")");
    }

    public static void rootHistFill(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist fill");
        if (a.isEmpty()) {
            usage(":root hist fill <name> <v>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Fill(" + a1 + ")");
    }

    public static void rootHistClone(String i, CommandExecutionContext c) {
        String a = args(i, ":root hist clone");
        if (a.isEmpty()) {
            usage(":root hist clone <name> <new>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Clone(\"" + a1 + "\")");
    }

    public static void rootGetObject(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj get");
        if (a.isEmpty()) {
            usage(":root obj get <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->ClassName()");
    }

    public static void rootDumpObject(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj dump");
        if (a.isEmpty()) {
            usage(":root obj dump <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Dump()");
    }

    public static void rootDescribeObject(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj describe");
        if (a.isEmpty()) {
            usage(":root obj describe <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->Print()");
    }

    public static void rootObjClone(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj clone");
        if (a.isEmpty()) {
            usage(":root obj clone <name> <new>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Clone(\"" + a1 + "\")");
    }

    public static void rootObjWrite(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj write");
        if (a.isEmpty()) {
            usage(":root obj write <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Write()");
    }

    public static void rootObjDelete(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj delete");
        if (a.isEmpty()) {
            usage(":root obj delete <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Delete()");
    }

    public static void rootObjMethods(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj methods");
        if (a.isEmpty()) {
            usage(":root obj methods <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->GetListOfMethods()->Print()");
    }

    public static void rootObjMembers(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj members");
        if (a.isEmpty()) {
            usage(":root obj members <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->GetListOfDataMembers()->Print()");
    }

    public static void rootObjList(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfSpecials()->Print()");
    }

    public static void rootObjClass(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj class");
        if (a.isEmpty()) {
            usage(":root obj class <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->ClassName()");
    }

    public static void rootObjType(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj type");
        if (a.isEmpty()) {
            usage(":root obj type <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->IsA()->GetName()");
    }

    public static void rootObjPrint(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj print");
        if (a.isEmpty()) {
            usage(":root obj print <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Print()");
    }

    public static void rootObjInspect(String i, CommandExecutionContext c) {
        String a = args(i, ":root obj inspect");
        if (a.isEmpty()) {
            usage(":root obj inspect <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Inspect()");
    }

    public static void rootTreeScan(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree scan");
        if (a.isEmpty()) {
            usage(":root tree scan <name> [expr]");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TTree", a0) + "->Scan(\"" + a1 + "\")");
    }

    public static void rootTreeDraw(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree draw");
        if (a.isEmpty()) {
            usage(":root tree draw <name> <expr>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TTree", a0) + "->Draw(\"" + a1 + "\")");
    }

    public static void rootTreeProcess(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree process");
        if (a.isEmpty()) {
            usage(":root tree process <name> <macro>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TTree", a0) + "->Process(\"" + a1 + "\")");
    }

    public static void rootTreeProject(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree project");
        if (a.isEmpty()) {
            usage(":root tree project <name> <h> <e>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TTree", a0) + "->Project(\"" + a1 + "\")");
    }

    public static void rootTreeCopytree(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree copytree");
        if (a.isEmpty()) {
            usage(":root tree copytree <name> <cut>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TTree", a0) + "->CopyTree(\"" + a1 + "\")");
    }

    public static void rootGetTree(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree open");
        if (a.isEmpty()) {
            usage(":root tree open <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TTree", a0) + "->GetEntries()");
    }

    public static void rootGetBranch(String i, CommandExecutionContext c) {
        String a = args(i, ":root tree branch");
        if (a.isEmpty()) {
            usage(":root tree branch <name> <b>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TTree", a0) + "->GetBranch(\"" + a1 + "\")->Print()");
    }

    public static void rootChainAdd(String i, CommandExecutionContext c) {
        String a = args(i, ":root chain add");
        if (a.isEmpty()) {
            usage(":root chain add <name> <file>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TChain", a0) + "->Add(\"" + a1 + "\")");
    }

    public static void rootRdfOpen(String i, CommandExecutionContext c) {
        String a = args(i, ":root rdf open");
        if (a.isEmpty()) {
            usage(":root rdf open <tree> <file>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "ROOT::RDataFrame(\"" + a0 + "\",\"" + a1 + "\")");
    }

    public static void rootRdfFilter(String i, CommandExecutionContext c) {
        String a = args(i, ":root rdf filter");
        if (a.isEmpty()) {
            usage(":root rdf filter <expr>");
            return;
        }
        String a0 = a;
        cling(c, "df.Filter(\"" + a0 + "\")");
    }

    public static void rootRdfCount(String i, CommandExecutionContext c) {
        cling(c, "df.Count().GetValue()");
    }

    public static void rootGraphDraw(String i, CommandExecutionContext c) {
        String a = args(i, ":root graph draw");
        if (a.isEmpty()) {
            usage(":root graph draw <name> [opt]");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TGraph", a0) + "->Draw(\"" + a1 + "\")");
    }

    public static void rootGraphFit(String i, CommandExecutionContext c) {
        String a = args(i, ":root graph fit");
        if (a.isEmpty()) {
            usage(":root graph fit <name> <f>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TGraph", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootGraphPoints(String i, CommandExecutionContext c) {
        String a = args(i, ":root graph points");
        if (a.isEmpty()) {
            usage(":root graph points <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TGraph", a0) + "->Print()");
    }

    public static void rootGraphAdd(String i, CommandExecutionContext c) {
        String a = args(i, ":root graph add");
        if (a.isEmpty()) {
            usage(":root graph add <name> <x> <y>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TGraph", a0) + "->SetPoint(" + a1 + ")");
    }

    public static void rootCanvasNew(String i, CommandExecutionContext c) {
        String a = args(i, ":root canvas new");
        if (a.isEmpty()) {
            usage(":root canvas new <name>");
            return;
        }
        String a0 = a;
        cling(c, "new TCanvas(\"" + a0 + "\",\"" + a0 + "\",800,600)");
    }

    public static void rootCanvasCd(String i, CommandExecutionContext c) {
        String a = args(i, ":root canvas cd");
        if (a.isEmpty()) {
            usage(":root canvas cd <pad>");
            return;
        }
        String a0 = a;
        cling(c, "gPad->cd(" + a0 + ")");
    }

    public static void rootCanvasSave(String i, CommandExecutionContext c) {
        String a = args(i, ":root canvas save");
        if (a.isEmpty()) {
            usage(":root canvas save <file>");
            return;
        }
        String a0 = a;
        cling(c, "gPad->SaveAs(\"" + a0 + "\")");
    }

    public static void rootCanvasClear(String i, CommandExecutionContext c) {
        cling(c, "gPad->Clear()");
    }

    public static void rootCanvasUpdate(String i, CommandExecutionContext c) {
        cling(c, "gPad->Update()");
    }

    public static void rootCanvasList(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfCanvases()->Print()");
    }

    public static void rootStyleSet(String i, CommandExecutionContext c) {
        String a = args(i, ":root style set");
        if (a.isEmpty()) {
            usage(":root style set <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->SetStyle(\"" + a0 + "\")");
    }

    public static void rootFuncNew(String i, CommandExecutionContext c) {
        String a = args(i, ":root func new");
        if (a.isEmpty()) {
            usage(":root func new <name> <f>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "new TF1(\"" + a0 + "\",\"" + a1 + "\",0,1)");
    }

    public static void rootFitExpr(String i, CommandExecutionContext c) {
        String a = args(i, ":root fit expr");
        if (a.isEmpty()) {
            usage(":root fit expr <obj> <f>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootFitFunction(String i, CommandExecutionContext c) {
        String a = args(i, ":root fit function");
        if (a.isEmpty()) {
            usage(":root fit function <obj> <f>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TH1", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootFitReset(String i, CommandExecutionContext c) {
        cling(c, "gMinuit->mnrset(1)");
    }

    public static void rootFitParams(String i, CommandExecutionContext c) {
        String a = args(i, ":root fit params");
        if (a.isEmpty()) {
            usage(":root fit params <name>");
            return;
        }
        String a0 = a;
        cling(c, "" + obj("TF1", a0) + "->Print()");
    }

    public static void rootMathEval(String i, CommandExecutionContext c) {
        String a = args(i, ":root math eval");
        if (a.isEmpty()) {
            usage(":root math eval <name> <x>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TF1", a0) + "->Eval(" + a1 + ")");
    }

    public static void rootMathDeriv(String i, CommandExecutionContext c) {
        String a = args(i, ":root math deriv");
        if (a.isEmpty()) {
            usage(":root math deriv <name> <x>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TF1", a0) + "->Derivative(" + a1 + ")");
    }

    public static void rootMathIntegral(String i, CommandExecutionContext c) {
        String a = args(i, ":root math integral");
        if (a.isEmpty()) {
            usage(":root math integral <name> <a> <b>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "" + obj("TF1", a0) + "->Integral(" + a1 + ")");
    }

    public static void rootSysInfo(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetBuildArch()");
    }

    public static void rootSysMemory(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootSysPlugins(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfTypes()->Print()");
    }

    public static void rootStatus(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootDump(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfSpecials()->Print()");
    }

    public static void rootReset(String i, CommandExecutionContext c) {
        cling(c, "gROOT->Reset()");
    }

    public static void rootGc(String i, CommandExecutionContext c) {
        cling(c, "gSystem->CheckObjectValidity()");
    }

    public static void rootStats(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootInfo(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetVersion()");
    }

    public static void rootVars(String i, CommandExecutionContext c) {
        String a = args(i, ":root vars");
        if (a.isEmpty()) {
            usage(":root vars <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->GetGlobal(\"" + a0 + "\")->Print()");
    }

    public static void rootGetEnv(String i, CommandExecutionContext c) {
        String a = args(i, ":root getenv");
        if (a.isEmpty()) {
            usage(":root getenv <name>");
            return;
        }
        String a0 = a;
        cling(c, "gSystem->Getenv(\"" + a0 + "\")");
    }

    public static void rootConfig(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMakeSharedLib()");
    }

    public static void rootDiag(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetVersion()");
    }

    public static void rootBenchmark(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetCpuInfo(0)");
    }

    public static void rootSafeMode(String i, CommandExecutionContext c) {
        String a = args(i, ":root safe-mode");
        if (a.isEmpty()) {
            usage(":root safe-mode <0|1>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->SetBatch(" + a0 + ")");
    }

    public static void rootSetOutput(String i, CommandExecutionContext c) {
        String a = args(i, ":root output set");
        if (a.isEmpty()) {
            usage(":root output set <file>");
            return;
        }
        String a0 = a;
        cling(c, "gSystem->RedirectOutput(\"" + a0 + "\")");
    }

    public static void rootLoadScript(String i, CommandExecutionContext c) {
        String a = args(i, ":root script load");
        if (a.isEmpty()) {
            usage(":root script load <file>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->LoadMacro(\"" + a0 + "\")");
    }

    public static void rootRunScript(String i, CommandExecutionContext c) {
        String a = args(i, ":root script run");
        if (a.isEmpty()) {
            usage(":root script run <file>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->ProcessLine(\".x " + a0 + "\")");
    }

    public static void rootCompileScripts(String i, CommandExecutionContext c) {
        String a = args(i, ":root script compile");
        if (a.isEmpty()) {
            usage(":root script compile <file>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->LoadMacro(\"" + a0 + "+\")");
    }

    public static void rootLoadIncludes(String i, CommandExecutionContext c) {
        String a = args(i, ":root includes load");
        if (a.isEmpty()) {
            usage(":root includes load <dir>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->ProcessLine(\".I " + a0 + "\")");
    }

    public static void rootCompileIncludes(String i, CommandExecutionContext c) {
        String a = args(i, ":root includes compile");
        if (a.isEmpty()) {
            usage(":root includes compile <dir>");
            return;
        }
        String a0 = a;
        cling(c, "gSystem->AddIncludePath(\"-I" + a0 + "\")");
    }

    public static void rootGeomLoad(String i, CommandExecutionContext c) {
        String a = args(i, ":root geom load");
        if (a.isEmpty()) {
            usage(":root geom load <file>");
            return;
        }
        String a0 = a;
        cling(c, "TGeoManager::Import(\"" + a0 + "\")");
    }

    public static void rootGeomDraw(String i, CommandExecutionContext c) {
        cling(c, "gGeoManager->GetTopVolume()->Draw()");
    }

    public static void rootGeomExport(String i, CommandExecutionContext c) {
        String a = args(i, ":root geom export");
        if (a.isEmpty()) {
            usage(":root geom export <file>");
            return;
        }
        String a0 = a;
        cling(c, "gGeoManager->Export(\"" + a0 + "\")");
    }

    public static void rootSqlConnect(String i, CommandExecutionContext c) {
        String a = args(i, ":root sql connect");
        if (a.isEmpty()) {
            usage(":root sql connect <url>");
            return;
        }
        String a0 = a;
        cling(c, "TSQLServer::Connect(\"" + a0 + "\",\"\",\"\")");
    }

    public static void rootSqlQuery(String i, CommandExecutionContext c) {
        String a = args(i, ":root sql query");
        if (a.isEmpty()) {
            usage(":root sql query <sql>");
            return;
        }
        String a0 = a;
        cling(c, "db->Query(\"" + a0 + "\")");
    }

    public static void rootSqlDisconnect(String i, CommandExecutionContext c) {
        cling(c, "db->Close()");
    }

    public static void rootNetServer(String i, CommandExecutionContext c) {
        String a = args(i, ":root net server");
        if (a.isEmpty()) {
            usage(":root net server <port>");
            return;
        }
        String a0 = a;
        cling(c, "new TServerSocket(" + a0 + ",kTRUE)");
    }

    public static void rootNetConnect(String i, CommandExecutionContext c) {
        String a = args(i, ":root net connect");
        if (a.isEmpty()) {
            usage(":root net connect <host> <port>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "new TSocket(\"" + a0 + "\"," + a1 + ")");
    }

    public static void rootNetSend(String i, CommandExecutionContext c) {
        String a = args(i, ":root net send");
        if (a.isEmpty()) {
            usage(":root net send <msg>");
            return;
        }
        String a0 = a;
        cling(c, "sock->Send(\"" + a0 + "\")");
    }

    public static void rootProofOpen(String i, CommandExecutionContext c) {
        String a = args(i, ":root proof open");
        if (a.isEmpty()) {
            usage(":root proof open <url>");
            return;
        }
        String a0 = a;
        cling(c, "TProof::Open(\"" + a0 + "\")");
    }

    public static void rootProofProcess(String i, CommandExecutionContext c) {
        String a = args(i, ":root proof process");
        if (a.isEmpty()) {
            usage(":root proof process <sel>");
            return;
        }
        String a0 = a;
        cling(c, "gProof->Process(\"" + a0 + "\")");
    }

    public static void rootProofStatus(String i, CommandExecutionContext c) {
        cling(c, "gProof->Print()");
    }

    public static void rootGuiNew(String i, CommandExecutionContext c) {
        String a = args(i, ":root gui new");
        if (a.isEmpty()) {
            usage(":root gui new <name>");
            return;
        }
        String a0 = a;
        cling(c, "new TGMainFrame(gClient->GetRoot())");
    }

    public static void rootGuiShow(String i, CommandExecutionContext c) {
        cling(c, "gClient->GetRoot()->MapWindow()");
    }

    public static void rootGuiClose(String i, CommandExecutionContext c) {
        cling(c, "gClient->GetRoot()->UnmapWindow()");
    }

    public static void rootPyImport(String i, CommandExecutionContext c) {
        String a = args(i, ":root py import");
        if (a.isEmpty()) {
            usage(":root py import <mod>");
            return;
        }
        String a0 = a;
        cling(c, "TPython::Exec(\"import " + a0 + "\")");
    }

    public static void rootPyEval(String i, CommandExecutionContext c) {
        String a = args(i, ":root py eval");
        if (a.isEmpty()) {
            usage(":root py eval <expr>");
            return;
        }
        String a0 = a;
        cling(c, "TPython::Eval(\"" + a0 + "\")");
    }

    public static void rootPyExec(String i, CommandExecutionContext c) {
        String a = args(i, ":root py exec");
        if (a.isEmpty()) {
            usage(":root py exec <code>");
            return;
        }
        String a0 = a;
        cling(c, "TPython::Exec(\"" + a0 + "\")");
    }

    public static void rootTmvaFactory(String i, CommandExecutionContext c) {
        String a = args(i, ":root tmva factory");
        if (a.isEmpty()) {
            usage(":root tmva factory <name>");
            return;
        }
        String a0 = a;
        cling(c, "new TMVA::Factory(\"" + a0 + "\",0,\"\")");
    }

    public static void rootTmvaTrain(String i, CommandExecutionContext c) {
        cling(c, "factory->TrainAllMethods()");
    }

    public static void rootTmvaTest(String i, CommandExecutionContext c) {
        cling(c, "factory->TestAllMethods()");
    }

    public static void rootTmvaEvaluate(String i, CommandExecutionContext c) {
        cling(c, "factory->EvaluateAllMethods()");
    }

    public static void rootTmvaGui(String i, CommandExecutionContext c) {
        String a = args(i, ":root tmva gui");
        if (a.isEmpty()) {
            usage(":root tmva gui <file>");
            return;
        }
        String a0 = a;
        cling(c, "TMVA::TMVAGui(\"" + a0 + "\")");
    }

    public static void rootRoofitWorkspace(String i, CommandExecutionContext c) {
        String a = args(i, ":root roofit workspace");
        if (a.isEmpty()) {
            usage(":root roofit workspace <name>");
            return;
        }
        String a0 = a;
        cling(c, "new RooWorkspace(\"" + a0 + "\")");
    }

    public static void rootRoofitPdf(String i, CommandExecutionContext c) {
        String a = args(i, ":root roofit pdf");
        if (a.isEmpty()) {
            usage(":root roofit pdf <expr>");
            return;
        }
        String a0 = a;
        cling(c, "w->factory(\"" + a0 + "\")");
    }

    public static void rootRoofitFit(String i, CommandExecutionContext c) {
        String a = args(i, ":root roofit fit");
        if (a.isEmpty()) {
            usage(":root roofit fit <pdf> <data>");
            return;
        }
        String a0 = head(a);
        String a1 = tail(a);
        cling(c, "w->pdf(\"" + a0 + "\")->fitTo(*w->data(\"" + a1 + "\"))");
    }

    public static void rootRoofitPlot(String i, CommandExecutionContext c) {
        String a = args(i, ":root roofit plot");
        if (a.isEmpty()) {
            usage(":root roofit plot <var>");
            return;
        }
        String a0 = a;
        cling(c, "w->var(\"" + a0 + "\")->frame()->Draw()");
    }

    public static void rootProfile(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetCpuInfo(0)");
    }

    public static void rootProfileStats(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootProfileJson(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootProfileReset(String i, CommandExecutionContext c) {
        cling(c, "gSystem->ResetSignal(kSigSegmentationViolation)");
    }

    public static void rootProfileLevel(String i, CommandExecutionContext c) {
        String a = args(i, ":root profile level");
        if (a.isEmpty()) {
            usage(":root profile level <n>");
            return;
        }
        String a0 = a;
        cling(c, "gDebug=" + a0 + "");
    }

    public static void rootProfileThreshold(String i, CommandExecutionContext c) {
        String a = args(i, ":root profile threshold");
        if (a.isEmpty()) {
            usage(":root profile threshold <ms>");
            return;
        }
        String a0 = a;
        cling(c, "gDebug=" + a0 + "");
    }

    public static void rootProfilingStatus(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootProfilingJson(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootProfilingReset(String i, CommandExecutionContext c) {
        cling(c, "gDebug=0");
    }

    public static void rootProfilingLevel(String i, CommandExecutionContext c) {
        String a = args(i, ":root profiling level");
        if (a.isEmpty()) {
            usage(":root profiling level <n>");
            return;
        }
        String a0 = a;
        cling(c, "gDebug=" + a0 + "");
    }

    public static void rootDebugDump(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfSpecials()->Print()");
    }

    public static void rootDebugGraphviz(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfClasses()->Print()");
    }

    public static void rootDebugAudit(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootDebugLevel(String i, CommandExecutionContext c) {
        String a = args(i, ":root debug level");
        if (a.isEmpty()) {
            usage(":root debug level <0-5>");
            return;
        }
        String a0 = a;
        cling(c, "gDebug=" + a0 + "");
    }

    public static void rootWatchdogStatus(String i, CommandExecutionContext c) {
        cling(c, "gSystem->GetMemInfo(0)");
    }

    public static void rootWatchdogKill(String i, CommandExecutionContext c) {
        cling(c, "gROOT->Reset()");
    }

    public static void rootSetCacheSize(String i, CommandExecutionContext c) {
        String a = args(i, ":root cache size");
        if (a.isEmpty()) {
            usage(":root cache size <n>");
            return;
        }
        String a0 = a;
        cling(c, "gEnv->SetValue(\"TFile.CacheSize\"," + a0 + ")");
    }

    public static void rootSetCachePolicy(String i, CommandExecutionContext c) {
        String a = args(i, ":root cache policy");
        if (a.isEmpty()) {
            usage(":root cache policy <n>");
            return;
        }
        String a0 = a;
        cling(c, "gEnv->SetValue(\"TFile.CachePolicy\"," + a0 + ")");
    }

    public static void rootCacheStats(String i, CommandExecutionContext c) {
        cling(c, "gEnv->Print()");
    }

    public static void rootCacheClear(String i, CommandExecutionContext c) {
        cling(c, "gROOT->GetListOfFiles()->Print()");
    }

    public static void rootSetMaxObjSize(String i, CommandExecutionContext c) {
        String a = args(i, ":root limits obj-size");
        if (a.isEmpty()) {
            usage(":root limits obj-size <n>");
            return;
        }
        String a0 = a;
        cling(c, "gEnv->SetValue(\"TFile.MaxSize\"," + a0 + ")");
    }

    public static void rootSetMaxHandles(String i, CommandExecutionContext c) {
        String a = args(i, ":root limits handles");
        if (a.isEmpty()) {
            usage(":root limits handles <n>");
            return;
        }
        String a0 = a;
        cling(c, "gEnv->SetValue(\"TFile.MaxHandles\"," + a0 + ")");
    }

    public static void rootSetMaxAge(String i, CommandExecutionContext c) {
        String a = args(i, ":root limits age");
        if (a.isEmpty()) {
            usage(":root limits age <n>");
            return;
        }
        String a0 = a;
        cling(c, "gEnv->SetValue(\"TFile.MaxAge\"," + a0 + ")");
    }

    public static void rootAnalyze(String i, CommandExecutionContext c) {
        String a = args(i, ":root analyze");
        if (a.isEmpty()) {
            usage(":root analyze <name>");
            return;
        }
        String a0 = a;
        cling(c, "gROOT->FindObject(\"" + a0 + "\")->Print()");
    }

}
