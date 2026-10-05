package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.List;

/**
 * The contour of two parameters where the function, minimized over the
 * others, is the minimum + Up: four points from Minos, then each new point
 * found by Minos-like crossing across the largest gap of the polygon.
 */
public final class MnContours {

    private final FCNBase fcn;
    private final FunctionMinimum minimum;
    private final MnStrategy strategy;

    public MnContours(FCNBase fcn, FunctionMinimum min) {
        this(fcn, min, new MnStrategy(1));
    }

    public MnContours(FCNBase fcn, FunctionMinimum min, int stra) {
        this(fcn, min, new MnStrategy(stra));
    }

    public MnContours(FCNBase fcn, FunctionMinimum min, MnStrategy stra) {
        this.fcn = fcn;
        this.minimum = min;
        this.strategy = new MnStrategy(stra);
    }

    public MnStrategy strategy() {
        return strategy;
    }

    /** The points of the contour of px and py. */
    public List<MnPrint.Point> points(int px, int py, int npoints) {
        return contour(px, py, npoints).points();
    }

    public List<MnPrint.Point> points(int px, int py) {
        return points(px, py, 20);
    }

    public ContoursError contour(int px, int py) {
        return contour(px, py, 20);
    }

    public ContoursError contour(int px, int py, int npoints) {
        if (npoints <= 3) throw new IllegalArgumentException("a contour needs more than 3 points");
        final int maxcalls = 100 * (npoints + 5) * (minimum.userState().variableParameters() + 1);
        final int[] nfcn = {0};
        final MnPrint print = new MnPrint("MnContours");
        print.debug("MnContours: finding ", npoints, " contours points for ", px, py, " at level ", fcn.up(), " from value ",
            minimum.fval());
        final List<MnPrint.Point> result = new ArrayList<>(npoints);
        final double toler = 0.1;
        final MnMinos minos = new MnMinos(fcn, minimum, strategy);
        final double valx = minimum.userState().value(px);
        final double valy = minimum.userState().value(py);
        print.debug("Run Minos to find first 4 contour points. Current minimum is : ", valx, valy);
        final MinosError mnex = minos.minos(px);
        nfcn[0] += mnex.nfcn();
        if (!mnex.isValid()) {
            print.error("unable to find first two points");
            return new ContoursError(px, py, result, mnex, mnex, nfcn[0]);
        }
        final double[] ex = mnex.pair();
        print.debug("Minos error for p0:  ", ex[0], ex[1]);
        final MinosError mney = minos.minos(py);
        nfcn[0] += mney.nfcn();
        if (!mney.isValid()) {
            print.error("unable to find second two points");
            return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
        }
        final double[] ey = mney.pair();
        print.debug("Minos error for p0:  ", ey[0], ey[1]);
        final MnMigrad migrad0 = new MnMigrad(fcn, minimum.userState(), strategy.nextLower());
        final MnUserParameterState ustate = new MnUserParameterState(minimum.userState());
        final MnFunctionCross cross1 = new MnFunctionCross(fcn, ustate, minimum.fval(), strategy);

        // findCrossValue: the crossing for one parameter along a direction, the other fixed
        final class Finder {
            boolean status;

            double findCrossValue(int ipar, double startValue, double direction) {
                final int[] vpar = {ipar};
                final double[] vmid = {startValue};
                final double[] vdir = {direction};
                ustate.fix(ipar);
                final MnCross crossResult = cross1.cross(vpar, vmid, vdir, toler, maxcalls);
                status = crossResult.isValid();
                print.debug("result of findCrossValue for par", ipar, "status: ", status, " searching from ", vmid[0], "dir",
                    vdir[0], " -> ", crossResult.value(), " fcn = ", crossResult.state().fval());
                return status ? vmid[0] + crossResult.value() * vdir[0] : 0;
            }

            List<Double> findContourPointsAtBorder(int p1, int p2, double p1Limit, FunctionMinimum minp2, boolean order) {
                ustate.setValue(p1, p1Limit);
                ustate.fix(p1);
                final double deltaFCN = minimum.fval() + fcn.up() - minp2.userState().fval();
                final double pmid = minp2.userState().value(p2);
                final double pdir = minp2.userState().error(p2) * deltaFCN / fcn.up();
                final double y1 = findCrossValue(p2, pmid, pdir);
                final boolean ret1 = status;
                final double y2 = findCrossValue(p2, pmid, -pdir);
                final boolean ret2 = status;
                final List<Double> yvalues = new ArrayList<>(2);
                if (!ret1 && !ret2) return yvalues;
                if (ret1 && !ret2) {
                    yvalues.add(y1);
                } else if (!ret1 && ret2) {
                    yvalues.add(y2);
                } else {
                    if (Math.abs(y1 - y2) > 0.0001 * minimum.userState().error(p2)) {
                        final int orderDir = order ? 1 : -1;
                        if (orderDir * (y2 - y1) > 0) {
                            yvalues.add(y1);
                            yvalues.add(y2);
                        } else {
                            yvalues.add(y2);
                            yvalues.add(y1);
                        }
                    } else {
                        yvalues.add(y1);
                    }
                }
                print.debug("Found contour point at the border: ", ustate.value(p1), " , ", yvalues.get(0));
                if (yvalues.size() > 1) print.debug("Found additional point at : ", ustate.value(p1), " , ", yvalues.get(1));
                return yvalues;
            }
        }
        final Finder finder = new Finder();

        List<Double> yvaluesXlo = new ArrayList<>(List.of(0.));
        migrad0.state().fix(px);
        migrad0.state().setValue(px, valx + ex[0]);
        final FunctionMinimum exyLo = migrad0.minimize();
        nfcn[0] += exyLo.nfcn();
        if (!exyLo.isValid()) {
            print.error("unable to find Lower y Value for x Parameter", px);
            return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
        }
        yvaluesXlo.set(0, exyLo.userState().value(py));
        print.debug("Minimum fcn value for px set to ", valx + ex[0], " is at py = ", yvaluesXlo.get(0), " fcn = ",
            exyLo.userState().fval());
        if (mnex.atLowerLimit()) {
            yvaluesXlo = finder.findContourPointsAtBorder(px, py, ustate.parameter(px).lowerLimit(), exyLo, false);
            if (yvaluesXlo.isEmpty()) {
                print.error("unable to find corresponding value for ", py, "when Parameter", px, "is at lower limit: ",
                    ustate.value(px));
                return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
            }
        }
        List<Double> yvaluesXup = new ArrayList<>(List.of(0.));
        migrad0.state().setValue(px, valx + ex[1]);
        migrad0.state().fix(px);
        final FunctionMinimum exyUp = migrad0.minimize();
        nfcn[0] += exyUp.nfcn();
        if (!exyUp.isValid()) {
            print.error("unable to find Upper y Value for x Parameter", px);
            return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
        }
        yvaluesXup.set(0, exyUp.userState().value(py));
        print.debug("Minimum fcn value for px set to ", valx + ex[1], " is at py = ", yvaluesXup.get(0), " fcn = ",
            exyUp.userState().fval());
        if (mnex.atUpperLimit()) {
            yvaluesXup = finder.findContourPointsAtBorder(px, py, ustate.parameter(px).upperLimit(), exyUp, true);
            if (yvaluesXup.isEmpty()) {
                print.error("unable to find corresponding value for ", py, "when Parameter", px, "is at upper limit: ",
                    ustate.value(px));
                return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
            }
        }
        final MnMigrad migrad1 = new MnMigrad(fcn, minimum.userState(), strategy.nextLower());
        migrad1.state().fix(py);
        List<Double> xvaluesYlo = new ArrayList<>(List.of(0.));
        migrad1.state().setValue(py, valy + ey[0]);
        final FunctionMinimum eyxLo = migrad1.minimize();
        nfcn[0] += eyxLo.nfcn();
        if (!eyxLo.isValid()) {
            print.error("unable to find Lower x Value for y Parameter", py);
            return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
        }
        xvaluesYlo.set(0, eyxLo.userState().value(px));
        print.debug("Minimum fcn value for py set to ", valy + ey[0], " is at px = ", xvaluesYlo.get(0), " fcn = ",
            eyxLo.userState().fval());
        if (mney.atLowerLimit()) {
            xvaluesYlo = finder.findContourPointsAtBorder(py, px, ustate.parameter(py).lowerLimit(), eyxLo, true);
            if (xvaluesYlo.isEmpty()) {
                print.error("unable to find corresponding value for ", px, "when Parameter", py, "is at lower limit: ",
                    ustate.value(py));
                return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
            }
        }
        List<Double> xvaluesYup = new ArrayList<>(List.of(0.));
        migrad1.state().fix(py);
        migrad1.state().setValue(py, valy + ey[1]);
        final FunctionMinimum eyxUp = migrad1.minimize();
        nfcn[0] += eyxUp.nfcn();
        if (!eyxUp.isValid()) {
            print.error("unable to find Upper x Value for y Parameter", py);
            return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
        }
        xvaluesYup.set(0, eyxUp.userState().value(px));
        print.debug("Minimum fcn value for py set to ", valy + ey[1], " is at px = ", xvaluesYup.get(0), " fcn = ",
            eyxUp.userState().fval());
        if (mney.atUpperLimit()) {
            xvaluesYup = finder.findContourPointsAtBorder(py, px, ustate.parameter(py).upperLimit(), eyxUp, false);
            if (xvaluesYup.isEmpty()) {
                print.error("unable to find corresponding value for ", px, "when Parameter", py, "is at upper limit: ",
                    ustate.value(py));
                return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
            }
        }
        result.add(new MnPrint.Point(valx + ex[0], yvaluesXlo.get(0)));
        if (yvaluesXlo.size() == 2) result.add(new MnPrint.Point(valx + ex[0], yvaluesXlo.get(1)));
        result.add(new MnPrint.Point(xvaluesYlo.get(0), valy + ey[0]));
        if (xvaluesYlo.size() == 2) result.add(new MnPrint.Point(xvaluesYlo.get(1), valy + ey[0]));
        result.add(new MnPrint.Point(valx + ex[1], yvaluesXup.get(0)));
        if (yvaluesXup.size() == 2) result.add(new MnPrint.Point(valx + ex[1], yvaluesXup.get(1)));
        result.add(new MnPrint.Point(xvaluesYup.get(0), valy + ey[1]));
        if (xvaluesYup.size() == 2) result.add(new MnPrint.Point(xvaluesYup.get(1), valy + ey[1]));
        final MnUserParameterState upar = new MnUserParameterState(minimum.userState());
        print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
            os.put("List of first ").put(result.size()).put(" points found: \n");
            os.put("Parameters :   ").put(upar.name(px)).put("\t").put(upar.name(py)).endl();
            for (MnPrint.Point p : result) {
                p.print(os);
                os.endl();
            }
        });
        final double scalx = 1. / (ex[1] - ex[0]);
        final double scaly = 1. / (ey[1] - ey[0]);
        upar.fix(px);
        upar.fix(py);
        final int[] par = {px, py};
        final MnFunctionCross cross = new MnFunctionCross(fcn, upar, minimum.fval(), strategy);
        final int np0 = result.size();
        for (int i = np0; i < npoints; i++) {
            int idist1 = result.size() - 1;
            int idist2 = 0;
            final double dx = result.get(idist1).x() - result.get(idist2).x();
            final double dy = result.get(idist1).y() - result.get(idist2).y();
            double bigdis = scalx * scalx * dx * dx + scaly * scaly * dy * dy;
            for (int ipair = 0; ipair < result.size() - 1; ++ipair) {
                final MnPrint.Point a = result.get(ipair), b = result.get(ipair + 1);
                final double distx = a.x() - b.x();
                final double disty = a.y() - b.y();
                double dist = scalx * scalx * distx * distx + scaly * scaly * disty * disty;
                if (distx == 0. && upar.parameter(px).hasLimits()
                    && (a.x() == upar.parameter(px).lowerLimit() || a.x() == upar.parameter(px).upperLimit())) {
                    dist = 0;
                }
                if (disty == 0. && upar.parameter(py).hasLimits()
                    && (a.y() == upar.parameter(py).lowerLimit() || a.y() == upar.parameter(py).upperLimit())) {
                    dist = 0;
                }
                if (dist > bigdis) {
                    bigdis = dist;
                    idist1 = ipair;
                    idist2 = ipair + 1;
                }
            }
            double a1 = 0.5;
            double a2 = 0.5;
            final double sca = 1.;
            boolean validPoint = false;
            do {
                if (nfcn[0] > maxcalls) {
                    print.error("maximum number of function calls exhausted");
                    return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
                }
                final MnPrint.Point p1 = result.get(idist1), p2 = result.get(idist2);
                print.debug("Find new contour point between points with max sep:  (", p1.x(), ", ", p1.y(), ") and (", p2.x(),
                    ", ", p2.y(), ")  with weights ", a1, a2);
                final double xmidcr = a1 * p1.x() + a2 * p2.x();
                final double ymidcr = a1 * p1.y() + a2 * p2.y();
                final double xdir = p2.y() - p1.y();
                final double ydir = p1.x() - p2.x();
                final double scalfac = sca * Cxx.max(Math.abs(xdir * scalx), Math.abs(ydir * scaly));
                final double xdircr = xdir / scalfac;
                final double ydircr = ydir / scalfac;
                final double[] pmid = {xmidcr, ymidcr};
                final double[] pdir = {xdircr, ydircr};
                print.debug("calling MnCross with pmid: ", pmid[0], pmid[1], "and  pdir ", pdir[0], pdir[1]);
                final MnCross opt = cross.cross(par, pmid, pdir, toler, maxcalls);
                nfcn[0] += opt.nfcn();
                if (!opt.isValid() || opt.atLimit()) {
                    if (a1 < 0.3) {
                        print.info("Unable to find point on Contour", i + 1, '\n', "found only", i, "points");
                        return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
                    } else if (a1 > 0.5) {
                        a1 = 0.25;
                        a2 = 0.75;
                        print.debug("Unable to find point, try closer to p2 with weight values", a1, a2);
                    } else {
                        a1 = 0.75;
                        a2 = 0.25;
                        print.debug("Unable to find point, try closer to p1 with weight values", a1, a2);
                    }
                } else {
                    final double aopt = opt.value();
                    int pos = result.size();
                    final MnPrint.Point np = new MnPrint.Point(xmidcr + (aopt) * xdircr, ymidcr + (aopt) * ydircr);
                    if (idist2 == 0) {
                        result.add(np);
                        print.info(result.get(result.size() - 1));
                    } else {
                        print.info(result.get(idist2));
                        result.add(idist2, np);
                        pos = idist2;
                    }
                    print.info("Found new point - pos: ", pos, result.get(pos), "fcn = ", opt.state().fval());
                    validPoint = true;
                }
            } while (!validPoint);
        }
        print.info("Number of contour points =", result.size());
        return new ContoursError(px, py, result, mnex, mney, nfcn[0]);
    }
}
