package com.sphere.core.geant4;

/**
 * G4GenericTrap, GDML's arb8 and ROOT's TGeoArb8: eight (x, y) vertices,
 * four at -dz and four at +dz, joined by straight lines. A lateral face whose
 * four corners are not coplanar is twisted: it is the ruled surface the lines
 * sweep, so the cross-section at any z is the quadrilateral whose corners are
 * interpolated between the two ends. TGeoTrap and TGeoGtra are such solids,
 * which is how a ROOT geometry brings them in.
 */
public final class GenericTrap extends Solid {

    private static final Kind L = Kind.LENGTH;

    /** Bottom then top corners, counter-clockwise seen from +z, as {x, y}. */
    private double[][] bottom, top;
    private double dz;
    private boolean twisted;
    private double safety = 1;
    private BBox box;

    public GenericTrap(String name) {
        super(name);
        param("dz", "Half length z", L, "50*mm");
        // G4GenericTrap lists each end clockwise seen from +z
        final String[][] def = {{"-30", "-30"}, {"-30", "30"}, {"30", "30"}, {"30", "-30"},
                                {"-15", "-15"}, {"-15", "15"}, {"15", "15"}, {"15", "-15"}};
        for (int i = 0; i < 8; i++) {
            param("v" + (i + 1) + "x", "Vertex " + (i + 1) + " x" + (i < 4 ? " (-dz)" : " (+dz)"), L, def[i][0] + "*mm");
            param("v" + (i + 1) + "y", "Vertex " + (i + 1) + " y" + (i < 4 ? " (-dz)" : " (+dz)"), L, def[i][1] + "*mm");
        }
    }

    public String tag() {
        return "arb8";
    }

    public String g4Class() {
        return "G4GenericTrap";
    }

    @Override
    public String description() {
        return twisted ? "G4GenericTrap (twisted)" : "G4GenericTrap";
    }

    /** Vertex i (0..7) as evaluated, in the order the parameters give them. */
    public double[] vertex(int i) {
        return new double[] {v("v" + (i + 1) + "x"), v("v" + (i + 1) + "y")};
    }

    public boolean twisted() {
        return twisted;
    }

    protected void validate() throws GeometryException {
        dz = v("dz");
        require(dz > 0, "G4GenericTrap " + name + ": dz must be positive");
        double[][] lo = new double[4][], hi = new double[4][];
        for (int i = 0; i < 4; i++) {
            lo[i] = vertex(i);
            hi[i] = vertex(i + 4);
        }
        final double aLo = signedArea(lo), aHi = signedArea(hi);
        require(Math.abs(aLo) > 0 || Math.abs(aHi) > 0, "G4GenericTrap " + name + ": both ends are degenerate");
        // Either orientation is accepted, as G4GenericTrap::ReorderVertices does;
        // both ends must agree, or the lateral faces would cross.
        require(aLo * aHi >= 0, "G4GenericTrap " + name + ": the two ends are listed in opposite directions");
        if (aLo < 0 || aHi < 0) {
            lo = reversed(lo);
            hi = reversed(hi);
        }
        bottom = lo;
        top = hi;
        twisted = false;
        double slope = 0;
        BBox b = BBox.EMPTY;
        for (int i = 0; i < 4; i++) {
            final int j = (i + 1) % 4;
            // Twist: the cross product of the bottom and top edges, as G4GenericTrap::ComputeIsTwisted
            final double ex0 = bottom[j][0] - bottom[i][0], ey0 = bottom[j][1] - bottom[i][1];
            final double ex1 = top[j][0] - top[i][0], ey1 = top[j][1] - top[i][1];
            final double cross = ex0 * ey1 - ey0 * ex1;
            final double scale = Math.max(1e-12, Math.hypot(ex0, ey0) * Math.hypot(ex1, ey1));
            if (Math.abs(cross) / scale > 1e-9 && Math.hypot(ex0, ey0) > 1e-12 && Math.hypot(ex1, ey1) > 1e-12) {
                twisted = true;
            }
            slope = Math.max(slope, Math.hypot(top[i][0] - bottom[i][0], top[i][1] - bottom[i][1]) / (2 * dz));
            b = b.include(new Vec3(bottom[i][0], bottom[i][1], -dz)).include(new Vec3(top[i][0], top[i][1], dz));
        }
        // The walls lean (and turn, when twisted): an in-plane distance overestimates then.
        safety = 1 / Math.sqrt(1 + slope * slope) * (twisted ? 0.5 : 1);
        box = b;
    }

