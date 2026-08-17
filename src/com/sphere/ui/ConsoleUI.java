package com.sphere.ui;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.sphere.utils.SessionManager;
import com.sphere.components.QuickCodeEditor;
import com.sphere.utils.SettingsManager;
import com.sphere.components.SessionSelectionDialog;
import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.SettingsEditorLauncher;
import com.sphere.core.cpp.CppIntellisenseBackend;
import com.sphere.core.cpp.CppDiagnosticsEngine;
import com.sphere.core.cpp.CppDiagnosticsParser.Diagnostic;
import com.sphere.core.cpp.CppBackend;
import com.sphere.core.cpp.CppFormatterEngine;
import com.sphere.core.rootbackend.RootBridgeCompiler;


/**
 * Thread-safe, optimized UI terminal surface for output display execution chains.
 * Combines high-performance Enum routing with fallback semantic regex scanning.
 * Relies on a shared centralized C++ runtime engine hook to manage LSP context fields safely.
 */
public class ConsoleUI extends JPanel {
    
    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    private final JTextPane logArea;
    private final SessionManager session;
    private final QuickCodeEditorFrame editorFrame;
    private final CppDiagnosticsEngine diagnosticsEngine;
    
    // Shared reference to the persistent language server sub-processes managed by the core engine
    private final CppIntellisenseBackend intellisenseBackend;
    
    // Shared reference to the centralized settings provider to ensure system-wide configuration consistency
    private final SettingsManager settings;

    private final CppBackend cppBackend;
    
    private static final int MAX_LINE_COUNT = 5000;

    private JCheckBox verboseFormatCheck;

    /**
     * High-Performance Explicit Log Levels
     */
    public enum LogLevel {
        ERROR("[!]"),
        SUCCESS("[+]"),
        INFO("[i]"),
        PROMPT("[>]"),
        WARN("[W]"),
        CLEAR("[C]"),
        NONE(""); // Used for raw external sub-process data streams

        private final String prefix;
        LogLevel(String prefix) { this.prefix = prefix; }
        public String getPrefix() { return prefix; }
    }

    // Fallback Scanning Patterns (Only parsed if LogLevel is NONE)
    private static final Pattern ERROR_PATTERN = compileLogPattern("error", "exception", "failed", "failure", "critical", "fatal", "severe", "denied", "unauthorized", "crash", "halted", "abort", "aborted", "timeout");
    private static final Pattern WARN_PATTERN = compileLogPattern("warning", "warn", "caution", "alert", "stuck", "deprecated", "deprecation", "invalid", "missing", "skipped", "retry", "retrying", "bad", "attention");
    private static final Pattern SUCCESS_PATTERN = compileLogPattern("success", "successfully", "passed", "ok", "done", "completed", "complete", "valid", "validated", "connected", "resolved", "ready", "enabled", "loaded", "finished");

    private static Pattern compileLogPattern(String... words) {
        return Pattern.compile("(?i)\\b(" + String.join("|", words) + ")\\b");
    }

    /**
     * Constructs the Console UI layer linked to the session pipeline, shared text editor tabs,
     * the centralized C++ backend manager, and the system global settings registry.
     *
     * @param session           The current diagnostic tracking session.
     * @param editorFrame       The persistent workspace frame used for code and configuration asset display.
     * @param diagnosticsEngine The central data processing hub collecting compiler logs and LSP diagnostic items.
     * @param cppBackend        The core C++ service orchestration backend managing background sub-processes.
     * @param settings          The persistent shared configuration state context.
     */
    public ConsoleUI(SessionManager session, QuickCodeEditorFrame editorFrame, CppDiagnosticsEngine diagnosticsEngine, com.sphere.core.cpp.CppBackend cppBackend, SettingsManager settings) {
        this.session = session;
        this.editorFrame = editorFrame;
        this.diagnosticsEngine = diagnosticsEngine != null ? diagnosticsEngine : new CppDiagnosticsEngine();
        
        // Decouple lifecycle management by grabbing the shared instance from the centralized core engine
        this.intellisenseBackend = cppBackend != null ? cppBackend.getIntellisenseBackend() : new CppIntellisenseBackend();

        this.cppBackend = cppBackend;
        
        // Retain the system-wide settings registry instance to prevent redundant disk I/O and configuration drifts
        this.settings = settings != null ? settings : new SettingsManager();
        
        setLayout(new BorderLayout());
        logArea = new JTextPane();
        logArea.setEditable(false);
        
        logArea.setBackground(palette.getTerminalBackground());
        logArea.setForeground(palette.getTerminalForeground());
        logArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        
        logArea.setSelectionColor(palette.getTerminalSelection());
        logArea.setSelectedTextColor(palette.getTextWhite());
        logArea.putClientProperty("JTextPane.honorDisplayProperties", Boolean.TRUE);
        
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(palette.getTerminalBorder(), 1));
        scrollPane.setBackground(palette.getTerminalBackground());
        scrollPane.getViewport().setBackground(palette.getTerminalBackground());
        
