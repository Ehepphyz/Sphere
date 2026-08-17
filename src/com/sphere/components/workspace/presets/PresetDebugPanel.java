package com.sphere.components.workspace.presets;

import com.sphere.components.WorkspaceManager;
import com.sphere.components.workspace.ProjectManifest;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.IconManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.Objects;

/**
 * Diagnostic tracking console viewport layout displaying ongoing validation anomalies,
 * experiment configuration profiles, and active preset alignment metrics.
 */
public class PresetDebugPanel extends JPanel {

    private final WorkspaceManager workspaceManager;
    private final ProjectManifest projectManifest;

    private final DefaultTableModel rulesTableModel;
    private final JLabel lblExperimentValue;
    private final JLabel lblVersionValue;
    
    private final JPanel statusAlertBoxPanel;
    private final JLabel lblStatusIcon;
    private final JLabel lblStatusText;
    private final JButton btnRefreshTelemetry;

    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public PresetDebugPanel(WorkspaceManager manager,
                            File projectDirectory,
                            ProjectManifest manifest) {
        this.workspaceManager = Objects.requireNonNull(manager, "Workspace manager tracking instance handle cannot be null.");
        this.projectManifest = Objects.requireNonNull(manifest, "Project manifest metadata configuration cannot be null.");

        // Main layout setup with proportional margins
        setLayout(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(16, 16, 16, 16));
        

        JPanel contentGridWrapperPanel = new JPanel();
        setBackground(palette.getBackgroundMain());
        contentGridWrapperPanel.setLayout(new BoxLayout(contentGridWrapperPanel, BoxLayout.Y_AXIS));

        // --- 1. SECTION: Loaded Validation Rules ---
        JLabel lblTableTitle = new JLabel("Active Loaded Rules Matrix");
        lblTableTitle.setFont(FontLoader.getGlobalFont(Font.PLAIN, 14));
        lblTableTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentGridWrapperPanel.add(lblTableTitle);
        contentGridWrapperPanel.add(Box.createVerticalStrut(6));

        String[] columnHeaders = {"Experiment Identifier", "Valid Version Prefixes"};
        rulesTableModel = new DefaultTableModel(columnHeaders, 0) {
            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return false; // Cell metrics remain read-only
            }
        };
        
        JTable rulesGridTable = new JTable(rulesTableModel);
        rulesGridTable.setFillsViewportHeight(true);
        rulesGridTable.setRowHeight(24);
        rulesGridTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rulesGridTable.getTableHeader().setReorderingAllowed(false);
        
        JScrollPane tableLayoutScrollPane = new JScrollPane(rulesGridTable);
        tableLayoutScrollPane.setPreferredSize(new Dimension(450, 160));
        tableLayoutScrollPane.setMaximumSize(new Dimension(Short.MAX_VALUE, 200));
        tableLayoutScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentGridWrapperPanel.add(tableLayoutScrollPane);
        
        contentGridWrapperPanel.add(Box.createVerticalStrut(20));

        // --- 2. SECTION: Loaded Project Environment Manifest ---
        JLabel lblManifestTitle = new JLabel("Target Application Profile Metadata");
        lblManifestTitle.setFont(FontLoader.getGlobalFont(Font.PLAIN, 14));
        lblManifestTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentGridWrapperPanel.add(lblManifestTitle);
        contentGridWrapperPanel.add(Box.createVerticalStrut(6));
        