    private static double signedArea(double[][] q) {
        double a = 0;
        for (int i = 0; i < q.length; i++) {
            final double[] p = q[i], r = q[(i + 1) % q.length];
            a += p[0] * r[1] - r[0] * p[1];
        }
        return a / 2;
    }

    private static double[][] reversed(double[][] q) {
        return new double[][] {q[3], q[2], q[1], q[0]};
    }

    /** The cross-section at height t in [0, 1] from -dz to +dz, as x y pairs. */
    private double[] section(double t) {
        final double[] s = new double[8];
        for (int i = 0; i < 4; i++) {
            s[i * 2] = bottom[i][0] + t * (top[i][0] - bottom[i][0]);
            s[i * 2 + 1] = bottom[i][1] + t * (top[i][1] - bottom[i][1]);
        }
        return s;
    }

    public double sdf(double x, double y, double z) {
        final double t = (Math.max(-dz, Math.min(dz, z)) + dz) / (2 * dz);
        final double d2 = Polygon2D.signedDistance(section(t), x, y);
        return Math.max(d2, Math.abs(z) - dz);
    }

    public double safety() {
        return safety;
    }

    public BBox extent() {
        return box;
    }

    private Vec3 at(int i, double t) {
        return new Vec3(bottom[i][0] + t * (top[i][0] - bottom[i][0]),
                        bottom[i][1] + t * (top[i][1] - bottom[i][1]), -dz + 2 * dz * t);
    }

    protected Mesh buildMesh(int q) {
        final Mesh.Builder mb = new Mesh.Builder();
        final int steps = twisted ? 4 << Math.max(0, Math.min(3, q)) : 1;
        final Vec3 c = new Vec3(0.5 * (box.minX() + box.maxX()), 0.5 * (box.minY() + box.maxY()), 0);
        for (int i = 0; i < 4; i++) {
            final int j = (i + 1) % 4;
            for (int s = 0; s < steps; s++) {
                final double t0 = (double) s / steps, t1 = (double) (s + 1) / steps;
                final Vec3 a0 = at(i, t0), b0 = at(j, t0), b1 = at(j, t1), a1 = at(i, t1);
                // counter-clockwise ends: (a0, b0, b1) faces outward
                mb.flatTriangle(a0, b0, b1);
                mb.flatTriangle(a0, b1, a1);
            }
        }
        mb.flatPolygon(Convex.outward(new Vec3[] {at(0, 1), at(1, 1), at(2, 1), at(3, 1)}, c));
        mb.flatPolygon(Convex.outward(new Vec3[] {at(3, 0), at(2, 0), at(1, 0), at(0, 0)}, c));
        return mb.build();
    }

    /** The area of the section is quadratic in z: Simpson's rule is exact. */
    protected double computeVolume() {
        final double a0 = Math.abs(Polygon2D.signedArea(section(0)));
        final double am = Math.abs(Polygon2D.signedArea(section(0.5)));
        final double a1 = Math.abs(Polygon2D.signedArea(section(1)));
        return 2 * dz / 6 * (a0 + 4 * am + a1);
    }

    /** Sets the eight vertices (internal units) and the half length, as numbers. */
    public void setVertices(double[] xy, double halfZ) {
        set("dz", Units.num(halfZ) + "*mm");
        for (int i = 0; i < 8; i++) {
            set("v" + (i + 1) + "x", Units.num(xy[i * 2]) + "*mm");
            set("v" + (i + 1) + "y", Units.num(xy[i * 2 + 1]) + "*mm");
        }
    }

    public String cpp(CppNames n) {
        final StringBuilder b = new StringBuilder("[&] { std::vector<G4TwoVector> vertices = {");
        for (int i = 0; i < 8; i++) {
            b.append(i > 0 ? ", " : "").append("G4TwoVector(").append(c("v" + (i + 1) + "x")).append(", ")
             .append(c("v" + (i + 1) + "y")).append(")");
        }
        return b.append("}; return new G4GenericTrap(").append(n.string(name)).append(", ").append(c("dz"))
                .append(", vertices); }()").toString();
    }
}
