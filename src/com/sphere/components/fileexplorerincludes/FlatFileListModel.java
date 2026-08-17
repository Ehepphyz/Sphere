package com.sphere.components.fileexplorerincludes;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Functional list data model handling file indexing, type filtering, and directory sorting.
 * Optimized with asynchronous background loading to prevent UI lag on directories with thousands of files.
 */
public class FlatFileListModel extends AbstractListModel<File> {

    private File directory;
    private List<File> children = Collections.synchronizedList(new ArrayList<>());
    private FlatFileFilter filter = null;
    private SwingWorker<List<File>, Void> activeWorker = null;

    public FlatFileListModel(File initialDir) {
        setDirectory(initialDir);
    }

    public void setDirectory(File dir) {
        if (dir != null && dir.isDirectory()) {
            this.directory = dir;
            reload();
        }
    }

    public void setFilter(FlatFileFilter filter) {
        this.filter = filter;
        reload();
    }

    /**
     * Asynchronously reloads directory content to keep the UI perfectly responsive.
     */
    private void reload() {
        // Cancel any pending loading task
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
        }

        // Fast clear: notify the UI immediately
        int oldSize = children.size();
        children.clear();
        if (oldSize > 0) {
            fireIntervalRemoved(this, 0, oldSize - 1);
        }

        if (directory == null) {
            return;
        }

        // Capture local variables to prevent race conditions during background tasks
        final File targetDir = this.directory;
        final FlatFileFilter activeFilter = this.filter;

        activeWorker = new SwingWorker<List<File>, Void>() {
            @Override
            protected List<File> doInBackground() {
                // Disk read runs off the EDT
                File[] contents = targetDir.listFiles();
                if (contents == null) {
                    return Collections.emptyList();
                }

                // Filter items
                List<File> filteredList = new ArrayList<>(contents.length);
                for (File f : contents) {
                    if (isCancelled()) {
                        return Collections.emptyList();
                    }
                    if (activeFilter == null || activeFilter.accept(f)) {
                        filteredList.add(f);
                    }
                }

                // Sort items: directories first, then alphabetical
                filteredList.sort((a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });

                return filteredList;
            }

            @Override
            protected void done() {
                if (isCancelled()) return;
                try {
                    List<File> loaded = get();
                    children.addAll(loaded);
                    if (!children.isEmpty()) {
                        fireIntervalAdded(FlatFileListModel.this, 0, children.size() - 1);
                    }
                } catch (Exception e) {
                    children.clear();
                }
            }
        };

        activeWorker.execute();
    }

    @Override
    public int getSize() {
        return children.size();
    }

    @Override
    public File getElementAt(int index) {
        if (index >= 0 && index < children.size()) {
            return children.get(index);
        }
        return null;
    }
}