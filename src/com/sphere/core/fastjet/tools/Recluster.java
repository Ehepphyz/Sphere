package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceActiveAreaExplicitGhosts;
import com.sphere.core.fastjet.CompositeJetStructure;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;

import java.util.ArrayList;
import java.util.List;

/**
 * Reclusters the constituents of a jet with another definition, fastjet::Recluster,
 * keeping the hardest new jet or joining them all. When the jet came from
 * Cambridge/Aachen and the new definition is C/A with a smaller R, the
 * subjets are read off the existing history rather than reclustered.
 */
public class Recluster implements FunctionOfPseudoJet<PseudoJet> {

    public enum Keep { KEEP_ONLY_HARDEST, KEEP_ALL }

    private static final LimitedWarning EXPLICIT_GHOST_WARNING = new LimitedWarning();

    private JetDefinition newJetDef;
    private boolean acquireRecombiner;
    private Keep keep;
    private boolean cambridgeOptimisationEnabled = true;

    public Recluster() {
        this.newJetDef = new JetDefinition();
        this.acquireRecombiner = true;
        this.keep = Keep.KEEP_ONLY_HARDEST;
    }

    public Recluster(JetDefinition newJetDef, boolean acquireRecombiner, Keep keep) {
        this.newJetDef = newJetDef;
        this.acquireRecombiner = acquireRecombiner;
        this.keep = keep;
    }

    public Recluster(JetDefinition newJetDef) {
        this(newJetDef, false, Keep.KEEP_ONLY_HARDEST);
    }

    public Recluster(JetAlgorithm alg, double radius, Keep keep) {
        this(new JetDefinition(alg, radius), true, keep);
    }

    public Recluster(JetAlgorithm alg, Keep keep) {
        this.acquireRecombiner = true;
        this.keep = keep;
        switch (alg.nParameters()) {
            case 0 -> newJetDef = new JetDefinition(alg);
            case 1 -> newJetDef = new JetDefinition(alg, JetDefinition.MAX_ALLOWABLE_R);
            default -> throw new FastJetException("Recluster(): tried to construct specifying only a jet algorithm ("
                + alg.description() + ") which takes more than 1 parameter");
        }
    }

    public void setAcquireRecombiner(boolean acquire) { acquireRecombiner = acquire; }
    public boolean acquireRecombiner() { return acquireRecombiner; }
    public void setCambridgeOptimisation(boolean enabled) { cambridgeOptimisationEnabled = enabled; }
    public boolean cambridgeOptimisation() { return cambridgeOptimisationEnabled; }
    public void setKeep(Keep k) { keep = k; }
    public Keep keep() { return keep; }

    @Override
    public String description() {
        final StringBuilder o = new StringBuilder("Recluster with new_jet_def = ");
        if (acquireRecombiner) {
            o.append(newJetDef.descriptionNoRecombiner()).append(", using a recombiner obtained from the jet being reclustered");
        } else {
            o.append(newJetDef.description());
        }
        o.append(keep == Keep.KEEP_ONLY_HARDEST ? " and keeping the hardest inclusive jet"
                                                : " and joining all inclusive jets into a composite jet");
        return o.toString();
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        final List<PseudoJet> incljets = new ArrayList<>();
        final boolean caOptimised = getNewJetsAndDef(jet, incljets);
        return generateOutputJet(incljets, caOptimised);
    }

    /** Fills the new jets, sorted by pt; returns true if the C/A shortcut was used. */
    public boolean getNewJetsAndDef(PseudoJet inputJet, List<PseudoJet> outputJets) {
        if (!inputJet.hasConstituents()) {
            throw new FastJetException("Recluster can only be applied on jets having constituents");
        }
        final List<PseudoJet> allPieces = new ArrayList<>();
        if (!getAllPieces(inputJet, allPieces) || allPieces.isEmpty()) {
            throw new FastJetException("Recluster: failed to retrieve all the pieces composing the jet.");
        }
        final JetDefinition def = new JetDefinition(newJetDef);
        if (acquireRecombiner) {
            acquireRecombinerFromPieces(allPieces, def);
        }
        outputJets.clear();
        if (checkCa(allPieces, def)) {
            reclusterCa(allPieces, outputJets, def.R());
            final List<PseudoJet> s = PseudoJet.sortedByPt(outputJets);
            outputJets.clear();
            outputJets.addAll(s);
            return true;
        }
        boolean includeArea = inputJet.hasArea();
        if (includeArea && !checkExplicitGhosts(allPieces)) {
            EXPLICIT_GHOST_WARNING.warn("Recluster: the original cluster sequence is lacking explicit ghosts; area support will no longer be available after re-clustering");
            includeArea = false;
        }
        reclusterGeneric(inputJet, outputJets, def, includeArea);
        final List<PseudoJet> s = PseudoJet.sortedByPt(outputJets);
        outputJets.clear();
        outputJets.addAll(s);
        return false;
    }

