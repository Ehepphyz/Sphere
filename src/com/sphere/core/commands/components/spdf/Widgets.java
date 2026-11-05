package com.sphere.components.spdf;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.plaf.basic.BasicButtonUI;

/** Flat buttons, toggles and the color picker of the :spdf toolbars. */
final class Widgets {

    private Widgets() {
    }

    /** A flat button: no border, a rounded hover, an icon and a tooltip. */
    static JButton button(String icon, String tip, Runnable action) {
        JButton b = new JButton(SpdfTheme.icon(icon, 18));
        style(b, tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    static JButton textButton(String text, String tip, Runnable action) {
        JButton b = new JButton(text);
        style(b, tip);
        b.setFont(SpdfTheme.ui(java.awt.Font.PLAIN, 12));
        b.setForeground(SpdfTheme.fg());
        b.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        b.addActionListener(e -> action.run());
        return b;
    }

    static JToggleButton toggle(String icon, String tip) {
        JToggleButton b = new JToggleButton(SpdfTheme.icon(icon, 18));
        style(b, tip);
        return b;
    }

    private static void style(AbstractButton b, String tip) {
        b.setUI(new FlatUI());
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setMargin(new Insets(4, 4, 4, 4));
        b.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Paints the hover and pressed/selected state behind the icon. */
    private static final class FlatUI extends BasicButtonUI {
        @Override
        public void paint(Graphics g0, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on = b.isSelected() || b.getModel().isPressed();
            if (on || b.getModel().isRollover()) {
                g.setColor(on ? SpdfTheme.alpha(SpdfTheme.accent(), 70) : SpdfTheme.hover());
                g.fill(new RoundRectangle2D.Float(1, 1, c.getWidth() - 2, c.getHeight() - 2, 8, 8));
                if (b.isSelected()) {
                    g.setColor(SpdfTheme.alpha(SpdfTheme.accent(), 200));
                    g.setStroke(new BasicStroke(1.2f));
                    g.draw(new RoundRectangle2D.Float(1.5f, 1.5f, c.getWidth() - 3, c.getHeight() - 3, 8, 8));
                }
            }
            g.dispose();
            super.paint(g0, c);
        }
    }

    static JComponent separator() {
        JComponent s = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(SpdfTheme.border());
                g.fillRect(getWidth() / 2, 6, 1, getHeight() - 12);
            }
        };
        s.setPreferredSize(new Dimension(11, 28));
        return s;
    }

    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(SpdfTheme.muted());
        l.setFont(SpdfTheme.ui(java.awt.Font.PLAIN, 12));
        return l;
    }

    /** A swatch button: shows a color, opens a palette (document colors first). */
    static final class ColorButton extends JButton {
        private Color color;
        private final IntConsumer onPick;
        java.util.function.Supplier<List<Integer>> documentColors = List::of;

        ColorButton(Color initial, String tip, IntConsumer onPick) {
            this.color = initial;
            this.onPick = onPick;
            style(this, tip);
            setPreferredSize(new Dimension(32, 30));
            addActionListener(e -> showPalette());
        }

        void setColor(Color c) {
            color = c;
            repaint();
        }

        Color color() {
            return color;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = 16;
            int x = (getWidth() - s) / 2;
            int y = (getHeight() - s) / 2 - 2;
            g.setColor(SpdfTheme.fg());
            g.setFont(SpdfTheme.ui(java.awt.Font.BOLD, 13));
            g.drawString("A", x + 3, y + 13);
            g.setColor(color);
            g.fillRoundRect(x - 1, y + s, s + 2, 4, 2, 2);
            g.dispose();
        }

        private void showPalette() {
            JPopupMenu m = new JPopupMenu();
            JPanel p = new JPanel();
            p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
            p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            p.setBackground(SpdfTheme.popup());
            List<Integer> doc = documentColors.get();
            if (!doc.isEmpty()) {
                p.add(caption("Document colors"));
                p.add(grid(doc, m));
            }
            p.add(caption("Palette"));
            p.add(grid(List.of(0x000000, 0x444444, 0x888888, 0xCCCCCC, 0xFFFFFF, 0xC62828, 0xE65100, 0xF9A825,
                               0x2E7D32, 0x00838F, 0x1565C0, 0x283593, 0x6A1B9A, 0xAD1457, 0x4E342E, 0x37474F,
                               0xEF9A9A, 0xFFCC80, 0xFFF59D, 0xA5D6A7, 0x80DEEA, 0x90CAF9, 0xCE93D8, 0xF48FB1), m));
            JButton more = textButton("More colors…", null, () -> {
                m.setVisible(false);
                Color c = JColorChooser.showDialog(this, "Color", color);
                if (c != null) {
                    pick(c.getRGB() & 0xffffff);
                }
            });
            p.add(more);
            m.add(p);
            m.show(this, 0, getHeight());
        }

        private JLabel caption(String s) {
            JLabel l = label(s);
            l.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
            return l;
        }

        private JPanel grid(List<Integer> colors, JPopupMenu m) {
            JPanel g = new JPanel(new GridLayout(0, 8, 4, 4));
            g.setOpaque(false);
            for (int c : colors) {
                JComponent sw = new JComponent() {
                    @Override
                    protected void paintComponent(Graphics g0) {
                        Graphics2D gg = (Graphics2D) g0;
                        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gg.setColor(new Color(c));
                        gg.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 5, 5);
                        gg.setColor(SpdfTheme.border());
                        gg.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 5, 5);
                    }
                };
                sw.setPreferredSize(new Dimension(20, 20));
                sw.setToolTipText(String.format("#%06X", c));
                sw.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                sw.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        m.setVisible(false);
                        pick(c);
                    }
                });
                g.add(sw);
            }
            return g;
        }

        private void pick(int rgb) {
            color = new Color(rgb);
            repaint();
            onPick.accept(rgb);
        }
    }

    static Icon dot(Color c) {
        return new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c);
                g.fillOval(x + 2, y + 2, 12, 12);
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }
}
