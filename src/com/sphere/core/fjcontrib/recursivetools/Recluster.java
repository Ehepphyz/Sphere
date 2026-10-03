package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceActiveAreaExplicitGhosts;
import com.sphere.core.fastjet.DefaultRecombiner;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fastjet.tools.Transformer;

import java.util.ArrayList;
import java.util.List;

/**
 * The reclustering tool RecursiveTools shipped before FastJet had its own,
 * fastjet::contrib::Recluster. Kept, as the contrib keeps it, for
 * compatibility: {@link com.sphere.core.fastjet.tools.Recluster} is the
 * one to use.
 *
 * A jet from C/A reclustered with C/A reads its subjets off the existing
 * history; otherwise the constituents are clustered again (with the ghosts
 * kept apart when the jet has explicit ghosts, so the subjets have areas).
 */
@Deprecated
public class Recluster implements Transformer {

    private static final LimitedWarning EXPLICIT_GHOST_WARNING = new LimitedWarning();

    private final JetDefinition subjetDef;
    private final JetAlgorithm subjetAlg;
    private final boolean useFullDef;
    private final double subjetRadius;
    private final boolean hasSubjetRadius;
    private final double subjetExtra;
    private final boolean hasSubjetExtra;
    private final boolean single;

    /** With a full definition; single keeps only the hardest subjet. */
    public Recluster(JetDefinition subjetDef, boolean single) {
        this.subjetDef = subjetDef;
        this.subjetAlg = subjetDef.jetAlgorithm();
        this.useFullDef = true;
        this.subjetRadius = 0;
        this.hasSubjetRadius = false;
        this.subjetExtra = 0;
        this.hasSubjetExtra = false;
        this.single = single;
    }

    public Recluster(JetDefinition subjetDef) {
        this(subjetDef, true);
    }

    /** An algorithm with R and extra parameter, the recombiner taken from the jet. */
    public Recluster(JetAlgorithm subjetAlg, double subjetRadius, double subjetExtra, boolean single) {
        this.subjetDef = null;
        this.subjetAlg = subjetAlg;
        this.useFullDef = false;
        this.subjetRadius = subjetRadius;
        this.hasSubjetRadius = true;
        this.subjetExtra = subjetExtra;
        this.hasSubjetExtra = true;
        this.single = single;
    }

    public Recluster(JetAlgorithm subjetAlg, double subjetRadius, boolean single) {
        this.subjetDef = null;
        this.subjetAlg = subjetAlg;
        this.useFullDef = false;
        this.subjetRadius = subjetRadius;
        this.hasSubjetRadius = true;
        this.subjetExtra = 0;
        this.hasSubjetExtra = false;
        this.single = single;
    }

    public Recluster(JetAlgorithm subjetAlg, double subjetRadius) {
        this(subjetAlg, subjetRadius, true);
    }

    public Recluster(JetAlgorithm subjetAlg, boolean single) {
        this.subjetDef = null;
        this.subjetAlg = subjetAlg;
        this.useFullDef = false;
        this.subjetRadius = 0;
        this.hasSubjetRadius = false;
        this.subjetExtra = 0;
        this.hasSubjetExtra = false;
        this.single = single;
    }

