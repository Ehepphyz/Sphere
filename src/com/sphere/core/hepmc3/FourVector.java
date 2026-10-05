package com.sphere.core.hepmc3;

/**
 * A generic four-vector, momentum (px, py, pz, e) or position (x, y, z, t)
 * depending on the accessors used, as in HepMC3. Mutable like its C++
 * original: the vector a particle or vertex holds is the one its accessors
 * return, so changing it changes them (use {@link #copy()} to keep a value).
 */
public final class FourVector {

    private double v1;
    private double v2;
    private double v3;
    private double v4;

    public FourVector() {
    }

    public FourVector(double x, double y, double z, double t) {
        this.v1 = x;
        this.v2 = y;
        this.v3 = z;
        this.v4 = t;
    }

    public FourVector(FourVector o) {
        this(o.v1, o.v2, o.v3, o.v4);
    }

    /** A new (0,0,0,0): FourVector::ZERO_VECTOR(), fresh so that nobody changes a shared one. */
    public static FourVector zero() {
        return new FourVector();
    }

    public FourVector copy() {
        return new FourVector(this);
    }

    /* ---- components ----------------------------------------------------- */

    public void set(double x1, double x2, double x3, double x4) {
        v1 = x1;
        v2 = x2;
        v3 = x3;
        v4 = x4;
    }

    public void set(FourVector o) {
        set(o.v1, o.v2, o.v3, o.v4);
    }

    public void setComponent(int i, double x) {
        if (i == 0) v1 = x;
        else if (i == 1) v2 = x;
        else if (i == 2) v3 = x;
        else if (i == 3) v4 = x;
    }

    public double getComponent(int i) {
        return switch (i) {
            case 0 -> v1;
            case 1 -> v2;
            case 2 -> v3;
            case 3 -> v4;
            default -> 0.0;
        };
    }

    public double x() {
        return v1;
    }

    public void setX(double xx) {
        v1 = xx;
    }

    public double y() {
        return v2;
    }

    public void setY(double yy) {
        v2 = yy;
    }

    public double z() {
        return v3;
    }

    public void setZ(double zz) {
        v3 = zz;
    }

    public double t() {
        return v4;
    }

    public void setT(double tt) {
        v4 = tt;
    }

    public double px() {
        return v1;
    }

    public void setPx(double v) {
        v1 = v;
    }

    public double py() {
        return v2;
    }

    public void setPy(double v) {
        v2 = v;
    }

    public double pz() {
        return v3;
    }

    public void setPz(double v) {
        v3 = v;
    }

    public double e() {
        return v4;
    }

    public void setE(double v) {
        v4 = v;
    }

    /* ---- computed properties --------------------------------------------- */

    public double length2() {
        return x() * x() + y() * y() + z() * z();
    }

    public double length() {
        return Math.sqrt(length2());
    }

    /** HepMC2's name for length(). */
    public double rho() {
        return length();
    }

    public double perp2() {
        return x() * x() + y() * y();
    }

    public double perp() {
        return Math.sqrt(perp2());
    }

    /** t^2 - x^2 - y^2 - z^2. */
    public double interval() {
        return t() * t() - length2();
    }

    public double p3mod2() {
        return length2();
    }

    public double p3mod() {
        return length();
    }

    public double pt2() {
        return perp2();
    }

    public double pt() {
        return perp();
    }

    public double m2() {
        return interval();
    }

    /** The invariant mass; -sqrt(-m2) when m2 is negative. */
    public double m() {
        return (m2() > 0.0) ? Math.sqrt(m2()) : -Math.sqrt(-m2());
    }

    public double phi() {
        return Math.atan2(y(), x());
    }

    public double theta() {
        return Math.atan2(perp(), z());
    }

    public double eta() {
        if (p3mod() == 0.0) return 0.0;
        if (p3mod() == Math.abs(pz())) return Math.copySign(Double.POSITIVE_INFINITY, pz());
        return 0.5 * Math.log((p3mod() + pz()) / (p3mod() - pz()));
    }

    public double rap() {
        if (e() == 0.0) return 0.0;
        if (e() == Math.abs(pz())) return Math.copySign(Double.POSITIVE_INFINITY, pz());
        return 0.5 * Math.log((e() + pz()) / (e() - pz()));
    }

