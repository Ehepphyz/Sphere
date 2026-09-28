package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.CDFCones.Centroid;
import com.sphere.core.fastjet.plugins.CDFCones.Cluster;
import com.sphere.core.fastjet.plugins.CDFCones.LorentzVector;
import com.sphere.core.fastjet.plugins.CDFCones.PhysicsTower;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * fastjet::CDFJetCluPlugin, the CDF Run I JetClu cone (F. Abe et al., Phys.
 * Rev. D 45 (1992) 1448; code by J. Huston): seeds are the calorimeter
 * towers above threshold, gathered into preclusters of adjacent towers,
 * iterated to stable cones (keeping the precluster's towers when iratch is
 * set), then split or merged at overlap_threshold.
 *
 * The CDF arithmetic, tolerances and sorts (libstdc++'s std::sort replayed)
 * are kept, so that the jets are the C++ ones; under {@link Precision#DD}
 * only the four-momenta in the ClusterSequence carry 106 bits.
 */
public final class CDFJetCluPlugin implements JetDefinition.Plugin {

    private static final String BANNER = String.join("\n",
        "#-------------------------------------------------------------------------",
        "# You are running the CDF JetClu plugin for FastJet                       ",
        "# This is based on an implementation provided by Joey Huston.             ",
        "# If you use this plugin, please cite                                     ",
        "#   F. Abe et al. [CDF Collaboration], Phys. Rev. D 45 (1992) 1448.       ",
        "# in addition to the usual FastJet reference.                             ",
        "#-------------------------------------------------------------------------");

    private final double seedThreshold;
    private final double coneRadius;
    private final int adjacencyCut;
    private final int maxIterations;
    private final int iratch;
    private final double overlapThreshold;

    public CDFJetCluPlugin(double coneRadius, double overlapThreshold) {
        this(coneRadius, overlapThreshold, 1.0, 1);
    }

    public CDFJetCluPlugin(double coneRadius, double overlapThreshold, double seedThreshold) {
        this(coneRadius, overlapThreshold, seedThreshold, 1);
    }

    public CDFJetCluPlugin(double coneRadius, double overlapThreshold, double seedThreshold, int iratch) {
        this(seedThreshold, coneRadius, 2, 100, iratch, overlapThreshold);
    }

    public CDFJetCluPlugin(double seedThreshold, double coneRadius, int adjacencyCut, int maxIterations, int iratch,
                           double overlapThreshold) {
        this.seedThreshold = seedThreshold;
        this.coneRadius = coneRadius;
        this.adjacencyCut = adjacencyCut;
        this.maxIterations = maxIterations;
        this.iratch = iratch;
        this.overlapThreshold = overlapThreshold;
    }

    public double seedThreshold() { return seedThreshold; }
    public double coneRadius() { return coneRadius; }
    public int adjacencyCut() { return adjacencyCut; }
    public int maxIterations() { return maxIterations; }
    public int iratch() { return iratch; }
    public double overlapThreshold() { return overlapThreshold; }

    @Override
    public double R() {
        return coneRadius;
    }

    @Override
    public String description() {
        return "CDF JetClu jet algorithm with seed_threshold = " + Fmt.g(seedThreshold) + ", cone_radius = "
            + Fmt.g(coneRadius) + ", adjacency_cut = " + adjacencyCut + ", max_iterations = " + maxIterations
            + ", iratch = " + iratch + ", overlap_threshold = " + Fmt.g(overlapThreshold);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("CDFJetClu", BANNER);
        final boolean dd = cs.precision() == Precision.DD;
        final List<PhysicsTower> towers = new ArrayList<>(cs.nJets());
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet p = cs.jet(i);
            final PhysicsTower tower = new PhysicsTower(new LorentzVector(p.px(), p.py(), p.pz(), p.E()));
            tower.fjindex = i;
            towers.add(tower);
        }
        final List<Cluster> jets = run(towers);
        for (int ij = jets.size() - 1; ij >= 0; ij--) {
            final List<PhysicsTower> towerList = jets.get(ij).towerList;
            if (towerList.isEmpty()) continue;
            final int[] fj = new int[towerList.size()];
            for (int t = 0; t < fj.length; t++) fj[t] = towerList.get(t).fjindex;
            Arrays.sort(fj);
            int jetK = fj[0];
            for (int t = 1; t < fj.length; t++) {
                jetK = cs.pluginRecordIJRecombination(jetK, fj[t], 0.0);
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
    /* cdf::JetCluAlgorithm                                                */
    /* ------------------------------------------------------------------ */

    private List<Cluster> run(List<PhysicsTower> towers) {
        final List<Cluster> seedTowers = makeSeedTowers(towers);
        final List<Cluster> preClusters = buildPreClusters(seedTowers);
        final List<Cluster> stableCones = findStableCones(preClusters, towers);
        return splitAndMerge(stableCones);
    }

    private List<Cluster> makeSeedTowers(List<PhysicsTower> towers) {
        final List<Cluster> seedTowers = new ArrayList<>();
        for (int iEta = 4; iEta < 48; iEta++) {
            final boolean seg24 = !((iEta >= 8 && iEta < 14) || (iEta >= 38 && iEta < 44));
            for (int iPhi = 0; iPhi < 24; iPhi++) {
                final Cluster seed = new Cluster();
                for (PhysicsTower t : towers) {
                    if (t.iEta() == iEta
                        && ((seg24 && t.iPhi() == iPhi) || (!seg24 && (t.iPhi() == 2 * iPhi || t.iPhi() == 2 * iPhi + 1)))) {
                        seed.addTower(t);
                    }
                }
                if (seed.centroid.Et > seedThreshold) seedTowers.add(seed);
            }
        }
        StdAlgorithms.sort(seedTowers, CDFCones::centroidEtGreater);
        return seedTowers;
    }

    private static int halvedPhi(int iEta, int iPhi) {
        if ((iEta >= 8 && iEta < 14) || (iEta >= 38 && iEta < 44)) return iPhi / 2;
        return iPhi;
    }

    private List<Cluster> buildPreClusters(List<Cluster> seedTowers) {
        final List<Cluster> preClusters = new ArrayList<>();
        final List<Centroid> leadingSeedTowers = new ArrayList<>();
        for (Cluster seedTower : seedTowers) {
            boolean added = false;
            for (int pc = 0; pc < preClusters.size() && !added; pc++) {
                final Cluster preCluster = preClusters.get(pc);
                final Centroid leading = leadingSeedTowers.get(pc);
                final double dEta = Math.abs(seedTower.centroid.eta - leading.eta);
                double dPhi = Math.abs(seedTower.centroid.phi - leading.phi);
                if (dPhi > CDFCones.PI) dPhi = 2 * CDFCones.PI - dPhi;
                if (dEta <= coneRadius && dPhi <= coneRadius) {
                    final int iEtaSeed = seedTower.towerList.get(0).iEta();
                    final int iPhiSeed = halvedPhi(iEtaSeed, seedTower.towerList.get(0).iPhi());
                    // the list grows as towers are added: scan the towers there
                    // were when the scan started, as the C++ iterators would
                    // not (towers are only appended once a match is found)
                    for (int t = 0; t < preCluster.towerList.size() && !added; t++) {
                        final PhysicsTower pt = preCluster.towerList.get(t);
                        final int iEtaPre = pt.iEta();
                        final int iPhiPre = halvedPhi(iEtaPre, pt.iPhi());
                        final int dIEta = Math.abs(iEtaSeed - iEtaPre);
                        int dIPhi = Math.abs(iPhiSeed - iPhiPre);
                        if (dIPhi > 12) dIPhi = 24 - dIPhi;
                        final int adj = dIPhi * dIPhi + dIEta * dIEta;
                        if (adj <= adjacencyCut) {
                            for (PhysicsTower st : seedTower.towerList) preCluster.addTower(st);
                            added = true;
                        }
                    }
                }
            }
            if (!added) {
                final Cluster newPreCluster = new Cluster();
                for (PhysicsTower st : seedTower.towerList) newPreCluster.addTower(st);
                preClusters.add(newPreCluster);
                leadingSeedTowers.add(new Centroid(newPreCluster.centroid.Et, newPreCluster.centroid.eta,
                    newPreCluster.centroid.phi));
            }
        }
        return preClusters;
    }

    private List<Cluster> findStableCones(List<Cluster> preClusters, List<PhysicsTower> towers) {
        final List<Cluster> stableCones = new ArrayList<>();
        for (Cluster preCluster : preClusters) {
            double startEt = preCluster.centroid.Et;
            double startEta = preCluster.centroid.eta;
            double startPhi = preCluster.centroid.phi;
            int nIterations = 0;
            final Cluster trialCone = new Cluster();
            while (nIterations++ < maxIterations) {
                trialCone.clear();
                for (PhysicsTower t : towers) {
                    final double dEta = Math.abs(t.eta() - startEta);
                    double dPhi = Math.abs(t.phi() - startPhi);
                    if (dPhi > CDFCones.PI) dPhi = 2 * CDFCones.PI - dPhi;
                    final double dR = Math.sqrt(dEta * dEta + dPhi * dPhi);
                    if (dR < coneRadius) trialCone.addTower(t);
                }
                if (iratch != 0) {
                    for (PhysicsTower pt : preCluster.towerList) {
                        boolean found = false;
                        for (int k = 0; k < trialCone.towerList.size() && !found; k++) {
                            if (trialCone.towerList.get(k).isEqual(pt)) found = true;
                        }
                        if (!found) trialCone.addTower(pt);
                    }
                }
                if (nIterations <= maxIterations) {
                    final double endEt = trialCone.centroid.Et;
                    final double endEta = trialCone.centroid.eta;
                    final double endPhi = trialCone.centroid.phi;
                    if (endEt == startEt && endEta == startEta && endPhi == startPhi) {
                        nIterations = maxIterations;
                    } else {
                        startEt = endEt;
                        startEta = endEta;
                        startPhi = endPhi;
                    }
                }
            }
            stableCones.add(new Cluster(trialCone));
        }
        StdAlgorithms.sort(stableCones, CDFCones::centroidEtGreater);
        return stableCones;
    }

    private List<Cluster> splitAndMerge(List<Cluster> stableCones) {
        final int n = stableCones.size();
        final boolean[] isActive = new boolean[n];
        Arrays.fill(isActive, true);
        for (int i1 = 0; i1 < n; i1++) {
            final Cluster cone1 = stableCones.get(i1);
            for (int i2 = 0; i2 != i1 && isActive[i1]; i2++) {
                if (!isActive[i2]) continue;
                final Cluster cone2 = stableCones.get(i2);
                final Cluster overlap = new Cluster();
                for (PhysicsTower t1 : cone1.towerList) {
                    for (PhysicsTower t2 : cone2.towerList) {
                        if (t1.isEqual(t2)) {
                            overlap.addTower(t1);
                            break;
                        }
                    }
                }
                if (overlap.size() == 0) continue;
                if (overlap.size() == cone2.size()) {
                    isActive[i2] = false;
                } else if (overlap.size() == cone1.size()) {
                    isActive[i1] = false;
                } else if (overlap.centroid.Et > overlapThreshold * cone1.centroid.Et
                           || overlap.centroid.Et > overlapThreshold * cone2.centroid.Et) {
                    // merge
                    for (PhysicsTower t2 : new ArrayList<>(cone2.towerList)) {
                        boolean isInOverlap = false;
                        for (int k = 0; k < overlap.towerList.size() && !isInOverlap; k++) {
                            if (t2.isEqual(overlap.towerList.get(k))) isInOverlap = true;
                        }
                        if (!isInOverlap) cone1.addTower(t2);
                    }
                    isActive[i2] = false;
                } else {
                    split(cone1, cone2, overlap);
                }
            }
        }
        final List<Cluster> jets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isActive[i]) jets.add(new Cluster(stableCones.get(i)));
        }
        StdAlgorithms.sort(jets, CDFCones::fourVectorEtGreater);
        return jets;
    }

    /** The shared towers each go to the nearer cone, iterating the cones' centroids. */
    private void split(Cluster cone1, Cluster cone2, Cluster overlap) {
        Cluster removeFrom1 = new Cluster();
        Cluster removeFrom2 = new Cluster();
        final Cluster oldRemoveFrom1 = new Cluster();
        final Cluster oldRemoveFrom2 = new Cluster();
        double eta1 = cone1.centroid.eta;
        double phi1 = cone1.centroid.phi;
        double eta2 = cone2.centroid.eta;
        double phi2 = cone2.centroid.phi;
        int iterCount = 0;
        while (iterCount++ <= maxIterations) {
            oldRemoveFrom1.clear();
            oldRemoveFrom2.clear();
            if (iterCount > 1) {
                if (removeFrom1.size() != 0) {
                    final Centroid c1 = new Centroid(cone1.centroid);
                    c1.subtract(new Centroid(removeFrom1.centroid));
                    eta1 = c1.eta;
                    phi1 = c1.phi;
                } else {
                    eta1 = cone1.centroid.eta;
                    phi1 = cone1.centroid.phi;
                }
                if (removeFrom2.size() != 0) {
                    final Centroid c2 = new Centroid(cone2.centroid);
                    c2.subtract(new Centroid(removeFrom2.centroid));
                    eta2 = c2.eta;
                    phi2 = c2.phi;
                } else {
                    eta2 = cone2.centroid.eta;
                    phi2 = cone2.centroid.phi;
                }
                for (PhysicsTower t : removeFrom1.towerList) oldRemoveFrom1.addTower(t);
                for (PhysicsTower t : removeFrom2.towerList) oldRemoveFrom2.addTower(t);
            }
            removeFrom1 = new Cluster();
            removeFrom2 = new Cluster();
            for (PhysicsTower t : overlap.towerList) {
                final double dEta1 = Math.abs(t.eta() - eta1);
                double dPhi1 = Math.abs(t.phi() - phi1);
                if (dPhi1 > CDFCones.PI) dPhi1 = 2 * CDFCones.PI - dPhi1;
                final double dR1 = dEta1 * dEta1 + dPhi1 * dPhi1;
                final double dEta2 = Math.abs(t.eta() - eta2);
                double dPhi2 = Math.abs(t.phi() - phi2);
                if (dPhi2 > CDFCones.PI) dPhi2 = 2 * CDFCones.PI - dPhi2;
                final double dR2 = dEta2 * dEta2 + dPhi2 * dPhi2;
                if (dR1 < dR2) removeFrom2.addTower(t);
                else removeFrom1.addTower(t);
            }
            if (iterCount > 1
                && removeFrom1.size() == oldRemoveFrom1.size()
                && removeFrom2.size() == oldRemoveFrom2.size()
                && (removeFrom1.size() == 0 || removeFrom2.size() == 0
                    || (removeFrom1.centroid.isEqual(oldRemoveFrom1.centroid)
                        && removeFrom2.centroid.isEqual(oldRemoveFrom2.centroid)))) {
                iterCount = maxIterations + 1;
            }
        }
        for (PhysicsTower t : removeFrom1.towerList) cone1.removeTower(t);
        for (PhysicsTower t : removeFrom2.towerList) cone2.removeTower(t);
    }
}
