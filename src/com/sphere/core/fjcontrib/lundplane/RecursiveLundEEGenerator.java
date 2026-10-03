package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.lundplane.LundEEHelpers.Matrix3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lund diagram of an e+e- event, RecursiveLundEEGenerator (LundPlane
 * 2.1.2; with the psibar of A. Karlberg, G.P. Salam, L. Scyboz and
 * R. Verheyen, arXiv:2103.16526): from a clustering by an e+e- C/A
 * algorithm (EECambridgePlugin with ycut = 1), the two exclusive jets are
 * declustered, their softer branches followed down to the requested depth
 * (-1: all), and every declustering returned in order of decreasing kt.
 */
public class RecursiveLundEEGenerator {

    static {
        ContribCitations.use("lund");
    }

    private final int maxDepth;
    private final PseudoJet nx = new PseudoJet(1, 0, 0, 0);
    private final PseudoJet ny = new PseudoJet(0, 1, 0, 0);
    private final boolean dynamicalPsiRef;

    public RecursiveLundEEGenerator() {
        this(0, false);
    }

    public RecursiveLundEEGenerator(int maxDepth) {
        this(maxDepth, false);
    }

    public RecursiveLundEEGenerator(int maxDepth, boolean dynamicalPsiRef) {
        this.maxDepth = maxDepth;
        this.dynamicalPsiRef = dynamicalPsiRef;
    }

    public List<LundEEDeclustering> result(ClusterSequence cs) {
        final List<PseudoJet> exclusiveJets = new ArrayList<>(cs.exclusiveJets(2));
        if (exclusiveJets.size() != 2) throw new FastJetException("RecursiveLundEEGenerator: two exclusive jets expected");
        if (exclusiveJets.get(0).pz() < exclusiveJets.get(1).pz()) {
            final PseudoJet t = exclusiveJets.get(0);
            exclusiveJets.set(0, exclusiveJets.get(1));
            exclusiveJets.set(1, t);
        }
        final PseudoJet dEv = exclusiveJets.get(0).minus(exclusiveJets.get(1));
        final Matrix3 rotmat = Matrix3.fromDirection(dEv);
        final List<LundEEDeclustering> declusterings = new ArrayList<>();
        final int[] maxIplaneSofar = {1};
        final PseudoJet[] refPlane = {new PseudoJet()};
        final boolean[] firstTime = {true};
        for (int ijet = 0; ijet < exclusiveJets.size(); ijet++) {
            final int signS = ijet == 0 ? +1 : -1;
            appendToVector(declusterings, exclusiveJets.get(ijet), 0, ijet, maxIplaneSofar, rotmat, signS, refPlane, 0.0, firstTime);
        }
        declusterings.sort((d1, d2) -> Double.compare(d2.kt(), d1.kt()));
        return declusterings;
    }

    private void appendToVector(List<LundEEDeclustering> declusterings, PseudoJet jet, int depth, int iplane,
                                int[] maxIplaneSofar, Matrix3 rotmat, int signS, PseudoJet[] psibarRefPlane,
                                double lastPsibar, boolean[] firstTime) {
        final PseudoJet[] parents = jet.parents();
        if (parents == null) return;
        PseudoJet j1 = parents[0];
        PseudoJet j2 = parents[1];
        if (j1.modp2() < j2.modp2()) {
            final PseudoJet t = j1;
            j1 = j2;
            j2 = t;
        }
        final Matrix3 newRotmat = dynamicalPsiRef
            ? Matrix3.fromDirection(rotmat.transpose().times(jet.times(signS))).times(rotmat)
            : rotmat;
        final PseudoJet rx = newRotmat.times(nx);
        final PseudoJet ry = newRotmat.times(ny);
        final PseudoJet u1 = j1.divide(j1.modp());
        final PseudoJet u2 = j2.divide(j2.modp());
        final PseudoJet du = u2.minus(u1);
        final double x = du.px() * rx.px() + du.py() * rx.py() + du.pz() * rx.pz();
        final double y = du.px() * ry.px() + du.py() * ry.py() + du.pz() * ry.pz();
        final double psi = CRMath.atan2(y, x);

        final double psibar;
        final PseudoJet n2;
        if (firstTime[0]) {
            if (lastPsibar != 0.0) throw new FastJetException("RecursiveLundEEGenerator: psibar reference inconsistent");
            psibar = 0.0;
            n2 = LundEEHelpers.crossProduct(j1, j2);
            n2.divideEqual(n2.modp());
            psibarRefPlane[0] = n2;
            firstTime[0] = false;
        } else {
            n2 = LundEEHelpers.crossProduct(j1, j2);
            n2.divideEqual(n2.modp());
            psibar = LundEEHelpers.mapToPi(lastPsibar + signS * LundEEHelpers.signedAngleBetweenPlanes(psibarRefPlane[0], n2, j1));
        }

        int leafIplane = -1;
        final boolean recurseIntoSofter = depth < maxDepth || maxDepth < 0;
        if (recurseIntoSofter) {
            maxIplaneSofar[0] += 1;
            leafIplane = maxIplaneSofar[0];
        }
        declusterings.add(new LundEEDeclustering(jet, j1, j2, iplane, psi, psibar, depth, leafIplane, signS));
        final boolean[] lclFirstTime = {false};
        final PseudoJet[] ref = {n2};
        appendToVector(declusterings, j1, depth, iplane, maxIplaneSofar, newRotmat, signS, ref, psibar, lclFirstTime);
        if (recurseIntoSofter) {
            appendToVector(declusterings, j2, depth + 1, leafIplane, maxIplaneSofar, newRotmat, signS, ref, psibar, lclFirstTime);
        }
    }
}
