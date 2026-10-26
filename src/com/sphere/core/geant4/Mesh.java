package com.sphere.core.geant4;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A triangle mesh: what a solid looks like on screen, the way Geant4's
 * visualisation sees a solid through its G4Polyhedron.
 *
 * Positions and normals are float, since they only feed the rasterizer and the
 * picking; analysis never measures a mesh, it asks the solid itself.
 */
public final class Mesh {

    public final float[] pos;
    public final float[] nrm;
    public final int[] tri;
    private float[] featureEdges;

    public Mesh(float[] pos, float[] nrm, int[] tri) {
        this.pos = pos;
        this.nrm = nrm;
        this.tri = tri;
    }

    public static final Mesh EMPTY = new Mesh(new float[0], new float[0], new int[0]);

    public int vertexCount() {
        return pos.length / 3;
    }

    public int triangleCount() {
        return tri.length / 3;
    }

    public boolean isEmpty() {
        return tri.length == 0;
    }

    public BBox bounds() {
        if (pos.length == 0) {
            return BBox.EMPTY;
        }
        double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
        for (int i = 0; i < pos.length; i += 3) {
            x0 = Math.min(x0, pos[i]);
            x1 = Math.max(x1, pos[i]);
            y0 = Math.min(y0, pos[i + 1]);
            y1 = Math.max(y1, pos[i + 1]);
            z0 = Math.min(z0, pos[i + 2]);
            z1 = Math.max(z1, pos[i + 2]);
        }
        return new BBox(x0, y0, z0, x1, y1, z1);
    }

    public Mesh transformed(Xform t) {
        final float[] p = new float[pos.length];
        final float[] n = new float[nrm.length];
        final double[] r = t.r;
        for (int i = 0; i < pos.length; i += 3) {
            final double x = pos[i], y = pos[i + 1], z = pos[i + 2];
            p[i] = (float) (r[0] * x + r[1] * y + r[2] * z + t.tx);
            p[i + 1] = (float) (r[3] * x + r[4] * y + r[5] * z + t.ty);
            p[i + 2] = (float) (r[6] * x + r[7] * y + r[8] * z + t.tz);
            final double a = nrm[i], b = nrm[i + 1], c = nrm[i + 2];
            n[i] = (float) (r[0] * a + r[1] * b + r[2] * c);
            n[i + 1] = (float) (r[3] * a + r[4] * b + r[5] * c);
            n[i + 2] = (float) (r[6] * a + r[7] * b + r[8] * c);
        }
        final Mesh m = new Mesh(p, n, tri);
        if (featureEdges != null) {
            final float[] e = new float[featureEdges.length];
            for (int i = 0; i < e.length; i += 3) {
                final double x = featureEdges[i], y = featureEdges[i + 1], z = featureEdges[i + 2];
                e[i] = (float) (r[0] * x + r[1] * y + r[2] * z + t.tx);
                e[i + 1] = (float) (r[3] * x + r[4] * y + r[5] * z + t.ty);
                e[i + 2] = (float) (r[6] * x + r[7] * y + r[8] * z + t.tz);
            }
            m.featureEdges = e;
        }
        return m;
    }

    /**
     * A coarse stand-in, for drawing a volume a few pixels wide: the vertices clustered on a grid of about
     * `cells` cells along the longest side, each cluster merged into its centroid, the triangles that
     * collapse dropped (Rossignac and Borrel's vertex clustering). The outline survives with a fraction of
     * the triangles; faces come out flat, normals recomputed. This mesh itself when that would not shrink it.
     */
    public synchronized Mesh simplified(int cells) {
        // kept: meshes are shared by every solid of a shape (MeshCache), and the view asks for one size only
        if (simplified == null || simplifiedCells != cells) {
            simplified = simplify(cells);
            simplifiedCells = cells;
        }
        return simplified;
    }

    private Mesh simplified;
    private int simplifiedCells;

