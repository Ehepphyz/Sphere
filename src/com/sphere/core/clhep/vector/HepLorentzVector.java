package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.log;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's HepLorentzVector (Vector/LorentzVector.h, .icc, LorentzVector*.cc): a four-vector, a
 * Hep3Vector and a time (or energy) component, with the metric (+,-,-,-) by default (TimeNegative
 * flips it). Boosts, rotations, rapidities, invariant masses and the nearness tests of ZOOM.
 *
 * <p>{@code a + b} is {@link #plus(HepLorentzVector)}, {@code a * b} the {@link #dot} product,
 * {@code a * c} {@link #times(double)}, {@code a / c} {@link #div}. The light-cone {@code plus()} and
 * {@code minus()} keep their C++ names.
 */
public class HepLorentzVector implements Comparable<HepLorentzVector> {

    public static final int X = 0, Y = 1, Z = 2, T = 3, NUM_COORDINATES = 4, SIZE = NUM_COORDINATES;

    /** ZMpvMetric_t. */
    public enum ZMpvMetric_t { TimePositive, TimeNegative }

    /** CLHEP's Tcomponent: a value meant as the time component. */
    public record Tcomponent(double t) {
    }

    private static double tolerance = Hep3Vector.ToleranceTicks * 2.22045e-16;
    private static double metric = 1.0;

    public static final HepLorentzVector X_HAT4 = new HepLorentzVector(1, 0, 0, 0);
    public static final HepLorentzVector Y_HAT4 = new HepLorentzVector(0, 1, 0, 0);
    public static final HepLorentzVector Z_HAT4 = new HepLorentzVector(0, 0, 1, 0);
    public static final HepLorentzVector T_HAT4 = new HepLorentzVector(0, 0, 0, 1);

    private final Hep3Vector pp;
    private double ee;

    public HepLorentzVector(double x1, double y1, double z1, double t1) {
        pp = new Hep3Vector(x1, y1, z1);
        ee = t1;
    }

    public HepLorentzVector(double x1, double y1, double z1) {
        pp = new Hep3Vector(x1, y1, z1);
    }

    /** Only the time component (explicit in C++). */
    public HepLorentzVector(double t1) {
        pp = new Hep3Vector(0, 0, 0);
        ee = t1;
    }

    public HepLorentzVector() {
        pp = new Hep3Vector(0, 0, 0);
    }

    public HepLorentzVector(Hep3Vector p, double e1) {
        pp = new Hep3Vector(p);
        ee = e1;
    }

    public HepLorentzVector(double e1, Hep3Vector p) {
        pp = new Hep3Vector(p);
        ee = e1;
    }

    public HepLorentzVector(HepLorentzVector p) {
        pp = new Hep3Vector(p.x(), p.y(), p.z());
        ee = p.t();
    }

    public HepLorentzVector(double x1, double y1, double z1, Tcomponent t1) {
        pp = new Hep3Vector(x1, y1, z1);
        ee = t1.t();
    }

    public HepLorentzVector(Tcomponent t1, double x1, double y1, double z1) {
        pp = new Hep3Vector(x1, y1, z1);
        ee = t1.t();
    }

    public HepLorentzVector(Tcomponent t1) {
        pp = new Hep3Vector(0, 0, 0);
        ee = t1.t();
    }

    /** The space part, time 0 (explicit in C++). */
    public HepLorentzVector(Hep3Vector v1) {
        pp = new Hep3Vector(v1);
        ee = 0;
    }

    public HepLorentzVector copy() {
        return new HepLorentzVector(this);
    }

    /** operator=. */
    public HepLorentzVector assign(HepLorentzVector q) {
        pp.assign(q.vect());
        ee = q.t();
        return this;
    }

    /** operator=(Hep3Vector): the space part, time 0. */
    public HepLorentzVector assign(Hep3Vector v1) {
        pp.assign(v1);
        ee = 0;
        return this;
    }

    /** operator Hep3Vector&amp;: the space part itself, changed by changes made to it. */
    public Hep3Vector spaceRef() {
        return pp;
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    public double x() {
        return pp.x();
    }

    public double y() {
        return pp.y();
    }

    public double z() {
        return pp.z();
    }

    public double t() {
        return ee;
    }

    public void setX(double a) {
        pp.setX(a);
    }

    public void setY(double a) {
        pp.setY(a);
    }

    public void setZ(double a) {
        pp.setZ(a);
    }

    public void setT(double a) {
        ee = a;
    }

    public double px() {
        return pp.x();
    }

    public double py() {
        return pp.y();
    }

    public double pz() {
        return pp.z();
    }

    public double e() {
        return ee;
    }

    public void setPx(double a) {
        pp.setX(a);
    }

    public void setPy(double a) {
        pp.setY(a);
    }

    public void setPz(double a) {
        pp.setZ(a);
    }

    public void setE(double a) {
        ee = a;
    }

    public Hep3Vector vect() {
        return new Hep3Vector(pp);
    }

    public void setVect(Hep3Vector p) {
        pp.assign(p);
    }

    public double getX() {
        return pp.x();
    }

    public double getY() {
        return pp.y();
    }

    public double getZ() {
        return pp.z();
    }

    public double getT() {
        return ee;
    }

    public Hep3Vector getV() {
        return new Hep3Vector(pp);
    }

    public Hep3Vector v() {
        return new Hep3Vector(pp);
    }

    public void setV(Hep3Vector v1) {
        pp.assign(v1);
    }

    public void setV(double x1, double y1, double z1) {
        pp.set(x1, y1, z1);
    }

    public void set(double x1, double y1, double z1, double t1) {
        set(x1, y1, z1, new Tcomponent(t1));
    }

    public void set(double x1, double y1, double z1, Tcomponent t1) {
        pp.set(x1, y1, z1);
        ee = t1.t();
    }

    public void set(Tcomponent t1, double x1, double y1, double z1) {
        pp.set(x1, y1, z1);
        ee = t1.t();
    }

    public void set(Tcomponent t1) {
        pp.set(0, 0, 0);
        ee = t1.t();
    }

    public void set(double t1) {
        pp.set(0, 0, 0);
        ee = t1;
    }

    public void set(Hep3Vector v1) {
        pp.assign(v1);
        ee = 0;
    }

    public void set(Hep3Vector v1, double t1) {
        pp.assign(v1);
        ee = t1;
    }

    public void set(double t1, Hep3Vector v1) {
        pp.assign(v1);
        ee = t1;
    }

    /** operator() and operator[]: 0, 1, 2 the space components, 3 the time; another index is said on cerr. */
    public double get(int i) {
        switch (i) {
            case X, Y, Z -> {
                return pp.get(i);
            }
            case T -> {
                return e();
            }
            default -> StdStreams.cerr().print("HepLorentzVector subscripting: bad index (" + i + ")\n");
        }
        return 0.;
    }

    public void set(int i, double v) {
        switch (i) {
            case X, Y, Z -> pp.set(i, v);
            case T -> ee = v;
            default -> StdStreams.cerr().print("HepLorentzVector subscripting: bad index (" + i + ")\n");
        }
    }

    public double theta() {
        return pp.theta();
    }

    public double cosTheta() {
        return pp.cosTheta();
    }

    public double phi() {
        return pp.phi();
    }

    public double rho() {
        return pp.mag();
    }

    public void setTheta(double a) {
        pp.setTheta(a);
    }

    public void setPhi(double a) {
        pp.setPhi(a);
    }

    public void setRho(double a) {
        pp.setMag(a);
    }

    public void setRThetaPhi(double r, double ttheta, double phi1) {
        pp.setRThetaPhi(r, ttheta, phi1);
    }

    public void setREtaPhi(double r, double eta1, double phi1) {
        pp.setREtaPhi(r, eta1, phi1);
    }

    public void setRhoPhiZ(double rho1, double phi1, double z1) {
        pp.setRhoPhiZ(rho1, phi1, z1);
    }

    // ------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------

    public HepLorentzVector plus(HepLorentzVector q) {
        return new HepLorentzVector(x() + q.x(), y() + q.y(), z() + q.z(), t() + q.t());
    }

    public HepLorentzVector minus(HepLorentzVector q) {
        return new HepLorentzVector(x() - q.x(), y() - q.y(), z() - q.z(), t() - q.t());
    }

    public HepLorentzVector plusAssign(HepLorentzVector q) {
        pp.plusAssign(q.vect());
        ee += q.t();
        return this;
    }

    public HepLorentzVector minusAssign(HepLorentzVector q) {
        pp.minusAssign(q.vect());
        ee -= q.t();
        return this;
    }

    public HepLorentzVector negate() {
        return new HepLorentzVector(-x(), -y(), -z(), -t());
    }

    public HepLorentzVector timesAssign(double a) {
        pp.timesAssign(a);
        ee *= a;
        return this;
    }

    public HepLorentzVector divAssign(double c) {
        if (c == 0) {
            ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "Attempt to do LorentzVector /= 0 -- \n"
                + "division by zero would produce infinite or NAN components", "HepLorentzVector::operator/=");
        }
        final double oneOverC = 1.0 / c;
        pp.timesAssign(oneOverC);
        ee *= oneOverC;
        return this;
    }

    /** p * a and a * p. */
    public HepLorentzVector times(double a) {
        return new HepLorentzVector(a * x(), a * y(), a * z(), a * t());
    }

    public HepLorentzVector div(double c) {
        if (c == 0) {
            ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "Attempt to do LorentzVector / 0 -- \n"
                + "division by zero would produce infinite or NAN components", "operator/(HepLorentzVector, double)");
        }
        final double oneOverC = 1.0 / c;
        return new HepLorentzVector(getV().times(oneOverC), getT() * oneOverC);
    }

    public boolean isEqual(HepLorentzVector q) {
        return vect().isEqual(q.vect()) && t() == q.t();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HepLorentzVector q && isEqual(q);
    }

    @Override
    public int hashCode() {
        return pp.hashCode() * 31 + Double.hashCode(ee);
    }

    // ------------------------------------------------------------------
    // Invariants and kinematics
    // ------------------------------------------------------------------

    public double perp2() {
        return pp.perp2();
    }

    public double perp() {
        return pp.perp();
    }

    public void setPerp(double a) {
        pp.setPerp(a);
    }

    public double perp2(Hep3Vector v1) {
        return pp.perp2(v1);
    }

    public double perp(Hep3Vector v1) {
        return pp.perp(v1);
    }

    public double angle(Hep3Vector v1) {
        return pp.angle(v1);
    }

    /** The square of the invariant length, with the metric's sign. */
    public double mag2() {
        return metric * (t() * t() - pp.mag2());
    }

    public double mag() {
        final double mmm = m2();
        return mmm < 0.0 ? -sqrt(-mmm) : sqrt(mmm);
    }

    public double m2() {
        return t() * t() - pp.mag2();
    }

    public double m() {
        return mag();
    }

    public double mt2() {
        return e() * e() - pz() * pz();
    }

    public double mt() {
        final double mmm = mt2();
        return mmm < 0.0 ? -sqrt(-mmm) : sqrt(mmm);
    }

    public double et2() {
        final double pt2 = pp.perp2();
        return pt2 == 0 ? 0 : e() * e() * pt2 / (pt2 + z() * z());
    }

    public double et() {
        final double etet = et2();
        return e() < 0.0 ? -sqrt(etet) : sqrt(etet);
    }

    public double et2(Hep3Vector v1) {
        final double pt2 = pp.perp2(v1);
        final double pv = pp.dot(v1.unit());
        return pt2 == 0 ? 0 : e() * e() * pt2 / (pt2 + pv * pv);
    }

    public double et(Hep3Vector v1) {
        final double etet = et2(v1);
        return e() < 0.0 ? -sqrt(etet) : sqrt(etet);
    }

    public void setVectMag(Hep3Vector spatial, double magnitude) {
        setVect(spatial);
        setT(sqrt(magnitude * magnitude + spatial.dot(spatial)));
    }

    public void setVectM(Hep3Vector spatial, double mass) {
        setVectMag(spatial, mass);
    }

    public double dot(HepLorentzVector q) {
        return metric * (t() * q.t() - z() * q.z() - y() * q.y() - x() * q.x());
    }

    /** The light-cone component t + z. */
    public double plus() {
        return t() + z();
    }

    /** The light-cone component t - z. */
    public double minus() {
        return t() - z();
    }

    public double plus(Hep3Vector ref) {
        final double r = ref.mag();
        if (r == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "A zero vector used as reference to LorentzVector plus-part",
                "HepLorentzVector::plus");
        }
        return ee + pp.dot(ref) / r;
    }

    public double minus(Hep3Vector ref) {
        final double r = ref.mag();
        if (r == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "A zero vector used as reference to LorentzVector minus-part",
                "HepLorentzVector::minus");
        }
        return ee - pp.dot(ref) / r;
    }

    public double invariantMass2(HepLorentzVector w) {
        return plus(w).m2();
    }

    public double invariantMass(HepLorentzVector w) {
        final double m1 = invariantMass2(w);
        if (m1 < 0) {
            if (ee * w.ee < 0) {
                ZMxpv.throwA(ZMxpv.NEGATIVE_MASS, "invariant mass meaningless: \n"
                    + "a negative-mass input led to spacelike 4-vector sum", "HepLorentzVector::invariantMass");
            } else if ((isSpacelike() && !isLightlike()) || (w.isSpacelike() && !w.isLightlike())) {
                ZMxpv.throwA(ZMxpv.SPACELIKE, "invariant mass meaningless because of spacelike input",
                    "HepLorentzVector::invariantMass");
            }
            return 0;
        }
        return (ee + w.ee >= 0) ? sqrt(m1) : -sqrt(m1);
    }

    public double euclideanNorm2() {
        return ee * ee + pp.mag2();
    }

    public double euclideanNorm() {
        return sqrt(euclideanNorm2());
    }

    public double restMass2() {
        return m2();
    }

    public double invariantMass2() {
        return m2();
    }

    public double restMass() {
        if (t() < 0.0) {
            ZMxpv.throwC(ZMxpv.NEGATIVE_MASS, "E^2-p^2 < 0 for this particle. Magnitude returned.",
                "HepLorentzVector::restMass");
        }
        return t() < 0.0 ? -m() : m();
    }

    public double invariantMass() {
        if (t() < 0.0) {
            ZMxpv.throwC(ZMxpv.NEGATIVE_MASS, "E^2-p^2 < 0 for this particle. Magnitude returned.",
                "HepLorentzVector::invariantMass");
        }
        return t() < 0.0 ? -m() : m();
    }

    public HepLorentzVector rest4Vector() {
        return new HepLorentzVector(0, 0, 0, (t() < 0.0 ? -m() : m()));
    }

    public boolean isTimelike() {
        return restMass2() > 0;
    }

    public boolean isSpacelike() {
        return restMass2() < 0;
    }

    public boolean isLightlike() {
        return isLightlike(tolerance);
    }

    public boolean isLightlike(double epsilon) {
        return fabs(restMass2()) < 2.0 * epsilon * ee * ee;
    }

    public double howLightlike() {
        final double m1 = fabs(restMass2());
        final double twoT2 = 2 * ee * ee;
        if (m1 < twoT2) {
            return m1 / twoT2;
        } else {
            return 1;
        }
    }

    public double diff2(HepLorentzVector w) {
        return metric * ((ee - w.ee) * (ee - w.ee) - pp.minus(w.pp).mag2());
    }

    public double delta2Euclidean(HepLorentzVector w) {
        return (ee - w.ee) * (ee - w.ee) + pp.minus(w.pp).mag2();
    }

    public Hep3Vector boostVector() {
        if (ee == 0) {
            if (pp.mag2() == 0) {
                return new Hep3Vector(0, 0, 0);
            } else {
                ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "boostVector computed for LorentzVector with t=0 -- infinite result",
                    "HepLorentzVector::boostVector");
            }
        }
        if (restMass2() <= 0) {
            ZMxpv.throwC(ZMxpv.TACHYONIC, "boostVector computed for a non-timelike LorentzVector ",
                "HepLorentzVector::boostVector");
        }
        return pp.times(1. / ee);
    }

    public double beta() {
        if (ee == 0) {
            if (pp.mag2() == 0) {
                return 0;
            } else {
                ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "beta computed for HepLorentzVector with t=0 -- infinite result",
                    "HepLorentzVector::beta");
            }
        }
        if (restMass2() <= 0) {
            ZMxpv.throwC(ZMxpv.TACHYONIC, "beta computed for a non-timelike HepLorentzVector", "HepLorentzVector::beta");
        }
        return sqrt(pp.mag2() / (ee * ee));
    }

    public double gamma() {
        final double v2 = pp.mag2();
        final double t2 = ee * ee;
        if (ee == 0) {
            if (pp.mag2() == 0) {
                return 1;
            } else {
                ZMxpv.throwC(ZMxpv.INFINITE_VECTOR, "gamma computed for HepLorentzVector with t=0 -- zero result",
                    "HepLorentzVector::gamma");
                return 0;
            }
        }
        if (t2 < v2) {
            ZMxpv.throwA(ZMxpv.SPACELIKE, "gamma computed for a spacelike HepLorentzVector -- imaginary result",
                "HepLorentzVector::gamma");
        } else if (t2 == v2) {
            ZMxpv.throwA(ZMxpv.INFINITY, "gamma computed for a lightlike HepLorentzVector -- infinite result",
                "HepLorentzVector::gamma");
        }
        return 1. / sqrt(1. - v2 / t2);
    }

    public double rapidity() {
        final double z1 = pp.getZ();
        if (fabs(ee) == fabs(z1)) {
            ZMxpv.throwA(ZMxpv.INFINITY, "rapidity for 4-vector with |E| = |Pz| -- infinite result",
                "HepLorentzVector::rapidity");
        }
        if (fabs(ee) < fabs(z1)) {
            ZMxpv.throwA(ZMxpv.SPACELIKE, "rapidity for spacelike 4-vector with |E| < |Pz| -- undefined",
                "HepLorentzVector::rapidity");
        }
        final double q = (ee + z1) / (ee - z1);
        return .5 * log(q);
    }

    public double rapidity(Hep3Vector ref) {
        final double r = ref.mag2();
        if (r == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "A zero vector used as reference to LorentzVector rapidity",
                "HepLorentzVector::rapidity");
        }
        final double vdotu = pp.dot(ref) / sqrt(r);
        if (fabs(ee) == fabs(vdotu)) {
            ZMxpv.throwA(ZMxpv.INFINITY, "rapidity for 4-vector with |E| = |Pu| -- infinite result",
                "HepLorentzVector::rapidity");
        }
        if (fabs(ee) < fabs(vdotu)) {
            ZMxpv.throwA(ZMxpv.SPACELIKE, "rapidity for spacelike 4-vector with |E| < |P*ref| -- undefined ",
                "HepLorentzVector::rapidity");
        }
        final double q = (ee + vdotu) / (ee - vdotu);
        return .5 * log(q);
    }

    public double coLinearRapidity() {
        final double v1 = pp.mag();
        if (fabs(ee) == fabs(v1)) {
            ZMxpv.throwA(ZMxpv.INFINITY, "co-Linear rapidity for 4-vector with |E| = |P| -- infinite result",
                "HepLorentzVector::coLinearRapidity");
        }
        if (fabs(ee) < fabs(v1)) {
            ZMxpv.throwA(ZMxpv.SPACELIKE, "co-linear rapidity for spacelike 4-vector -- undefined",
                "HepLorentzVector::coLinearRapidity");
        }
        final double q = (ee + v1) / (ee - v1);
        return .5 * log(q);
    }

    public double pseudoRapidity() {
        return pp.pseudoRapidity();
    }

    public double eta() {
        return pp.pseudoRapidity();
    }

    public double eta(Hep3Vector ref) {
        return pp.eta(ref);
    }

    public Hep3Vector findBoostToCM() {
        return boostVector().negate();
    }

    public Hep3Vector findBoostToCM(HepLorentzVector w) {
        final double t1 = ee + w.ee;
        final Hep3Vector v1 = pp.plus(w.pp);
        if (t1 == 0) {
            if (v1.mag2() == 0) {
                return new Hep3Vector(0, 0, 0);
            } else {
                ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "boostToCM computed for two 4-vectors with combined t=0 -- "
                    + "infinite result", "HepLorentzVector::findBoostToCM");
            }
        }
        if (t1 * t1 - v1.mag2() <= 0) {
            ZMxpv.throwC(ZMxpv.TACHYONIC, "boostToCM  computed for pair of HepLorentzVectors with non-timelike sum",
                "HepLorentzVector::findBoostToCM");
        }
        return new Hep3Vector(v1.times(-1. / t1));
    }

    public double deltaR(HepLorentzVector w) {
        final double a = eta() - w.eta();
        final double b = pp.deltaPhi(w.getV());
        return sqrt(a * a + b * b);
    }

    // ------------------------------------------------------------------
    // Comparisons
    // ------------------------------------------------------------------

    /** t first, then the space part. */
    public int compare(HepLorentzVector w) {
        if (ee > w.ee) {
            return 1;
        } else if (ee < w.ee) {
            return -1;
        } else {
            return pp.compare(w.pp);
        }
    }

    @Override
    public int compareTo(HepLorentzVector w) {
        return compare(w);
    }

    public boolean greaterThan(HepLorentzVector w) {
        return compare(w) > 0;
    }

    public boolean lessThan(HepLorentzVector w) {
        return compare(w) < 0;
    }

    public boolean greaterOrEqual(HepLorentzVector w) {
        return compare(w) >= 0;
    }

    public boolean lessOrEqual(HepLorentzVector w) {
        return compare(w) <= 0;
    }

    public boolean isNear(HepLorentzVector w) {
        return isNear(w, tolerance);
    }

    public boolean isNear(HepLorentzVector w, double epsilon) {
        double limit = fabs(pp.dot(w.pp));
        limit += .25 * ((ee + w.ee) * (ee + w.ee));
        limit *= epsilon * epsilon;
        double delta = pp.minus(w.pp).mag2();
        delta += (ee - w.ee) * (ee - w.ee);
        return delta <= limit;
    }

    public double howNear(HepLorentzVector w) {
        final double wdw = fabs(pp.dot(w.pp)) + .25 * ((ee + w.ee) * (ee + w.ee));
        final double delta = pp.minus(w.pp).mag2() + (ee - w.ee) * (ee - w.ee);
        if ((wdw > 0) && (delta < wdw)) {
            return sqrt(delta / wdw);
        } else if ((wdw == 0) && (delta == 0)) {
            return 0;
        } else {
            return 1;
        }
    }

    public boolean isNearCM(HepLorentzVector w) {
        return isNearCM(w, tolerance);
    }

    public boolean isNearCM(HepLorentzVector w, double epsilon) {
        final double tTotal = (ee + w.ee);
        final Hep3Vector vTotal = pp.plus(w.pp);
        final double vTotal2 = vTotal.mag2();
        if (vTotal2 >= tTotal * tTotal) {
            return isEqual(w);
        }
        if (vTotal2 == 0) {   // no boost needed
            return isNear(w, epsilon);
        }
        final double tRecip = 1. / tTotal;
        final Hep3Vector bboost = vTotal.times(-tRecip);
        final double b2 = vTotal2 * tRecip * tRecip;
        final double ggamma = sqrt(1. / (1. - b2));
        final double boostDotV1 = bboost.dot(pp);
        final double gm1_b2 = (ggamma - 1) / b2;
        final HepLorentzVector w1 = new HepLorentzVector(pp.plus(bboost.times((gm1_b2) * boostDotV1 + ggamma * ee)),
            ggamma * (ee + boostDotV1));
        final double boostDotV2 = bboost.dot(w.pp);
        final HepLorentzVector w2 = new HepLorentzVector(w.pp.plus(bboost.times((gm1_b2) * boostDotV2 + ggamma * w.ee)),
            ggamma * (w.ee + boostDotV2));
        return w1.isNear(w2, epsilon);
    }

    public double howNearCM(HepLorentzVector w) {
        final double tTotal = (ee + w.ee);
        final Hep3Vector vTotal = pp.plus(w.pp);
        final double vTotal2 = vTotal.mag2();
        if (vTotal2 >= tTotal * tTotal) {
            return isEqual(w) ? 0 : 1;
        }
        if (vTotal2 == 0) {
            return howNear(w);
        }
        final double tRecip = 1. / tTotal;
        final Hep3Vector bboost = vTotal.times(-tRecip);
        final double b2 = vTotal2 * tRecip * tRecip;
        if (b2 >= 1) {
            ZMxpv.throwC(ZMxpv.TACHYONIC, "boost vector in howNearCM appears to be tachyonic",
                "HepLorentzVector::howNearCM");
        }
        final double ggamma = sqrt(1. / (1. - b2));
        final double boostDotV1 = bboost.dot(pp);
        final double gm1_b2 = (ggamma - 1) / b2;
        final HepLorentzVector w1 = new HepLorentzVector(pp.plus(bboost.times((gm1_b2) * boostDotV1 + ggamma * ee)),
            ggamma * (ee + boostDotV1));
        final double boostDotV2 = bboost.dot(w.pp);
        final HepLorentzVector w2 = new HepLorentzVector(w.pp.plus(bboost.times((gm1_b2) * boostDotV2 + ggamma * w.ee)),
            ggamma * (w.ee + boostDotV2));
        return w1.howNear(w2);
    }

    public boolean isParallel(HepLorentzVector w) {
        return isParallel(w, tolerance);
    }

    public boolean isParallel(HepLorentzVector w, double epsilon) {
        final double norm = euclideanNorm();
        final double wnorm = w.euclideanNorm();
        if (norm == 0) {
            return wnorm == 0;
        }
        if (wnorm == 0) {
            return false;
        }
        final HepLorentzVector w1 = div(norm);
        final HepLorentzVector w2 = w.div(wnorm);
        return w1.minus(w2).euclideanNorm2() <= epsilon * epsilon;
    }

    public double howParallel(HepLorentzVector w) {
        final double norm = euclideanNorm();
        final double wnorm = w.euclideanNorm();
        if (norm == 0) {
            return wnorm == 0 ? 0 : 1;
        }
        if (wnorm == 0) {
            return 1;
        }
        final HepLorentzVector w1 = div(norm);
        final HepLorentzVector w2 = w.div(wnorm);
        final double x1 = w1.minus(w2).euclideanNorm();
        return (x1 < 1) ? x1 : 1;
    }

    public static double getTolerance() {
        return tolerance;
    }

    public static double setTolerance(double tol) {
        final double old = tolerance;
        tolerance = tol;
        return old;
    }

    public static ZMpvMetric_t setMetric(ZMpvMetric_t a1) {
        final ZMpvMetric_t oldMetric = (metric > 0) ? ZMpvMetric_t.TimePositive : ZMpvMetric_t.TimeNegative;
        metric = a1 == ZMpvMetric_t.TimeNegative ? -1.0 : 1.0;
        return oldMetric;
    }

    public static ZMpvMetric_t getMetric() {
        return (metric > 0) ? ZMpvMetric_t.TimePositive : ZMpvMetric_t.TimeNegative;
    }

    // ------------------------------------------------------------------
    // Boosts and rotations
    // ------------------------------------------------------------------

    public HepLorentzVector boost(double bx, double by, double bz) {
        final double b2 = bx * bx + by * by + bz * bz;
        final double ggamma = 1.0 / sqrt(1.0 - b2);
        final double bp = bx * x() + by * y() + bz * z();
        final double gamma2 = b2 > 0 ? (ggamma - 1.0) / b2 : 0.0;
        setX(x() + gamma2 * bp * bx + ggamma * bx * t());
        setY(y() + gamma2 * bp * by + ggamma * by * t());
        setZ(z() + gamma2 * bp * bz + ggamma * bz * t());
        setT(ggamma * (t() + bp));
        return this;
    }

    public HepLorentzVector boost(Hep3Vector b) {
        return boost(b.x(), b.y(), b.z());
    }

    public HepLorentzVector boost(Hep3Vector aaxis, double bbeta) {
        if (bbeta == 0) {
            return this;   // nothing for a zero boost
        }
        final double r2 = aaxis.mag2();
        if (r2 == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "A zero vector used as axis defining a boost -- no boost done",
                "HepLorentzVector::boost");
        }
        final double b2 = bbeta * bbeta;
        if (b2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "LorentzVector boosted with beta >= 1 (speed of light) -- \n"
                + "no boost done", "HepLorentzVector::boost");
        } else {
            final Hep3Vector u = aaxis.unit();
            final double ggamma = sqrt(1. / (1. - b2));
            final double betaDotV = u.dot(pp) * bbeta;
            final double tt = ee;
            ee = ggamma * (tt + betaDotV);
            pp.plusAssign(u.times(((ggamma - 1) / b2) * betaDotV * bbeta + ggamma * bbeta * tt));
        }
        return this;
    }

    public HepLorentzVector boostX(double bbeta) {
        final double b2 = bbeta * bbeta;
        if (b2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "boost along X with beta >= 1 (speed of light) -- no boost done",
                "HepLorentzVector::boostX");
        } else {
            final double ggamma = sqrt(1. / (1 - b2));
            final double tt = ee;
            ee = ggamma * (ee + bbeta * pp.getX());
            pp.setX(ggamma * (pp.getX() + bbeta * tt));
        }
        return this;
    }

    public HepLorentzVector boostY(double bbeta) {
        final double b2 = bbeta * bbeta;
        if (b2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "boost along Y with beta >= 1 (speed of light) -- \nno boost done",
                "HepLorentzVector::boostY");
        } else {
            final double ggamma = sqrt(1. / (1 - b2));
            final double tt = ee;
            ee = ggamma * (ee + bbeta * pp.getY());
            pp.setY(ggamma * (pp.getY() + bbeta * tt));
        }
        return this;
    }

    public HepLorentzVector boostZ(double bbeta) {
        final double b2 = bbeta * bbeta;
        if (b2 >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "boost along Z with beta >= 1 (speed of light) -- \nno boost done",
                "HepLorentzVector::boostZ");
        } else {
            final double ggamma = sqrt(1. / (1 - b2));
            final double tt = ee;
            ee = ggamma * (ee + bbeta * pp.getZ());
            pp.setZ(ggamma * (pp.getZ() + bbeta * tt));
        }
        return this;
    }

    public HepLorentzVector rotateX(double a) {
        pp.rotateX(a);
        return this;
    }

    public HepLorentzVector rotateY(double a) {
        pp.rotateY(a);
        return this;
    }

    public HepLorentzVector rotateZ(double a) {
        pp.rotateZ(a);
        return this;
    }

    public HepLorentzVector rotateUz(Hep3Vector v1) {
        pp.rotateUz(v1);
        return this;
    }

    public HepLorentzVector rotate(double a, Hep3Vector v1) {
        pp.rotate(a, v1);
        return this;
    }

    public HepLorentzVector rotate(Hep3Vector aaxis, double ddelta) {
        pp.rotate(aaxis, ddelta);
        return this;
    }

    public HepLorentzVector rotate(HepAxisAngle ax) {
        pp.rotate(ax);
        return this;
    }

    public HepLorentzVector rotate(HepEulerAngles e1) {
        pp.rotate(e1);
        return this;
    }

    public HepLorentzVector rotate(double phi1, double theta1, double psi1) {
        pp.rotate(phi1, theta1, psi1);
        return this;
    }

    /** v *= r: the space part rotated by r. */
    public HepLorentzVector timesAssign(HepRotation m1) {
        pp.transform(m1);
        return this;
    }

    public HepLorentzVector transform(HepRotation m1) {
        pp.transform(m1);
        return this;
    }

    public HepLorentzVector timesAssign(HepLorentzRotation m1) {
        return assign(m1.vectorMultiplication(this));
    }

    public HepLorentzVector transform(HepLorentzRotation m1) {
        return assign(m1.vectorMultiplication(this));
    }

    public static HepLorentzVector rotationXOf(HepLorentzVector vec, double phi) {
        return new HepLorentzVector(vec).rotateX(phi);
    }

    public static HepLorentzVector rotationYOf(HepLorentzVector vec, double phi) {
        return new HepLorentzVector(vec).rotateY(phi);
    }

    public static HepLorentzVector rotationZOf(HepLorentzVector vec, double phi) {
        return new HepLorentzVector(vec).rotateZ(phi);
    }

    public static HepLorentzVector rotationOf(HepLorentzVector vec, Hep3Vector aaxis, double ddelta) {
        return new HepLorentzVector(vec).rotate(aaxis, ddelta);
    }

    public static HepLorentzVector rotationOf(HepLorentzVector vec, HepAxisAngle ax) {
        return new HepLorentzVector(vec).rotate(ax);
    }

    public static HepLorentzVector rotationOf(HepLorentzVector vec, HepEulerAngles e1) {
        return new HepLorentzVector(vec).rotate(e1);
    }

    public static HepLorentzVector rotationOf(HepLorentzVector vec, double phi1, double theta1, double psi1) {
        return new HepLorentzVector(vec).rotate(phi1, theta1, psi1);
    }

    public static HepLorentzVector boostXOf(HepLorentzVector vec, double bbeta) {
        return new HepLorentzVector(vec).boostX(bbeta);
    }

    public static HepLorentzVector boostYOf(HepLorentzVector vec, double bbeta) {
        return new HepLorentzVector(vec).boostY(bbeta);
    }

    public static HepLorentzVector boostZOf(HepLorentzVector vec, double bbeta) {
        return new HepLorentzVector(vec).boostZ(bbeta);
    }

    public static HepLorentzVector boostOf(HepLorentzVector vec, Hep3Vector betaVector) {
        return new HepLorentzVector(vec).boost(betaVector);
    }

    public static HepLorentzVector boostOf(HepLorentzVector vec, Hep3Vector aaxis, double bbeta) {
        return new HepLorentzVector(vec).boost(aaxis, bbeta);
    }

    // ------------------------------------------------------------------
    // Streams
    // ------------------------------------------------------------------

    /** operator&lt;&lt;: "(x,y,z;t)". */
    public COStream print(COStream os) {
        return os.put("(").put(x()).put(",").put(y()).put(",").put(z()).put(";").put(t()).put(")");
    }

    /** operator&gt;&gt;: "(x, y, z; t)" exactly so; what is missing is said on cerr and the vector kept. */
    public static CIStream read(CIStream is, HepLorentzVector v1) {
        int c = is.nextChar();
        if (is.fail() || c != '(') {
            StdStreams.cerr().print("Could not find required opening parenthesis in input of a HepLorentzVector\n");
            return is;
        }
        final double x = is.nextDouble();
        c = is.nextChar();
        if (is.fail() || c != ',') {
            StdStreams.cerr().print("Could not find x value and required trailing comma in input of a HepLorentzVector\n");
            return is;
        }
        final double y = is.nextDouble();
        c = is.nextChar();
        if (is.fail() || c != ',') {
            StdStreams.cerr().print("Could not find y value and required trailing comma in input of a HepLorentzVector\n");
            return is;
        }
        final double z = is.nextDouble();
        c = is.nextChar();
        if (is.fail() || c != ';') {
            StdStreams.cerr().print("Could not find z value and required trailing semicolon in input of a "
                + "HepLorentzVector\n");
            return is;
        }
        final double t = is.nextDouble();
        c = is.nextChar();
        if (is.fail() || c != ')') {
            StdStreams.cerr().print("Could not find t value and required close parenthesis in input of a "
                + "HepLorentzVector\n");
            return is;
        }
        v1.setX(x);
        v1.setY(y);
        v1.setZ(z);
        v1.setT(t);
        return is;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
