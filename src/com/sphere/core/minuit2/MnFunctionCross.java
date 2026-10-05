package com.sphere.core.minuit2;

/**
 * Finds where the function, minimized over the other parameters, crosses
 * the minimum + Up along a line pmid + a pdir of fixed parameters: Migrad at
 * successive values of a, straight lines then parabolas through the points,
 * to a tolerance of 0.01 (MnCross holds a).
 */
public final class MnFunctionCross {

    private final FCNBase fcn;
    private final MnUserParameterState state;
    private final double fval;
    private final MnStrategy strategy;

    public MnFunctionCross(FCNBase fcn, MnUserParameterState state, double fval, MnStrategy stra) {
        this.fcn = fcn;
        this.state = state;
        this.fval = fval;
        this.strategy = stra;
    }

    public MnCross cross(int[] par, double[] pmid, double[] pdir, double tlr, int maxcalls) {
        final int npar = par.length;
        int nfcn = 0;
        final MnMachinePrecision prec = state.precision();
        final double mgrTlr = 0.5 * tlr;
        tlr = 0.01;
        final double up = fcn.up();
        final double tlf = tlr * up;
        double tla = tlr;
        final int maxitr = 30;
        int ipt = 0;
        final double aminsv = fval;
        final double aim = aminsv + up;
        double aopt = 0.;
        boolean limset = false;
        final double[] alsb = {0., 0., 0.};
        final double[] flsb = {0., 0., 0.};
        final MnPrint print = new MnPrint("MnFunctionCross");
        print.debug((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
            for (int i = 0; i < par.length; ++i) {
                os.put("Parameter ").put(par[i]).put(" value ").put(pmid[i]).put(" dir ").put(pdir[i])
                    .put(" function min = ").put(aminsv).put(" contour value aim = (fmin + up) = ").put(aim);
            }
        });
        double aulim = 100.;
        for (int i = 0; i < par.length; i++) {
            final int kex = par[i];
            final MinuitParameter p = state.parameter(kex);
            if (p.hasLimits()) {
                final double zmid = pmid[i];
                final double zdir = pdir[i];
                if (zdir > 0. && p.hasUpperLimit()) {
                    final double zlim = p.upperLimit();
                    if (Math.abs(zdir) < state.precision().eps()) {
                        if (Math.abs(zlim - zmid) < state.precision().eps()) limset = true;
                        continue;
                    }
                    aulim = Cxx.min(aulim, (zlim - zmid) / zdir);
                } else if (zdir < 0. && p.hasLowerLimit()) {
                    final double zlim = p.lowerLimit();
                    if (Math.abs(zdir) < state.precision().eps()) {
                        if (Math.abs(zlim - zmid) < state.precision().eps()) limset = true;
                        continue;
                    }
                    aulim = Cxx.min(aulim, (zlim - zmid) / zdir);
                }
            }
        }
        print.debug("Largest allowed aulim", aulim);
        if (limset && npar == 1) {
            print.warn("Parameter is at limit", pmid[0], "delta", pdir[0]);
            return new MnCross(state, nfcn, MnCross.Kind.PAR_LIMIT);
        }
        if (aulim < aopt + tla) limset = true;
        final MnMigrad migrad = new MnMigrad(fcn, state, strategy.nextLower());
        print.info((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
            os.put("Run Migrad with fixed parameters:");
            for (int i = 0; i < npar; ++i) os.put("\n  Pos ").put(par[i]).put(": ").put(state.name(par[i])).put(" = ").put(pmid[i]);
        });
        for (int i = 0; i < npar; i++) migrad.state().setValue(par[i], pmid[i]);
        final FunctionMinimum min0 = migrad.minimize(maxcalls, mgrTlr);
        nfcn += min0.nfcn();
        print.info("Result after Migrad", new MnPrint.Oneline(min0), min0.userState().parameters());
        if (min0.fval() < fval - tlf) {
            print.warn("New minimum found while scanning parameter", par[0], "new value =", min0.fval(), "old value =", fval);
            return new MnCross(min0.userState(), nfcn, MnCross.Kind.NEW_MIN);
        }
        if (min0.hasReachedCallLimit()) return new MnCross(min0.userState(), nfcn, MnCross.Kind.FCN_LIMIT);
        if (!min0.isValid()) return new MnCross(state, nfcn);
        if (limset && min0.fval() < aim) return new MnCross(min0.userState(), nfcn, MnCross.Kind.PAR_LIMIT);
        ipt++;
        alsb[0] = 0.;
        flsb[0] = min0.fval();
        flsb[0] = Cxx.max(flsb[0], aminsv + 0.1 * up);
        aopt = Math.sqrt(up / (flsb[0] - aminsv)) - 1.;
        if (Math.abs(flsb[0] - aim) < tlf) return new MnCross(aopt, min0.userState(), nfcn);
        if (aopt > 1.) aopt = 1.;
        if (aopt < -0.5) aopt = -0.5;
        limset = false;
        if (aopt > aulim) {
            aopt = aulim;
            limset = true;
        }
        print.debug("flsb[0]", flsb[0], "aopt", aopt);
        final double aopt1 = aopt;
        print.info((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
            os.put("Run Migrad again (2nd) with fixed parameters:");
            for (int i = 0; i < npar; ++i) {
                os.put("\n  Pos ").put(par[i]).put(": ").put(state.name(par[i])).put(" = ").put(pmid[i] + (aopt1) * pdir[i]);
            }
        });
        for (int i = 0; i < npar; i++) migrad.state().setValue(par[i], pmid[i] + (aopt) * pdir[i]);
        FunctionMinimum min1 = migrad.minimize(maxcalls, mgrTlr);
        nfcn += min1.nfcn();
        print.info("Result after 2nd Migrad", new MnPrint.Oneline(min1), min1.userState().parameters());
        if (min1.fval() < fval - tlf) {
            print.debug("A new minimum is found: return");
            return new MnCross(min1.userState(), nfcn, MnCross.Kind.NEW_MIN);
        }
        if (min1.hasReachedCallLimit()) {
            print.debug("FCN call limit is reached: return");
            return new MnCross(min1.userState(), nfcn, MnCross.Kind.FCN_LIMIT);
        }
        if (!min1.isValid()) {
            print.debug("Migrad failed: return ");
            return new MnCross(state, nfcn);
        }
        if (limset && min1.fval() < aim) {
            print.debug("Parameter(s) at limit: return ");
            return new MnCross(min1.userState(), nfcn, MnCross.Kind.PAR_LIMIT);
        }
        ipt++;
        alsb[1] = aopt;
        flsb[1] = min1.fval();
        double dfda = (flsb[1] - flsb[0]) / (alsb[1] - alsb[0]);
        print.debug("aopt", aopt, "min1Val", flsb[1], "dfda", dfda);
        FunctionMinimum min2 = null;
        int ibest = 2;
        // L300 / L460 / L500 of the C++, as a state machine
        int label = 300;
        while (true) {
            if (label == 300) {
                if (dfda < 0.) {
                    print.debug("dfda < 0 - iterate from", ipt, "to max of", maxitr);
                    // unsigned in the C++: past maxitr it does not stop the loop
                    final long maxlk = Integer.toUnsignedLong(maxitr - ipt);
                    for (long it = 0; it < maxlk; it++) {
                        alsb[0] = alsb[1];
                        flsb[0] = flsb[1];
                        aopt = alsb[0] + 0.2 * (it + 1);
                        limset = false;
                        if (aopt > aulim) {
                            aopt = aulim;
                            limset = true;
                        }
                        final double aoptI = aopt;
                        final long itI = it;
                        print.info((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                            os.put("Run Migrad again (iteration ").put(itI).put(" ) :");
                            for (int i = 0; i < npar; ++i) {
                                os.put("\n  parameter ").put(par[i]).put(" (").put(state.name(par[i])).put(") fixed to ")
                                    .put(pmid[i] + (aoptI) * pdir[i]);
                            }
                        });
                        for (int i = 0; i < npar; i++) migrad.state().setValue(par[i], pmid[i] + (aopt) * pdir[i]);
                        min1 = migrad.minimize(maxcalls, mgrTlr);
                        nfcn += min1.nfcn();
                        print.info("Result after Migrad", new MnPrint.Oneline(min1), '\n', min1.userState().parameters());
                        if (min1.fval() < fval - tlf) {
                            print.debug("A new minimum is found: return");
                            return new MnCross(min1.userState(), nfcn, MnCross.Kind.NEW_MIN);
                        }
                        if (min1.hasReachedCallLimit()) {
                            print.debug("FCN call limit is reached: return");
                            return new MnCross(min1.userState(), nfcn, MnCross.Kind.FCN_LIMIT);
                        }
                        if (!min1.isValid()) {
                            print.debug("Migrad failed: return ");
                            return new MnCross(state, nfcn);
                        }
                        if (limset && min1.fval() < aim) {
                            print.debug("Parameter(s) at limit: return ");
                            return new MnCross(min1.userState(), nfcn, MnCross.Kind.PAR_LIMIT);
                        }
                        ipt++;
                        alsb[1] = aopt;
                        flsb[1] = min1.fval();
                        dfda = (flsb[1] - flsb[0]) / (alsb[1] - alsb[0]);
                        print.debug("aopt", aopt, "min1Val", flsb[1], "dfda", dfda);
                        if (dfda > 0.) break;
                    }
                    if (ipt > maxitr) return new MnCross(state, nfcn);
                }
                label = 460;
            }
            if (label == 460) {
                aopt = alsb[1] + (aim - flsb[1]) / dfda;
                print.debug("dfda > 0 : aopt", aopt);
                final double fdist = Cxx.min(Math.abs(aim - flsb[0]), Math.abs(aim - flsb[1]));
                final double adist = Cxx.min(Math.abs(aopt - alsb[0]), Math.abs(aopt - alsb[1]));
                tla = tlr;
                if (Math.abs(aopt) > 1.) tla = tlr * Math.abs(aopt);
                if (adist < tla && fdist < tlf) {
                    print.info("Return: Found good value for aopt = ", aopt);
                    return new MnCross(aopt, min1.userState(), nfcn);
                }
                if (ipt > maxitr) {
                    print.info("Number of iterations", ipt, "larger than max", maxitr, ": return");
                    return new MnCross(state, nfcn);
                }
                final double bmin = Cxx.min(alsb[0], alsb[1]) - 1.;
                if (aopt < bmin) aopt = bmin;
                final double bmax = Cxx.max(alsb[0], alsb[1]) + 1.;
                if (aopt > bmax) aopt = bmax;
                limset = false;
                if (aopt > aulim) {
                    aopt = aulim;
                    limset = true;
                }
                final double aopt3 = aopt;
                print.info((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                    os.put("Run Migrad again (3rd) with fixed parameters:");
                    for (int i = 0; i < npar; ++i) {
                        os.put("\n  Pos ").put(par[i]).put(": ").put(state.name(par[i])).put(" = ").put(pmid[i] + (aopt3) * pdir[i]);
                    }
                });
                for (int i = 0; i < npar; i++) migrad.state().setValue(par[i], pmid[i] + (aopt) * pdir[i]);
                min2 = migrad.minimize(maxcalls, mgrTlr);
                nfcn += min2.nfcn();
                print.info("Result after Migrad (3rd):", new MnPrint.Oneline(min2), min2.userState().parameters());
                if (min2.fval() < fval - tlf) {
                    print.debug("A new minimum is found: return");
                    return new MnCross(min2.userState(), nfcn, MnCross.Kind.NEW_MIN);
                }
                if (min2.hasReachedCallLimit()) {
                    print.debug("FCN call limit is reached: return");
                    return new MnCross(min2.userState(), nfcn, MnCross.Kind.FCN_LIMIT);
                }
                if (!min2.isValid()) {
                    print.debug("Migrad failed: return ");
                    return new MnCross(state, nfcn);
                }
                if (limset && min2.fval() < aim) {
                    print.debug("Parameter(s) at limit: return ");
                    return new MnCross(min2.userState(), nfcn, MnCross.Kind.PAR_LIMIT);
                }
                ipt++;
                alsb[2] = aopt;
                flsb[2] = min2.fval();
                double ecarmn = Math.abs(flsb[2] - aim);
                double ecarmx = 0.;
                ibest = 2;
                int iworst = 0;
                int noless = 0;
                for (int i = 0; i < 3; i++) {
                    final double ecart = Math.abs(flsb[i] - aim);
                    if (ecart > ecarmx) {
                        ecarmx = ecart;
                        iworst = i;
                    }
                    if (ecart < ecarmn) {
                        ecarmn = ecart;
                        ibest = i;
                    }
                    if (flsb[i] < aim) noless++;
                }
                print.debug("have three points : noless < aim; noless", noless, "ibest", ibest, "iworst", iworst);
                if (noless == 1 || noless == 2) {
                    label = 500;
                } else if (noless == 0 && ibest != 2) {
                    print.debug("all 3 points are above - invalid result- return");
                    return new MnCross(state, nfcn);
                } else if (noless == 3 && ibest != 2) {
                    alsb[1] = alsb[2];
                    flsb[1] = flsb[2];
                    print.debug("All three points below - look again for positive slope");
                    label = 300;
                    continue;
                } else {
                    flsb[iworst] = flsb[2];
                    alsb[iworst] = alsb[2];
                    dfda = (flsb[1] - flsb[0]) / (alsb[1] - alsb[0]);
                    print.debug("New straight line using point 1-2; dfda", dfda);
                    label = 460;
                    continue;
                }
            }
            if (label == 500) {
                do {
                    final MnParabola parbol = MnParabola.through(new MnParabola.Point(alsb[0], flsb[0]),
                        new MnParabola.Point(alsb[1], flsb[1]), new MnParabola.Point(alsb[2], flsb[2]));
                    print.debug("Parabola fit: iteration", ipt);
                    final double coeff1 = parbol.c();
                    final double coeff2 = parbol.b();
                    final double coeff3 = parbol.a();
                    final double determ = coeff2 * coeff2 - 4. * coeff3 * (coeff1 - aim);
                    print.debug("Parabola fit: a =", coeff3, "b =", coeff2, "c =", coeff1, "determ =", determ);
                    if (determ < prec.eps()) return new MnCross(state, nfcn);
                    final double rt = Math.sqrt(determ);
                    final double x1 = (-coeff2 + rt) / (2. * coeff3);
                    final double x2 = (-coeff2 - rt) / (2. * coeff3);
                    final double s1 = coeff2 + 2. * x1 * coeff3;
                    final double s2 = coeff2 + 2. * x2 * coeff3;
                    print.debug("Parabola fit: x1", x1, "x2", x2, "s1", s1, "s2", s2);
                    if (s1 * s2 > 0.) print.warn("Problem 1");
                    aopt = x1;
                    double slope = s1;
                    if (s2 > 0.) {
                        aopt = x2;
                        slope = s2;
                    }
                    print.debug("Parabola fit: aopt", aopt, "slope", slope);
                    tla = tlr;
                    if (Math.abs(aopt) > 1.) tla = tlr * Math.abs(aopt);
                    print.debug("Delta(aopt)", Math.abs(aopt - alsb[ibest]), "tla", tla, "Delta(F)", Math.abs(flsb[ibest] - aim),
                        "tlf", tlf);
                    if (Math.abs(aopt - alsb[ibest]) < tla && Math.abs(flsb[ibest] - aim) < tlf) {
                        print.debug("Return: Found best value is within tolerance, aopt", aopt, "F=", flsb[ibest]);
                        return new MnCross(aopt, min2.userState(), nfcn);
                    }
                    int ileft = 3;
                    int iright = 3;
                    int iout = 3;
                    ibest = 0;
                    double ecarmx = 0.;
                    double ecarmn = Math.abs(aim - flsb[0]);
                    for (int i = 0; i < 3; i++) {
                        final double ecart = Math.abs(flsb[i] - aim);
                        if (ecart < ecarmn) {
                            ecarmn = ecart;
                            ibest = i;
                        }
                        if (ecart > ecarmx) ecarmx = ecart;
                        if (flsb[i] > aim) {
                            if (iright == 3) iright = i;
                            else if (flsb[i] > flsb[iright]) iout = i;
                            else {
                                iout = iright;
                                iright = i;
                            }
                        } else if (ileft == 3) {
                            ileft = i;
                        } else if (flsb[i] < flsb[ileft]) {
                            iout = i;
                        } else {
                            iout = ileft;
                            ileft = i;
                        }
                    }
                    print.debug("ileft", ileft, "iright", iright, "iout", iout, "ibest", ibest);
                    if (ecarmx > 10. * Math.abs(flsb[iout] - aim)) aopt = 0.5 * (aopt + 0.5 * (alsb[iright] + alsb[ileft]));
                    double smalla = 0.1 * tla;
                    if (slope * smalla > tlf) smalla = tlf / slope;
                    final double aleft = alsb[ileft] + smalla;
                    final double aright = alsb[iright] - smalla;
                    if (aopt < aleft) aopt = aleft;
                    if (aopt > aright) aopt = aright;
                    if (aleft > aright) aopt = 0.5 * (aleft + aright);
                    limset = false;
                    if (aopt > aulim) {
                        aopt = aulim;
                        limset = true;
                    }
                    final double aopt5 = aopt;
                    final int ipt5 = ipt;
                    print.info((java.util.function.Consumer<com.sphere.core.hepmc3.cxx.COStream>) os -> {
                        os.put("Run Migrad again at new point (#iter = ").put(ipt5 + 1).put(" ):");
                        for (int i = 0; i < npar; ++i) {
                            os.put("\n\t - parameter ").put(par[i]).put(" fixed to ").put(pmid[i] + (aopt5) * pdir[i]);
                        }
                    });
                    for (int i = 0; i < npar; i++) migrad.state().setValue(par[i], pmid[i] + (aopt) * pdir[i]);
                    min2 = migrad.minimize(maxcalls, mgrTlr);
                    nfcn += min2.nfcn();
                    print.info("Result after new Migrad:", new MnPrint.Oneline(min2), min2.userState().parameters());
                    if (min2.fval() < fval - tlf) {
                        print.debug("A new minimum is found: return");
                        return new MnCross(min2.userState(), nfcn, MnCross.Kind.NEW_MIN);
                    }
                    if (min2.hasReachedCallLimit()) {
                        print.debug("FCN call limit is reached: return");
                        return new MnCross(min2.userState(), nfcn, MnCross.Kind.FCN_LIMIT);
                    }
                    if (!min2.isValid()) {
                        print.debug("Migrad failed: return ");
                        return new MnCross(state, nfcn);
                    }
                    if (limset && min2.fval() < aim) {
                        print.debug("Parameter(s) at limit: return ");
                        return new MnCross(min2.userState(), nfcn, MnCross.Kind.PAR_LIMIT);
                    }
                    ipt++;
                    alsb[iout] = aopt;
                    flsb[iout] = min2.fval();
                    ibest = iout;
                } while (ipt < maxitr);
                print.debug("Best point is not found: return invalid result after many trial", ipt);
                return new MnCross(state, nfcn);
            }
        }
    }
}
