package com.sphere.core.clhep.geometry;

import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's HepGeom::Transform3D (Geometry/Transform3D.h, .icc, Transform3D.cc): a transformation of
 * space as a 3x3 matrix and a translation (the top three rows of a 4x4 matrix), which moves points, turns
 * vectors and transforms normals; Rotate3D, Translate3D, Reflect3D, Scale3D and their X, Y, Z forms make
 * the usual ones.
 */
public class Transform3D {

    protected double xx_, xy_, xz_, dx_,
        yx_, yy_, yz_, dy_,
        zx_, zy_, zz_, dz_;

    public static final Transform3D Identity = new Transform3D();

    /** The identity. */
    public Transform3D() {
        xx_ = 1;
        xy_ = 0;
        xz_ = 0;
        dx_ = 0;
        yx_ = 0;
        yy_ = 1;
        yz_ = 0;
        dy_ = 0;
        zx_ = 0;
        zy_ = 0;
        zz_ = 1;
        dz_ = 0;
    }

    /** The twelve elements (protected in C++). */
    protected Transform3D(double XX, double XY, double XZ, double DX,
                          double YX, double YY, double YZ, double DY,
                          double ZX, double ZY, double ZZ, double DZ) {
        xx_ = XX;
        xy_ = XY;
        xz_ = XZ;
        dx_ = DX;
        yx_ = YX;
        yy_ = YY;
        yz_ = YZ;
        dy_ = DY;
        zx_ = ZX;
        zy_ = ZY;
        zz_ = ZZ;
        dz_ = DZ;
    }

    /** A rotation, then a translation. */
    public Transform3D(HepRotation mt, Hep3Vector v) {
        xx_ = mt.xx();
        xy_ = mt.xy();
        xz_ = mt.xz();
        yx_ = mt.yx();
        yy_ = mt.yy();
        yz_ = mt.yz();
        zx_ = mt.zx();
        zy_ = mt.zy();
        zz_ = mt.zz();
        dx_ = v.x();
        dy_ = v.y();
        dz_ = v.z();
    }

    public Transform3D(Transform3D mt) {
        assign(mt);
    }

    /**
     * The transformation that takes three points (fr0, fr1, fr2) to three others (to0, to1, to2): the
     * axes fr1-fr0, fr2-fr0 onto to1-to0, to2-to0, fr0 onto to0. The identity, said on cerr, when the
     * axes are parallel.
     */
    public Transform3D(Point3D fr0, Point3D fr1, Point3D fr2, Point3D to0, Point3D to1, Point3D to2) {
        final Vector3D x1 = new Vector3D(fr1.minus(fr0).unit());
        Vector3D y1 = new Vector3D(fr2.minus(fr0).unit());
        final Vector3D x2 = new Vector3D(to1.minus(to0).unit());
        Vector3D y2 = new Vector3D(to2.minus(to0).unit());

        // check the angles between the axes
        final double cos1 = x1.dot(y1);
        final double cos2 = x2.dot(y2);

        if (Math.abs(1.0 - cos1) <= 0.000001 || Math.abs(1.0 - cos2) <= 0.000001) {
            StdStreams.cerr().print("Transform3D: zero angle between axes\n");
            setIdentity();
        } else {
            if (Math.abs(cos1 - cos2) > 0.000001) {
                StdStreams.cerr().print("Transform3D: angles between axes are not equal\n");
            }

            // the orthogonal bases
            final Vector3D z1 = new Vector3D(x1.cross(y1).unit());
            y1 = new Vector3D(z1.cross(x1));

            final Vector3D z2 = new Vector3D(x2.cross(y2).unit());
            y2 = new Vector3D(z2.cross(x2));

            final double detxx = (y1.y() * z1.z() - z1.y() * y1.z());
            final double detxy = -(y1.x() * z1.z() - z1.x() * y1.z());
            final double detxz = (y1.x() * z1.y() - z1.x() * y1.y());
            final double detyx = -(x1.y() * z1.z() - z1.y() * x1.z());
            final double detyy = (x1.x() * z1.z() - z1.x() * x1.z());
            final double detyz = -(x1.x() * z1.y() - z1.x() * x1.y());
            final double detzx = (x1.y() * y1.z() - y1.y() * x1.z());
            final double detzy = -(x1.x() * y1.z() - y1.x() * x1.z());
            final double detzz = (x1.x() * y1.y() - y1.x() * x1.y());

            final double txx = x2.x() * detxx + y2.x() * detyx + z2.x() * detzx;
            final double txy = x2.x() * detxy + y2.x() * detyy + z2.x() * detzy;
            final double txz = x2.x() * detxz + y2.x() * detyz + z2.x() * detzz;
            final double tyx = x2.y() * detxx + y2.y() * detyx + z2.y() * detzx;
            final double tyy = x2.y() * detxy + y2.y() * detyy + z2.y() * detzy;
            final double tyz = x2.y() * detxz + y2.y() * detyz + z2.y() * detzz;
            final double tzx = x2.z() * detxx + y2.z() * detyx + z2.z() * detzx;
            final double tzy = x2.z() * detxy + y2.z() * detyy + z2.z() * detzy;
            final double tzz = x2.z() * detxz + y2.z() * detyz + z2.z() * detzz;

            final double dx1 = fr0.x(), dy1 = fr0.y(), dz1 = fr0.z();
            final double dx2 = to0.x(), dy2 = to0.y(), dz2 = to0.z();

            setTransform(txx, txy, txz, dx2 - txx * dx1 - txy * dy1 - txz * dz1,
                tyx, tyy, tyz, dy2 - tyx * dx1 - tyy * dy1 - tyz * dz1,
                tzx, tzy, tzz, dz2 - tzx * dx1 - tzy * dy1 - tzz * dz1);
        }
    }

