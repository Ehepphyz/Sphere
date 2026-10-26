package com.sphere.core.geant4;

/**
 * A rigid transformation p' = R p + t, with R a proper rotation -- or, for
 * a mirrored placement, a rotation times the mirror through z = 0.
 *
 * Two Geant4 conventions meet here and both are spelt out once, so that the
 * editor, the GDML file and the generated C++ agree on where a volume is:
 *
 *  - GDML angles (x, y, z) make R_gdml = Rz(z) Ry(y) Rx(x): CLHEP's
 *    rotateX(x).rotateY(y).rotateZ(z), as G4GDMLReadDefine::GetRotationMatrix.
 *  - A physvol is placed with G4Transform3D(R_gdml.inverse(), position)
 *    (G4GDMLReadStructure::PhysvolRead): the daughter-to-mother map is
 *    p_mother = R_gdml^-1 p_daughter + t. This is G4PVPlacement(pRot=R_gdml, t).
 *  - The second solid of a boolean is placed with G4Transform3D(R_gdml, position)
 *    (G4GDMLReadSolids::BooleanRead): p_first = R_gdml p_second + t.
 */
public final class Xform {

    public static final Xform IDENTITY = new Xform(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, 0, 0, 0);

    /** The mirror through z = 0: GDML's physvol scale (1, 1, -1), CLHEP's G4ReflectZ3D. */
    public static final Xform REFLECT_Z = new Xform(new double[] {1, 0, 0, 0, 1, 0, 0, 0, -1}, 0, 0, 0);

