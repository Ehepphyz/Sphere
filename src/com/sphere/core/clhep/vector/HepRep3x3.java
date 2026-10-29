package com.sphere.core.clhep.vector;

/** CLHEP's HepRep3x3 (Vector/RotationInterfaces.h): the nine elements of a 3x3 matrix, row by row. */
public final class HepRep3x3 {

    public double xx_, xy_, xz_, yx_, yy_, yz_, zx_, zy_, zz_;

    /** The identity. */
    public HepRep3x3() {
        setToIdentity();
    }

    public HepRep3x3(double xx, double xy, double xz, double yx, double yy, double yz, double zx, double zy, double zz) {
        xx_ = xx;
        xy_ = xy;
        xz_ = xz;
        yx_ = yx;
        yy_ = yy;
        yz_ = yz;
        zx_ = zx;
        zy_ = zy;
        zz_ = zz;
    }

    /** From nine values, row by row. */
    public HepRep3x3(double[] array) {
        this(array[0], array[1], array[2], array[3], array[4], array[5], array[6], array[7], array[8]);
    }

    public void setToIdentity() {
        xx_ = 1.0;
        xy_ = 0.0;
        xz_ = 0.0;
        yx_ = 0.0;
        yy_ = 1.0;
        yz_ = 0.0;
        zx_ = 0.0;
        zy_ = 0.0;
        zz_ = 1.0;
    }

    public void getArray(double[] array) {
        final double[] r = {xx_, xy_, xz_, yx_, yy_, yz_, zx_, zy_, zz_};
        System.arraycopy(r, 0, array, 0, 9);
    }
}
