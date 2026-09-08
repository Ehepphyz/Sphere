package com.sphere.components.rootview;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What sits under the plot: the object, the key that holds it, the file, and the
 * bins themselves in a table that can be corrected.
 */
public final class RootInspector extends ViewSurface {

    /** Told when a bin is edited, so the plot can be redrawn. */
    public interface BinListener {
        void binChanged(int bin, double value);
    }

    private final ImagingTheme.Tabs tabs = new ImagingTheme.Tabs();
    private final JTextArea objectText = area();
    private final JTextArea keyText = area();
    private final JTextArea fileText = area();
    private final BinModel binModel = new BinModel();
    private final JTable binTable = new JTable(binModel);

    private BinListener binListener;

    public RootInspector() {
        super(new BorderLayout(), false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        styleTable();
        tabs.addTab("Object", wrap(objectText));
        tabs.addTab("Bins", wrap(binTable));
        tabs.addTab("Key", wrap(keyText));
        tabs.addTab("File", wrap(fileText));
        tabs.setSelectedIndex(0);
        add(tabs, BorderLayout.CENTER);
    }

    public void setBinListener(BinListener listener) {
        this.binListener = listener;
    }

    private static JTextArea area() {
        JTextArea text = new JTextArea();
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(ImagingTheme.panel());
        text.setForeground(ImagingTheme.text());
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        text.setCaretColor(ImagingTheme.accent());
        return text;
    }

    private void styleTable() {
        binTable.setBackground(ImagingTheme.panel());
        binTable.setForeground(ImagingTheme.text());
        binTable.setGridColor(ImagingTheme.border());
        binTable.setRowHeight(20);
        binTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        binTable.setSelectionBackground(ImagingTheme.accent());
        binTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        binTable.getTableHeader().setBackground(ImagingTheme.surface());
        binTable.getTableHeader().setForeground(ImagingTheme.subduedText());
        binTable.getTableHeader().setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));

        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean selected, boolean focus, int row, int column) {
                Component c = super.getTableCellRendererComponent(
                    table, value, selected, focus, row, column);
                setHorizontalAlignment(RIGHT);
                if (!selected) {
                    c.setBackground(ImagingTheme.panel());
                    c.setForeground(column == 3 ? ImagingTheme.text()
                                                : ImagingTheme.subduedText());
                }
                return c;
            }
        };
        for (int i = 0; i < binModel.getColumnCount(); i++) {
            binTable.getColumnModel().getColumn(i).setCellRenderer(right);
        }
    }

    private static JScrollPane wrap(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ImagingTheme.panel());
        scroll.setBackground(ImagingTheme.panel());
        return scroll;
    }

    // ---- what to show ------------------------------------------------------

    public void showNothing() {
        objectText.setText("");
        keyText.setText("");
        binModel.set(null);
    }

    public void showHistogram(RootHistogram h) {
        objectText.setText(describeHistogram(h));
        objectText.setCaretPosition(0);
        binModel.set(h);
    }

    public void showGraph(RootGraph g) {
        objectText.setText(describeGraph(g));
        objectText.setCaretPosition(0);
        binModel.set(null);
    }

    public void showMessage(String text) {
        objectText.setText(text);
        binModel.set(null);
    }

    public void showKey(RootKey key, String path) {
        if (key == null) {
            keyText.setText("");
            return;
        }
        StringBuilder b = new StringBuilder();
        line(b, "path", path);
        line(b, "class", key.className);
        line(b, "name", key.name);
        line(b, "title", key.title);
        line(b, "cycle", String.valueOf(key.cycle));
        LocalDateTime written = key.written();
        line(b, "written", written == null ? "unknown"
             : written.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        b.append('\n');
        line(b, "record starts", String.format(Locale.ROOT, "%,d", key.seekKey));
        line(b, "header", key.keylen + " bytes");
        line(b, "on disk", String.format(Locale.ROOT, "%,d bytes", key.storedBytes()));
        line(b, "once read", String.format(Locale.ROOT, "%,d bytes", key.objlen));
        line(b, "shrunk by", key.compressed()
             ? String.format(Locale.ROOT, "%.2f times", key.compressionRatio())
             : "not compressed");
        line(b, "parent at", String.format(Locale.ROOT, "%,d", key.seekPdir));
        keyText.setText(b.toString());
        keyText.setCaretPosition(0);
    }

    public void showFile(RootFile file, RootNode root) {
        StringBuilder b = new StringBuilder();
        line(b, "file", file.getPath().toString());
        line(b, "size", String.format(Locale.ROOT, "%,d bytes", file.getSize()));
        line(b, "format", String.valueOf(file.getFormatVersion()));
        line(b, "pointers", file.getPointerBits() + " bits");
        line(b, "compression", RootFile.compressionName(file.getCompressionAlgorithm())
             + ", level " + file.getCompressionLevel());
        line(b, "data ends at", String.format(Locale.ROOT, "%,d", file.getEnd()));
        line(b, "free list at", String.format(Locale.ROOT, "%,d", file.getFreeSeek()));
        line(b, "class list at", file.getStreamerInfoSeek() > 0
             ? String.format(Locale.ROOT, "%,d (%,d bytes)",
                             file.getStreamerInfoSeek(), file.getStreamerInfoBytes())
             : "absent");
        line(b, "objects", String.valueOf(root.countObjects()));
        b.append('\n').append("what it holds\n");
        try {
            for (String entry : file.classSummary()) {
                b.append("    ").append(entry).append('\n');
            }
        } catch (IOException unreadable) {
            b.append("    the class list could not be read\n");
        }
        fileText.setText(b.toString());
        fileText.setCaretPosition(0);
    }

    private static void line(StringBuilder b, String label, String value) {
        b.append(String.format(Locale.ROOT, "%-16s %s%n", label, value));
    }

    private static String describeHistogram(RootHistogram h) {
        StringBuilder b = new StringBuilder();
        line(b, "class", h.className);
        line(b, "name", h.name);
        line(b, "title", h.title);
        b.append('\n');
        line(b, "bins", String.valueOf(h.xAxis.bins));
        line(b, "range", String.format(Locale.ROOT, "%.6g to %.6g",
                                       h.xAxis.min, h.xAxis.max));
        line(b, "binning", h.xAxis.isVariable() ? "uneven" : "even");
        if (h.dimensions > 1) {
            line(b, "second axis", h.yAxis.toString());
        }
        b.append('\n');
        line(b, "entries", String.format(Locale.ROOT, "%.0f", h.entries));
        line(b, "integral", String.format(Locale.ROOT, "%.6g", h.integral()));
        line(b, "mean", String.format(Locale.ROOT, "%.6g", h.mean()));
        line(b, "std dev", String.format(Locale.ROOT, "%.6g", h.stdDev()));
        line(b, "smallest bin", String.format(Locale.ROOT, "%.6g", h.minContent()));
        line(b, "largest bin", String.format(Locale.ROOT, "%.6g", h.maxContent()));
        line(b, "underflow", String.format(Locale.ROOT, "%.6g", h.underflow()));
        line(b, "overflow", String.format(Locale.ROOT, "%.6g", h.overflow()));
        line(b, "weights kept", h.sumw2.length > 0 ? "yes" : "no, errors are sqrt(n)");
        return b.toString();
    }

    private static String describeGraph(RootGraph g) {
        StringBuilder b = new StringBuilder();
        line(b, "class", g.className);
        line(b, "name", g.name);
        line(b, "title", g.title);
        b.append('\n');
        line(b, "points", String.valueOf(g.size()));
        line(b, "x from", String.format(Locale.ROOT, "%.6g to %.6g",
                                        g.minX(), g.maxX()));
        line(b, "y from", String.format(Locale.ROOT, "%.6g to %.6g",
                                        g.minY(), g.maxY()));
        line(b, "error bars", g.hasErrors() ? "yes" : "no");
        b.append('\n');
        final int shown = Math.min(g.size(), 400);
        b.append(String.format(Locale.ROOT, "%6s %14s %14s%n", "i", "x", "y"));
        for (int i = 0; i < shown; i++) {
            b.append(String.format(Locale.ROOT, "%6d %14.6g %14.6g%n",
                                   i, g.x[i], g.y[i]));
        }
        if (shown < g.size()) {
            b.append("    ... ").append(g.size() - shown).append(" more\n");
        }
        return b.toString();
    }

    /** The bins, with the content column open to correction. */
    private final class BinModel extends AbstractTableModel {

        private RootHistogram histogram;

        void set(RootHistogram h) {
            this.histogram = h;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return histogram == null ? 0 : histogram.xAxis.bins;
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "bin";
                case 1 -> "from";
                case 2 -> "to";
                case 3 -> "content";
                default -> "error";
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }

        @Override
        public Object getValueAt(int row, int column) {
            final int bin = row + 1;
            return switch (column) {
                case 0 -> bin;
                case 1 -> format(histogram.xAxis.edge(row));
                case 2 -> format(histogram.xAxis.edge(row + 1));
                case 3 -> format(histogram.content(bin));
                default -> format(histogram.error(bin));
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != 3 || histogram == null) {
                return;
            }
            try {
                final double parsed = Double.parseDouble(value.toString().trim());
                final int bin = row + 1;
                if (bin < histogram.contents.length) {
                    histogram.contents[bin] = parsed;
                    fireTableRowsUpdated(row, row);
                    if (binListener != null) {
                        binListener.binChanged(bin, parsed);
                    }
                }
            } catch (NumberFormatException notANumber) {
                fireTableRowsUpdated(row, row);
            }
        }

        private String format(double value) {
            if (value == Math.rint(value) && Math.abs(value) < 1e9) {
                return String.valueOf((long) value);
            }
            return String.format(Locale.ROOT, "%.6g", value);
        }
    }
}
