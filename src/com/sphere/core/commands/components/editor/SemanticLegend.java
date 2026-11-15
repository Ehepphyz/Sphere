package com.sphere.components.editor;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * The color code of resolved names, drawn with the very styles the editor
 * paints them in: what each hue means, what each face and stroke says, and the
 * cap of each runtime family.
 */
public final class SemanticLegend extends JComponent {

    private static final long serialVersionUID = 1L;

    private record Row(String header, String sample, int sem, String meaning) {
    }

    private final List<Row> rows = new ArrayList<>();

    public SemanticLegend() {
        header("What a name is (its hue)");
        row("events", PySemantics.Role.VARIABLE, 0, null, "global variable: the notebook's state");
        row("count", PySemantics.Role.LOCAL, 0, null, "local variable of a function");
        row("pt_min", PySemantics.Role.PARAMETER, 0, null, "parameter, keyword argument (italic)");
        row("self", PySemantics.Role.SELF, 0, null, "self / cls");
        row("jet.pt", PySemantics.Role.ATTRIBUTE, 0, null, "attribute");
        row("select", PySemantics.Role.FUNCTION, 0, null, "function, method, builtin");
        row("Tagger", PySemantics.Role.CLASS, 0, null, "class, type");
        row("np", PySemantics.Role.MODULE, 0, null, "module");
        row("MAX_PT", PySemantics.Role.CONSTANT, 0, null, "constant: ALL_CAPS, Final, True / None");
        header("How it is used (face and strokes)");
        row("total", PySemantics.Role.VARIABLE, PySemantics.DEF, null, "bold: where it is defined");
        row("total", PySemantics.Role.VARIABLE, PySemantics.REASSIGNED, null, "underlined: bound more than once");
        row("tmp", PySemantics.Role.VARIABLE, PySemantics.UNUSED, null, "faded: never read anywhere");
        row("list", PySemantics.Role.VARIABLE, PySemantics.SHADOWS, null, "dashed: hides a builtin");
        row("weights", PySemantics.Role.VARIABLE, PySemantics.STALE | PySemantics.LIVE, null,
            "dotted: computed from a value changed since (stale)");
        row("tpyo", PySemantics.Role.VARIABLE, PySemantics.UNRESOLVED, null, "faint dots: no cell defines it");
        header("What the kernel holds (a chip behind a live variable)");
        row("x", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.TENSOR, "tensor (PyTorch, TensorFlow, JAX)");
        row("arr", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.ARRAY, "NumPy array");
        row("df", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.FRAME, "data frame (pandas, polars)");
        row("events", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.JAGGED, "jagged array (awkward)");
        row("h_pt", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.HIST, "histogram (ROOT, hist, boost)");
        row("tree", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.ROOT, "ROOT / uproot tree, file, frame");
        row("net", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.MODEL, "model (torch.nn, Keras, sklearn)");
        row("fig", PySemantics.Role.VARIABLE, PySemantics.LIVE, PySemantics.Family.FIGURE, "figure (matplotlib)");
        setOpaque(true);
    }

    private void header(String text) {
        rows.add(new Row(text, null, 0, null));
    }

    private void row(String sample, PySemantics.Role role, int mods, PySemantics.Family family, String meaning) {
        rows.add(new Row(null, sample, PySemantics.pack(role, mods, family == null ? PySemantics.Family.NONE : family),
                         meaning));
    }

    private Font code() {
        Font f = getFont();
        try {
            return com.sphere.fonts.FontLoader.getTerminalFont(Font.PLAIN, 13);
        } catch (RuntimeException e) {
            return f == null ? new Font(Font.MONOSPACED, Font.PLAIN, 13) : f;
        }
    }

    private Font text() {
        try {
            return com.sphere.fonts.FontLoader.getGlobalFont(Font.PLAIN, 12);
        } catch (RuntimeException e) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(code());
        FontMetrics tm = getFontMetrics(text());
        int width = 0;
        int height = 12;
        for (Row r : rows) {
            if (r.header() != null) {
                width = Math.max(width, tm.stringWidth(r.header()) + 24);
                height += tm.getHeight() + 10;
            } else {
                width = Math.max(width, 24 + fm.charWidth('m') * 10 + tm.stringWidth(r.meaning()) + 16);
                height += fm.getHeight() + 4;
            }
        }
        return new Dimension(Math.max(380, width), height + 6);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(EditorTheme.background());
            g.fillRect(0, 0, getWidth(), getHeight());
            Font base = code();
            Font bold = base.deriveFont(Font.BOLD);
            Font italic = base.deriveFont(Font.ITALIC);
            Font label = text();
            FontMetrics fm = g.getFontMetrics(base);
            FontMetrics tm = g.getFontMetrics(label);
            int x = 14;
            int y = 8;
            int sampleWidth = fm.charWidth('m') * 10;
            for (Row r : rows) {
                if (r.header() != null) {
                    y += 8;
                    g.setFont(label.deriveFont(Font.BOLD));
                    g.setColor(EditorTheme.textMuted());
                    g.drawString(r.header(), x, y + tm.getAscent());
                    y += tm.getHeight() + 2;
                    continue;
                }
                SemanticStyle.Look look = SemanticStyle.look(r.sem());
                int style = look.fontStyle();
                Font f = style == Font.BOLD ? bold : style == Font.ITALIC ? italic
                       : style == (Font.BOLD | Font.ITALIC) ? base.deriveFont(Font.BOLD | Font.ITALIC) : base;
                int baseline = y + fm.getAscent() + 2;
                float sx = x + 10;
                float ex = sx + g.getFontMetrics(f).stringWidth(r.sample());
                if ((look.decorations() & SemanticStyle.D_CAP) != 0 && look.cap() != null) {
                    CodeTextUI.chip(g, look, sx, ex, baseline, fm);
                }
                g.setFont(f);
                g.setColor(look.color());
                g.drawString(r.sample(), sx, baseline);
                if (look.decorations() != 0) {
                    CodeTextUI.decorate(g, look, sx, ex, baseline, fm);
                }
                g.setFont(label);
                g.setColor(EditorTheme.foreground());
                g.drawString(r.meaning(), x + 10 + sampleWidth + 14, baseline);
                y += fm.getHeight() + 4;
            }
        } finally {
            g.dispose();
        }
    }
}
