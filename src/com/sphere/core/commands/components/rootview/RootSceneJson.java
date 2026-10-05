package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.Axis;
import com.sphere.components.rootview.RootScene.AxisStyle;
import com.sphere.components.rootview.RootScene.Cloud3D;
import com.sphere.components.rootview.RootScene.Entry;
import com.sphere.components.rootview.RootScene.GeoMesh;
import com.sphere.components.rootview.RootScene.Geometry;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.components.rootview.RootScene.Pave;
import com.sphere.components.rootview.RootScene.Segment;
import com.sphere.components.rootview.RootScene.Shape;
import com.sphere.components.rootview.RootScene.Text;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/**
 * A scene written back as the JSON sphere_view.hpp writes, with the
 * attributes the context menus change: what makes Undo and Redo, a clone
 * in a new tab, and a canvas saved as .sphere.json. RootScene.parse reads it.
 */
public final class RootSceneJson {

    private RootSceneJson() {
    }

    public static String write(RootScene s) {
        final StringBuilder b = new StringBuilder(4096);
        b.append("{\"k\":\"canvas\",\"n\":").append(q(s.name)).append(",\"t\":").append(q(s.title))
            .append(",\"w\":").append(s.width).append(",\"h\":").append(s.height)
            .append(",\"optstat\":").append(s.optStat).append(",\"opttitle\":").append(s.optTitle)
            .append(",\"optfit\":").append(s.optFit).append(",\"statfmt\":").append(q(s.statFormat))
            .append(",\"fitfmt\":").append(q(s.fitFormat)).append(",\"statopt\":").append(q(s.statOption))
            .append(",\"gray\":").append(s.grayscale).append(",\"fixed\":").append(s.fixedAspect)
            .append(",\"palette\":[");
        for (int i = 0; i < s.palette.length; i++) b.append(i == 0 ? "" : ",").append(c(s.palette[i]));
        b.append("],\"pad\":");
        pad(b, s.pad);
        return b.append('}').toString();
    }

    /** A deep copy, through the JSON. */
    public static RootScene copy(RootScene s) {
        return RootScene.parse(write(s));
    }

    /** An item alone, deep-copied. */
    public static Item copy(Item i) {
        final RootScene s = new RootScene();
        s.pad.items.add(i);
        final RootScene c = copy(s);
        return c.pad.items.isEmpty() ? null : c.pad.items.get(0);
    }

    /** A pad alone, deep-copied, as the pad of a new canvas. */
    public static Pad copy(Pad p) {
        final RootScene s = new RootScene();
        s.pad = p;
        return copy(s).pad;
    }

    private static void pad(StringBuilder b, Pad p) {
        b.append("{\"n\":").append(q(p.name)).append(",\"t\":").append(q(p.title))
            .append(",\"px\":").append(n(p.px)).append(",\"py\":").append(n(p.py))
            .append(",\"pw\":").append(n(p.pw)).append(",\"ph\":").append(n(p.ph))
            .append(",\"logx\":").append(p.logx).append(",\"logy\":").append(p.logy).append(",\"logz\":").append(p.logz)
            .append(",\"gridx\":").append(p.gridx).append(",\"gridy\":").append(p.gridy)
            .append(",\"theta\":").append(n(p.theta)).append(",\"phi\":").append(n(p.phi))
            .append(",\"fc\":").append(c(p.fill))
            .append(",\"lm\":").append(n(p.lm)).append(",\"rm\":").append(n(p.rm))
            .append(",\"bm\":").append(n(p.bm)).append(",\"tm\":").append(n(p.tm))
            .append(",\"tickx\":").append(p.tickx).append(",\"ticky\":").append(p.ticky)
            .append(",\"bmode\":").append(p.borderMode).append(",\"bsize\":").append(p.borderSize)
            .append(",\"cross\":").append(p.crosshair).append(",\"editable\":").append(p.editable)
            .append(",\"fixed\":").append(p.fixedAspect)
            .append(",\"spx\":").append(p.showProjectionX).append(",\"spy\":").append(p.showProjectionY)
            .append(",\"ux1\":").append(n(p.ux1)).append(",\"uy1\":").append(n(p.uy1))
            .append(",\"ux2\":").append(n(p.ux2)).append(",\"uy2\":").append(n(p.uy2));
        if (p.frameFill != null) b.append(",\"ffc\":").append(c(p.frameFill)).append(",\"ffs\":").append(p.frameFillStyle);
        if (p.viewMin != null && p.viewMax != null) {
            b.append(",\"vmin\":").append(arr(p.viewMin)).append(",\"vmax\":").append(arr(p.viewMax))
                .append(",\"vlat\":").append(n(p.viewLat)).append(",\"vlon\":").append(n(p.viewLon));
        }
        b.append(",\"ax\":[");
        for (int a = 0; a < 3; a++) {
            if (a > 0) b.append(',');
            style(b, p.axes[a]);
        }
        b.append("],\"items\":[");
        for (int i = 0; i < p.items.size(); i++) {
            if (i > 0) b.append(',');
            item(b, p.items.get(i));
        }
        b.append("],\"pads\":[");
        for (int i = 0; i < p.pads.size(); i++) {
            if (i > 0) b.append(',');
            pad(b, p.pads.get(i));
        }
        b.append("]}");
    }

