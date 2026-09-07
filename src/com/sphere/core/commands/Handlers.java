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
        String[] args = c.getArgs();
        if (args.length == 0) {
            AppLogger.error("Usage: :edit <file>");
            return;
        }
        java.nio.file.Path target = java.nio.file.Path.of(String.join(" ", args));
        if (!target.isAbsolute() && c.ctx != null && c.ctx.router != null) {
            target = c.ctx.router.getCurrentDirectory().resolve(target);
        }
        if (!java.nio.file.Files.isRegularFile(target)) {
            AppLogger.error("File not found: " + target.toAbsolutePath());
            return;
        }
        java.nio.file.Path opened = target.toAbsolutePath().normalize();
        SwingUtilities.invokeLater(() -> {
            try {
                com.sphere.ui.QuickCodeEditorFrame frame = com.sphere.Sphere.editorWindow();
                if (frame == null) {
                    AppLogger.error("The editor window is not open yet.");
                    return;
                }
                frame.openFileInternally(opened.toFile());
                frame.setVisible(true);
            } catch (Throwable t) {
                AppLogger.error("Editor did not open " + opened + ": " + t.getMessage());
            }
        });
    }

    public static void createNew(String input, CommandExecutionContext c) {
        String[] args = c.getArgs();
        if (args.length == 0) {
            AppLogger.error("Usage: :create new <file>");
            return;
        }
        java.nio.file.Path target = java.nio.file.Path.of(String.join(" ", args));
        if (!target.isAbsolute() && c.ctx != null && c.ctx.router != null) {
            target = c.ctx.router.getCurrentDirectory().resolve(target);
        }
        target = target.toAbsolutePath().normalize();
        if (java.nio.file.Files.exists(target)) {
            AppLogger.error("Already exists: " + target);
            return;
        }
        try {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.createFile(target);
            AppLogger.raw("Created " + target);
        } catch (java.io.IOException ex) {
            AppLogger.error("Could not create " + target + ": " + ex.getMessage());
        }
    }

    // --- Project Commands ---
    public static void projectNew(String input, CommandExecutionContext c) {
        // The folder layout has one owner, the creator window; a second copy here
        // would drift from it at the first change.
        SwingUtilities.invokeLater(() -> {
            try {
                new com.sphere.components.workspace.ProjectCreatorWindow(null).setVisible(true);
            } catch (Throwable t) {
                AppLogger.error("Project creator did not open: " + t.getMessage());
            }
        });
    }

    public static void projectOpen(String input, CommandExecutionContext c) {
        setActiveProject(c, "open");
    }

    public static void projectClose(String input, CommandExecutionContext c) {
        if (c.ctx == null || c.ctx.getActiveProject() == null) {
            AppLogger.raw("No active project.");
            return;
        }
        String previous = c.ctx.getActiveProject();
        c.ctx.setActiveProject(null);
        AppLogger.raw("Closed " + previous);
    }

    public static void projectSet(String input, CommandExecutionContext c) {
        setActiveProject(c, "set");
    }

    public static void projectInfo(String input, CommandExecutionContext c) {
        String name = c.ctx == null ? null : c.ctx.getActiveProject();
        String[] args = c.getArgs();
        if (args.length > 0) {
            name = String.join(" ", args);
        }
        if (name == null) {
            AppLogger.raw("No active project. Use  :project open <name>");
            return;
        }
        java.nio.file.Path project = workspaceRoot().resolve(name);
        if (!java.nio.file.Files.isDirectory(project)) {
            AppLogger.error("No project named " + name + " under " + workspaceRoot().toAbsolutePath());
            return;
        }
        long[] tally = tallyTree(project);
        AppLogger.raw("  name       " + name);
        AppLogger.raw("  path       " + project.toAbsolutePath());
        AppLogger.raw("  files      " + tally[0] + " in " + tally[1] + " folders");
        AppLogger.raw("  size       " + humanBytes(tally[2]));
        for (String marker : new String[]{".projectsettings", ".workflow", ".presets",
                                          "CMakeLists.txt", "README.md"}) {
            AppLogger.raw(String.format("  %-10s %s", marker,
                java.nio.file.Files.isRegularFile(project.resolve(marker)) ? "yes" : "no"));
        }
    }

    public static void projectList(String input, CommandExecutionContext c) {
        java.util.List<java.nio.file.Path> projects = workspaceProjects();
        if (projects == null) {
            return;
        }
        if (projects.isEmpty()) {
            AppLogger.raw("No project under " + workspaceRoot().toAbsolutePath());
            return;
        }
        String active = c.ctx == null ? null : c.ctx.getActiveProject();
        for (java.nio.file.Path project : projects) {
            String name = project.getFileName().toString();
            AppLogger.raw(String.format("  %s %-28s %s",
                name.equals(active) ? "*" : " ", name,
                java.nio.file.Files.isRegularFile(project.resolve(".projectsettings"))
                    ? "" : "(no .projectsettings)"));
        }
    }

    public static void projectDelete(String input, CommandExecutionContext c) {
        String[] args = c.getArgs();
        if (args.length == 0) {
            AppLogger.error("Usage: :project delete <name> --confirm");
            return;
        }
        boolean confirmed = java.util.Arrays.asList(args).contains("--confirm");
        String name = args[0];
        java.nio.file.Path project = workspaceRoot().resolve(name);
        if (!java.nio.file.Files.isDirectory(project)) {
            AppLogger.error("No project named " + name);
            return;
        }
        if (!confirmed) {
            long[] tally = tallyTree(project);
            AppLogger.raw("This deletes " + project.toAbsolutePath());
            AppLogger.raw(tally[0] + " files, " + humanBytes(tally[2]) + ", permanently.");
            AppLogger.raw("Type  :project delete " + name + " --confirm  to go ahead.");
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(project)) {
            java.util.List<java.nio.file.Path> all =
                walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (java.nio.file.Path f : all) {
                java.nio.file.Files.deleteIfExists(f);
            }
            if (c.ctx != null && name.equals(c.ctx.getActiveProject())) {
                c.ctx.setActiveProject(null);
            }
            AppLogger.raw("Deleted " + project.toAbsolutePath());
        } catch (java.io.IOException ex) {
            AppLogger.error("Deletion stopped: " + ex.getMessage());
        }
    }

    // --- Workspace Commands ---
    public static void workspaceScan(String input, CommandExecutionContext c) {
        java.util.List<java.nio.file.Path> projects = workspaceProjects();
        if (projects == null) {
            return;
        }
        int complete = 0;
        int loose = 0;
        for (java.nio.file.Path project : projects) {
            if (java.nio.file.Files.isRegularFile(project.resolve(".projectsettings"))) {
                complete++;
            } else {
                loose++;
                AppLogger.raw("  untracked  " + project.getFileName()
                    + "  (no .projectsettings)");
            }
        }
        AppLogger.raw("  " + complete + " tracked, " + loose + " untracked, in "
            + workspaceRoot().toAbsolutePath());
    }

    public static void workspaceClean(String input, CommandExecutionContext c) {
        java.util.List<java.nio.file.Path> projects = workspaceProjects();
        if (projects == null) {
            return;
        }
        boolean confirmed = java.util.Arrays.asList(c.getArgs()).contains("--confirm");
        java.util.List<java.nio.file.Path> victims = new java.util.ArrayList<>();
        long bytes = 0;
        for (java.nio.file.Path project : projects) {
            try (java.util.stream.Stream<java.nio.file.Path> walk =
                    java.nio.file.Files.walk(project)) {
                for (java.nio.file.Path f : walk.toList()) {
                    String name = f.getFileName().toString();
                    boolean junk = name.equals("__pycache__") || name.equals(".pytest_cache")
                        || name.endsWith(".o") || name.endsWith(".obj") || name.endsWith(".class")
                        || name.endsWith(".pyc") || name.endsWith(".d")
                        || (java.nio.file.Files.isDirectory(f)
                            && (name.equals("build") || name.equals("bin")));
                    if (junk) {
                        victims.add(f);
                        long[] tally = java.nio.file.Files.isDirectory(f)
                            ? tallyTree(f) : new long[]{1, 0, java.nio.file.Files.size(f)};
                        bytes += tally[2];
                    }
                }
            } catch (java.io.IOException ex) {
                AppLogger.error(project.getFileName() + ": " + ex.getMessage());
            }
        }
        if (victims.isEmpty()) {
            AppLogger.raw("Nothing to clean.");
            return;
        }
        if (!confirmed) {
            for (java.nio.file.Path v : victims) {
                AppLogger.raw("  " + workspaceRoot().relativize(v));
            }
            AppLogger.raw(victims.size() + " entries, " + humanBytes(bytes)
                + ". Type  :workspace clean --confirm  to remove them.");
            return;
        }
        int removed = 0;
        for (java.nio.file.Path v : victims) {
            try (java.util.stream.Stream<java.nio.file.Path> walk =
                    java.nio.file.Files.walk(v)) {
                for (java.nio.file.Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (java.nio.file.Files.deleteIfExists(f)) {
                        removed++;
                    }
                }
            } catch (java.io.IOException ex) {
                AppLogger.error("Could not remove " + v + ": " + ex.getMessage());
            }
        }
        AppLogger.raw(removed + " entries removed, " + humanBytes(bytes) + " freed.");
    }

    public static void workspaceDiag(String input, CommandExecutionContext c) {
        java.nio.file.Path root = workspaceRoot();
        if (!java.nio.file.Files.isDirectory(root)) {
            AppLogger.error("No WorkSpace folder at " + root.toAbsolutePath());
            return;
        }
        AppLogger.raw("  root       " + root.toAbsolutePath());
        AppLogger.raw("  writable   " + java.nio.file.Files.isWritable(root));
        java.util.List<java.nio.file.Path> projects = workspaceProjects();
        long[] tally = tallyTree(root);
        AppLogger.raw("  projects   " + (projects == null ? 0 : projects.size()));
        AppLogger.raw("  files      " + tally[0] + " in " + tally[1] + " folders");
        AppLogger.raw("  size       " + humanBytes(tally[2]));
        try {
            java.nio.file.FileStore store = java.nio.file.Files.getFileStore(root);
            AppLogger.raw("  free space " + humanBytes(store.getUsableSpace())
                + " of " + humanBytes(store.getTotalSpace()));
        } catch (java.io.IOException ex) {
            AppLogger.error("Free space unknown: " + ex.getMessage());
        }
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
        if (c == null || c.ctx == null || c.ctx.backends == null || c.ctx.backends.isEmpty()) {
            AppLogger.error("No backend is registered.");
            return;
        }
        c.ctx.backends.forEach((name, backend) -> AppLogger.raw(String.format("  %-10s %s",
            name, backend == null ? "not loaded" : backend.getClass().getSimpleName())));
    }

    public static void backendDiag(String input, CommandExecutionContext c) {
        if (c == null || c.ctx == null || c.ctx.backends == null) {
            AppLogger.error("No backend is registered.");
            return;
        }
        com.sphere.utils.SettingsManager sm = new com.sphere.utils.SettingsManager();
        String[][] probes = {
            {"python", "PYTHON_EXEC", "python3"},
            {"cpp", "GPP_DIR", "g++"},
            {"js", "NODE_DIR", "node"},
        };
        for (String[] probe : probes) {
            Object backend = c.ctx.backends.get(probe[0]);
            String tool = sm.isDeclaredEmpty(probe[1]) ? null : sm.resolveTool(probe[1], probe[2]);
            AppLogger.raw(String.format("  %-8s %-14s %s", probe[0],
                backend == null ? "not loaded" : "loaded",
                tool == null ? (sm.isDeclaredEmpty(probe[1])
                    ? probe[1] + " is empty in settings.conf" : probe[2] + " not found")
                    : tool));
        }
    }

    public static void backendReload(String input, CommandExecutionContext c) {
        com.sphere.utils.SettingsManager sm = new com.sphere.utils.SettingsManager();
        com.sphere.components.terminal.ConfigLoader.load(sm);
        AppLogger.raw("settings.conf reread. Backends already running keep their current tools;");
        AppLogger.raw("a new terminal or a restarted backend picks up the new values.");
    }

    // --- Configuration Commands ---
    public static void configShow(String input, CommandExecutionContext c) {
        com.sphere.utils.SettingsManager sm = new com.sphere.utils.SettingsManager();
        java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> all =
            sm.getSequentialStructure();
        if (all.isEmpty()) {
            AppLogger.error("No settings.conf found in " + java.nio.file.Path.of("").toAbsolutePath());
            return;
        }
        for (java.util.Map.Entry<String, java.util.List<java.util.Map.Entry<String, String>>> section
                : all.entrySet()) {
            AppLogger.raw("[" + section.getKey() + "]");
            for (java.util.Map.Entry<String, String> kv : section.getValue()) {
                String value = kv.getValue();
                AppLogger.raw("  " + kv.getKey() + " = "
                    + (value == null || value.isBlank() ? "(disabled)" : value));
            }
        }
    }

    public static void configEdit(String input, CommandExecutionContext c) {
        java.nio.file.Path conf = java.nio.file.Path.of(
            com.sphere.utils.SettingsManager.CONFIG_FILENAME).toAbsolutePath();
        if (!java.nio.file.Files.isReadable(conf)) {
            AppLogger.error("No settings.conf to edit at " + conf);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                new com.sphere.utils.settingsmanager.SettingsEditorWindow(conf).setVisible(true);
            } catch (Exception ex) {
                AppLogger.error("Settings editor did not open: " + ex.getMessage());
            }
        });
    }

    public static void configReset(String input, CommandExecutionContext c) {
        // Destructive: the file is only moved aside, and only when asked twice.
        java.nio.file.Path conf = java.nio.file.Path.of(
            com.sphere.utils.SettingsManager.CONFIG_FILENAME).toAbsolutePath();
        String[] args = c.getArgs();
        boolean confirmed = args.length > 0 && args[0].equals("--confirm");
        if (!confirmed) {
            AppLogger.raw("This moves " + conf + " aside and lets Sphere rebuild it at the");
            AppLogger.raw("next start. Your declared paths are kept in the backup file.");
            AppLogger.raw("Type  :config reset --confirm  to go ahead.");
            return;
        }
        try {
            if (!java.nio.file.Files.exists(conf)) {
                AppLogger.error("Nothing to reset: " + conf + " does not exist.");
                return;
            }
            java.nio.file.Path backup = conf.resolveSibling("settings.conf.reset-"
                + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            java.nio.file.Files.move(conf, backup);
            AppLogger.raw("Moved to " + backup + ". Restart Sphere to rebuild a fresh file.");
        } catch (java.io.IOException ex) {
            AppLogger.error("Reset failed: " + ex.getMessage());
        }
    }

    // --- Logging Commands ---
    public static void logLevel(String input, CommandExecutionContext c) {
        String[] args = c.getArgs();
        if (args.length == 0) {
            AppLogger.raw("Logging level: " + (AppLogger.isDebugEnabled() ? "debug" : "normal"));
            AppLogger.raw("Use  :log level debug  or  :log level normal");
            return;
        }
        String wanted = args[0].toLowerCase(java.util.Locale.ROOT);
        if (wanted.equals("debug") || wanted.equals("verbose")) {
            AppLogger.setDebugEnabled(true);
            AppLogger.raw("Logging level: debug");
        } else if (wanted.equals("normal") || wanted.equals("info") || wanted.equals("off")) {
            AppLogger.setDebugEnabled(false);
            AppLogger.raw("Logging level: normal");
        } else {
            AppLogger.error("Unknown level: " + args[0] + ". Use debug or normal.");
        }
    }

    public static void logTail(String input, CommandExecutionContext c) {
        java.util.List<java.nio.file.Path> sessions = com.sphere.utils.SessionManager.getAllSessions();
        if (sessions.isEmpty()) {
            AppLogger.error("No session log found.");
            return;
        }
        java.nio.file.Path latest = sessions.get(sessions.size() - 1);
        int count = 40;
        String[] args = c.getArgs();
        if (args.length > 0) {
            try {
                count = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException ex) {
                AppLogger.error("Not a number: " + args[0]);
                return;
            }
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                latest, java.nio.charset.StandardCharsets.UTF_8);
            AppLogger.raw(latest.getFileName() + ", last " + Math.min(count, lines.size())
                          + " of " + lines.size() + " lines:");
            for (String line : lines.subList(Math.max(0, lines.size() - count), lines.size())) {
                AppLogger.raw("  " + line);
            }
        } catch (java.io.IOException ex) {
            AppLogger.error("Session log could not be read: " + ex.getMessage());
        }
    }

    public static void logClear(String input, CommandExecutionContext c) {
        AppLogger.clear();
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
        reportTool("python", "PYTHON_EXEC", "python3", "--version");
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
        reportTool("cpp", "GPP_DIR", "g++", "--version");
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
        reportTool("js", "NODE_DIR", "node", "-p", "process.versions.v8");
    }

    public static void jsDiag(String input, CommandExecutionContext c) {
        reportTool("js", "NODE_DIR", "node", "--version");
    }

    // --- Snippet & Tool Commands ---
    public static void snippetList(String input, CommandExecutionContext c) {
        java.nio.file.Path root = java.nio.file.Path.of("snippets");
        if (!java.nio.file.Files.isDirectory(root)) {
            AppLogger.error("No snippets folder at " + root.toAbsolutePath());
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(root)) {
            java.util.List<java.nio.file.Path> files = walk
                .filter(java.nio.file.Files::isRegularFile)
                .sorted()
                .toList();
            if (files.isEmpty()) {
                AppLogger.raw("No snippet indexed under " + root.toAbsolutePath());
                return;
            }
            for (java.nio.file.Path f : files) {
                AppLogger.raw(String.format("  %-40s %d bytes",
                    root.relativize(f), java.nio.file.Files.size(f)));
            }
        } catch (java.io.IOException ex) {
            AppLogger.error("Snippets could not be listed: " + ex.getMessage());
        }
    }

    public static void snippetInfo(String input, CommandExecutionContext c) {
        String[] args = c.getArgs();
        if (args.length == 0) {
            AppLogger.error("Usage: :snippet info <name>");
            return;
        }
        java.nio.file.Path root = java.nio.file.Path.of("snippets");
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(root)) {
            java.nio.file.Path found = walk
                .filter(java.nio.file.Files::isRegularFile)
                .filter(f -> f.getFileName().toString().contains(args[0]))
                .findFirst().orElse(null);
            if (found == null) {
                AppLogger.error("No snippet matching: " + args[0]);
                return;
            }
            AppLogger.raw(found.toAbsolutePath().toString());
            AppLogger.raw(java.nio.file.Files.size(found) + " bytes, modified "
                + java.nio.file.Files.getLastModifiedTime(found));
            for (String line : java.nio.file.Files.readAllLines(
                    found, java.nio.charset.StandardCharsets.UTF_8)) {
                AppLogger.raw("  " + line);
            }
        } catch (java.io.IOException ex) {
            AppLogger.error("Snippet could not be read: " + ex.getMessage());
        }
    }

    public static void snippetReload(String input, CommandExecutionContext c) {
        snippetList(input, c);
    }

    public static void toolsDiag(String input, CommandExecutionContext c) {
        com.sphere.utils.StartupDiagnostic.run(new com.sphere.utils.SettingsManager());
    }

    public static void toolsList(String input, CommandExecutionContext c) {
        com.sphere.utils.SettingsManager sm = new com.sphere.utils.SettingsManager();
        java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> all =
            sm.getSequentialStructure();
        boolean any = false;
        for (String section : new String[]{"SYSTEM_PATH", "GENERAL"}) {
            java.util.List<java.util.Map.Entry<String, String>> entries = all.get(section);
            if (entries == null) {
                continue;
            }
            for (java.util.Map.Entry<String, String> kv : entries) {
                any = true;
                String key = kv.getKey();
                String declared = kv.getValue();
                String resolved;
                if (sm.isDeclaredEmpty(key)) {
                    resolved = "disabled in settings.conf";
                } else {
                    String found = sm.resolveTool(key, null);
                    resolved = found == null ? "not found" : found;
                }
                AppLogger.raw(String.format("  %-20s %-40s %s", key,
                    declared == null || declared.isBlank() ? "(empty)" : declared, resolved));
            }
        }
        if (!any) {
            AppLogger.error("No [SYSTEM_PATH] or [GENERAL] section in settings.conf.");
        }
    }

    public static void toolsUpdate(String input, CommandExecutionContext c) {
        // Rereads settings.conf and reports what each declared tool resolves to now.
        toolsList(input, c);
    }

    /** Projects live in WorkSpace/, one folder each. */
    private static java.nio.file.Path workspaceRoot() {
        return java.nio.file.Path.of("WorkSpace");
    }

    private static java.util.List<java.nio.file.Path> workspaceProjects() {
        java.nio.file.Path root = workspaceRoot();
        if (!java.nio.file.Files.isDirectory(root)) {
            AppLogger.error("No WorkSpace folder at " + root.toAbsolutePath());
            return null;
        }
        try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(root)) {
            return list.filter(java.nio.file.Files::isDirectory).sorted().toList();
        } catch (java.io.IOException ex) {
            AppLogger.error("WorkSpace could not be read: " + ex.getMessage());
            return null;
        }
    }

    private static void setActiveProject(CommandExecutionContext c, String verb) {
        String[] args = c.getArgs();
        if (args.length == 0) {
            AppLogger.error("Usage: :project " + verb + " <name>");
            return;
        }
        String name = String.join(" ", args);
        java.nio.file.Path project = workspaceRoot().resolve(name);
        if (!java.nio.file.Files.isDirectory(project)) {
            AppLogger.error("No project named " + name + " under "
                + workspaceRoot().toAbsolutePath());
            return;
        }
        if (c.ctx != null) {
            c.ctx.setActiveProject(name);
        }

        // Opening a project brings its own includes/ and user_scripts/ into play,
        // on top of the global pair. The folders are created on first use.
        com.sphere.core.rootbackend.RootUserPipeline.ensureLayout(project.toAbsolutePath());
        com.sphere.core.rootbackend.RootBackend.setActivePipelineProject(name);
        com.sphere.core.rootbackend.RootBackend engine = backend(c);
        if (engine != null && engine.isAvailable()) {
            com.sphere.core.rootbackend.RootUserPipeline.loadInto(engine, name);
        }

        AppLogger.raw("Active project: " + name + "  (" + project.toAbsolutePath() + ")");
    }

    /** files, folders, bytes. */
    private static long[] tallyTree(java.nio.file.Path root) {
        long files = 0;
        long folders = 0;
        long bytes = 0;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path f : walk.toList()) {
                if (java.nio.file.Files.isDirectory(f)) {
                    folders++;
                } else {
                    files++;
                    try {
                        bytes += java.nio.file.Files.size(f);
                    } catch (java.io.IOException ignored) {
                        // a file that vanished between the walk and the read
                    }
                }
            }
        } catch (java.io.IOException ex) {
            AppLogger.error(root.getFileName() + " could not be walked: " + ex.getMessage());
        }
        return new long[]{files, Math.max(0, folders - 1), bytes};
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** Runs a declared tool with the given arguments and prints its first answer. */
    private static void reportTool(String label, String key, String fallback, String... args) {
        com.sphere.utils.SettingsManager sm = new com.sphere.utils.SettingsManager();
        if (sm.isDeclaredEmpty(key)) {
            AppLogger.raw("  " + label + ": " + key + " is empty in settings.conf, which disables it.");
            return;
        }
        String tool = sm.resolveTool(key, fallback);
        if (tool == null) {
            AppLogger.error(label + ": " + fallback + " not found. Set " + key + " in settings.conf.");
            return;
        }
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(tool);
        command.addAll(java.util.Arrays.asList(args));
        try {
            Process probe = new ProcessBuilder(command).redirectErrorStream(true).start();
            String answer;
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(probe.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
                answer = in.readLine();
            }
            if (!probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                probe.destroyForcibly();
            }
            AppLogger.raw("  " + label + ": " + tool);
            AppLogger.raw("  " + " ".repeat(label.length()) + "  " + (answer == null ? "(no answer)" : answer));
        } catch (java.io.IOException ex) {
            AppLogger.error(label + ": " + tool + " did not run: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
        String a0 = macroPath(a, c);
        if (a0 == null) return;
        cling(c, "gROOT->LoadMacro(\"" + a0 + "\")");
    }

    public static void rootRunScript(String i, CommandExecutionContext c) {
        String a = args(i, ":root script run");
        if (a.isEmpty()) {
            usage(":root script run <file>");
            return;
        }
        String a0 = macroPath(a, c);
        if (a0 == null) return;
        cling(c, "gROOT->ProcessLine(\".x " + a0 + "\")");
    }

    public static void rootCompileScripts(String i, CommandExecutionContext c) {
        String a = args(i, ":root script compile");
        if (a.trim().equals("--all")) {
            rootScriptCompileAll(i, c);
            return;
        }
        if (a.isEmpty()) {
            usage(":root script compile <file> | --all");
            return;
        }
        String a0 = macroPath(a, c);
        if (a0 == null) return;
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

    /**
     * A macro named on its own is looked up in user_scripts/, the project's
     * folder first and then the global one, before being treated as a path.
     */
    private static String macroPath(String argument, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        java.nio.file.Path found =
            com.sphere.core.rootbackend.RootUserPipeline.resolveMacro(argument, project);
        if (found == null) {
            AppLogger.error("No macro named " + argument
                + ". Use  :root script list  to see what is in user_scripts/.");
            return null;
        }
        return com.sphere.core.rootbackend.RootUserPipeline.forCling(found);
    }

    /** The macros of both layers, the project marked, so the user sees what runs. */
    public static void rootScriptList(String i, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        int shown = 0;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            java.util.List<java.nio.file.Path> found =
                com.sphere.core.rootbackend.RootUserPipeline.macros(root);
            if (found.isEmpty()) continue;
            AppLogger.raw("  " + root.resolve(
                com.sphere.core.rootbackend.RootUserPipeline.SCRIPTS_DIR));
            for (java.nio.file.Path macro : found) {
                AppLogger.raw(String.format("      %-32s %s",
                    macro.getFileName(), humanBytes(sizeOf(macro))));
                shown++;
            }
        }
        if (shown == 0) {
            AppLogger.info("No macro yet. Put a .C or .cpp in user_scripts/.");
        } else {
            AppLogger.raw("  " + shown + " macros. A later folder overrides an earlier name.");
        }
    }

    /** The libraries of both layers, and the C++ sources waiting to be built. */
    public static void rootIncludesList(String i, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        int libraries = 0, sources = 0;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            java.util.List<java.nio.file.Path> found =
                com.sphere.core.rootbackend.RootUserPipeline.libraries(root);
            java.util.List<java.nio.file.Path> toBuild =
                com.sphere.core.rootbackend.RootUserPipeline.sources(root);
            if (found.isEmpty() && toBuild.isEmpty()) continue;
            AppLogger.raw("  " + root.resolve(
                com.sphere.core.rootbackend.RootUserPipeline.INCLUDES_DIR));
            for (java.nio.file.Path library : found) {
                AppLogger.raw(String.format("      %-32s %s  loaded at startup",
                    library.getFileName(), humanBytes(sizeOf(library))));
                libraries++;
            }
            for (java.nio.file.Path source : toBuild) {
                AppLogger.raw(String.format("      %-32s %s  source, :root includes build",
                    source.getFileName(), humanBytes(sizeOf(source))));
                sources++;
            }
        }
        if (libraries + sources == 0) {
            AppLogger.info("Nothing in includes/ yet. A .so there is loaded at startup.");
        }

        com.sphere.core.rootbackend.RootUserCompiler compiler =
            new com.sphere.core.rootbackend.RootUserCompiler(
                new com.sphere.utils.SettingsManager(), backend(c));
        AppLogger.raw("  compiler    " + String.valueOf(compiler.compiler()));
        AppLogger.raw("  flags from  " + compiler.flagSource());
        String rootCompiler = compiler.rootBuildCompiler();
        if (rootCompiler != null) {
            AppLogger.raw("  ROOT built with " + rootCompiler);
        }
    }

    /** Loads again what is in includes/, for a library rebuilt while Sphere runs. */
    public static void rootIncludesReload(String i, CommandExecutionContext c) {
        com.sphere.core.rootbackend.RootBackend b = backend(c);
        if (b == null) {
            AppLogger.error("The ROOT engine is not running.");
            return;
        }
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        com.sphere.core.rootbackend.RootBackend.setActivePipelineProject(project);
        var outcome = com.sphere.core.rootbackend.RootUserPipeline.loadInto(b, project);
        if (outcome.total() == 0) {
            AppLogger.info("Nothing to load in includes/.");
        }
        for (String problem : outcome.problems()) {
            AppLogger.raw("      " + problem);
        }
    }

    /**
     * Builds the C++ of includes/ into shared libraries. One named source, or
     * every source with --all, and only what changed unless --force is given.
     */
    public static void rootIncludesBuild(String i, CommandExecutionContext c) {
        String a = args(i, ":root includes build").trim();
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;

        boolean all = a.contains("--all");
        boolean force = a.contains("--force");
        String named = a.replace("--all", "").replace("--force", "").trim();

        if (!all && named.isEmpty()) {
            AppLogger.raw("Usage: :root includes build <file.cpp> | --all [--force]");
            AppLogger.raw("  --all      build every source of includes/, both layers");
            AppLogger.raw("  --force    build even when the library is already newer");
            return;
        }

        java.util.List<java.nio.file.Path> queue = new java.util.ArrayList<>();
        if (all) {
            for (java.nio.file.Path root
                    : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
                for (java.nio.file.Path source
                        : com.sphere.core.rootbackend.RootUserPipeline.sources(root)) {
                    if (force
                        || com.sphere.core.rootbackend.RootUserCompiler.needsBuilding(source)) {
                        queue.add(source);
                    }
                }
            }
            if (queue.isEmpty()) {
                AppLogger.info("Nothing to build. Everything in includes/ is up to date.");
                return;
            }
        } else {
            java.nio.file.Path source = findSource(named, project);
            if (source == null) {
                AppLogger.error("No source named " + named + " in includes/.");
                return;
            }
            queue.add(source);
        }

        runBuilds(queue, project, c);
    }

    /** A source of includes/ by bare name, the project layer winning. */
    private static java.nio.file.Path findSource(String name, String project) {
        String wanted = name.replace("\"", "").trim();
        java.nio.file.Path found = null;
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            java.nio.file.Path candidate = root
                .resolve(com.sphere.core.rootbackend.RootUserPipeline.INCLUDES_DIR)
                .resolve(wanted);
            if (java.nio.file.Files.isRegularFile(candidate)) found = candidate;
        }
        if (found != null) return found;
        java.nio.file.Path direct = java.nio.file.Path.of(wanted);
        return java.nio.file.Files.isRegularFile(direct) ? direct.toAbsolutePath() : null;
    }

    /** Compiles a queue off the event thread, then reloads what was produced. */
    private static void runBuilds(java.util.List<java.nio.file.Path> queue,
                                  String project, CommandExecutionContext c) {
        AppLogger.info("Building " + queue.size()
            + (queue.size() == 1 ? " source..." : " sources..."));

        new javax.swing.SwingWorker<java.util.List<
                com.sphere.core.rootbackend.RootUserCompiler.Build>, String>() {
            @Override
            protected java.util.List<com.sphere.core.rootbackend.RootUserCompiler.Build>
                    doInBackground() {
                // The engine is handed over so its own build settings are used
                // rather than a root-config that may not even be installed.
                com.sphere.core.rootbackend.RootUserCompiler compiler =
                    new com.sphere.core.rootbackend.RootUserCompiler(
                        new com.sphere.utils.SettingsManager(), backend(c));

                publish("      flags from " + compiler.flagSource());
                String rootCompiler = compiler.rootBuildCompiler();
                String ours = compiler.compiler();
                if (rootCompiler != null && ours != null
                        && !sameCompilerFamily(rootCompiler, ours)) {
                    // A library built by another compiler can load and then fail
                    // on a symbol, which is hard to read from the other end.
                    publish("[W] ROOT was built with " + rootCompiler
                            + ", this builds with " + ours);
                }
                java.util.List<com.sphere.core.rootbackend.RootUserCompiler.Build> done =
                    new java.util.ArrayList<>();
                for (java.nio.file.Path source : queue) {
                    com.sphere.core.rootbackend.RootUserCompiler.Build built =
                        compiler.build(source, null);
                    done.add(built);
                    publish((built.succeeded() ? "[+] " : "[!] ") + source.getFileName()
                            + (built.succeeded()
                               ? "  ->  " + built.output().getFileName()
                               : ""));
                    // The compiler's own words, which used to go to a terminal
                    // that does not exist when Sphere runs from a .jar
                    if (!built.succeeded()) {
                        for (String line : built.lines()) publish("      " + line);
                    }
                }
                return done;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("[+] ")) AppLogger.success(line.substring(4));
                    else if (line.startsWith("[!] ")) AppLogger.error(line.substring(4));
                    else if (line.startsWith("[W] ")) AppLogger.warn(line.substring(4));
                    else AppLogger.raw(line);
                }
            }

            @Override
            protected void done() {
                java.util.List<com.sphere.core.rootbackend.RootUserCompiler.Build> results;
                try { results = get(); } catch (Exception e) { return; }
                long good = results.stream().filter(
                    com.sphere.core.rootbackend.RootUserCompiler.Build::succeeded).count();
                long bad = results.size() - good;
                AppLogger.raw("  " + good + " built" + (bad > 0 ? ", " + bad + " failed" : "") + ".");

                if (good > 0) {
                    com.sphere.core.rootbackend.RootBackend engine = backend(c);
                    if (engine != null && engine.isAvailable()) {
                        com.sphere.core.rootbackend.RootUserPipeline.loadInto(engine, project);
                    }
                }
            }
        }.execute();
    }

    /**
     * Compiles the macros of user_scripts/ with ACLiC, inside the interpreter.
     * One named macro, or every macro with --all.
     */
    public static void rootScriptCompileAll(String i, CommandExecutionContext c) {
        String project = c != null && c.ctx != null ? c.ctx.getActiveProject() : null;
        com.sphere.core.rootbackend.RootBackend engine = backend(c);
        if (engine == null || !engine.isAvailable()) {
            AppLogger.error("ACLiC compiles inside the interpreter, "
                + "so the ROOT engine has to be running.");
            return;
        }

        java.util.List<java.nio.file.Path> queue = new java.util.ArrayList<>();
        for (java.nio.file.Path root
                : com.sphere.core.rootbackend.RootUserPipeline.layers(project)) {
            queue.addAll(com.sphere.core.rootbackend.RootUserPipeline.macros(root));
        }
        if (queue.isEmpty()) {
            AppLogger.info("No macro in user_scripts/ to compile.");
            return;
        }

        AppLogger.info("Compiling " + queue.size() + " macros with ACLiC...");
        for (java.nio.file.Path macro : queue) {
            String path = com.sphere.core.rootbackend.RootUserPipeline.forCling(macro);
            cling(c, "gROOT->LoadMacro(\"" + path + "+\")");
        }
    }

    /** Builds both folders in order: the libraries first, then the macros. */
    public static void rootBuildAll(String i, CommandExecutionContext c) {
        AppLogger.info("Building includes/ then user_scripts/.");
        rootIncludesBuild(":root includes build --all", c);
        // The macros come after, so ACLiC already has the libraries it may need.
        rootScriptCompileAll(":root script compile --all", c);
    }

    /** Same compiler family, ignoring the path and the version suffix. */
    private static boolean sameCompilerFamily(String a, String b) {
        return family(a).equals(family(b));
    }

    private static String family(String compiler) {
        String name = java.nio.file.Path.of(compiler.trim().split("\\s+")[0])
                        .getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        name = name.replaceAll("\\.exe$", "").replaceAll("-?\\d+(\\.\\d+)*$", "");
        if (name.contains("clang")) return "clang";
        if (name.contains("g++") || name.contains("gcc")) return "gcc";
        return name;
    }

    private static long sizeOf(java.nio.file.Path p) {
        try { return java.nio.file.Files.size(p); } catch (java.io.IOException e) { return 0L; }
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