    private Mesh simplify(int cells) {
        final int nt = tri.length / 3;
        if (nt <= 24) {
            return this;
        }
        final BBox b = bounds();
        final Vec3 sz = b.size();
        final double cell = Math.max(sz.x(), Math.max(sz.y(), sz.z())) / Math.max(1, cells);
        if (!(cell > 0)) {
            return this;
        }
        final int gx = Math.max(1, (int) Math.ceil(sz.x() / cell)), gy = Math.max(1, (int) Math.ceil(sz.y() / cell));
        final int gz = Math.max(1, (int) Math.ceil(sz.z() / cell));
        final int nv = vertexCount();
        final int[] cluster = new int[nv];
        final Map<Integer, Integer> ids = new HashMap<>();
        double[] sum = new double[64 * 3];
        int[] count = new int[64];
        for (int i = 0; i < nv; i++) {
            final double x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
            final int ix = Math.min(gx - 1, (int) ((x - b.minX()) / cell));
            final int iy = Math.min(gy - 1, (int) ((y - b.minY()) / cell));
            final int iz = Math.min(gz - 1, (int) ((z - b.minZ()) / cell));
            final int id = ids.computeIfAbsent((ix * gy + iy) * gz + iz, k -> ids.size());
            if (id >= count.length) {
                count = Arrays.copyOf(count, count.length * 2);
                sum = Arrays.copyOf(sum, sum.length * 2);
            }
            sum[id * 3] += x;
            sum[id * 3 + 1] += y;
            sum[id * 3 + 2] += z;
            count[id]++;
            cluster[i] = id;
        }
        final int nc = ids.size();
        final Vec3[] centre = new Vec3[nc];
        for (int c = 0; c < nc; c++) {
            centre[c] = new Vec3(sum[c * 3] / count[c], sum[c * 3 + 1] / count[c], sum[c * 3 + 2] / count[c]);
        }
        final Builder mb = new Builder();
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int t = 0; t < nt; t++) {
            int a = cluster[tri[t * 3]], bb = cluster[tri[t * 3 + 1]], c = cluster[tri[t * 3 + 2]];
            if (a == bb || bb == c || a == c) {
                continue;
            }
            // the same triangle once, whichever corner it starts from (the winding is kept)
            while (a > bb || a > c) {
                final int s = a;
                a = bb;
                bb = c;
                c = s;
            }
            if (seen.add(((long) a * nc + bb) * nc + c)) {
                mb.flatTriangle(centre[a], centre[bb], centre[c]);
            }
        }
        final Mesh m = mb.build();
        return m.tri.length > 0 && m.tri.length < tri.length ? m : this;
    }

    /** Surface area of the triangles. */
    public double area() {
        double a = 0;
        for (int i = 0; i < tri.length; i += 3) {
            a += triArea(tri[i], tri[i + 1], tri[i + 2]);
        }
        return a;
    }

    double triArea(int ia, int ib, int ic) {
        final double ax = pos[ia * 3], ay = pos[ia * 3 + 1], az = pos[ia * 3 + 2];
        final double ux = pos[ib * 3] - ax, uy = pos[ib * 3 + 1] - ay, uz = pos[ib * 3 + 2] - az;
        final double vx = pos[ic * 3] - ax, vy = pos[ic * 3 + 1] - ay, vz = pos[ic * 3 + 2] - az;
        final double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Enclosed volume by the divergence theorem; meaningful for a closed, outward mesh. */
    public double volume() {
        double v = 0;
        for (int i = 0; i < tri.length; i += 3) {
            final int a = tri[i] * 3, b = tri[i + 1] * 3, c = tri[i + 2] * 3;
            v += pos[a] * (pos[b + 1] * pos[c + 2] - pos[b + 2] * pos[c + 1])
               - pos[a + 1] * (pos[b] * pos[c + 2] - pos[b + 2] * pos[c])
               + pos[a + 2] * (pos[b] * pos[c + 1] - pos[b + 1] * pos[c]);
        }
        return v / 6.0;
    }

    /**
     * The edges a CAD view outlines: borders, and creases sharper than 25
     * degrees. Coplanar triangles of one face and the facets of a smooth
     * surface are not outlined. Returned as segment pairs, xyz xyz.
     */
    public synchronized float[] featureEdges() {
        if (featureEdges == null) {
            featureEdges = computeFeatureEdges(Math.cos(Math.toRadians(35)));
        }
        return featureEdges;
    }

    private float[] computeFeatureEdges(double cosLimit) {
        final int nt = triangleCount();
        if (nt == 0) {
            return new float[0];
        }
        // Weld positions, since flat faces carry their own copies of shared corners.
        final int nv = vertexCount();
        final int[] weld = new int[nv];
        final Map<Cell, Integer> grid = new HashMap<>(nv * 2);
        final BBox b = bounds();
        final double q = Math.max(b.diagonal(), 1e-9) * 2e-6;
        int unique = 0;
        for (int i = 0; i < nv; i++) {
            final Cell key = new Cell(Math.round(pos[i * 3] / q), Math.round(pos[i * 3 + 1] / q),
                Math.round(pos[i * 3 + 2] / q));
            Integer w = grid.get(key);
            if (w == null) {
                w = unique++;
                grid.put(key, w);
            }
            weld[i] = w;
        }
        // Face normals
        final double[] fn = new double[nt * 3];
        for (int t = 0; t < nt; t++) {
            final int a = tri[t * 3] * 3, bb = tri[t * 3 + 1] * 3, c = tri[t * 3 + 2] * 3;
            final double ux = pos[bb] - pos[a], uy = pos[bb + 1] - pos[a + 1], uz = pos[bb + 2] - pos[a + 2];
            final double vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            final double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0) {
                nx /= l;
                ny /= l;
                nz /= l;
            }
            fn[t * 3] = nx;
            fn[t * 3 + 1] = ny;
            fn[t * 3 + 2] = nz;
        }
        // Edge -> first triangle, second triangle
        final Map<Long, int[]> edges = new HashMap<>(nt * 3);
        for (int t = 0; t < nt; t++) {
            for (int k = 0; k < 3; k++) {
                final int i0 = tri[t * 3 + k], i1 = tri[t * 3 + (k + 1) % 3];
                final int w0 = weld[i0], w1 = weld[i1];
                if (w0 == w1) {
                    continue;
                }
                final long key = w0 < w1 ? ((long) w0 << 32) | w1 : ((long) w1 << 32) | w0;
                final int[] e = edges.get(key);
                if (e == null) {
                    edges.put(key, new int[] {t, -1, i0, i1});
                } else if (e[1] < 0) {
                    e[1] = t;
                } else {
                    e[1] = -2; // non-manifold: always outline
                }
            }
        }
        // Edges with a single triangle: true borders, or seams where a boolean split one
        // face against another (T-junctions). A seam has a collinear, overlapping partner
        // whose triangle continues the surface smoothly; a border or a crease does not.
        final java.util.Set<int[]> seams = new java.util.HashSet<>();
        final java.util.List<int[]> open = new java.util.ArrayList<>();
        for (int[] e : edges.values()) {
            if (e[1] == -1) {
                open.add(e);
            }
        }
        if (!open.isEmpty() && open.size() < 200_000) {
            final Map<Cell, java.util.List<int[]>> lines = new HashMap<>();
            final double qd = 1e-3;
            for (int[] e : open) {
                final Cell k = lineKey(e, qd, q * 50);
                lines.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(e);
            }
            for (java.util.List<int[]> group : lines.values()) {
                if (group.size() < 2) {
                    continue;
                }
                for (int i = 0; i < group.size(); i++) {
                    final int[] a = group.get(i);
                    for (int j = 0; j < group.size(); j++) {
                        if (i == j) {
                            continue;
                        }
                        final int[] o = group.get(j);
                        if (overlaps(a, o, q * 50)) {
                            final int t0 = a[0], t1 = o[0];
                            final double d = fn[t0 * 3] * fn[t1 * 3] + fn[t0 * 3 + 1] * fn[t1 * 3 + 1]
                                + fn[t0 * 3 + 2] * fn[t1 * 3 + 2];
                            if (d >= cosLimit) {
                                seams.add(a);
                                break;
                            }
                        }
                    }
                }
            }
        }
        float[] out = new float[256];
        int n = 0;
        for (int[] e : edges.values()) {
            boolean keep;
            if (e[1] < 0) {
                keep = !seams.contains(e);
            } else {
                final int t0 = e[0], t1 = e[1];
                final double l0 = fn[t0 * 3] * fn[t0 * 3] + fn[t0 * 3 + 1] * fn[t0 * 3 + 1] + fn[t0 * 3 + 2] * fn[t0 * 3 + 2];
                final double l1 = fn[t1 * 3] * fn[t1 * 3] + fn[t1 * 3 + 1] * fn[t1 * 3 + 1] + fn[t1 * 3 + 2] * fn[t1 * 3 + 2];
                final double d = fn[t0 * 3] * fn[t1 * 3] + fn[t0 * 3 + 1] * fn[t1 * 3 + 1]
                    + fn[t0 * 3 + 2] * fn[t1 * 3 + 2];
                // A degenerate triangle (a pole, a sliver) says nothing about a crease
                keep = l0 > 0.5 && l1 > 0.5 && d < cosLimit;
            }
            if (keep) {
                if (n + 6 > out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                final int a = e[2] * 3, c = e[3] * 3;
                out[n++] = pos[a];
                out[n++] = pos[a + 1];
                out[n++] = pos[a + 2];
                out[n++] = pos[c];
                out[n++] = pos[c + 1];
                out[n++] = pos[c + 2];
            }
        }
        return Arrays.copyOf(out, n);
    }

    private record Cell(long x, long y, long z) {
    }

    /** A key for the infinite line through an edge: its direction and its point nearest the origin. */
    private Cell lineKey(int[] e, double qd, double qp) {
        final int a = e[2] * 3, b = e[3] * 3;
        double dx = pos[b] - pos[a], dy = pos[b + 1] - pos[a + 1], dz = pos[b + 2] - pos[a + 2];
        final double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= l;
        dy /= l;
        dz /= l;
        // Canonical sign
        if (dx < -1e-9 || (Math.abs(dx) <= 1e-9 && (dy < -1e-9 || (Math.abs(dy) <= 1e-9 && dz < 0)))) {
            dx = -dx;
            dy = -dy;
            dz = -dz;
        }
        final double t = pos[a] * dx + pos[a + 1] * dy + pos[a + 2] * dz;
        final double px = pos[a] - t * dx, py = pos[a + 1] - t * dy, pz = pos[a + 2] - t * dz;
        final long kd = Math.round(dx / qd) * 1_000_003L * 1_000_003L + Math.round(dy / qd) * 1_000_003L + Math.round(dz / qd);
        return new Cell(kd, Math.round((px + py * 1.7 + pz * 2.3) / qp), Math.round((px * 0.3 - py + pz * 1.1) / qp));
    }

    /** True when two edges on one line share a stretch longer than the tolerance. */
    private boolean overlaps(int[] e, int[] o, double tol) {
        final int a = e[2] * 3, b = e[3] * 3, c = o[2] * 3, d = o[3] * 3;
        final double dx = pos[b] - pos[a], dy = pos[b + 1] - pos[a + 1], dz = pos[b + 2] - pos[a + 2];
        final double l2 = dx * dx + dy * dy + dz * dz;
        if (l2 == 0) {
            return false;
        }
        final double tc = ((pos[c] - pos[a]) * dx + (pos[c + 1] - pos[a + 1]) * dy + (pos[c + 2] - pos[a + 2]) * dz) / l2;
        final double td = ((pos[d] - pos[a]) * dx + (pos[d + 1] - pos[a + 1]) * dy + (pos[d + 2] - pos[a + 2]) * dz) / l2;
        final double lo = Math.max(0, Math.min(tc, td)), hi = Math.min(1, Math.max(tc, td));
        return (hi - lo) * Math.sqrt(l2) > tol;
    }

    // ------------------------------------------------------------------

    /** Accumulates vertices and triangles. */
    public static final class Builder {
        private float[] p = new float[3 * 256];
        private float[] n = new float[3 * 256];
        private int[] t = new int[3 * 256];
        private int nv, nt;

        public int vertex(double x, double y, double z, double nx, double ny, double nz) {
            if ((nv + 1) * 3 > p.length) {
                p = Arrays.copyOf(p, p.length * 2);
                n = Arrays.copyOf(n, n.length * 2);
            }
            final int i = nv * 3;
            p[i] = (float) x;
            p[i + 1] = (float) y;
            p[i + 2] = (float) z;
            n[i] = (float) nx;
            n[i + 1] = (float) ny;
            n[i + 2] = (float) nz;
            return nv++;
        }

        public int vertex(Vec3 v, Vec3 normal) {
            return vertex(v.x(), v.y(), v.z(), normal.x(), normal.y(), normal.z());
        }

        public void triangle(int a, int b, int c) {
            if ((nt + 1) * 3 > t.length) {
                t = Arrays.copyOf(t, t.length * 2);
            }
            t[nt * 3] = a;
            t[nt * 3 + 1] = b;
            t[nt * 3 + 2] = c;
            nt++;
        }

        public void quad(int a, int b, int c, int d) {
            triangle(a, b, c);
            triangle(a, c, d);
        }

        /** A flat triangle with its own vertices and face normal; skips degenerate ones. */
        public void flatTriangle(Vec3 a, Vec3 b, Vec3 c) {
            final Vec3 nrm = b.sub(a).cross(c.sub(a));
            final double l = nrm.length();
            if (l < 1e-18) {
                return;
            }
            final Vec3 u = nrm.mul(1 / l);
            triangle(vertex(a, u), vertex(b, u), vertex(c, u));
        }

        /** A flat convex-or-not planar polygon, given counter-clockwise seen from outside. */
        public void flatPolygon(Vec3[] ring) {
            if (ring.length < 3) {
                return;
            }
            // Newell normal
            double nx = 0, ny = 0, nz = 0;
            for (int i = 0; i < ring.length; i++) {
                final Vec3 a = ring[i], b = ring[(i + 1) % ring.length];
                nx += (a.y() - b.y()) * (a.z() + b.z());
                ny += (a.z() - b.z()) * (a.x() + b.x());
                nz += (a.x() - b.x()) * (a.y() + b.y());
            }
            final Vec3 normal = new Vec3(nx, ny, nz).unit();
            if (normal.length2() == 0) {
                return;
            }
            // Project to the dominant plane and ear-clip
            final Vec3 u = normal.orthogonal();
            final Vec3 v = normal.cross(u);
            final double[] xy = new double[ring.length * 2];
            for (int i = 0; i < ring.length; i++) {
                xy[i * 2] = ring[i].dot(u);
                xy[i * 2 + 1] = ring[i].dot(v);
            }
            final int[] tris = Polygon2D.triangulate(xy);
            final int base = nv;
            for (Vec3 r : ring) {
                vertex(r, normal);
            }
            for (int i = 0; i < tris.length; i += 3) {
                triangle(base + tris[i], base + tris[i + 1], base + tris[i + 2]);
            }
        }

        public void append(Mesh m) {
            final int base = nv;
            for (int i = 0; i < m.vertexCount(); i++) {
                vertex(m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2],
                       m.nrm[i * 3], m.nrm[i * 3 + 1], m.nrm[i * 3 + 2]);
            }
            for (int i = 0; i < m.tri.length; i += 3) {
                triangle(base + m.tri[i], base + m.tri[i + 1], base + m.tri[i + 2]);
            }
        }

        public int vertexCount() {
            return nv;
        }

        public Mesh build() {
            return new Mesh(Arrays.copyOf(p, nv * 3), Arrays.copyOf(n, nv * 3), Arrays.copyOf(t, nt * 3));
        }
    }
}
