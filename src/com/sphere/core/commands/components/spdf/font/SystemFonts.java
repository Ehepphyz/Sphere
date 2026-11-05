package com.sphere.components.spdf.font;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The fonts of this machine, for the two things spdf needs them for: drawing
 * a font a PDF names but does not embed (by the closest installed family,
 * the standard 14 by their metric-compatible twins: Arial, Times New Roman,
 * Courier New), and embedding a font chosen while editing, for which the
 * TrueType files are indexed by family and style.
 */
public final class SystemFonts {

    private SystemFonts() {
    }

    public static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    /** One face of an installed TrueType font file. */
    public record Face(String family, String style, boolean bold, boolean italic, Path file, int index) {
    }

    private static volatile Map<String, String> installed;
    private static volatile Map<String, List<Face>> faces;
    private static final Map<String, TrueTypeProgram> programs = new ConcurrentHashMap<>();
    private static final Map<String, Font> javaFonts = new ConcurrentHashMap<>();

    /* ------------------------------------------------------------- family names */

    private static String key(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Installed family names by their normalized key. */
    private static Map<String, String> installed() {
        Map<String, String> m = installed;
        if (m == null) {
            synchronized (SystemFonts.class) {
                m = installed;
                if (m == null) {
                    m = new HashMap<>();
                    try {
                        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment()
                                                           .getAvailableFontFamilyNames(Locale.ROOT)) {
                            m.putIfAbsent(key(f), f);
                        }
                    } catch (Throwable e) {
                        // headless without fonts: the logical families remain
                    }
                    installed = m;
                }
            }
        }
        return m;
    }

    private static final String[] SUFFIXES = { "psmt", "mt", "ps", "std", "pro", "lt", "com", "regular", "roman",
        "book", "medium", "light", "semibold", "bold", "italic", "oblique", "it", "bd", "bi" };

    /** The installed family a PDF base font name stands for, or null. */
    public static String installedFamily(String baseName) {
        String bare = baseName;
        if (bare.length() > 7 && bare.charAt(6) == '+') {
            bare = bare.substring(7);
        }
        String std = standardFamily(bare);
        if (std != null) {
            return installed().containsKey(key(std)) ? std : null;
        }
        String head = bare.split("[-,]")[0];
        Map<String, String> inst = installed();
        String k = key(head);
        for (int round = 0; round < 4 && !k.isEmpty(); round++) {
            String f = inst.get(k);
            if (f != null) {
                return f;
            }
            boolean cut = false;
            for (String s : SUFFIXES) {
                if (k.endsWith(s) && k.length() > s.length() + 2) {
                    k = k.substring(0, k.length() - s.length());
                    cut = true;
                    break;
                }
            }
            if (!cut) {
                break;
            }
        }
        // the longest installed family the name starts with
        String best = null;
        String full = key(bare);
        for (Map.Entry<String, String> e : inst.entrySet()) {
            if (e.getKey().length() >= 4 && full.startsWith(e.getKey())
                && (best == null || e.getKey().length() > key(best).length())) {
                best = e.getValue();
            }
        }
        return best;
    }

    /** The family of a standard 14 name (or a common alias of it), or null. */
    public static String standardFamily(String bare) {
        String l = bare.toLowerCase(Locale.ROOT);
        if (l.startsWith("helvetica") || l.startsWith("arial")) {
            return "Arial";
        }
        if (l.startsWith("times") || l.equals("timesnewroman")) {
            return "Times New Roman";
        }
        if (l.startsWith("courier")) {
            return "Courier New";
        }
        return null;
    }

