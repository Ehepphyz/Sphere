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
    /** Shows next to the prompt that a block is open and waiting. */
    private static java.util.function.Consumer<Boolean> globalPendingListener;
    private JLabel pathLabel;
    private JLabel modeIndicator;
    private JTextField commandInputField;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    private static RootBackend rootBackend;

    // Persistent workspace frame instance for editing text files internally
    private com.sphere.ui.QuickCodeEditorFrame editorFrame;
    private static com.sphere.ui.QuickCodeEditorFrame activeEditorFrame;

    // Layout components
    private JSplitPane leftVerticalSplit;
    private JSplitPane rightVerticalSplit;
    private JSplitPane mainSplit;
    private JSplitPane centerRightSplit;

    /** The window a console command reports on. */
    private static Sphere activeWindow;

    /** The free area of the screen, kept so the window can be refitted to it. */
    private Rectangle freeArea;

    /** How many times the window has been resized to reach its intended inside. */
    private int fitAttempts;

    /** True once Sphere has finished sizing itself, so a resize is the user's. */
    private boolean settled;

    /** Waits for the dragging to stop before writing, rather than at every pixel. */
    private javax.swing.Timer resizeWriter;

    // State
    private final Preferences prefs = Preferences.userNodeForPackage(Sphere.class);

    /** How many times the tree is laid out before it is shown, one per nested pane. */
    private static final int LAYOUT_PASSES = 4;

    /** How many pixels off the intended inside is close enough to leave alone. */
    private static final int TOLERANCE = 2;

    /** A window manager may refuse to grow the window; this stops the asking. */
    private static final int MAX_FIT_ATTEMPTS = 4;

    /** How much of the free screen area the window takes, when settings.conf is silent. */
    private static final double DEFAULT_WINDOW_SHARE = 0.95;

    /** The width both side panels open on, when settings.conf is silent. */
    private static final int DEFAULT_SIDE_PANEL_WIDTH = 300;

    /** What one screen is worth differs from desk to desk, so both are settings. */
    private static double windowShare = DEFAULT_WINDOW_SHARE;
    private static int sidePanelWidth = DEFAULT_SIDE_PANEL_WIDTH;

    private final SessionManager session = new SessionManager("WorkStation");
    private final HistoryManager historyManager = new HistoryManager();
    private final CommandRouter router = new CommandRouter();
    private final SettingsManager settings = new SettingsManager();
    /** Kept so the shutdown hook can stop the shells it started. */
    private TerminalManager terminals;

    public Sphere() {
        // Shutdown hook - Ensures session logs close cleanly on application termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // The JVM leaves its children behind: without this, the shells, clangd
            // and the Python kernel keep running after Sphere is closed.
            releaseChildProcesses();
            session.close();
            AppLogger.info("Engine shutdown cleanly.");
        }));

        attachGlobalKeyInterceptor();

        // A window that stops answering says where it stopped, instead of
        // leaving nothing to look at.
        com.sphere.core.EdtWatchdog.start();
        setUIFont();
        initRouter();
        initWorkbench();

        // Both of these take seconds: the whitelist asks pip what is installed,
        // and the diagnostics probe the toolchain and may compile the C++ bridge.
        // This constructor runs on the event thread, so doing either here froze
        // the window before it could be used.
        Thread startup = new Thread(() -> {
            com.sphere.utils.SecurityManager.initialize();
            StartupDiagnostic.run(this.settings);
        }, "sphere-startup");
        startup.setDaemon(true);
        startup.start();
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
        activeEditorFrame = this.editorFrame;

        activeWindow = this;

        JSplitPane leftPane = initLeftPane();
        JPanel consolePanel   = initConsole();
        JSplitPane rightPane = initRightPane();

        assembleMainLayout(leftPane, consolePanel, rightPane);
        initStatusBarPanel();
        attachWindowHooks();
    }

    /**
     * Writes one layout setting back into [LAYOUT_SIZE].
     *
     * What is adjusted by hand has to survive the next launch, otherwise the
     * adjustment is to be made again every morning.
     */
    private static void writeLayoutSetting(String key, String value) {
        try {
            com.sphere.utils.SettingsManager settings = new com.sphere.utils.SettingsManager();
            settings.setProperty("LAYOUT_SIZE", key, value);
            settings.save();
        } catch (RuntimeException unwritable) {
            AppLogger.error("Could not write " + key + " to settings.conf: "
                            + unwritable.getMessage());
        }
    }

    /** The width the user gave the side panels, kept for the next launch. */
    private static void rememberSidePanelWidth(int width) {
        if (width == sidePanelWidth) {
            return;
        }
        sidePanelWidth = width;
        writeLayoutSetting("SIDE_PANEL_WIDTH", Integer.toString(width));
    }

    /** The share of the screen the user gave the window, once the dragging stops. */
    private void rememberWindowShare() {
        if (freeArea == null || freeArea.width <= 0 || getContentPane().getWidth() <= 0) {
            return;
        }
        final double share = getContentPane().getWidth() / (double) freeArea.width;
        if (share < 0.3 || share > 1.0
            || Math.abs(share - windowShare) < 0.005) {
            return;
        }
        windowShare = share;
        writeLayoutSetting("WINDOW_SHARE",
                           String.format(java.util.Locale.ROOT, "%.3f", share));
    }

    /** A resize is answered once it has stopped, not at every pixel of the drag. */
    private void scheduleWindowShareWrite() {
        if (!settled) {
            return;
        }
        if (resizeWriter == null) {
            resizeWriter = new javax.swing.Timer(800, event -> rememberWindowShare());
            resizeWriter.setRepeats(false);
        }
        resizeWriter.restart();
    }

    /** Called once Sphere has stopped sizing itself. */
    void layoutSettled() {
        settled = true;
    }

    /**
     * Reads the two layout settings from [LAYOUT_SIZE] in settings.conf.
     *
     * A value per machine rather than one per family of systems: what a desktop
     * reserves, and how it scales, differs from one machine to the next even under
     * the same name. A value outside what makes sense is reported and ignored.
     */
    private static void readLayoutSettings() {
        try {
            com.sphere.utils.SettingsManager settings = new com.sphere.utils.SettingsManager();
            final String share = settings.getProperty("LAYOUT_SIZE", "WINDOW_SHARE");
            if (share != null && !share.isBlank()) {
                final double asked = Double.parseDouble(share.trim());
                if (asked >= 0.3 && asked <= 1.0) {
                    windowShare = asked;
                } else {
                    AppLogger.error("WINDOW_SHARE must be between 0.3 and 1.0.");
                }
            }
            final String width = settings.getProperty("LAYOUT_SIZE", "SIDE_PANEL_WIDTH");
            if (width != null && !width.isBlank()) {
                final int asked = Integer.parseInt(width.trim());
                if (asked >= 100 && asked <= 1000) {
                    sidePanelWidth = asked;
                } else {
                    AppLogger.error("SIDE_PANEL_WIDTH must be between 100 and 1000 pixels.");
                }
            }
        } catch (NumberFormatException notANumber) {
            AppLogger.error("WINDOW_SHARE or SIDE_PANEL_WIDTH is not a number.");
        } catch (RuntimeException unreadable) {
            // settings.conf is unreadable; the values written above stand.
        }
    }

    private void initFrame() {
        readLayoutSettings();
        setTitle("Sphere - HEP WorkStation");
        
        // Every size at once: the window manager picks, instead of shrinking a
        // single 256 pixel image down to 16.
        IconManager.applyAppIcon(this);

        setDefaultCloseOperation(EXIT_ON_CLOSE);

        // The free area of the screen the window opens on, taskbar or dock taken
        // out. getMaximumWindowBounds() is meant to say this, but several Linux
        // desktops and macOS hand back the whole screen, so the reserved edges are
        // asked for and subtracted here instead.
        GraphicsConfiguration screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                          .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = screen.getBounds();
        java.awt.Insets reserved = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        Rectangle byInsets = new Rectangle(bounds.x + reserved.left, bounds.y + reserved.top,
                                           bounds.width - reserved.left - reserved.right,
                                           bounds.height - reserved.top - reserved.bottom);
        // Neither source is reliable everywhere: the reserved edges come back empty
        // on several desktops, and the maximum bounds come back as the whole screen
        // on others. What both agree on is free on all of them.
        Rectangle byMaximum = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                                 .getMaximumWindowBounds();
        freeArea = byInsets.intersection(byMaximum);
        if (freeArea.width < 400 || freeArea.height < 300) {
            freeArea = byInsets;
        }
        setSize((int) (freeArea.width * windowShare), (int) (freeArea.height * windowShare));
        centreInFreeArea();
        setLayout(new BorderLayout());
    }

    /**
     * Grows the window so that its inside, and not its outside, gets the intended
     * size.
     *
     * A window carries its title bar and its borders on top of what it shows, and
     * those are thin on Windows and thick on several Linux desktops and on macOS.
     * Sizing the window alone therefore gave a smaller working area on those
     * systems for the same numbers. The decorations are only measurable once the
     * window has been given to the system, which is why this is called then.
     */
    void fitInsideToScreen() {
        if (freeArea == null || fitAttempts >= MAX_FIT_ATTEMPTS) {
            return;
        }
        final java.awt.Container inside = getContentPane();
        final int haveWide = inside.getWidth();
        final int haveHigh = inside.getHeight();
        if (haveWide <= 0 || haveHigh <= 0) {
            return;
        }
        final int wantWide = (int) (freeArea.width * windowShare);
        final int wantHigh = (int) (freeArea.height * windowShare);
        final int growWide = wantWide - haveWide;
        final int growHigh = wantHigh - haveHigh;
        if (Math.abs(growWide) <= TOLERANCE && Math.abs(growHigh) <= TOLERANCE) {
            return;
        }
        fitAttempts++;
        setSize(Math.min(getWidth() + growWide, freeArea.width),
                Math.min(getHeight() + growHigh, freeArea.height));
        centreInFreeArea();
    }

    /** Centred in the free area: centring on the screen slides it under a top bar. */
    private void centreInFreeArea() {
        if (freeArea == null) {
            setLocationRelativeTo(null);
            return;
        }
        setLocation(freeArea.x + (freeArea.width - getWidth()) / 2,
                    freeArea.y + (freeArea.height - getHeight()) / 2);
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

        // Cut, copy and paste of its own: the ones the look and feel installs go
        // to the clipboard from the event thread, and freeze the window whenever
        // another program is holding it.
        com.sphere.components.ClipboardBridge.install(commandInputField);

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
        this.terminals = terminalManager;
        Component terminalComponent = terminalManager.getTabbedPane();

        // Set the minimum size to prevent the 1/4 screen collapse issue
        terminalComponent.setMinimumSize(new Dimension(200, 100));

        // Determine default system shell framework
        terminalManager.newTerminal(com.sphere.components.terminal.ShellSelector.defaultShell(settings));
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
        
        com.sphere.components.variables.VariablesPanel variables =
            com.sphere.components.variables.VariablesPanel.instance();
        topRightTabs.addTab("Variables", variables);
        variables.setReveal(() -> topRightTabs.setSelectedComponent(variables));
        topRightTabs.addTab("Physics", new JPanel());
        com.sphere.components.rootview.RootPlotsPanel plots =
            com.sphere.components.rootview.RootPlotsPanel.instance();
        topRightTabs.addTab("Plots", plots);
        plots.setReveal(() -> topRightTabs.setSelectedComponent(plots));

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
        // Both dividers are persistent: the shares are only what an installation
        // that has never been adjusted opens on. Asking for a proportion here used
        // to be undone by the layout that followed setVisible, which is why the
        // panel beside the console had to be dragged at every launch.
        // The right panel is given the width of the left one, and the console takes
        // what is left. Said this way the two sides stay equal whatever the window
        // does, without a second proportion to keep in step with the first.
        centerRightSplit = new com.sphere.components.PersistentSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, consolePanel, rightPane,
                prefs, "centerRightSplit",
                span -> span - centerRightSplit.getDividerSize()
                             - mainSplit.getDividerLocation());
        centerRightSplit.setBorder(null);
        // All of a width change goes to the console: the right panel keeps the
        // width of the left one, so it has no share to take. Weights that disagreed
        // with the intended widths were what moved the dividers after they had been
        // placed, and that correction is what was seen jumping.
        centerRightSplit.setResizeWeight(1.0);

        mainSplit = new com.sphere.components.PersistentSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftPane, centerRightSplit,
                prefs, "mainSplit", sidePanelWidth);
        ((com.sphere.components.PersistentSplitPane) mainSplit)
            .setOnUserMoved(Sphere::rememberSidePanelWidth);
        mainSplit.setBorder(null);
        // The side panels keep their width; the console takes every extra pixel.
        mainSplit.setResizeWeight(0.0);

        leftPane.setMinimumSize(new Dimension(120, 200));
        rightPane.setMinimumSize(new Dimension(120, 200));
        consolePanel.setMinimumSize(new Dimension(200, 200));

        // The two side panels are asked for the same width before anything is laid
        // out. A split pane honors the preferred width of its children on the very
        // first pass, so the dividers land there straight away instead of being put
        // somewhere else and moved afterwards, in full view.
        leftPane.setPreferredSize(new Dimension(sidePanelWidth, 200));
        rightPane.setPreferredSize(new Dimension(sidePanelWidth, 200));

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
        globalPendingListener = (Boolean pending) -> SwingUtilities.invokeLater(() -> {
            if (modeIndicator == null) {
                return;
            }
            final String shown = modeIndicator.getText().replace(" ...", "");
            modeIndicator.setText(Boolean.TRUE.equals(pending) ? shown + " ..." : shown);
        });
    }

    /**
     * Exposes the active mode hook safely to external execution threads.
     */
    /** The editor window, so a console command can open a file in it. */
    public static com.sphere.ui.QuickCodeEditorFrame editorWindow() {
        return activeEditorFrame;
    }

    public static void assignGlobalIndicator(String indicator) {
        if (globalModeListener != null) {
            globalModeListener.accept(indicator);
        }
    }

    /** Marks, next to the prompt, that Enter is waiting for the block to close. */
    /**
     * What the three columns actually measure, and where those numbers came
     * from. An interface that opens wrong looks the same whatever the reason,
     * so this says which one it is rather than leaving it to be guessed.
     */
    public static String layoutReport() {
        Sphere window = activeWindow;
        if (window == null || window.mainSplit == null || window.centerRightSplit == null) {
            return "The workbench is not built yet.";
        }
        final int total = window.getWidth();
        final int left = window.mainSplit.getDividerLocation();
        final int console = window.centerRightSplit.getDividerLocation();
        // The divider sits between the two, so its thickness belongs to neither.
        final int right = window.centerRightSplit.getWidth() - console
                        - window.centerRightSplit.getDividerSize();
        StringBuilder said = new StringBuilder("Layout");
        final java.awt.Insets frame = window.getInsets();
        final java.awt.Container inside = window.getContentPane();
        said.append(String.format("%n  screen free   %5d x %d",
            window.freeArea == null ? 0 : window.freeArea.width,
            window.freeArea == null ? 0 : window.freeArea.height));
        said.append(String.format("%n  window        %5d x %d", total, window.getHeight()));
        said.append(String.format("%n  lost to frame %5d x %d   (measured)",
            total - inside.getWidth(), window.getHeight() - inside.getHeight()));
        said.append(String.format("%n  system claims %5d x %d   (left %d right %d top %d bottom %d)",
            frame.left + frame.right, frame.top + frame.bottom,
            frame.left, frame.right, frame.top, frame.bottom));
        said.append(String.format("%n  drawing area  %5d x %d   <- what you actually see",
            inside.getWidth(), inside.getHeight()));
        final int drawn = Math.max(inside.getWidth(), 1);
        said.append(String.format("%n  left panel    %5d  (%3.0f%%)", left, pc(left, drawn)));
        said.append(String.format("%n  console       %5d  (%3.0f%%)", console, pc(console, drawn)));
        said.append(String.format("%n  right panel   %5d  (%3.0f%%)", right, pc(right, drawn)));
        said.append(String.format("%n  left - right  %5d px apart", Math.abs(left - right)));
        for (JSplitPane pane : new JSplitPane[] { window.mainSplit, window.centerRightSplit,
                                                  window.leftVerticalSplit, window.rightVerticalSplit }) {
            if (pane instanceof com.sphere.components.PersistentSplitPane kept) {
                said.append("\n  ").append(kept.state());
            }
        }
        return said.toString();
    }

    private static double pc(int part, int whole) {
        return whole > 0 ? 100.0 * part / whole : 0;
    }

    public static void assignPendingMarker(boolean pending) {
        if (globalPendingListener != null) {
            globalPendingListener.accept(pending);
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

    /** Stops everything Sphere started, so nothing survives the window closing. */
    private void releaseChildProcesses() {
        try {
            if (terminals != null) {
                terminals.shutdownAllTerminals();
            }
        } catch (Exception ex) {
            AppLogger.error("Terminals did not stop cleanly: " + ex.getMessage());
        }
        try {
            if (router != null && router.getCppBackend()
                    instanceof com.sphere.core.cpp.CppBackend cpp) {
                cpp.getIntellisenseBackend().stop();
            }
        } catch (Exception ex) {
            AppLogger.error("clangd did not stop cleanly: " + ex.getMessage());
        }
    }

    private void attachWindowHooks() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                scheduleWindowShareWrite();
            }
        });

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

                // Escape drops the block being written, and only that: a few
                // lines, never a whole session's work.
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    commandInputField.setText("");
                    router.abandonPendingBlock();
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
        // Before anything else, so that whatever fails below is said rather than
        // written to a terminal that may not exist.
        com.sphere.core.JavaErrors.install();

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
        
        // 3. Claim the icon before any window exists, so the dock entry and every
        // window Sphere opens carry it rather than the Java default.
        com.sphere.utils.IconManager.installApplicationIcon();

        // 4. Force theme parameters onto the system thread before any UI components load
        try {
            ThemeManager.applyDarkTheme();
        } catch (Exception e) {
            com.sphere.utils.AppLogger.error("Theme application failed: " + e.getMessage());
        }

        // The jar manifest has the JVM show a picture before the first class of
        // Sphere is loaded. Here only because the properties above must be set
        // before the toolkit starts, and a window of our own would start it.
        com.sphere.core.Splash.begin();
        com.sphere.core.Splash.step("Reading the settings");

        // 5. Instantiate settings and synchronize configuration registry
        com.sphere.utils.SettingsManager settings = null;
        try {
            settings = new com.sphere.utils.SettingsManager();
            com.sphere.utils.EngineConfigRegistry.synchronize(settings);
        } catch (Exception e) {
            com.sphere.utils.AppLogger.error("Settings initialization failed: " + e.getMessage());
        }

        com.sphere.core.Splash.step("Starting the ROOT bridge");

        // 6. Initialize the ROOT backend (Completely silent unless configuration is active and fails)
        // The same sequence is reachable from the console menu, so it lives in
        // RootBackend.startShared rather than being written out twice.
        if (settings != null && !settings.isDeclaredEmpty("ROOT_DIR")
                && settings.getProperty("ROOT_DIR") != null) {
            try {
                rootBackend = com.sphere.core.rootbackend.RootBackend.startShared(settings);
                Runtime.getRuntime().addShutdownHook(
                    new Thread(com.sphere.core.rootbackend.RootBackend::stopShared));
            } catch (Exception e) {
                com.sphere.utils.AppLogger.error("Failed to start ROOT backend: " + e.getMessage());
            }
        }

        com.sphere.core.Splash.step("Building the interface");

        // 7. Safely instantiate the GUI layout tree on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            // The whole first display blocks the event thread for as long as the
            // machine needs: building the tree, parsing the icons, then the render
            // pipeline itself. That is not a freeze to report, and the stretch has
            // to start here -- the constructor is part of it.
            com.sphere.core.EdtWatchdog.expectBusy(true);
            try {
                Sphere frame = new Sphere();
                // Laid out before being shown, so the dividers are in place on the
                // first picture. Several passes because the panes settle one level at
                // a time: the inner one only knows its width once the outer one has
                // taken its own.
                frame.addNotify();
                frame.fitInsideToScreen();
                for (int pass = 0; pass < LAYOUT_PASSES; pass++) {
                    // invalidate() before each pass: moving a divider asks for a new
                    // layout through the repaint manager, which answers later, so a
                    // plain validate() would find the tree already valid and skip the
                    // pass that was needed.
                    frame.invalidate();
                    frame.validate();
                }
                frame.setVisible(true);
                com.sphere.core.Splash.done();
                // X11 and macOS only decorate the window when it reaches the screen,
                // so its borders measure zero until now and the fit made earlier was
                // made on nothing. Windows creates them sooner, which is why only
                // those two systems opened smaller.
                // Measured rather than calculated: the window is grown, laid out,
                // measured again, until what it draws matches what was asked for.
                for (int attempt = 0; attempt < MAX_FIT_ATTEMPTS; attempt++) {
                    frame.fitInsideToScreen();
                    frame.invalidate();
                    frame.validate();
                }
                // The settle pass runs after this block, so it closes the stretch
                // itself; ending it here would leave the last layout unprotected.
                SwingUtilities.invokeLater(() -> {
                    try {
                        frame.fitInsideToScreen();
                        frame.layoutSettled();
                    } finally {
                        com.sphere.core.EdtWatchdog.expectBusy(false);
                    }
                });
            } catch (RuntimeException | Error startupFailed) {
                com.sphere.core.EdtWatchdog.expectBusy(false);
                throw startupFailed;
            }
        });
    }
}
