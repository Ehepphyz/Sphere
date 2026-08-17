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

        // Context 1: Direct structural path parameter validation
        File direct = new File(normalizedName);
        if (direct.exists()) {
            return direct.getAbsolutePath();
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

        return name;
    }
}
