package com.sphere.components.workspace.presets;

import com.sphere.components.WorkspaceManager;
import com.sphere.components.workspace.MinimalJson;
import com.sphere.utils.AppLogger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Migration verification dashboard layout. Safely audits, handles schema additions,
 * and updates local experimental baseline presets dynamically without blocking the main rendering engine.
 */
public class PresetMigrationPanel extends JPanel {

    private final WorkspaceManager workspaceManager;
    private final Path targetPresetFilePath;
    private final JTextArea terminalLogOutputArea;
    private final JButton btnApplyMigration;

    /**
     * Initializes the schema migration dashboard.
     * @param manager Underlying Workspace tracking coordinator instance handle.
     * @param projectDirectory Path reference mapping to the active working directory space on disk.
     */
    private final PresetLogPanel console;

    public PresetMigrationPanel(WorkspaceManager manager, File projectDirectory,
                                PresetLogPanel console) {
        this.console = Objects.requireNonNull(console, "Console cannot be null.");
        this.workspaceManager = Objects.requireNonNull(manager, "Workspace manager instance pointer cannot be null.");
        Objects.requireNonNull(projectDirectory, "Project layout directory marker reference cannot be null.");
        this.targetPresetFilePath = projectDirectory.toPath().resolve(".presets").normalize();

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // 1. Center Layout - Live Operations Console Viewport
        terminalLogOutputArea = new JTextArea("Ready to initialize presets database structural audit workflow...\n");
        terminalLogOutputArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        if (!"Consolas".equals(terminalLogOutputArea.getFont().getName())) {
            terminalLogOutputArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        }
        terminalLogOutputArea.setEditable(false);
        terminalLogOutputArea.setBackground(new Color(248, 249, 250));
        terminalLogOutputArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 222, 229)),
                new EmptyBorder(6, 8, 6, 8)
        ));

        JScrollPane logScrollPane = new JScrollPane(terminalLogOutputArea);
        add(logScrollPane, BorderLayout.CENTER);

        // 2. Lower Layout - Action Controls Panel Toolbar
        JPanel lowerActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        btnApplyMigration = new JButton("Apply Migration");
        btnApplyMigration.setFont(btnApplyMigration.getFont().deriveFont(Font.BOLD, 12f));
        btnApplyMigration.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        btnApplyMigration.addActionListener(e -> executeAsynchronousMigrationWorkflow());
        lowerActionPanel.add(btnApplyMigration);
        add(lowerActionPanel, BorderLayout.SOUTH);
    }

    private void executeAsynchronousMigrationWorkflow() {
        btnApplyMigration.setEnabled(false);
        terminalLogOutputArea.setText("Beginning active database audit sequence...\n");

        // Disconnect blocking system routines from the interactive Swing EDT loop
        SwingWorker<String, Void> migrationWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                if (!Files.exists(targetPresetFilePath)) {
                    return "Operation aborted: Target configuration file '.presets' does not exist inside workspace layout topology.";
                }

                String rawJsonContent = Files.readString(targetPresetFilePath, StandardCharsets.UTF_8);
                Object parsedJsonRoot = MinimalJson.parseStrict(rawJsonContent);

                if (!(parsedJsonRoot instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("Target configuration parsing failure: JSON structural layout does not map to an object root container.");
                }

                // Safe runtime conversion wrapper
                @SuppressWarnings("unchecked")
                Map<String, Object> workspaceSettingsRootMap = (Map<String, Object>) parsedJsonRoot;
                boolean structuralChangesDetected = false;

                // Sync expected analytical experiments data matrices
                for (Map.Entry<String, List<String>> rule
                        : WorkspaceManager.DEFAULT_PREFIXES.entrySet()) {
                    structuralChangesDetected |= workspaceManager.ensurePresetEntry(
                        workspaceSettingsRootMap, rule.getKey(), rule.getValue());
                }

                if (structuralChangesDetected) {
                    String updatedJsonOutput = MinimalJson.toJson(workspaceSettingsRootMap);
                    Files.writeString(targetPresetFilePath, updatedJsonOutput, StandardCharsets.UTF_8,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    return "Success: Schema updates cleanly mapped, evaluated, and committed downstream onto storage metrics.";
                }

                return "Audit Complete: No structural preset updates are needed for the active project layout configuration rules.";
            }

            @Override
            protected void done() {
                try {
                    String operationExecutionSummary = get();
                    terminalLogOutputArea.append(operationExecutionSummary + "\n");
                    AppLogger.info("Preset migration cycle finalized: " + operationExecutionSummary);
                    console.log("info", operationExecutionSummary);
                } catch (Exception executionThreadFault) {
                    Throwable structuralRootCause = executionThreadFault.getCause() != null 
                            ? executionThreadFault.getCause() : executionThreadFault;
                    
                    String structuralErrorMessage = "Migration structural processing failure: " + structuralRootCause.getMessage();
                    terminalLogOutputArea.append(structuralErrorMessage + "\n");
                    AppLogger.error(structuralErrorMessage);
                    console.log("error", "Left untouched: " + structuralRootCause.getMessage());
                } finally {
                    btnApplyMigration.setEnabled(true);
                }
            }
        };

        migrationWorker.execute();
    }
}
