package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.commandrouterincludes.Tokenizer;
import com.sphere.core.fs.WorkingDirectory;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SecurityManager;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

/**
 * Acts as the ultimate fallback dispatcher, executing system-level shell commands.
 * Fully managed with dynamic cancellation tracking controls for asynchronous processes.
 */
public class SystemDispatcher implements CommandDispatcher {

    @Override
    public boolean canHandle(String input) {
        return true; // Last resort: handles anything not caught by other dispatchers
    }

    @Override
    public void handle(String command, CommandContext ctx) {
        String trimmed = command.trim();
        
        // Intercept special internal engine process termination syntax
        if (trimmed.startsWith(":kill ")) {
            // Use the centralized static tokenizer instance to safely isolate parameters
            List<String> tokens = Tokenizer.DEFAULT.tokenize(trimmed);
            if (tokens.size() > 1) {
                String processToken = tokens.get(1);
                if (ctx.killProcess(processToken)) {
                    AppLogger.info("Process '" + processToken + "' was successfully terminated by user request.");
                } else {
                    AppLogger.error("No active running process found registered under token: " + processToken);
                }
            } else {
                AppLogger.error("Usage: :kill <process_token>");
            }
            return;
        }

        if (trimmed.startsWith("cd ")) {
            handleCd(trimmed.substring(3).trim(), ctx);
        } else {
            runSystemCommand(trimmed, ctx);
        }
    }

    /**
     * Updates the internal shell runtime context tracking directories.
     */
    private void handleCd(String targetDir, CommandContext ctx) {
        boolean success = WorkingDirectory.changeTo(targetDir);
        if (success) {
            Path resolved = WorkingDirectory.get();
            if (ctx.statusBarUpdater != null) {
                SwingUtilities.invokeLater(() -> ctx.statusBarUpdater.accept(resolved.toString()));
            }
        } else {
            AppLogger.error("Directory not found or invalid: " + targetDir);
        }
    }

    /**
     * Executes arbitrary safe native terminal scripts asynchronously using a background execution worker.
     */
    private void runSystemCommand(String command, CommandContext ctx) {
        if (!SecurityManager.isCommandSafe(command)) {
            AppLogger.error("Security: System command blocked: " + command);
            return;
        }

        // Safely extract the root execution token for registration mapping using the shared tokenizer
        List<String> tokens = Tokenizer.DEFAULT.tokenize(command);
        final String processToken = tokens.isEmpty() ? "unknown" : tokens.get(0);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
                ProcessBuilder pb = isWindows 
                    ? new ProcessBuilder("cmd.exe", "/c", command) 
                    : new ProcessBuilder("sh", "-c", command);

                pb.redirectErrorStream(true);
                pb.directory(WorkingDirectory.get().toFile());

                Process process = pb.start();
                
                // Register the process into execution visibility tracking bounds immediately
                ctx.registerProcess(processToken, process);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                } finally {
                    process.waitFor();
                    // Unregister process upon standard stream exit exhaustion
                    ctx.unregisterProcess(processToken);
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    AppLogger.raw(line);
                }
            }
        };
        worker.execute();
    }
}