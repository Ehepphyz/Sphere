package com.sphere.components.variables;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.components.variables.VariableStore.Variable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The variables of every language, in one table.
 *
 * Two kinds of language meet here. Those still running are asked again when the
 * refresh is pressed; those that have ended are read from the file they left in
 * the watched folder. Which of the two a row came from does not show, and does
 * not need to: a variable is a name, a type and a value whatever produced it.
 */
public final class VariablesPanel extends JPanel implements VariableStore.Listener {

    private static VariablesPanel shared;

    /** The folder watched when no project is open. */
    public static final String FOLDER_NAME = "variables";

    /**
     * The languages the tab covers, listed whether or not they have run yet, so
     * that the choice says what is supported rather than what happens to be there.
     */
    private static final List<String> KNOWN_SOURCES =
        List.of("python", "cpp", "root", "julia", "fortran");

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextField filter = new JTextField();
    private final JComboBox<String> sourceChoice = new JComboBox<>();
    private final JLabel count = new JLabel(" ");
    private final VariableWatch watch = new VariableWatch();

    private Path folder;
    private Runnable reveal;
    /** Told which variable was picked, so a value can be asked for on demand. */
    private Consumer<Variable> pickListener;

    /** The one panel the tab holds, which every language reaches for. */
    public static synchronized VariablesPanel instance() {
        if (shared == null) {
            shared = new VariablesPanel();
        }
        return shared;
    }

