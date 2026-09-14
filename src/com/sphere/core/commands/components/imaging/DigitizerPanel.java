package com.sphere.components.imaging;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Turns a published plot back into numbers.
 *
 * Two clicks per axis fix the mapping from pixels to data, then every click on
 * the curve lands in the table. The result leaves as CSV or as a Python
 * assignment ready for the notebook.
 */
public final class DigitizerPanel extends JPanel {

    private final ImageCanvas canvas;
    private final Digitizer digitizer;
    private final PointTable table = new PointTable();
    private final JLabel status = new JLabel();
    private final JTextField seriesName = new JTextField("series");

    private final JButton[] references = new JButton[4];

    public DigitizerPanel(ImageCanvas canvas) {
        this.canvas = canvas;
        this.digitizer = canvas.getDigitizer();

        setLayout(new BorderLayout());
        setBackground(ImagingTheme.panel());
        add(ImagingTheme.sectionLabel("Plot digitizer"), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        canvas.setDigitizerListener(this::refresh);
        refresh();
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
        java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
        try {
            g.setColor(ImagingTheme.panel());
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    private JComponent buildBody() {
        ImagingTheme.Surface body = ImagingTheme.stack(false);
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        status.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        status.setForeground(ImagingTheme.subduedText());
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        body.add(status);

        body.add(axisRow("x axis", Digitizer.Axis.X, 0));
        body.add(axisRow("y axis", Digitizer.Axis.Y, 2));

        ImagingTheme.Surface picking = ImagingTheme.strip(false);
        picking.setAlignmentX(Component.LEFT_ALIGNMENT);
        picking.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));

        JButton pick = ImagingTheme.textButton("Pick points",
            "Click along the curve; each click adds a point");
        pick.addActionListener(e -> {
            canvas.cancelReference();
            canvas.setTool(ImageCanvas.Tool.DIGITIZE);
            refresh();
        });
        JButton undo = ImagingTheme.textButton("Undo point", "Remove the last point");
        undo.addActionListener(e -> {
            digitizer.removeLast();
            canvas.repaint();
            refresh();
        });
        JButton clear = ImagingTheme.textButton("Clear", "Remove every point");
        clear.addActionListener(e -> {
            digitizer.clearPicked();
            canvas.repaint();
            refresh();
        });
        picking.add(pick);
        picking.add(Box.createHorizontalStrut(4));
        picking.add(undo);
        picking.add(Box.createHorizontalStrut(4));
        picking.add(clear);
        picking.add(Box.createHorizontalGlue());
        body.add(picking);

        table.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        table.setBackground(ImagingTheme.panel());
        table.setForeground(ImagingTheme.text());
        table.setGridColor(ImagingTheme.border());
        table.setRowHeight(19);
        table.getTableHeader().setFont(ImagingTheme.uiFont(Font.BOLD, 10f));
        table.getTableHeader().setBackground(ImagingTheme.surface());
        table.getTableHeader().setForeground(ImagingTheme.subduedText());

        // Nimbus paints a table from its own defaults, so the cells and the empty
        // area below them are drawn here instead.
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable host, Object value, boolean selected, boolean focused,
                    int row, int column) {
                java.awt.Component cell = super.getTableCellRendererComponent(
                    host, value, selected, focused, row, column);
                cell.setBackground(selected
                    ? ImagingTheme.palette().getTabEditorSelectBg() : ImagingTheme.panel());
                cell.setForeground(ImagingTheme.text());
                cell.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
                if (cell instanceof javax.swing.JComponent jc) {
                    jc.setOpaque(true);
                    jc.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                }
                return cell;
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setOpaque(true);
        scroll.setBackground(ImagingTheme.panel());
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(ImagingTheme.panel());
        scroll.setPreferredSize(new Dimension(214, 150));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(BorderFactory.createLineBorder(ImagingTheme.border()));
        scroll.getViewport().setBackground(ImagingTheme.panel());
        body.add(Box.createVerticalStrut(6));
        body.add(scroll);

        ImagingTheme.Surface naming = ImagingTheme.panelOf(new BorderLayout(6, 0));
        naming.setAlignmentX(Component.LEFT_ALIGNMENT);
        naming.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        naming.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JLabel caption = new JLabel("Series");
        caption.setForeground(ImagingTheme.subduedText());
        caption.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        seriesName.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        seriesName.setBackground(ImagingTheme.surface());
        seriesName.setForeground(ImagingTheme.text());
        seriesName.setCaretColor(ImagingTheme.text());
        seriesName.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ImagingTheme.border()),
            BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        naming.add(caption, BorderLayout.WEST);
        naming.add(seriesName, BorderLayout.CENTER);
        body.add(naming);

        ImagingTheme.Surface export = ImagingTheme.strip(false);
        export.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton copyCsv = ImagingTheme.textButton("Copy CSV", "Put the table on the clipboard");
        copyCsv.addActionListener(e -> copy(digitizer.toCsv()));
        JButton copyPython = ImagingTheme.textButton("Copy Python",
            "Two lists, ready to paste into a notebook cell");
        copyPython.addActionListener(e -> {
            digitizer.setSeriesName(seriesName.getText());
            copy(digitizer.toPython());
        });
        JButton saveCsv = ImagingTheme.textButton("Save…", "Write the table to a .csv file");
        saveCsv.addActionListener(e -> saveCsv());

        export.add(copyCsv);
        export.add(Box.createHorizontalStrut(4));
        export.add(copyPython);
        export.add(Box.createHorizontalStrut(4));
        export.add(saveCsv);
        export.add(Box.createHorizontalGlue());
        body.add(Box.createVerticalStrut(4));
        body.add(export);
        body.add(Box.createVerticalGlue());
        return body;
    }

