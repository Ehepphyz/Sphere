package com.sphere.core.commands;

import com.sphere.utils.AppLogger;
import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.Optional;

/**
 * Handles core command logic for the Sphere platform.
 * Logic is delegated to domain-specific services or bridge handlers.
 */
public class Handlers {

    // --- Core Commands ---
    /**
     * ":help" lists every command by category, ":help <category>" only that one.
     *
     * A word that names no category is looked for inside the names and the
     * descriptions, so ":help histogram" finds what it should even though no
     * command starts with that word.
     */
    public static void help(String input, CommandExecutionContext c) {
        final Map<String, String> commands = CommandDefinitions.allForHelp();
        final String[] args = (c == null) ? null : c.getArgs();
        final String wanted = (args == null || args.length == 0)
            ? "" : String.join(" ", args).trim();

        if (wanted.isEmpty() || "all".equalsIgnoreCase(wanted)) {
            helpEverything(commands);
        } else {
            helpCategory(commands, wanted);
        }
    }

    /** Every command, under the heading of its category. */
    static void helpEverything(Map<String, String> commands) {
        AppLogger.info("Commands by category. ':help <category>' shows one of them.");
        java.util.Map<String, java.util.List<String>> categories = CommandDefinitions.sections();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : categories.entrySet()) {
            AppLogger.raw("");
            AppLogger.raw("  [" + entry.getKey() + "]");
            for (String name : entry.getValue()) {
                AppLogger.raw("    " + name + " - " + commands.getOrDefault(name, ""));
            }
        }
    }

    /** One category, one branch inside it, or whatever the word turns up. */
    static void helpCategory(Map<String, String> commands, String wanted) {
        final String query = wanted.replaceFirst("^:+", "").trim().toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, java.util.List<String>> categories = CommandDefinitions.sections();

        java.util.List<String> picked = categories.get(helpCategoryFor(query));
        if (picked == null) {
            // Not a category: the start of a command, so ":help root hist" works.
            final String prefix = ":" + query;
            picked = new java.util.ArrayList<>();
            for (String name : commands.keySet()) {
                if (name.equals(prefix) || name.startsWith(prefix + " ")) {
                    picked.add(name);
                }
            }
        }
        if (!picked.isEmpty()) {
            list(commands, picked, query);
            return;
        }

        // Still nothing: look for the word in the names and in what they say.
        java.util.List<String> found = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            if (entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(query)
                || entry.getValue().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                found.add(entry.getKey());
            }
        }
        if (found.isEmpty()) {
            AppLogger.error("Nothing about '" + query + "'. ':help' lists the categories.");
            return;
        }
        list(commands, found, query);
    }

    /**
     * The category a word stands for.
     *
     * Categories are named after the first word of their commands, which is not
     * always the word that comes to mind.
     */
    static String helpCategoryFor(String query) {
        return switch (query) {
            case "core", "sphere", "general" -> "system";
            case "file", "fs" -> "files";
            case "dir", "directory", "directories" -> "dirs";
            case "python" -> "py";
            case "fortran" -> "fort";
            case "javascript" -> "js";
            case "c++", "cxx" -> "cpp";
            case "variables", "var" -> "vars";
            case "plot" -> "plots";
            case "snippets" -> "snippet";
            case "tool" -> "tools";
            case "logs" -> "log";
            default -> query;
        };
    }

    /** Prints the commands asked for, with what each one does. */
    static void list(Map<String, String> commands,
                             java.util.Collection<String> names, String what) {
        AppLogger.info("[" + what + "]");
        for (String name : names) {
            AppLogger.raw("  " + name + " - " + commands.getOrDefault(name, ""));
        }
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
        com.sphere.components.rootview.RootPlotsPanel.instance().setOutputFolder(null);
        com.sphere.components.variables.VariablesPanel.instance().setFolder(null);
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
        describeWorkflow(project.resolve(".workflow"));
    }

    /**
     * Reads what the project says it is made of.
     *
     * The file is written at creation from the modules the user ticked; saying
     * only that it exists left it a file nobody ever opened.
     */
    static void describeWorkflow(java.nio.file.Path workflow) {
        if (!java.nio.file.Files.isRegularFile(workflow)) {
            return;
        }
        java.util.Map<String, Object> content;
        try {
            content = com.sphere.components.workspace.MinimalJson.parse(
                java.nio.file.Files.readString(workflow));
        } catch (java.io.IOException unreadable) {
            AppLogger.error("Could not read " + workflow + ": " + unreadable.getMessage());
            return;
        }

        java.util.List<String> on = new java.util.ArrayList<>();
        if (content.get("modules") instanceof java.util.Map<?, ?> modules) {
            for (java.util.Map.Entry<?, ?> module : modules.entrySet()) {
                if (Boolean.TRUE.equals(module.getValue())) {
                    on.add(String.valueOf(module.getKey()));
                }
            }
        }
        AppLogger.raw(String.format("  %-10s %s", "modules",
            on.isEmpty() ? "none" : String.join(", ", on)));

        if (content.get("entries") instanceof java.util.Map<?, ?> entries) {
            for (java.util.Map.Entry<?, ?> entry : entries.entrySet()) {
                if (entry.getValue() instanceof java.util.Map<?, ?> where) {
                    AppLogger.raw(String.format("  %-10s %s", entry.getKey(),
                        String.valueOf(where.get("file"))));
                }
            }
        }

        if (content.get("pipelines") instanceof java.util.Map<?, ?> pipelines) {
            for (java.util.Map.Entry<?, ?> pipeline : pipelines.entrySet()) {
                java.util.List<?> steps = pipeline.getValue() instanceof java.util.List<?> l
                    ? l : java.util.List.of();
                AppLogger.raw(String.format("  %-10s %s", pipeline.getKey(),
                    steps.isEmpty() ? "no step" : String.join(" -> ",
                        steps.stream().map(String::valueOf).toList())));
            }
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
        askAndReport("python");
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
        askAndReport("cpp");
    }

    public static void cppDiag(String input, CommandExecutionContext c) {
        reportTool("cpp", "GPP_DIR", "g++", "--version");
    }

    // --- JS Engine ---
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
    static java.nio.file.Path workspaceRoot() {
        return java.nio.file.Path.of("WorkSpace");
    }

    static java.util.List<java.nio.file.Path> workspaceProjects() {
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

    static void setActiveProject(CommandExecutionContext c, String verb) {
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

        // Plots drawn from now on belong to this project rather than to
        // whichever folder Sphere happened to start in.
        com.sphere.components.rootview.RootPlotsPanel.instance()
            .setOutputFolder(project.toAbsolutePath().resolve("plots"));
        com.sphere.components.variables.VariablesPanel.instance()
            .setFolder(project.toAbsolutePath().resolve(
                com.sphere.components.variables.VariablesPanel.FOLDER_NAME));

        AppLogger.raw("Active project: " + name + "  (" + project.toAbsolutePath() + ")");
    }

    /** files, folders, bytes. */
    static long[] tallyTree(java.nio.file.Path root) {
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

    static String humanBytes(long bytes) {
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
    static void reportTool(String label, String key, String fallback, String... args) {
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

    static void switchMode(CommandExecutionContext c, String mode, String indicator) {
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

    static final long TIMEOUT_MS = 5000L;

    static com.sphere.core.rootbackend.RootBackend backend(CommandExecutionContext c) {
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

    static final java.util.regex.Pattern UNDECLARED =
        java.util.regex.Pattern.compile("use of undeclared identifier '([^']+)'");

    /** The identifier cling did not know, or null when it refused for another reason. */
    static String undeclaredIdentifier(String answer) {
        if (answer == null || !answer.startsWith("ERROR")) {
            return null;
        }
        final java.util.regex.Matcher m = UNDECLARED.matcher(answer);
        return m.find() ? m.group(1) : null;
    }

    static void reportUnknown(String text, java.util.List<String> near,
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

    static String firstLine(String text) {
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
    static String runnable(String name, String text) {
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
    static java.util.List<String> nearestCommands(String text) {
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

    static int editDistance(String a, String b) {
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
    static String args(String input, String command) {
        if (input == null) {
            return "";
        }
        String s = input.trim();
        return s.regionMatches(true, 0, command, 0, command.length())
            ? s.substring(command.length()).trim()
            : s.replaceFirst("^:root\\s+", "").trim();
    }

    static void usage(String text) {
        AppLogger.warn("Usage: " + text);
    }

    /** Sends a native opcode and prints the engine's answer. */
    static void send(CommandExecutionContext c, short opcode, int jobId, String payload) {
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
    static void cling(CommandExecutionContext c, String expression) {
        String answer = clingAnswer(c, expression);
        if (answer != null) {
            AppLogger.info(answer);
        }
    }

    /** Same, but hands the answer back instead of printing it. Null when none came. */
    static String clingAnswer(CommandExecutionContext c, String expression) {
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
    static String head(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }

    static String tail(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? "" : s.substring(i + 1).trim();
    }

    static int asInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A named ROOT object, cast to `type`. Handles are names, not numbers:
     *  the engine keeps no registry for histograms, objects, graphs or canvases. */
    /** A checked lookup: a missing or mistyped object raises instead of yielding null. */
    /** Asks the engine for its own figures. The view names what to report. */
    static void metrics(CommandExecutionContext c, String view) {
        send(c, com.sphere.core.rootbackend.RootBackend.CMD_SYS_METRICS, 0, view);
    }

    /**
     * Binds what an expression builds to a name, so the next command finds it.
     *
     * A data frame, a workspace, a factory, a connection or a socket is not a
     * named ROOT object, so the commands that used one referred to a variable
     * the interpreter never had.
     */
    static String keep(String name, String type, String expression) {
        return "SphereBridge::Keep<" + type + ">(\"" + name + "\", " + expression
             + ", \"" + type + "\")";
    }

    /** The object a name is bound to, refused if it holds something else. */
    static String held(String name, String type) {
        return "SphereBridge::Held<" + type + ">(\"" + name + "\")";
    }

    /** The words of an argument list, with the commas a C++ call needs. */
    static String csv(String rest) {
        return (rest == null || rest.isBlank())
            ? "" : rest.trim().replaceAll("[,\\s]+", ", ");
    }

    /** The same, with each word quoted, for a call that takes strings. */
    static String quotedCsv(String rest) {
        if (rest == null || rest.isBlank()) {
            return "";
        }
        final String[] words = rest.trim().split("[,\\s]+");
        final StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append('"').append(word).append('"');
        }
        return out.toString();
    }

    /** The words of a request, or an empty array when there is nothing to read. */
    static String[] words(String a) {
        return a.isBlank() ? new String[0] : a.trim().split("\\s+");
    }

    /** The words from `from` on, joined back with single spaces. */
    static String join(String[] w, int from) {
        if (w == null || from >= w.length) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (int i = from; i < w.length; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(w[i]);
        }
        return out.toString();
    }

    // --- New ROOT coverage: one family per ROOT class, native where the
    // --- engine can answer on its own and through the interpreter otherwise.

    static String obj(String type, String name) {
        return "SphereBridge::Need<" + type + ">(\"" + name + "\", \"" + type + "\")";
    }

    // --- Level 1: native opcodes ---

    // ---- the Plots tab ------------------------------------------------------

    /** Shows a picture in the Plots tab, whatever wrote it. */
    public static void plotsAdd(String i, CommandExecutionContext c) {
        String a = args(i, ":plots add");
        if (a.isEmpty()) {
            usage(":plots add <file.png|jpg|svg>");
            return;
        }
        java.io.File image = resolve(a);
        if (!com.sphere.components.imaging.ImageFileIO.isImage(image)) {
            AppLogger.error(image + " is not a picture Sphere reads. "
                + "It reads png, jpg, jpeg, gif, bmp and svg.");
            return;
        }
        com.sphere.components.rootview.RootPlotsPanel.instance().showImage(image);
        AppLogger.info(image.getName() + " shown in the Plots tab");
    }

    /** Adds a folder to the ones watched for new pictures. */
    public static void plotsWatch(String i, CommandExecutionContext c) {
        String a = args(i, ":plots watch");
        com.sphere.components.rootview.RootPlotsPanel panel =
            com.sphere.components.rootview.RootPlotsPanel.instance();
        if (a.isEmpty()) {
            StringBuilder text = new StringBuilder("Watching for pictures in:");
            for (java.nio.file.Path folder : panel.watched()) {
                text.append("\n  ").append(folder);
            }
            AppLogger.info(text.toString());
            return;
        }
        java.io.File folder = resolve(a);
        if (!folder.isDirectory()) {
            AppLogger.error("Not a folder: " + folder);
            return;
        }
        panel.watch(folder.toPath());
        AppLogger.info("Watching " + folder + " for new pictures");
    }

    public static void plotsUnwatch(String i, CommandExecutionContext c) {
        String a = args(i, ":plots unwatch");
        if (a.isEmpty()) {
            usage(":plots unwatch <folder>");
            return;
        }
        final boolean dropped = com.sphere.components.rootview.RootPlotsPanel
            .instance().unwatch(resolve(a).toPath());
        if (dropped) {
            AppLogger.info("No longer watching " + resolve(a));
        } else {
            AppLogger.error("That folder was not being watched. "
                + "Try :plots watch to see which are.");
        }
    }

    /** Shows or moves the folder the Plots tab writes its pictures into. */
    public static void plotsFolder(String i, CommandExecutionContext c) {
        String a = args(i, ":plots folder");
        com.sphere.components.rootview.RootPlotsPanel panel =
            com.sphere.components.rootview.RootPlotsPanel.instance();
        if (!a.isEmpty()) {
            java.io.File folder = resolve(a);
            panel.setOutputFolder(folder.toPath());
        }
        try {
            AppLogger.info("Plots are written to " + panel.outputFolder());
        } catch (java.io.IOException unwritable) {
            AppLogger.error("That folder cannot be used: " + unwritable.getMessage());
        }
    }

    // ---- Julia --------------------------------------------------------------

    /** The one interpreter every Julia command shares. */
    static com.sphere.core.julia.JuliaSession julia() {
        return com.sphere.core.julia.JuliaSession.instance(
            new com.sphere.utils.SettingsManager());
    }

    public static void juliaStart(String i, CommandExecutionContext c) {
        try {
            julia().start();
            AppLogger.info("Julia session open. What it defines stays between commands.");
        } catch (java.io.IOException unavailable) {
            AppLogger.error(unavailable.getMessage());
        }
    }

    public static void juliaStop(String i, CommandExecutionContext c) {
        julia().shutdown();
        AppLogger.info("Julia session closed. Its variables went with it.");
    }

    public static void juliaMode(String i, CommandExecutionContext c) {
        String clean = (i != null) ? i.trim() : "";
        if (clean.equals(":julia mode")) {
            switchMode(c, "julia", "[julia]");
        }
    }

    public static void juliaExit(String i, CommandExecutionContext c) {
        switchMode(c, null, "");
    }

    // ---- the permanent modes ------------------------------------------------

    /** Prints what the three columns measure and where those widths came from. */
    public static void layoutReport(String i, CommandExecutionContext c) {
        AppLogger.info(com.sphere.Sphere.layoutReport());
    }

    public static void fortMode(String i, CommandExecutionContext c) {
        String clean = (i != null) ? i.trim() : "";
        if (clean.equals(":fort mode")) {
            switchMode(c, "fortran", "[fortran]");
        }
    }

    public static void fortExit(String i, CommandExecutionContext c) {
        switchMode(c, null, "");
    }

    /** The mode whose lines :exec would run, or null when none is active. */
    static String collectingMode(CommandExecutionContext c) {
        final String mode = (c == null || c.ctx == null) ? null : c.ctx.currentMode;
        if (!com.sphere.core.exec.CodeBuffer.collects(mode)) {
            AppLogger.error("No permanent mode is active. Enter one with \":py mode\","
                            + " \":cpp mode\", \":julia mode\" or \":fort mode\".");
            return null;
        }
        return mode;
    }

    public static void execReset(String i, CommandExecutionContext c) {
        final String mode = collectingMode(c);
        if (mode != null) {
            com.sphere.core.exec.CodeBuffer.clear(mode);
        }
    }

    /**
     * Runs what a mode still holds. With Enter running each finished line, only
     * Fortran normally has anything left here: it has no interpreter, so its
     * program is built up first and run by this command.
     */
    public static void execRun(String i, CommandExecutionContext c) {
        final String mode = collectingMode(c);
        if (mode == null) {
            return;
        }
        final String code = com.sphere.core.exec.CodeBuffer.text(mode);
        if (code.isBlank()) {
            AppLogger.error("Nothing to run: the " + mode + " block is empty.");
            return;
        }
        if (!"fortran".equals(mode)) {
            com.sphere.core.exec.CodeBuffer.clear(mode);
        }
        if (c.ctx != null && c.ctx.router != null) {
            c.ctx.router.runModeBlock(mode, code, true);
        }
    }

    /** Runs a file in the session, so what it defines is still there afterwards. */
    public static void juliaRun(String i, CommandExecutionContext c) {
        String a = args(i, ":julia run");
        if (a.isEmpty()) {
            usage(":julia run <file.jl>");
            return;
        }
        java.io.File file = resolve(a);
        if (!file.isFile()) {
            AppLogger.error("No such file: " + file);
            return;
        }
        try {
            julia().start();
        } catch (java.io.IOException unavailable) {
            AppLogger.error(unavailable.getMessage());
            return;
        }
        julia().runFile(file);
    }

    public static void juliaVars(String i, CommandExecutionContext c) {
        askAndReport("julia");
    }

    public static void juliaDiag(String i, CommandExecutionContext c) {
        reportTool("julia", "JULIA_DIR", "julia", "--version");
    }

    // ---- variables ----------------------------------------------------------

    /** Lists what every language is holding right now. */
    public static void varsList(String i, CommandExecutionContext c) {
        registerRootVariables();
        java.util.List<com.sphere.components.variables.VariableStore.Variable> held =
            com.sphere.components.variables.VariableStore.all();
        if (held.isEmpty()) {
            AppLogger.raw("No variable yet. A running language answers :vars refresh; "
                + "one that has ended writes a file in "
                + variablesFolder() + ", and :vars helper <language> writes what it "
                + "takes to do that.");
            return;
        }
        StringBuilder text = new StringBuilder();
        String source = "";
        for (com.sphere.components.variables.VariableStore.Variable variable : held) {
            if (!variable.source().equals(source)) {
                source = variable.source();
                text.append(text.length() == 0 ? "" : "\n").append(source).append(':');
            }
            text.append(String.format("%n  %-28s %-16s %s",
                        variable.name(), variable.type(), variable.value()));
        }
        AppLogger.raw(text.toString());
    }

    /** Says where the variables would come from, and what is in the way. */
    public static void varsDiag(String i, CommandExecutionContext c) {
        registerRootVariables();
        AppLogger.info(com.sphere.components.variables.VariablesPanel.instance()
                                                                    .diagnosis());
    }

    /** Asks the languages that are still running, and rereads the folder. */
    public static void varsRefresh(String i, CommandExecutionContext c) {
        registerRootVariables();
        com.sphere.components.variables.VariablesPanel.instance().refreshAll();
        AppLogger.info("Asked " + String.join(", ",
            com.sphere.components.variables.VariableSources.names())
            + " and reread " + variablesFolder());
    }

    public static void varsClear(String i, CommandExecutionContext c) {
        String a = args(i, ":vars clear");
        // The files the runs left behind go too, otherwise the next pass over the
        // folder publishes them again and the table fills back up.
        final int removed = com.sphere.components.variables.VariablesPanel.instance()
                                .forget(a.isEmpty() ? null : a);
        AppLogger.info((a.isEmpty() ? "Variables tab emptied"
                                    : "Dropped the variables of " + a)
                       + (removed == 0 ? "" : ", " + removed + " file(s) removed"));
    }

    /** Shows or moves the folder the variable files are read from. */
    public static void varsFolder(String i, CommandExecutionContext c) {
        String a = args(i, ":vars folder");
        com.sphere.components.variables.VariablesPanel panel =
            com.sphere.components.variables.VariablesPanel.instance();
        if (!a.isEmpty()) {
            panel.setFolder(resolve(a).toPath());
        }
        AppLogger.info("Variables are read from " + variablesFolder());
    }

    public static void varsWatch(String i, CommandExecutionContext c) {
        String a = args(i, ":vars watch");
        com.sphere.components.variables.VariablesPanel panel =
            com.sphere.components.variables.VariablesPanel.instance();
        if (a.isEmpty()) {
            StringBuilder text = new StringBuilder("Watching for variable files in:");
            for (java.nio.file.Path folder : panel.watched()) {
                text.append("\n  ").append(folder);
            }
            AppLogger.raw(text.toString());
            return;
        }
        java.io.File folder = resolve(a);
        if (!folder.isDirectory()) {
            AppLogger.error("Not a folder: " + folder);
            return;
        }
        panel.watch(folder.toPath());
        AppLogger.info("Watching " + folder + " for variable files");
    }

    public static void varsUnwatch(String i, CommandExecutionContext c) {
        String a = args(i, ":vars unwatch");
        if (a.isEmpty()) {
            usage(":vars unwatch <folder>");
            return;
        }
        final boolean dropped = com.sphere.components.variables.VariablesPanel
            .instance().unwatch(resolve(a).toPath());
        if (dropped) {
            AppLogger.info("No longer watching " + resolve(a));
        } else {
            AppLogger.error("That folder was not being watched. "
                + "Try :vars watch to see which are.");
        }
    }

    /** Turns off, or back on, collecting a Python script's variables. */
    public static void varsWrap(String i, CommandExecutionContext c) {
        String a = args(i, ":vars wrap").toLowerCase(java.util.Locale.ROOT);
        if (a.equals("on") || a.equals("off")) {
            com.sphere.components.variables.PythonProbe.setWrapping(a.equals("on"));
        } else if (!a.isEmpty()) {
            usage(":vars wrap [on|off]");
            return;
        }
        AppLogger.info("A Python script "
            + (com.sphere.components.variables.PythonProbe.isWrapping()
               ? "is run so that its variables are collected"
               : "is run as it is, and publishes nothing"));
    }

    /** Writes what a language needs in order to publish its variables. */
    public static void varsHelper(String i, CommandExecutionContext c) {
        String a = args(i, ":vars helper");
        if (a.isEmpty()) {
            usage(":vars helper <" + String.join("|",
                com.sphere.components.variables.VariableHelpers.languages()) + ">");
            return;
        }
        try {
            java.nio.file.Path folder = com.sphere.components.variables.VariablesPanel
                .instance().folder().getParent();
            java.nio.file.Path written = com.sphere.components.variables.VariableHelpers
                .write(folder == null ? java.nio.file.Path.of(".") : folder, a);
            AppLogger.info("Wrote " + written);
        } catch (java.io.IOException unwritable) {
            AppLogger.error(unwritable.getMessage());
        }
    }

    /** Asks one language again, then says what it answered. */
    static void askAndReport(String source) {
        registerRootVariables();
        com.sphere.components.variables.VariablesPanel.instance().refreshAll();
        int held = 0;
        for (com.sphere.components.variables.VariableStore.Variable variable
                : com.sphere.components.variables.VariableStore.all()) {
            if (variable.source().equals(source)) {
                held++;
            }
        }
        if (held > 0) {
            AppLogger.info(held + " " + source + " variables in the Variables tab");
            return;
        }
        AppLogger.raw("Nothing from " + source + " yet. It answers while it is running; "
            + "once it has ended it has to leave a " + source + ".vars file in "
            + variablesFolder() + ". Try :vars helper " + source + ".");
    }

    /** The ROOT interpreter is only asked once it exists. */
    static void registerRootVariables() {
        if (!com.sphere.components.variables.VariableSources.names()
                .contains(com.sphere.components.variables.RootVariables.SOURCE)
            && com.sphere.core.rootbackend.RootBackend.getInstance() != null) {
            com.sphere.components.variables.VariableSources.register(
                new com.sphere.components.variables.RootVariables());
        }
    }

    static String variablesFolder() {
        try {
            return com.sphere.components.variables.VariablesPanel.instance()
                                                                 .folder().toString();
        } catch (java.io.IOException unreachable) {
            return "the variables folder";
        }
    }

    /** Says what the clipboard holds and which fields answer the copy keys. */
    public static void clipStatus(String i, CommandExecutionContext c) {
        AppLogger.info(com.sphere.components.ClipboardBridge.status());
    }

    /** Writes a marker to the clipboard and reads it back. */
    public static void clipTest(String i, CommandExecutionContext c) {
        AppLogger.info(com.sphere.components.ClipboardBridge.roundTrip());
    }

    public static void plotsClear(String i, CommandExecutionContext c) {
        com.sphere.components.rootview.RootPlotsPanel.instance().clear();
        AppLogger.info("Plots tab emptied");
    }

    /** A path as typed, taken from the console's own folder when relative. */
    static java.io.File resolve(String path) {
        java.io.File given = new java.io.File(path);
        return given.isAbsolute() ? given
            : com.sphere.core.fs.WorkingDirectory.get().resolve(path).toFile();
    }

    /**
     * Says why a column came back empty.
     *
     * A bare refusal leaves nothing to act on, and the three reasons call for
     * three different moves: attach a tree, fix the name, or pick another
     * branch.
     */
    static String whyNoColumn(com.sphere.core.rootbackend.RootBackend b,
                                      int jobId, String branch) {
        return whyNoColumn(branchesOf(b, jobId), jobId, branch);
    }

    static String whyNoColumn(java.util.List<String> names, int jobId,
                              String branch) {
        if (names.isEmpty()) {
            return "No tree is bound to id " + jobId
                 + ". Bind one with :root tree attach " + jobId
                 + " <file_id> <tree_name>";
        }
        if (!names.contains(branch)) {
            return "No branch named \"" + branch + "\" in tree " + jobId
                 + ". It holds: " + String.join(", ", names);
        }
        return branch + " holds objects, not numbers, so it cannot be drawn.";
    }

    /** The branch names of a bound tree, empty when none is bound. */
    static java.util.List<String> branchesOf(
            com.sphere.core.rootbackend.RootBackend b, int jobId) {
        java.util.List<String> names = new java.util.ArrayList<>();
        String answer = b.treeBranchesAwait(jobId, TIMEOUT_MS);
        if (answer == null || !answer.startsWith("{")) {
            return names;
        }
        for (Object branch : com.sphere.components.rootview.Json.list(
                 com.sphere.components.rootview.Json.parse(answer), "branches")) {
            names.add(com.sphere.components.rootview.Json.text(branch, "name", "?"));
        }
        return names;
    }

    /** How many values came back, what they span, and the first of them. */
    static String summarize(String branch, double[] values) {
        double min = values[0];
        double max = values[0];
        double sum = 0;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        StringBuilder first = new StringBuilder();
        for (int k = 0; k < Math.min(8, values.length); k++) {
            first.append(k > 0 ? "  " : "")
                 .append(String.format(java.util.Locale.ROOT, "%.6g", values[k]));
        }
        if (values.length > 8) {
            first.append("  ...");
        }
        return String.format(java.util.Locale.ROOT,
            "%s  %d values   min %.6g   max %.6g   mean %.6g%n%s",
            branch, values.length, min, max, sum / values.length, first);
    }

    /**
     * A macro named on its own is looked up in user_scripts/, the project's
     * folder first and then the global one, before being treated as a path.
     */
    static String macroPath(String argument, CommandExecutionContext c) {
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

    /** A source of includes/ by bare name, the project layer winning. */
    static java.nio.file.Path findSource(String name, String project) {
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
    static void runBuilds(java.util.List<java.nio.file.Path> queue,
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

    /** Same compiler family, ignoring the path and the version suffix. */
    static boolean sameCompilerFamily(String a, String b) {
        return family(a).equals(family(b));
    }

    static String family(String compiler) {
        String name = java.nio.file.Path.of(compiler.trim().split("\\s+")[0])
                        .getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        name = name.replaceAll("\\.exe$", "").replaceAll("-?\\d+(\\.\\d+)*$", "");
        if (name.contains("clang")) return "clang";
        if (name.contains("g++") || name.contains("gcc")) return "gcc";
        return name;
    }

    static long sizeOf(java.nio.file.Path p) {
        try { return java.nio.file.Files.size(p); } catch (java.io.IOException e) { return 0L; }
    }

}
