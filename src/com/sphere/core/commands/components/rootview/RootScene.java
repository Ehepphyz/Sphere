package com.sphere.components.rootview;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A ROOT canvas as Sphere's engine describes it (sphere_view.hpp): its pads,
 * where each sits in its mother, and the objects each holds with the option
 * they were drawn with. Numbers only; how to draw them is RootPadPainter's.
 */
public final class RootScene {

    /**
     * What TAttAxis and TAxis keep besides the binning: divisions, label and
     * title fonts, sizes, offsets and colours, the tick side, and the flags
     * of the axis's context menu (more log labels, no exponent, time display...).
     * ROOT's defaults; a null colour is the canvas ink.
     */
    public static final class AxisStyle {
        public int ndivisions = 510;
        public boolean optimize = true;
        public java.awt.Color axisColor;
        public java.awt.Color labelColor;
        public int labelFont = 42;
        public double labelOffset = 0.005;
        public double labelSize = 0.035;
        public double tickLength = 0.03;
        public double titleOffset = 1;
        public double titleSize = 0.035;
        public java.awt.Color titleColor;
        public int titleFont = 42;
        public int maxDigits = 5;
        public boolean moreLogLabels;
        public boolean noExponent;
        public boolean decimals;
        public boolean timeDisplay;
        public String timeFormat = "";
        public boolean rotateTitle;
        public boolean centerTitle;
        public boolean centerLabels;
        /** "+" ticks inside the frame, "-" outside, "+-" both. */
        public String ticks = "+";
        /** LabelsOption: "h" horizontal, "v" vertical, "u" up, "d" down, "a" alphabetic, ">" "<" by content. */
        public String labelsOption = "h";
        public double lineWidth = 1;

        /** ROOT's first-order divisions: the units of ndivisions. */
        public int primary() {
            return Math.max(1, Math.abs(ndivisions) % 100);
        }

        /** The second-order divisions inside each primary one. */
        public int secondary() {
            return Math.abs(ndivisions) / 100 % 100;
        }

        public AxisStyle copy() {
            final AxisStyle c = new AxisStyle();
            c.ndivisions = ndivisions;
            c.optimize = optimize;
            c.axisColor = axisColor;
            c.labelColor = labelColor;
            c.labelFont = labelFont;
            c.labelOffset = labelOffset;
            c.labelSize = labelSize;
            c.tickLength = tickLength;
            c.titleOffset = titleOffset;
            c.titleSize = titleSize;
            c.titleColor = titleColor;
            c.titleFont = titleFont;
            c.maxDigits = maxDigits;
            c.moreLogLabels = moreLogLabels;
            c.noExponent = noExponent;
            c.decimals = decimals;
            c.timeDisplay = timeDisplay;
            c.timeFormat = timeFormat;
            c.rotateTitle = rotateTitle;
            c.centerTitle = centerTitle;
            c.centerLabels = centerLabels;
            c.ticks = ticks;
            c.labelsOption = labelsOption;
            c.lineWidth = lineWidth;
            return c;
        }
    }

    /** A TAxis: the binning, the title, and the range the user zoomed to in ROOT. */
    public static final class Axis {
        public int n;
        public double lo;
        public double hi;
        public double[] edges;
        public String title = "";
        public String[] labels;
        public int first;
        public int last;

        public double edge(int i) {
            if (edges != null && edges.length == n + 1) return edges[Math.max(0, Math.min(n, i))];
            return n <= 0 ? lo : lo + (hi - lo) * i / n;
        }

        public double center(int i) {
            return 0.5 * (edge(i) + edge(i + 1));
        }

        /** The bin holding x, 0-based, or -1 outside. */
        public int bin(double x) {
            if (n <= 0 || x < edge(0) || x >= edge(n)) return -1;
            if (edges == null || edges.length != n + 1) return Math.min(n - 1, (int) ((x - lo) / (hi - lo) * n));
            int a = 0;
            int b = n;
            while (b - a > 1) {
                final int m = (a + b) >>> 1;
                if (edges[m] <= x) a = m;
                else b = m;
            }
            return a;
        }

        /** The range ROOT shows: the user's zoom (SetRangeUser) when there is one. */
        public double[] shown() {
            if (first > 0 && last >= first && last <= n) return new double[]{edge(first - 1), edge(last)};
            return new double[]{lo, hi};
        }
    }

    /** What every drawn object has: its names, the option, its colours. */
    public abstract static class Item {
        public String kind = "";
        public String className = "";
        public String name = "";
        public String title = "";
        public String option = "";
        public Color line;
        public Color fill;
        public Color marker;
        public double lineWidth = 1;
        public int lineStyle = 1;
        public int fillStyle;
        public int markerStyle = 1;
        public double markerSize = 1;
        /** TH1::SetHighlight, TGraph::SetHighlight: the bin or point under the mouse is shown. */
        public boolean highlight;
        /** TGraph::SetEditable: its points follow the mouse. */
        public boolean editable;
        /** Removed from its pad by TObject::Delete, kept for an undo. */
        public boolean deleted;

