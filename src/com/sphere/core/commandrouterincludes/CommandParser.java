package com.sphere.core.commandrouterincludes;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal tokenizer that executes Sphere's 3-level grammar schema:
 * :command vs ::macro [ @snippet ]
 * Drops clean structural elements into the shared ParsedCommand record.
 */
public final class CommandParser {

    private static final Pattern ENCLOSED_SNIPPET_PATTERN = Pattern.compile("\\[\\s*@\\s*([^\\]]+)\\]");

    public static ParsedCommand parse(String rawLine) {
        if (rawLine == null || rawLine.strip().isEmpty()) {
            return null;
        }

        ParsedCommand cmd = new ParsedCommand();
        String remainingLine = rawLine.strip();

        // 1. Identify context level prefix routing
        if (remainingLine.startsWith("::")) {
            cmd.type = ParsedCommand.RootType.MACRO;
            remainingLine = remainingLine.substring(2).strip();
        } else if (remainingLine.startsWith(":")) {
            cmd.type = ParsedCommand.RootType.COMMAND;
            remainingLine = remainingLine.substring(1).strip();
        } else {
            return null;
        }

        // 2. Extract and slice the Enclosed Snippet payload if present
        Matcher matcher = ENCLOSED_SNIPPET_PATTERN.matcher(remainingLine);
        if (matcher.find()) {
            cmd.hasSnippet = true;
            String snippetRawContent = matcher.group(1).strip();
            
            // Clean macro line by removing the brackets footprint
            remainingLine = matcher.replaceAll("").replaceAll("\\s+", " ").strip();

            // Tokenize inside the snippet scope using your Tokenizer
            tokenizeSegment(snippetRawContent, true, cmd);
        }

        // 3. Tokenize macro elements using your Tokenizer
        tokenizeSegment(remainingLine, false, cmd);

        return cmd;
    }

    /**
     * What follows ":lang" or "::lang", exactly as typed: flags, quotes and the
     * order kept. parse() files the second word of a macro under filepath and
     * every flag under macroFlags, so code rebuilt from macroTokens alone lost
     * both: "::py script.py" ran nothing, ":py -c ..." lost its "-c".
     */
    public static String remainderAfterLanguage(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        String line = rawLine.strip();
        if (line.startsWith("::")) {
            line = line.substring(2).strip();
        } else if (line.startsWith(":")) {
            line = line.substring(1).strip();
        }
        int space = 0;
        while (space < line.length() && !Character.isWhitespace(line.charAt(space))) {
            space++;
        }
        return line.substring(space).strip();
    }

    private static void tokenizeSegment(String segmentText, boolean isSnippetScope, ParsedCommand cmd) {
        // Use your application's Tokenizer instead of basic regex split
        List<String> rawTokens = Tokenizer.DEFAULT.tokenize(segmentText);
        if (rawTokens.isEmpty()) return;

        int startIndex = 0;

        if (!isSnippetScope) {
            cmd.languageOrApp = rawTokens.get(0);
            startIndex = 1;
            
            if (cmd.type == ParsedCommand.RootType.MACRO && rawTokens.size() > 1) {
                String potentialPath = rawTokens.get(1);
                if (!potentialPath.startsWith("-")) {
                    cmd.filepath = potentialPath;
                    startIndex = 2;
                }
            }
        } else {
            cmd.filepath = rawTokens.get(0);
            startIndex = 1;
        }

        for (int i = startIndex; i < rawTokens.size(); i++) {
            String token = rawTokens.get(i);
            
            List<String> flagsTarget = isSnippetScope ? cmd.snippetFlags : cmd.macroFlags;
            List<String> optionsTarget = isSnippetScope ? cmd.snippetOptions : cmd.macroOptions;
            List<String> tokensTarget = isSnippetScope ? cmd.snippetTokens : cmd.macroTokens;

            if (token.startsWith("--")) {
                optionsTarget.add(token);
            } else if (token.startsWith("-")) {
                flagsTarget.add(token);
            } else {
                tokensTarget.add(token);
            }
        }
    }
}