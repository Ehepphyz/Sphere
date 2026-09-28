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
 * fastjet::ATLASConePlugin, the ATLAS iterative cone with split-merge, from
 * SpartyJet (P.A. Delsart, K. Geerlings, J. Huston, B. Martin,
 * C. Vermilion): seeds above seedPt in Et order are iterated to stable cones
 * of radius R in (eta, phi) (to 0.05, at most 10 times, |eta| < 5); the cones
 * are then split or merged, those sharing more than a fraction f of the
 * softer one's pt being merged.
 *
 * The SpartyJet arithmetic is kept as it is (double, its tolerances, its
 * sort orders, which with an Et comparator blind below 1 MeV depend on the
 * sorting algorithm and replay libstdc++'s), so that the jets are the C++
 * ones; under {@link Precision#DD} only the four-momenta of the jets in the
 * ClusterSequence are summed to 106 bits.
 */
public final class ATLASConePlugin implements JetDefinition.Plugin {

    private static final String BANNER = String.join("\n",
        "#-------------------------------------------------------------------------",
        "# You are running the ATLAS Cone plugin for FastJet                       ",
        "# Original code from SpartyJet; interface by the FastJet authors          ",
        "# If you use this plugin, please cite                                     ",
        "#   P.A. Delsart, K. Geerlings, J. Huston, B. Martin and C. Vermilion,    ",
        "#   SpartyJet, http://projects.hepforge.org/spartyjet                     ",
        "# in addition to the usual FastJet reference.                             ",
        "#-------------------------------------------------------------------------");

    private final double radius;
    private final double seedPt;
    private final double f;

    public ATLASConePlugin(double radius) {
        this(radius, 2.0, 0.5);
    }

    public ATLASConePlugin(double radius, double seedPt, double f) {
        this.radius = radius;
        this.seedPt = seedPt;
        this.f = f;
    }

    public double seedPt() {
        return seedPt;
    }

    public double f() {
        return f;
    }

    @Override
    public double R() {
        return radius;
    }

    @Override
    public String description() {
        return "ATLASCone plugin with R = " + Fmt.g(radius) + ", seed threshold = " + Fmt.g(seedPt)
            + ", overlap threshold f = " + Fmt.g(f);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("ATLASCone", BANNER);
        final boolean dd = cs.precision() == Precision.DD;
        final List<AJet> jets = new ArrayList<>();
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet mom = cs.jet(i);
            final AJet particle = new AJet(mom.px(), mom.py(), mom.pz(), mom.E(), i);
            final AJet jet = new AJet();
            jet.index = particle.index;
            jet.addConstituent(particle);
            jets.add(jet);
        }

        List<AJet> stable = coneFinder(jets);
        stable = splitMerge(stable);

        for (AJet jet : stable) {
            if (jet.constituents.isEmpty()) {
                // a cone emptied by the splitting; the C++ code would read
                // past the end of its (empty) list here
                continue;
            }
            int jetK = jet.constituents.get(0).index;
            for (int c = 1; c < jet.constituents.size(); c++) {
                final int jetI = jetK;
                final int jetJ = jet.constituents.get(c).index;
                final PseudoJet newjet = cs.jet(jetI).plus(cs.jet(jetJ));
                jetK = cs.pluginRecordIJRecombination(jetI, jetJ, 0.0, newjet);
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
    /* atlas::Jet                                                          */
    /* ------------------------------------------------------------------ */

    /** atlas::Jet: a four-vector and the particles it is made of. */
    static final class AJet {
        double px, py, pz, e;
        int index;
        final List<AJet> constituents = new ArrayList<>();

        AJet() {
        }

        AJet(double px, double py, double pz, double e, int index) {
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.e = e;
            this.index = index;
        }

        double p() {
            return Math.sqrt(px * px + py * py + pz * pz);
        }

        double pt() {
            return Math.sqrt(px * px + py * py);
        }

        double et() {
            return e / p() * pt();
        }

        double eta() {
            return 0.5 * CRMath.log((p() + pz) / (p() - pz));
        }

        double phi() {
            double r = CRMath.atan2(py, px);
            if (r < 0) r += 2 * Math.PI;
            return r;
        }

        void add(AJet v) {
            px += v.px;
            py += v.py;
            pz += v.pz;
            e += v.e;
        }

        void subtract(AJet v) {
            px -= v.px;
            py -= v.py;
            pz -= v.pz;
            e -= v.e;
        }

        void addJet(AJet j) {
            add(j);
            constituents.addAll(j.constituents);
        }

        void addConstituent(AJet j) {
            constituents.add(j);
            add(j);
        }

        /** std::list::remove: every occurrence goes; the momentum once. */
        void removeConstituent(AJet j) {
            constituents.removeIf(c -> c == j);
            subtract(j);
        }
    }

    /** atlas::JetSorter_Et: blind to Et differences below 0.001. */
    private static boolean sorterEt(AJet j1, AJet j2) {
        if (Math.abs(j1.et() - j2.et()) < 0.001) return false;
        return j1.et() > j2.et();
    }

    private static double fixedPhi(double aPhi) {
        while (aPhi < -Math.PI) aPhi += 2. * Math.PI;
        while (aPhi > Math.PI) aPhi -= 2. * Math.PI;
        return aPhi;
    }

    private static double deltaR(double eta1, double phi1, double eta2, double phi2) {
        final double deta = eta1 - eta2;
        final double dphi = fixedPhi(phi1 - phi2);
        return Math.sqrt(deta * deta + dphi * dphi);
    }

    private static AJet jetFromOverlap(AJet j1, AJet j2) {
        final AJet j = new AJet();
        for (AJet c1 : j1.constituents) {
            for (AJet c2 : j2.constituents) {
                if (c1 == c2) j.addConstituent(c1);
            }
        }
        return j;
    }

    /* ------------------------------------------------------------------ */
    /* atlas::JetConeFinderTool                                            */
    /* ------------------------------------------------------------------ */

    private static final double EPS = 0.05;
    private static final double ETA_MAX = 5.0;

    private List<AJet> coneFinder(List<AJet> theJets) {
        StdAlgorithms.stableSort(theJets, ATLASConePlugin::sorterEt);
        final List<AJet> out = new ArrayList<>();
        if (theJets.isEmpty()) return out;
        final int n = theJets.size();
        final double[] etaIn = new double[n];
        final double[] phiIn = new double[n];
        for (int i = 0; i < n; i++) {
            etaIn[i] = theJets.get(i).eta();
            phiIn[i] = theJets.get(i).phi();
        }
        for (int t = 0; t < n; t++) {
            final AJet seed = theJets.get(t);
            if (seed.et() < seedPt) break;
            double etaT = etaIn[t];
            double phiT = phiIn[t];
            boolean stable = false;
            boolean inGeom = true;
            AJet preJet;
            int count = 1;
            do {
                preJet = calcCone(theJets, etaIn, phiIn, etaT, phiT);
                final double etaC = preJet.eta();
                final double phiC = preJet.phi();
                final double deta = Math.abs(etaT - etaC);
                final double dphi = Math.abs(fixedPhi(phiT - phiC));
                if (deta < EPS && dphi < EPS) stable = true;
                if (Math.abs(etaC) > ETA_MAX) inGeom = false;
                etaT = etaC;
                phiT = phiC;
                ++count;
            } while (!stable && inGeom && count < 10);
            if (count > 9 && (!stable && inGeom)) continue;
            if (stable && inGeom) {
                boolean newJet = true;
                final double etaP = preJet.eta();
                final double phiP = preJet.phi();
                for (AJet p : out) {
                    final double deta = Math.abs(etaP - p.eta());
                    final double dphi = Math.abs(fixedPhi(phiP - p.phi()));
                    if (deta < 0.05 && dphi < 0.05) {
                        newJet = false;
                        break;
                    }
                }
                if (newJet) out.add(preJet);
            }
        }
        return out;
    }

    private AJet calcCone(List<AJet> in, double[] etaIn, double[] phiIn, double eta, double phi) {
        final AJet j = new AJet();
        for (int i = 0; i < in.size(); i++) {
            if (deltaR(eta, phi, etaIn[i], phiIn[i]) < radius) j.addJet(in.get(i));
        }
        return j;
    }

    /* ------------------------------------------------------------------ */
    /* atlas::JetSplitMergeTool                                            */
    /* ------------------------------------------------------------------ */

    private List<AJet> splitMerge(List<AJet> theJets) {
        final List<AJet> preJet = new ArrayList<>();
        for (AJet in : theJets) {
            final AJet j = new AJet();
            j.addJet(in);
            preJet.add(j);
        }
        final List<AJet> jets = new ArrayList<>();
        if (preJet.size() >= 2) {
            do {
                StdAlgorithms.stableSort(preJet, ATLASConePlugin::sorterEt);
                final AJet first = preJet.get(0);
                boolean overlap = false;
                for (int k = 1; k < preJet.size(); k++) {
                    final AJet second = preJet.get(k);
                    final double etaF = first.eta();
                    final double phiF = first.phi();
                    final double etaS = second.eta();
                    final double phiS = second.phi();
                    final AJet oJet = jetFromOverlap(first, second);
                    if (!oJet.constituents.isEmpty()) {
                        overlap = true;
                        final double frac = Math.sqrt(oJet.px * oJet.px + oJet.py * oJet.py)
                            / Math.sqrt(second.px * second.px + second.py * second.py);
                        if (frac > f) {
                            // merge
                            for (AJet c : oJet.constituents) first.removeConstituent(c);
                            first.addJet(second);
                            preJet.remove(k);
                        }
                        if (frac <= f) {
                            // split: each shared particle stays with the nearer jet
                            for (AJet c : oJet.constituents) {
                                final double deta1 = etaF - c.eta();
                                final double dphi1 = Math.abs(fixedPhi(phiF - c.phi()));
                                final double dist1 = deta1 * deta1 + dphi1 * dphi1;
                                final double deta2 = etaS - c.eta();
                                final double dphi2 = Math.abs(fixedPhi(phiS - c.phi()));
                                final double dist2 = deta2 * deta2 + dphi2 * dphi2;
                                if (dist1 > dist2) first.removeConstituent(c);
                                if (dist1 <= dist2) second.removeConstituent(c);
                            }
                        }
                        break;
                    }
                }
                if (!overlap) {
                    jets.add(first);
                    preJet.remove(0);
                }
            } while (!preJet.isEmpty());
        } else if (preJet.size() == 1) {
            jets.add(preJet.get(0));
        }
        return jets;
    }
}
