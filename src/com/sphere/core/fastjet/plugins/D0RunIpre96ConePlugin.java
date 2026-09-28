package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;

/**
 * fastjet::D0RunIpre96ConePlugin, the D0 Run I cone before 1996: the jets'
 * four-momenta are rebuilt from the summed (Ex, Ey, Ez) of their items
 * rather than from Et-weighted angles. See {@link D0RunIBaseConePlugin}.
 */
public final class D0RunIpre96ConePlugin extends D0RunIBaseConePlugin {

    private static final String BANNER = String.join("\n",
        "#--------------------------------------------------------------------------",
        "# You are running the D0 Run I (pre96) Cone plugin for FastJet             ",
        "# Original code by the D0 collaboration, provided by Lars Sonnenschein;    ",
        "# interface by FastJet authors                                             ",
        "# If you use this plugin, please cite                                      ",
        "#   B. Abbott et al. [D0 Collaboration], FERMILAB-PUB-97-242-E.            ",
        "# in addition to the usual FastJet reference.                              ",
        "#--------------------------------------------------------------------------");

    public D0RunIpre96ConePlugin(double coneRad, double jetMinEt) {
        this(coneRad, jetMinEt, DEFAULT_SPLIFR);
    }

    public D0RunIpre96ConePlugin(double coneRad, double jetMinEt, double splitFraction) {
        super(coneRad, jetMinEt, splitFraction);
    }

    @Override
    public String description() {
        return "D0 Run I (pre 96) cone jet algorithm, with cone_radius = " + Fmt.g(coneRad) + ", min_jet_Et = "
            + Fmt.g(jetMinEt) + ", split_fraction = " + Fmt.g(splitFraction)
            + (fastjetOrder ? "" : " (parameters in D0 order)");
    }

    @Override
    protected EntityI newEntity(double e, double px, double py, double pz, int index) {
        return new EntityIpre96(e, px, py, pz, index);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("D0RunIpre96Cone", BANNER);
        runClusteringWorker(cs);
    }

    /** d0runi::HepEntityIpre96: also keeps the summed (Ex, Ey, Ez). */
    static final class EntityIpre96 extends EntityI {
        double ex;
        double ey;
        double ez;
        double phiPre96;
        double etaPre96;

        EntityIpre96(double eIn, double pxIn, double pyIn, double pzIn, int index) {
            super(eIn, pxIn, pyIn, pzIn, index);
            phiPre96 = phi;
            etaPre96 = eta;
            ex = et * CRMath.cos(phiPre96);
            ey = et * CRMath.sin(phiPre96);
            ez = et * CRMath.sinh(etaPre96);
        }

        EntityIpre96(EntityIpre96 o) {
            super(o);
            ex = o.ex;
            ey = o.ey;
            ez = o.ez;
            phiPre96 = o.phiPre96;
            etaPre96 = o.etaPre96;
        }

        @Override
        EntityI copy() {
            return new EntityIpre96(this);
        }

        @Override
        double px() {
            return et * CRMath.cos(phiPre96);
        }

        @Override
        double py() {
            return et * CRMath.sin(phiPre96);
        }

        @Override
        double pz() {
            return et * CRMath.sinh(etaPre96);
        }

        @Override
        double E() {
            return et * CRMath.cosh(etaPre96);
        }

        @Override
        void add(EntityI other) {
            final EntityIpre96 el = (EntityIpre96) other;
            super.add(el);
            ex += el.ex;
            ey += el.ey;
            ez += el.ez;
            phiPre96 = CRMath.atan2(ey, ex);
            final double thetaPre96 = CRMath.atan2(Math.sqrt(ex * ex + ey * ey), ez);
            etaPre96 = -CRMath.log(CRMath.tan(thetaPre96 / 2.));
        }
    }
}
