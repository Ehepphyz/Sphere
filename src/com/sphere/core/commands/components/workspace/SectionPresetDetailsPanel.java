package com.sphere.components.workspace;

import com.sphere.components.WorkspaceManager;
import com.sphere.components.workspace.presets.PresetDetailsWindow;
import com.sphere.utils.IconManager;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;

/**
 * Workspace utility panel displaying preset runtime summaries and offering 
 * contextual action controls to initialize deeper preset configuration analysis inspectors.
 */
public class SectionPresetDetailsPanel extends JPanel {

    private ProjectManifest manifest;
    private final WorkspaceManager workspaceManager;
    private final File projectDirectory;
    
    private JButton btnInspect;
    private JLabel infoLabel;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public SectionPresetDetailsPanel(ProjectManifest manifest,
                                     WorkspaceManager workspaceManager,
                                     File projectDirectory) {

        this.manifest = manifest;
        this.workspaceManager = workspaceManager;
        this.projectDirectory = projectDirectory;

        setLayout(new BorderLayout(12, 0));
        setBorder(new EmptyBorder(6, 8, 6, 8));
        setOpaque(false);

        initComponents();
    }

    private void initComponents() {
        // Flat informative descriptive label linked to dynamic theme typography
        infoLabel = new JLabel("Inspect associated dataset models, parameters, and environmental presets.");
        infoLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        infoLabel.setForeground(palette.getTextSecondary());

        // Polished action button synced with palette definitions
        btnInspect = new JButton("View Preset Details", IconManager.getIcon("modify.png"));
        btnInspect.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        btnInspect.setForeground(palette.getTextPrimary());
        btnInspect.setBackground(palette.getButtonHover());
        btnInspect.setFocusPainted(false);
        btnInspect.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnInspect.addActionListener(e -> showPopup());

        add(infoLabel, BorderLayout.CENTER);
        add(btnInspect, BorderLayout.EAST);
    }

    /**
     * Refreshes the active data context manifest payload instance.
     * @param manifest the revised project configuration manifest.
     */
    public void setManifest(ProjectManifest manifest) {
        this.manifest = manifest;
    }

    /**
     * Resolves layout contexts and displays the deep configuration inspector window system safely.
     */
    public void showPopup() {
        if (manifest == null || workspaceManager == null || projectDirectory == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "The target project configuration metadata or workspace settings are currently unavailable.",
                    "Preset Information Error",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Intercept parent frame ancestry references accurately
        Window ancestorWindow = SwingUtilities.getWindowAncestor(this);
        Frame parentFrame = (ancestorWindow instanceof Frame frame) ? frame : null;

        PresetDetailsWindow detailsWindow = new PresetDetailsWindow(
                parentFrame,
                workspaceManager,
                projectDirectory,
                manifest
        );

        // Position window cleanly centered relative to the requesting parent panel
        detailsWindow.setLocationRelativeTo(this);
        detailsWindow.setVisible(true);
    }
}
