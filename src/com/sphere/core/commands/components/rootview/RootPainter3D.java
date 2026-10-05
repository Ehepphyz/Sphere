package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What ROOT draws in three dimensions, drawn so it can be turned: LEGO and
 * SURF for a TH2 or a TF2, BOX and SCAT for a TH3, points and Delaunay
 * triangles for a TGraph2D.
 *
 * The view is ROOT's: theta the elevation, phi the azimuth, 30 and 30 by
 * default, and the pad's own when ROOT set them. The data fill a cube; its
 * three walls at the back carry the grid, the axes run along the edges nearest
 * the viewer. Faces are painted from the back to the front.
 */
final class RootPainter3D {

    private RootPainter3D() {
    }

    /** A face or a mark, projected, with how far it is from the viewer. */
    private record Poly(Path2D shape, double depth, Color fill, Color edge) {
    }

    /** The view: rotation, scale and centre, from the cube [-1,1]^3 to the screen. */
    static final class Projection {
        final double ct;
        final double st;
        final double cp;
        final double sp;
        double scale = 1;
        double cx;
        double cy;
        /** TView3D::SetPerspective: how much nearer is larger; 0 is ROOT's parallel projection. */
        double perspective;

        Projection(double thetaDeg, double phiDeg) {
            final double t = Math.toRadians(thetaDeg);
            final double p = Math.toRadians(phiDeg);
            ct = Math.cos(t);
            st = Math.sin(t);
            cp = Math.cos(p);
            sp = Math.sin(p);
        }

        /** Screen x, screen y (down), depth (larger is farther). */
        double[] at(double x, double y, double z) {
            final double a = x * cp + y * sp;
            final double b = -x * sp + y * cp;
            final double sy = z * ct + b * st;
            final double depth = b * ct - z * st;
            final double f = perspective > 0 ? 1 / Math.max(0.2, 1 + perspective * depth) : 1;
            return new double[]{cx + scale * a * f, cy - scale * sy * f, depth};
        }

        /** Whether a face with this outward normal looks toward the viewer. */
        boolean faces(double nx, double ny, double nz) {
            // The gradient of depth over the cube.
            final double dx = -sp * ct;
            final double dy = cp * ct;
            final double dz = -st;
            return nx * dx + ny * dy + nz * dz < 0;
        }
    }

    static void paint(Graphics2D g, Rectangle area, Pad pad, View view, RootScene scene, String option) {
        final Item main = pad.main();
        final String o = option.toUpperCase(java.util.Locale.ROOT);
        final boolean palette = o.contains("Z");
        final Rectangle box = new Rectangle(area.x + area.width / 14, area.y + area.height / 9,
            area.width - area.width / 7 - (palette ? area.width / 8 : 0), area.height - area.height / 6);
        view.frame.setBounds(box);

        final double theta = Double.isNaN(view.theta) ? pad.theta : view.theta;
        final double phi = Double.isNaN(view.phi) ? pad.phi : view.phi;
        final Projection pr = new Projection(theta, phi);
        pr.perspective = view.perspective ? 0.22 : 0;
        fit(pr, box, view.zoom);

        final boolean lz = RootPadPainter.log(view.logz, pad.logz);
        final Ranges r = ranges(main, o, view, lz);
        if (r == null) return;

        walls(g, pr, r);
        final List<Poly> polys = new ArrayList<>();
        final Color[] colors = scene.palette;
        if (main instanceof Hist h && h.dim == 2) {
            if ((o.contains("SURF") || o.contains("TRI")) && h.nx() >= 2 && h.ny() >= 2) {
                surface(polys, pr, h, r, o, colors, pad.fill);
            } else {
                lego(polys, pr, h, r, o, colors);
            }
        } else if (main instanceof Hist h && h.dim == 3) {
            boxes(polys, pr, h, r, o, colors);
        } else if (main instanceof Graph2D g2) {
            graph2d(polys, pr, g2, r, o, colors);
        }
        polys.sort(Comparator.comparingDouble(Poly::depth).reversed());
        g.setStroke(new BasicStroke(0.8f));
        for (Poly p : polys) {
            if (p.fill != null) {
                g.setColor(p.fill);
                g.fill(p.shape);
            }
            if (p.edge != null) {
                g.setColor(p.edge);
                g.draw(p.shape);
            }
        }
        g.setStroke(new BasicStroke(1f));
        if (!view.hideAxes3D) axes(g, box, pr, r, main, view);
        if (palette) paletteBar(g, area, box, colors, r.z0, r.z1, lz);
    }

