package com.sphere.utils.settingsmanager;

import javax.swing.tree.DefaultTreeModel;

/**
 * Filter-aware tree structure model that dynamically hides or exposes configuration leaves
 * matching search constraints.
 */
public final class SettingsTreeModel extends DefaultTreeModel {

    private final SettingsFile srcFile;

    public SettingsTreeModel(SettingsFile file) {
        super(buildFilteredRoot(file, null));
        this.srcFile = file;
    }

    /**
     * Triggers a safe ui-thread reload pass if an external disk update occurs
     */
    public void reloadFromDiskModel() {
        setRoot(buildFilteredRoot(srcFile, null));
    }

    /**
     * Regenerates the model node structure, stripping out non-matching configurations if filtering is on.
     */
    public void applyFilter(String filterQuery) {
        setRoot(buildFilteredRoot(srcFile, filterQuery));
    }

    private static SettingsTreeNode buildFilteredRoot(SettingsFile file, String query) {
        SettingsTreeNode root = new SettingsTreeNode("settings.conf", SettingsNodeType.ROOT);
        String criterion = (query != null) ? query.toLowerCase().trim() : "";

        file.getCategories().forEach((cat, map) -> {
            SettingsTreeNode catNode = new SettingsTreeNode(cat, SettingsNodeType.CATEGORY);
            
            map.forEach((k, v) -> {
                // If filter query is set, bypass node generation for mismatched terms
                if (!criterion.isEmpty() && !k.toLowerCase().contains(criterion) && !v.toLowerCase().contains(criterion)) {
                    return; 
                }
                catNode.add(new SettingsTreeNode(new KeyValue(cat, k, v), SettingsNodeType.KEY));
            });

            // Only add categories that have valid leaves matching our criteria
            if (criterion.isEmpty() || catNode.getChildCount() > 0 || cat.toLowerCase().contains(criterion)) {
                root.add(catNode);
            }
        });

        return root;
    }
}
