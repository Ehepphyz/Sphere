package com.sphere.core.geant4;

/**
 * Tessellates solids of revolution from their (r, z) cross-section: G4Tubs,
 * G4Cons, G4Polycone, G4GenericPolycone, G4Sphere, G4Torus, and G4Polyhedra
 * with flat sides.
 */
final class Revolve {

    private Revolve() {
    }

    /**
     * @param rz      cross-section, any orientation (made counter-clockwise here)
     * @param smooth  per vertex: true where the profile is a curve sampled, so the
     *                normals of the two edges meeting there are averaged
     * @param sphi    start angle
     * @param dphi    angular extent
     * @param nphi    segments over dphi
     * @param sides   0 for a smooth surface of revolution, else the number of flat
     *                sides over dphi (G4Polyhedra; rz then holds apothems)
     */
    static void build(Mesh.Builder mb, double[] rz, boolean[] smooth, double sphi, double dphi,
                      int nphi, int sides) {
        build(mb, rz, smooth, sphi, dphi, nphi, sides, true, false);
    }

    /**
     * @param caps   close a partial turn with the cross-section
     * @param invert the profile bounds a hole: normals point into it
     */
    static void build(Mesh.Builder mb, double[] rz, boolean[] smooth, double sphi, double dphi,
                      int nphi, int sides, boolean caps, boolean invert) {
        int n = rz.length / 2;
        if (n < 3) {
            return;
        }
        if (Polygon2D.signedArea(rz) < 0) {
            final double[] r2 = new double[rz.length];
            final boolean[] s2 = new boolean[n];
            for (int i = 0; i < n; i++) {
                r2[i * 2] = rz[(n - 1 - i) * 2];
                r2[i * 2 + 1] = rz[(n - 1 - i) * 2 + 1];
                s2[i] = smooth != null && smooth[n - 1 - i];
            }
            rz = r2;
            smooth = s2;
        }
        final boolean full = dphi >= Units.TWOPI - 1e-12;
        final int steps = sides > 0 ? sides : Math.max(1, nphi);
        final double dstep = dphi / steps;
        final double corner = sides > 0 ? 1.0 / Math.cos(dstep / 2) : 1.0;

        // Edge normals in the meridian plane: (dz, -dr) is outward for a CCW profile.
        final double[] en = new double[n * 2];
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            final double dr = rz[j * 2] - rz[i * 2], dz = rz[j * 2 + 1] - rz[i * 2 + 1];
            final double l = Math.hypot(dr, dz);
            en[i * 2] = l > 0 ? dz / l : 0;
            en[i * 2 + 1] = l > 0 ? -dr / l : 0;
            if (invert) {
                en[i * 2] = -en[i * 2];
                en[i * 2 + 1] = -en[i * 2 + 1];
            }
        }

        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            final double ra = rz[i * 2], za = rz[i * 2 + 1], rb = rz[j * 2], zb = rz[j * 2 + 1];
            if (ra == 0 && rb == 0) {
                continue; // on the axis
            }
            if (ra == rb && za == zb) {
                continue;
            }
            // Vertex normals at both ends: the edge's own, or averaged with the neighbour's
            double nar = en[i * 2], naz = en[i * 2 + 1], nbr = nar, nbz = naz;
            if (sides == 0 && smooth != null) {
                final int p = (i + n - 1) % n;
                if (smooth[i]) {
                    nar = en[p * 2] + en[i * 2];
                    naz = en[p * 2 + 1] + en[i * 2 + 1];
                    final double l = Math.hypot(nar, naz);
                    nar /= l;
                    naz /= l;
                }
                if (smooth[j]) {
                    nbr = en[i * 2] + en[j * 2];
                    nbz = en[i * 2 + 1] + en[j * 2 + 1];
                    final double l = Math.hypot(nbr, nbz);
                    nbr /= l;
                    nbz /= l;
                }
            }
            if (sides > 0) {
                for (int k = 0; k < steps; k++) {
                    final double p0 = sphi + k * dstep, p1 = p0 + dstep, pc = p0 + dstep / 2;
                    final double cx = Math.cos(pc), cy = Math.sin(pc);
                    final double nx = en[i * 2] * cx, ny = en[i * 2] * cy, nz = en[i * 2 + 1];
                    final double c0 = Math.cos(p0) * corner, s0 = Math.sin(p0) * corner;
                    final double c1 = Math.cos(p1) * corner, s1 = Math.sin(p1) * corner;
                    final int a0 = mb.vertex(ra * c0, ra * s0, za, nx, ny, nz);
                    final int a1 = mb.vertex(ra * c1, ra * s1, za, nx, ny, nz);
                    final int b1 = mb.vertex(rb * c1, rb * s1, zb, nx, ny, nz);
                    final int b0 = mb.vertex(rb * c0, rb * s0, zb, nx, ny, nz);
                    tri(mb, ra != 0, a0, a1, b1, invert);
                    tri(mb, rb != 0, a0, b1, b0, invert);
                }
            } else {
                final int base = mb.vertexCount();
                for (int k = 0; k <= steps; k++) {
                    final double p = sphi + k * dstep;
                    final double c = Math.cos(p), s = Math.sin(p);
                    mb.vertex(ra * c, ra * s, za, nar * c, nar * s, naz);
                    mb.vertex(rb * c, rb * s, zb, nbr * c, nbr * s, nbz);
                }
                for (int k = 0; k < steps; k++) {
                    final int a0 = base + k * 2, b0 = a0 + 1, a1 = a0 + 2, b1 = a0 + 3;
                    tri(mb, ra != 0, a0, a1, b1, invert);
                    tri(mb, rb != 0, a0, b1, b0, invert);
                }
            }
        }

        if (!full && caps) {
            cap(mb, rz, sphi, corner, true);
            cap(mb, rz, sphi + dphi, corner, false);
        }
    }

    private static void tri(Mesh.Builder mb, boolean keep, int a, int b, int c, boolean invert) {
        if (!keep) {
            return;
        }
        if (invert) {
            mb.triangle(a, c, b);
        } else {
            mb.triangle(a, b, c);
        }
    }

    /** The cross-section itself, closing a solid that spans less than a full turn. */
    private static void cap(Mesh.Builder mb, double[] rz, double phi, double corner, boolean start) {
        final int[] tris = Polygon2D.triangulate(rz);
        final double c = Math.cos(phi) * corner, s = Math.sin(phi) * corner;
        final double nx = start ? Math.sin(phi) : -Math.sin(phi);
        final double ny = start ? -Math.cos(phi) : Math.cos(phi);
        final int base = mb.vertexCount();
        final int n = rz.length / 2;
        for (int i = 0; i < n; i++) {
            mb.vertex(rz[i * 2] * c, rz[i * 2] * s, rz[i * 2 + 1], nx, ny, 0);
        }
        for (int i = 0; i < tris.length; i += 3) {
            if (start) {
                mb.triangle(base + tris[i], base + tris[i + 1], base + tris[i + 2]);
            } else {
                mb.triangle(base + tris[i], base + tris[i + 2], base + tris[i + 1]);
            }
        }
    }

    /** Closed annulus caps for a ring section with a hole (torus with rmin). */
    static void annulusCap(Mesh.Builder mb, double[] outer, double[] inner, double phi, boolean start) {
        final int n = outer.length / 2;
        final double c = Math.cos(phi), s = Math.sin(phi);
        final double nx = start ? Math.sin(phi) : -Math.sin(phi);
        final double ny = start ? -Math.cos(phi) : Math.cos(phi);
        final int base = mb.vertexCount();
        for (int i = 0; i < n; i++) {
            mb.vertex(outer[i * 2] * c, outer[i * 2] * s, outer[i * 2 + 1], nx, ny, 0);
            mb.vertex(inner[i * 2] * c, inner[i * 2] * s, inner[i * 2 + 1], nx, ny, 0);
        }
        for (int i = 0; i < n; i++) {
            final int o0 = base + i * 2, i0 = o0 + 1;
            final int o1 = base + ((i + 1) % n) * 2, i1 = o1 + 1;
            if (start) {
                mb.triangle(o0, o1, i1);
                mb.triangle(o0, i1, i0);
            } else {
                mb.triangle(o0, i1, o1);
                mb.triangle(o0, i0, i1);
            }
        }
    }

    /** Samples an arc of radius rho about (rc, zc) from angle a0 to a1 (radians, in the r-z plane). */
    static void arc(java.util.List<double[]> out, double rc, double zc, double rho, double a0, double a1,
                    int segments) {
        final int n = Math.max(1, segments);
        for (int i = 0; i <= n; i++) {
            final double a = a0 + (a1 - a0) * i / n;
            out.add(new double[] {Math.max(0, rc + rho * Math.cos(a)), zc + rho * Math.sin(a), i == 0 || i == n ? 0 : 1});
        }
    }
}
