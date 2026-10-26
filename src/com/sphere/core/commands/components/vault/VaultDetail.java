package com.sphere.components.vault;

import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.SyntaxTokenizer;
import com.sphere.components.vault.VaultAnalysis.Issue;
import com.sphere.components.vault.VaultRunner.Mode;
import com.sphere.components.vault.VaultRunner.Request;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The item chosen in the list, in full: what it is, what is wrong with it and
 * how to fix that, a form for its arguments with the exact line it will send,
 * its code, and what the index knows about it.
 *
 * The form is built from the item's own source. A ROOT macro shows the
 * parameters of its function, a Python script those of its argparse calls,
 * and each field knows its type, so a count takes digits, a switch is a check
 * box, a file has a chooser and a choice a list. What is typed is kept per
 * item for the session and saved with each run, so the next run starts where
 * the last one left off.
 */
final class VaultDetail extends JPanel {

    interface Actions {
        void run(VaultItem item, Request request);

        void compose(VaultItem item, Request request);

        void edit(VaultItem item);

        void fix(VaultItem item, VaultAnalysis.Fix fix);

        void filter(String token);

        void select(VaultItem item);

        void togglePin(VaultItem item);

        void send(VaultItem item, String command);

        void more(VaultItem item, Component anchor, int x, int y);

        String project();

        boolean engineRunning();

        VaultIndex index();
    }

    private static final String[] TABS = {"Run", "Code", "Info"};
    private static final int CODE_LINES = 2500;

    private final Actions actions;
    /** What each form holds this session, shared by the tab and the Code Vault window. */
    private static final Map<String, Request> DRAFTS = new HashMap<>();

    private final JPanel header = VaultLook.transparent(new JPanel());
    private final JPanel cards = VaultLook.transparent(new JPanel(new CardLayout()));
    private final TabStrip strip = new TabStrip();
    private final JPanel runPanel = VaultLook.transparent(new JPanel(new GridBagLayout()));
    private final JTextPane code = new CodeView();
    private final JPanel infoPanel = VaultLook.transparent(new JPanel(new GridBagLayout()));
    private final JPanel actionBar = VaultLook.transparent(new JPanel());
    private final JPanel empty = VaultLook.transparent(new JPanel(new BorderLayout()));
    private final JPanel body = VaultLook.transparent(new JPanel(new BorderLayout()));

    private final JScrollPane runScroll;
    private VaultItem item;
    private Request request;
    private JTextArea preview;
    private JLabel problem;
    private VaultLook.FlatButton runButton;
    private String codeFor;
    private int tab;

