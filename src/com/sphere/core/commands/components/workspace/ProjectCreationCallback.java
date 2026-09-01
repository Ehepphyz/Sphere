package com.sphere.components.workspace;

/**
 * Functional observer contract designed to receive lifecycle interception alerts 
 * immediately following the successful generation and disk initialization of a new project workspace.
 */
@FunctionalInterface
public interface ProjectCreationCallback {
    
    /**
     * Invoked immediately after a new project filesystem structure and its 
     * corresponding metadata descriptors are safely provisioned onto local storage.
     * * @param projectName the verified naming designation of the newly initialized project space.
     */
    void onProjectCreated(String projectName);
}
