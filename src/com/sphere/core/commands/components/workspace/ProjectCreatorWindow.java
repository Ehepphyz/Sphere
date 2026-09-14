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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.ui.SPComboBoxUI;
import com.sphere.ui.SPTabbedPaneUI;

/**
 * High-Energy Physics environment creation window. Operates on background worker threads
 * to provision nested experiment topologies, boilerplate build contexts, and cross-platform build rules.
 *
 * Every field except the name is optional. A name on its own builds the standard
 * layout; a field the user fills in replaces the default it stands for, so the
 * window never forces anyone through a form to get a project.
 */
public class ProjectCreatorWindow extends JFrame {

    private final ProjectCreationCallback generationCallback;

    private JTextField txtProjectName;
    private JComboBox<String> cmbExperiment;
    private JComboBox<String> cmbProjectType;
    private JTextField txtPresetVersion;
    private JTextField txtTags;
    private JTextArea txtDescription;
    private JTextArea txtFolders;
    private JTextArea txtFiles;
    private JTextArea txtCMake;
    private JTextArea txtEnvironment;
    private final Map<String, JCheckBox> moduleToggles = new LinkedHashMap<>();
    private final Map<String, JTextField> moduleFolders = new LinkedHashMap<>();
    private final Map<String, JTextField> moduleFiles = new LinkedHashMap<>();
    private final Map<String, JTextArea> moduleContents = new LinkedHashMap<>();

    private JButton btnSave;
    private JButton btnCancel;
    private JProgressBar progressOverlayIndicator;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    private static final String WORKSPACE_ROOT_DIRECTORY = "WorkSpace";
    private static final Pattern INVALID_NAME_PATTERN = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    /** The layout a project gets when the user names it and nothing else. */
    private static final String[] DEFAULT_DIRECTORIES = {
            "env", "include", "scripts", "externals",
            "src/core", "src/utils", "src/detectors", "src/physics",
            "sim/geant4", "sim/madgraph", "sim/herwig", "sim/configs",
            "analysis/root", "analysis/python", "analysis/jupyter",
            "data/input", "data/output", "docs/figures"
    };

    private static final String[] DEFAULT_FILES = {
            "CMakeLists.txt", "README.md", "env/environment.yml"
    };

    /** The experiment that adds nothing of its own to the standard layout. */
    private static final String NO_EXPERIMENT = "Generic";

    /**
     * Generic, then every framework the preset rules know, then Custom.
     *
     * Taking the middle from the rules is what keeps the two in step: a
     * framework the rules know is one a project can be built on.
     */
    private static String[] experiments() {
        List<String> all = new ArrayList<>();
        all.add(NO_EXPERIMENT);
        all.addAll(com.sphere.components.WorkspaceManager.DEFAULT_PREFIXES.keySet());
        all.add("Custom");
        return all.toArray(new String[0]);
    }

    private static final String[] PROJECT_TYPES = {
            "Analysis", "Detector Simulation", "Event Generation", "Reconstruction",
            "Visualization", "Documentation", "ML/AI Pipeline", "Custom"
    };

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
        style(txtProjectName);

        JLabel lblHint = new JLabel("A name is enough. Anything you fill in elsewhere replaces the default it stands for.");
        lblHint.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        lblHint.setForeground(palette.getLogDefaultText());

        headerInputPanel.add(lblName);
        headerInputPanel.add(txtProjectName);
        headerInputPanel.add(Box.createHorizontalStrut(16));
        headerInputPanel.add(lblHint);
        add(headerInputPanel, BorderLayout.NORTH);