    VaultDetail(Actions actions) {
        super(new CardLayout());
        this.actions = actions;
        setOpaque(true);
        setBackground(VaultLook.panel());

        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));

        runScroll = scroll(runPanel);
        cards.add(runScroll, TABS[0]);
        final JScrollPane codeScroll = new JScrollPane(code);
        codeScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, VaultLook.mix(VaultLook.text(), VaultLook.panel(), 0.1f)));
        codeScroll.getViewport().setBackground(EditorTheme.background());
        codeScroll.getVerticalScrollBar().setUnitIncrement(16);
        cards.add(codeScroll, TABS[1]);
        cards.add(scroll(infoPanel), TABS[2]);

        final JPanel middle = VaultLook.transparent(new JPanel(new BorderLayout()));
        middle.add(strip, BorderLayout.NORTH);
        middle.add(cards, BorderLayout.CENTER);

        actionBar.setLayout(new BoxLayout(actionBar, BoxLayout.X_AXIS));
        actionBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, VaultLook.mix(VaultLook.text(), VaultLook.panel(), 0.1f)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        final JScrollPane headerScroll = scroll(header);
        body.setLayout(new BodyLayout(headerScroll, middle, actionBar));
        body.add(headerScroll);
        body.add(middle);
        body.add(actionBar);

        // Nothing chosen, nothing shown: the panel hides this pane then.
        add(empty, "empty");
        add(body, "item");
        // Small, so the split above can give the list its share whatever the
        // height of the tab: every part scrolls rather than pushing the list out.
        setMinimumSize(new Dimension(0, 60));
        show(null);
    }

    private static JScrollPane scroll(JComponent content) {
        final JPanel holder = new VaultLook.Column(new BorderLayout());
        holder.add(content, BorderLayout.NORTH);
        final JScrollPane scroll = new VaultLook.ClearScroll(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setMinimumSize(new Dimension(0, 0));
        return scroll;
    }

    /**
     * The header on top, the actions at the bottom, the tabs between them. The
     * header takes what it needs up to a little under half the height and
     * scrolls past that, so a long description or a list of issues never
     * leaves the form without room in a short tab.
     */
    private static final class BodyLayout implements java.awt.LayoutManager {
        private final JComponent header;
        private final JComponent middle;
        private final JComponent actions;

        BodyLayout(JComponent header, JComponent middle, JComponent actions) {
            this.header = header;
            this.middle = middle;
            this.actions = actions;
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            final int w = parent.getWidth();
            final int h = parent.getHeight();
            final int actionH = Math.min(h, actions.getPreferredSize().height);
            final int room = Math.max(0, h - actionH);
            final int headerH = Math.min(header.getPreferredSize().height, Math.max(56, (int) (room * 0.42)));
            header.setBounds(0, 0, w, Math.min(room, headerH));
            middle.setBounds(0, Math.min(room, headerH), w, Math.max(0, room - headerH));
            actions.setBounds(0, h - actionH, w, actionH);
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            return new Dimension(100, header.getPreferredSize().height + 220 + actions.getPreferredSize().height);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return new Dimension(0, actions.getPreferredSize().height);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(VaultLook.panel());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    VaultItem item() {
        return item;
    }

    /** Brings Run, Code or Info to the front. */
    void showTab(int index) {
        strip.select(index);
    }

    /** What the form holds for an item, typed this session or saved by its last run. */
    Request request(VaultItem target) {
        final Request draft = DRAFTS.get(target.key());
        if (draft != null) return draft;
        final Request r = saved(target, actions.index().state());
        DRAFTS.put(target.key(), r);
        return r;
    }

    /** The form as the item's last run left it, or as its source declares it. */
    static Request saved(VaultItem target, VaultState state) {
        final String key = target.key();
        final Request r = new Request();
        final String opts = state.get(key, "opts");
        r.options = opts.isEmpty() ? target.info.defaultOptions : opts;
        final List<String> saved = state.values(key);
        for (int i = 0; i < target.info.params.size(); i++) {
            r.values.add(i < saved.size() ? saved.get(i) : "");
        }
        r.extra = state.get(key, "extra");
        final String mode = state.get(key, "mode");
        r.mode = mode.equals("PROCESS") ? Mode.PROCESS : Mode.ENGINE;
        r.aclic = state.get(key, "aclic");
        return r;
    }

    /** Keeps the form's values for the next session too. */
    void remember(VaultItem target, Request r) {
        final VaultState state = actions.index().state();
        final String key = target.key();
        state.put(key, "opts", r.options.equals(target.info.defaultOptions) ? "" : r.options);
        state.putValues(key, r.values);
        state.put(key, "extra", r.extra);
        state.put(key, "mode", r.mode == Mode.PROCESS ? "PROCESS" : "");
        state.put(key, "aclic", r.aclic);
    }

    // ---- showing an item -----------------------------------------------------------

    void show(VaultItem next) {
        final boolean same = next != null && item != null && next.key().equals(item.key())
            && next.modified == item.modified && next.size == item.size;
        if (same && next == item) {
            refreshHeader();
            return;
        }
        if (next != null && item != null && !next.key().equals(item.key())) {
            codeFor = null;
        }
        item = next;
        final CardLayout layout = (CardLayout) getLayout();
        if (next == null) {
            layout.show(this, "empty");
            return;
        }
        if (!same) {
            final Request r = DRAFTS.get(next.key());
            if (r != null) {
                // The file changed: a parameter list of a new length starts afresh.
                while (r.values.size() < next.info.params.size()) r.values.add("");
                while (r.values.size() > next.info.params.size()) r.values.remove(r.values.size() - 1);
            }
            codeFor = null;
        }
        request = request(next);
        refreshHeader();
        buildRun();
        buildInfo();
        buildActions();
        if (tab == 1) loadCode();
        layout.show(this, "item");
        revalidate();
        repaint();
    }

    // ---- header ----------------------------------------------------------------------

    private void refreshHeader() {
        header.removeAll();
        final VaultItem it = item;
        final VaultAnalysis info = it.info;

        final JPanel top = VaultLook.transparent(new JPanel(new BorderLayout(8, 0)));
        top.add(new Badge(VaultQuery.family(it)), BorderLayout.WEST);
        final JLabel name = new JLabel(it.name());
        name.setFont(VaultLook.ui(Font.BOLD, 13.5f));
        name.setForeground(VaultLook.text());
        // Where it lives is in the tooltip and the Info tab: a short tab has no line to spare.
        name.setToolTipText((it.layer == VaultItem.Layer.PROJECT ? "project " + actions.project() + " · " : "")
            + it.kind.folder + "/" + it.relative + "  —  " + it.path);
        top.add(name, BorderLayout.CENTER);
        final boolean pinned = actions.index().state().pinned(it.key());
        final VaultLook.FlatButton pin = new VaultLook.FlatButton("", VaultLook.glyph(pinned ? "star-filled" : "star", 14,
            pinned ? VaultLook.warning() : VaultLook.muted()), pinned ? "Unpin" : "Pin: kept at the top of the list", false);
        pin.addActionListener(e -> actions.togglePin(it));
        top.add(pin, BorderLayout.EAST);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(top);

        // What is wrong comes first, under the name; hints wait in the Info tab.
        for (Issue issue : info.issues) {
            if (issue.severity() != VaultAnalysis.Severity.INFO) header.add(issueRow(it, issue));
        }

        final String description = info.description.isEmpty() ? info.title : info.description;
        if (!description.isEmpty()) {
            final VaultLook.Text text = new VaultLook.Text(description, VaultLook.ui(Font.PLAIN, 11.5f),
                VaultLook.muted(), null).limit(2);
            text.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            header.add(text);
        }

        final JPanel chips = VaultLook.transparent(new JPanel(new VaultLook.WrapLayout(FlowLayout.LEFT, 4, 3)));
        chips.setBorder(BorderFactory.createEmptyBorder(2, -4, 0, 0));
        for (String tag : info.tags) {
            chips.add(chip("#" + tag, VaultLook.accent(), null, "Show everything tagged " + tag, () -> actions.filter("#" + tag)));
        }
        if (info.draws) {
            chips.add(chip("plots", VaultLook.ok(), "plot", "Draws or saves a figure: it lands in the Plots tab", () -> actions.filter("is:plot")));
        }
        final List<VaultItem> twins = actions.index().twinsOf(it);
        if (!twins.isEmpty()) {
            chips.add(chip(twins.size() + " twin" + (twins.size() > 1 ? "s" : ""), VaultLook.info(), "twin",
                "Same content as " + twins.get(0).relative + (twins.size() > 1 ? " and others" : ""),
                () -> actions.select(twins.get(0))));
        }
        if (chips.getComponentCount() > 0) {
            chips.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(chips);
        }

        header.revalidate();
        header.repaint();
        relayout(header);
    }

    /** The message on its own lines, the fix as a link under it: the side panel is narrow. */
    private JComponent issueRow(VaultItem it, Issue issue) {
        final JPanel row = VaultLook.transparent(new JPanel(new BorderLayout(6, 0)));
        row.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        final String glyph = switch (issue.severity()) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "info";
        };
        final JLabel icon = new JLabel(VaultLook.glyph(glyph, 13, VaultLook.severity(issue.severity())));
        icon.setVerticalAlignment(JLabel.TOP);
        icon.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
        row.add(icon, BorderLayout.WEST);
        final JPanel text = VaultLook.transparent(new JPanel());
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(wrapping(issue.message(), VaultLook.ui(Font.PLAIN, 11f),
            issue.severity() == VaultAnalysis.Severity.INFO ? VaultLook.muted() : VaultLook.text()));
        if (issue.fix() != null) {
            final JLabel fix = new JLabel(issue.fix().label() + "  →");
            fix.setFont(VaultLook.ui(Font.BOLD, 10.5f));
            fix.setForeground(VaultLook.accent());
            fix.setToolTipText("Apply this fix to the file");
            fix.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            fix.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            fix.setAlignmentX(Component.LEFT_ALIGNMENT);
            fix.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    actions.fix(it, issue.fix());
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    fix.setForeground(VaultLook.shift(VaultLook.accent(), 0.25f));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    fix.setForeground(VaultLook.accent());
                }
            });
            text.add(fix);
        }
        row.add(text, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static VaultLook.Chip chip(String text, Color dot, String glyph, String tip, Runnable action) {
        final VaultLook.Chip chip = new VaultLook.Chip(text, dot, glyph, tip);
        chip.addActionListener(e -> {
            chip.setSelected(false);
            action.run();
        });
        return chip;
    }

    static JTextArea wrapping(String text, Font font, Color color) {
        final JTextArea area = new VaultLook.Text(text, font, color, null);
        area.setFocusable(false);
        return area;
    }

    /**
     * Lays a rebuilt part out a second time once it has a width. A wrapping
     * text measures its height for the width it has, which is none the first
     * time round, so a single pass leaves it one line tall.
     */
    private void relayout(JComponent part) {
        // Twice removed, so it runs after the layout the first revalidate asked
        // for. The part is invalidated through and through (a box layout keeps
        // its sizes until then), and the body after it, since the scroll the
        // part sits in stops a revalidate from reaching the body's layout.
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            invalidateAll(part);
            body.revalidate();
            body.repaint();
        }));
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) invalidateAll(child);
        }
    }

    // ---- the run form ----------------------------------------------------------------------

    private void buildRun() {
        runPanel.removeAll();
        runPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        final VaultItem it = item;
        final Request r = request;
        int row = 0;

        if (!VaultRunner.runnable(it)) {
            final String why = it.info.header ? "A header is included by the macros that use it; it does not run on its own."
                : it.info.lines == 0 ? "The file is empty."
                : "Sphere has no runner for " + it.info.language.name + " files. Compose inserts its reference.";
            runPanel.add(wrapping(why, VaultLook.ui(Font.PLAIN, 11.5f), VaultLook.muted()), gbc(0, row, 2, 1));
            preview = null;
            problem = null;
            runPanel.revalidate();
            runPanel.repaint();
            return;
        }

        if (it.isRootMacro()) {
            final JPanel modes = VaultLook.transparent(new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)));
            final ButtonGroup group = new ButtonGroup();
            for (Mode mode : Mode.values()) {
                final VaultLook.Chip chip = new VaultLook.Chip(mode.label,
                    mode == Mode.ENGINE ? (actions.engineRunning() ? VaultLook.ok() : VaultLook.faint()) : null,
                    mode == Mode.PROCESS ? "layer" : null,
                    mode.tip + (mode == Mode.ENGINE && !actions.engineRunning() ? " (not running now)" : ""));
                chip.setSelected(r.mode == mode);
                group.add(chip);
                chip.addActionListener(e -> {
                    r.mode = mode;
                    buildRun();
                });
                modes.add(chip);
            }
            runPanel.add(label("Run in", "Where the macro runs"), gbc(0, row, 1, 0));
            runPanel.add(modes, gbcField(1, row++));

            final JComboBox<String> aclic = new JComboBox<>(new String[]{
                "interpreted", "+  compiled", "++ rebuilt", "+g with debug info", "+O optimized"});
            final String[] suffixes = {"", "+", "++", "+g", "+O"};
            for (int i = 0; i < suffixes.length; i++) if (suffixes[i].equals(r.aclic)) aclic.setSelectedIndex(i);
            aclic.setFont(VaultLook.ui(Font.PLAIN, 11.5f));
            aclic.setToolTipText("ACLiC: compile the macro to a library before running it (macro.C+)");
            aclic.addActionListener(e -> {
                r.aclic = suffixes[Math.max(0, aclic.getSelectedIndex())];
                updatePreview();
            });
            final VaultLook.FlatButton check = VaultLook.button("Check", "info",
                "Compile it with ACLiC in the engine and load it, without running it (.L macro.C+)", false);
            check.addActionListener(e -> actions.send(it, VaultRunner.compile(it, r)));
            final JPanel line = VaultLook.transparent(new JPanel(new BorderLayout(4, 0)));
            line.add(aclic, BorderLayout.CENTER);
            line.add(check, BorderLayout.EAST);
            runPanel.add(label("ACLiC", "How the macro is built"), gbc(0, row, 1, 0));
            runPanel.add(line, gbcField(1, row++));
        }

        if (VaultRunner.takesOptions(it, r)) {
            final VaultLook.Field options = new VaultLook.Field(VaultRunner.optionsHint(it));
            options.setText(r.options);
            options.setFont(VaultLook.mono(11.5f));
            options.setToolTipText("Outside the bracket: for " + VaultRunner.prefix(it) + " itself, not for the script");
            onChange(options, () -> {
                r.options = options.getText();
                updatePreview();
            });
            runPanel.add(label(VaultRunner.prefix(it), "Options for the interpreter or the compiler, outside [@ ... ]"),
                gbc(0, row, 1, 0));
            runPanel.add(options, gbcField(1, row++));

            final List<String> quick = VaultRunner.quickOptions(it);
            if (!quick.isEmpty()) {
                final JPanel chips = VaultLook.transparent(new JPanel(new VaultLook.WrapLayout(FlowLayout.LEFT, 4, 2)));
                chips.setBorder(BorderFactory.createEmptyBorder(0, -4, 2, 0));
                for (String q : quick) {
                    final VaultLook.Chip chip = new VaultLook.Chip(q, null, null, "Toggle " + q);
                    chip.setFont(VaultLook.mono(10f));
                    chip.setSelected(hasOption(r.options, q));
                    chip.addActionListener(e -> {
                        options.setText(toggleOption(options.getText(), q));
                        chip.setSelected(hasOption(options.getText(), q));
                    });
                    chips.add(chip);
                }
                runPanel.add(chips, gbcField(1, row++));
            }
        }

        final List<VaultParam> params = it.info.params;
        if (!params.isEmpty()) {
            runPanel.add(section(it.isRootMacro() && VaultRunner.usesEngine(it, r)
                ? "Arguments of " + it.info.entry + "()" : "Arguments inside [@ ... ]"), gbc(0, row++, 2, 1));
        }
        for (int i = 0; i < params.size(); i++) {
            final int index = i;
            final VaultParam p = params.get(i);
            final JComponent editor = editorFor(p, r.value(i), value -> {
                r.values.set(index, value);
                updatePreview();
            });
            runPanel.add(label(p.label() + (p.required() ? " *" : ""), tip(p)), gbc(0, row, 1, 0));
            runPanel.add(editor, gbcField(1, row++));
            if (!p.help().isEmpty()) {
                final JTextArea help = wrapping(p.help(), VaultLook.ui(Font.PLAIN, 10f), VaultLook.faint());
                final GridBagConstraints c = gbcField(1, row++);
                c.insets = new Insets(0, 4, 4, 0);
                runPanel.add(help, c);
            }
        }

        final VaultLook.Field extra = new VaultLook.Field(params.isEmpty()
            ? "arguments for " + it.name() : "more arguments");
        extra.setText(r.extra);
        extra.setFont(VaultLook.mono(11.5f));
        extra.setToolTipText(VaultRunner.usesEngine(it, r)
            ? "Written into the call as they are: 3, \"abc\", kRed"
            : "Inside the bracket, after the file: they reach " + it.name());
        onChange(extra, () -> {
            r.extra = extra.getText();
            updatePreview();
        });
        runPanel.add(label(params.isEmpty() ? "Arguments" : "More", "Inside the bracket, for the script"), gbc(0, row, 1, 0));
        runPanel.add(extra, gbcField(1, row++));

        final JPanel commandHead = VaultLook.transparent(new JPanel(new BorderLayout()));
        commandHead.add(section("What runs"), BorderLayout.WEST);
        final VaultLook.FlatButton copy = VaultLook.button("Copy", null, "Copy the line", false);
        copy.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
        copy.addActionListener(e -> {
            if (preview != null) com.sphere.components.ClipboardBridge.write(preview.getText());
        });
        final VaultLook.FlatButton reset = VaultLook.button("Reset", null, "Back to the defaults the source declares", false);
        reset.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
        reset.addActionListener(e -> {
            for (int i = 0; i < r.values.size(); i++) r.values.set(i, "");
            r.extra = "";
            r.options = it.info.defaultOptions;
            r.aclic = "";
            buildRun();
        });
        final JPanel tools = VaultLook.transparent(new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)));
        tools.add(reset);
        tools.add(copy);
        commandHead.add(tools, BorderLayout.EAST);
        runPanel.add(commandHead, gbc(0, row++, 2, 1));

        preview = new VaultLook.Text("", VaultLook.mono(11.5f), VaultLook.text(), VaultLook.field());
        preview.setWrapStyleWord(false);
        preview.setSelectionColor(VaultLook.selection());
        preview.setToolTipText("Exactly what Run sends to the console");
        preview.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, VaultLook.accent()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        runPanel.add(preview, gbc(0, row++, 2, 1));

        problem = new JLabel(" ");
        problem.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
        problem.setForeground(VaultLook.error());
        runPanel.add(problem, gbc(0, row, 2, 1));

        updatePreview();
        runPanel.revalidate();
        runPanel.repaint();
        relayout(runPanel);
        SwingUtilities.invokeLater(() -> runScroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
    }

    private void updatePreview() {
        if (item == null || request == null) return;
        final String command = VaultRunner.command(item, request, actions.project());
        if (preview != null) preview.setText(command == null ? "" : command);
        final String why = VaultRunner.problem(item, request);
        if (problem != null) problem.setText(why == null ? " " : why);
        if (runButton != null) {
            runButton.setEnabled(VaultRunner.runnable(item) && why == null);
            runButton.setToolTipText(why == null ? "Run now (Enter)" : why);
        }
    }

    /** One field, of the kind its type calls for. */
    private JComponent editorFor(VaultParam p, String value, java.util.function.Consumer<String> changed) {
        switch (p.type()) {
            case BOOL -> {
                final JCheckBox box = new JCheckBox(p.help().isEmpty() ? (p.isOption() ? "on" : "true") : "");
                final String state = value.isEmpty() ? p.defaultValue() : value;
                box.setSelected(state.equalsIgnoreCase("true"));
                box.setOpaque(false);
                box.setFont(VaultLook.ui(Font.PLAIN, 11f));
                box.setForeground(VaultLook.muted());
                box.setFocusPainted(false);
                box.addActionListener(e -> {
                    final String now = Boolean.toString(box.isSelected());
                    changed.accept(now.equalsIgnoreCase(p.defaultValue()) ? "" : now);
                });
                return box;
            }
            case CHOICE -> {
                final List<String> options = new ArrayList<>(p.choices());
                final JComboBox<String> combo = new JComboBox<>(options.toArray(new String[0]));
                final String state = value.isEmpty() ? p.defaultValue() : value;
                if (options.contains(state)) combo.setSelectedItem(state);
                combo.setFont(VaultLook.ui(Font.PLAIN, 11.5f));
                combo.addActionListener(e -> {
                    final String now = String.valueOf(combo.getSelectedItem());
                    changed.accept(now.equals(p.defaultValue()) ? "" : now);
                });
                return combo;
            }
            default -> {
                final VaultLook.Field field = new VaultLook.Field(p.defaultValue().isEmpty()
                    ? (p.required() ? "required" : p.type().name().toLowerCase(Locale.ROOT))
                    : p.defaultValue());
                field.setText(value);
                field.setFont(p.type() == VaultParam.Type.TEXT || p.type() == VaultParam.Type.PATH
                    ? VaultLook.ui(Font.PLAIN, 11.5f) : VaultLook.mono(11.5f));
                field.setToolTipText(tip(p));
                onChange(field, () -> {
                    field.invalid = !field.getText().isBlank() && !p.accepts(field.getText());
                    field.repaint();
                    changed.accept(field.getText());
                });
                if (p.type() != VaultParam.Type.PATH) return field;
                final VaultLook.FlatButton browse = VaultLook.button("", "folder", "Choose a file", false);
                browse.addActionListener(e -> {
                    final java.awt.Window window = SwingUtilities.getWindowAncestor(this);
                    final com.sphere.components.fileexplorerincludes.FlatFileChooser chooser =
                        new com.sphere.components.fileexplorerincludes.FlatFileChooser(window,
                            new java.io.File(System.getProperty("user.dir")));
                    if (chooser.showDialog() == com.sphere.components.fileexplorerincludes.FlatFileChooser.APPROVE_OPTION) {
                        final List<java.io.File> chosen = chooser.getSelectedFiles();
                        if (chosen != null && !chosen.isEmpty()) {
                            field.setText(chosen.get(0).getAbsolutePath().replace('\\', '/'));
                        }
                    }
                });
                final JPanel line = VaultLook.transparent(new JPanel(new BorderLayout(4, 0)));
                line.add(field, BorderLayout.CENTER);
                line.add(browse, BorderLayout.EAST);
                return line;
            }
        }
    }

    private static String tip(VaultParam p) {
        final StringBuilder t = new StringBuilder("<html><b>").append(escape(p.label())).append("</b>");
        if (!p.cppType().isEmpty() && !p.cppType().equals("count")) t.append("  <i>").append(escape(p.cppType())).append("</i>");
        else t.append("  <i>").append(p.type().name().toLowerCase(Locale.ROOT)).append("</i>");
        if (!p.defaultValue().isEmpty()) t.append("<br>default: ").append(escape(p.defaultValue()));
        if (!p.help().isEmpty()) t.append("<br>").append(escape(p.help()));
        return t.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static boolean hasOption(String options, String option) {
        return (" " + options.strip() + " ").contains(" " + option + " ");
    }

    static String toggleOption(String options, String option) {
        final String padded = " " + options.strip() + " ";
        if (padded.contains(" " + option + " ")) {
            return padded.replace(" " + option + " ", " ").strip();
        }
        return (options.strip() + " " + option).strip();
    }

    private static void onChange(javax.swing.text.JTextComponent field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { action.run(); }
            @Override public void removeUpdate(DocumentEvent e) { action.run(); }
            @Override public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }

    private static JLabel label(String text, String tip) {
        final JLabel label = new JLabel(text);
        label.setFont(VaultLook.ui(Font.PLAIN, 11f));
        label.setForeground(VaultLook.muted());
        label.setToolTipText(tip);
        return label;
    }

    private static JLabel section(String text) {
        final JLabel label = new JLabel(text);
        label.setFont(VaultLook.ui(Font.BOLD, 10f));
        label.setForeground(VaultLook.faint());
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 3, 0));
        return label;
    }

    private static GridBagConstraints gbc(int x, int y, int width, double weight) {
        final GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = width;
        c.weightx = weight;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        return c;
    }

    private static GridBagConstraints gbcField(int x, int y) {
        final GridBagConstraints c = gbc(x, y, 1, 1);
        c.insets = new Insets(2, 0, 2, 0);
        return c;
    }

    // ---- code -----------------------------------------------------------------------------

    private void loadCode() {
        if (item == null || item.path.toString().equals(codeFor)) return;
        codeFor = item.path.toString();
        String text;
        try {
            text = item.size > VaultAnalyzer.MAX_READ ? null : Files.readString(item.path, StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException unreadable) {
            text = null;
        }
        final StyledDocument doc = code.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            if (text == null) {
                doc.insertString(0, "(not readable as text)", null);
                return;
            }
            final String[] lines = text.split("\\R", -1);
            if (lines.length > CODE_LINES) {
                text = String.join("\n", java.util.Arrays.copyOf(lines, CODE_LINES))
                    + "\n... " + (lines.length - CODE_LINES) + " more lines";
            } else {
                text = String.join("\n", lines);
            }
            final SimpleAttributeSet base = new SimpleAttributeSet();
            StyleConstants.setForeground(base, EditorTheme.foreground());
            doc.insertString(0, text, base);
            final SyntaxTokenizer tokenizer = new SyntaxTokenizer(item.info.language);
            for (SyntaxTokenizer.Span span : tokenizer.tokenizeAll(text)) {
                final Color color = EditorTheme.token(span.kind);
                if (color == null) continue;
                final SimpleAttributeSet style = new SimpleAttributeSet();
                StyleConstants.setForeground(style, color);
                if (span.kind == com.sphere.components.editor.TokenKind.COMMENT) StyleConstants.setItalic(style, true);
                doc.setCharacterAttributes(span.start, Math.max(0, span.end - span.start), style, false);
            }
            code.setCaretPosition(0);
        } catch (javax.swing.text.BadLocationException | RuntimeException failed) {
            // A preview only: the editor shows the file whatever happens here.
        }
    }

    /** Code shown as the editor shows it, its lines not wrapped. */
    private static final class CodeView extends JTextPane {
        CodeView() {
            setEditable(false);
            setFont(VaultLook.mono(11.5f));
            setBackground(EditorTheme.background());
            setForeground(EditorTheme.foreground());
            setSelectionColor(EditorTheme.selection());
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() == null || getUI().getPreferredSize(this).width <= getParent().getWidth();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            graphics.setColor(EditorTheme.background());
            graphics.fillRect(0, 0, getWidth(), getHeight());
            getUI().paint(graphics, this);
        }
    }

    // ---- info ---------------------------------------------------------------------------------

    private void buildInfo() {
        infoPanel.removeAll();
        infoPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        final VaultItem it = item;
        final VaultAnalysis info = it.info;
        final VaultState state = actions.index().state();
        int row = 0;
        row = fact(row, "Folder", (it.layer == VaultItem.Layer.PROJECT ? "project " + actions.project() : "global")
            + "  ·  " + it.root);
        row = fact(row, "Size", VaultLook.bytes(it.size) + "  ·  " + info.lines + " lines");
        row = fact(row, "Changed", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(it.modified))
            + "  (" + VaultLook.ago(it.modified) + ")");
        row = fact(row, "Language", info.language.name
            + (info.declared != info.language ? "  (named " + info.declared.name + ")" : "")
            + (info.sniffed != null && info.sniffed == info.language ? "  · confirmed by its content" : ""));
        final String prefix = VaultRunner.prefix(it);
        row = fact(row, "Runs with", prefix == null ? "nothing" : it.isRootMacro()
            ? ":root .x (engine) or ::root (process)" : prefix);
        if (info.entry != null) row = fact(row, "Entry", info.entry + "()  ·  what .x calls");
        if (info.unnamed) row = fact(row, "Entry", "an unnamed block { ... }, run as it is");
        final int runs = state.runs(it.key());
        row = fact(row, "Runs", runs == 0 ? "never from the vault"
            : runs + "  ·  last " + VaultLook.ago(state.lastRun(it.key())) + " ago");
        if (!info.functions.isEmpty()) row = fact(row, "Defines", String.join(", ", info.functions));
        if (!info.uses.isEmpty()) row = fact(row, "Uses", String.join(", ", info.uses));
        if (!info.defaultOptions.isEmpty()) row = fact(row, "@opts", info.defaultOptions);
        if (info.runner != null) row = fact(row, "@runner", info.runner);
        row = fact(row, "Content", info.hash == 0 ? "empty" : String.format("%016x", info.hash));

        boolean hints = false;
        for (Issue issue : info.issues) {
            if (issue.severity() != VaultAnalysis.Severity.INFO) continue;
            if (!hints) infoPanel.add(section("Hints"), gbc(0, row++, 2, 1));
            hints = true;
            infoPanel.add(issueRow(it, issue), gbc(0, row++, 2, 1));
        }

        final List<VaultItem> twins = actions.index().twinsOf(it);
        if (!twins.isEmpty()) {
            infoPanel.add(section("Same content"), gbc(0, row++, 2, 1));
            for (VaultItem twin : twins) {
                final VaultLook.FlatButton b = VaultLook.button(twin.kind.folder + "/" + twin.relative, "twin",
                    "Show it", false);
                b.setHorizontalAlignment(JLabel.LEFT);
                b.addActionListener(e -> actions.select(twin));
                infoPanel.add(b, gbc(0, row++, 2, 1));
            }
        }
        infoPanel.add(section("Metadata it can carry"), gbc(0, row++, 2, 1));
        infoPanel.add(wrapping("In its first comment block: a sentence for the description, and\n"
                + "  @tags plot, mg5      @desc one line      @opts -u\n"
                + "  @arg n:int=10 help   @runner py",
            VaultLook.mono(10f), VaultLook.faint()), gbc(0, row, 2, 1));
        infoPanel.revalidate();
        infoPanel.repaint();
        relayout(infoPanel);
    }

    private int fact(int row, String key, String value) {
        final JLabel k = new JLabel(key);
        k.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
        k.setForeground(VaultLook.faint());
        k.setVerticalAlignment(JLabel.TOP);
        final GridBagConstraints kc = gbc(0, row, 1, 0);
        kc.anchor = GridBagConstraints.NORTHWEST;
        infoPanel.add(k, kc);
        infoPanel.add(wrapping(value, VaultLook.ui(Font.PLAIN, 11f), VaultLook.text()), gbcField(1, row));
        return row + 1;
    }

    // ---- actions ------------------------------------------------------------------------------

    private void buildActions() {
        actionBar.removeAll();
        final VaultItem it = item;
        runButton = VaultLook.button("Run", "play", "Run now (Enter)", true);
        runButton.addActionListener(e -> actions.run(it, request));
        final VaultLook.FlatButton compose = VaultLook.button("Compose", "compose",
            "Write the line into the console, to read or change before Enter (Ctrl+Enter)", false);
        compose.addActionListener(e -> actions.compose(it, request));
        final VaultLook.FlatButton edit = VaultLook.button("", "edit", "Open in the editor (Ctrl+E)", false);
        edit.addActionListener(e -> actions.edit(it));
        final VaultLook.FlatButton more = VaultLook.button("", "more", "More", false);
        more.addActionListener(e -> actions.more(it, more, 0, more.getHeight()));
        if (VaultRunner.runnable(it)) {
            actionBar.add(runButton);
            actionBar.add(Box.createHorizontalStrut(4));
        }
        actionBar.add(compose);
        actionBar.add(Box.createHorizontalGlue());
        actionBar.add(edit);
        actionBar.add(more);
        updatePreview();
        actionBar.revalidate();
        actionBar.repaint();
    }

    // ---- the tab strip -------------------------------------------------------------------------

    private final class TabStrip extends JComponent {
        TabStrip() {
            setPreferredSize(new Dimension(10, 26));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int x = 10;
                    final FontMetrics fm = getFontMetrics(VaultLook.ui(Font.BOLD, 11f));
                    for (int i = 0; i < TABS.length; i++) {
                        final int w = fm.stringWidth(TABS[i]) + 18;
                        if (e.getX() >= x && e.getX() < x + w) {
                            select(i);
                            return;
                        }
                        x += w;
                    }
                }
            });
        }

        private void select(int index) {
            tab = index;
            ((CardLayout) cards.getLayout()).show(cards, TABS[index]);
            if (index == 1) loadCode();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                VaultLook.antialias(g);
                g.setFont(VaultLook.ui(Font.BOLD, 11f));
                final FontMetrics fm = g.getFontMetrics();
                int x = 10;
                for (int i = 0; i < TABS.length; i++) {
                    final int w = fm.stringWidth(TABS[i]) + 18;
                    final boolean on = i == tab;
                    g.setColor(on ? VaultLook.text() : VaultLook.faint());
                    g.drawString(TABS[i], x + 9, 17);
                    if (on) {
                        g.setColor(VaultLook.accent());
                        g.fillRoundRect(x + 6, getHeight() - 3, w - 12, 3, 3, 3);
                    }
                    x += w;
                }
                g.setColor(VaultLook.mix(VaultLook.text(), VaultLook.panel(), 0.1f));
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            } finally {
                g.dispose();
            }
        }
    }

    /** The family badge, at the size of the header. */
    private static final class Badge extends JComponent {
        private final String family;

        Badge(String family) {
            this.family = family;
            setPreferredSize(new Dimension(Math.max(30, family.length() * 7 + 12), 20));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                VaultLook.antialias(g);
                VaultLook.badge(g, family, 0, 1, 18);
            } finally {
                g.dispose();
            }
        }
    }
}
