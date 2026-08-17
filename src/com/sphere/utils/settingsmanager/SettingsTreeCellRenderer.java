package com.sphere.utils.settingsmanager;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.utils.IconManager;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;

/**
 * Custom tree cell renderer that manages theme coloring, icon presentation, 
 * substring search match highlighting, and active match tracking indicators.
 * Encapsulated instance scope replaces prior static global state references.
 */
public class SettingsTreeCellRenderer extends DefaultTreeCellRenderer {

    private SearchContext searchContext = null;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    /**
     * Updates the internal mutable encapsulation state wrapper for queries.
     * @param searchContext The new localized context parameter payload to apply.
     */
    public void setSearchContext(SearchContext searchContext) {
        this.searchContext = searchContext;
    }

    /**
     * Retrieves the current active search data state container tracking settings.
     * @return The active encapsulated SearchContext.
     */
    public SearchContext getSearchContext() {
        return this.searchContext;
    }

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean selected, boolean expanded,
            boolean leaf, int row, boolean hasFocus) {

        // 1. Call super FIRST so Swing sets up its standard parameters
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        // 2. Apply localized background/foreground styling *strictly* on this instance 
        // without invoking global look-and-feel configuration properties.
        if (palette != null) {
            if (selected) {
                setBackground(palette.getColFillBlue());
                setForeground(palette.getTextWhite());
            } else {
                setBackground(palette.getBackgroundSurface());
                setForeground(palette.getTextPrimary());
            }
        }

        if (selected) {
            setOpaque(true);
        } else {
            setOpaque(false);
        }

        if (value instanceof SettingsTreeNode node) {
            switch (node.getType()) {
                case ROOT     -> setIcon(IconManager.getIcon("settings.png"));
                case CATEGORY -> setIcon(IconManager.getIcon("redfolder.png"));
                case KEY      -> setIcon(IconManager.getIcon("settingskey.png"));
            }

            String text = node.toString();

            // Safe encapsulation check targeting the localized SearchContext instance
            if (searchContext != null && searchContext.query() != null && !searchContext.query().isBlank()) {
                String lower = text.toLowerCase();
                String q = searchContext.query().toLowerCase();
                int idx = lower.indexOf(q);

                if (idx >= 0 && palette != null) {
                    String pre  = text.substring(0, idx);
                    String mid  = text.substring(idx, idx + q.length());
                    String post = text.substring(idx + q.length());

                    Color bg = palette.getAmberBackground();
                    Color fg = palette.getAmberForeground();

                    setText("<html>" +
                            escape(pre) +
                            "<span style='background-color: rgb(" +
                            bg.getRed() + "," + bg.getGreen() + "," + bg.getBlue() +
                            "); color: rgb(" +
                            fg.getRed() + "," + fg.getGreen() + "," + fg.getBlue() +
                            ");'>" +
                            escape(mid) +
                            "</span>" +
                            escape(post) +
                            "</html>");
                }
            }

            // Verify active matching highlights against instance context paths
            if (searchContext != null && searchContext.activeMatch() != null && palette != null) {
                TreePath thisPath = tree.getPathForRow(row);
                if (thisPath != null && thisPath.equals(searchContext.activeMatch())) {
                    setBorder(BorderFactory.createLineBorder(palette.getAmberActiveBorder(), 2));
                } else {
                    setBorder(null);
                }
            } else {
                setBorder(null);
            }
        }

        return this;
    }

    private String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
