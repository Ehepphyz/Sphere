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
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
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
            int segEnd;
            if (i < n && h.spanStart(i) <= pos) {
                kind = h.spanKind(i);
                segEnd = Math.min(h.spanEnd(i), p1);
            } else {
                kind = TokenKind.DEFAULT;
                segEnd = i < n ? Math.min(h.spanStart(i), p1) : p1;
            }
            if (segEnd <= pos) {
                segEnd = p1;
            }
            doc.getText(pos, segEnd - pos, seg);
            g.setColor(styles.color(kind));
            Font font = styles.font(kind);
            g.setFont(font);
            x = drawRun(g, font, fm, seg, x, y, expander, pos);
            pos = segEnd;
        }
        g.setFont(styles.base);
        return x;
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

        private Styles(Font base, ThemePalette palette, LanguageSpec spec, JTextComponent c) {
            this.base = base;
            this.palette = palette;
            this.spec = spec;
            for (TokenKind kind : TokenKind.values()) {
                colors.put(kind, EditorTheme.token(kind));
            }
            Font bold = boldOf(base, c);
            Font italic = base.deriveFont(Font.ITALIC);
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
