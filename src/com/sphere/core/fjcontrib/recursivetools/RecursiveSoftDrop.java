package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.internal.StdPriorityQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive soft drop, fastjet::contrib::RecursiveSoftDrop (F.A. Dreyer,
 * L. Necib, G. Soyez and J. Thaler, JHEP 06 (2018) 093): the soft-drop
 * condition applied again in the branches that passed, from the largest
 * angle down, until n splittings have passed (n = -1: all the way down).
 *
 * Variants: fixed depth (every branch recursed into down to depth n), a
 * dynamical R0 (the angle of the last splitting found), the hardest branch
 * only (iterated soft drop), a smallest angle.
 */
public class RecursiveSoftDrop extends SoftDrop {

    static {
        ContribCitations.use("rsd");
    }

    private final int n;
    private boolean fixedDepth;
    private boolean dynamicalR0;
    private boolean hardestBranchOnly;
    private double minDR2;

    public RecursiveSoftDrop(double beta, double symmetryCut) {
        this(beta, symmetryCut, -1, 1.0, null);
    }

    public RecursiveSoftDrop(double beta, double symmetryCut, int n) {
        this(beta, symmetryCut, n, 1.0, null);
    }

    public RecursiveSoftDrop(double beta, double symmetryCut, int n, double r0) {
        this(beta, symmetryCut, n, r0, null);
    }

    public RecursiveSoftDrop(double beta, double symmetryCut, int n, double r0, FunctionOfPseudoJet<PseudoJet> subtractor) {
        super(beta, symmetryCut, r0, subtractor);
        this.n = n;
        setDefaults();
    }

    public RecursiveSoftDrop(double beta, double symmetryCut, SymmetryMeasure symmetryMeasure, int n, double r0,
                             double muCut, RecursionChoice recursionChoice, FunctionOfPseudoJet<PseudoJet> subtractor) {
        super(beta, symmetryCut, symmetryMeasure, r0, muCut, recursionChoice, subtractor);
        this.n = n;
        setDefaults();
    }

    public int n() { return n; }

    public final void setDefaults() {
        setFixedDepthMode(false);
        setDynamicalR0(false);
        setHardestBranchOnly(false);
        setMinDeltaRSquared(-1.0);
    }

    /** Recurse in all branches at once down to depth n, rather than until n passes. */
    public void setFixedDepthMode(boolean value) { fixedDepth = value; }
    public boolean fixedDepthMode() { return fixedDepth; }

    /** R0 set to the angle of the last splitting found. */
    public void setDynamicalR0(boolean value) { dynamicalR0 = value; }
    public boolean useDynamicalR0() { return dynamicalR0; }

    /** Follow only the hardest branch of a splitting found. */
    public void setHardestBranchOnly(boolean value) { hardestBranchOnly = value; }
    public boolean useHardestBranchOnly() { return hardestBranchOnly; }

    /** The smallest squared angle searched (-1: none). */
    public void setMinDeltaRSquared(double value) { minDR2 = value; }
    public double minDeltaRSquared() { return minDR2; }

    @Override
    public String description() {
        final StringBuilder res = new StringBuilder("recursive application of [")
            .append(super.description()).append("]");
        if (fixedDepth) {
            res.append(", recursively applied down to a maximal depth of N=").append(n == -1 ? "infinity" : String.valueOf(n));
        } else {
            res.append(", applied N=").append(n == -1 ? "infinity" : String.valueOf(n)).append(" times");
        }
        res.append(dynamicalR0 ? ", with R0 dynamically scaled" : ", with R0 kept fixed");
        if (hardestBranchOnly) res.append(", following only the hardest branch");
        if (minDR2 > 0) res.append(", with minimal angle (squared) = ").append(Fmt.g(minDR2));
        return res.toString();
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        return fixedDepth ? resultFixedDepth(jet) : resultFixedTags(jet);
    }

    private boolean continueGrooming(int currentN) {
        return n < 0 || currentN < n;
    }

    /** A node of the RSD tree: created at the top of a branch, updated as it is groomed. */
    private static final class HistoryElement {
        int currentInCaTree;
        double thetaSquared;
        final double r0Squared;
        int child1InHistory = -1;
        int child2InHistory = -1;
        final List<Double> droppedDeltaR = new ArrayList<>();
        final List<Double> droppedSymmetry = new ArrayList<>();
        final List<Double> droppedMu = new ArrayList<>();
        double symmetry = -1.0;
        double mu2 = -1.0;

        HistoryElement(PseudoJet jet, RecursiveSoftDrop rsd, double r0sqr) {
            r0Squared = r0sqr;
            reset(jet, rsd);
        }

