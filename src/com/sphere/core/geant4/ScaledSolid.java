package com.sphere.core.geant4;

import java.util.List;

/**
 * G4ScaledSolid, GDML's scaledSolid and ROOT's TGeoScaledShape: another
 * solid stretched along x, y and z. ROOT builds its ellipsoids and
 * elliptical cones this way, so a ROOT file brings them in as such.
 */
public final class ScaledSolid extends Solid {

    private static final Kind N = Kind.NUMBER;

    private Solid base;
    private double sx = 1, sy = 1, sz = 1;

    public ScaledSolid(String name, Solid base) {
        super(name);
        this.base = base;
        param("sx", "Scale x", N, "1");
        param("sy", "Scale y", N, "1");
        param("sz", "Scale z", N, "1");
    }

    public Solid base() {
        return base;
    }

    public String tag() {
        return "scaledSolid";
    }

    public String g4Class() {
        return "G4ScaledSolid";
    }

    @Override
    public String description() {
        return "G4ScaledSolid of " + (base == null ? "nothing" : base.name());
    }

    @Override
    public List<Solid> operands() {
        return List.of(base);
    }

    @Override
    public void replaceOperand(Solid oldOne, Solid newOne) {
        if (base == oldOne) {
            base = newOne;
        }
    }

    protected void validate() throws GeometryException {
        require(base != null, "G4ScaledSolid " + name + ": needs a solid to scale");
        require(base != this, "G4ScaledSolid " + name + ": cannot scale itself");
        sx = v("sx");
        sy = v("sy");
        sz = v("sz");
        require(sx != 0 && sy != 0 && sz != 0, "G4ScaledSolid " + name + ": a scale factor is zero");
    }

    /** The base's distance in the scaled frame, shrunk by the smallest factor: a lower bound with the exact sign. */
    public double sdf(double x, double y, double z) {
        final double m = Math.min(Math.abs(sx), Math.min(Math.abs(sy), Math.abs(sz)));
        return base.sdf(x / sx, y / sy, z / sz) * m;
    }

    public double safety() {
        return base.safety();
    }

    public BBox extent() {
        final BBox b = base.box();
        if (b.isEmpty()) {
            return b;
        }
        final double x0 = b.minX() * sx, x1 = b.maxX() * sx;
        final double y0 = b.minY() * sy, y1 = b.maxY() * sy;
        final double z0 = b.minZ() * sz, z1 = b.maxZ() * sz;
        return new BBox(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1), Math.max(y0, y1),
            Math.max(z0, z1));
    }

    protected Mesh buildMesh(int q) {
        final Mesh m = base.mesh(q);
        final float[] p = m.pos.clone();
        final float[] n = m.nrm.clone();
        for (int i = 0; i < p.length; i += 3) {
            p[i] *= (float) sx;
            p[i + 1] *= (float) sy;
            p[i + 2] *= (float) sz;
            // normals transform by the inverse transpose
            final double nx = n[i] / sx, ny = n[i + 1] / sy, nz = n[i + 2] / sz;
            final double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0) {
                n[i] = (float) (nx / l);
                n[i + 1] = (float) (ny / l);
                n[i + 2] = (float) (nz / l);
            }
        }
        final int[] t = m.tri.clone();
        if (sx * sy * sz < 0) {
            // a reflection turns the triangles inside out
            for (int i = 0; i < t.length; i += 3) {
                final int s = t[i + 1];
                t[i + 1] = t[i + 2];
                t[i + 2] = s;
            }
        }
        return new Mesh(p, n, t);
    }

    protected double computeVolume() {
        return base.cubicVolume() * Math.abs(sx * sy * sz);
    }

    public String cpp(CppNames n) {
        return "new G4ScaledSolid(" + n.string(name) + ", " + n.solid(base) + ", G4Scale3D(" + c("sx") + ", " + c("sy")
            + ", " + c("sz") + "))";
    }

    @Override
    public Solid copy(String newName) {
        final ScaledSolid s = new ScaledSolid(newName, base);
        copyParamsTo(s);
        return s;
    }
}
