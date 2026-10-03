package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceAreaBase;
import com.sphere.core.fastjet.CompositeJetStructure;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.PseudoJetStructure;
import com.sphere.core.fastjet.WrappedStructure;
import com.sphere.core.fastjet.tools.Recluster;
import com.sphere.core.fastjet.tools.Transformer;

import java.util.ArrayList;
import java.util.List;

/**
 * The declustering shared by the modified mass-drop tagger and soft drop,
 * fastjet::contrib::RecursiveSymmetryCutBase (RecursiveTools 2.0.4): the
 * jet is reclustered with Cambridge/Aachen, then undone one step at a time,
 * following the harder branch, until a splitting passes a symmetry cut that
 * the subclass defines (and, optionally, a mass-drop cut).
 *
 * With double jets every number is the C++'s, operation for operation. With
 * double-double jets the symmetry and the cut it is compared to are
 * evaluated to 106 bits, so that a splitting on the edge of the cut is kept
 * or dropped by the physics rather than by rounding; the values stored in
 * the structure are then the doubles nearest them. The structure also
 * records how far from the cut each decision was ({@link
 * StructureType#decisionMargin()}), which tells a fragile groomed jet from a
 * robust one.
 */
public abstract class RecursiveSymmetryCutBase implements Transformer {

    /** The (a)symmetry measures. */
    public enum SymmetryMeasure {
        /** min(pt_i, pt_j)/(pt_i + pt_j). */
        SCALAR_Z("scalar_z"),
        /** min(pt_i, pt_j)/pt_(i+j). */
        VECTOR_Z("vector_z"),
        /** min(pt_i^2, pt_j^2) DeltaR_ij^2 / m_ij^2. */
        Y("y"),
        /** min(E_i, E_j)/(E_i + E_j) with the 3d angle (e+e-). */
        THETA_E("theta_E"),
        /** min(E_i, E_j)/(E_i + E_j) with sqrt(2[1-cos theta]) for angles (e+e-). */
        COS_THETA_E("cos_theta_E");

        private final String label;

