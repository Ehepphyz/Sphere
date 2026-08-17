package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.commandrouterincludes.ParsedCommand;
import com.sphere.core.snippets.TagInterpreter;
import com.sphere.utils.AppLogger;

/**
 * Handles "one-shot" backend execution macro commands using the '::' prefix
 * by integrating with the core routing parser.
 */
public class OneShotDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        return input != null && input.startsWith("::");
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        // 1. Delegate to the unified routing parser engine
        com.sphere.core.commandrouterincludes.ParsedCommand pc = com.sphere.core.commandrouterincludes.CommandParser.parse(input);
        if (pc == null) {
            AppLogger.error("Failed to parse one-shot macro input framework: " + input);
            return;
        }

        // 2. Validate the target language backend extracted by the parser
        String lang = pc.languageOrApp;
        if (lang != null && ctx.backends != null && ctx.backends.containsKey(lang)) {
            
            // Reconstruct arguments or extract structural code from macro tokens
            String rawCode = String.join(" ", pc.macroTokens).trim();
            
            // Resolve any template snippets/tags relative to the current project
            String resolvedCode = TagInterpreter.resolve(rawCode, ctx.getActiveProject());
            
            // Safely execute the payload on the target backend
            ctx.executeBackendSafely(lang, resolvedCode);
        } else {
            AppLogger.error("Unknown or unregistered one-shot backend target identifier: " + lang);
        }
    }
}