    protected void setTransform(double XX, double XY, double XZ, double DX,
                                double YX, double YY, double YZ, double DY,
                                double ZX, double ZY, double ZZ, double DZ) {
        xx_ = XX;
        xy_ = XY;
        xz_ = XZ;
        dx_ = DX;
        yx_ = YX;
        yy_ = YY;
        yz_ = YZ;
        dy_ = DY;
        zx_ = ZX;
        zy_ = ZY;
        zz_ = ZZ;
        dz_ = DZ;
    }

    /** operator=. */
    public Transform3D assign(Transform3D mt) {
        setTransform(mt.xx_, mt.xy_, mt.xz_, mt.dx_, mt.yx_, mt.yy_, mt.yz_, mt.dy_, mt.zx_, mt.zy_, mt.zz_, mt.dz_);
        return this;
    }

    /** A row of the 4x4 matrix: operator[](i), whose operator[](j) is the element. */
    public static final class Transform3D_row {
        private final Transform3D rr;
        private final int ii;

        Transform3D_row(Transform3D r, int i) {
            rr = r;
            ii = i;
        }

        public double get(int jj) {
            return rr.get(ii, jj);
        }
    }

    /** operator[](i). */
    public Transform3D_row row(int i) {
        return new Transform3D_row(this, i);
    }

    /** operator()(i, j): the element of the 4x4 matrix (its last row 0 0 0 1); 0, said on cerr, out of it. */
    public double get(int i, int j) {
        if (i == 0) {
            if (j == 0) return xx_;
            if (j == 1) return xy_;
            if (j == 2) return xz_;
            if (j == 3) return dx_;
        } else if (i == 1) {
            if (j == 0) return yx_;
            if (j == 1) return yy_;
            if (j == 2) return yz_;
            if (j == 3) return dy_;
        } else if (i == 2) {
            if (j == 0) return zx_;
            if (j == 1) return zy_;
            if (j == 2) return zz_;
            if (j == 3) return dz_;
        } else if (i == 3) {
            if (j == 0) return 0.0;
            if (j == 1) return 0.0;
            if (j == 2) return 0.0;
            if (j == 3) return 1.0;
        }
        StdStreams.cerr().print("Transform3D subscripting: bad indices (" + i + "," + j + ")\n");
        return 0.0;
    }

    public double xx() {
        return xx_;
    }

    public double xy() {
        return xy_;
    }

    public double xz() {
        return xz_;
    }

    public double yx() {
        return yx_;
    }

    public double yy() {
        return yy_;
    }

    public double yz() {
        return yz_;
    }

    public double zx() {
        return zx_;
    }

    public double zy() {
        return zy_;
    }

    public double zz() {
        return zz_;
    }

    public double dx() {
        return dx_;
    }

    public double dy() {
        return dy_;
    }

    public double dz() {
        return dz_;
    }

    public void setIdentity() {
        xy_ = xz_ = dx_ = yx_ = yz_ = dy_ = zx_ = zy_ = dz_ = 0;
        xx_ = yy_ = zz_ = 1;
    }

