package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.ByteBuf;
import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.cos.PdfString;
import com.sphere.components.spdf.font.SystemFonts;
import com.sphere.components.spdf.font.TrueTypeProgram;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Embeds installed TrueType fonts for new text: each one becomes a Type 0
 * font (Identity-H, CIDs equal to glyph ids) whose program is a subset of the
 * glyphs used so far, with its widths and a ToUnicode map so the text stays
 * searchable and copyable everywhere. The subset grows as edits use more
 * glyphs; {@link #sync} rewrites the objects after each commit.
 */
public final class FontEmbedder {

    private final PdfDocument doc;
    private final Map<String, Embedded> fonts = new HashMap<>();
    private int tagCounter;

    public FontEmbedder(PdfDocument doc) {
        this.doc = doc;
    }

    /** The embedded font for an installed face (created on first use). */
    public Embedded get(SystemFonts.Face face) {
        String k = face.file() + "#" + face.index();
        return fonts.computeIfAbsent(k, x -> new Embedded(face, SystemFonts.program(face), nextTag()));
    }

    private String nextTag() {
        int n = tagCounter++;
        long t = System.nanoTime() / 1000 % 100000;
        char[] c = new char[6];
        long v = n * 100003L + t;
        for (int i = 0; i < 6; i++) {
            c[i] = (char) ('A' + (v % 26));
            v /= 26;
        }
        return new String(c);
    }

    /** Rewrites every embedded font whose glyph set grew. */
    public void sync(EditHistory h) {
        for (Embedded e : fonts.values()) {
            if (e.dirty && e.type0 != null) {
                e.write(h);
            }
        }
    }

    public final class Embedded implements OutputFont {
        final SystemFonts.Face face;
        final TrueTypeProgram tt;
        final String baseName;
        final TreeSet<Integer> used = new TreeSet<>();
        final Map<Integer, Integer> gidToUni = new HashMap<>();
        PdfRef type0;
        PdfRef cidFont;
        PdfRef descriptor;
        PdfRef file;
        PdfRef toUnicode;
        boolean dirty;
        private final Map<Integer, Shape> outlines = new HashMap<>();

        Embedded(SystemFonts.Face face, TrueTypeProgram tt, String tag) {
            this.face = face;
            this.tt = tt;
            String ps = tt.postscriptName.isEmpty() ? face.family().replace(" ", "") : tt.postscriptName;
            this.baseName = tag + "+" + ps.replaceAll("[^A-Za-z0-9\\-]", "");
        }

        @Override
        public byte[] encode(int cp) {
            int g = tt.gidForUnicode(cp);
            if (g <= 0) {
                return null;
            }
            gidToUni.putIfAbsent(g, cp);
            return new byte[] { (byte) (g >> 8), (byte) g };
        }

        private int gid(byte[] code) {
            return ((code[0] & 0xff) << 8) | (code[1] & 0xff);
        }

        @Override
        public float width(byte[] code) {
            return tt.advance(gid(code)) / (float) tt.unitsPerEm;
        }

        @Override
        public synchronized Shape glyph(byte[] code) {
            int g = gid(code);
            return outlines.computeIfAbsent(g, x -> {
                double k = 1.0 / tt.unitsPerEm;
                return tt.glyph(g).createTransformedShape(AffineTransform.getScaleInstance(k, k));
            });
        }

        @Override
        public float ascent() {
            return tt.ascender / (float) tt.unitsPerEm;
        }

        @Override
        public float descent() {
            return tt.descender / (float) tt.unitsPerEm;
        }

        @Override
        public void used(byte[] code) {
            if (used.add(gid(code))) {
                dirty = true;
            }
        }

        @Override
        public String key() {
            return "sys:" + face.file() + "#" + face.index();
        }

        @Override
        public String displayName() {
            return face.family() + (face.bold() ? " Bold" : "") + (face.italic() ? " Italic" : "");
        }

        @Override
        public boolean singleByte() {
            return false;
        }

        @Override
        public PdfObject resource(EditHistory h) {
            if (type0 == null || !doc.exists(type0.num)) {
                type0 = h.add(new PdfDict());
                cidFont = h.add(new PdfDict());
                descriptor = h.add(new PdfDict());
                file = h.add(PdfStream.of(new byte[0], false));
                toUnicode = h.add(PdfStream.of(new byte[0], false));
                dirty = true;
            }
            return type0;
        }

        /** Writes the five objects of the font for the glyphs used so far. */
        void write(EditHistory h) {
            h.touch(type0);
            h.touch(cidFont);
            h.touch(descriptor);
            h.touch(file);
            h.touch(toUnicode);
            PdfDict t0 = new PdfDict();
            t0.put("Type", "Font");
            t0.put("Subtype", "Type0");
            t0.put("BaseFont", baseName);
            t0.put("Encoding", "Identity-H");
            t0.put("DescendantFonts", PdfArray.of(cidFont));
            t0.put("ToUnicode", toUnicode);
            doc.putObject(type0.num, t0);

            PdfDict cid = new PdfDict();
            cid.put("Type", "Font");
            cid.put("Subtype", "CIDFontType2");
            cid.put("BaseFont", baseName);
            PdfDict info = new PdfDict();
            info.put("Registry", new PdfString("Adobe".getBytes()));
            info.put("Ordering", new PdfString("Identity".getBytes()));
            info.put("Supplement", 0);
            cid.put("CIDSystemInfo", info);
            cid.put("FontDescriptor", descriptor);
            cid.put("DW", 1000);
            cid.put("W", widths());
            cid.put("CIDToGIDMap", "Identity");
            doc.putObject(cidFont.num, cid);

            double k = 1000.0 / tt.unitsPerEm;
            PdfDict fd = new PdfDict();
            fd.put("Type", "FontDescriptor");
            fd.put("FontName", baseName);
            fd.put("FontFamily", new PdfString(face.family().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
            int flags = 32 | (face.italic() ? 64 : 0);
            if (face.family().toLowerCase().contains("courier") || face.family().toLowerCase().contains("mono")
                || face.family().toLowerCase().contains("consol")) {
                flags |= 1;
            }
            fd.put("Flags", flags);
            fd.put("FontBBox", PdfArray.ofNumbers(Math.round(tt.bbox[0] * k), Math.round(tt.bbox[1] * k),
                                                 Math.round(tt.bbox[2] * k), Math.round(tt.bbox[3] * k)));
            fd.put("ItalicAngle", tt.italicAngle);
            fd.put("Ascent", Math.round(tt.ascender * k));
            fd.put("Descent", Math.round(tt.descender * k));
            fd.put("CapHeight", Math.round(tt.capHeight * k));
            fd.put("StemV", face.bold() ? 140 : 80);
            fd.put("FontFile2", file);
            doc.putObject(descriptor.num, fd);

            PdfStream program = PdfStream.of(tt.subset(used), true);
            doc.putObject(file.num, program);
            PdfStream tu = PdfStream.of(toUnicodeCMap(), true);
            doc.putObject(toUnicode.num, tu);
            dirty = false;
        }

        private PdfArray widths() {
            PdfArray w = new PdfArray();
            Integer start = null;
            PdfArray run = null;
            int prev = -2;
            for (int g : used) {
                if (g != prev + 1 || run == null) {
                    if (run != null) {
                        w.add(new PdfNumber(start));
                        w.add(run);
                    }
                    start = g;
                    run = new PdfArray();
                }
                run.add(new PdfNumber(Math.round(tt.advance(g) * 1000.0 / tt.unitsPerEm)));
                prev = g;
            }
            if (run != null) {
                w.add(new PdfNumber(start));
                w.add(run);
            }
            return w;
        }

        private byte[] toUnicodeCMap() {
            ByteBuf b = new ByteBuf();
            b.put("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n");
            b.put("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
            b.put("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n");
            b.put("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
            java.util.List<int[]> pairs = new java.util.ArrayList<>();
            for (int g : used) {
                Integer u = gidToUni.get(g);
                if (u != null) {
                    pairs.add(new int[] { g, u });
                }
            }
            for (int i = 0; i < pairs.size(); i += 100) {
                int n = Math.min(100, pairs.size() - i);
                b.put(n + " beginbfchar\n");
                for (int k = 0; k < n; k++) {
                    int[] p = pairs.get(i + k);
                    b.put(String.format("<%04X> <", p[0]));
                    for (char c : Character.toChars(p[1])) {
                        b.put(String.format("%04X", (int) c));
                    }
                    b.put(">\n");
                }
                b.put("endbfchar\n");
            }
            b.put("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            return b.toBytes();
        }
    }

    static boolean isName(PdfObject o) {
        return o instanceof PdfName;
    }
}
