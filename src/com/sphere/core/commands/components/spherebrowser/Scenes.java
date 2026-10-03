package com.sphere.components.spherebrowser;

import com.sphere.components.rootview.RootAxis;
import com.sphere.components.rootview.RootGraph;
import com.sphere.components.rootview.RootGraph2D;
import com.sphere.components.rootview.RootHistogram;
import com.sphere.components.rootview.RootScene;
import com.sphere.components.rootview.RootScene.Axis;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import java.awt.Color;

/**
 * Scenes made here rather than read: an object of a .root file read without
 * ROOT, a result of the analysis, two pads one above the other.
 */
final class Scenes {

    private Scenes() {
    }

    /** One object alone on a canvas, as TBrowser draws what is double-clicked. */
    static RootScene single(Item item, String title) {
        final RootScene s = new RootScene();
        s.name = item.name;
        s.title = title == null ? item.title : title;
        s.width = 800;
        s.height = 560;
        s.pad = new Pad();
        s.pad.name = item.name;
        s.pad.title = s.title;
        s.pad.lm = 0.12;
        s.pad.rm = item instanceof Hist h && h.dim == 2 ? 0.14 : 0.05;
        s.pad.items.add(item);
        return s;
    }

    /** Two pads: the data with its fit above, a smaller panel below (pulls, a ratio). */
    static RootScene stacked(Item top, Item bottom, String title) {
        final RootScene s = new RootScene();
        s.name = title;
        s.title = title;
        s.width = 800;
        s.height = 720;
        final Pad canvas = new Pad();
        canvas.name = "canvas";
        final Pad upper = new Pad();
        upper.name = "upper";
        upper.px = 0;
        upper.py = 0.3;
        upper.pw = 1;
        upper.ph = 0.7;
        upper.lm = 0.12;
        upper.rm = 0.05;
        upper.bm = 0.02;
        upper.items.add(top);
        final Pad lower = new Pad();
        lower.name = "lower";
        lower.px = 0;
        lower.py = 0;
        lower.pw = 1;
        lower.ph = 0.3;
        lower.lm = 0.12;
        lower.rm = 0.05;
        lower.tm = 0.04;
        lower.bm = 0.3;
        lower.gridy = true;
        lower.items.add(bottom);
        canvas.pads.add(upper);
        canvas.pads.add(lower);
        s.pad = canvas;
        return s;
    }

    /* ------------------------------------------------------------------ */
    /* Objects of a .root file, read by Sphere's own reader                */
    /* ------------------------------------------------------------------ */

    static Hist hist(RootHistogram r) {
        final Hist h = new Hist();
        h.dim = Math.max(1, Math.min(3, r.dimensions));
        h.kind = "h" + h.dim;
        h.className = r.className;
        h.name = r.name;
        h.title = r.title;
        h.x = axis(r.xAxis);
        h.y = h.dim > 1 ? axis(r.yAxis) : null;
        h.z = h.dim > 2 ? axis(r.zAxis) : null;
        final int nx = h.nx();
        final int ny = h.dim > 1 ? h.y.n : 1;
        final int nz = h.dim > 2 ? h.z.n : 1;
        h.v = new double[nx * ny * nz];
        final boolean weights = r.sumw2.length == r.contents.length && r.sumw2.length > 0;
        if (h.dim == 1) h.err = new double[nx];
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    final int bin = h.dim == 1 ? i + 1
                        : h.dim == 2 ? (i + 1) + (nx + 2) * (j + 1)
                        : (i + 1) + (nx + 2) * ((j + 1) + (ny + 2) * (k + 1));
                    final double v = bin < r.contents.length ? r.contents[bin] : 0;
                    h.v[(k * ny + j) * nx + i] = v;
                    if (h.dim == 1) {
                        h.err[i] = weights ? Math.sqrt(Math.max(0, r.sumw2[bin])) : Math.sqrt(Math.abs(v));
                    }
                }
            }
        }
        h.entries = r.entries;
        h.stats = true;
        if (h.dim == 1) {
            h.mean = r.mean();
            h.std = r.stdDev();
            h.option = "HIST";
            h.line = new Color(0x2050A0);
            h.fill = new Color(0x9CC0EC);
            h.fillStyle = 1001;
        } else {
            h.option = h.dim == 2 ? "COLZ" : "BOX";
        }
        if (Double.isFinite(r.maximum) && r.maximum != -1111) h.max = r.maximum;
        if (Double.isFinite(r.minimum) && r.minimum != -1111) h.min = r.minimum;
        return h;
    }

    private static Axis axis(RootAxis a) {
        final Axis x = new Axis();
        x.n = a.bins;
        x.lo = a.min;
        x.hi = a.max;
        x.title = a.title == null ? "" : a.title;
        if (a.isVariable()) x.edges = a.edges.clone();
        return x;
    }

    static Graph graph(RootGraph r) {
        final Graph g = new Graph();
        g.kind = "g";
        g.className = r.className;
        g.name = r.name;
        g.title = r.title;
        g.x = r.x.clone();
        g.y = r.y.clone();
        if (r.hasErrors()) {
            g.exl = r.exLow.length == r.x.length ? r.exLow.clone() : null;
            g.exh = r.exHigh.length == r.x.length ? r.exHigh.clone() : g.exl;
            g.eyl = r.eyLow.length == r.x.length ? r.eyLow.clone() : null;
            g.eyh = r.eyHigh.length == r.x.length ? r.eyHigh.clone() : g.eyl;
        }
        g.option = "ALP";
        g.marker = new Color(0x2050A0);
        g.markerStyle = 20;
        g.line = new Color(0x2050A0);
        return g;
    }

    static Graph2D graph2d(RootGraph2D r) {
        final Graph2D g = new Graph2D();
        g.kind = "g2";
        g.className = r.className;
        g.name = r.name;
        g.title = r.title;
        g.x = r.x.clone();
        g.y = r.y.clone();
        g.z = r.z.clone();
        g.option = "TRI1";
        return g;
    }

    /** The first histogram of a pad, or of the canvas's pads, for the analysis. */
    static Hist firstHist(Pad pad) {
        if (pad == null) return null;
        for (Item i : pad.items) if (i instanceof Hist h && !h.isFunction()) return h;
        for (Item i : pad.items) {
            if (i instanceof RootScene.Group g) {
                for (Item m : g.items) if (m instanceof Hist h && !h.isFunction()) return h;
            }
        }
        for (Pad p : pad.pads) {
            final Hist h = firstHist(p);
            if (h != null) return h;
        }
        return null;
    }

    /** The pad holding an item, searching the pads below. */
    static Pad padOf(Pad pad, Item item) {
        if (pad.items.contains(item)) return pad;
        for (RootScene.Item i : pad.items) {
            if (i instanceof RootScene.Group g && g.items.contains(item)) return pad;
        }
        for (Pad p : pad.pads) {
            final Pad found = padOf(p, item);
            if (found != null) return found;
        }
        return null;
    }
}
