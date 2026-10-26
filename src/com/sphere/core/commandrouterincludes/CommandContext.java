package com.sphere.core.commandrouterincludes;

import com.sphere.core.Backend;
import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SecurityManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages the shared execution state for the CommandRouter and its components.
 * Includes advanced tracking for asynchronous background processes.
 */
public class CommandContext {

    // Dependencies and state
    public CommandRouter router;
    public Map<String, Backend> backends;
    public HistoryManager history;
    public Tokenizer tokenizer;
    
    // UI Feedback hooks
    public Consumer<String> statusBarUpdater;
    public Consumer<String> modeUpdater;
    
    public String currentMode;
    private String activeProject;

    // Process tracking register for long-running scientific simulations
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    /**
     * Executes a command through a backend after security validation.
     */
    public void executeBackendSafely(String lang, String code) {
        if (code == null || code.isBlank()) {
            return;
        }

        if (!SecurityManager.isCommandSafe(code)) {
            AppLogger.error("Security: Backend command blocked.");
            return;
        }

        Backend backend = backends.get(lang);
        if (backend != null) {
            backend.execute(code);
        } else {
            AppLogger.error("Backend not available: " + lang);
        }
    }

    /**
     * Registers a running process under a name of its own, and returns that name.
     *
     * Two runs of one program used to share its name: the second replaced the
     * first in the table, and the first, on finishing, removed the second's
     * entry, so neither could be stopped by name any more. A name already taken
     * now gets a number: python, python#2, python#3.
     */
    public String registerProcess(String commandName, Process process) {
        final String base = (commandName == null || commandName.isBlank()) ? "process" : commandName;
        if (activeProcesses.putIfAbsent(base, process) == null) {
            return base;
        }
        for (int n = 2; ; n++) {
            final String numbered = base + "#" + n;
            if (activeProcesses.putIfAbsent(numbered, process) == null) {
                return numbered;
            }
        }
    }

    /**
     * Unregisters a completed or killed process from tracking: this process
     * only, so a name taken over since is left alone.
     */
    public void unregisterProcess(String commandName, Process process) {
        if (commandName != null) {
            activeProcesses.remove(commandName, process);
        }
    }

    /**
     * Terminate an active running process by its token identifier name, with
     * the programs it started. A line runs through "cmd /c" or "bash -c", so the
     * process Sphere holds is the shell: stopping it alone left the program it
     * had launched running, and on Windows that was every program.
     */
    public boolean killProcess(String commandName) {
        Process p = commandName == null ? null : activeProcesses.get(commandName);
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            activeProcesses.remove(commandName, p);
            return true;
        }
        return false;
    }

    public Map<String, Process> getActiveProcesses() {
        return activeProcesses;
    }

    // Getters and Setters
    public String getActiveProject() {
        return activeProject;
    }

    public void setActiveProject(String projectName) {
        this.activeProject = projectName;
        AppLogger.result("Active project set to: " + projectName);
    }
}