    /** The inverse; the identity, said on cerr, when the determinant is zero. */
    public Transform3D inverse() {
        double detxx = yy_ * zz_ - yz_ * zy_;
        double detxy = yx_ * zz_ - yz_ * zx_;
        double detxz = yx_ * zy_ - yy_ * zx_;
        double det = xx_ * detxx - xy_ * detxy + xz_ * detxz;
        if (det == 0) {
            StdStreams.cerr().print("Transform3D::inverse error: zero determinant\n");
            return new Transform3D();
        }
        det = 1. / det;
        detxx *= det;
        detxy *= det;
        detxz *= det;
        final double detyx = (xy_ * zz_ - xz_ * zy_) * det;
        final double detyy = (xx_ * zz_ - xz_ * zx_) * det;
        final double detyz = (xx_ * zy_ - xy_ * zx_) * det;
        final double detzx = (xy_ * yz_ - xz_ * yy_) * det;
        final double detzy = (xx_ * yz_ - xz_ * yx_) * det;
        final double detzz = (xx_ * yy_ - xy_ * yx_) * det;
        return new Transform3D(
            detxx, -detyx, detzx, -detxx * dx_ + detyx * dy_ - detzx * dz_,
            -detxy, detyy, -detzy, detxy * dx_ - detyy * dy_ + detzy * dz_,
            detxz, -detyz, detzz, -detxz * dx_ + detyz * dy_ - detzz * dz_);
    }

    /** operator*: this after b. */
    public Transform3D times(Transform3D b) {
        return new Transform3D(
            xx_ * b.xx_ + xy_ * b.yx_ + xz_ * b.zx_, xx_ * b.xy_ + xy_ * b.yy_ + xz_ * b.zy_,
            xx_ * b.xz_ + xy_ * b.yz_ + xz_ * b.zz_, xx_ * b.dx_ + xy_ * b.dy_ + xz_ * b.dz_ + dx_,
            yx_ * b.xx_ + yy_ * b.yx_ + yz_ * b.zx_, yx_ * b.xy_ + yy_ * b.yy_ + yz_ * b.zy_,
            yx_ * b.xz_ + yy_ * b.yz_ + yz_ * b.zz_, yx_ * b.dx_ + yy_ * b.dy_ + yz_ * b.dz_ + dy_,
            zx_ * b.xx_ + zy_ * b.yx_ + zz_ * b.zx_, zx_ * b.xy_ + zy_ * b.yy_ + zz_ * b.zy_,
            zx_ * b.xz_ + zy_ * b.yz_ + zz_ * b.zz_, zx_ * b.dx_ + zy_ * b.dy_ + zz_ * b.dz_ + dz_);
    }

    /** operator*(Transform3D, Point3D&lt;double&gt;): the point moved. */
    public Point3D times(Point3D v) {
        final double vx = v.x(), vy = v.y(), vz = v.z();
        return new Point3D(
            xx() * vx + xy() * vy + xz() * vz + dx(),
            yx() * vx + yy() * vy + yz() * vz + dy(),
            zx() * vx + zy() * vy + zz() * vz + dz());
    }

    /** operator*(Transform3D, Point3D&lt;float&gt;): computed in double, rounded to float. */
    public Point3DF times(Point3DF v) {
        final double vx = v.x(), vy = v.y(), vz = v.z();
        return new Point3DF(
            (float) (xx() * vx + xy() * vy + xz() * vz + dx()),
            (float) (yx() * vx + yy() * vy + yz() * vz + dy()),
            (float) (zx() * vx + zy() * vy + zz() * vz + dz()));
    }

    /** operator*(Transform3D, Vector3D&lt;double&gt;): the vector turned. */
    public Vector3D times(Vector3D v) {
        final double vx = v.x(), vy = v.y(), vz = v.z();
        return new Vector3D(
            xx() * vx + xy() * vy + xz() * vz,
            yx() * vx + yy() * vy + yz() * vz,
            zx() * vx + zy() * vy + zz() * vz);
    }

    public Vector3DF times(Vector3DF v) {
        final double vx = v.x(), vy = v.y(), vz = v.z();
        return new Vector3DF(
            (float) (xx() * vx + xy() * vy + xz() * vz),
            (float) (yx() * vx + yy() * vy + yz() * vz),
            (float) (zx() * vx + zy() * vy + zz() * vz));
    }

    /** operator*(Transform3D, Normal3D&lt;double&gt;): by the cofactor matrix. */
    public Normal3D times(Normal3D v) {
        final double vx = v.x(), vy = v.y(), vz = v.z();
        final double xx = xx(), xy = xy(), xz = xz();
        final double yx = yx(), yy = yy(), yz = yz();
        final double zx = zx(), zy = zy(), zz = zz();
        return new Normal3D(
            (yy * zz - yz * zy) * vx + (yz * zx - yx * zz) * vy + (yx * zy - yy * zx) * vz,
            (zy * xz - zz * xy) * vx + (zz * xx - zx * xz) * vy + (zx * xy - zy * xx) * vz,
            (xy * yz - xz * yy) * vx + (xz * yx - xx * yz) * vy + (xx * yy - xy * yx) * vz);
    }

