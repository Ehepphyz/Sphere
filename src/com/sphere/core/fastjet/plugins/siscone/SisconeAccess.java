package com.sphere.core.fastjet.plugins.siscone;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.SISConeBasePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** What the FastJet SISConePlugin needs of the planar SISCone. */
public final class SisconeAccess {

    public static final String VERSION = Siscone.VERSION;

    private final Siscone s = new Siscone();

    /** Where SISCone's banner goes, as (key, text). */
    public static void setBannerSink(BiConsumer<String, String> sink) {
        Siscone.setBannerSink(text -> sink.accept("SISCone", text));
    }

    public static String scaleName(int ordinal) {
        return SplitMerge.Scale.values()[ordinal].label();
    }

    public void setSmVar2HardestCutOff(double v) {
        s.setSmVar2HardestCutOff(v);
    }

    public void setStableConeSoftPt2Cutoff(double v) {
        s.setStableConeSoftPt2Cutoff(v);
    }

    public void setPtWeightedSplitting(boolean b) {
        s.setPtWeightedSplitting(b);
    }

    /** The stable cones ordered by a FastJet user scale, as SISConeUserScale does. */
    public void setUserScale(ClusterSequence cs, SISConeBasePlugin.UserScale scale) {
        s.setUserScale(new SplitMerge.UserScale() {
            @Override
            public double scale(SplitMerge.Cjet jet) {
                return scale.result(pj(jet));
            }

            @Override
            public boolean isLarger(SplitMerge.Cjet a, SplitMerge.Cjet b) {
                return scale.isLarger(pj(a), pj(b));
            }

            private PseudoJet pj(SplitMerge.Cjet jet) {
                final PseudoJet j = new PseudoJet(jet.v.px, jet.v.py, jet.v.pz, jet.v.E);
                j.setStructure(new SISConeBasePlugin.StableConeStructure(cs, new ArrayList<>(jet.contents), jet.smVar2));
                return j;
            }
        });
    }

    public int computeJets(List<Cmomentum> particles, double radius, double f, int nPassMax, double ptmin, int scale) {
        return s.computeJets(particles, radius, f, nPassMax, ptmin, SplitMerge.Scale.values()[scale]);
    }

    public int computeJetsProgressiveRemoval(List<Cmomentum> particles, double radius, int nPassMax, double ptmin,
                                             int scale) {
        return s.computeJetsProgressiveRemoval(particles, radius, nPassMax, ptmin, SplitMerge.Scale.values()[scale]);
    }

    public int recomputeJets(double f, double ptmin, int scale) {
        return s.recomputeJets(f, ptmin, SplitMerge.Scale.values()[scale]);
    }

    public int njets() {
        return s.jets().size();
    }

    public List<Integer> contents(int ijet) {
        return s.jets().get(ijet).contents;
    }

    public int pass(int ijet) {
        return s.jets().get(ijet).pass;
    }

    public double mostAmbiguousSplit() {
        return s.mostAmbiguousSplit();
    }

    /** The protocones of each pass, as (px, py, pz, E). */
    public List<List<double[]>> protocones() {
        final List<List<double[]>> out = new ArrayList<>();
        for (List<Cmomentum> pass : s.protoconesList) {
            final List<double[]> l = new ArrayList<>(pass.size());
            for (Cmomentum c : pass) l.add(new double[]{c.px, c.py, c.pz, c.E});
            out.add(l);
        }
        return out;
    }
}
