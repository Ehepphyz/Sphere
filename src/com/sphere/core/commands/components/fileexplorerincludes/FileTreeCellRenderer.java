package com.sphere.components.fileexplorerincludes;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;

import com.sphere.utils.IconManager;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;
import com.sphere.components.FileExplorer;

public class FileTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final Color HIGHLIGHT_COLOR = new Color(0, 120, 215, 60);

    public FileTreeCellRenderer() {
        
        setOpaque(false);
    }

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean selected,
            boolean expanded, boolean leaf, int row, boolean hasFocus) {

        // 1. Default initialization
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        // 2. Theme color management (Selection detection via Foreground for the paint method)
        Color fgSel  = Color.WHITE;
        Color fgNorm = new Color(220, 222, 225); // brightText
        setForeground(selected ? fgSel : fgNorm);
        
        // Disable default opacity to allow our custom paint() method to draw the rounded background
        setOpaque(false);

        // 3. Icons and text management
        Object obj = ((DefaultMutableTreeNode) value).getUserObject();

        if (obj instanceof File file) {
            // --- DRIVE ROOTS ---
            if (file.getParentFile() == null) {
                setIcon(IconManager.getIcon("drive.png"));
                setText(file.getAbsolutePath());
            }
            // --- FOLDERS ---
            else if (file.isDirectory()) {
                if (expanded) {
                    setIcon(IconManager.getIcon("folder_open.png"));
                } else {
                    setIcon(IconManager.getIcon("folder_closed.png"));
                }
                setText(file.getName());
            }
            // --- FILES ---
            else {
                setIcon(IconManager.getIconForFile(file.getName()));
                setText(file.getName());
            }
        }

        // 4. Drag & Drop hover management (FIXED)
        // Clear the background first so previous cell states don't bleed into this one
        setBackground(null); 

        if (tree instanceof FileExplorer explorer) {
            if (explorer.isDragging && explorer.hoveredPath != null) {
                TreePath thisPath = new TreePath(((DefaultMutableTreeNode) value).getPath());
                if (thisPath.equals(explorer.hoveredPath)) {
                    setBackground(HIGHLIGHT_COLOR); 
                }
            }
        }

        return this;
    }

    @Override
    public void paint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Detect selection via foreground color (set in getTreeCellRendererComponent)
        boolean selected = getForeground().equals(Color.WHITE);
        
        // Detect Drag & Drop hover via background color (FIXED: Safe null check)
        boolean isHoveredByDrag = getBackground() != null && getBackground().equals(HIGHLIGHT_COLOR);

        // Render the background
        if (selected) {
            g2.setColor(new Color(60, 90, 120)); // activeBlue
            g2.fillRoundRect(0, 0, w, h, 8, 8);
        } else if (isHoveredByDrag) {
            g2.setColor(HIGHLIGHT_COLOR); // Drag hover color
            g2.fillRoundRect(0, 0, w, h, 8, 8);
        }

        g2.dispose();
        
        // Call super.paint() afterward to draw text and icons on top of the rounded background
        super.paint(g);
    }

    // Used by FileExplorer for ghost preview
    public Icon getIconForFile(File file) {
        if (file == null) {
            return IconManager.getIcon("default.png");
        }
        return IconManager.getIconForFile(file.getName());
    }
}

