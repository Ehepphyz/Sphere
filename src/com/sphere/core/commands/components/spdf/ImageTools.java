package com.sphere.components.spdf;

import com.sphere.components.imaging.ImageDocument;
import com.sphere.components.imaging.ImageEditorPanel;
import com.sphere.components.imaging.ImageFileIO;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.edit.ImageOps;
import com.sphere.components.spdf.render.Op;
import com.sphere.components.spdf.render.PageImage;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Everything done to the pictures of the PDF shown: edited in Sphere Image
 * (the page showing the edits as they are made), replaced by a file, saved
 * as one, copied and pasted, inserted, moved and resized, turned and
 * mirrored (losslessly: only their matrix changes), made gray, enhanced,
 * given a transparent paper, shrunk to a sensible resolution, taken out,
 * extracted all at once, compressed all at once.
 */
final class ImageTools {

    private final SpdfPanel p;
    /** The pictures open in Sphere Image, by their link's key. */
    private final Map<String, PdfLink> open = new HashMap<>();

    ImageTools(SpdfPanel p) {
        this.p = p;
    }

    /* ================================================================= reading */

    /** A copy of a picture's pixels (transparency included), or null when it cannot be read. */
    BufferedImage pixels(int page, PageImage im) {
        SpdfDoc d = p.doc;
        PdfPage pg = d.page(page);
        BufferedImage img;
        if (im.inline()) {
            Op op = ImageOps.opOf(d.pdf, pg, d.renderer, im);
            img = op == null || op.inlineDict == null ? null : d.cache.decode(op.inlineDict, op.inlineData, pg.resources());
        } else if (d.pdf.getObject(im.objNum) instanceof PdfStream s) {
            img = d.cache.image(s);
            if (img == null) {
                img = d.cache.decode(s, null, pg.resources());
            }
        } else {
            img = null;
        }
        if (img == null) {
            return null;
        }
        // the editor paints into what it is given: never into the picture the pages are drawn from
        BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** The picture of a page at a place (display space) whose bounds best match, or null. */
    PageImage find(int page, Rectangle2D near, int objNum) {
        PageImage best = null;
        double bestScore = 0;
        for (PageImage x : p.doc.images(page)) {
            if (objNum >= 0 && x.objNum != objNum) {
                continue;
            }
            Rectangle2D b = x.bounds();
            Rectangle2D i = b.createIntersection(near);
            double inter = i.isEmpty() ? 0 : i.getWidth() * i.getHeight();
            double union = b.getWidth() * b.getHeight() + near.getWidth() * near.getHeight() - inter;
            double score = union <= 0 ? 0 : inter / union;
            if (score > bestScore || best == null && objNum >= 0) {
                bestScore = score;
                best = x;
            }
        }
        return best;
    }

    /** The name the PDF's tab shows: its file's, else its title. */
    private static String fileName(SpdfDoc d) {
        return d.file != null ? d.file.getFileName().toString() : d.title();
    }

    /* ================================================================= Sphere Image */

    /** Opens a picture in Sphere Image, linked: the page follows the edits, Ctrl+S there puts the picture back. */
    void edit(int page, PageImage im) {
        if (im.stencil) {
            p.status("This picture is a one-color stencil, painted with the current color: it has no pixels to edit");
            return;
        }
        BufferedImage img = pixels(page, im);
        if (img == null) {
            p.error("This picture could not be read (its format is not one Sphere decodes)", null);
            return;
        }
        SpdfDoc d = p.doc;
        String key = System.identityHashCode(d) + ":" + (im.inline() ? "inline:" + page + ":" + im.streamId + ":" + im.opIndex
            : String.valueOf(im.objNum));
        PdfLink link = open.computeIfAbsent(key, k -> new PdfLink(k, d, page, im));
        String name = fileName(d).replaceFirst("(?i)\\.pdf$", "") + ", page " + d.pageName(page) + " picture";
        p.imageHost().open(img, name, link);
        p.status("Picture opened in Sphere Image: the page shows your edits as you make them; Ctrl+S there puts it back");
    }

    /** The tie between a picture of this PDF and Sphere Image. */
    private final class PdfLink implements ImageEditorPanel.Link {
        final String key;
        final SpdfDoc d;
        int page;
        PageImage im;
        int objNum;

        PdfLink(String key, SpdfDoc d, int page, PageImage im) {
            this.key = key;
            this.d = d;
            this.page = page;
            this.im = im;
            this.objNum = im.objNum;
        }

        @Override
        public String label() {
            return "picture on page " + d.pageName(page) + " of " + fileName(d);
        }

        @Override
        public Object key() {
            return key;
        }

        @Override
        public void apply(BufferedImage image) {
            if (p.doc != d) {
                throw new IllegalStateException("the PDF it came from was closed: save the picture as a file instead");
            }
            PageImage cur = current();
            if (cur == null) {
                throw new IllegalStateException("the picture is no longer in the PDF");
            }
            if (objNum >= 0) {
                d.cache.setOverride(objNum, null);
            }
            Rectangle2D was = cur.bounds();
            replace(page, cur, image, "Edit picture in Sphere Image");
            if (cur.inline()) {
                // an inline picture became an image object: the next edits go to that object
                PageImage now = find(page, was, -1);
                if (now != null) {
                    im = now;
                    objNum = now.objNum;
                }
            }
        }

        @Override
        public void preview(BufferedImage image) {
            if (p.doc != d || objNum < 0) {
                return;
            }
            d.cache.setOverride(objNum, image);
            d.bumpLive();
            p.view.repaint();
            p.side.thumbnails.repaint();
        }

        @Override
        public void closed() {
            open.remove(key);
            if (objNum >= 0) {
                d.cache.setOverride(objNum, null);
                d.bumpLive();
                if (p.doc == d) {
                    p.view.repaint();
                    p.side.thumbnails.repaint();
                }
            }
        }

        /** The picture as the PDF has it now (pages may have moved since it was opened). */
        PageImage current() {
            if (objNum >= 0) {
                if (page < d.pageCount()) {
                    for (PageImage x : d.images(page)) {
                        if (x.objNum == objNum) {
                            return x;
                        }
                    }
                }
                for (int[] pg : d.pagesUsing(objNum)) {
                    for (PageImage x : d.images(pg[0])) {
                        if (x.objNum == objNum) {
                            page = pg[0];
                            return x;
                        }
                    }
                }
                return null;
            }
            return page < d.pageCount() ? find(page, im.bounds(), -1) : null;
        }
    }

    /** The PDF closes: the pictures open in Sphere Image show their own pixels again and lose their way back. */
    void closeAll() {
        for (PdfLink l : new ArrayList<>(open.values())) {
            if (l.objNum >= 0) {
                l.d.cache.setOverride(l.objNum, null);
            }
        }
        open.clear();
    }

    /* ================================================================= changes */

    /** Replaces a picture's pixels (everywhere the picture is drawn). */
    void replace(int page, PageImage im, BufferedImage img, String label) {
        SpdfDoc d = p.doc;
        List<int[]> pages = im.inline() ? List.of(new int[] { page, d.page(page).objNum() }) : d.pagesUsing(im.objNum);
        if (pages.isEmpty()) {
            pages = List.of(new int[] { page, d.page(page).objNum() });
        }
        try {
            ImageOps.replace(d.history, d.pdf, d.page(page), page, d.renderer, im, img, pages, label);
        } catch (RuntimeException e) {
            p.error(label + ": " + e.getMessage(), e);
            return;
        }
        for (int[] pg : pages) {
            d.forgetText(pg[0]);
            p.documentEdited(pg[0]);
        }
        p.view.reselectImage(page, im.objNum, im.bounds());
        p.status(label + (pages.size() > 1 ? " (shown on " + pages.size() + " pages)" : "") + " — Ctrl+Z to undo");
    }

    /** Moves, resizes, turns or mirrors a picture: its unit square goes where newToUser maps it. */
    void place(int page, PageImage im, AffineTransform newToUser, String label) {
        SpdfDoc d = p.doc;
        try {
            ImageOps.transform(d.history, d.pdf, d.page(page), page, d.renderer, im, newToUser, label);
        } catch (RuntimeException e) {
            p.error(label + ": " + e.getMessage(), e);
            return;
        }
        p.documentEdited(page);
        Rectangle2D now = d.page(page).userToDisplay().createTransformedShape(
            newToUser.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1))).getBounds2D();
        p.view.reselectImage(page, im.objNum, now);
        if (!im.onPage()) {
            p.status(label + ": this picture belongs to a form, the change shows wherever that form is drawn");
        }
    }

    void turn(int page, PageImage im, int quarterTurnsClockwise) {
        place(page, im, ImageOps.turned(im, p.doc.page(page), quarterTurnsClockwise),
              quarterTurnsClockwise == 2 ? "Turn picture upside down" : quarterTurnsClockwise > 0 ? "Rotate picture right"
                  : "Rotate picture left");
    }

    void mirror(int page, PageImage im, boolean horizontal) {
        place(page, im, ImageOps.mirrored(im, horizontal), horizontal ? "Mirror picture left to right"
            : "Mirror picture top to bottom");
    }

    /** A change of the pixels themselves (gray, enhanced, transparent paper...). */
    void pixelOp(int page, PageImage im, String label, UnaryOperator<BufferedImage> f) {
        BufferedImage img = pixels(page, im);
        if (img == null) {
            p.error("This picture could not be read", null);
            return;
        }
        replace(page, im, f.apply(img), label);
    }

    /** Shrinks a picture to a resolution (pixels per inch at its size on the page). */
    void shrink(int page, PageImage im, double ppi) {
        BufferedImage img = pixels(page, im);
        if (img == null) {
            p.error("This picture could not be read", null);
            return;
        }
        int w = (int) Math.max(1, Math.round(im.widthPt() / 72 * ppi));
        int h = (int) Math.max(1, Math.round(im.heightPt() / 72 * ppi));
        if (w >= img.getWidth() && h >= img.getHeight()) {
            p.status("This picture is already at " + Math.round(im.ppi()) + " ppi");
            return;
        }
        replace(page, im, ImageOps.downscale(img, w, h), "Shrink picture to " + Math.round(ppi) + " ppi");
    }

    void delete(int page, PageImage im) {
        SpdfDoc d = p.doc;
        try {
            ImageOps.delete(d.history, d.pdf, d.page(page), page, d.renderer, im);
        } catch (RuntimeException e) {
            p.error("Could not delete the picture: " + e.getMessage(), e);
            return;
        }
        p.view.clearImageSelection();
        p.documentEdited(page);
        p.status("Picture deleted — Ctrl+Z to undo");
    }

    /** Takes out every picture a page draws itself, in one step. */
    void deleteAll(int page) {
        SpdfDoc d = p.doc;
        List<PageImage> ims = new ArrayList<>();
        for (PageImage im : d.images(page)) {
            if (im.onPage() && !im.stencil) {
                ims.add(im);
            }
        }
        if (ims.isEmpty()) {
            p.status("Page " + (page + 1) + " has no picture of its own");
            return;
        }
        // from the last operation back, so the operations before keep their places
        ims.sort((a, b) -> b.opIndex - a.opIndex);
        d.history.begin("Delete " + ims.size() + (ims.size() == 1 ? " picture" : " pictures"));
        try {
            for (PageImage im : ims) {
                ImageOps.delete(d.history, d.pdf, d.page(page), page, d.renderer, im);
            }
            d.history.commit();
        } catch (RuntimeException e) {
            d.history.rollback();
            p.error("Could not delete the pictures: " + e.getMessage(), e);
            return;
        }
        p.view.clearImageSelection();
        p.documentEdited(page);
        p.status(ims.size() + (ims.size() == 1 ? " picture" : " pictures") + " deleted from page " + (page + 1)
                 + " — Ctrl+Z to undo");
    }

    /**
     * The picture a command means: the selected one (on that page, when a
     * page is named), else the biggest of the page named, else, with no page
     * named, the biggest of the nearest page that has one (the current page,
     * then the ones after it, then before). {page, picture}, or null.
     */
    Object[] target(int page) {
        PdfView v = p.view;
        if (v.selImage != null && (page < 0 || page == v.selImagePage)) {
            return new Object[] { v.selImagePage, v.selImage };
        }
        if (page >= 0) {
            PageImage best = biggest(page);
            return best == null ? null : new Object[] { page, best };
        }
        int cur = v.currentPage();
        int n = p.doc.pageCount();
        for (int k = 0; k <= Math.min(n, 40); k++) {
            for (int pg : new int[] { cur + k, cur - k }) {
                if (pg >= 0 && pg < n && (k > 0 || pg == cur)) {
                    PageImage best = biggest(pg);
                    if (best != null) {
                        return new Object[] { pg, best };
                    }
                }
            }
        }
        return null;
    }

    private PageImage biggest(int page) {
        PageImage best = null;
        for (PageImage im : p.doc.images(page)) {
            if (!im.stencil && (best == null || im.widthPt() * im.heightPt() > best.widthPt() * best.heightPt())) {
                best = im;
            }
        }
        return best;
    }

    /** Counts the pictures of the whole document (in the background) and says it. */
    void report() {
        SpdfDoc d = p.doc;
        p.status("Counting the pictures…");
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                java.util.Set<Integer> objects = new java.util.HashSet<>();
                int drawn = 0;
                int pages = 0;
                int low = 0;
                for (int i = 0; i < d.pageCount(); i++) {
                    List<PageImage> ims = d.images(i);
                    if (!ims.isEmpty()) {
                        pages++;
                    }
                    for (PageImage im : ims) {
                        if (im.stencil) {
                            continue;
                        }
                        drawn++;
                        if (im.objNum >= 0) {
                            objects.add(im.objNum);
                        }
                        if (im.ppi() < 150) {
                            low++;
                        }
                    }
                }
                return new int[] { drawn, objects.size(), pages, low };
            }

            @Override
            protected void done() {
                try {
                    int[] r = get();
                    p.status(r[0] == 0 ? "This PDF has no pictures"
                        : r[0] + (r[0] == 1 ? " picture" : " pictures") + " drawn on " + r[2] + (r[2] == 1 ? " page" : " pages")
                          + " (" + r[1] + " distinct)" + (r[3] > 0 ? ", " + r[3] + " below 150 ppi" : ""));
                } catch (Exception e) {
                    p.error("Could not count the pictures: " + e.getMessage(), e);
                }
            }
        }.execute();
    }

    /* ================================================================= adding */

    /** Adds a picture to a page, centered on a point (display space) or the page, at a sensible size. */
    void insert(int page, BufferedImage img, Point2D at, String label) {
        SpdfDoc d = p.doc;
        if (d == null) {
            return;
        }
        PdfPage pg = d.page(page);
        double pw = pg.displayWidth();
        double ph = pg.displayHeight();
        // its own size at 96 ppi, within 60% of the page
        double w = img.getWidth() * 72.0 / 96;
        double h = img.getHeight() * 72.0 / 96;
        double f = Math.min(1, Math.min(pw * 0.6 / w, ph * 0.6 / h));
        w *= f;
        h *= f;
        double cx = at == null ? pw / 2 : at.getX();
        double cy = at == null ? ph / 2 : at.getY();
        double x = Math.max(0, Math.min(pw - w, cx - w / 2));
        double y = Math.max(0, Math.min(ph - h, cy - h / 2));
        Rectangle2D box = new Rectangle2D.Double(x, y, w, h);
        try {
            ImageOps.insert(d.history, d.pdf, pg, page, d.renderer, img, box, label);
        } catch (RuntimeException e) {
            p.error(label + ": " + e.getMessage(), e);
            return;
        }
        p.documentEdited(page);
        p.setTool(PdfView.Tool.SELECT);
        p.view.reselectImage(page, -1, box);
        p.status("Picture added — drag it to move it, drag a handle to resize it, double-click to edit it");
    }

    void insertFile(int page, File f, Point2D at) {
        try {
            ImageDocument doc = ImageFileIO.open(f);
            insert(page, doc.render(false), at, "Insert picture " + f.getName());
        } catch (Exception e) {
            p.error("Could not read " + f.getName() + ": " + e.getMessage(), e);
        }
    }

    void insertFromFile(int page, Point2D at) {
        if (p.doc == null) {
            p.status("Open a PDF first");
            return;
        }
        File f = chooseImage("Insert a Picture");
        if (f != null) {
            insertFile(page, f, at);
        }
    }

    /** Replaces a picture by a file's, fitted into the same place (its own proportions kept). */
    void replaceWithFile(int page, PageImage im) {
        File f = chooseImage("Replace the Picture");
        if (f == null) {
            return;
        }
        BufferedImage img;
        try {
            img = ImageFileIO.open(f).render(false);
        } catch (Exception e) {
            p.error("Could not read " + f.getName() + ": " + e.getMessage(), e);
            return;
        }
        SpdfDoc d = p.doc;
        double was = im.widthPt() / Math.max(1e-9, im.heightPt());
        double now = (double) img.getWidth() / img.getHeight();
        d.history.begin("Replace picture with " + f.getName());
        try {
            replace(page, im, img, "Replace picture with " + f.getName());
            if (Math.abs(was / now - 1) > 0.02 && im.onPage()) {
                // fitted in the old box, centered, rather than stretched to it
                double sx = now < was ? now / was : 1;
                double sy = now < was ? 1 : was / now;
                AffineTransform t = new AffineTransform(im.toUser);
                t.translate((1 - sx) / 2, (1 - sy) / 2);
                t.scale(sx, sy);
                PageImage cur = find(page, im.bounds(), im.inline() ? -1 : im.objNum);
                if (cur != null) {
                    ImageOps.transform(d.history, d.pdf, d.page(page), page, d.renderer, cur, t, "Fit picture");
                }
            }
            d.history.commit();
        } catch (RuntimeException e) {
            d.history.rollback();
            p.error("Could not replace the picture: " + e.getMessage(), e);
        }
        d.forgetText(page);
        p.documentEdited(page);
        p.view.reselectImage(page, im.objNum, im.bounds());
    }

    private File chooseImage(String title) {
        JFileChooser c = new JFileChooser(p.doc != null && p.doc.file != null ? p.doc.file.getParent().toFile() : null);
        c.setDialogTitle(title);
        c.setFileFilter(new FileNameExtensionFilter("Pictures", ImageFileIO.READABLE));
        return c.showOpenDialog(p) == JFileChooser.APPROVE_OPTION ? c.getSelectedFile() : null;
    }

    /* ================================================================= clipboard and files */

    void copy(int page, PageImage im) {
        BufferedImage img = pixels(page, im);
        if (img == null) {
            p.status("This picture could not be read");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(img), null);
        p.status("Picture copied (" + img.getWidth() + " × " + img.getHeight() + " px)");
    }

    /** Pastes a picture from the clipboard onto a page; false when the clipboard holds none. */
    boolean paste(int page, Point2D at) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                Image img = (Image) cb.getData(DataFlavor.imageFlavor);
                BufferedImage b = toBuffered(img);
                if (b != null) {
                    insert(page, b, at, "Paste picture");
                    return true;
                }
            }
            if (cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) cb.getData(DataFlavor.javaFileListFlavor);
                for (File f : files) {
                    if (ImageFileIO.isImage(f)) {
                        insertFile(page, f, at);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            p.status("Could not paste: " + e.getMessage());
        }
        return false;
    }

    private static BufferedImage toBuffered(Image img) {
        if (img instanceof BufferedImage b) {
            return b;
        }
        int w = img.getWidth(null);
        int h = img.getHeight(null);
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return b;
    }

    /** Saves a picture as a file: a JPEG as it is stored (no quality lost), anything else as PNG. */
    void saveAs(int page, PageImage im) {
        SpdfDoc d = p.doc;
        byte[] original = ImageOps.originalFile(d.pdf, im);
        String ext = original != null ? ImageOps.originalExtension(im) : "png";
        String base = (d.file == null ? "picture" : d.file.getFileName().toString().replaceFirst("(?i)\\.pdf$", ""))
            + "_p" + (page + 1) + (im.name != null ? "_" + im.name : "");
        JFileChooser c = new JFileChooser(d.file != null ? d.file.getParent().toFile() : null);
        c.setDialogTitle("Save the Picture As");
        c.setSelectedFile(new File(c.getCurrentDirectory(), base + "." + ext));
        if (c.showSaveDialog(p) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = c.getSelectedFile();
        try {
            String want = ImageFileIO.extension(f.getName()).toLowerCase(Locale.ROOT);
            if (original != null && (want.equals(ext) || want.equals("jpeg") && ext.equals("jpg"))) {
                Files.write(f.toPath(), original);
            } else {
                BufferedImage img = pixels(page, im);
                if (img == null) {
                    throw new IllegalStateException("the picture could not be read");
                }
                String fmt = want.isEmpty() ? "png" : want;
                if (want.isEmpty()) {
                    f = new File(f.getParentFile(), f.getName() + ".png");
                }
                if (!ImageIO.write(fmt.equals("jpg") || fmt.equals("jpeg") ? opaque(img) : img, fmt, f)) {
                    ImageIO.write(img, "png", f);
                }
            }
            p.status("Picture saved: " + f.getName());
        } catch (Exception e) {
            p.error("Could not save the picture: " + e.getMessage(), e);
        }
    }

    private static BufferedImage opaque(BufferedImage img) {
        BufferedImage o = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, o.getWidth(), o.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return o;
    }

    /* ================================================================= the whole document */

    /** Every distinct picture of the document with the biggest size it is drawn at. */
    private Map<Integer, Object[]> allPictures(SpdfDoc d) {
        Map<Integer, Object[]> out = new LinkedHashMap<>();
        for (int i = 0; i < d.pageCount(); i++) {
            for (PageImage im : d.images(i)) {
                if (im.inline() || im.stencil) {
                    continue;
                }
                Object[] have = out.get(im.objNum);
                if (have == null || ((PageImage) have[1]).widthPt() * ((PageImage) have[1]).heightPt()
                                    < im.widthPt() * im.heightPt()) {
                    out.put(im.objNum, new Object[] { i, im });
                }
            }
        }
        return out;
    }

    /** Saves every picture of the document into a folder (JPEGs as they are stored). */
    void extractAll() {
        SpdfDoc d = p.doc;
        if (d == null) {
            return;
        }
        JFileChooser c = new JFileChooser(d.file != null ? d.file.getParent().toFile() : null);
        c.setDialogTitle("Folder for the Pictures");
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (c.showSaveDialog(p) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File dir = c.getSelectedFile();
        String base = d.file == null ? "picture" : d.file.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        p.status("Saving the pictures…");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                Files.createDirectories(dir.toPath());
                int n = 0;
                for (Map.Entry<Integer, Object[]> e : allPictures(d).entrySet()) {
                    int page = (Integer) e.getValue()[0];
                    PageImage im = (PageImage) e.getValue()[1];
                    byte[] original = ImageOps.originalFile(d.pdf, im);
                    String name = base + "_p" + (page + 1) + "_" + (++n);
                    if (original != null) {
                        Files.write(dir.toPath().resolve(name + "." + ImageOps.originalExtension(im)), original);
                    } else if (d.pdf.getObject(im.objNum) instanceof PdfStream s) {
                        BufferedImage img = d.cache.decode(s, null, d.page(page).resources());
                        if (img != null) {
                            ImageIO.write(img, "png", dir.toPath().resolve(name + ".png").toFile());
                        }
                    }
                }
                return n;
            }

            @Override
            protected void done() {
                try {
                    int n = get();
                    p.status(n == 0 ? "This PDF has no pictures" : n + (n == 1 ? " picture" : " pictures") + " saved in "
                                                                     + dir.getName());
                } catch (Exception e) {
                    p.error("Could not save the pictures: " + e.getMessage(), e);
                }
            }
        }.execute();
    }

    /**
     * Shrinks every picture drawn at more than a resolution to it, the
     * photographs written as JPEG: one step, undoable, the space saved said.
     */
    void compressAll(double ppi) {
        SpdfDoc d = p.doc;
        if (d == null) {
            return;
        }
        p.status("Looking at the pictures…");
        new SwingWorker<List<Object[]>, Void>() {
            long before;
            long after;

            @Override
            protected List<Object[]> doInBackground() {
                List<Object[]> done = new ArrayList<>();
                for (Map.Entry<Integer, Object[]> e : allPictures(d).entrySet()) {
                    int page = (Integer) e.getValue()[0];
                    PageImage im = (PageImage) e.getValue()[1];
                    if (im.ppi() < ppi * 1.3 || !(d.pdf.getObject(im.objNum) instanceof PdfStream s)) {
                        continue;
                    }
                    BufferedImage img = d.cache.decode(s, null, d.page(page).resources());
                    if (img == null) {
                        continue;
                    }
                    int w = (int) Math.max(1, Math.round(im.widthPt() / 72 * ppi));
                    int h = (int) Math.max(1, Math.round(im.heightPt() / 72 * ppi));
                    BufferedImage small = ImageOps.downscale(img, w, h);
                    ImageOps.Encoded enc = ImageOps.encodeDetached(small,
                        "DCTDecode".equals(s.imageFilter()) ? Boolean.TRUE : null, 0.85f);
                    long was = s.getRaw().length + (s.getStream("SMask") != null ? s.getStream("SMask").getRaw().length : 0);
                    long now = enc.size();
                    if (now < was * 0.9) {
                        before += was;
                        after += now;
                        done.add(new Object[] { im.objNum, enc });
                    }
                }
                return done;
            }

            @Override
            protected void done() {
                try {
                    List<Object[]> changes = get();
                    if (p.doc != d) {
                        return;
                    }
                    if (changes.isEmpty()) {
                        p.status("No picture is above " + Math.round(ppi) + " ppi: nothing to shrink");
                        return;
                    }
                    d.history.begin("Compress pictures to " + Math.round(ppi) + " ppi");
                    try {
                        for (Object[] c : changes) {
                            int num = (Integer) c[0];
                            ImageOps.Encoded enc = (ImageOps.Encoded) c[1];
                            d.history.touch(num);
                            d.pdf.putObject(num, ImageOps.attach(d.history, enc));
                            for (int[] pg : d.pagesUsing(num)) {
                                d.history.touchPage(pg[0], pg[1]);
                                d.forgetText(pg[0]);
                            }
                        }
                        d.history.commit();
                    } catch (RuntimeException e) {
                        d.history.rollback();
                        throw e;
                    }
                    p.documentEdited(-1);
                    p.status(changes.size() + (changes.size() == 1 ? " picture" : " pictures") + " shrunk to "
                             + Math.round(ppi) + " ppi: " + kb(before) + " → " + kb(after) + " — save to keep it, Ctrl+Z to undo");
                } catch (Exception e) {
                    p.error("Could not compress the pictures: " + e.getMessage(), e);
                }
            }
        }.execute();
    }

    private static String kb(long bytes) {
        return bytes >= 1 << 20 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            : String.format(Locale.US, "%d KB", Math.max(1, bytes / 1024));
    }

    /* ================================================================= about */

    /** What a picture is: its size on the page and in pixels, its resolution, how it is stored, where it is used. */
    void properties(int page, PageImage im) {
        SpdfDoc d = p.doc;
        StringBuilder b = new StringBuilder("<html><body style='width:340px'>");
        b.append(String.format(Locale.US, "<b>On the page</b>: %.0f × %.0f pt (%.1f × %.1f mm)<br>", im.widthPt(),
                               im.heightPt(), im.widthPt() / 72 * 25.4, im.heightPt() / 72 * 25.4));
        b.append(String.format(Locale.US, "<b>Pixels</b>: %d × %d<br>", im.pixelWidth, im.pixelHeight));
        double ppi = im.ppi();
        b.append(String.format(Locale.US, "<b>Resolution</b>: %.0f ppi", ppi));
        if (ppi < 150) {
            b.append(" <font color='#E67E22'>(low for print: 300 ppi is the usual target)</font>");
        } else if (ppi > 450) {
            b.append(" <font color='#7F8C8D'>(more than print needs: shrinking to 300 ppi saves space)</font>");
        }
        b.append("<br><b>Stored as</b>: ").append(im.kind()).append(im.alpha ? ", with transparency" : "");
        if (!im.inline() && d.pdf.getObject(im.objNum) instanceof PdfStream s) {
            b.append(" (").append(kb(s.getRaw().length)).append(")");
            b.append("<br><b>Object</b>: ").append(im.objNum).append(" 0 R");
            List<int[]> pages = d.pagesUsing(im.objNum);
            if (pages.size() > 1) {
                StringBuilder ps = new StringBuilder();
                for (int k = 0; k < Math.min(12, pages.size()); k++) {
                    ps.append(k == 0 ? "" : ", ").append(pages.get(k)[0] + 1);
                }
                b.append("<br><b>Shown on pages</b>: ").append(ps).append(pages.size() > 12 ? "…" : "");
            }
        } else {
            b.append("<br>An inline picture (written in the page's content)");
        }
        if (!im.onPage()) {
            b.append("<br>Drawn by a form (shared content): moving it moves it wherever the form is drawn");
        }
        b.append("</body></html>");
        JOptionPane.showMessageDialog(p, b.toString(), "Picture", JOptionPane.INFORMATION_MESSAGE);
    }

    /* ================================================================= clipboard glue */

    /** A picture on the clipboard. */
    private record ImageSelection(BufferedImage image) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DataFlavor.imageFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor f) {
            return DataFlavor.imageFlavor.equals(f);
        }

        @Override
        public Object getTransferData(DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) {
                throw new java.awt.datatransfer.UnsupportedFlavorException(f);
            }
            return image;
        }
    }
}