        JPanel metaPropertiesDataGridPanel = new JPanel(new GridBagLayout());
        metaPropertiesDataGridPanel.setBorder(BorderFactory.createLineBorder(palette.getBorder(), 1));
        metaPropertiesDataGridPanel.setBackground(palette.getBackgroundSurface());
        metaPropertiesDataGridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        metaPropertiesDataGridPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, 90));
        
        GridBagConstraints layoutGridBagConstraints = new GridBagConstraints();
        layoutGridBagConstraints.insets = new Insets(8, 12, 8, 12);
        layoutGridBagConstraints.fill = GridBagConstraints.HORIZONTAL;

        // Row Entry: Selected Experiment Identifier
        layoutGridBagConstraints.gridx = 0; layoutGridBagConstraints.gridy = 0; layoutGridBagConstraints.weightx = 0;
        metaPropertiesDataGridPanel.add(new JLabel("Experiment Type:"), layoutGridBagConstraints);
        layoutGridBagConstraints.gridx = 1; layoutGridBagConstraints.weightx = 1;
        lblExperimentValue = new JLabel("-");
        lblExperimentValue.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        metaPropertiesDataGridPanel.add(lblExperimentValue, layoutGridBagConstraints);

        // Row Entry: Expected Version Format
        layoutGridBagConstraints.gridx = 0; layoutGridBagConstraints.gridy = 1; layoutGridBagConstraints.weightx = 0;
        metaPropertiesDataGridPanel.add(new JLabel("Profile Target Version:"), layoutGridBagConstraints);
        layoutGridBagConstraints.gridx = 1; layoutGridBagConstraints.weightx = 1;
        lblVersionValue = new JLabel("-");
        lblVersionValue.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        
        // FIX: Removed the incorrect inline variable assignment string (gbc = )
        metaPropertiesDataGridPanel.add(lblVersionValue, layoutGridBagConstraints);

        contentGridWrapperPanel.add(metaPropertiesDataGridPanel);
        add(contentGridWrapperPanel, BorderLayout.CENTER);

        // --- 3. SECTION: Contextual Alert Status Box ---
        JPanel lowerActionContainerPanel = new JPanel(new BorderLayout(0, 12));

        statusAlertBoxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        statusAlertBoxPanel.setBorder(BorderFactory.createLineBorder(palette.getBorder(), 1));
        
        lblStatusIcon = new JLabel();
        lblStatusText = new JLabel();
        lblStatusText.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        
        statusAlertBoxPanel.add(lblStatusIcon);
        statusAlertBoxPanel.add(lblStatusText);
        lowerActionContainerPanel.add(statusAlertBoxPanel, BorderLayout.CENTER);

        // Core Interaction Layer Refresh Triggers
        btnRefreshTelemetry = new JButton("Refresh Telemetry Matrix");
        btnRefreshTelemetry.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        btnRefreshTelemetry.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnRefreshTelemetry.addActionListener(e -> synchronizeActiveWorkspaceValidationDetails());

        JPanel toolbarContainerRowPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        toolbarContainerRowPanel.add(btnRefreshTelemetry);
        lowerActionContainerPanel.add(toolbarContainerRowPanel, BorderLayout.SOUTH);

        add(lowerActionContainerPanel, BorderLayout.SOUTH);

        // Run evaluation cycle
        synchronizeActiveWorkspaceValidationDetails();
    }

    /**
     * Re-queries background preset parameters and forces safe graphical context redrawing.
     */
    public final void synchronizeActiveWorkspaceValidationDetails() {
        rulesTableModel.setRowCount(0);
        Map<String, WorkspaceManager.PresetRule> metricsRulesMap = workspaceManager.getPresetRules();

        // Target Boundary: Catch empty environment rule structures early
        if (metricsRulesMap == null || metricsRulesMap.isEmpty()) {
            updateStatusContainerContext("System Exception: No active pipeline rule limits populated.", 
                    "warning.png", palette.getAmberBackground(), palette.getAmberForeground());
            lblExperimentValue.setText("UNDEFINED");
            lblVersionValue.setText("UNDEFINED");
            return;
        }

        // Hydrate data rows safely
        for (Map.Entry<String, WorkspaceManager.PresetRule> entry : metricsRulesMap.entrySet()) {
            rulesTableModel.addRow(new Object[]{entry.getKey(), entry.getValue().allowedPrefixes.toString()});
        }

        final String activeExperimentTag = projectManifest.experiment != null ? projectManifest.experiment.trim() : "";
        final String activeVersionString = projectManifest.presetVersion != null ? projectManifest.presetVersion.trim() : "";

        lblExperimentValue.setText(activeExperimentTag.isEmpty() ? "NULL" : activeExperimentTag);
        lblVersionValue.setText(activeVersionString.isEmpty() ? "NULL" : activeVersionString);

        WorkspaceManager.PresetRule selectedTargetRule = metricsRulesMap.get(activeExperimentTag);

        if (selectedTargetRule == null) {
            updateStatusContainerContext("Validation Exception: Missing evaluation constraints for target: " + activeExperimentTag, 
                    "warning.png", palette.getAmberBackground(), palette.getAmberForeground());
        } else {
            boolean isSequenceValid = selectedTargetRule.allowedPrefixes.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(prefix -> !activeVersionString.isEmpty() && activeVersionString.startsWith(prefix.trim()));

            if (isSequenceValid) {
                updateStatusContainerContext("Workspace consistency verified: Active version attributes align cleanly.", 
                        "check.png", palette.getAmberBackground(), palette.getAmberForeground());
            } else {
                updateStatusContainerContext("Validation Mismatch: Target build string variations conflict with expected limits.", 
                        "close.png", palette.getAmberBackground(), palette.getAmberForeground());
            }
        }
    }

    private void updateStatusContainerContext(String operationalText, String contextualIconKey, Color colorBg, Color colorFg) {
        lblStatusText.setText(operationalText);
        lblStatusText.setForeground(colorFg);
        
        try {
            Icon dynamicAssetIcon = IconManager.getIcon(contextualIconKey);
            lblStatusIcon.setIcon(dynamicAssetIcon); 
        } catch (Exception skippedIconException) {
            lblStatusIcon.setIcon(null);
        }
        
        statusAlertBoxPanel.setBackground(colorBg);
        statusAlertBoxPanel.setBorder(BorderFactory.createLineBorder(colorFg.brighter(), 1));
        statusAlertBoxPanel.revalidate();
        statusAlertBoxPanel.repaint();
    }
}
