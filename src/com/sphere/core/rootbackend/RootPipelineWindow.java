package com.sphere.core.rootbackend;

import com.sphere.components.TextLineNumber;
import com.sphere.components.editor.CodeTextPane;
import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.editor.SyntaxHighlighter;
import com.sphere.theme.AnimProgressBar;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a physicist builds their own ROOT pipeline.
 *
 * The form is a generator rather than a blank editor: it collects what the
 * pipeline is, writes C++ that already compiles and already declares itself,
 * and hands the body to the editor. Everything slow -- the compiler, loading
 * the library into the engine -- runs on a worker, so the interface stays
 * answering and the watchdog stays quiet.
 */
public final class RootPipelineWindow extends JFrame {

    /** One pipeline as the list shows it. */
    private record Entry(RootPipelineManifest manifest, Path source, Path library,
                         boolean global, boolean stale) {
        @Override
        public String toString() {
            return manifest.name;
        }
    }

    /** Compiler findings look like "file.cpp:12:5: error: ...". */
    private static final Pattern FINDING =
        Pattern.compile("^(.*?):(\\d+):(?:(\\d+):)?\\s*(error|warning|note)\\s*:\\s*(.*)$");

    private static RootPipelineWindow open;

    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    /** Sphere's own controls and fonts, compact: a field, a list and a button at one height. */
    private final RootForgeStyle style = new RootForgeStyle(palette);

    /** What the Inspect tab found, drawn: a walk's curve, a call's values, a sweep's answers. */
    private final RootPipelinePlot inspectPicture = new RootPipelinePlot(palette);
    private final RootBackend engine;
    private final String activeProject;

    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> pipelines = new JList<>(model);

    private final JTextField nameField = new JTextField(18);
    private final JTextField descriptionField = new JTextField(28);
    private final JComboBox<RootPipelineManifest.Kind> kindBox =
        new JComboBox<>(RootPipelineManifest.Kind.values());
    private final JRadioButton globalLayer = new JRadioButton("beside Sphere");
    private final JRadioButton projectLayer = new JRadioButton("in this project");

    private final DefaultTableModel inputModel =
        new DefaultTableModel(new Object[]{"input", "type"}, 0);
    private final JTable inputTable = new JTable(inputModel);

    /** One box per part of ROOT, which decides both the includes and the -l flags. */
    private final java.util.Map<RootPipelineModules, JCheckBox> moduleBoxes =
        new java.util.EnumMap<>(RootPipelineModules.class);

    private final DefaultComboBoxModel<RootPipelineRecipes.Recipe> recipeModel =
        new DefaultComboBoxModel<>(RootPipelineRecipes.all().toArray(new RootPipelineRecipes.Recipe[0]));
    private final JComboBox<RootPipelineRecipes.Recipe> recipeBox = new JComboBox<>(recipeModel);

    /** Narrows the catalog as it is typed in: "dr", "mjj", "bw" all find their recipe. */
    private final JTextField recipeFind = new JTextField(9);
    private final JButton fromFormulaButton = new JButton("Formula…");
    private final JButton fromCopyButton = new JButton("Copy of…");
    private final JButton fromMacroButton = new JButton("Macro…");
    /** What the pipeline's name points to, offered rather than imposed. */
    private final JButton suggestion = new JButton();
    private RootPipelineRecipes.Recipe suggested;

    /**
     * The recipe the form was last filled from. The box may be showing a search
     * result the user has not picked yet, so the generator never reads the box.
     */
    private RootPipelineRecipes.Recipe applied;

    /**
     * The name the last start put in the form. Like the description, the name
     * follows the starts until the user writes one: a copy's mt_v2 must not
     * stay on the macro function chosen next.
     */
    private String namedByStart;

    private final JCheckBox threadSafe = new JCheckBox("stateless", true);
    private final JCheckBox openMp = new JCheckBox("OpenMP");
    /** The compiler's own choices: how hard it optimizes, the standard, its review, symbols, fast math. */
    private final JComboBox<String> level = new JComboBox<>(new String[]{"-O0", "-O1", "-O2", "-O3", "-Ofast"});
    private final JComboBox<String> standard = new JComboBox<>(new String[]{"C++17", "C++20", "C++23"});
    private final JCheckBox warnings = new JCheckBox("warnings");
    private final JCheckBox debug = new JCheckBox("debug symbols");
    private final JCheckBox fastMath = new JCheckBox("fast math");
    private final JCheckBox nativeArch = new JCheckBox("-march=native");
    private final JCheckBox generateTest = new JCheckBox("test macro");
    private final JCheckBox showInHelp = new JCheckBox("show in :help", true);
    private final JTextField extraFlags = new JTextField(20);

    /** What the pipeline can do besides answering for one entry (RootPipelineExtras). */
    private final JCheckBox perObject = new JCheckBox("per object");
    private final JCheckBox uncertainty = new JCheckBox("uncertainty σ");
    private final JCheckBox monitor = new JCheckBox("monitor");
    /** What the form makes of the inputs and the code, and the one click that takes it. */
    private final JLabel extrasHint = new JLabel(" ");
    private final JButton extrasAction = new JButton(" ");
    private final java.awt.CardLayout extrasCards = new java.awt.CardLayout();
    private final JPanel extrasActionCard = new JPanel(extrasCards);
    private RootPipelineExtras.Suggestion extrasSuggested;

    private final CodeTextPane sourceArea = new CodeTextPane();
    private final SyntaxHighlighter highlighter =
        new SyntaxHighlighter(sourceArea, LanguageSpec.CPP);
    private final JTextArea logArea = new JTextArea();

