package com.sphere.core.commandrouterincludes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizes raw command strings, supporting quoted arguments, escaped quotes, and short/long flags.
 */
public class Tokenizer {

    public static final Tokenizer DEFAULT = new Tokenizer();

    // Advanced pattern: Group 1 handles escaped/standard quotes, Group 2 handles non-whitespace tokens
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|(\\S+)");

    /**
     * Splits the input string into a list of tokens while cleaning outer quotes.
     * 
     * @param input The raw input command line string.
     * @return A list of extracted, unescaped string tokens.
     */
    public List<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(input);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                // Remove the escaping backslashes from inside the quoted string
                tokens.add(matcher.group(1).replaceAll("\\\\(.)", "$1"));
            } else {
                tokens.add(matcher.group(2));
            }
        }
        return tokens;
    }
}