package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::D0RunIBaseConePlugin, the D0 Run I cone (B. Abbott et al.,
 * FERMILAB-PUB-97-242-E; D0 code by L. Sonnenschein), common to
 * {@link D0RunIConePlugin} and {@link D0RunIpre96ConePlugin}, which differ
 * in how the jets' four-momenta are rebuilt.
 *
 * <p><b>Parameter order.</b> FastJet 3.5.2 hands its twelve parameters to
 * the D0 ConeClusterAlgo in an order that is not the one the constructor
 * declares (from the third on: TWOrad, SPLifr, D0_Angle... against SPLifr,
 * TWOrad, Thresh_Diff_Et...), so that what runs is: split fraction 0 (cones
 * sharing items always merge), minimum cone separation = the split fraction,
 * D0 angles on, far-cluster killing through a negative radius (i.e. off),
 * items below 0.5 GeV ignored and Et_min_ratio = 0.01. By default this port
 * does the same, so that its jets are FastJet's; {@link #withD0Parameters()}
 * gives the algorithm with the parameters as named.
 *
 * <p>The D0 code mixes double and single precision (float cone axes, the
 * float versions of log, atan2, sin, cos); the port follows it expression
 * for expression.
 */
public abstract class D0RunIBaseConePlugin implements JetDefinition.Plugin {

    public static final double DEFAULT_SPLIFR = 0.5;
    public static final double DEFAULT_TWORAD = 0.;
    public static final boolean DEFAULT_D0_ANGLE = false;
    public static final boolean DEFAULT_INCREASE_DELTA_R = true;
    public static final boolean DEFAULT_KILL_FAR_CLUSTERS = true;
    public static final boolean DEFAULT_JET_ET_MIN_ON_ITER = true;
    public static final double DEFAULT_FAR_RATIO = 0.5;
    public static final double DEFAULT_EITEM_NEGDROP = -1.0;
    public static final double DEFAULT_ET_MIN_RATIO = 0.5;
    public static final double DEFAULT_THRESH_DIFF_ET = 0.01;

    protected final double coneRad;
    protected final double jetMinEt;
    protected final double splitFraction;
    protected double twoRad = DEFAULT_TWORAD;
    protected boolean d0Angle = DEFAULT_D0_ANGLE;
    protected boolean increaseDeltaR = DEFAULT_INCREASE_DELTA_R;
    protected boolean killFarClusters = DEFAULT_KILL_FAR_CLUSTERS;
    protected boolean jetEtMinOnIter = DEFAULT_JET_ET_MIN_ON_ITER;
    protected double farRatio = DEFAULT_FAR_RATIO;
    protected double eitemNegdrop = DEFAULT_EITEM_NEGDROP;
    protected double etMinRatio = DEFAULT_ET_MIN_RATIO;
    protected double threshDiffEt = DEFAULT_THRESH_DIFF_ET;
    /** Whether the parameters reach the algorithm in FastJet's order. */
    protected boolean fastjetOrder = true;

    protected D0RunIBaseConePlugin(double coneRad, double jetMinEt, double splitFraction) {
        this.coneRad = coneRad;
        this.jetMinEt = jetMinEt;
        this.splitFraction = splitFraction;
    }

    /**
     * Runs the D0 algorithm with each parameter where its name says, rather
     * than in FastJet's order; the jets then differ from FastJet's.
     */
    public D0RunIBaseConePlugin withD0Parameters() {
        fastjetOrder = false;
        return this;
    }

    public boolean usesFastJetParameterOrder() {
        return fastjetOrder;
    }

    public double CONErad() { return coneRad; }
    public double JETmne() { return jetMinEt; }
    public double SPLifr() { return splitFraction; }
    public double TWOrad() { return twoRad; }
    public boolean D0Angle() { return d0Angle; }
    public boolean increaseDeltaR() { return increaseDeltaR; }
    public boolean killFarClusters() { return killFarClusters; }
    public boolean jetEtMinOnIter() { return jetEtMinOnIter; }
    public double farRatio() { return farRatio; }
    public double eitemNegdrop() { return eitemNegdrop; }
    public double etMinRatio() { return etMinRatio; }
    public double threshDiffEt() { return threshDiffEt; }
    public double overlapThreshold() { return splitFraction; }

    @Override
    public double R() {
        return coneRad;
    }