        /** The option in capitals, "SAME" taken out: what the painter reads. */
        public String opt() {
            return option == null ? "" : option.toUpperCase(Locale.ROOT).replace("SAME", "").strip();
        }

        public boolean hasFill() {
            return fill != null && fillStyle > 0 && fillStyle != 4000;
        }
    }

    /** A histogram of 1, 2 or 3 dimensions; a function arrives as the histogram it paints. */
    public static final class Hist extends Item {
        public int dim;
        public Axis x;
        public Axis y;
        public Axis z;
        public String yTitle = "";
        public String zTitle = "";
        /** Contents, x fastest, then y, then z; bins 1..n of each axis. */
        public double[] v = new double[0];
        public double[] err;
        public double min = Double.NaN;
        public double max = Double.NaN;
        public boolean stats;
        public double entries;
        /** The contents of the underflow and overflow bins, which OptStat's u and o show. */
        public double underflow;
        public double overflow;
        public double mean;
        public double std;
        public double meanY;
        public double stdY;
        /** The formula, for a function. */
        public String formula;
        /** A function's parameters, in the order of its formula's [0], [1]...; null when not sent. */
        public double[] parameters;
        public final List<Hist> fits = new ArrayList<>();
        /** TProfile::SetErrorOption: "" error of the mean, "s" spread, "i" integer, "g" Gaussian. */
        public String errorOption = "";
        /** The fit results shown in the statistics box (TPaveStats::SetOptFit): name, value, error. */
        public final List<String[]> fitResults = new ArrayList<>();
        /** A profile's entries in each bin (TProfile::GetBinEntries), null when not sent. */
        public double[] binEntries;

        public boolean isFunction() {
            return formula != null;
        }

        public int nx() {
            return x == null ? 0 : x.n;
        }

        public int ny() {
            return y == null ? 1 : y.n;
        }

        public int nz() {
            return z == null ? 1 : z.n;
        }

        public double at(int ix, int iy) {
            final int k = iy * nx() + ix;
            return k >= 0 && k < v.length ? v[k] : 0;
        }

        public double at(int ix, int iy, int iz) {
            final int k = (iz * ny() + iy) * nx() + ix;
            return k >= 0 && k < v.length ? v[k] : 0;
        }

        public double error(int ix) {
            if (err != null && ix < err.length) return err[ix];
            return Math.sqrt(Math.abs(at(ix, 0)));
        }

        /** Whether the errors say more than the square root of the contents. */
        public boolean weighted() {
            if (err == null) return false;
            for (int i = 0; i < err.length && i < v.length; i++) {
                if (Math.abs(err[i] - Math.sqrt(Math.abs(v[i]))) > 1e-9 * (1 + Math.abs(err[i]))) return true;
            }
            return false;
        }
    }

    public static final class Graph extends Item {
        public double[] x = new double[0];
        public double[] y = new double[0];
        public double[] exl;
        public double[] exh;
        public double[] eyl;
        public double[] eyh;
        public String xTitle = "";
        public String yTitle = "";
        public double min = Double.NaN;
        public double max = Double.NaN;
        /** The functions fitted to the graph (TGraph::Fit), and their results. */
        public final List<Hist> fits = new ArrayList<>();
        public final List<String[]> fitResults = new ArrayList<>();
    }

    public static final class Graph2D extends Item {
        public double[] x = new double[0];
        public double[] y = new double[0];
        public double[] z = new double[0];
        /** TGraph2D::SetNpx, SetNpy: the grid it is interpolated on. */
        public int npx = 40;
        public int npy = 40;
        /** TGraph2D::SetMargin, SetMarginBinsContent, SetMaxIter. */
        public double margin = 0.1;
        public double marginZ;
        public int maxIter = 100000;
        public double min = Double.NaN;
        public double max = Double.NaN;
        public String xTitle = "";
        public String yTitle = "";
        public String zTitle = "";
    }

    /** A THStack or a TMultiGraph: members drawn together. */
    public static final class Group extends Item {
        public final List<Item> items = new ArrayList<>();
        /** THStack::SetMaximum, SetMinimum. */
        public double min = Double.NaN;
        public double max = Double.NaN;
    }

    public static final class Text extends Item {
        public double x;
        public double y;
        public boolean ndc;
        public String text = "";
        public double size = 0.05;
        public Color color = Color.BLACK;
        public int align = 11;
        public double angle;
        /** TAttText's font: 10 * family + precision. */
        public int font = 42;
    }

    /** One line of a TPaveText or a TLegend. */
    public static final class Entry {
        public String text = "";
        public String option = "";
        public Color color;
        public Color line;
        public Color fill;
        public Color marker;
        public int fillStyle;
        public int markerStyle = 1;
        /** TPaveText::InsertLine: a line drawn across the pave rather than a text. */
        public boolean separator;
        /** TPaveText::SetAllWith "font" and "size": 0 when the pave's own. */
        public int font;
        public double size;
        /** The line's own TAttText alignment, 0 when the pave's. */
        public int align;
    }