    // ---- the Try tab, where a built pipeline is called and timed -----------
    private final JTextField tryArguments = new JTextField(26);
    private final JTextArea tryAnswer = new JTextArea(3, 40);
    private final JButton tryButton = new JButton("Call it");
    private final JButton timeButton = new JButton("Time it");
    private final JComboBox<String> rounds =
        new JComboBox<>(new String[]{"100 000", "1 000 000", "10 000 000"});
    private final DefaultTableModel timingModel =
        new DefaultTableModel(new Object[]{"flags", "per call", "against the slowest"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    private final JTable timingTable = new JTable(timingModel);

    // ---- the Chain tab, where an analysis is assembled -------------------
    private final JTextField chainName = new JTextField(12);
    private final JTextField chainTree = new JTextField(10);
    private final JTextField chainFile = new JTextField(20);
    private final JComboBox<RootPipelineChain.Step> stepBox =
        new JComboBox<>(RootPipelineChain.Step.values());
    private final JTextField stepArguments = new JTextField(30);
    private final DefaultListModel<RootPipelineChain.Stage> stageModel =
        new DefaultListModel<>();
    private final JList<RootPipelineChain.Stage> stageList = new JList<>(stageModel);
    private final JTextArea chainPreview = new JTextArea();

    // ---- the Inspect tab, where a wrong answer is tracked down ------------
    private final JTextField inspectArguments = new JTextField(22);
    private final DefaultTableModel watchModel =
        new DefaultTableModel(new Object[]{"named value", "held"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    private final JTable watchTable = new JTable(watchModel);
    private final JComboBox<String> scanInput = new JComboBox<>();
    private final JTextField scanLow = new JTextField("0", 5);
    private final JTextField scanHigh = new JTextField("100", 5);
    private final JTextField scanHeld = new JTextField("1", 5);
    private final JTextArea inspectSaid = new JTextArea();
    private final JTextField commandLine = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final AnimProgressBar progress = new AnimProgressBar();
    private final JTabbedPane tabs = new JTabbedPane();

    private final JButton newButton = new JButton("New");
    private final JButton openButton = new JButton("Open");
    private final JButton saveButton = new JButton("Save");
    private final JButton editButton = new JButton("Edit");
    private final JButton buildButton = new JButton("Build");
    private final JButton loadButton = new JButton("Load");
    private final JButton exitButton = new JButton("Close");

    /** Splits the form from the tabs, so either can be given the height. */
    private JSplitPane divider;
    private JComponent formPanel;
    private int formPreferredHeight = 400;

    /** The pipeline the form is showing, or null for one that is not written yet. */
    private Entry current;
    private boolean filling;

    // -------------------------------------------------------------------------

    /** Shows the window, reusing the one already open. */
    public static void show(RootBackend engine, String activeProject) {
        show(engine, activeProject, null);
    }

    /**
     * Shows the window on one pipeline: an existing one is opened, a name that
     * is not taken yet starts a blank form already carrying it.
     */
    public static void show(RootBackend engine, String activeProject, String name) {
        show(engine, activeProject, name, null);
    }

    /**
     * Shows the window on a new pipeline already filled from a start: a recipe,
     * a formula, a copy or a macro. With no start it is the plain form.
     */
    public static void show(RootBackend engine, String activeProject, String name,
                            RootPipelineStarts.Start start) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(engine, activeProject, name, start));
            return;
        }
        if (start != null) {
            if (open == null || !open.isDisplayable()) {
                open = new RootPipelineWindow(engine, activeProject);
                open.setVisible(true);
            } else {
                open.refresh();
            }
            open.blank();
            if (name != null && !name.isBlank()) {
                open.nameField.setText(name.trim());
            }
            open.start(start);
            open.toFront();
            open.requestFocus();
            return;
        }
        if (open == null || !open.isDisplayable()) {
            open = new RootPipelineWindow(engine, activeProject);
            open.setVisible(true);
        } else {
            open.refresh();
        }
        if (name != null && !name.isBlank()) {
            open.go(name.trim());
        }
        open.toFront();
        open.requestFocus();
    }

    /** Puts the form on one pipeline, existing or about to be. */
    private void go(String name) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).manifest.name.equalsIgnoreCase(name)) {
                pipelines.setSelectedIndex(i);
                openSelected();
                return;
            }
        }
        blank();
        nameField.setText(name);
        regenerate();
    }

    private RootPipelineWindow(RootBackend engine, String activeProject) {
        super("ROOT forge — pipeline builder");
        com.sphere.utils.IconManager.applyAppIcon(this);
        this.engine = engine;
        this.activeProject = activeProject;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        final JComponent band = header();
        add(band, BorderLayout.NORTH);
        // The list of pipelines folds away with its own triangles, for the
        // room the form and the code want.
        final JComponent list = left();
        final JComponent work = middlePart();
        list.setMinimumSize(new Dimension(0, 0));
        work.setMinimumSize(new Dimension(0, 0));
        final JSplitPane sides = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, work);
        sides.setOneTouchExpandable(true);
        sides.setDividerSize(11);
        sides.setDividerLocation(250);
        sides.setResizeWeight(0.0);
        sides.setBorder(null);
        sides.setBackground(palette.getTerminalBackground());
        add(sides, BorderLayout.CENTER);
        final JComponent bar = footer();
        add(bar, BorderLayout.SOUTH);

        wire();
        dress();
        refresh();
        blank();
        fitForm();
        final Insets margins = divider.getParent().getInsets();
        fitWindow(band.getPreferredSize().height + bar.getPreferredSize().height + margins.top + margins.bottom);
        // How thick the system draws the frame is known for sure once the window shows -- late under X11, where WSLg
        // counts its shadow in it: the window and the bar are fitted again then.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                SwingUtilities.invokeLater(() -> {
                    keepOnScreen();
                    placeDivider();
                });
            }
        });
    }

    /**
     * The bar between the form and the code, just under the form. Measured
     * before the form was styled, the form still had Sphere's taller
     * controls, and the bar sat some 40 pixels too low: three lines of code
     * given up for a band of nothing.
     */
    private void fitForm() {
        formPreferredHeight = formPanel.getPreferredSize().height;
        placeDivider();
    }

    // ---- the window's size and the bar's place ----------------------------------

    /** The room the forge's content was laid out for, the frame not counted: 1180 x 860 with Windows' frame. */
    private static final Dimension ROOM = new Dimension(1174, 835);

    /** The least room the content stays usable in, the frame not counted. */
    private static final Dimension LEAST_ROOM = new Dimension(1034, 675);

    /** The height the form keeps when the tab shown takes the rest: its first rows, scrolled. */
    private static final int FORM_LEAST = 120;

    /** Where the window itself last put the bar; -1 before it did. A place the user dragged the bar to is theirs. */
    private int placedAt = -1;

    /**
     * Sizes the window by its content and the screen. The content gets the room it was laid out for -- more height
     * when the form and a few lines of code need it, as with Linux's taller fonts -- never more than the screen
     * leaves free, and the frame comes on top of that, however thick the system draws it.
     *
     * The window used to be given Windows' outer size, 1180 x 860. Under WSLg, whose frame counts its shadow (38
     * pixels a side, 59 on top), that left the content 1104 x 763: the form took its whole height and the tabs got
     * 56 pixels, their strip under the command line and Try it's fields at a negative height, drawn over one
     * another. Packed, the window keeps its content's size when X11 tells late how thick the frame is. On a Windows
     * screen of 864 points, 860 also put Build and Load under the taskbar.
     *
     * The window is placed before it is made displayable: WSLg's window manager shows a window where it was first
     * put, and put the forge in the screen's corner when its place came after.
     *
     * @param around what the window holds besides the form and the tabs: its header, its footer, the margins
     */
    private void fitWindow(int around) {
        final JComponent content = (JComponent) getContentPane();
        final Rectangle free = freeArea();
        final int wanted = around + formPreferredHeight + 6 + divider.getDividerSize() + tabsNeed();
        final Dimension room = new Dimension(ROOM.width, Math.max(ROOM.height, wanted));
        setSize(room);
        setLocationRelativeTo(null);
        content.setPreferredSize(room);
        pack(); // displayable: the frame's thickness is known (under X11, a guess until the window shows)
        final Insets frame = getInsets();
        room.width = Math.min(room.width, free.width - frame.left - frame.right);
        room.height = Math.min(room.height, free.height - frame.top - frame.bottom);
        content.setPreferredSize(room);
        pack();
        content.setPreferredSize(null);
        setMinimumSize(new Dimension(
            Math.min(LEAST_ROOM.width + frame.left + frame.right, free.width),
            Math.min(LEAST_ROOM.height + frame.top + frame.bottom, free.height)));
        setLocationRelativeTo(null);
        keepOnScreen();
    }

    /** The part of the screen a window may take: the screen less its taskbars and docks. */
    private Rectangle freeArea() {
        final GraphicsConfiguration screen = getGraphicsConfiguration();
        final Rectangle all = screen.getBounds();
        final Insets bars = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        return new Rectangle(all.x + bars.left, all.y + bars.top,
            Math.max(1, all.width - bars.left - bars.right), Math.max(1, all.height - bars.top - bars.bottom));
    }

    /**
     * Brings the window inside the screen's free area, smaller if it must be: never a button under a taskbar. What
     * must be inside is the window less the thickness of its frame's sides on every edge -- its content and its
     * title bar; the sides themselves may fall outside, which under WSLg are mostly a shadow, 38 pixels wide.
     */
    private void keepOnScreen() {
        final Rectangle free = freeArea();
        final Rectangle now = getBounds();
        final Insets frame = getInsets();
        final int edge = Math.max(0, Math.min(frame.left, Math.min(frame.right, frame.bottom)));
        final Rectangle seen = new Rectangle(now.x + edge, now.y + edge,
            Math.max(1, now.width - 2 * edge), Math.max(1, now.height - 2 * edge));
        if (free.contains(seen)) {
            return;
        }
        final int w = Math.min(seen.width, free.width), h = Math.min(seen.height, free.height);
        final int x = Math.max(free.x, Math.min(seen.x, free.x + free.width - w));
        final int y = Math.max(free.y, Math.min(seen.y, free.y + free.height - h));
        final Dimension least = getMinimumSize();
        setMinimumSize(new Dimension(Math.min(least.width, w + 2 * edge), Math.min(least.height, h + 2 * edge)));
        setBounds(x - edge, y - edge, w + 2 * edge, h + 2 * edge);
    }

    /**
     * Puts the bar where the tab shown wants it: under the whole form, or high for Inspect and Chain, which need the
     * height -- but never so low that the tabs lose the room they need (see tabsNeed); the form scrolls instead.
     */
    private void placeDivider() {
        if (divider == null) {
            return;
        }
        placedAt = fitDivider(tabs.getSelectedIndex() >= 3 ? FORM_LEAST : formPreferredHeight + 6);
        divider.setDividerLocation(placedAt);
    }

    /**
     * After the window changed size: the window's own place for the bar is worked out again, and a place the user
     * chose is only raised if the tabs no longer had their room. Folded away with the triangles, it stays folded.
     */
    private void refitDivider() {
        if (divider == null) {
            return;
        }
        final int at = divider.getDividerLocation();
        if (placedAt < 0 || at == placedAt) {
            placeDivider();
            return;
        }
        if (at >= divider.getMaximumDividerLocation() || at <= divider.getMinimumDividerLocation()) {
            return;
        }
        final int fitted = fitDivider(at);
        if (fitted < at) {
            divider.setDividerLocation(fitted);
        }
    }

    /**
     * The place for the bar nearest the one wanted that leaves the tabs their room, the form keeping at least its
     * first rows. Before the window is laid out, its height unknown, the place wanted.
     */
    private int fitDivider(int wanted) {
        final Insets in = divider.getInsets();
        final int height = divider.getHeight() - in.top - in.bottom;
        if (height <= 0) {
            return wanted;
        }
        final int lowest = height - divider.getDividerSize() - tabsNeed();
        return Math.max(Math.min(wanted, lowest), Math.min(FORM_LEAST, wanted));
    }

    /**
     * The height the tabs need under the bar: their strip, what the tab shown needs to be read and used -- two
     * lines of code at the least (the form comes first; the bar gives the code more), or the whole of Try it,
     * Inspect or Chain, their fields and answers -- and the command line.
     */
    private int tabsNeed() {
        final int line = sourceArea.getFontMetrics(sourceArea.getFont()).getHeight();
        final Component shown = tabs.getSelectedComponent();
        int content = 2 * line + 8;
        if (shown != null && !(shown instanceof JScrollPane)) {
            content = Math.max(content, shown.getPreferredSize().height);
        }
        // The strip as laid out (all its rows, if the titles wrap); before the tabs have a width, a guess.
        int strip = 2 * line + 8;
        if (tabs.getWidth() > 0) {
            int bottom = 0;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                final Rectangle tab = tabs.getBoundsAt(i);
                if (tab != null) {
                    bottom = Math.max(bottom, tab.y + tab.height);
                }
            }
            if (bottom > 0) {
                strip = bottom + 6;
            }
        }
        return strip + content + 4 + commandLine.getPreferredSize().height;
    }

    // ---- the pieces ---------------------------------------------------------

    private JComponent header() {
        JPanel band = new JPanel(new GridLayout(2, 1, 0, 3));
        band.setBackground(palette.getTerminalBackground());
        band.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBorder()),
            new EmptyBorder(11, 16, 11, 16)));

        JLabel title = new JLabel("ROOT forge");
        title.setFont(style.font(Font.BOLD, 16));
        title.setForeground(palette.getTextPrimary());

        JLabel hint = new JLabel("Your own C++ pipelines: compiled into includes/, loaded when Sphere starts, "
                                 + "callable by name from :root and RDataFrame.");
        hint.setFont(style.font());
        hint.setForeground(palette.getTextSecondary());

        band.add(title);
        band.add(hint);
        return band;
    }

    /**
     * Gives every control Sphere's look.
     *
     * Sphere themes what it can through UIManager, but a tab strip's delegate,
     * a viewport's ground and the font of a check box, a list or a table are
     * not among those keys, so they stay at whatever the look and feel chose.
     * They are set here instead, from Sphere's own delegates, fonts and
     * palette, so the window is one piece with the rest of Sphere. Fields keep
     * Sphere's as they are.
     */
    private void dress() {
        // The search box is measured in columns, so it keeps its width
        // whatever measures it again later.
        recipeFind.setColumns(15);
        scanInput.setPrototypeDisplayValue("dphi_lead_err");
        // One pass over everything: every list, button and table, whatever
        // tab it lives in.
        for (Component one : all(getContentPane())) {
            if (one instanceof JComboBox<?> box) {
                style.combo(box);
            } else if (one instanceof JButton button && button != suggestion) {
                style.button(button);
            } else if (one instanceof JTable table) {
                style.table(table);
            } else if (one instanceof JCheckBox || one instanceof JRadioButton) {
                style.toggle((AbstractButton) one);
            }
        }
        style.caption(commandLine, commandLine.getBorder());
        extrasHint.setFont(style.font(Font.PLAIN, 11));
        // What to type, said where the pointer rests: Sphere's fields write nothing in themselves.
        nameField.setToolTipText("A C++ identifier, the name :root calls it by: mt, deltaR, myCut.");
        descriptionField.setToolTipText("What it computes, in a few words.");
        extraFlags.setToolTipText("More compiler flags, e.g. -DNDEBUG.");
        // Lists sized to their purpose, not to their longest entry: a catalog
        // title of fifty letters used to widen the form past the window and
        // squeeze the labels until they were cut ("Descriptior"). A typical
        // entry is what they are measured on, with whatever font they end up
        // with -- never a width fixed in pixels. The four kinds are short, and
        // measured all of them: HISTOGRAM, taken for the widest, is not, and
        // TRANSFORM was cut.
        recipeBox.setPrototypeDisplayValue(new RootPipelineRecipes.Recipe(
            RootPipelineRecipes.Family.KINEMATICS, "Invariant mass of two objects", "",
            RootPipelineManifest.Kind.TRANSFORM, java.util.List.of(), java.util.List.of(), null));

        inputTable.setSelectionForeground(palette.getTextPrimary());

        tabs.setBackground(palette.getTerminalBackground());
        tabs.setForeground(palette.getTextPrimary());
        style.tabs(tabs);

        progress.setBackground(palette.getBackgroundTrack());
        pipelines.setSelectionBackground(palette.getTerminalSelection());
        pipelines.setSelectionForeground(palette.getTextWhite());

        for (Component one : all(getContentPane())) {
            if (one instanceof JScrollPane pane) {
                pane.getViewport().setBackground(palette.getTerminalBackground());
                pane.setBackground(palette.getTerminalBackground());
            } else if (one instanceof JPanel panel && panel.getBorder() == null) {
                panel.setBackground(palette.getTerminalBackground());
            }
        }
    }

    /** Every component under one, however deep -- but not the parts a control is made of. */
    private static List<Component> all(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component one : root.getComponents()) {
            found.add(one);
            if (one instanceof Container deeper && !madeOfParts(one)) {
                found.addAll(all(deeper));
            }
        }
        return found;
    }

    /**
     * A list's arrow, a scroll bar's arrows, a divider's triangles: buttons
     * that belong to their control's delegate, which lays them out itself.
     * Styled as buttons, they took a button's 12-pixel padding, and a list
     * gives its arrow that padding on top of the room it counted when it
     * measured itself: its text lost 24 pixels and read "TRAN..." for
     * TRANSFORM, and nothing but "..." for -O3.
     */
    private static boolean madeOfParts(Component one) {
        return one instanceof JComboBox<?> || one instanceof javax.swing.JScrollBar
            || one instanceof javax.swing.JSpinner
            || one instanceof javax.swing.plaf.basic.BasicSplitPaneDivider;
    }

    private JComponent left() {
        pipelines.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pipelines.setBackground(palette.getTerminalBackground());
        pipelines.setForeground(palette.getTextPrimary());
        pipelines.setCellRenderer(new Renderer());

        JScrollPane scroll = new JScrollPane(pipelines);
        scroll.setPreferredSize(new Dimension(250, 10));
        scroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, palette.getBorder()));

        JLabel caption = new JLabel("  pipelines found");
        caption.setForeground(palette.getTextSecondary());
        caption.setBorder(new EmptyBorder(8, 8, 6, 8));

        JPanel keeping = new JPanel(new GridLayout(1, 3, 4, 0));
        keeping.setBackground(palette.getTerminalBackground());
        keeping.setBorder(new EmptyBorder(6, 6, 6, 6));
        JButton revert = small("Revert");
        JButton export = small("Export");
        JButton bring = small("Import");
        revert.setToolTipText("Go back to a version saved earlier.");
        export.setToolTipText("Put the pipeline into one archive, to send it on.");
        bring.setToolTipText("Read a pipeline someone else exported.");
        revert.addActionListener(e -> revert());
        export.addActionListener(e -> exportOne());
        bring.addActionListener(e -> importOne());
        keeping.add(revert);
        keeping.add(export);
        keeping.add(bring);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(palette.getTerminalBackground());
        side.add(caption, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.add(keeping, BorderLayout.SOUTH);
        return side;
    }

    private JComponent middlePart() {
        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.setBackground(palette.getTerminalBackground());
        middle.setBorder(new EmptyBorder(10, 12, 6, 12));
        JComponent theForm = form();
        formPanel = theForm;

        // Read only, but colored by the same highlighter the editor uses, so
        // what the form generates reads the way the file will in the editor.
        sourceArea.setEditable(false);
        // The editor's own font, not the toolkit's Courier.
        sourceArea.setFont(style.code(12));
        sourceArea.applyTheme();
        sourceArea.setHighlightCurrentLine(false);
        JScrollPane sourceScroll = new JScrollPane(sourceArea);
        sourceScroll.getViewport().setBackground(EditorTheme.background());
        TextLineNumber numbers = new TextLineNumber(sourceArea);
        sourceScroll.setRowHeaderView(numbers);

        logArea.setEditable(false);
        logArea.setFont(style.code(12));
        logArea.setBackground(palette.getTerminalBackground());
        logArea.setForeground(palette.getTerminalForeground());

        tabs.addTab("Source", sourceScroll);
        tabs.addTab("Build log", new JScrollPane(logArea));
        tabs.addTab("Try it", tryPanel());
        tabs.addTab("Inspect", inspectPanel());
        tabs.addTab("Chain", chainPanel());
        // Choosing the Chain tab gives it the room it needs, and leaving it
        // gives the form its own back (see placeDivider).
        tabs.addChangeListener(e -> placeDivider());

        JPanel below = new JPanel(new BorderLayout(0, 4));
        below.setBackground(palette.getTerminalBackground());
        below.add(tabs, BorderLayout.CENTER);

        // The form and the tabs share the height rather than the form taking
        // what it wants: assembling a chain needs room, and reading a form does
        // not, so which of the two gets the space is the user's to decide.
        // The form scrolls, so giving its height to the tabs cuts nothing off:
        // what no longer fits is still reachable rather than simply gone.
        // The form keeps to the top of its pane: whatever height the pane has
        // beyond the form's own goes below it. Stretched to the pane, the grid
        // centered itself and left a band of nothing above the name.
        JPanel atTop = new JPanel(new BorderLayout());
        atTop.setBackground(palette.getTerminalBackground());
        atTop.add(theForm, BorderLayout.NORTH);
        JScrollPane formScroll = new JScrollPane(atTop,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(null);
        formScroll.getViewport().setBackground(palette.getTerminalBackground());
        formScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane between = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, below);
        divider = between;
        between.setResizeWeight(0.0);
        between.setBorder(null);
        between.setBackground(palette.getTerminalBackground());
        // The triangles of Sphere's other windows: one click folds the code
        // away, or gives it the whole height. With no minimum on either side
        // the bar goes wherever it is dragged; the tabs' own minimum used to
        // stop it a third of the way down.
        between.setOneTouchExpandable(true);
        between.setDividerSize(11);
        formScroll.setMinimumSize(new Dimension(0, 0));
        below.setMinimumSize(new Dimension(0, 0));
        // Where the bar starts is decided once the form is styled (see fitForm).
        // A window made shorter takes the height from the form, which scrolls,
        // not from the tabs (see refitDivider).
        between.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                refitDivider();
            }
        });

        commandLine.setEditable(false);
        commandLine.setFont(style.code(11));
        commandLine.setForeground(palette.getTextSecondary());
        commandLine.setBackground(palette.getTerminalBackground());
        commandLine.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
            new EmptyBorder(5, 4, 5, 4)));
        commandLine.setToolTipText("The exact command the compiler will be given.");
        below.add(commandLine, BorderLayout.SOUTH);

        middle.add(between, BorderLayout.CENTER);
        return middle;
    }

    private JComponent form() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(palette.getTerminalBackground());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // The name takes the width; the kind is a short word and keeps a short box.
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(style.formLabel("Name"), c);
        c.gridx = 1; c.weightx = 1;
        grid.add(nameField, c);
        c.gridx = 2; c.weightx = 0;
        grid.add(style.formLabel("Kind"), c);
        c.gridx = 3; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        grid.add(kindBox, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(style.formLabel("Start from"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        recipeBox.setToolTipText("A quantity that is already written correctly. "
                                 + "It brings its own inputs and its own ROOT modules.");
        recipeBox.setRenderer(new RecipeRenderer());
        recipeBox.setMaximumRowCount(20);
        grid.add(startRow(), c);
        c.gridwidth = 1;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(style.formLabel("Description"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(descriptionField, c);
        c.gridwidth = 1;
        row++;

        ButtonGroup layers = new ButtonGroup();
        layers.add(globalLayer);
        layers.add(projectLayer);
        globalLayer.setSelected(true);
        for (JRadioButton radio : new JRadioButton[]{globalLayer, projectLayer}) {
            radio.setBackground(palette.getTerminalBackground());
            radio.setForeground(palette.getTextPrimary());
        }
        projectLayer.setEnabled(activeProject != null && !activeProject.isBlank());
        if (!projectLayer.isEnabled()) {
            projectLayer.setToolTipText("No project is open.");
        }
        JPanel layerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        layerRow.setBackground(palette.getTerminalBackground());
        layerRow.add(globalLayer);
        layerRow.add(projectLayer);

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(style.formLabel("Lives"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(layerRow, c);
        c.gridwidth = 1;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        grid.add(style.formLabel("Inputs"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(inputs(), c);
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        // Tight rows at their own width rather than spread across the whole
        // form: the boxes read as one group. Five across: seven made the form
        // wider than the window, and the layout then cut every label short.
        JPanel parts = new JPanel(new GridLayout(3, 5, 14, 0));
        parts.setBackground(palette.getTerminalBackground());
        for (RootPipelineModules module : RootPipelineModules.values()) {
            JCheckBox box = new JCheckBox(module.token);
            box.setBackground(palette.getTerminalBackground());
            box.setForeground(palette.getTextPrimary());
            box.setToolTipText(module.label
                + (module.linkFlags().isEmpty() ? "" : "   " + String.join(" ", module.linkFlags())));
            moduleBoxes.put(module, box);
            parts.add(box);
        }
        moduleBoxes.get(RootPipelineModules.MATH).setSelected(true);

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        grid.add(style.formLabel("ROOT modules"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST;
        grid.add(parts, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        JPanel options = new JPanel(new GridLayout(2, 3, 14, 0));
        options.setBackground(palette.getTerminalBackground());
        for (JCheckBox box : new JCheckBox[]{threadSafe, openMp, nativeArch, generateTest, showInHelp}) {
            box.setBackground(palette.getTerminalBackground());
            box.setForeground(palette.getTextPrimary());
            options.add(box);
        }
        threadSafe.setToolTipText("Keeps no state between calls, so :root mt on may run it "
                                  + "on several entries at once.");
        openMp.setToolTipText("Build with -fopenmp.");
        nativeArch.setToolTipText("Build with -march=native. The library then runs only "
                                  + "on this kind of processor.");
        generateTest.setToolTipText("Write a macro in user_scripts/ that calls the entry point.");
        showInHelp.setToolTipText("List the pipeline in :help root once it is loaded.");

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        grid.add(style.formLabel("Build"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST;
        grid.add(options, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        // The compiler's own choices, each a click away rather than a flag to remember.
        final JPanel compiler = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        compiler.setBackground(palette.getTerminalBackground());
        compiler.add(label("optimization"));
        compiler.add(level);
        compiler.add(label("standard"));
        compiler.add(standard);
        for (JCheckBox box : new JCheckBox[]{warnings, debug, fastMath}) {
            box.setBackground(palette.getTerminalBackground());
            box.setForeground(palette.getTextPrimary());
            compiler.add(box);
        }
        level.setSelectedItem("-O3");
        standard.setSelectedItem("C++20");
        level.setToolTipText("-O0 builds fastest and debugs best; -O3 runs fastest; -Ofast also"
            + " relaxes the floating-point rules, as fast math does.");
        standard.setToolTipText("The C++ standard the body is written in.");
        warnings.setToolTipText("-Wall -Wextra: the compiler's own review of the code, in the build log.");
        debug.setToolTipText("-g: symbols a debugger can stop on.");
        fastMath.setToolTipText("-ffast-math: faster arithmetic that may assume no NaN or infinity ever"
            + " appears -- so a NaN check can be compiled away.");

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.WEST;
        grid.add(style.formLabel("Compiler"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST;
        grid.add(compiler, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        grid.add(style.formLabel("Runtime"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        grid.add(runtimeRow(), c);
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(style.formLabel("Extra flags"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(extraFlags, c);
        c.gridwidth = 1;

        return grid;
    }

    /**
     * What the pipeline can do besides answering for one entry, and, on the
     * line below, what the form makes of its inputs and code: a branch named
     * like Muon_pt asks for per object, pt_err beside pt for the uncertainty,
     * a filter's && return for a cutflow -- with the one click that does it.
     */
    private JComponent runtimeRow() {
        final JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        boxes.setBackground(palette.getTerminalBackground());
        for (JCheckBox box : new JCheckBox[]{perObject, uncertainty, monitor}) {
            box.setBackground(palette.getTerminalBackground());
            box.setForeground(palette.getTextPrimary());
            boxes.add(box);
        }
        extrasHint.setForeground(palette.getTextSecondary());
        // The button's place is kept when there is nothing to offer, so the
        // form does not move by a line each time a suggestion comes and goes.
        final JPanel none = new JPanel();
        none.setOpaque(false);
        extrasActionCard.setOpaque(false);
        extrasActionCard.add(extrasAction, "act");
        extrasActionCard.add(none, "none");
        extrasCards.show(extrasActionCard, "none");
        final JPanel said = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        said.setBackground(palette.getTerminalBackground());
        said.add(extrasActionCard);
        said.add(extrasHint);
        final JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setBackground(palette.getTerminalBackground());
        row.add(boxes, BorderLayout.NORTH);
        row.add(said, BorderLayout.CENTER);
        return row;
    }

    /** What each extra does, for its tooltip while it can be ticked. */
    private static final String PER_OBJECT_TIP = "<html>Also callable on collections &mdash; a ROOT::RVec or a std::vector &mdash;"
        + " for any input:<br><b>mt(Muon_pt, MET_pt, Muon_dphi)</b> gives one value per muon, and a filter a mask,"
        + " as in Muon_pt[cut(...)].<br>Declared to the interpreter when the library loads: nothing more is compiled.</html>";
    private static final String SIGMA_TIP = "<html>Also gives <b>name_sigma(inputs..., errors...)</b>: each input moved"
        + " by its error, the others held,<br>the swings added in quadrature &mdash; one side only where the other"
        + " leaves the domain.<br>An input called x_err beside x is taken as x's error.</html>";
    private static final String MONITOR_TIP = "<html>Keeps score per thread, without a lock, about 20 ns a call:"
        + " the calls, inputs and answers that were<br>not numbers with the first of each, where each input lived"
        + " &mdash; with a word when it looks like MeV,<br>degrees or a -999 marker &mdash; the cost of a call, and"
        + " the cutflow of every SPHERE_CUT(...).<br><b>:root pipeline report name</b></html>";

    /**
     * The extras the pipeline can have, the reason for those it cannot, and
     * what the form suggests. A box that no longer applies -- the kind became
     * a histogram, an input a collection -- is unticked and says why.
     *
     * @param preview the source as Save would write it, read for the cuts
     */
    private void fitExtras(RootPipelineManifest manifest, String preview) {
        fitExtra(perObject, RootPipelineExtras.whyNotPerObject(manifest), PER_OBJECT_TIP);
        fitExtra(uncertainty, RootPipelineExtras.whyNotSigma(manifest), SIGMA_TIP);
        fitExtra(monitor, RootPipelineExtras.whyNotMonitor(manifest), MONITOR_TIP);
        extrasSuggested = RootPipelineExtras.suggest(manifest, preview);
        if (extrasSuggested == null) {
            extrasHint.setText(" ");
            extrasHint.setToolTipText(null);
            extrasCards.show(extrasActionCard, "none");
            return;
        }
        extrasHint.setText(extrasSuggested.text());
        extrasHint.setToolTipText(extrasSuggested.detail());
        extrasAction.setText(extrasSuggested.button());
        extrasAction.setToolTipText(extrasSuggested.detail());
        extrasCards.show(extrasActionCard, "act");
    }

    private static void fitExtra(JCheckBox box, String whyNot, String tip) {
        box.setEnabled(whyNot == null);
        if (whyNot != null) {
            box.setSelected(false);
        }
        box.setToolTipText(whyNot == null ? tip : whyNot);
    }

    /** Takes what the form suggested about the extras. */
    private void takeExtrasSuggestion() {
        final RootPipelineExtras.Suggestion taken = extrasSuggested;
        if (taken == null) {
            return;
        }
        switch (taken.act()) {
            case PER_OBJECT -> perObject.setSelected(true);
            case SIGMA -> uncertainty.setSelected(true);
            case COUNT_CUTS -> {
                monitor.setSelected(true);
                markCuts();
                return;
            }
            case MARK_CUTS -> {
                markCuts();
                return;
            }
            default -> { }
        }
        regenerate();
    }

    /**
     * Wraps each condition of the filter's return in SPHERE_CUT, in the file
     * itself: the one edit the forge makes inside the user's code, on a click,
     * with the version before kept for Revert. Saved first, so the function
     * already has the name the monitor gives it.
     */
    private void markCuts() {
        if (!save(false) || current == null) {
            return;
        }
        final RootPipelineManifest manifest = collect();
        final Path file = current.source;
        final String marked = RootPipelineExtras.markCuts(read(file), manifest);
        if (marked == null) {
            say("Nothing left to mark: every condition of the return already counts.", false);
            regenerate();
            return;
        }
        try {
            RootPipelineHistory.keep(file);
            Files.writeString(file, marked, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            say("Could not write " + file + ": " + failure.getMessage(), true);
            return;
        }
        regenerate();
        say("Each cut is now counted. Build and load " + manifest.name + ", run it, then :root pipeline report "
            + manifest.name + " gives the cutflow.", false);
    }

    /** The runtime row, showing what a manifest asks for. */
    private void showRuntime(RootPipelineManifest m) {
        perObject.setSelected(m.each);
        uncertainty.setSelected(m.sigma);
        monitor.setSelected(m.monitor);
    }

    /** What the runtime row says, into a manifest: only what can be had. */
    private void readRuntime(RootPipelineManifest m) {
        m.each = perObject.isSelected() && perObject.isEnabled();
        m.sigma = uncertainty.isSelected() && uncertainty.isEnabled();
        m.monitor = monitor.isSelected() && monitor.isEnabled();
    }

    /** What the extras add, for the line under the code. */
    private static String extrasLine(RootPipelineManifest m) {
        final List<String> parts = new ArrayList<>();
        // Named once the pipeline is; before that, by what they are.
        final boolean named = m.name != null && !m.name.isBlank();
        if (RootPipelineExtras.perObject(m)) {
            parts.add(named ? m.name + "(collections)" : "per object");
        }
        if (RootPipelineExtras.sigma(m)) {
            parts.add(named ? RootPipelineExtras.sigmaName(m) + "(...)" : "its uncertainty");
        }
        if (RootPipelineExtras.monitored(m)) {
            parts.add(named ? ":root pipeline report " + m.name : "a report");
        }
        return parts.isEmpty() ? "" : "      also:  " + String.join(",  ", parts);
    }

    private JComponent inputs() {
        inputTable.setRowHeight(21);
        // The editors a cell opens are Sphere's field and list, with no more
        // padding than a row has room for: with their own, the text of a
        // 21-pixel row was cut at the top and the bottom.
        final JComboBox<String> types = new JComboBox<>(RootPipelineManifest.TYPES);
        style.cellCombo(types);
        inputTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(types));
        final JTextField cell = new JTextField();
        style.cellField(cell);
        inputTable.setDefaultEditor(Object.class, new DefaultCellEditor(cell));

        JScrollPane scroll = new JScrollPane(inputTable);
        // Four rows show, at the compact row height; more scroll.
        scroll.setPreferredSize(new Dimension(360, 4 * 22 + 26));
        scroll.setBorder(BorderFactory.createLineBorder(palette.getBorder()));

        JButton add = small("Add");
        JButton remove = small("Remove");
        JButton fromTree = small("From tree");
        fromTree.setToolTipText("Reads the branches of a tree the engine already holds "
                                + "and offers them as inputs, with their own types.");
        fromTree.addActionListener(e -> fromTree());
        add.addActionListener(e -> {
            inputModel.addRow(new Object[]{"x" + (inputModel.getRowCount() + 1), "double"});
            regenerate();
        });
        remove.addActionListener(e -> {
            int at = inputTable.getSelectedRow();
            if (at >= 0) {
                inputModel.removeRow(at);
                regenerate();
            }
        });
        inputModel.addTableModelListener(e -> regenerate());

        JPanel side = new JPanel(new GridLayout(3, 1, 0, 4));
        side.setBackground(palette.getTerminalBackground());
        side.add(add);
        side.add(remove);
        side.add(fromTree);

        JPanel wrap = new JPanel(new BorderLayout(6, 0));
        wrap.setBackground(palette.getTerminalBackground());
        wrap.add(scroll, BorderLayout.CENTER);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(palette.getTerminalBackground());
        holder.add(side, BorderLayout.NORTH);
        wrap.add(holder, BorderLayout.EAST);
        return wrap;
    }

    private JComponent footer() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(palette.getTerminalBackground());
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
            new EmptyBorder(8, 12, 10, 12)));

        status.setForeground(palette.getTextSecondary());

        progress.setPreferredSize(new Dimension(190, 14));
        progress.setVisible(false);

        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.setBackground(palette.getTerminalBackground());
        left.add(status, BorderLayout.CENTER);
        left.add(progress, BorderLayout.EAST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBackground(palette.getTerminalBackground());
        for (JButton button : new JButton[]{newButton, openButton, saveButton, editButton,
                                            buildButton, loadButton, exitButton}) {
            button.setFocusPainted(false);
            buttons.add(button);
        }
        editButton.setToolTipText("Open the source in the Sphere editor.");
        buildButton.setToolTipText("Compile it into a shared library in includes/.");
        loadButton.setToolTipText("Hand the built library to the running engine.");

        bar.add(left, BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    /**
     * Where a built pipeline is called, and where the build flags are settled.
     *
     * A pipeline that compiles is not yet a pipeline that is right, and a flag
     * that is ticked is not yet a flag that helps. Both questions are answered
     * here rather than left to the user to arrange elsewhere.
     */
    private JComponent tryPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(palette.getTerminalBackground());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel call = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        call.setBackground(palette.getTerminalBackground());
        call.add(label("Arguments"));
        call.add(tryArguments);
        call.add(tryButton);
        tryArguments.setToolTipText("Written as C++ would take them: 40.0 55.0 1.2");
        tryButton.setToolTipText("Calls the loaded pipeline once and shows what it answers.");

        tryAnswer.setEditable(false);
        tryAnswer.setLineWrap(true);
        tryAnswer.setWrapStyleWord(true);
        tryAnswer.setFont(style.code(12));
        tryAnswer.setBackground(palette.getTerminalBackground());
        tryAnswer.setForeground(palette.getTerminalForeground());

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBackground(palette.getTerminalBackground());
        top.add(call, BorderLayout.NORTH);
        top.add(new JScrollPane(tryAnswer), BorderLayout.CENTER);

        JPanel measure = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        measure.setBackground(palette.getTerminalBackground());
        measure.add(label("Calls"));
        rounds.setSelectedIndex(1);
        measure.add(rounds);
        measure.add(timeButton);
        timeButton.setToolTipText("Builds the pipeline once per set of flags and times each, "
                                  + "outside the engine so nothing that is loaded is disturbed.");

        timingTable.setRowHeight(21);
        timingTable.setFillsViewportHeight(true);
        JScrollPane timingScroll = new JScrollPane(timingTable);
        timingScroll.setPreferredSize(new Dimension(400, 120));

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBackground(palette.getTerminalBackground());
        bottom.add(measure, BorderLayout.NORTH);
        bottom.add(timingScroll, BorderLayout.CENTER);

        panel.add(top, BorderLayout.NORTH);
        panel.add(bottom, BorderLayout.CENTER);
        return panel;
    }

    /** Calls the loaded pipeline with whatever is in the arguments field. */
    private void callIt() {
        final Entry entry = current;
        if (entry == null) {
            say("Open a pipeline first.", true);
            return;
        }
        if (engine == null || !engine.isAvailable()) {
            say("The ROOT engine is not running, so nothing can be called.", true);
            return;
        }
        final String written = tryArguments.getText().trim();
        final String arguments = written.isEmpty() ? ""
            : written.replaceAll("[,\\s]+", ", ");
        final String expression = entry.manifest.name + "(" + arguments + ")";
        tryAnswer.setText(expression + "\n");
        working(true, "Calling " + entry.manifest.name + "...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return engine.executeClingAwait(expression, 20_000L);
            }

            @Override
            protected void done() {
                working(false, null);
                String answer;
                try {
                    answer = get();
                } catch (Exception failure) {
                    tryAnswer.append("The engine did not answer: " + failure.getMessage());
                    return;
                }
                tryAnswer.append(answer == null ? "no answer" : answer);
                say(answer != null && answer.startsWith("ERROR")
                    ? "The engine refused the call. Is the library loaded?"
                    : "Called " + entry.manifest.name + ".",
                    answer != null && answer.startsWith("ERROR"));
            }
        }.execute();
    }

    /** Builds the pipeline once per set of flags and fills the table. */
    private void timeIt() {
        if (!save(false)) {
            return;
        }
        final Entry entry = current;
        if (entry == null) {
            return;
        }
        final RootPipelineManifest manifest = collect();
        if (!RootPipelineTemplates.canBeTimed(manifest)) {
            say("Only a transform or a filter taking numbers can be timed.", true);
            return;
        }
        final long calls = Long.parseLong(
            String.valueOf(rounds.getSelectedItem()).replaceAll("\\D", ""));

        timingModel.setRowCount(0);
        tabs.setSelectedIndex(2);
        working(true, "Building " + manifest.name + " once per set of flags...");

        new SwingWorker<List<RootPipelineBench.Timing>, Void>() {
            @Override
            protected List<RootPipelineBench.Timing> doInBackground() {
                return RootPipelineBench.compare(
                    new RootUserCompiler(new SettingsManager(), engine),
                    manifest, entry.source, calls);
            }

            @Override
            protected void done() {
                working(false, null);
                List<RootPipelineBench.Timing> results;
                try {
                    results = get();
                } catch (Exception failure) {
                    say("The measurement could not be run: " + failure.getMessage(), true);
                    return;
                }
                for (RootPipelineBench.Timing one : results) {
                    timingModel.addRow(new Object[]{
                        one.label().isEmpty() ? "--" : one.label(),
                        one.succeeded() ? one.reading() : one.message(),
                        RootPipelineBench.relativeTo(one, results)});
                }
                RootPipelineBench.Timing best = RootPipelineBench.best(results);
                if (best == null) {
                    say("Nothing could be measured.", true);
                    return;
                }
                say("Fastest: " + best.label() + " at " + best.reading()
                    + " per call. The form's own flags are "
                    + String.join(" ", manifest.flags()) + ".", false);
            }
        }.execute();
    }

    /**
     * Fills the inputs from a tree the engine is holding.
     *
     * Typing branch names by hand is where a pipeline usually goes wrong: a
     * capital letter in the wrong place compiles and then reads nothing. The
     * tree knows its own branches and their types, so it is asked.
     */
    private void fromTree() {
        if (engine == null || !engine.isAvailable()) {
            say("The ROOT engine is not running, so no tree can be read.", true);
            return;
        }
        final String tree = JOptionPane.showInputDialog(this,
            "Which tree? It must already be open in the engine.", "Events");
        if (tree == null || tree.isBlank()) {
            return;
        }
        working(true, "Reading the branches of " + tree.trim() + "...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                // A branch whose leaf carries a count is a collection, and the
                // square brackets are what tells the two apart on the way back.
                return engine.executeClingAwait(
                    "[]{ TTree *t = SphereBridge::Need<TTree>(\"" + tree.trim()
                  + "\", \"TTree\");"
                  + " std::string out;"
                  + " TObjArray *list = t->GetListOfBranches();"
                  + " if (list == nullptr) { return out; }"
                  + " for (int i = 0; i < list->GetEntries(); ++i) {"
                  + "   TBranch *b = (TBranch *) list->At(i);"
                  + "   if (b == nullptr) { continue; }"
                  + "   TLeaf *leaf = b->GetLeaf(b->GetName());"
                  + "   out += b->GetName(); out += ':';"
                  + "   out += (leaf != nullptr ? leaf->GetTypeName() : \"Double_t\");"
                  + "   if (leaf != nullptr && leaf->GetLeafCount() != nullptr) { out += \"[]\"; }"
                  + "   out += '\\n';"
                  + " } return out; }()", 20_000L);
            }

            @Override
            protected void done() {
                working(false, null);
                String answer;
                try {
                    answer = get();
                } catch (Exception failure) {
                    say("The engine did not answer: " + failure.getMessage(), true);
                    return;
                }
                if (answer == null || answer.startsWith("ERROR")) {
                    say("No tree named " + tree.trim() + " is open in the engine.", true);
                    return;
                }
                offerBranches(tree.trim(), answer);
            }
        }.execute();
    }

    /** Lets the user pick among the branches the tree reported. */
    private void offerBranches(String tree, String answer) {
        final List<String[]> found = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            final String one = line.strip();
            final int at = one.lastIndexOf(':');
            if (at <= 0 || one.startsWith("(")) {
                continue;
            }
            final String type = cppTypeOf(one.substring(at + 1).trim());
            if (type != null) {
                found.add(new String[]{one.substring(0, at).trim(), type});
            }
        }
        if (found.isEmpty()) {
            say(tree + " reported no branch this form can use.", true);
            return;
        }

        DefaultListModel<String> offered = new DefaultListModel<>();
        for (String[] one : found) {
            offered.addElement(one[0] + "   " + one[1]);
        }
        JList<String> list = new JList<>(offered);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setVisibleRowCount(14);

        final int answered = JOptionPane.showConfirmDialog(this,
            new JScrollPane(list),
            found.size() + " branches in " + tree + " -- pick the inputs",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answered != JOptionPane.OK_OPTION || list.getSelectedIndices().length == 0) {
            return;
        }

        filling = true;
        for (int index : list.getSelectedIndices()) {
            final String[] one = found.get(index);
            // A branch name is not always a C++ identifier, and the argument it
            // becomes has to be one.
            inputModel.addRow(new Object[]{identifier(one[0]), one[1]});
            if (one[1].contains("RVec")) {
                moduleBoxes.get(RootPipelineModules.VECTORS).setSelected(true);
            }
        }
        filling = false;
        regenerate();
        say(list.getSelectedIndices().length + " input"
            + (list.getSelectedIndices().length == 1 ? "" : "s") + " taken from " + tree + ".",
            false);
    }

    /** The C++ type a ROOT leaf type stands for, or null when the form has none. */
    private static String cppTypeOf(String leafType) {
        if (leafType == null || leafType.isBlank()) {
            return null;
        }
        final boolean many = leafType.endsWith("[]");
        final String bare = many ? leafType.substring(0, leafType.length() - 2) : leafType;
        final String scalar = switch (bare) {
            case "Double_t", "double", "Double32_t" -> "double";
            case "Float_t", "float", "Float16_t" -> "float";
            case "Int_t", "int" -> "int";
            case "UInt_t", "unsigned int" -> "unsigned int";
            case "Long64_t", "Long_t", "long", "ULong64_t" -> "long";
            case "Short_t", "UShort_t" -> "int";
            case "Bool_t", "bool" -> "bool";
            case "Char_t", "UChar_t" -> many ? null : "const char *";
            default -> null;
        };
        if (scalar == null) {
            return null;
        }
        return many ? "ROOT::RVec<" + scalar + ">" : scalar;
    }

    /** A branch name turned into something C++ will take as an argument. */
    private static String identifier(String branch) {
        String made = branch.replaceAll("[^A-Za-z0-9_]", "_");
        if (made.isEmpty() || Character.isDigit(made.charAt(0))) {
            made = "b_" + made;
        }
        return made;
    }

    /**
     * Where an analysis is assembled out of stages.
     *
     * The stages are the only thing kept. From them Sphere writes the Sphere
     * commands that replay the chain on the engine now, and a standalone macro
     * that runs it anywhere ROOT does. Neither is edited by hand, so they
     * cannot drift apart, and the chain is not lost when the session ends.
     */
    private JComponent chainPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(palette.getTerminalBackground());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel source = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        source.setBackground(palette.getTerminalBackground());
        source.add(label("Chain"));
        source.add(chainName);
        source.add(label("Tree"));
        source.add(chainTree);
        source.add(label("File"));
        source.add(chainFile);
        chainName.setToolTipText("The name of the chain, and of the macro it writes.");
        chainFile.setToolTipText("The ROOT file the frame reads.");

        JPanel adding = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        adding.setBackground(palette.getTerminalBackground());
        adding.add(label("Stage"));
        adding.add(stepBox);
        adding.add(stepArguments);
        JButton addStage = small("Add");
        JButton dropStage = small("Remove");
        JButton upStage = small("Up");
        JButton downStage = small("Down");
        adding.add(addStage);
        adding.add(dropStage);
        adding.add(upStage);
        adding.add(downStage);

        stepBox.addActionListener(e -> {
            RootPipelineChain.Step step =
                (RootPipelineChain.Step) stepBox.getSelectedItem();
            if (step != null) {
                stepArguments.setToolTipText(step.explanation
                    + (step.fields().isEmpty() ? "" : ".  " + step.placeholder()));
                stepArguments.setEnabled(!step.fields().isEmpty());
            }
        });
        addStage.addActionListener(e -> {
            RootPipelineChain.Step step =
                (RootPipelineChain.Step) stepBox.getSelectedItem();
            if (step == null) {
                return;
            }
            stageModel.addElement(
                new RootPipelineChain.Stage(step, stepArguments.getText().trim()));
            stepArguments.setText("");
            chainChanged();
        });
        dropStage.addActionListener(e -> {
            final int at = stageList.getSelectedIndex();
            if (at >= 0) {
                stageModel.remove(at);
                chainChanged();
            }
        });
        upStage.addActionListener(e -> moveStage(-1));
        downStage.addActionListener(e -> moveStage(1));

        stageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stageList.setBackground(palette.getTerminalBackground());
        stageList.setForeground(palette.getTextPrimary());
        stageList.setFont(style.code(12));
        JScrollPane stageScroll = new JScrollPane(stageList);
        stageScroll.setPreferredSize(new Dimension(360, 150));

        chainPreview.setEditable(false);
        chainPreview.setFont(style.code(11));
        chainPreview.setBackground(palette.getTerminalBackground());
        chainPreview.setForeground(palette.getTerminalForeground());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            stageScroll, new JScrollPane(chainPreview));
        split.setResizeWeight(0.42);
        split.setBorder(null);
        split.setBackground(palette.getTerminalBackground());

        JPanel doing = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        doing.setBackground(palette.getTerminalBackground());
        JButton saveChain = small("Save chain");
        JButton writeMacro = small("Write macro");
        JButton runChain = small("Run on the engine");
        JButton loadChain = small("Open chain");
        doing.add(saveChain);
        doing.add(writeMacro);
        doing.add(runChain);
        doing.add(loadChain);
        saveChain.setToolTipText("Keeps the chain in user_scripts/ so it outlives the session.");
        writeMacro.setToolTipText("Writes a standalone RDataFrame macro beside it.");
        runChain.setToolTipText("Replays the chain command by command on the running engine.");

        saveChain.addActionListener(e -> saveChain());
        writeMacro.addActionListener(e -> writeMacro());
        runChain.addActionListener(e -> runChain());
        loadChain.addActionListener(e -> openChain());

        // Each line as tall as what it holds (see the Inspect tab).
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBackground(palette.getTerminalBackground());
        top.add(source, BorderLayout.NORTH);
        top.add(adding, BorderLayout.SOUTH);

        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(doing, BorderLayout.SOUTH);

        DocumentListener watcher = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { chainChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { chainChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { chainChanged(); }
        };
        chainName.getDocument().addDocumentListener(watcher);
        chainTree.getDocument().addDocumentListener(watcher);
        chainFile.getDocument().addDocumentListener(watcher);
        chainTree.setText("Events");
        return panel;
    }

    /**
     * Where a wrong answer is tracked down.
     *
     * A pipeline says one number and nothing else, so when that number is
     * wrong there is normally nothing to look at. Three ways in: the values
     * the user named with SPHERE_WATCH, a sweep that counts the answers which
     * are not numbers at all, and a walk of one input that shows where the
     * curve breaks. All three build and run the pipeline in a process of its
     * own, so looking at it cannot disturb the engine.
     */
    private JComponent inspectPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(palette.getTerminalBackground());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setBackground(palette.getTerminalBackground());
        JButton watch = small("Watch a call");
        JButton health = small("Health check");
        JButton scan = small("Scan");
        top.add(label("Arguments"));
        top.add(inspectArguments);
        top.add(watch);
        top.add(health);
        inspectArguments.setToolTipText("The values to call it with: 80 30");
        watch.setToolTipText("Calls it once and shows every value the code named "
                             + "with SPHERE_WATCH(x).");
        health.setToolTipText("Sweeps the inputs and counts the answers that are "
                              + "not a number. A pipeline that fails on one entry "
                              + "in ten thousand is found here, not in a histogram.");

        JPanel scanRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scanRow.setBackground(palette.getTerminalBackground());
        scanRow.add(label("Walk"));
        scanRow.add(scanInput);
        scanRow.add(label("from"));
        scanRow.add(scanLow);
        scanRow.add(label("to"));
        scanRow.add(scanHigh);
        scanRow.add(label("others at"));
        scanRow.add(scanHeld);
        scanRow.add(scan);
        scan.setToolTipText("Walks one input across the range while the others "
                            + "are held. A step or a spike is a branch, not a curve.");

        watchTable.setRowHeight(21);
        watchTable.setFillsViewportHeight(true);
        JScrollPane watchScroll = new JScrollPane(watchTable);
        watchScroll.setPreferredSize(new Dimension(360, 110));

        inspectSaid.setEditable(false);
        inspectSaid.setFont(style.code(12));
        inspectSaid.setBackground(palette.getTerminalBackground());
        inspectSaid.setForeground(palette.getTerminalForeground());

        // The picture first: a curve or bars say at a glance what a column of
        // numbers says only to whoever reads it all.
        inspectPicture.setFont(style.font());
        inspectPicture.showMessage("Walk an input to see its curve, watch a call to see what it named,\n"
            + "or check its health to see how often it answers a number.");
        inspectPicture.setPreferredSize(new Dimension(460, 220));
        final JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inspectPicture, watchScroll);
        left.setResizeWeight(0.75);
        left.setBorder(null);
        left.setOneTouchExpandable(true);
        left.setDividerSize(11);
        inspectPicture.setMinimumSize(new Dimension(0, 0));
        watchScroll.setMinimumSize(new Dimension(0, 0));

        final JScrollPane saidScroll = new JScrollPane(inspectSaid);
        saidScroll.setMinimumSize(new Dimension(0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, saidScroll);
        split.setOneTouchExpandable(true);
        split.setDividerSize(11);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        split.setBackground(palette.getTerminalBackground());

        // Each line as tall as what it holds: equal grid rows gave the line of
        // fields the height of the list below it, and a band under it.
        JPanel rows = new JPanel(new BorderLayout(0, 6));
        rows.setBackground(palette.getTerminalBackground());
        rows.add(top, BorderLayout.NORTH);
        rows.add(scanRow, BorderLayout.SOUTH);

        watch.addActionListener(e -> inspect("watch"));
        health.addActionListener(e -> inspect("health"));
        scan.addActionListener(e -> inspect("scan"));
        scanInput.addActionListener(e -> walkedChanged());

        panel.add(rows, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    /** Runs one of the three ways in, on a worker. */
    private void inspect(String how) {
        if (!save(false)) {
            return;
        }
        final Entry entry = current;
        if (entry == null) {
            return;
        }
        final RootPipelineManifest manifest = collect();
        if (!RootPipelineTemplates.canBeTimed(manifest)) {
            say("Only a transform or a filter taking numbers can be inspected.", true);
            return;
        }
        final double[] arguments = new double[manifest.inputs.size()];
        if ("watch".equals(how)) {
            final String[] written = inspectArguments.getText().trim().split("[,\\s]+");
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = i < written.length ? asDouble(written[i], 1.0) : 1.0;
            }
        }
        final int which = Math.max(0, scanInput.getSelectedIndex());
        final double low = asDouble(scanLow.getText(), 0.0);
        final double high = asDouble(scanHigh.getText(), 100.0);
        final double held = asDouble(scanHeld.getText(), 1.0);

        tabs.setSelectedIndex(3);
        working(true, "Building " + manifest.name + " to look inside it...");

        new SwingWorker<String, Void>() {
            private List<RootPipelineProbe.Watched> values = List.of();
            private RootPipelineProbe.Health swept;
            private RootPipelineProbe.Scan walked;
            private String problem;

            @Override
            protected String doInBackground() {
                RootUserCompiler compiler = new RootUserCompiler(new SettingsManager(), engine);
                switch (how) {
                    case "watch" -> {
                        final RootPipelineProbe.Watching watching = RootPipelineProbe.watch(
                            compiler, manifest, entry.source, arguments);
                        values = watching.values();
                        problem = watching.problem();
                        if (problem != null) {
                            return problem;
                        }
                        return values.isEmpty()
                            ? "Nothing was recorded. Name a value with SPHERE_WATCH(x) "
                              + "inside the body, then look again."
                            : values.size() + " value"
                              + (values.size() == 1 ? "" : "s") + " recorded.";
                    }
                    case "health" -> {
                        // Each input where its name says it lives: an angle
                        // swept to 100 radians is not a fault of the pipeline.
                        final double[][] ranges = RootPipelineProbe.rangesByName(manifest);
                        RootPipelineProbe.Health found = RootPipelineProbe.health(
                            compiler, manifest, entry.source, 20000, ranges);
                        swept = found;
                        if (!found.succeeded()) {
                            problem = found.message();
                            return problem;
                        }
                        return "Sweeping each input where its name says it lives:\n  "
                             + RootPipelineProbe.describeRanges(manifest, ranges) + "\n\n"
                             + found.summary() + "\n\n"
                             + (found.isClean()
                                ? "Every call answered a number."
                                : "Some calls did not answer a number. "
                                  + "Walk one input to find where.");
                    }
                    default -> {
                        RootPipelineProbe.Scan found = RootPipelineProbe.scan(
                            compiler, manifest, entry.source, which, low, high, 61, held);
                        walked = found;
                        if (!found.succeeded()) {
                            problem = found.message();
                            return problem;
                        }
                        StringBuilder out = new StringBuilder();
                        final String name = which < manifest.inputs.size()
                            ? manifest.inputs.get(which).name() : "input";
                        out.append(name).append(" from ").append(low)
                           .append(" to ").append(high)
                           .append(", the others held at ").append(held).append("\n\n");
                        for (RootPipelineProbe.Point p : found.points()) {
                            out.append(String.format("  %12.6g   %s%n", p.x(), p.y()));
                        }
                        if (!found.findings().isEmpty()) {
                            out.append("\n---- what it noticed ----\n");
                            for (String one : found.findings()) {
                                out.append("  ").append(one).append('\n');
                            }
                        }
                        return out.toString();
                    }
                }
            }

            @Override
            protected void done() {
                working(false, null);
                String said;
                try {
                    said = get();
                } catch (Exception failure) {
                    say("The pipeline could not be inspected: " + failure.getMessage(), true);
                    return;
                }
                inspectSaid.setText(said);
                inspectSaid.setCaretPosition(0);
                if (problem != null) {
                    inspectPicture.showMessage(problem);
                    say(said.lines().findFirst().orElse("It could not be inspected."), true);
                    return;
                }
                if ("watch".equals(how)) {
                    watchModel.setRowCount(0);
                    for (RootPipelineProbe.Watched one : values) {
                        watchModel.addRow(new Object[]{one.name(), one.value()});
                    }
                }
                drawInspection(how, manifest, values, swept, walked, which, low, high, held);
                say(said.lines().findFirst().orElse("Done."), false);
            }
        }.execute();
    }

    /**
     * What an inspection found, as a picture: the walk as a curve with its
     * NaN marked, a watched call as bars (an error budget when the values are
     * the c_ contributions an uncertainty pipeline names), a sweep as the
     * share of its answers that were numbers.
     */
    private void drawInspection(String how, RootPipelineManifest manifest,
                                List<RootPipelineProbe.Watched> values, RootPipelineProbe.Health swept,
                                RootPipelineProbe.Scan walked, int which, double low, double high, double held) {
        switch (how) {
            case "watch" -> {
                if (values.isEmpty()) {
                    inspectPicture.showMessage("Nothing named: SPHERE_WATCH(x) inside the body shows x here.");
                    return;
                }
                final boolean budget = values.stream()
                    .filter(one -> !one.name().equals("(returned)"))
                    .allMatch(one -> one.name().startsWith("c_"));
                double total = 0;
                double largest = 0;
                for (RootPipelineProbe.Watched one : values) {
                    if (one.name().equals("(returned)") || !Double.isFinite(one.value())) {
                        continue;
                    }
                    total += one.value() * one.value();
                    largest = Math.max(largest, Math.abs(one.value()));
                }
                final java.util.List<RootPipelinePlot.Bar> bars = new java.util.ArrayList<>();
                for (RootPipelineProbe.Watched one : values) {
                    if (one.name().equals("(returned)")) {
                        continue;
                    }
                    final double v = one.value();
                    final double share = !Double.isFinite(v) ? 0
                        : budget ? (total > 0 ? v * v / total : 0)
                        : (largest > 0 ? Math.abs(v) / largest : 0);
                    bars.add(new RootPipelinePlot.Bar(budget ? one.name().substring(2) : one.name(), v, share));
                }
                inspectPicture.showBars(budget
                    ? "Error budget of this call: what each input brings (share of the variance)"
                    : "What the call named with SPHERE_WATCH (bars against the largest)", bars, budget);
            }
            case "health" -> {
                if (swept == null || swept.tried() == 0) {
                    inspectPicture.showMessage("The sweep made no call.");
                    return;
                }
                final double tried = swept.tried();
                final long numbers = swept.tried() - swept.notANumber() - swept.infinite();
                inspectPicture.showBars("What " + swept.tried() + " calls answered, each input where its name says it lives",
                    java.util.List.of(
                        new RootPipelinePlot.Bar("a number", numbers, numbers / tried),
                        new RootPipelinePlot.Bar("NaN", swept.notANumber(), swept.notANumber() / tried),
                        new RootPipelinePlot.Bar("infinite", swept.infinite(), swept.infinite() / tried)));
            }
            default -> {
                if (walked == null || walked.points().isEmpty()) {
                    inspectPicture.showMessage("The walk gave no point.");
                    return;
                }
                final double[] xs = new double[walked.points().size()];
                final double[] ys = new double[xs.length];
                for (int k = 0; k < xs.length; k++) {
                    xs[k] = walked.points().get(k).x();
                    ys[k] = walked.points().get(k).y();
                }
                final String name = which < manifest.inputs.size() ? manifest.inputs.get(which).name() : "input";
                // The compiled pipeline is known only at the points it answered.
                inspectPicture.setExact(null);
                inspectPicture.showCurves(name + " from " + RootFormula.shortNumber(low) + " to "
                        + RootFormula.shortNumber(high) + ", the others at " + RootFormula.shortNumber(held)
                        + "  (compiled, as the engine runs it)", name, xs,
                    new RootPipelinePlot.Series(manifest.name, ys, palette.getAccent()), null, Double.NaN, "");
            }
        }
    }

    private static double asDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** Moves the selected stage, because order is what a chain is. */
    private void moveStage(int by) {
        final int at = stageList.getSelectedIndex();
        final int to = at + by;
        if (at < 0 || to < 0 || to >= stageModel.size()) {
            return;
        }
        RootPipelineChain.Stage moved = stageModel.remove(at);
        stageModel.add(to, moved);
        stageList.setSelectedIndex(to);
        chainChanged();
    }

    /** The chain the Chain tab is showing. */
    private RootPipelineChain chain() {
        RootPipelineChain chain = new RootPipelineChain();
        chain.name = chainName.getText().trim();
        chain.tree = chainTree.getText().trim();
        chain.file = chainFile.getText().trim();
        for (int i = 0; i < stageModel.size(); i++) {
            chain.stages.add(stageModel.get(i));
        }
        return chain;
    }

    /** Shows what the chain would run, both ways, after every change. */
    private void chainChanged() {
        RootPipelineChain chain = chain();
        StringBuilder out = new StringBuilder();
        out.append("--- on the engine ---\n");
        for (String one : chain.commands()) {
            out.append(one).append('\n');
        }
        out.append("\n--- as a macro ---\n").append(chain.macro());
        chainPreview.setText(out.toString());
        chainPreview.setCaretPosition(0);
    }

    /** Keeps the chain beside the macros, where it outlives the session. */
    private void saveChain() {
        RootPipelineChain chain = chain();
        final String why = chain.validate();
        if (why != null) {
            say(why, true);
            return;
        }
        Path layer = globalLayer.isSelected() || activeProject == null
            ? RootUserPipeline.globalRoot() : RootUserPipeline.projectRoot(activeProject);
        RootUserPipeline.ensureLayout(layer);
        Path target = RootPipelineChain.fileIn(layer, chain.name);
        try {
            Files.writeString(target, chain.encode(), StandardCharsets.UTF_8);
            say("Chain written to " + target, false);
            AppLogger.success("Chain " + chain.name + " written to " + target);
        } catch (IOException failure) {
            say("Could not write " + target + ": " + failure.getMessage(), true);
        }
    }

    /** Writes the standalone macro the chain stands for. */
    private void writeMacro() {
        RootPipelineChain chain = chain();
        final String why = chain.validate();
        if (why != null) {
            say(why, true);
            return;
        }
        Path layer = globalLayer.isSelected() || activeProject == null
            ? RootUserPipeline.globalRoot() : RootUserPipeline.projectRoot(activeProject);
        RootUserPipeline.ensureLayout(layer);
        Path target = layer.resolve(RootUserPipeline.SCRIPTS_DIR)
                           .resolve(chain.name + ".C");
        try {
            Files.writeString(target, chain.macro(), StandardCharsets.UTF_8);
            say("Macro written. Run it with :root script run " + chain.name + ".C", false);
            AppLogger.success("Macro " + target + " written from the chain.");
        } catch (IOException failure) {
            say("Could not write " + target + ": " + failure.getMessage(), true);
        }
    }

    /**
     * Replays the chain on the running engine, one command at a time.
     *
     * The commands go through the router rather than the engine directly, so a
     * chain does exactly what the user would have done by typing, and a stage
     * that fails is reported the way any command failing is.
     */
    private void runChain() {
        RootPipelineChain chain = chain();
        final String why = chain.validate();
        if (why != null) {
            say(why, true);
            return;
        }
        if (engine == null || !engine.isAvailable()) {
            say("The ROOT engine is not running.", true);
            return;
        }
        final List<String> commands = chain.commands();
        working(true, "Replaying " + commands.size() + " commands...");
        AppLogger.info("Chain " + chain.name + ": replaying " + commands.size() + " commands.");

        // Off the event thread: each stage waits for the engine's answer, and a
        // stage that runs the event loop waits for the whole dataset, so the
        // window froze until the chain had been replayed.
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                for (String one : commands) {
                    AppLogger.raw("  " + one);
                    com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
                    if (router == null) {
                        return "No command router is available to replay it.";
                    }
                    router.processInput(one);
                }
                return null;
            }

            @Override
            protected void done() {
                working(false, null);
                String failure;
                try {
                    failure = get();
                } catch (Exception stopped) {
                    failure = "The replay stopped: " + stopped.getMessage();
                }
                if (failure != null) {
                    say(failure, true);
                    return;
                }
                say("The chain has been replayed. :root rdf report " + chain.frame()
                    + " says what each cut kept.", false);
            }
        }.execute();
    }

    /** Opens a chain that was saved earlier. */
    private void openChain() {
        List<Path> found = new ArrayList<>();
        for (Path layer : RootUserPipeline.layers(activeProject)) {
            found.addAll(RootPipelineChain.chainsIn(layer));
        }
        if (found.isEmpty()) {
            say("No chain has been saved yet.", true);
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) {
            names[i] = found.get(i).getFileName().toString();
        }
        final Object picked = JOptionPane.showInputDialog(this, "Which chain?",
            "Open a chain", JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (picked == null) {
            return;
        }
        for (Path one : found) {
            if (!one.getFileName().toString().equals(picked.toString())) {
                continue;
            }
            RootPipelineChain chain = RootPipelineChain.read(one);
            if (chain == null) {
                say(picked + " is not a chain Sphere wrote.", true);
                return;
            }
            chainName.setText(chain.name);
            chainTree.setText(chain.tree);
            chainFile.setText(chain.file);
            stageModel.clear();
            for (RootPipelineChain.Stage stage : chain.stages) {
                stageModel.addElement(stage);
            }
            chainChanged();
            say(chain.stages.size() + " stages read from " + one.getFileName(), false);
            return;
        }
    }

    /** Offers the versions kept for the open pipeline, and puts one back. */
    private void revert() {
        final Entry entry = current;
        if (entry == null) {
            say("Open a pipeline first.", true);
            return;
        }
        List<RootPipelineHistory.Version> versions =
            RootPipelineHistory.versionsOf(entry.source);
        if (versions.isEmpty()) {
            say("No earlier version of " + entry.manifest.name + " has been kept yet.", true);
            return;
        }
        final Object picked = JOptionPane.showInputDialog(this,
            "Which version of " + entry.manifest.name + "?",
            "Go back", JOptionPane.PLAIN_MESSAGE, null,
            versions.toArray(), versions.get(0));
        if (!(picked instanceof RootPipelineHistory.Version version)) {
            return;
        }
        if (!RootPipelineHistory.revertTo(entry.source, version)) {
            say("That version could not be put back.", true);
            return;
        }
        refresh();
        selectByName(entry.manifest.name);
        openSelected();
        say("Back to the version of " + version.when()
            + ". The one it replaced was kept too.", false);
        AppLogger.success("Pipeline " + entry.manifest.name
            + " reverted to " + version.when());
    }

    /** Writes the open pipeline into one archive, beside its own folder. */
    private void exportOne() {
        final Entry entry = current;
        if (entry == null) {
            say("Open a pipeline first.", true);
            return;
        }
        if (!save(false)) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Where should " + entry.manifest.name + " go?");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path archive = RootPipelineHistory.export(
            entry.source, chooser.getSelectedFile().toPath());
        if (archive == null) {
            say("The pipeline could not be exported.", true);
            return;
        }
        say("Exported to " + archive + ". The library is not in it: "
            + "the source rebuilds anywhere, a built library does not.", false);
        AppLogger.success("Pipeline " + entry.manifest.name + " exported to " + archive);
    }

    /** Reads a pipeline somebody else exported. */
    private void importOne() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Which archive?");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Pipeline archives", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path layer = globalLayer.isSelected() || activeProject == null
            ? RootUserPipeline.globalRoot() : RootUserPipeline.projectRoot(activeProject);
        RootPipelineHistory.Taken taken = RootPipelineHistory.importInto(
            chooser.getSelectedFile().toPath(), layer);

        for (String one : taken.refused()) {
            AppLogger.warn("  " + one);
        }
        refresh();
        if (taken.written().isEmpty()) {
            say("Nothing was taken in. " + (taken.refused().isEmpty()
                ? "The archive held no pipeline."
                : String.join("; ", taken.refused())), true);
            return;
        }
        say("Taken in: " + String.join(", ", taken.written())
            + (taken.refused().isEmpty() ? "" : "   (" + taken.refused().size()
               + " left alone)"), false);
        AppLogger.success("Imported " + taken.written().size() + " file"
            + (taken.written().size() == 1 ? "" : "s") + " into " + layer);
    }

    /**
     * The catalog and its search belong to a new pipeline only. A copy can be
     * made from any pipeline, so that button stays, and so do the formula and
     * the macro, which start a new one from what is open.
     */
    private void setStartEnabled(boolean fresh) {
        recipeBox.setEnabled(fresh);
        recipeFind.setEnabled(fresh);
    }

    /** Ticks exactly the modules a manifest names. */
    private void setModules(java.util.List<RootPipelineModules> wanted) {
        for (var entry : moduleBoxes.entrySet()) {
            entry.getValue().setSelected(wanted != null && wanted.contains(entry.getKey()));
        }
    }

    /**
     * Puts a ready-made quantity into the form.
     *
     * The recipe brings the kind, the inputs and the modules its body needs, so
     * all three are set together: a body written for four arguments under a
     * signature that declares two would not compile, and the form would look
     * complete while being wrong.
     */
    private void chooseRecipe() {
        if (filling || current != null) {
            return;
        }
        final RootPipelineRecipes.Recipe recipe =
            (RootPipelineRecipes.Recipe) recipeBox.getSelectedItem();
        // Picking again the recipe already applied is how its inputs are reset.
        if (recipe != null) {
            // A name a start suggested belongs to that start, not to this recipe.
            if (recipe != applied && namedByStart != null
                    && nameField.getText().strip().equals(namedByStart)) {
                nameField.setText("");
                namedByStart = null;
            }
            apply(recipe, java.util.List.of());
        }
    }

    /** Fills the form from one recipe, whatever it was made from. */
    private void apply(RootPipelineRecipes.Recipe recipe, java.util.List<String> notes) {
        // The description follows the recipe for as long as the user has not
        // written one: it is blank, or still the previous recipe's own.
        final String description = descriptionField.getText();
        final boolean ours = description.isBlank()
            || (applied != null && description.equals(applied.about()));
        filling = true;
        applied = recipe;
        kindBox.setSelectedItem(recipe.kind());
        inputModel.setRowCount(0);
        for (RootPipelineManifest.Input input : recipe.inputs()) {
            inputModel.addRow(new Object[]{input.name(), input.type()});
        }
        setModules(recipe.modules());
        if (ours) {
            descriptionField.setText(recipe.body() == null ? "" : recipe.about());
        }
        filling = false;
        regenerate();
        updateSuggestion();
        final String said = recipe.body() == null
            ? "Blank body. Fill it in yourself."
            : recipe.title() + " is ready."
              + (recipe.family().written() ? "" : " Change the names if your branches differ.");
        final java.util.List<String> told = notes == null ? java.util.List.of() : notes;
        if (told.isEmpty()) {
            say(said, false);
        } else {
            // The line has room for one note: the one that matters most -- a
            // place where it stops being a number, else what it gives.
            final String headline = headline(told);
            say(said + (told.size() > 1 ? "  (" + told.size() + " notes in the Build log)  " : "  ")
                + headline, headline.contains("NaN") || headline.contains("inf ") || headline.startsWith("WARNING"));
        }
        if (!told.isEmpty()) {
            logArea.append("---- " + recipe.title() + "\n");
            for (String one : told) {
                logArea.append("  " + one + "\n");
            }
        }
    }

    /**
     * Fills the form from a start the command line or a dialog made.
     *
     * A start that is not in the catalog -- a formula, a copy, a macro -- is put
     * at the top of the box, so what the form was filled from stays visible.
     * A start always makes a new pipeline, whatever the form was showing.
     */
    void start(RootPipelineStarts.Start start) {
        if (start == null) {
            return;
        }
        if (!start.ok()) {
            say(RootPipelineStarts.firstLine(start.error()), true);
            return;
        }
        if (current != null) {
            blank();
        }
        final RootPipelineRecipes.Recipe recipe = start.recipe();
        final boolean was = filling;
        filling = true;
        if (recipeModel.getIndexOf(recipe) < 0) {
            recipeModel.insertElementAt(recipe, 0);
        }
        recipeBox.setSelectedItem(recipe);
        filling = was;
        final String typed = nameField.getText().strip();
        if (typed.isEmpty() || typed.equals(namedByStart)) {
            nameField.setText(start.suggestedName() == null ? "" : start.suggestedName());
            namedByStart = start.suggestedName();
        }
        if (start.options() != null) {
            applyOptions(start.options());
        }
        apply(recipe, start.notes());
        if (nameField.getText().isBlank()) {
            nameField.requestFocusInWindow();
        }
    }

    /** The note worth the status line: a warning first, then a preview, then the first. */
    private static String headline(java.util.List<String> notes) {
        for (String one : notes) {
            if (one.startsWith("WARNING") || one.contains("is NaN") || one.contains("gives NaN")
                    || one.contains(" is inf") || one.contains("keeps state")) {
                return one;
            }
        }
        for (String one : notes) {
            if (one.startsWith("At ") || one.startsWith("With 5% errors") || one.startsWith("It ")) {
                return one;
            }
        }
        return notes.get(0);
    }

    /** The compiler row, showing what a manifest asks for. */
    private void showCompiler(RootPipelineManifest m) {
        level.setSelectedItem("-" + m.effectiveLevel());
        standard.setSelectedItem("C++" + m.standard);
        warnings.setSelected(m.warnings);
        debug.setSelected(m.debug);
        fastMath.setSelected(m.fastMath);
    }

    /** What the compiler row says, into a manifest. */
    private void readCompiler(RootPipelineManifest m) {
        final String chosen = String.valueOf(level.getSelectedItem()).replace("-", "");
        m.optimize = !chosen.equals("O0");
        m.level = m.optimize ? chosen : "O3";
        final String std = String.valueOf(standard.getSelectedItem()).replace("C++", "");
        m.standard = std.equals("17") ? 17 : std.equals("23") ? 23 : 20;
        m.warnings = warnings.isSelected();
        m.debug = debug.isSelected();
        m.fastMath = fastMath.isSelected();
    }

    /**
     * Says what fast math costs, the moment it is chosen: the compiler may
     * then take for granted that no value is NaN, and a check such as
     * std::isnan can be compiled into "never" -- which is what the health
     * check and the probes count on.
     */
    private void fastMathWarning() {
        if (filling) {
            return;
        }
        if (fastMath.isSelected() || "-Ofast".equals(level.getSelectedItem())) {
            say("Fast math may compile isnan() into \"never\": the health check and :root pipeline check"
                + " can no longer see a NaN in this pipeline.", palette.getLogWarnText());
        }
    }

    /** The build settings a start brings: a copy's own, or stateless switched off. */
    private void applyOptions(RootPipelineManifest options) {
        filling = true;
        threadSafe.setSelected(options.threadSafe);
        openMp.setSelected(options.openMp);
        showCompiler(options);
        showRuntime(options);
        nativeArch.setSelected(options.nativeArch);
        showInHelp.setSelected(options.showInHelp);
        extraFlags.setText(options.extraFlags == null ? "" : options.extraFlags);
        if (!options.global && projectLayer.isEnabled()) {
            projectLayer.setSelected(true);
        } else {
            globalLayer.setSelected(true);
        }
        if (options.description != null && !options.description.isBlank()) {
            descriptionField.setText(options.description);
        }
        filling = false;
    }

    /** The recipe the form starts from, or null once a pipeline exists on disk. */
    private RootPipelineRecipes.Recipe chosenRecipe() {
        return current != null ? null : applied;
    }

    /** The search, the catalog, the other ways in, and what the name suggests. */
    private JComponent startRow() {
        recipeFind.setToolTipText("<html>Narrows the catalog. Physicists' shorthand works:<br>"
            + "dr, dphi, mt, mjj, ht, met, iso, z, pval, eff, bw, cb, pu, sf, xsec, alphas, chi2,<br>"
            + "and so do typos. Down and Up walk the results, Enter takes one, Escape clears.</html>");
        recipeFind.putClientProperty("JTextField.placeholderText", "find…");
        fromFormulaButton.setToolTipText("<html>One line of math, read as it is typed: its value, or a filter<br>"
            + "when it compares -- or its uncertainty by linear propagation, or its derivative,<br>"
            + "both exact and checked against finite differences.</html>");
        fromCopyButton.setToolTipText("<html>A pipeline already written, under its next free version name,<br>"
            + "with its build options and what surrounds its entry point.</html>");
        fromMacroButton.setToolTipText("<html>One function of a ROOT macro, with the helpers, constants and<br>"
            + "includes it uses. A function that keeps state is not declared stateless.</html>");
        for (JButton one : new JButton[]{fromFormulaButton, fromCopyButton, fromMacroButton}) {
            one.setFocusPainted(false);
            one.setMargin(new Insets(2, 6, 2, 6));
        }
        suggestion.setVisible(false);
        suggestion.setFocusPainted(false);
        suggestion.setBorderPainted(false);
        suggestion.setContentAreaFilled(false);
        suggestion.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        suggestion.setForeground(palette.getAccent());
        suggestion.setMargin(new Insets(0, 4, 0, 4));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setBackground(palette.getTerminalBackground());
        buttons.add(fromFormulaButton);
        buttons.add(fromCopyButton);
        buttons.add(fromMacroButton);
        buttons.add(suggestion);

        // Each control at its own height, the search field centered beside the
        // taller list, and each line as tall as what it holds: two equal grid
        // rows stretched the field to the list's height and left a band under
        // the buttons.
        JPanel both = new JPanel(new GridBagLayout());
        both.setBackground(palette.getTerminalBackground());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 6);
        both.add(recipeFind, c);
        c.gridx = 1; c.weightx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        both.add(recipeBox, c);
        c.gridx = 0; c.gridy = 1; c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 0, 0);
        both.add(buttons, c);
        return both;
    }

    /** Shows the catalog narrowed to what the search field says, best first. */
    private void filterRecipes() {
        final String query = recipeFind.getText();
        // blank() empties the field while it is itself filling the form, so
        // the flag is put back as it was rather than simply cleared.
        final boolean was = filling;
        filling = true;
        recipeModel.removeAllElements();
        // What the form was filled from stays at the top while the catalog is searched.
        final boolean keepApplied = applied != null && applied.family().written();
        if (keepApplied) {
            recipeModel.addElement(applied);
        }
        for (RootPipelineStarts.Hit hit : RootPipelineStarts.search(query, 0)) {
            recipeModel.addElement(hit.recipe());
        }
        if (query.isBlank() && applied != null && recipeModel.getIndexOf(applied) >= 0) {
            recipeBox.setSelectedItem(applied);
        } else {
            recipeBox.setSelectedIndex(-1);
        }
        filling = was;
        if (was) {
            return;
        }
        if (query.isBlank()) {
            recipeBox.hidePopup();
            return;
        }
        final int found = recipeModel.getSize() - (keepApplied ? 1 : 0);
        say(found == 0
            ? "Nothing in the catalog answers \"" + query.trim() + "\"."
            : found + (found == 1 ? " recipe answers" : " recipes answer") + " \"" + query.trim()
              + "\". Down and Up to choose, Enter to take it.", false);
        if (found > 0 && recipeBox.isShowing() && current == null && recipeFind.hasFocus()) {
            // Hidden and shown again, so the list takes the size of what it now holds.
            recipeBox.hidePopup();
            recipeBox.showPopup();
        }
    }

    /** Enter in the search field: the result chosen with the arrows, or else the best one. */
    private void takeBestMatch() {
        if (current != null || recipeModel.getSize() == 0) {
            return;
        }
        recipeBox.hidePopup();
        RootPipelineRecipes.Recipe chosen = (RootPipelineRecipes.Recipe) recipeBox.getSelectedItem();
        if (chosen == null) {
            final boolean skip = recipeModel.getSize() > 1 && recipeModel.getElementAt(0) == applied
                && applied.family().written();
            chosen = recipeModel.getElementAt(skip ? 1 : 0);
        }
        final boolean was = filling;
        filling = true;
        recipeBox.setSelectedItem(chosen);
        filling = was;
        apply(chosen, java.util.List.of());
    }

    /** Down and Up in the search field walk the results without applying them. */
    private void moveRecipe(int step) {
        final int size = recipeModel.getSize();
        if (current != null || size == 0) {
            return;
        }
        final int at = recipeBox.getSelectedIndex();
        final int next = at < 0 ? (step > 0 ? 0 : size - 1) : Math.max(0, Math.min(size - 1, at + step));
        final boolean was = filling;
        filling = true;
        recipeBox.setSelectedIndex(next);
        filling = was;
        if (!recipeBox.isPopupVisible() && recipeBox.isShowing()) {
            recipeBox.showPopup();
        }
    }

    /** Escape: the search is cleared, and a second Escape closes the list. */
    private void clearSearch() {
        if (!recipeFind.getText().isEmpty()) {
            recipeFind.setText("");
        } else {
            recipeBox.hidePopup();
        }
    }

    /** Offers the recipe the pipeline's name points to, while the form is still new. */
    private void updateSuggestion() {
        // Offered only while the form starts from nothing: once a start was
        // chosen, a suggestion would second-guess it.
        final boolean fromNothing = applied == null || applied.body() == null;
        suggested = current != null || !fromNothing ? null : RootPipelineStarts.suggest(nameField.getText());
        suggestion.setVisible(suggested != null);
        if (suggested != null) {
            suggestion.setText("<html><u>→ " + suggested.title() + "?</u></html>");
            suggestion.setToolTipText("The name looks like this recipe. Click to start from it: "
                + suggested.about());
        }
    }

    /**
     * The Formula button: a line of math, read as it is typed.
     *
     * Every keystroke reads the formula again, so a mistake is shown with a
     * caret under it before anything is compiled, and a correct one shows its
     * inputs, its kind, where it can stop being a number, and what it gives at
     * typical values. The same formula can start its uncertainty or its
     * derivative instead of its value.
     */
    private void askFormula() {
        final JDialog dialog = new JDialog(this, "Start from a formula", true);
        final JTextField field = new JTextField(52);
        field.setFont(style.code(14));
        // Sphere's field, whose border turns to the warning color while what it holds is wrong.
        style.checked(field);

        final JRadioButton asValue = new JRadioButton("its value (a filter when it compares)", true);
        final JRadioButton asError = new JRadioButton("its uncertainty, by linear propagation");
        final JRadioButton asSlope = new JRadioButton("its derivative in");
        final ButtonGroup modes = new ButtonGroup();
        final JComboBox<String> slopeOf = new JComboBox<>();
        // A fixed width: filled as the formula is typed, the box must not grow
        // past the dialog's edge and wrap out of sight.
        slopeOf.setPrototypeDisplayValue("dphi_lead_err");
        final JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeRow.setBackground(palette.getTerminalBackground());
        for (JRadioButton one : new JRadioButton[]{asValue, asError, asSlope}) {
            modes.add(one);
            one.setBackground(palette.getTerminalBackground());
            style.toggle(one);
            modeRow.add(one);
        }
        modeRow.add(slopeOf);

        // The formula as a curve, drawn as it is typed: computed here, in Java,
        // so it needs neither a compiler nor ROOT.
        final RootPipelinePlot picture = new RootPipelinePlot(palette);
        picture.setFont(style.font());
        picture.setPreferredSize(new Dimension(680, 230));
        final JComboBox<String> against = new JComboBox<>();
        against.setPrototypeDisplayValue("dphi_lead_err");
        against.setToolTipText("The input the curve walks, across the range its name suggests; "
            + "the others stay at typical values.");
        final JPanel pictureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pictureRow.setBackground(palette.getTerminalBackground());
        final JLabel drawnAgainst = label("drawn against");
        drawnAgainst.setBorder(new EmptyBorder(0, 0, 0, 4));
        pictureRow.add(drawnAgainst);
        pictureRow.add(against);
        final JPanel pictureBox = new JPanel(new BorderLayout(0, 4));
        pictureBox.setBackground(palette.getTerminalBackground());
        pictureBox.setBorder(new EmptyBorder(0, 12, 8, 12));
        pictureBox.add(pictureRow, BorderLayout.NORTH);
        pictureBox.add(picture, BorderLayout.CENTER);

        // Formulas a physicist types every week, one click away: the fastest
        // way to see what the dialog does is to see it do it.
        final JPanel examples = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        examples.setBackground(palette.getTerminalBackground());
        final JLabel tryOne = label("try");
        tryOne.setBorder(new EmptyBorder(0, 0, 0, 2));
        examples.add(tryOne);
        for (String[] one : new String[][]{
                {"transverse mass", "sqrt(2*pt*met*(1-cos(dphi)))"},
                {"ΔR", "sqrt((eta1-eta2)^2 + dphi^2)"},
                {"invariant mass", "sqrt(2*pt1*pt2*(cosh(eta1-eta2)-cos(phi1-phi2)))"},
                {"turn-on", "plateau/(1+exp(-(pt-threshold)/width))"},
                {"Breit-Wigner", "1/((m*m-mz*mz)^2 + mz*mz*width*width)"},
                {"Asimov Z", "sqrt(2*((s+b)*log(1+s/b)-s))"},
                {"central jet", "pt > 30 and abs(eta) < 2.5"}}) {
            final JButton chip = new JButton(one[0]);
            style.button(chip);
            chip.setToolTipText(one[1]);
            chip.addActionListener(e -> {
                field.setText(one[1]);
                field.requestFocusInWindow();
            });
            examples.add(chip);
        }

        final JTextArea live = new JTextArea(8, 80);
        live.setEditable(false);
        live.setLineWrap(true);
        live.setWrapStyleWord(true);
        live.setFont(style.code(12));
        live.setBackground(palette.getTerminalBackground());
        live.setBorder(new EmptyBorder(6, 8, 6, 8));

        final JButton take = new JButton("Start from it");
        final JButton cancel = new JButton("Cancel");
        style.button(take);
        style.button(cancel);
        style.combo(slopeOf);
        style.combo(against);
        final RootPipelineStarts.Start[] made = {null};
        final boolean[] busy = {false};
        final String hint = "Type a formula: its names become the inputs, in the order they appear.\n\n"
            + "  sqrt(2*pt*met*(1-cos(dphi)))        a value\n"
            + "  pt > 25 && abs(eta) < 2.4           a filter\n"
            + "  0 < x < 1,  x^2,  x**2,  1/2,  %    read as mathematics reads them\n"
            + "  TMath::Pi(),  TMath::Gaus(x,0,1)    ROOT's own functions\n\n"
            + "Nothing is compiled until you start from it.";

        final Runnable read = () -> {
            if (busy[0]) {
                return;
            }
            busy[0] = true;
            try {
                final String text = field.getText();
                final java.util.List<String> inputs = RootPipelineStarts.formulaInputs(text);
                final java.util.List<String> offered = new java.util.ArrayList<>();
                for (int i = 0; i < slopeOf.getItemCount(); i++) {
                    offered.add(slopeOf.getItemAt(i));
                }
                if (!inputs.isEmpty() && !offered.equals(inputs)) {
                    final Object had = slopeOf.getSelectedItem();
                    slopeOf.removeAllItems();
                    for (String one : inputs) {
                        slopeOf.addItem(one);
                    }
                    if (had != null && inputs.contains(had)) {
                        slopeOf.setSelectedItem(had);
                    }
                    final Object drawn = against.getSelectedItem();
                    against.removeAllItems();
                    for (String one : inputs) {
                        against.addItem(one);
                    }
                    if (drawn != null && inputs.contains(drawn)) {
                        against.setSelectedItem(drawn);
                    }
                }
                slopeOf.setEnabled(asSlope.isSelected());
                against.setEnabled(!asError.isSelected());
                if (text.isBlank()) {
                    made[0] = null;
                    take.setEnabled(false);
                    style.wrong(field, false);
                    live.setForeground(palette.getTextSecondary());
                    live.setText(hint);
                    picture.showMessage("Its curve appears here as you type: one input across the range"
                        + " its name suggests,\nthe others at typical values, and in red where it is not a number.");
                    return;
                }
                final RootPipelineStarts.Start s = asError.isSelected()
                    ? RootPipelineStarts.fromUncertainty(text)
                    : asSlope.isSelected()
                      ? RootPipelineStarts.fromDerivative(text, (String) slopeOf.getSelectedItem())
                      : RootPipelineStarts.fromFormula(text);
                made[0] = s.ok() ? s : null;
                take.setEnabled(s.ok());
                style.wrong(field, !s.ok());
                if (s.ok()) {
                    final RootPipelineManifest preview = new RootPipelineManifest();
                    preview.name = nameField.getText().isBlank() ? "pipeline" : nameField.getText().trim();
                    RootPipelineRecipes.applyTo(s.recipe(), preview);
                    final StringBuilder told = new StringBuilder(s.recipe().kind().token)
                        .append(":  ").append(preview.signature()).append("\n\n");
                    for (String one : s.notes()) {
                        told.append("  ").append(one).append('\n');
                    }
                    live.setForeground(palette.getTerminalForeground());
                    live.setText(told.toString());
                    drawFormula(picture, text, asError.isSelected(),
                        asSlope.isSelected() ? (String) slopeOf.getSelectedItem() : null,
                        (String) against.getSelectedItem());
                } else {
                    // The last picture stays while the formula is half typed.
                    live.setForeground(palette.getLogWarnText());
                    live.setText(s.error());
                }
                live.setCaretPosition(0);
            } finally {
                busy[0] = false;
            }
        };
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { read.run(); }
            @Override public void removeUpdate(DocumentEvent e) { read.run(); }
            @Override public void changedUpdate(DocumentEvent e) { read.run(); }
        });
        for (JRadioButton one : new JRadioButton[]{asValue, asError, asSlope}) {
            one.addActionListener(e -> read.run());
        }
        slopeOf.addActionListener(e -> read.run());
        against.addActionListener(e -> read.run());
        take.addActionListener(e -> {
            if (made[0] != null) {
                final RootPipelineStarts.Start chosen = made[0];
                dialog.dispose();
                start(chosen);
            }
        });
        cancel.addActionListener(e -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(take);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        final JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setBackground(palette.getTerminalBackground());
        top.setBorder(new EmptyBorder(12, 12, 8, 12));
        top.add(field, BorderLayout.NORTH);
        top.add(examples, BorderLayout.CENTER);
        top.add(modeRow, BorderLayout.SOUTH);
        final JScrollPane scroll = new JScrollPane(live);
        scroll.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(0, 12, 0, 12),
            BorderFactory.createLineBorder(palette.getBorder())));
        scroll.getViewport().setBackground(palette.getTerminalBackground());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        buttons.setBackground(palette.getTerminalBackground());
        buttons.add(cancel);
        buttons.add(take);

        final JPanel middle = new JPanel(new BorderLayout());
        middle.setBackground(palette.getTerminalBackground());
        middle.add(pictureBox, BorderLayout.NORTH);
        middle.add(scroll, BorderLayout.CENTER);

        dialog.getContentPane().setBackground(palette.getTerminalBackground());
        dialog.setLayout(new BorderLayout());
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(middle, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        read.run();
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * The picture under a formula: its curve along one input, the others at
     * typical values; with its derivative in front when that is what is being
     * made; or, for an uncertainty, the error budget at typical values. All of
     * it is computed by Sphere's own evaluator, so it is there before anything
     * is compiled, and on a machine with no ROOT at all.
     */
    private void drawFormula(RootPipelinePlot picture, String text, boolean error,
                             String slope, String against) {
        final RootFormula.Parsed parsed;
        try {
            parsed = RootFormula.parse(text);
        } catch (RootFormula.Problem unreadable) {
            return;
        }
        final java.util.List<String> inputs = parsed.inputs();
        try {
            if (inputs.isEmpty()) {
                picture.showMessage("A constant: " + RootFormula.shortNumber(
                    RootFormula.eval(parsed.root(), java.util.Map.of())));
                return;
            }
            final java.util.Map<String, Double> typical = RootFormula.typical(inputs);
            if (error) {
                final java.util.List<RootFormula.Share> shares =
                    RootFormula.budget(parsed.root(), inputs, typical, 0.05);
                double variance = 0;
                final java.util.List<RootPipelinePlot.Bar> bars = new java.util.ArrayList<>();
                for (RootFormula.Share one : shares) {
                    variance += one.contribution() * one.contribution();
                    bars.add(new RootPipelinePlot.Bar(one.input(), one.contribution(), one.fraction()));
                }
                picture.showBars("Error budget at typical values, every input known to 5%:   "
                    + RootFormula.shortNumber(RootFormula.eval(parsed.root(), typical))
                    + " ± " + RootFormula.shortNumber(Math.sqrt(variance))
                    + "   (share of the variance)", bars);
                return;
            }
            final String x = against != null && inputs.contains(against) ? against
                : slope != null && inputs.contains(slope) ? slope : inputs.get(0);
            final double[] lives = RootFormula.range(x);
            final double at = typical.get(x);
            final double[] xs = RootFormula.grid(Math.min(lives[0], at), Math.max(lives[1], at), 241);
            final double[] values = RootFormula.sample(parsed.root(), x, xs, typical);
            final String held = inputs.size() > 1 ? ", the others at typical values" : "";
            final String mark = x + " = " + RootFormula.shortNumber(at);
            if (slope != null && inputs.contains(slope)) {
                final RootFormula.Node derivative = RootFormula.derivative(parsed.root(), slope);
                picture.setExact(along(derivative, x, typical));
                final double[] slopes = RootFormula.sample(derivative, x, xs, typical);
                picture.showCurves("d/d" + slope + " along " + x + held, x, xs,
                    new RootPipelinePlot.Series("d/d" + slope, slopes, palette.getAccent()),
                    new RootPipelinePlot.Series("the formula", values, palette.getTextSecondary()),
                    at, exactly(derivative, typical), mark);
            } else {
                picture.setExact(along(parsed.root(), x, typical));
                picture.showCurves((parsed.condition() ? "Kept (1) or not (0)" : "The formula")
                        + " along " + x + held, x, xs,
                    new RootPipelinePlot.Series(parsed.condition() ? "kept" : "value", values,
                        palette.getAccent()),
                    null, at, exactly(parsed.root(), typical), mark);
            }
        } catch (RootFormula.NotEvaluable cannot) {
            picture.showMessage("No picture: " + cannot.getMessage()
                + "\nOnly the compiled pipeline can say what it gives.");
        } catch (RootFormula.Problem noSlope) {
            picture.showMessage("No picture of the derivative: " + noSlope.getMessage());
        }
    }

    /** f along x, the others held: what the pointer reads off a curve, exactly. */
    private static java.util.function.DoubleUnaryOperator along(RootFormula.Node f, String x,
                                                                java.util.Map<String, Double> held) {
        final java.util.Map<String, Double> at = new java.util.HashMap<>(held);
        return value -> {
            at.put(x, value);
            return exactly(f, at);
        };
    }

    /** f at a point, or NaN when it is not a number there. */
    private static double exactly(RootFormula.Node f, java.util.Map<String, Double> at) {
        try {
            return RootFormula.eval(f, at);
        } catch (RootFormula.NotEvaluable cannot) {
            return Double.NaN;
        }
    }

    /** The Copy button. */
    private void askCopy() {
        final java.util.List<Path> sources = RootPipelineStarts.copyable(activeProject);
        if (sources.isEmpty()) {
            say("No pipeline is written yet, so there is nothing to copy.", false);
            return;
        }
        // Each line says what the pipeline is and where it lives, so a project's
        // pipeline and a global one of the same name cannot be mistaken.
        final Path global = RootUserPipeline.globalRoot();
        final String[] labels = new String[sources.size()];
        int initial = 0;
        final Entry selected = pipelines.getSelectedValue();
        for (int i = 0; i < labels.length; i++) {
            final RootPipelineManifest m = RootPipelineManifest.fromSource(sources.get(i));
            final String where = sources.get(i).toAbsolutePath().normalize().startsWith(global)
                ? "global" : "project";
            labels[i] = (m == null ? sources.get(i).getFileName().toString() : m.signature())
                + "   -   " + where;
            if (selected != null && selected.source != null
                    && selected.source.toAbsolutePath().normalize()
                           .equals(sources.get(i).toAbsolutePath().normalize())) {
                initial = i;
            }
        }
        final Object picked = JOptionPane.showInputDialog(this, "Which pipeline?",
            "Start from a copy", JOptionPane.PLAIN_MESSAGE, null, labels, labels[initial]);
        if (picked == null) {
            return;
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(picked)) {
                start(RootPipelineStarts.fromPipeline(sources.get(i), activeProject));
                return;
            }
        }
    }

    /** The Macro button: a file, then one of its functions. */
    private void askMacro() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Which macro?");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "ROOT macros and C++", "C", "c", "cc", "cxx", "cpp", "h", "hh", "hpp", "hxx"));
        final Path scripts = (activeProject != null && !activeProject.isBlank()
            ? RootUserPipeline.projectRoot(activeProject) : RootUserPipeline.globalRoot())
            .resolve(RootUserPipeline.SCRIPTS_DIR);
        if (Files.isDirectory(scripts)) {
            chooser.setCurrentDirectory(scripts.toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final Path file = chooser.getSelectedFile().toPath();
        final RootPipelineStarts.Macro macro = RootPipelineStarts.readMacro(file);
        if (macro == null) {
            say("Cannot read " + file.getFileName() + ".", true);
            return;
        }
        final java.util.List<RootPipelineStarts.Function> functions = macro.functions();
        if (functions.isEmpty()) {
            say(macro.origin() + " defines no function at file scope.", true);
            return;
        }
        RootPipelineStarts.Function chosen = functions.get(0);
        if (functions.size() > 1) {
            // Each function says what it would become, or why it cannot, so the
            // choice is made knowing; the first that can is offered first.
            final String[] lines = new String[functions.size()];
            int first = -1;
            for (int i = 0; i < lines.length; i++) {
                lines[i] = RootPipelineStarts.verdict(macro, functions.get(i));
                if (first < 0 && RootPipelineStarts.fromFunction(macro, functions.get(i), null).ok()) {
                    first = i;
                }
            }
            final Object picked = JOptionPane.showInputDialog(this,
                "Which function of " + macro.origin() + "?", "Start from a macro",
                JOptionPane.PLAIN_MESSAGE, null, lines, lines[Math.max(first, 0)]);
            if (picked == null) {
                return;
            }
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].equals(picked)) {
                    chosen = functions.get(i);
                    break;
                }
            }
        }
        start(RootPipelineStarts.fromFunction(macro, chosen, activeProject));
    }

    private JLabel label(String text) {
        JLabel made = new JLabel(text);
        made.setForeground(palette.getTextSecondary());
        return made;
    }

    private JButton small(String text) {
        JButton made = new JButton(text);
        made.setFocusPainted(false);
        made.setMargin(new Insets(2, 8, 2, 8));
        return made;
    }

    // ---- behavior ----------------------------------------------------------

    private void wire() {
        DocumentListener watcher = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { regenerate(); }
            @Override public void removeUpdate(DocumentEvent e) { regenerate(); }
            @Override public void changedUpdate(DocumentEvent e) { regenerate(); }
        };
        nameField.getDocument().addDocumentListener(watcher);
        descriptionField.getDocument().addDocumentListener(watcher);
        extraFlags.getDocument().addDocumentListener(watcher);
        kindBox.addActionListener(e -> regenerate());
        for (JCheckBox box : new JCheckBox[]{threadSafe, openMp, nativeArch, generateTest, showInHelp,
                                             warnings, debug}) {
            box.addActionListener(e -> regenerate());
        }
        level.addActionListener(e -> {
            regenerate();
            fastMathWarning();
        });
        standard.addActionListener(e -> regenerate());
        fastMath.addActionListener(e -> {
            regenerate();
            fastMathWarning();
        });
        for (JCheckBox box : moduleBoxes.values()) {
            box.addActionListener(e -> regenerate());
        }
        for (JCheckBox box : new JCheckBox[]{perObject, uncertainty, monitor}) {
            box.addActionListener(e -> regenerate());
        }
        extrasAction.addActionListener(e -> takeExtrasSuggestion());
        recipeBox.addActionListener(e -> chooseRecipe());
        recipeFind.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterRecipes(); }
            @Override public void removeUpdate(DocumentEvent e) { filterRecipes(); }
            @Override public void changedUpdate(DocumentEvent e) { filterRecipes(); }
        });
        recipeFind.addActionListener(e -> takeBestMatch());
        // The arrows walk the results from the search field itself, so finding
        // a recipe never needs the mouse.
        final InputMap keys = recipeFind.getInputMap(JComponent.WHEN_FOCUSED);
        final ActionMap acts = recipeFind.getActionMap();
        keys.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0), "sphere-next-recipe");
        keys.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0), "sphere-previous-recipe");
        keys.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "sphere-clear-search");
        acts.put("sphere-next-recipe", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { moveRecipe(1); }
        });
        acts.put("sphere-previous-recipe", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { moveRecipe(-1); }
        });
        acts.put("sphere-clear-search", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { clearSearch(); }
        });
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateSuggestion(); }
            @Override public void removeUpdate(DocumentEvent e) { updateSuggestion(); }
            @Override public void changedUpdate(DocumentEvent e) { updateSuggestion(); }
        });
        suggestion.addActionListener(e -> {
            if (suggested != null) {
                start(RootPipelineStarts.Start.of(suggested));
            }
        });
        fromFormulaButton.addActionListener(e -> askFormula());
        fromCopyButton.addActionListener(e -> askCopy());
        fromMacroButton.addActionListener(e -> askMacro());
        tryButton.addActionListener(e -> callIt());
        timeButton.addActionListener(e -> timeIt());
        globalLayer.addActionListener(e -> regenerate());
        projectLayer.addActionListener(e -> regenerate());

        pipelines.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                openSelected();
            }
        });

        newButton.addActionListener(e -> blank());
        openButton.addActionListener(e -> openChosen());
        saveButton.addActionListener(e -> save(true));
        editButton.addActionListener(e -> edit());
        buildButton.addActionListener(e -> build());
        loadButton.addActionListener(e -> load());
        exitButton.addActionListener(e -> dispose());
    }

    /** Reads both layers again and refills the list. */
    private void refresh() {
        final String keep = current != null ? current.manifest.name : null;
        model.clear();
        for (Path root : RootUserPipeline.layers(activeProject)) {
            RootUserPipeline.ensureLayout(root);
            final boolean isGlobal = root.equals(RootUserPipeline.globalRoot());
            for (Path source : RootUserPipeline.sources(root)) {
                RootPipelineManifest manifest = RootPipelineManifest.fromSource(source);
                if (manifest == null) {
                    continue;
                }
                manifest.global = isGlobal;
                // The compiler decides the file name, prefix included, so it is
                // asked rather than guessed: libmyCalc.so, not myCalc.so.
                Path library = RootUserCompiler.libraryFor(
                    source, RootPipelineTemplates.libraryExtension());
                model.addElement(new Entry(manifest, source, library, isGlobal,
                                           RootUserCompiler.needsBuilding(source)));
            }
        }
        if (keep != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).manifest.name.equals(keep)) {
                    pipelines.setSelectedIndex(i);
                    return;
                }
            }
        }
    }

    /** Empties the form for a pipeline that does not exist yet. */
    private void blank() {
        filling = true;
        current = null;
        pipelines.clearSelection();
        nameField.setText("");
        namedByStart = null;
        descriptionField.setText("");
        kindBox.setSelectedItem(RootPipelineManifest.Kind.TRANSFORM);
        globalLayer.setSelected(true);
        inputModel.setRowCount(0);
        inputModel.addRow(new Object[]{"pt", "double"});
        setModules(java.util.List.of(RootPipelineModules.MATH));
        // The catalog comes back whole, without the formulas and copies a
        // previous start put at its top.
        recipeFind.setText("");
        recipeModel.removeAllElements();
        for (RootPipelineRecipes.Recipe one : RootPipelineRecipes.all()) {
            recipeModel.addElement(one);
        }
        recipeBox.setSelectedIndex(0);
        applied = recipeModel.getElementAt(0);
        setStartEnabled(true);
        recipeBox.setToolTipText("A quantity that is already written correctly. "
                                 + "It brings its own inputs and its own ROOT modules.");
        threadSafe.setSelected(true);
        openMp.setSelected(false);
        showCompiler(new RootPipelineManifest());
        showRuntime(new RootPipelineManifest());
        nativeArch.setSelected(false);
        generateTest.setSelected(false);
        showInHelp.setSelected(true);
        extraFlags.setText("");
        logArea.setText("");
        commandLine.setText("");
        filling = false;
        regenerate();
        nameField.requestFocusInWindow();
    }

    /** Fills the form from the pipeline the list has selected. */
    /** The Open button: the pipeline chosen in the list, or why there is none. */
    private void openChosen() {
        if (pipelines.getSelectedValue() == null) {
            say(model.isEmpty() ? "No pipeline yet: fill in the form and press Save."
                                : "Choose a pipeline in the list on the left.", false);
            return;
        }
        openSelected();
    }

    private void openSelected() {
        Entry entry = pipelines.getSelectedValue();
        if (entry == null) {
            return;
        }
        filling = true;
        current = entry;
        RootPipelineManifest manifest = entry.manifest;
        nameField.setText(manifest.name);
        descriptionField.setText(manifest.description);
        kindBox.setSelectedItem(manifest.kind);
        globalLayer.setSelected(entry.global);
        projectLayer.setSelected(!entry.global && projectLayer.isEnabled());
        inputModel.setRowCount(0);
        for (RootPipelineManifest.Input input : manifest.inputs) {
            inputModel.addRow(new Object[]{input.name(), input.type()});
        }
        threadSafe.setSelected(manifest.threadSafe);
        setModules(manifest.modules);
        openMp.setSelected(manifest.openMp);
        showCompiler(manifest);
        showRuntime(manifest);
        nativeArch.setSelected(manifest.nativeArch);
        showInHelp.setSelected(manifest.showInHelp);
        setStartEnabled(false);
        recipeBox.setToolTipText("The file already exists. Its body is yours from now on.");
        filling = false;
        updateSuggestion();

        final String text = read(entry.source);
        showSource(text);
        fitExtras(manifest, text);
        commandLine.setText("added flags:  " + String.join(" ", manifest.flags()) + extrasLine(manifest));
        // The Inspect tab walks this pipeline's inputs, not the last form's.
        fillScanInputs(manifest);
        say(entry.stale
            ? entry.manifest.name + " needs building: the source is newer than the library."
            : entry.manifest.name + " is up to date.",
            entry.stale ? palette.getLogWarnText() : palette.getTextSecondary());
        validateForm();
    }

    /** Rebuilds the preview and the validation after any change to the form. */
    private void regenerate() {
        if (filling) {
            return;
        }
        RootPipelineManifest manifest = collect();
        // An extra that no longer applies is unticked first, so the preview
        // never carries it; then the form is read again.
        fitExtras(manifest, null);
        manifest = collect();
        // An open pipeline shows what Save would write: the form's header and
        // declaration around the body on disk. Showing the file as it stands
        // made every field look as if it did nothing until Save was pressed.
        final String preview = current == null
            ? RootPipelineTemplates.source(manifest, chosenRecipe())
            : RootPipelineTemplates.reheader(read(current.source), manifest);
        showSource(preview);
        // What the form suggests is read in that same text, saved or not.
        fitExtras(manifest, preview);
        commandLine.setText("added flags:  " + String.join(" ", manifest.flags()) + extrasLine(manifest));
        fillScanInputs(manifest);
        validateForm();
    }

    /** Keeps the list of inputs the scan offers in step with the form. */
    private void fillScanInputs(RootPipelineManifest manifest) {
        final Object had = scanInput.getSelectedItem();
        refillingScan = true;
        try {
            scanInput.removeAllItems();
            for (RootPipelineManifest.Input input : manifest.inputs) {
                scanInput.addItem(input.name());
            }
            if (had != null) {
                scanInput.setSelectedItem(had);
            }
        } finally {
            refillingScan = false;
        }
        walkedChanged();
    }

    /** True while the list is refilled, which is not the user choosing an input. */
    private boolean refillingScan;

    /** The input the range fields were last set for. */
    private String walked;

    /**
     * A newly chosen input to walk brings the range its name suggests: eta
     * from -5 to 5, an angle from -pi to pi, a mass from 0 to 1000. A range
     * typed by hand stays until another input is chosen.
     */
    private void walkedChanged() {
        if (refillingScan) {
            return;
        }
        final Object picked = scanInput.getSelectedItem();
        if (picked == null || picked.toString().equals(walked)) {
            return;
        }
        walked = picked.toString();
        final double[] lives = RootFormula.range(walked);
        scanLow.setText(RootFormula.shortNumber(lives[0]));
        scanHigh.setText(RootFormula.shortNumber(lives[1]));
    }

    /** What the form says, as a manifest. */
    private RootPipelineManifest collect() {
        RootPipelineManifest manifest = new RootPipelineManifest();
        manifest.name = nameField.getText().trim();
        manifest.description = descriptionField.getText().trim();
        manifest.kind = (RootPipelineManifest.Kind) kindBox.getSelectedItem();
        manifest.global = globalLayer.isSelected();
        manifest.threadSafe = threadSafe.isSelected();
        manifest.openMp = openMp.isSelected();
        for (var entry : moduleBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                manifest.modules.add(entry.getKey());
            }
        }
        readCompiler(manifest);
        readRuntime(manifest);
        manifest.nativeArch = nativeArch.isSelected();
        manifest.generateTest = generateTest.isSelected();
        manifest.showInHelp = showInHelp.isSelected();
        manifest.extraFlags = extraFlags.getText().trim();
        for (int row = 0; row < inputModel.getRowCount(); row++) {
            final Object name = inputModel.getValueAt(row, 0);
            final Object type = inputModel.getValueAt(row, 1);
            if (name != null && !name.toString().isBlank()) {
                manifest.inputs.add(new RootPipelineManifest.Input(
                    name.toString().trim(),
                    type == null ? "double" : type.toString().trim()));
            }
        }
        return manifest;
    }

    /**
     * Says why the form cannot be saved yet, and grays the buttons that would
     * fail, rather than letting the compiler discover it three minutes later.
     */
    private void validateForm() {
        RootPipelineManifest manifest = collect();
        final List<String> taken = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            final Entry entry = model.get(i);
            if (current == null || !entry.source.equals(current.source)) {
                taken.add(entry.manifest.name);
            }
        }
        final String problem = manifest.validate(taken);
        saveButton.setEnabled(problem == null);
        buildButton.setEnabled(problem == null);
        editButton.setEnabled(problem == null);
        loadButton.setEnabled(current != null && Files.isRegularFile(current.library));
        if (problem != null && manifest.name.isBlank()) {
            // Not an error yet: the next step of a form just opened.
            say("Name it to save and build it; start from a recipe, a formula, a copy or a macro.", false);
        } else if (problem != null) {
            say(problem, true);
        } else if (current == null) {
            say("Ready: " + manifest.signature(), false);
        }
    }

    // ---- the buttons that do something --------------------------------------

    /** Writes the source, keeping whatever the user wrote below the mark. */
    private boolean save(boolean loud) {
        RootPipelineManifest manifest = collect();
        // Time it, the Inspect buttons and Export save first: refusing in
        // silence left them doing nothing at all, so the reason is said.
        final String problem = manifest.validate(null);
        if (problem != null) {
            say(problem, true);
            return false;
        }
        Path layer = manifest.global
            ? RootUserPipeline.globalRoot()
            : RootUserPipeline.projectRoot(activeProject);
        if (layer == null) {
            say("No project is open, so the project layer cannot be used.", true);
            return false;
        }
        RootUserPipeline.ensureLayout(layer);
        Path target = manifest.sourceIn(layer);

        try {
            // What is on disk is kept before it is replaced, so a change that
            // turns out to be wrong is one button away from being undone.
            RootPipelineHistory.keep(target);
            // A renamed pipeline takes its body along from the file it was
            // opened from; a new one starts from the recipe the form shows.
            final Path from = Files.isRegularFile(target) ? target
                : current != null && Files.isRegularFile(current.source) ? current.source : null;
            final String existing = from != null ? read(from) : null;
            final String written = (existing == null)
                ? RootPipelineTemplates.source(manifest, chosenRecipe())
                : RootPipelineTemplates.reheader(existing, manifest);
            Files.writeString(target, written, StandardCharsets.UTF_8);

            if (manifest.generateTest) {
                Path macro = layer.resolve(RootUserPipeline.SCRIPTS_DIR)
                                  .resolve(manifest.name + "_test.C");
                if (!Files.isRegularFile(macro)) {
                    Files.writeString(macro, RootPipelineTemplates.testMacro(manifest),
                                      StandardCharsets.UTF_8);
                }
            }

            showSource(written);
            refresh();
            selectByName(manifest.name);
            if (loud) {
                say("Written to " + target, false);
                AppLogger.success("Pipeline " + manifest.name + " written to " + target);
            }
            return true;
        } catch (IOException failure) {
            say("Could not write " + target + ": " + failure.getMessage(), true);
            return false;
        }
    }

    /** Hands the source to the Sphere editor, which is where it gets written. */
    private void edit() {
        if (!save(false)) {
            return;
        }
        Entry entry = current;
        if (entry == null) {
            return;
        }
        com.sphere.ui.WindowManager.showFileInEditor(entry.source.toFile());
        say("Opened " + entry.source.getFileName() + " in the editor. "
            + "Come back and press Build when you are done.", false);
    }

    /** Compiles, on a worker, with the compiler's own words kept. */
    private void build() {
        if (!save(false)) {
            return;
        }
        final Entry entry = current;
        if (entry == null) {
            return;
        }
        final RootPipelineManifest manifest = collect();

        working(true, "Compiling " + manifest.name + "...");
        logArea.setText("");
        tabs.setSelectedIndex(1);

        new SwingWorker<RootUserCompiler.Build, Void>() {
            private String rootFrom;

            @Override
            protected RootUserCompiler.Build doInBackground() {
                RootUserCompiler compiler = new RootUserCompiler(new SettingsManager(), engine);
                final RootUserCompiler.Build built = compiler.build(entry.source, manifest.flags());
                if (built != null && !built.succeeded() && compiler.rootFlags().isEmpty()) {
                    rootFrom = compiler.flagSource();
                }
                return built;
            }

            @Override
            protected void done() {
                working(false, null);
                RootUserCompiler.Build built;
                try {
                    built = get();
                } catch (Exception failure) {
                    say("The compiler could not be run: " + failure.getMessage(), true);
                    return;
                }
                report(built, entry);
                if (rootFrom != null) {
                    // "TMath.h: No such file" says what is missing, not why
                    say("The compiler was given no ROOT headers (" + rootFrom
                        + "). Start the ROOT engine, or set ROOT_DIR in settings.conf.", true);
                }
            }
        }.execute();
    }

    /** What the build said, and where its findings point. */
    private void report(RootUserCompiler.Build built, Entry entry) {
        if (built == null) {
            say("The compiler said nothing at all.", true);
            return;
        }
        commandLine.setText(built.commandLine() == null ? "" : built.commandLine());

        StringBuilder text = new StringBuilder();
        for (String line : built.lines()) {
            text.append(line).append('\n');
        }
        logArea.setText(text.toString());
        logArea.setCaretPosition(0);

        refresh();
        selectByName(entry.manifest.name);

        if (built.succeeded()) {
            say("Built " + built.output().getFileName() + ". Press Load to give it to the engine.",
                false);
            AppLogger.success("Pipeline " + entry.manifest.name + " built: " + built.output());
        } else {
            say("The compiler refused it. The findings are in the build log.", true);
            AppLogger.error("Pipeline " + entry.manifest.name + " did not build.");
        }
        showFindings(entry, built.lines());
    }

    /**
     * Sends the compiler's findings to the editor, when it has this file open.
     *
     * A line that reads "file.cpp:12:5: error: ..." becomes a marker on line 12,
     * so the user goes straight there instead of counting lines.
     */
    private void showFindings(Entry entry, List<String> lines) {
        final java.io.File file = entry.source.toFile();
        final List<com.sphere.components.editor.EditorDiagnostic> findings = new ArrayList<>();
        for (String line : lines) {
            Matcher match = FINDING.matcher(line);
            if (!match.matches()) {
                continue;
            }
            if (!match.group(1).endsWith(file.getName())) {
                continue;
            }
            findings.add(new com.sphere.components.editor.EditorDiagnostic(
                Integer.parseInt(match.group(2)),
                match.group(3) == null ? 0 : Integer.parseInt(match.group(3)),
                com.sphere.components.editor.EditorDiagnostic.severityOf(match.group(4)),
                "", match.group(5)));
        }
        if (findings.isEmpty()) {
            return;
        }
        com.sphere.ui.SphereCoderFrame frame =
            com.sphere.ui.WindowManager.showFileInEditor(file);
        if (frame != null) {
            frame.getEditor().showDiagnostics(file, findings);
        }
    }

    /** Gives the built library to the engine without restarting Sphere. */
    private void load() {
        final Entry entry = current;
        if (entry == null || !Files.isRegularFile(entry.library)) {
            say("There is nothing built to load yet.", true);
            return;
        }
        if (engine == null || !engine.isAvailable()) {
            say("The ROOT engine is not running, so nothing can be loaded into it.", true);
            return;
        }
        working(true, "Loading " + entry.library.getFileName() + " into the engine...");

        new SwingWorker<RootUserPipeline.Loaded, Void>() {
            @Override
            protected RootUserPipeline.Loaded doInBackground() {
                // The load, the manifest read back and the declaration that
                // lets the engine call it by name, as at startup.
                return RootUserPipeline.loadOne(engine, layerOf(entry), entry.library);
            }

            @Override
            protected void done() {
                working(false, null);
                // The dot beside the name says where it stands now.
                pipelines.repaint();
                RootUserPipeline.Loaded loaded;
                try {
                    loaded = get();
                } catch (Exception failure) {
                    say("The engine did not answer: " + failure.getMessage(), true);
                    return;
                }
                final String name = entry.manifest.name;
                if (!loaded.ok()) {
                    say("The engine refused the library: " + loaded.problem(), true);
                    return;
                }
                if (loaded.stale()) {
                    // "Loaded" would be a lie: the engine runs the build it read first.
                    say(name + " was rebuilt after the engine loaded it, and the engine still runs that"
                        + " first build. " + RootUserPipeline.RESTART, true);
                    AppLogger.warn("Pipeline " + name + ": the engine still runs its earlier build. "
                        + RootUserPipeline.RESTART);
                    return;
                }
                if (loaded.shadowedBy() != null) {
                    say(name + " is loaded, but calls by name still reach the " + name + " the engine read first ("
                        + loaded.shadowedBy() + "). " + RootUserPipeline.RESTART, true);
                    return;
                }
                say(name + (loaded.code() == 1 ? " was loaded already" : " is loaded")
                    + ": call it by name, or see :root pipeline list.", false);
                AppLogger.success("Pipeline " + name + " loaded into the engine.");
            }
        }.execute();
    }

    // ---- small things -------------------------------------------------------

    private void working(boolean busy, String message) {
        progress.setVisible(busy);
        progress.setIndeterminate(busy);
        for (JButton button : new JButton[]{newButton, openButton, saveButton, editButton,
                                            buildButton, loadButton, tryButton, timeButton}) {
            button.setEnabled(!busy);
        }
        if (message != null) {
            say(message, false);
        }
        if (!busy) {
            validateForm();
        }
    }

    private void say(String message, boolean wrong) {
        say(message, wrong ? palette.getError() : palette.getTextSecondary());
    }

    private void say(String message, Color color) {
        status.setText(message == null ? " " : message);
        status.setForeground(color);
    }

    private void selectByName(String name) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).manifest.name.equals(name)) {
                pipelines.setSelectedIndex(i);
                current = model.get(i);
                return;
            }
        }
    }

    /** The layer a pipeline lives in: its library sits one level inside includes/. */
    private static Path layerOf(Entry entry) {
        Path includes = entry.library.getParent();
        return includes == null ? null : includes.getParent();
    }

    /** Puts C++ in the preview and asks the highlighter to color it. */
    private void showSource(String text) {
        sourceArea.setText(text == null ? "" : text);
        sourceArea.setCaretPosition(0);
        highlighter.rehighlightAll();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /**
     * The family beside the title, so a long catalog keeps its shape -- at the
     * padding SPComboBoxUI gives every other list of Sphere, so the catalog is
     * as tall as the lists around it, and with the item under the pointer lit
     * as they light it.
     */
    private final class RecipeRenderer extends DefaultListCellRenderer {
        /** Where SPComboBoxUI's open list keeps the item under the pointer. */
        private static final String HOVER = "popupHoverIndex";
        private final javax.swing.border.Border padding = new EmptyBorder(6, 12, 6, 12);

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            setBorder(padding);
            if (!selected && index >= 0 && list.getClientProperty(HOVER) instanceof Integer under
                    && under == index) {
                setBackground(palette.getButtonHover());
            }
            if (!(value instanceof RootPipelineRecipes.Recipe recipe)) {
                return this;
            }
            final Color faint = selected ? palette.getTextWhite() : palette.getTextSecondary();
            setText("<html>" + recipe.title()
                    + "<font color='" + hex(faint) + "'>   &middot; "
                    + recipe.family().label + "</font></html>");
            setToolTipText(recipe.about());
            return this;
        }
    }

    /**
     * Where a pipeline stands, as a color and a few words: what its next step
     * is. The last case is the one a session would otherwise never see -- a
     * rebuild the engine did not take, still running the build before it.
     */
    private String[] state(Entry entry) {
        final boolean built = Files.isRegularFile(entry.library);
        final RootUserPipeline.Pipeline loaded = RootUserPipeline.pipeline(entry.manifest.name);
        final boolean ours = loaded != null && loaded.library() != null
            && loaded.library().toAbsolutePath().normalize()
                     .equals(entry.library.toAbsolutePath().normalize());
        if (ours && RootUserPipeline.stale(entry.library)) {
            return new String[]{hex(palette.getError()), "an older build runs in the engine"};
        }
        if (ours) {
            return new String[]{hex(palette.getSuccess()), "in the engine"};
        }
        if (!built) {
            return new String[]{hex(palette.getTextSecondary()), "not built yet"};
        }
        if (entry.stale) {
            return new String[]{hex(palette.getLogWarnText()), "changed since its build"};
        }
        return new String[]{hex(palette.getAccent()), "built, not loaded"};
    }

    /** Name, kind and layer, then where the pipeline stands, as a colored dot and words. */
    private final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (!(value instanceof Entry entry)) {
                return this;
            }
            // The colors come from the palette rather than from the markup, so
            // the list follows the theme the way the rest of the window does.
            final Color name = selected ? palette.getTextWhite() : palette.getTextPrimary();
            final Color faint = selected ? palette.getTextWhite() : palette.getTextSecondary();
            final String[] state = state(entry);
            // &nbsp; rather than spaces: Swing's HTML drops the ones after a tag.
            setText("<html><b><font color='" + hex(name) + "'>" + entry.manifest.name
                    + "</font></b><font color='" + hex(faint) + "'>&nbsp;&nbsp;"
                    + entry.manifest.kind.token
                    + "&nbsp;&middot;&nbsp;" + (entry.global ? "global" : "project")
                    + "</font><br><font color='" + state[0] + "'>&#9679;</font>"
                    + "<font color='" + hex(faint) + "'>&nbsp;" + state[1] + "</font></html>");
            setToolTipText(entry.manifest.signature()
                + (entry.manifest.description == null || entry.manifest.description.isBlank()
                   ? "" : "  --  " + entry.manifest.description));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0,
                    selected ? palette.getAccent() : palette.getTerminalBackground()),
                new EmptyBorder(4, 8, 4, 8)));
            setBackground(selected ? palette.getTerminalSelection()
                                   : palette.getTerminalBackground());
            setToolTipText(entry.stale
                ? "The source is newer than the library. Build it."
                : entry.manifest.signature());
            return this;
        }
    }

    /** A palette color as the markup writes it. */
    private static String hex(Color color) {
        return String.format("#%02x%02x%02x",
            color.getRed(), color.getGreen(), color.getBlue());
    }
}
