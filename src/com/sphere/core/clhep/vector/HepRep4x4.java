package com.sphere.core.clhep.vector;

/** CLHEP's HepRep4x4 (Vector/RotationInterfaces.h): the sixteen elements of a 4x4 matrix, row by row. */
public final class HepRep4x4 {

    public double xx_, xy_, xz_, xt_, yx_, yy_, yz_, yt_, zx_, zy_, zz_, zt_, tx_, ty_, tz_, tt_;

    /** The identity. */
    public HepRep4x4() {
        setToIdentity();
    }

    public HepRep4x4(double xx, double xy, double xz, double xt, double yx, double yy, double yz, double yt,
                     double zx, double zy, double zz, double zt, double tx, double ty, double tz, double tt) {
        xx_ = xx;
        xy_ = xy;
        xz_ = xz;
        xt_ = xt;
        yx_ = yx;
        yy_ = yy;
        yz_ = yz;
        yt_ = yt;
        zx_ = zx;
        zy_ = zy;
        zz_ = zz;
        zt_ = zt;
        tx_ = tx;
        ty_ = ty;
        tz_ = tz;
        tt_ = tt;
    }

    /** The full matrix of a symmetric one. */
    public HepRep4x4(HepRep4x4Symmetric rep) {
        this(rep.xx_, rep.xy_, rep.xz_, rep.xt_,
            rep.xy_, rep.yy_, rep.yz_, rep.yt_,
            rep.xz_, rep.yz_, rep.zz_, rep.zt_,
            rep.xt_, rep.yt_, rep.zt_, rep.tt_);
    }

    /** From sixteen values, row by row. */
    public HepRep4x4(double[] a) {
        this(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15]);
    }

    public void setToIdentity() {
        xx_ = 1.0;
        xy_ = 0.0;
        xz_ = 0.0;
        xt_ = 0.0;
        yx_ = 0.0;
        yy_ = 1.0;
        yz_ = 0.0;
        yt_ = 0.0;
        zx_ = 0.0;
        zy_ = 0.0;
        zz_ = 1.0;
        zt_ = 0.0;
        tx_ = 0.0;
        ty_ = 0.0;
        tz_ = 0.0;
        tt_ = 1.0;
    }

    public void getArray(double[] array) {
        final double[] r = {xx_, xy_, xz_, xt_, yx_, yy_, yz_, yt_, zx_, zy_, zz_, zt_, tx_, ty_, tz_, tt_};
        System.arraycopy(r, 0, array, 0, 16);
    }

    /** operator==: all sixteen elements equal. */
    public boolean isEqual(HepRep4x4 r) {
        return xx_ == r.xx_ && xy_ == r.xy_ && xz_ == r.xz_ && xt_ == r.xt_
            && yx_ == r.yx_ && yy_ == r.yy_ && yz_ == r.yz_ && yt_ == r.yt_
            && zx_ == r.zx_ && zy_ == r.zy_ && zz_ == r.zz_ && zt_ == r.zt_
            && tx_ == r.tx_ && ty_ == r.ty_ && tz_ == r.tz_ && tt_ == r.tt_;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepRep4x4 r && isEqual(r);
    }

    @Override
    public int hashCode() {
        final double[] a = new double[16];
        getArray(a);
        return java.util.Arrays.hashCode(a);
    }
}
