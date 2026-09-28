package com.sphere.components.terminal;

import javax.swing.*;
import java.awt.*;

/**
 * A large window on a terminal that stays in its tab.
 *
 * Detaching moves the terminal out of the tabs; this does not. The shell keeps
 * running where it is and this window shows the same document, so a long run
 * can be read at a comfortable size without giving up the tab it belongs to.
 * Ctrl with + and - changes the size of the text here alone.
 */
public class TerminalZoomWindow extends JFrame {

    private final TerminalMirrorView mirror;

    public TerminalZoomWindow(TerminalPanel source, String terminalName) {
        super(terminalName == null || terminalName.isBlank()
              ? "Terminal" : terminalName + " -- enlarged");

        mirror = new TerminalMirrorView(source);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(mirror, BorderLayout.CENTER);
        add(controls(), BorderLayout.SOUTH);

        setMinimumSize(new Dimension(420, 260));
        setSize(1000, 680);
        setLocationRelativeTo(null);
    }

    /** The same three steps as the keys, for whoever prefers a button. */
    private JComponent controls() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        bar.setOpaque(false);
        bar.add(button("Smaller", -1));
        bar.add(button("Original", 0));
        bar.add(button("Larger", 1));
        return bar;
    }

    private JButton button(String text, int step) {
        JButton one = new JButton(text);
        one.setFocusable(false);
        one.addActionListener(event -> mirror.zoom(step));
        return one;
    }

    public TerminalMirrorView getMirror() {
        return mirror;
    }
}
