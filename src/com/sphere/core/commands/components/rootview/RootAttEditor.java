package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.Item;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.Consumer;

/**
 * The attribute editors of ROOT's context menu: SetLineAttributes,
 * SetFillAttributes, SetMarkerAttributes and SetTextAttributes, each with
 * a preview of what it sets, Apply to see it on the canvas, OK to keep it.
 */
final class RootAttEditor {

    private RootAttEditor() {
    }

    enum Kind { LINE, FILL, MARKER, TEXT }

    /** The values an editor sets, copied in and out of the object. */
    static final class Att {
        Color color = Color.BLACK;
        double width = 1;
        int style = 1;
        double size = 1;
        int font = 42;
        int align = 22;
        double angle;
    }

    static Att read(Kind kind, Item i) {
        final Att a = new Att();
        switch (kind) {
            case LINE -> {
                a.color = i.line == null ? Color.BLACK : i.line;
                a.width = i.lineWidth;
                a.style = i.lineStyle;
            }
            case FILL -> {
                a.color = i.fill == null ? Color.WHITE : i.fill;
                a.style = i.fillStyle;
            }
            case MARKER -> {
                a.color = i.marker == null ? Color.BLACK : i.marker;
                a.style = i.markerStyle;
                a.size = i.markerSize;
            }
            case TEXT -> {
                if (i instanceof RootScene.Text t) {
                    a.color = t.color;
                    a.size = t.size;
                    a.font = t.font;
                    a.align = t.align;
                    a.angle = t.angle;
                } else if (i instanceof RootScene.Pave p) {
                    a.color = p.lines.isEmpty() || p.lines.get(0).color == null ? Color.BLACK : p.lines.get(0).color;
                    a.size = p.textSize;
                    a.font = p.textFont;
                    a.align = p.textAlign;
                }
            }
            default -> {
            }
        }
        return a;
    }

    static void write(Kind kind, Item i, Att a) {
        switch (kind) {
            case LINE -> {
                i.line = a.color;
                i.lineWidth = a.width;
                i.lineStyle = a.style;
            }
            case FILL -> {
                i.fill = a.color;
                i.fillStyle = a.style;
            }
            case MARKER -> {
                i.marker = a.color;
                i.markerStyle = a.style;
                i.markerSize = a.size;
            }
            case TEXT -> {
                if (i instanceof RootScene.Text t) {
                    t.color = a.color;
                    t.size = a.size;
                    t.font = a.font;
                    t.align = a.align;
                    t.angle = a.angle;
                } else if (i instanceof RootScene.Pave p) {
                    for (RootScene.Entry e : p.lines) e.color = a.color;
                    p.textSize = a.size;
                    p.textFont = a.font;
                    p.textAlign = a.align;
                }
            }
            default -> {
            }
        }
    }

