package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import com.sphere.ui.SPComboBoxUI;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * Primary metadata configuration panel for project setup,
 * experiment selection, and preset environment parameters.
 */
public class SectionOverviewPanel extends JPanel {

    private final JTextField txtName;
    private final JTextField txtPresetVersion;
    private final JComboBox<String> cmbProjectType;
    private final JComboBox<String> cmbExperiment;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public interface ProjectTypeListener {
        void onProjectTypeChanged(String type);
    }

    public SectionOverviewPanel(ProjectManifest manifest, ProjectTypeListener typeListener) {
        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setOpaque(false);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        // Configuration Labels
        JLabel lblName = new JLabel("Project Name:");
        JLabel lblPreset = new JLabel("Preset Version:");
        JLabel lblType = new JLabel("Project Type:");
        JLabel lblExperiment = new JLabel("Experiment:");

        Font labelFont = FontLoader.getGlobalFont(Font.BOLD, 12);
        lblName.setFont(labelFont);
        lblPreset.setFont(labelFont);
        lblType.setFont(labelFont);
        lblExperiment.setFont(labelFont);

        lblName.setForeground(palette.getTextPrimary());
        lblPreset.setForeground(palette.getTextPrimary());
        lblType.setForeground(palette.getTextPrimary());
        lblExperiment.setForeground(palette.getTextPrimary());

        // Input Fields Initialization
        txtName = new JTextField(
                manifest != null && manifest.projectName != null ? manifest.projectName : "",
                16
        );

        txtPresetVersion = new JTextField(
                manifest != null && manifest.presetVersion != null ? manifest.presetVersion : "",
                8
        );

        // Styling Fields dynamically with Palette properties
        txtName.setBackground(palette.getBackgroundTrack());
        txtName.setForeground(palette.getTextPrimary());
        txtName.setCaretColor(palette.getAccent());
        txtName.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        txtName.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        txtPresetVersion.setBackground(palette.getBackgroundTrack());
        txtPresetVersion.setForeground(palette.getTextPrimary());
        txtPresetVersion.setCaretColor(palette.getAccent());
        txtPresetVersion.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        txtPresetVersion.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        txtName.setSelectionColor(palette.getAccent());
        txtName.setSelectedTextColor(palette.getTextPrimary());

        txtPresetVersion.setSelectionColor(palette.getAccent());
        txtPresetVersion.setSelectedTextColor(palette.getTextPrimary());

        // Dropdown Combo Boxes Configuration
        cmbProjectType = new JComboBox<>(new String[]{
                "Analysis",
                "Detector Simulation",
                "Event Generation",
                "Reconstruction",
                "Visualization",
                "Documentation",
                "ML/AI Pipeline",
                "Custom"
        });
        cmbProjectType.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        cmbProjectType.putClientProperty("ComboBox.focusCellHighlightBorder", BorderFactory.createEmptyBorder());
        
        if (manifest != null && manifest.projectType != null) {
            cmbProjectType.setSelectedItem(manifest.projectType);
        }

        cmbExperiment = new JComboBox<>(new String[]{
                "ATLAS",
                "CMS",
                "LHCb",
                "Belle II",
                "Custom"
        });
        cmbExperiment.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        cmbExperiment.putClientProperty("ComboBox.focusCellHighlightBorder", BorderFactory.createEmptyBorder());
        
        if (manifest != null && manifest.experiment != null) {
            cmbExperiment.setSelectedItem(manifest.experiment);
        }

        // Notify parent when project type changes
        cmbProjectType.addActionListener(e -> {
            if (typeListener != null) {
                typeListener.onProjectTypeChanged((String) cmbProjectType.getSelectedItem());
            }
        });

        // Layout Matrix Generation
        int row = 0;

        // Row 1: Name + Preset
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0.0;
        add(lblName, gc);
        gc.gridx = 1; gc.weightx = 1.0;
        add(txtName, gc);

        gc.gridx = 2; gc.weightx = 0.0;
        add(lblPreset, gc);
        gc.gridx = 3; gc.weightx = 0.5;
        add(txtPresetVersion, gc);

        row++;

        // Row 2: Project Type + Experiment
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0.0;
        add(lblType, gc);
        gc.gridx = 1; gc.weightx = 1.0;
        add(cmbProjectType, gc);

        gc.gridx = 2; gc.weightx = 0.0;
        add(lblExperiment, gc);
        gc.gridx = 3; gc.weightx = 0.5;
        add(cmbExperiment, gc);

        // --- Apply custom ComboBox UI AFTER layout initialization ---
        SwingUtilities.invokeLater(() -> {
            cmbProjectType.setUI(new SPComboBoxUI());
            cmbExperiment.setUI(new SPComboBoxUI());
            
            // Defuse native focus paint markers on internal editors if editable down the road
            if (cmbProjectType.getEditor().getEditorComponent() instanceof JComponent) {
                ((JComponent) cmbProjectType.getEditor().getEditorComponent()).putClientProperty("JTextField.focusPainter", null);
            }
            if (cmbExperiment.getEditor().getEditorComponent() instanceof JComponent) {
                ((JComponent) cmbExperiment.getEditor().getEditorComponent()).putClientProperty("JTextField.focusPainter", null);
            }
        });
    }

    /**
     * Writes the current form values back into the manifest.
     */
    public void apply(ProjectManifest manifest) {
        if (manifest == null) return;
        manifest.projectName = txtName.getText().trim();
        manifest.presetVersion = txtPresetVersion.getText().trim();
        manifest.projectType = (String) cmbProjectType.getSelectedItem();
        manifest.experiment = (String) cmbExperiment.getSelectedItem();
    }

    /**
     * Registers validation callbacks for real-time updates.
     */
    public void addRealtimeValidation(Runnable callback) {
        if (callback == null) return;
        txtName.getDocument().addDocumentListener(new SimpleDocumentListener(callback));
        txtPresetVersion.getDocument().addDocumentListener(new SimpleDocumentListener(callback));
        cmbProjectType.addActionListener(e -> callback.run());
        cmbExperiment.addActionListener(e -> callback.run());
    }

    public void setProjectType(String type) {
        if (cmbProjectType != null) {
            cmbProjectType.setSelectedItem(type);
        }
    }

    public String getProjectNameInput() {
        return txtName.getText().trim();
    }

    public String getPresetVersionInput() {
        return txtPresetVersion.getText().trim();
    }

    public String getSelectedProjectType() {
        return (String) cmbProjectType.getSelectedItem();
    }

    public String getSelectedExperiment() {
        return (String) cmbExperiment.getSelectedItem();
    }
}
