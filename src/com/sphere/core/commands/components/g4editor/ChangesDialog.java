package com.sphere.components.g4editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The files changed and not saved -- typed, or written from the drawing: a volume moved, turned, drawn,
 * deleted, a characteristic changed in the properties, G4editor's volumes brought in -- each with what
 * changed against the file as saved. Nothing is written until the user says which to save.
 */
final class ChangesDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    enum Choice { SAVE, DISCARD, CANCEL }

    /** A file, as saved and as it is now. */
    record Change(Path file, String saved, String now) {
    }

    /** What was chosen, and the files to save when it is SAVE. */
    record Result(Choice choice, Set<Path> save) {
    }

    private final boolean[] chosen;
    private final JList<Change> list;
    private final JEditorPane diff = new JEditorPane();
    private final JButton save;
    private Choice choice = Choice.CANCEL;

    private ChangesDialog(Window owner, String title, String question, List<Change> changes, String saveLabel,
                          String discardLabel) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.chosen = new boolean[changes.size()];
        java.util.Arrays.fill(chosen, true);

        final JLabel ask = new JLabel("<html>" + escape(question) + "</html>");
        ask.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        final DefaultListModel<Change> m = new DefaultListModel<>();
        changes.forEach(m::addElement);
        list = new JList<>(m);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                // a click on the box ticks it; elsewhere it shows the file's changes
                if (i >= 0 && e.getX() < 26) {
                    toggle(i);
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        list.getActionMap().put("toggle", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            public void actionPerformed(ActionEvent e) {
                if (list.getSelectedIndex() >= 0) {
                    toggle(list.getSelectedIndex());
                }
            }
        });
        list.addListSelectionListener(e -> show(list.getSelectedValue()));

        diff.setEditable(false);
        diff.setContentType("text/html");
        diff.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        diff.setOpaque(true);
        diff.setBackground(isDark() ? new Color(0x1E1F22) : Color.WHITE);
        ask.setForeground(foreground());

        final JScrollPane left = new JScrollPane(list);
        left.setMinimumSize(new Dimension(160, 80));
        final JScrollPane right = new JScrollPane(diff);
        right.setMinimumSize(new Dimension(200, 80));
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(260);
        split.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        save = new JButton(saveLabel);
        save.addActionListener(e -> finish(Choice.SAVE));
        final JButton discard = new JButton(discardLabel);
        discard.setToolTipText("Leaves the files on disk as they were; the changes are not written");
        discard.addActionListener(e -> finish(Choice.DISCARD));
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> finish(Choice.CANCEL));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.add(save);
        buttons.add(discard);
        buttons.add(cancel);

        final JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(true);
        setContentPane(content);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(ask, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> finish(Choice.CANCEL), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(980, 600);
        setLocationRelativeTo(owner);
        list.setSelectedIndex(0);
        updateSave();
    }

    /**
     * Asks which of the changed files to save. Returns null when nothing changed (nothing to ask).
     * DISCARD leaves every file as it is on disk; SAVE names the ticked ones.
     */
    static Result ask(Component parent, String title, String question, List<Change> changes, String saveLabel,
                      String discardLabel) {
        if (changes.isEmpty()) {
            return null;
        }
        final Window owner = parent instanceof Window w ? w : parent == null ? null
            : javax.swing.SwingUtilities.getWindowAncestor(parent);
        final ChangesDialog d = new ChangesDialog(owner, title, question, changes, saveLabel, discardLabel);
        d.setVisible(true);
        final Set<Path> files = new LinkedHashSet<>();
        for (int i = 0; i < changes.size(); i++) {
            if (d.chosen[i]) {
                files.add(changes.get(i).file());
            }
        }
        return new Result(d.choice, files);
    }

    private void finish(Choice c) {
        choice = c;
        dispose();
    }

    private void toggle(int i) {
        chosen[i] = !chosen[i];
        list.repaint();
        updateSave();
    }

    private void updateSave() {
        int n = 0;
        for (boolean b : chosen) {
            n += b ? 1 : 0;
        }
        save.setEnabled(n > 0);
    }

    private void show(Change c) {
        if (c == null) {
            diff.setText("");
            return;
        }
        diff.setText(html(c));
        diff.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    private final class Row implements ListCellRenderer<Change> {
        private final JPanel panel = new JPanel(new BorderLayout(6, 0));
        private final JCheckBox box = new JCheckBox();
        private final JLabel name = new JLabel();
        private final JLabel count = new JLabel();

        Row() {
            box.setOpaque(false);
            panel.add(box, BorderLayout.WEST);
            panel.add(name, BorderLayout.CENTER);
            panel.add(count, BorderLayout.EAST);
            panel.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 8));
            count.setFont(count.getFont().deriveFont(11f));
        }

        public Component getListCellRendererComponent(JList<? extends Change> l, Change c, int index, boolean selected,
                                                      boolean focus) {
            box.setSelected(chosen[index]);
            name.setText(c.file().getFileName().toString());
            name.setToolTipText(c.file().toString());
            final int[] n = counts(lines(c.saved()), lines(c.now()));
            count.setText("+" + n[0] + "  −" + n[1]);
            panel.setOpaque(selected);
            panel.setBackground(l.getSelectionBackground());
            name.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
            count.setForeground(selected ? l.getSelectionForeground() : muted());
            return panel;
        }
    }

    // ------------------------------------------------------------------
    // The changes, line by line
    // ------------------------------------------------------------------

    /** One line of a comparison: ' ' kept, '-' removed, '+' added; numbers in the old and new texts (from 1). */
    record Line(char op, String text, int oldNo, int newNo) {
    }

    static String[] lines(String s) {
        return s == null || s.isEmpty() ? new String[0] : s.replace("\r\n", "\n").split("\n", -1);
    }

    /**
     * The lines of the new text against the old: the common head and tail kept, the middle compared by
     * longest common subsequence (a middle too big for that is shown removed, then added).
     */
    static List<Line> diff(String[] a, String[] b) {
        int head = 0;
        while (head < a.length && head < b.length && a[head].equals(b[head])) {
            head++;
        }
        int tail = 0;
        while (tail < a.length - head && tail < b.length - head && a[a.length - 1 - tail].equals(b[b.length - 1 - tail])) {
            tail++;
        }
        final List<Line> out = new ArrayList<>();
        for (int i = 0; i < head; i++) {
            out.add(new Line(' ', a[i], i + 1, i + 1));
        }
        final int n = a.length - head - tail, m = b.length - head - tail;
        if ((long) n * m > 4_000_000L) {
            for (int i = 0; i < n; i++) {
                out.add(new Line('-', a[head + i], head + i + 1, -1));
            }
            for (int j = 0; j < m; j++) {
                out.add(new Line('+', b[head + j], -1, head + j + 1));
            }
        } else {
            final int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = a[head + i].equals(b[head + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a[head + i].equals(b[head + j])) {
                    out.add(new Line(' ', a[head + i], head + i + 1, head + j + 1));
                    i++;
                    j++;
                } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                    out.add(new Line('+', b[head + j], -1, head + j + 1));
                    j++;
                } else {
                    out.add(new Line('-', a[head + i], head + i + 1, -1));
                    i++;
                }
            }
        }
        for (int k = tail; k > 0; k--) {
            out.add(new Line(' ', a[a.length - k], a.length - k + 1, b.length - k + 1));
        }
        return out;
    }

    /** Lines added and removed. */
    static int[] counts(String[] a, String[] b) {
        int add = 0, del = 0;
        for (Line l : diff(a, b)) {
            add += l.op() == '+' ? 1 : 0;
            del += l.op() == '-' ? 1 : 0;
        }
        return new int[] {add, del};
    }

    private static String html(Change c) {
        final List<Line> all = diff(lines(c.saved()), lines(c.now()));
        final boolean dark = isDark();
        final String fg = hex(dark ? new Color(0xD7DCE2) : new Color(0x1F2328), "#1f2328");
        final String addBg = dark ? "#1f3d29" : "#e6f6ea", delBg = dark ? "#4a2125" : "#fde8e8";
        final String hunkFg = dark ? "#8fa7c7" : "#57606a";
        final StringBuilder b = new StringBuilder("<html><body style='font-family:monospace;font-size:11px;color:")
            .append(fg).append("'>");
        b.append("<div style='font-family:sans-serif;font-size:11px;color:").append(hunkFg).append(";padding:4px'>")
            .append(escape(c.file().toString())).append("</div>");
        // three lines of context around each change
        final boolean[] show = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).op() != ' ') {
                for (int k = Math.max(0, i - 3); k <= Math.min(all.size() - 1, i + 3); k++) {
                    show[k] = true;
                }
            }
        }
        boolean gap = true;
        for (int i = 0; i < all.size(); i++) {
            if (!show[i]) {
                gap = true;
                continue;
            }
            final Line l = all.get(i);
            if (gap) {
                b.append("<div style='color:").append(hunkFg).append(";padding:6px 4px 2px 4px'>@@ line ")
                    .append(l.newNo() > 0 ? l.newNo() : l.oldNo()).append("</div>");
                gap = false;
            }
            final String bg = l.op() == '+' ? addBg : l.op() == '-' ? delBg : null;
            b.append("<div style='white-space:pre;padding:0 4px").append(bg == null ? "" : ";background:" + bg).append("'>");
            b.append(String.format("%5s %5s %s ", l.oldNo() > 0 ? l.oldNo() : "", l.newNo() > 0 ? l.newNo() : "",
                l.op()).replace(" ", "&nbsp;"));
            b.append(escape(l.text()).replace(" ", "&nbsp;").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;"));
            b.append("</div>");
        }
        if (all.stream().noneMatch(l -> l.op() != ' ')) {
            b.append("<div style='padding:6px 4px'>Only the line endings or the file's encoding differ.</div>");
        }
        return b.append("</body></html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean isDark() {
        final Color c = UIManager.getColor("Panel.background");
        return c != null && (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 110;
    }

    /** The text colour against the dialog's own background, whatever the theme gives labels. */
    private static Color foreground() {
        final Color bg = UIManager.getColor("Panel.background");
        final boolean darkBg = bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 110;
        return darkBg ? new Color(0xD7DCE2) : new Color(0x1F2328);
    }

    private static Color muted() {
        return isDark() ? new Color(0x9AA5B1) : new Color(0x6E7781);
    }

    private static String hex(Color c, String fallback) {
        return c == null ? fallback : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
