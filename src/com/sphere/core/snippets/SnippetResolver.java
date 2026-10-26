package com.sphere.core.snippets;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Validates dynamic script footprints across hierarchical project structures and internal repositories.
 * Accurately processes relative subdirectories to return verified absolute filesystem locations.
 */
public class SnippetResolver {

    /**
     * Evaluates local, global, or workspace paths—including nested subdirectories—to securely 
     * identify target file parameters.
     *
     * @param name          The target snippet tracking identifier or relative subdirectory path (e.g., "subfolder/script.py").
     * @param activeProject The active workspace project reference framework block.
     * @return The absolute path mapped onto the filesystem if discovered, otherwise the fallback identifier name.
     */
    public static String resolve(String name, String activeProject) {
        if (name == null || name.isBlank()) {
            return name;
        }

        // Normalize file separators to match the host Operating System rules (handles mixed / and \)
        String normalizedName = name.replace("/", File.separator).replace("\\", File.separator);

        // Context 1: Direct structural path parameter validation, in the folder
        // "cd" moved to. A bare File looked in the folder Sphere started in, so
        // after a cd the snippet was not found -- or a file of the same name
        // beside the launcher ran in its place.
        try {
            File direct = com.sphere.core.fs.WorkingDirectory.get().resolve(normalizedName).toFile();
            if (direct.exists()) {
                return direct.getAbsolutePath();
            }
        } catch (java.nio.file.InvalidPathException notAPath) {
            // A name this system cannot hold is no file here; the folders below may still know it.
        }
        // Then relative to Sphere's own folder, where it was looked for alone before.
        File besideSphere = new File(normalizedName);
        if (besideSphere.exists()) {
            return besideSphere.getAbsolutePath();
        }

        // Context 2: Global structural fallback asset tracking inside "snippets/" tree
        File global = new File("snippets", normalizedName);
        if (global.exists()) {
            return global.getAbsolutePath();
        }

        // Context 3: Scoped active project location sandbox directory evaluation
        if (activeProject != null && !activeProject.isBlank()) {
            File ws = new File("WorkSpace/" + activeProject + "/snippets", normalizedName);
            if (ws.exists()) {
                return ws.getAbsolutePath();
            }
        }

        // Context 4: the ROOT macros of user_scripts/, the project's before the
        // global ones as RootUserPipeline orders them, so [@ analyse.C] names a
        // macro the way [@ gen.py] names a snippet. Searched last, it changes
        // nothing for a name the folders above already answer.
        for (java.nio.file.Path layer : reversed(com.sphere.core.rootbackend.RootUserPipeline.layers(activeProject))) {
            File macro = layer.resolve(com.sphere.core.rootbackend.RootUserPipeline.SCRIPTS_DIR)
                              .resolve(normalizedName).toFile();
            if (macro.isFile()) {
                return macro.getAbsolutePath();
            }
        }

        return name;
    }

    private static <T> java.util.List<T> reversed(java.util.List<T> list) {
        java.util.List<T> copy = new java.util.ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
