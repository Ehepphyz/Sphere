package com.sphere.core.fjcontrib.flavorcone;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cones of fixed radius around given seeds, fastjet::contrib::FlavorConePlugin
 * (FlavorCone 1.0.0; P. Ilten, N.L. Rodd, J. Thaler and M. Williams,
 * arXiv:1702.02947): every particle within rcut of its nearest seed
 * joins that seed's jet, overlapping cones being split by nearest neighbour.
 *
 * <p>With {@link Precision#DOUBLE} the assignment is the C++ one bit for bit.
 * In double-double the distances to the seeds are compared to 106 bits, and
 * {@link Extras#closestTie()} gives the smallest relative gap between a
 * particle's two nearest seeds, or between its distance and rcut: how close
 * the partition came to putting a particle elsewhere.
 */
public class FlavorConePlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("flavorcone");
    }

    private final double rcut;
    private final List<PseudoJet> seeds;

    /** The seed of each jet, FlavorConePlugin::Extras. */
    public static final class Extras {
        private static final LimitedWarning WARN_SEED = new LimitedWarning();
        private final Map<Integer, PseudoJet> seeds = new HashMap<>();
        private final PseudoJet invalidSeed = new PseudoJet(0, 0, 0, -1);
        private double closestTie = Double.POSITIVE_INFINITY;

        /** The seed the jet was built around; (0, 0, 0, -1) with a warning if none. */
        public PseudoJet seed(PseudoJet jet) {
            final PseudoJet s = seeds.get(jet.clusterHistIndex());
            if (s == null) {
                WARN_SEED.warn("FlavorConePlugin::Extras::seed: No seed associated with this jet, invalid seed with momentum (0, 0, 0, -1) returned.");
                return invalidSeed;
            }
            return s;
        }

        /**
         * The smallest relative gap, over the particles, between the squared
         * distances to the nearest and next-nearest seeds and between the
         * nearest one and rcut^2: a gap near zero means the partition turns
         * on a rounding.
         */
        public double closestTie() {
            return closestTie;
        }
    }

    public FlavorConePlugin(List<PseudoJet> seeds, double rcut) {
        this.rcut = rcut;
        this.seeds = new ArrayList<>(seeds);
    }

    @Override
    public String description() {
        return "FlavorCone plugin with " + seeds.size() + " seeds and rcut = " + Fmt.g(rcut);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final boolean dd = cs.precision() == Precision.DD;
        final Extras extras = new Extras();
        final int nprts = cs.jets().size();
        final int nseeds = seeds.size();
        final int[] jets = new int[nseeds];
        java.util.Arrays.fill(jets, -1);
        final double rcut2 = rcut * rcut;
        for (int iprt = 0; iprt < nprts; ++iprt) {
            final PseudoJet prt = cs.jet(iprt);
            int imin = -1;
            DD drmin = new DD(Double.POSITIVE_INFINITY, 0.0);
            DD second = new DD(Double.POSITIVE_INFINITY, 0.0);
            for (int iseed = 0; iseed < nseeds; ++iseed) {
                final DD dr = dd ? prt.squaredDistanceDD(seeds.get(iseed)) : new DD(prt.squaredDistance(seeds.get(iseed)), 0.0);
                if (dr.lt(drmin)) {
                    second = drmin;
                    drmin = dr;
                    imin = iseed;
                } else if (dr.lt(second)) {
                    second = dr;
                }
            }
            if (imin >= 0) {
                if (second.hi < Double.POSITIVE_INFINITY && second.hi > 0) {
                    extras.closestTie = Math.min(extras.closestTie, second.sub(drmin).hi / second.hi);
                }
                if (rcut2 > 0) extras.closestTie = Math.min(extras.closestTie, Math.abs(drmin.sub(rcut2).hi) / rcut2);
            }
            if (drmin.gt(rcut2)) continue;
            if (jets[imin] == -1) {
                jets[imin] = iprt;
            } else {
                jets[imin] = dd ? cs.pluginRecordIJRecombination(iprt, jets[imin], drmin)
                    : cs.pluginRecordIJRecombination(iprt, jets[imin], drmin.hi);
            }
        }
        for (int iseed = nseeds - 1; iseed >= 0; --iseed) {
            if (jets[iseed] != -1) {
                cs.pluginRecordIBRecombination(jets[iseed], iseed);
                extras.seeds.put(cs.jet(jets[iseed]).clusterHistIndex(), seeds.get(iseed));
            }
        }
        cs.pluginAssociateExtras(extras);
    }

    @Override public double R() { return rcut; }
    @Override public boolean exclusiveSequenceMeaningful() { return false; }
    @Override public boolean isSpherical() { return false; }
}
