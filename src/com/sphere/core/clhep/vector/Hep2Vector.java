package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

import static com.sphere.core.clhep.CMath.acos;
import static com.sphere.core.clhep.CMath.atan2;
import static com.sphere.core.clhep.CMath.cos;
import static com.sphere.core.clhep.CMath.fabs;
import static com.sphere.core.clhep.CMath.sin;
import static com.sphere.core.clhep.CMath.sqrt;

/**
 * CLHEP's Hep2Vector (Vector/TwoVector.h, .icc, TwoVector.cc): a vector in a plane. Its parallel and
 * orthogonal tests keep CLHEP's cross product as written ({@code dx * v.y() - dy - v.x()}), so that
 * they answer as CLHEP answers.
 */
public final class Hep2Vector implements Comparable<Hep2Vector> {

    public static final int X = 0, Y = 1, NUM_COORDINATES = 2, SIZE = NUM_COORDINATES;
    public static final int ZMpvToleranceTicks = 100;
    private static double tolerance = ZMpvToleranceTicks * 2.22045e-16;

    public static final Hep2Vector X_HAT2 = new Hep2Vector(1.0, 0.0);
    public static final Hep2Vector Y_HAT2 = new Hep2Vector(0.0, 1.0);

    private double dx;
    private double dy;

    public Hep2Vector() {
    }

    public Hep2Vector(double x) {
        dx = x;
    }

    public Hep2Vector(double x, double y) {
        dx = x;
        dy = y;
    }

    public Hep2Vector(Hep2Vector p) {
        dx = p.x();
        dy = p.y();
    }

    /** The x and y of a Hep3Vector. */
    public Hep2Vector(Hep3Vector v3) {
        dx = v3.x();
        dy = v3.y();
    }

    public Hep2Vector copy() {
        return new Hep2Vector(this);
    }

    public double x() {
        return dx;
    }

    public double y() {
        return dy;
    }

    /** operator() and operator[]: 0 is x, 1 is y; another index is reported and thrown. */
    public double get(int i) {
        if (i == 0) {
            return x();
        } else if (i == 1) {
            return y();
        }
        throw ZMxpv.throwA(ZMxpv.INDEX_RANGE, "Hep2Vector::operator(): bad index", "Hep2Vector::operator()");
    }

    public void set(int i, double v) {
        switch (i) {
            case X -> dx = v;
            case Y -> dy = v;
            default -> ZMxpv.throwA(ZMxpv.INDEX_RANGE, "Hep2Vector::operator() : bad index", "Hep2Vector::operator()");
        }
    }

    public void setX(double x1) {
        dx = x1;
    }

    public void setY(double y1) {
        dy = y1;
    }

    public void set(double x1, double y1) {
        dx = x1;
        dy = y1;
    }

    public Hep2Vector assign(Hep2Vector p) {
        dx = p.x();
        dy = p.y();
        return this;
    }

    public double phi() {
        return dx == 0.0 && dy == 0.0 ? 0.0 : atan2(dy, dx);
    }

    public double mag2() {
        return dx * dx + dy * dy;
    }

    public double mag() {
        return sqrt(mag2());
    }

    public double r() {
        return sqrt(mag2());
    }

    public void setPhi(double phi1) {
        final double ma = mag();
        setX(ma * cos(phi1));
        setY(ma * sin(phi1));
    }

    public void setMag(double r1) {
        final double ph = phi();
        setX(r1 * cos(ph));
        setY(r1 * sin(ph));
    }

    public void setR(double r1) {
        setMag(r1);
    }

    public void setPolar(double r1, double phi1) {
        setX(r1 * cos(phi1));
        setY(r1 * sin(phi1));
    }

    public boolean isEqual(Hep2Vector v) {
        return v.x() == x() && v.y() == y();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hep2Vector v && isEqual(v);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(dx) * 31 + Double.hashCode(dy);
    }

    /** y first, then x. */
    public int compare(Hep2Vector v) {
        if (dy > v.dy) {
            return 1;
        } else if (dy < v.dy) {
            return -1;
        } else if (dx > v.dx) {
            return 1;
        } else if (dx < v.dx) {
            return -1;
        } else {
            return 0;
        }
    }

    @Override
    public int compareTo(Hep2Vector v) {
        return compare(v);
    }

    public boolean greaterThan(Hep2Vector v) {
        return compare(v) > 0;
    }

    public boolean lessThan(Hep2Vector v) {
        return compare(v) < 0;
    }

    public boolean greaterOrEqual(Hep2Vector v) {
        return compare(v) >= 0;
    }

    public boolean lessOrEqual(Hep2Vector v) {
        return compare(v) <= 0;
    }

    public static double getTolerance() {
        return tolerance;
    }

    public static double setTolerance(double tol) {
        final double old = tolerance;
        tolerance = tol;
        return old;
    }

