package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfDocument;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.font.PdfFont;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What rendering a document keeps between pages and redraws: its fonts,
 * color spaces, decoded images and parsed form contents, all keyed by the
 * object they come from, so an edited object (a new one) misses the cache by
 * itself. Also the optional content groups hidden by default.
 */
public final class RenderCache {

    public final PdfDocument doc;
    private final Map<PdfDict, PdfFont> fonts = Collections.synchronizedMap(new IdentityHashMap<>());
    final Map<Object, PdfColorSpace> colorSpaces = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<PdfStream, SoftReference<BufferedImage>> images = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<PdfStream, SoftReference<List<Op>>> forms = Collections.synchronizedMap(new WeakHashMap<>());
    private Set<PdfDict> hiddenGroups;

    public RenderCache(PdfDocument doc) {
        this.doc = doc;
    }

    public PdfFont font(PdfDict fd) {
        PdfFont f = fonts.get(fd);
        if (f == null) {
            f = PdfFont.load(fd);
            fonts.put(fd, f);
        }
        return f;
    }

    /** Every font loaded so far. */
    public java.util.Collection<PdfFont> loadedFonts() {
        synchronized (fonts) {
            return new java.util.ArrayList<>(fonts.values());
        }
    }

    /** Pictures shown in place of image objects (by number) while they are edited elsewhere. */
    private final Map<Integer, BufferedImage> overrides = new java.util.concurrent.ConcurrentHashMap<>();

    public BufferedImage override(int objNum) {
        return overrides.isEmpty() ? null : overrides.get(objNum);
    }

    /** Shows a picture in place of an image object; null goes back to the object's own. */
    public void setOverride(int objNum, BufferedImage img) {
        if (img == null) {
            overrides.remove(objNum);
        } else {
            overrides.put(objNum, img);
        }
    }

    /** Decodes a picture (an image XObject, or an inline image's dictionary and data), not cached. */
    public BufferedImage decode(PdfDict d, byte[] inline, PdfDict resources) {
        return ImageDecoder.decode(d, inline, resources, colorSpaces, 0);
    }

    public BufferedImage image(PdfStream s) {
        SoftReference<BufferedImage> r = images.get(s);
        return r == null ? null : r.get();
    }

    public void putImage(PdfStream s, BufferedImage img) {
        images.put(s, new SoftReference<>(img));
    }

    public List<Op> form(PdfStream s) {
        SoftReference<List<Op>> r = forms.get(s);
        List<Op> ops = r == null ? null : r.get();
        if (ops == null) {
            ops = ContentParser.parse(s.getDecoded());
            forms.put(s, new SoftReference<>(ops));
        }
        return ops;
    }

    /**
     * Decodes, side by side on the machine's cores, the pictures of a page
     * (its own and those of its forms) not decoded yet, so the render that
     * follows finds them all ready: a page of twenty pictures is not twenty
     * decodings in a row.
     */
    public void predecode(com.sphere.components.spdf.cos.PdfPage page) {
        List<Object[]> todo = new java.util.ArrayList<>();
        collect(page.resources(), todo, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (todo.size() < 2) {
            return;
        }
        todo.parallelStream().forEach(t -> {
            PdfStream s = (PdfStream) t[0];
            if (image(s) == null) {
                try {
                    BufferedImage img = ImageDecoder.decode(s, null, (PdfDict) t[1], colorSpaces, 0);
                    if (img != null) {
                        putImage(s, img);
                    }
                } catch (RuntimeException e) {
                    // the render meets the same picture and deals with it
                }
            }
        });
    }

    private void collect(PdfDict res, List<Object[]> out, Set<PdfDict> seen, int depth) {
        PdfDict xo = res == null || depth > 6 ? null : res.getDict("XObject");
        if (xo == null || !seen.add(xo)) {
            return;
        }
        for (PdfName k : xo.keys()) {
            if (!(xo.get(k.name) instanceof PdfStream s)) {
                continue;
            }
            String sub = s.getName("Subtype", "");
            if (sub.equals("Image")) {
                if (!s.getBool("ImageMask", false) && image(s) == null) {
                    out.add(new Object[] { s, res });
                }
            } else if (sub.equals("Form")) {
                PdfDict fr = s.getDict("Resources");
                collect(fr != null ? fr : res, out, seen, depth + 1);
            }
        }
    }

    /** Forgets the parsed contents: a form's stream may have been rewritten in place. */
    public void clearForms() {
        forms.clear();
    }

    /** Forgets everything: after an undo, objects come back that the caches may have seen changed. */
    public void clear() {
        fonts.clear();
        colorSpaces.clear();
        images.clear();
        forms.clear();
        hiddenGroups = null;
    }

    /* ------------------------------------------------------------- optional content */

    private synchronized Set<PdfDict> hidden() {
        if (hiddenGroups == null) {
            Set<PdfDict> h = Collections.newSetFromMap(new IdentityHashMap<>());
            PdfDict cat = doc.catalog();
            PdfDict ocp = cat == null ? null : cat.getDict("OCProperties");
            PdfDict d = ocp == null ? null : ocp.getDict("D");
            if (d != null) {
                boolean baseOff = "OFF".equals(d.getName("BaseState"));
                if (baseOff) {
                    PdfArray all = ocp.getArray("OCGs");
                    for (int i = 0; all != null && i < all.size(); i++) {
                        if (all.getDict(i) != null) {
                            h.add(all.getDict(i));
                        }
                    }
                    PdfArray on = d.getArray("ON");
                    for (int i = 0; on != null && i < on.size(); i++) {
                        h.remove(on.getDict(i));
                    }
                }
                PdfArray off = d.getArray("OFF");
                for (int i = 0; off != null && i < off.size(); i++) {
                    if (off.getDict(i) != null) {
                        h.add(off.getDict(i));
                    }
                }
            }
            hiddenGroups = h;
        }
        return hiddenGroups;
    }

    /** Whether an optional content group or membership dictionary is visible. */
    public boolean visible(PdfObject oc) {
        oc = PdfRef.deref(oc);
        if (!(oc instanceof PdfDict d)) {
            return true;
        }
        Set<PdfDict> h = hidden();
        if (h.isEmpty()) {
            return true;
        }
        if ("OCMD".equals(d.getName("Type"))) {
            PdfObject ocgs = d.get("OCGs");
            Set<PdfDict> members = new HashSet<>();
            if (ocgs instanceof PdfDict one) {
                members.add(one);
            } else if (ocgs instanceof PdfArray a) {
                for (int i = 0; i < a.size(); i++) {
                    if (a.getDict(i) != null) {
                        members.add(a.getDict(i));
                    }
                }
            }
            if (members.isEmpty()) {
                return true;
            }
            String p = d.getName("P", "AnyOn");
            int on = 0;
            for (PdfDict m : members) {
                if (!h.contains(m)) {
                    on++;
                }
            }
            return switch (p) {
                case "AllOn" -> on == members.size();
                case "AnyOff" -> on < members.size();
                case "AllOff" -> on == 0;
                default -> on > 0;
            };
        }
        return !h.contains(d);
    }

    static boolean isName(PdfObject o, String n) {
        return o instanceof PdfName pn && pn.name.equals(n);
    }
}