    /** True when the transform mirrors (its matrix has determinant -1): a reflected placement. */
    public boolean isReflection() {
        return r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6])
            + r[2] * (r[3] * r[7] - r[4] * r[6]) < 0;
    }

    // Row-major rotation
    final double[] r;
    final double tx, ty, tz;

    public Xform(double[] r, double tx, double ty, double tz) {
        this.r = r;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
    }

    public static Xform translation(double x, double y, double z) {
        return new Xform(IDENTITY.r.clone(), x, y, z);
    }

    /** R_gdml = Rz(az) Ry(ay) Rx(ax), the matrix of a GDML rotation element. */
    public static double[] gdmlRotation(double ax, double ay, double az) {
        final double cx = Math.cos(ax), sx = Math.sin(ax);
        final double cy = Math.cos(ay), sy = Math.sin(ay);
        final double cz = Math.cos(az), sz = Math.sin(az);
        // Rx
        final double[] rx = {1, 0, 0, 0, cx, -sx, 0, sx, cx};
        final double[] ry = {cy, 0, sy, 0, 1, 0, -sy, 0, cy};
        final double[] rz = {cz, -sz, 0, sz, cz, 0, 0, 0, 1};
        return mul(rz, mul(ry, rx));
    }

    /** The daughter-to-mother transform of a GDML physvol. */
    public static Xform placement(double x, double y, double z, double ax, double ay, double az) {
        return new Xform(transpose(gdmlRotation(ax, ay, az)), x, y, z);
    }

    /** The second-to-first transform of a GDML boolean solid. */
    public static Xform booleanPlacement(double x, double y, double z, double ax, double ay, double az) {
        return new Xform(gdmlRotation(ax, ay, az), x, y, z);
    }

    public Vec3 apply(Vec3 p) {
        return new Vec3(r[0] * p.x() + r[1] * p.y() + r[2] * p.z() + tx,
                        r[3] * p.x() + r[4] * p.y() + r[5] * p.z() + ty,
                        r[6] * p.x() + r[7] * p.y() + r[8] * p.z() + tz);
    }

    public Vec3 applyDir(Vec3 d) {
        return new Vec3(r[0] * d.x() + r[1] * d.y() + r[2] * d.z(),
                        r[3] * d.x() + r[4] * d.y() + r[5] * d.z(),
                        r[6] * d.x() + r[7] * d.y() + r[8] * d.z());
    }

    /** R^T (p - t). */
    public Vec3 applyInverse(Vec3 p) {
        final double x = p.x() - tx, y = p.y() - ty, z = p.z() - tz;
        return new Vec3(r[0] * x + r[3] * y + r[6] * z,
                        r[1] * x + r[4] * y + r[7] * z,
                        r[2] * x + r[5] * y + r[8] * z);
    }

    public Vec3 applyInverseDir(Vec3 d) {
        return new Vec3(r[0] * d.x() + r[3] * d.y() + r[6] * d.z(),
                        r[1] * d.x() + r[4] * d.y() + r[7] * d.z(),
                        r[2] * d.x() + r[5] * d.y() + r[8] * d.z());
    }

    /** Inverse-applies into an output array, without allocating: the navigator's hot path. */
    public void applyInverse(double x, double y, double z, double[] out) {
        x -= tx;
        y -= ty;
        z -= tz;
        out[0] = r[0] * x + r[3] * y + r[6] * z;
        out[1] = r[1] * x + r[4] * y + r[7] * z;
        out[2] = r[2] * x + r[5] * y + r[8] * z;
    }

    /** this * other: first other, then this. */
    public Xform compose(Xform o) {
        // Called for every drawn copy on every rebuild: no temporaries, and a rotation that is exactly the
        // identity (most placements are pure translations) shares the other's matrix, never mutated
        final double[] a = r, b = o.r;
        final double[] m = isExactIdentity(b) ? a : isExactIdentity(a) ? b : mul(a, b);
        return new Xform(m,
            a[0] * o.tx + a[1] * o.ty + a[2] * o.tz + tx,
            a[3] * o.tx + a[4] * o.ty + a[5] * o.tz + ty,
            a[6] * o.tx + a[7] * o.ty + a[8] * o.tz + tz);
    }

    private static boolean isExactIdentity(double[] m) {
        return m == IDENTITY.r || (m[0] == 1 && m[4] == 1 && m[8] == 1 && m[1] == 0 && m[2] == 0 && m[3] == 0
            && m[5] == 0 && m[6] == 0 && m[7] == 0);
    }

    public Xform inverse() {
        final double[] rt = transpose(r);
        final double x = -(rt[0] * tx + rt[1] * ty + rt[2] * tz);
        final double y = -(rt[3] * tx + rt[4] * ty + rt[5] * tz);
        final double z = -(rt[6] * tx + rt[7] * ty + rt[8] * tz);
        return new Xform(rt, x, y, z);
    }

    public Vec3 translation() {
        return new Vec3(tx, ty, tz);
    }

    public double[] rotation() {
        return r.clone();
    }

    public boolean isIdentityRotation() {
        return Math.abs(r[0] - 1) < 1e-15 && Math.abs(r[4] - 1) < 1e-15 && Math.abs(r[8] - 1) < 1e-15;
    }

    /** Transforms an axis-aligned box and returns the axis-aligned box around the result. */
    public BBox apply(BBox b) {
        if (b.isEmpty()) {
            return b;
        }
        final Vec3 c = apply(b.center());
        final Vec3 h = b.halfSize();
        final double ex = Math.abs(r[0]) * h.x() + Math.abs(r[1]) * h.y() + Math.abs(r[2]) * h.z();
        final double ey = Math.abs(r[3]) * h.x() + Math.abs(r[4]) * h.y() + Math.abs(r[5]) * h.z();
        final double ez = Math.abs(r[6]) * h.x() + Math.abs(r[7]) * h.y() + Math.abs(r[8]) * h.z();
        return new BBox(c.x() - ex, c.y() - ey, c.z() - ez, c.x() + ex, c.y() + ey, c.z() + ez);
    }

    /**
     * The GDML angles (x, y, z) of a rotation matrix: the inverse of
     * gdmlRotation, as G4GDMLWriteDefine::GetAngles computes them.
     */
    public static double[] gdmlAngles(double[] m) {
        // m = Rz Ry Rx : m[6] = -sin(y)
        double x, y, z;
        final double sy = -m[6];
        if (Math.abs(sy) < 1 - 1e-12) {
            y = Math.asin(Math.max(-1, Math.min(1, sy)));
            x = Math.atan2(m[7], m[8]);
            z = Math.atan2(m[3], m[0]);
        } else {
            y = sy > 0 ? Math.PI / 2 : -Math.PI / 2;
            z = 0;
            x = Math.atan2(sy > 0 ? m[1] : -m[1], m[4]);
        }
        return new double[] {x, y, z};
    }

    static double[] mul(double[] a, double[] b) {
        final double[] m = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return m;
    }

    static double[] transpose(double[] a) {
        return new double[] {a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    /** Rotation of angle a (radians) about a unit axis. */
    public static double[] axisAngle(Vec3 axis, double a) {
        final Vec3 u = axis.unit();
        final double c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        final double x = u.x(), y = u.y(), z = u.z();
        return new double[] {
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }
}
