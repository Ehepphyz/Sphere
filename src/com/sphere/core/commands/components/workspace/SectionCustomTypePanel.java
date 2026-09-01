package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.IconManager;

/**
 * Modern visual layout group for user-defined custom types,
 * workflow templates, and structural metadata rules.
 */
public class SectionCustomTypePanel extends JPanel {

    private final JTextField txtCustomName = new JTextField();
    private final JTextField txtCustomDescription = new JTextField();
    private final JTextField txtCustomWorkflowTemplate = new JTextField();
    private final JTextField txtCustomStructure = new JTextField();
    private final JTextField txtCustomIcon = new JTextField();

    // Map to keep track of hints associated with each text field for dynamic styling
    private final Map<JTextField, JLabel> fieldHintMap = new LinkedHashMap<>();
    private final Map<JTextField, JLabel> fieldLabelMap = new LinkedHashMap<>();

    public SectionCustomTypePanel(ProjectManifest manifest) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(16, 16, 16, 16));

        initComponents(manifest);
        buildLayout();
        updateTheme();
    }

    private void initComponents(ProjectManifest manifest) {
        Map<JTextField, FieldConfig> configurations = new LinkedHashMap<>();
        configurations.put(txtCustomName, new FieldConfig(
            manifest != null ? manifest.customTypeName : null, 
            "Custom Type Name", 
            "e.g., QuantumAnnealerSuite"
        ));
        configurations.put(txtCustomDescription, new FieldConfig(
            manifest != null ? manifest.customTypeDescription : null, 
            "Description", 
            "Describe the purpose of this custom profile..."
        ));
        configurations.put(txtCustomWorkflowTemplate, new FieldConfig(
            manifest != null ? manifest.customWorkflowTemplate : null, 
            "Workflow Template Path", 
            "e.g., /templates/quantum_baseline.json"
        ));
        configurations.put(txtCustomStructure, new FieldConfig(
            manifest != null ? manifest.customDefaultStructure : null, 
            "Suggested Default Structure", 
            "e.g., src/, data/raw/, docs/abstract.md"
        ));
        configurations.put(txtCustomIcon, new FieldConfig(
            manifest != null ? manifest.customIcon : null, 
            "Icon Identifier", 
            "e.g., custom_gear.png"
        ));

        configurations.forEach((field, config) -> {
            if (config.value != null) {
                field.setText(config.value);
            }
            
            // Create the top title label
            JLabel label = new JLabel(config.title);
            fieldLabelMap.put(field, label);

            // Create the bottom hint label
            JLabel hintLabel = new JLabel(config.hint);
            fieldHintMap.put(field, hintLabel);
        });
    }

    private void buildLayout() {
        // Build the vertical layout stack for each field row block
        fieldLabelMap.forEach((field, label) -> {
            JLabel hint = fieldHintMap.get(field);

            // Row Container Block
            JPanel rowBlock = new JPanel();
            rowBlock.setOpaque(false);
            rowBlock.setLayout(new BoxLayout(rowBlock, BoxLayout.Y_AXIS));
            rowBlock.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Align all components within the block to the left
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Add elements with tight vertical spaces
            rowBlock.add(label);
            rowBlock.add(Box.createVerticalStrut(4));
            rowBlock.add(field);
            rowBlock.add(Box.createVerticalStrut(3));
            rowBlock.add(hint);

            // Add block to main panel with spacing between sections
            add(rowBlock);
            add(Box.createVerticalStrut(16));
        });
    }

    /**
     * Dynamically loads the current theme and formats titles, input borders, 
     * and the small italicized description hints under fields.
     */
    public void updateTheme() {
        ThemePalette palette = ThemeManager.getCurrentPalette();
        if (palette == null) return;

        Font titleFont = FontLoader.getGlobalFont(Font.BOLD, 12);
        Font hintFont = FontLoader.getGlobalFont(Font.ITALIC, 10);

        fieldLabelMap.forEach((field, label) -> {
            label.setFont(titleFont);
            label.setForeground(palette.getTextWhite());

            // Input field styling adjustments
            field.setOpaque(true);
            field.setBackground(palette.getBackgroundSurface());
            field.setForeground(palette.getTextWhite());
            field.setCaretColor(palette.getTextWhite());
            field.setSelectionColor(palette.getAccent());
            field.setSelectedTextColor(palette.getTextPrimary());
            field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6) // Padding inside the field
            ));
            
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            field.setPreferredSize(new Dimension(field.getPreferredSize().width, 28));
        });

        fieldHintMap.forEach((field, hintLabel) -> {
            hintLabel.setFont(hintFont);
            // Apply a softer white/gray tone if available, or fall back to standard text color
            hintLabel.setForeground(palette.getTextWhite().darker()); 
        });

        revalidate();
        repaint();
    }

    public void apply(ProjectManifest manifest) {
        if (manifest == null) return;

        manifest.customTypeName = getCustomNameInput();
        manifest.customTypeDescription = getCustomDescriptionInput();
        manifest.customWorkflowTemplate = getCustomWorkflowTemplateInput();
        manifest.customIcon = getCustomIconInput();
        manifest.customDefaultStructure = getCustomStructureInput();
    }

    public void addRealtimeValidation(Runnable callback) {
        if (callback == null) return;

        SimpleDocumentListener listener = new SimpleDocumentListener(callback);
        txtCustomName.getDocument().addDocumentListener(listener);
        txtCustomDescription.getDocument().addDocumentListener(listener);
        txtCustomWorkflowTemplate.getDocument().addDocumentListener(listener);
        txtCustomStructure.getDocument().addDocumentListener(listener);
        txtCustomIcon.getDocument().addDocumentListener(listener);
    }

    public String getCustomNameInput() { return txtCustomName.getText().trim(); }
    public String getCustomDescriptionInput() { return txtCustomDescription.getText().trim(); }
    public String getCustomWorkflowTemplateInput() { return txtCustomWorkflowTemplate.getText().trim(); }
    public String getCustomStructureInput() { return txtCustomStructure.getText().trim(); }
    public String getCustomIconInput() { return txtCustomIcon.getText().trim(); }

    @Deprecated(since = "2026", forRemoval = false)
    public JTextField getTxtCustomIcon() { return txtCustomIcon; }

    @Deprecated(since = "2026", forRemoval = false)
    public JTextField getTxtCustomStructure() { return txtCustomStructure; }

    /**
     * Clean structure configuration wrapper.
     */
    private static class FieldConfig {
        final String value;
        final String title;
        final String hint;

        FieldConfig(String value, String title, String hint) {
            this.value = value;
            this.title = title;
            this.hint = hint;
        }
    }
}
