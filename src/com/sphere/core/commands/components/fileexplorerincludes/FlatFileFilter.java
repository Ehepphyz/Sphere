package com.sphere.components.fileexplorerincludes;

import java.io.File;

/**
 * Simple operational functional interface for evaluating custom workspace file visibility parameters.
 */
@FunctionalInterface
public interface FlatFileFilter {
    /**
     * Determines whether the specified file entry context passes validation constraints.
     * @param file The target file reference to check.
     * @return true if the item should be rendered visible inside the view layer list model.
     */
    boolean accept(File file);
}
