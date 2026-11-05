package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.cos.PdfRef;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.cos.PdfString;
import com.sphere.components.spdf.font.PdfFont;
import com.sphere.components.spdf.font.Type3Font;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Runs a page's content (8, 9) on a Java2D surface: graphics state, paths,
 * clipping, colors and patterns, shadings, images, form XObjects, text in
 * every font kind (Type 3 procedures included), optional content and the
 * annotations' appearances. The same pass can hand every glyph to a sink,
 * which is how text is extracted with its exact geometry, and can leave out
 * glyphs (those being edited, which the editor draws itself).
 */
public final class PageRenderer {

    /** Receives each glyph shown. */
    public interface GlyphSink {
        void glyph(TextGlyph g);
    }

    /** Which glyphs not to draw: by stream (-1 page), operation, index in the operation. */
    public interface GlyphFilter {
        boolean hide(int streamId, int opIndex, int glyphIndex);
    }

    /** Receives each picture drawn (with painting off too). */
    public interface ImageSink {
        void image(PageImage i);
    }

    public static final class Options {
        public boolean paint = true;
        public boolean annotations = true;
        public GlyphSink sink;
        public ImageSink images;
        public GlyphFilter hide;
        /** Annotations not to draw (being moved or edited), by their dictionary. */
        public java.util.Set<PdfDict> hiddenAnnotations;
        /** Stop early (a newer render was asked for). */
        public java.util.function.BooleanSupplier canceled = () -> false;
    }

    private final RenderCache cache;

    public PageRenderer(RenderCache cache) {
        this.cache = cache;
    }

    /** The page's operations, parsed once per content object. */
    public List<Op> pageOps(PdfPage page) {
        PdfObject c = page.dict().get("Contents");
        if (c instanceof PdfStream s) {
            return cache.form(s);
        }
        return ContentParser.parse(page.contents());
    }

