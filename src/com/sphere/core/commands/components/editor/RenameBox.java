package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * The box F2 opens under a name: the new name, typed over the old one, and what
 * the rename will change ("4 occurrences in loop()"), with what is wrong with the
 * name if anything. Enter renames, Escape leaves everything as it was.
 */
final class RenameBox extends EditorWidgets.Card {

    final EditorWidgets.FlatField field = new EditorWidgets.FlatField("New name");
    private final JLabel info = new JLabel();
    private final SmartRename.Plan plan;
    private final LanguageSpec spec;
    private final String text;

    RenameBox(SmartRename.Plan plan, LanguageSpec spec, String text, Consumer<String> rename, Runnable cancel) {
        super(new BorderLayout(0, 4), 8, 8);
        this.plan = plan;
        this.spec = spec;
        this.text = text;
        field.setText(plan.name());
        field.setColumns(Math.max(14, plan.name().length() + 6));
        field.setFont(FontLoader.getTerminalFont(Font.PLAIN, 13));
        info.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));

        JPanel body = new JPanel(new BorderLayout(0, 3));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        body.add(field, BorderLayout.CENTER);
        body.add(info, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { check(); }
            @Override public void removeUpdate(DocumentEvent e) { check(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "rename");
        field.getActionMap().put("rename", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String name = field.getText().strip();
                if (SmartRename.problemWith(name, spec) == null) {
                    rename.accept(name);
                }
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        field.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel.run();
            }
        });
        check();
    }

    /** What the rename will do, or why it cannot; a name already used in the scope is said but allowed. */
    private void check() {
        String name = field.getText().strip();
        String problem = SmartRename.problemWith(name, spec);
        int n = plan.ranges().size();
        String what = n + (n == 1 ? " occurrence " : " occurrences ") + plan.scope();
        if (problem != null) {
            info.setForeground(EditorTheme.errorSquiggle());
            info.setText(problem);
            field.setInvalid(true);
        } else if (!name.equals(plan.name()) && usedInScope(name)) {
            info.setForeground(EditorTheme.warningSquiggle());
            info.setText("'" + name + "' is already used " + plan.scope() + " · " + what);
            field.setInvalid(false);
        } else {
            info.setForeground(EditorTheme.textMuted());
            info.setText("Enter renames " + what + " · Escape cancels");
            field.setInvalid(false);
        }
        revalidate();
        repaint();
    }

    private boolean usedInScope(String name) {
        int from = Math.max(0, plan.scopeFrom());
        int to = Math.min(text.length(), plan.scopeTo());
        String scope = text.substring(from, Math.max(from, to));
        return java.util.regex.Pattern.compile("(?<![\\w])" + java.util.regex.Pattern.quote(name) + "(?![\\w])")
            .matcher(scope).find();
    }
}
