package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;

/**
 * G4TessellatedSolid and ROOT's TGeoTessellated: a closed surface of
 * triangles and planar quadrilaterals. The vertices are a table of points;
 * each facet lists three or four of them by index (the fourth is -1 for a
 * triangle), counter-clockwise seen from outside, as both toolkits want them.
 *
 * It is also what a shape Geant4 has no class for becomes when a ROOT file
 * brings one in: its surface, exactly as ROOT would draw it.
 */
public final class Tessellated extends Solid {

    private static final Kind L = Kind.LENGTH;
    private static final Kind C = Kind.COUNT;

    /** Triangles after splitting the quadrilaterals: 9 coordinates each. */
    private double[] tri = new double[0];
    private BBox box = BBox.EMPTY;

    public Tessellated(String name) {
        super(name);
        final Table v = table("vertex", new String[] {"x", "y", "z"}, new Kind[] {L, L, L});
        v.add("0*mm", "0*mm", "0*mm");
        v.add("60*mm", "0*mm", "0*mm");
        v.add("0*mm", "60*mm", "0*mm");
        v.add("0*mm", "0*mm", "60*mm");
        final Table f = table("facet", new String[] {"v1", "v2", "v3", "v4"}, new Kind[] {C, C, C, C});
        f.add("0", "2", "1", "-1");
        f.add("0", "1", "3", "-1");
        f.add("0", "3", "2", "-1");
        f.add("1", "2", "3", "-1");
    }

    public String tag() {
        return "tessellated";
    }

    public String g4Class() {
        return "G4TessellatedSolid";
    }

    @Override
    public String description() {
        return "G4TessellatedSolid, " + table("facet").size() + " facets";
    }

    /** Replaces the surface: vertices in internal units, facets as index quadruples (-1 ends a triangle). */
    public void setSurface(List<double[]> vertices, List<int[]> facets) {
        final Table v = table("vertex"), f = table("facet");
        v.clear();
        f.clear();
        for (double[] p : vertices) {
            v.add(Units.num(p[0]) + "*mm", Units.num(p[1]) + "*mm", Units.num(p[2]) + "*mm");
        }
        for (int[] q : facets) {
            f.add(String.valueOf(q[0]), String.valueOf(q[1]), String.valueOf(q[2]),
                String.valueOf(q.length > 3 ? q[3] : -1));
        }
    }

    /** The evaluated vertices, as {x, y, z}. */
    public List<double[]> vertices() {
        final Table v = table("vertex");
        final List<double[]> out = new ArrayList<>(v.size());
        for (int i = 0; i < v.size(); i++) {
            out.add(new double[] {v.value(i, 0), v.value(i, 1), v.value(i, 2)});
        }
        return out;
    }

    /** The facets as index lists of three or four. */
    public List<int[]> facets() {
        final Table f = table("facet");
        final List<int[]> out = new ArrayList<>(f.size());
        for (int i = 0; i < f.size(); i++) {
            final int d = (int) Math.round(f.value(i, 3));
            out.add(d < 0
                ? new int[] {(int) Math.round(f.value(i, 0)), (int) Math.round(f.value(i, 1)), (int) Math.round(f.value(i, 2))}
                : new int[] {(int) Math.round(f.value(i, 0)), (int) Math.round(f.value(i, 1)), (int) Math.round(f.value(i, 2)), d});
        }
        return out;
    }

