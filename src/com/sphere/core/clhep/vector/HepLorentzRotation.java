package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepLorentzRotation (Vector/LorentzRotation.h, .icc, LorentzRotation.cc, LorentzRotationC.cc,
 * LorentzRotationD.cc): a general Lorentz transformation, any product of boosts and rotations, as its
 * 4x4 matrix; it decomposes back into a boost and a rotation.
 */
public class HepLorentzRotation implements Comparable<HepLorentzRotation> {

    public static final HepLorentzRotation IDENTITY = new HepLorentzRotation();

    protected double mxx, mxy, mxz, mxt, myx, myy, myz, myt, mzx, mzy, mzz, mzt, mtx, mty, mtz, mtt;

    /** The identity. */
    public HepLorentzRotation() {
        mxx = 1.0;
        myy = 1.0;
        mzz = 1.0;
        mtt = 1.0;
    }

    public HepLorentzRotation(HepLorentzRotation r) {
        set(r.rep4x4());
    }

    public HepLorentzRotation(HepRotation r) {
        set(r.rep4x4());
    }

    public HepLorentzRotation(HepAxialRotation r) {
        set(r.rep4x4());
    }

    public HepLorentzRotation(HepBoost b) {
        set(b.rep4x4());
    }

    public HepLorentzRotation(HepAxialBoost b) {
        set(b.rep4x4());
    }

    /** The boost by (bx, by, bz). */
    public HepLorentzRotation(double bx, double by, double bz) {
        set(bx, by, bz);
    }

    public HepLorentzRotation(Hep3Vector p) {
        set(p.x(), p.y(), p.z());
    }

    /** B * R. */
    public HepLorentzRotation(HepBoost b, HepRotation r) {
        set(b, r);
    }

    /** R * B. */
    public HepLorentzRotation(HepRotation r, HepBoost b) {
        set(r, b);
    }

    /** From its four columns, checked and made a Lorentz transformation (what cannot be is said on cerr). */
    public HepLorentzRotation(HepLorentzVector ccol1, HepLorentzVector ccol2, HepLorentzVector ccol3,
                              HepLorentzVector ccol4) {
        set(ccol1, ccol2, ccol3, ccol4);
    }

    public HepLorentzRotation(HepRep4x4 rep) {
        set(rep);
    }

    /** The sixteen elements, row by row (protected in C++). */
    protected HepLorentzRotation(double rxx, double rxy, double rxz, double rxt, double ryx, double ryy, double ryz,
                                 double ryt, double rzx, double rzy, double rzz, double rzt, double rtx, double rty,
                                 double rtz, double rtt) {
        mxx = rxx;
        mxy = rxy;
        mxz = rxz;
        mxt = rxt;
        myx = ryx;
        myy = ryy;
        myz = ryz;
        myt = ryt;
        mzx = rzx;
        mzy = rzy;
        mzz = rzz;
        mzt = rzt;
        mtx = rtx;
        mty = rty;
        mtz = rtz;
        mtt = rtt;
    }

    public HepLorentzRotation copy() {
        return new HepLorentzRotation(this);
    }

    /** operator=. */
    public HepLorentzRotation assign(HepLorentzRotation r) {
        return set(r.rep4x4());
    }

    public HepLorentzRotation assign(HepRotation m1) {
        return set(m1.rep4x4());
    }

    public HepLorentzRotation assign(HepBoost m1) {
        return set(m1.rep4x4());
    }

    public HepLorentzRotation set(HepRep4x4 rep) {
        mxx = rep.xx_;
        mxy = rep.xy_;
        mxz = rep.xz_;
        mxt = rep.xt_;
        myx = rep.yx_;
        myy = rep.yy_;
        myz = rep.yz_;
        myt = rep.yt_;
        mzx = rep.zx_;
        mzy = rep.zy_;
        mzz = rep.zz_;
        mzt = rep.zt_;
        mtx = rep.tx_;
        mty = rep.ty_;
        mtz = rep.tz_;
        mtt = rep.tt_;
        return this;
    }

