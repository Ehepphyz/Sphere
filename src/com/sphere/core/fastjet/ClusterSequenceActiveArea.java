package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * Active areas with hidden ghosts, fastjet::ClusterSequenceActiveArea.
 *
 * The event is clustered with ghosts (once per repetition); the history
 * without the ghosts is taken from the first repetition, and every jet of it
 * gets the average of the areas it had in each ghosted clustering, with the
 * spread as its error.
 */
public class ClusterSequenceActiveArea extends ClusterSequenceAreaBase {

    /** How pt_per_unit_area estimates the background, for the old interface. */
    public enum MeanPtStrategy { MEDIAN, NON_GHOST_MEDIAN, PTTOT_OVER_AREATOT, PTTOT_OVER_AREATOT_CUT,
        MEAN_RATIO_CUT, PLAY, MEDIAN_4VECTOR }

    protected double[] averageArea;
    protected double[] averageArea2;
    protected PseudoJet[] averageArea4vector;
    private double nonJetArea, nonJetArea2, nonJetNumber;
    private double maxrapForArea;
    private double safeRapForArea;
    private boolean hasDangerousParticles;
    private int ghostSpecRepeat;

    /** A pure-ghost jet with its area. */
    protected record GhostJet(PseudoJet jet, double area) { }

    protected final List<GhostJet> ghostJets = new ArrayList<>();
    protected final List<GhostJet> unclusteredGhosts = new ArrayList<>();

    protected ClusterSequenceActiveArea() {
    }

    public ClusterSequenceActiveArea(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                     GhostedAreaSpec ghostSpec) {
        this(particles, jetDef, ghostSpec, false);
    }

    public ClusterSequenceActiveArea(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                     GhostedAreaSpec ghostSpec, boolean writeout) {
        transferInputJets(particles, jetDef.precision());
        initialiseAndRunAA(jetDef, ghostSpec, writeout);
    }

    /** Copies the input into the sequence under a given precision. */
    protected final void transferInputJets(List<? extends PseudoJet> particles, Precision p) {
        jets.ensureCapacity(particles.size() * 2);
        for (PseudoJet particle : particles) {
            final PseudoJet c = particle.copy();
            c.setPrecision(p);
            jets.add(c);
        }
    }

    protected final void initialiseAndRunAA(JetDefinition jetDefIn, GhostedAreaSpec ghostSpec, boolean writeout) {
        if (initialiseAA(jetDefIn, ghostSpec, writeout)) {
            runAA(ghostSpec);
            postprocessAA(ghostSpec);
        }
    }

    protected final void resizeAndZeroAA() {
        final int n = 2 * jets.size();
        averageArea = new double[n];
        averageArea2 = new double[n];
        averageArea4vector = new PseudoJet[n];
        final Precision p = jetDef == null ? Precision.defaultPrecision() : jetDef.precision();
        for (int i = 0; i < n; i++) {
            averageArea4vector[i] = new PseudoJet(0.0, 0.0, 0.0, 0.0, p);
        }
        nonJetArea = 0.0;
        nonJetArea2 = 0.0;
        nonJetNumber = 0.0;
    }

    /** Returns true when the ghosted runs are still to be done. */
    protected final boolean initialiseAA(JetDefinition jetDefIn, GhostedAreaSpec ghostSpec, boolean writeout) {
        ghostSpecRepeat = ghostSpec.repeat();
        jetDef = jetDefIn;
        resizeAndZeroAA();
        maxrapForArea = ghostSpec.ghostMaxrap();
        safeRapForArea = maxrapForArea - jetDefIn.R();
        if (ghostSpec.repeat() <= 0) {
            initialiseAndRun(jetDefIn, writeout);
            return false;
        }
        decantOptions(jetDefIn, writeout);
        fillInitialHistory();
        hasDangerousParticles = false;
        return true;
    }

    private void runAA(GhostedAreaSpec ghostSpec) {
        final List<PseudoJet> inputJets = new ArrayList<>(jets.size());
        for (PseudoJet j : jets) {
            inputJets.add(j.copy());
        }
        int[] uniqueTree = null;
        for (int irepeat = 0; irepeat < ghostSpec.repeat(); irepeat++) {
            final ClusterSequenceActiveAreaExplicitGhosts cs =
                new ClusterSequenceActiveAreaExplicitGhosts(inputJets, jetDef(), ghostSpec);
            hasDangerousParticles |= cs.hasDangerousParticles();
            if (irepeat == 0) {
                transferGhostFreeHistory(cs);
                uniqueTree = uniqueHistoryOrder();
            }
            transferAreas(uniqueTree, cs);
        }
    }