    protected void validate() throws GeometryException {
        final List<double[]> v = vertices();
        final List<int[]> f = facets();
        require(f.size() >= 4, "G4TessellatedSolid " + name + ": a closed surface needs four facets");
        final List<Double> t = new ArrayList<>();
        BBox b = BBox.EMPTY;
        for (double[] p : v) {
            b = b.include(new Vec3(p[0], p[1], p[2]));
        }
        for (int k = 0; k < f.size(); k++) {
            final int[] q = f.get(k);
            for (int i : q) {
                require(i >= 0 && i < v.size(), "G4TessellatedSolid " + name + ": facet " + k + " names vertex " + i
                    + " of " + v.size());
            }
            for (int s = 1; s + 1 < q.length; s++) {
                for (int i : new int[] {q[0], q[s], q[s + 1]}) {
                    t.add(v.get(i)[0]);
                    t.add(v.get(i)[1]);
                    t.add(v.get(i)[2]);
                }
            }
        }
        tri = new double[t.size()];
        for (int i = 0; i < tri.length; i++) {
            tri[i] = t.get(i);
        }
        box = b;
        require(!box.isEmpty() && box.volume() > 0, "G4TessellatedSolid " + name + ": the surface encloses nothing");
        // Facets listed clockwise everywhere still make a solid: turn them around
        if (signedVolume() < 0) {
            for (int i = 0; i < tri.length; i += 9) {
                for (int c = 0; c < 3; c++) {
                    final double s = tri[i + 3 + c];
                    tri[i + 3 + c] = tri[i + 6 + c];
                    tri[i + 6 + c] = s;
                }
            }
        }
    }

    private double signedVolume() {
        double v = 0;
        for (int i = 0; i < tri.length; i += 9) {
            v += tri[i] * (tri[i + 4] * tri[i + 8] - tri[i + 5] * tri[i + 7])
               - tri[i + 1] * (tri[i + 3] * tri[i + 8] - tri[i + 5] * tri[i + 6])
               + tri[i + 2] * (tri[i + 3] * tri[i + 7] - tri[i + 4] * tri[i + 6]);
        }
        return v / 6;
    }

    /**
     * The distance to the nearest facet, signed by the winding number (the
     * solid angle the surface subtends, over 4 pi): robust for a closed
     * surface, whatever its facets' sizes. Outside the bounding box the
     * distance to the box is enough, and costs nothing.
     */
    public double sdf(double x, double y, double z) {
        final double ox = Math.max(Math.max(box.minX() - x, x - box.maxX()), 0);
        final double oy = Math.max(Math.max(box.minY() - y, y - box.maxY()), 0);
        final double oz = Math.max(Math.max(box.minZ() - z, z - box.maxZ()), 0);
        final double outside = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (outside > 1e-6 * Math.max(1, box.diagonal())) {
            return outside;
        }
        double d2 = Double.MAX_VALUE;
        double winding = 0;
        for (int i = 0; i < tri.length; i += 9) {
            d2 = Math.min(d2, pointTriangle2(x, y, z, i));
            winding += solidAngle(x, y, z, i);
        }
        final double d = Math.sqrt(d2);
        return Math.abs(winding) > 2 * Math.PI ? -d : d;
    }

    /** Van Oosterom and Strackee's formula for the solid angle of one triangle. */
    private double solidAngle(double x, double y, double z, int i) {
        final double ax = tri[i] - x, ay = tri[i + 1] - y, az = tri[i + 2] - z;
        final double bx = tri[i + 3] - x, by = tri[i + 4] - y, bz = tri[i + 5] - z;
        final double cx = tri[i + 6] - x, cy = tri[i + 7] - y, cz = tri[i + 8] - z;
        final double la = Math.sqrt(ax * ax + ay * ay + az * az);
        final double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        final double lc = Math.sqrt(cx * cx + cy * cy + cz * cz);
        final double det = ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
        final double div = la * lb * lc + (ax * bx + ay * by + az * bz) * lc + (ax * cx + ay * cy + az * cz) * lb
            + (bx * cx + by * cy + bz * cz) * la;
        return 2 * Math.atan2(det, div);
    }

