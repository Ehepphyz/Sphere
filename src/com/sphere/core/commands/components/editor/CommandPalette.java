package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One box for everything, as in VS Code. Typing a name finds a file; ">" runs a
 * command; "@" jumps to a symbol of this file and "#" to one of any open file;
 * ":" goes to a line. The same box picks a language or a line ending when a
 * status bar entry asks it to.
 */
public final class CommandPalette extends EditorWidgets.Card {

    private static final int WIDTH = 640;
    private static final int ROWS = 12;

    /** One entry of the list. */
    public static final class Item {
        public final String label;
        public final String detail;
        /** Shown on the right: a shortcut ("Ctrl+Shift+P") or a location. */
        public final String hint;
        public final Icon icon;
        public final Object payload;
        /** Fixed order bonus, before matching: recent first. */
        public final int bias;
        int score;
        int[] matches;
        boolean separatorAbove;
        String group;

        public Item(String label, String detail, String hint, Icon icon, Object payload, int bias) {
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.hint = hint == null ? "" : hint;
            this.icon = icon;
            this.payload = payload;
            this.bias = bias;
        }

        public Item group(String name) {
            this.group = name;
            return this;
        }
    }

    /** Supplies the entries of a mode and acts on the one chosen. */
    public interface Source {
        /** Entries for what follows the prefix; the palette ranks them. */
        List<Item> items(String query);

        void accept(Item item, String query);

        /** Called as the selection moves, for a live preview; may do nothing. */
        default void preview(Item item) {
        }

        /** Called when the box closes without a choice, to undo a preview. */
        default void cancel() {
        }

        /** When true, items are shown as given rather than ranked by the query. */
        default boolean ordered() {
            return false;
        }

        /** Text shown when there is nothing to list. */
        default String empty(String query) {
            return "No matching results";
        }
    }

    private final EditorWidgets.FlatField field = new EditorWidgets.FlatField("Search files by name (> commands, @ symbols, : line, ? help)");
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JLabel emptyLabel = new JLabel();
    private final JScrollPane scroll;
    private final java.util.Map<String, Source> modes = new java.util.LinkedHashMap<>();
    private Source fixed;
    private Source active;
    private String activePrefix = "";
    private boolean closing;
    /** When the box last opened; a focus change soon after is the menu that opened it closing. */
    private long shownAt;
    private static final long SETTLE_MS = 600;
    private Runnable onClose;
    private final PropertyChangeListener focusWatcher;