    public double howNear(Hep2Vector p) {
        final double d = minus(p).mag2();
        final double pdp = dot(p);
        if ((pdp > 0) && (d < pdp)) {
            return sqrt(d / pdp);
        } else if ((pdp == 0) && (d == 0)) {
            return 0;
        } else {
            return 1;
        }
    }

    public boolean isNear(Hep2Vector p) {
        return isNear(p, tolerance);
    }

    public boolean isNear(Hep2Vector p, double epsilon) {
        final double limit = dot(p) * epsilon * epsilon;
        return minus(p).mag2() <= limit;
    }

    public double howParallel(Hep2Vector v) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 == 0) {
            return ((mag2() == 0) && (v.mag2() == 0)) ? 0 : 1;
        }
        final double abscross = fabs(dx * v.y() - dy - v.x());
        if (abscross >= v1v2) {
            return 1;
        } else {
            return abscross / v1v2;
        }
    }

    public boolean isParallel(Hep2Vector v) {
        return isParallel(v, tolerance);
    }

    public boolean isParallel(Hep2Vector v, double epsilon) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 == 0) {
            return (mag2() == 0) && (v.mag2() == 0);
        }
        final double abscross = fabs(dx * v.y() - dy - v.x());
        return abscross <= epsilon * v1v2;
    }

    public double howOrthogonal(Hep2Vector v) {
        final double v1v2 = fabs(dot(v));
        if (v1v2 == 0) {
            return 0;
        }
        final double abscross = fabs(dx * v.y() - dy - v.x());
        if (v1v2 >= abscross) {
            return 1;
        } else {
            return v1v2 / abscross;
        }
    }

    public boolean isOrthogonal(Hep2Vector v) {
        return isOrthogonal(v, tolerance);
    }

    public boolean isOrthogonal(Hep2Vector v, double epsilon) {
        final double v1v2 = fabs(dot(v));
        final double abscross = fabs(dx * v.y() - dy - v.x());
        return v1v2 <= epsilon * abscross;
    }

    public Hep2Vector plusAssign(Hep2Vector p) {
        dx += p.x();
        dy += p.y();
        return this;
    }

    public Hep2Vector minusAssign(Hep2Vector p) {
        dx -= p.x();
        dy -= p.y();
        return this;
    }

    public Hep2Vector negate() {
        return new Hep2Vector(-dx, -dy);
    }

    public Hep2Vector timesAssign(double a) {
        dx *= a;
        dy *= a;
        return this;
    }

    public Hep2Vector unit() {
        final double tot = mag2();
        final Hep2Vector p = new Hep2Vector(this);
        return tot > 0.0 ? p.timesAssign(1.0 / sqrt(tot)) : new Hep2Vector(1, 0);
    }

    public Hep2Vector orthogonal() {
        final double x1 = fabs(dx), y1 = fabs(dy);
        if (x1 < y1) {
            return new Hep2Vector(dy, -dx);
        } else {
            return new Hep2Vector(-dy, dx);
        }
    }

    public double dot(Hep2Vector p) {
        return dx * p.x() + dy * p.y();
    }

    public double angle(Hep2Vector q) {
        final double ptot2 = mag2() * q.mag2();
        return ptot2 <= 0.0 ? 0.0 : acos(dot(q) / sqrt(ptot2));
    }

    public void rotate(double angler) {
        final double s1 = sin(angler);
        final double c = cos(angler);
        final double xx = dx;
        dx = c * xx - s1 * dy;
        dy = s1 * xx + c * dy;
    }

    /** operator Hep3Vector: (x, y, 0). */
    public Hep3Vector toHep3Vector() {
        return new Hep3Vector(dx, dy, 0.0);
    }

    public Hep2Vector plus(Hep2Vector b) {
        return new Hep2Vector(x() + b.x(), y() + b.y());
    }

    public Hep2Vector minus(Hep2Vector b) {
        return new Hep2Vector(x() - b.x(), y() - b.y());
    }

    public Hep2Vector times(double a) {
        return new Hep2Vector(a * x(), a * y());
    }

    /** p / a: each component divided (not multiplied by 1/a, as Hep3Vector does). */
    public Hep2Vector div(double a) {
        if (a == 0) {
            ZMxpv.throwA(ZMxpv.INFINITE_VECTOR, "Division of Hep2Vector by zero", "operator/(Hep2Vector, double)");
        }
        return new Hep2Vector(x() / a, y() / a);
    }

    /** operator&lt;&lt;: "(x, y)". */
    public COStream print(COStream os) {
        return os.put("(").put(x()).put(", ").put(y()).put(")");
    }

    public static CIStream read(CIStream is, Hep2Vector p) {
        final double[] c = new double[2];
        ZMinput.input2doubles(is, "Hep2Vector", c);
        p.set(c[0], c[1]);
        return is;
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}