    public static final class Pave extends Item {
        public double x1;
        public double y1;
        public double x2;
        public double y2;
        public int border = 1;
        public final List<Entry> lines = new ArrayList<>();

        public boolean isLegend() {
            return "legend".equals(kind);
        }

        /** TPave::SetCornerRadius, a fraction of the pave's height; rounded corners when the option has "arc". */
        public double cornerRadius;
        /** TPave::SetShadowColor. */
        public java.awt.Color shadowColor;
        /** TPaveText::SetMargin, TLegend::SetMargin. */
        public double margin = 0.05;
        /** TPaveText::SetLabel: a small label on the top edge. */
        public String label = "";
        /** TLegend::SetNColumns. */
        public int nColumns = 1;
        /** TLegend::SetHeader and its option ("C" centred, "R" right). */
        public String header;
        public String headerOption = "";
        public int textFont = 42;
        public int textAlign = 22;
        public double textSize;

        public boolean isTitle() {
            return "title".equals(name);
        }

        /** Whether it is a TPaveLabel, whose text size is a fraction of its own height. */
        public boolean paveLabel;

        public boolean isLabel() {
            return paveLabel || "TPaveLabel".equals(className) || "TPaveClass".equals(className);
        }
    }

    public static final class Segment extends Item {
        public double x1;
        public double y1;
        public double x2;
        public double y2;
        public boolean ndc;
    }

    /** Anything the viewer does not draw, kept so it can be named. */
    public static final class Other extends Item {
    }

    /**
     * A shape of a slide (sphere_view3d.hpp): a TBox, a TEllipse or TArc, a
     * TPolyLine, a TMarker, a TArrow; in the pad's user coordinates unless ndc.
     */
    public static final class Shape extends Item {
        public double x1;
        public double y1;
        public double x2;
        public double y2;
        public double r1;
        public double r2;
        public double phimin;
        public double phimax = 360;
        public double theta;
        public double[] xs = new double[0];
        public double[] ys = new double[0];
        public boolean ndc;
        public double arrowSize = 0.05;
        public String arrowOption = "|>";
        /** TArrow::SetAngle, in degrees. */
        public double arrowAngle = 60;
        /** TEllipse::SetNoEdges: an arc without its two radii. */
        public boolean noEdges;
        /** TWbox::SetBorderMode, SetBorderSize. */
        public int borderMode;
        public int borderSize;
    }

    /** What ROOT draws through a TView: a TPolyMarker3D ("pm3") or a TPolyLine3D ("pl3"), x y z interleaved. */
    public static final class Cloud3D extends Item {
        public float[] p = new float[0];

        public int size() {
            return p.length / 3;
        }

        public boolean isLine() {
            return "pl3".equals(kind);
        }
    }

    /** One volume of a TGeo geometry, placed: its corners, its polygons as counted loops, its wires. */
    public static final class GeoMesh {
        public String name = "";
        public String shape = "";
        public Color color = Color.LIGHT_GRAY;
        public int transparency;
        public float[] p = new float[0];
        public int[] pol = new int[0];
        public int[] seg = new int[0];
        /** TGeoVolume::SetVisibility. */
        public boolean visible = true;
    }

    /** A TGeo geometry as meshes, which ROOT in batch cannot draw and Sphere's 3D space can. */
    public static final class Geometry extends Item {
        public final List<GeoMesh> meshes = new ArrayList<>();
        public int nodes;
        public boolean cut;
    }

    public static final class Pad {
        /** The style of the frame's X, Y and Z axes (TAttAxis and TAxis of the histogram that frames them). */
        public final AxisStyle[] axes = {new AxisStyle(), new AxisStyle(), new AxisStyle()};
        /** TPad::SetTickx, SetTicky: ticks on the opposite side too (2: with labels). */
        public int tickx;
        public int ticky;
        public int borderMode;
        public int borderSize = 2;
        /** TPad::SetCrosshair: lines that follow the mouse across the pad. */
        public boolean crosshair;
        /** TPad::SetEditable: false refuses every change. */
        public boolean editable = true;
        public boolean fixedAspect;
        /** TH2::SetShowProjectionX/Y/XY and TH3::SetShowProjection: bins summed, 0 when off. */
        public int showProjectionX;
        public int showProjectionY;
        public String showProjection3D;
        public String name = "";
        public String title = "";
        public double px;
        public double py;
        public double pw = 1;
        public double ph = 1;
        public boolean logx;
        public boolean logy;
        public boolean logz;
        public boolean gridx;
        public boolean gridy;
        public double theta = 30;
        public double phi = 30;
        public Color fill = Color.WHITE;
        /** TFrame::SetFillColor and SetFillStyle: the ground inside the axes; null when the pad's. */
        public Color frameFill;
        public int frameFillStyle = 1001;
        public double lm = 0.1;
        public double rm = 0.1;
        public double bm = 0.1;
        public double tm = 0.1;
        public final List<Item> items = new ArrayList<>();
        public final List<Pad> pads = new ArrayList<>();
        /** The pad's user range (TPad::Range), NaN when the scene did not say. */
        public double ux1 = Double.NaN;
        public double uy1 = Double.NaN;
        public double ux2 = Double.NaN;
        public double uy2 = Double.NaN;
        /** The box of the pad's TView, and its angles, when it has one. */
        public double[] viewMin;
        public double[] viewMax;
        public double viewLat = Double.NaN;
        public double viewLon = Double.NaN;