    /** Squared distance from a point to triangle i (Ericson, Real-Time Collision Detection 5.1.5). */
    private double pointTriangle2(double px, double py, double pz, int i) {
        final double ax = tri[i], ay = tri[i + 1], az = tri[i + 2];
        final double abx = tri[i + 3] - ax, aby = tri[i + 4] - ay, abz = tri[i + 5] - az;
        final double acx = tri[i + 6] - ax, acy = tri[i + 7] - ay, acz = tri[i + 8] - az;
        final double apx = px - ax, apy = py - ay, apz = pz - az;
        final double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        double qx, qy, qz;
        if (d1 <= 0 && d2 <= 0) {
            qx = ax;
            qy = ay;
            qz = az;
        } else {
            final double bpx = px - tri[i + 3], bpy = py - tri[i + 4], bpz = pz - tri[i + 5];
            final double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
            final double cpx = px - tri[i + 6], cpy = py - tri[i + 7], cpz = pz - tri[i + 8];
            final double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
            final double vc = d1 * d4 - d3 * d2, vb = d5 * d2 - d1 * d6, va = d3 * d6 - d5 * d4;
            if (d3 >= 0 && d4 <= d3) {
                qx = tri[i + 3];
                qy = tri[i + 4];
                qz = tri[i + 5];
            } else if (d6 >= 0 && d5 <= d6) {
                qx = tri[i + 6];
                qy = tri[i + 7];
                qz = tri[i + 8];
            } else if (vc <= 0 && d1 >= 0 && d3 <= 0) {
                final double v = d1 / (d1 - d3);
                qx = ax + v * abx;
                qy = ay + v * aby;
                qz = az + v * abz;
            } else if (vb <= 0 && d2 >= 0 && d6 <= 0) {
                final double w = d2 / (d2 - d6);
                qx = ax + w * acx;
                qy = ay + w * acy;
                qz = az + w * acz;
            } else if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                final double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                qx = tri[i + 3] + w * (tri[i + 6] - tri[i + 3]);
                qy = tri[i + 4] + w * (tri[i + 7] - tri[i + 4]);
                qz = tri[i + 5] + w * (tri[i + 8] - tri[i + 5]);
            } else {
                final double denom = 1 / (va + vb + vc);
                final double v = vb * denom, w = vc * denom;
                qx = ax + abx * v + acx * w;
                qy = ay + aby * v + acy * w;
                qz = az + abz * v + acz * w;
            }
        }
        final double dx = px - qx, dy = py - qy, dz = pz - qz;
        return dx * dx + dy * dy + dz * dz;
    }

    public BBox extent() {
        return box;
    }

    protected Mesh buildMesh(int q) {
        final Mesh.Builder mb = new Mesh.Builder();
        for (int i = 0; i < tri.length; i += 9) {
            mb.flatTriangle(new Vec3(tri[i], tri[i + 1], tri[i + 2]), new Vec3(tri[i + 3], tri[i + 4], tri[i + 5]),
                new Vec3(tri[i + 6], tri[i + 7], tri[i + 8]));
        }
        return mb.build();
    }

    protected double computeVolume() {
        return Math.abs(signedVolume());
    }

    protected double computeArea() {
        double a = 0;
        for (int i = 0; i < tri.length; i += 9) {
            final Vec3 p = new Vec3(tri[i], tri[i + 1], tri[i + 2]);
            a += new Vec3(tri[i + 3], tri[i + 4], tri[i + 5]).sub(p).cross(new Vec3(tri[i + 6], tri[i + 7], tri[i + 8]).sub(p))
                .length() / 2;
        }
        return a;
    }

    public String cpp(CppNames n) {
        final Table v = table("vertex");
        final List<int[]> f = facets();
        final StringBuilder b = new StringBuilder("[&] { auto* s = new G4TessellatedSolid(").append(n.string(name))
            .append("); std::vector<G4ThreeVector> v = {");
        for (int i = 0; i < v.size(); i++) {
            b.append(i > 0 ? ", " : "").append("G4ThreeVector(").append(c(v, i, 0)).append(", ").append(c(v, i, 1))
             .append(", ").append(c(v, i, 2)).append(")");
        }
        b.append("};");
        for (int[] q : f) {
            if (q.length == 3) {
                b.append(" s->AddFacet(new G4TriangularFacet(v[").append(q[0]).append("], v[").append(q[1]).append("], v[")
                 .append(q[2]).append("], ABSOLUTE));");
            } else {
                b.append(" s->AddFacet(new G4QuadrangularFacet(v[").append(q[0]).append("], v[").append(q[1])
                 .append("], v[").append(q[2]).append("], v[").append(q[3]).append("], ABSOLUTE));");
            }
        }
        return b.append(" s->SetSolidClosed(true); return s; }()").toString();
    }
}
