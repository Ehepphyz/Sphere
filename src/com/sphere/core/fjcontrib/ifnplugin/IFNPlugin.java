package com.sphere.core.fjcontrib.ifnplugin;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.List;

/**
 * Interleaved Flavour Neutralisation, fastjet::contrib::IFNPlugin (IFNPlugin
 * 1.0.4; F. Caola, R. Grabarczyk, M. Hutt, G.P. Salam, L. Scyboz and
 * J. Thaler, "Flavoured jets with exact anti-kt kinematics and tests of
 * infrared and collinear safety", Phys. Rev. D 108 (2023) 094010,
 * arXiv:2306.07314).
 *
 * <p>The event is clustered with the base definition (anti-kt or C/A, or
 * their e+e- forms), and the clustering replayed with {@link FlavNeutraliser}:
 * the jets are the base algorithm's to the bit, their flavours infrared and
 * collinear safe. The particles must carry a {@link FlavInfo} or a
 * {@link FlavHistory}; with {@link #setFlavourFromUserIndex(boolean)} those
 * that carry neither take the flavour of the PDG code in their user index
 * instead of being refused (Sphere's addition, for events read from LHE or
 * HepMC files).
 */
public class IFNPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("ifn");
    }

    private final JetDefinition jetDef;
    private final double p;
    private final double q;
    private final double a;
    private final boolean modulo2;
    private FlavNeutraliser.Measure measure;
    private final boolean useMassFlav;
    private final boolean sphericalAlgo;
    private final double pp;
    private boolean recursive = true;
    private boolean flavourFromUserIndex;

    /**
     * The published algorithm: u_ij = max(pt_i, pt_j)^alpha min(pt_i,
     * pt_j)^(2-alpha) Omega_ij, with Omega's rapidity term damped by omega
     * (&lt;= 0 means 3 - alpha, as in the paper).
     *
     * @param flavSummation NET or MODULO_2; ANY_ABS is refused
     */
    public IFNPlugin(JetDefinition jetDef, double alpha, double omega, FlavRecombiner.FlavSummation flavSummation,
                     boolean useMassFlav) {
        if (flavSummation == FlavRecombiner.FlavSummation.ANY_ABS) {
            throw new FastJetException("IFNPlugin: FlavRecombiner::any_abs is not supported");
        }
        this.jetDef = new JetDefinition(jetDef);
        this.p = 0.5 * alpha;
        this.q = 0.5 * (2 - alpha);
        this.a = omega > 0 ? omega : 3 - alpha;
        this.modulo2 = flavSummation == FlavRecombiner.FlavSummation.MODULO_2;
        this.measure = FlavNeutraliser.Measure.GENERAL;
        this.useMassFlav = useMassFlav;
        this.sphericalAlgo = jetDef.isSpherical();
        this.pp = 1;
        checkMod2Consistency();
    }

    public IFNPlugin(JetDefinition jetDef, double alpha, double omega, FlavRecombiner.FlavSummation flavSummation) {
        this(jetDef, alpha, omega, flavSummation, false);
    }

    public IFNPlugin(JetDefinition jetDef, double alpha) {
        this(jetDef, alpha, -1, FlavRecombiner.FlavSummation.NET, false);
    }

    /** The general measure with p, q and a given directly. */
    public IFNPlugin(JetDefinition jetDef, double p, double q, double a, boolean modulo2,
                     FlavNeutraliser.Measure measure, boolean useMassFlav, double pp, boolean recursive) {
        this.jetDef = new JetDefinition(jetDef);
        this.p = p;
        this.q = q;
        this.a = a;
        this.modulo2 = modulo2;
        this.measure = measure;
        this.useMassFlav = useMassFlav;
        this.sphericalAlgo = jetDef.isSpherical();
        this.pp = pp;
        this.recursive = recursive;
        checkMod2Consistency();
    }

    public void setRecursive(boolean value) { recursive = value; }
    public boolean recursive() { return recursive; }
    public void setMeasure(FlavNeutraliser.Measure m) { measure = m; }
    public FlavNeutraliser.Measure measure() { return measure; }
    public JetDefinition baseDefinition() { return new JetDefinition(jetDef); }
    public boolean modulo2() { return modulo2; }

    /** Particles without flavour information take it from the PDG code of their user index. */
    public IFNPlugin setFlavourFromUserIndex(boolean value) {
        flavourFromUserIndex = value;
        return this;
    }

    @Override public double R() { return jetDef.R(); }
    @Override public boolean isSpherical() { return sphericalAlgo; }
    @Override public boolean exclusiveSequenceMeaningful() { return jetDef.plugin() == null || jetDef.plugin().exclusiveSequenceMeaningful(); }

    private void checkMod2Consistency() {
        if (!(jetDef.recombiner() instanceof FlavRecombiner fr)) return;
        if (modulo2 && fr.flavSummation() != FlavRecombiner.FlavSummation.MODULO_2) {
            throw new FastJetException("IFNPlugin modulo_2 is set to true, but base jet definition ("
                + jetDef.description() + ") has a FlavRecombiner with flav_summation != modulo_2");
        }
        if (!modulo2 && fr.flavSummation() != FlavRecombiner.FlavSummation.NET) {
            throw new FastJetException("IFNPlugin modulo_2 is set to false, but base jet definition ()"
                + jetDef.description() + ") has a FlavRecombiner with flav_summation != net");
        }
    }

    @Override
    public String description() {
        final StringBuilder d = new StringBuilder("Interleaved Flavour Neutralisation (IFN) plugin based on ")
            .append(jetDef.description());
        if (sphericalAlgo) {
            d.append(", using a spherical neutralisation measure of type ");
            switch (measure) {
                case GENERAL -> {
                    if (p + q == 1.0) d.append("standard uij, with alpha = ").append(Fmt.g(2 * p));
                    else d.append("general case with p = ").append(Fmt.g(p)).append(" q = ").append(Fmt.g(q));
                }
                case JADE -> d.append("jade");
                case MAXSCALE -> d.append("maxscale");
                case AKTLIKE_PAIR_REFRATIO -> d.append("aktlike_pair_refratio");
                default -> d.append("[deprecated, index=").append(measure.ordinal()).append("]")
                    .append(", with pp = ").append(Fmt.g(pp));
            }
        } else {
            d.append(", using a ");
            final String nm = " neutralisation measure";
            switch (measure) {
                case GENERAL -> {
                    if (p + q == 1.0) {
                        d.append("standard uij ").append(nm).append(" with alpha = ").append(Fmt.g(2 * p))
                            .append(", omega = ").append(Fmt.g(a));
                    } else {
                        d.append("general case with p = ").append(Fmt.g(p)).append(" q = ").append(Fmt.g(q))
                            .append(" omega = ").append(Fmt.g(a));
                    }
                }
                case SINH_DELTA_R -> d.append("sinh_delta_R").append(nm);
                case DELTA_R -> d.append("delta_R").append(nm);
                case JADE_DELTA_R -> d.append("jade_delta_R").append(nm);
                case MAXSCALE_DELTA_R -> d.append("maxscale_delta_R").append(nm);
                case PHI2_COSHY -> d.append("phi2_coshy").append(nm);
                case COSPHI_COSHY -> d.append("cosphi_coshy").append(nm);
                case AKTLIKE_PAIR_REFRATIO -> d.append("aktlike_pair_refratio").append(nm);
                case AKTLIKE_PAIR_DYNREFRATIO -> d.append("aktlike_pair_dynrefratio").append(nm);
                case JADE -> d.append("jade (without correction factor a)").append(nm);
                case JADEA2 -> d.append("jade (with a = 2)").append(nm);
                case MAXSCALE -> d.append("maxscale");
            }
        }
        d.append(", with modulo_2 = ").append(modulo2 ? 1 : 0);
        d.append(" and recursive = ").append(recursive ? 1 : 0);
        return d.toString();
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        // every input gets a FlavHistory of its own, starting at its place in this sequence
        for (int i = 0; i < cs.nJets(); i++) {
            final PseudoJet jet = cs.pluginNonConstJet(i);
            final int histIndex = jet.clusterHistIndex();
            final FlavInfo start;
            if (jet.hasUserInfo(FlavInfo.class)) {
                start = jet.userInfo(FlavInfo.class);
            } else if (jet.hasUserInfo(FlavHistory.class)) {
                start = jet.userInfo(FlavHistory.class).currentFlavour();
            } else if (flavourFromUserIndex) {
                start = FlavInfo.fromUserIndex(jet);
            } else {
                throw new FastJetException("A PseudoJet being clustered with IFNPlugin had neither FlavInfo nor FlavHistory user_info.");
            }
            final FlavHistory h = new FlavHistory(start, histIndex);
            if (modulo2) h.applyModulo2();
            jet.setUserInfo(h);
        }

        final ClusterSequence local = new ClusterSequence(cs.jets(), jetDef);
        final FlavNeutraliser neutraliser = new FlavNeutraliser(p, q, a, modulo2, measure, useMassFlav, sphericalAlgo,
            pp, recursive);
        final List<PseudoJet> jets = neutraliser.neutralise(local);

        // the neutralised inputs replace ours
        final int n = cs.nJets();
        for (int i = 0; i < n; i++) cs.pluginNonConstJet(i).setUserInfo(jets.get(i).userInfo());

        final List<ClusterSequence.HistoryElement> hist = local.history();
        for (ClusterSequence.HistoryElement h : hist) {
            if (h.parent1() == ClusterSequence.INEXISTENT_PARENT && h.parent2() == ClusterSequence.INEXISTENT_PARENT) {
                continue;
            } else if (h.parent1() >= 0 && h.parent2() == ClusterSequence.BEAM_JET) {
                cs.pluginRecordIBRecombination(hist.get(h.parent1()).jetpIndex(), h.dijDD());
            } else if (h.parent1() >= 0 && h.parent2() >= 0) {
                cs.pluginRecordIJRecombination(hist.get(h.parent1()).jetpIndex(), hist.get(h.parent2()).jetpIndex(),
                    h.dijDD(), jets.get(h.jetpIndex()));
            } else {
                throw new FastJetException("Invalid h.parent1 and h.parent2 combination");
            }
        }
    }
}
