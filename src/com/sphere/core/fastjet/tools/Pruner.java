package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceActiveAreaExplicitGhosts;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.WrappedStructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Pruning, fastjet::Pruner: the jet is reclustered, and at each merging the
 * softer branch is dropped if it carries less than zcut of the pt and lies
 * farther than Rcut. By default Rcut = Rcut_factor * 2 m / pt.
 */
public class Pruner implements Transformer {

    static {
        Citations.use("pruner"); // listed in the console's Citations menu once used
    }

    /** The pruned jet's structure. */
    public static final class PrunerStructure extends WrappedStructure {
        double rcut;
        double zcut;

        PrunerStructure(PseudoJet resultJet) {
            super(resultJet.structure());
        }

        @Override
        public String description() {
            return "Pruned PseudoJet";
        }

        /** The branches pruned away. */
        public List<PseudoJet> rejected() {
            return validatedCs().childlessPseudojets();
        }

        /** The other inclusive jets of the pruning clustering. */
        public List<PseudoJet> extraJets() {
            return PseudoJet.sortedByPt(Selector.nHardest(1).negate().apply(validatedCs().inclusiveJets()));
        }

        public double rcut() {
            return rcut;
        }

        public double zcut() {
            return zcut;
        }
    }

    /** Recombines, unless the softer piece is too soft and too far, which it then drops. */
    public static final class PruningRecombiner implements Recombiner {
        private final double zcut2;
        private final double rcut2;
        private final Recombiner recombiner;
        private final List<Integer> rejected = new ArrayList<>();

        public PruningRecombiner(double zcut, double rcut, Recombiner recombiner) {
            this.zcut2 = zcut * zcut;
            this.rcut2 = rcut * rcut;
            this.recombiner = recombiner;
        }

        @Override
        public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
            final PseudoJet p = new PseudoJet(0, 0, 0, 0, pa.precision());
            recombiner.recombine(pa, pb, p);
            if (pa.squaredDistance(pb) <= rcut2) {
                pab.reset(p);
                return;
            }
            final double pt2a = pa.perp2();
            final double pt2b = pb.perp2();
            if (pt2a < pt2b) {
                if (pt2a < zcut2 * p.perp2()) {
                    pab.reset(pb);
                    rejected.add(pa.clusterHistIndex());
                } else {
                    pab.reset(p);
                }
            } else {
                if (pt2b < zcut2 * p.perp2()) {
                    pab.reset(pa);
                    rejected.add(pb.clusterHistIndex());
                } else {
                    pab.reset(p);
                }
            }
        }

        @Override
        public String description() {
            return "Pruning recombiner with zcut = " + Fmt.g(Math.sqrt(zcut2)) + ", Rcut = " + Fmt.g(Math.sqrt(rcut2))
                + ", and underlying recombiner = " + recombiner.description();
        }

        public List<Integer> rejected() {
            return rejected;
        }

