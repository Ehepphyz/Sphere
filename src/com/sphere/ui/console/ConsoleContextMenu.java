package com.sphere.ui.console;

import com.sphere.core.cpp.CppDiagnosticsEngine;
import com.sphere.core.cpp.CppDiagnosticsParser.Diagnostic;
import com.sphere.core.cpp.CppIntellisenseBackend;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.core.rootbackend.RootBridgeCompiler;
import com.sphere.ui.ConsoleUI.LogLevel;
import com.sphere.utils.SettingsManager;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The console context menu. Frequent actions stay one click away; the ROOT, LSP
 * and settings families sit in their own submenus.
 *
 * Every entry's state is recomputed when the menu opens rather than fixed at
 * construction: a menu built once could only ever describe the application as it
 * was at startup, which is why it used to offer a ROOT rebuild with ROOT_DIR empty.
 */
public final class ConsoleContextMenu {

    /** What the menu needs the console itself to do. */
    public interface Host {
        void log(LogLevel level, String message);
        void copySelection();
        void copyFullLog();
        void clearLogView();
        void formatActiveFile(boolean dryRun);
        void renameSymbol();
        void openConfigFile(String title, String relativePath);
        void openSettingsFile();
        void chooseSession();
        void startClangd(String executable, String compileCommandsDir);
        void stopClangd();
        File activeFile();
    }

    private final Host host;
    private final SettingsManager settings;
    private final CppDiagnosticsEngine diagnostics;
    private final BackendAvailability availability;

    private final JPopupMenu popup = ConsoleMenuFactory.popup();

    // Entries whose label or state depends on the application's live state.
    private final JMenuItem globalSnapshot = ConsoleMenuFactory.item("Global Snapshot");
    private final JMenuItem fileSnapshot   = ConsoleMenuFactory.item("Snapshot: Active File");
    private final JMenuItem copySnapshot   = ConsoleMenuFactory.item("Copy Snapshot to Clipboard");
    private final JMenuItem clearDiags     = ConsoleMenuFactory.item("Clear Diagnostics");
    private final JMenuItem formatFile     = ConsoleMenuFactory.item("Format Active Source File");
    private final JMenuItem checkFormat    = ConsoleMenuFactory.item("Check Format (Dry-Run)");
    private final JCheckBoxMenuItem verbose = ConsoleMenuFactory.check("Display Verbose [D]");

    private final JMenu lspMenu           = ConsoleMenuFactory.submenu("C++ / LSP");
    private final JMenuItem lspStatus     = ConsoleMenuFactory.status("Status: unknown");
    private final JMenuItem lspStart      = ConsoleMenuFactory.item("Start clangd");
    private final JMenuItem lspStop       = ConsoleMenuFactory.item("Stop clangd");
    private final JMenuItem lspRestart    = ConsoleMenuFactory.item("Restart clangd");
    private final JMenuItem renameSymbol  = ConsoleMenuFactory.item("Rename Symbol");

    private final JMenu rootMenu          = ConsoleMenuFactory.submenu("ROOT");
    private final JMenuItem rootStatus    = ConsoleMenuFactory.status("Status: unknown");
    private final JMenuItem rootStart     = ConsoleMenuFactory.item("Start ROOT Backend");
    private final JMenuItem rootStop      = ConsoleMenuFactory.item("Stop ROOT Backend");
    private final JMenuItem rootRebuild   = ConsoleMenuFactory.item("Rebuild ROOT Bridge");
    private final JMenuItem rootEnv       = ConsoleMenuFactory.item("Print ROOT Environment");

    public ConsoleContextMenu(Host host,
                              SettingsManager settings,
                              CppDiagnosticsEngine diagnostics,
                              CppIntellisenseBackend clangd) {
        this.host = host;
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.availability = new BackendAvailability(settings, clangd);

        buildLspMenu();
        buildRootMenu();
        assemble();
        wire();
    }

    /** Shows this menu on right-click over the given component. */
    public void installOn(JComponent target) {
        target.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
    }

    // ---- Assembly ----------------------------------------------------------

    private void buildLspMenu() {
        lspMenu.add(lspStatus);
        lspMenu.add(ConsoleMenuFactory.separator());
        lspMenu.add(lspStart);
        lspMenu.add(lspStop);
        lspMenu.add(lspRestart);
        lspMenu.add(ConsoleMenuFactory.separator());
        lspMenu.add(renameSymbol);
    }

    private void buildRootMenu() {
        rootMenu.add(rootStatus);
        rootMenu.add(ConsoleMenuFactory.separator());
        rootMenu.add(rootStart);
        rootMenu.add(rootStop);
        rootMenu.add(rootRebuild);
        rootMenu.add(ConsoleMenuFactory.separator());
        rootMenu.add(rootEnv);
    }

