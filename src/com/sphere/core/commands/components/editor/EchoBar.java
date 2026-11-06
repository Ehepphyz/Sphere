package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * The bar Edit Echo raises under the code while lines are offered the last edit:
 * how many, and what to do with them (apply all, go to the next, dismiss).
 */
final class EchoBar extends EditorWidgets.Card {

    private final JLabel text = new JLabel();

    EchoBar(Runnable applyAll, Runnable next, Runnable dismiss) {
        super(new BorderLayout(), 8, 8);
        setVisible(false);
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));

        text.setIcon(EditorIcons.bolt(14, EditorTheme::accent));
        text.setIconTextGap(7);
        text.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        text.setForeground(EditorTheme.foreground());

        EditorWidgets.FlatButton apply = new EditorWidgets.FlatButton("Apply All", "Make the same edit on every line shown (Alt+Enter)");
        apply.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        apply.addActionListener(e -> applyAll.run());
        EditorWidgets.FlatButton go = new EditorWidgets.FlatButton("Next",
            "Go to the next line offered the edit (Alt+] or Alt+Page Down)");
        go.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        go.addActionListener(e -> next.run());
        EditorWidgets.FlatButton close = new EditorWidgets.FlatButton(EditorIcons.close(14, EditorTheme::foreground),
                                                                      "Dismiss (Escape)");
        close.addActionListener(e -> dismiss.run());
        for (EditorWidgets.FlatButton b : new EditorWidgets.FlatButton[] { apply, go, close }) {
            b.setFocusable(false);
        }

        row.add(text);
        row.add(Box.createHorizontalStrut(14));
        row.add(apply);
        row.add(Box.createHorizontalStrut(2));
        row.add(go);
        row.add(Box.createHorizontalStrut(2));
        row.add(close);
        add(row, BorderLayout.CENTER);
    }

    /** Shows the bar for that many lines, or hides it at none. */
    void showCount(int count) {
        boolean visible = count > 0;
        if (visible) {
            text.setForeground(EditorTheme.foreground());
            text.setText("Edit Echo: the same edit fits " + count + (count == 1 ? " other line" : " other lines"));
        }
        if (visible != isVisible() || visible) {
            setVisible(visible);
            if (getParent() != null) {
                getParent().revalidate();
                getParent().repaint();
            }
        }
    }
}
