package com.sphere;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.prefs.Preferences;
import java.util.concurrent.CompletableFuture;

import com.sphere.ui.ConsoleUI;
import com.sphere.ui.QuickCodeEditorFrame;
import com.sphere.fonts.FontLoader;
import com.sphere.components.FileExplorer;
import com.sphere.components.SnippetsPanel;
import com.sphere.utils.SessionManager;
import com.sphere.utils.SettingsManager;
import com.sphere.utils.StartupDiagnostic;
import com.sphere.utils.OSValidator;
import com.sphere.core.commandrouterincludes.HistoryManager;
import com.sphere.utils.AppLogger;
import com.sphere.core.CommandRouter;
import com.sphere.components.TerminalManager;
import com.sphere.components.WorkspaceManager;
import com.sphere.components.workspace.WorkspacePanel;
import com.sphere.components.PersistentSplitPane;
import com.sphere.utils.IconManager;
import com.sphere.core.EnvBackend;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.ui.WindowManager;
import com.sphere.ui.SPTabbedPaneUI;
import com.sphere.utils.EngineConfigRegistry;
import com.sphere.components.CppMetricsPanel;
import com.sphere.ui.GenericEnvManagerDialog;

import com.sphere.core.rootbackend.RootBackend;
import com.sphere.core.rootbackend.RootBridgeCompiler;

/**
 * Main Orchestrator and Frame Execution Environment for the Sphere HEP platform.
 */
public class Sphere extends JFrame {

    // UI components
    private ConsoleUI console;
    private JPanel statusBar;
    private JLabel statusModeLabel;
    private static java.util.function.Consumer<String> globalModeListener;
    private JLabel pathLabel;
    private JLabel modeIndicator;
    private JTextField commandInputField;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    private static RootBackend rootBackend;

    // Persistent workspace frame instance for editing text files internally
    private com.sphere.ui.QuickCodeEditorFrame editorFrame;

    // Layout components
    private JSplitPane leftVerticalSplit;
    private JSplitPane rightVerticalSplit;
    private JSplitPane mainSplit;

    // State
    private final Preferences prefs = Preferences.userNodeForPackage(Sphere.class);
    private final SessionManager session = new SessionManager("WorkStation");
    private final HistoryManager historyManager = new HistoryManager();
    private final CommandRouter router = new CommandRouter();
    private final SettingsManager settings = new SettingsManager();