    static void style(StringBuilder b, AxisStyle s) {
        b.append("{\"nd\":").append(s.ndivisions).append(",\"opt\":").append(s.optimize)
            .append(",\"ac\":").append(c(s.axisColor)).append(",\"lc\":").append(c(s.labelColor))
            .append(",\"lf\":").append(s.labelFont).append(",\"lo\":").append(n(s.labelOffset))
            .append(",\"ls\":").append(n(s.labelSize)).append(",\"tl\":").append(n(s.tickLength))
            .append(",\"to\":").append(n(s.titleOffset)).append(",\"ts\":").append(n(s.titleSize))
            .append(",\"tc\":").append(c(s.titleColor)).append(",\"tf\":").append(s.titleFont)
            .append(",\"md\":").append(s.maxDigits).append(",\"mll\":").append(s.moreLogLabels)
            .append(",\"nexp\":").append(s.noExponent).append(",\"dec\":").append(s.decimals)
            .append(",\"time\":").append(s.timeDisplay).append(",\"tfmt\":").append(q(s.timeFormat))
            .append(",\"rot\":").append(s.rotateTitle).append(",\"ctr\":").append(s.centerTitle)
            .append(",\"clab\":").append(s.centerLabels).append(",\"ticks\":").append(q(s.ticks))
            .append(",\"lopt\":").append(q(s.labelsOption)).append(",\"lw\":").append(n(s.lineWidth)).append('}');
    }

    private static void head(StringBuilder b, Item i) {
        b.append("{\"k\":").append(q(i.kind)).append(",\"c\":").append(q(i.className)).append(",\"n\":").append(q(i.name))
            .append(",\"t\":").append(q(i.title)).append(",\"o\":").append(q(i.option))
            .append(",\"lc\":").append(c(i.line)).append(",\"lw\":").append(n(i.lineWidth)).append(",\"ls\":").append(i.lineStyle)
            .append(",\"fc\":").append(c(i.fill)).append(",\"fs\":").append(i.fillStyle)
            .append(",\"mc\":").append(c(i.marker)).append(",\"mst\":").append(i.markerStyle)
            .append(",\"msz\":").append(n(i.markerSize)).append(",\"hl\":").append(i.highlight)
            .append(",\"ed\":").append(i.editable);
    }

