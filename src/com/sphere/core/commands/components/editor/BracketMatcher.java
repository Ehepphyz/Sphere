package com.sphere.components.editor;

import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;

/**
 * Marks the bracket facing the caret and its partner. Pairs come from the
 * highlighter's scan, which knows strings and comments; between a keystroke
 * and that scan a plain scan of the text stands in.
 */
public final class BracketMatcher implements CaretListener, SyntaxHighlighter.Listener {

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";
    private static final int MAX_SCAN = 200_000;

    private final CodeTextPane pane;

    public BracketMatcher(CodeTextPane pane) {
        this.pane = pane;
        pane.addCaretListener(this);
        if (pane.getSyntax() != null) {
            pane.getSyntax().addListener(this);
        }
    }

    @Override
    public void caretUpdate(CaretEvent e) {
        update(e.getDot());
    }

    @Override
    public void highlighted(SyntaxHighlighter.Snapshot snapshot) {
        update(pane.getCaretPosition());
    }

    private void update(int dot) {
        SyntaxHighlighter h = pane.getSyntax();
        if (h != null && h.isCurrent()) {
            SyntaxHighlighter.Snapshot s = h.snapshot();
            int idx = dot > 0 ? s.bracketAt(dot - 1) : -1;
            if (idx < 0) {
                idx = s.bracketAt(dot);
            }
            if (idx >= 0 && s.bracketPartner[idx] >= 0) {
                pane.setBracketPair(s.bracketPos[idx], s.bracketPos[s.bracketPartner[idx]]);
            } else {
                pane.setBracketPair(-1, -1);
            }
            return;
        }
        try {
            String text = pane.getDocument().getText(0, pane.getDocument().getLength());
            int index = -1;
            if (dot > 0 && dot - 1 < text.length() && isBracket(text.charAt(dot - 1))) {
                index = dot - 1;
            } else if (dot < text.length() && isBracket(text.charAt(dot))) {
                index = dot;
            }
            if (index < 0) {
                pane.setBracketPair(-1, -1);
                return;
            }
            int partner = findPartner(text, index);
            pane.setBracketPair(partner < 0 ? -1 : index, partner);
        } catch (BadLocationException ex) {
            pane.setBracketPair(-1, -1);
        }
    }

    private static boolean isBracket(char c) {
        return OPENERS.indexOf(c) >= 0 || CLOSERS.indexOf(c) >= 0;
    }

    private int findPartner(String text, int index) {
        char c = text.charAt(index);
        int open = OPENERS.indexOf(c);
        int close = CLOSERS.indexOf(c);

        final boolean forward = open >= 0;
        final char match = forward ? CLOSERS.charAt(open) : OPENERS.charAt(close);
        final int step = forward ? 1 : -1;

        int depth = 0;
        int scanned = 0;
        for (int i = index; i >= 0 && i < text.length() && scanned < MAX_SCAN; i += step, scanned++) {
            char ch = text.charAt(i);
            if (ch == c) {
                depth++;
            } else if (ch == match) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
