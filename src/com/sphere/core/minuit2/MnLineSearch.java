package com.sphere.core.minuit2;

import com.sphere.core.minuit2.MnParabola.Point;

/**
 * The one-dimensional minimization along a direction: from the start and
 * the full step, parabolas through the best three points, with limits on how
 * far a new point may go, until the minimum along the line is bracketed
 * closely enough (at most 12 function calls).
 */
public final class MnLineSearch {

    /**
     * Searches along step from st, gdel being the slope s.g at st; returns
     * (x, f): the fraction of the step and the function value there.
     */
    public Point search(MnFcn fcn, MinimumParameters st, LAVector step, double gdel, MnMachinePrecision prec) {
        final MnPrint print = new MnPrint("MnLineSearch");
        print.debug("doing line search along given step and gdel = s^t*g = ", gdel);
        double overall = 1000.;
        double undral = -100.;
        final double toler = 0.05;
        double slamin = 0.;
        final double slambg = 5.;
        final double alpha = 2.;
        final int maxiter = 12;
        int niter = 1;
        final MnFcn.Caller fcnCaller = new MnFcn.Caller(fcn);
        for (int i = 0; i < step.size(); i++) {
            if (step.get(i) == 0) continue;
            final double ratio = Math.abs(st.vec().get(i) / step.get(i));
            if (slamin == 0) slamin = ratio;
            if (ratio < slamin) slamin = ratio;
        }
        if (Math.abs(slamin) < prec.eps()) slamin = prec.eps();
        slamin *= prec.eps2();
        final double f0 = st.fval();
        final double f1 = fcnCaller.call(MnMatrix.add(st.vec(), step));
        niter++;
        double fvmin = st.fval();
        double xvmin = 0.;
        if (f1 < f0) {
            fvmin = f1;
            xvmin = 1.;
        }
        double toler8 = toler;
        double slamax = slambg;
        double flast = f1;
        double slam = 1.;
        boolean iterate;
        Point p0 = new Point(0., f0);
        Point p1 = new Point(slam, flast);
        double f2 = 0.;
        do {
            iterate = false;
            print.trace("flast", flast, "f0", f0, "flast-f0", flast - f0, "slam", slam);
            double denom = 2. * (flast - f0 - gdel * slam) / (slam * slam);
            print.trace("denom", denom);
            if (denom != 0) {
                slam = -gdel / denom;
            } else {
                denom = -0.1 * gdel;
                slam = 1.;
            }
            print.trace("new slam", slam);
            if (slam < 0.) {
                print.trace("slam is negative - set to", slamax);
                slam = slamax;
            }
            if (slam > slamax) {
                slam = slamax;
                print.trace("slam larger than max value - set to", slamax);
            }
            if (slam < toler8) {
                print.trace("slam too small - set to", toler8);
                slam = toler8;
            }
            if (slam < slamin) {
                print.trace("slam smaller than", slamin, "return");
                return new Point(xvmin, fvmin);
            }
            if (Math.abs(slam - 1.) < toler8 && p1.y() < p0.y()) {
                return new Point(xvmin, fvmin);
            }
            if (Math.abs(slam - 1.) < toler8) slam = 1. + toler8;
            f2 = fcnCaller.call(MnMatrix.addScaled(st.vec(), slam, step));
            niter++;
            if (f2 < fvmin) {
                fvmin = f2;
                xvmin = slam;
            }
            if (Math.abs(p0.y() - fvmin) < Math.abs(fvmin) * prec.eps()) {
                iterate = true;
                flast = f2;
                toler8 = toler * slam;
                overall = slam - toler8;
                slamax = overall;
                p1 = new Point(slam, flast);
            }
        } while (iterate && niter < maxiter);
        if (niter >= maxiter) {
            return new Point(xvmin, fvmin);
        }
        print.trace("after initial 2-point iter:", '\n', " x0, x1, x2:", p0.x(), p1.x(), slam, '\n', " f0, f1, f2:", p0.y(),
            p1.y(), f2);
        Point p2 = new Point(slam, f2);
        do {
            slamax = Cxx.max(slamax, alpha * Math.abs(xvmin));
            final MnParabola pb = MnParabola.through(p0, p1, p2);
            print.trace("Iteration", niter, '\n', " x0, x1, x2:", p0.x(), p1.x(), p2.x(), '\n', " f0, f1, f2:", p0.y(),
                p1.y(), p2.y(), '\n', " slamax    :", slamax, '\n', " p2-p0,p1  :", p2.y() - p0.y(), p2.y() - p1.y(),
                '\n', " a, b, c   :", pb.a(), pb.b(), pb.c());
            if (pb.a() < prec.eps2()) {
                final double slopem = 2. * pb.a() * xvmin + pb.b();
                if (slopem < 0.) slam = xvmin + slamax;
                else slam = xvmin - slamax;
                print.trace("xvmin", xvmin, "slopem", slopem, "slam", slam);
            } else {
                slam = pb.min();
                if (slam > xvmin + slamax) slam = xvmin + slamax;
                if (slam < xvmin - slamax) slam = xvmin - slamax;
            }
            if (slam > 0.) {
                if (slam > overall) slam = overall;
            } else {
                if (slam < undral) slam = undral;
            }
            print.debug("slam", slam, "undral", undral, "overall", overall);
            double f3 = 0.;
            do {
                print.trace("iterate on f3- slam", niter, "slam", slam, "xvmin", xvmin);
                iterate = false;
                final double toler9 = Cxx.max(toler8, Math.abs(toler8 * slam));
                if (Math.abs(p0.x() - slam) < toler9 || Math.abs(p1.x() - slam) < toler9 || Math.abs(p2.x() - slam) < toler9) {
                    return new Point(xvmin, fvmin);
                }
                f3 = fcnCaller.call(MnMatrix.addScaled(st.vec(), slam, step));
                print.trace("f3", f3, "f3-p(2-0).Y()", f3 - p2.y(), f3 - p1.y(), f3 - p0.y());
                if (f3 > p0.y() && f3 > p1.y() && f3 > p2.y()) {
                    print.trace("f3 worse than all three previous");
                    if (slam > xvmin) overall = Cxx.min(overall, slam - toler8);
                    if (slam < xvmin) undral = Cxx.max(undral, slam + toler8);
                    slam = 0.5 * (slam + xvmin);
                    print.trace("new slam", slam);
                    iterate = true;
                    niter++;
                }
            } while (iterate && niter < maxiter);
            if (niter >= maxiter) {
                print.debug("Exhausted max number of iterations ", maxiter, " return");
                return new Point(xvmin, fvmin);
            }
            final Point p3 = new Point(slam, f3);
            if (p0.y() > p1.y() && p0.y() > p2.y()) p0 = p3;
            else if (p1.y() > p0.y() && p1.y() > p2.y()) p1 = p3;
            else p2 = p3;
            print.trace("f3", f3, "fvmin", fvmin, "xvmin", xvmin);
            if (f3 < fvmin) {
                fvmin = f3;
                xvmin = slam;
            } else {
                if (slam > xvmin) overall = Cxx.min(overall, slam - toler8);
                if (slam < xvmin) undral = Cxx.max(undral, slam + toler8);
            }
            niter++;
        } while (niter < maxiter);
        print.debug("f1, f2 =", p0.y(), p1.y(), '\n', "x1, x2 =", p0.x(), p1.x(), '\n', "x, f =", xvmin, fvmin);
        return new Point(xvmin, fvmin);
    }
}
