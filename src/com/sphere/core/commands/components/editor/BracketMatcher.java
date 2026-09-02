package com.sphere.components.editor;

import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;

/**
 * Marks the bracket facing the caret and its partner, skipping pairs that sit
 * inside a string or a comment.
 */
public final class BracketMatcher implements CaretListener {

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";
    private static final int MAX_SCAN = 200_000;

    private final CodeTextPane pane;

    public BracketMatcher(CodeTextPane pane) {
        this.pane = pane;
        pane.addCaretListener(this);
    }

    @Override
    public void caretUpdate(CaretEvent e) {
        int dot = e.getDot();
        try {
            String text = pane.getDocument().getText(0, pane.getDocument().getLength());

            int index = -1;
            if (dot > 0 && isBracket(text.charAt(dot - 1))) {
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
