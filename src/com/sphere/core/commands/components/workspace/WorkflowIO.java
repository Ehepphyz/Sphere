package com.sphere.components.workspace;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes a project's .workflow file.
 *
 * The creation window writes it once and the settings window changes it later,
 * so both go through here rather than each formatting the same document.
 */
public final class WorkflowIO {

    public static final String FILE_NAME = ".workflow";

    private WorkflowIO() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The file's content, or an empty document when it is missing or unreadable. */
    public static Map<String, Object> load(Path projectDirectory) {
        Path target = projectDirectory.resolve(FILE_NAME);
        if (!Files.exists(target)) {
            return new LinkedHashMap<>();
        }
        try {
            return MinimalJson.parse(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            AppLogger.error("Could not read " + target + ": " + unreadable.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Writes the file back, with its pipelines rebuilt from the modules.
     *
     * A project that no longer uses Geant4 has no business keeping a Geant4
     * step, so the steps are derived rather than carried over.
     */
    public static void save(Path projectDirectory, Map<String, Object> workflow)
            throws IOException {
        Map<String, Object> pipelines = new LinkedHashMap<>();
        pipelines.put("simulation", steps(workflow,
            new String[] {"geant4", "madgraph", "herwig"},
            new String[] {"geant4:run", "madgraph:generate", "herwig:shower"}));
        pipelines.put("analysis", steps(workflow,
            new String[] {"root", "python"},
            new String[] {"root:macro", "python:script"}));

        workflow.putIfAbsent("buildSystem", "cmake");
        workflow.put("pipelines", pipelines);

        Files.writeString(projectDirectory.resolve(FILE_NAME), MinimalJson.toJson(workflow),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Whether the project declares that module. */
    public static boolean uses(Map<String, Object> workflow, String module) {
        return workflow.get("modules") instanceof Map<?, ?> modules
            && Boolean.TRUE.equals(modules.get(module));
    }

    /** What the module's entry says, or the fallback when it says nothing. */
    public static String entry(Map<String, Object> workflow, String module, String key,
                               String fallback) {
        if (workflow.get("entries") instanceof Map<?, ?> entries
            && entries.get(module) instanceof Map<?, ?> where
            && where.get(key) != null) {
            final String value = String.valueOf(where.get(key)).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    /** Records a module's state, dropping its entry when it is no longer used. */
    @SuppressWarnings("unchecked")
    public static void record(Map<String, Object> workflow, String module, boolean used,
                              String folder, String file) {
        Map<String, Object> modules = workflow.get("modules") instanceof Map<?, ?> found
            ? (Map<String, Object>) found : new LinkedHashMap<>();
        modules.put(module, used);
        workflow.put("modules", modules);

        Map<String, Object> entries = workflow.get("entries") instanceof Map<?, ?> found
            ? (Map<String, Object>) found : new LinkedHashMap<>();
        if (used) {
            Map<String, Object> where = new LinkedHashMap<>();
            where.put("folder", folder);
            where.put("file", file);
            entries.put(module, where);
        } else {
            entries.remove(module);
        }
        workflow.put("entries", entries);
    }

    private static List<String> steps(Map<String, Object> workflow, String[] modules,
                                      String[] named) {
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < modules.length; i++) {
            if (uses(workflow, modules[i])) {
                chosen.add(named[i]);
            }
        }
        return chosen;
    }
}
