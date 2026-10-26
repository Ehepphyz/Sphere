package com.sphere.core.geant4;

/**
 * G4ExtrudedSolid: a planar polygon swept along z through sections that may
 * shift and scale it. The editor's sketcher produces these: a polygon drawn
 * with the mouse, then pulled into a solid.
 */
public final class Extruded extends Solid {

    private static final Kind L = Kind.LENGTH;
    private static final Kind N = Kind.NUMBER;

    double[] poly;     // x y, counter-clockwise
    double[] zs, ox, oy, sc;
    double safety = 1;
    double polyArea;
    BBox box;

    public Extruded(String name) {
        super(name);
        final Table v = table("twoDimVertex", new String[] {"x", "y"}, new Kind[] {L, L});
        v.add("-50*mm", "-50*mm");
        v.add("50*mm", "-50*mm");
        v.add("50*mm", "50*mm");
        v.add("0*mm", "20*mm");
        v.add("-50*mm", "50*mm");
        final Table s = table("section", new String[] {"zPosition", "xOffset", "yOffset", "scalingFactor"},
            new Kind[] {L, L, L, N});
        s.add("-50*mm", "0*mm", "0*mm", "1");
        s.add("50*mm", "0*mm", "0*mm", "1");
    }

    public String tag() {
        return "xtru";
    }

    public String g4Class() {
        return "G4ExtrudedSolid";
    }

    protected void validate() throws GeometryException {
        final Table v = table("twoDimVertex"), s = table("section");
        require(v.size() >= 3, "G4ExtrudedSolid " + name + ": the polygon needs three vertices");
        require(s.size() >= 2, "G4ExtrudedSolid " + name + ": needs two sections");
        double[] p = new double[v.size() * 2];
        for (int i = 0; i < v.size(); i++) {
            p[i * 2] = v.value(i, 0);
            p[i * 2 + 1] = v.value(i, 1);
        }
        p = Shapes.clean(p);
        require(p.length >= 6, "G4ExtrudedSolid " + name + ": the polygon is degenerate");
        require(Polygon2D.isSimple(p), "G4ExtrudedSolid " + name + ": the polygon crosses itself");
        double a = Polygon2D.signedArea(p);
        require(Math.abs(a) > 0, "G4ExtrudedSolid " + name + ": the polygon has no area");
        if (a < 0) {
            final int n = p.length / 2;
            final double[] r = new double[p.length];
            for (int i = 0; i < n; i++) {
                r[i * 2] = p[(n - 1 - i) * 2];
                r[i * 2 + 1] = p[(n - 1 - i) * 2 + 1];
            }
            p = r;
            a = -a;
        }
        poly = p;
        polyArea = a;
        final int ns = s.size();
        zs = new double[ns];
        ox = new double[ns];
        oy = new double[ns];
        sc = new double[ns];
        for (int i = 0; i < ns; i++) {
            zs[i] = s.value(i, 0);
            ox[i] = s.value(i, 1);
            oy[i] = s.value(i, 2);
            sc[i] = s.value(i, 3);
            require(sc[i] > 0, "G4ExtrudedSolid " + name + ": scaling factors must be positive");
            if (i > 0) {
                require(zs[i] > zs[i - 1], "G4ExtrudedSolid " + name + ": sections must be ordered in z");
            }
        }
        // The walls lean where sections shift or scale: in-plane distances overestimate then.
        double rmax = 0;
        for (int i = 0; i < poly.length; i += 2) {
            rmax = Math.max(rmax, Math.hypot(poly[i], poly[i + 1]));
        }
        double slope = 0;
        BBox b = BBox.EMPTY;
        for (int i = 0; i < ns; i++) {
            if (i > 0) {
                final double dz = zs[i] - zs[i - 1];
                final double ds = Math.abs(sc[i] - sc[i - 1]) * rmax + Math.hypot(ox[i] - ox[i - 1], oy[i] - oy[i - 1]);
                slope = Math.max(slope, ds / dz);
            }
            for (int k = 0; k < poly.length; k += 2) {
                b = b.include(new Vec3(poly[k] * sc[i] + ox[i], poly[k + 1] * sc[i] + oy[i], zs[i]));
            }
        }
        safety = 1 / Math.sqrt(1 + slope * slope);
        box = b;
    }