    /** A readable family name for a base font name: the installed one, or the name split at its capitals. */
    public static String familyOf(String bare) {
        String inst = installedFamily(bare);
        if (inst != null) {
            return inst;
        }
        String head = bare.split("[-,+]")[0];
        if (head.isEmpty()) {
            return bare;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(head.charAt(i - 1))) {
                b.append(' ');
            }
            b.append(c);
        }
        String s = b.toString();
        for (String suf : new String[] { " PSMT", " MT", " PS" }) {
            if (s.endsWith(suf)) {
                s = s.substring(0, s.length() - suf.length());
            }
        }
        return s;
    }

    /* ------------------------------------------------------------- Java fonts for drawing */

    /** The Java font standing in for a PDF font that is not embedded. */
    public static Font javaFont(String baseName, boolean bold, boolean italic, boolean serif, boolean fixed) {
        String fam = installedFamily(baseName);
        if (fam == null) {
            String l = baseName.toLowerCase(Locale.ROOT);
            if (fixed || l.contains("mono") || l.contains("courier") || l.contains("consol") || l.contains("code")) {
                fam = installed().containsKey("couriernew") ? "Courier New" : Font.MONOSPACED;
            } else if (l.contains("symbol")) {
                fam = installed().containsKey("timesnewroman") ? "Times New Roman" : Font.SERIF;
            } else if (l.contains("dingbat") || l.contains("wingding")) {
                fam = installed().containsKey("segoeuisymbol") ? "Segoe UI Symbol" : Font.DIALOG;
            } else if (serif || l.contains("serif") && !l.contains("sans") || l.contains("roman") || l.contains("cmr")
                       || l.contains("georgia") || l.contains("garamond") || l.contains("book") || l.contains("minion")
                       || l.contains("palat") || l.contains("cambria")) {
                fam = installed().containsKey("timesnewroman") ? "Times New Roman" : Font.SERIF;
            } else {
                fam = installed().containsKey("arial") ? "Arial" : Font.SANS_SERIF;
            }
        }
        int style = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
        String k = fam + "|" + style;
        final String family = fam;
        return javaFonts.computeIfAbsent(k, x -> new Font(family, style, 1000));
    }

    private static Font fallback;

    /** The outline of a character in a Java font, in text space (em = 1, y up), with its advance. */
    public static Shape outline(Font f, String text, float[] advance) {
        Font use = f;
        if (!canDisplay(f, text)) {
            if (fallback == null) {
                Map<String, String> inst = installed();
                String name = inst.containsKey("segoeuisymbol") ? "Segoe UI Symbol"
                    : inst.containsKey("arialunicodems") ? "Arial Unicode MS" : Font.DIALOG;
                fallback = new Font(name, Font.PLAIN, 1000);
            }
            if (canDisplay(fallback, text)) {
                use = fallback.deriveFont(f.getStyle());
            }
        }
        GlyphVector gv = use.createGlyphVector(FRC, text);
        Shape s = gv.getOutline();
        if (advance != null) {
            advance[0] = (float) (gv.getGlyphPosition(gv.getNumGlyphs()).getX() / use.getSize2D());
        }
        double k = 1.0 / use.getSize2D();
        return AffineTransform.getScaleInstance(k, -k).createTransformedShape(s);
    }

    private static boolean canDisplay(Font f, String text) {
        return f.canDisplayUpTo(text) < 0;
    }

    /* ------------------------------------------------------------- TrueType files for embedding */

    private static List<Path> fontDirs() {
        List<Path> dirs = new ArrayList<>();
        String win = System.getenv("WINDIR");
        if (win != null) {
            dirs.add(Path.of(win, "Fonts"));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            dirs.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
        }
        String home = System.getProperty("user.home");
        dirs.add(Path.of("/usr/share/fonts"));
        dirs.add(Path.of("/usr/local/share/fonts"));
        dirs.add(Path.of("/Library/Fonts"));
        dirs.add(Path.of("/System/Library/Fonts"));
        if (home != null) {
            dirs.add(Path.of(home, ".fonts"));
            dirs.add(Path.of(home, ".local", "share", "fonts"));
            dirs.add(Path.of(home, "Library", "Fonts"));
        }
        return dirs;
    }

    /** Every TrueType face installed, by family (scanned once, a few milliseconds per file). */
    public static Map<String, List<Face>> faces() {
        Map<String, List<Face>> m = faces;
        if (m == null) {
            synchronized (SystemFonts.class) {
                m = faces;
                if (m == null) {
                    m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    for (Path dir : fontDirs()) {
                        if (!Files.isDirectory(dir)) {
                            continue;
                        }
                        try (Stream<Path> s = Files.walk(dir, 3)) {
                            for (Path p : (Iterable<Path>) s::iterator) {
                                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                if (n.endsWith(".ttf") || n.endsWith(".ttc")) {
                                    for (Face f : readFaces(p)) {
                                        m.computeIfAbsent(f.family, x -> new ArrayList<>()).add(f);
                                    }
                                }
                            }
                        } catch (IOException | RuntimeException e) {
                            // an unreadable folder adds nothing
                        }
                    }
                    faces = m;
                }
            }
        }
        return m;
    }

    /** The families that can be embedded, sorted. */
    public static List<String> embeddableFamilies() {
        List<String> out = new ArrayList<>(faces().keySet());
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The face of a family closest to a style, or null when the family is not installed as TrueType. */
    public static Face face(String family, boolean bold, boolean italic) {
        List<Face> list = faces().get(family);
        if (list == null || list.isEmpty()) {
            String inst = installedFamily(family);
            list = inst == null ? null : faces().get(inst);
            if (list == null || list.isEmpty()) {
                return null;
            }
        }
        Face best = null;
        int bestScore = -1;
        for (Face f : list) {
            int score = (f.bold == bold ? 2 : 0) + (f.italic == italic ? 2 : 0);
            String st = f.style.toLowerCase(Locale.ROOT);
            if (!bold && !italic && (st.equals("regular") || st.equals("normal") || st.equals("roman"))) {
                score++;
            }
            if (st.contains("light") || st.contains("condensed") || st.contains("narrow") || st.contains("semi")
                || st.contains("black")) {
                score--;
            }
            if (score > bestScore) {
                best = f;
                bestScore = score;
            }
        }
        return best;
    }

    /** The parsed program of a face, kept once read. */
    public static TrueTypeProgram program(Face f) {
        String k = f.file + "#" + f.index;
        return programs.computeIfAbsent(k, x -> {
            try {
                return TrueTypeProgram.parse(Files.readAllBytes(f.file), f.index);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static List<Face> readFaces(Path p) {
        List<Face> out = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            byte[] h = new byte[12];
            f.readFully(h);
            List<Long> starts = new ArrayList<>();
            if (h[0] == 't' && h[1] == 't' && h[2] == 'c' && h[3] == 'f') {
                int n = (int) be(h, 8, 4);
                byte[] offs = new byte[4 * Math.min(n, 64)];
                f.seek(12);
                f.readFully(offs);
                for (int i = 0; i < offs.length / 4; i++) {
                    starts.add(be(offs, 4 * i, 4));
                }
            } else {
                starts.add(0L);
            }
            for (int idx = 0; idx < starts.size(); idx++) {
                Face face = readFace(f, starts.get(idx), p, idx);
                if (face != null) {
                    out.add(face);
                }
            }
        } catch (IOException | RuntimeException e) {
            // not a font this reader understands
        }
        return out;
    }

    private static Face readFace(RandomAccessFile f, long base, Path p, int index) throws IOException {
        byte[] h = new byte[12];
        f.seek(base);
        f.readFully(h);
        int num = (int) be(h, 4, 2);
        if (num <= 0 || num > 200) {
            return null;
        }
        byte[] dir = new byte[16 * num];
        f.readFully(dir);
        Map<String, long[]> t = new LinkedHashMap<>();
        for (int i = 0; i < num; i++) {
            String tag = new String(dir, 16 * i, 4, StandardCharsets.ISO_8859_1);
            t.put(tag, new long[] { be(dir, 16 * i + 8, 4), be(dir, 16 * i + 12, 4) });
        }
        if (!t.containsKey("glyf") || !t.containsKey("name")) {
            return null;
        }
        long[] nm = t.get("name");
        byte[] name = new byte[(int) Math.min(nm[1], 1 << 20)];
        f.seek(nm[0]);
        f.readFully(name);
        String family = null;
        String typoFamily = null;
        String style = null;
        String typoStyle = null;
        int count = (int) be(name, 2, 2);
        int strings = (int) be(name, 4, 2);
        for (int i = 0; i < count; i++) {
            int r = 6 + 12 * i;
            if (r + 12 > name.length) {
                break;
            }
            int platform = (int) be(name, r, 2);
            int lang = (int) be(name, r + 4, 2);
            int id = (int) be(name, r + 6, 2);
            int len = (int) be(name, r + 8, 2);
            int off = strings + (int) be(name, r + 10, 2);
            if (platform != 3 || (lang != 0x409 && lang != 0) || off + len > name.length) {
                continue;
            }
            String s = new String(name, off, len, StandardCharsets.UTF_16BE);
            switch (id) {
                case 1 -> family = s;
                case 2 -> style = s;
                case 16 -> typoFamily = s;
                case 17 -> typoStyle = s;
                default -> { }
            }
        }
        if (typoFamily != null) {
            family = typoFamily;
            if (typoStyle != null) {
                style = typoStyle;
            }
        }
        if (family == null) {
            return null;
        }
        if (style == null) {
            style = "Regular";
        }
        boolean bold = false;
        boolean italic = false;
        long[] os2 = t.get("OS/2");
        if (os2 != null && os2[1] >= 64) {
            byte[] o = new byte[64];
            f.seek(os2[0]);
            f.readFully(o);
            int weight = (int) be(o, 4, 2);
            int fs = (int) be(o, 62, 2);
            bold = weight >= 600 || (fs & 32) != 0;
            italic = (fs & 1) != 0;
        }
        String sl = style.toLowerCase(Locale.ROOT);
        bold |= sl.contains("bold") || sl.contains("black") || sl.contains("heavy");
        italic |= sl.contains("italic") || sl.contains("oblique");
        return new Face(family, style, bold, italic, p, index);
    }

    private static long be(byte[] b, int p, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (b[p + i] & 0xff);
        }
        return v;
    }
}
