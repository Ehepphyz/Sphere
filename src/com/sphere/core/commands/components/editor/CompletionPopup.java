package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Completion list anchored under the caret. Ctrl+Space opens it, Enter or a
 * double click inserts, Escape closes.
 */
public final class CompletionPopup {

    private final CodeTextPane pane;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<CompletionProvider.Item> model = new DefaultListModel<>();
    private final JList<CompletionProvider.Item> list = new JList<>(model);

    private CompletionProvider provider;
    private int anchorOffset = -1;

    public CompletionPopup(CodeTextPane pane, CompletionProvider provider) {
        this.pane = pane;
        this.provider = provider;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(EditorTheme.popupBackground());
        list.setForeground(EditorTheme.foreground());
        list.setSelectionBackground(EditorTheme.popupSelection());
        list.setSelectionForeground(EditorTheme.foreground());
        list.setFont(pane.getFont());
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    accept();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(320, 180));

        popup.setBorder(BorderFactory.createLineBorder(EditorTheme.popupBorder()));
        popup.add(scroll);
        popup.setFocusable(false);

        installKeys();
    }

    public void setProvider(CompletionProvider provider) {
        this.provider = provider;
    }

    private void installKeys() {
        JComponent c = pane;
        c.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK), "sphere-complete");
        c.getActionMap().put("sphere-complete", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { show(); }
        });

        bind(c, KeyEvent.VK_ESCAPE, "sphere-complete-hide", () -> popup.setVisible(false));
        bind(c, KeyEvent.VK_ENTER, "sphere-complete-accept", this::accept);
        bind(c, KeyEvent.VK_DOWN, "sphere-complete-down", () -> move(1));
        bind(c, KeyEvent.VK_UP, "sphere-complete-up", () -> move(-1));
    }

    /**
     * Only intercepts a key while the list is up, so Enter, arrows and Escape keep
     * their editor behaviour the rest of the time.
     */
    private void bind(JComponent c, int keyCode, String name, Runnable action) {
        final Object previous = c.getInputMap(JComponent.WHEN_FOCUSED)
                                 .get(KeyStroke.getKeyStroke(keyCode, 0));
        final javax.swing.Action fallback = previous == null ? null : c.getActionMap().get(previous);

        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (popup.isVisible()) {
                    action.run();
                } else if (fallback != null) {
                    fallback.actionPerformed(e);
                }
            }
        });
    }

    private void move(int delta) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int index = Math.floorMod(list.getSelectedIndex() + delta, size);
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    public void show() {
        if (provider == null) {
            return;
        }
        String text = pane.getText();
        int offset = pane.getCaretPosition();
        String prefix = prefixAt(text, offset);
        anchorOffset = offset - prefix.length();

        List<CompletionProvider.Item> items = provider.complete(text, offset, prefix);
        if (items == null || items.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        model.clear();
        for (CompletionProvider.Item item : items) {
            model.addElement(item);
        }
        list.setSelectedIndex(0);

        try {
            Rectangle2D r = pane.modelToView2D(offset);
            if (r == null) {
                return;
            }
            popup.show(pane, (int) r.getX(), (int) (r.getY() + r.getHeight()));
            SwingUtilities.invokeLater(pane::requestFocusInWindow);
        } catch (BadLocationException ignored) {
            // caret out of view; nothing to anchor to
        }
    }

    private void accept() {
        CompletionProvider.Item item = list.getSelectedValue();
        popup.setVisible(false);
        if (item == null || anchorOffset < 0) {
            return;
        }
        try {
            int caret = pane.getCaretPosition();
            pane.getDocument().remove(anchorOffset, caret - anchorOffset);
            pane.getDocument().insertString(anchorOffset, item.insert, null);
        } catch (BadLocationException ignored) {
            // the buffer moved under us; the user can retype
        }
    }

    static String prefixAt(String text, int offset) {
        int i = Math.min(offset, text.length());
        int start = i;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, i);
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof CompletionProvider.Item item) {
                setText(item.label);
                Color detail = EditorTheme.mix(EditorTheme.foreground(),
                                               EditorTheme.popupBackground(), 0.45f);
                setToolTipText(item.detail.isEmpty() ? null : item.detail);
                if (!selected) {
                    setForeground("keyword".equals(item.detail)
                                  ? EditorTheme.token(TokenKind.KEYWORD)
                                  : "type".equals(item.detail)
                                    ? EditorTheme.token(TokenKind.TYPE) : detail);
                }
            }
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            return this;
        }
    }
}
