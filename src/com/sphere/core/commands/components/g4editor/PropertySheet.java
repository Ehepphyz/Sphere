package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.Define;
import com.sphere.core.geant4.Expr;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.Inputs;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Mass;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.NistDB;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Script;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * FreeCAD's property view for Geant4: the placement, the volume and the solid
 * of the selection, each value an expression with its unit, and what Geant4
 * would compute from them (volume, mass, radiation lengths) underneath.
 * A typed value is checked before it is taken; a wrong one turns red.
 */
final class PropertySheet extends JPanel {

    private static final long serialVersionUID = 1L;

    enum Kind { HEADER, TEXT, LENGTH, ANGLE, NUMBER, BOOL, COLOR, MATERIAL, READONLY }

    /** One row: a property of one object. */
    static final class Prop {
        final String section, label, target, key;
        final Kind kind;
        final Supplier<String> value;
        final Supplier<String> evaluated;
        String tooltip;

        Prop(String section, String label, String target, String key, Kind kind, Supplier<String> value,
             Supplier<String> evaluated) {
            this.section = section;
            this.label = label;
            this.target = target;
            this.key = key;
            this.kind = kind;
            this.value = value;
            this.evaluated = evaluated;
        }
    }

    private final EditorModel model;
    private final Script script;
    private final List<Prop> rows = new ArrayList<>();
    private final Table tableModel = new Table();
    private final JTable table = new JTable(tableModel);
    private final JLabel title = new JLabel(" ");
    private final JPanel tableTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private String error;

    PropertySheet(EditorModel model, Script script) {
        super(new BorderLayout());
        this.model = model;
        this.script = script;
        title.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(tableTools, BorderLayout.SOUTH);
        model.addListener(new EditorModel.Listener() {
            public void selectionChanged() {
                rebuild();
            }

            public void documentChanged(Geometry.Change c) {
                if (!table.isEditing()) {
                    rebuild();
                }
            }
        });
        rebuild();
    }

    // ------------------------------------------------------------------

    void rebuild() {
        rows.clear();
        tableTools.removeAll();
        final PhysicalVolume pv = model.primary();
        final Object focus = model.focus();
        if (pv != null) {
            title.setText(pv.name() + (model.selection().size() > 1 ? "  (+" + (model.selection().size() - 1) + " more)" : ""));
            placement(pv);
            volume(pv.logical());
            solid(pv.logical().solid(), pv.logical().name());
            info(pv.logical(), pv);
        } else if (focus instanceof LogicalVolume lv) {
            title.setText(lv.name());
            volume(lv);
            solid(lv.solid(), lv.name());
            info(lv, null);
        } else if (focus instanceof Define d) {
            title.setText("Variable " + d.name());
            header("Variable");
            rows.add(new Prop("Variable", "Expression", d.name(), "value", Kind.TEXT, d::expr, () -> Units.num(d.value())));
        } else if (focus instanceof Material m) {
            title.setText("Material " + m.name());
            materialInfo(m);
        } else {
            title.setText("Nothing selected");
            final Geometry g = model.geometry;
            header("Document");
            rows.add(new Prop("Document", "Volumes", null, null, Kind.READONLY, () -> String.valueOf(g.volumes().size()), () -> ""));
            rows.add(new Prop("Document", "Placements", null, null, Kind.READONLY, () -> String.valueOf(g.placements().size()), () -> ""));
            rows.add(new Prop("Document", "Drawn copies", null, null, Kind.READONLY, () -> String.valueOf(g.instances().size()), () -> ""));
            rows.add(new Prop("Document", "Variables", null, null, Kind.READONLY, () -> String.valueOf(g.defines().size()), () -> ""));
            if (g.world() != null) {
                volume(g.world());
                solid(g.world().solid(), g.world().name());
            }
        }
        tableModel.fireTableDataChanged();
        tableTools.revalidate();
        tableTools.repaint();
    }

    private void header(String s) {
        rows.add(new Prop(s, s, null, null, Kind.HEADER, () -> "", () -> ""));
    }

