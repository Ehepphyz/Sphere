package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.siscone.Cmomentum;
import com.sphere.core.fastjet.plugins.siscone.SisconeAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::SISConePlugin, the Seedless Infrared-Safe Cone (G.P. Salam,
 * G. Soyez, JHEP 05 (2007) 086), SISCone 3.1.3 ported to Java: all stable
 * cones of radius R are found exactly (no seeds), in passes over the
 * particles left out of the previous ones, then split or merged at overlap
 * threshold f in the chosen scale (pttilde by default).
 *
 * The port keeps SISCone's arithmetic and orderings (its random references,
 * hash, and libstdc++'s sort and red-black multiset replayed), so that its
 * jets are the C++ ones; under {@link Precision#DD} the four-momenta summed
 * in the ClusterSequence carry 106 bits.
 */
public final class SISConePlugin extends SISConeBasePlugin {

    public enum SplitMergeScale {
        SM_PT, SM_ET, SM_MT, SM_PTTILDE
    }

    private double protojetPtmin;
    private SplitMergeScale splitMergeScale;
    private boolean usePtWeightedSplitting;

    // the cache of the last clustering, as SISConePlugin's static members
    private static SISConePlugin storedPlugin;
    private static List<PseudoJet> storedParticles;
    private static SisconeAccess storedSiscone;

    public SISConePlugin(double coneRadius, double overlapThreshold) {
        this(coneRadius, overlapThreshold, 0, 0.0, false, SplitMergeScale.SM_PTTILDE, 0.0);
    }

    public SISConePlugin(double coneRadius, double overlapThreshold, int nPassMax, double protojetPtmin,
                         boolean caching) {
        this(coneRadius, overlapThreshold, nPassMax, protojetPtmin, caching, SplitMergeScale.SM_PTTILDE, 0.0);
    }

    public SISConePlugin(double coneRadius, double overlapThreshold, int nPassMax, double protojetPtmin,
                         boolean caching, SplitMergeScale splitMergeScale, double splitMergeStoppingScale) {
        this.coneRadius = coneRadius;
        this.overlapThreshold = overlapThreshold;
        this.nPassMax = nPassMax;
        this.protojetPtmin = protojetPtmin;
        this.caching = caching;
        this.splitMergeScale = splitMergeScale;
        this.splitMergeStoppingScale = splitMergeStoppingScale;
        this.ghostSepScale = 0.0;
        this.usePtWeightedSplitting = false;
    }

    private SISConePlugin(SISConePlugin o) {
        this(o.coneRadius, o.overlapThreshold, o.nPassMax, o.protojetPtmin, o.caching, o.splitMergeScale,
             o.splitMergeStoppingScale);
        this.ghostSepScale = o.ghostSepScale;
        this.usePtWeightedSplitting = o.usePtWeightedSplitting;
        this.useJetDefRecombiner = o.useJetDefRecombiner;
        this.progressiveRemoval = o.progressiveRemoval;
        this.userScale = o.userScale;
    }

    public double protojetPtmin() {
        return protojetPtmin;
    }

    public double protojetOrGhostPtmin() {
        return (protojetPtmin < ghostSepScale) ? ghostSepScale : protojetPtmin;
    }

    public SplitMergeScale splitMergeScale() {
        return splitMergeScale;
    }

    public void setSplitMergeScale(SplitMergeScale sms) {
        splitMergeScale = sms;
    }

    public boolean splitMergeOnTransverseMass() {
        return splitMergeScale == SplitMergeScale.SM_MT;
    }

    public void setSplitMergeOnTransverseMass(boolean val) {
        splitMergeScale = val ? SplitMergeScale.SM_MT : SplitMergeScale.SM_PT;
    }

    public boolean splitMergeUsePtWeightedSplitting() {
        return usePtWeightedSplitting;
    }

    public void setSplitMergeUsePtWeightedSplitting(boolean val) {
        usePtWeightedSplitting = val;
    }

    @Override
    public String description() {
        final StringBuilder desc = new StringBuilder("SISCone jet algorithm with ");
        desc.append("cone_radius = ").append(Fmt.g(coneRadius)).append(", ");
        if (progressiveRemoval) desc.append("progressive-removal mode, ");
        else desc.append("overlap_threshold = ").append(Fmt.g(overlapThreshold)).append(", ");
        desc.append("n_pass_max = ").append(nPassMax).append(", ");
        desc.append("protojet_ptmin = ").append(Fmt.g(protojetPtmin)).append(", ");
        if (progressiveRemoval && userScale != null) {
            desc.append("using a user-defined scale for ordering of stable cones");
            final String d = userScale.description();
            if (!d.isEmpty()) desc.append(" (").append(d).append(")");
        } else {
            desc.append("split-merge uses ").append(SisconeAccess.scaleName(splitMergeScale.ordinal()));
        }
        if (!progressiveRemoval) {
            desc.append(", caching turned ").append(caching ? "on" : "off");
            desc.append(", SM stop scale = ").append(Fmt.g(splitMergeStoppingScale));
        }
        if (usePtWeightedSplitting) desc.append(", using pt-weighted splitting");
        if (useJetDefRecombiner) desc.append(", using jet-definition's own recombiner");
        desc.append(", SISCone code v").append(SisconeAccess.VERSION);
        return desc.toString();
    }

    private static boolean sameMomentum(PseudoJet a, PseudoJet b) {
        return a.px() == b.px() && a.py() == b.py() && a.pz() == b.pz() && a.E() == b.E();
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        SisconeAccess.setBannerSink(Citations::plugin);
        final int n = cs.nJets();
        final List<PseudoJet> jetsIn = cs.jets();
        final SisconeAccess siscone;
        boolean newSiscone = true;
        synchronized (SISConePlugin.class) {
            if (caching && !progressiveRemoval) {
                if (storedSiscone != null) {
                    newSiscone = !(storedPlugin.coneRadius == coneRadius && storedPlugin.nPassMax == nPassMax
                                   && storedParticles.size() == n);
                    if (!newSiscone) {
                        for (int i = 0; i < n; i++) newSiscone |= !sameMomentum(jetsIn.get(i), storedParticles.get(i));
                    }
                }
                if (newSiscone) {
                    storedSiscone = new SisconeAccess();
                    storedParticles = jetsIn;
                    storedPlugin = new SISConePlugin(this);
                }
                siscone = storedSiscone;
            } else {
                siscone = new SisconeAccess();
            }
        }
        siscone.setSmVar2HardestCutOff(splitMergeStoppingScale * splitMergeStoppingScale);
        siscone.setStableConeSoftPt2Cutoff(ghostSeparationScale() * ghostSeparationScale());
        siscone.setPtWeightedSplitting(usePtWeightedSplitting);
        final int scale = splitMergeScale.ordinal();
        if (newSiscone) {
            final List<Cmomentum> momenta = new ArrayList<>(n);
            for (PseudoJet p : jetsIn) momenta.add(new Cmomentum(p.px(), p.py(), p.pz(), p.E()));
            if (progressiveRemoval) {
                if (userScale != null) siscone.setUserScale(cs, userScale);
                siscone.computeJetsProgressiveRemoval(momenta, coneRadius, nPassMax, protojetOrGhostPtmin(), scale);
            } else {
                siscone.computeJets(momenta, coneRadius, overlapThreshold, nPassMax, protojetOrGhostPtmin(), scale);
            }
        } else {
            siscone.recomputeJets(overlapThreshold, protojetOrGhostPtmin(), scale);
        }
        recordJets(cs, siscone, useJetDefRecombiner, this);
    }

    /** Records the SISCone jets into the ClusterSequence, as both plugins do. */
    static void recordJets(ClusterSequence cs, SisconeAccess siscone, boolean useJetDefRecombiner,
                           SISConeBasePlugin plugin) {
        final boolean dd = cs.precision() == Precision.DD;
        final int n = cs.nJets();
        final Extras extras = new Extras(n);
        final int njet = siscone.njets();
        for (int ijet = njet - 1; ijet >= 0; ijet--) {
            final List<Integer> contents = siscone.contents(ijet);
            if (contents.isEmpty()) continue;
            int jetK = contents.get(0);
            for (int ip = 1; ip < contents.size(); ip++) {
                final int jetI = jetK;
                final int jetJ = contents.get(ip);
                if (useJetDefRecombiner) {
                    jetK = cs.pluginRecordIJRecombination(jetI, jetJ, 0.0);
                } else {
                    final PseudoJet newjet = cs.jet(jetI).plus(cs.jet(jetJ));
                    jetK = cs.pluginRecordIJRecombination(jetI, jetJ, 0.0, newjet);
                }
            }
            final PseudoJet j = cs.jet(jetK);
            if (dd) {
                cs.pluginRecordIBRecombination(jetK, j.kt2DD());
            } else {
                cs.pluginRecordIBRecombination(jetK, j.perp2());
            }
            extras.pass[cs.jet(jetK).clusterHistIndex()] = siscone.pass(ijet);
        }
        final List<List<double[]>> pcs = siscone.protocones();
        for (int ipass = 0; ipass < pcs.size(); ipass++) {
            for (double[] p : pcs.get(ipass)) {
                final PseudoJet protocone = new PseudoJet(p[0], p[1], p[2], p[3]);
                protocone.setUserIndex(ipass);
                extras.protocones.add(protocone);
            }
        }
        extras.mostAmbiguousSplit = siscone.mostAmbiguousSplit();
        extras.plugin = plugin;
        cs.pluginAssociateExtras(extras);
    }
}
