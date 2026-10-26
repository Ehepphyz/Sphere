package com.sphere.components.g4editor;

import com.sphere.core.geant4.Define;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.Inputs;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.NistDB;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Units;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The material library and the variables of the document. */
final class LibraryPanels {

    private LibraryPanels() {
    }

    // ==================================================================
    // Materials
    // ==================================================================

    static final class Materials extends JPanel {
        private static final long serialVersionUID = 1L;
        private final EditorModel model;
        private final JTextField filter = new JTextField();
        private final List<Material> shown = new ArrayList<>();
        private final AbstractTableModel tm;
        private final JTable table;
        private final JLabel current = new JLabel();
        private final Consumer<String> console;

        Materials(EditorModel model, Consumer<String> console) {
            super(new BorderLayout(0, 4));
            this.model = model;
            this.console = console;
            tm = new AbstractTableModel() {
                private static final long serialVersionUID = 1L;
                final String[] cols = {"Material", "Density", "X0", "Lambda I"};

                public int getRowCount() {
                    return shown.size();
                }

                public int getColumnCount() {
                    return 4;
                }

                @Override
                public String getColumnName(int c) {
                    return cols[c];
                }

                public Object getValueAt(int r, int c) {
                    final Material m = shown.get(r);
                    return switch (c) {
                        case 0 -> m.name();
                        case 1 -> Units.best(m.density(), Units.Category.DENSITY);
                        case 2 -> Units.best(m.radiationLength(), Units.Category.LENGTH);
                        default -> Units.best(m.nuclearInteractionLength(), Units.Category.LENGTH);
                    };
                }
            };
            table = new JTable(tm);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoCreateRowSorter(true);
            table.getColumnModel().getColumn(0).setPreferredWidth(170);
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                private static final long serialVersionUID = 1L;

                @Override
                public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                    final JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, s, f, r, c);
                    final Material m = shown.get(t.convertRowIndexToModel(r));
                    if (c == 0) {
                        final int col = com.sphere.core.geant4.MaterialColors.colorFor(m.name());
                        l.setIcon(Icons.dot(12, new Color((col >> 16) & 0xFF, (col >> 8) & 0xFF, col & 0xFF)));
                        if (!m.isNist()) {
                            l.setText(m.name() + "  (document)");
                        }
                    } else {
                        l.setIcon(null);
                    }
                    return l;
                }
            });
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    final Material m = selected();
                    if (m == null) {
                        return;
                    }
                    if (e.getClickCount() == 2) {
                        apply(m);
                    } else {
                        model.material = m.name();
                        updateCurrent();
                    }
                }
            });
            filter.setToolTipText("Filter: words of the name (WATER, PB, SCINT...) or a common name (lead, water, scintillator)");
            filter.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    refresh();
                }

                public void removeUpdate(DocumentEvent e) {
                    refresh();
                }

                public void changedUpdate(DocumentEvent e) {
                    refresh();
                }
            });
            final JPanel top = new JPanel(new BorderLayout(4, 0));
            top.add(new JLabel(" Search "), BorderLayout.WEST);
            top.add(filter, BorderLayout.CENTER);
            final JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            final JButton apply = new JButton("Apply to selection");
            apply.addActionListener(e -> {
                final Material m = selected();
                if (m != null) {
                    apply(m);
                }
            });
            final JButton define = new JButton("New material...");
            define.setToolTipText("Define a mixture in the console: material <name> <density> atoms|fractions El:n ...");
            define.addActionListener(e -> console.accept("material MyMix 1.032g/cm3 atoms C:9 H:10"));
            bottom.add(apply);
            bottom.add(define);
            final JPanel south = new JPanel(new BorderLayout());
            current.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            south.add(current, BorderLayout.NORTH);
            south.add(bottom, BorderLayout.SOUTH);
            add(top, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);
            model.addListener(new EditorModel.Listener() {
                public void documentChanged(Geometry.Change c) {
                    if (c == Geometry.Change.ALL || c == Geometry.Change.MATERIALS) {
                        refresh();
                    }
                }
            });
            refresh();
        }

        private Material selected() {
            final int r = table.getSelectedRow();
            return r < 0 ? null : shown.get(table.convertRowIndexToModel(r));
        }

        private void apply(Material m) {
            final List<PhysicalVolume> sel = new ArrayList<>(model.selection());
            if (sel.isEmpty()) {
                model.material = m.name();
                updateCurrent();
                return;
            }
            model.edit("Material " + m.name(), () -> {
                for (PhysicalVolume pv : sel) {
                    final LogicalVolume lv = pv.logical();
                    final boolean recolor = lv.color() == com.sphere.core.geant4.MaterialColors.colorFor(lv.material());
                    lv.setMaterial(m.name());
                    if (recolor) {
                        lv.setColor(com.sphere.core.geant4.MaterialColors.colorFor(m.name()));
                    }
                }
            });
            for (PhysicalVolume pv : sel) {
                model.log("set " + pv.logical().name() + ".material = " + m.name());
            }
        }

        void updateCurrent() {
            current.setText("New volumes: " + model.material);
        }

        void refresh() {
            shown.clear();
            final String f = filter.getText().trim();
            final String common = com.sphere.core.geant4.Intent.material(f);
            shown.addAll(model.geometry.materials().defined());
            if (common != null && !f.isEmpty()) {
                final Material m = NistDB.get().material(common);
                if (m != null) {
                    shown.add(m);
                }
            }
            for (Material m : NistDB.get().search(f)) {
                if (!shown.contains(m)) {
                    shown.add(m);
                }
            }
            tm.fireTableDataChanged();
            updateCurrent();
        }
    }

    // ==================================================================
    // Variables
    // ==================================================================

    static final class Variables extends JPanel {
        private static final long serialVersionUID = 1L;
        private final EditorModel model;
        private final List<Define> list = new ArrayList<>();
        private final AbstractTableModel tm;
        private final JTable table;

        Variables(EditorModel model) {
            super(new BorderLayout());
            this.model = model;
            tm = new AbstractTableModel() {
                private static final long serialVersionUID = 1L;

                public int getRowCount() {
                    return list.size();
                }

                public int getColumnCount() {
                    return 3;
                }

                @Override
                public String getColumnName(int c) {
                    return c == 0 ? "Name" : c == 1 ? "Expression" : "Value";
                }

                public Object getValueAt(int r, int c) {
                    final Define d = list.get(r);
                    return switch (c) {
                        case 0 -> d.name();
                        case 1 -> d.effectiveExpr();
                        default -> d.error() != null ? "error: " + d.error() : guess(d);
                    };
                }

                @Override
                public boolean isCellEditable(int r, int c) {
                    return c == 1;
                }

                @Override
                public void setValueAt(Object v, int r, int c) {
                    final Define d = list.get(r);
                    final String e = Inputs.normalize(String.valueOf(v), Units.Category.NONE, model.lengthUnit);
                    model.edit("Variable " + d.name(), () -> {
                        d.setKind(Define.Kind.CONSTANT);
                        d.setUnit(null);
                        d.setExpr(e);
                    });
                    model.log("var " + d.name() + " = " + e);
                }
            };
            table = new JTable(tm);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            table.getSelectionModel().addListSelectionListener(e -> {
                final int r = table.getSelectedRow();
                if (!e.getValueIsAdjusting() && r >= 0 && r < list.size()) {
                    model.focus(list.get(r));
                }
            });
            final JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            final JButton add = new JButton("New variable");
            add.addActionListener(e -> {
                final String n = model.geometry.uniqueName("par");
                model.edit("Variable " + n, () -> model.geometry.addDefine(new Define(n, Define.Kind.CONSTANT, "10*" + model.lengthUnit)));
                model.log("var " + n + " = 10*" + model.lengthUnit);
            });
            final JButton rm = new JButton("Delete");
            rm.addActionListener(e -> {
                final int r = table.getSelectedRow();
                if (r >= 0) {
                    final String n = list.get(r).name();
                    model.edit("Delete " + n, () -> model.geometry.removeDefine(n));
                    model.log("delete " + n);
                }
            });
            final JLabel hint = new JLabel("  Variables feed every expression: change one, the detector follows.");
            hint.setForeground(new Color(0x7D8796));
            tools.add(add);
            tools.add(rm);
            add(new JScrollPane(table), BorderLayout.CENTER);
            final JPanel south = new JPanel(new BorderLayout());
            south.add(tools, BorderLayout.NORTH);
            south.add(hint, BorderLayout.SOUTH);
            add(south, BorderLayout.SOUTH);
            model.addListener(new EditorModel.Listener() {
                public void documentChanged(Geometry.Change c) {
                    refresh();
                }
            });
            refresh();
        }

        /** A value shown with the unit its expression suggests. */
        private static String guess(Define d) {
            final String e = d.effectiveExpr();
            if (e.matches(".*\\b(mm|cm|m|um|nm|km|micrometer|meter)\\b.*")) {
                return Units.best(d.value(), Units.Category.LENGTH);
            }
            if (e.matches(".*\\b(deg|rad|mrad)\\b.*")) {
                return Units.best(d.value(), Units.Category.ANGLE);
            }
            if (e.matches(".*\\b(MeV|GeV|keV|eV|TeV)\\b.*")) {
                return Units.best(d.value(), Units.Category.ENERGY);
            }
            return Units.num(d.value());
        }

        void refresh() {
            if (table.isEditing()) {
                return;
            }
            list.clear();
            list.addAll(model.geometry.defines());
            tm.fireTableDataChanged();
        }
    }
}
