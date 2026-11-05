package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.Map;

/**
 * Shadings (8.7.4.5): axial and radial ones as a Java Paint evaluated per
 * pixel through a color table (Extend honored); function-based ones
 * sampled; the mesh shadings (free-form and lattice Gouraud triangles,
 * Coons and tensor patches) drawn as small flat pieces.
 */
public final class ShadingPaint {

    private ShadingPaint() {
    }

    /** A Paint for an axial or radial shading, or null for the mesh types (drawn by {@link #fillMesh}). */
    public static Paint paint(PdfDict sh, AffineTransform shadingToDevice, PdfDict resources,
                              Map<Object, PdfColorSpace> csCache, float alpha) {
        int type = sh.getInt("ShadingType", 0);
        PdfColorSpace cs = PdfColorSpace.resolve(sh.get("ColorSpace", "CS"), resources, csCache);
        PdfFunction fn = PdfFunction.parse(sh.get("Function"));
        if (type == 2 || type == 3) {
            PdfArray coords = sh.getArray("Coords");
            if (coords == null) {
                return null;
            }
            double[] c = coords.toDoubles();
            PdfArray dom = sh.getArray("Domain");
            double t0 = dom == null ? 0 : dom.getDouble(0, 0);
            double t1 = dom == null ? 1 : dom.getDouble(1, 1);
            PdfArray ext = sh.getArray("Extend");
            boolean e0 = ext != null && ext.get(0) instanceof com.sphere.components.spdf.cos.PdfBoolean b0 && b0.value;
            boolean e1 = ext != null && ext.get(1) instanceof com.sphere.components.spdf.cos.PdfBoolean b1 && b1.value;
            int[] lut = new int[1024];
            for (int i = 0; i < lut.length; i++) {
                double t = t0 + (t1 - t0) * i / (lut.length - 1.0);
                lut[i] = colorAt(cs, fn, t);
            }
            int a = Math.round(alpha * 255);
            return new GradientPaint2(type == 2, c, e0, e1, lut, shadingToDevice, a);
        }
        if (type == 1) {
            return new FunctionPaint(sh, cs, fn, shadingToDevice, Math.round(alpha * 255));
        }
        return null;
    }

    static int colorAt(PdfColorSpace cs, PdfFunction fn, double t) {
        float[] c = new float[cs.components()];
        if (fn != null) {
            double[] r = fn.eval(new double[] { t });
            for (int i = 0; i < c.length && i < r.length; i++) {
                c[i] = (float) r[i];
            }
        } else if (c.length > 0) {
            c[0] = (float) t;
        }
        return cs.rgb(c);
    }

    /** The color a shading paints before and beyond its geometry, when it has a Background. */
    public static Color background(PdfDict sh, PdfDict resources, Map<Object, PdfColorSpace> csCache) {
        PdfArray bg = sh.getArray("Background");
        if (bg == null) {
            return null;
        }
        PdfColorSpace cs = PdfColorSpace.resolve(sh.get("ColorSpace", "CS"), resources, csCache);
        return new Color(cs.rgb(bg.toFloats()));
    }

