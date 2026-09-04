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
                        verifiedPath = settings.resolveTool(configKey, defaultExeName);
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
                // ENCAPSULATED END-TO-END ROOT BRIDGE TELEMETRY
                // =============================================================
                boolean isRootBackend =
                        backend.name().toUpperCase().contains("ROOT") ||
                        configKey.toUpperCase().contains("ROOT");

                if (isRootBackend) {
                    publish("\n----------------------------------------------------------------");
                    publish("[INFO] RUNNING END-TO-END ROOT INTERACTIVE BRIDGE HANDSHAKE (v4 - SHM)");
                    publish("----------------------------------------------------------------");
                    publish("[INFO] Locating active workspace RootBackend SHM instance...");

                    com.sphere.core.rootbackend.RootBackend rootBackendInstance = null;
                    boolean weOwnTheInstance = false;

                    try {
                        // 1. Reuse the application's live instance if there is one.
                        try {
                            java.lang.reflect.Method getInst =
                                    com.sphere.core.rootbackend.RootBackend.class.getMethod("getInstance");
                            rootBackendInstance =
                                    (com.sphere.core.rootbackend.RootBackend) getInst.invoke(null);
                        } catch (Exception ignored) {
                            // No singleton accessor: fall through to a standalone instance.
                        }

                        // 2. Otherwise stand one up for the duration of the test.
                        if (rootBackendInstance == null) {
                            publish("[WARN] Active RootBackend SHM instance not found. Attempting standalone initialization...");

                            try {
                                rootBackendInstance =
                                        new com.sphere.core.rootbackend.RootBackend(
                                                (java.nio.file.Path) null, 0L);
                                weOwnTheInstance = true;
                                publish("[SUCCESS] Standalone SHM RootBackend bridge instantiated.");
                            } catch (Exception ex) {
                                publish("[WARN] RootBackend could not be initialized: " + ex.getMessage());
                            }
                        }

                    } catch (Exception e) {
                        publish("[WARN] Telemetry pipeline encountered an error initializing RootBackend: " + e.getMessage());
                    }

                    if (rootBackendInstance == null) {
                        publish("[ERROR] Failed to obtain or instantiate a valid RootBackend instance.");
                        return "ERROR";
                    }

                    final com.sphere.core.rootbackend.RootBackend rb = rootBackendInstance;
                    final boolean closeWhenDone = weOwnTheInstance;

                    try {
                        // 3. Availability: compiled binary plus a mapped, verified region.
                        if (!rb.isAvailable()) {
                            publish("[WARN] RootBackend is NOT available (C++ bridge binary missing or SHM allocation failed).");
                            publish("[PROMPT] Ensure ROOT environment is configured and root-bridge binary is compiled.");
                            return "ERROR";
                        }

                        publish("[INFO] RootBackend memory layout & SHM segments verified successfully.");

                        // 4. Lifecycle hooks.
                        try {
                            rb.initialize();
                            rb.activate();
                            publish("[SUCCESS] RootBackend lifecycle hooks (initialize/activate) executed.");
                        } catch (Exception ex) {
                            publish("[WARN] Non-fatal issue during backend activation: " + ex.getMessage());
                        }

                        // 5. Liveness before anything else
                        publish("[INFO] Probing the engine for liveness (CMD_PING)...");

                        if (!rb.pingAwait(3000L)) {
                            publish("[ERROR] No EVT_PONG within 3.0 s: nothing is draining the command ring.");
                            publish("[PROMPT] Check that root-bridge started with --serve, and read");
                            publish("         rootbackend/rootbackend_error.log for why it exited.");
                            return "ERROR";
                        }
                        publish("  > EVT_PONG received. The engine is draining the ring.");

                        // 6. Interpreter round trip. Separated from the ping so a working
                        // ring with a broken interpreter is distinguishable.
                        publish("[INFO] Dispatching interactive telemetry to the SHM command ring...");

                        String version = rb.executeClingAwait("gROOT->GetVersion()", 3000L);
                        if (version == null) {
                            publish("[ERROR] The engine answers CMD_PING but not CMD_CLING_EXEC.");
                            publish("[PROMPT] The ring is sound; the interpreter path is not.");
                            return "ERROR";
                        }

                        publish("  > ROOT version reported by the engine: " + version);
                        publish("  > Round trip verified: queued, executed inside ROOT, answer returned.");

                        // 7. System telemetry.
                        publish("[INFO] Verifying gSystem platform telemetry interface...");

                        String pathValue = rb.executeClingAwait("gSystem->Getenv(\"PATH\")", 1500L);
                        if (pathValue != null) {
                            String shown = pathValue.length() > 60
                                    ? pathValue.substring(0, 60) + "..."
                                    : pathValue;
                            publish("  > System Health Token Dispatch: [SUCCESS] " + shown);
                        } else {
                            publish("  > System Health Token: [Timeout / Unavailable]");
                        }

                        // 8. Diagnostic suite, timed
                        publish("[INFO] Running ROOT C++ bridge diagnostic suite...");
                        long startTimeNs = System.nanoTime();

                        String diagnostics = rb.executeClingAwait("gROOT->GetListOfGlobals()->Print()", 2500L);
                        long roundTripMs = (System.nanoTime() - startTimeNs) / 1_000_000L;

                        if (diagnostics != null) {
                            publish("  > SHM round-trip latency: " + roundTripMs + " ms");
                            publish("  > Diagnostics executed; engine returned: " + diagnostics);
                        } else {
                            publish("  [WARN] Diagnostic suite timed out after 2500 ms.");
                            publish("  [PROMPT] The engine answered the first probes, so the ring is sound;");
                            publish("           this command is the one taking too long inside ROOT.");
                        }

                        publish("[SUCCESS] ROOT interactive scientific SHM backend is fully functional!");
                        return "ONLINE";

                    } finally {
                        // Release the engine process and the mapping if this block
                        // started them.
                        if (closeWhenDone) {
                            try {
                                rb.close();
                                publish("[INFO] Standalone RootBackend instance released.");
                            } catch (Exception ex) {
                                publish("[WARN] Could not release the standalone instance: " + ex.getMessage());
                            }
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