    private VariablesPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(ImagingTheme.panel());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);

        VariableStore.addListener(this);
        ensureWatched();
        watch.start();
        refreshTable();
    }

    // ---- what the rest of Sphere asks of it ---------------------------------

    /** Where the variable files are read from, made if it was not there. */
    public Path folder() throws IOException {
        Path target = folder != null ? folder
            : com.sphere.core.fs.WorkingDirectory.get().resolve(FOLDER_NAME);
        Files.createDirectories(target);
        return target;
    }

    /**
     * Points the watch at a folder of its own.
     *
     * A project keeps its variables with the rest of its files, so opening one
     * moves the watch there rather than leaving it in the folder Sphere was
     * launched from.
     */
    public void setFolder(Path target) {
        if (folder != null) {
            watch.remove(folder);
        }
        folder = target == null ? null : target.toAbsolutePath().normalize();
        if (folder != null) {
            try {
                Files.createDirectories(folder);
                watch.add(folder);
            } catch (IOException unwritable) {
                folder = null;
            }
        }
    }

    public List<Path> watched() {
        return watch.folders();
    }

    public void watch(Path directory) {
        watch.add(directory);
    }

    public boolean unwatch(Path directory) {
        return watch.remove(directory);
    }

    /** Exposed so a test can drive the watch without waiting for its thread. */
    public int scan() {
        ensureWatched();
        return watch.scan();
    }

    /** Rereads the folder alone, for a run that has just ended. */
    public int reread() {
        ensureWatched();
        return watch.readNow();
    }

    /**
     * Drops what a language left behind, on screen and on disk.
     *
     * A language still running publishes again at the next refresh, which is the
     * point: what is cleared is what has been left, not what is live.
     */
    public int forget(String source) {
        // The folder in use has to be on the watch list before anything can be
        // removed from it. Without this, a clear asked before the first scan of a
        // session found no folder to look in and deleted nothing.
        ensureWatched();
        VariableStore.clear(source);
        return watch.forget(source);
    }

    /** Asks every running language again, and rereads the folder. */
    public int refreshAll() {
        ensureWatched();
        VariableSources.refreshAll();
        return watch.readNow();
    }

    /**
     * Where the variables would come from, and what is in the way.
     *
     * An empty table has several causes that look alike from the outside: no
     * file written, a folder nobody watches, a filter hiding everything. This
     * says which one it is instead of leaving it to be guessed.
     */
    public String diagnosis() {
        StringBuilder report = new StringBuilder("Variables");

        Path inUse = null;
        try {
            inUse = folder();
            report.append("\n  folder in use    : ").append(inUse)
                  .append(Files.isDirectory(inUse) ? "" : "   (it does not exist)")
                  .append(folder == null ? "   (follows the console)" : "   (set by a project)");
        } catch (IOException unwritable) {
            report.append("\n  folder in use    : cannot be made, ")
                  .append(unwritable.getMessage());
        }

        final List<Path> folders = watch.folders();
        report.append("\n  folders watched  : ")
              .append(folders.isEmpty() ? "none" : String.join(", ",
                      folders.stream().map(Path::toString).toList()));
        if (inUse != null && !folders.contains(inUse)) {
            report.append("\n                     the folder in use is NOT among them");
        }

        report.append("\n  files there      : ");
        java.io.File[] entries = inUse == null ? null : inUse.toFile().listFiles();
        int found = 0;
        if (entries != null) {
            for (java.io.File entry : entries) {
                if (VariableFile.isVariableFile(entry.toPath())) {
                    report.append(found++ == 0 ? "" : ", ").append(entry.getName())
                          .append(" (").append(entry.length()).append(" bytes)");
                }
            }
        }
        if (found == 0) {
            report.append("none");
        }

        report.append("\n  held             : ").append(VariableStore.count())
              .append(VariableStore.sources().isEmpty() ? ""
                      : " from " + String.join(", ", VariableStore.sources()));
        report.append("\n  rows shown       : ").append(model.getRowCount());
        report.append("\n  asked on refresh : ")
              .append(VariableSources.names().isEmpty() ? "no language is running"
                      : String.join(", ", VariableSources.names()));

        final String needle = filter.getText().strip();
        report.append("\n  filter box       : ")
              .append(needle.isEmpty() ? "empty" : "\"" + needle + "\"   (it hides the rest)");
        report.append("\n  language shown   : ").append(sourceChoice.getSelectedItem());
        report.append("\n  python wrapper   : ")
              .append(PythonProbe.isWrapping() ? "on" : "off, set by :vars wrap");
        report.append("\n  watch running    : ").append(watch.isRunning());
        return report.toString();
    }

    /**
     * Keeps the folder in use under watch.
     *
     * Only opening a project used to put one there, so a run made without a
     * project wrote its file where nothing was looking. The folder follows the
     * console, so it is checked again each time rather than once.
     */
    private void ensureWatched() {
        if (folder != null) {
            return;
        }
        try {
            watch.add(folder());
        } catch (IOException unwritable) {
            // Nothing to watch until the folder can be made.
        }
    }

    public void setReveal(Runnable action) {
        this.reveal = action;
    }

    public void setPickListener(Consumer<Variable> listener) {
        this.pickListener = listener;
    }

    /** Brings the tab to the front, when something worth seeing arrives. */
    public void show(String source) {
        if (reveal != null) {
            SwingUtilities.invokeLater(reveal);
        }
    }

    @Override
    public void variablesChanged() {
        SwingUtilities.invokeLater(this::refreshTable);
    }

    // ---- the table ----------------------------------------------------------

    private void refreshTable() {
        final String wanted = String.valueOf(sourceChoice.getSelectedItem());
        final String needle = filter.getText().strip().toLowerCase(Locale.ROOT);

        List<Variable> shown = new ArrayList<>();
        for (Variable variable : VariableStore.all()) {
            if (!"all".equals(wanted) && !variable.source().equals(wanted)) {
                continue;
            }
            if (!needle.isEmpty() && !matches(variable, needle)) {
                continue;
            }
            shown.add(variable);
        }
        model.replace(shown);

        final List<String> sources = VariableStore.sources();
        count.setText(shown.size() + " of " + VariableStore.count()
                      + (sources.isEmpty() ? "" : "  from " + String.join(", ", sources)));
        syncSources(sources, wanted);
    }

    private static boolean matches(Variable variable, String needle) {
        return variable.name().toLowerCase(Locale.ROOT).contains(needle)
            || variable.type().toLowerCase(Locale.ROOT).contains(needle)
            || variable.source().contains(needle);
    }

    /** Keeps the source list in step without disturbing what is selected. */
    private void syncSources(List<String> sources, String selected) {
        List<String> wanted = new ArrayList<>();
        wanted.add("all");
        wanted.addAll(KNOWN_SOURCES);
        for (String source : sources) {
            // A .vars file can be named after anything, so a source of its own
            // joins the list rather than being dropped.
            if (!wanted.contains(source)) {
                wanted.add(source);
            }
        }

        List<String> present = new ArrayList<>();
        for (int i = 0; i < sourceChoice.getItemCount(); i++) {
            present.add(sourceChoice.getItemAt(i));
        }
        if (present.equals(wanted)) {
            return;
        }
        sourceChoice.removeAllItems();
        for (String source : wanted) {
            sourceChoice.addItem(source);
        }
        sourceChoice.setSelectedItem(wanted.contains(selected) ? selected : "all");
    }

    private JScrollPane buildTable() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(ImagingTheme.panel());
        table.setForeground(ImagingTheme.text());
        table.setSelectionBackground(ImagingTheme.surface());
        table.setSelectionForeground(ImagingTheme.accent());
        table.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));
        table.setDefaultRenderer(Object.class, new Cell());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(200);

        JTableHeader header = table.getTableHeader();
        header.setBackground(ImagingTheme.surface());
        header.setForeground(ImagingTheme.subduedText());
        header.setFont(ImagingTheme.uiFont(Font.BOLD, 11f));
        header.setReorderingAllowed(false);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                final int row = table.rowAtPoint(event.getPoint());
                if (row < 0 || pickListener == null || event.getClickCount() < 2) {
                    return;
                }
                pickListener.accept(model.at(row));
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ImagingTheme.panel());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel buildToolbar() {
        filter.setToolTipText("Show only the variables whose name or type contains this");
        filter.setColumns(10);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshTable(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshTable(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshTable(); }
        });
        com.sphere.components.ClipboardBridge.install(filter);

        sourceChoice.addItem("all");
        for (String source : KNOWN_SOURCES) {
            sourceChoice.addItem(source);
        }
        sourceChoice.setToolTipText("Show one language, or all of them");
        sourceChoice.addActionListener(e -> refreshTable());

        JButton refresh = ImagingTheme.textButton("Refresh",
            "Ask the languages that are still running, and reread the folder");
        refresh.addActionListener(e -> refreshAll());

        JButton drop = ImagingTheme.textButton("Clear", "Empty the table");
        drop.addActionListener(e -> VariableStore.clear(null));

        ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.add(filter);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(sourceChoice);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(refresh);
        bar.add(Box.createHorizontalStrut(2));
        bar.add(drop);
        bar.add(Box.createHorizontalStrut(8));
        count.setForeground(ImagingTheme.subduedText());
        count.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        bar.add(count);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(ImagingTheme.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    /** Four columns, and the rows the filter left. */
    private static final class Model extends AbstractTableModel {

        private static final String[] COLUMNS = {"Source", "Name", "Type", "Value"};

        private List<Variable> rows = new ArrayList<>();

        void replace(List<Variable> shown) {
            rows = shown;
            fireTableDataChanged();
        }

        Variable at(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return COLUMNS.length; }

        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            final Variable variable = rows.get(row);
            return switch (column) {
                case 0 -> variable.source();
                case 1 -> variable.name();
                case 2 -> variable.type();
                default -> variable.value();
            };
        }
    }

    /**
     * The source column stands back, so the names read first, and what the last
     * line changed stands out, so a value can be watched as it is typed.
     */
    private static final class Cell extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value,
                                                       boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            final boolean fresh = owner.getModel() instanceof Model rows
                               && VariableStore.isFresh(rows.at(row));
            setFont(getFont().deriveFont(fresh ? Font.BOLD : Font.PLAIN));
            if (!selected) {
                if (fresh) {
                    setForeground(ImagingTheme.accent());
                } else {
                    setForeground(column == 0 || column == 2
                                  ? ImagingTheme.subduedText() : ImagingTheme.text());
                }
            }
            setToolTipText(value == null || String.valueOf(value).isEmpty()
                           ? null : String.valueOf(value));
            return this;
        }
    }
}
