package com.sphere.components.terminal;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;

import java.awt.BorderLayout;

/**
 * A second view of a running terminal.
 *
 * It shares the source's document rather than copying its text: the mirror keeps
 * the colours, stays in step for free, and no longer rebuilds the whole buffer on
 * every character the shell writes.
 */
public class TerminalMirrorView extends JPanel {

    private final JTextPane mirror;

    public TerminalMirrorView(TerminalPanel source) {
        super(new BorderLayout());

        JTextPane origin = source.getView().getTextPane();

        mirror = new JTextPane();
        mirror.setEditable(false);
        mirror.setDocument(origin.getDocument());
        mirror.setFont(origin.getFont());
        mirror.setBackground(origin.getBackground());
        mirror.setForeground(origin.getForeground());
        mirror.setCaretColor(origin.getCaretColor());
        mirror.putClientProperty("JTextPane.honorDisplayProperties", Boolean.TRUE);

        JScrollPane scroll = new JScrollPane(mirror);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    public JTextPane getTextPane() {
        return mirror;
    }
}
