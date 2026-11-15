package com.sphere.components.editor;

import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.View;
import javax.swing.text.WrappedPlainView;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Text UI of the code surface. Its views paint each token in its own color
 * straight from the highlighter's spans, so coloring a file costs a paint and
 * never an edit of the document.
 */
public final class CodeTextUI extends BasicTextAreaUI {

    @Override
    public View create(Element elem) {
        JTextComponent c = getComponent();
        if (c instanceof CodeTextPane pane) {
            if (pane.getLineWrap()) {
                return new WrappedCodeView(elem, pane);
            }
            return new PlainCodeView(elem, pane);
        }
        return super.create(elem);
    }

    /** The pane paints its own surface, under the line bands and guides. */
    @Override
    protected void paintBackground(Graphics g) {
        // intentionally empty
    }

    // ---- Views -------------------------------------------------------------

    static final class PlainCodeView extends PlainView {
        private final CodeTextPane pane;

        PlainCodeView(Element elem, CodeTextPane pane) {
            super(elem);
            this.pane = pane;
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            return paintTokens(getDocument(), this, getLineBuffer(), g, x, y, p0, p1, pane);
        }

        @Override
        protected float drawSelectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            return paintTokens(getDocument(), this, getLineBuffer(), g, x, y, p0, p1, pane);
        }
    }

    static final class WrappedCodeView extends WrappedPlainView {
        private final CodeTextPane pane;

        WrappedCodeView(Element elem, CodeTextPane pane) {
            super(elem, true);
            this.pane = pane;
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            return paintTokens(getDocument(), this, getLineBuffer(), g, x, y, p0, p1, pane);
        }

        @Override
        protected float drawSelectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            return paintTokens(getDocument(), this, getLineBuffer(), g, x, y, p0, p1, pane);
        }
    }

    /**
     * Draws [p0, p1) cut at span boundaries, each piece in its token's color.
     * Selected text keeps its colors: the selection is painted underneath.
     */
    static float paintTokens(Document doc, TabExpander expander, Segment seg, Graphics2D g,
                             float x, float y, int p0, int p1, CodeTextPane pane)
            throws BadLocationException {
        SyntaxHighlighter h = pane.getSyntax();
        Styles styles = Styles.forPane(pane);
        // The view places the caret and the selection with the component's
        // metrics; glyphs are put on that same grid, or the text would drift
        // from both by a fraction of a pixel per character.
        FontMetrics fm = pane.getFontMetrics(styles.base);
        int n = h == null ? 0 : h.spanCount();
        int i = h == null ? 0 : h.firstSpanAfter(p0);
        int pos = p0;
        while (pos < p1) {
            while (i < n && h.spanEnd(i) <= pos) {
                i++;
            }
            TokenKind kind;
            int sem = 0;
            int segEnd;
            if (i < n && h.spanStart(i) <= pos) {
                kind = h.spanKind(i);
                sem = h.spanSem(i);
                segEnd = Math.min(h.spanEnd(i), p1);
            } else {
                kind = TokenKind.DEFAULT;
                segEnd = i < n ? Math.min(h.spanStart(i), p1) : p1;
            }
            if (segEnd <= pos) {
                segEnd = p1;
            }
            doc.getText(pos, segEnd - pos, seg);
            if (sem != 0) {
                // A resolved name: its role's hue, a face for how it is bound, strokes for how it is used,
                // and behind it a chip in the color of what the kernel holds under it.
                SemanticStyle.Look look = SemanticStyle.look(sem);
                if ((look.decorations() & SemanticStyle.D_CAP) != 0 && look.cap() != null) {
                    chip(g, look, x, x + runWidth(fm, seg), y, fm);
                }
                g.setColor(look.color());
                Font font = styles.fontFor(look.fontStyle());
                g.setFont(font);
                float x0 = x;
                x = drawRun(g, font, fm, seg, x, y, expander, pos);
                if (look.decorations() != 0) {
                    decorate(g, look, x0, x, y, fm);
                }
            } else {
                g.setColor(styles.color(kind));
                Font font = styles.font(kind);
                g.setFont(font);
                x = drawRun(g, font, fm, seg, x, y, expander, pos);
            }
            pos = segEnd;
        }
        g.setFont(styles.base);
        return x;
    }

    /**
     * The strokes of a resolved name, under or over its glyphs: a line when it is
     * bound more than once, dashes when it hides a builtin, dots when the kernel's
     * value is stale, faint dots when nothing defines it, and a cap in the color of
     * its runtime family.
     */
    static void decorate(Graphics2D g, SemanticStyle.Look look, float x0, float x1, float y, FontMetrics fm) {
        if (x1 - x0 < 1f) {
            return;
        }
        int deco = look.decorations();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color before = g.getColor();
        float under = y + Math.max(1.6f, Math.min(2.6f, fm.getDescent() * 0.5f));
        float w = x1 - x0;
        if ((deco & SemanticStyle.D_UNDERLINE) != 0) {
            g.setColor(EditorTheme.alpha(look.color(), 175));
            g.fill(new Rectangle2D.Float(x0, under, w, 1f));
        }
        float low = (deco & SemanticStyle.D_UNDERLINE) != 0 ? under + 2f : under;
        if ((deco & SemanticStyle.D_DASHED) != 0) {
            g.setColor(EditorTheme.warningSquiggle());
            for (float x = x0; x < x1; x += 4.5f) {
                g.fill(new Rectangle2D.Float(x, low, Math.min(2.6f, x1 - x), 1.2f));
            }
        } else if ((deco & SemanticStyle.D_DOTTED) != 0) {
            g.setColor(SemanticStyle.staleColor());
            for (float x = x0 + 0.5f; x < x1; x += 3f) {
                g.fill(new Ellipse2D.Float(x, low - 0.2f, 1.6f, 1.6f));
            }
        } else if ((deco & SemanticStyle.D_UNRESOLVED) != 0) {
            g.setColor(EditorTheme.alpha(EditorTheme.errorSquiggle(), 150));
            for (float x = x0 + 0.5f; x < x1; x += 3f) {
                g.fill(new Rectangle2D.Float(x, low, 1.1f, 1.1f));
            }
        }
        g.setColor(before);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
    }

    /**
     * The chip behind a live variable, in its runtime family's color: inside the
     * name's own line box, so it can never be read as belonging to the line above.
     */
    static void chip(Graphics2D g, SemanticStyle.Look look, float x0, float x1, float y, FontMetrics fm) {
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color before = g.getColor();
        float top = y - fm.getAscent() + 1.5f;
        float height = fm.getAscent() + fm.getDescent() - 3f;
        g.setColor(look.cap());
        g.fill(new RoundRectangle2D.Float(x0 - 2f, top, (x1 - x0) + 4f, height, 7f, 7f));
        g.setColor(before);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
    }

    /** The width a run of a name takes on the view's grid (names hold no tabs). */
    private static float runWidth(FontMetrics fm, Segment seg) {
        float w = 0;
        for (int k = 0; k < seg.count; k++) {
            w += fm.charWidth(seg.array[seg.offset + k]);
        }
        return w;
    }

    /** Draws a piece of a line, each glyph at the x the view's metrics give it. */
    private static float drawRun(Graphics2D g, Font font, FontMetrics fm, Segment seg, float x, float y,
                                 TabExpander expander, int startOffset) {
        char[] a = seg.array;
        int off = seg.offset;
        int count = seg.count;
        int runStart = -1;
        float runX = x;
        for (int k = 0; k < count; k++) {
            char c = a[off + k];
            if (c == '\t' || c == '\n' || c == '\r') {
                if (runStart >= 0) {
                    drawGlyphs(g, font, fm, a, off + runStart, k - runStart, runX, y);
                    runStart = -1;
                }
                if (c == '\t') {
                    x = expander != null ? expander.nextTabStop(x, startOffset + k) : x + fm.charWidth(' ') * 4;
                }
                continue;
            }
            if (runStart < 0) {
                runStart = k;
                runX = x;
            }
            x += fm.charWidth(c);
        }
        if (runStart >= 0) {
            drawGlyphs(g, font, fm, a, off + runStart, count - runStart, runX, y);
        }
        return x;
    }

    private static void drawGlyphs(Graphics2D g, Font font, FontMetrics fm, char[] a, int from, int len,
                                   float x, float y) {
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), Arrays.copyOfRange(a, from, from + len));
        if (gv.getNumGlyphs() != len) {
            // Surrogates or ligatures: the glyphs do not map one to one; draw as text.
            g.drawString(new String(a, from, len), x, y);
            return;
        }
        float px = 0;
        for (int k = 0; k < len; k++) {
            gv.setGlyphPosition(k, new Point2D.Float(px, 0));
            px += fm.charWidth(a[from + k]);
        }
        g.drawGlyphVector(gv, x, y);
    }

    // ---- Styles ------------------------------------------------------------

    /**
     * Colors and fonts per token kind, rebuilt when the theme, the font or the
     * language changes, and otherwise reused by every paint.
     */
    static final class Styles {
        final Font base;
        final ThemePalette palette;
        final LanguageSpec spec;
        final Map<TokenKind, Color> colors = new EnumMap<>(TokenKind.class);
        final Map<TokenKind, Font> fonts = new EnumMap<>(TokenKind.class);
        /** Plain, bold, italic, bold italic: the faces of resolved names. */
        private final Font[] faces = new Font[4];

        private Styles(Font base, ThemePalette palette, LanguageSpec spec, JTextComponent c) {
            this.base = base;
            this.palette = palette;
            this.spec = spec;
            for (TokenKind kind : TokenKind.values()) {
                colors.put(kind, EditorTheme.token(kind));
            }
            Font bold = boldOf(base, c);
            Font italic = base.deriveFont(Font.ITALIC);
            faces[Font.PLAIN] = base;
            faces[Font.BOLD] = bold;
            faces[Font.ITALIC] = italic;
            faces[Font.BOLD | Font.ITALIC] = bold == base ? italic : bold.deriveFont(Font.BOLD | Font.ITALIC);
            fonts.put(TokenKind.COMMENT, italic);
            if (spec != null && spec.dialect == LanguageSpec.Dialect.MARKDOWN) {
                fonts.put(TokenKind.KEYWORD, bold);
                fonts.put(TokenKind.TYPE, bold);
                fonts.put(TokenKind.DECORATOR, italic);
            }
        }

        static Styles forPane(CodeTextPane pane) {
            Styles s = pane.styleCache;
            ThemePalette p = ThemeManager.getCurrentPalette();
            if (s == null || s.base != pane.getFont() || s.palette != p || s.spec != pane.getLanguage()) {
                s = new Styles(pane.getFont(), p, pane.getLanguage(), pane);
                pane.styleCache = s;
            }
            return s;
        }

        Color color(TokenKind kind) {
            Color c = colors.get(kind);
            return c == null ? colors.get(TokenKind.DEFAULT) : c;
        }

        Font font(TokenKind kind) {
            Font f = fonts.get(kind);
            return f == null ? base : f;
        }

        /** The face for a java.awt.Font style of a resolved name. */
        Font fontFor(int style) {
            Font f = style >= 0 && style < faces.length ? faces[style] : null;
            return f == null ? base : f;
        }

        /** The real bold face when it keeps the advance of the regular one. */
        private static Font boldOf(Font base, JTextComponent c) {
            Font bold;
            try {
                bold = FontLoader.getTerminalFont(Font.BOLD, Math.round(base.getSize2D()));
                bold = bold.deriveFont(Font.BOLD, base.getSize2D());
            } catch (RuntimeException ex) {
                bold = base.deriveFont(Font.BOLD);
            }
            FontMetrics a = c.getFontMetrics(base);
            FontMetrics b = c.getFontMetrics(bold);
            return a.charWidth('m') == b.charWidth('m') ? bold : base;
        }
    }
}
