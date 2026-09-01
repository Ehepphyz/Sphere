package com.sphere.components.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility validation layer responsible for running diagnostic checks on project manifest blueprints
 * and flagging structural layout rule violations before persistence operations execute.
 */
public final class Validator {

    // Compile-time constants for unified form field binding across Swing interfaces
    public static final String KEY_NAME = "name";
    public static final String KEY_PRESET = "preset";
    public static final String KEY_DESCRIPTION = "description";
    public static final String KEY_CUSTOM_NAME = "customName";
    public static final String KEY_CUSTOM_ICON = "customIcon";
    public static final String KEY_CUSTOM_STRUCTURE = "customStructure";

    private static final String TYPE_CUSTOM = "Custom";

    // Suppress default constructor to enforce utility pattern constraints
    private Validator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Inspects a project manifest instance and returns an ordered collection of any validation issues found.
     * * @param manifest the project manifest blueprint to validate.
     * @return a map containing the invalid field keys paired with their corresponding descriptive error messages.
     */
    public static Map<String, String> validate(ProjectManifest manifest) {
        // Guard against null targets completely to protect the system validation pipeline
        if (manifest == null) {
            Map<String, String> fatalIssue = new LinkedHashMap<>();
            fatalIssue.put("manifest", "Project manifest configuration cannot be null.");
            return fatalIssue;
        }

        Map<String, String> issues = new LinkedHashMap<>();

        // Validate basic baseline fields
        if (manifest.projectName == null || manifest.projectName.isBlank()) {
            issues.put(KEY_NAME, "Project name is required.");
        }

        if (manifest.presetVersion == null || manifest.presetVersion.isBlank()) {
            issues.put(KEY_PRESET, "Preset version is required.");
        }

        if (manifest.description == null || manifest.description.isBlank()) {
            issues.put(KEY_DESCRIPTION, "Description is empty.");
        }

        // Validate deep custom type specifications if matching structural contexts
        if (TYPE_CUSTOM.equals(manifest.projectType)) {
            if (manifest.customTypeName == null || manifest.customTypeName.isBlank()) {
                issues.put(KEY_CUSTOM_NAME, "Custom type name is required.");
            }
            if (manifest.customIcon == null || manifest.customIcon.isBlank()) {
                issues.put(KEY_CUSTOM_ICON, "Custom icon resource target is required.");
            }
            if (manifest.customDefaultStructure == null || manifest.customDefaultStructure.isBlank()) {
                issues.put(KEY_CUSTOM_STRUCTURE, "Custom structural blueprint is required.");
            }
        }

        return issues.isEmpty() ? Collections.emptyMap() : issues;
    }
}