    public double absEta() {
        return Math.abs(eta());
    }

    public double absRap() {
        return Math.abs(rap());
    }

    /** Deprecated in HepMC3: eta(). */
    public double pseudoRapidity() {
        return eta();
    }

    /* ---- comparisons --------------------------------------------------- */

    public boolean isZero() {
        return x() == 0 && y() == 0 && z() == 0 && t() == 0;
    }

    /** Signed azimuthal separation in [-pi, pi]. */
    public double deltaPhi(FourVector v) {
        double dphi = phi() - v.phi();
        if (dphi != dphi) return dphi;
        while (dphi >= Math.PI) dphi -= 2. * Math.PI;
        while (dphi < -Math.PI) dphi += 2. * Math.PI;
        return dphi;
    }

    public double deltaEta(FourVector v) {
        return eta() - v.eta();
    }

    public double deltaRap(FourVector v) {
        return rap() - v.rap();
    }

    public double deltaR2Eta(FourVector v) {
        return deltaPhi(v) * deltaPhi(v) + deltaEta(v) * deltaEta(v);
    }

    public double deltaREta(FourVector v) {
        return Math.sqrt(deltaR2Eta(v));
    }

    public double deltaR2Rap(FourVector v) {
        return deltaPhi(v) * deltaPhi(v) + deltaRap(v) * deltaRap(v);
    }

    public double deltaRRap(FourVector v) {
        return Math.sqrt(deltaR2Rap(v));
    }

    /* ---- arithmetic ---------------------------------------------------- */

    public FourVector plus(FourVector r) {
        return new FourVector(x() + r.x(), y() + r.y(), z() + r.z(), t() + r.t());
    }

    public FourVector minus(FourVector r) {
        return new FourVector(x() - r.x(), y() - r.y(), z() - r.z(), t() - r.t());
    }

    public FourVector times(double r) {
        return new FourVector(x() * r, y() * r, z() * r, t() * r);
    }

    public FourVector dividedBy(double r) {
        return new FourVector(x() / r, y() / r, z() / r, t() / r);
    }

    /** operator+= */
    public void add(FourVector r) {
        setX(x() + r.x());
        setY(y() + r.y());
        setZ(z() + r.z());
        setT(t() + r.t());
    }

    /** operator-= */
    public void subtract(FourVector r) {
        setX(x() - r.x());
        setY(y() - r.y());
        setZ(z() - r.z());
        setT(t() - r.t());
    }

    /** operator*= */
    public void multiplyBy(double r) {
        setX(x() * r);
        setY(y() * r);
        setZ(z() * r);
        setT(t() * r);
    }

    /** operator/= */
    public void divideBy(double r) {
        setX(x() / r);
        setY(y() / r);
        setZ(z() / r);
        setT(t() / r);
    }

    /** operator==: every component equal (so a NaN never is). */
    @Override
    public boolean equals(Object o) {
        return o instanceof FourVector r && x() == r.x() && y() == r.y() && z() == r.z() && t() == r.t();
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(v1, v2, v3, v4);
    }

    /** Print::line(FourVector): "FourVector:  (P,E)=+1.00e+00,..." */
    @Override
    public String toString() {
        return Print.line(this);
    }

    /* ---- the unbound comparison functions of FourVector.h ---------------- */

    public static double deltaPhi(FourVector a, FourVector b) {
        return b.deltaPhi(a);
    }

    public static double deltaEta(FourVector a, FourVector b) {
        return b.deltaEta(a);
    }

    public static double deltaRap(FourVector a, FourVector b) {
        return b.deltaRap(a);
    }

    public static double deltaR2Eta(FourVector a, FourVector b) {
        return b.deltaR2Eta(a);
    }

    public static double deltaREta(FourVector a, FourVector b) {
        return b.deltaREta(a);
    }

    public static double deltaR2Rap(FourVector a, FourVector b) {
        return b.deltaR2Rap(a);
    }

    public static double deltaRRap(FourVector a, FourVector b) {
        return b.deltaRRap(a);
    }
}
