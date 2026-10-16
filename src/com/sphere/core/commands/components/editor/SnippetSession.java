package com.sphere.components.editor;

import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fields of an inserted snippet. Tab moves to the next field, Shift+Tab to
 * the previous one; typing in a field repeats the text in its copies; leaving
 * the fields, Escape or Tab past the last one ends the session.
 */
final class SnippetSession implements DocumentListener, CaretListener {

    private final CodeTextPane pane;
    private final Document doc;
    /** Fields in Tab order; each holds its ranges, the first being the one typed in. */
    private final List<List<int[]>> fields = new ArrayList<>();
    private final int[] finalOffset = new int[1];
    private int current = -1;
    private boolean active;
    private boolean mirroring;
    private boolean mirrorQueued;
    private final Runnable onEnd;

    private SnippetSession(CodeTextPane pane, Runnable onEnd) {
        this.pane = pane;
        this.doc = pane.getDocument();
        this.onEnd = onEnd;
    }

    /**
     * Replaces [from, to) with the snippet and starts walking its fields.
     * Returns the session, or null when the snippet has no field to visit.
     */
    static SnippetSession insert(CodeTextPane pane, Snippets.Snippet snippet, int from, int to,
                                 String unit, Runnable onEnd) {
        Document doc = pane.getDocument();
        String indent;
        try {
            int line = pane.lineAt(from);
            int ls = pane.lineStartOffset(line);
            indent = AutoIndenter.leadingWhitespace(doc.getText(ls, from - ls));
        } catch (BadLocationException ex) {
            indent = "";
        }
        Snippets.Expansion ex = Snippets.expand(snippet.body, indent, unit);
        SnippetSession session = new SnippetSession(pane, onEnd);
        final int base = from;
        pane.atomic(() -> {
            try {
                doc.remove(from, to - from);
                doc.insertString(from, ex.text, null);
            } catch (BadLocationException ignored) {
                // the buffer moved; the snippet is simply not inserted
            }
        });
        for (Map.Entry<Integer, List<int[]>> e : ex.fields.entrySet()) {
            List<int[]> ranges = new ArrayList<>();
            for (int[] r : e.getValue()) {
                ranges.add(new int[] { base + r[0], base + r[1] });
            }
            session.fields.add(ranges);
        }
        session.finalOffset[0] = base + ex.finalOffset;
        if (session.fields.isEmpty()) {
            pane.setCaretPosition(Math.min(session.finalOffset[0], doc.getLength()));
            return null;
        }
        session.start();
        return session;
    }

    private void start() {
        active = true;
        doc.addDocumentListener(this);
        pane.addCaretListener(this);
        select(0);
    }

    boolean isActive() {
        return active;
    }

    /** Tab: the next field, or the end. */
    boolean next() {
        if (!active) {
            return false;
        }
        if (current + 1 >= fields.size()) {
            finish(true);
            return true;
        }
        select(current + 1);
        return true;
    }

    /** Shift+Tab: the previous field. */
    boolean previous() {
        if (!active) {
            return false;
        }
        if (current > 0) {
            select(current - 1);
        }
        return true;
    }

    private void select(int index) {
        current = index;
        int[] r = fields.get(index).get(0);
        int max = doc.getLength();
        pane.setCaretPosition(Math.min(r[0], max));
        pane.moveCaretPosition(Math.min(r[1], max));
        paint();
    }

    void finish(boolean moveToEnd) {
        if (!active) {
            return;
        }
        active = false;
        doc.removeDocumentListener(this);
        pane.removeCaretListener(this);
        pane.setPlaceholders(null, -1);
        if (moveToEnd) {
            pane.setCaretPosition(Math.max(0, Math.min(finalOffset[0], doc.getLength())));
        }
        if (onEnd != null) {
            onEnd.run();
        }
    }

    private void paint() {
        List<int[]> all = new ArrayList<>();
        for (int f = 0; f < fields.size(); f++) {
            for (int[] r : fields.get(f)) {
                all.add(new int[] { r[0], r[1], f == current ? 1 : 0 });
            }
        }
        pane.setPlaceholders(all, -1);
    }

    // ---- Keeping ranges on their text --------------------------------------

    @Override
    public void insertUpdate(DocumentEvent e) {
        int off = e.getOffset();
        int len = e.getLength();
        boolean inCurrent = false;
        for (int f = 0; f < fields.size(); f++) {
            for (int[] r : fields.get(f)) {
                if (off < r[0]) {
                    r[0] += len;
                    r[1] += len;
                } else if (off <= r[1]) {
                    r[1] += len;
                    if (f == current) {
                        inCurrent = true;
                    }
                }
            }
        }
        if (off <= finalOffset[0]) {
            finalOffset[0] += len;
        }
        afterEdit(inCurrent);
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        int off = e.getOffset();
        int end = off + e.getLength();
        int len = e.getLength();
        boolean inCurrent = false;
        for (int f = 0; f < fields.size(); f++) {
            for (int[] r : fields.get(f)) {
                boolean touches = off <= r[1] && end >= r[0];
                r[0] = r[0] >= end ? r[0] - len : Math.min(r[0], off);
                r[1] = r[1] >= end ? r[1] - len : Math.min(r[1], off);
                if (touches && f == current) {
                    inCurrent = true;
                }
            }
        }
        finalOffset[0] = finalOffset[0] >= end ? finalOffset[0] - len : Math.min(finalOffset[0], off);
        afterEdit(inCurrent);
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        // attribute-only
    }

    private void afterEdit(boolean inCurrent) {
        if (mirroring) {
            return;
        }
        if (!inCurrent) {
            SwingUtilities.invokeLater(() -> finish(false));
            return;
        }
        paint();
        if (!mirrorQueued && current >= 0 && fields.get(current).size() > 1) {
            mirrorQueued = true;
            SwingUtilities.invokeLater(this::mirror);
        }
    }

    /** Copies the field being typed into its repetitions. */
    private void mirror() {
        mirrorQueued = false;
        if (!active || current < 0) {
            return;
        }
        List<int[]> ranges = fields.get(current);
        int[] primary = ranges.get(0);
        String text;
        try {
            text = doc.getText(primary[0], primary[1] - primary[0]);
        } catch (BadLocationException ex) {
            return;
        }
        mirroring = true;
        int caret = pane.getCaretPosition();
        int caretFromEnd = primary[1] - caret;
        try {
            // From the last copy back, so earlier offsets stay valid.
            for (int k = ranges.size() - 1; k >= 1; k--) {
                int[] r = ranges.get(k);
                String old = doc.getText(r[0], r[1] - r[0]);
                if (!old.equals(text)) {
                    int at = r[0];
                    doc.remove(at, r[1] - r[0]);
                    doc.insertString(at, text, null);
                    r[0] = at;
                    r[1] = at + text.length();
                }
            }
        } catch (BadLocationException ignored) {
            // a copy moved; it keeps its old text
        } finally {
            mirroring = false;
        }
        int[] p = fields.get(current).get(0);
        pane.setCaretPosition(Math.max(p[0], Math.min(p[1] - caretFromEnd, doc.getLength())));
        paint();
    }

    @Override
    public void caretUpdate(CaretEvent e) {
        if (!active || mirroring) {
            return;
        }
        int dot = e.getDot();
        SwingUtilities.invokeLater(() -> {
            if (!active) {
                return;
            }
            for (List<int[]> f : fields) {
                for (int[] r : f) {
                    if (dot >= r[0] && dot <= r[1]) {
                        return;
                    }
                }
            }
            if (dot == finalOffset[0]) {
                return;
            }
            finish(false);
        });
    }
}