        // 2. Central Panel - Module Core Configuration Tabs
        JTabbedPane configurationModuleTabs = new JTabbedPane();
        configurationModuleTabs.setUI(new SPTabbedPaneUI());
        configurationModuleTabs.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        configurationModuleTabs.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));

        configurationModuleTabs.addTab("General Matrix", createGeneralConfigurationTab());
        configurationModuleTabs.addTab("ROOT Suite", createModuleTab("root",
            "ROOT Runtime Binding Macros and Tree Options",
            "analysis/root", "analysis/root/macro.C", defaultRootMacro(), true));
        configurationModuleTabs.addTab("Geant4 Rules", createModuleTab("geant4",
            "Geant4 Volumetric Detector Geometry and Physics List Builders",
            "sim/geant4", "sim/geant4/run.mac", defaultGeant4Macro(), false));
        configurationModuleTabs.addTab("MadGraph Setup", createModuleTab("madgraph",
            "MadGraph5 Matrix Element Generation and Kinematic cuts",
            "sim/madgraph", "sim/madgraph/proc_card.dat", defaultMadGraphCard(), false));
        configurationModuleTabs.addTab("Herwig Process", createModuleTab("herwig",
            "Herwig Cluster Hadronization and Parton Shower matrix templates",
            "sim/herwig", "sim/herwig/setup.in", defaultHerwigInput(), false));
        configurationModuleTabs.addTab("Build", createBuildTab());

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
        JPanel containerLayout = new JPanel(new BorderLayout(0, 16));
        containerLayout.setBorder(new EmptyBorder(20, 24, 20, 24));
        containerLayout.setBackground(palette.getTerminalBackground());//Project Creator pannel background

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        cmbExperiment = new JComboBox<>(experiments());
        cmbProjectType = new JComboBox<>(PROJECT_TYPES);
        style(cmbExperiment);
        style(cmbProjectType);
        cmbExperiment.setToolTipText("Generic keeps the standard layout. "
            + "An experiment adds its own folders on top of it.");

        txtPresetVersion = new JTextField("1.0.0", 10);
        txtTags = new JTextField(24);
        txtTags.setToolTipText("Separated by commas.");
        style(txtPresetVersion);
        style(txtTags);

        txtDescription = area(3);

        int row = 0;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0.0;
        form.add(label("Experiment:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(cmbExperiment, gc);
        gc.gridx = 2; gc.weightx = 0.0;
        form.add(label("Preset Version:"), gc);
        gc.gridx = 3; gc.weightx = 0.5;
        form.add(txtPresetVersion, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0.0;
        form.add(label("Project Type:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(cmbProjectType, gc);
        gc.gridx = 2; gc.weightx = 0.0;
        form.add(label("Tags:"), gc);
        gc.gridx = 3; gc.weightx = 0.5;
        form.add(txtTags, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0.0;
        gc.anchor = GridBagConstraints.NORTHWEST;
        form.add(label("Description:"), gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1.0;
        form.add(new JScrollPane(txtDescription), gc);

        txtFolders = area(0);
        txtFiles = area(0);
        JPanel layout = new JPanel(new GridLayout(1, 2, 12, 0));
        layout.setOpaque(false);
        layout.add(createEditorPanel(txtFolders,
            "Folders, one per line. Empty for the standard layout.",
            () -> String.join("\n", DEFAULT_DIRECTORIES)));
        layout.add(createEditorPanel(txtFiles,
            "Files, one per line. Empty for the standard set.",
            () -> String.join("\n", DEFAULT_FILES)));

        containerLayout.add(form, BorderLayout.NORTH);
        containerLayout.add(layout, BorderLayout.CENTER);
        containerLayout.add(createDefaultsSummary(), BorderLayout.SOUTH);

        SwingUtilities.invokeLater(() -> {
            cmbExperiment.setUI(new SPComboBoxUI());
            cmbProjectType.setUI(new SPComboBoxUI());
        });
        return containerLayout;
    }

    /** What gets built when the user fills in nothing beyond the name. */
    private JPanel createDefaultsSummary() {
        JPanel summary = new JPanel();
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setOpaque(false);

        JLabel lblTitle = new JLabel("Global Project Configuration Blueprint");
        lblTitle.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        lblTitle.setForeground(new Color(130, 163, 219));//YELLOW COLOR JUST TRACKER
        lblTitle.setAlignmentX(LEFT_ALIGNMENT);

        JLabel lblDesc = new JLabel("Left alone, Sphere provisions standard cross-platform templates:");
        lblDesc.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        lblDesc.setAlignmentX(LEFT_ALIGNMENT);

        summary.add(lblTitle);
        summary.add(Box.createVerticalStrut(6));
        summary.add(lblDesc);
        summary.add(Box.createVerticalStrut(8));

        String[] blueprints = {
            "• Physics Analysis Topologies (ROOT Macro structures and Jupyter Kernels)",
            "• Simulation Core Engines (Geant4 event matrices, MadGraph parameters, Herwig Shower blocks)",
            "• Embedded Build Toolchains (Deterministic cross-platform CMake architecture mappings)",
            "• Isolated Virtual Environments (Conda dependency lock files for high-energy physics dependencies)"
        };

        for (String item : blueprints) {
            JLabel lblItem = new JLabel(item);
            lblItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 9));
            lblItem.setForeground(palette.getLogDefaultText());
            lblItem.setAlignmentX(LEFT_ALIGNMENT);
            summary.add(lblItem);
            summary.add(Box.createVerticalStrut(3));
        }
        return summary;
    }

    /**
     * One tool's tab: whether the project uses it, where its work lives, and the
     * file it starts from. Unticked, the tool contributes nothing to the project.
     */
    private JPanel createModuleTab(String module, String caption, String folder,
                                   String starterFile, String starterContent,
                                   boolean onByDefault) {
        JCheckBox toggle = new JCheckBox("Include " + module + " in this project");
        toggle.setOpaque(false);
        toggle.setForeground(palette.getTextPrimary());
        toggle.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        toggle.setSelected(onByDefault);
        moduleToggles.put(module, toggle);

        JTextField folderField = new JTextField(folder, 24);
        JTextField fileField = new JTextField(starterFile, 28);
        style(folderField);
        style(fileField);
        moduleFolders.put(module, folderField);
        moduleFiles.put(module, fileField);

        JTextArea content = area(0);
        content.setText(starterContent);
        moduleContents.put(module, content);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 4; gc.weightx = 1.0;
        form.add(toggle, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0.0;
        form.add(label("Folder:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(folderField, gc);
        gc.gridx = 2; gc.weightx = 0.0;
        form.add(label("Starter file:"), gc);
        gc.gridx = 3; gc.weightx = 1.0;
        form.add(fileField, gc);

        JPanel wrapper = new JPanel(new BorderLayout(0, 12));
        wrapper.setBackground(palette.getTerminalBackground());//Project Creator Other Panels Background
        wrapper.setBorder(new EmptyBorder(16, 16, 16, 16));
        wrapper.add(form, BorderLayout.NORTH);
        wrapper.add(createEditorPanel(content, caption, () -> starterContent),
                    BorderLayout.CENTER);

        return wrapper;
    }

    private JPanel createBuildTab() {
        txtCMake = area(0);
        txtEnvironment = area(0);

        JPanel split = new JPanel(new GridLayout(1, 2, 12, 0));
        split.setOpaque(false);
        split.add(createEditorPanel(txtCMake, "CMakeLists.txt. Empty for the standard one.",
                                    () -> defaultCMake(nameOrPlaceholder())));
        split.add(createEditorPanel(txtEnvironment,
                                    "env/environment.yml. Empty for the standard one.",
                                    this::defaultEnvironment));

        JPanel boxes = new JPanel(new GridLayout(1, 0, 12, 0));
        boxes.setOpaque(false);
        for (String module : new String[] {"python", "latex"}) {
            JCheckBox toggle = new JCheckBox(module + " in this project");
            toggle.setOpaque(false);
            toggle.setForeground(palette.getTextPrimary());
            toggle.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            toggle.setSelected(true);
            moduleToggles.put(module, toggle);
            boxes.add(toggle);
        }

        JPanel wrapper = new JPanel(new BorderLayout(0, 12));
        wrapper.setBackground(palette.getTerminalBackground());
        wrapper.setBorder(new EmptyBorder(16, 16, 16, 16));
        wrapper.add(boxes, BorderLayout.NORTH);
        wrapper.add(split, BorderLayout.CENTER);
        return wrapper;
    }

    /** A text area with the note above it and a button that fills in the default. */
    private JPanel createEditorPanel(JTextArea target, String note,
                                     java.util.function.Supplier<String> defaults) {
        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setBackground(palette.getTerminalBackground());//Project Creator Other Panels Background
        wrapper.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel caption = new JLabel(note);
        caption.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        caption.setForeground(palette.getLogDefaultText());

        JButton load = new JButton("Load defaults");
        load.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        load.addActionListener(e -> target.setText(defaults.get()));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.add(caption, BorderLayout.CENTER);
        header.add(load, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(target);
        scroll.setPreferredSize(new Dimension(320, 150));
        scroll.setBorder(BorderFactory.createEmptyBorder());

        wrapper.add(header, BorderLayout.NORTH);
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        label.setForeground(palette.getTextPrimary());
        return label;
    }

    private JTextArea area(int rows) {
        JTextArea text = new JTextArea(rows, 0);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(palette.getBackgroundTrack());
        text.setForeground(palette.getTextPrimary());
        text.setCaretColor(palette.getAccent());
        text.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return text;
    }

    private void style(JComboBox<String> combo) {
        combo.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        combo.putClientProperty("ComboBox.focusCellHighlightBorder",
                                BorderFactory.createEmptyBorder());
    }

    private void style(JTextField field) {
        field.setBackground(palette.getBackgroundTrack());
        field.setForeground(palette.getTextPrimary());
        field.setCaretColor(palette.getAccent());
        field.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    private String nameOrPlaceholder() {
        final String typed = txtProjectName.getText().strip();
        return typed.isEmpty() ? "project" : typed;
    }

    /* ---------------------------------------------------------------------
    *  Creation
    */
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

        final ProjectManifest manifest = collectManifest(inputProjectName, targetWorkspacePath);

        // Lock form components and toggle progress indicator view tracking states
        setControlsEnabled(false);
        progressOverlayIndicator.setVisible(true);

        // 2. Multithreaded Background Generation Run
        SwingWorker<Void, Void> filesystemGenerationWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                provisionSystemDirectoriesAndTemplates(targetWorkspacePath, manifest);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // Evaluates thread exceptions gracefully if any occurred

                    AppLogger.success("Workspace structure generated at: "
                        + targetWorkspacePath.toAbsolutePath());

                    if (generationCallback != null) {
                        generationCallback.onProjectCreated(inputProjectName);
                    }

                    JOptionPane.showMessageDialog(ProjectCreatorWindow.this,
                            "Project space directories and data manifests successfully initialized!",
                            "Generation Complete", JOptionPane.INFORMATION_MESSAGE);
                    dispose();

                } catch (Exception processException) {
                    // A cancelled worker carries no cause, so the message has to be
                    // taken from whichever of the two is there.
                    Throwable reason = processException.getCause() != null
                        ? processException.getCause() : processException;
                    AppLogger.error("Failed executing storage provision run loops: " + reason.getMessage());
                    JOptionPane.showMessageDialog(ProjectCreatorWindow.this,
                            "Could not initialize filesystem array structures: " + reason.getMessage(),
                            "I/O Exception Failure", JOptionPane.ERROR_MESSAGE);

                    setControlsEnabled(true);
                    progressOverlayIndicator.setVisible(false);
                }
            }
        };

        filesystemGenerationWorker.execute();
    }

    /** Reads the form into a manifest, leaving the defaults where nothing was typed. */
    private ProjectManifest collectManifest(String projectName, Path root) {
        ProjectManifest manifest = new ProjectManifest();
        manifest.projectName = projectName;
        manifest.projectRoot = root.toAbsolutePath().toString();
        manifest.experiment = (String) cmbExperiment.getSelectedItem();
        manifest.projectType = (String) cmbProjectType.getSelectedItem();
        manifest.presetVersion = txtPresetVersion.getText().strip();
        manifest.description = txtDescription.getText().strip();
        manifest.customDefaultStructure = txtFolders.getText().strip();

        for (String tag : txtTags.getText().split(",")) {
            final String cleaned = tag.strip();
            if (!cleaned.isEmpty()) {
                manifest.tags.add(cleaned);
            }
        }

        final String stamp = Instant.now().toString();
        manifest.createdAt = stamp;
        manifest.modifiedAt = stamp;
        manifest.ensureDefaults();
        return manifest;
    }

    private void setControlsEnabled(boolean isEnabled) {
        txtProjectName.setEnabled(isEnabled);
        btnSave.setEnabled(isEnabled);
        btnCancel.setEnabled(isEnabled);
    }

    private void provisionSystemDirectoriesAndTemplates(Path baseRoot, ProjectManifest manifest)
            throws IOException {
        // Build base layout path targets safely
        Files.createDirectories(baseRoot);

        List<String> directories = relativePaths(txtFolders.getText());
        if (directories.isEmpty()) {
            directories = List.of(DEFAULT_DIRECTORIES);
        }
        for (String relativeSubPath : directories) {
            Path target = insideRoot(baseRoot, relativeSubPath);
            if (target != null) {
                Files.createDirectories(target);
            }
        }

        List<String> files = relativePaths(txtFiles.getText());
        if (files.isEmpty()) {
            files = List.of(DEFAULT_FILES);
        }
        for (String relativeTarget : files) {
            if (insideRoot(baseRoot, relativeTarget) != null) {
                writeTemplateFile(baseRoot, relativeTarget,
                                  templateFor(relativeTarget, manifest.projectName));
            }
        }

        // A ticked tool adds its folder and its starter file to whatever the
        // layout above already holds.
        for (Map.Entry<String, JCheckBox> module : moduleToggles.entrySet()) {
            if (!module.getValue().isSelected()) {
                continue;
            }
            JTextField folderField = moduleFolders.get(module.getKey());
            if (folderField != null && !folderField.getText().isBlank()) {
                Path folder = insideRoot(baseRoot, folderField.getText().strip());
                if (folder != null) {
                    Files.createDirectories(folder);
                }
            }
            JTextField fileField = moduleFiles.get(module.getKey());
            JTextArea contentArea = moduleContents.get(module.getKey());
            if (fileField != null && !fileField.getText().isBlank()
                && insideRoot(baseRoot, fileField.getText().strip()) != null) {
                writeTemplateFile(baseRoot, fileField.getText().strip(),
                                  contentArea == null ? "" : contentArea.getText());
            }
        }

        // Commit the two records every project carries, whatever its layout.
        writeTemplateFile(baseRoot, ".workflow", generateWorkflowTemplatePayload());
        writeTemplateFile(baseRoot, ".projectsettings",
                generateProjectSettingsTemplatePayload(manifest.projectName,
                        baseRoot.toAbsolutePath().toString(), directories, files));

        // An experiment lays its own folders over the base rather than instead of it.
        if (!NO_EXPERIMENT.equals(manifest.experiment)) {
            ProjectStructureGenerator.generate(manifest);
        }

        // The manifest joins the structure in the same file: saving merges over
        // what is already there, so the folder and file lists above are kept.
        ProjectManifestIO.save(manifest, baseRoot.resolve(".projectsettings"));
    }

    /** Non-empty lines, trimmed, with the separators a user is likely to type. */
    private static List<String> relativePaths(String text) {
        List<String> paths = new ArrayList<>();
        if (text == null) {
            return paths;
        }
        for (String line : text.split("[,\\n\\r;]+")) {
            final String cleaned = line.strip();
            if (!cleaned.isEmpty()) {
                paths.add(cleaned.replace('\\', '/'));
            }
        }
        return paths;
    }

    /** Resolves a typed path, or null when it would land outside the project. */
    private static Path insideRoot(Path root, String relative) {
        Path candidate = root.resolve(relative).normalize();
        return candidate.startsWith(root.normalize()) ? candidate : null;
    }

    private String templateFor(String relativeTarget, String projectName) {
        return switch (relativeTarget) {
            case "CMakeLists.txt" -> txtCMake.getText().isBlank()
                ? defaultCMake(projectName) : txtCMake.getText();
            case "env/environment.yml" -> txtEnvironment.getText().isBlank()
                ? defaultEnvironment() : txtEnvironment.getText();
            case "README.md" -> "# " + projectName
                + "\nScientific analysis directory layout initialized by Sphere.\n";
            default -> "";
        };
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

    private String generateProjectSettingsTemplatePayload(String projectName, String rootPath,
                                                          List<String> directories,
                                                          List<String> files) {
        String normalizedFsPath = rootPath.replace("\\", "/");
        StringBuilder payload = new StringBuilder();
        payload.append("{\n  \"projectName\": \"").append(projectName)
               .append("\",\n  \"projectRoot\": \"").append(normalizedFsPath)
               .append("\",\n  \"directories\": [\n");
        appendQuoted(payload, directories);
        payload.append("  ],\n  \"files\": [\n");
        appendQuoted(payload, files);
        payload.append("  ]\n}\n");
        return payload.toString();
    }

    private static void appendQuoted(StringBuilder into, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            into.append("    \"").append(values.get(i)).append("\"");
            into.append(i < values.size() - 1 ? ",\n" : "\n");
        }
    }

    /**
     * What the project is made of, as the modules the user ticked.
     *
     * The pipelines follow the same ticks: a project without Geant4 has no
     * business advertising a Geant4 step.
     */
    private String generateWorkflowTemplatePayload() {
        StringBuilder payload = new StringBuilder("{\n  \"modules\": {\n");
        int written = 0;
        for (Map.Entry<String, JCheckBox> module : moduleToggles.entrySet()) {
            payload.append("    \"").append(module.getKey()).append("\": ")
                   .append(module.getValue().isSelected());
            payload.append(++written < moduleToggles.size() ? ",\n" : "\n");
        }
        payload.append("  },\n  \"entries\": {\n");

        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> module : moduleToggles.entrySet()) {
            JTextField folderField = moduleFolders.get(module.getKey());
            JTextField fileField = moduleFiles.get(module.getKey());
            if (!module.getValue().isSelected() || folderField == null || fileField == null) {
                continue;
            }
            entries.add("    \"" + module.getKey() + "\": {\"folder\": \""
                + folderField.getText().strip() + "\", \"file\": \""
                + fileField.getText().strip() + "\"}");
        }
        payload.append(String.join(",\n", entries));
        payload.append(entries.isEmpty() ? "" : "\n");
        payload.append("  },\n  \"buildSystem\": \"cmake\",\n  \"pipelines\": {\n");
        payload.append("    \"simulation\": [")
               .append(steps(Map.of("geant4", "geant4:run", "madgraph", "madgraph:generate",
                                    "herwig", "herwig:shower"),
                             new String[] {"geant4", "madgraph", "herwig"}))
               .append("],\n");
        payload.append("    \"analysis\": [")
               .append(steps(Map.of("root", "root:macro", "python", "python:script"),
                             new String[] {"root", "python"}))
               .append("]\n  }\n}\n");
        return payload.toString();
    }

    /** The steps of a pipeline, in order, for the modules that are on. */
    private String steps(Map<String, String> byModule, String[] order) {
        List<String> chosen = new ArrayList<>();
        for (String module : order) {
            JCheckBox toggle = moduleToggles.get(module);
            if (toggle != null && toggle.isSelected()) {
                chosen.add("\"" + byModule.get(module) + "\"");
            }
        }
        return String.join(", ", chosen);
    }

    private String defaultEnvironment() {
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

    private String defaultRootMacro() {
        return """
        void macro() {
            TFile file("../../data/output/results.root", "RECREATE");
            TH1F spectrum("spectrum", "spectrum;value;entries", 100, 0, 100);
            spectrum.Write();
            file.Close();
        }
        """;
    }

    private String defaultGeant4Macro() {
        return """
        /run/initialize
        /run/verbose 1
        /event/verbose 0
        /gun/particle mu-
        /gun/energy 10 GeV
        /run/beamOn 1000
        """;
    }

    private String defaultMadGraphCard() {
        return """
        import model sm
        generate p p > t t~
        output proc_tt
        """;
    }

    private String defaultHerwigInput() {
        return """
        read snippets/PPCollider.in
        set EventGenerator:NumberOfEvents 1000
        set EventGenerator:EventHandler:LuminosityFunction:Energy 13600.0
        saverun setup EventGenerator
        """;
    }

    private String defaultCMake(String projectName) {
        return """
        cmake_minimum_required(VERSION 3.16)
        project(%s LANGUAGES CXX)

        set(CMAKE_CXX_STANDARD 17)
        set(CMAKE_CXX_STANDARD_REQUIRED ON)
        """.formatted(projectName);
    }
}
