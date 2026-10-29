package com.sphere.core.clhep.genfun;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepVector;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Genfun::CubicSplinePolynomial (CubicSplinePolynomial.hh, .icc): the natural cubic spline through
 * the points added, its slopes solved for (by inverting the tridiagonal system as a full HepMatrix) when
 * first evaluated after a change; straight lines beyond the end points.
 */
public class CubicSplinePolynomial extends AbsFunction {

    /** A point, std::pair&lt;double,double&gt;, ordered as pairs are. */
    private record Point(double x, double y) {
        boolean lessThan(Point o) {
            return x < o.x || (!(o.x < x) && y < o.y);
        }
    }

    private final List<Point> xPoints = new ArrayList<>();
    private boolean stale = true;
    private HepVector Q = new HepVector();

    public CubicSplinePolynomial() {
    }

    public CubicSplinePolynomial(CubicSplinePolynomial right) {
        xPoints.addAll(right.xPoints);
        stale = right.stale;
        Q = new HepVector(right.Q);
    }

    @Override
    public CubicSplinePolynomial copy() {
        return new CubicSplinePolynomial(this);
    }

    private void computeSlopes() {
        final int N = xPoints.size() - 1;
        final HepMatrix A = new HepMatrix(N + 1, N + 1, 0);
        final HepVector V = new HepVector(N + 1, 0);
        for (int i = 1; i < N; i++) {
            final double dxPlus = xPoints.get(i + 1).x() - xPoints.get(i).x();
            final double dyPlus = xPoints.get(i + 1).y() - xPoints.get(i).y();
            final double mPlus = dyPlus / dxPlus;
            final double dx = xPoints.get(i).x() - xPoints.get(i - 1).x();
            final double dy = xPoints.get(i).y() - xPoints.get(i - 1).y();
            final double m = dy / dx;
            A.row(i).set(i - 1, 1 / dx);
            A.row(i).set(i, 2 * (1 / dxPlus + 1 / dx));
            A.row(i).set(i + 1, 1 / dxPlus);
            V.set(i + 1, 3 * (m / dx + mPlus / dxPlus));
        }
        {
            final double dx = xPoints.get(1).x() - xPoints.get(0).x();
            final double dy = xPoints.get(1).y() - xPoints.get(0).y();
            final double m = dy / dx;
            A.row(0).set(0, 2.0);
            A.row(0).set(1, 1);
            V.set(1, 3 * m);
        }
        {
            final double dx = xPoints.get(N).x() - xPoints.get(N - 1).x();
            final double dy = xPoints.get(N).y() - xPoints.get(N - 1).y();
            final double m = dy / dx;
            A.row(N).set(N - 1, 1.0);
            A.row(N).set(N, 2.0);
            V.set(N + 1, 3 * m);
        }
        final int[] err = new int[1];
        Q = A.inverse(err).times(V);
    }

    @Override
    public double call(double x) {
        final int N = xPoints.size() - 1;
        if (xPoints.isEmpty()) return 0;
        if (xPoints.size() == 1) return xPoints.get(0).y();
        if (stale) {
            computeSlopes();
            stale = false;
        }
        final int i = lowerBound(new Point(x, 0));
        if (i == 0) {
            final double x0 = xPoints.get(0).x();
            final double y0 = xPoints.get(0).y();
            final double m = Q.get(1);
            return y0 + m * (x - x0);
        } else if (i == xPoints.size()) {
            final double x0 = xPoints.get(N).x();
            final double y0 = xPoints.get(N).y();
            final double m = Q.get(N + 1);
            return y0 + m * (x - x0);
        } else {
            final double x0 = xPoints.get(i - 1).x();
            final double x1 = xPoints.get(i).x();
            final double y0 = xPoints.get(i - 1).y();
            final double y1 = xPoints.get(i).y();
            final double dx = x1 - x0;
            final double dy = y1 - y0;
            final double m = dy / dx;
            final double Q0 = Q.get(i);
            final double Q1 = Q.get(i + 1);
            final double a = (Q0 - m) * dx;
            final double b = (m - Q1) * dx;
            final double t = (x - x0) / dx;
            final double u = (1 - t);
            return u * y0 + t * y1 + t * u * (u * a + t * b);
        }
    }

    /** std::lower_bound, as libstdc++ bisects. */
    private int lowerBound(Point val) {
        int first = 0;
        int len = xPoints.size();
        while (len > 0) {
            final int half = len >> 1;
            final int middle = first + half;
            if (xPoints.get(middle).lessThan(val)) {
                first = middle + 1;
                len = len - half - 1;
            } else {
                len = half;
            }
        }
        return first;
    }

    @Override
    public double call(Argument a) {
        return call(a.get(0));
    }

    /** Adds a point and keeps the points sorted. */
    public void addPoint(double x, double y) {
        xPoints.add(new Point(x, y));
        xPoints.sort((p, q) -> p.lessThan(q) ? -1 : q.lessThan(p) ? 1 : 0);
        stale = true;
    }

    /** {min, max} of the abscissas, {DBL_MAX, -DBL_MAX} without points. */
    public double[] getRange() {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Point p : xPoints) {
            min = CMath.min(min, p.x());
            max = CMath.max(max, p.x());
        }
        return new double[] {min, max};
    }
}
