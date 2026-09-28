package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.PseudoJetStructure;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::SISConeBasePlugin: what the planar and spherical SISCone plugins
 * share: radius, overlap threshold, number of passes, caching, split-merge
 * stopping scale, progressive removal with an optional user ordering scale,
 * ghosted passive areas (with a ghost separation scale below which particles
 * seed no stable cone).
 */
public abstract class SISConeBasePlugin implements JetDefinition.Plugin {

    protected double coneRadius;
    protected double overlapThreshold;
    protected int nPassMax;
    protected boolean caching;
    protected double splitMergeStoppingScale;
    protected boolean useJetDefRecombiner;
    protected boolean progressiveRemoval;
    protected double ghostSepScale;
    protected UserScale userScale;

    /**
     * An ordering scale for the stable cones in progressive-removal mode,
     * SISConeBasePlugin::UserScaleBase. The jets it is given carry a
     * {@link StableConeStructure}.
     */
    public interface UserScale extends FunctionOfPseudoJet<Double> {
        default boolean isLarger(PseudoJet a, PseudoJet b) {
            return ((StableConeStructure) a.structure()).orderingVar2()
                > ((StableConeStructure) b.structure()).orderingVar2();
        }
    }

    /** The structure of a jet made from a stable cone: its constituents and ordering variable. */
    public static final class StableConeStructure implements PseudoJetStructure {
        private final ClusterSequence cs;
        private final List<Integer> contents;
        private final double orderingVar2;

        public StableConeStructure(ClusterSequence cs, List<Integer> contents, double orderingVar2) {
            this.cs = cs;
            this.contents = contents;
            this.orderingVar2 = orderingVar2;
        }

        @Override
        public String description() {
            return "PseudoJet wrapping a siscone jet from a stable cone";
        }

        @Override
        public boolean hasConstituents() {
            return true;
        }

        @Override
        public List<PseudoJet> constituents(PseudoJet reference) {
            final List<PseudoJet> out = new ArrayList<>(contents.size());
            for (int i : contents) out.add(cs.jet(i));
            return out;
        }

        public int size() {
            return contents.size();
        }

        public int constituentIndex(int i) {
            return contents.get(i);
        }

        public double orderingVar2() {
            return orderingVar2;
        }
    }

    public void setProgressiveRemoval(boolean b) {
        progressiveRemoval = b;
    }

    public boolean progressiveRemoval() {
        return progressiveRemoval;
    }

    public double coneRadius() {
        return coneRadius;
    }

    public double overlapThreshold() {
        return overlapThreshold;
    }

    public int nPassMax() {
        return nPassMax;
    }

    public void setSplitMergeStoppingScale(double scale) {
        splitMergeStoppingScale = scale;
    }

    public double splitMergeStoppingScale() {
        return splitMergeStoppingScale;
    }

    public void setUseJetDefRecombiner(boolean b) {
        useJetDefRecombiner = b;
    }

    public boolean useJetDefRecombiner() {
        return useJetDefRecombiner;
    }

    public boolean caching() {
        return caching;
    }

    public void setUserScale(UserScale s) {
        userScale = s;
    }

    public UserScale userScale() {
        return userScale;
    }

    @Override
    public double R() {
        return coneRadius;
    }

    @Override
    public boolean supportsGhostedPassiveAreas() {
        return true;
    }

    @Override
    public void setGhostSeparationScale(double scale) {
        ghostSepScale = scale;
    }

    @Override
    public double ghostSeparationScale() {
        return ghostSepScale;
    }

    /** SISConeBaseExtras: the stable cones, each jet's pass, the most ambiguous split. */
    public static class Extras {
        final List<PseudoJet> protocones = new ArrayList<>();
        final int[] pass;
        double mostAmbiguousSplit;
        SISConeBasePlugin plugin;

        Extras(int nparticles) {
            pass = new int[2 * nparticles];
            java.util.Arrays.fill(pass, -1);
        }

        /** The stable cones (protocones) of all passes, their user index being the pass. */
        public List<PseudoJet> stableCones() {
            return protocones;
        }

        public List<PseudoJet> protocones() {
            return protocones;
        }

        /** The pass in which a jet's stable cone was found. */
        public int pass(PseudoJet jet) {
            return pass[jet.clusterHistIndex()];
        }

        public double mostAmbiguousSplit() {
            return mostAmbiguousSplit;
        }

        public SISConeBasePlugin jetDefPlugin() {
            return plugin;
        }

        public String description() {
            return "This SISCone clustering found " + protocones.size() + " stable protocones";
        }
    }
}
