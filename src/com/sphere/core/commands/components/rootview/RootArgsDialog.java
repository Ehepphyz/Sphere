package com.sphere.components.rootview;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The dialog of TContextMenu: a function's parameters, each with its type
 * and its default, before the function is called. A colour is picked among
 * ROOT's colour indices (or any colour), a font among ROOT's fonts, an option
 * among those the function knows, an object among those open; OK calls the
 * function and closes, Apply calls it and stays, Online Help opens the
 * function's page in ROOT's reference.
 */
final class RootArgsDialog {

    private RootArgsDialog() {
    }

    /** One choice of an object parameter: what is shown, and the object. */
    record Choice(String label, Object value) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Asks for the arguments of a function. Returns them, or null when
     * cancelled; apply is called for each Apply.
     */
    static Object[] ask(Component parent, RootTarget target, RootMethod m, Object[] initial,
                        Map<String, String[]> suggestions, List<Choice> objects, Consumer<Object[]> apply) {
        return ask(parent, target.title(), m, initial, suggestions, objects, apply);
    }

    /** The same, for an object known by its title only ("TTree::events" of a file). */
    static Object[] ask(Component parent, String title, RootMethod m, Object[] initial,
                        Map<String, String[]> suggestions, List<Choice> objects, Consumer<Object[]> apply) {
        final Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(owner, title + " — " + m.name, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        final List<Editor> editors = new ArrayList<>();
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        final JLabel head = new JLabel("<html><b>" + escape(title) + "</b>&nbsp;&nbsp;"
            + escape(m.owner + "::" + m.signature()) + "</html>");
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        form.add(head, c);
        c.gridwidth = 1;
        for (int i = 0; i < m.params.size(); i++) {
            final RootMethod.Param p = m.params.get(i);
            final Object start = initial != null && i < initial.length && initial[i] != null ? initial[i] : null;
            final Editor e = editor(p, start, suggestions.get(p.name()), objects);
            editors.add(e);
            c.gridx = 0;
            c.gridy = i + 1;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            final JLabel l = new JLabel(p.type() + " " + p.name());
            l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            form.add(l, c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            form.add(e.component(), c);
        }
        final Object[][] result = {null};
        final JLabel error = new JLabel(" ");
        error.setForeground(new Color(0xD04040));
        final JButton ok = new JButton("OK");
        final JButton cancel = new JButton("Cancel");
        final JButton applyButton = new JButton("Apply");
        final JButton help = new JButton("Online Help");
        ok.addActionListener(e -> {
            try {
                result[0] = values(m, editors);
                d.dispose();
            } catch (IllegalArgumentException bad) {
                error.setText(bad.getMessage());
            }
        });
        applyButton.addActionListener(e -> {
            try {
                if (apply != null) apply.accept(values(m, editors));
                error.setText(" ");
            } catch (IllegalArgumentException bad) {
                error.setText(bad.getMessage());
            }
        });
        cancel.addActionListener(e -> d.dispose());
        help.addActionListener(e -> com.sphere.utils.WebLinks.open(
            "https://root.cern/doc/master/class" + m.owner + ".html"));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(help);
        buttons.add(applyButton);
        buttons.add(cancel);
        buttons.add(ok);
        final JPanel south = new JPanel(new BorderLayout());
        error.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
        south.add(error, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(form, BorderLayout.CENTER);
        d.getContentPane().add(south, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        d.setMinimumSize(new Dimension(Math.max(380, d.getWidth()), d.getHeight()));
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return result[0];
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Object[] values(RootMethod m, List<Editor> editors) {
        final Object[] out = new Object[editors.size()];
        for (int i = 0; i < out.length; i++) out[i] = editors.get(i).value();
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* The editors                                                         */
    /* ------------------------------------------------------------------ */

    private interface Editor {
        JComponent component();

        Object value();
    }

    private static Editor editor(RootMethod.Param p, Object start, String[] suggestions, List<Choice> objects) {
        final String def = start != null ? text(start) : p.defaultText();
        return switch (p.kind()) {
            case BOOL -> {
                final JCheckBox b = new JCheckBox("", start instanceof Boolean v ? v : "true".equals(def) || "1".equals(def));
                yield new Editor() {
                    public JComponent component() {
                        return b;
                    }

                    public Object value() {
                        return b.isSelected();
                    }
                };
            }
            case COLOR -> colorEditor(start instanceof Color col ? col
                : def.isEmpty() ? Color.BLACK : RootColors.parse(def) != null ? RootColors.parse(def) : Color.BLACK);
            case FONT -> fontEditor(start instanceof Number n ? n.intValue() : parseInt(def, 42));
            case OBJECT -> {
                final JComboBox<Choice> box = new JComboBox<>();
                // A pointer that may be null (Int_t *error = nullptr) offers nullptr first.
                if (p.hasDefault() && p.defaultText().isEmpty()) box.addItem(new Choice("nullptr", null));
                if (objects != null) for (Choice c : objects) box.addItem(c);
                yield new Editor() {
                    public JComponent component() {
                        return box;
                    }

                    public Object value() {
                        final Choice ch = (Choice) box.getSelectedItem();
                        if (ch == null) throw new IllegalArgumentException(p.name() + ": no object to choose");
                        return ch.value();
                    }
                };
            }
            default -> {
                final JComponent field;
                final java.util.function.Supplier<String> read;
                if (suggestions != null && suggestions.length > 0) {
                    final JComboBox<String> box = new JComboBox<>(suggestions);
                    box.setEditable(true);
                    box.setSelectedItem(def);
                    field = box;
                    read = () -> String.valueOf(box.getEditor().getItem());
                } else {
                    final JTextField t = new JTextField(def, 18);
                    field = t;
                    read = t::getText;
                }
                yield new Editor() {
                    public JComponent component() {
                        return field;
                    }

                    public Object value() {
                        final String s = read.get().strip();
                        try {
                            return switch (p.kind()) {
                                case INT -> s.isEmpty() ? 0L : (long) Double.parseDouble(s);
                                case REAL -> s.isEmpty() ? 0.0 : Double.parseDouble(s);
                                case ARRAY -> array(s);
                                default -> s;
                            };
                        } catch (NumberFormatException bad) {
                            throw new IllegalArgumentException(p.name() + ": a number is expected, not \"" + s + "\"");
                        }
                    }
                };
            }
        };
    }

    private static double[] array(String s) {
        if (s.isBlank()) return null;
        final String[] w = s.split("[,;\\s]+");
        final double[] out = new double[w.length];
        for (int i = 0; i < w.length; i++) out[i] = Double.parseDouble(w[i]);
        return out;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return (int) Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String text(Object o) {
        if (o instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString(d.longValue());
            return String.format(Locale.ROOT, "%.10g", d).replaceAll("\\.?0+(e|$)", "$1");
        }
        if (o instanceof double[] a) {
            final StringBuilder b = new StringBuilder();
            for (double v : a) b.append(b.length() == 0 ? "" : ", ").append(text(v));
            return b.toString();
        }
        return String.valueOf(o);
    }

    /** A swatch and the colour's ROOT name; a click opens ROOT's colours. */
    private static Editor colorEditor(Color start) {
        final Color[] chosen = {start};
        final JButton b = new JButton();
        final Runnable show = () -> {
            final int idx = RootColors.index(chosen[0]);
            final boolean exact = RootColors.color(idx).getRGB() == chosen[0].getRGB();
            b.setText((exact ? RootColors.name(idx) + " (" + idx + ")" : String.format("#%06X", chosen[0].getRGB() & 0xFFFFFF)));
            b.setIcon(swatch(chosen[0], 14));
        };
        show.run();
        b.setHorizontalAlignment(JButton.LEFT);
        b.addActionListener(e -> {
            final JPopupMenu menu = new JPopupMenu();
            final JPanel grid = new JPanel(new GridLayout(0, 10, 2, 2));
            grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            final List<Integer> shown = new ArrayList<>();
            for (int i = 0; i <= 50; i++) shown.add(i);
            for (int base : new int[]{RootColors.K_RED, RootColors.K_ORANGE, RootColors.K_YELLOW, RootColors.K_SPRING,
                RootColors.K_GREEN, RootColors.K_TEAL, RootColors.K_CYAN, RootColors.K_AZURE, RootColors.K_BLUE,
                RootColors.K_VIOLET, RootColors.K_MAGENTA, RootColors.K_PINK}) {
                for (int k = -4; k <= 4; k++) if (RootColors.defined(base + k)) shown.add(base + k);
            }
            for (int k = 0; k <= 3; k++) shown.add(RootColors.K_GRAY + k);
            for (int idx : shown) {
                final JButton cell = new JButton(swatch(RootColors.color(idx), 16));
                cell.setMargin(new Insets(0, 0, 0, 0));
                cell.setToolTipText(RootColors.name(idx) + " (" + idx + ")");
                cell.addActionListener(a -> {
                    chosen[0] = RootColors.color(idx);
                    show.run();
                    menu.setVisible(false);
                });
                grid.add(cell);
            }
            final JButton other = new JButton("Other…");
            other.addActionListener(a -> {
                menu.setVisible(false);
                final Color c = JColorChooser.showDialog(b, "Colour", chosen[0]);
                if (c != null) {
                    chosen[0] = c;
                    show.run();
                }
            });
            final JPanel all = new JPanel(new BorderLayout());
            all.add(grid, BorderLayout.CENTER);
            all.add(other, BorderLayout.SOUTH);
            menu.add(all);
            menu.show(b, 0, b.getHeight());
        });
        return new Editor() {
            public JComponent component() {
                return b;
            }

            public Object value() {
                return chosen[0];
            }
        };
    }

    static javax.swing.Icon swatch(Color c, int size) {
        return new javax.swing.Icon() {
            public void paintIcon(Component comp, java.awt.Graphics g, int x, int y) {
                g.setColor(c);
                g.fillRect(x, y, size, size);
                g.setColor(Color.GRAY);
                g.drawRect(x, y, size - 1, size - 1);
            }

            public int getIconWidth() {
                return size;
            }

            public int getIconHeight() {
                return size;
            }
        };
    }

    /** ROOT's fonts: the 15 families at precision 2 (a fraction of the pad) and 3 (pixels). */
    private static Editor fontEditor(int start) {
        final List<Integer> codes = new ArrayList<>();
        for (int f = 1; f <= 15; f++) {
            codes.add(f * 10 + 2);
            codes.add(f * 10 + 3);
        }
        if (!codes.contains(start)) codes.add(0, start);
        final JComboBox<Integer> box = new JComboBox<>(codes.toArray(new Integer[0]));
        box.setSelectedItem(start);
        box.setEditable(true);
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof Integer code) {
                    setText(RootFonts.describe(code));
                    setFont(RootFonts.font(code, 13));
                }
                return this;
            }
        });
        return new Editor() {
            public JComponent component() {
                return box;
            }

            public Object value() {
                final Object v = box.getEditor().getItem();
                try {
                    return Integer.parseInt(String.valueOf(v).replaceAll("\\s.*", "").strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("a font is a number, 10 * family + precision");
                }
            }
        };
    }
}
