package com.sphere.components.fileexplorerincludes;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Breadcrumb-style file system path navigation header panel.
 */
public class FlatPathBar extends JPanel {

    private final FlatFileChooserPanel chooser;
    private File currentDirectory;

    public FlatPathBar(FlatFileChooserPanel chooser) {
        this.chooser = chooser;
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 4));
        updateUI();
    }

    public void updatePath(File dir) {
        this.currentDirectory = dir;
        removeAll();

        File current = dir;
        java.util.List<File> parts = new java.util.ArrayList<>();

        while (current != null) {
            parts.add(0, current);
            current = current.getParentFile();
        }

        for (File f : parts) {
            String name = f.getName().isEmpty() ? "/" : f.getName();
            JButton breadcrumbButton = new JButton(name);
            breadcrumbButton.setFocusPainted(false);
            breadcrumbButton.setFont(com.sphere.fonts.FontLoader.getGlobalFont(Font.PLAIN, 11));
            breadcrumbButton.addActionListener(e -> chooser.setDirectory(f));
            add(breadcrumbButton);
        }

        // Keep the design integrity preserved when path strings flip
        ThemePalette palette = ThemeManager.getCurrentPalette();
        if (palette != null) {
            setBackground(palette.getBackgroundSurface());
        }

        revalidate();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        ThemePalette palette = ThemeManager.getCurrentPalette();
        if (palette != null) {
            setBackground(palette.getBackgroundSurface());
            if (currentDirectory != null) {
                updatePath(currentDirectory);
            }
        }
    }
}