    public Sphere() {
        // Shutdown hook - Ensures session logs close cleanly on application termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.close();
            AppLogger.info("Engine shutdown cleanly.");
        }));

        attachGlobalKeyInterceptor();
        setUIFont();
        initRouter();
        initWorkbench();

        com.sphere.utils.SecurityManager.initialize();
        
        // FIXED: Using class field to prevent local shadowing variable leak
        StartupDiagnostic.run(this.settings);
    }

    /**
     * Generates the platform runtime menu bar dynamically using the backend environment registry.
     * Segregates active hooks from unconfigured items using semantic theme coloring and italics in U.S. English.
     * Integrates a distinctive outline border to ensure visibility contrast against dark application frames.
     */
    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu envMenu = new JMenu("Environment Managers");
        
        envMenu.setForeground(java.awt.Color.WHITE);
        envMenu.setFont(com.sphere.fonts.FontLoader.getGlobalFont(java.awt.Font.PLAIN, 12));

        com.sphere.theme.ThemePalette palette = com.sphere.theme.ThemeManager.getCurrentPalette();
        
        // Apply a fine-line structural border to the popup menu container to separate it from the background layer
        JPopupMenu popupMenu = envMenu.getPopupMenu();
        popupMenu.setBorder(BorderFactory.createLineBorder(palette.getScrollBorder(), 1));
        popupMenu.setBackground(palette.getTerminalBackground()); // Ensure popup matching background context

        java.util.List<EnvBackend> activeBackends = new java.util.ArrayList<>();
        java.util.List<EnvBackend> inactiveBackends = new java.util.ArrayList<>();

        // 1. Sort backends based on settings.conf profile configurations
        for (EnvBackend backend : EnvBackend.values()) {
            String key = backend.getConfigKey();
            String path = this.settings.getProperty("SYSTEM_PATH", key);
            if (path == null || path.trim().isEmpty()) {
                path = this.settings.getProperty("GENERAL", key);
            }

            if (path != null && !path.trim().isEmpty()) {
                activeBackends.add(backend);
            } else {
                inactiveBackends.add(backend);
            }
        }

        // 2. Append Active Profiles (High Contrast - Primary / White text layout)
        for (EnvBackend backend : activeBackends) {
            JMenuItem item = new JMenuItem(backend.getDisplayName());
            item.setForeground(palette.getTextWhite()); 
            item.setBackground(palette.getTerminalBackground());
            item.setFont(com.sphere.fonts.FontLoader.getGlobalFont(java.awt.Font.PLAIN, 12));
            
            // Instantiates the environment telemetry log view explicitly
            item.addActionListener(e -> SwingUtilities.invokeLater(() -> 
                new GenericEnvManagerDialog(this, backend, this.settings).setVisible(true)
            ));
            envMenu.add(item);
        }

        // 3. Inject structural breakline boundary if both categories exist concurrently
        if (!activeBackends.isEmpty() && !inactiveBackends.isEmpty()) {
            envMenu.addSeparator();
        }

        // 4. Append Inactive Profiles (Low Contrast - Muted / Italicized Gray text layout via FontLoader)
        for (EnvBackend backend : inactiveBackends) {
            JMenuItem item = new JMenuItem(backend.getDisplayName() + " (Unset)");
            item.setForeground(palette.getLogPromptPrefix()); // Your neutral theme gray accent
            item.setBackground(palette.getTerminalBackground());
            
            // Seamlessly routes directly onto Inter-Italic.ttf (or its Linux equivalent)
            item.setFont(com.sphere.fonts.FontLoader.getGlobalFont(java.awt.Font.ITALIC, 12));
            
            // Bypasses WindowManager's automatic editor triggers, opening the standard diagnostic dialog instead
            item.addActionListener(e -> SwingUtilities.invokeLater(() -> 
                new GenericEnvManagerDialog(this, backend, this.settings).setVisible(true)
            ));
            envMenu.add(item);
        }

        menuBar.add(envMenu);
        setJMenuBar(menuBar);
    }

    /**
     * Initializes the command router and attaches modern UI state hooks.
     */
    private void initRouter() {
        // Mode indicator callback - Now synchronizes BOTH the prompt and the status bar
        router.setModeUpdater(modeText -> SwingUtilities.invokeLater(() -> {
            // 1. Update the little prompt indicator next to the input field
            if (modeIndicator != null) {
                modeIndicator.setText(modeText);
            }
            
            // 2. CRITICAL FIX: Directly update the persistent status bar text and color right here!
            if (statusModeLabel != null) {
                if (modeText == null || modeText.trim().isEmpty()) {
                    statusModeLabel.setText("Normal Mode");
                    statusModeLabel.setForeground(palette.getTextWhite());
                } else {
                    // Strips down tags like "[py]" or "[cpp]" to raw text strings safely
                    String cleanMode = modeText.toLowerCase().replaceAll("[\\[\\]]", "").trim();
                    
                    switch (cleanMode) {
                        case "py":
                            statusModeLabel.setText("Python Mode Activated");
                            statusModeLabel.setForeground(palette.getlockedmode()); // Amber/Orange
                            break;
                        case "cpp":
                            statusModeLabel.setText("C++ Mode Activated");
                            statusModeLabel.setForeground(palette.getlockedmode());
                            break;
                        case "js":
                            statusModeLabel.setText("JavaScript Mode Activated");
                            statusModeLabel.setForeground(palette.getlockedmode());
                            break;
                        default:
                            statusModeLabel.setText(cleanMode.toUpperCase() + " Mode Activated");
                            statusModeLabel.setForeground(palette.getlockedmode());
                            break;
                    }
                }
            }
        }));

        // Status bar path callback
        router.setStatusBarUpdater(newPath -> SwingUtilities.invokeLater(() -> {
            if (pathLabel != null && statusBar != null) {
                pathLabel.setText(newPath);
                pathLabel.setToolTipText(newPath);
                statusBar.revalidate();
            }
        }));

        // Plugin samples (no new files)
        router.registerPlugin(new CommandRouter.CommandPlugin() {
            @Override
            public String getName() {
                return "hep";
            }

            @Override
            public boolean supports(String input) {
                String lower = input.toLowerCase().trim();
                return lower.startsWith("hep ") || lower.startsWith("physics ");
            }

            @Override
            public void execute(String input) {
                AppLogger.info("[HEP] " + input);
            }
        });
    }

    private void initWorkbench() {
        initFrame();
        initMenuBar(); // Registered the central application menu bar routing loop
        this.editorFrame = new QuickCodeEditorFrame(null);

        JSplitPane leftPane = initLeftPane();
        JPanel consolePanel   = initConsole();
        JSplitPane rightPane = initRightPane();

        assembleMainLayout(leftPane, consolePanel, rightPane);
        initStatusBarPanel();
        attachWindowHooks();
    }

    private void initFrame() {
        setTitle("Sphere - HEP WorkStation");
        
        // Insert main workbench icon window target
        ImageIcon icon = (ImageIcon) IconManager.getIcon("cta_logo.png");
        if (icon != null) {
            setIconImage(icon.getImage());
        }

        setDefaultCloseOperation(EXIT_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.85), (int) (screenSize.height * 0.85));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
    }

    private JPanel initConsole() {
        // Instantiate or reference the core C++ diagnostics ingestion pipeline engine
        com.sphere.core.cpp.CppDiagnosticsEngine diagnosticsEngine = new com.sphere.core.cpp.CppDiagnosticsEngine();

        // Retrieve the central managed backend instance directly from the router with an explicit cast
        com.sphere.core.cpp.CppBackend cppBackend = (com.sphere.core.cpp.CppBackend) this.router.getCppBackend();

        // CONNECTIVE WIRING: Pre-stage the backend engine formatting pipeline with global user configs
        if (cppBackend != null) {
            cppBackend.initializeFormatter(this.settings);

            // Compiler findings reach the editor: the backend fills the engine, the
            // editor underlines whatever concerns the file it is showing.
            cppBackend.setDiagnosticsEngine(diagnosticsEngine);
            cppBackend.setDiagnosticsListener(source -> {
                if (this.editorFrame == null || this.editorFrame.getEditor() == null) {
                    return;
                }
                java.util.List<com.sphere.components.editor.EditorDiagnostic> found =
                    com.sphere.components.editor.DiagnosticsBridge.forFile(diagnosticsEngine, source);
                javax.swing.SwingUtilities.invokeLater(
                    () -> this.editorFrame.getEditor().showDiagnostics(source, found));
            });
        }

        // Pass the diagnostic engine, the shared backend instance, and the global settings context down to the console
        console = new ConsoleUI(this.session, this.editorFrame, diagnosticsEngine, cppBackend, this.settings);
        AppLogger.setLogTarget(this.console);
        AppLogger.setSession(this.session);

        commandInputField = new JTextField();
        modeIndicator = new JLabel("");
        modeIndicator.setForeground(palette.getlockedmode()); // Compiles perfectly with ThemePalette mappings
        modeIndicator.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        JPanel promptPanel = buildPromptPanel();
        attachCommandFieldKeyBindings();
        attachCommandExecutionHandler();

        JPanel bottomControls = new JPanel(new BorderLayout());
        bottomControls.add(new JSeparator(JSeparator.HORIZONTAL), BorderLayout.NORTH);
        bottomControls.add(promptPanel, BorderLayout.CENTER);

        JPanel consolePanel = new JPanel(new BorderLayout());
        consolePanel.add(this.console, BorderLayout.CENTER);
        consolePanel.add(bottomControls, BorderLayout.SOUTH);

        return consolePanel;
    }

    /**
     * Builds the CLI prompt panel.
     */
    private JPanel buildPromptPanel() {
        JPanel promptPanel = new JPanel(new BorderLayout(5, 0));

        // CRITICAL: Prevent Swing from using the TAB key to transfer focus 
        commandInputField.setFocusTraversalKeysEnabled(false);

        // FIX: Configure selection colors on the command input field to match the dark theme palette.
        commandInputField.setSelectionColor(palette.getTerminalSelection());
        commandInputField.setSelectedTextColor(palette.getTextWhite());
        commandInputField.setCaretColor(palette.getAccent()); // Enhances caret visibility

        // FIX: Use GridBagLayout instead of FlowLayout to force absolute vertical centering.
        JPanel leftPrompt = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER; // Hard centers all elements vertically
        gbc.insets = new java.awt.Insets(0, 0, 0, 0);

        JLabel cliLabel = new JLabel(" CLI ");
        cliLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        
        JLabel arrowLabel = new JLabel(" >");
        arrowLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        // Add components sequentially on the same horizontal row (gridy = 0)
        gbc.gridx = 0;
        leftPrompt.add(modeIndicator, gbc);
        
        gbc.gridx = 1;
        leftPrompt.add(cliLabel, gbc);
        
        gbc.gridx = 2;
        leftPrompt.add(arrowLabel, gbc);

        // FIXED: Removed redundant, conflicting InputMap/ActionMap blocks. 
        // The centralized global KeyEventDispatcher handles TAB safely.

        promptPanel.add(leftPrompt, BorderLayout.WEST);
        promptPanel.add(commandInputField, BorderLayout.CENTER);
        promptPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        return promptPanel;
    }

    private JSplitPane initLeftPane() {
        // --- Top Tabs (File Explorer) ---
        JTabbedPane topTabs = new JTabbedPane();
        topTabs.setUI(new SPTabbedPaneUI());
        topTabs.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        
        // Pass the valid shared editor frame reference to resolve the runtime missing context error
        topTabs.addTab("File Explorer", new JScrollPane(new FileExplorer(this.editorFrame)));

        // --- Bottom Tabs (Workspace, Performance Metrics, Terminal) ---
        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.setUI(new SPTabbedPaneUI());
        bottomTabs.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        
        WorkspaceManager workspaceManager = new WorkspaceManager();
        WorkspacePanel workspacePanel = new WorkspacePanel(workspaceManager);
        bottomTabs.addTab("WorkSpace", workspacePanel);
        
        // NEW: Replacing the empty placeholder with the C++ Engine Metrics display panel
        // (Assuming you pass the backend instance or registry containing the metrics)
        bottomTabs.addTab("C++ Metrics", new CppMetricsPanel(this.router.getCppBackend()));

        // --- Terminal Setup ---
        TerminalManager terminalManager = new TerminalManager();
        Component terminalComponent = terminalManager.getTabbedPane();

        // Set the minimum size to prevent the 1/4 screen collapse issue
        terminalComponent.setMinimumSize(new Dimension(200, 100));

        // Determine default system shell framework
        String defaultShell = System.getProperty("os.name").toLowerCase().contains("win")
                ? "cmd.exe"
                : "/bin/bash";

        terminalManager.newTerminal(defaultShell);
        bottomTabs.addTab("Terminal", terminalComponent);

        leftVerticalSplit = new PersistentSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                topTabs,
                bottomTabs,
                prefs,
                "leftVert",
                400
        );
        leftVerticalSplit.setResizeWeight(0.6);

        return leftVerticalSplit;
    }

    /**
     * Initializes the right panel layout workspace containing variable inspectors,
     * physics utilities, plotting controls, and the internal snippet management hub.
     */
    private JSplitPane initRightPane() {
        JTabbedPane topRightTabs = new JTabbedPane();
        topRightTabs.setUI(new SPTabbedPaneUI());
        topRightTabs.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        
        topRightTabs.addTab("Variables", new JPanel());
        topRightTabs.addTab("Physics", new JPanel());
        topRightTabs.addTab("Plots", new JPanel());

        JTabbedPane bottomRightTabs = new JTabbedPane();
        bottomRightTabs.setUI(new SPTabbedPaneUI());
        bottomRightTabs.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        
        // Injecting the shared editor frame reference context into the Snippets panel constructor
        bottomRightTabs.addTab("Snippets", new SnippetsPanel(this.commandInputField, this.editorFrame));

        rightVerticalSplit = new PersistentSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                topRightTabs,
                bottomRightTabs,
                prefs,
                "rightVert",
                400
        );

        return rightVerticalSplit;
    }

    private void assembleMainLayout(JSplitPane leftPane, JComponent consolePanel, JSplitPane rightPane) {
        JSplitPane centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, consolePanel, rightPane);
        centerRightSplit.setBorder(null);
        centerRightSplit.setResizeWeight(0.75);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, centerRightSplit);
        mainSplit.setBorder(null);
        mainSplit.setResizeWeight(0.25);

        leftPane.setMinimumSize(new Dimension(120, 200));
        rightPane.setMinimumSize(new Dimension(120, 200));
        consolePanel.setMinimumSize(new Dimension(200, 200));

        SwingUtilities.invokeLater(() -> {
            mainSplit.setDividerLocation(0.23);
            centerRightSplit.setDividerLocation(0.85);
        });
        add(mainSplit, BorderLayout.CENTER);
    }

    /**
     * Builds the CLI prompt status bar panel.
     */
    private void initStatusBarPanel() {
        statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusBar.setBorder(BorderFactory.createEtchedBorder());

        pathLabel = new JLabel(System.getProperty("user.dir"));
        pathLabel.setForeground(palette.getTextLightGray());
        pathLabel.setToolTipText(System.getProperty("user.dir"));

        JLabel iconLabel = new JLabel(IconManager.getIcon("sfolder.png"));

        // Initialize the persistent mode label with default layout metrics
        statusModeLabel = new JLabel("Normal Mode");
        statusModeLabel.setForeground(palette.getTextWhite());
        statusModeLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        JLabel separatorLabel = new JLabel(" | ");
        separatorLabel.setForeground(palette.getTextWhite());

        // Standard sequence assembly
        statusBar.add(statusModeLabel);
        statusBar.add(separatorLabel);
        statusBar.add(iconLabel);
        statusBar.add(pathLabel);

        add(statusBar, BorderLayout.SOUTH);

        // FIX: Assign the local UI update logic to a global static hook
        globalModeListener = (String indicator) -> updateStatusBarMode(indicator);
    }

    /**
     * Exposes the active mode hook safely to external execution threads.
     */
    public static void assignGlobalIndicator(String indicator) {
        if (globalModeListener != null) {
            globalModeListener.accept(indicator);
        }
    }

    /**
     * Dynamically updates the status bar text and color based on the current execution engine mode.
     */
    private void updateStatusBarMode(String indicator) {
        SwingUtilities.invokeLater(() -> {
            if (indicator == null || indicator.trim().isEmpty()) {
                statusModeLabel.setText("Normal Mode");
                statusModeLabel.setForeground(palette.getTextWhite());
                return;
            }

            // Remove brackets for structural string validation matching
            String cleanMode = indicator.toLowerCase().replaceAll("[\\[\\]]", "").trim();
            
            switch (cleanMode) {
                case "py":
                    statusModeLabel.setText("Python Mode Activated");
                    statusModeLabel.setForeground(palette.getlockedmode()); // Amber/Orange token mapping
                    break;
                case "cpp":
                    statusModeLabel.setText("C++ Mode Activated");
                    statusModeLabel.setForeground(palette.getlockedmode());
                    break;
                case "js":
                    statusModeLabel.setText("JavaScript Mode Activated");
                    statusModeLabel.setForeground(palette.getlockedmode());
                    break;
                default:
                    statusModeLabel.setText(cleanMode.toUpperCase() + " Mode Activated");
                    statusModeLabel.setForeground(palette.getlockedmode());
                    break;
            }
        });
    }

    private void attachWindowHooks() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Ensure internal text editor resource allocations cleanly wind down
                if (editorFrame != null) {
                    editorFrame.dispose();
                }
                session.close();
            }
        });
    }

    /* -------------------------------------------------------------------------
     * Command Field Interceptors
     * ------------------------------------------------------------------------- */
    private void attachCommandFieldKeyBindings() {
        commandInputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {

                // Multi-line input processing: Shift+Enter inserts newline
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isShiftDown()) {
                    String text = commandInputField.getText();
                    int pos = commandInputField.getCaretPosition();
                    commandInputField.setText(text.substring(0, pos) + "\n" + text.substring(pos));
                    commandInputField.setCaretPosition(pos + 1);
                    e.consume();
                    return;
                }

                // History navigation tracking: Up/Down
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    commandInputField.setText(historyManager.previous());
                    e.consume();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    commandInputField.setText(historyManager.next());
                    e.consume();
                    return;
                }

                // Inline history regex search constraints: Ctrl+R
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_R) {
                    String pattern = commandInputField.getText();
                    commandInputField.setText(historyManager.search(pattern));
                    e.consume();
                }
                
                // FIXED: Removed the conflicting, unconsumed manual TAB block from the raw key listener.
            }
        });
    }

    private void attachCommandExecutionHandler() {
        commandInputField.addActionListener(e -> {
            String input = commandInputField.getText();
            if (input == null || input.isEmpty()) return;

            AppLogger.recall(input);
            historyManager.add(input);
            historyManager.save();

            commandInputField.setText("");

            // Synchronous background context streaming path avoids UI thread freezes
            CompletableFuture.runAsync(() -> {
                try {
                    router.processInput(input);
                } catch (Exception ex) {
                    AppLogger.error("Failed to execute command: " + ex.getMessage());
                }
            });
        });
    }

    /**
     * Attaches a global key dispatcher to capture the TAB key.
     * This bypasses focus traversal and captures the event before any other component.
     */
    private void attachGlobalKeyInterceptor() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_TAB) {
                    if (commandInputField.hasFocus()) {
                        e.consume(); // Intercept and block focus migration completely

                        String text = commandInputField.getText();
                        String suggestion = router.autoComplete(text);
                        if (suggestion != null) {
                            SwingUtilities.invokeLater(() -> commandInputField.setText(suggestion));
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /* -------------------------------------------------------------------------
     * Structural Font Metrics Override
     * ------------------------------------------------------------------------- */
    private void setUIFont() {
        Font globalEngineFont = FontLoader.getGlobalFont(Font.PLAIN, 12);

        String[] uiKeys = {
            "Label.font", "Button.font", "TextField.font", "TextArea.font",
            "Tree.font", "List.font", "TabbedPane.font", "MenuItem.font",
            "RadioButton.font"
        };
        
        for (String key : uiKeys) {
            UIManager.put(key, globalEngineFont);
        }
    }

    /* -------------------------------------------------------------------------
     * Core Main Application Entry Point
     * ------------------------------------------------------------------------- */
    public static void main(String[] args) {
        String os = System.getProperty("os.name").toLowerCase();

        // 1. Conditionally isolate Linux/WSL font anti-aliasing pipelines
        if (os.contains("linux")) {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("awt.font.desktophints", "true");
            System.setProperty("swing.aatext", "true");
            System.setProperty("sun.java2d.xrender", "true");
        }

        // 2. Apply macOS native integrations
        if (os.contains("mac")) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "Sphere");
        }
        
        // 3. Force theme parameters onto the system thread before any UI components load
        try {
            ThemeManager.applyDarkTheme();
        } catch (Exception e) {
            System.err.println("Theme application failed: " + e.getMessage());
        }

        // 4. Instantiate settings and synchronize configuration registry
        com.sphere.utils.SettingsManager settings = null;
        try {
            settings = new com.sphere.utils.SettingsManager();
            com.sphere.utils.EngineConfigRegistry.synchronize(settings);
        } catch (Exception e) {
            System.err.println("Settings initialization failed: " + e.getMessage());
        }

        // 5. Initialize the ROOT backend (Completely silent unless configuration is active and fails)
        if (settings != null) {
            String rootDir = settings.getProperty("ROOT_DIR");
            if (rootDir != null && !rootDir.trim().isEmpty()) {
                try {
                    String binaryPath = com.sphere.core.rootbackend.RootBridgeCompiler.getOrCompileBridge(settings);
                    
                    if (binaryPath != null) {
                        rootBackend = new com.sphere.core.rootbackend.RootBackend(binaryPath);
                        rootBackend.initialize();
                        
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            if (rootBackend != null) {
                                rootBackend.close();
                            }
                        }));
                    }
                } catch (Exception e) {
                    com.sphere.utils.AppLogger.error("Failed to start ROOT backend: " + e.getMessage());
                }
            }
        }

        // 6. Safely instantiate the GUI layout tree on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            Sphere frame = new Sphere();
            frame.setVisible(true);
        });
    }
}
