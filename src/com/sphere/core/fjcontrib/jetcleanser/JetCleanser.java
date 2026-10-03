package com.sphere.core.fjcontrib.jetcleanser;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Jet cleansing, fastjet::contrib::JetCleanser (JetCleanser 1.0.1;
 * D. Krohn, M.D. Schwartz, M. Low and L.-T. Wang, Phys. Rev. D 90 (2014)
 * 065020): the jet is cut into subjets and each is rescaled by the fraction
 * of its charged pt that comes from the leading vertex, corrected for the
 * neutral pileup (JVF, linear or gaussian cleansing), then trimmed or
 * filtered. The charged tracks are followed into the subjets as ghosts of
 * pt 1e-60 carrying their origin ({@link #clusterSets}).
 */
public class JetCleanser {

    static {
        ContribCitations.use("jetcleanser");
    }

    /** How the rescaling is worked out. */
    public enum CleansingMode {
        /** gamma0 = gamma1. */
        jvf_cleansing,
        /** gamma0 taken constant. */
        linear_cleansing,
        /** gammas as truncated gaussians, likelihood maximised. */
        gaussian_cleansing
    }

    /** What the input is. */
    public enum InputMode {
        /** Charged and neutral together (calorimeter cells), plus the tracks. */
        input_nc_together,
        /** Neutrals, leading-vertex tracks and pileup tracks apart (particle flow). */
        input_nc_separate
    }

    private static final double JC_ZERO = 1.0e-6;

    private double rsub;
    private double fcut;
    private double nsjmin;
    private final JetDefinition subjetDef;
    private CleansingMode cleansingMode;
    private InputMode inputMode;
    private double linearGamma0Mean;
    private double gaussianGamma0Mean;
    private double gaussianGamma0Width;
    private double gaussianGamma1Mean;
    private double gaussianGamma1Width;

    public JetCleanser(JetDefinition subjetDef, CleansingMode cmode, InputMode imode) {
        this.subjetDef = subjetDef;
        this.rsub = subjetDef.R();
        this.cleansingMode = cmode;
        this.inputMode = imode;
        setDefaults();
    }

    public JetCleanser(double rsub, CleansingMode cmode, InputMode imode) {
        this(new JetDefinition(JetAlgorithm.KT, rsub), cmode, imode);
        this.rsub = rsub;
    }

    private void setDefaults() {
        fcut = 0.0;
        nsjmin = -1;
        linearGamma0Mean = -1;
        gaussianGamma0Mean = -1;
        gaussianGamma1Mean = -1;
        gaussianGamma0Width = -1;
        gaussianGamma1Width = -1;
    }

    public void setGroomingParameters(double fcutIn, int nsjminIn) {
        if (fcutIn < 0 || fcutIn > 1) throw new FastJetException("SetGroomingParameters(): fcut must be >= 0 and <= 1");
        fcut = fcutIn;
        nsjmin = nsjminIn;
    }

    public void setTrimming(double fcutIn) { setGroomingParameters(fcutIn, 0); }
    public void setFiltering(int nsj) { setGroomingParameters(1.0, nsj); }

    public void setLinearParameters(double g0Mean) {
        if (g0Mean < 0 || g0Mean > 1) throw new FastJetException("SetLinearParameters(): g0_mean must be >= 0 and <= 1");
        linearGamma0Mean = g0Mean;
    }

    public void setLinearParameters() { setLinearParameters(0.67); }

    public void setGaussianParameters(double g0Mean, double g1Mean, double g0Width, double g1Width) {
        if (g0Mean < 0 || g0Mean > 1) throw new FastJetException("SetGaussianParameters(): g0_mean must be >= 0 and <= 1");
        if (g1Mean < 0 || g1Mean > 1) throw new FastJetException("SetGaussianParameters(): g1_mean must be >= 0 and <= 1");
        if (g0Width < 0 || g0Width > 1) throw new FastJetException("SetGaussianParameters(): g0_width must be >= 0 and <= 1");
        if (g1Width < 0 || g1Width > 1) throw new FastJetException("SetGaussianParameters(): g1_width must be >= 0 and <= 1");
        gaussianGamma0Mean = g0Mean;
        gaussianGamma1Mean = g1Mean;
        gaussianGamma0Width = g0Width;
        gaussianGamma1Width = g1Width;
    }

    public void setGaussianParameters() { setGaussianParameters(0.67, 0.67, 0.15, 0.25); }

    public String description() {
        final StringBuilder o = new StringBuilder("JetCleanser [");
        o.append(switch (cleansingMode) {
            case jvf_cleansing -> "JVF mode, ";
            case linear_cleansing -> "Linear mode, ";
            case gaussian_cleansing -> "Gaussian mode, ";
        });
        o.append(inputMode == InputMode.input_nc_together ? "input = neutral and charged together]\n"
                                                          : "input = neutral and charged separate]\n");
        if (nsjmin <= 0) o.append(" Trimming: fcut = ").append(Fmt.g(fcut)).append('\n');
        else if (fcut >= 1.0) o.append(" Filtering: nsj = ").append(Fmt.g(nsjmin)).append('\n');
        else o.append(" Trimming + Filtering: fcut = ").append(Fmt.g(fcut)).append(", nsj = ").append(Fmt.g(nsjmin)).append('\n');
        if (cleansingMode == CleansingMode.linear_cleansing) {
            o.append(" g0_mean = ").append(Fmt.g(linearGamma0Mean)).append('\n');
        } else if (cleansingMode == CleansingMode.gaussian_cleansing) {
            o.append(" g0_mean = ").append(Fmt.g(gaussianGamma0Mean)).append(", g0_width = ").append(Fmt.g(gaussianGamma0Width))
                .append(", g1_mean = ").append(Fmt.g(gaussianGamma1Mean)).append(", g1_width = ").append(Fmt.g(gaussianGamma1Width))
                .append('\n');
        }
        return o.toString();
    }

    /** input_nc_together: a plain jet with the leading-vertex and pileup tracks. */
    public PseudoJet result(PseudoJet jet, List<PseudoJet> tracksLv, List<PseudoJet> tracksPu) {
        if (inputMode != InputMode.input_nc_together) throw new FastJetException("result(): This operator is only defined for input_nc_together mode");
        if (!jet.hasConstituents()) return new PseudoJet();
        final List<PseudoJet> constituentsAll = jet.constituents();
        final List<List<PseudoJet>> follow = List.of(constituentsAll, tracksLv, tracksPu);
        final List<List<PseudoJet>> sets = clusterSets(subjetDef, constituentsAll, follow, 0.0);
        final List<PseudoJet> subAll = sets.get(0);
        final List<PseudoJet> subLv = sets.get(1);
        final List<PseudoJet> subPu = sets.get(2);
        List<PseudoJet> rescaled = new ArrayList<>();
        for (int i = 0; i < subAll.size(); i++) {
            final double s = subjetRescalingNcTogether(subAll.get(i).pt(), subLv.get(i).pt(), subPu.get(i).pt());
            final PseudoJet r = rescalePseudoJetConstituents(subAll.get(i), s);
            if (!r.isZero()) rescaled.add(r);
        }
        return groom(PseudoJet.sortedByPt(rescaled), jet.pt());
    }

    /** input_nc_separate: all neutrals, leading-vertex tracks, pileup tracks. */
    public PseudoJet result(List<PseudoJet> neutralsAll, List<PseudoJet> tracksLv, List<PseudoJet> tracksPu, boolean separate) {
        if (inputMode != InputMode.input_nc_separate) throw new FastJetException("result(): This operator is only defined for input_nc_separate mode");
        final List<PseudoJet> all = new ArrayList<>(neutralsAll);
        all.addAll(tracksLv);
        all.addAll(tracksPu);
        final PseudoJet jet = PseudoJet.join(all);
        final List<List<PseudoJet>> follow = List.of(all, neutralsAll, tracksLv, tracksPu);
        final List<List<PseudoJet>> sets = clusterSets(subjetDef, all, follow, 0.0);
        final List<PseudoJet> subAll = sets.get(0);
        final List<PseudoJet> subN = sets.get(1);
        final List<PseudoJet> subLv = sets.get(2);
        final List<PseudoJet> subPu = sets.get(3);
        List<PseudoJet> rescaled = new ArrayList<>();
        for (int i = 0; i < subAll.size(); i++) {
            final double s = subjetRescalingNcSeparate(subN.get(i).pt(), subLv.get(i).pt(), subPu.get(i).pt());
            final PseudoJet ntrl = rescalePseudoJetConstituents(subN.get(i), s);
            final PseudoJet r = PseudoJet.join(ntrl, subLv.get(i));
            if (!r.isZero()) rescaled.add(r);
        }
        return groom(PseudoJet.sortedByPt(rescaled), jet.pt());
    }

    /** The separate-input form, as operator() with three vectors in the C++. */
    public PseudoJet resultSeparate(List<PseudoJet> neutralsAll, List<PseudoJet> tracksLv, List<PseudoJet> tracksPu) {
        return result(neutralsAll, tracksLv, tracksPu, true);
    }

    private PseudoJet groom(List<PseudoJet> rescaled, double jetPt) {
        final List<PseudoJet> trimmed = new ArrayList<>();
        for (int i = 0; i < rescaled.size(); i++) {
            final boolean passFiltering = nsjmin > 0 && i < nsjmin;
            final boolean passTrimming = rescaled.get(i).pt() > fcut * jetPt;
            if (passTrimming || passFiltering) trimmed.add(rescaled.get(i));
        }
        return PseudoJet.join(trimmed);
    }

    /** pt_all raised when the tracks exceed it (by up to 5%). */
    private double checkRescalingValues(double ptAll, double ptcLv, double ptcPu) {
        final double ratio = (ptcLv + ptcPu) / ptAll;
        if (ratio > 1.05) throw new FastJetException("_CheckRescalingValues: ptc_lv + ptc_pu is more than 5% larger than pt_all");
        return ratio > 1.0 ? ptAll * ratio : ptAll;
    }

    double subjetRescalingNcTogether(double ptAll, double ptcLv, double ptcPu) {
        double scale;
        switch (cleansingMode) {
            case jvf_cleansing -> scale = ptcLv > JC_ZERO ? ptcLv / (ptcLv + ptcPu) : 0.0;
            case linear_cleansing -> {
                if (linearGamma0Mean < 0) throw new FastJetException("Linear cleansing parameters have not been set yet.");
                ptAll = checkRescalingValues(ptAll, ptcLv, ptcPu);
                if (ptcPu > JC_ZERO && ptcPu / (ptAll - ptcLv) > linearGamma0Mean) {
                    scale = ptcLv > JC_ZERO ? ptcLv / (ptcLv + ptcPu) : 0.0;
                } else {
                    scale = ptcLv > JC_ZERO ? 1.0 - (1.0 / linearGamma0Mean) * ptcPu / ptAll : 0.0;
                }
            }
            default -> {
                requireGaussian();
                ptAll = checkRescalingValues(ptAll, ptcLv, ptcPu);
                final double g0 = gaussianGetMinimizedGamma0(ptAll, ptcLv, ptcPu);
                scale = ptcLv > JC_ZERO ? 1.0 - (1.0 / g0) * ptcPu / ptAll : 0.0;
            }
        }
        return scale > JC_ZERO ? scale : 0.0;
    }

    double subjetRescalingNcSeparate(double ptnAll, double ptcLv, double ptcPu) {
        double scale;
        switch (cleansingMode) {
            case jvf_cleansing -> scale = ptcLv > JC_ZERO && ptnAll > JC_ZERO ? ptcLv / (ptcLv + ptcPu) : 0.0;
            case linear_cleansing -> {
                if (linearGamma0Mean < 0) throw new FastJetException("Linear cleansing parameters have not been set yet.");
                double ptAll = ptnAll + ptcLv + ptcPu;
                ptAll = checkRescalingValues(ptAll, ptcLv, ptcPu);
                if ((ptcPu > JC_ZERO && ptcPu / (ptAll - ptcLv) > linearGamma0Mean) || ptnAll < JC_ZERO) {
                    scale = ptcLv > JC_ZERO && ptnAll > JC_ZERO ? ptcLv / (ptcLv + ptcPu) : 0.0;
                } else {
                    scale = ptcLv > JC_ZERO && ptnAll > JC_ZERO ? 1.0 - (1.0 / linearGamma0Mean - 1.0) * ptcPu / ptnAll : 0.0;
                }
            }
            default -> {
                requireGaussian();
                double ptAll = ptnAll + ptcLv + ptcPu;
                ptAll = checkRescalingValues(ptAll, ptcLv, ptcPu);
                final double g0 = gaussianGetMinimizedGamma0(ptAll, ptcLv, ptcPu);
                scale = ptcLv > JC_ZERO && ptnAll > JC_ZERO ? 1.0 - (1.0 / g0 - 1.0) * ptcPu / ptnAll : 0.0;
            }
        }
        return scale > JC_ZERO ? scale : 0.0;
    }

    private void requireGaussian() {
        if (gaussianGamma0Mean < 0 || gaussianGamma1Mean < 0 || gaussianGamma0Width < 0 || gaussianGamma1Width < 0) {
            throw new FastJetException("Gaussian cleansing parameters have not been set yet.");
        }
    }

    /** The gamma0 of largest likelihood on a grid of 0.01 (a std::map keyed by the function, as the C++). */
    private double gaussianGetMinimizedGamma0(double ptAll, double ptcLv, double ptcPu) {
        if (ptAll == 0.0 && ptcLv == 0.0 && ptcPu == 0.0) return 0.0;
        if (ptcLv == 0.0) return ptcPu / ptAll;
        final TreeMap<Double, Double> map = new TreeMap<>((a, b) -> a < b ? -1 : (b < a ? 1 : 0));
        for (double x0 = 0.0; x0 <= 1.0 + JC_ZERO; x0 += 0.01) {
            map.put(gaussianFunction(x0, ptcLv, ptcPu, ptAll), x0);
        }
        return map.firstEntry().getValue();
    }

    private double gaussianGetGamma1(double gamma0, double ptAll, double ptcLv, double ptcPu) {
        if (ptAll == 0.0 && ptcLv == 0.0 && ptcPu == 0.0) return 0.0;
        if (gamma0 == 0.0 || Math.abs(ptAll - ptcPu / gamma0) < JC_ZERO) return 0.0;
        return ptcLv / (ptAll - ptcPu / gamma0);
    }

    private double gaussianFunction(double x, double ptcLv, double ptcPu, double ptAll) {
        final double g1 = gaussianGetGamma1(x, ptAll, ptcLv, ptcPu);
        if (g1 >= 1. || g1 <= 0. || x <= 0. || x >= 1.) return (x - 1.) * (x - 1.) + 10.;
        return -CRMath.exp(-(g1 - gaussianGamma1Mean) * (g1 - gaussianGamma1Mean) / 2. / gaussianGamma1Width / gaussianGamma1Width
            - (x - gaussianGamma0Mean) * (x - gaussianGamma0Mean) / 2. / gaussianGamma0Width / gaussianGamma0Width);
    }

    /* ------------------------------------------------------------------ */
    /* The helpers of the contrib                                          */
    /* ------------------------------------------------------------------ */

    /** Which follow set and which element a ghost of clusterSets stands for, FollowSetGhostInfo. */
    public record FollowSetGhostInfo(int setId, int indId) {
    }

    /**
     * Clusters cluster_set with every follow set added as ghosts of pt
     * 1e-60, and returns, for each follow set, the (composite) jet of its
     * members that ended in each jet, in the order of the jets by pt.
     */
    public static List<List<PseudoJet>> clusterSets(JetDefinition jetDef, List<PseudoJet> clusterSet,
                                                    List<List<PseudoJet>> followSets, double ptmin) {
        final List<PseudoJet> fullSet = new ArrayList<>(clusterSet);
        for (int i = 0; i < followSets.size(); i++) {
            final List<PseudoJet> current = followSets.get(i);
            for (int j = 0; j < current.size(); j++) {
                final PseudoJet ghost = current.get(j).times(1.0e-60);
                ghost.setUserInfo(new FollowSetGhostInfo(i, j));
                fullSet.add(ghost);
            }
        }
        final List<PseudoJet> jets = PseudoJet.sortedByPt(new ClusterSequence(fullSet, jetDef).inclusiveJets(ptmin));
        final List<List<PseudoJet>> followJets = new ArrayList<>();
        for (int i = 0; i < followSets.size(); i++) {
            final List<PseudoJet> current = new ArrayList<>();
            for (int j = 0; j < jets.size(); j++) current.add(PseudoJet.join(new PseudoJet()));
            followJets.add(current);
        }
        for (int i = 0; i < jets.size(); i++) {
            for (PseudoJet c : jets.get(i).constituents()) {
                if (c.userInfo() instanceof FollowSetGhostInfo info) {
                    final List<PseudoJet> current = followSets.get(info.setId());
                    final PseudoJet existing = followJets.get(info.setId()).get(i);
                    followJets.get(info.setId()).set(i, existing.isZero()
                        ? PseudoJet.join(current.get(info.indId()))
                        : PseudoJet.join(existing, current.get(info.indId())));
                }
            }
        }
        return followJets;
    }

    public static List<PseudoJet> rescalePseudoJetVector(List<PseudoJet> jets, double sFactor) {
        final List<PseudoJet> out = new ArrayList<>();
        if (sFactor == 0.0) return out;
        for (PseudoJet j : jets) out.add(j.times(sFactor));
        return out;
    }

    public static PseudoJet rescalePseudoJetConstituents(PseudoJet jet, double sFactor) {
        if (!jet.hasConstituents()) return new PseudoJet();
        return PseudoJet.join(rescalePseudoJetVector(jet.constituents(), sFactor));
    }
}