    public CommandPalette() {
        super(new BorderLayout(0, 6), 10, 10);
        setVisible(false);

        field.setFont(EditorWidgets.uiFont(Font.PLAIN, 14));
        field.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setFixedCellHeight(28);
        list.setFocusable(false);
        list.setOpaque(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    list.setSelectedIndex(i);
                    accept();
                }
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && active != null && list.getSelectedValue() != null) {
                active.preview(list.getSelectedValue());
            }
        });

        scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        emptyLabel.setFont(EditorWidgets.uiFont(Font.PLAIN, 12));
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(scroll, BorderLayout.CENTER);
        center.add(emptyLabel, BorderLayout.SOUTH);

        add(field, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        bind(KeyEvent.VK_ESCAPE, "palette-close", () -> close(true));
        bind(KeyEvent.VK_ENTER, "palette-accept", this::accept);
        bind(KeyEvent.VK_DOWN, "palette-down", () -> move(1));
        bind(KeyEvent.VK_UP, "palette-up", () -> move(-1));
        bind(KeyEvent.VK_PAGE_DOWN, "palette-pgdn", () -> move(ROWS - 1));
        bind(KeyEvent.VK_PAGE_UP, "palette-pgup", () -> move(-(ROWS - 1)));

        // Clicking back into the code closes the box, as a lost focus should.
        focusWatcher = evt -> {
            if (!isVisible() || closing) {
                return;
            }
            Object now = evt.getNewValue();
            if (now instanceof Component c && !SwingUtilities.isDescendingFrom(c, this)) {
                if (System.currentTimeMillis() - shownAt < SETTLE_MS) {
                    // Opened from a menu (Preferences > Change Language Mode...): the
                    // closing menu hands the focus back to the code just after the box
                    // opens. That is not the user leaving it; the box takes the focus
                    // back instead of closing on the spot.
                    SwingUtilities.invokeLater(() -> {
                        if (isVisible() && !closing) {
                            field.requestFocusInWindow();
                        }
                    });
                    return;
                }
                SwingUtilities.invokeLater(() -> close(true));
            }
        };
    }

    private void bind(int key, String name, Runnable r) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    /** Registers what a prefix lists: "" for files, ">" for commands... */
    public void addMode(String prefix, Source source) {
        modes.put(prefix, source);
    }

    public void setOnClose(Runnable r) {
        this.onClose = r;
    }

    // ---- Opening -----------------------------------------------------------

    /** Opens with a prefix already typed: ">" for commands, "@" for symbols. */
    public void open(String text) {
        fixed = null;
        field.setPlaceholderHint("Search files by name (> commands, @ symbols, : line, ? help)");
        show(text);
    }

    /** Opens a one-off list (languages, line endings) with its own prompt. */
    public void pick(String prompt, Source source) {
        fixed = source;
        field.setPlaceholderHint(prompt);
        show("");
    }

    private void show(String text) {
        closing = false;
        shownAt = System.currentTimeMillis();
        setVisible(true);
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
        field.setText(text);
        refilter();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focusWatcher);
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.setCaretPosition(field.getText().length());
        });
    }

    public boolean isOpen() {
        return isVisible();
    }

    public void close(boolean cancelled) {
        if (!isVisible()) {
            return;
        }
        closing = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focusWatcher);
        if (cancelled && active != null) {
            active.cancel();
        }
        setVisible(false);
        if (getParent() != null) {
            getParent().repaint();
        }
        if (onClose != null) {
            onClose.run();
        }
    }

    // ---- Filtering ---------------------------------------------------------

    private void refilter() {
        String text = field.getText();
        Source source = fixed;
        String query = text;
        if (source == null) {
            String prefix = "";
            for (String p : modes.keySet()) {
                if (!p.isEmpty() && text.startsWith(p) && p.length() > prefix.length()) {
                    prefix = p;
                }
            }
            source = modes.get(prefix);
            query = text.substring(prefix.length()).trim();
            activePrefix = prefix;
        }
        active = source;
        model.clear();
        if (source == null) {
            updateSize(0, "");
            return;
        }
        List<Item> items = source.items(query);
        List<Item> ranked = new ArrayList<>();
        if (source.ordered() || query.isEmpty()) {
            ranked.addAll(items);
        } else {
            for (Item it : items) {
                int[] pos = new int[query.length()];
                int s = FuzzyMatcher.score(query, it.label, pos);
                if (s == FuzzyMatcher.NO_MATCH) {
                    // A path or detail can match too ("src/Foo" finds Foo.cpp under src).
                    int d = FuzzyMatcher.score(query, it.detail + "/" + it.label, null);
                    if (d == FuzzyMatcher.NO_MATCH) {
                        continue;
                    }
                    it.score = d / 2 + it.bias;
                    it.matches = null;
                } else {
                    it.score = s + it.bias;
                    it.matches = pos;
                }
                ranked.add(it);
            }
            ranked.sort((a, b) -> Integer.compare(b.score, a.score));
        }
        String lastGroup = null;
        int shown = 0;
        for (Item it : ranked) {
            if (shown++ >= 400) {
                break;
            }
            it.separatorAbove = it.group != null && lastGroup != null && !it.group.equals(lastGroup)
                                && (source.ordered() || query.isEmpty());
            lastGroup = it.group;
            model.addElement(it);
        }
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }
        updateSize(model.size(), model.isEmpty() ? source.empty(query) : "");
    }

    private void updateSize(int count, String emptyText) {
        int rows = Math.min(ROWS, count);
        scroll.setPreferredSize(new Dimension(WIDTH - 40, Math.max(0, rows * list.getFixedCellHeight() + (rows > 0 ? 2 : 0))));
        scroll.setVisible(rows > 0);
        emptyLabel.setText(emptyText);
        emptyLabel.setForeground(EditorTheme.textMuted());
        emptyLabel.setVisible(!emptyText.isEmpty());
        revalidate();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(WIDTH, d.height);
    }

    private void move(int delta) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int i = list.getSelectedIndex() + delta;
        i = Math.abs(delta) == 1 ? Math.floorMod(i, size) : Math.max(0, Math.min(size - 1, i));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void accept() {
        Item item = list.getSelectedValue();
        Source source = active;
        String text = field.getText();
        String query = fixed != null ? text : text.substring(Math.min(activePrefix.length(), text.length())).trim();
        if (item == null && source != null && !(source.ordered())) {
            // ":42" has no list entry to pick; the source still gets the query.
            close(false);
            source.accept(null, query);
            return;
        }
        close(false);
        if (source != null) {
            source.accept(item, query);
        }
    }

    // ---- Row ---------------------------------------------------------------

    private final class Row extends JComponent implements ListCellRenderer<Item> {
        private Item item;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.item = value;
            this.selected = isSelected;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            int w = getWidth();
            int h = getHeight();
            if (selected) {
                g2.setColor(EditorTheme.widgetSelection());
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(0, 1, w, h - 2, 7, 7));
            }
            if (item == null) {
                g2.dispose();
                return;
            }
            if (item.separatorAbove) {
                g2.setColor(EditorTheme.widgetBorder());
                g2.fillRect(8, 0, w - 16, 1);
            }
            int x = 10;
            if (item.icon != null) {
                item.icon.paintIcon(this, g2, x, (h - item.icon.getIconHeight()) / 2);
                x += Math.max(18, item.icon.getIconWidth()) + 8;
            }
            Font font = EditorWidgets.uiFont(Font.PLAIN, 13);
            Font bold = EditorWidgets.uiFont(Font.BOLD, 13);
            FontMetrics fm = g2.getFontMetrics(font);
            FontMetrics fb = g2.getFontMetrics(bold);
            int base = (h + fm.getAscent() - fm.getDescent()) / 2;
            Color normal = EditorTheme.foreground();
            Color hit = EditorTheme.readable(new Color(24, 163, 255));
            int mi = 0;
            int[] matches = item.matches;
            String label = item.label;
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
                g2.drawString(ch, x, base);
                x += matched ? fb.stringWidth(ch) : fm.stringWidth(ch);
            }

            // Right: the hint as keycaps, or as dim text.
            int right = w - 10;
            if (!item.hint.isEmpty()) {
                Font small = EditorWidgets.uiFont(Font.PLAIN, 11);
                FontMetrics fs = g2.getFontMetrics(small);
                if (item.hint.startsWith("key:")) {
                    String keys = item.hint.substring(4);
                    int kw = 0;
                    for (String part : keys.split("\\+")) {
                        kw += fs.stringWidth(part) + 12 + 4;
                    }
                    int kx = right - kw;
                    g2.setFont(small);
                    CodeTextPane.paintKeys(g2, fs, keys, kx, (h - 18) / 2, 18,
                                           selected ? EditorTheme.foreground() : EditorTheme.textMuted());
                    right = kx - 8;
                } else {
                    g2.setFont(small);
                    g2.setColor(EditorTheme.textMuted());
                    String hint = item.hint;
                    while (hint.length() > 4 && fs.stringWidth(hint) > (w - x) / 2) {
                        hint = "…" + hint.substring(3);
                    }
                    g2.drawString(hint, right - fs.stringWidth(hint), base);
                    right -= fs.stringWidth(hint) + 10;
                }
            }
            if (!item.detail.isEmpty()) {
                Font small = EditorWidgets.uiFont(Font.PLAIN, 12);
                FontMetrics fs = g2.getFontMetrics(small);
                int room = right - x - 12;
                String detail = item.detail;
                while (detail.length() > 3 && fs.stringWidth(detail) > room) {
                    detail = "…" + detail.substring(3);
                }
                if (room > 40) {
                    g2.setFont(small);
                    g2.setColor(EditorTheme.textMuted());
                    g2.drawString(detail, x + 12, base);
                }
            }
            g2.dispose();
        }
    }

    /** Builds a source from two lambdas, for the simple pickers. */
    public static Source of(java.util.function.Function<String, List<Item>> items,
                            Consumer<Item> accept, boolean ordered) {
        return new Source() {
            @Override public List<Item> items(String query) { return items.apply(query); }
            @Override public void accept(Item item, String query) { if (item != null) accept.accept(item); }
            @Override public boolean ordered() { return ordered; }
        };
    }
}
