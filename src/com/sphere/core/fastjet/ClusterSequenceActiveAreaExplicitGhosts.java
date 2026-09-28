package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * Active areas with the ghosts kept in the clustering,
 * fastjet::ClusterSequenceActiveAreaExplicitGhosts. A jet's area is the
 * number of ghosts it holds times the area of a ghost.
 */
public class ClusterSequenceActiveAreaExplicitGhosts extends ClusterSequenceAreaBase {

    private static final LimitedWarning WARNINGS = new LimitedWarning();

    private int nGhosts;
    private double ghostArea;
    private boolean[] isPureGhost;
    private final ArrayList<Boolean> isPureGhostInit = new ArrayList<>();
    private double[] areas;
    private PseudoJet[] area4vectors;
    private double maxGhostPerp2;
    private boolean hasDangerousParticles;
    private int initialHardN;

    /** The particles and ghosts laid out by the specification. */
    public ClusterSequenceActiveAreaExplicitGhosts(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                                   GhostedAreaSpec ghostSpec) {
        this(particles, jetDef, ghostSpec, false);
    }

    public ClusterSequenceActiveAreaExplicitGhosts(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                                   GhostedAreaSpec ghostSpec, boolean writeout) {
        initialise(particles, jetDef, ghostSpec, null, 0.0, writeout);
    }

    /** The particles and ghosts given explicitly, each of area ghostArea. */
    public ClusterSequenceActiveAreaExplicitGhosts(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                                   List<? extends PseudoJet> ghosts, double ghostArea) {
        this(particles, jetDef, ghosts, ghostArea, false);
    }

    public ClusterSequenceActiveAreaExplicitGhosts(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                                   List<? extends PseudoJet> ghosts, double ghostArea,
                                                   boolean writeout) {
        initialise(particles, jetDef, null, ghosts, ghostArea, writeout);
    }

    private void initialise(List<? extends PseudoJet> particles, JetDefinition jetDefIn, GhostedAreaSpec ghostSpec,
                            List<? extends PseudoJet> ghosts, double ghostAreaIn, boolean writeout) {
        final Precision p = jetDefIn.precision();
        for (PseudoJet particle : particles) {
            final PseudoJet mom = particle.copy();
            mom.setPrecision(p);
            jets.add(mom);
            isPureGhostInit.add(false);
        }
        initialHardN = jets.size();
        final List<PseudoJet> added = new ArrayList<>();
        if (ghostSpec != null) {
            ghostSpec.addGhosts(added);
            ghostArea = ghostSpec.actualGhostArea();
            nGhosts = ghostSpec.nGhosts();
        } else {
            added.addAll(ghosts);
            ghostArea = ghostAreaIn;
            nGhosts = ghosts.size();
        }
        for (PseudoJet g : added) {
            final PseudoJet c = g.copy();
            c.setPrecision(p);
            jets.add(c);
            isPureGhostInit.add(true);
        }
        if (writeout) {
            System.out.println("# Printing particles including ghosts");
            for (int j = 0; j < jets.size(); j++) {
                System.out.printf("%5d %20.13f %20.13f %20.13e%n", j, jets.get(j).rap(), jets.get(j).phi(), jets.get(j).kt2());
            }
            System.out.println("# Finished printing particles including ghosts");
        }
        initialiseAndRun(jetDefIn, writeout);
        postProcess();
    }

    private void postProcess() {
        final int histN = history.size();
        isPureGhost = new boolean[histN];
        for (int i = 0; i < initialN; i++) {
            isPureGhost[i] = isPureGhostInit.get(i);
        }
        maxGhostPerp2 = 0.0;
        for (int i = 0; i < initialN; i++) {
            if (isPureGhost[i] && jets.get(i).perp2() > maxGhostPerp2) {
                maxGhostPerp2 = jets.get(i).perp2();
            }
        }
        double dangerRatio = Math.ulp(1.0);
        dangerRatio = dangerRatio * dangerRatio;
        hasDangerousParticles = false;
        for (int i = 0; i < initialN; i++) {
            if (!isPureGhost[i] && dangerRatio * jets.get(i).perp2() <= maxGhostPerp2) {
                hasDangerousParticles = true;
                break;
            }
        }
        if (hasDangerousParticles) {
            WARNINGS.warn("ClusterSequenceActiveAreaExplicitGhosts: \n  ghosts not sufficiently soft wrt some of the input particles\n  a common cause is (unphysical?) input particles with pt=0 but finite rapidity");
        }
        areas = new double[histN];
        area4vectors = new PseudoJet[histN];
        for (int i = 0; i < initialN; i++) {
            if (isPureGhost[i]) {
                areas[i] = ghostArea;
                final PseudoJet a = new PseudoJet(0, 0, 0, 0, precision());
                a.resetMomentum(jets.get(i));
                a.timesEqual(ghostArea / jets.get(i).perp());
                area4vectors[i] = a;
            } else {
                areas[i] = 0;
                area4vectors[i] = new PseudoJet(0.0, 0.0, 0.0, 0.0, precision());
            }
        }
        for (int i = initialN; i < histN; i++) {
            final HistoryElement h = history.get(i);
            if (h.parent2 == BEAM_JET) {
                isPureGhost[i] = isPureGhost[h.parent1];
                areas[i] = areas[h.parent1];
                area4vectors[i] = area4vectors[h.parent1];
            } else {
                isPureGhost[i] = isPureGhost[h.parent1] && isPureGhost[h.parent2];
                areas[i] = areas[h.parent1] + areas[h.parent2];
                final PseudoJet r = new PseudoJet(0, 0, 0, 0, precision());
                jetDef.recombiner().recombine(area4vectors[h.parent1], area4vectors[h.parent2], r);
                area4vectors[i] = r;
            }
        }
    }

    public int nHardParticles() {
        return initialHardN;
    }

    @Override
    public double area(PseudoJet jet) {
        return areas[jet.clusterHistIndex()];
    }

    @Override
    public PseudoJet area4vector(PseudoJet jet) {
        return area4vectors[jet.clusterHistIndex()].copy();
    }

    @Override
    public boolean isPureGhost(PseudoJet jet) {
        return isPureGhost[jet.clusterHistIndex()];
    }

    public boolean isPureGhost(int historyIndex) {
        return historyIndex >= 0 && isPureGhost[historyIndex];
    }

    @Override
    public boolean hasExplicitGhosts() {
        return true;
    }

    @Override
    public double emptyArea(Selector selector) {
        if (!selector.appliesJetByJet()) {
            throw new FastJetException("ClusterSequenceActiveAreaExplicitGhosts: empty area can only be computed from selectors applying jet by jet");
        }
        double a = 0.0;
        for (PseudoJet u : unclusteredParticles()) {
            if (isPureGhost(u) && selector.pass(u)) {
                a += ghostArea;
            }
        }
        return a;
    }

    public double totalArea() {
        return nGhosts * ghostArea;
    }

    public double maxGhostPerp2() {
        return maxGhostPerp2;
    }

    public boolean hasDangerousParticles() {
        return hasDangerousParticles;
    }

    public double ghostArea() {
        return ghostArea;
    }
}