    /* ------------------------------------------------------------------ */
    /* The cube                                                            */
    /* ------------------------------------------------------------------ */

    /** The data ranges the cube stands for. */
    static final class Ranges {
        double x0;
        double x1;
        double y0;
        double y1;
        double z0;
        double z1;
        boolean lz;

        double nx(double x) {
            return 2 * (x - x0) / (x1 - x0) - 1;
        }

        double ny(double y) {
            return 2 * (y - y0) / (y1 - y0) - 1;
        }

        double nz(double z) {
            final double f = lz ? (Math.log10(Math.max(z, z0)) - Math.log10(z0)) / (Math.log10(z1) - Math.log10(z0))
                : (z - z0) / (z1 - z0);
            return 2 * Math.max(0, Math.min(1, f)) - 1;
        }

        double fraction(double z) {
            return (nz(z) + 1) / 2;
        }
    }

    private static Ranges ranges(Item main, String o, View view, boolean lz) {
        final Ranges r = new Ranges();
        r.lz = lz;
        if (main instanceof Hist h) {
            final double[] xs = view.xr != null ? view.xr : h.x.shown();
            final double[] ys = view.yr != null ? view.yr : h.y.shown();
            r.x0 = xs[0];
            r.x1 = xs[1];
            r.y0 = ys[0];
            r.y1 = ys[1];
            if (h.dim == 3) {
                final double[] zs = view.zr != null ? view.zr : h.z.shown();
                r.z0 = zs[0];
                r.z1 = zs[1];
                r.lz = false;
                return r;
            }
            final double[] zr = view.zr != null ? view.zr : RootPadPainter.zRange(h, lz);
            r.z0 = zr[0];
            r.z1 = zr[1];
            // A LEGO of positive contents stands on zero, as ROOT's does.
            if (view.zr == null && o.contains("LEGO") && !lz && r.z0 > 0) r.z0 = 0;
            if (view.zr == null && !lz) r.z1 += 0.05 * (r.z1 - r.z0);
        } else if (main instanceof Graph2D g) {
            if (g.x.length == 0) return null;
            final double[] xs = view.xr != null ? view.xr : span(g.x);
            final double[] ys = view.yr != null ? view.yr : span(g.y);
            final double[] zs = view.zr != null ? view.zr : span(g.z).clone();
            if (view.zr == null && !Double.isNaN(g.min)) zs[0] = g.min;
            if (view.zr == null && !Double.isNaN(g.max)) zs[1] = g.max;
            r.x0 = xs[0];
            r.x1 = xs[1];
            r.y0 = ys[0];
            r.y1 = ys[1];
            r.z0 = zs[0];
            r.z1 = zs[1];
            if (lz && r.z0 <= 0) r.z0 = Math.max(1e-9, r.z1 * 1e-4);
        } else {
            return null;
        }
        if (!(r.x1 > r.x0)) r.x1 = r.x0 + 1;
        if (!(r.y1 > r.y0)) r.y1 = r.y0 + 1;
        if (!(r.z1 > r.z0)) r.z1 = r.z0 + 1;
        return r;
    }

