package com.sphere.components.terminal;

import com.sphere.fonts.FontLoader;
import com.sphere.utils.AppLogger;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One terminal: the shell's output, a line to type into, and the keys a terminal
 * is expected to answer to.
 *
 * The history outlives the session, Ctrl+C stops the running command without
 * taking the shell with it, and Tab completes a path against the directory the
 * shell is actually in.
 */
public class TerminalPanel extends JPanel {

    private static final int HISTORY_LIMIT = 500;

    private final TerminalEngine engine;
    private final TerminalView view;
    private final JTextField input;
    /** Shown only when something is going on: reverse search, or a dead shell. */
    private final JLabel notice;
    private final List<String> history = new ArrayList<>();
    private final Path historyFile;

    private int historyIndex;
    private String draft = "";
    private boolean searching;
    private String searchTerm = "";

    public TerminalPanel(String shellCommand) {
        this(new ShellInfo(shellCommand, shellCommand,
                           ShellSelector.interactiveArguments(shellCommand), null), null);
    }

    public TerminalPanel(ShellInfo shell, File workingDirectory) {
        super(new BorderLayout());

        engine = new TerminalEngine(shell, workingDirectory);
        view = new TerminalView(engine);

        // No permanent bar: the tab already names the shell and the prompt already
        // prints the directory, and an absolute path ran off the right edge.
        notice = new JLabel();
        notice.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        notice.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        notice.setVisible(false);

        input = new JTextField();
        input.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        input.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        // Tab moves the focus by default, so completion would never see the key.
        input.setFocusTraversalKeysEnabled(false);

        historyFile = Path.of("config", "terminal-history-"
            + shell.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + ".txt");
        loadHistory();
        historyIndex = history.size();

        bindKeys();

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(notice, BorderLayout.NORTH);
        bottom.add(input, BorderLayout.CENTER);

        add(view, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        engine.start();
    }

    // ---- keys --------------------------------------------------------------

    private void bindKeys() {
        input.addActionListener(e -> submit());

        bind("UP", () -> recall(-1));
        bind("DOWN", () -> recall(1));
        bind("TAB", this::complete);
        bind("ctrl C", this::interrupt);
        bind("ctrl L", () -> {
            view.clear();
            input.setText("");
        });
        bind("ctrl R", this::toggleSearch);
        bind("ESCAPE", () -> {
            if (searching) {
                endSearch();
            }
        });
        // Ctrl+D closes the shell only on an empty line, as a shell does.
        bind("ctrl D", () -> {
            if (input.getText().isEmpty()) {
                engine.sendRaw("exit");
            }
        });

        // A multi-line paste is a series of commands, not one line with the
        // newlines eaten: every complete line is sent, the last one stays to edit.
        input.getInputMap(JComponent.WHEN_FOCUSED)
             .put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "sphere-paste");
        input.getActionMap().put("sphere-paste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                paste();
            }
        });
    }

    private void bind(String stroke, Runnable action) {
        KeyStroke key = KeyStroke.getKeyStroke(stroke);
        input.getInputMap(JComponent.WHEN_FOCUSED).put(key, stroke);
        input.getActionMap().put(stroke, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void submit() {
        String command = input.getText();
        if (searching) {
            endSearch();
        }
        engine.sendCommand(command);
        if (!command.isBlank() && (history.isEmpty()
                || !history.get(history.size() - 1).equals(command))) {
            history.add(command);
            if (history.size() > HISTORY_LIMIT) {
                history.remove(0);
            }
            saveHistory();
        }
        historyIndex = history.size();
        draft = "";
        input.setText("");
    }

    /**
     * Ctrl+C stops the command. With a selection it copies instead, so the key
     * keeps both meanings the way a terminal emulator does.
     */
    private void interrupt() {
        if (input.getSelectedText() != null && !input.getSelectedText().isEmpty()) {
            input.copy();
            return;
        }
        engine.interrupt();
        input.setText("");
    }

    private void recall(int direction) {
        if (history.isEmpty()) {
            return;
        }
        if (historyIndex == history.size()) {
            draft = input.getText();
        }
        int next = historyIndex + direction;
        if (next < 0) {
            next = 0;
        }
        if (next >= history.size()) {
            historyIndex = history.size();
            input.setText(draft);
            return;
        }
        historyIndex = next;
        input.setText(history.get(historyIndex));
        input.setCaretPosition(input.getText().length());
    }

    // ---- reverse search ----------------------------------------------------

    private void toggleSearch() {
        if (!searching) {
            searching = true;
            searchTerm = "";
            show("(reverse-i-search)  ↵ pour lancer, Échap pour sortir");
            return;
        }
        searchTerm = input.getText();
        searchBackwards();
    }

    /** The notice line appears for the moment it has something to say, then goes. */
    private void show(String message) {
        notice.setText(message);
        notice.setVisible(message != null && !message.isEmpty());
        revalidate();
    }

    private void endSearch() {
        searching = false;
        searchTerm = "";
        show("");
    }

    private void searchBackwards() {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).contains(searchTerm)) {
                input.setText(history.get(i));
                historyIndex = i;
                return;
            }
        }
    }

    // ---- completion --------------------------------------------------------

    /**
     * Completes the last word as a path. A single match is written in; several
     * are listed and the common prefix filled, the way a shell does it.
     */
    private void complete() {
        String text = input.getText();
        int cut = Math.max(text.lastIndexOf(' '), text.lastIndexOf('\t')) + 1;
        String token = text.substring(cut);

        File base = engine.currentDirectory();
        String folderPart = "";
        String namePart = token;
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf(File.separatorChar));
        if (slash >= 0) {
            folderPart = token.substring(0, slash + 1);
            namePart = token.substring(slash + 1);
        }
        File folder = folderPart.isEmpty() ? base : resolve(base, folderPart);
        String[] names = folder.list();
        if (names == null) {
            return;
        }
        final String prefix = namePart;
        List<String> matches = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(prefix)) {
                matches.add(new File(folder, name).isDirectory() ? name + "/" : name);
            }
        }
        if (matches.isEmpty()) {
            return;
        }
        String common = matches.get(0);
        for (String match : matches) {
            common = commonPrefix(common, match);
        }
        input.setText(text.substring(0, cut) + folderPart + common);
        input.setCaretPosition(input.getText().length());

        if (matches.size() > 1) {
            view.onOutput("\n" + String.join("   ", matches) + "\n");
        }
    }

    private static File resolve(File base, String folder) {
        File candidate = new File(folder);
        return candidate.isAbsolute() ? candidate : new File(base, folder);
    }

    private static String commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    private void paste() {
        try {
            Object content = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            String text = String.valueOf(content);
            if (!text.contains("\n")) {
                input.replaceSelection(text);
                return;
            }
            String[] lines = text.split("\r?\n", -1);
            for (int i = 0; i < lines.length - 1; i++) {
                input.setText(lines[i]);
                submit();
            }
            input.setText(lines[lines.length - 1]);
        } catch (Exception ex) {
            AppLogger.error("Clipboard could not be read: " + ex.getMessage());
        }
    }

    // ---- history on disk ---------------------------------------------------

    private void loadHistory() {
        try {
            if (Files.isReadable(historyFile)) {
                history.addAll(Files.readAllLines(historyFile, StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            AppLogger.error("Terminal history could not be read: " + ex.getMessage());
        }
    }

    private void saveHistory() {
        try {
            Path folder = historyFile.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.write(historyFile, history, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            AppLogger.error("Terminal history could not be written: " + ex.getMessage());
        }
    }

    // ---- state -------------------------------------------------------------

    public TerminalEngine getEngine() {
        return engine;
    }

    public TerminalView getView() {
        return view;
    }

    @Override
    public boolean requestFocusInWindow() {
        return input.requestFocusInWindow();
    }

    public void disposeTerminal() {
        engine.stop();
    }

    /** Colours the input to match the terminal surface. */
    public void applyColours(Color background, Color foreground) {
        input.setBackground(background);
        input.setForeground(foreground);
        input.setCaretColor(foreground);
        notice.setForeground(foreground);
        SwingUtilities.invokeLater(this::repaint);
    }
}
