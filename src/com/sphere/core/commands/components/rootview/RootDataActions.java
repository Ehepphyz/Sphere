package com.sphere.components.rootview;

import com.sphere.components.rootview.RootActions.Def;
import com.sphere.components.rootview.RootPadPainter.AxisRef;
import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootScene.Axis;
import com.sphere.components.rootview.RootScene.GeoMesh;
import com.sphere.components.rootview.RootScene.Geometry;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

import static com.sphere.components.rootview.RootActions.b;
import static com.sphere.components.rootview.RootActions.d;
import static com.sphere.components.rootview.RootActions.def;
import static com.sphere.components.rootview.RootActions.find;
import static com.sphere.components.rootview.RootActions.i;
import static com.sphere.components.rootview.RootActions.look;
import static com.sphere.components.rootview.RootActions.s;

/**
 * The functions of the data classes' menus, done by Sphere: TH1, TH2, TH3,
 * TProfile, TProfile2D, TF1, TF2, TGraph and its errors, TGraph2D, THStack,
 * TPolyMarker, TPolyLine3D, TPolyMarker3D, and TGeoVolume and TGeoManager
 * on the meshes a scene holds. ROOT's algorithms where it matters to the
 * numbers (RootSpectrum for Smooth, ShowBackground and ShowPeaks, RootFitter
 * for Fit); what returns a new object (ProjectionX, ProfileX, Rebin into a
 * new name, DrawDerivative) is drawn in a canvas of its own.
 */
final class RootDataActions {

    /** gStyle's FuncColor and FuncWidth: a fitted function is red, two pixels wide. */
    static final Color FUNCTION_COLOR = RootColors.color(2);

    private RootDataActions() {
    }