        public boolean hasUserRange() {
            return Double.isFinite(ux1) && Double.isFinite(ux2) && Double.isFinite(uy1) && Double.isFinite(uy2)
                && ux2 != ux1 && uy2 != uy1;
        }

        /** Whether the pad holds something only a 3D space shows well: a cloud or a geometry. */
        public boolean holdsSpace() {
            for (Item i : items) if (i instanceof Cloud3D || i instanceof Geometry) return true;
            return false;
        }

        /** Every pad, this one first, depth first: the order sphere_view3d.hpp counts them in. */
        public List<Pad> flatten() {
            final List<Pad> out = new ArrayList<>();
            flattenInto(out);
            return out;
        }

        private void flattenInto(List<Pad> out) {
            out.add(this);
            for (Pad p : pads) p.flattenInto(out);
        }

        /** The object that sets the axes: the first histogram, graph or group. */
        public Item main() {
            for (Item i : items) {
                if (i instanceof Hist || i instanceof Graph || i instanceof Graph2D || i instanceof Group) return i;
            }
            return null;
        }

        public boolean drawsSomething() {
            return main() != null;
        }
    }

    public String name = "";
    public String title = "";
    public int width = 700;
    public int height = 500;
    public int optStat = 1111;
    public int optTitle = 1;
    /** gStyle's OptFit, and the formats of TPaveStats::SetStatFormat and SetFitFormat. */
    public int optFit;
    public String statFormat = "6.4g";
    public String fitFormat = "5.4g";
    /** TPaveStats::SetOption: where the box sits, "br" by default (ROOT's top right in NDC terms). */
    public String statOption = "br";
    /** TCanvas::SetGrayscale. */
    public boolean grayscale;
    /** TCanvas::SetFixedAspectRatio. */
    public boolean fixedAspect;
    public Color[] palette = defaultPalette();
    public Pad pad = new Pad();
    /** Set when the engine refused. */
    public String error;

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Reads what the engine answered. What ROOT printed before the JSON (a
     * warning while opening a file) is stepped over: the JSON is one line.
     */
    public static RootScene parse(String answer) {
        final RootScene scene = new RootScene();
        final String json = jsonLine(answer);
        if (json == null) {
            scene.error = answer == null ? "no answer from the engine" : answer.strip();
            return scene;
        }
        final Object root = Json.parse(json);
        final String error = Json.text(root, "error", null);
        if (error != null) {
            scene.error = error;
            return scene;
        }
        scene.optStat = (int) Json.number(root, "optstat", 1111);
        scene.optTitle = (int) Json.number(root, "opttitle", 1);
        scene.optFit = (int) Json.number(root, "optfit", 0);
        scene.statFormat = Json.text(root, "statfmt", scene.statFormat);
        scene.fitFormat = Json.text(root, "fitfmt", scene.fitFormat);
        scene.statOption = Json.text(root, "statopt", scene.statOption);
        scene.grayscale = bool(root, "gray");
        scene.fixedAspect = bool(root, "fixed");
        final List<Object> palette = Json.list(root, "palette");
        if (!palette.isEmpty()) {
            final Color[] colors = new Color[palette.size()];
            for (int i = 0; i < colors.length; i++) colors[i] = color(palette.get(i), Color.GRAY);
            scene.palette = colors;
        }
        if ("canvas".equals(Json.text(root, "k", ""))) {
            scene.name = Json.text(root, "n", "");
            scene.title = Json.text(root, "t", "");
            scene.width = (int) Json.number(root, "w", 700);
            scene.height = (int) Json.number(root, "h", 500);
            scene.pad = pad(Json.get(root, "pad"));
            extras(scene.pad, Json.list(root, "x3d"));
        } else {
            final Item item = item(Json.get(root, "item"));
            scene.name = item == null ? "" : item.name;
            scene.title = item == null ? "" : item.title;
            scene.pad = new Pad();
            scene.pad.name = scene.name;
            scene.pad.lm = 0.12;
            scene.pad.rm = item instanceof Hist h && h.dim == 2 ? 0.14 : 0.06;
            if (item != null) scene.pad.items.add(item);
        }
        return scene;
    }

    /** Reads a scene file written by sphere_view3d.hpp: one JSON line, which may be large. */
    public static RootScene read(java.nio.file.Path file) throws java.io.IOException {
        final String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        return parse(text.replace('\r', ' ').replace('\n', ' ').strip());
    }

