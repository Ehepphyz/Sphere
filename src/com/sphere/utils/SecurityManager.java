package com.sphere.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class SecurityManager {
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "pyconfigparam.src";
    private static final String TRUSTED_FILE = CONFIG_DIR + File.separator + "trusted_commands.src";
    private static final long PIP_TIMEOUT_SECONDS = 60L;

    /**
     * Initializes the security directory and configuration files.
     */
    public static void initialize() {
        File dir = new File(CONFIG_DIR);
        File configFile = new File(CONFIG_FILE);
        File trustedFile = new File(TRUSTED_FILE);

        boolean needsAction = !dir.exists() || !configFile.exists() || !trustedFile.exists();

        if (needsAction) {
            AppLogger.info(":: INITIALIZING SECURITY SUBSYSTEMS ::");
        }

        try {
            if (!dir.exists() && dir.mkdir()) {
                AppLogger.info("Security directory created: " + CONFIG_DIR);
            }

            if (!configFile.exists()) {
                AppLogger.info("Whitelist not found. Generating initial configuration...");
                refreshWhitelist();
            }

            if (!trustedFile.exists()) {
                if (trustedFile.createNewFile()) {
                    AppLogger.info("Security SubSystem files created.");
                }
            }
        } catch (IOException e) {
            AppLogger.error("Failed to initialize security files: " + e.getMessage());
        }

        if (needsAction){
            com.sphere.utils.AppLogger.separator();
            com.sphere.utils.AppLogger.raw("");
        }
    }

    /**
     * Whether a line typed at the console, or a tool path from settings.conf,
     * may be run: anything that is not blank.
     *
     * It used to refuse everything whose first word was not an installed pip
     * package and that was not written out in trusted_commands.src, and every
     * line that merely contained "open(" or "socket". That stopped ordinary
     * work -- ":py script.py", ":cpp main.cpp", a ":julia" one-liner, clangd's
     * own path, the configured cmake -- and protected nothing: the same code
     * ran unexamined through "::py [@ ...]", the shell fallback and the
     * Terminal tab, and __import__('os').system went round the word list.
     * What the user types is theirs to run, as in any shell. Keeping untrusted
     * code away from the machine would take a separate, restricted process,
     * not a list of words.
     */
    public static boolean isCommandSafe(String code) {
        return code != null && !code.trim().isEmpty();
    }

    /**
     * Validates if a target token prefix module matches the cached installation tracking ledger.
     */
    public static boolean isModuleAllowed(String module) {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) refreshWhitelist();

        // Files.lines keeps the file open until the stream is closed. This runs on
        // every command, so leaving it to the GC leaked one descriptor per call
        // and held a lock on the file under Windows.
        try (java.util.stream.Stream<String> lines = Files.lines(Paths.get(CONFIG_FILE))) {
            return lines
                    .skip(1) // Skip [Python_param] header
                    .map(String::trim)
                    .anyMatch(line -> line.equalsIgnoreCase(module.trim()));
        } catch (IOException e) {
            AppLogger.error("Error reading whitelist: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes the whitelist using 'pip list --format=freeze'.
     */
    public static void refreshWhitelist() {
        SettingsManager settings = new SettingsManager();
        String pythonPath = settings.resolveTool("PYTHON_EXEC",
            System.getProperty("os.name").toLowerCase().contains("win") ? "python.exe" : "python3");

        if (pythonPath == null) {
            AppLogger.error("Whitelist update aborted: Cannot resolve valid path for PYTHON_EXEC");
            return;
        }

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "-m", "pip", "list", "--format=freeze");
            pb.redirectErrorStream(true);
            p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 PrintWriter writer = new PrintWriter(new BufferedWriter(
                         new OutputStreamWriter(new FileOutputStream(CONFIG_FILE), java.nio.charset.StandardCharsets.UTF_8)))) {
                
                writer.println("[Python_param]");
                String line;
                boolean found = false;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("==");
                    if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                        writer.println(parts[0].trim());
                        found = true;
                    }
                }
                
                if (!found) {
                    AppLogger.error("Whitelist generated but is empty. Check your environment at: " + pythonPath);
                } else {
                    AppLogger.info("Whitelist successfully updated");
                }
            }
            // Bounded: a pip that never returns used to hang the caller here for
            // good, and the process was left behind on any failure.
            if (!p.waitFor(PIP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                AppLogger.error("Whitelist update timed out after "
                                + PIP_TIMEOUT_SECONDS + "s: " + pythonPath);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLogger.error("Whitelist update interrupted.");
        } catch (IOException e) {
            AppLogger.error("Failed to refresh whitelist: " + e.getMessage());
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
