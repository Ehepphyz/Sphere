package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.CDFCones.Cluster;
import com.sphere.core.fastjet.plugins.CDFCones.LorentzVector;
import com.sphere.core.fastjet.plugins.CDFCones.PhysicsTower;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::CDFMidPointPlugin, the CDF Run II midpoint cone (G.C. Blazey et
 * al., hep-ex/0005012; code by J. Huston): stable cones iterated from every
 * particle above the seed threshold (in a cone reduced by
 * sqrt(cone_area_fraction), the "searchcone", then one full-size step), then
 * from the midpoints of all sets of up to max_pair_size stable cones within
 * 2R of each other, followed by a split-merge in the chosen scale.
 *
 * The CDF arithmetic, the exact-equality stability test and the sorts
 * (libstdc++'s std::sort replayed) are kept, so that the jets are the C++
 * ones; under {@link Precision#DD} only the four-momenta in the
 * ClusterSequence carry 106 bits.
 */
public final class CDFMidPointPlugin implements JetDefinition.Plugin {

    public enum SplitMergeScale {
        SM_PT, SM_ET, SM_MT, SM_PTTILDE
    }

    private static final String BANNER = String.join("\n",
        "#-------------------------------------------------------------------------",
        "# You are running the CDF MidPoint plugin for FastJet                     ",
        "# This is based on an implementation provided by Joey Huston.             ",
        "# If you use this plugin, please cite                                     ",
        "#   G. C. Blazey et al., hep-ex/0005012.                                  ",
        "# in addition to the usual FastJet reference.                             ",
        "#-------------------------------------------------------------------------");

    private final double seedThreshold;
    private final double coneRadius;
    private final double coneAreaFraction;
    private final int maxPairSize;
    private final int maxIterations;
    private final double overlapThreshold;
    private final SplitMergeScale smScale;

    public CDFMidPointPlugin(double seedThreshold, double coneRadius, double coneAreaFraction, int maxPairSize,
                             int maxIterations, double overlapThreshold) {
        this(seedThreshold, coneRadius, coneAreaFraction, maxPairSize, maxIterations, overlapThreshold,
             SplitMergeScale.SM_PT);
    }

    public CDFMidPointPlugin(double seedThreshold, double coneRadius, double coneAreaFraction, int maxPairSize,
                             int maxIterations, double overlapThreshold, SplitMergeScale smScale) {
        this.seedThreshold = seedThreshold;
        this.coneRadius = coneRadius;
        this.coneAreaFraction = coneAreaFraction;
        this.maxPairSize = maxPairSize;
        this.maxIterations = maxIterations;
        this.overlapThreshold = overlapThreshold;
        this.smScale = smScale;
    }

    /** The short form: (cone radius, overlap threshold[, seed threshold[, cone area fraction]]). */
    public static CDFMidPointPlugin of(double coneRadius, double overlapThreshold) {
        return of(coneRadius, overlapThreshold, 1.0, 1.0);
    }

    public static CDFMidPointPlugin of(double coneRadius, double overlapThreshold, double seedThreshold,
                                       double coneAreaFraction) {
        return new CDFMidPointPlugin(seedThreshold, coneRadius, coneAreaFraction, 2, 100, overlapThreshold);
    }

    public double seedThreshold() { return seedThreshold; }
    public double coneRadius() { return coneRadius; }
    public double coneAreaFraction() { return coneAreaFraction; }
    public int maxPairSize() { return maxPairSize; }
    public int maxIterations() { return maxIterations; }
    public double overlapThreshold() { return overlapThreshold; }
    public SplitMergeScale splitMergeScale() { return smScale; }

    @Override
    public double R() {
        return coneRadius;
    }

    @Override
    public String description() {
        final String sm = switch (smScale) {
            case SM_PT -> "pt";
            case SM_ET -> "Et";
            case SM_MT -> "mt";
            case SM_PTTILDE -> "pttilde (scalar sum of pts)";
        };
        return (coneAreaFraction == 1 ? "CDF MidPoint jet algorithm, with " : "CDF MidPoint+Searchcone jet algorithm, with ")
            + "seed_threshold = " + Fmt.g(seedThreshold) + ", cone_radius = " + Fmt.g(coneRadius)
            + ", cone_area_fraction = " + Fmt.g(coneAreaFraction) + ", max_pair_size = " + maxPairSize
            + ", max_iterations = " + maxIterations + ", overlap_threshold  = " + Fmt.g(overlapThreshold)
            + ", split-merge uses " + sm;
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("CDFMidPoint", BANNER);
        final boolean dd = cs.precision() == Precision.DD;
        final List<PhysicsTower> towers = new ArrayList<>(cs.nJets());
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet p = cs.jet(i);
            final PhysicsTower tower = new PhysicsTower(new LorentzVector(p.px(), p.py(), p.pz(), p.E()));
            tower.calTower.iEta = i;
            towers.add(tower);
        }
        final List<Cluster> jets = new ArrayList<>();
        final List<Cluster> stableCones = new ArrayList<>();
        findStableConesFromSeeds(towers, stableCones);
        if (!stableCones.isEmpty()) {
            findStableConesFromMidPoints(towers, stableCones);
            splitAndMerge(stableCones, jets);
        }
        for (Cluster jet : jets) {
            final List<PhysicsTower> towerList = jet.towerList;
            if (towerList.isEmpty()) continue;
            int jetK = towerList.get(0).calTower.iEta;
            for (int t = 1; t < towerList.size(); t++) {
                jetK = cs.pluginRecordIJRecombination(jetK, towerList.get(t).calTower.iEta, 0.0);
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
    /* cdf::MidPointAlgorithm                                              */
    /* ------------------------------------------------------------------ */

    private void findStableConesFromSeeds(List<PhysicsTower> towers, List<Cluster> stableCones) {
        for (PhysicsTower t : towers) {
            if (t.fourVector.pt() > seedThreshold) {
                iterateCone(t.fourVector.y(), t.fourVector.phi(), 0, towers, stableCones, true);
            }
        }
    }

    private void findStableConesFromMidPoints(List<PhysicsTower> towers, List<Cluster> stableCones) {
        final int n = stableCones.size();
        final boolean[][] distanceOK = new boolean[Math.max(n - 1, 0)][];
        for (int c1 = 1; c1 < n; c1++) {
            distanceOK[c1 - 1] = new boolean[c1];
            final double y1 = stableCones.get(c1).fourVector.y();
            final double phi1 = stableCones.get(c1).fourVector.phi();
            for (int c2 = 0; c2 < c1; c2++) {
                final double y2 = stableCones.get(c2).fourVector.y();
                final double phi2 = stableCones.get(c2).fourVector.phi();
                final double dRapidity = Math.abs(y1 - y2);
                double dPhi = Math.abs(phi1 - phi2);
                if (dPhi > CDFCones.PI) dPhi = 2 * CDFCones.PI - dPhi;
                final double dR = Math.sqrt(dRapidity * dRapidity + dPhi * dPhi);
                distanceOK[c1 - 1][c2] = dR < 2 * coneRadius;
            }
        }
        final List<int[]> pairs = new ArrayList<>();
        int maxClustersInPair = maxPairSize;
        if (maxClustersInPair == 0) maxClustersInPair = n;
        addClustersToPairs(new ArrayList<>(), pairs, distanceOK, maxClustersInPair);
        for (int[] pair : pairs) {
            final LorentzVector midPoint = new LorentzVector(0, 0, 0, 0);
            for (int member : pair) midPoint.add(stableCones.get(member).fourVector);
            iterateCone(midPoint.y(), midPoint.phi(), midPoint.pt(), towers, stableCones, false);
        }
        localSort(stableCones);
    }

    private void iterateCone(double startRapidity, double startPhi, double startPt, List<PhysicsTower> towers,
                             List<Cluster> stableCones, boolean reduceConeSize) {
        int nIterations = 0;
        boolean keepJet = true;
        final Cluster trialCone = new Cluster();
        double iterationConeRadius = coneRadius;
        if (reduceConeSize) iterationConeRadius *= Math.sqrt(coneAreaFraction);
        while (nIterations++ < maxIterations + 1 && keepJet) {
            trialCone.clear();
            if (nIterations == maxIterations + 1) iterationConeRadius = coneRadius;
            for (PhysicsTower t : towers) {
                final double dRapidity = Math.abs(t.fourVector.y() - startRapidity);
                double dPhi = Math.abs(t.fourVector.phi() - startPhi);
                if (dPhi > CDFCones.PI) dPhi = 2 * CDFCones.PI - dPhi;
                final double dR = Math.sqrt(dRapidity * dRapidity + dPhi * dPhi);
                if (dR < iterationConeRadius) trialCone.addTower(t);
            }
            if (trialCone.size() == 0) {
                keepJet = false;
            } else if (nIterations <= maxIterations) {
                final double endRapidity = trialCone.fourVector.y();
                final double endPhi = trialCone.fourVector.phi();
                final double endPt = trialCone.fourVector.pt();
                if (endRapidity == startRapidity && endPhi == startPhi && endPt == startPt) {
                    // stable: one more (full-size) iteration if the cone was reduced
                    nIterations = maxIterations;
                    if (!reduceConeSize) nIterations++;
                } else {
                    startRapidity = endRapidity;
                    startPhi = endPhi;
                    startPt = endPt;
                }
            }
        }
        if (keepJet) {
            boolean identical = false;
            for (Cluster c : stableCones) {
                if (trialCone.fourVector.isEqual(c.fourVector)) identical = true;
            }
            if (!identical) stableCones.add(new Cluster(trialCone));
        }
    }

    private void addClustersToPairs(List<Integer> testPair, List<int[]> pairs, boolean[][] distanceOK,
                                    int maxClustersInPair) {
        int nextClusterStart = 0;
        if (!testPair.isEmpty()) nextClusterStart = testPair.get(testPair.size() - 1) + 1;
        for (int next = nextClusterStart; next <= distanceOK.length; next++) {
            boolean addCluster = true;
            for (int i = 0; i < testPair.size() && addCluster; i++) {
                if (!distanceOK[next - 1][testPair.get(i)]) addCluster = false;
            }
            if (addCluster) {
                testPair.add(next);
                if (testPair.size() > 1) {
                    final int[] pair = new int[testPair.size()];
                    for (int k = 0; k < pair.length; k++) pair[k] = testPair.get(k);
                    pairs.add(pair);
                }
                if (testPair.size() < maxClustersInPair) {
                    addClustersToPairs(testPair, pairs, distanceOK, maxClustersInPair);
                }
                testPair.remove(testPair.size() - 1);
            }
        }
    }

    private double scale(Cluster c) {
        return switch (smScale) {
            case SM_PT -> c.fourVector.pt();
            case SM_ET -> c.fourVector.Et();
            case SM_MT -> c.fourVector.mt();
            case SM_PTTILDE -> c.ptTilde;
        };
    }

    private void splitAndMerge(List<Cluster> stableCones, List<Cluster> jets) {
        boolean mergingNotFinished = true;
        while (mergingNotFinished) {
            localSort(stableCones);
            if (stableCones.isEmpty()) {
                mergingNotFinished = false;
                continue;
            }
            final Cluster cone1 = stableCones.get(0);
            boolean coneNotModified = true;
            int i2 = 1;
            while (coneNotModified && i2 < stableCones.size()) {
                final Cluster cone2 = stableCones.get(i2);
                final Cluster overlap = new Cluster();
                for (PhysicsTower t1 : cone1.towerList) {
                    boolean isInCone2 = false;
                    for (PhysicsTower t2 : cone2.towerList) {
                        if (t1.isEqual(t2)) isInCone2 = true;
                    }
                    if (isInCone2) overlap.addTower(t1);
                }
                if (overlap.size() != 0) {
                    coneNotModified = false;
                    final double overlapScale = scale(overlap);
                    final double jet2Scale = scale(cone2);
                    if (overlapScale >= overlapThreshold * jet2Scale) {
                        // merge the two cones
                        for (PhysicsTower t2 : cone2.towerList) {
                            boolean isInOverlap = false;
                            for (PhysicsTower o : overlap.towerList) {
                                if (t2.isEqual(o)) isInOverlap = true;
                            }
                            if (!isInOverlap) cone1.addTower(t2);
                        }
                        stableCones.remove(i2);
                    } else {
                        // each shared particle goes to the nearer cone
                        final List<PhysicsTower> removeFrom1 = new ArrayList<>();
                        final List<PhysicsTower> removeFrom2 = new ArrayList<>();
                        for (PhysicsTower t : overlap.towerList) {
                            final double ty = t.fourVector.y();
                            final double tphi = t.fourVector.phi();
                            final double dRapidity1 = Math.abs(ty - cone1.fourVector.y());
                            double dPhi1 = Math.abs(tphi - cone1.fourVector.phi());
                            if (dPhi1 > CDFCones.PI) dPhi1 = 2 * CDFCones.PI - dPhi1;
                            final double dR1 = Math.sqrt(dRapidity1 * dRapidity1 + dPhi1 * dPhi1);
                            final double dRapidity2 = Math.abs(ty - cone2.fourVector.y());
                            double dPhi2 = Math.abs(tphi - cone2.fourVector.phi());
                            if (dPhi2 > CDFCones.PI) dPhi2 = 2 * CDFCones.PI - dPhi2;
                            final double dR2 = Math.sqrt(dRapidity2 * dRapidity2 + dPhi2 * dPhi2);
                            if (dR1 < dR2) removeFrom2.add(t);
                            else removeFrom1.add(t);
                        }
                        for (PhysicsTower t : removeFrom1) cone1.removeTower(t);
                        for (PhysicsTower t : removeFrom2) cone2.removeTower(t);
                    }
                }
                i2++;
            }
            if (coneNotModified) {
                jets.add(new Cluster(cone1));
                stableCones.remove(0);
            }
        }
        localSort(jets);
    }

    private void localSort(List<Cluster> clusters) {
        switch (smScale) {
            case SM_PT -> StdAlgorithms.sort(clusters, CDFCones::ptGreater);
            case SM_ET -> StdAlgorithms.sort(clusters, CDFCones::fourVectorEtGreater);
            case SM_MT -> StdAlgorithms.sort(clusters, CDFCones::mtGreater);
            case SM_PTTILDE -> StdAlgorithms.sort(clusters, CDFCones::ptTildeGreater);
            default -> throw new FastJetException("Unrecognized split-merge scale choice = " + smScale);
        }
    }
}