    static void item(StringBuilder b, Item i) {
        head(b, i);
        if (i instanceof Hist h) {
            b.append(",\"x\":");
            axis(b, h.x);
            if (h.y != null) {
                b.append(",\"y\":");
                axis(b, h.y);
            }
            if (h.z != null) {
                b.append(",\"z\":");
                axis(b, h.z);
            }
            b.append(",\"yt\":").append(q(h.yTitle)).append(",\"zt\":").append(q(h.zTitle))
                .append(",\"v\":").append(arr(h.v));
            if (h.err != null) b.append(",\"err\":").append(arr(h.err));
            b.append(",\"min\":").append(n(h.min)).append(",\"max\":").append(n(h.max))
                .append(",\"stats\":").append(h.stats).append(",\"entries\":").append(n(h.entries))
                .append(",\"uf\":").append(n(h.underflow)).append(",\"of\":").append(n(h.overflow))
                .append(",\"mean\":").append(n(h.mean)).append(",\"std\":").append(n(h.std))
                .append(",\"meany\":").append(n(h.meanY)).append(",\"stdy\":").append(n(h.stdY))
                .append(",\"eo\":").append(q(h.errorOption));
            if (h.formula != null) b.append(",\"fn\":").append(q(h.formula));
            if (h.parameters != null) b.append(",\"par\":").append(arr(h.parameters));
            if (h.binEntries != null) b.append(",\"be\":").append(arr(h.binEntries));
            if (!h.fits.isEmpty()) {
                b.append(",\"fits\":[");
                for (int k = 0; k < h.fits.size(); k++) {
                    if (k > 0) b.append(',');
                    item(b, h.fits.get(k));
                }
                b.append(']');
            }
            if (!h.fitResults.isEmpty()) {
                b.append(",\"fr\":[");
                for (int k = 0; k < h.fitResults.size(); k++) {
                    final String[] r = h.fitResults.get(k);
                    b.append(k == 0 ? "" : ",").append('[');
                    for (int j = 0; j < r.length; j++) b.append(j == 0 ? "" : ",").append(q(r[j]));
                    b.append(']');
                }
                b.append(']');
            }
        } else if (i instanceof Graph g) {
            b.append(",\"x\":").append(arr(g.x)).append(",\"y\":").append(arr(g.y));
            if (g.exl != null) b.append(",\"exl\":").append(arr(g.exl));
            if (g.exh != null) b.append(",\"exh\":").append(arr(g.exh));
            if (g.eyl != null) b.append(",\"eyl\":").append(arr(g.eyl));
            if (g.eyh != null) b.append(",\"eyh\":").append(arr(g.eyh));
            b.append(",\"xt\":").append(q(g.xTitle)).append(",\"yt\":").append(q(g.yTitle))
                .append(",\"min\":").append(n(g.min)).append(",\"max\":").append(n(g.max));
            fits(b, g.fits, g.fitResults);
        } else if (i instanceof Graph2D g) {
            b.append(",\"x\":").append(arr(g.x)).append(",\"y\":").append(arr(g.y)).append(",\"z\":").append(arr(g.z))
                .append(",\"xt\":").append(q(g.xTitle)).append(",\"yt\":").append(q(g.yTitle))
                .append(",\"zt\":").append(q(g.zTitle)).append(",\"npx\":").append(g.npx).append(",\"npy\":").append(g.npy)
                .append(",\"min\":").append(n(g.min)).append(",\"max\":").append(n(g.max))
                .append(",\"margin\":").append(n(g.margin)).append(",\"zout\":").append(n(g.marginZ))
                .append(",\"maxiter\":").append(g.maxIter);
        } else if (i instanceof Group g) {
            b.append(",\"min\":").append(n(g.min)).append(",\"max\":").append(n(g.max)).append(",\"items\":[");
            for (int k = 0; k < g.items.size(); k++) {
                if (k > 0) b.append(',');
                item(b, g.items.get(k));
            }
            b.append(']');
        } else if (i instanceof Text t) {
            b.append(",\"x\":").append(n(t.x)).append(",\"y\":").append(n(t.y)).append(",\"ndc\":").append(t.ndc)
                .append(",\"s\":").append(q(t.text)).append(",\"sz\":").append(n(t.size)).append(",\"tc\":").append(c(t.color))
                .append(",\"al\":").append(t.align).append(",\"an\":").append(n(t.angle)).append(",\"tf\":").append(t.font);
        } else if (i instanceof Pave p) {
            b.append(",\"x1\":").append(n(p.x1)).append(",\"y1\":").append(n(p.y1)).append(",\"x2\":").append(n(p.x2))
                .append(",\"y2\":").append(n(p.y2)).append(",\"bs\":").append(p.border)
                .append(",\"cr\":").append(n(p.cornerRadius)).append(",\"shc\":").append(c(p.shadowColor))
                .append(",\"mg\":").append(n(p.margin)).append(",\"lb\":").append(q(p.label))
                .append(",\"nc\":").append(p.nColumns).append(",\"hd\":").append(q(p.header))
                .append(",\"ho\":").append(q(p.headerOption)).append(",\"ptf\":").append(p.textFont)
                .append(",\"pta\":").append(p.textAlign).append(",\"pts\":").append(n(p.textSize))
                .append(",\"pl\":").append(p.isLabel()).append(",\"lines\":[");
            for (int k = 0; k < p.lines.size(); k++) {
                final Entry e = p.lines.get(k);
                b.append(k == 0 ? "" : ",").append("{\"s\":").append(q(e.text)).append(",\"o\":").append(q(e.option))
                    .append(",\"tc\":").append(c(e.color)).append(",\"lc\":").append(c(e.line))
                    .append(",\"fc\":").append(c(e.fill)).append(",\"mc\":").append(c(e.marker))
                    .append(",\"fs\":").append(e.fillStyle).append(",\"mst\":").append(e.markerStyle)
                    .append(",\"sep\":").append(e.separator).append(",\"ef\":").append(e.font)
                    .append(",\"esz\":").append(n(e.size)).append(",\"eal\":").append(e.align).append('}');
            }
            b.append(']');
        } else if (i instanceof Segment s) {
            b.append(",\"x1\":").append(n(s.x1)).append(",\"y1\":").append(n(s.y1)).append(",\"x2\":").append(n(s.x2))
                .append(",\"y2\":").append(n(s.y2)).append(",\"ndc\":").append(s.ndc);
        } else if (i instanceof Shape s) {
            b.append(",\"x1\":").append(n(s.x1)).append(",\"y1\":").append(n(s.y1)).append(",\"x2\":").append(n(s.x2))
                .append(",\"y2\":").append(n(s.y2)).append(",\"r1\":").append(n(s.r1)).append(",\"r2\":").append(n(s.r2))
                .append(",\"phimin\":").append(n(s.phimin)).append(",\"phimax\":").append(n(s.phimax))
                .append(",\"theta\":").append(n(s.theta)).append(",\"ndc\":").append(s.ndc)
                .append(",\"as\":").append(n(s.arrowSize)).append(",\"ao\":").append(q(s.arrowOption))
                .append(",\"aa\":").append(n(s.arrowAngle)).append(",\"noedges\":").append(s.noEdges)
                .append(",\"bmode\":").append(s.borderMode).append(",\"bsize\":").append(s.borderSize);
            if (s.xs.length > 0) b.append(",\"x\":").append(arr(s.xs)).append(",\"y\":").append(arr(s.ys));
            else if ("marker".equals(s.kind)) b.append(",\"x\":").append(n(s.x1)).append(",\"y\":").append(n(s.y1));
        } else if (i instanceof Cloud3D c) {
            b.append(",\"p\":").append(arr(c.p));
        } else if (i instanceof Geometry g) {
            b.append(",\"nodes\":").append(g.nodes).append(",\"cut\":").append(g.cut).append(",\"meshes\":[");
            for (int k = 0; k < g.meshes.size(); k++) {
                final GeoMesh m = g.meshes.get(k);
                b.append(k == 0 ? "" : ",").append("{\"n\":").append(q(m.name)).append(",\"sh\":").append(q(m.shape))
                    .append(",\"c\":").append(c(m.color)).append(",\"a\":").append(m.transparency)
                    .append(",\"vis\":").append(m.visible)
                    .append(",\"p\":").append(arr(m.p)).append(",\"pol\":").append(arr(m.pol)).append(",\"seg\":")
                    .append(arr(m.seg)).append('}');
            }
            b.append(']');
        }
        b.append('}');
    }

