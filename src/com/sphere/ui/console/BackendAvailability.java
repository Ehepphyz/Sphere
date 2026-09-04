package com.sphere.ui.console;

import com.sphere.core.cpp.CppIntellisenseBackend;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.SettingsManager;

/**
 * What the menu is allowed to offer, and why not when it is not. settings.conf is
 * the reference: a key left blank disables that backend, so the answer here is a
 * reading of that file plus the live state of the process it describes.
 */
public final class BackendAvailability {

    /** Usable or not, with the sentence shown as the entry's tooltip when not. */
    public record State(boolean usable, String reason) {
        public static State ok() {
            return new State(true, null);
        }

        public static State no(String reason) {
            return new State(false, reason);
        }
    }

    private final SettingsManager settings;
    private final CppIntellisenseBackend clangd;

    public BackendAvailability(SettingsManager settings, CppIntellisenseBackend clangd) {
        this.settings = settings;
        this.clangd = clangd;
    }

    // ---- ROOT --------------------------------------------------------------

    public boolean rootRunning() {
        return RootBackend.getInstance() != null;
    }

    /** Whether settings.conf declares a ROOT installation at all. */
    public State rootConfigured() {
        if (settings == null) {
            return State.no("no settings.conf is loaded.");
        }
        String dir = settings.getProperty("ROOT_DIR");
        if (dir == null || dir.isBlank()) {
            return State.no("ROOT_DIR is empty in settings.conf, which disables this backend.");
        }
        return State.ok();
    }

    public String rootStatusLine() {
        State configured = rootConfigured();
        if (!configured.usable()) {
            return "Status: not configured";
        }
        return rootRunning() ? "Status: running" : "Status: stopped";
    }

    // ---- clangd ------------------------------------------------------------

    public boolean clangdRunning() {
        return clangd != null && clangd.isRunning();
    }

    /**
     * The clangd binary to launch, or null. A blank CLANGD_DIR is a decision to
     * disable it; an absent key lets the automatic search look for clangd.
     */
    public String clangdExecutable() {
        return settings == null ? null : settings.resolveTool("CLANGD_DIR", "clangd");
    }

    public State clangdConfigured() {
        if (settings == null) {
            return State.no("no settings.conf is loaded.");
        }
        if (settings.isDeclaredEmpty("CLANGD_DIR")) {
            return State.no("CLANGD_DIR is empty in settings.conf, which disables this backend.");
        }
        return clangdExecutable() == null
             ? State.no("clangd was not found. Set CLANGD_DIR in settings.conf.")
             : State.ok();
    }

    /** Directory holding compile_commands.json, or null when none is declared. */
    public String compileCommandsDir() {
        if (settings == null || settings.isDeclaredEmpty("LSP_COMPILE_COMMANDS")) {
            return null;
        }
        String declared = settings.getProperty("LSP_COMPILE_COMMANDS");
        if (declared == null || declared.isBlank()) {
            return null;
        }
        return SettingsManager.toNativePath(declared);
    }

    public String clangdStatusLine() {
        State configured = clangdConfigured();
        if (!configured.usable()) {
            return "Status: not configured";
        }
        return clangdRunning() ? "Status: running" : "Status: stopped";
    }
}
