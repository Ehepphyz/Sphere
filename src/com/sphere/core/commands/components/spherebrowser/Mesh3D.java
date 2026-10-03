package com.sphere.components.spherebrowser;

import java.util.Arrays;

/**
 * Triangles, segments and points in data coordinates, each vertex with its
 * colour and the value it stands for: what Sphere's 3D space draws.
 *
 * The arrays grow as a builder adds to them; nothing here knows about a
 * camera. A vertex carries the data value it was made from so that the mouse,
 * over a surface, can say what lies under it.
 */
public final class Mesh3D {

    public String name;
    /** 1 opaque; below, the faces are blended back to front. */
    public float opacity = 1f;
    public boolean visible = true;
    /** Lit by the lamps; off for a floor map or labels that keep their colour. */
    public boolean lit = true;
    /** Normals averaged over the faces around a vertex: a surface, not facets. */
    public boolean smooth;
    /** Coordinates already in the unit cube: the frame, not the data. */
    public boolean normalized;
    /** Point size in pixels, at a supersampling of one. */
    public float pointSize = 3f;
    /** Line width in pixels. */
    public float lineWidth = 1f;

    float[] pos = new float[3 * 256];
    int[] argb = new int[256];
    float[] value = new float[256];
    float[] normal;
    int nv;

    int[] tri = new int[3 * 256];
    int nt;
    int[] lin = new int[2 * 256];
    int nl;
    int[] pts = new int[256];
    int np;

    public Mesh3D(String name) {
        this.name = name;
    }

    public int vertexCount() {
        return nv;
    }

    public int triangleCount() {
        return nt;
    }

    public int lineCount() {
        return nl;
    }

    public int pointCount() {
        return np;
    }

    public boolean isEmpty() {
        return nt == 0 && nl == 0 && np == 0;
    }

    /** Adds a vertex and answers its index. */
    public int vertex(double x, double y, double z, int color, double v) {
        if (nv == argb.length) {
            final int n = argb.length * 2;
            pos = Arrays.copyOf(pos, 3 * n);
            argb = Arrays.copyOf(argb, n);
            value = Arrays.copyOf(value, n);
            if (normal != null) normal = Arrays.copyOf(normal, 3 * n);
        }
        pos[3 * nv] = (float) x;
        pos[3 * nv + 1] = (float) y;
        pos[3 * nv + 2] = (float) z;
        argb[nv] = color;
        value[nv] = (float) v;
        return nv++;
    }

    /** Sets a vertex's normal explicitly, as a field's gradient gives it. */
    public void normal(int i, double x, double y, double z) {
        if (normal == null) normal = new float[pos.length];
        if (normal.length < pos.length) normal = Arrays.copyOf(normal, pos.length);
        final double l = Math.sqrt(x * x + y * y + z * z);
        final double k = l > 1e-30 ? 1 / l : 0;
        normal[3 * i] = (float) (x * k);
        normal[3 * i + 1] = (float) (y * k);
        normal[3 * i + 2] = (float) (z * k);
    }

    public void triangle(int a, int b, int c) {
        if (3 * nt + 3 > tri.length) tri = Arrays.copyOf(tri, tri.length * 2);
        tri[3 * nt] = a;
        tri[3 * nt + 1] = b;
        tri[3 * nt + 2] = c;
        nt++;
    }

    public void quad(int a, int b, int c, int d) {
        triangle(a, b, c);
        triangle(a, c, d);
    }

    public void line(int a, int b) {
        if (2 * nl + 2 > lin.length) lin = Arrays.copyOf(lin, lin.length * 2);
        lin[2 * nl] = a;
        lin[2 * nl + 1] = b;
        nl++;
    }