    /**
     * The extras of sphere_view3d.hpp, merged into the pads they belong to: the
     * pad's range and TView, and the clouds, geometries and shapes, which take
     * the place of the "other" items sphere_view.hpp could only name.
     */
    static void extras(Pad top, List<Object> entries) {
        if (entries.isEmpty()) return;
        final List<Pad> pads = top.flatten();
        for (Object e : entries) {
            final int idx = (int) num(e, "idx", -1);
            if (idx < 0 || idx >= pads.size()) continue;
            final Pad pad = pads.get(idx);
            pad.ux1 = num(e, "ux1", Double.NaN);
            pad.uy1 = num(e, "uy1", Double.NaN);
            pad.ux2 = num(e, "ux2", Double.NaN);
            pad.uy2 = num(e, "uy2", Double.NaN);
            final Object view = Json.get(e, "view");
            if (view != null) {
                pad.viewMin = numbers(Json.list(view, "min"));
                pad.viewMax = numbers(Json.list(view, "max"));
                pad.viewLat = num(view, "lat", Double.NaN);
                pad.viewLon = num(view, "lon", Double.NaN);
            }
            for (Object o : Json.list(e, "items")) {
                final Item extra = item(o);
                if (extra == null || extra instanceof Other) continue;
                // The frame is the pad's, drawn by the painter: as a filled box over the histogram
                // it hid every bin and curve (scenes written before the exporter skipped it). Its fill is kept.
                if ("TFrame".equals(extra.className)) {
                    if (pad.frameFill == null) {
                        pad.frameFill = extra.fill;
                        pad.frameFillStyle = extra.fillStyle;
                    }
                    continue;
                }
                final int at = standIn(pad.items, extra);
                if (at >= 0) pad.items.set(at, extra);
                else pad.items.add(extra);
            }
        }
    }

    /**
     * The item sphere_view.hpp wrote for the object an extra describes: one it
     * could only name, or the line it made of an arrow. The extra takes its
     * place, so that the pad's objects keep the order ROOT paints them in.
     */
    private static int standIn(List<Item> items, Item extra) {
        for (int k = 0; k < items.size(); k++) {
            final Item i = items.get(k);
            if (!i.className.equals(extra.className) || !i.name.equals(extra.name)) continue;
            if (i instanceof Other || i instanceof Segment && "arrow".equals(extra.kind)) return k;
        }
        return -1;
    }

    /** The last line that is a JSON object, or null. */
    public static String jsonLine(String answer) {
        if (answer == null) return null;
        final String[] lines = answer.split("\n");
        for (int k = lines.length - 1; k >= 0; k--) {
            final String l = lines[k].strip();
            if (l.startsWith("{")) return l;
        }
        return null;
    }

    static Pad pad(Object o) {
        final Pad p = new Pad();
        if (o == null) return p;
        p.name = Json.text(o, "n", "");
        p.title = Json.text(o, "t", "");
        p.px = num(o, "px", 0);
        p.py = num(o, "py", 0);
        p.pw = num(o, "pw", 1);
        p.ph = num(o, "ph", 1);
        p.logx = bool(o, "logx");
        p.logy = bool(o, "logy");
        p.logz = bool(o, "logz");
        p.gridx = bool(o, "gridx");
        p.gridy = bool(o, "gridy");
        p.theta = num(o, "theta", 30);
        p.phi = num(o, "phi", 30);
        p.fill = color(Json.get(o, "fc"), Color.WHITE);
        p.frameFill = color(Json.get(o, "ffc"), null);
        p.frameFillStyle = (int) num(o, "ffs", 1001);
        p.lm = num(o, "lm", 0.1);
        p.rm = num(o, "rm", 0.1);
        p.bm = num(o, "bm", 0.1);
        p.tm = num(o, "tm", 0.1);
        if (Json.get(o, "tickx") != null) {
            p.tickx = (int) num(o, "tickx", 0);
            p.ticky = (int) num(o, "ticky", 0);
            p.borderMode = (int) num(o, "bmode", 0);
            p.borderSize = (int) num(o, "bsize", 2);
            p.crosshair = bool(o, "cross");
            p.editable = Json.get(o, "editable") == null || bool(o, "editable");
            p.fixedAspect = bool(o, "fixed");
            p.showProjectionX = (int) num(o, "spx", 0);
            p.showProjectionY = (int) num(o, "spy", 0);
            p.ux1 = num(o, "ux1", Double.NaN);
            p.uy1 = num(o, "uy1", Double.NaN);
            p.ux2 = num(o, "ux2", Double.NaN);
            p.uy2 = num(o, "uy2", Double.NaN);
        }
        if (Json.get(o, "vmin") != null) {
            p.viewMin = numbers(Json.list(o, "vmin"));
            p.viewMax = numbers(Json.list(o, "vmax"));
            p.viewLat = num(o, "vlat", Double.NaN);
            p.viewLon = num(o, "vlon", Double.NaN);
        }
        final List<Object> ax = Json.list(o, "ax");
        for (int a = 0; a < Math.min(3, ax.size()); a++) style(ax.get(a), p.axes[a]);
        for (Object i : Json.list(o, "items")) {
            final Item item = item(i);
            if (item != null) p.items.add(item);
        }
        for (Object s : Json.list(o, "pads")) p.pads.add(pad(s));
        return p;
    }

