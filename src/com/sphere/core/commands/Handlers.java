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
    public static void rootMode(String input, CommandExecutionContext c) { 
        String clean = (input != null) ? input.trim() : "";
        if (clean.equals(":root mode")) {
            switchMode(c, "root", "[root]"); 
        } else {
            AppLogger.info("[root] Evaluating ROOT script or standalone interactive statement...");
        }
    }

    public static void rootExit(String input, CommandExecutionContext c) { 
        switchMode(c, null, ""); 
    }

    public static void rootVars(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root vars", "").trim();
        if (args.isEmpty()) {
            sendToRootBridge("vars", c);
        } else {
            sendToRootBridge("cling exec gROOT->GetGlobal(\"" + args + "\")->Print();", c);
        }
    }

    public static void rootDiag(String input, CommandExecutionContext c) { 
        sendToRootBridge("diag", c); 
    }

    public static void rootConfig(String input, CommandExecutionContext c) {
        sendToRootBridge("cling exec gSystem->GetMakeSharedLib();", c);
    }

    public static void rootVersion(String input, CommandExecutionContext c) {
        sendToRootBridge("version", c);
    }

    public static void sendToRootBridge(String command, CommandExecutionContext context) {
        if (context != null && context.ctx != null && context.ctx.router != null) {
            Object backendObj = context.ctx.router.getRootBackend();
            if (backendObj instanceof com.sphere.core.rootbackend.RootBackend rootBackend) {
                boolean status = rootBackend.executeCling(command);
                if (!status) {
                    AppLogger.warn("Failed to dispatch command via ROOT process bridge: " + command);
                }
            } else {
                AppLogger.error("ROOT backend core component is uninitialized or type-mismatched.");
            }
        } else {
            AppLogger.error("Command execution context is lost or missing router driver configuration.");
        }
    }

    // --- Files & Handles ---
    public static void rootOpenFile(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root open", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing parameters. Usage: :root open <path> [mode]");
            return;
        }
        sendToRootBridge("file open " + args, c);
    }

    public static void rootOpenRemoteFile(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root open_remote", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing parameters. Usage: :root open_remote <url>");
            return;
        }
        sendToRootBridge("file open-remote " + args, c);
    }

    public static void rootClose(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root close", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing handle ID. Usage: :root close <handle_id>");
            return;
        }
        sendToRootBridge("file close " + args, c);
    }

    public static void rootCloseAll(String input, CommandExecutionContext c) {
        sendToRootBridge("file close-all", c);
    }

    public static void rootLs(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root ls", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing handle ID. Usage: :root ls <file_handle_id>");
            return;
        }
        sendToRootBridge("file ls " + args, c);
    }

    public static void rootPwd(String input, CommandExecutionContext c) {
        sendToRootBridge("file pwd", c);
    }

    public static void rootCd(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root cd", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing target directory. Usage: :root cd <dir_path>");
            return;
        }
        sendToRootBridge("file cd " + args, c);
    }

    public static void rootMkdir(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root mkdir", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing directory name. Usage: :root mkdir <dir_name>");
            return;
        }
        sendToRootBridge("file mkdir " + args, c);
    }

    // --- Histograms ---
    public static void rootGetHist(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root get_hist", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root get_hist <file_id> <hist_name>");
            return;
        }
        sendToRootBridge("hist get " + args, c);
    }

    public static void rootDumpHistBins(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root dump_bins", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing Histogram Handle. Usage: :root dump_bins <hist_id>");
            return;
        }
        sendToRootBridge("hist bins " + args, c);
    }

    public static void rootHistDraw(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist_draw", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing Histogram Handle. Usage: :root hist_draw <hist_id> [opt]");
            return;
        }
        sendToRootBridge("hist draw " + args, c);
    }

    public static void rootHistFit(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist_fit", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root hist_fit <hist_id> <formula>");
            return;
        }
        sendToRootBridge("hist fit " + args, c);
    }

    public static void rootHistRebin(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist_rebin", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root hist_rebin <hist_id> <ngroup>");
            return;
        }
        sendToRootBridge("hist rebin " + args, c);
    }

    // --- Objects ---
    public static void rootGetObject(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root get_object", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root get_object <file_id> <obj_name>");
            return;
        }
        sendToRootBridge("obj get " + args, c);
    }

    public static void rootDumpObject(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root dump", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing Handle ID. Usage: :root dump <handle_id>");
            return;
        }
        sendToRootBridge("obj dump " + args, c);
    }

    public static void rootDescribeObject(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root describe", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing Handle ID. Usage: :root describe <handle_id>");
            return;
        }
        sendToRootBridge("obj describe " + args, c);
    }

    // --- Trees & Chains ---
    public static void rootGetTree(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root get_tree", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root get_tree <file_id> <tree_name>");
            return;
        }
        sendToRootBridge("obj get " + args, c);
    }

    public static void rootTreePrint(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree_print", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root tree_print <tree_handle_id>");
            return;
        }
        sendToRootBridge("tree print " + args, c);
    }

    public static void rootTreeScan(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree_scan", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root tree_scan <tree_handle_id> [varexp] [selection]");
            return;
        }
        sendToRootBridge("tree scan " + args, c);
    }

    public static void rootTreeDraw(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree_draw", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root tree_draw <tree_handle_id> <varexp> [selection]");
            return;
        }
        sendToRootBridge("tree draw " + args, c);
    }

    public static void rootGetBranch(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root get_branch", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root get_branch <tree_handle_id>");
            return;
        }
        sendToRootBridge("tree branches " + args, c);
    }

    public static void rootChainAdd(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root chain_add", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root chain_add <tree_name> <file_pattern>");
            return;
        }
        sendToRootBridge("chain add " + args, c);
    }

    // --- DataFrames (RDataFrame) ---
    public static void rootRdfOpen(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root rdf_open", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root rdf_open <tree_name> <file_path>");
            return;
        }
        sendToRootBridge("dataframe open " + args, c);
    }

    public static void rootRdfFilter(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root rdf_filter", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root rdf_filter <rdf_handle_id> <expression>");
            return;
        }
        sendToRootBridge("dataframe filter " + args, c);
    }

    public static void rootRdfCount(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root rdf_count", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root rdf_count <rdf_handle_id>");
            return;
        }
        sendToRootBridge("dataframe count " + args, c);
    }

    // --- Graphs & Canvases ---
    public static void rootGraphDraw(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root graph_draw", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root graph_draw <graph_handle_id> [opt]");
            return;
        }
        sendToRootBridge("graph draw " + args, c);
    }

    public static void rootCanvasNew(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root canvas_new", "").trim();
        sendToRootBridge("canvas new " + args, c);
    }

    public static void rootCanvasSave(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root canvas_save", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root canvas_save <canvas_id> <file_path>");
            return;
        }
        sendToRootBridge("canvas save " + args, c);
    }

    // --- Functions & Style ---
    public static void rootFuncNew(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root func_new", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root func_new <name> <formula> <xmin> <xmax>");
            return;
        }
        sendToRootBridge("func new " + args, c);
    }

    public static void rootStyleSet(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root style_set", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root style_set <style_name>");
            return;
        }
        sendToRootBridge("style set " + args, c);
    }

    // --- Analytics, Diagnostics & System ---
    public static void rootAnalyze(String input, CommandExecutionContext c) {
        String rawArgs = input.replaceFirst("^:root analyze", "").trim();
        if (rawArgs.isEmpty()) {
            AppLogger.warn("Missing Handle. Usage: :root analyze [hist/obj] <handle_id>");
            return;
        }
        
        if (rawArgs.startsWith("hist ")) {
            sendToRootBridge("analyze hist " + rawArgs.substring(5).trim(), c);
        } else if (rawArgs.startsWith("obj ")) {
            sendToRootBridge("analyze obj " + rawArgs.substring(4).trim(), c);
        } else {
            sendToRootBridge("analyze hist " + rawArgs, c);
        }
    }

    public static void rootInfo(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root info", "").trim();
        if (args.isEmpty()) {
            sendToRootBridge("status", c);
        } else {
            sendToRootBridge("file info " + args, c);
        }
    }

    public static void rootListHandles(String input, CommandExecutionContext c) {
        sendToRootBridge("handles list", c);
    }

    public static void rootStats(String input, CommandExecutionContext c) {
        sendToRootBridge("stats", c);
    }

    public static void rootGc(String input, CommandExecutionContext c) {
        sendToRootBridge("gc", c);
    }

    public static void rootSafeMode(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root safe_mode", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root safe_mode <ON/OFF>");
            return;
        }
        sendToRootBridge("safe-mode " + args, c);
    }

    public static void rootSetOutput(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root set_output", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root set_output <TEXT/JSON>");
            return;
        }
        sendToRootBridge("output set " + args, c);
    }

    public static void rootSetCacheSize(String input, CommandExecutionContext c) {
        if (!c.hasParamValue(":root cache_size")) {
            AppLogger.warn("Usage: :root cache_size=<max_items> or :root cache_size <max_items>");
            return;
        }
        sendToRootBridge("cache size " + c.getCleanParamValue(":root cache_size"), c);
    }

    public static void rootSetCachePolicy(String input, CommandExecutionContext c) {
        String value = c.getCleanParamValue(":root cache_policy");
        if (value.isEmpty()) {
            AppLogger.warn("Usage: :root cache_policy=<NONE/FIFO/LRU>");
            return;
        }
        sendToRootBridge("cache policy " + value, c);
    }

    public static void rootCacheStats(String input, CommandExecutionContext c) {
        sendToRootBridge("cache stats", c);
    }

    public static void rootCacheClear(String input, CommandExecutionContext c) {
        sendToRootBridge("cache clear", c);
    }

    public static void rootSetMaxObjSize(String input, CommandExecutionContext c) {
        if (!c.hasParamValue(":root max_size")) {
            AppLogger.warn("Usage: :root max_size=<bytes> or :root max_size <bytes>");
            return;
        }
        sendToRootBridge("limits obj-size " + c.getCleanParamValue(":root max_size"), c);
    }

    public static void rootSetMaxHandles(String input, CommandExecutionContext c) {
        if (!c.hasParamValue(":root max_handles")) {
            AppLogger.warn("Usage: :root max_handles=<n> or :root max_handles <n>");
            return;
        }
        sendToRootBridge("limits handles " + c.getCleanParamValue(":root max_handles"), c);
    }

    public static void rootSetMaxAge(String input, CommandExecutionContext c) {
        if (!c.hasParamValue(":root max_age")) {
            AppLogger.warn("Usage: :root max_age=<seconds> or :root max_age <seconds>");
            return;
        }
        sendToRootBridge("limits age " + c.getCleanParamValue(":root max_age"), c);
    }

    public static void rootCompileIncludes(String input, CommandExecutionContext c) {
        sendToRootBridge("includes compile", c);
    }

    public static void rootLoadIncludes(String input, CommandExecutionContext c) {
        sendToRootBridge("includes load", c);
    }

    public static void rootCompileScripts(String input, CommandExecutionContext c) {
        sendToRootBridge("script compile", c);
    }

    public static void rootLoadScript(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root load_script", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root load_script <script_name>");
            return;
        }
        sendToRootBridge("script load " + args, c);
    }

    public static void rootRunScript(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root run_script", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root run_script <script_path>");
            return;
        }
        sendToRootBridge("script run " + args, c);
    }

    // --- Bridge Environment & Maintenance Systems ---
    public static void rootReset(String input, CommandExecutionContext c) {
        sendToRootBridge("system reset", c);
    }

    public static void rootBenchmark(String input, CommandExecutionContext c) {
        sendToRootBridge("stats", c);
    }

    public static void rootGetEnv(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root getenv", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root getenv <VAR_NAME>");
            return;
        }
        sendToRootBridge("system exec echo $" + args, c);
    }

    public static void rootPing(String input, CommandExecutionContext c) {
        sendToRootBridge("ping", c);
    }

    // =========================================================================
    // --- EXTENDED ROOT BRIDGE & SYSTEM HANDLERS ---
    // =========================================================================

    // --- Files & Directories (Extended) ---
    public static void rootFileInfo(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file info", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing parameters. Usage: :root file info <file_id>");
            return;
        }
        sendToRootBridge("file info " + args, c);
    }

    public static void rootFileWrite(String input, CommandExecutionContext c) {
        sendToRootBridge("file write", c);
    }

    public static void rootFileKeys(String input, CommandExecutionContext c) {
        sendToRootBridge("file keys", c);
    }

    public static void rootFileCd(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file cd", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing path. Usage: :root file cd <path>");
            return;
        }
        sendToRootBridge("file cd " + args, c);
    }

    public static void rootFilePwd(String input, CommandExecutionContext c) {
        sendToRootBridge("file pwd", c);
    }

    public static void rootFileDir(String input, CommandExecutionContext c) {
        sendToRootBridge("file dir", c);
    }

    public static void rootFileGet(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file get", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root file get <file_id> <name>");
            return;
        }
        sendToRootBridge("file get " + args, c);
    }

    public static void rootFileRecreate(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file recreate", "").trim();
        sendToRootBridge("file recreate " + args, c);
    }

    public static void rootFileOpenUpdate(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file open-update", "").trim();
        sendToRootBridge("file open-update " + args, c);
    }

    public static void rootFileMkdir(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file mkdir", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing directory name. Usage: :root file mkdir <name>");
            return;
        }
        sendToRootBridge("file mkdir " + args, c);
    }

    public static void rootFileRmdir(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file rmdir", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing directory name. Usage: :root file rmdir <name>");
            return;
        }
        sendToRootBridge("file rmdir " + args, c);
    }

    public static void rootFileCopy(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file copy", "").trim();
        sendToRootBridge("file copy " + args, c);
    }

    public static void rootFileMove(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file move", "").trim();
        sendToRootBridge("file move " + args, c);
    }

    public static void rootFileDelete(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root file delete", "").trim();
        sendToRootBridge("file delete " + args, c);
    }

    // --- Histograms (Extended) ---
    public static void rootHistReset(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist reset", "").trim();
        sendToRootBridge("hist reset " + args, c);
    }

    public static void rootHistScale(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist scale", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root hist scale <hist_id> <factor>");
            return;
        }
        sendToRootBridge("hist scale " + args, c);
    }

    public static void rootHistIntegral(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist integral", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing handle. Usage: :root hist integral <hist_id>");
            return;
        }
        sendToRootBridge("hist integral " + args, c);
    }

    public static void rootHistMax(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist max", "").trim();
        sendToRootBridge("hist max " + args, c);
    }

    public static void rootHistMin(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist min", "").trim();
        sendToRootBridge("hist min " + args, c);
    }

    public static void rootHistList(String input, CommandExecutionContext c) {
        sendToRootBridge("hist list", c);
    }

    public static void rootHistSmooth(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist smooth", "").trim();
        sendToRootBridge("hist smooth " + args, c);
    }

    public static void rootHistProject(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist project", "").trim();
        sendToRootBridge("hist project " + args, c);
    }

    public static void rootHistStatbox(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist statbox", "").trim();
        sendToRootBridge("hist statbox " + args, c);
    }

    public static void rootHistSetbin(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist setbin", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root hist setbin <hist_id> <bin> <val>");
            return;
        }
        sendToRootBridge("hist setbin " + args, c);
    }

    public static void rootHistFill(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist fill", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Missing arguments. Usage: :root hist fill <hist_id> <val> [weight]");
            return;
        }
        sendToRootBridge("hist fill " + args, c);
    }

    public static void rootHistClone(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root hist clone", "").trim();
        sendToRootBridge("hist clone " + args, c);
    }

    // --- Generic Objects (Extended) ---
    public static void rootObjClone(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj clone", "").trim();
        sendToRootBridge("obj clone " + args, c);
    }

    public static void rootObjWrite(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj write", "").trim();
        sendToRootBridge("obj write " + args, c);
    }

    public static void rootObjDelete(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj delete", "").trim();
        sendToRootBridge("obj delete " + args, c);
    }

    public static void rootObjMethods(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj methods", "").trim();
        sendToRootBridge("obj methods " + args, c);
    }

    public static void rootObjMembers(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj members", "").trim();
        sendToRootBridge("obj members " + args, c);
    }

    public static void rootObjList(String input, CommandExecutionContext c) {
        sendToRootBridge("obj list", c);
    }

    public static void rootObjClass(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj class", "").trim();
        sendToRootBridge("obj class " + args, c);
    }

    public static void rootObjType(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj type", "").trim();
        sendToRootBridge("obj type " + args, c);
    }

    public static void rootObjPrint(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj print", "").trim();
        sendToRootBridge("obj print " + args, c);
    }

    public static void rootObjInspect(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root obj inspect", "").trim();
        sendToRootBridge("obj inspect " + args, c);
    }

    // --- Trees & Datasets (Extended) ---
    public static void rootTreeProcess(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree process", "").trim();
        sendToRootBridge("tree process " + args, c);
    }

    public static void rootTreeProject(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree project", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root tree project <tree_id> <hist_id> <expr>");
            return;
        }
        sendToRootBridge("tree project " + args, c);
    }

    public static void rootTreeEntries(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree entries", "").trim();
        sendToRootBridge("tree entries " + args, c);
    }

    public static void rootTreeBranches(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree branches", "").trim();
        sendToRootBridge("tree branches " + args, c);
    }

    public static void rootTreeLeaves(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree leaves", "").trim();
        sendToRootBridge("tree leaves " + args, c);
    }

    public static void rootTreeGetentry(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree getentry", "").trim();
        sendToRootBridge("tree getentry " + args, c);
    }

    public static void rootTreeCopytree(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tree copytree", "").trim();
        sendToRootBridge("tree copytree " + args, c);
    }

    // --- Graphs & Canvas (Extended) ---
    public static void rootGraphFit(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root graph fit", "").trim();
        sendToRootBridge("graph fit " + args, c);
    }

    public static void rootGraphPoints(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root graph points", "").trim();
        sendToRootBridge("graph points " + args, c);
    }

    public static void rootGraphAdd(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root graph add", "").trim();
        sendToRootBridge("graph add " + args, c);
    }

    public static void rootCanvasCd(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root canvas cd", "").trim();
        sendToRootBridge("canvas cd " + args, c);
    }

    public static void rootCanvasClear(String input, CommandExecutionContext c) {
        sendToRootBridge("canvas clear", c);
    }

    public static void rootCanvasUpdate(String input, CommandExecutionContext c) {
        sendToRootBridge("canvas update", c);
    }

    public static void rootCanvasList(String input, CommandExecutionContext c) {
        sendToRootBridge("canvas list", c);
    }

    // --- Fitting & Math Engine ---
    public static void rootFitExpr(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root fit expr", "").trim();
        sendToRootBridge("fit expr " + args, c);
    }

    public static void rootFitFunction(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root fit function", "").trim();
        sendToRootBridge("fit function " + args, c);
    }

    public static void rootFitReset(String input, CommandExecutionContext c) {
        sendToRootBridge("fit reset", c);
    }

    public static void rootFitParams(String input, CommandExecutionContext c) {
        sendToRootBridge("fit params", c);
    }

    public static void rootMathEval(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root math eval", "").trim();
        sendToRootBridge("math eval " + args, c);
    }

    public static void rootMathDeriv(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root math deriv", "").trim();
        sendToRootBridge("math deriv " + args, c);
    }

    public static void rootMathIntegral(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root math integral", "").trim();
        sendToRootBridge("math integral " + args, c);
    }

    // --- TMVA & RooFit ---
    public static void rootTmvaFactory(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root tmva factory", "").trim();
        sendToRootBridge("tmva factory " + args, c);
    }

    public static void rootTmvaTrain(String input, CommandExecutionContext c) {
        sendToRootBridge("tmva train", c);
    }

    public static void rootTmvaTest(String input, CommandExecutionContext c) {
        sendToRootBridge("tmva test", c);
    }

    public static void rootTmvaEvaluate(String input, CommandExecutionContext c) {
        sendToRootBridge("tmva evaluate", c);
    }

    public static void rootTmvaGui(String input, CommandExecutionContext c) {
        sendToRootBridge("tmva gui", c);
    }

    public static void rootRoofitWorkspace(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root roofit workspace", "").trim();
        sendToRootBridge("roofit workspace " + args, c);
    }

    public static void rootRoofitPdf(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root roofit pdf", "").trim();
        sendToRootBridge("roofit pdf " + args, c);
    }

    public static void rootRoofitFit(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root roofit fit", "").trim();
        sendToRootBridge("roofit fit " + args, c);
    }

    public static void rootRoofitPlot(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root roofit plot", "").trim();
        sendToRootBridge("roofit plot " + args, c);
    }

    // --- Geometry, SQL, Networking & PROOF ---
    public static void rootGeomLoad(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root geom load", "").trim();
        sendToRootBridge("geom load " + args, c);
    }

    public static void rootGeomDraw(String input, CommandExecutionContext c) {
        sendToRootBridge("geom draw", c);
    }

    public static void rootGeomExport(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root geom export", "").trim();
        sendToRootBridge("geom export " + args, c);
    }

    public static void rootSqlConnect(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root sql connect", "").trim();
        sendToRootBridge("sql connect " + args, c);
    }

    public static void rootSqlQuery(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root sql query", "").trim();
        sendToRootBridge("sql query " + args, c);
    }

    public static void rootSqlDisconnect(String input, CommandExecutionContext c) {
        sendToRootBridge("sql disconnect", c);
    }

    public static void rootNetServer(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root net server", "").trim();
        sendToRootBridge("net server " + args, c);
    }

    public static void rootNetConnect(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root net connect", "").trim();
        sendToRootBridge("net connect " + args, c);
    }

    public static void rootNetSend(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root net send", "").trim();
        sendToRootBridge("net send " + args, c);
    }

    public static void rootProofOpen(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root proof open", "").trim();
        sendToRootBridge("proof open " + args, c);
    }

    public static void rootProofProcess(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root proof process", "").trim();
        sendToRootBridge("proof process " + args, c);
    }

    public static void rootProofStatus(String input, CommandExecutionContext c) {
        sendToRootBridge("proof status", c);
    }

    // --- GUI, PyROOT & System Diagnostics ---
    public static void rootGuiNew(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root gui new", "").trim();
        sendToRootBridge("gui new " + args, c);
    }

    public static void rootGuiShow(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root gui show", "").trim();
        sendToRootBridge("gui show " + args, c);
    }

    public static void rootGuiClose(String input, CommandExecutionContext c) {
        sendToRootBridge("gui close", c);
    }

    public static void rootPyImport(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root py import", "").trim();
        sendToRootBridge("py import " + args, c);
    }

    public static void rootPyEval(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root py eval", "").trim();
        sendToRootBridge("py eval " + args, c);
    }

    public static void rootPyExec(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root py exec", "").trim();
        sendToRootBridge("py exec " + args, c);
    }

    public static void rootSysInfo(String input, CommandExecutionContext c) {
        sendToRootBridge("sys info", c);
    }

    public static void rootSysMemory(String input, CommandExecutionContext c) {
        sendToRootBridge("sys memory", c);
    }

    public static void rootSysPlugins(String input, CommandExecutionContext c) {
        sendToRootBridge("sys plugins", c);
    }

    public static void rootStatus(String input, CommandExecutionContext c) {
        sendToRootBridge("status", c);
    }

    public static void rootDump(String input, CommandExecutionContext c) {
        sendToRootBridge("dump", c);
    }

    // --- Profiling Commands ---
    public static void rootProfile(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root profile", "").trim();
        if (args.isEmpty()) {
            sendToRootBridge("profile", c);
        } else {
            sendToRootBridge("profile " + args, c);
        }
    }

    public static void rootProfileStats(String input, CommandExecutionContext c) {
        sendToRootBridge("profile stats", c);
    }

    public static void rootProfileJson(String input, CommandExecutionContext c) {
        sendToRootBridge("profile json", c);
    }

    public static void rootProfileReset(String input, CommandExecutionContext c) {
        sendToRootBridge("profile reset", c);
    }

    public static void rootProfileLevel(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root profile_level", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root profile_level <basic|advanced|full>");
            return;
        }
        sendToRootBridge("profile level " + args, c);
    }

    // --- Debug & Diagnostic Commands ---
    public static void rootDebugDump(String input, CommandExecutionContext c) {
        sendToRootBridge("debug dump", c);
    }

    public static void rootDebugGraphviz(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root\\s+", "").trim();
        sendToRootBridge(args, c);
    }

    public static void rootDebugAudit(String input, CommandExecutionContext c) {
        sendToRootBridge("debug audit", c);
    }

    public static void rootDebugLevel(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root\\s+", "").trim();
        sendToRootBridge(args, c);
    }

    // =========================================================================
    // --- PROFILING & WATCHDOG DIAGNOSTICS ---
    // =========================================================================

    public static void rootProfilingStatus(String input, CommandExecutionContext c) {
        sendToRootBridge("profile stats", c);
    }

    public static void rootProfilingJson(String input, CommandExecutionContext c) {
        sendToRootBridge("profile json", c);
    }

    public static void rootProfilingReset(String input, CommandExecutionContext c) {
        sendToRootBridge("profile reset", c);
    }

    public static void rootProfilingLevel(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root profiling level", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root profiling level <BASIC|ADVANCED|FULL>");
            return;
        }
        sendToRootBridge("profile level " + args, c);
    }

    public static void rootProfileThreshold(String input, CommandExecutionContext c) {
        String args = input.replaceFirst("^:root profile threshold", "").trim();
        if (args.isEmpty()) {
            AppLogger.warn("Usage: :root profile threshold <ms>");
            return;
        }
        sendToRootBridge("profile threshold " + args, c);
    }

    public static void rootWatchdogStatus(String input, CommandExecutionContext c) {
        sendToRootBridge("watchdog status", c);
    }

    public static void rootWatchdogKill(String input, CommandExecutionContext c) {
        sendToRootBridge("watchdog kill", c);
    }

}