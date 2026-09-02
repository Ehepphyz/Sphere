package com.sphere.execution;

import com.sphere.utils.AppLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Enterprise Execution Module for Sphere.
 * Automatically extracts shebang interpreters, updates execution flags,
 * and isolates processing environments via sandbox configurations.
 */
public final class ShellScriptExecutor {

    private ShellScriptExecutor() {}

    /**
     * Inspects a command line payload, automatically manages scripts/permissions,
     * and constructs an isolated execution environment
     */
    public static ProcessBuilder prepareExecution(String rawCommand, Map<String, String> isolatedContext) {
        if (rawCommand == null || rawCommand.isBlank()) {
            throw new IllegalArgumentException("Execution command cannot be empty.");
        }

        String trimmed = rawCommand.trim();
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean isWindows = osName.contains("win");

        List<String> commandTokens = new ArrayList<>();

        // Scenario 1: Explicit shell sourcing instructions
        if (trimmed.startsWith("source ") || trimmed.startsWith(". ")) {
            if (isWindows) {
                commandTokens.add("cmd.exe");
                commandTokens.add("/c");
                String winPayload = trimmed.replaceAll("(?i)^(source|\\.)\\s+", "call ");
                commandTokens.add(winPayload);
            } else {
                commandTokens.add("/bin/bash");
                commandTokens.add("-c");
                commandTokens.add(trimmed);
            }
        } else {
            // Scenario 2: Parse standalone commands or direct file script pointers
            List<String> parsedTokens = splitCommand(trimmed);
            if (!parsedTokens.isEmpty()) {
                String primaryTarget = parsedTokens.get(0);
                boolean interpreterFound = false;

                try {
                    Path path = Path.of(primaryTarget);
                    if (Files.exists(path) && Files.isRegularFile(path)) {
                        String fileName = path.getFileName().toString();

                        if (!isWindows) {
                            ensureExecutable(path);

                            String shebang = readShebangHeader(path);
                            List<String> interpreterTokens = null;

                            if (shebang != null) {
                                interpreterTokens = splitShebangInterpreter(shebang);
                            }

                            if (interpreterTokens == null || interpreterTokens.isEmpty()) {
                                interpreterTokens = guessInterpreterFromExtension(fileName);
                            }

                            if (interpreterTokens != null && !interpreterTokens.isEmpty()) {
                                commandTokens.addAll(interpreterTokens);
                                commandTokens.add(path.toAbsolutePath().toString());
                                interpreterFound = true;
                            } else {
                                AppLogger.warn("Interpreter not found for script-like file: " + path.toAbsolutePath());
                            }

                        } else {
                            String fileNameLower = fileName.toLowerCase(Locale.ROOT);

                            if (fileNameLower.endsWith(".bat") || fileNameLower.endsWith(".cmd")) {
                                commandTokens.add("cmd.exe");
                                commandTokens.add("/c");
                                commandTokens.add(path.toAbsolutePath().toString());
                                interpreterFound = true;
                            } else {
                                List<String> winInterpreter = guessInterpreterFromExtension(fileName);
                                if (!winInterpreter.isEmpty()) {
                                    commandTokens.addAll(winInterpreter);
                                    commandTokens.add(path.toAbsolutePath().toString());
                                    interpreterFound = true;
                                }
                            }

                            if (!interpreterFound && looksLikeScript(fileNameLower)) {
                                AppLogger.warn("Interpreter not found for Windows script-like file: " + path.toAbsolutePath());
                            }
                        }

                        // Append trailing parameters if an interpreter path was generated
                        if (interpreterFound) {
                            for (int i = 1; i < parsedTokens.size(); i++) {
                                commandTokens.add(parsedTokens.get(i));
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Scenario 3: Standard native binary fallback if no script parameters match
                if (commandTokens.isEmpty()) {
                    commandTokens.addAll(parsedTokens);
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(commandTokens);

        if (isolatedContext != null && !isolatedContext.isEmpty()) {
            Map<String, String> env = pb.environment();
            env.putAll(isolatedContext);
        }

        return pb;
    }

    private static String readShebangHeader(Path path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String firstLine = reader.readLine();
            if (firstLine != null) {
                firstLine = firstLine.replace("\uFEFF", "");
                if (firstLine.startsWith("#!")) {
                    return firstLine.substring(2).trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static List<String> splitShebangInterpreter(String shebang) {
        List<String> tokens = new ArrayList<>();
        String[] parts = shebang.split("\\s+");
        for (String part : parts) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static List<String> guessInterpreterFromExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();

        if (lower.endsWith(".py")) {
            tokens.add("python3");
        } else if (lower.endsWith(".pl")) {
            tokens.add("perl");
        } else if (lower.endsWith(".rb")) {
            tokens.add("ruby");
        } else if (lower.endsWith(".php")) {
            tokens.add("php");
        } else if (lower.endsWith(".sh")) {
            tokens.add("/bin/bash");
        }

        return tokens;
    }

    private static boolean looksLikeScript(String fileNameLower) {
        return fileNameLower.endsWith(".sh") || fileNameLower.endsWith(".py") || 
               fileNameLower.endsWith(".pl") || fileNameLower.endsWith(".rb") || 
               fileNameLower.endsWith(".php");
    }

    private static void ensureExecutable(Path path) {
        try {
            File file = path.toFile();
            if (!file.canExecute()) {
                AppLogger.info("Applying script execution bits (chmod +x) onto: " + path.getFileName());
                file.setExecutable(true, false);
            }
        } catch (Exception e) {
            AppLogger.warn("Permissions modification aborted for " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private static List<String> splitCommand(String command) {
        List<String> list = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([^\"]\\S*|\".+?\")\\s*")
                .matcher(command);
        while (m.find()) {
            list.add(m.group(1).replace("\"", ""));
        }
        return list;
    }
}