    /** An axis's style, as sphere_view.hpp and RootSceneJson write it; what is absent keeps ROOT's default. */
    static void style(Object o, AxisStyle st) {
        if (o == null) return;
        st.ndivisions = (int) num(o, "nd", st.ndivisions);
        if (Json.get(o, "opt") != null) st.optimize = bool(o, "opt");
        st.axisColor = color(Json.get(o, "ac"), st.axisColor);
        st.labelColor = color(Json.get(o, "lc"), st.labelColor);
        st.labelFont = (int) num(o, "lf", st.labelFont);
        st.labelOffset = num(o, "lo", st.labelOffset);
        st.labelSize = num(o, "ls", st.labelSize);
        st.tickLength = num(o, "tl", st.tickLength);
        st.titleOffset = num(o, "to", st.titleOffset);
        st.titleSize = num(o, "ts", st.titleSize);
        st.titleColor = color(Json.get(o, "tc"), st.titleColor);
        st.titleFont = (int) num(o, "tf", st.titleFont);
        st.maxDigits = (int) num(o, "md", st.maxDigits);
        st.moreLogLabels = bool(o, "mll");
        st.noExponent = bool(o, "nexp");
        st.decimals = bool(o, "dec");
        st.timeDisplay = bool(o, "time");
        st.timeFormat = Json.text(o, "tfmt", st.timeFormat);
        st.rotateTitle = bool(o, "rot");
        st.centerTitle = bool(o, "ctr");
        st.centerLabels = bool(o, "clab");
        st.ticks = Json.text(o, "ticks", st.ticks);
        st.labelsOption = Json.text(o, "lopt", st.labelsOption);
        st.lineWidth = num(o, "lw", st.lineWidth);
    }

    static Item item(Object o) {
        if (o == null) return null;
        final String kind = Json.text(o, "k", "");
        final Item item = switch (kind) {
            case "h1", "h2", "h3" -> hist(o, kind);
            case "g" -> graph(o);
            case "g2" -> graph2d(o);
            case "stack", "mgraph" -> group(o);
            case "text" -> text(o);
            case "pave", "legend" -> pave(o);
            case "line" -> segment(o);
            case "box", "ellipse", "polyline", "marker", "arrow", "pm" -> shape(o);
            case "pm3", "pl3" -> cloud(o);
            case "geo" -> geometry(o);
            default -> new Other();
        };
        item.kind = kind;
        item.className = Json.text(o, "c", "");
        item.name = Json.text(o, "n", "");
        item.title = Json.text(o, "t", "");
        item.option = Json.text(o, "o", "");
        item.line = color(Json.get(o, "lc"), null);
        item.fill = color(Json.get(o, "fc"), null);
        item.marker = color(Json.get(o, "mc"), null);
        item.lineWidth = num(o, "lw", 1);
        item.lineStyle = (int) num(o, "ls", 1);
        item.fillStyle = (int) num(o, "fs", 0);
        item.markerStyle = (int) num(o, "mst", 1);
        item.markerSize = num(o, "msz", 1);
        item.highlight = bool(o, "hl");
        item.editable = bool(o, "ed");
        return item;
    }

    private static Hist hist(Object o, String kind) {
        final Hist h = new Hist();
        h.dim = kind.charAt(1) - '0';
        h.x = axis(Json.get(o, "x"));
        h.y = h.dim > 1 ? axis(Json.get(o, "y")) : null;
        h.z = h.dim > 2 ? axis(Json.get(o, "z")) : null;
        h.yTitle = Json.text(o, "yt", "");
        h.zTitle = Json.text(o, "zt", "");
        h.v = numbers(Json.list(o, "v"));
        final List<Object> err = Json.list(o, "err");
        h.err = err.isEmpty() ? null : numbers(err);
        h.min = num(o, "min", Double.NaN);
        h.max = num(o, "max", Double.NaN);
        h.stats = bool(o, "stats");
        h.entries = num(o, "entries", 0);
        h.mean = num(o, "mean", 0);
        h.std = num(o, "std", 0);
        h.meanY = num(o, "meany", 0);
        h.stdY = num(o, "stdy", 0);
        h.formula = Json.text(o, "fn", null);
        h.underflow = num(o, "uf", 0);
        h.overflow = num(o, "of", 0);
        h.errorOption = Json.text(o, "eo", "");
        final List<Object> be = Json.list(o, "be");
        if (!be.isEmpty()) h.binEntries = numbers(be);
        final List<Object> par = Json.list(o, "par");
        if (!par.isEmpty()) h.parameters = numbers(par);
        for (Object r : Json.list(o, "fr")) {
            if (r instanceof List<?> l) {
                final String[] row = new String[l.size()];
                for (int k = 0; k < row.length; k++) row[k] = String.valueOf(l.get(k));
                h.fitResults.add(row);
            }
        }
        for (Object f : Json.list(o, "fits")) {
            if (item(f) instanceof Hist fit) h.fits.add(fit);
        }
        return h;
    }

