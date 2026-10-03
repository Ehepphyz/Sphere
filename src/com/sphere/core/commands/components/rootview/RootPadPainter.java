package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.Axis;
import com.sphere.components.rootview.RootScene.Entry;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.components.rootview.RootScene.Pave;
import com.sphere.components.rootview.RootScene.Segment;
import com.sphere.components.rootview.RootScene.Text;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.theme.ThemePaletteDark;
import com.sphere.theme.ThemePaletteLight;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Paints one pad of a ROOT canvas the way ROOT would: the frame and its axes,
 * then each object with the option it was drawn with, then the title, the
 * statistics box, legends and texts. What ROOT draws in three dimensions
 * (LEGO, SURF, a TH3, a TGraph2D) is handed to RootPainter3D.
 *
 * The user's changes to a pad (another option, logarithmic axes, a zoom, the
 * angle of a 3D view) live in its View, which the painter reads and fills with
 * the mapping it used, so the mouse can be turned back into data.
 */
final class RootPadPainter {

    enum Mode { EMPTY, ONE_D, FLAT, THREE_D }

    /** What the user changed in one pad, and how the pad was last drawn. */
    static final class View {
        String option;
        Boolean logx;
        Boolean logy;
        Boolean logz;
        Boolean gridx;
        Boolean gridy;
        boolean statsOff;
        double[] xr;
        double[] yr;
        double[] zr;
        String xTitle;
        String yTitle;
        String zTitle;
        double theta = Double.NaN;
        double phi = Double.NaN;
        double zoom = 1;

        // How the pad was last painted, for the mouse.
        Mode mode = Mode.EMPTY;
        final Rectangle frame = new Rectangle();
        double x0;
        double x1;
        double y0;
        double y1;
        boolean lx;
        boolean ly;

        void reset() {
            xr = null;
            yr = null;
            zr = null;
            theta = Double.NaN;
            phi = Double.NaN;
            zoom = 1;
        }

        double dataX(int px) {
            final double f = (px - frame.x) / (double) Math.max(1, frame.width);
            return lx ? Math.pow(10, Math.log10(x0) + f * (Math.log10(x1) - Math.log10(x0))) : x0 + f * (x1 - x0);
        }

        double dataY(int py) {
            final double f = (frame.y + frame.height - py) / (double) Math.max(1, frame.height);
            return ly ? Math.pow(10, Math.log10(y0) + f * (Math.log10(y1) - Math.log10(y0))) : y0 + f * (y1 - y0);
        }
    }

    private RootPadPainter() {
    }

    /* ------------------------------------------------------------------ */
    /* The colours of the canvas                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The palette the canvas is drawn with, through its canvas keys: the
     * theme's, or the light palette, whose canvas keys are ROOT's own white
     * paper and black ink (a picture to export, or ROOT's paper asked for).
     * RootCanvasView sets it before each paint, on the event thread.
     */
    static ThemePalette palette = ThemeManager.getCurrentPalette();

    static boolean isWhite(Color c) {
        return c != null && (c.getRGB() & 0xFFFFFF) == 0xFFFFFF;
    }

    static boolean isBlack(Color c) {
        return c != null && (c.getRGB() & 0xFFFFFF) == 0;
    }

    /**
     * The ground of the pad being drawn: its own fill when its author gave it
     * one, else the palette's paper. Set by paint, read by the inks below, so
     * that black on a light fill of the author's stays black while black on
     * the theme's dark paper becomes the theme's ink.
     */
    static Color ground = palette.getCanvasPaper();

    /** ROOT's paper (white, or no colour) is the palette's; any other fill is the author's. */
    static Color paper(Color c) {
        return c == null || isWhite(c) ? palette.getCanvasPaper() : c;
    }

    /** The ink that reads on the pad being drawn. */
    static Color inkColor() {
        return inkOn(ground);
    }

    /**
     * The ink that reads on a ground: the palette's when it does (a contrast
     * of 3:1), else whichever of ROOT's black and the dark theme's ink reads
     * better there.
     */
    static Color inkOn(Color under) {
        final Color mine = palette.getCanvasInk();
        final double g = luminance(under);
        if (contrast(luminance(mine), g) >= 3) return mine;
        final Color black = ThemePaletteLight.INSTANCE.getCanvasInk();
        final Color light = ThemePaletteDark.INSTANCE.getCanvasInk();
        return contrast(luminance(black), g) >= contrast(luminance(light), g) ? black : light;
    }

    /** ROOT's ink (black, or no colour) is the ink of the ground; any other colour carries data and is kept. */
    static Color ink(Color c) {
        return c == null || isBlack(c) ? inkColor() : c;
    }

    /** A text's colour on the pad being drawn. */
    static Color text(Color c) {
        return text(c, ground);
    }

    /**
     * A text's colour on a ground: black is the ink of that ground; another
     * colour is the author's, kept unless it would not read there (a contrast
     * under 3:1), then moved toward the ink, its hue kept, until it does.
     */
    static Color text(Color c, Color under) {
        final Color toward = inkOn(under);
        if (c == null || isBlack(c)) return toward;
        final double g = luminance(under);
        Color out = c;
        for (int k = 0; k < 6 && contrast(luminance(out), g) < 3; k++) {
            out = new Color((out.getRed() * 7 + toward.getRed() * 3) / 10, (out.getGreen() * 7 + toward.getGreen() * 3) / 10,
                (out.getBlue() * 7 + toward.getBlue() * 3) / 10, c.getAlpha());
        }
        return out;
    }

    private static double luminance(Color c) {
        return lin(c.getRed()) * 0.2126 + lin(c.getGreen()) * 0.7152 + lin(c.getBlue()) * 0.0722;
    }

