package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A four-momentum with what is known about where it came from: a particle, or
 * a jet made of particles. The counterpart of fastjet::PseudoJet.
 *
 * The momentum is held as four double-double components, so a jet summed from
 * thousands of particles keeps 106 bits in each, and the rapidity and azimuth
 * are computed from them to 2^-106 and kept. The plain accessors
 * ({@link #rap()}, {@link #phi()}, ...) return the double nearest those values;
 * the {@code ...DD()} ones return all of it.
 *
 * A jet made under {@link Precision#DOUBLE} holds no low parts and computes
 * its rapidity and azimuth with the formulas of the C++, operation for
 * operation, so that clustering in that mode reproduces FastJet exactly.
 *
 * Unlike the C++ class this is a reference type: a ClusterSequence hands out
 * copies, so changing a jet one has been given never changes the clustering.
 */
public class PseudoJet {

    /** The rapidity given to a massless particle along the beam. */
    public static final double MAX_RAP = 1e5;
    static final double INVALID_PHI = -100.0;
    static final double INVALID_RAP = -1e200;
    static final double PI = Math.PI;
    static final double TWOPI = 2.0 * Math.PI;

    // The momentum: high parts, then low parts (zero under DOUBLE).
    double px, py, pz, e;
    double pxL, pyL, pzL, eL;
    // kt^2, the rapidity and the azimuth, as pairs.
    double kt2, kt2L;
    double phi = INVALID_PHI, phiL;
    double rap = INVALID_RAP, rapL;
    /** True when the rapidity and azimuth were set rather than computed. */
    boolean explicitRapPhi;
    /** The arithmetic of this jet's kinematics. */
    boolean dd;

    int clusterHistIndex = -1;
    int userIndex = -1;
    PseudoJetStructure structure;
    Object userInfo;

    /* ------------------------------------------------------------------ */
    /* Construction                                                        */
    /* ------------------------------------------------------------------ */

    /** The zero four-vector. */
    public PseudoJet() {
        this(0.0, 0.0, 0.0, 0.0);
    }

    public PseudoJet(double px, double py, double pz, double e) {
        this(px, py, pz, e, Precision.defaultPrecision());
    }

    public PseudoJet(double px, double py, double pz, double e, Precision precision) {
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.e = e;
        this.dd = precision == Precision.DD;
        finishInit();
    }

    /** A four-momentum given to 106 bits. */
    public PseudoJet(DD px, DD py, DD pz, DD e) {
        this.px = px.hi;
        this.pxL = px.lo;
        this.py = py.hi;
        this.pyL = py.lo;
        this.pz = pz.hi;
        this.pzL = pz.lo;
        this.e = e.hi;
        this.eL = e.lo;
        this.dd = true;
        finishInit();
    }

    /** A copy, sharing the structure and the user information. */
    public PseudoJet(PseudoJet other) {
        copyFrom(other);
    }

    public PseudoJet copy() {
        return new PseudoJet(this);
    }

    final void copyFrom(PseudoJet o) {
        px = o.px; py = o.py; pz = o.pz; e = o.e;
        pxL = o.pxL; pyL = o.pyL; pzL = o.pzL; eL = o.eL;
        kt2 = o.kt2; kt2L = o.kt2L;
        phi = o.phi; phiL = o.phiL;
        rap = o.rap; rapL = o.rapL;
        explicitRapPhi = o.explicitRapPhi;
        dd = o.dd;
        clusterHistIndex = o.clusterHistIndex;
        userIndex = o.userIndex;
        structure = o.structure;
        userInfo = o.userInfo;
    }

    /** A massless or massive particle from pt, rapidity, azimuth and mass. */
    public static PseudoJet ptYPhiM(double pt, double y, double phi, double m) {
        PseudoJet mom = new PseudoJet();
        mom.resetMomentumPtYPhiM(pt, y, phi, m);
        return mom;
    }

    public static PseudoJet ptYPhiM(double pt, double y, double phi) {
        return ptYPhiM(pt, y, phi, 0.0);
    }

    /** The same under a given precision. */
    public static PseudoJet ptYPhiM(double pt, double y, double phi, double m, Precision precision) {
        PseudoJet mom = new PseudoJet(0, 0, 0, 0, precision);
        mom.resetMomentumPtYPhiM(pt, y, phi, m);
        return mom;
    }

    /** From pt, rapidity, azimuth and mass given to 106 bits. */
    public static PseudoJet ptYPhiM(DD pt, DD y, DD phi, DD m) {
        PseudoJet mom = new PseudoJet(0, 0, 0, 0, Precision.DD);
        mom.resetMomentumPtYPhiMDD(pt, y, phi, m);
        return mom;
    }

    /* ------------------------------------------------------------------ */
    /* The arithmetic                                                      */
    /* ------------------------------------------------------------------ */

    public Precision precision() {
        return dd ? Precision.DD : Precision.DOUBLE;
    }

    /**
     * Changes the arithmetic of this jet. Going to DOUBLE drops the low parts;
     * a rapidity and azimuth that were set explicitly are kept.
     */
    public PseudoJet setPrecision(Precision precision) {
        final boolean wantDD = precision == Precision.DD;
        if (wantDD == dd) {
            return this;
        }
        dd = wantDD;
        if (!dd) {
            pxL = pyL = pzL = eL = 0.0;
        }
        final boolean keep = explicitRapPhi;
        final double r = rap, rl = rapL, f = phi, fl = phiL;
        finishInit();
        if (keep) {
            rap = r;
            phi = f;
            rapL = dd ? rl : 0.0;
            phiL = dd ? fl : 0.0;
            explicitRapPhi = true;
        }
        return this;
    }

    /** The standard end of every change of momentum. */
    final void finishInit() {
        if (dd) {
            final double a = px * px;
            final double aErr = DD.twoProdErr(px, px, a) + 2.0 * px * pxL + pxL * pxL;
            final double b = py * py;
            final double bErr = DD.twoProdErr(py, py, b) + 2.0 * py * pyL + pyL * pyL;
            final double s = a + b;
            double err = DD.twoSumErr(a, b, s) + aErr + bErr;
            final double t = s + err;
            kt2L = DD.quickTwoSumErr(s, err, t);
            kt2 = t;
        } else {
            kt2 = px * px + py * py;
            kt2L = 0.0;
        }
        phi = INVALID_PHI;
        phiL = 0.0;
        rap = INVALID_RAP;
        rapL = 0.0;
        explicitRapPhi = false;
    }

    final void ensureValidRapPhi() {
        if (phi == INVALID_PHI) {
            setRapPhi();
        }
    }

    private void setRapPhi() {
        if (dd) {
            setRapPhiDD();
            return;
        }
        // Exactly the C++, with correctly rounded atan2 and log.
        if (kt2 == 0.0) {
            phi = 0.0;
        } else {
            phi = CRMath.atan2(py, px);
        }
        if (phi < 0.0) {
            phi += TWOPI;
        }
        if (phi >= TWOPI) {
            phi -= TWOPI;
        }
        if (e == Math.abs(pz) && kt2 == 0) {
            final double maxRapHere = MAX_RAP + Math.abs(pz);
            rap = pz >= 0.0 ? maxRapHere : -maxRapHere;
        } else {
            final double effectiveM2 = Math.max(0.0, m2Double());
            final double ePlusPz = e + Math.abs(pz);
            rap = 0.5 * CRMath.log((kt2 + effectiveM2) / (ePlusPz * ePlusPz));
            if (pz > 0) {
                rap = -rap;
            }
        }
        phiL = 0.0;
        rapL = 0.0;
    }

    private void setRapPhiDD() {
        final DD PX = pxDD(), PY = pyDD(), PZ = pzDD(), EE = eDD();
        final DD KT2 = new DD(kt2, kt2L);
        DD f = (kt2 == 0.0 && kt2L == 0.0) ? DD.ZERO : DD.atan2(PY, PX);
        if (f.lt(0.0)) {
            f = f.add(DD.TWO_PI);
        }
        if (f.ge(DD.TWO_PI)) {
            f = f.sub(DD.TWO_PI);
        }
        phi = f.hi;
        phiL = f.lo;
        final DD absPz = PZ.abs();
        if (EE.equals(absPz) && kt2 == 0.0 && kt2L == 0.0) {
            DD r = absPz.add(MAX_RAP);
            if (PZ.signum() < 0) {
                r = r.neg();
            }
            rap = r.hi;
            rapL = r.lo;
        } else {
            final DD m2 = EE.add(PZ).mul(EE.sub(PZ)).sub(KT2);
            final DD eff = m2.signum() > 0 ? m2 : DD.ZERO;
            final DD ePlusPz = EE.add(absPz);
            DD r = KT2.add(eff).div(ePlusPz.sqr()).log().mulPow2(0.5);
            if (PZ.signum() > 0) {
                r = r.neg();
            }
            rap = r.hi;
            rapL = r.lo;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Components                                                          */
    /* ------------------------------------------------------------------ */

    public double E() { return e; }
    public double e() { return e; }
    public double px() { return px; }
    public double py() { return py; }
    public double pz() { return pz; }

    public DD pxDD() { return new DD(px, pxL); }
    public DD pyDD() { return new DD(py, pyL); }
    public DD pzDD() { return new DD(pz, pzL); }
    public DD eDD() { return new DD(e, eL); }

    /** Whether the momentum is held in double-double. */
    public boolean isDD() { return dd; }

    /** px, py, pz, E. */
    public double[] fourMom() {
        return new double[]{px, py, pz, e};
    }

    /** Component by index: 0..3 for px, py, pz, E, as in CLHEP. */
    public double get(int i) {
        return switch (i) {
            case 0 -> px;
            case 1 -> py;
            case 2 -> pz;
            case 3 -> e;
            default -> throw new FastJetException("PseudoJet subscripting: bad index (" + i + ")");
        };
    }

    /* ------------------------------------------------------------------ */
    /* Kinematics                                                          */
    /* ------------------------------------------------------------------ */

    /** The azimuth in [0, 2pi). */
    public double phi() {
        ensureValidRapPhi();
        return phi;
    }

    public double phi02pi() {
        return phi();
    }

    /** The azimuth in (-pi, pi]. */
    public double phiStd() {
        ensureValidRapPhi();
        if (dd) {
            final DD f = new DD(phi, phiL);
            return f.gt(DD.PI) ? f.sub(DD.TWO_PI).hi : phi;
        }
        return phi > PI ? phi - TWOPI : phi;
    }

    public DD phiDD() {
        ensureValidRapPhi();
        return new DD(phi, phiL);
    }

    public DD phiStdDD() {
        final DD f = phiDD();
        return f.gt(DD.PI) ? f.sub(DD.TWO_PI) : f;
    }

    /** The rapidity, or +-(1e5 + |pz|) for a massless particle along the beam. */
    public double rap() {
        ensureValidRapPhi();
        return rap;
    }

    public double rapidity() {
        return rap();
    }

    public DD rapDD() {
        ensureValidRapPhi();
        return new DD(rap, rapL);
    }

    /** The pseudorapidity. */
    public double pseudorapidity() {
        if (!dd) {
            if (px == 0.0 && py == 0.0) return MAX_RAP;
            if (pz == 0.0) return 0.0;
            double theta = CRMath.atan(perp() / pz);
            if (theta < 0) theta += PI;
            return -CRMath.log(CRMath.tan(theta / 2));
        }
        return pseudorapidityDD().hi;
    }

    public double eta() {
        return pseudorapidity();
    }

    /** The pseudorapidity to 106 bits, as sign(pz) log((|p| + |pz|) / pt). */
    public DD pseudorapidityDD() {
        if (px == 0.0 && py == 0.0 && pxL == 0.0 && pyL == 0.0) return new DD(MAX_RAP);
        if (pz == 0.0 && pzL == 0.0) return DD.ZERO;
        final DD kt2dd = new DD(kt2, kt2L);
        final DD absPz = pzDD().abs();
        final DD modp = kt2dd.add(absPz.sqr()).sqrt();
        final DD eta = modp.add(absPz).div(kt2dd.sqrt()).log();
        return pz > 0 ? eta : eta.neg();
    }

    public double pt2() { return kt2; }
    public double pt() { return Math.sqrt(kt2); }
    public double perp2() { return kt2; }
    public double perp() { return Math.sqrt(kt2); }
    public double kt2() { return kt2; }

    public DD kt2DD() { return new DD(kt2, kt2L); }
    public DD ptDD() { return new DD(kt2, kt2L).sqrt(); }

    private double m2Double() {
        return (e + pz) * (e - pz) - kt2;
    }

    /** The squared invariant mass. */
    public double m2() {
        return dd ? m2DD().hi : m2Double();
    }

    public DD m2DD() {
        final DD EE = eDD(), PZ = pzDD();
        return EE.add(PZ).mul(EE.sub(PZ)).sub(new DD(kt2, kt2L));
    }

    /** The invariant mass, -sqrt(-m2) when m2 is negative, as in CLHEP. */
    public double m() {
        final double mm = m2();
        return mm < 0.0 ? -Math.sqrt(-mm) : Math.sqrt(mm);
    }

    public DD mDD() {
        final DD mm = m2DD();
        return mm.signum() < 0 ? mm.neg().sqrt().neg() : mm.sqrt();
    }

    public double mperp2() {
        return dd ? mperp2DD().hi : (e + pz) * (e - pz);
    }

    private DD mperp2DD() {
        final DD EE = eDD(), PZ = pzDD();
        return EE.add(PZ).mul(EE.sub(PZ));
    }

    public double mperp() { return Math.sqrt(Math.abs(mperp2())); }
    public double mt2() { return mperp2(); }
    public double mt() { return Math.sqrt(Math.abs(mperp2())); }

    public double modp2() {
        return dd ? new DD(kt2, kt2L).add(pzDD().sqr()).hi : kt2 + pz * pz;
    }

    public double modp() { return Math.sqrt(modp2()); }

    public double Et() {
        return kt2 == 0 ? 0.0 : e / Math.sqrt(1.0 + pz * pz / kt2);
    }

    public double Et2() {
        return kt2 == 0 ? 0.0 : e * e / (1.0 + pz * pz / kt2);
    }

    public double cosTheta() {
        return Math.min(1.0, Math.max(-1.0, pz / Math.sqrt(modp2())));
    }

    public double theta() {
        return CRMath.acos(cosTheta());
    }

    /* ------------------------------------------------------------------ */
    /* Distances                                                           */
    /* ------------------------------------------------------------------ */

    /** The squared distance in rapidity and azimuth. */
    public double plainDistance(PseudoJet other) {
        if (dd || other.dd) {
            return squaredDistanceDD(other).hi;
        }
        double dphi = Math.abs(phi() - other.phi());
        if (dphi > PI) {
            dphi = TWOPI - dphi;
        }
        final double drap = rap() - other.rap();
        return dphi * dphi + drap * drap;
    }

    public double squaredDistance(PseudoJet other) {
        return plainDistance(other);
    }

    /** The squared distance to 106 bits. */
    public DD squaredDistanceDD(PseudoJet other) {
        DD dphi = phiDD().sub(other.phiDD()).abs();
        if (dphi.gt(DD.PI)) {
            dphi = DD.TWO_PI.sub(dphi);
        }
        final DD drap = rapDD().sub(other.rapDD());
        return dphi.sqr().add(drap.sqr());
    }

    public double deltaR(PseudoJet other) {
        return Math.sqrt(squaredDistance(other));
    }

    public DD deltaRDD(PseudoJet other) {
        return squaredDistanceDD(other).sqrt();
    }

    /** other.phi() - phi(), in [-pi, pi]. */
    public double deltaPhiTo(PseudoJet other) {
        if (dd || other.dd) {
            return deltaPhiToDD(other).hi;
        }
        double dphi = other.phi() - phi();
        if (dphi > PI) dphi -= TWOPI;
        if (dphi < -PI) dphi += TWOPI;
        return dphi;
    }

    public DD deltaPhiToDD(PseudoJet other) {
        DD dphi = other.phiDD().sub(phiDD());
        if (dphi.gt(DD.PI)) dphi = dphi.sub(DD.TWO_PI);
        if (dphi.lt(DD.PI.neg())) dphi = dphi.add(DD.TWO_PI);
        return dphi;
    }

    /** min(kt^2) times the squared distance. */
    public double ktDistance(PseudoJet other) {
        if (dd || other.dd) {
            final DD d = DD.min(kt2DD(), other.kt2DD());
            return d.mul(squaredDistanceDD(other)).hi;
        }
        final double distance = Math.min(kt2, other.kt2);
        double dphi = Math.abs(phi() - other.phi());
        if (dphi > PI) dphi = TWOPI - dphi;
        final double drap = rap() - other.rap();
        return distance * (dphi * dphi + drap * drap);
    }

    public double beamDistance() {
        return kt2;
    }

    /* ------------------------------------------------------------------ */
    /* Arithmetic on four-vectors                                          */
    /* ------------------------------------------------------------------ */

    /** The sum, in the finer of the two arithmetics. */
    public PseudoJet plus(PseudoJet o) {
        if (dd || o.dd) {
            return new PseudoJet(pxDD().add(o.pxDD()), pyDD().add(o.pyDD()),
                                 pzDD().add(o.pzDD()), eDD().add(o.eDD()));
        }
        return new PseudoJet(px + o.px, py + o.py, pz + o.pz, e + o.e, Precision.DOUBLE);
    }

    public PseudoJet minus(PseudoJet o) {
        if (dd || o.dd) {
            return new PseudoJet(pxDD().sub(o.pxDD()), pyDD().sub(o.pyDD()),
                                 pzDD().sub(o.pzDD()), eDD().sub(o.eDD()));
        }
        return new PseudoJet(px - o.px, py - o.py, pz - o.pz, e - o.e, Precision.DOUBLE);
    }

    /** coeff times the jet, keeping its rapidity and azimuth. */
    public PseudoJet times(double coeff) {
        ensureValidRapPhi();
        PseudoJet r = new PseudoJet(this);
        r.timesEqual(coeff);
        return r;
    }

    public PseudoJet divide(double coeff) {
        return times(1.0 / coeff);
    }

    public PseudoJet plusEqual(PseudoJet o) {
        if (dd || o.dd) {
            setMomentumDD(pxDD().add(o.pxDD()), pyDD().add(o.pyDD()),
                          pzDD().add(o.pzDD()), eDD().add(o.eDD()));
        } else {
            px += o.px; py += o.py; pz += o.pz; e += o.e;
            finishInit();
        }
        return this;
    }

    public PseudoJet minusEqual(PseudoJet o) {
        if (dd || o.dd) {
            setMomentumDD(pxDD().sub(o.pxDD()), pyDD().sub(o.pyDD()),
                          pzDD().sub(o.pzDD()), eDD().sub(o.eDD()));
        } else {
            px -= o.px; py -= o.py; pz -= o.pz; e -= o.e;
            finishInit();
        }
        return this;
    }

    /** Scales the momentum; the rapidity and azimuth are those before. */
    public PseudoJet timesEqual(double coeff) {
        ensureValidRapPhi();
        if (dd) {
            final DD c = new DD(coeff);
            DD t = pxDD().mul(coeff); px = t.hi; pxL = t.lo;
            t = pyDD().mul(coeff); py = t.hi; pyL = t.lo;
            t = pzDD().mul(coeff); pz = t.hi; pzL = t.lo;
            t = eDD().mul(coeff); e = t.hi; eL = t.lo;
            t = new DD(kt2, kt2L).mul(c.sqr()); kt2 = t.hi; kt2L = t.lo;
        } else {
            px *= coeff;
            py *= coeff;
            pz *= coeff;
            e *= coeff;
            kt2 *= coeff * coeff;
        }
        return this;
    }

    public PseudoJet divideEqual(double coeff) {
        return timesEqual(1.0 / coeff);
    }

    private void setMomentumDD(DD x, DD y, DD z, DD t) {
        px = x.hi; pxL = x.lo;
        py = y.hi; pyL = y.lo;
        pz = z.hi; pzL = z.lo;
        e = t.hi; eL = t.lo;
        dd = true;
        finishInit();
    }

    /** Transforms this jet, given in the rest frame of prest, to the lab frame. */
    public PseudoJet boost(PseudoJet prest) {
        if (prest.px == 0.0 && prest.py == 0.0 && prest.pz == 0.0
                && prest.pxL == 0.0 && prest.pyL == 0.0 && prest.pzL == 0.0) {
            return this;
        }
        if (dd || prest.dd) {
            final DD m = prest.mDD();
            final DD pf4 = pxDD().mul(prest.pxDD()).add(pyDD().mul(prest.pyDD()))
                .add(pzDD().mul(prest.pzDD())).add(eDD().mul(prest.eDD())).div(m);
            final DD fn = pf4.add(eDD()).div(prest.eDD().add(m));
            setMomentumDD(pxDD().add(fn.mul(prest.pxDD())), pyDD().add(fn.mul(prest.pyDD())),
                          pzDD().add(fn.mul(prest.pzDD())), pf4);
            return this;
        }
        final double mLocal = prest.m();
        final double pf4 = (px * prest.px + py * prest.py + pz * prest.pz + e * prest.e) / mLocal;
        final double fn = (pf4 + e) / (prest.e + mLocal);
        px += fn * prest.px;
        py += fn * prest.py;
        pz += fn * prest.pz;
        e = pf4;
        finishInit();
        return this;
    }

    /** Transforms this jet, given in the lab, to the rest frame of prest. */
    public PseudoJet unboost(PseudoJet prest) {
        if (prest.px == 0.0 && prest.py == 0.0 && prest.pz == 0.0
                && prest.pxL == 0.0 && prest.pyL == 0.0 && prest.pzL == 0.0) {
            return this;
        }
        if (dd || prest.dd) {
            final DD m = prest.mDD();
            final DD pf4 = eDD().mul(prest.eDD()).sub(pxDD().mul(prest.pxDD()))
                .sub(pyDD().mul(prest.pyDD())).sub(pzDD().mul(prest.pzDD())).div(m);
            final DD fn = pf4.add(eDD()).div(prest.eDD().add(m));
            setMomentumDD(pxDD().sub(fn.mul(prest.pxDD())), pyDD().sub(fn.mul(prest.pyDD())),
                          pzDD().sub(fn.mul(prest.pzDD())), pf4);
            return this;
        }
        final double mLocal = prest.m();
        final double pf4 = (-px * prest.px - py * prest.py - pz * prest.pz + e * prest.e) / mLocal;
        final double fn = (pf4 + e) / (prest.e + mLocal);
        px -= fn * prest.px;
        py -= fn * prest.py;
        pz -= fn * prest.pz;
        e = pf4;
        finishInit();
        return this;
    }

    /* ------------------------------------------------------------------ */
    /* Resetting                                                           */
    /* ------------------------------------------------------------------ */

    /** New momentum, and no history, structure or user information. */
    public void reset(double px, double py, double pz, double e) {
        resetMomentum(px, py, pz, e);
        resetIndices();
    }

    public void reset(PseudoJet other) {
        copyFrom(other);
    }

    /** New momentum; the indices, structure and user information stay. */
    public void resetMomentum(double px, double py, double pz, double e) {
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.e = e;
        pxL = pyL = pzL = eL = 0.0;
        finishInit();
    }

    public void resetMomentum(DD px, DD py, DD pz, DD e) {
        setMomentumDD(px, py, pz, e);
    }

    /** Takes the momentum of another jet, with its cached rapidity and azimuth. */
    public void resetMomentum(PseudoJet o) {
        px = o.px; py = o.py; pz = o.pz; e = o.e;
        pxL = o.pxL; pyL = o.pyL; pzL = o.pzL; eL = o.eL;
        kt2 = o.kt2; kt2L = o.kt2L;
        phi = o.phi; phiL = o.phiL;
        rap = o.rap; rapL = o.rapL;
        explicitRapPhi = o.explicitRapPhi;
        dd = o.dd;
    }

    public void resetPtYPhiM(double pt, double y, double phi, double m) {
        resetMomentumPtYPhiM(pt, y, phi, m);
        resetIndices();
    }

    /** The momentum from pt, rapidity, azimuth and mass, which are then kept as given. */
    public void resetMomentumPtYPhiM(double pt, double y, double phi, double m) {
        if (dd) {
            resetMomentumPtYPhiMDD(new DD(pt), new DD(y), new DD(phi), new DD(m));
            return;
        }
        if (!(phi < 2 * TWOPI && phi > -TWOPI)) {
            throw new FastJetException("PtYPhiM: phi out of range: " + phi);
        }
        final double ptm = (m == 0) ? pt : Math.sqrt(pt * pt + m * m);
        final double exprap = CRMath.exp(y);
        final double pminus = ptm / exprap;
        final double pplus = ptm * exprap;
        final double pxLocal = pt * CRMath.cos(phi);
        final double pyLocal = pt * CRMath.sin(phi);
        resetMomentum(pxLocal, pyLocal, 0.5 * (pplus - pminus), 0.5 * (pplus + pminus));
        setCachedRapPhi(y, phi);
    }

    public void resetMomentumPtYPhiMDD(DD pt, DD y, DD phi, DD m) {
        final DD ptm = m.isZero() ? pt : pt.sqr().add(m.sqr()).sqrt();
        final DD exprap = y.exp();
        final DD pminus = ptm.div(exprap);
        final DD pplus = ptm.mul(exprap);
        final DD[] sc = phi.sincos();
        setMomentumDD(pt.mul(sc[1]), pt.mul(sc[0]),
                      pplus.sub(pminus).mulPow2(0.5), pplus.add(pminus).mulPow2(0.5));
        setCachedRapPhi(y, phi);
    }

    /** Declares the rapidity and azimuth, as a ghost placed on a grid does. */
    public void setCachedRapPhi(double rapIn, double phiIn) {
        setCachedRapPhi(new DD(rapIn), new DD(phiIn));
    }

    public void setCachedRapPhi(DD rapIn, DD phiIn) {
        DD f = phiIn;
        if (dd) {
            if (f.ge(DD.TWO_PI)) f = f.sub(DD.TWO_PI);
            if (f.lt(0.0)) f = f.add(DD.TWO_PI);
            rap = rapIn.hi;
            rapL = rapIn.lo;
            phi = f.hi;
            phiL = f.lo;
        } else {
            double p = phiIn.hi;
            if (p >= TWOPI) p -= TWOPI;
            if (p < 0) p += TWOPI;
            rap = rapIn.hi;
            rapL = 0.0;
            phi = p;
            phiL = 0.0;
        }
        explicitRapPhi = true;
    }

    private void resetIndices() {
        clusterHistIndex = -1;
        userIndex = -1;
        structure = null;
        userInfo = null;
    }

    /* ------------------------------------------------------------------ */
    /* Indices and user information                                        */
    /* ------------------------------------------------------------------ */

    public int userIndex() { return userIndex; }
    public PseudoJet setUserIndex(int index) { this.userIndex = index; return this; }

    public int clusterHistIndex() { return clusterHistIndex; }
    public void setClusterHistIndex(int index) { this.clusterHistIndex = index; }
    public int clusterSequenceHistoryIndex() { return clusterHistIndex; }

    public boolean hasUserInfo() { return userInfo != null; }
    public Object userInfo() { return userInfo; }
    public PseudoJet setUserInfo(Object info) { this.userInfo = info; return this; }

    public <T> T userInfo(Class<T> type) {
        if (userInfo == null) {
            throw new FastJetException("you attempted to perform a dynamic cast of a PseudoJet's extra info, but the extra info pointer was null");
        }
        return type.cast(userInfo);
    }

    public <T> boolean hasUserInfo(Class<T> type) {
        return type.isInstance(userInfo);
    }

    /* ------------------------------------------------------------------ */
    /* Structure                                                           */
    /* ------------------------------------------------------------------ */

    public boolean hasStructure() { return structure != null; }
    public PseudoJetStructure structure() { return structure; }
    public void setStructure(PseudoJetStructure s) { this.structure = s; }

    public PseudoJetStructure validatedStructure() {
        if (structure == null) {
            throw new FastJetException("Trying to access the structure of a PseudoJet which has no associated structure");
        }
        return structure;
    }

    public <T extends PseudoJetStructure> boolean hasStructureOf(Class<T> type) {
        return type.isInstance(structure);
    }

    public String description() {
        return structure == null
            ? "standard PseudoJet (with no associated clustering information)"
            : structure.description();
    }

    public boolean hasAssociatedClusterSequence() {
        return structure != null && structure.hasAssociatedClusterSequence();
    }

    public ClusterSequence associatedClusterSequence() {
        return hasAssociatedClusterSequence() ? structure.associatedClusterSequence() : null;
    }

    public ClusterSequence associatedCs() {
        return associatedClusterSequence();
    }

    public boolean hasValidClusterSequence() {
        return structure != null && structure.hasValidClusterSequence();
    }

    public ClusterSequence validatedCs() {
        return validatedStructure().validatedCs();
    }

    public ClusterSequence validatedClusterSequence() {
        return validatedCs();
    }

    public ClusterSequenceAreaBase validatedCsab() {
        ClusterSequence cs = validatedCs();
        if (!(cs instanceof ClusterSequenceAreaBase csab)) {
            throw new FastJetException("you requested jet-area related information, but the PseudoJet does not have associated area information.");
        }
        return csab;
    }

    /** The partner it was recombined with, or null. */
    public PseudoJet partner() { return validatedStructure().partner(this); }
    public boolean hasPartner() { return partner() != null; }

    /** What it was recombined into, or null. */
    public PseudoJet child() { return validatedStructure().child(this); }
    public boolean hasChild() { return child() != null; }

    /** The two parents, harder first, or null for an initial particle. */
    public PseudoJet[] parents() { return validatedStructure().parents(this); }
    public boolean hasParents() { return parents() != null; }

    /** True if the constituent given is part of this jet. */
    public boolean contains(PseudoJet constituent) {
        return validatedStructure().objectInJet(constituent, this);
    }

    public boolean isInside(PseudoJet jet) {
        return validatedStructure().objectInJet(this, jet);
    }

    public boolean hasConstituents() {
        return structure != null && structure.hasConstituents();
    }

    public List<PseudoJet> constituents() {
        return validatedStructure().constituents(this);
    }

    public boolean hasExclusiveSubjets() {
        return structure != null && structure.hasExclusiveSubjets();
    }

    public List<PseudoJet> exclusiveSubjets(double dcut) {
        return validatedStructure().exclusiveSubjets(this, dcut);
    }

    public int nExclusiveSubjets(double dcut) {
        return validatedStructure().nExclusiveSubjets(this, dcut);
    }

    public List<PseudoJet> exclusiveSubjetsUpTo(int nsub) {
        return validatedStructure().exclusiveSubjetsUpTo(this, nsub);
    }

    public List<PseudoJet> exclusiveSubjets(int nsub) {
        List<PseudoJet> subjets = exclusiveSubjetsUpTo(nsub);
        if (subjets.size() < nsub) {
            throw new FastJetException("Requested " + nsub + " exclusive subjets, but there were only "
                + subjets.size() + " particles in the jet");
        }
        return subjets;
    }

    public double exclusiveSubdmerge(int nsub) {
        return validatedStructure().exclusiveSubdmerge(this, nsub);
    }

    public double exclusiveSubdmergeMax(int nsub) {
        return validatedStructure().exclusiveSubdmergeMax(this, nsub);
    }

    public boolean hasPieces() {
        return structure != null && structure.hasPieces(this);
    }

    public List<PseudoJet> pieces() {
        return validatedStructure().pieces(this);
    }

    public boolean hasArea() {
        return structure != null && structure.hasArea();
    }

    public double area() { return validatedStructure().area(this); }
    public double areaError() { return validatedStructure().areaError(this); }
    public PseudoJet area4vector() { return validatedStructure().area4vector(this); }
    public boolean isPureGhost() { return validatedStructure().isPureGhost(this); }

    /* ------------------------------------------------------------------ */
    /* Comparisons                                                         */
    /* ------------------------------------------------------------------ */

    /** FastJet's operator==: same momentum, indices, user information and structure. */
    public boolean sameAs(PseudoJet b) {
        return px == b.px && py == b.py && pz == b.pz && e == b.e
            && pxL == b.pxL && pyL == b.pyL && pzL == b.pzL && eL == b.eL
            && userIndex == b.userIndex && clusterHistIndex == b.clusterHistIndex
            && userInfo == b.userInfo && structure == b.structure;
    }

    /** True if the four-momentum is exactly zero. */
    public boolean isZero() {
        return px == 0 && py == 0 && pz == 0 && e == 0 && pxL == 0 && pyL == 0 && pzL == 0 && eL == 0;
    }

    public static boolean haveSameMomentum(PseudoJet a, PseudoJet b) {
        return a.px == b.px && a.py == b.py && a.pz == b.pz && a.e == b.e
            && a.pxL == b.pxL && a.pyL == b.pyL && a.pzL == b.pzL && a.eL == b.eL;
    }

    public static double dotProduct(PseudoJet a, PseudoJet b) {
        if (a.dd || b.dd) {
            return a.eDD().mul(b.eDD()).sub(a.pxDD().mul(b.pxDD()))
                .sub(a.pyDD().mul(b.pyDD())).sub(a.pzDD().mul(b.pzDD())).hi;
        }
        return a.e * b.e - a.px * b.px - a.py * b.py - a.pz * b.pz;
    }

    public static double cosTheta(PseudoJet a, PseudoJet b) {
        final double dot3 = a.px * b.px + a.py * b.py + a.pz * b.pz;
        return Math.min(1.0, Math.max(-1.0, dot3 / Math.sqrt(a.modp2() * b.modp2())));
    }

    /** The angle between two three-momenta, to 106 bits, stable in the collinear limit. */
    public static DD thetaDD(PseudoJet a, PseudoJet b) {
        final DD ax = a.pxDD(), ay = a.pyDD(), az = a.pzDD();
        final DD bx = b.pxDD(), by = b.pyDD(), bz = b.pzDD();
        final DD cx = ay.mul(bz).sub(az.mul(by));
        final DD cy = az.mul(bx).sub(ax.mul(bz));
        final DD cz = ax.mul(by).sub(ay.mul(bx));
        final DD cross = cx.sqr().add(cy.sqr()).add(cz.sqr()).sqrt();
        final DD dot = ax.mul(bx).add(ay.mul(by)).add(az.mul(bz));
        return DD.atan2(cross, dot);
    }

    public static double theta(PseudoJet a, PseudoJet b) {
        return (a.dd || b.dd) ? thetaDD(a, b).hi : CRMath.acos(cosTheta(a, b));
    }

    /* ------------------------------------------------------------------ */
    /* Sorting                                                             */
    /* ------------------------------------------------------------------ */

    public static final Comparator<PseudoJet> BY_PT_DESC =
        (a, b) -> b.kt2DD().compareTo(a.kt2DD());
    public static final Comparator<PseudoJet> BY_E_DESC =
        (a, b) -> b.eDD().compareTo(a.eDD());
    public static final Comparator<PseudoJet> BY_RAPIDITY =
        (a, b) -> a.rapDD().compareTo(b.rapDD());
    public static final Comparator<PseudoJet> BY_PZ =
        (a, b) -> a.pzDD().compareTo(b.pzDD());

    /** Decreasing pt. */
    public static List<PseudoJet> sortedByPt(Collection<PseudoJet> jets) {
        return sorted(jets, BY_PT_DESC);
    }

    /** Increasing rapidity. */
    public static List<PseudoJet> sortedByRapidity(Collection<PseudoJet> jets) {
        return sorted(jets, BY_RAPIDITY);
    }

    /** Decreasing energy. */
    public static List<PseudoJet> sortedByE(Collection<PseudoJet> jets) {
        return sorted(jets, BY_E_DESC);
    }

    /** Increasing pz. */
    public static List<PseudoJet> sortedByPz(Collection<PseudoJet> jets) {
        return sorted(jets, BY_PZ);
    }

    private static List<PseudoJet> sorted(Collection<PseudoJet> jets, Comparator<PseudoJet> by) {
        List<PseudoJet> out = new ArrayList<>(jets);
        out.sort(by);
        return out;
    }

    /** The indices that sort the values into increasing order. */
    public static int[] sortIndices(double[] values) {
        Integer[] idx = new Integer[values.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));
        int[] out = new int[idx.length];
        for (int i = 0; i < out.length; i++) out[i] = idx[i];
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Joining                                                             */
    /* ------------------------------------------------------------------ */

    /** A composite jet whose momentum is the four-vector sum of the pieces. */
    public static PseudoJet join(List<PseudoJet> pieces) {
        PseudoJet result = new PseudoJet();
        for (PseudoJet p : pieces) {
            result.plusEqual(p);
        }
        result.structure = new CompositeJetStructure(pieces, null);
        return result;
    }

    public static PseudoJet join(PseudoJet... pieces) {
        return join(Arrays.asList(pieces));
    }

    /** A composite jet whose momentum the recombiner builds from the pieces. */
    public static PseudoJet join(List<PseudoJet> pieces, Recombiner recombiner) {
        PseudoJet result = new PseudoJet();
        if (!pieces.isEmpty()) {
            result = pieces.get(0).copy();
            for (int i = 1; i < pieces.size(); i++) {
                recombiner.plusEqual(result, pieces.get(i));
            }
        }
        // As in FastJet, the indices are those the recombination left (or the
        // single piece's), so a recombiner that tracks flavour in the user
        // index keeps it through a join.
        result.structure = new CompositeJetStructure(pieces, recombiner);
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* Text                                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PseudoJet(px=%.10g, py=%.10g, pz=%.10g, E=%.10g)",
            px, py, pz, e);
    }

    /** The kinematics with the angles to the full 106 bits. */
    public String toStringDD() {
        return "pt=" + ptDD().toString(32) + " y=" + rapDD().toString(32)
            + " phi=" + phiDD().toString(32) + " m=" + mDD().toString(32);
    }
}
