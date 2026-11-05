package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.font.PdfFont;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.TextBlock;
import com.sphere.components.spdf.text.TextLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Text being edited: characters, each with its style (font, size, color,
 * bold, italic, underline, strike-through, raise), and paragraphs with
 * theirs (alignment, line spacing, first-line indent). A paragraph of the
 * page loads with every glyph's own style, so a bold word or a red one stays
 * as it was around the words that change.
 */
public final class RichText {

    public enum Align { LEFT, CENTER, RIGHT, JUSTIFY }

    /** The style of one character. Immutable: changes make new styles. */
    public static final class Style {
        public final String family;
        public final boolean bold;
        public final boolean italic;
        public final boolean underline;
        public final boolean strike;
        public final float size;
        public final int color;
        public final float rise;
        /** The document font the text came in, kept while family, bold and italic do not change. */
        public final PdfFont original;

        public Style(String family, boolean bold, boolean italic, boolean underline, boolean strike, float size,
                     int color, float rise, PdfFont original) {
            this.family = family == null || family.isBlank() ? "Arial" : family;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strike = strike;
            this.size = Math.max(1, size);
            this.color = color & 0xffffff;
            this.rise = rise;
            this.original = original;
        }

        public static Style of(TextGlyph g, float sizeInPoints) {
            PdfFont f = g.font;
            return new Style(f == null ? "Arial" : f.family, f != null && f.bold, f != null && f.italic, false, false,
                             sizeInPoints, g.color, 0, f);
        }

        public Style family(String fam) {
            return new Style(fam, bold, italic, underline, strike, size, color, rise, null);
        }

        public Style bold(boolean b) {
            return b == bold ? this : new Style(family, b, italic, underline, strike, size, color, rise, null);
        }

        public Style italic(boolean i) {
            return i == italic ? this : new Style(family, bold, i, underline, strike, size, color, rise, null);
        }

        public Style underline(boolean u) {
            return new Style(family, bold, italic, u, strike, size, color, rise, original);
        }

        public Style strike(boolean s) {
            return new Style(family, bold, italic, underline, s, size, color, rise, original);
        }

        public Style size(float s) {
            return new Style(family, bold, italic, underline, strike, s, color, rise, original);
        }

        public Style color(int c) {
            return new Style(family, bold, italic, underline, strike, size, c, rise, original);
        }

        public Style rise(float r) {
            return new Style(family, bold, italic, underline, strike, size, color, r, original);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Style s && s.family.equals(family) && s.bold == bold && s.italic == italic
                && s.underline == underline && s.strike == strike && s.size == size && s.color == color
                && s.rise == rise && s.original == original;
        }

        @Override
        public int hashCode() {
            return Objects.hash(family, bold, italic, underline, strike, size, color, rise,
                                System.identityHashCode(original));
        }
    }

    /** The style of a paragraph. */
    public static final class Para {
        public Align align = Align.LEFT;
        /** Baseline to baseline as a multiple of the line's largest size. */
        public float lineHeight = 1.2f;
        public float firstIndent;
        public float spaceAfter;

        public Para copy() {
            Para p = new Para();
            p.align = align;
            p.lineHeight = lineHeight;
            p.firstIndent = firstIndent;
            p.spaceAfter = spaceAfter;
            return p;
        }
    }

    private final StringBuilder chars = new StringBuilder();
    private final List<Style> styles = new ArrayList<>();
    private final List<Para> paras = new ArrayList<>();

    public RichText() {
        paras.add(new Para());
    }

    public int length() {
        return chars.length();
    }

    public String text() {
        return chars.toString();
    }

    public char charAt(int i) {
        return chars.charAt(i);
    }

    public Style styleAt(int i) {
        if (styles.isEmpty()) {
            return null;
        }
        return styles.get(Math.max(0, Math.min(styles.size() - 1, i)));
    }

    /** The style typing at a position takes: the one of the character before it. */
    public Style styleForInsert(int pos, Style fallback) {
        if (pos > 0 && pos - 1 < styles.size() && chars.charAt(pos - 1) != '\n') {
            return styles.get(pos - 1);
        }
        if (pos < styles.size()) {
            return styles.get(pos);
        }
        if (pos > 0 && pos - 1 < styles.size()) {
            return styles.get(pos - 1);
        }
        return fallback;
    }

    public int paragraphOf(int pos) {
        int p = 0;
        for (int i = 0; i < pos && i < chars.length(); i++) {
            if (chars.charAt(i) == '\n') {
                p++;
            }
        }
        return p;
    }

    public Para para(int index) {
        return paras.get(Math.max(0, Math.min(paras.size() - 1, index)));
    }

    public int paragraphCount() {
        return paras.size();
    }

