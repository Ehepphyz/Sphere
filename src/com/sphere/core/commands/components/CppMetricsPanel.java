package com.sphere.components;

import com.sphere.core.telemetry.RunLog;
import com.sphere.core.telemetry.RunRecord;
import com.sphere.core.telemetry.Telemetry;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.utils.AppLogger;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What Sphere has compiled and run, line by line.
 *
 * Every figure here is measured. The panel used to show four numbers written
 * into the source, which looked like a dashboard and reported nothing.
 */
public class CppMetricsPanel extends JPanel implements RunLog.Listener {

    /** Every language that can appear, so the choice says what is covered. */
    private static final List<String> LANGUAGES =
        List.of("all", "cpp", "python", "fortran", "julia", "shell");

    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<String> languageChoice = new JComboBox<>();
    private final JLabel headline = new JLabel(" ");
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss");

    public CppMetricsPanel(Object cppBackend) {
        setLayout(new BorderLayout());
        setBackground(palette.getBackgroundSurface());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        RunLog.addListener(this);
        refresh();
    }

    // ---- what the log says ---------------------------------------------------

    @Override
    public void runsChanged() {
        SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        final String wanted = languageChoice.getSelectedItem() == null
                            ? "all" : languageChoice.getSelectedItem().toString();
        List<RunRecord> shown = new ArrayList<>();
        for (RunRecord record : RunLog.all()) {
            if ("all".equals(wanted) || wanted.equals(record.language())) {
                shown.add(record);
            }
        }
        // Newest first: what just happened is what is being looked for.
        Collections.reverse(shown);
        model.replace(shown);
        headline.setText(summaryLine(shown));
    }

    /** The one line above the table, built from what the table is showing. */
    private String summaryLine(List<RunRecord> shown) {
        if (shown.isEmpty()) {
            return "Nothing compiled or run yet.";
        }
        long compiles = 0;
        long runs = 0;
        long failures = 0;
        long cached = 0;
        long peak = RunRecord.UNKNOWN;
        for (RunRecord record : shown) {
            if (record.kind() == RunRecord.Kind.COMPILE) {
                compiles++;
                if (record.cached()) {
                    cached++;
                }
            } else {
                runs++;
                peak = Math.max(peak, record.peakMemoryKb());
            }
            if (record.failed()) {
                failures++;
            }
        }
        StringBuilder said = new StringBuilder();
        said.append(compiles).append(" compiled, ").append(runs).append(" run");
        if (failures > 0) {
            said.append(", ").append(failures).append(" failed");
        }
        if (cached > 0) {
            said.append(", ").append(cached).append(" from cache");
        }
        if (peak > 0) {
            said.append(", peak ").append(Telemetry.memory(peak));
        }
        return said.toString();
    }

    // ---- the pieces ----------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(palette.getBackgroundSurface());

        JLabel title = new JLabel("Build and run history");
        title.setFont(FontLoader.getGlobalFont(Font.BOLD, 13));
        title.setForeground(palette.getTextWhite());
        bar.add(title);

        for (String language : LANGUAGES) {
            languageChoice.addItem(language);
        }
        languageChoice.setToolTipText("Show one language, or all of them");
        languageChoice.addActionListener(event -> refresh());
        bar.add(languageChoice);

        bar.add(button("Clear", "Empty the history held in memory", event -> {
            RunLog.clear();
            refresh();
        }));
        bar.add(button("Save", "Write the history to a file", event -> save()));
        bar.add(button("Open", "Read a history back and put it before this session",
                       event -> open()));
        return bar;
    }

    private JButton button(String text, String tip, java.awt.event.ActionListener action) {
        JButton made = new JButton(text);
        made.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        made.setToolTipText(tip);
        made.addActionListener(action);
        return made;
    }

    private JScrollPane buildTable() {
        table.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setShowGrid(false);
        table.setBackground(palette.getBackgroundSurface());
        table.setForeground(palette.getTextLightGray());
        table.setSelectionBackground(palette.getTerminalSelection());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setDefaultRenderer(Object.class, new Cell(palette));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(palette.getBackgroundSurface());
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        footer.setBackground(palette.getBackgroundSurface());
        headline.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        headline.setForeground(palette.getlockedmode());
        footer.add(headline);
        return footer;
    }

    // ---- keeping it ----------------------------------------------------------

    private void save() {
        JFileChooser chooser = new JFileChooser(
            com.sphere.core.fs.WorkingDirectory.get().toFile());
        chooser.setSelectedFile(new java.io.File(RunLog.FILE_NAME));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Path written = RunLog.save(chooser.getSelectedFile().toPath());
            AppLogger.info("History written to " + written);
        } catch (IOException unwritable) {
            AppLogger.error("Could not write the history: " + unwritable.getMessage());
        }
    }

    private void open() {
        JFileChooser chooser = new JFileChooser(
            com.sphere.core.fs.WorkingDirectory.get().toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            final int read = RunLog.open(chooser.getSelectedFile().toPath());
            AppLogger.info(read + " line(s) read from "
                           + chooser.getSelectedFile().getName());
            refresh();
        } catch (IOException unreadable) {
            AppLogger.error("Could not read that history: " + unreadable.getMessage());
        }
    }

    @Override
    public void removeNotify() {
        RunLog.removeListener(this);
        super.removeNotify();
    }

    // ---- the table -----------------------------------------------------------

    /** One line per compilation or run, with what makes it comparable. */
    private final class Model extends AbstractTableModel {

        private static final String[] COLUMNS =
            {"Time", "Language", "What", "Source", "Tool", "Took", "vs usual",
             "Peak memory", "Produced", "Warn", "Err"};

        private List<RunRecord> rows = new ArrayList<>();

        void replace(List<RunRecord> shown) {
            rows = shown;
            fireTableDataChanged();
        }

        RunRecord at(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return COLUMNS.length; }

        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            final RunRecord r = rows.get(row);
            return switch (column) {
                case 0 -> clock.format(new Date(r.startedAt()));
                case 1 -> r.language();
                case 2 -> r.kind().name().toLowerCase(Locale.ROOT);
                case 3 -> r.sourceName();
                case 4 -> r.tool();
                case 5 -> Telemetry.human(r.millis());
                case 6 -> ratio(r);
                case 7 -> Telemetry.memory(r.peakMemoryKb());
                case 8 -> Telemetry.bytes(r.producedBytes());
                case 9 -> r.warnings() == 0 ? "" : Integer.toString(r.warnings());
                default -> r.errors() == 0 ? "" : Integer.toString(r.errors());
            };
        }

        /** How this line compares with what the same source usually takes. */
        private String ratio(RunRecord record) {
            final double times = Telemetry.ratioToUsual(record);
            if (times < 0) {
                return "";
            }
            return String.format(Locale.ROOT, "%.1fx", times);
        }
    }

    /** A failure reads red, a slow build reads amber, the rest stays quiet. */
    private static final class Cell extends DefaultTableCellRenderer {

        private final ThemePalette palette;

        Cell(ThemePalette palette) {
            this.palette = palette;
        }

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value,
                                                       boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            if (selected || !(owner.getModel() instanceof Model rows)) {
                return this;
            }
            final RunRecord record = rows.at(row);
            if (record == null) {
                return this;
            }
            if (record.failed()) {
                setForeground(Color.RED);
            } else if (Telemetry.ratioToUsual(record) >= Telemetry.NOTABLE_RATIO) {
                setForeground(palette.getlockedmode());
            } else {
                setForeground(palette.getTextLightGray());
            }
            return this;
        }
    }
}
