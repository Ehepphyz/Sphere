package com.sphere.components.editor;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Line and selection commands: comment toggling, moving, duplicating, joining,
 * sorting lines, indenting a block and growing the selection by syntax. Each
 * one is a single undo step.
 */
public final class EditorActions {

    private static final String SELECTION_STACK = "sphere.selectionStack";

    private EditorActions() {
    }

    // ---- Geometry ----------------------------------------------------------

    /** {first, last} lines touched by the selection; a selection ending at column 0 stops before. */
    static int[] selectedLines(JTextComponent c) {
        Element root = c.getDocument().getDefaultRootElement();
        int s = c.getSelectionStart();
        int e = c.getSelectionEnd();
        int first = root.getElementIndex(s);
        int last = root.getElementIndex(e);
        if (e > s && last > first && root.getElement(last).getStartOffset() == e) {
            last--;
        }
        return new int[] { first, last };
    }

    private static int lineStart(Document doc, int line) {
        return doc.getDefaultRootElement().getElement(line).getStartOffset();
    }

    /** End of the line's content, before its newline. */
    private static int lineEnd(Document doc, int line) {
        Element el = doc.getDefaultRootElement().getElement(line);
        return Math.min(el.getEndOffset() - 1, doc.getLength());
    }

    private static String[] lines(Document doc, int first, int last) throws BadLocationException {
        String[] out = new String[last - first + 1];
        for (int i = first; i <= last; i++) {
            int s = lineStart(doc, i);
            out[i - first] = doc.getText(s, lineEnd(doc, i) - s);
        }
        return out;
    }

    private static void atomic(JTextComponent c, Runnable r) {
        if (c instanceof CodeTextPane p) {
            p.atomic(r);
        } else {
            r.run();
        }
    }

    /** Replaces [start, end) and selects [selStart, selEnd) in the new text. */
    private static void replace(JTextComponent c, int start, int end, String text, int selStart, int selEnd)
            throws BadLocationException {
        Document doc = c.getDocument();
        String old = doc.getText(start, end - start);
        if (!old.equals(text)) {
            // Keep the common head and tail so marks and positions outside the change survive.
            int head = 0;
            while (head < old.length() && head < text.length() && old.charAt(head) == text.charAt(head)) {
                head++;
            }
            int tail = 0;
            while (tail < old.length() - head && tail < text.length() - head
                   && old.charAt(old.length() - 1 - tail) == text.charAt(text.length() - 1 - tail)) {
                tail++;
            }
            doc.remove(start + head, old.length() - head - tail);
            doc.insertString(start + head, text.substring(head, text.length() - tail), null);
        }
        int max = doc.getLength();
        c.setCaretPosition(Math.max(0, Math.min(selStart, max)));
        c.moveCaretPosition(Math.max(0, Math.min(selEnd, max)));
    }

    // ---- Indentation -------------------------------------------------------

