package com.sphere.components.spherebrowser;

import com.sphere.components.rootview.RootScene;
import com.sphere.components.rootview.RootScene.Cloud3D;
import com.sphere.components.rootview.RootScene.GeoMesh;
import com.sphere.components.rootview.RootScene.Geometry;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Turns what ROOT drew into meshes for the 3D space.
 *
 * Everything gets a third dimension, even what ROOT drew flat: a TH2 becomes
 * a lit surface with its colour map and contours on the floor, or a LEGO of
 * bars; a TH3 nested iso-surfaces (marching tetrahedra), voxels or a cloud
 * sampled from its contents; a TGraph2D a Delaunay surface through its
 * points; a pad of 1D histograms a waterfall, one behind the other; a
 * TPolyMarker3D the cloud it is; a TGeo geometry its volumes, which can be
 * pulled apart; and any picture, a PNG among them, a relief whose height is
 * its ink.
 */
public final class SceneBuilder3D {

    public enum Style {
        AUTO("Auto"), SURFACE("Surface"), LEGO("Lego bars"), POINTS("Point cloud"), ISO("Iso-surfaces"),
        VOXELS("Voxels"), WATERFALL("Waterfall"), RIBBON("Ribbons");

        final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What the user chose in the panel; the builder reads it, never changes it. */
    public static final class Options {
        public Style style = Style.AUTO;
        public String palette = "ROOT kBird";
        public boolean logZ;
        /** The iso level of a TH3, as a fraction of its maximum. */
        public double iso = 0.35;
        public boolean shells = true;
        /** Cells below this fraction of the maximum are left out of bars, voxels and clouds. */
        public double threshold = 0.0;
        public boolean floorMap = true;
        public boolean contours = true;
        public boolean markers = true;
        public int imageGrid = 240;
        public boolean invertImage = true;
        /** Read a colour map back into values when the picture's colours come from a known palette. */
        public boolean decodePalette = true;
        /** A picture's paper and ink re-inked for a dark theme, its data colours kept. */
        public boolean themePaper = com.sphere.components.imaging.ImagingTheme.isDark();
        public double reliefHeight = 0.22;
        /** How far a geometry's volumes are pulled from its centre, 0 none. */
        public double explode;
        public double opacity = 1;
        public long seed = 7;
    }

    private SceneBuilder3D() {
    }

    /* ------------------------------------------------------------------ */
    /* Entry points                                                        */
    /* ------------------------------------------------------------------ */

    /** A pad: its clouds and geometry together, a waterfall of its curves, or its main object. */
    public static Scene3D pad(Pad pad, RootScene scene, Options o) {
        if (pad == null) return empty("nothing to show");
        if (pad.holdsSpace()) return space(pad, o);
        final List<Item> curves = curves(pad);
        if (o.style == Style.WATERFALL && !curves.isEmpty() || o.style == Style.AUTO && curves.size() > 1) {
            return waterfall(curves, pad.title, o);
        }
        final Item main = pad.main();
        if (main == null) return empty("this pad draws nothing in three dimensions");
        return item(main, o);
    }

    /** The 1D histograms and graphs of a pad, members of stacks and multigraphs included. */
    static List<Item> curves(Pad pad) {
        final List<Item> out = new ArrayList<>();
        for (Item i : pad.items) {
            if (i instanceof Hist h && h.dim == 1 || i instanceof Graph) out.add(i);
            else if (i instanceof Group g) {
                for (Item m : g.items) if (m instanceof Hist h && h.dim == 1 || m instanceof Graph) out.add(m);
            }
        }
        return out;
    }

    public static Scene3D item(Item main, Options o) {
        if (main instanceof Hist h) {
            if (h.dim == 3) return hist3(h, o);
            if (h.dim == 2) return hist2(h, o);
            return o.style == Style.WATERFALL ? waterfall(List.of(h), h.title, o) : hist1(h, o);
        }
        if (main instanceof Graph2D g) return graph2d(g, o);
        if (main instanceof Graph g) return waterfall(List.of(g), g.title, o);
        if (main instanceof Group g) {
            final List<Item> members = new ArrayList<>();
            for (Item i : g.items) if (i instanceof Hist h && h.dim == 1 || i instanceof Graph) members.add(i);
            if (!members.isEmpty()) return waterfall(members, g.title, o);
        }
        if (main instanceof Cloud3D || main instanceof Geometry) {
            final Pad p = new Pad();
            p.items.add(main);
            return space(p, o);
        }
        return empty(main.className + " has no 3D form here");
    }

    static Scene3D empty(String why) {
        final Scene3D s = new Scene3D();
        s.title = why;
        s.notes.add(why);
        return s;
    }

    /* ------------------------------------------------------------------ */
    /* Values and colours                                                  */
    /* ------------------------------------------------------------------ */

    private static double zOf(double v, boolean log, double floor) {
        if (!log) return v;
        return Math.log10(Math.max(v, floor));
    }

    /** The range of the contents shown: positive part in log, all in linear. */
    private static double[] range(double[] v, boolean log) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        double minPos = Double.MAX_VALUE;
        for (double x : v) {
            if (!Double.isFinite(x)) continue;
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
            if (x > 0) minPos = Math.min(minPos, x);
        }
        if (lo > hi) return new double[]{0, 1, 1e-3};
        if (log) {
            if (minPos == Double.MAX_VALUE) minPos = 1e-3;
            final double floor = minPos * 0.5;
            return new double[]{Math.log10(floor), Math.log10(Math.max(hi, minPos)), floor};
        }
        if (lo > 0) lo = 0;
        if (hi == lo) hi = lo + 1;
        return new double[]{lo, hi, 0};
    }

    /** Where a height falls on the colour scale: on its own range when one was given (r[3], r[4]). */
    private static double cf(double z, double[] r) {
        return r.length > 4 && r[4] > r[3] ? (z - r[3]) / (r[4] - r[3]) : (z - r[0]) / (r[1] - r[0]);
    }

    private static int argb(Color c, int fallback) {
        return c == null ? fallback : 0xFF000000 | c.getRGB();
    }

    /* ------------------------------------------------------------------ */
    /* TH1                                                                 */
    /* ------------------------------------------------------------------ */

