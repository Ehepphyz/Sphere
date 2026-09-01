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
import java.util.Map;
import java.util.Objects;

/**
 * Visual utility tab designed to safely re-parse, index, pretty-print,
 * and beautify preset manifest files on background worker threads.
 */
public class PresetFormatterPanel extends JPanel {

    private final WorkspaceManager workspaceManager;
    private final Path targetPresetFilePath;
    private final JTextArea previewTextArea;
    private final JButton btnFormatAndSave;

    /**
     * Initializes the preset beautification and formatting panel layout.
     * @param manager          Underlying core Workspace orchestration coordinator instance handle.
     * @param projectDirectory Path reference mapping to the active working directory space on disk.
     */
    public PresetFormatterPanel(WorkspaceManager manager, File projectDirectory) {
        this.workspaceManager = Objects.requireNonNull(manager, "Workspace manager coordinator handle cannot be null.");
        Objects.requireNonNull(projectDirectory, "Active workspace project layout folder reference cannot be null.");
        this.targetPresetFilePath = projectDirectory.toPath().resolve(".presets").normalize();

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // 1. Center Layout - Configuration Preview Console Window
        previewTextArea = new JTextArea("Initializing syntax formatter view port...\n");
        previewTextArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        
        // Fall back to general programming system font indicators if Consolas is missing
        if (!"Consolas".equals(previewTextArea.getFont().getName())) {
            previewTextArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        }
        
        previewTextArea.setEditable(false);
        previewTextArea.setBackground(new Color(245, 246, 248));
        previewTextArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230)),
                new EmptyBorder(6, 8, 6, 8)
        ));

        JScrollPane previewScrollPane = new JScrollPane(previewTextArea);
        add(previewScrollPane, BorderLayout.CENTER);

        // 2. Footer Section - Tool Controls Operations Bar
        JPanel footerControlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        btnFormatAndSave = new JButton("Format & Save Document");
        btnFormatAndSave.setFont(btnFormatAndSave.getFont().deriveFont(Font.BOLD, 12f));
        btnFormatAndSave.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnFormatAndSave.addActionListener(e -> executeAsynchronousFormattingWorkflow());
        footerControlsPanel.add(btnFormatAndSave);
        add(footerControlsPanel, BorderLayout.SOUTH);

        // Hydrate configuration state visual assets asynchronously
        executeAsynchronousLoadWorkflow();
    }

    private void executeAsynchronousLoadWorkflow() {
        btnFormatAndSave.setEnabled(false);

        SwingWorker<String, Void> contentLoaderWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws IOException {
                if (Files.exists(targetPresetFilePath)) {
                    return Files.readString(targetPresetFilePath, StandardCharsets.UTF_8);
                }
                return "// Notice: The selected workspace target does not track a local '.presets' metadata configuration file.";
            }

            @Override
            protected void done() {
                try {
                    String documentTextPayload = get();
                    previewTextArea.setText(documentTextPayload);
                    previewTextArea.setCaretPosition(0);
                } catch (Exception loadFaultException) {
                    AppLogger.error("Error reading configuration properties for preview window: " + loadFaultException.getMessage());
                    previewTextArea.setText("// Localized File System Exception: Failed to read local template files accurately.");
                } finally {
                    btnFormatAndSave.setEnabled(true);
                }
            }
        };

        contentLoaderWorker.execute();
    }

    private void executeAsynchronousFormattingWorkflow() {
        btnFormatAndSave.setEnabled(false);
        previewTextArea.setText("// Re-parsing syntax structures and executing linear beautification loops...\n");

        // Disconnect structural modifications from the interactive Swing EDT loop
        SwingWorker<String, Void> formattingExecutionWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                if (!Files.exists(targetPresetFilePath)) {
                    return "// Operational Fault: Pre-conditions matching configuration targeting metrics failed. Missing '.presets' resource.";
                }

                String unformattedRawJson = Files.readString(targetPresetFilePath, StandardCharsets.UTF_8);
                Object parsedJsonRootElement = MinimalJson.parse(unformattedRawJson);

                if (!(parsedJsonRootElement instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("Target configuration parsing failure: JSON structural layout does not map to an object root container.");
                }

                // Defensively safe cast check configuration mapping pass
                @SuppressWarnings("unchecked")
                Map<String, Object> validMetadataPropertiesRootMap = (Map<String, Object>) parsedJsonRootElement;

                // Pretty-print and rebuild spacing parameters through our core formatting engine
                String unifiedBeautifiedJsonOutput = MinimalJson.toJson(validMetadataPropertiesRootMap);
                
                Files.writeString(targetPresetFilePath, unifiedBeautifiedJsonOutput, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                return unifiedBeautifiedJsonOutput;
            }

            @Override
            protected void done() {
                try {
                    String beautifullyFormattedJson = get();
                    previewTextArea.setText(beautifullyFormattedJson);
                    previewTextArea.setCaretPosition(0);
                    AppLogger.success("Workspace environment configuration properties file successfully beatified and synchronized.");
                } catch (Exception computationFaultException) {
                    Throwable realRootCause = computationFaultException.getCause() != null 
                            ? computationFaultException.getCause() : computationFaultException;
                    
                    String structuralErrorMessage = "Formatting processing failure: " + realRootCause.getMessage();
                    previewTextArea.setText("// Configuration Compilation Exception:\n" + structuralErrorMessage);
                    AppLogger.error(structuralErrorMessage);
                } finally {
                    btnFormatAndSave.setEnabled(true);
                }
            }
        };

        formattingExecutionWorker.execute();
    }
}
