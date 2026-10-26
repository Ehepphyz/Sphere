package com.sphere.core.geant4;

/**
 * Geant4's solids bounded by planes: G4Trd, G4Para, G4Trap and G4Tet. Each is
 * an intersection of half-spaces; the signed distance is the largest plane
 * distance, exact inside and a lower bound outside.
 */
public final class Convex {

    private Convex() {
    }

    private static final Solid.Kind L = Solid.Kind.LENGTH;
    private static final Solid.Kind A = Solid.Kind.ANGLE;

    /** Faces of a hexahedron whose corners are numbered (-x-y, +x-y, -x+y, +x+y) at -z then +z. */
    static final int[][] HEX_FACES = {
        {0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};

    static final int[][] TET_FACES = {{0, 2, 1}, {0, 1, 3}, {0, 3, 2}, {1, 2, 3}};

    static Mesh boxMesh(double hx, double hy, double hz) {
        final Vec3[] v = {
            new Vec3(-hx, -hy, -hz), new Vec3(hx, -hy, -hz), new Vec3(-hx, hy, -hz), new Vec3(hx, hy, -hz),
            new Vec3(-hx, -hy, hz), new Vec3(hx, -hy, hz), new Vec3(-hx, hy, hz), new Vec3(hx, hy, hz)};
        return polyhedronMesh(v, HEX_FACES);
    }

    static Mesh polyhedronMesh(Vec3[] v, int[][] faces) {
        final Vec3 c = centroid(v);
        final Mesh.Builder mb = new Mesh.Builder();
        for (int[] f : faces) {
            Vec3[] ring = new Vec3[f.length];
            for (int i = 0; i < f.length; i++) {
                ring[i] = v[f[i]];
            }
            ring = outward(ring, c);
            mb.flatPolygon(ring);
        }
        return mb.build();
    }

    static Vec3 centroid(Vec3[] v) {
        Vec3 s = Vec3.ZERO;
        for (Vec3 p : v) {
            s = s.add(p);
        }
        return s.mul(1.0 / v.length);
    }

    /** Reverses a face whose Newell normal points into the solid. */
    static Vec3[] outward(Vec3[] ring, Vec3 center) {
        double nx = 0, ny = 0, nz = 0;
        Vec3 fc = Vec3.ZERO;
        for (int i = 0; i < ring.length; i++) {
            final Vec3 a = ring[i], b = ring[(i + 1) % ring.length];
            nx += (a.y() - b.y()) * (a.z() + b.z());
            ny += (a.z() - b.z()) * (a.x() + b.x());
            nz += (a.x() - b.x()) * (a.y() + b.y());
            fc = fc.add(a);
        }
        fc = fc.mul(1.0 / ring.length);
        if (new Vec3(nx, ny, nz).dot(fc.sub(center)) < 0) {
            final Vec3[] r = new Vec3[ring.length];
            for (int i = 0; i < ring.length; i++) {
                r[i] = ring[ring.length - 1 - i];
            }
            return r;
        }
        return ring;
    }

    /** A solid bounded by planes, given by its corners and faces. */
    public abstract static class PlaneSolid extends Solid {
        protected Vec3[] corners;
        protected int[][] faces;
        double[] planes; // nx ny nz d per face: n.p - d <= 0 inside
        BBox box;

        protected PlaneSolid(String name) {
            super(name);
        }

        /** Fills corners and faces from the evaluated parameters. */
        protected abstract void corners() throws GeometryException;

        @Override
        protected void prepare() throws GeometryException {
            corners();
            final Vec3 c = centroid(corners);
            planes = new double[faces.length * 4];
            BBox b = BBox.EMPTY;
            for (Vec3 p : corners) {
                b = b.include(p);
            }
            box = b;
            int kept = 0;
            for (int f = 0; f < faces.length; f++) {
                Vec3[] ring = new Vec3[faces[f].length];
                for (int i = 0; i < ring.length; i++) {
                    ring[i] = corners[faces[f][i]];
                }
                ring = outward(ring, c);
                double nx = 0, ny = 0, nz = 0;
                for (int i = 0; i < ring.length; i++) {
                    final Vec3 a = ring[i], bb = ring[(i + 1) % ring.length];
                    nx += (a.y() - bb.y()) * (a.z() + bb.z());
                    ny += (a.z() - bb.z()) * (a.x() + bb.x());
                    nz += (a.x() - bb.x()) * (a.y() + bb.y());
                }
                final Vec3 n = new Vec3(nx, ny, nz).unit();
                if (n.length2() == 0) {
                    // a face shrunk to an edge (a G4Trd with x1 = 0, a wedge) bounds nothing: the others do
                    continue;
                }
                // Planarity, as G4Trap::MakePlane checks it
                for (Vec3 p : ring) {
                    require(Math.abs(n.dot(p.sub(ring[0]))) < 1e-6 * Math.max(1, box.diagonal()),
                        g4Class() + " " + name + ": a face is not planar");
                }
                planes[kept * 4] = n.x();
                planes[kept * 4 + 1] = n.y();
                planes[kept * 4 + 2] = n.z();
                planes[kept * 4 + 3] = n.dot(ring[0]);
                kept++;
            }
            require(kept >= 4 && box.volume() > 0, g4Class() + " " + name + ": the solid is flat");
            planes = java.util.Arrays.copyOf(planes, kept * 4);
        }

        public double sdf(double x, double y, double z) {
            double d = -Double.MAX_VALUE;
            for (int i = 0; i < planes.length; i += 4) {
                d = Math.max(d, planes[i] * x + planes[i + 1] * y + planes[i + 2] * z - planes[i + 3]);
            }
            return d;
        }

        public BBox extent() {
            return box;
        }

        protected Mesh buildMesh(int q) {
            return polyhedronMesh(corners, faces);
        }

        protected double computeVolume() {
            return Math.abs(buildMesh(0).volume());
        }

        protected double computeArea() {
            return buildMesh(0).area();
        }
    }

    // ==================================================================
    // G4Trd
    // ==================================================================

    public static final class Trd extends PlaneSolid {
        public Trd(String name) {
            super(name);
            param("x1", "Length x at -z", L, "100*mm");
            param("x2", "Length x at +z", L, "50*mm");
            param("y1", "Length y at -z", L, "100*mm");
            param("y2", "Length y at +z", L, "50*mm");
            param("z", "Length z", L, "100*mm");
        }

        public String tag() {
            return "trd";
        }

        public String g4Class() {
            return "G4Trd";
        }

        protected void validate() throws GeometryException {
            require(v("x1") >= 0 && v("x2") >= 0 && v("y1") >= 0 && v("y2") >= 0 && v("z") > 0,
                "G4Trd " + name + ": lengths must not be negative");
            require(v("x1") + v("x2") > 0 && v("y1") + v("y2") > 0, "G4Trd " + name + ": zero section");
        }

        protected void corners() {
            final double dx1 = v("x1") / 2, dx2 = v("x2") / 2, dy1 = v("y1") / 2, dy2 = v("y2") / 2, dz = v("z") / 2;
            corners = new Vec3[] {
                new Vec3(-dx1, -dy1, -dz), new Vec3(dx1, -dy1, -dz), new Vec3(-dx1, dy1, -dz), new Vec3(dx1, dy1, -dz),
                new Vec3(-dx2, -dy2, dz), new Vec3(dx2, -dy2, dz), new Vec3(-dx2, dy2, dz), new Vec3(dx2, dy2, dz)};
            faces = HEX_FACES;
        }

        protected double computeVolume() {
            final double dx1 = v("x1") / 2, dx2 = v("x2") / 2, dy1 = v("y1") / 2, dy2 = v("y2") / 2, dz = v("z") / 2;
            return 2 * dz * ((dx1 + dx2) * (dy1 + dy2) + (dx2 - dx1) * (dy2 - dy1) / 3);
        }

        public String cpp(CppNames n) {
            return "new G4Trd(" + n.string(name) + ", " + Shapes.half(c("x1")) + ", " + Shapes.half(c("x2"))
                + ", " + Shapes.half(c("y1")) + ", " + Shapes.half(c("y2")) + ", " + Shapes.half(c("z")) + ")";
        }
    }

    // ==================================================================
    // G4Para
    // ==================================================================

    public static final class Para extends PlaneSolid {
        public Para(String name) {
            super(name);
            param("x", "Length x", L, "100*mm");
            param("y", "Length y", L, "100*mm");
            param("z", "Length z", L, "100*mm");
            param("alpha", "Alpha", A, "10*deg");
            param("theta", "Theta", A, "10*deg");
            param("phi", "Phi", A, "0*deg");
        }

        public String tag() {
            return "para";
        }

        public String g4Class() {
            return "G4Para";
        }

        protected void validate() throws GeometryException {
            require(v("x") > 0 && v("y") > 0 && v("z") > 0, "G4Para " + name + ": lengths must be positive");
            require(Math.abs(v("alpha")) < Math.PI / 2 && Math.abs(v("theta")) < Math.PI / 2,
                "G4Para " + name + ": alpha and theta must be within (-90, 90) deg");
        }

        protected void corners() {
            final double dx = v("x") / 2, dy = v("y") / 2, dz = v("z") / 2;
            final double ta = Math.tan(v("alpha")), tt = Math.tan(v("theta"));
            final double tcp = tt * Math.cos(v("phi")), tsp = tt * Math.sin(v("phi"));
            corners = new Vec3[8];
            int i = 0;
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sx = -1; sx <= 1; sx += 2) {
                        final double z = sz * dz, y = sy * dy, x = sx * dx;
                        corners[i++] = new Vec3(x + y * ta + z * tcp, y + z * tsp, z);
                    }
                }
            }
            faces = HEX_FACES;
        }

        protected double computeVolume() {
            return v("x") * v("y") * v("z");
        }

        public String cpp(CppNames n) {
            return "new G4Para(" + n.string(name) + ", " + Shapes.half(c("x")) + ", " + Shapes.half(c("y"))
                + ", " + Shapes.half(c("z")) + ", " + c("alpha") + ", " + c("theta") + ", " + c("phi") + ")";
        }
    }

    // ==================================================================
    // G4Trap
    // ==================================================================

    public static final class Trap extends PlaneSolid {
        public Trap(String name) {
            super(name);
            param("z", "Length z", L, "100*mm");
            param("theta", "Theta", A, "0*deg");
            param("phi", "Phi", A, "0*deg");
            param("y1", "Length y at -z", L, "60*mm");
            param("x1", "Length x at -z, -y", L, "40*mm");
            param("x2", "Length x at -z, +y", L, "60*mm");
            param("alpha1", "Alpha at -z", A, "0*deg");
            param("y2", "Length y at +z", L, "60*mm");
            param("x3", "Length x at +z, -y", L, "40*mm");
            param("x4", "Length x at +z, +y", L, "60*mm");
            param("alpha2", "Alpha at +z", A, "0*deg");
        }

        public String tag() {
            return "trap";
        }

        public String g4Class() {
            return "G4Trap";
        }

        protected void validate() throws GeometryException {
            require(v("z") > 0 && v("y1") > 0 && v("y2") > 0, "G4Trap " + name + ": z, y1, y2 must be positive");
            require(v("x1") >= 0 && v("x2") >= 0 && v("x3") >= 0 && v("x4") >= 0
                    && v("x1") + v("x2") > 0 && v("x3") + v("x4") > 0,
                "G4Trap " + name + ": x lengths must not be negative");
        }

        protected void corners() {
            final double dz = v("z") / 2, th = v("theta"), ph = v("phi");
            final double dy1 = v("y1") / 2, dx1 = v("x1") / 2, dx2 = v("x2") / 2, ta1 = Math.tan(v("alpha1"));
            final double dy2 = v("y2") / 2, dx3 = v("x3") / 2, dx4 = v("x4") / 2, ta2 = Math.tan(v("alpha2"));
            final double tcp = Math.tan(th) * Math.cos(ph), tsp = Math.tan(th) * Math.sin(ph);
            // G4Trap::MakePlanes
            corners = new Vec3[] {
                new Vec3(-dz * tcp - dy1 * ta1 - dx1, -dz * tsp - dy1, -dz),
                new Vec3(-dz * tcp - dy1 * ta1 + dx1, -dz * tsp - dy1, -dz),
                new Vec3(-dz * tcp + dy1 * ta1 - dx2, -dz * tsp + dy1, -dz),
                new Vec3(-dz * tcp + dy1 * ta1 + dx2, -dz * tsp + dy1, -dz),
                new Vec3(dz * tcp - dy2 * ta2 - dx3, dz * tsp - dy2, dz),
                new Vec3(dz * tcp - dy2 * ta2 + dx3, dz * tsp - dy2, dz),
                new Vec3(dz * tcp + dy2 * ta2 - dx4, dz * tsp + dy2, dz),
                new Vec3(dz * tcp + dy2 * ta2 + dx4, dz * tsp + dy2, dz)};
            faces = HEX_FACES;
        }

        public String cpp(CppNames n) {
            return "new G4Trap(" + n.string(name) + ", " + Shapes.half(c("z")) + ", " + c("theta") + ", "
                + c("phi") + ", " + Shapes.half(c("y1")) + ", " + Shapes.half(c("x1")) + ", "
                + Shapes.half(c("x2")) + ", " + c("alpha1") + ", " + Shapes.half(c("y2")) + ", "
                + Shapes.half(c("x3")) + ", " + Shapes.half(c("x4")) + ", " + c("alpha2") + ")";
        }
    }

    // ==================================================================
    // G4Tet
    // ==================================================================

    public static final class Tet extends PlaneSolid {
        public Tet(String name) {
            super(name);
            final String[] def = {"0", "0", "60", "-50", "-30", "-20", "50", "-30", "-20", "0", "50", "-20"};
            int k = 0;
            for (int v = 1; v <= 4; v++) {
                for (String axis : new String[] {"x", "y", "z"}) {
                    param("v" + v + axis, "Vertex " + v + " " + axis, L, def[k++] + "*mm");
                }
            }
        }

        public String tag() {
            return "tet";
        }

        public String g4Class() {
            return "G4Tet";
        }

        Vec3 vertex(int i) {
            return new Vec3(v("v" + i + "x"), v("v" + i + "y"), v("v" + i + "z"));
        }

        protected void validate() throws GeometryException {
            final Vec3 a = vertex(1), b = vertex(2), c = vertex(3), d = vertex(4);
            final double vol = Math.abs(b.sub(a).cross(c.sub(a)).dot(d.sub(a))) / 6;
            require(vol > 1e-9, "G4Tet " + name + ": the four vertices are coplanar");
        }

        protected void corners() {
            corners = new Vec3[] {vertex(1), vertex(2), vertex(3), vertex(4)};
            faces = TET_FACES;
        }

        public String cpp(CppNames n) {
            final StringBuilder b = new StringBuilder("new G4Tet(").append(n.string(name));
            for (int v = 1; v <= 4; v++) {
                b.append(", G4ThreeVector(").append(c("v" + v + "x")).append(", ").append(c("v" + v + "y"))
                 .append(", ").append(c("v" + v + "z")).append(")");
            }
            return b.append(")").toString();
        }
    }
}
