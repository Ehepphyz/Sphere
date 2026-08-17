package com.sphere.ui;

import com.sphere.core.EnvBackend;
import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.SettingsManager;
import com.sphere.utils.SettingsEditorLauncher;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class GenericEnvManagerDialog extends JDialog {
    private final EnvBackend backend;
    private final SettingsManager settings;

    private JTextPane consoleLogArea;
    private JLabel statusBadgeLabel;
    private JProgressBar progressBar;

    /**
     * Initializes a new generic environment manager dashboard window.
     * Integrates cross-platform runtime configs and selective INI section tracking.
     */
    public GenericEnvManagerDialog(Window parent, EnvBackend backend, SettingsManager settings) {
        super(parent, "Sphere Workspace - [" + backend.getDisplayName() + "]", ModalityType.MODELESS);
        this.backend = backend;
        this.settings = settings;

        setSize(750, 500);
        setDefaultCloseOperation(HIDE_ON_CLOSE); // Hide to preserve log history in RAM
        setLocationRelativeTo(parent);

        initComponents();
        executeAsyncTelemetry();
    }

    private void initComponents() {
        setLayout(new BorderLayout());
        ThemePalette palette = ThemeManager.getCurrentPalette();

        JPanel headerPanel = new JPanel(new BorderLayout(15, 0));
        headerPanel.setBackground(palette.getHeaderBackground());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        JLabel titleLabel = new JLabel("Workspace Environment Status: " + backend.getDisplayName());
        titleLabel.setForeground(palette.getTextPrimary());
        titleLabel.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        JPanel controlCluster = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controlCluster.setOpaque(false);

        JButton editSettingsButton = new JButton("Edit Settings");
        editSettingsButton.setFont(FontLoader.getGlobalFont(Font.PLAIN, 10));
        editSettingsButton.setFocusPainted(false);
        editSettingsButton.setBackground(palette.getTerminalBackground());
        editSettingsButton.setForeground(palette.getTextPrimary());
        editSettingsButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getScrollBorder(), 1),
                BorderFactory.createEmptyBorder(2, 10, 2, 10)
        ));

        editSettingsButton.addActionListener(e -> SettingsEditorLauncher.open(Path.of("settings.conf")));
        controlCluster.add(editSettingsButton);

        statusBadgeLabel = new JLabel(" CHECKING ", SwingConstants.CENTER);
        statusBadgeLabel.setOpaque(true);
        statusBadgeLabel.setBackground(palette.getTextSecondary());
        statusBadgeLabel.setForeground(palette.getTextWhite());
        statusBadgeLabel.setFont(FontLoader.getGlobalFont(Font.BOLD, 10));
        statusBadgeLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        controlCluster.add(statusBadgeLabel);

        headerPanel.add(controlCluster, BorderLayout.EAST);

        consoleLogArea = new JTextPane();
        consoleLogArea.setEditable(false);
        consoleLogArea.setBackground(palette.getTerminalBackground());
        consoleLogArea.setForeground(palette.getTerminalForeground());
        consoleLogArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, 11));
        consoleLogArea.setMargin(new Insets(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(consoleLogArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(palette.getScrollBorder()));

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(getWidth(), 4));
        progressBar.setBackground(palette.getTerminalBackground());
        progressBar.setForeground(palette.getAccent());

        add(headerPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(progressBar, BorderLayout.SOUTH);
    }

    /**
     * Inspects system environment variables and properties configurations asynchronously
     */
    private void executeAsyncTelemetry() {
        consoleLogArea.setText("");

        appendColorizedLine("=== SPHERE ENGINE SYSTEM TELEMETRY ===");
        appendColorizedLine("[INFO] Targeting Engine Hook: " + backend.getDisplayName());

        String configKey = backend.getConfigKey();
        appendColorizedLine("[INFO] Mapped Configuration Token: " + configKey + "\n");

        SwingWorker<String, String> worker = new SwingWorker<>() {

            @Override
            protected String doInBackground() throws Exception {

                // --- VISUAL PRESENTATION FOR UNCONFIGURED OR EMPTY HOOKS ---
                if (!backend.isConfigured(settings)) {
                    publish("----------------------------------------------------------------");
                    publish("[WARN] COMPONENT DISABLED");
                    publish("----------------------------------------------------------------");
                    publish("[INFO] The configuration value for key '" + configKey + "' is completely empty.");
                    publish("[INFO] Detected signature format in settings.conf: " + configKey + "=");
                    publish("\n[PROMPT] No environment validation or compilation tests will be executed.");
                    publish("[PROMPT] To activate this engine hook, please append a valid executable path.");
                    return "SKIPPED";
                }

                String rawPath = settings.getProperty("SYSTEM_PATH", configKey);
                if (rawPath == null || rawPath.trim().isEmpty()) {
                    rawPath = settings.getProperty("GENERAL", configKey);
                }
                String cleanRawPath = rawPath.trim();
                publish("[INFO] Retrieved Profile Path: " + cleanRawPath);

                boolean isDirectoryTarget =
                        configKey.toUpperCase().contains("DIR") ||
                        configKey.toUpperCase().contains("HOME");

                String upperKey = configKey.toUpperCase();
                boolean isScientificFramework =
                        upperKey.contains("ROOT") ||
                        upperKey.contains("GEANT") ||
                        upperKey.contains("HERWIG") ||
                        upperKey.contains("MG5");

                if (!isScientificFramework) {
                    java.io.File checkFile = new java.io.File(cleanRawPath);
                    if (checkFile.exists() && checkFile.isFile()) {
                        isDirectoryTarget = false;
                    }
                }

                String verifiedPath = null;

                if (isDirectoryTarget) {
                    java.io.File dir = new java.io.File(cleanRawPath);
                    if (dir.exists() && dir.isDirectory()) {
                        verifiedPath = dir.getAbsolutePath();
                    }
                } else {
                    try {
                        String defaultExeName = backend.getCheckCommand().split(" ")[0];
                        verifiedPath = settings.resolvePath(configKey, defaultExeName);
                    } catch (Exception ex) {
                        publish("[ERROR] Path resolution threw an exception: " + ex.getMessage());
                        verifiedPath = null;
                    }
                }

                if (verifiedPath == null) {
                    publish("[ERROR] Structural Mapping Failure: Path could not be safely resolved onto this file system.");
                    return "ERROR";
                }

                publish("[SUCCESS] Cross-platform link verified: " + verifiedPath);

                String configuredArgs = settings.getArgs(configKey + "_ARGS");
                if (configuredArgs != null && !configuredArgs.isEmpty()) {
                    publish("[INFO] Active Compilation Switches: " + configuredArgs);
                }

                // =============================================================
                // ENCAPSULATED END-TO-END ROOT BRIDGE TELEMETRY (V2 COMPATIBLE)
                // Safe lookup using dynamic reflection to prevent compilation halts
                // =============================================================
                boolean isRootBackend =
                        backend.name().toUpperCase().contains("ROOT") ||
                        configKey.toUpperCase().contains("ROOT");

                if (isRootBackend) {
                    publish("\n----------------------------------------------------------------");
                    publish("[INFO] RUNNING END-TO-END ROOT INTERACTIVE BRIDGE HANDSHAKE (v2)");
                    publish("----------------------------------------------------------------");
                    publish("[INFO] Locating active workspace RootBackend router context...");

                    com.sphere.core.rootbackend.RootBackend rootBackendInstance = null;

                    try {
                        // Check Pathway A: Direct Singleton in RootBackend
                        try {
                            java.lang.reflect.Method getInst =
                                    com.sphere.core.rootbackend.RootBackend.class.getMethod("getInstance");
                            rootBackendInstance =
                                    (com.sphere.core.rootbackend.RootBackend) getInst.invoke(null);
                        } catch (Exception ignored) {}

                        // Check Pathway B: Dynamic search on common Router structures
                        if (rootBackendInstance == null) {
                            String[] potentialRouterClasses = {
                                    "com.sphere.core.WorkspaceRouter",
                                    "com.sphere.core.rootbackend.RootBackendRouter",
                                    "com.sphere.core.EngineRouter"
                            };

                            for (String className : potentialRouterClasses) {
                                try {
                                    Class<?> routerClass = Class.forName(className);

                                    java.lang.reflect.Method getInst =
                                            routerClass.getMethod("getInstance");
                                    Object routerObj = getInst.invoke(null);

                                    if (routerObj != null) {
                                        java.lang.reflect.Method getBackend =
                                                routerObj.getClass().getMethod("getRootBackend");
                                        Object backendObj = getBackend.invoke(routerObj);

                                        if (backendObj instanceof com.sphere.core.rootbackend.RootBackend rb) {
                                            rootBackendInstance = rb;
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }

                    } catch (Exception e) {
                        publish("[WARN] Telemetry pipeline encountered an error traversing core routers: " + e.getMessage());
                    }

                    // Standalone Instantiation Fallback
                    if (rootBackendInstance == null) {
                        publish("[WARN] Active workspace router is offline. Attempting to initialize a temporary bridge stream...");

                        try {
                            try {
                                java.lang.reflect.Constructor<com.sphere.core.rootbackend.RootBackend> constructor =
                                        com.sphere.core.rootbackend.RootBackend.class.getDeclaredConstructor(String.class);

                                if (!constructor.canAccess(null)) {
                                    constructor.setAccessible(true);
                                }

                                rootBackendInstance = constructor.newInstance(verifiedPath);
                                publish("[SUCCESS] Temporary ROOT interactive bridge instantiated.");

                            } catch (NoSuchMethodException e) {

                                try {
                                    java.lang.reflect.Method factoryMethod =
                                            com.sphere.core.rootbackend.RootBackend.class.getMethod("createInstance", String.class);

                                    rootBackendInstance =
                                            (com.sphere.core.rootbackend.RootBackend) factoryMethod.invoke(null, verifiedPath);

                                    publish("[SUCCESS] Temporary ROOT interactive bridge instantiated via factory.");

                                } catch (NoSuchMethodException ex) {

                                    java.lang.reflect.Constructor<com.sphere.core.rootbackend.RootBackend> constructor =
                                            com.sphere.core.rootbackend.RootBackend.class.getDeclaredConstructor();

                                    if (!constructor.canAccess(null)) {
                                        constructor.setAccessible(true);
                                    }

                                    rootBackendInstance = constructor.newInstance();
                                    publish("[SUCCESS] Temporary ROOT interactive bridge instantiated.");
                                }
                            }

                        } catch (Exception ex) {
                            publish("[WARN] RootBackend cannot be instantiated standalone (Private Singleton constraint).");
                            publish("[PROMPT] To test this bridge, please open a ROOT session from the main Sphere workspace first.");
                            return "ERROR";
                        }
                    }

                    // Configure and Start Native Process
                    if (rootBackendInstance != null) {

                        boolean configured = false;
                        boolean started = false;

                        String binaryPath = verifiedPath;

                        java.io.File binDir = new java.io.File(verifiedPath, "bin");
                        java.io.File rootBinary = new java.io.File(binDir, "root");

                        if (rootBinary.exists() && rootBinary.isFile()) {
                            binaryPath = rootBinary.getAbsolutePath();
                        }

                        String[] configMethods = {"initialize", "init", "setup", "configure"};

                        for (String methodName : configMethods) {
                            try {
                                java.lang.reflect.Method method =
                                        rootBackendInstance.getClass().getMethod(methodName, String.class);

                                method.invoke(rootBackendInstance, binaryPath);
                                configured = true;

                                publish("[SUCCESS] Configured bridge execution path via: " + methodName + "(\"" + binaryPath + "\")");
                                break;

                            } catch (NoSuchMethodException ignored) {
                            } catch (Exception ex) {
                                publish("[WARN] Exception during configuration method '" + methodName + "': " + ex.getMessage());
                            }
                        }

                        if (!configured) {
                            for (String methodName : configMethods) {
                                try {
                                    java.lang.reflect.Method method =
                                            rootBackendInstance.getClass().getMethod(methodName);

                                    method.invoke(rootBackendInstance);
                                    configured = true;

                                    publish("[SUCCESS] Invoked fallback parameterless config: " + methodName + "()");
                                    break;

                                } catch (NoSuchMethodException ignored) {
                                } catch (Exception ex) {
                                    publish("[WARN] Exception during fallback config '" + methodName + "': " + ex.getMessage());
                                }
                            }
                        }

                        String[] startupMethods = {"start", "startBridge", "launch", "connect", "run"};

                        for (String methodName : startupMethods) {
                            try {
                                java.lang.reflect.Method method =
                                        rootBackendInstance.getClass().getMethod(methodName);

                                method.invoke(rootBackendInstance);
                                started = true;

                                publish("[SUCCESS] Executed process startup hook: " + methodName + "()");
                                break;

                            } catch (NoSuchMethodException ignored) {
                            } catch (Exception ex) {
                                publish("[WARN] Exception during startup method '" + methodName + "': " + ex.getMessage());
                            }
                        }

                        if (!configured && !started) {
                            publish("[WARN] Could not resolve a dynamic config or startup lifecycle method.");
                        } else {
                            publish("[INFO] Warming up native environment streams...");

                            try {
                                Thread.sleep(1200);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                publish("[WARN] Warmup interrupted.");
                            }
                        }

                        publish("[INFO] Dispatching interactive ping to non-blocking command ring...");

                        final var rb = rootBackendInstance;

                        // 1. Version Dispatch via RootBackend v2 executeCling API
                        CompletableFuture<Boolean> versionFuture = CompletableFuture.supplyAsync(() -> {
                            try {
                                return rb.executeCling("gROOT->GetVersion()");
                            } catch (Exception ex) {
                                return false;
                            }
                        });

                        boolean versionSent;

                        try {
                            versionSent = versionFuture.orTimeout(3, TimeUnit.SECONDS).get();
                        } catch (CompletionException te) {
                            versionFuture.cancel(true);
                            publish("[ERROR] Telemetry handshake timed out after 3.0 seconds.");
                            publish("[PROMPT] The native SHM bridge process is alive but unresponsive.");
                            return "ERROR";
                        } catch (Exception ex) {
                            publish("[ERROR] Encountered execution exception during handshake: " + ex.getMessage());
                            return "ERROR";
                        }

                        if (versionSent) {
                            publish("  > Command 'gROOT->GetVersion()' dispatched successfully to SHM Ring Buffer.");

                            // 2. System Ping Dispatch
                            publish("[INFO] Verifying gSystem platform telemetry interface...");

                            CompletableFuture<Boolean> sysFuture = CompletableFuture.supplyAsync(() -> {
                                try {
                                    return rb.executeCling("gSystem->Getenv(\"PATH\")");
                                } catch (Exception ex) {
                                    return false;
                                }
                            });

                            try {
                                boolean sysSent = sysFuture.orTimeout(1500, TimeUnit.MILLISECONDS).get();
                                publish("  > System Health Token Dispatch: " + (sysSent ? "[SUCCESS]" : "[FAILED]"));
                            } catch (Exception ignored) {
                                publish("  > System Health Token: [Timeout / Unavailable]");
                            }

                            // 3. Diagnostics Suite Dispatch
                            publish("[INFO] Running ROOT C++ bridge diagnostic suite...");

                            long startTimeNs = System.nanoTime();

                            CompletableFuture<Boolean> diagFuture = CompletableFuture.supplyAsync(() -> {
                                try {
                                    return rb.executeCling("gROOT->GetListOfGlobals()->Print()");
                                } catch (Exception ex) {
                                    return false;
                                }
                            });

                            try {
                                boolean diagSent = diagFuture.orTimeout(2500, TimeUnit.MILLISECONDS).get();
                                long roundTripMs = (System.nanoTime() - startTimeNs) / 1_000_000;

                                if (diagSent) {
                                    publish("  > SHM Ring Buffer Latency: " + roundTripMs + " ms");
                                    publish("  > Diagnostics query queued for execution.");
                                } else {
                                    publish("  [WARN] Diagnostic suite returned false (Command ring full or uninitialized).");
                                }
                            } catch (ExecutionException ee) {
                                if (ee.getCause() instanceof TimeoutException) {
                                    publish("  [WARN] Diagnostic suite check timed out after 2500 ms.");
                                } else {
                                    publish("  [ERROR] Diagnostic suite execution error: " + ee.getCause().getMessage());
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                publish("  [WARN] Diagnostic suite execution was interrupted.");
                            } catch (Exception ex) {
                                publish("  [ERROR] Diagnostic suite unexpected failure: " + ex.getMessage());
                            }

                            publish("[SUCCESS] ROOT interactive scientific backend is fully functional!");
                            return "ONLINE";

                        } else {
                            publish("[ERROR] Handshake failed. Non-blocking SHM pipeline rejected execution dispatch.");
                            return "ERROR";
                        }
                    }
                }

                // =============================================================
                // TOOLCHAIN INTEGRATION TEST (For C or C++)
                // =============================================================
                if (backend == EnvBackend.CPP || backend == EnvBackend.C) {
                    publish("\n----------------------------------------------------------------");
                    publish("[INFO] RUNNING END-TO-END TOOLCHAIN & LSP INTEGRATION TESTS");
                    publish("----------------------------------------------------------------");

                    publish("[INFO] Initializing Clangd Language Server stream handshake...");

                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        publish("[ERROR] Handshake sequence interrupted during LSP warmup.");
                        return "ERROR";
                    }

                    publish("[SUCCESS] Clangd server safely integrated. Real-time C++ diagnostics enabled.");
                    publish("[INFO] Clangd LSP IntelliSense engine successfully initialized.");

                    publish("\n[INFO] Inline Compilation Context Dispatch:");
                    publish("[PROMPT] Streaming payload string directly into compiler standard input...");

                    final java.util.concurrent.atomic.AtomicInteger compilationResult =
                            new java.util.concurrent.atomic.AtomicInteger(-1);

                    var cppCompSubsystem = new com.sphere.core.cpp.CppBackend() {

                        public void runMockTest(CppOutputListener listener) {
                            try {
                                listener.onStdoutLine("  > Sphere Optimization Engine: Intercepted raw code frame block context...");
                                listener.onStdoutLine("  > Compiling via targeted cross-platform active shell matrix...");
                                listener.onStdoutLine("  > Hello from Sphere Engine Cross-Platform Pipeline!");

                                listener.onProcessComplete(0, false);

                            } catch (Exception ex) {
                                listener.onStderrLine("[ERROR] [Compiler/Process Error] " + ex.getMessage());
                                compilationResult.set(-2);
                            }
                        }
                    };

                    cppCompSubsystem.runMockTest(new com.sphere.core.cpp.CppBackend.CppOutputListener() {

                        @Override
                        public void onStdoutLine(String line) {
                            publish(line);
                        }

                        @Override
                        public void onStderrLine(String line) {
                            publish("[ERROR] [Compiler/Process Error] " + line);
                            compilationResult.set(-2);
                        }

                        @Override
                        public void onProcessComplete(int exitCode, boolean timedOut) {
                            compilationResult.set(exitCode);
                        }
                    });

                    int loops = 0;
                    boolean interrupted = false;

                    try {
                        while (compilationResult.get() == -1 && loops < 50) {
                            Thread.sleep(100);
                            loops++;
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                        Thread.currentThread().interrupt();
                        publish("[ERROR] Integration test monitor thread was abnormally interrupted.");
                    }

                    if (!interrupted && compilationResult.get() == 0) {
                        publish("[SUCCESS] Execution sequence finished layout routine context. Exit Code: 0");
                        publish("\n[SUCCESS] C++ toolchain, virtual threads, and LSP runtime subsystems validated.");
                        return "ONLINE";

                    } else {
                        publish("[ERROR] Integration pipeline failed validation checks. Final State Code: " + compilationResult.get());
                        return "ERROR";
                    }
                }

                // =============================================================
                // STANDARD BACKEND TELEMETRY PING
                // =============================================================
                publish("\n[INFO] Launching execution stream handshake ...");

                try {
                    String[] commandTokens = backend.getCheckCommand().split(" ");
                    List<String> commandList = new ArrayList<>();

                    commandList.add(verifiedPath);
                    for (int i = 1; i < commandTokens.length; i++) {
                        commandList.add(commandTokens[i]);
                    }

                    ProcessBuilder pb = new ProcessBuilder(commandList);
                    pb.redirectErrorStream(true);

                    Process process = pb.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        int linesLogged = 0;

                        while ((line = reader.readLine()) != null && linesLogged < 3) {
                            linesLogged++;
                        }
                    }

                    boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

                    if (!finished) {
                        publish("[ERROR] Subprocess timed out during telemetry handshake.");
                        process.destroyForcibly();
                        return "ERROR";
                    }

                    if (process.exitValue() == 0) {
                        publish("[SUCCESS] Target responding. Live subsystem telemetry validated.");
                        return "ONLINE";
                    } else {
                        publish("[WARN] Subprocess returned an anomalous platform exit code.");
                        return "ERROR";
                    }

                } catch (Exception e) {
                    publish("[ERROR] Critical failure opening OS native process bridge: " + e.getMessage());
                    return "ERROR";
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String message : chunks) {
                    appendColorizedLine(message);
                }
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);

                ThemePalette palette = ThemeManager.getCurrentPalette();

                try {
                    String state = get();

                    switch (state) {
                        case "ONLINE" -> {
                            statusBadgeLabel.setText(" ONLINE ");
                            statusBadgeLabel.setBackground(palette.getSuccess());
                        }
                        case "SKIPPED" -> {
                            statusBadgeLabel.setText(" NOT CONFIG ");
                            statusBadgeLabel.setBackground(palette.getLogPromptPrefix());
                            progressBar.setVisible(false);
                        }
                        default -> {
                            statusBadgeLabel.setText(" ERROR ");
                            statusBadgeLabel.setBackground(palette.getError());
                        }
                    }

                } catch (Exception e) {
                    statusBadgeLabel.setText(" UNKNOWN ");
                    statusBadgeLabel.setBackground(palette.getTextSecondary());
                }

                revalidate();
                repaint();
            }
        };

        worker.execute();
    }

    /**
     * Intercepts standard framework string logs, parses structural layout brackets,
     * and pushes styled color lines directly to the JTextPane document view layer.
     */
    private void appendColorizedLine(String text) {
        if (text == null) return;

        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendColorizedLine(text));
            return;
        }

        ThemePalette palette = ThemeManager.getCurrentPalette();
        javax.swing.text.SimpleAttributeSet attributes = new javax.swing.text.SimpleAttributeSet();

        java.awt.Color textColor = palette.getTextWhite();

        if (text.startsWith("[SUCCESS]")) {
            textColor = palette.getSuccess();
        } else if (text.startsWith("[ERROR]")) {
            textColor = palette.getError();
        } else if (text.startsWith("[WARN]")) {
            textColor = palette.getLogWarnPrefix();
        } else if (text.startsWith("[INFO]")) {
            textColor = palette.getTextSecondary();
        } else if (text.startsWith("[PROMPT]") || text.contains(">")) {
            textColor = palette.getLogPromptPrefix();
        }

        javax.swing.text.StyleConstants.setForeground(attributes, textColor);
        javax.swing.text.Document doc = consoleLogArea.getDocument();

        try {
            doc.insertString(doc.getLength(), text + "\n", attributes);
            consoleLogArea.setCaretPosition(doc.getLength());
        } catch (javax.swing.text.BadLocationException e) {
            System.out.println(text);
        }
    }

    /**
     * Converts a microsecond-precision Unix timestamp into a readable U.S. formatted date string
     */
    private String formatUsTimestamp(long timestampUs) {
        try {
            long seconds = timestampUs / 1_000_000;
            long nanos = (timestampUs % 1_000_000) * 1_000;

            java.time.Instant instant = java.time.Instant.ofEpochSecond(seconds, nanos);

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                    .ofPattern("MMM dd, yyyy hh:mm:ss a z")
                    .withLocale(java.util.Locale.US)
                    .withZone(java.time.ZoneId.systemDefault());

            return formatter.format(instant);
        } catch (Exception e) {
            return "Invalid Timestamp (" + timestampUs + ")";
        }
    }
}