package com.sphere.components.editor.debug;

import com.sphere.components.editor.BreakpointModel;
import com.sphere.components.editor.DebugAdapter;
import com.sphere.components.editor.EditorIcons;
import com.sphere.components.editor.EditorState;
import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.EditorWidgets;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.SettingsManager;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Debug controls and state for the editor. Along the top: continue (or pause a
 * running program, or start a session), the three steps, run to the cursor,
 * restart, stop, when to stop for an exception, and why the program stopped.
 * Below: the variables of the chosen frame, those changed since the last stop
 * standing out; the expressions watched, evaluated again at every stop; the call
 * stack, where a click shows another frame; and the debug console, with the
 * program's output, the logpoints' lines and a field that evaluates in the frame.
 */
public final class DebugPanel extends JPanel implements DebugAdapter.Listener {

    /** What the panel needs from the editor around it. */
    public interface Host {
        /** File of the active tab, or null when none is open. */
        File activeFile();

        /** Breakpoints of the active tab. */
        BreakpointModel activeBreakpoints();

        /** Marks the line the session stopped on, or -1 to clear it. */
        void showExecutionLine(File file, int line);

        /** Shows the values of a frame's variables past the end of its lines, up to the stopped line. */
        default void showInlineValues(File file, int line, Map<String, String> variables) { }

        /** Takes the inline values away (the program runs again, or ended). */
        default void clearInlineValues() { }

        /** The 1-based line of the caret in an editor of the file, or -1 (Run to Cursor). */
        default int cursorLine(File file) {
            return -1;
        }

        /** Shows the line of another frame of the stack, opening its file if needed. */
        default void revealFrame(File file, int line) {
            showExecutionLine(file, line);
        }

        /** The session started, paused, ran or ended: the editor's keys and menus follow. */
        default void sessionChanged(DebugAdapter.State state) { }
    }

    private static final String WATCHES = "debug.watches";
    private static final String EXCEPTIONS = "debug.exceptions";
    private static final char SEPARATOR = '\u001f';
    private static final int CONSOLE_LIMIT = 400_000;

    private final Host host;
    private final SettingsManager settings;

    private final EditorWidgets.FlatButton continueButton = new EditorWidgets.FlatButton((Icon) null, "");
    private final EditorWidgets.FlatButton overButton =
        new EditorWidgets.FlatButton(stepOverIcon(DebugPanel::stepColor), "Step Over (F10)");
    private final EditorWidgets.FlatButton intoButton =
        new EditorWidgets.FlatButton(stepIntoIcon(DebugPanel::stepColor), "Step Into (F11)");
    private final EditorWidgets.FlatButton outButton =
        new EditorWidgets.FlatButton(stepOutIcon(DebugPanel::stepColor), "Step Out (Shift+F11)");
    private final EditorWidgets.FlatButton cursorButton =
        new EditorWidgets.FlatButton(runToCursorIcon(DebugPanel::stepColor), "Run to Cursor (Ctrl+F10)");
    private final EditorWidgets.FlatButton restartButton =
        new EditorWidgets.FlatButton(EditorIcons.refresh(16, DebugPanel::goColor), "Restart (Ctrl+Shift+F5)");
    private final EditorWidgets.FlatButton stopButton =
        new EditorWidgets.FlatButton(stopIcon(EditorTheme::breakpoint), "Stop (Shift+F5)");
    private final JComboBox<DebugAdapter.ExceptionMode> exceptionBox =
        new JComboBox<>(DebugAdapter.ExceptionMode.values());
    private final JLabel status = new JLabel();

    private final ValueModel variables = new ValueModel();
    private final ValueModel watches = new ValueModel();
    private final StackModel stackModel = new StackModel();
    private final JTable variableTable = table(variables);
    private final JTable watchTable = table(watches);
    private final JTable stackTable = new JTable(stackModel);
    private final JTextPane console = new JTextPane();
    private final EditorWidgets.FlatField evalField =
        new EditorWidgets.FlatField("Evaluate in the paused frame (Up/Down: history)");
    private final EditorWidgets.FlatField watchField = new EditorWidgets.FlatField("Add an expression to watch");

    private DebugAdapter adapter;
    private File sessionFile;
    private BreakpointModel sessionBreakpoints;
    /** The name the debugged program's variables appear under in the Variables tab. */
    private String debugSource;
    private List<DebugAdapter.StackFrame> stack = List.of();
    private int selectedFrame;
    private boolean fillingStack;
    private String reason = "";
    private boolean reasonProblem;
    /** The values of the previous stop, by frame and name: what changed since stands out. */
    private final Map<String, String> lastValues = new HashMap<>();
    private final List<String> watchList = new ArrayList<>();
    private int watchGeneration;
    private final List<String> history = new ArrayList<>();
    private int historyIndex;

    public DebugPanel(Host host, SettingsManager settings) {
        this.host = host;
        this.settings = settings;
        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());

