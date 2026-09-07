package com.sphere.components.imaging;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Look of the image editor: colors derived from the active Sphere palette, and
 * the vector glyphs the toolbar draws.
 *
 * The icons are drawn rather than loaded so they stay sharp at any display scale
 * and follow the theme instead of carrying baked-in colors.
 */
public final class ImagingTheme {

    private ImagingTheme() {
    }

    public static ThemePalette palette() {
        return ThemeManager.getCurrentPalette();
    }

    public static Color surface() {
        return palette().getBackgroundSurface();
    }

    public static Color panel() {
        return palette().getBackgroundMain();
    }

    public static Color border() {
        return palette().getBorder();
    }

    public static Color text() {
        return palette().getTextPrimary();
    }

    public static Color subduedText() {
        return palette().getTextSecondary();
    }

    public static Color accent() {
        return palette().getAccent();
    }

    /** The dark ground the image sits on, a shade below the surrounding panels. */
    public static Color canvasGround() {
        Color base = panel();
        return new Color(Math.max(0, base.getRed() - 14),
                         Math.max(0, base.getGreen() - 14),
                         Math.max(0, base.getBlue() - 14));
    }

    public static Font uiFont(int style, float size) {
        return new Font(Font.SANS_SERIF, style, 12).deriveFont(style, size);
    }