    protected final void postprocessAA(GhostedAreaSpec ghostSpec) {
        final int rep = ghostSpec.repeat();
        for (int i = 0; i < averageArea.length; i++) {
            averageArea[i] /= rep;
            averageArea2[i] /= rep;
        }
        if (rep > 1) {
            final double tmp = rep - 1;
            for (int i = 0; i < averageArea.length; i++) {
                averageArea2[i] = Math.sqrt(Math.abs(averageArea2[i] - averageArea[i] * averageArea[i]) / tmp);
            }
        } else {
            for (int i = 0; i < averageArea.length; i++) {
                averageArea2[i] = 0.0;
            }
        }
        nonJetArea /= rep;
        nonJetArea2 /= rep;
        nonJetArea2 = Math.sqrt(Math.abs(nonJetArea2 - nonJetArea * nonJetArea) / rep);
        nonJetNumber /= rep;
        for (int i = 0; i < averageArea4vector.length; i++) {
            averageArea4vector[i] = averageArea4vector[i].times(1.0 / rep);
        }
    }

    /** Rebuilds our own history from a ghosted one, dropping every ghost. */
    protected final void transferGhostFreeHistory(ClusterSequenceActiveAreaExplicitGhosts ghosted) {
        final List<HistoryElement> gsHistory = ghosted.history();
        final int[] gs2self = new int[gsHistory.size()];
        strategy = ghosted.strategyUsed();
        int igs = 0;
        int iself = 0;
        while (igs < gsHistory.size() && gsHistory.get(igs).parent1 == INEXISTENT_PARENT) {
            if (!ghosted.isPureGhost(igs)) {
                gs2self[igs] = iself++;
            } else {
                gs2self[igs] = INVALID;
            }
            igs++;
        }
        if (iself != history.size()) {
            throw new FastJetException.Internal("ghost-free history does not match the input");
        }
        if (igs == gsHistory.size()) {
            return;
        }
        do {
            if (ghosted.isPureGhost(igs)) {
                gs2self[igs] = INVALID;
                continue;
            }
            final HistoryElement el = gsHistory.get(igs);
            final boolean p1Ghost = ghosted.isPureGhost(el.parent1);
            final boolean p2Ghost = ghosted.isPureGhost(el.parent2);
            if (p1Ghost && !p2Ghost && el.parent2 >= 0) {
                gs2self[igs] = gs2self[el.parent2];
                continue;
            }
            if (!p1Ghost && p2Ghost) {
                gs2self[igs] = gs2self[el.parent1];
                continue;
            }
            if (el.parent2 >= 0) {
                gs2self[igs] = history.size();
                final int jetI = history.get(gs2self[el.parent1]).jetpIndex;
                final int jetJ = history.get(gs2self[el.parent2]).jetpIndex;
                doIJRecombinationStep(jetI, jetJ, el.dij, el.dijL);
            } else {
                gs2self[igs] = history.size();
                doIBRecombinationStep(history.get(gs2self[el.parent1]).jetpIndex, el.dij, el.dijL);
            }
        } while (++igs < gsHistory.size());
    }

