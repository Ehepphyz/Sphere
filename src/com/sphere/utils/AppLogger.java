package com.sphere.utils;

import com.sphere.ui.ConsoleUI;
import com.sphere.ui.ConsoleUI.LogLevel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Thread-safe, high-velocity logging facility for the hybrid shell platform.
 * Routes diagnostics seamlessly to UI text panes and persisted local storage contexts.
 */
public class AppLogger {

    // Thread visibility fences for cross-platform process streaming
    private static volatile ConsoleUI logTarget;
    private static volatile SessionManager session;
    private static volatile boolean debugEnabled = true; // Enabled by default, can be toggled via [D+]/[D-]

    // Reusable, thread-safe date-time formatter for file serialization
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void setLogTarget(ConsoleUI target) {
        logTarget = target;
    }

    public static void setSession(SessionManager s) {
        session = s;
    }

    /**
     * Toggles the global visibility state of debug traces across the engine.
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /* -------------------------------------------------------------------------
     * Public Logging API (Prefix tokens preserved for ConsoleUI context compilation)
     * ------------------------------------------------------------------------- */
    
    public static void info(String message) { 
        log("[i] " + message); 
    }
    
    public static void success(String message) { 
        log("[+] " + message); 
    }
    
    public static void error(String message) { 
        log("[!] " + message); 
    }

    /**
     * Logs an error message accompanied by an exception or throwable stack trace.
     */
    public static void error(String message, Throwable throwable) {
        if (throwable != null) {
            log("[!] " + message + " | Exception: " + throwable.getMessage());
        } else {
            log("[!] " + message);
        }
    }
    
    public static void recall(String message) { 
        log("[>] " + message); 
    }

    /**
     * Logs structural runtime warnings to flag non-breaking pipeline exceptions.
     */
    public static void warn(String message) { 
        log("[W] " + message);
    }

    /**
     * Logs fine-grained operational parameters. Automatically bypassed if debug visibility is deactivated.
     */
    public static void debug(String message) {
        if (debugEnabled) {
            log("[D] " + message);
        }
    }

    /**
     * Explicit state mutator that prints a message and dynamically activates debug-level logging.
     */
    public static void debugOn(String message) {
        setDebugEnabled(true);
        log("[D+] " + message);
    }

    /**
     * Explicit state mutator that prints a message and dynamically deactivates debug-level logging views.
     */
    public static void debugOff(String message) {
        log("[D-] " + message);
        setDebugEnabled(false);
    }

    /**
     * Direct string writer bypassing prefix parsing stacks entirely.
     */
    public static void raw(String message) {
        writeToTargets(message);
    }

    /**
     * Triggers a clean screen purge operation across active user interface display buffers.
     */
    public static void clear() {
        log("[C] Clear requested");
    }

    /* -------------------------------------------------------------------------
     * Internal Core Log Engine
     * ------------------------------------------------------------------------- */
    
    private static void log(String message) {
        writeToTargets(message);
    }

    /**
     * Dispatches processed sequences synchronously to active text panes and files.
     * Maps inline token signatures cleanly to strict ConsoleUI LogLevels to preserve CPU cycles.
     */
    private static void writeToTargets(String message) {
        if (message == null) return;

        ConsoleUI target = logTarget; // Local reference snapshot for thread safety
        
        if (target != null) {
            LogLevel level = LogLevel.NONE;
            String cleanMessage = message;

            // Extract level and strip single-character token markers to feed the high-performance UI layer
            if (message.startsWith("[C]")) {
                level = LogLevel.CLEAR;
                cleanMessage = ""; // Empty string payload serves as a visual flush trigger
            } else if (message.startsWith("[!]")) {
                level = LogLevel.ERROR;
                cleanMessage = message.substring(3).trim();
            } else if (message.startsWith("[+]")) {
                level = LogLevel.SUCCESS;
                cleanMessage = message.substring(3).trim();
            } else if (message.startsWith("[i]")) {
                level = LogLevel.INFO;
                cleanMessage = message.substring(3).trim();
            } else if (message.startsWith("[>]")) {
                level = LogLevel.PROMPT;
                cleanMessage = message.substring(3).trim();
            } else if (message.startsWith("[W]")) {
                level = LogLevel.WARN;
                cleanMessage = message.substring(3).trim();
            } else if (message.startsWith("[D]") || message.startsWith("[D+]") || message.startsWith("[D-]")) {
                level = LogLevel.INFO; // Map debug traces smoothly to standard info typography colors
                int splitOffset = message.indexOf("]") + 1;
                cleanMessage = message.substring(splitOffset).trim();
            }

            // Route execution with type metadata attached directly
            target.log(level, cleanMessage);
        } else {
            System.out.println(message);
        }

        // Phase 2: Route formatted content to tracking session log files with timestamps
        SessionManager activeSession = session; 
        if (activeSession != null && !message.startsWith("[C]")) {
            String timestamp = LocalTime.now().format(TIMESTAMP_FORMATTER);
            activeSession.write("[" + timestamp + "] " + message);
        }
    }

    /**
     * Generates an aesthetic clean break boundary across active console views.
     */
    public static void separator() {
        raw("------------------------------------------------------------");
    }
}