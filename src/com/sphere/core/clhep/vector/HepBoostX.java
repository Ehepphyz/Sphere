package com.sphere.core.clhep.vector;

/**
 * CLHEP's HepBoostX (Vector/BoostX.h, .icc, BoostX.cc): a Lorentz boost along the x axis, as its beta and gamma.
 * Products of two such boosts stay of this kind; with others they are HepLorentzRotations.
 */
public final class HepBoostX extends HepAxialBoost implements Comparable<HepBoostX> {

    /** The identity. */
    public HepBoostX() {
        super(0.0, 1.0);
    }

    /** A boost by beta; a speed of c or more is reported and thrown. */
    public HepBoostX(double bbeta) {
        super(0.0, 1.0);
        set(bbeta);
    }

    public HepBoostX(HepBoostX b) {
        super(b.beta_, b.gamma_);
    }

    HepBoostX(double bbeta, double ggamma) {
        super(bbeta, ggamma);
    }

    public HepBoostX copy() {
        return new HepBoostX(this);
    }

    public HepBoostX assign(HepBoostX b) {
        beta_ = b.beta_;
        gamma_ = b.gamma_;
        return this;
    }

    public HepBoostX set(double bbeta) {
        setBeta(bbeta, "X");
        return this;
    }

    @Override
    public Hep3Vector boostVector() {
        return new Hep3Vector(beta_, 0, 0);
    }

    @Override
    public Hep3Vector getDirection() {
        return new Hep3Vector(1.0, 0.0, 0.0);
    }

    @Override
    public double xx() {
        return gamma();
    }

    @Override
    public double xy() {
        return 0.0;
    }

    @Override
    public double xz() {
        return 0.0;
    }

    @Override
    public double xt() {
        return beta() * gamma();
    }

    @Override
    public double yx() {
        return 0.0;
    }

    @Override
    public double yy() {
        return 1.0;
    }

    @Override
    public double yz() {
        return 0.0;
    }

    @Override
    public double yt() {
        return 0.0;
    }

    @Override
    public double zx() {
        return 0.0;
    }

    @Override
    public double zy() {
        return 0.0;
    }

    @Override
    public double zz() {
        return 1.0;
    }

    @Override
    public double zt() {
        return 0.0;
    }

    @Override
    public double tx() {
        return beta() * gamma();
    }

    @Override
    public double ty() {
        return 0.0;
    }

    @Override
    public double tz() {
        return 0.0;
    }

    @Override
    public double tt() {
        return gamma();
    }

    @Override
    public HepLorentzVector col1() {
        return new HepLorentzVector(gamma(), 0, 0, beta() * gamma());
    }

    @Override
    public HepLorentzVector col2() {
        return new HepLorentzVector(0, 1, 0, 0);
    }

    @Override
    public HepLorentzVector col3() {
        return new HepLorentzVector(0, 0, 1, 0);
    }

    @Override
    public HepLorentzVector col4() {
        return new HepLorentzVector(beta() * gamma(), 0, 0, gamma());
    }

    @Override
    public HepRep4x4 rep4x4() {
        final double bg = beta_ * gamma_;
        return new HepRep4x4(gamma_, 0, 0, bg, 0, 1, 0, 0, 0, 0, 1, 0, bg, 0, 0, gamma_);
    }

    @Override
    public HepRep4x4Symmetric rep4x4Symmetric() {
        final double bg = beta_ * gamma_;
        return new HepRep4x4Symmetric(gamma_, 0, 0, bg, 1, 0, 0, 1, 0, gamma_);
    }

    @Override
    public HepLorentzVector times(HepLorentzVector p) {
        final double bg = beta_ * gamma_;
        return new HepLorentzVector(gamma_ * p.x() + bg * p.t(), p.y(), p.z(), gamma_ * p.t() + bg * p.x());
    }

    /** this * b: still a boost along the same axis, by the relativistic sum of the betas. */
    public HepBoostX times(HepBoostX b) {
        return new HepBoostX((beta() + b.beta()) / (1 + beta() * b.beta()));
    }

    @Override
    public double distance2(HepBoost b) {
        return b.distance2(this);
    }

    public double distance2(HepBoostX b) {
        return distance2Same(b);
    }

    public double howNear(HepBoostX b) {
        return Math.sqrt(distance2(b));
    }

    public boolean isNear(HepBoostX b) {
        return isNear(b, Hep4RotationInterface.tolerance);
    }

    public boolean isNear(HepBoostX b, double epsilon) {
        return distance2(b) <= epsilon * epsilon;
    }

    public HepBoostX inverse() {
        return new HepBoostX(-beta(), gamma());
    }

    public static HepBoostX inverseOf(HepBoostX b) {
        return new HepBoostX(-b.beta(), b.gamma());
    }

    public HepBoostX invert() {
        beta_ = -beta_;
        return this;
    }

    public int compare(HepBoostX b) {
        if (beta() < b.beta()) {
            return -1;
        } else if (beta() > b.beta()) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public int compareTo(HepBoostX b) {
        return compare(b);
    }

    /** operator==: the same beta. */
    public boolean isEqual(HepBoostX b) {
        return beta_ == b.beta_;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepBoostX b && isEqual(b);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(beta_);
    }

    @Override
    protected char axisName() {
        return 'X';
    }
}