    public void insert(int pos, String s, Style st) {
        pos = Math.max(0, Math.min(pos, chars.length()));
        int para = paragraphOf(pos);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                continue;
            }
            chars.insert(pos, c);
            styles.add(pos, st);
            if (c == '\n') {
                paras.add(para + 1, para(para).copy());
                para++;
            }
            pos++;
        }
    }

    public void delete(int from, int to) {
        from = Math.max(0, from);
        to = Math.min(chars.length(), to);
        if (to <= from) {
            return;
        }
        int para = paragraphOf(from);
        for (int i = to - 1; i >= from; i--) {
            if (chars.charAt(i) == '\n' && para + 1 < paras.size()) {
                paras.remove(para + 1);
            }
            chars.deleteCharAt(i);
            styles.remove(i);
        }
    }

    public void restyle(int from, int to, UnaryOperator<Style> f) {
        for (int i = Math.max(0, from); i < Math.min(to, styles.size()); i++) {
            styles.set(i, f.apply(styles.get(i)));
        }
    }

    /** Applies a paragraph change to every paragraph a range touches. */
    public void restylePara(int from, int to, java.util.function.Consumer<Para> f) {
        int a = paragraphOf(from);
        int b = paragraphOf(Math.max(from, to));
        for (int p = a; p <= b && p < paras.size(); p++) {
            f.accept(paras.get(p));
        }
    }

    public RichText copy() {
        RichText r = new RichText();
        r.chars.append(chars);
        r.styles.addAll(styles);
        r.paras.clear();
        for (Para p : paras) {
            r.paras.add(p.copy());
        }
        return r;
    }

    /* ------------------------------------------------------------- loading a paragraph */

    /**
     * The text of a block of the page, each glyph keeping its style; line
     * ends become spaces (the paragraph reflows), hyphens at line ends go.
     * Sizes are in display points (the page's own scale).
     */
    public static RichText of(TextBlock block) {
        RichText r = new RichText();
        Para p = r.paras.get(0);
        p.align = switch (block.align) {
            case CENTER -> Align.CENTER;
            case RIGHT -> Align.RIGHT;
            case JUSTIFY -> Align.JUSTIFY;
            default -> Align.LEFT;
        };
        p.firstIndent = (float) Math.max(0, block.firstIndent);
        double size = block.size();
        p.lineHeight = block.lineSpacing > 0 ? (float) (block.lineSpacing / size) : 1.2f;
        Style last = null;
        for (int li = 0; li < block.lines.size(); li++) {
            TextLine line = block.lines.get(li);
            if (li > 0 && last != null && hardBreak(block, block.lines.get(li - 1), line)) {
                // the line ended by choice (a list, a legend, an address): it stays a line of its own
                while (r.chars.length() > 0 && r.chars.charAt(r.chars.length() - 1) == ' ') {
                    r.chars.deleteCharAt(r.chars.length() - 1);
                    r.styles.remove(r.styles.size() - 1);
                }
                r.chars.append('\n');
                r.styles.add(last);
                Para np = r.paras.get(r.paras.size() - 1).copy();
                np.firstIndent = 0;
                r.paras.add(np);
            } else if (li > 0 && last != null) {
                // a word broken by a hyphen at the line end is joined again
                int n = r.chars.length();
                TextGlyph first = line.glyphs.isEmpty() ? null : line.first();
                if (n > 1 && r.chars.charAt(n - 1) == '-' && Character.isLetter(r.chars.charAt(n - 2)) && first != null
                    && !first.text.isEmpty() && Character.isLowerCase(first.text.charAt(0))) {
                    r.chars.deleteCharAt(n - 1);
                    r.styles.remove(n - 1);
                } else if (n > 0 && r.chars.charAt(n - 1) != ' ') {
                    r.chars.append(' ');
                    r.styles.add(last);
                }
            }
            for (int i = 0; i < line.glyphs.size(); i++) {
                TextGlyph g = line.glyphs.get(i);
                Style st = Style.of(g, g.size);
                // a raised or lowered glyph (superscript, subscript) keeps its offset
                double base = line.v;
                double gv = -g.x * line.dy + g.y * line.dx;
                float rise = (float) (base - gv);
                if (Math.abs(rise) > 0.15 * g.size) {
                    st = st.rise(rise);
                }
                if (line.spaceBefore.get(i) && last != null) {
                    r.chars.append(' ');
                    r.styles.add(last);
                }
                String t = g.text;
                if (t.equals("�") || t.isEmpty()) {
                    // a glyph with no known meaning travels as itself (its font and code)
                    t = new String(Character.toChars(RawGlyphs.of(g.font, g.code, g.codeLength)));
                }
                // ligatures become their letters, so they can be edited one by one
                t = switch (t) {
                    case "ﬀ" -> "ff";
                    case "ﬁ" -> "fi";
                    case "ﬂ" -> "fl";
                    case "ﬃ" -> "ffi";
                    case "ﬄ" -> "ffl";
                    default -> t;
                };
                for (int k = 0; k < t.length(); k++) {
                    char c = t.charAt(k);
                    if (c == '\n' || c == '\r') {
                        c = ' ';
                    }
                    r.chars.append(c);
                    r.styles.add(st);
                }
                last = st;
            }
        }
        // trailing spaces add nothing
        while (r.chars.length() > 0 && r.chars.charAt(r.chars.length() - 1) == ' ') {
            r.chars.deleteCharAt(r.chars.length() - 1);
            r.styles.remove(r.styles.size() - 1);
        }
        return r;
    }

    /**
     * Whether a line was ended by hand rather than by wrapping: the first
     * word of the next line would have fitted at its end. Justified text
     * always wraps; a list, a legend, an address or a title broken on purpose
     * keeps its breaks when edited.
     */
    static boolean hardBreak(TextBlock block, TextLine prev, TextLine next) {
        if (block.align == TextBlock.Align.JUSTIFY || next.glyphs.isEmpty()) {
            return false;
        }
        double width = block.u1() - block.u0();
        double used;
        if (block.align == TextBlock.Align.CENTER || block.align == TextBlock.Align.RIGHT) {
            used = prev.u1 - prev.u0;
        } else {
            used = prev.u1 - block.u0();
        }
        double word = 0;
        for (int i = 0; i < next.glyphs.size(); i++) {
            TextGlyph g = next.glyphs.get(i);
            if (i > 0 && (next.spaceBefore.get(i) || g.isSpace())) {
                break;
            }
            word += g.advance();
        }
        double space = Math.max(0.25 * prev.size, 1);
        return used + space + word < width - 0.5 * prev.size;
    }
}