    private static double[] span(double[] v) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            if (!Double.isFinite(x)) continue;
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return lo < hi ? new double[]{lo, hi} : new double[]{lo - 1, lo + 1};
    }

    /** Scales the cube's projection to fill the box, whatever the angle. */
    private static void fit(Projection pr, Rectangle box, double zoom) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 8; k++) {
            final double[] p = pr.at((k & 1) == 0 ? -1 : 1, (k & 2) == 0 ? -1 : 1, (k & 4) == 0 ? -1 : 1);
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        pr.scale = 0.92 * zoom * Math.min(box.width / (maxX - minX), box.height / (maxY - minY));
        pr.cx = box.x + box.width / 2.0 - pr.scale * (minX + maxX) / 2;
        pr.cy = box.y + box.height / 2.0 - pr.scale * (minY + maxY) / 2;
    }

    /** The three walls at the back, with the grid at the ticks. */
    private static void walls(Graphics2D g, Projection pr, Ranges r) {
        final double[] xt = norm(RootPadPainter.ticks(r.x0, r.x1, false), r::nx);
        final double[] yt = norm(RootPadPainter.ticks(r.y0, r.y1, false), r::ny);
        final double[] zt = norm(RootPadPainter.ticks(r.z0, r.z1, r.lz), r::nz);
        final double bx = pr.at(-1, 0, 0)[2] > pr.at(1, 0, 0)[2] ? -1 : 1;
        final double by = pr.at(0, -1, 0)[2] > pr.at(0, 1, 0)[2] ? -1 : 1;
        final double bz = pr.at(0, 0, -1)[2] > pr.at(0, 0, 1)[2] ? -1 : 1;
        final Color wall = RootPadPainter.palette.getCanvas3DWall();
        final Color line = RootPadPainter.palette.getCanvas3DWallLine();
        final Color gridColor = RootPadPainter.palette.getCanvas3DWallGrid();
        // x = bx wall
        quad(g, pr, new double[][]{{bx, -1, -1}, {bx, 1, -1}, {bx, 1, 1}, {bx, -1, 1}}, wall, line);
        quad(g, pr, new double[][]{{-1, by, -1}, {1, by, -1}, {1, by, 1}, {-1, by, 1}}, wall, line);
        quad(g, pr, new double[][]{{-1, -1, bz}, {1, -1, bz}, {1, 1, bz}, {-1, 1, bz}}, wall, line);
        g.setColor(gridColor);
        for (double z : zt) {
            seg(g, pr, bx, -1, z, bx, 1, z);
            seg(g, pr, -1, by, z, 1, by, z);
        }
        for (double x : xt) {
            seg(g, pr, x, by, -1, x, by, 1);
            seg(g, pr, x, -1, bz, x, 1, bz);
        }
        for (double y : yt) {
            seg(g, pr, bx, y, -1, bx, y, 1);
            seg(g, pr, -1, y, bz, 1, y, bz);
        }
    }

    private interface Norm {
        double apply(double v);
    }

    private static double[] norm(double[] ticks, Norm n) {
        final double[] out = new double[ticks.length];
        for (int i = 0; i < ticks.length; i++) out[i] = n.apply(ticks[i]);
        return out;
    }

    private static void quad(Graphics2D g, Projection pr, double[][] corners, Color fill, Color edge) {
        final Path2D path = new Path2D.Double();
        for (int k = 0; k < corners.length; k++) {
            final double[] p = pr.at(corners[k][0], corners[k][1], corners[k][2]);
            if (k == 0) path.moveTo(p[0], p[1]);
            else path.lineTo(p[0], p[1]);
        }
        path.closePath();
        g.setColor(fill);
        g.fill(path);
        g.setColor(edge);
        g.draw(path);
    }

    private static void seg(Graphics2D g, Projection pr, double x0, double y0, double z0, double x1, double y1, double z1) {
        final double[] a = pr.at(x0, y0, z0);
        final double[] b = pr.at(x1, y1, z1);
        g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
    }

    /* ------------------------------------------------------------------ */
    /* LEGO and SURF                                                       */
    /* ------------------------------------------------------------------ */

    private static void lego(List<Poly> polys, Projection pr, Hist h, Ranges r, String o, Color[] colors) {
        final boolean byPalette = o.contains("LEGO2") || o.contains("LEGO4") || o.contains("COL");
        final boolean edges = !o.contains("LEGO3") && !o.contains("LEGO4");
        final Color base = h.hasFill() && !isWhite(h.fill) ? h.fill : RootPadPainter.palette.getCanvas3DDefaultFill();
        final double zb = r.nz(r.lz ? r.z0 : Math.max(r.z0, Math.min(r.z1, 0)));
        for (int iy = 0; iy < h.ny(); iy++) {
            final double ya = h.y.edge(iy);
            final double yb = h.y.edge(iy + 1);
            if (yb <= r.y0 || ya >= r.y1) continue;
            for (int ix = 0; ix < h.nx(); ix++) {
                final double xa = h.x.edge(ix);
                final double xb = h.x.edge(ix + 1);
                if (xb <= r.x0 || xa >= r.x1) continue;
                final double v = h.at(ix, iy);
                if (v == 0 || r.lz && v <= 0) continue;
                final double x0 = r.nx(Math.max(xa, r.x0));
                final double x1 = r.nx(Math.min(xb, r.x1));
                final double y0 = r.ny(Math.max(ya, r.y0));
                final double y1 = r.ny(Math.min(yb, r.y1));
                final double zt = r.nz(v);
                final double lo = Math.min(zb, zt);
                final double hi = Math.max(zb, zt);
                if (hi - lo < 1e-9) continue;
                final Color c = byPalette ? colors[index(colors, r.fraction(v))] : base;
                final Color edge = edges ? RootPadPainter.inkColor() : null;
                cuboid(polys, pr, x0, x1, y0, y1, lo, hi, c, edge);
            }
        }
    }

    /** The visible faces of a box, shaded by how they face the light. */
    private static void cuboid(List<Poly> polys, Projection pr, double x0, double x1, double y0, double y1,
                               double z0, double z1, Color c, Color edge) {
        if (pr.faces(0, 0, 1)) face(polys, pr, new double[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}}, c, edge);
        if (pr.faces(0, 0, -1)) face(polys, pr, new double[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}}, shade(c, 0.6), edge);
        if (pr.faces(-1, 0, 0)) face(polys, pr, new double[][]{{x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}}, shade(c, 0.72), edge);
        if (pr.faces(1, 0, 0)) face(polys, pr, new double[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}}, shade(c, 0.72), edge);
        if (pr.faces(0, -1, 0)) face(polys, pr, new double[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}}, shade(c, 0.86), edge);
        if (pr.faces(0, 1, 0)) face(polys, pr, new double[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, shade(c, 0.86), edge);
    }

    private static void surface(List<Poly> polys, Projection pr, Hist h, Ranges r, String o, Color[] colors, Color background) {
        final boolean wire = o.equals("SURF") || o.equals("SURFZ");
        final boolean noEdges = o.contains("SURF2") || o.contains("SURF4");
        final boolean lit = o.contains("SURF4");
        final int nx = h.nx();
        final int ny = h.ny();
        final double[][] X = new double[nx][ny];
        final double[][] Y = new double[nx][ny];
        final double[][] Z = new double[nx][ny];
        final double[][] V = new double[nx][ny];
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                X[ix][iy] = r.nx(h.x.center(ix));
                Y[ix][iy] = r.ny(h.y.center(iy));
                V[ix][iy] = h.at(ix, iy);
                Z[ix][iy] = r.nz(V[ix][iy]);
            }
        }
        final Color edgeColor = h.line == null || isWhite(h.line) ? RootPadPainter.inkColor() : RootPadPainter.ink(h.line);
        for (int ix = 0; ix < nx - 1; ix++) {
            for (int iy = 0; iy < ny - 1; iy++) {
                if (X[ix + 1][iy] < -1.0001 || X[ix][iy] > 1.0001 || Y[ix][iy + 1] < -1.0001 || Y[ix][iy] > 1.0001) continue;
                if (r.lz && (V[ix][iy] <= 0 || V[ix + 1][iy] <= 0 || V[ix + 1][iy + 1] <= 0 || V[ix][iy + 1] <= 0)) continue;
                final double[][] corners = {
                    {clampN(X[ix][iy]), clampN(Y[ix][iy]), Z[ix][iy]},
                    {clampN(X[ix + 1][iy]), clampN(Y[ix + 1][iy]), Z[ix + 1][iy]},
                    {clampN(X[ix + 1][iy + 1]), clampN(Y[ix + 1][iy + 1]), Z[ix + 1][iy + 1]},
                    {clampN(X[ix][iy + 1]), clampN(Y[ix][iy + 1]), Z[ix][iy + 1]}};
                final double mean = 0.25 * (V[ix][iy] + V[ix + 1][iy] + V[ix + 1][iy + 1] + V[ix][iy + 1]);
                Color fill = wire ? RootPadPainter.paper(background) : colors[index(colors, r.fraction(mean))];
                if (lit) fill = shade(fill, light(corners));
                face(polys, pr, corners, fill, noEdges ? fill : edgeColor);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* TH3 and TGraph2D                                                    */
    /* ------------------------------------------------------------------ */

    private static void boxes(List<Poly> polys, Projection pr, Hist h, Ranges r, String o, Color[] colors) {
        double biggest = 0;
        for (double v : h.v) biggest = Math.max(biggest, v);
        if (biggest <= 0) return;
        final boolean scatter = o.contains("SCAT");
        final boolean byPalette = o.contains("BOX2") || o.contains("COL");
        final Color base = h.hasFill() && !isWhite(h.fill) ? h.fill : RootPadPainter.palette.getCanvas3DDefaultFill();
        final java.util.Random random = new java.util.Random(4242);
        for (int iz = 0; iz < h.nz(); iz++) {
            for (int iy = 0; iy < h.ny(); iy++) {
                for (int ix = 0; ix < h.nx(); ix++) {
                    final double v = h.at(ix, iy, iz);
                    if (v <= 0) continue;
                    final double cx = r.nx(h.x.center(ix));
                    final double cy = r.ny(h.y.center(iy));
                    final double cz = 2 * (h.z.center(iz) - r.z0) / (r.z1 - r.z0) - 1;
                    final double hx = (r.nx(h.x.edge(ix + 1)) - r.nx(h.x.edge(ix))) / 2;
                    final double hy = (r.ny(h.y.edge(iy + 1)) - r.ny(h.y.edge(iy))) / 2;
                    final double hz = (h.z.edge(iz + 1) - h.z.edge(iz)) / (r.z1 - r.z0);
                    if (Math.abs(cx) > 1 || Math.abs(cy) > 1 || Math.abs(cz) > 1) continue;
                    if (scatter) {
                        final int dots = (int) Math.ceil(v / biggest * 20);
                        for (int k = 0; k < dots; k++) {
                            final double[] p = pr.at(cx + (random.nextDouble() * 2 - 1) * hx,
                                cy + (random.nextDouble() * 2 - 1) * hy, cz + (random.nextDouble() * 2 - 1) * hz);
                            polys.add(new Poly(dot(p[0], p[1], 1.2), p[2], RootPadPainter.inkColor(), null));
                        }
                        continue;
                    }
                    final double s = Math.cbrt(v / biggest);
                    final Color c = byPalette ? colors[index(colors, v / biggest)] : base;
                    cuboid(polys, pr, cx - s * hx, cx + s * hx, cy - s * hy, cy + s * hy, cz - s * hz, cz + s * hz, c,
                        shade(c, 0.45));
                }
            }
        }
    }

    private static void graph2d(List<Poly> polys, Projection pr, Graph2D gd, Ranges r, String o, Color[] colors) {
        final int n = gd.x.length;
        final boolean triangles = (o.contains("TRI") || o.contains("SURF")) && n >= 3 && n <= 6000;
        if (triangles) {
            final double[] xs = new double[n];
            final double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = r.nx(gd.x[i]);
                ys[i] = r.ny(gd.y[i]);
            }
            final boolean noEdges = o.contains("TRI2") || o.contains("SURF2");
            final boolean plain = o.equals("TRI") || o.equals("TRIW");
            for (int[] t : Delaunay.triangulate(xs, ys)) {
                final double[][] corners = new double[3][];
                double mean = 0;
                for (int k = 0; k < 3; k++) {
                    corners[k] = new double[]{xs[t[k]], ys[t[k]], r.nz(gd.z[t[k]])};
                    mean += gd.z[t[k]] / 3;
                }
                final Color fill = plain ? RootPadPainter.palette.getCanvasPaper() : colors[index(colors, r.fraction(mean))];
                face(polys, pr, corners, fill, noEdges ? fill : RootPadPainter.palette.getCanvas3DMesh());
            }
        }
        if (!triangles || o.contains("P")) {
            final Color mc = RootPadPainter.ink(gd.marker);
            final boolean byPalette = o.contains("PCOL");
            for (int i = 0; i < n; i++) {
                final double[] p = pr.at(r.nx(gd.x[i]), r.ny(gd.y[i]), r.nz(gd.z[i]));
                final Color c = byPalette ? colors[index(colors, r.fraction(gd.z[i]))] : mc;
                polys.add(new Poly(dot(p[0], p[1], Math.max(2, 3 * gd.markerSize)), p[2] - 1e-6, c, null));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Axes and palette                                                    */
    /* ------------------------------------------------------------------ */

    private static void axes(Graphics2D g, Rectangle box, Projection pr, Ranges r, Item main, View view) {
        g.setFont(RootPadPainter.labelFont(box));
        final FontMetrics fm = g.getFontMetrics();
        g.setColor(RootPadPainter.inkColor());
        final double[] centre = pr.at(0, 0, 0);
        // x along the nearer of the two bottom edges that run in x; y likewise.
        final double ey = pr.at(0, -1, -1)[2] < pr.at(0, 1, -1)[2] ? -1 : 1;
        final double ex = pr.at(-1, 0, -1)[2] < pr.at(1, 0, -1)[2] ? -1 : 1;
        axis(g, fm, pr, centre, RootPadPainter.ticks(r.x0, r.x1, false), false, r::nx,
            t -> new double[]{t, ey, -1}, title(view.xTitle, main, 'x'));
        axis(g, fm, pr, centre, RootPadPainter.ticks(r.y0, r.y1, false), false, r::ny,
            t -> new double[]{ex, t, -1}, title(view.yTitle, main, 'y'));
        // z up the vertical edge furthest to the left on the screen.
        double bestX = Double.POSITIVE_INFINITY;
        double zx = -1;
        double zy = -1;
        for (int k = 0; k < 4; k++) {
            final double cx = (k & 1) == 0 ? -1 : 1;
            final double cy = (k & 2) == 0 ? -1 : 1;
            final double sx = pr.at(cx, cy, 0)[0];
            if (sx < bestX) {
                bestX = sx;
                zx = cx;
                zy = cy;
            }
        }
        final double fx = zx;
        final double fy = zy;
        axis(g, fm, pr, centre, RootPadPainter.ticks(r.z0, r.z1, r.lz), r.lz, r::nz,
            t -> new double[]{fx, fy, t}, title(view.zTitle, main, 'z'));
    }

    private interface Place {
        double[] at(double t);
    }

    private static void axis(Graphics2D g, FontMetrics fm, Projection pr, double[] centre, double[] ticks, boolean log,
                             Norm norm, Place place, String title) {
        final double[] a = place.at(-1);
        final double[] b = place.at(1);
        final double[] pa = pr.at(a[0], a[1], a[2]);
        final double[] pb = pr.at(b[0], b[1], b[2]);
        g.draw(new Line2D.Double(pa[0], pa[1], pb[0], pb[1]));
        // Outward, away from the cube's centre on the screen.
        final double mx = (pa[0] + pb[0]) / 2 - centre[0];
        final double my = (pa[1] + pb[1]) / 2 - centre[1];
        final double len = Math.max(1e-9, Math.hypot(mx, my));
        final double ox = mx / len;
        final double oy = my / len;
        for (double t : ticks) {
            final double n = norm.apply(t);
            if (n < -1.0001 || n > 1.0001) continue;
            final double[] c = place.at(n);
            final double[] p = pr.at(c[0], c[1], c[2]);
            g.draw(new Line2D.Double(p[0], p[1], p[0] + 5 * ox, p[1] + 5 * oy));
            final String s = RootPadPainter.tickLabel(t, ticks, log);
            final double lx = p[0] + 11 * ox + (ox < -0.3 ? -fm.stringWidth(s) : ox > 0.3 ? 0 : -fm.stringWidth(s) / 2.0);
            final double ly = p[1] + 11 * oy + (oy > 0.3 ? fm.getAscent() : oy < -0.3 ? 0 : fm.getAscent() / 2.0);
            g.drawString(s, (float) lx, (float) ly);
        }
        if (title != null && !title.isEmpty()) {
            final String t = RootLatex.plain(title);
            final double[] end = pr.at(b[0], b[1], b[2]);
            final double tx = end[0] + 26 * ox + (ox < -0.3 ? -fm.stringWidth(t) : 0);
            final double ty = end[1] + 26 * oy + (oy > 0.3 ? fm.getAscent() : 0);
            g.drawString(t, (float) tx, (float) ty);
        }
    }

    private static String title(String user, Item main, char axis) {
        if (user != null) return user;
        if (main instanceof Hist h) {
            return switch (axis) {
                case 'x' -> h.x.title;
                case 'y' -> h.y == null ? "" : h.y.title;
                default -> h.dim == 3 ? h.z.title : h.zTitle;
            };
        }
        if (main instanceof Graph2D gd) return axis == 'x' ? gd.xTitle : axis == 'y' ? gd.yTitle : gd.zTitle;
        return "";
    }

    private static void paletteBar(Graphics2D g, Rectangle area, Rectangle box, Color[] colors, double z0, double z1,
                                   boolean lz) {
        final int x = box.x + box.width + area.width / 30;
        final int w = Math.max(8, area.width / 45);
        final int top = box.y + box.height / 8;
        final int h = box.height * 3 / 4;
        for (int i = 0; i < h; i++) {
            g.setColor(colors[index(colors, 1 - i / (double) h)]);
            g.fillRect(x, top + i, w, 1);
        }
        g.setColor(RootPadPainter.inkColor());
        g.drawRect(x, top, w, h);
        g.setFont(RootPadPainter.labelFont(box));
        final FontMetrics fm = g.getFontMetrics();
        final double[] ticks = RootPadPainter.ticks(z0, z1, lz);
        for (double t : ticks) {
            final double f = lz ? (Math.log10(t) - Math.log10(z0)) / (Math.log10(z1) - Math.log10(z0)) : (t - z0) / (z1 - z0);
            final int y = top + h - (int) Math.round(f * h);
            g.drawLine(x + w - 3, y, x + w, y);
            g.drawString(RootPadPainter.tickLabel(t, ticks, lz), x + w + 3, y + fm.getAscent() / 2 - 1);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static void face(List<Poly> polys, Projection pr, double[][] corners, Color fill, Color edge) {
        final Path2D path = new Path2D.Double();
        double depth = 0;
        for (int k = 0; k < corners.length; k++) {
            final double[] p = pr.at(corners[k][0], corners[k][1], corners[k][2]);
            if (k == 0) path.moveTo(p[0], p[1]);
            else path.lineTo(p[0], p[1]);
            depth += p[2];
        }
        path.closePath();
        polys.add(new Poly(path, depth / corners.length, fill, edge));
    }

    private static Path2D dot(double x, double y, double r) {
        final Path2D p = new Path2D.Double();
        p.append(new java.awt.geom.Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r), false);
        return p;
    }

    /** Brightness of a face under a light from the viewer's side and above. */
    private static double light(double[][] c) {
        final double ux = c[1][0] - c[0][0];
        final double uy = c[1][1] - c[0][1];
        final double uz = c[1][2] - c[0][2];
        final double vx = c[3][0] - c[0][0];
        final double vy = c[3][1] - c[0][1];
        final double vz = c[3][2] - c[0][2];
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        final double n = Math.max(1e-12, Math.sqrt(nx * nx + ny * ny + nz * nz));
        nx /= n;
        ny /= n;
        nz /= n;
        final double lx = 0.3;
        final double ly = -0.5;
        final double lz = 0.8;
        final double l = Math.sqrt(lx * lx + ly * ly + lz * lz);
        return 0.45 + 0.55 * Math.abs(nx * lx / l + ny * ly / l + nz * lz / l);
    }

    private static double clampN(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    static int index(Color[] colors, double f) {
        final double c = Double.isFinite(f) ? Math.max(0, Math.min(1, f)) : 0;
        return (int) Math.round(c * (colors.length - 1));
    }

    static Color shade(Color c, double f) {
        return new Color((int) Math.max(0, Math.min(255, c.getRed() * f)), (int) Math.max(0, Math.min(255, c.getGreen() * f)),
            (int) Math.max(0, Math.min(255, c.getBlue() * f)), c.getAlpha());
    }

    private static boolean isWhite(Color c) {
        return c == null || c.getRed() > 250 && c.getGreen() > 250 && c.getBlue() > 250;
    }

    /* ------------------------------------------------------------------ */
    /* Delaunay                                                            */
    /* ------------------------------------------------------------------ */

    /** Bowyer-Watson: the triangles ROOT's TGraph2D draws its TRI options on. */
    static final class Delaunay {

        private Delaunay() {
        }

        static List<int[]> triangulate(double[] xs, double[] ys) {
            final int n = xs.length;
            final double[] px = new double[n + 3];
            final double[] py = new double[n + 3];
            System.arraycopy(xs, 0, px, 0, n);
            System.arraycopy(ys, 0, py, 0, n);
            // A triangle holding every point.
            px[n] = -30;
            py[n] = -30;
            px[n + 1] = 30;
            py[n + 1] = -30;
            px[n + 2] = 0;
            py[n + 2] = 30;
            List<int[]> triangles = new ArrayList<>();
            triangles.add(new int[]{n, n + 1, n + 2});
            for (int i = 0; i < n; i++) {
                final List<int[]> bad = new ArrayList<>();
                for (int[] t : triangles) if (inCircle(px, py, t, px[i], py[i])) bad.add(t);
                final List<int[]> edges = new ArrayList<>();
                for (int[] t : bad) {
                    for (int k = 0; k < 3; k++) {
                        final int a = t[k];
                        final int b = t[(k + 1) % 3];
                        boolean shared = false;
                        for (int[] u : bad) {
                            if (u == t) continue;
                            if (has(u, a) && has(u, b)) {
                                shared = true;
                                break;
                            }
                        }
                        if (!shared) edges.add(new int[]{a, b});
                    }
                }
                triangles.removeAll(bad);
                for (int[] e : edges) triangles.add(new int[]{e[0], e[1], i});
            }
            final List<int[]> out = new ArrayList<>();
            for (int[] t : triangles) if (t[0] < n && t[1] < n && t[2] < n) out.add(t);
            return out;
        }

        private static boolean has(int[] t, int v) {
            return t[0] == v || t[1] == v || t[2] == v;
        }

        private static boolean inCircle(double[] px, double[] py, int[] t, double x, double y) {
            final double ax = px[t[0]] - x;
            final double ay = py[t[0]] - y;
            final double bx = px[t[1]] - x;
            final double by = py[t[1]] - y;
            final double cx = px[t[2]] - x;
            final double cy = py[t[2]] - y;
            final double det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay)
                + (cx * cx + cy * cy) * (ax * by - bx * ay);
            final double orient = (px[t[1]] - px[t[0]]) * (py[t[2]] - py[t[0]]) - (py[t[1]] - py[t[0]]) * (px[t[2]] - px[t[0]]);
            return orient > 0 ? det > 0 : det < 0;
        }
    }
}
