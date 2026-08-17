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
import java.util.Objects;

/**
 * Direct editing interface designed to validate, format, and serialize
 * environment preset data models without freezing user interactions.
 */
public class PresetJsonEditorPanel extends JPanel {

    private final JTextArea textEditorArea;
    private final WorkspaceManager workspaceManager;
    private final Path targetsPresetFilePath;
    private final JButton btnSaveConfiguration;

    public PresetJsonEditorPanel(WorkspaceManager manager, File projectDirectory) {
        this.workspaceManager = Objects.requireNonNull(manager, "Workspace management tracking entity cannot be null.");
        Objects.requireNonNull(projectDirectory, "Active workspace home location path parameter cannot be null.");
        this.targetsPresetFilePath = projectDirectory.toPath().resolve(".presets").normalize();

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        textEditorArea = new JTextArea();
        textEditorArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        textEditorArea.setTabSize(2);
        
        if (!"Consolas".equals(textEditorArea.getFont().getName())) {
            textEditorArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        }

        JScrollPane editorLayoutScrollPane = new JScrollPane(textEditorArea);
        editorLayoutScrollPane.setBorder(BorderFactory.createLineBorder(new Color(218, 222, 229)));
        add(editorLayoutScrollPane, BorderLayout.CENTER);

        JPanel operationalFooterControlBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        btnSaveConfiguration = new JButton("Save Preset Manifest");
        btnSaveConfiguration.setFont(btnSaveConfiguration.getFont().deriveFont(Font.BOLD, 12f));
        btnSaveConfiguration.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnSaveConfiguration.addActionListener(e -> executeSafeAsynchronousSaveAction());
        operationalFooterControlBar.add(btnSaveConfiguration);
        add(operationalFooterControlBar, BorderLayout.SOUTH);

        executeAsynchronousLoadAction();
    }

    private void executeAsynchronousLoadAction() {
        textEditorArea.setText("// Hydrating metadata preset elements from storage disk...\n");
        textEditorArea.setEnabled(false);
        btnSaveConfiguration.setEnabled(false);

        SwingWorker<String, Void> configContentLoaderWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws IOException {
                if (Files.exists(targetsPresetFilePath)) {
                    return Files.readString(targetsPresetFilePath, StandardCharsets.UTF_8);
                }
                return "{\n  \n}";
            }

            @Override
            protected void done() {
                try {
                    String extractedContentPayload = get();
                    textEditorArea.setText(extractedContentPayload);
                    textEditorArea.setCaretPosition(0);
                } catch (Exception loadingFaultException) {
                    AppLogger.error("Error reading configuration properties: " + loadingFaultException.getMessage());
                    textEditorArea.setText("// Structural fault matching: failed parsing project setting descriptors safely.\n{\n}");
                } finally {
                    textEditorArea.setEnabled(true);
                    btnSaveConfiguration.setEnabled(true);
                }
            }
        };

        configContentLoaderWorker.execute();
    }

    private void executeSafeAsynchronousSaveAction() {
        final String draftContentTextSnapshot = textEditorArea.getText();

        try {
            MinimalJson.parse(draftContentTextSnapshot);
        } catch (Exception structuralSyntaxException) {
            // FIX: Fallback to AppLogger.info
            AppLogger.info("Local preset configuration commit aborted: Malformed JSON document framework detected.");
            JOptionPane.showMessageDialog(this,
                    "Failed parsing properties framework: Invalid or unclosed JSON syntax boundaries.\n" +
                    "Please verify syntax tokens match structural validation properties.",
                    "JSON Compilation Exception", JOptionPane.ERROR_MESSAGE);
            return;
        }

        btnSaveConfiguration.setEnabled(false);
        textEditorArea.setEnabled(false);

        SwingWorker<Void, Void> configurationSaveWorkerPass = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws IOException {
                Path trackingParentFolderLocation = targetsPresetFilePath.getParent();
                if (trackingParentFolderLocation != null && !Files.exists(trackingParentFolderLocation)) {
                    Files.createDirectories(trackingParentFolderLocation);
                }

                Files.writeString(targetsPresetFilePath, draftContentTextSnapshot, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    AppLogger.success("Workspace preset settings properties cleanly synchronized onto disk storage elements.");
                    JOptionPane.showMessageDialog(PresetJsonEditorPanel.this,
                            "Preset parameters saved successfully.",
                            "Synchronization Complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception processingFaultException) {
                    AppLogger.error("Failed executing storage data write: " + processingFaultException.getMessage());
                    JOptionPane.showMessageDialog(PresetJsonEditorPanel.this,
                            "Failed to write modifications downstream:\n" + processingFaultException.getCause().getMessage(),
                            "I/O Exception Intercept", JOptionPane.ERROR_MESSAGE);
                } finally {
                    btnSaveConfiguration.setEnabled(true);
                    textEditorArea.setEnabled(true);
                }
            }
        };

        configurationSaveWorkerPass.execute();
    }
}
