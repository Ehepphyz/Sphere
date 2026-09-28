package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::D0RunIIConePlugin, the D0 Run II improved legacy (midpoint) cone
 * (G.C. Blazey et al., hep-ex/0005012; D0 code by L. Sonnenschein): stable
 * cones grown from every item far enough from those already found, then
 * from the midpoints of pairs of them, followed by a split-merge in pT
 * order with shared fraction split_ratio.
 *
 * The D0 code works in single precision, and so does this port, expression
 * for expression (a float here where the C++ has one, the double
 * intermediates where it converts), so that the jets are D0's own; under
 * {@link Precision#DD} only the four-momenta summed in the ClusterSequence
 * carry 106 bits.
 */
public final class D0RunIIConePlugin implements JetDefinition.Plugin {

    private static final String BANNER = String.join("\n",
        "#--------------------------------------------------------------------------",
        "# You are running the D0 Run II Cone plugin for FastJet                    ",
        "# Original code by the D0 collaboration, provided by Lars Sonnenschein;    ",
        "# interface by FastJet authors                                             ",
        "# If you use this plugin, please cite                                      ",
        "#   G. C. Blazey et al., hep-ex/0005012                                    ",
        "#   V. M. Abazov et al. [D0 Collaboration], arXiv:1110.3771 [hep-ex]       ",
        "# in addition to the usual FastJet reference.                              ",
        "#--------------------------------------------------------------------------");

    public static final double DEFAULT_SPLIT_RATIO = 0.5;
    public static final double DEFAULT_FAR_RATIO = 0.5;
    public static final double DEFAULT_ET_MIN_RATIO = 0.5;
    public static final boolean DEFAULT_KILL_DUPLICATE = true;
    public static final double DEFAULT_DUPLICATE_DR = 0.005;
    public static final double DEFAULT_DUPLICATE_DPT = 0.01;
    public static final double DEFAULT_SEARCH_FACTOR = 1.0;
    public static final double DEFAULT_PT_MIN_LEADING_PROTOJET = 0.;
    public static final double DEFAULT_PT_MIN_SECOND_PROTOJET = 0.;
    public static final int DEFAULT_MERGE_MAX = 10000;
    public static final double DEFAULT_PT_MIN_NOMERGE = 0.;

    private final double coneRadius;
    private final double minJetEt;
    private final double splitRatio;
    private final double farRatio;
    private final double etMinRatio;
    private final boolean killDuplicate;
    private final double duplicateDR;
    private final double duplicateDPT;
    private final double searchFactor;
    private final double pTMinLeadingProtojet;
    private final double pTMinSecondProtojet;
    private final int mergeMax;
    private final double pTMinNomerge;

    public D0RunIIConePlugin(double coneRadius, double minJetEt) {
        this(coneRadius, minJetEt, DEFAULT_SPLIT_RATIO);
    }

    public D0RunIIConePlugin(double coneRadius, double minJetEt, double splitRatio) {
        this(coneRadius, minJetEt, splitRatio, DEFAULT_FAR_RATIO, DEFAULT_ET_MIN_RATIO, DEFAULT_KILL_DUPLICATE,
             DEFAULT_DUPLICATE_DR, DEFAULT_DUPLICATE_DPT, DEFAULT_SEARCH_FACTOR, DEFAULT_PT_MIN_LEADING_PROTOJET,
             DEFAULT_PT_MIN_SECOND_PROTOJET, DEFAULT_MERGE_MAX, DEFAULT_PT_MIN_NOMERGE);
    }

    /** Every parameter of the D0 algorithm (FastJet exposes only the first three). */
    public D0RunIIConePlugin(double coneRadius, double minJetEt, double splitRatio, double farRatio,
                             double etMinRatio, boolean killDuplicate, double duplicateDR, double duplicateDPT,
                             double searchFactor, double pTMinLeadingProtojet, double pTMinSecondProtojet,
                             int mergeMax, double pTMinNomerge) {
        this.coneRadius = coneRadius;
        this.minJetEt = minJetEt;
        this.splitRatio = splitRatio;
        this.farRatio = farRatio;
        this.etMinRatio = etMinRatio;
        this.killDuplicate = killDuplicate;
        this.duplicateDR = duplicateDR;
        this.duplicateDPT = duplicateDPT;
        this.searchFactor = searchFactor;
        this.pTMinLeadingProtojet = pTMinLeadingProtojet;
        this.pTMinSecondProtojet = pTMinSecondProtojet;
        this.mergeMax = mergeMax;
        this.pTMinNomerge = pTMinNomerge;
    }

    public double coneRadius() { return coneRadius; }
    public double minJetEt() { return minJetEt; }
    public double splitRatio() { return splitRatio; }
    public double overlapThreshold() { return splitRatio; }
    public double farRatio() { return farRatio; }
    public double etMinRatio() { return etMinRatio; }
    public boolean killDuplicate() { return killDuplicate; }
    public double duplicateDR() { return duplicateDR; }
    public double duplicateDPT() { return duplicateDPT; }
    public double searchFactor() { return searchFactor; }
    public double pTMinLeadingProtojet() { return pTMinLeadingProtojet; }
    public double pTMinSecondProtojet() { return pTMinSecondProtojet; }
    public int mergeMax() { return mergeMax; }
    public double pTMinNomerge() { return pTMinNomerge; }

    @Override
    public double R() {
        return coneRadius;
    }

    @Override
    public String description() {
        return "D0 Run II Improved Legacy (midpoint) cone jet algorithm, with cone_radius = " + Fmt.g(coneRadius)
            + ", min_jet_Et = " + Fmt.g(minJetEt) + ", split_ratio = " + Fmt.g(splitRatio);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("D0RunIICone", BANNER);
        final boolean dd = cs.precision() == Precision.DD;
        final List<HepEntity> ensemble = new ArrayList<>();
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet p = cs.jet(i);
            final HepEntity e = new HepEntity(p.E(), p.px(), p.py(), p.pz(), i);
            if (Math.abs(e.pz) < e.E) ensemble.add(e);
        }
        final ILConeAlgorithm ilegac = new ILConeAlgorithm((float) coneRadius, (float) minJetEt, (float) splitRatio,
            (float) farRatio, (float) etMinRatio, killDuplicate, (float) duplicateDR, (float) duplicateDPT,
            (float) searchFactor, (float) pTMinLeadingProtojet, (float) pTMinSecondProtojet, mergeMax,
            (float) pTMinNomerge);
        ilegac.makeClusters(ensemble, 0.f);
        for (int i = ilegac.ilcv.size() - 1; i >= 0; i--) {
            final List<HepEntity> tlist = ilegac.ilcv.get(i).items;
            if (tlist.isEmpty()) continue;
            int jetK = tlist.get(0).index;
            for (int t = 1; t < tlist.size(); t++) {
                jetK = cs.pluginRecordIJRecombination(jetK, tlist.get(t).index, 0.0);
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
    /* d0::inline_maths                                                    */
    /* ------------------------------------------------------------------ */

    static final double PI = Math.PI;
    static final double TWOPI = 2 * PI;

    static double deltaPhi(double phi1, double phi2) {
        final double a = Math.abs(phi1 - phi2);
        final double b = 2. * PI - Math.abs(phi1 - phi2);
        return (a < b) ? a : b;
    }

    static double y(double e, double pz) {
        if (Math.abs(e - pz) == 0.) return 99999.;
        return 0.5 * CRMath.log((e + pz) / (e - pz));
    }

    static float rd2(float y1, float phi1, float y2, float phi2) {
        final float dphi = (float) deltaPhi(phi1, phi2);
        return (y1 - y2) * (y1 - y2) + dphi * dphi;
    }

    static float rDelta(float y1, float phi1, float y2, float phi2) {
        final float dphi = (float) deltaPhi(phi1, phi2);
        return (float) Math.sqrt((y1 - y2) * (y1 - y2) + dphi * dphi);
    }

    static float p2y(float[] p) {
        return (float) y(p[3], p[2]);
    }

    static float p2phi(float[] p) {
        return (float) CRMath.atan2(p[1], p[0]);
    }

    /* ------------------------------------------------------------------ */
    /* d0::HepEntity, ConeJetInfo, ProtoJet                                */
    /* ------------------------------------------------------------------ */

    static final class HepEntity {
        final double E, px, py, pz;
        final int index;

        HepEntity(double e, double px, double py, double pz, int index) {
            this.E = e;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.index = index;
        }

        // computed once: the cone search asks for them at every iteration
        private double y = Double.NaN;
        private double phi = Double.NaN;
        private boolean known;

        private void compute() {
            y = D0RunIIConePlugin.y(E, pz);
            phi = CRMath.atan2(py, px);
            known = true;
        }

        double y() {
            if (!known) compute();
            return y;
        }

        double phi() {
            if (!known) compute();
            return phi;
        }

        double pT() {
            return Math.sqrt(px * px + py * py);
        }

        void p4vec(float[] p) {
            p[0] = (float) px;
            p[1] = (float) py;
            p[2] = (float) pz;
            p[3] = (float) E;
        }
    }

    static final int CONEJET_SPLITMERGE_MOD = 100;

    static class ProtoJet {
        final List<HepEntity> items;
        float y;
        float phi;
        float pT;
        // ConeJetInfo
        final float seedET;
        float initialET;
        int nbSplitMerge;

        ProtoJet(float seedET) {
            this.items = new ArrayList<>();
            this.seedET = seedET;
        }

        ProtoJet(float seedET, float y, float phi) {
            this(seedET);
            this.y = y;
            this.phi = phi;
        }

        ProtoJet(ProtoJet pj) {
            this.items = new ArrayList<>(pj.items);
            this.y = pj.y;
            this.phi = pj.phi;
            this.pT = pj.pT;
            this.seedET = pj.seedET;
            this.initialET = pj.initialET;
            this.nbSplitMerge = pj.nbSplitMerge;
        }

        void addItem(HepEntity tw) {
            items.add(tw);
        }

        void setJet(float y, float phi, float pT) {
            this.y = y;
            this.phi = phi;
            this.pT = pT;
        }

        void updateJet() {
            final float[] p = {0.f, 0.f, 0.f, 0.f};
            final float[] pk = new float[4];
            for (HepEntity it : items) {
                it.p4vec(pk);
                for (int i = 0; i < 4; ++i) p[i] += pk[i];
            }
            y = p2y(p);
            phi = p2phi(p);
            pT = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]);
            if (p[3] < 0.) pT = -pT;
        }

        void erase() {
            items.clear();
            y = 0.0f;
            phi = 0.0f;
            pT = 0.0f;
        }

        void nowStable() {
            initialET = pT;
        }

        int nbMerge() {
            return nbSplitMerge % CONEJET_SPLITMERGE_MOD;
        }

        void splitted() {
            nbSplitMerge += CONEJET_SPLITMERGE_MOD;
        }

        void merged() {
            nbSplitMerge += 1;
        }

        boolean contains(HepEntity e) {
            for (HepEntity i : items) {
                if (i == e) return true;
            }
            return false;
        }
    }

    /** ILConeAlgorithm::TemporaryJet. */
    static final class TemporaryJet extends ProtoJet {
        TemporaryJet(float seedET, float y, float phi) {
            super(seedET, y, phi);
        }

        float dist(ProtoJet jet) {
            return rDelta(y, phi, jet.y, jet.phi);
        }

        /** The pT-weighted midpoint, {y, phi}. */
        float[] midpoint(ProtoJet jet) {
            final float pTsum = pT + jet.pT;
            final float yOut = (y * pT + jet.y * jet.pT) / pTsum;
            float phiOut = (phi * pT + jet.phi * jet.pT) / pTsum;
            if (Math.abs(phiOut - phi) > 2.0) {
                phiOut = (float) ((phi + PI) % TWOPI);
                if (phiOut < 0.0) phiOut = (float) (phiOut + TWOPI);
                phiOut = (float) (phiOut - PI);
                float temp = (float) ((jet.phi + PI) % TWOPI);
                if (temp < 0.0) temp = (float) (temp + TWOPI);
                temp = (float) (temp - PI);
                phiOut = (phiOut * pT + temp * jet.pT) / pTsum;
            }
            if (phiOut < 0.) phiOut = (float) (phiOut + TWOPI);
            return new float[]{yOut, phiOut};
        }

        boolean isStable(List<HepEntity> itemlist, float radius, float minET, int maxIterations) {
            final float radius2 = radius * radius;
            final float rcut = (float) 1.E-06;
            boolean stable = true;
            int trial = 0;
            float yst;
            float phist;
            do {
                trial++;
                yst = y;
                phist = phi;
                erase();
                setJet(yst, phist, 0.0f);
                for (HepEntity tk : itemlist) {
                    if (rd2((float) tk.y(), (float) tk.phi(), yst, phist) <= radius2) addItem(tk);
                }
                updateJet();
                if (pT < minET) {
                    stable = false;
                    break;
                }
            } while (rd2(y, phi, yst, phist) >= rcut && trial <= maxIterations);
            return stable;
        }
    }

    /* ------------------------------------------------------------------ */
    /* d0::ILConeAlgorithm                                                 */
    /* ------------------------------------------------------------------ */

    static final class ILConeAlgorithm {
        final float coneRadius;
        final float minJetEt;
        final float etMinRatio;
        final float farRatio;
        final float splitRatio;
        final float duplicateDR;
        final float duplicateDPT;
        final float searchCone;
        final boolean killDuplicate;
        final float ptMinLeadingProtojet;
        final float ptMinSecondProtojet;
        final int mergeMax;
        final float ptMinNoMergeMax;
        final List<ProtoJet> ilcv = new ArrayList<>();

        ILConeAlgorithm(float coneRadius, float minJetEt, float splitRatio, float farRatio, float etMinRatio,
                        boolean killDuplicate, float duplicateDR, float duplicateDPT, float searchFactor,
                        float ptMinLeadingProtojet, float ptMinSecondProtojet, int mergeMax, float ptMinNomerge) {
            this.coneRadius = coneRadius;
            this.minJetEt = minJetEt;
            this.etMinRatio = etMinRatio;
            this.farRatio = farRatio;
            this.splitRatio = splitRatio;
            this.duplicateDR = duplicateDR;
            this.duplicateDPT = duplicateDPT;
            this.searchCone = coneRadius / searchFactor;
            this.killDuplicate = killDuplicate;
            this.ptMinLeadingProtojet = ptMinLeadingProtojet;
            this.ptMinSecondProtojet = ptMinSecondProtojet;
            this.mergeMax = mergeMax;
            this.ptMinNoMergeMax = ptMinNomerge;
        }

        void makeClusters(List<HepEntity> ilist, float itemEtThreshold) {
            ilist.removeIf(it -> it.pT() < itemEtThreshold);
            final List<HepEntity> ecv = new ArrayList<>(ilist);
            final float farDef = farRatio * coneRadius * farRatio * coneRadius;
            final float ratio = minJetEt * etMinRatio;

            final List<ProtoJet> mcoll = new ArrayList<>();
            final List<TemporaryJet> scoll = new ArrayList<>();
            final float[] p = new float[4];
            for (HepEntity ptr : ecv) {
                ptr.p4vec(p);
                final float yst = p2y(p);
                final float phist = p2phi(p);
                boolean isFar = true;
                for (TemporaryJet s : scoll) {
                    if (rd2(yst, phist, s.y, s.phi) < farDef) {
                        isFar = false;
                        break;
                    }
                }
                if (!isFar) continue;
                final TemporaryJet jet = new TemporaryJet((float) ptr.pT(), yst, phist);
                if (jet.isStable(ilist, coneRadius, ratio, 0) && jet.isStable(ilist, searchCone, 3.0f, 50)) {
                    jet.isStable(ilist, coneRadius, ratio, 0);
                    if (killDuplicate) {
                        // is this the same jet found again?
                        float distmax = 999.f;
                        int imax = -1;
                        for (int i = 0; i < scoll.size(); ++i) {
                            final float dist = jet.dist(scoll.get(i));
                            if (dist < distmax) {
                                distmax = dist;
                                imax = i;
                            }
                        }
                        if (distmax > duplicateDR
                            || Math.abs((jet.pT - scoll.get(imax).pT) / scoll.get(imax).pT) > duplicateDPT) {
                            scoll.add(copyOf(jet));
                            mcoll.add(new ProtoJet(jet));
                        }
                    } else {
                        scoll.add(copyOf(jet));
                        mcoll.add(new ProtoJet(jet));
                    }
                }
            }
            for (int i = 0; i < scoll.size(); ++i) {
                for (int k = i + 1; k < scoll.size(); ++k) {
                    final float djet = scoll.get(i).dist(scoll.get(k));
                    if (djet > coneRadius && djet < 2. * coneRadius) {
                        final float[] mid = scoll.get(i).midpoint(scoll.get(k));
                        final TemporaryJet jet = new TemporaryJet(-999999.f, mid[0], mid[1]);
                        if (jet.isStable(ilist, coneRadius, ratio, 50)) {
                            mcoll.add(new ProtoJet(jet));
                        }
                    }
                }
            }
            ilcv.clear();
            splitMerge(mcoll);
        }

        private static TemporaryJet copyOf(TemporaryJet j) {
            final TemporaryJet c = new TemporaryJet(j.seedET, j.y, j.phi);
            c.items.addAll(j.items);
            c.pT = j.pT;
            c.initialET = j.initialET;
            c.nbSplitMerge = j.nbSplitMerge;
            return c;
        }

        /* ConeSplitMerge: a multimap ordered in pT, then seed ET, both decreasing */

        private static boolean order(ProtoJet first, ProtoJet second) {
            if (first.pT > second.pT) return true;
            if (first.pT < second.pT) return false;
            return first.seedET > second.seedET;
        }

        /** multimap::insert: after the elements it is equivalent to. */
        private static void insert(List<ProtoJet> members, ProtoJet pj) {
            int pos = members.size();
            for (int i = 0; i < members.size(); i++) {
                if (order(pj, members.get(i))) {
                    pos = i;
                    break;
                }
            }
            members.add(pos, pj);
        }

        private void splitMerge(List<ProtoJet> mcoll) {
            final List<ProtoJet> members = new ArrayList<>();
            for (ProtoJet j : mcoll) {
                final ProtoJet jet = new ProtoJet(j);
                jet.nowStable();
                insert(members, jet);
            }
            final float sharedETFraction = splitRatio;
            while (!members.isEmpty()) {
                final ProtoJet imax = members.remove(0);
                final List<HepEntity> ilist = imax.items;
                boolean share = false;
                float sharedET = 0.f;
                int jtmax = -1;
                for (int jt = 0; jt < members.size(); jt++) {
                    final ProtoJet j = members.get(jt);
                    for (HepEntity tk : ilist) {
                        if (j.contains(tk)) {
                            share = true;
                            sharedET = (float) (sharedET + tk.pT());
                        }
                    }
                    if (share) {
                        jtmax = jt;
                        break;
                    }
                }
                if (!share) {
                    ilcv.add(imax);
                    continue;
                }
                final ProtoJet jmax = members.remove(jtmax);
                if (sharedET > jmax.pT * sharedETFraction
                    && (imax.pT > ptMinLeadingProtojet || jmax.pT > ptMinSecondProtojet)
                    && (imax.nbMerge() < mergeMax || imax.pT > ptMinNoMergeMax)) {
                    // the neighbour's items go to imax
                    boolean same = true;
                    for (HepEntity tk : jmax.items) {
                        if (!imax.contains(tk)) {
                            imax.addItem(tk);
                            same = false;
                        }
                    }
                    if (!same) {
                        imax.updateJet();
                        imax.merged();
                    }
                } else if (sharedET > jmax.pT * sharedETFraction) {
                    final List<HepEntity> jlist = new ArrayList<>(jmax.items);
                    jlist.removeIf(imax::contains);
                    jmax.erase();
                    for (HepEntity it : jlist) jmax.addItem(it);
                    jmax.updateJet();
                    jmax.splitted();
                    insert(members, jmax);
                } else {
                    final List<HepEntity> il = new ArrayList<>(imax.items);
                    final List<HepEntity> jlist = new ArrayList<>(jmax.items);
                    for (int t = 0; t < jlist.size(); ) {
                        final HepEntity tk = jlist.get(t);
                        final int where = indexOf(il, tk);
                        if (where >= 0) {
                            final float yk = (float) tk.y();
                            final float phik = (float) tk.phi();
                            final float di = rd2(imax.y, imax.phi, yk, phik);
                            final float dj = rd2(jmax.y, jmax.phi, yk, phik);
                            if (dj > di) {
                                jlist.remove(t);
                            } else {
                                il.remove(where);
                                ++t;
                            }
                        } else {
                            ++t;
                        }
                    }
                    imax.erase();
                    for (HepEntity it : il) imax.addItem(it);
                    imax.updateJet();
                    imax.splitted();
                    jmax.erase();
                    for (HepEntity it : jlist) jmax.addItem(it);
                    jmax.updateJet();
                    jmax.splitted();
                    insert(members, jmax);
                }
                insert(members, imax);
            }
        }

        private static int indexOf(List<HepEntity> l, HepEntity e) {
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) == e) return i;
            }
            return -1;
        }
    }
}
