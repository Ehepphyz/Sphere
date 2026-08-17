package com.sphere.ui;

// --- Java Core, AWT & Swing UI ---
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;

// --- Icons Displayers ---
import javax.swing.Icon;
import javax.swing.ImageIcon;

// --- Swing Table Models & Renderers ---
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

// --- Java I/O & System Utilities ---
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

// --- Java Concurrency & Async Tasks ---
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// --- Sphere Framework Utilities ---
import com.sphere.fonts.FontLoader;
import com.sphere.utils.IconManager;
import com.sphere.utils.AppLogger;
import com.sphere.utils.JsonParser; 
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

// --- Sphere Core & Python Subpackages ---
import com.sphere.core.python.PythonEnvService;
import com.sphere.core.python.RequirementsDialog;
import com.sphere.core.python.PackageIntelligence;
import com.sphere.core.python.PipAction;
import com.sphere.core.python.EnvStatus;

// --- Custom UI & Animation Elements ---
import com.sphere.theme.AnimLoader;
import com.sphere.theme.AnimProgressBar;

/* -------------------------------------------------------------------------
 * 
 * Python Environment Manager (Refactored with TaskGroup, CompletableFuture, MVC-ish, spinner).
 * 
 * ------------------------------------------------------------------------- */
public class PyEnvManagerDialog extends JDialog {

    /* -------------------------------------------------------------------------
     * FONTS / CONSTANTS
     * -------------------------------------------------------------------------
     */
    private static final Font ARROW_FONT = FontLoader.getAccentFont(Font.PLAIN, 14);
    private final AnimLoader sharedLoader = new AnimLoader();

    /* -------------------------------------------------------------------------
     * MVC LIGHTWEIGHT ARCHITECTURE
     * -------------------------------------------------------------------------
     */
    private PythonEnvService envService;
    private final PythonEnvController controller;

    // VIEW COMPONENTS
    private JTextField pathField;
    private JLabel versionLabel;
    private JLabel envLabel;

    private JTable moduleTable;
    private ModuleTableModel tableModel;
    private JTextArea brokenArea;
    private JTextArea logArea;
    private JPanel packagePanel;
    private JPanel topPanel;
    private AnimProgressBar progressBar;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    private JTextField packageField;
    private JTextField searchField;

    private boolean packagesCollapsed = true;
    private CollapseButton togglePackageBtn;

    // TASK GROUP MANAGEMENT
    private final TaskGroup taskGroup;

    // Safe wrapper around TaskGroup for robust async handling
    private SafeTaskGroup safeTaskGroup;

    // Timers
    private Timer spinnerTimer;
    private Timer tableRepaintTimer; // Tracked instance variable to prevent memory leaks
    private int spinnerIndex = 0;

