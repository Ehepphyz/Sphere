package com.sphere.components.editor;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The bar under the editor: problems on the left, then where the caret is, how
 * the file is indented, encoded and ended, and its language on the right. Each
 * entry is a button that changes what it shows.
 */
public final class EditorStatusBar extends JPanel {

    /** One clickable entry. */
    public static final class Segment extends JComponent {
        private Icon icon;
        private String text = "";
        private Icon icon2;
        private String text2 = "";
        private Color dot;
        private boolean hovered;
        private Consumer<Segment> onClick;

        Segment(String tooltip) {
            setToolTipText(tooltip);
            setFont(EditorWidgets.uiFont(Font.PLAIN, 12));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = onClick != null; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (onClick != null) {
                        onClick.accept(Segment.this);
                    }
                }
            });
        }

        public Segment onClick(Consumer<Segment> action) {
            this.onClick = action;
            setCursor(Cursor.getPredefinedCursor(action == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
            return this;
        }

        public void set(Icon icon, String text) {
            set(icon, text, null, "");
        }

        public void set(Icon icon, String text, Icon icon2, String text2) {
            this.icon = icon;
            this.text = text == null ? "" : text;
            this.icon2 = icon2;
            this.text2 = text2 == null ? "" : text2;
            revalidate();
            repaint();
        }

        public void setDot(Color color) {
            this.dot = color;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = 16 + fm.stringWidth(text);
            if (icon != null) {
                w += icon.getIconWidth() + 5;
            }
            if (dot != null) {
                w += 13;
            }
            if (icon2 != null || !text2.isEmpty()) {
                w += 10 + fm.stringWidth(text2) + (icon2 == null ? 0 : icon2.getIconWidth() + 5);
            }
            return new Dimension(w, 22);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            if (hovered) {
                g2.setColor(EditorTheme.chromeHover());
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            int x = 8;
            int h = getHeight();
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int base = (h + fm.getAscent() - fm.getDescent()) / 2;
            if (dot != null) {
                g2.setColor(dot);
                g2.fillOval(x, (h - 8) / 2, 8, 8);
                x += 13;
            }
            if (icon != null) {
                icon.paintIcon(this, g2, x, (h - icon.getIconHeight()) / 2);
                x += icon.getIconWidth() + 5;
            }
            g2.setColor(hovered ? EditorTheme.foreground() : EditorTheme.chromeForeground());
            g2.drawString(text, x, base);
            x += fm.stringWidth(text);
            if (icon2 != null || !text2.isEmpty()) {
                x += 10;
                if (icon2 != null) {
                    icon2.paintIcon(this, g2, x, (h - icon2.getIconHeight()) / 2);
                    x += icon2.getIconWidth() + 5;
                }
                g2.setColor(hovered ? EditorTheme.foreground() : EditorTheme.chromeForeground());
                g2.drawString(text2, x, base);
            }
            g2.dispose();
        }
    }

    private final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));

    public final Segment problems = new Segment("Problems (Ctrl+Shift+M)");
    public final Segment message = new Segment(null);
    public final Segment cursor = new Segment("Go to Line (Ctrl+G)");
    public final Segment indentation = new Segment("Select Indentation");
    public final Segment encoding = new Segment("Select Encoding");
    public final Segment eol = new Segment("Select End of Line Sequence");
    public final Segment language = new Segment("Select Language Mode");
    public final Segment insight = new Segment("Sphere Insight: live analysis of the code");

    public EditorStatusBar() {
        super(new BorderLayout());
        setOpaque(true);
        left.setOpaque(false);
        right.setOpaque(false);
        left.add(problems);
        left.add(insight);
        left.add(message);
        right.add(cursor);
        right.add(indentation);
        right.add(encoding);
        right.add(eol);
        right.add(language);
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, 24);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(EditorTheme.chrome());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(EditorTheme.chromeBorder());
        g.fillRect(0, 0, getWidth(), 1);
    }
}
