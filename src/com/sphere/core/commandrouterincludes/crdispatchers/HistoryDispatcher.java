package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.utils.AppLogger;

/**
 * Dispatches history-based macros (!!, !n, !?) to the CommandRouter.
 */
public class HistoryDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        // Handle macros starting with '!'
        return input != null && input.startsWith("!");
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        if (ctx.history == null) {
            AppLogger.error("History manager is not initialized.");
            return;
        }

        // Use the HistoryManager to resolve the actual command string
        String resolvedCommand = ctx.history.expandMacros(input);

        if (resolvedCommand != null && !resolvedCommand.equals(input)) {
            // Guard against infinite macro loops
            if (resolvedCommand.startsWith("!")) {
                AppLogger.error("Recursive history macro expansion blocked: " + resolvedCommand);
                return;
            }
            AppLogger.info("Executing from history: " + resolvedCommand);
            ctx.router.processInput(resolvedCommand);
        } else {
            AppLogger.error("No matching command found in history for: " + input);
        }
    }
}
