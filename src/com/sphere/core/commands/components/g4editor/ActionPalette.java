package com.sphere.components.g4editor;

import com.sphere.components.editor.FuzzyMatcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every command of the menu bar in one searchable list (Ctrl+Shift+P), as
 * code editors have it: a few letters of each word find it ("exp root",
 * "sec y", "nist"), Enter runs it. Nothing is listed twice: the palette reads
 * the menus as they are when it opens, recent files included.
 */
final class ActionPalette {

    private ActionPalette() {
    }

    /** One command: where it is in the menus, its shortcut, and the item that runs it. */
    record Entry(String path, String keys, JMenuItem item) {
    }

    static List<Entry> entries(JMenuBar bar) {
        final List<Entry> out = new ArrayList<>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            final JMenu m = bar.getMenu(i);
            if (m != null) {
                collect(m, m.getText(), out);
            }
        }
        return out;
    }

    private static void collect(JMenu menu, String path, List<Entry> out) {
        for (Component c : menu.getMenuComponents()) {
            if (c instanceof JMenu sub) {
                collect(sub, path + " › " + sub.getText(), out);
            } else if (c instanceof JMenuItem it && it.isEnabled() && it.getText() != null && !it.getText().isBlank()) {
                final KeyStroke k = it.getAccelerator();
                out.add(new Entry(path + " › " + it.getText().trim(), k == null ? "" : keys(k), it));
            }
        }
    }

    private static String keys(KeyStroke k) {
        final String mod = KeyEvent.getModifiersExText(k.getModifiers());
        final String key = KeyEvent.getKeyText(k.getKeyCode());
        return mod.isEmpty() ? key : mod + "+" + key;
    }

    /** How well every word typed matches the entry: the sum of their scores, or NO_MATCH. */
    static int score(String query, Entry e) {
        final String text = e.path().toLowerCase(Locale.ROOT);
        int total = 0;
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int best = FuzzyMatcher.NO_MATCH;
            // a word may match any part of the path: the menu, the item, a word inside them
            for (String part : text.split("[\\s›()/.,]+")) {
                best = Math.max(best, FuzzyMatcher.score(word, part, null));
            }
            // across words too, but below a match inside one (NO_MATCH is MIN_VALUE: never subtract from it)
            final int across = FuzzyMatcher.score(word, text, null);
            if (across != FuzzyMatcher.NO_MATCH) {
                best = Math.max(best, across - 300);
            }
            if (best == FuzzyMatcher.NO_MATCH) {
                return FuzzyMatcher.NO_MATCH;
            }
            total += best;
        }
        // between equal matches, the shorter entry: it is about fewer other things
        return total - e.path().length() / 8;
    }

    static void show(JFrame owner, JMenuBar bar) {
        final List<Entry> all = entries(bar);
        final JDialog d = new JDialog(owner, false);
        d.setUndecorated(true);
        final JTextField query = new JTextField();
        query.setFont(query.getFont().deriveFont(15f));
        query.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        final DefaultListModel<Entry> model = new DefaultListModel<>();
        final JList<Entry> list = new JList<>(model);
        list.setCellRenderer(renderer());
        list.setVisibleRowCount(14);
        final Runnable refilter = () -> {
            final String q = query.getText();
            model.clear();
            final List<Entry> hits = new ArrayList<>();
            for (Entry e : all) {
                if (q.isBlank() || score(q, e) != FuzzyMatcher.NO_MATCH) {
                    hits.add(e);
                }
            }
            if (!q.isBlank()) {
                hits.sort(Comparator.comparingInt((Entry e) -> -score(q, e)));
            }
            hits.forEach(model::addElement);
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
        };
        final Runnable run = () -> {
            final Entry e = list.getSelectedValue();
            d.dispose();
            if (e != null) {
                SwingUtilities.invokeLater(e.item()::doClick);
            }
        };
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refilter.run();
            }

            public void removeUpdate(DocumentEvent e) {
                refilter.run();
            }

            public void changedUpdate(DocumentEvent e) {
                refilter.run();
            }
        });
        query.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                final int i = list.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        list.setSelectedIndex(Math.min(model.size() - 1, i + 1));
                        list.ensureIndexIsVisible(list.getSelectedIndex());
                    }
                    case KeyEvent.VK_UP -> {
                        list.setSelectedIndex(Math.max(0, i - 1));
                        list.ensureIndexIsVisible(list.getSelectedIndex());
                    }
                    case KeyEvent.VK_ENTER -> run.run();
                    case KeyEvent.VK_ESCAPE -> d.dispose();
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    run.run();
                }
            }
        });
        final JPanel p = new JPanel(new BorderLayout());
        final Color line = UIManager.getColor("Separator.foreground");
        p.setBorder(BorderFactory.createLineBorder(line == null ? Color.GRAY : line));
        final JLabel hint = new JLabel("  Type a command: a few letters of each word. Enter runs it, Esc closes.");
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setForeground(new Color(0x7D8796));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        p.add(query, BorderLayout.NORTH);
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        p.add(hint, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                d.dispose();
            }
        });
        refilter.run();
        d.setSize(new Dimension(620, 420));
        final Point o = owner.getLocationOnScreen();
        d.setLocation(o.x + (owner.getWidth() - d.getWidth()) / 2, o.y + 90);
        d.setVisible(true);
        query.requestFocusInWindow();
    }

    private static ListCellRenderer<Entry> renderer() {
        return (list, e, index, selected, focus) -> {
            final JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            final JLabel name = new JLabel(e.path());
            final JLabel keys = new JLabel(e.keys());
            keys.setForeground(new Color(0x7D8796));
            row.add(name, BorderLayout.CENTER);
            row.add(keys, BorderLayout.EAST);
            row.setOpaque(true);
            row.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            ((JComponent) row).setToolTipText(e.item().getToolTipText());
            return row;
        };
    }
}
