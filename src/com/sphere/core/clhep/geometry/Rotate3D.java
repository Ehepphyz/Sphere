package com.sphere.core.clhep.geometry;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's HepGeom::Rotate3D: a rotation, about an axis through the origin or through two points, or the
 * one that turns two directions onto two others.
 */
public class Rotate3D extends Transform3D {

    /** The identity. */
    public Rotate3D() {
        super();
    }

    public Rotate3D(HepRotation mt) {
        xx_ = mt.xx();
        xy_ = mt.xy();
        xz_ = mt.xz();
        yx_ = mt.yx();
        yy_ = mt.yy();
        yz_ = mt.yz();
        zx_ = mt.zx();
        zy_ = mt.zy();
        zz_ = mt.zz();
        dx_ = 0;
        dy_ = 0;
        dz_ = 0;
    }

    /** By a about the axis from p1 to p2 (the identity, said on cerr, when they are the same point). */
    public Rotate3D(double a, Point3D p1, Point3D p2) {
        super();
        if (a == 0) return;
        double cx = p2.x() - p1.x(), cy = p2.y() - p1.y(), cz = p2.z() - p1.z();
        final double ll = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (ll == 0) {
            StdStreams.cerr().print("Rotate3D: zero axis\n");
        } else {
            final double cosa = CMath.cos(a), sina = CMath.sin(a);
            cx /= ll;
            cy /= ll;
            cz /= ll;

            final double txx = cosa + (1 - cosa) * cx * cx;
            final double txy = (1 - cosa) * cx * cy - sina * cz;
            final double txz = (1 - cosa) * cx * cz + sina * cy;

            final double tyx = (1 - cosa) * cy * cx + sina * cz;
            final double tyy = cosa + (1 - cosa) * cy * cy;
            final double tyz = (1 - cosa) * cy * cz - sina * cx;

            final double tzx = (1 - cosa) * cz * cx - sina * cy;
            final double tzy = (1 - cosa) * cz * cy + sina * cx;
            final double tzz = cosa + (1 - cosa) * cz * cz;

            final double tdx = p1.x(), tdy = p1.y(), tdz = p1.z();

            setTransform(txx, txy, txz, tdx - txx * tdx - txy * tdy - txz * tdz,
                tyx, tyy, tyz, tdy - tyx * tdx - tyy * tdy - tyz * tdz,
                tzx, tzy, tzz, tdz - tzx * tdx - tzy * tdy - tzz * tdz);
        }
    }

    /** By a about the axis v through the origin. */
    public Rotate3D(double a, Vector3D v) {
        this(a, new Point3D(0, 0, 0), new Point3D(v.x(), v.y(), v.z()));
    }

    /** The rotation that turns the directions fr1, fr2 onto to1, to2. */
    public Rotate3D(Point3D fr1, Point3D fr2, Point3D to1, Point3D to2) {
        super(new Point3D(0, 0, 0), fr1, fr2, new Point3D(0, 0, 0), to1, to2);
    }
}