    private void assemble() {
        JMenuItem copy = ConsoleMenuFactory.item("Copy Selection");
        JMenuItem copyLog = ConsoleMenuFactory.item("Copy Full Log");
        JMenuItem clearLog = ConsoleMenuFactory.item("Clear Log View");
        copy.addActionListener(e -> host.copySelection());
        copyLog.addActionListener(e -> host.copyFullLog());
        clearLog.addActionListener(e -> host.clearLogView());

        JMenu settingsMenu = ConsoleMenuFactory.submenu("Settings");
        JMenuItem editSettings = ConsoleMenuFactory.item("settings.conf");
        JMenuItem editSessions = ConsoleMenuFactory.item("Sessions");
        JMenuItem editWhitelist = ConsoleMenuFactory.item("Python Modules Whitelist");
        JMenuItem editTrusted = ConsoleMenuFactory.item("Trusted Commands");
        editSettings.addActionListener(e -> host.openSettingsFile());
        editSessions.addActionListener(e -> host.chooseSession());
        editWhitelist.addActionListener(e ->
            host.openConfigFile("Edit Python Modules Whitelist", "config/pyconfigparam.src"));
        editTrusted.addActionListener(e ->
            host.openConfigFile("Edit Trusted Commands", "config/trusted_commands.src"));
        settingsMenu.add(editSettings);
        settingsMenu.add(editSessions);
        settingsMenu.add(editWhitelist);
        settingsMenu.add(editTrusted);

        popup.add(copy);
        popup.add(copyLog);
        popup.add(clearLog);
        popup.add(ConsoleMenuFactory.separator());
        popup.add(globalSnapshot);
        popup.add(fileSnapshot);
        popup.add(copySnapshot);
        popup.add(clearDiags);
        popup.add(ConsoleMenuFactory.separator());
        popup.add(formatFile);
        popup.add(checkFormat);
        popup.add(verbose);
        popup.add(ConsoleMenuFactory.separator());
        popup.add(lspMenu);
        popup.add(rootMenu);
        popup.add(settingsMenu);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refresh();
                ConsoleMenuFactory.fitWidth(popup);
                ConsoleMenuFactory.fitWidth(lspMenu.getPopupMenu());
                ConsoleMenuFactory.fitWidth(rootMenu.getPopupMenu());
                ConsoleMenuFactory.fitWidth(settingsMenu.getPopupMenu());
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    // ---- Wiring ------------------------------------------------------------

    private void wire() {
        formatFile.addActionListener(e -> host.formatActiveFile(false));
        checkFormat.addActionListener(e -> host.formatActiveFile(true));
        renameSymbol.addActionListener(e -> host.renameSymbol());

        // The old checkbox wrote into a JCheckBox field that was never assigned, so
        // it toggled nothing. The flag the rest of the code reads is AppLogger's.
        verbose.addActionListener(e ->
            com.sphere.utils.AppLogger.setDebugEnabled(verbose.isSelected()));

        globalSnapshot.addActionListener(e -> printSnapshot(null));
        fileSnapshot.addActionListener(e -> printSnapshot(host.activeFile()));
        copySnapshot.addActionListener(e -> {
            String text = snapshotText();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                   .setContents(new StringSelection(text), null);
            host.log(LogLevel.INFO, "Snapshot copied to clipboard.");
        });
        clearDiags.addActionListener(e -> {
            diagnostics.clearAll();
            host.log(LogLevel.INFO, "Diagnostics cleared.");
        });

        lspStart.addActionListener(e -> startClangd());
        lspStop.addActionListener(e -> host.stopClangd());
        lspRestart.addActionListener(e -> {
            host.stopClangd();
            startClangd();
        });

        rootStart.addActionListener(e -> new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    RootBackend.startShared(settings);
                    host.log(LogLevel.SUCCESS, "ROOT backend started.");
                } catch (Exception ex) {
                    host.log(LogLevel.ERROR, "ROOT backend did not start: " + ex.getMessage());
                }
                return null;
            }
        }.execute());

        rootStop.addActionListener(e -> {
            RootBackend.stopShared();
            host.log(LogLevel.INFO, "ROOT backend stopped.");
        });

        rootRebuild.addActionListener(e -> new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                RootBridgeCompiler.forceRebuildBridge(settings);
                return null;
            }
        }.execute());

        rootEnv.addActionListener(e -> new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                report("version", "--version");
                report("incdir", "--incdir");
                report("libdir", "--libdir");
                return null;
            }

            private void report(String label, String flag) {
                String value = RootBridgeCompiler.getRootConfigOutput(flag, settings);
                host.log(value == null || value.isBlank() ? LogLevel.WARN : LogLevel.INFO,
                         "ROOT " + label + ": "
                         + (value == null || value.isBlank() ? "unavailable" : value.trim()));
            }
        }.execute());
    }

    private void startClangd() {
        String executable = availability.clangdExecutable();
        if (executable == null) {
            host.log(LogLevel.ERROR, availability.clangdConfigured().reason());
            return;
        }
        host.startClangd(executable, availability.compileCommandsDir());
    }

    // ---- Live state --------------------------------------------------------

    /**
     * Recomputes every label and enabled state. An entry that cannot act is
     * disabled and carries the reason as its tooltip, which is what settings.conf
     * describes as a muted target.
     */
    private void refresh() {
        verbose.setSelected(com.sphere.utils.AppLogger.isDebugEnabled());

        int errors = diagnostics == null ? 0 : diagnostics.getErrorCount();
        int warnings = diagnostics == null ? 0 : diagnostics.getWarningCount();
        int total = errors + warnings;

        globalSnapshot.setText(total == 0
            ? "Global Snapshot (none)"
            : "Global Snapshot (" + plural(errors, "error") + ", " + plural(warnings, "warning") + ")");
        setState(globalSnapshot, total > 0, "No diagnostic has been collected yet.");

        File active = host.activeFile();
        int fileCount = active == null || diagnostics == null ? 0
                      : diagnostics.getDiagnosticsForFile(active.getAbsolutePath()).size();
        fileSnapshot.setText(active == null
            ? "Snapshot: Active File"
            : "Snapshot: " + active.getName() + " (" + fileCount + ")");
        setState(fileSnapshot, active != null && fileCount > 0,
                 active == null ? "No source file is open in the editor."
                                : "No diagnostic recorded for this file.");

        setState(copySnapshot, total > 0, "No diagnostic has been collected yet.");
        setState(clearDiags, total > 0, "No diagnostic has been collected yet.");
        setState(formatFile, active != null, "No source file is open in the editor.");
        setState(checkFormat, active != null, "No source file is open in the editor.");

        // clangd
        BackendAvailability.State clangd = availability.clangdConfigured();
        boolean clangdUp = availability.clangdRunning();
        lspStatus.setText(availability.clangdStatusLine());
        setState(lspStart, clangd.usable() && !clangdUp,
                 clangd.usable() ? "clangd is already running." : clangd.reason());
        setState(lspStop, clangdUp, "No language server is running.");
        setState(lspRestart, clangd.usable(), clangd.reason());
        setState(renameSymbol, clangdUp,
                 "Renaming a symbol needs clangd running.");
        setState(lspMenu, true, null);

        // ROOT
        BackendAvailability.State root = availability.rootConfigured();
        boolean rootUp = availability.rootRunning();
        rootStatus.setText(availability.rootStatusLine());
        setState(rootStart, root.usable() && !rootUp,
                 root.usable() ? "The ROOT backend is already running." : root.reason());
        setState(rootStop, rootUp, "The ROOT backend is not running.");
        setState(rootRebuild, root.usable(), root.reason());
        setState(rootEnv, root.usable(), root.reason());
        setState(rootMenu, true, null);
    }

    private static void setState(JMenuItem item, boolean enabled, String reasonWhenDisabled) {
        item.setEnabled(enabled);
        item.setToolTipText(enabled ? null : reasonWhenDisabled);
    }

    private static String plural(int count, String word) {
        return count + " " + word + (count == 1 ? "" : "s");
    }

    // ---- Diagnostics output ------------------------------------------------

    private void printSnapshot(File file) {
        if (diagnostics == null) {
            return;
        }
        List<Diagnostic> list = file == null
            ? diagnostics.getAllDiagnostics()
            : diagnostics.getDiagnosticsForFile(file.getAbsolutePath());
        host.log(LogLevel.INFO, file == null
            ? "--- Global snapshot (" + list.size() + ") ---"
            : "--- Snapshot for " + file.getName() + " (" + list.size() + ") ---");
        for (Diagnostic d : list) {
            host.log(isError(d) ? LogLevel.ERROR : LogLevel.WARN, format(d, file == null));
        }
    }

    private String snapshotText() {
        StringBuilder sb = new StringBuilder();
        if (diagnostics != null) {
            for (Diagnostic d : diagnostics.getAllDiagnostics()) {
                sb.append(format(d, true)).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private static boolean isError(Diagnostic d) {
        return d.getSeverity() != null
            && d.getSeverity().toLowerCase(Locale.ROOT).contains("error");
    }

    private static String format(Diagnostic d, boolean withFile) {
        return withFile
            ? String.format("[%s : Line %d] %s", d.getFile(), d.getLine(), d.getMessage())
            : String.format("[Line %d] %s", d.getLine(), d.getMessage());
    }
}
