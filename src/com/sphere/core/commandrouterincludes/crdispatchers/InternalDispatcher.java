package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commands.CommandDefinitions;
import com.sphere.core.commands.CommandExecutionContext;
import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.commandrouterincludes.ParsedCommand;
import com.sphere.core.snippets.TagInterpreter;
import com.sphere.utils.AppLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles internal system commands and delegates backend-specific execution
 * by leveraging the central routing parse engine.
 */
public class InternalDispatcher implements CommandDispatcher {

    private static final Pattern SNIPPET_BLOCK_PATTERN = Pattern.compile("\\[@\\s*(.*?)\\s*\\]");

    @Override
    public boolean canHandle(String input) {
        return input != null && input.startsWith(":") && !input.startsWith("::");
    }

    private boolean isManualCodeBlock(String inner) {
        return inner.startsWith("{") && inner.endsWith("}");
    }

    private boolean isSnippetCall(String inner) {
        return inner.matches("[A-Za-z0-9._/\\-]+(\\s+.*)?");
    }

    private List<String> extractAllSnippetBlocks(String input) {
        List<String> blocks = new ArrayList<>();
        Matcher m = SNIPPET_BLOCK_PATTERN.matcher(input);
        while (m.find()) {
            blocks.add(m.group(1).trim());
        }
        return blocks;
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        if (input == null) {
            return;
        }

        String trimmedInput = input.trim();

        // FAST-TRACK: Intercept direct inline routing requests targeting the ROOT backend
        if (trimmedInput.startsWith(":root ")) {
            String rawArgs = trimmedInput.substring(":root ".length()).trim();

            // Context requis par les méthodes de Handlers
            CommandExecutionContext execContext = new CommandExecutionContext(ctx, trimmedInput);

            List<String> blocks = extractAllSnippetBlocks(rawArgs);

            if (!blocks.isEmpty()) {
                for (String block : blocks) {
                    String inner = block.trim();
                    String resolved;

                    if (isManualCodeBlock(inner)) {
                        resolved = inner.substring(1, inner.length() - 1).trim();
                    } else if (isSnippetCall(inner)) {
                        resolved = TagInterpreter.resolve(inner, ctx.getActiveProject());
                    } else {
                        AppLogger.error("Invalid snippet/code block syntax inside block payload.");
                        return;
                    }

                    // CORRECTION: Ajout du 2ème argument (execContext)
                    com.sphere.core.commands.Handlers.sendToRootBridge("CLING_EXEC " + resolved, execContext);
                }
                return;
            }

            // CORRECTION: Ajout du 2ème argument (execContext)
            com.sphere.core.commands.Handlers.sendToRootBridge(rawArgs, execContext);
            return;
        }

        // 1. Check for explicitly registered system commands first
        var info = CommandDefinitions.find(trimmedInput);
        if (info != null) {
            info.handler.accept(trimmedInput, new CommandExecutionContext(ctx, trimmedInput));
            return;
        }

        // 2. Delegate to the unified routing parser engine
        ParsedCommand pc = com.sphere.core.commandrouterincludes.CommandParser.parse(trimmedInput);
        if (pc == null) {
            AppLogger.error("Failed to parse internal command framework input: " + trimmedInput);
            return;
        }

        // 3. Route backend-specific shortcuts dynamically using the parsed metadata
        String lang = pc.languageOrApp;
        if (lang != null && ctx.backends != null && ctx.backends.containsKey(lang)) {
            executeBackend(ctx, pc);
        } else {
            AppLogger.error("Unknown internal command or language backend identifier: " + trimmedInput);
        }
    }

    /**
     * Resolves snippets and executes code via the target language backend safely.
     */
    private void executeBackend(CommandContext ctx, ParsedCommand pc) {
        String rawArgs = String.join(" ", pc.macroTokens).trim();
        String resolvedCode = TagInterpreter.resolve(rawArgs, ctx.getActiveProject());
        ctx.executeBackendSafely(pc.languageOrApp, resolvedCode);
    }
}