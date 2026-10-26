package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;

/**
 * Geant4's CSG and specific solids. Parameters carry the names and meanings
 * of their GDML attributes (G4GDMLReadSolids), so a box's x is its full
 * length, a tube's z its full length, a paraboloid's dz its half length.
 */
public final class Shapes {

    private Shapes() {
    }

    private static final Solid.Kind L = Solid.Kind.LENGTH;
    private static final Solid.Kind A = Solid.Kind.ANGLE;
    private static final Solid.Kind N = Solid.Kind.NUMBER;
    private static final Solid.Kind C = Solid.Kind.COUNT;

    static String half(String cpp) {
        return "0.5*(" + cpp + ")";
    }

    // ==================================================================
    // G4Box
    // ==================================================================

    public static final class Box extends Solid {
        double hx, hy, hz;

        public Box(String name) {
            super(name);
            param("x", "Length x", L, "100*mm");
            param("y", "Length y", L, "100*mm");
            param("z", "Length z", L, "100*mm");
        }

        public String tag() {
            return "box";
        }

        public String g4Class() {
            return "G4Box";
        }

        protected void validate() throws GeometryException {
            hx = v("x") / 2;
            hy = v("y") / 2;
            hz = v("z") / 2;
            require(hx > 2 * HALF_TOLERANCE && hy > 2 * HALF_TOLERANCE && hz > 2 * HALF_TOLERANCE,
                "G4Box " + name + ": dimensions must be positive");
        }

        public double sdf(double x, double y, double z) {
            final double qx = Math.abs(x) - hx, qy = Math.abs(y) - hy, qz = Math.abs(z) - hz;
            final double ox = Math.max(qx, 0), oy = Math.max(qy, 0), oz = Math.max(qz, 0);
            return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0);
        }

        public BBox extent() {
            return BBox.centered(hx, hy, hz);
        }

        protected Mesh buildMesh(int q) {
            return Convex.boxMesh(hx, hy, hz);
        }

        protected double computeVolume() {
            return 8 * hx * hy * hz;
        }

        protected double computeArea() {
            return 8 * (hx * hy + hy * hz + hz * hx);
        }

