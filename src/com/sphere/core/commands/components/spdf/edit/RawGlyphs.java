package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.font.PdfFont;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Glyphs whose meaning the PDF does not say (no Unicode for them: a custom
 * symbol, a TeX glyph). The editor gives each one a private-use character
 * standing for "this code of that font", so editing the words around it
 * writes it back exactly as it was instead of losing it.
 */
public final class RawGlyphs {

    private RawGlyphs() {
    }

    public record Raw(PdfFont font, int code, int length) {
    }

    private static final int FIRST = 0xF0000;
    private static final int LAST = 0xFFFFD;
    private static final Map<Integer, Raw> BY_CP = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BY_KEY = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(FIRST);

    /** The private character standing for a code of a font. */
    public static int of(PdfFont font, int code, int length) {
        String k = System.identityHashCode(font) + ":" + code + ":" + length;
        return BY_KEY.computeIfAbsent(k, x -> {
            int cp = NEXT.getAndIncrement();
            if (cp > LAST) {
                NEXT.set(FIRST);
                cp = NEXT.getAndIncrement();
            }
            BY_CP.put(cp, new Raw(font, code, length));
            return cp;
        });
    }

    /** What a private character stands for, or null for any other character. */
    public static Raw get(int cp) {
        return cp >= FIRST && cp <= LAST ? BY_CP.get(cp) : null;
    }

    static byte[] bytes(Raw r) {
        byte[] b = new byte[Math.max(1, r.length())];
        for (int i = 0; i < b.length; i++) {
            b[b.length - 1 - i] = (byte) (r.code() >> (8 * i));
        }
        return b;
    }
}
