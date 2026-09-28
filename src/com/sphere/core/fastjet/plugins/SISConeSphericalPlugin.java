package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.siscone.Siscone;
import com.sphere.core.fastjet.plugins.siscone.SphMomentum;
import com.sphere.core.fastjet.plugins.siscone.SphSiscone;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::SISConeSphericalPlugin: SISCone with cones of angular radius R on
 * the sphere and a split-merge in energy (Etilde by default), for e+e-
 * collisions.
 */
public final class SISConeSphericalPlugin extends SISConeBasePlugin {

    public enum SplitMergeScale {
        SM_E, SM_ETILDE
    }

    private double protojetEmin;
    private SplitMergeScale splitMergeScale;
    private boolean useEWeightedSplitting;

    private static SISConeSphericalPlugin storedPlugin;
    private static List<PseudoJet> storedParticles;
    private static SphSiscone storedSiscone;

    public SISConeSphericalPlugin(double coneRadius, double overlapThreshold) {
        this(coneRadius, overlapThreshold, 0, 0.0, false, SplitMergeScale.SM_ETILDE, 0.0);
    }

    public SISConeSphericalPlugin(double coneRadius, double overlapThreshold, int nPassMax, double protojetEmin) {
        this(coneRadius, overlapThreshold, nPassMax, protojetEmin, false, SplitMergeScale.SM_ETILDE, 0.0);
    }

    public SISConeSphericalPlugin(double coneRadius, double overlapThreshold, int nPassMax, double protojetEmin,
                                  boolean caching, SplitMergeScale splitMergeScale, double splitMergeStoppingScale) {
        this.coneRadius = coneRadius;
        this.overlapThreshold = overlapThreshold;
        this.nPassMax = nPassMax;
        this.protojetEmin = protojetEmin;
        this.caching = caching;
        this.splitMergeScale = splitMergeScale;
        this.splitMergeStoppingScale = splitMergeStoppingScale;
        this.ghostSepScale = 0.0;
        this.useEWeightedSplitting = false;
    }

    private SISConeSphericalPlugin(SISConeSphericalPlugin o) {
        this(o.coneRadius, o.overlapThreshold, o.nPassMax, o.protojetEmin, o.caching, o.splitMergeScale,
             o.splitMergeStoppingScale);
        this.ghostSepScale = o.ghostSepScale;
        this.useEWeightedSplitting = o.useEWeightedSplitting;
        this.useJetDefRecombiner = o.useJetDefRecombiner;
        this.progressiveRemoval = o.progressiveRemoval;
        this.userScale = o.userScale;
    }

    public double protojetEmin() {
        return protojetEmin;
    }

    public double protojetOrGhostEmin() {
        return (protojetEmin < ghostSepScale) ? ghostSepScale : protojetEmin;
    }

    public SplitMergeScale splitMergeScale() {
        return splitMergeScale;
    }

    public void setSplitMergeScale(SplitMergeScale sms) {
        splitMergeScale = sms;
    }

    public boolean splitMergeUseEWeightedSplitting() {
        return useEWeightedSplitting;
    }

    public void setSplitMergeUseEWeightedSplitting(boolean val) {
        useEWeightedSplitting = val;
    }

    @Override
    public boolean isSpherical() {
        return true;
    }

    @Override
    public String description() {
        final StringBuilder desc = new StringBuilder("Spherical SISCone jet algorithm with ");
        desc.append("cone_radius = ").append(Fmt.g(coneRadius)).append(", ");
        if (progressiveRemoval) desc.append("progressive-removal mode, ");
        else desc.append("overlap_threshold = ").append(Fmt.g(overlapThreshold)).append(", ");
        desc.append("n_pass_max = ").append(nPassMax).append(", ");
        desc.append("protojet_Emin = ").append(Fmt.g(protojetEmin)).append(", ");
        if (progressiveRemoval && userScale != null) {
            desc.append("using a user-defined scale for ordering of stable cones");
            final String d = userScale.description();
            if (!d.isEmpty()) desc.append(" (").append(d).append(")");
        } else {
            desc.append("split-merge uses ").append(SphSiscone.scaleName(splitMergeScale.ordinal()));
        }
        if (!progressiveRemoval) {
            // (no separator before "caching", as in FastJet)
            desc.append("caching turned ").append(caching ? "on" : "off");
            desc.append(", SM stop scale = ").append(Fmt.g(splitMergeStoppingScale));
        }
        if (useEWeightedSplitting) desc.append(", using E-weighted splitting");
        if (useJetDefRecombiner) desc.append(", using jet-definition's own recombiner");
        desc.append(", SISCone code v").append(Siscone.VERSION);
        return desc.toString();
    }

