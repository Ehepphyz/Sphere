package com.sphere.components.workspace.presets;

import com.sphere.components.WorkspaceManager;
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
 * Script composition dashboard housing local script macros and background
 * automation tools designed to reconcile high-energy physics preset schemas.
 */
public class PresetPythonUpdaterPanel extends JPanel {

    private final Path targetProjectDirectoryPath;
    private final JTextArea scriptEditorArea;
    private final JButton btnExportScript;

    /**
     * Initializes the automation scripting editor layout.
     * @param workspaceManager   Workspace orchestration context handle.
     * @param projectDirectory Path reference mapping to the active working directory space on disk.
     */
    public PresetPythonUpdaterPanel(WorkspaceManager workspaceManager, File projectDirectory) {
        Objects.requireNonNull(projectDirectory, "Target workflow project folder reference cannot be null.");
        this.targetProjectDirectoryPath = projectDirectory.toPath().normalize();

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // 1. Center Layout - Configuration Scripting Code Workspace
        scriptEditorArea = new JTextArea();
        scriptEditorArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        scriptEditorArea.setTabSize(4);
        scriptEditorArea.setLineWrap(false);
        
        // Provide standard system fallbacks if standard programming fonts are missing on the host environment
        if (!"Consolas".equals(scriptEditorArea.getFont().getName())) {
            scriptEditorArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        }

        JScrollPane editorScrollPane = new JScrollPane(scriptEditorArea);
        editorScrollPane.setBorder(BorderFactory.createLineBorder(new Color(215, 220, 230)));
        add(editorScrollPane, BorderLayout.CENTER);

        // 2. Lower Layout - Action Controls Panel Toolbar
        JPanel lowerActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        btnExportScript = new JButton("Export Automation Script");
        btnExportScript.setFont(btnExportScript.getFont().deriveFont(Font.BOLD, 12f));
        btnExportScript.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        btnExportScript.addActionListener(e -> executeAsynchronousScriptExportPass());
        lowerActionPanel.add(btnExportScript);
        add(lowerActionPanel, BorderLayout.SOUTH);

        // Load baseline scripting definitions
        populatePythonBaselineTemplate();
    }

    private void populatePythonBaselineTemplate() {
        scriptEditorArea.setText("""
                import json
                import requests
                
                def update_presets(preset_file_path):
                    \"\"\"
                    Automatically synchronizes localized metadata definition prefixes
                    against distributed collaboration endpoints.
                    \"\"\"
                    try:
                        with open(preset_file_path, "r", encoding="utf-8") as file_stream:
                            preset_matrix = json.load(file_stream)
                
                        # Operational defaults: simulate live telemetry retrieval hooks
                        latest_atlas_baseline = "24.1.0"
                        latest_cms_baseline = "13.2.5"
                
                        preset_matrix["ATLAS"]["allowedPrefixes"] = ["24"]
                        preset_matrix["CMS"]["allowedPrefixes"] = ["13", "23"]
                
                        with open(preset_file_path, "w", encoding="utf-8") as file_stream:
                            json.dump(preset_matrix, file_stream, indent=4)
                        
                        print(f"[SUCCESS] Normalized preset schemas synchronized under target path: {preset_file_path}")
                        
                    except Exception as failure_exception:
                        print(f"[ERROR] Routine initialization fault matched: {str(failure_exception)}")
                
                if __name__ == "__main__":
                    update_presets(".presets")
                """);
        
        // Return cursor tracking precisely back to top-left boundary elements
        scriptEditorArea.setCaretPosition(0);
    }

    private void executeAsynchronousScriptExportPass() {
        final String scriptContentSnapshotText = scriptEditorArea.getText();
        final Path targetScriptOutputFilePath = targetProjectDirectoryPath.resolve("preset_auto_update.py").normalize();

        btnExportScript.setEnabled(false);

        // Disconnect task writing executions from the interactive Swing EDT loop
        SwingWorker<Void, Void> fileExportWorkerPass = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws IOException {
                if (!Files.exists(targetProjectDirectoryPath)) {
                    Files.createDirectories(targetProjectDirectoryPath);
                }
                Files.writeString(targetScriptOutputFilePath, scriptContentSnapshotText, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // Check for exceptions during thread execution
                    
                    AppLogger.success("Python preset module exported successfully: " + targetScriptOutputFilePath.toAbsolutePath());
                    JOptionPane.showMessageDialog(PresetPythonUpdaterPanel.this,
                            "Automation utility python script layout cleanly provisioned:\n" + targetScriptOutputFilePath.toAbsolutePath(),
                            "Export Phase Finalized", JOptionPane.INFORMATION_MESSAGE);

                } catch (Exception processFaultException) {
                    AppLogger.error("Failed compiling template automation script: " + processFaultException.getMessage());
                    JOptionPane.showMessageDialog(PresetPythonUpdaterPanel.this,
                            "Failed exporting script components downstream:\n" + processFaultException.getCause().getMessage(),
                            "I/O Generation Failure", JOptionPane.ERROR_MESSAGE);
                } finally {
                    btnExportScript.setEnabled(true);
                }
            }
        };

        fileExportWorkerPass.execute();
    }
}
