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

    private final JCheckBox rootHeaders = new JCheckBox("ROOT headers", true);
    private final JCheckBox threadSafe = new JCheckBox("stateless", true);
    private final JCheckBox useRVec = new JCheckBox("RVec");
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
        setMinimumSize(new Dimension(1020, 660));
        setSize(1120, 720);
        setLocationRelativeTo(null);

        add(header(), BorderLayout.NORTH);
        add(left(), BorderLayout.WEST);
        add(centre(), BorderLayout.CENTER);
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

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(palette.getTerminalBackground());
        side.add(caption, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    private JComponent centre() {
        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.setBackground(palette.getTerminalBackground());
        middle.setBorder(new EmptyBorder(10, 12, 6, 12));
        middle.add(form(), BorderLayout.NORTH);

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

        JPanel below = new JPanel(new BorderLayout(0, 4));
        below.setBackground(palette.getTerminalBackground());
        below.add(tabs, BorderLayout.CENTER);

        commandLine.setEditable(false);
        commandLine.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        commandLine.setForeground(palette.getTextSecondary());
        commandLine.setBackground(palette.getTerminalBackground());
        commandLine.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
            new EmptyBorder(5, 4, 5, 4)));
        commandLine.setToolTipText("The exact command the compiler will be given.");
        below.add(commandLine, BorderLayout.SOUTH);

        middle.add(below, BorderLayout.CENTER);
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

        JPanel options = new JPanel(new GridLayout(2, 4, 8, 2));
        options.setBackground(palette.getTerminalBackground());
        for (JCheckBox box : new JCheckBox[]{rootHeaders, threadSafe, useRVec, openMp,
                                             optimize, nativeArch, generateTest, showInHelp}) {
            box.setBackground(palette.getTerminalBackground());
            box.setForeground(palette.getTextPrimary());
            options.add(box);
        }
        rootHeaders.setToolTipText("Include TMath.h, and the tree headers a histogram needs.");
        threadSafe.setToolTipText("Keeps no state between calls, so :root mt on may run it "
                                  + "on several entries at once.");
        useRVec.setToolTipText("Include ROOT/RVec.hxx for per-event collections.");
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

        JPanel side = new JPanel(new GridLayout(2, 1, 0, 4));
        side.setBackground(palette.getTerminalBackground());
        side.add(add);
        side.add(remove);

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
        for (JCheckBox box : new JCheckBox[]{rootHeaders, threadSafe, useRVec, openMp,
                                             optimize, nativeArch, generateTest, showInHelp}) {
            box.addActionListener(e -> regenerate());
        }
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
        rootHeaders.setSelected(true);
        threadSafe.setSelected(true);
        useRVec.setSelected(false);
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
        useRVec.setSelected(manifest.useRVec);
        openMp.setSelected(manifest.openMp);
        optimize.setSelected(manifest.optimize);
        nativeArch.setSelected(manifest.nativeArch);
        showInHelp.setSelected(manifest.showInHelp);
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
            showSource(RootPipelineTemplates.source(manifest));
        }
        commandLine.setText("added flags:  " + String.join(" ", manifest.flags()));
        validateForm();
    }

    /** What the form says, as a manifest. */
    private RootPipelineManifest collect() {
        RootPipelineManifest manifest = new RootPipelineManifest();
        manifest.name = nameField.getText().trim();
        manifest.description = descriptionField.getText().trim();
        manifest.kind = (RootPipelineManifest.Kind) kindBox.getSelectedItem();
        manifest.global = globalLayer.isSelected();
        manifest.rootHeaders = rootHeaders.isSelected();
        manifest.threadSafe = threadSafe.isSelected();
        manifest.useRVec = useRVec.isSelected();
        manifest.openMp = openMp.isSelected();
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
                                            buildButton, loadButton}) {
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
