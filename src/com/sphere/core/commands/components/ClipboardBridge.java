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
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
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

    /** How many of the clipboard's text formats :clip reports on. */
    private static final int FORMATS = 30;

    private static final int MENU_MASK =
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    /** The fields wired so far, so :clip can say whether this one is among them. */
    private static final List<WeakReference<JTextComponent>> WIRED = new ArrayList<>();

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
        synchronized (WIRED) {
            WIRED.add(new WeakReference<>(field));
        }
    }

    /** Puts the selection on the clipboard. */
    public static void copy(JTextComponent field) {
        final String selected = field.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            AppLogger.error("Nothing is selected, so there is nothing to copy.");
            return;
        }
        write(selected);
    }

    /** Puts the selection on the clipboard and removes it. */
    public static void cut(JTextComponent field) {
        final String selected = field.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            AppLogger.error("Nothing is selected, so there is nothing to cut.");
            return;
        }
        if (!field.isEditable()) {
            AppLogger.error("This text cannot be changed, so it cannot be cut.");
            return;
        }
        write(selected);
        field.replaceSelection("");
    }

    /** Drops the clipboard text where the caret is. */
    public static void paste(JTextComponent field) {
        if (!field.isEditable() || !field.isEnabled()) {
            AppLogger.error("This text cannot be changed, so nothing can be pasted into it.");
            return;
        }
        read(field::replaceSelection);
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

    /** Said once when nothing at all could be read out of the clipboard. */
    private static final String NOTHING_BEHIND =
        "The clipboard announces text and hands over none. Nothing was pasted. "
        + "Copy it again; a program that has since closed takes its clipboard "
        + "with it unless a clipboard manager is running.";

    /**
     * Reads the clipboard off the event thread, then hands the text over on it.
     *
     * Nothing is handed over when the clipboard holds no text, so the caller is
     * only ever called with something to work with.
     */
    public static void read(Consumer<String> whenRead) {
        detach("read the clipboard", () -> {
            final String text = fetch();
            if (text == null) {
                AppLogger.error("The clipboard holds no text.");
                return;
            }
            if (!text.isEmpty()) {
                hand(whenRead, text);
                return;
            }
            // A clipboard owned by another program is handed over through the
            // thread that runs the toolkit. One more try there before giving up;
            // the first try answered at once, so this one will not hang either.
            SwingUtilities.invokeLater(() -> {
                String again;
                try {
                    again = fetch();
                } catch (RuntimeException unreadable) {
                    AppLogger.error("Could not read the clipboard: " + say(unreadable));
                    return;
                }
                if (again == null || again.isEmpty()) {
                    AppLogger.error(NOTHING_BEHIND);
                    return;
                }
                accept(whenRead, again);
            });
        });
    }

    /**
     * One reading of the clipboard.
     *
     * X11 keeps two of them: the clipboard proper, which Ctrl+C fills, and the
     * selection, which fills itself as soon as text is highlighted anywhere.
     * Programs do not all use the first, so the second is read when the first
     * has nothing, rather than pasting nothing at all.
     *
     * Null means neither carries text, an empty string that one announced some
     * and every route came back with nothing.
     */
    private static String fetch() {
        final String copied = from(board());
        if (copied != null && !copied.isEmpty()) {
            return copied;
        }
        final String highlighted = from(Toolkit.getDefaultToolkit().getSystemSelection());
        if (highlighted != null && !highlighted.isEmpty()) {
            return highlighted;
        }
        return copied != null ? copied : highlighted;
    }

    /** One clipboard's text, asked again while another program is holding it. */
    private static String from(Clipboard board) {
        if (board == null) {
            return null;
        }
        RuntimeException busy = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                Transferable held = board.getContents(null);
                return held == null ? null : asText(held);
            } catch (IllegalStateException | java.io.IOException held) {
                // Both of these come back while another program is holding it.
                busy = new IllegalStateException(say(held), held);
                pause();
            } catch (Exception unreadable) {
                throw new IllegalStateException(say(unreadable), unreadable);
            }
        }
        throw busy;
    }

    private static void hand(Consumer<String> whenRead, String text) {
        SwingUtilities.invokeLater(() -> accept(whenRead, text));
    }

    private static void accept(Consumer<String> whenRead, String text) {
        try {
            whenRead.accept(text);
        } catch (RuntimeException refused) {
            AppLogger.error("The pasted text was refused: " + say(refused));
        }
    }

    /**
     * The clipboard's text, by whichever route its holder answers.
     *
     * The plain string is asked for first. A holder that announces text and then
     * hands over an empty string is asked again through each of its own text
     * formats, which are different routes into the same data; there are a couple
     * of dozen of them and they do not all answer alike. Null means it
     * carries no text at all, an empty string that every route came back empty.
     */
    private static String asText(Transferable held) throws Exception {
        boolean announced = false;

        if (held.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            announced = true;
            final Object value = held.getTransferData(DataFlavor.stringFlavor);
            if (value instanceof String text && !text.isEmpty()) {
                return text;
            }
        }

        for (DataFlavor flavor : held.getTransferDataFlavors()) {
            if (flavor == null || !flavor.isFlavorTextType()
                || DataFlavor.stringFlavor.equals(flavor)) {
                continue;
            }
            announced = true;
            try (java.io.Reader reader = flavor.getReaderForText(held)) {
                final String text = drain(reader);
                if (!text.isEmpty()) {
                    return text;
                }
            } catch (Exception unusable) {
                // This format cannot be read here; the next one may be.
            }
        }
        return announced ? "" : null;
    }

    private static String drain(java.io.Reader reader) throws java.io.IOException {
        StringBuilder text = new StringBuilder();
        char[] chunk = new char[4096];
        int taken;
        while ((taken = reader.read(chunk)) > 0) {
            text.append(chunk, 0, taken);
        }
        return text.toString();
    }

    /** Writes a marker and reads it back, to say whether the exchange works at all. */
    public static String roundTrip() {
        final String marker = "sphere-clipboard-" + System.currentTimeMillis();
        StringBuilder report = new StringBuilder("Clipboard round trip");
        try {
            board().setContents(new StringSelection(marker), null);
            report.append("\n  writing          : done");
        } catch (Exception refused) {
            report.append("\n  writing          : refused, ").append(say(refused));
            return report.toString();
        }
        pause();
        try {
            Transferable held = board().getContents(null);
            final String back = held == null ? null : asText(held);
            if (marker.equals(back)) {
                report.append("\n  reading back     : the same text came back");
                report.append("\n  so               : Sphere can copy and paste; "
                              + "what was on the clipboard before could not be read");
            } else {
                report.append("\n  reading back     : ")
                      .append(back == null ? "no text at all" : "\"" + back + "\"");
                report.append("\n  so               : this machine's clipboard does not "
                              + "hand back what Sphere puts on it");
            }
        } catch (Exception unreadable) {
            report.append("\n  reading back     : refused, ").append(say(unreadable));
        }
        return report.toString();
    }

    /** What the clipboard says it can hand over, in words. */
    private static String offered(Clipboard board) {
        StringBuilder names = new StringBuilder();
        try {
            for (DataFlavor flavor : board.getAvailableDataFlavors()) {
                names.append(names.length() == 0 ? "" : ", ")
                     .append(flavor.getHumanPresentableName());
            }
        } catch (RuntimeException unreadable) {
            return "it would not say (" + say(unreadable) + ")";
        }
        return names.length() == 0 ? "nothing" : names.toString();
    }

    /**
     * What the clipboard holds and which fields answer the copy keys.
     *
     * Copy and paste failing is hard to tell from copy and paste doing nothing,
     * so this says which of the two it is without guessing.
     */
    public static String status() {
        StringBuilder report = new StringBuilder("Clipboard");

        final List<JTextComponent> fields = wired();
        report.append("\n  fields wired     : ").append(fields.size());
        if (fields.isEmpty()) {
            report.append("  (the command field has not been given its own copy keys)");
        }
        for (JTextComponent field : fields) {
            report.append("\n  keys on ")
                  .append(field.getClass().getSimpleName())
                  .append("      : ")
                  .append(bound(field, KeyStroke.getKeyStroke(KeyEvent.VK_C, MENU_MASK)))
                  .append(" / ")
                  .append(bound(field, KeyStroke.getKeyStroke(KeyEvent.VK_X, MENU_MASK)))
                  .append(" / ")
                  .append(bound(field, KeyStroke.getKeyStroke(KeyEvent.VK_V, MENU_MASK)));
        }

        final Clipboard selection = Toolkit.getDefaultToolkit().getSystemSelection();
        if (selection != null) {
            try {
                final String highlighted = from(selection);
                report.append("\n  highlighted text : ")
                      .append(highlighted == null || highlighted.isEmpty() ? "none"
                              : highlighted.length() + " characters "
                                + shorten(highlighted));
            } catch (RuntimeException unreadable) {
                report.append("\n  highlighted text : ").append(say(unreadable));
            }
        }

        try {
            Clipboard board = board();
            report.append("\n  it offers        : ").append(offered(board));
            Transferable held = board.getContents(null);
            if (held != null && held.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                final Object plain = held.getTransferData(DataFlavor.stringFlavor);
                final String text = plain == null ? null : String.valueOf(plain);
                report.append("\n  as a string      : ")
                      .append(text == null ? "nothing"
                              : text.length() + " characters " + shorten(text));
            }
            if (held != null) {
                final String through = asText(held);
                report.append("\n  by any route     : ")
                      .append(through == null ? "no text at all"
                              : through.length() + " characters " + shorten(through));
                report.append(perFormat(held));
            }
        } catch (Exception unreadable) {
            report.append("\n  it would not answer: ").append(say(unreadable));
        }
        return report.toString();
    }

    /** What each text format of the clipboard hands over, one line each. */
    private static String perFormat(Transferable held) {
        StringBuilder lines = new StringBuilder("\n  format by format :");
        int shown = 0;
        for (DataFlavor flavor : held.getTransferDataFlavors()) {
            if (flavor == null || !flavor.isFlavorTextType() || ++shown > FORMATS) {
                continue;
            }
            lines.append("\n      ").append(flavor.getMimeType()).append("\n          -> ");
            try {
                if (DataFlavor.stringFlavor.equals(flavor)) {
                    final Object value = held.getTransferData(flavor);
                    lines.append(value == null ? "nothing"
                                 : String.valueOf(value).length() + " characters");
                } else {
                    try (java.io.Reader reader = flavor.getReaderForText(held)) {
                        lines.append(drain(reader).length()).append(" characters");
                    }
                }
            } catch (Exception unusable) {
                lines.append("refused, ").append(say(unusable));
            }
        }
        return lines.toString();
    }

    private static List<JTextComponent> wired() {
        List<JTextComponent> alive = new ArrayList<>();
        synchronized (WIRED) {
            WIRED.removeIf(held -> held.get() == null);
            for (WeakReference<JTextComponent> held : WIRED) {
                JTextComponent field = held.get();
                if (field != null) {
                    alive.add(field);
                }
            }
        }
        return alive;
    }

    private static String bound(JTextComponent field, KeyStroke stroke) {
        final Object name = field.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        return name == null ? "nothing" : String.valueOf(name);
    }

    /** One line of it, with the line breaks made visible. */
    private static String shorten(String text) {
        final String flat = text.replace("\r", "").replace("\n", "\\n");
        return flat.length() <= 70 ? "\"" + flat + "\""
                                   : "\"" + flat.substring(0, 70) + "\" and "
                                     + (flat.length() - 70) + " more characters";
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
            } catch (Throwable refused) {
                AppLogger.error("Could not " + what + ": " + say(refused));
            }
        }, "sphere-clipboard");
        worker.setDaemon(true);
        worker.start();
    }

    /** A failure named even when it carries no message of its own. */
    private static String say(Throwable failure) {
        if (failure == null) {
            return "no reason given";
        }
        final String message = failure.getMessage();
        return message == null || message.isBlank()
               ? failure.getClass().getName() : message;
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
