package com.sphere.components.workspace;

import com.sphere.utils.AppLogger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.ui.SPTabbedPaneUI;

/**
 * High-Energy Physics environment creation window. Operates on background worker threads
 * to provision nested experiment topologies, boilerplate build contexts, and cross-platform build rules.
 */
public class ProjectCreatorWindow extends JFrame {

    private final ProjectCreationCallback generationCallback;

    private JTextField txtProjectName;
    private JButton btnSave;
    private JButton btnCancel;
    private JProgressBar progressOverlayIndicator;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    private static final String WORKSPACE_ROOT_DIRECTORY = "WorkSpace";
    private static final Pattern INVALID_NAME_PATTERN = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    public ProjectCreatorWindow(ProjectCreationCallback callback) {
        super("Initialize Scientific Project Workspace");
        this.generationCallback = callback;

        setSize(1250, 740);
        setMinimumSize(new Dimension(1200, 640));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // 1. Top Panel - Controls Banner Header Input Segment
        JPanel headerInputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        headerInputPanel.setBackground(palette.getTerminalBackground());
        headerInputPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getLogDefaultText()));

        JLabel lblName = new JLabel("Project Name:");
        lblName.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        txtProjectName = new JTextField(25);
        txtProjectName.setToolTipText("Enter a valid name sequence avoiding spaces or invalid target filesystem characters.");

        headerInputPanel.add(lblName);
        headerInputPanel.add(txtProjectName);
        add(headerInputPanel, BorderLayout.NORTH);

        // 2. Central Panel - Module Core Configuration Tabs
        JTabbedPane configurationModuleTabs = new JTabbedPane();
        configurationModuleTabs.setUI(new SPTabbedPaneUI());
        configurationModuleTabs.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        configurationModuleTabs.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));

        configurationModuleTabs.addTab("General Matrix", createGeneralConfigurationTab());
        configurationModuleTabs.addTab("ROOT Suite", createPlaceholderPanel("ROOT Runtime Binding Macros and Tree Options coming soon."));
        configurationModuleTabs.addTab("Geant4 Rules", createPlaceholderPanel("Geant4 Volumetric Detector Geometry and Physics List Builders coming soon."));
        configurationModuleTabs.addTab("MadGraph Setup", createPlaceholderPanel("MadGraph5 Matrix Element Generation and Kinematic cuts coming soon."));
        configurationModuleTabs.addTab("Herwig Process", createPlaceholderPanel("Herwig Cluster Hadronization and Parton Shower matrix templates coming soon."));

        add(configurationModuleTabs, BorderLayout.CENTER);

        // 3. Bottom Panel - Controls Footer Action Section
        JPanel footerActionPanel = new JPanel(new BorderLayout());
        footerActionPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        progressOverlayIndicator = new JProgressBar();
        progressOverlayIndicator.setIndeterminate(true);
        progressOverlayIndicator.setVisible(false);
        progressOverlayIndicator.setPreferredSize(new Dimension(140, 18));

        JPanel operationalButtonsWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        btnCancel = new JButton("Cancel Operations");
        btnSave = new JButton("Generate Workspace");

        Cursor handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        btnCancel.setCursor(handCursor);
        btnSave.setCursor(handCursor);

        operationalButtonsWrapper.add(btnCancel);
        operationalButtonsWrapper.add(btnSave);

        footerActionPanel.add(progressOverlayIndicator, BorderLayout.WEST);
        footerActionPanel.add(operationalButtonsWrapper, BorderLayout.EAST);
        add(footerActionPanel, BorderLayout.SOUTH);

        // Action Bindings Hookups
        btnCancel.addActionListener(e -> dispose());
        btnSave.addActionListener(e -> triggerAsynchronousWorkspaceCreation());

        setVisible(true);
    }

    /* ---------------------------------------------------------------------
    *  Project Creation Tabs
    */
    private JPanel createGeneralConfigurationTab() {
        JPanel containerLayout = new JPanel();
        containerLayout.setLayout(new BoxLayout(containerLayout, BoxLayout.Y_AXIS));
        containerLayout.setBorder(new EmptyBorder(20, 24, 20, 24));
        containerLayout.setBackground(palette.getTerminalBackground());//Project Creator pannel background

        JLabel lblTitle = new JLabel("Global Project Configuration Blueprint");
        lblTitle.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        lblTitle.setForeground(new Color(130, 163, 219));//YELLOW COLOR JUST TRACKER

        JLabel lblDesc = new JLabel("Upon generation initialization, Sphere provisions standard cross-platform templates:");
        lblDesc.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        containerLayout.add(lblTitle);
        containerLayout.add(Box.createVerticalStrut(12));
        containerLayout.add(lblDesc);
        containerLayout.add(Box.createVerticalStrut(16));

        String[] blueprints = {
            "• Physics Analysis Topologies (ROOT Macro structures and Jupyter Kernels)",
            "• Simulation Core Engines (Geant4 event matrices, MadGraph parameters, Herwig Shower blocks)",
            "• Embedded Build Toolchains (Deterministic cross-platform CMake architecture mappings)",
            "• Isolated Virtual Environments (Conda dependency lock files for high-energy physics dependencies)"
        };

        for (String item : blueprints) {
            JLabel lblItem = new JLabel(item);
            lblItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 10));
            lblItem.setForeground(palette.getLogDefaultText());
            containerLayout.add(lblItem);
            containerLayout.add(Box.createVerticalStrut(6));
        }

        return containerLayout;
    }

    private JPanel createPlaceholderPanel(String informationalStatusText) {
        JPanel layoutWrapper = new JPanel(new BorderLayout());
        layoutWrapper.setBackground(palette.getTerminalBackground());//Project Creator Other Panels Background
        layoutWrapper.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel statusLabel = new JLabel(informationalStatusText, SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 10));//Project Creator Other Panels Font
        statusLabel.setForeground(palette.getClearCleanPrefix());//Project Creator Other Panels Font color

        layoutWrapper.add(statusLabel, BorderLayout.CENTER);
        return layoutWrapper;
    }

    private void triggerAsynchronousWorkspaceCreation() {
        String inputProjectName = txtProjectName.getText().strip();

        // 1. Validation Checks
        if (inputProjectName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Target Project Name Cannot Be Empty.", 
                    "Validation Exception", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (INVALID_NAME_PATTERN.matcher(inputProjectName).find()) {
            JOptionPane.showMessageDialog(this, "The specified workspace name tracks invalid directory tokens or spaces.\n" +
                    "Please restrict input variables to standard alphanumeric strings or dashes.", 
                    "Validation Exception", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Path targetWorkspacePath = Paths.get(WORKSPACE_ROOT_DIRECTORY).resolve(inputProjectName).normalize();

        if (Files.exists(targetWorkspacePath)) {
            JOptionPane.showMessageDialog(this, "A target folder structure matches this designation name on the filesystem already.", 
                    "Structural Collision", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Lock form components and toggle progress indicator view tracking states
        setControlsEnabled(false);
        progressOverlayIndicator.setVisible(true);

        // 2. Multithreaded Background Generation Run
        SwingWorker<Void, Void> filesystemGenerationWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                provisionSystemDirectoriesAndTemplates(targetWorkspacePath, inputProjectName);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // Evaluates thread exceptions gracefully if any occurred
                    
                    //AppLogger.success("Workspace structure generated successfully at: " + targetWorkspacePath.toAbsolutePath());
                    
                    if (generationCallback != null) {
                        generationCallback.onProjectCreated(inputProjectName);
                    }

                    JOptionPane.showMessageDialog(ProjectCreatorWindow.this, 
                            "Project space directories and data manifests successfully initialized!", 
                            "Generation Complete", JOptionPane.INFORMATION_MESSAGE);
                    dispose();

                } catch (Exception processException) {
                    AppLogger.error("Failed executing storage provision run loops: " + processException.getMessage());
                    JOptionPane.showMessageDialog(ProjectCreatorWindow.this, 
                            "Could not initialize filesystem array structures: " + processException.getCause().getMessage(), 
                            "I/O Exception Failure", JOptionPane.ERROR_MESSAGE);
                    
                    setControlsEnabled(true);
                    progressOverlayIndicator.setVisible(false);
                }
            }
        };

        filesystemGenerationWorker.execute();
    }

    private void setControlsEnabled(boolean isEnabled) {
        txtProjectName.setEnabled(isEnabled);
        btnSave.setEnabled(isEnabled);
        btnCancel.setEnabled(isEnabled);
    }

    private void provisionSystemDirectoriesAndTemplates(Path baseRoot, String projectName) throws IOException {
        // Build base layout path targets safely
        Files.createDirectories(baseRoot);

        String[] subDirectoriesBlueprints = {
                "env", "include", "scripts", "externals",
                "src/core", "src/utils", "src/detectors", "src/physics",
                "sim/geant4", "sim/madgraph", "sim/herwig", "sim/configs",
                "analysis/root", "analysis/python", "analysis/jupyter",
                "data/input", "data/output", "docs/figures"
        };

        for (String relativeSubPath : subDirectoriesBlueprints) {
            Files.createDirectories(baseRoot.resolve(relativeSubPath));
        }

        // Commit standard template file configurations downstream onto the mounted disk target
        writeTemplateFile(baseRoot, "CMakeLists.txt", generateCMakeTemplatePayload(projectName));
        writeTemplateFile(baseRoot, "README.md", "# " + projectName + "\nScientific analysis directory layout initialized by Sphere.\n");
        writeTemplateFile(baseRoot, ".workflow", generateWorkflowTemplatePayload());
        writeTemplateFile(baseRoot, ".projectsettings", generateProjectSettingsTemplatePayload(projectName, baseRoot.toAbsolutePath().toString()));
        writeTemplateFile(baseRoot, "env/environment.yml", generateEnvironmentCondaTemplatePayload());
    }

    private void writeTemplateFile(Path rootPath, String relativeTarget, String contentPayload) throws IOException {
        Path directFileTarget = rootPath.resolve(relativeTarget).normalize();
        Path safeParentTarget = directFileTarget.getParent();
        
        if (safeParentTarget != null && !Files.exists(safeParentTarget)) {
            Files.createDirectories(safeParentTarget);
        }

        Files.writeString(directFileTarget, contentPayload, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // --- High Energy Physics String Payload Structural Builders ---

    private String generateProjectSettingsTemplatePayload(String projectName, String rootPath) {
        String normalizedFsPath = rootPath.replace("\\", "/");
        return """
        {
          "projectName": "%s",
          "projectRoot": "%s",
          "directories": [
            "env", "src/core", "src/utils", "src/detectors", "src/physics", "include",
            "sim/geant4", "sim/madgraph", "sim/herwig", "sim/configs",
            "analysis/root", "analysis/python", "analysis/jupyter",
            "data/input", "data/output", "externals", "scripts", "docs/figures"
          ],
          "files": [
            "CMakeLists.txt", "README.md", ".workflow", "env/environment.yml"
          ]
        }
        """.formatted(projectName, normalizedFsPath);
    }

    private String generateWorkflowTemplatePayload() {
        return """
        {
          "modules": {
            "root": true,
            "geant4": false,
            "madgraph": false,
            "herwig": false,
            "python": true,
            "latex": true
          },
          "buildSystem": "cmake",
          "pipelines": {
            "simulation": ["geant4:run", "madgraph:generate", "herwig:shower"],
            "analysis": ["root:macro", "python:script", "jupyter:notebook"]
          }
        }
        """;
    }

    private String generateEnvironmentCondaTemplatePayload() {
        return """
        name: hep-env
        dependencies:
          - python=3.11
          - root
          - numpy
          - matplotlib
          - jupyterlab
        """;
    }

    private String generateCMakeTemplatePayload(String projectName) {
        return """
        cmake_minimum_required(VERSION 3.16)
        project(%s LANGUAGES CXX)
        
        set(CMAKE_CXX_STANDARD 17)
        set(CMAKE_CXX_STANDARD_REQUIRED ON)
        """.formatted(projectName);
    }
}
