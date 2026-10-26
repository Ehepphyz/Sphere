package com.sphere.components.g4editor;

import com.sphere.core.geant4.Script;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The console, FreeCAD's Python console for Geant4: commands typed with
 * their units, sentences read by the natural-language reader, and every
 * mouse action echoed as the command that would replay it.
 */
final class ConsolePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTextPane out = new JTextPane();
    private final JTextField in = new JTextField();
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;
    private Script script;
    private final SimpleAttributeSet sCmd = new SimpleAttributeSet(), sText = new SimpleAttributeSet(),
        sErr = new SimpleAttributeSet(), sLog = new SimpleAttributeSet(), sOk = new SimpleAttributeSet();
    private List<String> completions = List.of();
    private int completionIndex;
    private String completionStem;

    ConsolePanel() {
        super(new BorderLayout());
        final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        out.setEditable(false);
        out.setFont(mono);
        in.setFont(mono);
        in.setFocusTraversalKeysEnabled(false);
        in.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        StyleConstants.setBold(sCmd, true);
        StyleConstants.setForeground(sErr, new Color(0xD03A3A));
        StyleConstants.setForeground(sLog, new Color(0x7D8796));
        StyleConstants.setItalic(sLog, true);
        StyleConstants.setForeground(sOk, new Color(0x2E9D5A));
        final JPanel line = new JPanel(new BorderLayout());
        final JLabel prompt = new JLabel(" G4 > ");
        prompt.setFont(mono.deriveFont(Font.BOLD));
        line.add(prompt, BorderLayout.WEST);
        line.add(in, BorderLayout.CENTER);
        add(new JScrollPane(out), BorderLayout.CENTER);
        add(line, BorderLayout.SOUTH);
        in.addActionListener(e -> submit());
        in.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP -> {
                        if (!history.isEmpty()) {
                            historyPos = historyPos < 0 ? history.size() - 1 : Math.max(0, historyPos - 1);
                            in.setText(history.get(historyPos));
                        }
                        e.consume();
                    }
                    case KeyEvent.VK_DOWN -> {
                        if (historyPos >= 0) {
                            historyPos++;
                            in.setText(historyPos < history.size() ? history.get(historyPos) : "");
                            if (historyPos >= history.size()) {
                                historyPos = -1;
                            }
                        }
                        e.consume();
                    }
                    case KeyEvent.VK_TAB -> {
                        complete();
                        e.consume();
                    }
                    case KeyEvent.VK_ESCAPE -> in.setText("");
                    default -> completions = List.of();
                }
            }
        });
        print("G4editor console - type help, a command (box Layer 20cm 20cm 300um G4_Si at 0 0 10cm),", sLog);
        print("or a sentence (\"add a lead cylinder of radius 5 cm\"). Tab completes, Up recalls.", sLog);
    }

    void setScript(Script s) {
        this.script = s;
    }

    void focusInput() {
        in.requestFocusInWindow();
    }

    void setInput(String s) {
        in.setText(s);
        in.requestFocusInWindow();
    }

    private void submit() {
        final String text = in.getText();
        in.setText("");
        historyPos = -1;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (history.isEmpty() || !history.get(history.size() - 1).equals(line)) {
                history.add(line);
            }
            if (line.trim().equals("clear")) {
                out.setText("");
                continue;
            }
            print("G4 > " + line, sCmd);
            if (script != null) {
                script.run(line);
            }
        }
    }

    /** Runs a line as if typed, e.g. a fix from the assistant. */
    void run(String line) {
        print("G4 > " + line, sCmd);
        if (script != null) {
            script.run(line);
        }
    }

    private void complete() {
        if (script == null) {
            return;
        }
        final String t = in.getText();
        final int sp = t.lastIndexOf(' ');
        final String word = t.substring(sp + 1);
        if (completions.isEmpty() || completionStem == null || !t.startsWith(completionStem)) {
            completionStem = t.substring(0, sp + 1);
            completions = script.completions(word);
            completionIndex = 0;
            if (completions.size() > 1) {
                print(String.join("  ", completions.subList(0, Math.min(40, completions.size())))
                    + (completions.size() > 40 ? "  ..." : ""), sLog);
            }
        }
        if (!completions.isEmpty()) {
            in.setText(completionStem + completions.get(completionIndex++ % completions.size()));
        }
    }

    void print(String s) {
        print(s, sText);
    }

    void error(String s) {
        print(s, sErr);
    }

    void ok(String s) {
        print(s, sOk);
    }

    /** An action done with the mouse, as the command that replays it. */
    void logged(String s) {
        print(s.startsWith("#") ? s : "» " + s, sLog);
    }

    private void print(String s, SimpleAttributeSet style) {
        final Runnable r = () -> {
            final StyledDocument d = out.getStyledDocument();
            try {
                d.insertString(d.getLength(), s + "\n", style);
                if (d.getLength() > 400_000) {
                    d.remove(0, d.getLength() - 300_000);
                }
            } catch (BadLocationException ignored) {
                // nothing to show then
            }
            out.setCaretPosition(d.getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
