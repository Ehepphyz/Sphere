package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceActiveAreaExplicitGhosts;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.WrappedStructure;
import com.sphere.core.fastjet.tools.Transformer;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bottom-up soft drop, fastjet::contrib::BottomUpSoftDrop (F.A. Dreyer,
 * L. Necib, G. Soyez and J. Thaler, JHEP 06 (2018) 093): the soft-drop
 * condition applied during the clustering itself, by a recombiner that keeps
 * only the harder of two objects failing it. It grooms a jet, or a whole
 * event at once ({@link #globalGrooming}).
 */
public class BottomUpSoftDrop implements Transformer {

    static {
        ContribCitations.use("rsd");
    }

    private final JetDefinition jetDef;
    private final double beta;
    private final double symmetryCut;
    private final double r0;
    private final boolean getRecombinerFromJet;

    /** With C/A (R = max) and the recombiner of the jet groomed. */
    public BottomUpSoftDrop(double beta, double symmetryCut) {
        this(beta, symmetryCut, 1.0);
    }

    public BottomUpSoftDrop(double beta, double symmetryCut, double r0) {
        this(JetAlgorithm.CAMBRIDGE, beta, symmetryCut, r0);
    }

    public BottomUpSoftDrop(JetAlgorithm jetAlg, double beta, double symmetryCut, double r0) {
        this.jetDef = new JetDefinition(jetAlg, JetDefinition.MAX_ALLOWABLE_R);
        this.beta = beta;
        this.symmetryCut = symmetryCut;
        this.r0 = r0;
        this.getRecombinerFromJet = true;
    }

    public BottomUpSoftDrop(JetDefinition jetDef, double beta, double symmetryCut, double r0) {
        this.jetDef = jetDef;
        this.beta = beta;
        this.symmetryCut = symmetryCut;
        this.r0 = r0;
        this.getRecombinerFromJet = false;
    }

    public BottomUpSoftDrop(JetDefinition jetDef, double beta, double symmetryCut) {
        this(jetDef, beta, symmetryCut, 1.0);
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasConstituents()) {
            throw new FastJetException("BottomUpSoftDrop: trying to apply the Soft Drop transformer to a jet that has no constituents");
        }
        final boolean doAreas = jet.hasArea() && checkExplicitGhosts(jet);
        final Plugin plugin;
        if (getRecombinerFromJet) {
            final JetDefinition def = new JetDefinition(jetDef);
            final JetDefinition[] forRecombiner = new JetDefinition[1];
            if (checkCommonRecombiner(jet, forRecombiner)) def.setRecombiner(forRecombiner[0]);
            plugin = new Plugin(def, beta, symmetryCut, r0);
        } else {
            plugin = new Plugin(jetDef, beta, symmetryCut, r0);
        }
        final JetDefinition internal = new JetDefinition(plugin);
        internal.setPrecision(jetDef.precision());
        final ClusterSequence cs;
        if (doAreas) {
            final List<PseudoJet> particles = new ArrayList<>();
            final List<PseudoJet> ghosts = new ArrayList<>();
            Selector.isPureGhost().sift(jet.constituents(), ghosts, particles);
            final double ghostArea = ghosts.isEmpty() ? 0.01 : ghosts.get(0).area();
            cs = new ClusterSequenceActiveAreaExplicitGhosts(particles, internal, ghosts, ghostArea);
        } else {
            cs = new ClusterSequence(jet.constituents(), internal);
        }
        final PseudoJet resultLocal = Selector.nHardest(1).apply(cs.inclusiveJets()).get(0).copy();
        final Structure s = new Structure(resultLocal);
        s.beta = beta;
        s.symmetryCut = symmetryCut;
        s.r0 = r0;
        resultLocal.setStructure(s);
        return resultLocal;
    }

    /** The whole event groomed at once (no area support): the constituents that survive. */
    public List<PseudoJet> globalGrooming(List<PseudoJet> event) {
        final ClusterSequence cs = new ClusterSequence(event, jetDef);
        final List<PseudoJet> globalJet = Selector.nHardest(1).apply(cs.inclusiveJets());
        if (globalJet.isEmpty()) return new ArrayList<>();
        return result(globalJet.get(0)).constituents();
    }

    private boolean checkExplicitGhosts(PseudoJet jet) {
        if (jet.hasAssociatedClusterSequence()) return jet.validatedCsab().hasExplicitGhosts();
        if (jet.hasPieces()) {
            for (PseudoJet p : jet.pieces()) if (!checkExplicitGhosts(p)) return false;
            return true;
        }
        return false;
    }

    /** Whether all pieces share a recombiner; if so, a definition holding it is left in out[0]. */
    private boolean checkCommonRecombiner(PseudoJet jet, JetDefinition[] out) {
        if (jet.hasAssociatedClusterSequence()) {
            // As in the C++, where the "assigned" flag is passed by value:
            // each piece assigns its own definition and the last one wins.
            out[0] = jet.validatedCs().jetDef();
            return true;
        }
        if (jet.hasPieces()) {
            final List<PseudoJet> pieces = jet.pieces();
            if (pieces.isEmpty()) return false;
            for (PseudoJet p : pieces) if (!checkCommonRecombiner(p, out)) return false;
            return true;
        }
        return false;
    }

    @Override
    public String description() {
        return "BottomUpSoftDrop with jet_definition = (" + jetDef.description() + "), symmetry_cut = "
            + Fmt.g(symmetryCut) + ", beta = " + Fmt.g(beta) + ", R0 = " + Fmt.g(r0);
    }

    /** The structure of the groomed jet, BottomUpSoftDropStructure. */
    public static final class Structure extends WrappedStructure {
        double beta;
        double symmetryCut;
        double r0;

        Structure(PseudoJet resultJet) {
            super(resultJet.structure());
        }

        @Override
        public String description() {
            return "Bottom/Up Soft Dropped PseudoJet";
        }

        /** What the grooming rejected. */
        public List<PseudoJet> rejected() {
            return validatedCs().childlessPseudojets();
        }

        /** The other jets the internal clustering found. */
        public List<PseudoJet> extraJets() {
            return PseudoJet.sortedByPt(Selector.not(Selector.nHardest(1)).apply(validatedCs().inclusiveJets()));
        }

        public double beta() { return beta; }
        public double symmetryCut() { return symmetryCut; }
        public double R0() { return r0; }
    }

    /** The recombiner that applies the condition, BottomUpSoftDropRecombiner. */
    public static final class SoftDropRecombiner implements Recombiner {
        private final double beta;
        private final double symmetryCut;
        private final double r0sqr;
        private final Recombiner recombiner;
        private final List<Integer> rejected = new ArrayList<>();

        public SoftDropRecombiner(double beta, double symmetryCut, double r0, Recombiner recombiner) {
            this.beta = beta;
            this.symmetryCut = symmetryCut;
            this.r0sqr = r0 * r0;
            this.recombiner = recombiner;
        }

        @Override
        public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
            final PseudoJet p = new PseudoJet(0, 0, 0, 0, pab.precision());
            recombiner.recombine(pa, pb, p);
            final double cutFn = symmetryCut * CRMath.pow(pa.squaredDistance(pb) / r0sqr, 0.5 * beta);
            final double pta = pa.pt();
            final double ptb = pb.pt();
            double sym = pta + ptb;
            if (sym == 0) {
                pab.reset(p);
                return;
            }
            sym = Math.min(pta, ptb) / sym;
            if (sym > cutFn) {
                pab.reset(p);
                return;
            }
            if (pta < ptb) {
                pab.reset(pb);
                rejected.add(pa.clusterHistIndex());
            } else {
                pab.reset(pa);
                rejected.add(pb.clusterHistIndex());
            }
        }

        @Override
        public String description() {
            return "SoftDrop recombiner with symmetry_cut = " + Fmt.g(symmetryCut) + ", beta = " + Fmt.g(beta)
                + ", and underlying recombiner = " + recombiner.description();
        }

        public List<Integer> rejected() {
            return rejected;
        }

        public void clearRejected() {
            rejected.clear();
        }
    }

    /** The clustering with the recombiner above, BottomUpSoftDropPlugin. */
    public static final class Plugin implements JetDefinition.Plugin {
        private final JetDefinition jetDef;
        private final double beta;
        private final double symmetryCut;
        private final double r0;

        public Plugin(JetDefinition jetDef, double beta, double symmetryCut, double r0) {
            this.jetDef = jetDef;
            this.beta = beta;
            this.symmetryCut = symmetryCut;
            this.r0 = r0;
        }

        @Override
        public double R() {
            return jetDef.R();
        }

        @Override
        public void runClustering(ClusterSequence inputCs) {
            final SoftDropRecombiner sdRecombiner = new SoftDropRecombiner(beta, symmetryCut, r0, jetDef.recombiner());
            final JetDefinition def = new JetDefinition(jetDef);
            def.setRecombiner(sdRecombiner);
            def.setPrecision(inputCs.precision());
            final ClusterSequence internal = new ClusterSequence(inputCs.jets(), def);
            final List<ClusterSequence.HistoryElement> internalHist = internal.history();
            final boolean[] kept = new boolean[internalHist.size()];
            Arrays.fill(kept, true);
            for (int r : sdRecombiner.rejected()) kept[r] = false;

            final int nInput = inputCs.nJets();
            final int[] internal2input = new int[internalHist.size()];
            for (int i = 0; i < nInput; i++) internal2input[i] = i;
            for (int i = nInput; i < internalHist.size(); i++) {
                final ClusterSequence.HistoryElement he = internalHist.get(i);
                if (he.parent2() == ClusterSequence.BEAM_JET) {
                    final int internalJetpIndex = internalHist.get(he.parent1()).jetpIndex();
                    final int internalHistIndex = internal.jet(internalJetpIndex).clusterHistIndex();
                    final int inputJetpIndex = inputCs.history().get(internal2input[internalHistIndex]).jetpIndex();
                    inputCs.pluginRecordIBRecombination(inputJetpIndex, he.dij());
                    continue;
                }
                if (!kept[he.parent1()]) {
                    internal2input[i] = internal2input[he.parent2()];
                } else if (!kept[he.parent2()]) {
                    internal2input[i] = internal2input[he.parent1()];
                } else {
                    final int newIndex = inputCs.pluginRecordIJRecombination(
                        inputCs.history().get(internal2input[he.parent1()]).jetpIndex(),
                        inputCs.history().get(internal2input[he.parent2()]).jetpIndex(),
                        he.dij(), internal.jet(he.jetpIndex()));
                    internal2input[i] = inputCs.jet(newIndex).clusterHistIndex();
                }
            }
        }

        @Override
        public String description() {
            return "BottomUpSoftDropPlugin with jet_definition = (" + jetDef.description() + "), symmetry_cut = "
                + Fmt.g(symmetryCut) + ", beta = " + Fmt.g(beta) + ", R0 = " + Fmt.g(r0);
        }
    }
}