    private static final ExecutorService PY_ENV_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SciPhy-PythonEnv-Worker");
        thread.setDaemon(true); // Prevents blocking JVM shutdown if a thread is hanging
        return thread;
    });

    public PyEnvManagerDialog() {
        try {
            this.controller = new PythonEnvController();

            setTitle("Python Environment Manager");

            Icon appIcon = com.sphere.utils.IconManager.getIcon("cta_logo.png");
            if (appIcon instanceof ImageIcon) {
                // Extract the underlying Image from the ImageIcon wrapper and set it on this dialog
                setIconImage(((ImageIcon) appIcon).getImage());
            }
            setLayout(new BorderLayout());

            packagePanel = new JPanel(new BorderLayout());
            packagePanel.setVisible(false); // Collapsed at startup execution

            initTopPanel();
            initPackagePanel();

            // TaskGroup hook: when all threads complete execution -> progress(false)
            this.taskGroup = new TaskGroup(() -> SwingUtilities.invokeLater(() -> progress(false)));
            // SafeTaskGroup uses the same completion hook
            this.safeTaskGroup = new SafeTaskGroup(() -> SwingUtilities.invokeLater(() -> progress(false)));

            updatePythonService();
            validatePythonPath();

            // UI/UX Window Interception: Force cleanup of background processes on dialog close
            this.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            this.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    AppLogger.info("[WATCHDOG] PyEnvManagerDialog closing. Initiating controller shutdown sequence...");
                    if (controller != null) {
                        controller.shutdown();
                    }
                }
            });

            // Center window after components have been structurally initialized
            this.setLocationRelativeTo(null);

        } catch (Throwable ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    "Exception occurred during construction:\n" + ex.toString(),
                    "FATAL ERROR",
                    JOptionPane.ERROR_MESSAGE
            );
            throw ex;
        }
    }

    /* -------------------------------------------------------------------------
     * TOP HEADER PANEL INITIALIZATION
     */
    private void initTopPanel() {
        topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.setBorder(BorderFactory.createTitledBorder("Python Executable Path"));

        String initialPath = PythonEnvService.loadPythonExecFromConfig("settings.conf");
        pathField = new JTextField(initialPath);

        versionLabel = new JLabel(" ");
        versionLabel.setFont(FontLoader.getGlobalFont(Font.ITALIC, 10));

        envLabel = new JLabel(" ");
        envLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 10));

        JPanel pathContainer = new JPanel(new BorderLayout());
        pathContainer.add(pathField, BorderLayout.CENTER);

        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.add(versionLabel);
        infoPanel.add(envLabel);
        pathContainer.add(infoPanel, BorderLayout.SOUTH);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton browseBtn = new JButton("Browse");
        JButton saveBtn = new JButton("Save");
        togglePackageBtn = new CollapseButton();
        packagesCollapsed = true;
        JButton closeBtn = new JButton("Close");
        
        browseBtn.addActionListener(e -> onBrowsePython());
        saveBtn.addActionListener(e -> onSavePythonPath());
        togglePackageBtn.addActionListener(e -> onTogglePackagePanel());
        closeBtn.addActionListener(e -> dispose());

        btnPanel.add(browseBtn);
        btnPanel.add(saveBtn);
        btnPanel.add(togglePackageBtn);
        btnPanel.add(closeBtn);

        progressBar = new AnimProgressBar();
        progressBar.setVisible(false);

        topPanel.add(pathContainer, BorderLayout.CENTER);
        topPanel.add(btnPanel, BorderLayout.EAST);
        topPanel.add(progressBar, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
    }

    /* -------------------------------------------------------------------------
     * PACKAGE MANAGEMENT PANEL INITIALIZATION
     * -------------------------------------------------------------------------
     */
    private void initPackagePanel() {
        JPanel toolBar = new JPanel();
        toolBar.setLayout(new BoxLayout(toolBar, BoxLayout.Y_AXIS));

        JPanel line1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        packageField = new JTextField(15);
        JButton installBtn = new JButton("Install");
        JButton refreshBtn = new JButton("Refresh");
        JButton smartManagerBtn = new JButton("Smart Package Manager");
        JButton cachePurgeBtn = new JButton("Purge Cache");

        line1.add(new JLabel("Package:"));
        line1.add(packageField);
        line1.add(installBtn);
        line1.add(refreshBtn);
        line1.add(smartManagerBtn);
        line1.add(cachePurgeBtn);

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        JPanel line2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        searchField = new JTextField(15);
        searchField.setToolTipText("Filter packages");

        JButton openCacheBtn = new JButton("Open Cache");
        JButton openPackagesBtn = new JButton("Open Site-Packages");
        JButton upgradeAllBtn = new JButton("Upgrade All Outdated");
        JButton requireBtn = new JButton("Requirements");

        line2.add(new JLabel("Search:"));
        line2.add(searchField);
        line2.add(openCacheBtn);
        line2.add(openPackagesBtn);
        line2.add(upgradeAllBtn);
        line2.add(requireBtn);

        installBtn.addActionListener(e -> onInstallPackage());
        refreshBtn.addActionListener(e -> loadModules());
        smartManagerBtn.addActionListener(e -> onOpenSmartPackageManager());
        cachePurgeBtn.addActionListener(e -> purgePipCache());
        openCacheBtn.addActionListener(e -> onOpenCache());
        openPackagesBtn.addActionListener(e -> onOpenSitePackages());
        upgradeAllBtn.addActionListener(e -> onUpgradeAllOutdated());
        requireBtn.addActionListener(e -> new RequirementsDialog(this));

        toolBar.add(line1);
        toolBar.add(sep);
        toolBar.add(line2);

        packagePanel.add(toolBar, BorderLayout.NORTH);

        tableModel = new ModuleTableModel();
        moduleTable = new JTable(tableModel);
        moduleTable.setRowHeight(28);
        configureTableColumns();

        // Setup Actions Column Interaction
        moduleTable.getColumnModel().getColumn(3).setCellRenderer(new ActionRenderer());
        moduleTable.getColumnModel().getColumn(3).setCellEditor(
                new ActionEditor(this, this::loadModules));

        // Setup Latest Version Status Column Interaction
        TableColumn latestCol = moduleTable.getColumnModel().getColumn(2);
        latestCol.setCellRenderer(new LatestColumnRenderer());

        TableRowSorter<TableModel> sorter = new TableRowSorter<>(moduleTable.getModel());
        moduleTable.setRowSorter(sorter);
        searchField.getDocument().addDocumentListener((SimpleDocumentListener) () -> {
            String text = searchField.getText();
            sorter.setRowFilter(text == null || text.isEmpty()
                    ? null
                    : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        });

        packagePanel.add(new JScrollPane(moduleTable), BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new GridLayout(2, 1));
        brokenArea = new JTextArea(4, 20);
        brokenArea.setEditable(false);
        brokenArea.setForeground(palette.getPythonUpdateColor());
        bottomPanel.add(new JScrollPane(brokenArea));

        logArea = new JTextArea(4, 20);
        logArea.setEditable(false);
        bottomPanel.add(new JScrollPane(logArea));

        packagePanel.add(bottomPanel, BorderLayout.SOUTH);

        add(packagePanel, BorderLayout.CENTER);

        // Saved as an instance variable to avoid memory leaks on dispose
        tableRepaintTimer = new Timer(80, e -> moduleTable.repaint());
        tableRepaintTimer.start();
    }

    @Override
    public void dispose() {
        // Prevent background Swing Timer from running forever and pinning memory
        if (tableRepaintTimer != null && tableRepaintTimer.isRunning()) {
            tableRepaintTimer.stop();
        }
        if (spinnerTimer != null && spinnerTimer.isRunning()) {
            spinnerTimer.stop();
        }
        super.dispose();
    }

    /* -------------------------------------------------------------------------
     * THEME-COMPLIANT COLLAPSE HANDLER BUTTON
     * -------------------------------------------------------------------------
     */
    private class CollapseButton extends JButton {

        private final Font arrowFont = FontLoader.getAccentFont(Font.PLAIN, 14);
        private final Font labelFont = FontLoader.getGlobalFont(Font.PLAIN, 12);
        private boolean isHovered = false;

        public CollapseButton() {
            setPreferredSize(new Dimension(180, 28));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setText("");
            setFont(labelFont);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent evt) {
                    isHovered = true;
                    repaint();
                }
                @Override
                public void mouseExited(MouseEvent evt) {
                    isHovered = false;
                    repaint();
                }
            });
        }

        public void updateState() {
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();

            // 1. Dynamic color retrieval from the centralized DarkTheme system
            Color darkSurface  = UIManager.getColor("nimbusLightBackground");
            Color buttonHover  = UIManager.getColor("Button[MouseOver].background"); 
            Color darkBorder   = UIManager.getColor("nimbusBlueGrey");
            Color brightText   = UIManager.getColor("text");

            // Safe fallback defaults in case UIManager properties are unresolved
            if (darkSurface == null) darkSurface = palette.getBackgroundSurface();
            if (buttonHover == null) buttonHover = palette.getButtonHover();
            if (darkBorder == null) darkBorder = palette.getBorder();
            if (brightText == null) brightText = palette.getTextPrimary();

            // 2. Background and hover state rendering
            Color backgroundColor = isHovered ? buttonHover : darkSurface;
            g2.setColor(backgroundColor);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // 3. Flat bottom border divider line rendering
            g2.setColor(darkBorder);
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);

            // 4. Text anti-aliasing configurations
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 5. Glyph and label vector execution using theme-compliant foreground text
            String arrow = packagesCollapsed ? "▶" : "▼";
            g2.setFont(arrowFont);
            g2.setColor(brightText);

            FontMetrics arrowFm = g2.getFontMetrics();
            int y = (getHeight() + arrowFm.getAscent() - arrowFm.getDescent()) / 2;

            g2.drawString(arrow, 12, y);
            g2.setFont(labelFont);
            g2.drawString("Package Manager", 12 + arrowFm.stringWidth(arrow) + 8, y);

            g2.dispose();
        }
    }

