package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.CommandRouter;
import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.utils.AppLogger;

/**
 * Dispatches raw console text strings to matching registered file system or tool plugins.
 */
public class PluginDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        // Should not handle explicit system commands or macros
        return input != null && !input.startsWith(":") && !input.startsWith("!");
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        if (ctx.router == null || ctx.router.getPlugins() == null) {
            return;
        }

        // Loop through and find the concrete plugin (like CatPlugin or LsPlugin) that identifies with the text
        for (CommandRouter.CommandPlugin plugin : ctx.router.getPlugins()) {
            if (plugin.supports(input)) {
                try {
                    plugin.execute(input);
                    return;
                } catch (Exception e) {
                    AppLogger.error("Error executing plugin for input '" + input + "': " + e.getMessage());
                }
            }
        }
    }
}
