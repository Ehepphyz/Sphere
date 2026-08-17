package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.utils.AppLogger;

/**
 * Dispatches raw input to the currently active backend if an operational mode is enabled.
 */
public class ModeDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        // ModeDispatcher captures non-system input but steps aside if no active console mode is set
        return input != null && !input.startsWith(":") && !input.startsWith("!");
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        if (ctx.currentMode == null) {
            return;
        }

        // Ensure the backend exists for the current mode
        if (ctx.backends != null && ctx.backends.containsKey(ctx.currentMode)) {
            ctx.executeBackendSafely(ctx.currentMode, input);
        } else {
            AppLogger.error("Backend not found or unavailable for mode: " + ctx.currentMode);
        }
    }
}