    private static boolean sameMomentum(PseudoJet a, PseudoJet b) {
        return a.px() == b.px() && a.py() == b.py() && a.pz() == b.pz() && a.E() == b.E();
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        SphSiscone.setBannerSink(Citations::plugin);
        final int n = cs.nJets();
        final List<PseudoJet> jetsIn = cs.jets();
        final SphSiscone siscone;
        boolean newSiscone = true;
        synchronized (SISConeSphericalPlugin.class) {
            if (caching && !progressiveRemoval) {
                if (storedSiscone != null) {
                    newSiscone = !(storedPlugin.coneRadius == coneRadius && storedPlugin.nPassMax == nPassMax
                                   && storedParticles.size() == n);
                    if (!newSiscone) {
                        for (int i = 0; i < n; i++) newSiscone |= !sameMomentum(jetsIn.get(i), storedParticles.get(i));
                    }
                }
                if (newSiscone) {
                    storedSiscone = new SphSiscone();
                    storedParticles = jetsIn;
                    storedPlugin = new SISConeSphericalPlugin(this);
                }
                siscone = storedSiscone;
            } else {
                siscone = new SphSiscone();
            }
        }
        siscone.setSmVar2HardestCutOff(splitMergeStoppingScale * splitMergeStoppingScale);
        siscone.setStableConeSoftE2Cutoff(ghostSeparationScale() * ghostSeparationScale());
        siscone.setEWeightedSplitting(useEWeightedSplitting);
        final int scale = splitMergeScale.ordinal();
        if (newSiscone) {
            final List<SphMomentum> momenta = new ArrayList<>(n);
            for (PseudoJet p : jetsIn) momenta.add(new SphMomentum(p.px(), p.py(), p.pz(), p.E()));
            if (progressiveRemoval) {
                if (userScale != null) siscone.setUserScale(cs, userScale);
                siscone.computeJetsProgressiveRemoval(momenta, coneRadius, nPassMax, protojetOrGhostEmin(), scale);
            } else {
                siscone.computeJets(momenta, coneRadius, overlapThreshold, nPassMax, protojetOrGhostEmin(), scale);
            }
        } else {
            siscone.recomputeJets(overlapThreshold, protojetOrGhostEmin(), scale);
        }

        final boolean dd = cs.precision() == Precision.DD;
        final Extras extras = new Extras(n);
        for (int ijet = siscone.njets() - 1; ijet >= 0; ijet--) {
            final List<Integer> contents = siscone.contents(ijet);
            if (contents.isEmpty()) continue;
            int jetK = contents.get(0);
            for (int ip = 1; ip < contents.size(); ip++) {
                final int jetI = jetK;
                final int jetJ = contents.get(ip);
                if (useJetDefRecombiner) {
                    jetK = cs.pluginRecordIJRecombination(jetI, jetJ, 0.0);
                } else {
                    jetK = cs.pluginRecordIJRecombination(jetI, jetJ, 0.0, cs.jet(jetI).plus(cs.jet(jetJ)));
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
        final List<List<double[]>> pcs = siscone.protoconesAsArrays();
        for (int ipass = 0; ipass < pcs.size(); ipass++) {
            for (double[] p : pcs.get(ipass)) {
                final PseudoJet protocone = new PseudoJet(p[0], p[1], p[2], p[3]);
                protocone.setUserIndex(ipass);
                extras.protocones.add(protocone);
            }
        }
        extras.mostAmbiguousSplit = siscone.mostAmbiguousSplit();
        extras.plugin = this;
        cs.pluginAssociateExtras(extras);
    }
}
