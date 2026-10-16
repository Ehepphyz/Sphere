package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find and replace floating over the top right of the code, as in VS Code:
 * matches highlight as you type, the count says where you are, Enter and
 * Shift+Enter walk them, Alt+C / Alt+W / Alt+R switch case, whole word and
 * regular expressions, and Replace All is a single undo step.
 */
public final class FindWidget extends EditorWidgets.Card {

    private static final int MAX_MATCHES = 20_000;

    private final CodeTextPane pane;
    private final SyntaxHighlighter syntax;
    private final EditorWidgets.FlatField findField = new EditorWidgets.FlatField("Find");
    private final EditorWidgets.FlatField replaceField = new EditorWidgets.FlatField("Replace");
    private final EditorWidgets.FlatToggle matchCase = new EditorWidgets.FlatToggle("Aa", "Match Case (Alt+C)");
    private final EditorWidgets.FlatToggle wholeWord = new EditorWidgets.FlatToggle("ab", "Match Whole Word (Alt+W)");
    private final EditorWidgets.FlatToggle regex = new EditorWidgets.FlatToggle(".*", "Use Regular Expression (Alt+R)");
    private final EditorWidgets.FlatToggle inSelection = new EditorWidgets.FlatToggle("≡", "Find in Selection (Alt+L)");
    private final JLabel count = new JLabel("No results");
    private final EditorWidgets.FlatButton toggleReplace;
    private final JPanel replaceRow = new JPanel();
    private final Timer research;

    private int[] starts = new int[0];
    private int[] ends = new int[0];
    private int matches;
    private int current = -1;
    private Pattern pattern;
    private int scopeStart = -1;
    private int scopeEnd = -1;

    public FindWidget(CodeTextPane pane, SyntaxHighlighter syntax) {
        super(new BorderLayout(0, 4), 8, 8);
        this.pane = pane;
        this.syntax = syntax;
        this.research = new Timer(120, e -> search(false));
        this.research.setRepeats(false);
        setVisible(false);

        toggleReplace = new EditorWidgets.FlatButton(
                EditorIcons.chevron(14, EditorTheme::foreground, false), "Toggle Replace (Ctrl+H)");
        toggleReplace.addActionListener(e -> setReplaceVisible(!replaceRow.isVisible()));

        EditorWidgets.FlatButton prev = new EditorWidgets.FlatButton(
                EditorIcons.arrow(14, EditorTheme::foreground, true), "Previous Match (Shift+Enter)");
        prev.addActionListener(e -> step(-1));
        EditorWidgets.FlatButton next = new EditorWidgets.FlatButton(
                EditorIcons.arrow(14, EditorTheme::foreground, false), "Next Match (Enter)");
        next.addActionListener(e -> step(1));
        EditorWidgets.FlatButton close = new EditorWidgets.FlatButton(
                EditorIcons.close(14, EditorTheme::foreground), "Close (Escape)");
        close.addActionListener(e -> close());

        count.setFont(EditorWidgets.uiFont(Font.PLAIN, 12));
        count.setPreferredSize(new Dimension(86, 20));
        count.setHorizontalAlignment(JLabel.CENTER);

        findField.setColumns(18);
        replaceField.setColumns(18);

        JPanel findRow = row();
        findRow.add(findField);
        findRow.add(Box.createHorizontalStrut(4));
        findRow.add(matchCase);
        findRow.add(wholeWord);
        findRow.add(regex);
        findRow.add(Box.createHorizontalStrut(4));
        findRow.add(count);
        findRow.add(prev);
        findRow.add(next);
        findRow.add(inSelection);
        findRow.add(close);

        replaceRow.setLayout(new BoxLayout(replaceRow, BoxLayout.X_AXIS));
        replaceRow.setOpaque(false);
        EditorWidgets.FlatButton replaceOne = new EditorWidgets.FlatButton("Replace", "Replace (Ctrl+Shift+1)");
        replaceOne.addActionListener(e -> replaceCurrent());
        EditorWidgets.FlatButton replaceAll = new EditorWidgets.FlatButton("All", "Replace All (Ctrl+Alt+Enter)");
        replaceAll.addActionListener(e -> replaceAll());
        replaceRow.add(replaceField);
        replaceRow.add(Box.createHorizontalStrut(6));
        replaceRow.add(replaceOne);
        replaceRow.add(replaceAll);
        replaceRow.add(Box.createHorizontalGlue());
        replaceRow.setVisible(false);

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.add(findRow);
        rows.add(Box.createVerticalStrut(4));
        rows.add(replaceRow);

        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(toggleReplace, BorderLayout.NORTH);
        add(left, BorderLayout.WEST);
        add(rows, BorderLayout.CENTER);

        for (EditorWidgets.FlatToggle t : new EditorWidgets.FlatToggle[] { matchCase, wholeWord, regex, inSelection }) {
            t.addActionListener(e -> {
                if (t == inSelection) {
                    captureScope();
                }
                search(false);
                findField.requestFocusInWindow();
            });
        }

        DocumentListener retype = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { research.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { research.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        };
        findField.getDocument().addDocumentListener(retype);
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { if (isVisible()) research.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { if (isVisible()) research.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        keys(findField);
        keys(replaceField);
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find-next", () -> step(1));
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "find-prev", () -> step(-1));
        bind(replaceField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "replace-one", this::replaceCurrent);
        bind(replaceField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
             "replace-all", this::replaceAll);
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
             "replace-all", this::replaceAll);
    }

    private static JPanel row() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setOpaque(false);
        return p;
    }

