package com.sphere.components.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structural configuration data model representation housing core metadata parameters, 
 * experiment target alignments, plugin extensions, and lifecycle change timestamps.
 */
public class ProjectManifest {

    // --- Core Properties ---
    public String projectName = "";
    public String projectRoot = "";

    public String experiment = "Generic"; 
    public String presetVersion = "1.0.0";

    public String description = "";
    public List<String> tags = new ArrayList<>();

    public String projectType = "Analysis";

    // --- Custom Schema Plugin Domain Extensions ---
    public String customTypeName = "";
    public String customTypeDescription = "";
    public List<String> customTags = new ArrayList<>();
    public String customIcon = "";
    public String customWorkflowTemplate = "";
    public String customDefaultStructure = "";

    public String notes = "";

    // --- Lifecycle Audit Tracking Timestamps ---
    public String createdAt = "";
    public String modifiedAt = "";

    /**
     * Standard default constructor.
     */
    public ProjectManifest() {
    }

    /**
     * Copy constructor for defensive structural cloning.
     * @param source The source object to clone.
     */
    public ProjectManifest(ProjectManifest source) {
        if (source == null) return;
        
        this.projectName = source.projectName;
        this.projectRoot = source.projectRoot;
        this.experiment = source.experiment;
        this.presetVersion = source.presetVersion;
        this.description = source.description;
        this.projectType = source.projectType;
        this.notes = source.notes;
        this.createdAt = source.createdAt;
        this.modifiedAt = source.modifiedAt;
        
        this.customTypeName = source.customTypeName;
        this.customTypeDescription = source.customTypeDescription;
        this.customIcon = source.customIcon;
        this.customWorkflowTemplate = source.customWorkflowTemplate;
        this.customDefaultStructure = source.customDefaultStructure;

        this.tags = (source.tags != null) ? new ArrayList<>(source.tags) : new ArrayList<>();
        this.customTags = (source.customTags != null) ? new ArrayList<>(source.customTags) : new ArrayList<>();
    }

    /**
     * Ensures that the manifest contains valid default values for critical fields
     * when instantiated or loaded from persistent storage.
     */
    public void ensureDefaults() {
        if (this.projectType == null || this.projectType.isBlank()) {
            this.projectType = "Analysis";
        }
        if (this.tags == null) {
            this.tags = new ArrayList<>();
        }
        if (this.experiment == null || this.experiment.isBlank()) {
            this.experiment = "Generic";
        }
    }

    // --- Getters and Setters ---

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = Objects.requireNonNullElse(projectName, "").trim(); }

    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String projectRoot) { this.projectRoot = Objects.requireNonNullElse(projectRoot, "").trim(); }

    public String getExperiment() { return experiment; }
    public void setExperiment(String experiment) { this.experiment = Objects.requireNonNullElse(experiment, "Generic").trim(); }

    public String getPresetVersion() { return presetVersion; }
    public void setPresetVersion(String presetVersion) { this.presetVersion = Objects.requireNonNullElse(presetVersion, "").trim(); }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = Objects.requireNonNullElse(description, "").trim(); }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = (tags != null) ? new ArrayList<>(tags) : new ArrayList<>(); }

    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = Objects.requireNonNullElse(projectType, "").trim(); }

    public String getCustomTypeName() { return customTypeName; }
    public void setCustomTypeName(String customTypeName) { this.customTypeName = Objects.requireNonNullElse(customTypeName, "").trim(); }

    public String getCustomTypeDescription() { return customTypeDescription; }
    public void setCustomTypeDescription(String customTypeDescription) { this.customTypeDescription = Objects.requireNonNullElse(customTypeDescription, "").trim(); }

    public List<String> getCustomTags() { return customTags; }
    public void setCustomTags(List<String> customTags) { this.customTags = (customTags != null) ? new ArrayList<>(customTags) : new ArrayList<>(); }

    public String getCustomIcon() { return customIcon; }
    public void setCustomIcon(String customIcon) { this.customIcon = Objects.requireNonNullElse(customIcon, "").trim(); }

    public String getCustomWorkflowTemplate() { return customWorkflowTemplate; }
    public void setCustomWorkflowTemplate(String customWorkflowTemplate) { this.customWorkflowTemplate = Objects.requireNonNullElse(customWorkflowTemplate, "").trim(); }

    public String getCustomDefaultStructure() { return customDefaultStructure; }
    public void setCustomDefaultStructure(String customDefaultStructure) { this.customDefaultStructure = Objects.requireNonNullElse(customDefaultStructure, "").trim(); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = Objects.requireNonNullElse(notes, ""); }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = Objects.requireNonNullElse(createdAt, "").trim(); }

    public String getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(String modifiedAt) { this.modifiedAt = Objects.requireNonNullElse(modifiedAt, "").trim(); }
}
