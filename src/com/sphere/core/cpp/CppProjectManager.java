package com.sphere.core.cpp;

import java.io.File;
import java.util.*;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;
import com.sphere.utils.SecurityManager;

/**
 * Intelligent C++ Project Workspace Lifecycle Orchestrator.
 * Supports automated build-system blueprint fingerprinting, out-of-source build isolation,
 * and contextual dynamic toolchain generation parameters.
 */
public final class CppProjectManager {

    public enum BuildSystem {
        CMAKE("CMakeLists.txt"),
        NINJA("build.ninja"),
        MAKEFILE("Makefile");

        private final String continuousMarkerFile;
        BuildSystem(String marker) { this.continuousMarkerFile = marker; }
        public String getMarkerFile() { return continuousMarkerFile; }
    }

    private final File rootDirectory;
    private final File buildDirectory;
    private final SettingsManager settings;
    private BuildSystem buildSystem;

    /**
     * Instantiates the manager and automatically fingerprints the workspace to detect the C++ build system.
     */
    public CppProjectManager(File rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "Root workspace folder context cannot be null.");
        this.buildDirectory = new File(rootDirectory, "build");
        this.settings = new SettingsManager();
        this.buildSystem = autoDetectBuildSystem();
    }

    public File getRootDirectory() { return rootDirectory; }
    public File getBuildDirectory() { return buildDirectory; }
    public BuildSystem getBuildSystem() { return buildSystem; }
    public void setBuildSystem(BuildSystem buildSystem) { this.buildSystem = buildSystem; }

    /**
     * Inspects filesystem structures inside the workspace directory to determine project blueprint formats.
     */
    public BuildSystem autoDetectBuildSystem() {
        if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
            AppLogger.error("Target project path context is missing or invalid: " + rootDirectory.getAbsolutePath());
            return BuildSystem.CMAKE; // Intelligent fallback default initialization configuration
        }

        for (BuildSystem system : BuildSystem.values()) {
            File marker = new File(rootDirectory, system.getMarkerFile());
            if (marker.exists() && marker.isFile()) {
                AppLogger.info("Automatically fingerprinted C++ build structure type: " + system.name());
                return system;
            }
        }

        AppLogger.info("No explicit build blueprint discovered. Falling back to default CMAKE framework layout.");
        return BuildSystem.CMAKE;
    }

    /**
     * Generates system pipeline argument strings targeting initial environment setup.
     * Evaluates security settings before returning execution structures.
     */
    public List<String> configure(String additionalArguments) {
        return configure(additionalArguments == null || additionalArguments.isBlank()
            ? List.of() : CppBackend.tokenizeArguments(additionalArguments.trim()));
    }

    /**
     * The same, from arguments already separated.
     *
     * A caller that has the words of the command line must not glue them back
     * together for this: -G "Unix Makefiles" is one word carrying a space, and
     * a round trip through a single string loses the quotes that said so.
     */
    public List<String> configure(List<String> additionalArguments) {
        List<String> cmd = new ArrayList<>();
        ensureBuildDirectoryExists();

        List<String> resolvedArgs = (additionalArguments == null) ? List.of() : additionalArguments;
        // The same gate as before, on the line as a whole. It is not applied to
        // each argument on its own: isCommandSafe reads the first token as a
        // Python module name, which "Unix Makefiles" is not.
        String asOneLine = String.join(" ", resolvedArgs).trim();
        if (!asOneLine.isEmpty() && !SecurityManager.isCommandSafe(asOneLine)) {
            throw new SecurityException("Security Violation: Detected malicious parameter injections in project build config parameters.");
        }

        switch (buildSystem) {
            case CMAKE:
                cmd.add(resolveExecutablePath("CPP_CMAKE_EXEC", "cmake"));
                cmd.add("-S");
                cmd.add(rootDirectory.getAbsolutePath()); // Source tree reference folder
                cmd.add("-B");
                cmd.add(buildDirectory.getAbsolutePath());  // Out-of-source binary object dump directory
                
                cmd.addAll(resolvedArgs);
                break;

            case NINJA:
            case MAKEFILE:
                // Ninja and classic Makefiles natively do not define a separate operational 'configure' stage step
                AppLogger.info("Build system context [" + buildSystem.name() + "] skips explicit standalone configuration staging.");
                break;
        }
        return cmd;
    }

    /**
     * Formulates system execution argument strings to run compile operations.
     */
    public List<String> build(String target) {
        List<String> cmd = new ArrayList<>();
        String cleanTarget = (target != null) ? target.trim() : "";

        switch (buildSystem) {
            case CMAKE:
                cmd.add(resolveExecutablePath("CPP_CMAKE_EXEC", "cmake"));
                cmd.add("--build");
                cmd.add(buildDirectory.getAbsolutePath());
                if (!cleanTarget.isEmpty()) {
                    cmd.add("--target");
                    cmd.add(cleanTarget);
                }
                // One core is what cmake and make use when nobody says
                // otherwise, which on an eight-core machine is a build eight
                // times longer than it needs to be. ninja is already parallel.
                cmd.add("--parallel");
                cmd.add(String.valueOf(buildJobs()));
                break;

            case NINJA:
                cmd.add(resolveExecutablePath("CPP_NINJA_EXEC", "ninja"));
                cmd.add("-C");
                cmd.add(rootDirectory.getAbsolutePath());
                if (!cleanTarget.isEmpty()) {
                    cmd.add(cleanTarget);
                }
                break;

            case MAKEFILE:
                cmd.add(resolveExecutablePath("CPP_MAKE_EXEC", "make"));
                cmd.add("-C");
                cmd.add(rootDirectory.getAbsolutePath());
                cmd.add("-j" + buildJobs());
                if (!cleanTarget.isEmpty()) {
                    cmd.add(cleanTarget);
                }
                break;
        }
        return cmd;
    }

    /**
     * Prepares commands to scrub workspace environments and wipe cached artifacts.
     */
    public List<String> clean() {
        List<String> cmd = new ArrayList<>();
        switch (buildSystem) {
            case CMAKE:
                cmd.add(resolveExecutablePath("CPP_CMAKE_EXEC", "cmake"));
                cmd.add("--build");
                cmd.add(buildDirectory.getAbsolutePath());
                cmd.add("--target");
                cmd.add("clean");
                break;

            case NINJA:
                cmd.add(resolveExecutablePath("CPP_NINJA_EXEC", "ninja"));
                cmd.add("-C");
                cmd.add(rootDirectory.getAbsolutePath());
                cmd.add("clean");
                break;

            case MAKEFILE:
                cmd.add(resolveExecutablePath("CPP_MAKE_EXEC", "make"));
                cmd.add("-C");
                cmd.add(rootDirectory.getAbsolutePath());
                cmd.add("clean");
                break;
        }
        return cmd;
    }

    /**
     * How many compilations run at once: CPP_BUILD_JOBS, or the core count.
     */
    public int buildJobs() {
        String declared = settings.getProperty("CPP_BUILD_JOBS");
        if (declared != null) {
            try {
                int asked = Integer.parseInt(declared.trim());
                if (asked > 0) {
                    return asked;
                }
            } catch (NumberFormatException notANumber) {
                AppLogger.error("CPP_BUILD_JOBS is not a number: " + declared);
            }
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** True when the root carries the marker file of a real build system. */
    public boolean hasBuildSystem() {
        for (BuildSystem system : BuildSystem.values()) {
            File marker = new File(rootDirectory, system.getMarkerFile());
            if (marker.isFile()) {
                return true;
            }
        }
        return false;
    }

    private void ensureBuildDirectoryExists() {
        if (!buildDirectory.exists() && !buildDirectory.mkdirs()) {
            AppLogger.error("Failed to provision out-of-source target build folder allocation: " + buildDirectory.getAbsolutePath());
        }
    }

    private String resolveExecutablePath(String propertyKey, String defaultBinary) {
        String resolved = settings.resolveTool(propertyKey, defaultBinary);
        if (resolved == null || !SecurityManager.isCommandSafe(resolved)) {
            return defaultBinary;
        }
        return resolved;
    }
}