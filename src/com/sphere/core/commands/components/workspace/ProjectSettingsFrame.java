package com.sphere.components.workspace;

import com.sphere.components.WorkspaceManager;
import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Primary workspace control window housing advanced project explorer tree navigation nodes, 
 * metadata dashboards, and specialized scientific framework plugin integrations.
 */
public class ProjectSettingsFrame extends JDialog implements WorkspaceListener {

    private final File projectDirectory;
    private final WorkspaceManager workspaceManager;
    private final Path manifestFilePath;

    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    
    private WorkspaceFileExplorerPanel fileExplorerPanel;
    private ProjectDashboardPanel dashboardPanel;
    private final Timer fileSystemPollerTimer;

    private final AtomicBoolean isCleanedUp = new AtomicBoolean(false);
    private final ProjectManifest manifestContext;

    public ProjectSettingsFrame(Frame owner, File projectDirectory, WorkspaceManager manager) {
        
        super(owner, "Project Settings — " + projectDirectory.getName(), false);
        
        this.projectDirectory = projectDirectory;
        this.workspaceManager = manager;
        this.manifestFilePath = projectDirectory.toPath().resolve(".projectsettings");

        // Initialize and hydrate data metrics context safely using a local reference
        ProjectManifest loadedManifest = ProjectManifestIO.load(manifestFilePath);

        if (loadedManifest == null) {
            loadedManifest = new ProjectManifest();
        }

        // Ensure the manifest is in a valid state
        loadedManifest.ensureDefaults();
        validateManifestHealth(loadedManifest);

        if (loadedManifest.createdAt == null || loadedManifest.createdAt.isBlank()) {
            loadedManifest.createdAt = java.time.Instant.now().toString();
        }

        // Final assignment to the class field
        this.manifestContext = loadedManifest;

        setSize(1050, 680);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        initComponents();

        if (workspaceManager != null) {
            workspaceManager.addWorkspaceListener(this);
        }

        // Initialize the file-tree monitoring thread loop
        fileSystemPollerTimer = new Timer(1500, e -> refreshExplorerTreeIfShowing());
        fileSystemPollerTimer.start();

        addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                refreshExplorerTreeIfShowing();
            }
            @Override 
            public void windowLostFocus(WindowEvent e) {}
        });

        addWindowListener(new WindowAdapter() {
            @Override 
            public void windowClosing(WindowEvent e) { 
                performLifecycleCleanup(); 
            }
        });

        // Trigger an initial asynchronous background structural file system pass
        if (fileExplorerPanel != null) {
            fileExplorerPanel.triggerStructuralAnalysisSync();
        }
    }

    private void initComponents() {
        fileExplorerPanel = new WorkspaceFileExplorerPanel();
        
        // Harmonisation de la bordure du projet avec la couleur de bordure de la palette
        fileExplorerPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(palette.getBorder()), "Project Explorer"));
        fileExplorerPanel.setProjectTarget(projectDirectory);
        fileExplorerPanel.setMinimumSize(new Dimension(200, 0));
        fileExplorerPanel.setPreferredSize(new Dimension(240, 0));

        JTabbedPane workspaceTabs = new JTabbedPane();
        workspaceTabs.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        // Construct integrated workspace metadata configuration cockpit panel
        dashboardPanel = new ProjectDashboardPanel(
                manifestContext,
                workspaceManager,
                new ProjectDashboardPanel.DashboardListener() {
                    @Override
                    public void onApplyChanges() {
                        persistActiveWorkspaceSettings();
                        dispose(); // Close window safely after an intentional save event
                    }

                    @Override
                    public void onCancel() {
                        dispose(); // Exit without altering local manifest profiles
                    }
                },
                projectDirectory
        );

        workspaceTabs.addTab("Dashboard", dashboardPanel);
        workspaceTabs.addTab("Madgraph Suite", createPlaceholderTab("Madgraph Simulation & Kinematic Integration Controls"));
        workspaceTabs.addTab("ROOT Framework", createPlaceholderTab("ROOT Subprocess Environment Bindings"));

        JSplitPane mainSplitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                fileExplorerPanel,
                workspaceTabs
        );
        mainSplitPane.setDividerLocation(240);
        mainSplitPane.setDividerSize(6);
        mainSplitPane.setBorder(null);

        add(mainSplitPane, BorderLayout.CENTER);
    }

    /**
     * Commits active panel inputs, serializes updates to the manifest disk file, 
     * and re-arms runtime rule managers.
     */
    private void persistActiveWorkspaceSettings() {
        try {
            dashboardPanel.apply(manifestContext);
            ProjectManifestIO.save(manifestContext, manifestFilePath);

            if (workspaceManager != null) {
                workspaceManager.loadOrCreatePresetFile(projectDirectory);
                workspaceManager.modifyPresetFileIfNeeded(projectDirectory);
                workspaceManager.loadPresetRules(projectDirectory);
            }

            AppLogger.success("Workspace parameters synchronized with .projectsettings successfully.");
        } catch (Exception ex) {
            AppLogger.error("Failed to write revised project configuration parameters to disk: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, 
                    "Could not persist settings updates onto disk. Check write permissions.", 
                    "I/O Storage Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshExplorerTreeIfShowing() {
        if (this.isShowing() && fileExplorerPanel != null) {
            fileExplorerPanel.refreshVisualTree();
        }
    }

    @Override
    public void onProjectStructureUpdated(File project) {
        if (project != null && project.equals(projectDirectory)) {
            SwingUtilities.invokeLater(() -> {
                if (fileExplorerPanel != null) {
                    fileExplorerPanel.triggerStructuralAnalysisSync();
                }
            });
        }
    }

    @Override 
    public void onWorkspaceChanged(List<File> updatedProjects) {}
    
    @Override 
    public void onProjectSelected(File project) {}

    /**
     * Unbinds runtime monitors, breaks structural poll threads, and mitigates background resource leaks.
     */
    private void performLifecycleCleanup() {
        if (isCleanedUp.compareAndSet(false, true)) {
            if (fileSystemPollerTimer != null && fileSystemPollerTimer.isRunning()) {
                fileSystemPollerTimer.stop();
            }
            if (workspaceManager != null) {
                workspaceManager.removeWorkspaceListener(this);
            }
        }
    }

    @Override
    public void dispose() {
        performLifecycleCleanup();
        super.dispose();
    }

    private JPanel createPlaceholderTab(String message) {
        JPanel placeholderPanel = new JPanel(new BorderLayout());
        
        // Remplacement du gris statique par le puits ou le fond de surface sombre
        placeholderPanel.setBackground(palette.getBackgroundSurface());
        
        JLabel messageLabel = new JLabel(message, SwingConstants.CENTER);
        messageLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        
        // Remplacement du jaune canari statique par la couleur de texte secondaire ou l'accent
        messageLabel.setForeground(palette.getTextSecondary());
        
        placeholderPanel.add(messageLabel, BorderLayout.CENTER);
        return placeholderPanel;
    }

    /**
     * Validates the structural health of the manifest and auto-recovers if corrupted.
     */
    private void validateManifestHealth(ProjectManifest manifest) {
        boolean corrupted = false;

        if (manifest.projectName == null) corrupted = true;
        if (manifest.projectType == null) corrupted = true;
        if (manifest.tags == null) corrupted = true;
        if (manifest.experiment == null) corrupted = true;

        if (corrupted) {
            AppLogger.error("Manifest appears corrupted. Reinitializing with safe defaults.");
            manifest.ensureDefaults();
        }
    }
}
