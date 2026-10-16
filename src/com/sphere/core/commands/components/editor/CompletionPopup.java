package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.function.Consumer;

/**
 * Completion list anchored under the word being typed. It opens by itself after
 * two letters, after "." or "->" in code, or on Ctrl+Space; it filters as the
 * word grows; Enter or Tab inserts, Escape closes. The selected entry's
 * signature and documentation show underneath.
 */
public final class CompletionPopup {

    private static final int WIDTH = 480;
    private static final int ROWS = 10;

    private final CodeTextPane pane;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<CompletionProvider.Item> model = new DefaultListModel<>();
    private final JList<CompletionProvider.Item> list = new JList<>(model);
    private final JLabel docLabel = new JLabel();
    private final JPanel docPanel = new JPanel(new BorderLayout());
    private final Timer trigger;

    private CompletionProvider provider;
    private Consumer<CompletionProvider.Item> snippetHandler;
    private int anchorOffset = -1;
    private boolean autoTrigger = true;
    private boolean explicit;
    private boolean refreshQueued;

    public CompletionPopup(CodeTextPane pane, CompletionProvider provider) {
        this.pane = pane;
        this.provider = provider;
        this.trigger = new Timer(60, e -> refresh(false));
        this.trigger.setRepeats(false);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setFixedCellHeight(22);
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    accept();
                }
            }
        });
        list.addListSelectionListener(e -> updateDoc());

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setOpaque(true);

        docLabel.setVerticalAlignment(JLabel.TOP);
        docLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 7, 10));
        docPanel.add(docLabel, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(docPanel, BorderLayout.SOUTH);

        popup.setLayout(new BorderLayout());
        popup.add(content, BorderLayout.CENTER);
        popup.setFocusable(false);
        popup.putClientProperty("JPopupMenu.firePopupMenuCanceledOnClose", Boolean.TRUE);
        applyTheme(scroll, content);

        installKeys();
        installTriggers();
    }

    private void applyTheme(JScrollPane scroll, JPanel content) {
        Color bg = EditorTheme.widgetBackground();
        list.setBackground(bg);
        list.setForeground(EditorTheme.foreground());
        list.setSelectionBackground(EditorTheme.widgetSelection());
        list.setSelectionForeground(EditorTheme.foreground());
        scroll.getViewport().setBackground(bg);
        content.setBackground(bg);
        docPanel.setBackground(EditorTheme.shift(bg, EditorTheme.isDarkSurface() ? -0.12f : -0.03f));
        docPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, EditorTheme.widgetBorder()));
        docLabel.setForeground(EditorTheme.textMuted());
        popup.setBackground(bg);
        popup.setBorder(BorderFactory.createLineBorder(EditorTheme.widgetBorder()));
    }

    public void setProvider(CompletionProvider provider) {
        this.provider = provider;
    }

    public void setSnippetHandler(Consumer<CompletionProvider.Item> handler) {
        this.snippetHandler = handler;
    }

    public void setAutoTrigger(boolean value) {
        this.autoTrigger = value;
    }

    public boolean isVisible() {
        return popup.isVisible();
    }

    public void hide() {
        trigger.stop();
        popup.setVisible(false);
        explicit = false;
    }

    /** Tab inserts the selection when the list is up. */
    public boolean acceptIfVisible() {
        if (!popup.isVisible() || list.getSelectedValue() == null) {
            return false;
        }
        accept();
        return true;
    }

    // ---- Keys and triggers -------------------------------------------------

    private void installKeys() {
        JComponent c = pane;
        c.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK), "sphere-complete");
        c.getActionMap().put("sphere-complete", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { show(); }
        });

        bind(c, KeyEvent.VK_ESCAPE, "sphere-complete-hide", this::hide);
        bind(c, KeyEvent.VK_ENTER, "sphere-complete-accept", this::accept);
        bind(c, KeyEvent.VK_DOWN, "sphere-complete-down", () -> move(1));
        bind(c, KeyEvent.VK_UP, "sphere-complete-up", () -> move(-1));
        bind(c, KeyEvent.VK_PAGE_DOWN, "sphere-complete-pgdn", () -> move(ROWS - 1));
        bind(c, KeyEvent.VK_PAGE_UP, "sphere-complete-pgup", () -> move(-(ROWS - 1)));
    }

    /**
     * Only intercepts a key while the list is up, so Enter, arrows and Escape keep
     * their editor behavior the rest of the time.
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

    private void installTriggers() {
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onInsert(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (popup.isVisible()) {
                    queueRefresh();
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attribute-only
            }
        });
        pane.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) {
                    hide();
                }
            }
        });
        pane.addCaretListener(e -> {
            if (popup.isVisible() && !refreshQueued) {
                int dot = e.getDot();
                if (dot < anchorOffset || dot > anchorOffset + 200) {
                    hide();
                }
            }
        });
    }

    private void onInsert(DocumentEvent e) {
        if (!pane.isFocusOwner()) {
            return;
        }
        if (e.getLength() != 1) {
            hide();
            return;
        }
        char c;
        char prev = 0;
        try {
            Document doc = pane.getDocument();
            c = doc.getText(e.getOffset(), 1).charAt(0);
            if (e.getOffset() > 0) {
                prev = doc.getText(e.getOffset() - 1, 1).charAt(0);
            }
        } catch (BadLocationException ex) {
            return;
        }
        if (Character.isLetterOrDigit(c) || c == '_' || (c == '!' && pane.getLanguage() == LanguageSpec.JULIA)) {
            if (popup.isVisible()) {
                queueRefresh();
            } else if (autoTrigger && !inLiteral(e.getOffset())) {
                trigger.restart();
            }
            return;
        }
        boolean member = c == '.' || (c == '>' && prev == '-') || (c == ':' && prev == ':');
        if (member && autoTrigger && pane.getLanguage().isCode() && !inLiteral(e.getOffset())
                && !(c == '.' && Character.isDigit(prev))) {
            hide();
            trigger.restart();
            return;
        }
        hide();
    }

    private boolean inLiteral(int offset) {
        SyntaxHighlighter h = pane.getSyntax();
        if (h == null || offset <= 0) {
            return false;
        }
        TokenKind k = h.liveKindAt(offset - 1);
        return k == TokenKind.COMMENT || k == TokenKind.STRING || k == TokenKind.CHARACTER;
    }

    private void queueRefresh() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        SwingUtilities.invokeLater(() -> {
            refreshQueued = false;
            refresh(explicit);
        });
    }

    private void move(int delta) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int index = list.getSelectedIndex() + delta;
        if (Math.abs(delta) == 1) {
            index = Math.floorMod(index, size);
        } else {
            index = Math.max(0, Math.min(size - 1, index));
        }
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    // ---- Showing -----------------------------------------------------------

    /** Ctrl+Space: opens even without a prefix. */
    public void show() {
        explicit = true;
        refresh(true);
    }

    private void refresh(boolean forced) {
        if (provider == null || !pane.isShowing()) {
            // A popup needs its invoker on screen to find where to open.
            return;
        }
        Document doc = pane.getDocument();
        String text;
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            return;
        }
        int offset = pane.getCaretPosition();
        if (pane.getSelectionStart() != pane.getSelectionEnd()) {
            hide();
            return;
        }
        String prefix = prefixAt(text, offset);
        int anchor = offset - prefix.length();
        boolean member = provider.isMemberContext(text, anchor);
        boolean wasVisible = popup.isVisible();
        if (!forced && !wasVisible && prefix.length() < 2 && !member) {
            return;
        }
        if (!forced && prefix.isEmpty() && !member) {
            hide();
            return;
        }
        List<CompletionProvider.Item> items = provider.complete(text, offset, prefix);
        if (items == null || items.isEmpty()) {
            hide();
            return;
        }
        // Nothing left to complete: the word is already the only candidate.
        if (!forced && items.size() == 1 && items.get(0).insert.equals(prefix)) {
            hide();
            return;
        }
        anchorOffset = anchor;
        CompletionProvider.Item previous = list.getSelectedValue();
        model.clear();
        for (CompletionProvider.Item item : items) {
            model.addElement(item);
        }
        int keep = 0;
        if (previous != null && wasVisible) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).label.equals(previous.label) && i < 3) {
                    keep = i;
                    break;
                }
            }
        }
        list.setSelectedIndex(keep);
        list.ensureIndexIsVisible(keep);
        list.setFont(pane.getFont().deriveFont(pane.getFont().getSize2D() - 0.5f));
        int rows = Math.min(ROWS, items.size());
        list.setVisibleRowCount(rows);
        Dimension listSize = new Dimension(WIDTH, rows * list.getFixedCellHeight() + 2);
        ((JComponent) list.getParent().getParent()).setPreferredSize(listSize);
        updateDoc();

        try {
            Rectangle2D r = pane.modelToView2D(anchor);
            if (r == null) {
                return;
            }
            int x = (int) r.getX() - 30;
            int y = (int) (r.getY() + r.getHeight() + 2);
            popup.pack();
            // Without room below the line, the list opens above it rather than over it.
            java.awt.GraphicsConfiguration gc = pane.getGraphicsConfiguration();
            if (gc != null && pane.isShowing()) {
                java.awt.Point onScreen = new java.awt.Point(x, y);
                SwingUtilities.convertPointToScreen(onScreen, pane);
                java.awt.Rectangle screen = gc.getBounds();
                java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
                int bottom = screen.y + screen.height - insets.bottom;
                int height = popup.getPreferredSize().height;
                if (onScreen.y + height > bottom && onScreen.y - r.getHeight() - height - 4 > screen.y + insets.top) {
                    y = (int) r.getY() - height - 2;
                }
            }
            if (wasVisible && popup.isVisible()) {
                popup.setPopupSize(popup.getPreferredSize());
                popup.revalidate();
                popup.repaint();
                popup.show(pane, x, y);
            } else {
                popup.show(pane, x, y);
            }
        } catch (BadLocationException ignored) {
            // caret out of view; nothing to anchor to
        }
    }

    private void updateDoc() {
        CompletionProvider.Item item = list.getSelectedValue();
        String doc = item == null ? null : item.doc;
        if (doc == null || doc.isBlank()) {
            docPanel.setVisible(false);
        } else {
            String[] lines = doc.split("\n", 2);
            String head = CodeTextPane.escape(lines[0]);
            String body = lines.length > 1 ? CodeTextPane.escape(trimDoc(lines[1])).replace("\n", "<br>") : "";
            String fg = hex(EditorTheme.foreground());
            String mono = pane.getFont().getFamily();
            docLabel.setText("<html><div style='width:" + (WIDTH - 28) + "px'>"
                             + "<span style='font-family:\"" + mono + "\";color:" + fg + "'>" + head + "</span>"
                             + (body.isEmpty() ? "" : "<br><span>" + body + "</span>") + "</div></html>");
            docPanel.setVisible(true);
        }
        if (popup.isVisible()) {
            popup.pack();
        }
    }

    private static String trimDoc(String doc) {
        String t = doc.strip();
        String[] lines = t.split("\n");
        if (lines.length > 7) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.append('…').toString();
        }
        return t;
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void accept() {
        CompletionProvider.Item item = list.getSelectedValue();
        hide();
        if (item == null || anchorOffset < 0) {
            return;
        }
        int caret = pane.getCaretPosition();
        if (caret < anchorOffset) {
            return;
        }
        SmartCompletion.recordUse(item.snippet != null ? "snippet:" + item.snippet.trigger : item.label);
        if (item.snippet != null && snippetHandler != null) {
            snippetHandler.accept(item);
            return;
        }
        pane.atomic(() -> {
            try {
                Document doc = pane.getDocument();
                String insert = item.insert;
                int back = item.caretBack;
                // "Fill()" before an existing '(' would double the parentheses.
                if (insert.endsWith("()") && caret < doc.getLength() && doc.getText(caret, 1).equals("(")) {
                    insert = insert.substring(0, insert.length() - 2);
                    back = 0;
                }
                doc.remove(anchorOffset, caret - anchorOffset);
                doc.insertString(anchorOffset, insert, null);
                pane.setCaretPosition(anchorOffset + insert.length() - back);
            } catch (BadLocationException ignored) {
                // the buffer moved under us; the user can retype
            }
        });
    }

    /** Where the completion anchors, for a snippet insertion. */
    public int anchorOffset() {
        return anchorOffset;
    }

    static String prefixAt(String text, int offset) {
        int i = Math.min(offset, text.length());
        int start = i;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) {
            start--;
        }
        return text.substring(start, i);
    }

    // ---- Row ---------------------------------------------------------------

    private final class Row extends JComponent implements ListCellRenderer<CompletionProvider.Item> {
        private CompletionProvider.Item item;
        private boolean selected;

        Row() {
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CompletionProvider.Item> l,
                                                      CompletionProvider.Item value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.item = value;
            this.selected = isSelected;
            setFont(l.getFont());
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Object aa = pane.getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                aa != null ? aa : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(selected ? EditorTheme.widgetSelection() : EditorTheme.widgetBackground());
            g2.fillRect(0, 0, w, h);
            if (item == null) {
                g2.dispose();
                return;
            }
            new EditorIcons.KindIcon(item.kind, 15).paintIcon(this, g2, 7, (h - 15) / 2);

            Font font = getFont();
            Font bold = font.deriveFont(Font.BOLD);
            FontMetrics fm = g2.getFontMetrics(font);
            FontMetrics fb = g2.getFontMetrics(bold);
            int baseline = (h + fm.getAscent() - fm.getDescent()) / 2;
            int x = 30;
            String label = item.label;
            int[] matches = item.matches;
            Color normal = EditorTheme.foreground();
            Color hit = EditorTheme.readable(new Color(24, 163, 255));
            int mi = 0;
            for (int i = 0; i < label.length(); i++) {
                boolean matched = false;
                if (matches != null) {
                    while (mi < matches.length && matches[mi] < i) {
                        mi++;
                    }
                    matched = mi < matches.length && matches[mi] == i;
                }
                String ch = String.valueOf(label.charAt(i));
                g2.setFont(matched ? bold : font);
                g2.setColor(matched ? hit : normal);
                g2.drawString(ch, x, baseline);
                x += matched ? fb.stringWidth(ch) : fm.stringWidth(ch);
            }

            String detail = item.detail;
            if (!detail.isEmpty()) {
                Font small = font.deriveFont(font.getSize2D() - 1f);
                FontMetrics fs = g2.getFontMetrics(small);
                int room = w - x - 22;
                String shown = detail;
                while (shown.length() > 1 && fs.stringWidth(shown) > room) {
                    shown = shown.substring(0, shown.length() - 2) + "…";
                }
                if (room > 30) {
                    g2.setFont(small);
                    g2.setColor(selected ? EditorTheme.mix(normal, EditorTheme.widgetSelection(), 0.75f)
                                         : EditorTheme.textMuted());
                    g2.drawString(shown, w - 10 - fs.stringWidth(shown), baseline);
                }
            }
            g2.dispose();
        }
    }
}
