package com.sphere.components.editor.debug;

import com.sphere.components.editor.BreakpointModel;
import com.sphere.components.editor.DebugAdapter;
import com.sphere.components.editor.EditorTheme;
import com.sphere.utils.SettingsManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Debug controls and state for the editor: the transport buttons, the variables
 * of the current frame, the call stack and the program's output.
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
    }

    private final Host host;
    private final SettingsManager settings;

    private final JButton startButton  = button("Start");
    private final JButton resumeButton = button("Continue");
    private final JButton overButton   = button("Step Over");
    private final JButton intoButton   = button("Step Into");
    private final JButton outButton    = button("Step Out");
    private final JButton stopButton   = button("Stop");
    private final JLabel stateLabel    = new JLabel("idle");

    private final DefaultTableModel variableModel =
        new DefaultTableModel(new Object[] {"Name", "Value"}, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    private final DefaultTableModel stackModel =
        new DefaultTableModel(new Object[] {"Frame", "File", "Line"}, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    private final JTextArea console = new JTextArea();

    private DebugAdapter adapter;
    private File sessionFile;

    public DebugPanel(Host host, SettingsManager settings) {
        this.host = host;
        this.settings = settings;
        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, EditorTheme.gutterBorder()));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        updateButtons(DebugAdapter.State.IDLE);
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBackground(EditorTheme.background());
        bar.add(startButton);
        bar.add(resumeButton);
        bar.add(overButton);
        bar.add(intoButton);
        bar.add(outButton);
        bar.add(stopButton);
        bar.add(Box.createHorizontalGlue());
        stateLabel.setForeground(EditorTheme.gutterForeground());
        bar.add(stateLabel);

        startButton.addActionListener(this::onStart);
        resumeButton.addActionListener(e -> withAdapter(DebugAdapter::resume));
        overButton.addActionListener(e -> withAdapter(DebugAdapter::stepOver));
        intoButton.addActionListener(e -> withAdapter(DebugAdapter::stepInto));
        outButton.addActionListener(e -> withAdapter(DebugAdapter::stepOut));
        stopButton.addActionListener(e -> withAdapter(DebugAdapter::terminate));
        return bar;
    }

    private JPanel buildBody() {
        JTable variables = table(variableModel);
        JTable stack = table(stackModel);

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setBackground(EditorTheme.background());
        console.setForeground(EditorTheme.foreground());

        JPanel variablePane = titled("Variables", new JScrollPane(variables));
        JPanel stackPane = titled("Call stack", new JScrollPane(stack));
        JPanel consolePane = titled("Output", new JScrollPane(console));
        // Preferred widths decide where the dividers land on the first layout;
        // without them the output column was squeezed against the window edge.
        variablePane.setPreferredSize(new Dimension(340, 190));
        stackPane.setPreferredSize(new Dimension(300, 190));
        consolePane.setPreferredSize(new Dimension(360, 190));

        JSplitPane tables = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, variablePane, stackPane);
        tables.setResizeWeight(0.55);
        tables.setDividerSize(4);
        tables.setBorder(null);

        JSplitPane all = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tables, consolePane);
        all.setResizeWeight(0.64);
        all.setDividerSize(4);
        all.setBorder(null);

        JPanel body = new JPanel(new BorderLayout());
        body.add(all, BorderLayout.CENTER);
        body.setPreferredSize(new Dimension(100, 190));
        return body;
    }

    private JPanel titled(String title, JScrollPane content) {
        JLabel label = new JLabel(" " + title);
        label.setForeground(EditorTheme.gutterForeground());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        content.setBorder(null);
        content.getViewport().setBackground(EditorTheme.background());
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.background());
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(19);
        table.setShowGrid(false);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        table.setBackground(EditorTheme.background());
        table.setForeground(EditorTheme.foreground());
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        return button;
    }

    // ---- Session -----------------------------------------------------------

    private void onStart(ActionEvent event) {
        File file = host.activeFile();
        if (file == null) {
            append("Open a source file before starting a session.\n", true);
            return;
        }
        String name = file.getName().toLowerCase();
        // The adapter follows the file, so one panel drives gdb for a .cpp and the
        // Python driver for a .py without the user choosing.
        if (name.endsWith(".py") || name.endsWith(".pyw")) {
            adapter = new PdbAdapter(settings);
        } else if (name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cc")
                   || name.endsWith(".cxx") || name.endsWith(".c++")) {
            adapter = new GdbAdapter(settings);
        } else {
            append("No debugger for " + file.getName()
                   + ". C, C++ and Python are supported.\n", true);
            return;
        }
        sessionFile = file;
        variableModel.setRowCount(0);
        stackModel.setRowCount(0);
        console.setText("");
        append("Starting session on " + file.getName() + "\n", false);
        adapter.start(file, List.of(), host.activeBreakpoints(), this);
    }

    private void withAdapter(Consumer<DebugAdapter> action) {
        if (adapter != null && adapter.state() != DebugAdapter.State.TERMINATED) {
            action.accept(adapter);
        }
    }

    /** Forwards a breakpoint toggled while a session is live. */
    public void breakpointChanged(int line, boolean enabled) {
        if (adapter instanceof GdbAdapter gdb) {
            gdb.syncBreakpoint(line, enabled);
        } else if (adapter instanceof PdbAdapter pdb) {
            pdb.syncBreakpoint(line, enabled);
        }
    }

    public boolean isSessionLive() {
        return adapter != null && adapter.state() != DebugAdapter.State.TERMINATED
            && adapter.state() != DebugAdapter.State.IDLE;
    }

    /** Ends a running session, for when the editor window closes. */
    public void shutdown() {
        withAdapter(DebugAdapter::terminate);
    }

    // ---- Adapter callbacks -------------------------------------------------

    @Override
    public void stateChanged(DebugAdapter.State state) {
        onEdt(() -> {
            stateLabel.setText(state.name().toLowerCase());
            updateButtons(state);
            if (state == DebugAdapter.State.RUNNING || state == DebugAdapter.State.TERMINATED) {
                host.showExecutionLine(sessionFile, -1);
            }
            if (state == DebugAdapter.State.TERMINATED) {
                variableModel.setRowCount(0);
                stackModel.setRowCount(0);
            }
        });
    }

    @Override
    public void paused(DebugAdapter.StackFrame frame, List<DebugAdapter.StackFrame> callStack,
                       Map<String, String> variables) {
        onEdt(() -> {
            variableModel.setRowCount(0);
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                variableModel.addRow(new Object[] {entry.getKey(), entry.getValue()});
            }
            stackModel.setRowCount(0);
            for (DebugAdapter.StackFrame f : callStack) {
                stackModel.addRow(new Object[] {
                    f.name, f.file == null ? "" : f.file.getName(), f.line});
            }
            host.showExecutionLine(frame.file, frame.line);
            updateButtons(DebugAdapter.State.PAUSED);
            stateLabel.setText("paused");
        });
    }

    @Override
    public void breakpointMoved(int requested, int effective) {
        onEdt(() -> {
            BreakpointModel model = host.activeBreakpoints();
            if (model.has(requested) && !model.has(effective)) {
                model.toggle(requested);
                model.toggle(effective);
            }
            append("breakpoint moved from line " + requested + " to line "
                   + effective + "\n", false);
        });
    }

    @Override
    public void output(String line, boolean stderr) {
        onEdt(() -> append(line.endsWith("\n") ? line : line + "\n", stderr));
    }

    private void append(String text, boolean stderr) {
        console.append(stderr ? "! " + text : text);
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void updateButtons(DebugAdapter.State state) {
        boolean paused = state == DebugAdapter.State.PAUSED;
        boolean live = state == DebugAdapter.State.RUNNING || paused
                    || state == DebugAdapter.State.STARTING;
        startButton.setEnabled(!live);
        resumeButton.setEnabled(paused);
        overButton.setEnabled(paused);
        intoButton.setEnabled(paused);
        outButton.setEnabled(paused);
        stopButton.setEnabled(live);
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