//--------

    /* -------------------------------------------------------------------------
     * SMART PACKAGE MANAGER
     * -------------------------------------------------------------------------
     */
    private void onOpenSmartPackageManager() {
        if (envService == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "Python environment is not configured.",
                    "Smart Package Manager",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        JDialog dialog = new JDialog(this, "Smart Package Manager", true);
        dialog.setLayout(new BorderLayout());

        JTextArea intelligenceTextArea = new JTextArea();
        intelligenceTextArea.setEditable(false);
        intelligenceTextArea.setFont(FontLoader.getGlobalFont(Font.PLAIN, 10));
        intelligenceTextArea.setBackground(palette.getTerminalBackground());
        intelligenceTextArea.setForeground(palette.getTerminalForeground());

        JScrollPane scroll = new JScrollPane(intelligenceTextArea);

        JButton runIntelligenceButton = new JButton("Analyze Environment");
        runIntelligenceButton.setFont(FontLoader.getTerminalFont(Font.PLAIN, 11));
        runIntelligenceButton.addActionListener(
                e -> runPackageIntelligence(intelligenceTextArea, runIntelligenceButton)
        );

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(runIntelligenceButton);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);

        dialog.setSize(700, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void runPackageIntelligence(JTextArea targetArea, JButton triggerButton) {
        if (envService == null) {
            targetArea.setText("Python environment is not configured.\n");
            return;
        }

        triggerButton.setEnabled(false);
        targetArea.setText("Running analysis...\n");

        new SwingWorker<Void, Void>() {

            private List<PackageIntelligence.BrokenModule> broken = Collections.emptyList();
            private List<PackageIntelligence.Suggestion> suggestions = Collections.emptyList();
            private String errorMessage = null;

            @Override
            protected Void doInBackground() {
                try {
                    PackageIntelligence intel = new PackageIntelligence(envService);
                    broken = intel.findBrokenModules();
                    suggestions = intel.suggestPackages(envService.listModules());
                } catch (Exception ex) {
                    errorMessage = ex.toString();
                }
                return null;
            }

            @Override
            protected void done() {
                StringBuilder sb = new StringBuilder();

                if (errorMessage != null) {
                    sb.append("Error during analysis:\n").append(errorMessage).append("\n");
                } else {
                    sb.append("=== Broken Modules ===\n");
                    if (broken == null || broken.isEmpty()) {
                        sb.append("No broken modules detected.\n");
                    } else {
                        for (PackageIntelligence.BrokenModule b : broken) {
                            sb.append("- ").append(b.name)
                              .append(" : ").append(b.error).append("\n");
                        }
                    }

                    sb.append("\n=== Package Suggestions ===\n");
                    if (suggestions == null || suggestions.isEmpty()) {
                        sb.append("No suggestions available.\n");
                    } else {
                        for (PackageIntelligence.Suggestion s : suggestions) {
                            sb.append("- ").append(s.module)
                              .append(" (Reason: ").append(s.reason).append(")\n");
                        }
                    }
                }

                targetArea.setText(sb.toString());
                targetArea.setCaretPosition(0);
                triggerButton.setEnabled(true);
            }
        }.execute();
    }

    /* -------------------------------------------------------------------------
     * ACTIONS
     * -------------------------------------------------------------------------
     */
    private void onBrowsePython() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Python Executable");
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selectedPath = fileChooser.getSelectedFile().getAbsolutePath();
            pathField.setText(selectedPath);
            updatePythonService();
            validatePythonPath();
        }
    }

    private void onSavePythonPath() {
        PythonEnvService.savePythonExecToConfig("settings.conf", pathField.getText().trim());
        JOptionPane.showMessageDialog(this, "Configuration saved.");
        updatePythonService();
        validatePythonPath();
    }

    private void onTogglePackagePanel() {
        // Block collapse if Python executable is invalid
        if (envService == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "Not a valid python.exe executable.",
                    "Invalid Python Path",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        Point currentLocation = this.getLocation();

        packagesCollapsed = !packagesCollapsed;

        if (packagesCollapsed) {
            togglePackageBtn.setText("▶ Package Manager");
            packagePanel.setVisible(false);
            Dimension d = new Dimension(780, 110);
            this.setPreferredSize(d);
            this.setSize(d);
        } else {
            togglePackageBtn.setText("▼ Package Manager");
            packagePanel.setVisible(true);
            Dimension d = new Dimension(780, 800);
            this.setPreferredSize(d);
            this.setSize(d);
            loadModules();
        }

        this.revalidate();
        this.repaint();
        this.pack();
        togglePackageBtn.updateState();
        this.setLocation(currentLocation);
    }

    private void onInstallPackage() {
        String pkgName = packageField.getText().trim();
        if (pkgName.isEmpty() || envService == null) return;

        logToUI("Installing: " + pkgName + "...");
        new SwingWorker<Void, String>() {
            private String workerError = null;

            @Override
            protected Void doInBackground() {
                try {
                    List<String> out = envService.runPipAction(PipAction.INSTALL, pkgName);
                    if (out != null) {
                        for (String s : out) publish(s);
                    }
                } catch (Exception ex) {
                    workerError = ex.getMessage();
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(PyEnvManagerDialog.this::logToUI);
            }

            @Override
            protected void done() {
                if (workerError != null) {
                    logToUI("Installation failed: " + workerError);
                } else {
                    logToUI("Installation complete.");
                }
                loadModules();
            }
        }.execute();
    }

    private void onUpgradeAllOutdated() {
        if (envService == null) return;
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Upgrade all outdated packages?",
                "Confirm Upgrade",
                JOptionPane.YES_NO_OPTION
        );
        if (confirm != JOptionPane.YES_OPTION) return;

        logToUI("Upgrading all outdated packages...");
        new SwingWorker<Void, String>() {
            private String workerError = null;

            @Override
            protected Void doInBackground() {
                try {
                    List<String> out = envService.upgradeAllOutdated();
                    if (out != null) {
                        for (String s : out) publish(s);
                    }
                } catch (Exception ex) {
                    workerError = ex.getMessage();
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(PyEnvManagerDialog.this::logToUI);
            }

            @Override
            protected void done() {
                if (workerError != null) {
                    logToUI("Upgrade execution failed: " + workerError);
                } else {
                    logToUI("All packages upgraded successfully.");
                }
                loadModules();
            }
        }.execute();
    }

    private void onOpenCache() {
        if (envService == null) return;
        try {
            String path = envService.getPipCacheDirectory();
            openFolder(path);
        } catch (Exception e) {
            brokenArea.setText("Error opening cache directory: " + e.getMessage());
        }
    }

    private void onOpenSitePackages() {
        if (envService == null) return;
        try {
            String path = envService.getSitePackagesDirectory();
            openFolder(path);
        } catch (Exception e) {
            brokenArea.setText("Error opening site-packages directory: " + e.getMessage());
        }
    }

    private void openFolder(String path) {
        if (path == null || path.isEmpty()) {
            brokenArea.setText("Error: Directory path not found.");
            return;
        }
        File folder = new File(path);
        if (!folder.exists()) {
            brokenArea.setText("Error: Target directory folder does not exist: " + path);
            return;
        }
        try {
            Desktop.getDesktop().open(folder);
        } catch (IOException e) {
            brokenArea.setText("Error opening target system directory: " + e.getMessage());
        }
    }

    /* -------------------------------------------------------------------------
     * SERVICE & VALIDATION MANAGEMENT
     * -------------------------------------------------------------------------
     */
    private void updatePythonService() {
        String path = pathField.getText().trim();

        // Safety: avoid initializing service with a non-existing path
        if (path.isEmpty() || !new File(path).exists()) {
            envService = null;
            controller.setEnvService(null);
            if (brokenArea != null) {
                brokenArea.setText("Python executable not found.\nPath: " + path);
            }
            return;
        }

        try {
            envService = new PythonEnvService(path);
            controller.setEnvService(envService);
        } catch (Exception ex) {
            // Explicit handling for missing system runtimes and native I/O errors
            envService = null;
            controller.setEnvService(null);
            if (brokenArea != null) {
                brokenArea.setText("I/O error while initializing Python service:\n" + ex.getMessage());
            }
        }
    }

    public PythonEnvService getEnvService() {
        return envService;
    }

    private void validatePythonPath() {
        if (envService == null) {
            versionLabel.setText("Invalid path.");
            envLabel.setText(" ");
            return;
        }

        new SwingWorker<Void, Void>() {
            private String version = "Unknown version";
            private EnvStatus status = EnvStatus.NOT_PYTHON;

            @Override
            protected Void doInBackground() {
                try {
                    if (!envService.isPythonExecutable()) {
                        status = EnvStatus.NOT_PYTHON;
                        return null;
                    }

                    version = envService.getPythonVersion();
                    status = envService.detectEnvironment();
                } catch (Exception e) {
                    version = "Check failed: " + e.getMessage();
                    status = EnvStatus.NOT_PYTHON;
                }
                return null;
            }

            @Override
            protected void done() {
                switch (status) {
                    case NOT_PYTHON:
                        versionLabel.setText("Invalid executable (not Python)");
                        envLabel.setText(" ");
                        break;

                    case SYSTEM:
                        versionLabel.setText("Python version: " + version);
                        envLabel.setText("Environment: System Python");
                        break;

                    case VENV:
                        versionLabel.setText("Python version: " + version);
                        envLabel.setText("Environment: venv (Standard)");
                        break;

                    case VIRTUALENV:
                        versionLabel.setText("Python version: " + version);
                        envLabel.setText("Environment: virtualenv (Legacy)");
                        break;

                    case CONDA:
                        versionLabel.setText("Python version: " + version);
                        envLabel.setText("Environment: Conda");
                        break;

                    case MICROMAMBA:
                        versionLabel.setText("Python version: " + version);
                        envLabel.setText("Environment: Micromamba");
                        break;

                    case PYENV:
                        versionLabel.setText("Python version: " + version);
                        envLabel.setText("Environment: Pyenv");
                        break;
                }
                versionLabel.setFont(FontLoader.getGlobalFont(Font.ITALIC, 10));
                envLabel.setFont(FontLoader.getGlobalFont(Font.ITALIC, 10));
            }
        }.execute();
    }

//--------
    /* -------------------------------------------------------------------------
     * MODULES LOADING (Optimized + CompletableFuture + TaskGroup)
     * -------------------------------------------------------------------------
     */
    public void loadModules() {
        if (envService == null) {
            brokenArea.setText("No valid Python environment configured.");
            return;
        }

        brokenArea.setText("Loading packages...");
        progress(true);

        // 1. Fast loading sequence via asynchronous execution stream
        taskGroup.start();
        controller.listModulesFastAsync()
                .whenComplete((modules, ex) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (ex != null) {
                            brokenArea.setText("Error: " + ex.getMessage());
                            return;
                        }
                        // Populates the data model mapping instantly
                        tableModel.setModules(modules);

                        // Forces the tracking column into animated spinner mode
                        tableModel.setLatestLoading(true);
                        moduleTable.getTableHeader().getColumnModel()
                                .getColumn(2).setHeaderValue("Latest (loading...)");
                        moduleTable.getTableHeader().repaint();

                        brokenArea.setText("Updating versions and checking dependencies...");

                        // 2. Background multi-threaded task allocation triggers here
                        startParallelTasks();

                    } finally {
                        taskGroup.finish();
                    }
                }));
    }

    private void startParallelTasks() {
        // pip check
        runPipCheck();

        // pip upgrade
        runPipUpgrade();

        // pip outdated
        runOutdatedScan();
    }

    private void runPipCheck() {
        taskGroup.start();
        controller.checkDependenciesAsync()
                .whenComplete((check, ex) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (ex != null) {
                            brokenArea.setText("Error during pip check: " + ex.getMessage());
                        } else {
                            brokenArea.setText((check == null || check.isEmpty())
                                    ? "Environment is healthy."
                                    : check);
                        }
                    } finally {
                        taskGroup.finish();
                    }
                }));
    }

    private void runPipUpgrade() {
        taskGroup.start();
        controller.upgradePipAsync()
                .whenComplete((ignored, ex) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (ex != null) {
                            logToUI("Pip upgrade error: " + ex.getMessage());
                        } else {
                            logToUI("Pip upgrade finished.");
                        }
                    } finally {
                        taskGroup.finish();
                    }
                }));
    }

    private void runOutdatedScan() {
        taskGroup.start();
        controller.listOutdatedAsync()
                .whenComplete((outdated, ex) -> SwingUtilities.invokeLater(() -> {
                    try {
                        if (ex != null) {
                            logToUI("Error fetching outdated packages: " + ex.getMessage());
                            tableModel.setLatestLoading(false);
                            moduleTable.getTableHeader().getColumnModel()
                                    .getColumn(2).setHeaderValue("Latest");
                            moduleTable.getTableHeader().repaint();
                            return;
                        }

                        if (outdated != null) {
                            for (JsonParser.ModuleInfo o : outdated) {
                                tableModel.updateLatestVersion(o.name, o.latestVersion);
                            }
                        }
                        tableModel.setLatestLoading(false);
                        moduleTable.getTableHeader().getColumnModel()
                                .getColumn(2).setHeaderValue("Latest");
                        moduleTable.getTableHeader().repaint();

                    } finally {
                        taskGroup.finish();
                    }
                }));
    }

    /* -------------------------------------------------------------------------
     * PURGE PIP CACHE
     * -------------------------------------------------------------------------
     */
    private void purgePipCache() {
        if (envService == null) return;

        logToUI("Purging pip cache...");
        new SwingWorker<Void, String>() {
            private String workerError = null;

            @Override
            protected Void doInBackground() {
                try {
                    List<String> out = envService.purgeCache();
                    if (out != null) {
                        for (String s : out) publish(s);
                    }
                } catch (Exception ex) {
                    workerError = ex.getMessage();
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(PyEnvManagerDialog.this::logToUI);
            }

            @Override
            protected void done() {
                if (workerError != null) {
                    logToUI("Cache purge failed: " + workerError);
                } else {
                    logToUI("Pip cache purged successfully.");
                }
            }
        }.execute();
    }

    /* -------------------------------------------------------------------------
     * INTERFACE HELPERS
     * -------------------------------------------------------------------------
     */
    private void progress(boolean on) {
        progressBar.setIndeterminate(on); // ensure animation state matches visibility
        progressBar.setVisible(on);
        setCursor(Cursor.getPredefinedCursor(on ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        topPanel.revalidate();
        topPanel.repaint();
    }

    public void logToUI(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            AppLogger.raw(msg);
        });
    }

    private void configureTableColumns() {
        moduleTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        TableColumnModel cm = moduleTable.getColumnModel();
        cm.getColumn(0).setPreferredWidth(300);
        cm.getColumn(1).setMinWidth(100);
        cm.getColumn(1).setMaxWidth(100);
        cm.getColumn(2).setMinWidth(100);
        cm.getColumn(2).setMaxWidth(100);
        cm.getColumn(3).setMinWidth(260);
        cm.getColumn(3).setMaxWidth(260);
    }

    /* -------------------------------------------------------------------------
     * ATOMIC TASK GROUP CONCURRENCY TRACKER
     * -------------------------------------------------------------------------
     */
    private static class TaskGroup {
        private final AtomicInteger count = new AtomicInteger(0);
        private final Runnable onAllFinished;

        TaskGroup(Runnable onAllFinished) {
            this.onAllFinished = onAllFinished;
        }

        void start() {
            count.incrementAndGet();
        }

        void finish() {
            if (count.decrementAndGet() == 0) {
                onAllFinished.run();
            }
        }
    }

    /* -------------------------------------------------------------------------
     * CONTROLLER LAYER (MVC LIGHT) + CompletableFuture ASYNC PIPELINE
     * -------------------------------------------------------------------------
     */
    private static class PythonEnvController {

        private PythonEnvService envService;
        
        // Hardened Thread Pool: Bounded thread count (max 3 concurrent python tasks) 
        // to prevent CPU/IO spikes. Configured with daemon threads to prevent JVM shutdown hangs.
        private final ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "SciPhy-PythonEnv-Worker");
            thread.setDaemon(true); 
            return thread;
        });

        void setEnvService(PythonEnvService envService) {
            this.envService = envService;
        }

        /**
         * Executes a clean shutdown sequence of background workers.
         * Tries to gracefully shut down tasks first, then forcefully stops them if they persist.
         */
        void shutdown() {
            try {
                executor.shutdown(); // Disable new tasks from being submitted
                // Wait up to 2 seconds for active pip listings or upgrades to finalize cleanly
                if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Forcefully interrupt hanging network or shell tasks
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        CompletableFuture<List<JsonParser.ModuleInfo>> listModulesFastAsync() {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return envService.listModules();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor);
        }

        CompletableFuture<List<JsonParser.ModuleInfo>> listOutdatedAsync() {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return envService.listOutdated();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor);
        }

        CompletableFuture<String> checkDependenciesAsync() {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return envService.checkDependencies();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor);
        }

        CompletableFuture<Void> upgradePipAsync() {
            return CompletableFuture.runAsync(() -> {
                try {
                    envService.upgradePip();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor);
        }
    }

    public static class ModuleTableModel extends AbstractTableModel {

        private final List<JsonParser.ModuleInfo> modules = new ArrayList<>();
        private boolean latestLoading = false;

        private final String[] columns = {"Name", "Installed", "Latest", "Action"};

        public void setModules(List<JsonParser.ModuleInfo> list) {
            modules.clear();
            if (list != null) modules.addAll(list);
            fireTableDataChanged();
        }

        public void updateLatestVersion(String name, String latest) {
            for (int i = 0; i < modules.size(); i++) {
                JsonParser.ModuleInfo m = modules.get(i);
                if (m.name.equals(name)) {
                    m.latestVersion = latest;
                    fireTableCellUpdated(i, 2);
                    break;
                }
            }
        }

        public void setLatestLoading(boolean loading) {
            this.latestLoading = loading;
            if (loading) {
                for (JsonParser.ModuleInfo m : modules) {
                    m.latestVersion = "Loading...";
                }
                fireTableColumnUpdated(2);
            }
        }

        public boolean isLatestLoading() {
            return latestLoading;
        }

        private void fireTableColumnUpdated(int col) {
            if (getRowCount() == 0) return;
            fireTableRowsUpdated(0, getRowCount() - 1);
        }

        @Override
        public int getRowCount() {
            return modules.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JsonParser.ModuleInfo m = modules.get(rowIndex);
            switch (columnIndex) {
                case 0: return m.name;
                case 1: return m.version;
                case 2: return m.latestVersion;
                case 3: return "Actions";
            }
            return null;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 3;
        }
    }

//---------
        /* -------------------------------------------------------------------------
 * SINGLETON ACTION PANEL (FLYWEIGHT PATTERN OPTIMIZATION)
 * -------------------------------------------------------------------------
 */
class ActionPanel extends JPanel {
    // Single shared instance variables to prevent component multiplication in memory
    final JButton btnUpdate = new JButton();
    final JButton btnReinstall = new JButton();
    final JButton btnUninstall = new JButton();

    public ActionPanel() {
        super(new GridLayout(1, 3, 6, 0));
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // Connect your custom engine UI architecture once to enforce anti-aliasing globally
        btnUpdate.setUI(new com.sphere.ui.SPButtonUI());
        btnReinstall.setUI(new com.sphere.ui.SPButtonUI());
        btnUninstall.setUI(new com.sphere.ui.SPButtonUI());

        btnUpdate.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnReinstall.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnUninstall.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        add(btnUpdate);
        add(btnReinstall);
        add(btnUninstall);
    }

    /**
     * Mutates the state of the shared button instances dynamically based on row data context.
     */
    public void configureForCell(JTable table, int row, boolean isSelected) {
        setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());

        btnReinstall.setIcon(IconManager.getIcon("preinstall.png"));
        btnUninstall.setIcon(IconManager.getIcon("delete.png"));

        Object latestVal = table.getValueAt(row, 2);
        Object currentVal = table.getValueAt(row, 1);
        String latest = (latestVal != null) ? latestVal.toString() : "";
        String current = (currentVal != null) ? currentVal.toString() : "";
        boolean outdated = !latest.isEmpty() && !"Loading...".equals(latest) && !"N/A".equals(latest) && !latest.equals(current);

        btnUpdate.setIcon(IconManager.getIcon(outdated ? "rpupdate.png" : "bpupdate.png"));
        btnUpdate.setEnabled(outdated);

        // Update individual button tooltips cleanly
        btnUpdate.setToolTipText(outdated ? "Update package" : "Package is already up to date");
        btnReinstall.setToolTipText("Reinstall package");
        btnUninstall.setToolTipText("Uninstall package");
    }
}

        /* -------------------------------------------------------------------------
        * ACTION RENDERER (PASSIVE FLYWEIGHT RENDERING BUFFER)
        * -------------------------------------------------------------------------
        */
        class ActionRenderer extends ActionPanel implements javax.swing.table.TableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                configureForCell(table, row, isSelected);
                return this;
            }
        }

        /* -------------------------------------------------------------------------
        * ACTION EDITOR (INTERACTIVE MEMORY-EFFICIENT LAYER)
        * -------------------------------------------------------------------------
        */
        class ActionEditor extends AbstractCellEditor implements TableCellEditor {
            private final ActionPanel panel = new ActionPanel();
            private String moduleName;
            private final Runnable onRefresh;
            private final PyEnvManagerDialog parent;

            public ActionEditor(PyEnvManagerDialog parent, Runnable onRefresh) {
                this.parent = parent;
                this.onRefresh = onRefresh;

                // Route actions safely using the mutated context data parameters
                panel.btnUpdate.addActionListener(e -> execute(PipAction.UPDATE, "Updating " + moduleName));
                panel.btnReinstall.addActionListener(e -> execute(PipAction.REINSTALL, "Reinstalling " + moduleName));
                panel.btnUninstall.addActionListener(e -> execute(PipAction.UNINSTALL, "Uninstalling " + moduleName));
            }

            private void execute(PipAction action, String logMsg) {
                PythonEnvService service = parent.getEnvService();
                if (service == null) {
                    parent.logToUI("No valid Python environment configured.");
                    fireEditingStopped();
                    return;
                }
                parent.logToUI(logMsg + "...");

                new SwingWorker<Void, String>() {
                    private String workerError = null;
                    @Override protected Void doInBackground() {
                        try {
                            java.util.List<String> out = service.runPipAction(action, moduleName);
                            if (out != null) {
                                for (String s : out) publish(s);
                            }
                        } catch (Exception ex) {
                            workerError = ex.getMessage();
                        }
                        return null;
                    }
                    @Override protected void process(java.util.List<String> chunks) {
                        chunks.forEach(parent::logToUI);
                    }
                    @Override protected void done() {
                        if (workerError != null) {
                            parent.logToUI("Operation failed: " + workerError);
                        } else {
                            parent.logToUI("Operation finished successfully.");
                        }
                        SwingUtilities.invokeLater(onRefresh);
                    }
                }.execute();
                fireEditingStopped();
            }

            @Override
            public Component getTableCellEditorComponent(JTable t, Object v, boolean s, int r, int c) {
                Object nameVal = t.getValueAt(r, 0);
                this.moduleName = (nameVal != null) ? nameVal.toString() : "";
                panel.configureForCell(t, r, true);
                return panel;
            }

            @Override
            public Object getCellEditorValue() {
                return "Action";
            }
        }

        /* -------------------------------------------------------------------------
         * MODULE DATA HOLDER
         * -------------------------------------------------------------------------
         */
        class ModuleData {
            final List<JsonParser.ModuleInfo> modules;
            final List<JsonParser.ModuleInfo> outdated;
            final String check;

            ModuleData(List<JsonParser.ModuleInfo> modules,
                    List<JsonParser.ModuleInfo> outdated,
                    String check) {
                this.modules = modules;
                this.outdated = outdated;
                this.check = check;
            }
        }

        /**
         * Renderer for the "Latest" column.
         * Displays a VSCode-style animated loader while data is being fetched.
         */
        private class LatestColumnRenderer extends DefaultTableCellRenderer {

            // Shared loader instance (must be shared or animation will freeze)
            private final AnimLoader loader = new AnimLoader();

            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {

                // 1. Loading state → show animated loader
                if (tableModel.isLatestLoading() &&
                    (value == null || "Loading...".equals(value))) {

                    loader.setOpaque(true);
                    loader.setBackground(isSelected
                            ? table.getSelectionBackground()
                            : table.getBackground());

                    return loader;
                }

                // 2. Normal state → fallback to text rendering
                JLabel label = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                label.setHorizontalAlignment(SwingConstants.LEFT);

                Object currentVal = table.getValueAt(row, 1);
                String current = (currentVal != null) ? currentVal.toString() : "";
                String latest = (value == null || value.toString().equals("Loading..."))
                        ? "N/A"
                        : value.toString();

                label.setText(latest);

                // Determine if a newer package version exists
                boolean isOutdated =
                        !latest.equals("N/A") &&
                        !latest.equals("Loading...") &&
                        !latest.equals(current);

                // Apply custom styling for outdated packages
                if (isOutdated) {
                    label.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
                    label.setToolTipText("Update available");
                    
                    if (isSelected) {
                        // If selected, preserve the theme's selection colors but keep it bold
                        label.setBackground(table.getSelectionBackground());
                        label.setForeground(table.getSelectionForeground());
                    } else {
                        // Smooth UI styling
                        label.setOpaque(true);
                        label.setBackground(palette.getAmberBackground());
                        label.setForeground(palette.getAmberForeground());
                    }
                } else {
                    // Restore standard look-and-feel variables for up-to-date entries
                    label.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                    label.setToolTipText(null);
                    
                    if (isSelected) {
                        label.setBackground(table.getSelectionBackground());
                        label.setForeground(table.getSelectionForeground());
                    } else {
                        label.setBackground(table.getBackground());
                        label.setForeground(palette.getTextWhite());
                    }
                }

                return label;
            }
        }

        /* -------------------------------------------------------------------------
        * SAFE TASK GROUP (Anti-deadlock, Watchdog, Timeout)
        * ------------------------------------------------------------------------- */
        class SafeTaskGroup {

            private final AtomicInteger counter = new AtomicInteger(0);
            private final Runnable onAllFinished;
            private final java.util.concurrent.ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            private final long timeoutMs = 15000; // 15-second timeout for async tasks

            public SafeTaskGroup(Runnable onAllFinished) {
                this.onAllFinished = onAllFinished;

                // Watchdog: prints warnings to runtime app logs if tasks remain stuck
                watchdog.scheduleAtFixedRate(() -> {
                    int c = counter.get();
                    if (c > 0) {
                        AppLogger.warn("[WATCHDOG] TaskGroup stuck: " + c + " tasks still running.");
                    }
                }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
            }

            public void start() {
                counter.incrementAndGet();
            }

            public void finish() {
                if (counter.decrementAndGet() == 0) {
                    SwingUtilities.invokeLater(onAllFinished);
                }
            }

            public void shutdown() {
                watchdog.shutdownNow();
            }

            // Wraps a CompletableFuture with timeout + guaranteed finish()
            public <T> CompletableFuture<T> safe(CompletableFuture<T> future) {
                start();

                CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
                watchdog.schedule(() -> timeoutFuture.completeExceptionally(
                        new java.util.concurrent.TimeoutException("Asynchronous task timed out after " + timeoutMs + " ms")
                ), timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                return future.applyToEither(timeoutFuture, r -> r)
                        .handle((result, ex) -> {
                            finish();
                            if (ex != null) throw new java.util.concurrent.CompletionException(ex);
                            return result;
                        });
            }

            public boolean isBusy() {
                return counter.get() > 0;
            }
        }

        // UI watchdog to detect potential background freezes
        private final Timer uiWatchdog = new Timer(3000, e -> {
            if (safeTaskGroup != null && safeTaskGroup.isBusy()) {
                brokenArea.setText("Warning: Background tasks appear to be stuck.");
            }
        });

        {
            uiWatchdog.start();
        }

        /* -------------------------------------------------------------------------
         * SIMPLE DOCUMENT LISTENER
         * -------------------------------------------------------------------------
         */
        @FunctionalInterface
        public interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
            void update();

            @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        }
}
