package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.Sphere;
import com.sphere.core.fastjet.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * siscone::Csiscone, SISCone 3.1.3 (G.P. Salam, G. Soyez, JHEP 05 (2007)
 * 086): passes of seedless stable-cone search on the particles not yet in a
 * stable cone, followed by the split-merge; or, in progressive-removal mode,
 * the hardest stable cone of each pass taken as a jet.
 */
public final class Siscone extends StableCones {

    public static final String VERSION = Sphere.SISCONE_VERSION;
    public static final String PACKAGE_NAME = "SISCone";

    private static boolean initDone;
    private static Consumer<String> bannerSink;

    final SplitMerge sm = new SplitMerge();
    /** The protocones of each pass. */
    public final List<List<Cmomentum>> protoconesList = new ArrayList<>();
    private boolean rerunAllowed;

    public static synchronized void setBannerSink(Consumer<String> sink) {
        bannerSink = sink;
    }

    public List<SplitMerge.Cjet> jets() {
        return sm.jets;
    }

    public double mostAmbiguousSplit() {
        return sm.mostAmbiguousSplit;
    }

    public void setSmVar2HardestCutOff(double v) {
        sm.smVar2HardestCutOff = v;
    }

    public void setStableConeSoftPt2Cutoff(double v) {
        sm.stableConeSoftPt2Cutoff = v;
    }

    public void setPtWeightedSplitting(boolean b) {
        sm.setPtWeightedSplitting(b);
    }

    public void setUserScale(SplitMerge.UserScale s) {
        sm.setUserScale(s);
    }

    public int computeJets(List<Cmomentum> particles, double radius, double f, int nPassMax, double ptmin,
                           SplitMerge.Scale scale) {
        initialiseIfNeeded();
        checkRadius(radius);
        sm.splitMergeScale = scale;
        sm.partialClear();
        sm.initParticles(particles);
        boolean finished = false;
        rerunAllowed = false;
        protoconesList.clear();
        do {
            init(sm.pUncolHard);
            if (getStableCones(radius) != 0) {
                sm.addProtocones(protocones, R2, ptmin);
                final List<Cmomentum> copy = new ArrayList<>(protocones.size());
                for (Cmomentum c : protocones) copy.add(c.copy());
                protoconesList.add(copy);
            } else {
                finished = true;
            }
            nPassMax--;
        } while (!finished && sm.nLeft > 0 && nPassMax != 0);
        rerunAllowed = true;
        return sm.perform(f, ptmin);
    }

    public int computeJetsProgressiveRemoval(List<Cmomentum> particles, double radius, int nPassMax, double ptmin,
                                             SplitMerge.Scale scale) {
        initialiseIfNeeded();
        checkRadius(radius);
        sm.splitMergeScale = scale;
        sm.partialClear();
        sm.initParticles(particles);
        sm.jets.clear();
        boolean unclusteredLeft;
        rerunAllowed = false;
        protoconesList.clear();
        do {
            init(sm.pUncolHard);
            unclusteredLeft = getStableCones(radius) != 0;
            if (sm.addHardestProtoconeToJets(protocones, R2, ptmin) != 0) break;
            nPassMax--;
        } while (unclusteredLeft && sm.nLeft > 0 && nPassMax != 0);
        return sm.jets.size();
    }

    /** Runs the split-merge again on the stable cones already found. */
    public int recomputeJets(double f, double ptmin, SplitMerge.Scale scale) {
        if (!rerunAllowed) return -1;
        sm.splitMergeScale = scale;
        sm.partialClear();
        sm.initPleft();
        for (List<Cmomentum> pcs : protoconesList) sm.addProtocones(pcs, R2, ptmin);
        return sm.perform(f, ptmin);
    }

    private static void checkRadius(double radius) {
        if (radius <= 0.0 || radius >= 0.5 * Geom.M_PI) {
            throw new IllegalArgumentException("Illegal value for cone radius, R = " + Fmt.g(radius)
                + " (legal values are 0<R<pi/2)");
        }
    }

    static synchronized void initialiseIfNeeded() {
        if (initDone) return;
        Ranlux.init();
        initDone = true;
        if (bannerSink != null) {
            bannerSink.accept(String.join("\n",
                "#ooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo",
                "#                    SISCone   version " + String.format("%-28s", VERSION) + "o",
                "#              http://projects.hepforge.org/siscone                o",
                "#                                                                  o",
                "# This is SISCone: the Seedless Infrared Safe Cone Jet Algorithm   o",
                "# SISCone was written by Gavin Salam and Gregory Soyez             o",
                "# It is released under the terms of the GNU General Public License o",
                "#                                                                  o",
                "# A description of the algorithm is available in the publication   o",
                "# JHEP 05 (2007) 086 [arXiv:0704.0292 (hep-ph)].                   o",
                "# Please cite it if you use SISCone.                               o",
                "#ooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo",
                ""));
        }
    }
}
