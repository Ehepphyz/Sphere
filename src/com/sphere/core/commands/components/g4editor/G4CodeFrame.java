package com.sphere.components.g4editor;

import com.sphere.components.editor.CodeTab;
import com.sphere.components.editor.EditorDiagnostic;
import com.sphere.components.editor.KnowledgeBase;
import com.sphere.core.geant4.CppExport;
import com.sphere.core.geant4.CppGeometry;
import com.sphere.core.geant4.CppInsert;
import com.sphere.core.geant4.Gdml;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryFiles;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.Overlaps;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Script;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Templates;
import com.sphere.core.geant4.UiCommands;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geantbackend.Geant4AppBuilder;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * :g4code -- a Geant4 application written in C++, in three columns.
 *
 * Left, the project: its files, the volumes the code builds, and Geant4's
 * API (3400 classes of Geant4 master with their documentation). In the
 * middle, the code, in SphereCoder's editor with Geant4 completion and hover.
 * Right, the detector in G4editor's 3D view.
 *
 * The drawing is the code's: as it is typed, DetectorConstruction::Construct()
 * is run by CppGeometry and the geometry it builds is drawn, its overlaps
 * checked as Geant4's CheckOverlaps would and marked on the G4PVPlacement
 * lines; what the reading cannot know is marked too. A volume clicked in 3D
 * shows the line that placed it; the caret on such a line selects it in 3D;
 * a volume moved or turned with the mouse has its G4PVPlacement's position
 * and rotation rewritten in the code. A volume drawn in 3D, or brought from
 * G4editor, is written into the code with all that was given to it (solid
 * and its expressions, material, colour, visibility, wireframe, rotation,
 * mirror, copy number, what it holds); a characteristic changed in the
 * properties is written where the code gives it. When a
 * Geant4 is installed, the application compiles and runs from here, the
 * compiler's errors marked in the code.
 */
