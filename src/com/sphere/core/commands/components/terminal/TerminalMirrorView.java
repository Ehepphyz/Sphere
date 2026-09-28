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
 * the colors, stays in step for free, and no longer rebuilds the whole buffer on
 * every character the shell writes.
 */
public class TerminalMirrorView extends JPanel {

    private static final int MINIMUM_SIZE = 6;
    private static final int MAXIMUM_SIZE = 72;

    private final JTextPane mirror;
    private final int originalSize;

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
        originalSize = mirror.getFont().getSize();

        JScrollPane scroll = new JScrollPane(mirror);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        installZoom();
    }

    /**
     * Ctrl and + or -, and the wheel with Ctrl held: the text grows and shrinks.
     *
     * Only the mirror's own font changes, never the terminal's: reading a long
     * run in a large window is the point of this view, and the tab it came from
     * must stay the size the rest of the layout expects.
     */
    private void installZoom() {
        int shortcut = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(java.awt.event.KeyEvent.VK_PLUS, shortcut, 1);
        bind(java.awt.event.KeyEvent.VK_EQUALS, shortcut, 1);
        bind(java.awt.event.KeyEvent.VK_ADD, shortcut, 1);
        bind(java.awt.event.KeyEvent.VK_MINUS, shortcut, -1);
        bind(java.awt.event.KeyEvent.VK_SUBTRACT, shortcut, -1);
        bind(java.awt.event.KeyEvent.VK_0, shortcut, 0);

        addMouseWheelListener(wheel -> {
            if ((wheel.getModifiersEx() & shortcut) != 0) {
                zoom(wheel.getWheelRotation() < 0 ? 1 : -1);
                wheel.consume();
            }
        });
    }

    private void bind(int key, int modifiers, int step) {
        String name = "sphere-zoom-" + key;
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(javax.swing.KeyStroke.getKeyStroke(key, modifiers), name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                zoom(step);
            }
        });
    }

    /** One step larger, one smaller, or back to the terminal's own size. */
    public void zoom(int step) {
        java.awt.Font font = mirror.getFont();
        int size = (step == 0) ? originalSize
                               : Math.max(MINIMUM_SIZE, Math.min(MAXIMUM_SIZE, font.getSize() + step));
        mirror.setFont(font.deriveFont((float) size));
    }

    /** The size the text is drawn at. */
    public int getFontSize() {
        return mirror.getFont().getSize();
    }

    public JTextPane getTextPane() {
        return mirror;
    }
}
