package com.sphere.core.clhep.vector;

import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.atan;
import static com.sphere.core.clhep.CMath.atan2;
import static com.sphere.core.clhep.CMath.acos;
import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.exp;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.log;
import static com.sphere.core.clhep.CMath.pow;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;
import static com.sphere.core.clhep.CMath.tan;

/**
 * CLHEP's Hep3Vector (Vector/ThreeVector.h, .icc, ThreeVector.cc, ThreeVectorR.cc, SpaceVector*.cc):
 * a vector in three dimensions, in Cartesian components, with the polar, cylindrical and spherical
 * views, rotations, and the physics helpers (rapidity, beta, gamma) of the ZOOM PhysicsVectors.
 *
 * <p>Mutable as in C++: the C++ value semantics are the copies made by the methods returning a new
 * vector. The operators are named: {@code a + b} is {@link #plus}, {@code a += b} {@link #plusAssign},
 * {@code a * b} the {@link #dot} product, {@code a * c} {@link #times}, {@code -a} {@link #negate},
 * {@code a[i]} {@link #get}. Every computation is CLHEP's, in its order, for its bits.
 */
public class Hep3Vector implements Comparable<Hep3Vector> {

    public static final int X = 0, Y = 1, Z = 2, NUM_COORDINATES = 3, SIZE = NUM_COORDINATES;

    /** isNear's default tolerance: 100 ticks of 2.22045e-16. */
    public static final int ToleranceTicks = 100;
    protected static double tolerance = ToleranceTicks * 2.22045e-16;

    public static final Hep3Vector HepXHat = new Hep3Vector(1.0, 0.0, 0.0);
    public static final Hep3Vector HepYHat = new Hep3Vector(0.0, 1.0, 0.0);
    public static final Hep3Vector HepZHat = new Hep3Vector(0.0, 0.0, 1.0);

    protected final double[] data = new double[3];

    public Hep3Vector() {
    }

    public Hep3Vector(double x) {
        data[0] = x;
    }

    public Hep3Vector(double x, double y) {
        data[0] = x;
        data[1] = y;
    }

    public Hep3Vector(double x, double y, double z) {
        data[0] = x;
        data[1] = y;
        data[2] = z;
    }

    public Hep3Vector(Hep3Vector p) {
        this(p.x(), p.y(), p.z());
    }

    public Hep3Vector copy() {
        return new Hep3Vector(this);
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /** operator[] and operator(): the component i (0, 1, 2). */
    public double get(int i) {
        return data[i];
    }

    public void set(int i, double v) {
        data[i] = v;
    }

    public double x() {
        return data[X];
    }

    public double y() {
        return data[Y];
    }

    public double z() {
        return data[Z];
    }

    public double getX() {
        return data[X];
    }

    public double getY() {
        return data[Y];
    }

    public double getZ() {
        return data[Z];
    }

    public void setX(double x) {
        data[X] = x;
    }

    public void setY(double y) {
        data[Y] = y;
    }

    public void setZ(double z) {
        data[Z] = z;
    }

    public void set(double x, double y, double z) {
        data[X] = x;
        data[Y] = y;
        data[Z] = z;
    }

    /** operator=: the components of p. */
    public Hep3Vector assign(Hep3Vector p) {
        set(p.x(), p.y(), p.z());
        return this;
    }

    // ------------------------------------------------------------------
    // Polar and cylindrical views
    // ------------------------------------------------------------------

    public double mag2() {
        return x() * x() + y() * y() + z() * z();
    }

    public double mag() {
        return sqrt(mag2());
    }

    public double r() {
        return mag();
    }

    public double theta() {
        return x() == 0.0 && y() == 0.0 && z() == 0.0 ? 0.0 : atan2(perp(), z());
    }

    public double phi() {
        return x() == 0.0 && y() == 0.0 ? 0.0 : atan2(y(), x());
    }

    public double getR() {
        return mag();
    }

    public double getTheta() {
        return theta();
    }

    public double getPhi() {
        return phi();
    }

    public double angle() {
        return theta();
    }

    public double cosTheta() {
        final double ptot = mag();
        return ptot == 0.0 ? 1.0 : z() / ptot;
    }

    public double cos2Theta() {
        final double ptot2 = mag2();
        return ptot2 == 0.0 ? 1.0 : z() * z() / ptot2;
    }

    public void setR(double r1) {
        setMag(r1);
    }

    public void setTheta(double th) {
        final double ma = mag();
        final double ph = phi();
        setX(ma * sin(th) * cos(ph));
        setY(ma * sin(th) * sin(ph));
        setZ(ma * cos(th));
    }

    public void setPhi(double ph) {
        final double xy = perp();
        setX(xy * cos(ph));
        setY(xy * sin(ph));
    }

    public void setMag(double ma) {
        double factor = mag();
        if (factor == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "Hep3Vector::setMag : zero vector can't be stretched", "Hep3Vector::setMag");
        } else {
            factor = ma / factor;
            setX(x() * factor);
            setY(y() * factor);
            setZ(z() * factor);
        }
    }