    private void keys(JComponent c) {
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find-close", this::close);
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK), "find-case", () -> flip(matchCase));
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.ALT_DOWN_MASK), "find-word", () -> flip(wholeWord));
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK), "find-regex", () -> flip(regex));
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.ALT_DOWN_MASK), "find-scope", () -> {
            inSelection.setSelected(!inSelection.isSelected());
            captureScope();
            search(false);
        });
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "find-f3", () -> step(1));
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "find-f3s", () -> step(-1));
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
             "replace-one-key", this::replaceCurrent);
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "find-toggle-replace",
             () -> setReplaceVisible(!replaceRow.isVisible()));
        bind(c, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "find-tab", () -> {
            if (c == findField && replaceRow.isVisible()) {
                replaceField.requestFocusInWindow();
            } else {
                findField.requestFocusInWindow();
            }
        });
    }

    private void flip(EditorWidgets.FlatToggle t) {
        t.setSelected(!t.isSelected());
        search(false);
    }

    private static void bind(JComponent c, KeyStroke ks, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void setReplaceVisible(boolean visible) {
        replaceRow.setVisible(visible);
        toggleReplace.setIcon(EditorIcons.chevron(14, EditorTheme::foreground, visible));
        revalidate();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    // ---- Opening and closing ----------------------------------------------

    /** Ctrl+F (replace false) or Ctrl+H (replace true). */
    public void open(boolean replace) {
        String sel = pane.getSelectedText();
        if (sel != null && !sel.isEmpty() && sel.indexOf('\n') < 0 && sel.length() < 200) {
            findField.setText(regex.isSelected() ? Pattern.quote(sel) : sel);
        } else if (sel != null && sel.indexOf('\n') >= 0 && !inSelection.isSelected()) {
            // A multi-line selection is searched inside rather than for.
            inSelection.setSelected(true);
            captureScope();
        }
        setReplaceVisible(replace);
        setVisible(true);
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
        SwingUtilities.invokeLater(() -> {
            JComponent target = replace && !findField.getText().isEmpty() ? replaceField : findField;
            target.requestFocusInWindow();
            findField.selectAll();
        });
        search(true);
    }

    public void close() {
        if (!isVisible()) {
            return;
        }
        setVisible(false);
        pane.clearFindMatches();
        inSelection.setSelected(false);
        scopeStart = scopeEnd = -1;
        if (getParent() != null) {
            getParent().repaint();
        }
        pane.requestFocusInWindow();
    }

    public boolean isOpen() {
        return isVisible();
    }

    private void captureScope() {
        if (inSelection.isSelected() && pane.getSelectionStart() != pane.getSelectionEnd()) {
            scopeStart = pane.getSelectionStart();
            scopeEnd = pane.getSelectionEnd();
        } else if (!inSelection.isSelected()) {
            scopeStart = scopeEnd = -1;
        }
    }

    // ---- Searching ---------------------------------------------------------

    private Pattern compile() {
        String q = findField.getText();
        if (q.isEmpty()) {
            return null;
        }
        int flags = Pattern.MULTILINE | (matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        String body = regex.isSelected() ? q : Pattern.quote(q);
        if (wholeWord.isSelected()) {
            body = "(?<![\\w])(?:" + body + ")(?![\\w])";
        }
        return Pattern.compile(body, flags);
    }

    /** Recomputes the matches; on open, the current one is the first after the caret. */
    private void search(boolean fromCaret) {
        research.stop();
        Document doc = pane.getDocument();
        String text;
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            return;
        }
        try {
            pattern = compile();
            findField.setInvalid(false);
        } catch (PatternSyntaxException ex) {
            pattern = null;
            findField.setInvalid(true);
            count.setText("Invalid regex");
            count.setForeground(EditorTheme.errorSquiggle());
            pane.clearFindMatches();
            return;
        }
        if (pattern == null) {
            matches = 0;
            current = -1;
            count.setText("No results");
            count.setForeground(EditorTheme.textMuted());
            pane.clearFindMatches();
            return;
        }
        int from = scopeStart >= 0 ? Math.min(scopeStart, text.length()) : 0;
        int to = scopeEnd >= 0 ? Math.min(scopeEnd, text.length()) : text.length();
        Matcher m = pattern.matcher(text);
        m.region(from, to);
        int[] s = new int[64];
        int[] e = new int[64];
        int n = 0;
        while (m.find() && n < MAX_MATCHES) {
            if (m.end() == m.start()) {
                // An empty match (a lookahead, ^) would loop; step over it.
                if (m.end() >= to) {
                    break;
                }
                m.region(m.end() + 1, to);
                continue;
            }
            if (n == s.length) {
                s = Arrays.copyOf(s, n * 2);
                e = Arrays.copyOf(e, n * 2);
            }
            s[n] = m.start();
            e[n] = m.end();
            n++;
        }
        starts = s;
        ends = e;
        matches = n;
        int caret = fromCaret ? pane.getSelectionStart() : (current >= 0 && current < n ? s[current] : pane.getSelectionStart());
        current = -1;
        for (int i = 0; i < n; i++) {
            if (s[i] >= caret) {
                current = i;
                break;
            }
        }
        if (current < 0 && n > 0) {
            current = 0;
        }
        pane.setFindMatches(starts, ends, matches, current);
        updateCount();
        if (fromCaret && current >= 0) {
            reveal(current, false);
        }
    }

    private void updateCount() {
        if (matches == 0) {
            count.setText("No results");
            count.setForeground(findField.getText().isEmpty() ? EditorTheme.textMuted() : EditorTheme.errorSquiggle());
        } else {
            count.setText((current + 1) + " of " + matches + (matches >= MAX_MATCHES ? "+" : ""));
            count.setForeground(EditorTheme.foreground());
        }
    }

    /** F3 / Shift+F3 from the editor: open with the last query when closed. */
    public void step(int direction) {
        if (!isVisible()) {
            if (findField.getText().isEmpty()) {
                open(false);
                return;
            }
            setVisible(true);
            if (getParent() != null) {
                getParent().revalidate();
            }
            search(true);
        }
        if (matches == 0) {
            return;
        }
        int caret = pane.getSelectionStart();
        if (current >= 0 && current < matches && starts[current] == pane.getSelectionStart()
                && ends[current] == pane.getSelectionEnd()) {
            current = Math.floorMod(current + direction, matches);
        } else if (direction > 0) {
            current = 0;
            for (int i = 0; i < matches; i++) {
                if (starts[i] >= caret) {
                    current = i;
                    break;
                }
            }
        } else {
            current = matches - 1;
            for (int i = matches - 1; i >= 0; i--) {
                if (starts[i] < caret) {
                    current = i;
                    break;
                }
            }
        }
        pane.setFindMatches(starts, ends, matches, current);
        updateCount();
        reveal(current, true);
    }

    private void reveal(int index, boolean select) {
        int s = starts[index];
        int e = ends[index];
        if (select) {
            pane.setCaretPosition(s);
            pane.moveCaretPosition(e);
        }
        try {
            Rectangle2D r = pane.modelToView2D(s);
            if (r != null) {
                Rectangle visible = pane.getVisibleRect();
                Rectangle target = r.getBounds();
                if (!visible.contains(target)) {
                    int y = Math.max(0, target.y - visible.height / 3);
                    pane.scrollRectToVisible(new Rectangle(target.x, y, target.width, visible.height));
                }
            }
        } catch (BadLocationException ignored) {
            // the match moved; it is found again on the next search
        }
    }

    // ---- Replacing ---------------------------------------------------------

    private String replacementFor(String matched) {
        String rep = replaceField.getText();
        if (!regex.isSelected() || pattern == null) {
            return rep;
        }
        try {
            Matcher m = pattern.matcher(matched);
            if (m.matches()) {
                StringBuilder sb = new StringBuilder();
                m.appendReplacement(sb, rep.replace("\\n", "\n").replace("\\t", "\t"));
                return sb.toString();
            }
        } catch (RuntimeException ex) {
            return rep;
        }
        return rep;
    }

    private void replaceCurrent() {
        if (matches == 0 || current < 0) {
            return;
        }
        int s = starts[current];
        int e = ends[current];
        if (pane.getSelectionStart() != s || pane.getSelectionEnd() != e) {
            // First press selects the match, as VS Code does.
            pane.setFindMatches(starts, ends, matches, current);
            reveal(current, true);
            return;
        }
        pane.atomic(() -> {
            try {
                Document doc = pane.getDocument();
                String matched = doc.getText(s, e - s);
                String rep = replacementFor(matched);
                doc.remove(s, e - s);
                doc.insertString(s, rep, null);
                pane.setCaretPosition(s + rep.length());
            } catch (BadLocationException ignored) {
                // the match moved
            }
        });
        search(false);
        if (matches > 0) {
            step(1);
        }
    }

    private void replaceAll() {
        if (matches == 0) {
            return;
        }
        final int total = matches;
        final int[] s = Arrays.copyOf(starts, matches);
        final int[] e = Arrays.copyOf(ends, matches);
        if (syntax != null) {
            syntax.setSuspended(true);
        }
        try {
            pane.atomic(() -> {
                try {
                    Document doc = pane.getDocument();
                    for (int i = total - 1; i >= 0; i--) {
                        String matched = doc.getText(s[i], e[i] - s[i]);
                        String rep = replacementFor(matched);
                        doc.remove(s[i], e[i] - s[i]);
                        doc.insertString(s[i], rep, null);
                    }
                } catch (BadLocationException ignored) {
                    // stop at the first match that moved
                }
            });
        } finally {
            if (syntax != null) {
                syntax.setSuspended(false);
            }
        }
        search(false);
        count.setText(total + " replaced");
        count.setForeground(EditorTheme.added());
    }

    public void refreshTheme() {
        updateCount();
        repaint();
    }
}