        add(scrollPane, BorderLayout.CENTER);
        setupContextMenu();
    }

    /**
     * Seamlessly launches the internal clangd sub-process engine toolchain.
     * Automatically hooks diagnostics and streaming standard error loops directly into the visible console view.
     */
    public void startCppIntellisense(String executablePath, String compilationDatabaseDir, CppIntellisenseBackend.IntellisenseListener listener) {
        if (intellisenseBackend.isRunning()) {
            log(LogLevel.INFO, "Clangd Intellisense server is already running.");
            return;
        }

        log(LogLevel.INFO, "Initializing Clangd LSP toolchain infrastructure...");
        try {
            intellisenseBackend.start(executablePath, compilationDatabaseDir, rpcMessage -> {
                // Route the raw responses out to the primary systemic caller implementation
                if (listener != null) {
                    listener.onLspResponse(rpcMessage);
                }
            });
            log(LogLevel.SUCCESS, "Clangd server safely integrated. Real-time C++ diagnostics enabled.");
        } catch (SecurityException se) {
            log(LogLevel.ERROR, se.getMessage());
        } catch (IOException ioException) {
            log(LogLevel.ERROR, "Failed to successfully initialize clangd sub-process: " + ioException.getMessage());
        }
    }

    /**
     * Explicitly shuts down the clangd sub-process and frees socket file allocations.
     */
    public void stopCppIntellisense() {
        if (intellisenseBackend.isRunning()) {
            log(LogLevel.WARN, "Halting C++ Intellisense sub-process channels...");
            intellisenseBackend.stop();
            log(LogLevel.INFO, "Clangd language server engine safely terminated.");
        }
    }

    /**
     * Unified performance entry point. Checks the structural LogLevel first to save CPU cycles,
     * falling back to string pattern classification rules only if the data stream source is raw text.
     */
    public void log(LogLevel level, final String message) {
        if (message == null) return;

        // Automatically structure the saved logs file format 
        String persistentMessage = level == LogLevel.NONE ? message : level.getPrefix() + " " + message;
        session.log(persistentMessage);

        SwingUtilities.invokeLater(() -> {
            Color prefixColor = palette.getLogDefaultText();
            boolean hasEnumPrefix = level != LogLevel.NONE;

            // O(1) Instant Enum Match Routine (Saves massive CPU cycles)
            switch (level) {
                case CLEAR:   
                    // Wipe the visual log text pane completely and stop processing immediately
                    this.logArea.setText(""); 
                    return;
                    
                case ERROR:   prefixColor = palette.getLogErrorPrefix(); break;
                case SUCCESS: prefixColor = palette.getLogSuccessPrefix(); break;
                case INFO:    prefixColor = palette.getLogInfoPrefix(); break;
                case PROMPT:  prefixColor = palette.getLogPromptPrefix(); break;
                case WARN:    prefixColor = palette.getLogWarnPrefix();  break;
                case NONE:
                    if (message.startsWith("[!]")) { return; }
                    break;
            }

            if (hasEnumPrefix) {
                appendColoredText(level.getPrefix() + " ", prefixColor, true);
                appendExplicitLevelText(message + "\n", level);
            } else {
                appendSmartHighlightedText(message + "\n");
            }

            enforceBufferLimit();
        });
    }

    /**
     * High performance text insertion path. Zero regex processing overhead.
     */
    private void appendExplicitLevelText(String text, LogLevel level) {
        StyledDocument doc = logArea.getStyledDocument();
        SimpleAttributeSet attributes = new SimpleAttributeSet();

        switch (level) {
            case ERROR:
                StyleConstants.setForeground(attributes, palette.getLogErrorText());
                StyleConstants.setBold(attributes, true);
                break;
            case WARN:
                StyleConstants.setForeground(attributes, palette.getLogWarnText());
                StyleConstants.setBold(attributes, true);
                break;
            case SUCCESS:
                StyleConstants.setForeground(attributes, palette.getLogSuccessText());
                StyleConstants.setBold(attributes, false);
                break;
            default:
                StyleConstants.setForeground(attributes, palette.getLogDefaultText());
                StyleConstants.setBold(attributes, false);
                break;
        }
        insertDocumentStringSafely(doc, text, attributes);
    }

    /**
     * Fallback text insertion path. Scans external process strings to dynamically apply styles.
     */
    private void appendSmartHighlightedText(String text) {
        StyledDocument doc = logArea.getStyledDocument();
        SimpleAttributeSet attributes = new SimpleAttributeSet();

        if (ERROR_PATTERN.matcher(text).find()) {
            StyleConstants.setForeground(attributes, palette.getLogErrorText());
            StyleConstants.setBold(attributes, true);
        } else if (WARN_PATTERN.matcher(text).find()) {
            StyleConstants.setForeground(attributes, palette.getLogWarnText());
            StyleConstants.setBold(attributes, true);
        } else if (SUCCESS_PATTERN.matcher(text).find()) {
            StyleConstants.setForeground(attributes, palette.getLogSuccessText());
            StyleConstants.setBold(attributes, false);
        } else {
            StyleConstants.setForeground(attributes, palette.getLogDefaultText());
            StyleConstants.setBold(attributes, false);
        }
        insertDocumentStringSafely(doc, text, attributes);
    }

    private void appendColoredText(String text, Color color, boolean isBold) {
        StyledDocument doc = logArea.getStyledDocument();
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, isBold);
        insertDocumentStringSafely(doc, text, style);
    }

    private void insertDocumentStringSafely(StyledDocument doc, String text, AttributeSet attributes) {
        try {
            int targetOffset = doc.getLength();
            doc.insertString(targetOffset, text, attributes);
            logArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            AppLogger.error("Failed to insert text stream sequence: " + e.getMessage());
        }
    }

    private void enforceBufferLimit() {
        Document doc = logArea.getDocument();
        Element root = doc.getDefaultRootElement();
        int lineCount = root.getElementCount();
        
        if (lineCount > MAX_LINE_COUNT) {
            int endOffset = root.getElement(lineCount - MAX_LINE_COUNT).getStartOffset();
            try {
                doc.remove(0, endOffset);
            } catch (BadLocationException ignored) {}
        }
    }

    /**
     * Programmatic Hook: Automatically formats and streams active workspace diagnostics.
     */
    public void printActiveDiagnosticsSummary() {
        List<Diagnostic> totalIssues = diagnosticsEngine.getAllDiagnostics();
        if (totalIssues.isEmpty()) {
            log(LogLevel.SUCCESS, "Automatic Check: Workspace is completely clear! No diagnostics reported.");
            return;
        }
        
        log(LogLevel.INFO, "--- Automatic Diagnostic Watcher Update (" + totalIssues.size() + " total items) ---");
        for (Diagnostic issue : totalIssues) {
            boolean isError = issue.getSeverity() != null && issue.getSeverity().toLowerCase(Locale.ROOT).contains("error");
            LogLevel structuralLevel = isError ? LogLevel.ERROR : LogLevel.WARN;
            log(structuralLevel, String.format("%s [Line %d]: %s", 
                issue.getFile(), issue.getLine(), issue.getMessage()));
        }
    }

    /**
     * Initializes the context menu for the console logging panel.
     */
    private void setupContextMenu() {
        JPopupMenu popup = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (palette != null) {
                    // Updated to match your exact Popup palette mappings:
                    g2.setColor(palette.getPopupBackground());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    
                    g2.setColor(palette.getPopupBorder());
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.dispose();
            }
        };
        popup.setOpaque(false);
        popup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JMenuItem editSettings       = createModernMenuItem("Edit Settings");
        JMenuItem editSessions       = createModernMenuItem("Edit Sessions");
        JMenuItem editWhitelist      = createModernMenuItem("Edit Python Modules Whitelist");
        JMenuItem editTcommand       = createModernMenuItem("Edit Trusted Commands");
        JMenuItem copy               = createModernMenuItem("Copy Selection");
        JMenuItem copyLog            = createModernMenuItem("Copy Full Log");
        JMenuItem killIntellisense   = createModernMenuItem("Restart Clangd LSP Server");
        JMenuItem rebuildRootBridge  = createModernMenuItem("Rebuild ROOT Bridge");
        JMenuItem clearLog           = createModernMenuItem("Clear Log View");
        
        // Code Analysis, Formatting, and Refactoring Actions
        JMenuItem formatFile         = createModernMenuItem("Format Active Source File");
        JMenuItem checkFormat        = createModernMenuItem("Check Format (Dry-Run)");
        
        // New interactive checkbox item inside the menu structure itself
        JCheckBoxMenuItem verboseLogItem = new JCheckBoxMenuItem("Display Verbose [D]");
        
        // Inject our isolated, premium flat-styled UI delegate manager cleanly
        verboseLogItem.setUI(new SPCheckBoxMenuItemUI());
        verboseLogItem.setOpaque(false);
        if (palette != null) {
            verboseLogItem.setForeground(palette.getTextPrimary());
        }
        
        JMenuItem renameSymbol       = createModernMenuItem("Rename Symbol (LSP)");
        JMenuItem viewAllDiagnostics = createModernMenuItem("Print Snapshot: Global Diagnostics");
        JMenuItem viewFileDiagnostics = createModernMenuItem("Print Snapshot: Active File Diagnostics");

        JSeparator sep = new JSeparator() {
            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(palette.getPopupBorder());
                g2.drawLine(6, 0, getWidth() - 6, 0);
                g2.dispose();
            }
        };

        // Core UI Actions
        copy.addActionListener(e -> logArea.copy());
        copyLog.addActionListener(e -> {
            String allText = logArea.getText();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(allText), null);
        });
        clearLog.addActionListener(e -> logArea.setText(""));

        // Configuration Hooks
        editWhitelist.addActionListener(e -> launchInternalEditorAsync("Edit Python Modules Whitelist", "config/pyconfigparam.src"));
        editTcommand.addActionListener(e -> launchInternalEditorAsync("Edit Trusted Commands", "config/trusted_commands.src"));
        editSettings.addActionListener(e -> SettingsEditorLauncher.open(Path.of("settings.conf")));

        // Refactoring and Formatting Pipeline Execution Mappings
        formatFile.addActionListener(e -> triggerActiveFileFormatting(false)); 
        checkFormat.addActionListener(e -> triggerActiveFileFormatting(true));   
        renameSymbol.addActionListener(e -> triggerActiveSymbolRename());

        // Sync local field reference so the execution worker thread knows its active state
        verboseLogItem.addActionListener(e -> {
            if (this.verboseFormatCheck != null) {
                this.verboseFormatCheck.setSelected(verboseLogItem.isSelected());
            }
        });

        killIntellisense.addActionListener(e -> {
            if (intellisenseBackend.isRunning()) {
                stopCppIntellisense();
            } else {
                log(LogLevel.INFO, "No active language server found to cycle.");
            }
        });

        rebuildRootBridge.addActionListener(e -> {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    // Triggers the compiler with explicit user logging feedback
                    com.sphere.core.rootbackend.RootBridgeCompiler.forceRebuildBridge(settings);
                    return null;
                }
            }.execute();
        });

        editSessions.addActionListener(e -> {
            File sessionDir = new File("sessions/"); 
            File[] sessions = sessionDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".txt"));

            if (sessions == null || sessions.length == 0) {
                JOptionPane.showMessageDialog(this, "No active log sessions discovered inside terminal storage.", "Information", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            SessionSelectionDialog dialog = new SessionSelectionDialog((Frame) SwingUtilities.getWindowAncestor(this));
            dialog.setVisible(true);

            String selected = dialog.getSelectedSession();
            if (selected != null) {
                launchInternalEditorAsync("Editing: " + selected, new File(sessionDir, selected).getPath());
            }
        });

        viewAllDiagnostics.addActionListener(e -> {
            List<Diagnostic> diagnostics = diagnosticsEngine.getAllDiagnostics();
            log(LogLevel.INFO, "--- Global Snapshot Reminder (" + diagnostics.size() + " issues) ---");
            for (Diagnostic diag : diagnostics) {
                boolean isError = diag.getSeverity() != null && diag.getSeverity().toLowerCase(Locale.ROOT).contains("error");
                log(isError ? LogLevel.ERROR : LogLevel.WARN, 
                    String.format("[%s : Line %d] %s", diag.getFile(), diag.getLine(), diag.getMessage()));
            }
        });

        viewFileDiagnostics.addActionListener(e -> {
            if (editorFrame == null || editorFrame.getEditor() == null) return;
            File currentFile = editorFrame.getEditor().getCurrentFile();
            if (currentFile == null) {
                log(LogLevel.WARN, "Reminder Check Canceled: No source file active in current viewport.");
                return;
            }
            
            List<Diagnostic> diagnostics = diagnosticsEngine.getDiagnosticsForFile(currentFile.getAbsolutePath());
            log(LogLevel.INFO, "--- Snapshot Reminder for " + currentFile.getName() + " (" + diagnostics.size() + " issues) ---");
            for (Diagnostic diag : diagnostics) {
                boolean isError = diag.getSeverity() != null && diag.getSeverity().toLowerCase(Locale.ROOT).contains("error");
                log(isError ? LogLevel.ERROR : LogLevel.WARN, 
                    String.format("[Line %d] %s", diag.getLine(), diag.getMessage()));
            }
        });
        
        // Structural Menu Assembly
        popup.add(copy);
        popup.add(copyLog);
        popup.add(clearLog);
        popup.add(createCustomSeparator());
        popup.add(formatFile);
        popup.add(checkFormat);
        popup.add(verboseLogItem); // Injected directly below styling operations context loops
        popup.add(renameSymbol);
        popup.add(viewAllDiagnostics);
        popup.add(viewFileDiagnostics);
        popup.add(killIntellisense);
        popup.add(rebuildRootBridge);
        popup.add(createCustomSeparator());
        popup.add(editSettings);
        popup.add(editSessions);
        popup.add(editWhitelist);
        popup.add(editTcommand);

        // Dynamically compute and enforce bounds constraints matching custom item text limits
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Ensure internal sync values reflect accurate tracking references prior to viewing layout updates
                if (ConsoleUI.this.verboseFormatCheck != null) {
                    verboseLogItem.setSelected(ConsoleUI.this.verboseFormatCheck.isSelected());
                }
                
                SwingUtilities.invokeLater(() -> {
                    int maxTextWidth = 160; 
                    Font font = FontLoader.getGlobalFont(Font.PLAIN, 12);
                    FontMetrics fm = popup.getFontMetrics(font);
                    
                    for (Component comp : popup.getComponents()) {
                        if (comp instanceof JMenuItem && comp.isVisible()) {
                            int textWidth = fm.stringWidth(((JMenuItem) comp).getText());
                            if (textWidth > maxTextWidth) {
                                maxTextWidth = textWidth;
                            }
                        }
                    }

                    int targetWidth = maxTextWidth + 32;
                    popup.setPreferredSize(new Dimension(targetWidth, popup.getPreferredSize().height));
                    popup.revalidate();
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        logArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e); }
            @Override
            public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e); }
            
            private void showMenu(MouseEvent e) {
                popup.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private JSeparator createCustomSeparator() {
            return new JSeparator() {
                @Override
                public void paint(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(palette.getPopupBorder());
                    g2.drawLine(6, 0, getWidth() - 6, 0);
                    g2.dispose();
                }
            };
    }

    private JMenuItem createModernMenuItem(String text) {
        JMenuItem item = new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                ButtonModel model = getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                if (model.isArmed() || model.isSelected()) {
                    // Using getButtonPressed() which perfectly maps to your BTN_PRESSED variable
                    g2.setColor(palette.getButtonPressed());
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(palette.getTextWhite());
                } else {
                    g2.setColor(palette.getTextPrimary());
                }
                
                g2.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                FontMetrics fm = g2.getFontMetrics();
                int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                
                g2.drawString(getText(), 12, textY);
                g2.dispose();
            }
        };
        
        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        return item;
    }

    private void launchInternalEditorAsync(String windowTitle, String relativeFilePath) {
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                File targetFile = new File(relativeFilePath);
                if (!targetFile.exists()) {
                    File parent = targetFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    targetFile.createNewFile();
                }
                return targetFile;
            }

            @Override
            protected void done() {
                try {
                    File fileToLoad = get();
                    if (editorFrame != null) {
                        editorFrame.openFileInternally(fileToLoad);
                    } else {
                        AppLogger.error("Failed executing inline click: Shared window context is missing.");
                    }
                } catch (Exception ex) {
                    AppLogger.error("Failed executing storage asset IO lookup: " + ex.getMessage());
                    JOptionPane.showMessageDialog(ConsoleUI.this,
                        "Could not modify file resources safely:\n" + relativeFilePath + "\nVerify access locks.",
                        "Storage Stream Violation Exception", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Triggers code formatting or style checking asynchronously, handling verbose diagnostic pipelines.
     * @param dryRun true to perform a non-mutative style validation check, false to format in-place.
     */
    private void triggerActiveFileFormatting(boolean dryRun) {
        if (this.editorFrame == null || this.editorFrame.getEditor() == null) {
            AppLogger.warn("Format aborted: No workspace view context available.");
            return;
        }

        java.io.File currentFile = this.editorFrame.getEditor().getCurrentFile();
        if (currentFile == null) {
            AppLogger.warn("Format aborted: No source file active in current viewport.");
            return;
        }

        // Dynamically query the state framework built directly into AppLogger
        boolean isVerbose = AppLogger.isDebugEnabled();

        if (dryRun) {
            String msg = "Checking formatting style (Dry-Run): " + currentFile.getName() + "...";
            if (isVerbose) AppLogger.debug(msg); else AppLogger.info(msg);
        } else {
            String msg = "Formatting source file in-place: " + currentFile.getName() + "...";
            if (isVerbose) AppLogger.debug(msg); else AppLogger.info(msg);
        }

        if (this.cppBackend == null) {
            AppLogger.error("Formatting failure: C++ backend execution engine is uninitialized.");
            return;
        }

        SwingWorker<CppFormatterEngine.FormatResult, Void> worker = new SwingWorker<>() {
            @Override
            protected CppFormatterEngine.FormatResult doInBackground() throws Exception {
                // Call the verbose wrapper to completely bypass missing engine getter errors safely
                return ConsoleUI.this.cppBackend.formatFileVerbose(currentFile.toPath(), dryRun);
            }

            @Override
            protected void done() {
                try {
                    CppFormatterEngine.FormatResult result = get();
                    
                    if (dryRun) {
                        // If verbose logging is enabled, append advanced process telemetry to the dialog view text
                        CppFormatterEngine.FormatResult finalResult = result;
                        if (isVerbose && !result.isSuccess()) {
                            String verboseMessage = String.format(
                                "[D] --- Process Debug Summary ---\n[D] Exit Code: %d\n[D] Output Length: %d characters\n\n%s",
                                result.getExitCode(), result.getStdout().length(), result.getStderr()
                            );
                            finalResult = new CppFormatterEngine.FormatResult(
                                result.isSuccess(), result.getStdout(), verboseMessage, result.getExitCode()
                            );
                        }

                        JFrame topFrame = (JFrame) SwingUtilities.getWindowAncestor(ConsoleUI.this);
                        FormatReportDialog dialog = new FormatReportDialog(topFrame, currentFile.getName(), finalResult);
                        dialog.setVisible(true);
                        
                        String successMsg = "Format check passed: " + currentFile.getName() + " matches style profiles.";
                        String failMsg = "Format check failed: Style deviations found in " + currentFile.getName();
                        
                        if (result.isSuccess()) {
                            if (isVerbose) AppLogger.debug(successMsg); else AppLogger.success(successMsg);
                        } else {
                            if (isVerbose) AppLogger.debug(failMsg); else AppLogger.warn(failMsg);
                        }
                    } else {
                        if (result.isSuccess()) {
                            String successMsg = "File successfully formatted. Reloading buffer...";
                            if (isVerbose) AppLogger.debug(successMsg); else AppLogger.success(successMsg);
                            ConsoleUI.this.editorFrame.openFileInternally(currentFile);
                        } else {
                            String errorMessage = isVerbose 
                                ? String.format("(Exit Code: %d) Fail details: %s", result.getExitCode(), result.getStderr()) 
                                : result.getStderr();
                            AppLogger.error("Formatting engine failure: " + errorMessage);
                        }
                    }
                } catch (Exception ex) {
                    String exMsg = "Formatting execution exception: " + ex.getMessage();
                    if (isVerbose) AppLogger.debug(exMsg); else AppLogger.error(exMsg);
                }
            }
        };
        worker.execute();
    }

    /**
     * Prompts the user for a new variable identifier and requests an LSP refactor via Clangd.
     */
    private void triggerActiveSymbolRename() {
        if (!intellisenseBackend.isRunning()) {
            log(LogLevel.WARN, "Refactor aborted: Clangd LSP server is not actively running.");
            return;
        }

        if (editorFrame == null || editorFrame.getEditor() == null) return;
        var editor = editorFrame.getEditor();
        File currentFile = editor.getCurrentFile();
        if (currentFile == null) return;

        // Grab the active text selection or current caret offset position via the underlying TextPane component
        int caretPosition = editor.getEditorArea().getCaretPosition();

        // Prompt user input using a clean dialog layout
        String newName = JOptionPane.showInputDialog(this, 
            "Enter new identifier name for the selected symbol:", 
            "Refactor: Rename Symbol", 
            JOptionPane.QUESTION_MESSAGE);

        if (newName == null || newName.trim().isEmpty()) {
            log(LogLevel.INFO, "Rename refactoring canceled by user.");
            return;
        }

        log(LogLevel.INFO, String.format("Requesting symbol rename to '%s' via Clangd LSP channels...", newName.trim()));

        // Dispatch the payload request directly into the RefactorEngine and Clangd subprocesses
        SwingUtilities.invokeLater(() -> {
            try {
                // Forwards the textDocument/rename JSON-RPC payload directly to the running Clangd instance
                com.sphere.core.cpp.CppRefactorEngine.RefactorResult outcome = 
                    com.sphere.core.cpp.CppRefactorEngine.renameSymbol(
                        intellisenseBackend, 
                        currentFile.getAbsolutePath(), 
                        caretPosition, 
                        newName.trim()
                    );

                if (outcome.success()) {
                    log(LogLevel.SUCCESS, "LSP Refactor status: " + outcome.message());
                    editorFrame.openFileInternally(currentFile); // Refresh viewport content dynamically
                } else {
                    log(LogLevel.WARN, "Refactor rejected: " + outcome.message());
                }
            } catch (Exception ex) {
                log(LogLevel.ERROR, "Refactoring structural pipeline exception: " + ex.getMessage());
            }
        });
    }

    public static final class FormatReportDialog extends JDialog {
        public FormatReportDialog(Frame owner, String fileName, CppFormatterEngine.FormatResult result) {
            super(owner, "Format Check: " + fileName, true);
            setSize(600, 400);
            setLocationRelativeTo(owner);
            setLayout(new BorderLayout(10, 10));

            // Header panel featuring color-coded validation tracking
            JLabel statusLabel = new JLabel();
            statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
            statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

            if (result.isSuccess()) {
                statusLabel.setText("✅ File is properly formatted. No issues found!");
                statusLabel.setForeground(new Color(46, 139, 87)); // Sea Green
            } else {
                statusLabel.setText("❌ Formatting discrepancies or errors found (Exit Code: " + result.getExitCode() + ")");
                statusLabel.setForeground(new Color(178, 34, 34)); // Firebrick Red
            }
            add(statusLabel, BorderLayout.NORTH);

            // Text viewport displaying telemetry tracking arrays or style deviations
            JTextArea reportArea = new JTextArea();
            reportArea.setEditable(false);
            reportArea.setFont(new Font("Consolas", Font.PLAIN, 12));
            
            String reportText = result.isSuccess() ? "Everything looks perfect." : result.getStderr();
            if (reportText.isBlank() && !result.getStdout().isBlank()) {
                // Fallback strategy if target clang-format version pipes lint telemetry to stdout
                reportText = result.getStdout(); 
            }
            reportArea.setText(reportText);

            JScrollPane scrollPane = new JScrollPane(reportArea);
            scrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(10, 10, 10, 10),
                BorderFactory.createLineBorder(Color.GRAY)
            ));
            add(scrollPane, BorderLayout.CENTER);

            // Standard close execution block layout
            JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> dispose());
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(closeButton);
            add(buttonPanel, BorderLayout.SOUTH);
        }
    }
}