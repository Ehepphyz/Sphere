package com.sphere.components;

import com.sphere.ui.console.ConsoleMenuFactory;
import com.sphere.utils.AppLogger;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Cut, copy and paste that never stop the window.
 *
 * One process at a time owns the system clipboard. Reading it straight from the
 * event thread therefore waits on whatever else holds it, and Sphere stops
 * answering until that program lets go; when the wait ends in a refusal the
 * exception has nowhere to go, so the key looks simply dead. The exchange runs
 * off the event thread here, a clipboard that is busy is asked again, and a real
 * failure is reported instead of being lost.
 */
public final class ClipboardBridge {

    /** How many times a busy clipboard is asked again before giving up. */
    private static final int ATTEMPTS = 8;

    /** How long the holder is left alone between two attempts. */
    private static final long RETRY_MILLIS = 80;

    private static final int MENU_MASK =
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private ClipboardBridge() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Gives a text component its own cut, copy and paste.
     *
     * The look and feel installs bindings of its own, but they go straight to
     * the clipboard from the event thread. These take their place, and the right
     * click menu offers the same three without the keyboard.
     */
    public static void install(JTextComponent field) {
        if (field == null) {
            return;
        }
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_C, MENU_MASK), "sphere-copy");
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_X, MENU_MASK), "sphere-cut");
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_V, MENU_MASK), "sphere-paste");
        bind(field, KeyStroke.getKeyStroke("COPY"), "sphere-copy");
        bind(field, KeyStroke.getKeyStroke("CUT"), "sphere-cut");
        bind(field, KeyStroke.getKeyStroke("PASTE"), "sphere-paste");
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT,
                                           InputEvent.CTRL_DOWN_MASK), "sphere-copy");
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT,
                                           InputEvent.SHIFT_DOWN_MASK), "sphere-paste");
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,
                                           InputEvent.SHIFT_DOWN_MASK), "sphere-cut");

        field.getActionMap().put("sphere-copy", action(() -> copy(field)));
        field.getActionMap().put("sphere-cut", action(() -> cut(field)));
        field.getActionMap().put("sphere-paste", action(() -> paste(field)));

        attachMenu(field);
    }

    /** Puts the selection on the clipboard. */
    public static void copy(JTextComponent field) {
        write(field.getSelectedText());
    }

    /** Puts the selection on the clipboard and removes it. */
    public static void cut(JTextComponent field) {
        final String selected = field.getSelectedText();
        if (selected == null || selected.isEmpty() || !field.isEditable()) {
            return;
        }
        write(selected);
        field.replaceSelection("");
    }

    /** Drops the clipboard text where the caret is. */
    public static void paste(JTextComponent field) {
        if (!field.isEditable() || !field.isEnabled()) {
            return;
        }
        read(text -> field.replaceSelection(text));
    }

    /** Puts text on the clipboard, off the event thread. */
    public static void write(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        detach("write to the clipboard", () -> {
            IllegalStateException busy = null;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                    board().setContents(new StringSelection(text), null);
                    return;
                } catch (IllegalStateException held) {
                    busy = held;
                    pause();
                }
            }
            throw busy;
        });
    }

    /**
     * Reads the clipboard off the event thread, then hands the text over on it.
     *
     * Nothing is handed over when the clipboard holds no text, so the caller is
     * only ever called with something to work with.
     */
    public static void read(Consumer<String> whenRead) {
        detach("read the clipboard", () -> {
            IllegalStateException busy = null;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                    Clipboard board = board();
                    if (!board.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                        return;
                    }
                    final String text = (String) board.getData(DataFlavor.stringFlavor);
                    if (text != null) {
                        SwingUtilities.invokeLater(() -> whenRead.accept(text));
                    }
                    return;
                } catch (IllegalStateException held) {
                    busy = held;
                    pause();
                } catch (Exception unreadable) {
                    throw new IllegalStateException(unreadable.getMessage(), unreadable);
                }
            }
            throw busy;
        });
    }

    // ---- the plumbing -------------------------------------------------------

    private static Clipboard board() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_MILLIS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs one exchange on a thread of its own, and says so when it fails. */
    private static void detach(String what, Runnable exchange) {
        Thread worker = new Thread(() -> {
            try {
                exchange.run();
            } catch (RuntimeException refused) {
                AppLogger.error("Could not " + what + ": " + refused.getMessage()
                                + ". Another program is holding it.");
            }
        }, "sphere-clipboard");
        worker.setDaemon(true);
        worker.start();
    }

    private static void bind(JTextComponent field, KeyStroke stroke, String name) {
        if (stroke != null) {
            field.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name);
        }
    }

    private static AbstractAction action(Runnable body) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                body.run();
            }
        };
    }

    private static void attachMenu(JTextComponent field) {
        JPopupMenu menu = ConsoleMenuFactory.popup();
        menu.add(item("Cut", () -> cut(field)));
        menu.add(item("Copy", () -> copy(field)));
        menu.add(item("Paste", () -> paste(field)));
        menu.add(ConsoleMenuFactory.separator());
        menu.add(item("Select All", field::selectAll));
        ConsoleMenuFactory.fitWidth(menu);

        field.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                reveal(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                reveal(event);
            }

            private void reveal(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    field.requestFocusInWindow();
                    menu.show(field, event.getX(), event.getY());
                }
            }
        });
    }

    private static JMenuItem item(String text, Runnable body) {
        JMenuItem entry = ConsoleMenuFactory.item(text);
        entry.addActionListener(event -> body.run());
        return entry;
    }
}
