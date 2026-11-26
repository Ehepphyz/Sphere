package com.sphere.core.rootbackend;

import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemePalette;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The forge in Sphere's own look, at Sphere's own sizes.
 *
 * Every control keeps the delegate Sphere's theme gives it -- SPTextFieldUI,
 * SPButtonUI, SPComboBoxUI -- with its colors, corners, focus ring and
 * padding, and the tabs take SPTabbedPaneUI, as in Sphere's main window. What
 * the forge adds is Sphere's fonts where the theme leaves the look and feel's
 * own: Inter for check boxes, lists and tables, which would otherwise come up
 * in a plain sans serif, and Noto Sans Mono -- the editor's font -- for code
 * and logs, rather than the toolkit's Courier. The one exception to Sphere's
 * sizes is an editor opened in a table's cell, which has the row's height and
 * no more.
 *
 * The forge once painted its own controls instead (lighter boxes with a hint
 * written in them, tab labels of its own, a blue Build button, a font of 12.5
 * points, paddings of its own), and no longer looked like the rest of Sphere.
 * A look belongs to Sphere's delegates: a change wanted here is wanted in all
 * of Sphere. Nothing here fixes a size either: a size fixed when the window
 * was built cut "Cancel" to "Can..." as soon as the borders or the font changed.
 */
final class RootForgeStyle {

    private final ThemePalette palette;

    RootForgeStyle(ThemePalette palette) {
        this.palette = palette;
    }

    /** Sphere's UI font, as its other windows take it. */
    Font font() {
        return FontLoader.getGlobalFont(Font.PLAIN, 12);
    }

    Font font(int style, int size) {
        return FontLoader.getGlobalFont(style, size);
    }

    /** Sphere's font for code and logs: its editor's and its terminal's. */
    Font code(int size) {
        return FontLoader.getTerminalFont(Font.PLAIN, size);
    }

    /** The client property that marks a field whose content is wrong. */
    private static final String WRONG = "sphere.forge.wrong";

    /**
     * A field that can say its content is wrong: Sphere's field, with its
     * rounded border drawn in the warning color while the content is wrong,
     * the way the accent marks the focus.
     */
    void checked(JTextField field) {
        field.setUI(new CheckedFieldUI());
    }

    void wrong(JTextField field, boolean wrong) {
        if (Boolean.TRUE.equals(field.getClientProperty(WRONG)) != wrong) {
            field.putClientProperty(WRONG, wrong);
            field.repaint();
        }
    }

