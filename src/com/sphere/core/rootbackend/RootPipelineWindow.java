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

    private final JComboBox<RootPipelineRecipes.Recipe> recipeBox =
        new JComboBox<>(RootPipelineRecipes.all().toArray(new RootPipelineRecipes.Recipe[0]));

    private final JCheckBox threadSafe = new JCheckBox("stateless", true);
    private final JCheckBox openMp = new JCheckBox("OpenMP");
    private final JCheckBox optimize = new JCheckBox("-O3", true);
    private final JCheckBox nativeArch = new JCheckBox("-march=native");
    private final JCheckBox generateTest = new JCheckBox("test macro");
    private final JCheckBox showInHelp = new JCheckBox("show in :help", true);
    private final JTextField extraFlags = new JTextField(20);

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
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(engine, activeProject, name));
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
        super("ROOT pipeline builder");
        this.engine = engine;
        this.activeProject = activeProject;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(1040, 700));
        setSize(1180, 860);
        setLocationRelativeTo(null);

        add(header(), BorderLayout.NORTH);
        add(left(), BorderLayout.WEST);
        add(middlePart(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        wire();
        dress();
        refresh();
        blank();
    }

    // ---- the pieces ---------------------------------------------------------

    private JComponent header() {
        JPanel band = new JPanel(new GridLayout(2, 1, 0, 3));
        band.setBackground(palette.getTerminalBackground());
        band.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBorder()),
            new EmptyBorder(11, 16, 11, 16)));

        JLabel title = new JLabel("Your own ROOT pipeline");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(palette.getTextPrimary());

        JLabel hint = new JLabel("Compiled into includes/, loaded when Sphere starts, "
                                 + "and callable by name from :root afterwards.");
        hint.setForeground(palette.getTextSecondary());

        band.add(title);
        band.add(hint);
        return band;
    }

    /**
     * Paints what the look and feel does not.
     *
     * Sphere themes what it can through UIManager, but a table's selection, a
     * tab strip's own background and a viewport are not among those keys, so
     * they stay at whatever the look and feel chose. They are set here instead,
     * from the same palette, so the window is one piece.
     */
    private void dress() {
        for (JTextField field : new JTextField[]{nameField, descriptionField, extraFlags}) {
            field.setBackground(palette.getBackgroundSurface());
            field.setForeground(palette.getTextPrimary());
            field.setCaretColor(palette.getAccent());
            field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder()),
                new EmptyBorder(3, 6, 3, 6)));
        }

        kindBox.setBackground(palette.getBackgroundSurface());
        kindBox.setForeground(palette.getTextPrimary());

        inputTable.setBackground(palette.getTerminalBackground());
        inputTable.setForeground(palette.getTextPrimary());
        inputTable.setGridColor(palette.getBorder());
        inputTable.setSelectionBackground(palette.getTerminalSelection());
        inputTable.setSelectionForeground(palette.getTextWhite());
        inputTable.getTableHeader().setBackground(palette.getBackgroundSurface());
        inputTable.getTableHeader().setForeground(palette.getTextSecondary());
        inputTable.getTableHeader().setReorderingAllowed(false);

        tabs.setBackground(palette.getTerminalBackground());
        tabs.setForeground(palette.getTextPrimary());

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

    /** Every component under one, however deep. */
    private static List<Component> all(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component one : root.getComponents()) {
            found.add(one);
            if (one instanceof Container deeper) {
                found.addAll(all(deeper));
            }
        }
        return found;
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

        // Read only, but colored by the same highlighter the editor uses, so
        // what the form generates reads the way the file will in the editor.
        sourceArea.setEditable(false);
        sourceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sourceArea.applyTheme();
        sourceArea.setHighlightCurrentLine(false);
        JScrollPane sourceScroll = new JScrollPane(sourceArea);
        sourceScroll.getViewport().setBackground(EditorTheme.background());
        TextLineNumber numbers = new TextLineNumber(sourceArea);
        sourceScroll.setRowHeaderView(numbers);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(palette.getTerminalBackground());
        logArea.setForeground(palette.getTerminalForeground());

        tabs.addTab("Source", sourceScroll);
        tabs.addTab("Build log", new JScrollPane(logArea));
        tabs.addTab("Try it", tryPanel());
        tabs.addTab("Inspect", inspectPanel());
        tabs.addTab("Chain", chainPanel());
        // Choosing the Chain tab gives it the room it needs, and leaving it
        // gives the form its own back.
        tabs.addChangeListener(e -> roomForTabs(tabs.getSelectedIndex() >= 3));

        JPanel below = new JPanel(new BorderLayout(0, 4));
        below.setBackground(palette.getTerminalBackground());
        below.add(tabs, BorderLayout.CENTER);

        // The form and the tabs share the height rather than the form taking
        // what it wants: assembling a chain needs room, and reading a form does
        // not, so which of the two gets the space is the user's to decide.
        // The form scrolls, so giving its height to the tabs cuts nothing off:
        // what no longer fits is still reachable rather than simply gone.
        JScrollPane formScroll = new JScrollPane(theForm,
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
        between.setDividerSize(7);
        between.setDividerLocation(theForm.getPreferredSize().height + 6);
        formPreferredHeight = theForm.getPreferredSize().height;

        commandLine.setEditable(false);
        commandLine.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
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

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(label("Name"), c);
        c.gridx = 1; c.weightx = 0.4;
        grid.add(nameField, c);
        c.gridx = 2; c.weightx = 0;
        grid.add(label("Kind"), c);
        c.gridx = 3; c.weightx = 0.6;
        grid.add(kindBox, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(label("Start from"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        recipeBox.setToolTipText("A quantity that is already written correctly. "
                                 + "It brings its own inputs and its own ROOT modules.");
        recipeBox.setRenderer(new RecipeRenderer());
        recipeBox.setMaximumRowCount(20);
        grid.add(recipeBox, c);
        c.gridwidth = 1;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(label("Description"), c);
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
        grid.add(label("Lives"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(layerRow, c);
        c.gridwidth = 1;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        grid.add(label("Inputs"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(inputs(), c);
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        JPanel parts = new JPanel(new GridLayout(3, 4, 8, 2));
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
        grid.add(label("ROOT"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(parts, c);
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        JPanel options = new JPanel(new GridLayout(2, 3, 8, 2));
        options.setBackground(palette.getTerminalBackground());
        for (JCheckBox box : new JCheckBox[]{threadSafe, openMp, optimize,
                                             nativeArch, generateTest, showInHelp}) {
            box.setBackground(palette.getTerminalBackground());
            box.setForeground(palette.getTextPrimary());
            options.add(box);
        }
        threadSafe.setToolTipText("Keeps no state between calls, so :root mt on may run it "
                                  + "on several entries at once.");
        openMp.setToolTipText("Build with -fopenmp.");
        optimize.setToolTipText("Build with -O3 rather than -O0.");
        nativeArch.setToolTipText("Build with -march=native. The library then runs only "
                                  + "on this kind of processor.");
        generateTest.setToolTipText("Write a macro in user_scripts/ that calls the entry point.");
        showInHelp.setToolTipText("List the pipeline in :help root once it is loaded.");

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        grid.add(label("Build"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(options, c);
        c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        grid.add(label("Extra flags"), c);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        grid.add(extraFlags, c);
        c.gridwidth = 1;

        return grid;
    }

    private JComponent inputs() {
        inputTable.setRowHeight(21);
        inputTable.setBackground(palette.getTerminalBackground());
        inputTable.setForeground(palette.getTextPrimary());
        inputTable.setGridColor(palette.getBorder());
        inputTable.getColumnModel().getColumn(1)
                  .setCellEditor(new DefaultCellEditor(
                      new JComboBox<>(RootPipelineManifest.TYPES)));

        JScrollPane scroll = new JScrollPane(inputTable);
        // Five rows fit without pushing the rest of the form off the panel.
        scroll.setPreferredSize(new Dimension(360, 5 * 21 + 24));

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
        tryAnswer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
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
        stageList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane stageScroll = new JScrollPane(stageList);
        stageScroll.setPreferredSize(new Dimension(360, 150));

        chainPreview.setEditable(false);
        chainPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
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

        JPanel top = new JPanel(new GridLayout(2, 1, 0, 6));
        top.setBackground(palette.getTerminalBackground());
        top.add(source);
        top.add(adding);

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

    /** Gives the tabs most of the height, or hands it back to the form. */
    private void roomForTabs(boolean wide) {
        if (divider == null) {
            return;
        }
        divider.setDividerLocation(wide ? 120 : formPreferredHeight + 6);
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
        watchScroll.setPreferredSize(new Dimension(360, 150));

        inspectSaid.setEditable(false);
        inspectSaid.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        inspectSaid.setBackground(palette.getTerminalBackground());
        inspectSaid.setForeground(palette.getTerminalForeground());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            watchScroll, new JScrollPane(inspectSaid));
        split.setResizeWeight(0.4);
        split.setBorder(null);
        split.setBackground(palette.getTerminalBackground());

        JPanel rows = new JPanel(new GridLayout(2, 1, 0, 6));
        rows.setBackground(palette.getTerminalBackground());
        rows.add(top);
        rows.add(scanRow);

        watch.addActionListener(e -> inspect("watch"));
        health.addActionListener(e -> inspect("health"));
        scan.addActionListener(e -> inspect("scan"));

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

            @Override
            protected String doInBackground() {
                RootUserCompiler compiler = new RootUserCompiler(new SettingsManager(), engine);
                switch (how) {
                    case "watch" -> {
                        values = RootPipelineProbe.watch(
                            compiler, manifest, entry.source, arguments);
                        return values.isEmpty()
                            ? "Nothing was recorded. Name a value with SPHERE_WATCH(x) "
                              + "inside the body, then look again."
                            : values.size() + " value"
                              + (values.size() == 1 ? "" : "s") + " recorded.";
                    }
                    case "health" -> {
                        RootPipelineProbe.Health found = RootPipelineProbe.health(
                            compiler, manifest, entry.source, 20000, low, high);
                        return "Sweeping every input over [" + low + ", " + high + "]\n\n"
                             + found.summary() + "\n\n"
                             + (found.isClean()
                                ? "Every call answered a number."
                                : "Some calls did not answer a number. "
                                  + "Walk one input to find where.");
                    }
                    default -> {
                        RootPipelineProbe.Scan found = RootPipelineProbe.scan(
                            compiler, manifest, entry.source, which, low, high, 61, held);
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
                if ("watch".equals(how)) {
                    watchModel.setRowCount(0);
                    for (RootPipelineProbe.Watched one : values) {
                        watchModel.addRow(new Object[]{one.name(), one.value()});
                    }
                    inspectSaid.setText(said);
                } else {
                    inspectSaid.setText(said);
                }
                inspectSaid.setCaretPosition(0);
                say(said.lines().findFirst().orElse("Done."), false);
            }
        }.execute();
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
        say("Replaying " + commands.size() + " commands...", false);
        AppLogger.info("Chain " + chain.name + ": replaying " + commands.size() + " commands.");
        for (String one : commands) {
            AppLogger.raw("  " + one);
            com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
            if (router == null) {
                say("No command router is available to replay it.", true);
                return;
            }
            router.processInput(one);
        }
        say("The chain has been replayed. :root rdf report " + chain.frame()
            + " says what each cut kept.", false);
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
        if (recipe == null) {
            return;
        }
        filling = true;
        kindBox.setSelectedItem(recipe.kind());
        inputModel.setRowCount(0);
        for (RootPipelineManifest.Input input : recipe.inputs()) {
            inputModel.addRow(new Object[]{input.name(), input.type()});
        }
        setModules(recipe.modules());
        if (descriptionField.getText().isBlank() && recipe.body() != null) {
            descriptionField.setText(recipe.about());
        }
        filling = false;
        regenerate();
        say(recipe.body() == null
            ? "Blank body. Fill it in yourself."
            : recipe.title() + " is ready. Change the names if your branches differ.", false);
    }

    /** The recipe the form starts from, or null once a pipeline exists on disk. */
    private RootPipelineRecipes.Recipe chosenRecipe() {
        return current != null ? null
            : (RootPipelineRecipes.Recipe) recipeBox.getSelectedItem();
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
        for (JCheckBox box : new JCheckBox[]{threadSafe, openMp,
                                             optimize, nativeArch, generateTest, showInHelp}) {
            box.addActionListener(e -> regenerate());
        }
        for (JCheckBox box : moduleBoxes.values()) {
            box.addActionListener(e -> regenerate());
        }
        recipeBox.addActionListener(e -> chooseRecipe());
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
        openButton.addActionListener(e -> openSelected());
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
        descriptionField.setText("");
        kindBox.setSelectedItem(RootPipelineManifest.Kind.TRANSFORM);
        globalLayer.setSelected(true);
        inputModel.setRowCount(0);
        inputModel.addRow(new Object[]{"pt", "double"});
        setModules(java.util.List.of(RootPipelineModules.MATH));
        recipeBox.setSelectedIndex(0);
        recipeBox.setEnabled(true);
        recipeBox.setToolTipText("A quantity that is already written correctly. "
                                 + "It brings its own inputs and its own ROOT modules.");
        threadSafe.setSelected(true);
        openMp.setSelected(false);
        optimize.setSelected(true);
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
        optimize.setSelected(manifest.optimize);
        nativeArch.setSelected(manifest.nativeArch);
        showInHelp.setSelected(manifest.showInHelp);
        recipeBox.setEnabled(false);
        recipeBox.setToolTipText("The file already exists. Its body is yours from now on.");
        filling = false;

        showSource(read(entry.source));
        commandLine.setText("added flags:  " + String.join(" ", manifest.flags()));
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
        if (current == null) {
            showSource(RootPipelineTemplates.source(manifest, chosenRecipe()));
        }
        commandLine.setText("added flags:  " + String.join(" ", manifest.flags()));
        fillScanInputs(manifest);
        validateForm();
    }

    /** Keeps the list of inputs the scan offers in step with the form. */
    private void fillScanInputs(RootPipelineManifest manifest) {
        final Object had = scanInput.getSelectedItem();
        scanInput.removeAllItems();
        for (RootPipelineManifest.Input input : manifest.inputs) {
            scanInput.addItem(input.name());
        }
        if (had != null) {
            scanInput.setSelectedItem(had);
        }
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
        manifest.optimize = optimize.isSelected();
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
     * Says why the form cannot be saved yet, and greys the buttons that would
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
        if (problem != null) {
            say(problem, true);
        } else if (current == null) {
            say("Ready: " + manifest.signature(), false);
        }
    }

    // ---- the buttons that do something --------------------------------------

    /** Writes the source, keeping whatever the user wrote below the mark. */
    private boolean save(boolean loud) {
        RootPipelineManifest manifest = collect();
        if (manifest.validate(null) != null) {
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
            final String existing = Files.isRegularFile(target) ? read(target) : null;
            final String written = (existing == null)
                ? RootPipelineTemplates.source(manifest)
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
            @Override
            protected RootUserCompiler.Build doInBackground() {
                RootUserCompiler compiler = new RootUserCompiler(new SettingsManager(), engine);
                return compiler.build(entry.source, manifest.flags());
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
        com.sphere.ui.QuickCodeEditorFrame frame =
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

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                final String answer = engine.executeClingAwait(
                    "gSystem->Load(\"" + entry.library.toAbsolutePath().toString()
                        .replace("\\", "/") + "\")", 20_000L);
                if (answer != null && !answer.startsWith("ERROR")) {
                    // Reading the manifest back is what makes it a pipeline
                    // rather than one more anonymous library in the process.
                    RootUserPipeline.refresh(engine, layerOf(entry), entry.library);
                }
                return answer;
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
                    say("The engine refused the library: "
                        + (answer == null ? "no answer" : answer), true);
                    return;
                }
                say(entry.manifest.name + " is loaded. Try :root pipeline list.", false);
                AppLogger.success("Pipeline " + entry.manifest.name + " loaded into the engine.");
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

    /** The family beside the title, so a long catalogue keeps its shape. */
    private final class RecipeRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
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

    /** Name, kind and layer on one line, with the state shown beside the name. */
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
            setText("<html><b><font color='" + hex(name) + "'>" + entry.manifest.name
                    + "</font></b><font color='" + hex(faint) + "'>  "
                    + entry.manifest.kind.token
                    + "  &middot;  " + (entry.global ? "global" : "project")
                    + "</font>"
                    + (entry.stale
                       ? "<font color='" + hex(palette.getLogWarnText()) + "'>  &bull;</font>"
                       : "")
                    + "</html>");
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