    public HepLorentzRotation set(double bx, double by, double bz) {
        final double bp2 = bx * bx + by * by + bz * bz;
        if (bp2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Boost Vector supplied to set HepLorentzRotation represents speed >= c.",
                "HepLorentzRotation::set");
        }
        final double gamma = 1.0 / sqrt(1.0 - bp2);
        final double bgamma = gamma * gamma / (1.0 + gamma);
        mxx = 1.0 + bgamma * bx * bx;
        myy = 1.0 + bgamma * by * by;
        mzz = 1.0 + bgamma * bz * bz;
        mxy = myx = bgamma * bx * by;
        mxz = mzx = bgamma * bx * bz;
        myz = mzy = bgamma * by * bz;
        mxt = mtx = gamma * bx;
        myt = mty = gamma * by;
        mzt = mtz = gamma * bz;
        mtt = gamma;
        return this;
    }

    public HepLorentzRotation set(Hep3Vector p) {
        return set(p.x(), p.y(), p.z());
    }

    public HepLorentzRotation set(HepRotation r) {
        return set(r.rep4x4());
    }

    public HepLorentzRotation set(HepAxialRotation r) {
        return set(r.rep4x4());
    }

    public HepLorentzRotation set(HepBoost bboost) {
        return set(bboost.rep4x4());
    }

    public HepLorentzRotation set(HepAxialBoost bboost) {
        return set(bboost.rep4x4());
    }

    public HepLorentzRotation set(HepBoost b, HepRotation r) {
        set(b.rep4x4());
        return assign(matrixMultiplication(r.rep4x4()));
    }

    public HepLorentzRotation set(HepRotation r, HepBoost b) {
        set(r.rep4x4());
        return assign(matrixMultiplication(b.rep4x4()));
    }

    protected void setBoost(double bx, double by, double bz) {
        set(bx, by, bz);
    }

    public HepLorentzRotation set(HepLorentzVector ccol1, HepLorentzVector ccol2, HepLorentzVector ccol3,
                                  HepLorentzVector ccol4) {
        final HepLorentzVector.ZMpvMetric_t savedMetric =
            HepLorentzVector.setMetric(HepLorentzVector.ZMpvMetric_t.TimePositive);
        if (ccol4.getT() < 0) {
            ZMxpv.throwC(ZMxpv.IMPROPER_TRANSFORMATION,
                "column 4 supplied to define transformation has negative T component", "HepLorentzRotation::set");
            assign(new HepLorentzRotation());
            return this;
        }
        final String where = "HepLorentzRotation::set";
        final double tol = Hep4RotationInterface.tolerance;
        if (fabs(ccol1.dot(ccol1) + 1.0) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_SYMPLECTIC, "column 1 supplied for HepLorentzRotation has w*w != -1", where);
        }
        if (fabs(ccol2.dot(ccol2) + 1.0) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_SYMPLECTIC, "column 2 supplied for HepLorentzRotation has w*w != -1", where);
        }
        if (fabs(ccol3.dot(ccol3) + 1.0) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_SYMPLECTIC, "column 3 supplied for HepLorentzRotation has w*w != -1", where);
        }
        if (fabs(ccol4.dot(ccol4) - 1.0) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_SYMPLECTIC, "column 4 supplied for HepLorentzRotation has w*w != +1", where);
        }
        if (fabs(ccol1.dot(ccol2)) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "columns 1 and 2 supplied for HepLorentzRotation have non-zero dot",
                where);
        }
        if (fabs(ccol1.dot(ccol3)) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "columns 1 and 3 supplied for HepLorentzRotation have non-zero dot",
                where);
        }
        if (fabs(ccol1.dot(ccol4)) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "columns 1 and 4 supplied for HepLorentzRotation have non-zero dot",
                where);
        }
        if (fabs(ccol2.dot(ccol3)) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "columns 2 and 3 supplied for HepLorentzRotation have non-zero dot",
                where);
        }
        if (fabs(ccol2.dot(ccol4)) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "columns 2 and 4 supplied for HepLorentzRotation have non-zero dot",
                where);
        }
        if (fabs(ccol3.dot(ccol4)) > tol) {
            ZMxpv.throwC(ZMxpv.NOT_ORTHOGONAL, "columns 3 and 4 supplied for HepLorentzRotation have non-zero dot",
                where);
        }
        HepLorentzVector a, b, c, d;
        boolean isLorentzTransformation = true;
        double norm;
        d = new HepLorentzVector(ccol4);
        norm = d.dot(d);
        if (norm <= 0.0) {
            isLorentzTransformation = false;
            if (norm == 0.0) {
                d = new HepLorentzVector(HepLorentzVector.T_HAT4);
                norm = 1.0;
            }
        }
        d.divAssign(norm);
        c = ccol3.minus(d.times(ccol3.dot(d)));
        norm = -c.dot(c);
        if (norm <= 0.0) {
            isLorentzTransformation = false;
            if (norm == 0.0) {
                c = new HepLorentzVector(HepLorentzVector.Z_HAT4);
                norm = 1.0;
            }
        }
        c.divAssign(norm);
        b = ccol2.plus(c.times(ccol2.dot(c))).minus(d.times(ccol2.dot(d)));
        norm = -b.dot(b);
        if (norm <= 0.0) {
            isLorentzTransformation = false;
            if (norm == 0.0) {
                b = new HepLorentzVector(HepLorentzVector.Y_HAT4);
                norm = 1.0;
            }
        }
        b.divAssign(norm);
        a = ccol1.plus(b.times(ccol1.dot(b))).plus(c.times(ccol1.dot(c))).minus(d.times(ccol1.dot(d)));
        norm = -a.dot(a);
        if (norm <= 0.0) {
            isLorentzTransformation = false;
            if (norm == 0.0) {
                a = new HepLorentzVector(HepLorentzVector.X_HAT4);
                norm = 1.0;
            }
        }
        a.divAssign(norm);
        if (!isLorentzTransformation) {
            ZMxpv.throwC(ZMxpv.IMPROPER_TRANSFORMATION, "cols 1-4 supplied to define transformation form either \n"
                + "       a boosted reflection or a tachyonic transformation -- \n"
                + "       transformation will be set to Identity ", where);
            assign(new HepLorentzRotation());
        }
        if (isLorentzTransformation) {
            mxx = a.x();
            myx = a.y();
            mzx = a.z();
            mtx = a.t();
            mxy = b.x();
            myy = b.y();
            mzy = b.z();
            mty = b.t();
            mxz = c.x();
            myz = c.y();
            mzz = c.z();
            mtz = c.t();
            mxt = d.x();
            myt = d.y();
            mzt = d.z();
            mtt = d.t();
        }
        HepLorentzVector.setMetric(savedMetric);
        return this;
    }

    public HepLorentzRotation setRows(HepLorentzVector rrow1, HepLorentzVector rrow2, HepLorentzVector rrow3,
                                      HepLorentzVector rrow4) {
        set(rrow1, rrow2, rrow3, rrow4);
        double q1, q2, q3;
        q1 = mxy;
        q2 = mxz;
        q3 = mxt;
        mxy = myx;
        mxz = mzx;
        mxt = mtx;
        myx = q1;
        mzx = q2;
        mtx = q3;
        q1 = myz;
        q2 = myt;
        q3 = mzt;
        myz = mzy;
        myt = mty;
        mzt = mtz;
        mzy = q1;
        mty = q2;
        mtz = q3;
        return this;
    }

    // ------------------------------------------------------------------
    // Elements
    // ------------------------------------------------------------------

    public double xx() {
        return mxx;
    }

    public double xy() {
        return mxy;
    }

    public double xz() {
        return mxz;
    }

    public double xt() {
        return mxt;
    }

    public double yx() {
        return myx;
    }

    public double yy() {
        return myy;
    }

    public double yz() {
        return myz;
    }

    public double yt() {
        return myt;
    }

    public double zx() {
        return mzx;
    }

    public double zy() {
        return mzy;
    }

    public double zz() {
        return mzz;
    }

    public double zt() {
        return mzt;
    }

    public double tx() {
        return mtx;
    }

    public double ty() {
        return mty;
    }

    public double tz() {
        return mtz;
    }

    public double tt() {
        return mtt;
    }

    public HepLorentzVector col1() {
        return new HepLorentzVector(mxx, myx, mzx, mtx);
    }

    public HepLorentzVector col2() {
        return new HepLorentzVector(mxy, myy, mzy, mty);
    }

    public HepLorentzVector col3() {
        return new HepLorentzVector(mxz, myz, mzz, mtz);
    }

    public HepLorentzVector col4() {
        return new HepLorentzVector(mxt, myt, mzt, mtt);
    }

    public HepLorentzVector row1() {
        return new HepLorentzVector(mxx, mxy, mxz, mxt);
    }

    public HepLorentzVector row2() {
        return new HepLorentzVector(myx, myy, myz, myt);
    }

    public HepLorentzVector row3() {
        return new HepLorentzVector(mzx, mzy, mzz, mzt);
    }

    public HepLorentzVector row4() {
        return new HepLorentzVector(mtx, mty, mtz, mtt);
    }

    public HepRep4x4 rep4x4() {
        return new HepRep4x4(mxx, mxy, mxz, mxt, myx, myy, myz, myt, mzx, mzy, mzz, mzt, mtx, mty, mtz, mtt);
    }

    /** operator()(i, j); bad indices are said on cerr and give 0. */
    public double get(int i, int j) {
        if (i == 0) {
            if (j == 0) return xx();
            if (j == 1) return xy();
            if (j == 2) return xz();
            if (j == 3) return xt();
        } else if (i == 1) {
            if (j == 0) return yx();
            if (j == 1) return yy();
            if (j == 2) return yz();
            if (j == 3) return yt();
        } else if (i == 2) {
            if (j == 0) return zx();
            if (j == 1) return zy();
            if (j == 2) return zz();
            if (j == 3) return zt();
        } else if (i == 3) {
            if (j == 0) return tx();
            if (j == 1) return ty();
            if (j == 2) return tz();
            if (j == 3) return tt();
        }
        StdStreams.cerr().print("HepLorentzRotation subscripting: bad indeces (" + i + "," + j + ")\n");
        return 0.0;
    }

    /** operator[](i): the row i, read by its own [j]. */
    public Row row(int i) {
        return new Row(this, i);
    }

    public record Row(HepLorentzRotation rr, int ii) {
        public double get(int jj) {
            return rr.get(ii, jj);
        }
    }

    // ------------------------------------------------------------------
    // Decomposition and comparisons
    // ------------------------------------------------------------------

    /** this = boost * rotation. */
    public void decompose(HepBoost bboost, HepRotation rotation) {
        final Hep3Vector betaVec = new Hep3Vector(xt(), yt(), zt());
        betaVec.timesAssign(1.0 / tt());
        bboost.set(betaVec);
        final HepBoost b = new HepBoost(betaVec.negate());
        final HepLorentzRotation r = b.times(this);
        final HepRep3x3 m1 = new HepRep3x3(r.xx(), r.xy(), r.xz(), r.yx(), r.yy(), r.yz(), r.zx(), r.zy(), r.zz());
        rotation.set(m1);
        rotation.rectify();
    }

    public void decompose(Hep3Vector bboost, HepAxisAngle rotation) {
        final HepRotation r = new HepRotation();
        final HepBoost b = new HepBoost();
        decompose(b, r);
        bboost.assign(b.boostVector());
        rotation.assign(r.axisAngle());
    }

    /** this = rotation * boost. */
    public void decompose(HepRotation rotation, HepBoost bboost) {
        final Hep3Vector betaVec = new Hep3Vector(tx(), ty(), tz());
        betaVec.timesAssign(1.0 / tt());
        bboost.set(betaVec);
        final HepBoost b = new HepBoost(betaVec.negate());
        final HepLorentzRotation r = times(b);
        final HepRep3x3 m1 = new HepRep3x3(r.xx(), r.xy(), r.xz(), r.yx(), r.yy(), r.yz(), r.zx(), r.zy(), r.zz());
        rotation.set(m1);
        rotation.rectify();
    }

    public void decompose(HepAxisAngle rotation, Hep3Vector bboost) {
        final HepRotation r = new HepRotation();
        final HepBoost b = new HepBoost();
        decompose(r, b);
        rotation.assign(r.axisAngle());
        bboost.assign(b.boostVector());
    }

    /** tt first, down to xx. */
    public int compare(HepLorentzRotation m1) {
        if (mtt < m1.mtt) return -1;
        else if (mtt > m1.mtt) return 1;
        else if (mtz < m1.mtz) return -1;
        else if (mtz > m1.mtz) return 1;
        else if (mty < m1.mty) return -1;
        else if (mty > m1.mty) return 1;
        else if (mtx < m1.mtx) return -1;
        else if (mtx > m1.mtx) return 1;
        else if (mzt < m1.mzt) return -1;
        else if (mzt > m1.mzt) return 1;
        else if (mzz < m1.mzz) return -1;
        else if (mzz > m1.mzz) return 1;
        else if (mzy < m1.mzy) return -1;
        else if (mzy > m1.mzy) return 1;
        else if (mzx < m1.mzx) return -1;
        else if (mzx > m1.mzx) return 1;
        else if (myt < m1.myt) return -1;
        else if (myt > m1.myt) return 1;
        else if (myz < m1.myz) return -1;
        else if (myz > m1.myz) return 1;
        else if (myy < m1.myy) return -1;
        else if (myy > m1.myy) return 1;
        else if (myx < m1.myx) return -1;
        else if (myx > m1.myx) return 1;
        else if (mxt < m1.mxt) return -1;
        else if (mxt > m1.mxt) return 1;
        else if (mxz < m1.mxz) return -1;
        else if (mxz > m1.mxz) return 1;
        else if (mxy < m1.mxy) return -1;
        else if (mxy > m1.mxy) return 1;
        else if (mxx < m1.mxx) return -1;
        else if (mxx > m1.mxx) return 1;
        else return 0;
    }

    @Override
    public int compareTo(HepLorentzRotation o) {
        return compare(o);
    }

    public boolean isEqual(HepLorentzRotation r) {
        return mxx == r.xx() && mxy == r.xy() && mxz == r.xz() && mxt == r.xt()
            && myx == r.yx() && myy == r.yy() && myz == r.yz() && myt == r.yt()
            && mzx == r.zx() && mzy == r.zy() && mzz == r.zz() && mzt == r.zt()
            && mtx == r.tx() && mty == r.ty() && mtz == r.tz() && mtt == r.tt();
    }

    /** lt == r: the rotation as a Lorentz transformation. */
    public boolean isEqual(HepRotation r) {
        return isEqual(new HepLorentzRotation(r));
    }

    public boolean isEqual(HepBoost b) {
        return isEqual(new HepLorentzRotation(b));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepLorentzRotation r && isEqual(r);
    }

    @Override
    public int hashCode() {
        final double[] a = new double[16];
        rep4x4().getArray(a);
        return java.util.Arrays.hashCode(a);
    }

    public boolean lessThan(HepLorentzRotation r) {
        return compare(r) < 0;
    }

    public boolean lessOrEqual(HepLorentzRotation r) {
        return compare(r) <= 0;
    }

    public boolean greaterOrEqual(HepLorentzRotation r) {
        return compare(r) >= 0;
    }

    public boolean greaterThan(HepLorentzRotation r) {
        return compare(r) > 0;
    }

    public boolean isIdentity() {
        return mxx == 1.0 && mxy == 0.0 && mxz == 0.0 && mxt == 0.0
            && myx == 0.0 && myy == 1.0 && myz == 0.0 && myt == 0.0
            && mzx == 0.0 && mzy == 0.0 && mzz == 1.0 && mzt == 0.0
            && mtx == 0.0 && mty == 0.0 && mtz == 0.0 && mtt == 1.0;
    }

    public double distance2(HepBoost b) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        decompose(b1, r1);
        final double db2 = b1.distance2(b);
        final double dr2 = r1.norm2();
        return db2 + dr2;
    }

    public double distance2(HepRotation r) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        decompose(b1, r1);
        final double db2 = b1.norm2();
        final double dr2 = r1.distance2(r);
        return db2 + dr2;
    }

    public double distance2(HepLorentzRotation lt) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        decompose(b1, r1);
        final HepBoost b2 = new HepBoost();
        final HepRotation r2 = new HepRotation();
        lt.decompose(b2, r2);
        final double db2 = b1.distance2(b2);
        final double dr2 = r1.distance2(r2);
        return db2 + dr2;
    }

    public double howNear(HepBoost b) {
        return sqrt(distance2(b));
    }

    public double howNear(HepRotation r) {
        return sqrt(distance2(r));
    }

    public double howNear(HepLorentzRotation lt) {
        return sqrt(distance2(lt));
    }

    public boolean isNear(HepBoost b) {
        return isNear(b, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoost b, double epsilon) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        decompose(b1, r1);
        final double db2 = b1.distance2(b);
        if (db2 > epsilon * epsilon) {
            return false;
        }
        final double dr2 = r1.norm2();
        return (db2 + dr2) <= epsilon * epsilon;
    }

    public boolean isNear(HepRotation r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotation r, double epsilon) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        decompose(b1, r1);
        final double db2 = b1.norm2();
        if (db2 > epsilon * epsilon) {
            return false;
        }
        final double dr2 = r1.distance2(r);
        return (db2 + dr2) <= epsilon * epsilon;
    }

    public boolean isNear(HepLorentzRotation lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepLorentzRotation lt, double epsilon) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        decompose(b1, r1);
        final HepBoost b2 = new HepBoost();
        final HepRotation r2 = new HepRotation();
        lt.decompose(b2, r2);
        final double db2 = b1.distance2(b2);
        if (db2 > epsilon * epsilon) {
            return false;
        }
        final double dr2 = r1.distance2(r2);
        return (db2 + dr2) <= epsilon * epsilon;
    }

    public double norm2() {
        final HepBoost b = new HepBoost();
        final HepRotation r = new HepRotation();
        decompose(b, r);
        return b.norm2() + r.norm2();
    }

    public void rectify() {
        final Hep3Vector beta = new Hep3Vector(tx(), ty(), tz());
        double gam = tt();
        if (gam <= 0) {
            ZMxpv.throwA(ZMxpv.IMPROPER_TRANSFORMATION, "rectify() on a transformation with tt() <= 0 - will not help!",
                "HepLorentzRotation::rectify");
            gam = 1;
        }
        beta.timesAssign(1.0 / gam);
        final HepLorentzRotation r = times(new HepBoost(beta.negate()));
        final HepRep3x3 m1 = new HepRep3x3(r.xx(), r.xy(), r.xz(), r.yx(), r.yy(), r.yz(), r.zx(), r.zy(), r.zz());
        final HepRotation rgood = new HepRotation(m1);
        rgood.rectify();
        set(rgood, new HepBoost(beta));
    }

    // ------------------------------------------------------------------
    // Application and products
    // ------------------------------------------------------------------

    public HepLorentzVector vectorMultiplication(HepLorentzVector p) {
        final double x = p.x();
        final double y = p.y();
        final double z = p.z();
        final double t = p.t();
        return new HepLorentzVector(mxx * x + mxy * y + mxz * z + mxt * t,
            myx * x + myy * y + myz * z + myt * t,
            mzx * x + mzy * y + mzz * z + mzt * t,
            mtx * x + mty * y + mtz * z + mtt * t);
    }

    public HepLorentzVector apply(HepLorentzVector w) {
        return vectorMultiplication(w);
    }

    public HepLorentzVector times(HepLorentzVector p) {
        return vectorMultiplication(p);
    }

    public HepLorentzRotation matrixMultiplication(HepRep4x4 m1) {
        return new HepLorentzRotation(
            mxx * m1.xx_ + mxy * m1.yx_ + mxz * m1.zx_ + mxt * m1.tx_,
            mxx * m1.xy_ + mxy * m1.yy_ + mxz * m1.zy_ + mxt * m1.ty_,
            mxx * m1.xz_ + mxy * m1.yz_ + mxz * m1.zz_ + mxt * m1.tz_,
            mxx * m1.xt_ + mxy * m1.yt_ + mxz * m1.zt_ + mxt * m1.tt_,
            myx * m1.xx_ + myy * m1.yx_ + myz * m1.zx_ + myt * m1.tx_,
            myx * m1.xy_ + myy * m1.yy_ + myz * m1.zy_ + myt * m1.ty_,
            myx * m1.xz_ + myy * m1.yz_ + myz * m1.zz_ + myt * m1.tz_,
            myx * m1.xt_ + myy * m1.yt_ + myz * m1.zt_ + myt * m1.tt_,
            mzx * m1.xx_ + mzy * m1.yx_ + mzz * m1.zx_ + mzt * m1.tx_,
            mzx * m1.xy_ + mzy * m1.yy_ + mzz * m1.zy_ + mzt * m1.ty_,
            mzx * m1.xz_ + mzy * m1.yz_ + mzz * m1.zz_ + mzt * m1.tz_,
            mzx * m1.xt_ + mzy * m1.yt_ + mzz * m1.zt_ + mzt * m1.tt_,
            mtx * m1.xx_ + mty * m1.yx_ + mtz * m1.zx_ + mtt * m1.tx_,
            mtx * m1.xy_ + mty * m1.yy_ + mtz * m1.zy_ + mtt * m1.ty_,
            mtx * m1.xz_ + mty * m1.yz_ + mtz * m1.zz_ + mtt * m1.tz_,
            mtx * m1.xt_ + mty * m1.yt_ + mtz * m1.zt_ + mtt * m1.tt_);
    }

    public HepLorentzRotation times(HepBoost b) {
        return matrixMultiplication(b.rep4x4());
    }

    public HepLorentzRotation times(HepRotation r) {
        return matrixMultiplication(r.rep4x4());
    }

    public HepLorentzRotation times(HepLorentzRotation lt) {
        return matrixMultiplication(lt.rep4x4());
    }

    /** r * lt, as CLHEP writes it element by element. */
    public static HepLorentzRotation times(HepRotation r, HepLorentzRotation lt) {
        return new HepLorentzRotation(new HepRep4x4(
            r.xx() * lt.xx() + r.xy() * lt.yx() + r.xz() * lt.zx() + r.xt() * lt.tx(),
            r.xx() * lt.xy() + r.xy() * lt.yy() + r.xz() * lt.zy() + r.xt() * lt.ty(),
            r.xx() * lt.xz() + r.xy() * lt.yz() + r.xz() * lt.zz() + r.xt() * lt.tz(),
            r.xx() * lt.xt() + r.xy() * lt.yt() + r.xz() * lt.zt() + r.xt() * lt.tt(),
            r.yx() * lt.xx() + r.yy() * lt.yx() + r.yz() * lt.zx() + r.yt() * lt.tx(),
            r.yx() * lt.xy() + r.yy() * lt.yy() + r.yz() * lt.zy() + r.yt() * lt.ty(),
            r.yx() * lt.xz() + r.yy() * lt.yz() + r.yz() * lt.zz() + r.yt() * lt.tz(),
            r.yx() * lt.xt() + r.yy() * lt.yt() + r.yz() * lt.zt() + r.yt() * lt.tt(),
            r.zx() * lt.xx() + r.zy() * lt.yx() + r.zz() * lt.zx() + r.zt() * lt.tx(),
            r.zx() * lt.xy() + r.zy() * lt.yy() + r.zz() * lt.zy() + r.zt() * lt.ty(),
            r.zx() * lt.xz() + r.zy() * lt.yz() + r.zz() * lt.zz() + r.zt() * lt.tz(),
            r.zx() * lt.xt() + r.zy() * lt.yt() + r.zz() * lt.zt() + r.zt() * lt.tt(),
            r.tx() * lt.xx() + r.ty() * lt.yx() + r.tz() * lt.zx() + r.tt() * lt.tx(),
            r.tx() * lt.xy() + r.ty() * lt.yy() + r.tz() * lt.zy() + r.tt() * lt.ty(),
            r.tx() * lt.xz() + r.ty() * lt.yz() + r.tz() * lt.zz() + r.tt() * lt.tz(),
            r.tx() * lt.xt() + r.ty() * lt.yt() + r.tz() * lt.zt() + r.tt() * lt.tt()));
    }

    public HepLorentzRotation timesAssign(HepBoost b) {
        return assign(matrixMultiplication(b.rep4x4()));
    }

    public HepLorentzRotation timesAssign(HepRotation r) {
        return assign(matrixMultiplication(r.rep4x4()));
    }

    public HepLorentzRotation timesAssign(HepLorentzRotation lt) {
        return assign(matrixMultiplication(lt.rep4x4()));
    }

    public HepLorentzRotation transform(HepBoost b) {
        return assign(new HepLorentzRotation(b).matrixMultiplication(rep4x4()));
    }

    public HepLorentzRotation transform(HepRotation r) {
        return assign(new HepLorentzRotation(r).matrixMultiplication(rep4x4()));
    }

    public HepLorentzRotation transform(HepLorentzRotation lt) {
        return assign(lt.matrixMultiplication(rep4x4()));
    }

    public HepLorentzRotation rotateX(double delta) {
        final double c1 = cos(delta);
        final double s1 = sin(delta);
        final HepLorentzVector rowy = row2();
        final HepLorentzVector rowz = row3();
        final HepLorentzVector r2 = rowy.times(c1).minus(rowz.times(s1));
        final HepLorentzVector r3 = rowy.times(s1).plus(rowz.times(c1));
        myx = r2.x();
        myy = r2.y();
        myz = r2.z();
        myt = r2.t();
        mzx = r3.x();
        mzy = r3.y();
        mzz = r3.z();
        mzt = r3.t();
        return this;
    }

    public HepLorentzRotation rotateY(double delta) {
        final double c1 = cos(delta);
        final double s1 = sin(delta);
        final HepLorentzVector rowx = row1();
        final HepLorentzVector rowz = row3();
        final HepLorentzVector r1 = rowx.times(c1).plus(rowz.times(s1));
        final HepLorentzVector r3 = rowx.times(-s1).plus(rowz.times(c1));
        mxx = r1.x();
        mxy = r1.y();
        mxz = r1.z();
        mxt = r1.t();
        mzx = r3.x();
        mzy = r3.y();
        mzz = r3.z();
        mzt = r3.t();
        return this;
    }

    public HepLorentzRotation rotateZ(double delta) {
        final double c1 = cos(delta);
        final double s1 = sin(delta);
        final HepLorentzVector rowx = row1();
        final HepLorentzVector rowy = row2();
        final HepLorentzVector r1 = rowx.times(c1).minus(rowy.times(s1));
        final HepLorentzVector r2 = rowx.times(s1).plus(rowy.times(c1));
        mxx = r1.x();
        mxy = r1.y();
        mxz = r1.z();
        mxt = r1.t();
        myx = r2.x();
        myy = r2.y();
        myz = r2.z();
        myt = r2.t();
        return this;
    }

    public HepLorentzRotation rotate(double angle, Hep3Vector axis) {
        return transform(new HepRotation().rotate(angle, axis));
    }

    public HepLorentzRotation boostX(double beta) {
        return boostAlong(beta, 0, "X");
    }

    public HepLorentzRotation boostY(double beta) {
        return boostAlong(beta, 1, "Y");
    }

    public HepLorentzRotation boostZ(double beta) {
        return boostAlong(beta, 2, "Z");
    }

    /** The rows of the axis and of t mixed by the boost, as boostX, boostY and boostZ do it. */
    private HepLorentzRotation boostAlong(double beta, int axis, String name) {
        final double b2 = beta * beta;
        if (b2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Beta supplied to HepLorentzRotation::boost" + name
                + " represents speed >= c.", "HepLorentzRotation::boost" + name);
        }
        final double g1 = 1.0 / sqrt(1.0 - b2);
        final double bg = beta * g1;
        final HepLorentzVector rowa = axis == 0 ? row1() : axis == 1 ? row2() : row3();
        final HepLorentzVector rowt = row4();
        final HepLorentzVector ra = rowa.times(g1).plus(rowt.times(bg));
        final HepLorentzVector r4 = rowa.times(bg).plus(rowt.times(g1));
        switch (axis) {
            case 0 -> {
                mxx = ra.x();
                mxy = ra.y();
                mxz = ra.z();
                mxt = ra.t();
            }
            case 1 -> {
                myx = ra.x();
                myy = ra.y();
                myz = ra.z();
                myt = ra.t();
            }
            default -> {
                mzx = ra.x();
                mzy = ra.y();
                mzz = ra.z();
                mzt = ra.t();
            }
        }
        mtx = r4.x();
        mty = r4.y();
        mtz = r4.z();
        mtt = r4.t();
        return this;
    }

    public HepLorentzRotation boost(double bx, double by, double bz) {
        return transform(new HepLorentzRotation(bx, by, bz));
    }

    public HepLorentzRotation boost(Hep3Vector b) {
        return transform(new HepLorentzRotation(b));
    }

    public HepLorentzRotation inverse() {
        return new HepLorentzRotation(mxx, myx, mzx, -mtx, mxy, myy, mzy, -mty, mxz, myz, mzz, -mtz, -mxt, -myt, -mzt,
            mtt);
    }

    public HepLorentzRotation invert() {
        return assign(inverse());
    }

    public static HepLorentzRotation inverseOf(HepLorentzRotation lt) {
        return new HepLorentzRotation(new HepRep4x4(lt.mxx, lt.myx, lt.mzx, -lt.mtx, lt.mxy, lt.myy, lt.mzy, -lt.mty,
            lt.mxz, lt.myz, lt.mzz, -lt.mtz, -lt.mxt, -lt.myt, -lt.mzt, lt.mtt));
    }

    public static double getTolerance() {
        return Hep4RotationInterface.tolerance;
    }

    public static double setTolerance(double tol) {
        return Hep4RotationInterface.setTolerance(tol);
    }

    /** The matrix in four rows of width 11, precision 6. */
    public COStream print(COStream os) {
        final double[][] m = {{xx(), xy(), xz(), xt()}, {yx(), yy(), yz(), yt()}, {zx(), zy(), zz(), zt()},
            {tx(), ty(), tz(), tt()}};
        for (int i = 0; i < 4; i++) {
            os.put(i == 0 ? "\n   [ ( " : "     ( ");
            for (int j = 0; j < 4; j++) {
                os.setw(11).setprecision(6).put(m[i][j]);
                os.put(j < 3 ? "   " : i < 3 ? ")\n" : ") ]\n");
            }
        }
        return os;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