    /** Shows the editor; apply is called on Apply and on OK with the values chosen. */
    static void edit(Component parent, String title, Kind kind, Att start, Consumer<Att> apply) {
        final JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), title,
            java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final Att a = new Att();
        a.color = start.color;
        a.width = start.width;
        a.style = start.style;
        a.size = start.size;
        a.font = start.font;
        a.align = start.align;
        a.angle = start.angle;
        final Preview preview = new Preview(kind, a);
        final JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        final int[] row = {0};
        final java.util.function.BiConsumer<String, JComponent> add = (label, comp) -> {
            final GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 4, 3, 4);
            c.gridx = 0;
            c.gridy = row[0];
            c.anchor = GridBagConstraints.WEST;
            form.add(new JLabel(label), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            form.add(comp, c);
            row[0]++;
        };
        final JButton color = new JButton();
        final Runnable showColor = () -> {
            final int idx = RootColors.index(a.color);
            color.setText(RootColors.color(idx).getRGB() == a.color.getRGB() ? RootColors.name(idx) + " (" + idx + ")"
                : String.format("#%06X", a.color.getRGB() & 0xFFFFFF));
            color.setIcon(RootArgsDialog.swatch(a.color, 14));
            preview.repaint();
        };
        showColor.run();
        color.setHorizontalAlignment(JButton.LEFT);
        color.addActionListener(e -> {
            final Color c = JColorChooser.showDialog(color, "Colour", a.color);
            if (c != null) {
                a.color = c;
                showColor.run();
            }
        });
        add.accept("Colour", color);
        final JComboBox<Integer> basic = new JComboBox<>();
        for (int i = 0; i <= 50; i++) basic.addItem(i);
        for (int base : new int[]{RootColors.K_RED, RootColors.K_ORANGE, RootColors.K_YELLOW, RootColors.K_SPRING,
            RootColors.K_GREEN, RootColors.K_TEAL, RootColors.K_CYAN, RootColors.K_AZURE, RootColors.K_BLUE,
            RootColors.K_VIOLET, RootColors.K_MAGENTA, RootColors.K_PINK, RootColors.K_GRAY}) {
            for (int k = -4; k <= 4; k++) if (RootColors.defined(base + k)) basic.addItem(base + k);
        }
        basic.setSelectedItem(RootColors.index(a.color));
        basic.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof Integer i) {
                    setIcon(RootArgsDialog.swatch(RootColors.color(i), 12));
                    setText(RootColors.name(i) + "  (" + i + ")");
                }
                return this;
            }
        });
        basic.addActionListener(e -> {
            if (basic.getSelectedItem() instanceof Integer i) {
                a.color = RootColors.color(i);
                showColor.run();
            }
        });
        add.accept("ROOT colour", basic);
        switch (kind) {
            case LINE -> {
                add.accept("Width", spinner(a.width, 0, 20, 1, v -> {
                    a.width = v;
                    preview.repaint();
                }));
                final JComboBox<Integer> style = new JComboBox<>(new Integer[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
                style.setSelectedItem(a.style);
                style.setRenderer(new LineStyleRenderer());
                style.addActionListener(e -> {
                    a.style = (Integer) style.getSelectedItem();
                    preview.repaint();
                });
                add.accept("Style", style);
            }
            case FILL -> {
                final Integer[] styles = new Integer[30];
                styles[0] = 0;
                styles[1] = 1001;
                for (int i = 1; i <= 25; i++) styles[i + 1] = 3000 + i;
                styles[27] = 3144;
                styles[28] = 3354;
                styles[29] = 4050;
                final JComboBox<Integer> style = new JComboBox<>(styles);
                style.setEditable(true);
                style.setSelectedItem(a.style);
                style.addActionListener(e -> {
                    try {
                        a.style = Integer.parseInt(String.valueOf(style.getSelectedItem()).strip());
                        preview.repaint();
                    } catch (NumberFormatException ignored) {
                        // kept until the field holds a number
                    }
                });
                add.accept("Style (0 hollow, 1001 solid, 3001-3025, 3ijk, 4000-4100 transparent)", style);
            }
            case MARKER -> {
                final Integer[] styles = new Integer[49];
                for (int i = 0; i < styles.length; i++) styles[i] = i + 1;
                final JComboBox<Integer> style = new JComboBox<>(styles);
                style.setSelectedItem(a.style);
                style.addActionListener(e -> {
                    a.style = (Integer) style.getSelectedItem();
                    preview.repaint();
                });
                add.accept("Style", style);
                add.accept("Size", spinner(a.size, 0.1, 10, 0.1, v -> {
                    a.size = v;
                    preview.repaint();
                }));
            }
            case TEXT -> {
                final JComboBox<Integer> font = new JComboBox<>();
                for (int f = 1; f <= 15; f++) {
                    font.addItem(f * 10 + 2);
                    font.addItem(f * 10 + 3);
                }
                font.setSelectedItem(a.font);
                font.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                                                                  boolean focus) {
                        super.getListCellRendererComponent(list, value, index, sel, focus);
                        if (value instanceof Integer code) setText(RootFonts.describe(code));
                        return this;
                    }
                });
                font.addActionListener(e -> {
                    a.font = (Integer) font.getSelectedItem();
                    preview.repaint();
                });
                add.accept("Font", font);
                add.accept("Size", spinner(a.size, 0, 200, a.size >= 1 ? 1 : 0.005, v -> {
                    a.size = v;
                    preview.repaint();
                }));
                final JComboBox<Integer> align = new JComboBox<>(new Integer[]{11, 12, 13, 21, 22, 23, 31, 32, 33});
                align.setSelectedItem(a.align);
                align.addActionListener(e -> {
                    a.align = (Integer) align.getSelectedItem();
                    preview.repaint();
                });
                add.accept("Align (10*horizontal + vertical)", align);
                final JSlider angle = new JSlider(-180, 180, (int) a.angle);
                angle.addChangeListener(e -> {
                    a.angle = angle.getValue();
                    preview.repaint();
                });
                add.accept("Angle", angle);
            }
            default -> {
            }
        }
        final JButton ok = new JButton("OK");
        final JButton applyB = new JButton("Apply");
        final JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            apply.accept(a);
            d.dispose();
        });
        applyB.addActionListener(e -> apply.accept(a));
        cancel.addActionListener(e -> d.dispose());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(applyB);
        buttons.add(cancel);
        buttons.add(ok);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(form, BorderLayout.CENTER);
        d.getContentPane().add(preview, BorderLayout.NORTH);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }

    private static JSpinner spinner(double value, double min, double max, double step, Consumer<Double> set) {
        final JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
        s.addChangeListener(e -> set.accept(((Number) s.getValue()).doubleValue()));
        return s;
    }

    /** What the attributes look like, drawn by the painter's own routines. */
    private static final class Preview extends JComponent {
        private final Kind kind;
        private final Att a;

        Preview(Kind kind, Att a) {
            this.kind = kind;
            this.a = a;
            setPreferredSize(new Dimension(360, 70));
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(8, 6, getWidth() - 16, getHeight() - 12);
            g.setColor(Color.GRAY);
            g.drawRect(8, 6, getWidth() - 16, getHeight() - 12);
            final Item probe = new RootScene.Other();
            switch (kind) {
                case LINE -> {
                    probe.lineStyle = a.style;
                    probe.lineWidth = a.width;
                    RootPadPainter.stroke(g, probe, a.color);
                    g.drawLine(24, getHeight() / 2, getWidth() - 24, getHeight() / 2);
                }
                case FILL -> {
                    probe.fill = a.color;
                    probe.fillStyle = a.style;
                    if (a.style != 0) {
                        g.setPaint(RootPadPainter.fillPaint(probe, a.color));
                        g.fillRect(24, 14, getWidth() - 48, getHeight() - 28);
                    }
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(24, 14, getWidth() - 48, getHeight() - 28);
                }
                case MARKER -> {
                    for (int k = 0; k < 6; k++) {
                        RootPadPainter.marker(g, a.style, a.size, a.color, 40 + k * (getWidth() - 80) / 5.0, getHeight() / 2.0);
                    }
                }
                case TEXT -> {
                    g.setColor(a.color);
                    g.setFont(RootFonts.font(a.font, a.size >= 1 ? (float) a.size : (float) Math.max(10, a.size * 400)));
                    final java.awt.geom.AffineTransform saved = g.getTransform();
                    g.rotate(-Math.toRadians(a.angle), getWidth() / 2.0, getHeight() / 2.0);
                    final String s = "ROOT text  αβγ  p_{T}";
                    final int w = g.getFontMetrics().stringWidth(s);
                    final int h = a.align / 10;
                    final int x = h == 1 ? getWidth() / 2 : h == 3 ? getWidth() / 2 - w : getWidth() / 2 - w / 2;
                    g.drawString(s, x, getHeight() / 2 + g.getFontMetrics().getAscent() / 3);
                    g.setTransform(saved);
                }
                default -> {
                }
            }
            g.dispose();
        }
    }

    /** The ten line styles of ROOT, drawn. */
    private static final class LineStyleRenderer extends JLabel implements javax.swing.ListCellRenderer<Integer> {
        private int style = 1;

        LineStyleRenderer() {
            setPreferredSize(new Dimension(160, 18));
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Integer> list, Integer value, int index,
                                                      boolean sel, boolean focus) {
            style = value == null ? 1 : value;
            setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            final Graphics2D g = (Graphics2D) graphics;
            g.setColor(getForeground());
            g.drawString(Integer.toString(style), 4, getHeight() - 4);
            g.setStroke(RootPadPainter.lineStroke(style, 1.5));
            g.drawLine(24, getHeight() / 2, getWidth() - 6, getHeight() / 2);
            g.setStroke(new BasicStroke(1f));
        }
    }
}
