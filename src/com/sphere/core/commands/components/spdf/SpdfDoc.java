package com.sphere.components.spdf;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfString;
import com.sphere.components.spdf.cos.PdfWriter;
import com.sphere.components.spdf.edit.EditHistory;
import com.sphere.components.spdf.edit.FontEmbedder;
import com.sphere.components.spdf.render.PageImage;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.RenderCache;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.LayoutAnalyzer;
import com.sphere.components.spdf.text.PageText;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An open PDF as the editor holds it: the document, its edit history, the
 * render cache and renderer, the fonts embedded by edits, and the text of
 * each page (extracted when first needed, again after a page changes).
 */
public final class SpdfDoc {

    public Path file;
    public final PdfDocument pdf;
    public final EditHistory history;
    public RenderCache cache;
    public PageRenderer renderer;
    public final FontEmbedder embedder;
    /** Each page's text, kept while memory allows (a long document's glyphs weigh their weight). */
    private final Map<Integer, java.lang.ref.SoftReference<PageText>> texts = Collections.synchronizedMap(new HashMap<>());
    private final Map<Integer, Integer> textSig = Collections.synchronizedMap(new HashMap<>());
    private int globalRev;

    private SpdfDoc(Path file, PdfDocument pdf) {
        this.file = file;
        this.pdf = pdf;
        this.history = new EditHistory(pdf);
        this.cache = new RenderCache(pdf);
        this.renderer = new PageRenderer(cache);
        this.embedder = new FontEmbedder(pdf);
        history.addListener(this::changed);
    }

    public static SpdfDoc open(Path file, String password) throws IOException {
        return new SpdfDoc(file, PdfDocument.open(file, password));
    }

    public static SpdfDoc blank() {
        PdfDocument d = PdfDocument.create();
        SpdfDoc s = new SpdfDoc(null, d);
        com.sphere.components.spdf.edit.PageOps.insertBlank(new EditHistory(d), d, 0, 595.28, 841.89);
        return s;
    }

    private final Map<Integer, Integer> pageRev = Collections.synchronizedMap(new HashMap<>());

    private void changed() {
        EditHistory.Step s = history.last();
        if (s == null || s.pages.isEmpty() || s.label.startsWith("Replace")) {
            // pages moved, or forms rewritten in place: every page may look different
            globalRev++;
        } else {
            for (int p : s.pages) {
                pageRev.merge(p, 1, Integer::sum);
            }
        }
        // a form rewritten in place keeps its identity: its parsed content must go
        cache.clearForms();
    }

    public int pageCount() {
        return pdf.pageCount();
    }

    public PdfPage page(int i) {
        List<PdfPage> ps = pdf.pages();
        return ps.get(Math.max(0, Math.min(ps.size() - 1, i)));
    }

    /** A signature changing whenever the page's text may change (annotations do not count). */
    public int textSignature(int i) {
        PdfPage p = page(i);
        PdfDict d = p.dict();
        int h = p.objNum() * 31 + System.identityHashCode(d);
        h = h * 31 + System.identityHashCode(d.get("Contents"));
        h = h * 31 + System.identityHashCode(d.get("Resources"));
        h = h * 31 + p.rotation();
        return h * 31 + globalRev;
    }

    /** A signature changing whenever the page's look may change. */
    public int signature(int i) {
        PdfPage p = page(i);
        PdfDict d = p.dict();
        int h = p.objNum() * 31 + System.identityHashCode(d.getRaw("Contents"));
        h = h * 31 + System.identityHashCode(d.get("Contents"));
        PdfObject annots = d.getRaw("Annots");
        h = h * 31 + System.identityHashCode(annots);
        PdfArray a = d.getArray("Annots");
        if (a != null) {
            h = h * 31 + a.size();
            for (int k = 0; k < Math.min(a.size(), 64); k++) {
                h = h * 31 + System.identityHashCode(a.getRaw(k)) + System.identityHashCode(a.get(k));
            }
        }
        h = h * 31 + p.rotation();
        h = h * 31 + System.identityHashCode(d.getRaw("Resources"));
        h = h * 31 + System.identityHashCode(d);
        return ((h * 31 + globalRev) * 31 + pageRev.getOrDefault(i, 0)) * 31 + liveRev;
    }