    public Normal3DF times(Normal3DF v) {
        final double vx = v.x(), vy = v.y(), vz = v.z();
        final double xx = xx(), xy = xy(), xz = xz();
        final double yx = yx(), yy = yy(), yz = yz();
        final double zx = zx(), zy = zy(), zz = zz();
        return new Normal3DF(
            (float) ((yy * zz - yz * zy) * vx + (yz * zx - yx * zz) * vy + (yx * zy - yy * zx) * vz),
            (float) ((zy * xz - zz * xy) * vx + (zz * xx - zx * xz) * vy + (zx * xy - zy * xx) * vz),
            (float) ((xy * yz - xz * yy) * vx + (xz * yx - xx * yz) * vy + (xx * yy - xy * yx) * vz));
    }

    /**
     * This transformation as scale, then rotation, then translation: the scales the lengths of the
     * columns (the z one negative for a reflection).
     */
    public void getDecomposition(Scale3D scale, Rotate3D rotation, Translate3D translation) {
        final double sx = Math.sqrt(xx_ * xx_ + yx_ * yx_ + zx_ * zx_);
        final double sy = Math.sqrt(xy_ * xy_ + yy_ * yy_ + zy_ * zy_);
        double sz = Math.sqrt(xz_ * xz_ + yz_ * yz_ + zz_ * zz_);

        if (xx_ * (yy_ * zz_ - yz_ * zy_)
            - xy_ * (yx_ * zz_ - yz_ * zx_)
            + xz_ * (yx_ * zy_ - yy_ * zx_) < 0) sz = -sz;
        scale.setTransform(sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, sz, 0);
        rotation.setTransform(xx_ / sx, xy_ / sy, xz_ / sz, 0,
            yx_ / sx, yy_ / sy, yz_ / sz, 0,
            zx_ / sx, zy_ / sy, zz_ / sz, 0);
        translation.setTransform(1, 0, 0, dx_, 0, 1, 0, dy_, 0, 0, 1, dz_);
    }

    public boolean isNear(Transform3D t) {
        return isNear(t, 2.2E-14);
    }

    /** Every element within the tolerance of t's. */
    public boolean isNear(Transform3D t, double tolerance) {
        return ((Math.abs(xx_ - t.xx_) <= tolerance)
            && (Math.abs(xy_ - t.xy_) <= tolerance)
            && (Math.abs(xz_ - t.xz_) <= tolerance)
            && (Math.abs(dx_ - t.dx_) <= tolerance)
            && (Math.abs(yx_ - t.yx_) <= tolerance)
            && (Math.abs(yy_ - t.yy_) <= tolerance)
            && (Math.abs(yz_ - t.yz_) <= tolerance)
            && (Math.abs(dy_ - t.dy_) <= tolerance)
            && (Math.abs(zx_ - t.zx_) <= tolerance)
            && (Math.abs(zy_ - t.zy_) <= tolerance)
            && (Math.abs(zz_ - t.zz_) <= tolerance)
            && (Math.abs(dz_ - t.dz_) <= tolerance));
    }

    /** The rotation, as a HepRotation of the matrix's columns. */
    public HepRotation getRotation() {
        final HepRotation mt = new HepRotation();
        return mt.rotateAxes(new Hep3Vector(xx_, yx_, zx_),
            new Hep3Vector(xy_, yy_, zy_),
            new Hep3Vector(xz_, yz_, zz_));
    }

    public Hep3Vector getTranslation() {
        return new Hep3Vector(dx_, dy_, dz_);
    }

    /** operator==: the same object, or the same twelve elements. */
    public boolean isEqual(Transform3D t) {
        return (this == t) || (xx_ == t.xx_ && xy_ == t.xy_ && xz_ == t.xz_ && dx_ == t.dx_
            && yx_ == t.yx_ && yy_ == t.yy_ && yz_ == t.yz_ && dy_ == t.dy_
            && zx_ == t.zx_ && zy_ == t.zy_ && zz_ == t.zz_ && dz_ == t.dz_);
    }

    public boolean notEqual(Transform3D t) {
        return !isEqual(t);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Transform3D t && isEqual(t);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(xx_, xy_, xz_, dx_, yx_, yy_, yz_, dy_, zx_, zy_, zz_, dz_);
    }

    @Override
    public String toString() {
        return "Transform3D[" + xx_ + " " + xy_ + " " + xz_ + " " + dx_ + " | " + yx_ + " " + yy_ + " " + yz_ + " "
            + dy_ + " | " + zx_ + " " + zy_ + " " + zz_ + " " + dz_ + "]";
    }
}