    @Override
    public String description() {
        final StringBuilder o = new StringBuilder("Recluster with subjet_def = ");
        if (useFullDef) {
            o.append(subjetDef.description());
        } else {
            final String r = Fmt.g(subjetRadius);
            switch (subjetAlg) {
                case KT -> o.append("Longitudinally invariant kt algorithm with R = ").append(r);
                case CAMBRIDGE -> o.append("Longitudinally invariant Cambridge/Aachen algorithm with R = ").append(r);
                case ANTIKT -> o.append("Longitudinally invariant anti-kt algorithm with R = ").append(r);
                case GENKT -> o.append("Longitudinally invariant generalised kt algorithm with R = ").append(r)
                    .append(", p = ").append(Fmt.g(subjetExtra));
                case CAMBRIDGE_FOR_PASSIVE -> o.append("Longitudinally invariant Cambridge/Aachen algorithm with R = ")
                    .append(r).append(" and a special hack whereby particles with kt < ").append(Fmt.g(subjetExtra))
                    .append("are treated as passive ghosts");
                case EE_KT -> o.append("e+e- kt (Durham) algorithm");
                case EE_GENKT -> o.append("e+e- generalised kt algorithm with R = ").append(r)
                    .append(", p = ").append(Fmt.g(subjetExtra));
                case UNDEFINED -> o.append("uninitialised JetDefinition (jet_algorithm=undefined_jet_algorithm)");
                default -> o.append("unrecognized jet_algorithm");
            }
            o.append(", a recombiner obtained from the jet being reclustered");
        }
        o.append(single ? " and keeping the hardest subjet" : " and joining all subjets in a composite jet");
        return o.toString();
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasConstituents()) {
            throw new FastJetException("Filter can only be applied on jets having constituents");
        }
        final List<PseudoJet> allPieces = new ArrayList<>();
        if (!getAllPieces(jet, allPieces) || allPieces.isEmpty()) {
            throw new FastJetException("Recluster: failed to retrieve all the pieces composing the jet.");
        }
        final JetDefinition def = useFullDef ? subjetDef : buildJetDefWithRecombiner(allPieces);
        List<PseudoJet> subjets = new ArrayList<>();
        if (checkCa(allPieces, def)) {
            reclusterCafilt(allPieces, subjets, def.R());
            subjets = PseudoJet.sortedByPt(subjets);
            return single ? subjets.get(0) : PseudoJet.join(subjets, def.recombiner());
        }
        boolean includeArea = jet.hasArea();
        if (includeArea && !checkExplicitGhosts(allPieces)) {
            EXPLICIT_GHOST_WARNING.warn("Recluster: the original cluster sequence is lacking explicit ghosts; area support will no longer be available after re-clustering");
            includeArea = false;
        }
        reclusterGeneric(jet, subjets, def, includeArea);
        subjets = PseudoJet.sortedByPt(subjets);
        return single ? subjets.get(0) : PseudoJet.join(subjets, def.recombiner());
    }

    private void reclusterCafilt(List<PseudoJet> allPieces, List<PseudoJet> subjets, double rfilt) {
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

    private void reclusterGeneric(PseudoJet jet, List<PseudoJet> subjets, JetDefinition def, boolean doAreas) {
        if (doAreas) {
            final List<PseudoJet> regular = new ArrayList<>();
            final List<PseudoJet> ghosts = new ArrayList<>();
            for (PseudoJet c : jet.constituents()) {
                if (c.isPureGhost()) ghosts.add(c); else regular.add(c);
            }
            final double ghostArea = ghosts.isEmpty() ? 0.01 : ghosts.get(0).area();
            subjets.addAll(new ClusterSequenceActiveAreaExplicitGhosts(regular, def, ghosts, ghostArea).inclusiveJets());
        } else {
            subjets.addAll(new ClusterSequence(jet.constituents(), def).inclusiveJets());
        }
    }

    private boolean getAllPieces(PseudoJet jet, List<PseudoJet> allPieces) {
        if (jet.hasAssociatedClusterSequence()) {
            allPieces.add(jet);
            return true;
        }
        if (jet.hasPieces()) {
            for (PseudoJet p : jet.pieces()) if (!getAllPieces(p, allPieces)) return false;
            return true;
        }
        return false;
    }

    private JetDefinition buildJetDefWithRecombiner(List<PseudoJet> allPieces) {
        final JetDefinition ref = allPieces.get(0).validatedCs().jetDef();
        for (int i = 1; i < allPieces.size(); i++) {
            if (!allPieces.get(i).validatedCs().jetDef().hasSameRecombiner(ref)) {
                throw new FastJetException("Recluster: requested to guess the recombination scheme (or recombiner) from the original jet but an inconsistency was found between the pieces constituing that jet.");
            }
        }
        final Recombiner common = ref.recombiner();
        final JetDefinition def;
        if (common.getClass() == DefaultRecombiner.class) {
            final RecombinationScheme scheme = common.scheme();
            if (hasSubjetExtra) def = new JetDefinition(subjetAlg, subjetRadius, subjetExtra, scheme);
            else if (hasSubjetRadius) def = new JetDefinition(subjetAlg, subjetRadius, scheme);
            else def = new JetDefinition(subjetAlg, scheme, Strategy.BEST);
        } else {
            if (hasSubjetExtra) def = new JetDefinition(subjetAlg, subjetRadius, subjetExtra, common, Strategy.BEST);
            else if (hasSubjetRadius) def = new JetDefinition(subjetAlg, subjetRadius, common);
            else {
                def = new JetDefinition(subjetAlg, RecombinationScheme.E_SCHEME, Strategy.BEST);
                def.setRecombiner(common);
            }
        }
        return def;
    }

    private boolean checkExplicitGhosts(List<PseudoJet> allPieces) {
        for (PseudoJet p : allPieces) if (!p.validatedCsab().hasExplicitGhosts()) return false;
        return true;
    }

    private boolean checkCa(List<PseudoJet> allPieces, JetDefinition def) {
        if (def.jetAlgorithm() != JetAlgorithm.CAMBRIDGE) return false;
        final ClusterSequence csRef = allPieces.get(0).validatedCs();
        if (csRef.jetDef().jetAlgorithm() != JetAlgorithm.CAMBRIDGE) return false;
        for (int i = 1; i < allPieces.size(); i++) if (allPieces.get(i).validatedCs() != csRef) return false;
        if (!csRef.jetDef().hasSameRecombiner(def)) return false;
        double rsub2 = def.R();
        rsub2 *= rsub2;
        for (int i = 0; i < allPieces.size() - 1; i++) {
            for (int j = i + 1; j < allPieces.size(); j++) {
                if (allPieces.get(i).squaredDistance(allPieces.get(j)) < rsub2) return false;
            }
        }
        return true;
    }
}