    public void point(int a) {
        if (np == pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
        pts[np++] = a;
    }

    /** A box of six faces, each with its own corners so that its facets stay sharp. */
    public void box(double x0, double y0, double z0, double x1, double y1, double z1, int color, double v) {
        final double[][] c = {
            {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
            {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        final int[][] faces = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
        for (int[] f : faces) {
            final int a = vertex(c[f[0]][0], c[f[0]][1], c[f[0]][2], color, v);
            final int b = vertex(c[f[1]][0], c[f[1]][1], c[f[1]][2], color, v);
            final int d = vertex(c[f[2]][0], c[f[2]][1], c[f[2]][2], color, v);
            final int e = vertex(c[f[3]][0], c[f[3]][1], c[f[3]][2], color, v);
            quad(a, b, d, e);
        }
    }

    /** The twelve edges of a box, as segments. */
    public void boxEdges(double x0, double y0, double z0, double x1, double y1, double z1, int color) {
        final int[] v = new int[8];
        for (int k = 0; k < 8; k++) {
            v[k] = vertex((k & 1) == 0 ? x0 : x1, (k & 2) == 0 ? y0 : y1, (k & 4) == 0 ? z0 : z1, color, 0);
        }
        final int[][] e = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] s : e) line(v[s[0]], v[s[1]]);
    }

    /** The smallest box holding every vertex: x0 x1 y0 y1 z0 z1. */
    public double[] bounds() {
        final double[] b = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < nv; i++) {
            for (int a = 0; a < 3; a++) {
                final float p = pos[3 * i + a];
                if (!Float.isFinite(p)) continue;
                if (p < b[2 * a]) b[2 * a] = p;
                if (p > b[2 * a + 1]) b[2 * a + 1] = p;
            }
        }
        return b;
    }

    /** Normals averaged over the faces around each vertex, weighted by their area. */
    void computeSmoothNormals(double[] scale) {
        final float[] n = new float[3 * nv];
        for (int t = 0; t < nt; t++) {
            final int a = tri[3 * t];
            final int b = tri[3 * t + 1];
            final int c = tri[3 * t + 2];
            final double ux = (pos[3 * b] - pos[3 * a]) * scale[0];
            final double uy = (pos[3 * b + 1] - pos[3 * a + 1]) * scale[1];
            final double uz = (pos[3 * b + 2] - pos[3 * a + 2]) * scale[2];
            final double vx = (pos[3 * c] - pos[3 * a]) * scale[0];
            final double vy = (pos[3 * c + 1] - pos[3 * a + 1]) * scale[1];
            final double vz = (pos[3 * c + 2] - pos[3 * a + 2]) * scale[2];
            final float nx = (float) (uy * vz - uz * vy);
            final float ny = (float) (uz * vx - ux * vz);
            final float nz = (float) (ux * vy - uy * vx);
            for (int k : new int[]{a, b, c}) {
                n[3 * k] += nx;
                n[3 * k + 1] += ny;
                n[3 * k + 2] += nz;
            }
        }
        for (int i = 0; i < nv; i++) {
            final double l = Math.sqrt(n[3 * i] * n[3 * i] + n[3 * i + 1] * n[3 * i + 1] + n[3 * i + 2] * n[3 * i + 2]);
            if (l > 1e-30) {
                n[3 * i] /= (float) l;
                n[3 * i + 1] /= (float) l;
                n[3 * i + 2] /= (float) l;
            }
        }
        normal = n;
    }

    /* ------------------------------------------------------------------ */
    /* Writing it out                                                      */
    /* ------------------------------------------------------------------ */

    /** Wavefront OBJ with vertex colours (the common x y z r g b extension). */
    public void writeObj(StringBuilder out, int offset) {
        out.append("o ").append(name == null ? "mesh" : name.replaceAll("\\s+", "_")).append('\n');
        for (int i = 0; i < nv; i++) {
            final int c = argb[i];
            out.append(String.format(java.util.Locale.ROOT, "v %.6g %.6g %.6g %.4f %.4f %.4f%n",
                pos[3 * i], pos[3 * i + 1], pos[3 * i + 2],
                (c >> 16 & 255) / 255.0, (c >> 8 & 255) / 255.0, (c & 255) / 255.0));
        }
        for (int t = 0; t < nt; t++) {
            out.append("f ").append(tri[3 * t] + 1 + offset).append(' ').append(tri[3 * t + 1] + 1 + offset)
                .append(' ').append(tri[3 * t + 2] + 1 + offset).append('\n');
        }
        for (int l = 0; l < nl; l++) {
            out.append("l ").append(lin[2 * l] + 1 + offset).append(' ').append(lin[2 * l + 1] + 1 + offset).append('\n');
        }
    }
}