    /** The functions fitted to an object and the rows of their results. */
    private static void fits(StringBuilder b, List<Hist> fits, List<String[]> results) {
        if (!fits.isEmpty()) {
            b.append(",\"fits\":[");
            for (int k = 0; k < fits.size(); k++) {
                if (k > 0) b.append(',');
                item(b, fits.get(k));
            }
            b.append(']');
        }
        if (!results.isEmpty()) {
            b.append(",\"fr\":[");
            for (int k = 0; k < results.size(); k++) {
                final String[] r = results.get(k);
                b.append(k == 0 ? "" : ",").append('[');
                for (int j = 0; j < r.length; j++) b.append(j == 0 ? "" : ",").append(q(r[j]));
                b.append(']');
            }
            b.append(']');
        }
    }

    private static void axis(StringBuilder b, Axis a) {
        b.append("{\"n\":").append(a.n).append(",\"lo\":").append(n(a.lo)).append(",\"hi\":").append(n(a.hi))
            .append(",\"t\":").append(q(a.title));
        if (a.edges != null) b.append(",\"e\":").append(arr(a.edges));
        if (a.first > 0) b.append(",\"first\":").append(a.first).append(",\"last\":").append(a.last);
        if (a.labels != null) {
            b.append(",\"labels\":[");
            for (int i = 0; i < a.labels.length; i++) b.append(i == 0 ? "" : ",").append(q(a.labels[i]));
            b.append(']');
        }
        b.append('}');
    }

    /* ------------------------------------------------------------------ */

    static String q(String s) {
        if (s == null) return "null";
        final StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    static String n(double v) {
        if (!Double.isFinite(v)) return "null";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.10g", v);
    }

    static String c(Color c) {
        if (c == null) return "null";
        return c.getAlpha() == 255 ? String.format("\"#%06x\"", c.getRGB() & 0xFFFFFF)
            : String.format("\"#%06x%02x\"", c.getRGB() & 0xFFFFFF, c.getAlpha());
    }

    static String arr(double[] a) {
        if (a == null) return "null";
        final StringBuilder b = new StringBuilder(a.length * 8 + 2).append('[');
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : ",").append(n(a[i]));
        return b.append(']').toString();
    }

    static String arr(float[] a) {
        final StringBuilder b = new StringBuilder(a.length * 8 + 2).append('[');
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : ",").append(n(a[i]));
        return b.append(']').toString();
    }

    static String arr(int[] a) {
        final StringBuilder b = new StringBuilder(a.length * 4 + 2).append('[');
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : ",").append(a[i]);
        return b.append(']').toString();
    }

    /** Unused: the list form, for callers holding lists. */
    static String arr(List<Double> a) {
        final double[] d = new double[a.size()];
        for (int i = 0; i < d.length; i++) d[i] = a.get(i);
        return arr(d);
    }
}
