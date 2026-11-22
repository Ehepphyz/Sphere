package com.sphere.components.g4editor;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * G4editor's look, after PTC Creo Parametric: a ribbon of labeled groups under a File tab, a navigator
 * of icon tabs on the left, a graphics toolbar floating over the view, a dashboard for the tool at work,
 * a status line at the bottom. Dark by default, as Sphere is; light (Creo's classic colors) when Sphere
 * runs its light theme. Everything here paints itself: Sphere's theme, which restyles every button of
 * every window when it changes, leaves it as it is.
 */
final class SphereCreo {

    private SphereCreo() {
    }

    /** The colors of the interface and of the graphics area. */
    record Palette(boolean dark, Color window, Color title, Color titleText, Color tabRow, Color tabText, Color tabTextOn,
                   Color tabOn, Color tabHover, Color file, Color fileText, Color ribbonTop, Color ribbonBottom,
                   Color groupLine, Color groupTitle, Color text, Color textDim, Color hover, Color hoverLine, Color press,
                   Color pressLine, Color accent, Color context, Color navBg, Color navHeader, Color line, Color statusBg,
                   Color statusText, Color field, Color fieldLine, int graphicsTop, int graphicsBottom, int select,
                   int preselect, int edge, int datum) {
    }

    static final Palette DARK = new Palette(true,
        new Color(30, 33, 39), new Color(23, 25, 30), new Color(206, 211, 219),
        new Color(32, 36, 42), new Color(170, 178, 190), new Color(244, 246, 249), new Color(46, 51, 59), new Color(52, 58, 68),
        new Color(70, 146, 54), Color.WHITE,
        new Color(46, 51, 59), new Color(39, 43, 50), new Color(63, 69, 80), new Color(134, 143, 157),
        new Color(224, 228, 234), new Color(150, 158, 170),
        new Color(60, 68, 80), new Color(88, 102, 122), new Color(30, 74, 122), new Color(66, 148, 234),
        new Color(66, 148, 234), new Color(108, 186, 72),
        new Color(35, 39, 46), new Color(43, 48, 56), new Color(56, 62, 72),
        new Color(25, 28, 33), new Color(172, 180, 192),
        new Color(27, 30, 36), new Color(72, 80, 92),
        0xFF475262, 0xFF181C22, 0xFF3DDC6A, 0xFF29B6F6, 0xFF0C0E11, 0xFFD69D5B);

    /** Creo's classic colors, for Sphere's light theme. */
    static final Palette LIGHT = new Palette(false,
        new Color(232, 235, 239), new Color(246, 247, 249), new Color(40, 44, 52),
        new Color(236, 238, 241), new Color(60, 66, 76), new Color(18, 22, 28), Color.WHITE, new Color(224, 229, 236),
        new Color(78, 158, 52), Color.WHITE,
        Color.WHITE, new Color(243, 245, 248), new Color(214, 218, 224), new Color(110, 116, 126),
        new Color(30, 34, 40), new Color(100, 106, 116),
        new Color(226, 238, 252), new Color(150, 190, 236), new Color(204, 228, 247), new Color(86, 157, 229),
        new Color(40, 120, 215), new Color(78, 158, 52),
        Color.WHITE, new Color(243, 245, 248), new Color(214, 218, 224),
        new Color(236, 238, 241), new Color(60, 66, 76),
        Color.WHITE, new Color(170, 178, 190),
        0xFFC3CFDF, 0xFFFCFDFE, 0xFF22B14C, 0xFF00A2E8, 0xFF1A1C20, 0xFFB07A40);

    /** The palette of the moment: Sphere's theme decides (dark unless Sphere runs light). */
    static Palette palette() {
        final Color c = UIManager.getColor("Panel.background");
        return c == null || (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 110 ? DARK : LIGHT;
    }

    // Creo's interface font is Segoe UI on Windows; the logical font elsewhere
    static final Font UI = font(Font.PLAIN, 12), UI_BOLD = font(Font.BOLD, 12), SMALL = font(Font.PLAIN, 11),
        TITLE = font(Font.BOLD, 13), BIG = font(Font.BOLD, 15);

    private static Font font(int style, int size) {
        final Font f = new Font("Segoe UI", style, size);
        return "Segoe UI".equals(f.getFamily()) ? f : new Font(Font.SANS_SERIF, style, size);
    }

    static Graphics2D smooth(Graphics g0) {
        final Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    /** The sizes of a button: a ribbon's large one, a small one with its label, an icon alone. */
    enum Size { LARGE, SMALL, ICON }

    /**
     * A button of the ribbon, of the navigator, of the quick access bar: flat until the mouse is over it,
     * lit while what it switches is on (a tool at work, a view option), with a small arrow when it opens a
     * menu.
     */
    static class Button extends JButton {
        private static final long serialVersionUID = 1L;
        final Size size;
        private final Icon icon;
        private final String[] lines;
        private final String label;
        private BooleanSupplier on = () -> false;
        private boolean menu;

        Button(Size size, Icon icon, String label, String tip, Runnable action) {
            this.size = size;
            this.icon = icon;
            this.label = label == null ? "" : label;
            this.lines = size == Size.LARGE ? split(this.label) : new String[] {this.label};
            setToolTipText(tip);
            restyle();
            if (action != null) {
                addActionListener(e -> action.run());
            }
        }

        /** Lit while this holds (a tool at work, an option on). */
        Button on(BooleanSupplier s) {
            on = s == null ? () -> false : s;
            return this;
        }

        /** Shows the small arrow of a button that opens a menu. */
        Button menu() {
            menu = true;
            return this;
        }

        String label() {
            return label;
        }

        private void restyle() {
            setBorder(BorderFactory.createEmptyBorder());
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            restyle(); // Sphere's theme changes reset the look and feel of every button
        }

        /** A label on two lines when it is long, cut at the space nearest its middle. */
        private static String[] split(String s) {
            if (s.length() <= 10 || !s.contains(" ")) {
                return new String[] {s};
            }
            int best = -1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == ' ' && (best < 0 || Math.abs(i - s.length() / 2) < Math.abs(best - s.length() / 2))) {
                    best = i;
                }
            }
            return new String[] {s.substring(0, best), s.substring(best + 1)};
        }

        @Override
        public Dimension getPreferredSize() {
            final FontMetrics fm = getFontMetrics(UI);
            return switch (size) {
                case LARGE -> {
                    int w = 0;
                    for (String l : lines) {
                        w = Math.max(w, fm.stringWidth(l));
                    }
                    yield new Dimension(Math.max(50, w + 14 + (menu && lines.length == 1 ? 10 : 0)), 68);
                }
                case SMALL -> new Dimension(6 + 16 + 6 + fm.stringWidth(label) + (menu ? 12 : 0) + 8, 22);
                case ICON -> new Dimension(28, 24);
            };
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Palette p = palette();
            final Graphics2D g = smooth(g0);
            final int w = getWidth(), h = getHeight();
            final boolean lit = on.getAsBoolean(), over = getModel().isRollover() && isEnabled(),
                down = getModel().isArmed() && getModel().isPressed();
            if (lit || over || down) {
                g.setColor(down ? p.press().darker() : lit ? p.press() : p.hover());
                g.fillRoundRect(1, 1, w - 2, h - 2, 6, 6);
                g.setColor(lit || down ? p.pressLine() : p.hoverLine());
                g.drawRoundRect(1, 1, w - 3, h - 3, 6, 6);
            }
            if (!isEnabled()) {
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.4f));
            }
            final Color ink = lit && !p.dark() ? p.text() : lit ? p.tabTextOn() : p.text();
            g.setFont(UI);
            final FontMetrics fm = g.getFontMetrics();
            switch (size) {
                case LARGE -> {
                    if (icon != null) {
                        icon.paintIcon(this, g, (w - icon.getIconWidth()) / 2, 3);
                    }
                    g.setColor(ink);
                    int y = 3 + 32 + 2 + fm.getAscent();
                    for (int i = 0; i < lines.length; i++) {
                        final String l = lines[i];
                        final boolean arrow = menu && i == lines.length - 1;
                        final int lw = fm.stringWidth(l) + (arrow ? 9 : 0);
                        final int x = (w - lw) / 2;
                        g.drawString(l, x, y);
                        if (arrow) {
                            caret(g, x + fm.stringWidth(l) + 3, y - fm.getAscent() / 2 + 1, ink);
                        }
                        y += fm.getHeight() - 3;
                    }
                }
                case SMALL -> {
                    if (icon != null) {
                        icon.paintIcon(this, g, 5, (h - icon.getIconHeight()) / 2);
                    }
                    g.setColor(ink);
                    final int tx = 5 + 16 + 6;
                    g.drawString(label, tx, (h + fm.getAscent() - fm.getDescent()) / 2);
                    if (menu) {
                        caret(g, tx + fm.stringWidth(label) + 5, h / 2, ink);
                    }
                }
                case ICON -> {
                    if (icon != null) {
                        icon.paintIcon(this, g, (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
                    }
                }
            }
            g.dispose();
        }
    }

    /** The small downward arrow of a menu button. */
    static void caret(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.fillPolygon(new int[] {x - 3, x + 3, x}, new int[] {y - 1, y - 1, y + 3}, 3);
    }

    // ------------------------------------------------------------------
    // Ribbon groups
    // ------------------------------------------------------------------

    /**
     * A group of the ribbon: its buttons side by side, its name under them, a line after it (Creo's look). When the
     * window is too narrow for the whole ribbon, a group folds into one button named after it, which opens it under
     * itself, as Creo's groups do.
     */
    static final class Group extends JPanel {
        private static final long serialVersionUID = 1L;
        private final String title;
        private final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        private boolean folded;
        private Button fold;
        /** The folded group, opened under its button; null when closed. */
        private javax.swing.JWindow open;
        private java.awt.event.AWTEventListener closer;

        Group(String title) {
            super(new BorderLayout());
            this.title = title;
            setOpaque(false);
            row.setOpaque(false);
            add(row, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 18, 8));
        }

        /** The width the group takes open. */
        int fullWidth() {
            final java.awt.Insets in = getInsets();
            return row.getPreferredSize().width + in.left + in.right;
        }

        int fullHeight() {
            final java.awt.Insets in = getInsets();
            return row.getPreferredSize().height + in.top + in.bottom;
        }

        int foldedWidth() {
            final java.awt.Insets in = getInsets();
            return foldButton().getPreferredSize().width + in.left + in.right;
        }

        boolean folded() {
            return folded;
        }

        /** Folds the group into its button, or opens it again in the ribbon. */
        void setFolded(boolean f) {
            if (f == folded) {
                return;
            }
            close();
            folded = f;
            removeAll();
            add(f ? foldButton() : row, BorderLayout.CENTER);
        }

        private Button foldButton() {
            if (fold == null) {
                final Icon small = firstIcon(row);
                // the group's own icon, drawn large: the first one it shows
                final Icon icon = small == null ? null : new Icon() {
                    @Override
                    public void paintIcon(Component c, Graphics g, int x, int y) {
                        final Graphics2D g2 = (Graphics2D) g.create();
                        g2.translate(x, y);
                        g2.scale(32.0 / small.getIconWidth(), 32.0 / small.getIconHeight());
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        small.paintIcon(c, g2, 0, 0);
                        g2.dispose();
                    }

                    @Override
                    public int getIconWidth() {
                        return 32;
                    }

                    @Override
                    public int getIconHeight() {
                        return 32;
                    }
                };
                fold = new Button(Size.LARGE, icon, title, title + " (the window is too narrow to show it: click to open"
                    + " it)", this::toggleOpen).menu();
            }
            return fold;
        }

        private static Icon firstIcon(java.awt.Container c) {
            for (Component k : c.getComponents()) {
                if (k instanceof Button b && b.icon != null) {
                    return b.icon;
                }
                if (k instanceof java.awt.Container cc) {
                    final Icon i = firstIcon(cc);
                    if (i != null) {
                        return i;
                    }
                }
            }
            return null;
        }

        /** Opens the folded group under its button; a click elsewhere, Esc or the window moving closes it. */
        private void toggleOpen() {
            if (open != null) {
                close();
                return;
            }
            final java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
            if (owner == null || !fold.isShowing()) {
                return;
            }
            final javax.swing.JWindow w = new javax.swing.JWindow(owner);
            final JPanel body = new JPanel(new BorderLayout()) {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g) {
                    final Palette p = palette();
                    final Graphics2D g2 = (Graphics2D) g;
                    g2.setPaint(new GradientPaint(0, 0, p.ribbonTop(), 0, getHeight(), p.ribbonBottom()));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette().groupLine()),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            body.add(row, BorderLayout.CENTER);
            w.setContentPane(body);
            w.pack();
            final java.awt.Point at = fold.getLocationOnScreen();
            w.setLocation(at.x, at.y + fold.getHeight());
            w.setVisible(true);
            open = w;
            closer = e -> {
                if (open == null) {
                    return;
                }
                if (e instanceof java.awt.event.MouseEvent me && me.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                    final Component src = me.getComponent();
                    final java.awt.Window sw = src == null ? null : src instanceof java.awt.Window win ? win
                        : javax.swing.SwingUtilities.getWindowAncestor(src);
                    boolean inside = src == fold;
                    for (java.awt.Window x = sw; x != null && !inside; x = x.getOwner()) {
                        inside = x == open; // the group's window, or a list a combo of it opened
                    }
                    if (!inside) {
                        close();
                    }
                } else if (e instanceof java.awt.event.KeyEvent ke && ke.getID() == java.awt.event.KeyEvent.KEY_PRESSED
                    && ke.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    close();
                } else if (e instanceof java.awt.event.ComponentEvent ce && ce.getComponent() == owner
                    && (ce.getID() == java.awt.event.ComponentEvent.COMPONENT_MOVED
                    || ce.getID() == java.awt.event.ComponentEvent.COMPONENT_RESIZED)) {
                    close();
                }
            };
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(closer, java.awt.AWTEvent.MOUSE_EVENT_MASK
                | java.awt.AWTEvent.KEY_EVENT_MASK | java.awt.AWTEvent.COMPONENT_EVENT_MASK);
            repaint();
        }

        private void close() {
            if (open == null) {
                return;
            }
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(closer);
            closer = null;
            open.getContentPane().remove(row);
            open.dispose();
            open = null;
            if (!folded) {
                add(row, BorderLayout.CENTER);
            }
            repaint();
        }

        /** Adds a large button, a stack, any component. */
        Group add(JComponent c) {
            row.add(c);
            return this;
        }

        /** Up to three small things stacked in a column. */
        Group stack(JComponent... cs) {
            final JPanel col = new JPanel(new GridLayout(3, 1, 0, 1));
            col.setOpaque(false);
            for (int i = 0; i < 3; i++) {
                if (i < cs.length && cs[i] != null) {
                    col.add(cs[i]);
                } else {
                    final JPanel gap = new JPanel();
                    gap.setOpaque(false);
                    col.add(gap);
                }
            }
            row.add(col);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Palette p = palette();
            final Graphics2D g = smooth(g0);
            if (!folded) {
                g.setFont(SMALL);
                final FontMetrics fm = g.getFontMetrics();
                g.setColor(p.groupTitle());
                g.drawString(title, (getWidth() - fm.stringWidth(title)) / 2, getHeight() - 5);
            }
            g.setColor(p.groupLine());
            g.drawLine(getWidth() - 1, 6, getWidth() - 1, getHeight() - 6);
            g.dispose();
        }
    }

    /**
     * The groups of a ribbon page in one row. When they do not fit, groups fold from the right into buttons that
     * open them, until they do; the page keeps the height of its open groups.
     */
    static final class FoldLayout implements java.awt.LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component c) {
        }

        @Override
        public void removeLayoutComponent(Component c) {
        }

        private static int width(Component c) {
            return c instanceof Group g ? g.fullWidth() : c.getPreferredSize().width;
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container p) {
            int w = 0, h = 0;
            for (Component c : p.getComponents()) {
                if (c.isVisible()) {
                    w += width(c);
                    h = Math.max(h, c instanceof Group g ? g.fullHeight() : c.getPreferredSize().height);
                }
            }
            final java.awt.Insets in = p.getInsets();
            return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container p) {
            return new Dimension(0, preferredLayoutSize(p).height);
        }

        @Override
        public void layoutContainer(java.awt.Container p) {
            final java.awt.Insets in = p.getInsets();
            final List<Component> cs = new ArrayList<>();
            for (Component c : p.getComponents()) {
                if (c.isVisible()) {
                    cs.add(c);
                }
            }
            final int n = cs.size();
            final int[] full = new int[n], small = new int[n];
            int total = 0;
            for (int i = 0; i < n; i++) {
                full[i] = width(cs.get(i));
                small[i] = cs.get(i) instanceof Group g ? g.foldedWidth() : full[i];
                total += full[i];
            }
            final int avail = p.getWidth() - in.left - in.right;
            final boolean[] fold = new boolean[n];
            for (int i = n - 1; i >= 0 && total > avail; i--) {
                if (cs.get(i) instanceof Group && small[i] < full[i]) {
                    fold[i] = true;
                    total -= full[i] - small[i];
                }
            }
            int x = in.left;
            final int h = p.getHeight() - in.top - in.bottom;
            for (int i = 0; i < n; i++) {
                if (cs.get(i) instanceof Group g) {
                    g.setFolded(fold[i]);
                }
                final int w = fold[i] ? small[i] : full[i];
                cs.get(i).setBounds(x, in.top, w, h);
                x += w;
            }
        }
    }

    /** A small labeled field of a group: "Material [G4_Al]". */
    static JComponent labeled(String label, JComponent c, int width) {
        final JPanel p = new JPanel(new BorderLayout(6, 0)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                final Dimension d = super.getPreferredSize();
                return new Dimension(d.width, 22);
            }
        };
        p.setOpaque(false);
        final JLabel l = new JLabel(label) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                super.updateUI();
                setFont(UI);
            }

            @Override
            protected void paintComponent(Graphics g) {
                setForeground(palette().textDim());
                super.paintComponent(g);
            }
        };
        l.setFont(UI);
        l.setPreferredSize(new Dimension(Math.max(52, l.getPreferredSize().width), 22));
        c.setPreferredSize(new Dimension(width, 22));
        p.add(l, BorderLayout.WEST);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------
    // The navigator
    // ------------------------------------------------------------------

    /** The navigator, left: a row of icon tabs (model tree, materials, variables), the page's name, the page. */
    static final class Navigator extends JPanel {
        private static final long serialVersionUID = 1L;
        private final CardLayout cards = new CardLayout();
        private final JPanel pages = new JPanel(cards);
        private final List<String> titles = new ArrayList<>();
        private int shown;
        private final JComponent header;

        Navigator() {
            super(new BorderLayout());
            final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 3)) {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g) {
                    final Palette p = palette();
                    g.setColor(p.navHeader());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(p.line());
                    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                }
            };
            header = new JComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(100, 26);
                }

                @Override
                protected void paintComponent(Graphics g0) {
                    final Palette p = palette();
                    final Graphics2D g = smooth(g0);
                    g.setColor(p.navBg());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setFont(UI_BOLD);
                    g.setColor(p.text());
                    final String t = titles.isEmpty() ? "" : titles.get(shown);
                    g.drawString(t, 10, (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
                    g.setColor(p.line());
                    g.drawLine(8, getHeight() - 1, getWidth() - 8, getHeight() - 1);
                    g.dispose();
                }
            };
            final JPanel north = new JPanel(new BorderLayout());
            north.add(tabs, BorderLayout.NORTH);
            north.add(header, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);
            add(pages, BorderLayout.CENTER);
            this.tabs = tabs;
        }

        private final JPanel tabs;

        /** Adds a page, its tab an icon. */
        Navigator page(String title, Icon icon, Component content) {
            final int index = titles.size();
            titles.add(title);
            pages.add(content, title);
            final Button b = new Button(Size.ICON, icon, title, title, () -> show(index));
            b.on(() -> shown == index);
            tabs.add(b);
            return this;
        }

        void show(int index) {
            shown = index;
            cards.show(pages, titles.get(index));
            header.repaint();
            tabs.repaint();
        }

        /** Shows the page of that name, if there is one. */
        void show(String title) {
            final int i = titles.indexOf(title);
            if (i >= 0) {
                show(i);
            }
        }
    }

    // ------------------------------------------------------------------
    // The status line
    // ------------------------------------------------------------------

    /** The message area at the bottom, Creo's: a sign, what the editor says, and what is under the mouse. */
    static final class StatusBar extends JPanel {
        private static final long serialVersionUID = 1L;

        StatusBar(JLabel message, JLabel hover) {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 28, 3, 10));
            message.setFont(UI);
            hover.setFont(UI);
            add(message, BorderLayout.CENTER);
            add(hover, BorderLayout.EAST);
            setPreferredSize(new Dimension(100, 26));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Palette p = palette();
            final Graphics2D g = smooth(g0);
            g.setColor(p.statusBg());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(p.line());
            g.drawLine(0, 0, getWidth(), 0);
            // the message sign: Creo's blue "i"
            g.setColor(p.accent());
            g.fill(new Ellipse2D.Double(8, getHeight() / 2.0 - 7, 14, 14));
            g.setColor(Color.WHITE);
            g.setFont(UI_BOLD);
            g.drawString("i", 13, getHeight() / 2 + 5);
            for (Component c : getComponents()) {
                c.setForeground(c == getComponent(0) ? p.statusText() : p.textDim());
            }
            g.dispose();
        }
    }

    /** Fills a panel with the ribbon's gradient. */
    static void ribbonBackground(Graphics g0, JComponent c) {
        final Palette p = palette();
        final Graphics2D g = (Graphics2D) g0.create();
        g.setPaint(new GradientPaint(0, 0, p.ribbonTop(), 0, c.getHeight(), p.ribbonBottom()));
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        g.setColor(p.line());
        g.drawLine(0, c.getHeight() - 1, c.getWidth(), c.getHeight() - 1);
        g.dispose();
    }

    /** A thin line in the palette's line color, as a component (between panes). */
    static void outline(Graphics g, JComponent c) {
        g.setColor(palette().line());
        g.drawRect(0, 0, c.getWidth() - 1, c.getHeight() - 1);
    }

    static BasicStroke stroke(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    // ------------------------------------------------------------------
    // The ribbon
    // ------------------------------------------------------------------

    /**
     * Creo's ribbon: the quick access bar and the document's name on top, then the File tab and the tabs,
     * then the groups of the tab chosen. A tool at work gets a tab of its own (Creo's dashboard), taken
     * back when the tool ends. A double click on a tab folds the ribbon away, another unfolds it.
     */
    static final class Ribbon extends JPanel {
        private static final long serialVersionUID = 1L;
        private final java.util.function.Supplier<String> title;
        private final JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 1, 1));
        private final JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 1));
        private final CardLayout cards = new CardLayout();
        private final JPanel content = new JPanel(cards) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                ribbonBackground(g, this);
            }
        };
        private final List<String> tabs = new ArrayList<>();
        private final List<java.awt.Rectangle> tabBoxes = new ArrayList<>();
        private java.awt.Rectangle fileBox = new java.awt.Rectangle();
        private String selected, beforeContext, context;
        private int hoverTab = -2;
        private boolean collapsed;
        private java.util.function.BiConsumer<JComponent, java.awt.Rectangle> file = (c, r) -> { };
        private final javax.swing.Timer refresh;

        Ribbon(java.util.function.Supplier<String> title) {
            super(new BorderLayout());
            this.title = title;
            final JPanel top = new JPanel(new BorderLayout()) {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g0) {
                    final Palette p = palette();
                    final Graphics2D g = smooth(g0);
                    g.setColor(p.title());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setFont(UI);
                    final String t = Ribbon.this.title.get();
                    final FontMetrics fm = g.getFontMetrics();
                    g.setColor(p.titleText());
                    g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() + fm.getAscent()) / 2 - 2);
                    g.dispose();
                }

                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(200, 28);
                }
            };
            quick.setOpaque(false);
            trailing.setOpaque(false);
            top.add(quick, BorderLayout.WEST);
            top.add(trailing, BorderLayout.EAST);
            final JComponent row = new TabRow();
            final JPanel head = new JPanel(new BorderLayout());
            head.add(top, BorderLayout.NORTH);
            head.add(row, BorderLayout.SOUTH);
            add(head, BorderLayout.NORTH);
            add(content, BorderLayout.CENTER);
            // what the ribbon shows lit (tools, options) also changes from the keyboard: kept current
            refresh = new javax.swing.Timer(250, e -> {
                if (isShowing()) {
                    content.repaint();
                    row.repaint();
                    top.repaint();
                }
            });
        }

        @Override
        public void addNotify() {
            super.addNotify();
            refresh.start();
        }

        @Override
        public void removeNotify() {
            refresh.stop();
            super.removeNotify();
        }

        /** What the File tab does: open the file menu below its box (in the row of tabs given). */
        void onFile(java.util.function.BiConsumer<JComponent, java.awt.Rectangle> f) {
            file = f;
        }

        /** A button of the quick access bar, top left. */
        void quick(JComponent c) {
            quick.add(c);
        }

        /** A button top right (help). */
        void trailing(JComponent c) {
            trailing.add(c);
        }

        /** A new tab; its groups go into the panel returned. */
        JPanel tab(String name) {
            final JPanel page = new JPanel(new FoldLayout());
            page.setOpaque(false);
            tabs.add(name);
            content.add(page, name);
            if (selected == null) {
                select(name);
            }
            return page;
        }

        void select(String name) {
            selected = name;
            cards.show(content, name);
            if (collapsed) {
                setCollapsed(false);
            }
            repaint();
        }

        /** The tabs' names, in order (the context tab not counted). */
        List<String> tabNames() {
            return List.copyOf(tabs);
        }

        String selected() {
            return selected;
        }

        /** Shows a tool's own tab, its dashboard, and goes to it. */
        void showContext(String name, JComponent page) {
            if (context != null) {
                content.remove(content.getComponent(content.getComponentCount() - 1));
            } else {
                beforeContext = selected;
            }
            context = name;
            content.add(page, "\u0000context");
            selected = "\u0000context";
            cards.show(content, selected);
            revalidate();
            repaint();
        }

        /** Takes the tool's tab away, back to the tab shown before it. */
        void hideContext() {
            if (context == null) {
                return;
            }
            content.remove(content.getComponent(content.getComponentCount() - 1));
            context = null;
            select(beforeContext == null || beforeContext.startsWith("\u0000") ? tabs.get(0) : beforeContext);
            revalidate();
        }

        private void setCollapsed(boolean c) {
            collapsed = c;
            content.setVisible(!c);
            revalidate();
            repaint();
        }

        /** The row of tabs: File (PTC's green), the tabs, the tool's tab in its color. */
        private final class TabRow extends JComponent {
            private static final long serialVersionUID = 1L;

            TabRow() {
                final java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e) {
                        final int t = at(e.getPoint());
                        if (t != hoverTab) {
                            hoverTab = t;
                            repaint();
                        }
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        hoverTab = -2;
                        repaint();
                    }

                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        final int t = at(e.getPoint());
                        if (t == -1) {
                            file.accept(TabRow.this, new java.awt.Rectangle(fileBox));
                        } else if (t >= 0) {
                            final String name = t < tabs.size() ? tabs.get(t) : "\u0000context";
                            if (e.getClickCount() >= 2) {
                                setCollapsed(!collapsed);
                            } else if (name.equals("\u0000context")) {
                                selected = name;
                                cards.show(content, name);
                                if (collapsed) {
                                    setCollapsed(false);
                                }
                                repaint();
                            } else {
                                select(name);
                            }
                        }
                    }
                };
                addMouseListener(m);
                addMouseMotionListener(m);
            }

            /** -1 the File tab, a tab's index (tabs.size() for the tool's), -2 nothing. */
            private int at(java.awt.Point p) {
                if (fileBox.contains(p)) {
                    return -1;
                }
                for (int i = 0; i < tabBoxes.size(); i++) {
                    if (tabBoxes.get(i).contains(p)) {
                        return i;
                    }
                }
                return -2;
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(200, 27);
            }

            @Override
            protected void paintComponent(Graphics g0) {
                final Palette p = palette();
                final Graphics2D g = smooth(g0);
                final int h = getHeight();
                g.setColor(p.tabRow());
                g.fillRect(0, 0, getWidth(), h);
                g.setFont(UI);
                final FontMetrics fm = g.getFontMetrics();
                // File
                final int fw = fm.stringWidth("File") + 30;
                fileBox = new java.awt.Rectangle(4, 3, fw, h - 3);
                g.setColor(hoverTab == -1 ? p.file().brighter() : p.file());
                g.fillRoundRect(fileBox.x, fileBox.y, fileBox.width, fileBox.height + 4, 6, 6);
                g.setColor(p.fileText());
                g.setFont(UI_BOLD);
                g.drawString("File", fileBox.x + 10, h - 8);
                caret(g, fileBox.x + fileBox.width - 10, h - 12, p.fileText());
                g.setFont(UI);
                // the tabs
                tabBoxes.clear();
                int x = fileBox.x + fileBox.width + 6;
                final List<String> names = new ArrayList<>(tabs);
                if (context != null) {
                    names.add(context);
                }
                for (int i = 0; i < names.size(); i++) {
                    final boolean ctx = context != null && i == names.size() - 1;
                    final String n = names.get(i);
                    final int w = fm.stringWidth(n) + 26;
                    final java.awt.Rectangle r = new java.awt.Rectangle(x, 3, w, h - 3);
                    tabBoxes.add(r);
                    final boolean on = ctx ? "\u0000context".equals(selected) : n.equals(selected);
                    if (on && !collapsed) {
                        g.setColor(p.tabOn());
                        g.fillRoundRect(r.x, r.y, r.width, r.height + 6, 6, 6);
                        g.setColor(p.groupLine());
                        g.drawRoundRect(r.x, r.y, r.width - 1, r.height + 6, 6, 6);
                    } else if (hoverTab == i) {
                        g.setColor(p.tabHover());
                        g.fillRoundRect(r.x, r.y, r.width, r.height + 6, 6, 6);
                    }
                    if (ctx) {
                        // the tool's tab: Creo marks it with a band of color
                        g.setColor(p.context());
                        g.fillRoundRect(r.x + 1, r.y, r.width - 2, 3, 3, 3);
                    }
                    g.setColor(ctx && !on ? p.context() : on ? p.tabTextOn() : p.tabText());
                    g.setFont(on || ctx ? UI_BOLD : UI);
                    g.drawString(n, r.x + 13, h - 8);
                    g.setFont(UI);
                    x += w + 2;
                }
                g.setColor(p.groupLine());
                g.drawLine(0, h - 1, getWidth(), h - 1);
                // the line under the tab shown is that tab's: open toward its groups
                for (int i = 0; i < tabBoxes.size(); i++) {
                    final boolean ctx = context != null && i == tabBoxes.size() - 1;
                    final boolean on = ctx ? "\u0000context".equals(selected) : i < tabs.size() && tabs.get(i).equals(selected);
                    if (on && !collapsed) {
                        g.setColor(p.tabOn());
                        g.drawLine(tabBoxes.get(i).x + 1, h - 1, tabBoxes.get(i).x + tabBoxes.get(i).width - 2, h - 1);
                    }
                }
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    // The dashboard of the tool at work
    // ------------------------------------------------------------------

    /**
     * Creo's dashboard: the tool at work, its values as they are being set (what the mouse gives, what was
     * typed, the field Tab moves to), where the volume will go and what it would hit, what to do next, and
     * OK and Cancel. It shows the tool's own input: values are still typed in the view, by the cursor.
     */
    static final class Dashboard extends JPanel {
        private static final long serialVersionUID = 1L;
        private final Viewport vp;
        private final java.util.function.Function<Tools.Tool, Icon> icons;
        private final javax.swing.Timer refresh;

        Dashboard(Viewport vp, java.util.function.Function<Tools.Tool, Icon> icons) {
            super(new BorderLayout());
            this.vp = vp;
            this.icons = icons;
            setOpaque(false);
            final JComponent info = new JComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g) {
                    paintInfo(smooth(g), getWidth(), getHeight());
                }
            };
            add(info, BorderLayout.CENTER);
            final Group done = new Group("Done");
            done.add(new Button(Size.LARGE, Icons.ok(32), "OK", "Takes the step, as Enter does (or a middle click)",
                vp::toolAccept));
            done.add(new Button(Size.LARGE, Icons.cancel(32), "Cancel", "Leaves the tool (Esc)", () -> vp.setTool(null)));
            add(done, BorderLayout.EAST);
            setPreferredSize(new Dimension(600, 90));
            refresh = new javax.swing.Timer(120, e -> {
                if (isShowing()) {
                    info.repaint();
                }
            });
        }

        @Override
        public void addNotify() {
            super.addNotify();
            refresh.start();
        }

        @Override
        public void removeNotify() {
            refresh.stop();
            super.removeNotify();
        }

        private void paintInfo(Graphics2D g, int w, int h) {
            final Palette p = palette();
            final Tools.Tool t = vp.tool;
            if (t == null) {
                g.dispose();
                return;
            }
            // the tool
            final Icon ic = icons.apply(t);
            if (ic != null) {
                ic.paintIcon(this, g, 14, 10);
            }
            g.setFont(BIG);
            g.setColor(p.text());
            g.drawString(t.name(), 56, 30);
            g.setFont(SMALL);
            g.setColor(p.textDim());
            g.drawString(t.phase == 0 ? "first point" : "step " + (t.phase + 1), 56, 46);
            int x = Math.max(150, 56 + g.getFontMetrics(BIG).stringWidth(t.name()) + 24);
            g.setColor(p.groupLine());
            g.drawLine(x - 12, 8, x - 12, h - 8);
            // its values
            final DynamicInput in = t.hud();
            final String unit = vp.model.lengthUnit;
            for (int i = 0; i < in.fields.size(); i++) {
                final DynamicInput.Field f = in.fields.get(i);
                final String v = in.display(i, unit).replace("|", "");
                g.setFont(SMALL);
                g.setColor(p.textDim());
                g.drawString(f.label, x, 20);
                g.setFont(f.typed != null ? UI_BOLD : UI);
                final int bw = Math.max(92, g.getFontMetrics().stringWidth(v) + 20);
                g.setColor(p.field());
                g.fillRoundRect(x, 25, bw, 24, 5, 5);
                g.setColor(i == in.active ? p.accent() : p.fieldLine());
                g.setStroke(new BasicStroke(i == in.active ? 1.6f : 1f));
                g.drawRoundRect(x, 25, bw, 24, 5, 5);
                g.setColor(f.typed != null ? new Color(0x5FCB78) : p.text());
                g.drawString(v, x + 9, 42);
                if (i == in.active && f.typed != null && (System.currentTimeMillis() / 500) % 2 == 0) {
                    final int cx = x + 9 + g.getFontMetrics().stringWidth(v) + 1;
                    g.drawLine(cx, 29, cx, 45);
                }
                x += bw + 12;
            }
            // where it goes, what it would hit
            final LiveCheck.Verdict v = t.verdict();
            if (v != null) {
                g.setColor(p.groupLine());
                g.drawLine(x - 2, 8, x - 2, h - 30);
                x += 10;
                g.setFont(SMALL);
                g.setColor(p.textDim());
                g.drawString("into " + v.motherName(), x, 20);
                final Color c = v.state() == LiveCheck.State.CLEAR ? new Color(0x4CAF50) : new Color(v.state().argb & 0xFFFFFF);
                g.setColor(c);
                g.fill(new Ellipse2D.Double(x, 31, 10, 10));
                g.setFont(UI_BOLD);
                g.drawString(v.describe(), x + 16, 41);
                x += g.getFontMetrics().stringWidth(v.describe()) + 30;
            }
            // what to do next
            g.setFont(UI);
            g.setColor(p.textDim());
            String hint = t.hint();
            final int room = w - 20;
            final FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(hint) > room) {
                while (hint.length() > 4 && fm.stringWidth(hint + "...") > room) {
                    hint = hint.substring(0, hint.length() - 1);
                }
                hint += "...";
            }
            g.drawString(hint, 14, h - 12);
            if (in.error != null) {
                g.setColor(new Color(0xE05050));
                g.drawString(in.error, Math.min(x, w - fm.stringWidth(in.error) - 10), 64);
            }
            g.dispose();
        }
    }
}
