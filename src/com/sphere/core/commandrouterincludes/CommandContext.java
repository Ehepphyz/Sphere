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
     * Registers a running process to the context for life-cycle management.
     */
    public void registerProcess(String commandName, Process process) {
        activeProcesses.put(commandName, process);
    }

    /**
     * Unregisters a completed or killed process from tracking.
     */
    public void unregisterProcess(String commandName) {
        activeProcesses.remove(commandName);
    }

    /**
     * Terminate an active running process by its token identifier name.
     */
    public boolean killProcess(String commandName) {
        Process p = activeProcesses.get(commandName);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            activeProcesses.remove(commandName);
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
        AppLogger.info("Active project set to: " + projectName);
    }
}
