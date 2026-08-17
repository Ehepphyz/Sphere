package com.sphere.components.workspace.presets;

import com.sphere.components.WorkspaceManager;
import com.sphere.components.workspace.ProjectManifest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.Objects;

/**
 * Centered orchestration dialog window aggregating property editors, formatting grids,
 * execution logs, and background automation profiles for an active project workspace.
 */
public class PresetDetailsWindow extends JDialog {

    private final WorkspaceManager workspaceManager;
    private final File projectDirectory;
    private final ProjectManifest projectManifest;

    /**
     * Constructs the preset configuration management workspace center window.
     * Note: Visual display activation is intentionally uncoupled from constructor tracking initialization bounds.
     */
    public PresetDetailsWindow(Frame owner,
                               WorkspaceManager workspaceManager,
                               File projectDirectory,
                               ProjectManifest manifest) {
        super(owner, "Preset Details — " + Objects.requireNonNull(projectDirectory, "Project directory context reference cannot be null.").getName(), false);

        this.workspaceManager = Objects.requireNonNull(workspaceManager, "Workspace manager tracking reference context pointer cannot be null.");
        this.projectDirectory = projectDirectory;
        this.projectManifest = Objects.requireNonNull(manifest, "Target project configuration mapping metadata blueprint cannot be null.");

        initializeDialogWindowArchitecture(owner);
    }

    private void initializeDialogWindowArchitecture(Frame contextOwnerFrame) {
        setSize(950, 650); // Marginally expanded to ensure multi-column logs or tabs match natural scaling layouts
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(contextOwnerFrame);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // 1. Core Center Layout - Managed Dashboard Tab Context Grid
        JTabbedPane multiAssetTabbedContainerPane = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);

        multiAssetTabbedContainerPane.addTab("Preset JSON Editor",
                new PresetJsonEditorPanel(workspaceManager, projectDirectory));

        multiAssetTabbedContainerPane.addTab("System Diagnostics",
                new PresetDebugPanel(workspaceManager, projectDirectory, projectManifest));

        multiAssetTabbedContainerPane.addTab("Schema Migrations",
                new PresetMigrationPanel(workspaceManager, projectDirectory));

        multiAssetTabbedContainerPane.addTab("Execution Telemetry Logs",
                new PresetLogPanel());

        multiAssetTabbedContainerPane.addTab("Syntax Formatter",
                new PresetFormatterPanel(workspaceManager, projectDirectory));

        multiAssetTabbedContainerPane.addTab("Python Automation Updater",
                new PresetPythonUpdaterPanel(workspaceManager, projectDirectory));

        add(multiAssetTabbedContainerPane, BorderLayout.CENTER);

        // 2. Footer Section - Window Form Close Control Layout Action Row
        JPanel bottomActionLayoutToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        bottomActionLayoutToolbar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(225, 228, 235)));

        JButton btnCloseWindowNode = new JButton("Close Environment Manager");
        btnCloseWindowNode.setFont(btnCloseWindowNode.getFont().deriveFont(Font.BOLD, 12f));
        btnCloseWindowNode.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCloseWindowNode.addActionListener(e -> dispose());
        
        bottomActionLayoutToolbar.add(btnCloseWindowNode);
        add(bottomActionLayoutToolbar, BorderLayout.SOUTH);

        // Connect global key intercept patterns to register standard developer window shortcuts
        registerKeyboardShortcutClosingGuards();
    }

    private void registerKeyboardShortcutClosingGuards() {
        KeyStroke escapeKeyStrokeToken = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        String actionMapCancelIdentifierKey = "ESCAPE_WINDOW_DISPOSE_ACTION";

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escapeKeyStrokeToken, actionMapCancelIdentifierKey);
        getRootPane().getActionMap().put(actionMapCancelIdentifierKey, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                dispose();
            }
        });
    }

    /**
     * Synchronizes and safely projects the managed view context onto the screen matrix.
     * Invoke this validation pass immediately following initial system orchestration.
     */
    public void displayWorkspaceWindow() {
        SwingUtilities.invokeLater(() -> {
            if (!isVisible()) {
                setVisible(true);
            }
        });
    }
}