    private void placement(PhysicalVolume pv) {
        header("Placement");
        final String t = pv.name();
        rows.add(new Prop("Placement", "Name", t, "#name", Kind.TEXT, pv::name, () -> ""));
        rows.add(new Prop("Placement", "Mother", t, null, Kind.READONLY, () -> pv.mother().name(), () -> ""));
        rows.add(new Prop("Placement", "Copy number", t, "copy", Kind.NUMBER, () -> String.valueOf(pv.copyNumber()), () -> ""));
        if (pv.isReplica()) {
            final PhysicalVolume.Replica r = pv.replica();
            rows.add(new Prop("Placement", "Replica axis", t, null, Kind.READONLY, () -> r.axis().name(), () -> ""));
            rows.add(new Prop("Placement", "Copies", t, null, Kind.READONLY, r::number, () -> String.valueOf(r.count())));
            rows.add(new Prop("Placement", "Width", t, null, Kind.READONLY, r::width,
                () -> Units.best(r.widthValue(), r.axis() == PhysicalVolume.Replica.Axis.kPhi ? Units.Category.ANGLE : Units.Category.LENGTH)));
            return;
        }
        final String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            rows.add(new Prop("Placement", "Position " + axes[i], t, axes[i], Kind.LENGTH, () -> pv.positionExprs()[k],
                () -> Units.best(pv.position().get(k), Units.Category.LENGTH)));
        }
        for (int i = 0; i < 3; i++) {
            final int k = i;
            rows.add(new Prop("Placement", "Rotation " + axes[i], t, "r" + axes[i], Kind.ANGLE, () -> pv.rotationExprs()[k],
                () -> Units.best(pv.rotation().get(k), Units.Category.ANGLE)));
        }
    }

    private void volume(LogicalVolume lv) {
        header("Volume");
        final String t = lv.name();
        rows.add(new Prop("Volume", "Name", t, "#lvname", Kind.TEXT, lv::name, () -> ""));
        rows.add(new Prop("Volume", "Material", t, "material", Kind.MATERIAL, lv::material, () -> {
            final Material m = model.geometry.materials().material(lv.material());
            return m == null ? "unknown" : Units.best(m.density(), Units.Category.DENSITY);
        }));
        rows.add(new Prop("Volume", "Color", t, "color", Kind.COLOR, () -> com.sphere.core.geant4.Gdml.colorHex(lv.color()), () -> ""));
        rows.add(new Prop("Volume", "Opacity", t, "alpha", Kind.NUMBER, () -> Units.num(Math.round(lv.alpha() * 100) / 100.0), () -> ""));
        rows.add(new Prop("Volume", "Visible", t, "visible", Kind.BOOL, () -> String.valueOf(lv.visible()), () -> ""));
        rows.add(new Prop("Volume", "Wireframe", t, "wireframe", Kind.BOOL, () -> String.valueOf(lv.wireframe()), () -> ""));
        rows.add(new Prop("Volume", "Sensitive detector", t, "sensitive", Kind.TEXT,
            () -> lv.sensitive() == null ? "" : lv.sensitive(), () -> lv.sensitive() == null ? "none" : "scored"));
    }

    private void solid(Solid s, String lvName) {
        if (s == null) {
            return;
        }
        final String section = "Solid  " + s.g4Class();
        header(section);
        rows.add(new Prop(section, "Solid name", s.name(), "#solidname", Kind.TEXT, s::name, () -> ""));
        if (s.error() != null) {
            final Prop p = new Prop(section, "Error", null, null, Kind.READONLY, s::error, () -> "");
            rows.add(p);
        }
        for (Solid.Param p : s.params()) {
            final Kind k = switch (p.kind) {
                case LENGTH -> Kind.LENGTH;
                case ANGLE -> Kind.ANGLE;
                default -> Kind.NUMBER;
            };
            final Prop row = new Prop(section, p.label, s.name(), p.key, k, p::expr,
                () -> p.error() != null ? "error" : Units.best(p.value(), p.kind.category));
            row.tooltip = p.key + " (GDML attribute)";
            rows.add(row);
        }
        for (Solid.Table t : s.tables()) {
            for (int r = 0; r < t.size(); r++) {
                for (int c = 0; c < t.columns.length; c++) {
                    final int rr = r, cc = c;
                    final Kind k = t.kinds[c] == Solid.Kind.LENGTH ? Kind.LENGTH : Kind.NUMBER;
                    final Prop row = new Prop(section, t.element + " " + (r + 1) + " " + t.columns[c], s.name(),
                        "#table:" + t.element + ":" + r + ":" + c, k, () -> t.rows().get(rr)[cc],
                        () -> rr < t.size() && s.error() == null ? Units.best(t.value(rr, cc), t.kinds[cc].category) : "");
                    rows.add(row);
                }
            }
            final JButton add = new JButton("+ " + t.element);
            add.setToolTipText("Add a row to " + t.element + " (a copy of the last)");
            add.addActionListener(e -> model.edit("Add " + t.element, () -> {
                final String[] last = t.rows().get(t.size() - 1).clone();
                t.add(last);
            }));
            final JButton rm = new JButton("- " + t.element);
            rm.addActionListener(e -> {
                final int min = t.element.equals("rzpoint") || t.element.equals("twoDimVertex") ? 3 : 2;
                if (t.size() > min) {
                    model.edit("Remove " + t.element, () -> t.remove(t.size() - 1));
                }
            });
            tableTools.add(add);
            tableTools.add(rm);
        }
    }

    private void info(LogicalVolume lv, PhysicalVolume pv) {
        final Solid s = lv.solid();
        if (s == null || s.error() != null) {
            return;
        }
        header("Measured");
        final Material m = model.geometry.materials().material(lv.material());
        rows.add(new Prop("Measured", "Cubic volume", null, null, Kind.READONLY,
            () -> Units.best(s.cubicVolume(), Units.Category.VOLUME), () -> ""));
        rows.add(new Prop("Measured", "Surface", null, null, Kind.READONLY, () -> Units.best(s.surfaceArea(), Units.Category.AREA), () -> ""));
        rows.add(new Prop("Measured", "Mass (with daughters)", null, null, Kind.READONLY,
            () -> Units.best(Mass.of(model.geometry, lv), Units.Category.MASS), () -> ""));
        final BBox e = s.extent();
        rows.add(new Prop("Measured", "Extent", null, null, Kind.READONLY, () -> Units.best(e.size().x(), Units.Category.LENGTH)
            + " x " + Units.best(e.size().y(), Units.Category.LENGTH) + " x " + Units.best(e.size().z(), Units.Category.LENGTH), () -> ""));
        if (m != null) {
            rows.add(new Prop("Measured", "Radiation length X0", null, null, Kind.READONLY,
                () -> Units.best(m.radiationLength(), Units.Category.LENGTH), () -> ""));
            rows.add(new Prop("Measured", "Interaction length", null, null, Kind.READONLY,
                () -> Units.best(m.nuclearInteractionLength(), Units.Category.LENGTH), () -> ""));
            final Vec3 sz = e.size();
            final double thin = s.thickness();
            rows.add(new Prop("Measured", "Thickness in X0", null, null, Kind.READONLY,
                () -> String.format("%.4g X0  (%.3g %%)", thin / m.radiationLength(), 100 * thin / m.radiationLength()), () -> ""));
            rows.add(new Prop("Measured", "Moliere radius", null, null, Kind.READONLY,
                () -> Units.best(m.moliereRadius(), Units.Category.LENGTH), () -> ""));
        }
        if (pv != null) {
            final List<Geometry.Instance> ins = model.geometry.instancesOf(pv);
            if (!ins.isEmpty()) {
                final Vec3 w = ins.get(0).world().translation();
                rows.add(new Prop("Measured", "World position", null, null, Kind.READONLY,
                    () -> Units.best(w.x(), Units.Category.LENGTH) + ", " + Units.best(w.y(), Units.Category.LENGTH) + ", "
                        + Units.best(w.z(), Units.Category.LENGTH), () -> ""));
                rows.add(new Prop("Measured", "Path", null, null, Kind.READONLY, () -> ins.get(0).pathName(), () -> ""));
            }
        }
    }

    private void materialInfo(Material m) {
        header("Material");
        rows.add(new Prop("Material", "Density", null, null, Kind.READONLY, () -> Units.best(m.density(), Units.Category.DENSITY), () -> ""));
        rows.add(new Prop("Material", "State", null, null, Kind.READONLY, () -> m.state().gdml, () -> ""));
        rows.add(new Prop("Material", "X0", null, null, Kind.READONLY, () -> Units.best(m.radiationLength(), Units.Category.LENGTH), () -> ""));
        rows.add(new Prop("Material", "Lambda I", null, null, Kind.READONLY,
            () -> Units.best(m.nuclearInteractionLength(), Units.Category.LENGTH), () -> ""));
        rows.add(new Prop("Material", "Moliere radius", null, null, Kind.READONLY,
            () -> Units.best(m.moliereRadius(), Units.Category.LENGTH), () -> ""));
        rows.add(new Prop("Material", "Critical energy", null, null, Kind.READONLY,
            () -> Units.num(m.criticalEnergy()) + " MeV", () -> ""));
        rows.add(new Prop("Material", "Z effective", null, null, Kind.READONLY, () -> Units.num(m.zEffective()), () -> ""));
        if (m.meanExcitation() > 0) {
            rows.add(new Prop("Material", "Mean excitation", null, null, Kind.READONLY, () -> Units.num(m.meanExcitation()) + " eV", () -> ""));
        }
        header("Composition");
        final double[] w = m.massFractions();
        for (int i = 0; i < m.elements().size(); i++) {
            final int k = i;
            rows.add(new Prop("Composition", m.elements().get(i).symbol, null, null, Kind.READONLY,
                () -> String.format("%.4f %%", 100 * w[k]), () -> ""));
        }
    }

    // ------------------------------------------------------------------
    // Applying an edit
    // ------------------------------------------------------------------

    private void apply(Prop p, String text) {
        error = null;
        if (p.target == null || p.key == null) {
            return;
        }
        final String value = text.trim();
        if (p.key.startsWith("#")) {
            special(p, value);
            return;
        }
        String v = value;
        if (p.kind == Kind.LENGTH || p.kind == Kind.ANGLE) {
            v = Inputs.normalize(value, p.kind == Kind.LENGTH ? Units.Category.LENGTH : Units.Category.ANGLE, model.lengthUnit);
            try {
                Expr.eval(v, model.geometry.scope());
            } catch (GeometryException e) {
                error = e.getMessage();
                repaint();
                return;
            }
        }
        final String fv = v;
        try {
            final String[] fail = {null};
            model.edit("Set " + p.label, () -> {
                try {
                    script.setProperty(p.target, p.key, fv);
                } catch (GeometryException e) {
                    fail[0] = e.getMessage();
                }
            });
            if (fail[0] != null) {
                error = fail[0];
            } else {
                model.log("set " + p.target + "." + p.key + " = " + fv);
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        for (Geometry.Issue i : model.geometry.issues()) {
            if (i.severity() == Geometry.Severity.ERROR && i.message().contains(p.target)) {
                error = i.message();
            }
        }
        repaint();
    }

    private void special(Prop p, String value) {
        final Geometry g = model.geometry;
        Object target = null;
        switch (p.key) {
            case "#name" -> target = g.placement(p.target);
            case "#lvname" -> target = g.volume(p.target);
            case "#solidname" -> target = g.solid(p.target);
            default -> {
                if (p.key.startsWith("#table:")) {
                    final String[] f = p.key.split(":");
                    final Solid s = g.solid(p.target);
                    final Solid.Table t = s.table(f[1]);
                    final int r = Integer.parseInt(f[2]), c = Integer.parseInt(f[3]);
                    final String v = Inputs.normalize(value, t.kinds[c].category, model.lengthUnit);
                    model.edit("Set " + p.label, () -> t.set(r, c, v));
                    model.log("# " + s.name() + "." + t.element + "[" + r + "]." + t.columns[c] + " = " + v);
                    return;
                }
            }
        }
        if (target == null || value.equals(p.target)) {
            return;
        }
        final Object tg = target;
        final boolean[] ok = {false};
        model.edit("Rename " + p.target, () -> ok[0] = g.rename(tg, value));
        if (!ok[0]) {
            error = "the name " + value + " is taken or invalid";
        } else {
            model.log("rename " + p.target + " " + value);
        }
    }

    // ------------------------------------------------------------------
    // Table
    // ------------------------------------------------------------------

    private final class Table extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Property" : c == 1 ? "Value" : "Evaluates to";
        }

        public Object getValueAt(int r, int c) {
            final Prop p = rows.get(r);
            if (p.kind == Kind.HEADER) {
                return c == 0 ? p.label : "";
            }
            try {
                return c == 0 ? p.label : c == 1 ? p.value.get() : p.evaluated.get();
            } catch (RuntimeException e) {
                return "";
            }
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            final Prop p = rows.get(r);
            return c == 1 && p.kind != Kind.HEADER && p.kind != Kind.READONLY && p.key != null;
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (v != null) {
                apply(rows.get(r), v.toString());
            }
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        table.getColumnModel().getColumn(1).setCellEditor(new Editor());
    }

    /** The editor fits the row: text, a checkbox, a material list, a colour chooser. */
    private final class Editor extends AbstractCellEditor implements TableCellEditor {
        private static final long serialVersionUID = 1L;
        private TableCellEditor delegate;

        public Object getCellEditorValue() {
            return delegate == null ? null : delegate.getCellEditorValue();
        }

        public Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int row, int col) {
            final Prop p = rows.get(row);
            switch (p.kind) {
                case BOOL -> {
                    final JCheckBox cb = new JCheckBox();
                    cb.setSelected(Boolean.parseBoolean(String.valueOf(value)));
                    cb.setHorizontalAlignment(SwingConstants.LEFT);
                    delegate = new DefaultCellEditor(cb) {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Object getCellEditorValue() {
                            return String.valueOf(cb.isSelected());
                        }
                    };
                    cb.addActionListener(e -> stopCellEditing());
                }
                case MATERIAL -> {
                    final JComboBox<String> box = new JComboBox<>();
                    box.setEditable(true);
                    for (Material m : model.geometry.materials().defined()) {
                        box.addItem(m.name());
                    }
                    for (Material m : NistDB.get().materials()) {
                        box.addItem(m.name());
                    }
                    box.setSelectedItem(value);
                    box.setMaximumRowCount(24);
                    delegate = new DefaultCellEditor(box);
                }
                case COLOR -> {
                    final Color c0 = parse(String.valueOf(value));
                    final Color c = JColorChooser.showDialog(table, "Color of " + p.target, c0);
                    final JTextField f = new JTextField(c == null ? String.valueOf(value)
                        : String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c0.getAlpha()));
                    delegate = new DefaultCellEditor(f);
                }
                default -> {
                    final JTextField f = new JTextField(String.valueOf(value));
                    f.setBorder(BorderFactory.createLineBorder(new Color(0x4A7FD0)));
                    delegate = new DefaultCellEditor(f);
                    ((DefaultCellEditor) delegate).setClickCountToStart(1);
                }
            }
            delegate.addCellEditorListener(new javax.swing.event.CellEditorListener() {
                public void editingStopped(javax.swing.event.ChangeEvent e) {
                    Editor.this.fireEditingStopped();
                }

                public void editingCanceled(javax.swing.event.ChangeEvent e) {
                    Editor.this.fireEditingCanceled();
                }
            });
            return delegate.getTableCellEditorComponent(t, value, selected, row, col);
        }

        @Override
        public boolean stopCellEditing() {
            return delegate == null || delegate.stopCellEditing();
        }

        @Override
        public void cancelCellEditing() {
            if (delegate != null) {
                delegate.cancelCellEditing();
            }
        }
    }

    private static Color parse(String hex) {
        try {
            final int v = (int) Long.parseLong(hex.replace("#", "").substring(0, 6), 16);
            final int a = hex.length() >= 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255;
            return new Color((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, a);
        } catch (RuntimeException e) {
            return Color.GRAY;
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            final JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            final Prop p = rows.get(row);
            l.setIcon(null);
            l.setToolTipText(p.tooltip);
            final Color base = t.getBackground();
            final boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) / 3 < 110;
            if (p.kind == Kind.HEADER) {
                l.setFont(l.getFont().deriveFont(Font.BOLD));
                l.setBackground(dark ? new Color(52, 60, 74) : new Color(224, 230, 240));
                l.setForeground(dark ? new Color(220, 226, 236) : new Color(30, 40, 60));
                return l;
            }
            l.setFont(l.getFont().deriveFont(Font.PLAIN));
            if (!sel) {
                l.setBackground(base);
                l.setForeground(p.kind == Kind.READONLY && col == 1 ? (dark ? new Color(170, 178, 190) : new Color(70, 80, 95))
                    : t.getForeground());
            }
            if (col == 2) {
                l.setForeground(dark ? new Color(140, 200, 160) : new Color(30, 120, 70));
                final String s = String.valueOf(value);
                if (s.equals("error")) {
                    l.setForeground(new Color(210, 50, 50));
                }
            }
            if (col == 1 && p.kind == Kind.COLOR) {
                final Color c = parse(String.valueOf(value));
                l.setIcon(Icons.dot(14, c));
            }
            if (col == 1 && p.kind == Kind.BOOL) {
                l.setText(Boolean.parseBoolean(String.valueOf(value)) ? "yes" : "no");
            }
            if (error != null && t.getSelectedRow() == row && col == 1) {
                l.setForeground(new Color(210, 50, 50));
                l.setToolTipText(error);
            }
            return l;
        }
    }

    String lastError() {
        return error;
    }
}