    /** The page's text, extracted and laid out once per version of the page. */
    public PageText text(int i) {
        int sig = textSignature(i);
        Integer have = textSig.get(i);
        java.lang.ref.SoftReference<PageText> ref = texts.get(i);
        PageText t = ref == null ? null : ref.get();
        if (t != null && have != null && have == sig) {
            return t;
        }
        PdfPage p = page(i);
        PageRenderer.Options o = new PageRenderer.Options();
        o.paint = false;
        o.annotations = false;
        List<TextGlyph> gs = new ArrayList<>();
        List<PageImage> ims = new ArrayList<>();
        o.sink = gs::add;
        o.images = ims::add;
        try {
            renderer.render(null, p, null, o);
        } catch (RuntimeException e) {
            // the glyphs read before the error are kept
        }
        t = LayoutAnalyzer.analyze(gs, p.displayWidth());
        t.images = ims;
        texts.put(i, new java.lang.ref.SoftReference<>(t));
        textSig.put(i, sig);
        return t;
    }

    /** The pictures of a page, in drawing order (found with its text). */
    public List<PageImage> images(int i) {
        return text(i).images;
    }

    /** The pictures of a page if its text was extracted already, else none (no work done). */
    public List<PageImage> imagesIfReady(int i) {
        PageText t = i < 0 || i >= pageCount() ? null : textIfReady(i);
        return t == null ? List.of() : t.images;
    }

    /** The topmost picture under a point of a page (display space), or null. */
    public PageImage imageAt(int i, java.awt.geom.Point2D d) {
        List<PageImage> ims = images(i);
        for (int k = ims.size() - 1; k >= 0; k--) {
            PageImage im = ims.get(k);
            if (!im.stencil && im.contains(d)) {
                return im;
            }
        }
        return null;
    }

    /** Forgets one page's text and pictures (its pictures changed in place). */
    public void forgetText(int i) {
        texts.remove(i);
        textSig.remove(i);
    }

    /**
     * The pages whose resources reach an object (directly or through their
     * forms): where a picture shows, each as {page index, page object}.
     */
    public List<int[]> pagesUsing(int objNum) {
        List<int[]> out = new ArrayList<>();
        List<PdfPage> ps = pdf.pages();
        Map<PdfDict, Boolean> memo = new java.util.IdentityHashMap<>();
        for (int i = 0; i < ps.size(); i++) {
            if (reaches(ps.get(i).resources(), objNum, memo, 0)) {
                out.add(new int[] { i, ps.get(i).objNum() });
            }
        }
        return out;
    }

    private boolean reaches(PdfDict res, int objNum, Map<PdfDict, Boolean> memo, int depth) {
        PdfDict xo = res == null || depth > 6 ? null : res.getDict("XObject");
        if (xo == null) {
            return false;
        }
        Boolean known = memo.get(xo);
        if (known != null) {
            return known;
        }
        memo.put(xo, false);
        boolean found = false;
        for (PdfName k : xo.keys()) {
            PdfObject raw = xo.getRaw(k);
            if (raw instanceof PdfRef r && r.num == objNum) {
                found = true;
                break;
            }
            if (PdfRef.deref(raw) instanceof com.sphere.components.spdf.cos.PdfStream s && "Form".equals(s.getName("Subtype"))
                && reaches(s.getDict("Resources"), objNum, memo, depth + 1)) {
                found = true;
                break;
            }
        }
        memo.put(xo, found);
        return found;
    }

    /* ------------------------------------------------------------- page labels */

    private String[] labels;
    private int labelsAt = Integer.MIN_VALUE;

    /**
     * The pages' own numbers (12.4.2 page labels): what each page is called
     * by the document, "i", "ii", then "1", "2"... when the front matter is
     * numbered apart. Null when the document has none, or none that differ
     * from the pages' places.
     */
    public synchronized String[] pageLabels() {
        int at = pdf.modCount() * 31 + pageCount();
        if (at != labelsAt) {
            labelsAt = at;
            labels = readLabels();
        }
        return labels;
    }

