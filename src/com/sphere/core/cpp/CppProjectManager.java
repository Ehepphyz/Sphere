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
        List<String> cmd = new ArrayList<>();
        ensureBuildDirectoryExists();

        String resolvedArgs = (additionalArguments != null) ? additionalArguments.trim() : "";
        if (!resolvedArgs.isEmpty() && !SecurityManager.isCommandSafe(resolvedArgs)) {
            throw new SecurityException("Security Violation: Detected malicious parameter injections in project build config parameters.");
        }

        switch (buildSystem) {
            case CMAKE:
                cmd.add(resolveExecutablePath("CPP_CMAKE_EXEC", "cmake"));
                cmd.add("-S");
                cmd.add(rootDirectory.getAbsolutePath()); // Source tree reference folder
                cmd.add("-B");
                cmd.add(buildDirectory.getAbsolutePath());  // Out-of-source binary object dump directory
                
                // Parse optional parameters if supplied (e.g., -G "Ninja" -DCMAKE_BUILD_TYPE=Release)
                if (!resolvedArgs.isEmpty()) {
                    cmd.addAll(Arrays.asList(resolvedArgs.split("\\s+")));
                }
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