    static void register() {
        registerHist1();
        registerHist2();
        registerHist3();
        registerProfiles();
        registerFunctions();
        registerGraphs();
        registerGraph2D();
        registerGroups();
        registerPoints();
        registerGeometry();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    static Hist hist(RootTarget t) {
        return RootActions.hist(t);
    }

    static Hist hist(RootTarget t, int dim) {
        final Hist h = hist(t);
        if (h.dim != dim) throw new IllegalStateException(t.className + " " + h.name + " has " + h.dim + " dimensions");
        return h;
    }

    static Graph graph(RootTarget t) {
        if (t.item() instanceof Graph g) return g;
        throw new IllegalStateException("not a graph");
    }

    static Graph2D graph2D(RootTarget t) {
        if (t.item() instanceof Graph2D g) return g;
        throw new IllegalStateException("not a TGraph2D");
    }

    static Axis copy(Axis a) {
        if (a == null) return null;
        final Axis c = new Axis();
        c.n = a.n;
        c.lo = a.lo;
        c.hi = a.hi;
        c.edges = a.edges == null ? null : a.edges.clone();
        c.title = a.title;
        c.labels = a.labels == null ? null : a.labels.clone();
        c.first = a.first;
        c.last = a.last;
        return c;
    }

    static Axis axis(int n, double lo, double hi, String title) {
        final Axis a = new Axis();
        a.n = n;
        a.lo = lo;
        a.hi = hi;
        a.title = title == null ? "" : title;
        return a;
    }

    /** A new histogram of one dimension, in the canvas's own colours. */
    static Hist newHist(String className, String name, String title, Axis x, double[] v, double[] err) {
        final Hist h = new Hist();
        h.kind = "h1";
        h.className = className;
        h.name = name;
        h.title = title == null ? "" : title;
        h.dim = 1;
        h.x = x;
        h.v = v;
        h.err = err;
        h.stats = true;
        h.lineWidth = 1;
        restat(h);
        h.entries = 0;
        for (double c : v) h.entries += c;
        return h;
    }

    static Hist newHist2(String className, String name, String title, Axis x, Axis y, double[] v, double[] err) {
        final Hist h = newHist(className, name, title, x, v, err);
        h.kind = "h2";
        h.dim = 2;
        h.y = y;
        restat(h);
        return h;
    }

    /** The error of a cell, the square root of its content when the histogram keeps none. */
    static double err(Hist h, int k) {
        if (h.err != null && k < h.err.length) return h.err[k];
        return k < h.v.length ? Math.sqrt(Math.abs(h.v[k])) : 0;
    }

    static double[] errors(Hist h) {
        final double[] e = new double[h.v.length];
        for (int k = 0; k < e.length; k++) e[k] = err(h, k);
        return e;
    }

    /** Mean and standard deviation of the contents, as ROOT's statistics box shows them after a change. */
    static void restat(Hist h) {
        if (h.x == null) return;
        double sw = 0;
        double sx = 0;
        double sxx = 0;
        double sy = 0;
        double syy = 0;
        for (int iy = 0; iy < h.ny(); iy++) {
            for (int ix = 0; ix < h.nx(); ix++) {
                double w = 0;
                for (int iz = 0; iz < h.nz(); iz++) w += h.at(ix, iy, iz);
                if (h.dim == 1) w = h.at(ix, 0);
                final double x = h.x.center(ix);
                final double y = h.y == null ? 0 : h.y.center(iy);
                sw += w;
                sx += w * x;
                sxx += w * x * x;
                sy += w * y;
                syy += w * y * y;
            }
            if (h.dim == 1) break;
        }
        if (sw == 0) return;
        h.mean = sx / sw;
        h.std = Math.sqrt(Math.max(0, sxx / sw - h.mean * h.mean));
        if (h.dim >= 2) {
            h.meanY = sy / sw;
            h.stdY = Math.sqrt(Math.max(0, syy / sw - h.meanY * h.meanY));
        }
    }

    /** The bins of x shown in the pad, 0-based and inclusive. */
    static int[] shownBins(RootTarget t, Hist h) {
        final View v = RootActions.view(t);
        final double[] r = t.item() == t.pad.main() && v.xr != null ? v.xr : h.x.shown();
        int a = h.x.n;
        int b = -1;
        for (int i = 0; i < h.x.n; i++) {
            final double c = h.x.center(i);
            if (c >= r[0] && c <= r[1]) {
                a = Math.min(a, i);
                b = Math.max(b, i);
            }
        }
        return b < a ? new int[]{0, h.x.n - 1} : new int[]{a, b};
    }

    /** Draws what a function returned in a canvas of its own. */
    static String show(RootTarget t, Item item, String option) {
        final RootScene s = new RootScene();
        s.name = item.name;
        s.title = item.title;
        s.palette = t.scene.palette;
        s.optStat = t.scene.optStat;
        s.optFit = t.scene.optFit;
        s.statFormat = t.scene.statFormat;
        s.fitFormat = t.scene.fitFormat;
        s.pad.name = "c_" + item.name;
        s.pad.lm = 0.12;
        s.pad.bm = 0.12;
        s.pad.rm = item instanceof Hist h && h.dim == 2 || item instanceof Graph2D ? 0.15 : 0.05;
        item.option = option == null ? "" : option;
        s.pad.items.add(item);
        t.canvas.host().open(s, item.name);
        return item.className + " " + item.name + " drawn in a new canvas";
    }

    /** "_px" appended to the histogram's name, as ROOT names a projection; a full name is kept. */
    static String derivedName(Hist h, String name, String suffix) {
        if (name == null || name.isBlank()) name = suffix;
        return name.startsWith("_") ? h.name + name : name;
    }

    /** A function's value at (x, y), from its formula and parameters; null when they were not sent. */
    static DoubleBinaryOperator evaluator(Hist f) {
        if (f.formula == null || f.formula.isBlank()) return null;
        try {
            final RootFormula rf = RootFormula.parse(f.formula);
            final double[] p = f.parameters != null ? f.parameters : rf.parameters() == 0 ? new double[0] : null;
            if (p == null || p.length < rf.parameters()) return null;
            return (x, y) -> rf.eval(x, y, p);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The function sampled again on npx points over [lo, hi], as TF1 paints itself. */
    static void resample(Hist f, double lo, double hi, int npx, int npy, double ylo, double yhi) {
        final DoubleBinaryOperator e = evaluator(f);
        if (e == null) {
            if (f.dim == 1 && lo >= f.x.lo && hi <= f.x.hi && npx > 1) {
                final double[] v = new double[npx];
                final Axis a = axis(npx, lo, hi, f.x.title);
                for (int i = 0; i < npx; i++) v[i] = interpolate(f, a.center(i));
                f.x = a;
                f.v = v;
                f.err = null;
                return;
            }
            throw new IllegalStateException("the parameters of " + f.name + " were not exported: run the function with the ROOT engine");
        }
        if (f.dim == 2) {
            final Axis ax = axis(npx, lo, hi, f.x.title);
            final Axis ay = axis(npy, ylo, yhi, f.y.title);
            final double[] v = new double[npx * npy];
            for (int iy = 0; iy < npy; iy++) for (int ix = 0; ix < npx; ix++) v[iy * npx + ix] = e.applyAsDouble(ax.center(ix), ay.center(iy));
            f.x = ax;
            f.y = ay;
            f.v = v;
        } else {
            final Axis a = axis(npx, lo, hi, f.x.title);
            final double[] v = new double[npx];
            for (int i = 0; i < npx; i++) v[i] = e.applyAsDouble(a.center(i), 0);
            f.x = a;
            f.v = v;
        }
        f.err = null;
    }

    /** A sampled curve's value between its samples. */
    static double interpolate(Hist f, double x) {
        final int n = f.nx();
        if (n == 0) return 0;
        if (x <= f.x.center(0)) return f.at(0, 0);
        if (x >= f.x.center(n - 1)) return f.at(n - 1, 0);
        for (int i = 0; i + 1 < n; i++) {
            final double a = f.x.center(i);
            final double b = f.x.center(i + 1);
            if (x >= a && x <= b) return f.at(i, 0) + (f.at(i + 1, 0) - f.at(i, 0)) * (x - a) / (b - a);
        }
        return 0;
    }

    /** The point of a graph nearest the mouse, in pixels; -1 when none. */
    static int nearest(RootTarget t, double[] xs, double[] ys) {
        final View v = RootActions.view(t);
        if (t.at == null) return -1;
        int best = -1;
        double dist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(xs.length, ys.length); i++) {
            final double d = Math.hypot(RootPadPainter.px(v, xs[i]) - t.at.x, RootPadPainter.py(v, ys[i]) - t.at.y);
            if (d < dist) {
                dist = d;
                best = i;
            }
        }
        return best;
    }

    static double[] insert(double[] a, int at, double value) {
        if (a == null) return null;
        final double[] out = new double[a.length + 1];
        System.arraycopy(a, 0, out, 0, at);
        out[at] = value;
        System.arraycopy(a, at, out, at + 1, a.length - at);
        return out;
    }

    static double[] remove(double[] a, int at) {
        if (a == null || at < 0 || at >= a.length) return a;
        final double[] out = new double[a.length - 1];
        System.arraycopy(a, 0, out, 0, at);
        System.arraycopy(a, at + 1, out, at, a.length - at - 1);
        return out;
    }

    /** -1111, ROOT's "not set", as NaN. */
    static double limit(double v) {
        return v == -1111 ? Double.NaN : v;
    }

    /* ------------------------------------------------------------------ */
    /* TAxis helpers for RootActions                                       */
    /* ------------------------------------------------------------------ */

    static Axis binnedAxis(RootTarget t) {
        final int a = RootActions.axisOf(t);
        if (t.item() instanceof Hist h) {
            if (a == 0) return h.x;
            if (a == 1 && h.dim >= 2) return h.y;
            if (a == 2 && h.dim == 3) return h.z;
        }
        return null;
    }

    /** TAxis::LabelsOption's sorting: "a" alphabetic, ">" by decreasing content, "<" increasing. */
    static String sortLabels(RootTarget t, String option) {
        final String o = option == null ? "" : option;
        if (!(o.contains("a") || o.contains(">") || o.contains("<"))) return null;
        if (!(t.item() instanceof Hist h) || h.dim != 1 || h.x.labels == null) return "nothing to sort: the axis has no labels";
        final int n = h.x.n;
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final String[] labels = h.x.labels;
        if (o.contains("a")) {
            Arrays.sort(order, Comparator.comparing(i -> i < labels.length && labels[i] != null ? labels[i] : ""));
        } else if (o.contains(">")) {
            Arrays.sort(order, (p, q) -> Double.compare(h.at(q, 0), h.at(p, 0)));
        } else {
            Arrays.sort(order, (p, q) -> Double.compare(h.at(p, 0), h.at(q, 0)));
        }
        final double[] v = new double[n];
        final double[] e = new double[n];
        final String[] l = new String[n];
        for (int k = 0; k < n; k++) {
            v[k] = h.at(order[k], 0);
            e[k] = err(h, order[k]);
            l[k] = order[k] < labels.length ? labels[order[k]] : "";
        }
        h.v = v;
        h.err = e;
        h.x.labels = l;
        return "bins sorted";
    }

    static double[] limits(RootTarget t) {
        final Axis a = binnedAxis(t);
        if (a != null) return new double[]{a.lo, a.hi};
        return RootActions.range(t, RootActions.axisOf(t));
    }

    /** TAxis::SetLimits: the axis runs from xmin to xmax, its bins stretched to fit. */
    static String setLimits(RootTarget t, double lo, double hi) {
        if (!(hi > lo)) throw new IllegalArgumentException("xmax must be above xmin");
        final Axis a = binnedAxis(t);
        if (a != null) {
            if (a.edges != null) {
                final double[] e = new double[a.edges.length];
                for (int i = 0; i < e.length; i++) e[i] = lo + (a.edges[i] - a.lo) * (hi - lo) / (a.hi - a.lo);
                a.edges = e;
            }
            a.lo = lo;
            a.hi = hi;
            final View v = RootActions.view(t);
            if (RootActions.axisOf(t) == 0) v.xr = null;
            else if (RootActions.axisOf(t) == 1) v.yr = null;
            restat(hist(t));
        } else {
            RootActions.setRangeUser(t, lo, hi);
        }
        return String.format(Locale.ROOT, "limits %.6g .. %.6g", lo, hi);
    }

    static Object[] binRange(RootTarget t) {
        final Axis a = binnedAxis(t);
        if (a == null) return new Object[]{0L, 0L};
        return new Object[]{(long) (a.first > 0 ? a.first : 1), (long) (a.last > 0 ? a.last : a.n)};
    }

    /** TAxis::SetRange: the bins first to last shown, 1-based; 0 0, or last below first, all of them. */
    static String setBinRange(RootTarget t, int first, int last) {
        final Axis a = binnedAxis(t);
        if (a == null) throw new IllegalStateException("this axis has no bins: use SetRangeUser");
        final View v = RootActions.view(t);
        if (first == 0 && last == 0 || last < first) {
            a.first = 0;
            a.last = 0;
        } else {
            a.first = Math.max(1, first);
            a.last = Math.min(a.n, last);
        }
        if (RootActions.axisOf(t) == 0) v.xr = null;
        else if (RootActions.axisOf(t) == 1) v.yr = null;
        return a.first == 0 ? "all bins shown" : "bins " + a.first + " to " + a.last;
    }

    /* ------------------------------------------------------------------ */
    /* TH1                                                                 */
    /* ------------------------------------------------------------------ */

    private static void registerHist1() {
        look("TH1::DrawPanel", (t, a) -> {
            RootPanels.drawPanel(t);
            return null;
        });
        look("TH1::FitPanel", (t, a) -> {
            if (!t.canvas.host().fitPanel(t)) RootFitPanel.open(t);
            return null;
        });
        def("TH1::Fit", (t, a) -> fit(t, s(a, 0, "gaus"), s(a, 1, ""), s(a, 2, ""), d(a, 3, 0), d(a, 4, 0), null).message());
        fitSuggestions(find("TH1", "Fit"));
        def("TH1::Normalize", (t, a) -> {
            final Hist h = hist(t);
            final String o = s(a, 0, "").toLowerCase(Locale.ROOT);
            double sum = 0;
            double max = 0;
            final int[] r = h.dim == 1 ? shownBins(t, h) : new int[]{0, h.nx() - 1};
            for (int k = 0; k < h.v.length; k++) {
                final int ix = k % Math.max(1, h.nx());
                if (ix < r[0] || ix > r[1]) continue;
                sum += h.v[k] * (o.contains("width") ? cellSize(h, k) : 1);
                max = Math.max(max, h.v[k]);
            }
            final double c = o.contains("max") ? max : sum;
            if (c == 0) throw new IllegalStateException("the integral is 0: nothing to normalise");
            scale(h, 1 / c, o.contains("width"));
            return o.contains("max") ? "highest bin at 1" : "integral 1";
        });
        find("TH1", "Normalize").suggestions.put("option", new String[]{"", "width", "max"});
        def("TH1::Rebin", (t, a) -> {
            final Hist h = hist(t);
            final String newName = s(a, 1, "");
            final double[] xbins = a != null && a.length > 2 && a[2] instanceof double[] e && e.length >= 2 ? e : null;
            if (h.dim == 2) return rebin2D(t, h, i(a, 0, 2), i(a, 0, 2), newName);
            if (h.dim != 1) throw new IllegalStateException("Rebin of a TH3 is done by the ROOT engine");
            final Hist target = newName.isBlank() ? h : (Hist) RootSceneJson.copy(h);
            rebinX(target, Math.max(1, i(a, 0, 2)), xbins);
            if (target == h) return h.name + " now has " + h.x.n + " bins";
            target.name = newName;
            return show(t, target, "");
        });
        def("TH1::Scale", (t, a) -> {
            final Hist h = hist(t);
            final double c = d(a, 0, 1);
            scale(h, c, s(a, 1, "").toLowerCase(Locale.ROOT).contains("width"));
            return "scaled by " + c;
        });
        find("TH1", "Scale").suggestions.put("option", new String[]{"", "width", "nosw2"});
        def("TH1::SetHighlight", (t, a) -> {
            hist(t).highlight = b(a, 0, true);
            return hist(t).highlight ? "the bin under the mouse is highlighted" : "highlight off";
        }).state = t -> t.item() != null && t.item().highlight;
        def("TH1::SetMaximum", (t, a) -> {
            hist(t).max = limit(d(a, 0, -1111));
            if (t.item() == t.pad.main()) RootActions.view(t).yr = null;
            return Double.isNaN(hist(t).max) ? "maximum automatic" : "maximum " + hist(t).max;
        }).current = t -> new Object[]{Double.isNaN(hist(t).max) ? -1111.0 : hist(t).max};
        def("TH1::SetMinimum", (t, a) -> {
            hist(t).min = limit(d(a, 0, -1111));
            if (t.item() == t.pad.main()) RootActions.view(t).yr = null;
            return Double.isNaN(hist(t).min) ? "minimum automatic" : "minimum " + hist(t).min;
        }).current = t -> new Object[]{Double.isNaN(hist(t).min) ? -1111.0 : hist(t).min};
        def("TH1::SetStats", (t, a) -> {
            hist(t).stats = b(a, 0, true);
            RootActions.view(t).statsOff = !hist(t).stats;
            return hist(t).stats ? "statistics box shown" : "statistics box hidden";
        }).current = t -> new Object[]{hist(t).stats};
        look("TH1::SaveAs", (t, a) -> RootSaver.save(t, s(a, 0, ""), s(a, 1, "")));
        def("TH1::ShowBackground", (t, a) -> {
            final Hist h = hist(t, 1);
            final int[] r = shownBins(t, h);
            final double[] src = Arrays.copyOfRange(h.v, r[0], r[1] + 1);
            final String o = s(a, 1, "same");
            final double[] bg = RootSpectrum.background(src, Math.max(1, i(a, 0, 20)), o);
            final double[] v = new double[h.nx()];
            System.arraycopy(bg, 0, v, r[0], bg.length);
            final Hist b = newHist(h.className, h.name + "_background", h.title + "_background", copy(h.x), v, null);
            b.line = RootColors.color(2);
            b.stats = false;
            if (o.toLowerCase(Locale.ROOT).contains("same")) {
                b.option = "SAME";
                t.pad.items.removeIf(i -> i instanceof Hist x && x.name.equals(b.name));
                t.pad.items.add(b);
                return "background estimated by SNIP, " + i(a, 0, 20) + " iterations";
            }
            return show(t, b, "");
        });
        find("TH1", "ShowBackground").suggestions.put("option", new String[]{"same", "same BackOrder4",
            "same BackIncreasingWindow", "same nosmoothing", "same BackSmoothing5", "same Compton", "goff"});
        def("TH1::ShowPeaks", (t, a) -> {
            final Hist h = hist(t);
            if (h.dim == 2) return showPeaks2D(t, h, d(a, 0, 2), s(a, 1, ""), d(a, 2, 0.05));
            return showPeaks(t, h, d(a, 0, 2), s(a, 1, ""), d(a, 2, 0.05));
        });
        find("TH1", "ShowPeaks").suggestions.put("option", new String[]{"", "nobackground", "noMarkov", "nodraw",
            "nobackground noMarkov"});
        def("TH1::Smooth", (t, a) -> {
            final Hist h = hist(t);
            final int n = Math.max(1, i(a, 0, 1));
            if (h.dim == 2) {
                for (int k = 0; k < n; k++) RootSpectrum.smooth2D(h.v, h.err, h.nx(), h.ny(), s(a, 1, ""));
                return "smoothed " + n + (n == 1 ? " time" : " times");
            }
            if (h.dim != 1) throw new IllegalStateException("TH3 cannot be smoothed");
            final int[] r = s(a, 1, "").toUpperCase(Locale.ROOT).contains("R") ? shownBins(t, h) : new int[]{0, h.nx() - 1};
            final double[] part = Arrays.copyOfRange(h.v, r[0], r[1] + 1);
            if (part.length < 3) throw new IllegalStateException("at least 3 bins are needed");
            RootSpectrum.smoothArray(part, n);
            System.arraycopy(part, 0, h.v, r[0], part.length);
            restat(h);
            return "smoothed " + n + (n == 1 ? " time" : " times") + " (353QH twice)";
        });
        find("TH1", "Smooth").suggestions.put("option", new String[]{"", "R"});
    }

    static void fitSuggestions(Def d) {
        d.suggestions.put("formula", new String[]{"gaus", "gausn", "expo", "landau", "landaun", "breitwigner",
            "crystalball", "pol0", "pol1", "pol2", "pol3", "pol4", "pol5", "gaus(0)+pol1(3)", "gaus(0)+expo(3)",
            "chebyshev3", "xygaus", "bigaus", "[0]*exp(-0.5*((x-[1])/[2])^2)+[3]"});
        d.suggestions.put("option", new String[]{"", "L", "WL", "W", "Q", "R", "+", "N", "0", "S", "LQ"});
        d.suggestions.put("goption", new String[]{"", "SAME", "E", "C"});
    }

    /** The width of a cell, its area in 2D. */
    static double cellSize(Hist h, int k) {
        final int ix = k % Math.max(1, h.nx());
        double w = h.x.edge(ix + 1) - h.x.edge(ix);
        if (h.dim >= 2) {
            final int iy = k / Math.max(1, h.nx()) % Math.max(1, h.ny());
            w *= h.y.edge(iy + 1) - h.y.edge(iy);
        }
        return w;
    }

    /** TH1::Scale: contents and errors times c, divided by the bin width with "width". */
    static void scale(Hist h, double c, boolean width) {
        final double[] e = errors(h);
        for (int k = 0; k < h.v.length; k++) {
            final double f = width ? c / cellSize(h, k) : c;
            h.v[k] *= f;
            e[k] *= Math.abs(f);
        }
        h.err = e;
        if (!Double.isNaN(h.max)) h.max *= c;
        if (!Double.isNaN(h.min)) h.min *= c;
        restat(h);
    }

    /** TH1::Rebin: ngroup bins merged into one, or onto the edges given. */
    static void rebinX(Hist h, int ngroup, double[] xbins) {
        final int n = h.nx();
        final double[] e2 = errors(h);
        if (xbins != null) {
            final int m = xbins.length - 1;
            final double[] v = new double[m];
            final double[] e = new double[m];
            for (int i = 0; i < n; i++) {
                final double c = h.x.center(i);
                for (int k = 0; k < m; k++) {
                    if (c >= xbins[k] && c < xbins[k + 1]) {
                        v[k] += h.v[i];
                        e[k] += e2[i] * e2[i];
                        break;
                    }
                }
            }
            for (int k = 0; k < m; k++) e[k] = Math.sqrt(e[k]);
            final Axis a = axis(m, xbins[0], xbins[m], h.x.title);
            a.edges = xbins.clone();
            h.x = a;
            h.v = v;
            h.err = e;
            restat(h);
            return;
        }
        if (ngroup > n) throw new IllegalArgumentException("ngroup " + ngroup + " is above the " + n + " bins");
        final int m = n / ngroup;
        final double[] v = new double[m];
        final double[] e = new double[m];
        for (int k = 0; k < m; k++) {
            for (int j = 0; j < ngroup; j++) {
                v[k] += h.v[k * ngroup + j];
                e[k] += e2[k * ngroup + j] * e2[k * ngroup + j];
            }
            e[k] = Math.sqrt(e[k]);
        }
        // What is left past the last full group goes to the overflow, as ROOT does.
        for (int i = m * ngroup; i < n; i++) h.overflow += h.v[i];
        final Axis a = copy(h.x);
        a.n = m;
        a.hi = h.x.edge(m * ngroup);
        if (h.x.edges != null) {
            a.edges = new double[m + 1];
            for (int k = 0; k <= m; k++) a.edges[k] = h.x.edge(k * ngroup);
        }
        if (h.x.labels != null) {
            a.labels = new String[m];
            for (int k = 0; k < m; k++) a.labels[k] = k * ngroup < h.x.labels.length ? h.x.labels[k * ngroup] : "";
        }
        if (h.x.first > 0) {
            a.first = Math.max(1, (h.x.first - 1) / ngroup + 1);
            a.last = Math.max(a.first, Math.min(m, (h.x.last - 1) / ngroup + 1));
        }
        h.x = a;
        h.v = v;
        h.err = e;
        restat(h);
    }

    /** TH1::ShowPeaks through TSpectrum::Search: markers on the peaks, the positions in the status line. */
    static String showPeaks(RootTarget t, Hist h, double sigma, String option, double threshold) {
        final int[] r = shownBins(t, h);
        final double[] src = Arrays.copyOfRange(h.v, r[0], r[1] + 1);
        final String o = option == null ? "" : option.toLowerCase(Locale.ROOT);
        final RootSpectrum.Peaks peaks = RootSpectrum.searchHighRes(src, sigma, threshold * 100,
            !o.contains("nobackground"), !o.contains("nomarkov"));
        final int n = peaks.positions().length;
        final double[] xs = new double[n];
        final double[] ys = new double[n];
        final StringBuilder list = new StringBuilder();
        for (int k = 0; k < n; k++) {
            final double pos = peaks.positions()[k];
            final int bin = r[0] + (int) (pos + 0.5);
            final double w = h.x.edge(bin + 1) - h.x.edge(bin);
            xs[k] = h.x.edge(r[0]) + (pos + 0.5) * w;
            if (h.x.edges != null) xs[k] = h.x.center(Math.min(h.nx() - 1, bin));
            ys[k] = h.at(Math.min(h.nx() - 1, bin), 0);
            list.append(k == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.5g", xs[k]));
        }
        if (!o.contains("nodraw") && !o.contains("goff")) {
            t.pad.items.removeIf(i -> i instanceof RootScene.Shape s && "pm".equals(s.kind) && (h.name + "_peaks").equals(s.name));
            final RootScene.Shape pm = new RootScene.Shape();
            pm.kind = "pm";
            pm.className = "TPolyMarker";
            pm.name = h.name + "_peaks";
            pm.xs = xs;
            pm.ys = ys;
            pm.marker = RootColors.color(2);
            pm.markerStyle = 23;
            pm.markerSize = 1.3;
            t.pad.items.add(pm);
        }
        return n == 0 ? "no peak found (sigma " + sigma + ", threshold " + threshold + ")"
            : n + (n == 1 ? " peak" : " peaks") + " at " + list;
    }

    /**
     * TH2::ShowPeaks: the local maxima of the smoothed histogram above the
     * threshold, a neighbourhood of sigma bins apart, as TSpectrum2::Search marks them.
     */
    static String showPeaks2D(RootTarget t, Hist h, double sigma, String option, double threshold) {
        final int nx = h.nx();
        final int ny = h.ny();
        final double[] v = h.v.clone();
        if (!(option != null && option.toLowerCase(Locale.ROOT).contains("nomarkov"))) {
            RootSpectrum.smooth2D(v, null, nx, ny, "k5a");
        }
        double top = 0;
        for (double c : v) top = Math.max(top, c);
        final int reach = Math.max(1, (int) Math.round(sigma));
        final List<double[]> found = new ArrayList<>();
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                final double c = v[iy * nx + ix];
                if (c <= threshold * top) continue;
                boolean max = true;
                for (int dy = -reach; dy <= reach && max; dy++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        final int x = ix + dx;
                        final int y = iy + dy;
                        if (x < 0 || y < 0 || x >= nx || y >= ny) continue;
                        final double o = v[y * nx + x];
                        if (o > c || o == c && (dy < 0 || dy == 0 && dx < 0)) {
                            max = false;
                            break;
                        }
                    }
                }
                if (max) found.add(new double[]{h.x.center(ix), h.y.center(iy), c});
            }
        }
        found.sort((p, q) -> Double.compare(q[2], p[2]));
        while (found.size() > RootSpectrum.MAX_PEAKS) found.remove(found.size() - 1);
        final RootScene.Shape pm = new RootScene.Shape();
        pm.kind = "pm";
        pm.className = "TPolyMarker";
        pm.name = h.name + "_peaks";
        pm.xs = new double[found.size()];
        pm.ys = new double[found.size()];
        final StringBuilder list = new StringBuilder();
        for (int k = 0; k < found.size(); k++) {
            pm.xs[k] = found.get(k)[0];
            pm.ys[k] = found.get(k)[1];
            if (k < 8) list.append(k == 0 ? "" : ", ").append(String.format(Locale.ROOT, "(%.4g, %.4g)", pm.xs[k], pm.ys[k]));
        }
        pm.marker = RootColors.color(2);
        pm.markerStyle = 23;
        pm.markerSize = 1.3;
        t.pad.items.removeIf(i -> i instanceof RootScene.Shape s && pm.name.equals(s.name));
        t.pad.items.add(pm);
        return found.size() + " peaks" + (found.isEmpty() ? "" : ": " + list + (found.size() > 8 ? "..." : ""));
    }