        loadPreferences();
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        updateButtons(DebugAdapter.State.IDLE);
        setStatus("Not debugging: F5 starts a session on the active file", Tone.MUTED, null);
    }

    // ---- Building ------------------------------------------------------------

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(EditorTheme.background());
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        buttons.setOpaque(false);
        for (EditorWidgets.FlatButton b : new EditorWidgets.FlatButton[] {
                continueButton, overButton, intoButton, outButton, cursorButton, restartButton, stopButton }) {
            b.setFocusable(false);
            buttons.add(b);
        }
        continueButton.addActionListener(e -> continueOrPause());
        overButton.addActionListener(e -> stepOver());
        intoButton.addActionListener(e -> stepInto());
        outButton.addActionListener(e -> stepOut());
        cursorButton.addActionListener(e -> runToCursor());
        restartButton.addActionListener(e -> restart());
        stopButton.addActionListener(e -> stop());

        status.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        status.setIconTextGap(6);

        exceptionBox.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        exceptionBox.setFocusable(false);
        exceptionBox.setToolTipText("When the program stops for an exception");
        exceptionBox.addActionListener(e -> {
            DebugAdapter.ExceptionMode mode = exceptionMode();
            EditorState.setText(EXCEPTIONS, mode.name());
            if (isSessionLive()) {
                adapter.setExceptionMode(mode);
            }
        });
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        east.setOpaque(false);
        JLabel breakOn = new JLabel("Break on");
        breakOn.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        breakOn.setForeground(EditorTheme.textMuted());
        east.add(breakOn);
        east.add(exceptionBox);

        bar.add(buttons, BorderLayout.WEST);
        bar.add(status, BorderLayout.CENTER);
        bar.add(east, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildBody() {
        // Variables
        variableTable.addMouseListener(popupOn(variableTable, row -> {
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Add to Watch", () -> addWatch(variables.name(row))));
            menu.add(item("Copy Value", () -> copy(variables.value(row))));
            menu.add(item("Copy Name and Value", () -> copy(variables.name(row) + " = " + variables.value(row))));
            return menu;
        }));

        // Watch
        watchTable.addMouseListener(popupOn(watchTable, row -> {
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Remove Expression", () -> removeWatch(row)));
            menu.add(item("Copy Value", () -> copy(watches.value(row))));
            menu.add(item("Remove All Expressions", this::clearWatches));
            return menu;
        }));
        bindKey(watchTable, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove-watch", () -> {
            int row = watchTable.getSelectedRow();
            if (row >= 0) {
                removeWatch(row);
            }
        });
        watchField.addActionListener(e -> {
            addWatch(watchField.getText());
            watchField.setText("");
        });
        JPanel watchPane = new JPanel(new BorderLayout(0, 3));
        watchPane.setOpaque(false);
        watchPane.add(scroll(watchTable), BorderLayout.CENTER);
        watchPane.add(pad(watchField), BorderLayout.SOUTH);

        // Call stack
        stackTable.setFillsViewportHeight(true);
        stackTable.setShowGrid(false);
        stackTable.setRowHeight(20);
        stackTable.setTableHeader(null);
        stackTable.setBackground(EditorTheme.background());
        stackTable.setSelectionBackground(EditorTheme.selection());
        stackTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stackTable.setDefaultRenderer(Object.class, new StackRenderer(() -> selectedFrame));
        stackTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || fillingStack) {
                return;
            }
            int row = stackTable.getSelectedRow();
            if (row >= 0 && row != selectedFrame && adapter != null
                    && adapter.state() == DebugAdapter.State.PAUSED) {
                selectedFrame = row;
                adapter.selectFrame(row);
            }
        });

        // Console
        console.setEditable(false);
        console.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        console.setBackground(EditorTheme.background());
        console.setForeground(EditorTheme.foreground());
        console.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        evalField.addActionListener(e -> evaluateTyped());
        bindKey(evalField, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "history-back", () -> walkHistory(-1));
        bindKey(evalField, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "history-forward", () -> walkHistory(1));
        JPanel consolePane = new JPanel(new BorderLayout(0, 3));
        consolePane.setOpaque(false);
        consolePane.add(scroll(console), BorderLayout.CENTER);
        consolePane.add(pad(evalField), BorderLayout.SOUTH);

        JComponent vars = titled("Variables", scroll(variableTable));
        JComponent watch = titled("Watch", watchPane);
        JComponent stackPane = titled("Call Stack", scroll(stackTable));
        JComponent consoleSection = titled("Debug Console", consolePane);
        // Preferred widths decide where the dividers land on the first layout;
        // without them the console column was squeezed against the window edge.
        vars.setPreferredSize(new Dimension(360, 120));
        watch.setPreferredSize(new Dimension(360, 90));
        stackPane.setPreferredSize(new Dimension(270, 210));
        consoleSection.setPreferredSize(new Dimension(380, 210));

        JSplitPane left = new EditorWidgets.FlatSplitPane(JSplitPane.VERTICAL_SPLIT, vars, watch);
        left.setResizeWeight(0.6);
        left.setDividerSize(4);
        left.setBorder(null);
        JSplitPane right = new EditorWidgets.FlatSplitPane(JSplitPane.HORIZONTAL_SPLIT, stackPane, consoleSection);
        right.setResizeWeight(0.4);
        right.setDividerSize(4);
        right.setBorder(null);
        JSplitPane all = new EditorWidgets.FlatSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        all.setResizeWeight(0.38);
        all.setDividerSize(4);
        all.setBorder(null);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(EditorTheme.background());
        body.add(all, BorderLayout.CENTER);
        body.setPreferredSize(new Dimension(100, 230));
        rebuildWatchRows();
        return body;
    }

    private JComponent titled(String title, JComponent content) {
        JLabel label = new JLabel(title.toUpperCase());
        label.setForeground(EditorTheme.textMuted());
        label.setFont(FontLoader.getGlobalFont(Font.BOLD, 10));
        label.setBorder(BorderFactory.createEmptyBorder(4, 8, 3, 4));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.background());
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EditorTheme.chromeBorder()));
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JScrollPane scroll(JComponent c) {
        JScrollPane pane = new JScrollPane(c);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setViewportBorder(null);
        pane.getViewport().setBackground(EditorTheme.background());
        c.setBorder(BorderFactory.createEmptyBorder());
        return pane;
    }

    private static JComponent pad(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private JTable table(ValueModel model) {
        JTable table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) {
                    return null;
                }
                String value = model.value(row);
                return value.length() < 40 ? null
                    : "<html><pre style='margin:0'>" + escape(wrap(value, 100)) + "</pre></html>";
            }
        };
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(20);
        table.setTableHeader(null);
        table.setBackground(EditorTheme.background());
        table.setSelectionBackground(EditorTheme.selection());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new ValueRenderer(model));
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        return table;
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private static MouseAdapter popupOn(JTable table, java.util.function.IntFunction<JPopupMenu> menu) {
        return new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                    menu.apply(row).show(table, e.getX(), e.getY());
                }
            }
        };
    }

    private static void bindKey(JComponent c, KeyStroke key, String name, Runnable action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    // ---- Session ---------------------------------------------------------------

    /** Starts a session on the active file, with its breakpoints. */
    public void startSession() {
        File file = host.activeFile();
        if (file == null) {
            append("Open a source file before starting a session.\n", Tone.ERROR);
            return;
        }
        start(file, host.activeBreakpoints());
    }

    private void start(File file, BreakpointModel breakpoints) {
        if (isSessionLive()) {
            return;
        }
        String name = file.getName().toLowerCase();
        // The adapter follows the file, so one panel drives gdb for a .cpp and the
        // Python driver for a .py without the user choosing.
        if (name.endsWith(".py") || name.endsWith(".pyw")) {
            adapter = new PdbAdapter(settings);
            debugSource = "python";
        } else if (name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cc")
                   || name.endsWith(".cxx") || name.endsWith(".c++")) {
            adapter = new GdbAdapter(settings);
            debugSource = "cpp";
        } else if (GdbAdapter.isFortran(name)) {
            // gdb reads Fortran as it reads C: the debug tables carry the names,
            // and it is the only way to see a compiled run before it ends.
            adapter = new GdbAdapter(settings);
            debugSource = "fortran";
        } else {
            append("No debugger for " + file.getName()
                   + ". C, C++, Fortran and Python are supported.\n", Tone.ERROR);
            return;
        }
        sessionFile = file;
        sessionBreakpoints = breakpoints == null ? new BreakpointModel() : breakpoints;
        variables.set(List.of());
        stackModel.set(List.of());
        stack = List.of();
        lastValues.clear();
        console.setText("");
        append("Debugging " + file.getName() + "\n", Tone.MUTED);
        adapter.setExceptionMode(exceptionMode());
        // Starting a session compiles the source first, which is a g++ run of
        // unbounded length. This method is a button handler, so doing it here
        // froze the whole window for the length of the compile.
        final DebugAdapter starting = adapter;
        final BreakpointModel model = sessionBreakpoints;
        Thread worker = new Thread(
            () -> starting.start(file, List.of(), model, this), "sphere-debug-start");
        worker.setDaemon(true);
        worker.start();
    }

    /** F5: starts a session, continues a paused one, pauses a running one. */
    public void continueOrPause() {
        if (adapter == null || !isSessionLive()) {
            startSession();
        } else if (adapter.state() == DebugAdapter.State.PAUSED) {
            adapter.resume();
        } else if (adapter.state() == DebugAdapter.State.RUNNING) {
            adapter.pause();
        }
    }

    public void stepOver() {
        whenPaused(DebugAdapter::stepOver);
    }

    public void stepInto() {
        whenPaused(DebugAdapter::stepInto);
    }

    public void stepOut() {
        whenPaused(DebugAdapter::stepOut);
    }

    public void pause() {
        if (adapter != null && adapter.state() == DebugAdapter.State.RUNNING) {
            adapter.pause();
        }
    }

    /** Runs on to the line of the caret in the session's file. */
    public void runToCursor() {
        if (adapter == null || adapter.state() != DebugAdapter.State.PAUSED) {
            return;
        }
        int line = host.cursorLine(sessionFile);
        if (line < 1) {
            append("Place the caret in " + sessionFile.getName() + " on the line to run to.\n", Tone.ERROR);
            return;
        }
        adapter.runToLine(line);
    }

    /** Runs on to a line of the session's file (the gutter's Run to Line). */
    public void runToLine(File file, int line) {
        if (adapter != null && adapter.state() == DebugAdapter.State.PAUSED && sameFile(file, sessionFile)) {
            adapter.runToLine(line);
        }
    }

    /** Ends the session and starts it again on the same file, with the same breakpoints. */
    public void restart() {
        File file = sessionFile;
        BreakpointModel model = sessionBreakpoints;
        if (file == null) {
            startSession();
            return;
        }
        DebugAdapter old = adapter;
        if (old == null || !isSessionLive()) {
            start(file, model);
            return;
        }
        Thread t = new Thread(() -> {
            old.terminate();
            SwingUtilities.invokeLater(() -> start(file, model));
        }, "sphere-debug-restart");
        t.setDaemon(true);
        t.start();
    }

    /** Ends the session; the debugger's process can take a moment, so off the event thread. */
    public void stop() {
        DebugAdapter a = adapter;
        if (a == null || !isSessionLive()) {
            return;
        }
        Thread t = new Thread(a::terminate, "sphere-debug-stop");
        t.setDaemon(true);
        t.start();
    }

    private void whenPaused(Consumer<DebugAdapter> action) {
        if (adapter != null && adapter.state() == DebugAdapter.State.PAUSED) {
            action.accept(adapter);
        }
    }

    /** The breakpoints of a file changed: a live session on that file follows them. */
    public void breakpointsChanged(BreakpointModel model) {
        if (isSessionLive() && model == sessionBreakpoints) {
            adapter.syncBreakpoints(model);
        }
    }

    /** Forwards a breakpoint toggled while a session is live (kept for former callers). */
    public void breakpointChanged(int line, boolean enabled) {
        breakpointsChanged(sessionBreakpoints);
    }

    public boolean isSessionLive() {
        return adapter != null && adapter.state() != DebugAdapter.State.TERMINATED
            && adapter.state() != DebugAdapter.State.IDLE;
    }

    public boolean isPaused() {
        return adapter != null && adapter.state() == DebugAdapter.State.PAUSED;
    }

    /** The file the session debugs, or null. */
    public File sessionFile() {
        return isSessionLive() ? sessionFile : null;
    }

    /** Ends a running session, for when the editor window closes. */
    public void shutdown() {
        if (isSessionLive()) {
            adapter.terminate();
        }
    }

    private DebugAdapter.ExceptionMode exceptionMode() {
        Object mode = exceptionBox.getSelectedItem();
        return mode instanceof DebugAdapter.ExceptionMode m ? m : DebugAdapter.ExceptionMode.UNCAUGHT;
    }

    // ---- Watch and console --------------------------------------------------------

    /** Adds an expression to the Watch list, evaluated at every stop. */
    public void addWatch(String expression) {
        String e = expression == null ? "" : expression.strip();
        if (e.isEmpty() || e.indexOf(SEPARATOR) >= 0) {
            return;
        }
        if (!watchList.contains(e)) {
            watchList.add(e);
            saveWatches();
            rebuildWatchRows();
        }
        refreshWatches();
    }

    private void removeWatch(int row) {
        if (row >= 0 && row < watchList.size()) {
            watchList.remove(row);
            saveWatches();
            rebuildWatchRows();
        }
    }

    private void clearWatches() {
        watchList.clear();
        saveWatches();
        rebuildWatchRows();
    }

    private void rebuildWatchRows() {
        List<String[]> rows = new ArrayList<>();
        for (String w : watchList) {
            rows.add(new String[] { w, isPaused() ? "…" : "", "" });
        }
        watches.set(rows);
    }

    /** Evaluates the watched expressions in the chosen frame; each value arrives on its own. */
    private void refreshWatches() {
        final int generation = ++watchGeneration;
        if (adapter == null || !adapter.canEvaluate() || adapter.state() != DebugAdapter.State.PAUSED) {
            return;
        }
        final int frame = selectedFrame;
        for (int i = 0; i < watchList.size(); i++) {
            final int row = i;
            final String expression = watchList.get(i);
            final String before = row < watches.getRowCount() ? watches.value(row) : "";
            adapter.evaluate(expression, frame, (ok, value) -> onEdt(() -> {
                if (generation != watchGeneration || row >= watches.getRowCount()
                        || !expression.equals(watches.name(row))) {
                    return;
                }
                String shown = ok ? oneLine(value) : "⚠ " + oneLine(value);
                watches.setValue(row, shown, ok && !before.isEmpty() && !before.equals("…") && !before.equals(shown));
            }));
        }
    }

    private void evaluateTyped() {
        String expression = evalField.getText().strip();
        if (expression.isEmpty()) {
            return;
        }
        history.remove(expression);
        history.add(expression);
        historyIndex = history.size();
        evalField.setText("");
        evaluateInConsole(expression);
    }

    /** Evaluates an expression in the chosen frame and prints it, with its value, in the console. */
    public void evaluateInConsole(String expression) {
        String e = expression == null ? "" : expression.strip();
        if (e.isEmpty()) {
            return;
        }
        append("› " + e + "\n", Tone.MUTED);
        if (adapter == null || !adapter.canEvaluate() || adapter.state() != DebugAdapter.State.PAUSED) {
            append("The program has to be paused to evaluate.\n", Tone.ERROR);
            return;
        }
        adapter.evaluate(e, selectedFrame, (ok, value) -> onEdt(() ->
            append(value + (value.endsWith("\n") ? "" : "\n"), ok ? Tone.RESULT : Tone.ERROR)));
    }

    private void walkHistory(int direction) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + direction));
        evalField.setText(historyIndex < history.size() ? history.get(historyIndex) : "");
    }

    private void loadPreferences() {
        String saved = EditorState.text(WATCHES, "");
        if (!saved.isEmpty()) {
            for (String w : saved.split(String.valueOf(SEPARATOR))) {
                if (!w.isBlank()) {
                    watchList.add(w);
                }
            }
        }
        try {
            exceptionBox.setSelectedItem(DebugAdapter.ExceptionMode.valueOf(
                EditorState.text(EXCEPTIONS, DebugAdapter.ExceptionMode.UNCAUGHT.name())));
        } catch (IllegalArgumentException ex) {
            exceptionBox.setSelectedItem(DebugAdapter.ExceptionMode.UNCAUGHT);
        }
    }

    private void saveWatches() {
        EditorState.setText(WATCHES, String.join(String.valueOf(SEPARATOR), watchList));
    }

    // ---- Adapter callbacks ------------------------------------------------------

    @Override
    public void stateChanged(DebugAdapter.State state) {
        onEdt(() -> {
            updateButtons(state);
            host.sessionChanged(state);
            switch (state) {
                case STARTING -> setStatus("Starting…", Tone.MUTED, null);
                case RUNNING -> {
                    setStatus("Running", Tone.RUNNING, null);
                    host.showExecutionLine(sessionFile, -1);
                    host.clearInlineValues();
                    watchGeneration++;
                }
                case TERMINATED -> {
                    setStatus("Session ended", Tone.MUTED, null);
                    host.showExecutionLine(sessionFile, -1);
                    host.clearInlineValues();
                    variables.set(List.of());
                    stackModel.set(List.of());
                    stack = List.of();
                    watchGeneration++;
                    rebuildWatchRows();
                    // The frame is gone with the process. What the run left on disk
                    // is still true, so it takes its place rather than nothing.
                    com.sphere.components.variables.VariableStore.clear(debugSource);
                    com.sphere.components.variables.VariablesPanel.instance().reread();
                }
                default -> { }
            }
        });
    }

    @Override
    public void stopReason(String text, boolean problem) {
        onEdt(() -> {
            reason = text == null ? "" : text;
            reasonProblem = problem;
        });
    }

    @Override
    public void paused(DebugAdapter.StackFrame frame, List<DebugAdapter.StackFrame> callStack,
                       Map<String, String> vars) {
        publish(vars);
        onEdt(() -> {
            stack = List.copyOf(callStack);
            selectedFrame = 0;
            for (int i = 0; i < callStack.size(); i++) {
                if (callStack.get(i) == frame) {
                    selectedFrame = i;
                    break;
                }
            }
            fillStack();
            showVariables(frame, vars);
            String where = frame.file == null || frame.line <= 0 ? null : frame.file.getName() + ":" + frame.line;
            setStatus(reason.isEmpty() ? "Paused" : reason, reasonProblem ? Tone.PROBLEM : Tone.PAUSED, where);
            if (reasonProblem) {
                append("⚠ " + reason + (where == null ? "" : " at " + where) + "\n", Tone.ERROR);
            }
            reason = "";
            reasonProblem = false;
            host.showExecutionLine(frame.file, frame.line);
            host.showInlineValues(frame.file, frame.line, vars);
            refreshWatches();
            updateButtons(DebugAdapter.State.PAUSED);
        });
    }

    @Override
    public void frameSelected(int index, Map<String, String> vars) {
        onEdt(() -> {
            if (index < 0 || index >= stack.size()) {
                return;
            }
            selectedFrame = index;
            stackTable.repaint();
            DebugAdapter.StackFrame frame = stack.get(index);
            showVariables(frame, vars);
            if (frame.hasSource()) {
                host.revealFrame(frame.file, frame.line);
                host.showInlineValues(frame.file, frame.line, vars);
            }
            refreshWatches();
        });
    }

    /** Fills the Variables table; a value differing from the one this frame had at the last stop stands out. */
    private void showVariables(DebugAdapter.StackFrame frame, Map<String, String> vars) {
        String prefix = frame.name + '\u0000';
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String before = lastValues.get(prefix + e.getKey());
            boolean changed = before != null && !before.equals(e.getValue());
            rows.add(new String[] { e.getKey(), e.getValue(), changed ? "1" : "" });
            lastValues.put(prefix + e.getKey(), e.getValue());
        }
        variables.set(rows);
    }

    private void fillStack() {
        fillingStack = true;
        try {
            stackModel.set(stack);
            if (selectedFrame < stack.size()) {
                stackTable.setRowSelectionInterval(selectedFrame, selectedFrame);
            }
        } finally {
            fillingStack = false;
        }
    }

    /**
     * Hands the paused frame's variables to the Variables tab.
     *
     * A program that has ended can only be read from the file it left behind,
     * which holds what it finished with. Stopped on a breakpoint it can be asked
     * directly, so this is where a local, and a value part way through a run,
     * become visible at all.
     */
    private void publish(Map<String, String> vars) {
        if (debugSource == null) {
            return;
        }
        List<com.sphere.components.variables.VariableStore.Variable> held = new ArrayList<>();
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            held.add(new com.sphere.components.variables.VariableStore.Variable(
                debugSource, entry.getKey(), "local", entry.getValue()));
        }
        com.sphere.components.variables.VariableStore.publish(debugSource, held);
    }

    @Override
    public void breakpointMoved(int requested, int effective) {
        onEdt(() -> {
            BreakpointModel model = sessionBreakpoints;
            if (model != null && model.has(requested) && !model.has(effective)) {
                model.move(requested, effective);
            }
            append("Breakpoint moved from line " + requested + " to line " + effective
                   + ", the first line there that runs.\n", Tone.MUTED);
        });
    }

    @Override
    public void output(String line, boolean stderr) {
        String text = line.endsWith("\n") ? line : line + "\n";
        onEdt(() -> append(text, stderr ? Tone.ERROR : text.startsWith("»") ? Tone.LOG : Tone.NORMAL));
    }

    // ---- Look ---------------------------------------------------------------------

    private enum Tone { NORMAL, MUTED, ERROR, LOG, RESULT, RUNNING, PAUSED, PROBLEM }

    private static Color color(Tone tone) {
        return switch (tone) {
            case MUTED -> EditorTheme.textMuted();
            case ERROR, PROBLEM -> EditorTheme.errorSquiggle();
            case LOG -> EditorTheme.readable(new Color(86, 182, 194));
            case RESULT -> EditorTheme.readable(new Color(152, 195, 121));
            case RUNNING -> goColor();
            case PAUSED -> EditorTheme.readable(new Color(229, 192, 123));
            default -> EditorTheme.foreground();
        };
    }

    private void append(String text, Tone tone) {
        StyledDocument doc = console.getStyledDocument();
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, color(tone));
        if (tone == Tone.MUTED) {
            StyleConstants.setItalic(style, true);
        }
        try {
            doc.insertString(doc.getLength(), text, style);
            if (doc.getLength() > CONSOLE_LIMIT) {
                doc.remove(0, doc.getLength() - CONSOLE_LIMIT / 2);
            }
        } catch (BadLocationException ignored) {
            // the console only loses a line
        }
        console.setCaretPosition(doc.getLength());
    }

    private void setStatus(String text, Tone tone, String where) {
        Color c = color(tone);
        status.setForeground(c);
        status.setIcon(new EditorIcons.Glyph(10, () -> c,
            (g, x, y, s) -> g.fill(new Ellipse2D.Float(x + s * 0.15f, y + s * 0.15f, s * 0.7f, s * 0.7f))));
        status.setText(where == null ? text : text + "  ·  " + where);
        status.setToolTipText(status.getText());
    }

    private void updateButtons(DebugAdapter.State state) {
        boolean paused = state == DebugAdapter.State.PAUSED;
        boolean running = state == DebugAdapter.State.RUNNING;
        boolean live = running || paused || state == DebugAdapter.State.STARTING;
        if (!live) {
            continueButton.setIcon(EditorIcons.play(16, DebugPanel::goColor));
            continueButton.setToolTipText("Start Debugging the active file (F5)");
        } else if (running) {
            continueButton.setIcon(pauseIcon(DebugPanel::stepColor));
            continueButton.setToolTipText("Pause (F5)");
        } else {
            continueButton.setIcon(continueIcon(DebugPanel::goColor));
            continueButton.setToolTipText("Continue (F5)");
        }
        continueButton.setEnabled(state != DebugAdapter.State.STARTING);
        overButton.setEnabled(paused);
        intoButton.setEnabled(paused);
        outButton.setEnabled(paused);
        cursorButton.setEnabled(paused);
        restartButton.setEnabled(live);
        stopButton.setEnabled(live);
        evalField.setEnabled(true);
    }

    static Color goColor() {
        return EditorTheme.readable(new Color(89, 196, 112));
    }

    static Color stepColor() {
        return EditorTheme.readable(new Color(117, 190, 255));
    }

    // Glyphs of the transport bar, drawn like the editor's own.

    static Icon continueIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) -> {
            g.fill(new Rectangle2D.Float(x + s * 0.2f, y + s * 0.2f, s * 0.13f, s * 0.6f));
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.42f, y + s * 0.2f);
            p.lineTo(x + s * 0.84f, y + s * 0.5f);
            p.lineTo(x + s * 0.42f, y + s * 0.8f);
            p.closePath();
            g.fill(p);
        });
    }

    static Icon pauseIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) -> {
            g.fill(new RoundRectangle2D.Float(x + s * 0.26f, y + s * 0.2f, s * 0.16f, s * 0.6f, 2, 2));
            g.fill(new RoundRectangle2D.Float(x + s * 0.58f, y + s * 0.2f, s * 0.16f, s * 0.6f, 2, 2));
        });
    }

    static Icon stepOverIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) -> {
            g.draw(new Arc2D.Float(x + s * 0.16f, y + s * 0.2f, s * 0.66f, s * 0.5f, 180, -170, Arc2D.OPEN));
            GeneralPath head = new GeneralPath();
            head.moveTo(x + s * 0.66f, y + s * 0.38f);
            head.lineTo(x + s * 0.82f, y + s * 0.46f);
            head.lineTo(x + s * 0.88f, y + s * 0.28f);
            g.draw(head);
            g.fill(new Ellipse2D.Float(x + s * 0.4f, y + s * 0.66f, s * 0.2f, s * 0.2f));
        });
    }

    static Icon stepIntoIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) -> {
            float cx = x + s * 0.5f;
            g.draw(new Line2D.Float(cx, y + s * 0.12f, cx, y + s * 0.56f));
            GeneralPath head = new GeneralPath();
            head.moveTo(cx - s * 0.2f, y + s * 0.38f);
            head.lineTo(cx, y + s * 0.58f);
            head.lineTo(cx + s * 0.2f, y + s * 0.38f);
            g.draw(head);
            g.fill(new Ellipse2D.Float(cx - s * 0.1f, y + s * 0.7f, s * 0.2f, s * 0.2f));
        });
    }

    static Icon stepOutIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) -> {
            float cx = x + s * 0.5f;
            g.draw(new Line2D.Float(cx, y + s * 0.14f, cx, y + s * 0.58f));
            GeneralPath head = new GeneralPath();
            head.moveTo(cx - s * 0.2f, y + s * 0.34f);
            head.lineTo(cx, y + s * 0.14f);
            head.lineTo(cx + s * 0.2f, y + s * 0.34f);
            g.draw(head);
            g.fill(new Ellipse2D.Float(cx - s * 0.1f, y + s * 0.7f, s * 0.2f, s * 0.2f));
        });
    }

    static Icon runToCursorIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) -> {
            float cy = y + s * 0.5f;
            g.draw(new Line2D.Float(x + s * 0.14f, cy, x + s * 0.62f, cy));
            GeneralPath head = new GeneralPath();
            head.moveTo(x + s * 0.44f, cy - s * 0.18f);
            head.lineTo(x + s * 0.62f, cy);
            head.lineTo(x + s * 0.44f, cy + s * 0.18f);
            g.draw(head);
            g.draw(new Line2D.Float(x + s * 0.8f, y + s * 0.2f, x + s * 0.8f, y + s * 0.8f));
        });
    }

    static Icon stopIcon(Supplier<Color> color) {
        return new EditorIcons.Glyph(16, color, (g, x, y, s) ->
            g.fill(new RoundRectangle2D.Float(x + s * 0.24f, y + s * 0.24f, s * 0.52f, s * 0.52f, 3, 3)));
    }

    private static String oneLine(String value) {
        String v = value == null ? "" : value.strip();
        int nl = v.indexOf('\n');
        return nl < 0 ? v : v.substring(0, nl) + " …";
    }

    private static String wrap(String value, int width) {
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (int i = 0; i < value.length() && sb.length() < 4000; i++) {
            char c = value.charAt(i);
            sb.append(c);
            col = c == '\n' ? 0 : col + 1;
            if (col >= width && (c == ',' || c == ' ')) {
                sb.append('\n');
                col = 0;
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean sameFile(File a, File b) {
        return a != null && b != null && a.getAbsoluteFile().equals(b.getAbsoluteFile());
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    // ---- Tables -------------------------------------------------------------------

    /** Name, value and whether it changed since the last stop. */
    private static final class ValueModel extends AbstractTableModel {
        private final List<String[]> rows = new ArrayList<>();

        void set(List<String[]> r) {
            rows.clear();
            rows.addAll(r);
            fireTableDataChanged();
        }

        void setValue(int row, String value, boolean changed) {
            if (row >= 0 && row < rows.size()) {
                rows.get(row)[1] = value;
                rows.get(row)[2] = changed ? "1" : "";
                fireTableRowsUpdated(row, row);
            }
        }

        String name(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row)[0] : "";
        }

        String value(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row)[1] : "";
        }

        boolean changed(int row) {
            return row >= 0 && row < rows.size() && "1".equals(rows.get(row)[2]);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }
    }

    private static final class ValueRenderer extends DefaultTableCellRenderer {
        private final ValueModel model;

        ValueRenderer(ValueModel model) {
            this.model = model;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, column == 0 ? 10 : 4, 0, 4));
            boolean changed = column == 1 && model.changed(row);
            setFont(FontLoader.getTerminalFont(changed ? Font.BOLD : Font.PLAIN, 12));
            String text = String.valueOf(value);
            Color fg;
            if (column == 0) {
                fg = EditorTheme.readable(new Color(156, 220, 254));
            } else if (changed) {
                fg = color(Tone.PAUSED);
            } else if (text.startsWith("⚠")) {
                fg = EditorTheme.errorSquiggle();
            } else if (text.startsWith("\"") || text.startsWith("'")) {
                fg = EditorTheme.readable(new Color(206, 145, 120));
            } else if (!text.isEmpty() && (Character.isDigit(text.charAt(0)) || text.charAt(0) == '-')) {
                fg = EditorTheme.readable(new Color(181, 206, 168));
            } else {
                fg = EditorTheme.foreground();
            }
            setForeground(fg);
            setBackground(selected ? EditorTheme.selection() : EditorTheme.background());
            return this;
        }
    }

    /** One row per frame: its function, then where. */
    private static final class StackModel extends AbstractTableModel {
        private List<DebugAdapter.StackFrame> frames = List.of();

        void set(List<DebugAdapter.StackFrame> f) {
            frames = List.copyOf(f);
            fireTableDataChanged();
        }

        DebugAdapter.StackFrame frame(int row) {
            return frames.get(row);
        }

        @Override
        public int getRowCount() {
            return frames.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return frames.get(row);
        }
    }

    private static final class StackRenderer extends DefaultTableCellRenderer {
        private final java.util.function.IntSupplier current;
        /** The frame shown in the editor, marked as the gutter marks the stopped line. */
        private final Icon arrow = new EditorIcons.Glyph(12, EditorTheme::executionArrow, (g, x, y, s) -> {
            GeneralPath p = new GeneralPath();
            p.moveTo(x + s * 0.3f, y + s * 0.2f);
            p.lineTo(x + s * 0.8f, y + s * 0.5f);
            p.lineTo(x + s * 0.3f, y + s * 0.8f);
            p.closePath();
            g.fill(p);
        });
        private final Icon none = new EditorIcons.Glyph(12, EditorTheme::background, (g, x, y, s) -> { });

        StackRenderer(java.util.function.IntSupplier current) {
            this.current = current;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            DebugAdapter.StackFrame f = (DebugAdapter.StackFrame) value;
            boolean source = f.hasSource();
            boolean shown = row == current.getAsInt();
            String fg = hex(source ? EditorTheme.foreground() : EditorTheme.textMuted());
            String muted = hex(EditorTheme.textMuted());
            String where = f.file == null ? "" : f.file.getName() + (f.line > 0 ? ":" + f.line : "");
            setText("<html><span style='color:" + fg + "'>" + (shown ? "<b>" : "") + escape(f.name) + (shown ? "</b>" : "")
                    + "</span>&nbsp;&nbsp;<span style='color:" + muted + "'>" + escape(where) + "</span></html>");
            setIcon(shown ? arrow : none);
            setIconTextGap(4);
            setFont(FontLoader.getTerminalFont(source ? Font.PLAIN : Font.ITALIC, 12));
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            setBackground(selected ? EditorTheme.selection() : EditorTheme.background());
            setToolTipText(f.file == null ? f.name : f.file.getPath() + (f.line > 0 ? ":" + f.line : ""));
            return this;
        }

        private static String hex(Color c) {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }
    }
}
