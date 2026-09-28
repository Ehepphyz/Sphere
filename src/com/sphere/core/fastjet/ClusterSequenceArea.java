package com.sphere.core.fastjet;

import java.util.List;

/**
 * A clustering with areas of whichever kind an AreaDefinition names,
 * fastjet::ClusterSequenceArea. The work is done by the specialised sequence;
 * this one takes over its history, so its jets answer area questions
 * directly.
 */
public class ClusterSequenceArea extends ClusterSequenceAreaBase {

    private static final LimitedWarning RANGE_WARNINGS = new LimitedWarning();
    private static final LimitedWarning EXPLICIT_GHOSTS_REPEATS_WARNINGS = new LimitedWarning();

    private final AreaDefinition areaDef;
    private final ClusterSequenceAreaBase areaBase;

    public ClusterSequenceArea(List<? extends PseudoJet> particles, JetDefinition jetDef, AreaDefinition areaDef) {
        this.areaDef = areaDef;
        switch (areaDef.areaType()) {
            case ACTIVE -> areaBase = new ClusterSequenceActiveArea(particles, jetDef, areaDef.ghostSpec());
            case ACTIVE_EXPLICIT_GHOSTS -> {
                if (areaDef.ghostSpec().repeat() != 1) {
                    EXPLICIT_GHOSTS_REPEATS_WARNINGS.warn("Requested active area with explicit ghosts with repeat != 1; only 1 set of ghosts will be used");
                }
                areaBase = new ClusterSequenceActiveAreaExplicitGhosts(particles, jetDef, areaDef.ghostSpec());
            }
            case VORONOI -> areaBase = new ClusterSequenceVoronoiArea(particles, jetDef, areaDef.voronoiSpec());
            case ONE_GHOST_PASSIVE -> areaBase = new ClusterSequence1GhostPassiveArea(particles, jetDef, areaDef.ghostSpec());
            case PASSIVE -> areaBase = new ClusterSequencePassiveArea(particles, jetDef, areaDef.ghostSpec());
            default -> throw new FastJetException("Error: unrecognized area_type in ClusterSequenceArea:" + areaDef.areaType().id);
        }
        transferFromSequence(areaBase);
    }

    public ClusterSequenceArea(List<? extends PseudoJet> particles, JetDefinition jetDef, GhostedAreaSpec ghostSpec) {
        this(particles, jetDef, new AreaDefinition(ghostSpec));
    }

    public ClusterSequenceArea(List<? extends PseudoJet> particles, JetDefinition jetDef,
                               AreaDefinition.VoronoiAreaSpec voronoiSpec) {
        this(particles, jetDef, new AreaDefinition(voronoiSpec));
    }

    public AreaDefinition areaDef() {
        return areaDef;
    }

    /** The sequence that computed the areas. */
    public ClusterSequenceAreaBase areaBase() {
        return areaBase;
    }

    @Override
    public double area(PseudoJet jet) {
        return areaBase.area(jet);
    }

    @Override
    public double areaError(PseudoJet jet) {
        return areaBase.areaError(jet);
    }

    @Override
    public PseudoJet area4vector(PseudoJet jet) {
        return areaBase.area4vector(jet);
    }

    @Override
    public double emptyArea(Selector selector) {
        return areaBase.emptyArea(selector);
    }

    @Override
    public double nEmptyJets(Selector selector) {
        return areaBase.nEmptyJets(selector);
    }

    @Override
    public boolean isPureGhost(PseudoJet jet) {
        return areaBase.isPureGhost(jet);
    }

    @Override
    public boolean hasExplicitGhosts() {
        return areaBase.hasExplicitGhosts();
    }

    @Override
    public MedianRho medianRhoAndSigma(List<PseudoJet> allJets, Selector selector, boolean useArea4vector,
                                       boolean allAreInclusive) {
        warnIfRangeUnsuitable(selector);
        return super.medianRhoAndSigma(allJets, selector, useArea4vector, allAreInclusive);
    }

    @Override
    public double[] parabolicPtPerUnitArea(Selector selector, double excludeAbove, boolean useArea4vector) {
        warnIfRangeUnsuitable(selector);
        return super.parabolicPtPerUnitArea(selector, excludeAbove, useArea4vector);
    }

    private void warnIfRangeUnsuitable(Selector selector) {
        checkSelectorGoodForMedian(selector);
        final boolean noGhosts = areaDef.areaType() == AreaDefinition.AreaType.VORONOI
            || (areaDef.areaType() == AreaDefinition.AreaType.PASSIVE
                && jetDef().jetAlgorithm() == JetAlgorithm.KT);
        if (!noGhosts) {
            final double[] r = selector.rapidityExtent();
            final double maxrap = areaDef.ghostSpec().ghostMaxrap();
            if (r[0] < -maxrap + 0.95 * jetDef().R() || r[1] > maxrap - 0.95 * jetDef().R()) {
                RANGE_WARNINGS.warn("rapidity range for median (rho) extends beyond +-(ghost_maxrap - 0.95*R); this is likely to cause the results to be unreliable; safest option is to increase ghost_maxrap in the area definition");
            }
        }
    }
}
