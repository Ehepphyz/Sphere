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
import com.sphere.core.geant4.Relations;
import com.sphere.core.geant4.Script;
import com.sphere.core.geant4.TGeoReader;
import com.sphere.core.geant4.Templates;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
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
    /** Physics Live: the beam's physics on the drawing, and its panel of the beam, the layers and goals. */
    private final SphereLive live;
    private final Script script;
    private final JLabel status = new JLabel(" ");
    private final JLabel hover = new JLabel(" ");
    private final JTabbedPane bottom = new JTabbedPane();
    private final List<AbstractButton> toolButtons = new ArrayList<>();
    /** The tool buttons by the name the keys, the menus and the console's "tool" command use. */
    private final java.util.Map<String, AbstractButton> toolNamed = new java.util.LinkedHashMap<>();
    private final JComboBox<String> materialBox = new JComboBox<>();
    private final Function<String, String> settings;
    private JSplitPane centerSplit;
    /** The drawing's share of the height is saved once the one of the last session is back, not before. */
    private boolean splitRestored;
    private JMenu recentMenu;
    private JMenuItem undoItem;
    private JMenuItem redoItem;
    /**
     * The classic menus: no longer shown (Creo's ribbon and File tab are), still the command search's index,
     * the File tab's menu and where the keyboard shortcuts come from.
     */
    private JMenuBar menuBar;
    private JMenu fileMenu, helpMenu;
    private SphereCreo.Ribbon ribbon;
    private SphereCreo.Navigator navigator;
    private SphereCreo.Dashboard dashboard;
    private java.awt.Component mlPanel;
    /** The tool at work, by its button's name ("select" when none): what the ribbon shows lit. */
    private String activeTool = "select";
    private final JComboBox<String> unitBox = new JComboBox<>(new String[] {"mm", "cm", "m", "um"});
    private final JComboBox<String> planeBox = new JComboBox<>(new String[] {"XY", "XZ", "YZ"});
    private final JTextField offsetField = new JTextField("0", 5);
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
        vp.creoMouse = PREFS.getBoolean("creoMouse", true);
        script = new Script(model.geometry, new Host());
        tree = new ModelTree(model);
        props = new PropertySheet(model, script);
        analysis = new AnalysisPanel(model, vp, console::run);
        clashes = new ClashWatch(model, vp, console::print);
        runPanel = new RunPanel(model, vp, this.settings, s -> SwingUtilities.invokeLater(() -> console.print(s)));
        live = new SphereLive(model, vp);
        vp.live = live;
        live.gunChanged = runPanel::restoreGun;
        // each run in Geant4 is set against Physics Live's prediction for the same beam and design
        runPanel.onResult = r -> {
            final com.sphere.core.geantbackend.RunComparison.Result c = live.runFinished(r, runPanel.runDesign());
            if (c != null) {
                for (String l : c.lines()) {
                    console.print(l);
                }
            }
        };
        live.checkInGeant4 = () -> {
            runPanel.runNow();
            status.setText("Geant4 runs Physics Live's beam; its answer is set against the prediction when it comes");
        };
        console.setScript(script);
        model.addLog(console::logged);

        // Left: Creo's navigator (model tree, materials, variables) above the property view
        final LibraryPanels.Materials materials = new LibraryPanels.Materials(model, console::setInput);
        navigator = new SphereCreo.Navigator()
            .page("Model Tree", Icons.tree(16), tree)
            .page("Materials", Icons.flask(16), materials)
            .page("Variables", Icons.variables(16), new LibraryPanels.Variables(model));
        navigator.show(0);
        final JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, navigator, props);
        left.setResizeWeight(0.45);
        left.setDividerLocation(360);
        // A split pane's divider stops at its panes' minimum sizes, which forms full of fields make large: the panes
        // around the drawing may be made as small as one likes, so the drawing can be given the whole window
        navigator.setMinimumSize(new java.awt.Dimension(0, 0));
        props.setMinimumSize(new java.awt.Dimension(0, 0));
        left.setMinimumSize(new java.awt.Dimension(0, 0));

        bottom.addTab("Console", Icons.console(14), console);
        bottom.addTab("Analyses", Icons.check(14), analysis);
        bottom.addTab("Physics Live", Icons.shower(14), live.panel);
        live.showPanel = () -> showBottom(live.panel);
        bottom.addTab("Run in Geant4", Icons.run(14), runPanel);
        mlPanel = new FastSimPanel(model, this.settings, s -> SwingUtilities.invokeLater(() -> console.print(s)));
        bottom.addTab("Machine learning", Icons.network(14), mlPanel);
        final JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, vp, bottom);
        center.setResizeWeight(0.7);
        // The console, the Run panel and the others below can be shrunk to their tabs, or folded away with the
        // divider's arrows, to give the drawing the room; the height chosen is kept for the next session
        bottom.setMinimumSize(new java.awt.Dimension(0, 0));
        vp.setMinimumSize(new java.awt.Dimension(120, 80));
        center.setOneTouchExpandable(true);
        center.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            final int span = center.getHeight() - center.getDividerSize();
            if (splitRestored && span > 100 && center.isShowing()) {
                PREFS.putDouble("drawingShare", Math.max(0.05, Math.min(1.0, center.getDividerLocation() / (double) span)));
            }
        });
        centerSplit = center;
        final JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, center);
        main.setDividerLocation(360);
        main.setOneTouchExpandable(true);

        // Creo's frame: the ribbon above, the message area below
        final JPanel statusBar = new SphereCreo.StatusBar(status, hover);
        ribbon = ribbon();
        dashboard = new SphereCreo.Dashboard(vp, G4EditorFrame::toolIcon);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(ribbon, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        getContentPane().setBackground(SphereCreo.palette().window());
        menuBar = menus();
        installAccelerators(menuBar);
        // The tool at work gets its tab in the ribbon (Creo's dashboard), lit buttons follow it
        vp.setToolListener(t -> {
            activeTool = toolName(t);
            if (t == null) {
                ribbon.hideContext();
            } else {
                ribbon.showContext(t.name(), dashboard);
            }
            ribbon.repaint();
        });
        // the tree keeps its own drag and drop (reparenting volumes)
        installDrop(getRootPane(), vp);

        live.setOn(PREFS.getBoolean("physicsLive", true));
        vp.physicsToggle = this::toggleLive;
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
            console.print("New design: a 2 m air world. Draw with the tools above (Q sketch freehand, B box, T tube,"
                + " S sphere, C cone, P extrude, V revolve; E pushes and pulls faces), type values while drawing, or"
                + " describe a detector in the console. File > Open reads GDML and ROOT geometries (TGeoManager);"
                + " Ctrl+Shift+P finds any command.");
        }
        updateTitle();
        vp.updateStatus();
        SwingUtilities.invokeLater(() -> {
            centerSplit.setDividerLocation(PREFS.getDouble("drawingShare", 0.68));
            splitRestored = true;
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
                case "colorby", "color-by" -> {
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
                case "templates", "gallery" -> SwingUtilities.invokeLater(G4EditorFrame.this::openGallery);
                case "live" -> {
                    setLive(a.isEmpty() ? !live.isOn() : !a.get(0).equals("off"));
                    console.print("Physics Live " + (live.isOn() ? "on: the beam's physics is on the drawing, worked out"
                        + " again at every edit ('beam' sets the gun, 'goal' seeks a target)" : "off"));
                }
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

    // ------------------------------------------------------------------
    // The ribbon (PTC Creo's): File, Model, Analysis, Geant4, View
    // ------------------------------------------------------------------

    private SphereCreo.Ribbon ribbon() {
        final SphereCreo.Ribbon r = new SphereCreo.Ribbon(() -> model.title() + "  (Active)  -  G4editor");
        r.onFile((c, box) -> fileMenu.getPopupMenu().show(c, box.x, box.y + box.height));
        final SphereCreo.Size S = SphereCreo.Size.SMALL, L = SphereCreo.Size.LARGE, I = SphereCreo.Size.ICON;

        // Quick access, top left, as Creo's title bar has it
        r.quick(new SphereCreo.Button(I, Icons.file(16, "new"), "New", "New design (Ctrl+N)", this::newDesign));
        r.quick(new SphereCreo.Button(I, Icons.file(16, "open"), "Open", "Open a GDML design or a ROOT geometry (Ctrl+O)",
            this::openDialog));
        r.quick(new SphereCreo.Button(I, Icons.file(16, "save"), "Save", "Save (Ctrl+S), in the format of the file", () -> save(null)));
        r.quick(new SphereCreo.Button(I, Icons.undo(16, false), "Undo", "Undo (Ctrl+Z)", model::undo));
        r.quick(new SphereCreo.Button(I, Icons.undo(16, true), "Redo", "Redo (Ctrl+Y)", model::redo));
        r.quick(new SphereCreo.Button(I, Icons.zoom(16, 0), "Find", "Find any command (Ctrl+Shift+P)",
            () -> ActionPalette.show(this, menuBar)));
        r.quick(new SphereCreo.Button(I, Icons.shower(16), "Physics Live", "Physics Live over the drawing, on or off (L)",
            this::toggleLive).on(live::isOn));
        final SphereCreo.Button[] help = new SphereCreo.Button[1];
        help[0] = new SphereCreo.Button(I, Icons.help(16), "Help", "Help: console commands, mouse and keyboard, files",
            () -> helpMenu.getPopupMenu().show(help[0], 0, help[0].getHeight()));
        r.trailing(help[0]);

        setupControls();
        final Color c = new Color(0x8AA4C8);

        // Model: what is drawn and how
        final JPanel m = r.tab("Model");
        m.add(new SphereCreo.Group("Operations")
            .add(toolButton("select", L, Icons.select(32), "Select", "Select (Esc)", () -> null))
            .add(large(Icons.regenerate(32), "Regenerate", "Evaluates every expression and placement again (Ctrl+G)",
                this::regenerate))
            .stack(small(Icons.copy(16), "Copy", "The selected placements (Ctrl+C)", this::copySelection),
                small(Icons.paste(16), "Paste", "Copies of them, to drag into place (Ctrl+V)", this::paste),
                small(Icons.trash(16), "Delete", "Delete the selection (Delete)", this::deleteSelection))
            .stack(small(Icons.selectAll(16), "Select all", "Every placement (Ctrl+A)",
                    () -> model.selectAll(model.geometry.placements())),
                small(Icons.copy(16), "Duplicate", "A copy of the selection in place",
                    () -> forSelection(pv -> "copy " + pv.name() + " 1"))));
        m.add(new SphereCreo.Group("Get Data")
            .add(large(Icons.template(32), "Templates", "The template gallery: 14 parametric detectors, each drawn in 3D"
                + " with the beam it is made for; search them in words, set their parameters, see their physics, seek a goal",
                this::openGallery))
            .add(menuButton(L, Icons.volume(32, "polycone", c), "Any solid", "Any Geant4 solid, made at the origin",
                this::solidsPopup)));
        m.add(new SphereCreo.Group("Datum")
            .add(toolButton("sketch", L, Icons.sketch(32), "Sketch", "Sketch (Q): draw a shape freehand, a circle, a"
                + " rectangle, a hexagon, any outline, a line; it is read and made a volume (Space: another reading)",
                () -> new SmartTools.SketchTool(vp)))
            .stack(SphereCreo.labeled("Plane", planeBox, 100), SphereCreo.labeled("Offset", offsetField, 100),
                toggle(Icons.plane(16), "On faces", "Draw on the face clicked first", () -> vp.faceSnapPlane,
                    () -> vp.faceSnapPlane = !vp.faceSnapPlane)));
        m.add(new SphereCreo.Group("Shapes")
            .add(toolButton("extrude", L, Icons.extrude(32, c), "Extrude", "Extrusion, G4ExtrudedSolid (P): outline,"
                + " thickness", () -> new Tools.PolygonTool(vp, false)))
            .add(toolButton("revolve", L, Icons.revolve(32, c), "Revolve", "Revolution, G4GenericPolycone (V): (r, z)"
                + " profile", () -> new Tools.PolygonTool(vp, true)))
            .stack(toolButton("box", S, Icons.box(16, c), "Box", "Box, G4Box (B): corner, opposite corner (or one drag),"
                    + " height", () -> new Tools.BoxTool(vp)),
                toolButton("tube", S, Icons.tube(16, c), "Tube", "Tube, G4Tubs (T): center, radius (or one drag), length",
                    () -> new Tools.TubeTool(vp)),
                toolButton("sphere", S, Icons.sphere(16, c), "Sphere", "Sphere, G4Orb / G4Sphere (S): center, radius",
                    () -> new Tools.SphereTool(vp)))
            .stack(toolButton("cone", S, Icons.cone(16, c), "Cone", "Cone, G4Cons (C): center, radius, height, top radius",
                    () -> new Tools.ConeTool(vp)),
                toolButton("torus", S, Icons.torus(16, c), "Torus", "Torus, G4Torus: center, ring radius, tube radius",
                    () -> new Tools.TorusTool(vp))));
        m.add(new SphereCreo.Group("Editing")
            .add(toolButton("pushpull", L, Icons.pushPull(32), "Push/Pull", "Push/Pull (E): drag a face of a volume out or"
                + " in; its length or radius follows, the opposite face stays", () -> new SmartTools.PushPullTool(vp)))
            .add(toolButton("move", L, Icons.move(32), "Move", "Move (M): drag the selected volume (it lands flush against"
                + " its neighbors; Ctrl drags a copy, then type 5 for a row of five), drag the gizmo, or type a"
                + " displacement", () -> new Tools.MoveTool(vp, false)))
            .stack(toolButton("rotate", S, Icons.rotate(16), "Rotate", "Rotate (R): drag a ring or type angles",
                    () -> new Tools.MoveTool(vp, true)),
                small(Icons.eye(16, true), "Hide / Show", "Hide or show the selection (H)", this::toggleVisibility))
            .stack(small(Icons.bool(16, 0), "Union", "Union of the two selected volumes",
                    () -> booleanOf(BooleanSolid.Op.UNION)),
                small(Icons.bool(16, 1), "Subtract", "Subtract the second selected volume from the first",
                    () -> booleanOf(BooleanSolid.Op.SUBTRACTION)),
                small(Icons.bool(16, 2), "Intersect", "Intersection of the two selected volumes",
                    () -> booleanOf(BooleanSolid.Op.INTERSECTION))));
        m.add(new SphereCreo.Group("Model Intent")
            .stack(SphereCreo.labeled("Material", materialBox, 170), SphereCreo.labeled("Unit", unitBox, 100),
                small(Icons.flask(16), "NIST equivalents", "Use NIST equivalents of the defined materials", () -> nistMatch(true)))
            .stack(small(Icons.tree(16), "Model Tree", "The volumes, in the navigator", () -> navigator.show("Model Tree")),
                small(Icons.flask(16), "Materials", "The materials, in the navigator", () -> navigator.show("Materials")),
                small(Icons.variables(16), "Variables", "The design's variables, in the navigator",
                    () -> navigator.show("Variables")))
            .stack(small(Icons.relations(16), "Relations", "The stacks drawn by hand made parametric: their thicknesses"
                    + " become variables, their positions and envelopes follow them (nothing moves)", this::relationsDialog),
                small(Icons.goal(16), "Goal Seek", "The value of a variable that meets a physics target (95% kept, 25 X0,"
                    + " a mass...), tried on a copy of the design, applied as one step", this::goalSeek)));

        // Analysis
        final JPanel a = r.tab("Analysis");
        a.add(new SphereCreo.Group("Measure")
            .add(toolButton("measure", L, Icons.measure(32), "Measure", "Measure distance and radiation lengths crossed (D)",
                () -> new Tools.MeasureTool(vp))));
        a.add(new SphereCreo.Group("Physics Live")
            .add(new SphereCreo.Button(L, Icons.shower(32), "Physics Live", "What the beam does, on the drawing: its shower,"
                + " the energy kept, where it leaks, the sampling; worked out again at every edit (L)", this::toggleLive)
                .on(live::isOn))
            .add(menuButton(L, Icons.beam(32), "Beam", "The design's gun, the one the Run panel fires and the C++ uses: its"
                + " particle, its energy, where it goes", this::beamPopup))
            .add(large(Icons.goal(32), "Goal Seek", "The value of a variable that meets a physics target: 95% kept, 25 X0,"
                + " a mass... Tried on a copy of the design, applied as one step", this::goalSeek)));
        a.add(new SphereCreo.Group("Inspect Geometry")
            .add(large(Icons.check(32), "Check overlaps", "Check the whole design for overlaps (F7)", () -> {
                showBottom(analysis);
                analysis.checkOverlaps(1000);
            }))
            .add(large(Icons.clash(32), "Resolve clash", "Move the selected volume out of what it overlaps (F6)",
                this::resolveClash))
            .stack(toggle(Icons.clash(16), "Live clash check", "Check the selection as it is edited",
                    clashes::enabled, () -> clashes.setEnabled(!clashes.enabled())),
                toggle(Icons.check(16), "Check while drawing", "Previews say where they go and what they would overlap",
                    () -> vp.liveCheck, () -> vp.liveCheck = !vp.liveCheck)));
        a.add(new SphereCreo.Group("Model Report")
            .add(large(Icons.mass(32), "Mass", "Masses of the volumes", () -> {
                showBottom(analysis);
                analysis.computeMasses();
            }))
            .add(large(Icons.budget(32), "Material budget", "Material budget scan (F8)", () -> {
                showBottom(analysis);
                analysis.scanBudget(true, 4, 80, false);
            }))
            .add(large(Icons.advise(32), "Design assistant", "Design assistant (F9)", () -> {
                showBottom(analysis);
                analysis.showAssistant();
            })));

        // Applications (Creo's name for the tab of the programs around the model): Geant4's runs, G4code, ROOT and GDML
        final JPanel g = r.tab("Applications");
        g.add(new SphereCreo.Group("Simulation")
            .add(large(Icons.run(32), "Run in Geant4", "Run the design in Geant4 (F5)", () -> showBottom(runPanel)))
            .stack(small(Icons.geant4(16), "Installations", "The Geant4 installations found", this::showInstallations),
                small(Icons.network(16), "Machine learning", "Fast simulation by machine learning",
                    () -> showBottom(mlPanel))));
        g.add(new SphereCreo.Group("Code")
            .add(large(Icons.code(32), "Into G4code", "The selection (else the whole detector) written into the C++ open"
                + " in G4code, in the volume selected there (Ctrl+Shift+I)", this::insertIntoG4code))
            .add(large(Icons.export(32, Icons.BLUE), "C++ project", "A Geant4 C++ project (Ctrl+E)", this::exportCppDialog)));
        g.add(new SphereCreo.Group("Exchange")
            .add(large(Icons.root(32), "ROOT geometry", "A .root file TGeoManager::Import reads, written without ROOT"
                + " (Ctrl+Shift+R)", () -> exportDialog(GeometryFiles.Format.ROOT)))
            .stack(small(Icons.export(16, Icons.GREEN), "GDML for Geant4", "The design as it is, expressions and variables"
                    + " kept", () -> exportDialog(GeometryFiles.Format.GDML)),
                small(Icons.export(16, Icons.ORANGE), "GDML for ROOT", "Numbers instead of expressions: ROOT reads it too",
                    () -> exportDialog(GeometryFiles.Format.GDML_PORTABLE)),
                small(Icons.convert(16), "Convert files", "GDML to ROOT or ROOT to GDML, many files at once",
                    this::convertDialog))
            .stack(small(Icons.file(16, "open"), "Open", "Open a GDML design or a ROOT geometry (Ctrl+O)", this::openDialog),
                small(Icons.image(16), "Image (PNG)", "The view as a picture", this::exportImage)));
        g.add(new SphereCreo.Group("Materials")
            .stack(small(Icons.flask(16), "NIST equivalents", "Use NIST equivalents of the defined materials",
                    () -> nistMatch(true)),
                small(Icons.list(16), "List materials", "The materials, in the console", () -> {
                    bottom.setSelectedIndex(0);
                    console.run("materials");
                })));

        // View
        final JPanel v = r.tab("View");
        v.add(new SphereCreo.Group("Orientation")
            .add(large(Icons.fit(32), "Refit", "Fit all (F)", () -> vp.fitAll(true)))
            .add(menuButton(L, Icons.view(32, "iso"), "Saved orientations", "Standard, FRONT, TOP, RIGHT... (0-8)",
                vp::viewsMenu))
            .stack(small(Icons.view(16, "front"), "Front", "Front view (1)", () -> vp.setView("front", true)),
                small(Icons.view(16, "top"), "Top", "Top view (2)", () -> vp.setView("top", true)),
                small(Icons.view(16, "right"), "Right", "Right view (3)", () -> vp.setView("right", true)))
            .stack(small(Icons.view(16, "iso"), "Standard", "Standard orientation (0)", () -> vp.setView("iso", true)),
                small(Icons.fit(16), "Fit selection", "Frame the selection", () -> vp.fitSelection(true))));
        v.add(new SphereCreo.Group("Model Display")
            .add(menuButton(L, Icons.box(32, c), "Display style", "Shading with edges, shading, wireframe", vp::styleMenu))
            .add(menuButton(L, Icons.palette(32), "Color by", "Document, material, density, X0, depth, sensitive (K)",
                vp::colorMenu))
            .stack(toggle(Icons.perspective(16), "Perspective", "Perspective (O)", () -> vp.camera.perspective,
                    vp::togglePerspective),
                toggle(Icons.isolate(16), "See-through envelopes", "Envelopes as glass, so what they hold shows",
                    () -> vp.autoTransparent, () -> vp.autoTransparent = !vp.autoTransparent),
                toggle(Icons.palette(16), "Dark background", "The graphics area's dark gradient", () -> vp.darkTheme,
                    () -> vp.darkTheme = !vp.darkTheme)));
        v.add(new SphereCreo.Group("Show")
            .stack(toggle(Icons.plane(16), "Datum planes", "FRONT, TOP, RIGHT and the world's coordinate system",
                    () -> vp.showDatums, () -> vp.showDatums = !vp.showDatums),
                toggle(Icons.spin(16), "Spin center", "Where the view turns about", () -> vp.showSpin,
                    () -> vp.showSpin = !vp.showSpin),
                toggle(Icons.grid(16), "Grid", "The work plane's grid", () -> vp.showGrid, () -> vp.showGrid = !vp.showGrid))
            .stack(toggle(Icons.isolate(16), "Isolate", "Only the selection (I)", vp::isIsolated, () -> {
                    if (!vp.isolate(!vp.isIsolated())) {
                        status.setText("Select volumes first: Isolate then shows only them, the rest as outlines");
                    }
                }),
                toggle(Icons.isolate(16), "X-ray", "Isolated: the rest as outlines", vp::xray, () -> vp.setXray(!vp.xray())),
                toggle(Icons.section(16), "Section", "Cut the detector open along a plane (X)", () -> vp.section,
                    () -> vp.section = !vp.section))
            .stack(menuButton(S, Icons.section(16), "Section plane", "Normal to X, Y or Z", this::sectionPopup)));
        v.add(new SphereCreo.Group("Drawing Aids")
            .stack(toggle(Icons.grid(16), "Snap to grid", "Snap to the grid (G)", () -> vp.gridSnap,
                    () -> vp.gridSnap = !vp.gridSnap),
                toggle(Icons.select(16), "Snap to points", "Corners, middles, centers and edges (Ctrl held: off)",
                    () -> vp.vertexSnap, () -> vp.vertexSnap = !vp.vertexSnap),
                toggle(Icons.view(16, "top"), "Z up", "Z up (FreeCAD) instead of Y up (Geant4)", () -> !vp.yUp,
                    () -> vp.setUpAxis(!vp.yUp))));
        v.add(new SphereCreo.Group("Navigation")
            .stack(toggle(Icons.spin(16), "Creo mouse", "Middle drag spins, Shift+middle pans, Ctrl+middle zooms and"
                    + " turns, a left drag selects with a box, a double click shows the dimensions (off: FreeCAD's mouse)",
                    () -> vp.creoMouse, () -> {
                        vp.creoMouse = !vp.creoMouse;
                        PREFS.putBoolean("creoMouse", vp.creoMouse);
                        vp.updateStatus();
                    }),
                small(Icons.view(16, "iso"), "Standard orientation", "Ctrl+D", () -> vp.setView("iso", true)),
                small(Icons.fit(16), "Repaint", "Ctrl+R", vp::repaint)));
        v.add(new SphereCreo.Group("Window")
            .add(large(Icons.zoom(32, 0), "Find a command", "Every command, found by typing a few letters (Ctrl+Shift+P)",
                () -> ActionPalette.show(this, menuBar)))
            .stack(small(Icons.console(16), "Console", "The console (Space)", () -> {
                    bottom.setSelectedIndex(0);
                    console.focusInput();
                }),
                small(Icons.pane(16), "Analyses", "The analyses", () -> showBottom(analysis)),
                small(Icons.run(16), "Run panel", "Run in Geant4", () -> showBottom(runPanel))));
        r.select("Model");
        return r;
    }

    /** The material, unit and plane controls, as they were: the ribbon only holds them. */
    private void setupControls() {
        materialBox.setEditable(true);
        for (String mat : List.of("G4_Al", "G4_Si", "G4_Pb", "G4_Fe", "G4_Cu", "G4_W", "G4_AIR", "G4_Galactic", "G4_WATER",
            "G4_PLASTIC_SC_VINYLTOLUENE", "G4_PbWO4", "G4_lAr", "G4_CONCRETE", "G4_STAINLESS-STEEL", "G4_KAPTON")) {
            materialBox.addItem(mat);
        }
        materialBox.setSelectedItem(model.material);
        materialBox.setToolTipText("Material of the volumes you draw next (any NIST name, or a common name: lead, water, scintillator)");
        materialBox.addActionListener(e -> {
            final String val = String.valueOf(materialBox.getSelectedItem()).trim();
            final String mat = model.geometry.materials().material(val) != null ? val : com.sphere.core.geant4.Intent.material(val);
            if (mat != null) {
                model.material = mat;
                if (!mat.equals(val)) {
                    materialBox.setSelectedItem(mat);
                }
            } else {
                status.setText("unknown material " + val);
            }
        });
        unitBox.setSelectedItem(model.lengthUnit);
        unitBox.setToolTipText("Unit of lengths typed without one");
        unitBox.addActionListener(e -> model.lengthUnit = String.valueOf(unitBox.getSelectedItem()));
        planeBox.setToolTipText("The work plane");
        offsetField.setToolTipText("Offset of the work plane along its normal (Enter)");
        final Runnable applyPlane = () -> {
            double o = 0;
            try {
                o = com.sphere.core.geant4.Expr.eval(com.sphere.core.geant4.Inputs.normalize(offsetField.getText(),
                    com.sphere.core.geant4.Units.Category.LENGTH, model.lengthUnit), model.geometry.scope());
            } catch (GeometryException ex) {
                status.setText(ex.getMessage());
            }
            vp.plane = switch (String.valueOf(planeBox.getSelectedItem())) {
                case "XZ" -> WorkPlane.xz(o);
                case "YZ" -> WorkPlane.yz(o);
                default -> WorkPlane.xy(o);
            };
            if (vp.tool != null) {
                vp.tool.fields();
            }
            vp.repaint();
        };
        planeBox.addActionListener(e -> applyPlane.run());
        offsetField.addActionListener(e -> applyPlane.run());
    }

    /** A tool's button: lit while its tool is at work; also what its key and the console's "tool" command press. */
    private SphereCreo.Button toolButton(String name, SphereCreo.Size size, Icon icon, String label, String tip,
                                         Supplier<Tools.Tool> make) {
        final SphereCreo.Button b = new SphereCreo.Button(size, icon, label, tip, () -> vp.setTool(make.get()));
        b.on(() -> name.equals(activeTool));
        toolNamed.put(name, b);
        toolButtons.add(b);
        return b;
    }

    private SphereCreo.Button large(Icon icon, String label, String tip, Runnable r) {
        return new SphereCreo.Button(SphereCreo.Size.LARGE, icon, label, tip, r);
    }

    private SphereCreo.Button small(Icon icon, String label, String tip, Runnable r) {
        return new SphereCreo.Button(SphereCreo.Size.SMALL, icon, label, tip, r);
    }

    /** A small button lit while an option is on, which a click flips. */
    private SphereCreo.Button toggle(Icon icon, String label, String tip, java.util.function.BooleanSupplier on, Runnable flip) {
        return small(icon, label, tip, () -> {
            flip.run();
            vp.repaint();
        }).on(on);
    }

    /** A button that opens a menu under itself. */
    private SphereCreo.Button menuButton(SphereCreo.Size size, Icon icon, String label, String tip, Supplier<JPopupMenu> menu) {
        final SphereCreo.Button[] b = new SphereCreo.Button[1];
        b[0] = new SphereCreo.Button(size, icon, label, tip, () -> menu.get().show(b[0], 0, b[0].getHeight()));
        b[0].menu();
        return b[0];
    }

    private JPopupMenu solidsPopup() {
        final JPopupMenu m = new JPopupMenu();
        for (var t : com.sphere.core.geant4.Solids.types()) {
            m.add(item(t.label() + "  (" + t.g4Class() + ")", null, () -> console.run("solid " + t.tag())));
        }
        return m;
    }

    private JPopupMenu sectionPopup() {
        final JPopupMenu m = new JPopupMenu();
        for (int i = 0; i < 3; i++) {
            final int k = i;
            m.add(item("Normal to " + "XYZ".charAt(i), null, () -> {
                vp.sectionAxis = k;
                vp.section = true;
                vp.repaint();
            }));
        }
        return m;
    }

    /** What Copy took: the names of the placements, which Paste copies. */
    private final List<String> clipboard = new ArrayList<>();

    private void copySelection() {
        clipboard.clear();
        for (PhysicalVolume pv : model.selection()) {
            clipboard.add(pv.name());
        }
        status.setText(clipboard.isEmpty() ? "Nothing selected to copy"
            : clipboard.size() + " placement" + (clipboard.size() > 1 ? "s" : "") + " copied: Ctrl+V pastes them");
    }

    /** Creo's paste: copies of what was copied, on the originals, the Move tool at hand to put them in place. */
    private void paste() {
        if (clipboard.isEmpty()) {
            status.setText("Nothing copied: select volumes, then Ctrl+C");
            return;
        }
        final List<PhysicalVolume> made = new ArrayList<>();
        for (String n : clipboard) {
            if (model.geometry.placement(n) == null) {
                continue;
            }
            final Set<PhysicalVolume> before = new java.util.HashSet<>(model.geometry.placements());
            console.run("copy " + n + " 1");
            for (PhysicalVolume pv : model.geometry.placements()) {
                if (!before.contains(pv)) {
                    made.add(pv);
                }
            }
        }
        if (made.isEmpty()) {
            status.setText("The placements copied are gone");
            return;
        }
        model.selectAll(made);
        shortcutTool("move");
        status.setText(made.size() + " pasted on the originals: drag them into place (or type the move); Esc leaves them");
    }

    /** Creo's Regenerate: every expression and placement evaluated again, the view and the panels told. */
    private void regenerate() {
        model.geometry.rebuild();
        model.fireDocument(Geometry.Change.ALL);
        vp.repaint();
        long errors = 0;
        for (Geometry.Issue i : model.geometry.issues()) {
            if (i.severity() == Geometry.Severity.ERROR) {
                errors++;
            }
        }
        status.setText("Regenerated: " + model.geometry.placements().size() + " placements"
            + (errors > 0 ? ", " + errors + " error" + (errors > 1 ? "s" : "") + " (see the console)" : ", no error"));
    }

    /** The Geant4 installations found, in the console. */
    private void showInstallations() {
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
    }

    /** The name of a tool's button: what the ribbon lights while it is at work. */
    static String toolName(Tools.Tool t) {
        if (t == null) {
            return "select";
        }
        if (t instanceof Tools.MoveTool mt) {
            return mt.rotate ? "rotate" : "move";
        }
        if (t instanceof Tools.PolygonTool pt) {
            return pt.revolve ? "revolve" : "extrude";
        }
        if (t instanceof SmartTools.SketchTool) {
            return "sketch";
        }
        if (t instanceof SmartTools.PushPullTool) {
            return "pushpull";
        }
        return t.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A tool's icon, for its dashboard (G4code's too). */
    static Icon toolIcon(Tools.Tool t) {
        final Color c = new Color(0x8AA4C8);
        return switch (toolName(t)) {
            case "move" -> Icons.move(32);
            case "rotate" -> Icons.rotate(32);
            case "measure" -> Icons.measure(32);
            case "box" -> Icons.box(32, c);
            case "tube" -> Icons.tube(32, c);
            case "sphere" -> Icons.sphere(32, c);
            case "cone" -> Icons.cone(32, c);
            case "torus" -> Icons.torus(32, c);
            case "extrude" -> Icons.extrude(32, c);
            case "revolve" -> Icons.revolve(32, c);
            case "sketch" -> Icons.sketch(32);
            case "pushpull" -> Icons.pushPull(32);
            default -> Icons.select(32);
        };
    }

    /**
     * The menus' keyboard shortcuts, on the window: the menus are no longer shown, their keys still work (Ctrl+S,
     * Ctrl+Z, F5 to F9...).
     */
    private void installAccelerators(JMenuBar bar) {
        final javax.swing.InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        final javax.swing.ActionMap am = getRootPane().getActionMap();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            accelerators(bar.getMenu(i), im, am);
        }
    }

    private void accelerators(JMenu menu, javax.swing.InputMap im, javax.swing.ActionMap am) {
        for (java.awt.Component c : menu.getMenuComponents()) {
            if (c instanceof JMenu sub) {
                accelerators(sub, im, am);
            } else if (c instanceof JMenuItem it && it.getAccelerator() != null) {
                final String key = "g4editor:" + it.getAccelerator();
                im.put(it.getAccelerator(), key);
                am.put(key, new javax.swing.AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (it.isEnabled()) {
                            it.doClick(0);
                        }
                    }
                });
            }
        }
    }

    /** Tool letters, FreeCAD-like; also Delete and the console key. */
    private boolean shortcut(KeyEvent e) {
        if (e.isControlDown() || e.isAltDown()) {
            return false;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_Q -> shortcutTool("sketch");
            case KeyEvent.VK_E -> shortcutTool("pushpull");
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
            case KeyEvent.VK_L -> toggleLive();
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
        final AbstractButton b = toolNamed.get(switch (name) {
            case "push", "pull", "push/pull", "pushpull" -> "pushpull";
            case "draw", "freehand", "sketch" -> "sketch";
            default -> name;
        });
        if (b != null) {
            b.doClick();
        }
    }

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    private JMenuBar menus() {
        final JMenuBar bar = new JMenuBar();
        final JMenu file = menu("File", 'F');
        fileMenu = file;
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
                + " there: solids, materials, colors, rotations and what they hold"));
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
        edit.add(item("Copy", "control C", this::copySelection, "The selected placements, for Ctrl+V"));
        edit.add(item("Paste", "control V", this::paste,
            "Copies of the placements copied, put in place by dragging them (Creo's paste)"));
        edit.add(item("Duplicate", null, () -> forSelection(pv -> "copy " + pv.name() + " 1")));
        edit.add(item("Delete", null, this::deleteSelection));
        edit.add(item("Select all", "control A", () -> model.selectAll(model.geometry.placements())));
        edit.add(item("Regenerate", "control G", this::regenerate, "Evaluates every expression and placement again"));
        edit.addSeparator();
        edit.add(item("Fit the world around the detector", null, () -> console.run("world fit")));
        edit.addSeparator();
        edit.add(item("Command palette...", "control shift P", () -> ActionPalette.show(this, menuBar),
            "Every command of these menus, found by typing a few letters"));
        bar.add(edit);
        updateUndoItems();

        final JMenu view = menu("View", 'V');
        view.add(item("Standard orientation", "control D", () -> vp.setView("iso", true)));
        view.add(item("Repaint", "control R", vp::repaint));
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
        view.add(check("Snap to corners, middles, centers and edges (Ctrl held: off)", true, b -> vp.vertexSnap = b));
        view.add(check("Check what is drawn for overlaps as it is drawn", true, b -> {
            vp.liveCheck = b;
            vp.repaint();
        }));
        view.add(check("Z up (FreeCAD) instead of Y up (Geant4)", false, b -> vp.setUpAxis(!b)));
        view.add(check("Dark background", vp.darkTheme, b -> {
            vp.darkTheme = b;
            vp.repaint();
        }));
        bar.add(view);

        final JMenu create = menu("Create", 'C');
        create.add(item("Sketch freehand (Q) - drawn, read, made", null, () -> shortcutTool("sketch"),
            "Draw a circle, a rectangle, a hexagon, any outline or a line by hand: it is read as the shape it looks"
                + " like and made a tube, a box, a prism, a plate (Space offers another reading)"));
        create.add(item("Push/Pull a face (E)", null, () -> shortcutTool("pushpull"),
            "Drag a face of a box, tube, cone, sphere, torus, trapezoid or extrusion: the size it stands for follows"));
        create.addSeparator();
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
        templates.add(item("Template gallery...", null, this::openGallery,
            "Every template drawn in 3D with its beam: search, parameters, physics, goals"));
        templates.addSeparator();
        for (Templates.Template t : Templates.catalog()) {
            templates.add(item(t.title() + "  (" + t.kind() + ")", null, () -> console.run("template " + t.kind())));
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
        tools.addSeparator();
        tools.add(item("Physics Live on the drawing (L)", null, this::toggleLive,
            "The beam's shower, the energy kept, the sampling, on the drawing and again at every edit"));
        tools.add(item("Goal seek...", null, this::goalSeek,
            "The value of a variable that meets a physics target, tried on a copy of the design"));
        tools.add(item("Relations from the drawing...", null, this::relationsDialog,
            "Stacks drawn by hand made parametric: thicknesses become variables, positions follow"));
        bar.add(tools);

        final JMenu g4 = menu("Geant4", 'G');
        g4.add(item("Run in Geant4...", "F5", () -> showBottom(runPanel)));
        g4.add(item("Geant4 installations found", null, this::showInstallations));
        bar.add(g4);

        final JMenu help = menu("Help", 'H');
        helpMenu = help;
        help.add(item("Console commands", null, () -> {
            bottom.setSelectedIndex(0);
            console.run("help");
        }));
        help.add(item("Mouse and keyboard", null, this::showKeys));
        help.add(item("Files: GDML and ROOT", null, this::showFormats));
        bar.add(help);
        // Sphere's dark theme leaves Nimbus's MenuBar:Menu text color all but the bar's background, and
        // Nimbus overrides a color that comes from the theme (a UIResource): the theme's text color, as a
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
            G4editor reads and writes three kinds of files; File > Open recognizes them by their content.

            GDML for Geant4 (.gdml)
              The design as it is: expressions with units, variables, NIST materials by name, colors,
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
            Mouse (PTC Creo's; View > Navigation > Creo mouse off gives FreeCAD's)
              middle drag: spin, about the spin center (or the point under the mouse when it is hidden)
              Shift+middle drag: pan     Ctrl+middle drag: zoom (up and down) and turn (across)
              wheel: zoom at the cursor (Ctrl: finely, Shift: coarsely)     middle click: OK
              click: select     Ctrl+click: add or remove     drag a box: select (left to right: what is wholly
                inside, right to left: what it touches)
              double click a volume: its dimensions on the model; double click one to change it
              right click: menu (in a tool: OK, Cancel, snaps)     click the navigation cube: standard view
            Drawing
              a press takes the point at once; press and drag from the first point draws the base in one stroke
              while drawing: middle drag orbits, Shift+middle or right drag pans, the left button only draws
              the assistant snaps to corners, middles of edges, centers of circles and faces, edges, lines up with
              the points it saw (dashed guides), the axes and 45 degrees from the last point; Ctrl held: free
              sizes stop at the design's variables (kept by name), at sizes already drawn, level with neighbors
              the preview turns red when it would overlap, blue when it only touches; it says where it goes
              type values: 12, 12 cm, 2*Rin+5mm    Tab: next field   Enter: take the step   Esc: cancel
              Shift: horizontal / vertical edge (outlines)   a first point on a face draws on that face
            Smart tools
              Q sketch: draw freehand, a circle becomes a tube, a rectangle a box, a hexagon a prism, a line a plate
                (Space: another reading: sphere, cone, extrusion, revolution)
              E push/pull: drag a face out or in, its length or radius follows, the opposite face stays
              M move: drag the selected volume, it lands flush against its neighbors; Ctrl drags a copy,
                then type 5 and Enter for five in a row, /5 to spread five over the gap
            Physics (Analysis > Physics Live)
              the beam is the design's gun (Run panel, C++): its line, the energy left along it in heat colors,
                the shower's maximum and the cylinder holding 95% of it, red where it leaves the detector
              Goal Seek: a target (95% kept, 25 X0, 2 t...) and a variable; the value is searched on a copy
              Relations (Model): plates drawn face to face become a stack of variables; change one, the rest follow
              console: beam e- 50 GeV along +z   goal containment >= 95% by cal_nLayers   relations apply
            Interface (PTC Creo's)
              the ribbon: File, Model, Analysis, Applications (Geant4 runs, G4code, C++, ROOT, GDML), View; a double click on a tab
                folds it away, another unfolds it; the quick access bar above: new, open, save, undo, redo, find
              a tool at work gets its own tab, its dashboard: its values, where the volume goes, OK and Cancel
                (a middle click is OK, as in Creo)
              the graphics toolbar over the view: refit, zoom, saved orientations, display style, color by,
                datum planes (FRONT, TOP, RIGHT), spin center, grid, section, isolate, perspective
            Keys
              Q sketch  E push/pull  B box  T tube  S sphere  C cone  P extrude  V revolve  M move  R rotate  D measure
              L Physics Live: the beam's shower, the energy kept, the sampling, on the drawing
              0-8 views   F fit   O perspective   X section   G grid snap   H hide   Delete
              I isolate the selection (again: show all)    K color by material, density, X0, depth...
              Space: console    Ctrl+Z / Ctrl+Y undo / redo    Ctrl+S save    Ctrl+D standard orientation
              Ctrl+C / Ctrl+V copy and paste (then drag the copy into place)    Ctrl+G regenerate    Ctrl+R repaint
              Ctrl+Shift+P: every command by name    files (GDML, ROOT) can be dropped on the window
            """, "G4editor - mouse and keyboard", JOptionPane.INFORMATION_MESSAGE);
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

    // ------------------------------------------------------------------
    // Physics Live, goals, relations
    // ------------------------------------------------------------------

    private void toggleLive() {
        setLive(!live.isOn());
    }

    private void setLive(boolean on) {
        live.setOn(on);
        PREFS.putBoolean("physicsLive", on);
        status.setText(on ? "Physics Live: " + live.report().beam().describe() + " (Analysis > Beam aims it; the card"
            + " opens its panel)" : "Physics Live off");
        ribbon.repaint();
    }

    /** The gun's aim, particles and energies at hand, and the panel for the rest. */
    private JPopupMenu beamPopup() {
        final JPopupMenu m = new JPopupMenu();
        m.add(item("Along +Z, through the center", null, () -> live.aim(com.sphere.core.geant4.Vec3.Z, null)));
        m.add(item("Along +X", null, () -> live.aim(com.sphere.core.geant4.Vec3.X, null)));
        m.add(item("Along +Y", null, () -> live.aim(com.sphere.core.geant4.Vec3.Y, null)));
        m.add(item("Through the selection", null, () -> {
            if (!live.aimAtSelection()) {
                status.setText("Select the volumes the beam should go through first");
            }
        }));
        m.add(item("Along the view", null, live::aimAlongView));
        m.addSeparator();
        for (String[] b : new String[][] {{"e-", "1 GeV"}, {"e-", "10 GeV"}, {"e-", "100 GeV"}, {"gamma", "10 GeV"},
            {"mu-", "10 GeV"}, {"pi-", "50 GeV"}, {"proton", "100 GeV"}}) {
            m.add(item(b[0] + "  " + b[1], null, () -> {
                final com.sphere.core.geant4.BeamPhysics.Beam now = com.sphere.core.geant4.BeamPhysics.Beam.of(model.geometry);
                try {
                    live.setBeam(now.with(com.sphere.core.geant4.BeamPhysics.Particle.of(b[0]), com.sphere.core.geant4.Expr.eval(
                        com.sphere.core.geant4.Inputs.normalize(b[1], com.sphere.core.geant4.Units.Category.ENERGY, "mm"), null)));
                } catch (GeometryException e) {
                    status.setText(e.getMessage());
                }
                if (!live.isOn()) {
                    setLive(true);
                }
            }));
        }
        m.addSeparator();
        m.add(item("The beam, the layers, goals...", null, () -> showBottom(live.panel)));
        return m;
    }

    private void goalSeek() {
        showBottom(live.panel);
        live.panel.target.requestFocusInWindow();
        live.panel.target.selectAll();
    }

    /** Creo's Relations, read from the drawing: the stacks drawn by hand become variables and sums of them. */
    private void relationsDialog() {
        final List<PhysicalVolume> scope = new ArrayList<>(model.selection());
        final Relations.Plan p = Relations.plan(model.geometry, scope);
        if (p.isEmpty()) {
            JOptionPane.showMessageDialog(this, p.findings().isEmpty() ? "No stack found" + (scope.isEmpty() ? ""
                : " in the selection") + ".\nRelations are read from volumes standing face to face along an axis:"
                + " unturned boxes, or tubes, cones and trds along z." : String.join("\n", p.findings()), "Relations",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final StringBuilder b = new StringBuilder("<html><body style='width:460px'>");
        for (String f : p.findings()) {
            b.append("&bull; ").append(f.replace("&", "&amp;").replace("<", "&lt;")).append("<br>");
        }
        b.append("<br>Variables: <b>").append(String.join(", ", p.variables())).append("</b><br><br>Nothing moves now."
            + " Change a variable afterwards (double click on the model, the Variables, a goal) and the stack follows.");
        if (JOptionPane.showConfirmDialog(this, b.append("</body></html>").toString(), "Relations",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            model.edit("Relations", () -> {
                try {
                    Relations.apply(model.geometry, p);
                } catch (GeometryException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            });
        } catch (IllegalStateException e) {
            status.setText("Relations not written: " + e.getMessage());
            return;
        }
        for (String c : p.commands()) {
            model.log(c);
        }
        status.setText("Relations written: " + String.join(", ", p.variables()));
        navigator.show("Variables");
    }

    /**
     * The template gallery; the template chosen is built by the console's template command (logged, one undo step),
     * its beam set as the design's gun when asked.
     */
    private void openGallery() {
        final TemplateGallery.Choice c = TemplateGallery.choose(this, "Create", vp.darkTheme);
        if (c == null) {
            return;
        }
        bottom.setSelectedIndex(0);
        console.run(c.command());
        if (c.beamCommand() != null) {
            console.run(c.beamCommand());
        }
        vp.fitAll(true);
    }

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
            final java.util.Map<String, String> generated = new java.util.LinkedHashMap<>(
                new CppExport(model.geometry, CppExport.Options.defaults(model.geometry)).files());
            // the networks attached to detectors, transcribed and checked before anything is written
            com.sphere.core.geant4.NetworkExport.apply(model.geometry, generated, console::print);
            final var files = CppExport.write(dir, generated);
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
    SphereCreo.Ribbon ribbonBar() {
        return ribbon;
    }

    JMenu fileMenu() {
        return fileMenu;
    }

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