public final class G4CodeFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static G4CodeFrame shared;

    private final Function<String, String> settings;
    private final EditorModel model = new EditorModel(Geometry.blank());
    private final Viewport vp;
    private final ModelTree volumes;
    private final ApiBrowser api;
    private final JTabbedPane left = new JTabbedPane();
    private final JTabbedPane editors = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JTabbedPane rightTabs = new JTabbedPane();
    private final List<CodeTab> tabs = new ArrayList<>();
    private final ProjectTree projectTree = new ProjectTree(new ProjectHost());
    private final DefaultListModel<Problem> problems = new DefaultListModel<>();
    private final JList<Problem> problemList = new JList<>(problems);
    private final JTextArea console = new JTextArea();
    private final JEditorPane inspector = new JEditorPane();
    private final JLabel status = new JLabel(" ");
    private final JLabel reading = new JLabel(" ");
    private final javax.swing.JCheckBox live = new javax.swing.JCheckBox("Live 3D", true);
    private final List<JToggleButton> toolButtons = new ArrayList<>();
    private final Timer reparse = new Timer(350, e -> reparse());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "g4code-reader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong generation = new AtomicLong();
    /** Findings by file: the code reading's and overlaps (live), the compiler's (last build). */
    private final Map<Path, List<EditorDiagnostic>> liveFindings = new HashMap<>();
    private final Map<Path, List<EditorDiagnostic>> buildFindings = new HashMap<>();
    private final List<Problem> buildProblems = new ArrayList<>();
    private final List<Problem> overlapProblems = new ArrayList<>();
    /** Where each placement stood when the code drew it: a drag that moves one is written back. */
    private final Map<PhysicalVolume, Vec3> drawnPositions = new IdentityHashMap<>();
    private final Map<PhysicalVolume, Vec3> drawnRotations = new IdentityHashMap<>();
    /** The volumes the code made at the last reading, and their characteristics then: what changed is written back. */
    private final Set<LogicalVolume> drawnVolumes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<LogicalVolume, Look> drawnLooks = new IdentityHashMap<>();
    private final Map<Solid, Map<String, String>> drawnParams = new IdentityHashMap<>();
    /** The texts the last reading read: the offsets it gave are theirs. */
    private Map<Path, String> readTexts = Map.of();
    private final JComboBox<String> materialBox = new JComboBox<>();

    /** How a volume is made and drawn, as the properties change it. */
    private record Look(String material, int color, boolean visible, boolean wireframe, String sensitive) {
        static Look of(LogicalVolume lv) {
            return new Look(lv.material(), lv.color(), lv.visible(), lv.wireframe(), lv.sensitive());
        }
    }
    private CppGeometry.Result last;
    private Path project;
    private boolean adopting, syncing, fitted;
    /** A drawing made with no application open has been offered as C++ once: it is not asked again. */
    private boolean drawingOffered;
    private int caretLine = -1;

    record Problem(Path file, int line, Geometry.Severity severity, String message, String source) {
        @Override
        public String toString() {
            return (file == null ? "" : file.getFileName() + (line > 0 ? ":" + line : "") + "  ") + message;
        }
    }

    // ==================================================================
    // Window
    // ==================================================================

    public G4CodeFrame(Path target, Function<String, String> settings) {
        super("G4code");
        this.settings = settings == null ? k -> null : settings;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1680, 980);
        setLocationRelativeTo(null);

        vp = new Viewport(model);
        vp.darkTheme = isDark();
        volumes = new ModelTree(model);
        api = new ApiBrowser(this::insertAtCaret);
        reparse.setRepeats(false);

        // Left: the project
        left.addTab("Project", projectTree);
        left.addTab("Volumes", volumes);
        left.addTab("Geant4 API", api);
        left.addTab("UI commands", uiCommandsPanel());

        // Middle: the code, and what is wrong with it
        problemList.setCellRenderer(new ProblemRenderer());
        problemList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && problemList.getSelectedValue() != null) {
                    final Problem p = problemList.getSelectedValue();
                    if (p.file() != null) {
                        reveal(p.file(), Math.max(1, p.line()));
                    }
                }
            }
        });
        editors.addChangeListener(e -> {
            caretLine = -1;
            final CodeTab t = current();
            if (t != null && t.getFile() != null && isGdml(t.getFile().toPath()) && project == null) {
                schedule();
            }
        });
        final JPanel problemsPanel = new JPanel(new BorderLayout());
        final JLabel problemsTitle = new JLabel(" Problems: what the code reading, the overlap check and the compiler found"
            + " (double-click to go)");
        problemsTitle.setFont(problemsTitle.getFont().deriveFont(Font.BOLD, 11f));
        problemsPanel.add(problemsTitle, BorderLayout.NORTH);
        problemsPanel.add(new JScrollPane(problemList), BorderLayout.CENTER);
        final JSplitPane middle = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editors, problemsPanel);
        middle.setResizeWeight(0.8);
        problemList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                problemMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                problemMenu(e);
            }
        });

        // Right: the detector
        inspector.setEditable(false);
        inspector.setContentType("text/html");
        inspector.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        inspector.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null
                && e.getDescription().startsWith("goto:")) {
                final String[] f = e.getDescription().substring(5).split("\\|");
                reveal(Path.of(f[0]), Integer.parseInt(f[1]));
            } else if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null
                && e.getDescription().startsWith("class:")) {
                left.setSelectedComponent(api);
                api.showClass(e.getDescription().substring(6));
            }
        });
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        rightTabs.addTab("Inspector", new JScrollPane(inspector));
        // the characteristics of the selection: each change is written into the code
        final Script script = new Script(model.geometry, new Script.Host() {
            public void print(String text) {
                say(text);
            }

            public void error(String text) {
                say(text);
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
        });
        rightTabs.addTab("Properties", new PropertySheet(model, script));
        rightTabs.addTab("Console", new JScrollPane(console));
        final JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, vp, rightTabs);
        right.setResizeWeight(0.66);

        final JSplitPane centerRight = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, middle, right);
        centerRight.setResizeWeight(0.55);
        final JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, centerRight);
        main.setDividerLocation(330);
        // Without a minimum of their own, Swing takes a component's current size as its minimum (the 3D view has
        // no layout): the dividers would not move. Every column may shrink, and fold away with the divider's arrows.
        for (JComponent c : new JComponent[] {left, middle, right, centerRight, vp, rightTabs, editors, problemsPanel}) {
            c.setMinimumSize(new java.awt.Dimension(60, 40));
        }
        for (JSplitPane sp : new JSplitPane[] {main, centerRight, middle, right}) {
            sp.setOneTouchExpandable(true);
            sp.setContinuousLayout(true);
        }

        final JPanel statusBar = new JPanel(new BorderLayout(12, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusBar.add(status, BorderLayout.CENTER);
        statusBar.add(reading, BorderLayout.EAST);
        reading.setForeground(new Color(0x7D8796));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar(), BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        keys();

        vp.setStatusSink(s -> status.setText(s));
        vp.setHoverSink(in -> status.setText(Viewport.describe(model.geometry, in)));
        vp.setShortcuts(this::shortcut);
        vp.setContextMenuHandler(e -> volumeMenu(e, vp, model.primary(), null));
        volumes.setMenu((e, n) -> volumeMenu(e, volumes.tree(), n.pv(), n.lv()));
        model.addListener(new EditorModel.Listener() {
            public void selectionChanged() {
                inspect();
                if (!syncing && !adopting) {
                    revealSelection();
                }
            }

            public void documentChanged(Geometry.Change c) {
                if (!adopting && c == Geometry.Change.ALL) {
                    writeBack();
                }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmClose()) {
                    for (CodeTab t : tabs) {
                        t.dispose();
                    }
                    worker.shutdownNow();
                    dispose();
                    if (shared == G4CodeFrame.this) {
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
        // Geant4's API for completion and hover, read once off the event thread
        final Thread preload = new Thread(KnowledgeBase::loadGeant4, "g4code-api");
        preload.setDaemon(true);
        preload.start();

        SwingUtilities.invokeLater(() -> {
            centerRight.setDividerLocation(0.52);
            right.setDividerLocation(0.64);
        });
        openTarget(target);
    }

    /** Opens a project folder or a file in the shared window, raising it when it exists. */
    public static G4CodeFrame show(Path target, Function<String, String> settings) {
        if (shared == null || !shared.isDisplayable()) {
            shared = new G4CodeFrame(target, settings);
            shared.setVisible(true);
            return shared;
        }
        if (target != null) {
            shared.openTarget(target);
        }
        shared.setVisible(true);
        shared.toFront();
        return shared;
    }

    private static boolean isDark() {
        final Color c = UIManager.getColor("Panel.background");
        return c != null && (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 110;
    }

    private void openTarget(Path target) {
        if (target == null) {
            say("G4code: a Geant4 application in C++, its detector drawn as the code is typed.\n"
                + "  New project (Ctrl+Shift+N) writes one from a template, from what is drawn here, or from a GDML\n"
                + "  design turned into C++; Open (Ctrl+O) takes a folder holding a DetectorConstruction (Geant4's\n"
                + "  examples B1..B5 open as they are). Draw in 3D first and the drawing is offered as the C++.");
            setTitle("G4code");
            return;
        }
        if (Files.isDirectory(target)) {
            openProject(target);
        } else if (Files.isRegularFile(target)) {
            final Path folder = projectOf(target);
            if (folder != null && !folder.equals(project)) {
                openProject(folder);
            }
            open(target);
            if (folder == null && isGdml(target)) {
                schedule();
            }
        }
    }

    /** The application folder holding a file: the one with CMakeLists.txt or src/ and include/. */
    private static Path projectOf(Path file) {
        for (Path d = file.toAbsolutePath().getParent(); d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("CMakeLists.txt")) || (Files.isDirectory(d.resolve("src"))
                && Files.isDirectory(d.resolve("include")))) {
                return d;
            }
            if (d.getNameCount() < 2) {
                break;
            }
        }
        return null;
    }

    private void openProject(Path folder) {
        project = folder.toAbsolutePath().normalize();
        setTitle("G4code - " + project.getFileName());
        projectTree.setRoot(project);
        fitted = false;
        // the detector first: the file to look at is the one that builds it
        Path detector = null;
        for (CppGeometry.Source s : CppGeometry.sourcesOf(project)) {
            final String n = s.path().getFileName().toString();
            if (n.endsWith(".cc") && s.text().contains("::Construct()") && s.text().contains("G4VPhysicalVolume")) {
                detector = s.path();
                break;
            }
        }
        if (detector != null) {
            open(detector);
        }
        say("Project " + project);
        schedule();
    }

    // ==================================================================
    // Toolbar and keys
    // ==================================================================

    private JComponent toolbar() {
        final JToolBar t = new JToolBar();
        t.setFloatable(false);
        t.add(button(Icons.file(20, "new"), "New project in C++ from a template, the detector drawn here or a GDML design"
            + " (Ctrl+Shift+N)", this::newProject));
        t.add(button(Icons.file(20, "open"), "Open a project folder or a file (Ctrl+O)", this::openDialog));
        t.add(button(Icons.file(20, "save"), "Save the file (Ctrl+S); Ctrl+Shift+S saves them all", this::saveCurrent));
        t.addSeparator();
        t.add(text("Build", "Compile with CMake against the Geant4 found (Ctrl+B)", () -> build(false)));
        final JButton run = text("Run", "Compile if needed, then run in batch on the open macro or run1.mac (F5)", () -> build(true));
        run.setIcon(Icons.run(14));
        t.add(run);
        t.add(text("Stop", "Stop the build or the run", () -> {
            if (Geant4AppBuilder.stop()) {
                say("stopped");
            }
        }));
        t.addSeparator();
        final JButton insert = text("Insert", "Geant4 code to insert at the caret: volumes, loops, materials...", null);
        insert.addActionListener(e -> snippets(insert));
        t.add(insert);
        t.add(text("API", "Find a Geant4 class or method (Ctrl+Shift+A); the word under the caret is searched", () -> {
            left.setSelectedComponent(api);
            api.focusSearch(wordAtCaret());
        }));
        t.addSeparator();
        t.add(tool(Icons.select(20), "Select (Esc): a click shows the line that placed the volume", () -> null));
        t.add(tool(Icons.move(20), "Move the selection (M): drag the gizmo, the position is rewritten in the code",
            () -> new Tools.MoveTool(vp, false)));
        t.add(tool(Icons.rotate(20), "Turn the selection (R): drag a ring, the rotation is rewritten in the code",
            () -> new Tools.MoveTool(vp, true)));
        t.addSeparator();
        final Color c = new Color(0x8AA4C8);
        final String into = ": written into the code, in the volume it is drawn in";
        t.add(tool(Icons.box(20, c), "Draw a box, G4Box (B): corner, opposite corner, height" + into,
            () -> new Tools.BoxTool(vp)));
        t.add(tool(Icons.tube(20, c), "Draw a tube, G4Tubs (T): center, radius, length" + into, () -> new Tools.TubeTool(vp)));
        t.add(tool(Icons.sphere(20, c), "Draw a sphere, G4Orb / G4Sphere (S): center, radius" + into,
            () -> new Tools.SphereTool(vp)));
        t.add(tool(Icons.cone(20, c), "Draw a cone, G4Cons (C): center, radius, height, top radius" + into,
            () -> new Tools.ConeTool(vp)));
        t.add(tool(Icons.torus(20, c), "Draw a torus, G4Torus: center, ring radius, tube radius" + into,
            () -> new Tools.TorusTool(vp)));
        t.add(tool(Icons.extrude(20, c), "Draw an extrusion, G4ExtrudedSolid (P): outline, thickness" + into,
            () -> new Tools.PolygonTool(vp, false)));
        t.add(tool(Icons.revolve(20, c), "Draw a revolution, G4GenericPolycone (V): (r, z) profile" + into,
            () -> new Tools.PolygonTool(vp, true)));
        materialBox.setEditable(true);
        materialBox.setMaximumSize(new java.awt.Dimension(190, 28));
        for (String m : List.of("G4_Al", "G4_Si", "G4_Pb", "G4_Fe", "G4_Cu", "G4_W", "G4_AIR", "G4_Galactic", "G4_WATER",
            "G4_PLASTIC_SC_VINYLTOLUENE", "G4_PbWO4", "G4_BGO", "G4_CESIUM_IODIDE", "G4_lAr", "G4_CONCRETE",
            "G4_STAINLESS-STEEL", "G4_KAPTON")) {
            materialBox.addItem(m);
        }
        materialBox.setSelectedItem(model.material);
        materialBox.setToolTipText("Material of the volumes drawn next: a NIST name, a material the code defines, or a"
            + " common name (lead, water, scintillator)");
        materialBox.addActionListener(e -> {
            final String v = String.valueOf(materialBox.getSelectedItem()).trim();
            final String m = model.geometry.materials().material(v) != null ? v : com.sphere.core.geant4.Intent.material(v);
            if (m != null) {
                model.material = m;
                if (!m.equals(v)) {
                    materialBox.setSelectedItem(m);
                }
            } else {
                say("unknown material " + v + ": a NIST name (G4_...) or one the code defines");
            }
        });
        t.add(materialBox);
        t.addSeparator();
        t.add(button(Icons.check(20), "Check overlaps finely (Geant4's CheckOverlaps with 2000 points)", () -> overlaps(2000)));
        t.add(text("GDML", "Write the detector the code builds as a GDML file", this::exportGdml));
        t.add(text("G4editor", "Open the detector (or the open GDML file) in G4editor", this::toG4editor));
        t.addSeparator();
        for (String v : new String[] {"iso", "front", "right", "top"}) {
            t.add(button(Icons.view(20, v), "View " + v, () -> vp.setView(v, true)));
        }
        t.add(button(Icons.fit(20), "Fit the detector in the view", () -> vp.fitAll(true)));
        t.addSeparator();
        live.setToolTipText("Checked: the detector is drawn again as the code is typed. Unchecked: F6 draws it");
        live.setFocusable(false);
        live.setOpaque(false);
        live.addActionListener(e -> {
            if (live.isSelected()) {
                say("Live 3D on: the drawing follows the code as it is typed");
                schedule();
            } else {
                say("Live 3D off: the drawing stays as it is; F6 draws the code again");
            }
        });
        t.add(live);
        t.add(text("Redraw", "Draw the detector the code builds now (F6)", this::reparse));
        return t;
    }

    private JToggleButton tool(javax.swing.Icon icon, String tip, java.util.function.Supplier<Tools.Tool> make) {
        final JToggleButton b = new ToolToggle(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> {
            for (JToggleButton o : toolButtons) {
                o.setSelected(o == b);
            }
            vp.setTool(make.get());
        });
        toolButtons.add(b);
        b.setSelected(toolButtons.size() == 1);
        return b;
    }

    /**
     * A tool button whose state shows: the theme paints a pressed toggle much as a plain one, so the chosen
     * tool is framed and underlined in the accent colour.
     */
    private static final class ToolToggle extends JToggleButton {
        private static final long serialVersionUID = 1L;

        ToolToggle(javax.swing.Icon icon) {
            super(icon);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (isSelected()) {
                final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                final Color accent = accent();
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 50));
                g2.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
                g2.setColor(accent);
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
                g2.fillRect(4, getHeight() - 4, getWidth() - 8, 2);
                g2.dispose();
            }
        }
    }

    private static Color accent() {
        try {
            return com.sphere.theme.ThemeManager.getCurrentPalette().getAccent();
        } catch (Throwable e) {
            final Color c = UIManager.getColor("List.selectionBackground");
            return c != null ? c : new Color(0x2F6FDE);
        }
    }

    /** The view's letters: M moves, R turns, Esc selects (in the 3D view, as in G4editor). */
    private boolean shortcut(KeyEvent e) {
        if (e.isControlDown() || e.isAltDown() || toolButtons.size() < 3) {
            return false;
        }
        if (e.getKeyCode() == KeyEvent.VK_DELETE && !model.selection().isEmpty()) {
            deleteSelection();
            return true;
        }
        final int i = switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> 0;
            case KeyEvent.VK_M -> 1;
            case KeyEvent.VK_R -> 2;
            case KeyEvent.VK_B -> 3;
            case KeyEvent.VK_T -> 4;
            case KeyEvent.VK_S -> 5;
            case KeyEvent.VK_C -> 6;
            case KeyEvent.VK_P -> 8;
            case KeyEvent.VK_V -> 9;
            default -> -1;
        };
        if (i < 0 || i >= toolButtons.size()) {
            return false;
        }
        toolButtons.get(i).doClick();
        return true;
    }

    private static JButton button(javax.swing.Icon icon, String tip, Runnable r) {
        final JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JButton text(String label, String tip, Runnable r) {
        final JButton b = new JButton(label);
        b.setToolTipText(tip);
        b.setFocusable(false);
        if (r != null) {
            b.addActionListener(e -> r.run());
        }
        return b;
    }

    private void keys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), this::saveCurrent);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::saveAll);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), this::openDialog);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::newProject);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), () -> build(false));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> build(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), () -> {
            left.setSelectedComponent(api);
            api.focusSearch(wordAtCaret());
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), () -> close(current()));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), this::reparse);
    }

    private void bind(KeyStroke ks, Runnable r) {
        final String name = "g4code-" + ks;
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    // ==================================================================
    // Project files
    // ==================================================================

    /** What the project tree asks of the window when files change under the open tabs. */
    private final class ProjectHost implements ProjectTree.Host {
        public void open(Path file) {
            G4CodeFrame.this.open(file);
        }

        public void moved(Path from, Path to) {
            for (CodeTab t : new ArrayList<>(tabs)) {
                final Path f = t.getFile() == null ? null : t.getFile().toPath().toAbsolutePath().normalize();
                final Path a = from.toAbsolutePath().normalize();
                if (f != null && f.startsWith(a)) {
                    final Path now = to.toAbsolutePath().normalize().resolve(a.relativize(f).toString());
                    t.setFile(now.toFile());
                    retitle(t);
                }
            }
            schedule();
        }

        public void deleted(Path path) {
            final Path a = path.toAbsolutePath().normalize();
            for (CodeTab t : new ArrayList<>(tabs)) {
                if (t.getFile() != null && t.getFile().toPath().toAbsolutePath().normalize().startsWith(a)) {
                    discard(t);
                }
            }
            schedule();
        }

        public void say(String message) {
            G4CodeFrame.this.say(message);
        }
    }

    // ==================================================================
    // Editors
    // ==================================================================

    private final CodeTab.Host host = new CodeTab.Host() {
        public void openFile(File file) {
            open(file.toPath());
        }

        public void runFile(CodeTab tab) {
            build(true);
        }

        public void showPalette(String prefix) {
            left.setSelectedComponent(api);
            api.focusSearch(prefix);
        }

        public void toast(String message) {
            status.setText(message);
        }

        public List<CodeTab> allTabs() {
            return tabs;
        }

        public void selectTab(CodeTab tab) {
            editors.setSelectedComponent(tab);
        }

        public void tabStateChanged(CodeTab tab) {
            retitle(tab);
        }
    };

    /** The tab's title (and its header): the file's name, a star while it is not saved. */
    private void retitle(CodeTab tab) {
        final int i = editors.indexOfComponent(tab);
        if (i < 0 || tab.getFile() == null) {
            return;
        }
        final String title = tab.getFile().getName() + (tab.isModified() ? " *" : "");
        if (!title.equals(editors.getTitleAt(i))) {
            editors.setTitleAt(i, title);
            editors.setToolTipTextAt(i, tab.getFile().getPath());
        }
        if (editors.getTabComponentAt(i) instanceof TabHeader h) {
            h.title.setText(title);
        }
    }

    /** A tab's header: its title, and a close button; the middle button closes, the right one has a menu. */
    private final class TabHeader extends JPanel {
        private static final long serialVersionUID = 1L;
        final JLabel title = new JLabel();

        TabHeader(CodeTab tab) {
            super(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            setOpaque(false);
            final JButton x = new JButton("\u00d7");
            x.setToolTipText("Close (Ctrl+W)");
            x.setFocusable(false);
            x.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
            x.setContentAreaFilled(false);
            x.setFont(x.getFont().deriveFont(Font.BOLD, 13f));
            x.setForeground(new Color(0x8A93A3));
            x.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    x.setForeground(new Color(0xD9534F));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    x.setForeground(new Color(0x8A93A3));
                }
            });
            x.addActionListener(e -> close(tab));
            add(title);
            add(x);
            final MouseAdapter click = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        editors.setSelectedComponent(tab);
                    }
                    tabMenu(e, tab);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    tabMenu(e, tab);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        close(tab);
                    }
                }
            };
            addMouseListener(click);
            title.addMouseListener(click);
        }
    }

    private void tabMenu(MouseEvent e, CodeTab tab) {
        if (!e.isPopupTrigger()) {
            return;
        }
        final JPopupMenu m = new JPopupMenu();
        m.add(menuItem("Close", "ctrl W", () -> close(tab)));
        m.add(menuItem("Close the others", null, () -> {
            final List<CodeTab> others = new ArrayList<>(tabs);
            others.remove(tab);
            close(others);
        }));
        m.add(menuItem("Close all", null, () -> close(new ArrayList<>(tabs))));
        m.addSeparator();
        m.add(menuItem("Save", "ctrl S", () -> {
            tab.saveFile();
            retitle(tab);
        }));
        if (tab.getFile() != null) {
            m.add(menuItem("Copy path", null, () -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(tab.getFile().getPath()), null)));
        }
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String label, String key, Runnable r) {
        final JMenuItem i = new JMenuItem(label);
        if (key != null) {
            i.setAccelerator(KeyStroke.getKeyStroke(key));
        }
        i.addActionListener(e -> r.run());
        return i;
    }

    /** Closes a tab without asking (its file deleted). */
    private void discard(CodeTab t) {
        editors.remove(t);
        tabs.remove(t);
        t.dispose();
    }

    private CodeTab current() {
        return editors.getSelectedComponent() instanceof CodeTab t ? t : null;
    }

    private CodeTab tabOf(Path p) {
        final Path n = p.toAbsolutePath().normalize();
        for (CodeTab t : tabs) {
            if (t.getFile() != null && t.getFile().toPath().toAbsolutePath().normalize().equals(n)) {
                return t;
            }
        }
        return null;
    }

    /** Opens a file in a tab (or brings its tab forward). */
    CodeTab open(Path p) {
        final CodeTab existing = tabOf(p);
        if (existing != null) {
            editors.setSelectedComponent(existing);
            return existing;
        }
        final CodeTab t = new CodeTab(p.toFile());
        t.setHost(host);
        // written only when the user says so: the 3D and the properties change the code, the user validates it
        t.setAutoSave(false);
        tabs.add(t);
        editors.addTab(p.getFileName().toString(), t);
        editors.setToolTipTextAt(editors.getTabCount() - 1, p.toString());
        final TabHeader header = new TabHeader(t);
        header.title.setText(p.getFileName().toString());
        editors.setTabComponentAt(editors.getTabCount() - 1, header);
        editors.setSelectedComponent(t);
        final Document doc = t.getEditorArea().getDocument();
        doc.addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edited(t);
            }

            public void removeUpdate(DocumentEvent e) {
                edited(t);
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
        t.getEditorArea().addCaretListener(e -> caretMoved(t, e.getDot()));
        applyFindings(t);
        return t;
    }

    private void close(CodeTab t) {
        if (t != null) {
            close(List.of(t));
        }
    }

    /** Closes tabs, once the changes they hold are saved or let go as the user says. */
    private void close(List<CodeTab> which) {
        final ChangesDialog.Result r = review(which, "Close " + (which.size() == 1 ? which.get(0).getFile().getName()
            : which.size() + " files"), "These files changed since they were saved (typed, or written from the 3D view,"
            + " the properties or G4editor). Tick those to save; the others close as they are on disk.",
            "Save selected and close", "Close without saving");
        if (r != null && r.choice() == ChangesDialog.Choice.CANCEL) {
            return;
        }
        save(r);
        for (CodeTab t : which) {
            discard(t);
        }
        schedule();
    }

    /**
     * The tabs' unsaved changes, shown file by file against the files as saved, for the user to save or not.
     * Null when none of them changed.
     */
    private ChangesDialog.Result review(List<CodeTab> which, String title, String question, String saveLabel,
                                        String discardLabel) {
        final List<ChangesDialog.Change> changes = new ArrayList<>();
        for (CodeTab t : which) {
            if (t.isModified() && t.getFile() != null) {
                changes.add(new ChangesDialog.Change(t.getFile().toPath(), t.getSavedText(), t.getEditorArea().getText()));
            }
        }
        return ChangesDialog.ask(this, title, question, changes, saveLabel, discardLabel);
    }

    /** Saves the files the user ticked. */
    private void save(ChangesDialog.Result r) {
        if (r == null || r.choice() != ChangesDialog.Choice.SAVE) {
            return;
        }
        int n = 0;
        for (CodeTab t : tabs) {
            if (t.getFile() != null && t.isModified() && r.save().contains(t.getFile().toPath())) {
                t.saveFile();
                host.tabStateChanged(t);
                n++;
            }
        }
        status.setText(n == 1 ? "1 file saved" : n + " files saved");
    }

    private void edited(CodeTab t) {
        if (t.getFile() == null) {
            return;
        }
        final Path p = t.getFile().toPath();
        if (isCode(p) || isGdml(p) || isMacro(p)) {
            schedule();
        }
    }

    private void saveCurrent() {
        final CodeTab t = current();
        if (t != null && t.getFile() != null) {
            t.saveFile();
            host.tabStateChanged(t);
            status.setText("saved " + t.getFile().getName());
        }
    }

    private void saveAll() {
        for (CodeTab t : tabs) {
            if (t.isModified() && t.getFile() != null) {
                t.saveFile();
                host.tabStateChanged(t);
            }
        }
        status.setText("all saved");
    }

    private boolean confirmClose() {
        final ChangesDialog.Result r = review(tabs, "Quit G4code", "These files changed since they were saved (typed,"
            + " or written from the 3D view, the properties or G4editor). Nothing is written until you say so: tick the"
            + " files to save.", "Save selected and quit", "Quit without saving");
        if (r != null && r.choice() == ChangesDialog.Choice.CANCEL) {
            return false;
        }
        save(r);
        return true;
    }

    private void insertAtCaret(String text) {
        final CodeTab t = current();
        if (t == null) {
            status.setText("open a file to insert into");
            return;
        }
        t.getEditorArea().replaceSelection(text);
        t.getEditorArea().requestFocusInWindow();
    }

    private String wordAtCaret() {
        final CodeTab t = current();
        if (t == null) {
            return null;
        }
        try {
            final Document d = t.getEditorArea().getDocument();
            final String text = d.getText(0, d.getLength());
            int a = t.getEditorArea().getCaretPosition(), b = a;
            while (a > 0 && Character.isJavaIdentifierPart(text.charAt(a - 1))) {
                a--;
            }
            while (b < text.length() && Character.isJavaIdentifierPart(text.charAt(b))) {
                b++;
            }
            return a < b ? text.substring(a, b) : null;
        } catch (BadLocationException e) {
            return null;
        }
    }

    private void reveal(Path file, int line) {
        final CodeTab t = open(file);
        syncing = true;
        try {
            t.goToLine(line, 0);
            caretLine = line;
        } finally {
            syncing = false;
        }
    }

    private static boolean isCode(Path p) {
        final String n = p.getFileName().toString();
        return n.endsWith(".cc") || n.endsWith(".hh") || n.endsWith(".cpp") || n.endsWith(".hpp") || n.endsWith(".h")
            || n.endsWith(".icc");
    }

    private static boolean isGdml(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gdml");
    }

    private static boolean isMacro(Path p) {
        final String n = p.getFileName().toString();
        return n.endsWith(".mac") || n.endsWith(".in");
    }

    // ==================================================================
    // The detector, read from the code as it is typed
    // ==================================================================

    private void schedule() {
        if (live.isSelected()) {
            reparse.restart();
        }
    }

    /** The project's sources as the editors hold them (unsaved text included), else as the disk does. */
    private List<CppGeometry.Source> sources() {
        final Map<Path, CppGeometry.Source> out = new LinkedHashMap<>();
        if (project != null) {
            for (CppGeometry.Source s : CppGeometry.sourcesOf(project)) {
                out.put(s.path().toAbsolutePath().normalize(), s);
            }
        }
        for (CodeTab t : tabs) {
            if (t.getFile() == null) {
                continue;
            }
            final Path p = t.getFile().toPath().toAbsolutePath().normalize();
            if (isCode(p) || isGdml(p)) {
                out.put(p, new CppGeometry.Source(p, t.getEditorArea().getText()));
            }
        }
        return new ArrayList<>(out.values());
    }

    private void reparse() {
        checkMacros();
        final long gen = generation.incrementAndGet();
        final CodeTab active = current();
        final Path activePath = active == null || active.getFile() == null ? null : active.getFile().toPath();
        if (project == null) {
            // a lone GDML file: its text is the detector
            if (activePath != null && isGdml(activePath)) {
                final String text = active.getEditorArea().getText();
                worker.submit(() -> {
                    try {
                        final Gdml.Result r = Gdml.read(text, activePath.getParent());
                        r.geometry().rebuild();
                        final List<CppGeometry.Note> notes = new ArrayList<>();
                        for (String w : r.warnings()) {
                            notes.add(new CppGeometry.Note(activePath, 0, Geometry.Severity.WARNING, w));
                        }
                        final CppGeometry.Result res = new CppGeometry.Result(r.geometry(), notes, new IdentityHashMap<>(),
                            new IdentityHashMap<>(), new IdentityHashMap<>(), activePath.getFileName().toString(), 0, 0,
                            activePath, CppGeometry.Links.none());
                        SwingUtilities.invokeLater(() -> shown(gen, res));
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> reading.setText("GDML: " + e.getMessage()));
                    }
                });
            }
            return;
        }
        final List<CppGeometry.Source> sources = sources();
        final Path base = project;
        worker.submit(() -> {
            final CppGeometry.Result r = CppGeometry.read(sources, base);
            SwingUtilities.invokeLater(() -> {
                if (gen == generation.get()) {
                    final Map<Path, String> texts = new HashMap<>();
                    for (CppGeometry.Source src : sources) {
                        texts.put(src.path().toAbsolutePath().normalize(), src.text());
                    }
                    readTexts = texts;
                }
                shown(gen, r);
            });
        });
    }

    private void shown(long gen, CppGeometry.Result r) {
        if (gen != generation.get()) {
            return;   // the code changed again meanwhile
        }
        last = r;
        // the selection survives the new reading, by mother, name and copy
        final Set<String> selected = new LinkedHashSet<>();
        for (PhysicalVolume pv : model.selection()) {
            selected.add(key(pv));
        }
        adopting = true;
        try {
            model.adopt(null, new GeometryFiles.Loaded(r.geometry(), GeometryFiles.Format.GDML, List.of(), List.of()), "");
            final List<PhysicalVolume> again = new ArrayList<>();
            for (PhysicalVolume pv : model.geometry.placements()) {
                if (selected.contains(key(pv))) {
                    again.add(pv);
                }
            }
            if (!again.isEmpty()) {
                model.selectAll(again);
            }
        } finally {
            adopting = false;
        }
        drawnPositions.clear();
        drawnRotations.clear();
        for (PhysicalVolume pv : model.geometry.placements()) {
            drawnPositions.put(pv, pv.position());
            drawnRotations.put(pv, pv.rotation());
        }
        drawnVolumes.clear();
        drawnLooks.clear();
        drawnParams.clear();
        for (LogicalVolume lv : model.geometry.volumes()) {
            noteDrawn(lv);
        }
        if (!fitted && model.geometry.world() != null) {
            vp.fitAll(false);
            fitted = true;
        }
        final Geometry g = model.geometry;
        reading.setText(g.world() == null ? "no detector read" : String.format(Locale.ROOT,
            "%s: %d volumes, %d placements, %d drawn, read in %.1f ms%s", r.entry().isEmpty() ? "detector" : r.entry(),
            g.volumes().size(), g.placements().size(), g.instances().size(), r.micros() / 1000.0,
            r.gdml() != null ? " (from " + r.gdml().getFileName() + ")" : ""));
        overlapProblems.clear();
        publishFindings();
        inspect();
        if (g.world() != null && g.instances().size() <= 20_000) {
            overlaps(300);
        }
    }

    private static String key(PhysicalVolume pv) {
        return (pv.mother() == null ? "" : pv.mother().name()) + "/" + pv.name() + "#" + pv.copyNumber();
    }

    /** Geant4's CheckOverlaps on every placement, off the event thread; faults go on the placement's line. */
    private void overlaps(int points) {
        final CppGeometry.Result r = last;
        if (r == null || r.geometry().world() == null) {
            return;
        }
        final long gen = generation.get();
        final boolean fine = points > 300;
        if (fine) {
            status.setText("checking overlaps with " + points + " points per surface...");
        }
        worker.submit(() -> {
            final long t0 = System.nanoTime();
            final List<Overlaps.Fault> faults = Overlaps.check(r.geometry(), points, 0, null);
            final double ms = (System.nanoTime() - t0) / 1e6;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation.get()) {
                    return;
                }
                overlapProblems.clear();
                for (Overlaps.Fault f : faults) {
                    final CppGeometry.Origin o = r.origin(f.daughter());
                    overlapProblems.add(new Problem(o == null ? null : o.file(), o == null ? 0 : o.line(),
                        Geometry.Severity.ERROR, f.describe(), "overlap"));
                }
                publishFindings();
                status.setText(faults.isEmpty() ? String.format(Locale.ROOT, "no overlap (%d points per surface, %.0f ms)",
                    points, ms) : faults.size() + " overlap(s): marked on their G4PVPlacement lines");
            });
        });
    }

    /** The problems list and the marks in the editors, from the reading, the overlaps, the compiler and the macros. */
    private void publishFindings() {
        final List<Problem> all = new ArrayList<>();
        if (last != null) {
            for (CppGeometry.Note n : last.notes()) {
                all.add(new Problem(n.file(), n.line(), n.severity(), n.message(), "reading"));
            }
        }
        all.addAll(overlapProblems);
        all.addAll(macroProblems);
        all.addAll(buildProblems);
        all.sort((a, b) -> a.severity().ordinal() - b.severity().ordinal());
        problems.clear();
        for (Problem p : all) {
            problems.addElement(p);
        }
        liveFindings.clear();
        for (Problem p : all) {
            if (p.file() == null || p.line() <= 0 || p.source().equals("compiler")) {
                continue;
            }
            liveFindings.computeIfAbsent(p.file().toAbsolutePath().normalize(), k -> new ArrayList<>())
                .add(new EditorDiagnostic(p.line(), 0, 0, severity(p.severity()), p.source(), p.message(),
                    "G4code " + p.source()));
        }
        for (CodeTab t : tabs) {
            applyFindings(t);
        }
    }

    private void applyFindings(CodeTab t) {
        if (t.getFile() == null) {
            return;
        }
        final Path p = t.getFile().toPath().toAbsolutePath().normalize();
        final List<EditorDiagnostic> d = new ArrayList<>(liveFindings.getOrDefault(p, List.of()));
        d.addAll(buildFindings.getOrDefault(p, List.of()));
        t.setDiagnostics(d);
    }

    private static EditorDiagnostic.Severity severity(Geometry.Severity s) {
        return switch (s) {
            case ERROR -> EditorDiagnostic.Severity.ERROR;
            case WARNING -> EditorDiagnostic.Severity.WARNING;
            default -> EditorDiagnostic.Severity.INFO;
        };
    }

    // ==================================================================
    // Code <-> 3D
    // ==================================================================

    /** The caret on a line that built something selects it in 3D. */
    private void caretMoved(CodeTab t, int dot) {
        if (syncing || last == null || t.getFile() == null) {
            return;
        }
        final int line = t.getEditorArea().getDocument().getDefaultRootElement().getElementIndex(dot) + 1;
        if (line == caretLine) {
            return;
        }
        caretLine = line;
        final List<PhysicalVolume> pvs = last.placementsAt(t.getFile().toPath(), line);
        final List<PhysicalVolume> live = new ArrayList<>();
        final Set<PhysicalVolume> placed = new java.util.HashSet<>(model.geometry.placements());
        for (PhysicalVolume pv : pvs) {
            if (placed.contains(pv)) {
                live.add(pv);
            }
        }
        if (!live.isEmpty()) {
            syncing = true;
            try {
                model.selectAll(live);
            } finally {
                syncing = false;
            }
        }
    }

    /** The right button on a volume, in 3D or in the Volumes tree. */
    private void volumeMenu(MouseEvent e, Component on, PhysicalVolume pv, LogicalVolume lv) {
        final JPopupMenu m = new JPopupMenu();
        final Object target = pv != null ? pv : lv;
        if (target != null && last != null) {
            final CppGeometry.Origin o = last.origin(target);
            final JMenuItem show = menuItem("Show in the code", null, () -> reveal(o.file(), o.line()));
            show.setEnabled(o != null);
            m.add(show);
            if (pv != null) {
                final LogicalVolume plv = pv.logical();
                final CppGeometry.Origin lo = last.origin(plv);
                final JMenuItem showLv = menuItem("Show its volume's line (" + plv.name() + ")", null,
                    () -> reveal(lo.file(), lo.line()));
                showLv.setEnabled(lo != null);
                m.add(showLv);
                if (plv.solid() != null) {
                    m.add(menuItem("Geant4 API: " + plv.solid().g4Class(), null, () -> {
                        left.setSelectedComponent(api);
                        api.showClass(plv.solid().g4Class());
                    }));
                }
            }
            m.addSeparator();
        }
        if (pv != null) {
            final LogicalVolume plv = pv.logical();
            m.add(menuItem("Properties (written into the code)", null, () -> {
                model.select(pv, false);
                rightTabs.setSelectedIndex(rightTabs.indexOfTab("Properties"));
            }));
            m.add(menuItem(plv.visible() ? "Hide it (SetVisAttributes)" : "Show it (SetVisAttributes)", null,
                () -> model.edit((plv.visible() ? "Hide " : "Show ") + plv.name(), () -> plv.setVisible(!plv.visible()))));
            m.add(menuItem(plv.wireframe() ? "Draw it solid" : "Draw it as wireframe", null,
                () -> model.edit("Wireframe " + plv.name(), () -> plv.setWireframe(!plv.wireframe()))));
            m.add(menuItem("Colour...", null, () -> {
                final Color now = new Color(plv.color(), true);
                final Color c = javax.swing.JColorChooser.showDialog(this, "Colour of " + plv.name(), now);
                if (c != null) {
                    model.edit("Colour " + plv.name(), () -> plv.setColor((now.getAlpha() << 24) | (c.getRGB() & 0xFFFFFF)));
                }
            }));
            final javax.swing.JMenu mat = new javax.swing.JMenu("Material");
            for (String name : List.of("G4_AIR", "G4_Galactic", "G4_Si", "G4_Pb", "G4_W", "G4_Fe", "G4_Cu", "G4_Al",
                "G4_WATER", "G4_PLASTIC_SC_VINYLTOLUENE", "G4_PbWO4", "G4_BGO", "G4_lAr", "G4_CONCRETE")) {
                mat.add(menuItem(name, null, () -> model.edit("Material of " + plv.name(), () -> plv.setMaterial(name))));
            }
            m.add(mat);
            m.add(menuItem(plv.sensitive() == null ? "Make it a sensitive detector (ConstructSDandField)"
                : "No longer sensitive (" + plv.sensitive() + ")", null, () -> model.edit("Sensitive " + plv.name(),
                    () -> plv.setSensitive(plv.sensitive() == null ? plv.name() + "SD" : null))));
            m.add(menuItem("Delete (takes it out of the code)", "DELETE", () -> {
                if (!model.selection().contains(pv)) {
                    model.select(pv, false);
                }
                deleteSelection();
            }));
            m.add(menuItem("New volumes go inside " + plv.name(), null, () -> {
                model.container = plv;
                say("Volumes drawn now go inside " + plv.name() + " (Esc on empty space: where they are drawn)");
            }));
            final G4EditorFrame editor = G4EditorFrame.window();
            final JMenuItem from = menuItem("Insert G4editor's selection here (C++)", null, () -> {
                final G4EditorFrame ed = G4EditorFrame.window();
                if (ed != null) {
                    model.select(pv, false);
                    insertDesign(ed.design(), ed.chosen(), ed.designTitle());
                }
            });
            from.setEnabled(editor != null);
            m.add(from);
            m.addSeparator();
            m.add(menuItem("Zoom to it", null, () -> {
                model.select(pv, false);
                vp.fitSelection(true);
            }));
            m.add(menuItem("Isolate it", null, () -> {
                model.select(pv, false);
                vp.isolate(true);
            }));
        }
        m.add(menuItem("Show all", null, () -> {
            vp.isolate(false);
            vp.fitAll(true);
        }));
        m.add(menuItem(vp.xray() ? "X-ray off" : "X-ray (see through)", null, () -> vp.setXray(!vp.xray())));
        if (target != null) {
            m.addSeparator();
            final String name = pv != null ? pv.name() : lv.name();
            m.add(menuItem("Copy the name", null, () -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(name), null)));
        }
        m.show(on, e.getX(), e.getY());
    }

    private void problemMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        final int i = problemList.locationToIndex(e.getPoint());
        if (i < 0) {
            return;
        }
        problemList.setSelectedIndex(i);
        final Problem p = problems.get(i);
        final JPopupMenu m = new JPopupMenu();
        final JMenuItem go = menuItem("Go to the line", null, () -> reveal(p.file(), Math.max(1, p.line())));
        go.setEnabled(p.file() != null);
        m.add(go);
        m.add(menuItem("Copy the message", null, () -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(p.toString()), null)));
        m.show(problemList, e.getX(), e.getY());
    }

    /** A volume picked in 3D or in the tree shows the line that placed it. */
    private void revealSelection() {
        if (last == null) {
            return;
        }
        Object o = model.primary();
        if (o == null) {
            o = model.focus();
        }
        if (o == null) {
            return;
        }
        final CppGeometry.Origin origin = last.origin(o);
        if (origin != null) {
            reveal(origin.file(), origin.line());
        }
    }

    /** One rewrite of the code: the text expected between two offsets, and what replaces it. */
    private record Rewrite(int start, int end, String expected, String text) {
    }

    /**
     * Placements moved or turned in 3D, written back into the code. A position
     * written once as G4ThreeVector(...) is rewritten where it stands (the
     * variable's initializer, for pos1 in B1); otherwise the position argument
     * of the G4PVPlacement is. A rotation replaces the rotation argument with
     * new G4RotationMatrix(G4RotationMatrix().rotateX(..).rotateY(..).rotateZ(..)),
     * and a G4Transform3D argument is written whole. A placement made in a loop,
     * or by a method called twice, is one text for many volumes: the code is
     * left alone and the drawing goes back to what it says.
     */
    private void writeBack() {
        if (project == null) {
            // no application, so no code to write into: the drawing is offered as one, in C++
            offerDrawingAsCode();
            return;
        }
        if (last == null) {
            return;
        }
        final Map<Path, List<Rewrite>> edits = new LinkedHashMap<>();
        final List<String> refused = new ArrayList<>();
        final Map<Integer, List<PhysicalVolume>> sharedChanged = new LinkedHashMap<>();
        int changed = 0;
        for (Map.Entry<PhysicalVolume, Vec3> e : drawnPositions.entrySet()) {
            final PhysicalVolume pv = e.getKey();
            final boolean moved = pv.position().distance(e.getValue()) > 1e-9;
            final Vec3 r0 = drawnRotations.get(pv);
            final boolean turned = r0 != null && pv.rotation().distance(r0) > 1e-12;
            if (!moved && !turned) {
                continue;
            }
            changed++;
            final CppGeometry.PlacementText pt = last.placementTexts().get(pv);
            final CppGeometry.PositionText pos = last.positions().get(pv);
            if (pt != null && pt.shared()) {
                sharedChanged.computeIfAbsent(pt.group(), k -> new ArrayList<>()).add(pv);
                continue;
            }
            if (pt != null && pt.transform()) {
                rewrite(edits, pt.file(), new Rewrite(pt.rotStart(), pt.rotEnd(), pt.rotText(),
                    transform3D(pv.rotation(), pv.position(), pv.reflected())));
                continue;
            }
            if (turned) {
                if (pt == null) {
                    refused.add(refusal(pv));
                    continue;
                }
                rewrite(edits, pt.file(), new Rewrite(pt.rotStart(), pt.rotEnd(), pt.rotText(), rotationMatrix(pv.rotation())));
            }
            if (moved) {
                if (pos != null) {
                    rewrite(edits, pos.file(), new Rewrite(pos.start(), pos.end(), pos.text(), threeVector(pv.position())));
                } else if (pt != null && pt.posText() != null) {
                    rewrite(edits, pt.file(), new Rewrite(pt.posStart(), pt.posEnd(), pt.posText(), threeVector(pv.position())));
                } else {
                    refused.add(refusal(pv));
                }
            }
        }
        for (List<PhysicalVolume> group : sharedChanged.values()) {
            writeShared(group, edits, refused);
        }
        final Map<Path, Map<Integer, StringBuilder>> inserts = new LinkedHashMap<>();
        final Map<Path, Set<String>> headers = new LinkedHashMap<>();
        final List<String> added = new ArrayList<>();
        changed += writeDrawn(edits, inserts, headers, refused, added);
        flush(inserts, headers, edits);
        if (changed == 0) {
            // another change made in 3D (a deletion, a volume dragged in the tree): the code stays the truth
            schedule();
            return;
        }
        String done = null;
        for (Map.Entry<Path, List<Rewrite>> e : edits.entrySet()) {
            final CodeTab t = open(e.getKey());
            final Document d = t.getEditorArea().getDocument();
            // last first, so that the earlier offsets hold; overlapping ones (never written by the reading) are skipped
            e.getValue().sort((a, b) -> b.start() - a.start());
            int floor = Integer.MAX_VALUE;
            for (Rewrite r : e.getValue()) {
                if (r.end() > floor) {
                    continue;
                }
                try {
                    if (r.end() <= d.getLength() && d.getText(r.start(), r.end() - r.start()).equals(r.expected())) {
                        d.remove(r.start(), r.end() - r.start());
                        d.insertString(r.start(), r.text(), null);
                        floor = r.start();
                        done = r.expected() + "  ->  " + r.text();
                    }
                } catch (BadLocationException ignored) {
                    // the text moved under us: the next reading redraws what the code says
                }
            }
        }
        if (!refused.isEmpty()) {
            status.setText("computed in the code, not rewritten: " + String.join(", ", refused)
                + "; change the code, the drawing follows it");
        } else if (!added.isEmpty()) {
            status.setText("written into the code: " + String.join(", ", added));
        } else if (done != null) {
            status.setText("written back: " + done);
        }
        if (!inserts.isEmpty()) {
            reparse.restart();   // even with Live 3D off: what was drawn is now the code's
        } else {
            schedule();
        }
    }

    /** The selected placements deleted, one copy of a loop as well: the write-back takes their code out. */
    private void deleteSelection() {
        final List<PhysicalVolume> sel = new ArrayList<>(model.selection());
        if (sel.isEmpty()) {
            return;
        }
        model.edit("Delete " + (sel.size() == 1 ? sel.get(0).name() : sel.size() + " volumes"), () -> {
            for (PhysicalVolume pv : sel) {
                model.geometry.unplace(pv);
            }
        });
    }

    /** A volume the code makes, as drawn now: a change made to it later is told by these. */
    private void noteDrawn(LogicalVolume lv) {
        drawnVolumes.add(lv);
        drawnLooks.put(lv, Look.of(lv));
        noteSolid(lv.solid());
    }

    /** A solid as drawn now, and the solids it is made of. */
    private void noteSolid(Solid s) {
        if (s == null || drawnParams.containsKey(s)) {
            return;
        }
        drawnParams.put(s, params(s));
        for (Solid op : s.operands()) {
            noteSolid(op);
        }
    }

    /** A solid's parameters, and its tables (planes, corners) as "#zplane" and the like. */
    private static Map<String, String> params(Solid s) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (Solid.Param p : s.params()) {
            out.put(p.key, p.expr());
        }
        for (Solid.Table t : s.tables()) {
            final StringBuilder b = new StringBuilder();
            for (String[] row : t.rows()) {
                b.append(String.join(",", row)).append(';');
            }
            out.put("#" + t.element, b.toString());
        }
        return out;
    }

    /**
     * Volumes drawn in 3D since the reading, written into the code where their mother is made, and the
     * characteristics changed in the properties: a solid's parameters in the arguments of its constructor,
     * the material in the G4LogicalVolume's argument, the colour, visibility and wireframe in its
     * SetVisAttributes (or a SetVisAttributes written after the volume is made).
     */
    private int writeDrawn(Map<Path, List<Rewrite>> edits, Map<Path, Map<Integer, StringBuilder>> inserts,
                           Map<Path, Set<String>> headers, List<String> refused, List<String> added) {
        final CppGeometry.Links links = last.links();
        int changed = 0;
        final Cuts cuts = new Cuts();
        changed += writeDeleted(cuts, edits, refused, added);
        final Map<String, String> sensitiveNow = new LinkedHashMap<>();
        final List<LogicalVolume> sensitiveGone = new ArrayList<>();
        // new placements in volumes the code makes, by mother (a new volume's own daughters come with it)
        final Map<LogicalVolume, List<PhysicalVolume>> byMother = new LinkedHashMap<>();
        for (PhysicalVolume pv : model.geometry.placements()) {
            if (!drawnPositions.containsKey(pv) && pv.mother() != null && drawnVolumes.contains(pv.mother())) {
                byMother.computeIfAbsent(pv.mother(), k -> new ArrayList<>()).add(pv);
            }
        }
        for (Map.Entry<LogicalVolume, List<PhysicalVolume>> e : byMother.entrySet()) {
            changed++;
            final CppInsert.Code code = insertCode(model.geometry, e.getValue(), e.getKey(),
                (e.getValue().size() == 1 ? e.getValue().get(0).name() : e.getValue().size() + " volumes") + ", drawn in G4code",
                inserts, headers);
            if (code == null) {
                refused.add(e.getValue().get(0).name() + " (no place found in the code for the daughters of "
                    + e.getKey().name() + ")");
                continue;
            }
            added.addAll(code.volumes().isEmpty() ? e.getValue().stream().map(PhysicalVolume::name).toList() : code.volumes());
            sensitiveNow.putAll(code.sensitive());
            // written: the drawing holds it until the code is read again
            for (PhysicalVolume pv : e.getValue()) {
                accept(pv);
            }
        }
        // characteristics of the volumes the code makes
        final Set<LogicalVolume> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(model.geometry.volumes());
        for (LogicalVolume lv : new ArrayList<>(drawnVolumes)) {
            if (!present.contains(lv)) {
                continue;
            }
            final Look was = drawnLooks.get(lv), now = Look.of(lv);
            if (was != null && !was.material().equals(now.material())) {
                changed++;
                final CppGeometry.CodeText t = links.materials().get(lv);
                final String expr = CppInsert.materialExpr(now.material(), links.codeMaterials(), model.geometry);
                if (t != null && expr != null) {
                    rewrite(edits, t.file(), new Rewrite(t.start(), t.end(), t.text(), expr));
                    added.add(lv.name() + " in " + now.material());
                } else {
                    refused.add(lv.name() + "'s material (" + (expr == null ? now.material() + " is not NIST's nor the code's"
                        : "given in a loop or by a function") + ")");
                }
            }
            if (was != null && (was.color() != now.color() || was.visible() != now.visible()
                || was.wireframe() != now.wireframe())) {
                changed++;
                final String vis = CppInsert.vis(lv);
                final CppGeometry.CodeText t = links.vis().get(lv);
                final CppGeometry.InsertSite site = links.sites().get(lv);
                if (t != null) {
                    rewrite(edits, t.file(), new Rewrite(t.start(), t.end(), t.text(), vis));
                    added.add(lv.name() + "'s look");
                } else if (site != null) {
                    // drawn as the material says until now: told at the end of the function that makes it
                    final String target = site.topLevel() ? site.variable() : CppInsert.storeLookup(lv);
                    insertAt(inserts, site, site.indent() + target + "->SetVisAttributes(" + vis + ");\n");
                    if (!site.topLevel()) {
                        headers.computeIfAbsent(site.file(), k -> new LinkedHashSet<>()).add("G4LogicalVolumeStore.hh");
                    }
                    headers.computeIfAbsent(site.file(), k -> new LinkedHashSet<>()).add("G4VisAttributes.hh");
                    added.add(lv.name() + "'s look");
                } else {
                    refused.add(lv.name() + "'s look");
                }
            }
            if (was != null && !java.util.Objects.equals(was.sensitive(), now.sensitive())) {
                changed++;
                if (was.sensitive() != null) {
                    sensitiveGone.add(lv);
                }
                if (now.sensitive() != null) {
                    sensitiveNow.put(lv.name(), now.sensitive());
                }
            }
            drawnLooks.put(lv, now);
        }
        // the solids, a boolean's operands as well
        final Set<Solid> solids = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        solids.addAll(model.geometry.solids());
        for (Solid solid : new ArrayList<>(drawnParams.keySet())) {
            if (solids.contains(solid)) {
                changed += writeSolid(solid, links, edits, refused, added);
            }
        }
        changed += writeSensitive(sensitiveNow, sensitiveGone, cuts, inserts, headers, refused, added);
        cuts.flush(edits);
        return changed;
    }

    /**
     * A solid's changed parameters written where the code gives them: each in its argument of the constructor
     * (the others keep the code's variables), a boolean's second solid in its rotation and position arguments,
     * planes and corners in the {...} of their arrays (and the count argument); else the constructor whole.
     */
    private int writeSolid(Solid solid, CppGeometry.Links links, Map<Path, List<Rewrite>> edits, List<String> refused,
                           List<String> added) {
        final Map<String, String> before = drawnParams.get(solid), after = params(solid);
        final List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> p : after.entrySet()) {
            if (!p.getValue().equals(before.get(p.getKey()))) {
                keys.add(p.getKey());
            }
        }
        if (keys.isEmpty()) {
            return 0;
        }
        drawnParams.put(solid, after);   // a solid shared by volumes is written once
        final List<String> left = new ArrayList<>(keys);
        // a boolean's second solid: its placement in the constructor
        final CppGeometry.BooleanText bt = links.booleans().get(solid);
        final List<String> placing = List.of("x", "y", "z", "rx", "ry", "rz");
        if (bt != null && keys.stream().anyMatch(placing::contains)) {
            final Vec3 rot = new Vec3(solid.v("rx"), solid.v("ry"), solid.v("rz"));
            final Vec3 pos = new Vec3(solid.v("x"), solid.v("y"), solid.v("z"));
            if (bt.rot() != null) {
                rewrite(edits, bt.file(), new Rewrite(bt.rot().start(), bt.rot().end(), bt.rot().text(), rotationMatrix(rot)));
                rewrite(edits, bt.file(), new Rewrite(bt.pos().start(), bt.pos().end(), bt.pos().text(), threeVector(pos)));
            } else if (bt.transform() != null) {
                rewrite(edits, bt.file(), new Rewrite(bt.transform().start(), bt.transform().end(), bt.transform().text(),
                    transform3D(rot, pos, false)));
            } else {
                rewrite(edits, bt.file(), new Rewrite(bt.close(), bt.close() + 1, ")", ", " + rotationMatrix(rot) + ", "
                    + threeVector(pos) + ")"));
            }
            left.removeAll(placing);
        }
        // planes and corners: the arrays they were read from
        final CppGeometry.TableText tt = links.tables().get(solid);
        if (tt != null && left.contains("#" + tt.table())) {
            if (writeTable(solid, tt, edits, refused)) {
                left.remove("#" + tt.table());
            } else {
                return 1;
            }
        }
        final Map<String, CppGeometry.ParamText> args = links.params().get(solid);
        if (!left.isEmpty() && args != null && args.keySet().containsAll(left)) {
            // only the arguments changed: the code's variables in the others stay
            for (String k : left) {
                final CppGeometry.ParamText a = args.get(k);
                final String expr = solid.param(k).expr();
                rewrite(edits, a.text().file(), new Rewrite(a.text().start(), a.text().end(), a.text().text(),
                    a.kind() == 'N' ? integer(expr) : CppInsert.argument(expr, a.factor())));
            }
            left.clear();
        }
        if (!left.isEmpty()) {
            final CppGeometry.CodeText whole = links.solids().get(solid);
            final String now = whole == null ? null : CppInsert.constructor(solid, whole.text(), whole.name());
            if (now == null) {
                refused.add(solid.name() + "'s " + String.join(", ", left));
                return 1;
            }
            rewrite(edits, whole.file(), new Rewrite(whole.start(), whole.end(), whole.text(), now));
        }
        added.add(solid.name() + " " + String.join(", ", keys).replace("#", ""));
        return 1;
    }

    /** A number of sides, of planes: an integer in the code. */
    private static String integer(String expr) {
        try {
            final double v = Double.parseDouble(expr.strip());
            if (v == Math.rint(v)) {
                return String.valueOf((long) v);
            }
        } catch (NumberFormatException ignored) {
            // an expression: as it is
        }
        return CppInsert.cpp(expr);
    }

    /**
     * A table written back in the arrays it was read from, {a, b, c} each; the count argument and an array's
     * written size follow when the rows are more or fewer.
     */
    private boolean writeTable(Solid solid, CppGeometry.TableText tt, Map<Path, List<Rewrite>> edits, List<String> refused) {
        final Solid.Table t = solid.table(tt.table());
        final int rows = t.size();
        final boolean resized = rows != tt.arrays().get(0).text().split(",", -1).length;
        for (int j = 0; j < tt.arrays().size(); j++) {
            final CppGeometry.CodeText a = tt.arrays().get(j);
            final StringBuilder b = new StringBuilder("{");
            for (int r = 0; r < rows; r++) {
                b.append(r > 0 ? ", " : "").append(CppInsert.cpp(t.rows().get(r)[tt.columnOf()[j]]));
            }
            b.append('}');
            if (resized) {
                // G4double z[4] = {...}: the written size follows; a size named by a variable cannot
                final String text = readTexts.get(a.file().toAbsolutePath().normalize());
                final Matcher m = text == null ? null : Pattern.compile("\\[\\s*([^\\]]*?)\\s*\\]\\s*=?\\s*$")
                    .matcher(text.substring(Math.max(0, a.start() - 80), a.start()));
                if (m != null && m.find() && !m.group(1).isEmpty()) {
                    if (!m.group(1).matches("\\d+")) {
                        refused.add(solid.name() + "'s " + tt.table() + " (its arrays' size is " + m.group(1) + ")");
                        return false;
                    }
                    final int at = Math.max(0, a.start() - 80) + m.start(1);
                    rewrite(edits, a.file(), new Rewrite(at, at + m.group(1).length(), m.group(1), String.valueOf(rows)));
                }
            }
            rewrite(edits, a.file(), new Rewrite(a.start(), a.end(), a.text(), b.toString()));
        }
        if (resized && tt.count() != null) {
            rewrite(edits, tt.count().file(), new Rewrite(tt.count().start(), tt.count().end(), tt.count().text(),
                String.valueOf(rows)));
        } else if (resized) {
            refused.add(solid.name() + "'s number of " + tt.table() + "s");
            return false;
        }
        return true;
    }

    /**
     * Volumes made sensitive, or no longer, written in ConstructSDandField(): the SetSensitiveDetector lines
     * of those no longer sensitive taken out; for the others a detector per name and SetSensitiveDetector,
     * in the class's ConstructSDandField() -- added to the class (declared, defined after Construct()) when
     * it has none.
     */
    private int writeSensitive(Map<String, String> now, List<LogicalVolume> gone, Cuts cuts,
                               Map<Path, Map<Integer, StringBuilder>> inserts, Map<Path, Set<String>> headers,
                               List<String> refused, List<String> added) {
        final CppGeometry.Links links = last.links();
        for (LogicalVolume lv : gone) {
            final List<CppGeometry.CodeText> texts = links.sensitiveTexts().get(lv);
            if (texts == null || texts.isEmpty()) {
                refused.add(lv.name() + "'s detector (given in a loop or by a function)");
                continue;
            }
            texts.forEach(cuts::add);
            for (CppGeometry.CodeText t : texts) {
                dropDetector(t, cuts);
            }
            if (!now.containsKey(lv.name())) {
                added.add(lv.name() + " no longer sensitive");
            }
        }
        if (now.isEmpty()) {
            return 0;
        }
        final Set<String> taken = new java.util.HashSet<>();
        for (String text : readTexts.values()) {
            taken.addAll(CppInsert.codeIdentifiers(text));
        }
        final String code = CppInsert.sensitiveCode(now, taken);
        final CppGeometry.InsertSite sd = links.sensitive();
        if (sd != null) {
            insertAt(inserts, sd, CppInsert.indent(code, sd.indent()));
            headers.computeIfAbsent(sd.file(), k -> new LinkedHashSet<>()).addAll(CppInsert.headers(code));
        } else {
            // the class has none: one of its own, defined after Construct(), declared after it in the class
            final CppGeometry.InsertSite c = links.construct();
            final String cc = c == null ? null : readTexts.get(c.file().toAbsolutePath().normalize());
            final Object[] def = cc == null ? null : CppInsert.sdDefinition(cc, c.bodyStart(), c.bodyEnd(), code);
            Path header = null;
            Object[] decl = null;
            for (Map.Entry<Path, String> e : readTexts.entrySet()) {
                decl = links.detectorClass() == null ? null : CppInsert.sdDeclaration(e.getValue(), links.detectorClass());
                if (decl != null) {
                    header = e.getKey();
                    break;
                }
            }
            if (def == null || decl == null) {
                refused.add("the detectors of " + String.join(", ", now.keySet()) + " (no ConstructSDandField() in "
                    + links.detectorClass() + ", and none could be added)");
                return 1;
            }
            insertRaw(inserts, c.file(), (int) def[0], (String) def[1]);
            insertRaw(inserts, header, (int) decl[0], (String) decl[1]);
            headers.computeIfAbsent(c.file(), k -> new LinkedHashSet<>()).addAll(CppInsert.headers(code));
            added.add(links.detectorClass() + "::ConstructSDandField()");
        }
        added.add(String.join(", ", now.keySet()) + " sensitive");
        return 1;
    }

    private static final Pattern SD_ARG = Pattern.compile("SetSensitiveDetector\\s*\\([^,]+,\\s*([A-Za-z_]\\w*)");

    /**
     * The detector a SetSensitiveDetector line gave, when it is one G4code wrote (found by name in G4SDManager,
     * else made) and nothing else uses it: its lines go with the volume's sensitivity.
     */
    private void dropDetector(CppGeometry.CodeText set, Cuts cuts) {
        final Matcher a = SD_ARG.matcher(set.text());
        final String text = readTexts.get(set.file().toAbsolutePath().normalize());
        if (!a.find() || text == null) {
            return;
        }
        final String v = Pattern.quote(a.group(1));
        final Matcher block = Pattern.compile("(?m)^[ \\t]*//[^\\n]*\\n[ \\t]*auto\\* " + v + " = G4SDManager::GetSDMpointer"
            + "\\(\\)->FindSensitiveDetector\\([^\\n]*\\n[ \\t]*if \\(!" + v + "\\) \\{\\n(?:[^\\n]*\\n){1,6}?[ \\t]*\\}[ \\t]*\\n")
            .matcher(text);
        if (!block.find()) {
            return;
        }
        final CppGeometry.CodeText made = new CppGeometry.CodeText(set.file(), block.start(), block.end(),
            text.substring(block.start(), block.end()), null);
        if (cuts.uses(set.file(), a.group(1), made) == 0) {
            cuts.add(made);
        }
    }

    private static void insertRaw(Map<Path, Map<Integer, StringBuilder>> inserts, Path file, int offset, String text) {
        inserts.computeIfAbsent(file, k -> new java.util.TreeMap<>()).computeIfAbsent(offset, k -> new StringBuilder())
            .append(text);
    }

    // ------------------------------------------------------------------
    // Volumes deleted in 3D
    // ------------------------------------------------------------------

    /**
     * Placements deleted in 3D taken out of the code: the statement that made them, when the variable it
     * fills is used nowhere else; one copy of a loop, by its copy number: if (copy != 3) before the
     * statement, or (copy != 3) ? new G4PVPlacement(...) : nullptr when its result is kept. A volume no
     * longer placed goes too (its statement, its SetVisAttributes, what it holds, its solid), when nothing
     * else names its variable.
     */
    private int writeDeleted(Cuts cuts, Map<Path, List<Rewrite>> edits, List<String> refused, List<String> added) {
        final CppGeometry.Statements st = last.links().statements();
        final Set<PhysicalVolume> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(model.geometry.placements());
        // the statements of the deleted placements, each with all the placements it makes
        final Map<String, List<PhysicalVolume>> byStatement = new LinkedHashMap<>();
        final Map<String, List<PhysicalVolume>> members = new LinkedHashMap<>();
        int changed = 0;
        for (PhysicalVolume pv : drawnPositions.keySet()) {
            final CppGeometry.CodeText t = st.made().get(pv);
            if (t != null) {
                members.computeIfAbsent(t.file() + "@" + t.start(), k -> new ArrayList<>()).add(pv);
            }
        }
        for (PhysicalVolume pv : new ArrayList<>(drawnPositions.keySet())) {
            if (present.contains(pv)) {
                continue;
            }
            changed++;
            drawnPositions.remove(pv);
            drawnRotations.remove(pv);
            final CppGeometry.CodeText t = st.made().get(pv);
            if (t == null) {
                refused.add(pv.name() + " (placed by code the reading cannot take out)");
                continue;
            }
            byStatement.computeIfAbsent(t.file() + "@" + t.start(), k -> new ArrayList<>()).add(pv);
        }
        final Set<LogicalVolume> unplaced = new LinkedHashSet<>();
        for (Map.Entry<String, List<PhysicalVolume>> e : byStatement.entrySet()) {
            final List<PhysicalVolume> gone = e.getValue(), all = members.get(e.getKey());
            final CppGeometry.CodeText t = st.made().get(gone.get(0));
            final String names = gone.size() == 1 ? gone.get(0).name() : gone.size() + " copies of " + gone.get(0).name();
            if (gone.size() == all.size()) {
                if (t.name() != null && cuts.uses(t.file(), t.name(), t) > 0) {
                    refused.add(names + " (its variable " + t.name() + " is used elsewhere)");
                    continue;
                }
                cuts.add(t);
                gone.forEach(pv -> unplaced.add(pv.logical()));
                added.add(names + " taken out");
                continue;
            }
            // some copies of a loop: told apart by their copy numbers
            if (!excludeCopies(gone, all, t, edits, refused)) {
                continue;
            }
            added.add(names + " left out of the loop");
        }
        // volumes no longer placed anywhere, and what they hold, when nothing else names them
        for (LogicalVolume lv : unplaced) {
            dropVolume(lv, present, cuts, added);
        }
        return changed;
    }

    private static final Pattern IF_COPIES = Pattern.compile("if\\s*\\(([^()]*)\\)\\s*$");
    private static final Pattern TERNARY_COPIES = Pattern.compile("\\(([^()]*)\\)\\s*\\?\\s*$");

    /** Copies of a loop left out by their copy numbers; an exclusion written before is extended. */
    private boolean excludeCopies(List<PhysicalVolume> gone, List<PhysicalVolume> all, CppGeometry.CodeText t,
                                  Map<Path, List<Rewrite>> edits, List<String> refused) {
        final CppGeometry.PlacementText pt = last.placementTexts().get(gone.get(0));
        final String copy = pt == null ? null : pt.copyText();
        if (copy == null) {
            refused.add(gone.get(0).name() + " (a copy of a loop with no copy number to tell it by)");
            return false;
        }
        final Set<Integer> out = new java.util.TreeSet<>();
        gone.forEach(pv -> out.add(pv.copyNumber()));
        for (PhysicalVolume pv : all) {
            if (!gone.contains(pv) && out.contains(pv.copyNumber())) {
                refused.add(gone.get(0).name() + " (its copies share the copy number " + pv.copyNumber() + ")");
                return false;
            }
        }
        final String text = readTexts.get(t.file().toAbsolutePath().normalize());
        final CppGeometry.CodeText call = last.links().statements().calls().get(gone.get(0));
        if (text == null || call == null) {
            refused.add(gone.get(0).name());
            return false;
        }
        final boolean alone = call.start() == t.start();   // new G4PVPlacement(...); its result not kept
        final int at = alone ? t.start() : call.start();
        final int lineStart = text.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
        final Matcher m = (alone ? IF_COPIES : TERNARY_COPIES).matcher(text.substring(lineStart, at));
        final String term = copy.contains(" ") ? "(" + copy + ")" : copy;
        if (m.find()) {
            // an exclusion of earlier deletes: its copies kept out, these added
            final List<String> terms = new ArrayList<>(List.of(m.group(1).split("\\s*&&\\s*")));
            for (String c : terms) {
                if (!c.strip().matches(Pattern.quote(term) + "\\s*!=\\s*-?\\d+")) {
                    refused.add(gone.get(0).name() + " (its statement has a condition of its own)");
                    return false;
                }
            }
            for (int c : out) {
                terms.add(term + " != " + c);
            }
            final int cs = lineStart + m.start(1);
            rewrite(edits, t.file(), new Rewrite(cs, cs + m.group(1).length(), m.group(1), String.join(" && ", terms)));
            return true;
        }
        final List<String> terms = new ArrayList<>();
        for (int c : out) {
            terms.add(term + " != " + c);
        }
        final String cond = String.join(" && ", terms);
        if (alone) {
            final int open = text.indexOf('(', t.start());
            final String head = text.substring(t.start(), open + 1);
            rewrite(edits, t.file(), new Rewrite(t.start(), open + 1, head, "if (" + cond + ") " + head));
        } else {
            rewrite(edits, t.file(), new Rewrite(call.start(), call.end(), call.text(), "(" + cond + ") ? " + call.text()
                + " : nullptr"));
        }
        return true;
    }

    /**
     * A volume no longer placed taken out with what it holds: its statement, its SetVisAttributes and
     * SetSensitiveDetector, its daughters' placements; then its solid. Each only when its variable is named
     * nowhere else (a member like fScoringVolume keeps it).
     */
    private void dropVolume(LogicalVolume lv, Set<PhysicalVolume> present, Cuts cuts, List<String> added) {
        for (PhysicalVolume pv : present) {
            if (pv.logical() == lv) {
                return;   // still placed elsewhere
            }
        }
        final CppGeometry.Links links = last.links();
        final CppGeometry.CodeText made = links.statements().made().get(lv);
        if (made == null || made.name() == null) {
            return;
        }
        final List<CppGeometry.CodeText> with = new ArrayList<>(List.of(made));
        with.addAll(links.statements().vis().getOrDefault(lv, List.of()));
        with.addAll(links.sensitiveTexts().getOrDefault(lv, List.of()));
        final List<PhysicalVolume> held = new ArrayList<>();
        for (PhysicalVolume d : present) {
            if (d.mother() == lv) {
                final CppGeometry.CodeText t = links.statements().made().get(d);
                if (t == null) {
                    return;   // a daughter placed by code that cannot be taken out: the volume stays
                }
                if (!with.contains(t)) {
                    with.add(t);
                }
                held.add(d);
            }
        }
        if (cuts.uses(made.file(), made.name(), with.toArray(new CppGeometry.CodeText[0])) > 0) {
            return;
        }
        with.forEach(cuts::add);
        added.add("its volume " + lv.name());
        final Set<PhysicalVolume> still = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        still.addAll(present);
        still.removeAll(held);
        for (PhysicalVolume d : held) {
            dropVolume(d.logical(), still, cuts, added);
        }
        dropSolid(lv.solid(), lv, cuts);
    }

    private void dropSolid(Solid s, LogicalVolume leaving, Cuts cuts) {
        if (s == null) {
            return;
        }
        for (LogicalVolume user : model.geometry.users(s)) {
            if (user != leaving && !cuts.dropped(user)) {
                return;
            }
        }
        final CppGeometry.CodeText made = last.links().statements().made().get(s);
        if (made == null || made.name() == null || cuts.uses(made.file(), made.name(), made) > 0) {
            return;
        }
        cuts.add(made);
        for (Solid op : s.operands()) {
            dropSolid(op, leaving, cuts);
        }
    }

    /**
     * Statements to take out, each with its whole lines when it has them to itself (its indentation, its
     * trailing comment, its line break), and the uses of a variable outside of them.
     */
    private final class Cuts {
        private final Map<Path, List<int[]>> spans = new LinkedHashMap<>();
        private final List<CppGeometry.CodeText> taken = new ArrayList<>();
        private final Map<Path, String> blanked = new HashMap<>();

        void add(CppGeometry.CodeText t) {
            if (taken.contains(t)) {
                return;
            }
            taken.add(t);
            final String text = readTexts.get(t.file().toAbsolutePath().normalize());
            if (text != null) {
                spans.computeIfAbsent(t.file(), k -> new ArrayList<>()).add(lines(text, t.start(), t.end()));
            }
        }

        /** A volume whose statement is taken out. */
        boolean dropped(LogicalVolume lv) {
            final CppGeometry.CodeText t = last.links().statements().made().get(lv);
            return t != null && taken.contains(t);
        }

        /** How often a variable is named in the code, comments and strings left out, outside these statements and those taken. */
        int uses(Path file, String var, CppGeometry.CodeText... also) {
            final Pattern word = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(var) + "(?![A-Za-z0-9_])");
            int n = 0;
            for (Map.Entry<Path, String> e : readTexts.entrySet()) {
                final String code = blanked.computeIfAbsent(e.getKey(), k -> blank(e.getValue()));
                final Matcher m = word.matcher(code);
                while (m.find()) {
                    if (!inside(e.getKey(), m.start(), also)) {
                        n++;
                    }
                }
            }
            return n;
        }

        private boolean inside(Path file, int at, CppGeometry.CodeText... also) {
            for (CppGeometry.CodeText t : also) {
                if (t.file().toAbsolutePath().normalize().equals(file) && at >= t.start() && at < t.end()) {
                    return true;
                }
            }
            for (CppGeometry.CodeText t : taken) {
                if (t.file().toAbsolutePath().normalize().equals(file) && at >= t.start() && at < t.end()) {
                    return true;
                }
            }
            return false;
        }

        void flush(Map<Path, List<Rewrite>> edits) {
            for (Map.Entry<Path, List<int[]>> e : spans.entrySet()) {
                final String text = readTexts.get(e.getKey().toAbsolutePath().normalize());
                final List<int[]> list = new ArrayList<>(e.getValue());
                list.sort((a, b) -> a[0] - b[0]);
                int[] cur = null;
                final List<int[]> merged = new ArrayList<>();
                for (int[] sp : list) {
                    if (cur != null && (sp[0] <= cur[1] || text.substring(cur[1], sp[0]).isBlank())) {
                        cur[1] = Math.max(cur[1], sp[1]);
                    } else {
                        cur = sp.clone();
                        merged.add(cur);
                    }
                }
                for (int[] sp : merged) {
                    // a blank line before and after: one of them goes with the statements
                    final boolean blankBefore = sp[0] >= 2 && text.charAt(sp[0] - 1) == '\n'
                        && text.substring(text.lastIndexOf('\n', sp[0] - 2) + 1, sp[0] - 1).isBlank();
                    final int eol = text.indexOf('\n', sp[1]);
                    if (blankBefore && sp[1] > 0 && text.charAt(sp[1] - 1) == '\n' && eol >= 0
                        && text.substring(sp[1], eol).isBlank()) {
                        sp[1] = eol + 1;
                    }
                    rewrite(edits, e.getKey(), new Rewrite(sp[0], sp[1], text.substring(sp[0], sp[1]), ""));
                }
            }
        }
    }

    /** A statement's span, grown to its whole lines when it has them to itself. */
    static int[] lines(String text, int start, int end) {
        int a = start;
        while (a > 0 && (text.charAt(a - 1) == ' ' || text.charAt(a - 1) == '\t')) {
            a--;
        }
        final boolean ownStart = a == 0 || text.charAt(a - 1) == '\n';
        int b = end;
        while (b < text.length() && (text.charAt(b) == ' ' || text.charAt(b) == '\t')) {
            b++;
        }
        if (text.startsWith("//", b)) {
            final int eol = text.indexOf('\n', b);
            b = eol < 0 ? text.length() : eol;
        }
        if (b < text.length() && text.charAt(b) == '\r') {
            b++;
        }
        final boolean ownEnd = b >= text.length() || text.charAt(b) == '\n';
        if (ownStart && ownEnd) {
            return new int[] {a, Math.min(text.length(), b + 1)};
        }
        return new int[] {start, end};   // it shares its line: the statement alone goes
    }

    /** Code with its comments, strings and characters turned to spaces, every offset kept. */
    static String blank(String text) {
        final char[] c = text.toCharArray();
        int i = 0;
        while (i < c.length) {
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    c[i] = c[i] == '\n' ? '\n' : ' ';
                    i++;
                }
                for (int k = 0; k < 2 && i < c.length; k++) {
                    c[i++] = ' ';
                }
            } else if (c[i] == '"' || c[i] == '\'') {
                final char q = c[i];
                c[i++] = ' ';
                while (i < c.length && c[i] != q && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < c.length) {
                        c[i++] = ' ';
                    }
                    c[i++] = ' ';
                }
                if (i < c.length && c[i] == q) {
                    c[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(c);
    }

    /** A placement written into the code: no longer new, until the code is read again. */
    private void accept(PhysicalVolume pv) {
        drawnPositions.put(pv, pv.position());
        drawnRotations.put(pv, pv.rotation());
        noteDrawn(pv.logical());
        for (PhysicalVolume d : model.geometry.placements()) {
            if (d.mother() == pv.logical() && !drawnPositions.containsKey(d)) {
                accept(d);
            }
        }
    }

    /**
     * The code placing volumes of a design (this drawing's, or G4editor's) in a volume the code makes,
     * queued at the end of the function that makes that volume: its variable when it is in reach there,
     * else Geant4's store of logical volumes by name. Null when the code gives no such place.
     */
    private CppInsert.Code insertCode(Geometry design, List<PhysicalVolume> pvs, LogicalVolume mother, String comment,
                                      Map<Path, Map<Integer, StringBuilder>> inserts, Map<Path, Set<String>> headers) {
        final CppGeometry.Links links = last.links();
        CppGeometry.InsertSite site = links.sites().get(mother);
        String motherExpr;
        if (site != null && site.topLevel()) {
            motherExpr = site.variable();
        } else {
            if (site == null && model.geometry.world() != null) {
                site = links.sites().get(model.geometry.world());
            }
            if (site == null) {
                site = links.construct();
            }
            if (site == null) {
                return null;
            }
            motherExpr = CppInsert.storeLookup(mother);
        }
        final CppGeometry.InsertSite at = site;
        final Set<String> taken = new java.util.HashSet<>();
        for (String text : readTexts.values()) {
            taken.addAll(CppInsert.codeIdentifiers(text));
        }
        // volumes the code already makes are reached, not written again
        final Function<LogicalVolume, String> known = design != model.geometry ? lv -> null : lv -> {
            if (!drawnVolumes.contains(lv)) {
                return null;
            }
            final CppGeometry.InsertSite s = links.sites().get(lv);
            return s != null && s.topLevel() && s.file().equals(at.file()) && s.bodyStart() == at.bodyStart()
                ? s.variable() : CppInsert.storeLookup(lv);
        };
        final CppInsert.Code code = new CppInsert(design, taken, links.codeMaterials(), known)
            .place(pvs, motherExpr, at.indent(), comment);
        insertAt(inserts, at, code.text() + "\n");   // a blank line after, before what follows
        headers.computeIfAbsent(at.file(), k -> new LinkedHashSet<>()).addAll(code.headers());
        if (code.text().contains("G4LogicalVolumeStore::")) {
            headers.get(at.file()).add("G4LogicalVolumeStore.hh");
        }
        return code;
    }

    private static void insertAt(Map<Path, Map<Integer, StringBuilder>> inserts, CppGeometry.InsertSite at, String text) {
        final StringBuilder b = inserts.computeIfAbsent(at.file(), k -> new java.util.TreeMap<>())
            .computeIfAbsent(at.offset(), k -> new StringBuilder(at.midLine() ? "\n" : ""));
        b.append(text);
    }

    /**
     * The queued code as rewrites: each written before the line it goes in front of (that line, as read,
     * must still be there), and the #include lines its classes need after the file's last one.
     */
    private void flush(Map<Path, Map<Integer, StringBuilder>> inserts, Map<Path, Set<String>> headers,
                       Map<Path, List<Rewrite>> edits) {
        for (Map.Entry<Path, Map<Integer, StringBuilder>> f : inserts.entrySet()) {
            final String text = readTexts.get(f.getKey().toAbsolutePath().normalize());
            if (text == null) {
                continue;
            }
            for (Map.Entry<Integer, StringBuilder> e : f.getValue().entrySet()) {
                rewrite(edits, f.getKey(), before(text, e.getKey(), e.getValue().toString()));
            }
            final Object[] inc = CppInsert.includes(text, headers.getOrDefault(f.getKey(), Set.of()));
            if (inc != null) {
                rewrite(edits, f.getKey(), before(text, (int) inc[0], (String) inc[1]));
            }
        }
    }

    private static Rewrite before(String text, int at, String insert) {
        final int eol = text.indexOf('\n', at);
        final String line = text.substring(at, eol < 0 ? text.length() : eol);
        return new Rewrite(at, at + line.length(), line, insert + line);
    }

    /**
     * Volumes of a G4editor design written into the code, all they hold with them, in the volume selected
     * here (else the world): the volumes, their solids and materials, colours, rotations, copies.
     */
    void insertDesign(Geometry design, List<PhysicalVolume> pvs, String from) {
        if (last == null || project == null || model.geometry.world() == null) {
            say("Open a Geant4 application first: the volumes are written into its DetectorConstruction");
            return;
        }
        final LogicalVolume mother = model.primary() != null ? model.primary().logical() : model.geometry.world();
        final Map<Path, List<Rewrite>> edits = new LinkedHashMap<>();
        final Map<Path, Map<Integer, StringBuilder>> inserts = new LinkedHashMap<>();
        final Map<Path, Set<String>> headers = new LinkedHashMap<>();
        final CppInsert.Code code = insertCode(design, pvs, mother, (pvs.size() == 1 ? pvs.get(0).name() : pvs.size()
            + " volumes") + " from G4editor (" + from + ")", inserts, headers);
        if (code == null) {
            say("No place found in the code for the daughters of " + mother.name());
            return;
        }
        final List<String> refused = new ArrayList<>(), notes = new ArrayList<>();
        final Cuts cuts = new Cuts();
        writeSensitive(code.sensitive(), List.of(), cuts, inserts, headers, refused, notes);
        if (!refused.isEmpty()) {
            say("Not written: " + String.join(", ", refused));
        }
        flush(inserts, headers, edits);
        int written = 0;
        for (Map.Entry<Path, List<Rewrite>> e : edits.entrySet()) {
            final CodeTab t = open(e.getKey());
            final Document d = t.getEditorArea().getDocument();
            e.getValue().sort((a, b) -> b.start() - a.start());
            for (Rewrite r : e.getValue()) {
                try {
                    if (r.end() <= d.getLength() && d.getText(r.start(), r.end() - r.start()).equals(r.expected())) {
                        d.remove(r.start(), r.end() - r.start());
                        d.insertString(r.start(), r.text(), null);
                        written++;
                    }
                } catch (BadLocationException ignored) {
                    // the code changed since it was read: nothing is written
                }
            }
        }
        if (written == 0) {
            say("The code changed since it was read: draw it again (F6), then insert");
            return;
        }
        status.setText("written into the code, in " + mother.name() + ": " + String.join(", ", code.volumes()));
        say("Inserted from G4editor into " + mother.name() + ": " + String.join(", ", code.volumes()));
        toFront();
        reparse.restart();
    }

    /** The open G4code window, if any. */
    static G4CodeFrame window() {
        return shared != null && shared.isDisplayable() ? shared : null;
    }

    /**
     * Copies made by one placement run in a loop. All of them shifted alike: the loop's position is
     * shifted, (pos) + G4ThreeVector(d), or G4Translate3D(d) * (transform). Some of them moved or turned:
     * an exception is written for each by its copy number, (icrys == 5 ? G4ThreeVector(...) : pos), the
     * exceptions of earlier moves kept and updated. Copies that share their copy number cannot be told apart.
     */
    private void writeShared(List<PhysicalVolume> changed, Map<Path, List<Rewrite>> edits, List<String> refused) {
        final CppGeometry.PlacementText pt = last.placementTexts().get(changed.get(0));
        final List<PhysicalVolume> members = new ArrayList<>();
        for (Map.Entry<PhysicalVolume, CppGeometry.PlacementText> e : last.placementTexts().entrySet()) {
            if (e.getValue().group() == pt.group()) {
                members.add(e.getKey());
            }
        }
        // the whole loop shifted by one vector, nothing turned
        Vec3 shift = null;
        boolean alike = changed.size() == members.size();
        for (PhysicalVolume pv : changed) {
            final Vec3 d = pv.position().sub(drawnPositions.get(pv));
            final Vec3 r0 = drawnRotations.get(pv);
            if (r0 != null && pv.rotation().distance(r0) > 1e-12) {
                alike = false;
            }
            if (shift == null) {
                shift = d;
            } else if (shift.distance(d) > 1e-9) {
                alike = false;
            }
        }
        if (alike && shift != null) {
            if (pt.transform()) {
                rewrite(edits, pt.file(), new Rewrite(pt.rotStart(), pt.rotEnd(), pt.rotText(), shiftTransform(pt.rotText(),
                    shift)));
            } else if (pt.posText() != null) {
                rewrite(edits, pt.file(), new Rewrite(pt.posStart(), pt.posEnd(), pt.posText(), shiftVector(pt.posText(),
                    shift)));
            } else {
                refused.add(refusal(changed.get(0)));
            }
            return;
        }
        final Set<Integer> copies = new java.util.HashSet<>();
        for (PhysicalVolume pv : members) {
            if (!copies.add(pv.copyNumber())) {
                refused.add(refusal(changed.get(0)) + ": its copies share their copy number, give each its own to move one");
                return;
            }
        }
        if (pt.copyText() == null) {
            refused.add(refusal(changed.get(0)) + ": its copy number is not given");
            return;
        }
        final String cond = pt.copyText().matches("[A-Za-z_][\\w.\\->\\[\\]]*") ? pt.copyText() : "(" + pt.copyText() + ")";
        if (pt.transform()) {
            final Chain chain = Chain.parse(pt.rotText(), cond);
            for (PhysicalVolume pv : changed) {
                chain.values.put(pv.copyNumber(), transform3D(pv.rotation(), pv.position(), pv.reflected()));
            }
            rewrite(edits, pt.file(), new Rewrite(pt.rotStart(), pt.rotEnd(), pt.rotText(), chain.text()));
            return;
        }
        final Chain rot = Chain.parse(pt.rotText(), cond), pos = pt.posText() == null ? null : Chain.parse(pt.posText(), cond);
        boolean turned = false, moved = false;
        for (PhysicalVolume pv : changed) {
            final Vec3 r0 = drawnRotations.get(pv);
            if (r0 != null && pv.rotation().distance(r0) > 1e-12) {
                rot.values.put(pv.copyNumber(), rotationMatrix(pv.rotation()));
                turned = true;
            }
            if (pv.position().distance(drawnPositions.get(pv)) > 1e-9) {
                if (pos == null) {
                    refused.add(refusal(pv));
                    continue;
                }
                pos.values.put(pv.copyNumber(), threeVector(pv.position()));
                moved = true;
            }
        }
        if (turned) {
            rewrite(edits, pt.file(), new Rewrite(pt.rotStart(), pt.rotEnd(), pt.rotText(), rot.text()));
        }
        if (moved) {
            rewrite(edits, pt.file(), new Rewrite(pt.posStart(), pt.posEnd(), pt.posText(), pos.text()));
        }
    }

    /** Exceptions by copy number, as G4code writes them: (c == 3 ? A : c == 7 ? B : original). */
    static final class Chain {
        final String cond;
        String orig;
        final Map<Integer, String> values = new java.util.TreeMap<>();

        private Chain(String cond, String orig) {
            this.cond = cond;
            this.orig = orig;
        }

        static Chain parse(String text, String cond) {
            final Chain c = new Chain(cond, text);
            if (!text.startsWith("(") || !text.endsWith(")")) {
                return c;
            }
            String inner = text.substring(1, text.length() - 1);
            final String head = cond + " == ";
            final Map<Integer, String> found = new LinkedHashMap<>();
            while (inner.startsWith(head)) {
                final int q = inner.indexOf(" ? ", head.length());
                if (q < 0) {
                    return c;
                }
                final int copy;
                try {
                    copy = Integer.parseInt(inner.substring(head.length(), q).strip());
                } catch (NumberFormatException e) {
                    return c;
                }
                int depth = 0, colon = -1;
                for (int i = q + 3; i < inner.length(); i++) {
                    final char ch = inner.charAt(i);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                    } else if (depth == 0 && inner.startsWith(" : ", i)) {
                        colon = i;
                        break;
                    }
                }
                if (colon < 0) {
                    return c;
                }
                found.put(copy, inner.substring(q + 3, colon));
                inner = inner.substring(colon + 3);
            }
            if (!found.isEmpty()) {
                c.orig = inner;
                c.values.putAll(found);
            }
            return c;
        }

        String text() {
            if (values.isEmpty()) {
                return orig;
            }
            final StringBuilder b = new StringBuilder("(");
            for (Map.Entry<Integer, String> e : values.entrySet()) {
                b.append(cond).append(" == ").append(e.getKey()).append(" ? ").append(e.getValue()).append(" : ");
            }
            return b.append(orig).append(')').toString();
        }
    }

    private static final Pattern SHIFTED = Pattern.compile("^\\((.*)\\) \\+ G4ThreeVector\\(([^()]*)\\)$");
    private static final Pattern TRANSLATED = Pattern.compile("^G4Translate3D\\(([^()]*)\\) \\* \\((.*)\\)$");

    /** pos shifted by d: (pos) + G4ThreeVector(d), an earlier shift of G4code's added to. */
    static String shiftVector(String text, Vec3 d) {
        final Matcher m = SHIFTED.matcher(text);
        if (m.matches()) {
            final Vec3 before = components(m.group(2));
            if (before != null) {
                final Vec3 total = before.add(d);
                return total.length() < 1e-9 ? m.group(1) : "(" + m.group(1) + ") + " + threeVector(total);
            }
        }
        return "(" + text + ") + " + threeVector(d);
    }

    /** A transform moved by d in its mother: G4Translate3D(d) * (transform), an earlier one added to. */
    static String shiftTransform(String text, Vec3 d) {
        final Matcher m = TRANSLATED.matcher(text);
        if (m.matches()) {
            final Vec3 before = components(m.group(1));
            if (before != null) {
                final Vec3 total = before.add(d);
                return total.length() < 1e-9 ? m.group(2) : "G4Translate3D(" + vectorArgs(total) + ") * (" + m.group(2) + ")";
            }
        }
        return "G4Translate3D(" + vectorArgs(d) + ") * (" + text + ")";
    }

    /** "1 * cm, 0, -2 * mm" read back as a vector in mm; null when a component is not a number with a unit. */
    private static Vec3 components(String args) {
        final String[] p = args.split(",");
        if (p.length != 3) {
            return null;
        }
        try {
            return new Vec3(com.sphere.core.geant4.Expr.eval(p[0].strip(), com.sphere.core.geant4.Expr.NO_VARIABLES),
                com.sphere.core.geant4.Expr.eval(p[1].strip(), com.sphere.core.geant4.Expr.NO_VARIABLES),
                com.sphere.core.geant4.Expr.eval(p[2].strip(), com.sphere.core.geant4.Expr.NO_VARIABLES));
        } catch (com.sphere.core.geant4.GeometryException e) {
            return null;
        }
    }

    private static String vectorArgs(Vec3 v) {
        final String s = threeVector(v);
        return s.substring("G4ThreeVector(".length(), s.length() - 1);
    }

    private static void rewrite(Map<Path, List<Rewrite>> edits, Path file, Rewrite r) {
        edits.computeIfAbsent(file, k -> new ArrayList<>()).add(r);
    }

    private String refusal(PhysicalVolume pv) {
        final CppGeometry.Origin o = last.origin(pv);
        return pv.name() + (o == null ? "" : " (line " + o.line() + ")");
    }

    /**
     * The frame rotation of a placement (GDML's angles: Rz Ry Rx, what G4PVPlacement's
     * pRot holds) as C++: rotateX, then Y, then Z, each premultiplying as CLHEP does.
     */
    static String rotationMatrix(Vec3 gdml) {
        final String chain = rotations(new double[] {gdml.x(), gdml.y(), gdml.z()}, new char[] {'X', 'Y', 'Z'}, 1);
        return chain.isEmpty() ? "nullptr" : "new G4RotationMatrix(G4RotationMatrix()" + chain + ")";
    }

    /**
     * A G4Transform3D holds the volume's own rotation, the inverse of the frame's: Rz(-z) first, then Y,
     * then X; a mirrored placement keeps its mirror through z = 0, applied first.
     */
    static String transform3D(Vec3 gdml, Vec3 position, boolean reflected) {
        final String chain = rotations(new double[] {gdml.z(), gdml.y(), gdml.x()}, new char[] {'Z', 'Y', 'X'}, -1);
        return "G4Transform3D(G4RotationMatrix()" + chain + ", " + threeVector(position) + ")"
            + (reflected ? " * G4ReflectZ3D()" : "");
    }

    private static String rotations(double[] radians, char[] axes, int sign) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            final double deg = sign * Math.toDegrees(radians[i]);
            if (Math.abs(deg) > 1e-9) {
                b.append(".rotate").append(axes[i]).append('(').append(CppGeometry.fmt(deg)).append(" * deg)");
            }
        }
        return b.toString();
    }

    /** "G4ThreeVector(0, 2 * cm, -7 * cm)": centimetres when every coordinate is a whole number of them. */
    static String threeVector(Vec3 p) {
        final double[] v = {p.x(), p.y(), p.z()};
        boolean cm = false, allCm = true;
        for (double x : v) {
            if (Math.abs(x) > 1e-9) {
                cm = true;
                if (Math.abs(x / 10 - Math.rint(x / 10)) > 1e-9) {
                    allCm = false;
                }
            }
        }
        final String unit = cm && allCm ? "cm" : "mm";
        final double u = unit.equals("cm") ? 10 : 1;
        final StringBuilder b = new StringBuilder("G4ThreeVector(");
        for (int i = 0; i < 3; i++) {
            b.append(i > 0 ? ", " : "");
            b.append(Math.abs(v[i]) < 1e-9 ? "0" : CppGeometry.fmt(v[i] / u) + " * " + unit);
        }
        return b.append(')').toString();
    }

    // ==================================================================
    // Inspector
    // ==================================================================

    private void inspect() {
        final PhysicalVolume pv = model.primary();
        final LogicalVolume lv = pv != null ? pv.logical() : model.focus() instanceof LogicalVolume l ? l : null;
        if (lv == null) {
            final Geometry g = model.geometry;
            inspector.setText("<html><body style='font-family:sans-serif;font-size:11px'>"
                + (g.world() == null ? "No detector read yet." : "<b>" + g.volumes().size() + "</b> volumes, <b>"
                + g.placements().size() + "</b> placements, <b>" + g.instances().size() + "</b> copies drawn."
                + "<br><br>Click a volume in 3D or in Volumes to see it here and its line in the code; put the caret"
                + " on a G4PVPlacement line to select what it places. Drag a selected volume to move it: a position"
                + " written once as G4ThreeVector(...) is rewritten in the code.")
                + "</body></html>");
            return;
        }
        final StringBuilder b = new StringBuilder("<html><body style='font-family:sans-serif;font-size:11px'>");
        if (pv != null) {
            b.append("<div style='font-size:13px'><b>").append(ApiBrowser.esc(pv.name())).append("</b> copy ")
             .append(pv.copyNumber()).append(" in ").append(ApiBrowser.esc(pv.mother().name())).append("</div>");
            final Vec3 p = pv.position(), r = pv.rotation();
            b.append("position ").append(len(p.x())).append(", ").append(len(p.y())).append(", ").append(len(p.z()));
            if (r.length() > 1e-12) {
                b.append("<br>rotation (GDML) ").append(deg(r.x())).append(", ").append(deg(r.y())).append(", ")
                 .append(deg(r.z()));
            }
            link(b, "placed", last == null ? null : last.origin(pv));
            final CppGeometry.PlacementText ptx = last == null ? null : last.placementTexts().get(pv);
            if (ptx != null && ptx.shared()) {
                b.append("<br><span style='font-style:italic'>placed in a loop").append(ptx.copyText() == null ? ""
                    : ", copy number " + ApiBrowser.esc(ptx.copyText())).append(": moving or turning this copy writes an"
                    + " exception for it; all its copies moved alike shift the loop</span>");
            } else if (last != null && last.positions().containsKey(pv)) {
                b.append("<br><span style='font-style:italic'>move it (M) or turn it (R) in 3D: the code is rewritten</span>");
            } else if (last != null && last.placementTexts().containsKey(pv)) {
                b.append("<br><span style='font-style:italic'>turn it (R) or move it (M) in 3D: its G4PVPlacement arguments"
                    + " are rewritten</span>");
            } else if (last != null && last.origin(pv) != null) {
                b.append("<br><span style='font-style:italic'>placed in a loop or a method called twice: change the code to"
                    + " move it</span>");
            }
            b.append("<hr>");
        }
        b.append("<b>").append(ApiBrowser.esc(lv.name())).append("</b> (G4LogicalVolume)");
        link(b, "made", last == null ? null : last.origin(lv));
        final Solid s = lv.solid();
        if (s != null) {
            b.append("<br><br><a href='class:").append(s.g4Class()).append("'>").append(s.g4Class()).append("</a> \"")
             .append(ApiBrowser.esc(s.name())).append("\"");
            link(b, "made", last == null ? null : last.origin(s));
            b.append("<table cellspacing=0 cellpadding=1>");
            for (Solid.Param prm : s.params()) {
                b.append("<tr><td style='padding-right:8px'>").append(ApiBrowser.esc(prm.label)).append("</td><td>")
                 .append(ApiBrowser.esc(prm.expr())).append("</td></tr>");
            }
            b.append("</table>");
        }
        final Material m = model.geometry.materials().material(lv.material());
        b.append("<br><b>").append(ApiBrowser.esc(lv.material())).append("</b>");
        if (m != null) {
            b.append(String.format(Locale.ROOT, "<br>density %.4g g/cm3, X0 %s, lambda_I %s", m.densityGcm3(),
                Units.best(m.radiationLength(), Units.Category.LENGTH),
                Units.best(m.nuclearInteractionLength(), Units.Category.LENGTH)));
            if (s != null && s.error() == null) {
                final double vol = s.cubicVolume();
                double own = vol;
                for (PhysicalVolume d : lv.daughters()) {
                    if (d.logical().solid() != null) {
                        own -= d.logical().solid().cubicVolume() * Math.max(1, d.copies());
                    }
                }
                b.append(String.format(Locale.ROOT, "<br>volume %.4g cm3, mass of its own material %s", vol / 1000,
                    Units.best(Math.max(0, own) * m.density(), Units.Category.MASS)));
            }
        } else {
            b.append(" <span style='color:#c0392b'>unknown material</span>");
        }
        b.append("</body></html>");
        inspector.setText(b.toString());
        inspector.setCaretPosition(0);
    }

    private static void link(StringBuilder b, String what, CppGeometry.Origin o) {
        if (o != null) {
            b.append(" &middot; ").append(what).append(" at <a href='goto:").append(o.file()).append('|').append(o.line())
             .append("'>").append(o.file().getFileName()).append(':').append(o.line()).append("</a>");
        }
    }

    private static String len(double mm) {
        return Units.best(mm, Units.Category.LENGTH);
    }

    private static String deg(double rad) {
        return CppGeometry.fmt(Math.toDegrees(rad)) + " deg";
    }

    // ==================================================================
    // Macros: every line checked as G4UImanager would, while typed
    // ==================================================================

    private final List<Problem> macroProblems = new ArrayList<>();

    private void checkMacros() {
        macroProblems.clear();
        UiCommands u = null;
        for (CodeTab t : tabs) {
            if (t.getFile() == null || !isMacro(t.getFile().toPath())) {
                continue;
            }
            if (u == null) {
                try {
                    u = UiCommands.get();
                } catch (Exception e) {
                    return;
                }
            }
            final String[] lines = t.getEditorArea().getText().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (UiCommands.Finding f : u.check(lines[i].strip())) {
                    if (f.kind() != UiCommands.Finding.Kind.FOREIGN) {
                        macroProblems.add(new Problem(t.getFile().toPath(), i + 1, f.error() ? Geometry.Severity.ERROR
                            : Geometry.Severity.INFO, f.message(), "macro"));
                    }
                }
            }
        }
    }

    private JComponent uiCommandsPanel() {
        final JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        final javax.swing.JTextField q = new javax.swing.JTextField();
        q.putClientProperty("JTextField.placeholderText", "Search Geant4's UI commands: beamOn, gun energy...");
        final DefaultListModel<UiCommands.Command> found = new DefaultListModel<>();
        final JList<UiCommands.Command> list = new JList<>(found);
        final JTextArea help = new JTextArea();
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        list.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof UiCommands.Command c) {
                    setText(c.path());
                    setToolTipText(c.summary());
                }
                return this;
            }
        });
        final Runnable search = () -> {
            found.clear();
            try {
                final UiCommands u = UiCommands.get();
                final String text = q.getText().strip();
                final List<UiCommands.Command> r = text.isEmpty() ? u.search("run") : text.startsWith("/")
                    && u.find(text) != null ? List.of(u.find(text)) : u.search(text);
                for (int i = 0; i < r.size() && i < 200; i++) {
                    found.addElement(r.get(i));
                }
            } catch (Exception e) {
                help.setText("Geant4's command catalog could not be read: " + e.getMessage());
            }
        };
        q.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                search.run();
            }

            public void removeUpdate(DocumentEvent e) {
                search.run();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
        list.addListSelectionListener(e -> {
            final UiCommands.Command c = list.getSelectedValue();
            if (c != null) {
                try {
                    help.setText(String.join("\n", UiCommands.get().describe(c)));
                    help.setCaretPosition(0);
                } catch (Exception ignored) {
                    // left as it was
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    insertAtCaret(list.getSelectedValue().path() + " ");
                }
            }
        });
        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(help));
        split.setResizeWeight(0.5);
        p.add(q, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        final JLabel hint = new JLabel("Double-click writes the command into the macro; .mac files are checked as typed");
        hint.setForeground(new Color(0x7D8796));
        p.add(hint, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(search);
        return p;
    }

    // ==================================================================
    // Build and run (needs a Geant4)
    // ==================================================================

    private static final Pattern GCC = Pattern.compile("^(.+?):(\\d+):(\\d+): (fatal error|error|warning): (.*)$");
    private static final Pattern MSVC = Pattern.compile("^(.+?)\\((\\d+)(?:,(\\d+))?\\): (fatal error|error|warning) \\w+: (.*)$");

    private void build(boolean run) {
        if (project == null) {
            say("Open or create a project first.");
            return;
        }
        if (Geant4AppBuilder.running()) {
            say("A build or a run is going on (Stop ends it).");
            return;
        }
        final ChangesDialog.Result r = review(tabs, run ? "Run" : "Build", "The compiler reads the files as they are"
            + " saved; these changed since. Tick the files to save before building.", "Save selected and "
            + (run ? "run" : "build"), (run ? "Run" : "Build") + " without saving");
        if (r != null && r.choice() == ChangesDialog.Choice.CANCEL) {
            return;
        }
        save(r);
        rightTabs.setSelectedIndex(rightTabs.indexOfTab("Console"));
        console.setText("");
        buildProblems.clear();
        buildFindings.clear();
        publishFindings();
        final CodeTab active = current();
        final Path macro = active != null && active.getFile() != null && isMacro(active.getFile().toPath())
            ? active.getFile().toPath() : null;
        final Path folder = project;
        final Thread t = new Thread(() -> {
            final long t0 = System.nanoTime();
            final Geant4AppBuilder.Build b = Geant4AppBuilder.build(folder, false, settings, line -> {
                compilerLine(line, folder);
                SwingUtilities.invokeLater(() -> console.append(line + "\n"));
            });
            SwingUtilities.invokeLater(() -> {
                say((b.ok() ? "" : "[build failed] ") + b.message() + String.format(Locale.ROOT, " (%.0f s)",
                    (System.nanoTime() - t0) / 1e9));
                publishFindings();
                for (CodeTab tab : tabs) {
                    applyFindings(tab);
                }
            });
            if (!b.ok() || !run) {
                return;
            }
            final List<String> args = new ArrayList<>();
            if (macro != null) {
                args.add(folder.relativize(macro).toString().replace('\\', '/'));
            } else {
                final String m = Geant4AppBuilder.defaultMacro(b);
                if (m == null) {
                    SwingUtilities.invokeLater(() -> say("Open the macro to run (run1.mac...) and press Run again."));
                    return;
                }
                args.add(m);
            }
            final long start = System.currentTimeMillis();
            try {
                final int code = Geant4AppBuilder.run(b, b.program(), args, settings,
                    line -> SwingUtilities.invokeLater(() -> console.append(line + "\n")));
                final List<Path> outs = Geant4AppBuilder.outputs(b, start);
                SwingUtilities.invokeLater(() -> {
                    say(b.program() + (code == 0 ? " finished" : " exited with code " + code) + String.format(Locale.ROOT,
                        " after %.1f s", (System.currentTimeMillis() - start) / 1000.0));
                    for (Path o : outs) {
                        say("  wrote " + o);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> say("Cannot run " + b.program() + ": " + e.getMessage()));
            }
        }, "g4code-build");
        t.setDaemon(true);
        t.start();
    }

    /** A compiler's error or warning line, put on the file and line it names. */
    private void compilerLine(String line, Path folder) {
        Matcher m = GCC.matcher(line.strip());
        if (!m.matches()) {
            m = MSVC.matcher(line.strip());
            if (!m.matches()) {
                return;
            }
        }
        final Path file = resolve(m.group(1), folder);
        final int ln = Integer.parseInt(m.group(2));
        final int col = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        final boolean error = !m.group(4).equals("warning");
        final String msg = m.group(5);
        SwingUtilities.invokeLater(() -> {
            buildProblems.add(new Problem(file, ln, error ? Geometry.Severity.ERROR : Geometry.Severity.WARNING, msg,
                "compiler"));
            if (file != null) {
                buildFindings.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new ArrayList<>())
                    .add(new EditorDiagnostic(ln, col, 0, error ? EditorDiagnostic.Severity.ERROR
                        : EditorDiagnostic.Severity.WARNING, "compiler", msg, EditorDiagnostic.SOURCE_COMPILER));
            }
        });
    }

    /** A path as the compiler wrote it (in WSL, a Linux one): the project file of that name. */
    private static Path resolve(String raw, Path folder) {
        try {
            final Path p = Path.of(raw);
            if (Files.isRegularFile(p)) {
                return p;
            }
        } catch (Exception ignored) {
            // a WSL path on Windows
        }
        final String name = raw.replace('\\', '/').substring(raw.replace('\\', '/').lastIndexOf('/') + 1);
        for (String sub : new String[] {"src", "include", ""}) {
            final Path c = folder.resolve(sub).resolve(name);
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    private void say(String s) {
        console.append(s + "\n");
        console.setCaretPosition(console.getDocument().getLength());
        status.setText(s.lines().findFirst().orElse(s));
    }

    // ==================================================================
    // Projects, GDML, G4editor
    // ==================================================================

    private JFileChooser chooser() {
        final JFileChooser c = new JFileChooser(project != null ? project.toFile()
            : com.sphere.core.fs.WorkingDirectory.get().toFile());
        c.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        return c;
    }

    private void openDialog() {
        final JFileChooser c = chooser();
        c.setDialogTitle("Open a Geant4 application folder, or a file");
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openTarget(c.getSelectedFile().toPath());
        }
    }

    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(G4CodeFrame.class);
    private static final String DRAWN = "The detector drawn here";

    /**
     * Drawn in 3D with no application open (or on a GDML file alone): there is no C++ to write the change into,
     * so the drawing is offered as a new application whose DetectorConstruction builds it. Asked once; declined,
     * the drawing stays a drawing until New project is chosen.
     */
    private void offerDrawingAsCode() {
        if (drawingOffered || model.geometry.world() == null || model.geometry.placements().isEmpty()) {
            return;
        }
        drawingOffered = true;
        // after the tool's own edit is done: a dialog in the middle of it would cut the drawing short
        SwingUtilities.invokeLater(() -> newProject(DRAWN));
    }

    private void newProject() {
        newProject(null);
    }

    /**
     * A complete application (CMake, main, DetectorConstruction, actions, macros) from a template or a design:
     * its name (the folder's and the program's), where it goes, what it starts from. The detector is written in
     * C++ only, a GDML design included: no .gdml goes with it.
     */
    private void newProject(String startFrom) {
        final javax.swing.JTextField name = new javax.swing.JTextField("MyDetector", 24);
        final javax.swing.JTextField where = new javax.swing.JTextField(PREFS.get("newProjectFolder",
            com.sphere.core.fs.WorkingDirectory.get().toString()), 24);
        final JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> {
            final JFileChooser c = new JFileChooser(where.getText());
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            c.setDialogTitle("The folder the project goes in");
            if (c.showDialog(this, "Choose") == JFileChooser.APPROVE_OPTION) {
                where.setText(c.getSelectedFile().getPath());
            }
        });
        final List<String> choices = new ArrayList<>();
        for (String k : Templates.kinds()) {
            choices.add("Template: " + k);
        }
        if (model.geometry.world() != null && !model.geometry.placements().isEmpty()) {
            choices.add(DRAWN);
        }
        choices.add("From a GDML design...");
        choices.add("Empty world (a box of air)");
        final JComboBox<String> what = new JComboBox<>(choices.toArray(String[]::new));
        if (startFrom != null && choices.contains(startFrom)) {
            what.setSelectedItem(startFrom);
        }
        // The preview keeps one width: a long folder grew it past the dialog, and the grid then fell back to
        // every component's minimum (the fields shrank to nothing, the button spread). The end of the path,
        // where the project's name is, is what shows; the whole path is the tooltip.
        final JLabel result = new JLabel(" ");
        result.setFont(result.getFont().deriveFont(11f));
        result.setPreferredSize(new java.awt.Dimension(440, result.getPreferredSize().height));
        result.setMinimumSize(new java.awt.Dimension(100, result.getPreferredSize().height));
        final Runnable preview = () -> {
            String full;
            try {
                full = Path.of(where.getText().strip(), name.getText().strip()).toString();
            } catch (RuntimeException e) {
                full = where.getText().strip() + java.io.File.separator + name.getText().strip();
            }
            result.setText("Creates " + (full.length() > 58 ? "…" + full.substring(full.length() - 57) : full));
            result.setToolTipText(full);
        };
        final DocumentListener update = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                preview.run();
            }

            public void removeUpdate(DocumentEvent e) {
                preview.run();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        };
        name.getDocument().addDocumentListener(update);
        where.getDocument().addDocumentListener(update);
        preview.run();

        final JPanel form = new JPanel(new java.awt.GridBagLayout());
        final java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        // labels in column 0, fields in column 1 (it takes any room left), the Browse button in column 2 as it is
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        form.add(new JLabel("Project name"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(name, c);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel("Location"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(where, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        form.add(browse, c);
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 2;
        form.add(new JLabel("Start from"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(what, c);
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        form.add(result, c);
        where.setMinimumSize(where.getPreferredSize());
        name.setMinimumSize(name.getPreferredSize());
        SwingUtilities.invokeLater(() -> {
            name.requestFocusInWindow();
            name.selectAll();
        });
        while (true) {
            if (JOptionPane.showConfirmDialog(this, form, DRAWN.equals(startFrom) ? "The drawing as a Geant4 application in C++"
                : "New Geant4 application", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                if (DRAWN.equals(startFrom)) {
                    say("The drawing is not code yet: New project (Ctrl+Shift+N), \"" + DRAWN + "\", writes it in C++");
                }
                return;
            }
            final String n = name.getText().strip();
            if (!n.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
                JOptionPane.showMessageDialog(this, "The name starts with a letter and holds letters, digits, '_' and '-'"
                    + " only: it names the folder and the program.", "New Geant4 application", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            final Path parent = Path.of(where.getText().strip());
            if (!Files.isDirectory(parent)) {
                JOptionPane.showMessageDialog(this, "No folder " + parent, "New Geant4 application",
                    JOptionPane.WARNING_MESSAGE);
                continue;
            }
            final Path folder = parent.resolve(n);
            if (Files.exists(folder)) {
                JOptionPane.showMessageDialog(this, folder + " exists already: give another name.",
                    "New Geant4 application", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            PREFS.put("newProjectFolder", parent.toString());
            createProject(n, folder, (String) what.getSelectedItem());
            return;
        }
    }

    private void createProject(String name, Path folder, String choice) {
        final Geometry g = Geometry.blank();
        try {
            if (choice.startsWith("Template: ")) {
                Templates.build(g, choice.substring(10), Map.of(), "mm");
                g.rebuild();
            } else if (choice.startsWith("From a GDML")) {
                final JFileChooser c = chooser();
                c.setFileSelectionMode(JFileChooser.FILES_ONLY);
                c.setDialogTitle("The GDML design");
                if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                g.replaceWith(GeometryFiles.read(c.getSelectedFile().toPath(), null).geometry());
            } else if (choice.equals(DRAWN)) {
                g.replaceWith(model.geometry);
            }
            g.setTitle(name);
            // the detector in C++ alone: the design it came from (a GDML file, a drawing) is not carried along
            final List<Path> written = new CppExport(g, CppExport.Options.defaults(g).cppOnly()).write(folder);
            say("Wrote the application " + name + ": " + written.size() + " files in " + folder);
            openProject(folder);
        } catch (Exception e) {
            say("Cannot write the application: " + e.getMessage());
        }
    }

    private void exportGdml() {
        if (model.geometry.world() == null) {
            say("No detector to write: the code builds none yet.");
            return;
        }
        final JFileChooser c = chooser();
        c.setFileSelectionMode(JFileChooser.FILES_ONLY);
        c.setSelectedFile(new File(c.getCurrentDirectory(), (project == null ? "detector" : project.getFileName()) + ".gdml"));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            final Path p = c.getSelectedFile().toPath();
            for (String n : GeometryFiles.write(model.geometry, p, GeometryFiles.Format.GDML)) {
                say("  " + n);
            }
            say("Wrote " + p + ": the detector of the C++, readable by G4GDMLParser, G4editor and ROOT (via :g4 convert)");
            if (project != null && p.startsWith(project)) {
                projectTree.refresh();
            }
        } catch (IOException e) {
            say("Cannot write: " + e.getMessage());
        }
    }

    private void toG4editor() {
        final CodeTab t = current();
        if (t != null && t.getFile() != null && isGdml(t.getFile().toPath())) {
            final ChangesDialog.Result r = review(List.of(t), "Open in G4editor", "G4editor opens the file as it is"
                + " saved; it changed since.", "Save and open", "Open the saved file");
            if (r != null && r.choice() == ChangesDialog.Choice.CANCEL) {
                return;
            }
            save(r);
            G4EditorFrame.show(t.getFile().toPath(), settings);
            say("G4editor opened " + t.getFile().getName() + ": saved there, it reloads here");
            return;
        }
        if (last != null && last.gdml() != null) {
            G4EditorFrame.show(last.gdml(), settings);
            return;
        }
        if (model.geometry.world() == null) {
            say("No detector to open.");
            return;
        }
        try {
            final Path tmp = Files.createTempFile("g4code-", ".gdml");
            GeometryFiles.write(model.geometry, tmp, GeometryFiles.Format.GDML);
            G4EditorFrame.show(tmp, settings);
            say("G4editor opened a copy of the detector the C++ builds (" + tmp.getFileName() + "): its analyses and"
                + " runs work on it; to change the C++, edit it here");
        } catch (IOException e) {
            say("Cannot open G4editor: " + e.getMessage());
        }
    }

    // ==================================================================
    // Geant4 code to insert
    // ==================================================================

    private static final String[][] SNIPPETS = {
        {"Box volume placed in a mother", """
            auto* solidBox = new G4Box("Box", 0.5 * 10 * cm, 0.5 * 10 * cm, 0.5 * 2 * cm);
            auto* logicBox = new G4LogicalVolume(solidBox, nist->FindOrBuildMaterial("G4_Si"), "Box");
            new G4PVPlacement(nullptr, G4ThreeVector(0, 0, 10 * cm), logicBox, "Box", logicWorld, false, 0, true);
            """},
        {"Tube volume placed in a mother", """
            auto* solidTube = new G4Tubs("Tube", 0., 5 * cm, 0.5 * 20 * cm, 0., 360. * deg);
            auto* logicTube = new G4LogicalVolume(solidTube, nist->FindOrBuildMaterial("G4_WATER"), "Tube");
            new G4PVPlacement(nullptr, G4ThreeVector(), logicTube, "Tube", logicWorld, false, 0, true);
            """},
        {"Layers along z (a calorimeter)", """
            const G4int nLayers = 10;
            const G4double absT = 10 * mm, gapT = 5 * mm, layerT = absT + gapT;
            auto* solidAbs = new G4Box("Abs", 0.5 * 20 * cm, 0.5 * 20 * cm, 0.5 * absT);
            auto* logicAbs = new G4LogicalVolume(solidAbs, nist->FindOrBuildMaterial("G4_Pb"), "Abs");
            for (G4int i = 0; i < nLayers; ++i) {
              const G4double z = -0.5 * nLayers * layerT + (i + 0.5) * layerT;
              new G4PVPlacement(nullptr, G4ThreeVector(0, 0, z), logicAbs, "Abs", logicWorld, false, i, true);
            }
            """},
        {"Ring of crystals (rotated placements)", """
            const G4int nCrystals = 24;
            const G4double radius = 30 * cm;
            auto* solidCrystal = new G4Box("Crystal", 0.5 * 2 * cm, 0.5 * 4 * cm, 0.5 * 10 * cm);
            auto* logicCrystal = new G4LogicalVolume(solidCrystal, nist->FindOrBuildMaterial("G4_BGO"), "Crystal");
            for (G4int i = 0; i < nCrystals; ++i) {
              const G4double phi = i * twopi / nCrystals;
              G4RotationMatrix rotm;
              rotm.rotateY(90 * deg);
              rotm.rotateZ(phi);
              const G4ThreeVector position = radius * G4ThreeVector(std::cos(phi), std::sin(phi), 0.);
              new G4PVPlacement(G4Transform3D(rotm, position), logicCrystal, "Crystal", logicWorld, false, i, true);
            }
            """},
        {"Material from elements", """
            auto* elH = nist->FindOrBuildElement("H");
            auto* elC = nist->FindOrBuildElement("C");
            auto* scintillator = new G4Material("Scintillator", 1.032 * g / cm3, 2);
            scintillator->AddElement(elC, 9);
            scintillator->AddElement(elH, 10);
            """},
        {"Hollow volume (G4SubtractionSolid)", """
            auto* outer = new G4Box("Outer", 0.5 * 20 * cm, 0.5 * 20 * cm, 0.5 * 20 * cm);
            auto* hole = new G4Tubs("Hole", 0., 4 * cm, 0.5 * 30 * cm, 0., 360. * deg);
            auto* shell = new G4SubtractionSolid("Shell", outer, hole);
            auto* logicShell = new G4LogicalVolume(shell, nist->FindOrBuildMaterial("G4_Fe"), "Shell");
            new G4PVPlacement(nullptr, G4ThreeVector(), logicShell, "Shell", logicWorld, false, 0, true);
            """},
        {"Colour and visibility", """
            auto* vis = new G4VisAttributes(G4Colour(0.2, 0.6, 1.0, 0.5));
            vis->SetForceSolid(true);
            logicBox->SetVisAttributes(vis);
            """},
        {"Geometry from a GDML file", """
            G4GDMLParser parser;
            parser.Read("detector.gdml", false);
            return parser.GetWorldVolume();
            """},
    };

    private void snippets(JComponent anchor) {
        final JPopupMenu m = new JPopupMenu();
        for (String[] s : SNIPPETS) {
            final JMenuItem i = new JMenuItem(s[0]);
            i.addActionListener(e -> insertAtCaret(s[1].stripIndent()));
            m.add(i);
        }
        m.show(anchor, 0, anchor.getHeight());
    }

    // ==================================================================
    // Problems list
    // ==================================================================

    private static final class ProblemRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
            super.getListCellRendererComponent(l, v, i, s, f);
            if (v instanceof Problem p) {
                final Color color = switch (p.severity()) {
                    case ERROR -> new Color(0xD9534F);
                    case WARNING -> new Color(0xE0A030);
                    default -> new Color(0x7D8796);
                };
                setIcon(Icons.dot(10, color));
                setText(p.source() + "   " + p);
            }
            return this;
        }
    }
}