    private static double lin(int v) {
        final double x = v / 255.0;
        return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    private static double contrast(double a, double b) {
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /* ------------------------------------------------------------------ */
    /* What a pad is                                                       */
    /* ------------------------------------------------------------------ */

    /** The option the main object is drawn with: the user's, else ROOT's, else a sensible one. */
    static String option(Pad pad, View view) {
        final Item main = pad.main();
        if (main == null) return "";
        if (view != null && view.option != null) return view.option.toUpperCase(Locale.ROOT);
        final String o = main.opt();
        if (!o.isEmpty()) return o;
        if (main instanceof Hist h) {
            if (h.dim == 2) return h.isFunction() ? "SURF1" : "COLZ";
            if (h.dim == 3) return "BOX";
            return h.isFunction() ? "C" : "";
        }
        if (main instanceof Graph2D g) return g.x.length >= 3 ? "TRI1" : "P0";
        return "";
    }

    static Mode mode(Pad pad, View view) {
        final Item main = pad.main();
        if (main == null) return Mode.EMPTY;
        final String o = option(pad, view);
        if (main instanceof Graph2D) return Mode.THREE_D;
        if (main instanceof Hist h) {
            if (h.dim == 3) return Mode.THREE_D;
            if (h.dim == 2) return o.contains("LEGO") || o.contains("SURF") || o.contains("TRI") ? Mode.THREE_D : Mode.FLAT;
        }
        return Mode.ONE_D;
    }

    static boolean log(Boolean user, boolean root) {
        return user != null ? user : root;
    }

    /* ------------------------------------------------------------------ */
    /* The pad                                                             */
    /* ------------------------------------------------------------------ */

    static void paint(Graphics2D g, Rectangle area, Pad pad, View view, RootScene scene) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        ground = paper(pad.fill);
        g.setColor(ground);
        g.fill(area);
        view.mode = mode(pad, view);
        final Rectangle frame = new Rectangle(
            area.x + (int) Math.round(pad.lm * area.width),
            area.y + (int) Math.round(pad.tm * area.height),
            (int) Math.round((1 - pad.lm - pad.rm) * area.width),
            (int) Math.round((1 - pad.tm - pad.bm) * area.height));
        view.frame.setBounds(frame);
        switch (view.mode) {
            case ONE_D -> paintOneD(g, area, frame, pad, view, scene);
            case FLAT -> paintFlat(g, area, frame, pad, view, scene);
            case THREE_D -> RootPainter3D.paint(g, area, pad, view, scene, option(pad, view));
            default -> {
            }
        }
        if (view.mode != Mode.EMPTY) {
            title(g, area, pad, scene);
            stats(g, area, pad, view, scene);
        }
        overlays(g, area, frame, pad, view);
    }

    /* ------------------------------------------------------------------ */
    /* One dimension                                                       */
    /* ------------------------------------------------------------------ */

    private static void paintOneD(Graphics2D g, Rectangle area, Rectangle frame, Pad pad, View view, RootScene scene) {
        final Item main = pad.main();
        final boolean lx = log(view.logx, pad.logx);
        final boolean ly = log(view.logy, pad.logy);
        final String mainOption = option(pad, view);

        double[] xr = view.xr != null ? view.xr.clone() : xRange(main);
        double[] yr = view.yr != null ? view.yr.clone() : yRange(pad, main, mainOption, xr, ly);
        if (lx && xr[0] <= 0) xr[0] = Math.max(1e-9, xr[1] * 1e-4);
        if (ly && yr[0] <= 0) yr[0] = Math.max(1e-9, yr[1] * 1e-4);
        view.x0 = xr[0];
        view.x1 = xr[1];
        view.y0 = yr[0];
        view.y1 = yr[1];
        view.lx = lx;
        view.ly = ly;

        g.setColor(ground);
        g.fill(frame);
        grid(g, frame, view, log(view.gridx, pad.gridx), log(view.gridy, pad.gridy));

        final Shape clip = g.getClip();
        g.clip(frame);
        boolean first = true;
        for (Item item : pad.items) {
            final String o = item == main ? mainOption : item.opt();
            if (item instanceof Hist h && h.dim == 1) {
                hist1(g, h, first && item == main ? mainOption : o, view);
                for (Hist fit : h.fits) hist1(g, fit, "C", view);
            } else if (item instanceof Graph gr) {
                graph(g, gr, o, view);
            } else if (item instanceof Group group) {
                group(g, group, o, view);
            }
            if (item == main) first = false;
        }
        g.setClip(clip);

        frameBox(g, frame);
        axisX(g, frame, view.x0, view.x1, lx, titleX(main, view), axisLabels(main));
        axisY(g, frame, view.y0, view.y1, ly, titleY(main, view), true);
    }

    static double[] xRange(Item main) {
        if (main instanceof Hist h) return h.x.shown();
        if (main instanceof Graph gr) return padded(bounds(gr.x, gr.exl, gr.exh));
        if (main instanceof Group group) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (Item i : group.items) {
                final double[] r = xRange(i);
                lo = Math.min(lo, r[0]);
                hi = Math.max(hi, r[1]);
            }
            return lo < hi ? new double[]{lo, hi} : new double[]{0, 1};
        }
        return new double[]{0, 1};
    }

