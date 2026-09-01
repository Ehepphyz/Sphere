package com.sphere.core.rootbackend;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RootCompilerManager {

    private static final String CONFIG_FILE = "settings.conf";
    private final Map<String, String> configMap = new HashMap<>();
    private boolean systemSafeMode = false; // Synchronized with the native C++ bridge state

    public RootCompilerManager() {
        loadAndResolveConfig();
    }

    /**
     * Activates or deactivates Safe Mode locally to block compilations before spawning processes.
     */
    public void setSafeMode(boolean enabled) {
        this.systemSafeMode = enabled;
    }

    public boolean isSafeMode() {
        return this.systemSafeMode;
    }

    /**
     * Parses settings.conf, extracts sections, and resolves dynamic references (e.g., $GPP_DIR).
     */
    private void loadAndResolveConfig() {
        File conf = new File(CONFIG_FILE);
        if (!conf.exists()) {
            return;
        }

        Map<String, String> rawVariables = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(conf))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                    continue; 
                }
                int splitIdx = line.indexOf('=');
                if (splitIdx != -1) {
                    String key = line.substring(0, splitIdx).trim();
                    String val = line.substring(splitIdx + 1).trim();
                    rawVariables.put(key, val);
                }
            }
        } catch (IOException e) {
            System.err.println("[Compiler] Failed to read configuration: " + e.getMessage());
        }

        // Resolve references like $GPP_DIR or $FCAD_BASE
        for (Map.Entry<String, String> entry : rawVariables.entrySet()) {
            String resolvedValue = resolveVariables(entry.getValue(), rawVariables);
            configMap.put(entry.getKey(), resolvedValue);
        }
    }

    private String resolveVariables(String value, Map<String, String> context) {
        if (value == null || !value.contains("$")) {
            return value;
        }
        String result = value;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            String placeholder = "$" + entry.getKey();
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, entry.getValue());
            }
        }
        return result;
    }

    public String getValue(String key, String defaultValue) {
        return configMap.getOrDefault(key, defaultValue);
    }

    /**
     * Compiles a C++ source file into a shared dynamic library (.so or .dll)
     * using the compiler and library paths configured in settings.conf
     */
    public boolean compileSharedLibrary(String sourcePath, String outputPath, List<String> extraFlags) {
        // Safe-guard validation
        if (systemSafeMode) {
            System.err.println("[Compiler] Compilation rejected: Safe Mode is currently ENABLED.");
            return false;
        }

        // Fetch paths from configuration
        String compiler = getValue("CPP_COMPILER_PATH", "g++");
        String rootDir = getValue("ROOT_DIR", "");
        
        // Detect OS for flag adjustments
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        List<String> command = new ArrayList<>();
        command.add(compiler);
        
        // Add core flags for shared library generation
        command.add("-shared");
        if (!isWindows) {
            command.add("-fPIC");
        }
        
        // Match ROOT's engine requirements (C++20)
        command.add("-std=c++20");

        // Inject ROOT include paths and libraries to prevent dlopen/dynamic load failures
        if (!rootDir.isEmpty()) {
            // Headers inclusion
            command.add("-I" + rootDir + File.separator + "include");
            
            // Library path inclusion
            command.add("-L" + rootDir + File.separator + "lib");
            
            // Link ROOT core dynamic components
            command.add("-lCore");
            command.add("-lRIO");
            command.add("-lNet");
            command.add("-lHist");
            command.add("-lGraf");
            command.add("-lGpad");
            command.add("-lTree");
            command.add("-lMathCore");
        }

        // Add user-provided compilation options
        if (extraFlags != null) {
            command.addAll(extraFlags);
        }

        // Define output and source targets
        command.add("-o");
        command.add(outputPath);
        command.add(sourcePath);

        System.out.println("[Compiler] Running command: " + String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO(); // Route errors and logs directly to the parent Java console
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                System.out.println("[Compiler] Library successfully generated: " + outputPath);
                return true;
            } else {
                System.err.println("[Compiler] Compilation failed with exit code: " + exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[Compiler] Execution failed: " + e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }
}