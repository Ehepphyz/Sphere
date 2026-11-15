package com.sphere.core.python.jupyterlab;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;

import com.sphere.components.editor.AutoIndenter;
import com.sphere.components.editor.BracketMatcher;
import com.sphere.components.editor.CodeTextPane;
import com.sphere.components.editor.CompletionPopup;
import com.sphere.components.editor.EditorActions;
import com.sphere.components.editor.EditorDiagnostic;
import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.editor.PySemantics;
import com.sphere.components.editor.RenameBox;
import com.sphere.components.editor.SmartRename;
import com.sphere.components.editor.SemanticLegend;
import com.sphere.components.editor.SemanticStyle;
import com.sphere.components.editor.SmartUndoManager;
import com.sphere.components.editor.SyntaxHighlighter;
import com.sphere.components.editor.TokenKind;
import com.sphere.core.python.jupyterlab.kernel.KernelSpecs;
import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * The notebook window: cells to edit and run on a kernel, their outputs, the
 * kernel's namespace.
 *
 * Code cells are SphereCoder's own code surface (CodeTextPane): colors painted
 * from Python's grammar rather than written into the text, auto-indent, brackets
 * and quotes that close themselves where they should (never after a letter, so
 * "l'énergie" stays as typed), undo, bracket matching. On top of it the notebook
 * adds what only a live kernel knows: completion from the objects themselves,
 * their documentation on Shift+Tab, names colored by what they are at run time,
 * and squiggles where the notebook would not survive a restart.
 */
public final class JupylabXeditor extends JFrame {

    private static final long serialVersionUID = 1L;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    static class Notebook {
        int nbformat = 4;
        int nbformat_minor = 5;
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Cell> cells = new ArrayList<>();
        // Keys the format may gain, kept so a save does not strip them.
        Map<String, Object> extra = new LinkedHashMap<>();
    }

    static class Cell {
        String cell_type;
        // Required from nbformat 4.5 on, and dropped by the previous reader.
        String id;
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object source;
        Integer execution_count;
        List<Output> outputs = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();
    }

    static class Output {
        String output_type;
        String name;
        Object text;
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String ename;
        String evalue;
        List<String> traceback = new ArrayList<>();
        Integer execution_count;
        // Not saved: the id an update_display_data names, and a counter its updates bump.
        transient String displayId;
        transient int version;
    }

    public enum KernelMode { DISABLED, LOCAL_PYTHON, CONNECTING }

    /** Every Jupylab window open: :jlab brings one to the front rather than opening its notebook twice. */
    private static final Set<JupylabXeditor> OPEN = Collections.newSetFromMap(new WeakHashMap<>());

    private transient KernelMode kernelMode = KernelMode.LOCAL_PYTHON;
    private transient Notebook notebook;
    private transient Path currentPath;
    private transient JPanel cellsContainer;
    /** The kernel at the top right: name, version, memory, the menu of kernels and actions. */
    private transient KernelChip kernelChip;
    private transient List<KernelSpecs.Spec> kernelSpecs = List.of();
    private transient KernelSpecs.Spec selectedSpec;
    private transient JLabel kernelIndicator;
    private transient JScrollPane cellsScrollPane;
    private boolean dirty = false;

    // The dataflow between cells: what each takes from which, which are stale (see NotebookDataflow).
    private transient NotebookDataflow.Graph dataflow = NotebookDataflow.Graph.EMPTY;
    private transient javax.swing.Timer dataflowTimer;
    /** The text each cell had when it last ran: a cell edited since is out of date. */
    private final transient Map<Cell, String> ranSource = new IdentityHashMap<>();
    private transient DataflowMap dataflowMap;
    private transient JTabbedPane sideTabs;
    private transient JButton runStaleBtn;
    private transient JToggleButton varsToggle;
    private transient JToggleButton flowToggle;
    /**
     * Reactive: the cells are a graph, not a list. Running a cell runs what it
     * needs that is out of date, then itself, then every cell taking from it, in the graph's order,
     * wherever the cells sit; a variable has one home; a name a cell stops defining leaves the kernel;
     * objects changed in place are followed. Saved with the notebook (metadata.sphere.reactive).
     */
    private boolean reactive;
    private transient JToggleButton reactiveBtn;
    /** The run under way: the cells still to go, in the graph's order, the one at the kernel, why each runs. */
    private final transient Deque<Cell> reactiveQueue = new ArrayDeque<>();
    private transient Cell reactiveInFlight;
    private final transient Map<Cell, String> runReasons = new IdentityHashMap<>();
    private transient int reactiveDone;
    private transient int reactiveTotal;
    /** What each cell defined when it last ran: what it no longer defines leaves the kernel when it runs again. */
    private final transient Map<Cell, Set<String>> ranBinds = new IdentityHashMap<>();
    /** The stop glyph turns red while a cell runs; how many cells are out of date, on the run-stale glyph. */
    private transient JButton stopBtn;
    private transient int staleCount;
    /** How long each cell took when it last ran: what a run will take, said before it starts. */
    private final transient Map<Cell, Long> lastMillis = new IdentityHashMap<>();
    /** Each cell's last run that did not fail, to say what its next run changed (OutputDelta). */
    private final transient Map<Cell, OutputDelta.Snapshot> deltaBase = new IdentityHashMap<>();
    /** What the doctor or the delta says under each cell: kept when the cells are laid out again. */
    private final transient Map<Cell, Insight> insights = new IdentityHashMap<>();
    /** Counts each cell's runs: what was found about an older run is dropped. */
    private final transient Map<Cell, Integer> runSerial = new IdentityHashMap<>();
    /** Counts the kernels this window started: outputs made by another kernel are "before the restart". */
    private transient int kernelEpoch;
    private transient boolean kernelAlive;
    // The top line: actions, the notebook's state, the kernel; fitted to the window (fitHeader).
    private transient JToolBar toolbar;
    private transient JPanel northPanel;
    private transient JPanel statePanel;
    private transient JPanel kernelPanel;
    /** Why the last Run All would fail, from the kernel's analysis (linear notebooks). */
    private transient int fragileCells = -1;
    private transient String fragileWhy = "";
    private transient javax.swing.Timer flashTimer;
    /** Memory and CPU of the kernel, asked every few seconds while it runs. */
    private transient javax.swing.Timer usageTimer;
    // What the kernel says a value looks like (statistics, a histogram of it), asked when the mouse rests on it.
    private final transient Map<String, Map<String, Object>> peeks = new HashMap<>();
    private final transient Map<Integer, String> peekRequests = new HashMap<>();
    private transient CellPanel tooltipCell;
    private transient java.awt.Point tooltipPoint;

    // Kernel side: one live kernel, its inspector, its completion and its analysis.
    private final transient com.sphere.utils.SettingsManager settings =
        new com.sphere.utils.SettingsManager();
    private transient NotebookExecutor executor;
    private transient JLabel kernelStatusLabel;
    private transient JLabel reproLabel;
    private transient JLabel saveStatus;
    private transient JTable variableTable;
    private transient javax.swing.table.DefaultTableModel variableModel;
    private transient JSplitPane bodySplit;
    private final transient InspectPopup inspectPopup = new InspectPopup();
    private final transient Map<Integer, KernelCompletion> completionRequests = new HashMap<>();
    private final transient Map<Integer, javax.swing.text.JTextComponent> inspectRequests = new HashMap<>();

    /** What the kernel says each name is; read by every code cell's lexer. */
    private transient volatile Map<String, TokenKind> liveKinds = Map.of();
    private final transient Function<String, TokenKind> liveKindOf = name -> liveKinds.get(name);

    /**
     * The notebook as one module for the semantic colors: what every cell binds
     * and reads, and what the kernel holds (runtime families, staleness). Each
     * code cell's scope analysis asks it about the names it does not bind.
     */
    private final transient NotebookSemantics semantics = new NotebookSemantics(this::codeCellLabel);
    /** Rescans the cells once another cell changed what they see. */
    private transient javax.swing.Timer semanticTimer;
    private boolean semanticColors = true;
    private transient javax.swing.Timer analysisTimer;
    private transient int analysisRequest = -1;
    private transient List<Cell> analysisCells = List.of();
    private transient List<String> analysisSources = List.of();
    private transient CellPanel hovered;

    /**
     * Opens a notebook in Jupylab, on the event thread: a window already showing
     * it comes to the front; a path not on disk yet is a new notebook saved there;
     * null is a new, untitled one.
     */
    public static void open(Path path) {
        SwingUtilities.invokeLater(() -> {
            Path wanted = path == null ? null : path.toAbsolutePath().normalize();
            if (wanted != null) {
                for (JupylabXeditor w : new ArrayList<>(OPEN)) {
                    if (w.isDisplayable() && w.currentPath != null
                        && w.currentPath.toAbsolutePath().normalize().equals(wanted)) {
                        if ((w.getExtendedState() & Frame.ICONIFIED) != 0) {
                            w.setExtendedState(w.getExtendedState() & ~Frame.ICONIFIED);
                        }
                        w.setVisible(true);
                        w.toFront();
                        w.requestFocus();
                        return;
                    }
                }
            }
            try {
                JupylabXeditor editor = new JupylabXeditor(wanted);
                editor.setVisible(true);
                editor.toFront();
            } catch (Exception ex) {
                com.sphere.core.JavaErrors.report("opening Jupylab", ex);
                JOptionPane.showMessageDialog(null, "Jupylab could not open: " + ex.getMessage());
            }
        });
    }

    public JupylabXeditor(Path ipynbPath) throws Exception {
        this(ipynbPath, ipynbPath != null && Files.exists(ipynbPath) ? NotebookIO.read(ipynbPath) : new Notebook());
    }

    /** A window on a notebook already read, or made here (a gathered one, unsaved until the user saves it). */
    private JupylabXeditor(Path ipynbPath, Notebook loaded) {
        super("JupylabXeditor - " + (ipynbPath == null ? "untitled" : ipynbPath.getFileName()));
        com.sphere.utils.IconManager.applyAppIcon(this);
        this.currentPath = ipynbPath;
        this.notebook = loaded;
        // What the saved notebook shows is the first run's reference: its next run says what changed.
        for (Cell c : notebook.cells) {
            seedDelta(c);
        }
        if (notebook.cells.isEmpty()) {
            // a new notebook opens on a code cell to type in, as Jupyter's do
            Cell first = new Cell();
            first.cell_type = "code";
            first.source = new ArrayList<>(List.of(""));
            notebook.cells.add(first);
        }
        this.reactive = notebook.metadata.get("sphere") instanceof Map<?, ?> sphere
                        && Boolean.TRUE.equals(sphere.get("reactive"));
        OPEN.add(this);

        initUI();

        // Hover: the cell under the mouse, found from the event's own component
        // rather than by testing every cell of the notebook on each mouse move.
        AWTEventListener mouseHoverListener = event -> {
            if (event instanceof MouseEvent me
                    && (me.getID() == MouseEvent.MOUSE_MOVED || me.getID() == MouseEvent.MOUSE_ENTERED)) {
                Component src = me.getComponent();
                if (src == null || cellsContainer == null
                        || SwingUtilities.getWindowAncestor(src) != JupylabXeditor.this) {
                    return;
                }
                CellPanel target = src instanceof CellPanel cp ? cp
                                 : (CellPanel) SwingUtilities.getAncestorOfClass(CellPanel.class, src);
                if (target != hovered) {
                    if (hovered != null) {
                        hovered.setCellHoverActive(false);
                    }
                    hovered = target;
                    if (target != null) {
                        target.setCellHoverActive(true);
                    }
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseHoverListener,
                AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleQuit();
            }
            @Override
            public void windowClosed(WindowEvent e) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(mouseHoverListener);
                OPEN.remove(JupylabXeditor.this);
                for (javax.swing.Timer t : new javax.swing.Timer[] { analysisTimer, semanticTimer, dataflowTimer, usageTimer,
                                                                     flashTimer }) {
                    if (t != null) {
                        t.stop();
                    }
                }
                // A kernel outliving its notebook would hold an interpreter per
                // window that was ever opened. Stopped off the event thread: the
                // kernel gets two seconds to leave on its own before it is killed.
                if (executor != null) {
                    NotebookExecutor closing = executor;
                    offEdt(closing::shutdown);
                }
            }
        });

