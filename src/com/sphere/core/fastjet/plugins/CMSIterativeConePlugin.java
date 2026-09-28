package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * fastjet::CMSIterativeConePlugin, the CMS iterative cone (CMS physics TDR):
 * from the hardest remaining tower above the seed threshold, a cone of
 * radius R in (eta, phi) is moved to its Et-weighted centre until stable
 * (to 0.001, at most 100 times); its towers make a jet and are removed.
 *
 * The stable-cone search is the CMS code's own double arithmetic, tolerances
 * and ordering included, so that the jets are theirs; under
 * {@link Precision#DD} only the jets' four-momenta are summed to 106 bits.
 */
public final class CMSIterativeConePlugin implements JetDefinition.Plugin {

    private static final String BANNER = String.join("\n",
        "#-------------------------------------------------------------------------",
        "# You are running the CMS Iterative Cone plugin for FastJet               ",
        "# Original code by the CMS collaboration adapted by the FastJet authors   ",
        "# If you use this plugin, please cite                                     ",
        "#   G. L. Bayatian et al. [CMS Collaboration],                            ",
        "#   CMS physics: Technical design report.                                 ",
        "# in addition to the usual FastJet reference.                             ",
        "#-------------------------------------------------------------------------");

    private static final double EPS = Math.ulp(1.0);

    private final double coneRadius;
    private final double seedThreshold;

    public CMSIterativeConePlugin(double coneRadius) {
        this(coneRadius, 1.0);
    }

    public CMSIterativeConePlugin(double coneRadius, double seedThreshold) {
        this.coneRadius = coneRadius;
        this.seedThreshold = seedThreshold;
    }

    public double seedThreshold() {
        return seedThreshold;
    }

    @Override
    public String description() {
        return "CMSIterativeCone plugin with R = " + Fmt.g(coneRadius) + " and seed threshold = " + Fmt.g(seedThreshold);
    }

    @Override
    public double R() {
        return coneRadius;
    }

    /** An input tower and its jet index. */
    private static final class Tower {
        final int index;
        final double et, eta, phi, px, pz;

        Tower(PseudoJet p, int index) {
            this.index = index;
            et = p.Et();
            eta = p.eta();
            phi = p.phi();
            px = p.px();
            pz = p.pz();
        }
    }

    /** cms::NumericSafeGreaterByEt. */
    private static boolean greaterByEt(Tower a1, Tower a2) {
        final double et1 = a1.et;
        final double et2 = a2.et;
        return Math.abs(et1 - et2) > EPS ? et1 > et2
            : Math.abs(a1.px - a2.px) > EPS ? a1.px > a2.px
            : a1.pz > a2.pz;
    }

    private static double deltaPhi(double phi1, double phi2) {
        double result = phi1 - phi2;
        while (result > Math.PI) result -= 2 * Math.PI;
        while (result <= -Math.PI) result += 2 * Math.PI;
        return result;
    }

    private static double deltaR2(double eta1, double phi1, double eta2, double phi2) {
        final double deta = eta1 - eta2;
        final double dphi = deltaPhi(phi1, phi2);
        return deta * deta + dphi * dphi;
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("CMSIterativeCone", BANNER);
        final boolean dd = cs.precision() == Precision.DD;
        List<Tower> input = new ArrayList<>();
        for (int i = 0; i < cs.nJets(); i++) {
            input.add(new Tower(cs.jet(i), i));
        }
        StdAlgorithms.listSort(input, CMSIterativeConePlugin::greaterByEt);
        final double r2 = coneRadius * coneRadius;

        while (!input.isEmpty() && input.get(0).et > seedThreshold) {
            double eta0 = input.get(0).eta;
            double phi0 = input.get(0).phi;
            List<Tower> cone = new ArrayList<>();
            for (int iteration = 0; iteration < 100; iteration++) {
                cone = new ArrayList<>();
                double eta = 0;
                double phi = 0;
                double et = 0;
                for (Tower tower : input) {
                    if (deltaR2(eta0, phi0, tower.eta, tower.phi) < r2) {
                        final double towerEt = tower.et;
                        cone.add(tower);
                        eta += towerEt * tower.eta;
                        double dphi = tower.phi - phi0;
                        if (dphi > Math.PI) dphi -= 2 * Math.PI;
                        else if (dphi <= -Math.PI) dphi += 2 * Math.PI;
                        phi += towerEt * dphi;
                        et += towerEt;
                    }
                }
                eta = eta / et;
                phi = phi0 + phi / et;
                if (phi > Math.PI) phi -= 2 * Math.PI;
                else if (phi <= -Math.PI) phi += 2 * Math.PI;
                if (Math.abs(eta - eta0) < .001 && Math.abs(phi - phi0) < .001) break; // stable cone found
                eta0 = eta;
                phi0 = phi;
            }
            if (cone.isEmpty()) {
                // the C++ code has no answer to a cone that drifted off all
                // towers; the seed is then taken as a jet on its own
                cone.add(input.get(0));
            }

            final Map<Tower, Boolean> used = new IdentityHashMap<>();
            int jetK = cone.get(0).index;
            used.put(cone.get(0), Boolean.TRUE);
            for (int c = 1; c < cone.size(); c++) {
                final int jetI = jetK;
                final int jetJ = cone.get(c).index;
                final PseudoJet newjet = cs.jet(jetI).plus(cs.jet(jetJ));
                jetK = cs.pluginRecordIJRecombination(jetI, jetJ, 0.0, newjet);
                used.put(cone.get(c), Boolean.TRUE);
            }
            final PseudoJet jet = cs.jet(jetK);
            if (dd) {
                cs.pluginRecordIBRecombination(jetK, jet.kt2DD());
            } else {
                cs.pluginRecordIBRecombination(jetK, jet.perp2());
            }
            final List<Tower> remaining = new ArrayList<>(input.size() - used.size());
            for (Tower t : input) {
                if (!used.containsKey(t)) remaining.add(t);
            }
            input = remaining;
        }
    }
}