        public void clearRejected() {
            rejected.clear();
        }
    }

    /** Runs the pruned clustering and records it, dropping what was pruned. */
    public static final class PruningPlugin implements JetDefinition.Plugin {
        private final JetDefinition jetDef;
        private final double zcut;
        private final double rcut;

        public PruningPlugin(JetDefinition jetDef, double zcut, double rcut) {
            this.jetDef = jetDef;
            this.zcut = zcut;
            this.rcut = rcut;
        }

        @Override
        public void runClustering(ClusterSequence inputCs) {
            final PruningRecombiner rec = new PruningRecombiner(zcut, rcut, jetDef.recombiner());
            final JetDefinition def = new JetDefinition(jetDef);
            def.setRecombiner(rec);
            final ClusterSequence internal = new ClusterSequence(inputCs.jets(), def);
            final List<ClusterSequence.HistoryElement> hist = internal.history();
            final boolean[] kept = new boolean[hist.size()];
            java.util.Arrays.fill(kept, true);
            for (int r : rec.rejected()) {
                kept[r] = false;
            }
            final int[] internal2input = new int[hist.size()];
            final int n = inputCs.nJets();
            for (int i = 0; i < n; i++) {
                internal2input[i] = i;
            }
            for (int i = n; i < hist.size(); i++) {
                final ClusterSequence.HistoryElement he = hist.get(i);
                if (he.parent2() == ClusterSequence.BEAM_JET) {
                    final int internalJetp = hist.get(he.parent1()).jetpIndex();
                    final int internalHist = internal.jet(internalJetp).clusterHistIndex();
                    final int inputJetp = inputCs.history().get(internal2input[internalHist]).jetpIndex();
                    inputCs.pluginRecordIBRecombination(inputJetp, he.dij());
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
            return "Pruning plugin with jet_definition = (" + jetDef.description() + "), zcut = " + Fmt.g(zcut)
                + ", Rcut = " + Fmt.g(rcut);
        }

        @Override
        public double R() {
            return jetDef.R();
        }
    }

    private final JetDefinition jetDef;
    private final double zcut;
    private final double rcutFactor;
    private final FunctionOfPseudoJet<Double> zcutDyn;
    private final FunctionOfPseudoJet<Double> rcutDyn;
    private final boolean getRecombinerFromJet;

    /** Pruning with an algorithm of infinite radius, the recombiner taken from the jet. */
    public Pruner(JetAlgorithm alg, double zcut, double rcutFactor) {
        this.jetDef = new JetDefinition(alg, JetDefinition.MAX_ALLOWABLE_R);
        this.zcut = zcut;
        this.rcutFactor = rcutFactor;
        this.zcutDyn = null;
        this.rcutDyn = null;
        this.getRecombinerFromJet = true;
    }

    public Pruner(JetDefinition jetDef, double zcut, double rcutFactor) {
        this.jetDef = jetDef;
        this.zcut = zcut;
        this.rcutFactor = rcutFactor;
        this.zcutDyn = null;
        this.rcutDyn = null;
        this.getRecombinerFromJet = false;
    }

    /** Pruning with zcut and Rcut computed from each jet. */
    public Pruner(JetDefinition jetDef, FunctionOfPseudoJet<Double> zcutDyn, FunctionOfPseudoJet<Double> rcutDyn) {
        if (zcutDyn == null || rcutDyn == null) {
            throw new FastJetException("Pruner: dynamic zcut and Rcut must both be given");
        }
        this.jetDef = jetDef;
        this.zcut = 0;
        this.rcutFactor = 0;
        this.zcutDyn = zcutDyn;
        this.rcutDyn = rcutDyn;
        this.getRecombinerFromJet = false;
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasConstituents()) {
            throw new FastJetException("Pruner: trying to apply the Pruner transformer to a jet that has no constituents");
        }
        final boolean doAreas = jet.hasArea() && checkExplicitGhosts(jet);
        final double rcut = rcutDyn != null ? rcutDyn.result(jet) : rcutFactor * 2.0 * jet.m() / jet.perp();
        final double z = zcutDyn != null ? zcutDyn.result(jet) : zcut;
        final PruningPlugin plugin;
        if (getRecombinerFromJet) {
            final JetDefinition def = new JetDefinition(jetDef);
            final JetDefinition[] forRec = new JetDefinition[1];
            if (checkCommonRecombiner(jet, forRec)) {
                def.setRecombiner(forRec[0]);
            }
            plugin = new PruningPlugin(def, z, rcut);
        } else {
            plugin = new PruningPlugin(jetDef, z, rcut);
        }
        final JetDefinition internalDef = new JetDefinition(plugin);
        internalDef.setPrecision(jet.precision());
        final ClusterSequence cs;
        if (doAreas) {
            final List<PseudoJet> particles = new ArrayList<>();
            final List<PseudoJet> ghosts = new ArrayList<>();
            Selector.isPureGhost().sift(jet.constituents(), ghosts, particles);
            final double ghostArea = ghosts.isEmpty() ? 0.01 : ghosts.get(0).area();
            cs = new ClusterSequenceActiveAreaExplicitGhosts(particles, internalDef, ghosts, ghostArea);
        } else {
            cs = new ClusterSequence(jet.constituents(), internalDef);
        }
        final PseudoJet resultLocal = Selector.nHardest(1).apply(cs.inclusiveJets()).get(0);
        final PrunerStructure s = new PrunerStructure(resultLocal);
        s.rcut = rcut;
        s.zcut = z;
        resultLocal.setStructure(s);
        return resultLocal;
    }

    private boolean checkExplicitGhosts(PseudoJet jet) {
        if (jet.hasAssociatedClusterSequence()) {
            return jet.validatedCsab().hasExplicitGhosts();
        }
        if (jet.hasPieces()) {
            for (PseudoJet p : jet.pieces()) {
                if (!checkExplicitGhosts(p)) return false;
            }
            return true;
        }
        return false;
    }

    private boolean checkCommonRecombiner(PseudoJet jet, JetDefinition[] forRec) {
        if (jet.hasAssociatedClusterSequence()) {
            if (forRec[0] != null) {
                return jet.validatedCs().jetDef().hasSameRecombiner(forRec[0]);
            }
            forRec[0] = jet.validatedCs().jetDef();
            return true;
        }
        if (jet.hasPieces()) {
            final List<PseudoJet> pieces = jet.pieces();
            if (pieces.isEmpty()) return false;
            for (PseudoJet p : pieces) {
                if (!checkCommonRecombiner(p, forRec)) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public String description() {
        final StringBuilder o = new StringBuilder("Pruner with jet_definition = (").append(jetDef.description()).append(")");
        if (zcutDyn != null) {
            o.append(", dynamic zcut (").append(zcutDyn.description()).append(")")
             .append(", dynamic Rcut (").append(rcutDyn.description()).append(")");
        } else {
            o.append(", zcut = ").append(Fmt.g(zcut)).append(", Rcut_factor = ").append(Fmt.g(rcutFactor));
        }
        return o.toString();
    }
}
