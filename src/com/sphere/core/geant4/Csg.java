package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mesh booleans through BSP trees (the algorithm of csg.js, E. Wallace, MIT),
 * which is how a boolean solid is drawn: Geant4's own visualisation goes
 * through HepPolyhedron's BooleanProcessor the same way. Only the drawing
 * uses this; inside tests and distances of a boolean are exact, composed from
 * the operands.
 */
final class Csg {

    private Csg() {
    }

    enum Op { UNION, SUBTRACTION, INTERSECTION }

    private static final int COPLANAR = 0, FRONT = 1, BACK = 2, SPANNING = 3;

    record V(double x, double y, double z, double nx, double ny, double nz) {
        V lerp(V o, double t) {
            return new V(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t,
                         nx + (o.nx - nx) * t, ny + (o.ny - ny) * t, nz + (o.nz - nz) * t);
        }

        V flip() {
            return new V(x, y, z, -nx, -ny, -nz);
        }
    }

    static final class Plane {
        double nx, ny, nz, w;

        Plane(double nx, double ny, double nz, double w) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.w = w;
        }

        static Plane of(V a, V b, V c) {
            final double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
            final double vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            final double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l == 0) {
                return null;
            }
            nx /= l;
            ny /= l;
            nz /= l;
            return new Plane(nx, ny, nz, nx * a.x + ny * a.y + nz * a.z);
        }

        Plane flipped() {
            return new Plane(-nx, -ny, -nz, -w);
        }

        void split(Poly p, List<Poly> cf, List<Poly> cb, List<Poly> front, List<Poly> back, double eps) {
            int type = 0;
            final int n = p.v.size();
            final int[] types = new int[n];
            for (int i = 0; i < n; i++) {
                final V v = p.v.get(i);
                final double t = nx * v.x + ny * v.y + nz * v.z - w;
                final int ty = t < -eps ? BACK : t > eps ? FRONT : COPLANAR;
                type |= ty;
                types[i] = ty;
            }
            switch (type) {
                case COPLANAR -> {
                    final Plane q = p.plane;
                    (nx * q.nx + ny * q.ny + nz * q.nz > 0 ? cf : cb).add(p);
                }
                case FRONT -> front.add(p);
                case BACK -> back.add(p);
                default -> {
                    final List<V> f = new ArrayList<>(), b = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        final int j = (i + 1) % n;
                        final int ti = types[i], tj = types[j];
                        final V vi = p.v.get(i), vj = p.v.get(j);
                        if (ti != BACK) {
                            f.add(vi);
                        }
                        if (ti != FRONT) {
                            b.add(ti != BACK ? vi : vi);
                        }
                        if ((ti | tj) == SPANNING) {
                            final double di = nx * vi.x + ny * vi.y + nz * vi.z - w;
                            final double dj = nx * vj.x + ny * vj.y + nz * vj.z - w;
                            final double t = di / (di - dj);
                            final V m = vi.lerp(vj, t);
                            f.add(m);
                            b.add(m);
                        }
                    }
                    if (f.size() >= 3) {
                        front.add(new Poly(f, p.plane));
                    }
                    if (b.size() >= 3) {
                        back.add(new Poly(b, p.plane));
                    }
                }
            }
        }
    }

    static final class Poly {
        final List<V> v;
        final Plane plane;

        Poly(List<V> v, Plane plane) {
            this.v = v;
            this.plane = plane;
        }

        double area() {
            final V o = v.get(0);
            double x = 0, y = 0, z = 0;
            for (int i = 1; i + 1 < v.size(); i++) {
                final V a = v.get(i), b = v.get(i + 1);
                final double ax = a.x - o.x, ay = a.y - o.y, az = a.z - o.z;
                final double bx = b.x - o.x, by = b.y - o.y, bz = b.z - o.z;
                x += ay * bz - az * by;
                y += az * bx - ax * bz;
                z += ax * by - ay * bx;
            }
            return Math.sqrt(x * x + y * y + z * z) / 2;
        }

        /** True when the polygon's box meets b. */
        boolean meets(BBox b) {
            double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
            for (V p : v) {
                x0 = Math.min(x0, p.x);
                y0 = Math.min(y0, p.y);
                z0 = Math.min(z0, p.z);
                x1 = Math.max(x1, p.x);
                y1 = Math.max(y1, p.y);
                z1 = Math.max(z1, p.z);
            }
            return x1 >= b.minX() && x0 <= b.maxX() && y1 >= b.minY() && y0 <= b.maxY() && z1 >= b.minZ() && z0 <= b.maxZ();
        }

        Poly flipped() {
            final List<V> r = new ArrayList<>(v.size());
            for (int i = v.size() - 1; i >= 0; i--) {
                r.add(v.get(i).flip());
            }
            return new Poly(r, plane.flipped());
        }
    }

    static final class Node {
        Plane plane;
        Node front, back;
        List<Poly> polys = new ArrayList<>();
        final double eps;

        Node(double eps) {
            this.eps = eps;
        }

        Node(List<Poly> list, double eps) {
            this.eps = eps;
            build(list);
        }

        void invert() {
            final List<Poly> r = new ArrayList<>(polys.size());
            for (Poly p : polys) {
                r.add(p.flipped());
            }
            polys = r;
            if (plane != null) {
                plane = plane.flipped();
            }
            if (front != null) {
                front.invert();
            }
            if (back != null) {
                back.invert();
            }
            final Node t = front;
            front = back;
            back = t;
        }

        List<Poly> clip(List<Poly> list) {
            if (plane == null) {
                return new ArrayList<>(list);
            }
            List<Poly> f = new ArrayList<>(), b = new ArrayList<>();
            for (Poly p : list) {
                plane.split(p, f, b, f, b, eps);
            }
            if (front != null) {
                f = front.clip(f);
            }
            if (back != null) {
                b = back.clip(b);
            } else {
                b = new ArrayList<>();
            }
            f.addAll(b);
            return f;
        }

        /**
         * clip(list), with the polygons that do not meet the tree's box (b)
         * left whole: outside its solid, so kept -- or, the tree inverted,
         * inside, so dropped.
         */
        List<Poly> clip(List<Poly> list, BBox b, boolean inverted) {
            final List<Poly> out = new ArrayList<>(list.size());
            final List<Poly> one = new ArrayList<>(1);
            for (Poly p : list) {
                if (!p.meets(b)) {
                    if (!inverted) {
                        out.add(p);
                    }
                    continue;
                }
                one.clear();
                one.add(p);
                final List<Poly> kept = clip(one);
                // the tree's planes split whatever crosses them, far from its polygons too: a polygon
                // kept in all its pieces goes on whole, or the pieces multiply at every boolean
                double area = 0;
                for (Poly k : kept) {
                    area += k.area();
                }
                if (kept.size() > 1 && Math.abs(area - p.area()) <= 1e-9 * p.area()) {
                    out.add(p);
                } else {
                    out.addAll(kept);
                }
            }
            return out;
        }

        void build(List<Poly> list) {
            if (list.isEmpty()) {
                return;
            }
            if (plane == null) {
                plane = list.get(0).plane;
            }
            final List<Poly> f = new ArrayList<>(), b = new ArrayList<>();
            for (Poly p : list) {
                plane.split(p, polys, polys, f, b, eps);
            }
            if (!f.isEmpty()) {
                if (front == null) {
                    front = new Node(eps);
                }
                front.build(f);
            }
            if (!b.isEmpty()) {
                if (back == null) {
                    back = new Node(eps);
                }
                back.build(b);
            }
        }
    }

    static List<Poly> polys(Mesh m) {
        final List<Poly> out = new ArrayList<>(m.triangleCount());
        for (int i = 0; i < m.tri.length; i += 3) {
            final V a = vertex(m, m.tri[i]), b = vertex(m, m.tri[i + 1]), c = vertex(m, m.tri[i + 2]);
            final Plane p = Plane.of(a, b, c);
            if (p != null) {
                final List<V> l = new ArrayList<>(3);
                l.add(a);
                l.add(b);
                l.add(c);
                out.add(new Poly(l, p));
            }
        }
        return out;
    }

    private static V vertex(Mesh m, int i) {
        return new V(m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2], m.nrm[i * 3], m.nrm[i * 3 + 1], m.nrm[i * 3 + 2]);
    }

    static Mesh mesh(List<Poly> list) {
        final Mesh.Builder mb = new Mesh.Builder();
        for (Poly p : list) {
            final int base = mb.vertexCount();
            for (V v : p.v) {
                mb.vertex(v.x, v.y, v.z, v.nx, v.ny, v.nz);
            }
            for (int k = 1; k + 1 < p.v.size(); k++) {
                mb.triangle(base, base + k, base + k + 1);
            }
        }
        return mb.build();
    }

    /**
     * The boolean of two closed meshes. Runs on a thread with a deep stack,
     * since the BSP recursion follows the depth of the tree.
     */
    static Mesh apply(Op op, Mesh a, Mesh b) {
        if (a.isEmpty()) {
            return op == Op.UNION ? b : Mesh.EMPTY;
        }
        if (b.isEmpty()) {
            return op == Op.INTERSECTION ? Mesh.EMPTY : a;
        }
        final BBox ba = a.bounds(), bb = b.bounds();
        if (!ba.overlaps(bb)) {
            if (op == Op.SUBTRACTION) {
                return a;
            }
            if (op == Op.INTERSECTION) {
                return Mesh.EMPTY;
            }
            final Mesh.Builder mb = new Mesh.Builder();
            mb.append(a);
            mb.append(b);
            return mb.build();
        }
        final double eps = Math.max(1e-9, ba.union(bb).diagonal() * 1e-7);
        final AtomicReference<Mesh> out = new AtomicReference<>(Mesh.EMPTY);
        final Thread t = new Thread(null, () -> out.set(run(op, a, b, eps)), "g4-csg", 256L << 20);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    /**
     * csg.js's sequence of clips, with the trees used only to tell inside
     * from outside: the polygons drawn are the operands' own, cut only by the
     * other operand's planes and only where its box is. Fed back the trees'
     * polygons, as csg.js does, a chain of booleans (a volume less forty
     * neighbours, as overlapping ROOT placements become) splits the whole
     * surface again at every step and runs out of memory.
     */
    private static Mesh run(Op op, Mesh ma, Mesh mb, double eps) {
        try {
            final List<Poly> pa = polys(ma), pb = polys(mb);
            final Node a = new Node(pa, eps);
            final Node b = new Node(pb, eps);
            final BBox boxA = ma.bounds().grow(4 * eps), boxB = mb.bounds().grow(4 * eps);
            final List<Poly> out;
            switch (op) {
                case UNION -> {
                    out = b.clip(pa, boxB, false);
                    final List<Poly> b1 = a.clip(pb, boxA, false);
                    out.addAll(flipped(a.clip(flipped(b1), boxA, false)));
                }
                case SUBTRACTION -> {
                    a.invert();
                    out = flipped(b.clip(flipped(pa), boxB, false));
                    final List<Poly> b1 = a.clip(pb, boxA, true);
                    out.addAll(a.clip(flipped(b1), boxA, true));
                }
                default -> {
                    a.invert();
                    final List<Poly> b1 = a.clip(pb, boxA, true);
                    b.invert();
                    out = flipped(b.clip(flipped(pa), boxB, true));
                    out.addAll(flipped(a.clip(flipped(b1), boxA, true)));
                }
            }
            return mesh(out);
        } catch (StackOverflowError | OutOfMemoryError | RuntimeException e) {
            return ma;      // drawn uncut rather than not at all
        }
    }

    /**
     * body − c1 − c2 − ... − ck, the steps of the chain worked out together:
     * the body's tree is built once, not the tree of what is left at every
     * step -- "inside what is left" is inside the body and outside the
     * cutters before.
     */
    static Mesh subtractAll(Mesh body, List<Mesh> cutters) {
        if (body.isEmpty()) {
            return Mesh.EMPTY;
        }
        final BBox bb = body.bounds();
        BBox all = bb;
        final List<Mesh> near = new ArrayList<>();
        for (Mesh c : cutters) {
            if (!c.isEmpty() && c.bounds().overlaps(bb)) {
                near.add(c);
                all = all.union(c.bounds());
            }
        }
        if (near.isEmpty()) {
            return body;
        }
        final double eps = Math.max(1e-9, all.diagonal() * 1e-7);
        final AtomicReference<Mesh> out = new AtomicReference<>(body);
        final Thread t = new Thread(null, () -> out.set(runAll(body, near, eps)), "g4-csg", 256L << 20);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    private static Mesh runAll(Mesh body, List<Mesh> cutters, double eps) {
        try {
            final List<Poly> pa = polys(body);
            final Node inBody = new Node(pa, eps);
            inBody.invert();
            final BBox boxBody = body.bounds().grow(4 * eps);
            final int k = cutters.size();
            final Node[] c = new Node[k];
            final BBox[] box = new BBox[k];
            List<Poly> out = pa;
            for (int i = 0; i < k; i++) {
                final List<Poly> pc = polys(cutters.get(i));
                c[i] = new Node(pc, eps);
                box[i] = cutters.get(i).bounds().grow(4 * eps);
                // what is left so far loses what is in cutter i ...
                out = flipped(c[i].clip(flipped(out), box[i], false));
                // ... and gains cutter i's wall where it is inside what was left
                final List<Poly> wall = left(pc, inBody, boxBody, c, box, i);
                out.addAll(left(flipped(wall), inBody, boxBody, c, box, i));
            }
            return mesh(out);
        } catch (StackOverflowError | OutOfMemoryError | RuntimeException e) {
            return body;
        }
    }

    /** The polygons kept where they are inside the body and outside the first n cutters. */
    private static List<Poly> left(List<Poly> list, Node inBody, BBox boxBody, Node[] c, BBox[] box, int n) {
        List<Poly> l = inBody.clip(list, boxBody, true);
        for (int j = 0; j < n && !l.isEmpty(); j++) {
            l = c[j].clip(l, box[j], false);
        }
        return l;
    }

    private static List<Poly> flipped(List<Poly> list) {
        final List<Poly> out = new ArrayList<>(list.size());
        for (Poly p : list) {
            out.add(p.flipped());
        }
        return out;
    }
}
