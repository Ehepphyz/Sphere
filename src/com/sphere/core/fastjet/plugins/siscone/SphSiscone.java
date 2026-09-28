package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.SISConeBasePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * siscone_spherical::CSphsiscone, the spherical SISCone (angular cones on
 * the sphere, energies for the split-merge), for e+e- events; and what the
 * FastJet SISConeSphericalPlugin needs of it.
 */
public final class SphSiscone extends SphStableCones {

    private static boolean initDone;
    private static BiConsumer<String, String> bannerSink;

    private final SphSplitMerge sm = new SphSplitMerge();
    final List<List<SphMomentum>> protoconesList = new ArrayList<>();
    private boolean rerunAllowed;

    public static synchronized void setBannerSink(BiConsumer<String, String> sink) {
        bannerSink = sink;
    }

    public static String scaleName(int ordinal) {
        return SphSplitMerge.Scale.values()[ordinal].label();
    }

    public void setSmVar2HardestCutOff(double v) {
        sm.smVar2HardestCutOff = v;
    }

    public void setStableConeSoftE2Cutoff(double v) {
        sm.stableConeSoftE2Cutoff = v;
    }

    public void setEWeightedSplitting(boolean b) {
        sm.setEWeightedSplitting(b);
    }

    public void setUserScale(ClusterSequence cs, SISConeBasePlugin.UserScale scale) {
        sm.setUserScale(new SphSplitMerge.UserScale() {
            @Override
            public double scale(SphSplitMerge.Jet jet) {
                return scale.result(pj(jet));
            }

            @Override
            public boolean isLarger(SphSplitMerge.Jet a, SphSplitMerge.Jet b) {
                return scale.isLarger(pj(a), pj(b));
            }

            private PseudoJet pj(SphSplitMerge.Jet jet) {
                final PseudoJet j = new PseudoJet(jet.v.px, jet.v.py, jet.v.pz, jet.v.E);
                j.setStructure(new SISConeBasePlugin.StableConeStructure(cs, new ArrayList<>(jet.contents), jet.smVar2));
                return j;
            }
        });
    }

    private static void checkRadius(double radius) {
        if (radius <= 0.0 || radius >= 0.5 * Geom.M_PI) {
            throw new IllegalArgumentException("Illegal value for cone radius, R = " + Fmt.g(radius)
                + " (legal values are 0<R<pi/2)");
        }
    }

    public int computeJets(List<SphMomentum> particles, double radius, double f, int nPassMax, double emin, int scale) {
        initialiseIfNeeded();
        checkRadius(radius);
        sm.splitMergeScale = SphSplitMerge.Scale.values()[scale];
        sm.partialClear();
        sm.initParticles(particles);
        boolean finished = false;
        rerunAllowed = false;
        protoconesList.clear();
        do {
            init(sm.pUncolHard);
            if (getStableCones(radius) != 0) {
                // stored before the split-merge gives them their contents, as the C++ does
                final List<SphMomentum> copy = new ArrayList<>(protocones.size());
                for (SphMomentum c : protocones) copy.add(c.copy());
                protoconesList.add(copy);
                sm.addProtocones(protocones, R2, emin);
            } else {
                finished = true;
            }
            nPassMax--;
        } while (!finished && sm.nLeft > 0 && nPassMax != 0);
        rerunAllowed = true;
        return sm.perform(f, emin);
    }

    public int computeJetsProgressiveRemoval(List<SphMomentum> particles, double radius, int nPassMax, double emin,
                                             int scale) {
        initialiseIfNeeded();
        checkRadius(radius);
        sm.splitMergeScale = SphSplitMerge.Scale.values()[scale];
        sm.partialClear();
        sm.initParticles(particles);
        sm.jets.clear();
        boolean unclusteredLeft;
        rerunAllowed = false;
        protoconesList.clear();
        do {
            init(sm.pUncolHard);
            unclusteredLeft = getStableCones(radius) != 0;
            if (sm.addHardestProtoconeToJets(protocones, R2, emin) != 0) break;
            nPassMax--;
        } while (unclusteredLeft && sm.nLeft > 0 && nPassMax != 0);
        return sm.jets.size();
    }

    public int recomputeJets(double f, double emin, int scale) {
        if (!rerunAllowed) return -1;
        sm.splitMergeScale = SphSplitMerge.Scale.values()[scale];
        sm.partialClear();
        sm.initPleft();
        for (List<SphMomentum> pcs : protoconesList) sm.addProtocones(pcs, R2, emin);
        return sm.perform(f, emin);
    }

    public int njets() {
        return sm.jets.size();
    }

    public List<Integer> contents(int ijet) {
        return sm.jets.get(ijet).contents;
    }

    public int pass(int ijet) {
        return sm.jets.get(ijet).pass;
    }

    public double mostAmbiguousSplit() {
        return sm.mostAmbiguousSplit;
    }

    public List<List<double[]>> protoconesAsArrays() {
        final List<List<double[]>> out = new ArrayList<>();
        for (List<SphMomentum> pass : protoconesList) {
            final List<double[]> l = new ArrayList<>(pass.size());
            for (SphMomentum c : pass) l.add(new double[]{c.px, c.py, c.pz, c.E});
            out.add(l);
        }
        return out;
    }

    private static synchronized void initialiseIfNeeded() {
        if (initDone) return;
        Ranlux.init();
        initDone = true;
        if (bannerSink != null) {
            bannerSink.accept("SISConeSpherical", String.join("\n",
                "#ooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo",
                "#                    SISCone   version " + String.format("%-28s", Siscone.VERSION) + "o",
                "#              http://projects.hepforge.org/siscone                o",
                "#                                                                  o",
                "# This is SISCone: the Seedless Infrared Safe Cone Jet Algorithm   o",
                "# SISCone was written by Gavin Salam and Gregory Soyez             o",
                "# It is released under the terms of the GNU General Public License o",
                "#                                                                  o",
                "#            !!!             WARNING            !!!                o",
                "#    This is the version of SISCone using spherical coordinates    o",
                "#                                                                  o",
                "# A description of the algorithm is available in the publication   o",
                "# JHEP 05 (2007) 086 [arXiv:0704.0292 (hep-ph)].                   o",
                "# Please cite it if you use SISCone.                               o",
                "#ooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo",
                ""));
        }
    }
}
