package com.sphere.components.fileexplorerincludes;

import java.io.File;

/**
 * A tree of files the context menu acts on: the explorer of the main window,
 * and the one at the left of SphereCoder. The menu changes the disk, then
 * asks the tree to show it.
 */
public interface FileTreeView {

    /** Reads a folder again and shows what it now holds. */
    void refreshNode(File folder);

    /** True when dot files are listed. */
    boolean isShowHidden();

    void setShowHidden(boolean visible);

    /**
     * A file or a folder now lives elsewhere (renamed or moved), so the
     * editors showing it, or a file inside it, can follow it there.
     */
    default void fileMoved(File from, File to) {
    }
}