        public String cpp(CppNames n) {
            return "new G4Box(" + n.string(name) + ", " + half(c("x")) + ", " + half(c("y")) + ", "
                + half(c("z")) + ")";
        }
    }

    // ==================================================================
    // G4Orb
    // ==================================================================

    public static final class Orb extends Solid {
        double r;

        public Orb(String name) {
            super(name);
            param("r", "Radius", L, "50*mm");
        }

        public String tag() {
            return "orb";
        }

        public String g4Class() {
            return "G4Orb";
        }

        protected void validate() throws GeometryException {
            r = v("r");
            require(r > 10 * HALF_TOLERANCE, "G4Orb " + name + ": radius must be positive");
        }

        public double sdf(double x, double y, double z) {
            return Math.sqrt(x * x + y * y + z * z) - r;
        }

        public BBox extent() {
            return BBox.centered(r, r, r);
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            final List<double[]> pts = new ArrayList<>();
            Revolve.arc(pts, 0, 0, r, -Math.PI / 2, Math.PI / 2, segments(q) / 2);
            Revolve.build(mb, flat(pts), smooth(pts), 0, Units.TWOPI, segments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return 4.0 / 3.0 * Math.PI * r * r * r;
        }

        protected double computeArea() {
            return 4 * Math.PI * r * r;
        }

        public String cpp(CppNames n) {
            return "new G4Orb(" + n.string(name) + ", " + c("r") + ")";
        }
    }

    static double[] flat(List<double[]> pts) {
        final double[] out = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            out[i * 2] = pts.get(i)[0];
            out[i * 2 + 1] = pts.get(i)[1];
        }
        return out;
    }

    static boolean[] smooth(List<double[]> pts) {
        final boolean[] out = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[i] = pts.get(i).length > 2 && pts.get(i)[2] != 0;
        }
        return out;
    }

    /** Phi segment shared by the solids of revolution. */
    abstract static class PhiSolid extends Solid {
        double sphi, dphi, cs, ss, ce, se;

        PhiSolid(String name) {
            super(name);
        }

        protected void phi(double start, double delta) throws GeometryException {
            require(delta > 0, g4Class() + " " + name + ": deltaphi must be positive");
            if (delta >= Units.TWOPI - 1e-12) {
                start = 0;
                delta = Units.TWOPI;
            }
            sphi = start;
            dphi = delta;
            cs = Math.cos(sphi);
            ss = Math.sin(sphi);
            ce = Math.cos(sphi + dphi);
            se = Math.sin(sphi + dphi);
        }

        protected double phiDistance(double x, double y) {
            return wedge(x, y, cs, ss, ce, se, dphi);
        }

        protected int phiSegments(int q) {
            return Math.max(3, (int) Math.ceil(segments(q) * dphi / Units.TWOPI));
        }

        protected BBox clipPhi(double rmax, double zlo, double zhi) {
            if (dphi >= Units.TWOPI) {
                return new BBox(-rmax, -rmax, zlo, rmax, rmax, zhi);
            }
            // Extreme points: the two end rays and every axis direction the segment crosses
            double x0 = Math.min(0, Math.min(cs, ce) * rmax), x1 = Math.max(0, Math.max(cs, ce) * rmax);
            double y0 = Math.min(0, Math.min(ss, se) * rmax), y1 = Math.max(0, Math.max(ss, se) * rmax);
            for (int k = 0; k < 4; k++) {
                final double a = k * Math.PI / 2;
                double rel = (a - sphi) % Units.TWOPI;
                if (rel < 0) {
                    rel += Units.TWOPI;
                }
                if (rel <= dphi) {
                    final double cx = Math.cos(a) * rmax, cy = Math.sin(a) * rmax;
                    x0 = Math.min(x0, cx);
                    x1 = Math.max(x1, cx);
                    y0 = Math.min(y0, cy);
                    y1 = Math.max(y1, cy);
                }
            }
            return new BBox(x0, y0, zlo, x1, y1, zhi);
        }
    }

    // ==================================================================
    // G4Tubs
    // ==================================================================

    public static final class Tubs extends PhiSolid {
        double rmin, rmax, dz;

        public Tubs(String name) {
            super(name);
            param("rmin", "Inner radius", L, "0*mm");
            param("rmax", "Outer radius", L, "50*mm");
            param("z", "Length z", L, "100*mm");
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
        }

        public String tag() {
            return "tube";
        }

        public String g4Class() {
            return "G4Tubs";
        }

        protected void validate() throws GeometryException {
            rmin = v("rmin");
            rmax = v("rmax");
            dz = v("z") / 2;
            require(dz > 0, "G4Tubs " + name + ": length must be positive");
            require(rmin >= 0 && rmax > rmin, "G4Tubs " + name + ": needs 0 <= rmin < rmax");
            phi(v("startphi"), v("deltaphi"));
        }

        public double sdf(double x, double y, double z) {
            final double r = Math.sqrt(x * x + y * y);
            final double qr = rmin > 0 ? Math.abs(r - 0.5 * (rmin + rmax)) - 0.5 * (rmax - rmin) : r - rmax;
            final double qz = Math.abs(z) - dz;
            final double or = Math.max(qr, 0), oz = Math.max(qz, 0);
            final double d = Math.sqrt(or * or + oz * oz) + Math.min(Math.max(qr, qz), 0);
            return Math.max(d, phiDistance(x, y));
        }

        public BBox extent() {
            return clipPhi(rmax, -dz, dz);
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, new double[] {rmin, -dz, rmax, -dz, rmax, dz, rmin, dz}, null, sphi, dphi,
                phiSegments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return dphi * dz * (rmax * rmax - rmin * rmin);
        }

        protected double computeArea() {
            double a = dphi * (rmin + rmax) * (2 * dz + rmax - rmin);
            if (dphi < Units.TWOPI) {
                a += 4 * dz * (rmax - rmin);
            }
            return a;
        }

        public String cpp(CppNames n) {
            return "new G4Tubs(" + n.string(name) + ", " + c("rmin") + ", " + c("rmax") + ", "
                + half(c("z")) + ", " + c("startphi") + ", " + c("deltaphi") + ")";
        }
    }

    // ==================================================================
    // G4CutTubs
    // ==================================================================

    public static final class CutTubs extends PhiSolid {
        double rmin, rmax, dz;
        double lx, ly, lz, hx, hy, hz;

        public CutTubs(String name) {
            super(name);
            param("rmin", "Inner radius", L, "0*mm");
            param("rmax", "Outer radius", L, "50*mm");
            param("z", "Length z", L, "100*mm");
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
            param("lowX", "Low normal x", N, "0");
            param("lowY", "Low normal y", N, "-0.7");
            param("lowZ", "Low normal z", N, "-0.71");
            param("highX", "High normal x", N, "0.7");
            param("highY", "High normal y", N, "0");
            param("highZ", "High normal z", N, "0.71");
        }

        public String tag() {
            return "cutTube";
        }

        public String g4Class() {
            return "G4CutTubs";
        }

        protected void validate() throws GeometryException {
            rmin = v("rmin");
            rmax = v("rmax");
            dz = v("z") / 2;
            require(dz > 0, "G4CutTubs " + name + ": length must be positive");
            require(rmin >= 0 && rmax > rmin, "G4CutTubs " + name + ": needs 0 <= rmin < rmax");
            phi(v("startphi"), v("deltaphi"));
            final Vec3 low = new Vec3(v("lowX"), v("lowY"), v("lowZ")).unit();
            final Vec3 high = new Vec3(v("highX"), v("highY"), v("highZ")).unit();
            require(low.z() < 0, "G4CutTubs " + name + ": the low normal must point down (z < 0)");
            require(high.z() > 0, "G4CutTubs " + name + ": the high normal must point up (z > 0)");
            lx = low.x();
            ly = low.y();
            lz = low.z();
            hx = high.x();
            hy = high.y();
            hz = high.z();
            // The cut planes must not cross inside the tube (G4CutTubs::IsCrossingCutPlanes)
            for (int k = 0; k < 72; k++) {
                final double a = k * Units.TWOPI / 72;
                final double x = rmax * Math.cos(a), y = rmax * Math.sin(a);
                require(zHigh(x, y) - zLow(x, y) > 0, "G4CutTubs " + name + ": the cut planes cross inside the solid");
            }
        }

        double zLow(double x, double y) {
            return -dz - (lx * x + ly * y) / lz;
        }

        double zHigh(double x, double y) {
            return dz - (hx * x + hy * y) / hz;
        }

        public double sdf(double x, double y, double z) {
            final double r = Math.sqrt(x * x + y * y);
            final double qr = rmin > 0 ? Math.abs(r - 0.5 * (rmin + rmax)) - 0.5 * (rmax - rmin) : r - rmax;
            final double low = lx * x + ly * y + lz * (z + dz);
            final double high = hx * x + hy * y + hz * (z - dz);
            return Math.max(Math.max(qr, phiDistance(x, y)), Math.max(low, high));
        }

        public BBox extent() {
            double z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int k = 0; k < 360; k++) {
                final double a = k * Units.TWOPI / 360;
                final double x = rmax * Math.cos(a), y = rmax * Math.sin(a);
                z0 = Math.min(z0, zLow(x, y));
                z1 = Math.max(z1, zHigh(x, y));
            }
            z0 = Math.min(z0, -dz);
            z1 = Math.max(z1, dz);
            return clipPhi(rmax, z0, z1);
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            final int n = phiSegments(q);
            final boolean full = dphi >= Units.TWOPI;
            // Outer and inner walls, smooth in phi
            for (int wall = 0; wall < (rmin > 0 ? 2 : 1); wall++) {
                final double r = wall == 0 ? rmax : rmin;
                final double sgn = wall == 0 ? 1 : -1;
                final int base = mb.vertexCount();
                for (int k = 0; k <= n; k++) {
                    final double a = sphi + dphi * k / n, c = Math.cos(a), s = Math.sin(a);
                    final double x = r * c, y = r * s;
                    mb.vertex(x, y, zLow(x, y), sgn * c, sgn * s, 0);
                    mb.vertex(x, y, zHigh(x, y), sgn * c, sgn * s, 0);
                }
                for (int k = 0; k < n; k++) {
                    final int a0 = base + k * 2, a1 = a0 + 2;
                    if (wall == 0) {
                        mb.quad(a0, a1, a1 + 1, a0 + 1);
                    } else {
                        mb.quad(a0, a0 + 1, a1 + 1, a1);
                    }
                }
            }
            // Cut faces: annular strips lying in the planes
            for (int face = 0; face < 2; face++) {
                final boolean top = face == 1;
                final Vec3 nrm = top ? new Vec3(hx, hy, hz) : new Vec3(lx, ly, lz);
                final int base = mb.vertexCount();
                for (int k = 0; k <= n; k++) {
                    final double a = sphi + dphi * k / n, c = Math.cos(a), s = Math.sin(a);
                    final double xo = rmax * c, yo = rmax * s, xi = rmin * c, yi = rmin * s;
                    mb.vertex(xo, yo, top ? zHigh(xo, yo) : zLow(xo, yo), nrm.x(), nrm.y(), nrm.z());
                    mb.vertex(xi, yi, top ? zHigh(xi, yi) : zLow(xi, yi), nrm.x(), nrm.y(), nrm.z());
                }
                for (int k = 0; k < n; k++) {
                    final int o0 = base + k * 2, i0 = o0 + 1, o1 = o0 + 2, i1 = o0 + 3;
                    if (top) {
                        mb.quad(o0, o1, i1, i0);
                    } else {
                        mb.quad(o0, i0, i1, o1);
                    }
                }
            }
            if (!full) {
                for (int end = 0; end < 2; end++) {
                    final double a = end == 0 ? sphi : sphi + dphi, c = Math.cos(a), s = Math.sin(a);
                    final Vec3 p0 = new Vec3(rmin * c, rmin * s, zLow(rmin * c, rmin * s));
                    final Vec3 p1 = new Vec3(rmax * c, rmax * s, zLow(rmax * c, rmax * s));
                    final Vec3 p2 = new Vec3(rmax * c, rmax * s, zHigh(rmax * c, rmax * s));
                    final Vec3 p3 = new Vec3(rmin * c, rmin * s, zHigh(rmin * c, rmin * s));
                    if (end == 0) {
                        mb.flatPolygon(new Vec3[] {p0, p1, p2, p3});
                    } else {
                        mb.flatPolygon(new Vec3[] {p3, p2, p1, p0});
                    }
                }
            }
            return mb.build();
        }

        protected double computeVolume() {
            // ∫∫ (zHigh - zLow) dA over the annular sector, zHigh - zLow being linear in x, y
            final double a = -(hx / hz - lx / lz), b = -(hy / hz - ly / lz);
            final double area = 0.5 * dphi * (rmax * rmax - rmin * rmin);
            final double m3 = (rmax * rmax * rmax - rmin * rmin * rmin) / 3.0;
            final double mx = m3 * (Math.sin(sphi + dphi) - Math.sin(sphi));
            final double my = m3 * (Math.cos(sphi) - Math.cos(sphi + dphi));
            return 2 * dz * area + a * mx + b * my;
        }

        public String cpp(CppNames n) {
            return "new G4CutTubs(" + n.string(name) + ", " + c("rmin") + ", " + c("rmax") + ", "
                + half(c("z")) + ", " + c("startphi") + ", " + c("deltaphi") + ", G4ThreeVector("
                + c("lowX") + ", " + c("lowY") + ", " + c("lowZ") + "), G4ThreeVector(" + c("highX")
                + ", " + c("highY") + ", " + c("highZ") + "))";
        }
    }

    // ==================================================================
    // G4Cons
    // ==================================================================

    public static final class Cons extends PhiSolid {
        double rmin1, rmax1, rmin2, rmax2, dz;
        double[] rz;

        public Cons(String name) {
            super(name);
            param("rmin1", "Inner radius at -z", L, "0*mm");
            param("rmax1", "Outer radius at -z", L, "50*mm");
            param("rmin2", "Inner radius at +z", L, "0*mm");
            param("rmax2", "Outer radius at +z", L, "20*mm");
            param("z", "Length z", L, "100*mm");
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
        }

        public String tag() {
            return "cone";
        }

        public String g4Class() {
            return "G4Cons";
        }

        protected void validate() throws GeometryException {
            rmin1 = v("rmin1");
            rmax1 = v("rmax1");
            rmin2 = v("rmin2");
            rmax2 = v("rmax2");
            dz = v("z") / 2;
            require(dz > 0, "G4Cons " + name + ": length must be positive");
            require(rmin1 >= 0 && rmin2 >= 0 && rmax1 >= rmin1 && rmax2 >= rmin2 && rmax1 + rmax2 > 0,
                "G4Cons " + name + ": needs 0 <= rmin <= rmax at both ends");
            phi(v("startphi"), v("deltaphi"));
            rz = clean(new double[] {rmin1, -dz, rmax1, -dz, rmax2, dz, rmin2, dz});
        }

        public double sdf(double x, double y, double z) {
            return Math.max(rzDistance(rz, Math.sqrt(x * x + y * y), z), phiDistance(x, y));
        }

        public BBox extent() {
            return clipPhi(Math.max(rmax1, rmax2), -dz, dz);
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, rz, null, sphi, dphi, phiSegments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return dphi * dz / 3.0 * (rmax1 * rmax1 + rmax2 * rmax2 + rmax1 * rmax2
                - rmin1 * rmin1 - rmin2 * rmin2 - rmin1 * rmin2);
        }

        public String cpp(CppNames n) {
            return "new G4Cons(" + n.string(name) + ", " + c("rmin1") + ", " + c("rmax1") + ", "
                + c("rmin2") + ", " + c("rmax2") + ", " + half(c("z")) + ", " + c("startphi") + ", "
                + c("deltaphi") + ")";
        }
    }

    /** Drops consecutive duplicate points of a closed polygon. */
    static double[] clean(double[] p) {
        final int n = p.length / 2;
        final double[] out = new double[p.length];
        int m = 0;
        for (int i = 0; i < n; i++) {
            final double x = p[i * 2], y = p[i * 2 + 1];
            if (m > 0 && Math.abs(out[(m - 1) * 2] - x) < 1e-12 && Math.abs(out[(m - 1) * 2 + 1] - y) < 1e-12) {
                continue;
            }
            out[m * 2] = x;
            out[m * 2 + 1] = y;
            m++;
        }
        if (m > 1 && Math.abs(out[0] - out[(m - 1) * 2]) < 1e-12 && Math.abs(out[1] - out[(m - 1) * 2 + 1]) < 1e-12) {
            m--;
        }
        return java.util.Arrays.copyOf(out, m * 2);
    }

    // ==================================================================
    // G4Sphere
    // ==================================================================

    public static final class Sphere extends PhiSolid {
        double rmin, rmax, stheta, dtheta, ct1, st1, ct2, st2;
        boolean fullTheta;

        public Sphere(String name) {
            super(name);
            param("rmin", "Inner radius", L, "0*mm");
            param("rmax", "Outer radius", L, "50*mm");
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
            param("starttheta", "Start theta", A, "0*deg");
            param("deltatheta", "Delta theta", A, "180*deg");
        }

        public String tag() {
            return "sphere";
        }

        public String g4Class() {
            return "G4Sphere";
        }

        protected void validate() throws GeometryException {
            rmin = v("rmin");
            rmax = v("rmax");
            require(rmin >= 0 && rmax > rmin, "G4Sphere " + name + ": needs 0 <= rmin < rmax");
            phi(v("startphi"), v("deltaphi"));
            stheta = v("starttheta");
            dtheta = v("deltatheta");
            require(stheta >= 0 && stheta <= Math.PI, "G4Sphere " + name + ": starttheta must be within [0, 180] deg");
            require(dtheta > 0, "G4Sphere " + name + ": deltatheta must be positive");
            if (stheta + dtheta >= Math.PI) {
                dtheta = Math.PI - stheta;
            }
            fullTheta = stheta <= 0 && dtheta >= Math.PI - 1e-12;
            ct1 = Math.cos(stheta);
            st1 = Math.sin(stheta);
            ct2 = Math.cos(stheta + dtheta);
            st2 = Math.sin(stheta + dtheta);
        }

        public double sdf(double x, double y, double z) {
            final double rho = Math.sqrt(x * x + y * y + z * z);
            double d = rmin > 0 ? Math.max(rho - rmax, rmin - rho) : rho - rmax;
            if (!fullTheta) {
                // The theta cones are a wedge in the meridian plane, angle measured from +z
                d = Math.max(d, wedge(z, Math.sqrt(x * x + y * y), ct1, st1, ct2, st2, dtheta));
            }
            return Math.max(d, phiDistance(x, y));
        }

        public BBox extent() {
            final double zmax = stheta < 1e-12 ? rmax : Math.max(rmax * ct1, rmin * ct1);
            final double zmin = stheta + dtheta > Math.PI - 1e-12 ? -rmax : Math.min(rmax * ct2, rmin * ct2);
            final boolean equator = stheta <= Math.PI / 2 && stheta + dtheta >= Math.PI / 2;
            final double rxy = equator ? rmax : rmax * Math.max(st1, st2);
            return clipPhi(rxy, zmin, zmax);
        }

        protected Mesh buildMesh(int q) {
            final int nt = Math.max(2, (int) Math.ceil(segments(q) / 2.0 * dtheta / Math.PI));
            final List<double[]> pts = new ArrayList<>();
            // Outer arc from the end theta up to the start theta (z increasing), then the inner back
            for (int i = 0; i <= nt; i++) {
                final double t = stheta + dtheta - dtheta * i / nt;
                pts.add(new double[] {rmax * Math.sin(t), rmax * Math.cos(t), i == 0 || i == nt ? 0 : 1});
            }
            if (rmin > 0) {
                for (int i = 0; i <= nt; i++) {
                    final double t = stheta + dtheta * i / nt;
                    pts.add(new double[] {rmin * Math.sin(t), rmin * Math.cos(t), i == 0 || i == nt ? 0 : 1});
                }
            } else {
                pts.add(new double[] {0, 0, 0});
            }
            final double[] rz = clean(flat(pts));
            final boolean[] sm = new boolean[rz.length / 2];
            // smooth flags follow the cleaned points: recompute by matching
            int k = 0;
            for (double[] p : pts) {
                if (k < sm.length && Math.abs(rz[k * 2] - p[0]) < 1e-12 && Math.abs(rz[k * 2 + 1] - p[1]) < 1e-12) {
                    sm[k] = p[2] != 0;
                    k++;
                }
            }
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, rz, sm, sphi, dphi, phiSegments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return dphi * (ct1 - ct2) * (rmax * rmax * rmax - rmin * rmin * rmin) / 3.0;
        }

        public String cpp(CppNames n) {
            return "new G4Sphere(" + n.string(name) + ", " + c("rmin") + ", " + c("rmax") + ", "
                + c("startphi") + ", " + c("deltaphi") + ", " + c("starttheta") + ", " + c("deltatheta") + ")";
        }
    }

    // ==================================================================
    // G4Torus
    // ==================================================================

    public static final class Torus extends PhiSolid {
        double rmin, rmax, rtor;

        public Torus(String name) {
            super(name);
            param("rmin", "Inner radius", L, "0*mm");
            param("rmax", "Outer radius", L, "20*mm");
            param("rtor", "Swept radius", L, "80*mm");
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
        }

        public String tag() {
            return "torus";
        }

        public String g4Class() {
            return "G4Torus";
        }

        protected void validate() throws GeometryException {
            rmin = v("rmin");
            rmax = v("rmax");
            rtor = v("rtor");
            require(rmin >= 0 && rmax > rmin, "G4Torus " + name + ": needs 0 <= rmin < rmax");
            require(rtor >= rmax + 1e-6, "G4Torus " + name + ": the swept radius must exceed rmax");
            phi(v("startphi"), v("deltaphi"));
        }

        public double sdf(double x, double y, double z) {
            final double q = Math.hypot(Math.sqrt(x * x + y * y) - rtor, z);
            final double d = rmin > 0 ? Math.max(q - rmax, rmin - q) : q - rmax;
            return Math.max(d, phiDistance(x, y));
        }

        public BBox extent() {
            return clipPhi(rtor + rmax, -rmax, rmax);
        }

        protected Mesh buildMesh(int q) {
            final int nc = Math.max(16, segments(q) / 2);
            final double[] outer = circle(rtor, 0, rmax, nc);
            final boolean[] sm = new boolean[nc];
            java.util.Arrays.fill(sm, true);
            final Mesh.Builder mb = new Mesh.Builder();
            if (rmin > 0) {
                final double[] inner = circle(rtor, 0, rmin, nc);
                Revolve.build(mb, outer, sm, sphi, dphi, phiSegments(q), 0, false, false);
                Revolve.build(mb, inner, sm, sphi, dphi, phiSegments(q), 0, false, true);
                if (dphi < Units.TWOPI) {
                    Revolve.annulusCap(mb, outer, inner, sphi, true);
                    Revolve.annulusCap(mb, outer, inner, sphi + dphi, false);
                }
            } else {
                Revolve.build(mb, outer, sm, sphi, dphi, phiSegments(q), 0);
            }
            return mb.build();
        }

        protected double computeVolume() {
            return dphi * Math.PI * rtor * (rmax * rmax - rmin * rmin);
        }

        protected double computeArea() {
            double a = dphi * Units.TWOPI * rtor * (rmax + rmin);
            if (dphi < Units.TWOPI) {
                a += Units.TWOPI * (rmax * rmax - rmin * rmin);
            }
            return a;
        }

        public String cpp(CppNames n) {
            return "new G4Torus(" + n.string(name) + ", " + c("rmin") + ", " + c("rmax") + ", "
                + c("rtor") + ", " + c("startphi") + ", " + c("deltaphi") + ")";
        }
    }

    static double[] circle(double rc, double zc, double rho, int n) {
        final double[] out = new double[n * 2];
        for (int i = 0; i < n; i++) {
            final double a = Units.TWOPI * i / n;
            out[i * 2] = rc + rho * Math.cos(a);
            out[i * 2 + 1] = zc + rho * Math.sin(a);
        }
        return out;
    }

    // ==================================================================
    // G4Polycone (z-planes) and G4GenericPolycone (r-z points)
    // ==================================================================

    public static final class Polycone extends PhiSolid {
        double[] rz;
        double rmaxAll, zmin, zmax;

        public Polycone(String name) {
            super(name);
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
            final Table t = table("zplane", new String[] {"rmin", "rmax", "z"}, new Kind[] {L, L, L});
            t.add("0*mm", "30*mm", "-50*mm");
            t.add("0*mm", "50*mm", "0*mm");
            t.add("0*mm", "20*mm", "50*mm");
        }

        public String tag() {
            return "polycone";
        }

        public String g4Class() {
            return "G4Polycone";
        }

        protected void validate() throws GeometryException {
            phi(v("startphi"), v("deltaphi"));
            final Table t = table("zplane");
            require(t.size() >= 2, "G4Polycone " + name + ": needs at least two z planes");
            final int n = t.size();
            final double[] p = new double[n * 4];
            rmaxAll = 0;
            zmin = Double.MAX_VALUE;
            zmax = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                final double rmin = t.value(i, 0), rmax = t.value(i, 1), z = t.value(i, 2);
                require(rmin >= 0 && rmax >= rmin, "G4Polycone " + name + ": plane " + i + " needs 0 <= rmin <= rmax");
                if (i > 0) {
                    require(z >= t.value(i - 1, 2), "G4Polycone " + name + ": z planes must not decrease");
                }
                p[i * 2] = rmax;
                p[i * 2 + 1] = z;
                p[(2 * n - 1 - i) * 2] = rmin;
                p[(2 * n - 1 - i) * 2 + 1] = z;
                rmaxAll = Math.max(rmaxAll, rmax);
                zmin = Math.min(zmin, z);
                zmax = Math.max(zmax, z);
            }
            require(zmax > zmin, "G4Polycone " + name + ": zero length");
            rz = clean(p);
            require(rz.length >= 6 && Math.abs(Polygon2D.signedArea(rz)) > 0, "G4Polycone " + name + ": empty cross-section");
        }

        public double sdf(double x, double y, double z) {
            return Math.max(rzDistance(rz, Math.sqrt(x * x + y * y), z), phiDistance(x, y));
        }

        public BBox extent() {
            return clipPhi(rmaxAll, zmin, zmax);
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, rz, null, sphi, dphi, phiSegments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return revolvedVolume(rz, dphi);
        }

        public String cpp(CppNames n) {
            final Table t = table("zplane");
            final StringBuilder z = new StringBuilder(), rmin = new StringBuilder(), rmax = new StringBuilder();
            for (int i = 0; i < t.size(); i++) {
                final String sep = i > 0 ? ", " : "";
                rmin.append(sep).append(c(t, i, 0));
                rmax.append(sep).append(c(t, i, 1));
                z.append(sep).append(c(t, i, 2));
            }
            return "[&] { const G4double z[] = {" + z + "}; const G4double rmin[] = {" + rmin
                + "}; const G4double rmax[] = {" + rmax + "}; return new G4Polycone(" + n.string(name)
                + ", " + c("startphi") + ", " + c("deltaphi") + ", " + t.size() + ", z, rmin, rmax); }()";
        }
    }

    public static final class GenericPolycone extends PhiSolid {
        double[] rz;
        BBox box;

        public GenericPolycone(String name) {
            super(name);
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
            final Table t = table("rzpoint", new String[] {"r", "z"}, new Kind[] {L, L});
            t.add("0*mm", "-50*mm");
            t.add("40*mm", "-50*mm");
            t.add("20*mm", "0*mm");
            t.add("40*mm", "50*mm");
            t.add("0*mm", "50*mm");
        }

        public String tag() {
            return "genericPolycone";
        }

        public String g4Class() {
            return "G4GenericPolycone";
        }

        protected void validate() throws GeometryException {
            phi(v("startphi"), v("deltaphi"));
            final Table t = table("rzpoint");
            require(t.size() >= 3, "G4GenericPolycone " + name + ": needs at least three (r, z) points");
            final double[] p = new double[t.size() * 2];
            double rm = 0, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int i = 0; i < t.size(); i++) {
                p[i * 2] = t.value(i, 0);
                p[i * 2 + 1] = t.value(i, 1);
                require(p[i * 2] >= 0, "G4GenericPolycone " + name + ": r must not be negative");
                rm = Math.max(rm, p[i * 2]);
                z0 = Math.min(z0, p[i * 2 + 1]);
                z1 = Math.max(z1, p[i * 2 + 1]);
            }
            rz = clean(p);
            require(Polygon2D.isSimple(rz), "G4GenericPolycone " + name + ": the (r, z) contour crosses itself");
            require(Math.abs(Polygon2D.signedArea(rz)) > 0, "G4GenericPolycone " + name + ": empty cross-section");
            box = clipPhi(rm, z0, z1);
        }

        public double sdf(double x, double y, double z) {
            return Math.max(rzDistance(rz, Math.sqrt(x * x + y * y), z), phiDistance(x, y));
        }

        public BBox extent() {
            return box;
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, rz, null, sphi, dphi, phiSegments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return revolvedVolume(rz, dphi);
        }

        public String cpp(CppNames n) {
            final Table t = table("rzpoint");
            final StringBuilder r = new StringBuilder(), z = new StringBuilder();
            for (int i = 0; i < t.size(); i++) {
                final String sep = i > 0 ? ", " : "";
                r.append(sep).append(c(t, i, 0));
                z.append(sep).append(c(t, i, 1));
            }
            return "[&] { const G4double r[] = {" + r + "}; const G4double z[] = {" + z
                + "}; return new G4GenericPolycone(" + n.string(name) + ", " + c("startphi") + ", "
                + c("deltaphi") + ", " + t.size() + ", r, z); }()";
        }
    }

    // ==================================================================
    // G4Polyhedra
    // ==================================================================

    public static final class Polyhedra extends PhiSolid {
        double[] rz;
        int sides;
        double step, rmaxAll, zmin, zmax;

        public Polyhedra(String name) {
            super(name);
            param("startphi", "Start phi", A, "0*deg");
            param("deltaphi", "Delta phi", A, "360*deg");
            param("numsides", "Number of sides", C, "6");
            final Table t = table("zplane", new String[] {"rmin", "rmax", "z"}, new Kind[] {L, L, L});
            t.add("20*mm", "50*mm", "-50*mm");
            t.add("20*mm", "50*mm", "50*mm");
        }

        public String tag() {
            return "polyhedra";
        }

        public String g4Class() {
            return "G4Polyhedra";
        }

        protected void validate() throws GeometryException {
            phi(v("startphi"), v("deltaphi"));
            if (dphi >= Units.TWOPI) {
                // a full polyhedra still turns its sides by startphi, as G4Polyhedra and TGeoPgon do
                sphi = v("startphi");
            }
            sides = (int) Math.round(v("numsides"));
            require(sides >= 1, "G4Polyhedra " + name + ": needs at least one side");
            require(dphi < Units.TWOPI || sides >= 3, "G4Polyhedra " + name + ": a full polyhedra needs three sides or more");
            step = dphi / sides;
            final Table t = table("zplane");
            require(t.size() >= 2, "G4Polyhedra " + name + ": needs at least two z planes");
            final int n = t.size();
            final double[] p = new double[n * 4];
            rmaxAll = 0;
            zmin = Double.MAX_VALUE;
            zmax = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                final double rmin = t.value(i, 0), rmax = t.value(i, 1), z = t.value(i, 2);
                require(rmin >= 0 && rmax >= rmin, "G4Polyhedra " + name + ": plane " + i + " needs 0 <= rmin <= rmax");
                if (i > 0) {
                    require(z >= t.value(i - 1, 2), "G4Polyhedra " + name + ": z planes must not decrease");
                }
                p[i * 2] = rmax;
                p[i * 2 + 1] = z;
                p[(2 * n - 1 - i) * 2] = rmin;
                p[(2 * n - 1 - i) * 2 + 1] = z;
                rmaxAll = Math.max(rmaxAll, rmax);
                zmin = Math.min(zmin, z);
                zmax = Math.max(zmax, z);
            }
            require(zmax > zmin, "G4Polyhedra " + name + ": zero length");
            rz = clean(p);
        }

        public double sdf(double x, double y, double z) {
            double a = Math.atan2(y, x) - sphi;
            a %= Units.TWOPI;
            if (a < 0) {
                a += Units.TWOPI;
            }
            int k = (int) Math.floor(a / step);
            if (k >= sides) {
                // Outside a partial segment: the nearer end sector
                k = (a - dphi < Units.TWOPI - a) ? sides - 1 : 0;
            }
            final double pc = sphi + (k + 0.5) * step;
            final double rp = x * Math.cos(pc) + y * Math.sin(pc);
            return Math.max(rzDistance(rz, rp, z), phiDistance(x, y));
        }

        public double safety() {
            return 0.7;
        }

        public BBox extent() {
            return clipPhi(rmaxAll / Math.cos(step / 2), zmin, zmax);
        }

        protected Mesh buildMesh(int q) {
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, rz, null, sphi, dphi, sides, sides);
            return mb.build();
        }

        protected double computeVolume() {
            return sides * 2 * Math.tan(step / 2) * rzMoment(rz);
        }

        public String cpp(CppNames n) {
            final Table t = table("zplane");
            final StringBuilder z = new StringBuilder(), rmin = new StringBuilder(), rmax = new StringBuilder();
            for (int i = 0; i < t.size(); i++) {
                final String sep = i > 0 ? ", " : "";
                rmin.append(sep).append(c(t, i, 0));
                rmax.append(sep).append(c(t, i, 1));
                z.append(sep).append(c(t, i, 2));
            }
            return "[&] { const G4double z[] = {" + z + "}; const G4double rmin[] = {" + rmin
                + "}; const G4double rmax[] = {" + rmax + "}; return new G4Polyhedra(" + n.string(name)
                + ", " + c("startphi") + ", " + c("deltaphi") + ", static_cast<G4int>(" + c("numsides") + "), " + t.size()
                + ", z, rmin, rmax); }()";
        }
    }

    // ==================================================================
    // G4EllipticalTube
    // ==================================================================

    public static final class EllipticalTube extends Solid {
        double dx, dy, dz;

        public EllipticalTube(String name) {
            super(name);
            param("dx", "Semi-axis x", L, "50*mm");
            param("dy", "Semi-axis y", L, "30*mm");
            param("dz", "Half length z", L, "50*mm");
        }

        public String tag() {
            return "eltube";
        }

        public String g4Class() {
            return "G4EllipticalTube";
        }

        protected void validate() throws GeometryException {
            dx = v("dx");
            dy = v("dy");
            dz = v("dz");
            require(dx > 0 && dy > 0 && dz > 0, "G4EllipticalTube " + name + ": dimensions must be positive");
        }

        public double sdf(double x, double y, double z) {
            final double e = (Math.sqrt((x / dx) * (x / dx) + (y / dy) * (y / dy)) - 1) * Math.min(dx, dy);
            return Math.max(e, Math.abs(z) - dz);
        }

        public BBox extent() {
            return BBox.centered(dx, dy, dz);
        }

        protected Mesh buildMesh(int q) {
            final int n = segments(q);
            final Mesh.Builder mb = new Mesh.Builder();
            final int base = mb.vertexCount();
            for (int i = 0; i <= n; i++) {
                final double t = Units.TWOPI * i / n, c = Math.cos(t), s = Math.sin(t);
                final Vec3 nr = new Vec3(c / dx, s / dy, 0).unit();
                mb.vertex(dx * c, dy * s, -dz, nr.x(), nr.y(), 0);
                mb.vertex(dx * c, dy * s, dz, nr.x(), nr.y(), 0);
            }
            for (int i = 0; i < n; i++) {
                final int a = base + i * 2;
                mb.quad(a, a + 2, a + 3, a + 1);
            }
            final Vec3[] top = new Vec3[n], bottom = new Vec3[n];
            for (int i = 0; i < n; i++) {
                final double t = Units.TWOPI * i / n;
                top[i] = new Vec3(dx * Math.cos(t), dy * Math.sin(t), dz);
                bottom[n - 1 - i] = new Vec3(dx * Math.cos(t), dy * Math.sin(t), -dz);
            }
            mb.flatPolygon(top);
            mb.flatPolygon(bottom);
            return mb.build();
        }

        protected double computeVolume() {
            return 2 * Math.PI * dx * dy * dz;
        }

        public String cpp(CppNames n) {
            return "new G4EllipticalTube(" + n.string(name) + ", " + c("dx") + ", " + c("dy") + ", " + c("dz") + ")";
        }
    }

    // ==================================================================
    // G4Ellipsoid
    // ==================================================================

    public static final class Ellipsoid extends Solid {
        double ax, by, cz, zlo, zhi;

        public Ellipsoid(String name) {
            super(name);
            param("ax", "Semi-axis x", L, "50*mm");
            param("by", "Semi-axis y", L, "40*mm");
            param("cz", "Semi-axis z", L, "30*mm");
            param("zcut1", "Bottom cut", L, "0*mm");
            param("zcut2", "Top cut", L, "0*mm");
        }

        public String tag() {
            return "ellipsoid";
        }

        public String g4Class() {
            return "G4Ellipsoid";
        }

        protected void validate() throws GeometryException {
            ax = v("ax");
            by = v("by");
            cz = v("cz");
            require(ax > 0 && by > 0 && cz > 0, "G4Ellipsoid " + name + ": semi-axes must be positive");
            zlo = v("zcut1");
            zhi = v("zcut2");
            // G4Ellipsoid::CheckParameters: both cuts zero mean no cut
            if (zlo == 0 && zhi == 0) {
                zlo = -cz;
                zhi = cz;
            }
            require(!(zlo >= cz || zhi <= -cz || zlo >= zhi), "G4Ellipsoid " + name + ": invalid z cuts");
            zlo = Math.max(zlo, -cz);
            zhi = Math.min(zhi, cz);
        }

        public double sdf(double x, double y, double z) {
            final double f = Math.sqrt((x / ax) * (x / ax) + (y / by) * (y / by) + (z / cz) * (z / cz));
            final double e = (f - 1) * Math.min(ax, Math.min(by, cz));
            return Math.max(e, Math.max(zlo - z, z - zhi));
        }

        public BBox extent() {
            final double s = zlo > 0 ? Math.sqrt(1 - (zlo / cz) * (zlo / cz))
                : zhi < 0 ? Math.sqrt(1 - (zhi / cz) * (zhi / cz)) : 1;
            return new BBox(-ax * s, -by * s, zlo, ax * s, by * s, zhi);
        }

        protected Mesh buildMesh(int q) {
            final int n = segments(q), m = Math.max(4, segments(q) / 2);
            final double t0 = Math.acos(Math.max(-1, Math.min(1, zhi / cz)));
            final double t1 = Math.acos(Math.max(-1, Math.min(1, zlo / cz)));
            final Mesh.Builder mb = new Mesh.Builder();
            final int base = mb.vertexCount();
            for (int j = 0; j <= m; j++) {
                final double t = t0 + (t1 - t0) * j / m;
                final double st = Math.sin(t), ctz = cz * Math.cos(t);
                for (int i = 0; i <= n; i++) {
                    final double p = Units.TWOPI * i / n;
                    final double x = ax * st * Math.cos(p), y = by * st * Math.sin(p);
                    final Vec3 nr = new Vec3(x / (ax * ax), y / (by * by), ctz / (cz * cz)).unit();
                    mb.vertex(x, y, ctz, nr.x(), nr.y(), nr.z());
                }
            }
            for (int j = 0; j < m; j++) {
                for (int i = 0; i < n; i++) {
                    final int a = base + j * (n + 1) + i, b = a + n + 1;
                    mb.quad(a, b, b + 1, a + 1);
                }
            }
            for (int end = 0; end < 2; end++) {
                final double zc = end == 0 ? zhi : zlo;
                if (Math.abs(zc) >= cz - 1e-12) {
                    continue;
                }
                final double s = Math.sqrt(1 - (zc / cz) * (zc / cz));
                final Vec3[] ring = new Vec3[n];
                for (int i = 0; i < n; i++) {
                    final double p = Units.TWOPI * (end == 0 ? i : n - i) / n;
                    ring[i] = new Vec3(ax * s * Math.cos(p), by * s * Math.sin(p), zc);
                }
                mb.flatPolygon(ring);
            }
            return mb.build();
        }

        protected double computeVolume() {
            return Math.PI * ax * by * ((zhi - zlo) - (zhi * zhi * zhi - zlo * zlo * zlo) / (3 * cz * cz));
        }

        public String cpp(CppNames n) {
            return "new G4Ellipsoid(" + n.string(name) + ", " + c("ax") + ", " + c("by") + ", " + c("cz")
                + ", " + c("zcut1") + ", " + c("zcut2") + ")";
        }
    }

    // ==================================================================
    // G4EllipticalCone
    // ==================================================================

    public static final class EllipticalCone extends Solid {
        double dx, dy, h, zcut;

        public EllipticalCone(String name) {
            super(name);
            param("dx", "Semi-axis slope x", N, "0.5");
            param("dy", "Semi-axis slope y", N, "0.3");
            param("zmax", "Height of apex", L, "100*mm");
            param("zcut", "Half length", L, "50*mm");
        }

        public String tag() {
            return "elcone";
        }

        public String g4Class() {
            return "G4EllipticalCone";
        }

        protected void validate() throws GeometryException {
            dx = v("dx");
            dy = v("dy");
            h = v("zmax");
            zcut = v("zcut");
            require(dx > 0 && dy > 0 && h > 0 && zcut > 0, "G4EllipticalCone " + name + ": parameters must be positive");
            zcut = Math.min(zcut, h);
        }

        public double sdf(double x, double y, double z) {
            final double f = Math.sqrt((x / dx) * (x / dx) + (y / dy) * (y / dy)) - (h - z);
            final double lat = f / Math.sqrt(2) * Math.min(1, Math.min(dx, dy));
            return Math.max(lat, Math.abs(z) - zcut);
        }

        public BBox extent() {
            final double s = h + zcut;
            return new BBox(-dx * s, -dy * s, -zcut, dx * s, dy * s, zcut);
        }

        protected Mesh buildMesh(int q) {
            final int n = segments(q);
            final Mesh.Builder mb = new Mesh.Builder();
            final int base = mb.vertexCount();
            for (int i = 0; i <= n; i++) {
                final double t = Units.TWOPI * i / n, c = Math.cos(t), s = Math.sin(t);
                for (int e = 0; e < 2; e++) {
                    final double z = e == 0 ? -zcut : zcut, k = h - z;
                    final double x = dx * k * c, y = dy * k * s;
                    final Vec3 nr = new Vec3(c / dx, s / dy, 1).unit();
                    mb.vertex(x, y, z, nr.x(), nr.y(), nr.z());
                }
            }
            for (int i = 0; i < n; i++) {
                final int a = base + i * 2;
                mb.quad(a, a + 2, a + 3, a + 1);
            }
            for (int e = 0; e < 2; e++) {
                final double z = e == 0 ? zcut : -zcut, k = h - z;
                if (k <= 1e-12) {
                    continue;
                }
                final Vec3[] ring = new Vec3[n];
                for (int i = 0; i < n; i++) {
                    final double t = Units.TWOPI * (e == 0 ? i : n - i) / n;
                    ring[i] = new Vec3(dx * k * Math.cos(t), dy * k * Math.sin(t), z);
                }
                mb.flatPolygon(ring);
            }
            return mb.build();
        }

        protected double computeVolume() {
            final double a = h + zcut, b = h - zcut;
            return Math.PI * dx * dy * (a * a * a - b * b * b) / 3.0;
        }

        public String cpp(CppNames n) {
            return "new G4EllipticalCone(" + n.string(name) + ", " + c("dx") + ", " + c("dy") + ", "
                + c("zmax") + ", " + c("zcut") + ")";
        }
    }

    // ==================================================================
    // G4Paraboloid
    // ==================================================================

    public static final class Paraboloid extends Solid {
        double rlo, rhi, dz, k1, k2;

        public Paraboloid(String name) {
            super(name);
            param("rlo", "Radius at -dz", L, "10*mm");
            param("rhi", "Radius at +dz", L, "50*mm");
            param("dz", "Half length z", L, "50*mm");
        }

        public String tag() {
            return "paraboloid";
        }

        public String g4Class() {
            return "G4Paraboloid";
        }

        protected void validate() throws GeometryException {
            rlo = v("rlo");
            rhi = v("rhi");
            dz = v("dz");
            require(dz > 0 && rhi > rlo && rlo >= 0, "G4Paraboloid " + name + ": needs dz > 0 and 0 <= rlo < rhi");
            k1 = (rhi * rhi - rlo * rlo) / (2 * dz);
            k2 = (rhi * rhi + rlo * rlo) / 2;
        }

        double radius(double z) {
            return Math.sqrt(Math.max(0, k1 * z + k2));
        }

        public double sdf(double x, double y, double z) {
            final double zc = Math.max(-dz, Math.min(dz, z));
            final double R = radius(zc);
            final double slope = R > 1e-9 ? k1 / (2 * R) : 1e9;
            final double lat = (Math.sqrt(x * x + y * y) - R) / Math.sqrt(1 + slope * slope);
            return Math.max(lat, Math.abs(z) - dz);
        }

        public double safety() {
            return 0.8;
        }

        public BBox extent() {
            return BBox.centered(rhi, rhi, dz);
        }

        protected Mesh buildMesh(int q) {
            final int m = Math.max(8, segments(q) / 2);
            final List<double[]> pts = new ArrayList<>();
            pts.add(new double[] {0, -dz, 0});
            for (int i = 0; i <= m; i++) {
                final double z = -dz + 2 * dz * i / m;
                pts.add(new double[] {radius(z), z, i == 0 || i == m ? 0 : 1});
            }
            pts.add(new double[] {0, dz, 0});
            final Mesh.Builder mb = new Mesh.Builder();
            final double[] rz = flat(pts);
            Revolve.build(mb, rz, smooth(pts), 0, Units.TWOPI, segments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return Math.PI * dz * (rlo * rlo + rhi * rhi);
        }

        public String cpp(CppNames n) {
            return "new G4Paraboloid(" + n.string(name) + ", " + c("dz") + ", " + c("rlo") + ", " + c("rhi") + ")";
        }
    }

    // ==================================================================
    // G4Hype
    // ==================================================================

    public static final class Hype extends Solid {
        double rmin, rmax, tin2, tout2, dz;

        public Hype(String name) {
            super(name);
            param("rmin", "Inner radius at z=0", L, "10*mm");
            param("rmax", "Outer radius at z=0", L, "30*mm");
            param("inst", "Inner stereo", A, "20*deg");
            param("outst", "Outer stereo", A, "30*deg");
            param("z", "Length z", L, "100*mm");
        }

        public String tag() {
            return "hype";
        }

        public String g4Class() {
            return "G4Hype";
        }

        protected void validate() throws GeometryException {
            rmin = v("rmin");
            rmax = v("rmax");
            dz = v("z") / 2;
            tin2 = Math.pow(Math.tan(v("inst")), 2);
            tout2 = Math.pow(Math.tan(v("outst")), 2);
            require(dz > 0 && rmin >= 0 && rmax > rmin, "G4Hype " + name + ": needs dz > 0 and 0 <= rmin < rmax");
            require(rmin * rmin + tin2 * dz * dz < rmax * rmax + tout2 * dz * dz,
                "G4Hype " + name + ": the inner surface crosses the outer one");
        }

        double rout(double z) {
            return Math.sqrt(rmax * rmax + tout2 * z * z);
        }

        double rin(double z) {
            return Math.sqrt(rmin * rmin + tin2 * z * z);
        }

        public double sdf(double x, double y, double z) {
            final double r = Math.sqrt(x * x + y * y);
            final double zc = Math.max(-dz, Math.min(dz, z));
            final double ro = rout(zc), so = ro > 0 ? tout2 * zc / ro : 0;
            double d = (r - ro) / Math.sqrt(1 + so * so);
            if (rmin > 0 || tin2 > 0) {
                final double ri = rin(zc), si = ri > 0 ? tin2 * zc / ri : 0;
                d = Math.max(d, (ri - r) / Math.sqrt(1 + si * si));
            }
            return Math.max(d, Math.abs(z) - dz);
        }

        public double safety() {
            return 0.8;
        }

        public BBox extent() {
            final double r = rout(dz);
            return BBox.centered(r, r, dz);
        }

        protected Mesh buildMesh(int q) {
            final int m = Math.max(8, segments(q) / 2);
            final List<double[]> pts = new ArrayList<>();
            for (int i = 0; i <= m; i++) {
                final double z = -dz + 2 * dz * i / m;
                pts.add(new double[] {rout(z), z, i == 0 || i == m ? 0 : 1});
            }
            for (int i = m; i >= 0; i--) {
                final double z = -dz + 2 * dz * i / m;
                pts.add(new double[] {rin(z), z, i == 0 || i == m ? 0 : 1});
            }
            final double[] rz = clean(flat(pts));
            final boolean[] sm = new boolean[rz.length / 2];
            for (int i = 0; i < sm.length && i < pts.size(); i++) {
                sm[i] = pts.get(i)[2] != 0;
            }
            final Mesh.Builder mb = new Mesh.Builder();
            Revolve.build(mb, rz, sm, 0, Units.TWOPI, segments(q), 0);
            return mb.build();
        }

        protected double computeVolume() {
            return Math.PI * (2 * dz * (rmax * rmax - rmin * rmin) + (tout2 - tin2) * 2 * dz * dz * dz / 3.0);
        }

        public String cpp(CppNames n) {
            return "new G4Hype(" + n.string(name) + ", " + c("rmin") + ", " + c("rmax") + ", " + c("inst")
                + ", " + c("outst") + ", " + half(c("z")) + ")";
        }
    }
}
