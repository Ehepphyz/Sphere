package com.sphere.components.workspace;

import java.io.File;
import java.util.List;

/**
 * Listener contract for receiving real-time lifecycle notifications regarding 
 * root workspace updates, user target selections, and internal background file changes.
 */
public interface WorkspaceListener {
    
    /**
     * Invoked when projects are added, removed, or modified within the workspace root.
     * * @param projects an immutable snapshot list containing all current project directories.
     */
    void onWorkspaceChanged(List<File> projects);
    
    /**
     * Invoked when a different project directory becomes selected or targeted in the explorer UI.
     * * @param project the newly selected project directory file handle, or {@code null} if selection is cleared.
     */
    void onProjectSelected(File project);

    /**
     * Invoked when background polling threads detect a deep structural file update 
     * inside the active project directory on disk.
     * * @param project the active project directory that triggered the modification update.
     */
    default void onProjectStructureUpdated(File project) {}
}
