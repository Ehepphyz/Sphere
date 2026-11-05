package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.font.PdfFont;
import com.sphere.components.spdf.font.SystemFonts;
import com.sphere.components.spdf.render.RenderCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks, character by character, the font new text is written in:
 * <ol>
 * <li>the original font of the text being edited, when it holds the glyph;</li>
 * <li>another font of the page from the same family that holds it (subsets
 * of one font often hold different letters: the glyph is "harvested");</li>
 * <li>the installed font of that family (or the closest one: serif, sans,
 * monospace), embedded as a subset;</li>
 * <li>a fallback chain of fonts covering symbols, Greek, CJK...</li>
 * </ol>
 * So fixing a typo keeps the document's own font, and any character typed,
 * in any language, still shows.
 */
public final class FontResolver {

    private static final String[] FALLBACKS = { "Arial", "Segoe UI", "Times New Roman", "Cambria Math",
        "Segoe UI Symbol", "Arial Unicode MS", "Microsoft YaHei", "MS Gothic", "Malgun Gothic", "Nirmala UI",
        "Ebrima", "DejaVu Sans", "Noto Sans", "Liberation Sans" };

    private final RenderCache cache;
    private final FontEmbedder embedder;
    private final Map<PdfFont, ExistingFontOut> existing = new IdentityHashMap<>();
    private final List<PdfFont> pageFonts = new ArrayList<>();
    private final Map<PdfFont, PdfObject> refs = new IdentityHashMap<>();
    private final Map<String, OutputFont> chosen = new HashMap<>();

    public FontResolver(RenderCache cache, FontEmbedder embedder, PdfPage page) {
        this.cache = cache;
        this.embedder = embedder;
        collect(page.resources(), 0);
    }

    /** The fonts of the page and of its forms, with the references naming them. */
    private void collect(PdfDict res, int depth) {
        if (res == null || depth > 4) {
            return;
        }
        PdfDict fonts = res.getDict("Font");
        if (fonts != null) {
            for (PdfName k : fonts.keys()) {
                PdfObject raw = fonts.getRaw(k);
                if (PdfRef.deref(raw) instanceof PdfDict fd) {
                    PdfFont f = cache.font(fd);
                    if (!refs.containsKey(f)) {
                        refs.put(f, raw);
                        pageFonts.add(f);
                    }
                }
            }
        }
        PdfDict xo = res.getDict("XObject");
        if (xo != null) {
            for (PdfName k : xo.keys()) {
                if (xo.get(k) instanceof com.sphere.components.spdf.cos.PdfStream s && "Form".equals(s.getName("Subtype"))) {
                    collect(s.getDict("Resources"), depth + 1);
                }
            }
        }
    }

    /** The output font for one of the document's fonts. */
    public ExistingFontOut existing(PdfFont f) {
        return existing.computeIfAbsent(f, x -> {
            PdfObject ref = refs.get(f);
            return new ExistingFontOut(f, ref != null ? ref : f.dict);
        });
    }

    public FontEmbedder embedder() {
        return embedder;
    }

    /** The font a character of a style is written with, and its code; null when no font has it. */
    public Choice choose(RichText.Style st, int cp) {
        RawGlyphs.Raw raw = RawGlyphs.get(cp);
        if (raw != null) {
            // a glyph kept as its font and code: written back as it was
            return new Choice(existing(raw.font()), RawGlyphs.bytes(raw));
        }
        if (st.original != null) {
            ExistingFontOut ex = existing(st.original);
            byte[] code = ex.encode(cp);
            if (code != null) {
                return new Choice(ex, code);
            }
            String fam = norm(st.original);
            for (PdfFont f : pageFonts) {
                if (f != st.original && norm(f).equals(fam) && f.bold == st.original.bold && f.italic == st.original.italic) {
                    ExistingFontOut o = existing(f);
                    byte[] c2 = o.encode(cp);
                    if (c2 != null) {
                        return new Choice(o, c2);
                    }
                }
            }
        }
        if (st.original == null) {
            // a font of the page in the family and style asked for: no need to embed anything
            for (PdfFont f : pageFonts) {
                if (!f.isType3() && f.family.equalsIgnoreCase(st.family) && f.bold == st.bold && f.italic == st.italic) {
                    ExistingFontOut o = existing(f);
                    byte[] code = o.encode(cp);
                    if (code != null) {
                        return new Choice(o, code);
                    }
                }
            }
        }
        String key = st.family + "|" + st.bold + "|" + st.italic;
        OutputFont main = chosen.get(key);
        if (main == null && !chosen.containsKey(key)) {
            main = system(st.family, st.bold, st.italic, st.original);
            chosen.put(key, main);
        }
        if (main != null) {
            byte[] code = main.encode(cp);
            if (code != null) {
                return new Choice(main, code);
            }
        }
        for (String fam : FALLBACKS) {
            SystemFonts.Face face = SystemFonts.face(fam, st.bold, st.italic);
            if (face == null) {
                continue;
            }
            OutputFont f = embedder.get(face);
            byte[] code = f.encode(cp);
            if (code != null) {
                return new Choice(f, code);
            }
        }
        return null;
    }

    /** The installed font for a family, or the closest by kind. */
    private OutputFont system(String family, boolean bold, boolean italic, PdfFont original) {
        SystemFonts.Face face = family == null ? null : SystemFonts.face(family, bold, italic);
        if (face == null) {
            String generic;
            String f = (family == null ? "" : family).toLowerCase(Locale.ROOT);
            boolean mono = (original != null && original.fixedPitch) || f.contains("mono") || f.contains("courier")
                || f.contains("consol") || f.contains("cmtt");
            boolean serif = (original != null && original.serif) || f.contains("times") || f.contains("serif")
                && !f.contains("sans") || f.contains("roman") || f.startsWith("cm") || f.contains("georgia")
                || f.contains("garamond") || f.contains("palat") || f.contains("minion") || f.contains("nimbus rom")
                || f.contains("lmroman") || f.contains("latin modern roman");
            generic = mono ? "Courier New" : serif ? "Times New Roman" : "Arial";
            face = SystemFonts.face(generic, bold, italic);
        }
        return face == null ? null : embedder.get(face);
    }

    private static String norm(PdfFont f) {
        String b = f.baseFont;
        if (f.subset) {
            b = b.substring(7);
        }
        return b.toLowerCase(Locale.ROOT);
    }

    public record Choice(OutputFont font, byte[] code) {
    }
}
