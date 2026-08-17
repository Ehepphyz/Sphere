package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.fs.WorkingDirectory;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Provides user-friendly shortcuts and context-aware smart commands.
 */
public class SmartDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase().trim();
        return lower.equals("open last") ||
               lower.startsWith("find ") ||
               lower.startsWith("search ") ||
               lower.startsWith("plot ");
    }

    @Override
    public void handle(String cmd, CommandContext ctx) {
        String lower = cmd.toLowerCase().trim();

        if (lower.equals("open last")) {
            handleOpenLast();
        } else if (lower.startsWith("find ") || lower.startsWith("search ")) {
            String query = cmd.substring(cmd.indexOf(" ") + 1).trim();
            handleSearch(query);
        } else if (lower.startsWith("plot ")) {
            String expr = cmd.substring(5).trim();
            handlePlot(expr);
        }
    }

    private void handleOpenLast() {
        Path dir = WorkingDirectory.get();
        
        try (Stream<Path> stream = Files.list(dir)) {
            Path lastModifiedFile = stream
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .orElse(null);

            if (lastModifiedFile != null) {
                AppLogger.info("Opening last modified file: " + lastModifiedFile.getFileName());
            } else {
                AppLogger.error("No files found in current directory.");
            }
        } catch (IOException e) {
            AppLogger.error("Failed to check files in directory: " + e.getMessage());
        }
    }

    private void handleSearch(String query) {
        if (query.isBlank()) {
            AppLogger.error("Search query cannot be empty.");
            return;
        }
        AppLogger.info("Searching for: " + query);
    }

    private void handlePlot(String expr) {
        if (expr.isBlank()) {
            AppLogger.error("Plot expression cannot be empty.");
            return;
        }
        AppLogger.info("Plotting expression: " + expr);
    }
}
