package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;

/**
 * fastjet::D0RunIConePlugin, the D0 Run I cone as used from 1996 on: a jet's
 * direction is the Et-weighted mean of its items' (eta, phi). See
 * {@link D0RunIBaseConePlugin}.
 */
public final class D0RunIConePlugin extends D0RunIBaseConePlugin {

    private static final String BANNER = String.join("\n",
        "#--------------------------------------------------------------------------",
        "# You are running the D0 Run I Cone plugin for FastJet                     ",
        "# Original code provided by Lars Sonnenschein; interface by FastJet authors",
        "# If you use this plugin, please cite                                      ",
        "#   B. Abbott et al. [D0 Collaboration], FERMILAB-PUB-97-242-E.            ",
        "# in addition to the usual FastJet reference.                              ",
        "#--------------------------------------------------------------------------");

    public D0RunIConePlugin(double coneRad, double jetMinEt) {
        this(coneRad, jetMinEt, DEFAULT_SPLIFR);
    }

    public D0RunIConePlugin(double coneRad, double jetMinEt, double splitFraction) {
        super(coneRad, jetMinEt, splitFraction);
    }

    @Override
    public String description() {
        return "D0 Run I cone jet algorithm, with cone_radius = " + Fmt.g(coneRad) + ", min_jet_Et = "
            + Fmt.g(jetMinEt) + ", split_fraction = " + Fmt.g(splitFraction)
            + (fastjetOrder ? "" : " (parameters in D0 order)");
    }

    @Override
    protected EntityI newEntity(double e, double px, double py, double pz, int index) {
        return new EntityI(e, px, py, pz, index);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("D0RunICone", BANNER);
        runClusteringWorker(cs);
    }
}