    private String[] readLabels() {
        PdfDict cat = pdf.catalog();
        PdfDict tree = cat == null ? null : cat.getDict("PageLabels");
        if (tree == null) {
            return null;
        }
        java.util.TreeMap<Integer, PdfDict> ranges = new java.util.TreeMap<>();
        collectLabels(tree, ranges, 0);
        if (ranges.isEmpty()) {
            return null;
        }
        int n = pageCount();
        String[] out = new String[n];
        boolean differ = false;
        for (int i = 0; i < n; i++) {
            Map.Entry<Integer, PdfDict> e = ranges.floorEntry(i);
            if (e == null) {
                out[i] = String.valueOf(i + 1);
                continue;
            }
            PdfDict d = e.getValue();
            String prefix = d.getText("P");
            int number = d.getInt("St", 1) + i - e.getKey();
            out[i] = (prefix == null ? "" : prefix) + labelNumber(d.getName("S"), number);
            differ |= !out[i].equals(String.valueOf(i + 1));
        }
        return differ ? out : null;
    }

    /** The number tree's entries: page index, label dictionary. */
    private static void collectLabels(PdfDict node, java.util.TreeMap<Integer, PdfDict> out, int depth) {
        if (node == null || depth > 32) {
            return;
        }
        PdfArray nums = node.getArray("Nums");
        if (nums != null) {
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                if (nums.get(i) instanceof com.sphere.components.spdf.cos.PdfNumber k && nums.get(i + 1) instanceof PdfDict d) {
                    out.put(k.intValue(), d);
                }
            }
        }
        PdfArray kids = node.getArray("Kids");
        for (int i = 0; kids != null && i < kids.size(); i++) {
            collectLabels(kids.getDict(i), out, depth + 1);
        }
    }

    /** A label's number in its style: D decimal, R and r Roman, A and a letters; no style, no number. */
    static String labelNumber(String style, int n) {
        if (style == null) {
            return "";
        }
        return switch (style) {
            case "R" -> roman(n);
            case "r" -> roman(n).toLowerCase(java.util.Locale.ROOT);
            case "A" -> letters(n);
            case "a" -> letters(n).toLowerCase(java.util.Locale.ROOT);
            default -> String.valueOf(n);
        };
    }

    private static String roman(int n) {
        if (n <= 0 || n >= 4000) {
            return String.valueOf(n);
        }
        int[] v = { 1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1 };
        String[] s = { "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I" };
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                b.append(s[i]);
                n -= v[i];
            }
        }
        return b.toString();
    }

    /** A to Z, then AA to ZZ, then AAA... (the letter repeated, as the format says). */
    private static String letters(int n) {
        if (n <= 0) {
            return String.valueOf(n);
        }
        return String.valueOf((char) ('A' + (n - 1) % 26)).repeat((n - 1) / 26 + 1);
    }

    /** What a page is called: its own number (label) when the document numbers its pages its way, else its place. */
    public String pageName(int i) {
        String[] l = pageLabels();
        return l != null && i >= 0 && i < l.length && !l[i].isEmpty() ? l[i] : String.valueOf(i + 1);
    }

    /** True when the document numbers its pages otherwise than by their places in the file. */
    public boolean hasPageLabels() {
        return pageLabels() != null;
    }

    /**
     * The page a number or label designates, as the user reads it on the
     * pages: the page so labeled ("23", "iv", "A-3"); a plain number no page
     * carries falls back to the page at that place in the file. -1 when none.
     */
    public int pageNamed(String name) {
        String s = name == null ? "" : name.trim();
        String[] l = pageLabels();
        if (l != null) {
            for (int i = 0; i < l.length; i++) {
                if (l[i].equalsIgnoreCase(s)) {
                    return i;
                }
            }
        }
        if (s.matches("\\d{1,6}")) {
            int n = Integer.parseInt(s);
            return n >= 1 && n <= pageCount() ? n - 1 : -1;
        }
        return -1;
    }

    /** Bumped while a picture is edited live elsewhere: the pages showing it are drawn again. */
    private volatile int liveRev;

    public void bumpLive() {
        liveRev++;
    }

    /** The text if already extracted for the current version, else null (no work done). */
    public PageText textIfReady(int i) {
        Integer have = textSig.get(i);
        java.lang.ref.SoftReference<PageText> ref = texts.get(i);
        PageText t = ref == null ? null : ref.get();
        return t != null && have != null && have == textSignature(i) ? t : null;
    }

    public void forgetTexts() {
        texts.clear();
        textSig.clear();
    }

    public int revision() {
        return globalRev;
    }

    /** Thrown when another program holds the file open and locked, so it cannot be replaced. */
    public static final class LockedException extends IOException {
        public LockedException(Path file, Throwable cause) {
            super(file.getFileName() + " is open in another program, which keeps it locked", cause);
        }
    }

    /**
     * Saves to a file. The new PDF is read back first: a file that would not
     * open never replaces one that does. It then replaces the target through a
     * temporary file (atomically when the system allows), else by writing
     * into it; a target another program keeps locked raises LockedException
     * and stays as it was.
     */
    public void save(Path target, boolean compact) throws IOException {
        embedder.sync(history);
        byte[] b = PdfWriter.write(pdf, compact);
        PdfDocument check = PdfDocument.open(b, null);
        if (check.pageCount() != pdf.pageCount()) {
            throw new IOException("the written PDF did not read back (" + check.pageCount() + " of " + pdf.pageCount()
                                  + " pages); the file on disk was left as it was");
        }
        Path abs = target.toAbsolutePath();
        Path tmp = Files.createTempFile(abs.getParent(), abs.getFileName().toString(), ".spdf-tmp");
        try {
            Files.write(tmp, b);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                try {
                    Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException move) {
                    // a viewer may keep the file open yet let it be written into
                    try {
                        Files.write(abs, b, java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                                    java.nio.file.StandardOpenOption.WRITE);
                    } catch (IOException write) {
                        throw new LockedException(abs, write);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        file = abs;
        history.markSaved();
    }

    public String title() {
        PdfDict info = pdf.info();
        String t = info == null ? null : info.getText("Title");
        return t == null || t.isBlank() ? (file == null ? "Untitled.pdf" : file.getFileName().toString()) : t.trim();
    }

    /* ------------------------------------------------------------- outline and links */

    /** One bookmark: its title, level, and where it leads (page index and y in display space, or -1). */
    public record Bookmark(String title, int level, int page, double y) {
    }

    /** The document's bookmarks, flattened depth-first. */
    public List<Bookmark> bookmarks() {
        List<Bookmark> out = new ArrayList<>();
        PdfDict cat = pdf.catalog();
        PdfDict outlines = cat == null ? null : cat.getDict("Outlines");
        if (outlines == null) {
            return out;
        }
        Map<Integer, Integer> pageOf = pageIndexByObject();
        walk(outlines.getDict("First"), 0, out, pageOf, new HashSet<>());
        return out;
    }

    private void walk(PdfDict item, int level, List<Bookmark> out, Map<Integer, Integer> pageOf, Set<PdfDict> seen) {
        int guard = 0;
        while (item != null && seen.add(item) && guard++ < 10000 && level < 32) {
            String title = item.getText("Title");
            int[] page = { -1 };
            double[] y = { -1 };
            PdfObject dest = item.get("Dest");
            if (dest == com.sphere.components.spdf.cos.PdfNull.NULL) {
                PdfDict a = item.getDict("A");
                if (a != null && "GoTo".equals(a.getName("S"))) {
                    dest = a.get("D");
                }
            }
            resolveDest(dest, pageOf, page, y);
            out.add(new Bookmark(title == null ? "" : title.trim(), level, page[0], y[0]));
            walk(item.getDict("First"), level + 1, out, pageOf, seen);
            item = item.getDict("Next");
        }
    }

    public Map<Integer, Integer> pageIndexByObject() {
        Map<Integer, Integer> m = new HashMap<>();
        List<PdfPage> ps = pdf.pages();
        for (int i = 0; i < ps.size(); i++) {
            m.put(ps.get(i).objNum(), i);
        }
        return m;
    }

    /** Resolves a destination (explicit array, or a name to look up) to a page and a y. */
    public void resolveDest(PdfObject dest, Map<Integer, Integer> pageOf, int[] page, double[] y) {
        dest = PdfRef.deref(dest);
        if (dest instanceof PdfString || dest instanceof PdfName) {
            String key = dest instanceof PdfString s ? s.latin1() : ((PdfName) dest).name;
            dest = namedDest(key);
            if (dest instanceof PdfDict dd) {
                dest = dd.get("D");
            }
        }
        if (!(dest instanceof PdfArray a) || a.size() == 0) {
            return;
        }
        PdfObject p = a.getRaw(0);
        if (p instanceof PdfRef r) {
            Integer idx = pageOf.get(r.num);
            if (idx != null) {
                page[0] = idx;
            }
        } else if (p instanceof com.sphere.components.spdf.cos.PdfNumber n) {
            page[0] = n.intValue();
        }
        if (page[0] >= 0 && "XYZ".equals(a.getName(1)) && a.get(3) instanceof com.sphere.components.spdf.cos.PdfNumber top) {
            PdfPage pg = page(page[0]);
            java.awt.geom.Point2D d = pg.userToDisplay().transform(
                new java.awt.geom.Point2D.Double(a.getDouble(2, 0), top.doubleValue()), null);
            y[0] = d.getY();
        } else if (page[0] >= 0 && ("FitH".equals(a.getName(1)) || "FitBH".equals(a.getName(1))) && a.size() > 2) {
            PdfPage pg = page(page[0]);
            java.awt.geom.Point2D d = pg.userToDisplay().transform(
                new java.awt.geom.Point2D.Double(0, a.getDouble(2, 0)), null);
            y[0] = d.getY();
        }
    }

    private PdfObject namedDest(String key) {
        PdfDict cat = pdf.catalog();
        PdfDict dests = cat.getDict("Dests");
        if (dests != null && dests.has(key)) {
            return dests.get(key);
        }
        PdfDict names = cat.getDict("Names");
        PdfDict tree = names == null ? null : names.getDict("Dests");
        return tree == null ? null : nameTree(tree, key, 0);
    }

    private static PdfObject nameTree(PdfDict node, String key, int depth) {
        if (depth > 32) {
            return null;
        }
        PdfArray names = node.getArray("Names");
        if (names != null) {
            for (int i = 0; i + 1 < names.size(); i += 2) {
                if (names.get(i) instanceof PdfString s && s.latin1().equals(key)) {
                    return names.get(i + 1);
                }
            }
        }
        PdfArray kids = node.getArray("Kids");
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                PdfDict k = kids.getDict(i);
                if (k == null) {
                    continue;
                }
                PdfArray lim = k.getArray("Limits");
                if (lim != null && lim.size() == 2 && lim.get(0) instanceof PdfString lo && lim.get(1) instanceof PdfString hi
                    && (key.compareTo(lo.latin1()) < 0 || key.compareTo(hi.latin1()) > 0)) {
                    continue;
                }
                PdfObject r = nameTree(k, key, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** A link of a page: its area in display space, and where it goes (a URI, or a page and y). */
    public record Link(Rectangle2D area, String uri, int page, double y) {
    }

    public List<Link> links(int pageIndex, Map<Integer, Integer> pageOf) {
        List<Link> out = new ArrayList<>();
        PdfPage p = page(pageIndex);
        java.awt.geom.AffineTransform u2d = p.userToDisplay();
        for (PdfDict a : p.annotations()) {
            if (!"Link".equals(a.getName("Subtype"))) {
                continue;
            }
            PdfArray r = a.getArray("Rect");
            if (r == null || r.size() < 4) {
                continue;
            }
            double[] v = r.toDoubles();
            Rectangle2D area = u2d.createTransformedShape(new Rectangle2D.Double(Math.min(v[0], v[2]), Math.min(v[1], v[3]),
                Math.abs(v[2] - v[0]), Math.abs(v[3] - v[1]))).getBounds2D();
            PdfDict act = a.getDict("A");
            String uri = null;
            int[] page = { -1 };
            double[] y = { -1 };
            if (act != null && "URI".equals(act.getName("S"))) {
                PdfString s = act.getString("URI");
                uri = s == null ? null : s.latin1();
            } else if (act != null && "GoTo".equals(act.getName("S"))) {
                resolveDest(act.get("D"), pageOf, page, y);
            } else if (a.has("Dest")) {
                resolveDest(a.get("Dest"), pageOf, page, y);
            }
            if (uri != null || page[0] >= 0) {
                out.add(new Link(area, uri, page[0], y[0]));
            }
        }
        return out;
    }
}
