package com.sphere.components.g4editor;

import com.sphere.core.geant4.BooleanSolid;
import com.sphere.core.geant4.CppExport;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.GeometryFiles;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.MaterialMatcher;
import com.sphere.core.geant4.NistDB;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Script;
import com.sphere.core.geant4.TGeoReader;
import com.sphere.core.geant4.Templates;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * G4editor: a FreeCAD-style editor for Geant4 geometries, inside Sphere.
 *
 * Volumes are drawn with the mouse on a work plane and dimensioned from the
 * keyboard as they are drawn; every value is an expression with units that
 * may name the design's variables; the model is GDML, so Geant4 reads what
 * the editor saves. Around the drawing: the volume tree, the property view,
 * a console that speaks commands and sentences, a design assistant, overlap
 * and material-budget analyses, and runs in Geant4 through geant4-bridge.
 */
public final class G4EditorFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static G4EditorFrame shared;

    private final EditorModel model;
    private final Viewport vp;
    private final ModelTree tree;
    private final PropertySheet props;
    private final ConsolePanel console = new ConsolePanel();
    private final AnalysisPanel analysis;
    private final ClashWatch clashes;
    private final RunPanel runPanel;
    private final Script script;
    private final JLabel status = new JLabel(" ");
    private final JLabel hover = new JLabel(" ");
    private final JTabbedPane bottom = new JTabbedPane();
    private final List<AbstractButton> toolButtons = new ArrayList<>();
    private final JComboBox<String> materialBox = new JComboBox<>();
    private final Function<String, String> settings;
    private JSplitPane centerSplit;
    private JMenu recentMenu;
    private JMenuItem undoItem;
    private JMenuItem redoItem;
    /** The files opened and saved last, most recent first (kept across sessions). */
    private static final Preferences PREFS = Preferences.userNodeForPackage(G4EditorFrame.class);
    private static final int MAX_RECENT = 12;

    public G4EditorFrame(Path file, Function<String, String> settings) {
        super("G4editor");
        this.settings = settings == null ? k -> null : settings;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1440, 900);
        setLocationRelativeTo(null);

        model = new EditorModel(Geometry.blank());
        vp = new Viewport(model);
        vp.darkTheme = isDark();
        script = new Script(model.geometry, new Host());
        tree = new ModelTree(model);
        props = new PropertySheet(model, script);
        analysis = new AnalysisPanel(model, vp, console::run);
        clashes = new ClashWatch(model, vp, console::print);
        runPanel = new RunPanel(model, vp, this.settings, s -> SwingUtilities.invokeLater(() -> console.print(s)));
        console.setScript(script);
        model.addLog(console::logged);

        // Left: model tree, materials, variables above the property view (FreeCAD's combo view)
        final JTabbedPane leftTabs = new JTabbedPane();
        leftTabs.addTab("Model", tree);
        final LibraryPanels.Materials materials = new LibraryPanels.Materials(model, console::setInput);
        leftTabs.addTab("Materials", materials);
        leftTabs.addTab("Variables", new LibraryPanels.Variables(model));
        final JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, leftTabs, props);
        left.setResizeWeight(0.45);
        left.setDividerLocation(360);

        bottom.addTab("Console", console);
        bottom.addTab("Analyses", analysis);
        bottom.addTab("Run in Geant4", Icons.run(14), runPanel);
        final JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, vp, bottom);
        center.setResizeWeight(0.7);
        centerSplit = center;
        final JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, center);
        main.setDividerLocation(360);

        final JPanel statusBar = new JPanel(new BorderLayout(10, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusBar.add(status, BorderLayout.CENTER);
        statusBar.add(hover, BorderLayout.EAST);
        hover.setForeground(new Color(0x7D8796));

        final JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar(), BorderLayout.CENTER);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(menus());
        // the tree keeps its own drag and drop (reparenting volumes)
        installDrop(getRootPane(), vp);

        vp.setStatusSink(s -> status.setText(s));
        vp.setHoverSink(in -> hover.setText(Viewport.describe(model.geometry, in)));
        vp.setShortcuts(this::shortcut);
        vp.setContextMenuHandler(e -> contextMenu(e, vp));
        tree.setMenu((e, n) -> contextMenu(e, tree.tree()));
        model.addListener(new EditorModel.Listener() {
            public void fileChanged() {
                updateTitle();
                updateUndoItems();
            }

            public void documentChanged(Geometry.Change change) {
                updateUndoItems();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscard()) {
                    runPanel.shutdown();
                    dispose();
                    if (shared == G4EditorFrame.this) {
                        shared = null;
                    }
                }
            }
        });
        try {
            com.sphere.utils.IconManager.applyAppIcon(this);
        } catch (Throwable ignored) {
            // standalone, outside Sphere
        }

        if (file != null) {
            open(file);
        } else {
            console.print("New design: a 2 m air world. Draw with the tools above (B box, T tube, S sphere, C cone, P extrude,"
                + " V revolve), type values while drawing, or describe a detector in the console. File > Open reads GDML"
                + " and ROOT geometries (TGeoManager); Ctrl+Shift+P finds any command.");
        }
        updateTitle();
        vp.updateStatus();
        SwingUtilities.invokeLater(() -> {
            centerSplit.setDividerLocation(0.68);
            vp.fitAll(false);
            vp.requestFocusInWindow();
        });
    }

    /** The open G4editor window, if any. */
    static G4EditorFrame window() {
        return shared != null && shared.isDisplayable() ? shared : null;
    }

    Geometry design() {
        return model.geometry;
    }

    String designTitle() {
        return model.title();
    }

    /** The volumes to take elsewhere: the selection, the outermost of it (the others come with it), else the world's. */
    List<PhysicalVolume> chosen() {
        final List<PhysicalVolume> sel = new ArrayList<>(model.selection());
        if (sel.isEmpty()) {
            for (PhysicalVolume pv : model.geometry.placements()) {
                if (pv.mother() == model.geometry.world()) {
                    sel.add(pv);
                }
            }
        }
        final List<PhysicalVolume> all = List.copyOf(sel);
        sel.removeIf(pv -> all.stream().anyMatch(q -> q != pv && holds(q.logical(), pv.mother(), new java.util.HashSet<>())));
        return sel;
    }

    private boolean holds(LogicalVolume outer, LogicalVolume lv, Set<LogicalVolume> seen) {
        if (outer == lv) {
            return true;
        }
        if (!seen.add(outer)) {
            return false;
        }
        for (PhysicalVolume d : model.geometry.placements()) {
            if (d.mother() == outer && holds(d.logical(), lv, seen)) {
                return true;
            }
        }
        return false;
    }

    /** The selection, or the whole detector, written into the C++ open in G4code. */
    private void insertIntoG4code() {
        final G4CodeFrame code = G4CodeFrame.window();
        if (code == null) {
            status.setText("Open a Geant4 application in G4code (:g4code) first: the volumes are written into its C++");
            return;
        }
        final List<PhysicalVolume> pvs = chosen();
        if (pvs.isEmpty()) {
            status.setText("Nothing to insert: the design holds no volume");
            return;
        }
        code.insertDesign(model.geometry, pvs, model.title());
        status.setText("Written into G4code: " + pvs.size() + " volume(s) with what they hold");
    }

    /** Opens a design in the shared window, raising it if it already exists. */
    public static G4EditorFrame show(Path file, Function<String, String> settings) {
        if (shared == null || !shared.isDisplayable()) {
            shared = new G4EditorFrame(file, settings);
            shared.setVisible(true);
            return shared;
        }
        if (file != null) {
            shared.open(file);
        }
        shared.setVisible(true);
        shared.toFront();
        return shared;
    }

    private static boolean isDark() {
        final Color c = UIManager.getColor("Panel.background");
        return c != null && (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 110;
    }

    private void updateTitle() {
        setTitle("G4editor - " + model.title() + (model.format() == GeometryFiles.Format.ROOT ? "  [ROOT geometry]" : ""));
    }

    /** "Undo move Box_pv": the menu says what it will undo. */
    private void updateUndoItems() {
        if (undoItem == null) {
            return;
        }
        undoItem.setEnabled(model.canUndo());
        undoItem.setText(model.canUndo() && !model.undoLabel().isBlank() ? "Undo " + model.undoLabel() : "Undo");
        redoItem.setEnabled(model.canRedo());
        redoItem.setText(model.canRedo() && !model.redoLabel().isBlank() ? "Redo " + model.redoLabel() : "Redo");
    }

    // ------------------------------------------------------------------
    // The console's view of the editor
    // ------------------------------------------------------------------

    private final class Host implements Script.Host {
        public void print(String t) {
            console.print(t);
        }

        public void error(String t) {
            console.error(t);
            bottom.setSelectedIndex(0);
        }

        public String lengthUnit() {
            return model.lengthUnit;
        }

        public String material() {
            return model.material;
        }

        public void edit(String label, Runnable change) {
            model.edit(label, change);
        }

        public void select(List<PhysicalVolume> pvs) {
            model.selectAll(pvs);
        }

        public boolean command(String name, List<String> a) {
            switch (name) {
                case "view" -> {
                    if (!a.isEmpty()) {
                        vp.setView(a.get(0), true);
                    }
                }
                case "fit" -> vp.fitAll(true);
                case "colorby", "colourby", "color-by" -> {
                    final ColorScheme.Mode m = a.isEmpty() ? null : ColorScheme.Mode.of(a.get(0));
                    if (m == null) {
                        console.error("colorby document|material|density|x0|depth|sensitive");
                    } else {
                        vp.setColorMode(m);
                    }
                }
                case "isolate" -> {
                    if (!a.isEmpty() && a.get(0).equals("off")) {
                        vp.isolate(false);
                    } else {
                        if (!a.isEmpty()) {
                            final List<PhysicalVolume> pvs = new ArrayList<>();
                            for (String n : a) {
                                final PhysicalVolume pv = model.geometry.placement(n);
                                if (pv != null) {
                                    pvs.add(pv);
                                } else {
                                    final var lv = model.geometry.volume(n);
                                    if (lv != null) {
                                        pvs.addAll(model.geometry.placementsOf(lv));
                                    }
                                }
                            }
                            if (pvs.isEmpty()) {
                                console.error("isolate: no placement or volume named " + String.join(" ", a));
                                return true;
                            }
                            model.selectAll(pvs);
                        }
                        if (!vp.isolate(true)) {
                            console.error("isolate: select volumes first, or name them: isolate <placement|volume> ...");
                        }
                    }
                }
                case "xray" -> vp.setXray(a.isEmpty() || !a.get(0).equals("off"));
                case "section" -> {
                    if (a.isEmpty() || a.get(0).equals("off")) {
                        vp.section = false;
                    } else {
                        vp.section = true;
                        vp.sectionAxis = "xyz".indexOf(a.get(0).charAt(0));
                        if (a.size() > 1) {
                            try {
                                vp.sectionOffset = com.sphere.core.geant4.Expr.eval(com.sphere.core.geant4.Inputs.normalize(a.get(1),
                                    com.sphere.core.geant4.Units.Category.LENGTH, model.lengthUnit), model.geometry.scope());
                            } catch (GeometryException e) {
                                console.error(e.getMessage());
                            }
                        }
                    }
                    vp.repaint();
                }
                case "check" -> {
                    showBottom(analysis);
                    analysis.checkOverlaps(a.isEmpty() ? 1000 : Integer.parseInt(a.get(0)));
                }
                case "budget" -> {
                    showBottom(analysis);
                    analysis.scanBudget(a.isEmpty() || !a.get(0).equals("theta"), 4, 80, false);
                }
                case "mass" -> {
                    showBottom(analysis);
                    analysis.computeMasses();
                }
                case "advise" -> {
                    showBottom(analysis);
                    analysis.showAssistant();
                }
                case "resolve" -> resolveClash();
                case "clashes" -> {
                    clashes.setEnabled(a.isEmpty() || !a.get(0).equals("off"));
                    console.print("Live clash check " + (clashes.enabled() ? "on: the selection is checked as it is edited."
                        : "off; F7 still checks the whole design."));
                }
                case "export" -> {
                    if (a.size() >= 2 && a.get(0).equals("gdml")) {
                        export(Path.of(a.get(1)), a.size() > 2 && a.get(2).startsWith("port")
                            ? GeometryFiles.Format.GDML_PORTABLE : GeometryFiles.Format.GDML);
                    } else if (a.size() >= 2 && a.get(0).equals("root")) {
                        export(Path.of(a.get(1)), GeometryFiles.Format.ROOT);
                    } else if (a.size() >= 2 && a.get(0).equals("cpp")) {
                        exportCpp(Path.of(a.get(1)));
                    } else {
                        console.error("export gdml <file> [portable] | export root <file> | export cpp <folder>");
                    }
                }
                case "save" -> save(a.isEmpty() ? null : Path.of(a.get(0)));
                case "open" -> {
                    if (!a.isEmpty()) {
                        open(Path.of(a.get(0)), a.size() > 1 ? a.get(1) : null);
                    }
                }
                case "convert" -> {
                    if (a.size() < 2) {
                        console.error("convert <in> <out> [portable]: the format is the output's extension (.gdml, .root)");
                    } else {
                        final Path out = Path.of(a.get(1));
                        final GeometryFiles.Format f = GeometryFiles.byName(out) == GeometryFiles.Format.ROOT
                            ? GeometryFiles.Format.ROOT
                            : a.size() > 2 && a.get(2).startsWith("port") ? GeometryFiles.Format.GDML_PORTABLE
                            : GeometryFiles.Format.GDML;
                        convert(List.of(Path.of(a.get(0))), out, f);
                    }
                }
                case "nist" -> nistMatch(!a.isEmpty() && a.get(0).equals("apply"));
                case "new" -> newDesign();
                case "undo" -> model.undo();
                case "redo" -> model.redo();
                case "unit" -> {
                    if (!a.isEmpty()) {
                        model.lengthUnit = a.get(0);
                        console.print("lengths typed without unit are now in " + a.get(0));
                    }
                }
                case "run" -> {
                    showBottom(runPanel);
                    console.print("Set the gun in the 'Run in Geant4' tab and press Run (the engine needs Geant4).");
                }
                case "tool" -> {
                    if (!a.isEmpty()) {
                        shortcutTool(a.get(0));
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------

    private JComponent toolbar() {
        final JPanel rows = new JPanel(new java.awt.GridLayout(1, 1));
        final JToolBar t = new JToolBar();
        t.setFloatable(false);
        t.add(button(Icons.file(20, "new"), "New design (Ctrl+N)", this::newDesign));
        t.add(button(Icons.file(20, "open"), "Open a GDML design or a ROOT geometry (Ctrl+O); files can also be dropped"
            + " on the window", this::openDialog));
        t.add(button(Icons.file(20, "save"), "Save (Ctrl+S), in the format of the file: GDML or ROOT", () -> save(null)));
        t.addSeparator();
        t.add(button(Icons.undo(20, false), "Undo (Ctrl+Z)", model::undo));
        t.add(button(Icons.undo(20, true), "Redo (Ctrl+Y)", model::redo));
        t.addSeparator();
        t.add(tool(Icons.select(20), "Select (Esc)", () -> null));
        t.add(tool(Icons.move(20), "Move: drag the gizmo or type a displacement (M)", () -> new Tools.MoveTool(vp, false)));
        t.add(tool(Icons.rotate(20), "Rotate: drag a ring or type angles (R)", () -> new Tools.MoveTool(vp, true)));
        t.add(tool(Icons.measure(20), "Measure distance and radiation lengths crossed (D)", () -> new Tools.MeasureTool(vp)));
        t.addSeparator();
        final Color c = new Color(0x8AA4C8);
        t.add(tool(Icons.box(20, c), "Box, G4Box (B): corner, opposite corner, height", () -> new Tools.BoxTool(vp)));
        t.add(tool(Icons.tube(20, c), "Tube, G4Tubs (T): center, radius, length", () -> new Tools.TubeTool(vp)));
        t.add(tool(Icons.sphere(20, c), "Sphere, G4Orb / G4Sphere (S): center, radius", () -> new Tools.SphereTool(vp)));
        t.add(tool(Icons.cone(20, c), "Cone, G4Cons (C): center, radius, height, top radius", () -> new Tools.ConeTool(vp)));
        t.add(tool(Icons.torus(20, c), "Torus, G4Torus: center, ring radius, tube radius", () -> new Tools.TorusTool(vp)));
        t.add(tool(Icons.extrude(20, c), "Extrusion, G4ExtrudedSolid (P): outline, thickness", () -> new Tools.PolygonTool(vp, false)));
        t.add(tool(Icons.revolve(20, c), "Revolution, G4GenericPolycone (V): (r, z) profile", () -> new Tools.PolygonTool(vp, true)));
        t.add(button(Icons.template(20), "Parametric detectors (templates)", () -> templateMenu(t)));
        t.addSeparator();
        t.add(button(Icons.bool(20, 0), "Union of the two selected volumes", () -> booleanOf(BooleanSolid.Op.UNION)));
        t.add(button(Icons.bool(20, 1), "Subtract the second selected volume from the first", () -> booleanOf(BooleanSolid.Op.SUBTRACTION)));
        t.add(button(Icons.bool(20, 2), "Intersection of the two selected volumes", () -> booleanOf(BooleanSolid.Op.INTERSECTION)));
        t.addSeparator();
        t.add(button(Icons.check(20), "Check overlaps", () -> {
            showBottom(analysis);
            analysis.checkOverlaps(1000);
        }));
        t.add(button(Icons.budget(20), "Material budget scan", () -> {
            showBottom(analysis);
            analysis.scanBudget(true, 4, 80, false);
        }));
        t.add(button(Icons.advise(20), "Design assistant", () -> {
            showBottom(analysis);
            analysis.showAssistant();
        }));
        t.add(button(Icons.run(20), "Run in Geant4", () -> showBottom(runPanel)));
        t.addSeparator();
        t.add(button(Icons.view(20, "iso"), "Isometric view (0)", () -> vp.setView("iso", true)));
        t.add(button(Icons.view(20, "front"), "Front view (1)", () -> vp.setView("front", true)));
        t.add(button(Icons.view(20, "top"), "Top view (2)", () -> vp.setView("top", true)));
        t.add(button(Icons.view(20, "right"), "Right view (3)", () -> vp.setView("right", true)));
        t.add(button(Icons.fit(20), "Fit all (F)", () -> vp.fitAll(true)));
        final JToggleButton section = new JToggleButton(Icons.section(20));
        section.setToolTipText("Section view (X): cut the detector open along a plane");
        section.addActionListener(e -> {
            vp.section = section.isSelected();
            vp.repaint();
        });
        t.add(section);
        final JToggleButton grid = new JToggleButton(Icons.grid(20), true);
        grid.setToolTipText("Snap to the grid (G)");
        grid.addActionListener(e -> vp.gridSnap = grid.isSelected());
        t.add(grid);
        t.addSeparator();
        t.add(new JLabel(" Material "));
        materialBox.setEditable(true);
        materialBox.setMaximumSize(new Dimension(220, 28));
        for (String m : List.of("G4_Al", "G4_Si", "G4_Pb", "G4_Fe", "G4_Cu", "G4_W", "G4_AIR", "G4_Galactic", "G4_WATER",
            "G4_PLASTIC_SC_VINYLTOLUENE", "G4_PbWO4", "G4_lAr", "G4_CONCRETE", "G4_STAINLESS-STEEL", "G4_KAPTON")) {
            materialBox.addItem(m);
        }
        materialBox.setSelectedItem(model.material);
        materialBox.setToolTipText("Material of the volumes you draw next (any NIST name, or a common name: lead, eau, scintillator)");
        materialBox.addActionListener(e -> {
            final String v = String.valueOf(materialBox.getSelectedItem()).trim();
            final String m = model.geometry.materials().material(v) != null ? v : com.sphere.core.geant4.Intent.material(v);
            if (m != null) {
                model.material = m;
                if (!m.equals(v)) {
                    materialBox.setSelectedItem(m);
                }
            } else {
                status.setText("unknown material " + v);
            }
        });
        t.add(materialBox);
        t.add(new JLabel(" Unit "));
        final JComboBox<String> unit = new JComboBox<>(new String[] {"mm", "cm", "m", "um"});
        unit.setMaximumSize(new Dimension(70, 28));
        unit.setSelectedItem(model.lengthUnit);
        unit.setToolTipText("Unit of lengths typed without one");
        unit.addActionListener(e -> model.lengthUnit = String.valueOf(unit.getSelectedItem()));
        t.add(unit);
        t.add(new JLabel(" Plane "));
        final JComboBox<String> plane = new JComboBox<>(new String[] {"XY", "XZ", "YZ"});
        plane.setMaximumSize(new Dimension(70, 28));
        final JTextField offset = new JTextField("0", 5);
        offset.setMaximumSize(new Dimension(70, 28));
        offset.setToolTipText("Offset of the work plane along its normal (Enter)");
        final Runnable applyPlane = () -> {
            double o = 0;
            try {
                o = com.sphere.core.geant4.Expr.eval(com.sphere.core.geant4.Inputs.normalize(offset.getText(),
                    com.sphere.core.geant4.Units.Category.LENGTH, model.lengthUnit), model.geometry.scope());
            } catch (GeometryException ex) {
                status.setText(ex.getMessage());
            }
            vp.plane = switch (String.valueOf(plane.getSelectedItem())) {
                case "XZ" -> WorkPlane.xz(o);
                case "YZ" -> WorkPlane.yz(o);
                default -> WorkPlane.xy(o);
            };
            if (vp.tool != null) {
                vp.tool.fields();
            }
            vp.repaint();
        };
        plane.addActionListener(e -> applyPlane.run());
        offset.addActionListener(e -> applyPlane.run());
        t.add(plane);
        t.add(offset);
        rows.add(t);
        return rows;
    }

    private JButton button(Icon icon, String tip, Runnable r) {
        final JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private JToggleButton tool(Icon icon, String tip, Supplier<Tools.Tool> make) {
        final JToggleButton b = new JToggleButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> {
            for (AbstractButton o : toolButtons) {
                o.setSelected(o == b);
            }
            vp.setTool(make.get());
        });
        toolButtons.add(b);
        if (toolButtons.size() == 1) {
            b.setSelected(true);
        }
        return b;
    }

    private void selectToolButton(int index) {
        for (int i = 0; i < toolButtons.size(); i++) {
            toolButtons.get(i).setSelected(i == index);
        }
    }

    /** Tool letters, FreeCAD-like; also Delete and the console key. */
    private boolean shortcut(KeyEvent e) {
        if (e.isControlDown() || e.isAltDown()) {
            return false;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_B -> shortcutTool("box");
            case KeyEvent.VK_T -> shortcutTool("tube");
            case KeyEvent.VK_S -> shortcutTool("sphere");
            case KeyEvent.VK_C -> shortcutTool("cone");
            case KeyEvent.VK_P -> shortcutTool("extrude");
            case KeyEvent.VK_V -> shortcutTool("revolve");
            case KeyEvent.VK_M -> shortcutTool("move");
            case KeyEvent.VK_R -> shortcutTool("rotate");
            case KeyEvent.VK_D -> shortcutTool("measure");
            case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSelection();
            case KeyEvent.VK_H -> toggleVisibility();
            case KeyEvent.VK_SPACE -> {
                bottom.setSelectedIndex(0);
                console.focusInput();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Applies the remedy of the selection's deepest clash (see ClashWatch), echoed as its console command. */
    private void resolveClash() {
        bottom.setSelectedIndex(0);
        if (!clashes.resolve(console::run)) {
            console.print(clashes.current().isEmpty() ? "Nothing to resolve: the selection overlaps nothing."
                : "No short move clears it (a replica, a volume placed several times, or wedged in): see the Analyses tab.");
        }
    }

    private void shortcutTool(String name) {
        final String[] names = {"select", "move", "rotate", "measure", "box", "tube", "sphere", "cone", "torus", "extrude", "revolve"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                toolButtons.get(i).doClick();
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    private JMenuBar menus() {
        final JMenuBar bar = new JMenuBar();
        final JMenu file = menu("File", 'F');
        file.add(item("New design", "control N", this::newDesign));
        file.add(item("Open...", "control O", this::openDialog,
            "A GDML design, or the geometry (TGeoManager) a ROOT file holds"));
        recentMenu = menu("Open recent", 'R');
        file.add(recentMenu);
        rebuildRecent();
        file.addSeparator();
        file.add(item("Save", "control S", () -> save(null), "In the format of the design's file: GDML or ROOT"));
        file.add(item("Save as...", "control shift S", this::saveAs,
            "GDML (parametric, for Geant4) or ROOT geometry: the file chosen becomes the design's"));
        final JMenu export = menu("Export", 'E');
        export.add(item("GDML for Geant4...", null, () -> exportDialog(GeometryFiles.Format.GDML),
            "The design as it is, expressions and variables kept"));
        export.add(item("GDML for ROOT and Geant4 (numbers)...", null, () -> exportDialog(GeometryFiles.Format.GDML_PORTABLE),
            "Numbers instead of expressions, NIST materials written out, solids TGDMLParse knows: ROOT reads it too"));
        export.add(item("ROOT geometry (TGeoManager)...", "control shift R", () -> exportDialog(GeometryFiles.Format.ROOT),
            "A .root file TGeoManager::Import reads, written without ROOT"));
        export.addSeparator();
        export.add(item("Geant4 C++ project...", "control E", this::exportCppDialog));
        export.add(item("Into G4code's C++...", "control shift I", this::insertIntoG4code,
            "The selection (else the whole detector) written into the code open in G4code, in the volume selected"
                + " there: solids, materials, colours, rotations and what they hold"));
        export.add(item("Image (PNG)...", null, this::exportImage));
        file.add(export);
        file.add(item("Convert files...", null, this::convertDialog,
            "GDML to ROOT or ROOT to GDML, any number of files at once, without opening them"));
        file.addSeparator();
        file.add(item("Close", "control W", () -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
        bar.add(file);

        final JMenu edit = menu("Edit", 'E');
        undoItem = item("Undo", "control Z", model::undo);
        redoItem = item("Redo", "control Y", model::redo);
        edit.add(undoItem);
        edit.add(redoItem);
        edit.addSeparator();
        edit.add(item("Duplicate", "control D", () -> forSelection(pv -> "copy " + pv.name() + " 1")));
        edit.add(item("Delete", null, this::deleteSelection));
        edit.add(item("Select all", "control A", () -> model.selectAll(model.geometry.placements())));
        edit.addSeparator();
        edit.add(item("Fit the world around the detector", null, () -> console.run("world fit")));
        edit.addSeparator();
        edit.add(item("Command palette...", "control shift P", () -> ActionPalette.show(this, getJMenuBar()),
            "Every command of these menus, found by typing a few letters"));
        bar.add(edit);
        updateUndoItems();

        final JMenu view = menu("View", 'V');
        for (String[] v : new String[][] {{"Isometric", "0", "iso"}, {"Front", "1", "front"}, {"Top", "2", "top"},
            {"Right", "3", "right"}, {"Rear", "4", "rear"}, {"Bottom", "5", "bottom"}, {"Left", "6", "left"},
            {"Side (beam to the right)", "7", "side"}, {"Along the beam", "8", "beam"}}) {
            view.add(item(v[0] + "   (" + v[1] + ")", null, () -> vp.setView(v[2], true)));
        }
        view.addSeparator();
        view.add(item("Fit all", null, () -> vp.fitAll(true)));
        view.add(item("Fit selection", null, () -> vp.fitSelection(true)));
        view.add(check("Perspective (O)", vp.camera.perspective, b -> {
            vp.camera.perspective = b;
            vp.repaint();
        }));
        view.add(check("Section view (X)", false, b -> {
            vp.section = b;
            vp.repaint();
        }));
        final JMenu axis = new JMenu("Section plane");
        for (int i = 0; i < 3; i++) {
            final int k = i;
            axis.add(item("Normal to " + "XYZ".charAt(i), null, () -> {
                vp.sectionAxis = k;
                vp.section = true;
                vp.repaint();
            }));
        }
        view.add(axis);
        view.add(check("Envelopes see-through", true, b -> {
            vp.autoTransparent = b;
            vp.repaint();
        }));
        view.addSeparator();
        final JMenu colorBy = new JMenu("Color by (K)");
        final javax.swing.ButtonGroup colorGroup = new javax.swing.ButtonGroup();
        final java.util.Map<ColorScheme.Mode, javax.swing.JRadioButtonMenuItem> colorItems =
            new java.util.EnumMap<>(ColorScheme.Mode.class);
        for (ColorScheme.Mode m : ColorScheme.Mode.values()) {
            final javax.swing.JRadioButtonMenuItem it = new javax.swing.JRadioButtonMenuItem(m.label, m == vp.colorMode());
            it.addActionListener(e -> vp.setColorMode(m));
            colorGroup.add(it);
            colorBy.add(it);
            colorItems.put(m, it);
        }
        view.add(colorBy);
        final JCheckBoxMenuItem isolateItem = check("Isolate selection (I)", false, b -> {
            if (!vp.isolate(b)) {
                status.setText("Select volumes first: Isolate then shows only them, the rest as outlines");
            }
        });
        view.add(isolateItem);
        final JCheckBoxMenuItem xrayItem = check("Isolated: keep the rest as outlines", vp.xray(), vp::setXray);
        view.add(xrayItem);
        vp.setViewOptionsListener(() -> {
            colorItems.get(vp.colorMode()).setSelected(true);
            isolateItem.setSelected(vp.isIsolated());
            xrayItem.setSelected(vp.xray());
        });
        view.add(check("Grid", true, b -> {
            vp.showGrid = b;
            vp.repaint();
        }));
        view.add(check("Draw on the face clicked first", true, b -> vp.faceSnapPlane = b));
        view.add(check("Snap to vertices", true, b -> vp.vertexSnap = b));
        view.add(check("Z up (FreeCAD) instead of Y up (Geant4)", false, b -> vp.setUpAxis(!b)));
        view.add(check("Dark background", vp.darkTheme, b -> {
            vp.darkTheme = b;
            vp.repaint();
        }));
        bar.add(view);

        final JMenu create = menu("Create", 'C');
        for (String[] s : new String[][] {{"Box (B)", "box"}, {"Tube (T)", "tube"}, {"Sphere (S)", "sphere"}, {"Cone (C)", "cone"},
            {"Torus", "torus"}, {"Extrusion (P)", "extrude"}, {"Revolution (V)", "revolve"}}) {
            create.add(item(s[0] + " - draw", null, () -> shortcutTool(s[1])));
        }
        create.addSeparator();
        final JMenu byType = new JMenu("Any Geant4 solid at the origin");
        for (var t : com.sphere.core.geant4.Solids.types()) {
            byType.add(item(t.label() + "  (" + t.g4Class() + ")", null, () -> console.run("solid " + t.tag())));
        }
        create.add(byType);
        final JMenu templates = new JMenu("Parametric detector");
        for (String k : Templates.kinds()) {
            templates.add(item(k, null, () -> console.run("template " + k)));
        }
        create.add(templates);
        bar.add(create);

        final JMenu materials = menu("Materials", 'M');
        materials.add(item("Use NIST equivalents of the defined materials...", null, () -> nistMatch(true),
            "A ROOT geometry defines its materials (Lead, SILICON...): those that are NIST ones become G4_Pb, G4_Si..."));
        materials.add(item("List the materials in the console", null, () -> {
            bottom.setSelectedIndex(0);
            console.run("materials");
        }));
        bar.add(materials);

        final JMenu tools = menu("Analysis", 'A');
        tools.add(item("Check overlaps", "F7", () -> {
            showBottom(analysis);
            analysis.checkOverlaps(1000);
        }));
        tools.add(item("Resolve the clash", "F6", this::resolveClash,
            "Moves the selected volume out of what it overlaps, by the shortest move that leaves it clear"));
        tools.add(item("Material budget scan", "F8", () -> {
            showBottom(analysis);
            analysis.scanBudget(true, 4, 80, false);
        }));
        tools.add(item("Masses", null, () -> {
            showBottom(analysis);
            analysis.computeMasses();
        }));
        tools.add(item("Design assistant", "F9", () -> {
            showBottom(analysis);
            analysis.showAssistant();
        }));
        bar.add(tools);

        final JMenu g4 = menu("Geant4", 'G');
        g4.add(item("Run in Geant4...", "F5", () -> showBottom(runPanel)));
        g4.add(item("Geant4 installations found", null, () -> {
            final var list = com.sphere.core.geantbackend.Geant4Locator.find(settings);
            bottom.setSelectedIndex(0);
            if (list.isEmpty()) {
                console.print("No Geant4 found (settings.conf GEANT4_DIR, PATH, conda, virtual environments; on Windows a "
                    + "Linux GEANT4_DIR such as /home/me/geant4 is looked for inside WSL). G4editor works without it; the "
                    + "engine and the exported project need it to be built.");
            }
            for (var i : list) {
                console.print("  " + i + (i.datasets().isEmpty() ? "  (no data sets found)" : "  " + i.datasets().size() + " data sets"));
            }
        }));
        bar.add(g4);

        final JMenu help = menu("Help", 'H');
        help.add(item("Console commands", null, () -> {
            bottom.setSelectedIndex(0);
            console.run("help");
        }));
        help.add(item("Mouse and keyboard", null, this::showKeys));
        help.add(item("Files: GDML and ROOT", null, this::showFormats));
        bar.add(help);
        // Sphere's dark theme leaves Nimbus's MenuBar:Menu text colour all but the bar's background, and
        // Nimbus overrides a colour that comes from the theme (a UIResource): the theme's text colour, as a
        // plain Color, as Sphere's own window sets its menus'
        final Color text = UIManager.getColor("Label.foreground");
        if (text != null) {
            for (int i = 0; i < bar.getMenuCount(); i++) {
                bar.getMenu(i).setForeground(new Color(text.getRGB()));
            }
        }
        return bar;
    }

    private static JMenu menu(String label, char mnemonic) {
        final JMenu m = new JMenu(label);
        m.setMnemonic(mnemonic);
        return m;
    }

    private JMenuItem item(String label, String accel, Runnable r) {
        final JMenuItem i = new JMenuItem(label);
        if (accel != null) {
            i.setAccelerator(KeyStroke.getKeyStroke(accel));
        }
        i.addActionListener(e -> r.run());
        return i;
    }

    private JMenuItem item(String label, String accel, Runnable r, String tip) {
        final JMenuItem i = item(label, accel, r);
        i.setToolTipText(tip);
        return i;
    }

    private void showFormats() {
        JOptionPane.showMessageDialog(this, """
            G4editor reads and writes three kinds of files; File > Open recognises them by their content.

            GDML for Geant4 (.gdml)
              The design as it is: expressions with units, variables, NIST materials by name, colours,
              sensitive detectors. What Geant4's G4GDMLParser reads, and what undo keeps.

            GDML for ROOT and Geant4 (Export)
              Numbers instead of expressions, every material and element written out, G4Tet and
              G4GenericPolycone as solids ROOT's GDML reader knows. TGeoManager::Import reads it.

            ROOT geometry (.root)
              The TGeoManager a .root file holds, read and written without ROOT, with the class
              descriptions of ROOT 6.36: TGeoManager::Import reads it, and ROOT files open here.
              Shapes become the Geant4 solids of the same surface, divisions become replicas,
              assemblies are opened up, materials named like NIST ones become NIST ones.
              ROOT keeps no sensitive detectors nor editor settings: keep the GDML as the design.

            Convert (File > Convert files...) turns any number of files from one to the other at once;
            in the console: convert in.root out.gdml [portable], export root file.root, nist apply.
            """, "G4editor - files", JOptionPane.INFORMATION_MESSAGE);
    }

    private JCheckBoxMenuItem check(String label, boolean on, java.util.function.Consumer<Boolean> r) {
        final JCheckBoxMenuItem i = new JCheckBoxMenuItem(label, on);
        i.addActionListener(e -> r.accept(i.isSelected()));
        return i;
    }

    private void showKeys() {
        JOptionPane.showMessageDialog(this, """
            Mouse
              left drag: orbit          right or middle drag: pan        wheel: zoom at the cursor
              click: select             Ctrl+click: add to selection     double click: frame it
              right click: menu (ends a drawing tool)       click the navigation cube: standard view
            Drawing
              click points on the work plane; while drawing, type values: 12, 12 cm, 2*Rin+5mm
              Tab: next field (locks the typed one)   Enter: take the step   Esc: cancel
              Shift: horizontal / vertical edge (outlines)   a first click on a face draws on that face
            Keys
              B box  T tube  S sphere  C cone  P extrude  V revolve  M move  R rotate  D measure
              0-8 views   F fit   O perspective   X section   G grid snap   H hide   Delete
              I isolate the selection (again: show all)    K colour by material, density, X0, depth...
              Space: console    Ctrl+Z / Ctrl+Y undo / redo    Ctrl+D duplicate   Ctrl+S save
              Ctrl+Shift+P: every command by name    files (GDML, ROOT) can be dropped on the window
            """, "G4editor - mouse and keyboard", JOptionPane.INFORMATION_MESSAGE);
    }

    private void templateMenu(JComponent anchor) {
        final JPopupMenu m = new JPopupMenu();
        final String[][] t = {
            {"Sampling calorimeter (B4-like, replicated layers)", "template calorimeter"},
            {"Silicon tracker barrel", "template tracker"},
            {"Beam telescope", "template telescope"},
            {"Concrete shielding", "template shield"},
            {"Water phantom with bone", "template phantom"},
            {"TPC drift volume", "template tpc"}};
        for (String[] x : t) {
            m.add(item(x[0], null, () -> console.run(x[1])));
        }
        final java.awt.Point p = anchor.getMousePosition();
        m.show(anchor, p == null ? 0 : p.x, p == null ? anchor.getHeight() : p.y);
    }

    // ------------------------------------------------------------------
    // Selection actions
    // ------------------------------------------------------------------

    private void forSelection(java.util.function.Function<PhysicalVolume, String> command) {
        for (PhysicalVolume pv : new ArrayList<>(model.selection())) {
            console.run(command.apply(pv));
        }
    }

    private void deleteSelection() {
        if (model.selection().isEmpty()) {
            return;
        }
        final StringBuilder b = new StringBuilder("delete");
        for (PhysicalVolume pv : model.selection()) {
            b.append(' ').append(pv.name());
        }
        console.run(b.toString());
    }

    private void toggleVisibility() {
        for (PhysicalVolume pv : new ArrayList<>(model.selection())) {
            console.run((pv.logical().visible() ? "hide " : "show ") + pv.logical().name());
        }
    }

    private void booleanOf(BooleanSolid.Op op) {
        final List<PhysicalVolume> sel = new ArrayList<>(model.selection());
        if (sel.size() != 2) {
            status.setText("Select two volumes (Ctrl+click): the first keeps its name, the second is "
                + (op == BooleanSolid.Op.SUBTRACTION ? "cut out of it" : "merged into it"));
            return;
        }
        console.run(op.tag.replace("subtraction", "subtract").replace("intersection", "intersect") + " "
            + sel.get(0).name() + " " + sel.get(1).name());
    }

    private void contextMenu(MouseEvent e, JComponent on) {
        final JPopupMenu m = new JPopupMenu();
        final PhysicalVolume pv = model.primary();
        if (pv != null) {
            final LogicalVolume lv = pv.logical();
            m.add(item("Frame it", null, () -> vp.fitSelection(true)));
            m.add(item(lv.visible() ? "Hide" : "Show", null, this::toggleVisibility));
            m.add(item("Duplicate", null, () -> forSelection(p -> "copy " + p.name() + " 1")));
            m.add(item("Rename...", null, () -> {
                final String n = JOptionPane.showInputDialog(this, "New name of " + pv.name(), pv.name());
                if (n != null && !n.isBlank()) {
                    console.run("rename " + pv.name() + " " + n.trim());
                }
            }));
            m.add(item(lv.sensitive() == null ? "Make it a sensitive detector" : "No longer sensitive", null,
                () -> console.run("sensitive " + lv.name() + (lv.sensitive() == null ? "" : " none"))));
            m.add(item("New volumes go inside " + lv.name(), null, () -> {
                model.container = lv;
                status.setText("New volumes now go inside " + lv.name() + " (Esc on empty space resets)");
            }));
            final JMenu mat = new JMenu("Material");
            for (String name : List.of("G4_AIR", "G4_Galactic", "G4_Si", "G4_Pb", "G4_W", "G4_Fe", "G4_Cu", "G4_Al", "G4_WATER",
                "G4_PLASTIC_SC_VINYLTOLUENE", "G4_PbWO4", "G4_lAr", "G4_CONCRETE")) {
                mat.add(item(name, null, () -> forSelection(p -> "set " + p.logical().name() + ".material = " + name)));
            }
            m.add(mat);
            m.add(item("Info", null, () -> {
                bottom.setSelectedIndex(0);
                console.run("info " + pv.name());
            }));
            final JMenuItem toCode = item("Insert into G4code (C++)", null, this::insertIntoG4code);
            toCode.setEnabled(G4CodeFrame.window() != null);
            toCode.setToolTipText("Written into the code open in G4code, in the volume selected there");
            m.add(toCode);
            m.addSeparator();
            if (model.selection().size() == 2) {
                m.add(item("Union", null, () -> booleanOf(BooleanSolid.Op.UNION)));
                m.add(item("Subtract second from first", null, () -> booleanOf(BooleanSolid.Op.SUBTRACTION)));
                m.add(item("Intersection", null, () -> booleanOf(BooleanSolid.Op.INTERSECTION)));
                m.addSeparator();
            }
            m.add(item("Delete", null, this::deleteSelection));
        } else {
            m.add(item("Fit all", null, () -> vp.fitAll(true)));
            m.add(item("New volumes go where they are drawn", null, () -> model.container = null));
            m.add(item("Paste a design description into the console", null, () -> {
                bottom.setSelectedIndex(0);
                console.setInput("add a 300 um silicon layer at z = 10 cm");
            }));
        }
        m.show(on, e.getX(), e.getY());
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /** Brings a bottom tab forward, giving it room when the 3D view had taken it all. */
    private void showBottom(java.awt.Component c) {
        bottom.setSelectedComponent(c);
        if (centerSplit != null && bottom.getHeight() < 300 && centerSplit.getHeight() > 600) {
            centerSplit.setDividerLocation(centerSplit.getHeight() - 330);
        }
    }

    private boolean confirmDiscard() {
        if (!model.dirty()) {
            return true;
        }
        final int r = JOptionPane.showConfirmDialog(this, "Save the changes to " + model.title() + "?", "G4editor",
            JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        if (r == JOptionPane.YES_OPTION) {
            return save(null);
        }
        return true;
    }

    private void newDesign() {
        if (confirmDiscard()) {
            model.newDocument();
            vp.tracks.clear();
            vp.trackColors.clear();
            vp.fitAll(true);
        }
    }

    private static final FileFilter GEOMETRY_FILES = new FileNameExtensionFilter("Geometries (*.gdml, *.root)", "gdml",
        "root", "xml");
    private static final FileFilter GDML_FILES = new FileNameExtensionFilter("GDML for Geant4 (*.gdml)", "gdml");
    private static final FileFilter PORTABLE_FILES = new FileNameExtensionFilter("GDML for ROOT and Geant4 (*.gdml)", "gdml");
    private static final FileFilter ROOT_FILES = new FileNameExtensionFilter("ROOT geometry, TGeoManager (*.root)", "root");

    private JFileChooser chooser() {
        final Path dir = model.file() != null ? model.file().getParent() : recentFiles().stream().findFirst()
            .map(Path::getParent).orElse(null);
        return new JFileChooser(dir == null ? null : dir.toFile());
    }

    private void openDialog() {
        if (!confirmDiscard()) {
            return;
        }
        final JFileChooser ch = chooser();
        ch.setDialogTitle("Open a GDML design or a ROOT geometry");
        ch.addChoosableFileFilter(GEOMETRY_FILES);
        ch.addChoosableFileFilter(GDML_FILES);
        ch.addChoosableFileFilter(ROOT_FILES);
        ch.setFileFilter(GEOMETRY_FILES);
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            open(ch.getSelectedFile().toPath(), null);
        }
    }

    public void open(Path p) {
        open(p, null);
    }

    /**
     * Opens a design: a GDML file, or the geometry a ROOT file holds (the
     * user picks one when it holds several). The file is read off the event
     * thread, so a large ROOT geometry does not freeze the window.
     */
    public void open(Path p, String rootName) {
        String name = rootName;
        try {
            if (name == null && GeometryFiles.detect(p) == GeometryFiles.Format.ROOT) {
                final List<TGeoReader.Entry> geos = TGeoReader.geometries(p);
                if (geos.isEmpty()) {
                    console.error(p.getFileName() + " is a ROOT file without a geometry (no TGeoManager in it). The ROOT"
                        + " viewer (:root) opens its histograms and trees.");
                    bottom.setSelectedIndex(0);
                    return;
                }
                if (geos.size() > 1) {
                    final String[] names = geos.stream().map(e -> e.name() + ";" + e.cycle()
                        + (e.title().isBlank() ? "" : "  (" + e.title() + ")")).toArray(String[]::new);
                    final Object pick = JOptionPane.showInputDialog(this, p.getFileName() + " holds " + geos.size()
                        + " geometries. Which one?", "G4editor", JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
                    if (pick == null) {
                        return;
                    }
                    name = geos.get(java.util.Arrays.asList(names).indexOf(pick)).name();
                }
            }
        } catch (Exception e) {
            console.error("cannot open " + p + ": " + e.getMessage());
            bottom.setSelectedIndex(0);
            return;
        }
        final String geometryName = name;
        status.setText("Reading " + p.getFileName() + "...");
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        final long t0 = System.nanoTime();
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                // Read, and the GDML the undo history starts from written here too: both take seconds
                // for a big ROOT geometry, which the window does not wait for
                final GeometryFiles.Loaded r = GeometryFiles.read(p, geometryName);
                return new Object[] {r, com.sphere.core.geant4.Gdml.write(r.geometry())};
            }

            @Override
            protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                try {
                    final Object[] read = get();
                    final GeometryFiles.Loaded r = (GeometryFiles.Loaded) read[0];
                    model.adopt(p, r, (String) read[1]);
                    remember(p);
                    console.ok(String.format(java.util.Locale.ROOT, "opened %s (%s, %.2f s): %d volumes, %d drawn copies", p,
                        r.format().label, (System.nanoTime() - t0) / 1e9, model.geometry.volumes().size(),
                        model.geometry.instances().size()));
                    for (String n : r.notes()) {
                        console.print("  " + n);
                    }
                    if (!r.warnings().isEmpty()) {
                        bottom.setSelectedIndex(0);
                    }
                    vp.tracks.clear();
                    vp.trackColors.clear();
                    vp.fitAll(false);
                    vp.updateStatus();
                } catch (Exception e) {
                    final Throwable c = e.getCause() != null ? e.getCause() : e;
                    console.error("cannot open " + p + ": " + c.getMessage());
                    bottom.setSelectedIndex(0);
                    status.setText(" ");
                }
            }
        }.execute();
    }

    /** Saves in the format of the design's file; to another file, in the format its extension names. */
    private boolean save(Path p) {
        final Path target = p != null ? p : model.file();
        if (target == null) {
            return saveAs();
        }
        final GeometryFiles.Format f = p != null ? GeometryFiles.byName(p) : model.format();
        try {
            final List<String> notes = model.save(target, f);
            remember(target);
            console.ok("saved " + target + " (" + f.label + ")");
            notes.forEach(n -> console.print("  " + n));
            return true;
        } catch (Exception e) {
            console.error("cannot save: " + e.getMessage());
            bottom.setSelectedIndex(0);
            return false;
        }
    }

    private boolean saveAs() {
        final JFileChooser ch = chooser();
        ch.setDialogTitle("Save the design as");
        ch.setAcceptAllFileFilterUsed(false);
        ch.addChoosableFileFilter(GDML_FILES);
        ch.addChoosableFileFilter(ROOT_FILES);
        ch.setFileFilter(model.format() == GeometryFiles.Format.ROOT ? ROOT_FILES : GDML_FILES);
        if (model.file() != null) {
            ch.setSelectedFile(model.file().toFile());
        }
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        final GeometryFiles.Format f = ch.getFileFilter() == ROOT_FILES ? GeometryFiles.Format.ROOT : GeometryFiles.Format.GDML;
        final Path p = withExtension(ch.getSelectedFile().toPath(), f);
        if (!confirmOverwrite(p)) {
            return false;
        }
        if (f == GeometryFiles.Format.ROOT && model.format() != GeometryFiles.Format.ROOT) {
            console.print("  the design now lives in a ROOT file: sensitive detectors and editor settings are not kept"
                + " there; Export > GDML keeps a copy with them");
        }
        try {
            final List<String> notes = model.save(p, f);
            remember(p);
            console.ok("saved " + p + " (" + f.label + ")");
            notes.forEach(n -> console.print("  " + n));
            return true;
        } catch (Exception e) {
            console.error("cannot save: " + e.getMessage());
            bottom.setSelectedIndex(0);
            return false;
        }
    }

    /** The path with the format's extension, unless it already has one that fits. */
    private static Path withExtension(Path p, GeometryFiles.Format f) {
        final String n = p.getFileName().toString();
        final String lower = n.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("." + f.extension) || (f != GeometryFiles.Format.ROOT && lower.endsWith(".xml"))) {
            return p;
        }
        final int dot = n.lastIndexOf('.');
        final String stem = dot > 0 && (lower.endsWith(".gdml") || lower.endsWith(".root")) ? n.substring(0, dot) : n;
        return p.resolveSibling(stem + "." + f.extension);
    }

    private boolean confirmOverwrite(Path p) {
        return !Files.exists(p) || JOptionPane.showConfirmDialog(this, p.getFileName() + " exists. Replace it?", "G4editor",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private File chooseFile(boolean save, String ext) {
        final JFileChooser ch = chooser();
        ch.setFileFilter(new FileNameExtensionFilter(ext.toUpperCase() + " files", ext));
        final int r = save ? ch.showSaveDialog(this) : ch.showOpenDialog(this);
        return r == JFileChooser.APPROVE_OPTION ? ch.getSelectedFile() : null;
    }

    /** Export: the design written in a format, its own file unchanged. */
    private void exportDialog(GeometryFiles.Format f) {
        final JFileChooser ch = chooser();
        ch.setDialogTitle("Export as " + f.label);
        ch.setAcceptAllFileFilterUsed(false);
        final FileFilter filter = switch (f) {
            case ROOT -> ROOT_FILES;
            case GDML_PORTABLE -> PORTABLE_FILES;
            default -> GDML_FILES;
        };
        ch.setFileFilter(filter);
        final String stem = model.file() == null ? model.geometry.title()
            : model.file().getFileName().toString().replaceFirst("\\.[^.]*$", "");
        ch.setSelectedFile(new File(stem + (f == GeometryFiles.Format.GDML_PORTABLE ? "_portable" : "") + "." + f.extension));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final Path p = withExtension(ch.getSelectedFile().toPath(), f);
        if (confirmOverwrite(p)) {
            export(p, f);
        }
    }

    private void export(Path p, GeometryFiles.Format f) {
        try {
            final List<String> notes = GeometryFiles.write(model.geometry, p, f);
            console.ok(f.label + " written: " + p + switch (f) {
                case ROOT -> "  (TGeoManager::Import reads it)";
                case GDML_PORTABLE -> "  (ROOT's TGeoManager::Import and Geant4's G4GDMLParser read it)";
                default -> "  (G4GDMLParser reads it as is)";
            });
            notes.forEach(n -> console.print("  " + n));
            model.log("export " + (f == GeometryFiles.Format.ROOT ? "root " : "gdml ") + p
                + (f == GeometryFiles.Format.GDML_PORTABLE ? " portable" : ""));
        } catch (Exception e) {
            console.error("cannot write " + p + ": " + e.getMessage());
            bottom.setSelectedIndex(0);
        }
    }

    private void exportCppDialog() {
        final JFileChooser ch = chooser();
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setDialogTitle("Folder of the Geant4 application");
        if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            exportCpp(ch.getSelectedFile().toPath());
        }
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /** File > Convert files: any number of GDML or ROOT files into another format, next to them or in a folder. */
    private void convertDialog() {
        final JFileChooser ch = chooser();
        ch.setDialogTitle("Files to convert (GDML or ROOT geometries)");
        ch.setMultiSelectionEnabled(true);
        ch.addChoosableFileFilter(GEOMETRY_FILES);
        ch.setFileFilter(GEOMETRY_FILES);
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || ch.getSelectedFiles().length == 0) {
            return;
        }
        final List<Path> in = new ArrayList<>();
        for (File f : ch.getSelectedFiles()) {
            in.add(f.toPath());
        }
        final GeometryFiles.Format[] formats = GeometryFiles.Format.values();
        final String[] labels = java.util.Arrays.stream(formats).map(f -> f.label).toArray(String[]::new);
        // the format they are not in is the likely target
        final boolean allRoot = in.stream().allMatch(p -> GeometryFiles.byName(p) == GeometryFiles.Format.ROOT);
        final Object pick = JOptionPane.showInputDialog(this, "Convert " + in.size() + " file" + (in.size() > 1 ? "s" : "")
            + " to:", "G4editor - convert", JOptionPane.QUESTION_MESSAGE, null, labels,
            allRoot ? labels[GeometryFiles.Format.GDML.ordinal()] : labels[GeometryFiles.Format.ROOT.ordinal()]);
        if (pick == null) {
            return;
        }
        final GeometryFiles.Format f = formats[java.util.Arrays.asList(labels).indexOf(pick)];
        convert(in, null, f);
    }

    /**
     * Converts files in the background, each written next to its source with
     * the target's extension (or to out, for a single file), and says what
     * each conversion had to approximate.
     */
    private void convert(List<Path> in, Path out, GeometryFiles.Format f) {
        showBottom(console);
        console.print("converting " + in.size() + " file" + (in.size() > 1 ? "s" : "") + " to " + f.label + "...");
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                for (Path p : in) {
                    Path target = out != null && in.size() == 1 ? out : withExtension(p, f);
                    if (target.toAbsolutePath().normalize().equals(p.toAbsolutePath().normalize())) {
                        target = p.resolveSibling(p.getFileName().toString().replaceFirst("\\.[^.]*$", "")
                            + (f == GeometryFiles.Format.GDML_PORTABLE ? "_portable." : "_converted.") + f.extension);
                    }
                    try {
                        final long t0 = System.nanoTime();
                        final List<String> notes = GeometryFiles.convert(p, target, f);
                        publish(String.format(java.util.Locale.ROOT, "OK  %s -> %s (%.2f s)", p.getFileName(), target,
                            (System.nanoTime() - t0) / 1e9));
                        for (String n : notes) {
                            publish("      " + n);
                        }
                    } catch (Exception e) {
                        publish("ERR " + p.getFileName() + ": " + e.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> lines) {
                for (String l : lines) {
                    if (l.startsWith("OK")) {
                        console.ok(l.substring(4));
                    } else if (l.startsWith("ERR")) {
                        console.error(l.substring(4));
                    } else {
                        console.print(l);
                    }
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // Materials
    // ------------------------------------------------------------------

    /** The defined materials that are NIST ones; with apply, the volumes get the NIST material (one undo step). */
    private void nistMatch(boolean apply) {
        final List<MaterialMatcher.Match> matches = MaterialMatcher.matches(model.geometry);
        showBottom(console);
        if (matches.isEmpty()) {
            console.print("no defined material is a NIST one under another name");
            return;
        }
        console.print(matches.size() + " defined material" + (matches.size() > 1 ? "s are NIST ones:" : " is a NIST one:"));
        matches.forEach(m -> console.print("  " + m.describe()));
        if (!apply) {
            console.print("  'nist apply' gives the volumes the NIST materials (undo restores them)");
            return;
        }
        final StringBuilder list = new StringBuilder();
        matches.forEach(m -> list.append("\n  ").append(m.material()).append("  ->  ").append(m.nist()));
        if (JOptionPane.showConfirmDialog(this, "Use the NIST materials Geant4 knows, with their exact ionization"
            + " potentials?" + list, "G4editor - NIST materials", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        final int[] n = new int[1];
        model.edit("use NIST materials", () -> n[0] = MaterialMatcher.apply(model.geometry, matches));
        console.ok(n[0] + " volume" + (n[0] == 1 ? "" : "s") + " now made of NIST materials");
        model.log("nist apply");
    }

    // ------------------------------------------------------------------
    // Recent files and dropped files
    // ------------------------------------------------------------------

    private static List<Path> recentFiles() {
        final List<Path> out = new ArrayList<>();
        for (String s : PREFS.get("recent", "").split("\n")) {
            if (!s.isBlank()) {
                try {
                    final Path p = Path.of(s);
                    if (Files.isRegularFile(p) && !out.contains(p)) {
                        out.add(p);
                    }
                } catch (RuntimeException badPath) {
                    // dropped from the list
                }
            }
        }
        return out;
    }

    private void remember(Path p) {
        final List<Path> list = recentFiles();
        final Path abs = p.toAbsolutePath().normalize();
        list.remove(abs);
        list.add(0, abs);
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_RECENT, list.size()); i++) {
            b.append(list.get(i)).append('\n');
        }
        PREFS.put("recent", b.toString());
        rebuildRecent();
    }

    private void rebuildRecent() {
        if (recentMenu == null) {
            return;
        }
        recentMenu.removeAll();
        final List<Path> list = recentFiles();
        if (list.isEmpty()) {
            final JMenuItem none = new JMenuItem("(no file opened yet)");
            none.setEnabled(false);
            recentMenu.add(none);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final Path p = list.get(i);
            final String kind = GeometryFiles.byName(p) == GeometryFiles.Format.ROOT ? "ROOT" : "GDML";
            final JMenuItem it = item((i + 1) + "   " + p.getFileName() + "   [" + kind + "]", null, () -> {
                if (confirmDiscard()) {
                    open(p, null);
                }
            }, p.toString());
            if (i < 9) {
                it.setMnemonic('1' + i);
            }
            recentMenu.add(it);
        }
        recentMenu.addSeparator();
        recentMenu.add(item("Clear the list", null, () -> {
            PREFS.remove("recent");
            rebuildRecent();
        }));
    }

    /** Files dropped on the window: one is opened; several are offered for conversion. */
    private void installDrop(JComponent... targets) {
        final TransferHandler h = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    final List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    final List<Path> geos = new ArrayList<>();
                    for (File f : files) {
                        if (f.isFile() && GeometryFiles.isGeometryFile(f.toPath())) {
                            geos.add(f.toPath());
                        }
                    }
                    if (geos.isEmpty()) {
                        status.setText("Drop GDML or ROOT geometry files here");
                        return false;
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (geos.size() == 1) {
                            if (confirmDiscard()) {
                                open(geos.get(0), null);
                            }
                            return;
                        }
                        final String[] options = {"Convert them to ROOT", "Convert them to GDML", "Open the first", "Cancel"};
                        final int r = JOptionPane.showOptionDialog(G4EditorFrame.this, geos.size() + " geometry files dropped.",
                            "G4editor", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                        if (r == 0) {
                            convert(geos, null, GeometryFiles.Format.ROOT);
                        } else if (r == 1) {
                            convert(geos, null, GeometryFiles.Format.GDML);
                        } else if (r == 2 && confirmDiscard()) {
                            open(geos.get(0), null);
                        }
                    });
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
        for (JComponent c : targets) {
            c.setTransferHandler(h);
        }
    }

    private void exportCpp(Path dir) {
        try {
            final CppExport ex = new CppExport(model.geometry, CppExport.Options.defaults(model.geometry));
            final var files = ex.write(dir);
            console.ok("Geant4 application written to " + dir + " (" + files.size() + " files): build it with "
                + "cmake -DGeant4_DIR=... and run ./" + CppExport.Options.defaults(model.geometry).appName());
            model.log("export cpp " + dir);
        } catch (Exception e) {
            console.error("cannot export: " + e.getMessage());
        }
    }

    private void exportImage() {
        final File f = chooseFile(true, "png");
        if (f == null) {
            return;
        }
        try {
            final java.awt.image.BufferedImage img = vp.snapshot(Math.max(800, vp.getWidth()), Math.max(600, vp.getHeight()));
            final File out = f.getName().endsWith(".png") ? f : new File(f.getPath() + ".png");
            ImageIO.write(img, "png", out);
            console.ok("image written: " + out);
        } catch (Exception e) {
            console.error("cannot write the image: " + e.getMessage());
        }
    }

    /** Warms up the NIST tables off the event thread, so the first material lookup does not stall the window. */
    static {
        final Thread t = new Thread(NistDB::get, "g4editor-nist");
        t.setDaemon(true);
        t.start();
    }

    /** Standalone launch, for development: java ... G4EditorFrame [design.gdml] */
    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.US);
        javax.swing.JComponent.setDefaultLocale(java.util.Locale.US);
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look
            }
            final G4EditorFrame f = new G4EditorFrame(args.length > 0 ? Path.of(args[0]) : null, k -> null);
            f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    System.exit(0);
                }
            });
            f.setVisible(true);
        });
    }

    // For headless checks
    Viewport viewport() {
        return vp;
    }

    EditorModel model() {
        return model;
    }

    Script script() {
        return script;
    }

    static boolean unused(FlowLayout f) {
        return f == null;
    }
}