    public static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(uiFont(Font.BOLD, 10f));
        label.setForeground(subduedText());
        label.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        return label;
    }

    /**
     * A panel that fills its own background.
     *
     * Nimbus derives its painters from the look and feel's own defaults at the
     * moment it is installed, so a color set afterwards reaches setBackground but
     * never the painter. Filling here is what makes the editor follow the theme.
     */
    public static final class Surface extends JPanel {
        private final boolean raised;

        public Surface(java.awt.LayoutManager layout, boolean raised) {
            super(layout);
            this.raised = raised;
            setOpaque(true);
            setBackground(raised ? surface() : panel());
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(raised ? surface() : panel());
                g.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g.dispose();
            }
        }
    }

    public static Surface panelOf(java.awt.LayoutManager layout) {
        return new Surface(layout, false);
    }

    public static Surface surfaceOf(java.awt.LayoutManager layout) {
        return new Surface(layout, true);
    }

    /** A vertical stack that paints the panel background. */
    public static Surface stack(boolean raised) {
        Surface panel = new Surface(null, raised);
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        return panel;
    }

    /** A horizontal strip that paints the panel background. */
    public static Surface strip(boolean raised) {
        Surface panel = new Surface(null, raised);
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.X_AXIS));
        return panel;
    }

    public static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBackground(panel());
        return panel;
    }

    public static GridBagConstraints gbc(int x, int y, int width, double weight) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = width;
        c.weightx = weight;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    public static void styleButton(AbstractButton button) {
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(surface());
        button.setForeground(text());
        button.setFont(uiFont(Font.PLAIN, 11f));
        button.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        final Color idle = surface();
        final Color hover = palette().getButtonHover();
        button.addChangeListener(e -> {
            if (button instanceof JToggleButton toggle && toggle.isSelected()) {
                button.setBackground(accent());
                button.setForeground(palette().getTextWhite());
            } else if (button.getModel().isRollover()) {
                button.setBackground(hover);
                button.setForeground(text());
            } else {
                button.setBackground(idle);
                button.setForeground(text());
            }
        });
    }

    public static JButton textButton(String label, String tip) {
        JButton button = new JButton(label);
        button.setToolTipText(tip);
        styleButton(button);
        return button;
    }

    public static JPanel separator() {
        JPanel line = new JPanel();
        line.setPreferredSize(new Dimension(1, 22));
        line.setMaximumSize(new Dimension(1, 22));
        line.setBackground(border());
        return line;
    }

    public static void framed(JComponent component) {
        component.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, border()));
    }

    /**
     * A tab strip that paints itself, with one card shown at a time.
     *
     * The look and feel's own tabs derive their colors when it is installed, so a
     * theme applied afterwards never reaches them; drawing the strip here is what
     * keeps the panel consistent with the rest of the editor.
     */
    public static final class Tabs extends JPanel {
        private final java.util.List<String> titles = new java.util.ArrayList<>();
        private final JPanel cards = new JPanel(new java.awt.CardLayout());
        private final Strip strip = new Strip();
        private int selected;

        public Tabs() {
            super(new java.awt.BorderLayout());
            setOpaque(true);
            cards.setOpaque(false);
            add(strip, java.awt.BorderLayout.NORTH);
            add(cards, java.awt.BorderLayout.CENTER);
        }

        public void addTab(String title, java.awt.Component content) {
            titles.add(title);
            cards.add(content, title);
            strip.revalidate();
            strip.repaint();
        }

        public void setSelectedIndex(int index) {
            if (index < 0 || index >= titles.size()) {
                return;
            }
            selected = index;
            ((java.awt.CardLayout) cards.getLayout()).show(cards, titles.get(index));
            strip.repaint();
        }

        public int getTabCount() {
            return titles.size();
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(panel());
                g.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g.dispose();
            }
        }

        private final class Strip extends JComponent {
            private static final int HEIGHT = 28;

            Strip() {
                setPreferredSize(new Dimension(10, HEIGHT));
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        int x = 0;
                        for (int i = 0; i < titles.size(); i++) {
                            final int w = tabWidth(titles.get(i));
                            if (e.getX() >= x && e.getX() < x + w) {
                                setSelectedIndex(i);
                                return;
                            }
                            x += w;
                        }
                    }
                });
            }

            private int tabWidth(String title) {
                return getFontMetrics(uiFont(Font.PLAIN, 11f)).stringWidth(title) + 26;
            }

            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                       RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(surface());
                    g.fillRect(0, 0, getWidth(), HEIGHT);
                    g.setFont(uiFont(Font.PLAIN, 11f));

                    int x = 0;
                    for (int i = 0; i < titles.size(); i++) {
                        final String title = titles.get(i);
                        final int w = tabWidth(title);
                        final boolean active = i == selected;
                        if (active) {
                            g.setColor(panel());
                            g.fillRect(x, 0, w, HEIGHT);
                            g.setColor(accent());
                            g.fillRect(x, HEIGHT - 2, w, 2);
                        }
                        g.setColor(active ? text() : subduedText());
                        final int textWidth = g.getFontMetrics().stringWidth(title);
                        g.drawString(title, x + (w - textWidth) / 2, HEIGHT / 2 + 4);
                        x += w;
                    }
                    g.setColor(border());
                    g.drawLine(0, HEIGHT - 1, getWidth(), HEIGHT - 1);
                } finally {
                    g.dispose();
                }
            }
        }
    }

    // ---- icons -------------------------------------------------------------

    /** Names the toolbar asks for, drawn at the size the button needs. */
    public static javax.swing.Icon icon(String name, int size, Color color) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(Math.max(1.3f, size / 12f),
                                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g, name, size);
        } finally {
            g.dispose();
        }
        return new javax.swing.ImageIcon(image);
    }

    private static void draw(Graphics2D g, String name, int s) {
        final double p = s * 0.18;
        final double q = s - p;

        switch (name) {
            case "select": {
                Path2D.Double arrow = new Path2D.Double();
                arrow.moveTo(s * 0.28, s * 0.14);
                arrow.lineTo(s * 0.28, s * 0.80);
                arrow.lineTo(s * 0.45, s * 0.63);
                arrow.lineTo(s * 0.57, s * 0.88);
                arrow.lineTo(s * 0.68, s * 0.82);
                arrow.lineTo(s * 0.56, s * 0.58);
                arrow.lineTo(s * 0.76, s * 0.55);
                arrow.closePath();
                g.fill(arrow);
                break;
            }
            case "pan": {
                g.draw(new java.awt.geom.Line2D.Double(s * 0.5, p, s * 0.5, q));
                g.draw(new java.awt.geom.Line2D.Double(p, s * 0.5, q, s * 0.5));
                arrowHead(g, s * 0.5, p, 0, -1, s * 0.16);
                arrowHead(g, s * 0.5, q, 0, 1, s * 0.16);
                arrowHead(g, p, s * 0.5, -1, 0, s * 0.16);
                arrowHead(g, q, s * 0.5, 1, 0, s * 0.16);
                break;
            }
            case "crop": {
                g.draw(new java.awt.geom.Line2D.Double(s * 0.28, p, s * 0.28, s * 0.74));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.28, s * 0.74, q, s * 0.74));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.72, q, s * 0.72, s * 0.26));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.72, s * 0.26, p, s * 0.26));
                break;
            }
            case "arrow": {
                g.draw(new java.awt.geom.Line2D.Double(p, q, s * 0.72, s * 0.28));
                arrowHead(g, q, p, 0.7, -0.7, s * 0.3);
                break;
            }
            case "line":
                g.draw(new java.awt.geom.Line2D.Double(p, q, q, p));
                break;
            case "rectangle":
                g.draw(new java.awt.geom.Rectangle2D.Double(p, s * 0.26, q - p, s * 0.48));
                break;
            case "ellipse":
                g.draw(new Ellipse2D.Double(p, s * 0.26, q - p, s * 0.48));
                break;
            case "text": {
                g.setFont(new Font(Font.SERIF, Font.BOLD, (int) (s * 0.72)));
                java.awt.FontMetrics fm = g.getFontMetrics();
                g.drawString("T", (float) (s / 2.0 - fm.stringWidth("T") / 2.0),
                             (float) (s / 2.0 + fm.getAscent() / 2.4));
                break;
            }
            case "measure": {
                g.draw(new java.awt.geom.Line2D.Double(p, s * 0.5, q, s * 0.5));
                g.draw(new java.awt.geom.Line2D.Double(p, s * 0.32, p, s * 0.68));
                g.draw(new java.awt.geom.Line2D.Double(q, s * 0.32, q, s * 0.68));
                break;
            }
            case "freehand": {
                Path2D.Double curve = new Path2D.Double();
                curve.moveTo(p, s * 0.68);
                curve.curveTo(s * 0.35, s * 0.20, s * 0.55, s * 0.90, q, s * 0.34);
                g.draw(curve);
                break;
            }
            case "digitize": {
                g.draw(new java.awt.geom.Line2D.Double(s * 0.22, s * 0.18, s * 0.22, s * 0.80));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.22, s * 0.80, s * 0.86, s * 0.80));
                double[][] pts = {{0.34, 0.66}, {0.48, 0.46}, {0.62, 0.54}, {0.76, 0.30}};
                Path2D.Double curve = new Path2D.Double();
                for (int i = 0; i < pts.length; i++) {
                    double x = pts[i][0] * s;
                    double y = pts[i][1] * s;
                    if (i == 0) {
                        curve.moveTo(x, y);
                    } else {
                        curve.lineTo(x, y);
                    }
                }
                g.draw(curve);
                for (double[] pt : pts) {
                    g.fill(new Ellipse2D.Double(pt[0] * s - s * 0.055,
                                                pt[1] * s - s * 0.055,
                                                s * 0.11, s * 0.11));
                }
                break;
            }
            case "zoom-in":
            case "zoom-out": {
                double r = s * 0.28;
                g.draw(new Ellipse2D.Double(s * 0.16, s * 0.16, r * 2, r * 2));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.66, s * 0.66, q, q));
                double cx = s * 0.16 + r;
                double cy = s * 0.16 + r;
                g.draw(new java.awt.geom.Line2D.Double(cx - r * 0.5, cy, cx + r * 0.5, cy));
                if (name.equals("zoom-in")) {
                    g.draw(new java.awt.geom.Line2D.Double(cx, cy - r * 0.5, cx, cy + r * 0.5));
                }
                break;
            }
            case "fit": {
                g.draw(new java.awt.geom.Rectangle2D.Double(p, s * 0.24, q - p, s * 0.52));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.34, s * 0.40, s * 0.34, s * 0.60));
                g.draw(new java.awt.geom.Line2D.Double(s * 0.66, s * 0.40, s * 0.66, s * 0.60));
                break;
            }
            case "undo":
            case "redo": {
                Path2D.Double curve = new Path2D.Double();
                if (name.equals("undo")) {
                    curve.moveTo(s * 0.78, s * 0.72);
                    curve.curveTo(s * 0.78, s * 0.30, s * 0.34, s * 0.24, s * 0.22, s * 0.42);
                    g.draw(curve);
                    arrowHead(g, s * 0.22, s * 0.42, -0.5, -0.85, s * 0.26);
                } else {
                    curve.moveTo(s * 0.22, s * 0.72);
                    curve.curveTo(s * 0.22, s * 0.30, s * 0.66, s * 0.24, s * 0.78, s * 0.42);
                    g.draw(curve);
                    arrowHead(g, s * 0.78, s * 0.42, 0.5, -0.85, s * 0.26);
                }
                break;
            }
            case "layers": {
                for (int i = 0; i < 3; i++) {
                    Path2D.Double plate = new Path2D.Double();
                    double y = s * (0.30 + i * 0.18);
                    plate.moveTo(s * 0.5, y - s * 0.12);
                    plate.lineTo(q, y);
                    plate.lineTo(s * 0.5, y + s * 0.12);
                    plate.lineTo(p, y);
                    plate.closePath();
                    g.draw(plate);
                }
                break;
            }
            default:
                g.draw(new Ellipse2D.Double(p, p, q - p, q - p));
                break;
        }
    }

    private static void arrowHead(Graphics2D g, double x, double y,
                                  double dx, double dy, double size) {
        final double angle = Math.atan2(dy, dx);
        Path2D.Double head = new Path2D.Double();
        head.moveTo(x, y);
        head.lineTo(x - size * Math.cos(angle - Math.PI / 6),
                    y - size * Math.sin(angle - Math.PI / 6));
        head.lineTo(x - size * Math.cos(angle + Math.PI / 6),
                    y - size * Math.sin(angle + Math.PI / 6));
        head.closePath();
        g.fill(head);
    }

    /** Aligns a component to the left of a vertical box. */
    public static void alignLeft(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
    }
}
