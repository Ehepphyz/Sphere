package com.sphere.components.spherebrowser;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * What the 3D space shows: meshes in data coordinates, the box of data they
 * fill, the axes' titles and the palette the colours were taken from.
 *
 * The box is mapped onto a cube, each axis on its own as ROOT does for a
 * LEGO or a SURF, or all together when the shape itself matters (a detector
 * geometry, a cloud of hits): keepAspect.
 */
public final class Scene3D {

    public final List<Mesh3D> meshes = new ArrayList<>();
    public String title = "";
    public final String[] axisTitle = {"x", "y", "z"};
    /** The axes shown in logarithm: the coordinates are already log10, the labels are not. */
    public final boolean[] log = new boolean[3];
    /** x0 x1 y0 y1 z0 z1 in data coordinates. */
    public double[] box;
    public boolean keepAspect;
    /** The palette the vertex colours were taken from, and the values at its ends; null when none. */
    public Color[] palette;
    public double valueLo;
    public double valueHi;
    public String valueTitle = "";
    /** Lines the head-up display adds: what was built, how many cells, what was cut. */
    public final List<String> notes = new ArrayList<>();

    public Mesh3D mesh(String name) {
        final Mesh3D m = new Mesh3D(name);
        meshes.add(m);
        return m;
    }

    /** The box, from the meshes when no builder set it; never empty along an axis. */
    public double[] box() {
        double[] b = box;
        if (b == null) {
            b = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE};
            for (Mesh3D m : meshes) {
                if (m.normalized || m.vertexCount() == 0) continue;
                final double[] mb = m.bounds();
                for (int a = 0; a < 3; a++) {
                    b[2 * a] = Math.min(b[2 * a], mb[2 * a]);
                    b[2 * a + 1] = Math.max(b[2 * a + 1], mb[2 * a + 1]);
                }
            }
            box = b;
        }
        for (int a = 0; a < 3; a++) {
            if (!(b[2 * a] <= b[2 * a + 1])) {
                b[2 * a] = -1;
                b[2 * a + 1] = 1;
            }
            if (b[2 * a] == b[2 * a + 1]) {
                final double d = Math.max(1e-9, Math.abs(b[2 * a]) * 0.05);
                b[2 * a] -= d;
                b[2 * a + 1] += d;
            }
        }
        return b;
    }

    public int triangles() {
        int n = 0;
        for (Mesh3D m : meshes) n += m.triangleCount();
        return n;
    }

    public int vertices() {
        int n = 0;
        for (Mesh3D m : meshes) n += m.vertexCount();
        return n;
    }
}
