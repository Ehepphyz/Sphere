package com.sphere.ui;

import com.sphere.core.python.PythonEnvService;
import com.sphere.core.python.env.ImportScanner;
import com.sphere.core.python.env.Pep440;
import com.sphere.core.python.env.PipRunner;
import com.sphere.core.python.env.PyAdvisor;
import com.sphere.core.python.env.PyEnv;
import com.sphere.core.python.env.PyInterpreters;
import com.sphere.core.python.env.PyPi;
import com.sphere.core.python.env.PyProbe;
import com.sphere.core.python.env.PySnapshots;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.AnimProgressBar;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.ui.pyenv.PyWidgets;
import com.sphere.utils.AppLogger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sphere's Python environment manager (:py settings).
 *
 * Every Python of the machine in one list, any of them opened in a third
 * of a second (one probe instead of a dozen pip runs), and PyPI asked in
 * parallel with a cache: what pip list --outdated says in sixteen seconds
 * is here in one, and more than it says. For each package the newest
 * version the environment can actually take without breaking what depends
 * on it, the known vulnerabilities of the installed version, its size, who
 * needs it. For the environment a health score and its findings, each with
 * its fix. Installs previewed by pip's resolver before they run, done by uv
 * when it is there; a snapshot before every change, to go back to; the
 * imports of a project checked against the environment.
 */
public class PyEnvManagerDialog extends JDialog {

    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    private final PyPi pypi = PyPi.get();
    private final PipRunner runner = new PipRunner();
    private final ExecutorService ops = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Sphere-PyEnv-Operation");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService loads = Executors.newVirtualThreadPerTaskExecutor();

    private volatile PyEnv env;
    private List<PyAdvisor.Finding> findings = List.of();
    private int loadGeneration;
    private String configured = "";

    // Header
    private final JComboBox<Object> interpreterBox = new JComboBox<>();
    private final JLabel envLine = new JLabel(" ");
    private final JLabel timing = new JLabel(" ");
    private final PyWidgets.Gauge gauge = new PyWidgets.Gauge();
    private final AnimProgressBar progress = new AnimProgressBar();
    private final JLabel activity = new JLabel(" ");
    private final JButton stopButton = new JButton("Stop");
    private boolean switching;

    // Packages
    private final PackageModel model = new PackageModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<PackageModel> sorter = new TableRowSorter<>(model);
    private final JTextField search = new JTextField(16);
    private final List<PyWidgets.Chip> chips = new ArrayList<>();
    private String filter = "All";
    private final JEditorPane details = html();
    private final JPanel detailActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final JCheckBox prereleases = new JCheckBox("Pre-releases");

    // Health: as wide as the view, never wider, so that each finding's Fix button stays in sight.
    private final JPanel findingsPanel = new TrackingPanel();
    private final JScrollPane findingsScroll = new JScrollPane(findingsPanel);
    private final JLabel healthTitle = new JLabel(" ");
    private final JTextArea importReport = new JTextArea();

    // Install
    private final JTextField spec = new JTextField(28);
    private final JEditorPane lookupCard = html();
    private final JComboBox<String> versionBox = new JComboBox<>();
    private final JCheckBox eager = new JCheckBox("Upgrade dependencies too");
    private final JCheckBox useUv = new JCheckBox("Use uv");
    private final JTextArea previewArea = new JTextArea(8, 40);

    // Imports
    private final JTextField folder = new JTextField(36);
    private final ImportModel importModel = new ImportModel();
    private final JTable importTable = new JTable(importModel);

    // Snapshots
    private final DefaultListModel<PySnapshots.Snapshot> snapshotModel = new DefaultListModel<>();
    private final JList<PySnapshots.Snapshot> snapshotList = new JList<>(snapshotModel);
    private final JTextArea diffArea = new JTextArea();

    // Console
    private final JTextPane console = new JTextPane();
    private final JTabbedPane tabs = new JTabbedPane();

    private static PyEnvManagerDialog shown;

    /**
     * Opens the manager (:py settings, the console's Settings menu), or brings
     * the one already open to the front: two managers on one environment would
     * run pip against each other.
     */
    public static void open() {
        SwingUtilities.invokeLater(() -> {
            if (shown != null && shown.isDisplayable()) {
                shown.setVisible(true);
                shown.toFront();
                shown.requestFocus();
                return;
            }
            shown = new PyEnvManagerDialog();
            shown.setVisible(true);
        });
    }