    /** Takes the areas of one ghosted clustering onto our history. */
    protected final void transferAreas(int[] uniqueHistOrder, ClusterSequenceActiveAreaExplicitGhosts ghosted) {
        final List<HistoryElement> gsHistory = ghosted.history();
        final int[] gsUniqueHistOrder = ghosted.uniqueHistoryOrder();
        final double tolerance = 1e-11;
        int j = -1;
        int histIndex = -1;
        final int histN = history.size();
        final double[] ourAreas = new double[histN];
        final PseudoJet[] our4 = new PseudoJet[histN];
        for (int i = 0; i < histN; i++) {
            our4[i] = new PseudoJet(0.0, 0.0, 0.0, 0.0, precision());
        }
        for (int i = 0; i < gsHistory.size(); i++) {
            final int gsHistIndex = gsUniqueHistOrder[i];
            if (gsHistIndex < ghosted.nParticles()) continue;
            final HistoryElement gsHist = gsHistory.get(gsHistIndex);
            final int parent1 = gsHist.parent1;
            final int parent2 = gsHist.parent2;
            if (parent2 == BEAM_JET) {
                final PseudoJet jet = ghosted.jet(gsHistory.get(parent1).jetpIndex);
                final double areaLocal = ghosted.area(jet);
                final PseudoJet extArea = ghosted.area4vector(jet);
                if (ghosted.isPureGhost(parent1)) {
                    ghostJets.add(new GhostJet(jet, areaLocal));
                    if (Math.abs(jet.rap()) < safeRapForArea) {
                        nonJetArea += areaLocal;
                        nonJetArea2 += areaLocal * areaLocal;
                        nonJetNumber += 1;
                    }
                } else {
                    while (++j < histN) {
                        histIndex = uniqueHistOrder[j];
                        if (histIndex >= initialN) break;
                    }
                    if (j >= histN) {
                        throw new FastJetException("ClusterSequenceActiveArea: overran reference array in diB matching");
                    }
                    final int refjetIndex = history.get(history.get(histIndex).parent1).jetpIndex;
                    final PseudoJet refjet = jets.get(refjetIndex);
                    throwUnlessSamePerpOrE(jet, refjet, tolerance, ghosted);
                    ourAreas[histIndex] = areaLocal;
                    our4[histIndex] = extArea;
                    ourAreas[history.get(histIndex).parent1] = areaLocal;
                    our4[history.get(histIndex).parent1] = extArea;
                }
            } else if (!ghosted.isPureGhost(parent1) && !ghosted.isPureGhost(parent2)) {
                while (++j < histN) {
                    histIndex = uniqueHistOrder[j];
                    if (histIndex >= initialN) break;
                }
                if (j >= histN) {
                    throw new FastJetException("ClusterSequenceActiveArea: overran reference array in dij matching");
                }
                if (history.get(histIndex).parent2 == BEAM_JET) {
                    throw new FastJetException("ClusterSequenceActiveArea: could not match clustering sequences (encountered dij matched with diB)");
                }
                final PseudoJet jet = ghosted.jet(gsHist.jetpIndex);
                final PseudoJet refjet = jets.get(history.get(histIndex).jetpIndex);
                throwUnlessSamePerpOrE(jet, refjet, tolerance, ghosted);
                final double areaLocal = ghosted.area(jet);
                ourAreas[histIndex] += areaLocal;
                final PseudoJet extArea = ghosted.area4vector(jet);
                jetDef.recombiner().plusEqual(our4[histIndex], extArea);
                final PseudoJet jet1 = ghosted.jet(gsHistory.get(parent1).jetpIndex);
                final int ourParent1 = history.get(histIndex).parent1;
                ourAreas[ourParent1] = ghosted.area(jet1);
                our4[ourParent1] = ghosted.area4vector(jet1);
                final PseudoJet jet2 = ghosted.jet(gsHistory.get(parent2).jetpIndex);
                final int ourParent2 = history.get(histIndex).parent2;
                ourAreas[ourParent2] = ghosted.area(jet2);
                our4[ourParent2] = ghosted.area4vector(jet2);
            }
        }
        for (PseudoJet u : ghosted.unclusteredParticles()) {
            if (ghosted.isPureGhost(u)) {
                unclusteredGhosts.add(new GhostJet(u, ghosted.area(u)));
            }
        }
        for (int a = 0; a < ourAreas.length; a++) {
            averageArea[a] += ourAreas[a];
            averageArea2[a] += ourAreas[a] * ourAreas[a];
        }
        for (int i = 0; i < our4.length; i++) {
            jetDef.recombiner().plusEqual(averageArea4vector[i], our4[i]);
        }
    }