    private static Graph graph(Object o) {
        final Graph g = new Graph();
        g.x = numbers(Json.list(o, "x"));
        g.y = numbers(Json.list(o, "y"));
        g.exl = optional(o, "exl");
        g.exh = optional(o, "exh");
        g.eyl = optional(o, "eyl");
        g.eyh = optional(o, "eyh");
        g.xTitle = Json.text(o, "xt", "");
        g.yTitle = Json.text(o, "yt", "");
        g.min = num(o, "min", Double.NaN);
        g.max = num(o, "max", Double.NaN);
        for (Object r : Json.list(o, "fr")) {
            if (r instanceof List<?> l) {
                final String[] row = new String[l.size()];
                for (int k = 0; k < row.length; k++) row[k] = String.valueOf(l.get(k));
                g.fitResults.add(row);
            }
        }
        for (Object f : Json.list(o, "fits")) {
            if (item(f) instanceof Hist fit) g.fits.add(fit);
        }
        return g;
    }

    private static Graph2D graph2d(Object o) {
        final Graph2D g = new Graph2D();
        g.x = numbers(Json.list(o, "x"));
        g.y = numbers(Json.list(o, "y"));
        g.z = numbers(Json.list(o, "z"));
        g.xTitle = Json.text(o, "xt", "");
        g.yTitle = Json.text(o, "yt", "");
        g.zTitle = Json.text(o, "zt", "");
        g.npx = (int) num(o, "npx", 40);
        g.npy = (int) num(o, "npy", 40);
        g.margin = num(o, "margin", 0.1);
        g.marginZ = num(o, "zout", 0);
        g.maxIter = (int) num(o, "maxiter", 100000);
        g.min = num(o, "min", Double.NaN);
        g.max = num(o, "max", Double.NaN);
        return g;
    }

    private static Group group(Object o) {
        final Group g = new Group();
        g.min = num(o, "min", Double.NaN);
        g.max = num(o, "max", Double.NaN);
        for (Object i : Json.list(o, "items")) {
            final Item member = item(i);
            if (member != null) g.items.add(member);
        }
        return g;
    }

    private static Text text(Object o) {
        final Text t = new Text();
        t.x = num(o, "x", 0);
        t.y = num(o, "y", 0);
        t.ndc = bool(o, "ndc");
        t.text = Json.text(o, "s", "");
        t.size = num(o, "sz", 0.05);
        t.color = color(Json.get(o, "tc"), Color.BLACK);
        t.align = (int) num(o, "al", 11);
        t.angle = num(o, "an", 0);
        t.font = (int) num(o, "tf", 42);
        return t;
    }

    private static Pave pave(Object o) {
        final Pave p = new Pave();
        p.x1 = num(o, "x1", 0);
        p.y1 = num(o, "y1", 0);
        p.x2 = num(o, "x2", 1);
        p.y2 = num(o, "y2", 1);
        p.border = (int) num(o, "bs", 1);
        p.cornerRadius = num(o, "cr", 0);
        p.shadowColor = color(Json.get(o, "shc"), null);
        p.margin = num(o, "mg", 0.05);
        p.label = Json.text(o, "lb", "");
        p.nColumns = (int) num(o, "nc", 1);
        p.header = Json.text(o, "hd", null);
        p.headerOption = Json.text(o, "ho", "");
        p.textFont = (int) num(o, "ptf", 42);
        p.textAlign = (int) num(o, "pta", "legend".equals(Json.text(o, "k", "")) ? 12 : 22);
        p.textSize = num(o, "pts", 0);
        p.paveLabel = bool(o, "pl");
        for (Object l : Json.list(o, "lines")) {
            final Entry e = new Entry();
            e.text = Json.text(l, "s", "");
            e.option = Json.text(l, "o", "");
            e.color = color(Json.get(l, "tc"), Color.BLACK);
            e.line = color(Json.get(l, "lc"), null);
            e.fill = color(Json.get(l, "fc"), null);
            e.marker = color(Json.get(l, "mc"), null);
            e.fillStyle = (int) num(l, "fs", 0);
            e.markerStyle = (int) num(l, "mst", 1);
            e.separator = bool(l, "sep");
            e.font = (int) num(l, "ef", 0);
            e.size = num(l, "esz", 0);
            e.align = (int) num(l, "eal", 0);
            p.lines.add(e);
        }
        return p;
    }

    private static Segment segment(Object o) {
        final Segment s = new Segment();
        s.x1 = num(o, "x1", 0);
        s.y1 = num(o, "y1", 0);
        s.x2 = num(o, "x2", 0);
        s.y2 = num(o, "y2", 0);
        s.ndc = bool(o, "ndc");
        return s;
    }

