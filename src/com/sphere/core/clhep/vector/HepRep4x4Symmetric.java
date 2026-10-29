package com.sphere.core.clhep.vector;

/** CLHEP's HepRep4x4Symmetric (Vector/RotationInterfaces.h): the ten elements of a symmetric 4x4 matrix. */
public final class HepRep4x4Symmetric {

    public double xx_, xy_, xz_, xt_, yy_, yz_, yt_, zz_, zt_, tt_;

    /** The identity. */
    public HepRep4x4Symmetric() {
        setToIdentity();
    }

    public HepRep4x4Symmetric(double xx, double xy, double xz, double xt, double yy, double yz, double yt, double zz,
                              double zt, double tt) {
        xx_ = xx;
        xy_ = xy;
        xz_ = xz;
        xt_ = xt;
        yy_ = yy;
        yz_ = yz;
        yt_ = yt;
        zz_ = zz;
        zt_ = zt;
        tt_ = tt;
    }

    /** From ten values: the upper triangle, row by row. */
    public HepRep4x4Symmetric(double[] a) {
        this(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9]);
    }

    public void setToIdentity() {
        xx_ = 1.0;
        xy_ = 0.0;
        xz_ = 0.0;
        xt_ = 0.0;
        yy_ = 1.0;
        yz_ = 0.0;
        yt_ = 0.0;
        zz_ = 1.0;
        zt_ = 0.0;
        tt_ = 1.0;
    }

    public void getArray(double[] array) {
        final double[] r = {xx_, xy_, xz_, xt_, yy_, yz_, yt_, zz_, zt_, tt_};
        System.arraycopy(r, 0, array, 0, 10);
    }
}