        if (cellsScrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar verticalBar = cellsScrollPane.getVerticalScrollBar();
                if (verticalBar != null) {
                    verticalBar.setValue(0);
                }
            });
        }
    }

    private Notebook loadNotebook(Path path) throws Exception {
        return NotebookIO.read(path);
    }

    private void saveNotebook() throws Exception {
        if (currentPath == null) throw new IOException("No file path specified. Use Save As.");
        NotebookIO.write(currentPath, notebook);
        setDirty(false);
        if (saveStatus != null) {
            saveStatus.setText("saved " + java.time.LocalTime.now().withNano(0));
            saveStatus.setForeground(palette.getTextSecondary());
            fitHeader();
        }
    }

    private void setDirty(boolean value) {
        if (dirty != value) {
            dirty = value;
            String name = currentPath == null ? "untitled" : currentPath.getFileName().toString();
            setTitle("JupylabXeditor - " + (value ? "● " : "") + name);
        }
    }

    // -----------------------------
    // UI Layout Initialization
    // -----------------------------
    private final void initUI() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.setForeground(palette.getTextPrimary());
        fileMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(e -> {
            if (!confirmDiscardIfDirty()) return;
            notebook = new Notebook();
            deltaBase.clear();
            insights.clear();
            lastMillis.clear();
            runSerial.clear();
            currentPath = null;
            setDirty(false);
            rebuildCellsUI();
        });
        fileMenu.add(newItem);

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> {
            try {
                syncUIToModel();
                if (currentPath == null) {
                    JFileChooser fc = new JFileChooser();
                    if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        currentPath = fc.getSelectedFile().toPath();
                    } else return;
                }
                saveNotebook();
            } catch (Exception ex) {
                com.sphere.core.JavaErrors.report("saving the notebook", ex);
                JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            }
        });
        fileMenu.add(saveItem);

        JMenuItem saveAsItem = new JMenuItem("Save As...");
        saveAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveAsItem.addActionListener(e -> {
            try {
                syncUIToModel();
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    currentPath = fc.getSelectedFile().toPath();
                    saveNotebook();
                }
            } catch (Exception ex) {
                com.sphere.core.JavaErrors.report("saving the notebook under a new name", ex);
                JOptionPane.showMessageDialog(this, "Save As failed: " + ex.getMessage());
            }
        });
        fileMenu.add(saveAsItem);

        fileMenu.addSeparator();
        JMenuItem quitItem = new JMenuItem("Quit");
        quitItem.addActionListener(e -> handleQuit());
        fileMenu.add(quitItem);

        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        // The actions on the left; the notebook's state in the middle; the kernel on the right, pinned to the
        // edge so that a narrow window cuts into the middle and never hides which kernel runs, nor its status.
        toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        // The actions as glyphs. Each tooltip names its action, then says what a click would do now:
        // how many cells, and how long they took when they last ran.
        JButton addCodeBtn = new NotebookIcons.ToolButton(NotebookIcons.addCode(18), "Add a code cell",
            () -> "<html><b>Add a code cell</b> at the end of the notebook</html>");
        addCodeBtn.addActionListener(e -> addNewCell("code"));
        toolbar.add(addCodeBtn);

        JButton addTextBtn = new NotebookIcons.ToolButton(NotebookIcons.addText(18), "Add a Markdown cell",
            () -> "<html><b>Add a Markdown cell</b> at the end of the notebook</html>");
        addTextBtn.addActionListener(e -> addNewCell("markdown"));
        toolbar.add(addTextBtn);
        toolbar.addSeparator();

        JButton runAllBtn = new NotebookIcons.ToolButton(NotebookIcons.runAll(18), "Run all cells",
            () -> "<html><b>Run all</b>: every code cell, top to bottom, or in the graph's order when the notebook is reactive"
                  + estimateLine(codeCells()) + "</html>");
        runAllBtn.addActionListener(e -> runAllCells(0, notebook.cells.size()));
        toolbar.add(runAllBtn);

        runStaleBtn = new NotebookIcons.ToolButton(NotebookIcons.runStale(18, () -> staleCount), "Run the stale cells",
            () -> "<html><b>Run stale</b>" + (staleCount > 0 ? " (" + staleCount + ")" : "")
                  + ": the cells that no longer match what they read: edited since they ran, or reading<br>"
                  + "a variable another cell changed after them -- and only those, in notebook order"
                  + estimateLine(staleCells()) + "</html>");
        runStaleBtn.addActionListener(e -> runStale());
        runStaleBtn.setEnabled(false);
        toolbar.add(runStaleBtn);

        stopBtn = new NotebookIcons.ToolButton(NotebookIcons.stop(18, this::isRunning), "Stop",
            () -> "<html><b>Stop</b>: interrupt the running cell; the cells waiting their turn are dropped</html>");
        stopBtn.addActionListener(e -> {
            cancelReactive();
            offEdt(() -> executor().interrupt());
        });
        toolbar.add(stopBtn);

        JButton restartBtn = new NotebookIcons.ToolButton(NotebookIcons.restart(18), "Restart the kernel",
            () -> "<html><b>Restart the kernel</b> and forget every variable<br>"
                  + "(Restart and Run All: the kernel menu, top right)</html>");
        restartBtn.addActionListener(e -> restartKernel(false));
        toolbar.add(restartBtn);
        toolbar.addSeparator();

        reactiveBtn = new JToggleButton("Reactive");
        reactiveBtn.setSelected(reactive);
        reactiveBtn.setToolTipText("<html><b>Reactive notebook</b>: the cells are a graph, not a list.<br>"
            + "Running a cell runs what it needs that is out of date, then itself, then every cell taking from it,<br>"
            + "in the graph's order wherever the cells sit. One home per variable; a name a cell stops defining<br>"
            + "leaves the kernel; objects changed in place (x.append, df[c] = ...) are followed. Saved with the notebook.</html>");
        reactiveBtn.addActionListener(e -> setReactive(reactiveBtn.isSelected()));
        toolbar.add(reactiveBtn);

        JButton colorsBtn = new JButton("Colors");
        colorsBtn.setToolTipText("The color code of names: scopes, roles, how each is used, what the kernel holds");
        colorsBtn.addActionListener(e -> showColorLegend(colorsBtn));
        toolbar.add(colorsBtn);

        varsToggle = new JToggleButton("Variables");
        varsToggle.setToolTipText("Show the kernel's live namespace");
        varsToggle.addActionListener(e -> {
            setSidePanel(varsToggle.isSelected() ? "Variables" : null);
            if (varsToggle.isSelected()) {
                offEdt(() -> executor().requestVariables());
            }
        });
        toolbar.add(varsToggle);

        flowToggle = new JToggleButton("Dataflow");
        flowToggle.setToolTipText("<html>The dataflow map: which cell takes which variable from which,<br>"
                                  + "and which cells are stale</html>");
        flowToggle.addActionListener(e -> setSidePanel(flowToggle.isSelected() ? "Dataflow" : null));
        toolbar.add(flowToggle);

        reproLabel = new JLabel(" ");
        reproLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 10));
        reproLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reproLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                revealFirstFragileCell();
            }
        });
        saveStatus = new JLabel(" ");
        saveStatus.setForeground(palette.getTextSecondary());
        saveStatus.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        kernelStatusLabel = new JLabel(" "); // kept for the messages; the chip shows them

        kernelChip = new KernelChip(new KernelChip.Host() {
            @Override
            public List<KernelSpecs.Spec> kernels() {
                return kernelSpecs;
            }

            @Override
            public KernelSpecs.Spec selected() {
                return selectedSpec;
            }

            @Override
            public void select(KernelSpecs.Spec spec) {
                switchKernel(spec);
            }

            @Override
            public void interrupt() {
                cancelReactive();
                offEdt(() -> executor().interrupt());
            }

            @Override
            public void restart(boolean runAll) {
                restartKernel(runAll);
            }

            @Override
            public void shutdownKernel() {
                offEdt(() -> executor().shutdown());
                setKernelMode(KernelMode.DISABLED);
                kernelChip.setState(KernelChip.State.OFF, "shut down: starts again on the next run");
            }
        });

        kernelIndicator = new JLabel(" ");
        kernelIndicator.setOpaque(true);
        kernelIndicator.setBorder(BorderFactory.createLineBorder(palette.getBackgroundSurface()));
        kernelIndicator.setPreferredSize(new Dimension(18, 18));
        kernelIndicator.setToolTipText("Kernel status");

        statePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        statePanel.setOpaque(false);
        statePanel.add(reproLabel);
        statePanel.add(saveStatus);

        kernelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 3));
        kernelPanel.setOpaque(false);
        kernelPanel.add(kernelChip);
        kernelPanel.add(kernelIndicator);

        JPanel north = new JPanel(new BorderLayout());
        northPanel = north;
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBorder()));
        north.add(kernelPanel, BorderLayout.EAST); // added first: drawn on top should a tiny window make them meet
        north.add(toolbar, BorderLayout.WEST);
        north.add(statePanel, BorderLayout.CENTER);
        north.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fitHeader();
            }
        });
        add(north, BorderLayout.NORTH);

        cellsContainer = new JPanel() {
            private static final long serialVersionUID = 1L;
            @Override
            public void scrollRectToVisible(Rectangle aRect) {
                // Focus moving between cells must not make the view jump; the caret
                // of the cell being typed in is followed explicitly (revealCaret).
            }
        };
        cellsContainer.setLayout(new BoxLayout(cellsContainer, BoxLayout.Y_AXIS));
        cellsContainer.setBorder(new EmptyBorder(10, 1, 10, 1));
        cellsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        cellsContainer.setFocusable(true);

        cellsScrollPane = new JScrollPane(cellsContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        cellsScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        dataflowMap = new DataflowMap(new DataflowMap.Actions() {
            @Override
            public void reveal(int codeIndex) {
                Cell c = codeCellAt(codeIndex);
                if (c != null) {
                    scrollToCell(notebook.cells.indexOf(c));
                }
            }

            @Override
            public void runDownstream(int codeIndex) {
                Cell c = codeCellAt(codeIndex);
                if (c != null) {
                    runWithDownstream(c);
                }
            }
        });
        JScrollPane flowScroll = new JScrollPane(dataflowMap);
        flowScroll.setBorder(BorderFactory.createEmptyBorder());
        flowScroll.getVerticalScrollBar().setUnitIncrement(18);
        sideTabs = new JTabbedPane();
        sideTabs.addTab("Variables", createVariablePanel());
        sideTabs.addTab("Dataflow", flowScroll);
        sideTabs.addChangeListener(e -> syncSideToggles());
        bodySplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cellsScrollPane, sideTabs);
        bodySplit.setResizeWeight(1.0);
        bodySplit.setBorder(null);
        bodySplit.setDividerSize(4);
        setSidePanel(null);
        add(bodySplit, BorderLayout.CENTER);

        analysisTimer = new javax.swing.Timer(700, e -> runAnalysis());
        analysisTimer.setRepeats(false);
        semanticTimer = new javax.swing.Timer(140, e -> rescanCodeCells());
        semanticTimer.setRepeats(false);
        dataflowTimer = new javax.swing.Timer(120, e -> recomputeDataflow());
        dataflowTimer.setRepeats(false);
        usageTimer = new javax.swing.Timer(4000, e -> askUsage());
        usageTimer.setInitialDelay(1500);

        rebuildCellsUI();

        // Wide enough for the whole top line as it will read once the notebook runs -- the longest state the
        // notebook can be in, the save time, the kernel with its version and memory -- on any screen allowing it.
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        kernelChip.setState(KernelChip.State.IDLE, "Python 3.14.3 · sphere_kernel 1.0.0");
        kernelChip.setUsage("9999 MB · 99% CPU");
        reproLabel.setText("⚠ 12 cells would not survive a restart");
        saveStatus.setText("saved 23:59:59");
        int needed = toolbar.getPreferredSize().width + statePanel.getPreferredSize().width
                     + kernelPanel.getPreferredSize().width + 32;
        reproLabel.setText(" ");
        saveStatus.setText(" ");
        kernelChip.setState(KernelChip.State.OFF, "looking for kernels...");
        int w = Math.min(usable.width - 16, Math.max(Math.max(900, Math.min(1200, usable.width - 200)), needed));
        int h = Math.max(600, Math.min(900, usable.height - 120));
        setMinimumSize(new Dimension(Math.min(w, 760), 420));
        setSize(w, h);
        setLocationRelativeTo(null);

        addComponentListener(new ComponentAdapter() {
            private int lastWidth = 0;

            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    int w = cellsScrollPane.getViewport().getWidth();
                    if (w <= 0) return;

                    if (w != lastWidth) {
                        lastWidth = w;
                        for (Component comp : cellsContainer.getComponents()) {
                            if (comp instanceof CellPanel cp) {
                                cp.updateViewportWidth(w);
                            }
                        }
                        cellsContainer.revalidate();
                        cellsContainer.repaint();
                    }
                });
            }
        });
        updateKernelIndicator();
        fillKernelSwitcher();
    }

    /** Lists the installed kernels (a few folders to read: done off the event thread). */
    private void fillKernelSwitcher() {
        offEdt(() -> {
            List<KernelSpecs.Spec> specs = executor().specs();
            KernelSpecs.Spec chosen = KernelSpecs.choose(specs, notebook.metadata);
            executor().setSpec(chosen);
            SwingUtilities.invokeLater(() -> {
                kernelSpecs = List.copyOf(specs);
                selectedSpec = chosen;
                kernelChip.setState(KernelChip.State.OFF, chosen == null ? "no kernel" : "starts on the first run");
            });
        });
    }

    /** The kernel chosen in the chip's menu: the running one stops, the new one starts now. */
    private void switchKernel(KernelSpecs.Spec spec) {
        if (spec == null) {
            selectedSpec = null;
            offEdt(() -> executor().shutdown());
            setKernelMode(KernelMode.DISABLED);
            kernelChip.setState(KernelChip.State.OFF, "no kernel");
            return;
        }
        selectedSpec = spec;
        setKernelMode(KernelMode.CONNECTING);
        kernelChip.setState(KernelChip.State.STARTING, "starting...");
        rememberKernel(spec);
        offEdt(() -> {
            executor().setSpec(spec);
            try {
                executor().ensureStarted();
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    setKernelMode(KernelMode.DISABLED);
                    kernelChip.setState(KernelChip.State.DEAD, "could not start: " + ex.getMessage());
                });
                AppLogger.error("JupyLab kernel: " + ex.getMessage());
            }
        });
    }

    /** Memory and CPU, from kernels that answer usage_request (Sphere's own does). */
    private void askUsage() {
        if (executor != null && executor.isAlive()) {
            offEdt(() -> executor.requestUsage());
        } else {
            kernelChip.setUsage("");
        }
    }

    /** As Jupyter does, a notebook remembers the kernel it was last run on. */
    private void rememberKernel(KernelSpecs.Spec spec) {
        rememberKernel(spec, true);
    }

    private void rememberKernel(KernelSpecs.Spec spec, boolean userChoice) {
        if (spec == null || spec.isSphereDriver()) {
            return;
        }
        Map<String, Object> ks = new LinkedHashMap<>();
        ks.put("display_name", spec.displayName());
        ks.put("language", spec.language());
        ks.put("name", spec.name());
        notebook.metadata.put("kernelspec", ks);
        if (userChoice) {
            setDirty(true);
        }
    }

    // -----------------------------
    // Cell Management Engine
    // -----------------------------
    private void rebuildCellsUI() {
        if (cellsContainer == null) return;
        // the order of the code cells first: each new cell's first scan reports to it
        semantics.retain(codeCells());
        scheduleDataflow();
        cellsContainer.removeAll();
        hovered = null;
        int idx = 0;
        CellPanel previousPanel = null;

        for (Cell c : notebook.cells) {
            CellPanel cp = new CellPanel(c, idx++);
            cp.setAlignmentX(Component.LEFT_ALIGNMENT);

            int viewportWidth = cellsScrollPane.getViewport().getWidth();
            if (viewportWidth <= 0) viewportWidth = getWidth();
            cp.updateViewportWidth(viewportWidth);
            cp.putClientProperty("cellIndex", cp.index);

            DraggableCellSupport.makeDraggable(cp, cellsContainer, notebook.cells, () -> {
                setDirty(true);
                rebuildCellsUI();
                scheduleAnalysis();
            });

            if (previousPanel != null) {
                if ("markdown".equals(previousPanel.cell.cell_type) && "markdown".equals(c.cell_type)) {
                    cellsContainer.add(Box.createVerticalStrut(4));
                } else {
                    cellsContainer.add(Box.createVerticalStrut(10));
                }
            }

            cellsContainer.add(cp);
            previousPanel = cp;
        }
        cellsContainer.revalidate();
        cellsContainer.repaint();
    }

    private void addNewCell(String type) {
        insertCellAt(notebook.cells.size(), type);
    }

    private void insertCellAt(int pos, String type) {
        Cell c = new Cell();
        c.cell_type = type;
        c.source = Arrays.asList("");
        notebook.cells.add(Math.max(0, Math.min(pos, notebook.cells.size())), c);
        setDirty(true);
        rebuildCellsUI();
        SwingUtilities.invokeLater(() -> scrollToCell(notebook.cells.indexOf(c)));
    }

    private void removeCellAt(int pos) {
        if (pos >= 0 && pos < notebook.cells.size()) {
            Cell gone = notebook.cells.get(pos);
            // Reactive: no hidden state -- what the cell defined leaves the kernel, and the cells reading it find out.
            List<Cell> readers = new ArrayList<>();
            Set<String> names = new TreeSet<>();
            if (reactive && "code".equals(gone.cell_type) && executor != null && executor.isAlive()) {
                names.addAll(ranBinds.getOrDefault(gone, Set.of()));
                PySemantics.Summary s = summaryOf(gone);
                if (s != null) {
                    names.addAll(s.bound().keySet());
                }
                int at = codeIndexOf(gone);
                if (at >= 0) {
                    for (int i : dataflow.downstream(at)) {
                        Cell c = codeCellAt(i);
                        if (c != null && c.execution_count != null) {
                            readers.add(c);
                        }
                    }
                }
            }
            notebook.cells.remove(pos);
            ranBinds.remove(gone);
            reactiveQueue.remove(gone);
            setDirty(true);
            rebuildCellsUI();
            scheduleAnalysis();
            if (!names.isEmpty()) {
                refreshSummaries();
                names.removeAll(NotebookDataflow.defined(semantics.summaries()));
                if (!names.isEmpty() && executor.forget(names)) {
                    names.forEach(peeks::remove);
                }
                if (!readers.isEmpty()) {
                    runReactive(readers);
                }
            }
        }
    }

    private void syncUIToModel() {
        List<Cell> newCells = new ArrayList<>();
        for (Component comp : cellsContainer.getComponents()) {
            if (comp instanceof CellPanel cp) {
                newCells.add(cp.toCell());
            }
        }
        notebook.cells = newCells;
    }

    private CellPanel panelAt(int cellIndex) {
        int count = 0;
        for (Component comp : cellsContainer.getComponents()) {
            if (comp instanceof CellPanel cp) {
                if (count == cellIndex) {
                    return cp;
                }
                count++;
            }
        }
        return null;
    }

    private void scrollToCell(int cellIndex) {
        CellPanel cp = panelAt(cellIndex);
        if (cp == null) return;
        cellsContainer.validate();
        reveal(cp.getBounds());
        cp.focusEditor();
    }

    /** Scrolls the notebook just enough for a rectangle of the cells container to be seen. */
    private void reveal(Rectangle r) {
        JViewport vp = cellsScrollPane.getViewport();
        Rectangle view = vp.getViewRect();
        int y = view.y;
        if (r.y < view.y) {
            y = Math.max(0, r.y - 12);
        } else if (r.y + r.height > view.y + view.height) {
            y = Math.min(r.y + r.height - view.height + 12, Math.max(0, cellsContainer.getHeight() - view.height));
            y = Math.min(y, Math.max(0, r.y - 12));
        }
        if (y != view.y) {
            vp.setViewPosition(new Point(view.x, Math.max(0, y)));
        }
    }

    /** Keeps the caret of the cell being typed in on screen. */
    private void revealCaret(javax.swing.text.JTextComponent editor) {
        if (!editor.isFocusOwner()) {
            return;
        }
        try {
            Rectangle caret = editor.modelToView2D(editor.getCaretPosition()).getBounds();
            Rectangle inContainer = SwingUtilities.convertRectangle(editor, caret, cellsContainer);
            inContainer.grow(0, 16);
            reveal(inContainer);
        } catch (javax.swing.text.BadLocationException | NullPointerException | IllegalArgumentException ignored) {
            // caret briefly outside the document
        }
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty) return true;
        int res = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Discard changes?",
                "Unsaved Changes",
                JOptionPane.YES_NO_OPTION);
        return res == JOptionPane.YES_OPTION;
    }

    private void handleQuit() {
        if (!dirty) {
            dispose();
            return;
        }
        int res = JOptionPane.showOptionDialog(this,
                "Save changes before quitting?",
                "Save Changes?",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new String[] {"Yes", "No", "Cancel"},
                "Yes");
        if (res == JOptionPane.CLOSED_OPTION || res == 2) {
            return;
        }
        if (res == 1) {
            dispose();
            return;
        }
        try {
            syncUIToModel();
            if (currentPath == null) {
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    currentPath = fc.getSelectedFile().toPath();
                } else {
                    return;
                }
            }
            saveNotebook();
            dispose();
        } catch (Exception ex) {
            com.sphere.core.JavaErrors.report("saving the notebook before closing", ex);
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    // -----------------------------
    // Cell editors
    // -----------------------------

    /** SphereCoder's code surface, sized to its text, inside a cell. */
    private CodeTextPane newEditor(String text, LanguageSpec spec, boolean wrap) {
        CodeTextPane p = new CodeTextPane();
        p.setFont(FontLoader.getTerminalFont(Font.PLAIN, 13));
        p.setTabWidth(4);
        p.setLineWrap(wrap);
        p.setWrapStyleWord(true);
        p.setScrollBeyondLastLine(false);
        p.setHighlightCurrentLine(false);
        p.setIndentGuidesVisible(spec == LanguageSpec.PYTHON);
        p.setText(text);
        p.setCaretPosition(0);
        p.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                p.setHighlightCurrentLine(true);
            }

            @Override
            public void focusLost(FocusEvent e) {
                p.setHighlightCurrentLine(false);
            }
        });
        p.addCaretListener(e -> SwingUtilities.invokeLater(() -> revealCaret(p)));
        ToolTipManager.sharedInstance().registerComponent(p);
        return p;
    }

    private static void bind(JComponent c, KeyStroke ks, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private static void bindUndo(CodeTextPane p, SmartUndoManager undo) {
        p.setAtomicRunner(undo::group);
        bind(p, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "nb-undo", () -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        });
        Runnable redo = () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        };
        bind(p, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "nb-redo", redo);
        bind(p, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "nb-redo2", redo);
    }

    /** Identifiers the notebook's cells contain: what completion offers before, or without, a kernel. */
    private Set<String> notebookWords() {
        Set<String> words = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}").matcher("");
        for (Cell c : notebook.cells) {
            if ("code".equals(c.cell_type)) {
                m.reset(NotebookIO.joinLines(c.source));
                while (m.find()) {
                    words.add(m.group());
                }
            }
        }
        return words;
    }

    // -----------------------------
    // Cell Component Wrapper Class
    // -----------------------------
    class CellPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        transient Cell cell;
        int index;
        CodeTextPane codeEditor;
        CodeTextPane markdownEditor;
        CodeTextPane rawEditor;
        transient SyntaxHighlighter highlighter;
        transient CompletionPopup completionPopup;
        JPanel bodyPanel;
        JPanel outputsPanel;
        JPanel headerPanel;
        JLabel countLbl;
        JPanel codeContainer;
        JButton runOverlayBtn;
        private final transient OutputRenderer renderer = new OutputRenderer(palette);
        private final javax.swing.Timer outputTimer;

        private boolean isEditing = false;
        private int currentViewportWidth = 800;
        private int lastEditorHeight = -1;

        private final int SIDEBAR_WIDTH = 45;
        private final transient Border normalBorder = BorderFactory.createLineBorder(palette.getJupyLabXedNBorder(), 1);
        private final transient Border activeBorder = BorderFactory.createMatteBorder(0, 3, 0, 0, palette.getJupyLabXedActive());
        private boolean isCurrentlyHovered = false;
        private boolean isRunning = false;
        private long lastElapsedMs = -1;
        private transient List<EditorDiagnostic> analysisDiagnostics = List.of();
        private transient List<EditorDiagnostic> semanticDiagnostics = List.of();
        private transient EditorDiagnostic errorDiagnostic;
        /** Where the cell stands in the notebook's dataflow: fresh, stale, edited since it ran... */
        private transient NotebookDataflow.Node dataflowNode;
        private transient JPanel gutter;
        /** A Ctrl+hover link is shown on a name. */
        private boolean linked;

        // Between the code and the outputs: the widgets of the cell's bonds ("x = 5  # @slider 0..10"),
        // then a notice line (why the cell ran by itself, why it cannot run).
        private transient List<NotebookBonds.Bond> bonds = List.of();
        private JPanel bondBar;
        private JPanel bondRow;
        private final List<JComponent> bondWidgets = new ArrayList<>();
        private final List<JLabel> bondValues = new ArrayList<>();
        /** The bar is writing a literal: that change of the text is its own. */
        private boolean bondWriting;
        /** The widgets follow the text: their listeners must not write it back. */
        private boolean bondSyncing;
        private final javax.swing.Timer bondTimer = new javax.swing.Timer(250, e -> refreshBonds());
        private final javax.swing.Timer bondRunTimer = new javax.swing.Timer(140, e -> runBonded(cell));
        private JPanel noticeRow;
        private int noticeKind = -1;
        private String noticeKey;
        /** Holds the bond bar and the notice: changed alone, so the editor above keeps the keyboard. */
        private JPanel extras;
        /** Under the notice: what the doctor says of the cell's error, or what its last run changed. */
        private JPanel insightRow;
        private transient Insight shownInsight;

        {
            bondTimer.setRepeats(false);
            bondRunTimer.setRepeats(false);
        }

        private JEditorPane previewPaneReference;

        CellPanel(Cell cell, int index) {
            this.cell = cell;
            this.index = index;
            // Outputs arriving faster than the screen refreshes are drawn once per frame.
            this.outputTimer = new javax.swing.Timer(33, e -> refreshOutputs());
            this.outputTimer.setRepeats(false);

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);

            if (cellsScrollPane != null && cellsScrollPane.getViewport().getWidth() > 0) {
                this.currentViewportWidth = cellsScrollPane.getViewport().getWidth();
            }

            headerPanel = (JPanel) createHeader();
            headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            headerPanel.setOpaque(false);

            bodyPanel = new JPanel(new BorderLayout());
            bodyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            bodyPanel.setOpaque(false);
            bodyPanel.add(createBody(), BorderLayout.CENTER);

            codeContainer = new JPanel();
            codeContainer.setLayout(new BoxLayout(codeContainer, BoxLayout.Y_AXIS));
            codeContainer.setOpaque(true);
            codeContainer.setBackground(palette.getAccent());
            codeContainer.add(headerPanel);
            codeContainer.add(bodyPanel);

            if ("code".equals(cell.cell_type) && !cell.outputs.isEmpty()) {
                renderer.render(cell.outputs);
                outputsPanel = (JPanel) renderer.component();
            }

            if ("code".equals(cell.cell_type)) {
                countLbl = new JLabel();
                countLbl.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                countLbl.setHorizontalAlignment(SwingConstants.RIGHT);
                countLbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
                updateCounterLabel();

                runOverlayBtn = new JButton("▶") {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String getToolTipText(MouseEvent event) {
                        return runTooltip(CellPanel.this);
                    }
                };
                runOverlayBtn.setFont(FontLoader.getGlobalFont(Font.PLAIN, 14));
                runOverlayBtn.setForeground(palette.getJupyLabXedLbutton());
                runOverlayBtn.setContentAreaFilled(false);
                runOverlayBtn.setBorderPainted(false);
                runOverlayBtn.setFocusPainted(false);
                runOverlayBtn.setOpaque(false);
                runOverlayBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                runOverlayBtn.setVisible(false);
                runOverlayBtn.setMargin(new Insets(0, 0, 0, 0));
                runOverlayBtn.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
                runOverlayBtn.setToolTipText("Run (Ctrl+Enter); Shift+Enter runs and moves on");
                runOverlayBtn.addActionListener(e -> runPythonAsync());
            }

            if (codeEditor != null) {
                bonds = NotebookBonds.parse(codeEditor.getText());
                buildBondBar();
            }
            rebuildCellStructure();

            if ("markdown".equals(cell.cell_type)) {
                codeContainer.setBorder(null);
                codeContainer.setOpaque(false);
                headerPanel.setVisible(false);

                SwingUtilities.invokeLater(() -> {
                    this.isEditing = true;
                    switchToPreviewMode();
                });
            } else {
                codeContainer.setBorder(normalBorder);
                headerPanel.setVisible(false);
                setupFocusIndicator();
            }
            Insight kept = insights.get(cell);
            if (kept != null) {
                showInsight(kept);
            }
        }

        void focusEditor() {
            if (codeEditor != null) codeEditor.requestFocusInWindow();
            else if (markdownEditor != null) {
                switchToEditMode();
            } else if (rawEditor != null) rawEditor.requestFocusInWindow();
        }

        private void setHeaderVisible(boolean visible) {
            if (headerPanel != null && headerPanel.isVisible() != visible) {
                headerPanel.setVisible(visible);
                revalidate();
                repaint();
            }
        }

        public void setCellHoverActive(boolean active) {
            if (this.isCurrentlyHovered == active) return;
            this.isCurrentlyHovered = active;

            if (active) {
                if ("code".equals(cell.cell_type)) {
                    if (codeContainer != null) codeContainer.setBorder(activeBorder);
                    if (runOverlayBtn != null) runOverlayBtn.setVisible(true);
                }
            } else {
                boolean insideEditWindow = "markdown".equals(cell.cell_type) && isEditing;
                boolean focused = codeEditor != null && codeEditor.isFocusOwner();
                if (!insideEditWindow) {
                    if (codeContainer != null && !focused) {
                        codeContainer.setBorder("code".equals(cell.cell_type) ? normalBorder : null);
                    }
                    if (runOverlayBtn != null) runOverlayBtn.setVisible(false);
                }
            }
            repaint();
        }

        /**
         * Outputs changed: drawn on the next frame, however many chunks arrive
         * meanwhile. A frame waits at least four times what the last refresh cost,
         * so a console that grew expensive to draw takes at most a fifth of the
         * event thread, and typing elsewhere stays fluid while a cell prints.
         */
        void scheduleOutputs() {
            if (!outputTimer.isRunning()) {
                outputTimer.setInitialDelay((int) Math.max(33, Math.min(500, 4 * lastRefreshMillis)));
                outputTimer.start();
            }
        }

        private long lastRefreshMillis;

        /**
         * Brings the outputs on screen up to date: appended text is appended, and
         * the cell is laid out again only when the outputs appear or vanish.
         */
        void refreshOutputs() {
            if (!"code".equals(cell.cell_type)) {
                return;
            }
            outputTimer.stop();
            long started = System.nanoTime();
            boolean had = outputsPanel != null;
            renderer.render(cell.outputs);
            boolean has = !cell.outputs.isEmpty();
            if (had != has) {
                outputsPanel = has ? (JPanel) renderer.component() : null;
                rebuildCellStructure();
            }
            updateCounterLabel();
            revalidate();
            repaint();
            // the layout and paint this refresh causes come later; validate now to count them
            RepaintManager.currentManager(this).validateInvalidComponents();
            lastRefreshMillis = (System.nanoTime() - started) / 1_000_000L;
        }

        /** The counter shows [*] while the cell holds the kernel, as Jupyter does. */
        void setRunning(boolean running) {
            this.isRunning = running;
            if (running) {
                this.lastElapsedMs = -1;
            }
            updateCounterLabel();
        }

        void setElapsed(long millis) {
            this.lastElapsedMs = millis;
            updateCounterLabel();
        }

        private void updateCounterLabel() {
            if (countLbl == null) {
                return;
            }
            NotebookDataflow.Node n = dataflowNode;
            boolean outdated = !isRunning && n != null && n.outdated();
            countLbl.setText(isRunning ? "[*]"
                : cell.execution_count == null ? "[ ]" : "[" + cell.execution_count + "]");
            countLbl.setForeground(isRunning ? palette.getJupyLabXedActive()
                                 : outdated ? SemanticStyle.staleColor() : palette.getJupyCellCounter());
            String timing = lastElapsedMs < 0 ? null : "Last run: " + formatElapsed(lastElapsedMs);
            countLbl.setToolTipText(!outdated ? timing
                : "<html><b>" + (n.status() == NotebookDataflow.Status.EDITED ? "Edited" : "Stale") + "</b>: "
                  + escape(n.why()) + "<br>Ctrl+Shift+Enter runs it and every cell that takes from it"
                  + (timing == null ? "" : "<br>" + timing) + "</html>");
            if (gutter != null) {
                gutter.setToolTipText(outdated ? countLbl.getToolTipText() : null);
                gutter.repaint();
            }
        }

        /** The cell's place in the dataflow changed: its counter and its stripe follow. */
        void setDataflow(NotebookDataflow.Node node) {
            dataflowNode = node;
            updateCounterLabel();
        }

        void setAnalysisDiagnostics(List<EditorDiagnostic> list) {
            analysisDiagnostics = list == null ? List.of() : list;
            pushDiagnostics();
        }

        void setErrorDiagnostic(EditorDiagnostic d) {
            errorDiagnostic = d;
            pushDiagnostics();
        }

        /** What scope resolution finds in the cell itself: builtins hidden, mutable defaults, late-bound loop variables. */
        void setSemanticDiagnostics(List<EditorDiagnostic> list) {
            List<EditorDiagnostic> now = list == null ? List.of() : list;
            if (!sameDiagnostics(now, semanticDiagnostics)) {
                semanticDiagnostics = now;
                pushDiagnostics();
            }
        }

        private void pushDiagnostics() {
            if (codeEditor == null) {
                return;
            }
            List<EditorDiagnostic> all = new ArrayList<>(analysisDiagnostics);
            all.addAll(semanticDiagnostics);
            if (errorDiagnostic != null) {
                all.add(errorDiagnostic);
            }
            codeEditor.setDiagnostics(all);
        }

        private void rebuildCellStructure() {
            removeAll();
            if ("code".equals(cell.cell_type)) {
                JPanel codeRowWrapper = new JPanel(new BorderLayout());
                codeRowWrapper.setOpaque(false);
                codeRowWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                codeRowWrapper.add(codeContainer, BorderLayout.CENTER);

                // The left column: run button, counter, and an amber stripe while the cell is out of date.
                JPanel leftSidebar = new JPanel(new BorderLayout()) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        NotebookDataflow.Node n = dataflowNode;
                        if (n != null && !isRunning && n.outdated()) {
                            Graphics2D g2 = (Graphics2D) g.create();
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g2.setColor(SemanticStyle.staleColor());
                            g2.fill(new java.awt.geom.RoundRectangle2D.Float(getWidth() - 4f, 3f, 3f,
                                                                               Math.max(4, getHeight() - 6), 3f, 3f));
                            g2.dispose();
                        }
                    }
                };
                gutter = leftSidebar;
                leftSidebar.setOpaque(false);
                leftSidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
                leftSidebar.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                if (runOverlayBtn != null) leftSidebar.add(runOverlayBtn, BorderLayout.NORTH);
                if (countLbl != null) leftSidebar.add(countLbl, BorderLayout.SOUTH);

                codeRowWrapper.add(leftSidebar, BorderLayout.WEST);
                add(codeRowWrapper);
                add(extras());
                if (outputsPanel != null) {
                    JPanel outputRowWrapper = new JPanel(new BorderLayout());
                    outputRowWrapper.setOpaque(false);
                    outputRowWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                    outputRowWrapper.add(outputsPanel, BorderLayout.CENTER);

                    JPanel outputSpacer = new JPanel();
                    outputSpacer.setOpaque(false);
                    outputSpacer.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
                    outputRowWrapper.add(outputSpacer, BorderLayout.WEST);

                    add(outputRowWrapper);
                }
            } else {
                add(codeContainer);
            }
        }

        private void switchToEditMode() {
            if (!"markdown".equals(cell.cell_type) || isEditing) {
                if (markdownEditor != null) markdownEditor.requestFocusInWindow();
                return;
            }
            isEditing = true;
            codeContainer.setOpaque(true);
            codeContainer.setBorder(activeBorder);
            setHeaderVisible(true);

            if (bodyPanel.getComponent(0) instanceof JPanel cardPanel) {
                CardLayout cl = (CardLayout) cardPanel.getLayout();
                cl.show(cardPanel, "EDITOR");
                if (markdownEditor != null) {
                    markdownEditor.requestFocusInWindow();
                }
            }
            revalidate();
            repaint();
        }

        private void switchToPreviewMode() {
            if (!"markdown".equals(cell.cell_type)) return;
            isEditing = false;
            codeContainer.setOpaque(false);
            codeContainer.setBorder(null);
            setHeaderVisible(false);

            if (bodyPanel.getComponent(0) instanceof JPanel cardPanel) {
                CardLayout cl = (CardLayout) cardPanel.getLayout();
                if (markdownEditor != null && previewPaneReference != null) {
                    previewPaneReference.setText(JupyLabMarkdown.toHtml(markdownEditor.getText()));
                    previewPaneReference.setCaretPosition(0);
                }
                cl.show(cardPanel, "PREVIEW");
            }

            bodyPanel.revalidate();
            bodyPanel.repaint();
            if (cellsContainer != null) {
                cellsContainer.revalidate();
                cellsContainer.repaint();
            }
        }

        public void updateViewportWidth(int width) {
            if (width > 0) {
                this.currentViewportWidth = width;
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int targetWidth = (currentViewportWidth > 100) ? currentViewportWidth - 20 : 800;
            int totalHeight = 0;
            if (headerPanel != null && headerPanel.isVisible()) {
                totalHeight += headerPanel.getPreferredSize().height;
            }
            if (bodyPanel != null) {
                int bodyHeight = bodyPanel.getPreferredSize().height;
                if ("markdown".equals(cell.cell_type) && isEditing) {
                    bodyHeight = Math.max(bodyHeight, 65);
                } else if ("code".equals(cell.cell_type)) {
                    bodyHeight = Math.max(bodyHeight, 32);
                }
                totalHeight += bodyHeight;
            }
            if (outputsPanel != null && outputsPanel.isVisible()) {
                totalHeight += outputsPanel.getPreferredSize().height;
            }
            if (extras != null && extras.getComponentCount() > 0) {
                totalHeight += extras.getPreferredSize().height;
            }
            totalHeight += 6;
            return new Dimension(targetWidth, totalHeight);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension pref = getPreferredSize();
            int minWidth = (currentViewportWidth > 100) ? currentViewportWidth - 20 : 200;
            return new Dimension(minWidth, pref.height);
        }

        private void setupFocusIndicator() {
            FocusListener highlightListener = new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    codeContainer.setBorder(activeBorder);
                    setHeaderVisible(true);
                }
                @Override
                public void focusLost(FocusEvent e) {
                    if (!isCurrentlyHovered) {
                        codeContainer.setBorder(normalBorder);
                    }
                    if (!e.isTemporary()) {
                        setHeaderVisible(false);
                    }
                }
            };
            if (codeEditor != null) codeEditor.addFocusListener(highlightListener);
            if (rawEditor != null) rawEditor.addFocusListener(highlightListener);
        }

        private JComponent createHeader() {
            JPanel p = new JPanel(new BorderLayout());
            String typeName;
            if (cell.cell_type == null || "code".equals(cell.cell_type)) {
                typeName = "CODE";
                p.setBackground(palette.getJupyUniLayoutCode());
            } else if ("markdown".equals(cell.cell_type)) {
                typeName = "TEXT";
                p.setBackground(palette.getJupyUniLayoutMark());
            } else {
                typeName = cell.cell_type.toUpperCase();
                p.setBackground(palette.getJupyUnilayoutDefo());
            }
            p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getJupyMatteSep()));

            JLabel type = new JLabel(typeName);
            type.setFont(FontLoader.getGlobalFont(Font.BOLD, 10));
            type.setForeground(palette.getTextSecondary());

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
            left.setOpaque(false);
            left.add(type);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
            right.setOpaque(false);

            java.util.function.Consumer<JButton> applyFlatAesthetic = (btn) -> {
                btn.setContentAreaFilled(false);
                btn.setBorderPainted(false);
                btn.setFocusPainted(false);
                btn.setFocusable(false);
                btn.setOpaque(false);
                btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                btn.setMargin(new Insets(4, 6, 4, 6));
                btn.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        btn.setContentAreaFilled(true);
                        btn.setBackground(palette.getJupyDarkTranslus());
                    }
                    @Override
                    public void mouseExited(MouseEvent e) {
                        btn.setContentAreaFilled(false);
                    }
                });
            };

            JButton execBtn = new JButton(com.sphere.utils.IconManager.getIcon("execution.png"));
            applyFlatAesthetic.accept(execBtn);
            if ("code".equals(cell.cell_type)) {
                execBtn.setToolTipText("Run (Ctrl+Enter)");
                execBtn.addActionListener(e -> runPythonAsync());
            } else {
                execBtn.setToolTipText("Render Preview");
                execBtn.addActionListener(e -> switchToPreviewMode());
            }
            right.add(execBtn);

            if ("code".equals(cell.cell_type)) {
                JButton gatherBtn = new JButton(NotebookIcons.gather(16, () -> NotebookIcons.Tone.NORMAL));
                applyFlatAesthetic.accept(gatherBtn);
                gatherBtn.setToolTipText("<html><b>Gather</b>: the cells this one needs, and no others, in an order "
                                         + "that runs<br>-- as a new notebook, or as a Python script</html>");
                gatherBtn.addActionListener(e -> showGatherMenu(cell, gatherBtn));
                right.add(gatherBtn);
            }

            JButton editBtn = new JButton(com.sphere.utils.IconManager.getIcon("edit_cell.png"));
            applyFlatAesthetic.accept(editBtn);
            editBtn.setToolTipText("Edit Cell");
            editBtn.addActionListener(e -> switchToEditMode());
            right.add(editBtn);

            JButton addNextBtn = new JButton(com.sphere.utils.IconManager.getIcon("add_down.png"));
            applyFlatAesthetic.accept(addNextBtn);
            addNextBtn.setToolTipText("Add next cell");
            addNextBtn.addActionListener(e -> insertCellAt(findMyIndex() + 1, "code"));
            right.add(addNextBtn);

            JButton addPrevBtn = new JButton(com.sphere.utils.IconManager.getIcon("add_up.png"));
            applyFlatAesthetic.accept(addPrevBtn);
            addPrevBtn.setToolTipText("Add previous cell");
            addPrevBtn.addActionListener(e -> insertCellAt(Math.max(0, findMyIndex()), "code"));
            right.add(addPrevBtn);

            JButton modifyBtn = new JButton(com.sphere.utils.IconManager.getIcon("switch_text.png"));
            applyFlatAesthetic.accept(modifyBtn);
            modifyBtn.setToolTipText("Switch cell type");
            modifyBtn.addActionListener(e -> {
                String[] opts = {"code", "markdown", "raw"};
                String cur = cell.cell_type == null ? "code" : cell.cell_type;
                String sel = (String) JOptionPane.showInputDialog(this, "Select cell type:", "Modify Cell", JOptionPane.PLAIN_MESSAGE, null, opts, cur);
                if (sel != null && !sel.equals(cur)) {
                    cell.cell_type = sel;
                    setDirty(true);
                    rebuildCellsUI();
                    scheduleAnalysis();
                }
            });
            right.add(modifyBtn);

            JButton removeBtn = new JButton(com.sphere.utils.IconManager.getIcon("trash.png"));
            applyFlatAesthetic.accept(removeBtn);
            removeBtn.setToolTipText("Remove cell");
            removeBtn.addActionListener(e -> removeCellAt(findMyIndex()));
            right.add(removeBtn);

            p.add(left, BorderLayout.WEST);
            p.add(right, BorderLayout.EAST);
            return p;
        }

        private int findMyIndex() {
            Component[] comps = cellsContainer.getComponents();
            int cellCount = 0;
            for (Component comp : comps) {
                if (comp instanceof CellPanel) {
                    if (comp == this) return cellCount;
                    cellCount++;
                }
            }
            return -1;
        }

        /** A text pane sized to its text in a scroll pane that gives the wheel to the notebook. */
        private JScrollPane wrap(CodeTextPane pane, boolean horizontal) {
            JScrollPane sp = new JScrollPane(pane, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                horizontal ? JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED : JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(BorderFactory.createEmptyBorder());
            sp.setViewportBorder(null);
            sp.setWheelScrollingEnabled(false);
            sp.addMouseWheelListener(e -> {
                if (e.isShiftDown() && horizontal) {
                    JScrollBar bar = sp.getHorizontalScrollBar();
                    bar.setValue(bar.getValue() + e.getWheelRotation() * 30);
                    return;
                }
                cellsScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(sp, e, cellsScrollPane));
            });
            return sp;
        }

        /** Re-lays the notebook out only when the editor changed height, not on every keystroke. */
        private void followHeight(CodeTextPane pane) {
            int h = pane.getPreferredSize().height;
            if (h != lastEditorHeight) {
                lastEditorHeight = h;
                revalidate();
                if (cellsContainer != null) {
                    cellsContainer.revalidate();
                }
            }
        }

        private JComponent createBody() {
            String src = NotebookIO.joinLines(cell.source);

            if ("markdown".equals(cell.cell_type)) {
                JPanel cardPanel = new JPanel(new CardLayout()) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Dimension getPreferredSize() {
                        for (Component comp : getComponents()) {
                            if (comp.isVisible()) {
                                return comp.getPreferredSize();
                            }
                        }
                        return new Dimension(0, 0);
                    }
                    @Override
                    public Dimension getMaximumSize() {
                        for (Component comp : getComponents()) {
                            if (comp.isVisible()) {
                                return new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height);
                            }
                        }
                        return new Dimension(Integer.MAX_VALUE, 0);
                    }
                };
                cardPanel.setOpaque(false);
                CardLayout cl = (CardLayout) cardPanel.getLayout();

                markdownEditor = newEditor(src, LanguageSpec.MARKDOWN, true);
                new SyntaxHighlighter(markdownEditor, LanguageSpec.MARKDOWN);
                SmartUndoManager undo = new SmartUndoManager(markdownEditor.getDocument());
                // List items continue on Enter; a quote after a letter is an apostrophe,
                // so "l'énergie" no longer grows a stray closing quote.
                new AutoIndenter(markdownEditor, LanguageSpec.MARKDOWN, 2);
                bindUndo(markdownEditor, undo);
                bind(markdownEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nb-leave", () -> {
                    switchToPreviewMode();
                    cellsContainer.requestFocusInWindow();
                });
                bind(markdownEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "nb-render-next", () -> {
                    switchToPreviewMode();
                    focusNext(this, true);
                });
                bind(markdownEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "nb-render", this::switchToPreviewMode);

                markdownEditor.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        Component opposite = e.getOppositeComponent();
                        if (opposite == null) return;
                        Component cellAncestor = SwingUtilities.getAncestorOfClass(CellPanel.class, opposite);
                        if (cellAncestor != null && cellAncestor != CellPanel.this) {
                            switchToPreviewMode();
                        }
                    }
                });
                markdownEditor.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                    cell.source = markdownEditor.getText();
                    setDirty(true);
                    followHeight(markdownEditor);
                }));

                JScrollPane editorScrollPane = wrap(markdownEditor, false);

                previewPaneReference = new JEditorPane();
                previewPaneReference.setContentType("text/html");
                previewPaneReference.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
                previewPaneReference.setEditable(false);
                previewPaneReference.setOpaque(false);
                previewPaneReference.setBorder(null);
                previewPaneReference.setMargin(new Insets(0, 0, 0, 0));
                previewPaneReference.setText(JupyLabMarkdown.toHtml(src));

                JScrollPane previewScrollPane = new JScrollPane(previewPaneReference);
                previewScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                previewScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
                previewScrollPane.setBorder(null);
                previewScrollPane.setViewportBorder(null);
                previewScrollPane.setOpaque(false);
                previewScrollPane.getViewport().setOpaque(false);
                previewScrollPane.setWheelScrollingEnabled(false);
                previewScrollPane.addMouseWheelListener(e ->
                    cellsScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(previewScrollPane, e, cellsScrollPane)));

                cardPanel.add(previewScrollPane, "PREVIEW");
                cardPanel.add(editorScrollPane, "EDITOR");
                cl.show(cardPanel, "PREVIEW");

                previewPaneReference.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() < 2) return;
                        // A double click edits, as in JupyterLab; a single one follows links and selects.
                        final int scrollValue = cellsScrollPane.getVerticalScrollBar().getValue();
                        switchToEditMode();
                        markdownEditor.requestFocusInWindow();
                        SwingUtilities.invokeLater(() -> cellsScrollPane.getVerticalScrollBar().setValue(scrollValue));
                    }
                });

                this.putClientProperty("triggerPreview", (Runnable) this::switchToPreviewMode);
                this.putClientProperty("triggerEdit", (Runnable) this::switchToEditMode);
                return cardPanel;
            }

            if ("code".equals(cell.cell_type)) {
                codeEditor = newEditor(src, LanguageSpec.PYTHON, false);
                highlighter = new SyntaxHighlighter(codeEditor, LanguageSpec.PYTHON);
                highlighter.setNameClassifier(liveKindOf);
                // Names resolved scope by scope, the notebook and the kernel around them (see NotebookSemantics)
                highlighter.setSemanticEnabled(semanticColors);
                highlighter.setSemanticContext(semantics.contextFor(cell));
                final CellPanel self = this;
                highlighter.addListener(snapshot -> onSemanticScan(self, snapshot));
                codeEditor.setTooltipProvider(event -> semanticTooltip(self, event));
                SmartUndoManager undo = new SmartUndoManager(codeEditor.getDocument());
                AutoIndenter indenter = new AutoIndenter(codeEditor, LanguageSpec.PYTHON, 4);
                indenter.setAutoClose(true);
                new BracketMatcher(codeEditor);
                bindUndo(codeEditor, undo);
                // Escape leaves the cell, keeping what was typed: it used to throw the edits away.
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nb-leave", () -> {
                    inspectPopup.hide();
                    cellsContainer.requestFocusInWindow();
                });
                KernelCompletion completion = new KernelCompletion(codeEditor,
                    (code, caret) -> requestCompletion(this, code, caret), JupylabXeditor.this::notebookWords);
                completionPopup = new CompletionPopup(codeEditor, completion);
                completionPopup.setAutoTrigger(true);
                completion.setPopup(completionPopup);
                codeEditor.putClientProperty(KernelCompletion.class, completion);
                indenter.setTabInterceptor(() -> completionPopup.acceptIfVisible() || tabCompletes(),
                                           this::shiftTabInspects);
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "nb-run-next",
                     () -> runCell(this, RunThen.NEXT));
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "nb-run",
                     () -> runCell(this, RunThen.STAY));
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "nb-run-insert",
                     () -> runCell(this, RunThen.INSERT));
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK), "nb-comment",
                     () -> EditorActions.toggleLineComment(codeEditor));
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_DIVIDE, InputEvent.CTRL_DOWN_MASK), "nb-comment2",
                     () -> EditorActions.toggleLineComment(codeEditor));
                // The dataflow: this cell, then every cell that takes from it, in notebook order.
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                     "nb-run-downstream", () -> runWithDownstream(cell));
                // Navigation across cells: F12 or Ctrl+click goes where a name is defined, in this cell or another.
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "nb-definition",
                     () -> goToDefinition(self, codeEditor.getCaretPosition()));
                // F2: the symbol under the caret renamed -- a notebook variable in every cell and in the kernel.
                bind(codeEditor, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "nb-rename", () -> renameSymbol(self));
                codeEditor.addCaretListener(e -> SwingUtilities.invokeLater(() -> updateSymbolMarks(self)));
                codeEditor.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        if (!e.isTemporary()) {
                            SwingUtilities.invokeLater(JupylabXeditor.this::clearMarksUnlessEditing);
                        }
                    }
                });
                MouseAdapter navigation = new MouseAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        ctrlLink(self, e);
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        clearLink(self);
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (e.isControlDown() && SwingUtilities.isLeftMouseButton(e)
                            && findDefinition(self, codeEditor.viewToModel2D(e.getPoint())) != null) {
                            e.consume();
                            clearLink(self);
                            goToDefinition(self, codeEditor.viewToModel2D(e.getPoint()));
                        }
                    }
                };
                codeEditor.addMouseListener(navigation);
                codeEditor.addMouseMotionListener(navigation);

                codeEditor.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                    cell.source = codeEditor.getText();
                    setDirty(true);
                    errorDiagnostic = null;
                    if (shownInsight != null && shownInsight.doctor()) {
                        setInsight(cell, null); // the advice was about the code as it ran
                    }
                    followHeight(codeEditor);
                    scheduleAnalysis();
                    if (bondWriting) {
                        bonds = NotebookBonds.parse(codeEditor.getText()); // its own literal: the widgets stay
                    } else {
                        bondTimer.restart();
                        if (noticeKind == 0) {
                            clearNotice(); // why it last ran by itself: no longer about this code
                        }
                    }
                }));
                return wrap(codeEditor, true);
            }

            rawEditor = newEditor(src, LanguageSpec.NONE, true);
            SmartUndoManager undo = new SmartUndoManager(rawEditor.getDocument());
            bindUndo(rawEditor, undo);
            bind(rawEditor, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nb-leave",
                 () -> cellsContainer.requestFocusInWindow());
            rawEditor.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                cell.source = rawEditor.getText();
                setDirty(true);
                followHeight(rawEditor);
            }));
            return wrap(rawEditor, false);
        }

        /** Tab after a name, a dot or an open bracket completes; anywhere else it indents. */
        private boolean tabCompletes() {
            int caret = codeEditor.getCaretPosition();
            if (caret == 0 || codeEditor.getSelectionStart() != codeEditor.getSelectionEnd()) {
                return false;
            }
            try {
                char before = codeEditor.getText(caret - 1, 1).charAt(0);
                if (Character.isLetterOrDigit(before) || before == '_' || before == '.' || before == '['
                        || before == '\'' || before == '"' || before == '/' || before == '\\') {
                    completionPopup.show();
                    return true;
                }
            } catch (javax.swing.text.BadLocationException ignored) {
                // caret at an edge
            }
            return false;
        }

        /** Shift+Tab on a name, or just inside a call's parenthesis, shows its documentation. */
        private boolean shiftTabInspects() {
            int caret = codeEditor.getCaretPosition();
            try {
                char before = caret > 0 ? codeEditor.getText(caret - 1, 1).charAt(0) : ' ';
                int[] word = codeEditor.wordAt(Math.max(0, caret - 1));
                if (before == '(' || Character.isLetterOrDigit(before) || before == '_'
                        || word != null && word[1] > word[0]) {
                    requestInspection(codeEditor, codeEditor.getText(), caret);
                    return true;
                }
            } catch (javax.swing.text.BadLocationException ignored) {
                // nothing under the caret
            }
            return false;
        }

        // ---- Between code and outputs: bonds and notices ----------------------------------

        private JPanel extras() {
            if (extras == null) {
                extras = new JPanel();
                extras.setLayout(new BoxLayout(extras, BoxLayout.Y_AXIS));
                extras.setOpaque(false);
                extras.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            layoutExtras();
            return extras;
        }

        private void layoutExtras() {
            if (extras == null) {
                return;
            }
            extras.removeAll();
            bondRow = bondBar == null ? null : indent(bondBar);
            if (bondRow != null) {
                extras.add(bondRow);
            }
            if (noticeRow != null) {
                extras.add(noticeRow);
            }
            if (insightRow != null) {
                extras.add(insightRow);
            }
            extras.revalidate();
            extras.repaint();
            revalidate();
            repaint();
            if (cellsContainer != null) {
                cellsContainer.revalidate();
            }
        }

        /** A row starting where the code starts, right of the run button and the counter. */
        private JPanel indent(JComponent c) {
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel spacer = new JPanel();
            spacer.setOpaque(false);
            spacer.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
            row.add(spacer, BorderLayout.WEST);
            row.add(c, BorderLayout.CENTER);
            return row;
        }

        /**
         * A line under the code: why the cell ran by itself (kind 0), what holds it back (1), why it
         * cannot run (2), with up to two actions that fix it.
         */
        void showNotice(String text, int kind, String actionLabel, Runnable action, String action2Label, Runnable action2) {
            if (!"code".equals(cell.cell_type)) {
                return;
            }
            if (text == null || text.isBlank()) {
                clearNotice();
                return;
            }
            String key = kind + "|" + text + "|" + actionLabel + "|" + action2Label;
            if (key.equals(noticeKey) && noticeRow != null) {
                return;
            }
            noticeKey = key;
            noticeKind = kind;
            JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
            line.setOpaque(false);
            Font f = FontLoader.getGlobalFont(Font.PLAIN, 11);
            String mark = kind == 0 ? glyph(f, "↻ ", "> ") : glyph(f, "⚠ ", "(!) ");
            int width = Math.max(240, currentViewportWidth - SIDEBAR_WIDTH - 260);
            JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + escape(mark + text) + "</div></html>");
            label.setFont(f);
            label.setForeground(kind == 2 ? palette.getError() : kind == 1 ? SemanticStyle.staleColor() : palette.getTextSecondary());
            line.add(label);
            if (actionLabel != null && action != null) {
                line.add(linkButton(actionLabel, action));
            }
            if (action2Label != null && action2 != null) {
                line.add(linkButton(action2Label, action2));
            }
            noticeRow = indent(line);
            layoutExtras();
        }

        void clearNotice() {
            if (noticeRow == null) {
                return;
            }
            noticeRow = null;
            noticeKey = null;
            noticeKind = -1;
            layoutExtras();
        }

        /** What the doctor or the delta says under the code, or nothing (null). */
        void showInsight(Insight in) {
            if (!"code".equals(cell.cell_type) || in == shownInsight) {
                return;
            }
            shownInsight = in;
            insightRow = in == null ? null : indent(insightBar(in));
            layoutExtras();
        }

        /**
         * A pill fitted to its content: the glyph, one sentence (wrapped only when the window is
         * too narrow for it), the actions as chips, and a cross that puts it away.
         */
        private JComponent insightBar(Insight in) {
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)) {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color t = NotebookIcons.color(in.tone());
                    g2.setColor(NotebookIcons.alpha(t, 30));
                    g2.fill(new java.awt.geom.RoundRectangle2D.Float(0, 1, getWidth() - 1f, getHeight() - 2f, 10, 10));
                    g2.setColor(NotebookIcons.alpha(t, 210));
                    g2.fill(new java.awt.geom.RoundRectangle2D.Float(0, 1, 3, getHeight() - 2f, 3, 3));
                    g2.dispose();
                }
            };
            bar.setOpaque(false);
            bar.setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 2));
            bar.add(new JLabel(in.icon()));

            List<JButton> chips = new ArrayList<>();
            int chipsWidth = 0;
            for (InsightAction a : in.actions()) {
                JButton chip = new NotebookIcons.Chip(a.label(), a.tip(), in::tone);
                chip.addActionListener(e -> a.run().run());
                chips.add(chip);
                chipsWidth += chip.getPreferredSize().width + 6;
            }
            Font f = FontLoader.getGlobalFont(Font.PLAIN, 12);
            JLabel text = new JLabel("<html>" + in.html() + "</html>");
            text.setFont(f);
            text.setForeground(palette.getTextPrimary());
            int room = Math.max(220, currentViewportWidth - SIDEBAR_WIDTH - chipsWidth - 110);
            if (text.getPreferredSize().width > room) {
                text.setText("<html><div style='width:" + room + "px'>" + in.html() + "</div></html>");
            }
            bar.add(text);
            chips.forEach(bar::add);
            JButton close = new NotebookIcons.ToolButton(NotebookIcons.close(12), "Dismiss",
                () -> "Dismiss", new Dimension(20, 20));
            close.addActionListener(e -> setInsight(cell, null));
            bar.add(close);

            JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
            holder.setOpaque(false);
            holder.add(bar);
            return holder;
        }

        private JButton linkButton(String text, Runnable action) {
            JButton b = new JButton(text);
            b.setFont(FontLoader.getGlobalFont(Font.BOLD, 11));
            b.setForeground(palette.getAccent());
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setFocusable(false);
            b.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> action.run());
            return b;
        }

        /** The bonds as the text now has them: the same widgets follow, new ones are built. */
        void refreshBonds() {
            if (codeEditor == null) {
                return;
            }
            List<NotebookBonds.Bond> now = NotebookBonds.parse(codeEditor.getText());
            boolean same = now.size() == bonds.size();
            for (int k = 0; same && k < now.size(); k++) {
                same = now.get(k).sameWidget(bonds.get(k));
            }
            bonds = now;
            if (same) {
                syncBondWidgets();
                return;
            }
            buildBondBar();
            layoutExtras();
        }

        private void buildBondBar() {
            bondWidgets.clear();
            bondValues.clear();
            if (bonds.isEmpty()) {
                bondBar = null;
                return;
            }
            // part of the cell: the code's own background and border, readable in any theme
            JPanel bar = new JPanel();
            bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
            bar.setOpaque(true);
            bar.setBackground(com.sphere.components.editor.EditorTheme.background());
            bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 1, 1, palette.getJupyLabXedNBorder()),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            Font codeFont = FontLoader.getTerminalFont(Font.PLAIN, 12);
            for (NotebookBonds.Bond b : bonds) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                JLabel name = new JLabel(b.name());
                name.setFont(codeFont.deriveFont(Font.BOLD));
                name.setForeground(com.sphere.components.editor.EditorTheme.foreground());
                JLabel value = new JLabel(b.display());
                value.setFont(codeFont);
                value.setForeground(com.sphere.components.editor.EditorTheme.focus());
                String bn = b.name();
                JComponent w;
                switch (b.kind()) {
                    case SLIDER -> {
                        JSlider s = new JSlider(0, b.ticks(), b.tickOf(b.number()));
                        s.setOpaque(false);
                        s.setFocusable(false);
                        s.setPreferredSize(new Dimension(260, 20));
                        s.setToolTipText(b.literalFor(b.min()) + " to " + b.literalFor(b.max()) + ", step " + b.literalFor(b.step()));
                        s.addChangeListener(e -> {
                            String lit = b.literalFor(b.valueAt(s.getValue()));
                            value.setText(lit);
                            if (!bondSyncing) {
                                writeBond(bn, lit);
                            }
                        });
                        w = s;
                    }
                    case TOGGLE -> {
                        JCheckBox c = new JCheckBox();
                        c.setOpaque(false);
                        c.setFocusable(false);
                        c.setSelected(b.bool());
                        c.addActionListener(e -> {
                            String lit = b.literalFor(c.isSelected());
                            value.setText(lit);
                            if (!bondSyncing) {
                                writeBond(bn, lit);
                            }
                        });
                        w = c;
                    }
                    case SELECT -> {
                        JComboBox<String> c = new JComboBox<>(b.options().toArray(new String[0]));
                        c.setSelectedItem(b.string());
                        c.setFocusable(false);
                        c.addActionListener(e -> {
                            Object sel = c.getSelectedItem();
                            if (sel != null) {
                                value.setText(sel.toString());
                                if (!bondSyncing) {
                                    writeBond(bn, b.literalFor(sel.toString()));
                                }
                            }
                        });
                        w = c;
                        value.setVisible(false);
                    }
                    default -> {
                        JTextField t = new JTextField(b.string(), 22);
                        t.setFont(codeFont);
                        t.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                            if (!bondSyncing) {
                                writeBond(bn, b.literalFor(t.getText()));
                            }
                        }));
                        w = t;
                        value.setVisible(false);
                    }
                }
                row.add(name);
                row.add(w);
                row.add(value);
                bar.add(row);
                bondWidgets.add(w);
                bondValues.add(value);
            }
            bondBar = bar;
        }

        /** The text changed the values (typed, undone): the widgets show them, without writing back. */
        private void syncBondWidgets() {
            bondSyncing = true;
            try {
                for (int k = 0; k < bonds.size() && k < bondWidgets.size(); k++) {
                    NotebookBonds.Bond b = bonds.get(k);
                    JComponent w = bondWidgets.get(k);
                    if (w instanceof JSlider s) {
                        if (!s.getValueIsAdjusting()) {
                            s.setValue(b.tickOf(b.number()));
                        }
                    } else if (w instanceof JCheckBox c) {
                        c.setSelected(b.bool());
                    } else if (w instanceof JComboBox<?> c) {
                        c.setSelectedItem(b.string());
                    } else if (w instanceof JTextField t && !t.isFocusOwner() && !t.getText().equals(b.string())) {
                        t.setText(b.string());
                    }
                    bondValues.get(k).setText(b.display());
                }
            } finally {
                bondSyncing = false;
            }
        }

        /** A widget moved: its literal in the code becomes the new value, and the cell runs shortly after. */
        private void writeBond(String name, String literal) {
            if (codeEditor == null) {
                return;
            }
            for (NotebookBonds.Bond b : NotebookBonds.parse(codeEditor.getText())) {
                if (!b.name().equals(name)) {
                    continue;
                }
                if (b.literal().equals(literal)) {
                    return;
                }
                bondWriting = true;
                try {
                    javax.swing.text.Document doc = codeEditor.getDocument();
                    if (doc instanceof javax.swing.text.AbstractDocument ad) {
                        ad.replace(b.start(), b.end() - b.start(), literal, null);
                    } else {
                        doc.remove(b.start(), b.end() - b.start());
                        doc.insertString(b.start(), literal, null);
                    }
                } catch (javax.swing.text.BadLocationException ignored) {
                    // the text moved under the widget: the next scan rebuilds it
                } finally {
                    bondWriting = false;
                }
                bondRunTimer.restart();
                return;
            }
        }

        /** A Markdown cell's preview, after its text changed from outside (a rename). */
        void refreshPreview() {
            if (previewPaneReference != null && markdownEditor != null && !isEditing) {
                previewPaneReference.setText(JupyLabMarkdown.toHtml(markdownEditor.getText()));
            }
        }

        public Cell toCell() {
            return this.cell;
        }

        private void runPythonAsync() {
            runCell(this, RunThen.STAY);
        }
    }

    // -----------------------------
    // Running cells
    // -----------------------------

    private enum RunThen { STAY, NEXT, INSERT }

    /** Ctrl+Enter runs; Shift+Enter runs and moves on (a new cell at the end); Alt+Enter runs and inserts. */
    private void runCell(CellPanel cp, RunThen then) {
        if (reactive) {
            cp.clearNotice();
            runReactive(List.of(cp.cell)); // what it needs first, then it, then what takes from it
        } else {
            runPythonAsyncForCell(cp);
        }
        int index = cp.findMyIndex();
        switch (then) {
            case NEXT -> focusNext(cp, true);
            case INSERT -> insertCellAt(index + 1, "code");
            default -> { }
        }
    }

    private void focusNext(CellPanel cp, boolean createAtEnd) {
        int index = cp.findMyIndex();
        if (index + 1 < notebook.cells.size()) {
            scrollToCell(index + 1);
        } else if (createAtEnd) {
            insertCellAt(index + 1, "code");
        }
    }

    // -----------------------------
    // Variable inspector, completion, documentation
    // -----------------------------

    private JComponent createVariablePanel() {
        variableModel = new javax.swing.table.DefaultTableModel(
                new Object[] {"Name", "Type", "Value"}, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        variableTable = new JTable(variableModel);
        variableTable.setFillsViewportHeight(true);
        variableTable.setRowHeight(20);
        variableTable.setShowGrid(false);
        variableTable.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        variableTable.setBackground(palette.getBackgroundSurface());
        variableTable.setForeground(palette.getTextPrimary());
        variableTable.getTableHeader().setReorderingAllowed(false);

        JScrollPane scroll = new JScrollPane(variableTable);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, palette.getBorder()));
        scroll.setPreferredSize(new Dimension(300, 100));
        return scroll;
    }

    /** Shows the side panel on a tab ("Variables", "Dataflow"), or hides it (null). */
    private void setSidePanel(String tab) {
        if (bodySplit == null || sideTabs == null) {
            return;
        }
        boolean visible = tab != null;
        if (visible) {
            sideTabs.setSelectedIndex(Math.max(0, sideTabs.indexOfTab(tab)));
        }
        sideTabs.setVisible(visible);
        bodySplit.setDividerSize(visible ? 4 : 0);
        bodySplit.setDividerLocation(visible ? Math.max(260, getWidth() - ("Dataflow".equals(tab) ? 390 : 330))
                                             : Integer.MAX_VALUE);
        bodySplit.revalidate();
        syncSideToggles();
        if ("Dataflow".equals(tab)) {
            recomputeDataflow();
        }
    }

    private void syncSideToggles() {
        boolean visible = sideTabs != null && sideTabs.isVisible() && sideTabs.getSelectedIndex() >= 0;
        String tab = visible ? sideTabs.getTitleAt(sideTabs.getSelectedIndex()) : null;
        if (varsToggle != null) {
            varsToggle.setSelected("Variables".equals(tab));
        }
        if (flowToggle != null) {
            flowToggle.setSelected("Dataflow".equals(tab));
        }
    }

    private void updateVariablePanel(List<Map<String, Object>> items) {
        if (variableModel == null) {
            return;
        }
        variableModel.setRowCount(0);
        for (Map<String, Object> item : items) {
            variableModel.addRow(new Object[] {
                item.get("name"), item.get("type"), item.get("info")});
        }
    }

    /**
     * Asks the kernel what the object really holds: a static word list cannot
     * know the columns of a DataFrame loaded a second ago. Returns -1 without a
     * kernel, and the popup keeps the notebook's own names.
     */
    private int requestCompletion(CellPanel cp, String code, int caret) {
        if (executor == null || !executor.isAlive()) {
            return -1;
        }
        KernelCompletion target = (KernelCompletion) cp.codeEditor.getClientProperty(KernelCompletion.class);
        int id = executor.requestCompletion(code, caret);
        if (id >= 0) {
            completionRequests.put(id, target);
        }
        return id;
    }

    private void requestInspection(javax.swing.text.JTextComponent editor, String code, int caret) {
        if (executor == null || !executor.isAlive()) {
            return;
        }
        int id = executor.requestInspection(code, caret);
        if (id >= 0) {
            inspectRequests.put(id, editor);
        }
    }

    private void restartKernel(boolean thenRunAll) {
        reactiveQueue.clear();
        reactiveInFlight = null;
        ranBinds.clear();
        finishReactive();
        offEdt(() -> {
            try {
                executor().restart();
            } catch (IOException ex) {
                AppLogger.error("JupyLab kernel restart failed: " + ex.getMessage());
                return;
            }
            SwingUtilities.invokeLater(() -> {
                liveKinds = Map.of();
                semantics.setLive(Map.of(), false);
                peeks.clear();
                ranSource.clear();
                for (Cell cell : notebook.cells) {
                    cell.execution_count = null;
                }
                rebuildCellsUI();
                if (thenRunAll) {
                    runAllCells(0, notebook.cells.size());
                }
            });
        });
    }

    private static String formatElapsed(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(java.util.Locale.ROOT, "%.2f s", millis / 1000.0);
    }

    // -----------------------------
    // Notebook analysis: undefined names, run-order dependencies, live kinds
    // -----------------------------

    private void scheduleAnalysis() {
        if (analysisTimer != null) {
            analysisTimer.restart();
        }
    }

    /** Sends every code cell to the kernel's analysis; a busy kernel is asked again after its run. */
    private void runAnalysis() {
        if (executor == null || !executor.isAlive() || executor.isBusy()) {
            return;
        }
        List<Cell> code = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Cell c : notebook.cells) {
            if ("code".equals(c.cell_type)) {
                code.add(c);
                sources.add(NotebookIO.joinLines(c.source));
            }
        }
        analysisCells = code;
        analysisSources = sources;
        analysisRequest = executor.requestAnalysis(sources);
    }

    private void applyAnalysis(int id, List<Map<String, Object>> results) {
        if (id != analysisRequest || results.size() != analysisCells.size()) {
            return;
        }
        Map<String, TokenKind> kinds = NotebookInsight.kinds(results);
        boolean recolor = !kinds.equals(liveKinds);
        liveKinds = Map.copyOf(kinds);
        // runtime families, summaries and staleness: the caps, the value lens and the dotted stale names
        if (semantics.setLive(NotebookInsight.live(results), true)) {
            peeks.clear(); // the values changed: what the tooltips showed of them is gone
            scheduleSemanticRefresh();
        }
        int fragile = 0;
        int first = -1;
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> r = results.get(i);
            Cell cell = analysisCells.get(i);
            CellPanel cp = panelFor(cell);
            if (NotebookInsight.fragile(r)) {
                fragile++;
                if (first < 0) {
                    first = notebook.cells.indexOf(cell);
                }
                if (why.length() < 600) {
                    why.append("<br>• ").append(escape(describeCell(i)));
                }
            }
            if (cp == null || cp.highlighter == null) {
                continue;
            }
            if (recolor) {
                cp.highlighter.setNameClassifier(liveKindOf);
            }
            // A cell edited since it was sent waits for the next analysis rather than
            // get squiggles at offsets that moved.
            if (analysisSources.get(i).equals(NotebookIO.joinLines(cell.source))) {
                final SyntaxHighlighter h = cp.highlighter;
                cp.setAnalysisDiagnostics(NotebookInsight.diagnostics(analysisSources.get(i), r,
                    offset -> {
                        TokenKind k = h.liveKindAt(offset);
                        return k != TokenKind.STRING && k != TokenKind.COMMENT && k != TokenKind.PREPROCESSOR;
                    },
                    this::describeCell));
            }
        }
        reproLabel.putClientProperty("first", first);
        fragileCells = fragile;
        fragileWhy = why.toString();
        updateStateLabel();
    }

    private String describeCell(int codeIndex) {
        if (codeIndex < 0 || codeIndex >= analysisCells.size()) {
            return "a cell that no longer exists";
        }
        String text = NotebookIO.joinLines(analysisCells.get(codeIndex).source).strip();
        String firstLine = text.isEmpty() ? "" : text.split("\n", 2)[0].strip();
        if (firstLine.length() > 40) {
            firstLine = firstLine.substring(0, 37) + "...";
        }
        Integer count = analysisCells.get(codeIndex).execution_count;
        return "the cell " + (count == null ? "" : "[" + count + "] ") + "\"" + firstLine + "\"";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void revealFirstFragileCell() {
        if (reactive) {
            for (int i : dataflow.order()) {
                NotebookDataflow.Node n = dataflow.node(i);
                if (n != null && (n.broken() || n.status() == NotebookDataflow.Status.FAILED || n.outdated())) {
                    scrollToCell(notebook.cells.indexOf(codeCellAt(i)));
                    return;
                }
            }
            return;
        }
        Object first = reproLabel.getClientProperty("first");
        if (first instanceof Integer i && i >= 0) {
            scrollToCell(i);
        }
    }

    // -----------------------------
    // Semantic colors: the notebook as one module, the value lens, the tooltips
    // -----------------------------

    private List<Cell> codeCells() {
        List<Cell> out = new ArrayList<>();
        for (Cell c : notebook.cells) {
            if ("code".equals(c.cell_type)) {
                out.add(c);
            }
        }
        return out;
    }

    /** "cell [3]", or where an unrun cell sits: how a tooltip names the cell a variable comes from. */
    private String codeCellLabel(int codeIndex) {
        List<Object> cells = semantics.cells();
        if (codeIndex < 0 || codeIndex >= cells.size() || !(cells.get(codeIndex) instanceof Cell c)) {
            return "another cell";
        }
        Integer count = c.execution_count;
        return count != null ? "cell [" + count + "]" : "cell " + (notebook.cells.indexOf(c) + 1) + " (not run)";
    }

    /** A code cell was scanned: its module level for the others, its findings, its value lens. */
    private void onSemanticScan(CellPanel cp, SyntaxHighlighter.Snapshot snapshot) {
        PySemantics.Result r = snapshot.semantic;
        if (semantics.update(cp.cell, r == null ? null : r.summary())) {
            scheduleSemanticRefresh();
        }
        scheduleDataflow(); // an edit can make the cell out of date, a new name a new edge
        if (cp.codeEditor != null && cp.codeEditor.isFocusOwner()) {
            updateSymbolMarks(cp);
        }
        cp.setSemanticDiagnostics(r == null ? List.of() : findingsToDiagnostics(snapshot.text, r.findings()));
        updateLens(cp, r);
    }

    private void scheduleSemanticRefresh() {
        if (semanticTimer != null) {
            semanticTimer.restart();
        }
    }

    /** Every code cell scanned again: what another cell binds or reads changed what its names are. */
    private void rescanCodeCells() {
        if (cellsContainer == null) {
            return;
        }
        for (Component c : cellsContainer.getComponents()) {
            if (c instanceof CellPanel cp && cp.highlighter != null) {
                cp.highlighter.rehighlightAll();
            }
        }
    }

    private void showColorLegend(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem toggle = new JCheckBoxMenuItem("Semantic colors: scopes, roles, runtime types", semanticColors);
        toggle.addActionListener(e -> setSemanticColors(toggle.isSelected()));
        menu.add(toggle);
        menu.addSeparator();
        SemanticLegend legend = new SemanticLegend();
        legend.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 4));
        menu.add(legend);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void setSemanticColors(boolean on) {
        semanticColors = on;
        for (Component c : cellsContainer.getComponents()) {
            if (c instanceof CellPanel cp && cp.highlighter != null) {
                cp.highlighter.setSemanticEnabled(on);
                if (!on) {
                    cp.codeEditor.setInlays(Map.of());
                    cp.setSemanticDiagnostics(List.of());
                }
            }
        }
    }

    /**
     * The value lens: past the end of a line that binds a module-level name, what
     * the kernel holds under it ("Tensor · float32 (128, 7) cuda:0", "numpy 2.4.4"),
     * on the line of the cell whose run made that value only.
     */
    private void updateLens(CellPanel cp, PySemantics.Result r) {
        if (cp.codeEditor == null) {
            return;
        }
        if (r == null || !semanticColors || !semantics.hasLive() || cp.cell.execution_count == null) {
            cp.codeEditor.setInlays(Map.of());
            return;
        }
        Map<Integer, String> inlays = new HashMap<>();
        // the warning sign where the code font has it (not every coding font draws U+26A0)
        String warn = cp.codeEditor.getFont().canDisplay('⚠') ? "⚠ " : "(!) ";
        for (PySemantics.Lens lens : r.lenses()) {
            List<String> parts = new ArrayList<>();
            for (String name : lens.names()) {
                PySemantics.Live live = semantics.live(name);
                if (live != null && madeBy(cp.cell, name, live)) {
                    String text = lensText(name, live, lens.names().size() > 1, warn);
                    if (!text.isBlank()) {
                        parts.add(text);
                    }
                }
            }
            if (!parts.isEmpty()) {
                inlays.merge(lens.line(), String.join("    ", parts), (a, b) -> a + "    " + b);
            }
        }
        cp.codeEditor.setInlays(inlays);
    }

    /** Whether this cell's run made the value the kernel holds under name. */
    private boolean madeBy(Cell cell, String name, PySemantics.Live live) {
        Integer count = cell.execution_count;
        if (count == null) {
            return false;
        }
        if (live.made() >= 0) {
            return live.made() == count; // Sphere's own kernel knows which run made each value
        }
        int last = -1;
        List<Object> cells = semantics.cells();
        for (int i : semantics.binders(name)) {
            if (i < cells.size() && cells.get(i) instanceof Cell c && c.execution_count != null) {
                last = Math.max(last, c.execution_count);
            }
        }
        return last == count;
    }

    private static String lensText(String name, PySemantics.Live live, boolean named, String warn) {
        String type = live.type() == null ? "" : live.type();
        String info = live.info() == null ? "" : live.info();
        String text;
        if ("module".equals(live.kind())) {
            text = info.isEmpty() ? type : type + " " + info;
        } else if ("function".equals(live.kind()) || "class".equals(live.kind())) {
            text = info;
        } else {
            text = info.isEmpty() ? type : type + " · " + info;
        }
        if (named) {
            text = name + ": " + text;
        }
        if (live.stale() != null) {
            text = warn + "stale · " + text;
        }
        return text.length() > 90 ? text.substring(0, 89) + "…" : text;
    }

    /** What a name under the mouse is, how it is used, where it comes from and what the kernel holds. */
    private String semanticTooltip(CellPanel cp, java.awt.event.MouseEvent event) {
        if (cp.highlighter == null || cp.codeEditor == null || !semanticColors) {
            return null;
        }
        SyntaxHighlighter.Snapshot snap = cp.highlighter.snapshot();
        if (snap.semantic == null || !cp.highlighter.isCurrent()) {
            return null;
        }
        int offset = cp.codeEditor.viewToModel2D(event.getPoint());
        PySemantics.Occurrence o = snap.semantic.occurrence(offset);
        if (o == null) {
            return null;
        }
        try {
            java.awt.geom.Rectangle2D a = cp.codeEditor.modelToView2D(o.start());
            java.awt.geom.Rectangle2D b = cp.codeEditor.modelToView2D(o.end());
            if (event.getX() < a.getX() - 1 || event.getX() > b.getX() + 1
                || event.getY() < a.getY() || event.getY() > a.getY() + a.getHeight()) {
                return null; // past the end of the line, the nearest name is not the one pointed at
            }
        } catch (javax.swing.text.BadLocationException e) {
            return null;
        }
        PySemantics.Role role = o.role();
        StringBuilder sb = new StringBuilder("<html><div style='padding:2px'>");
        sb.append("<b style='color:").append(hex(SemanticStyle.roleColor(role))).append("'>")
          .append(escape(o.name())).append("</b>&nbsp;&nbsp;").append(escape(role.label));
        PySemantics.Global g = semantics.contextFor(cp.cell).global(o.name());
        boolean global = role == PySemantics.Role.VARIABLE || role == PySemantics.Role.CONSTANT
                         || o.external() || "module".equals(o.scope());
        if (!o.scope().isEmpty() && !"module".equals(o.scope())) {
            sb.append(" <span style='color:").append(hex(EditorTheme.textMuted())).append("'>of ")
              .append(escape(o.scope())).append("</span>");
        }
        List<String> facts = new ArrayList<>();
        if (o.has(PySemantics.DEF)) {
            facts.add("defined here");
        } else if (global && g != null && g.definedIn() != null) {
            facts.add("defined in " + g.definedIn());
        } else if (o.definedAt() > 0) {
            facts.add("defined on line " + o.definedAt());
        }
        int assigns = o.assigns() + (global && g != null ? g.assignsElsewhere() : 0);
        if (o.has(PySemantics.REASSIGNED)) {
            facts.add("bound " + Math.max(2, assigns) + " times: its value depends on which ran last");
        }
        if (o.has(PySemantics.UNUSED)) {
            facts.add("never read" + (global ? " in any cell" : ""));
        } else if (!o.external() && o.reads() > 0) {
            int elsewhere = global && g != null ? g.readsElsewhere() : 0;
            facts.add("read " + o.reads() + (o.reads() == 1 ? " time" : " times") + " here"
                      + (elsewhere > 0 ? ", " + elsewhere + " in other cells" : ""));
        }
        if (o.has(PySemantics.SHADOWS)) {
            facts.add("hides the builtin " + escape(o.name()) + "()");
        }
        if (o.has(PySemantics.CAPTURED)) {
            facts.add("read from the enclosing function (a closure)");
        }
        if (o.has(PySemantics.MUTATED)) {
            facts.add("changed in place here");
        }
        if (o.has(PySemantics.UNRESOLVED)) {
            facts.add("no cell, builtin or kernel value defines it yet");
        }
        for (String f : facts) {
            sb.append("<br>• ").append(f);
        }
        PySemantics.Live live = g == null ? null : g.live();
        if (live != null && global) {
            PySemantics.Family family = live.family();
            sb.append("<br><span style='color:").append(hex(family == PySemantics.Family.NONE
                      ? EditorTheme.textMuted() : SemanticStyle.familyColor(family))).append("'>■</span> ")
              .append("<b>").append(escape(live.type())).append("</b>");
            if (live.info() != null && !live.info().isEmpty()) {
                sb.append(" · ").append(escape(live.info()));
            }
            if (family != PySemantics.Family.NONE) {
                sb.append(" <span style='color:").append(hex(EditorTheme.textMuted())).append("'>(")
                  .append(family.label).append(")</span>");
            }
            if (live.stale() != null) {
                sb.append("<br><span style='color:").append(hex(SemanticStyle.staleColor())).append("'>⚠ stale: ")
                  .append(escape(live.stale())).append("</span>");
            }
            if ("data".equals(live.kind())) {
                // the values themselves, asked of the kernel the first time the mouse rests on the name
                Map<String, Object> pk = peeks.get(o.name());
                if (pk != null) {
                    sb.append(peekHtml(pk, live.family()));
                } else if (executor != null && executor.isAlive()) {
                    requestPeek(o.name());
                    sb.append("<br><span style='color:").append(hex(EditorTheme.textMuted())).append("'>")
                      .append(executor.isBusy() ? "the kernel is busy: the values come after the cell" : "reading the values…")
                      .append("</span>");
                }
                tooltipCell = cp;
                tooltipPoint = event.getPoint();
            }
        }
        int line = cp.codeEditor.lineAt(o.start()) + 1;
        for (EditorDiagnostic d : cp.codeEditor.getDiagnostics()) {
            if (d.getLine() == line) {
                sb.append("<br><span style='color:").append(hex(d.getSeverity() == EditorDiagnostic.Severity.ERROR
                          ? EditorTheme.errorSquiggle() : EditorTheme.warningSquiggle())).append("'>")
                  .append(escape(d.getMessage())).append("</span>");
            }
        }
        return sb.append("</div></html>").toString();
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static List<EditorDiagnostic> findingsToDiagnostics(String text, List<PySemantics.Finding> findings) {
        List<EditorDiagnostic> out = new ArrayList<>();
        for (PySemantics.Finding f : findings) {
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i < f.start() && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            EditorDiagnostic.Severity severity = f.severity() >= 2 ? EditorDiagnostic.Severity.ERROR
                : f.severity() == 1 ? EditorDiagnostic.Severity.WARNING : EditorDiagnostic.Severity.INFO;
            out.add(new EditorDiagnostic(line, f.start() - lineStart + 1, Math.max(1, f.end() - f.start()), severity,
                                         f.code(), f.message(), "Semantics"));
        }
        return out;
    }

    private static boolean sameDiagnostics(List<EditorDiagnostic> a, List<EditorDiagnostic> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            EditorDiagnostic x = a.get(i);
            EditorDiagnostic y = b.get(i);
            if (x.getLine() != y.getLine() || x.getColumn() != y.getColumn() || x.getLength() != y.getLength()
                || !x.getMessage().equals(y.getMessage())) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------
    // The dataflow: stale cells, Run Stale, run downstream, reactive mode, the map
    // -----------------------------

    private Cell codeCellAt(int codeIndex) {
        List<Object> cells = semantics.cells();
        return codeIndex >= 0 && codeIndex < cells.size() && cells.get(codeIndex) instanceof Cell c ? c : null;
    }

    private int codeIndexOf(Cell cell) {
        List<Object> cells = semantics.cells();
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i) == cell) {
                return i;
            }
        }
        return -1;
    }

    private void scheduleDataflow() {
        if (dataflowTimer != null) {
            dataflowTimer.restart();
        }
    }

    /** What each cell takes from which, and which no longer match what they read; the UI follows. */
    private void recomputeDataflow() {
        if (dataflowTimer != null) {
            dataflowTimer.stop();
        }
        List<Object> cells = semantics.cells();
        List<PySemantics.Summary> sums = semantics.summaries();
        List<Integer> counts = new ArrayList<>(cells.size());
        List<Boolean> edited = new ArrayList<>(cells.size());
        List<String> labels = new ArrayList<>(cells.size());
        Map<Integer, String> queued = new HashMap<>();
        Map<Integer, String> errors = new HashMap<>();
        Cell running = executor == null ? null : executor.runningCell();
        if (running == null) {
            running = reactiveInFlight; // sent, not started yet
        }
        int runningIndex = -1;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = (Cell) cells.get(i);
            String now = NotebookIO.joinLines(c.source);
            counts.add(c.execution_count);
            String ran = ranSource.get(c);
            edited.add(c.execution_count != null && ran != null && !ran.equals(now));
            labels.add(firstCodeLine(now));
            if (c == running) {
                runningIndex = i;
                String why = runReasons.get(c);
                if (why != null && reactiveTotal > 0) {
                    queued.put(i, "running: " + why);
                }
            }
            for (Output o : c.outputs) {
                if ("error".equals(o.output_type)) {
                    String v = o.evalue == null ? "" : o.evalue.strip();
                    errors.put(i, (o.ename == null ? "error" : o.ename) + (v.isEmpty() ? "" : ": " + (v.length() > 120 ? v.substring(0, 119) + "…" : v)));
                }
            }
        }
        for (Cell q : reactiveQueue) {
            int i = codeIndexOf(q);
            if (i >= 0 && i != runningIndex) {
                queued.put(i, "queued: " + runReasons.getOrDefault(q, "waits its turn"));
            }
        }
        dataflow = NotebookDataflow.compute(sums, counts, edited, runningIndex, queued, errors, reactive, this::shortLabel);
        List<Map.Entry<Cell, String>> redefs = reactive ? redefinitions() : List.of();
        for (Component comp : cellsContainer.getComponents()) {
            if (comp instanceof CellPanel cp && "code".equals(cp.cell.cell_type)) {
                NotebookDataflow.Node node = dataflow.node(codeIndexOf(cp.cell));
                cp.setDataflow(node);
                if (node != null && node.status() == NotebookDataflow.Status.CONFLICT) {
                    String redefined = null;
                    for (Map.Entry<Cell, String> r : redefs) {
                        if (r.getKey() == cp.cell) {
                            redefined = r.getValue();
                            break;
                        }
                    }
                    final String name = redefined;
                    cp.showNotice(node.why(), 2,
                                  name != null ? "Own name for '" + name + "'" : null,
                                  name != null ? () -> giveOwnName(cp.cell, name) : null,
                                  name != null && redefs.size() > 1 ? "Fix all " + redefs.size() : null,
                                  this::giveOwnNames);
                } else if (node != null && node.status() == NotebookDataflow.Status.BLOCKED) {
                    cp.showNotice(node.why(), 1, null, null, null, null);
                } else if (cp.noticeKind >= 1) {
                    cp.clearNotice();
                }
            }
        }
        int outdated = dataflow.outdated().size();
        if (runStaleBtn != null) {
            staleCount = outdated; // the glyph's badge
            runStaleBtn.setEnabled(outdated > 0 || reactive && dataflow.count(NotebookDataflow.Status.NOT_RUN) > 0);
            runStaleBtn.repaint();
        }
        if (stopBtn != null) {
            stopBtn.repaint();
        }
        if (dataflowMap != null) {
            dataflowMap.setGraph(dataflow, labels); // cheap: it only repaints when shown
            dataflowMap.setProgress(reactiveTotal <= 0 ? ""
                : "running " + Math.min(reactiveTotal, reactiveDone + 1) + " of " + reactiveTotal
                  + (runningIndex >= 0 ? " · " + shortLabel(runningIndex) + " " + labels.get(runningIndex) : ""));
        }
        updateStateLabel();
    }

    private static String firstCodeLine(String source) {
        for (String line : source.split("\n")) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("#")) {
                return s;
            }
        }
        return source.isBlank() ? "(empty)" : source.strip().split("\n", 2)[0];
    }

    /** Runs the edited and stale cells, in notebook order: the notebook matches its code again. */
    private void runStale() {
        syncUIToModel();
        refreshSummaries();
        recomputeDataflow();
        List<Cell> list = new ArrayList<>();
        for (int i : dataflow.outdated()) {
            Cell c = codeCellAt(i);
            if (c != null) {
                list.add(c);
            }
        }
        if (reactive) {
            for (int i = 0; i < dataflow.nodes().size(); i++) {
                Cell c = codeCellAt(i);
                if (c != null && dataflow.node(i).status() == NotebookDataflow.Status.NOT_RUN) {
                    list.add(c); // the graph is whole only once every cell ran
                }
            }
            if (!list.isEmpty()) {
                runReactive(list);
            }
            return;
        }
        if (!list.isEmpty()) {
            setDirty(true);
            offEdt(() -> executor().runAll(list));
        }
    }

    /** A cell, then every cell that takes from it, directly or not, in notebook order (Ctrl+Shift+Enter). */
    private void runWithDownstream(Cell cell) {
        if (reactive) {
            runReactive(List.of(cell));
            return;
        }
        syncUIToModel();
        refreshSummaries();
        recomputeDataflow();
        int index = codeIndexOf(cell);
        if (index < 0) {
            return;
        }
        List<Cell> list = new ArrayList<>();
        list.add(cell);
        for (int i : dataflow.downstream(index)) {
            Cell c = codeCellAt(i);
            if (c != null) {
                list.add(c);
            }
        }
        setDirty(true);
        offEdt(() -> executor().runAll(list));
    }

    // -----------------------------
    // Reactive runs: the graph decides what runs, in which order, and why
    // -----------------------------

    /** Turns the notebook reactive or linear; saved with it. */
    private void setReactive(boolean on) {
        if (reactiveBtn != null && reactiveBtn.isSelected() != on) {
            reactiveBtn.setSelected(on);
        }
        if (reactive == on) {
            return;
        }
        reactive = on;
        Map<String, Object> sphere = new LinkedHashMap<>();
        if (notebook.metadata.get("sphere") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> sphere.put(String.valueOf(k), v));
        }
        if (on) {
            sphere.put("reactive", Boolean.TRUE);
        } else {
            sphere.remove("reactive");
        }
        if (sphere.isEmpty()) {
            notebook.metadata.remove("sphere");
        } else {
            notebook.metadata.put("sphere", sphere);
        }
        setDirty(true);
        refreshSummaries();
        recomputeDataflow();
    }

    private String textOf(Cell cell) {
        CellPanel cp = panelFor(cell);
        return cp != null && cp.codeEditor != null ? cp.codeEditor.getText() : NotebookIO.joinLines(cell.source);
    }

    /** A cell's analysis as its text is now: its last scan when current, else made on the spot. */
    private PySemantics.Result resultOf(Cell cell) {
        String text = textOf(cell);
        CellPanel cp = panelFor(cell);
        if (cp != null && cp.highlighter != null && cp.highlighter.isCurrent()) {
            SyntaxHighlighter.Snapshot s = cp.highlighter.snapshot();
            if (s != null && s.semantic != null && text.equals(s.text)) {
                return s.semantic;
            }
        }
        return com.sphere.components.editor.PythonLexer.lex(text, new ArrayList<>(), null, null, true);
    }

    private PySemantics.Summary summaryOf(Cell cell) {
        PySemantics.Result r = resultOf(cell);
        return r == null ? null : r.summary();
    }

    /** Every code cell's summary brought up to its text: a plan made just after typing sees what was typed. */
    private void refreshSummaries() {
        semantics.retain(codeCells());
        for (Cell c : codeCells()) {
            semantics.update(c, summaryOf(c));
        }
    }

    /** "[3]" for a cell that ran, "cell 4" for one that has not: how reasons name cells. */
    private String shortLabel(int codeIndex) {
        Cell c = codeCellAt(codeIndex);
        if (c == null) {
            return "a cell";
        }
        return c.execution_count != null ? "[" + c.execution_count + "]" : "cell " + (notebook.cells.indexOf(c) + 1);
    }

    /** What a cell defined when it last ran and no longer defines. */
    private Set<String> droppedNames(Cell cell) {
        Set<String> before = ranBinds.get(cell);
        if (before == null || before.isEmpty()) {
            return Set.of();
        }
        PySemantics.Summary now = summaryOf(cell);
        Set<String> gone = new TreeSet<>(before);
        if (now != null) {
            gone.removeAll(now.bound().keySet());
        }
        return gone;
    }

    /**
     * Runs cells the reactive way: the plan (NotebookDataflow.Graph.plan) says which cells and in which
     * order -- what they need that is out of date, them, what takes from them -- and why each; cells
     * that cannot run say why under their code. A run under way takes the new cells in, in the
     * graph's order, each cell once.
     */
    private void runReactive(Collection<Cell> roots) {
        syncUIToModel();
        for (Cell c : roots) {
            CellPanel cp = panelFor(c);
            if (cp != null && cp.codeEditor != null) {
                c.source = cp.codeEditor.getText();
                if (cp.completionPopup != null) {
                    cp.completionPopup.hide();
                }
            }
        }
        refreshSummaries();
        recomputeDataflow();
        // A cell no longer defining a name takes along the cells reading it: they have to find out it is gone.
        Set<Cell> start = new LinkedHashSet<>(roots);
        for (Cell r : roots) {
            Set<String> dropped = droppedNames(r);
            if (dropped.isEmpty()) {
                continue;
            }
            List<PySemantics.Summary> sums = semantics.summaries();
            for (int i = 0; i < sums.size(); i++) {
                Cell c = codeCellAt(i);
                if (c != null && c != r && sums.get(i) != null && !Collections.disjoint(sums.get(i).needs(), dropped)) {
                    start.add(c);
                }
            }
        }
        List<Integer> idx = new ArrayList<>();
        for (Cell c : start) {
            int i = codeIndexOf(c);
            if (i >= 0) {
                idx.add(i);
            }
        }
        NotebookDataflow.Plan plan = dataflow.plan(idx, this::shortLabel);
        if (plan.isEmpty()) {
            if (plan.refused().isEmpty()) {
                return;
            }
            Toolkit.getDefaultToolkit().beep(); // nothing can run: each refused cell says why under its code
            Integer first = plan.refused().keySet().iterator().next();
            Cell c = codeCellAt(first);
            if (c != null) {
                scrollToCell(notebook.cells.indexOf(c));
            }
            return;
        }
        Set<Cell> merged = new LinkedHashSet<>(reactiveQueue);
        for (int i : plan.cells()) {
            Cell c = codeCellAt(i);
            if (c != null) {
                merged.add(c);
                runReasons.put(c, plan.reasons().get(i));
            }
        }
        List<Cell> sorted = new ArrayList<>(merged);
        sorted.sort(Comparator.comparingInt(c -> {
            NotebookDataflow.Node n = dataflow.node(codeIndexOf(c));
            return n == null ? Integer.MAX_VALUE : n.rank();
        }));
        boolean underway = reactiveInFlight != null || !reactiveQueue.isEmpty();
        reactiveQueue.clear();
        reactiveQueue.addAll(sorted);
        if (!underway) {
            reactiveDone = 0;
        }
        reactiveTotal = reactiveDone + reactiveQueue.size() + (reactiveInFlight != null ? 1 : 0);
        setDirty(true);
        recomputeDataflow();
        pumpReactive();
    }

    /** The next cell of the run to the kernel, once the one before it is done. */
    private void pumpReactive() {
        if (reactiveInFlight != null || executor != null && executor.isBusy()) {
            return; // its end pumps again (EditorView.cellFinished)
        }
        Cell next = reactiveQueue.pollFirst();
        while (next != null && codeIndexOf(next) < 0) {
            next = reactiveQueue.pollFirst(); // deleted while it waited
        }
        if (next == null) {
            finishReactive();
            return;
        }
        reactiveInFlight = next;
        // No hidden state: what it defined when it last ran, and what it defines now, leave the kernel before it
        // runs (unless another cell defines them too, as an import repeated): a cell that fails leaves no old value
        // behind for the cells after it to read, and a name it no longer defines is gone.
        Set<String> gone = new TreeSet<>(ranBinds.getOrDefault(next, Set.of()));
        PySemantics.Summary nowDefines = summaryOf(next);
        if (nowDefines != null) {
            gone.addAll(nowDefines.bound().keySet());
        }
        if (!gone.isEmpty()) {
            List<PySemantics.Summary> sums = new ArrayList<>(semantics.summaries());
            int self = codeIndexOf(next);
            if (self >= 0 && self < sums.size()) {
                sums.set(self, null);
            }
            gone.removeAll(NotebookDataflow.defined(sums));
            if (!gone.isEmpty() && executor != null && executor.forget(gone)) {
                gone.forEach(peeks::remove);
            }
        }
        CellPanel cp = panelFor(next);
        if (cp != null && cp.codeEditor != null) {
            next.source = cp.codeEditor.getText();
        }
        final Cell cell = next;
        offEdt(() -> executor().run(cell));
        recomputeDataflow();
    }

    private void finishReactive() {
        reactiveTotal = 0;
        reactiveDone = 0;
        recomputeDataflow();
    }

    /** Stop: the cells still waiting are dropped; the one at the kernel is interrupted by the caller. */
    private void cancelReactive() {
        reactiveQueue.clear();
        reactiveTotal = reactiveInFlight != null ? reactiveDone + 1 : 0;
        recomputeDataflow();
    }

    /** A bond's widget moved: its cell runs with what depends on it, the kernel never flooded (one run per cell queued). */
    private void runBonded(Cell cell) {
        if (codeIndexOf(cell) >= 0) {
            runReactive(List.of(cell));
        }
    }

    /** The run button's tooltip: in a reactive notebook, the plan a click would carry out. */
    private String runTooltip(CellPanel cp) {
        if (!reactive) {
            return "Run (Ctrl+Enter); Shift+Enter runs and moves on; Ctrl+Shift+Enter takes the cells depending on it along";
        }
        int i = codeIndexOf(cp.cell);
        if (i < 0) {
            return null;
        }
        NotebookDataflow.Plan plan = dataflow.plan(List.of(i), this::shortLabel);
        StringBuilder sb = new StringBuilder("<html><b>Run</b> (Ctrl+Enter): ");
        if (plan.isEmpty()) {
            sb.append("cannot run: ").append(escape(plan.refused().getOrDefault(i, "")));
        } else {
            sb.append(plan.cells().size()).append(plan.cells().size() == 1 ? " cell" : " cells");
            int shown = 0;
            for (int c : plan.cells()) {
                if (shown++ == 8) {
                    sb.append("<br>…");
                    break;
                }
                sb.append("<br>").append(shown).append(". ").append(escape(shortLabel(c))).append(" <i>")
                  .append(escape(plan.reasons().getOrDefault(c, ""))).append("</i>");
            }
            List<Cell> cells = new ArrayList<>();
            for (int c : plan.cells()) {
                Cell at = codeCellAt(c);
                if (at != null) {
                    cells.add(at);
                }
            }
            sb.append(estimateLine(cells));
        }
        return sb.append("</html>").toString();
    }

    // -----------------------------
    // Code changes across cells: own names for redefinitions, rename (F2)
    // -----------------------------

    /** One replacement in a cell's text. */
    private record TextEdit(int start, int end, String text) {
    }

    /** Applies edits to a cell, from the end so offsets hold; one undo step in its editor. */
    private void applyEdits(Cell cell, List<TextEdit> edits) {
        if (edits.isEmpty()) {
            return;
        }
        List<TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> a.start() != b.start() ? Integer.compare(b.start(), a.start()) : Integer.compare(b.end(), a.end()));
        CellPanel cp = panelFor(cell);
        if (cp != null && cp.codeEditor != null) {
            javax.swing.text.Document doc = cp.codeEditor.getDocument();
            cp.codeEditor.atomic(() -> {
                for (TextEdit e : sorted) {
                    try {
                        if (doc instanceof javax.swing.text.AbstractDocument ad) {
                            ad.replace(e.start(), e.end() - e.start(), e.text(), null);
                        } else {
                            doc.remove(e.start(), e.end() - e.start());
                            doc.insertString(e.start(), e.text(), null);
                        }
                    } catch (javax.swing.text.BadLocationException ignored) {
                        // an offset past the text: the analysis was older than the text
                    }
                }
            });
            cell.source = cp.codeEditor.getText();
        } else {
            StringBuilder sb = new StringBuilder(NotebookIO.joinLines(cell.source));
            for (TextEdit e : sorted) {
                if (e.end() <= sb.length()) {
                    sb.replace(e.start(), e.end(), e.text());
                }
            }
            cell.source = sb.toString();
        }
        setDirty(true);
    }

    /**
     * Where the statement at offset ends: its line, carried on by open brackets and backslashes,
     * a comment left out; or a semicolon.
     */
    static int statementEnd(String text, int from) {
        int depth = 0;
        char quote = 0;
        int last = from;
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
                last = i + 1;
                continue;
            }
            switch (ch) {
                case '\'', '"' -> {
                    quote = ch;
                    last = i + 1;
                }
                case '(', '[', '{' -> {
                    depth++;
                    last = i + 1;
                }
                case ')', ']', '}' -> {
                    depth = Math.max(0, depth - 1);
                    last = i + 1;
                }
                case '#' -> {
                    if (depth == 0) {
                        return last;
                    }
                    int nl = text.indexOf('\n', i);
                    if (nl < 0) {
                        return last;
                    }
                    i = nl - 1;
                }
                case '\\' -> {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
                case '\n', ';' -> {
                    if (depth == 0) {
                        return last;
                    }
                }
                default -> {
                    if (!Character.isWhitespace(ch)) {
                        last = i + 1;
                    }
                }
            }
        }
        return last;
    }

    private static final java.util.regex.Pattern AUGMENTED =
        java.util.regex.Pattern.compile("[ \\t]*(\\*\\*|//|>>|<<|[-+*/%&|^@])=(?!=)");

    /**
     * The later definitions of names defined in several cells, in notebook order: top to bottom
     * each is a new value under an old name, which a reactive notebook cannot have.
     */
    private List<Map.Entry<Cell, String>> redefinitions() {
        List<Map.Entry<Cell, String>> out = new ArrayList<>();
        List<PySemantics.Summary> sums = semantics.summaries();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sums.size(); i++) {
            PySemantics.Summary s = sums.get(i);
            Cell c = codeCellAt(i);
            if (s == null || c == null) {
                continue;
            }
            for (Map.Entry<String, PySemantics.Bind> b : new TreeMap<>(s.bound()).entrySet()) {
                PySemantics.Binding k = b.getValue().kind();
                if (k == PySemantics.Binding.IMPORT_MODULE || k == PySemantics.Binding.IMPORT_NAME) {
                    continue; // importing twice gives the same module
                }
                if (!seen.add(b.getKey())) {
                    out.add(Map.entry(c, b.getKey()));
                }
            }
        }
        return out;
    }

    /** Every redefinition gets its own name, top to bottom ("Fix all" under a conflict). */
    private void giveOwnNames() {
        int fixed = 0;
        for (int guard = 0; guard < 500; guard++) {
            refreshSummaries();
            List<Map.Entry<Cell, String>> r = redefinitions();
            if (r.isEmpty() || !giveOwnName(r.get(0).getKey(), r.get(0).getValue(), false)) {
                break;
            }
            fixed++;
        }
        refreshSummaries();
        recomputeDataflow();
        if (fixed > 0) {
            flash(fixed + (fixed == 1 ? " redefinition has" : " redefinitions have") + " a name of its own now");
        }
    }

    private void giveOwnName(Cell cell, String name) {
        giveOwnName(cell, name, true);
        refreshSummaries();
        recomputeDataflow();
    }

    /**
     * Gives the definition of name in cell a name of its own (name_2...), and so do the cells below
     * reading it -- those that, top to bottom, take name from this cell: what the notebook computes
     * top to bottom is unchanged, and each variable has one home. x += 1 becomes x_2 = x + (1).
     */
    private boolean giveOwnName(Cell cell, String name, boolean tell) {
        List<Cell> code = codeCells();
        int at = code.indexOf(cell);
        PySemantics.Result own = at < 0 ? null : resultOf(cell);
        if (own == null) {
            return false;
        }
        Set<String> taken = new HashSet<>();
        for (Cell c : code) {
            PySemantics.Summary s = summaryOf(c);
            if (s != null) {
                taken.addAll(s.bound().keySet());
                taken.addAll(s.reads().keySet());
            }
        }
        String fresh = name + "_2";
        for (int k = 3; taken.contains(fresh) || semantics.live(fresh) != null; k++) {
            fresh = name + "_" + k;
        }
        // This cell: its definition and what follows take the new name; reads before it takes effect keep the old.
        String text = textOf(cell);
        List<int[]> occ = own.globalOccurrences(name);
        int bind = -1;
        for (int[] o : occ) {
            if (o[2] == 1) {
                bind = o[0];
                break;
            }
        }
        if (bind < 0) {
            return false;
        }
        int effect = statementEnd(text, bind);
        List<TextEdit> edits = new ArrayList<>();
        for (int[] o : occ) {
            if (o[0] == bind) {
                java.util.regex.Matcher aug = AUGMENTED.matcher(text).region(o[1], text.length());
                if (aug.lookingAt()) {
                    int end = statementEnd(text, aug.end());
                    edits.add(new TextEdit(o[0], aug.end(), fresh + " = " + name + " " + aug.group(1) + " ("));
                    edits.add(new TextEdit(end, end, ")"));
                } else {
                    edits.add(new TextEdit(o[0], o[1], fresh));
                }
            } else if (o[2] == 1 || o[0] >= effect) {
                edits.add(new TextEdit(o[0], o[1], fresh));
            }
        }
        applyEdits(cell, edits);
        // The cells below reading this definition, until one defines the name again.
        int readers = 0;
        for (int k = at + 1; k < code.size(); k++) {
            Cell c = code.get(k);
            PySemantics.Result r = resultOf(c);
            if (r == null) {
                continue;
            }
            String t = textOf(c);
            List<int[]> uses = r.globalOccurrences(name);
            if (uses.isEmpty()) {
                continue;
            }
            int theirs = -1;
            for (int[] o : uses) {
                if (o[2] == 1) {
                    theirs = o[0];
                    break;
                }
            }
            int theirEffect = theirs < 0 ? Integer.MAX_VALUE : statementEnd(t, theirs);
            List<TextEdit> more = new ArrayList<>();
            for (int[] o : uses) {
                if (o[0] == theirs) {
                    java.util.regex.Matcher aug = AUGMENTED.matcher(t).region(o[1], t.length());
                    if (aug.lookingAt()) {
                        // x += e there read this cell's x: x = x_2 + (e), its own definition from then on
                        int end = statementEnd(t, aug.end());
                        more.add(new TextEdit(o[0], aug.end(), name + " = " + fresh + " " + aug.group(1) + " ("));
                        more.add(new TextEdit(end, end, ")"));
                    }
                } else if (o[2] == 0 && o[0] < theirEffect) {
                    more.add(new TextEdit(o[0], o[1], fresh));
                }
            }
            if (!more.isEmpty()) {
                applyEdits(c, more);
                readers++;
            }
            if (theirs >= 0) {
                break;
            }
        }
        if (tell) {
            flash("'" + name + "' redefined in " + shortLabel(at) + " is now '" + fresh + "'"
                  + (readers == 0 ? "" : ", as in the " + readers + (readers == 1 ? " cell" : " cells") + " reading it"));
        }
        return true;
    }

    /**
     * F2: renames the symbol under the caret. A notebook variable is renamed in every cell (a local
     * of the same spelling, an attribute, a keyword argument stay), in the Markdown cells' inline code
     * and in the kernel, where its value moves to the new name: nothing needs to run again.
     */
    private void renameSymbol(CellPanel cp) {
        if (cp.codeEditor == null) {
            return;
        }
        PySemantics.Result r = resultOf(cp.cell);
        PySemantics.Occurrence o = occurrenceAt(r, cp.codeEditor.getCaretPosition());
        if (o == null || o.role() == PySemantics.Role.ATTRIBUTE || o.role() == PySemantics.Role.KWARG) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        String old = o.name();
        boolean global = o.external() || "module".equals(o.scope());
        Map<Cell, List<int[]>> places = new LinkedHashMap<>();
        StringBuilder all = new StringBuilder();
        if (global) {
            for (Cell c : codeCells()) {
                PySemantics.Result cr = resultOf(c);
                List<int[]> occ = cr == null ? List.of() : cr.globalOccurrences(old);
                if (!occ.isEmpty()) {
                    places.put(c, occ);
                }
                all.append(textOf(c)).append('\n');
            }
        } else {
            places.put(cp.cell, r.sameSymbol(o.start()));
            all.append(textOf(cp.cell));
        }
        List<int[]> ranges = new ArrayList<>();
        places.values().forEach(ranges::addAll);
        String scope = global ? "in " + places.size() + (places.size() == 1 ? " cell" : " cells")
                              : "in " + (o.scope() == null || o.scope().isEmpty() ? "this cell" : o.scope());
        SmartRename.Plan plan = new SmartRename.Plan(old, ranges, 0, scope, 0, all.length());
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createEmptyBorder());
        RenameBox box = new RenameBox(plan, LanguageSpec.PYTHON, all.toString(), nw -> {
            popup.setVisible(false);
            applyRename(cp, old, nw, global, places);
        }, () -> popup.setVisible(false));
        popup.add(box);
        try {
            Rectangle at = cp.codeEditor.modelToView2D(o.start()).getBounds();
            popup.show(cp.codeEditor, at.x, at.y + at.height + 2);
        } catch (javax.swing.text.BadLocationException ex) {
            popup.show(cp.codeEditor, 0, 0);
        }
        SwingUtilities.invokeLater(box::focusField);
    }

    private void applyRename(CellPanel origin, String old, String nw, boolean global, Map<Cell, List<int[]>> places) {
        if (nw.equals(old)) {
            return;
        }
        if (global && (NotebookDataflow.defined(semantics.summaries()).contains(nw) || semantics.live(nw) != null)) {
            int ok = JOptionPane.showConfirmDialog(this, "'" + nw + "' is already a variable of this notebook: renaming '" + old
                + "' to it makes the two one.\nRename anyway?", "Rename", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
        }
        int count = 0;
        for (Map.Entry<Cell, List<int[]>> e : places.entrySet()) {
            Cell c = e.getKey();
            boolean upToDate = textOf(c).equals(ranSource.get(c));
            List<TextEdit> edits = new ArrayList<>();
            for (int[] o : e.getValue()) {
                edits.add(new TextEdit(o[0], o[1], nw));
                count++;
            }
            applyEdits(c, edits);
            if (upToDate) {
                ranSource.put(c, textOf(c)); // the same code under another name: still what ran
            }
            Set<String> rb = ranBinds.get(c);
            if (global && rb != null && rb.contains(old)) {
                Set<String> moved = new TreeSet<>(rb);
                moved.remove(old);
                moved.add(nw);
                ranBinds.put(c, moved);
            }
        }
        int prose = 0;
        if (global) {
            for (Component comp : cellsContainer.getComponents()) {
                if (comp instanceof CellPanel p && "markdown".equals(p.cell.cell_type) && p.markdownEditor != null) {
                    String md = p.markdownEditor.getText();
                    String now = md.replace("`" + old + "`", "`" + nw + "`");
                    if (!now.equals(md)) {
                        prose++;
                        p.markdownEditor.setText(now);
                        p.refreshPreview();
                    }
                }
            }
        }
        boolean held = global && semantics.live(old) != null;
        boolean moved = global && executor != null && executor.isAlive() && executor.renameVariable(old, nw);
        peeks.remove(old);
        flash("Renamed " + old + " → " + nw + ": " + count + (count == 1 ? " place" : " places") + " in " + places.size()
              + (places.size() == 1 ? " cell" : " cells") + (prose > 0 ? " and " + prose + " in text" : "")
              + (moved && held ? "; the kernel kept its value" : ""));
        refreshSummaries();
        recomputeDataflow();
        scheduleAnalysis();
        origin.focusEditor();
    }

    // -----------------------------
    // The top line: fitted to the window; the notebook's state in it
    // -----------------------------

    /** A symbol when the font draws it, else its plain stand-in. */
    private static String glyph(Font f, String symbol, String fallback) {
        return f != null && f.canDisplayUpTo(symbol) == -1 ? symbol : fallback;
    }

    /**
     * Everything at the top on one line, readable whole. When the window is too narrow, what says
     * least gives way first: the kernel's version, the save time, the kernel's memory, then the long
     * wording of the notebook's state -- never the kernel's name, never the state itself.
     */
    private void fitHeader() {
        if (northPanel == null || toolbar == null || kernelChip == null || statePanel == null) {
            return;
        }
        int avail = northPanel.getWidth();
        if (avail <= 0) {
            return;
        }
        Object longText = reproLabel.getClientProperty("long");
        Object shortText = reproLabel.getClientProperty("short");
        boolean saved = !saveStatus.getText().isBlank();
        for (int level = 0; level <= 4; level++) {
            kernelChip.setDensity(level == 0 ? 0 : level <= 2 ? 1 : 2);
            saveStatus.setVisible(saved && level < 2);
            if (longText instanceof String l) {
                reproLabel.setText(level >= 4 && shortText instanceof String s ? s : l);
            }
            int need = toolbar.getPreferredSize().width + statePanel.getPreferredSize().width
                       + kernelPanel.getPreferredSize().width + 16;
            if (need <= avail) {
                break;
            }
        }
        northPanel.revalidate();
        northPanel.repaint();
    }

    private void setStateLabel(String longText, String shortText, Color color, String tooltip) {
        reproLabel.putClientProperty("long", longText);
        reproLabel.putClientProperty("short", shortText);
        reproLabel.setText(longText);
        reproLabel.setForeground(color);
        reproLabel.setToolTipText(tooltip);
        fitHeader();
    }

    /** Linear: whether the notebook survives Restart & Run All. Reactive: a run under way, conflicts, stale cells. */
    private void updateStateLabel() {
        if (reproLabel == null) {
            return;
        }
        if (!reactive) {
            if (fragileCells < 0) {
                setStateLabel(" ", " ", palette.getTextSecondary(), null);
            } else if (fragileCells == 0) {
                setStateLabel("✓ runs top to bottom", "✓ top to bottom", palette.getLogSuccessPrefix(),
                              "Every name a cell reads is defined above it or by the cell itself.");
            } else {
                setStateLabel("⚠ " + fragileCells + (fragileCells == 1 ? " cell" : " cells") + " would not survive a restart",
                              "⚠ " + fragileCells + " fragile", palette.getLogWarnPrefix(),
                              "<html>Run top to bottom, as Restart &amp; Run All does, these fail or read state "
                              + "no cell above creates:" + fragileWhy + "<br>Click to go to the first.</html>");
            }
            return;
        }
        int broken = dataflow.count(NotebookDataflow.Status.CONFLICT) + dataflow.count(NotebookDataflow.Status.BLOCKED);
        int stale = dataflow.count(NotebookDataflow.Status.STALE) + dataflow.count(NotebookDataflow.Status.EDITED);
        int failed = dataflow.count(NotebookDataflow.Status.FAILED);
        int notRun = dataflow.count(NotebookDataflow.Status.NOT_RUN);
        if (reactiveTotal > 0) {
            String run = "▶ running " + Math.min(reactiveTotal, reactiveDone + 1) + " of " + reactiveTotal;
            setStateLabel(run, run, palette.getAccent(), "A reactive run: the Dataflow map shows it live; Stop drops what waits.");
        } else if (broken > 0) {
            setStateLabel("⚠ " + broken + (broken == 1 ? " cell cannot run" : " cells cannot run"), "⚠ " + broken + " blocked",
                          palette.getError(), "Two cells define the same variable, or cells take from one another: "
                                              + "each says why under its code. Click to go to the first.");
        } else if (failed > 0) {
            setStateLabel("⚠ " + failed + (failed == 1 ? " cell failed" : " cells failed"), "⚠ " + failed + " failed",
                          palette.getError(), "Click to go to the first.");
        } else if (stale > 0) {
            setStateLabel("⚠ " + stale + " stale", "⚠ " + stale + " stale", SemanticStyle.staleColor(),
                          "Edited, or taking from a cell that ran again: Run Stale brings them up to date.");
        } else if (notRun == dataflow.nodes().size() && notRun > 0) {
            setStateLabel("reactive", "reactive", palette.getTextSecondary(),
                          "Cells run in the graph's order: run one, and what it needs and what needs it follow.");
        } else {
            setStateLabel("✓ reactive · in sync", "✓ in sync", palette.getLogSuccessPrefix(),
                          "Every cell that ran matches its code and its inputs.");
        }
    }

    private transient JLabel toast;

    /**
     * What an action did out of sight (a rename across cells), said for a few seconds in a toast at the
     * bottom of the window: over the cells, so the top line keeps all it shows.
     */
    private void flash(String message) {
        JLayeredPane layers = getLayeredPane();
        if (layers == null) {
            return;
        }
        if (toast == null) {
            toast = new JLabel() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(EditorTheme.widgetBackground());
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                    g2.setColor(EditorTheme.widgetBorder());
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            toast.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            toast.setForeground(EditorTheme.foreground());
            toast.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
            layers.add(toast, JLayeredPane.POPUP_LAYER);
            flashTimer = new javax.swing.Timer(6000, e -> toast.setVisible(false));
            flashTimer.setRepeats(false);
        }
        toast.setText(message);
        Dimension d = toast.getPreferredSize();
        int w = Math.min(d.width, Math.max(200, layers.getWidth() - 40));
        toast.setBounds((layers.getWidth() - w) / 2, Math.max(0, layers.getHeight() - d.height - 28), w, d.height);
        toast.setVisible(true);
        toast.repaint();
        flashTimer.restart();
    }

    // -----------------------------
    // After a run: the doctor on an error, the delta on a success, the time it took
    // -----------------------------

    /**
     * What is said under a cell after a run: the doctor's advice on its error (doctor), or what the
     * run changed in its outputs. pending: work under way (an install) that a new run does not cancel.
     */
    private record Insight(boolean doctor, boolean pending, Icon icon, String html, NotebookIcons.Tone tone,
                           List<InsightAction> actions) {
    }

    private record InsightAction(String label, String tip, Runnable run) {
    }

    private boolean isRunning() {
        return executor != null && executor.isBusy() || reactiveInFlight != null;
    }

    private void setInsight(Cell cell, Insight in) {
        if (in == null) {
            insights.remove(cell);
        } else {
            insights.put(cell, in);
        }
        CellPanel cp = panelFor(cell);
        if (cp != null) {
            cp.showInsight(in);
        }
    }

    /** A saved cell's outputs: what its first run here is compared with. */
    private void seedDelta(Cell cell) {
        if (!"code".equals(cell.cell_type) || cell.outputs.isEmpty()) {
            return;
        }
        for (Output o : cell.outputs) {
            if ("error".equals(o.output_type)) {
                return;
            }
        }
        deltaBase.put(cell, OutputDelta.snapshot(NotebookIO.joinLines(cell.source), cell.outputs, -1));
    }

    /** A cell finished: how long it took; if it failed, the doctor; else what its outputs changed. */
    private void afterRun(Cell cell, long millis) {
        lastMillis.put(cell, millis);
        Output error = null;
        for (Output o : cell.outputs) {
            if ("error".equals(o.output_type)) {
                error = o;
                break;
            }
        }
        Insight current = insights.get(cell);
        if (current != null && current.pending()) {
            return; // an install under way keeps its line; the delta waits for the next run
        }
        if (error != null) {
            if (!"KeyboardInterrupt".equals(error.ename)) {
                diagnose(cell, error);
            }
            return; // the delta keeps the last run that worked, to compare the next one with
        }
        String ran = ranSource.getOrDefault(cell, NotebookIO.joinLines(cell.source));
        OutputDelta.Snapshot now = OutputDelta.snapshot(ran, cell.outputs, kernelEpoch);
        OutputDelta.Snapshot before = deltaBase.put(cell, now);
        setInsight(cell, before == null ? null : deltaInsight(cell, before, now));
    }

    /**
     * What a run changed, in one line: the numbers that moved most, with their relative change; how
     * many lines differ; whether a plot changed. A run that changed nothing says so when that is news:
     * after an edit (the edit changed nothing visible), after a restart or against the saved notebook
     * (the results are reproduced).
     */
    private Insight deltaInsight(Cell cell, OutputDelta.Snapshot before, OutputDelta.Snapshot now) {
        OutputDelta.Delta d = OutputDelta.compare(before, now);
        boolean edited = before.source() != null && !before.source().equals(now.source());
        String since = before.epoch() < 0 ? "the saved notebook"
                     : before.epoch() != now.epoch() ? "before the restart" : "the last run";
        if (d.nothing()) {
            if (now.text().isBlank() && d.rich() == 0) {
                return null;
            }
            if (edited) {
                return new Insight(false, false, NotebookIcons.same(14), "Same output as before your edit",
                                   NotebookIcons.Tone.MUTED, List.of());
            }
            if (before.epoch() != now.epoch()) {
                return new Insight(false, false, NotebookIcons.check(14, () -> NotebookIcons.Tone.RUN),
                                   "Same output as " + escape(since) + ": <b>reproduced</b>", NotebookIcons.Tone.RUN, List.of());
            }
            return null;
        }
        Font f = FontLoader.getGlobalFont(Font.PLAIN, 12);
        String arrow = glyph(f, " → ", " -> ");
        String dot = glyph(f, " · ", " | ");
        String muted = hex(palette.getTextSecondary());
        List<String> parts = new ArrayList<>();
        switch (d.kind()) {
            case NUMBERS -> {
                Set<OutputDelta.Change> top = Collections.newSetFromMap(new IdentityHashMap<>());
                top.addAll(OutputDelta.largest(d.changes(), 3));
                for (OutputDelta.Change c : d.changes()) {
                    if (top.contains(c)) {
                        parts.add("<b>" + escape(c.label()) + "</b> " + escape(c.before()) + arrow + escape(c.after())
                                  + " <span style='color:" + muted + "'>(" + escape(OutputDelta.relative(c)) + ")</span>");
                    }
                }
                if (d.changes().size() > top.size()) {
                    parts.add("+" + (d.changes().size() - top.size()) + " more");
                }
            }
            case TEXT -> parts.add("the output changed: <b>+" + d.added() + "</b> / <b>-" + d.removed() + "</b> lines");
            case SAME -> { }
        }
        if (d.richChanged()) {
            parts.add(d.rich() <= 1 ? "the plot changed" : "the plots changed");
        }
        String html = "<span style='color:" + muted + "'>vs " + escape(since) + ":</span> " + String.join(dot, parts);
        List<InsightAction> actions = d.kind() == OutputDelta.Kind.SAME ? List.of()
            : List.of(new InsightAction("Compare", "Every change, before and after",
                                        () -> showDeltaDetails(cell, since, d)));
        return new Insight(false, false, NotebookIcons.delta(14, () -> NotebookIcons.Tone.INFO), html,
                           NotebookIcons.Tone.INFO, actions);
    }

    /** Every number that moved, or the lines that differ (unchanged ones shown only around a change). */
    private void showDeltaDetails(Cell cell, String since, OutputDelta.Delta d) {
        String fg = hex(palette.getTextPrimary());
        String muted = hex(palette.getTextSecondary());
        String plus = hex(NotebookIcons.color(NotebookIcons.Tone.RUN));
        String minus = hex(NotebookIcons.color(NotebookIcons.Tone.STOP));
        String name = shortLabel(codeIndexOf(cell));
        StringBuilder html = new StringBuilder("<html><body style='font-family:sans-serif; font-size:11px; color:" + fg + "'>");
        html.append("<p><b>").append(escape(name)).append("</b> <span style='color:").append(muted).append("'>vs ")
            .append(escape(since)).append("</span></p>");
        if (d.kind() == OutputDelta.Kind.NUMBERS) {
            html.append("<table cellspacing='0' cellpadding='3'><tr style='color:").append(muted)
                .append("'><td>what</td><td align='right'>before</td><td align='right'>after</td><td align='right'>change</td></tr>");
            for (OutputDelta.Change c : d.changes()) {
                html.append("<tr><td><b>").append(escape(c.label())).append("</b></td><td align='right'>")
                    .append(escape(c.before())).append("</td><td align='right'>").append(escape(c.after()))
                    .append("</td><td align='right' style='color:").append(c.to() >= c.from() ? plus : minus).append("'>")
                    .append(escape(OutputDelta.relative(c))).append("</td></tr>");
            }
            html.append("</table>");
        } else if (d.kind() == OutputDelta.Kind.TEXT) {
            if (d.diff().isEmpty()) {
                html.append("<p>Too long to compare line by line: ").append(d.added()).append(" lines added, ")
                    .append(d.removed()).append(" removed.</p>");
            } else {
                html.append("<pre style='font-family:monospaced; font-size:11px'>");
                List<String> lines = d.diff();
                boolean gap = false;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    char kind = line.isEmpty() ? ' ' : line.charAt(0);
                    String body = escape(line.length() > 1 ? line.substring(1) : "");
                    if (kind == ' ') {
                        boolean near = false;
                        for (int k = Math.max(0, i - 2); k <= Math.min(lines.size() - 1, i + 2) && !near; k++) {
                            near = !lines.get(k).isEmpty() && lines.get(k).charAt(0) != ' ';
                        }
                        if (!near) {
                            if (!gap) {
                                html.append("<span style='color:").append(muted).append("'>  …</span>\n");
                            }
                            gap = true;
                            continue;
                        }
                    }
                    gap = false;
                    String color = kind == '+' ? plus : kind == '-' ? minus : muted;
                    html.append("<span style='color:").append(color).append("'>").append(kind).append(' ')
                        .append(body).append("</span>\n");
                }
                html.append("</pre>");
            }
        }
        if (d.richChanged()) {
            html.append("<p>").append(d.rich() <= 1 ? "The plot" : "The plots").append(" changed too.</p>");
        }
        html.append("</body></html>");
        showHtmlDialog("What changed in " + name, html.toString());
    }

    private void showHtmlDialog(String title, String html) {
        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        pane.setBackground(palette.getBackgroundSurface());
        pane.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JDialog dialog = new JDialog(this, title, false);
        dialog.getContentPane().add(scroll);
        dialog.setSize(720, 460);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showTextDialog(String title, String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        area.setBackground(palette.getBackgroundSurface());
        area.setForeground(palette.getTextPrimary());
        area.setCaretPosition(Math.max(0, text.length()));
        JDialog dialog = new JDialog(this, title, false);
        dialog.getContentPane().add(new JScrollPane(area));
        dialog.setSize(760, 420);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ---- The doctor --------------------------------------------------------

    /**
     * Reads a failed cell's error with what the notebook knows -- which cell defines what, which
     * ran, what the kernel holds, which files sit by the notebook -- and offers the fixes under
     * the cell. The files are looked for off the event thread.
     */
    private void diagnose(Cell cell, Output error) {
        String source = ranSource.getOrDefault(cell, NotebookIO.joinLines(cell.source));
        EditorDiagnostic at = NotebookInsight.errorLine(error.ename, error.evalue, error.traceback, cell.execution_count);
        refreshSummaries();
        List<PySemantics.Summary> sums = semantics.summaries();
        List<Set<String>> binds = new ArrayList<>(sums.size());
        List<Boolean> ran = new ArrayList<>(sums.size());
        List<String> labels = new ArrayList<>(sums.size());
        for (int i = 0; i < sums.size(); i++) {
            PySemantics.Summary s = sums.get(i);
            binds.add(s == null ? Set.of() : Set.copyOf(s.bound().keySet()));
            Cell c = codeCellAt(i);
            ran.add(c != null && c.execution_count != null);
            labels.add(shortLabel(i));
        }
        Path parent = currentPath == null ? null : currentPath.toAbsolutePath().getParent();
        Path folder = parent != null ? parent : Path.of("").toAbsolutePath();
        CellDoctor.Context ctx = new CellDoctor.Context(source, at == null ? -1 : at.getLine(), codeIndexOf(cell),
            binds, ran, i -> i >= 0 && i < labels.size() ? labels.get(i) : "a cell", Set.copyOf(liveKinds.keySet()), folder);
        int serial = runSerial.getOrDefault(cell, 0);
        String ename = error.ename;
        String evalue = error.evalue;
        offEdt(() -> {
            CellDoctor.Diagnosis d;
            try {
                d = CellDoctor.diagnose(ename, evalue, ctx);
            } catch (RuntimeException ex) {
                AppLogger.error("Jupylab's cell doctor failed on " + ename, ex);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (d.isEmpty() || runSerial.getOrDefault(cell, 0) != serial || codeIndexOf(cell) < 0) {
                    return; // nothing to add, or the cell ran again, or is gone
                }
                setInsight(cell, doctorInsight(cell, source, d));
            });
        });
    }

    private Insight doctorInsight(Cell cell, String source, CellDoctor.Diagnosis d) {
        List<InsightAction> actions = new ArrayList<>();
        for (CellDoctor.Fix fix : d.fixes()) {
            CellDoctor.Fix chosen = fix;
            if (fix instanceof CellDoctor.Install in && pipPython() == null) {
                String command = "python -m pip install " + in.pipName();
                chosen = new CellDoctor.Copy("Copy the pip command",
                    "This kernel was not started from a Python Sphere can call: copies '" + command + "'", command);
            }
            CellDoctor.Fix f = chosen;
            actions.add(new InsightAction(f.label(), f.tip(), () -> applyFix(cell, source, f)));
        }
        return new Insight(true, false, NotebookIcons.doctor(15), escape(d.message()), NotebookIcons.Tone.ACCENT, actions);
    }

    private void applyFix(Cell cell, String source, CellDoctor.Fix fix) {
        switch (fix) {
            case CellDoctor.Edit edit -> {
                if (!textOf(cell).equals(source)) {
                    Toolkit.getDefaultToolkit().beep();
                    flash("The cell changed since it ran: run it again for fresh advice");
                    return;
                }
                List<TextEdit> edits = new ArrayList<>();
                for (CellDoctor.Span s : edit.spans()) {
                    edits.add(new TextEdit(s.start(), s.end(), s.text()));
                }
                setInsight(cell, null);
                applyEdits(cell, edits);
                CellPanel cp = panelFor(cell);
                if (cp != null && cp.codeEditor != null) {
                    runCell(cp, RunThen.STAY);
                }
            }
            case CellDoctor.RunFirst first -> runFirst(cell, first.definer());
            case CellDoctor.Install in -> install(cell, in.pipName());
            case CellDoctor.Copy copy -> {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                       .setContents(new java.awt.datatransfer.StringSelection(copy.text()), null);
                flash("Copied: " + copy.text());
            }
        }
    }

    /** "Run it first": the cell defining the name, what that cell needs that is not current, then this cell. */
    private void runFirst(Cell cell, int definer) {
        setInsight(cell, null);
        syncUIToModel();
        refreshSummaries();
        recomputeDataflow();
        Cell def = codeCellAt(definer);
        if (def == null) {
            return;
        }
        if (reactive) {
            runReactive(List.of(def, cell));
            return;
        }
        Set<Integer> chain = new LinkedHashSet<>();
        for (int u : dataflow.upstream(definer)) {
            NotebookDataflow.Node n = dataflow.node(u);
            if (n != null && (n.status() == NotebookDataflow.Status.NOT_RUN || n.status() == NotebookDataflow.Status.FAILED
                              || n.outdated())) {
                chain.add(u);
            }
        }
        chain.add(definer);
        List<Cell> list = new ArrayList<>();
        for (int i : dataflow.inRunOrder(chain)) {
            Cell c = codeCellAt(i);
            if (c != null && c != cell) {
                list.add(c);
            }
        }
        list.add(cell);
        setDirty(true);
        offEdt(() -> executor().runAll(list));
    }

    /**
     * The Python pip installs into: the kernel's own interpreter when the kernel is started from
     * one; null for a kernel started otherwise (a container, another machine), where only its own
     * shell knows its pip.
     */
    private String pipPython() {
        KernelSpecs.Spec spec = executor != null && executor.spec() != null ? executor.spec() : selectedSpec;
        if (spec == null || spec.isSphereDriver() || spec.argv().isEmpty()) {
            String exec = settings.resolveTool("PYTHON_EXEC", "python");
            return exec == null || exec.isBlank() ? null : exec;
        }
        String exe = spec.argv().get(0);
        String name = exe.substring(Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return !exe.contains("{") && name.startsWith("python") ? exe : null;
    }

    /** pip install into the kernel's Python, once the user agrees; its progress on the cell's line. */
    private void install(Cell cell, String pkg) {
        String python = pipPython();
        if (python == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
            "<html>Install <b>" + escape(pkg) + "</b> with pip into the kernel's Python?<br><br><code>"
            + escape(python) + " -m pip install " + escape(pkg) + "</code></html>",
            "Install a package", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        java.util.function.Consumer<String> progress = line -> setInsight(cell, new Insight(true, true,
            NotebookIcons.doctor(15), "Installing <b>" + escape(pkg) + "</b>…"
            + (line == null || line.isBlank() ? "" : " <span style='color:" + hex(palette.getTextSecondary()) + "'>"
                + escape(line.length() > 90 ? line.substring(0, 89) + "…" : line) + "</span>"),
            NotebookIcons.Tone.ACCENT, List.of()));
        progress.accept(null);
        offEdt(() -> {
            StringBuilder log = new StringBuilder();
            int exit;
            try {
                Process p = new ProcessBuilder(python, "-m", "pip", "install", "--disable-pip-version-check", pkg)
                    .redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                                                                               java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    long shown = 0;
                    while ((line = r.readLine()) != null) {
                        log.append(line).append('\n');
                        long now = System.currentTimeMillis();
                        if (now - shown > 250 && !line.isBlank()) {
                            shown = now;
                            String l = line.strip();
                            SwingUtilities.invokeLater(() -> progress.accept(l));
                        }
                    }
                }
                exit = p.waitFor();
            } catch (IOException ex) {
                log.append(ex.getMessage());
                exit = -1;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exit = -1;
            }
            int code = exit;
            SwingUtilities.invokeLater(() -> {
                if (code == 0) {
                    setInsight(cell, new Insight(true, false, NotebookIcons.check(15, () -> NotebookIcons.Tone.RUN),
                        "<b>" + escape(pkg) + "</b> is installed", NotebookIcons.Tone.RUN,
                        List.of(new InsightAction("Run again", "Runs this cell again", () -> {
                            setInsight(cell, null);
                            CellPanel cp = panelFor(cell);
                            if (cp != null && cp.codeEditor != null) {
                                runCell(cp, RunThen.STAY);
                            }
                        }))));
                } else {
                    String last = "";
                    for (String l : log.toString().split("\n")) {
                        if (!l.isBlank()) {
                            last = l.strip();
                        }
                    }
                    setInsight(cell, new Insight(true, false, NotebookIcons.doctor(15),
                        "pip could not install <b>" + escape(pkg) + "</b> (exit " + code + ")"
                        + (last.isEmpty() ? "" : ": " + escape(last.length() > 120 ? last.substring(0, 119) + "…" : last)),
                        NotebookIcons.Tone.STOP,
                        List.of(new InsightAction("Show pip's output", "Everything pip said",
                                                  () -> showTextDialog("pip install " + pkg, log.toString())))));
                }
            });
        });
    }

    // ---- How long a run will take ------------------------------------------

    /** The cells Run Stale would run. */
    private List<Cell> staleCells() {
        List<Cell> out = new ArrayList<>();
        for (int i : dataflow.outdated()) {
            Cell c = codeCellAt(i);
            if (c != null) {
                out.add(c);
            }
        }
        if (reactive) {
            for (int i = 0; i < dataflow.nodes().size(); i++) {
                Cell c = codeCellAt(i);
                if (c != null && dataflow.node(i).status() == NotebookDataflow.Status.NOT_RUN) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** "≈ 12.4 s from the last runs, plus 2 cells never timed", as a tooltip's last line; empty when none was timed. */
    private String estimateLine(Collection<Cell> cells) {
        long total = 0;
        int timed = 0;
        int untimed = 0;
        for (Cell c : cells) {
            if (!"code".equals(c.cell_type)) {
                continue;
            }
            Long ms = lastMillis.get(c);
            if (ms == null) {
                untimed++;
            } else {
                total += ms;
                timed++;
            }
        }
        if (timed == 0) {
            return "";
        }
        String about = glyph(FontLoader.getGlobalFont(Font.PLAIN, 12), "≈ ", "~ ");
        return "<br><i>" + about + formatDuration(total) + " from the last runs"
               + (untimed > 0 ? ", plus " + untimed + (untimed == 1 ? " cell" : " cells") + " never timed" : "") + "</i>";
    }

    private static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        long s = Math.round(millis / 1000.0);
        return s / 60 + " min " + (s % 60 < 10 ? "0" : "") + s % 60 + " s";
    }

    // ---- Gather ------------------------------------------------------------

    private void showGatherMenu(Cell cell, JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem asNotebook = new JMenuItem("Gather into a new notebook");
        asNotebook.addActionListener(e -> gather(cell, false));
        JMenuItem asScript = new JMenuItem("Export as a Python script...");
        asScript.addActionListener(e -> gather(cell, true));
        menu.add(asNotebook);
        menu.add(asScript);
        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * The cells a cell needs and no others (NotebookGather), in an order that runs: in a new window,
     * to run in a fresh kernel -- a result that reproduces there reproduces anywhere -- or as a
     * Python script in the percent format, for a batch job.
     */
    private void gather(Cell target, boolean script) {
        syncUIToModel();
        refreshSummaries();
        recomputeDataflow();
        int t = codeIndexOf(target);
        if (t < 0) {
            return;
        }
        List<Integer> slice = NotebookGather.slice(dataflow.nodes().size(), dataflow.edges(), t);
        Set<String> missing = NotebookGather.unresolved(slice, semantics.summaries());
        List<String> sources = new ArrayList<>();
        for (int i : slice) {
            Cell c = codeCellAt(i);
            if (c != null) {
                sources.add(textOf(c));
            }
        }
        List<Integer> pageOrder = new ArrayList<>(slice);
        Collections.sort(pageOrder);
        boolean moved = !pageOrder.equals(slice);
        String from = currentPath == null ? "an untitled notebook" : currentPath.getFileName().toString();
        String who = shortLabel(t);
        int total = codeCells().size();
        String what = "the " + sources.size() + " of " + total + " code cells that " + who + " needs, in an order that runs"
                      + " top to bottom" + (moved ? " (cells defining a name below its use were moved up)" : "");
        List<String> names = new ArrayList<>(missing);
        if (script) {
            String header = "Gathered by Sphere's Jupylab from " + from + ":\n" + what + "."
                + (names.isEmpty() ? "" : "\nIt reads " + String.join(", ", names) + ", which no cell defines: "
                                          + (names.size() == 1 ? "it" : "they") + " lived only in the kernel.");
            exportScript(header, sources);
            return;
        }
        StringBuilder head = new StringBuilder("**Gathered** from `" + from + "`: " + what + ".");
        if (!names.isEmpty()) {
            head.append("\n\n> It reads ");
            for (int k = 0; k < names.size(); k++) {
                head.append(k == 0 ? "" : ", ").append('`').append(names.get(k)).append('`');
            }
            head.append(", which no cell defines: ").append(names.size() == 1 ? "it" : "they").append(" lived only in the kernel.");
        }
        Notebook nb = new Notebook();
        nb.nbformat = notebook.nbformat;
        nb.nbformat_minor = notebook.nbformat_minor;
        nb.metadata = new LinkedHashMap<>(notebook.metadata);
        Cell first = new Cell();
        first.cell_type = "markdown";
        first.source = head.toString();
        nb.cells.add(first);
        for (String src : sources) {
            Cell c = new Cell();
            c.cell_type = "code";
            c.source = src;
            nb.cells.add(c);
        }
        Path suggested = null;
        if (currentPath != null && currentPath.toAbsolutePath().getParent() != null) {
            Path dir = currentPath.toAbsolutePath().getParent();
            String base = stem(currentPath) + "-gathered";
            suggested = dir.resolve(base + ".ipynb");
            for (int k = 2; Files.exists(suggested); k++) {
                suggested = dir.resolve(base + "-" + k + ".ipynb");
            }
        }
        JupylabXeditor window = new JupylabXeditor(suggested, nb);
        window.setDirty(true);
        window.setVisible(true);
        window.toFront();
        flash("Gathered " + sources.size() + " of " + total + " cells into a new window: run it in its fresh kernel");
    }

    private void exportScript(String header, List<String> sources) {
        Path dir = currentPath == null ? null : currentPath.toAbsolutePath().getParent();
        JFileChooser fc = new JFileChooser(dir == null ? null : dir.toFile());
        fc.setSelectedFile(new File((currentPath == null ? "gathered" : stem(currentPath) + "-gathered") + ".py"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path out = fc.getSelectedFile().toPath();
        if (!out.getFileName().toString().contains(".")) {
            out = out.resolveSibling(out.getFileName() + ".py");
        }
        if (Files.exists(out) && JOptionPane.showConfirmDialog(this, out.getFileName() + " exists. Replace it?",
                "Export as a Python script", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.writeString(out, NotebookGather.script(header, sources), java.nio.charset.StandardCharsets.UTF_8);
            flash("Wrote " + out.getFileName() + ": " + sources.size() + (sources.size() == 1 ? " cell" : " cells"));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write " + out + ": " + ex.getMessage());
        }
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // -----------------------------
    // Navigation across cells: the same symbol everywhere, its definition on F12 / Ctrl+click
    // -----------------------------

    private List<CellPanel> codePanels() {
        List<CellPanel> out = new ArrayList<>();
        if (cellsContainer != null) {
            for (Component c : cellsContainer.getComponents()) {
                if (c instanceof CellPanel cp && cp.codeEditor != null && cp.highlighter != null) {
                    out.add(cp);
                }
            }
        }
        return out;
    }

    private static PySemantics.Occurrence occurrenceAt(PySemantics.Result r, int offset) {
        if (r == null) {
            return null;
        }
        PySemantics.Occurrence o = r.occurrence(offset);
        return o != null || offset <= 0 ? o : r.occurrence(offset - 1);
    }

    /**
     * The caret on a name highlights that very symbol: in its own cell every use of
     * it (a local of another function with the same spelling stays dark); for a
     * notebook global, its uses in every cell, its bindings stronger.
     */
    private void updateSymbolMarks(CellPanel source) {
        if (source.codeEditor == null || source.highlighter == null || !source.codeEditor.isFocusOwner()) {
            return;
        }
        List<CellPanel> panels = codePanels();
        PySemantics.Result r = source.highlighter.isCurrent() ? source.highlighter.snapshot().semantic : null;
        PySemantics.Occurrence o = null;
        if (semanticColors && r != null && source.codeEditor.getSelectionStart() == source.codeEditor.getSelectionEnd()) {
            o = occurrenceAt(r, source.codeEditor.getCaretPosition());
        }
        if (o == null || o.role() == PySemantics.Role.ATTRIBUTE || o.role() == PySemantics.Role.KWARG) {
            for (CellPanel cp : panels) {
                cp.codeEditor.setSymbolMarks(List.of());
            }
            return;
        }
        boolean global = o.external() || "module".equals(o.scope());
        for (CellPanel cp : panels) {
            PySemantics.Result other = cp.highlighter.isCurrent() ? cp.highlighter.snapshot().semantic : null;
            if (!global) {
                cp.codeEditor.setSymbolMarks(cp == source ? r.sameSymbol(o.start()) : List.of());
            } else {
                cp.codeEditor.setSymbolMarks(other == null ? List.of() : other.globalOccurrences(o.name()));
            }
        }
    }

    private void clearMarksUnlessEditing() {
        for (CellPanel cp : codePanels()) {
            if (cp.codeEditor.isFocusOwner()) {
                return;
            }
        }
        for (CellPanel cp : codePanels()) {
            cp.codeEditor.setSymbolMarks(List.of());
        }
    }

    /** Where a definition lies: a cell, an offset in it, a length. */
    private record Definition(CellPanel panel, int offset, int length) {
    }

    /**
     * The definition of the name at offset: in the cell itself for a local, a
     * parameter, or a global bound above it there; else in the nearest cell above
     * binding it (what Restart & Run All reads), else in the last cell to have
     * bound it, else the first. Null for an attribute, a builtin, a name nowhere bound.
     */
    private Definition findDefinition(CellPanel cp, int offset) {
        if (cp.highlighter == null || !cp.highlighter.isCurrent()) {
            return null;
        }
        PySemantics.Result r = cp.highlighter.snapshot().semantic;
        PySemantics.Occurrence o = occurrenceAt(r, offset);
        if (o == null || o.role() == PySemantics.Role.ATTRIBUTE || o.role() == PySemantics.Role.KWARG) {
            return null;
        }
        int length = o.name().length();
        int here = r.definitionOf(o.start());
        boolean global = o.external() || "module".equals(o.scope());
        if (!global) {
            return here >= 0 ? new Definition(cp, here, length) : null;
        }
        if (here >= 0 && here <= o.start()) {
            return new Definition(cp, here, length);
        }
        int self = codeIndexOf(cp.cell);
        List<Integer> binders = semantics.binders(o.name());
        int pick = -1;
        for (int b : binders) {
            if (b < self && b != self) {
                pick = b; // the nearest above wins: binders are in notebook order
            }
        }
        if (pick < 0) {
            int best = Integer.MIN_VALUE;
            for (int b : binders) {
                Cell c = codeCellAt(b);
                if (b != self && c != null && c.execution_count != null && c.execution_count > best) {
                    best = c.execution_count;
                    pick = b;
                }
            }
        }
        if (pick < 0) {
            for (int b : binders) {
                if (b != self) {
                    pick = b;
                    break;
                }
            }
        }
        if (pick < 0) {
            return here >= 0 ? new Definition(cp, here, length) : null;
        }
        CellPanel target = panelFor(codeCellAt(pick));
        if (target == null || target.highlighter == null || target.highlighter.snapshot().semantic == null) {
            return null;
        }
        int def = target.highlighter.snapshot().semantic.definitionOfGlobal(o.name());
        return def < 0 ? null : new Definition(target, def, length);
    }

    private void goToDefinition(CellPanel cp, int offset) {
        Definition d = findDefinition(cp, offset);
        if (d == null) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (d.panel() != cp) {
            scrollToCell(notebook.cells.indexOf(d.panel().cell));
        }
        SwingUtilities.invokeLater(() -> {
            CodeTextPane editor = d.panel().codeEditor;
            editor.requestFocusInWindow();
            editor.select(d.offset(), Math.min(editor.getDocument().getLength(), d.offset() + d.length()));
            SwingUtilities.invokeLater(() -> revealCaret(editor));
        });
    }

    /** Ctrl held over a name that has a definition: the name becomes a link. */
    private void ctrlLink(CellPanel cp, MouseEvent e) {
        if (!e.isControlDown()) {
            clearLink(cp);
            return;
        }
        int offset = cp.codeEditor.viewToModel2D(e.getPoint());
        Definition d = findDefinition(cp, offset);
        PySemantics.Occurrence o = d == null ? null
            : occurrenceAt(cp.highlighter.snapshot().semantic, offset);
        if (o == null) {
            clearLink(cp);
            return;
        }
        cp.codeEditor.setLink(o.start(), o.end());
        cp.codeEditor.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cp.linked = true;
    }

    private void clearLink(CellPanel cp) {
        if (cp.linked) {
            cp.codeEditor.setLink(-1, -1);
            cp.codeEditor.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            cp.linked = false;
        }
    }

    // -----------------------------
    // Data peek: what a value looks like, in the tooltip of its name
    // -----------------------------

    private void requestPeek(String name) {
        if (executor == null || !executor.isAlive() || executor.isBusy() || peekRequests.containsValue(name)) {
            return;
        }
        int id = executor.requestPeek(name);
        if (id >= 0) {
            peekRequests.put(id, name);
        }
    }

    /** The tooltip under the mouse, asked again: a peek has arrived for it. */
    private void refreshTooltip() {
        CellPanel cp = tooltipCell;
        java.awt.Point p = tooltipPoint;
        if (cp == null || p == null || cp.codeEditor == null || !cp.codeEditor.isShowing()) {
            return;
        }
        ToolTipManager.sharedInstance().mouseMoved(new MouseEvent(cp.codeEditor, MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(), 0, p.x, p.y, 0, false));
    }

    private static final String BLOCKS = new Font(Font.MONOSPACED, Font.PLAIN, 12).canDisplay('▇')
                                         ? "▁▂▃▄▅▆▇█" : ".,:;=+*#";

    private static String sparkline(List<?> counts) {
        double max = 0;
        for (Object o : counts) {
            max = Math.max(max, o instanceof Number n ? n.doubleValue() : 0);
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : counts) {
            double v = o instanceof Number n ? n.doubleValue() : 0;
            if (v <= 0 || max <= 0) {
                sb.append("&nbsp;");
            } else {
                int level = (int) Math.min(BLOCKS.length() - 1, Math.round((BLOCKS.length() - 1) * v / max));
                sb.append(BLOCKS.charAt(Math.max(0, level)));
            }
        }
        return sb.toString();
    }

    private static String num(Object o) {
        if (!(o instanceof Number n)) {
            return o == null ? "-" : String.valueOf(o);
        }
        double v = n.doubleValue();
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%,d", (long) v);
        }
        return String.format(Locale.ROOT, "%.4g", v);
    }

    /** A value at a glance: the distribution of its numbers, a frame's columns and first rows, a tree's branches. */
    private String peekHtml(Map<String, Object> pk, PySemantics.Family family) {
        StringBuilder sb = new StringBuilder();
        String muted = hex(EditorTheme.textMuted());
        String kind = String.valueOf(pk.get("kind"));
        Color fc = family == PySemantics.Family.NONE ? EditorTheme.foreground() : SemanticStyle.familyColor(family);
        switch (kind) {
            case "numbers" -> {
                List<?> hist = pk.get("hist") instanceof List<?> l ? l : List.of();
                if (!hist.isEmpty()) {
                    sb.append("<br><span style='font-family:monospaced; font-size:15pt; color:").append(hex(fc)).append("'>")
                      .append(sparkline(hist)).append("</span><br><span style='color:").append(muted).append("'>")
                      .append(num(pk.get("lo"))).append(" … ").append(num(pk.get("hi"))).append("</span>");
                }
                sb.append("<br>n ").append(num(pk.get("n"))).append(" · mean ").append(num(pk.get("mean")))
                  .append(" ± ").append(num(pk.get("std"))).append(" · min ").append(num(pk.get("min")))
                  .append(" · max ").append(num(pk.get("max")));
                if (pk.get("nan") instanceof Number n && n.longValue() > 0) {
                    sb.append(" · <b>NaN ").append(num(n)).append("</b>");
                }
                if (pk.get("inf") instanceof Number n && n.longValue() > 0) {
                    sb.append(" · <b>inf ").append(num(n)).append("</b>");
                }
                if (pk.get("per_entry") instanceof Map<?, ?> m) {
                    sb.append("<br>per entry: ").append(num(m.get("mean"))).append(" on average, ")
                      .append(num(m.get("max"))).append(" at most, ").append(num(m.get("empty"))).append(" empty");
                }
                if (Boolean.TRUE.equals(pk.get("sampled"))) {
                    sb.append(" <span style='color:").append(muted).append("'>(from a sample)</span>");
                }
            }
            case "frame" -> {
                sb.append("<br>").append(num(pk.get("rows"))).append(" rows · ")
                  .append(pk.get("columns") instanceof List<?> c ? c.size() : 0).append(" columns");
                if (pk.get("columns") instanceof List<?> cols) {
                    StringBuilder names = new StringBuilder();
                    for (Object c : cols) {
                        if (c instanceof List<?> pair && pair.size() == 2) {
                            names.append(names.length() > 0 ? ", " : "").append(pair.get(0))
                                 .append(" <span style='color:").append(muted).append("'>").append(pair.get(1)).append("</span>");
                        }
                    }
                    sb.append("<br>").append(names);
                }
                if (pk.get("head") instanceof String head) {
                    sb.append("<pre style='margin:2px'>").append(escape(head)).append("</pre>");
                }
            }
            case "tree" -> {
                sb.append("<br>").append(num(pk.get("entries"))).append(" entries");
                if (pk.get("branches") instanceof List<?> br) {
                    StringBuilder names = new StringBuilder();
                    for (Object b : br) {
                        names.append(names.length() > 0 ? ", " : "").append(escape(String.valueOf(b)));
                    }
                    sb.append("<br>branches: ").append(names);
                }
            }
            case "mapping" -> {
                sb.append("<br>").append(num(pk.get("n"))).append(" keys");
                if (pk.get("keys") instanceof List<?> keys) {
                    sb.append(": ").append(escape(String.join(", ", keys.stream().map(String::valueOf).toList())));
                }
            }
            case "error" -> sb.append("<br><span style='color:").append(muted).append("'>")
                              .append(escape(String.valueOf(pk.get("text")))).append("</span>");
            default -> {
                if (pk.get("text") instanceof String t && !t.isBlank()) {
                    sb.append("<pre style='margin:2px'>").append(escape(t)).append("</pre>");
                }
            }
        }
        return sb.toString();
    }

    private static String formatUsage(Map<String, Object> usage) {
        StringBuilder sb = new StringBuilder();
        if (usage.get("kernel_memory") instanceof Number m && m.longValue() > 0) {
            double mb = m.doubleValue() / (1024 * 1024);
            sb.append(mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024) : String.format(Locale.ROOT, "%.0f MB", mb));
        }
        if (usage.get("kernel_cpu") instanceof Number c) {
            sb.append(sb.length() > 0 ? " · " : "").append(String.format(Locale.ROOT, "%.0f%% CPU", c.doubleValue()));
        }
        return sb.toString();
    }

    // -----------------------------
    // Kernel-backed execution
    // -----------------------------

    /**
     * Created on first use so opening a notebook does not launch an interpreter.
     * Synchronized: the kernel list is read off the event thread while a run may
     * already ask for the executor on it, and two executors would mean two kernels.
     */
    private synchronized NotebookExecutor executor() {
        if (executor == null) {
            executor = new NotebookExecutor(settings, new EditorView(),
                () -> currentPath == null ? null : currentPath.toAbsolutePath().getParent());
        }
        return executor;
    }

    /** Kernel work never runs on the event thread: starting a process is I/O. */
    private void offEdt(Runnable task) {
        Thread worker = new Thread(task, "jupylab-control");
        worker.setDaemon(true);
        worker.start();
    }

    public void runPythonAsyncForCell(CellPanel cp) {
        if (cp == null || cp.codeEditor == null) {
            return;
        }
        cp.cell.source = cp.codeEditor.getText();
        setDirty(true);
        if (cp.completionPopup != null) {
            cp.completionPopup.hide();
        }
        offEdt(() -> executor().run(cp.cell));
    }

    /** Runs every code cell in order, each waiting for the one before it. */
    private void runAllCells(int from, int to) {
        syncUIToModel();
        List<Cell> slice = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(to, notebook.cells.size()); i++) {
            slice.add(notebook.cells.get(i));
        }
        if (reactive) {
            slice.removeIf(c -> !"code".equals(c.cell_type));
            runReactive(slice); // in the graph's order, the cells that cannot run left out with why
            return;
        }
        setDirty(true);
        offEdt(() -> executor().runAll(slice));
    }

    private CellPanel panelFor(Cell cell) {
        if (cellsContainer == null) {
            return null;
        }
        for (Component c : cellsContainer.getComponents()) {
            if (c instanceof CellPanel cp && cp.cell == cell) {
                return cp;
            }
        }
        return null;
    }

    /** Everything the kernel makes the editor do, in one place. */
    private final class EditorView implements NotebookExecutor.View {

        @Override
        public void cellStarted(Cell cell) {
            // what the cell runs now is what its outputs and values will describe
            ranSource.put(cell, NotebookIO.joinLines(cell.source));
            runSerial.merge(cell, 1, Integer::sum);
            if (insights.get(cell) != null && !insights.get(cell).pending()) {
                setInsight(cell, null); // about the run before; an install under way keeps its line
            }
            PySemantics.Summary s = summaryOf(cell);
            ranBinds.put(cell, s == null ? Set.of() : Set.copyOf(s.bound().keySet()));
            CellPanel cp = panelFor(cell);
            if (cp != null) {
                cp.setRunning(true);
                cp.setErrorDiagnostic(null);
                cp.refreshOutputs();
                // a cell the run took along says why, under its code
                String why = cell == reactiveInFlight ? runReasons.get(cell) : null;
                if (why != null && !"run by you".equals(why)) {
                    cp.showNotice(why, 0, null, null, null, null);
                } else if (cp.noticeKind == 0) {
                    cp.clearNotice();
                }
            }
            scheduleDataflow();
        }

        @Override
        public void cellOutputsChanged(Cell cell) {
            CellPanel cp = panelFor(cell);
            if (cp != null) {
                cp.scheduleOutputs();
            }
        }

        @Override
        public void cellFinished(Cell cell, Integer count, long millis) {
            CellPanel cp = panelFor(cell);
            if (cp != null) {
                cp.setRunning(false);
                cp.setElapsed(millis);
                cp.refreshOutputs();
                for (Output o : cell.outputs) {
                    if ("error".equals(o.output_type)) {
                        cp.setErrorDiagnostic(NotebookInsight.errorLine(o.ename, o.evalue, o.traceback, count));
                    }
                }
            }
            afterRun(cell, millis);
            // The namespace changed: names may now exist, or have new kinds.
            if (executor != null && !executor.isBusy()) {
                runAnalysis();
            }
            if (cell == reactiveInFlight) {
                reactiveInFlight = null;
                reactiveDone++;
                boolean failed = false;
                for (Output o : cell.outputs) {
                    failed |= "error".equals(o.output_type);
                }
                if (failed) {
                    // what takes from a failed cell would fail in turn, or read what it did not make: it waits
                    recomputeDataflow();
                    Set<Integer> down = new HashSet<>(dataflow.downstream(codeIndexOf(cell)));
                    reactiveQueue.removeIf(c -> down.contains(codeIndexOf(c)));
                    reactiveTotal = reactiveDone + reactiveQueue.size();
                }
            }
            recomputeDataflow();
            pumpReactive();
        }

        @Override
        public void kernelState(String label, boolean alive) {
            if (alive && !kernelAlive) {
                kernelEpoch++; // a new interpreter: what the old one printed is "before the restart"
            }
            kernelAlive = alive;
            setKernelMode(alive ? KernelMode.LOCAL_PYTHON
                                : label != null && label.startsWith("starting") ? KernelMode.CONNECTING
                                : KernelMode.DISABLED);
            if (kernelIndicator != null) {
                kernelIndicator.setToolTipText("Kernel: " + label);
            }
            if (kernelStatusLabel != null) {
                kernelStatusLabel.setText(label);
            }
            if (kernelChip != null) {
                String text = label == null ? "" : label;
                if (alive) {
                    kernelChip.setState(KernelChip.State.IDLE, executor == null ? text : executor.describe());
                    usageTimer.start();
                } else {
                    usageTimer.stop();
                    boolean starting = text.startsWith("starting");
                    boolean dead = text.contains("exit") || text.contains("unavailable") || text.contains("not ")
                                   || text.contains("stopped accepting");
                    kernelChip.setState(starting ? KernelChip.State.STARTING : dead ? KernelChip.State.DEAD
                                        : KernelChip.State.OFF, starting ? "starting..." : text);
                }
            }
            if (!alive && semantics.setLive(Map.of(), false)) {
                peeks.clear();
                scheduleSemanticRefresh(); // the values went with the kernel: no caps, no lens, no stale names
            }
            boolean starting = label != null && label.startsWith("starting");
            if (!alive && !starting && (reactiveInFlight != null || !reactiveQueue.isEmpty())) {
                // the run went with the kernel (or it never started)
                reactiveQueue.clear();
                reactiveInFlight = null;
                finishReactive();
            }
            if (alive) {
                // A notebook that named no kernel records the one it ran on, without
                // counting that as an edit of the user's.
                if (!(notebook.metadata.get("kernelspec") instanceof Map) && executor != null) {
                    rememberKernel(executor.spec(), false);
                }
                runAnalysis();
            }
        }

        @Override
        public void kernelBusy(boolean busy) {
            if (kernelIndicator != null && kernelMode == KernelMode.LOCAL_PYTHON) {
                kernelIndicator.setBackground(busy ? palette.getLogWarnPrefix() : palette.getLogSuccessPrefix());
            }
            if (kernelChip != null) {
                kernelChip.setBusy(busy);
            }
            if (stopBtn != null) {
                stopBtn.repaint(); // red while a cell runs
            }
        }

        @Override
        public void peekReady(int requestId, Map<String, Object> peek) {
            String name = peekRequests.remove(requestId);
            if (name != null) {
                peeks.put(name, peek);
                refreshTooltip();
            }
        }

        @Override
        public void kernelUsage(Map<String, Object> usage) {
            if (kernelChip != null) {
                kernelChip.setUsage(formatUsage(usage));
            }
        }

        @Override
        public void inputRequested(Cell cell, String prompt) {
            String answer = JOptionPane.showInputDialog(JupylabXeditor.this,
                prompt == null || prompt.isEmpty() ? "Input:" : prompt, "Cell input",
                JOptionPane.QUESTION_MESSAGE);
            offEdt(() -> executor().answerInput(answer == null ? "" : answer));
        }

        @Override
        public void completionReady(int start, List<String> matches) {
            // the request-aware overload below is the one in use
        }

        @Override
        public void completionReady(int requestId, int start, int end, List<String> matches, List<String> types) {
            KernelCompletion target = completionRequests.remove(requestId);
            if (target != null) {
                target.answered(requestId, start, matches, types);
            }
        }

        @Override
        public void inspectionReady(int requestId, boolean found, Map<String, Object> bundle) {
            javax.swing.text.JTextComponent editor = inspectRequests.remove(requestId);
            if (editor != null && found && editor.isFocusOwner()) {
                inspectPopup.show(editor, OutputRenderer.text(bundle.get("text/plain")).toString());
            }
        }

        @Override
        public void analysisReady(int requestId, List<Map<String, Object>> cells) {
            applyAnalysis(requestId, cells);
        }

        @Override
        public void variablesReady(List<Map<String, Object>> items) {
            updateVariablePanel(items);
        }
    }

    private void setKernelMode(KernelMode mode) {
        this.kernelMode = mode;
        updateKernelIndicator();
    }

    private void updateKernelIndicator() {
        if (kernelIndicator == null) return;

        switch (kernelMode) {
            case DISABLED -> kernelIndicator.setBackground(palette.getLogPromptPrefix());
            case CONNECTING -> kernelIndicator.setBackground(palette.getLogWarnPrefix());
            case LOCAL_PYTHON -> kernelIndicator.setBackground(palette.getLogSuccessPrefix());
        }
    }

    @FunctionalInterface
    interface SimpleDocumentListener extends DocumentListener {
        void update();

        @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { }

        static SimpleDocumentListener of(Runnable r) {
            return () -> r.run();
        }
    }

    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.US);
        javax.swing.JComponent.setDefaultLocale(java.util.Locale.US);
        SwingUtilities.invokeLater(() -> {
            try {
                Path p = (args.length > 0) ? Paths.get(args[0]) : null;
                JupylabXeditor editor = new JupylabXeditor(p);
                editor.setVisible(true);
            } catch (Exception e) {
                com.sphere.core.JavaErrors.report("starting the notebook editor", e);
                JOptionPane.showMessageDialog(null, "Failed to start: " + e.getMessage());
            }
        });
    }
}