    private static final ColorModel CM = new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000);

    /** Axial (type 2) and radial (type 3) shadings. */
    private static final class GradientPaint2 implements Paint {
        final boolean axial;
        final double[] c;
        final boolean e0;
        final boolean e1;
        final int[] lut;
        final AffineTransform inverse;
        final int alpha;

        GradientPaint2(boolean axial, double[] c, boolean e0, boolean e1, int[] lut, AffineTransform toDevice, int alpha) {
            this.axial = axial;
            this.c = c;
            this.e0 = e0;
            this.e1 = e1;
            this.lut = lut;
            AffineTransform inv;
            try {
                inv = toDevice.createInverse();
            } catch (NoninvertibleTransformException e) {
                inv = new AffineTransform();
            }
            this.inverse = inv;
            this.alpha = alpha;
        }

        @Override
        public int getTransparency() {
            return Transparency.TRANSLUCENT;
        }

        @Override
        public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds,
                                          AffineTransform xform, RenderingHints hints) {
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
                            double t = axial ? axialT(pt[0], pt[1]) : radialT(pt[0], pt[1]);
                            int v;
                            if (Double.isNaN(t)) {
                                v = 0;
                            } else {
                                int idx = (int) Math.round(t * (lut.length - 1));
                                v = (alpha << 24) | lut[Math.max(0, Math.min(lut.length - 1, idx))];
                            }
                            px[j * w + i] = v;
                        }
                    }
                    r.setDataElements(0, 0, w, h, px);
                    return r;
                }
            };
        }

        private double axialT(double px, double py) {
            double dx = c[2] - c[0];
            double dy = c[3] - c[1];
            double den = dx * dx + dy * dy;
            double t = den == 0 ? 0 : ((px - c[0]) * dx + (py - c[1]) * dy) / den;
            return clampT(t);
        }

        private double clampT(double t) {
            if (t < 0) {
                return e0 ? 0 : Double.NaN;
            }
            if (t > 1) {
                return e1 ? 1 : Double.NaN;
            }
            return t;
        }

        /** The largest s with point on circle(s) = lerp of the two circles, as the spec defines it. */
        private double radialT(double px, double py) {
            double x0 = c[0];
            double y0 = c[1];
            double r0 = c[2];
            double x1 = c[3];
            double y1 = c[4];
            double r1 = c[5];
            double cdx = x1 - x0;
            double cdy = y1 - y0;
            double dr = r1 - r0;
            double pdx = px - x0;
            double pdy = py - y0;
            double a = cdx * cdx + cdy * cdy - dr * dr;
            double b = pdx * cdx + pdy * cdy + r0 * dr;
            double cc = pdx * pdx + pdy * pdy - r0 * r0;
            double s;
            if (Math.abs(a) < 1e-9) {
                if (Math.abs(b) < 1e-12) {
                    return Double.NaN;
                }
                s = cc / (2 * b);
                if (r0 + s * dr < 0) {
                    return Double.NaN;
                }
            } else {
                double disk = b * b - a * cc;
                if (disk < 0) {
                    return Double.NaN;
                }
                double sq = Math.sqrt(disk);
                double s1 = (b + sq) / a;
                double s2 = (b - sq) / a;
                double hi = Math.max(s1, s2);
                double lo = Math.min(s1, s2);
                s = hi;
                if (r0 + s * dr < 0 || (s > 1 && !e1) || (s < 0 && !e0)) {
                    s = lo;
                    if (r0 + s * dr < 0) {
                        return Double.NaN;
                    }
                }
            }
            return clampT(s);
        }
    }

    /** Type 1: a function of (x, y), sampled per pixel. */
    private static final class FunctionPaint implements Paint {
        final PdfColorSpace cs;
        final PdfFunction fn;
        final AffineTransform inverse;
        final double[] domain;
        final int alpha;

        FunctionPaint(PdfDict sh, PdfColorSpace cs, PdfFunction fn, AffineTransform toDevice, int alpha) {
            this.cs = cs;
            this.fn = fn;
            this.alpha = alpha;
            AffineTransform m = new AffineTransform(toDevice);
            PdfArray mat = sh.getArray("Matrix");
            if (mat != null && mat.size() == 6) {
                double[] v = mat.toDoubles();
                m.concatenate(new AffineTransform(v));
            }
            AffineTransform inv;
            try {
                inv = m.createInverse();
            } catch (NoninvertibleTransformException e) {
                inv = new AffineTransform();
            }
            inverse = inv;
            PdfArray dom = sh.getArray("Domain");
            domain = dom == null ? new double[] { 0, 1, 0, 1 } : dom.toDoubles();
        }

        @Override
        public int getTransparency() {
            return Transparency.TRANSLUCENT;
        }

        @Override
        public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds,
                                          AffineTransform xform, RenderingHints hints) {
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
                    float[] col = new float[cs.components()];
                    for (int j = 0; j < h; j++) {
                        for (int i = 0; i < w; i++) {
                            pt[0] = x + i + 0.5;
                            pt[1] = y + j + 0.5;
                            inverse.transform(pt, 0, pt, 0, 1);
                            if (domain.length >= 4 && (pt[0] < domain[0] || pt[0] > domain[1] || pt[1] < domain[2]
                                                       || pt[1] > domain[3])) {
                                continue;
                            }
                            if (fn == null) {
                                continue;
                            }
                            double[] o = fn.eval(pt);
                            for (int k = 0; k < col.length && k < o.length; k++) {
                                col[k] = (float) o[k];
                            }
                            px[j * w + i] = (alpha << 24) | cs.rgb(col);
                        }
                    }
                    r.setDataElements(0, 0, w, h, px);
                    return r;
                }
            };
        }
    }

    /* ------------------------------------------------------------- meshes (types 4 to 7) */

    /** Draws a mesh shading with the graphics' current transform set to shading space. */
    public static void fillMesh(Graphics2D g, PdfStream sh, PdfDict resources, Map<Object, PdfColorSpace> csCache,
                                float alpha) {
        int type = sh.getInt("ShadingType", 0);
        PdfColorSpace cs = PdfColorSpace.resolve(sh.get("ColorSpace", "CS"), resources, csCache);
        PdfFunction fn = PdfFunction.parse(sh.get("Function"));
        int bpc = sh.getInt("BitsPerCoordinate", 16);
        int bpcomp = sh.getInt("BitsPerComponent", 8);
        int bpf = sh.getInt("BitsPerFlag", 8);
        double[] decode = sh.getArray("Decode") == null ? null : sh.getArray("Decode").toDoubles();
        if (decode == null || decode.length < 6) {
            return;
        }
        int nc = fn != null ? 1 : cs.components();
        MeshReader r = new MeshReader(sh.getDecoded(), bpc, bpcomp, bpf, decode, nc);
        int a = Math.round(alpha * 255);
        try {
            if (type == 4 || type == 5) {
                java.util.List<double[]> verts = new java.util.ArrayList<>();
                int perRow = sh.getInt("VerticesPerRow", 2);
                if (type == 4) {
                    double[] p1 = null;
                    double[] p2 = null;
                    double[] p3;
                    while (r.more()) {
                        int flag = r.flag();
                        double[] v = r.vertex();
                        if (flag == 0 || p1 == null) {
                            double[] v2 = r.vertexWithFlag();
                            double[] v3 = r.vertexWithFlag();
                            p1 = v;
                            p2 = v2;
                            p3 = v3;
                        } else if (flag == 1) {
                            p1 = p2;
                            p2 = verts.get(verts.size() - 1);
                            p3 = v;
                        } else {
                            p2 = verts.get(verts.size() - 1);
                            p3 = v;
                        }
                        verts.add(p3);
                        triangle(g, p1, p2, p3, cs, fn, a, 0);
                        if (flag != 0) {
                            continue;
                        }
                    }
                } else {
                    while (r.more()) {
                        verts.add(r.vertex());
                    }
                    int rows = verts.size() / Math.max(2, perRow);
                    for (int y = 0; y + 1 < rows; y++) {
                        for (int x = 0; x + 1 < perRow; x++) {
                            double[] v00 = verts.get(y * perRow + x);
                            double[] v01 = verts.get(y * perRow + x + 1);
                            double[] v10 = verts.get((y + 1) * perRow + x);
                            double[] v11 = verts.get((y + 1) * perRow + x + 1);
                            triangle(g, v00, v01, v10, cs, fn, a, 0);
                            triangle(g, v01, v11, v10, cs, fn, a, 0);
                        }
                    }
                }
            } else if (type == 6 || type == 7) {
                double[][] prev = null;
                double[][] prevCol = null;
                int pts = type == 6 ? 12 : 16;
                while (r.more()) {
                    int flag = r.flag();
                    double[][] p = new double[16][];
                    double[][] col = new double[4][];
                    int start = 0;
                    int cstart = 0;
                    if (flag != 0 && prev != null) {
                        // the edge shared with the previous patch
                        int[][] edge = { {}, { 3, 4, 5, 6 }, { 6, 7, 8, 9 }, { 9, 10, 11, 0 } };
                        int[] e = edge[Math.min(3, flag)];
                        for (int i = 0; i < 4; i++) {
                            p[i] = prev[e[i]];
                        }
                        int[][] cedge = { {}, { 1, 2 }, { 2, 3 }, { 3, 0 } };
                        int[] ce = cedge[Math.min(3, flag)];
                        col[0] = prevCol[ce[0]];
                        col[1] = prevCol[ce[1]];
                        start = 4;
                        cstart = 2;
                    }
                    for (int i = start; i < pts; i++) {
                        p[i] = r.point();
                    }
                    for (int i = cstart; i < 4; i++) {
                        col[i] = r.color();
                    }
                    patch(g, p, col, cs, fn, a);
                    prev = p;
                    prevCol = col;
                }
            }
        } catch (RuntimeException e) {
            // a truncated mesh draws what it had
        }
    }

    private static void triangle(Graphics2D g, double[] p1, double[] p2, double[] p3, PdfColorSpace cs, PdfFunction fn,
                                 int alpha, int depth) {
        // subdivide while the colors differ much, then fill flat
        if (depth < 4 && colorDistance(p1, p2, p3) > 0.04) {
            double[] m12 = mid(p1, p2);
            double[] m23 = mid(p2, p3);
            double[] m31 = mid(p3, p1);
            triangle(g, p1, m12, m31, cs, fn, alpha, depth + 1);
            triangle(g, m12, p2, m23, cs, fn, alpha, depth + 1);
            triangle(g, m31, m23, p3, cs, fn, alpha, depth + 1);
            triangle(g, m12, m23, m31, cs, fn, alpha, depth + 1);
            return;
        }
        double[] avg = new double[p1.length];
        for (int i = 0; i < avg.length; i++) {
            avg[i] = (p1[i] + p2[i] + p3[i]) / 3;
        }
        g.setColor(new Color(color(avg, cs, fn) | (alpha << 24), true));
        GeneralPath t = new GeneralPath();
        t.moveTo(p1[0], p1[1]);
        t.lineTo(p2[0], p2[1]);
        t.lineTo(p3[0], p3[1]);
        t.closePath();
        g.fill(t);
        g.draw(t);
    }

    private static double colorDistance(double[] a, double[] b, double[] c) {
        double d = 0;
        for (int i = 2; i < a.length; i++) {
            d = Math.max(d, Math.max(Math.abs(a[i] - b[i]), Math.max(Math.abs(b[i] - c[i]), Math.abs(a[i] - c[i]))));
        }
        return d;
    }

    private static double[] mid(double[] a, double[] b) {
        double[] m = new double[a.length];
        for (int i = 0; i < m.length; i++) {
            m[i] = (a[i] + b[i]) / 2;
        }
        return m;
    }

    private static int color(double[] v, PdfColorSpace cs, PdfFunction fn) {
        if (fn != null) {
            return colorAt(cs, fn, v[2]);
        }
        float[] c = new float[cs.components()];
        for (int i = 0; i < c.length && i + 2 < v.length; i++) {
            c[i] = (float) v[i + 2];
        }
        return cs.rgb(c);
    }

    /** A Coons or tensor patch, evaluated on a grid and filled cell by cell. */
    private static void patch(Graphics2D g, double[][] p, double[][] col, PdfColorSpace cs, PdfFunction fn, int alpha) {
        // boundary curves of the Coons patch (points 0..11, counter-clockwise from the bottom-left)
        double[][] b = new double[][] { p[0], p[1], p[2], p[3] };
        double[][] right = new double[][] { p[3], p[4], p[5], p[6] };
        double[][] top = new double[][] { p[9], p[8], p[7], p[6] };
        double[][] left = new double[][] { p[0], p[11], p[10], p[9] };
        int n = 12;
        double[][][] grid = new double[n + 1][n + 1][];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            for (int j = 0; j <= n; j++) {
                double v = j / (double) n;
                double[] c1 = bez(b, u);
                double[] c2 = bez(top, u);
                double[] d1 = bez(left, v);
                double[] d2 = bez(right, v);
                double x = (1 - v) * c1[0] + v * c2[0] + (1 - u) * d1[0] + u * d2[0]
                    - ((1 - u) * (1 - v) * p[0][0] + u * (1 - v) * p[3][0] + (1 - u) * v * p[9][0] + u * v * p[6][0]);
                double y = (1 - v) * c1[1] + v * c2[1] + (1 - u) * d1[1] + u * d2[1]
                    - ((1 - u) * (1 - v) * p[0][1] + u * (1 - v) * p[3][1] + (1 - u) * v * p[9][1] + u * v * p[6][1]);
                grid[i][j] = new double[] { x, y };
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double u = (i + 0.5) / n;
                double v = (j + 0.5) / n;
                double[] c = new double[col[0].length];
                for (int k = 0; k < c.length; k++) {
                    c[k] = (1 - u) * (1 - v) * col[0][k] + u * (1 - v) * col[1][k] + u * v * col[2][k] + (1 - u) * v * col[3][k];
                }
                double[] vv = new double[c.length + 2];
                System.arraycopy(c, 0, vv, 2, c.length);
                g.setColor(new Color(color(vv, cs, fn) | (alpha << 24), true));
                GeneralPath q = new GeneralPath();
                q.moveTo(grid[i][j][0], grid[i][j][1]);
                q.lineTo(grid[i + 1][j][0], grid[i + 1][j][1]);
                q.lineTo(grid[i + 1][j + 1][0], grid[i + 1][j + 1][1]);
                q.lineTo(grid[i][j + 1][0], grid[i][j + 1][1]);
                q.closePath();
                g.fill(q);
                g.draw(q);
            }
        }
    }

    private static double[] bez(double[][] c, double t) {
        double mt = 1 - t;
        double a = mt * mt * mt;
        double b = 3 * mt * mt * t;
        double cc = 3 * mt * t * t;
        double d = t * t * t;
        return new double[] { a * c[0][0] + b * c[1][0] + cc * c[2][0] + d * c[3][0],
                              a * c[0][1] + b * c[1][1] + cc * c[2][1] + d * c[3][1] };
    }

    /** Reads the bit-packed vertices of a mesh shading. */
    private static final class MeshReader {
        final byte[] d;
        final int bpc;
        final int bpcomp;
        final int bpf;
        final double[] decode;
        final int nc;
        long bit;

        MeshReader(byte[] d, int bpc, int bpcomp, int bpf, double[] decode, int nc) {
            this.d = d;
            this.bpc = bpc;
            this.bpcomp = bpcomp;
            this.bpf = bpf;
            this.decode = decode;
            this.nc = nc;
        }

        boolean more() {
            return (bit + bpf + 2L * bpc) / 8 < d.length;
        }

        long read(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = (int) (bit >> 3);
                int b = byteIdx < d.length ? d[byteIdx] & 0xff : 0;
                v = (v << 1) | ((b >> (7 - (bit & 7))) & 1);
                bit++;
            }
            return v;
        }

        void align() {
            bit = (bit + 7) & ~7L;
        }

        int flag() {
            int f = (int) read(bpf);
            return f;
        }

        double[] point() {
            double maxC = Math.pow(2, bpc) - 1;
            double x = decode[0] + read(bpc) * (decode[1] - decode[0]) / maxC;
            double y = decode[2] + read(bpc) * (decode[3] - decode[2]) / maxC;
            return new double[] { x, y };
        }

        double[] color() {
            double maxK = Math.pow(2, bpcomp) - 1;
            double[] c = new double[nc];
            for (int i = 0; i < nc; i++) {
                int di = 4 + 2 * i;
                double lo = di < decode.length ? decode[di] : 0;
                double hi = di + 1 < decode.length ? decode[di + 1] : 1;
                c[i] = lo + read(bpcomp) * (hi - lo) / maxK;
            }
            return c;
        }

        double[] vertex() {
            double[] p = point();
            double[] c = color();
            double[] v = new double[2 + c.length];
            v[0] = p[0];
            v[1] = p[1];
            System.arraycopy(c, 0, v, 2, c.length);
            align();
            return v;
        }

        double[] vertexWithFlag() {
            read(bpf);
            return vertex();
        }
    }

    /** The bounds a shading covers in its own space, when it says (BBox). */
    public static Shape bbox(PdfDict sh) {
        PdfArray b = sh.getArray("BBox");
        if (b == null || b.size() < 4) {
            return null;
        }
        double x0 = b.getDouble(0, 0);
        double y0 = b.getDouble(1, 0);
        double x1 = b.getDouble(2, 0);
        double y1 = b.getDouble(3, 0);
        return new Rectangle2D.Double(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
    }

    static Point2D unused() {
        return null;
    }
}
