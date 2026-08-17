package com.sphere.core.commands;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.Tokenizer;
import java.util.List;

/**
 * Provides handlers with the execution environment and utility 
 * methods for argument parsing.
 * Formatted with U.S. English comments.
 */
public class CommandExecutionContext {
    public final CommandContext ctx;
    private final String rawInput;
    private final List<String> cachedTokens;

    public CommandExecutionContext(CommandContext ctx, String rawInput) {
        this.ctx = ctx;
        this.rawInput = rawInput;
        // Pre-tokenize the raw input using the robust framework tokenizer to handle quotes and spacing safely
        this.cachedTokens = Tokenizer.DEFAULT.tokenize(rawInput);
    }

    /**
     * Returns the raw input string for custom parsing.
     */
    public String getRawInput() {
        return rawInput;
    }

    /**
     * Returns the arguments after the command base.
     * Example: for ":project open MyProject", returns ["MyProject"]
     * Example: for ":set msg \"Hello World\"", returns ["msg", "Hello World"]
     */
    public String[] getArgs() {
        if (cachedTokens.isEmpty()) {
            return new String[0];
        }

        // Determine how many tokens belong to the command prefix signature itself
        int prefixTokenCount = 1;
        String firstToken = cachedTokens.get(0);
        
        // Match multi-token commands registered in CommandDefinitions (e.g., ":project open")
        if (cachedTokens.size() > 1) {
            String combinedTwo = firstToken + " " + cachedTokens.get(1);
            if (CommandDefinitions.all().containsKey(combinedTwo)) {
                prefixTokenCount = 2;
            } else if (cachedTokens.size() > 2) {
                String combinedThree = combinedTwo + " " + cachedTokens.get(2);
                if (CommandDefinitions.all().containsKey(combinedThree)) {
                    prefixTokenCount = 3;
                }
            }
        }

        // Extract everything remaining after the command prefix signature
        if (cachedTokens.size() <= prefixTokenCount) {
            return new String[0];
        }

        List<String> argList = cachedTokens.subList(prefixTokenCount, cachedTokens.size());
        return argList.toArray(new String[0]);
    }

    /**
     * Helper to get a specific argument by index.
     */
    public String getArg(int index) {
        String[] args = getArgs();
        return (index >= 0 && index < args.length) ? args[index] : null;
    }

    /**
     * Extracts a clean parameter value from the command string, supporting both 
     * space and assignment configurations (e.g., ":root max_size=2000" or ":root max_size 2000").
     * 
     * @param commandPrefix The exact command string to strip out (e.g., ":root max_size")
     * @return The isolated argument value, or an empty string if not found.
     */
    public String getCleanParamValue(String commandPrefix) {
        if (rawInput == null) return "";
        
        String args = rawInput.replace(commandPrefix, "").trim();
        if (args.startsWith("=")) {
            args = args.substring(1).trim();
        }
        
        // Strip outermost quotes if the parameter value is encapsulated
        if (args.startsWith("\"") && args.endsWith("\"") && args.length() >= 2) {
            args = args.substring(1, args.length() - 1);
        } else if (args.startsWith("'") && args.endsWith("'") && args.length() >= 2) {
            args = args.substring(1, args.length() - 1);
        }
        
        return args;
    }

    /**
     * Helper to check if a targeted parameter value is present and not empty.
     * 
     * @param commandPrefix The exact command string to verify (e.g., ":root max_size")
     * @return true if a value exists after the command prefix, false otherwise.
     */
    public boolean hasParamValue(String commandPrefix) {
        return !getCleanParamValue(commandPrefix).isEmpty();
    }
}