    /* ------------------------------------------------------------------ */
    /* Fit                                                                 */
    /* ------------------------------------------------------------------ */

    /** What a fit did: the result, and the line for the status bar. */
    record Fitted(RootFitter.Result result, Hist function, String message) {
    }

    /**
     * TH1::Fit, TGraph::Fit, TGraph2D::Fit: the formula fitted over the range
     * (the pad's when xmin = xmax), by chi2 or likelihood ("L"), errors
     * ignored with "W"; the function joins the object's list ("+" keeps the
     * others), is drawn unless "N" or "0", and the result is printed unless "Q".
     */
    static Fitted fit(RootTarget t, String formula, String option, String goption, double xmin, double xmax, double[] p0) {
        final Item it = t.item();
        final String o = option == null ? "" : option.toUpperCase(Locale.ROOT);
        RootFormula f;
        double[] start = p0;
        final Hist named = functionNamed(t, formula);
        if (named != null) {
            f = RootFormula.parse(named.formula);
            if (start == null && named.parameters != null) start = named.parameters.clone();
        } else {
            f = RootFormula.parse(formula);
        }
        final boolean likelihood = o.contains("L");
        final boolean ignore = o.contains("W");
        final List<double[]> xs = new ArrayList<>();
        final List<Double> ys = new ArrayList<>();
        final List<Double> es = new ArrayList<>();
        final List<Double> exs = new ArrayList<>();
        double lo;
        double hi;
        double ylo = 0;
        double yhi = 0;
        final boolean ranged = xmax > xmin;
        if (it instanceof Hist h && h.dim == 1) {
            final int[] r = shownBins(t, h);
            lo = ranged ? xmin : h.x.edge(r[0]);
            hi = ranged ? xmax : h.x.edge(r[1] + 1);
            for (int i = 0; i < h.nx(); i++) {
                final double c = h.x.center(i);
                if (c < lo || c > hi) continue;
                xs.add(new double[]{c});
                ys.add(h.at(i, 0));
                es.add(err(h, i));
            }
        } else if (it instanceof Hist h && h.dim == 2) {
            lo = ranged ? xmin : h.x.shown()[0];
            hi = ranged ? xmax : h.x.shown()[1];
            ylo = h.y.shown()[0];
            yhi = h.y.shown()[1];
            for (int iy = 0; iy < h.ny(); iy++) {
                for (int ix = 0; ix < h.nx(); ix++) {
                    final double cx = h.x.center(ix);
                    final double cy = h.y.center(iy);
                    if (cx < lo || cx > hi || cy < ylo || cy > yhi) continue;
                    xs.add(new double[]{cx, cy});
                    ys.add(h.at(ix, iy));
                    es.add(err(h, iy * h.nx() + ix));
                }
            }
        } else if (it instanceof Graph g) {
            final View v = RootActions.view(t);
            lo = ranged ? xmin : Double.NEGATIVE_INFINITY;
            hi = ranged ? xmax : Double.POSITIVE_INFINITY;
            double a = Double.POSITIVE_INFINITY;
            double b = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < g.x.length; i++) {
                if (g.x[i] < lo || g.x[i] > hi) continue;
                xs.add(new double[]{g.x[i]});
                ys.add(g.y[i]);
                es.add(g.eyl == null ? 1.0 : 0.5 * (g.eyl[i] + g.eyh[i]));
                exs.add(g.exl == null ? 0.0 : 0.5 * (g.exl[i] + g.exh[i]));
                a = Math.min(a, g.x[i]);
                b = Math.max(b, g.x[i]);
            }
            if (!ranged) {
                lo = a;
                hi = b;
                if (v.x1 > v.x0 && t.item() == t.pad.main()) {
                    lo = Math.min(lo, v.x0);
                    hi = Math.max(hi, v.x1);
                }
            }
        } else if (it instanceof Graph2D g) {
            lo = Double.POSITIVE_INFINITY;
            hi = Double.NEGATIVE_INFINITY;
            ylo = Double.POSITIVE_INFINITY;
            yhi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < g.x.length; i++) {
                xs.add(new double[]{g.x[i], g.y[i]});
                ys.add(g.z[i]);
                es.add(1.0);
                lo = Math.min(lo, g.x[i]);
                hi = Math.max(hi, g.x[i]);
                ylo = Math.min(ylo, g.y[i]);
                yhi = Math.max(yhi, g.y[i]);
            }
        } else {
            throw new IllegalStateException(t.className + " cannot be fitted");
        }
        if (xs.size() <= f.parameters()) {
            throw new IllegalStateException(xs.size() + " points for " + f.parameters() + " parameters: nothing to fit");
        }
        final double[][] px = xs.toArray(new double[0][]);
        final double[] py = ys.stream().mapToDouble(Double::doubleValue).toArray();
        double[] pe = es.stream().mapToDouble(Double::doubleValue).toArray();
        final boolean graphNoErrors = it instanceof Graph g0 && g0.eyl == null || it instanceof Graph2D;
        final RootFitter.Options fitOptions = RootFitter.Options.of(o);
        RootFitter.Result res = RootFitter.fit(f, px, py, pe, start, likelihood && it instanceof Hist,
            ignore || graphNoErrors, fitOptions);
        // TGraphErrors: the x errors count through the slope (effective variance), as ROOT fits them.
        if (it instanceof Graph g && g.exl != null && !o.contains("EX0") && !ignore) {
            final double[] p = res.p();
            final double[] eff = pe.clone();
            for (int k = 0; k < px.length; k++) {
                final double x = px[k][0];
                final double hstep = Math.max(1e-8, 1e-4 * (Math.abs(x) + exs.get(k)));
                final double slope = (f.eval(x + hstep, p) - f.eval(x - hstep, p)) / (2 * hstep);
                eff[k] = Math.sqrt(pe[k] * pe[k] + slope * slope * exs.get(k) * exs.get(k));
            }
            pe = eff;
            res = RootFitter.fit(f, px, py, pe, p, false, false, fitOptions);
        }
        final boolean twoD = it instanceof Graph2D || it instanceof Hist h2 && h2.dim == 2;
        final Hist fn = new Hist();
        fn.kind = twoD ? "h2" : "h1";
        fn.className = twoD ? "TF2" : "TF1";
        fn.name = named != null ? named.name : formula.matches("[A-Za-z]\\w*") ? formula : "PrevFitTMP";
        fn.title = f.text();
        fn.formula = f.text();
        fn.parameters = res.p().clone();
        fn.dim = twoD ? 2 : 1;
        fn.x = axis(100, lo, hi, "");
        if (twoD) fn.y = axis(40, ylo, yhi, "");
        fn.line = FUNCTION_COLOR;
        fn.lineWidth = 2;
        resample(fn, lo, hi, twoD ? 40 : 100, 40, ylo, yhi);
        final boolean draw = !o.contains("N") && !o.contains("0");
        final List<String[]> rows = new ArrayList<>();
        for (int k = 0; k < res.p().length; k++) {
            rows.add(new String[]{res.names().get(k), Double.toString(res.p()[k]), Double.toString(res.err()[k])});
        }
        rows.add(new String[]{"#chi2", Double.toString(res.chi2()), Integer.toString(res.ndf())});
        rows.add(new String[]{"#prob", Double.toString(res.prob())});
        if (it instanceof Hist h) {
            if (!o.contains("+")) h.fits.clear();
            if (draw) h.fits.add(fn);
            h.fitResults.clear();
            h.fitResults.addAll(rows);
        } else if (it instanceof Graph g) {
            if (!o.contains("+")) g.fits.clear();
            if (draw) g.fits.add(fn);
            g.fitResults.clear();
            g.fitResults.addAll(rows);
        } else if (it instanceof Graph2D g && draw) {
            fn.option = "SURF SAME";
            show(t, fn, "SURF1");
        }
        final String what = t.className + "::" + it.name;
        if (!o.contains("Q")) t.canvas.host().show("Fit of " + it.name, res.report(what, f.text()));
        final String message = String.format(Locale.ROOT, "%s fitted with %s: chi2/ndf = %.4g / %d, prob %.3g%s", it.name,
            f.text(), res.chi2(), res.ndf(), res.prob(), res.converged() ? "" : " (NOT CONVERGED)");
        return new Fitted(res, fn, message);
    }

    /** A function of the canvas by name, for Fit("myfunc"). */
    static Hist functionNamed(RootTarget t, String name) {
        if (name == null || !name.matches("[A-Za-z_]\\w*")) return null;
        for (Pad p : t.scene.pad.flatten()) {
            for (Item i : p.items) {
                if (i instanceof Hist h && h.isFunction() && name.equals(h.name)) return h;
                if (i instanceof Hist h) for (Hist f : h.fits) if (name.equals(f.name) && f.formula != null) return f;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* TH2                                                                 */
    /* ------------------------------------------------------------------ */

    private static void registerHist2() {
        def("TH2::RebinX", (t, a) -> rebin2D(t, hist(t, 2), i(a, 0, 2), 1, s(a, 1, "")));
        def("TH2::RebinY", (t, a) -> rebin2D(t, hist(t, 2), 1, i(a, 0, 2), s(a, 1, "")));
        def("TH2::Rebin2D", (t, a) -> rebin2D(t, hist(t, 2), i(a, 0, 2), i(a, 1, 2), s(a, 2, "")));
        look("TH2::ProjectionX", (t, a) -> show(t, project(hist(t, 2), true, derivedName(hist(t), s(a, 0, "_px"), "_px"),
            i(a, 1, 0), i(a, 2, -1)), ""));
        look("TH2::ProjectionY", (t, a) -> show(t, project(hist(t, 2), false, derivedName(hist(t), s(a, 0, "_py"), "_py"),
            i(a, 1, 0), i(a, 2, -1)), ""));
        look("TH2::ProfileX", (t, a) -> show(t, profile(hist(t, 2), true, derivedName(hist(t), s(a, 0, "_pfx"), "_pfx"),
            i(a, 1, 1), i(a, 2, -1), s(a, 3, "")), ""));
        look("TH2::ProfileY", (t, a) -> show(t, profile(hist(t, 2), false, derivedName(hist(t), s(a, 0, "_pfy"), "_pfy"),
            i(a, 1, 1), i(a, 2, -1), s(a, 3, "")), ""));
        for (String f : new String[]{"ProfileX", "ProfileY"}) {
            find("TH2", f).suggestions.put("option", new String[]{"", "s", "i", "g", "o"});
        }
        def("TH2::SetShowProjectionX", (t, a) -> {
            t.pad.showProjectionX = Math.max(0, i(a, 0, 1));
            t.canvas.showProjections(t.pad);
            return t.pad.showProjectionX > 0 ? "move the mouse over " + hist(t).name + ": its projection on x follows"
                : "projection on x off";
        }).current = t -> new Object[]{(long) Math.max(1, t.pad.showProjectionX)};
        def("TH2::SetShowProjectionY", (t, a) -> {
            t.pad.showProjectionY = Math.max(0, i(a, 0, 1));
            t.canvas.showProjections(t.pad);
            return t.pad.showProjectionY > 0 ? "move the mouse over " + hist(t).name + ": its projection on y follows"
                : "projection on y off";
        }).current = t -> new Object[]{(long) Math.max(1, t.pad.showProjectionY)};
        def("TH2::SetShowProjectionXY", (t, a) -> {
            t.pad.showProjectionY = Math.max(0, i(a, 0, 1));
            t.pad.showProjectionX = Math.max(0, i(a, 1, 1));
            t.canvas.showProjections(t.pad);
            return "projections on x and y follow the mouse";
        });
        def("TH2::ShowPeaks", (t, a) -> showPeaks2D(t, hist(t, 2), d(a, 0, 2), s(a, 1, ""), d(a, 2, 0.05)));
        def("TH2::Smooth", (t, a) -> {
            final Hist h = hist(t, 2);
            final int n = Math.max(1, i(a, 0, 1));
            for (int k = 0; k < n; k++) RootSpectrum.smooth2D(h.v, h.err, h.nx(), h.ny(), s(a, 1, ""));
            restat(h);
            return "smoothed " + n + (n == 1 ? " time" : " times") + " with " + (s(a, 1, "").isBlank() ? "k5a" : s(a, 1, ""));
        });
        find("TH2", "Smooth").suggestions.put("option", new String[]{"", "k5a", "k5b", "k3a"});
    }

    /** TH2::Rebin2D: nx by ny cells merged into one, in place or into a new histogram. */
    static String rebin2D(RootTarget t, Hist h, int ngx, int ngy, String newName) {
        ngx = Math.max(1, ngx);
        ngy = Math.max(1, ngy);
        final int nx = h.nx();
        final int ny = h.ny();
        if (ngx > nx || ngy > ny) throw new IllegalArgumentException("more bins grouped than the axis has");
        final int mx = nx / ngx;
        final int my = ny / ngy;
        final double[] e2 = errors(h);
        final double[] v = new double[mx * my];
        final double[] e = new double[mx * my];
        for (int iy = 0; iy < my * ngy; iy++) {
            for (int ix = 0; ix < mx * ngx; ix++) {
                final int k = (iy / ngy) * mx + ix / ngx;
                v[k] += h.v[iy * nx + ix];
                e[k] += e2[iy * nx + ix] * e2[iy * nx + ix];
            }
        }
        for (int k = 0; k < e.length; k++) e[k] = Math.sqrt(e[k]);
        final Hist target = newName == null || newName.isBlank() ? h : (Hist) RootSceneJson.copy(h);
        target.x = grouped(h.x, ngx, mx);
        target.y = grouped(h.y, ngy, my);
        target.v = v;
        target.err = e;
        restat(target);
        if (target == h) return h.name + " now " + mx + " x " + my + " bins";
        target.name = newName;
        return show(t, target, h.option);
    }

    private static Axis grouped(Axis a, int group, int m) {
        final Axis c = copy(a);
        c.n = m;
        c.hi = a.edge(m * group);
        if (a.edges != null) {
            c.edges = new double[m + 1];
            for (int k = 0; k <= m; k++) c.edges[k] = a.edge(k * group);
        }
        if (a.labels != null) {
            c.labels = new String[m];
            for (int k = 0; k < m; k++) c.labels[k] = k * group < a.labels.length ? a.labels[k * group] : "";
        }
        c.first = 0;
        c.last = 0;
        return c;
    }

    /** The bins [first, last] of an axis, 1-based, 0 and -1 for all, as 0-based bounds. */
    static int[] bins(Axis a, int first, int last) {
        int lo = first <= 0 ? 0 : first - 1;
        int hi = last < 0 || last > a.n ? a.n - 1 : last - 1;
        if (last == 0 && first == 0) hi = a.n - 1;
        if (hi < lo) {
            lo = 0;
            hi = a.n - 1;
        }
        return new int[]{lo, Math.min(a.n - 1, hi)};
    }

    /** TH2::ProjectionX (onX) or ProjectionY: the cells summed over the other axis's bins first to last. */
    static Hist project(Hist h, boolean onX, String name, int first, int last) {
        final Axis along = onX ? h.x : h.y;
        final Axis across = onX ? h.y : h.x;
        final int[] r = bins(across, first, last);
        final double[] v = new double[along.n];
        final double[] e = new double[along.n];
        for (int i = 0; i < along.n; i++) {
            for (int j = r[0]; j <= r[1]; j++) {
                final int k = onX ? j * h.nx() + i : i * h.nx() + j;
                v[i] += h.v[k];
                e[i] += err(h, k) * err(h, k);
            }
            e[i] = Math.sqrt(e[i]);
        }
        final Axis a = copy(along);
        a.first = 0;
        a.last = 0;
        final Hist p = newHist("TH1D", name, h.title + (onX ? " (x projection)" : " (y projection)"), a, v, e);
        p.x.title = along.title;
        return p;
    }

    /**
     * TH2::ProfileX (onX) or ProfileY: in each bin of one axis, the mean of
     * the other weighted by the contents, with the error of the mean (the
     * spread with "s", ROOT's integer and Gaussian options with "i" and "g").
     */
    static Hist profile(Hist h, boolean onX, String name, int first, int last, String option) {
        final Axis along = onX ? h.x : h.y;
        final Axis across = onX ? h.y : h.x;
        final int[] r = bins(across, first, last);
        final double[] mean = new double[along.n];
        final double[] e = new double[along.n];
        final double[] entries = new double[along.n];
        final String o = option == null ? "" : option.toLowerCase(Locale.ROOT);
        for (int i = 0; i < along.n; i++) {
            double sw = 0;
            double sw2 = 0;
            double swy = 0;
            double swy2 = 0;
            for (int j = r[0]; j <= r[1]; j++) {
                final int k = onX ? j * h.nx() + i : i * h.nx() + j;
                final double w = h.v[k];
                final double y = across.center(j);
                sw += w;
                sw2 += err(h, k) * err(h, k);
                swy += w * y;
                swy2 += w * y * y;
            }
            entries[i] = sw;
            if (sw == 0) continue;
            mean[i] = swy / sw;
            final double spread = Math.sqrt(Math.max(0, swy2 / sw - mean[i] * mean[i]));
            final double neff = sw2 > 0 ? sw * sw / sw2 : sw;
            e[i] = profileError(spread, neff, sw, o);
        }
        final Axis a = copy(along);
        a.first = 0;
        a.last = 0;
        final Hist p = newHist("TProfile", name, "Profile of " + h.title, a, mean, e);
        p.binEntries = entries;
        p.errorOption = o.replaceAll("[^sig]", "");
        p.yTitle = across.title;
        p.entries = h.entries;
        restat(p);
        return p;
    }

    /** TProfile's error of a bin for its error option. */
    static double profileError(double spread, double neff, double sw, String option) {
        if (option.contains("g")) return sw > 0 ? 1 / Math.sqrt(sw) : 0;
        double s = spread;
        if (option.contains("i") && s == 0) s = 1 / Math.sqrt(12);
        if (option.contains("s")) return s;
        return neff > 0 ? s / Math.sqrt(neff) : 0;
    }

    /* ------------------------------------------------------------------ */
    /* TH3                                                                 */
    /* ------------------------------------------------------------------ */

    private static void registerHist3() {
        look("TH3::Project3D", (t, a) -> {
            final Hist h = hist(t, 3);
            final String o = s(a, 0, "x");
            return show(t, project3D(h, o), axesOf(o).length == 2 ? "COLZ" : "");
        });
        find("TH3", "Project3D").suggestions.put("option", new String[]{"x", "y", "z", "xy", "yx", "xz", "zx", "yz", "zy",
            "xe", "yxe"});
        look("TH3::Project3DProfile", (t, a) -> show(t, profile3D(hist(t, 3), s(a, 0, "xy")), "COLZ"));
        find("TH3", "Project3DProfile").suggestions.put("option", new String[]{"xy", "yx", "xz", "zx", "yz", "zy"});
        look("TH3::SetShowProjection", (t, a) -> {
            t.pad.showProjection3D = s(a, 0, "xy");
            RootPanels.explore3D(t, hist(t, 3), s(a, 0, "xy"), Math.max(1, i(a, 1, 1)));
            return "slices of " + hist(t).name + " follow the slider";
        });
        find("TH3", "SetShowProjection").suggestions.put("option", new String[]{"xy", "yx", "xz", "zx", "yz", "zy", "x",
            "y", "z"});
    }

    /** The axes an option of Project3D names, in order: "yx" gives {1, 0}. */
    static int[] axesOf(String option) {
        final List<Integer> out = new ArrayList<>();
        for (char c : option.toLowerCase(Locale.ROOT).toCharArray()) {
            final int a = "xyz".indexOf(c);
            if (a >= 0 && !out.contains(a)) out.add(a);
            if (out.size() == 2) break;
        }
        if (out.isEmpty()) out.add(0);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    static Axis axisOf(Hist h, int a) {
        return a == 0 ? h.x : a == 1 ? h.y : h.z;
    }

    static int index(Hist h, int[] bin) {
        return (bin[2] * h.ny() + bin[1]) * h.nx() + bin[0];
    }

    /**
     * TH3::Project3D: one axis ("x") summed over the others into a TH1D, or
     * two ("yx": y against x) into a TH2D, the first letter vertical. The
     * ranges set on the other axes (SetRange) are respected.
     */
    static Hist project3D(Hist h, String option) {
        return slice(h, option, -1, 0, 0);
    }

    /** As Project3D, the axis sliceAxis only between its bins from and to (0-based, inclusive); -1 for none. */
    static Hist slice(Hist h, String option, int sliceAxis, int fromBin, int toBin) {
        final int[] axes = axesOf(option);
        final boolean two = axes.length == 2;
        final int ax = two ? axes[1] : axes[0];
        final int ay = two ? axes[0] : -1;
        final Axis xa = axisOf(h, ax);
        final Axis ya = two ? axisOf(h, ay) : null;
        final int n = xa.n * (two ? ya.n : 1);
        final double[] v = new double[n];
        final double[] e = new double[n];
        final int[] bin = new int[3];
        final int[][] ranges = new int[3][];
        for (int a = 0; a < 3; a++) {
            final Axis axis = axisOf(h, a);
            ranges[a] = axis.first > 0 ? new int[]{axis.first - 1, axis.last - 1} : new int[]{0, axis.n - 1};
        }
        if (sliceAxis >= 0 && sliceAxis < 3 && toBin >= fromBin) ranges[sliceAxis] = new int[]{fromBin, toBin};
        for (bin[2] = ranges[2][0]; bin[2] <= ranges[2][1]; bin[2]++) {
            for (bin[1] = ranges[1][0]; bin[1] <= ranges[1][1]; bin[1]++) {
                for (bin[0] = ranges[0][0]; bin[0] <= ranges[0][1]; bin[0]++) {
                    final int k = index(h, bin);
                    if (k >= h.v.length) continue;
                    final int o = two ? bin[ay] * xa.n + bin[ax] : bin[ax];
                    v[o] += h.v[k];
                    e[o] += err(h, k) * err(h, k);
                }
            }
        }
        for (int k = 0; k < n; k++) e[k] = Math.sqrt(e[k]);
        final Axis x = copy(xa);
        x.first = 0;
        x.last = 0;
        final String name = h.name + "_" + option.toLowerCase(Locale.ROOT).replaceAll("[^xyz]", "");
        if (!two) return newHist("TH1D", name, h.title + " (" + option + " projection)", x, v, e);
        final Axis y = copy(ya);
        y.first = 0;
        y.last = 0;
        return newHist2("TH2D", name, h.title + " (" + option + " projection)", x, y, v, e);
    }

    /** TH3::Project3DProfile: the mean of the third axis over the two named. */
    static Hist profile3D(Hist h, String option) {
        final int[] axes = axesOf(option);
        if (axes.length != 2) throw new IllegalArgumentException("Project3DProfile takes two axes, as \"xy\"");
        final int ax = axes[1];
        final int ay = axes[0];
        final int az = 3 - ax - ay;
        final Axis xa = axisOf(h, ax);
        final Axis ya = axisOf(h, ay);
        final Axis za = axisOf(h, az);
        final double[] sw = new double[xa.n * ya.n];
        final double[] swz = new double[sw.length];
        final double[] swz2 = new double[sw.length];
        final double[] sw2 = new double[sw.length];
        final int[] bin = new int[3];
        for (bin[2] = 0; bin[2] < h.nz(); bin[2]++) {
            for (bin[1] = 0; bin[1] < h.ny(); bin[1]++) {
                for (bin[0] = 0; bin[0] < h.nx(); bin[0]++) {
                    final int k = index(h, bin);
                    final int o = bin[ay] * xa.n + bin[ax];
                    final double w = h.v[k];
                    final double z = za.center(bin[az]);
                    sw[o] += w;
                    sw2[o] += err(h, k) * err(h, k);
                    swz[o] += w * z;
                    swz2[o] += w * z * z;
                }
            }
        }
        final double[] mean = new double[sw.length];
        final double[] e = new double[sw.length];
        for (int k = 0; k < sw.length; k++) {
            if (sw[k] == 0) continue;
            mean[k] = swz[k] / sw[k];
            final double spread = Math.sqrt(Math.max(0, swz2[k] / sw[k] - mean[k] * mean[k]));
            e[k] = profileError(spread, sw2[k] > 0 ? sw[k] * sw[k] / sw2[k] : sw[k], sw[k], "");
        }
        final Hist p = newHist2("TProfile2D", h.name + "_p" + option, "Profile of " + h.title, copy(xa), copy(ya), mean, e);
        p.binEntries = sw;
        p.zTitle = za.title;
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* TProfile, TProfile2D                                                */
    /* ------------------------------------------------------------------ */

    private static void registerProfiles() {
        for (String c : new String[]{"TProfile", "TProfile2D"}) {
            def(c + "::Add", (t, a) -> combine(t, a, '+'));
            def(c + "::Divide", (t, a) -> combine(t, a, '/'));
            def(c + "::Multiply", (t, a) -> combine(t, a, '*'));
            def(c + "::SetErrorOption", (t, a) -> setErrorOption(hist(t), s(a, 0, "")))
                .current = t -> new Object[]{hist(t).errorOption};
            find(c, "SetErrorOption").suggestions.put("option", new String[]{"", "s", "i", "g"});
            for (String f : new String[]{"Add", "Divide", "Multiply"}) {
                find(c, f).current = t -> new Object[]{null, null, 1.0, 1.0, ""};
            }
        }
        def("TProfile3D::SetErrorOption", (t, a) -> setErrorOption(hist(t), s(a, 0, "")));
        look("TProfile2D::ProfileX", (t, a) -> show(t, profileOfProfile(hist(t, 2), true, derivedName(hist(t),
            s(a, 0, "_pfx"), "_pfx"), i(a, 1, 0), i(a, 2, -1)), ""));
        look("TProfile2D::ProfileY", (t, a) -> show(t, profileOfProfile(hist(t, 2), false, derivedName(hist(t),
            s(a, 0, "_pfy"), "_pfy"), i(a, 1, 0), i(a, 2, -1)), ""));
        look("TProfile3D::Project3DProfile", (t, a) -> show(t, profile3D(hist(t, 3), s(a, 0, "xy")), "COLZ"));
    }

    /**
     * TProfile::Add, Divide, Multiply of two profiles (or histograms): the
     * means combined bin by bin, weighted by their entries for Add as ROOT
     * adds the sums.
     */
    static String combine(RootTarget t, Object[] a, char op) {
        final Hist self = hist(t);
        final Hist h1 = a != null && a.length > 0 && a[0] instanceof Hist x ? x : null;
        final Hist h2 = a != null && a.length > 1 && a[1] instanceof Hist x ? x : null;
        if (h1 == null || h2 == null) throw new IllegalArgumentException("choose the two histograms h1 and h2");
        if (h1.v.length != h2.v.length || h1.v.length != self.v.length) {
            throw new IllegalArgumentException("h1, h2 and " + self.name + " must have the same bins");
        }
        final double c1 = d(a, 2, 1);
        final double c2 = d(a, 3, 1);
        final double[] v = new double[self.v.length];
        final double[] e = new double[self.v.length];
        final double[] n = new double[self.v.length];
        for (int k = 0; k < v.length; k++) {
            final double n1 = h1.binEntries != null ? h1.binEntries[k] : 1;
            final double n2 = h2.binEntries != null ? h2.binEntries[k] : 1;
            final double m1 = h1.v[k];
            final double m2 = h2.v[k];
            final double e1 = err(h1, k);
            final double e2 = err(h2, k);
            switch (op) {
                case '+' -> {
                    final double w = c1 * n1 + c2 * n2;
                    n[k] = w;
                    v[k] = w == 0 ? 0 : (c1 * n1 * m1 + c2 * n2 * m2) / w;
                    e[k] = w == 0 ? 0 : Math.sqrt(c1 * n1 * c1 * n1 * e1 * e1 + c2 * n2 * c2 * n2 * e2 * e2) / Math.abs(w);
                }
                case '*' -> {
                    v[k] = c1 * m1 * c2 * m2;
                    e[k] = Math.abs(c1 * c2) * Math.sqrt(e1 * e1 * m2 * m2 + e2 * e2 * m1 * m1);
                    n[k] = Math.min(n1, n2);
                }
                default -> {
                    final double den = c2 * m2;
                    v[k] = den == 0 ? 0 : c1 * m1 / den;
                    e[k] = den == 0 || m1 == 0 ? 0 : Math.abs(v[k]) * Math.sqrt(e1 * e1 / (m1 * m1) + e2 * e2 / (m2 * m2));
                    n[k] = Math.min(n1, n2);
                }
            }
        }
        self.v = v;
        self.err = e;
        self.binEntries = n;
        restat(self);
        return switch (op) {
            case '+' -> self.name + " = " + c1 + "*" + h1.name + " + " + c2 + "*" + h2.name;
            case '*' -> self.name + " = " + c1 + "*" + h1.name + " * " + c2 + "*" + h2.name;
            default -> self.name + " = " + c1 + "*" + h1.name + " / (" + c2 + "*" + h2.name + ")";
        };
    }

    /** TProfile::SetErrorOption: the errors recomputed from the error of the mean the profile holds. */
    static String setErrorOption(Hist h, String option) {
        final String now = h.errorOption == null ? "" : h.errorOption.toLowerCase(Locale.ROOT);
        final String next = option == null ? "" : option.toLowerCase(Locale.ROOT);
        if (h.binEntries == null) {
            h.errorOption = next;
            throw new IllegalStateException("the bin entries of " + h.name + " were not exported: the option is kept,"
                + " the errors need the ROOT engine");
        }
        final double[] e = errors(h);
        for (int k = 0; k < e.length; k++) {
            final double n = h.binEntries[k];
            if (n <= 0) continue;
            // Back to the spread, then to what the new option shows.
            double spread = now.contains("s") ? e[k] : now.contains("g") ? Double.NaN : e[k] * Math.sqrt(n);
            if (Double.isNaN(spread)) throw new IllegalStateException("from the \"g\" option the spread is lost");
            e[k] = profileError(spread, n, n, next);
        }
        h.err = e;
        h.errorOption = next;
        return "error option \"" + next + "\"";
    }

    /** TProfile2D::ProfileX: the means of a 2D profile averaged over y, weighted by their entries. */
    static Hist profileOfProfile(Hist h, boolean onX, String name, int first, int last) {
        final Axis along = onX ? h.x : h.y;
        final Axis across = onX ? h.y : h.x;
        final int[] r = bins(across, first, last);
        final double[] v = new double[along.n];
        final double[] e = new double[along.n];
        final double[] entries = new double[along.n];
        for (int i = 0; i < along.n; i++) {
            double sw = 0;
            double swv = 0;
            double swe = 0;
            for (int j = r[0]; j <= r[1]; j++) {
                final int k = onX ? j * h.nx() + i : i * h.nx() + j;
                final double w = h.binEntries != null ? h.binEntries[k] : 1;
                sw += w;
                swv += w * h.v[k];
                swe += w * w * err(h, k) * err(h, k);
            }
            entries[i] = sw;
            if (sw > 0) {
                v[i] = swv / sw;
                e[i] = Math.sqrt(swe) / sw;
            }
        }
        final Hist p = newHist("TProfile", name, "Profile of " + h.title, copy(along), v, e);
        p.binEntries = entries;
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* TF1, TF2, TF3                                                       */
    /* ------------------------------------------------------------------ */

    private static void registerFunctions() {
        look("TF1::DrawDerivative", (t, a) -> {
            final Hist f = hist(t, 1);
            final Graph g = curveOf(f, f.name + "_derivative", "Derivative of " + f.name, 1);
            return show(t, g, s(a, 0, "al").toUpperCase(Locale.ROOT));
        });
        look("TF1::DrawIntegral", (t, a) -> {
            final Hist f = hist(t, 1);
            final Graph g = curveOf(f, f.name + "_integral", "Integral of " + f.name, -1);
            return show(t, g, s(a, 0, "al").toUpperCase(Locale.ROOT));
        });
        for (String f : new String[]{"DrawDerivative", "DrawIntegral"}) {
            find("TF1", f).suggestions.put("option", new String[]{"al", "al same", "ac", "alp"});
        }
        def("TF1::SetMaximum", (t, a) -> {
            hist(t).max = limit(d(a, 0, -1111));
            return "maximum set";
        });
        def("TF1::SetMinimum", (t, a) -> {
            hist(t).min = limit(d(a, 0, -1111));
            return "minimum set";
        });
        def("TF1::SetNpx", (t, a) -> {
            final Hist f = hist(t);
            final int n = Math.max(4, Math.min(10_000_000, i(a, 0, 100)));
            resample(f, f.x.lo, f.x.hi, n, f.dim == 2 ? f.ny() : 1, f.y == null ? 0 : f.y.lo, f.y == null ? 1 : f.y.hi);
            return f.name + " drawn on " + n + " points";
        }).current = t -> new Object[]{(long) hist(t).nx()};
        def("TF1::SetRange", (t, a) -> {
            final Hist f = hist(t);
            final double lo = d(a, 0, f.x.lo);
            final double hi = d(a, 1, f.x.hi);
            if (!(hi > lo)) throw new IllegalArgumentException("xmax must be above xmin");
            resample(f, lo, hi, f.nx(), f.ny(), f.y == null ? 0 : f.y.lo, f.y == null ? 1 : f.y.hi);
            if (t.item() == t.pad.main()) RootActions.view(t).xr = null;
            return String.format(Locale.ROOT, "%s on [%.6g, %.6g]", f.name, lo, hi);
        }).current = t -> new Object[]{hist(t).x.lo, hist(t).x.hi};
        def("TF2::SetNpy", (t, a) -> {
            final Hist f = hist(t, 2);
            final int n = Math.max(4, i(a, 0, 100));
            resample(f, f.x.lo, f.x.hi, f.nx(), n, f.y.lo, f.y.hi);
            return f.name + " drawn on " + n + " points in y";
        }).current = t -> new Object[]{(long) hist(t).ny()};
        def("TF2::SetRange", (t, a) -> {
            final Hist f = hist(t, 2);
            resample(f, d(a, 0, f.x.lo), d(a, 2, f.x.hi), f.nx(), f.ny(), d(a, 1, f.y.lo), d(a, 3, f.y.hi));
            return "range set";
        }).current = t -> new Object[]{hist(t).x.lo, hist(t).y.lo, hist(t).x.hi, hist(t).y.hi};
    }

    /** A function's derivative (order 1) or its integral from the low end (order -1), as a graph. */
    static Graph curveOf(Hist f, String name, String title, int order) {
        final int n = Math.max(2, f.nx());
        final DoubleBinaryOperator e = evaluator(f);
        final double[] x = new double[n];
        final double[] y = new double[n];
        final double lo = f.x.lo;
        final double hi = f.x.hi;
        for (int i = 0; i < n; i++) x[i] = lo + (hi - lo) * i / (n - 1);
        if (order > 0) {
            final double h = 1e-3 * (hi - lo) / n;
            for (int i = 0; i < n; i++) {
                if (e != null) {
                    // Five points, as TF1::Derivative's Richardson step.
                    y[i] = (-e.applyAsDouble(x[i] + 2 * h, 0) + 8 * e.applyAsDouble(x[i] + h, 0)
                        - 8 * e.applyAsDouble(x[i] - h, 0) + e.applyAsDouble(x[i] - 2 * h, 0)) / (12 * h);
                } else {
                    final double a = interpolate(f, Math.max(lo, x[i] - (hi - lo) / n));
                    final double b = interpolate(f, Math.min(hi, x[i] + (hi - lo) / n));
                    y[i] = (b - a) / (Math.min(hi, x[i] + (hi - lo) / n) - Math.max(lo, x[i] - (hi - lo) / n));
                }
            }
        } else {
            double sum = 0;
            final int fine = 20;
            for (int i = 1; i < n; i++) {
                final double step = (x[i] - x[i - 1]) / fine;
                for (int k = 0; k < fine; k++) {
                    final double a = x[i - 1] + k * step;
                    final double b = a + step;
                    final double fa = e != null ? e.applyAsDouble(a, 0) : interpolate(f, a);
                    final double fm = e != null ? e.applyAsDouble(0.5 * (a + b), 0) : interpolate(f, 0.5 * (a + b));
                    final double fb = e != null ? e.applyAsDouble(b, 0) : interpolate(f, b);
                    sum += step * (fa + 4 * fm + fb) / 6;
                }
                y[i] = sum;
            }
        }
        final Graph g = new Graph();
        g.kind = "g";
        g.className = "TGraph";
        g.name = name;
        g.title = title;
        g.x = x;
        g.y = y;
        g.line = f.line;
        g.lineWidth = Math.max(1, f.lineWidth);
        g.xTitle = f.x.title;
        return g;
    }

    /* ------------------------------------------------------------------ */
    /* TGraph and its errors                                               */
    /* ------------------------------------------------------------------ */

    private static void registerGraphs() {
        look("TGraph::DrawPanel", (t, a) -> {
            RootPanels.drawPanel(t);
            return null;
        });
        look("TGraph::FitPanel", (t, a) -> {
            if (!t.canvas.host().fitPanel(t)) RootFitPanel.open(t);
            return null;
        });
        def("TGraph::Fit", (t, a) -> fit(t, s(a, 0, "pol1"), s(a, 1, ""), s(a, 2, ""), d(a, 3, 0), d(a, 4, 0), null).message());
        fitSuggestions(find("TGraph", "Fit"));
        def("TGraph::InsertPoint", (t, a) -> {
            final Graph g = graph(t);
            if (!Double.isFinite(t.x) || !Double.isFinite(t.y)) throw new IllegalStateException("point inside the frame");
            final int at = insertionIndex(t, g);
            g.x = insert(g.x, at, t.x);
            g.y = insert(g.y, at, t.y);
            g.exl = insert(g.exl, at, 0);
            g.exh = insert(g.exh, at, 0);
            g.eyl = insert(g.eyl, at, 0);
            g.eyh = insert(g.eyh, at, 0);
            return String.format(Locale.ROOT, "point %d inserted at (%.5g, %.5g)", at, t.x, t.y);
        });
        def("TGraph::RemovePoint", (t, a) -> {
            final Graph g = graph(t);
            final int k = nearest(t, g.x, g.y);
            if (k < 0) throw new IllegalStateException("no point");
            final String said = String.format(Locale.ROOT, "point %d (%.5g, %.5g) removed", k, g.x[k], g.y[k]);
            g.x = remove(g.x, k);
            g.y = remove(g.y, k);
            g.exl = remove(g.exl, k);
            g.exh = remove(g.exh, k);
            g.eyl = remove(g.eyl, k);
            g.eyh = remove(g.eyh, k);
            return said;
        });
        look("TGraph::SaveAs", (t, a) -> RootSaver.save(t, s(a, 0, ""), s(a, 1, "")));
        for (String c : new String[]{"TGraph", "TGraphErrors", "TGraphAsymmErrors", "TGraphBentErrors", "TGraphMultiErrors"}) {
            def(c + "::Scale", (t, a) -> {
                final Graph g = graph(t);
                final double c1 = d(a, 0, 1);
                final String o = s(a, 1, "y").toLowerCase(Locale.ROOT);
                if (o.contains("x")) {
                    for (int k = 0; k < g.x.length; k++) g.x[k] *= c1;
                    scaleAll(g.exl, Math.abs(c1));
                    scaleAll(g.exh, Math.abs(c1));
                }
                if (o.contains("y") || !o.contains("x")) {
                    for (int k = 0; k < g.y.length; k++) g.y[k] *= c1;
                    scaleAll(g.eyl, Math.abs(c1));
                    scaleAll(g.eyh, Math.abs(c1));
                }
                RootActions.view(t).yr = null;
                RootActions.view(t).xr = null;
                return "scaled by " + c1 + " in " + o;
            });
            find(c, "Scale").suggestions.put("option", new String[]{"y", "x", "xy"});
        }
        def("TGraph::SetEditable", (t, a) -> {
            graph(t).editable = b(a, 0, true);
            return graph(t).editable ? "drag the points with the mouse" : "the points are fixed";
        }).state = t -> t.item() != null && t.item().editable;
        def("TGraph::SetHighlight", (t, a) -> {
            graph(t).highlight = b(a, 0, true);
            return graph(t).highlight ? "the point under the mouse is highlighted" : "highlight off";
        }).state = t -> t.item() != null && t.item().highlight;
        def("TGraph::SetMaximum", (t, a) -> {
            graph(t).max = limit(d(a, 0, -1111));
            RootActions.view(t).yr = null;
            return "maximum set";
        }).current = t -> new Object[]{Double.isNaN(graph(t).max) ? -1111.0 : graph(t).max};
        def("TGraph::SetMinimum", (t, a) -> {
            graph(t).min = limit(d(a, 0, -1111));
            RootActions.view(t).yr = null;
            return "minimum set";
        }).current = t -> new Object[]{Double.isNaN(graph(t).min) ? -1111.0 : graph(t).min};
        def("TGraph::SetStats", (t, a) -> {
            RootActions.view(t).statsOff = !b(a, 0, true);
            return b(a, 0, true) ? "statistics shown" : "statistics hidden";
        });
        def("TGraphAsymmErrors::SetPointError", (t, a) -> {
            final Graph g = graph(t);
            final int k = nearest(t, g.x, g.y);
            if (k < 0) throw new IllegalStateException("no point");
            if (g.exl == null) {
                g.exl = new double[g.x.length];
                g.exh = new double[g.x.length];
            }
            if (g.eyl == null) {
                g.eyl = new double[g.x.length];
                g.eyh = new double[g.x.length];
            }
            g.exl[k] = d(a, 0, 0);
            g.exh[k] = d(a, 1, 0);
            g.eyl[k] = d(a, 2, 0);
            g.eyh[k] = d(a, 3, 0);
            return "errors of point " + k + " set";
        }).current = t -> {
            final Graph g = graph(t);
            final int k = nearest(t, g.x, g.y);
            if (k < 0 || g.eyl == null) return new Object[]{0.0, 0.0, 0.0, 0.0};
            return new Object[]{g.exl == null ? 0.0 : g.exl[k], g.exh == null ? 0.0 : g.exh[k], g.eyl[k], g.eyh[k]};
        };
        look("TMultiGraph::FitPanel", (t, a) -> {
            if (!t.canvas.host().fitPanel(t)) RootFitPanel.open(t);
            return null;
        });
    }

    static void scaleAll(double[] a, double c) {
        if (a != null) for (int k = 0; k < a.length; k++) a[k] *= c;
    }

    /** Where TGraph::InsertPoint puts the new point: after the segment nearest the mouse. */
    static int insertionIndex(RootTarget t, Graph g) {
        final int n = g.x.length;
        if (n == 0) return 0;
        final View v = RootActions.view(t);
        int best = n;
        double dist = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < n; i++) {
            final double d = java.awt.geom.Line2D.ptSegDist(RootPadPainter.px(v, g.x[i]), RootPadPainter.py(v, g.y[i]),
                RootPadPainter.px(v, g.x[i + 1]), RootPadPainter.py(v, g.y[i + 1]), t.at.x, t.at.y);
            if (d < dist) {
                dist = d;
                best = i + 1;
            }
        }
        final double first = Math.hypot(RootPadPainter.px(v, g.x[0]) - t.at.x, RootPadPainter.py(v, g.y[0]) - t.at.y);
        final double last = Math.hypot(RootPadPainter.px(v, g.x[n - 1]) - t.at.x, RootPadPainter.py(v, g.y[n - 1]) - t.at.y);
        if (first < dist && first <= last && t.x < g.x[0]) return 0;
        if (last < dist && t.x > g.x[n - 1]) return n;
        return best;
    }

    /* ------------------------------------------------------------------ */
    /* TGraph2D                                                            */
    /* ------------------------------------------------------------------ */

    private static void registerGraph2D() {
        def("TGraph2D::Fit", (t, a) -> {
            if (a != null && a.length > 0 && a[0] instanceof Hist f2 && f2.formula != null) {
                return fit(t, f2.formula, s(a, 1, ""), s(a, 2, ""), 0, 0, f2.parameters).message();
            }
            return fit(t, s(a, 0, "xygaus"), s(a, 1, ""), s(a, 2, ""), 0, 0, null).message();
        });
        find("TGraph2D", "Fit").suggestions.put("formula", new String[]{"xygaus", "bigaus", "[0]+[1]*x+[2]*y",
            "[0]*exp(-0.5*((x-[1])^2+(y-[2])^2)/[3]^2)"});
        look("TGraph2D::FitPanel", (t, a) -> {
            if (!t.canvas.host().fitPanel(t)) RootFitPanel.open(t);
            return null;
        });
        look("TGraph2D::Project", (t, a) -> {
            final Graph2D g = graph2D(t);
            final Hist grid = interpolate(g);
            final String o = s(a, 0, "x").toLowerCase(Locale.ROOT);
            final int[] axes = axesOf(o);
            Hist p;
            if (axes.length == 2) {
                p = axes[0] == 1 ? grid : transpose(grid);
                p.name = g.name + "_" + o;
            } else {
                p = project(grid, axes[0] == 0, g.name + "_" + o, 0, -1);
            }
            p.title = g.title;
            return show(t, p, axes.length == 2 ? "COLZ" : "");
        });
        find("TGraph2D", "Project").suggestions.put("option", new String[]{"x", "y", "xy", "yx"});
        def("TGraph2D::RemovePoint", (t, a) -> {
            final Graph2D g = graph2D(t);
            final int k = i(a, 0, 0);
            if (k < 0 || k >= g.x.length) throw new IllegalArgumentException("point " + k + " is not among the " + g.x.length);
            g.x = remove(g.x, k);
            g.y = remove(g.y, k);
            g.z = remove(g.z, k);
            return "point " + k + " removed";
        });
        for (String c : new String[]{"TGraph2D", "TGraph2DErrors", "TGraph2DAsymmErrors"}) {
            def(c + "::Scale", (t, a) -> {
                final Graph2D g = graph2D(t);
                final double c1 = d(a, 0, 1);
                final String o = s(a, 1, "z").toLowerCase(Locale.ROOT);
                if (o.contains("x")) for (int k = 0; k < g.x.length; k++) g.x[k] *= c1;
                if (o.contains("y")) for (int k = 0; k < g.y.length; k++) g.y[k] *= c1;
                if (o.contains("z")) for (int k = 0; k < g.z.length; k++) g.z[k] *= c1;
                return "scaled by " + c1 + " in " + o;
            });
            if (!c.equals("TGraph2D")) {
                def(c + "::RemovePoint", def("TGraph2D::RemovePoint").action);
            }
            find(c, "Scale").suggestions.put("option", new String[]{"z", "x", "y", "xy", "xyz"});
        }
        def("TGraph2D::SetMargin", (t, a) -> {
            graph2D(t).margin = Math.max(0, Math.min(1, d(a, 0, 0.1)));
            return "margin " + graph2D(t).margin;
        }).current = t -> new Object[]{graph2D(t).margin};
        def("TGraph2D::SetMarginBinsContent", (t, a) -> {
            graph2D(t).marginZ = d(a, 0, 0);
            return "outside the points, z = " + graph2D(t).marginZ;
        }).current = t -> new Object[]{graph2D(t).marginZ};
        def("TGraph2D::SetMaximum", (t, a) -> {
            graph2D(t).max = limit(d(a, 0, -1111));
            return "maximum set";
        }).current = t -> new Object[]{Double.isNaN(graph2D(t).max) ? -1111.0 : graph2D(t).max};
        def("TGraph2D::SetMinimum", (t, a) -> {
            graph2D(t).min = limit(d(a, 0, -1111));
            return "minimum set";
        }).current = t -> new Object[]{Double.isNaN(graph2D(t).min) ? -1111.0 : graph2D(t).min};
        def("TGraph2D::SetMaxIter", (t, a) -> {
            graph2D(t).maxIter = Math.max(1, i(a, 0, 100000));
            return "at most " + graph2D(t).maxIter + " iterations";
        }).current = t -> new Object[]{(long) graph2D(t).maxIter};
        def("TGraph2D::SetNpx", (t, a) -> {
            graph2D(t).npx = Math.max(4, i(a, 0, 40));
            return "interpolated on " + graph2D(t).npx + " points in x";
        }).current = t -> new Object[]{(long) graph2D(t).npx};
        def("TGraph2D::SetNpy", (t, a) -> {
            graph2D(t).npy = Math.max(4, i(a, 0, 40));
            return "interpolated on " + graph2D(t).npy + " points in y";
        }).current = t -> new Object[]{(long) graph2D(t).npy};
        def("TGraph2D::SetPoint", (t, a) -> {
            final Graph2D g = graph2D(t);
            final int k = i(a, 0, 0);
            if (k < 0) throw new IllegalArgumentException("points are counted from 0");
            if (k >= g.x.length) {
                g.x = Arrays.copyOf(g.x, k + 1);
                g.y = Arrays.copyOf(g.y, k + 1);
                g.z = Arrays.copyOf(g.z, k + 1);
            }
            g.x[k] = d(a, 1, 0);
            g.y[k] = d(a, 2, 0);
            g.z[k] = d(a, 3, 0);
            return "point " + k + " set";
        });
    }

    /**
     * A TGraph2D on its npx by npy grid, by Delaunay triangles as
     * TGraph2D::GetHistogram interpolates it, the margin added around the
     * points, and the margin's content outside the triangles.
     */
    static Hist interpolate(Graph2D g) {
        final int n = g.x.length;
        if (n < 3) throw new IllegalStateException("a TGraph2D needs 3 points to be interpolated");
        double x0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            x0 = Math.min(x0, g.x[k]);
            x1 = Math.max(x1, g.x[k]);
            y0 = Math.min(y0, g.y[k]);
            y1 = Math.max(y1, g.y[k]);
        }
        final double mx = g.margin * (x1 - x0);
        final double my = g.margin * (y1 - y0);
        final Axis ax = axis(g.npx, x0 - mx, x1 + mx, g.xTitle);
        final Axis ay = axis(g.npy, y0 - my, y1 + my, g.yTitle);
        final double[] nx = new double[n];
        final double[] ny = new double[n];
        for (int k = 0; k < n; k++) {
            nx[k] = (g.x[k] - x0) / Math.max(1e-300, x1 - x0);
            ny[k] = (g.y[k] - y0) / Math.max(1e-300, y1 - y0);
        }
        final List<int[]> triangles = RootPainter3D.Delaunay.triangulate(nx, ny);
        final double[] v = new double[g.npx * g.npy];
        for (int iy = 0; iy < g.npy; iy++) {
            for (int ix = 0; ix < g.npx; ix++) {
                final double px = (ax.center(ix) - x0) / Math.max(1e-300, x1 - x0);
                final double py = (ay.center(iy) - y0) / Math.max(1e-300, y1 - y0);
                double z = g.marginZ;
                for (int[] tri : triangles) {
                    final double ax1 = nx[tri[0]];
                    final double ay1 = ny[tri[0]];
                    final double bx = nx[tri[1]];
                    final double by = ny[tri[1]];
                    final double cx = nx[tri[2]];
                    final double cy = ny[tri[2]];
                    final double det = (by - cy) * (ax1 - cx) + (cx - bx) * (ay1 - cy);
                    if (det == 0) continue;
                    final double l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det;
                    final double l2 = ((cy - ay1) * (px - cx) + (ax1 - cx) * (py - cy)) / det;
                    final double l3 = 1 - l1 - l2;
                    if (l1 >= -1e-12 && l2 >= -1e-12 && l3 >= -1e-12) {
                        z = l1 * g.z[tri[0]] + l2 * g.z[tri[1]] + l3 * g.z[tri[2]];
                        break;
                    }
                }
                v[iy * g.npx + ix] = z;
            }
        }
        final Hist h = newHist2("TH2D", g.name + "_hist", g.title, ax, ay, v, null);
        h.zTitle = g.zTitle;
        h.stats = false;
        return h;
    }

    static Hist transpose(Hist h) {
        final double[] v = new double[h.v.length];
        final double[] e = new double[h.v.length];
        for (int iy = 0; iy < h.ny(); iy++) {
            for (int ix = 0; ix < h.nx(); ix++) {
                v[ix * h.ny() + iy] = h.v[iy * h.nx() + ix];
                e[ix * h.ny() + iy] = err(h, iy * h.nx() + ix);
            }
        }
        return newHist2("TH2D", h.name + "_t", h.title, copy(h.y), copy(h.x), v, e);
    }

    /* ------------------------------------------------------------------ */
    /* THStack                                                             */
    /* ------------------------------------------------------------------ */

    private static void registerGroups() {
        def("THStack::SetMaximum", (t, a) -> {
            ((Group) RootActions.item(t)).max = limit(d(a, 0, -1111));
            RootActions.view(t).yr = null;
            return "maximum set";
        }).current = t -> new Object[]{Double.isNaN(((Group) RootActions.item(t)).max) ? -1111.0 : ((Group) RootActions.item(t)).max};
        def("THStack::SetMinimum", (t, a) -> {
            ((Group) RootActions.item(t)).min = limit(d(a, 0, -1111));
            RootActions.view(t).yr = null;
            return "minimum set";
        }).current = t -> new Object[]{Double.isNaN(((Group) RootActions.item(t)).min) ? -1111.0 : ((Group) RootActions.item(t)).min};
    }

    /* ------------------------------------------------------------------ */
    /* TPolyMarker, TPolyLine3D, TPolyMarker3D                             */
    /* ------------------------------------------------------------------ */

    private static void registerPoints() {
        def("TPolyMarker::SetNextPoint", def("TPolyLine::SetNextPoint").action).current = t -> new Object[]{t.x, t.y};
        def("TPolyMarker::SetPoint", def("TPolyLine::SetPoint").action);
        for (String c : new String[]{"TPolyLine3D", "TPolyMarker3D"}) {
            def(c + "::SetNextPoint", (t, a) -> {
                final RootScene.Cloud3D cloud = (RootScene.Cloud3D) RootActions.item(t);
                final float[] p = Arrays.copyOf(cloud.p, cloud.p.length + 3);
                p[p.length - 3] = (float) d(a, 0, 0);
                p[p.length - 2] = (float) d(a, 1, 0);
                p[p.length - 1] = (float) d(a, 2, 0);
                cloud.p = p;
                return "point " + (cloud.size() - 1) + " added";
            });
            def(c + "::SetPoint", (t, a) -> {
                final RootScene.Cloud3D cloud = (RootScene.Cloud3D) RootActions.item(t);
                final int k = i(a, 0, 0);
                if (k < 0) throw new IllegalArgumentException("points are counted from 0");
                if (3 * k + 2 >= cloud.p.length) cloud.p = Arrays.copyOf(cloud.p, 3 * k + 3);
                cloud.p[3 * k] = (float) d(a, 1, 0);
                cloud.p[3 * k + 1] = (float) d(a, 2, 0);
                cloud.p[3 * k + 2] = (float) d(a, 3, 0);
                return "point " + k + " set";
            });
        }
        def("TPolyMarker3D::SetName", (t, a) -> {
            RootActions.item(t).name = s(a, 0, "");
            return "renamed";
        }).current = t -> new Object[]{RootActions.item(t).name};
    }

    /* ------------------------------------------------------------------ */
    /* TGeoVolume, TGeoManager on the meshes                               */
    /* ------------------------------------------------------------------ */

    static Geometry geometry(RootTarget t) {
        if (t.item() instanceof Geometry g) return g;
        for (Item i : t.pad.items) if (i instanceof Geometry g) return g;
        throw new IllegalStateException("no geometry in this pad");
    }

    private static void registerGeometry() {
        def("TGeoVolume::SetVisibility", (t, a) -> {
            final boolean on = b(a, 0, true);
            for (GeoMesh m : geometry(t).meshes) m.visible = on;
            return on ? "volumes shown" : "volumes hidden";
        }).state = t -> geometry(t).meshes.stream().anyMatch(m -> m.visible);
        def("TGeoVolume::InvisibleAll", (t, a) -> {
            final boolean off = b(a, 0, true);
            for (GeoMesh m : geometry(t).meshes) m.visible = !off;
            return off ? "every volume hidden" : "every volume shown";
        }).state = t -> geometry(t).meshes.stream().noneMatch(m -> m.visible);
        def("TGeoVolume::VisibleDaughters", (t, a) -> {
            final boolean on = b(a, 0, true);
            final List<GeoMesh> ms = geometry(t).meshes;
            for (int k = 1; k < ms.size(); k++) ms.get(k).visible = on;
            return on ? "daughters shown" : "daughters hidden";
        }).state = t -> geometry(t).meshes.size() > 1 && geometry(t).meshes.get(1).visible;
        def("TGeoVolume::SetVisOnly", (t, a) -> {
            final List<GeoMesh> ms = geometry(t).meshes;
            for (int k = 0; k < ms.size(); k++) ms.get(k).visible = k == 0 || !b(a, 0, true);
            return "only the top volume";
        }).state = t -> geometry(t).meshes.stream().skip(1).noneMatch(m -> m.visible);
        def("TGeoVolume::SetVisLeaves", (t, a) -> {
            final List<GeoMesh> ms = geometry(t).meshes;
            for (int k = 0; k < ms.size(); k++) ms.get(k).visible = !b(a, 0, true) || k > 0;
            return "the leaves of the tree shown";
        }).state = t -> !geometry(t).meshes.isEmpty() && !geometry(t).meshes.get(0).visible;
        def("TGeoVolume::SetVisContainers", (t, a) -> {
            for (GeoMesh m : geometry(t).meshes) m.visible = true;
            return "containers shown";
        }).state = t -> geometry(t).meshes.stream().allMatch(m -> m.visible);
        def("TGeoVolume::SetTransparency", (t, a) -> {
            final int tr = Math.max(0, Math.min(100, i(a, 0, 0)));
            for (GeoMesh m : geometry(t).meshes) m.transparency = tr;
            return "transparency " + tr + "%";
        }).current = t -> new Object[]{(long) (geometry(t).meshes.isEmpty() ? 0 : geometry(t).meshes.get(0).transparency)};
        def("TGeoVolume::ResetTransparency", (t, a) -> {
            final int tr = i(a, 0, -1);
            for (GeoMesh m : geometry(t).meshes) m.transparency = tr < 0 ? 0 : tr;
            return "transparency reset";
        });
        look("TGeoVolume::Print", (t, a) -> {
            t.canvas.host().show("Geometry " + geometry(t).name, describe(geometry(t)));
            return null;
        });
        look("TGeoVolume::InspectShape", (t, a) -> {
            t.canvas.host().show("Shapes of " + geometry(t).name, describe(geometry(t)));
            return null;
        });
        look("TGeoVolume::Draw", (t, a) -> openSpace(t, false));
        look("TGeoVolume::DrawOnly", (t, a) -> openSpace(t, true));
        look("TGeoVolume::GrabFocus", (t, a) -> openSpace(t, false));
        look("TGeoVolume::Raytrace", (t, a) -> openSpace(t, false));
        look("TGeoShape::Draw", (t, a) -> openSpace(t, false));

        def("TGeoManager::ClearAttributes", (t, a) -> {
            for (GeoMesh m : geometry(t).meshes) {
                m.visible = true;
                m.transparency = 0;
            }
            return "attributes cleared";
        });
        def("TGeoManager::DefaultColors", (t, a) -> {
            final List<GeoMesh> ms = geometry(t).meshes;
            for (int k = 0; k < ms.size(); k++) ms.get(k).color = RootColors.color(2 + k % 8);
            return "ROOT's default colours, by volume";
        });
        def("TGeoManager::SetVisLevel", (t, a) -> {
            final int level = Math.max(0, i(a, 0, 3));
            final List<GeoMesh> ms = geometry(t).meshes;
            for (int k = 0; k < ms.size(); k++) ms.get(k).visible = level == 0 || k < Math.max(1, level * 8);
            return "visible to level " + level;
        });
        def("TGeoManager::SetMaxVisNodes", (t, a) -> {
            final int max = Math.max(1, i(a, 0, 10000));
            final List<GeoMesh> ms = geometry(t).meshes;
            for (int k = 0; k < ms.size(); k++) ms.get(k).visible = k < max;
            return "at most " + max + " volumes shown";
        });
        def("TGeoManager::RestoreMasterVolume", (t, a) -> {
            for (GeoMesh m : geometry(t).meshes) m.visible = true;
            return "the whole geometry";
        });
        def("TGeoManager::ViewLeaves", (t, a) -> def("TGeoVolume::SetVisLeaves").action.run(t, a))
            .state = t -> !geometry(t).meshes.isEmpty() && !geometry(t).meshes.get(0).visible;
        look("TGeoManager::Edit", (t, a) -> openSpace(t, false));
    }

    static String describe(Geometry g) {
        final StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%s: %d volumes drawn, %d nodes%s%n%n", g.name, g.meshes.size(), g.nodes,
            g.cut ? " (cut at the limit of what is sent)" : ""));
        b.append(String.format(Locale.ROOT, "%-28s %-16s %8s %8s %6s %s%n", "volume", "shape", "vertices", "polygons",
            "transp", "visible"));
        for (GeoMesh m : g.meshes) {
            int polygons = 0;
            for (int k = 0; k < m.pol.length; k += m.pol[k] + 1) polygons++;
            b.append(String.format(Locale.ROOT, "%-28s %-16s %8d %8d %5d%% %s%n", m.name, m.shape, m.p.length / 3, polygons,
                m.transparency, m.visible ? "yes" : "no"));
        }
        return b.toString();
    }

    /** Draw: the geometry in Sphere's 3D space, which turns it, cuts it and measures it. */
    static String openSpace(RootTarget t, boolean only) {
        final Geometry g = geometry(t);
        final RootScene s = new RootScene();
        s.name = g.name;
        s.title = g.title;
        final Geometry copy = (Geometry) RootSceneJson.copy(g);
        if (only && copy.meshes.size() > 1) copy.meshes.subList(1, copy.meshes.size()).clear();
        s.pad.items.add(copy);
        s.pad.viewMin = t.pad.viewMin;
        s.pad.viewMax = t.pad.viewMax;
        t.canvas.host().open(s, g.name + " (3D)");
        return "geometry opened";
    }
}