    public PseudoJet generateOutputJet(List<PseudoJet> incljets, boolean caOptimisationUsed) {
        if (keep == Keep.KEEP_ONLY_HARDEST) {
            return incljets.isEmpty() ? new PseudoJet() : incljets.get(0);
        }
        if (incljets.isEmpty()) {
            return PseudoJet.join(incljets);
        }
        final PseudoJet reclustered = PseudoJet.join(incljets,
            incljets.get(0).associatedClusterSequence().jetDef().recombiner());
        if (caOptimisationUsed && reclustered.hasArea() && !incljets.get(0).validatedCsab().hasExplicitGhosts()) {
            ((CompositeJetStructure) reclustered.structure()).discardArea();
        }
        return reclustered;
    }

    private void reclusterCa(List<PseudoJet> allPieces, List<PseudoJet> subjets, double rfilt) {
        subjets.clear();
        for (PseudoJet piece : allPieces) {
            final ClusterSequence cs = piece.associatedClusterSequence();
            final double dcut = rfilt / cs.jetDef().R();
            if (dcut >= 1.0) {
                subjets.add(piece);
            } else {
                subjets.addAll(piece.exclusiveSubjets(dcut * dcut));
            }
        }
    }

    private void reclusterGeneric(PseudoJet jet, List<PseudoJet> incljets, JetDefinition def, boolean doAreas) {
        if (doAreas) {
            final List<PseudoJet> regular = new ArrayList<>();
            final List<PseudoJet> ghosts = new ArrayList<>();
            Selector.isPureGhost().sift(jet.constituents(), ghosts, regular);
            final double ghostArea = ghosts.isEmpty() ? 0.01 : ghosts.get(0).area();
            final ClusterSequenceActiveAreaExplicitGhosts csa =
                new ClusterSequenceActiveAreaExplicitGhosts(regular, def, ghosts, ghostArea);
            incljets.addAll(csa.inclusiveJets());
        } else {
            final ClusterSequence cs = new ClusterSequence(jet.constituents(), def);
            incljets.addAll(cs.inclusiveJets());
        }
    }

    private boolean getAllPieces(PseudoJet jet, List<PseudoJet> allPieces) {
        if (jet.hasAssociatedClusterSequence()) {
            allPieces.add(jet);
            return true;
        }
        if (jet.hasPieces()) {
            for (PseudoJet p : jet.pieces()) {
                if (!getAllPieces(p, allPieces)) return false;
            }
            return true;
        }
        return false;
    }

    private void acquireRecombinerFromPieces(List<PseudoJet> allPieces, JetDefinition def) {
        final JetDefinition ref = allPieces.get(0).validatedCs().jetDef();
        for (int i = 1; i < allPieces.size(); i++) {
            if (!allPieces.get(i).validatedCs().jetDef().hasSameRecombiner(ref)) {
                throw new FastJetException("Recluster instance is configured to determine the recombination scheme (or recombiner) from the original jet, but different pieces of the jet were found to have non-equivalent recombiners.");
            }
        }
        def.setRecombiner(ref);
    }

    private boolean checkExplicitGhosts(List<PseudoJet> allPieces) {
        for (PseudoJet p : allPieces) {
            if (!p.validatedCsab().hasExplicitGhosts()) return false;
        }
        return true;
    }

    private boolean checkCa(List<PseudoJet> allPieces, JetDefinition def) {
        if (!cambridgeOptimisationEnabled) return false;
        if (def.jetAlgorithm() != JetAlgorithm.CAMBRIDGE) return false;
        final ClusterSequence csRef = allPieces.get(0).validatedCs();
        if (csRef.jetDef().jetAlgorithm() != JetAlgorithm.CAMBRIDGE) return false;
        for (int i = 1; i < allPieces.size(); i++) {
            if (allPieces.get(i).validatedCs() != csRef) return false;
        }
        if (!csRef.jetDef().hasSameRecombiner(def)) return false;
        double rnew2 = def.R();
        rnew2 *= rnew2;
        for (int i = 0; i < allPieces.size() - 1; i++) {
            for (int j = i + 1; j < allPieces.size(); j++) {
                if (allPieces.get(i).squaredDistance(allPieces.get(j)) < rnew2) return false;
            }
        }
        return true;
    }
}
