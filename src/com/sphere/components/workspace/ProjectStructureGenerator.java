package com.sphere.components.workspace;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/**
 * Enterprise filesystem layout manager responsible for creating experiment-specific 
 * directory hierarchies, generating boilerplate configurations, and provisioning custom workspace environments.
 */
public final class ProjectStructureGenerator {

    // Suppress default constructor to enforce utility pattern constraints
    private ProjectStructureGenerator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Entrypoint orchestrating target local directory layout creation runs.
     * @param manifest the active project configuration layout metadata.
     * @throws IOException if a file system write error or permission collision occurs.
     */
    public static void generate(ProjectManifest manifest) throws IOException {
        Objects.requireNonNull(manifest, "Project manifest context cannot be null.");
        if (manifest.projectRoot == null || manifest.projectRoot.isBlank()) {
            throw new IllegalArgumentException("Target project root system directory path is blank.");
        }

        Path rootPath = Paths.get(manifest.projectRoot);

        // Ensure baseline workspace container is mounted safely before injecting children
        mkdir(rootPath);

        switch (manifest.experiment) {
            case "ATLAS" -> generateATLAS(rootPath);
            case "CMS" -> generateCMS(rootPath);
            case "LHCb" -> generateLHCb(rootPath);
            case "Belle II" -> generateBelleII(rootPath);
            case "Custom" -> generateCustom(rootPath, manifest);
            default -> throw new IllegalArgumentException("Unknown research experiment configuration template target: " + manifest.experiment);
        }
    }

    private static void mkdir(Path targetPath) throws IOException {
        Files.createDirectories(targetPath);
    }

    private static void mkfile(Path targetPath, String documentContent) throws IOException {
        if (!Files.exists(targetPath)) {
            Files.writeString(targetPath, documentContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    // --- ATLAS Blueprint Setup ---
    private static void generateATLAS(Path root) throws IOException {
        mkdir(root.resolve("analysis"));
        mkdir(root.resolve("ntuples"));
        mkdir(root.resolve("config"));
        mkdir(root.resolve("plots"));
        mkdir(root.resolve("scripts"));

        mkfile(root.resolve("README.md"), """
                # ATLAS Research Framework Workspace
                
                Generated automatically by Sphere.
                """);

        mkfile(root.resolve("config/atlas_config.yaml"), """
                atlas:
                  release: 24.2.1
                  geometry: Run3
                """);
    }

    // --- CMS Blueprint Setup ---
    private static void generateCMS(Path root) throws IOException {
        mkdir(root.resolve("src"));
        mkdir(root.resolve("data"));
        mkdir(root.resolve("ntuples"));
        mkdir(root.resolve("plots"));
        mkdir(root.resolve("cfg"));

        mkfile(root.resolve("README.md"), """
                # CMS Analysis Environment
                
                Generated automatically by Sphere.
                """);

        mkfile(root.resolve("cfg/cms_config.py"), """
                import FWCore.ParameterSet.Config as cms
                process = cms.Process('Analysis')
                """);
    }

    // --- LHCb Blueprint Setup ---
    private static void generateLHCb(Path root) throws IOException {
        mkdir(root.resolve("options"));
        mkdir(root.resolve("data"));
        mkdir(root.resolve("analysis"));
        mkdir(root.resolve("plots"));

        mkfile(root.resolve("README.md"), """
                # LHCb Experiment Framework Workspace
                
                Generated automatically by Sphere.
                """);

        mkfile(root.resolve("options/lhcb_opts.py"), """
                # LHCb runtime tracking choices script options file
                """);
    }

    // --- Belle II Blueprint Setup ---
    private static void generateBelleII(Path root) throws IOException {
        mkdir(root.resolve("basf2"));
        mkdir(root.resolve("data"));
        mkdir(root.resolve("analysis"));
        mkdir(root.resolve("plots"));

        mkfile(root.resolve("README.md"), """
                # Belle II basf2 Tracking Blueprint
                
                Generated automatically by Sphere.
                """);

        mkfile(root.resolve("basf2/basf2_config.py"), """
                # Belle II analysis software framework basf2 configuration path setup
                """);
    }

    // --- Dynamic User-Defined Custom Layout Engine ---
    private static void generateCustom(Path root, ProjectManifest manifest) throws IOException {
        mkdir(root.resolve("custom"));

        // Evaluate user-defined path lists safely (Splits on commas, tabs, or newlines)
        if (manifest.customDefaultStructure != null && !manifest.customDefaultStructure.isBlank()) {
            String[] segments = manifest.customDefaultStructure.split("[,\\n\\r;]+");
            for (String segment : segments) {
                String cleanPath = segment.trim();
                if (!cleanPath.isEmpty()) {
                    // Prevent path-traversal attacks escaping project root
                    Path targetSubFolder = root.resolve(cleanPath).normalize();
                    if (targetSubFolder.startsWith(root)) {
                        mkdir(targetSubFolder);
                    }
                }
            }
        }

        // Build descriptive metadata profiles
        String name = manifest.customTypeName != null ? manifest.customTypeName.strip() : "GenericCustom";
        String description = manifest.customTypeDescription != null ? manifest.customTypeDescription.strip() : "No abstract details provided.";

        String readmeContent = String.format("""
                # Custom Specialized Project Profile: %s
                
                ## Context Summary Description
                %s
                
                Generated automatically by Sphere custom preset pipelines.
                """, name, description);

        mkfile(root.resolve("custom/README.md"), readmeContent);
    }
}