    /** The item type: how an item's and a jet's momenta are kept. */
    protected abstract EntityI newEntity(double e, double px, double py, double pz, int index);

    protected void runClusteringWorker(ClusterSequence cs) {
        final boolean dd = cs.precision() == Precision.DD;
        final List<EntityI> ensemble = new ArrayList<>();
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet p = cs.jet(i);
            final EntityI e = newEntity(p.E(), p.px(), p.py(), p.pz(), i);
            if (Math.abs(e.pz()) < e.E()) ensemble.add(e);
        }
        final ConeClusterAlgo algo;
        if (fastjetOrder) {
            // ConeClusterAlgo(CONErad, JETmne, SPLifr, TWOrad, Tresh_Diff_Et, D0_Angle, Increase_Delta_R,
            //                 Kill_Far_Clusters, Jet_Et_Min_On_Iter, Far_Ratio, Eitem_Negdrop, Et_Min_Ratio)
            // called with (CONErad, JETmne, TWOrad, SPLifr, D0_Angle, Increase_Delta_R, Kill_Far_Clusters,
            //              Jet_Et_Min_On_Iter, Far_Ratio, Eitem_Negdrop, Et_Min_Ratio, Thresh_Diff_Et)
            algo = new ConeClusterAlgo((float) coneRad, (float) jetMinEt, (float) twoRad, (float) splitFraction,
                d0Angle ? 1.f : 0.f, increaseDeltaR, killFarClusters, jetEtMinOnIter, farRatio != 0.0,
                (float) eitemNegdrop, (float) etMinRatio, (float) threshDiffEt);
        } else {
            algo = new ConeClusterAlgo((float) coneRad, (float) jetMinEt, (float) splitFraction, (float) twoRad,
                (float) threshDiffEt, d0Angle, increaseDeltaR, killFarClusters, jetEtMinOnIter,
                (float) farRatio, (float) eitemNegdrop, (float) etMinRatio);
        }
        algo.makeClusters(ensemble, 0.f);
        for (int i = algo.tempColl.size() - 1; i >= 0; i--) {
            final List<EntityI> tlist = algo.tempColl.get(i).items;
            if (tlist.isEmpty()) {
                // a cone emptied by the splitting; the C++ code would read
                // through the end of its empty list here
                continue;
            }
            int jetK = tlist.get(0).index;
            final EntityI current = tlist.get(0).copy();
            for (int t = 1; t < tlist.size(); t++) {
                final EntityI next = tlist.get(t);
                current.add(next);
                final PseudoJet newMom = new PseudoJet(current.px(), current.py(), current.pz(), current.E());
                jetK = cs.pluginRecordIJRecombination(jetK, next.index, 0.0, newMom);
            }
            final PseudoJet j = cs.jet(jetK);
            if (dd) {
                cs.pluginRecordIBRecombination(jetK, j.kt2DD());
            } else {
                cs.pluginRecordIBRecombination(jetK, j.perp2());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* d0runi::inline_maths and the float functions of <cmath>            */
    /* ------------------------------------------------------------------ */

    static final double PI = Math.PI;
    static final double TWOPI = 2 * PI;

    /** The signed azimuthal difference, phi1 - phi2 folded into [-pi, pi]. */
    static double deltaPhi(double phi1, double phi2) {
        final double a = Math.abs(phi1 - phi2);
        final double b = 2. * PI - Math.abs(phi1 - phi2);
        final double dphi = (a < b) ? a : b;
        return (phi1 < phi2) ? -dphi : dphi;
    }

    static double etaOfTheta(double theta) {
        return -CRMath.log(CRMath.tan(theta / 2.));
    }

    static double thetaOfEta(double eta) {
        return 2. * CRMath.atan(CRMath.exp(-eta));
    }

    static double eta3(double px, double py, double pz) {
        final double p = Math.sqrt(px * px + py * py + pz * pz);
        if (Math.abs(p - pz) == 0.) return 99999.;
        return 0.5 * CRMath.log((p + pz) / (p - pz));
    }

    static float logf(float x) {
        return (float) CRMath.log(x);
    }

    static float atan2f(float y, float x) {
        return (float) CRMath.atan2(y, x);
    }

    static float sinf(float x) {
        return (float) CRMath.sin(x);
    }

    static float cosf(float x) {
        return (float) CRMath.cos(x);
    }

    static float sqrtf(float x) {
        return (float) Math.sqrt(x);
    }

    static float r2Bis(float eta1, float phi1, float eta2, float phi2) {
        final float dphi = (float) deltaPhi(phi1, phi2);
        return (eta1 - eta2) * (eta1 - eta2) + dphi * dphi;
    }

    static float deltaR(float eta1, float eta2, float phi1, float phi2) {
        final float dphi = (float) deltaPhi(phi1, phi2);
        return sqrtf((eta1 - eta2) * (eta1 - eta2) + dphi * dphi);
    }

    static final float SMALL = (float) 1.E-05;

    static float e2eta(float[] p) {
        final float e0, e1, e2;
        if (p[3] < 0.0) {
            e0 = -p[0];
            e1 = -p[1];
            e2 = -p[2];
        } else {
            e0 = p[0];
            e1 = p[1];
            e2 = p[2];
        }
        final float pperp = sqrtf(e0 * e0 + e1 * e1) + SMALL;
        final float ptotal = sqrtf(e0 * e0 + e1 * e1 + e2 * e2) + SMALL;
        float eta = 0.0f;
        if (e2 > 0.0) eta = logf((ptotal + e2) / pperp);
        else eta = logf(pperp / (ptotal - e2));
        return eta;
    }

    static float e2phi(float[] p) {
        final float e0, e1;
        if (p[3] < 0.0) {
            e0 = -p[0];
            e1 = -p[1];
        } else {
            e0 = p[0];
            e1 = p[1];
        }
        float phi = atan2f(e1, e0 + SMALL);
        if (phi < 0.0) phi = (float) (phi + TWOPI);
        return phi;
    }

    /* ------------------------------------------------------------------ */
    /* d0runi::HepEntityI                                                  */
    /* ------------------------------------------------------------------ */

    /** d0runi::HepEntityI: an item kept as (Et, eta, phi). */
    protected static class EntityI {
        double et;
        double eta;
        double phi;
        final int index;
        // the float four-vector and its E2eta, E2phi, fixed for an item
        private float[] p4;
        private float eEta;
        private float ePhi;

        EntityI(double eIn, double pxIn, double pyIn, double pzIn, int index) {
            this.index = index;
            final double pt = Math.sqrt(pxIn * pxIn + pyIn * pyIn);
            final double p = Math.sqrt(pt * pt + pzIn * pzIn);
            phi = CRMath.atan2(pyIn, pxIn);
            final double theta = CRMath.asin(pt / p);
            eta = etaOfTheta(theta);
            et = eIn * CRMath.sin(theta);
        }

        EntityI(EntityI o) {
            this.et = o.et;
            this.eta = o.eta;
            this.phi = o.phi;
            this.index = o.index;
        }

        EntityI copy() {
            return new EntityI(this);
        }

        double pT() {
            return et;
        }

        double px() {
            return et * CRMath.cos(phi);
        }

        double py() {
            return et * CRMath.sin(phi);
        }

        double pz() {
            return et * CRMath.sinh(eta);
        }

        double E() {
            return et * CRMath.cosh(eta);
        }

        float[] p4vec() {
            if (p4 == null) {
                p4 = new float[]{(float) (et * CRMath.cos(phi)), (float) (et * CRMath.sin(phi)),
                    (float) (et * CRMath.sinh(eta)), (float) (et * CRMath.cosh(eta))};
                eEta = e2eta(p4);
                ePhi = e2phi(p4);
            }
            return p4;
        }

        float e2etaOf() {
            p4vec();
            return eEta;
        }

        float e2phiOf() {
            p4vec();
            return ePhi;
        }

        void add(EntityI el) {
            double w2 = el.et;
            et += el.et;
            w2 /= et;
            eta += w2 * (el.eta - eta);
            phi += w2 * deltaPhi(el.phi, phi);
            p4 = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* d0runi::ConeClusterAlgo                                             */
    /* ------------------------------------------------------------------ */

    static final class TemporaryJet {
        final List<EntityI> items = new ArrayList<>();
        float eta;
        float phi;
        float et;
        float e;

        TemporaryJet(float eta, float phi) {
            this.eta = eta;
            this.phi = phi;
        }

        TemporaryJet(TemporaryJet o) {
            items.addAll(o.items);
            eta = o.eta;
            phi = o.phi;
            et = o.et;
            e = o.e;
        }

        void setEtaPhiEt(float eta, float phi, float pT) {
            this.eta = eta;
            this.phi = phi;
            this.et = pT;
        }

        void erase() {
            items.clear();
            eta = 0.0f;
            phi = 0.0f;
            et = 0.0f;
        }

        boolean itemInJet(EntityI tw) {
            return indexOf(items, tw) >= 0;
        }

        /** Merges newJet in (returns false) or splits the shared items (true). */
        boolean shareJets(TemporaryJet newJet, float sharedFr, float splifr) {
            if (sharedFr >= splifr) {
                for (EntityI it : newJet.items) {
                    if (indexOf(items, it) < 0) items.add(it);
                }
                newJet.erase();
                return false;
            }
            for (int t = 0; t < newJet.items.size(); ) {
                final EntityI it = newJet.items.get(t);
                final int where = indexOf(items, it);
                if (where >= 0) {
                    final float etaItem = it.e2etaOf();
                    final float phiItem = it.e2phiOf();
                    final float radOld = r2Bis(eta, phi, etaItem, phiItem);
                    final float radNew = r2Bis(newJet.eta, newJet.phi, etaItem, phiItem);
                    if (radNew > radOld) {
                        newJet.items.remove(t);
                    } else {
                        items.remove(where);
                        ++t;
                    }
                } else {
                    ++t;
                }
            }
            return true;
        }

        float distR2(TemporaryJet jet) {
            final float deta = eta - jet.eta;
            final float dphi = (float) deltaPhi(phi, jet.phi);
            return deta * deta + dphi * dphi;
        }

        boolean updateEtaPhiEt() {
            float etSum = 0.0f;
            float etaSum = 0.0f;
            float phiSum = 0.0f;
            float eSum = 0.0f;
            for (EntityI it : items) {
                final float etk = (float) it.pT();
                final float[] pz = it.p4vec();
                final float etak = it.e2etaOf();
                float phik = it.e2phiOf();
                if (Math.abs(phik - phi) > TWOPI - Math.abs(phik - phi)) {
                    if (phi < phik) phik = (float) (phik - TWOPI);
                    else phik = (float) (phik + TWOPI);
                }
                etaSum += etak * etk;
                phiSum += phik * etk;
                etSum += etk;
                eSum += pz[3];
            }
            if (etSum <= 0.0) {
                eta = 0.0f;
                phi = 0.0f;
                et = 0.0f;
                e = 0.f;
                return false;
            }
            eta = etaSum / etSum;
            phi = phiSum / etSum;
            if (phi < 0) phi = (float) (phi + TWOPI);
            et = etSum;
            e = eSum;
            return true;
        }

        void d0AngleUpdateEtaPhi() {
            float exSum = 0.0f;
            float eySum = 0.0f;
            float ezSum = 0.0f;
            for (EntityI it : items) {
                final float[] p = it.p4vec();
                exSum += p[0];
                eySum += p[1];
                ezSum += p[2];
            }
            // inline_maths::phi(px, py) is atan2(py, px): called as
            // phi(EYsum, EXsum) by the D0 code, hence atan2(EX, EY)
            phi = (float) CRMath.atan2(exSum, eySum);
            eta = (float) eta3(exSum, eySum, ezSum);
        }
    }

    static int indexOf(List<EntityI> l, EntityI e) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == e) return i;
        }
        return -1;
    }

    static final class ConeClusterAlgo {
        final float coneRad;
        final float jetMinEt;
        final float splifr;
        final float twoRad;
        final boolean d0Angle;
        final boolean increaseDeltaR;
        final boolean killFarClusters;
        final boolean jetEtMinOnIter;
        final float farRatio;
        final float eitemNegdrop;
        final float etMinRatio;
        final float threshDiffEt;
        final List<TemporaryJet> tempColl = new ArrayList<>();

        ConeClusterAlgo(float coneRad, float jetMinEt, float splifr, float twoRad, float threshDiffEt,
                        boolean d0Angle, boolean increaseDeltaR, boolean killFarClusters, boolean jetEtMinOnIter,
                        float farRatio, float eitemNegdrop, float etMinRatio) {
            this.coneRad = Math.abs(coneRad);
            this.jetMinEt = jetMinEt;
            this.splifr = splifr;
            this.twoRad = twoRad;
            this.d0Angle = d0Angle;
            this.increaseDeltaR = increaseDeltaR;
            this.killFarClusters = killFarClusters;
            this.jetEtMinOnIter = jetEtMinOnIter;
            this.farRatio = farRatio;
            this.eitemNegdrop = eitemNegdrop;
            this.etMinRatio = etMinRatio;
            this.threshDiffEt = threshDiffEt;
        }

        private static float max(float a, float b) {
            return (a < b) ? b : a;
        }

        private static double min(double a, double b) {
            return (b < a) ? b : a;
        }

        /** The items that may lie in a cone about (etaJet, phiJet). */
        List<EntityI> getItemsInCone(List<EntityI> tlist, float etaJet, float phiJet, float coneRadius,
                                     float zvertexIn) {
            final float zvertexMax = 200.f;
            final float dmin = 80.f;
            final float dmax = 360.f;
            final float thetaMargin = (float) 0.022;
            float zvertex = zvertexIn;
            final float d1;
            final float d2;
            if (Math.abs(zvertex) > zvertexMax) zvertex = 0.0f;
            if (zvertex >= 0.) {
                d1 = Math.abs(dmin - zvertex);
                d2 = Math.abs(dmax + zvertex);
            } else {
                d1 = Math.abs(dmax - zvertex);
                d2 = Math.abs(dmin + zvertex);
            }
            final float phiD1 = phiJet + coneRadius;
            float thetaE1 = (float) thetaOfEta(etaJet + coneRadius);
            final float z1 = zvertex + d1 * cosf(thetaE1);
            final float r1 = d1 * sinf(thetaE1);
            final float phiD2 = phiJet - coneRadius;
            thetaE1 = (float) thetaOfEta(etaJet - coneRadius);
            final float z2 = zvertex + d2 * cosf(thetaE1);
            final float r2 = d2 * sinf(thetaE1);
            float thetaD1 = atan2f(r1, z1);
            float thetaD2 = atan2f(r2, z2);
            thetaD1 = max(thetaD1, thetaMargin);
            thetaD2 = max(thetaD2, thetaMargin);
            thetaD1 = (float) min(PI - (double) thetaMargin, thetaD1);
            thetaD2 = (float) min(PI - (double) thetaMargin, thetaD2);
            final float etaD1 = (float) etaOfTheta(thetaD1);
            final float etaD2 = (float) etaOfTheta(thetaD2);
            final List<EntityI> out = new ArrayList<>(tlist.size());
            for (EntityI it : tlist) {
                final float etaCur = it.e2etaOf();
                final float phiCur = it.e2phiOf();
                boolean accepted = etaCur < etaD1 && etaCur > etaD2;
                if (phiD2 > 0 && phiD1 < TWOPI) {
                    accepted = accepted && phiCur < phiD1 && phiCur > phiD2;
                } else if (phiD2 > 0) {
                    accepted = accepted && ((phiCur > phiD2 && phiCur < TWOPI) || phiCur < phiD1 - TWOPI);
                } else {
                    accepted = accepted && ((phiCur < phiD1 && phiCur > 0) || phiCur > phiD2 + TWOPI);
                }
                if (accepted) out.add(it);
            }
            return out;
        }

        void makeClusters(List<EntityI> itemlist, float zvertex) {
            final List<EntityI> ecv = new ArrayList<>(itemlist);
            float rcut = (float) 1.E-06;
            if (increaseDeltaR) rcut = (float) 1.E-04;
            final List<float[]> ltrack = new ArrayList<>();
            for (EntityI ptr : ecv) {
                float etast = ptr.e2etaOf();
                float phist = ptr.e2phiOf();
                boolean nojets = false;
                if (killFarClusters) {
                    for (float[] kj : ltrack) {
                        if (deltaR(kj[0], etast, kj[1], phist) < farRatio * coneRad) {
                            nojets = true;
                            break;
                        }
                    }
                }
                if (nojets) continue;

                final TemporaryJet tjet = new TemporaryJet(etast, phist);
                int trial = 0;
                do {
                    trial++;
                    etast = tjet.eta;
                    phist = tjet.phi;
                    tjet.erase();
                    if (phist > TWOPI) phist = (float) (phist - TWOPI);
                    if (phist < 0.0) phist = (float) (phist + TWOPI);
                    if (phist > TWOPI || phist < 0.0) {
                        tjet.setEtaPhiEt(0.0f, 0.0f, 0.0f);
                        break; // illegal jet phi
                    }
                    tjet.setEtaPhiEt(etast, phist, 0.0f);
                    final List<EntityI> twlist = getItemsInCone(itemlist, etast, phist, coneRad, zvertex);
                    for (EntityI tk : twlist) {
                        final float etk = (float) tk.pT();
                        if (etk > eitemNegdrop) {
                            final float etak = tk.e2etaOf();
                            float phik = tk.e2phiOf();
                            final float dphi = Math.abs(phik - phist);
                            if (dphi > TWOPI - dphi) {
                                if (phist < phik) phik = (float) (phik - TWOPI);
                                else phik = (float) (phik + TWOPI);
                            }
                            if (r2Bis(etak, phik, etast, phist) <= coneRad * coneRad) tjet.items.add(tk);
                        }
                    }
                    if (!tjet.updateEtaPhiEt()) break; // negative E jet
                    if (jetEtMinOnIter && tjet.et < jetMinEt * etMinRatio) break; // too low ET
                } while (r2Bis(tjet.eta, tjet.phi, etast, phist) >= rcut && trial <= 50);

                if (!(tjet.et >= jetMinEt)) continue;
                if (d0Angle) tjet.d0AngleUpdateEtaPhi();
                // the ET this cone shares with each of the cones found
                final List<int[]> shareJet = new ArrayList<>();
                final List<float[]> shareEt = new ArrayList<>();
                for (EntityI tk : new ArrayList<>(tjet.items)) {
                    final float etk = (float) tk.pT();
                    for (int kj = 0; kj < tempColl.size(); kj++) {
                        if (tempColl.get(kj).itemInJet(tk)) {
                            boolean jetok = false;
                            for (int s = 0; s < shareJet.size(); s++) {
                                if (shareJet.get(s)[0] == kj) {
                                    jetok = true;
                                    shareEt.get(s)[0] += etk;
                                    break;
                                }
                            }
                            if (!jetok) {
                                shareJet.add(new int[]{kj});
                                shareEt.add(new float[]{etk});
                            }
                        }
                    }
                }
                if (!shareJet.isEmpty()) {
                    float ssum = 0.0f;
                    int pmax = 0;
                    for (int s = 0; s < shareJet.size(); s++) {
                        ssum += shareEt.get(s)[0];
                        if (shareEt.get(s)[0] > shareEt.get(pmax)[0]) pmax = s;
                    }
                    final TemporaryJet jmax = tempColl.get(shareJet.get(pmax)[0]);
                    final float eleft = Math.abs(tjet.et - ssum);
                    final float djets = jmax.distR2(tjet);
                    if (djets <= twoRad || eleft <= threshDiffEt) {
                        tjet.erase();
                    } else {
                        final float sharedFr = ssum / ((tjet.et < jmax.et) ? tjet.et : jmax.et);
                        if (shareJet.size() > 1) {
                            for (int t = 0; t < tjet.items.size(); ) {
                                boolean found = false;
                                for (int s = 0; s < shareJet.size(); s++) {
                                    if (s != pmax && tempColl.get(shareJet.get(s)[0]).itemInJet(tjet.items.get(t))) {
                                        tjet.items.remove(t);
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) ++t;
                            }
                        }
                        final boolean splshr = jmax.shareJets(tjet, sharedFr, splifr);
                        if (splshr) {
                            jmax.updateEtaPhiEt();
                            tjet.updateEtaPhiEt();
                            if (d0Angle) tjet.d0AngleUpdateEtaPhi();
                            if (d0Angle) jmax.d0AngleUpdateEtaPhi();
                            tempColl.add(new TemporaryJet(tjet));
                            ltrack.add(new float[]{tjet.eta, tjet.phi});
                        } else {
                            jmax.updateEtaPhiEt();
                            if (d0Angle) jmax.d0AngleUpdateEtaPhi();
                        }
                    }
                } else {
                    tjet.updateEtaPhiEt();
                    if (d0Angle) tjet.d0AngleUpdateEtaPhi();
                    tempColl.add(new TemporaryJet(tjet));
                    ltrack.add(new float[]{tjet.eta, tjet.phi});
                }
            }
        }
    }
}
