package com.sphere.core.commandrouterincludes;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a parsed command structure extracted from a raw input string,
 * splitting execution contexts between the root/macro wrapper and encapsulated snippets.
 */
public final class ParsedCommand {

    /**
     * Defines the root routing context types.
     */
    public enum RootType {
        COMMAND,
        MACRO
    }

    // Context metadata
    public RootType type;
    public String languageOrApp;
    public String filepath;
    public boolean hasSnippet = false;

    // Root / Macro level token tracking
    public final List<String> macroFlags = new ArrayList<>();
    public final List<String> macroOptions = new ArrayList<>();
    public final List<String> macroTokens = new ArrayList<>();

    // Encapsulated Snippet level token tracking
    public final List<String> snippetFlags = new ArrayList<>();
    public final List<String> snippetOptions = new ArrayList<>();
    public final List<String> snippetTokens = new ArrayList<>();
}