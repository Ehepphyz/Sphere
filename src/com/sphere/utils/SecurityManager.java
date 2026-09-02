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
     * Inspects incoming execution syntax against critical keywords,
     * benign system flags, and the local installed package metadata ecosystem.
     */
    public static boolean isCommandSafe(String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }

        String cleaned = code.trim();

        // Tier 1: Bypass completely if explicitly logged inside trusted list
        if (isTrustedCommand(cleaned)) {
            AppLogger.info("Security: Trusted command bypass active for: " + cleaned);
            return true;
        }

        // Tier 2: Intercept global hard-blocked keywords to protect the underlying host runtime
        String[] forbiddenKeywords = {"eval(", "exec(", "subprocess", "socket", "open("};
        for (String keyword : forbiddenKeywords) {
            if (cleaned.contains(keyword)) {
                AppLogger.error("Security: Command blocked for safety reasons. '" + keyword + "' is dangerous.");
                return false;
            }
        }

        // Tier 3: Handle direct, informational Python CLI options (e.g., -V, --version, --help)
        if (cleaned.startsWith("-") || cleaned.equalsIgnoreCase("help()")) {
            return true;
        }

        // Tier 4: Modular package execution routing (e.g., allow ":py pip list" if "pip" is whitelisted)
        // Tokenize command context to isolate the targeted execution hook token
        String[] tokens = cleaned.split("\\s+");
        if (tokens.length > 0) {
            String baseModule = tokens[0];
            if (isModuleAllowed(baseModule)) {
                return true;
            }
        }

        // Fallback catch-all error handling
        //AppLogger.error("Security: Command blocked for safety reasons. '" + cleaned + "' is not whitelisted.");
        return false; 
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

    private static boolean isTrustedCommand(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }

        final String cleanedCode = code.trim().replaceAll("^\"|\"$", "");

        try {
            File trustedFile = new File(TRUSTED_FILE);
            if (!trustedFile.exists()) {
                return false;
            }

            try (java.util.stream.Stream<String> lines = Files.lines(trustedFile.toPath())) {
                return lines.anyMatch(line -> line.trim().equals(cleanedCode));
            }

        } catch (IOException e) {
            AppLogger.error("Security: Failed to read trusted_commands.src: " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes the whitelist using 'pip list --format=freeze'.
     */
    public static void refreshWhitelist() {
        SettingsManager settings = new SettingsManager();
        String pythonPath = settings.resolvePath("PYTHON_EXEC", "python.exe");

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