    private void throwUnlessSamePerpOrE(PseudoJet jet, PseudoJet refjet, double tolerance,
                                        ClusterSequenceActiveAreaExplicitGhosts ghosted) {
        if (Math.abs(jet.perp2() - refjet.perp2()) > tolerance * Math.max(jet.perp2(), refjet.perp2())
                && Math.abs(jet.E() - refjet.E()) > tolerance * Math.max(jet.E(), refjet.E())) {
            final StringBuilder o = new StringBuilder();
            o.append("Could not match clustering sequence for an inclusive/exclusive jet when reconstructing areas. See FAQ for possible explanations.\n");
            o.append("  Ref-Jet: ").append(refjet.px()).append(' ').append(refjet.py()).append(' ')
             .append(refjet.pz()).append(' ').append(refjet.E()).append('\n');
            o.append("  New-Jet: ").append(jet.px()).append(' ').append(jet.py()).append(' ')
             .append(jet.pz()).append(' ').append(jet.E()).append('\n');
            if (ghosted.hasDangerousParticles()) {
                o.append("  NB: some particles have pt too low wrt ghosts -- this may be the cause\n");
            }
            throw new FastJetException(o.toString());
        }
    }

    @Override
    public double area(PseudoJet jet) {
        return averageArea[jet.clusterHistIndex()];
    }

    @Override
    public double areaError(PseudoJet jet) {
        return averageArea2[jet.clusterHistIndex()];
    }

    @Override
    public PseudoJet area4vector(PseudoJet jet) {
        return averageArea4vector[jet.clusterHistIndex()].copy();
    }

    public boolean hasDangerousParticles() {
        return hasDangerousParticles;
    }

    /** The background estimate of FastJet 2, kept for comparison. */
    public double ptPerUnitArea(MeanPtStrategy strat, double range) {
        final List<PseudoJet> incl = inclusiveJets();
        final List<Double> ptOverAreas = new ArrayList<>();
        for (PseudoJet j : incl) {
            if (Math.abs(j.rap()) < safeRapForArea) {
                final double thisArea = strat == MeanPtStrategy.MEDIAN_4VECTOR ? area4vector(j).perp() : area(j);
                ptOverAreas.add(j.perp() / thisArea);
            }
        }
        if (ptOverAreas.isEmpty()) {
            return 0.0;
        }
        final double[] s = ptOverAreas.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        final double nonGhostMedianRatio = s[s.length / 2];
        final double njMedianPos = (s.length - 1 - nonJetNumber) / 2.0;
        double njMedianRatio;
        if (njMedianPos >= 0 && s.length > 1) {
            final int i = (int) njMedianPos;
            njMedianRatio = s[i] * (i + 1 - njMedianPos) + s[i + 1] * (njMedianPos - i);
        } else {
            njMedianRatio = 0.0;
        }
        double ptSum = 0, ptSumCut = 0;
        double areaSum = nonJetArea, areaSumCut = nonJetArea;
        double ratioSum = 0;
        double ratioN = nonJetNumber;
        for (PseudoJet j : incl) {
            if (Math.abs(j.rap()) < safeRapForArea) {
                final double thisArea = strat == MeanPtStrategy.MEDIAN_4VECTOR ? area4vector(j).perp() : area(j);
                ptSum += j.perp();
                areaSum += thisArea;
                final double ratio = j.perp() / thisArea;
                if (ratio < range * njMedianRatio) {
                    ptSumCut += j.perp();
                    areaSumCut += thisArea;
                    ratioSum += ratio;
                    ratioN++;
                }
            }
        }
        return switch (strat) {
            case MEDIAN, MEDIAN_4VECTOR, PLAY -> njMedianRatio;
            case NON_GHOST_MEDIAN -> nonGhostMedianRatio;
            case PTTOT_OVER_AREATOT -> ptSum / areaSum;
            case PTTOT_OVER_AREATOT_CUT -> ptSumCut / areaSumCut;
            case MEAN_RATIO_CUT -> ratioSum / ratioN;
        };
    }

    @Override
    public double emptyArea(Selector selector) {
        if (!selector.appliesJetByJet()) {
            throw new FastJetException("ClusterSequenceActiveArea: empty area can only be computed from selectors applying jet by jet");
        }
        double empty = 0.0;
        for (GhostJet g : ghostJets) {
            if (selector.pass(g.jet())) empty += g.area();
        }
        for (GhostJet g : unclusteredGhosts) {
            if (selector.pass(g.jet())) empty += g.area();
        }
        return empty / ghostSpecRepeat;
    }

    @Override
    public double nEmptyJets(Selector selector) {
        checkSelectorGoodForMedian(selector);
        double inrange = 0;
        for (GhostJet g : ghostJets) {
            if (selector.pass(g.jet())) inrange++;
        }
        return inrange / ghostSpecRepeat;
    }
}