    private static Scene3D hist1(Hist h, Options o) {
        final Scene3D s = new Scene3D();
        s.title = title(h);
        s.axisTitle[0] = h.x.title;
        s.axisTitle[1] = "";
        s.axisTitle[2] = h.yTitle == null || h.yTitle.isBlank() ? "entries" : h.yTitle;
        s.log[2] = o.logZ;
        final double[] r = range(h.v, o.logZ);
        final Color[] pal = Palettes.get(o.palette);
        s.palette = pal;
        s.valueLo = r[0];
        s.valueHi = r[1];
        final int n = h.nx();
        if (h.isFunction() || o.style == Style.RIBBON || o.style == Style.SURFACE) {
            ribbon(s.mesh(h.name), h, 0, 0.9, r, pal, o, -1);
        } else {
            final Mesh3D bars = s.mesh(h.name);
            bars.opacity = (float) o.opacity;
            final int fixed = h.fill != null && h.fillStyle > 0 ? argb(h.fill, 0) : 0;
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                if (!Double.isFinite(v) || v == 0 && !o.logZ) continue;
                final double z = zOf(v, o.logZ, r[2]);
                final double frac = (z - r[0]) / (r[1] - r[0]);
                if (frac < o.threshold) continue;
                final int c = o.style == Style.LEGO || fixed == 0 ? Palettes.at(pal, frac) : fixed;
                final double x0 = h.x.edge(i);
                final double x1 = h.x.edge(i + 1);
                final double gap = (x1 - x0) * 0.06;
                bars.box(x0 + gap, -0.42, r[0], x1 - gap, 0.42, z, c, v);
            }
            // The errors, as whiskers standing on each bar.
            final Mesh3D whiskers = s.mesh("errors");
            whiskers.lineWidth = 1.5f;
            final int wc = 0xFFE0E0E0;
            for (int i = 0; i < n; i++) {
                final double v = h.at(i, 0);
                final double e = h.error(i);
                if (v == 0 || e <= 0 || !Double.isFinite(e)) continue;
                final double x = h.x.center(i);
                final double a = zOf(v - e, o.logZ, r[2]);
                final double b = zOf(v + e, o.logZ, r[2]);
                whiskers.line(whiskers.vertex(x, 0, Math.max(r[0], a), wc, v - e), whiskers.vertex(x, 0, b, wc, v + e));
            }
        }
        for (Hist fit : h.fits) ribbon(s.mesh("fit " + fit.name), fit, -0.75, 0.12, r, pal, o, argb(fit.line, 0xFFFF4040));
        s.box = new double[]{h.x.lo, h.x.hi, -1, 1, r[0], r[1]};
        s.notes.add(n + " bins");
        return s;
    }

    /** A curve as a band of some depth at y, lit; coloured by the palette or in one colour. */
    private static void ribbon(Mesh3D m, Hist h, double y, double depth, double[] r, Color[] pal, Options o, int fixed) {
        m.smooth = true;
        final int n = h.nx();
        int prevA = -1;
        int prevB = -1;
        for (int i = 0; i < n; i++) {
            final double v = h.at(i, 0);
            final double z = zOf(v, o.logZ, r[2]);
            final double frac = (z - r[0]) / (r[1] - r[0]);
            final int c = fixed != -1 && fixed != 0 ? fixed : Palettes.at(pal, frac);
            final double x = h.x.center(i);
            final int a = m.vertex(x, y - depth / 2, z, c, v);
            final int b = m.vertex(x, y + depth / 2, z, c, v);
            if (prevA >= 0) m.quad(prevA, a, b, prevB);
            prevA = a;
            prevB = b;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Waterfall: 1D histograms and graphs one behind the other            */
    /* ------------------------------------------------------------------ */

    private static Scene3D waterfall(List<Item> curves, String title, Options o) {
        final Scene3D s = new Scene3D();
        s.title = title == null || title.isBlank() ? "waterfall" : title;
        s.axisTitle[1] = curves.size() > 1 ? "series" : "";
        s.log[2] = o.logZ;
        final Color[] pal = Palettes.get(o.palette);
        double xlo = Double.MAX_VALUE;
        double xhi = -Double.MAX_VALUE;
        final List<Double> all = new ArrayList<>();
        for (Item c : curves) {
            if (c instanceof Hist h) {
                xlo = Math.min(xlo, h.x.lo);
                xhi = Math.max(xhi, h.x.hi);
                for (double v : h.v) all.add(v);
                if (s.axisTitle[0].equals("x")) s.axisTitle[0] = h.x.title;
            } else if (c instanceof Graph g) {
                for (int i = 0; i < g.x.length; i++) {
                    xlo = Math.min(xlo, g.x[i]);
                    xhi = Math.max(xhi, g.x[i]);
                    all.add(g.y[i]);
                }
                if (s.axisTitle[0].equals("x")) s.axisTitle[0] = g.xTitle;
            }
        }
        final double[] r = range(all.stream().mapToDouble(Double::doubleValue).toArray(), o.logZ);
        s.palette = pal;
        s.valueLo = r[0];
        s.valueHi = r[1];
        final int k = curves.size();
        for (int idx = 0; idx < k; idx++) {
            final Item c = curves.get(idx);
            final double y = idx;
            final int series = k > 1 ? Palettes.at(pal, idx / (double) Math.max(1, k - 1))
                : argb(c.line != null ? c.line : c.fill, Palettes.at(pal, 0.6));
            final int own = c.fill != null && c.fillStyle > 0 ? argb(c.fill, series)
                : c.line != null && k > 1 && !c.line.equals(Color.BLACK) ? argb(c.line, series) : series;
            final Mesh3D m = s.mesh(c.name);
            m.opacity = (float) o.opacity;
            if (c instanceof Hist h) {
                final boolean bars = !h.isFunction() && h.nx() <= 400 && o.style != Style.RIBBON;
                for (int i = 0; i < h.nx(); i++) {
                    final double v = h.at(i, 0);
                    final double z = zOf(v, o.logZ, r[2]);
                    if (bars) {
                        if (v == 0 && !o.logZ) continue;
                        final double frac = (z - r[0]) / (r[1] - r[0]);
                        if (frac < o.threshold) continue;
                        final double x0 = h.x.edge(i);
                        final double x1 = h.x.edge(i + 1);
                        m.box(x0, y - 0.38, r[0], x1, y + 0.38, z, o.style == Style.LEGO ? Palettes.at(pal, frac) : own, v);
                    }
                }
                if (!bars) ribbon(m, h, y, 0.76, r, pal, o, own);
            } else if (c instanceof Graph g) {
                m.smooth = true;
                int pa = -1;
                int pb = -1;
                final Mesh3D dots = s.mesh(c.name + " points");
                dots.pointSize = 6;
                for (int i = 0; i < g.x.length; i++) {
                    final double z = zOf(g.y[i], o.logZ, r[2]);
                    final int a = m.vertex(g.x[i], y - 0.3, z, own, g.y[i]);
                    final int b = m.vertex(g.x[i], y + 0.3, z, own, g.y[i]);
                    if (pa >= 0) m.quad(pa, a, b, pb);
                    pa = a;
                    pb = b;
                    if (o.markers) dots.point(dots.vertex(g.x[i], y, z, 0xFFFFFFFF, g.y[i]));
                }
            }
        }
        if (!(xhi > xlo)) {
            xlo = 0;
            xhi = 1;
        }
        s.box = new double[]{xlo, xhi, -0.6, Math.max(0.6, k - 0.4), r[0], r[1]};
        s.notes.add(k + (k == 1 ? " curve" : " curves, one behind the other"));
        return s;
    }

    /* ------------------------------------------------------------------ */
    /* TH2                                                                 */
    /* ------------------------------------------------------------------ */

    private static Scene3D hist2(Hist h, Options o) {
        final Scene3D s = new Scene3D();
        s.title = title(h);
        s.axisTitle[0] = h.x.title;
        s.axisTitle[1] = h.y.title;
        s.axisTitle[2] = h.zTitle == null || h.zTitle.isBlank() ? (h.isFunction() ? "f" : "entries") : h.zTitle;
        s.log[2] = o.logZ;
        final Color[] pal = Palettes.get(o.palette);
        final double[] r0 = range(h.v, o.logZ);
        // The colours span the contents from their minimum, as COLZ does; the bars stand on the floor.
        double cLo = Double.MAX_VALUE;
        for (double v : h.v) if (Double.isFinite(v) && (!o.logZ || v > 0)) cLo = Math.min(cLo, zOf(v, o.logZ, r0[2]));
        if (!(cLo < r0[1])) cLo = r0[0];
        final double[] r = {r0[0], r0[1], r0[2], cLo, r0[1]};
        s.palette = pal;
        s.valueLo = cLo;
        s.valueHi = r[1];
        final int nx = h.nx();
        final int ny = h.ny();
        final String opt = h.opt();
        Style style = o.style;
        if (style == Style.AUTO) {
            // Few bins read best as bars, many as a surface; a function is always a surface.
            style = opt.contains("LEGO") || opt.contains("BOX") && !h.isFunction() ? Style.LEGO
                : !h.isFunction() && !opt.contains("SURF") && nx * ny <= 1600 ? Style.LEGO : Style.SURFACE;
        }
        if (style == Style.LEGO) {
            final Mesh3D bars = s.mesh(h.name);
            bars.opacity = (float) o.opacity;
            int count = 0;
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    final double v = h.at(i, j);
                    if (!Double.isFinite(v) || v == 0 && !o.logZ) continue;
                    final double z = zOf(v, o.logZ, r[2]);
                    final double frac = (z - r[0]) / (r[1] - r[0]);
                    if (frac < o.threshold || frac <= 0 && o.logZ) continue;
                    final double x0 = h.x.edge(i);
                    final double x1 = h.x.edge(i + 1);
                    final double y0 = h.y.edge(j);
                    final double y1 = h.y.edge(j + 1);
                    final double gx = (x1 - x0) * 0.04;
                    final double gy = (y1 - y0) * 0.04;
                    bars.box(x0 + gx, y0 + gy, r[0], x1 - gx, y1 - gy, z, Palettes.at(pal, cf(z, r)), v);
                    count++;
                }
            }
            s.notes.add(count + " bars of " + nx + " x " + ny + " bins");
        } else if (style == Style.POINTS) {
            scatter2(s, h, r, pal, o);
        } else {
            surface(s, h, r, pal, o);
        }
        if (o.floorMap && style != Style.POINTS) floor(s, h, r, pal, o);
        s.box = new double[]{h.x.lo, h.x.hi, h.y.lo, h.y.hi, r[0], r[1]};
        return s;
    }

    /** A smooth surface through the bin centres, coloured by height. */
    private static void surface(Scene3D s, Hist h, double[] r, Color[] pal, Options o) {
        final int nx = h.nx();
        final int ny = h.ny();
        final int step = Math.max(1, (int) Math.ceil(Math.sqrt(nx * (double) ny / 250000.0)));
        final Mesh3D m = s.mesh(h.name);
        m.smooth = true;
        m.opacity = (float) o.opacity;
        final int gx = (nx + step - 1) / step;
        final int gy = (ny + step - 1) / step;
        final int[] idx = new int[gx * gy];
        for (int j = 0; j < gy; j++) {
            for (int i = 0; i < gx; i++) {
                double sum = 0;
                int count = 0;
                for (int dj = 0; dj < step && j * step + dj < ny; dj++) {
                    for (int di = 0; di < step && i * step + di < nx; di++) {
                        sum += h.at(i * step + di, j * step + dj);
                        count++;
                    }
                }
                final double v = sum / Math.max(1, count);
                final double z = zOf(v, o.logZ, r[2]);
                final double frac = (z - r[0]) / (r[1] - r[0]);
                final double x = 0.5 * (h.x.edge(i * step) + h.x.edge(Math.min(nx, (i + 1) * step)));
                final double y = 0.5 * (h.y.edge(j * step) + h.y.edge(Math.min(ny, (j + 1) * step)));
                idx[j * gx + i] = m.vertex(x, y, Math.max(r[0], z), Palettes.at(pal, cf(z, r)), v);
            }
        }
        for (int j = 0; j + 1 < gy; j++) {
            for (int i = 0; i + 1 < gx; i++) {
                m.quad(idx[j * gx + i], idx[j * gx + i + 1], idx[(j + 1) * gx + i + 1], idx[(j + 1) * gx + i]);
            }
        }
        if (o.contours) {
            final Mesh3D lines = s.mesh("contours");
            lines.lineWidth = 1.2f;
            final double[][] grid = new double[gy][gx];
            for (int j = 0; j < gy; j++) {
                for (int i = 0; i < gx; i++) grid[j][i] = m.pos[3 * idx[j * gx + i] + 2];
            }
            final double[] xs = new double[gx];
            final double[] ys = new double[gy];
            for (int i = 0; i < gx; i++) xs[i] = m.pos[3 * idx[i]];
            for (int j = 0; j < gy; j++) ys[j] = m.pos[3 * idx[j * gx] + 1];
            for (int l = 1; l <= 9; l++) {
                final double level = r[0] + (r[1] - r[0]) * l / 10.0;
                contour(lines, grid, xs, ys, level, level + (r[1] - r[0]) * 0.002, 0xC0101418);
            }
        }
        s.notes.add(gx + " x " + gy + " surface" + (step > 1 ? " (" + step + "x" + step + " bins merged)" : ""));
    }

    /** The colour map and the contours laid on the floor of the box, as COLZ and CONT would be. */
    private static void floor(Scene3D s, Hist h, double[] r, Color[] pal, Options o) {
        final int nx = h.nx();
        final int ny = h.ny();
        if (nx * (long) ny > 400000) return;
        final Mesh3D f = s.mesh("floor map");
        f.lit = false;
        final double z0 = r[0];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                final double v = h.at(i, j);
                if (v == 0 && !h.isFunction()) continue;
                final double frac = (zOf(v, o.logZ, r[2]) - r[0]) / (r[1] - r[0]);
                final int c = Palettes.at(pal, cf(zOf(v, o.logZ, r[2]), r));
                final int a = f.vertex(h.x.edge(i), h.y.edge(j), z0, c, v);
                final int b = f.vertex(h.x.edge(i + 1), h.y.edge(j), z0, c, v);
                final int d = f.vertex(h.x.edge(i + 1), h.y.edge(j + 1), z0, c, v);
                final int e = f.vertex(h.x.edge(i), h.y.edge(j + 1), z0, c, v);
                f.quad(a, b, d, e);
            }
        }
        if (o.contours && nx >= 2 && ny >= 2) {
            final double[][] grid = new double[ny][nx];
            final double[] xs = new double[nx];
            final double[] ys = new double[ny];
            for (int i = 0; i < nx; i++) xs[i] = h.x.center(i);
            for (int j = 0; j < ny; j++) {
                ys[j] = h.y.center(j);
                for (int i = 0; i < nx; i++) grid[j][i] = zOf(h.at(i, j), o.logZ, r[2]);
            }
            final Mesh3D lines = s.mesh("floor contours");
            for (int l = 1; l <= 9; l++) {
                final double level = r[0] + (r[1] - r[0]) * l / 10.0;
                contour(lines, grid, xs, ys, level, z0 + (r[1] - r[0]) * 0.003, 0xE0FFFFFF);
            }
        }
    }

    /** Marching squares: the segments where the grid crosses a level, laid at height z. */
    static void contour(Mesh3D m, double[][] g, double[] xs, double[] ys, double level, double z, int color) {
        final int ny = g.length;
        final int nx = ny == 0 ? 0 : g[0].length;
        for (int j = 0; j + 1 < ny; j++) {
            for (int i = 0; i + 1 < nx; i++) {
                final double a = g[j][i];
                final double b = g[j][i + 1];
                final double c = g[j + 1][i + 1];
                final double d = g[j + 1][i];
                final List<double[]> cut = new ArrayList<>(4);
                cross(cut, a, b, level, xs[i], ys[j], xs[i + 1], ys[j]);
                cross(cut, b, c, level, xs[i + 1], ys[j], xs[i + 1], ys[j + 1]);
                cross(cut, c, d, level, xs[i + 1], ys[j + 1], xs[i], ys[j + 1]);
                cross(cut, d, a, level, xs[i], ys[j + 1], xs[i], ys[j]);
                for (int k = 0; k + 1 < cut.size(); k += 2) {
                    m.line(m.vertex(cut.get(k)[0], cut.get(k)[1], z, color, level),
                        m.vertex(cut.get(k + 1)[0], cut.get(k + 1)[1], z, color, level));
                }
            }
        }
    }

    private static void cross(List<double[]> out, double va, double vb, double level, double xa, double ya,
                              double xb, double yb) {
        if ((va < level) == (vb < level) || !Double.isFinite(va) || !Double.isFinite(vb)) return;
        final double t = (level - va) / (vb - va);
        out.add(new double[]{xa + t * (xb - xa), ya + t * (yb - ya)});
    }

    /** Points drawn in each bin as many as its content says, as SCAT does, in depth. */
    private static void scatter2(Scene3D s, Hist h, double[] r, Color[] pal, Options o) {
        final Mesh3D m = s.mesh(h.name);
        m.pointSize = 2.2f;
        double total = 0;
        for (double v : h.v) if (v > 0) total += v;
        final double per = Math.max(1, total / 120000);
        final SplittableRandom rnd = new SplittableRandom(o.seed);
        for (int j = 0; j < h.ny(); j++) {
            for (int i = 0; i < h.nx(); i++) {
                final double v = h.at(i, j);
                if (!(v > 0)) continue;
                final int count = (int) Math.round(v / per);
                final double frac = (zOf(v, o.logZ, r[2]) - r[0]) / (r[1] - r[0]);
                if (frac < o.threshold) continue;
                for (int k = 0; k < count; k++) {
                    final double x = h.x.edge(i) + rnd.nextDouble() * (h.x.edge(i + 1) - h.x.edge(i));
                    final double y = h.y.edge(j) + rnd.nextDouble() * (h.y.edge(j + 1) - h.y.edge(j));
                    final double z = r[0] + rnd.nextDouble() * (zOf(v, o.logZ, r[2]) - r[0]);
                    m.point(m.vertex(x, y, z, Palettes.at(pal, cf(zOf(v, o.logZ, r[2]), r)), v));
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* TH3                                                                 */
    /* ------------------------------------------------------------------ */

    private static Scene3D hist3(Hist h, Options o) {
        final Scene3D s = new Scene3D();
        s.title = title(h);
        s.axisTitle[0] = h.x.title;
        s.axisTitle[1] = h.y.title;
        s.axisTitle[2] = h.z.title;
        final Color[] pal = Palettes.get(o.palette);
        s.palette = pal;
        double max = 0;
        for (double v : h.v) if (Double.isFinite(v)) max = Math.max(max, v);
        if (max <= 0) max = 1;
        s.valueLo = 0;
        s.valueHi = max;
        s.valueTitle = "content";
        final int nx = h.nx();
        final int ny = h.ny();
        final int nz = h.nz();
        Style style = o.style == Style.AUTO ? (h.opt().contains("ISO") ? Style.ISO : Style.VOXELS) : o.style;
        if (style == Style.SURFACE || style == Style.LEGO || style == Style.WATERFALL || style == Style.RIBBON) {
            style = Style.VOXELS;
        }
        if (style == Style.ISO && (long) nx * ny * nz <= 3_000_000L) {
            final double[] levels = o.shells ? new double[]{o.iso * 0.3, o.iso * 0.6, o.iso} : new double[]{o.iso};
            final float[] opacities = o.shells ? new float[]{0.16f, 0.32f, 1f} : new float[]{1f};
            for (int k = 0; k < levels.length; k++) {
                final double level = levels[k] * max;
                final Mesh3D m = s.mesh(String.format(Locale.ROOT, "iso %.3g", level));
                m.opacity = (float) Math.min(opacities[k], o.opacity);
                isoSurface(m, h, level, Palettes.at(pal, levels[k]));
                s.notes.add(String.format(Locale.ROOT, "iso-surface at %.3g: %d triangles", level, m.triangleCount()));
            }
        } else if (style == Style.POINTS) {
            final Mesh3D m = s.mesh(h.name);
            m.pointSize = 2.2f;
            double total = 0;
            for (double v : h.v) if (v > 0) total += v;
            final double per = Math.max(1, total / 150000);
            final SplittableRandom rnd = new SplittableRandom(o.seed);
            for (int k = 0; k < nz; k++) {
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        final double v = h.at(i, j, k);
                        if (!(v > 0) || v / max < o.threshold) continue;
                        final int count = (int) Math.round(v / per);
                        final int c = Palettes.at(pal, v / max);
                        for (int q = 0; q < count; q++) {
                            m.point(m.vertex(
                                h.x.edge(i) + rnd.nextDouble() * (h.x.edge(i + 1) - h.x.edge(i)),
                                h.y.edge(j) + rnd.nextDouble() * (h.y.edge(j + 1) - h.y.edge(j)),
                                h.z.edge(k) + rnd.nextDouble() * (h.z.edge(k + 1) - h.z.edge(k)), c, v));
                        }
                    }
                }
            }
            s.notes.add(m.pointCount() + " points sampled from the contents");
        } else {
            // Voxels: a cube per cell, its side the cube root of its share, as BOX does.
            final Mesh3D m = s.mesh(h.name);
            m.opacity = (float) o.opacity;
            final double floor = Math.max(o.threshold, 0.002);
            final List<int[]> cells = new ArrayList<>();
            for (int k = 0; k < nz; k++) {
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        final double v = h.at(i, j, k);
                        if (v / max >= floor) cells.add(new int[]{i, j, k});
                    }
                }
            }
            if (cells.size() > 60000) {
                cells.sort((a, b) -> Double.compare(h.at(b[0], b[1], b[2]), h.at(a[0], a[1], a[2])));
                s.notes.add("the 60000 fullest of " + cells.size() + " cells");
                cells.subList(60000, cells.size()).clear();
            }
            for (int[] c : cells) {
                final double v = h.at(c[0], c[1], c[2]);
                final double side = 0.5 * Math.cbrt(v / max) * 0.96;
                final double cx = h.x.center(c[0]);
                final double cy = h.y.center(c[1]);
                final double cz = h.z.center(c[2]);
                final double wx = (h.x.edge(c[0] + 1) - h.x.edge(c[0])) * side;
                final double wy = (h.y.edge(c[1] + 1) - h.y.edge(c[1])) * side;
                final double wz = (h.z.edge(c[2] + 1) - h.z.edge(c[2])) * side;
                m.box(cx - wx, cy - wy, cz - wz, cx + wx, cy + wy, cz + wz, Palettes.at(pal, v / max), v);
            }
            s.notes.add(cells.size() + " voxels");
        }
        s.box = new double[]{h.x.lo, h.x.hi, h.y.lo, h.y.hi, h.z.lo, h.z.hi};
        return s;
    }

    private static final int[][] CORNER = {
        {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}};
    private static final int[][] TETRA = {{0, 5, 1, 6}, {0, 1, 2, 6}, {0, 2, 3, 6}, {0, 3, 7, 6}, {0, 7, 4, 6}, {0, 4, 5, 6}};

    /**
     * Marching tetrahedra through the bin centres: each cell cut into six
     * tetrahedra, each crossing the level in one triangle or two. The normals
     * are the field's gradient, so the surface is smooth however coarse the
     * binning.
     */
    static void isoSurface(Mesh3D m, Hist h, double level, int color) {
        final int nx = h.nx();
        final int ny = h.ny();
        final int nz = h.nz();
        if (nx < 2 || ny < 2 || nz < 2) return;
        final double[] cx = new double[nx];
        final double[] cy = new double[ny];
        final double[] cz = new double[nz];
        for (int i = 0; i < nx; i++) cx[i] = h.x.center(i);
        for (int j = 0; j < ny; j++) cy[j] = h.y.center(j);
        for (int k = 0; k < nz; k++) cz[k] = h.z.center(k);
        final double[] p = new double[12];
        final double[] v = new double[4];
        final double[] gr = new double[12];
        for (int k = 0; k + 1 < nz; k++) {
            for (int j = 0; j + 1 < ny; j++) {
                for (int i = 0; i + 1 < nx; i++) {
                    // A quick test: does the level pass through this cell at all?
                    boolean above = false;
                    boolean below = false;
                    for (int[] c : CORNER) {
                        if (h.at(i + c[0], j + c[1], k + c[2]) >= level) above = true;
                        else below = true;
                    }
                    if (!above || !below) continue;
                    for (int[] t : TETRA) {
                        for (int q = 0; q < 4; q++) {
                            final int[] c = CORNER[t[q]];
                            final int ii = i + c[0];
                            final int jj = j + c[1];
                            final int kk = k + c[2];
                            p[3 * q] = cx[ii];
                            p[3 * q + 1] = cy[jj];
                            p[3 * q + 2] = cz[kk];
                            v[q] = h.at(ii, jj, kk);
                            gradient(h, ii, jj, kk, cx, cy, cz, gr, 3 * q);
                        }
                        tetra(m, p, v, gr, level, color);
                    }
                }
            }
        }
    }

    private static void gradient(Hist h, int i, int j, int k, double[] cx, double[] cy, double[] cz, double[] out, int o) {
        final int nx = cx.length;
        final int ny = cy.length;
        final int nz = cz.length;
        final int i0 = Math.max(0, i - 1);
        final int i1 = Math.min(nx - 1, i + 1);
        final int j0 = Math.max(0, j - 1);
        final int j1 = Math.min(ny - 1, j + 1);
        final int k0 = Math.max(0, k - 1);
        final int k1 = Math.min(nz - 1, k + 1);
        out[o] = (h.at(i1, j, k) - h.at(i0, j, k)) / Math.max(1e-300, cx[i1] - cx[i0]);
        out[o + 1] = (h.at(i, j1, k) - h.at(i, j0, k)) / Math.max(1e-300, cy[j1] - cy[j0]);
        out[o + 2] = (h.at(i, j, k1) - h.at(i, j, k0)) / Math.max(1e-300, cz[k1] - cz[k0]);
    }

    private static void tetra(Mesh3D m, double[] p, double[] v, double[] g, double level, int color) {
        int inside = 0;
        int mask = 0;
        for (int q = 0; q < 4; q++) {
            if (v[q] >= level) {
                inside++;
                mask |= 1 << q;
            }
        }
        if (inside == 0 || inside == 4) return;
        if (inside == 1 || inside == 3) {
            final boolean single = inside == 1;
            int a = 0;
            for (int q = 0; q < 4; q++) if (((mask >> q & 1) == 1) == single) a = q;
            final int[] e = new int[3];
            int n = 0;
            for (int q = 0; q < 4; q++) if (q != a) e[n++] = edge(m, p, v, g, a, q, level, color);
            m.triangle(e[0], e[1], e[2]);
        } else {
            final int[] in = new int[2];
            final int[] out = new int[2];
            int ni = 0;
            int no = 0;
            for (int q = 0; q < 4; q++) {
                if ((mask >> q & 1) == 1) in[ni++] = q;
                else out[no++] = q;
            }
            final int a = edge(m, p, v, g, in[0], out[0], level, color);
            final int b = edge(m, p, v, g, in[0], out[1], level, color);
            final int c = edge(m, p, v, g, in[1], out[1], level, color);
            final int d = edge(m, p, v, g, in[1], out[0], level, color);
            m.quad(a, b, c, d);
        }
    }

    private static int edge(Mesh3D m, double[] p, double[] v, double[] g, int a, int b, double level, int color) {
        final double d = v[b] - v[a];
        final double t = Math.abs(d) < 1e-300 ? 0.5 : (level - v[a]) / d;
        final int i = m.vertex(p[3 * a] + t * (p[3 * b] - p[3 * a]), p[3 * a + 1] + t * (p[3 * b + 1] - p[3 * a + 1]),
            p[3 * a + 2] + t * (p[3 * b + 2] - p[3 * a + 2]), color, level);
        final double gx = g[3 * a] + t * (g[3 * b] - g[3 * a]);
        final double gy = g[3 * a + 1] + t * (g[3 * b + 1] - g[3 * a + 1]);
        final double gz = g[3 * a + 2] + t * (g[3 * b + 2] - g[3 * a + 2]);
        m.normal(i, -gx, -gy, -gz);
        return i;
    }

    /* ------------------------------------------------------------------ */
    /* TGraph2D                                                            */
    /* ------------------------------------------------------------------ */

    private static Scene3D graph2d(Graph2D g, Options o) {
        final Scene3D s = new Scene3D();
        s.title = g.title == null || g.title.isBlank() ? g.name : g.title;
        s.axisTitle[0] = g.xTitle;
        s.axisTitle[1] = g.yTitle;
        s.axisTitle[2] = g.zTitle;
        final Color[] pal = Palettes.get(o.palette);
        final double[] r = range(g.z, o.logZ);
        s.palette = pal;
        s.valueLo = r[0];
        s.valueHi = r[1];
        s.log[2] = o.logZ;
        final int n = Math.min(g.x.length, Math.min(g.y.length, g.z.length));
        final int stride = Math.max(1, (int) Math.ceil(n / 4000.0));
        final List<Integer> used = new ArrayList<>();
        for (int i = 0; i < n; i += stride) used.add(i);
        if (stride > 1) s.notes.add("triangulated through 1 point in " + stride);
        final double[] px = new double[used.size()];
        final double[] py = new double[used.size()];
        for (int k = 0; k < px.length; k++) {
            px[k] = g.x[used.get(k)];
            py[k] = g.y[used.get(k)];
        }
        if (o.style != Style.POINTS && px.length >= 3) {
            final int[] tris = Delaunay.triangulate(px, py);
            final Mesh3D m = s.mesh(g.name);
            m.smooth = true;
            m.opacity = (float) o.opacity;
            final int[] idx = new int[px.length];
            for (int k = 0; k < px.length; k++) {
                final double v = g.z[used.get(k)];
                final double z = zOf(v, o.logZ, r[2]);
                idx[k] = m.vertex(px[k], py[k], z, Palettes.at(pal, (z - r[0]) / (r[1] - r[0])), v);
            }
            for (int t = 0; t + 2 < tris.length; t += 3) m.triangle(idx[tris[t]], idx[tris[t + 1]], idx[tris[t + 2]]);
            s.notes.add(tris.length / 3 + " Delaunay triangles");
        }
        if (o.markers || o.style == Style.POINTS) {
            final Mesh3D dots = s.mesh(g.name + " points");
            dots.pointSize = n > 5000 ? 2.5f : 5f;
            for (int i = 0; i < n; i++) {
                final double z = zOf(g.z[i], o.logZ, r[2]);
                final int c = o.style == Style.POINTS ? Palettes.at(pal, (z - r[0]) / (r[1] - r[0]))
                    : argb(g.marker, 0xFF202020);
                dots.point(dots.vertex(g.x[i], g.y[i], z, c, g.z[i]));
            }
        }
        return s;
    }

    /* ------------------------------------------------------------------ */
    /* Clouds and geometry: the pad's TView, or the shapes' own box        */
    /* ------------------------------------------------------------------ */

    private static Scene3D space(Pad pad, Options o) {
        final Scene3D s = new Scene3D();
        s.title = pad.title == null || pad.title.isBlank() ? pad.name : pad.title;
        boolean geometry = false;
        for (Item i : pad.items) {
            if (i instanceof Cloud3D c) {
                final Mesh3D m = s.mesh(c.name);
                final int color = argb(c.isLine() ? c.line : c.marker, 0xFF40A0FF);
                final int n = c.size();
                if (c.isLine()) {
                    m.lineWidth = (float) Math.max(1.5, c.lineWidth);
                    int prev = -1;
                    for (int k = 0; k < n; k++) {
                        final int v = m.vertex(c.p[3 * k], c.p[3 * k + 1], c.p[3 * k + 2], color, k);
                        if (prev >= 0) m.line(prev, v);
                        prev = v;
                    }
                } else {
                    m.pointSize = n > 50000 ? 1.8f : n > 5000 ? 3f : (float) Math.max(4, 3 * c.markerSize);
                    for (int k = 0; k < n; k++) m.point(m.vertex(c.p[3 * k], c.p[3 * k + 1], c.p[3 * k + 2], color, k));
                }
                s.notes.add(n + (c.isLine() ? " points of a polyline " : " points of ") + c.name);
            } else if (i instanceof Geometry geo) {
                geometry = true;
                geometry(s, geo, o);
            }
        }
        if (pad.viewMin != null && pad.viewMax != null && pad.viewMin.length == 3 && !geometry) {
            s.box = new double[]{pad.viewMin[0], pad.viewMax[0], pad.viewMin[1], pad.viewMax[1], pad.viewMin[2],
                pad.viewMax[2]};
            boolean sane = true;
            for (int a = 0; a < 3; a++) sane &= s.box[2 * a + 1] > s.box[2 * a];
            if (!sane) s.box = null;
        }
        s.keepAspect = geometry || s.box == null;
        return s;
    }

    /** The volumes of a geometry, one mesh per transparency, pulled apart by the explode factor. */
    private static void geometry(Scene3D s, Geometry geo, Options o) {
        final Map<Integer, Mesh3D> byAlpha = new HashMap<>();
        final Mesh3D wires = s.mesh("wires");
        // The centre of the whole, from which the volumes are pulled.
        double gx = 0;
        double gy = 0;
        double gz = 0;
        long count = 0;
        for (GeoMesh m : geo.meshes) {
            for (int k = 0; k + 2 < m.p.length; k += 3) {
                gx += m.p[k];
                gy += m.p[k + 1];
                gz += m.p[k + 2];
                count++;
            }
        }
        if (count > 0) {
            gx /= count;
            gy /= count;
            gz /= count;
        }
        int volumes = 0;
        for (GeoMesh gm : geo.meshes) {
            double mx = 0;
            double my = 0;
            double mz = 0;
            final int nv = gm.p.length / 3;
            for (int k = 0; k < nv; k++) {
                mx += gm.p[3 * k];
                my += gm.p[3 * k + 1];
                mz += gm.p[3 * k + 2];
            }
            if (nv > 0) {
                mx /= nv;
                my /= nv;
                mz /= nv;
            }
            final double ox = (mx - gx) * o.explode;
            final double oy = (my - gy) * o.explode;
            final double oz = (mz - gz) * o.explode;
            final int alpha = Math.max(0, Math.min(100, gm.transparency));
            final Mesh3D m = byAlpha.computeIfAbsent(alpha, a -> {
                final Mesh3D x = s.mesh("volumes" + (a > 0 ? " (" + a + "% transparent)" : ""));
                x.opacity = a > 0 ? Math.max(0.08f, 1 - a / 100f) : (float) o.opacity;
                return x;
            });
            final int color = 0xFF000000 | gm.color.getRGB();
            final int base = m.vertexCount();
            for (int k = 0; k < nv; k++) {
                m.vertex(gm.p[3 * k] + ox, gm.p[3 * k + 1] + oy, gm.p[3 * k + 2] + oz, color, volumes);
            }
            int k = 0;
            while (k < gm.pol.length) {
                final int n = gm.pol[k];
                if (n >= 3 && k + n < gm.pol.length) {
                    for (int t = 1; t + 1 < n; t++) {
                        final int a = gm.pol[k + 1];
                        final int b = gm.pol[k + 1 + t];
                        final int c = gm.pol[k + 2 + t];
                        if (a < nv && b < nv && c < nv) m.triangle(base + a, base + b, base + c);
                    }
                }
                k += n + 1;
            }
            if (gm.seg.length > 1) {
                final int wb = wires.vertexCount();
                for (int q = 0; q < nv; q++) {
                    wires.vertex(gm.p[3 * q] + ox, gm.p[3 * q + 1] + oy, gm.p[3 * q + 2] + oz, color, volumes);
                }
                for (int q = 0; q + 1 < gm.seg.length; q += 2) {
                    if (gm.seg[q] < nv && gm.seg[q + 1] < nv) wires.line(wb + gm.seg[q], wb + gm.seg[q + 1]);
                }
            }
            volumes++;
        }
        s.notes.add(volumes + " volumes" + (geo.cut ? " (the geometry was cut: too many nodes)" : ""));
        s.axisTitle[0] = "x [cm]";
        s.axisTitle[1] = "y [cm]";
        s.axisTitle[2] = "z [cm]";
    }

    /* ------------------------------------------------------------------ */
    /* Pictures: a PNG becomes a relief                                    */
    /* ------------------------------------------------------------------ */

    /**
     * A picture as a landscape: each pixel's height its ink (or its light),
     * its colour its own. A plot of curves on white paper rises where it was
     * drawn, so a PNG that lost its numbers can still be turned and looked at
     * from the side.
     */
    public static Scene3D image(BufferedImage img, String title, Options o) {
        final Scene3D s = new Scene3D();
        s.title = title;
        s.axisTitle[0] = "pixel x";
        s.axisTitle[1] = "pixel y";
        s.axisTitle[2] = o.invertImage ? "ink" : "light";
        final int w = img.getWidth();
        final int h = img.getHeight();
        final int step = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) Math.max(32, o.imageGrid)));
        final int gx = Math.max(2, w / step);
        final int gy = Math.max(2, h / step);
        final double[][] height = new double[gy][gx];
        final int[][] col = new int[gy][gx];
        // The paper: the median lightness of the border, white for most plots, dark for some.
        final List<Double> border = new ArrayList<>();
        for (int x = 0; x < w; x += Math.max(1, w / 200)) {
            border.add(lightness(img.getRGB(x, 0)));
            border.add(lightness(img.getRGB(x, h - 1)));
        }
        for (int y = 0; y < h; y += Math.max(1, h / 200)) {
            border.add(lightness(img.getRGB(0, y)));
            border.add(lightness(img.getRGB(w - 1, y)));
        }
        border.sort(null);
        final double paper = border.isEmpty() ? 1 : border.get(border.size() / 2);
        final double span = Math.max(paper, 1 - paper);
        // A colour map plot (COLZ, imshow): its colours are values, and the palette says which.
        final String scaleName = o.decodePalette ? recognisePalette(img) : null;
        final Color[] scale = scaleName == null ? null : Palettes.get(scaleName);
        // The colours shown; the heights are always read from the picture's own.
        final BufferedImage paint = o.themePaper ? com.sphere.components.imaging.ImagingTheme.onThemePaper(img) : img;
        for (int j = 0; j < gy; j++) {
            for (int i = 0; i < gx; i++) {
                long r = 0;
                long g = 0;
                long b = 0;
                long pr = 0;
                long pg = 0;
                long pb = 0;
                int n = 0;
                for (int dy = 0; dy < step; dy++) {
                    for (int dx = 0; dx < step; dx++) {
                        final int x = Math.min(w - 1, i * step + dx);
                        final int y = Math.min(h - 1, j * step + dy);
                        final int c = img.getRGB(x, y);
                        r += c >> 16 & 255;
                        g += c >> 8 & 255;
                        b += c & 255;
                        final int q = paint == img ? c : paint.getRGB(x, y);
                        pr += q >> 16 & 255;
                        pg += q >> 8 & 255;
                        pb += q & 255;
                        n++;
                    }
                }
                final int cr = (int) (r / n);
                final int cg = (int) (g / n);
                final int cb = (int) (b / n);
                col[j][i] = 0xFF000000 | (int) (pr / n) << 16 | (int) (pg / n) << 8 | (int) (pb / n);
                final double lum = (0.2126 * cr + 0.7152 * cg + 0.0722 * cb) / 255.0;
                // Ink: how far a pixel stands from the paper, whatever the paper's colour.
                final double ink = o.invertImage ? Math.abs(lum - paper) / Math.max(1e-6, span) : lum;
                if (scale != null) {
                    // The value the colour stands for; text and axes stay low, engraved.
                    final double[] near = nearest(scale, cr, cg, cb);
                    height[j][i] = near[1] < 34 ? 0.04 + 0.96 * near[0] / (scale.length - 1) : 0.03 * ink;
                } else {
                    height[j][i] = ink;
                }
            }
        }
        // A light blur, so a one-pixel line becomes a ridge rather than a wall.
        final double[][] smooth = new double[gy][gx];
        for (int j = 0; j < gy; j++) {
            for (int i = 0; i < gx; i++) {
                double sum = 0;
                double wsum = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        final int jj = Math.max(0, Math.min(gy - 1, j + dj));
                        final int ii = Math.max(0, Math.min(gx - 1, i + di));
                        final double wt = di == 0 && dj == 0 ? (scale != null ? 40 : 4) : di == 0 || dj == 0 ? 2 : 1;
                        sum += height[jj][ii] * wt;
                        wsum += wt;
                    }
                }
                smooth[j][i] = sum / wsum;
            }
        }
        final Mesh3D m = s.mesh("relief");
        m.smooth = true;
        final double scaleZ = Math.max(w, h) * o.reliefHeight;
        final int[] idx = new int[gx * gy];
        for (int j = 0; j < gy; j++) {
            for (int i = 0; i < gx; i++) {
                idx[j * gx + i] = m.vertex(i * step, (gy - 1 - j) * step, smooth[j][i] * scaleZ, col[j][i], height[j][i]);
            }
        }
        for (int j = 0; j + 1 < gy; j++) {
            for (int i = 0; i + 1 < gx; i++) {
                m.quad(idx[j * gx + i], idx[(j + 1) * gx + i], idx[(j + 1) * gx + i + 1], idx[j * gx + i + 1]);
            }
        }
        s.keepAspect = true;
        s.box = new double[]{0, (gx - 1) * step, 0, (gy - 1) * step, 0, scaleZ};
        s.notes.add(gx + " x " + gy + " relief of a " + w + " x " + h + " picture");
        if (scale != null) {
            s.palette = scale;
            s.valueLo = 0;
            s.valueHi = 1;
            s.valueTitle = "colour scale";
            s.axisTitle[2] = "decoded value";
            s.notes.add("colours read back on the " + scaleName + " scale: the heights are the plot's values "
                + "(as a fraction of its colour bar)");
        }
        return s;
    }

    /**
     * Which known palette the coloured pixels of a picture come from, or null
     * when they come from none (a line plot, a photograph): the palette whose
     * nearest colours lie closest, on average, to the picture's saturated ones.
     */
    static String recognisePalette(BufferedImage img) {
        final int w = img.getWidth();
        final int h = img.getHeight();
        final int stride = Math.max(1, (int) Math.sqrt(w * (double) h / 40000));
        final List<int[]> coloured = new ArrayList<>();
        int seen = 0;
        final float[] hsb = new float[3];
        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                final int c = img.getRGB(x, y);
                seen++;
                Color.RGBtoHSB(c >> 16 & 255, c >> 8 & 255, c & 255, hsb);
                if (hsb[1] > 0.3f && hsb[2] > 0.12f) coloured.add(new int[]{c >> 16 & 255, c >> 8 & 255, c & 255});
            }
        }
        if (coloured.size() < Math.max(50, seen / 100)) return null;
        final int every = Math.max(1, coloured.size() / 4000);
        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (String name : Palettes.names()) {
            final Color[] p = Palettes.get(name);
            double sum = 0;
            int n = 0;
            for (int k = 0; k < coloured.size(); k += every) {
                final int[] c = coloured.get(k);
                final double d = nearest(p, c[0], c[1], c[2])[1];
                sum += d * d;
                n++;
            }
            final double score = Math.sqrt(sum / Math.max(1, n));
            if (score < bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return bestScore < 24 ? best : null;
    }

    /** The index of the palette's colour nearest to r g b, and the distance to it. */
    static double[] nearest(Color[] p, int r, int g, int b) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < p.length; i++) {
            final int dr = p[i].getRed() - r;
            final int dg = p[i].getGreen() - g;
            final int db = p[i].getBlue() - b;
            final double d = dr * dr + dg * dg + db * db;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return new double[]{best, Math.sqrt(bd)};
    }

    private static double lightness(int c) {
        return (0.2126 * (c >> 16 & 255) + 0.7152 * (c >> 8 & 255) + 0.0722 * (c & 255)) / 255.0;
    }

    private static String title(Hist h) {
        return h.title == null || h.title.isBlank() ? h.name : h.title;
    }
}
