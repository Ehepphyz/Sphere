package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewritings of a solid into another class that describes the same volume,
 * for the readers that know fewer solids than Geant4: ROOT's TGeo, and ROOT's
 * GDML reader (TGDMLParse), which has no G4GenericPolycone nor G4Tet.
 */
final class SolidConversions {

    private SolidConversions() {
    }

    /**
     * A G4GenericPolycone's (r, z) profile as polycone planes {z, rmin, rmax}
     * (internal units), or null when no polycone follows it. It does when
     * every horizontal line crosses the profile in one interval: cut at each
     * corner's z, each slab is bounded by one inner and one outer edge, which
     * a polycone interpolates linearly between its planes as the profile does.
     * A horizontal edge gives two planes at the same z.
     */
    static List<double[]> polyconePlanes(Solid genericPolycone) {
        final Solid.Table t = genericPolycone.table("rzpoint");
        final int n = t.size();
        final double[] r = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = t.value(i, 0);
            z[i] = t.value(i, 1);
        }
        final double[] zs = Arrays.stream(z).distinct().sorted().toArray();
        final List<double[]> planes = new ArrayList<>();
        for (int k = 0; k + 1 < zs.length; k++) {
            final double z0 = zs[k], z1 = zs[k + 1], zm = 0.5 * (z0 + z1);
            final List<double[]> edges = new ArrayList<>();   // {r at z0, r at z1, r at zm}
            for (int i = 0; i < n; i++) {
                final int j = (i + 1) % n;
                if ((z[i] - zm) * (z[j] - zm) >= 0) {
                    continue;
                }
                final double slope = (r[j] - r[i]) / (z[j] - z[i]);
                edges.add(new double[] {r[i] + slope * (z0 - z[i]), r[i] + slope * (z1 - z[i]), r[i] + slope * (zm - z[i])});
            }
            if (edges.size() != 2) {
                return null;
            }
            edges.sort((p, q) -> Double.compare(p[2], q[2]));
            final double[] lo = {z0, Math.max(0, edges.get(0)[0]), edges.get(1)[0]};
            final double[] hi = {z1, Math.max(0, edges.get(0)[1]), edges.get(1)[1]};
            if (planes.isEmpty() || !Arrays.equals(planes.get(planes.size() - 1), lo)) {
                planes.add(lo);
            }
            planes.add(hi);
        }
        return planes.size() < 2 ? null : planes;
    }

    /** A solid's surface as vertices (internal units) and triangles, shared corners merged. */
    record Surface(List<double[]> vertices, List<int[]> facets) {
    }

    static Surface surface(Solid s, int quality) {
        final Mesh m = s.mesh(quality);
        final List<double[]> vertices = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        final int[] map = new int[m.vertexCount()];
        for (int i = 0; i < m.vertexCount(); i++) {
            final String key = m.pos[i * 3] + "," + m.pos[i * 3 + 1] + "," + m.pos[i * 3 + 2];
            Integer k = index.get(key);
            if (k == null) {
                k = vertices.size();
                index.put(key, k);
                vertices.add(new double[] {m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2]});
            }
            map[i] = k;
        }
        final List<int[]> facets = new ArrayList<>();
        for (int t = 0; t < m.tri.length; t += 3) {
            final int a = map[m.tri[t]], b = map[m.tri[t + 1]], c = map[m.tri[t + 2]];
            if (a != b && b != c && a != c) {
                facets.add(new int[] {a, b, c});
            }
        }
        return new Surface(vertices, facets);
    }

    /** A G4Tet's four faces, counter-clockwise seen from outside whatever the order of its corners. */
    static Surface tet(Solid tet) {
        final List<double[]> v = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            v.add(new double[] {tet.v("v" + i + "x"), tet.v("v" + i + "y"), tet.v("v" + i + "z")});
        }
        final double[] a = v.get(0), b = v.get(1), c = v.get(2), d = v.get(3);
        final double det = (b[0] - a[0]) * ((c[1] - a[1]) * (d[2] - a[2]) - (c[2] - a[2]) * (d[1] - a[1]))
            - (b[1] - a[1]) * ((c[0] - a[0]) * (d[2] - a[2]) - (c[2] - a[2]) * (d[0] - a[0]))
            + (b[2] - a[2]) * ((c[0] - a[0]) * (d[1] - a[1]) - (c[1] - a[1]) * (d[0] - a[0]));
        // det > 0: (a, b, c, d) is right-handed, and the faces below point outward
        final List<int[]> f = det > 0
            ? List.of(new int[] {0, 2, 1}, new int[] {0, 1, 3}, new int[] {0, 3, 2}, new int[] {1, 2, 3})
            : List.of(new int[] {0, 1, 2}, new int[] {0, 3, 1}, new int[] {0, 2, 3}, new int[] {1, 3, 2});
        return new Surface(v, f);
    }
}
