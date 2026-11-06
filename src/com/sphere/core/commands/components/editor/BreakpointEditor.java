package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * The small editor of what a breakpoint asks, popped up under its line: the
 * expression it stops on, the hit it waits for, or the message that makes it a
 * logpoint. The three are kept together, so switching from one to the other
 * loses nothing; Enter keeps them, Escape leaves the breakpoint as it was.
 */
final class BreakpointEditor {

    enum Kind {
        CONDITION("Expression", "Stop when this expression is true, e.g. i == 3"),
        HIT("Hit Count", "Stop from this hit on, e.g. 10"),
        LOG("Log Message", "Print this and go on; {expressions} are filled in");

        final String label;
        final String hint;

        Kind(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    private BreakpointEditor() {
    }

    static void show(Component anchor, int x, int y, BreakpointModel model, int line, Kind start) {
        BreakpointModel.Spec spec = model.spec(line);
        String[] values = {
            spec.condition() == null ? "" : spec.condition(),
            spec.hitCount() > 1 ? String.valueOf(spec.hitCount()) : "",
            spec.logMessage() == null ? "" : spec.logMessage()
        };
        Kind[] current = { start };

        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(EditorTheme.popupBorder()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        popup.setBackground(EditorTheme.popupBackground());

        EditorWidgets.FlatField field = new EditorWidgets.FlatField(start.hint);
        field.setColumns(36);
        field.setText(values[start.ordinal()]);

        JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        kinds.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (Kind k : Kind.values()) {
            EditorWidgets.FlatToggle toggle = new EditorWidgets.FlatToggle(k.label, k.hint);
            toggle.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
            toggle.setFocusable(false);
            toggle.setSelected(k == start);
            toggle.addActionListener(e -> {
                values[current[0].ordinal()] = field.getText();
                current[0] = k;
                field.setText(values[k.ordinal()]);
                field.setPlaceholderHint(k.hint);
                field.setInvalid(false);
                field.requestFocusInWindow();
            });
            group.add(toggle);
            kinds.add(toggle);
        }

        JLabel note = new JLabel("Line " + line + "  ·  Enter keeps it, Escape cancels");
        note.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        note.setForeground(EditorTheme.textMuted());
        note.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));

        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        panel.add(kinds, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.add(note, BorderLayout.SOUTH);
        popup.add(panel);

        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "accept");
        field.getActionMap().put("accept", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                values[current[0].ordinal()] = field.getText();
                int hits;
                try {
                    hits = values[1].isBlank() ? 0 : Integer.parseInt(values[1].strip());
                } catch (NumberFormatException ex) {
                    hits = -1;
                }
                if (hits < 0) {
                    field.setInvalid(true);
                    return;
                }
                model.set(line, new BreakpointModel.Spec(values[0], hits, values[2]));
                popup.setVisible(false);
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        field.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                popup.setVisible(false);
            }
        });

        popup.show(anchor, x, y);
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
    }
}
