package com.sphere.components.spdf;

import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.edit.Annotator;
import com.sphere.components.spdf.edit.Redactor;
import com.sphere.components.spdf.edit.RichText;
import com.sphere.components.spdf.edit.TextEdit;
import com.sphere.components.spdf.render.PageImage;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.Insights;
import com.sphere.components.spdf.text.PageText;
import com.sphere.components.spdf.text.SmartSearch;
import com.sphere.components.spdf.text.TextBlock;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JViewport;
import javax.swing.JWindow;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The pages, one under the other, at a zoom; the tools act here: select and
 * copy text, follow links, edit text in place, add text, highlight, notes,
 * ink, shapes, redaction, panning. Hovering a citation like [12] shows the
 * reference it points to; hovering "Fig. 3" shows the figure.
 */
final class PdfView extends JComponent implements Scrollable {

    enum Tool { SELECT, EDIT, ADDTEXT, HIGHLIGHT, NOTE, INK, RECT, ELLIPSE, LINE, ARROW, REDACT, HAND }

    static final int GAP = 18;

    final SpdfPanel panel;
    SpdfDoc doc;
    RenderService render;
    double zoom = 1.0;
    Tool tool = Tool.SELECT;
    boolean night;
    private double[] px = new double[0];
    private double[] py = new double[0];
    private int totalW;
    private int totalH;

    // selection of text (one page at a time)
    int selPage = -1;
    int selStart;
    int selEnd;
    // search
    List<SmartSearch.Hit> hits = new ArrayList<>();
    int currentHit = -1;
    // editing
    TextEditor editor;
    private boolean caretOn = true;
    private final Timer blink;
    // hover
    private TextBlock hoverBlock;
    private int hoverPage = -1;
    private SpdfDoc.Link hoverLink;
    // drawing
    private List<Point2D> ink;
    private final List<List<Point2D>> inkStrokes = new ArrayList<>();
    private int drawPage = -1;
    private Point2D dragStart;
    private Point2D dragEnd;
    private Point pressView;
    private Point panFrom;
    private Point panViewFrom;
    // annotation selected
    PdfDict selectedAnnot;
    int selectedAnnotPage = -1;
    // picture selected (select tool), hovered, and being dragged: 0 the picture itself, 1-8 a handle
    PageImage selImage;
    int selImagePage = -1;
    private PageImage hoverImage;
    private int hoverImagePage = -1;
    private int imageGrab = -1;
    private Point2D imageGrabFrom;
    private AffineTransform imageTo;
    /** The handles of a picture, in its own unit square (y up): corners and edge middles, clockwise from top left. */
    private static final double[][] HANDLES = { {}, { 0, 1 }, { 0.5, 1 }, { 1, 1 }, { 1, 0.5 }, { 1, 0 }, { 0.5, 0 },
        { 0, 0 }, { 0, 0.5 } };
    // preview popup
    private final Preview preview = new Preview();
    private Map<Integer, Integer> pageOf;

    PdfView(SpdfPanel panel) {
        this.panel = panel;
        setFocusable(true);
        setOpaque(true);
        setRequestFocusEnabled(true);
        blink = new Timer(530, e -> {
            caretOn = !caretOn;
            if (editor != null) {
                repaint();
            }
        });
        blink.start();
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        addKeyListener(new Keys());
        setToolTipText("");
    }

    void setDocument(SpdfDoc d, RenderService r) {
        cancelEdit();
        this.doc = d;
        this.render = r;
        hits = new ArrayList<>();
        currentHit = -1;
        selPage = -1;
        selectedAnnot = null;
        selImage = null;
        selImagePage = -1;
        hoverImage = null;
        imageGrab = -1;
        pageOf = null;
        relayout();
    }

    /* ================================================================= pictures */

    /** The picture a click at a point means: the topmost one, unless it is a page-sized scan under text. */
    PageImage pictureAt(int page, Point2D d) {
        PageImage im = doc.imageAt(page, d);
        if (im == null) {
            return null;
        }
        Rectangle2D b = im.bounds();
        PdfPage p = doc.page(page);
        boolean pageSized = b.getWidth() * b.getHeight() > 0.6 * p.displayWidth() * p.displayHeight();
        if (pageSized && doc.text(page).blockAt(d.getX(), d.getY()) != null) {
            // a scanned page with its text over it: the click is for the text
            return null;
        }
        return im;
    }

    void selectImage(int page, PageImage im) {
        selImage = im;
        selImagePage = im == null ? -1 : page;
        if (im != null) {
            selectedAnnot = null;
            selPage = -1;
            selStart = selEnd = 0;
            panel.status(String.format(java.util.Locale.US,
                "Picture: %d × %d px, %.0f ppi, %s%s — drag to move, handles to resize, double-click to edit in Sphere Image",
                im.pixelWidth, im.pixelHeight, im.ppi(), im.kind(), im.alpha ? ", transparent" : ""));
        }
        repaint();
    }

    void clearImageSelection() {
        selImage = null;
        selImagePage = -1;
        imageGrab = -1;
        imageTo = null;
        repaint();
    }

    /** After a change: the same picture found again (its object, near where it went), selected. */
    void reselectImage(int page, int objNum, Rectangle2D near) {
        PageImage im = near == null ? null : panel.images.find(page, near, objNum);
        if (im == null) {
            clearImageSelection();
        } else {
            selectImage(page, im);
        }
    }

    /** The handle of the selected picture under a point (1-8), else 0. */
    private int handleAt(Point2D d) {
        if (selImage == null) {
            return 0;
        }
        double r = 6 / zoom;
        for (int k = 1; k <= 8; k++) {
            Point2D h = selImage.toDisplay.transform(new Point2D.Double(HANDLES[k][0], HANDLES[k][1]), null);
            if (Math.abs(h.getX() - d.getX()) <= r && Math.abs(h.getY() - d.getY()) <= r) {
                return k;
            }
        }
        return 0;
    }

    /** Where a drag takes the selected picture: moved by the mouse, or resized about the opposite handle. */
    private AffineTransform dragged(Point2D d, boolean shift) {
        PdfPage p = doc.page(selImagePage);
        if (imageGrab == 0) {
            double dx = d.getX() - imageGrabFrom.getX();
            double dy = d.getY() - imageGrabFrom.getY();
            if (shift) {
                // straight across or straight down
                if (Math.abs(dx) > Math.abs(dy)) {
                    dy = 0;
                } else {
                    dx = 0;
                }
            }
            Point2D du = p.displayToUser().deltaTransform(new Point2D.Double(dx, dy), null);
            AffineTransform t = AffineTransform.getTranslateInstance(du.getX(), du.getY());
            t.concatenate(selImage.toUser);
            return t;
        }
        Point2D q;
        try {
            q = selImage.toDisplay.inverseTransform(d, null);
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return selImage.toUser;
        }
        double hx = HANDLES[imageGrab][0];
        double hy = HANDLES[imageGrab][1];
        double ax = 1 - hx;
        double ay = 1 - hy;
        double sx = hx == 0.5 ? 1 : (q.getX() - ax) / (hx - ax);
        double sy = hy == 0.5 ? 1 : (q.getY() - ay) / (hy - ay);
        if (hx != 0.5 && hy != 0.5 && !shift) {
            // a corner keeps the proportions (Shift lets them go)
            double s = Math.max(sx, sy);
            sx = s;
            sy = s;
        }
        sx = Math.max(0.02, sx);
        sy = Math.max(0.02, sy);
        AffineTransform t = new AffineTransform(selImage.toUser);
        t.translate(ax, ay);
        t.scale(sx, sy);
        t.translate(-ax, -ay);
        return t;
    }

    /** True when two matrices place a picture visibly apart (a quarter point, or a thousandth of its size). */
    private static boolean moved(AffineTransform a, AffineTransform b) {
        double[] x = new double[6];
        double[] y = new double[6];
        a.getMatrix(x);
        b.getMatrix(y);
        double scale = Math.max(1e-9, Math.abs(y[0]) + Math.abs(y[1]) + Math.abs(y[2]) + Math.abs(y[3]));
        for (int i = 0; i < 4; i++) {
            if (Math.abs(x[i] - y[i]) > 1e-3 * scale) {
                return true;
            }
        }
        return Math.abs(x[4] - y[4]) > 0.25 || Math.abs(x[5] - y[5]) > 0.25;
    }