    public double perp2() {
        return x() * x() + y() * y();
    }

    public double perp() {
        return sqrt(perp2());
    }

    public double rho() {
        return perp();
    }

    public double eta() {
        return pseudoRapidity();
    }

    public double getRho() {
        return perp();
    }

    public double getEta() {
        return pseudoRapidity();
    }

    public void setPerp(double r1) {
        final double p = perp();
        if (p != 0.0) {
            data[X] *= r1 / p;
            data[Y] *= r1 / p;
        }
    }

    public void setRho(double rho1) {
        setPerp(rho1);
    }

    /** The transverse component squared with respect to p. */
    public double perp2(Hep3Vector p) {
        final double tot = p.mag2();
        final double ss = dot(p);
        return tot > 0.0 ? mag2() - ss * ss / tot : mag2();
    }

    public double perp(Hep3Vector p) {
        return sqrt(perp2(p));
    }

    public void setRThetaPhi(double r1, double theta1, double phi1) {
        setSpherical(r1, theta1, phi1);
    }

    public void setREtaPhi(double r1, double eta1, double phi1) {
        setSpherical(r1, 2 * atan(exp(-eta1)), phi1);
    }

    public void setRhoPhiZ(double rho1, double phi1, double z1) {
        setCylindrical(rho1, phi1, z1);
    }

    public double pseudoRapidity() {
        final double m1 = mag();
        if (m1 == 0) return 0.0;
        if (m1 == z()) return 1.0E72;
        if (m1 == -z()) return -1.0E72;
        return 0.5 * log((m1 + z()) / (m1 - z()));
    }