        SymmetryMeasure(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Which of the two subjets the recursion follows. */
    public enum RecursionChoice {
        LARGER_PT("pt"),
        LARGER_MT("mt(=sqrt(m^2+pt^2))"),
        LARGER_M("mass"),
        LARGER_E("energy");

        private final String label;

        RecursionChoice(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What one step of the recursion found. */
    protected enum RecursionStatus {
        /** Some substructure. */
        SUCCESS,
        /** The softer prong was dropped; the recursion goes on. */
        DROPPED,
        /** Down to a constituent: the bottom of the recursion. */
        NO_PARENTS,
        /** Something went wrong: the recursion stops. */
        ISSUE
    }

    /**
     * The output arguments of one step (piece1, piece2, sym, mu2 in the C++),
     * with the relative distance to the cut the step was decided by.
     */
    protected static final class Step {
        public PseudoJet piece1;
        public PseudoJet piece2;
        public double sym;
        public double mu2;
        /** (sym - cut)/cut, from the 106-bit comparison when the jets are double-double. */
        public double margin = Double.NaN;
    }

    /** For testing, as in the C++. */
    public static boolean verbose = false;

    private static final LimitedWarning NEGATIVE_MASS_WARNING = new LimitedWarning();
    private static final LimitedWarning MU2_GT1_WARNING = new LimitedWarning();
    private static final LimitedWarning EXPLICIT_GHOST_WARNING = new LimitedWarning();

    private final SymmetryMeasure symmetryMeasure;
    private final double muCut;
    private final RecursionChoice recursionChoice;
    private FunctionOfPseudoJet<PseudoJet> subtractor;
    private boolean inputJetIsSubtracted;
    private boolean doReclustering = true;
    private FunctionOfPseudoJet<PseudoJet> recluster;
    private boolean groomingMode;
    private boolean verboseStructure;

    protected RecursiveSymmetryCutBase() {
        this(SymmetryMeasure.SCALAR_Z, Double.POSITIVE_INFINITY, RecursionChoice.LARGER_PT, null);
    }

    /**
     * @param symmetryMeasure the measure of the symmetry
     * @param muCut           the largest mu = m_heavy/m_parent allowed (infinity: no cut)
     * @param recursionChoice which subjet to recurse into
     * @param subtractor      a pileup subtractor, or null
     */
    protected RecursiveSymmetryCutBase(SymmetryMeasure symmetryMeasure, double muCut,
                                       RecursionChoice recursionChoice, FunctionOfPseudoJet<PseudoJet> subtractor) {
        this.symmetryMeasure = symmetryMeasure;
        this.muCut = muCut;
        this.recursionChoice = recursionChoice;
        this.subtractor = subtractor;
    }

    public SymmetryMeasure symmetryMeasure() { return symmetryMeasure; }
    public double muCut() { return muCut; }
    public RecursionChoice recursionChoice() { return recursionChoice; }

    /** Whether the input jet is taken as already subtracted (relevant with a subtractor only). */
    public void setInputJetIsSubtracted(boolean isSubtracted) { inputJetIsSubtracted = isSubtracted; }
    public boolean inputJetIsSubtracted() { return inputJetIsSubtracted; }

    /** With a subtractor, the result is a subtracted jet. */
    public void setSubtractor(FunctionOfPseudoJet<PseudoJet> s) { subtractor = s; }
    public FunctionOfPseudoJet<PseudoJet> subtractor() { return subtractor; }

    /**
     * Whether to recluster before declustering, and how (Cambridge/Aachen
     * when {@code recluster} is null). The taggers are designed for C/A.
     */
    public void setReclustering(boolean doReclustering, FunctionOfPseudoJet<PseudoJet> recluster) {
        this.doReclustering = doReclustering;
        this.recluster = recluster;
    }

    public void setReclustering(boolean doReclustering) {
        setReclustering(doReclustering, null);
    }

    /** Grooming mode returns the last particle when nothing passes; tagging mode an empty jet. */
    public void setGroomingMode(boolean enable) { groomingMode = enable; }
    public void setTaggingMode(boolean enable) { groomingMode = !enable; }
    public boolean groomingMode() { return groomingMode; }

    /** Keep the symmetry, angle and mu of every dropped branch. */
    public void setVerboseStructure(boolean enable) { verboseStructure = enable; }
    public boolean hasVerboseStructure() { return verboseStructure; }

    /* ------------------------------------------------------------------ */
    /* What the subclasses define                                          */
    /* ------------------------------------------------------------------ */

    /**
     * The cut on the symmetry for the pair (p1, p2); {@code r0sqr} is the
     * normalisation a recursive variant passes (NaN for the default).
     */
    protected abstract double symmetryCutFn(PseudoJet p1, PseudoJet p2, double r0sqr);

    /** The same to 106 bits; by default the double one. */
    protected DD symmetryCutFnDD(PseudoJet p1, PseudoJet p2, double r0sqr) {
        return new DD(symmetryCutFn(p1, p2, r0sqr));
    }

    protected abstract String symmetryCutDescription();

    /* ------------------------------------------------------------------ */
    /* The transformer                                                     */
    /* ------------------------------------------------------------------ */

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasConstituents()) {
            throw new FastJetException("RecursiveSymmetryCutBase can only be applied to jets with constituents");
        }
        final PseudoJet j = reclusterIfNeeded(jet);
        if (!j.hasValidClusterSequence()) {
            throw new FastJetException("RecursiveSymmetryCutBase can only be applied to jets associated to a (valid) cluster sequence");
        }
        if (subtractor != null) {
            final ClusterSequence cs = j.associatedCs();
            if (!(cs instanceof ClusterSequenceAreaBase csab) || !csab.hasExplicitGhosts()) {
                EXPLICIT_GHOST_WARNING.warn("RecursiveSymmetryCutBase: there is no clustering sequence, or it lacks explicit ghosts: subtraction is not guaranteed to function properly");
            }
        }
        PseudoJet subjet = j;
        if (subtractor != null && !inputJetIsSubtracted) {
            subjet = subtractor.result(subjet);
        }

        final List<Double> droppedDeltaR = new ArrayList<>();
        final List<Double> droppedSymmetry = new ArrayList<>();
        final List<Double> droppedMu = new ArrayList<>();
        double closestDropped = Double.POSITIVE_INFINITY;

        final Step step = new Step();
        RecursionStatus status;
        while ((status = recurseOneStep(subjet, step, Double.NaN)) != RecursionStatus.SUCCESS) {
            if (status == RecursionStatus.ISSUE || status == RecursionStatus.NO_PARENTS) {
                PseudoJet result;
                if (status == RecursionStatus.ISSUE) {
                    result = step.piece1;
                    if (verbose) System.out.println("reached end; returning null jet ");
                } else {
                    result = resultNoSubstructure(step.piece1);
                    if (verbose) System.out.println("no parents found; returning last PJ or empty jet");
                }
                if (!result.isZero()) {
                    result = result.copy();
                    final StructureType structure = new StructureType(result);
                    structure.closestDroppedMargin = closestDropped;
                    if (verboseStructure) {
                        structure.hasVerbose = true;
                        structure.droppedSymmetry = toArray(droppedSymmetry);
                        structure.droppedMu = toArray(droppedMu);
                        structure.droppedDeltaR = toArray(droppedDeltaR);
                    }
                    result.setStructure(structure);
                }
                return result;
            }
            if (verboseStructure) {
                droppedDeltaR.add(step.piece1.deltaR(step.piece2));
                droppedSymmetry.add(step.sym);
                droppedMu.add(step.mu2 >= 0 ? Math.sqrt(step.mu2) : -Math.sqrt(-step.mu2));
            }
            if (Math.abs(step.margin) < Math.abs(closestDropped)) closestDropped = step.margin;
            subjet = step.piece1;
        }

        final PseudoJet out = subjet.copy();
        final StructureType structure = new StructureType(out);
        structure.symmetry = step.sym;
        structure.mu = step.mu2 >= 0 ? Math.sqrt(step.mu2) : -Math.sqrt(-step.mu2);
        structure.deltaR = Math.sqrt(squaredGeometricDistance(step.piece1, step.piece2));
        structure.margin = step.margin;
        structure.closestDroppedMargin = closestDropped;
        if (verboseStructure) {
            structure.hasVerbose = true;
            structure.droppedSymmetry = toArray(droppedSymmetry);
            structure.droppedMu = toArray(droppedMu);
            structure.droppedDeltaR = toArray(droppedDeltaR);
        }
        out.setStructure(structure);
        return out;
    }

    private static double[] toArray(List<Double> v) {
        final double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    /**
     * One step of the recursion. On success everything is filled; with no
     * parents piece1 is the subjet itself; on an issue piece2 is an empty jet
     * and piece1 what should be returned (empty if the issue was critical).
     *
     * @param r0sqr the normalisation passed to the symmetry cut (NaN: the tool's own)
     */
    protected RecursionStatus recurseOneStep(PseudoJet subjet, Step s, double r0sqr) {
        final PseudoJet[] parents = subjet.parents();
        s.margin = Double.NaN;
        if (parents == null) {
            s.piece1 = subjet;
            s.piece2 = new PseudoJet();
            return RecursionStatus.NO_PARENTS;
        }
        PseudoJet piece1 = parents[0];
        PseudoJet piece2 = parents[1];
        if (subjet.pt2() <= 0) {
            s.piece1 = new PseudoJet();
            s.piece2 = new PseudoJet();
            return RecursionStatus.ISSUE;
        }
        if (subtractor != null) {
            piece1 = subtractor.result(piece1);
            piece2 = subtractor.result(piece2);
        }
        final boolean dd = piece1.isDD() || piece2.isDD() || subjet.isDD();
        double sym;
        DD symDD = null;
        switch (symmetryMeasure) {
            case Y -> {
                if (subjet.m2() <= 0) {
                    NEGATIVE_MASS_WARNING.warn("RecursiveSymmetryCutBase: cannot calculate y, because (sub)jet mass is negative; bailing out");
                    s.piece1 = resultNoSubstructure(subjet);
                    s.piece2 = new PseudoJet();
                    return RecursionStatus.ISSUE;
                }
                sym = piece1.ktDistance(piece2) / subjet.m2();
                if (dd) {
                    symDD = DD.min(piece1.kt2DD(), piece2.kt2DD()).mul(piece1.squaredDistanceDD(piece2)).div(subjet.m2DD());
                }
            }
            case VECTOR_Z -> {
                sym = Math.min(piece1.pt(), piece2.pt()) / subjet.pt();
                if (dd) symDD = DD.min(piece1.ptDD(), piece2.ptDD()).div(subjet.ptDD());
            }
            case SCALAR_Z -> {
                final double pt1 = piece1.pt();
                final double pt2 = piece2.pt();
                sym = pt1 + pt2;
                if (sym == 0) {
                    s.piece1 = new PseudoJet();
                    s.piece2 = new PseudoJet();
                    return RecursionStatus.ISSUE;
                }
                sym = Math.min(pt1, pt2) / sym;
                if (dd) {
                    final DD a = piece1.ptDD();
                    final DD b = piece2.ptDD();
                    symDD = DD.min(a, b).div(a.add(b));
                }
            }
            case THETA_E, COS_THETA_E -> {
                final double e1 = piece1.E();
                final double e2 = piece2.E();
                sym = e1 + e2;
                if (sym == 0) {
                    s.piece1 = new PseudoJet();
                    s.piece2 = new PseudoJet();
                    return RecursionStatus.ISSUE;
                }
                sym = Math.min(e1, e2) / sym;
                if (dd) {
                    final DD a = piece1.eDD();
                    final DD b = piece2.eDD();
                    symDD = DD.min(a, b).div(a.add(b));
                }
            }
            default -> throw new FastJetException("Unrecognized choice of symmetry_measure");
        }

        boolean tagged;
        if (dd) {
            final DD cut = symmetryCutFnDD(piece1, piece2, r0sqr);
            tagged = symDD.gt(cut);
            s.margin = cut.isZero() ? (symDD.signum() == 0 ? 0.0 : Double.POSITIVE_INFINITY * symDD.signum())
                                    : symDD.sub(cut).div(cut).doubleValue();
            sym = symDD.doubleValue();
        } else {
            final double cut = symmetryCutFn(piece1, piece2, r0sqr);
            tagged = sym > cut;
            s.margin = cut == 0 ? (sym == 0 ? 0.0 : Math.copySign(Double.POSITIVE_INFINITY, sym)) : (sym - cut) / cut;
        }

        final boolean useMuCut = muCut != Double.POSITIVE_INFINITY;
        double mu2;
        if (subjet.m2() > 0) {
            mu2 = Math.max(piece1.m2(), piece2.m2()) / subjet.m2();
        } else {
            mu2 = -1.0;
        }
        if (tagged && useMuCut) {
            if (subjet.m2() <= 0) {
                NEGATIVE_MASS_WARNING.warn("RecursiveSymmetryCutBase: cannot trust mu, because (sub)jet mass is negative; bailing out");
                s.piece1 = new PseudoJet();
                s.piece2 = new PseudoJet();
                return RecursionStatus.ISSUE;
            }
            if (mu2 > 1) MU2_GT1_WARNING.warn("RecursiveSymmetryCutBase encountered mu^2 value > 1");
            if (mu2 > CRMath.pow(muCut, 2)) tagged = false;
        }

        final boolean swap = switch (recursionChoice) {
            case LARGER_PT -> piece1.pt2() < piece2.pt2();
            case LARGER_MT -> piece1.mt2() < piece2.mt2();
            case LARGER_M -> piece1.m2() < piece2.m2();
            case LARGER_E -> piece1.E() < piece2.E();
        };
        if (swap) {
            final PseudoJet t = piece1;
            piece1 = piece2;
            piece2 = t;
        }
        s.piece1 = piece1;
        s.piece2 = piece2;
        s.sym = sym;
        s.mu2 = mu2;
        return tagged ? RecursionStatus.SUCCESS : RecursionStatus.DROPPED;
    }

    @Override
    public String description() {
        final StringBuilder o = new StringBuilder("Recursive ").append(groomingMode ? "Groomer" : "Tagger")
            .append(" with a symmetry cut ").append(symmetryMeasure.label());
        o.append(" > ").append(symmetryCutDescription());
        if (muCut != Double.POSITIVE_INFINITY) {
            o.append(", mass-drop cut mu=max(m1,m2)/m < ").append(Fmt.g(muCut));
        } else {
            o.append(", no mass-drop requirement");
        }
        o.append(", recursion into the subjet with larger ").append(recursionChoice.label());
        if (subtractor != null) {
            o.append(", subtractor: ").append(subtractor.description());
            if (inputJetIsSubtracted) o.append(" (input jet is assumed already subtracted)");
        }
        if (recluster != null) {
            o.append(" and reclustering using ").append(recluster.description());
        }
        return o.toString();
    }

    /** The jet as the declustering needs it (C/A by default). */
    protected PseudoJet reclusterIfNeeded(PseudoJet jet) {
        if (!doReclustering) return jet;
        if (recluster != null) return recluster.result(jet);
        if (isEe()) {
            return new Recluster(new JetDefinition(JetAlgorithm.EE_GENKT, JetDefinition.MAX_ALLOWABLE_R, 0.0),
                true, Recluster.Keep.KEEP_ONLY_HARDEST).result(jet);
        }
        return new Recluster(JetAlgorithm.CAMBRIDGE, JetDefinition.MAX_ALLOWABLE_R, Recluster.Keep.KEEP_ONLY_HARDEST).result(jet);
    }

    protected final boolean isEe() {
        return symmetryMeasure == SymmetryMeasure.THETA_E || symmetryMeasure == SymmetryMeasure.COS_THETA_E;
    }

    /** The squared angle between two subjets, in the measure's geometry. */
    public double squaredGeometricDistance(PseudoJet j1, PseudoJet j2) {
        if (symmetryMeasure == SymmetryMeasure.THETA_E) {
            final double dot3d = j1.px() * j2.px() + j1.py() * j2.py() + j1.pz() * j2.pz();
            final double cosTheta = Math.max(-1.0, Math.min(1.0, dot3d / Math.sqrt(j1.modp2() * j2.modp2())));
            final double theta = CRMath.acos(cosTheta);
            return theta * theta;
        } else if (symmetryMeasure == SymmetryMeasure.COS_THETA_E) {
            final double dot3d = j1.px() * j2.px() + j1.py() * j2.py() + j1.pz() * j2.pz();
            return Math.max(0.0, 2 * (1 - dot3d / Math.sqrt(j1.modp2() * j2.modp2())));
        }
        return j1.squaredDistance(j2);
    }

    /** The same to 106 bits (the 3d angle from the cross product, stable when collinear). */
    public DD squaredGeometricDistanceDD(PseudoJet j1, PseudoJet j2) {
        if (symmetryMeasure == SymmetryMeasure.THETA_E) {
            return PseudoJet.thetaDD(j1, j2).sqr();
        } else if (symmetryMeasure == SymmetryMeasure.COS_THETA_E) {
            // 2(1 - cos theta) = 4 sin^2(theta/2)
            final DD s = PseudoJet.thetaDD(j1, j2).mulPow2(0.5).sin();
            return s.sqr().mulPow2(4.0);
        }
        return j1.squaredDistanceDD(j2);
    }

    /** What to return when no substructure was found. */
    protected PseudoJet resultNoSubstructure(PseudoJet lastParent) {
        return groomingMode ? lastParent : new PseudoJet();
    }

    /* ------------------------------------------------------------------ */
    /* The structure of the result                                         */
    /* ------------------------------------------------------------------ */

    /** What the tagged or groomed jet knows, RecursiveSymmetryCutBase::StructureType. */
    public static class StructureType extends WrappedStructure {
        double deltaR = -1.0;
        double symmetry = -1.0;
        double mu = -1.0;
        final boolean isComposite;
        boolean hasVerbose;
        double[] droppedDeltaR = new double[0];
        double[] droppedSymmetry = new double[0];
        double[] droppedMu = new double[0];
        double margin = Double.NaN;
        double closestDroppedMargin = Double.POSITIVE_INFINITY;

        public StructureType(PseudoJet j) {
            super(j.structure());
            isComposite = j.structure() instanceof CompositeJetStructure;
        }

        public StructureType(PseudoJet j, double deltaRIn, double symmetryIn, double muIn) {
            super(j.structure());
            deltaR = deltaRIn;
            symmetry = symmetryIn;
            mu = muIn;
            isComposite = j.structure() instanceof CompositeJetStructure;
        }

        /** The angle of the splitting kept (-1 when none). */
        public double deltaR() { return deltaR; }
        public double thetag() { return deltaR; }
        /** Its symmetry, z_g for soft drop. */
        public double symmetry() { return symmetry; }
        public double zg() { return symmetry; }
        public double mu() { return mu; }

        public boolean hasVerbose() { return hasVerbose; }
        public void setVerbose(boolean value) { hasVerbose = value; }

        /** Whether some substructure was found (deltaR = 0 included, a perfectly collinear splitting). */
        public boolean hasSubstructure() { return deltaR >= 0; }

        /**
         * (z - cut)/cut for the splitting that passed, the relative room it
         * had; NaN when none passed. Sphere's addition.
         */
        public double decisionMargin() { return margin; }

        /**
         * Of the branches dropped on the way, the (signed) relative distance
         * to the cut of the one that came closest to passing; +infinity when
         * none was dropped. Sphere's addition.
         */
        public double closestDroppedMargin() { return closestDroppedMargin; }

        /**
         * Whether a relative change of the cut by {@code relative} could
         * change the result: the splitting kept or one of the branches
         * dropped was within that distance of it.
         */
        public boolean isFragile(double relative) {
            return (!Double.isNaN(margin) && Math.abs(margin) < relative)
                || Math.abs(closestDroppedMargin) < relative;
        }

        public void setDroppedDeltaR(double[] v) { droppedDeltaR = v.clone(); }
        public void setDroppedSymmetry(double[] v) { droppedSymmetry = v.clone(); }
        public void setDroppedMu(double[] v) { droppedMu = v.clone(); }

        private void checkVerbose(String what) {
            if (!hasVerbose) {
                throw new FastJetException("RecursiveSymmetryCutBase::StructureType: Verbose structure must be turned on to get " + what + ".");
            }
        }

        /** The prongs of a composite result that carry substructure themselves. */
        private List<StructureType> substructuredProngs(StructureType current) {
            final List<StructureType> out = new ArrayList<>();
            if (!(current.structure instanceof CompositeJetStructure css)) return out;
            final List<PseudoJet> prongs = css.pieces(null);
            for (PseudoJet prong : prongs) {
                if (prong.structure() instanceof StructureType ps && ps.hasSubstructure()) out.add(ps);
            }
            return out;
        }

        /** The number of dropped branches, down the whole tree when global. */
        public int droppedCount(boolean global) {
            checkVerbose("dropped_count()");
            if (!hasSubstructure()) return droppedDeltaR.length;
            if (!global) return droppedDeltaR.length;
            int count = 0;
            final List<StructureType> toParse = new ArrayList<>();
            toParse.add(this);
            for (int i = 0; i < toParse.size(); i++) {
                count += toParse.get(i).droppedDeltaR.length;
                toParse.addAll(substructuredProngs(toParse.get(i)));
            }
            return count;
        }

        public int droppedCount() { return droppedCount(true); }

        private interface Field {
            double[] of(StructureType s);
        }

        private List<Double> collect(boolean global, Field f) {
            final List<Double> all = new ArrayList<>();
            if (!hasSubstructure()) return all;
            if (!global) {
                for (double v : f.of(this)) all.add(v);
                return all;
            }
            final List<StructureType> toParse = new ArrayList<>();
            toParse.add(this);
            for (int i = 0; i < toParse.size(); i++) {
                for (double v : f.of(toParse.get(i))) all.add(v);
                toParse.addAll(substructuredProngs(toParse.get(i)));
            }
            return all;
        }

        /** The angles of the dropped branches. */
        public List<Double> droppedDeltaR(boolean global) {
            checkVerbose("dropped_delta_R()");
            return collect(global, s -> s.droppedDeltaR);
        }

        public List<Double> droppedDeltaR() { return droppedDeltaR(true); }

        /** The symmetries of the dropped branches. */
        public List<Double> droppedSymmetry(boolean global) {
            checkVerbose("dropped_symmetry()");
            return collect(global, s -> s.droppedSymmetry);
        }

        public List<Double> droppedSymmetry() { return droppedSymmetry(true); }

        /** The mass drops of the dropped branches. */
        public List<Double> droppedMu(boolean global) {
            checkVerbose("dropped_mu()");
            return collect(global, s -> s.droppedMu);
        }

        public List<Double> droppedMu() { return droppedMu(true); }

        /** The largest symmetry dropped. */
        public double maxDroppedSymmetry(boolean global) {
            checkVerbose("max_dropped_symmetry()");
            if (!hasSubstructure()) return 0.0;
            double localMax = 0.0;
            if (droppedSymmetry.length > 0) {
                localMax = droppedSymmetry[0];
                for (double v : droppedSymmetry) if (v > localMax) localMax = v;
            }
            if (global && structure instanceof CompositeJetStructure css) {
                for (PseudoJet prong : css.pieces(null)) {
                    if (prong.structure() instanceof StructureType ps) {
                        localMax = Math.max(localMax, ps.maxDroppedSymmetry(true));
                    }
                }
            }
            return localMax;
        }

        public double maxDroppedSymmetry() { return maxDroppedSymmetry(true); }

        /** Every (z_g, theta_g) found down the tree, by decreasing angle. */
        public List<double[]> sortedZgAndThetag() {
            final List<double[]> all = new ArrayList<>();
            if (!hasSubstructure()) return all;
            final List<StructureType> toParse = new ArrayList<>();
            toParse.add(this);
            for (int i = 0; i < toParse.size(); i++) {
                final StructureType current = toParse.get(i);
                all.add(new double[]{current.symmetry, current.deltaR});
                // the prongs of a composite jet; a jet of the C/A tree has none to parse
                toParse.addAll(substructuredProngs(current));
            }
            all.sort((p1, p2) -> Double.compare(p2[1], p1[1]));
            return all;
        }

        /** The structure it wraps. */
        public PseudoJetStructure wrappedStructure() {
            return structure;
        }
    }
}