    private void paintImageOverlays(Graphics2D d, int i) {
        Color accent = SpdfTheme.accent();
        float px1 = (float) (1 / zoom);
        if (hoverImage != null && hoverImagePage == i && tool == Tool.SELECT
            && (selImage == null || !hoverImage.sameAs(selImage))) {
            d.setColor(SpdfTheme.alpha(accent, 150));
            d.setStroke(new BasicStroke(px1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                                        new float[] { 4 * px1, 3 * px1 }, 0));
            d.draw(hoverImage.outline());
        }
        if (selImage == null || selImagePage != i) {
            return;
        }
        AffineTransform toDisplay = selImage.toDisplay;
        if (imageTo != null && imageGrab >= 0) {
            // the picture where it is going, drawn over the page while the mouse holds it
            toDisplay = doc.page(i).userToDisplay();
            toDisplay.concatenate(imageTo);
            BufferedImage img = selImage.objNum >= 0 && doc.pdf.getObject(selImage.objNum)
                instanceof com.sphere.components.spdf.cos.PdfStream s ? doc.cache.image(s) : null;
            if (img != null) {
                AffineTransform at = new AffineTransform(toDisplay);
                at.concatenate(new AffineTransform(1.0 / img.getWidth(), 0, 0, -1.0 / img.getHeight(), 0, 1));
                java.awt.Composite c = d.getComposite();
                d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.75f));
                d.drawImage(img, at, null);
                d.setComposite(c);
            }
        }
        java.awt.Shape outline = toDisplay.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1));
        d.setColor(accent);
        d.setStroke(new BasicStroke(1.5f * px1));
        d.draw(outline);
        double hs = 3.5 / zoom;
        for (int k = 1; k <= 8; k++) {
            Point2D h = toDisplay.transform(new Point2D.Double(HANDLES[k][0], HANDLES[k][1]), null);
            Rectangle2D box = new Rectangle2D.Double(h.getX() - hs, h.getY() - hs, 2 * hs, 2 * hs);
            d.setColor(Color.WHITE);
            d.fill(box);
            d.setColor(accent);
            d.setStroke(new BasicStroke(px1));
            d.draw(box);
        }
        // what the picture is, above it
        Rectangle2D b = outline.getBounds2D();
        double ppi = imageTo != null && imageGrab > 0 ? selImage.pixelWidth / Math.max(1e-9,
            Math.hypot(imageTo.getScaleX(), imageTo.getShearY()) / 72) : selImage.ppi();
        String label = String.format(java.util.Locale.US, "%d × %d px · %.0f ppi · %s", selImage.pixelWidth,
                                     selImage.pixelHeight, ppi, selImage.kind());
        d.setFont(SpdfTheme.ui(Font.PLAIN, 10).deriveFont((float) (10 / zoom)));
        Rectangle2D lb = d.getFontMetrics().getStringBounds(label, d);
        float lx = (float) b.getX();
        float ly = (float) Math.max(lb.getHeight() + 2 / zoom, b.getY() - 5 / zoom);
        d.setColor(ppi < 150 ? new Color(0xE6, 0x7E, 0x22) : accent);
        d.fill(new RoundRectangle2D.Double(lx, ly - lb.getHeight(), lb.getWidth() + 8 / zoom, lb.getHeight() + 2 / zoom,
                                           4 / zoom, 4 / zoom));
        d.setColor(Color.WHITE);
        d.drawString(label, (float) (lx + 4 / zoom), (float) (ly - 3 / zoom));
    }

    /* ================================================================= layout */

    void relayout() {
        if (doc == null) {
            px = new double[0];
            py = new double[0];
            totalW = totalH = 0;
            revalidate();
            repaint();
            return;
        }
        int n = doc.pageCount();
        px = new double[n];
        py = new double[n];
        double maxW = 0;
        for (int i = 0; i < n; i++) {
            maxW = Math.max(maxW, doc.page(i).displayWidth() * zoom);
        }
        double y = GAP;
        double viewW = getParent() instanceof JViewport vp ? vp.getWidth() : maxW + 2 * GAP;
        double contentW = Math.max(viewW, maxW + 2 * GAP);
        for (int i = 0; i < n; i++) {
            double w = doc.page(i).displayWidth() * zoom;
            px[i] = Math.round((contentW - w) / 2);
            py[i] = Math.round(y);
            y += doc.page(i).displayHeight() * zoom + GAP;
        }
        totalW = (int) Math.ceil(contentW);
        totalH = (int) Math.ceil(y);
        revalidate();
        repaint();
    }

    Rectangle2D pageRect(int i) {
        PdfPage p = doc.page(i);
        return new Rectangle2D.Double(px[i], py[i], p.displayWidth() * zoom, p.displayHeight() * zoom);
    }

    /** The page under a view point (with the gap around it), or -1. */
    int pageAt(Point p) {
        if (doc == null) {
            return -1;
        }
        for (int i = 0; i < px.length; i++) {
            Rectangle2D r = pageRect(i);
            if (p.y >= r.getMinY() - GAP / 2.0 && p.y <= r.getMaxY() + GAP / 2.0) {
                return i;
            }
        }
        return -1;
    }

    Point2D toDisplay(int page, Point p) {
        return new Point2D.Double((p.x - px[page]) / zoom, (p.y - py[page]) / zoom);
    }

    Point toView(int page, Point2D d) {
        return new Point((int) Math.round(px[page] + d.getX() * zoom), (int) Math.round(py[page] + d.getY() * zoom));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(totalW, totalH);
    }

    /** The page most in view (the one crossing the viewport's upper third). */
    int currentPage() {
        if (doc == null || py.length == 0) {
            return 0;
        }
        Rectangle vis = getVisibleRect();
        double probe = vis.y + vis.height / 3.0;
        for (int i = 0; i < py.length; i++) {
            if (pageRect(i).getMaxY() + GAP >= probe) {
                return i;
            }
        }
        return py.length - 1;
    }

    void scrollToPage(int i, double displayY) {
        if (doc == null || i < 0 || i >= py.length) {
            return;
        }
        Rectangle vis = getVisibleRect();
        int y = (int) (py[i] + Math.max(0, displayY) * zoom) - (displayY > 0 ? 40 : GAP / 2);
        scrollRectToVisible(new Rectangle(vis.x, Math.max(0, y), vis.width, vis.height));
        if (getParent() instanceof JViewport vp) {
            vp.setViewPosition(new Point(vp.getViewPosition().x,
                                         Math.max(0, Math.min(y, totalH - vp.getHeight()))));
        }
        repaint();
    }

    /** Zooms keeping a view point (the mouse, or the center) still. */
    void setZoom(double z, Point anchor) {
        // a zoom of the user's ends a fit (the fits set theirs again after this)
        fit = 0;
        z = Math.max(0.1, Math.min(8, z));
        if (doc == null || Math.abs(z - zoom) < 1e-4) {
            zoom = z;
            relayout();
            return;
        }
        JViewport vp = getParent() instanceof JViewport v ? v : null;
        Point a = anchor;
        if (a == null && vp != null) {
            Rectangle r = vp.getViewRect();
            a = new Point(r.x + r.width / 2, r.y + r.height / 2);
        }
        int page = a == null ? -1 : pageAt(a);
        Point2D d = page >= 0 ? toDisplay(page, a) : null;
        Point offset = vp == null || a == null ? null
            : new Point(a.x - vp.getViewPosition().x, a.y - vp.getViewPosition().y);
        zoom = z;
        relayout();
        if (vp != null && d != null && offset != null) {
            Point na = toView(page, d);
            vp.validate();
            vp.setViewPosition(new Point(Math.max(0, na.x - offset.x), Math.max(0, na.y - offset.y)));
        }
        panel.zoomChanged();
    }

    /** 1 while the pages fit the width, 2 while a page fits the view, 0 at a zoom of the user's: kept when the view resizes. */
    int fit;

    void fitWidth() {
        if (doc == null || !(getParent() instanceof JViewport vp)) {
            return;
        }
        int cur = currentPage();
        double w = doc.page(cur).displayWidth();
        // room for the vertical scroll bar, so no horizontal one appears
        setZoom((vp.getWidth() - 2 * GAP - 16) / w, null);
        scrollToPage(cur, 0);
        fit = 1;
    }

    void fitPage() {
        if (doc == null || !(getParent() instanceof JViewport vp)) {
            return;
        }
        PdfPage p = doc.page(currentPage());
        int cur = currentPage();
        double z = Math.min((vp.getWidth() - 2 * GAP) / p.displayWidth(), (vp.getHeight() - 2 * GAP) / p.displayHeight());
        setZoom(z, null);
        scrollToPage(cur, 0);
        fit = 2;
    }

    /**
     * The view changed size (a tab opened beside, the window resized): a fit
     * the user chose is made again, the place read staying at the top.
     */
    void refit() {
        if (fit == 0 || doc == null || !(getParent() instanceof JViewport vp) || vp.getWidth() < 100 || py.length == 0) {
            return;
        }
        Point top = vp.getViewPosition();
        int page = Math.max(0, pageAt(new Point(top.x, top.y + 1)));
        double atY = (top.y - py[page]) / zoom;
        PdfPage p = doc.page(page);
        double z = fit == 1 ? (vp.getWidth() - 2 * GAP - 16) / p.displayWidth()
            : Math.min((vp.getWidth() - 2 * GAP) / p.displayWidth(), (vp.getHeight() - 2 * GAP) / p.displayHeight());
        if (Math.abs(z - zoom) < 1e-3) {
            return;
        }
        int keep = fit;
        setZoom(z, null);
        fit = keep;
        vp.setViewPosition(new Point(0, (int) Math.max(0, Math.min(totalH - vp.getHeight(), py[page] + atY * zoom))));
        repaint();
    }

    /* ================================================================= painting */

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle clip = g.getClipBounds();
        g.setColor(SpdfTheme.canvas());
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        if (doc == null) {
            paintEmpty(g);
            g.dispose();
            return;
        }
        double device = g.getTransform().getScaleX();
        double scale = zoom * device;
        int first = -1;
        for (int i = 0; i < px.length; i++) {
            Rectangle2D r = pageRect(i);
            if (r.getMaxY() < clip.y - 400 || r.getMinY() > clip.y + clip.height + 400) {
                continue;
            }
            boolean visible = r.getMaxY() >= clip.y && r.getMinY() <= clip.y + clip.height;
            if (first < 0) {
                first = i;
            }
            if (!visible) {
                // a little ahead of the scroll: drawn already when it comes into view
                prefetch(i, scale);
                continue;
            }
            paintPage(g, i, r, scale);
        }
        g.dispose();
    }

    private void prefetch(int i, double scale) {
        PdfPage p = doc.page(i);
        // a page drawn in tiles is prepared as its backdrop; its tiles come when they are seen
        render.get(i, RenderService.tiled(p, scale) ? RenderService.backdropScale(p, scale) : scale, null, 0, night,
                   this::repaint);
    }

    private void paintPage(Graphics2D g, int i, Rectangle2D r, double scale) {
        // shadow
        for (int k = 1; k <= 4; k++) {
            g.setColor(new Color(0, 0, 0, 14));
            g.fill(new RoundRectangle2D.Double(r.getX() - k + 2, r.getY() - k + 3, r.getWidth() + 2 * k - 2,
                                               r.getHeight() + 2 * k - 2, 2 * k, 2 * k));
        }
        g.setColor(night ? new Color(26, 27, 31) : Color.WHITE);
        g.fill(r);
        boolean editing = editor != null && editor.pageIndex == i;
        PageRenderer.GlyphFilter filter = editing ? editor.filter() : null;
        int hideKey = editing ? editor.hideKey : 0;
        if (RenderService.tiled(doc.page(i), scale)) {
            paintTiles(g, i, r, scale, filter, hideKey);
            paintOverlaysOf(g, i, r);
            return;
        }
        BufferedImage img = render.get(i, scale, filter, hideKey, night, this::repaint);
        if (img == null) {
            // another zoom or an older version stands in for the few milliseconds the new one takes
            img = render.any(i, scale, night);
        }
        if (img != null) {
            g.drawImage(img, (int) Math.round(r.getX()), (int) Math.round(r.getY()), (int) Math.round(r.getWidth()),
                        (int) Math.round(r.getHeight()), null);
        } else {
            g.setColor(SpdfTheme.alpha(SpdfTheme.muted(), 120));
            g.setFont(SpdfTheme.ui(Font.PLAIN, 12));
            g.drawString("…", (float) r.getCenterX() - 4, (float) r.getCenterY());
        }
        paintOverlaysOf(g, i, r);
    }

    /** The overlays of a page (selection, hits, the paragraph being edited...), in display space. */
    private void paintOverlaysOf(Graphics2D g, int i, Rectangle2D r) {
        Graphics2D d = (Graphics2D) g.create();
        d.translate(r.getX(), r.getY());
        d.scale(zoom, zoom);
        paintOverlays(d, i);
        d.dispose();
    }

    /**
     * A page too big for one picture: its whole picture at a lower resolution
     * first, then, over it, the tiles in sight at full resolution, each drawn
     * pixel for pixel in device space (no resampling, no seams).
     */
    private void paintTiles(Graphics2D g, int i, Rectangle2D r, double scale, PageRenderer.GlyphFilter filter,
                            int hideKey) {
        PdfPage p = doc.page(i);
        double low = RenderService.backdropScale(p, scale);
        BufferedImage back = render.get(i, low, filter, hideKey, night, this::repaint);
        if (back == null) {
            back = render.any(i, low, night);
        }
        if (back != null) {
            g.drawImage(back, (int) Math.round(r.getX()), (int) Math.round(r.getY()), (int) Math.round(r.getWidth()),
                        (int) Math.round(r.getHeight()), null);
        }
        AffineTransform t = g.getTransform();
        double device = t.getScaleX();
        int w = (int) Math.ceil(p.displayWidth() * scale);
        int h = (int) Math.ceil(p.displayHeight() * scale);
        // the page's origin in device pixels, and the part of it in sight
        double ox = Math.round(t.getTranslateX() + r.getX() * device);
        double oy = Math.round(t.getTranslateY() + r.getY() * device);
        Rectangle clip = g.getClipBounds();
        double cx0 = t.getTranslateX() + clip.getX() * device - ox;
        double cy0 = t.getTranslateY() + clip.getY() * device - oy;
        double cx1 = cx0 + clip.getWidth() * device;
        double cy1 = cy0 + clip.getHeight() * device;
        int tx0 = Math.max(0, (int) Math.floor(cx0 / RenderService.TILE));
        int ty0 = Math.max(0, (int) Math.floor(cy0 / RenderService.TILE));
        int tx1 = Math.min((w - 1) / RenderService.TILE, (int) Math.floor(cx1 / RenderService.TILE));
        int ty1 = Math.min((h - 1) / RenderService.TILE, (int) Math.floor(cy1 / RenderService.TILE));
        Graphics2D dg = (Graphics2D) g.create();
        dg.setTransform(AffineTransform.getTranslateInstance(ox, oy));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                BufferedImage tile = render.tile(i, scale, tx, ty, filter, hideKey, night, this::repaint);
                if (tile != null) {
                    dg.drawImage(tile, tx * RenderService.TILE, ty * RenderService.TILE, null);
                }
            }
        }
        dg.dispose();
    }

    private void paintOverlays(Graphics2D d, int i) {
        Color accent = SpdfTheme.accent();
        PageText t = doc.textIfReady(i);
        // search hits
        if (!hits.isEmpty() && t != null) {
            for (int k = 0; k < hits.size(); k++) {
                SmartSearch.Hit h = hits.get(k);
                if (h.page() != i) {
                    continue;
                }
                boolean cur = k == currentHit;
                for (Rectangle2D r : t.rects(h.start(), h.end())) {
                    Rectangle2D g2 = grow(r, 1);
                    d.setColor(cur ? new Color(255, 140, 0, 120) : new Color(255, 213, 0, 95));
                    d.fill(g2);
                    if (cur) {
                        d.setColor(new Color(230, 110, 0));
                        d.setStroke(new BasicStroke((float) (1.5 / zoom)));
                        d.draw(g2);
                    }
                }
            }
        }
        // text selection
        if (selPage == i && selStart != selEnd && t != null) {
            d.setColor(SpdfTheme.alpha(accent, 80));
            for (Rectangle2D r : t.rects(selStart, selEnd)) {
                d.fill(r);
            }
        }
        // hovered paragraph in the edit tool
        if ((tool == Tool.EDIT) && hoverBlock != null && hoverPage == i && (editor == null || editor.block != hoverBlock)) {
            Rectangle2D b = grow(hoverBlock.bounds(), 3);
            d.setColor(SpdfTheme.alpha(accent, 28));
            d.fill(b);
            d.setColor(SpdfTheme.alpha(accent, 200));
            d.setStroke(new BasicStroke((float) (1 / zoom), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                                        new float[] { (float) (4 / zoom), (float) (3 / zoom) }, 0));
            d.draw(b);
            d.setFont(SpdfTheme.ui(Font.PLAIN, 10).deriveFont((float) (10 / zoom)));
            String label = hoverBlock.align.name().charAt(0) + hoverBlock.align.name().substring(1).toLowerCase()
                + " · " + Math.round(hoverBlock.size()) + " pt"
                + (hoverBlock.dominant() != null && hoverBlock.dominant().font != null
                   ? " · " + hoverBlock.dominant().font.displayName() : "");
            float lx = (float) b.getX();
            float ly = (float) (b.getY() - 3 / zoom);
            Rectangle2D lb = d.getFontMetrics().getStringBounds(label, d);
            d.setColor(accent);
            d.fill(new RoundRectangle2D.Double(lx, ly - lb.getHeight(), lb.getWidth() + 8 / zoom, lb.getHeight() + 2 / zoom,
                                               4 / zoom, 4 / zoom));
            d.setColor(Color.WHITE);
            d.drawString(label, (float) (lx + 4 / zoom), (float) (ly - 3 / zoom));
        }
        // hovered link
        if (hoverLink != null && hoverPage == i) {
            d.setColor(SpdfTheme.alpha(accent, 40));
            d.fill(hoverLink.area());
        }
        // selected annotation
        if (selectedAnnot != null && selectedAnnotPage == i) {
            com.sphere.components.spdf.cos.PdfArray rect = selectedAnnot.getArray("Rect");
            if (rect != null && rect.size() == 4) {
                double[] v = rect.toDoubles();
                Rectangle2D u = new Rectangle2D.Double(Math.min(v[0], v[2]), Math.min(v[1], v[3]), Math.abs(v[2] - v[0]),
                                                       Math.abs(v[3] - v[1]));
                Rectangle2D dr = doc.page(i).userToDisplay().createTransformedShape(u).getBounds2D();
                d.setColor(accent);
                d.setStroke(new BasicStroke((float) (1.5 / zoom)));
                d.draw(grow(dr, 2));
            }
        }
        // drawing in progress
        if (drawPage == i) {
            Color c = panel.drawColor;
            d.setColor(c);
            d.setStroke(new BasicStroke(panel.drawWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (List<Point2D> s : inkStrokes) {
                drawPolyline(d, s);
            }
            if (ink != null) {
                drawPolyline(d, ink);
            }
            if (dragStart != null && dragEnd != null) {
                Rectangle2D box = box(dragStart, dragEnd);
                switch (tool) {
                    case RECT -> d.draw(box);
                    case ELLIPSE -> d.draw(new Ellipse2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight()));
                    case LINE, ARROW -> d.draw(new Line2D.Double(dragStart, dragEnd));
                    case REDACT -> {
                        d.setColor(new Color(0, 0, 0, 120));
                        d.fill(box);
                        d.setColor(Color.BLACK);
                        d.setStroke(new BasicStroke((float) (1 / zoom)));
                        d.draw(box);
                    }
                    default -> { }
                }
            }
        }
        // pictures: hovered, selected, being moved
        paintImageOverlays(d, i);
        // the paragraph being edited
        if (editor != null && editor.pageIndex == i) {
            editor.paint(d, zoom, caretOn && hasFocus(), accent);
        }
    }

    private static void drawPolyline(Graphics2D d, List<Point2D> pts) {
        if (pts.size() < 2) {
            return;
        }
        GeneralPath p = new GeneralPath();
        p.moveTo(pts.get(0).getX(), pts.get(0).getY());
        for (int k = 1; k < pts.size(); k++) {
            p.lineTo(pts.get(k).getX(), pts.get(k).getY());
        }
        d.draw(p);
    }

    private static Rectangle2D grow(Rectangle2D r, double m) {
        return new Rectangle2D.Double(r.getX() - m, r.getY() - m, r.getWidth() + 2 * m, r.getHeight() + 2 * m);
    }

    private static Rectangle2D box(Point2D a, Point2D b) {
        return new Rectangle2D.Double(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                                      Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private void paintEmpty(Graphics2D g) {
        Rectangle vis = getVisibleRect();
        String a = "Open a PDF (Ctrl+O) or drop one here";
        String b = "Ctrl+K: commands in plain words — \"delete page 3\", \"summary\", \"highlight energy\"";
        g.setColor(SpdfTheme.muted());
        g.setFont(SpdfTheme.ui(Font.BOLD, 16));
        int w = g.getFontMetrics().stringWidth(a);
        g.drawString(a, vis.x + (vis.width - w) / 2, vis.y + vis.height / 2 - 8);
        g.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        w = g.getFontMetrics().stringWidth(b);
        g.setColor(SpdfTheme.faint());
        g.drawString(b, vis.x + (vis.width - w) / 2, vis.y + vis.height / 2 + 18);
    }

    /* ================================================================= editing */

    /** Opens a paragraph for editing in place. */
    void editBlock(int page, TextBlock b, Point2D clickDisplay) {
        commitEdit();
        PdfPage p = doc.page(page);
        RichText rt = RichText.of(b);
        editor = new TextEditor(doc, page, b, rt, TextEdit.frameOf(b, p), TextEdit.widthOf(b), this::repaint);
        editor.onChange = e -> panel.editorChanged(e);
        if (clickDisplay != null) {
            editor.mousePressed(clickDisplay, 1, false, zoom);
            editor.mouseReleased();
        }
        panel.editorChanged(editor);
        requestFocusInWindow();
        repaint();
    }

    /** Starts a new text box with its first baseline at a display point. */
    void newTextBox(int page, Point2D at) {
        commitEdit();
        PdfPage p = doc.page(page);
        RichText rt = new RichText();
        float width = (float) Math.max(60, Math.min(320, p.displayWidth() - at.getX() - 24));
        editor = new TextEditor(doc, page, null, rt, TextEdit.frameAt(p, at.getX(), at.getY() + 10), width,
                                this::repaint);
        editor.setDefaultStyle(panel.lastStyle);
        editor.onChange = e -> panel.editorChanged(e);
        panel.editorChanged(editor);
        requestFocusInWindow();
        repaint();
    }

    /** Writes the edit in progress into the document. */
    void commitEdit() {
        TextEditor e = editor;
        if (e == null) {
            return;
        }
        editor = null;
        try {
            RichText.Style st = e.currentStyle();
            if (st != null) {
                panel.lastStyle = st.original == null ? st : st;
            }
            if (e.commit()) {
                panel.status("Text edited — Ctrl+Z to undo");
                panel.documentEdited(e.pageIndex);
            }
        } catch (RuntimeException ex) {
            panel.error("The edit could not be written: " + ex.getMessage(), ex);
        }
        panel.editorChanged(null);
        repaint();
    }

    void cancelEdit() {
        if (editor != null) {
            editor = null;
            panel.editorChanged(null);
            repaint();
        }
    }

    /* ================================================================= text selection */

    String selectedText() {
        if (selPage < 0 || selStart == selEnd) {
            return "";
        }
        PageText t = doc.text(selPage);
        int s = Math.max(0, Math.min(selStart, selEnd));
        int e = Math.min(t.text.length(), Math.max(selStart, selEnd));
        return t.text.substring(s, e);
    }

    List<TextGlyph> selectedGlyphs() {
        if (selPage < 0 || selStart == selEnd) {
            return List.of();
        }
        return doc.text(selPage).glyphs(selStart, selEnd);
    }

    void copySelection() {
        String s = selectedText();
        if (!s.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
            panel.status(s.length() + " characters copied");
        }
    }

    void clearSelection() {
        selPage = -1;
        selStart = selEnd = 0;
        repaint();
    }

    /** Quads of the selected glyphs, one per run on a line, for markup annotations. */
    List<float[]> selectionQuads() {
        List<float[]> out = new ArrayList<>();
        List<TextGlyph> gs = selectedGlyphs();
        float[] cur = null;
        TextGlyph prev = null;
        for (TextGlyph g : gs) {
            if (g.isSpace() && cur == null) {
                continue;
            }
            float[] q = g.quad();
            if (cur != null && prev != null && Math.abs(prev.y - g.y) < Math.max(prev.size, g.size) * 0.4
                && Math.abs(prev.ux - g.ux) < 0.01 && Math.abs(prev.uy - g.uy) < 0.01) {
                cur[2] = q[2];
                cur[3] = q[3];
                cur[4] = q[4];
                cur[5] = q[5];
            } else {
                cur = q.clone();
                out.add(cur);
            }
            prev = g;
        }
        return out;
    }

    void markupSelection(String subtype, int rgb) {
        if (selPage < 0 || selStart == selEnd) {
            return;
        }
        List<float[]> quads = selectionQuads();
        if (quads.isEmpty()) {
            return;
        }
        doc.history.begin(subtype.equals("Highlight") ? "Highlight" : subtype.equals("Underline") ? "Underline" : "Strike out");
        try {
            Annotator.markup(doc.history, doc.page(selPage), selPage, subtype, quads, rgb, null);
            doc.history.commit();
        } catch (RuntimeException e) {
            doc.history.rollback();
            panel.error("Could not annotate: " + e.getMessage(), e);
        }
        panel.documentEdited(selPage);
        clearSelection();
    }

    void redactSelection() {
        if (selPage < 0 || selStart == selEnd) {
            return;
        }
        List<TextGlyph> gs = selectedGlyphs();
        try {
            Redactor.redactGlyphs(doc.history, doc.pdf, doc.page(selPage), selPage, doc.renderer, gs, null, 0);
            panel.status(gs.size() + " characters redacted (removed from the file)");
        } catch (RuntimeException e) {
            panel.error("Could not redact: " + e.getMessage(), e);
        }
        panel.documentEdited(selPage);
        clearSelection();
    }

    /* ================================================================= hover help */

    @Override
    public String getToolTipText(MouseEvent e) {
        if (doc == null || tool != Tool.SELECT) {
            preview.hideIt();
            return null;
        }
        int page = pageAt(e.getPoint());
        if (page < 0) {
            preview.hideIt();
            return null;
        }
        if (hoverLink != null && hoverLink.uri() != null) {
            return hoverLink.uri();
        }
        PageText t = doc.textIfReady(page);
        if (t == null) {
            return null;
        }
        Point2D d = toDisplay(page, e.getPoint());
        int idx = t.indexAt(d.getX(), d.getY());
        if (idx < 0 || idx >= t.text.length()) {
            preview.hideIt();
            return null;
        }
        TextGlyph g = idx < t.charGlyph.length ? t.charGlyph[idx] : null;
        if (g == null || !g.bounds().contains(d)) {
            preview.hideIt();
            return null;
        }
        Insights in = panel.insightsIfReady();
        if (in != null && !in.references.isEmpty()) {
            List<String> labels = Insights.citationsAt(t.text, idx);
            if (!labels.isEmpty()) {
                StringBuilder sb = new StringBuilder("<html><body style='width:420px'>");
                int n = 0;
                for (String l : labels) {
                    String ref = in.references.get(l);
                    if (ref != null && n < 4) {
                        sb.append("<p><b>[").append(l).append("]</b> ").append(escape(ref)).append("</p>");
                        n++;
                    }
                }
                if (n > 0) {
                    return sb.append("</body></html>").toString();
                }
            }
        }
        // "Fig. 3" / "Figure 3" / "Table 2": show where it is
        int from = Math.max(0, idx - 12);
        int to = Math.min(t.text.length(), idx + 12);
        Matcher m = Insights.CAPTION_MENTION.matcher(t.text.substring(from, to));
        while (m.find()) {
            if (from + m.start() <= idx && from + m.end() >= idx && in != null) {
                String kind = m.group(1).toLowerCase().startsWith("tab") ? "Table" : "Figure";
                for (Insights.Caption c : in.captions) {
                    if (c.kind().equals(kind) && c.number().equals(m.group(2))) {
                        Point screen = e.getLocationOnScreen();
                        preview.showFigure(this, c, screen);
                        return null;
                    }
                }
            }
        }
        preview.hideIt();
        return null;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A small window with a figure's picture, cut from its page above the caption. */
    private final class Preview {
        private JWindow win;
        private Insights.Caption shown;

        void showFigure(JComponent owner, Insights.Caption c, Point screen) {
            if (c.equals(shown) && win != null && win.isVisible()) {
                return;
            }
            shown = c;
            PageText t = doc.text(c.page());
            TextGlyph g = c.start() < t.charGlyph.length ? t.charGlyph[c.start()] : null;
            int s = c.start();
            while (g == null && s < t.charGlyph.length - 1) {
                g = t.charGlyph[++s];
            }
            if (g == null) {
                return;
            }
            PdfPage p = doc.page(c.page());
            double capY = g.bounds().getY();
            boolean table = c.kind().equals("Table");
            double h = Math.min(p.displayHeight() * 0.45, table ? p.displayHeight() - capY : capY);
            Rectangle2D area = table ? new Rectangle2D.Double(0, capY - 4, p.displayWidth(), h)
                : new Rectangle2D.Double(0, Math.max(0, capY - h), p.displayWidth(), h + g.size * 2.5);
            BufferedImage full = render.any(c.page(), 1.0, night);
            if (full == null) {
                full = render.render(c.page(), 1.0, null, night);
            }
            int x0 = (int) area.getX();
            int y0 = (int) Math.max(0, area.getY());
            int w = (int) Math.min(full.getWidth() - x0, area.getWidth());
            int hh = (int) Math.min(full.getHeight() - y0, area.getHeight());
            if (w <= 0 || hh <= 0) {
                return;
            }
            double k = full.getWidth() / p.displayWidth();
            BufferedImage crop = full.getSubimage((int) (x0 * k), (int) (y0 * k),
                                                  (int) Math.min(full.getWidth() - x0 * k, w * k),
                                                  (int) Math.min(full.getHeight() - y0 * k, hh * k));
            double fit = Math.min(1.0, 460.0 / crop.getWidth());
            int vw = (int) (crop.getWidth() * fit);
            int vh = (int) (crop.getHeight() * fit);
            if (win == null) {
                win = new JWindow(SwingUtilities.getWindowAncestor(owner));
            }
            final BufferedImage pic = crop;
            final String title = c.kind() + " " + c.number() + " — page " + (c.page() + 1);
            win.setContentPane(new JComponent() {
                @Override
                protected void paintComponent(Graphics g0) {
                    Graphics2D gg = (Graphics2D) g0;
                    gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    gg.setColor(SpdfTheme.popup());
                    gg.fillRect(0, 0, getWidth(), getHeight());
                    gg.drawImage(pic, 6, 24, vw, vh, null);
                    gg.setColor(SpdfTheme.fg());
                    gg.setFont(SpdfTheme.ui(Font.BOLD, 12));
                    gg.drawString(title, 8, 16);
                    gg.setColor(SpdfTheme.border());
                    gg.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                }
            });
            win.setSize(vw + 12, vh + 30);
            win.setLocation(screen.x + 16, screen.y + 18);
            win.setVisible(true);
        }

        void hideIt() {
            if (win != null && win.isVisible()) {
                win.setVisible(false);
            }
            shown = null;
        }
    }

    /* ================================================================= mouse */

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            preview.hideIt();
            if (doc == null) {
                return;
            }
            pressView = e.getPoint();
            int page = pageAt(e.getPoint());
            if (SwingUtilities.isMiddleMouseButton(e) || tool == Tool.HAND) {
                panFrom = e.getLocationOnScreen();
                panViewFrom = getParent() instanceof JViewport vp ? vp.getViewPosition() : new Point();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                return;
            }
            if (page < 0) {
                return;
            }
            Point2D d = toDisplay(page, e.getPoint());
            if (SwingUtilities.isRightMouseButton(e)) {
                contextMenu(e, page, d);
                return;
            }
            if (editor != null) {
                if (editor.pageIndex == page && (editor.hitBox(d) || editor.hitMove(d, zoom) || editor.hitResize(d, zoom))) {
                    editor.mousePressed(d, e.getClickCount(), e.isShiftDown(), zoom);
                    return;
                }
                commitEdit();
                if (tool == Tool.EDIT || tool == Tool.ADDTEXT) {
                    // a click outside closes the edit; a second click starts another
                    return;
                }
            }
            switch (tool) {
                case EDIT -> {
                    PageText t = doc.text(page);
                    TextBlock b = t.blockAt(d.getX(), d.getY());
                    if (b != null) {
                        editBlock(page, b, d);
                    } else {
                        newTextBox(page, d);
                    }
                }
                case ADDTEXT -> newTextBox(page, d);
                case SELECT, HIGHLIGHT -> {
                    if (tool == Tool.SELECT) {
                        PdfDict a = Annotator.at(doc.page(page), d);
                        if (a != null) {
                            selectedAnnot = a;
                            selectedAnnotPage = page;
                            if (e.getClickCount() == 2 && "Text".equals(a.getName("Subtype"))) {
                                editNote(page, a);
                            }
                            repaint();
                            return;
                        }
                        selectedAnnot = null;
                        // pictures: a handle of the selected one, the selected one itself, any other
                        int hnd = selImagePage == page ? handleAt(d) : 0;
                        PageImage im = hnd > 0 ? selImage
                            : selImage != null && selImagePage == page && selImage.contains(d) ? selImage : pictureAt(page, d);
                        if (im != null) {
                            if (!im.sameAs(selImage) || selImagePage != page) {
                                selectImage(page, im);
                            }
                            if (e.getClickCount() == 2) {
                                imageGrab = -1;
                                panel.images.edit(page, im);
                                return;
                            }
                            imageGrab = hnd;
                            imageGrabFrom = d;
                            imageTo = null;
                            return;
                        }
                        if (selImage != null) {
                            clearImageSelection();
                        }
                    }
                    PageText t = doc.text(page);
                    int idx = t.indexAt(d.getX(), d.getY());
                    if (idx < 0) {
                        clearSelection();
                        return;
                    }
                    if (e.getClickCount() == 2) {
                        int[] w = t.wordAt(Math.min(idx, t.text.length() - 1));
                        selPage = page;
                        selStart = w[0];
                        selEnd = w[1];
                    } else if (e.getClickCount() >= 3) {
                        int[] b = t.blockRange(Math.min(idx, t.text.length() - 1));
                        selPage = page;
                        selStart = b[0];
                        selEnd = b[1];
                    } else if (e.isShiftDown() && selPage == page) {
                        selEnd = idx;
                    } else {
                        selPage = page;
                        selStart = idx;
                        selEnd = idx;
                    }
                    repaint();
                }
                case NOTE -> {
                    String s = JOptionPane.showInputDialog(PdfView.this, "Note text:", "Note",
                                                           JOptionPane.PLAIN_MESSAGE);
                    if (s != null && !s.isBlank()) {
                        doc.history.begin("Add a note");
                        try {
                            Annotator.note(doc.history, doc.page(page), page, d, s, panel.noteColor);
                            doc.history.commit();
                        } catch (RuntimeException ex) {
                            doc.history.rollback();
                            panel.error("Could not add the note: " + ex.getMessage(), ex);
                        }
                        panel.documentEdited(page);
                    }
                }
                case INK -> {
                    if (drawPage != page) {
                        flushInk();
                    }
                    drawPage = page;
                    ink = new ArrayList<>();
                    ink.add(d);
                }
                case RECT, ELLIPSE, LINE, ARROW, REDACT -> {
                    drawPage = page;
                    dragStart = d;
                    dragEnd = d;
                }
                default -> { }
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (doc == null) {
                return;
            }
            if (panFrom != null) {
                Point now = e.getLocationOnScreen();
                if (getParent() instanceof JViewport vp) {
                    int nx = Math.max(0, Math.min(totalW - vp.getWidth(), panViewFrom.x - (now.x - panFrom.x)));
                    int ny = Math.max(0, Math.min(totalH - vp.getHeight(), panViewFrom.y - (now.y - panFrom.y)));
                    vp.setViewPosition(new Point(nx, ny));
                }
                return;
            }
            if (editor != null) {
                Point2D d = toDisplay(editor.pageIndex, e.getPoint());
                editor.mouseDragged(d);
                autoscroll(e);
                return;
            }
            switch (tool) {
                case SELECT, HIGHLIGHT -> {
                    if (imageGrab >= 0 && selImage != null && tool == Tool.SELECT) {
                        imageTo = dragged(toDisplay(selImagePage, e.getPoint()), e.isShiftDown());
                        autoscroll(e);
                        repaint();
                        return;
                    }
                    if (selPage < 0) {
                        return;
                    }
                    Point2D d = toDisplay(selPage, e.getPoint());
                    int idx = doc.text(selPage).indexAt(d.getX(), d.getY());
                    if (idx >= 0) {
                        selEnd = idx;
                        repaint();
                    }
                    autoscroll(e);
                }
                case INK -> {
                    if (ink != null && drawPage >= 0) {
                        ink.add(toDisplay(drawPage, e.getPoint()));
                        repaint();
                    }
                }
                case RECT, ELLIPSE, LINE, ARROW, REDACT -> {
                    if (dragStart != null && drawPage >= 0) {
                        dragEnd = toDisplay(drawPage, e.getPoint());
                        if (e.isShiftDown() && (tool == Tool.RECT || tool == Tool.ELLIPSE)) {
                            double s = Math.max(Math.abs(dragEnd.getX() - dragStart.getX()),
                                                Math.abs(dragEnd.getY() - dragStart.getY()));
                            dragEnd = new Point2D.Double(dragStart.getX() + Math.signum(dragEnd.getX() - dragStart.getX()) * s,
                                                         dragStart.getY() + Math.signum(dragEnd.getY() - dragStart.getY()) * s);
                        }
                        repaint();
                    }
                }
                default -> { }
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (panFrom != null) {
                panFrom = null;
                updateCursor(e);
                return;
            }
            if (doc == null) {
                return;
            }
            if (editor != null) {
                editor.mouseReleased();
                return;
            }
            boolean click = pressView != null && pressView.distance(e.getPoint()) < 4;
            switch (tool) {
                case SELECT -> {
                    if (imageGrab >= 0 && selImage != null) {
                        int grab = imageGrab;
                        AffineTransform to = imageTo;
                        imageGrab = -1;
                        imageTo = null;
                        if (to != null && !click && moved(to, selImage.toUser)) {
                            panel.images.place(selImagePage, selImage, to, grab == 0 ? "Move picture" : "Resize picture");
                        }
                        repaint();
                        return;
                    }
                    if (click && hoverLink != null && (selStart == selEnd)) {
                        follow(hoverLink);
                    } else if (selStart != selEnd) {
                        panel.selectionChanged();
                    }
                }
                case HIGHLIGHT -> {
                    if (selStart != selEnd) {
                        markupSelection("Highlight", panel.highlightColor);
                    }
                }
                case INK -> {
                    if (ink != null) {
                        inkStrokes.add(ink);
                        ink = null;
                        // a few strokes make one drawing; it is written when the pen pauses
                        inkTimer.restart();
                    }
                }
                case RECT, ELLIPSE, LINE, ARROW -> {
                    if (dragStart != null && dragEnd != null && dragStart.distance(dragEnd) > 2) {
                        String kind = switch (tool) {
                            case RECT -> "Square";
                            case ELLIPSE -> "Circle";
                            case ARROW -> "Arrow";
                            default -> "Line";
                        };
                        doc.history.begin("Add a shape");
                        try {
                            Annotator.shape(doc.history, doc.page(drawPage), drawPage, kind, dragStart, dragEnd,
                                            panel.drawColor.getRGB() & 0xffffff, panel.fillShapes ? panel.drawColor.getRGB()
                                                & 0xffffff : null, panel.drawWidth);
                            doc.history.commit();
                        } catch (RuntimeException ex) {
                            doc.history.rollback();
                            panel.error("Could not add the shape: " + ex.getMessage(), ex);
                        }
                        panel.documentEdited(drawPage);
                    }
                    dragStart = dragEnd = null;
                    drawPage = -1;
                    repaint();
                }
                case REDACT -> {
                    if (dragStart != null && dragEnd != null && dragStart.distance(dragEnd) > 2) {
                        Rectangle2D box = box(dragStart, dragEnd);
                        PageText t = doc.text(drawPage);
                        List<TextGlyph> inside = new ArrayList<>();
                        for (TextGlyph g : t.glyphs) {
                            Rectangle2D b = g.bounds();
                            if (box.contains(b.getCenterX(), b.getCenterY())) {
                                inside.add(g);
                            }
                        }
                        try {
                            Redactor.redactGlyphs(doc.history, doc.pdf, doc.page(drawPage), drawPage, doc.renderer, inside,
                                                  List.of(box), 0);
                            panel.status("Area redacted: " + inside.size() + " characters removed from the file");
                        } catch (RuntimeException ex) {
                            panel.error("Could not redact: " + ex.getMessage(), ex);
                        }
                        panel.documentEdited(drawPage);
                    }
                    dragStart = dragEnd = null;
                    drawPage = -1;
                    repaint();
                }
                default -> { }
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            if (doc == null) {
                return;
            }
            int page = pageAt(e.getPoint());
            TextBlock hb = null;
            SpdfDoc.Link link = null;
            PageImage hi = null;
            if (page >= 0) {
                Point2D d = toDisplay(page, e.getPoint());
                if (tool == Tool.EDIT && (editor == null || !editor.hitBox(d))) {
                    hb = doc.text(page).blockAt(d.getX(), d.getY());
                }
                if (tool == Tool.SELECT && imageGrab < 0) {
                    hi = pictureAt(page, d);
                }
                if (tool == Tool.SELECT) {
                    if (pageOf == null) {
                        pageOf = doc.pageIndexByObject();
                    }
                    for (SpdfDoc.Link l : doc.links(page, pageOf)) {
                        if (l.area().contains(d)) {
                            link = l;
                            break;
                        }
                    }
                }
            }
            if (hb != hoverBlock || page != hoverPage || link != hoverLink && (link == null || hoverLink == null
                                                                              || !link.area().equals(hoverLink.area()))) {
                hoverBlock = hb;
                hoverPage = page;
                hoverLink = link;
                repaint();
            }
            if (hi == null ? hoverImage != null : !hi.sameAs(hoverImage) || hoverImagePage != page) {
                hoverImage = hi;
                hoverImagePage = page;
                repaint();
            }
            updateCursor(e);
        }

        @Override
        public void mouseExited(MouseEvent e) {
            preview.hideIt();
        }
    }

    private final Timer inkTimer = new Timer(900, e -> flushInk());

    {
        inkTimer.setRepeats(false);
    }

    /** Writes the strokes drawn so far as one ink annotation. */
    void flushInk() {
        inkTimer.stop();
        if (inkStrokes.isEmpty() || drawPage < 0) {
            inkStrokes.clear();
            return;
        }
        int page = drawPage;
        doc.history.begin("Freehand drawing");
        try {
            Annotator.ink(doc.history, doc.page(page), page, new ArrayList<>(inkStrokes),
                          panel.drawColor.getRGB() & 0xffffff, panel.drawWidth, panel.drawColor.getAlpha() / 255f);
            doc.history.commit();
        } catch (RuntimeException ex) {
            doc.history.rollback();
            panel.error("Could not add the drawing: " + ex.getMessage(), ex);
        }
        inkStrokes.clear();
        drawPage = -1;
        panel.documentEdited(page);
    }

    private void autoscroll(MouseEvent e) {
        Rectangle r = new Rectangle(e.getX() - 10, e.getY() - 10, 20, 20);
        scrollRectToVisible(r);
    }

    private void updateCursor(MouseEvent e) {
        if (doc == null) {
            setCursor(Cursor.getDefaultCursor());
            return;
        }
        int page = pageAt(e.getPoint());
        Cursor c = Cursor.getDefaultCursor();
        if (tool == Tool.HAND) {
            c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        } else if (editor != null && page == editor.pageIndex) {
            Point2D d = toDisplay(page, e.getPoint());
            if (editor.hitResize(d, zoom)) {
                c = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            } else if (editor.hitMove(d, zoom)) {
                c = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
            } else if (editor.hitBox(d)) {
                c = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR);
            }
        } else if (tool == Tool.SELECT && page >= 0 && page == selImagePage && selImage != null
                   && (handleAt(toDisplay(page, e.getPoint())) > 0 || selImage.contains(toDisplay(page, e.getPoint())))) {
            int h = handleAt(toDisplay(page, e.getPoint()));
            c = Cursor.getPredefinedCursor(switch (h) {
                case 1 -> Cursor.NW_RESIZE_CURSOR;
                case 2 -> Cursor.N_RESIZE_CURSOR;
                case 3 -> Cursor.NE_RESIZE_CURSOR;
                case 4 -> Cursor.E_RESIZE_CURSOR;
                case 5 -> Cursor.SE_RESIZE_CURSOR;
                case 6 -> Cursor.S_RESIZE_CURSOR;
                case 7 -> Cursor.SW_RESIZE_CURSOR;
                case 8 -> Cursor.W_RESIZE_CURSOR;
                default -> Cursor.MOVE_CURSOR;
            });
        } else if (tool == Tool.SELECT && hoverImage != null && hoverImagePage == page) {
            c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        } else if (hoverLink != null) {
            c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        } else if (page >= 0) {
            c = switch (tool) {
                case SELECT, HIGHLIGHT, EDIT, ADDTEXT -> Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR);
                case INK, RECT, ELLIPSE, LINE, ARROW, REDACT, NOTE -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
                default -> Cursor.getDefaultCursor();
            };
        }
        setCursor(c);
    }

    private void follow(SpdfDoc.Link l) {
        if (l.uri() != null) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(l.uri()));
            } catch (Exception ex) {
                panel.status("Link: " + l.uri());
            }
        } else if (l.page() >= 0) {
            panel.pushPosition();
            scrollToPage(l.page(), l.y());
            panel.status("Page " + doc.pageName(l.page()) + " — Alt+← to go back");
        }
    }

    private void editNote(int page, PdfDict a) {
        String cur = a.getText("Contents");
        String s = (String) JOptionPane.showInputDialog(this, "Note text:", "Note", JOptionPane.PLAIN_MESSAGE,
                                                       null, null, cur == null ? "" : cur);
        if (s == null) {
            return;
        }
        doc.history.begin("Edit note");
        try {
            com.sphere.components.spdf.cos.PdfObject raw = null;
            for (com.sphere.components.spdf.cos.PdfObject o : doc.page(page).annotsForWriting().rawList()) {
                if (com.sphere.components.spdf.cos.PdfRef.deref(o) == a) {
                    raw = o;
                }
            }
            if (raw instanceof com.sphere.components.spdf.cos.PdfRef r) {
                doc.history.touch(r.num);
                a = (PdfDict) doc.pdf.getObject(r.num);
            } else {
                doc.history.touchPage(page, doc.page(page).objNum());
            }
            a.put("Contents", com.sphere.components.spdf.cos.PdfString.text(s));
            doc.history.commit();
        } catch (RuntimeException ex) {
            doc.history.rollback();
        }
        panel.documentEdited(page);
    }

    private void contextMenu(MouseEvent e, int page, Point2D d) {
        JPopupMenu m = new JPopupMenu();
        PageImage pic = selImage != null && selImagePage == page && selImage.contains(d) ? selImage
            : tool == Tool.SELECT || tool == Tool.HAND ? pictureAt(page, d) : doc.imageAt(page, d);
        if (pic != null && !pic.stencil) {
            selectImage(page, pic);
            ImageTools it = panel.images;
            JMenuItem edit = item("Edit in Sphere Image…", () -> it.edit(page, pic));
            edit.setFont(edit.getFont().deriveFont(Font.BOLD));
            m.add(edit);
            m.add(item("Replace with a File…", () -> it.replaceWithFile(page, pic)));
            m.add(item("Save Picture As…", () -> it.saveAs(page, pic)));
            m.add(item("Copy Picture", () -> it.copy(page, pic)));
            m.addSeparator();
            m.add(item("Rotate Right", () -> it.turn(page, pic, 1)));
            m.add(item("Rotate Left", () -> it.turn(page, pic, -1)));
            m.add(item("Flip Horizontally", () -> it.mirror(page, pic, true)));
            m.add(item("Flip Vertically", () -> it.mirror(page, pic, false)));
            m.addSeparator();
            m.add(item("Make Gray", () -> it.pixelOp(page, pic, "Make picture gray",
                                                      com.sphere.components.spdf.edit.ImageOps::grayscale)));
            m.add(item("Auto Enhance", () -> it.pixelOp(page, pic, "Enhance picture",
                                                         com.sphere.components.spdf.edit.ImageOps::autoEnhance)));
            m.add(item("Make White Transparent", () -> it.pixelOp(page, pic, "Make the picture's white transparent",
                                                                   com.sphere.components.spdf.edit.ImageOps::removeWhite)));
            if (pic.ppi() > 450) {
                m.add(item("Shrink to 300 ppi (" + Math.round(pic.ppi()) + " now)", () -> it.shrink(page, pic, 300)));
            } else if (pic.ppi() > 225) {
                m.add(item("Shrink to 150 ppi (" + Math.round(pic.ppi()) + " now)", () -> it.shrink(page, pic, 150)));
            }
            m.addSeparator();
            m.add(item("Delete Picture", () -> it.delete(page, pic)));
            m.add(item("Picture Properties…", () -> it.properties(page, pic)));
            m.show(this, e.getX(), e.getY());
            return;
        }
        boolean hasSel = selPage == page && selStart != selEnd;
        if (hasSel) {
            String sel = selectedText();
            m.add(item("Copy", () -> copySelection()));
            m.add(item("Highlight", () -> markupSelection("Highlight", panel.highlightColor)));
            m.add(item("Underline", () -> markupSelection("Underline", 0x1565C0)));
            m.add(item("Strike Out", () -> markupSelection("StrikeOut", 0xC62828)));
            m.add(item("Redact (remove from the file)", this::redactSelection));
            m.addSeparator();
            String q = sel.length() > 40 ? sel.substring(0, 40) + "…" : sel;
            m.add(item("Search for \"" + q.replace('\n', ' ') + "\"", () -> panel.search(sel.trim())));
            m.add(item("Replace All…", () -> panel.replaceDialog(sel.trim())));
            m.addSeparator();
        }
        PageText t = doc.text(page);
        TextBlock b = t.blockAt(d.getX(), d.getY());
        if (b != null) {
            m.add(item("Edit This Paragraph", () -> {
                panel.setTool(Tool.EDIT);
                editBlock(page, b, d);
            }));
            m.add(item("Copy This Paragraph", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(b.text()), null)));
        }
        m.add(item("Add Text Here", () -> {
            panel.setTool(Tool.ADDTEXT);
            newTextBox(page, d);
        }));
        m.add(item("Insert a Picture Here…", () -> panel.images.insertFromFile(page, d)));
        if (Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            m.add(item("Paste Picture Here", () -> panel.images.paste(page, d)));
        }
        m.add(item("Add a Note Here", () -> {
            String s = JOptionPane.showInputDialog(this, "Note text:");
            if (s != null && !s.isBlank()) {
                doc.history.begin("Add a note");
                try {
                    Annotator.note(doc.history, doc.page(page), page, d, s, panel.noteColor);
                    doc.history.commit();
                } catch (RuntimeException ex) {
                    doc.history.rollback();
                }
                panel.documentEdited(page);
            }
        }));
        PdfDict a = Annotator.at(doc.page(page), d);
        if (a != null) {
            m.addSeparator();
            m.add(item("Delete Annotation", () -> {
                Annotator.delete(doc.history, doc.pdf, doc.page(page), page, a);
                selectedAnnot = null;
                panel.documentEdited(page);
            }));
        }
        m.addSeparator();
        m.add(item("Rotate Page Right", () -> panel.rotatePages(List.of(page), 90)));
        m.add(item("Copy All Text on This Page", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(t.text), null)));
        m.show(this, e.getX(), e.getY());
    }

    private static JMenuItem item(String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        return i;
    }

    /* ================================================================= keys */

    private final class Keys extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            if (editor != null) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancelEdit();
                    e.consume();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isControlDown()) {
                    commitEdit();
                    e.consume();
                    return;
                }
                if (editor.keyPressed(e)) {
                    caretOn = true;
                    blink.restart();
                    e.consume();
                }
                return;
            }
            boolean ctrl = e.isControlDown() || e.isMetaDown();
            if (selImage != null && doc != null && !ctrl) {
                // the selected picture: nudged by the arrows (Shift: ten points), edited with Enter
                double step = e.isShiftDown() ? 10 : 1;
                double dx = e.getKeyCode() == KeyEvent.VK_LEFT ? -step : e.getKeyCode() == KeyEvent.VK_RIGHT ? step : 0;
                double dy = e.getKeyCode() == KeyEvent.VK_UP ? -step : e.getKeyCode() == KeyEvent.VK_DOWN ? step : 0;
                if (dx != 0 || dy != 0) {
                    Point2D du = doc.page(selImagePage).displayToUser().deltaTransform(new Point2D.Double(dx, dy), null);
                    AffineTransform t = AffineTransform.getTranslateInstance(du.getX(), du.getY());
                    t.concatenate(selImage.toUser);
                    panel.images.place(selImagePage, selImage, t, "Move picture");
                    e.consume();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    panel.images.edit(selImagePage, selImage);
                    e.consume();
                    return;
                }
            }
            switch (e.getKeyCode()) {
                case KeyEvent.VK_C -> {
                    if (ctrl) {
                        if (selImage != null && selStart == selEnd) {
                            panel.images.copy(selImagePage, selImage);
                        } else {
                            copySelection();
                        }
                        e.consume();
                    }
                }
                case KeyEvent.VK_V -> {
                    if (ctrl && doc != null) {
                        Point mouse = getMousePosition();
                        int page = mouse == null ? -1 : pageAt(mouse);
                        if (page >= 0) {
                            panel.images.paste(page, toDisplay(page, mouse));
                        } else {
                            panel.images.paste(currentPage(), null);
                        }
                        e.consume();
                    }
                }
                case KeyEvent.VK_A -> {
                    if (ctrl && doc != null) {
                        int p = currentPage();
                        selPage = p;
                        selStart = 0;
                        selEnd = doc.text(p).text.length();
                        repaint();
                        e.consume();
                    }
                }
                case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                    if (selectedAnnot != null) {
                        Annotator.delete(doc.history, doc.pdf, doc.page(selectedAnnotPage), selectedAnnotPage, selectedAnnot);
                        int p = selectedAnnotPage;
                        selectedAnnot = null;
                        panel.documentEdited(p);
                        e.consume();
                    } else if (selImage != null) {
                        panel.images.delete(selImagePage, selImage);
                        e.consume();
                    }
                }
                case KeyEvent.VK_ESCAPE -> {
                    clearSelection();
                    selectedAnnot = null;
                    clearImageSelection();
                    flushInk();
                    repaint();
                }
                case KeyEvent.VK_PAGE_DOWN -> {
                    scrollToPage(Math.min(doc.pageCount() - 1, currentPage() + 1), 0);
                    e.consume();
                }
                case KeyEvent.VK_PAGE_UP -> {
                    scrollToPage(Math.max(0, currentPage() - 1), 0);
                    e.consume();
                }
                case KeyEvent.VK_HOME -> {
                    if (ctrl) {
                        scrollToPage(0, 0);
                        e.consume();
                    }
                }
                case KeyEvent.VK_END -> {
                    if (ctrl) {
                        scrollToPage(doc.pageCount() - 1, 0);
                        e.consume();
                    }
                }
                default -> { }
            }
        }

        @Override
        public void keyTyped(KeyEvent e) {
            if (editor != null && editor.keyTyped(e)) {
                caretOn = true;
                blink.restart();
                e.consume();
            }
        }
    }

    /* ================================================================= Scrollable */

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(800, 600);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 40;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - 40 : visibleRect.width - 40;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport vp && vp.getWidth() > totalW;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport vp && vp.getHeight() > totalH;
    }
}