    public void setEta(double eta1) {
        double phi1 = 0;
        final double r1;
        if ((x() == 0) && (y() == 0)) {
            if (z() == 0) {
                ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt to set eta of zero vector -- vector is unchanged",
                    "Hep3Vector::setEta");
                return;
            }
            ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt to set eta of vector along Z axis -- will use phi = 0",
                "Hep3Vector::setEta");
            r1 = fabs(z());
        } else {
            r1 = getR();
            phi1 = getPhi();
        }
        final double tanHalfTheta = exp(-eta1);
        final double cosTheta1 = (1 - tanHalfTheta * tanHalfTheta) / (1 + tanHalfTheta * tanHalfTheta);
        final double rho1 = r1 * sqrt(1 - cosTheta1 * cosTheta1);
        setZ(r1 * cosTheta1);
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    public void setCylTheta(double theta1) {
        if ((x() == 0) && (y() == 0)) {
            if (z() == 0) {
                ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt to set cylTheta of zero vector -- vector is unchanged",
                    "Hep3Vector::setCylTheta");
                return;
            }
            if (theta1 == 0) {
                setZ(fabs(z()));
                return;
            }
            if (theta1 == SystemOfUnits.pi) {
                setZ(-fabs(z()));
                return;
            }
            ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt set cylindrical theta of vector along Z axis "
                + "to a non-trivial value, while keeping rho fixed -- " + "will return zero vector",
                "Hep3Vector::setCylTheta");
            setZ(0.0);
            return;
        }
        if ((theta1 < 0) || (theta1 > SystemOfUnits.pi)) {
            ZMxpv.throwC(ZMxpv.UNUSUAL_THETA, "Setting Cyl theta of a vector based on a value not in [0, PI]",
                "Hep3Vector::setCylTheta");
        }
        final double phi1 = getPhi();
        final double rho1 = getRho();
        if ((theta1 == 0) || (theta1 == SystemOfUnits.pi)) {
            ZMxpv.throwC(ZMxpv.INFINITE_VECTOR, "Attempt to set cylindrical theta to 0 or PI "
                + "while keeping rho fixed -- infinite Z will be computed", "Hep3Vector::setCylTheta");
            setZ((theta1 == 0) ? 1.0E72 : -1.0E72);
            return;
        }
        setZ(rho1 / tan(theta1));
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    public void setCylEta(double eta1) {
        final double theta1 = 2 * atan(exp(-eta1));
        if ((x() == 0) && (y() == 0)) {
            if (z() == 0) {
                ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt to set cylEta of zero vector -- vector is unchanged",
                    "Hep3Vector::setCylEta");
                return;
            }
            if (theta1 == 0) {
                setZ(fabs(z()));
                return;
            }
            if (theta1 == SystemOfUnits.pi) {
                setZ(-fabs(z()));
                return;
            }
            ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt set cylindrical eta of vector along Z axis "
                + "to a non-trivial value, while keeping rho fixed -- " + "will return zero vector",
                "Hep3Vector::setCylEta");
            setZ(0.0);
            return;
        }
        final double phi1 = getPhi();
        final double rho1 = getRho();
        setZ(rho1 / tan(theta1));
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    /** setSpherical (protected in C++): from r, theta and phi. */
    protected void setSpherical(double r1, double theta1, double phi1) {
        if (r1 < 0) {
            ZMxpv.throwC(ZMxpv.NEGATIVE_R, "Spherical coordinates set with negative   R", "Hep3Vector::setSpherical");
        }
        if ((theta1 < 0) || (theta1 > SystemOfUnits.pi)) {
            ZMxpv.throwC(ZMxpv.UNUSUAL_THETA, "Spherical coordinates set with theta not in [0, PI]",
                "Hep3Vector::setSpherical");
        }
        final double rho1 = r1 * sin(theta1);
        setZ(r1 * cos(theta1));
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    /** setCylindrical (protected in C++): from rho, phi and z. */
    protected void setCylindrical(double rho1, double phi1, double z1) {
        if (rho1 < 0) {
            ZMxpv.throwC(ZMxpv.NEGATIVE_R, "Cylindrical coordinates supplied with negative Rho",
                "Hep3Vector::setCylindrical");
        }
        setZ(z1);
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    public void setRhoPhiTheta(double rho1, double phi1, double theta1) {
        if (rho1 == 0) {
            ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt set vector components rho, phi, theta with zero rho -- "
                + "zero vector is returned, ignoring theta and phi", "Hep3Vector::setRhoPhiTheta");
            set(0.0, 0.0, 0.0);
            return;
        }
        if ((theta1 == 0) || (theta1 == SystemOfUnits.pi)) {
            ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "Attempt set cylindrical vector vector with finite rho and "
                + "theta along the Z axis:  infinite Z would be computed", "Hep3Vector::setRhoPhiTheta");
        }
        if ((theta1 < 0) || (theta1 > SystemOfUnits.pi)) {
            ZMxpv.throwC(ZMxpv.UNUSUAL_THETA, "Rho, phi, theta set with theta not in [0, PI]",
                "Hep3Vector::setRhoPhiTheta");
        }
        setZ(rho1 / tan(theta1));
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    public void setRhoPhiEta(double rho1, double phi1, double eta1) {
        if (rho1 == 0) {
            ZMxpv.throwC(ZMxpv.ZERO_VECTOR, "Attempt set vector components rho, phi, eta with zero rho -- "
                + "zero vector is returned, ignoring eta and phi", "Hep3Vector::setRhoPhiEta");
            set(0.0, 0.0, 0.0);
            return;
        }
        final double theta1 = 2 * atan(exp(-eta1));
        setZ(rho1 / tan(theta1));
        setY(rho1 * sin(phi1));
        setX(rho1 * cos(phi1));
    }

    // ------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------

    /** a + b. */
    public Hep3Vector plus(Hep3Vector b) {
        return new Hep3Vector(x() + b.x(), y() + b.y(), z() + b.z());
    }

    /** a - b. */
    public Hep3Vector minus(Hep3Vector b) {
        return new Hep3Vector(x() - b.x(), y() - b.y(), z() - b.z());
    }

    /** a * c (and c * a: CLHEP multiplies c first). */
    public Hep3Vector times(double a) {
        return new Hep3Vector(a * x(), a * y(), a * z());
    }

    /** a / c: a times 1/c; dividing by 0 is reported and thrown. */
    public Hep3Vector div(double c) {
        if (c == 0) {
            ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "Attempt to divide vector by 0 -- "
                + "will produce infinities and/or NANs", "operator/(Hep3Vector, double)");
        }
        return times(1.0 / c);
    }

    /** -a. */
    public Hep3Vector negate() {
        return new Hep3Vector(-x(), -y(), -z());
    }

    public Hep3Vector plusAssign(Hep3Vector p) {
        data[X] += p.x();
        data[Y] += p.y();
        data[Z] += p.z();
        return this;
    }

    public Hep3Vector minusAssign(Hep3Vector p) {
        data[X] -= p.x();
        data[Y] -= p.y();
        data[Z] -= p.z();
        return this;
    }

    public Hep3Vector timesAssign(double a) {
        data[X] *= a;
        data[Y] *= a;
        data[Z] *= a;
        return this;
    }

    public Hep3Vector divAssign(double c) {
        if (c == 0) {
            ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "Attempt to do vector /= 0 -- "
                + "division by zero would produce infinite or NAN components", "Hep3Vector::operator/=");
        }
        return timesAssign(1.0 / c);
    }

    /** v *= m: the vector rotated by m (m * v). */
    public Hep3Vector timesAssign(HepRotation m1) {
        return assign(m1.times(this));
    }

    public Hep3Vector transform(HepRotation m1) {
        return assign(m1.times(this));
    }

    public double dot(Hep3Vector p) {
        return x() * p.x() + y() * p.y() + z() * p.z();
    }

    public Hep3Vector cross(Hep3Vector p) {
        return new Hep3Vector(y() * p.z() - p.y() * z(), z() * p.x() - p.z() * x(), x() * p.y() - p.x() * y());
    }

    public Hep3Vector unit() {
        final double tot = mag2();
        final Hep3Vector p = new Hep3Vector(x(), y(), z());
        return tot > 0.0 ? p.timesAssign(1.0 / sqrt(tot)) : p;
    }

    public Hep3Vector orthogonal() {
        final double xx = x() < 0.0 ? -x() : x();
        final double yy = y() < 0.0 ? -y() : y();
        final double zz = z() < 0.0 ? -z() : z();
        if (xx < yy) {
            return xx < zz ? new Hep3Vector(0, z(), -y()) : new Hep3Vector(y(), -x(), 0);
        } else {
            return yy < zz ? new Hep3Vector(-z(), 0, x()) : new Hep3Vector(y(), -x(), 0);
        }
    }

    public double diff2(Hep3Vector p) {
        return minus(p).mag2();
    }

    // ------------------------------------------------------------------
    // Comparisons
    // ------------------------------------------------------------------

    /** operator==: all three components equal. */
    public boolean isEqual(Hep3Vector v) {
        return v.x() == x() && v.y() == y() && v.z() == z();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hep3Vector v && isEqual(v);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(data);
    }

    /** z first, then y, then x: CLHEP's ordering for maps. */
    public int compare(Hep3Vector v) {
        if (z() > v.z()) {
            return 1;
        } else if (z() < v.z()) {
            return -1;
        } else if (y() > v.y()) {
            return 1;
        } else if (y() < v.y()) {
            return -1;
        } else if (x() > v.x()) {
            return 1;
        } else if (x() < v.x()) {
            return -1;
        } else {
            return 0;
        }
    }

    @Override
    public int compareTo(Hep3Vector v) {
        return compare(v);
    }

    public boolean greaterThan(Hep3Vector v) {
        return compare(v) > 0;
    }

    public boolean lessThan(Hep3Vector v) {
        return compare(v) < 0;
    }

    public boolean greaterOrEqual(Hep3Vector v) {
        return compare(v) >= 0;
    }

    public boolean lessOrEqual(Hep3Vector v) {
        return compare(v) <= 0;
    }

    public boolean isNear(Hep3Vector v) {
        return isNear(v, tolerance);
    }

    public boolean isNear(Hep3Vector v, double epsilon) {
        final double limit = dot(v) * epsilon * epsilon;
        return minus(v).mag2() <= limit;
    }

    public double howNear(Hep3Vector v) {
        final double d = minus(v).mag2();
        final double vdv = dot(v);
        if ((vdv > 0) && (d < vdv)) {
            return sqrt(d / vdv);
        } else if ((vdv == 0) && (d == 0)) {
            return 0;
        } else {
            return 1;
        }
    }

    public double deltaR(Hep3Vector v) {
        final double a = eta() - v.eta();
        final double b = deltaPhi(v);
        return sqrt(a * a + b * b);
    }

    public static double setTolerance(double tol) {
        final double old = tolerance;
        tolerance = tol;
        return old;
    }

    public static double getTolerance() {
        return tolerance;
    }

    private static final double TOOBIG = pow(2.0, 507);
    private static final double SCALE = pow(2.0, -507);

    public boolean isParallel(Hep3Vector v) {
        return isParallel(v, tolerance);
    }

    public boolean isParallel(Hep3Vector v, double epsilon) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 == 0) {
            return (mag2() == 0) && (v.mag2() == 0);
        }
        if (v1v2 >= TOOBIG) {
            final Hep3Vector sv1 = times(SCALE);
            final Hep3Vector sv2 = v.times(SCALE);
            final Hep3Vector sv1Xsv2 = sv1.cross(sv2);
            final double x2 = sv1Xsv2.mag2();
            double limit = v1v2 * SCALE * SCALE;
            limit = epsilon * epsilon * limit * limit;
            return x2 <= limit;
        }
        final Hep3Vector v1Xv2 = cross(v);
        if ((fabs(v1Xv2.x()) > TOOBIG) || (fabs(v1Xv2.y()) > TOOBIG) || (fabs(v1Xv2.z()) > TOOBIG)) {
            return false;
        }
        return v1Xv2.mag2() <= ((epsilon * v1v2) * (epsilon * v1v2));
    }

    public double howParallel(Hep3Vector v) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 == 0) {
            return ((mag2() == 0) && (v.mag2() == 0)) ? 0 : 1;
        }
        final Hep3Vector v1Xv2 = cross(v);
        final double abscross = v1Xv2.mag();
        if (abscross >= v1v2) {
            return 1;
        } else {
            return abscross / v1v2;
        }
    }

    public boolean isOrthogonal(Hep3Vector v) {
        return isOrthogonal(v, tolerance);
    }

    public boolean isOrthogonal(Hep3Vector v, double epsilon) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 >= TOOBIG) {
            final Hep3Vector sv1 = times(SCALE);
            final Hep3Vector sv2 = v.times(SCALE);
            final Hep3Vector sv1Xsv2 = sv1.cross(sv2);
            final double x2 = sv1Xsv2.mag2();
            final double limit = epsilon * epsilon * x2;
            final double y2 = v1v2 * SCALE * SCALE;
            return y2 * y2 <= limit;
        }
        final Hep3Vector eps_v1Xv2 = cross(v.times(epsilon));
        if ((fabs(eps_v1Xv2.x()) > TOOBIG) || (fabs(eps_v1Xv2.y()) > TOOBIG) || (fabs(eps_v1Xv2.z()) > TOOBIG)) {
            return true;
        }
        return v1v2 * v1v2 <= eps_v1Xv2.mag2();
    }

    public double howOrthogonal(Hep3Vector v) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 == 0) {
            return 0;   // even if one or both are 0, they are considered orthogonal
        }
        final Hep3Vector v1Xv2 = cross(v);
        final double abscross = v1Xv2.mag();
        if (v1v2 >= abscross) {
            return 1;
        } else {
            return v1v2 / abscross;
        }
    }

    // ------------------------------------------------------------------
    // Relativity and angles
    // ------------------------------------------------------------------

    public double beta() {
        final double b = sqrt(mag2());
        if (b >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Beta taken for Hep3Vector of at least unit length", "Hep3Vector::beta");
        }
        return b;
    }

    public double gamma() {
        final double bbeta = sqrt(mag2());
        if (bbeta == 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Gamma taken for Hep3Vector of unit magnitude -- infinite result",
                "Hep3Vector::gamma");
        }
        if (bbeta > 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Gamma taken for Hep3Vector of more than unit magnitude -- "
                + "the sqrt function would return NAN", "Hep3Vector::gamma");
        }
        return 1 / sqrt(1 - bbeta * bbeta);
    }

    public double rapidity() {
        if (fabs(z()) == 1) {
            ZMxpv.throwC(ZMxpv.TACHYONIC, "Rapidity in Z direction taken for Hep3Vector with |Z| = 1 -- \n"
                + "the log should return infinity", "Hep3Vector::rapidity");
        }
        if (fabs(z()) > 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Rapidity in Z direction taken for Hep3Vector with |Z| > 1 -- \n"
                + "the log would return a NAN", "Hep3Vector::rapidity");
        }
        return .5 * log((1 + z()) / (1 - z()));
    }

    public double coLinearRapidity() {
        final double b = beta();
        if (b == 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Co-linear Rapidity taken for Hep3Vector of unit length -- "
                + "the log should return infinity", "Hep3Vector::coLinearRapidity");
        }
        if (b > 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Co-linear Rapidity taken for Hep3Vector of more than unit length -- "
                + "the log would return a NAN", "Hep3Vector::coLinearRapidity");
        }
        return .5 * log((1 + b) / (1 - b));
    }

    public double theta(Hep3Vector v2) {
        return angle(v2);
    }

    public double angle(Hep3Vector q) {
        return acos(cosTheta(q));
    }

    public double cosTheta(Hep3Vector q) {
        double arg;
        final double ptot2 = mag2() * q.mag2();
        if (ptot2 <= 0) {
            arg = 0.0;
        } else {
            arg = dot(q) / sqrt(ptot2);
            if (arg > 1.0) arg = 1.0;
            if (arg < -1.0) arg = -1.0;
        }
        return arg;
    }

    public double cos2Theta(Hep3Vector q) {
        double arg;
        final double ptot2 = mag2();
        final double qtot2 = q.mag2();
        if (ptot2 == 0 || qtot2 == 0) {
            arg = 1.0;
        } else {
            final double pdq = dot(q);
            arg = (pdq / ptot2) * (pdq / qtot2);
            if (arg > 1.0) arg = 1.0;
        }
        return arg;
    }

    /** The part along z. */
    public Hep3Vector project() {
        return new Hep3Vector(0, 0, z());
    }

    public Hep3Vector project(Hep3Vector v2) {
        final double mag2v2 = v2.mag2();
        if (mag2v2 == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "Attempt to take projection of vector against zero reference vector ",
                "Hep3Vector::project");
        }
        return v2.times(dot(v2) / mag2v2);
    }

    /** The part across z. */
    public Hep3Vector perpPart() {
        return new Hep3Vector(x(), y(), 0);
    }

    public Hep3Vector perpPart(Hep3Vector v2) {
        return minus(project(v2));
    }

    public double rapidity(Hep3Vector v2) {
        final double vmag = v2.mag();
        if (vmag == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "Rapidity taken with respect to zero vector", "Hep3Vector::rapidity");
        }
        final double z1 = dot(v2) / vmag;
        if (fabs(z1) >= 1) {
            ZMxpv.throwA(ZMxpv.TACHYONIC, "Rapidity taken for too large a Hep3Vector "
                + "-- would return infinity or NAN", "Hep3Vector::rapidity");
        }
        return .5 * log((1 + z1) / (1 - z1));
    }

    public double eta(Hep3Vector v2) {
        final double r1 = getR();
        final double v2r = v2.mag();
        if ((r1 == 0) || (v2r == 0)) {
            ZMxpv.throwA(ZMxpv.AMBIGUOUS_ANGLE, "Cannot find pseudorapidity of a zero vector relative to a vector",
                "Hep3Vector::eta");
        }
        double c = dot(v2) / (r1 * v2r);
        if (c >= 1) {
            c = 1;   // we don't want to return NAN because of roundoff
            ZMxpv.throwC(ZMxpv.INFINITY, "Pseudorapidity of vector relative to parallel vector -- "
                + "will give infinite result", "Hep3Vector::eta");
        }
        if (c <= -1) {
            ZMxpv.throwC(ZMxpv.INFINITY, "Pseudorapidity of vector relative to anti-parallel vector -- "
                + "will give negative infinite result", "Hep3Vector::eta");
            return negativeInfinity();
        }
        final double tangent = sqrt(1 - c * c) / (1 + c);
        return -log(tangent);
    }

    public double polarAngle(Hep3Vector v2) {
        return fabs(v2.getTheta() - getTheta());
    }

    public double deltaPhi(Hep3Vector v2) {
        double dphi = v2.getPhi() - getPhi();
        if (dphi > SystemOfUnits.pi) {
            dphi -= SystemOfUnits.twopi;
        } else if (dphi <= -SystemOfUnits.pi) {
            dphi += SystemOfUnits.twopi;
        }
        return dphi;
    }

    public double azimAngle(Hep3Vector v2) {
        return deltaPhi(v2);
    }

    public double polarAngle(Hep3Vector v2, Hep3Vector ref) {
        return fabs(v2.angle(ref) - angle(ref));
    }

    public double azimAngle(Hep3Vector v2, Hep3Vector ref) {
        final Hep3Vector vperp = perpPart(ref);
        if (vperp.mag2() == 0) {
            ZMxpv.throwC(ZMxpv.AMBIGUOUS_ANGLE, "Cannot find azimuthal angle with reference direction parallel to "
                + "vector 1 -- will return zero", "Hep3Vector::azimAngle");
            return 0;
        }
        final Hep3Vector v2perp = v2.perpPart(ref);
        if (v2perp.mag2() == 0) {
            ZMxpv.throwC(ZMxpv.AMBIGUOUS_ANGLE, "Cannot find azimuthal angle with reference direction parallel to "
                + "vector 2 -- will return zero", "Hep3Vector::azimAngle");
            return 0;
        }
        final double ang = vperp.angle(v2perp);
        if (dot(v2.cross(ref)) >= 0) {
            return ang;
        } else {
            return -ang;
        }
    }

    /** -1 or'ed bit by bit with 2: the negative infinity CLHEP builds by hand. */
    protected double negativeInfinity() {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(-1.0) | Double.doubleToRawLongBits(2.0));
    }

    // ------------------------------------------------------------------
    // Rotations
    // ------------------------------------------------------------------

    public Hep3Vector rotateX(double phi1) {
        final double sinphi = sin(phi1);
        final double cosphi = cos(phi1);
        final double ty = y() * cosphi - z() * sinphi;
        final double tz = z() * cosphi + y() * sinphi;
        setY(ty);
        setZ(tz);
        return this;
    }

    public Hep3Vector rotateY(double phi1) {
        final double sinphi = sin(phi1);
        final double cosphi = cos(phi1);
        final double tx = x() * cosphi + z() * sinphi;
        final double tz = z() * cosphi - x() * sinphi;
        setX(tx);
        setZ(tz);
        return this;
    }

    public Hep3Vector rotateZ(double phi1) {
        final double sinphi = sin(phi1);
        final double cosphi = cos(phi1);
        final double tx = x() * cosphi - y() * sinphi;
        final double ty = y() * cosphi + x() * sinphi;
        setX(tx);
        setY(ty);
        return this;
    }

    /** The vector turned so that the z axis goes to the unit vector given. */
    public Hep3Vector rotateUz(Hep3Vector newUzVector) {
        final double u1 = newUzVector.x();
        final double u2 = newUzVector.y();
        final double u3 = newUzVector.z();
        double up = u1 * u1 + u2 * u2;
        if (up > 0) {
            up = sqrt(up);
            final double px = (u1 * u3 * x() - u2 * y()) / up + u1 * z();
            final double py = (u2 * u3 * x() + u1 * y()) / up + u2 * z();
            final double pz = -up * x() + u3 * z();
            set(px, py, pz);
        } else if (u3 < 0.) {
            setX(-x());
            setZ(-z());
        }   // phi=0 theta=pi
        return this;
    }

    /** rotate(angle, axis) (ThreeVectorR.cc): through a HepRotation. */
    public Hep3Vector rotate(double angle1, Hep3Vector aaxis) {
        final HepRotation trans = new HepRotation();
        trans.rotate(angle1, aaxis);
        timesAssign(trans);
        return this;
    }

    /** rotate(axis, delta) (SpaceVectorR.cc): by the formula of a rotation about an axis. */
    public Hep3Vector rotate(Hep3Vector axis, double ddelta) {
        final double r1 = axis.mag();
        if (r1 == 0) {
            ZMxpv.throwA(ZMxpv.ZERO_VECTOR, "Attempt to rotate around a zero vector axis! ", "Hep3Vector::rotate");
        }
        final double scale = 1.0 / r1;
        final double ux = scale * axis.getX();
        final double uy = scale * axis.getY();
        final double uz = scale * axis.getZ();
        final double cd = cos(ddelta);
        final double sd = sin(ddelta);
        final double ocd = 1 - cd;
        final double rx;
        final double ry;
        final double rz;
        {
            final double ocdux = ocd * ux;
            rx = x() * (cd + ocdux * ux) + y() * (ocdux * uy - sd * uz) + z() * (ocdux * uz + sd * uy);
        }
        {
            final double ocduy = ocd * uy;
            ry = y() * (cd + ocduy * uy) + z() * (ocduy * uz - sd * ux) + x() * (ocduy * ux + sd * uz);
        }
        {
            final double ocduz = ocd * uz;
            rz = z() * (cd + ocduz * uz) + x() * (ocduz * ux - sd * uy) + y() * (ocduz * uy + sd * ux);
        }
        set(rx, ry, rz);
        return this;
    }

    public Hep3Vector rotate(HepAxisAngle ax) {
        return rotate(ax.getAxis(), ax.delta());
    }

    public Hep3Vector rotate(HepEulerAngles ex) {
        return rotate(ex.phi(), ex.theta(), ex.psi());
    }

    /** rotate(phi, theta, psi): by Euler angles (Goldstein's convention, as CLHEP). */
    public Hep3Vector rotate(double phi1, double theta1, double psi1) {
        final double sinPhi = sin(phi1), cosPhi = cos(phi1);
        final double sinTheta = sin(theta1), cosTheta1 = cos(theta1);
        final double sinPsi = sin(psi1), cosPsi = cos(psi1);
        final double rx = (cosPsi * cosPhi - cosTheta1 * sinPsi * sinPhi) * x()
            + (cosPsi * sinPhi + cosTheta1 * sinPsi * cosPhi) * y()
            + (sinPsi * sinTheta) * z();
        final double ry = (-sinPsi * cosPhi - cosTheta1 * cosPsi * sinPhi) * x()
            + (-sinPsi * sinPhi + cosTheta1 * cosPsi * cosPhi) * y()
            + (cosPsi * sinTheta) * z();
        final double rz = (sinTheta * sinPhi) * x()
            + (-sinTheta * cosPhi) * y()
            + (cosTheta1) * z();
        set(rx, ry, rz);
        return this;
    }

    public static Hep3Vector rotationXOf(Hep3Vector vec, double delta) {
        return new Hep3Vector(vec).rotateX(delta);
    }

    public static Hep3Vector rotationYOf(Hep3Vector vec, double delta) {
        return new Hep3Vector(vec).rotateY(delta);
    }

    public static Hep3Vector rotationZOf(Hep3Vector vec, double delta) {
        return new Hep3Vector(vec).rotateZ(delta);
    }

    public static Hep3Vector rotationOf(Hep3Vector vec, Hep3Vector axis, double delta) {
        return new Hep3Vector(vec).rotate(axis, delta);
    }

    public static Hep3Vector rotationOf(Hep3Vector vec, HepAxisAngle ax) {
        return new Hep3Vector(vec).rotate(ax);
    }

    public static Hep3Vector rotationOf(Hep3Vector vec, double phi, double theta, double psi) {
        return new Hep3Vector(vec).rotate(phi, theta, psi);
    }

    public static Hep3Vector rotationOf(Hep3Vector vec, HepEulerAngles e) {
        return new Hep3Vector(vec).rotate(e);
    }

    // ------------------------------------------------------------------
    // Streams
    // ------------------------------------------------------------------

    /** operator&lt;&lt;: "(x,y,z)" with the stream's precision and flags. */
    public COStream print(COStream os) {
        return os.put("(").put(x()).put(",").put(y()).put(",").put(z()).put(")");
    }

    /** operator&gt;&gt;: "(x, y, z)", "x y z"...; what cannot be read is said on cerr. */
    public static CIStream read(CIStream is, Hep3Vector v) {
        final double[] c = new double[3];
        ZMinput.input3doubles(is, "Hep3Vector", c);
        v.set(c[0], c[1], c[2]);
        return is;
    }

    /** As cout prints it: six significant digits. */
    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
