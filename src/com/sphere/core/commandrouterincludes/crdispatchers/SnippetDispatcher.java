package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.commandrouterincludes.ParsedCommand;
import com.sphere.core.snippets.TagInterpreter;
import com.sphere.utils.AppLogger;

/**
 * Intercepts embedded shortcut macro invocations wrapped inside custom syntax boundaries
 * by leveraging the central routing parse engine.
 */
public class SnippetDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        if (input == null) {
            return false;
        }
        // Let the central parser evaluate if it structurally contains an encapsulated snippet block
        return input.contains("[@") && input.contains("]");
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        // 1. Delegate to the unified routing parser engine
        com.sphere.core.commandrouterincludes.ParsedCommand pc = com.sphere.core.commandrouterincludes.CommandParser.parse(input);
        if (pc == null || !pc.hasSnippet) {
            AppLogger.error("Failed to parse snippet payload or missing structural bounds: " + input);
            return;
        }

        // 2. Extract structural components provided by the parser
        String lang = pc.languageOrApp;
        if (lang != null && ctx.backends != null && ctx.backends.containsKey(lang)) {
            
            // Reconstruct the inner snippet context arguments
            String snippetRaw = String.join(" ", pc.snippetTokens).trim();
            
            // Reconstruct the outer command wrapper arguments/options
            String outerArgs = String.join(" ", pc.macroTokens).trim();

            // Resolve dynamic template tags inside the inner snippet boundaries
            String resolvedSnippet = TagInterpreter.resolve(snippetRaw, ctx.getActiveProject());
            
            // Recombine the execution sequence context safely
            String finalCommand = (resolvedSnippet + " " + outerArgs).trim();

            // Dispatch execution to the target language backend environment
            ctx.executeBackendSafely(lang, finalCommand);
        } else {
            AppLogger.error("Unknown or unregistered snippet backend target identifier: " + lang);
        }
    }
}