    private static Shape shape(Object o) {
        final Shape s = new Shape();
        s.x1 = num(o, "x1", Double.NaN);
        s.y1 = num(o, "y1", Double.NaN);
        s.x2 = num(o, "x2", Double.NaN);
        s.y2 = num(o, "y2", Double.NaN);
        if (Double.isNaN(s.x1)) s.x1 = num(o, "x", 0);
        if (Double.isNaN(s.y1)) s.y1 = num(o, "y", 0);
        s.r1 = num(o, "r1", 0);
        s.r2 = num(o, "r2", 0);
        s.phimin = num(o, "phimin", 0);
        s.phimax = num(o, "phimax", 360);
        s.theta = num(o, "theta", 0);
        s.ndc = bool(o, "ndc");
        s.arrowSize = num(o, "as", 0.05);
        s.arrowOption = Json.text(o, "ao", "|>");
        s.arrowAngle = num(o, "aa", 60);
        s.noEdges = bool(o, "noedges");
        s.borderMode = (int) num(o, "bmode", 0);
        s.borderSize = (int) num(o, "bsize", 0);
        if (Json.get(o, "x") instanceof List<?>) {
            s.xs = numbers(Json.list(o, "x"));
            s.ys = numbers(Json.list(o, "y"));
        }
        return s;
    }

    private static Cloud3D cloud(Object o) {
        final Cloud3D c = new Cloud3D();
        c.p = floats(Json.list(o, "p"));
        return c;
    }

    private static Geometry geometry(Object o) {
        final Geometry g = new Geometry();
        g.nodes = (int) num(o, "nodes", 0);
        g.cut = bool(o, "cut");
        for (Object m : Json.list(o, "meshes")) {
            final GeoMesh mesh = new GeoMesh();
            mesh.name = Json.text(m, "n", "");
            mesh.shape = Json.text(m, "sh", "");
            mesh.color = color(Json.get(m, "c"), Color.LIGHT_GRAY);
            mesh.transparency = (int) num(m, "a", 0);
            mesh.p = floats(Json.list(m, "p"));
            mesh.pol = ints(Json.list(m, "pol"));
            mesh.seg = ints(Json.list(m, "seg"));
            mesh.visible = Json.get(m, "vis") == null || bool(m, "vis");
            g.meshes.add(mesh);
        }
        return g;
    }

    static float[] floats(List<Object> list) {
        final float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
        return out;
    }

    static int[] ints(List<Object> list) {
        final int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i) instanceof Number n ? n.intValue() : 0;
        return out;
    }

    private static Axis axis(Object o) {
        final Axis a = new Axis();
        if (o == null) return a;
        a.n = (int) num(o, "n", 0);
        a.lo = num(o, "lo", 0);
        a.hi = num(o, "hi", 1);
        a.title = Json.text(o, "t", "");
        final List<Object> edges = Json.list(o, "e");
        a.edges = edges.isEmpty() ? null : numbers(edges);
        final List<Object> labels = Json.list(o, "labels");
        if (!labels.isEmpty()) {
            a.labels = new String[labels.size()];
            for (int i = 0; i < a.labels.length; i++) a.labels[i] = String.valueOf(labels.get(i));
        }
        a.first = (int) num(o, "first", 0);
        a.last = (int) num(o, "last", 0);
        return a;
    }

    private static double[] optional(Object o, String key) {
        final List<Object> list = Json.list(o, key);
        return list.isEmpty() ? null : numbers(list);
    }

    static double[] numbers(List<Object> list) {
        final double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i) instanceof Number n ? n.doubleValue() : Double.NaN;
        }
        return out;
    }

    static double num(Object o, String key, double fallback) {
        final Object value = Json.get(o, key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    static boolean bool(Object o, String key) {
        final Object value = Json.get(o, key);
        return value instanceof Boolean b ? b : value instanceof Number n && n.doubleValue() != 0;
    }

    /** "#rrggbb" or "#rrggbbaa", as TColor::AsHexString writes it. */
    static Color color(Object value, Color fallback) {
        if (!(value instanceof String s) || !s.startsWith("#") || (s.length() != 7 && s.length() != 9)) return fallback;
        try {
            final int rgb = Integer.parseInt(s.substring(1, 7), 16);
            final int alpha = s.length() == 9 ? Integer.parseInt(s.substring(7, 9), 16) : 255;
            return new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** ROOT's kBird, its default palette since 6.04, for a scene that carried none. */
    static Color[] defaultPalette() {
        final double[] stops = {0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0};
        final double[] r = {0.2082, 0.0592, 0.0780, 0.0232, 0.1802, 0.5301, 0.8186, 0.9956, 0.9764};
        final double[] g = {0.1664, 0.3599, 0.5041, 0.6419, 0.7178, 0.7492, 0.7328, 0.7862, 0.9832};
        final double[] b = {0.5293, 0.8684, 0.8385, 0.7914, 0.6425, 0.4662, 0.3499, 0.1968, 0.0539};
        final Color[] out = new Color[255];
        for (int i = 0; i < out.length; i++) {
            final double t = i / 254.0;
            int k = 0;
            while (k < stops.length - 2 && t > stops[k + 1]) k++;
            final double f = (t - stops[k]) / (stops[k + 1] - stops[k]);
            out[i] = new Color((float) (r[k] + f * (r[k + 1] - r[k])), (float) (g[k] + f * (g[k + 1] - g[k])),
                (float) (b[k] + f * (b[k + 1] - b[k])));
        }
        return out;
    }
}