    private static double[] yRange(Pad pad, Item main, String option, double[] xr, boolean log) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double positive = Double.POSITIVE_INFINITY;
        boolean histogram = false;
        final List<Item> considered = new ArrayList<>();
        considered.add(main);
        if (main instanceof Group group) {
            considered.clear();
            considered.addAll(group.items);
        }
        double[] stacked = null;
        if (main instanceof Group group && "stack".equals(group.kind) && !option.contains("NOSTACK")) {
            stacked = stackTotal(group);
        }
        for (Item item : considered) {
            if (item instanceof Hist h && h.dim == 1) {
                histogram = true;
                final boolean errors = option.contains("E");
                for (int i = 0; i < h.nx(); i++) {
                    final double c = h.x.center(i);
                    if (c < xr[0] || c > xr[1]) continue;
                    final double v = stacked != null && i < stacked.length ? stacked[i] : h.at(i, 0);
                    final double e = errors ? h.error(i) : 0;
                    lo = Math.min(lo, v - e);
                    hi = Math.max(hi, v + e);
                    if (v > 0) positive = Math.min(positive, v);
                }
                if (!Double.isNaN(h.min) && item == main) lo = h.min;
                if (!Double.isNaN(h.max) && item == main) hi = h.max;
            } else if (item instanceof Graph gr) {
                for (int i = 0; i < gr.y.length; i++) {
                    final double el = gr.eyl == null ? 0 : gr.eyl[i];
                    final double eh = gr.eyh == null ? 0 : gr.eyh[i];
                    lo = Math.min(lo, gr.y[i] - el);
                    hi = Math.max(hi, gr.y[i] + eh);
                    if (gr.y[i] > 0) positive = Math.min(positive, gr.y[i]);
                }
            }
        }
        if (!(lo < hi)) {
            lo = Double.isFinite(lo) ? lo - 1 : 0;
            hi = Double.isFinite(hi) ? hi + 1 : 1;
        }
        if (log) {
            final double bottom = Double.isFinite(positive) ? positive * 0.5 : Math.max(1e-3, hi * 1e-3);
            return new double[]{bottom, hi * 2};
        }
        if (histogram) {
            // As THistPainter: a histogram of positive contents starts at 0, and 5% is left on top.
            final boolean storedMin = main instanceof Hist h && !Double.isNaN(h.min);
            final boolean storedMax = main instanceof Hist h && !Double.isNaN(h.max);
            if (!storedMin && lo >= 0) lo = 0;
            if (!storedMax) hi += 0.05 * (hi - lo);
            if (!storedMin && lo < 0) lo -= 0.05 * (hi - lo);
            return new double[]{lo, hi};
        }
        final double d = 0.1 * (hi - lo);
        return new double[]{lo - d, hi + d};
    }

    private static double[] stackTotal(Group group) {
        double[] total = null;
        for (Item i : group.items) {
            if (!(i instanceof Hist h) || h.dim != 1) continue;
            if (total == null) total = new double[h.nx()];
            for (int k = 0; k < Math.min(total.length, h.nx()); k++) total[k] += h.at(k, 0);
        }
        return total == null ? new double[0] : total;
    }

    private static double[] bounds(double[] values, double[] low, double[] high) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            lo = Math.min(lo, values[i] - (low == null ? 0 : low[i]));
            hi = Math.max(hi, values[i] + (high == null ? 0 : high[i]));
        }
        return lo <= hi ? new double[]{lo, hi} : new double[]{0, 1};
    }

    private static double[] padded(double[] r) {
        final double d = r[1] > r[0] ? 0.1 * (r[1] - r[0]) : 1;
        return new double[]{r[0] - d, r[1] + d};
    }

    /* ---- drawing a histogram ----------------------------------------- */

    static void hist1(Graphics2D g, Hist h, String o, View view) {
        final boolean function = h.isFunction();
        boolean hist = o.contains("HIST");
        final boolean errors = !hist && o.contains("E");
        final boolean band = o.contains("E2") || o.contains("E3") || o.contains("E4");
        final boolean markers = o.contains("P") || o.contains("*");
        final boolean polyline = o.contains("L") || o.contains("C") || function;
        final boolean bars = o.contains("B") && !o.contains("BOX");
        if (!errors && !markers && !polyline && !bars) hist = true;
        final Color lineColor = h.line == null ? palette.getCanvasHistLine() : ink(h.line);
        final int n = h.nx();

        if (bars) {
            final Color fill = h.hasFill() ? h.fill : lineColor;
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                final double w = h.x.edge(i + 1) - h.x.edge(i);
                final int xa = px(view, h.x.edge(i) + 0.1 * w);
                final int xb = px(view, h.x.edge(i + 1) - 0.1 * w);
                final int ya = py(view, Math.max(view.y0, 0));
                final int yb = py(view, v);
                g.setColor(fill);
                g.fillRect(Math.min(xa, xb), Math.min(ya, yb), Math.abs(xb - xa), Math.abs(yb - ya));
            }
        }
        if (hist) {
            final Path2D steps = new Path2D.Double();
            final double base = view.ly ? view.y0 : Math.max(view.y0, Math.min(0, view.y1));
            steps.moveTo(px(view, h.x.edge(0)), py(view, base));
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                steps.lineTo(px(view, h.x.edge(i)), py(view, v));
                steps.lineTo(px(view, h.x.edge(i + 1)), py(view, v));
            }
            steps.lineTo(px(view, h.x.edge(n)), py(view, base));
            if (h.hasFill()) {
                g.setColor(fillColor(h));
                g.fill(steps);
            }
            // The outline without the drops to the axis at both ends, as ROOT draws it.
            final Path2D outline = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                if (i == 0) outline.moveTo(px(view, h.x.edge(0)), py(view, v));
                else outline.lineTo(px(view, h.x.edge(i)), py(view, v));
                outline.lineTo(px(view, h.x.edge(i + 1)), py(view, v));
            }
            stroke(g, h, lineColor);
            g.draw(outline);
        }
        if (band) {
            final Path2D area = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                final double e = h.error(i);
                final double c = h.x.center(i);
                if (i == 0) area.moveTo(px(view, c), py(view, v + e));
                else area.lineTo(px(view, c), py(view, v + e));
            }
            for (int i = n - 1; i >= 0; i--) area.lineTo(px(view, h.x.center(i)), py(view, h.at(i, 0) - h.error(i)));
            area.closePath();
            final Color c = h.hasFill() ? fillColor(h) : translucent(lineColor, 70);
            g.setColor(c);
            g.fill(area);
        }
        if (errors && !band) {
            stroke(g, h, lineColor);
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                final double e = h.error(i);
                if (v == 0 && e == 0) continue;
                final int x = px(view, h.x.center(i));
                g.draw(new Line2D.Double(x, py(view, v - e), x, py(view, v + e)));
                // The bin's width, as gStyle's ErrorX of 0.5 draws it.
                g.draw(new Line2D.Double(px(view, h.x.edge(i)), py(view, v), px(view, h.x.edge(i + 1)), py(view, v)));
                if (o.contains("E1")) {
                    g.draw(new Line2D.Double(x - 3, py(view, v - e), x + 3, py(view, v - e)));
                    g.draw(new Line2D.Double(x - 3, py(view, v + e), x + 3, py(view, v + e)));
                }
            }
        }
        if (polyline) {
            final Path2D curve = new Path2D.Double();
            boolean started = false;
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                if (view.ly && v <= 0) {
                    started = false;
                    continue;
                }
                final double x = px(view, h.x.center(i));
                final double y = py(view, v);
                if (!started) curve.moveTo(x, y);
                else curve.lineTo(x, y);
                started = true;
            }
            stroke(g, h, function && h.line != null ? h.line : lineColor);
            g.draw(curve);
        }
        if (markers || (errors && h.markerStyle > 1)) {
            final Color mc = ink(h.marker);
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                if (errors && v == 0) continue;
                marker(g, o.contains("*") ? 3 : h.markerStyle, h.markerSize, mc, px(view, h.x.center(i)), py(view, v));
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    /* ---- drawing a graph --------------------------------------------- */

    static void graph(Graphics2D g, Graph gr, String o, View view) {
        final boolean markers = o.contains("P") || o.contains("*");
        final boolean smooth = o.contains("C");
        final boolean line = o.contains("L") || smooth || (!markers && !o.contains("B") && !o.contains("F"));
        final boolean fill = o.contains("F");
        final boolean bars = o.contains("B");
        final Color lc = ink(gr.line);
        final int n = gr.x.length;

        if (bars) {
            g.setColor(gr.hasFill() ? gr.fill : lc);
            final double w = n > 1 ? 0.5 * Math.abs(gr.x[1] - gr.x[0]) : 0.5;
            for (int i = 0; i < n; i++) {
                final int a = px(view, gr.x[i] - w / 2);
                final int b = px(view, gr.x[i] + w / 2);
                final int y0 = py(view, Math.max(view.y0, 0));
                final int y1 = py(view, gr.y[i]);
                g.fillRect(Math.min(a, b), Math.min(y0, y1), Math.max(1, Math.abs(b - a)), Math.abs(y1 - y0));
            }
        }
        if (gr.eyl != null || gr.exl != null) {
            stroke(g, gr, lc);
            for (int i = 0; i < n; i++) {
                final int x = px(view, gr.x[i]);
                final int y = py(view, gr.y[i]);
                if (gr.eyl != null) g.draw(new Line2D.Double(x, py(view, gr.y[i] - gr.eyl[i]), x, py(view, gr.y[i] + gr.eyh[i])));
                if (gr.exl != null) g.draw(new Line2D.Double(px(view, gr.x[i] - gr.exl[i]), y, px(view, gr.x[i] + gr.exh[i]), y));
            }
        }
        if (line || fill) {
            final Path2D path = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                if (i == 0) path.moveTo(px(view, gr.x[i]), py(view, gr.y[i]));
                else path.lineTo(px(view, gr.x[i]), py(view, gr.y[i]));
            }
            if (fill) {
                g.setColor(gr.hasFill() ? fillColor(gr) : translucent(lc, 80));
                final Path2D closed = (Path2D) path.clone();
                closed.closePath();
                g.fill(closed);
            }
            if (line) {
                stroke(g, gr, lc);
                g.draw(path);
            }
        }
        if (markers) {
            final Color mc = ink(gr.marker);
            for (int i = 0; i < n; i++) {
                marker(g, o.contains("*") ? 3 : gr.markerStyle, gr.markerSize, mc, px(view, gr.x[i]), py(view, gr.y[i]));
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    private static void group(Graphics2D g, Group group, String o, View view) {
        if ("stack".equals(group.kind) && !o.contains("NOSTACK")) {
            // Each member drawn over the sum of those before it, the top one first.
            final List<Hist> members = new ArrayList<>();
            for (Item i : group.items) if (i instanceof Hist h && h.dim == 1) members.add(h);
            final double[][] sums = new double[members.size()][];
            double[] running = null;
            for (int k = 0; k < members.size(); k++) {
                final Hist h = members.get(k);
                final double[] now = new double[h.nx()];
                for (int i = 0; i < now.length; i++) now[i] = (running == null || i >= running.length ? 0 : running[i]) + h.at(i, 0);
                sums[k] = now;
                running = now;
            }
            for (int k = members.size() - 1; k >= 0; k--) {
                final Hist h = members.get(k);
                final Hist cumulative = new Hist();
                cumulative.dim = 1;
                cumulative.x = h.x;
                cumulative.v = sums[k];
                cumulative.line = h.line;
                cumulative.fill = h.hasFill() ? h.fill : null;
                cumulative.fillStyle = h.hasFill() ? h.fillStyle : 0;
                cumulative.lineWidth = h.lineWidth;
                hist1(g, cumulative, "HIST", view);
            }
            return;
        }
        for (Item i : group.items) {
            final String mine = i.opt().isEmpty() ? o : i.opt();
            if (i instanceof Hist h && h.dim == 1) hist1(g, h, mine, view);
            else if (i instanceof Graph gr) graph(g, gr, mine.isEmpty() ? "LP" : mine, view);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Two dimensions, flat                                                */
    /* ------------------------------------------------------------------ */

    private static void paintFlat(Graphics2D g, Rectangle area, Rectangle frame, Pad pad, View view, RootScene scene) {
        final Hist h = (Hist) pad.main();
        final String o = option(pad, view);
        final boolean palette = o.contains("Z");
        if (palette && pad.rm < 0.12) {
            // Room for the palette on the right, as ROOT makes when COLZ asks for it.
            frame.width = (int) Math.round((1 - pad.lm - 0.14) * area.width);
            view.frame.setBounds(frame);
        }
        final boolean lx = log(view.logx, pad.logx);
        final boolean ly = log(view.logy, pad.logy);
        final boolean lz = log(view.logz, pad.logz);
        final double[] xr = view.xr != null ? view.xr.clone() : h.x.shown();
        final double[] yr = view.yr != null ? view.yr.clone() : h.y.shown();
        view.x0 = xr[0];
        view.x1 = xr[1];
        view.y0 = yr[0];
        view.y1 = yr[1];
        view.lx = lx && xr[0] > 0;
        view.ly = ly && yr[0] > 0;
        final double[] zr = view.zr != null ? view.zr.clone() : zRange(h, lz);

        g.setColor(ground);
        g.fill(frame);
        final Shape clip = g.getClip();
        g.clip(frame);
        final Color[] colors = scene.palette;
        final int nx = h.nx();
        final int ny = h.ny();
        double biggest = 0;
        for (double v : h.v) biggest = Math.max(biggest, Math.abs(v));

        if (o.matches(".*CONT[123].*")) {
            contours(g, h, view, zr, lz, colors, o);
        } else if (o.contains("BOX")) {
            g.setColor(ink(h.line));
            for (int iy = 0; iy < ny; iy++) {
                for (int ix = 0; ix < nx; ix++) {
                    final double v = h.at(ix, iy);
                    if (v <= 0 || biggest <= 0) continue;
                    final double s = Math.sqrt(v / biggest);
                    final double cx = h.x.center(ix);
                    final double cy = h.y.center(iy);
                    final double wx = 0.5 * s * (h.x.edge(ix + 1) - h.x.edge(ix));
                    final double wy = 0.5 * s * (h.y.edge(iy + 1) - h.y.edge(iy));
                    final int a = px(view, cx - wx);
                    final int b = px(view, cx + wx);
                    final int c = py(view, cy + wy);
                    final int d = py(view, cy - wy);
                    if (h.hasFill()) {
                        g.setColor(h.fill);
                        g.fillRect(a, c, Math.max(1, b - a), Math.max(1, d - c));
                        g.setColor(ink(h.line));
                    }
                    g.drawRect(a, c, Math.max(1, b - a), Math.max(1, d - c));
                }
            }
        } else if (o.contains("SCAT")) {
            g.setColor(ink(h.marker));
            final java.util.Random random = new java.util.Random(12345);
            for (int iy = 0; iy < ny; iy++) {
                for (int ix = 0; ix < nx; ix++) {
                    final double v = h.at(ix, iy);
                    if (v <= 0 || biggest <= 0) continue;
                    final int dots = (int) Math.ceil(v / biggest * 50);
                    for (int k = 0; k < dots; k++) {
                        final double x = h.x.edge(ix) + random.nextDouble() * (h.x.edge(ix + 1) - h.x.edge(ix));
                        final double y = h.y.edge(iy) + random.nextDouble() * (h.y.edge(iy + 1) - h.y.edge(iy));
                        g.fillRect(px(view, x), py(view, y), 1, 1);
                    }
                }
            }
        } else {
            // COL, COLZ, and the filled contours (CONT, CONT0, CONT4), drawn as bands of the palette.
            final boolean bands = o.contains("CONT");
            final int levels = 20;
            for (int iy = 0; iy < ny; iy++) {
                for (int ix = 0; ix < nx; ix++) {
                    final double v = h.at(ix, iy);
                    if (v == 0 && !bands && zr[0] >= 0) continue;
                    if (v < zr[0] && !bands) continue;
                    double f = fraction(v, zr, lz);
                    if (bands) f = Math.floor(f * levels) / levels;
                    final int a = px(view, h.x.edge(ix));
                    final int b = px(view, h.x.edge(ix + 1));
                    final int c = py(view, h.y.edge(iy + 1));
                    final int d = py(view, h.y.edge(iy));
                    g.setColor(colors[Math.max(0, Math.min(colors.length - 1, (int) Math.round(f * (colors.length - 1))))]);
                    g.fillRect(Math.min(a, b), Math.min(c, d), Math.max(1, Math.abs(b - a)), Math.max(1, Math.abs(d - c)));
                }
            }
        }
        if (o.contains("TEXT")) {
            g.setColor(inkColor());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            final FontMetrics fm = g.getFontMetrics();
            for (int iy = 0; iy < ny; iy++) {
                for (int ix = 0; ix < nx; ix++) {
                    final double v = h.at(ix, iy);
                    if (v == 0) continue;
                    final String s = compact(v);
                    final int x = px(view, h.x.center(ix)) - fm.stringWidth(s) / 2;
                    final int y = py(view, h.y.center(iy)) + fm.getAscent() / 2;
                    g.drawString(s, x, y);
                }
            }
        }
        g.setClip(clip);
        frameBox(g, frame);
        axisX(g, frame, view.x0, view.x1, view.lx, titleX(h, view), h.x.labels);
        axisY(g, frame, view.y0, view.y1, view.ly, titleY(h, view), false);
        if (palette) paletteBar(g, area, frame, colors, zr, lz, view.zTitle != null ? view.zTitle : h.zTitle);
    }

    static double[] zRange(Hist h, boolean log) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double positive = Double.POSITIVE_INFINITY;
        for (double v : h.v) {
            if (!Double.isFinite(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
            if (v > 0) positive = Math.min(positive, v);
        }
        if (!Double.isNaN(h.min)) lo = h.min;
        if (!Double.isNaN(h.max)) hi = h.max;
        if (log) lo = Double.isFinite(positive) ? positive : 1e-3;
        if (!(lo < hi)) hi = lo + 1;
        return new double[]{lo, hi};
    }

    static double fraction(double v, double[] zr, boolean log) {
        if (log) {
            if (v <= 0) return 0;
            return clamp((Math.log10(v) - Math.log10(zr[0])) / (Math.log10(zr[1]) - Math.log10(zr[0])));
        }
        return clamp((v - zr[0]) / (zr[1] - zr[0]));
    }

    private static double clamp(double f) {
        return Double.isFinite(f) ? Math.max(0, Math.min(1, f)) : 0;
    }

    /** Contour lines by marching squares, at 20 levels, in the palette's colours. */
    private static void contours(Graphics2D g, Hist h, View view, double[] zr, boolean lz, Color[] colors, String o) {
        final int nx = h.nx();
        final int ny = h.ny();
        final int levels = 20;
        g.setStroke(new BasicStroke(1.2f));
        for (int l = 1; l < levels; l++) {
            final double f = l / (double) levels;
            final double level = lz ? Math.pow(10, Math.log10(zr[0]) + f * (Math.log10(zr[1]) - Math.log10(zr[0])))
                : zr[0] + f * (zr[1] - zr[0]);
            g.setColor(o.contains("CONT3") ? inkColor() : colors[(int) Math.round(f * (colors.length - 1))]);
            for (int iy = 0; iy < ny - 1; iy++) {
                for (int ix = 0; ix < nx - 1; ix++) {
                    final double a = h.at(ix, iy);
                    final double b = h.at(ix + 1, iy);
                    final double c = h.at(ix + 1, iy + 1);
                    final double d = h.at(ix, iy + 1);
                    final double x0 = h.x.center(ix);
                    final double x1 = h.x.center(ix + 1);
                    final double y0 = h.y.center(iy);
                    final double y1 = h.y.center(iy + 1);
                    final List<double[]> cut = new ArrayList<>(4);
                    edge(cut, a, b, level, x0, y0, x1, y0);
                    edge(cut, b, c, level, x1, y0, x1, y1);
                    edge(cut, c, d, level, x1, y1, x0, y1);
                    edge(cut, d, a, level, x0, y1, x0, y0);
                    for (int k = 0; k + 1 < cut.size(); k += 2) {
                        g.draw(new Line2D.Double(px(view, cut.get(k)[0]), py(view, cut.get(k)[1]),
                            px(view, cut.get(k + 1)[0]), py(view, cut.get(k + 1)[1])));
                    }
                }
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    private static void edge(List<double[]> cut, double va, double vb, double level, double xa, double ya,
                             double xb, double yb) {
        if ((va < level) == (vb < level) || va == vb) return;
        final double t = (level - va) / (vb - va);
        cut.add(new double[]{xa + t * (xb - xa), ya + t * (yb - ya)});
    }

    /* ------------------------------------------------------------------ */
    /* Axes, frame, grid                                                   */
    /* ------------------------------------------------------------------ */

    static int px(View v, double x) {
        final double f = v.lx ? (Math.log10(Math.max(x, 1e-300)) - Math.log10(v.x0)) / (Math.log10(v.x1) - Math.log10(v.x0))
            : (x - v.x0) / (v.x1 - v.x0);
        return (int) Math.round(v.frame.x + f * v.frame.width);
    }

    static int py(View v, double y) {
        final double f = v.ly ? (Math.log10(Math.max(y, 1e-300)) - Math.log10(v.y0)) / (Math.log10(v.y1) - Math.log10(v.y0))
            : (y - v.y0) / (v.y1 - v.y0);
        final double p = v.frame.y + v.frame.height - f * v.frame.height;
        return (int) Math.round(Math.max(-1e5, Math.min(1e5, p)));
    }

    private static void frameBox(Graphics2D g, Rectangle frame) {
        g.setColor(inkColor());
        g.setStroke(new BasicStroke(1f));
        g.drawRect(frame.x, frame.y, frame.width, frame.height);
    }

    private static void grid(Graphics2D g, Rectangle frame, View v, boolean gx, boolean gy) {
        if (!gx && !gy) return;
        g.setColor(palette.getCanvasGrid());
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 2f}, 0f));
        if (gx) for (double t : ticks(v.x0, v.x1, v.lx)) g.drawLine(px(v, t), frame.y, px(v, t), frame.y + frame.height);
        if (gy) for (double t : ticks(v.y0, v.y1, v.ly)) g.drawLine(frame.x, py(v, t), frame.x + frame.width, py(v, t));
        g.setStroke(new BasicStroke(1f));
    }

    static Font labelFont(Rectangle frame) {
        final int size = Math.max(9, Math.min(15, (int) Math.round(Math.min(frame.width, frame.height * 1.4) / 42.0)));
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    private static void axisX(Graphics2D g, Rectangle frame, double lo, double hi, boolean log, String title,
                              String[] labels) {
        g.setFont(labelFont(frame));
        final FontMetrics fm = g.getFontMetrics();
        g.setColor(inkColor());
        final View mapping = mapping(frame, lo, hi, log, 0, 1, false);
        final int bottom = frame.y + frame.height;
        if (labels != null && labels.length > 0) {
            for (int i = 0; i < labels.length; i++) {
                final double c = lo + (i + 0.5) * (hi - lo) / labels.length;
                final int x = px(mapping, c);
                g.drawString(labels[i], x - fm.stringWidth(labels[i]) / 2, bottom + fm.getAscent() + 3);
            }
        } else {
            final double[] major = ticks(lo, hi, log);
            for (double t : minorTicks(lo, hi, log, major)) {
                final int x = px(mapping, t);
                g.drawLine(x, bottom, x, bottom - 3);
            }
            for (double t : major) {
                final int x = px(mapping, t);
                g.drawLine(x, bottom, x, bottom - 7);
                g.drawLine(x, frame.y, x, frame.y + 4);
                final String s = tickLabel(t, major, log);
                g.drawString(s, x - fm.stringWidth(s) / 2, bottom + fm.getAscent() + 3);
            }
        }
        if (title != null && !title.isEmpty()) {
            final String t = RootLatex.plain(title);
            g.drawString(t, frame.x + frame.width - fm.stringWidth(t), bottom + 2 * fm.getHeight() + 4);
        }
    }

    private static void axisY(Graphics2D g, Rectangle frame, double lo, double hi, boolean log, String title,
                              boolean rightTicks) {
        g.setFont(labelFont(frame));
        final FontMetrics fm = g.getFontMetrics();
        g.setColor(inkColor());
        final View mapping = mapping(frame, 0, 1, false, lo, hi, log);
        final double[] major = ticks(lo, hi, log);
        int widest = 0;
        for (double t : minorTicks(lo, hi, log, major)) {
            final int y = py(mapping, t);
            g.drawLine(frame.x, y, frame.x + 3, y);
        }
        for (double t : major) {
            final int y = py(mapping, t);
            g.drawLine(frame.x, y, frame.x + 7, y);
            if (rightTicks) g.drawLine(frame.x + frame.width, y, frame.x + frame.width - 4, y);
            final String s = tickLabel(t, major, log);
            widest = Math.max(widest, fm.stringWidth(s));
            g.drawString(s, frame.x - fm.stringWidth(s) - 4, y + fm.getAscent() / 2 - 1);
        }
        if (title != null && !title.isEmpty()) {
            final String t = RootLatex.plain(title);
            final AffineTransform saved = g.getTransform();
            g.rotate(-Math.PI / 2, frame.x - widest - 10, frame.y);
            g.drawString(t, frame.x - widest - 10 - fm.stringWidth(t), frame.y);
            g.setTransform(saved);
        }
    }

    private static void paletteBar(Graphics2D g, Rectangle area, Rectangle frame, Color[] colors, double[] zr, boolean lz,
                                   String title) {
        final int x = frame.x + frame.width + Math.max(4, area.width / 100);
        final int w = Math.max(8, area.width / 40);
        for (int i = 0; i < frame.height; i++) {
            final double f = 1 - i / (double) frame.height;
            g.setColor(colors[(int) Math.round(f * (colors.length - 1))]);
            g.fillRect(x, frame.y + i, w, 1);
        }
        g.setColor(inkColor());
        g.drawRect(x, frame.y, w, frame.height);
        g.setFont(labelFont(frame));
        final FontMetrics fm = g.getFontMetrics();
        final View mapping = mapping(frame, 0, 1, false, zr[0], zr[1], lz);
        final double[] major = ticks(zr[0], zr[1], lz);
        for (double t : major) {
            final int y = py(mapping, t);
            g.drawLine(x + w - 4, y, x + w, y);
            g.drawString(tickLabel(t, major, lz), x + w + 3, y + fm.getAscent() / 2 - 1);
        }
    }

    static View mapping(Rectangle frame, double x0, double x1, boolean lx, double y0, double y1, boolean ly) {
        final View v = new View();
        v.frame.setBounds(frame);
        v.x0 = x0;
        v.x1 = x1;
        v.lx = lx;
        v.y0 = y0;
        v.y1 = y1;
        v.ly = ly;
        return v;
    }

    /** Round-numbered ticks between lo and hi: 1, 2 or 5 times a power of ten, or decades on a log axis. */
    static double[] ticks(double lo, double hi, boolean log) {
        if (!(hi > lo)) return new double[0];
        if (log && lo > 0) {
            final List<Double> out = new ArrayList<>();
            for (int e = (int) Math.floor(Math.log10(lo)); e <= (int) Math.ceil(Math.log10(hi)); e++) {
                final double v = Math.pow(10, e);
                if (v >= lo * 0.999 && v <= hi * 1.001) out.add(v);
            }
            if (out.size() < 2) return ticks(lo, hi, false);
            return out.stream().mapToDouble(Double::doubleValue).toArray();
        }
        final double step = niceStep((hi - lo) / 7);
        final double first = Math.ceil(lo / step - 1e-9) * step;
        final List<Double> out = new ArrayList<>();
        for (double t = first; t <= hi + step * 1e-9 && out.size() < 50; t += step) {
            out.add(Math.abs(t) < step * 1e-9 ? 0 : t);
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double[] minorTicks(double lo, double hi, boolean log, double[] major) {
        final List<Double> out = new ArrayList<>();
        if (log && lo > 0) {
            for (int e = (int) Math.floor(Math.log10(lo)); e <= (int) Math.ceil(Math.log10(hi)); e++) {
                for (int k = 2; k <= 9; k++) {
                    final double v = k * Math.pow(10, e);
                    if (v > lo && v < hi) out.add(v);
                }
            }
        } else if (major.length >= 2) {
            final double step = (major[1] - major[0]) / 5;
            for (double t = major[0] - 5 * step; t <= hi; t += step) if (t > lo) out.add(t);
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double niceStep(double raw) {
        if (!(raw > 0)) return 1;
        final double power = Math.pow(10, Math.floor(Math.log10(raw)));
        final double f = raw / power;
        return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * power;
    }

    static String tickLabel(double t, double[] all, boolean log) {
        if (log) {
            final double e = Math.log10(t);
            if (Math.abs(e - Math.round(e)) < 1e-6) {
                final long p = Math.round(e);
                if (p >= -2 && p <= 3) return compact(t);
                return "10" + RootLatex.superscript(Long.toString(p));
            }
        }
        final double step = all.length >= 2 ? Math.abs(all[1] - all[0]) : Math.abs(t);
        final double big = Math.max(Math.abs(all.length > 0 ? all[0] : t), Math.abs(all.length > 0 ? all[all.length - 1] : t));
        if (big >= 1e5 || (big < 1e-3 && big > 0)) return String.format(Locale.ROOT, "%.3g", t);
        final int decimals = step >= 1 ? 0 : (int) Math.ceil(-Math.log10(step) - 1e-9);
        return String.format(Locale.ROOT, "%." + Math.max(0, Math.min(8, decimals)) + "f", t);
    }

    static String compact(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.4g", v);
    }

    private static String titleX(Item main, View view) {
        if (view.xTitle != null) return view.xTitle;
        if (main instanceof Hist h) return h.x.title;
        if (main instanceof Graph g) return g.xTitle;
        if (main instanceof Group group && !group.items.isEmpty()) return titleX(group.items.get(0), view);
        return "";
    }

    private static String titleY(Item main, View view) {
        if (view.yTitle != null) return view.yTitle;
        if (main instanceof Hist h) return h.dim == 1 ? h.yTitle : h.y.title;
        if (main instanceof Graph g) return g.yTitle;
        return "";
    }

    private static String[] axisLabels(Item main) {
        return main instanceof Hist h ? h.x.labels : null;
    }

    /* ------------------------------------------------------------------ */
    /* Title, statistics, texts, legends                                   */
    /* ------------------------------------------------------------------ */

    private static void title(Graphics2D g, Rectangle area, Pad pad, RootScene scene) {
        if (scene.optTitle == 0) return;
        for (Item i : pad.items) if (i instanceof Pave p && p.isTitle()) return;
        final Item main = pad.main();
        if (main == null || main.title == null || main.title.isEmpty()) return;
        final String t = RootLatex.plain(main.title);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, Math.min(20, area.height / 22))));
        g.setColor(inkColor());
        final FontMetrics fm = g.getFontMetrics();
        g.drawString(t, area.x + (area.width - fm.stringWidth(t)) / 2, area.y + (int) (pad.tm * area.height * 0.5) + fm.getAscent() / 2);
    }

    private static void stats(Graphics2D g, Rectangle area, Pad pad, View view, RootScene scene) {
        if (scene.optStat == 0 || view.statsOff) return;
        if (!(pad.main() instanceof Hist h) || !h.stats || h.isFunction() || h.dim == 3) return;
        final List<String[]> lines = new ArrayList<>();
        final int stat = scene.optStat;
        if (stat % 10 != 0) lines.add(new String[]{h.name, ""});
        if (stat / 10 % 10 != 0) lines.add(new String[]{"Entries", compact(h.entries)});
        if (stat / 100 % 10 != 0) {
            lines.add(new String[]{h.dim == 2 ? "Mean x" : "Mean", String.format(Locale.ROOT, "%.4g", h.mean)});
            if (h.dim == 2) lines.add(new String[]{"Mean y", String.format(Locale.ROOT, "%.4g", h.meanY)});
        }
        if (stat / 1000 % 10 != 0) {
            lines.add(new String[]{h.dim == 2 ? "Std Dev x" : "Std Dev", String.format(Locale.ROOT, "%.4g", h.std)});
            if (h.dim == 2) lines.add(new String[]{"Std Dev y", String.format(Locale.ROOT, "%.4g", h.stdY)});
        }
        if (lines.isEmpty()) return;
        final int x2 = area.x + (int) Math.round(0.98 * area.width);
        final int w = (int) Math.round(0.20 * area.width);
        final int lineH = Math.max(12, Math.min(18, area.height / 26));
        final int y1 = area.y + (int) Math.round(0.065 * area.height);
        final int h2 = lineH * lines.size() + 4;
        g.setColor(palette.getCanvasBoxFill());
        g.fillRect(x2 - w, y1, w, h2);
        g.setColor(palette.getCanvasBoxBorder());
        g.drawRect(x2 - w, y1, w, h2);
        g.drawLine(x2 - w + 2, y1 + h2 + 1, x2 + 1, y1 + h2 + 1);
        g.drawLine(x2 + 1, y1 + 2, x2 + 1, y1 + h2 + 1);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(9, lineH - 5)));
        g.setColor(inkOn(palette.getCanvasBoxFill()));
        final FontMetrics fm = g.getFontMetrics();
        for (int k = 0; k < lines.size(); k++) {
            final String[] l = lines.get(k);
            final int y = y1 + 2 + lineH * k + (lineH + fm.getAscent()) / 2 - 2;
            if (l[1].isEmpty()) {
                g.drawString(l[0], x2 - w + (w - fm.stringWidth(l[0])) / 2, y);
                if (lines.size() > 1) g.drawLine(x2 - w, y1 + lineH + 2, x2, y1 + lineH + 2);
            } else {
                g.drawString(l[0], x2 - w + 4, y);
                g.drawString(l[1], x2 - 4 - fm.stringWidth(l[1]), y);
            }
        }
    }

    private static void overlays(Graphics2D g, Rectangle area, Rectangle frame, Pad pad, View view) {
        if (view.mode == Mode.EMPTY && pad.holdsSpace()) space(g, area, pad);
        for (Item item : pad.items) {
            if (item instanceof RootScene.Shape s) shape(g, area, pad, view, s);
        }
        for (Item item : pad.items) {
            if (item instanceof Pave p) pave(g, area, p);
            else if (item instanceof Text t) text(g, area, pad, view, t);
            else if (item instanceof Segment s) segment(g, area, pad, view, s);
        }
    }

    /** A point of the pad, in pixels: NDC, the frame's axes, or the pad's own range. */
    private static double[] at(Rectangle area, Pad pad, View view, boolean ndc, double x, double y) {
        if (!ndc && (view.mode == Mode.ONE_D || view.mode == Mode.FLAT)) return new double[]{px(view, x), py(view, y)};
        if (!ndc && pad.hasUserRange()) {
            return new double[]{area.x + (x - pad.ux1) / (pad.ux2 - pad.ux1) * area.width,
                area.y + area.height - (y - pad.uy1) / (pad.uy2 - pad.uy1) * area.height};
        }
        return new double[]{area.x + x * area.width, area.y + area.height - y * area.height};
    }

    /** A length along x in pixels, for radii. */
    private static double spanX(Rectangle area, Pad pad, View view, double r) {
        final double[] a = at(area, pad, view, false, 0, 0);
        final double[] b = at(area, pad, view, false, r, 0);
        return Math.abs(b[0] - a[0]);
    }

    private static double spanY(Rectangle area, Pad pad, View view, double r) {
        final double[] a = at(area, pad, view, false, 0, 0);
        final double[] b = at(area, pad, view, false, 0, r);
        return Math.abs(b[1] - a[1]);
    }

    /** TBox, TEllipse, TPolyLine, TMarker, TArrow: what a slide is made of. */
    private static void shape(Graphics2D g, Rectangle area, Pad pad, View view, RootScene.Shape s) {
        final Color fill = s.hasFill() ? paper(s.fill) : null;
        final Color line = ink(s.line);
        java.awt.Shape outline = null;
        switch (s.kind) {
            case "box" -> {
                final double[] a = at(area, pad, view, s.ndc, s.x1, s.y1);
                final double[] b = at(area, pad, view, s.ndc, s.x2, s.y2);
                outline = new java.awt.geom.Rectangle2D.Double(Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                    Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            }
            case "ellipse" -> {
                final double[] c = at(area, pad, view, false, s.x1, s.y1);
                final double rx = spanX(area, pad, view, s.r1);
                final double ry = spanY(area, pad, view, s.r2 == 0 ? s.r1 : s.r2);
                final double extent = s.phimax - s.phimin;
                final java.awt.Shape e = Math.abs(extent) >= 360
                    ? new java.awt.geom.Ellipse2D.Double(c[0] - rx, c[1] - ry, 2 * rx, 2 * ry)
                    : new java.awt.geom.Arc2D.Double(c[0] - rx, c[1] - ry, 2 * rx, 2 * ry, s.phimin, extent,
                        java.awt.geom.Arc2D.PIE);
                outline = s.theta == 0 ? e
                    : AffineTransform.getRotateInstance(-Math.toRadians(s.theta), c[0], c[1]).createTransformedShape(e);
            }
            case "polyline" -> {
                final java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
                for (int i = 0; i < Math.min(s.xs.length, s.ys.length); i++) {
                    final double[] q = at(area, pad, view, s.ndc, s.xs[i], s.ys[i]);
                    if (i == 0) path.moveTo(q[0], q[1]);
                    else path.lineTo(q[0], q[1]);
                }
                if (s.opt().contains("F")) path.closePath();
                outline = path;
            }
            case "marker" -> {
                final double[] q = at(area, pad, view, s.ndc, s.x1, s.y1);
                marker(g, s.markerStyle, s.markerSize, ink(s.marker), q[0], q[1]);
                return;
            }
            case "arrow" -> {
                final double[] a = at(area, pad, view, s.ndc, s.x1, s.y1);
                final double[] b = at(area, pad, view, s.ndc, s.x2, s.y2);
                stroke(g, s, line);
                g.draw(new java.awt.geom.Line2D.Double(a[0], a[1], b[0], b[1]));
                final double size = Math.max(6, s.arrowSize * Math.min(area.width, area.height));
                final String o = s.arrowOption == null ? "|>" : s.arrowOption;
                if (o.contains(">")) arrowHead(g, a, b, size, o.contains("|>"), fill == null ? line : fill);
                if (o.contains("<")) arrowHead(g, b, a, size, o.contains("<|"), fill == null ? line : fill);
                g.setStroke(new BasicStroke(1f));
                return;
            }
            default -> {
                return;
            }
        }
        if (fill != null && !"polyline".equals(s.kind) || fill != null && s.opt().contains("F")) {
            g.setColor(fill);
            g.fill(outline);
        }
        stroke(g, s, line);
        g.draw(outline);
        g.setStroke(new BasicStroke(1f));
    }

    private static void arrowHead(Graphics2D g, double[] from, double[] to, double size, boolean filled, Color color) {
        final double angle = Math.atan2(to[1] - from[1], to[0] - from[0]);
        final java.awt.geom.Path2D.Double head = new java.awt.geom.Path2D.Double();
        head.moveTo(to[0], to[1]);
        head.lineTo(to[0] - size * Math.cos(angle - 0.4), to[1] - size * Math.sin(angle - 0.4));
        head.lineTo(to[0] - size * Math.cos(angle + 0.4), to[1] - size * Math.sin(angle + 0.4));
        head.closePath();
        g.setColor(color);
        if (filled) g.fill(head);
        else g.draw(head);
    }

    /**
     * A cloud of TPolyMarker3D points, a TPolyLine3D or a geometry, seen from
     * the pad's TView: a still preview of what Sphere's 3D space turns.
     */
    private static void space(Graphics2D g, Rectangle area, Pad pad) {
        final double lat = Math.toRadians(Double.isNaN(pad.viewLat) ? 30 : pad.viewLat);
        final double lon = Math.toRadians(Double.isNaN(pad.viewLon) ? 30 : pad.viewLon);
        double[] lo = pad.viewMin;
        double[] hi = pad.viewMax;
        if (lo == null || hi == null || lo.length < 3 || hi.length < 3) {
            lo = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            hi = new double[]{-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (Item i : pad.items) {
                final float[][] all = i instanceof RootScene.Cloud3D c ? new float[][]{c.p}
                    : i instanceof RootScene.Geometry geo ? geo.meshes.stream().map(m -> m.p).toArray(float[][]::new)
                    : new float[0][];
                for (float[] p : all) {
                    for (int k = 0; k + 2 < p.length; k += 3) {
                        for (int a = 0; a < 3; a++) {
                            lo[a] = Math.min(lo[a], p[k + a]);
                            hi[a] = Math.max(hi[a], p[k + a]);
                        }
                    }
                }
            }
            if (lo[0] > hi[0]) return;
        }
        final double[] c = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        final double r = Math.max(1e-12, Math.sqrt((hi[0] - lo[0]) * (hi[0] - lo[0]) + (hi[1] - lo[1]) * (hi[1] - lo[1]) + (hi[2] - lo[2]) * (hi[2] - lo[2])));
        final double scale = 0.85 * Math.min(area.width, area.height) / r;
        final java.awt.Shape clipWas = g.getClip();
        g.clipRect(area.x, area.y, area.width, area.height);
        final double cl = Math.cos(lon);
        final double sl = Math.sin(lon);
        final double ct = Math.cos(lat);
        final double st = Math.sin(lat);
        for (Item i : pad.items) {
            if (i instanceof RootScene.Cloud3D cloud) {
                final Color color = cloud.isLine() ? ink(cloud.line)
                    : ink(cloud.marker);
                g.setColor(color);
                double px0 = 0;
                double py0 = 0;
                for (int k = 0; k + 2 < cloud.p.length; k += 3) {
                    final double x = cloud.p[k] - c[0];
                    final double y = cloud.p[k + 1] - c[1];
                    final double z = cloud.p[k + 2] - c[2];
                    final double u = -x * sl + y * cl;
                    final double v = -(x * cl + y * sl) * st + z * ct;
                    final double sx = area.getCenterX() + u * scale;
                    final double sy = area.getCenterY() - v * scale;
                    if (cloud.isLine() && k > 0) g.draw(new java.awt.geom.Line2D.Double(px0, py0, sx, sy));
                    else if (!cloud.isLine()) g.fillOval((int) sx - 1, (int) sy - 1, 3, 3);
                    px0 = sx;
                    py0 = sy;
                }
            } else if (i instanceof RootScene.Geometry geo) {
                for (RootScene.GeoMesh m : geo.meshes) {
                    g.setColor(translucent(m.color, 120));
                    int k = 0;
                    while (k < m.pol.length) {
                        final int n = m.pol[k];
                        final java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
                        for (int j = 0; j < n && k + 1 + j < m.pol.length; j++) {
                            final int v3 = 3 * m.pol[k + 1 + j];
                            if (v3 + 2 >= m.p.length) continue;
                            final double x = m.p[v3] - c[0];
                            final double y = m.p[v3 + 1] - c[1];
                            final double z = m.p[v3 + 2] - c[2];
                            final double u = -x * sl + y * cl;
                            final double v = -(x * cl + y * sl) * st + z * ct;
                            if (j == 0) path.moveTo(area.getCenterX() + u * scale, area.getCenterY() - v * scale);
                            else path.lineTo(area.getCenterX() + u * scale, area.getCenterY() - v * scale);
                        }
                        path.closePath();
                        g.draw(path);
                        k += n + 1;
                    }
                }
            }
        }
        g.setColor(palette.getCanvasSelection());
        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        g.drawString("3D: open in Sphere's 3D space to turn it", area.x + 6, area.y + area.height - 6);
        g.setClip(clipWas);
    }

    private static int ndcX(Rectangle area, double x) {
        return area.x + (int) Math.round(x * area.width);
    }

    private static int ndcY(Rectangle area, double y) {
        return area.y + area.height - (int) Math.round(y * area.height);
    }

    private static void pave(Graphics2D g, Rectangle area, Pave p) {
        final int x1 = ndcX(area, Math.min(p.x1, p.x2));
        final int x2 = ndcX(area, Math.max(p.x1, p.x2));
        final int y1 = ndcY(area, Math.max(p.y1, p.y2));
        final int y2 = ndcY(area, Math.min(p.y1, p.y2));
        final int w = Math.max(1, x2 - x1);
        final int h = Math.max(1, y2 - y1);
        if (p.hasFill() || p.fill != null) {
            g.setColor(p.fill == null ? palette.getCanvasBoxFill() : paper(p.fill));
            if (p.fillStyle != 0) g.fillRect(x1, y1, w, h);
        }
        if (p.border > 0) {
            g.setColor(p.line == null ? palette.getCanvasBoxBorder() : ink(p.line));
            g.drawRect(x1, y1, w, h);
            if (p.border > 1) {
                g.fillRect(x1 + p.border, y2, w, p.border);
                g.fillRect(x2, y1 + p.border, p.border, h);
            }
        }
        if (p.lines.isEmpty()) return;
        final int lineH = h / p.lines.size();
        final int fontSize = Math.max(8, Math.min(p.isTitle() ? 22 : 16, (int) (lineH * 0.7)));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
        final FontMetrics fm = g.getFontMetrics();
        for (int k = 0; k < p.lines.size(); k++) {
            final Entry e = p.lines.get(k);
            final String s = RootLatex.plain(e.text);
            final int y = y1 + lineH * k + (lineH + fm.getAscent()) / 2 - 2;
            if (p.isLegend()) {
                final int sample = Math.min(w / 4, 40);
                legendSample(g, e, x1 + 6, y - fm.getAscent() / 2, sample - 8, Math.max(6, lineH - 6));
                g.setColor(inkOn(p.fill == null ? palette.getCanvasBoxFill() : paper(p.fill)));
                g.drawString(s, x1 + sample + 2, y);
            } else {
                g.setColor(text(e.color, p.fill == null ? palette.getCanvasBoxFill() : paper(p.fill)));
                g.drawString(s, x1 + (w - fm.stringWidth(s)) / 2, y);
            }
        }
    }

    private static void legendSample(Graphics2D g, Entry e, int x, int yMid, int w, int h) {
        final String o = e.option == null ? "" : e.option.toLowerCase(Locale.ROOT);
        if (o.contains("f") && e.fill != null && e.fillStyle > 0) {
            g.setColor(e.fill);
            g.fillRect(x, yMid - h / 2, w, h);
        }
        if (o.contains("l") || o.contains("e")) {
            g.setColor(ink(e.line));
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(x, yMid, x + w, yMid);
            if (o.contains("e")) g.drawLine(x + w / 2, yMid - h / 2, x + w / 2, yMid + h / 2);
            g.setStroke(new BasicStroke(1f));
        }
        if (o.contains("p")) marker(g, e.markerStyle, 1, ink(e.marker), x + w / 2, yMid);
    }

    private static void text(Graphics2D g, Rectangle area, Pad pad, View view, Text t) {
        final int x;
        final int y;
        if (t.ndc || view.mode == Mode.THREE_D || view.mode == Mode.EMPTY && !pad.hasUserRange()) {
            x = ndcX(area, t.x);
            y = ndcY(area, t.y);
        } else {
            final double[] q = at(area, pad, view, false, t.x, t.y);
            x = (int) Math.round(q[0]);
            y = (int) Math.round(q[1]);
        }
        final String s = RootLatex.plain(t.text);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, (int) Math.round(t.size * area.height * 0.8))));
        g.setColor(text(t.color));
        final FontMetrics fm = g.getFontMetrics();
        final int horizontal = t.align / 10;
        final int vertical = t.align % 10;
        final int dx = horizontal == 2 ? -fm.stringWidth(s) / 2 : horizontal == 3 ? -fm.stringWidth(s) : 0;
        final int dy = vertical == 2 ? fm.getAscent() / 2 : vertical == 3 ? fm.getAscent() : 0;
        final AffineTransform saved = g.getTransform();
        if (t.angle != 0) g.rotate(-Math.toRadians(t.angle), x, y);
        g.drawString(s, x + dx, y + dy);
        g.setTransform(saved);
    }

    private static void segment(Graphics2D g, Rectangle area, Pad pad, View view, Segment s) {
        g.setColor(ink(s.line));
        g.setStroke(new BasicStroke((float) Math.max(1, s.lineWidth)));
        if (!s.ndc && view.mode == Mode.EMPTY && pad.hasUserRange()) {
            final double[] a = at(area, pad, view, false, s.x1, s.y1);
            final double[] b = at(area, pad, view, false, s.x2, s.y2);
            g.draw(new java.awt.geom.Line2D.Double(a[0], a[1], b[0], b[1]));
        } else if (s.ndc || view.mode != Mode.ONE_D && view.mode != Mode.FLAT) {
            g.drawLine(ndcX(area, s.x1), ndcY(area, s.y1), ndcX(area, s.x2), ndcY(area, s.y2));
        } else {
            g.drawLine(px(view, s.x1), py(view, s.y1), px(view, s.x2), py(view, s.y2));
        }
        g.setStroke(new BasicStroke(1f));
    }

    /* ------------------------------------------------------------------ */
    /* Strokes, fills, markers                                             */
    /* ------------------------------------------------------------------ */

    static void stroke(Graphics2D g, Item item, Color color) {
        g.setColor(color);
        final float w = (float) Math.max(1, item.lineWidth);
        g.setStroke(switch (item.lineStyle) {
            case 2 -> new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 5f}, 0f);
            case 3 -> new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{2f, 4f}, 0f);
            case 4 -> new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 4f, 2f, 4f}, 0f);
            default -> new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
        });
    }

    /** A fill style of ROOT: 1001 is solid, the 3000s are hatches, shown here as a lighter wash. */
    static Color fillColor(Item item) {
        final Color c = item.fill == null ? palette.getCanvasFillDefault() : paper(item.fill);
        if (item.fillStyle >= 3000 && item.fillStyle < 4000) return translucent(c, 110);
        if (item.fillStyle > 4000 && item.fillStyle < 4100) return translucent(c, (int) (255 * (item.fillStyle - 4000) / 100.0));
        return c;
    }

    static Color translucent(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /** ROOT's marker styles: 1 a dot, 2 +, 3 *, 4 o, 5 x, 20 to 34 the filled and open shapes. */
    static void marker(Graphics2D g, int style, double size, Color color, double x, double y) {
        g.setColor(color);
        final double s = Math.max(2, 8 * size) / 2;
        final Stroke saved = new Stroke(g);
        g.setStroke(new BasicStroke(1f));
        switch (style) {
            case 1, 6 -> g.fill(new Rectangle2D.Double(x - 0.5, y - 0.5, 1.5, 1.5));
            case 7 -> g.fill(new Rectangle2D.Double(x - 1.5, y - 1.5, 3, 3));
            case 2, 28 -> {
                g.draw(new Line2D.Double(x - s, y, x + s, y));
                g.draw(new Line2D.Double(x, y - s, x, y + s));
            }
            case 3, 31 -> {
                g.draw(new Line2D.Double(x - s, y, x + s, y));
                g.draw(new Line2D.Double(x, y - s, x, y + s));
                g.draw(new Line2D.Double(x - s * 0.7, y - s * 0.7, x + s * 0.7, y + s * 0.7));
                g.draw(new Line2D.Double(x - s * 0.7, y + s * 0.7, x + s * 0.7, y - s * 0.7));
            }
            case 5 -> {
                g.draw(new Line2D.Double(x - s * 0.8, y - s * 0.8, x + s * 0.8, y + s * 0.8));
                g.draw(new Line2D.Double(x - s * 0.8, y + s * 0.8, x + s * 0.8, y - s * 0.8));
            }
            case 4, 24 -> g.draw(new Ellipse2D.Double(x - s, y - s, 2 * s, 2 * s));
            case 8, 20 -> g.fill(new Ellipse2D.Double(x - s, y - s, 2 * s, 2 * s));
            case 21 -> g.fill(new Rectangle2D.Double(x - s, y - s, 2 * s, 2 * s));
            case 25 -> g.draw(new Rectangle2D.Double(x - s, y - s, 2 * s, 2 * s));
            case 22, 26 -> triangle(g, x, y, s, true, style == 22);
            case 23, 32 -> triangle(g, x, y, s, false, style == 23);
            case 27, 33 -> {
                final Path2D d = new Path2D.Double();
                d.moveTo(x, y - s);
                d.lineTo(x + s * 0.7, y);
                d.lineTo(x, y + s);
                d.lineTo(x - s * 0.7, y);
                d.closePath();
                if (style == 33) g.fill(d);
                else g.draw(d);
            }
            case 29, 30 -> {
                final Path2D star = new Path2D.Double();
                for (int k = 0; k < 10; k++) {
                    final double r = k % 2 == 0 ? s : s * 0.45;
                    final double a = -Math.PI / 2 + k * Math.PI / 5;
                    if (k == 0) star.moveTo(x + r * Math.cos(a), y + r * Math.sin(a));
                    else star.lineTo(x + r * Math.cos(a), y + r * Math.sin(a));
                }
                star.closePath();
                if (style == 29) g.fill(star);
                else g.draw(star);
            }
            default -> g.fill(new Ellipse2D.Double(x - s, y - s, 2 * s, 2 * s));
        }
        saved.restore(g);
    }

    private static void triangle(Graphics2D g, double x, double y, double s, boolean up, boolean filled) {
        final Path2D t = new Path2D.Double();
        final double d = up ? -1 : 1;
        t.moveTo(x, y + d * s);
        t.lineTo(x + s, y - d * s);
        t.lineTo(x - s, y - d * s);
        t.closePath();
        if (filled) g.fill(t);
        else g.draw(t);
    }

    /** Keeps a stroke to put back. */
    private static final class Stroke {
        private final java.awt.Stroke stroke;

        Stroke(Graphics2D g) {
            this.stroke = g.getStroke();
        }

        void restore(Graphics2D g) {
            g.setStroke(stroke);
        }
    }
}
