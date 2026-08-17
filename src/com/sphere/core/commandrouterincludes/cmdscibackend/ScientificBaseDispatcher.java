package com.sphere.core.commandrouterincludes.cmdscibackend;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.commandrouterincludes.CommandParser;
import com.sphere.core.commandrouterincludes.ParsedCommand;
import com.sphere.core.snippets.TagInterpreter;
import com.sphere.utils.AppLogger;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Base class for all high-energy physics scientific tool dispatchers.
 * Aligns tightly with the CommandParser layout and performs pre-flight executable availability scans.
 */
public abstract class ScientificBaseDispatcher implements CommandDispatcher {

    @Override
    public void handle(String input, CommandContext ctx) {
        if (input == null || input.strip().isEmpty()) {
            AppLogger.error("Received null or empty execution payload command string.");
            return;
        }

        // Parse the input via the centralized universal grammar rules engine
        ParsedCommand parsed = CommandParser.parse(input);
        
        if (parsed == null) {
            AppLogger.error("Failed to parse the target command. Input layout format is invalid.");
            return;
        }

        // Validate executable system path configuration requirements before launching backend
        if (!isExecutableAvailable(getBinaryName())) {
            AppLogger.error("Environment Error: Scientific tool executable binary '" + getBinaryName() + 
                            "' was not found in the system PATH layout configuration.");
            AppLogger.error("Please verify your local target toolchain environment installation dependencies.");
            return;
        }

        // Assemble the argument payload strings from the public fields of ParsedCommand
        String argumentPayload = String.join(" ", parsed.macroTokens);
        String finalPayload = argumentPayload;

        // Extract legacy snippets tags if explicit manual bypass sequences are intercepted
        if (input.contains("[@")) {
            int openBracket = input.indexOf("[@");
            int closeBracket = input.indexOf("]");
            if (openBracket != -1 && closeBracket != -1 && closeBracket > openBracket) {
                String snippetKey = input.substring(openBracket + 2, closeBracket).trim();
                String resolvedSnippet = TagInterpreter.resolve(snippetKey, ctx.getActiveProject());
                
                // Construct parameters cleanly from public list wrappers
                String optionsAndTrailing = String.join(" ", parsed.macroOptions) + " " + String.join(" ", parsed.macroTokens);
                finalPayload = (resolvedSnippet + " " + optionsAndTrailing).trim();
            }
        }

        AppLogger.info("Dispatching securely to scientific backend processor: " + getToolName());
        executeScientificCommand(finalPayload, parsed, ctx);
    }

    /**
     * Cross-platform check to verify whether a specific system executable binary is present in the environmental PATH setup.
     */
    private boolean isExecutableAvailable(String binary) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;

        String separator = File.pathSeparator;
        String[] directories = pathEnv.split(Pattern.quote(separator));
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        for (String dir : directories) {
            File binaryFile = new File(dir, binary);
            File windowsExeFile = new File(dir, binary + ".exe");
            File windowsCmdFile = new File(dir, binary + ".cmd");
            File windowsBatFile = new File(dir, binary + ".bat");

            if (binaryFile.exists() && !binaryFile.isDirectory()) return true;
            if (isWindows) {
                if (windowsExeFile.exists() && !windowsExeFile.isDirectory()) return true;
                if (windowsCmdFile.exists() && !windowsCmdFile.isDirectory()) return true;
                if (windowsBatFile.exists() && !windowsBatFile.isDirectory()) return true;
            }
        }
        return false;
    }

    protected abstract String getToolName();
    
    protected abstract String getBinaryName();
    
    protected abstract void executeScientificCommand(String resolvedArgs, ParsedCommand commandStructure, CommandContext ctx);
}