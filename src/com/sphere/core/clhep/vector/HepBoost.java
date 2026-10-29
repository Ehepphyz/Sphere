package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepBoost (Vector/Boost.h, .icc, Boost.cc): a pure Lorentz boost in any direction, kept as
 * its symmetric 4x4 matrix. Its products with other boosts and rotations are HepLorentzRotations.
 */
public class HepBoost implements Comparable<HepBoost> {

    protected HepRep4x4Symmetric rep_;

    /** The identity. */
    public HepBoost() {
        rep_ = new HepRep4x4Symmetric();
    }

    public HepBoost(HepBoost m1) {
        rep_ = copy(m1.rep_);
    }

    /** By the velocity (betaX, betaY, betaZ); a speed of c or more is reported and thrown. */
    public HepBoost(double betaX, double betaY, double betaZ) {
        rep_ = new HepRep4x4Symmetric();
        set(betaX, betaY, betaZ);
    }

    public HepBoost(HepRep4x4Symmetric m1) {
        rep_ = copy(m1);
    }

    /** By beta along a direction (made a unit vector). */
    public HepBoost(Hep3Vector ddirection, double bbeta) {
        rep_ = new HepRep4x4Symmetric();
        final double length = ddirection.mag();
        if (length == 0) {
            ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "HepBoost constructed using a zero vector as direction",
                "HepBoost::HepBoost");
            set(0, 0, 0);
        }
        set(bbeta * ddirection.x() / length, bbeta * ddirection.y() / length, bbeta * ddirection.z() / length);
    }

    /** By a velocity vector. */
    public HepBoost(Hep3Vector boost) {
        rep_ = new HepRep4x4Symmetric();
        set(boost.x(), boost.y(), boost.z());
    }

    /** The general boost of a HepBoostX, Y or Z. */
    public HepBoost(HepAxialBoost boost) {
        rep_ = new HepRep4x4Symmetric();
        set(boost.boostVector());
    }

    /** The ten elements of the symmetric matrix (protected in C++). */
    protected HepBoost(double xx1, double xy1, double xz1, double xt1, double yy1, double yz1, double yt1, double zz1,
                       double zt1, double tt1) {
        rep_ = new HepRep4x4Symmetric(xx1, xy1, xz1, xt1, yy1, yz1, yt1, zz1, zt1, tt1);
    }

    private static HepRep4x4Symmetric copy(HepRep4x4Symmetric r) {
        return new HepRep4x4Symmetric(r.xx_, r.xy_, r.xz_, r.xt_, r.yy_, r.yz_, r.yt_, r.zz_, r.zt_, r.tt_);
    }

    public HepBoost copy() {
        return new HepBoost(this);
    }

    /** operator=. */
    public HepBoost assign(HepBoost m1) {
        rep_ = copy(m1.rep_);
        return this;
    }

    public HepBoost set(double bx, double by, double bz) {
        final double bp2 = bx * bx + by * by + bz * bz;
        if (bp2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Boost Vector supplied to set HepBoost represents speed >= c.",
                "HepBoost::set");
        }
        final double ggamma = 1.0 / sqrt(1.0 - bp2);
        final double bgamma = ggamma * ggamma / (1.0 + ggamma);
        rep_.xx_ = 1.0 + bgamma * bx * bx;
        rep_.yy_ = 1.0 + bgamma * by * by;
        rep_.zz_ = 1.0 + bgamma * bz * bz;
        rep_.xy_ = bgamma * bx * by;
        rep_.xz_ = bgamma * bx * bz;
        rep_.yz_ = bgamma * by * bz;
        rep_.xt_ = ggamma * bx;
        rep_.yt_ = ggamma * by;
        rep_.zt_ = ggamma * bz;
        rep_.tt_ = ggamma;
        return this;
    }

    public HepBoost set(HepRep4x4Symmetric m1) {
        rep_ = copy(m1);
        return this;
    }

    public HepBoost set(Hep3Vector ddirection, double bbeta) {
        final double length = ddirection.mag();
        if (length <= 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "Direction supplied to set HepBoost is zero.", "HepBoost::set");
        }
        set(bbeta * ddirection.x() / length, bbeta * ddirection.y() / length, bbeta * ddirection.z() / length);
        return this;
    }

    public HepBoost set(Hep3Vector bboost) {
        return set(bboost.x(), bboost.y(), bboost.z());
    }

    public HepBoost set(HepAxialBoost boost) {
        return set(boost.boostVector());
    }

    protected void setBoost(double bx, double by, double bz) {
        set(bx, by, bz);
    }

    public double beta() {
        return sqrt(1.0 - 1.0 / (rep_.tt_ * rep_.tt_));
    }

    public double gamma() {
        return rep_.tt_;
    }

    public Hep3Vector boostVector() {
        return new Hep3Vector(rep_.xt_, rep_.yt_, rep_.zt_).times(1.0 / rep_.tt_);
    }

    public Hep3Vector getDirection() {
        final double norm = 1.0 / beta();
        return boostVector().times(norm);
    }

    public Hep3Vector direction() {
        return getDirection();
    }

    public double xx() {
        return rep_.xx_;
    }

    public double xy() {
        return rep_.xy_;
    }

    public double xz() {
        return rep_.xz_;
    }

    public double xt() {
        return rep_.xt_;
    }

    public double yx() {
        return rep_.xy_;
    }

    public double yy() {
        return rep_.yy_;
    }

    public double yz() {
        return rep_.yz_;
    }

    public double yt() {
        return rep_.yt_;
    }

    public double zx() {
        return rep_.xz_;
    }

    public double zy() {
        return rep_.yz_;
    }

    public double zz() {
        return rep_.zz_;
    }

    public double zt() {
        return rep_.zt_;
    }

    public double tx() {
        return rep_.xt_;
    }

    public double ty() {
        return rep_.yt_;
    }

    public double tz() {
        return rep_.zt_;
    }

    public double tt() {
        return rep_.tt_;
    }

    public HepLorentzVector col1() {
        return new HepLorentzVector(xx(), yx(), zx(), tx());
    }

    public HepLorentzVector col2() {
        return new HepLorentzVector(xy(), yy(), zy(), ty());
    }

    public HepLorentzVector col3() {
        return new HepLorentzVector(xz(), yz(), zz(), tz());
    }

    public HepLorentzVector col4() {
        return new HepLorentzVector(xt(), yt(), zt(), tt());
    }

    public HepLorentzVector row1() {
        return new HepLorentzVector(col1());
    }

    public HepLorentzVector row2() {
        return new HepLorentzVector(col2());
    }

    public HepLorentzVector row3() {
        return new HepLorentzVector(col3());
    }

    public HepLorentzVector row4() {
        return new HepLorentzVector(col4());
    }

    public HepRep4x4 rep4x4() {
        return new HepRep4x4(rep_);
    }

    public HepRep4x4Symmetric rep4x4Symmetric() {
        return copy(rep_);
    }

    public void decompose(HepRotation rotation, HepBoost boost) {
        final HepAxisAngle vdelta = new HepAxisAngle();
        rotation.assign(new HepRotation(vdelta));
        final Hep3Vector bbeta = boostVector();
        boost.assign(new HepBoost(bbeta));
    }

    public void decompose(HepAxisAngle rotation, Hep3Vector boost) {
        rotation.assign(new HepAxisAngle());
        boost.assign(boostVector());
    }

    public void decompose(HepBoost boost, HepRotation rotation) {
        decompose(rotation, boost);
    }

    public void decompose(Hep3Vector boost, HepAxisAngle rotation) {
        decompose(rotation, boost);
    }

    /** tt first, then zt, zz, yt, yz, yy, xt, xz, xy, xx. */
    public int compare(HepBoost b) {
        final HepRep4x4Symmetric s1 = b.rep_;
        if (rep_.tt_ < s1.tt_) return -1;
        else if (rep_.tt_ > s1.tt_) return 1;
        else if (rep_.zt_ < s1.zt_) return -1;
        else if (rep_.zt_ > s1.zt_) return 1;
        else if (rep_.zz_ < s1.zz_) return -1;
        else if (rep_.zz_ > s1.zz_) return 1;
        else if (rep_.yt_ < s1.yt_) return -1;
        else if (rep_.yt_ > s1.yt_) return 1;
        else if (rep_.yz_ < s1.yz_) return -1;
        else if (rep_.yz_ > s1.yz_) return 1;
        else if (rep_.yy_ < s1.yy_) return -1;
        else if (rep_.yy_ > s1.yy_) return 1;
        else if (rep_.xt_ < s1.xt_) return -1;
        else if (rep_.xt_ > s1.xt_) return 1;
        else if (rep_.xz_ < s1.xz_) return -1;
        else if (rep_.xz_ > s1.xz_) return 1;
        else if (rep_.xy_ < s1.xy_) return -1;
        else if (rep_.xy_ > s1.xy_) return 1;
        else if (rep_.xx_ < s1.xx_) return -1;
        else if (rep_.xx_ > s1.xx_) return 1;
        else return 0;
    }

    @Override
    public int compareTo(HepBoost b) {
        return compare(b);
    }

    public boolean isEqual(HepBoost b) {
        final HepRep4x4Symmetric s1 = b.rep_;
        return rep_.xx_ == s1.xx_ && rep_.xy_ == s1.xy_ && rep_.xz_ == s1.xz_ && rep_.xt_ == s1.xt_
            && rep_.yy_ == s1.yy_ && rep_.yz_ == s1.yz_ && rep_.yt_ == s1.yt_
            && rep_.zz_ == s1.zz_ && rep_.zt_ == s1.zt_
            && rep_.tt_ == s1.tt_;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepBoost b && isEqual(b);
    }

    @Override
    public int hashCode() {
        final double[] a = new double[10];
        rep_.getArray(a);
        return java.util.Arrays.hashCode(a);
    }

    public boolean lessOrEqual(HepBoost b) {
        return compare(b) <= 0;
    }

    public boolean greaterOrEqual(HepBoost b) {
        return compare(b) >= 0;
    }

    public boolean lessThan(HepBoost b) {
        return compare(b) < 0;
    }

    public boolean greaterThan(HepBoost b) {
        return compare(b) > 0;
    }

    public boolean isIdentity() {
        return xx() == 1.0 && xy() == 0.0 && xz() == 0.0 && xt() == 0.0
            && yy() == 1.0 && yz() == 0.0 && yt() == 0.0
            && zz() == 1.0 && zt() == 0.0
            && tt() == 1.0;
    }

    public double distance2(HepBoost b) {
        final double bgx = rep_.xt_ - b.rep_.xt_;
        final double bgy = rep_.yt_ - b.rep_.yt_;
        final double bgz = rep_.zt_ - b.rep_.zt_;
        return bgx * bgx + bgy * bgy + bgz * bgz;
    }

    public double distance2(HepBoostX bx) {
        final double bgx = rep_.xt_ - bx.beta() * bx.gamma();
        final double bgy = rep_.yt_;
        final double bgz = rep_.zt_;
        return bgx * bgx + bgy * bgy + bgz * bgz;
    }

    public double distance2(HepBoostY by) {
        final double bgy = rep_.xt_;
        final double bgx = rep_.yt_ - by.beta() * by.gamma();
        final double bgz = rep_.zt_;
        return bgx * bgx + bgy * bgy + bgz * bgz;
    }

    public double distance2(HepBoostZ bz) {
        final double bgz = rep_.xt_;
        final double bgy = rep_.yt_;
        final double bgx = rep_.zt_ - bz.beta() * bz.gamma();
        return bgx * bgx + bgy * bgy + bgz * bgz;
    }

    public double distance2(HepRotation r) {
        final double db2 = norm2();
        final double dr2 = r.norm2();
        return db2 + dr2;
    }

    public double distance2(HepLorentzRotation lt) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        lt.decompose(b1, r1);
        final double db2 = distance2(b1);
        final double dr2 = r1.norm2();
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
        return distance2(b) <= epsilon * epsilon;
    }

    public boolean isNear(HepRotation r) {
        return isNear(r, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepRotation r, double epsilon) {
        final double db2 = norm2();
        if (db2 > epsilon * epsilon) return false;
        final double dr2 = r.norm2();
        return db2 + dr2 <= epsilon * epsilon;
    }

    public boolean isNear(HepLorentzRotation lt) {
        return isNear(lt, Hep4RotationInterface.tolerance);
    }

    /** As CLHEP: the distance to the identity boost, and true unless the summed distances are zero. */
    public boolean isNear(HepLorentzRotation lt, double epsilon) {
        final HepBoost b1 = new HepBoost();
        final HepRotation r1 = new HepRotation();
        final double db2 = distance2(b1);
        lt.decompose(b1, r1);
        if (db2 > epsilon * epsilon) return false;
        final double dr2 = r1.norm2();
        return (db2 + dr2) != 0;
    }

    public double norm2() {
        final double bgx = rep_.xt_;
        final double bgy = rep_.yt_;
        final double bgz = rep_.zt_;
        return bgx * bgx + bgy * bgy + bgz * bgz;
    }

    public void rectify() {
        final double gam = tt();
        if (gam <= 0) {
            ZMxpv.throwC(ZMxpv.TACHYONIC, "Attempt to rectify a boost with non-positive gamma.", "HepBoost::rectify");
            if (gam == 0) return;
        }
        final Hep3Vector boost = new Hep3Vector(xt(), yt(), zt());
        boost.divAssign(tt());
        if (boost.mag2() >= 1) {
            boost.divAssign(boost.mag() * (1.0 + 1.0e-16));
        }
        set(boost);
    }

    /** this * p. */
    public HepLorentzVector vectorMultiplication(HepLorentzVector p) {
        final double x = p.x();
        final double y = p.y();
        final double z = p.z();
        final double t = p.t();
        return new HepLorentzVector(rep_.xx_ * x + rep_.xy_ * y + rep_.xz_ * z + rep_.xt_ * t,
            rep_.xy_ * x + rep_.yy_ * y + rep_.yz_ * z + rep_.yt_ * t,
            rep_.xz_ * x + rep_.yz_ * y + rep_.zz_ * z + rep_.zt_ * t,
            rep_.xt_ * x + rep_.yt_ * y + rep_.zt_ * z + rep_.tt_ * t);
    }

    public HepLorentzVector apply(HepLorentzVector p) {
        return vectorMultiplication(p);
    }

    public HepLorentzVector times(HepLorentzVector p) {
        return vectorMultiplication(p);
    }

    public HepLorentzRotation matrixMultiplication(HepRep4x4 m1) {
        final HepRep4x4Symmetric r = rep4x4Symmetric();
        return new HepLorentzRotation(new HepRep4x4(
            r.xx_ * m1.xx_ + r.xy_ * m1.yx_ + r.xz_ * m1.zx_ + r.xt_ * m1.tx_,
            r.xx_ * m1.xy_ + r.xy_ * m1.yy_ + r.xz_ * m1.zy_ + r.xt_ * m1.ty_,
            r.xx_ * m1.xz_ + r.xy_ * m1.yz_ + r.xz_ * m1.zz_ + r.xt_ * m1.tz_,
            r.xx_ * m1.xt_ + r.xy_ * m1.yt_ + r.xz_ * m1.zt_ + r.xt_ * m1.tt_,
            r.xy_ * m1.xx_ + r.yy_ * m1.yx_ + r.yz_ * m1.zx_ + r.yt_ * m1.tx_,
            r.xy_ * m1.xy_ + r.yy_ * m1.yy_ + r.yz_ * m1.zy_ + r.yt_ * m1.ty_,
            r.xy_ * m1.xz_ + r.yy_ * m1.yz_ + r.yz_ * m1.zz_ + r.yt_ * m1.tz_,
            r.xy_ * m1.xt_ + r.yy_ * m1.yt_ + r.yz_ * m1.zt_ + r.yt_ * m1.tt_,
            r.xz_ * m1.xx_ + r.yz_ * m1.yx_ + r.zz_ * m1.zx_ + r.zt_ * m1.tx_,
            r.xz_ * m1.xy_ + r.yz_ * m1.yy_ + r.zz_ * m1.zy_ + r.zt_ * m1.ty_,
            r.xz_ * m1.xz_ + r.yz_ * m1.yz_ + r.zz_ * m1.zz_ + r.zt_ * m1.tz_,
            r.xz_ * m1.xt_ + r.yz_ * m1.yt_ + r.zz_ * m1.zt_ + r.zt_ * m1.tt_,
            r.xt_ * m1.xx_ + r.yt_ * m1.yx_ + r.zt_ * m1.zx_ + r.tt_ * m1.tx_,
            r.xt_ * m1.xy_ + r.yt_ * m1.yy_ + r.zt_ * m1.zy_ + r.tt_ * m1.ty_,
            r.xt_ * m1.xz_ + r.yt_ * m1.yz_ + r.zt_ * m1.zz_ + r.tt_ * m1.tz_,
            r.xt_ * m1.xt_ + r.yt_ * m1.yt_ + r.zt_ * m1.zt_ + r.tt_ * m1.tt_));
    }

    public HepLorentzRotation matrixMultiplication(HepRep4x4Symmetric m1) {
        final HepRep4x4Symmetric r = rep4x4Symmetric();
        return new HepLorentzRotation(new HepRep4x4(
            r.xx_ * m1.xx_ + r.xy_ * m1.xy_ + r.xz_ * m1.xz_ + r.xt_ * m1.xt_,
            r.xx_ * m1.xy_ + r.xy_ * m1.yy_ + r.xz_ * m1.yz_ + r.xt_ * m1.yt_,
            r.xx_ * m1.xz_ + r.xy_ * m1.yz_ + r.xz_ * m1.zz_ + r.xt_ * m1.zt_,
            r.xx_ * m1.xt_ + r.xy_ * m1.yt_ + r.xz_ * m1.zt_ + r.xt_ * m1.tt_,
            r.xy_ * m1.xx_ + r.yy_ * m1.xy_ + r.yz_ * m1.xz_ + r.yt_ * m1.xt_,
            r.xy_ * m1.xy_ + r.yy_ * m1.yy_ + r.yz_ * m1.yz_ + r.yt_ * m1.yt_,
            r.xy_ * m1.xz_ + r.yy_ * m1.yz_ + r.yz_ * m1.zz_ + r.yt_ * m1.zt_,
            r.xy_ * m1.xt_ + r.yy_ * m1.yt_ + r.yz_ * m1.zt_ + r.yt_ * m1.tt_,
            r.xz_ * m1.xx_ + r.yz_ * m1.xy_ + r.zz_ * m1.xz_ + r.zt_ * m1.xt_,
            r.xz_ * m1.xy_ + r.yz_ * m1.yy_ + r.zz_ * m1.yz_ + r.zt_ * m1.yt_,
            r.xz_ * m1.xz_ + r.yz_ * m1.yz_ + r.zz_ * m1.zz_ + r.zt_ * m1.zt_,
            r.xz_ * m1.xt_ + r.yz_ * m1.yt_ + r.zz_ * m1.zt_ + r.zt_ * m1.tt_,
            r.xt_ * m1.xx_ + r.yt_ * m1.xy_ + r.zt_ * m1.xz_ + r.tt_ * m1.xt_,
            r.xt_ * m1.xy_ + r.yt_ * m1.yy_ + r.zt_ * m1.yz_ + r.tt_ * m1.yt_,
            r.xt_ * m1.xz_ + r.yt_ * m1.yz_ + r.zt_ * m1.zz_ + r.tt_ * m1.zt_,
            r.xt_ * m1.xt_ + r.yt_ * m1.yt_ + r.zt_ * m1.zt_ + r.tt_ * m1.tt_));
    }

    public HepLorentzRotation times(HepLorentzRotation lt) {
        return matrixMultiplication(lt.rep4x4());
    }

    public HepLorentzRotation times(HepBoost b) {
        return matrixMultiplication(b.rep_);
    }

    public HepLorentzRotation times(HepRotation r) {
        return matrixMultiplication(r.rep4x4());
    }

    /** The boost the other way: xt, yt, zt negated. */
    public HepBoost inverse() {
        return new HepBoost(xx(), yx(), zx(), -tx(), yy(), zy(), -ty(), zz(), -tz(), tt());
    }

    public static HepBoost inverseOf(HepBoost lt) {
        return new HepBoost(lt.xx(), lt.yx(), lt.zx(), -lt.tx(), lt.yy(), lt.zy(), -lt.ty(), lt.zz(), -lt.tz(), lt.tt());
    }

    public HepBoost invert() {
        rep_.xt_ = -rep_.xt_;
        rep_.yt_ = -rep_.yt_;
        rep_.zt_ = -rep_.zt_;
        return this;
    }

    public static double getTolerance() {
        return Hep4RotationInterface.tolerance;
    }

    public static double setTolerance(double tol) {
        return Hep4RotationInterface.setTolerance(tol);
    }

    /** "Lorentz Boost( IDENTITY )", else the direction, beta and gamma. */
    public COStream print(COStream os) {
        if (rep_.tt_ <= 1) {
            os.put("Lorentz Boost( IDENTITY )");
        } else {
            final double norm = boostVector().mag();
            os.put("\nLorentz Boost ");
            boostVector().div(norm).print(os);
            os.put("\n{beta = ").put(beta()).put(" gamma = ").put(gamma()).put("}\n");
        }
        return os;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