        void reset(PseudoJet jet, RecursiveSoftDrop rsd) {
            currentInCaTree = jet.clusterHistIndex();
            final PseudoJet[] p = jet.parents();
            thetaSquared = p != null ? rsd.squaredGeometricDistance(p[0], p[1]) : 0.0;
        }
    }

    private static double[] arr(List<Double> v) {
        final double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    private static PseudoJet treeJet(ClusterSequence cs, int histIndex) {
        return cs.jet(cs.history().get(histIndex).jetpIndex());
    }

    /** Declusters from large to small angles until n splittings have passed. */
    public PseudoJet resultFixedTags(PseudoJet jet) {
        final PseudoJet caJet = reclusterIfNeeded(jet);
        if (!caJet.hasValidClusterSequence()) {
            throw new FastJetException("RecursiveSoftDrop can only be applied to jets associated to a (valid) cluster sequence");
        }
        final ClusterSequence cs = caJet.validatedCs();
        int nTagged = 0;
        int maxNjet = caJet.constituents().size();

        final List<HistoryElement> history = new ArrayList<>();
        history.add(new HistoryElement(caJet, this, r0sqr));
        final StdPriorityQueue<HistoryElement> active =
            new StdPriorityQueue<>((e1, e2) -> e1.thetaSquared < e2.thetaSquared ? -1 : 0);
        active.push(history.get(0));

        final Step step = new Step();
        while (continueGrooming(nTagged) && active.size() > 0) {
            final HistoryElement elm = active.top();
            final PseudoJet parent = treeJet(cs, elm.currentInCaTree);
            final RecursionStatus status = recurseOneStep(parent, step, elm.r0Squared);
            if (status == RecursionStatus.SUCCESS) {
                if (minDR2 > 0 && squaredGeometricDistance(step.piece1, step.piece2) < minDR2) break;
                elm.child1InHistory = history.size();
                elm.child2InHistory = history.size() + 1;
                elm.symmetry = step.sym;
                elm.mu2 = step.mu2;
                active.pop();
                final double nextR0Squared = dynamicalR0 ? step.piece1.squaredDistance(step.piece2) : elm.r0Squared;
                final HistoryElement elm1 = new HistoryElement(step.piece1, this, nextR0Squared);
                history.add(elm1);
                active.push(elm1);
                final HistoryElement elm2 = new HistoryElement(step.piece2, this, nextR0Squared);
                history.add(elm2);
                if (!hardestBranchOnly) active.push(elm2);
                ++nTagged;
            } else if (status == RecursionStatus.DROPPED) {
                if (minDR2 > 0 && squaredGeometricDistance(step.piece1, step.piece2) < minDR2) break;
                active.pop();
                maxNjet -= step.piece2.constituents().size();
                // as in the C++, a negative squared value would give NaN here
                elm.droppedDeltaR.add(elm.thetaSquared >= 0 ? Math.sqrt(elm.thetaSquared) : -Math.sqrt(elm.thetaSquared));
                elm.droppedSymmetry.add(step.sym);
                elm.droppedMu.add(step.mu2 >= 0 ? Math.sqrt(step.mu2) : -Math.sqrt(step.mu2));
                elm.reset(step.piece1, this);
                active.push(elm);
            } else if (status == RecursionStatus.NO_PARENTS) {
                if (minDR2 > 0) break;
                active.pop();
            } else {
                active.pop();
                // a critical problem leaves piece2 empty, and an issue always does
                if (step.piece2.isZero()) return new PseudoJet();
                if (minDR2 > 0) break;
                maxNjet -= step.piece2.constituents().size() - 1;
                break;
            }
            if (nTagged == maxNjet) break;
        }
        return assemble(cs, history, true);
    }

    /** Recurses into every branch found at the previous depth, down to depth n. */
    public PseudoJet resultFixedDepth(PseudoJet jet) {
        final PseudoJet caJet = reclusterIfNeeded(jet);
        if (!caJet.hasValidClusterSequence()) {
            throw new FastJetException("RecursiveSoftDrop can only be applied to jets associated to a (valid) cluster sequence");
        }
        final ClusterSequence cs = caJet.validatedCs();
        int nDepth = 0;
        final List<HistoryElement> history = new ArrayList<>();
        history.add(new HistoryElement(caJet, this, r0sqr));
        history.get(history.size() - 1).thetaSquared = r0sqr;
        List<HistoryElement> active = new ArrayList<>();
        active.add(history.get(0));

        while (continueGrooming(nDepth) && !active.isEmpty()) {
            final List<HistoryElement> next = new ArrayList<>();
            for (HistoryElement elm : active) {
                final PseudoJet parent = treeJet(cs, elm.currentInCaTree);
                final PseudoJet resultSd;
                if (dynamicalR0) {
                    final SoftDrop sd = new SoftDrop(beta, symmetryCut, symmetryMeasure(), Math.sqrt(elm.thetaSquared),
                        muCut(), recursionChoice(), subtractor());
                    sd.setReclustering(false);
                    sd.setVerboseStructure(hasVerboseStructure());
                    resultSd = sd.result(parent);
                } else {
                    resultSd = super.result(parent);
                }
                if (resultSd.isZero()) return new PseudoJet();
                final StructureType st = (StructureType) resultSd.structure();
                elm.currentInCaTree = resultSd.clusterHistIndex();
                if (hasVerboseStructure()) {
                    elm.droppedDeltaR.clear();
                    elm.droppedDeltaR.addAll(st.droppedDeltaR());
                    elm.droppedSymmetry.clear();
                    elm.droppedSymmetry.addAll(st.droppedSymmetry());
                    elm.droppedMu.clear();
                    elm.droppedMu.addAll(st.droppedMu());
                }
                if (st.hasSubstructure()) {
                    elm.child1InHistory = history.size();
                    elm.child2InHistory = history.size() + 1;
                    elm.thetaSquared = st.deltaR() * st.deltaR();
                    elm.symmetry = st.symmetry();
                    elm.mu2 = st.mu() * st.mu();
                    final PseudoJet[] pieces = resultSd.parents();
                    final HistoryElement elm1 = new HistoryElement(pieces[0], this, r0sqr);
                    history.add(elm1);
                    if (elm1.thetaSquared > 0) next.add(elm1);
                    final HistoryElement elm2 = new HistoryElement(pieces[1], this, r0sqr);
                    history.add(elm2);
                    if (!hardestBranchOnly && elm2.thetaSquared > 0) next.add(elm2);
                }
            }
            active = next;
            ++nDepth;
        }
        return assemble(cs, history, false);
    }

    /** Builds the result from the tree: leaves are C/A subjets, nodes are joins. */
    private PseudoJet assemble(ClusterSequence cs, List<HistoryElement> history, boolean leavesKeepDropped) {
        final PseudoJet[] mapped = new PseudoJet[history.size()];
        for (int h = history.size() - 1; h >= 0; h--) {
            final HistoryElement elm = history.get(h);
            PseudoJet subjet;
            StructureType structure;
            if (elm.child1InHistory < 0) {
                subjet = treeJet(cs, elm.currentInCaTree);
                structure = new StructureType(subjet);
                if (hasVerboseStructure()) {
                    structure.setVerbose(true);
                    if (leavesKeepDropped) {
                        structure.setDroppedDeltaR(arr(elm.droppedDeltaR));
                        structure.setDroppedSymmetry(arr(elm.droppedSymmetry));
                        structure.setDroppedMu(arr(elm.droppedMu));
                    }
                }
            } else {
                subjet = PseudoJet.join(mapped[elm.child1InHistory], mapped[elm.child2InHistory]);
                structure = new StructureType(subjet, Math.sqrt(elm.thetaSquared), elm.symmetry, Math.sqrt(elm.mu2));
                if (hasVerboseStructure()) {
                    structure.setVerbose(true);
                    structure.setDroppedDeltaR(arr(elm.droppedDeltaR));
                    structure.setDroppedSymmetry(arr(elm.droppedSymmetry));
                    structure.setDroppedMu(arr(elm.droppedMu));
                }
            }
            subjet.setStructure(structure);
            mapped[h] = subjet;
        }
        return mapped[0];
    }

    /**
     * The prongs of a jet from RecursiveSoftDrop, as a flat list (no
     * particular order), rather than by walking the pairwise joins.
     */
    public static List<PseudoJet> recursiveSoftDropProngs(PseudoJet rsdJet) {
        if (!(rsdJet.structure() instanceof StructureType st)) return new ArrayList<>();
        if (!st.hasSubstructure()) {
            final List<PseudoJet> one = new ArrayList<>();
            one.add(rsdJet);
            return one;
        }
        final List<PseudoJet> prongs = new ArrayList<>();
        final List<PseudoJet> toParse = new ArrayList<>(rsdJet.pieces());
        int i = 0;
        while (i < toParse.size()) {
            final PseudoJet current = toParse.get(i);
            if (current.structure() instanceof StructureType cst && cst.hasSubstructure()) {
                final List<PseudoJet> pieces = current.pieces();
                toParse.set(i, pieces.get(0));
                toParse.add(pieces.get(1));
            } else {
                prongs.add(current);
                ++i;
            }
        }
        return prongs;
    }
}