    private JComponent axisRow(String title, Digitizer.Axis axis, int slot) {
        ImagingTheme.Surface row = ImagingTheme.strip(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JLabel caption = new JLabel(title);
        caption.setForeground(ImagingTheme.subduedText());
        caption.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        caption.setPreferredSize(new Dimension(46, 16));
        row.add(caption);

        for (int i = 0; i < 2; i++) {
            final boolean second = i == 1;
            JButton button = ImagingTheme.textButton(second ? "ref 2" : "ref 1",
                "Click a point on the " + title + " whose value you know");
            button.addActionListener(e -> canvas.awaitReference(axis, second));
            references[slot + i] = button;
            row.add(button);
            row.add(Box.createHorizontalStrut(3));
        }

        JComboBox<String> scale = new JComboBox<>(new String[] {"linear", "log"});
        scale.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        scale.setBackground(ImagingTheme.surface());
        scale.setForeground(ImagingTheme.text());
        scale.setFocusable(false);
        scale.setMaximumSize(new Dimension(74, 24));
        scale.addActionListener(e -> {
            digitizer.setScale(axis, scale.getSelectedIndex() == 1
                ? Digitizer.Scale.LOG10 : Digitizer.Scale.LINEAR);
            refresh();
        });
        row.add(scale);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private void copy(String text) {
        com.sphere.components.ClipboardBridge.write(text);
    }

    private void saveCsv() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setSelectedFile(new File(seriesName.getText().trim() + ".csv"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        try (PrintWriter out = new PrintWriter(chooser.getSelectedFile(),
                                               java.nio.charset.StandardCharsets.UTF_8)) {
            out.print(digitizer.toCsv());
        } catch (Exception e) {
            javax.swing.JOptionPane.showMessageDialog(this,
                "Could not write the file: " + e.getMessage(),
                "Save", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refresh() {
        for (int i = 0; i < 4; i++) {
            final Digitizer.Axis axis = i < 2 ? Digitizer.Axis.X : Digitizer.Axis.Y;
            final boolean second = (i % 2) == 1;
            Digitizer.Reference ref = digitizer.getReference(axis, second);
            references[i].setText(ref == null
                ? (second ? "ref 2" : "ref 1")
                : String.format(Locale.ROOT, "%.4g", ref.value));
        }

        final String missing = digitizer.missing();
        if (!missing.isEmpty()) {
            status.setForeground(ImagingTheme.subduedText());
            status.setText("<html><body style='width:200px'>" + missing + "</body></html>");
        } else {
            status.setForeground(ImagingTheme.palette().getSuccess());
            status.setText(digitizer.getPicked().size() + " point"
                + (digitizer.getPicked().size() == 1 ? "" : "s") + " read from the plot");
        }
        table.fireTableDataChanged();
    }

    /** The picked points, in the plot's own units. */
    private final class PointTable extends JTable {
        private final AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return digitizer.getPicked().size();
            }

            @Override
            public int getColumnCount() {
                return 3;
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> "#";
                    case 1 -> "x";
                    default -> "y";
                };
            }

            @Override
            public Object getValueAt(int row, int column) {
                if (column == 0) {
                    return row + 1;
                }
                List<Point2D.Double> data = digitizer.toDataSeries();
                if (row >= data.size()) {
                    return "";
                }
                final double v = column == 1 ? data.get(row).x : data.get(row).y;
                if (Double.isNaN(v)) {
                    return "—";
                }
                return String.format(Locale.ROOT, "%.6g", v);
            }
        };

        PointTable() {
            setModel(model);
            setShowVerticalLines(false);
            getColumnModel().getColumn(0).setMaxWidth(34);
        }

        void fireTableDataChanged() {
            model.fireTableDataChanged();
        }
    }
}
