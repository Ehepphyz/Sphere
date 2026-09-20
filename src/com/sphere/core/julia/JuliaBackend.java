package com.sphere.core.julia;

import com.sphere.core.Backend;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;

/**
 * Julia as one of the console's languages.
 *
 * Unlike the Python and C++ backends, which start a process per command, this
 * one hands every line to the same interpreter. That is the whole point: what a
 * line defines is still there for the next one, and for the Variables tab.
 */
public final class JuliaBackend implements Backend {

    private final SettingsManager settings;

    public JuliaBackend(SettingsManager settings) {
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "JuliaBackend";
    }

    @Override
    public void execute(String command) {
        if (command == null) {
            return;
        }
        final String code = command.replaceAll("^::?julia\\s*", "").strip();
        if (code.isEmpty()) {
            return;
        }
        JuliaSession session = JuliaSession.instance(settings);
        if (!session.isRunning() && !open(session)) {
            return;
        }
        if (code.endsWith(".jl")) {
            session.runFile(new java.io.File(code));
        } else {
            session.run(code);
        }
    }

    @Override
    public void activate() {
        open(JuliaSession.instance(settings));
    }

    /** Starts the interpreter, saying why when it cannot be had. */
    private static boolean open(JuliaSession session) {
        try {
            session.start();
            return true;
        } catch (IOException unavailable) {
            AppLogger.error(unavailable.getMessage());
            return false;
        }
    }
}