    /**
     * Draws a page. displayToDevice maps display space (points, y down) to the
     * surface; null with paint off (text extraction).
     */
    public void render(Graphics2D g, PdfPage page, AffineTransform displayToDevice, Options opt) {
        AffineTransform userToDisplay = page.userToDisplay();
        AffineTransform base = new AffineTransform(displayToDevice == null ? new AffineTransform() : displayToDevice);
        base.concatenate(userToDisplay);
        Run run = new Run(g, opt, userToDisplay, base);
        if (g != null && opt.paint) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            // the visible part of the page, as a rectangle when it is one (Java2D clips to those fast)
            Rectangle2D crop = page.cropBox();
            run.gs.clip = run.simplest(base.createTransformedShape(crop));
        }
        PdfDict res = page.resources();
        try {
            run.ops(pageOps(page), res, -1, 0);
        } catch (RuntimeException e) {
            // a broken content stream shows what was drawn before the break
        }
        if (opt.annotations) {
            for (PdfDict a : page.annotations()) {
                if (opt.canceled.getAsBoolean()) {
                    break;
                }
                if (opt.hiddenAnnotations != null && opt.hiddenAnnotations.contains(a)) {
                    continue;
                }
                try {
                    run.annotation(a, page);
                } catch (RuntimeException e) {
                    // skip the broken annotation
                }
            }
        }
    }

    /* ================================================================= state */

    static final class GState implements Cloneable {
        AffineTransform ctm = new AffineTransform();
        Shape clip;
        PdfColorSpace fillCS = PdfColorSpace.GRAY;
        PdfColorSpace strokeCS = PdfColorSpace.GRAY;
        float[] fillC = { 0 };
        float[] strokeC = { 0 };
        int fillRgb;
        int strokeRgb;
        PdfDict fillPattern;
        PdfDict strokePattern;
        float lineWidth = 1;
        int cap;
        int join;
        float miter = 10;
        float[] dash;
        float dashPhase;
        float fillAlpha = 1;
        float strokeAlpha = 1;
        String blend = "Normal";
        float tc;
        float tw;
        float th = 1;
        float tl;
        PdfFont font;
        float fs = 1;
        int tr;
        float rise;
        /** The space patterns map to: the content stream's default coordinates. */
        AffineTransform patternBase;

        GState copy() {
            try {
                GState g = (GState) super.clone();
                g.ctm = new AffineTransform(ctm);
                return g;
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final ColorModel CM = new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000);
    /** Never modified: Graphics2D.setTransform copies what it is given. */
    private static final AffineTransform IDENTITY = new AffineTransform();
    private static final Shape UNSET = new Rectangle(-1, -1, 0, 0);

    /* ================================================================= one pass */

    private final class Run {
        final Graphics2D g;
        final Options opt;
        final AffineTransform userToDisplay;
        final AffineTransform base;
        GState gs = new GState();
        final ArrayDeque<GState> stack = new ArrayDeque<>();
        GeneralPath path = new GeneralPath(Path2D.WIND_NON_ZERO);
        int pendingClip;
        AffineTransform tm = new AffineTransform();
        AffineTransform tlm = new AffineTransform();
        GeneralPath textClip;
        int hiddenDepth;
        final ArrayDeque<Boolean> marked = new ArrayDeque<>();
        int seq;
        boolean uncoloredType3;
        /** The clip last handed to Java2D (UNSET: unknown): the same one is not handed again. */
        Shape lastClip = UNSET;
        int formDepth;

        /** Device space back to user space: what the matrix of a picture is in the page's own terms. */
        final AffineTransform baseInverse;

        Run(Graphics2D g, Options opt, AffineTransform userToDisplay, AffineTransform base) {
            this.g = g;
            this.opt = opt;
            this.userToDisplay = userToDisplay;
            this.base = base;
            gs.ctm = new AffineTransform(base);
            gs.patternBase = new AffineTransform(base);
            AffineTransform inv;
            try {
                inv = base.createInverse();
            } catch (NoninvertibleTransformException e) {
                inv = new AffineTransform();
            }
            baseInverse = inv;
        }

        /** Hands a picture about to be drawn to the image sink. */
        void recordImage(PdfDict d, String name, int objNum, int streamId, int opIndex) {
            if (opt.images == null || hiddenDepth > 0 || d == null) {
                return;
            }
            AffineTransform toUser = new AffineTransform(baseInverse);
            toUser.concatenate(gs.ctm);
            AffineTransform toDisplay = new AffineTransform(userToDisplay);
            toDisplay.concatenate(toUser);
            boolean stencil = d.getBool("ImageMask", d.getBool("IM", false));
            boolean alpha = d.has("SMask") || d.get("Mask") instanceof PdfStream || d.get("Mask") instanceof PdfArray;
            String codec = d instanceof PdfStream s ? s.imageFilter() : com.sphere.components.spdf.cos.Filters.imageFilter(d);
            opt.images.image(new PageImage(name, objNum, streamId, opIndex, toUser, toDisplay,
                                           d.getInt("Width", d.getInt("W", 0)), d.getInt("Height", d.getInt("H", 0)),
                                           codec, alpha, stencil, formDepth));
        }

        boolean painting() {
            return g != null && opt.paint && hiddenDepth == 0;
        }

        /* ------------------------------------------------------------- operations */

        void ops(List<Op> ops, PdfDict res, int streamId, int depth) {
            if (depth > 16) {
                return;
            }
            int stackFloor = stack.size();
            for (int i = 0; i < ops.size(); i++) {
                if ((i & 255) == 0 && opt.canceled.getAsBoolean()) {
                    return;
                }
                Op op = ops.get(i);
                try {
                    op(op, res, streamId, i, depth);
                } catch (RuntimeException e) {
                    // one bad operation does not stop the page
                }
            }
            // unbalanced q: restore what the stream left saved
            while (stack.size() > stackFloor) {
                gs = stack.pop();
            }
        }

        void op(Op op, PdfDict res, int streamId, int index, int depth) {
            String n = op.name;
            switch (n) {
                // ---------------- state
                case "q":
                    stack.push(gs.copy());
                    break;
                case "Q":
                    if (!stack.isEmpty()) {
                        gs = stack.pop();
                    }
                    break;
                case "cm":
                    if (op.args.length >= 6) {
                        gs.ctm.concatenate(new AffineTransform(op.f(0), op.f(1), op.f(2), op.f(3), op.f(4), op.f(5)));
                    }
                    break;
                case "w":
                    gs.lineWidth = op.f(0);
                    break;
                case "J":
                    gs.cap = (int) op.f(0);
                    break;
                case "j":
                    gs.join = (int) op.f(0);
                    break;
                case "M":
                    gs.miter = Math.max(1, op.f(0));
                    break;
                case "d":
                    if (op.args.length >= 2 && op.args[0] instanceof PdfArray a) {
                        float[] d = a.toFloats();
                        boolean any = false;
                        for (float v : d) {
                            any |= v > 0;
                        }
                        gs.dash = d.length == 0 || !any ? null : d;
                        gs.dashPhase = op.f(1);
                    }
                    break;
                case "gs":
                    extGState(res, op.nameArg(0));
                    break;
                case "ri": case "i":
                    break;
                // ---------------- paths
                case "m":
                    path.moveTo(op.f(0), op.f(1));
                    break;
                case "l":
                    lineTo(op.f(0), op.f(1));
                    break;
                case "c":
                    curveTo(op.f(0), op.f(1), op.f(2), op.f(3), op.f(4), op.f(5));
                    break;
                case "v": {
                    Point2D p = path.getCurrentPoint();
                    if (p != null) {
                        path.curveTo(p.getX(), p.getY(), op.f(0), op.f(1), op.f(2), op.f(3));
                    }
                    break;
                }
                case "y":
                    curveTo(op.f(0), op.f(1), op.f(2), op.f(3), op.f(2), op.f(3));
                    break;
                case "h":
                    if (path.getCurrentPoint() != null) {
                        path.closePath();
                    }
                    break;
                case "re": {
                    float x = op.f(0);
                    float y = op.f(1);
                    float w = op.f(2);
                    float h = op.f(3);
                    path.moveTo(x, y);
                    path.lineTo(x + w, y);
                    path.lineTo(x + w, y + h);
                    path.lineTo(x, y + h);
                    path.closePath();
                    break;
                }
                case "S":
                    stroke();
                    endPath();
                    break;
                case "s":
                    closeIfOpen();
                    stroke();
                    endPath();
                    break;
                case "f": case "F":
                    fill(Path2D.WIND_NON_ZERO);
                    endPath();
                    break;
                case "f*":
                    fill(Path2D.WIND_EVEN_ODD);
                    endPath();
                    break;
                case "B":
                    fill(Path2D.WIND_NON_ZERO);
                    stroke();
                    endPath();
                    break;
                case "B*":
                    fill(Path2D.WIND_EVEN_ODD);
                    stroke();
                    endPath();
                    break;
                case "b":
                    closeIfOpen();
                    fill(Path2D.WIND_NON_ZERO);
                    stroke();
                    endPath();
                    break;
                case "b*":
                    closeIfOpen();
                    fill(Path2D.WIND_EVEN_ODD);
                    stroke();
                    endPath();
                    break;
                case "n":
                    endPath();
                    break;
                case "W":
                    pendingClip = 1;
                    break;
                case "W*":
                    pendingClip = 2;
                    break;
                // ---------------- colors
                case "CS":
                    gs.strokeCS = PdfColorSpace.resolve(op.args.length > 0 ? op.args[0] : null, res, cache.colorSpaces);
                    gs.strokeC = gs.strokeCS.initial();
                    gs.strokePattern = null;
                    gs.strokeRgb = gs.strokeCS.isPattern() ? 0 : gs.strokeCS.rgb(gs.strokeC);
                    break;
                case "cs":
                    gs.fillCS = PdfColorSpace.resolve(op.args.length > 0 ? op.args[0] : null, res, cache.colorSpaces);
                    gs.fillC = gs.fillCS.initial();
                    gs.fillPattern = null;
                    gs.fillRgb = gs.fillCS.isPattern() ? 0 : gs.fillCS.rgb(gs.fillC);
                    break;
                case "SC": case "SCN":
                    if (!uncoloredType3) {
                        setColor(op, res, true);
                    }
                    break;
                case "sc": case "scn":
                    if (!uncoloredType3) {
                        setColor(op, res, false);
                    }
                    break;
                case "G": case "g": case "RG": case "rg": case "K": case "k": {
                    if (uncoloredType3) {
                        break;
                    }
                    boolean stroke = Character.isUpperCase(n.charAt(0));
                    PdfColorSpace cs = n.equalsIgnoreCase("g") ? PdfColorSpace.GRAY
                        : n.equalsIgnoreCase("rg") ? PdfColorSpace.RGB : PdfColorSpace.CMYK;
                    float[] c = new float[cs.components()];
                    for (int k = 0; k < c.length; k++) {
                        c[k] = op.f(k);
                    }
                    if (stroke) {
                        gs.strokeCS = cs;
                        gs.strokeC = c;
                        gs.strokeRgb = cs.rgb(c);
                        gs.strokePattern = null;
                    } else {
                        gs.fillCS = cs;
                        gs.fillC = c;
                        gs.fillRgb = cs.rgb(c);
                        gs.fillPattern = null;
                    }
                    break;
                }
                // ---------------- text
                case "BT":
                    tm = new AffineTransform();
                    tlm = new AffineTransform();
                    textClip = null;
                    break;
                case "ET":
                    if (textClip != null) {
                        intersectClip(textClip);
                        textClip = null;
                    }
                    break;
                case "Tc":
                    gs.tc = op.f(0);
                    break;
                case "Tw":
                    gs.tw = op.f(0);
                    break;
                case "Tz":
                    gs.th = op.f(0) / 100f;
                    break;
                case "TL":
                    gs.tl = op.f(0);
                    break;
                case "Tf": {
                    String fname = op.nameArg(0);
                    PdfDict fonts = res == null ? null : res.getDict("Font");
                    PdfDict fd = fonts == null || fname == null ? null : fonts.getDict(fname);
                    if (fd != null) {
                        gs.font = cache.font(fd);
                    }
                    gs.fs = op.f(1);
                    break;
                }
                case "Tr":
                    gs.tr = (int) op.f(0);
                    break;
                case "Ts":
                    gs.rise = op.f(0);
                    break;
                case "Td":
                    tlm.concatenate(AffineTransform.getTranslateInstance(op.f(0), op.f(1)));
                    tm = new AffineTransform(tlm);
                    break;
                case "TD":
                    gs.tl = -op.f(1);
                    tlm.concatenate(AffineTransform.getTranslateInstance(op.f(0), op.f(1)));
                    tm = new AffineTransform(tlm);
                    break;
                case "Tm":
                    tlm = new AffineTransform(op.f(0), op.f(1), op.f(2), op.f(3), op.f(4), op.f(5));
                    tm = new AffineTransform(tlm);
                    break;
                case "T*":
                    nextLine();
                    break;
                case "Tj":
                    if (op.args.length > 0 && op.args[0] instanceof PdfString s) {
                        show(s.getBytes(), res, streamId, index, 0);
                    }
                    break;
                case "'":
                    nextLine();
                    if (op.args.length > 0 && op.args[0] instanceof PdfString s) {
                        show(s.getBytes(), res, streamId, index, 0);
                    }
                    break;
                case "\"":
                    gs.tw = op.f(0);
                    gs.tc = op.f(1);
                    nextLine();
                    if (op.args.length > 2 && op.args[2] instanceof PdfString s) {
                        show(s.getBytes(), res, streamId, index, 0);
                    }
                    break;
                case "TJ":
                    if (op.args.length > 0 && op.args[0] instanceof PdfArray a) {
                        int glyphs = 0;
                        for (int k = 0; k < a.size(); k++) {
                            PdfObject e = a.get(k);
                            if (e instanceof PdfString s) {
                                glyphs = show(s.getBytes(), res, streamId, index, glyphs);
                            } else if (e instanceof PdfNumber num) {
                                float adj = -num.floatValue() / 1000f * gs.fs;
                                if (gs.font != null && gs.font.vertical) {
                                    tm.concatenate(AffineTransform.getTranslateInstance(0, adj));
                                } else {
                                    tm.concatenate(AffineTransform.getTranslateInstance(adj * gs.th, 0));
                                }
                            }
                        }
                    }
                    break;
                case "d0":
                    break;
                case "d1":
                    uncoloredType3 = true;
                    break;
                // ---------------- objects
                case "Do":
                    xobject(res, op.nameArg(0), depth, streamId, index);
                    break;
                case "sh":
                    shading(res, op.nameArg(0));
                    break;
                case "BI":
                    recordImage(op.inlineDict, null, -1, streamId, index);
                    inlineImage(op, res);
                    break;
                // ---------------- marked content
                case "BDC": {
                    boolean hide = false;
                    if ("OC".equals(op.nameArg(0)) && op.args.length > 1) {
                        PdfObject props = op.args[1];
                        if (props instanceof PdfName pn) {
                            PdfDict pd = res == null ? null : res.getDict("Properties");
                            props = pd == null ? null : pd.get(pn.name);
                        }
                        hide = props != null && !cache.visible(props);
                    }
                    marked.push(hide);
                    if (hide) {
                        hiddenDepth++;
                    }
                    break;
                }
                case "BMC":
                    marked.push(false);
                    break;
                case "EMC":
                    if (!marked.isEmpty() && marked.pop()) {
                        hiddenDepth--;
                    }
                    break;
                default:
                    break;
            }
        }

        void lineTo(float x, float y) {
            if (path.getCurrentPoint() == null) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        void curveTo(float a, float b, float c, float d, float e, float f) {
            if (path.getCurrentPoint() == null) {
                path.moveTo(a, b);
            }
            path.curveTo(a, b, c, d, e, f);
        }

        void closeIfOpen() {
            if (path.getCurrentPoint() != null) {
                path.closePath();
            }
        }

        void endPath() {
            if (pendingClip != 0) {
                path.setWindingRule(pendingClip == 2 ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                intersectClip(gs.ctm.createTransformedShape(path));
                pendingClip = 0;
            }
            path = new GeneralPath(Path2D.WIND_NON_ZERO);
        }

        /** A shape as a Rectangle2D when it is an axis-aligned rectangle, else as it is. */
        Shape simplest(Shape s) {
            if (s instanceof Rectangle2D || s == null) {
                return s;
            }
            if (s instanceof Area a) {
                return a.isRectangular() ? a.getBounds2D() : a;
            }
            Rectangle2D r = rectOf(s);
            return r != null ? r : s;
        }

        void intersectClip(Shape deviceShape) {
            if (g == null) {
                return;
            }
            if (gs.clip == null) {
                gs.clip = simplest(deviceShape);
                return;
            }
            Rectangle2D a = gs.clip instanceof Rectangle2D r ? r : null;
            Rectangle2D b = deviceShape instanceof Rectangle2D r ? r : null;
            if (a == null && gs.clip instanceof Path2D) {
                a = rectOf(gs.clip);
            }
            if (b == null) {
                b = rectOf(deviceShape);
            }
            if (a != null && b != null) {
                gs.clip = a.createIntersection(b);
                return;
            }
            Area area = new Area(gs.clip);
            area.intersect(new Area(deviceShape));
            gs.clip = simplest(area);
        }

        /** A shape that is an axis-aligned rectangle, as one; else null. */
        Rectangle2D rectOf(Shape s) {
            java.awt.geom.PathIterator it = s.getPathIterator(null);
            double[] c = new double[6];
            double[] xs = new double[5];
            double[] ys = new double[5];
            int n = 0;
            while (!it.isDone()) {
                int t = it.currentSegment(c);
                if (t == java.awt.geom.PathIterator.SEG_CLOSE) {
                    it.next();
                    continue;
                }
                if (t != java.awt.geom.PathIterator.SEG_MOVETO && t != java.awt.geom.PathIterator.SEG_LINETO || n >= 5) {
                    return null;
                }
                xs[n] = c[0];
                ys[n] = c[1];
                n++;
                it.next();
            }
            if (n == 5 && Math.abs(xs[4] - xs[0]) < 1e-6 && Math.abs(ys[4] - ys[0]) < 1e-6) {
                n = 4;
            }
            if (n != 4) {
                return null;
            }
            boolean hv = (xs[0] == xs[1] && ys[1] == ys[2] && xs[2] == xs[3] && ys[3] == ys[0])
                || (ys[0] == ys[1] && xs[1] == xs[2] && ys[2] == ys[3] && xs[3] == xs[0]);
            if (!hv) {
                return null;
            }
            double x0 = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
            double x1 = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
            double y0 = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
            double y1 = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
            return new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
        }

        /** Sets the clip and composite for drawing; the transform is left to the caller. */
        void prepare(float alpha) {
            g.setTransform(IDENTITY);
            // Java2D turns every clip it is given into a region of spans: a page of a few thousand
            // operations under one clip pays that once, not once per operation
            if (gs.clip != lastClip) {
                g.setClip(gs.clip);
                lastClip = gs.clip;
            }
            Composite c = BlendComposite.of(gs.blend, alpha);
            if (c == null) {
                c = alpha >= 1 ? AlphaComposite.SrcOver : AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                                                                                     Math.max(0, alpha));
            }
            g.setComposite(c);
        }

        Paint fillPaint() {
            if (gs.fillPattern != null) {
                Paint p = patternPaint(gs.fillPattern, gs.fillC, gs.fillCS, gs.fillAlpha);
                if (p != null) {
                    return p;
                }
            }
            return new Color(gs.fillRgb);
        }

        Paint strokePaint() {
            if (gs.strokePattern != null) {
                Paint p = patternPaint(gs.strokePattern, gs.strokeC, gs.strokeCS, gs.strokeAlpha);
                if (p != null) {
                    return p;
                }
            }
            return new Color(gs.strokeRgb);
        }

        void fill(int rule) {
            if (!painting() || path.getCurrentPoint() == null && pendingClip == 0) {
                return;
            }
            if (path.getCurrentPoint() == null) {
                return;
            }
            path.setWindingRule(rule);
            prepare(gs.fillAlpha);
            g.setPaint(fillPaint());
            Shape s = gs.ctm.createTransformedShape(path);
            g.fill(s);
        }

        void stroke() {
            if (!painting() || path.getCurrentPoint() == null) {
                return;
            }
            prepare(gs.strokeAlpha);
            g.setPaint(strokePaint());
            double scale = Math.sqrt(Math.abs(gs.ctm.getDeterminant()));
            float lw = gs.lineWidth;
            // the thinnest line a device can show stays visible
            if (lw * scale < 0.6) {
                lw = (float) (0.6 / Math.max(scale, 1e-9));
            }
            g.setTransform(gs.ctm);
            float[] dash = gs.dash;
            if (dash != null) {
                for (float d : dash) {
                    if (d < 0) {
                        dash = null;
                        break;
                    }
                }
            }
            int cap = gs.cap == 1 ? BasicStroke.CAP_ROUND : gs.cap == 2 ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
            int join = gs.join == 1 ? BasicStroke.JOIN_ROUND : gs.join == 2 ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
            try {
                g.setStroke(new BasicStroke(lw, cap, join, Math.max(1, gs.miter), dash, gs.dashPhase));
                g.draw(path);
            } catch (RuntimeException e) {
                g.setStroke(new BasicStroke(lw));
                g.draw(path);
            } finally {
                g.setTransform(new AffineTransform());
            }
        }

        void setColor(Op op, PdfDict res, boolean stroke) {
            PdfColorSpace cs = stroke ? gs.strokeCS : gs.fillCS;
            int nums = 0;
            for (PdfObject a : op.args) {
                if (a instanceof PdfNumber) {
                    nums++;
                }
            }
            float[] c = new float[Math.max(nums, cs.components())];
            int k = 0;
            for (PdfObject a : op.args) {
                if (a instanceof PdfNumber num && k < c.length) {
                    c[k++] = num.floatValue();
                }
            }
            PdfDict pattern = null;
            if (cs.isPattern()) {
                String pn = op.args.length > 0 && op.args[op.args.length - 1] instanceof PdfName name ? name.name : null;
                PdfDict pats = res == null ? null : res.getDict("Pattern");
                pattern = pats == null || pn == null ? null : pats.getDict(pn);
            }
            if (stroke) {
                gs.strokeC = c;
                gs.strokePattern = pattern;
                gs.strokeRgb = cs.isPattern() ? (((PdfColorSpace.PatternSpace) cs).under == null ? 0x808080
                    : cs.rgb(c)) : cs.rgb(c);
            } else {
                gs.fillC = c;
                gs.fillPattern = pattern;
                gs.fillRgb = cs.isPattern() ? (((PdfColorSpace.PatternSpace) cs).under == null ? 0x808080
                    : cs.rgb(c)) : cs.rgb(c);
            }
        }

        void extGState(PdfDict res, String name) {
            PdfDict egs = res == null || name == null ? null : res.getDict("ExtGState");
            PdfDict d = egs == null ? null : egs.getDict(name);
            if (d == null) {
                return;
            }
            if (d.has("LW")) {
                gs.lineWidth = d.getFloat("LW", 1);
            }
            if (d.has("LC")) {
                gs.cap = d.getInt("LC", 0);
            }
            if (d.has("LJ")) {
                gs.join = d.getInt("LJ", 0);
            }
            if (d.has("ML")) {
                gs.miter = d.getFloat("ML", 10);
            }
            PdfArray dash = d.getArray("D");
            if (dash != null && dash.size() == 2 && dash.getArray(0) != null) {
                float[] da = dash.getArray(0).toFloats();
                gs.dash = da.length == 0 ? null : da;
                gs.dashPhase = dash.getFloat(1, 0);
            }
            PdfArray font = d.getArray("Font");
            if (font != null && font.size() == 2 && font.getDict(0) != null) {
                gs.font = cache.font(font.getDict(0));
                gs.fs = font.getFloat(1, 1);
            }
            if (d.has("CA")) {
                gs.strokeAlpha = d.getFloat("CA", 1);
            }
            if (d.has("ca")) {
                gs.fillAlpha = d.getFloat("ca", 1);
            }
            PdfObject bm = d.get("BM");
            if (bm instanceof PdfName b) {
                gs.blend = b.name;
            } else if (bm instanceof PdfArray ba && ba.size() > 0) {
                gs.blend = ba.getName(0);
            }
        }

        /* ------------------------------------------------------------- text */

        void nextLine() {
            tlm.concatenate(AffineTransform.getTranslateInstance(0, -gs.tl));
            tm = new AffineTransform(tlm);
        }

        /** Shows a string; returns the glyph count reached in the operation (for TJ's several strings). */
        int show(byte[] s, PdfDict res, int streamId, int opIndex, int glyphStart) {
            PdfFont font = gs.font;
            if (font == null) {
                return glyphStart;
            }
            float fs = gs.fs;
            float th = gs.th;
            int glyph = glyphStart;
            int pos = 0;
            boolean vertical = font.vertical;
            int mode = gs.tr;
            boolean fillIt = mode == 0 || mode == 2 || mode == 4 || mode == 6;
            boolean strokeIt = mode == 1 || mode == 2 || mode == 5 || mode == 6;
            boolean clipIt = mode >= 4;
            while (pos < s.length) {
                int len = Math.max(1, font.codeLength(s, pos));
                int code = PdfFont.code(s, pos, len);
                pos += len;
                float w0 = font.width(code);
                boolean wordSpace = font.isWordSpace(code, len);
                // glyph space to user space through the text matrix and the CTM
                AffineTransform trm = new AffineTransform(gs.ctm);
                trm.concatenate(tm);
                if (vertical) {
                    float[] v = font.verticalOrigin(code);
                    trm.concatenate(new AffineTransform(fs * th, 0, 0, fs, -v[0] * fs * th, -v[1] * fs + gs.rise));
                } else {
                    trm.concatenate(new AffineTransform(fs * th, 0, 0, fs, 0, gs.rise));
                }
                boolean hidden = opt.hide != null && opt.hide.hide(streamId, opIndex, glyph);
                if (painting() && !hidden && mode != 3 && mode != 7) {
                    if (font.isType3()) {
                        type3Glyph((Type3Font) font, code, trm, res);
                    } else {
                        Shape outline = font.glyph(code);
                        if (outline != null) {
                            Shape dev = trm.createTransformedShape(outline);
                            if (fillIt) {
                                prepare(gs.fillAlpha);
                                g.setPaint(fillPaint());
                                g.fill(dev);
                            }
                            if (strokeIt) {
                                prepare(gs.strokeAlpha);
                                g.setPaint(strokePaint());
                                double scale = Math.sqrt(Math.abs(gs.ctm.getDeterminant()));
                                float lw = (float) Math.max(gs.lineWidth * scale, 0.6);
                                g.setStroke(new BasicStroke(lw));
                                g.draw(dev);
                            }
                            if (clipIt) {
                                if (textClip == null) {
                                    textClip = new GeneralPath();
                                }
                                textClip.append(dev, false);
                            }
                        }
                    }
                }
                if (opt.sink != null && hiddenDepth == 0) {
                    emit(font, code, len, w0, wordSpace, streamId, opIndex, glyph, vertical);
                }
                glyph++;
                // advance
                if (vertical) {
                    float w1 = font.verticalAdvance(code);
                    float ty = w1 * fs + gs.tc + (wordSpace ? gs.tw : 0);
                    tm.concatenate(AffineTransform.getTranslateInstance(0, ty));
                } else {
                    float tx = (w0 * fs + gs.tc + (wordSpace ? gs.tw : 0)) * th;
                    tm.concatenate(AffineTransform.getTranslateInstance(tx, 0));
                }
            }
            return glyph;
        }

        void emit(PdfFont font, int code, int len, float w0, boolean wordSpace, int streamId, int opIndex, int glyph,
                  boolean vertical) {
            AffineTransform t = new AffineTransform(userToDisplay);
            try {
                // the CTM here is device-based; bring it back to user space first
                AffineTransform ctmUser = new AffineTransform(base.createInverse());
                ctmUser.concatenate(gs.ctm);
                t.concatenate(ctmUser);
            } catch (NoninvertibleTransformException e) {
                return;
            }
            t.concatenate(tm);
            t.concatenate(new AffineTransform(gs.fs * gs.th, 0, 0, gs.fs, 0, gs.rise));
            double[] p = { 0, 0, vertical ? 0 : w0, 0, 0, 1 };
            if (vertical) {
                float[] v = font.verticalOrigin(code);
                p = new double[] { -v[0], -v[1], -v[0] + w0, -v[1], -v[0], -v[1] + 1 };
            }
            t.transform(p, 0, p, 0, 3);
            TextGlyph tg = new TextGlyph();
            String u = font.unicode(code);
            tg.text = u == null ? "�" : u;
            tg.code = code;
            tg.codeLength = len;
            tg.font = font;
            tg.x = (float) p[0];
            tg.y = (float) p[1];
            tg.ex = (float) p[2];
            tg.ey = (float) p[3];
            double upx = p[4] - p[0];
            double upy = p[5] - p[1];
            double size = Math.hypot(upx, upy);
            tg.size = (float) size;
            if (size > 0) {
                tg.ux = (float) (upx / size);
                tg.uy = (float) (upy / size);
            } else {
                tg.ux = 0;
                tg.uy = -1;
            }
            tg.ascent = (float) (size * Math.max(0.5, font.ascent));
            tg.descent = (float) (size * Math.max(0.1, -font.descent));
            tg.color = gs.fillRgb;
            tg.renderMode = gs.tr;
            tg.streamId = streamId;
            tg.opIndex = opIndex;
            tg.glyphIndex = glyph;
            tg.tfs = gs.fs;
            tg.tc = gs.tc;
            tg.tw = gs.tw;
            tg.th = gs.th;
            tg.wordSpace = wordSpace;
            tg.readOnly = formDepth > 0 && streamId == -2;
            tg.seq = seq++;
            opt.sink.glyph(tg);
        }

        void type3Glyph(Type3Font font, int code, AffineTransform trm, PdfDict parentRes) {
            PdfStream proc = font.charProc(code);
            if (proc == null || formDepth > 8) {
                return;
            }
            GState saved = gs;
            AffineTransform savedTm = tm;
            AffineTransform savedTlm = tlm;
            GeneralPath savedPath = path;
            boolean savedUncolored = uncoloredType3;
            gs = gs.copy();
            AffineTransform m = new AffineTransform(trm);
            double[] fm = font.fontMatrix;
            m.concatenate(new AffineTransform(fm[0], fm[1], fm[2], fm[3], fm[4], fm[5]));
            gs.ctm = m;
            gs.tr = 0;
            path = new GeneralPath();
            uncoloredType3 = false;
            GlyphSink sink = opt.sink;
            opt.sink = null;
            formDepth++;
            try {
                PdfDict res = font.resources != null ? font.resources : parentRes;
                ops(cache.form(proc), res, -3, formDepth + 1);
            } finally {
                formDepth--;
                opt.sink = sink;
                gs = saved;
                tm = savedTm;
                tlm = savedTlm;
                path = savedPath;
                uncoloredType3 = savedUncolored;
            }
        }

        /* ------------------------------------------------------------- XObjects */

        void xobject(PdfDict res, String name, int depth, int streamId, int opIndex) {
            PdfDict xo = res == null || name == null ? null : res.getDict("XObject");
            if (xo == null) {
                return;
            }
            PdfObject raw = xo.getRaw(name);
            PdfObject o = PdfRef.deref(raw);
            if (!(o instanceof PdfStream s)) {
                return;
            }
            if (s.has("OC") && !cache.visible(s.get("OC"))) {
                return;
            }
            String sub = s.getName("Subtype", "");
            if (sub.equals("Image")) {
                int num = raw instanceof PdfRef r ? r.num : -1;
                recordImage(s, name, num, streamId, opIndex);
                if (painting()) {
                    drawImage(s, null, res, num);
                }
                return;
            }
            if (!sub.equals("Form") || formDepth > 12) {
                return;
            }
            int sid = raw instanceof PdfRef r ? r.num : -2;
            form(s, res, sid, depth, null);
        }

        void form(PdfStream s, PdfDict parentRes, int streamId, int depth, AffineTransform extra) {
            stack.push(gs.copy());
            GeneralPath savedPath = path;
            AffineTransform savedTm = tm;
            AffineTransform savedTlm = tlm;
            formDepth++;
            try {
                if (extra != null) {
                    gs.ctm.concatenate(extra);
                }
                PdfArray m = s.getArray("Matrix");
                if (m != null && m.size() == 6) {
                    gs.ctm.concatenate(new AffineTransform(m.toDoubles()));
                }
                gs.patternBase = new AffineTransform(gs.ctm);
                PdfArray bb = s.getArray("BBox");
                if (bb != null && bb.size() == 4 && g != null) {
                    double[] b = bb.toDoubles();
                    Rectangle2D r = new Rectangle2D.Double(Math.min(b[0], b[2]), Math.min(b[1], b[3]),
                                                           Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]));
                    intersectClip(gs.ctm.createTransformedShape(r));
                }
                path = new GeneralPath();
                PdfDict res = s.getDict("Resources");
                ops(cache.form(s), res != null ? res : parentRes, streamId, depth + 1);
            } finally {
                formDepth--;
                gs = stack.pop();
                path = savedPath;
                tm = savedTm;
                tlm = savedTlm;
            }
        }

        void inlineImage(Op op, PdfDict res) {
            if (!painting() || op.inlineDict == null) {
                return;
            }
            drawImage(op.inlineDict, op.inlineData, res, -1);
        }

        void drawImage(PdfDict d, byte[] inline, PdfDict res, int objNum) {
            boolean mask = d.getBool("ImageMask", d.getBool("IM", false));
            // a picture being edited elsewhere (Sphere Image) shows its edits live
            BufferedImage img = objNum >= 0 ? cache.override(objNum) : null;
            PdfStream s = d instanceof PdfStream st ? st : null;
            if (img == null && s != null && !mask) {
                img = cache.image(s);
            }
            if (img == null) {
                img = ImageDecoder.decode(d, inline, res, cache.colorSpaces, gs.fillRgb);
                if (img != null && s != null && !mask) {
                    cache.putImage(s, img);
                }
            }
            if (img == null) {
                return;
            }
            prepare(gs.fillAlpha);
            AffineTransform at = new AffineTransform(gs.ctm);
            at.concatenate(new AffineTransform(1.0 / img.getWidth(), 0, 0, -1.0 / img.getHeight(), 0, 1));
            // a small picture blown up keeps its pixels sharp
            double sx = Math.hypot(at.getScaleX(), at.getShearY());
            Object interp = sx > 3 && img.getWidth() < 300 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp);
            if (sx < 0.5) {
                img = ImageScaler.scaleFor(img, at);
                at = new AffineTransform(gs.ctm);
                at.concatenate(new AffineTransform(1.0 / img.getWidth(), 0, 0, -1.0 / img.getHeight(), 0, 1));
            }
            if (mask && gs.fillPattern != null) {
                // a stencil painted with a pattern: clip to it and fill
                g.drawImage(img, at, null);
            } else {
                g.drawImage(img, at, null);
            }
        }

        /* ------------------------------------------------------------- shadings and patterns */

        void shading(PdfDict res, String name) {
            if (!painting()) {
                return;
            }
            PdfDict shs = res == null || name == null ? null : res.getDict("Shading");
            PdfDict sh = shs == null ? null : shs.getDict(name);
            if (sh == null) {
                return;
            }
            prepare(gs.fillAlpha);
            Shape area = gs.clip != null ? gs.clip : g.getClipBounds();
            Shape bbox = ShadingPaint.bbox(sh);
            if (bbox != null) {
                Area a = new Area(gs.ctm.createTransformedShape(bbox));
                if (area != null) {
                    a.intersect(new Area(area));
                }
                area = a;
            }
            int type = sh.getInt("ShadingType", 0);
            if (type >= 4 && sh instanceof PdfStream ms) {
                Shape oldClip = g.getClip();
                if (area != null) {
                    g.setClip(area);
                }
                g.setTransform(gs.ctm);
                ShadingPaint.fillMesh(g, ms, res, cache.colorSpaces, gs.fillAlpha);
                g.setTransform(IDENTITY);
                g.setClip(oldClip);
                lastClip = UNSET;
                return;
            }
            Paint p = ShadingPaint.paint(sh, gs.ctm, res, cache.colorSpaces, 1f);
            if (p == null || area == null) {
                return;
            }
            g.setPaint(p);
            g.fill(area);
        }

        Paint patternPaint(PdfDict pat, float[] comps, PdfColorSpace cs, float alpha) {
            AffineTransform pm = new AffineTransform(gs.patternBase);
            PdfArray m = pat.getArray("Matrix");
            if (m != null && m.size() == 6) {
                pm.concatenate(new AffineTransform(m.toDoubles()));
            }
            int type = pat.getInt("PatternType", 1);
            if (type == 2) {
                PdfDict sh = pat.getDict("Shading");
                if (sh == null) {
                    return null;
                }
                Paint p = ShadingPaint.paint(sh, pm, null, cache.colorSpaces, 1f);
                if (p == null) {
                    // a mesh pattern: its average color
                    Color bg = ShadingPaint.background(sh, null, cache.colorSpaces);
                    return bg != null ? bg : Color.GRAY;
                }
                return p;
            }
            if (!(pat instanceof PdfStream ps)) {
                return null;
            }
            return tilingPaint(ps, pm, comps, cs);
        }

        Paint tilingPaint(PdfStream ps, AffineTransform pm, float[] comps, PdfColorSpace cs) {
            double xs = Math.abs(ps.getFloat("XStep", 0));
            double ys = Math.abs(ps.getFloat("YStep", 0));
            PdfArray bb = ps.getArray("BBox");
            if (bb == null || bb.size() < 4) {
                return null;
            }
            double[] b = bb.toDoubles();
            double bx = Math.min(b[0], b[2]);
            double by = Math.min(b[1], b[3]);
            if (xs == 0) {
                xs = Math.abs(b[2] - b[0]);
            }
            if (ys == 0) {
                ys = Math.abs(b[3] - b[1]);
            }
            if (xs <= 0 || ys <= 0) {
                return null;
            }
            double scale = Math.sqrt(Math.abs(pm.getDeterminant()));
            int pw = (int) Math.ceil(xs * scale);
            int ph = (int) Math.ceil(ys * scale);
            if (pw * (long) ph > 4_000_000 || pw > 4096 || ph > 4096) {
                double k = Math.sqrt(4_000_000.0 / Math.max(1, (double) pw * ph));
                pw = (int) Math.max(1, pw * Math.min(1, k));
                ph = (int) Math.max(1, ph * Math.min(1, k));
            }
            pw = Math.max(1, pw);
            ph = Math.max(1, ph);
            BufferedImage tile = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
            Graphics2D tg = tile.createGraphics();
            tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // cell space to tile pixels: y flipped, BBox origin at the corner
            AffineTransform cellToTile = new AffineTransform(pw / xs, 0, 0, -ph / ys, -bx * pw / xs, ph + by * ph / ys);
            Run sub = new Run(tg, opt, userToDisplay, cellToTile);
            sub.formDepth = formDepth + 1;
            sub.gs.clip = new Rectangle(0, 0, pw, ph);
            if (ps.getInt("PaintType", 1) == 2) {
                // uncolored: drawn in the color the scn gave
                PdfColorSpace under = cs instanceof PdfColorSpace.PatternSpace p && p.under != null ? p.under : null;
                int rgb = under == null ? 0 : under.rgb(comps);
                sub.gs.fillRgb = rgb;
                sub.gs.strokeRgb = rgb;
                sub.uncoloredType3 = true;
            }
            GlyphSink sink = opt.sink;
            opt.sink = null;
            try {
                PdfDict res = ps.getDict("Resources");
                if (formDepth < 8) {
                    sub.ops(cache.form(ps), res, -2, formDepth + 1);
                }
            } finally {
                opt.sink = sink;
                tg.dispose();
            }
            AffineTransform tileToDevice = new AffineTransform(pm);
            tileToDevice.concatenate(new AffineTransform(xs / pw, 0, 0, -ys / ph, bx, by + ys));
            return new TilingPaint(tile, tileToDevice);
        }

        /* ------------------------------------------------------------- annotations */

        void annotation(PdfDict a, PdfPage page) {
            int flags = a.getInt("F", 0);
            if ((flags & 2) != 0 || (flags & 32) != 0 || "Popup".equals(a.getName("Subtype"))) {
                return;
            }
            if (a.has("OC") && !cache.visible(a.get("OC"))) {
                return;
            }
            PdfArray rect = a.getArray("Rect");
            if (rect == null || rect.size() < 4) {
                return;
            }
            PdfDict ap = a.getDict("AP");
            PdfObject n = ap == null ? null : ap.get("N");
            PdfStream form = null;
            if (n instanceof PdfStream s) {
                form = s;
            } else if (n instanceof PdfDict states) {
                String as = a.getName("AS");
                if (as != null) {
                    form = states.getStream(as);
                }
            }
            if (form == null) {
                return;
            }
            double[] r = rect.toDoubles();
            double rx0 = Math.min(r[0], r[2]);
            double ry0 = Math.min(r[1], r[3]);
            double rx1 = Math.max(r[0], r[2]);
            double ry1 = Math.max(r[1], r[3]);
            PdfArray bb = form.getArray("BBox");
            if (bb == null || bb.size() < 4) {
                return;
            }
            AffineTransform matrix = new AffineTransform();
            PdfArray m = form.getArray("Matrix");
            if (m != null && m.size() == 6) {
                matrix = new AffineTransform(m.toDoubles());
            }
            double[] b = bb.toDoubles();
            Rectangle2D box = matrix.createTransformedShape(new Rectangle2D.Double(Math.min(b[0], b[2]),
                Math.min(b[1], b[3]), Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]))).getBounds2D();
            if (box.getWidth() <= 0 || box.getHeight() <= 0) {
                return;
            }
            // Algorithm 8.1: map the transformed box onto the rectangle
            AffineTransform fit = new AffineTransform();
            fit.translate(rx0, ry0);
            fit.scale((rx1 - rx0) / box.getWidth(), (ry1 - ry0) / box.getHeight());
            fit.translate(-box.getX(), -box.getY());
            // the form's own Matrix is applied by form(); here only the fitting
            gs = new GState();
            gs.ctm = new AffineTransform(base);
            gs.patternBase = new AffineTransform(base);
            if (g != null) {
                gs.clip = base.createTransformedShape(page.cropBox());
            }
            GlyphSink sink = opt.sink;
            opt.sink = null;
            try {
                form(form, page.resources(), -4, 0, fit);
            } finally {
                opt.sink = sink;
            }
        }
    }

    /* ================================================================= tiling */

    /** A tiling pattern: device pixels mapped back into the cell picture, wrapping around. */
    private static final class TilingPaint implements Paint {
        final BufferedImage tile;
        final AffineTransform inverse;

        TilingPaint(BufferedImage tile, AffineTransform tileToDevice) {
            this.tile = tile;
            AffineTransform inv;
            try {
                inv = tileToDevice.createInverse();
            } catch (NoninvertibleTransformException e) {
                inv = new AffineTransform();
            }
            inverse = inv;
        }

        @Override
        public int getTransparency() {
            return Transparency.TRANSLUCENT;
        }

        @Override
        public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds,
                                          AffineTransform xform, RenderingHints hints) {
            final int tw = tile.getWidth();
            final int th = tile.getHeight();
            final int[] tp = tile.getRGB(0, 0, tw, th, null, 0, tw);
            return new PaintContext() {
                @Override
                public void dispose() {
                }

                @Override
                public ColorModel getColorModel() {
                    return CM;
                }

                @Override
                public Raster getRaster(int x, int y, int w, int h) {
                    WritableRaster r = CM.createCompatibleWritableRaster(w, h);
                    int[] px = new int[w * h];
                    double[] pt = new double[2];
                    for (int j = 0; j < h; j++) {
                        for (int i = 0; i < w; i++) {
                            pt[0] = x + i + 0.5;
                            pt[1] = y + j + 0.5;
                            inverse.transform(pt, 0, pt, 0, 1);
                            int u = Math.floorMod((int) Math.floor(pt[0]), tw);
                            int v = Math.floorMod((int) Math.floor(pt[1]), th);
                            px[j * w + i] = tp[v * tw + u];
                        }
                    }
                    r.setDataElements(0, 0, w, h, px);
                    return r;
                }
            };
        }
    }
}