    public PyEnvManagerDialog() {
        setTitle("Python Environment Manager");
        com.sphere.utils.IconManager.applyAppIcon(this);
        setModal(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        tabs.addTab("Packages", packagesTab());
        tabs.addTab("Health", healthTab());
        tabs.addTab("Install", installTab());
        tabs.addTab("Imports", importsTab());
        tabs.addTab("Snapshots", snapshotsTab());
        tabs.addTab("Console", consoleTab());
        add(tabs, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1220, 820));
        setMinimumSize(new Dimension(900, 560));
        pack();
        setLocationRelativeTo(null);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                runner.cancel();
                ops.shutdownNow();
                loads.shutdownNow();
            }
        });
        configured = PythonEnvService.loadPythonExecFromConfig("settings.conf");
        useUv.setEnabled(PipRunner.uv() != null);
        useUv.setSelected(PipRunner.uv() != null);
        useUv.setToolTipText(PipRunner.uv() == null ? "uv is not installed (pip install uv, or astral.sh/uv): installs 10-100x faster"
            : "uv found: " + PipRunner.uv());
        discover();
    }

    /* ------------------------------------------------------------------ */
    /* Layout                                                              */
    /* ------------------------------------------------------------------ */

    private JPanel header() {
        final JPanel p = new JPanel(new BorderLayout(10, 4));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        final JPanel left = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 6);
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        final JLabel l = new JLabel("Interpreter");
        l.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        left.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        interpreterBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                final JLabel r = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof PyInterpreters.Found f) {
                    final boolean sphere = sameFile(f.path(), configured);
                    r.setText((sphere ? "★ " : "   ") + f.label() + (index >= 0 ? "    (" + f.origin() + ")" : ""));
                    r.setToolTipText(sphere ? "Sphere's Python (settings.conf PYTHON_EXEC)" : f.origin());
                }
                return r;
            }
        });
        interpreterBox.addActionListener(e -> {
            if (switching) return;
            final Object o = interpreterBox.getSelectedItem();
            if (o instanceof PyInterpreters.Found f) load(f.path());
        });
        left.add(interpreterBox, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(button("Rescan", "Look for every Python on this machine again", this::discover));
        buttons.add(button("Browse…", "Choose an interpreter by its file", this::browse));
        buttons.add(button("New venv…", "Create a virtual environment, with packages if you want", this::newVenv));
        buttons.add(button("Use for Sphere", "Make this interpreter Sphere's Python (PYTHON_EXEC in settings.conf)", this::useForSphere));
        final JButton more = button("More…", "Folders, caches, reports", null);
        more.addActionListener(e -> moreMenu().show(more, 0, more.getHeight()));
        buttons.add(more);
        left.add(buttons, c);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        envLine.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        left.add(envLine, c);
        c.gridy = 2;
        timing.setFont(FontLoader.getGlobalFont(Font.ITALIC, 10));
        timing.setForeground(palette.getTextSecondary());
        left.add(timing, c);
        p.add(left, BorderLayout.CENTER);
        final JPanel right = new JPanel(new BorderLayout());
        right.add(gauge, BorderLayout.CENTER);
        final JLabel h = new JLabel("health", JLabel.CENTER);
        h.setFont(FontLoader.getGlobalFont(Font.PLAIN, 10));
        h.setForeground(palette.getTextSecondary());
        right.add(h, BorderLayout.SOUTH);
        gauge.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                tabs.setSelectedIndex(1);
            }
        });
        gauge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JPanel footer() {
        final JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(4, 12, 6, 12));
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(160, 6));
        activity.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        activity.setForeground(palette.getTextSecondary());
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> runner.cancel());
        final JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        west.add(progress);
        p.add(west, BorderLayout.WEST);
        p.add(activity, BorderLayout.CENTER);
        final JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        east.add(stopButton);
        east.add(button("Close", null, this::dispose));
        p.add(east, BorderLayout.EAST);
        return p;
    }

    private JComponent packagesTab() {
        final JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        bar.add(new JLabel("Search"));
        search.setToolTipText("Name, summary or module");
        search.getDocument().addDocumentListener((SimpleDocumentListener) this::applyFilter);
        bar.add(search);
        final ButtonGroup group = new ButtonGroup();
        for (String f : new String[]{"All", "Updates", "Security", "Problems", "Installed by you", "Dependencies", "Orphans", "Largest"}) {
            final PyWidgets.Chip chip = new PyWidgets.Chip(f);
            chip.setSelected(f.equals("All"));
            chip.addActionListener(e -> {
                filter = f;
                applyFilter();
            });
            group.add(chip);
            chips.add(chip);
            bar.add(chip);
        }
        prereleases.setToolTipText("Count pre-releases (rc, beta) as updates");
        prereleases.addActionListener(e -> {
            if (env != null) enrich(env, loadGeneration);
        });
        bar.add(prereleases);
        p.add(bar, BorderLayout.NORTH);

        table.setRowSorter(sorter);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        table.getTableHeader().setFont(FontLoader.getGlobalFont(Font.BOLD, 11));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        final int[] widths = {26, 200, 90, 90, 90, 70, 72, 90, 360};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.setDefaultRenderer(Object.class, new Renderer());
        sorter.setComparator(2, versionOrder());
        sorter.setComparator(3, versionOrder());
        sorter.setComparator(4, versionOrder());
        sorter.setComparator(6, Comparator.comparingLong(o -> o instanceof Long l ? l : 0L));
        sorter.setComparator(5, Comparator.comparingInt(o -> o instanceof Pep440.Jump j ? -j.ordinal() : 1));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetails();
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                final int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                packageMenu().show(table, e.getX(), e.getY());
            }
        });
        details.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) open(e.getURL().toString());
        });
        final JPanel right = new JPanel(new BorderLayout());
        right.add(new JScrollPane(details), BorderLayout.CENTER);
        right.add(detailActions, BorderLayout.SOUTH);
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), right);
        split.setResizeWeight(0.62);
        split.setDividerLocation(760);
        p.add(split, BorderLayout.CENTER);
        final JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bottom.add(button("Upgrade selected (safe)", "Each selected package to the newest version every dependent accepts",
            () -> upgradeSelected(true)));
        bottom.add(button("Upgrade selected (latest)", "Each selected package to its latest version", () -> upgradeSelected(false)));
        bottom.add(button("Uninstall selected…", "With what nothing else needs any more", this::uninstallSelected));
        bottom.add(button("Refresh", "Read the environment again (PyPI from the cache)", () -> {
            if (env != null) load(env.executable);
        }));
        bottom.add(button("Refresh from PyPI", "Ask PyPI again, ignoring the cache", () -> {
            pypi.forget(true);
            if (env != null) load(env.executable);
        }));
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private JComponent healthTab() {
        final JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        final JPanel top = new JPanel(new BorderLayout());
        healthTitle.setFont(FontLoader.getGlobalFont(Font.BOLD, 14));
        top.add(healthTitle, BorderLayout.CENTER);
        final JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        b.add(button("Fix everything safe", "Security fixes, missing dependencies, leftovers: previewed first", this::fixAll));
        b.add(button("Deep check: import everything", "Import every installed module: what is broken, what is slow", this::deepCheck));
        b.add(button("Copy report", "The environment and its findings as Markdown, for an issue or a colleague", this::copyReport));
        top.add(b, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        findingsPanel.setLayout(new BoxLayout(findingsPanel, BoxLayout.Y_AXIS));
        importReport.setEditable(false);
        importReport.setFont(FontLoader.getTerminalFont(Font.PLAIN, 11));
        importReport.setRows(8);
        findingsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        findingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        final javax.swing.Timer relayout = new javax.swing.Timer(150, e -> {
            if (env != null) refreshFindings();
        });
        relayout.setRepeats(false);
        findingsScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                relayout.restart();
            }
        });
        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, findingsScroll, new JScrollPane(importReport));
        split.setResizeWeight(0.75);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JComponent installTab() {
        final JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        final JPanel form = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Package(s)"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        spec.setToolTipText("uproot   ·   numpy<2   ·   awkward==2.6.4 hist   ·   ./local/path   ·   git+https://...");
        spec.addActionListener(e -> lookup());
        form.add(spec, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        final JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        b.add(button("Look up", "What PyPI says of it: versions, Python support, vulnerabilities, look-alike names", this::lookup));
        versionBox.setToolTipText("The version to install");
        versionBox.setPrototypeDisplayValue("latest (2026.10.10.post1)");
        b.add(versionBox);
        b.add(button("Preview changes", "pip's resolver says what would be installed, upgraded or downgraded", () -> install(true)));
        b.add(button("Install", null, () -> install(false)));
        form.add(b, c);
        c.gridx = 1;
        c.gridy = 1;
        final JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        eager.setToolTipText("--upgrade --upgrade-strategy eager: also bring the dependencies to their newest");
        opts.add(eager);
        opts.add(useUv);
        opts.add(button("requirements.txt…", "Install every package of a requirements file", this::installRequirements));
        form.add(opts, c);
        p.add(form, BorderLayout.NORTH);
        final JPanel presets = new JPanel();
        presets.setLayout(new BoxLayout(presets, BoxLayout.Y_AXIS));
        presets.setBorder(BorderFactory.createTitledBorder("Sets of packages"));
        for (Map.Entry<String, List<String>> e : PyAdvisor.PRESETS.entrySet()) {
            final JButton pb = button(e.getKey(), String.join(" ", e.getValue()), () -> {
                spec.setText(String.join(" ", e.getValue()));
                lookup();
            });
            pb.setAlignmentX(Component.LEFT_ALIGNMENT);
            pb.setMaximumSize(new Dimension(260, 30));
            presets.add(pb);
            presets.add(Box.createVerticalStrut(3));
        }
        lookupCard.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) open(e.getURL().toString());
        });
        previewArea.setEditable(false);
        previewArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        final JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(lookupCard), new JScrollPane(previewArea));
        center.setResizeWeight(0.6);
        p.add(center, BorderLayout.CENTER);
        p.add(new JScrollPane(presets), BorderLayout.EAST);
        return p;
    }

    private JComponent importsTab() {
        final JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        bar.add(new JLabel("Scripts and notebooks in"));
        folder.setText(Path.of("WorkSpace").toAbsolutePath().toString());
        bar.add(folder);
        bar.add(button("Choose…", null, () -> {
            final JFileChooser ch = new JFileChooser(folder.getText());
            ch.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folder.setText(ch.getSelectedFile().getAbsolutePath());
                scanImports();
            }
        }));
        bar.add(button("Scan", "Every import of the .py and .ipynb files, set against this environment", this::scanImports));
        bar.add(button("Install the missing ones", null, () -> {
            final List<String> specs = new ArrayList<>();
            for (ImportScanner.Use u : importModel.rows) if (u.missing()) specs.add(u.distribution());
            if (!specs.isEmpty()) installSpecs(specs, List.of(), "Install what the project imports", true);
        }));
        p.add(bar, BorderLayout.NORTH);
        importTable.setRowHeight(22);
        importTable.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        importTable.setDefaultRenderer(Object.class, new ImportRenderer());
        importTable.setAutoCreateRowSorter(true);
        p.add(new JScrollPane(importTable), BorderLayout.CENTER);
        return p;
    }

    private JComponent snapshotsTab() {
        final JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bar.add(button("Take snapshot", "What is installed now, kept to come back to", () -> {
            if (env == null) return;
            try {
                PySnapshots.save(env, "taken by hand");
                refreshSnapshots();
            } catch (IOException e) {
                log("snapshot failed: " + e.getMessage(), palette.getLogErrorText());
            }
        }));
        bar.add(button("Compare with now", null, this::compareSnapshot));
        bar.add(button("Restore…", "Bring back the versions of the snapshot, remove what came after", this::restoreSnapshot));
        bar.add(button("Export requirements (pinned)…", "pip freeze: every package at its version", () -> exportRequirements(false)));
        bar.add(button("Export requirements (direct)…", "Only what you installed on purpose; pip resolves the rest",
            () -> exportRequirements(true)));
        p.add(bar, BorderLayout.NORTH);
        snapshotList.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        snapshotList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                final JLabel r = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof PySnapshots.Snapshot s) r.setText(s.label());
                return r;
            }
        });
        snapshotList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) compareSnapshot();
        });
        diffArea.setEditable(false);
        diffArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(snapshotList), new JScrollPane(diffArea));
        split.setResizeWeight(0.45);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JComponent consoleTab() {
        final JPanel p = new JPanel(new BorderLayout());
        console.setEditable(false);
        console.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        console.setBackground(palette.getTerminalBackground());
        console.setForeground(palette.getTerminalForeground());
        p.add(new JScrollPane(console), BorderLayout.CENTER);
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(button("Clear", null, () -> console.setText("")));
        bar.add(button("Copy", null, () -> Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(console.getText()), null)));
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* Interpreters and loading                                            */
    /* ------------------------------------------------------------------ */

    private void discover() {
        busy(true, "Looking for Pythons on this machine…");
        loads.submit(() -> {
            final List<PyInterpreters.Found> found = PyInterpreters.discover(configured);
            SwingUtilities.invokeLater(() -> {
                final Object was = interpreterBox.getSelectedItem();
                switching = true;
                interpreterBox.removeAllItems();
                for (PyInterpreters.Found f : found) interpreterBox.addItem(f);
                PyInterpreters.Found select = null;
                for (PyInterpreters.Found f : found) {
                    if (was instanceof PyInterpreters.Found w && sameFile(w.path(), f.path())) select = f;
                }
                if (select == null) {
                    for (PyInterpreters.Found f : found) if (sameFile(f.path(), configured)) select = f;
                }
                if (select == null && !found.isEmpty()) select = found.get(0);
                interpreterBox.setSelectedItem(select);
                switching = false;
                busy(false, found.size() + " Python interpreter" + (found.size() == 1 ? "" : "s") + " found");
                if (select != null) load(select.path());
                else envLine.setText("No Python found: Browse… for one, or install Python.");
            });
        });
    }

    private void browse() {
        final JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Python interpreter");
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final Path exe = ch.getSelectedFile().toPath();
        final PyInterpreters.Found f = new PyInterpreters.Found(exe.toString(), "", PyInterpreters.kind(exe), "chosen");
        switching = true;
        interpreterBox.insertItemAt(f, 0);
        interpreterBox.setSelectedItem(f);
        switching = false;
        load(exe.toString());
    }

    /** Probes an interpreter and shows it, then asks PyPI. */
    private void load(String executable) {
        final int generation = ++loadGeneration;
        busy(true, "Reading " + executable + " …");
        loads.submit(() -> {
            try {
                final PyEnv e = PyProbe.run(executable);
                SwingUtilities.invokeLater(() -> {
                    if (generation != loadGeneration) return;
                    env = e;
                    model.set(e);
                    refreshHeader();
                    refreshFindings();
                    refreshCounts();
                    refreshSnapshots();
                    showDetails();
                    enrich(e, generation);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (generation != loadGeneration) return;
                    env = null;
                    model.set(null);
                    envLine.setText("Not a working Python: " + ex.getMessage());
                    gauge.setScore(-1);
                    busy(false, "");
                });
            }
        });
    }

    /** PyPI for every package of the environment, the table filled in as answers come. */
    private void enrich(PyEnv e, int generation) {
        busy(true, "Asking PyPI about " + e.packages.size() + " packages…");
        final long t0 = System.nanoTime();
        final int r0 = pypi.requests();
        loads.submit(() -> {
            try {
                PyAdvisor.enrichNow(e, pypi, prereleases.isSelected(), p -> SwingUtilities.invokeLater(() -> {
                    if (generation == loadGeneration) model.updated(p);
                }));
            } catch (RuntimeException ex) {
                AppLogger.warn("[py] PyPI: " + ex.getMessage());
            }
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            SwingUtilities.invokeLater(() -> {
                if (generation != loadGeneration) return;
                model.fireTableDataChanged();
                refreshFindings();
                refreshCounts();
                showDetails();
                final int asked = pypi.requests() - r0;
                timing.setText(String.format(Locale.ROOT, "probe %.2f s  ·  PyPI %.2f s (%s)  ·  %s",
                    e.probeMillis / 1000.0, ms / 1000.0, asked == 0 ? "all from the cache" : asked + " requests via " + pypi.transport(),
                    pypi.offline() ? "OFFLINE: what the cache knew" : "pip list --outdated takes ~10-20 s"));
                busy(false, "");
            });
        });
    }

    private void refreshHeader() {
        final PyEnv e = env;
        if (e == null) return;
        final String sphere = sameFile(e.executable, configured) ? "  ·  ★ Sphere's Python" : "";
        envLine.setText(String.format(Locale.ROOT, "Python %s (%s, %d-bit)  ·  %s  ·  %d packages  ·  %s  ·  pip %s%s", e.version,
            e.implementation, e.bits, e.kind(), e.packages.size(), PyAdvisor.size(e.totalSize()), e.pip == null ? "missing" : e.pip,
            sphere));
        envLine.setToolTipText(e.executable + "  —  prefix " + e.prefix);
    }

    private void refreshCounts() {
        final PyEnv e = env;
        for (PyWidgets.Chip c : chips) {
            int n = 0;
            if (e != null) for (PyEnv.Pkg p : e.packages.values()) if (matches(c.getText(), p)) n++;
            c.setCount(c.getText().equals("Largest") ? -1 : n);
        }
    }

    private void refreshFindings() {
        final PyEnv e = env;
        findingsPanel.removeAll();
        if (e == null) {
            findings = List.of();
            gauge.setScore(-1);
            return;
        }
        findings = PyAdvisor.findings(e);
        final int score = PyAdvisor.score(findings);
        gauge.setScore(score);
        long problems = findings.stream().filter(f -> f.severity().ordinal() <= PyAdvisor.Severity.MEDIUM.ordinal()).count();
        healthTitle.setText("Health " + score + "/100  ·  " + (problems == 0 ? "nothing serious" : problems + " problem"
            + (problems == 1 ? "" : "s") + " to fix") + "  ·  " + e.conflicts.size() + " unmet requirement"
            + (e.conflicts.size() == 1 ? "" : "s"));
        healthTitle.setForeground(PyWidgets.scoreColor(score));
        if (findings.isEmpty()) {
            final JLabel ok = new JLabel("No problem found: requirements met, no known vulnerability, no leftover.");
            ok.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
            findingsPanel.add(ok);
        }
        for (PyAdvisor.Finding f : findings) findingsPanel.add(findingRow(f));
        findingsPanel.add(Box.createVerticalGlue());
        findingsPanel.revalidate();
        findingsPanel.repaint();
    }

    private JComponent findingRow(PyAdvisor.Finding f) {
        final JPanel row = new JPanel(new BorderLayout(10, 2));
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBorder()),
            BorderFactory.createEmptyBorder(8, 6, 8, 6)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        final PyWidgets.Badge badge = new PyWidgets.Badge(f.severity().name(), PyWidgets.severityColor(f.severity()));
        final JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        west.setPreferredSize(new Dimension(86, 24));
        west.add(badge);
        row.add(west, BorderLayout.WEST);
        // The detail wraps within the row, so that the Fix button stays in view.
        String detail = f.detail();
        if (detail.length() > 320) detail = detail.substring(0, 317) + "…";
        final int view = findingsScroll.getViewport().getWidth();
        final int width = Math.max(260, (view > 0 ? view : 1100) - 360);
        final JLabel text = new JLabel("<html><div style='width:" + width + "px'><b>" + esc(f.title()) + "</b><br><span style='color:"
            + hex(palette.getTextSecondary()) + "'>" + esc(detail) + "</span></div></html>");
        text.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        row.add(text, BorderLayout.CENTER);
        if (f.fix().kind() != PyAdvisor.FixKind.NONE) {
            final JButton fix = button(f.fix().label(), String.join(" ", f.fix().targets()), () -> applyFix(f));
            final JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
            east.add(fix);
            row.add(east, BorderLayout.EAST);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 6));
        return row;
    }

    /* ------------------------------------------------------------------ */
    /* The table                                                           */
    /* ------------------------------------------------------------------ */

    private final class PackageModel extends AbstractTableModel {
        final List<PyEnv.Pkg> rows = new ArrayList<>();
        PyEnv of;
        final String[] cols = {"", "Package", "Installed", "Latest", "Safe target", "Update", "Size", "Released", "Summary"};

        void set(PyEnv e) {
            of = e;
            rows.clear();
            if (e != null) rows.addAll(e.packages.values());
            rows.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
            fireTableDataChanged();
        }

        void updated(PyEnv.Pkg p) {
            final int i = rows.indexOf(p);
            if (i >= 0) fireTableRowsUpdated(i, i);
        }

        PyEnv.Pkg at(int viewRow) {
            final int m = table.convertRowIndexToModel(viewRow);
            return m >= 0 && m < rows.size() ? rows.get(m) : null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            final PyEnv.Pkg p = rows.get(r);
            return switch (c) {
                case 0 -> "";
                case 1 -> p.name;
                case 2 -> p.version;
                case 3 -> p.latest == null ? (p.notOnPyPi ? "not on PyPI" : p.project == null ? "…" : p.version) : p.latest;
                case 4 -> p.safeTarget == null ? "" : p.safeTarget;
                case 5 -> p.outdated() ? p.jump : Pep440.Jump.NONE;
                case 6 -> p.size;
                case 7 -> {
                    final PyPi.Published pub = p.project == null || p.latest == null ? null : p.project.find(p.latest);
                    yield pub == null ? "" : PyAdvisor.ago(pub.date());
                }
                default -> p.summary;
            };
        }
    }

    private final class Renderer extends PyWidgets.Cell {
        private final PyWidgets.DotCell dot = new PyWidgets.DotCell();
        private final PyWidgets.BadgeCell badge = new PyWidgets.BadgeCell();

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            final PyEnv.Pkg p = model.at(row);
            final int c = t.convertColumnIndexToModel(col);
            if (p == null) return super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            final PyEnv e = model.of;
            switch (c) {
                case 0 -> {
                    dot.getTableCellRendererComponent(t, "", sel, focus, row, col);
                    dot.dot(e == null ? null : PyWidgets.stateColor(e, p));
                    dot.setToolTipText(state(e, p));
                    return dot;
                }
                case 5 -> {
                    final Pep440.Jump j = (Pep440.Jump) v;
                    badge.getTableCellRendererComponent(t, j == Pep440.Jump.NONE ? "" : j.name().toLowerCase(Locale.ROOT), sel, focus, row, col);
                    badge.badge(j == Pep440.Jump.NONE ? null : PyWidgets.jumpColor(j));
                    return badge;
                }
                case 6 -> {
                    final Component r = super.getTableCellRendererComponent(t, PyAdvisor.size((Long) v), sel, focus, row, col);
                    ((JLabel) r).setHorizontalAlignment(JLabel.RIGHT);
                    return r;
                }
                default -> {
                    final JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                    if (c == 1) {
                        l.setFont(t.getFont().deriveFont(p.requested ? Font.BOLD : Font.PLAIN));
                        l.setToolTipText(p.requested ? "installed by you" : "installed as a dependency of " + String.join(", ", p.requiredBy));
                    }
                    if (c == 4 && p.outdated() && p.safeTarget == null && !sel) {
                        l.setText("held back");
                        l.setForeground(palette.getPyMinor());
                        l.setToolTipText(p.safeReason);
                    } else if (c == 4 && !p.safeReason.isEmpty()) {
                        l.setToolTipText(p.safeReason);
                    }
                    if (c == 3 && p.outdated() && !sel) l.setForeground(PyWidgets.jumpColor(p.jump));
                    if (c == 8 && !sel) l.setForeground(palette.getTextSecondary());
                    return l;
                }
            }
        }
    }

    private static Comparator<Object> versionOrder() {
        return (a, b) -> {
            final Pep440.Version x = Pep440.parse(String.valueOf(a));
            final Pep440.Version y = Pep440.parse(String.valueOf(b));
            if (x == null || y == null) return String.valueOf(a).compareTo(String.valueOf(b));
            return x.compareTo(y);
        };
    }

    private String state(PyEnv e, PyEnv.Pkg p) {
        final List<String> s = new ArrayList<>();
        if (p.vulnerabilities() > 0) s.add(p.vulnerabilities() + " known vulnerabilities");
        if (p.yanked()) s.add("yanked from PyPI");
        if (e != null && PyWidgets.conflicted(e, p)) s.add("a requirement is not met");
        if (p.outdated()) s.add(p.jump.name().toLowerCase(Locale.ROOT) + " update to " + p.latest);
        if (p.notOnPyPi) s.add("not on PyPI");
        return s.isEmpty() ? "up to date" : String.join(" · ", s);
    }

    private boolean matches(String chip, PyEnv.Pkg p) {
        return switch (chip) {
            case "Updates" -> p.outdated();
            case "Security" -> p.vulnerabilities() > 0 || p.yanked();
            case "Problems" -> env != null && PyWidgets.conflicted(env, p) || p.vulnerabilities() > 0 || p.yanked();
            case "Installed by you" -> p.requested;
            case "Dependencies" -> !p.requested;
            case "Orphans" -> p.orphan();
            default -> true;
        };
    }

    private void applyFilter() {
        final String q = search.getText().strip().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends PackageModel, ? extends Integer> e) {
                final PyEnv.Pkg p = model.rows.get(e.getIdentifier());
                if (!matches(filter, p)) return false;
                if (q.isEmpty()) return true;
                return p.name.toLowerCase(Locale.ROOT).contains(q) || p.summary.toLowerCase(Locale.ROOT).contains(q)
                    || p.top.stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(q));
            }
        });
        if (filter.equals("Largest")) {
            sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(6, javax.swing.SortOrder.DESCENDING)));
        }
    }

    private List<PyEnv.Pkg> selected() {
        final List<PyEnv.Pkg> out = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            final PyEnv.Pkg p = model.at(r);
            if (p != null) out.add(p);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Details                                                             */
    /* ------------------------------------------------------------------ */

    private void showDetails() {
        detailActions.removeAll();
        final List<PyEnv.Pkg> sel = selected();
        final PyEnv e = env;
        if (e == null || sel.isEmpty()) {
            details.setText(page(e == null ? "<p>Choose an interpreter.</p>" : overview(e)));
            detailActions.revalidate();
            detailActions.repaint();
            return;
        }
        if (sel.size() > 1) {
            long size = 0;
            for (PyEnv.Pkg p : sel) size += p.size;
            details.setText(page("<h2>" + sel.size() + " packages</h2><p>" + PyAdvisor.size(size) + " on disk</p>"));
            detailActions.add(button("Upgrade (safe)", null, () -> upgradeSelected(true)));
            detailActions.add(button("Uninstall…", null, this::uninstallSelected));
            detailActions.revalidate();
            detailActions.repaint();
            return;
        }
        final PyEnv.Pkg p = sel.get(0);
        details.setText(page(describe(e, p)));
        details.setCaretPosition(0);
        if (p.safeTarget != null) {
            detailActions.add(button("Safe upgrade → " + p.safeTarget, p.safeReason.isEmpty()
                ? "the latest, and every dependent accepts it" : p.safeReason,
                () -> installSpecs(List.of(p.name + "==" + p.safeTarget), List.of(), "Upgrade " + p.name + " to " + p.safeTarget, true)));
        }
        if (p.latest != null && p.outdated() && !p.latest.equals(p.safeTarget)) {
            detailActions.add(button("Latest " + p.latest, "may break: " + p.safeReason,
                () -> installSpecs(List.of(p.name + "==" + p.latest), List.of(), "Upgrade " + p.name + " to " + p.latest, true)));
        }
        if (p.project != null && !p.project.recent.isEmpty()) {
            final JComboBox<String> versions = new JComboBox<>();
            for (PyPi.Published v : p.project.sorted(true)) versions.addItem(v.version());
            versions.setSelectedItem(p.version);
            versions.setToolTipText("Install another version (downgrade, pre-release)");
            detailActions.add(versions);
            detailActions.add(button("Install this version", null, () -> {
                final Object v = versions.getSelectedItem();
                if (v != null && !v.equals(p.version)) {
                    installSpecs(List.of(p.name + "==" + v), List.of(), "Install " + p.name + " " + v, true);
                }
            }));
        }
        detailActions.add(button("Reinstall", "Same version, files restored", () -> runPip("Reinstall " + p.name, "install",
            List.of("--force-reinstall", "--no-deps", p.name + "==" + p.version))));
        detailActions.add(button("Uninstall…", null, () -> uninstall(List.of(p))));
        detailActions.add(button("Folder", "Open where it is installed", () -> openFolder(p)));
        detailActions.revalidate();
        detailActions.repaint();
    }

    private String overview(PyEnv e) {
        final StringBuilder b = new StringBuilder("<h2>").append(esc("Python " + e.version)).append("</h2>");
        b.append("<p>").append(esc(e.executable)).append("<br>").append(esc(e.kind())).append(" · prefix ").append(esc(e.prefix))
            .append("</p>");
        final List<PyEnv.Pkg> big = new ArrayList<>(e.packages.values());
        big.sort(Comparator.comparingLong((PyEnv.Pkg p) -> p.size).reversed());
        b.append("<h3>Largest packages</h3><table>");
        for (int i = 0; i < Math.min(8, big.size()); i++) {
            b.append("<tr><td>").append(esc(big.get(i).name)).append("</td><td align='right'>").append(PyAdvisor.size(big.get(i).size))
                .append("</td></tr>");
        }
        b.append("</table><p class='dim'>Select a package for its details; right-click for its actions.</p>");
        return b.toString();
    }

    private String describe(PyEnv e, PyEnv.Pkg p) {
        final StringBuilder b = new StringBuilder();
        b.append("<h2>").append(esc(p.name)).append(" <span class='dim'>").append(esc(p.version)).append("</span></h2>");
        if (!p.summary.isEmpty()) b.append("<p>").append(esc(p.summary)).append("</p>");
        b.append("<p>");
        if (p.vulnerabilities() > 0) b.append(badge(p.vulnerabilities() + " vulnerabilities", palette.getPyDanger()));
        if (p.yanked()) b.append(badge("yanked", palette.getPyDanger()));
        if (PyWidgets.conflicted(e, p)) b.append(badge("requirement not met", palette.getPyDanger()));
        if (p.outdated()) b.append(badge(p.jump.name().toLowerCase(Locale.ROOT) + " update", PyWidgets.jumpColor(p.jump)));
        b.append(badge(p.requested ? "installed by you" : "dependency", palette.getPyInfo()));
        if (p.editable) b.append(badge("editable", palette.getPyPatch()));
        if (p.orphan()) b.append(badge("orphan", palette.getPyMinor()));
        b.append("</p><table>");
        if (p.latest != null) {
            final PyPi.Published pub = p.project.find(p.latest);
            row(b, "Latest", p.latest + (pub == null || pub.date() == null ? "" : "  (" + PyAdvisor.ago(pub.date()) + ")"));
        }
        if (p.outdated()) {
            row(b, "Safe target", p.safeTarget == null ? "held back — " + p.safeReason
                : p.safeTarget + (p.safeReason.isEmpty() ? "" : "  — " + p.safeReason));
        }
        final PyPi.Published inst = p.project == null ? null : p.project.find(p.version);
        if (inst != null && inst.date() != null) row(b, "Installed version", "released " + PyAdvisor.ago(inst.date()));
        row(b, "Size", PyAdvisor.size(p.size) + " · " + p.files + " files");
        if (!p.top.isEmpty()) row(b, "Imported as", String.join(", ", p.top));
        if (!p.requiresPython.isEmpty()) row(b, "Requires Python", p.requiresPython);
        if (!p.license.isEmpty()) row(b, "License", p.license);
        if (!p.installer.isEmpty()) row(b, "Installed by", p.installer);
        row(b, "Location", p.path);
        b.append("</table>");
        if (!p.home.isEmpty()) b.append("<p><a href='").append(esc(p.home)).append("'>").append(esc(p.home)).append("</a></p>");
        b.append("<p><a href='https://pypi.org/project/").append(esc(p.name)).append("/'>PyPI page</a></p>");
        if (p.vulnerabilities() > 0) {
            b.append("<h3>Vulnerabilities of ").append(esc(p.version)).append("</h3><ul>");
            for (PyPi.Vulnerability v : p.installedRelease.vulnerabilities) {
                b.append("<li><a href='").append(esc(v.link().isEmpty() ? "https://osv.dev/vulnerability/" + v.id() : v.link())).append("'>")
                    .append(esc(v.label())).append("</a> ").append(esc(v.summary()));
                if (!v.fixedIn().isEmpty()) b.append(" <span class='dim'>— fixed in ").append(esc(String.join(", ", v.fixedIn()))).append("</span>");
                b.append("</li>");
            }
            b.append("</ul>");
        }
        final List<PyEnv.Req> applying = new ArrayList<>();
        for (PyEnv.Req r : p.requires) if (r.applies) applying.add(r);
        if (!applying.isEmpty()) {
            b.append("<h3>Requires</h3><ul>");
            for (PyEnv.Req r : applying) {
                final PyEnv.Pkg d = e.packages.get(r.name);
                final boolean ok = d != null && (r.spec.isEmpty() || Pep440.specifier(r.spec).contains(d.version));
                b.append("<li>").append(ok ? "<span class='ok'>✓</span> " : "<span class='bad'>✗</span> ").append(esc(r.name))
                    .append(" ").append(esc(r.spec)).append(" <span class='dim'>").append(d == null ? "not installed" : esc(d.version))
                    .append("</span></li>");
            }
            b.append("</ul>");
        }
        if (!p.requiredBy.isEmpty()) {
            b.append("<h3>Required by</h3><ul>");
            for (String[] c : PyAdvisor.constraintsOn(e, p.key)) {
                b.append("<li>").append(esc(c[0])).append(" <span class='dim'>").append(esc(c[1])).append("</span></li>");
            }
            for (String k : p.requiredBy) {
                final PyEnv.Pkg d = e.packages.get(k);
                if (d != null && PyAdvisor.constraintsOn(e, p.key).stream().noneMatch(c -> c[0].equals(d.name))) {
                    b.append("<li>").append(esc(d.name)).append("</li>");
                }
            }
            b.append("</ul>");
        }
        return b.toString();
    }

    private void row(StringBuilder b, String k, String v) {
        b.append("<tr><td class='dim' valign='top'>").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>");
    }

    private String badge(String text, Color c) {
        return "<span style='background:" + hex(c) + ";color:" + hex(palette.getPyBadgeText()) + "'>&nbsp;" + esc(text) + "&nbsp;</span>&nbsp; ";
    }

    private String page(String body) {
        return "<html><head><style>body{font-family:sans-serif;font-size:11px;color:" + hex(palette.getTextPrimary()) + ";margin:8px}"
            + "h2{font-size:15px;margin:2px 0 6px 0} h3{font-size:12px;margin:10px 0 3px 0}"
            + "a{color:" + hex(palette.getPyPatch()) + "} .dim{color:" + hex(palette.getTextSecondary()) + "}"
            + ".ok{color:" + hex(palette.getPyOk()) + "} .bad{color:" + hex(palette.getPyDanger()) + "}"
            + "td{padding:1px 8px 1px 0} ul{margin-left:16px;margin-top:2px}</style></head><body>" + body + "</body></html>";
    }

    private static JEditorPane html() {
        final JEditorPane p = new JEditorPane("text/html", "");
        p.setEditable(false);
        p.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return p;
    }

    private JPopupMenu packageMenu() {
        final JPopupMenu m = new JPopupMenu();
        final List<PyEnv.Pkg> sel = selected();
        if (sel.isEmpty()) return m;
        m.add(item("Upgrade (safe)", () -> upgradeSelected(true)));
        m.add(item("Upgrade to latest", () -> upgradeSelected(false)));
        m.add(item("Reinstall", () -> {
            final List<String> specs = new ArrayList<>();
            for (PyEnv.Pkg p : sel) specs.add(p.name + "==" + p.version);
            final List<String> args = new ArrayList<>(List.of("--force-reinstall", "--no-deps"));
            args.addAll(specs);
            runPip("Reinstall " + sel.size() + " package(s)", "install", args);
        }));
        m.add(item("Uninstall…", this::uninstallSelected));
        m.addSeparator();
        m.add(item("Copy as requirements", () -> {
            final StringBuilder b = new StringBuilder();
            for (PyEnv.Pkg p : sel) b.append(p.name).append("==").append(p.version).append('\n');
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(b.toString()), null);
        }));
        m.add(item("Open folder", () -> openFolder(sel.get(0))));
        m.add(item("PyPI page", () -> open("https://pypi.org/project/" + sel.get(0).name + "/")));
        return m;
    }

    private JPopupMenu moreMenu() {
        final JPopupMenu m = new JPopupMenu();
        m.add(item("Open site-packages", () -> {
            if (env != null && !env.purelib.isEmpty()) openPath(Path.of(env.purelib));
        }));
        m.add(item("Open the environment's folder", () -> {
            if (env != null) openPath(Path.of(env.prefix));
        }));
        m.add(item("Open pip's cache", () -> {
            final PyEnv e = env;
            if (e == null) return;
            loads.submit(() -> {
                final List<String> lines = new ArrayList<>();
                try {
                    new PipRunner().run(PipRunner.command(e, false, "cache", List.of("dir")), lines::add);
                } catch (Exception ex) {
                    lines.add(ex.getMessage());
                }
                final String dir = lines.isEmpty() ? "" : lines.get(lines.size() - 1).strip();
                SwingUtilities.invokeLater(() -> {
                    if (Files.isDirectory(Path.of(dir))) openPath(Path.of(dir));
                    else activity.setText("pip's cache: " + dir);
                });
            });
        }));
        m.add(item("Purge pip's cache…", () -> {
            if (confirm("Delete pip's download cache? Packages will be downloaded again when installed.")) {
                runPip("Purge pip's cache", "cache", List.of("purge"));
            }
        }));
        m.add(item("Forget what PyPI said (cache)", () -> {
            pypi.forget(true);
            if (env != null) load(env.executable);
        }));
        m.addSeparator();
        m.add(item("Copy the environment report", this::copyReport));
        m.add(item("Install uv (fast installs)", () -> installSpecs(List.of("uv"), List.of(), "Install uv", false)));
        return m;
    }

    /* ------------------------------------------------------------------ */
    /* Operations                                                          */
    /* ------------------------------------------------------------------ */

    /** A pip command on the environment, a snapshot taken first, the environment read again after. */
    private void runPip(String title, String verb, List<String> args) {
        final PyEnv e = env;
        if (e == null) return;
        final boolean changes = verb.equals("install") || verb.equals("uninstall");
        operation(title, () -> {
            if (changes) PySnapshots.save(e, title);
            return runner.run(PipRunner.command(e, useUv.isSelected() && !verb.equals("cache"), verb, args), this::logLine);
        }, changes);
    }

    @FunctionalInterface
    private interface Op {
        int run() throws Exception;
    }

    private void operation(String title, Op op, boolean reload) {
        busy(true, title + " …");
        stopButton.setEnabled(true);
        log("▶ " + title, palette.getLogInfoPrefix());
        ops.submit(() -> {
            int code;
            String error = null;
            try {
                code = op.run();
            } catch (Exception ex) {
                code = -1;
                error = ex.getMessage();
            }
            final int exit = code;
            final String why = error;
            SwingUtilities.invokeLater(() -> {
                stopButton.setEnabled(false);
                if (exit == 0) {
                    log("✔ " + title + " — done", palette.getLogSuccessText());
                    busy(false, title + " — done");
                } else {
                    log("✖ " + title + " — " + (why != null ? why : "exit code " + exit) + "  (see above)", palette.getLogErrorText());
                    busy(false, title + " — failed (Console tab)");
                    tabs.setSelectedIndex(5);
                }
                if (reload && env != null) load(env.executable);
            });
        });
    }

    private void installSpecs(List<String> specs, List<String> extra, String title, boolean preview) {
        final PyEnv e = env;
        if (e == null || specs.isEmpty()) return;
        if (preview && e.pip != null) {
            busy(true, "pip's resolver: what would change…");
            loads.submit(() -> {
                List<PipRunner.Change> changes = null;
                try {
                    changes = new PipRunner().preview(e, specs, extra, this::logLine);
                } catch (Exception ignored) {
                    // installed without a preview
                }
                final List<PipRunner.Change> c = changes;
                SwingUtilities.invokeLater(() -> {
                    busy(false, "");
                    if (c != null && !confirmChanges(title, c)) return;
                    final List<String> args = new ArrayList<>(extra);
                    args.addAll(specs);
                    runPip(title, "install", args);
                });
            });
            return;
        }
        final List<String> args = new ArrayList<>(extra);
        args.addAll(specs);
        runPip(title, "install", args);
    }

    private boolean confirmChanges(String title, List<PipRunner.Change> changes) {
        if (changes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to change: everything asked for is already installed.", title,
                JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        final StringBuilder b = new StringBuilder();
        int downgrades = 0;
        for (PipRunner.Change c : changes) {
            b.append(String.format(Locale.ROOT, "%-28s %-14s → %-14s %s%n", c.name(), c.from() == null ? "(new)" : c.from(), c.to(), c.kind()));
            if (c.kind().equals("downgrade")) downgrades++;
        }
        final JTextArea area = new JTextArea(b.toString(), Math.min(18, changes.size() + 1), 80);
        area.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        area.setEditable(false);
        final JPanel p = new JPanel(new BorderLayout(4, 6));
        p.add(new JLabel(changes.size() + " change" + (changes.size() == 1 ? "" : "s") + " (pip's resolver)"
            + (downgrades > 0 ? ", " + downgrades + " DOWNGRADE" + (downgrades == 1 ? "" : "S") : "")
            + ". A snapshot is taken first."), BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        return JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            == JOptionPane.OK_OPTION;
    }

    private void upgradeSelected(boolean safe) {
        final List<String> specs = new ArrayList<>();
        final List<String> held = new ArrayList<>();
        for (PyEnv.Pkg p : selected()) {
            if (!p.outdated()) continue;
            if (safe && p.safeTarget == null) {
                held.add(p.name + " (" + p.safeReason + ")");
                continue;
            }
            specs.add(p.name + "==" + (safe ? p.safeTarget : p.latest));
        }
        if (!held.isEmpty()) log("held back: " + String.join("; ", held), palette.getLogWarnText());
        if (specs.isEmpty()) {
            activity.setText(held.isEmpty() ? "Nothing to upgrade in the selection." : "The selection is held back by its dependents.");
            return;
        }
        installSpecs(specs, List.of(), "Upgrade " + specs.size() + " package" + (specs.size() == 1 ? "" : "s"), true);
    }

    private void uninstallSelected() {
        uninstall(selected());
    }

    /** Uninstall, saying what would break, offering what came with it and nothing else needs. */
    private void uninstall(List<PyEnv.Pkg> pkgs) {
        final PyEnv e = env;
        if (e == null || pkgs.isEmpty()) return;
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> breaks = new TreeSet<>();
        final Set<String> extra = new TreeSet<>();
        for (PyEnv.Pkg p : pkgs) names.add(p.name);
        for (PyEnv.Pkg p : pkgs) {
            for (String d : PyAdvisor.dependents(e, p.key)) {
                final PyEnv.Pkg dp = e.packages.get(d);
                if (dp != null && !names.contains(dp.name)) breaks.add(dp.name);
            }
            extra.addAll(PyAdvisor.autoremove(e, p.key));
        }
        extra.removeAll(names);
        final JPanel panel = new JPanel(new BorderLayout(4, 8));
        panel.add(new JLabel("<html>Uninstall <b>" + esc(String.join(", ", names)) + "</b>?"
            + (breaks.isEmpty() ? "" : "<br><span style='color:" + hex(palette.getPyDanger()) + "'>These need it and would break: "
            + esc(String.join(", ", breaks)) + "</span>") + "</html>"), BorderLayout.NORTH);
        final JCheckBox also = new JCheckBox("<html>Also remove what came with it and nothing else needs: " + esc(String.join(", ", extra))
            + "</html>", !extra.isEmpty());
        also.setVisible(!extra.isEmpty());
        panel.add(also, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, panel, "Uninstall", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
            != JOptionPane.OK_OPTION) {
            return;
        }
        final List<String> args = new ArrayList<>(List.of("-y"));
        args.addAll(names);
        if (also.isSelected()) args.addAll(extra);
        runPip("Uninstall " + String.join(", ", names), "uninstall", args);
    }

    private void applyFix(PyAdvisor.Finding f) {
        final PyAdvisor.Fix fix = f.fix();
        switch (fix.kind()) {
            case INSTALL -> installSpecs(fix.targets(), List.of(), fix.label(), true);
            case UNINSTALL -> {
                final List<PyEnv.Pkg> pkgs = new ArrayList<>();
                for (String n : fix.targets()) {
                    final PyEnv.Pkg p = env == null ? null : env.packages.get(Pep440.normalize(n));
                    if (p != null) pkgs.add(p);
                }
                uninstall(pkgs);
            }
            case REINSTALL -> runPip(fix.label(), "install", List.of("--force-reinstall", "--no-deps", fix.targets().get(0)));
            case DELETE_FOLDERS -> {
                if (!confirm("Delete these folders?\n" + String.join("\n", fix.targets()))) return;
                operation("Delete leftovers", () -> {
                    for (String t : fix.targets()) deleteTree(Path.of(t));
                    return 0;
                }, true);
            }
            case NEW_VENV -> newVenv();
            default -> {
            }
        }
    }

    /** Every safe fix of the findings at once: security, unmet requirements, leftovers; one preview. */
    private void fixAll() {
        final List<String> specs = new ArrayList<>();
        final List<String> folders = new ArrayList<>();
        for (PyAdvisor.Finding f : findings) {
            if (f.severity() == PyAdvisor.Severity.INFO || f.severity() == PyAdvisor.Severity.LOW) continue;
            if (f.fix().kind() == PyAdvisor.FixKind.INSTALL) specs.addAll(f.fix().targets());
            if (f.fix().kind() == PyAdvisor.FixKind.DELETE_FOLDERS) folders.addAll(f.fix().targets());
        }
        if (specs.isEmpty() && folders.isEmpty()) {
            activity.setText("Nothing serious to fix.");
            return;
        }
        if (!folders.isEmpty() && confirm("Delete the leftovers of interrupted pip runs?\n" + String.join("\n", folders))) {
            operation("Delete leftovers", () -> {
                for (String t : folders) deleteTree(Path.of(t));
                return 0;
            }, specs.isEmpty());
        }
        if (!specs.isEmpty()) installSpecs(dedupe(specs), List.of(), "Fix the environment", true);
    }

    private static List<String> dedupe(List<String> specs) {
        final Map<String, String> byName = new java.util.LinkedHashMap<>();
        for (String s : specs) byName.put(Pep440.normalize(s.split("[=<>!~ ]", 2)[0]), s);
        return new ArrayList<>(byName.values());
    }

    private void deepCheck() {
        final PyEnv e = env;
        if (e == null) return;
        final List<String> modules = new ArrayList<>();
        for (PyEnv.Pkg p : e.packages.values()) {
            if (PyEnv.TOOLING.contains(p.key)) continue;
            for (String t : p.top) if (!t.startsWith("_") && !t.equals("tests") && !t.equals("test")) modules.add(t);
        }
        importReport.setText("Importing " + modules.size() + " modules…");
        busy(true, "Importing every module…");
        loads.submit(() -> {
            String text;
            try {
                final List<PyProbe.ImportResult> res = PyProbe.importCheck(e.executable, modules);
                final StringBuilder b = new StringBuilder();
                final List<PyProbe.ImportResult> broken = res.stream().filter(r -> !r.ok()).toList();
                b.append(broken.isEmpty() ? "Every module imports.\n" : broken.size() + " module(s) do not import:\n");
                for (PyProbe.ImportResult r : broken) b.append(String.format(Locale.ROOT, "  ✖ %-22s %s%n", r.module(), r.error()));
                final List<PyProbe.ImportResult> slow = new ArrayList<>(res);
                slow.sort(Comparator.comparingDouble(PyProbe.ImportResult::seconds).reversed());
                b.append("\nSlowest imports (what a script pays at its start):\n");
                for (int i = 0; i < Math.min(12, slow.size()); i++) {
                    b.append(String.format(Locale.ROOT, "  %6.3f s  %s%n", slow.get(i).seconds(), slow.get(i).module()));
                }
                text = b.toString();
            } catch (Exception ex) {
                text = "The deep check failed: " + ex.getMessage();
            }
            final String t = text;
            SwingUtilities.invokeLater(() -> {
                importReport.setText(t);
                importReport.setCaretPosition(0);
                busy(false, "");
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /* Install tab                                                         */
    /* ------------------------------------------------------------------ */

    private void lookup() {
        final PyEnv e = env;
        final String text = spec.getText().strip();
        if (text.isEmpty()) return;
        final String first = text.split("\\s+")[0];
        final String name = first.split("[=<>!~\\[;@ ]", 2)[0];
        versionBox.removeAllItems();
        lookupCard.setText(page("<p>Asking PyPI about " + esc(name) + "…</p>"));
        loads.submit(() -> {
            if (e != null) pypi.setPython(e.executable);
            final PyPi.Project project = pypi.project(name);
            final PyPi.Published newest = project.found ? project.newest(false) : null;
            final PyPi.Release release = newest == null ? null : pypi.release(name, newest.version());
            final StringBuilder b = new StringBuilder();
            final List<String> similar = PyAdvisor.similar(name, e == null ? List.of() : e.packages.keySet());
            if (!project.found) {
                b.append("<h2>").append(esc(name)).append("</h2><p class='bad'>").append(project.stale
                    ? "PyPI cannot be reached." : "There is no package of this name on PyPI.").append("</p>");
                if (!similar.isEmpty()) b.append("<p>Did you mean <b>").append(esc(String.join(", ", similar))).append("</b>?</p>");
                final String alias = PyAdvisor.IMPORT_ALIASES.get(name);
                if (alias != null) b.append("<p>The module <b>").append(esc(name)).append("</b> comes from <b>").append(esc(alias))
                    .append("</b>.</p>");
                final String not = PyAdvisor.NOT_ON_PIP.get(name);
                if (not != null) b.append("<p>").append(esc(not)).append("</p>");
            } else {
                b.append("<h2>").append(esc(name)).append(" <span class='dim'>").append(newest == null ? "" : esc(newest.version()))
                    .append("</span></h2>");
                if (!project.summary.isEmpty()) b.append("<p>").append(esc(project.summary)).append("</p>");
                if (!similar.isEmpty()) {
                    b.append("<p class='bad'>Close to ").append(esc(String.join(", ", similar)))
                        .append(": check the name, look-alike packages are a known attack (typosquatting).</p>");
                }
                b.append("<table>");
                if (newest != null && newest.date() != null) row(b, "Latest", newest.version() + " (" + PyAdvisor.ago(newest.date()) + ")");
                if (release != null) {
                    if (!release.requiresPython.isEmpty()) {
                        final boolean ok = e == null || Pep440.specifier(release.requiresPython).contains(e.version);
                        row(b, "Requires Python", release.requiresPython + (ok ? "  ✓" : "  ✗ not this Python " + (e == null ? "" : e.version)));
                    }
                    if (!release.license.isEmpty()) row(b, "License", release.license);
                    if (!release.requiresDist.isEmpty()) {
                        final List<String> deps = new ArrayList<>();
                        for (String d : release.requiresDist) if (!d.contains("extra ==") && !d.contains("extra==")) deps.add(d.split(";")[0].strip());
                        row(b, "Depends on", deps.isEmpty() ? "nothing" : String.join(", ", deps));
                    }
                }
                final PyEnv.Pkg installed = e == null ? null : e.packages.get(Pep440.normalize(name));
                row(b, "Here", installed == null ? "not installed" : "installed: " + installed.version);
                b.append("</table>");
                if (release != null && !release.vulnerabilities.isEmpty()) {
                    b.append("<p class='bad'>").append(release.vulnerabilities.size()).append(" known vulnerabilities in the latest version.</p>");
                }
                if (release != null && !release.home.isEmpty()) b.append("<p><a href='").append(esc(release.home)).append("'>")
                    .append(esc(release.home)).append("</a></p>");
                b.append("<p><a href='https://pypi.org/project/").append(esc(name)).append("/'>PyPI page</a></p>");
            }
            SwingUtilities.invokeLater(() -> {
                lookupCard.setText(page(b.toString()));
                lookupCard.setCaretPosition(0);
                versionBox.removeAllItems();
                versionBox.addItem("latest");
                if (project.found) for (PyPi.Published v : project.sorted(true)) versionBox.addItem(v.version());
            });
        });
    }

    private void install(boolean previewOnly) {
        final PyEnv e = env;
        final String text = spec.getText().strip();
        if (e == null || text.isEmpty()) return;
        final List<String> specs = new ArrayList<>(List.of(text.split("\\s+")));
        final Object v = versionBox.getSelectedItem();
        if (specs.size() == 1 && v != null && !"latest".equals(v) && specs.get(0).matches("[A-Za-z0-9._-]+")) {
            specs.set(0, specs.get(0) + "==" + v);
        }
        final List<String> extra = eager.isSelected() ? List.of("--upgrade", "--upgrade-strategy", "eager") : List.of();
        if (!previewOnly) {
            installSpecs(specs, extra, "Install " + String.join(" ", specs), true);
            return;
        }
        previewArea.setText("pip's resolver…\n");
        loads.submit(() -> {
            String out;
            try {
                final List<PipRunner.Change> changes = new PipRunner().preview(e, specs, extra, line -> { });
                if (changes == null) {
                    out = "pip " + e.pip + " cannot preview (needs pip 22.2 or later), or the resolver refused: see the Console.";
                } else if (changes.isEmpty()) {
                    out = "Nothing would change: already installed.";
                } else {
                    final StringBuilder b = new StringBuilder(changes.size() + " change(s):\n");
                    for (PipRunner.Change c : changes) {
                        b.append(String.format(Locale.ROOT, "  %-28s %-14s → %-14s %s%n", c.name(), c.from() == null ? "(new)" : c.from(),
                            c.to(), c.kind()));
                    }
                    out = b.toString();
                }
            } catch (Exception ex) {
                out = "Preview failed: " + ex.getMessage();
            }
            final String o = out;
            SwingUtilities.invokeLater(() -> previewArea.setText(o));
        });
    }

    private void installRequirements() {
        final JFileChooser ch = new JFileChooser(Path.of("WorkSpace").toAbsolutePath().toFile());
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("requirements", "txt", "in"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        installSpecs(List.of("-r", ch.getSelectedFile().getAbsolutePath()), List.of(),
            "Install " + ch.getSelectedFile().getName(), true);
    }

    /* ------------------------------------------------------------------ */
    /* Imports tab                                                         */
    /* ------------------------------------------------------------------ */

    private final class ImportModel extends AbstractTableModel {
        List<ImportScanner.Use> rows = new ArrayList<>();
        final String[] cols = {"Module", "Status", "Distribution", "Files", "Note"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            final ImportScanner.Use u = rows.get(r);
            return switch (c) {
                case 0 -> u.module();
                case 1 -> u.status();
                case 2 -> u.distribution();
                case 3 -> u.files().size();
                default -> u.note().isEmpty() ? String.join(", ", u.files().stream().limit(3).toList()) : u.note();
            };
        }
    }

    private final class ImportRenderer extends PyWidgets.Cell {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            final JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            if (!sel && t.convertColumnIndexToModel(col) == 1) {
                final String s = String.valueOf(v);
                l.setForeground(s.equals("missing") ? palette.getPyDanger() : s.equals("not on pip") ? palette.getPyMinor()
                    : s.equals("installed") ? palette.getPyOk() : palette.getTextSecondary());
            }
            return l;
        }
    }

    private void scanImports() {
        final PyEnv e = env;
        if (e == null) return;
        final Path root = Path.of(folder.getText().strip());
        busy(true, "Reading the imports of " + root + " …");
        loads.submit(() -> {
            try {
                final Set<String> hints = new TreeSet<>();
                final var modules = ImportScanner.scan(root, 5000, hints);
                final List<ImportScanner.Use> uses = new ArrayList<>(ImportScanner.classify(modules, e, root));
                for (String h : hints) {
                    final String key = Pep440.normalize(h.split("[=<>!~\\[]", 2)[0]);
                    if (!e.packages.containsKey(key) && uses.stream().noneMatch(u -> Pep440.normalize(u.distribution()).equals(key))) {
                        uses.add(new ImportScanner.Use(h, Set.of("(pip install line)"), "missing", h, "a notebook installs it"));
                    }
                }
                uses.sort(Comparator.comparing((ImportScanner.Use u) -> !u.missing()).thenComparing(ImportScanner.Use::module));
                SwingUtilities.invokeLater(() -> {
                    importModel.rows = uses;
                    importModel.fireTableDataChanged();
                    final long missing = uses.stream().filter(ImportScanner.Use::missing).count();
                    busy(false, modules.size() + " modules imported; " + missing + " missing here");
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> busy(false, "cannot read " + root + ": " + ex.getMessage()));
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Snapshots tab                                                       */
    /* ------------------------------------------------------------------ */

    private void refreshSnapshots() {
        snapshotModel.clear();
        if (env == null) return;
        for (PySnapshots.Snapshot s : PySnapshots.list(env)) snapshotModel.addElement(s);
        diffArea.setText(snapshotModel.isEmpty() ? "No snapshot yet: one is taken before every change the manager makes."
            : "Select a snapshot to see what changed since.");
    }

    private void compareSnapshot() {
        final PySnapshots.Snapshot s = snapshotList.getSelectedValue();
        if (s == null || env == null) return;
        final PySnapshots.Diff d = PySnapshots.diff(s, env);
        final StringBuilder b = new StringBuilder(s.label()).append("\n\n");
        if (d.empty()) b.append("Identical to the environment now.\n");
        for (Map.Entry<String, String[]> e : d.changed().entrySet()) {
            b.append(String.format(Locale.ROOT, "  ~ %-28s %s → %s now%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        for (Map.Entry<String, String> e : d.removed().entrySet()) {
            b.append(String.format(Locale.ROOT, "  - %-28s %s (gone now)%n", e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, String> e : d.added().entrySet()) {
            b.append(String.format(Locale.ROOT, "  + %-28s %s (added since)%n", e.getKey(), e.getValue()));
        }
        diffArea.setText(b.toString());
        diffArea.setCaretPosition(0);
    }

    private void restoreSnapshot() {
        final PySnapshots.Snapshot s = snapshotList.getSelectedValue();
        final PyEnv e = env;
        if (s == null || e == null) return;
        final PySnapshots.Diff d = PySnapshots.diff(s, e);
        if (d.empty()) {
            activity.setText("The environment is already as in this snapshot.");
            return;
        }
        final List<String> install = PySnapshots.restoreInstall(d);
        final List<String> remove = PySnapshots.restoreUninstall(d);
        if (!confirm("Restore " + s.label() + "?\n\n" + install.size() + " package(s) back to their versions, " + remove.size()
            + " added since removed.\nA snapshot of the current state is taken first.")) {
            return;
        }
        operation("Restore the snapshot of " + s.label(), () -> {
            PySnapshots.save(e, "before restoring " + s.label());
            int code = 0;
            if (!install.isEmpty()) {
                final List<String> args = new ArrayList<>(List.of("--no-deps"));
                args.addAll(install);
                code = runner.run(PipRunner.command(e, useUv.isSelected(), "install", args), this::logLine);
            }
            if (code == 0 && !remove.isEmpty()) {
                final List<String> args = new ArrayList<>(List.of("-y"));
                args.addAll(remove);
                code = runner.run(PipRunner.command(e, useUv.isSelected(), "uninstall", args), this::logLine);
            }
            return code;
        }, true);
    }

    private void exportRequirements(boolean direct) {
        if (env == null) return;
        final JFileChooser ch = new JFileChooser(Path.of("WorkSpace").toAbsolutePath().toFile());
        ch.setSelectedFile(new File(direct ? "requirements.in.txt" : "requirements.txt"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(ch.getSelectedFile().toPath(), PySnapshots.requirements(env, direct), StandardCharsets.UTF_8);
            activity.setText("written: " + ch.getSelectedFile());
        } catch (IOException ex) {
            activity.setText("not written: " + ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* New venv, Sphere's Python                                           */
    /* ------------------------------------------------------------------ */

    private void newVenv() {
        final JComboBox<Object> base = new JComboBox<>();
        for (int i = 0; i < interpreterBox.getItemCount(); i++) {
            final Object o = interpreterBox.getItemAt(i);
            if (o instanceof PyInterpreters.Found f && !f.kind().contains("venv")) base.addItem(o);
        }
        if (base.getItemCount() == 0 && env != null) {
            base.addItem(new PyInterpreters.Found(env.executable, env.version, env.kind(), "current"));
        }
        final JTextField where = new JTextField(Path.of("WorkSpace", "venv-sphere").toAbsolutePath().toString(), 40);
        final JCheckBox system = new JCheckBox("Let it see the base Python's packages (--system-site-packages)");
        final JCheckBox sphere = new JCheckBox("Make it Sphere's Python", true);
        final JComboBox<String> preset = new JComboBox<>();
        preset.addItem("(no packages)");
        for (String k : PyAdvisor.PRESETS.keySet()) preset.addItem(k);
        final JPanel p = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        p.add(new JLabel("Base Python"), c);
        c.gridx = 1;
        p.add(base, c);
        c.gridx = 0;
        c.gridy = 1;
        p.add(new JLabel("Folder"), c);
        c.gridx = 1;
        p.add(where, c);
        c.gridx = 0;
        c.gridy = 2;
        p.add(new JLabel("Packages"), c);
        c.gridx = 1;
        p.add(preset, c);
        c.gridy = 3;
        p.add(system, c);
        c.gridy = 4;
        p.add(sphere, c);
        if (JOptionPane.showConfirmDialog(this, p, "New virtual environment", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            != JOptionPane.OK_OPTION) {
            return;
        }
        if (!(base.getSelectedItem() instanceof PyInterpreters.Found b)) return;
        final Path folderPath = Path.of(where.getText().strip());
        final List<String> packages = preset.getSelectedIndex() <= 0 ? List.of()
            : PyAdvisor.PRESETS.get(String.valueOf(preset.getSelectedItem()));
        operation("Create " + folderPath, () -> {
            int code = runner.createVenv(b.path(), folderPath, system.isSelected(), useUv.isSelected(), this::logLine);
            final Path exe = PipRunner.venvPython(folderPath);
            if (code == 0 && !packages.isEmpty()) {
                final PyEnv fresh = new PyEnv();
                fresh.executable = exe.toString();
                final List<String> args = new ArrayList<>(packages);
                code = runner.run(PipRunner.command(fresh, useUv.isSelected(), "install", args), this::logLine);
            }
            if (code == 0) {
                SwingUtilities.invokeLater(() -> {
                    if (sphere.isSelected()) {
                        PythonEnvService.savePythonExecToConfig("settings.conf", exe.toString());
                        configured = exe.toString();
                    }
                    final PyInterpreters.Found f = new PyInterpreters.Found(exe.toString(), b.version(), "venv", "created here");
                    switching = true;
                    interpreterBox.insertItemAt(f, 0);
                    interpreterBox.setSelectedItem(f);
                    switching = false;
                    load(exe.toString());
                });
            }
            return code;
        }, false);
    }

    private void useForSphere() {
        final Object o = interpreterBox.getSelectedItem();
        if (!(o instanceof PyInterpreters.Found f)) return;
        PythonEnvService.savePythonExecToConfig("settings.conf", f.path());
        configured = f.path();
        interpreterBox.repaint();
        refreshHeader();
        activity.setText("Sphere's Python is now " + f.path() + " (new Python sessions use it).");
        AppLogger.info("[py] PYTHON_EXEC = " + f.path());
    }

    /** Kept for RequirementsDialog: the service of the interpreter shown. */
    public PythonEnvService getEnvService() {
        return env == null ? null : new PythonEnvService(env.executable);
    }

    /* ------------------------------------------------------------------ */
    /* Report                                                              */
    /* ------------------------------------------------------------------ */

    private void copyReport() {
        final PyEnv e = env;
        if (e == null) return;
        final StringBuilder b = new StringBuilder();
        b.append("## Python environment\n\n");
        b.append("- Python ").append(e.version).append(" (").append(e.implementation).append(", ").append(e.bits).append("-bit, ")
            .append(e.platform).append(")\n");
        b.append("- ").append(e.kind()).append(": `").append(e.executable).append("`\n");
        b.append("- ").append(e.packages.size()).append(" packages, ").append(PyAdvisor.size(e.totalSize())).append(", pip ")
            .append(e.pip).append('\n');
        b.append("- Health ").append(PyAdvisor.score(findings)).append("/100\n\n");
        if (!findings.isEmpty()) {
            b.append("### Findings\n\n");
            for (PyAdvisor.Finding f : findings) {
                b.append("- **").append(f.severity()).append("** ").append(f.title()).append(" — ").append(f.detail()).append('\n');
            }
            b.append('\n');
        }
        b.append("### Packages\n\n| package | installed | latest | safe |\n|---|---|---|---|\n");
        final List<PyEnv.Pkg> sorted = new ArrayList<>(e.packages.values());
        sorted.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
        for (PyEnv.Pkg p : sorted) {
            b.append("| ").append(p.name).append(" | ").append(p.version).append(" | ").append(p.latest == null ? "" : p.latest)
                .append(" | ").append(p.safeTarget == null ? "" : p.safeTarget).append(" |\n");
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(b.toString()), null);
        activity.setText("Report copied as Markdown.");
    }

    /* ------------------------------------------------------------------ */
    /* Small things                                                        */
    /* ------------------------------------------------------------------ */

    private void busy(boolean on, String text) {
        progress.setIndeterminate(on);
        progress.setVisible(on);
        if (text != null && !text.isEmpty()) activity.setText(text);
        else if (!on) activity.setText(" ");
    }

    private void logLine(String line) {
        SwingUtilities.invokeLater(() -> {
            final String l = line == null ? "" : line;
            final Color c = l.startsWith("$ ") ? palette.getLogPromptPrefix()
                : l.contains("ERROR") || l.contains("error:") ? palette.getLogErrorText()
                : l.contains("WARNING") || l.contains("warning:") ? palette.getLogWarnText()
                : l.startsWith("Successfully") || l.startsWith("Installed") ? palette.getLogSuccessText() : palette.getTerminalForeground();
            append(l, c);
            if (!l.isBlank()) activity.setText(l.length() > 160 ? l.substring(0, 157) + "…" : l);
        });
    }

    private void log(String text, Color c) {
        append(text, c);
        AppLogger.stream(text);
    }

    private void append(String text, Color c) {
        final StyledDocument doc = console.getStyledDocument();
        final SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c == null ? palette.getTerminalForeground() : c);
        try {
            doc.insertString(doc.getLength(), text + "\n", a);
            console.setCaretPosition(doc.getLength());
        } catch (javax.swing.text.BadLocationException ignored) {
            // the console is only a log
        }
    }

    private JButton button(String text, String tip, Runnable run) {
        final JButton b = new JButton(text);
        b.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        b.setFocusPainted(false);
        if (tip != null) b.setToolTipText(tip);
        if (run != null) b.addActionListener(e -> run.run());
        return b;
    }

    private static JMenuItem item(String text, Runnable run) {
        final JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> run.run());
        return i;
    }

    private boolean confirm(String text) {
        return JOptionPane.showConfirmDialog(this, text, "Python environment", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void openFolder(PyEnv.Pkg p) {
        Path where = Path.of(p.path);
        if (!p.top.isEmpty() && where.getParent() != null) {
            final Path module = where.getParent().resolve(p.top.get(0));
            if (Files.isDirectory(module)) where = module;
            else where = where.getParent();
        }
        openPath(where);
    }

    private void openPath(Path p) {
        try {
            Desktop.getDesktop().open(p.toFile());
        } catch (IOException | RuntimeException e) {
            activity.setText("cannot open " + p + ": " + e.getMessage());
        }
    }

    private static void open(String url) {
        com.sphere.utils.WebLinks.open(url);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    private static boolean sameFile(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        try {
            return Path.of(a).toAbsolutePath().normalize().toString().equalsIgnoreCase(Path.of(b).toAbsolutePath().normalize().toString());
        } catch (RuntimeException e) {
            return a.equalsIgnoreCase(b);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    /** A panel that takes the width of its scroll pane's view and scrolls only vertically. */
    private static final class TrackingPanel extends JPanel implements javax.swing.Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return visible.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** A document listener with one method. */
    @FunctionalInterface
    public interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void update();

        @Override
        default void insertUpdate(javax.swing.event.DocumentEvent e) {
            update();
        }

        @Override
        default void removeUpdate(javax.swing.event.DocumentEvent e) {
            update();
        }

        @Override
        default void changedUpdate(javax.swing.event.DocumentEvent e) {
            update();
        }
    }
}