    private final class CheckedFieldUI extends com.sphere.ui.SPTextFieldUI {
        @Override
        public void update(Graphics g, JComponent c) {
            // Taken before the text is painted: the field's view clips the
            // graphics it is given to the text, inside the padding, and leaves
            // it so -- drawn after, the border was cut away.
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                super.update(g, c);
                if (Boolean.TRUE.equals(c.getClientProperty(WRONG))) {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(palette.getLogWarnText());
                    g2.setStroke(new BasicStroke(1.5f));
                    // The geometry of SPTextFieldUI's own border.
                    g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** A read-only line of text that is not a field: no box, the secondary color. */
    void caption(JTextField line, Border border) {
        line.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        line.setEditable(false);
        line.setOpaque(true);
        line.setFont(code(11));
        line.setBackground(palette.getTerminalBackground());
        line.setForeground(palette.getTextSecondary());
        line.setBorder(border);
    }

    /**
     * A list: Sphere's, in Sphere's font. The closed list is measured and
     * drawn in it; left to the look and feel, its font was a plain sans serif
     * beside the Inter of everything around it.
     */
    void combo(JComboBox<?> box) {
        box.setFont(font());
    }

    /**
     * A text field opened in a table's cell: Sphere's field, with no more
     * padding than the row has room for. With its own, the text of a
     * 21-pixel row was cut at the top and the bottom.
     */
    void cellField(JTextField field) {
        field.setBorder(new EmptyBorder(1, 10, 1, 10));
    }

    /**
     * A list opened in a table's cell: Sphere's list, slim enough for the
     * row. Only what the closed list shows loses the delegate's tall padding;
     * the items of the open list keep theirs.
     */
    <E> void cellCombo(JComboBox<E> box) {
        combo(box);
        // The delegate adds its own padding (ComboBox.padding) around what is shown.
        box.setBorder(new EmptyBorder(0, 3, 0, 2));
        box.setRenderer(compactShown(box.getRenderer()));
    }

    private static final Border SHOWN = new EmptyBorder(1, 4, 1, 4);

    /**
     * The renderer as it was, but what the closed list shows (index -1, which
     * is also what the list is measured on) with a slim padding. SPComboBoxUI
     * draws with a single label for both, whose padding is set once: it is
     * given back for the items of the open list.
     */
    private static <E> ListCellRenderer<E> compactShown(ListCellRenderer<? super E> inner) {
        final ListCellRenderer<? super E> under = inner != null ? inner : new javax.swing.DefaultListCellRenderer();
        final Border[] kept = {null};
        return (JList<? extends E> list, E value, int index, boolean selected, boolean focused) -> {
            final Component made = under.getListCellRendererComponent(list, value, index, selected, focused);
            if (made instanceof JComponent one) {
                if (index < 0) {
                    if (one.getBorder() != SHOWN) {
                        kept[0] = one.getBorder();
                    }
                    one.setBorder(SHOWN);
                } else if (one.getBorder() == SHOWN) {
                    one.setBorder(kept[0]);
                }
            }
            return made;
        };
    }

    /** A button: Sphere's, whose text dims while it cannot be pressed -- see QuietButtonUI. */
    void button(AbstractButton button) {
        if (button instanceof JButton && !(button.getUI() instanceof QuietButtonUI)) {
            button.setUI(new QuietButtonUI());
        }
    }

    /**
     * Sphere's button delegate, with a disabled button's text drawn flat in
     * a dimmed color. The delegate it builds on draws that text etched --
     * dark, over a light copy one pixel off -- which on Sphere's gray read as
     * black on gray: Save, Edit, Build and Load, all disabled on a new form,
     * were the darkest marks in the window. (Kept to the forge, as decided on
     * 2026-11-23; SPButtonUI itself is unchanged.)
     */
    private final class QuietButtonUI extends com.sphere.ui.SPButtonUI {
        @Override
        protected void paintText(Graphics g, JComponent c, java.awt.Rectangle textRect, String text) {
            if (((AbstractButton) c).getModel().isEnabled()) {
                super.paintText(g, c, textRect, text);
                return;
            }
            g.setColor(quiet());
            javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(c, (Graphics2D) g, text, -1,
                textRect.x, textRect.y + c.getFontMetrics(g.getFont()).getAscent());
        }
    }

    /**
     * Text that is there but cannot be acted on: the secondary color, a fifth
     * of the way to the button under it -- plainly off, and still read at a
     * glance.
     */
    Color quiet() {
        final Color text = palette.getTextSecondary();
        final Color under = palette.getButtonBase();
        return new Color(
            (int) Math.round(text.getRed() * 0.8 + under.getRed() * 0.2),
            (int) Math.round(text.getGreen() * 0.8 + under.getGreen() * 0.2),
            (int) Math.round(text.getBlue() * 0.8 + under.getBlue() * 0.2));
    }

    /**
     * A check box or a radio button, in Sphere's font -- Sphere gives it to
     * labels and buttons but not to check boxes -- with text that dims while
     * it cannot be used. Given a color of its own, Synth paints the text in it
     * in every state, so a box that could not be ticked looked just like one
     * that could.
     */
    void toggle(AbstractButton box) {
        box.setFont(font());
        final Runnable tint = () -> box.setForeground(box.isEnabled() ? palette.getTextPrimary() : quiet());
        tint.run();
        box.addPropertyChangeListener("enabled", e -> tint.run());
    }

    /** A form's label: right-aligned in its column, quieter than what it labels. */
    JLabel formLabel(String text) {
        final JLabel label = new JLabel(text);
        label.setForeground(palette.getTextSecondary());
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        // Room at the right, so a font drawn a hair wider than it measured
        // spills into the margin rather than losing its last letter. A border,
        // not a fixed size: the width still follows the font.
        label.setBorder(new EmptyBorder(0, 4, 0, 6));
        return label;
    }

    /**
     * A table: Sphere's colors, Sphere's font -- the look and feel's sans
     * serif otherwise, as for check boxes -- and rows that fit it.
     */
    void table(JTable table) {
        table.setFont(font());
        table.setRowHeight(Math.max(table.getRowHeight(), table.getFontMetrics(font()).getHeight() + 6));
        table.setShowVerticalLines(false);
        table.getTableHeader().setFont(font());
        table.getTableHeader().setReorderingAllowed(false);
    }

    /** Tabs as in Sphere's main window: the accent under the one shown, its title in bold. */
    void tabs(JTabbedPane tabs) {
        tabs.setUI(new com.sphere.ui.SPTabbedPaneUI());
        tabs.setBorder(new EmptyBorder(6, 0, 0, 0));
    }
}
