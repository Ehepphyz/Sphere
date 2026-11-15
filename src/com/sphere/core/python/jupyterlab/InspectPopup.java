package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.EditorTheme;
import com.sphere.fonts.FontLoader;

import javax.swing.BorderFactory;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import javax.swing.event.CaretListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Shift+Tab: the signature and documentation of the name under the caret, as
 * the kernel reads them on the live object, in a box beside the code. Any key
 * or a caret move closes it.
 */
final class InspectPopup {

    private final JPopupMenu popup = new JPopupMenu();
    private ConsoleArea text = new ConsoleArea();
    private final JScrollPane scroll;
    private JTextComponent owner;
    private CaretListener closeOnMove;
    private final KeyAdapter closeOnKey = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() != KeyEvent.VK_SHIFT && e.getKeyCode() != KeyEvent.VK_TAB) {
                hide();
            }
        }
    };

    InspectPopup() {
        text.setFocusable(false);
        scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        popup.setLayout(new java.awt.BorderLayout());
        popup.add(scroll);
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(EditorTheme.widgetBorder()));
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { detach(); }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { detach(); }
        });
    }

    void show(JTextComponent editor, String ansiText) {
        hide();
        if (ansiText == null || ansiText.isBlank() || !editor.isShowing()) {
            return;
        }
        java.awt.Color bg = EditorTheme.widgetBackground();
        Font mono = FontLoader.getTerminalFont(Font.PLAIN, 12);
        text = new ConsoleArea();
        text.setFocusable(false);
        text.setFont(mono);
        text.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        new AnsiText(text, EditorTheme.foreground(), bg, 400).append(ansiText.strip());
        text.setCaretPosition(0);
        scroll.setViewportView(text);
        scroll.getViewport().setBackground(bg);
        int lines = text.getDocument().getDefaultRootElement().getElementCount();
        int width = 640;
        int height = Math.min(320, 14 + lines * (mono.getSize() + 5));
        popup.setPreferredSize(new Dimension(width, Math.max(40, height)));
        try {
            Rectangle r = editor.modelToView2D(editor.getCaretPosition()).getBounds();
            popup.show(editor, Math.max(0, r.x - 20), r.y + r.height + 2);
        } catch (javax.swing.text.BadLocationException | NullPointerException e) {
            return;
        }
        owner = editor;
        int at = editor.getCaretPosition();
        closeOnMove = e -> {
            if (e.getDot() != at) {
                SwingUtilities.invokeLater(this::hide);
            }
        };
        editor.addCaretListener(closeOnMove);
        editor.addKeyListener(closeOnKey);
    }

    void hide() {
        popup.setVisible(false);
        detach();
    }

    boolean isVisible() {
        return popup.isVisible();
    }

    private void detach() {
        if (owner != null) {
            owner.removeCaretListener(closeOnMove);
            owner.removeKeyListener(closeOnKey);
            owner = null;
        }
    }
}
