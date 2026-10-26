package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small flat controls shared by the editor's bars and floating widgets. They
 * read their colors at paint time, so a theme switch needs no rebuild.
 */
public final class EditorWidgets {

    private EditorWidgets() {
    }

    static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    static Font uiFont(int style, int size) {
        return FontLoader.getGlobalFont(style, size);
    }

    // ---- Buttons -----------------------------------------------------------

    /** An icon (or text) button with a rounded hover background. */
    public static class FlatButton extends JButton {
        private boolean hovered;

        public FlatButton(Icon icon, String tooltip) {
            super(icon);
            init(tooltip);
        }

        public FlatButton(String text, String tooltip) {
            super(text);
            init(tooltip);
            setFont(uiFont(Font.PLAIN, 12));
        }

        private void init(String tooltip) {
            setUI(new BasicButtonUI());
            setToolTipText(tooltip);
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override
        public void updateUI() {
            setUI(new BasicButtonUI());
        }

        @Override
        protected void paintComponent(Graphics g) {
            if ((hovered || getModel().isPressed()) && isEnabled()) {
                Graphics2D g2 = smooth(g);
                g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), getModel().isPressed() ? 40 : 24));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
                g2.dispose();
            }
            setForeground(isEnabled() ? EditorTheme.foreground() : EditorTheme.textFaint());
            super.paintComponent(g);
        }
    }

    /** A two-state button showing short text ("Aa", ".*") with a highlighted on state. */
    public static class FlatToggle extends JToggleButton {
        private boolean hovered;

        public FlatToggle(String text, String tooltip) {
            super(text);
            setUI(new BasicButtonUI());
            setToolTipText(tooltip);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setFont(uiFont(Font.BOLD, 11));
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override
        public void updateUI() {
            setUI(new BasicButtonUI());
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(22, d.width), 20);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            if (isSelected()) {
                g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 70));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 5, 5));
                g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 200));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 5, 5));
            } else if (hovered) {
                g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 22));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 5, 5));
            }
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(isSelected() ? EditorTheme.foreground() : EditorTheme.textMuted());
            String t = getText();
            g2.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    // ---- Text field --------------------------------------------------------

    /** A flat input with a placeholder and a focus ring. */
    public static class FlatField extends JTextField {
        private String placeholder;
        private boolean invalid;

        public FlatField(String placeholder) {
            this.placeholder = placeholder;
            setUI(new BasicTextFieldUI());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
            setFont(uiFont(Font.PLAIN, 13));
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
            applyColors();
        }

        @Override
        public void updateUI() {
            setUI(new BasicTextFieldUI());
            applyColors();
        }

        private void applyColors() {
            setForeground(EditorTheme.foreground());
            setCaretColor(EditorTheme.foreground());
            setSelectionColor(EditorTheme.selection());
            setSelectedTextColor(EditorTheme.foreground());
        }

        public void setPlaceholderHint(String hint) {
            this.placeholder = hint;
            repaint();
        }

        public void setInvalid(boolean value) {
            if (invalid != value) {
                invalid = value;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            applyColors();
            Graphics2D g2 = smooth(g);
            g2.setColor(EditorTheme.widgetInput());
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
            Color ring = invalid ? EditorTheme.errorSquiggle()
                       : isFocusOwner() ? EditorTheme.focus() : EditorTheme.widgetBorder();
            g2.setColor(ring);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 6, 6));
            g2.dispose();
            super.paintComponent(g);
            if (getText().isEmpty() && placeholder != null) {
                Graphics2D g3 = smooth(g);
                g3.setFont(getFont());
                g3.setColor(EditorTheme.textFaint());
                Insets in = getInsets();
                FontMetrics fm = g3.getFontMetrics();
                g3.drawString(placeholder, in.left + 1, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g3.dispose();
            }
        }
    }

    // ---- Floating card -----------------------------------------------------

    /** A rounded panel with a soft shadow, for widgets that float over the code. */
    public static class Card extends JPanel {
        private final int shadow;
        private final int radius;

        public Card(LayoutManager layout, int shadow, int radius) {
            super(layout);
            this.shadow = shadow;
            this.radius = radius;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(shadow / 2 + 6, shadow + 8, shadow + 6, shadow + 8));
        }

        public int shadowSize() {
            return shadow;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            int w = getWidth();
            int h = getHeight();
            int s = shadow;
            for (int i = s; i > 0; i--) {
                int a = (int) (EditorTheme.shadow().getAlpha() * (1.0 - i / (double) (s + 1)) / s * 2.2);
                g2.setColor(new Color(0, 0, 0, Math.max(0, Math.min(255, a))));
                g2.fill(new RoundRectangle2D.Float(s - i, s - i + 2, w - 2 * (s - i), h - 2 * (s - i) - 1,
                                                   radius + i, radius + i));
            }
            RoundRectangle2D body = new RoundRectangle2D.Float(s, s / 2f, w - 2 * s, h - s - s / 2f, radius, radius);
            g2.setColor(EditorTheme.widgetBackground());
            g2.fill(body);
            g2.setColor(EditorTheme.widgetBorder());
            g2.draw(body);
            g2.dispose();
        }
    }

    // ---- Split pane --------------------------------------------------------

    /**
     * A split pane that leaves the keyboard to the editor. A plain one answers
     * F6, F8 and Ctrl+Tab for any component inside it (moving its divider or
     * the focus), which took Next Problem, Next Editor and Focus Next Group
     * away from the code. Under every look and feel and after a theme switch.
     */
    public static class QuietSplitPane extends javax.swing.JSplitPane {
        public QuietSplitPane(int orientation) {
            super(orientation);
        }

        public QuietSplitPane(int orientation, Component left, Component right) {
            super(orientation, left, right);
        }

        @Override
        protected boolean processKeyBinding(javax.swing.KeyStroke ks, java.awt.event.KeyEvent e, int condition,
                                            boolean pressed) {
            if (condition == WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) {
                return false;
            }
            return super.processKeyBinding(ks, e, condition, pressed);
        }
    }

    /**
     * A quiet split pane whose divider is drawn in the editor's colors, not
     * the look and feel's bar, and lights up under the pointer to show it can
     * be dragged. It keeps its look after a theme switch.
     */
    public static class FlatSplitPane extends QuietSplitPane {
        public FlatSplitPane(int orientation, Component left, Component right) {
            super(orientation, left, right);
        }

        @Override
        public void updateUI() {
            setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
                @Override
                public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                    return new FlatDivider(this);
                }
            });
            setBorder(null);
            revalidate();
        }
    }

    private static final class FlatDivider extends javax.swing.plaf.basic.BasicSplitPaneDivider {
        private boolean hovered;

        FlatDivider(javax.swing.plaf.basic.BasicSplitPaneUI ui) {
            super(ui);
            setBorder(null);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override
        public void paint(Graphics g) {
            g.setColor(hovered ? EditorTheme.focus() : EditorTheme.chrome());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    // ---- Overlay -----------------------------------------------------------

    /** Where an overlay sits over the base component. */
    public enum Anchor { TOP_RIGHT, TOP_CENTER, BOTTOM_RIGHT, BOTTOM_CENTER }

    /**
     * A base component with widgets floating over it: the find bar over the
     * code, the command palette and the notifications over the editor.
     */
    public static final class OverlayPane extends JLayeredPane {
        private Component base;
        private final Map<Component, Object[]> overlays = new LinkedHashMap<>();

        public OverlayPane(Component base) {
            this.base = base;
            setLayout(null);
            add(base, DEFAULT_LAYER);
        }

        public void setBase(Component c) {
            if (base != null) {
                remove(base);
            }
            base = c;
            add(c, DEFAULT_LAYER);
            revalidate();
        }

        public void addOverlay(JComponent c, Anchor anchor, int marginX, int marginY) {
            overlays.put(c, new Object[] { anchor, marginX, marginY });
            add(c, PALETTE_LAYER);
            revalidate();
        }

        public void setMargins(JComponent c, int marginX, int marginY) {
            Object[] spec = overlays.get(c);
            if (spec != null) {
                spec[1] = marginX;
                spec[2] = marginY;
                revalidate();
            }
        }

        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false;
        }

        @Override
        public Dimension getPreferredSize() {
            return base == null ? super.getPreferredSize() : base.getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return base == null ? super.getMinimumSize() : base.getMinimumSize();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            if (base != null) {
                base.setBounds(0, 0, w, h);
            }
            for (Map.Entry<Component, Object[]> e : overlays.entrySet()) {
                Component c = e.getKey();
                if (!c.isVisible()) {
                    continue;
                }
                Anchor anchor = (Anchor) e.getValue()[0];
                int mx = (int) e.getValue()[1];
                int my = (int) e.getValue()[2];
                Dimension d = c.getPreferredSize();
                int cw = Math.min(d.width, Math.max(120, w - 2 * mx));
                int ch = Math.min(d.height, h);
                Rectangle r;
                switch (anchor) {
                    case TOP_CENTER:    r = new Rectangle((w - cw) / 2, my, cw, ch); break;
                    case BOTTOM_RIGHT:  r = new Rectangle(w - cw - mx, h - ch - my, cw, ch); break;
                    case BOTTOM_CENTER: r = new Rectangle((w - cw) / 2, h - ch - my, cw, ch); break;
                    case TOP_RIGHT:
                    default:            r = new Rectangle(w - cw - mx, my, cw, ch); break;
                }
                c.setBounds(r);
            }
        }
    }
}
