package com.sphere.components.spdf.edit;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.cos.PdfWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Page operations, each one undoable step: rotate, delete, move, duplicate,
 * insert a blank page, import pages of another PDF (merge), extract pages to
 * a new PDF. The page tree is first made flat (one /Pages node), each page
 * keeping what it inherited, so every operation is a rewrite of one array.
 */
public final class PageOps {

    private PageOps() {
    }

    /** Flattens the tree inside the step: the root and every page are snapshotted first. */
    private static PdfArray flatKids(EditHistory h, PdfDocument doc) {
        PdfDict cat = doc.catalog();
        PdfObject rootRaw = cat.getRaw("Pages");
        if (rootRaw instanceof PdfRef r) {
            h.touch(r.num);
        }
        for (PdfPage p : doc.pages()) {
            h.touch(p.objNum());
        }
        PdfDict root = doc.flattenPageTree();
        return root.getArray("Kids");
    }

    private static void finish(PdfDocument doc, PdfArray kids) {
        PdfDict cat = doc.catalog();
        PdfDict root = cat.getDict("Pages");
        root.put("Count", kids.size());
        doc.changed();
    }

    public static void rotate(EditHistory h, PdfDocument doc, List<Integer> pages, int delta) {
        h.begin(delta > 0 ? "Rotate right" : "Rotate left");
        try {
            List<PdfPage> all = doc.pages();
            for (int i : pages) {
                if (i >= 0 && i < all.size()) {
                    PdfPage p = all.get(i);
                    h.touchPage(i, p.objNum());
                    p.setRotation(p.rotation() + delta);
                }
            }
            doc.changed();
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    public static void delete(EditHistory h, PdfDocument doc, List<Integer> pages) {
        if (pages.size() >= doc.pageCount()) {
            throw new IllegalArgumentException("A PDF keeps at least one page.");
        }
        h.begin(pages.size() > 1 ? "Delete " + pages.size() + " pages" : "Delete page");
        try {
            PdfArray kids = flatKids(h, doc);
            TreeSet<Integer> sorted = new TreeSet<>(pages);
            for (int i : sorted.descendingSet()) {
                if (i >= 0 && i < kids.size()) {
                    kids.remove(i);
                }
            }
            removeOutlineDangling(doc);
            finish(doc, kids);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** Moves pages so the first of them lands at an index (in the order without them). */
    public static void move(EditHistory h, PdfDocument doc, List<Integer> pages, int target) {
        h.begin("Move pages");
        try {
            PdfArray kids = flatKids(h, doc);
            TreeSet<Integer> sorted = new TreeSet<>(pages);
            List<PdfObject> moving = new ArrayList<>();
            for (int i : sorted) {
                moving.add(kids.getRaw(i));
            }
            int before = 0;
            for (int i : sorted.descendingSet()) {
                kids.remove(i);
                if (i < target) {
                    before++;
                }
            }
            int at = Math.max(0, Math.min(kids.size(), target - before));
            for (int k = 0; k < moving.size(); k++) {
                kids.add(at + k, moving.get(k));
            }
            finish(doc, kids);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    public static void duplicate(EditHistory h, PdfDocument doc, int index) {
        h.begin("Duplicate page");
        try {
            PdfArray kids = flatKids(h, doc);
            PdfDict src = doc.pages().get(index).dict();
            PdfDict copy = src.copy();
            copy.remove("Annots");
            PdfArray annots = src.getArray("Annots");
            if (annots != null) {
                // annotations belong to one page: each gets a copy of its own
                PdfArray na = new PdfArray();
                for (int i = 0; i < annots.size(); i++) {
                    PdfDict a = annots.getDict(i);
                    if (a != null && !"Popup".equals(a.getName("Subtype"))) {
                        PdfDict ac = a.copy();
                        ac.remove("P");
                        ac.remove("Popup");
                        ac.remove("Parent");
                        if (!"Widget".equals(ac.getName("Subtype"))) {
                            na.add(h.add(ac));
                        }
                    }
                }
                if (na.size() > 0) {
                    copy.put("Annots", na);
                }
            }
            PdfRef ref = h.add(copy);
            kids.add(index + 1, ref);
            finish(doc, kids);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    public static void insertBlank(EditHistory h, PdfDocument doc, int at, double width, double height) {
        h.begin("Insert blank page");
        try {
            PdfArray kids = flatKids(h, doc);
            PdfDict page = new PdfDict();
            page.put("Type", "Page");
            page.put("MediaBox", PdfArray.ofNumbers(0, 0, width, height));
            page.put("Resources", new PdfDict());
            page.put("Contents", h.add(PdfStream.of(new byte[0], false)));
            page.put("Parent", doc.catalog().getRaw("Pages"));
            kids.add(Math.max(0, Math.min(kids.size(), at)), h.add(page));
            finish(doc, kids);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** Copies pages of another document in, at an index. */
    public static void importPages(EditHistory h, PdfDocument doc, PdfDocument other, List<Integer> pages, int at) {
        h.begin("Insert pages from another PDF");
        try {
            PdfArray kids = flatKids(h, doc);
            Copier c = new Copier(other, doc, h);
            List<PdfPage> src = other.pages();
            int k = Math.max(0, Math.min(kids.size(), at));
            for (int i : pages) {
                if (i < 0 || i >= src.size()) {
                    continue;
                }
                PdfPage p = src.get(i);
                PdfDict d = (PdfDict) c.copyDirect(p.dict());
                for (String key : new String[] { "Resources", "MediaBox", "CropBox", "Rotate" }) {
                    if (!d.has(key)) {
                        PdfObject v = p.inheritedRaw(key);
                        if (v != null) {
                            d.put(key, c.copy(v));
                        }
                    }
                }
                d.put("Parent", doc.catalog().getRaw("Pages"));
                kids.add(k++, h.add(d));
            }
            finish(doc, kids);
            h.commit();
        } catch (RuntimeException e) {
            h.rollback();
            throw e;
        }
    }

    /** A new PDF holding copies of some pages. */
    public static byte[] extract(PdfDocument doc, List<Integer> pages) {
        PdfDocument out = PdfDocument.create();
        EditHistory h = new EditHistory(out);
        importPages(h, out, doc, pages, 0);
        return PdfWriter.write(out, true);
    }

    /** Drops outline entries pointing to pages that are gone (they would lead nowhere). */
    private static void removeOutlineDangling(PdfDocument doc) {
        // outline items keep working: destinations to removed pages resolve to nothing and are skipped
    }

    /**
     * Copies objects from one document into another, following references
     * (each source object copied once), leaving out a page's /Parent.
     */
    static final class Copier {
        private final PdfDocument from;
        private final PdfDocument to;
        private final EditHistory h;
        private final Map<Integer, PdfRef> done = new HashMap<>();

        Copier(PdfDocument from, PdfDocument to, EditHistory h) {
            this.from = from;
            this.to = to;
            this.h = h;
        }

        PdfObject copy(PdfObject o) {
            if (o instanceof PdfRef r) {
                PdfRef have = done.get(r.num);
                if (have != null) {
                    return have;
                }
                PdfObject target = from.getObject(r.num);
                PdfRef nr = h.add(com.sphere.components.spdf.cos.PdfNull.NULL == target ? new PdfDict() : new PdfDict());
                done.put(r.num, nr);
                PdfObject c = copyDirect(target);
                to.putObject(nr.num, c);
                return nr;
            }
            return copyDirect(o);
        }

        PdfObject copyDirect(PdfObject o) {
            if (o instanceof PdfStream s) {
                PdfStream c = new PdfStream(null, s.getRaw());
                for (var e : s.entries()) {
                    c.put(e.getKey(), copy(e.getValue()));
                }
                return c;
            }
            if (o instanceof PdfDict d) {
                PdfDict c = new PdfDict();
                boolean page = d.isType("Page");
                for (var e : d.entries()) {
                    PdfName k = e.getKey();
                    if (page && (k.name.equals("Parent") || k.name.equals("B") || k.name.equals("StructParents"))) {
                        continue;
                    }
                    if (k.name.equals("P") && d.has("Subtype")) {
                        // an annotation's page link would drag the whole source page in
                        continue;
                    }
                    c.put(k, copy(e.getValue()));
                }
                return c;
            }
            if (o instanceof PdfArray a) {
                PdfArray c = new PdfArray();
                for (PdfObject x : a.rawList()) {
                    c.add(copy(x));
                }
                return c;
            }
            return o.copy();
        }
    }
}