    public double sdf(double x, double y, double z) {
        final int ns = zs.length;
        final double zc = Math.max(zs[0], Math.min(zs[ns - 1], z));
        int i = 0;
        while (i < ns - 2 && zc > zs[i + 1]) {
            i++;
        }
        final double t = (zc - zs[i]) / (zs[i + 1] - zs[i]);
        final double s = sc[i] + t * (sc[i + 1] - sc[i]);
        final double cx = ox[i] + t * (ox[i + 1] - ox[i]), cy = oy[i] + t * (oy[i + 1] - oy[i]);
        final double d2 = Polygon2D.signedDistance(poly, (x - cx) / s, (y - cy) / s) * s;
        return Math.max(d2, Math.max(zs[0] - z, z - zs[ns - 1]));
    }

    public double safety() {
        return safety;
    }

    public BBox extent() {
        return box;
    }

    protected Mesh buildMesh(int q) {
        final Mesh.Builder mb = new Mesh.Builder();
        final int n = poly.length / 2, ns = zs.length;
        for (int k = 0; k + 1 < ns; k++) {
            for (int i = 0; i < n; i++) {
                final int j = (i + 1) % n;
                final Vec3 a0 = at(i, k), b0 = at(j, k), b1 = at(j, k + 1), a1 = at(i, k + 1);
                mb.flatPolygon(new Vec3[] {a0, b0, b1, a1});
            }
        }
        final Vec3[] top = new Vec3[n], bottom = new Vec3[n];
        for (int i = 0; i < n; i++) {
            top[i] = at(i, ns - 1);
            bottom[n - 1 - i] = at(i, 0);
        }
        mb.flatPolygon(top);
        mb.flatPolygon(bottom);
        return mb.build();
    }

    private Vec3 at(int vertex, int section) {
        return new Vec3(poly[vertex * 2] * sc[section] + ox[section],
                        poly[vertex * 2 + 1] * sc[section] + oy[section], zs[section]);
    }

    protected double computeVolume() {
        double v = 0;
        for (int k = 0; k + 1 < zs.length; k++) {
            final double s0 = sc[k], s1 = sc[k + 1];
            v += (zs[k + 1] - zs[k]) * (s0 * s0 + s0 * s1 + s1 * s1) / 3;
        }
        return v * polyArea;
    }

    /** Replaces the polygon, e.g. with what the sketcher drew; vertices in internal units. */
    public void setPolygon(double[] xy) {
        final Table v = table("twoDimVertex");
        v.clear();
        for (int i = 0; i < xy.length; i += 2) {
            v.add(Units.num(xy[i]) + "*mm", Units.num(xy[i + 1]) + "*mm");
        }
    }

    public void setSpan(String zlow, String zhigh) {
        final Table s = table("section");
        s.clear();
        s.add(zlow, "0*mm", "0*mm", "1");
        s.add(zhigh, "0*mm", "0*mm", "1");
    }

    public String cpp(CppNames n) {
        final Table v = table("twoDimVertex"), s = table("section");
        final StringBuilder b = new StringBuilder("[&] { std::vector<G4TwoVector> polygon = {");
        for (int i = 0; i < v.size(); i++) {
            b.append(i > 0 ? ", " : "").append("G4TwoVector(").append(c(v, i, 0)).append(", ")
             .append(c(v, i, 1)).append(")");
        }
        b.append("}; std::vector<G4ExtrudedSolid::ZSection> zsections = {");
        for (int i = 0; i < s.size(); i++) {
            b.append(i > 0 ? ", " : "").append("G4ExtrudedSolid::ZSection(").append(c(s, i, 0))
             .append(", G4TwoVector(").append(c(s, i, 1)).append(", ").append(c(s, i, 2)).append("), ")
             .append(c(s, i, 3)).append(")");
        }
        return b.append("}; return new G4ExtrudedSolid(").append(n.string(name))
                .append(", polygon, zsections); }()").toString();
    }
}