    public static void indentLines(JTextComponent c, String unit, int tabSize, boolean outdent) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int[] range = selectedLines(c);
                int start = lineStart(doc, range[0]);
                int end = lineEnd(doc, range[1]);
                String[] ls = lines(doc, range[0], range[1]);
                int selS = c.getSelectionStart();
                int selE = c.getSelectionEnd();
                boolean hadSelection = selS != selE;
                int firstDelta = 0;
                int totalDelta = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ls.length; i++) {
                    String l = ls[i];
                    String nl;
                    if (outdent) {
                        int remove = 0;
                        if (l.startsWith("\t")) {
                            remove = 1;
                        } else {
                            while (remove < l.length() && remove < tabSize && l.charAt(remove) == ' ') {
                                remove++;
                            }
                        }
                        nl = l.substring(remove);
                    } else {
                        nl = l.isBlank() && ls.length > 1 ? l : unit + l;
                    }
                    int delta = nl.length() - l.length();
                    if (i == 0) {
                        firstDelta = delta;
                    }
                    totalDelta += delta;
                    sb.append(nl);
                    if (i < ls.length - 1) {
                        sb.append('\n');
                    }
                }
                int newSelS = Math.max(start, selS + firstDelta);
                int newSelE = hadSelection ? Math.max(newSelS, selE + totalDelta) : newSelS;
                replace(c, start, end, sb.toString(), newSelS, newSelE);
            } catch (BadLocationException ignored) {
                // the selection moved under the command
            }
        });
    }

    /** Re-indents the whole buffer with tabs or spaces. */
    public static void convertIndentation(JTextComponent c, boolean toTabs, int tabSize) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int count = doc.getDefaultRootElement().getElementCount();
                for (int line = count - 1; line >= 0; line--) {
                    int s = lineStart(doc, line);
                    String text = doc.getText(s, lineEnd(doc, line) - s);
                    String ws = AutoIndenter.leadingWhitespace(text);
                    if (ws.isEmpty()) {
                        continue;
                    }
                    int cols = CodeTextPane.indentColumns(ws + "x", tabSize);
                    String rep = toTabs ? "\t".repeat(cols / tabSize) + " ".repeat(cols % tabSize)
                                        : " ".repeat(cols);
                    if (!rep.equals(ws)) {
                        doc.remove(s, ws.length());
                        doc.insertString(s, rep, null);
                    }
                }
            } catch (BadLocationException ignored) {
                // nothing converted
            }
        });
    }

    // ---- Comments ----------------------------------------------------------

    public static void toggleLineComment(CodeTextPane c) {
        LanguageSpec spec = c.getLanguage();
        String token = spec.lineComment;
        if (token == null) {
            if (spec.blockCommentOpen != null) {
                toggleBlockComment(c, true);
            }
            return;
        }
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int[] range = selectedLines(c);
                String[] ls = lines(doc, range[0], range[1]);
                boolean all = true;
                int minIndent = Integer.MAX_VALUE;
                for (String l : ls) {
                    if (l.isBlank()) {
                        continue;
                    }
                    String t = l.trim();
                    if (!t.startsWith(token)) {
                        all = false;
                    }
                    minIndent = Math.min(minIndent, AutoIndenter.leadingWhitespace(l).length());
                }
                if (minIndent == Integer.MAX_VALUE) {
                    minIndent = 0;
                }
                int selS = c.getSelectionStart();
                int selE = c.getSelectionEnd();
                boolean hadSelection = selS != selE;
                int start = lineStart(doc, range[0]);
                int end = lineEnd(doc, range[1]);
                StringBuilder sb = new StringBuilder();
                int firstDelta = 0;
                int total = 0;
                for (int i = 0; i < ls.length; i++) {
                    String l = ls[i];
                    String nl = l;
                    if (all) {
                        int at = l.indexOf(token);
                        if (at >= 0) {
                            int after = at + token.length();
                            if (after < l.length() && l.charAt(after) == ' ') {
                                after++;
                            }
                            nl = l.substring(0, at) + l.substring(after);
                        }
                    } else if (!l.isBlank() || ls.length == 1) {
                        int at = Math.min(minIndent, l.length());
                        nl = l.substring(0, at) + token + " " + l.substring(at);
                    }
                    if (i == 0) {
                        firstDelta = nl.length() - l.length();
                    }
                    total += nl.length() - l.length();
                    sb.append(nl);
                    if (i < ls.length - 1) {
                        sb.append('\n');
                    }
                }
                int caretCol = selS - start;
                int newS = caretCol <= minIndent && !all ? selS : Math.max(start, selS + firstDelta);
                int newE = hadSelection ? selE + total : newS;
                replace(c, start, end, sb.toString(), newS, Math.max(newS, newE));
            } catch (BadLocationException ignored) {
                // the selection moved under the command
            }
        });
    }

    public static void toggleBlockComment(CodeTextPane c, boolean wholeLinesIfEmpty) {
        LanguageSpec spec = c.getLanguage();
        String open = spec.blockCommentOpen;
        String close = spec.blockCommentClose;
        if (open == null) {
            toggleLineComment(c);
            return;
        }
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                if (s == e) {
                    int[] range = selectedLines(c);
                    s = lineStart(doc, range[0]);
                    e = lineEnd(doc, range[1]);
                    String t = doc.getText(s, e - s);
                    s += AutoIndenter.leadingWhitespace(t).length();
                }
                String text = doc.getText(s, e - s);
                String trimmed = text.trim();
                if (trimmed.startsWith(open) && trimmed.endsWith(close) && trimmed.length() >= open.length() + close.length()) {
                    int a = text.indexOf(open);
                    int b = text.lastIndexOf(close);
                    String inner = text.substring(a + open.length(), b);
                    if (inner.startsWith(" ")) {
                        inner = inner.substring(1);
                    }
                    if (inner.endsWith(" ")) {
                        inner = inner.substring(0, inner.length() - 1);
                    }
                    String rep = text.substring(0, a) + inner + text.substring(b + close.length());
                    replace(c, s, e, rep, s, s + rep.length());
                } else {
                    String rep = open + " " + text + " " + close;
                    replace(c, s, e, rep, s, s + rep.length());
                }
            } catch (BadLocationException ignored) {
                // the selection moved under the command
            }
        });
    }

    // ---- Lines -------------------------------------------------------------

    public static void duplicate(CodeTextPane c, boolean down) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                Element root = doc.getDefaultRootElement();
                if (s != e && root.getElementIndex(s) == root.getElementIndex(e)) {
                    String sel = doc.getText(s, e - s);
                    doc.insertString(e, sel, null);
                    c.setCaretPosition(e);
                    c.moveCaretPosition(e + sel.length());
                    return;
                }
                int[] range = selectedLines(c);
                int start = lineStart(doc, range[0]);
                int end = lineEnd(doc, range[1]);
                String block = doc.getText(start, end - start);
                doc.insertString(end, "\n" + block, null);
                int shift = down ? block.length() + 1 : 0;
                c.setCaretPosition(s + shift);
                c.moveCaretPosition(e + shift);
            } catch (BadLocationException ignored) {
                // nothing duplicated
            }
        });
    }

    public static void deleteLines(CodeTextPane c) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int[] range = selectedLines(c);
                int count = doc.getDefaultRootElement().getElementCount();
                int start = lineStart(doc, range[0]);
                int end;
                if (range[1] + 1 < count) {
                    end = lineStart(doc, range[1] + 1);
                } else {
                    end = doc.getLength();
                    if (range[0] > 0) {
                        start = lineEnd(doc, range[0] - 1);
                    }
                }
                int caretCol = c.getCaretPosition() - lineStart(doc, doc.getDefaultRootElement()
                                                                      .getElementIndex(c.getCaretPosition()));
                doc.remove(start, end - start);
                Element root = doc.getDefaultRootElement();
                int line = Math.min(range[0], root.getElementCount() - 1);
                int ls = lineStart(doc, line);
                c.setCaretPosition(Math.min(ls + caretCol, lineEnd(doc, line)));
            } catch (BadLocationException ignored) {
                // nothing deleted
            }
        });
    }

    public static void moveLines(CodeTextPane c, boolean up) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int[] range = selectedLines(c);
                int count = doc.getDefaultRootElement().getElementCount();
                if (up ? range[0] == 0 : range[1] >= count - 1) {
                    return;
                }
                int first = up ? range[0] - 1 : range[0];
                int last = up ? range[1] : range[1] + 1;
                String[] ls = lines(doc, first, last);
                List<String> order = new ArrayList<>(Arrays.asList(ls));
                String moved;
                if (up) {
                    moved = order.remove(0);
                    order.add(moved);
                } else {
                    moved = order.remove(order.size() - 1);
                    order.add(0, moved);
                }
                int start = lineStart(doc, first);
                int end = lineEnd(doc, last);
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                int shift = up ? -(moved.length() + 1) : moved.length() + 1;
                replace(c, start, end, String.join("\n", order), s + shift, e + shift);
            } catch (BadLocationException ignored) {
                // nothing moved
            }
        });
    }

    /** Ctrl+Enter / Ctrl+Shift+Enter: a new indented line below or above, whatever the caret column. */
    public static void insertLine(CodeTextPane c, boolean above) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                Element root = doc.getDefaultRootElement();
                int line = root.getElementIndex(c.getCaretPosition());
                int s = lineStart(doc, line);
                String text = doc.getText(s, lineEnd(doc, line) - s);
                String indent = AutoIndenter.leadingWhitespace(text);
                if (above) {
                    doc.insertString(s, indent + "\n", null);
                    c.setCaretPosition(s + indent.length());
                } else {
                    c.setCaretPosition(lineEnd(doc, line));
                    javax.swing.Action enter = c.getActionMap().get(
                            c.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("ENTER")));
                    if (enter != null) {
                        enter.actionPerformed(new java.awt.event.ActionEvent(c, 0, ""));
                    } else {
                        int at = lineEnd(doc, line);
                        doc.insertString(at, "\n" + indent, null);
                        c.setCaretPosition(at + 1 + indent.length());
                    }
                }
            } catch (BadLocationException ignored) {
                // no new line
            }
        });
    }

    /** Ctrl+L: the current line, then one more each time. */
    public static void selectLine(CodeTextPane c) {
        Document doc = c.getDocument();
        Element root = doc.getDefaultRootElement();
        int s = c.getSelectionStart();
        int e = c.getSelectionEnd();
        int first = root.getElementIndex(s);
        int last = root.getElementIndex(e);
        boolean fullLines = s == lineStart(doc, first) && e > s
                && (e == doc.getLength() || e == root.getElement(Math.max(0, root.getElementIndex(e))).getStartOffset());
        int endLine = fullLines ? root.getElementIndex(e) : last;
        int end = endLine + 1 < root.getElementCount() ? lineStart(doc, endLine + 1) : doc.getLength();
        if (fullLines && end == e && endLine + 1 < root.getElementCount()) {
            end = endLine + 2 < root.getElementCount() ? lineStart(doc, endLine + 2) : doc.getLength();
        }
        c.setCaretPosition(lineStart(doc, first));
        c.moveCaretPosition(end);
    }

    public static void joinLines(CodeTextPane c) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int[] range = selectedLines(c);
                int count = doc.getDefaultRootElement().getElementCount();
                int last = range[1] == range[0] ? Math.min(range[0] + 1, count - 1) : range[1];
                if (last == range[0]) {
                    return;
                }
                int start = lineStart(doc, range[0]);
                int end = lineEnd(doc, last);
                String[] ls = lines(doc, range[0], last);
                StringBuilder sb = new StringBuilder(ls[0].stripTrailing());
                int caret = start + sb.length();
                for (int i = 1; i < ls.length; i++) {
                    String t = ls[i].strip();
                    if (t.isEmpty()) {
                        continue;
                    }
                    boolean noSpace = t.startsWith(")") || t.startsWith("]") || t.startsWith(",")
                                      || t.startsWith(";") || sb.length() == 0
                                      || sb.charAt(sb.length() - 1) == '(' || sb.charAt(sb.length() - 1) == '[';
                    if (i == 1) {
                        caret = start + sb.length();
                    }
                    sb.append(noSpace ? "" : " ").append(t);
                }
                replace(c, start, end, sb.toString(), caret, caret);
            } catch (BadLocationException ignored) {
                // nothing joined
            }
        });
    }

    public static void transformCase(CodeTextPane c, boolean upper) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                if (s == e) {
                    int[] w = c.wordAt(s);
                    if (w == null) {
                        return;
                    }
                    s = w[0];
                    e = w[1];
                }
                String t = doc.getText(s, e - s);
                String r = upper ? t.toUpperCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT);
                replace(c, s, e, r, s, s + r.length());
            } catch (BadLocationException ignored) {
                // nothing changed
            }
        });
    }

    public static void sortLines(CodeTextPane c, boolean descending) {
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int[] range = selectedLines(c);
                if (range[0] == range[1]) {
                    return;
                }
                String[] ls = lines(doc, range[0], range[1]);
                List<String> sorted = new ArrayList<>(Arrays.asList(ls));
                sorted.sort(String.CASE_INSENSITIVE_ORDER);
                if (descending) {
                    java.util.Collections.reverse(sorted);
                }
                int start = lineStart(doc, range[0]);
                int end = lineEnd(doc, range[1]);
                String rep = String.join("\n", sorted);
                replace(c, start, end, rep, start, start + rep.length());
            } catch (BadLocationException ignored) {
                // nothing sorted
            }
        });
    }

    /** Removes spaces at the ends of lines; returns how many lines changed. */
    public static int trimTrailingWhitespace(JTextComponent c) {
        int[] changed = { 0 };
        atomic(c, () -> {
            try {
                Document doc = c.getDocument();
                int count = doc.getDefaultRootElement().getElementCount();
                int caret = c.getCaretPosition();
                int caretLine = doc.getDefaultRootElement().getElementIndex(caret);
                for (int line = count - 1; line >= 0; line--) {
                    if (line == caretLine) {
                        continue; // the line being typed keeps its spaces
                    }
                    int s = lineStart(doc, line);
                    int e = lineEnd(doc, line);
                    String t = doc.getText(s, e - s);
                    int keep = t.length();
                    while (keep > 0 && (t.charAt(keep - 1) == ' ' || t.charAt(keep - 1) == '\t')) {
                        keep--;
                    }
                    if (keep < t.length()) {
                        doc.remove(s + keep, t.length() - keep);
                        changed[0]++;
                    }
                }
            } catch (BadLocationException ignored) {
                // stop where it failed
            }
        });
        return changed[0];
    }

    // ---- Smart selection ---------------------------------------------------

    /** Shift+Alt+Right: word, string, bracket content, brackets, line, everything. */
    public static void expandSelection(CodeTextPane c) {
        Document doc = c.getDocument();
        int s = c.getSelectionStart();
        int e = c.getSelectionEnd();
        Deque<int[]> stack = stack(c);
        if (!stack.isEmpty() && (stack.peek()[2] != s || stack.peek()[3] != e)) {
            stack.clear();
        }
        int[] next = nextRange(c, s, e);
        if (next == null || (next[0] == s && next[1] == e)) {
            return;
        }
        stack.push(new int[] { s, e, next[0], next[1] });
        c.setCaretPosition(next[0]);
        c.moveCaretPosition(next[1]);
        c.putClientProperty(SELECTION_STACK, stack);
        if (doc.getLength() == 0) {
            stack.clear();
        }
    }

    /** Shift+Alt+Left: back to the previous, smaller selection. */
    public static void shrinkSelection(CodeTextPane c) {
        Deque<int[]> stack = stack(c);
        if (stack.isEmpty()) {
            return;
        }
        int[] top = stack.peek();
        if (top[2] != c.getSelectionStart() || top[3] != c.getSelectionEnd()) {
            stack.clear();
            return;
        }
        stack.pop();
        c.setCaretPosition(top[0]);
        c.moveCaretPosition(top[1]);
    }

    @SuppressWarnings("unchecked")
    private static Deque<int[]> stack(CodeTextPane c) {
        Object o = c.getClientProperty(SELECTION_STACK);
        if (o instanceof Deque<?> d) {
            return (Deque<int[]>) d;
        }
        Deque<int[]> d = new ArrayDeque<>();
        c.putClientProperty(SELECTION_STACK, d);
        return d;
    }

    private static int[] nextRange(CodeTextPane c, int s, int e) {
        Document doc = c.getDocument();
        if (s == e) {
            int[] w = c.wordAt(s);
            if (w != null) {
                return w;
            }
        }
        SyntaxHighlighter h = c.getSyntax();
        SyntaxHighlighter.Snapshot snap = h == null ? null : h.snapshot();
        boolean current = h != null && h.isCurrent();
        if (current) {
            // Inside a string: its content, then the string.
            int idx = snap.spanAt(s);
            if (idx >= 0 && (snap.kinds[idx] == TokenKind.STRING || snap.kinds[idx] == TokenKind.CHARACTER)
                    && snap.ends[idx] >= e) {
                int a = snap.starts[idx];
                int b = snap.ends[idx];
                int q = a;
                while (q < b && "\"'`".indexOf(snap.text.charAt(q)) < 0) {
                    q++; // past a prefix: f, r, u8, R
                }
                if (q < b) {
                    char quote = snap.text.charAt(q);
                    int open = q + 2 < b && snap.text.charAt(q + 1) == quote && snap.text.charAt(q + 2) == quote ? 3 : 1;
                    int qa = q + open;
                    int qb = b;
                    int closing = 0;
                    while (closing < open && qb > qa && snap.text.charAt(qb - 1) == quote) {
                        qb--;
                        closing++;
                    }
                    if (s >= qa && e <= qb && (s > qa || e < qb)) {
                        return new int[] { qa, qb };
                    }
                }
                if (s > a || e < b) {
                    return new int[] { a, b };
                }
            }
            // The innermost bracket pair around the selection.
            int[] pair = snap.enclosingPair(s);
            while (pair != null && pair[1] < e - 1) {
                pair = snap.enclosingPair(pair[0]);
            }
            if (pair != null) {
                int innerS = pair[0] + 1;
                int innerE = pair[1];
                // Trim the whitespace a block's content starts and ends with.
                String inner = snap.text.substring(innerS, innerE);
                int lead = 0;
                while (lead < inner.length() && Character.isWhitespace(inner.charAt(lead))) {
                    lead++;
                }
                int trail = 0;
                while (trail < inner.length() - lead && Character.isWhitespace(inner.charAt(inner.length() - 1 - trail))) {
                    trail++;
                }
                int ts = innerS + lead;
                int te = innerE - trail;
                if (ts < te && (s > ts || e < te) && s >= ts && e <= te) {
                    return new int[] { ts, te };
                }
                if (s > innerS || e < innerE) {
                    if (s >= innerS && e <= innerE) {
                        return new int[] { innerS, innerE };
                    }
                }
                return new int[] { pair[0], pair[1] + 1 };
            }
        }
        Element root = doc.getDefaultRootElement();
        int first = root.getElementIndex(s);
        int last = root.getElementIndex(e);
        int ls = lineStart(doc, first);
        int le = lineEnd(doc, last);
        if (s > ls || e < le) {
            return new int[] { ls, le };
        }
        return new int[] { 0, doc.getLength() };
    }
}
